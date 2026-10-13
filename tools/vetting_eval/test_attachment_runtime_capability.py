"""Authored fake runtime capability/signature guards; no real model or HTTP."""
import copy
import unittest

import test_window_server as baseline
from test_structural_window_runtime import DualRuntimeTests
from test_attachment_context_guards import native_corpus
from test_explicit_margin_context_red import corpus
from retrieval_source_units import POLICY, OBSERVED_POLICY, ATTACHMENT_POLICY, SourceUnits
from structural_retrieval import VERSION, CONTRACT, StructuralRecipe
from token_windows import WindowError
from window_runtime import WindowRuntime


class AttachmentRuntimeCapabilityTests(unittest.TestCase):
    def setUp(self):
        DualRuntimeTests.setUp(self)
        self.corpus = corpus()
    def tearDown(self): DualRuntimeTests.tearDown(self)
    def index(self): return DualRuntimeTests.index(self)
    def query(self):
        return baseline.s.retrieve(baseline.s.RetrieveRequest(projectId='dual-test', query='q', candidates=4, limit=4))

    def test_v2_health_and_new_signature_declare_exact_attachment_capability(self):
        self.rt.algorithms = lambda: WindowRuntime.algorithms(self.rt)
        recipe = self.rt.retrieval_recipe(observe_tokenizer_identity=False)
        self.assertEqual(recipe['contract'], CONTRACT)
        self.assertEqual(recipe['structuralSelection']['policy'], POLICY)
        self.assertEqual(recipe['structuralSelection']['attachmentContextPolicy'], ATTACHMENT_POLICY)
        self.assertEqual(recipe['structuralRecipe']['version'], VERSION)
        self.index(); signature = baseline.s.read_project('dual-test')['signature']
        self.assertEqual(signature['contract'], CONTRACT)
        self.assertEqual(signature['structuralSelectionPolicy'], POLICY)
        self.assertEqual(signature['structuralRecipe']['version'], VERSION)
        self.assertIn('retrieval_source_units.py', signature['algorithms'])

    def test_windows_legacy_signature_and_health_have_no_attachment_declaration(self):
        self.rt.structural_recipe = StructuralRecipe()
        recipe = self.rt.retrieval_recipe(observe_tokenizer_identity=False)
        self.assertEqual(recipe['structuralSelection']['policy'], OBSERVED_POLICY)
        self.assertNotIn('attachmentContextPolicy', recipe['structuralSelection'])
        self.assertNotIn('structuralRecipe', recipe)
        signature = self.rt.signature(self.corpus)
        self.assertNotIn('structuralRecipe', signature)
        self.assertEqual(signature['structuralSelectionPolicy'], OBSERVED_POLICY)
        self.index(); result = self.query()
        self.assertTrue(all('attachmentContext' not in u for u in result['sourceUnits']['units']))

    def test_fake_runtime_preserves_seed_scores_and_publishes_exact_complete_attachment_context(self):
        self.index(); response = self.query(); expected = SourceUnits(self.corpus)
        by_id = {p['id']: p for p in self.corpus}
        self.assertEqual(response['sourceUnits']['policy'], POLICY)
        for seed, unit in zip(response['hits'], response['sourceUnits']['units']):
            reference = expected.resolve(by_id[seed['id']])
            self.assertEqual(unit['attachmentContext'], reference['attachmentContext'])
            self.assertEqual(unit['originIds'], [seed['id']]); self.assertEqual(unit['seedScore'], seed['score'])
            self.assertEqual(seed['payload'], by_id[seed['id']])

    def test_context_edge_member_asset_and_policy_change_invalidate_cached_plan(self):
        self.index(); original = baseline.s.read_project('dual-test')
        for change in ('context-member', 'context-edge', 'policy'):
            saved = copy.deepcopy(original)
            context = next(c for c in saved['structureContexts'] if 'attachmentContextPolicy' in c['structuralUnit'])
            if change == 'context-member': context['structuralUnit']['requiredMemberIds'].reverse()
            elif change == 'context-edge': context['structuralUnit']['attachmentEdgeIds'].append('0' * 64)
            else: saved['signature']['structuralRecipe']['version'] = 'observed-structure-dual-granularity-typed-parent-context-v2'
            with self.subTest(change=change):
                if change == 'policy':
                    # The public read gate checks recipe/signature before the
                    # plan validator checks derived context/window payloads.
                    baseline.s._projects['dual-test'] = saved
                    try:
                        with self.assertRaises(baseline.s.HTTPException): baseline.s.read_project('dual-test')
                    finally:
                        baseline.s._projects['dual-test'] = original
                else:
                    with self.assertRaises(WindowError): self.rt.validate_metadata(saved)


if __name__ == '__main__': unittest.main()
