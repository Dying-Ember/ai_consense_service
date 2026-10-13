"""Source-only fixtures and explicit fake scorer; no model/vector/network calls."""
import copy
import unittest

from structural_retrieval import StructuralRecipe, compare, plan
from test_retrieval_source_units import parent
from test_token_windows import CharacterTokenizer
from token_windows import WindowError, WindowRecipe, verify_window_coverage, windows


class StructuralRetrievalTests(unittest.TestCase):
    def setUp(self):
        self.tokenizer = CharacterTokenizer()
        self.identity = {'tokenizer': 'TEST ONLY character tokenizer'}
        self.short = WindowRecipe(12, 2)
        self.recipe = StructuralRecipe('dual-structure-v1', 60, 60)
        self.corpus = [parent('z-head', 'body:1', 'A title', 'A1', 'body/1'),
                       parent('a-body', 'body:2', 'Must provide a report.', 'A1', 'body/1'),
                       parent('m-exception', 'body:3', 'Except when the work is cancelled.', 'A1', 'body/1'),
                       parent('b-head', 'body:4', 'B unrelated title', 'B1', 'body/4')]

    def build(self, corpus=None):
        return plan(corpus or self.corpus, self.tokenizer, self.identity, self.short, self.recipe)

    def test_default_recipe_stays_windows_only_and_requires_explicit_enable(self):
        self.assertFalse(StructuralRecipe.from_environment({}).enabled)
        with self.assertRaises(WindowError):
            plan(self.corpus, self.tokenizer, self.identity, self.short, StructuralRecipe())

    def test_invalid_mode_caps_and_strict_values_fail_before_planning(self):
        for environment in [
            {'CONSENSE_RETRIEVAL_GRANULARITY': 'auto'},
            {'CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS': '0'},
            {'CONSENSE_STRUCTURAL_RERANK_MAX_TOKENS': '8193'},
            {'CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS': 'many'},
            {'CONSENSE_STRUCTURAL_RERANK_STRICT': 'yes'},
        ]:
            with self.subTest(environment=environment), self.assertRaises(WindowError):
                StructuralRecipe.from_environment(environment)
        with self.assertRaises(WindowError):
            plan(self.corpus, self.tokenizer, self.identity, self.short,
                 StructuralRecipe('dual-structure-v1', 8, 60))
        calls = []
        with self.assertRaises(WindowError):
            compare('q', [self.corpus[0]], self.corpus, self.tokenizer, self.identity,
                    self.short, StructuralRecipe('dual-structure-v1', 60, 8),
                    lambda pairs: calls.append(pairs))
        self.assertEqual(calls, [])

    def test_dual_lanes_keep_source_order_conditions_and_all_short_text(self):
        before = copy.deepcopy(self.corpus)
        result = self.build()
        self.assertEqual(before, self.corpus)
        self.assertEqual({'short', 'structure'}, {p['granularity'] for p in result['points']})
        context = next(c for c in result['contexts'] if c.get('clauseId') == 'A1')
        self.assertEqual(context['content'], '\n'.join(c['content'] for c in self.corpus[:3]))
        self.assertIn('Except when', context['content'])
        self.assertEqual(result['coverage']['contextUnavailable'], [])
        for p in self.corpus:
            rows = [w for w in result['points'] if w['granularity'] == 'short' and w['parentId'] == p['id']]
            verify_window_coverage(p, rows, self.short.max_tokens)
        for context in result['contexts']:
            rows = [w for w in result['points'] if w['granularity'] == 'structure' and w['parentId'] == context['id']]
            verify_window_coverage(context, rows, self.recipe.long_embedding_tokens)
            self.assertTrue(all(w['retrievalSeedParentId'] in w['originParentIds'] for w in rows))

    def test_overlap_reconstruction_is_exact_and_keeps_utf16_source_evidence(self):
        a = parent('head', 'body:1', 'A😀BC')
        b = parent('tail', 'body:1', 'BC中文', start=3)
        result = self.build([a, b])
        self.assertEqual(len(result['contexts']), 1)
        context = result['contexts'][0]
        self.assertEqual(context['content'], 'A😀BC中文')
        self.assertEqual(context['parts'][0]['endOffset'], 7)
        self.assertEqual(len(context['structuralUnit']['originalPartOccurrences']), 2)

    def test_native_part_metadata_is_preserved_as_original_occurrences(self):
        a = parent('head', 'body:1', 'ABCD')
        b = parent('tail', 'body:1', 'CD中文', start=2)
        a['parts'][0]['cells'] = [{'row': 0, 'column': 0, 'text': 'ABCD', 'rowSpan': 2}]
        b['parts'][0]['cells'] = [{'row': 1, 'column': 0, 'text': 'CD中文'}]
        before = copy.deepcopy([a, b])
        context = self.build([a, b])['contexts'][0]
        originals = context['structuralUnit']['originalPartOccurrences']
        self.assertEqual([item['part'] for item in originals], [p['parts'][0] for p in before])
        self.assertEqual([a, b], before)
        self.assertIsNone(context['structuralUnit']['sourceBlockComplete'])
        self.assertEqual(context['structuralUnit']['qualifiersComplete'], 'unknown')

    def test_closure_member_other_blocks_are_not_second_hop_context(self):
        a = parent('head', 'body:1', 'ABC')
        b = parent('tail', 'body:1', 'BC', start=1)
        external = parent('external', 'body:2', 'Unrelated exception')
        b['parts'].extend(external['parts'])
        b['content'] += '\n' + external['content']
        result = self.build([a, b, external])
        first = next(c for c in result['contexts'] if c['structuralUnit']['requiredMemberIds'] == ['head', 'tail'])
        self.assertEqual(first['content'], 'ABC')

    def test_unknown_source_structure_is_explicit_short_only(self):
        p = copy.deepcopy(self.corpus[0])
        p.pop('sourceQualityHash')
        result = self.build([p])
        self.assertTrue(all(w['granularity'] == 'short' for w in result['points']))
        self.assertEqual(result['contexts'], [])
        self.assertEqual(result['coverage']['contextUnavailable'][0]['parentId'], p['id'])

    def test_strict_gap_fails_before_scoring(self):
        p = copy.deepcopy(self.corpus[0])
        p.pop('sourceQualityHash')
        calls = []
        with self.assertRaises(WindowError) as raised:
            compare('q', [p], [p], self.tokenizer, self.identity, self.short, self.recipe,
                    lambda pairs: calls.append(pairs))
        self.assertEqual(raised.exception.kind, 'STRUCTURAL_CONTEXT_UNAVAILABLE')
        self.assertEqual(calls, [])

    def test_same_frozen_pool_actual_pair_order_and_actual_scores_are_recorded(self):
        calls = []
        def scorer(pairs):
            calls.append(copy.deepcopy(pairs))
            # TEST ONLY score adapter. Production has no such scoring rule.
            return [0.2 if 'Except when' in text else 0.7 for query, text in pairs]
        candidates = [self.corpus[1], self.corpus[-1]]
        result = compare('q', candidates, self.corpus, self.tokenizer, self.identity,
                         self.short, self.recipe, scorer)
        self.assertEqual([x['id'] for x in result['candidatePool']], [p['id'] for p in candidates])
        self.assertEqual(len(calls), 2)
        self.assertTrue(all(query == 'q' for call in calls for query, text in call))
        self.assertTrue(any('Except when' in text for query, text in calls[1]))
        self.assertEqual(result['results'][0]['score'], 0.2)
        self.assertEqual(result['results'][0]['baselineScore'], 0.7)
        self.assertEqual(result['baseline']['actualScores'], [0.7] * len(calls[0]))
        self.assertTrue(result['sameQueryAndCandidatePool'])
        self.assertFalse(result['qualityImprovementEstablished'])

    def test_non_strict_unknown_is_explicitly_labelled_not_unrecorded_fallback(self):
        p = copy.deepcopy(self.corpus[0])
        p.pop('sourceQualityHash')
        recipe = StructuralRecipe('dual-structure-v1', 60, 60, False)
        result = compare('q', [p], [p], self.tokenizer, self.identity, self.short, recipe,
                         lambda pairs: [0.4 for _ in pairs])
        self.assertEqual(result['results'][0]['treatment'], 'explicit_baseline_context_unavailable')
        self.assertIsNone(result['results'][0]['contextScore'])
        self.assertTrue(result['contextUnavailable'])

    def test_wrong_source_or_pool_duplicates_are_rejected_before_scoring(self):
        changed = copy.deepcopy(self.corpus[1])
        changed['sourceHash'] = 'd' * 64
        for candidates in [[changed], [self.corpus[1], self.corpus[1]]]:
            with self.assertRaises(WindowError):
                compare('q', candidates, self.corpus, self.tokenizer, self.identity,
                        self.short, self.recipe, lambda pairs: self.fail('No inference allowed'))

    def test_real_scorer_shape_and_nonfinite_fail_closed(self):
        for values in [[], [float('nan')], [True], ['0.5'], [complex(0.5, 1)]]:
            with self.assertRaises(WindowError):
                compare('q', [self.corpus[0]], self.corpus, self.tokenizer, self.identity,
                        self.short, self.recipe, lambda pairs: values)

    def test_long_query_fails_during_planning_before_scoring(self):
        with self.assertRaises(WindowError) as error:
            compare('q' * 20, [self.corpus[0]], self.corpus, self.tokenizer, self.identity,
                    self.short, self.recipe, lambda pairs: self.fail('No inference allowed'))
        self.assertEqual(error.exception.kind, 'QUERY_TOKEN_BUDGET_EXHAUSTED')

    def test_structure_aligned_windows_keep_whole_parts_until_a_part_is_too_long(self):
        p = parent('multi', 'body:1', 'AAAA')
        p['parts'].append(parent('second', 'body:2', 'BBBB')['parts'][0])
        p['content'] = 'AAAA\nBBBB'
        rows = windows(p, self.tokenizer, WindowRecipe(8, 2), self.identity, structural_boundaries=True)
        self.assertEqual([w['content'] for w in rows], ['AAAA\n', 'BBBB'])
        self.assertFalse(any(w['oversizedPartSplit'] for w in rows))
        long = parent('long', 'body:1', 'ABCDEFGHIJKLMN')
        rows = windows(long, self.tokenizer, WindowRecipe(8, 2), self.identity, structural_boundaries=True)
        self.assertTrue(any(w['oversizedPartSplit'] for w in rows))
        verify_window_coverage(long, rows, 8)

    def test_structural_boundary_windows_cover_unicode_and_whitespace_at_small_budgets(self):
        for fragments in [('A😀B', '中文 C', '🙂D'), ('  A  ', 'BC\n\nDE', ' F  '),
                          ('a' * 20, ' B ', '中' * 10), ('A\u0301BC', '🌱中文', 'EF')]:
            p = parent('multi', 'body:0', fragments[0])
            p['parts'] = [parent(str(i), 'body:' + str(i), text)['parts'][0]
                          for i, text in enumerate(fragments)]
            p['content'] = '\n'.join(fragments)
            for cap in (6, 8, 12):
                with self.subTest(fragments=fragments, cap=cap):
                    rows = windows(p, self.tokenizer, WindowRecipe(cap, 2), self.identity,
                                   structural_boundaries=True)
                    verify_window_coverage(p, rows, cap)
                    self.assertTrue(all(row['fullModelTokens'] <= cap for row in rows))


if __name__ == '__main__':
    unittest.main()
