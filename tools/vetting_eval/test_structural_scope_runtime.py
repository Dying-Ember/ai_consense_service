"""Synthetic source/provenance boundaries, never actual evaluator answers."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import structural_scope_runtime as runtime
import source_unit_runtime as source
import span_comparison_runtime as spans


def chunk(identity, role, document, parts, owner='ZX12', heading=None):
    return {'id': identity, 'content': '\n'.join(part['text'] for part in parts), 'role': role,
            'fileKey': 'ZX' if role != 'project_fact' else 'OTHER', 'documentId': document,
            'fileName': identity + '.docx', 'anchor': parts[0]['anchor'], 'pageNo': None,
            'sourceHash': source.digest({'source': identity, 'parts': parts}), 'bbox': None,
            'parts': parts, 'clauseId': owner if role != 'project_fact' else None,
            'clauseHeadingLocation': heading, 'metadataVersion': 'synthetic-literal-owner'}


def part(text, block_id):
    return {'text': text, 'anchor': block_id.replace(':', '/'), 'pageNo': None,
            'blockId': block_id, 'startOffset': 0, 'endOffset': source.utf16_len(text), 'bbox': None}


def fixture(body=None, raw_ref='ZX12', reply='The delivery point is Depot Cedar.',
            request='Designer to confirm the delivery point.', headers=None, extra_rows=None, body_blocks=None):
    body = body or ['ZX12 Delivery arrangements', 'The delivery point is Depot Cedar after written authorization.']
    tender_parts = [part(text, body_blocks[index] if body_blocks else f'body:{index+1}:paragraph') for index, text in enumerate(body)]
    tender = chunk('tender', 'tender', '10', tender_parts, heading='body/1/paragraph')
    headers = headers or ['Clause', 'Required input', 'Reply']
    row = {'Clause': raw_ref, 'Required input': request, 'Reply': reply}
    data_rows = [[row.get(header, 'extra') for header in headers]] + (extra_rows or [])
    native_parts = []
    for index, cells in enumerate([headers] + data_rows):
        item = part(' | '.join(cells), f'body:7:table-row:{index}')
        item['table'] = {'cells': cells, 'headers': headers, 'tableLocation': 'body/7',
                         'headerBlockId': 'body:7:table-row:0', 'headerLocation': 'body/7/table-row/0', 'rowIndex': index}
        native_parts.append(item)
    mail = chunk('mail', 'project_fact', '20', native_parts, owner=None)
    chunks = [tender, mail]
    binding = {'ordinal': 1, 'productionPrompt': {'referenceIds': ['ZX12']}, 'fullOriginalChunks': chunks}
    base = source.build_catalog(chunks, ['ZX12'])
    catalog = spans.build_span_catalog(chunks, base)
    structure = runtime.make_catalog(binding, catalog)
    tasks = runtime.build_tasks(binding, catalog, structure)
    return binding, catalog, structure, tasks


def example_record(task, structure):
    left = next(identity for identity in task['leftBodyFragmentIds']
                if 'Depot Cedar' in next(f['rawText'] for f in structure['fragments'] if f['fragmentId'] == identity))
    right = task['rightPopulatedReplyFragmentIds'][0]
    return {'taskId': task['taskId'], 'attributeClaim': 'delivery point',
            'left': {'valueFragmentId': left, 'objectFragmentIds': [left], 'purposeFragmentIds': [left],
                     'condition': {'claim': 'unknown', 'fragmentIds': []}, 'adoption': {'claim': 'unknown', 'fragmentIds': []}},
            'right': {'valueFragmentId': right, 'objectFragmentIds': [right], 'purposeFragmentIds': [right],
                      'condition': {'claim': 'unknown', 'fragmentIds': []}, 'adoption': {'claim': 'unknown', 'fragmentIds': []}},
            'objectPurposeClaim': 'same', 'relation': 'match', 'differenceKind': 'none',
            'assessedScopeClaim': 'named_submitted_branch_only', 'reason': 'Bounded source value relation; adoption is not established.'}


class StructuralSourceTest(unittest.TestCase):
    def test_heading_alone_cannot_be_core_and_classification_is_hypothesis(self):
        _, _, structure, tasks = fixture()
        titles = [f for f in structure['fragments'] if f['structureKind'] == 'title_context']
        self.assertTrue(titles)
        self.assertTrue(all(f['fragmentId'] not in tasks[0]['leftBodyFragmentIds'] for f in titles))
        self.assertTrue(all(f['classificationSemanticallyVerified'] == 'unknown' for f in titles))

    def test_title_body_same_part_retains_exact_body_and_full_original(self):
        binding, catalog, structure, tasks = fixture(body=['ZX12 Delivery arrangements\r\nThe delivery point is Depot Cedar.'])
        core = [f for f in structure['fragments'] if f['fragmentId'] in tasks[0]['leftBodyFragmentIds']]
        self.assertTrue(any(f['rawText'] == 'The delivery point is Depot Cedar.' for f in core))
        self.assertFalse(any(f['rawText'].startswith('ZX12') for f in core))
        self.assertEqual('ZX12 Delivery arrangements\r\nThe delivery point is Depot Cedar.', binding['fullOriginalChunks'][0]['content'])
        rendered = spans.render_sources(binding['fullOriginalChunks'], catalog)
        self.assertEqual(binding['fullOriginalChunks'][0]['content'], ''.join(x['text'] for x in rendered[0]['contentSegments']))

    def test_heading_metadata_does_not_suppress_explicit_body_obligation(self):
        _, _, structure, tasks = fixture(body=['ZX12 Delivery shall occur after written authorization.'])
        self.assertTrue(tasks[0]['leftBodyFragmentIds'])
        self.assertFalse(any(f['structureKind'] == 'title_context' for f in structure['fragments']))

    def test_package_header_story_only_context(self):
        binding, catalog, _, _ = fixture()
        unit = next(u for u in catalog['units'] if u['role'] == 'tender')
        old_id = unit['blockId']
        for item in binding['fullOriginalChunks'][0]['parts']:
            if item['blockId'] == old_id:
                item['blockId'] = 'header:1:paragraph'; item['anchor'] = 'header/1/paragraph'
        unit['blockId'] = 'header:1:paragraph'; unit['anchor'] = 'header/1/paragraph'
        unit['coverages'][0]['anchor'] = 'header/1/paragraph'
        structure = runtime.make_catalog(binding, catalog)
        matching = [f for f in structure['fragments'] if f['unitId'] == unit['unitId']]
        self.assertTrue(all(not f['coreEligible'] for f in matching))

    def test_request_and_header_never_reply_value(self):
        _, catalog, structure, tasks = fixture()
        kinds = {u['unitId']: u['kind'] for u in catalog['units']}
        right = [f for f in structure['fragments'] if f['fragmentId'] in tasks[0]['rightPopulatedReplyFragmentIds']]
        self.assertTrue(right)
        self.assertTrue(all(kinds[f['unitId']] == 'project_reply' for f in right))
        self.assertTrue(all(not f['coreEligible'] for f in structure['fragments'] if kinds[f['unitId']] in ['header_context', 'request_context', 'clause_context']))

    def test_blank_whitespace_and_missing_column_stay_distinct(self):
        for reply, headers, state, length in [('', None, 'blank', 0), ('  ', None, 'blank', 2),
                                               (None, ['Clause', 'Required input'], 'no_reply_column', None)]:
            _, catalog, _, tasks = fixture(reply=reply, headers=headers)
            self.assertEqual([state], [v['replyState'] for v in tasks[0]['versions']])
            self.assertEqual([], tasks[0]['rightPopulatedReplyFragmentIds'])
            units = [u for u in catalog['units'] if u['kind'] == 'blank_reply']
            if length is None:
                self.assertEqual([], units)
            else:
                self.assertEqual(length, units[0]['originalEndUtf16'] - units[0]['originalStartUtf16'])

    def test_populated_negative_kept_when_another_row_blank(self):
        _, _, structure, tasks = fixture(reply='The modular units are not used.', extra_rows=[['ZX12', 'Designer to confirm the delivery point.', '']])
        self.assertEqual({'populated', 'blank'}, {v['replyState'] for v in tasks[0]['versions']})
        self.assertTrue(any(f['rawText'] == 'The modular units are not used.' for f in structure['fragments'] if f['fragmentId'] in tasks[0]['rightPopulatedReplyFragmentIds']))
        self.assertEqual('unknown', tasks[0]['adoptionVerified'])

    def test_sibling_numbered_body_not_candidate_for_other_reference(self):
        _, _, structure, tasks = fixture(body=['ZX12 Delivery arrangements', '(1) Plans are at Depot Cedar.', '(2) Records are at Depot Hazel.'],
                                         raw_ref='ZX12(1), (2)', reply='For ZX12(1), plans are at Depot Cedar.\nFor ZX12(2), records are at Depot Hazel.')
        self.assertEqual(2, len(tasks))
        by_id = {f['fragmentId']: f for f in structure['fragments']}
        first = next(t for t in tasks if t['referenceBranch'] == 'ZX12(1)')
        self.assertFalse(any('Depot Hazel' in by_id[x]['rawText'] for x in first['leftBodyFragmentIds']))
        self.assertFalse(any('Depot Hazel' in by_id[x]['rawText'] for x in first['rightPopulatedReplyFragmentIds']))

    def test_observed_same_unit_two_marker_counterexample(self):
        original = '(1) First body.\n(2) Second body.'
        binding, catalog, structure, tasks = fixture(body=['ZX12 Delivery arrangements', original],
                                                    raw_ref='ZX12(2)')
        fragments = {f['fragmentId']: f for f in structure['fragments']}
        candidate = next(f for f in fragments.values() if f['rawText'] == '(2) Second body.')
        self.assertEqual('ZX12(2)', candidate['parsedReference'])
        self.assertIn(candidate['fragmentId'], tasks[0]['leftBodyFragmentIds'])
        self.assertFalse(any('First body.' in fragments[x]['rawText'] for x in tasks[0]['leftBodyFragmentIds']))
        whole = next(f for f in fragments.values() if f['rawText'] == original)
        self.assertIsNone(whole['parsedReference'])
        self.assertFalse(whole['coreEligible'])
        self.assertEqual('cross_scope_unknown', whole['referenceRoutingState'])
        self.assertIn(whole['fragmentId'], tasks[0]['supportFragmentIds'])
        self.assertEqual(original, runtime.fragment_evidence(whole, catalog, binding['fullOriginalChunks'])['selectedText'])

    def test_numbered_segments_keep_continuations_conditions_and_xrefs(self):
        original = '(1) First body.\nOnly after written approval.\n(2) Second body.\nRefer to ZX80 for the exception.'
        _, _, structure, tasks = fixture(body=['ZX12 Delivery arrangements', original],
                                         raw_ref='ZX12(1), (2)',
                                         reply='For ZX12(1), first response.\nFor ZX12(2), second response.')
        first = next(t for t in tasks if t['referenceBranch'] == 'ZX12(1)')
        second = next(t for t in tasks if t['referenceBranch'] == 'ZX12(2)')
        by_id = {f['fragmentId']: f for f in structure['fragments']}
        self.assertTrue(any(by_id[x]['rawText'] == 'Only after written approval.' for x in first['leftBodyFragmentIds']))
        self.assertTrue(any(by_id[x]['rawText'] == 'Refer to ZX80 for the exception.' for x in second['leftBodyFragmentIds']))
        self.assertFalse(any('Second body.' in by_id[x]['rawText'] for x in first['leftBodyFragmentIds']))
        note = next(u for u in structure['units'] if u['parsedReferencesObserved'] == ['ZX12(1)', 'ZX12(2)'])
        self.assertEqual(['ZX80'], note['rawCrossReferences'])
        self.assertTrue(all(set(by_id) == set(task['supportFragmentIds']) for task in tasks))

    def test_nested_markers_crlf_and_surrogate_ranges_remain_exact(self):
        original = '(1)(a) A📦 first body.\r\nCondition A.\r\n(2) Second body.'
        binding, catalog, structure, tasks = fixture(body=['ZX12 Delivery arrangements', original], raw_ref='ZX12(2)')
        by_text = {f['rawText']: f for f in structure['fragments']}
        self.assertEqual('ZX12(1)(A)', by_text['Condition A.']['parsedReference'])
        second = by_text['(2) Second body.']
        self.assertEqual('ZX12(2)', second['parsedReference'])
        self.assertIn(second['fragmentId'], tasks[0]['leftBodyFragmentIds'])
        for fragment in structure['fragments']:
            evidence = runtime.fragment_evidence(fragment, catalog, binding['fullOriginalChunks'])
            self.assertTrue(evidence['literalBindingPassed'])

    def test_unnumbered_parent_does_not_inherit_prior_item_one(self):
        _, _, structure, tasks = fixture(body=['ZX12 Delivery arrangements', '(1) First body.',
                                              'Parent exception refers to ZX80.', '(a) Anonymous branch.'], raw_ref='ZX12(2)')
        parent = next(f for f in structure['fragments'] if f['rawText'] == 'Parent exception refers to ZX80.')
        anonymous = next(f for f in structure['fragments'] if f['rawText'] == '(a) Anonymous branch.')
        self.assertEqual('ZX12', parent['parsedReference'])
        self.assertFalse(parent['numericParentKnown'])
        self.assertEqual('ZX12(A)', anonymous['parsedReference'])
        self.assertFalse(anonymous['numericParentKnown'])
        self.assertIn(parent['fragmentId'], tasks[0]['supportFragmentIds'])

    def test_table_row_multiline_markers_use_ranges_inline_cells_stay_unknown(self):
        for body in ['(1) First body.\n(2) Second body.', '(1) First body. | (2) Second body.']:
            binding, catalog, structure, tasks = fixture(body=['ZX12 Delivery arrangements', body],
                body_blocks=['body:1:paragraph', 'body:9:table-row:1'], raw_ref='ZX12(2)')
            row_fragments = [f for f in structure['fragments'] if 'First body.' in f['rawText'] or 'Second body.' in f['rawText']]
            if '\n' in body:
                second = next(f for f in row_fragments if f['rawText'] == '(2) Second body.')
                self.assertIn(second['fragmentId'], tasks[0]['leftBodyFragmentIds'])
            else:
                self.assertTrue(all(not f['coreEligible'] and f['parsedReference'] is None for f in row_fragments))
                self.assertTrue(all(f['fragmentId'] in tasks[0]['supportFragmentIds'] for f in row_fragments))
            self.assertEqual(body, binding['fullOriginalChunks'][0]['parts'][1]['text'])
            self.assertTrue(all(runtime.fragment_evidence(f, catalog, binding['fullOriginalChunks'])['literalBindingPassed'] for f in row_fragments))

    def test_numbered_reply_does_not_borrow_tender_parent_or_guess_cell_purpose(self):
        binding, catalog, structure, tasks = fixture(body=['ZX12 Delivery arrangements', '(1) First body.', '(2) Second body.'],
            raw_ref='ZX12(1), (2)', reply='(1) First response.\n(2) Second response.')
        self.assertTrue(all(task['unresolvedReplyBranch'] and not task['rightPopulatedReplyFragmentIds'] for task in tasks))
        for fragment in structure['fragments']:
            if fragment['role'] == 'project_fact':
                self.assertIsNone(fragment['parsedReference'])
                self.assertFalse(fragment['numericParentKnown'])
        self.assertEqual(catalog['nativeContexts'], structure['nativeContexts'])
        self.assertTrue(all(runtime.fragment_evidence(f, catalog, binding['fullOriginalChunks'])['literalBindingPassed'] for f in structure['fragments']))

    def test_anonymous_letter_does_not_invent_numeric_parent(self):
        _, _, structure, _ = fixture(body=['ZX12 Delivery arrangements', '(b) Not used.'])
        note = next(u for u in structure['units'] if u['explicitRootMarker'] == '(b)')
        self.assertFalse(note['numericParentKnown'])
        self.assertEqual(['B'], note['literalItemPath'])
        self.assertNotIn('(1)', note['parsedReference'])
        self.assertEqual('unknown', note['referenceMeaningVerified'])

    def test_prose_number_and_defined_style_do_not_become_parent(self):
        _, _, structure, _ = fixture(body=['ZX12 Delivery arrangements', 'Return 4 sets of records.'])
        note = next(u for u in structure['units'] if u['role'] == 'tender' and not u['headingLocationMatched'])
        self.assertFalse(note['numericParentKnown'])
        self.assertIsNone(note['explicitRootMarker'])

    def test_shared_qualifier_and_preamble_remain_unassigned_not_lost(self):
        text = 'Prior written approval applies.\nFor ZX12(1), plans are at Depot Cedar.\nFor ZX12(2), records are at Depot Hazel.\nBoth are available only by appointment.'
        branches, warnings = runtime.explicit_reply_branches(text, ['ZX12(1)', 'ZX12(2)'])
        self.assertEqual(2, len(branches))
        self.assertTrue(all('Both are' not in source.utf16_slice(text, b['startUtf16'], b['endUtf16']) for b in branches))
        self.assertIn('reply_preamble_retained_unassigned_context', warnings)
        self.assertTrue(any('association_unknown' in warning for warning in warnings))
        _, _, structure, tasks = fixture(raw_ref='ZX12(1), (2)', reply=text)
        qualifier = next(f for f in structure['fragments'] if f['rawText'] == 'Both are available only by appointment.')
        self.assertEqual([], qualifier['referenceBranches'])
        self.assertFalse(qualifier['coreEligible'])
        self.assertTrue(all(qualifier['fragmentId'] in t['supportFragmentIds'] for t in tasks))

    def test_unlabelled_or_duplicate_multireference_reply_no_hard_mapping(self):
        for text in ['Depot Cedar; extension 555.', 'For ZX12(1), Depot Cedar.\nFor ZX12(1), Depot Hazel.']:
            _, _, _, tasks = fixture(raw_ref='ZX12(1), (2)', reply=text)
            self.assertEqual(2, len(tasks))
            self.assertTrue(all(t['unresolvedReplyBranch'] and not t['rightPopulatedReplyFragmentIds'] for t in tasks))

    def test_reference_boundary_does_not_confuse_twelve_and_hundredtwenty(self):
        branches, _ = runtime.explicit_reply_branches('For ZX120(1), wrong parent.\nFor ZX12(2), correct parent.', ['ZX12(1)', 'ZX12(2)'])
        self.assertEqual([], branches)

    def test_utf16_surrogate_newline_and_wrong_source_span_fail_closed(self):
        binding, catalog, structure, _ = fixture(body=['ZX12 Delivery arrangements', 'A📦B\r\nThe delivery point is Depot Cedar.'])
        full = next(f for f in structure['fragments'] if f['rawText'].startswith('A📦B\r\n'))
        evidence = runtime.fragment_evidence(full, catalog, binding['fullOriginalChunks'])
        self.assertEqual(full['rawText'], source.utf16_slice(binding['fullOriginalChunks'][0]['content'], evidence['selectedChunkStartUtf16'], evidence['selectedChunkEndUtf16']))
        broken = copy.deepcopy(full); broken['startInUnitUtf16'] = 2
        with self.assertRaises((ValueError, UnicodeDecodeError)):
            runtime.fragment_evidence(broken, catalog, binding['fullOriginalChunks'])
        broken = copy.deepcopy(full); broken['rawText'] = 'A📦B\nThe delivery point is Depot Cedar.'
        with self.assertRaisesRegex(ValueError, 'fragment_text'):
            runtime.fragment_evidence(broken, catalog, binding['fullOriginalChunks'])

    def test_changed_namespace_rejected_even_same_fragment_id(self):
        binding, catalog, structure, tasks = fixture()
        task = copy.deepcopy(tasks[0]); task['structuralCatalogHash'] = 'different'
        with self.assertRaisesRegex(ValueError, 'namespace'):
            runtime.validate([], task, structure, catalog, binding['fullOriginalChunks'], {})


class ComparisonBoundaryTest(unittest.TestCase):
    def test_match_with_value_and_separate_support_is_only_mechanical(self):
        binding, catalog, structure, tasks = fixture()
        record = example_record(tasks[0], structure)
        result = runtime.validate([record], tasks[0], structure, catalog, binding['fullOriginalChunks'], {})
        self.assertTrue(result['records'][0]['mechanicalGatePassed'])
        self.assertIsNone(result['records'][0]['semanticAccepted'])
        self.assertFalse(result['records'][0]['overallApproval'])
        self.assertEqual('unknown', result['records'][0]['modelClaims']['objectPurpose']['independentlyVerified'])

    def test_title_id_rejected_preserves_raw_claim(self):
        binding, catalog, structure, tasks = fixture()
        record = example_record(tasks[0], structure)
        record['left']['valueFragmentId'] = next(f['fragmentId'] for f in structure['fragments'] if f['structureKind'] == 'title_context')
        result = runtime.validate([record], tasks[0], structure, catalog, binding['fullOriginalChunks'], {})
        self.assertTrue(result['schemaErrors'])
        self.assertEqual([record], result['rawRecords'])
        self.assertIsNone(result['semanticAccepted'])

    def test_same_substring_warning_keeps_condition_or_value_difference(self):
        binding, catalog, structure, tasks = fixture(body=['ZX12 Delivery arrangements', 'The delivery point is Depot Cedar.'], reply='The delivery point is Depot Cedar.')
        record = example_record(tasks[0], structure)
        left = next(f['fragmentId'] for f in structure['fragments'] if f['role'] == 'tender' and f['rawText'] == 'Depot Cedar.')
        right = next(f['fragmentId'] for f in structure['fragments'] if f['role'] == 'project_fact' and f['rawText'] == 'Depot Cedar.' and f['coreEligible'])
        record['left']['valueFragmentId'] = left; record['right']['valueFragmentId'] = right
        record['relation'] = 'difference'
        for kind in ['condition_difference', 'value_difference']:
            record['differenceKind'] = kind
            result = runtime.validate([record], tasks[0], structure, catalog, binding['fullOriginalChunks'], {})['records'][0]
            self.assertTrue(result['mechanicalGatePassed'])
            self.assertEqual(record, result['rawRecord'])
            self.assertTrue(result['warnings'])
            self.assertIsNone(result['semanticAccepted'])

    def test_nulls_blank_and_empty_records_do_not_prove_absence_or_success(self):
        binding, catalog, structure, tasks = fixture()
        record = example_record(tasks[0], structure)
        record['left']['valueFragmentId'] = None; record['right']['valueFragmentId'] = None
        result = runtime.validate([record], tasks[0], structure, catalog, binding['fullOriginalChunks'], {})['records'][0]
        self.assertFalse(result['mechanicalGatePassed'])
        self.assertFalse(result['nullMeansMissing'])
        empty = runtime.validate([], tasks[0], structure, catalog, binding['fullOriginalChunks'], {})
        self.assertEqual([], empty['records']); self.assertIsNone(empty['semanticAccepted'])

    def test_relation_consistency_and_claimed_support_required(self):
        binding, catalog, structure, tasks = fixture()
        record = example_record(tasks[0], structure)
        record['differenceKind'] = 'value_difference'; record['left']['adoption']['claim'] = 'supported'
        record['right']['objectFragmentIds'] = []
        result = runtime.validate([record], tasks[0], structure, catalog, binding['fullOriginalChunks'], {})['records'][0]
        self.assertIn('match_requires_difference_kind_none', result['reasons'])
        self.assertIn('left_adoption_claimed_without_source_support', result['reasons'])
        self.assertIn('right_object_or_purpose_source_support_missing', result['reasons'])
        self.assertEqual(record, result['rawRecord'])

    def test_model_replaceable_but_schema_sources_unchanged(self):
        binding, catalog, structure, tasks = fixture()
        old = runtime.request_body(tasks[0], structure, catalog, binding['fullOriginalChunks'])
        new = runtime.request_body(tasks[0], structure, catalog, binding['fullOriginalChunks'], model='future-local:larger')
        expected = copy.deepcopy(old); expected['model'] = 'future-local:larger'
        self.assertEqual(expected, new)
        self.assertEqual({'num_ctx': 32768, 'temperature': .2, 'num_predict': 4096}, old['options'])

    def test_preflight_failure_records_zero_attempts_without_http(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); inputs = root/'inputs'; inputs.mkdir()
            source.save(inputs/'runtime_manifest.json', {'experimentId': 'synthetic-baseline'})
            with patch('requests.get', side_effect=AssertionError('HTTP forbidden')) as http:
                with self.assertRaisesRegex(ValueError, 'manifest_identity'):
                    runtime.prepare(inputs, root/'prepared', root/'registry', expected_manifest_sha='0'*64)
                http.assert_not_called()
            failure = json.loads((root/'prepared/bootstrap_failure.json').read_bytes())
            self.assertEqual(0, failure['measurements']['actualAttempts'])
            events = list((root/'registry/records/prepared').glob('*.json'))
            self.assertEqual(1, len(events))
            self.assertEqual('failed', json.loads(events[0].read_bytes())['eventType'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
