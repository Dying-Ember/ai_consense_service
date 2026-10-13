"""Pinned upstream identity checks without downloads or neural model loading."""
import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import prepare_oer_models as subject


class PinnedArtifactTests(unittest.TestCase):
    def test_blob_header_matches_standard_git_empty_blob_id(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / 'empty'
            path.write_bytes(b'')
            self.assertEqual(subject.git_blob_sha1(path), 'e69de29bb2d1d6434b8b29ae775ad8c2e48c5391')

    def verify(self, root, siblings):
        revision = subject.MODELS['embedding'][1]
        info = SimpleNamespace(sha=revision, siblings=siblings)
        with patch('huggingface_hub.HfApi') as api, patch('huggingface_hub.snapshot_download', return_value=str(root)):
            api.return_value.model_info.return_value = info
            return subject.prepare('embedding', root / 'cache')

    def artifacts(self, root):
        weights = root / 'model.safetensors'
        weights.write_bytes(b'isolated fake weight bytes')
        config = root / 'config.json'
        config.write_bytes(b'{"model_type":"xlm-roberta"}\n')
        return config, [
            SimpleNamespace(rfilename='model.safetensors', lfs=SimpleNamespace(sha256=subject.sha256(weights)), blob_id='pointer'),
            SimpleNamespace(rfilename='config.json', lfs=None, blob_id=subject.git_blob_sha1(config))]

    def test_both_lfs_and_non_lfs_are_bound_to_upstream(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            config, siblings = self.artifacts(root)
            receipt = self.verify(root, siblings)
            row = next(f for f in receipt['files'] if f['relativePath']=='config.json')
            self.assertEqual(row['actualGitBlobId'], siblings[1].blob_id)
            self.assertEqual(row['upstreamVerification'], 'git_blob_sha1')
            self.assertEqual(row['sha256'], hashlib.sha256(config.read_bytes()).hexdigest())

    def test_cached_modified_config_does_not_claim_pinned_revision(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            config, siblings = self.artifacts(root)
            config.write_bytes(b'{"model_type":"different"}\n')
            with self.assertRaisesRegex(RuntimeError, 'Git blob identity differs'):
                self.verify(root, siblings)

    def test_modified_weight_rejected(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            _, siblings = self.artifacts(root)
            (root/'model.safetensors').write_bytes(b'changed fake weight bytes')
            with self.assertRaisesRegex(RuntimeError, 'LFS hash differs'):
                self.verify(root, siblings)

    def test_untracked_snapshot_file_rejected(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            _, siblings = self.artifacts(root)
            (root/'unknown.json').write_bytes(b'{}')
            with self.assertRaisesRegex(RuntimeError, 'no pinned upstream identity'):
                self.verify(root, siblings)


if __name__ == '__main__':
    unittest.main()
