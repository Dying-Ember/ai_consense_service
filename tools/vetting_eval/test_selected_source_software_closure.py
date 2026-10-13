"""Normal reader/software guard with authored complete CAS, no inference/index."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _hash
import native_pdf_canonical_projection as native
from native_pdf_projection_transport import publish_native_pdf_transport, validate_native_pdf_transport
from oer_native_pdf_server import NativePdfOerWindowRuntime
from test_native_pdf_canonical_projection import fixture
from token_windows import WindowError
from unified_source_registry import UnifiedCanonicalSourceRegistry

OWNED = Path(__file__).resolve().parents[2] / 'src/test/resources/oer/native-pdf-producer-v1'
SELECTED_READERS = ('pdf_selected_source.py', 'selected_source_raster.py')
RUNTIME_READERS = ('parser_capture.py', 'native_pdf_table_projection.py', *SELECTED_READERS)


class SelectedSourceSoftwareClosureContracts(unittest.TestCase):
    def runtime(self, registry):
        # Startup declarations are fixture strings only; no CUDA/model module
        # is imported and none of these methods can execute numerical inference.
        backend = SimpleNamespace(device=lambda: 'cuda', SERIAL_OFFLOAD=True, MODEL_DTYPE='float32',
            MODEL_SETTINGS={k: {'maxTokens': 512} for k in ('embedding', 'reranker')})
        with patch.dict(os.environ, {}, clear=True):
            return NativePdfOerWindowRuntime(backend, SimpleNamespace(), source_registry=registry)

    def produce(self, store):
        return native.produce_native_pdf(*fixture(store), artifact_store=store)

    def test_new_projection_binds_exact_selected_source_reader_dependencies(self):
        with tempfile.TemporaryDirectory() as directory:
            internal = self.produce(ArtifactStore(Path(directory) / 'cas'))
            identity = internal['projectionIdentity']
            self.assertEqual('source-reviewed-native-pdf-prose-and-visual-layout-v2', identity['projectionVersion'])
            for name in SELECTED_READERS:
                self.assertEqual(hashlib.sha256(Path(native.__file__).with_name(name).read_bytes()).hexdigest(),
                                 identity['projectionDependencySha256'].get(name))

    def test_normal_runtime_algorithms_bind_capture_and_selected_reader_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            registry = UnifiedCanonicalSourceRegistry(Path(directory) / 'registry', store)
            actual = self.runtime(registry).algorithms()
            for name in RUNTIME_READERS:
                self.assertEqual(hashlib.sha256(Path(native.__file__).with_name(name).read_bytes()).hexdigest(),
                                 actual.get(name))

    def _warm_request_then_modify_actual_candidate_file(self, name):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            internal = self.produce(store)
            public = publish_native_pdf_transport(internal, artifact_store=store)
            registry = UnifiedCanonicalSourceRegistry(Path(directory) / 'registry', store)
            registry.publish(public)
            runtime = self.runtime(registry)
            path = Path(native.__file__).with_name(name)
            before = path.read_bytes(), path.stat().st_atime_ns, path.stat().st_mtime_ns
            try:
                with self.assertRaises(WindowError) as rejected:
                    with registry.validation_frame(software_guard=runtime.algorithms):
                        # Both ordinary loads use the normal source gate. The
                        # second load consumes the already verified derived
                        # value in this request, before actual source mutation.
                        self.assertEqual(public, registry.load(public['projectionSha256']))
                        self.assertEqual(public, registry.load(public['projectionSha256']))
                        self.assertIn(public['projectionSha256'], registry._validation_frame.get()['values'])
                        path.write_bytes(before[0] + b'\n# authored actual file changed after derived cache warm\n')
                        self.assertEqual(public, registry.load(public['projectionSha256']))
                self.assertEqual('SOFTWARE_CHANGED_SINCE_IMPORT', rejected.exception.kind)
                self.assertIsNone(registry._validation_frame.get())
            finally:
                path.write_bytes(before[0])
                os.utime(path, ns=(before[1], before[2]))

    def test_warm_derived_cache_rejects_actual_pdf_selected_source_change(self):
        self._warm_request_then_modify_actual_candidate_file('pdf_selected_source.py')

    def test_warm_derived_cache_rejects_actual_selected_source_raster_change(self):
        self._warm_request_then_modify_actual_candidate_file('selected_source_raster.py')

    def test_warm_derived_cache_rejects_changed_table_reader(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas'); internal = self.produce(store)
            public = publish_native_pdf_transport(internal, artifact_store=store)
            registry = UnifiedCanonicalSourceRegistry(Path(directory) / 'registry', store); registry.publish(public)
            runtime = self.runtime(registry); original = Path.read_bytes; state = {'changed': False}
            def changed(path):
                raw = original(path)
                return raw + b'\n# authored changed table reader\n' if state['changed'] and path.name == 'native_pdf_table_projection.py' else raw
            with patch.object(Path, 'read_bytes', changed), self.assertRaises(WindowError) as error:
                with registry.validation_frame(software_guard=runtime.algorithms):
                    self.assertEqual(public, registry.load(public['projectionSha256']))
                    state['changed'] = True
                    registry.load(public['projectionSha256'])
            self.assertEqual('SOFTWARE_CHANGED_SINCE_IMPORT', error.exception.kind)

    def test_new_derived_revision_changes_for_each_actual_selected_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            original = self.produce(store)
            for name in SELECTED_READERS:
                with self.subTest(name=name):
                    path = Path(native.__file__).with_name(name)
                    before = path.read_bytes(), path.stat().st_atime_ns, path.stat().st_mtime_ns
                    try:
                        path.write_bytes(before[0] + b'\n# authored different next-process source identity\n')
                        fresh = self.produce(store)
                        self.assertNotEqual(original['sourceHash'], fresh['sourceHash'])
                        self.assertNotEqual(original['projectionRevisionSha256'], fresh['projectionRevisionSha256'])
                        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(),
                                         fresh['projectionIdentity']['projectionDependencySha256'].get(name))
                    finally:
                        path.write_bytes(before[0])
                        os.utime(path, ns=(before[1], before[2]))

    def test_genuine_prior_v1_snapshot_and_public_replay_without_reseal(self):
        root = OWNED
        receipt = json.loads((root / 'production-receipt.json').read_text('utf-8'))
        for row in receipt['files']:
            # Freeze uses source names; fresh independent copies locate the same
            # exact sibling assets without relying on the producer host path.
            rel = row['path'].split('/legacy-v1-fixture/', 1)[1]
            path = (root / rel).resolve()
            self.assertTrue(path.is_relative_to(root.resolve()))
            raw = path.read_bytes()
            self.assertEqual((row['bytes'], row['sha256']), (len(raw), hashlib.sha256(raw).hexdigest()))
        store = ArtifactStore(root / 'cas')
        internal = json.loads((root / 'internal.json').read_text('utf-8'))
        public = json.loads((root / 'public.json').read_text('utf-8'))
        self.assertEqual('source-reviewed-native-pdf-prose-and-visual-layout-v1', internal['projectionIdentity']['projectionVersion'])
        with patch.object(store, 'put_bytes', side_effect=AssertionError('cold reader must not reseal or capture')):
            self.assertEqual(internal, native.validate_native_pdf_projection(internal, artifact_store=store))
            self.assertEqual(public, validate_native_pdf_transport(public, artifact_store=store))

    def test_new_recipe_replay_refuses_removed_selected_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            internal = self.produce(store)
            for name in SELECTED_READERS:
                with self.subTest(name=name):
                    changed = copy.deepcopy(internal)
                    self.assertIn(name, changed['projectionIdentity']['projectionDependencySha256'])
                    changed['projectionIdentity']['projectionDependencySha256'].pop(name)
                    changed['projectionSha256'] = _hash({k: v for k, v in changed.items() if k != 'projectionSha256'})
                    with self.assertRaises(CanonicalProjectionError):
                        native.validate_native_pdf_projection(changed, artifact_store=store)


if __name__ == '__main__':
    unittest.main()
