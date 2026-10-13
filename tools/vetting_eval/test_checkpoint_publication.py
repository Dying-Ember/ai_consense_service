"""Small real-byte publication lifecycle; no tokenizer/model/GPU is loaded."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from checkpoint_publication import CheckpointPublication, publish_checkpoint
from token_windows import WindowError
import window_runtime


class CheckpointPublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        self.model = {'name': 'AUTHORED-model', 'revision': 'AUTHORED-revision'}
        (self.source / 'model.safetensors.index.json').write_text('{"weight_map":{"a":"weight1.safetensors","b":"weight2.safetensors"}}')
        (self.source / 'weight1.safetensors').write_bytes(b'AUTHORED-weight-one')
        (self.source / 'weight2.safetensors').write_bytes(b'AUTHORED-weight-two')
        (self.source / 'consense_model_recipe.json').write_text('{"training":"AUTHORED-split-A"}')
        (self.source / '1_Pooling').mkdir()
        (self.source / '1_Pooling/config.json').write_text('{"pooling":"AUTHORED"}')
        self.receipt = publish_checkpoint(self.source, self.root / 'publications', self.model)
        self.publication = CheckpointPublication(self.receipt['manifestPath'], self.receipt['manifestSha256'], self.model)

    def tearDown(self):
        self.temp.cleanup()

    def test_snapshot_is_exact_copy_not_mutable_source_link_and_publication_is_idempotent(self):
        self.assertEqual(self.receipt, publish_checkpoint(self.source, self.root / 'publications', self.model))
        frozen = self.publication.snapshot / 'weight1.safetensors'
        before = frozen.read_bytes()
        (self.source / 'weight1.safetensors').write_bytes(b'AUTHORED-weight-new')
        self.assertEqual(before, frozen.read_bytes())
        new = publish_checkpoint(self.source, self.root / 'publications', self.model)
        self.assertNotEqual(self.receipt['publicationSha256'], new['publicationSha256'])
        self.assertTrue(Path(self.receipt['manifestPath']).exists())
        self.assertEqual(5, len(self.publication.verify_snapshot()['files']))

    def test_first_prepare_hashes_complete_snapshot_then_queries_read_small_manifest_only(self):
        calls = []
        original = window_runtime.file_identity
        def measured(path):
            calls.append(Path(path).name)
            return original(path)
        with patch.object(window_runtime, 'file_identity', side_effect=measured):
            first = self.publication.identity()
            for _ in range(8):
                self.assertEqual(first, self.publication.identity())
        self.assertEqual(5, len(calls))
        self.assertIn('weight1.safetensors', calls)
        self.assertIn('weight2.safetensors', calls)

    def test_manifest_change_is_detected_online_and_full_weight_drift_rejected_by_new_runtime(self):
        self.publication.identity()
        manifest = Path(self.receipt['manifestPath'])
        original = manifest.read_bytes()
        manifest.chmod(0o644)
        try:
            manifest.write_bytes(original + b' ')
            with self.assertRaises(WindowError):
                self.publication.identity()
        finally:
            manifest.write_bytes(original)
        frozen = self.publication.snapshot / 'weight1.safetensors'
        raw = frozen.read_bytes()
        frozen.chmod(0o644)
        frozen.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
        fresh = CheckpointPublication(self.receipt['manifestPath'], self.receipt['manifestSha256'], self.model)
        with self.assertRaises(WindowError) as error:
            fresh.identity()
        self.assertEqual('MODEL_PUBLICATION_BYTES_CHANGED', error.exception.kind)

    def test_runtime_loader_tokenizer_and_model_instance_use_pinned_publication(self):
        environment = {'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST': self.receipt['manifestPath'],
                       'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST_SHA256': self.receipt['manifestSha256']}
        loaded = SimpleNamespace()
        backend = SimpleNamespace(MODELS={'embedding': self.model, 'reranker': 'AUTHORED-reranker'},
            model_path=lambda model: (_ for _ in ()).throw(AssertionError('Legacy mutable loader forbidden')),
            embedding_model=lambda: loaded, rerank_model=lambda: None, _embedding=None, _reranker=None)
        with patch.dict(os.environ, environment):
            runtime = window_runtime.WindowRuntime(backend)
            snapshot = Path(backend.model_path(self.model)).resolve()
            self.assertEqual(self.publication.snapshot.resolve(), snapshot)
            runtime.tokenizers['embedding'] = object()
            runtime.identities['embedding'] = {'model': self.model, 'resolvedSnapshot': str(snapshot),
                'files': runtime.model_publications['embedding'].identity()['files']}
            original = window_runtime.file_identity
            with patch.object(window_runtime, 'file_identity', wraps=original) as hasher:
                for _ in range(4):
                    runtime.identity('embedding')
                    self.assertIs(loaded, backend.embedding_model())
                self.assertEqual(0, hasher.call_count)
            self.assertEqual(self.receipt['publicationSha256'], loaded._consense_checkpoint_publication)
            with patch.dict(os.environ, {'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST_SHA256': '1' * 64}):
                with self.assertRaises(WindowError) as error:
                    runtime.identity('embedding')
                self.assertEqual('MODEL_PUBLICATION_VERSION_SWITCH', error.exception.kind)
            loaded._consense_checkpoint_publication = '1' * 64
            with self.assertRaises(WindowError):
                backend.embedding_model()

    def test_missing_external_sha_wrong_model_and_nested_destination_reject(self):
        with self.assertRaises(WindowError):
            CheckpointPublication(self.receipt['manifestPath'], '', self.model)
        with self.assertRaises(WindowError):
            CheckpointPublication(self.receipt['manifestPath'], self.receipt['manifestSha256'], {'name': 'other'})
        with self.assertRaises(WindowError):
            publish_checkpoint(self.source, self.source / 'recursive', self.model)

    def test_declared_missing_shard_is_rejected_before_publication(self):
        (self.source / 'weight2.safetensors').unlink()
        with self.assertRaises(WindowError) as error:
            publish_checkpoint(self.source, self.root / 'incomplete-publications', self.model)
        self.assertEqual('MODEL_SHARD_UNAVAILABLE', error.exception.kind)
        self.assertEqual([], list((self.root / 'incomplete-publications').iterdir()))

    def test_complete_processor_tokenizer_and_nested_recipe_bytes_are_included(self):
        (self.source / 'processor').mkdir()
        (self.source / 'processor/preprocessor_config.json').write_bytes(b'AUTHORED-processor')
        (self.source / 'tokenizer.json').write_bytes(b'AUTHORED-tokenizer')
        (self.source / 'training_recipe.json').write_bytes(b'{"split":"AUTHORED-B"}')
        receipt = publish_checkpoint(self.source, self.root / 'complete-publications', self.model)
        publication = CheckpointPublication(receipt['manifestPath'], receipt['manifestSha256'], self.model)
        actual = publication.verify_snapshot()
        expected = sorted(path.relative_to(self.source).as_posix() for path in self.source.rglob('*') if path.is_file())
        self.assertEqual(expected, [row['relativePath'] for row in actual['files']])
        for row in actual['files']:
            self.assertEqual((self.source / row['relativePath']).read_bytes(), (publication.snapshot / row['relativePath']).read_bytes())


if __name__ == '__main__':
    unittest.main()
