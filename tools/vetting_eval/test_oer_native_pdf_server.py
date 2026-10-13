"""Authored actual-CAS native PDF HTTP contracts with fake vector inference."""
import copy
import json
from pathlib import Path
from unittest.mock import patch
import unittest

from fastapi.testclient import TestClient
from canonical_projection import _bytes
from oer_native_pdf_server import (create_native_pdf_app, configure_native_pdf_environment,
                                   NATIVE_PDF_CAPABILITY, NATIVE_PDF_ALGORITHMS)
from test_native_pdf_canonical_projection import fixture
import test_oer_server as http_fixture
from test_token_windows import CharacterTokenizer


class NativePdfHttpTest(unittest.TestCase):
    tearDown = http_fixture.OerHttpContractTest.tearDown

    def setUp(self):
        http_fixture.OerHttpContractTest.setUp(self)
        self.client.close()
        self.app = create_native_pdf_app(self.policy, backend=self.backend)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        self.client = TestClient(self.app); self.store = self.app.state.artifact_store
        native, dual, adoption = fixture(self.store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        # Two separate complete prose parents test source order and omission.
        reference = json.loads(self.store.read_asset(adoption['reference']['artifactDescriptor']))
        # Authored hierarchy only; no competition-source or human acceptance claim.
        reference['relations'] = [{'type': 'parent', 'fromRegionId': 'r2', 'toRegionId': 'r1',
            'humanConfirmed': False, 'sourceReview': 'authored_contract_fixture_declared_parent',
            'basis': 'Authored contract-only hierarchy: fixture r2 (physical page 2; second unit) '
                     'is the declared child of r1 (physical page 1; A1 unit). '
                     'No official contract, answer, or semantic acceptance is asserted.'}]
        descriptor = self.store.put_bytes(_bytes(reference))
        adoption['reference'].update(artifactDescriptor=descriptor, evidenceSha256=descriptor['sha256'])
        adoption['regionReview']['referenceSha256'] = descriptor['sha256']
        adoption['units'][0]['regionIds'] = ['r1']
        adoption['units'].append({'unitId': 'second', 'scope': 'whole_text_unit', 'regionIds': ['r2'],
             'partSeparator': {'kind': 'reviewed_region_boundary', 'text': '\n'}, 'clause': None})
        self.inputs = (native, dual, adoption)
        self.body = {key: self.store.put_bytes(_bytes(value), identity_scope='canonical_json_object')
                     for key, value in zip(('nativeBundleRef', 'dualBundleRef', 'adoptionManifestRef'), self.inputs)}

    def project(self):
        response = self.client.post('/project/native-pdf', json=self.body)
        self.assertEqual(200, response.status_code, response.text)
        return response.json()['projection']

    def index(self, public):
        response = self.client.post('/index-canonical', json={'projectId': 'native-pdf-test',
                         'projectionSha256': [public['projectionSha256']], 'module': 'vetting'})
        self.assertEqual(200, response.status_code, response.text)
        return response

    def query(self):
        return self.client.post('/retrieve', json={'projectId': 'native-pdf-test', 'query': 'Visible', 'candidates': 3, 'limit': 2})

    def test_health_is_inference_free_and_binds_pdf_algorithms(self):
        with patch.object(self.rt, 'tokenizer', side_effect=AssertionError('tokenizer forbidden')):
            response = self.client.get('/health')
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(NATIVE_PDF_CAPABILITY, response.json()['oer']['nativePdfCanonical'])
        algorithms = response.json()['retrieval']['sourceWindows']['algorithms']
        for name in NATIVE_PDF_ALGORITHMS: self.assertRegex(algorithms[name], r'^[a-f0-9]{64}$')
        self.assertEqual([], self.encodes); self.assertEqual([], self.predictions)

    def test_descriptor_only_project_snapshot_and_index_keep_actual_complete_parents(self):
        public = self.project()
        self.assertEqual(['Visible fi cafe', 'Second page'], [c['content'] for c in public['chunks']])
        fetched = self.client.get('/canonical/' + public['projectionSha256'] + '?module=advisory')
        self.assertEqual(200, fetched.status_code, fetched.text); self.assertEqual(public, fetched.json())
        self.index(public)
        stored = self.backend._projects[self.backend.project_key('native-pdf-test')]['chunks']
        self.assertEqual(public['chunks'], stored)
        result = self.query(); self.assertEqual(200, result.status_code, result.text)
        self.assertTrue(self.encodes); self.assertTrue(self.predictions)

    def test_inline_extra_missing_or_invalid_descriptor_refuses_before_inference(self):
        bodies = [{'nativeBundle': self.inputs[0], 'dualBundle': self.inputs[1], 'adoptionManifest': self.inputs[2]},
                  {**self.body, 'extra': 1}, {key: value for key, value in self.body.items() if key != 'dualBundleRef'}]
        bad = copy.deepcopy(self.body); bad['nativeBundleRef']['relativePath'] = '../outside.blob'; bodies.append(bad)
        bad = copy.deepcopy(self.body); bad['nativeBundleRef']['remoteUrl'] = 'https://example.invalid'; bodies.append(bad)
        for field in ('nativeBundleRef', 'dualBundleRef', 'adoptionManifestRef'):
            bad = copy.deepcopy(self.body)
            bad[field] = self.store.put_bytes(_bytes([]), identity_scope='canonical_json_object')
            bodies.append(bad)
        for body in bodies:
            with self.subTest(keys=list(body)):
                response = self.client.post('/project/native-pdf', json=body)
                self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes); self.assertEqual([], self.predictions)
        self.assertFalse(self.app.state.source_registry.root.exists())

    def test_bare_parent_declaration_retains_unknown_and_blocks_structural_rerank(self):
        native, dual, adoption = copy.deepcopy(self.inputs)
        reference = json.loads(self.store.read_asset(adoption['reference']['artifactDescriptor']))
        reference['relations'] = [{'type': 'parent', 'fromRegionId': 'r2', 'toRegionId': 'r1'}]
        descriptor = self.store.put_bytes(_bytes(reference))
        adoption['reference'].update(artifactDescriptor=descriptor, evidenceSha256=descriptor['sha256'])
        adoption['regionReview']['referenceSha256'] = descriptor['sha256']
        self.body = {key: self.store.put_bytes(_bytes(value), identity_scope='canonical_json_object')
                     for key, value in zip(('nativeBundleRef', 'dualBundleRef', 'adoptionManifestRef'),
                                           (native, dual, adoption))}
        public = self.project(); self.index(public)
        indexed_encodes = copy.deepcopy(self.encodes)
        response = self.query()
        self.assertEqual(503, response.status_code, response.text)
        detail = response.json()['detail']
        self.assertEqual('STRUCTURAL_CONTEXT_UNAVAILABLE', detail['kind'])
        rows = detail['metadata']['contextUnavailable']
        self.assertTrue(rows)
        self.assertTrue(all(row['status'] == 'unknown' and
                            row['reason'] == 'hierarchy_parent_declaration_untrusted' for row in rows))
        self.assertEqual([], self.predictions)
        self.assertGreater(len(self.encodes), len(indexed_encodes))

    def test_duplicate_raw_json_keys_and_oversized_request_are_refused(self):
        raw = _bytes(self.body)
        duplicate = raw[:-1] + b',"nativeBundleRef":' + _bytes(self.body['nativeBundleRef']) + b'}'
        response = self.client.post('/project/native-pdf', content=duplicate, headers={'Content-Type': 'application/json'})
        self.assertEqual(422, response.status_code, response.text)
        with patch('oer_native_pdf_server.MAX_MESSAGE_BYTES', 1024):
            app = create_native_pdf_app(self.policy, backend=self.backend, artifact_store=self.store)
        with TestClient(app) as client:
            response = client.post('/project/native-pdf', content=b' ' * 1025, headers={'Content-Type': 'application/json'})
        self.assertEqual(413, response.status_code, response.text)
        self.assertEqual([], self.encodes)

    def test_complete_reply_budget_includes_projection_receipt_envelope(self):
        public = self.project()
        cap = len(_bytes(public))
        with patch('oer_native_pdf_server.MAX_MESSAGE_BYTES', cap):
            app = create_native_pdf_app(self.policy, backend=self.backend, artifact_store=self.store)
        with TestClient(app) as client:
            response = client.post('/project/native-pdf', json=self.body)
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('NATIVE_PDF_MESSAGE_TOO_LARGE', response.json()['detail']['kind'])
        self.assertEqual([], self.encodes)

    def test_direct_index_requires_registered_exact_whole_parents_and_independent_markers(self):
        public = self.project()
        for chunks in (public['chunks'][1:], list(reversed(public['chunks'])),
                       [{**public['chunks'][0], 'content': 'changed'}] + public['chunks'][1:]):
            response = self.client.post('/index', json={'projectId': 'native-pdf-test', 'chunks': chunks})
            self.assertEqual(422, response.status_code, response.text)
        chunks = copy.deepcopy(public['chunks'])
        for chunk in chunks:
            for key in ('producerKind', 'storageShape', 'projectionVersion', 'metadataVersion'): chunk.pop(key, None)
        response = self.client.post('/index', json={'projectId': 'native-pdf-test', 'chunks': chunks})
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes); self.assertEqual([], self.predictions)

    def test_cold_cas_mutation_before_query_and_disk_restore_prevents_model_input(self):
        public = self.project(); self.index(public)
        encodes = copy.deepcopy(self.encodes)
        descriptor = public['nativeCaptureRefs']['rawObservation']
        (self.store.root / descriptor['relativePath']).write_bytes(b'changed raw observation')
        self.backend._projects.clear()
        response = self.query()
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual(encodes, self.encodes); self.assertEqual([], self.predictions)

    def test_cold_mutation_during_query_withholds_stale_result(self):
        public = self.project(); self.index(public)
        descriptor = public['nativeCaptureRefs']['sourcePackage']; original = self.backend.encode_embeddings
        def changed(texts):
            result = original(texts)
            (self.store.root / descriptor['relativePath']).write_bytes(b'changed during query')
            return result
        self.backend.encode_embeddings = changed
        response = self.query()
        self.assertEqual(422, response.status_code, response.text)
        self.assertTrue(self.predictions)

    def test_unknown_schema_or_stripped_pdf_public_cannot_use_scanned_gate(self):
        public = self.project()
        for schema, producer in [('consense.canonical-projection.v1', 'scanned_pdf'),
                                 ('consense.native-canonical-projection.v1', 'native_docx')]:
            snapshot = copy.deepcopy(public); snapshot['schema'] = schema; snapshot['producerKind'] = producer
            snapshot.pop('storageShape')
            with self.assertRaises(ValueError): self.app.state.source_registry.publish(snapshot)
        path = self.app.state.source_registry._path(public['projectionSha256'])
        path.write_bytes(_bytes([]))
        fetched = self.client.get('/canonical/' + public['projectionSha256'])
        self.assertEqual(404, fetched.status_code, fetched.text)
        indexed = self.client.post('/index-canonical', json={'projectId': 'bad-array',
                              'projectionSha256': [public['projectionSha256']], 'module': 'vetting'})
        self.assertEqual(422, indexed.status_code, indexed.text)
        self.assertEqual([], self.encodes); self.assertEqual([], self.predictions)

    def test_software_change_and_shared_state_are_rejected(self):
        with patch('oer_native_pdf_server._NATIVE_PDF_HASHES', {}):
            with self.assertRaisesRegex(ValueError, 'PDF wrapper bytes changed'): self.rt.algorithms()
        supplied = {'CONSENSE_SL_SCOPE_MANIFEST': str(self.scope_path)}
        settings = configure_native_pdf_environment(supplied, self.root)
        self.assertEqual(str((self.root / '.local/oer/native-pdf-retrieval-state').resolve()), settings['CONSENSE_RETRIEVAL_DATA'])
        for path in ('retrieval-state', 'native-retrieval-state'):
            supplied['CONSENSE_RETRIEVAL_DATA'] = str(self.root / '.local/oer' / path)
            with self.assertRaisesRegex(ValueError, 'separate state'): configure_native_pdf_environment(supplied, self.root)
        supplied['CONSENSE_RETRIEVAL_DATA'] = str(self.root / 'own-pdf-state')
        self.assertEqual(str((self.root / 'own-pdf-state').resolve()), configure_native_pdf_environment(supplied, self.root)['CONSENSE_RETRIEVAL_DATA'])


if __name__ == '__main__': unittest.main()
