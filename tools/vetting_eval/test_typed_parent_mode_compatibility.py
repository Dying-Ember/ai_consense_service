"""Authored mode regression: no real model, live DB, network, answers or gold."""
import copy
import unittest

import test_window_server as baseline
import test_structural_window_runtime as dual_baseline
from test_typed_parent_hierarchy_context import corpus
from test_retrieval_source_units import hit
from retrieval_source_units import SourceUnits, select_source_units, POLICY, HIERARCHY_POLICY
from structural_retrieval import StructuralRecipe

WINDOW_POLICY = 'unique-source-structural-unit-seeds-v1'

class TypedParentModeCompatibilityTests(unittest.TestCase):
    def setUp(self):
        dual_baseline.DualRuntimeTests.setUp(self)
        self.corpus = corpus()
    def tearDown(self):
        dual_baseline.DualRuntimeTests.tearDown(self)
    def use_windows(self):
        self.rt.structural_recipe = StructuralRecipe()
    def index(self):
        return dual_baseline.DualRuntimeTests.index(self)
    def query(self, **kwargs):
        return baseline.s.retrieve(baseline.s.RetrieveRequest(projectId='dual-test', query='q',
                       candidates=4, limit=4, **kwargs))

    def test_windows_health_declares_v1_without_hierarchy_context_policy(self):
        self.use_windows()
        selection = self.rt.retrieval_recipe(observe_tokenizer_identity=False)['structuralSelection']
        self.assertEqual(selection['policy'], WINDOW_POLICY)
        self.assertNotIn('hierarchyContextPolicy', selection)

    def test_dual_health_keeps_v2_and_hierarchy_context_policy(self):
        selection = self.rt.retrieval_recipe(observe_tokenizer_identity=False)['structuralSelection']
        self.assertEqual(selection['policy'], POLICY)
        self.assertEqual(selection['hierarchyContextPolicy'], HIERARCHY_POLICY)

    def test_windows_explicit_selection_uses_old_exact_unit_with_real_typed_edges(self):
        parents = self.corpus
        raw = [hit(parents[0], .9), hit(parents[3], .3)]
        selected, episode = select_source_units(parents, raw, 2, hierarchy_context=False)
        self.assertEqual(selected, raw)
        self.assertEqual(episode['policy'], WINDOW_POLICY)
        expected = SourceUnits(parents)._resolve_observed(parents[0])
        actual = {k:v for k,v in episode['units'][0].items() if k not in {'seedScore','originalRerankOrdinal'}}
        self.assertEqual(actual, expected)
        self.assertNotIn('hierarchyContext', actual)
        self.assertEqual(actual['requiredMemberIds'], [parents[0]['id']])

    def test_default_and_explicit_dual_keep_full_context_and_original_scores_origins(self):
        parents = self.corpus
        raw = [hit(parents[0], .9), hit(parents[3], .3)]
        default = select_source_units(parents, raw, 2)
        explicit = select_source_units(parents, raw, 2, hierarchy_context=True)
        self.assertEqual(default, explicit)
        self.assertEqual(default[0], raw)
        unit = default[1]['units'][0]
        self.assertEqual(unit['hierarchyContext']['requiredMemberIds'], [p['id'] for p in parents[:3]])
        self.assertEqual(unit['originIds'], [parents[0]['id']])
        self.assertEqual(unit['seedScore'], .9)

    def test_mode_parameter_is_bool_not_truthy_coercion(self):
        for value in (0, 1, None, 'false', 'true', [], {}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                select_source_units(self.corpus, [], 0, hierarchy_context=value)

    def test_windows_runtime_returns_exact_units_and_v1_ranking_event(self):
        self.use_windows(); self.index(); response = self.query()
        self.assertEqual(response['sourceUnits']['policy'], WINDOW_POLICY)
        self.assertTrue(response['hits'])
        catalog = SourceUnits(self.corpus)
        by_id = {p['id']:p for p in self.corpus}
        for selected, unit in zip(response['hits'], response['sourceUnits']['units']):
            original = catalog._resolve_observed(by_id[selected['id']])
            actual = {k:v for k,v in unit.items() if k not in {'seedScore','originalRerankOrdinal'}}
            self.assertEqual(actual, original)
            self.assertNotIn('hierarchyContext', unit)
            self.assertEqual(unit['seedScore'], selected['score'])
        event = next(p for stage,p in self.events if stage == 'parent-final-ranking')
        self.assertEqual(event['selectionPolicy'], WINDOW_POLICY)

    def test_dual_runtime_keeps_full_context_and_v2_ranking_event(self):
        self.index(); response = self.query()
        self.assertEqual(response['sourceUnits']['policy'], POLICY)
        title = next(u for u in response['sourceUnits']['units'] if u['originIds'] == [self.corpus[0]['id']])
        self.assertEqual(title['hierarchyContext']['requiredMemberIds'], [p['id'] for p in self.corpus[:3]])
        event = next(p for stage,p in self.events if stage == 'parent-final-ranking')
        self.assertEqual(event['selectionPolicy'], POLICY)

    def test_windows_empty_scope_declares_v1_without_new_model_adapter_call(self):
        self.use_windows(); self.index(); before = (len(self.encodes), len(self.predict_calls))
        response = self.query(role='standard')
        self.assertTrue(response['emptyScope'])
        self.assertEqual(response['sourceUnits']['policy'], WINDOW_POLICY)
        self.assertEqual(response['sourceUnits']['units'], [])
        self.assertEqual((len(self.encodes), len(self.predict_calls)), before)

    def test_dual_empty_scope_keeps_v2_without_new_model_adapter_call(self):
        self.index(); before = (len(self.encodes), len(self.predict_calls))
        response = self.query(role='standard')
        self.assertTrue(response['emptyScope'])
        self.assertEqual(response['sourceUnits']['policy'], POLICY)
        self.assertEqual((len(self.encodes), len(self.predict_calls)), before)

    def test_windows_signature_and_cache_bind_actual_v1_mode(self):
        self.use_windows()
        signature = self.rt.signature(self.corpus)
        cache = self.rt.embedding_cache_identity(3)
        self.assertEqual(signature['structuralSelectionPolicy'], WINDOW_POLICY)
        self.assertEqual(cache['structuralSelectionPolicy'], WINDOW_POLICY)
        self.assertNotIn('structuralRecipe', signature)
        self.assertNotIn('structuralRecipe', cache)

    def test_dual_signature_and_cache_bind_actual_v2_mode(self):
        self.assertEqual(self.rt.signature(self.corpus)['structuralSelectionPolicy'], POLICY)
        self.assertEqual(self.rt.embedding_cache_identity(3)['structuralSelectionPolicy'], POLICY)

if __name__ == '__main__': unittest.main()
