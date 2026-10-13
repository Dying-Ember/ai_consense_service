"""Explicit pin index contracts with fake vectors and small authored CAS only."""
import copy
from pathlib import Path
from types import SimpleNamespace
from threading import RLock
import unittest

from fastapi.testclient import TestClient
from native_canonical_projection import produce_native
from native_projection_transport import publish_native_transport
from oer_unified_server import create_unified_app
from source_page_index import SourcePageIndexRuntime
from test_native_canonical_projection import fixture
import test_oer_server as base_tests
import test_source_page_server as page_tests
from test_token_windows import CharacterTokenizer
from token_windows import digest, WindowError


class SourcePageIndexHttpTests(unittest.TestCase):
    tearDown = base_tests.OerHttpContractTest.tearDown

    def setUp(self):
        base_tests.OerHttpContractTest.setUp(self)
        self.client.close()
        self.app = create_unified_app(self.policy, backend=self.backend)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'AUTHORED-only'}
        self.client = TestClient(self.app)
        bundle, adoption = fixture(self.app.state.artifact_store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        self.projection = publish_native_transport(produce_native(bundle, adoption,
            artifact_store=self.app.state.artifact_store), artifact_store=self.app.state.artifact_store)
        self.app.state.source_registry.publish(self.projection)

    def index(self, pins=None):
        body = {'projectId': 'AUTHORED-pins-index', 'module': 'vetting',
                'projectionSha256': [self.projection['projectionSha256']]}
        if pins is not None:
            body['sourcePageEvidence'] = pins
        return self.client.post('/index-canonical', json=body)

    def test_unpinned_request_preserves_legacy_source_and_echoes_empty_map(self):
        response = self.index()
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual({}, response.json()['sourcePageEvidence'])
        self.assertNotIn('sourcePageEvidence', self.backend._projects['AUTHORED-pins-index']['signature'])
        before = len(self.encodes)
        second = self.index({})
        self.assertEqual(200, second.status_code, second.text)
        self.assertTrue(second.json()['cached'])
        self.assertEqual(before, len(self.encodes))
        query = self.client.post('/retrieve', json={'projectId': 'AUTHORED-pins-index', 'query': 'Visible',
                                                   'candidates': 4, 'limit': 2})
        self.assertEqual(200, query.status_code, query.text)

    def test_native_docx_cannot_admit_page_pin_or_encode_from_missing_sidecar(self):
        response = self.index({self.projection['projectionSha256']: 'a' * 64})
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes)
        self.assertEqual({}, self.backend._projects)

    def test_out_of_selected_scope_and_bad_identity_pin_fail_before_inference(self):
        for pins in ({'a' * 64: 'b' * 64}, {self.projection['projectionSha256']: 'bad'},
                     {self.projection['projectionSha256']: True}):
            response = self.index(pins)
            self.assertEqual(422, response.status_code, response.text)
        self.assertEqual([], self.encodes)

    def test_health_discloses_exact_pinned_index_protocol_without_model_load(self):
        response = self.client.get('/health')
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual('explicit-composite-index-pins-v1', response.json()['oer']['sourcePageIndexContract'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)


class SourcePagePinLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.fixture = page_tests.SourcePageRouteTests()
        self.fixture.setUp()
        self.assertEqual(200, self.fixture.publish().status_code)
        f = self.fixture
        f.projection['projectionIdentity']['documentId'] = 'AUTHORED-source'
        f.projection['chunks'] = [{'id': 'AUTHORED-parent', 'documentId': 'AUTHORED-source', 'sourceSha256': f.source_sha,
                                   'eligibleModules': ['vetting', 'advisory']}]
        self.chunks = copy.deepcopy(f.projection['chunks'])
        self.key = 'AUTHORED-lifecycle'
        self.backend = SimpleNamespace(LOCK=RLock(), _projects={}, project_key=lambda value: value,
                                       metadata_path=lambda key: f.root / 'absent-metadata.json')
        class Base:
            def __init__(inner):
                inner.s = self.backend
                inner.source_registry = f.canonical
                inner.target_module = 'vetting'
                inner.scope_policy = SimpleNamespace(policyIdentity={'manifestSha256': f.policy_sha})
                inner.after_query = None
            def signature(inner, chunks):
                return {'corpus': digest(chunks)}
            def retrieve(inner, request):
                current = inner.s._projects[request.projectId]
                if inner.signature(current['chunks']) != current['signature']:
                    raise WindowError('STALE_AUTHORED_INDEX', 'Authored signature changed')
                if inner.after_query:
                    inner.after_query()
                return {'hits': []}
        class Runtime(SourcePageIndexRuntime, Base):
            pass
        self.runtime = Runtime()
        self.pins = {f.projection_sha: f.evidence}

    def tearDown(self):
        self.fixture.tearDown()

    def save(self):
        token = self.runtime.source_page_index_pins.set(self.pins)
        try:
            signature = self.runtime.signature(self.chunks)
        finally:
            self.runtime.source_page_index_pins.reset(token)
        self.backend._projects[self.key] = {'chunks': self.chunks, 'signature': signature}
        return signature

    def test_signature_pins_exact_composite_and_query_restores_without_guessing(self):
        signature = self.save()
        self.assertEqual(self.pins, signature['sourcePageEvidence'])
        self.assertIsNone(self.runtime.source_page_index_pins.get())
        self.assertEqual({'hits': []}, self.runtime.retrieve(SimpleNamespace(projectId=self.key)))
        self.assertIsNone(self.runtime.source_page_index_pins.get())

    def test_source_drift_during_query_withholds_result_and_clears_context(self):
        self.save()
        f = self.fixture
        self.runtime.after_query = lambda: (f.store.root / f.source_ref['relativePath']).write_bytes(b'changed actual source')
        with self.assertRaises(ValueError):
            self.runtime.retrieve(SimpleNamespace(projectId=self.key))
        self.assertIsNone(self.runtime.source_page_index_pins.get())

    def test_persisted_pin_change_to_unregistered_composite_cannot_downgrade(self):
        self.save()
        self.backend._projects[self.key]['signature']['sourcePageEvidence'][self.fixture.projection_sha] = '1' * 64
        with self.assertRaises(ValueError):
            self.runtime.retrieve(SimpleNamespace(projectId=self.key))

    def test_partial_or_changed_selected_parent_rejects_before_saved_signature(self):
        token = self.runtime.source_page_index_pins.set(self.pins)
        try:
            with self.assertRaises(WindowError):
                self.runtime.signature([])
            changed = [{**self.chunks[0], 'invented': True}]
            with self.assertRaises(WindowError):
                self.runtime.signature(changed)
        finally:
            self.runtime.source_page_index_pins.reset(token)

    def test_multiple_parents_accept_baseline_uuid_order_and_bind_complete_payloads(self):
        original = self.chunks[0]
        source_order = [{**original, 'id': 'z-first-reading-parent', 'text': 'AUTHORED first reading parent'},
                        {**original, 'id': 'a-second-reading-parent', 'text': 'AUTHORED second reading parent'}]
        self.fixture.projection['chunks'] = copy.deepcopy(source_order)
        # The existing baseline index recipe sorts UUIDs; the dual recipe keeps
        # canonical reading order. Pins prove full source membership in either.
        self.chunks = sorted(copy.deepcopy(source_order), key=lambda chunk: chunk['id'])
        signature = self.save()
        self.assertEqual(digest(self.chunks), signature['corpus'])
        self.assertEqual(self.pins, signature['sourcePageEvidence'])
        self.assertEqual(source_order, self.fixture.projection['chunks'])
        self.assertEqual({'hits': []}, self.runtime.retrieve(SimpleNamespace(projectId=self.key)))
        token = self.runtime.source_page_index_pins.set(self.pins)
        try:
            for changed in (self.chunks[:1], self.chunks + [self.chunks[0]],
                            [{**self.chunks[0], 'text': 'invented'}, self.chunks[1]]):
                with self.assertRaises(WindowError):
                    self.runtime.signature(changed)
        finally:
            self.runtime.source_page_index_pins.reset(token)


if __name__ == '__main__':
    unittest.main()
