"""Synthetic, network-free checks for source-request routing and ID-only binding."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import request_task_runtime as runtime
import source_unit_runtime as source
import span_comparison_runtime as spans
from fact_stages import SOURCE_MARKER
from test_source_unit_runtime import chunk, table_chunk, schema


def binding(chunks, requested=None, ordinal=1):
    return {'ordinal': ordinal, 'productionPrompt': {'referenceIds': requested or ['ZZ9']},
            'fullOriginalChunks': chunks}


def catalogue(chunks, requested=None):
    original = source.build_catalog(chunks, requested or ['ZZ9'])
    if original['fatalErrors']:
        raise ValueError(original['fatalErrors'])
    return spans.build_span_catalog(chunks, original)


def build(chunks, requested=None):
    cat = catalogue(chunks, requested)
    return cat, runtime.build_tasks(binding(chunks, requested), cat)


def observed(task, **overrides):
    result = {'taskId': task['taskId'], 'leftUnitId': task['leftTenderUnitIds'][0] if task['leftTenderUnitIds'] else None,
              'rightUnitId': task['rightPopulatedReplyUnitIds'][0] if task['rightPopulatedReplyUnitIds'] else None,
              'relation': 'difference', 'differenceKind': 'value_difference', 'objectPurposeClaim': 'same',
              'conditionClaim': 'unknown', 'adoptionClaim': 'unknown', 'supportUnitIds': [],
              'reason': 'A synthetic local observation, with semantic interpretation still unverified.'}
    result.update(overrides)
    return result


def write_prepared(root, chunks, requested=None):
    request, selection = root / 'original_request.json', root / 'original_selection.json'
    original = {'model': 'qwen2.5:3b', 'stream': False, 'options': source.OPTIONS,
                'format': schema(chunks), 'messages': [{'role': 'system', 'content': 'Synthetic original system'},
                {'role': 'user', 'content': SOURCE_MARKER + source.raw_json([
                    {'id': item['id'], 'file': item['fileKey'] + ' · ' + item['fileName'], 'role': item['role'],
                     'anchor': item['anchor'], 'content': item['content']} for item in chunks]).decode('utf-8')} ]}
    source.save(request, original)
    source.save(selection, {'chunks': chunks, 'unresolvedComparisonIds': []})
    item = binding(chunks, requested)
    item.update(request=runtime.fingerprint(request), selection=runtime.fingerprint(selection))
    prepared = root / 'prepared.json'
    source.save(prepared, {'inputs': [item]})
    return prepared


class SourceTaskRoutingTest(unittest.TestCase):
    def test_source_request_counts_are_not_fixed_and_multi_reference_is_not_atomic(self):
        rows = [['ZZ9(1), (2)', 'Combined requested purposes', 'Combined actual answers'],
                ['ZZ9(3)', 'A different request', 'Another actual answer'],
                ['ZZ10', 'Outside named scope', 'Outside actual answer']]
        cat, tasks = build([chunk('tender', 'The original tender clause for this request.'), table_chunk(data=rows)])
        self.assertEqual(len(tasks), 2)
        combined = next(item for item in tasks if item['rawRequiredInput'] == 'Combined requested purposes')
        self.assertEqual(combined['referenceNominations'], ['ZZ9(1)', 'ZZ9(2)'])
        self.assertEqual(combined['isAtomicVerified'], 'unknown')
        self.assertNotIn('Outside named scope', [item['rawRequiredInput'] for item in tasks])
        _, five = build([table_chunk(data=[['ZZ9', f'Request {i}', f'Original reply {i}'] for i in range(5)])])
        self.assertEqual(len(five), 5)

    def test_filled_blank_whitespace_and_no_column_versions_remain_present(self):
        chunks = [table_chunk(data=[['ZZ9', 'A single original request', 'Actual filled answer']], document='2'),
                  table_chunk(data=[['ZZ9', 'A single original request', '']], identity='blank', document='3'),
                  table_chunk(data=[['ZZ9', 'A single original request', ' \n']], identity='space', document='4'),
                  table_chunk(labels=['Clause', 'Required input'], data=[['ZZ9', 'A single original request']],
                              identity='no_column', document='5')]
        cat, tasks = build(chunks)
        self.assertEqual(len(tasks), 1)
        task = tasks[0]
        self.assertEqual([item['replyState'] for item in task['versions']], ['populated', 'blank', 'blank', 'no_reply_column'])
        self.assertEqual(len(task['rightPopulatedReplyUnitIds']), 1)
        self.assertEqual(next(unit for unit in cat['units'] if unit['unitId'] == task['rightPopulatedReplyUnitIds'][0])['rawText'], 'Actual filled answer')
        self.assertIsNone(task['versions'][-1]['replyUnitId'])

    def test_overlapping_chunks_are_one_version_with_all_coverage_ids(self):
        first = table_chunk()
        duplicate = copy.deepcopy(first)
        duplicate['id'] = 'overlap'
        cat, tasks = build([first, duplicate])
        self.assertEqual(len(tasks[0]['versions']), 1)
        self.assertEqual(tasks[0]['versions'][0]['sourceChunkIds'], ['mail', 'overlap'])
        reply = next(unit for unit in cat['units'] if unit['kind'] == 'project_reply')
        self.assertEqual(len(reply['coverages']), 2)

    def test_request_text_is_exact_not_semantic_merge(self):
        _, tasks = build([table_chunk(data=[['ZZ9', 'Original request', 'One'],
                                           ['ZZ9', 'Original request ', 'Two']])])
        self.assertEqual(len(tasks), 2)

    def test_different_native_reference_sets_are_not_merged(self):
        _, tasks = build([table_chunk(data=[['ZZ9(1)', 'Same caption', 'One'],
                                           ['ZZ9(2)', 'Same caption', 'Two']])])
        self.assertEqual(len(tasks), 2)

    def test_numeric_parent_boundary_and_non_table_context_do_not_make_tasks(self):
        chunks = [table_chunk(data=[['ZZ90', 'Wrong numeric parent', 'Visible reply']]),
                  chunk('untyped', 'Original project paragraph with no native row structure.', role='project_fact', document='4')]
        cat, tasks = build(chunks)
        self.assertFalse(tasks)
        self.assertTrue(any(unit['kind'] == 'untyped_project_context' for unit in cat['units']))

    def test_unknown_headers_and_forged_native_row_cannot_route_a_reply(self):
        wrong = table_chunk(labels=['Clause', 'Question', 'Reply'])
        forged = table_chunk(identity='forged', document='3')
        forged['parts'][1]['table']['headerBlockId'] = 'body:8:table-row:0'
        _, tasks = build([wrong, forged])
        self.assertFalse(tasks)

    def test_reordered_columns_extra_actor_pipe_and_surrogate_are_original(self):
        chunks = [table_chunk(labels=['Actor', 'Reply', 'Required input', 'Clause'],
                              data=[['Original author', 'North | West 😀', 'The original request', 'ZZ9']])]
        cat, tasks = build(chunks)
        proof = runtime.whole_unit(tasks[0]['rightPopulatedReplyUnitIds'][0], cat, chunks)
        self.assertEqual(proof['wholeUnitText'], 'North | West 😀')
        self.assertEqual(proof['cellIndex'], 1)
        self.assertEqual(proof['quote'], chunks[0]['parts'][1]['text'])
        self.assertEqual(source.utf16_slice(chunks[0]['content'], proof['quoteChunkStartUtf16'], proof['quoteChunkEndUtf16']), proof['quote'])
        self.assertEqual(proof['unitEndUtf16'] - proof['unitStartUtf16'], source.utf16_len(proof['wholeUnitText']))


class TypedRoleAndObservationTest(unittest.TestCase):
    def setUp(self):
        self.chunks = [chunk('tender', 'The named original tender provision supplies a visible value.'),
                       chunk('standard', 'A template placeholder is still standard material.', role='standard', document='6'),
                       table_chunk(data=[['ZZ9', 'Named original request', 'A populated original reply'],
                                         ['ZZ9', 'A different request', 'Unrelated populated reply']])]
        self.cat, self.tasks = build(self.chunks)
        self.task = next(task for task in self.tasks if task['rawRequiredInput'] == 'Named original request')

    def audit(self, record, selection=None):
        return runtime.validate([record], self.task, self.cat, self.chunks, selection or {})

    def test_typed_enums_reject_template_request_blank_and_other_task_reply(self):
        wrong_left = [unit for unit in self.cat['units'] if unit['kind'] in ('standard', 'request_context')]
        for unit in wrong_left:
            self.assertTrue(self.audit(observed(self.task, leftUnitId=unit['unitId']))['schemaErrors'])
        other = next(task for task in self.tasks if task != self.task)['rightPopulatedReplyUnitIds'][0]
        self.assertTrue(self.audit(observed(self.task, rightUnitId=other))['schemaErrors'])

    def test_model_cannot_write_values_quotes_chunk_or_roles(self):
        for key in ['rawValue', 'quote', 'role', 'chunkId']:
            self.assertTrue(self.audit(observed(self.task, **{key: 'Invented'}))['schemaErrors'])

    def test_bound_origin_leaves_atomic_object_condition_adoption_unknown(self):
        actual = self.audit(observed(self.task, conditionClaim='supported', adoptionClaim='supported'))['records'][0]
        self.assertTrue(actual['literalBindingsPassed'])
        self.assertTrue(actual['candidateEligible'])
        self.assertIsNone(actual['semanticAccepted'])
        self.assertEqual(actual['completeValueExtraction'], 'unknown')
        self.assertEqual(actual['modelClaims']['adoptionClaim'], {'claim': 'supported', 'independentlyVerified': 'unknown'})
        self.assertFalse(actual['overallApproval'])

    def test_null_unknown_is_not_missing_or_literal_pass(self):
        item = self.audit(observed(self.task, leftUnitId=None, rightUnitId=None, relation='unknown', differenceKind='unknown', objectPurposeClaim='unknown'))['records'][0]
        self.assertFalse(item['literalBindingsPassed'])
        self.assertFalse(item['candidateEligible'])
        self.assertFalse(item['nullMeansMissing'])
        self.assertFalse(item['sourceUnitSelected'])

    def test_match_is_local_not_candidate_or_overall_approval(self):
        item = self.audit(observed(self.task, relation='match', differenceKind='none'))['records'][0]
        self.assertTrue(item['mechanicalGatePassed'])
        self.assertFalse(item['candidateEligible'])
        self.assertTrue(item['localObservationOnly'])
        self.assertFalse(item['overallApproval'])

    def test_unresolved_core_target_keeps_raw_record_but_rejects_candidate(self):
        tender = next(item for item in self.cat['units'] if item['unitId'] == self.task['leftTenderUnitIds'][0])
        result = self.audit(observed(self.task), {'unresolvedComparisonIds': [tender['coverages'][0]['chunkId']]})
        self.assertIn('quoted_comparison_target_unresolved', result['records'][0]['reasons'])
        self.assertFalse(result['records'][0]['candidateEligible'])
        self.assertEqual(result['records'][0]['rawRecord'], observed(self.task))

    def test_same_whole_literal_warning_does_not_reject_condition_or_other_claim(self):
        text = 'Same complete original source text.'
        chunks = [chunk('tender', text), table_chunk(data=[['ZZ9', 'A named request', text]])]
        cat, tasks = build(chunks)
        for kind in ['value_difference', 'condition_difference', 'obligation_difference']:
            item = runtime.validate([observed(tasks[0], differenceKind=kind)], tasks[0], cat, chunks, {})['records'][0]
            self.assertTrue(item['candidateEligible'])
            self.assertIn('same_literal_whole_units_do_not_certify_complete_atomic_value_or_applicability', item['warnings'])

    def test_support_duplicate_is_rejected_and_short_core_quote_keeps_existing_gate(self):
        unit = self.cat['units'][0]['unitId']
        self.assertIn('duplicate_support_ids', self.audit(observed(self.task, supportUnitIds=[unit, unit]))['records'][0]['reasons'])
        chunks = [chunk('tender', 'Short'), table_chunk(data=[['ZZ9', 'A named request', 'A populated original reply']])]
        cat, tasks = build(chunks)
        item = runtime.validate([observed(tasks[0])], tasks[0], cat, chunks, {})['records'][0]
        self.assertIn('program_quote_below_existing_minimum_length', item['reasons'])

    def test_unit_span_tamper_and_uncontained_quote_fail_closed(self):
        cat = copy.deepcopy(self.cat)
        unit = next(unit for unit in cat['units'] if unit['unitId'] == self.task['rightPopulatedReplyUnitIds'][0])
        unit['originalEndUtf16'] += 1
        with self.assertRaises(ValueError):
            runtime.whole_unit(unit['unitId'], cat, self.chunks)
        cat = copy.deepcopy(self.cat)
        unit = next(unit for unit in cat['units'] if unit['unitId'] == self.task['rightPopulatedReplyUnitIds'][0])
        unit['quoteSourceText'] = unit['rawText']
        unit['quoteSourceStartUtf16'] = 0
        with self.assertRaisesRegex(ValueError, 'does_not_contain'):
            runtime.whole_unit(unit['unitId'], cat, self.chunks)

    def test_payload_keeps_all_characters_all_versions_and_conditions(self):
        body = runtime.request_body(self.task, self.cat, self.chunks, runtime.OPTIONS)
        marker = 'All original source characters, with external unit markers; join segment.text in order to reproduce each original content. Requests, blanks, no-column rows and all versions remain present:\n'
        rendered = json.loads(body['messages'][1]['content'].split(marker, 1)[1])
        self.assertEqual([source.raw_json(''.join(segment['text'] for segment in item['contentSegments'])) for item in rendered],
                         [source.raw_json(item['content']) for item in self.chunks])
        self.assertEqual(body['options'], runtime.OPTIONS)
        self.assertEqual(body['format']['maxItems'], 3)


class GenerationProfileTest(unittest.TestCase):
    def setUp(self):
        self.chunks = [chunk('tender', 'The actual provision, including its original condition.'), table_chunk()]
        self.cat, tasks = build(self.chunks)
        self.task = tasks[0]
        self.old = runtime.request_body(self.task, self.cat, self.chunks, runtime.OPTIONS)

    def test_defaults_preserve_old_model_system_options_and_schema(self):
        model, digest, prompt = runtime.generation_profile()
        self.assertEqual((model, digest, prompt), ('qwen2.5:3b', source.EXPECTED_MODEL_DIGEST, runtime.SYSTEM))
        explicit = runtime.request_body(self.task, self.cat, self.chunks, runtime.OPTIONS, model=model, system_prompt=prompt)
        self.assertEqual(source.raw_json(explicit), source.raw_json(self.old))

    def test_model_only_body_change_preserves_user_system_format_stream_options(self):
        newer = runtime.request_body(self.task, self.cat, self.chunks, runtime.OPTIONS, model='synthetic:larger')
        self.assertEqual(newer['model'], 'synthetic:larger')
        newer['model'] = self.old['model']
        self.assertEqual(source.raw_json(newer), source.raw_json(self.old))

    def test_prompt_only_body_change_preserves_all_other_request_bytes(self):
        prompt = runtime.SYSTEM + '\nSelect original body text that supports the bounded comparison.'
        newer = runtime.request_body(self.task, self.cat, self.chunks, runtime.OPTIONS, system_prompt=prompt)
        self.assertEqual(newer['messages'][0]['content'], prompt)
        newer['messages'][0]['content'] = self.old['messages'][0]['content']
        self.assertEqual(source.raw_json(newer), source.raw_json(self.old))

    def test_profile_rejects_missing_digest_malformed_identity_and_empty_system(self):
        for kwargs in ({'model': 'synthetic:larger'}, {'expected_model_digest': 'not-a-digest'},
                       {'model': ' synthetic:larger ', 'expected_model_digest': 'a'*64}, {'system_prompt': ''}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                runtime.generation_profile(**kwargs)

    def test_parameterized_offline_profile_records_selected_identity_without_http(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            prepared = write_prepared(root, self.chunks)
            factors = ['model']
            with patch.object(runtime.requests, 'get', side_effect=AssertionError('No HTTP')):
                record = runtime.run(prepared, root/'out', root/'logs', model='synthetic:larger',
                                     expected_model_digest='a'*64, comparison_profile='model_only',
                                     baseline_id='actual-frozen-baseline', changed_factors=factors,
                                     expected_prepared_sha=runtime.fingerprint(prepared)['sha256'])
            self.assertEqual(record['parameters']['model'], 'synthetic:larger')
            self.assertEqual(record['parameters']['expectedModelDigest'], 'a'*64)
            self.assertEqual(record['changedFactors'], ['model'])
            self.assertEqual(record['baselineId'], 'actual-frozen-baseline')
            self.assertEqual(record['measurements']['actualAttempts'], 0)
            factors.append('caller-mutation')
            self.assertEqual(record['changedFactors'], ['model'])

    def test_wrong_inventory_digest_stops_before_sampler_and_model_attempt(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            prepared = write_prepared(root, self.chunks)
            def response(url, **kwargs):
                body = {'models': []} if url.endswith('/ps') else ({'models': [{'name': 'synthetic:larger', 'digest': 'b'*64}]} if url.endswith('/tags') else {'version': 'synthetic'})
                return Mock(**{'json.return_value': body})
            call = Mock(side_effect=AssertionError('No generation'))
            with patch.object(runtime.requests, 'get', side_effect=response), patch.object(runtime, 'ResourceSampler', side_effect=AssertionError('No sampler')), self.assertRaisesRegex(ValueError, 'model_digest_changed'):
                runtime.run(prepared, root/'out', root/'logs', execute=True, call=call,
                            model='synthetic:larger', expected_model_digest='a'*64,
                            expected_prepared_sha=runtime.fingerprint(prepared)['sha256'])
            call.assert_not_called()
            record = json.loads((root/'out/runtime_manifest.json').read_text(encoding='utf-8'))
            self.assertEqual(record['measurements']['actualAttempts'], 0)
            self.assertEqual(record['status'], 'failed')


class OfflineLedgerTest(unittest.TestCase):
    def test_default_prepare_has_source_derived_budget_and_zero_network(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            chunks = [chunk('tender', 'Original tender material remains present.'),
                      table_chunk(data=[['ZZ9', 'Request A', 'Reply A'], ['ZZ9', 'Request B', 'Reply B']])]
            prepared = write_prepared(root, chunks)
            with patch.object(runtime.requests, 'get', side_effect=AssertionError('No HTTP')), patch.object(runtime.requests, 'post', side_effect=AssertionError('No HTTP')):
                result = runtime.run(prepared, root / 'out', root / 'logs', expected_prepared_sha=runtime.fingerprint(prepared)['sha256'])
            self.assertEqual(result['status'], 'prepared')
            self.assertEqual(result['parameters']['maxNativeCalls'], 2)
            self.assertEqual(result['measurements']['actualAttempts'], 0)
            self.assertFalse(result['measurements']['calls'])
            plan = json.loads((root / 'out/task_plan.json').read_text(encoding='utf-8'))
            self.assertEqual(plan['sourceDerivedTaskCount'], 2)
            self.assertFalse(plan['expectedGoalOrGoldCountsRead'])
            events = (root / 'logs/events.jsonl').read_text(encoding='utf-8')
            self.assertIn('prepared', events)

    def test_source_preflight_failure_is_registered_with_zero_attempts(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            bad = root / 'prepared.json'
            bad.write_text('{}', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'identity_changed'):
                runtime.run(bad, root / 'out', root / 'logs', expected_prepared_sha='0' * 64)
            failure = json.loads((root / 'out/bootstrap_failure.json').read_text(encoding='utf-8'))
            self.assertEqual(failure['status'], 'failed')
            self.assertEqual(failure['measurements']['actualAttempts'], 0)
            self.assertTrue((root / 'logs/events.jsonl').exists())

    def test_attempt_budget_is_task_plan_length_not_six_and_consumed_before_response(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            chunks = [table_chunk(data=[['ZZ9', f'Request {i}', f'Reply {i}'] for i in range(7)])]
            prepared = write_prepared(root, chunks)
            result = runtime.run(prepared, root / 'out', root / 'logs', expected_prepared_sha=runtime.fingerprint(prepared)['sha256'])
            result['status'], result['finishedAtUtc'] = 'started', None
            result['experimentId'] = 'fresh-attempt-test'
            ledger = runtime.TaskLedger(root / 'attempt_logs', result)
            task = json.loads((root / 'out/task_01.json').read_text(encoding='utf-8'))
            cat = json.loads((root / 'out/span_catalog_01.json').read_text(encoding='utf-8'))
            body = runtime.request_body(task, cat, chunks, runtime.OPTIONS)
            for index in range(7):
                self.assertEqual(ledger.attempt('compare', body, root / f'attempt_{index}'), index + 1)
            self.assertEqual(result['measurements']['actualAttempts'], 7)
            self.assertEqual(result['measurements']['finishedCalls'], 0)
            with self.assertRaisesRegex(ValueError, 'task_plan_attempt_budget'):
                ledger.attempt('extra', body, root / 'blocked')
            self.assertFalse((root / 'blocked.attempt_request.bin').exists())


if __name__ == '__main__':
    unittest.main()
