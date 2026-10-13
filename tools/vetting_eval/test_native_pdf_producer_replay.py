"""Known native-PDF producer identities survive newer readers; no old code exec."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _hash
import native_pdf_canonical_projection as native
import native_pdf_projection_transport as transport
from test_native_pdf_canonical_projection import fixture

DEPENDENCIES = native.PROJECTION_DEPENDENCIES


def historical_internal(store, root):
    inputs = fixture(store)
    folder = Path(root) / 'historical-native-producer'
    folder.mkdir()
    actual = Path(native.__file__)
    for name in DEPENDENCIES:
        (folder / name).write_bytes(actual.with_name(name).read_bytes())
    old = folder / actual.name
    old.write_bytes(actual.read_bytes() + b'\n# authored historical producer bytes, never executed\n')
    with mock.patch.object(native, '__file__', str(old)):
        result = native.produce_native_pdf(*inputs, artifact_store=store)
    assert result['projectionIdentity']['projectionCodeSha256'] == hashlib.sha256(old.read_bytes()).hexdigest()
    assert result['projectionIdentity']['projectionCodeSha256'] != hashlib.sha256(actual.read_bytes()).hexdigest()
    return result


def historical_public(internal, store, root):
    old = Path(root) / 'historical-public-producer.py'
    old.write_bytes(Path(transport.__file__).read_bytes() + b'\n# authored historical publisher bytes, never executed\n')
    with mock.patch.object(transport, '__file__', str(old)):
        result = transport.publish_native_pdf_transport(internal, artifact_store=store)
    assert result['transportCodeRef']['sha256'] == hashlib.sha256(old.read_bytes()).hexdigest()
    assert result['transportCodeRef']['sha256'] != hashlib.sha256(Path(transport.__file__).read_bytes()).hexdigest()
    return result


class NativePdfProducerReplayTest(unittest.TestCase):
    def test_historical_internal_full_payload_hashes_chunks_identity_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            internal = historical_internal(store, directory)
            original = copy.deepcopy(internal)
            with mock.patch.object(store, 'put_bytes', side_effect=AssertionError('validation cannot capture')):
                self.assertEqual(original, native.validate_native_pdf_projection(internal, artifact_store=store))
            self.assertEqual(original, internal)

    def test_historical_public_publisher_cold_replays_entire_public_and_internal(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            internal = native.produce_native_pdf(*fixture(store), artifact_store=store)
            public = historical_public(internal, store, directory)
            old_public = copy.deepcopy(public)
            with mock.patch.object(store, 'put_bytes', side_effect=AssertionError('reads cannot repair missing CAS')):
                self.assertEqual(old_public, transport.validate_native_pdf_transport(public, artifact_store=store))
                self.assertEqual(internal, transport.hydrate_native_pdf_transport(public, artifact_store=store))
            self.assertEqual(old_public, public)

    def test_new_producers_bind_current_sources_and_legacy_chain_reads_without_reseal(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            old_internal = historical_internal(store, directory)
            old_public = historical_public(old_internal, store, directory)
            self.assertEqual(old_internal, transport.hydrate_native_pdf_transport(old_public, artifact_store=store))
            fresh = native.produce_native_pdf(*fixture(store), artifact_store=store)
            self.assertEqual(hashlib.sha256(Path(native.__file__).read_bytes()).hexdigest(),
                             fresh['projectionIdentity']['projectionCodeSha256'])
            self.assertEqual({name: hashlib.sha256(Path(native.__file__).with_name(name).read_bytes()).hexdigest()
                              for name in DEPENDENCIES}, fresh['projectionIdentity']['projectionDependencySha256'])
            new_public = transport.publish_native_pdf_transport(old_internal, artifact_store=store)
            self.assertEqual(hashlib.sha256(Path(transport.__file__).read_bytes()).hexdigest(),
                             new_public['projectionIdentity']['projectionCodeSha256'])
            with self.assertRaises(TypeError):
                native.produce_native_pdf(*fixture(store), artifact_store=store,
                                          replay_identity=old_internal['projectionIdentity'])

    def test_unknown_missing_extra_or_invalid_internal_producer_identity_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            internal = historical_internal(store, directory)
            for kind in ('version', 'missing_code', 'invalid_code', 'bool_code', 'missing_dependency',
                         'extra_dependency', 'invalid_dependency', 'extra_identity', 'derived_binding'):
                with self.subTest(kind=kind):
                    changed = copy.deepcopy(internal)
                    identity = changed['projectionIdentity']
                    if kind == 'version': identity['projectionVersion'] = 'future-unsupported'
                    elif kind == 'missing_code': identity.pop('projectionCodeSha256')
                    elif kind == 'invalid_code': identity['projectionCodeSha256'] = 'bad'
                    elif kind == 'bool_code': identity['projectionCodeSha256'] = True
                    elif kind == 'missing_dependency': identity['projectionDependencySha256'].pop('parser_capture.py')
                    elif kind == 'extra_dependency': identity['projectionDependencySha256']['unbound.py'] = 'a' * 64
                    elif kind == 'invalid_dependency': identity['projectionDependencySha256']['parser_capture.py'] = 'bad'
                    elif kind == 'extra_identity': identity['unknown'] = 'must not survive'
                    else: identity['nativeBundleSha256'] = 'f' * 64
                    changed['projectionSha256'] = _hash({k: v for k, v in changed.items() if k != 'projectionSha256'})
                    with self.assertRaises(CanonicalProjectionError):
                        native.validate_native_pdf_projection(changed, artifact_store=store)

    def test_legacy_public_version_and_producer_cas_bytes_required_on_every_read(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            internal = native.produce_native_pdf(*fixture(store), artifact_store=store)
            public = historical_public(internal, store, directory)
            changed = copy.deepcopy(public)
            changed['projectionIdentity']['projectionVersion'] = 'future-unsupported'
            changed['projectionSha256'] = _hash({k: v for k, v in changed.items() if k != 'projectionSha256'})
            with self.assertRaises(CanonicalProjectionError):
                transport.validate_native_pdf_transport(changed, artifact_store=store)
            self.assertEqual(public, transport.validate_native_pdf_transport(public, artifact_store=store))
            descriptor = public['transportCodeRef']
            path = store.root / descriptor['relativePath']
            raw = path.read_bytes()
            path.write_bytes(b'producer proof corrupted after prior pass')
            with self.assertRaises(CanonicalProjectionError):
                transport.validate_native_pdf_transport(public, artifact_store=store)
            path.write_bytes(raw)
            path.unlink()
            with mock.patch.object(store, 'put_bytes', side_effect=AssertionError('no recreate')):
                with self.assertRaises(CanonicalProjectionError):
                    transport.validate_native_pdf_transport(public, artifact_store=store)
            self.assertFalse(path.exists())

    def test_legacy_resealed_derived_content_and_changed_cas_are_not_repaired(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            internal = historical_internal(store, directory)
            changed = copy.deepcopy(internal)
            changed['chunks'][0]['content'] = 'repaired by reader must be rejected'
            changed['projectionSha256'] = _hash({k: v for k, v in changed.items() if k != 'projectionSha256'})
            with self.assertRaises(CanonicalProjectionError):
                native.validate_native_pdf_projection(changed, artifact_store=store)
            self.assertEqual(internal, native.validate_native_pdf_projection(internal, artifact_store=store))
            descriptor = internal['evidenceDescriptors']['nativeBundle']
            (store.root / descriptor['relativePath']).write_bytes(b'CAS changed after prior pass')
            with self.assertRaises(CanonicalProjectionError):
                native.validate_native_pdf_projection(internal, artifact_store=store)


if __name__ == '__main__':
    unittest.main()
