"""Explicit fake adapters exercise index/retrieve; zero real neural/DB calls."""
import copy
import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import test_window_server as baseline
from structural_retrieval import CONTRACT, StructuralRecipe
from test_retrieval_source_units import parent
from test_token_windows import CharacterTokenizer
from token_windows import WindowError
from window_runtime import WindowRuntime


class DualFakeDb(baseline.FakeDb):
    def query_points(self, name, query, limit, query_filter=None):
        self.fetch_limits.append(limit)
        selected = list(self.collections[name].values())
        for condition in query_filter.must if query_filter else []:
            selected = [p for p in selected if p.payload.get(condition.key) == condition.match.value]
        selected = sorted(selected, key=lambda p: (-len(p.payload['content']), str(p.id)))[:limit]
        return SimpleNamespace(points=[SimpleNamespace(id=p.id, payload=copy.deepcopy(p.payload),
                    score=len(p.payload['content']) / 1000) for p in selected])


class DualRuntimeTests(unittest.TestCase):
    def setUp(self):
        baseline.ServerTests.setUp(self)
        self.db = DualFakeDb()
        baseline.s.database = lambda: self.db
        baseline.s.MODEL_SETTINGS = {'embedding': {'maxTokens': 12, 'batchSize': 4},
                                     'reranker': {'maxTokens': 12, 'batchSize': 4}}
        self.rt.structural_recipe = StructuralRecipe('dual-structure-v1', 60, 60)
        self.corpus = [parent('z-head', 'body:1', 'A heading', 'A1', 'body/1'),
                       parent('a-exception', 'body:2', 'Except when cancelled.', 'A1', 'body/1'),
                       parent('b-head', 'body:3', 'B independent obligation', 'B1', 'body/3')]
        self.predict_calls = []
        class RecordingModel(baseline.FakeModel):
            def predict(model, pairs, **kwargs):
                self.predict_calls.append(copy.deepcopy(pairs))
                return super().predict(pairs, **kwargs)
        baseline.s.rerank_model = lambda: RecordingModel()

    def tearDown(self):
        baseline.ServerTests.tearDown(self)

    def index(self, corpus=None):
        return baseline.s.index(baseline.s.IndexRequest(projectId='dual-test',
             chunks=[baseline.s.Chunk.model_validate(p) for p in (corpus or self.corpus)]))

    def query(self, **kwargs):
        return baseline.s.retrieve(baseline.s.RetrieveRequest(projectId='dual-test', query='q',
                   candidates=3, limit=2, **kwargs))

    def test_explicit_contract_signature_lanes_and_input_order(self):
        result = self.index()
        self.assertEqual(result['contract'], CONTRACT)
        saved = baseline.s.read_project('dual-test')
        self.assertEqual(saved['signature']['signatureVersion'], 4)
        self.assertEqual([p['id'] for p in saved['chunks']], [p['id'] for p in self.corpus])
        self.assertEqual({p['granularity'] for p in saved['windows']}, {'short', 'structure'})
        self.assertEqual(saved['structuralCoverage']['contextUnavailable'], [])

    def test_retrieve_fuses_lanes_and_returns_actual_original_payloads(self):
        self.index()
        result = self.query()
        self.assertEqual(result['mode'], CONTRACT)
        self.assertEqual(set(result['denseWindowExpansion']['lanes']), {'short', 'structure'})
        by_id = {p['id']: p for p in self.corpus}
        self.assertTrue(result['hits'])
        for hit in result['hits']:
            self.assertEqual(hit['payload'], by_id[hit['id']])
        self.assertEqual(len(self.predict_calls), 2)
        evidence = next(p for stage, p in self.events if stage == 'rerank-same-pool-structure-comparison')
        self.assertTrue(evidence['sameQueryAndCandidatePool'])
        self.assertEqual(len(evidence['baseline']['pairs']), len(evidence['baseline']['actualScores']))
        self.assertEqual(len(evidence['structure']['pairs']), len(evidence['structure']['actualScores']))

    def test_mode_or_cap_change_rejects_old_collection_signature(self):
        self.index()
        self.rt.structural_recipe = StructuralRecipe()
        with self.assertRaises(baseline.s.HTTPException) as error:
            baseline.s.read_project('dual-test')
        self.assertEqual(error.exception.status_code, 409)
        self.rt.structural_recipe = StructuralRecipe('dual-structure-v1', 70, 60)
        with self.assertRaises(baseline.s.HTTPException):
            baseline.s.read_project('dual-test')

    def test_changed_structure_context_is_detected_independently_of_point_manifest(self):
        self.index()
        saved = copy.deepcopy(baseline.s.read_project('dual-test'))
        saved['structureContexts'][0]['content'] += ' invented condition'
        with self.assertRaises(WindowError) as error:
            self.rt.validate_metadata(saved)
        self.assertEqual(error.exception.kind, 'STRUCTURAL_MANIFEST_MISMATCH')

    def test_unknown_indexes_short_lane_and_strict_query_fails_before_rerank_forward(self):
        unknown = copy.deepcopy(self.corpus[0])
        unknown.pop('sourceQualityHash')
        result = self.index([unknown])
        self.assertTrue(result['structuralCoverage']['contextUnavailable'])
        saved = baseline.s.read_project('dual-test')
        self.assertEqual({p['granularity'] for p in saved['windows']}, {'short'})
        with self.assertRaises(baseline.s.HTTPException) as error:
            self.query()
        self.assertEqual(error.exception.detail['kind'], 'STRUCTURAL_CONTEXT_UNAVAILABLE')
        self.assertEqual(self.predict_calls, [])

    def test_cached_dual_index_revalidates_source_plan_without_new_encode(self):
        first = self.index()
        calls = len(self.encodes)
        second = self.index()
        self.assertTrue(second['cached'])
        self.assertEqual(first['signature'], second['signature'])
        self.assertEqual(calls, len(self.encodes))

    def test_actual_tokenizer_guard_uses_explicit_long_budget_and_never_clips(self):
        guard = self.rt.guard(CharacterTokenizer(), 'embedding')
        result = guard(['a' * 30], truncation=True, max_length=12)
        self.assertEqual(sum(result['attention_mask'][0]), 32)
        with self.assertRaises(WindowError):
            guard(['a' * 65], truncation=True, max_length=60)

    def test_dual_rerank_without_full_canonical_scope_is_explicit_error(self):
        with self.assertRaises(WindowError) as error:
            self.rt.rerank('q', [self.corpus[0]])
        self.assertEqual(error.exception.kind, 'MISSING_CANONICAL_STRUCTURE_SCOPE')

    def test_boolean_numpy_output_is_not_accepted_as_model_scores(self):
        class BooleanModel:
            def predict(model, pairs, **kwargs):
                return baseline.np.asarray([True for _ in pairs])
        baseline.s.rerank_model = lambda: BooleanModel()
        with self.assertRaises(WindowError) as error:
            self.rt.rerank('q', [self.corpus[0]], self.corpus)
        self.assertEqual(error.exception.kind, 'NONFINITE_SCORE')

    def test_canonical_source_order_is_bound_to_dual_signature(self):
        first = self.rt.signature(self.corpus)
        changed = self.rt.signature(list(reversed(self.corpus)))
        self.assertNotEqual(first, changed)
        self.assertEqual(first['sourceOrderBasis'], 'canonical_request_order')


class StructuralLoaderIntegrationTests(unittest.TestCase):
    """Exercise actual server loader functions with explicit fake neural classes."""
    def setUp(self):
        path = Path(__file__).with_name('server.py')
        self.module_name = 'structural_caps_loader_test'
        spec = importlib.util.spec_from_file_location(self.module_name, path)
        self.server = importlib.util.module_from_spec(spec)
        sys.modules[self.module_name] = self.server
        spec.loader.exec_module(self.server)
        self.addCleanup(sys.modules.pop, self.module_name, None)
        s = self.server
        s.MODEL_SETTINGS = {'embedding': {'maxTokens': 12, 'batchSize': 4},
                            'reranker': {'maxTokens': 12, 'batchSize': 4}}
        s.release_other_model = lambda kind: None
        s.model_path = lambda config: 'TEST ONLY offline model path'
        s.model_options = lambda: ('cpu', {})
        s.device = lambda: 'cpu'
        s.verify_loaded_model = lambda model, kind: None
        self.rt = WindowRuntime(s)
        self.rt.overlap = 2
        self.rt.structural_recipe = StructuralRecipe('dual-structure-v1', 60, 70)
        s._windows_runtime = self.rt
        self.calls = []

    def fake_classes(self):
        class Embedding:
            default_prompt_name = None
            def __init__(model, *args, **kwargs):
                model.module = SimpleNamespace(do_lower_case=False, tokenizer=CharacterTokenizer())
            def __getitem__(model, ordinal):
                return model.module
        def reranker(*args, **kwargs):
            self.calls.append(kwargs)
            return SimpleNamespace(tokenizer=CharacterTokenizer())
        return SimpleNamespace(SentenceTransformer=Embedding, CrossEncoder=reranker)

    def test_loader_and_actual_tokenizer_guard_use_long_caps(self):
        with patch.dict(sys.modules, {'sentence_transformers': self.fake_classes()}):
            embedding = self.server.embedding_model()
            reranker = self.server.rerank_model()
        self.assertEqual(embedding.max_seq_length, 60)
        self.assertEqual(self.calls[0]['max_length'], 70)
        encoded = embedding[0].tokenizer(['a' * 30], truncation=True, max_length=12)
        self.assertEqual(sum(encoded['attention_mask'][0]), 32)
        pairs = reranker.tokenizer([('q', 'b' * 40)], truncation=True, max_length=12)
        self.assertEqual(sum(pairs['attention_mask'][0]), 45)
        with self.assertRaises(WindowError):
            embedding[0].tokenizer(['a' * 65], truncation=True, max_length=60)

    def test_baseline_loader_caps_remain_short(self):
        self.rt.structural_recipe = StructuralRecipe()
        with patch.dict(sys.modules, {'sentence_transformers': self.fake_classes()}):
            embedding = self.server.embedding_model()
            self.server.rerank_model()
        self.assertEqual(embedding.max_seq_length, 12)
        self.assertEqual(self.calls[0]['max_length'], 12)
        with self.assertRaises(WindowError):
            embedding[0].tokenizer(['a' * 30], truncation=True, max_length=12)

    def test_dual_health_discloses_contract_version_and_caps_without_loading_models(self):
        self.rt.tokenizer = lambda kind: self.fail('health must not load tokenizer')
        self.rt.identity = lambda kind: self.fail('health must not load model files')
        fake_torch = SimpleNamespace(cuda=SimpleNamespace(is_available=lambda: False))
        with patch.dict(sys.modules, {'torch': fake_torch}):
            result = self.server.health()
        self.assertEqual(result['indexSignatureVersion'], 4)
        recipe = result['retrieval']['sourceWindows']
        self.assertEqual(recipe['contract'], CONTRACT)
        self.assertEqual((recipe['longEmbeddingMaxTokens'], recipe['contextRerankMaxTokens']), (60, 70))
        self.assertFalse(recipe['tokenizerIdentityObserved'])
        self.assertEqual(result['loaded'], {'embedding': False, 'reranker': False, 'ocr': False})


if __name__ == '__main__':
    unittest.main()
