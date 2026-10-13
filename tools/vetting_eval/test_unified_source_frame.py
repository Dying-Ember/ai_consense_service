"""Request-frame authored real-CAS contract probes; no live services/models/GPU."""
import json
import os
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStoreError
from oer_source_registry import SourceRegistryError
from test_oer_unified_server import UnifiedHttpTests
from token_windows import WindowError
from fastapi.testclient import TestClient
from native_canonical_projection import produce_native
from native_projection_collection import publish_native_collection
from native_projection_transport import publish_native_transport
from canonical_projection import _bytes
from oer_unified_server import create_unified_app
from test_native_canonical_projection import fixture as docx_fixture
from test_native_pdf_canonical_projection import fixture as pdf_fixture
from test_oer_server import OerHttpContractTest
from test_token_windows import CharacterTokenizer


class SourceFrameTests(unittest.TestCase):
    tearDown = UnifiedHttpTests.tearDown
    publish_pdf = UnifiedHttpTests.publish_pdf
    index = UnifiedHttpTests.index
    query = UnifiedHttpTests.query

    def setUp(self):
        # Build only the current native-DOCX/native-PDF seam. The historical
        # unrelated scanned fixture fails its complete capture gate on this
        # baseline; it is neither patched nor used as a prerequisite here.
        OerHttpContractTest.setUp(self)
        self.client.close()
        self.app = create_unified_app(self.policy, backend=self.backend)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        self.client = TestClient(self.app)
        self.store, self.registry = self.app.state.artifact_store, self.app.state.source_registry
        bundle, adoption = docx_fixture(self.store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        internal = produce_native(bundle, adoption, artifact_store=self.store)
        self.collection = publish_native_collection(internal, artifact_store=self.store, max_batch_bytes=60000)
        bundle, adoption = docx_fixture(self.store, adapter_suffix=b'-source-frame-single')
        adoption['documentId'] = 'AUTHORED-source-frame-single-docx'
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        self.single = publish_native_transport(produce_native(bundle, adoption, artifact_store=self.store), artifact_store=self.store)
        native, dual, adoption = pdf_fixture(self.store)
        adoption['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        self.pdf_body = {key: self.store.put_bytes(_bytes(value), identity_scope='canonical_json_object')
            for key, value in zip(('nativeBundleRef', 'dualBundleRef', 'adoptionManifestRef'), (native, dual, adoption))}

    def publish_all(self):
        pdf = self.publish_pdf()
        for source in (self.collection, self.single):
            self.registry.publish(source)
        return [self.collection, pdf, self.single]

    def single_address(self):
        self.registry.publish(self.single)
        return self.single['projectionSha256']

    def test_same_request_load_chunks_signature_reuses_one_complete_validation(self):
        address = self.single_address()
        with patch.object(self.registry, 'validator', wraps=self.registry.validator) as validate:
            with self.registry.validation_frame(software_guard=self.rt.algorithms):
                one = self.registry.load(address)
                chunks = self.registry.chunks(address)
                self.registry.validate_native_chunks(chunks)
                self.assertEqual(one['chunks'], chunks)
            self.assertEqual(1, validate.call_count)

    def test_pdf_subregistry_participates_and_detaches_mutable_returns(self):
        pdf = self.publish_pdf()
        address = pdf['projectionSha256']
        with patch.object(self.registry.pdf_registry, 'validator', wraps=self.registry.pdf_registry.validator) as validate:
            with self.registry.validation_frame(software_guard=self.rt.algorithms):
                first = self.registry.load(address)
                chunks = self.registry.chunks(address)
                self.registry.validate_native_chunks(chunks)
                first['chunks'][0]['text'] = 'caller-only mutation'
                later = self.registry.load(address)
                self.assertEqual(pdf['chunks'], later['chunks'])
            self.assertEqual(1, validate.call_count)

    def test_frames_reuse_pure_derivation_with_fresh_actual_cas_between_requests(self):
        address = self.single_address()
        traces = []
        current = []
        read = self.store._read_uncached
        def trace(descriptor, *, retain):
            current.append((descriptor['sha256'], descriptor['byteCount'], retain))
            return read(descriptor, retain=retain)
        with patch.object(self.registry, 'validator', wraps=self.registry.validator) as validate:
            with patch.object(self.store, '_read_uncached', trace):
                for _ in range(2):
                    current.clear()
                    with self.registry.validation_frame(software_guard=self.rt.algorithms):
                        self.registry.load(address)
                        self.registry.load(address)
                    traces.append(list(current))
            self.assertEqual(1, validate.call_count)
        self.assertEqual(traces[0], traces[1])
        self.assertTrue(traces[0])
        self.assertEqual({(sha, size) for sha, size, retain in traces[0] if retain},
                         {(sha, size) for sha, size, retain in traces[0] if not retain})
        self.assertIsNone(self.registry._validation_frame.get())
        self.registry.load(address)  # unframed normal validation remains supported

    def test_cross_request_pdf_mutable_return_cannot_change_private_derived_result(self):
        pdf = self.publish_pdf()
        address = pdf['projectionSha256']
        with self.registry.validation_frame(software_guard=self.rt.algorithms):
            first = self.registry.load(address)
        first['chunks'][0]['text'] = 'caller invention after complete prior request'
        first['canonical']['relations'] = []
        with self.registry.validation_frame(software_guard=self.rt.algorithms):
            later = self.registry.load(address)
            self.assertEqual(pdf, later)
            self.registry.validate_native_chunks(later['chunks'])

    def test_failed_body_does_not_publish_a_cross_request_derived_result(self):
        address = self.single_address()
        with patch.object(self.registry, 'validator', wraps=self.registry.validator) as validate:
            with self.assertRaisesRegex(RuntimeError, 'failed fixture body'):
                with self.registry.validation_frame(software_guard=self.rt.algorithms):
                    self.registry.load(address)
                    raise RuntimeError('failed fixture body')
            self.assertEqual(0, len(self.registry._derived_results))
            with self.registry.validation_frame(software_guard=self.rt.algorithms):
                self.registry.load(address)
            self.assertEqual(2, validate.call_count)

    def test_nested_frame_keeps_one_outer_boundary(self):
        address = self.single_address()
        with patch.object(self.registry, 'validator', wraps=self.registry.validator) as validate:
            with patch.object(self.rt, 'algorithms', wraps=self.rt.algorithms) as software:
                with self.registry.validation_frame(software_guard=software):
                    self.registry.load(address)
                    with self.registry.validation_frame(software_guard=software):
                        self.registry.load(address)
                self.assertEqual(2, software.call_count)
            self.assertEqual(1, validate.call_count)

    def test_fresh_frame_refuses_preexisting_actual_cas_session(self):
        address = self.single_address()
        body_entered = []
        with self.store.validation_session():
            self.registry.load(address)
            with self.assertRaises(SourceRegistryError) as caught:
                with self.registry.validation_frame(software_guard=self.rt.algorithms):
                    body_entered.append(True)
            self.assertEqual('CANONICAL_FRAME_CAS_BOUNDARY_REQUIRED', caught.exception.code)
            self.assertEqual([], body_entered)
        self.assertIsNone(self.store._read_session.get())
        self.assertIsNone(self.registry._validation_frame.get())

    def test_real_request_refuses_outer_cas_session_and_journals_failure(self):
        sources = self.publish_all()
        with self.store.validation_session():
            response = self.index(sources)
        self.assertEqual(422, response.status_code, f'HTTP {response.status_code}')
        self.assertEqual('CANONICAL_REQUEST_FRAME_UNAVAILABLE', response.json()['detail']['kind'])
        self.assertEqual([], self.encodes)
        runs = sorted((self.backend.STATE / 'run-evidence').iterdir())
        terminal = json.loads((runs[-1] / 'receipt.json').read_text())
        self.assertEqual('failed', terminal['terminalStatus'])
        self.assertIsNone(terminal['response'])

    def test_changed_public_root_same_length_restored_mtime_rejects_exit(self):
        address = self.single_address()
        path = self.registry._path(address)
        raw, stat = path.read_bytes(), path.stat()
        try:
            with self.assertRaises(SourceRegistryError):
                with self.registry.validation_frame(software_guard=self.rt.algorithms):
                    self.registry.load(address)
                    changed = raw.replace(b'"projectionSha256":"', b'"projectionSha256":"', 1)
                    offset = changed.index(address.encode())
                    changed = changed[:offset] + (b'0' if changed[offset:offset+1] != b'0' else b'1') + changed[offset+1:]
                    self.assertEqual(len(raw), len(changed))
                    path.write_bytes(changed)
                    os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        self.assertIsNone(self.registry._validation_frame.get())

    def test_missing_public_root_rejects_cached_load_and_clears_frame(self):
        address = self.single_address()
        path = self.registry._path(address)
        raw = path.read_bytes()
        try:
            with self.assertRaises(SourceRegistryError):
                with self.registry.validation_frame(software_guard=self.rt.algorithms):
                    self.registry.load(address)
                    path.unlink()
                    self.registry.load(address)
        finally:
            path.write_bytes(raw)
        self.assertIsNone(self.registry._validation_frame.get())

    def test_actual_cas_mutation_after_cached_load_rejects_exit(self):
        address = self.single_address()
        changed = None
        try:
            with self.assertRaises(ArtifactStoreError):
                with self.registry.validation_frame(software_guard=self.rt.algorithms):
                    self.registry.load(address)
                    self.registry.load(address)
                    descriptor, _ = next(iter(self.store._read_session.get().values()))
                    path = self.store._path(descriptor)
                    raw, stat = path.read_bytes(), path.stat()
                    changed = path, raw, stat
                    path.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
                    os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        finally:
            if changed:
                path, raw, stat = changed
                path.write_bytes(raw)
                os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        self.assertIsNone(self.store._read_session.get())
        self.assertIsNone(self.registry._validation_frame.get())

    def test_actual_revision_alias_mutation_after_members_check_rejects_exit(self):
        address = self.single_address()
        path = self.registry._revision_path(self.single['projectionRevisionSha256'])
        raw, stat = path.read_bytes(), path.stat()
        try:
            with self.assertRaises(SourceRegistryError):
                with self.registry.validation_frame(software_guard=self.rt.algorithms):
                    chunks = self.registry.chunks(address)
                    self.registry.validate_native_chunks(chunks)
                    path.write_bytes(b' ' + raw[1:])
                    os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))

    def test_actual_pdf_revision_alias_mutation_rejects_exit(self):
        pdf = self.publish_pdf()
        path = self.registry.pdf_registry._pdf_revision_path(pdf['projectionRevisionSha256'])
        raw, stat = path.read_bytes(), path.stat()
        try:
            with self.assertRaises(SourceRegistryError):
                with self.registry.validation_frame(software_guard=self.rt.algorithms):
                    chunks = self.registry.chunks(pdf['projectionSha256'])
                    self.registry.validate_native_chunks(chunks)
                    path.write_bytes(b' ' + raw[1:])
                    os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        self.assertIsNone(self.registry._validation_frame.get())

    def test_software_guard_rejects_changed_actual_imported_source_at_exit(self):
        import unified_source_registry
        address = self.single_address()
        path = unified_source_registry.__file__
        from pathlib import Path
        path = Path(path)
        raw, stat = path.read_bytes(), path.stat()
        try:
            with self.assertRaises(WindowError) as caught:
                with self.registry.validation_frame(software_guard=self.rt.algorithms):
                    self.registry.load(address)
                    path.write_bytes(raw + b'\n# owned-test-temporary-source-drift\n')
            self.assertEqual('SOFTWARE_CHANGED_SINCE_IMPORT', caught.exception.kind)
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))

    def test_contexts_in_two_threads_have_independent_validation_verdicts(self):
        address = self.single_address()
        barrier = Barrier(2)
        def use_frame():
            with self.registry.validation_frame(software_guard=self.rt.algorithms):
                first = self.registry.load(address)
                barrier.wait(timeout=10)
                second = self.registry.load(address)
                self.assertEqual(first, second)
        with patch.object(self.registry, 'validator', wraps=self.registry.validator) as validate:
            with ThreadPoolExecutor(max_workers=2) as pool:
                list(pool.map(lambda _: use_frame(), range(2)))
            self.assertEqual(2, validate.call_count)

    def test_real_canonical_index_route_runs_one_validation_per_source(self):
        sources = self.publish_all()
        with patch.object(self.registry.pdf_registry, 'validator', wraps=self.registry.pdf_registry.validator) as pdf_validate:
            response = self.index(sources)
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(1, pdf_validate.call_count)
        self.assertIsNone(self.registry._validation_frame.get())

    def test_health_distinguishes_request_frame_from_independent_canonical_load(self):
        with patch.object(self.registry, 'load', side_effect=AssertionError('health cannot validate source')):
            with patch.object(self.rt, 'tokenizer', side_effect=AssertionError('health cannot load tokenizer')):
                response = self.client.get('/health')
        self.assertEqual(200, response.status_code, f'HTTP {response.status_code}')
        oer = response.json()['oer']
        self.assertEqual('canonical_load', oer['unifiedCanonical']['artifactReadValidationScope'])
        request = oer['requestSourceValidation']
        self.assertEqual('one_normal_journalled_request', request['validationScope'])
        self.assertEqual(['/index-canonical', '/index-canonical/status', '/retrieve'], request['routes'])
        self.assertEqual('cold_once_per_source_within_request_or_byte_bound_replay', request['completeReconstruction'])
        self.assertFalse(request['crossRequestDerivedResultReuse']['cachedSourceValidity'])
        self.assertFalse(request['crossRequestDerivedResultReuse']['unframedReadsUseCache'])
        self.assertEqual('strict_bounded_each_reuse', request['publicWireRead'])
        self.assertEqual('all_complete_used_files_at_entry_and_fresh_exit', request['actualCasVerification'])
        self.assertFalse(request['crossRequestVerdictReuse'])
        self.assertFalse(request['healthPerformsSourceValidation'])
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_exit_guard_failure_is_in_failed_normal_request_journal(self):
        sources = self.publish_all()
        path = self.registry._path(self.single['projectionSha256'])
        raw, stat = path.read_bytes(), path.stat()
        original = self.backend.encode_embeddings
        calls = []
        def encode_then_change(texts):
            vectors = original(texts)
            calls.append(len(texts))
            # Encode is after the normal signature source validation. There is
            # no further source load in the index body; only its frame exit
            # catches this late, real file mutation with restored metadata.
            path.write_bytes(b' ' + raw[1:])
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            return vectors
        try:
            with patch.object(self.backend, 'encode_embeddings', side_effect=encode_then_change):
                response = self.index(sources)
        finally:
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        self.assertTrue(calls)
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual('CANONICAL_REQUEST_FRAME_UNAVAILABLE', response.json()['detail']['kind'])
        runs = sorted((self.backend.STATE / 'run-evidence').iterdir())
        terminal = json.loads((runs[-1] / 'receipt.json').read_text())
        self.assertEqual('failed', terminal['terminalStatus'])
        self.assertIsNone(terminal['response'])
        self.assertIsNone(self.registry._validation_frame.get())

    def test_real_retrieval_replays_pure_results_but_fresh_cas_each_request(self):
        sources = self.publish_all()
        response = self.index(sources)
        self.assertEqual(200, response.status_code, response.text)
        traces, current = [], []
        read = self.store._read_uncached
        def trace(descriptor, *, retain):
            current.append((descriptor['sha256'], descriptor['byteCount'], retain))
            return read(descriptor, retain=retain)
        with patch.object(self.registry.pdf_registry, 'validator', wraps=self.registry.pdf_registry.validator) as validate:
            with patch.object(self.store, '_read_uncached', trace):
                for request in range(2):
                    current.clear()
                    response = self.query()
                    self.assertEqual(200, response.status_code, response.text)
                    self.assertTrue(response.json()['hits'])
                    self.assertEqual(0, validate.call_count, 'Index already published the same verified pure result')
                    traces.append(list(current))
                    self.assertIsNone(self.registry._validation_frame.get())
        self.assertEqual(traces[0], traces[1])
        self.assertTrue(traces[0])
        self.assertEqual({(sha, size) for sha, size, retain in traces[0] if retain},
                         {(sha, size) for sha, size, retain in traces[0] if not retain})

    def test_late_retrieval_cas_mutation_is_rejected_and_journalled(self):
        sources = self.publish_all()
        response = self.index(sources)
        self.assertEqual(200, response.status_code, response.text)
        original = self.backend.rerank_model
        changed = []
        class MutatingScorer:
            def predict(inner, pairs, **kwargs):
                scores = original().predict(pairs, **kwargs)
                if not changed:
                    descriptor, _ = next(iter(self.store._read_session.get().values()))
                    path = self.store._path(descriptor)
                    raw, stat = path.read_bytes(), path.stat()
                    changed.append((path, raw, stat))
                    path.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
                    os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                return scores
        try:
            with patch.object(self.backend, 'rerank_model', return_value=MutatingScorer()):
                response = self.query()
        finally:
            for path, raw, stat in reversed(changed):
                path.write_bytes(raw)
                os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        self.assertTrue(changed)
        self.assertEqual(422, response.status_code, f'HTTP {response.status_code}')
        runs = sorted((self.backend.STATE / 'run-evidence').iterdir())
        terminal = json.loads((runs[-1] / 'receipt.json').read_text())
        self.assertEqual('failed', terminal['terminalStatus'])
        self.assertIsNone(terminal['response'])
        self.assertIsNone(self.store._read_session.get())
        self.assertIsNone(self.registry._validation_frame.get())


if __name__ == '__main__':
    unittest.main()
