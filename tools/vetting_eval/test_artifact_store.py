"""Authored disk artifact contracts; no parser/model or production writes."""
import copy
import hashlib
from io import BytesIO
import os
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore, ArtifactStoreError


class ArtifactStoreTest(unittest.TestCase):
    def test_file_stream_and_bytes_capture_same_actual_content(self):
        data = b'authored raw bytes\x00\xff\n'
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'store')
            source = Path(directory) / 'input.bin'
            source.write_bytes(data)
            descriptor = store.put_file(source)
            self.assertEqual(descriptor, store.put_stream(BytesIO(data)))
            self.assertEqual(descriptor, store.put_bytes(data))
            self.assertEqual(hashlib.sha256(data).hexdigest(), descriptor['sha256'])
            self.assertEqual(len(data), descriptor['byteCount'])
            self.assertFalse(Path(descriptor['relativePath']).is_absolute())
            self.assertEqual(data, store.read_asset(descriptor))
            self.assertEqual('verified', store.verify(descriptor)['status'])

    def test_large_stream_is_fully_copied_and_hashed_in_chunks(self):
        class Chunks:
            def __init__(self):
                self.remaining = 65
                self.requests = []
            def read(self, size):
                self.requests.append(size)
                if not self.remaining:
                    return b''
                self.remaining -= 1
                return bytes([self.remaining]) * (1024 * 1024)
        with tempfile.TemporaryDirectory() as directory:
            store, source = ArtifactStore(directory), Chunks()
            descriptor = store.put_stream(source)
            expected = hashlib.sha256()
            for number in reversed(range(65)):
                expected.update(bytes([number]) * (1024 * 1024))
            self.assertEqual(65 * 1024 * 1024, descriptor['byteCount'])
            self.assertEqual(expected.hexdigest(), descriptor['sha256'])
            self.assertEqual(descriptor['byteCount'], (store.root / descriptor['relativePath']).stat().st_size)
            self.assertEqual('verified', store.verify(descriptor)['status'])
            self.assertTrue(all(size <= 1024 * 1024 for size in source.requests))

    def test_same_size_tamper_and_changed_size_fail_verified_reads(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            descriptor = store.put_bytes(b'correct')
            path = store.root / descriptor['relativePath']
            for replacement in (b'changed', b'changed-length'):
                path.write_bytes(replacement)
                with self.assertRaisesRegex(ArtifactStoreError, 'SHA-256 or byteCount'):
                    store.read_asset(descriptor)
                with self.assertRaisesRegex(ArtifactStoreError, 'SHA-256 or byteCount'):
                    store.verify(descriptor)
            with self.assertRaisesRegex(ArtifactStoreError, 'SHA-256 or byteCount'):
                store.put_bytes(b'correct')

    def test_missing_artifact_does_not_turn_a_descriptor_into_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            descriptor = store.put_bytes(b'captured')
            (store.root / descriptor['relativePath']).unlink()
            with self.assertRaisesRegex(ArtifactStoreError, 'missing or unreadable'):
                store.read_asset(descriptor)

    def test_descriptor_path_escape_or_other_content_address_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            descriptor = store.put_bytes(b'evidence')
            for path in ('../outside.bin', '/outside.bin', 'C:/outside.bin',
                         'sha256/00/' + descriptor['sha256'] + '.blob',
                         descriptor['relativePath'].replace('/', '\\')):
                with self.subTest(path=path):
                    bad = dict(descriptor, relativePath=path)
                    with self.assertRaisesRegex(ArtifactStoreError, 'within the store'):
                        store.read_asset(bad)

    def test_symlink_parent_cannot_escape_the_store(self):
        with tempfile.TemporaryDirectory() as directory:
            root, outside = Path(directory) / 'store', Path(directory) / 'outside'
            store = ArtifactStore(root)
            descriptor = store.put_bytes(b'evidence')
            original = store.root / descriptor['relativePath']
            outside.mkdir()
            (outside / original.name).write_bytes(b'evidence')
            original.unlink()
            original.parent.rmdir()
            try:
                os.symlink(outside, original.parent, target_is_directory=True)
            except OSError as error:
                self.skipTest('Host cannot create directory symlinks: ' + str(error))
            with self.assertRaisesRegex(ArtifactStoreError, 'within the store'):
                store.verify(descriptor)

    def test_metadata_changes_and_text_stream_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            descriptor = store.put_bytes(b'proof')
            for key, value in (('byteCount', True), ('byteCount', -1), ('sha256', 'xyz'),
                               ('storage', 'external_url'), ('identityScope', '')):
                bad = copy.deepcopy(descriptor)
                bad[key] = value
                with self.assertRaises(ArtifactStoreError):
                    store.verify(bad)
            from io import StringIO
            with self.assertRaisesRegex(ArtifactStoreError, 'yield bytes'):
                store.put_stream(StringIO('not binary'))
            self.assertEqual([], list(store.root.glob('.capture-*')))


if __name__ == '__main__':
    unittest.main()
