"""Bounded HTTP and full-document index with actual authored CAS and fake BGE."""
import copy
import hashlib
from pathlib import Path
from unittest.mock import patch
import unittest

from canonical_projection import _bytes
from native_collection_envelope import consume_native_collection_wire
from fastapi.testclient import TestClient
from native_canonical_projection import produce_native
from native_projection_collection import publish_native_collection
from oer_native_collection_server import (COLLECTION_ALGORITHMS, COLLECTION_CAPABILITY,
    configure_collection_environment, create_collection_app)
from test_native_canonical_projection import fixture
import test_oer_server as oer_tests
from test_token_windows import CharacterTokenizer


class NativeCollectionHttpTests(unittest.TestCase):
    tearDown = oer_tests.OerHttpContractTest.tearDown

    def setUp(self):
        oer_tests.OerHttpContractTest.setUp(self)
        self.client.close()
        self.app = create_collection_app(self.policy, backend=self.backend)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        self.client = TestClient(self.app)
        self.store = self.app.state.artifact_store
        self.bundle, self.adoption = fixture(self.store)
        self.adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        self.internal = produce_native(self.bundle, self.adoption, artifact_store=self.store)

    def collection(self):
        root = publish_native_collection(self.internal, artifact_store=self.store, max_batch_bytes=60000)
        self.app.state.source_registry.publish(root)
        return root

    def test_health_capabilities_source_hashes_and_independent_default_without_models(self):
        with patch.object(self.rt, 'tokenizer', side_effect=AssertionError('model forbidden')):
            response = self.client.get('/health')
        self.assertEqual(200, response.status_code)
        result = response.json()
        self.assertEqual(COLLECTION_CAPABILITY, result['oer']['nativeDocumentCollection'])
        for name in COLLECTION_ALGORITHMS:
            self.assertRegex(result['retrieval']['sourceWindows']['algorithms'][name], '^[a-f0-9]{64}$')
        values = configure_collection_environment({'CONSENSE_SL_SCOPE_MANIFEST': 'scope.json'}, self.root)
        self.assertEqual(self.root.resolve() / '.local/oer/native-collection-retrieval-state', Path(values['CONSENSE_RETRIEVAL_DATA']))
        with self.assertRaisesRegex(ValueError, 'separate state'):
            configure_collection_environment({'CONSENSE_SL_SCOPE_MANIFEST': 'scope.json',
                'CONSENSE_RETRIEVAL_DATA': str(self.root / '.local/oer/native-retrieval-state')}, self.root)
        self.assertEqual([], self.encodes)

    def test_root_and_complete_batch_http_bytes_exactly_match_actual_cas_descriptors(self):
        root = self.collection()
        prefix = '/canonical/' + root['projectionSha256']
        fetched = self.client.get(prefix + '?module=advisory')
        self.assertEqual(200, fetched.status_code, fetched.text)
        self.assertEqual(_bytes(root), fetched.content)
        self.assertNotIn('chunks', fetched.json())
        for descriptor in root['batches']:
            response = self.client.get(prefix + '/batches/' + str(descriptor['ordinal']) + '?module=advisory')
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(descriptor['artifactDescriptor']['byteCount'], len(response.content))
            self.assertEqual(descriptor['artifactDescriptor']['sha256'], hashlib.sha256(response.content).hexdigest())
            self.assertEqual(root['projectionSha256'], response.headers['x-projection-sha256'])
        for suffix in ('?module=drafting', '/batches/-1', '/batches/3', '/batches/0?module=drafting'):
            self.assertEqual(404, self.client.get(prefix + suffix).status_code)

    def test_full_three_batch_native_index_exact_payload_and_partial_marker_strip_rejects(self):
        root = self.collection()
        response = self.client.post('/index-canonical', json={'projectId': 'native-collection-test',
            'projectionSha256': [root['projectionSha256']], 'module': 'vetting'})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual([root['projectionSha256']], response.json()['canonicalProjections'])
        stored = self.backend._projects[self.backend.project_key('native-collection-test')]['chunks']
        self.assertEqual(self.app.state.source_registry.chunks(root['projectionSha256']), stored)
        self.assertEqual(3, len(stored))
        altered = copy.deepcopy(stored)
        for chunk in altered:
            chunk.pop('storageShape', None)
            chunk.pop('originalGeometryStatus', None)
            for part in chunk['parts']:
                part.pop('native', None)
                part.pop('sourcePosition', None)
        rejected = self.client.post('/index', json={'projectId': 'stripped-native', 'chunks': altered})
        self.assertEqual(422, rejected.status_code, rejected.text)

    def test_actual_missing_nonrequested_batch_invalidates_wire_and_index_before_models(self):
        root = self.collection()
        descriptor = root['batches'][2]['artifactDescriptor']
        path = self.store.root / descriptor['relativePath']
        path.unlink()
        prefix = '/canonical/' + root['projectionSha256']
        self.assertEqual(404, self.client.get(prefix).status_code)
        self.assertEqual(404, self.client.get(prefix + '/batches/0').status_code)
        response = self.client.post('/index-canonical', json={'projectId': 'broken',
            'projectionSha256': [root['projectionSha256']], 'module': 'vetting'})
        self.assertEqual(422, response.status_code, response.text)
        self.assertFalse(path.exists())
        self.assertEqual([], self.encodes)

    def test_complete_envelope_http_wire_reconstructs_all_public_fields_no_models(self):
        root = self.collection()
        prefix = '/canonical/' + root['projectionSha256']
        raw_envelopes = {}
        for description in root['envelopeBatches']:
            response = self.client.get(prefix + '/envelopes/' + str(description['ordinal']))
            self.assertEqual(200, response.status_code, response.text)
            self.assertLessEqual(len(response.content), root['batchPolicy']['maximumBatchBytes'])
            self.assertEqual(self.store.read_asset(description['artifactDescriptor']), response.content)
            self.assertEqual(root['projectionSha256'], response.headers['X-Projection-Sha256'])
            raw_envelopes[description['ordinal']] = response.content
        raw_chunks = {row['ordinal']: self.client.get(prefix + '/batches/' + str(row['ordinal'])).content
                      for row in root['batches']}
        actual = consume_native_collection_wire(root, expected_projection_sha256=root['projectionSha256'],
            batch_reader=raw_chunks.__getitem__, envelope_reader=raw_envelopes.__getitem__)
        self.assertEqual(self.app.state.source_registry.load(root['projectionSha256']), actual)
        self.assertEqual(404, self.client.get(prefix + '/envelopes/99').status_code)
        self.assertEqual(404, self.client.get(prefix + '/envelopes/0?module=drafting').status_code)
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_missing_header_batch_invalidates_all_routes_without_read_repair(self):
        root = self.collection()
        descriptor = root['envelopeBatches'][-1]['artifactDescriptor']
        path = self.store.root / descriptor['relativePath']
        path.unlink()
        prefix = '/canonical/' + root['projectionSha256']
        for suffix in ('', '/batches/0', '/envelopes/0'):
            self.assertEqual(404, self.client.get(prefix + suffix).status_code)
        self.assertFalse(path.exists())
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)
        self.assertEqual([], self.predictions)

    def test_projection_accepts_only_typed_actual_internal_cas_not_file_paths(self):
        descriptor = self.store.put_bytes(_bytes(self.internal))
        response = self.client.post('/project/native-collection', json={'internalProjectionDescriptor': descriptor})
        self.assertEqual(200, response.status_code, response.text)
        root = response.json()['projection']
        self.assertNotIn('chunks', root)
        self.assertEqual(200, self.client.get('/canonical/' + root['projectionSha256']).status_code)
        response = self.client.post('/project/native-collection', json={'internalProjectionDescriptor':
            {**descriptor, 'relativePath': '../outside.blob'}})
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes)

    def test_corrupt_nonobject_or_duplicate_key_registry_root_is_controlled_unavailable(self):
        root = self.collection()
        address = root['projectionSha256']
        path = self.app.state.source_registry._path(address)
        for invalid in (b'[]', b'{"projectionSha256":"a","projectionSha256":"b"}'):
            path.write_bytes(invalid)
            response = self.client.get('/canonical/' + address)
            self.assertEqual(404, response.status_code, response.text)
        self.assertEqual([], self.encodes)

    def test_publication_receipt_wrapper_is_included_in_response_byte_cap(self):
        root = publish_native_collection(self.internal, artifact_store=self.store)
        descriptor = self.store.put_bytes(_bytes(self.internal))
        with patch('oer_native_collection_server.MAX_SNAPSHOT_BYTES', len(_bytes(root)) + 1):
            response = self.client.post('/project/native-collection', json={'internalProjectionDescriptor': descriptor})
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('NATIVE_WIRE_RESPONSE_TOO_LARGE', response.json()['detail']['kind'])
        self.assertEqual([], self.encodes)


if __name__ == '__main__':
    unittest.main()
