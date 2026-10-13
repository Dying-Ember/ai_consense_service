"""Fresh content identity and bounded pure preparation; no weights or GPU."""
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import copy
from types import SimpleNamespace

from window_runtime import file_identity, WindowRuntime
from checkpoint_identity import checkpoint_inventory
from preparation_cache import PreparationCache
from token_windows import WindowError


class FreshFileIdentityTests(unittest.TestCase):
    def test_same_length_write_with_restored_mtime_never_reuses_old_sha(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'authored-code.py'
            path.write_bytes(b'AUTHORED-original')
            first, stat = file_identity(path), path.stat()
            path.write_bytes(b'AUTHORED-modified')
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            second = file_identity(path)
            self.assertNotEqual(first['sha256'], second['sha256'])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), second['sha256'])

    def test_shard_index_all_weights_and_training_recipe_are_in_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'model.safetensors.index.json').write_text('{"weight_map":{"a":"shard-1.safetensors","b":"shard-2.safetensors"}}')
            (root / 'shard-1.safetensors').write_bytes(b'AUTHORED-shard1')
            (root / 'shard-2.safetensors').write_bytes(b'AUTHORED-shard2')
            (root / 'consense_model_recipe.json').write_text('{"split":"AUTHORED-split-A"}')
            original = checkpoint_inventory(root, file_identity)
            self.assertEqual({'model.safetensors.index.json', 'shard-1.safetensors', 'shard-2.safetensors',
                              'consense_model_recipe.json'}, {row['relativePath'] for row in original})
            (root / 'shard-2.safetensors').write_bytes(b'AUTHORED-shard3')
            self.assertNotEqual(original, checkpoint_inventory(root, file_identity))
            (root / 'shard-2.safetensors').unlink()
            with self.assertRaises(WindowError):
                checkpoint_inventory(root, file_identity)

    def test_bad_shard_indices_and_paths_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for raw in ('{"weight_map":{"a":"../escape"}}', '{"weight_map":{"a":"C:\\\\escape"}}',
                        '{"weight_map":{"a":"missing"}}', '{"weight_map":{},"weight_map":{}}'):
                (root / 'model.safetensors.index.json').write_text(raw)
                with self.subTest(raw=raw), self.assertRaises(WindowError):
                    checkpoint_inventory(root, file_identity)

    def test_warmed_tokenizer_rejects_same_size_weights_recipe_or_added_shard_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            weights = root / 'model.safetensors'
            weights.write_bytes(b'AUTHORED-one')
            backend = SimpleNamespace(MODELS={'embedding': 'AUTHORED-model'}, model_path=lambda model: str(root))
            runtime = WindowRuntime(backend)
            runtime.tokenizers['embedding'] = object()
            runtime.identities['embedding'] = {'resolvedSnapshot': str(root.resolve()), 'model': 'AUTHORED-model',
                                                'files': checkpoint_inventory(root, file_identity)}
            self.assertEqual(runtime.identities['embedding'], runtime.identity('embedding'))
            stat = weights.stat()
            weights.write_bytes(b'AUTHORED-two')
            os.utime(weights, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            with self.assertRaises(WindowError) as error:
                runtime.identity('embedding')
            self.assertEqual('MODEL_CHANGED_SINCE_PREPARATION', error.exception.kind)
            weights.write_bytes(b'AUTHORED-one')
            (root / 'consense_model_recipe.json').write_text('{"training":"AUTHORED-new"}')
            with self.assertRaises(WindowError):
                runtime.identity('embedding')


class PreparationCacheTests(unittest.TestCase):
    def test_cache_is_detached_bounded_and_never_retains_failed_producer(self):
        cache = PreparationCache(maximum_entries=2, maximum_bytes=48)
        calls = []
        def make(value):
            def producer():
                calls.append(value)
                return {'value': value}
            return producer
        first = cache.compute('a', make(1))
        first['value'] = 'caller mutation'
        self.assertEqual({'value': 1}, cache.compute('a', make(1)))
        cache.compute('b', make(2))
        cache.compute('c', make(3))
        self.assertLessEqual(cache.byte_count, 48)
        cache.compute('a', make(1))
        self.assertEqual([1, 2, 3, 1], calls)
        def failure():
            raise ValueError('AUTHORED failure')
        with self.assertRaises(ValueError):
            cache.compute('bad', failure)
        self.assertEqual({'value': 4}, cache.compute('bad', make(4)))

    def test_oversized_result_is_returned_intact_and_not_cached(self):
        cache = PreparationCache(maximum_entries=1, maximum_bytes=8)
        calls = []
        def producer():
            calls.append(1)
            return {'value': 'long AUTHORED output'}
        self.assertEqual(producer(), cache.compute('large', producer))
        cache.compute('large', producer)
        self.assertEqual(3, len(calls))
        self.assertEqual(0, cache.byte_count)

    def test_actual_structural_plan_prepares_once_and_metadata_tamper_still_rejects(self):
        import window_runtime
        import test_window_server as baseline
        from test_structural_window_runtime import DualRuntimeTests
        case = DualRuntimeTests()
        case.setUp()
        try:
            with patch.object(window_runtime, 'structural_plan', wraps=window_runtime.structural_plan) as planner:
                case.index()
                saved = baseline.s.read_project('dual-test')
                for _ in range(4):
                    case.rt.validate_metadata(saved)
                self.assertEqual(1, planner.call_count)
                returned = case.rt.prepared_structure(saved['chunks'])
                returned['contexts'][0]['content'] = 'caller mutation'
                self.assertNotEqual(returned, case.rt.prepared_structure(saved['chunks']))
                corrupt = copy.deepcopy(saved)
                corrupt['structureContexts'][0]['content'] = 'invented condition'
                with self.assertRaises(WindowError) as error:
                    case.rt.validate_metadata(corrupt)
                self.assertEqual('STRUCTURAL_MANIFEST_MISMATCH', error.exception.kind)
                self.assertEqual(1, planner.call_count)
                changed = copy.deepcopy(saved['chunks'])
                changed[0]['sourceQualityHash'] = '7' * 64
                case.rt.prepared_structure(changed)
                self.assertEqual(2, planner.call_count)
        finally:
            case.tearDown()


if __name__ == '__main__':
    unittest.main()
