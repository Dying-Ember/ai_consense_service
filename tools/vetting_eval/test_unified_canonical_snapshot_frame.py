"""Actual ASGI snapshot GET contracts; authored CAS only, no models/live services."""
import copy
import hashlib
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from oer_source_registry import _serialize
import test_unified_source_frame as source_frame_tests
import test_oer_native_collection_server as collection_tests


class ObserveSend:
    def __init__(self, app):
        self.app, self.events = app, []

    async def __call__(self, scope, receive, send):
        async def observed(event):
            if scope['type'] == 'http':
                self.events.append(copy.deepcopy(event))
            await send(event)
        return await self.app(scope, receive, observed)


class CanonicalSnapshotGetTests(unittest.TestCase):
    setUp = source_frame_tests.SourceFrameTests.setUp
    tearDown = source_frame_tests.SourceFrameTests.tearDown
    publish_pdf = source_frame_tests.SourceFrameTests.publish_pdf

    def single_root(self):
        self.registry.publish(self.single)
        return self.single

    def url(self, source, module='advisory'):
        return '/canonical/' + source['projectionSha256'] + '?module=' + module

    def assert_no_inference(self):
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)

    def test_two_actual_snapshot_gets_reconstruct_once_with_fresh_complete_cas(self):
        source = self.single_root()
        traces, current, responses = [], [], []
        read = self.store._read_uncached
        def traced(descriptor, *, retain):
            current.append((descriptor['sha256'], descriptor['byteCount'], retain))
            return read(descriptor, retain=retain)
        with patch.object(self.registry, '_load_current', wraps=self.registry._load_current) as derive:
            with patch.object(self.store, '_read_uncached', traced):
                for _ in range(2):
                    current.clear()
                    response = self.client.get(self.url(source))
                    self.assertEqual(200, response.status_code, response.text)
                    self.assertEqual(_serialize(source), response.content)
                    responses.append(response.content)
                    traces.append(list(current))
            print('ACTUAL_SNAPSHOT_GET_WORK', json.dumps({'actualGetCalls': 2,
                'reconstructionCalls': derive.call_count, 'completeResponseBytes': len(responses[0]),
                'completeResponseSha256': hashlib.sha256(responses[0]).hexdigest(),
                'casReadHashCallsEach': list(map(len, traces)), 'casTraceExactBetweenRequests': traces[0] == traces[1]}))
            self.assertEqual(1, derive.call_count, 'Actual GET must use the production frame across requests')
        self.assertEqual(traces[0], traces[1])
        self.assertTrue(traces[0])
        self.assertEqual([(sha, size) for sha, size, retain in traces[0] if retain],
                         [(sha, size) for sha, size, retain in traces[0] if not retain])
        self.assertIsNone(self.registry._validation_frame.get())
        self.assertIsNone(self.store._read_session.get())
        self.assert_no_inference()

    def test_actual_runtime_algorithms_called_at_entry_and_exit_for_each_get(self):
        source = self.single_root()
        with patch.object(self.rt, 'algorithms', wraps=self.rt.algorithms) as guard:
            for _ in range(2):
                self.assertEqual(200, self.client.get(self.url(source)).status_code)
            self.assertEqual(4, guard.call_count)
        self.assert_no_inference()

    def test_docx_pdf_and_collection_keep_complete_response_headers_and_ownership(self):
        pdf = self.publish_pdf()
        for source in (self.single, self.collection):
            self.registry.publish(source)
        outputs = []
        for source in (self.single, pdf, self.collection):
            with self.subTest(schema=source['schema']):
                expected = _serialize(source)
                first = self.client.get(self.url(source))
                self.assertEqual(200, first.status_code, first.text)
                self.assertEqual(expected, first.content)
                self.assertEqual('"' + hashlib.sha256(expected).hexdigest() + '"', first.headers['etag'])
                self.assertEqual(source['projectionSha256'], first.headers['x-projection-sha256'])
                self.assertEqual('private, no-cache', first.headers['cache-control'])
                self.assertEqual('nosniff', first.headers['x-content-type-options'])
                self.assertEqual('application/json', first.headers['content-type'])
                mutable = first.json()
                mutable['source']['sourceSha256'] = '0' * 64
                mutable['callerInjectedUnknown'] = {'unverified': True}
                second = self.client.get(self.url(source))
                self.assertEqual(expected, second.content)
                self.assertEqual(first.headers['etag'], second.headers['etag'])
                outputs.append({'schema': source['schema'], 'bytes': len(expected),
                                'sha256': hashlib.sha256(expected).hexdigest(), 'value': source})
        report_dir = os.environ.get('SNAPSHOT_CONTRACT_REPORT_DIR')
        if report_dir:
            Path(report_dir).mkdir(parents=True, exist_ok=True)
            (Path(report_dir) / 'complete-response-values.json').write_bytes(_serialize(outputs))
        self.assert_no_inference()

    def test_missing_invalid_module_stay_404_and_actual_scope_runs_on_each_get(self):
        source = self.single_root()
        for module in ('drafting', 'unknown'):
            with self.subTest(module=module):
                response = self.client.get(self.url(source, module))
                self.assertEqual(404, response.status_code, response.text)
                self.assertEqual({'detail': 'Canonical source revision is unavailable'}, response.json())
        self.assertEqual(404, self.client.get('/canonical/' + '0' * 64).status_code)
        import oer_native_collection_server
        with patch.object(oer_native_collection_server, 'assert_canonical_scope',
                          wraps=oer_native_collection_server.assert_canonical_scope) as scope:
            for _ in range(2):
                self.assertEqual(200, self.client.get(self.url(source)).status_code)
            self.assertEqual(2, scope.call_count)
            for call in scope.call_args_list:
                self.assertEqual(source['chunks'], call.args[0])
                self.assertIs(self.policy, call.args[1])
                self.assertEqual('advisory', call.kwargs['module'])
        self.assert_no_inference()

    def test_transport_limit_failure_does_not_publish_pending_derivation(self):
        source = self.single_root()
        import oer_native_collection_server
        with patch.object(oer_native_collection_server, 'MAX_SNAPSHOT_BYTES', len(_serialize(source)) - 1):
            response = self.client.get(self.url(source))
        self.assertEqual(404, response.status_code)
        self.assertEqual(0, len(self.registry._derived_results))
        self.assertEqual(200, self.client.get(self.url(source)).status_code)
        self.assert_no_inference()

    def test_actual_scope_class_exclusion_refuses_cold_and_warm_get_without_scope_cache(self):
        source = self.single_root()
        import competition_scope
        excluded = competition_scope.EXCLUDED_CLASSES | {'project_pre'}
        # Change the actual policy rule consumed by apply, not registry/source
        # validation or a fabricated verdict. Restore it after each probe.
        with patch.object(competition_scope, 'EXCLUDED_CLASSES', excluded):
            cold = self.client.get(self.url(source))
        self.assertEqual(404, cold.status_code, cold.text)
        self.assertEqual(0, len(self.registry._derived_results))
        self.assertEqual(200, self.client.get(self.url(source)).status_code)
        with patch.object(competition_scope, 'EXCLUDED_CLASSES', excluded):
            warm = self.client.get(self.url(source))
        self.assertEqual(404, warm.status_code, warm.text)
        self.assertEqual(200, self.client.get(self.url(source)).status_code)
        self.assert_no_inference()

    def test_cas_entry_missing_or_same_size_corruption_refuses_warm_get(self):
        source = self.single_root()
        self.assertEqual(200, self.client.get(self.url(source)).status_code)
        entry = next(iter(self.registry._derived_results.values()))
        descriptor = json.loads(entry.dependencies[-1])
        path = self.store.root / descriptor['relativePath']
        before, stat = path.read_bytes(), path.stat()
        try:
            path.unlink()
            self.assertEqual(404, self.client.get(self.url(source)).status_code)
            path.write_bytes(before)
            path.write_bytes(bytes([before[0] ^ 1]) + before[1:])
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            self.assertEqual(len(before), path.stat().st_size)
            self.assertEqual(404, self.client.get(self.url(source)).status_code)
        finally:
            path.write_bytes(before)
        self.assert_no_inference()

    def test_root_entry_same_address_changed_bytes_cannot_use_old_derived_value(self):
        source = self.single_root()
        self.assertEqual(200, self.client.get(self.url(source)).status_code)
        path = self.registry._path(source['projectionSha256'])
        before, stat = path.read_bytes(), path.stat()
        changed = json.loads(before)
        changed['source']['sourceSha256'] = '0' * 64
        raw = _serialize(changed)
        self.assertEqual(len(before), len(raw))
        try:
            path.write_bytes(raw)
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            self.assertEqual(404, self.client.get(self.url(source)).status_code)
        finally:
            path.write_bytes(before)
        self.assert_no_inference()

    def response_observer(self):
        observer = ObserveSend(self.app)
        return observer, TestClient(observer)

    def assert_refusal_before_success(self, observer, response):
        self.assertEqual(404, response.status_code, response.text)
        self.assertEqual({'detail': 'Canonical source revision is unavailable'}, response.json())
        statuses = [x['status'] for x in observer.events if x['type'] == 'http.response.start']
        self.assertEqual([404], statuses)
        self.assertNotIn(200, statuses)
        self.assertIsNone(self.registry._validation_frame.get())
        self.assertIsNone(self.store._read_session.get())

    def test_root_change_at_final_cas_exit_rejects_before_http_success(self):
        source = self.single_root()
        path = self.registry._path(source['projectionSha256'])
        before, stat = path.read_bytes(), path.stat()
        read, changed = self.store._read_uncached, False
        def mutate(descriptor, *, retain):
            nonlocal changed
            if not retain and not changed:
                path.write_bytes(before[:-1] + bytes([before[-1] ^ 1]))
                os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                changed = True
            return read(descriptor, retain=retain)
        observer, client = self.response_observer()
        try:
            with client, patch.object(self.store, '_read_uncached', mutate):
                response = client.get(self.url(source))
            self.assertTrue(changed)
            self.assert_refusal_before_success(observer, response)
            self.assertEqual(0, len(self.registry._derived_results))
        finally:
            path.write_bytes(before)
        self.assert_no_inference()

    def test_cas_change_at_final_exit_rejects_before_http_success(self):
        source = self.single_root()
        read, changed, restore = self.store._read_uncached, False, None
        def mutate(descriptor, *, retain):
            nonlocal changed, restore
            if not retain and not changed:
                path = self.store.root / descriptor['relativePath']
                before, stat = path.read_bytes(), path.stat()
                restore = path, before
                path.write_bytes(bytes([before[0] ^ 1]) + before[1:])
                os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                changed = True
            return read(descriptor, retain=retain)
        observer, client = self.response_observer()
        try:
            with client, patch.object(self.store, '_read_uncached', mutate):
                response = client.get(self.url(source))
            self.assertTrue(changed)
            self.assert_refusal_before_success(observer, response)
            self.assertEqual(0, len(self.registry._derived_results))
        finally:
            if restore:
                restore[0].write_bytes(restore[1])
        self.assert_no_inference()

    def test_actual_imported_software_drift_at_entry_refuses_warm_get(self):
        source = self.single_root()
        self.assertEqual(200, self.client.get(self.url(source)).status_code)
        import oer_unified_server
        path = Path(oer_unified_server.__file__).with_name('parser_capture.py')
        before, stat = path.read_bytes(), path.stat()
        try:
            path.write_bytes(before[:-1] + bytes([before[-1] ^ 1]))
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            self.assertEqual(404, self.client.get(self.url(source)).status_code)
        finally:
            path.write_bytes(before)
        self.assert_no_inference()

    def test_actual_imported_software_change_at_final_exit_rejects_before_success(self):
        source = self.single_root()
        import oer_unified_server
        path = Path(oer_unified_server.__file__).with_name('parser_capture.py')
        before, stat = path.read_bytes(), path.stat()
        read, changed = self.store._read_uncached, False
        def mutate(descriptor, *, retain):
            nonlocal changed
            if not retain and not changed:
                path.write_bytes(before[:-1] + bytes([before[-1] ^ 1]))
                os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                changed = True
            return read(descriptor, retain=retain)
        observer, client = self.response_observer()
        try:
            with client, patch.object(self.store, '_read_uncached', mutate):
                response = client.get(self.url(source))
            self.assertTrue(changed)
            self.assert_refusal_before_success(observer, response)
            self.assertEqual(0, len(self.registry._derived_results))
        finally:
            path.write_bytes(before)
        self.assert_no_inference()

    def test_actual_pypdf_runtime_version_drift_at_entry_refuses_warm_get(self):
        source = self.single_root()
        self.assertEqual(200, self.client.get(self.url(source)).status_code)
        import pypdf
        with patch.object(pypdf, '__version__', 'wrong-reader-version'):
            self.assertEqual(404, self.client.get(self.url(source)).status_code)
        self.assert_no_inference()

    def test_actual_pypdf_version_change_at_final_exit_rejects_before_success(self):
        source = self.single_root()
        import pypdf
        version = pypdf.__version__
        read, changed = self.store._read_uncached, False
        def mutate(descriptor, *, retain):
            nonlocal changed
            if not retain and not changed:
                pypdf.__version__ = 'wrong-reader-version'
                changed = True
            return read(descriptor, retain=retain)
        observer, client = self.response_observer()
        try:
            with client, patch.object(self.store, '_read_uncached', mutate):
                response = client.get(self.url(source))
            self.assertTrue(changed)
            self.assert_refusal_before_success(observer, response)
            self.assertEqual(0, len(self.registry._derived_results))
        finally:
            pypdf.__version__ = version
        self.assert_no_inference()


class StandaloneSnapshotPreservationTests(unittest.TestCase):
    setUp = collection_tests.NativeCollectionHttpTests.setUp
    tearDown = collection_tests.NativeCollectionHttpTests.tearDown
    collection = collection_tests.NativeCollectionHttpTests.collection

    def test_standalone_collection_snapshot_keeps_unframed_exact_wire_behavior(self):
        source = self.collection()
        registry = self.app.state.source_registry
        with patch.object(self.rt, 'algorithms', side_effect=AssertionError('standalone GET must remain unchanged')) as guard:
            for _ in range(2):
                response = self.client.get('/canonical/' + source['projectionSha256'] + '?module=advisory')
                self.assertEqual(200, response.status_code, response.text)
                self.assertEqual(_serialize(source), response.content)
            self.assertEqual(0, guard.call_count)
        self.assertFalse(hasattr(registry, '_validation_frame'))
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)


if __name__ == '__main__':
    unittest.main()
