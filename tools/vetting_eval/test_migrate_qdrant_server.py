"""Authored storage-boundary checks; no model or live service constructors."""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import migrate_qdrant_server as migration
import qdrant_backend


class StorageBoundaries(unittest.TestCase):
    def test_separate_copy_preserves_every_source_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); source = root/'source'; source.mkdir()
            (source/'storage.sqlite').write_bytes(b'frozen source')
            (source/'qdrant').mkdir(); (source/'qdrant/.lock').write_bytes(b'owner')
            before = migration.stable_copy(source, root/'copy')
            self.assertEqual(migration.tree_content(before), migration.tree_content(migration.tree(root/'copy')))
            self.assertEqual(migration.tree_content(before), migration.tree_content(migration.tree(source)))
            self.assertFalse((root/'copy/qdrant/.lock').exists())
            self.assertEqual((source/'qdrant/.lock').read_bytes(), b'owner')

    def test_nested_copy_and_existing_copy_refused_before_mutation(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder)/'source'; source.mkdir(); (source/'data').write_bytes(b'x')
            for target in (source, source/'nested', source.parent):
                with self.assertRaises(ValueError):
                    migration.stable_copy(source, target)
            self.assertFalse((source/'nested').exists())

    def test_uncheckpointed_source_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder)/'source'; source.mkdir(); (source/'storage.sqlite-wal').write_bytes(b'x')
            with self.assertRaises(ValueError):
                migration.stable_copy(source, Path(folder)/'copy')
            self.assertFalse((Path(folder)/'copy').exists())

    def test_source_changed_during_copy_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder)/'source'; source.mkdir(); (source/'data').write_bytes(b'x')
            original_copy = migration.shutil.copytree
            def changed_copy(*args, **kwargs):
                original_copy(*args, **kwargs)
                (source/'data').write_bytes(b'changed')
            with patch.object(migration.shutil, 'copytree', side_effect=changed_copy):
                with self.assertRaises(ValueError):
                    migration.stable_copy(source, Path(folder)/'copy')

    def test_cosine_normalization_is_tolerance_not_exact_bytes(self):
        comparison = migration.compare_vector([1., 0.], [.9999999, 0.], 2)
        self.assertFalse(comparison['float32BytesExact'])
        self.assertLess(comparison['maxAbsDifference'], 1e-6)
        self.assertEqual(migration.compare_vector([1., 0.], [1., 0.], 2)['maxAbsDifference'], 0.)

    def test_changed_dimension_nonfinite_and_different_direction_rejected(self):
        for vector in ([1.], [float('nan'), 0.], [0., 1.], [.99, 0.]):
            with self.assertRaises(ValueError):
                migration.compare_vector([1., 0.], vector, 2)

    def test_backend_url_does_not_create_or_open_local_state(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder)/'never-created'
            with patch('qdrant_client.QdrantClient') as client:
                qdrant_backend.connect(state, {'CONSENSE_QDRANT_URL': 'http://127.0.0.1:16333', 'CONSENSE_QDRANT_API_KEY': 'synthetic-test-key'})
                client.assert_called_once_with(url='http://127.0.0.1:16333', api_key='synthetic-test-key', timeout=120)
            self.assertFalse(state.exists())

    def test_local_backend_keeps_existing_single_owner_path(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder)/'state'
            with patch('qdrant_client.QdrantClient') as client:
                qdrant_backend.connect(state, {})
                client.assert_called_once_with(path=str(state/'qdrant'))
            self.assertTrue(state.exists())

    def test_incomplete_and_looping_remote_point_pages_rejected(self):
        for response in (([], None), ([SimpleNamespace(id='x')], 'same')):
            client = SimpleNamespace(scroll=lambda *args, **kwargs: response)
            with self.assertRaises(ValueError):
                list(migration.read_points(client, 'candidate', 2, 1))


if __name__ == '__main__':
    unittest.main()
