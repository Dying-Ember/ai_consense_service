"""Small synthetic offline checks; no gold fixture, real HTTP or model calls."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import span_comparison_runtime as runtime
import source_unit_runtime as source
from test_source_unit_runtime import chunk, table_chunk, schema


def catalog(chunks):
    return runtime.build_span_catalog(chunks, source.build_catalog(chunks, ['ZZ9']))


def select(unit, text=None):
    return {'unitId': unit['unitId'], 'rawValue': unit['rawText'] if text is None else text}


def first(catalogue, kind):
    return next(unit for unit in catalogue['units'] if unit['kind'] == kind)


def relation(catalogue, *, kind='value_difference', result='difference', left_value='Alpha', right_value='Beta'):
    left = first(catalogue, 'tender')
    right = first(catalogue, 'project_reply')
    def side(unit, value):
        return {'value': select(unit, value), 'objectEvidence': [select(unit)], 'purposeEvidence': [select(unit)],
                'condition': {'claim': 'unknown', 'evidence': []},
                'adoptionApplicability': {'claim': 'unknown', 'evidence': []}}
    return {'atomicAttribute': {'label': 'One named atomic attribute', 'evidence': [select(left)]},
            'left': side(left, left_value), 'right': side(right, right_value), 'objectPurposeRelation': 'same',
            'relation': result, 'differenceKind': kind, 'requiredFieldEvidence': [], 'assessedScopeUnitIds': [],
            'scopeClaim': 'not_established', 'reason': 'Synthetic local claim, not a quality conclusion.'}


class CatalogAndSpanTest(unittest.TestCase):
    def test_zero_blank_whitespace_and_no_column_preserve_exact_context(self):
        filled = table_chunk(data=[['ZZ9', 'First requested field', 'Filled'], ['ZZ9', 'Second request', ''],
                                   ['ZZ9', 'Whitespace request', ' \n']])
        no_column = table_chunk(labels=['Clause', 'Required input'], data=[['ZZ9', 'The absent-column request']],
                                identity='no_column', document='3')
        result = catalog([filled, no_column])
        blanks = [unit for unit in result['units'] if unit['kind'] == 'blank_reply']
        self.assertEqual([unit['rawText'] for unit in blanks], ['', ' \n'])
        self.assertEqual(blanks[0]['originalStartUtf16'], blanks[0]['originalEndUtf16'])
        self.assertEqual(blanks[1]['originalEndUtf16'] - blanks[1]['originalStartUtf16'], 2)
        self.assertFalse(any(unit['kind'] in ('blank_reply', 'project_reply') and unit['documentId'] == '3'
                             for unit in result['units']))
        self.assertTrue(any(unit['kind'] == 'request_context' and unit['documentId'] == '3' for unit in result['units']))
        self.assertIn('no_reply_column', [row['replyState'] for row in result['nativeContexts']])

    def test_zero_length_selector_is_only_real_zero_length_reply(self):
        chunks = [table_chunk(data=[['ZZ9', 'A requested field', ''], ['ZZ9', 'Other requested field', ' ']])]
        result = catalog(chunks)
        blank, whitespace = [unit for unit in result['units'] if unit['kind'] == 'blank_reply']
        bound = runtime.bind_selector(select(blank), result, chunks)
        self.assertEqual(bound['valueStartUtf16'], bound['valueEndUtf16'])
        self.assertIn('A requested field', bound['quote'])
        for unit in [whitespace, first(result, 'request_context'), first(result, 'header_context')]:
            with self.assertRaisesRegex(ValueError, 'zero_length'):
                runtime.bind_selector(select(unit, ''), result, chunks)
        self.assertEqual(runtime.bind_selector(select(whitespace), result, chunks)['rawValue'], ' ')

    def test_extra_columns_reordered_reply_literal_pipe_and_surrogates(self):
        chunks = [table_chunk(labels=['Actor', 'Reply', 'Clause', 'Required input'],
                               data=[['Author', 'North | West 😀', 'ZZ9', 'Named requested field']])]
        result = catalog(chunks)
        reply = first(result, 'project_reply')
        bound = runtime.bind_selector(select(reply, 'West 😀'), result, chunks)
        self.assertEqual(bound['cellIndex'], 1)
        self.assertEqual(source.utf16_slice(chunks[0]['content'], bound['valueChunkStartUtf16'], bound['valueChunkEndUtf16']), 'West 😀')
        self.assertEqual(bound['valueEndUtf16'] - bound['valueStartUtf16'], 7)

    def test_project_paragraphs_are_selectable_untyped_context_not_reply(self):
        paragraph = chunk('paragraph', 'The visible untyped original paragraph.', role='project_fact', document='9')
        result = catalog([paragraph])
        unit = first(result, 'untyped_project_context')
        self.assertFalse(unit['valueEligible'])
        self.assertTrue(runtime.bind_selector(select(unit), result, [paragraph])['literalSpanValidated'])

    def test_wrong_native_header_does_not_become_reply_or_cell_context(self):
        item = table_chunk()
        item['parts'][1]['table']['headerBlockId'] = 'body:99:table-row:0'
        result = catalog([item])
        self.assertFalse(any(unit['kind'] in ('project_reply', 'blank_reply', 'request_context') for unit in result['units']))
        self.assertTrue(any(unit['kind'] == 'untyped_project_context' for unit in result['units']))

    def test_unit_coverage_tamper_fails_closed(self):
        chunks = [chunk('tender', 'A continuous original value.')]
        result = catalog(chunks)
        result['units'][0]['originalStartUtf16'] += 1
        result['units'][0]['originalEndUtf16'] += 1
        with self.assertRaises(ValueError):
            runtime.audit_span_catalog(result, chunks)

    def test_nonliteral_or_ambiguous_value_is_not_first_match(self):
        chunks = [chunk('tender', 'Echo and Echo are two occurrences.')]
        result = catalog(chunks)
        unit = first(result, 'tender')
        for value in ['Echo', 'echo', 'Echo ... occurrences']:
            with self.assertRaises(ValueError):
                runtime.bind_selector(select(unit, value), result, chunks)

    def test_disjoint_parts_are_never_stitched(self):
        chunks = [chunk('one', 'First original fragment.', block='body:7', start=0),
                  chunk('two', 'Second original fragment.', block='body:7', start=100)]
        result = catalog(chunks)
        for unit in result['units']:
            with self.assertRaises(ValueError):
                runtime.bind_selector(select(unit, 'fragment.Second'), result, chunks)

    def test_stable_ids_and_duplicate_source_coverage(self):
        first_chunk = chunk('one', 'The stable original fragment.')
        duplicate = copy.deepcopy(first_chunk)
        duplicate['id'] = 'two'
        result = catalog([first_chunk, duplicate])
        self.assertEqual(len(result['units']), 1)
        self.assertEqual(len(result['units'][0]['coverages']), 2)
        self.assertEqual(result, catalog([first_chunk, duplicate]))

    def test_segmented_presentation_preserves_all_original_characters_and_zero_cells(self):
        chunks = [chunk('tender', 'First original paragraph.\nSecond original line 😀.'),
                  table_chunk(data=[['ZZ9', 'A named request', ''], ['ZZ9', 'A different request', ' \n']])]
        result = catalog(chunks)
        rendered = runtime.render_sources(chunks, result)
        for item, original in zip(rendered, chunks):
            self.assertEqual(''.join(segment['text'] for segment in item['contentSegments']).encode('utf-8'), original['content'].encode('utf-8'))
            self.assertEqual(item['originalContentSha256'], source.digest(original['content'].encode('utf-8')))
        zero_unit = next(unit for unit in result['units'] if unit['kind'] == 'blank_reply' and unit['rawText'] == '')
        zero_segments = [segment for item in rendered for segment in item['contentSegments'] if zero_unit['unitId'] in segment['unitIds']]
        self.assertEqual(zero_segments, [{'unitIds': [zero_unit['unitId']], 'text': ''}])


class ComparisonTest(unittest.TestCase):
    def setUp(self):
        self.chunks = [chunk('tender', 'The named Alpha value is supplied for this object.'),
                       table_chunk(data=[['ZZ9', 'Named field for this object', 'Beta is the supplied reply value.']])]
        self.catalog = catalog(self.chunks)

    def audit(self, records, catalogue=None, chunks=None, selection=None):
        return runtime.validate_comparisons(records, catalogue or self.catalog, chunks or self.chunks, selection or {})

    def test_program_binding_keeps_semantic_states_unverified(self):
        observed = self.audit([relation(self.catalog)])['records'][0]
        self.assertTrue(observed['literalSpanValidated'])
        self.assertTrue(observed['mechanicalGatePassed'])
        self.assertTrue(observed['candidateEligible'])
        self.assertEqual(observed['objectPurposeRelation'], {'modelClaim': 'same', 'verified': 'unknown'})
        self.assertEqual(observed['adoptionApplicability']['right']['verified'], 'unknown')
        self.assertIsNone(observed['semanticAccepted'])
        self.assertFalse(observed['overallApproval'])

    def test_extra_quote_chunk_role_fields_rejected_by_schema(self):
        for field in ('quote', 'chunkId', 'role'):
            record = relation(self.catalog)
            record['left']['value'][field] = 'fabricated'
            result = self.audit([record])
            self.assertTrue(result['schemaErrors'])
            self.assertEqual(result['candidateCount'], 0)

    def test_requested_or_header_cell_cannot_replace_populated_value(self):
        for kind in ('request_context', 'header_context', 'clause_context'):
            record = relation(self.catalog)
            record['right']['value'] = select(first(self.catalog, kind))
            result = self.audit([record])['records'][0]
            self.assertIn('right:context_cell_cannot_be_a_filled_value', result['reasons'])
            self.assertFalse(result['candidateEligible'])

    def test_context_only_project_evidence_does_not_relax_old_role_basis(self):
        record = relation(self.catalog)
        request = first(self.catalog, 'request_context')
        record['right']['value'] = None
        record['differenceKind'] = 'condition_difference'
        record['right']['objectEvidence'] = [select(request)]
        record['right']['purposeEvidence'] = [select(request)]
        record['right']['condition'] = {'claim': 'supported', 'evidence': [select(request)]}
        result = self.audit([record])['records'][0]
        self.assertIn('comparison_role_or_adoption_basis_missing', result['reasons'])
        self.assertFalse(result['candidateEligible'])

    def test_same_substring_warning_does_not_reject_any_semantic_claim(self):
        chunks = [chunk('tender', 'Alpha name with shared phone 123.'),
                  table_chunk(data=[['ZZ9', 'Name and phone of named object', 'Beta name with shared phone 123.']])]
        result = catalog(chunks)
        value_claim = relation(result, left_value='123', right_value='123')
        condition_claim = copy.deepcopy(value_claim)
        condition_claim['differenceKind'] = 'condition_difference'
        obligation_claim = copy.deepcopy(value_claim)
        obligation_claim['differenceKind'] = 'obligation_difference'
        name_claim = relation(result, left_value='Alpha', right_value='Beta')
        observed = self.audit([value_claim, condition_claim, obligation_claim, name_claim], result, chunks)['records']
        self.assertIn('selected_spans_literal_difference_unsupported', observed[0]['warnings'])
        self.assertFalse(observed[0]['equalValueObservation']['hardReject'])
        self.assertTrue(observed[0]['candidateEligible'])
        self.assertEqual(observed[0]['rawRecord'], value_claim)
        self.assertEqual(observed[0]['objectPurposeRelation']['verified'], 'unknown')
        self.assertTrue(all(item['candidateEligible'] for item in observed[1:]))
        self.assertTrue(all(item['equalValueObservation'] is None for item in observed[1:]))

    def test_equal_selected_substring_never_certifies_polarity_or_complete_value(self):
        chunks = [chunk('tender', 'The named item is used for this object.'),
                  table_chunk(data=[['ZZ9', 'Status for the named object', 'The named item is not used for this object.']])]
        result = catalog(chunks)
        record = relation(result, left_value='used', right_value='used')
        observed = self.audit([record], result, chunks)['records'][0]
        self.assertFalse(observed['equalValueObservation']['exactSelectedSubstringDifferenceSupported'])
        self.assertEqual(observed['equalValueObservation']['completeAtomicValueVerified'], 'unknown')
        self.assertIn('not used', observed['boundEvidence']['right/value/0']['quote'])
        self.assertIsNone(observed['semanticAccepted'])
        self.assertTrue(observed['mechanicalGatePassed'])
        self.assertEqual(observed['rawRecord'], record)
        record['differenceKind'] = 'condition_difference'
        self.assertIsNone(self.audit([record], result, chunks)['records'][0]['equalValueObservation'])

    def test_same_day_substring_different_months_preserves_full_source_claim(self):
        chunks = [chunk('tender', 'The named period ends on 21st January for this object.'),
                  table_chunk(data=[['ZZ9', 'Ending date for the named period', 'The named period ends on 21st February for this object.']])]
        result = catalog(chunks)
        record = relation(result, left_value='21st', right_value='21st')
        record['atomicAttribute']['label'] = 'Ending date'
        observed = self.audit([record], result, chunks)['records'][0]
        self.assertTrue(observed['mechanicalGatePassed'])
        self.assertTrue(observed['candidateEligible'])
        self.assertEqual(observed['rawRecord'], record)
        self.assertIn('selected_spans_literal_difference_unsupported', observed['warnings'])
        self.assertEqual(observed['equalValueObservation']['completeAtomicValueVerified'], 'unknown')
        self.assertIn('January', observed['boundEvidence']['left/value/0']['quote'])
        self.assertIn('February', observed['boundEvidence']['right/value/0']['quote'])

    def test_same_chunk_other_cell_value_cannot_be_bound_by_text_only(self):
        chunks = [chunk('tender', 'The named Alpha value is supplied for this object.'),
                  table_chunk(data=[['ZZ9', 'Requested 123 field', '123 reply value here.']])]
        result = catalog(chunks)
        record = relation(result, right_value='123')
        item = self.audit([record], result, chunks)['records'][0]
        bound = item['boundEvidence']['right/value/0']
        self.assertEqual(bound['kind'], 'project_reply')
        self.assertEqual(source.utf16_slice(chunks[1]['content'], bound['valueChunkStartUtf16'], bound['valueChunkEndUtf16']), '123')
        request_unit = first(result, 'request_context')
        self.assertGreater(bound['valueStartUtf16'], request_unit['originalEndUtf16'])

    def test_blank_missing_is_bounded_and_cannot_claim_scope_or_adoption(self):
        chunks = [chunk('tender', 'The named Alpha value is supplied for this object.'),
                  table_chunk(data=[['ZZ9', 'Named field explicitly requested', ''],
                                    ['ZZ9', 'A populated answer elsewhere', 'A completed answer exists.']])]
        result = catalog(chunks)
        record = relation(result)
        blank = first(result, 'blank_reply')
        record['differenceKind'] = 'missing_expected'
        record['right']['value'] = select(blank)
        request = first(result, 'request_context')
        record['requiredFieldEvidence'] = [select(request)]
        record['assessedScopeUnitIds'] = [blank['unitId']]
        record['scopeClaim'] = 'named_submitted_units_only'
        observed = self.audit([record], result, chunks)['records'][0]
        self.assertTrue(observed['literalSpanValidated'])
        self.assertFalse(observed['candidateEligible'])
        self.assertFalse(observed['missingExpectedObservation']['globalAbsenceEstablished'])
        self.assertEqual(observed['missingExpectedObservation']['requirementAdoptionVerified'], 'unknown')

    def test_standard_requirement_does_not_self_prove_adoption(self):
        standard = chunk('standard', 'A generic field shall be completed for the stated purpose.', role='standard', document='4')
        chunks = self.chunks + [standard]
        result = catalog(chunks)
        record = relation(result, kind='missing_expected')
        record['requiredFieldEvidence'] = [select(first(result, 'standard'))]
        record['assessedScopeUnitIds'] = [unit['unitId'] for unit in result['units']]
        record['scopeClaim'] = 'named_submitted_units_only'
        observed = self.audit([record], result, chunks)['records'][0]
        self.assertFalse(observed['candidateEligible'])
        self.assertEqual(observed['missingExpectedObservation']['requirementAdoptionVerified'], 'unknown')

    def test_supported_claim_without_original_span_is_rejected_not_certified(self):
        record = relation(self.catalog)
        record['left']['adoptionApplicability']['claim'] = 'supported'
        observed = self.audit([record])['records'][0]
        self.assertIn('left:adoptionApplicability_supported_without_original_evidence', observed['reasons'])
        self.assertEqual(observed['adoptionApplicability']['left']['verified'], 'unknown')

    def test_unresolved_quoted_chunk_and_unknown_identity_fail_closed(self):
        record = relation(self.catalog)
        observed = self.audit([record], selection={'unresolvedComparisonIds': ['mail']})['records'][0]
        self.assertIn('quoted_comparison_target_unresolved', observed['reasons'])
        record['right']['value']['unitId'] = 'not-a-real-unit'
        self.assertTrue(self.audit([record])['schemaErrors'])

    def test_local_match_never_becomes_overall_approval(self):
        chunks = [chunk('tender', 'Alpha supplied for this named object.'),
                  table_chunk(data=[['ZZ9', 'The field for this named object', 'Alpha supplied in reply.']])]
        result = catalog(chunks)
        observed = self.audit([relation(result, kind='none', result='match', left_value='Alpha', right_value='Alpha')], result, chunks)['records'][0]
        self.assertTrue(observed['mechanicalGatePassed'])
        self.assertFalse(observed['candidateEligible'])
        self.assertTrue(observed['localRelationOnly'])
        self.assertFalse(observed['overallApproval'])


class OfflineFlowTest(unittest.TestCase):
    def prepared(self, root):
        chunks = [chunk('tender', 'The named Alpha value is supplied for this object.'),
                  table_chunk(data=[['ZZ9', 'Named field for this object', 'Beta supplied as original reply.']])]
        inputs = []
        for ordinal in range(1, 4):
            body = {'model': 'qwen2.5:3b', 'stream': False, 'options': source.OPTIONS,
                    'messages': [{'role': 'system', 'content': 'Generic production system.'},
                    {'role': 'user', 'content': source.SOURCE_MARKER + json.dumps([
                     {'id': item['id'], 'file': item['fileKey'] + ' · ' + item['fileName'], 'role': item['role'],
                      'anchor': item['anchor'], 'content': item['content']} for item in chunks], ensure_ascii=False)}], 'format': schema(chunks)}
            request = root / f'request{ordinal}.json'
            request.write_bytes(source.raw_json(body))
            selection = root / f'selection{ordinal}.json'
            selection.write_bytes(source.raw_json({'chunks': chunks, 'unresolvedComparisonIds': []}))
            inputs.append({'ordinal': ordinal, 'request': source.fingerprint(request), 'selection': source.fingerprint(selection),
                           'fullOriginalChunks': chunks, 'productionPrompt': {'referenceIds': ['ZZ9']}})
        prepared = root / 'prepared.json'
        prepared.write_bytes(source.raw_json({'inputs': inputs}))
        prior = root / 'prior'
        prior.mkdir()
        descriptors, calls, states = [], [], []
        for binding in inputs:
            ordinal = binding['ordinal']
            prior_options = {'temperature': .2, 'num_ctx': 16384, 'num_predict': 2048}
            _, _, cat, selecting = source.prepare_input(binding, prior_options)
            source.save(prior / f'binding{ordinal}.json', binding)
            source.save(prior / f'catalog{ordinal}.json', cat)
            (prior / f'request{ordinal}.bin').write_bytes(source.raw_json(selecting))
            reply = first(cat, 'project_reply')
            tender = first(cat, 'tender')
            records = [{'unitId': reply['unitId'], 'rawValue': 'Beta'},
                       {'unitId': tender['unitId'], 'rawValue': 'Not a visible value'}]
            checks = source.validate_selections(records, cat)
            actual_request = prior / f'{ordinal:02}_select.request.bin'
            actual_request.write_bytes(source.raw_json(selecting))
            actual_response = prior / f'{ordinal:02}_select.response.bin'
            actual_response.write_bytes(source.raw_json({'message': {'content': json.dumps(records)}, 'done_reason': 'stop'}))
            result = {'status': 'completed', 'records': records, 'request': source.fingerprint(actual_request),
                      'response': source.fingerprint(actual_response), 'startedAtUtc': 'synthetic-start',
                      'finishedAtUtc': 'synthetic-finish', 'wallSeconds': 0,
                      'nativeMetrics': {'done_reason': 'stop'}, 'placement': []}
            source.save(prior / f'{ordinal:02}_select.result.json', result)
            source.save(prior / f'{ordinal:02}_selection_checks.json', checks)
            descriptors.append({'ordinal': ordinal, 'sourceBinding': source.fingerprint(prior / f'binding{ordinal}.json'),
                                'catalog': source.fingerprint(prior / f'catalog{ordinal}.json'),
                                'selectionRequest': source.fingerprint(prior / f'request{ordinal}.bin')})
            calls.append({'stage': f'{ordinal:02}_select', **{key: result[key] for key in ('status', 'request', 'response',
                            'startedAtUtc', 'finishedAtUtc', 'wallSeconds', 'nativeMetrics', 'placement')}})
            states += [{'node': 'select_units', 'details': {'ordinal': ordinal, 'result': source.fingerprint(prior / f'{ordinal:02}_select.result.json')}},
                       {'node': 'validate_literal_bindings', 'details': {'ordinal': ordinal, 'checks': source.fingerprint(prior / f'{ordinal:02}_selection_checks.json')}}]
        source.save(prior / 'runtime_manifest.json', {'parameters': {'options': prior_options},
                    'inputFingerprints': {'inputs': descriptors}, 'measurements': {'calls': calls, 'states': states}})
        return prepared, prior

    def pinned_sha(self, prior):
        return source.fingerprint(prior / 'runtime_manifest.json')['sha256']

    def test_default_offline_zero_calls_retains_all_sources_and_rejected_units(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(runtime.requests, 'get', side_effect=AssertionError('HTTP forbidden')):
            root = Path(temp)
            prepared, prior = self.prepared(root)
            result = runtime.run(prepared, prior, root / 'offline', root / 'registry', call=lambda *a: self.fail('model forbidden'),
                                 expected_runtime_sha=self.pinned_sha(prior))
            self.assertEqual(result['status'], 'prepared')
            self.assertEqual(result['measurements']['actualAttempts'], 0)
            self.assertEqual(result['measurements']['calls'], [])
            self.assertEqual(len(result['measurements']['reusedCalls']), 3)
            self.assertEqual(result['parameters']['newSelectionCalls'], 0)
            original = json.loads(prepared.read_text(encoding='utf-8'))['inputs'][0]['fullOriginalChunks']
            body = json.loads((root / 'offline/compare_01.prepared_request.bin').read_bytes())
            view = json.loads((root / 'offline/model_sources_01.json').read_text(encoding='utf-8'))
            self.assertTrue(body['messages'][1]['content'].endswith(source.raw_json(view).decode('utf-8')))
            for item, original_chunk in zip(view, original):
                self.assertEqual(''.join(segment['text'] for segment in item['contentSegments']), original_chunk['content'])
            self.assertIn('source_representation_dedup', result['changedFactors'])
            self.assertIn('options.num_ctx', result['changedFactors'])
            self.assertIn('options.num_predict', result['changedFactors'])
            spans = json.loads((root / 'offline/span_catalog_01.json').read_text(encoding='utf-8'))
            self.assertTrue(any(unit['kind'] == 'tender' for unit in spans['units']))
            events = [json.loads(path.read_text(encoding='utf-8')) for path in (root / 'registry/records/offline').glob('*.json')]
            self.assertEqual(len({source.digest(event['record']['inputFingerprints']) for event in events}), 1)
            self.assertEqual(len({source.digest(event['record']['parameters']) for event in events}), 1)

    def test_reused_binding_tamper_failed_event_zero_new_calls(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(runtime.requests, 'get', side_effect=AssertionError('HTTP forbidden')):
            root = Path(temp)
            prepared, prior = self.prepared(root)
            path = prior / '01_selection_checks.json'
            path.write_text('{}', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'binding_checks_changed'):
                runtime.run(prepared, prior, root / 'failed', root / 'registry', expected_runtime_sha=self.pinned_sha(prior))
            failure = json.loads((root / 'failed/bootstrap_failure.json').read_text(encoding='utf-8'))
            self.assertEqual(failure['measurements']['actualAttempts'], 0)
            self.assertTrue(list((root / 'registry/records/failed').glob('*.json')))

    def test_schema_rejects_overall_approval_and_missing_scope_enum(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            prepared, prior = self.prepared(root)
            binding = json.loads(prepared.read_text(encoding='utf-8'))['inputs'][0]
            body, selection, cat, checks, reused = runtime.load_reused_stage1(binding, prior, runtime.DEFAULT_OPTIONS, self.pinned_sha(prior))
            spans = runtime.build_span_catalog(binding['fullOriginalChunks'], cat)
            record = relation(spans)
            record['overallContractApproved'] = True
            self.assertTrue(runtime.validate_comparisons([record], spans, binding['fullOriginalChunks'], selection)['schemaErrors'])
            del record['overallContractApproved']
            record['assessedScopeUnitIds'] = ['invented']
            self.assertTrue(runtime.validate_comparisons([record], spans, binding['fullOriginalChunks'], selection)['schemaErrors'])

    def test_prior_manifest_identity_and_result_self_signed_replacement_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            prepared, prior = self.prepared(root)
            binding = json.loads(prepared.read_text(encoding='utf-8'))['inputs'][0]
            original_sha = self.pinned_sha(prior)
            with self.assertRaisesRegex(ValueError, 'manifest_identity_changed'):
                runtime.load_reused_stage1(binding, prior, runtime.DEFAULT_OPTIONS, '0' * 64)
            response_path = prior / '01_select.response.bin'
            response_path.write_bytes(source.raw_json({'message': {'content': '[]'}, 'done_reason': 'stop'}))
            result_path = prior / '01_select.result.json'
            replaced = json.loads(result_path.read_text(encoding='utf-8'))
            replaced['response'] = source.fingerprint(response_path)
            replaced['records'] = []
            result_path.write_bytes(source.raw_json(replaced))
            (prior / '01_selection_checks.json').write_bytes(source.raw_json({'accepted': [], 'rejected': [],
                                'selectionCapReached': False, 'semanticAccepted': None}))
            with self.assertRaisesRegex(ValueError, 'descriptor_not_original_ledger'):
                runtime.load_reused_stage1(binding, prior, runtime.DEFAULT_OPTIONS, original_sha)

    def fake_inventory(self, url, **kwargs):
        class Response:
            def __init__(self, value): self.value = value
            def raise_for_status(self): pass
            def json(self): return self.value
        if url.endswith('/api/tags'):
            return Response({'models': [{'name': 'qwen2.5:3b', 'digest': source.EXPECTED_MODEL_DIGEST}]})
        if url.endswith('/api/version'):
            return Response({'version': 'mock-no-network'})
        return Response({'models': []})

    def test_three_serial_mock_compares_no_selection_calls_and_stable_registry(self):
        class Sampler:
            def __init__(self, *a, **k): pass
            def start(self): pass
            def stop(self): return {'mock': True}
            def set_phase(self, phase): pass
        with tempfile.TemporaryDirectory() as temp, patch.object(runtime.requests, 'get', side_effect=self.fake_inventory), patch.object(runtime, 'ResourceSampler', Sampler):
            root = Path(temp)
            prepared, prior = self.prepared(root)
            observed = []
            def fake_call(url, body, prefix, sampler):
                observed.append(prefix.name)
                prefix.with_suffix('.request.bin').write_bytes(source.raw_json(body))
                result = {'status': 'completed', 'records': [], 'startedAtUtc': source.now(), 'finishedAtUtc': source.now(),
                          'wallSeconds': 0, 'nativeMetrics': {'done_reason': 'stop'}, 'placement': [],
                          'request': source.fingerprint(prefix.with_suffix('.request.bin'))}
                source.save(prefix.with_suffix('.result.json'), result)
                return result
            result = runtime.run(prepared, prior, root / 'three-mock', root / 'registry', execute=True, call=fake_call,
                                 expected_runtime_sha=self.pinned_sha(prior))
            self.assertEqual(observed, ['01_compare', '02_compare', '03_compare'])
            self.assertEqual(result['measurements']['actualAttempts'], 3)
            self.assertEqual(result['measurements']['finishedCalls'], 3)
            self.assertEqual(len(result['measurements']['attemptEvents']), 6)
            self.assertEqual(len(result['measurements']['reusedCalls']), 3)
            events = [json.loads(path.read_text(encoding='utf-8')) for path in (root / 'registry/records/three-mock').glob('*.json')]
            self.assertEqual(len({source.digest(event['record']['inputFingerprints']) for event in events}), 1)
            self.assertEqual(len({source.digest(event['record']['parameters']) for event in events}), 1)
            ledger = object.__new__(runtime.ComparisonLedger)
            ledger.record = result
            with self.assertRaisesRegex(ValueError, 'three_comparison_attempt_budget'):
                ledger.attempt('extra', {}, root / 'no-extra')

    def test_interrupted_compare_preserves_started_attempt_and_no_finished_call(self):
        class Sampler:
            def __init__(self, *a, **k): pass
            def start(self): pass
            def stop(self): return {'mock': True}
            def set_phase(self, phase): pass
        with tempfile.TemporaryDirectory() as temp, patch.object(runtime.requests, 'get', side_effect=self.fake_inventory), patch.object(runtime, 'ResourceSampler', Sampler):
            root = Path(temp)
            prepared, prior = self.prepared(root)
            def interrupted(*args):
                raise KeyboardInterrupt('mock client interruption, no real HTTP')
            with self.assertRaises(KeyboardInterrupt):
                runtime.run(prepared, prior, root / 'interrupted', root / 'registry', execute=True, call=interrupted,
                            expected_runtime_sha=self.pinned_sha(prior))
            result = json.loads((root / 'interrupted/runtime_manifest.json').read_text(encoding='utf-8'))
            self.assertEqual(result['measurements']['actualAttempts'], 1)
            self.assertEqual(result['measurements']['finishedCalls'], 0)
            self.assertEqual(len(result['measurements']['attemptEvents']), 1)
            self.assertTrue((root / 'interrupted/01_compare.attempt_request.bin').exists())


if __name__ == '__main__':
    unittest.main()
