"""Local byte-verification sessions, fresh sources and no crossed verdicts."""
import copy
from contextlib import nullcontext
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from artifact_store import ArtifactStore, ArtifactStoreError


def session(store):
    return store.validation_session() if hasattr(store, 'validation_session') else nullcontext()


class VerifiedReadSessionTests(unittest.TestCase):
    def test_repeated_same_descriptor_has_one_actual_read_and_one_exit_rehash(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            descriptor = store.put_bytes(b'complete actual source bytes')
            name = '_read_uncached' if hasattr(store, '_read_uncached') else '_read'
            with patch.object(store, name, wraps=getattr(store, name)) as actual:
                with session(store):
                    for _ in range(12):
                        self.assertEqual(store.read_asset(descriptor), b'complete actual source bytes')
                        self.assertEqual(store.verify(descriptor)['status'], 'verified')
                self.assertEqual(actual.call_count, 2)
                with session(store):
                    self.assertEqual(store.read_asset(descriptor), b'complete actual source bytes')
                self.assertEqual(actual.call_count, 4)

    def test_tamper_or_missing_during_session_never_escapes_successful_exit(self):
        for operation in ('same-size', 'different-size', 'missing'):
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as directory:
                store = ArtifactStore(directory)
                descriptor = store.put_bytes(b'original source')
                path = store.root / descriptor['relativePath']
                with self.assertRaises(ArtifactStoreError):
                    with session(store):
                        store.read_asset(descriptor)
                        if operation == 'missing': path.unlink()
                        elif operation == 'same-size': path.write_bytes(b'corrupt! source')
                        else: path.write_bytes(b'changed-length')
                        store.read_asset(descriptor)

    def test_next_session_is_cold_after_successful_previous_read(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            descriptor = store.put_bytes(b'source')
            with session(store): store.read_asset(descriptor)
            (store.root / descriptor['relativePath']).write_bytes(b'broken')
            with self.assertRaises(ArtifactStoreError):
                with session(store): store.read_asset(descriptor)
            with self.assertRaises(ArtifactStoreError): store.verify(descriptor)

    def test_cached_descriptor_does_not_relax_new_ref_path_or_size_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            descriptor = store.put_bytes(b'full evidence')
            with session(store):
                store.read_asset(descriptor)
                for field, value in [('relativePath', '../escape.blob'), ('byteCount', 99),
                                     ('identityScope', ''), ('storage', 'http')]:
                    changed = copy.deepcopy(descriptor)
                    changed[field] = value
                    with self.assertRaises(ArtifactStoreError): store.read_asset(changed)

    def test_nested_scopes_share_outer_boundary_and_failed_session_does_not_retain_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            descriptor = store.put_bytes(b'raw')
            name = '_read_uncached' if hasattr(store, '_read_uncached') else '_read'
            with patch.object(store, name, wraps=getattr(store, name)) as actual:
                with session(store):
                    store.read_asset(descriptor)
                    with session(store): store.read_asset(descriptor)
                self.assertEqual(actual.call_count, 2)
            with self.assertRaises(RuntimeError):
                with session(store):
                    store.read_asset(descriptor)
                    raise RuntimeError('caller refused output')
            (store.root / descriptor['relativePath']).unlink()
            with self.assertRaises(ArtifactStoreError):
                with session(store): store.read_asset(descriptor)


if __name__ == '__main__': unittest.main(verbosity=2)
