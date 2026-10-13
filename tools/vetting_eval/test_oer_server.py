"""OER HTTP/index guards using authored inputs and fake vector/model providers.

No actual BGE forward pass, persistent Qdrant engine, network or parser process.
TestClient exercises request validation and the real source/window integration.
"""
import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
from fastapi.testclient import TestClient

from competition_scope import load_policy
from oer_server import (FRESH_RECIPE, OerWindowRuntime, configure_environment,
                        create_app)
from test_competition_scope import manifest, seal, SOURCE_SHA
from test_canonical_projection import fixture as projection_fixture
from test_oer_source_registry import asset_fixture
from test_retrieval_source_units import parent
from test_structural_window_runtime import DualFakeDb
from test_structured_parser_evidence import run, accepted_manifest
from structured_parser_evidence import build_dual_parser_evidence
from test_token_windows import CharacterTokenizer
from test_window_server import FakeModel
from token_windows import WindowError


class OerStartupTest(unittest.TestCase):
    def test_cuda_dual2048_offline_and_separate_state_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            provided = {'CONSENSE_SL_SCOPE_MANIFEST': 'reviewed-scope.json'}
            frozen = copy.deepcopy(provided)
            values = configure_environment(provided, directory)
            self.assertEqual(frozen, provided)
            self.assertEqual('cuda', values['CONSENSE_MODEL_DEVICE'])
            self.assertEqual('float32', values['CONSENSE_MODEL_DTYPE'])
            self.assertEqual('1', values['CONSENSE_MODEL_SERIAL_OFFLOAD'])
            self.assertEqual('dual-structure-v1', values['CONSENSE_RETRIEVAL_GRANULARITY'])
            self.assertEqual('2048', values['CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS'])
            self.assertEqual('2048', values['CONSENSE_STRUCTURAL_RERANK_MAX_TOKENS'])
            self.assertEqual('true', values['CONSENSE_STRUCTURAL_RERANK_STRICT'])
            self.assertEqual('1', values['HF_HUB_OFFLINE'])
            self.assertEqual(Path(directory).resolve() / '.local/oer/retrieval-state',
                             Path(values['CONSENSE_RETRIEVAL_DATA']))

    def test_incompatible_recipe_is_rejected_before_import_or_inference(self):
        for name, value in (('CONSENSE_MODEL_DEVICE', 'cpu'),
                            ('CONSENSE_MODEL_DEVICE', 'auto'),
                            ('CONSENSE_MODEL_DEVICE', 'cuda:0'),
                            ('CONSENSE_MODEL_DTYPE', 'float16'),
                            ('CONSENSE_MODEL_SERIAL_OFFLOAD', '0'),
                            ('CONSENSE_RETRIEVAL_GRANULARITY', 'windows-v1'),
                            ('CONSENSE_STRUCTURAL_RERANK_STRICT', 'false'),
                            ('CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS', '4096'),
                            ('HF_HUB_OFFLINE', '0')):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(ValueError, name):
                    configure_environment({'CONSENSE_SL_SCOPE_MANIFEST': 'scope.json', name: value}, directory)

    def test_all_oer_entrypoints_require_cuda_and_keep_fp32_scope_and_token_gates(self):
        from oer_native_server import configure_native_environment
        from oer_native_pdf_server import configure_native_pdf_environment
        from oer_native_collection_server import configure_collection_environment
        from oer_unified_server import configure_unified_environment
        functions = (configure_environment, configure_native_environment,
                     configure_native_pdf_environment, configure_collection_environment,
                     configure_unified_environment)
        for configure in functions:
            with self.subTest(entry=configure.__name__), tempfile.TemporaryDirectory() as directory:
                supplied = {'CONSENSE_SL_SCOPE_MANIFEST': 'scope.json'}
                values = configure(supplied, directory)
                self.assertEqual(supplied, {'CONSENSE_SL_SCOPE_MANIFEST': 'scope.json'})
                self.assertEqual('cuda', values['CONSENSE_MODEL_DEVICE'])
                self.assertEqual('float32', values['CONSENSE_MODEL_DTYPE'])
                self.assertEqual('1', values['CONSENSE_MODEL_SERIAL_OFFLOAD'])
                self.assertEqual('512', values['CONSENSE_EMBED_MAX_TOKENS'])
                self.assertEqual('2048', values['CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS'])
                self.assertEqual('true', values['CONSENSE_STRUCTURAL_RERANK_STRICT'])
                self.assertEqual('1', values['HF_HUB_OFFLINE'])
                for device in ('cpu', 'auto'):
                    with self.assertRaisesRegex(ValueError, 'CONSENSE_MODEL_DEVICE.*cuda'):
                        configure({**supplied, 'CONSENSE_MODEL_DEVICE': device}, directory)
                with self.assertRaisesRegex(ValueError, 'CONSENSE_MODEL_DTYPE'):
                    configure({**supplied, 'CONSENSE_MODEL_DTYPE': 'float16'}, directory)
                for offload in ('0', 'true'):
                    with self.assertRaisesRegex(ValueError, 'CONSENSE_MODEL_SERIAL_OFFLOAD'):
                        configure({**supplied, 'CONSENSE_MODEL_SERIAL_OFFLOAD': offload}, directory)

    def test_legacy_cache_and_embedding_compatibility_are_not_reused(self):
        for name in ('CONSENSE_FIXED_VECTOR_CACHE', 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST',
                     'CONSENSE_EMBEDDING_COMPATIBILITY_RECEIPT'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(ValueError, 'fresh OER'):
                    configure_environment({'CONSENSE_SL_SCOPE_MANIFEST': 'scope.json', name: 'legacy'}, directory)

    def test_missing_manifest_or_legacy_state_is_a_startup_error(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'CONSENSE_SL_SCOPE_MANIFEST'):
                configure_environment({}, directory)
            with self.assertRaisesRegex(ValueError, 'separate state'):
                configure_environment({'CONSENSE_SL_SCOPE_MANIFEST': 'scope.json',
                    'CONSENSE_RETRIEVAL_DATA': str(Path(directory) / 'tmp/vetting_server')}, directory)
            with self.assertRaisesRegex(ValueError, 'separate OER state'):
                configure_environment({'CONSENSE_SL_SCOPE_MANIFEST': 'scope.json',
                    'CONSENSE_RETRIEVAL_DATA': ''}, directory)


class OerHttpContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {'CONSENSE_MODEL_DEVICE': 'cuda', 'CONSENSE_MODEL_DTYPE': 'float32',
            'CONSENSE_MODEL_SERIAL_OFFLOAD': '1',
            'CONSENSE_RETRIEVAL_GRANULARITY': 'dual-structure-v1',
            'CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS': '2048',
            'CONSENSE_STRUCTURAL_RERANK_MAX_TOKENS': '2048',
            'CONSENSE_STRUCTURAL_RERANK_STRICT': 'true',
            'CONSENSE_EMBED_MAX_TOKENS': '512', 'CONSENSE_RERANK_MAX_TOKENS': '512'})
        self.env.start()
        for name in ('CONSENSE_FIXED_VECTOR_CACHE', 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST',
                     'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256',
                     'CONSENSE_EMBEDDING_COMPATIBILITY_RECEIPT',
                     'CONSENSE_EMBEDDING_COMPATIBILITY_RECEIPT_SHA256'):
            os.environ.pop(name, None)
        name = 'oer_http_contract_backend'
        spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name('server.py'))
        self.backend = importlib.util.module_from_spec(spec)
        sys.modules[name] = self.backend
        spec.loader.exec_module(self.backend)
        self.backend.STATE = self.root / 'fresh-state'
        # Fake runtime identity only; no real GPU/model is instantiated by these contracts.
        self.backend._selected_device = 'cuda'
        self.db, self.encodes, self.predictions, self.events = DualFakeDb(), [], [], []
        self.backend.database = lambda: self.db
        self.backend.embedding_model = lambda: FakeModel()
        outer = self

        class RecordingModel(FakeModel):
            def predict(self, pairs, **kwargs):
                outer.predictions.append(copy.deepcopy(pairs))
                return super().predict(pairs, **kwargs)

        self.backend.rerank_model = lambda: RecordingModel()

        def encode(texts):
            self.encodes.append(copy.deepcopy(texts))
            return np.asarray([[1., 0.] for _ in texts], dtype=np.float32)

        self.backend.encode_embeddings = encode
        self.backend._window_observer = lambda stage, value: self.events.append((stage, value))
        self.scope_path = self.root / 'scope.json'
        self.scope_path.write_text(json.dumps(manifest()), encoding='utf-8')
        self.policy = load_policy(self.scope_path)
        self.app = create_app(self.policy, backend=self.backend)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        self.client = TestClient(self.app)
        self.corpus = [parent('z-heading', 'body:1', 'A heading', 'A1', 'body/1'),
                       parent('a-condition', 'body:2', 'Only after written approval.', 'A1', 'body/1')]

    def tearDown(self):
        self.client.close()
        self.env.stop()
        self.temp.cleanup()

    def index(self, chunks=None):
        return self.client.post('/index', json={'projectId': 'oer-test',
                                               'chunks': self.corpus if chunks is None else chunks})

    def query(self):
        return self.client.post('/retrieve', json={'projectId': 'oer-test', 'query': 'approval',
                                                   'candidates': 2, 'limit': 1})

    def test_health_keeps_identity_inference_free_and_scope_in_fingerprint(self):
        with patch.object(self.rt, 'tokenizer', side_effect=AssertionError('tokenizer forbidden')):
            response = self.client.get('/health')
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual(4, body['indexSignatureVersion'])
        self.assertEqual(FRESH_RECIPE, body['oer']['recipe'])
        windows = body['retrieval']['sourceWindows']
        self.assertEqual(self.policy.policyIdentity, windows['competitionScope'])
        self.assertIn('oer_server.py', windows['algorithms'])
        self.assertIn('competition_scope.py', windows['algorithms'])
        self.assertEqual(2048, windows['longEmbeddingMaxTokens'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_health_describes_external_complete_parsers_and_exposes_no_cpu_ocr_route(self):
        response = self.client.get('/health')
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertIn('External source-bound complete parsers', body['ocrEngine'])
        self.assertFalse(body['ocrRouteExposed'])
        self.assertNotIn('ocr', body['loaded'])
        self.assertEqual(404, self.client.post('/ocr').status_code)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_app_cannot_reconfigure_an_already_loaded_legacy_model_or_database(self):
        for field in ('_embedding', '_reranker', '_database'):
            with self.subTest(field=field), patch.object(self.backend, field, object()):
                with self.assertRaises(WindowError) as error:
                    create_app(self.policy, backend=self.backend)
                self.assertEqual('OER_STARTUP_RECIPE', error.exception.kind)

    def test_cpu_backend_is_rejected_by_the_shared_oer_runtime_gate(self):
        with patch.object(self.backend, '_selected_device', 'cpu'):
            with self.assertRaises(WindowError) as error:
                create_app(self.policy, backend=self.backend)
        self.assertEqual('OER_CUDA_REQUIRED', error.exception.kind)
        self.assertIn('CUDA', str(error.exception))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_cuda_unavailable_or_probe_error_cannot_fall_back_to_cpu(self):
        for probe in (lambda: False, lambda: (_ for _ in ()).throw(RuntimeError('AUTHORED CUDA probe failure'))):
            with self.subTest(probe=probe):
                torch = SimpleNamespace(cuda=SimpleNamespace(is_available=probe),
                                        set_num_threads=lambda count: None)
                with patch.object(self.backend, '_selected_device', None), patch.dict(sys.modules, {'torch': torch}):
                    with self.assertRaisesRegex(RuntimeError, 'CUDA'):
                        create_app(self.policy, backend=self.backend)
                    self.assertIsNone(self.backend._selected_device)
                    self.assertIsNone(self.backend._embedding)
                    self.assertIsNone(self.backend._reranker)
                    self.assertIsNone(self.backend._database)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_nonserial_cuda_backend_is_rejected_before_model_or_database_publication(self):
        for value in (False, '1', None):
            with self.subTest(serialOffload=value), patch.object(self.backend, 'SERIAL_OFFLOAD', value):
                with self.assertRaises(WindowError) as error:
                    create_app(self.policy, backend=self.backend)
                self.assertEqual('OER_STARTUP_RECIPE', error.exception.kind)
                self.assertIsNone(self.backend._embedding)
                self.assertIsNone(self.backend._reranker)
                self.assertIsNone(self.backend._database)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_runtime_recipe_and_signature_bind_cuda_fp32_and_refuse_a_persisted_cpu_index(self):
        first = self.index()
        self.assertEqual(200, first.status_code, first.text)
        stored = self.backend._projects['oer-test']
        self.assertEqual('cuda', stored['signature']['runtime']['device'])
        self.assertEqual('float32', stored['signature']['runtime']['dtype'])
        self.assertIs(stored['signature']['runtime']['serialOffload'], True)
        self.assertIn('cuda-fp32', stored['signature']['oerIndexRecipe'])
        recipe = self.rt.retrieval_recipe(observe_tokenizer_identity=False)
        self.assertEqual('cuda', recipe['rerankRuntime']['device'])
        self.assertEqual('float32', recipe['rerankRuntime']['dtype'])
        self.assertIs(recipe['rerankRuntime']['serialOffload'], True)
        self.assertIn('cuda-fp32', recipe['oerIndexRecipe'])
        calls = len(self.encodes)
        stored['signature']['runtime']['device'] = 'cpu'
        with self.assertRaises(self.backend.HTTPException) as error:
            self.backend.read_project('oer-test')
        self.assertEqual(409, error.exception.status_code)
        self.assertEqual(calls, len(self.encodes))
        self.assertEqual([], self.predictions)

    def test_excluded_input_rejects_entire_java_request_before_signature_or_write(self):
        pricing = copy.deepcopy(self.corpus[1])
        pricing['sourceClass'] = 'pricing_bq'
        chunks = [self.corpus[0], pricing]
        before = copy.deepcopy(chunks)
        with patch.object(self.rt, 'signature', side_effect=AssertionError('signature forbidden')):
            response = self.index(chunks)
        self.assertEqual(422, response.status_code)
        self.assertEqual('COMPETITION_SCOPE_UNFILTERED_REQUEST', response.json()['detail']['kind'])
        self.assertEqual(before, chunks)
        self.assertEqual({}, self.db.collections)
        self.assertEqual([], self.encodes)
        self.assertFalse(self.backend.metadata_path('oer-test').exists())

    def test_missing_original_sha_or_mixed_sl_mapping_rejects_without_text_slicing(self):
        sl = copy.deepcopy(self.corpus[0])
        sl.update(fileKey='SL', pageNo='P1127')
        response = self.index([sl])
        self.assertEqual('sl_original_source_identity_missing', response.json()['detail']['metadata']['code'])
        sl['originalSourceSha256'] = SOURCE_SHA
        sl['parts'][0]['pageNo'] = 1126
        sl['parts'].append({**sl['parts'][0], 'pageNo': 1127})
        response = self.index([sl])
        self.assertEqual(422, response.status_code)
        self.assertEqual('mixed_scope_chunk_requires_upstream_split', response.json()['detail']['metadata']['code'])
        self.assertEqual([], self.encodes)

    def test_project_pre_and_contract_references_keep_full_payload_and_unknown_quality(self):
        self.corpus[0]['sourceClass'] = 'project_pre'
        self.corpus[0]['content'] = 'A heading referring to BQ and SL Preliminaries'
        self.corpus[0]['parts'][0].update(text=self.corpus[0]['content'],
                                         endOffset=len(self.corpus[0]['content']))
        first = self.index()
        self.assertEqual(200, first.status_code, first.text)
        self.assertEqual(2, first.json()['indexed'])
        self.assertEqual(2, first.json()['competitionScope']['indexedChunks'])
        stored = self.backend._projects['oer-test']
        self.assertEqual(self.corpus, stored['chunks'])
        self.assertEqual(self.policy.policyIdentity, stored['signature']['competitionScope'])
        second = self.index()
        self.assertTrue(second.json()['cached'])
        response = self.query()
        self.assertEqual(200, response.status_code, response.text)
        originals = {p['id']: p for p in self.corpus}
        for hit in response.json()['hits']:
            self.assertEqual(originals[hit['id']], hit['payload'])
            self.assertEqual('unknown', hit['payload']['sourceQuality']['semanticQuality'])
        for unit in response.json()['sourceUnits']['units']:
            self.assertFalse(unit['semanticScopeVerified'])

    def test_scope_change_invalidates_old_index_identity_instead_of_reusing_cached_vectors(self):
        self.assertEqual(200, self.index().status_code)
        changed = manifest()
        changed['scopeVersion'] = 'AUTHORED-policy-revision-2'
        seal(changed)
        self.scope_path.write_text(json.dumps(changed), encoding='utf-8')
        policy = load_policy(self.scope_path)
        new_runtime = OerWindowRuntime(self.backend, policy)
        new_runtime.tokenizer = self.rt.tokenizer
        new_runtime.identity = self.rt.identity
        self.backend._windows_runtime = new_runtime
        with self.assertRaises(self.backend.HTTPException) as error:
            self.backend.read_project('oer-test')
        self.assertEqual(409, error.exception.status_code)

    def test_persisted_excluded_input_is_rejected_before_query_embedding(self):
        self.assertEqual(200, self.index().status_code)
        self.backend._projects['oer-test']['chunks'][0]['sourceClass'] = 'works_related_pss'
        count = len(self.encodes)
        response = self.query()
        self.assertEqual(422, response.status_code)
        self.assertEqual('COMPETITION_SCOPE_UNFILTERED_REQUEST', response.json()['detail']['kind'])
        self.assertEqual(count, len(self.encodes))
        self.assertEqual([], self.predictions)

    def test_dual_parser_candidate_and_selected_region_require_canonical_upstream(self):
        paddle, mineru = run('paddleocr-vl'), run('mineru')
        for acceptance in (None, accepted_manifest(paddle)):
            bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=acceptance)
            before = copy.deepcopy(bundle)
            chunk = copy.deepcopy(self.corpus[0])
            chunk['parserEvidence'] = bundle
            response = self.index([chunk])
            self.assertEqual(422, response.status_code)
            self.assertEqual('PARSER_CANDIDATE_REQUIRES_UPSTREAM_PROJECTION', response.json()['detail']['kind'])
            self.assertEqual(before, bundle)
            self.assertEqual('needs_review', bundle['qualityStatus'])
            self.assertFalse(bundle['humanConfirmed'])
        self.assertEqual([], self.encodes)

    def test_identity_and_quality_unknown_still_fails_strict_context(self):
        unknown = copy.deepcopy(self.corpus[0])
        unknown.pop('sourceQualityHash')
        response = self.index([unknown])
        self.assertEqual(200, response.status_code)
        self.assertTrue(response.json()['structuralCoverage']['contextUnavailable'])
        response = self.query()
        self.assertEqual(503, response.status_code)
        self.assertEqual('STRUCTURAL_CONTEXT_UNAVAILABLE', response.json()['detail']['kind'])
        self.assertEqual([], self.predictions)

    def test_evaluation_answers_still_rejected_by_existing_corpus_gate(self):
        answer = copy.deepcopy(self.corpus[0])
        answer['sourcePath'] = '2e_Questions and answers/answer.pdf'
        response = self.index([answer])
        self.assertEqual(422, response.status_code)
        self.assertEqual([], self.encodes)

    def project_fixture(self, **updates):
        bundle, adoption = projection_fixture()
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        adoption.update(updates)
        response = self.client.post('/project', json={'bundle': bundle, 'adoptionManifest': adoption})
        self.assertEqual(200, response.status_code, response.text)
        return response.json()['projection']

    def test_projection_index_retrieve_preserves_complete_table_group_and_unknowns(self):
        projection = self.project_fixture()
        response = self.client.post('/index-canonical', json={'projectId': 'oer-test',
            'projectionSha256': [projection['projectionSha256']], 'module': 'vetting'})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(projection['chunks'], self.backend._projects['oer-test']['chunks'])
        self.assertEqual('vetting', response.json()['competitionScope']['targetModule'])
        self.assertFalse(projection['humanConfirmed'])
        response = self.query()
        self.assertEqual(200, response.status_code, response.text)
        members = [p for u in response.json()['sourceUnits']['units'] for p in u['members']]
        self.assertTrue(any(p['physicalPages'] == [3, 4] for p in members))
        self.assertTrue(all(p['sourceQuality']['semanticQuality'] == 'unknown' for p in members))
        self.assertTrue(all(not u['semanticScopeVerified'] for u in response.json()['sourceUnits']['units']))

    def test_request_receipts_capture_exact_pool_scores_and_terminal_failure(self):
        projection = self.project_fixture()
        supplied = {'projectId': 'oer-test',
                    'projectionSha256': [projection['projectionSha256']], 'module': 'vetting'}
        response = self.client.post('/index-canonical', json=supplied)
        self.assertEqual(200, response.status_code, response.text)
        root = self.backend.STATE / 'run-evidence'
        receipts = list(root.glob('*/receipt.json'))
        self.assertEqual(1, len(receipts), 'Canonical index must not duplicate its nested index receipt')
        directory = receipts[0].parent
        self.assertEqual(supplied, json.loads((directory / 'request.json').read_text(encoding='utf-8')))
        events = [json.loads(line) for line in (directory / 'events.jsonl').read_text(encoding='utf-8').splitlines()]
        nested = next(e for e in events if e['stage'] == 'nested-operation-input')
        self.assertEqual(projection['chunks'], nested['payload']['request']['chunks'])
        actual = self.query()
        self.assertEqual(200, actual.status_code, actual.text)
        directories = [p for p in root.iterdir() if p != directory]
        self.assertEqual(1, len(directories))
        query_dir = directories[0]
        self.assertEqual(actual.json(), json.loads((query_dir / 'response.json').read_text(encoding='utf-8')))
        events = [json.loads(line) for line in (query_dir / 'events.jsonl').read_text(encoding='utf-8').splitlines()]
        stages = {e['stage'] for e in events}
        self.assertTrue({'parent-rrf-candidates', 'rerank-same-pool-structure-comparison',
                         'parent-final-ranking', 'source-unit-closure'} <= stages)
        import hashlib
        receipt = json.loads((query_dir / 'receipt.json').read_text(encoding='utf-8'))
        self.assertEqual('completed', receipt['terminalStatus'])
        self.assertFalse(receipt['qualityCertified'])
        raw = (query_dir / 'events.jsonl').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt['events']['sha256'])
        self.assertEqual(len(raw), receipt['events']['byteCount'])
        invalid = self.client.post('/index-canonical', json={**supplied, 'module': 'advisory'})
        self.assertEqual(422, invalid.status_code)
        failed = [json.loads(p.read_text(encoding='utf-8')) for p in root.glob('*/receipt.json')
                  if json.loads(p.read_text(encoding='utf-8'))['terminalStatus'] == 'failed']
        self.assertEqual(1, len(failed))
        self.assertEqual('INDEX_TARGET_MODULE_MISMATCH', failed[0]['error']['detail']['kind'])
        self.assertEqual(0, failed[0]['events']['count'])

    def test_explicit_module_revision_is_indexed_only_by_its_configured_process(self):
        projection = self.project_fixture(module='advisory', eligibleModules=['advisory'])
        response = self.client.post('/index-canonical', json={'projectId': 'oer-test',
            'projectionSha256': [projection['projectionSha256']], 'module': 'advisory'})
        self.assertEqual(422, response.status_code)
        self.assertEqual('INDEX_TARGET_MODULE_MISMATCH', response.json()['detail']['kind'])
        self.assertEqual([], self.encodes)
        self.assertFalse(self.backend.metadata_path('oer-test').exists())
        # Reconfigure only this inference-free test backend; live processes use
        # separate startup module identities and state paths.
        self.client.close()
        self.app = create_app(self.policy, backend=self.backend, target_module='advisory')
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        self.client = TestClient(self.app)
        response = self.client.post('/index-canonical', json={'projectId': 'oer-test',
            'projectionSha256': [projection['projectionSha256']], 'module': 'advisory'})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual('advisory', self.backend._projects['oer-test']['signature']['oerTargetModule'])
        self.assertEqual(200, self.query().status_code)

    def test_canonical_revision_duplicates_stale_source_and_module_reject_before_models(self):
        projection = self.project_fixture()
        address = projection['projectionSha256']
        response = self.client.post('/index-canonical', json={'projectId': 'oer-test',
            'projectionSha256': [address, address]})
        self.assertEqual(422, response.status_code)
        self.assertEqual('DUPLICATE_CANONICAL_REVISION', response.json()['detail']['kind'])
        self.assertEqual(404, self.client.get('/canonical/' + 'a' * 64).status_code)
        self.assertEqual(404, self.client.get('/canonical/' + address + '?module=drafting').status_code)
        self.assertEqual(200, self.client.get('/canonical/' + address + '?module=advisory').status_code)
        response = self.client.get('/canonical/' + address + '/sources/' + 'b' * 64 + '/assets/missing')
        self.assertEqual(404, response.status_code)
        self.assertEqual([], self.encodes)

    def test_adoption_quality_upgrade_and_partial_table_group_rejected_without_snapshot(self):
        for change in ('quality', 'partial'):
            with self.subTest(change=change):
                bundle, adoption = projection_fixture()
                adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
                if change == 'quality':
                    adoption['quality']['semanticQuality'] = 'verified'
                else:
                    adopted_run = adoption['units'][1]['runId']
                    adoption['tableGroups'][0]['candidates'][adopted_run].pop()
                response = self.client.post('/project', json={'bundle': bundle, 'adoptionManifest': adoption})
                self.assertEqual(422, response.status_code)
        self.assertFalse(self.app.state.source_registry.root.exists())
        self.assertEqual([], self.encodes)

    def test_real_asset_bytes_return_complete_group_with_revision_source_and_media_checks(self):
        bundle, adoption, originals = asset_fixture(store=self.app.state.artifact_store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        response = self.client.post('/project', json={'bundle': bundle, 'adoptionManifest': adoption})
        self.assertEqual(200, response.status_code, response.text)
        projection = response.json()['projection']
        self.assertEqual([[3], [4]], [a['physicalPages'] for a in projection['assetRefs']])
        for asset in projection['assetRefs']:
            url = '/canonical/' + projection['projectionSha256'] + '/sources/' + asset['sourceSha256'] + '/assets/' + asset['assetId']
            response = self.client.get(url)
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(originals[asset['artifactName']], response.content)
            self.assertEqual('image/png', response.headers['content-type'])
            self.assertEqual('nosniff', response.headers['x-content-type-options'])
            self.assertEqual('"' + asset['sha256'] + '"', response.headers['etag'])
            self.assertEqual(asset['sourceSha256'], response.headers['x-source-sha256'])
            self.assertEqual(200, self.client.get(url + '?module=advisory').status_code)
            self.assertEqual(404, self.client.get(url + '?module=drafting').status_code)
            tampered = self.app.state.artifact_store.root / asset['artifactDescriptor']['relativePath']
            tampered.write_bytes(b'corrupted evidence')
            self.assertEqual(404, self.client.get(url).status_code)
        self.assertEqual([], self.encodes)

    def test_stale_scope_policy_cannot_create_or_index_a_new_corpus(self):
        bundle, adoption = projection_fixture()
        response = self.client.post('/project', json={'bundle': bundle, 'adoptionManifest': adoption})
        self.assertEqual(422, response.status_code)
        self.assertEqual('PROJECTION_SCOPE_POLICY_MISMATCH', response.json()['detail']['kind'])
        # Simulate a valid immutable revision produced by a previous policy.
        from canonical_projection import project_canonical
        previous = project_canonical(bundle, adoption, artifact_store=self.app.state.artifact_store)
        self.app.state.source_registry.publish(previous)
        response = self.client.post('/index-canonical', json={'projectId': 'oer-test',
            'projectionSha256': [previous['projectionSha256']]})
        self.assertEqual(422, response.status_code)
        self.assertEqual('PROJECTION_SCOPE_POLICY_MISMATCH', response.json()['detail']['kind'])
        self.assertEqual([], self.encodes)
        self.assertFalse(self.backend.metadata_path('oer-test').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
