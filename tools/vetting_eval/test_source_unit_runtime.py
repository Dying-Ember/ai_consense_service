"""Synthetic offline failure shields; no contract-answer fixtures or network."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import source_unit_runtime as runtime


def chunk(identity, text, *, role='tender', document='1', owner='ZZ', clause='ZZ9', start=0, block='body:1'):
    part = {'text': text, 'anchor': clause + ' · ' + block, 'blockId': block,
            'startOffset': start, 'endOffset': start + runtime.utf16_len(text)}
    return {'id': identity, 'documentId': document, 'sourceHash': hashlib.sha256((document + role).encode()).hexdigest(),
            'fileKey': owner, 'fileName': owner + '.docx', 'role': role, 'clauseId': clause,
            'anchor': part['anchor'], 'content': text, 'parts': [part]}


def table_chunk(*, labels=None, data=None, identity='mail', document='2'):
    labels = labels or ['Clause', 'Required input', 'Reply']
    data = data or [['ZZ9', 'The requested field for the named object', 'Declined']]
    rows = [labels] + data
    result = chunk(identity, '', role='project_fact', document=document, owner='OTHER', clause='')
    result['parts'] = []
    for index, cells in enumerate(rows):
        text = ' | '.join(cells)
        result['parts'].append({'text': text, 'anchor': f'body/4/table-row/{index}', 'blockId': f'body:4:table-row:{index}',
             'startOffset': 0, 'endOffset': runtime.utf16_len(text),
             'table': {'cells': cells, 'headers': labels, 'tableLocation': 'body/4',
                       'headerBlockId': 'body:4:table-row:0', 'headerLocation': 'body/4/table-row/0', 'rowIndex': index}})
    result['content'] = '\n'.join(part['text'] for part in result['parts'])
    return result


def record(evidence, *, kind='reference', assessment='issue'):
    return {'assessment': assessment, 'type': kind, 'severity': 'high', 'title': 'Synthetic observation',
            'comment': 'Synthetic test claim without a source quality conclusion.', 'impact': 'Unknown.',
            'suggestion': 'Independent review.', 'evidence': evidence}


def schema(chunks):
    return {'type': 'array', 'maxItems': 3, 'items': {'type': 'object', 'additionalProperties': False,
            'properties': {'assessment': {'type': 'string', 'enum': ['issue', 'consistent', 'insufficient_context']},
                           'type': {'type': 'string', 'enum': ['reference', 'conflict', 'language', 'risk']},
                           'severity': {'type': 'string', 'enum': ['high', 'medium', 'low']},
                           **{name: {'type': 'string', 'minLength': 1} for name in ['title', 'comment', 'impact', 'suggestion']},
                           'evidence': {'type': 'array', 'minItems': 1, 'maxItems': 4, 'items': {'type': 'object',
                           'additionalProperties': False, 'properties': {'chunkId': {'type': 'string', 'enum': [item['id'] for item in chunks]},
                           'side': {'type': 'string', 'enum': ['source', 'reference']}, 'quote': {'type': 'string', 'minLength': 12}},
                           'required': ['chunkId', 'side', 'quote']}}},
            'required': ['assessment', 'type', 'severity', 'title', 'comment', 'impact', 'suggestion', 'evidence']}}


class SourceUnitCatalogTest(unittest.TestCase):
    def test_filled_blank_and_no_column_remain_distinct(self):
        filled = table_chunk(data=[['ZZ9', 'Requested name', 'Declined'], ['ZZ9', 'Another request', '']])
        no_column = table_chunk(labels=['Clause', 'Required input'], data=[['ZZ9', 'Requested name']], identity='other', document='3')
        result = runtime.build_catalog([filled, no_column], ['ZZ9'])
        self.assertFalse(result['fatalErrors'])
        self.assertEqual([unit['rawText'] for unit in result['units']], ['Declined'])
        self.assertEqual(sorted(row['replyState'] for row in result['sourceContextRows'] if row['replyState'] != 'header'), ['blank', 'no_reply_column', 'populated'])

    def test_column_order_extra_column_and_literal_pipe_are_preserved(self):
        item = table_chunk(labels=['Actor', 'Reply', 'Clause', 'Required input'],
                           data=[['Sender', 'North | West 😀', 'ZZ9', 'Requested field']])
        catalog = runtime.build_catalog([item], ['ZZ9'])
        unit = catalog['units'][0]
        self.assertEqual(unit['cellIndex'], 1)
        self.assertEqual(unit['rawText'], 'North | West 😀')
        self.assertEqual(runtime.utf16_slice(item['parts'][1]['text'], unit['originalStartUtf16'], unit['originalEndUtf16']), unit['rawText'])

    def test_request_text_is_not_a_reply_candidate(self):
        catalog = runtime.build_catalog([table_chunk(data=[['ZZ9', 'Value Requested', 'Value Replied']])], ['ZZ9'])
        result = runtime.validate_selections([{'unitId': catalog['units'][0]['unitId'], 'rawValue': 'Requested'}], catalog)
        self.assertFalse(result['accepted'])
        self.assertIn('value_not_verbatim_unit_substring', result['rejected'][0]['reasons'])

    def test_unknown_duplicate_headers_and_wrong_provenance_are_not_guessed(self):
        variants = [table_chunk(labels=['Clause', 'Question', 'Reply']),
                    table_chunk(labels=['Clause', 'Required input', 'Reply', 'Reply'], data=[['ZZ9', 'Question', 'A', 'B']]),
                    table_chunk()]
        variants[2]['parts'][1]['table']['headerBlockId'] = 'other:header'
        for item in variants:
            self.assertFalse(runtime.build_catalog([item], ['ZZ9'])['units'])

    def test_cell_row_mismatch_and_partial_row_rejected(self):
        first = table_chunk()
        first['parts'][1]['table']['cells'][2] = 'Tampered'
        self.assertFalse(runtime.build_catalog([first], ['ZZ9'])['units'])
        second = table_chunk()
        second['parts'][1]['startOffset'] = 3
        second['parts'][1]['endOffset'] += 3
        self.assertFalse(runtime.build_catalog([second], ['ZZ9'])['units'])

    def test_negative_row_index_forged_location_and_swapped_table_header_rejected(self):
        negative = table_chunk()
        negative['parts'][1]['table']['rowIndex'] = -1
        self.assertFalse(runtime.build_catalog([negative], ['ZZ9'])['units'])
        forged = table_chunk()
        forged['parts'][1]['table']['tableLocation'] = 'body/8'
        forged['parts'][1]['table']['headerBlockId'] = 'body:8:table-row:0'
        forged['parts'][1]['table']['headerLocation'] = 'body/8/table-row/0'
        self.assertFalse(runtime.build_catalog([forged], ['ZZ9'])['units'])
        original = table_chunk(data=[['ZZ9', 'Request must never become answer', 'Real reply']])
        other = table_chunk(labels=['Clause', 'Reply', 'Required input'], data=[['ZZ9', 'Other real reply', 'Other request']], identity='other')
        for part in other['parts']:
            part['blockId'] = part['blockId'].replace('body:4', 'body:8')
            part['anchor'] = part['anchor'].replace('body/4', 'body/8')
            part['table']['tableLocation'] = 'body/8'
            part['table']['headerBlockId'] = 'body:8:table-row:0'
            part['table']['headerLocation'] = 'body/8/table-row/0'
        original['parts'][1]['table']['headers'] = other['parts'][0]['table']['headers']
        original['parts'][1]['table']['headerBlockId'] = 'body:8:table-row:0'
        original['parts'][1]['table']['headerLocation'] = 'body/8/table-row/0'
        units = runtime.build_catalog([original, other], ['ZZ9'])['units']
        self.assertNotIn('Request must never become answer', [unit['rawText'] for unit in units])

    def test_parent_reference_is_not_prefix_match(self):
        item = table_chunk(data=[['ZZ70', 'Requested field', 'Wrong parent']])
        self.assertFalse(runtime.build_catalog([item], ['ZZ7'])['units'])
        item = table_chunk(data=[['ZZ7(1), (2), (3)', 'Several requested fields', 'One raw reply']])
        self.assertEqual(runtime.build_catalog([item], ['ZZ7'])['units'][0]['referenceNominations'], ['ZZ7(1)', 'ZZ7(2)', 'ZZ7(3)'])

    def test_overlap_dedup_never_stitches_disjoint_ranges(self):
        first = chunk('left', 'abcdef', start=0)
        repeated = copy.deepcopy(first)
        repeated['id'] = 'duplicate'
        last = chunk('right', 'efghij', start=4)
        catalog = runtime.build_catalog([first, repeated, last], ['ZZ9'])
        self.assertEqual([unit['rawText'] for unit in catalog['units']], ['abcdef', 'ghij'])
        self.assertEqual(len(catalog['units'][0]['coverages']), 2)
        bad = chunk('bad', 'ZZghij', start=4)
        self.assertTrue(runtime.build_catalog([first, bad], ['ZZ9'])['fatalErrors'])

    def test_utf16_surrogate_boundaries_and_invalid_part_end(self):
        self.assertEqual(runtime.utf16_slice('A😀B', 1, 3), '😀')
        with self.assertRaises(UnicodeError):
            runtime.utf16_slice('A😀B', 1, 2)
        item = chunk('unicode', 'A😀B explicit field')
        item['parts'][0]['endOffset'] -= 1
        self.assertTrue(runtime.build_catalog([item], ['ZZ9'])['fatalErrors'])

    def test_catalog_ids_bind_source_identity_and_hash(self):
        item = chunk('one', 'An explicit source value here.')
        first = runtime.build_catalog([item], ['ZZ9'])
        self.assertEqual(first, runtime.build_catalog([copy.deepcopy(item)], ['ZZ9']))
        other = copy.deepcopy(item)
        other['sourceHash'] = 'f' * 64
        self.assertNotEqual(first['units'][0]['unitId'], runtime.build_catalog([other], ['ZZ9'])['units'][0]['unitId'])


class SelectionBindingTest(unittest.TestCase):
    def test_program_builds_original_quote_without_model_identity_or_quote(self):
        item = table_chunk()
        catalog = runtime.build_catalog([item], ['ZZ9'])
        unit = catalog['units'][0]
        checks = runtime.validate_selections([{'unitId': unit['unitId'], 'rawValue': 'Declined'}], catalog)
        field = checks['accepted'][0]
        self.assertEqual(field['quote'], item['parts'][1]['text'])
        self.assertEqual(field['sourceHash'], item['sourceHash'])
        self.assertEqual(runtime.utf16_slice(item['parts'][1]['text'], field['valueStartUtf16'], field['valueEndUtf16']), 'Declined')
        self.assertEqual(field['purposeState'], 'unknown')
        self.assertIsNone(field['semanticAccepted'])
        self.assertEqual(set(runtime.selection_schema(catalog)['items']['properties']), {'unitId', 'rawValue'})

    def test_unknown_id_extra_fields_blank_and_repeated_value_rejected(self):
        catalog = runtime.build_catalog([chunk('one', 'alpha alpha in the original unit')], ['ZZ9'])
        unit_id = catalog['units'][0]['unitId']
        for output in [[{'unitId': 'foreign', 'rawValue': 'alpha'}],
                       [{'unitId': unit_id, 'rawValue': 'alpha', 'chunkId': 'one'}],
                       [{'unitId': unit_id, 'rawValue': ' '}],
                       [{'unitId': unit_id, 'rawValue': 'alpha'}]]:
            self.assertFalse(runtime.validate_selections(output, catalog)['accepted'])

    def test_cap_and_duplicate_selection_do_not_establish_coverage(self):
        catalog = runtime.build_catalog([table_chunk()], ['ZZ9'])
        item = {'unitId': catalog['units'][0]['unitId'], 'rawValue': 'Declined'}
        result = runtime.validate_selections([item] * 16, catalog)
        self.assertTrue(result['selectionCapReached'])
        self.assertEqual(len(result['accepted']), 1)
        self.assertEqual(len(result['rejected']), 15)
        self.assertFalse(runtime.validate_selections([item] * 17, catalog)['accepted'])


class ComparisonGateTest(unittest.TestCase):
    def pair(self):
        tender = chunk('tender', 'The requested named object has value Declined.')
        reply = table_chunk()
        chunks = [tender, reply]
        catalog = runtime.build_catalog(chunks, ['ZZ9'])
        units = [unit for unit in catalog['units'] if unit['kind'] == 'project_reply']
        selected = runtime.validate_selections([{'unitId': units[0]['unitId'], 'rawValue': 'Declined'}], catalog)['accepted']
        evidence = [{'chunkId': 'tender', 'side': 'source', 'quote': tender['content']},
                    {'chunkId': 'mail', 'side': 'reference', 'quote': reply['parts'][1]['text']}]
        return chunks, catalog, selected, evidence

    def test_literal_reference_basis_stays_semantically_unknown(self):
        chunks, catalog, selected, evidence = self.pair()
        result = runtime.validate_comparisons([record(evidence)], chunks, {}, catalog, selected, schema(chunks))
        self.assertEqual(result['candidateCount'], 1)
        self.assertIsNone(result['qualityAccepted'])
        self.assertEqual(result['records'][0]['purposeObjectApplicability'], 'unknown')

    def test_distinct_anchor_unresolved_and_role_gates_are_preserved(self):
        chunks, catalog, selected, evidence = self.pair()
        unresolved = runtime.validate_comparisons([record(evidence)], chunks, {'unresolvedComparisonIds': ['tender']}, catalog, selected, schema(chunks))
        self.assertEqual(unresolved['candidateCount'], 0)
        conflict = runtime.validate_comparisons([record(evidence, kind='conflict')], chunks, {}, catalog, selected, schema(chunks))
        self.assertIn('comparison_role_or_adoption_basis_missing', conflict['records'][0]['reasons'])
        one = runtime.validate_comparisons([record([evidence[0]])], chunks, {}, catalog, selected, schema(chunks))
        self.assertEqual(one['candidateCount'], 0)

    def test_request_only_quote_cannot_become_populated_reply_evidence(self):
        chunks, catalog, selected, evidence = self.pair()
        evidence[1]['quote'] = chunks[1]['parts'][1]['table']['cells'][1]
        result = runtime.validate_comparisons([record(evidence)], chunks, {}, catalog, selected, schema(chunks))
        self.assertIn('project_quote_not_bound_to_populated_native_reply', result['records'][0]['reasons'])

    def test_same_text_in_request_plus_partial_reply_does_not_locate_whole_reply_value(self):
        tender = chunk('tender', 'The explicit named value is 123.')
        reply = table_chunk(data=[['ZZ9', 'Request value 123', '123']])
        chunks = [tender, reply]
        catalog = runtime.build_catalog(chunks, ['ZZ9'])
        unit = next(unit for unit in catalog['units'] if unit['kind'] == 'project_reply')
        selected = runtime.validate_selections([{'unitId': unit['unitId'], 'rawValue': '123'}], catalog)['accepted']
        evidence = [{'chunkId': 'tender', 'side': 'source', 'quote': tender['content']},
                    {'chunkId': 'mail', 'side': 'reference', 'quote': 'ZZ9 | Request value 123 | 1'}]
        result = runtime.validate_comparisons([record(evidence)], chunks, {}, catalog, selected, schema(chunks))
        self.assertIn('project_quote_not_bound_to_populated_native_reply', result['records'][0]['reasons'])

    def test_same_owner_template_is_not_adoption_but_explicit_xref_can_be_basis(self):
        std = chunk('std', 'The generic requirement specifies an explicit value.', role='standard', document='3')
        tender = chunk('tender', 'The project requirement specifies an explicit value.')
        chunks = [tender, std]
        evidence = [{'chunkId': c['id'], 'side': 'source' if i == 0 else 'reference', 'quote': c['content']} for i, c in enumerate(chunks)]
        catalog = runtime.build_catalog(chunks, ['ZZ9'])
        self.assertEqual(runtime.validate_comparisons([record(evidence)], chunks, {}, catalog, [], schema(chunks))['candidateCount'], 0)
        tender = chunk('tender', 'Refer to ZZ clause 9 for this requirement.', owner='AA', clause='AA1')
        chunks[0] = tender
        evidence[0]['quote'] = tender['content']
        self.assertEqual(runtime.validate_comparisons([record(evidence)], chunks, {}, runtime.build_catalog(chunks, ['ZZ9']), [], schema(chunks))['candidateCount'], 1)

    def test_equal_value_is_only_unknown_observation_not_whole_claim_rejection(self):
        claimed = record([], kind='conflict')
        evidence = [{'chunkId': identity, 'quote': 'Original value here 123.', 'quoteChunkStartUtf16': 0, 'quoteChunkEndUtf16': 24} for identity in ['a', 'b']]
        fields = [{'chunkId': identity, 'rawValue': '123', 'referenceNominations': ['ZZ9'],
                   'purposeContext': 'The same multi-attribute requested row', 'valueSpanId': identity,
                   'valueChunkStartUtf16': 20, 'valueChunkEndUtf16': 23} for identity in ['a', 'b']]
        result = runtime.same_value_observations(claimed, fields, evidence)
        self.assertEqual(len(result), 1)
        self.assertFalse(result[0]['hardReject'])
        self.assertEqual(result[0]['objectPurposeAttributeAndClaimRelation'], 'unknown')
        # Same value in another row of the same chunk must not be bound by text alone.
        fields[1]['valueChunkStartUtf16'] = 40
        fields[1]['valueChunkEndUtf16'] = 43
        self.assertEqual(runtime.same_value_observations(claimed, fields, evidence), [])


class OfflineRuntimeTest(unittest.TestCase):
    def fake_inventory(self, url, **kwargs):
        class FakeResponse:
            def __init__(self, value): self.value = value
            def raise_for_status(self): pass
            def json(self): return self.value
        if url.endswith('/api/tags'):
            return FakeResponse({'models': [{'name': 'qwen2.5:3b', 'digest': runtime.EXPECTED_MODEL_DIGEST}]})
        if url.endswith('/api/version'):
            return FakeResponse({'version': 'synthetic-offline-test'})
        return FakeResponse({'models': []})

    def test_options_are_positive_finite_and_context_bounded(self):
        for key, value in [('num_ctx', 0), ('num_ctx', 32769), ('num_ctx', True), ('num_predict', -1),
                           ('temperature', float('nan')), ('temperature', True), ('temperature', 3)]:
            options = copy.deepcopy(runtime.OPTIONS)
            options[key] = value
            with self.assertRaises(ValueError):
                runtime.validate_options(options)
        options = {'num_ctx': 16384, 'num_predict': 2048, 'temperature': .2}
        self.assertEqual(runtime.validate_options(options), options)
    def prepared(self, directory):
        chunks = [chunk('tender', 'The explicit named field is Declined.'), table_chunk()]
        bindings = []
        for ordinal in range(1, 4):
            body = {'model': 'qwen2.5:3b', 'stream': False, 'options': runtime.OPTIONS,
                    'messages': [{'role': 'system', 'content': 'A generic system.'},
                                 {'role': 'user', 'content': runtime.SOURCE_MARKER + json.dumps([
                                  {'id': c['id'], 'file': c['fileKey'] + ' · ' + c['fileName'], 'role': c['role'],
                                   'anchor': c['anchor'], 'content': c['content']} for c in chunks], ensure_ascii=False)}], 'format': schema(chunks)}
            request = directory / f'request{ordinal}.json'
            request.write_bytes(runtime.raw_json(body))
            selection = directory / f'selection{ordinal}.json'
            selection.write_bytes(runtime.raw_json({'chunks': chunks, 'unresolvedComparisonIds': []}))
            bindings.append({'ordinal': ordinal, 'clause': 'ZZ9', 'request': runtime.fingerprint(request),
                             'selection': runtime.fingerprint(selection), 'fullOriginalChunks': chunks,
                             'productionPrompt': {'referenceIds': ['ZZ9']}})
        path = directory / 'prepared.json'
        path.write_bytes(runtime.raw_json({'inputs': bindings}))
        return path

    def test_offline_nodes_and_immutable_registry_no_network_or_model(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(runtime.requests, 'get', side_effect=AssertionError('network forbidden')), patch.object(runtime.requests, 'post', side_effect=AssertionError('network forbidden')):
            root = Path(temporary)
            prepared = self.prepared(root)
            forbidden_call = lambda *args, **kwargs: self.fail('model forbidden')
            result = runtime.run(prepared, root / 'offline-test', root / 'registry', execute=False, call=forbidden_call)
            self.assertEqual(result['status'], 'prepared')
            self.assertEqual(result['measurements']['calls'], [])
            self.assertEqual([item['node'] for item in result['measurements']['states']], ['freeze_sources', 'catalog_units', 'select_units', 'validate_literal_bindings', 'compare', 'validate_comparison', 'finish'])
            events = [json.loads(path.read_text(encoding='utf-8')) for path in (root / 'registry/records/offline-test').glob('*.json')]
            fingerprints = {runtime.digest(event['record']['inputFingerprints']) for event in events}
            parameters = {runtime.digest(event['record']['parameters']) for event in events}
            self.assertEqual(len(fingerprints), 1)
            self.assertEqual(len(parameters), 1)

    def test_hash_failure_records_zero_calls_before_any_network(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(runtime.requests, 'get', side_effect=AssertionError('network forbidden')):
            root = Path(temporary)
            prepared = self.prepared(root)
            (root / 'request1.json').write_text('{}', encoding='utf-8')
            with self.assertRaises(AssertionError):
                runtime.run(prepared, root / 'bad-input', root / 'registry')
            failure = json.loads((root / 'bad-input/bootstrap_failure.json').read_text())
            self.assertEqual(failure['measurements']['calls'], [])
            self.assertEqual(failure['measurements']['states'][0]['node'], 'freeze_sources')

    def test_interrupted_invocation_keeps_started_attempt_and_zero_finished_calls(self):
        class FakeSampler:
            def __init__(self, *args, **kwargs): pass
            def start(self): pass
            def set_phase(self, phase): pass
            def stop(self): return {'mockSampler': True}
        with tempfile.TemporaryDirectory() as temporary, patch.object(runtime.requests, 'get', side_effect=self.fake_inventory), patch.object(runtime.requests, 'post', side_effect=AssertionError('real network forbidden')), patch.object(runtime, 'ResourceSampler', FakeSampler):
            root = Path(temporary)
            prepared = self.prepared(root)
            def interrupted(*args, **kwargs):
                raise KeyboardInterrupt('synthetic process interruption before any real HTTP')
            with self.assertRaises(KeyboardInterrupt):
                runtime.run(prepared, root / 'interrupted', root / 'registry', execute=True, call=interrupted)
            result = json.loads((root / 'interrupted/runtime_manifest.json').read_text())
            self.assertEqual(result['measurements']['actualAttempts'], 1)
            self.assertEqual(result['measurements']['finishedCalls'], 0)
            self.assertEqual(result['measurements']['calls'], [])
            self.assertEqual(len(result['measurements']['attemptEvents']), 1)
            self.assertTrue((root / 'interrupted/01_select.attempt_request.bin').exists())
            events = [json.loads(path.read_text()) for path in (root / 'registry/records/interrupted').glob('*.json')]
            self.assertIn('call_attempt_started', [event['eventType'] for event in events])

    def test_six_fake_calls_serial_budget_and_unchanged_input_fingerprints(self):
        class FakeSampler:
            def __init__(self, *args, **kwargs): pass
            def start(self): pass
            def set_phase(self, phase): pass
            def stop(self): return {'mockSampler': True}
        with tempfile.TemporaryDirectory() as temporary, patch.object(runtime.requests, 'get', side_effect=self.fake_inventory), patch.object(runtime.requests, 'post', side_effect=AssertionError('real network forbidden')), patch.object(runtime, 'ResourceSampler', FakeSampler):
            root = Path(temporary)
            prepared = self.prepared(root)
            calls = []
            def fake_call(url, body, prefix, sampler):
                calls.append(prefix.name)
                self.assertEqual(body['options'], {'num_ctx': 16384, 'temperature': .2, 'num_predict': 2048})
                prefix.with_suffix('.request.bin').write_bytes(runtime.raw_json(body))
                # Transport-only fake responses test workflow accounting, not model quality.
                result = {'status': 'completed', 'startedAtUtc': runtime.now(), 'finishedAtUtc': runtime.now(),
                          'wallSeconds': 0, 'nativeMetrics': {'done_reason': 'stop'}, 'placement': [],
                          'request': runtime.fingerprint(prefix.with_suffix('.request.bin')), 'records': []}
                runtime.save(prefix.with_suffix('.result.json'), result)
                return result
            result = runtime.run(prepared, root / 'six-fake', root / 'registry', execute=True, call=fake_call,
                                 options={'num_ctx': 16384, 'temperature': .2, 'num_predict': 2048})
            self.assertEqual(calls, ['01_select', '01_compare', '02_select', '02_compare', '03_select', '03_compare'])
            self.assertEqual(result['measurements']['actualAttempts'], 6)
            self.assertEqual(result['measurements']['finishedCalls'], 6)
            self.assertEqual(len(result['measurements']['attemptEvents']), 12)
            events = [json.loads(path.read_text()) for path in (root / 'registry/records/six-fake').glob('*.json')]
            self.assertEqual(len({runtime.digest(event['record']['inputFingerprints']) for event in events}), 1)
            self.assertEqual(len({runtime.digest(event['record']['parameters']) for event in events}), 1)


if __name__ == '__main__':
    unittest.main()
