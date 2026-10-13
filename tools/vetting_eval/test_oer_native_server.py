"""Native HTTP/registry/index contracts with actual authored CAS and fake BGE.

These checks do not grade official answers, source semantics or model quality.
"""
import copy
from pathlib import Path
from unittest.mock import patch
import unittest

from fastapi.testclient import TestClient
from native_canonical_projection import produce_native
from native_projection_transport import publish_native_transport
from oer_native_server import create_native_app, configure_native_environment, NATIVE_CAPABILITY, NATIVE_ALGORITHMS
from test_native_canonical_projection import fixture
from test_oer_server import OerHttpContractTest
from test_token_windows import CharacterTokenizer


class NativeHttpTest(unittest.TestCase):
    tearDown = OerHttpContractTest.tearDown

    def setUp(self):
        OerHttpContractTest.setUp(self)
        self.client.close()
        self.app = create_native_app(self.policy, backend=self.backend)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        self.client = TestClient(self.app)
        self.store = self.app.state.artifact_store
        self.bundle, self.adoption = fixture(self.store)
        self.adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']

    def project(self):
        response = self.client.post('/project/native', json={'bundle': self.bundle, 'adoptionManifest': self.adoption})
        self.assertEqual(200, response.status_code, response.text)
        return response.json()['projection']

    def test_health_declares_native_identity_without_loading_tokenizer_or_neural_model(self):
        with patch.object(self.rt, 'tokenizer', side_effect=AssertionError('tokenizer forbidden')):
            result = self.client.get('/health')
        self.assertEqual(200, result.status_code, result.text)
        health = result.json()
        self.assertEqual(NATIVE_CAPABILITY, health['oer']['nativeCanonical'])
        algorithms = health['retrieval']['sourceWindows']['algorithms']
        for name in (*NATIVE_ALGORITHMS, 'oer_server.py', 'canonical_projection.py'):
            self.assertRegex(algorithms[name], r'^[a-f0-9]{64}$')
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_native_actual_wire_snapshot_and_index_retain_exact_parent_and_blank_parts(self):
        public = self.project()
        address = public['projectionSha256']
        fetched = self.client.get('/canonical/' + address + '?module=advisory')
        self.assertEqual(200, fetched.status_code, fetched.text)
        self.assertEqual(public, fetched.json())
        body = self.backend.IndexRequest(projectId='native-test', chunks=public['chunks'])
        self.assertEqual(public['chunks'], [chunk.model_dump(exclude_none=True) for chunk in body.chunks])
        indexed = self.client.post('/index-canonical', json={'projectId': 'native-test', 'projectionSha256': [address], 'module': 'vetting'})
        self.assertEqual(200, indexed.status_code, indexed.text)
        self.assertEqual([address], indexed.json()['canonicalProjections'])
        stored = self.backend._projects[self.backend.project_key('native-test')]['chunks']
        self.assertEqual(public['chunks'], stored)
        self.assertEqual(3, sum(part['text'] == '' for part in stored[1]['parts']))
        self.assertTrue(all(chunk['physicalPages'] == [] and 'pageNo' not in chunk for chunk in stored))

    def test_wrong_policy_and_bq_reject_before_automatic_projection_or_model_input(self):
        for mutation in ('policy', 'class'):
            adoption = copy.deepcopy(self.adoption)
            if mutation == 'policy':
                adoption['scope']['scopePolicySha256'] = '0' * 64
            else:
                adoption['sourceClass'] = 'pricing_bq'
            response = self.client.post('/project/native', json={'bundle': self.bundle, 'adoptionManifest': adoption})
            self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)
        self.assertFalse(self.app.state.source_registry.root.exists())

    def test_native_snapshot_rechecks_cas_and_unsupported_assets_fail_locally(self):
        public = self.project()
        address = public['projectionSha256']
        asset = self.client.get('/canonical/' + address + '/sources/' + public['source']['sourceSha256'] + '/assets/asset:missing')
        self.assertEqual(404, asset.status_code)
        descriptor = public['nativeCaptureRefs']['rawParts'][0]['artifactDescriptor']
        path = self.store.root / descriptor['relativePath']
        path.write_bytes(b'CORRUPTED-AUTHORED-PART')
        response = self.client.get('/canonical/' + address)
        self.assertEqual(404, response.status_code)
        indexed = self.client.post('/index-canonical', json={'projectId': 'native-test', 'projectionSha256': [address], 'module': 'vetting'})
        self.assertEqual(422, indexed.status_code)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_software_identity_change_invalidates_native_health_before_models(self):
        with patch('oer_native_server._NATIVE_HASHES', {}):
            with self.assertRaisesRegex(ValueError, 'Native wrapper bytes changed'):
                self.rt.algorithms()
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_native_direct_index_requires_exact_registered_complete_parents(self):
        public = publish_native_transport(produce_native(self.bundle, self.adoption, artifact_store=self.store), artifact_store=self.store)
        body = {'projectId': 'native-test', 'chunks': public['chunks']}
        refused = self.client.post('/index', json=body)
        self.assertEqual(422, refused.status_code, refused.text)
        self.assertEqual([], self.encodes)
        self.app.state.source_registry.publish(public)
        for chunks in (public['chunks'][1:], list(reversed(public['chunks'])),
                       [{**public['chunks'][0], 'content': 'altered'}] + public['chunks'][1:]):
            refused = self.client.post('/index', json={'projectId': 'native-test', 'chunks': chunks})
            self.assertEqual(422, refused.status_code, refused.text)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)
        accepted = self.client.post('/index', json=body)
        self.assertEqual(200, accepted.status_code, accepted.text)

    def test_partially_stripped_native_markers_cannot_fall_back_to_legacy_admission(self):
        public = self.project()
        chunks = copy.deepcopy(public['chunks'])
        for chunk in chunks:
            chunk.pop('storageShape', None)
            chunk.pop('originalGeometryStatus', None)
            for part in chunk['parts']:
                part.pop('native', None)
                part.pop('sourcePosition', None)
        refused = self.client.post('/index', json={'projectId': 'native-test', 'chunks': chunks})
        self.assertEqual(422, refused.status_code, refused.text)
        self.assertEqual('NATIVE_INDEX_PARENT_MISMATCH', refused.json()['detail']['kind'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_native_entrypoint_defaults_to_its_own_state_and_preserves_explicit_custom_state(self):
        supplied = {'CONSENSE_SL_SCOPE_MANIFEST': str(self.scope_path)}
        settings = configure_native_environment(supplied, self.root)
        self.assertEqual(str((self.root / '.local/oer/native-retrieval-state').resolve()), settings['CONSENSE_RETRIEVAL_DATA'])
        self.assertNotIn('CONSENSE_RETRIEVAL_DATA', supplied)
        supplied['CONSENSE_RETRIEVAL_DATA'] = str(self.root / 'custom-native-state')
        self.assertEqual(str(Path(supplied['CONSENSE_RETRIEVAL_DATA']).resolve()), configure_native_environment(supplied, self.root)['CONSENSE_RETRIEVAL_DATA'])
        supplied['CONSENSE_RETRIEVAL_DATA'] = str(self.root / '.local/oer/retrieval-state')
        with self.assertRaisesRegex(ValueError, 'separate state'):
            configure_native_environment(supplied, self.root)

    def test_cas_mutation_after_index_refuses_retrieve_before_query_or_rerank(self):
        public = self.project()
        indexed = self.client.post('/index-canonical', json={'projectId': 'native-test', 'projectionSha256': [public['projectionSha256']], 'module': 'vetting'})
        self.assertEqual(200, indexed.status_code, indexed.text)
        encodes_before = copy.deepcopy(self.encodes)
        descriptor = public['nativeCaptureRefs']['adapterSource']
        (self.store.root / descriptor['relativePath']).write_bytes(b'CORRUPTED-ADAPTER')
        refused = self.client.post('/retrieve', json={'projectId': 'native-test', 'query': 'outer', 'candidates': 3, 'limit': 2})
        self.assertEqual(422, refused.status_code, refused.text)
        self.assertEqual(encodes_before, self.encodes)
        self.assertEqual([], self.predictions)

    def test_cas_mutation_during_query_refuses_result_after_inference(self):
        public = self.project()
        indexed = self.client.post('/index-canonical', json={'projectId': 'native-test', 'projectionSha256': [public['projectionSha256']], 'module': 'vetting'})
        self.assertEqual(200, indexed.status_code, indexed.text)
        descriptor = public['nativeCaptureRefs']['adapterSource']
        original_encode = self.backend.encode_embeddings
        def mutate_while_query(texts):
            result = original_encode(texts)
            (self.store.root / descriptor['relativePath']).write_bytes(b'CHANGED-DURING-QUERY')
            return result
        self.backend.encode_embeddings = mutate_while_query
        refused = self.client.post('/retrieve', json={'projectId': 'native-test', 'query': 'outer', 'candidates': 3, 'limit': 2})
        self.assertEqual(422, refused.status_code, refused.text)
        self.assertTrue(self.predictions, 'Authored fake inference ran, but its stale result was withheld')


if __name__ == '__main__':
    unittest.main()
