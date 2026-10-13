"""Authored runtime adapters exercise signatures and cached context validation."""
import copy
from dataclasses import replace
import unittest

import test_window_server as baseline
from test_structural_window_runtime import DualRuntimeTests
from test_typed_parent_hierarchy_context import corpus
from retrieval_source_units import HIERARCHY_POLICY, POLICY
from structural_retrieval import VERSION
from token_windows import WindowError
from window_runtime import WindowRuntime


class TypedParentRuntimeTests(DualRuntimeTests):
    def setUp(self):
        super().setUp()
        self.corpus = corpus()

    def test_new_policy_recipe_and_algorithm_hash_bound_to_health_and_signature(self):
        # The inherited fixture deliberately mocks algorithms for its generic
        # runtime tests. This check observes the copied real source hashes.
        self.rt.algorithms = lambda: WindowRuntime.algorithms(self.rt)
        self.index()
        recipe = self.rt.retrieval_recipe()
        self.assertEqual(recipe['structuralSelection']['policy'], POLICY)
        self.assertEqual(recipe['structuralSelection']['hierarchyContextPolicy'], HIERARCHY_POLICY)
        self.assertEqual(recipe['structuralRecipe']['version'], VERSION)
        saved = baseline.s.read_project('dual-test')
        self.assertEqual(saved['signature']['structuralRecipe']['version'], VERSION)
        self.assertIn('retrieval_source_units.py', saved['signature']['algorithms'])
        self.assertIn('structural_retrieval.py', saved['signature']['algorithms'])
        new_cache = self.rt.embedding_cache_identity(3)
        self.rt.structural_recipe = replace(self.rt.structural_recipe,
            version='observed-structure-dual-granularity-and-same-pool-rerank-v1')
        self.assertNotEqual(new_cache, self.rt.embedding_cache_identity(3))
        with self.assertRaises(baseline.s.HTTPException):
            baseline.s.read_project('dual-test')

    def test_new_context_payload_and_order_tamper_not_accepted_from_old_cache(self):
        self.index()
        saved = copy.deepcopy(baseline.s.read_project('dual-test'))
        context = next(c for c in saved['structureContexts'] if
            c['structuralUnit'].get('hierarchyContextPolicy') == HIERARCHY_POLICY and
            len(c['structuralUnit']['requiredMemberIds']) > 1)
        context['structuralUnit']['requiredMemberIds'].reverse()
        with self.assertRaises(WindowError):
            self.rt.validate_metadata(saved)


if __name__ == '__main__': unittest.main()
