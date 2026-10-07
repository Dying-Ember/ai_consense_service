import copy
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import launch_window_retrieval as launcher


class LauncherGuards(unittest.TestCase):
    def args(self, root):
        return SimpleNamespace(workspace=str(root), state=str(root / 'fresh-state'),
                               embedding_snapshot='embed', rerank_snapshot='rank', fixed_cache_bundle=None,
                               fixed_cache_bundle_sha256=None, host='127.0.0.1', port=8868)

    def test_state_must_be_project_scoped(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            args.state = str(Path(directory).parent / 'outside')
            with self.assertRaises(ValueError):
                launcher.settings(args)

    def test_cache_requires_external_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory)); args.fixed_cache_bundle = 'cache.json'
            with self.assertRaises(ValueError):
                launcher.settings(args)

    def test_loopback_is_only_bind_host(self):
        with self.assertRaises(SystemExit):
            launcher.parse(['prepare', '--workspace', 'x', '--state', 'x', '--embedding-snapshot', 'x', '--rerank-snapshot', 'x', '--receipt', 'x', '--host', '0.0.0.0'])

    def test_receipt_binds_workspace_helper_without_loading_models(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(receipt=str(Path(directory) / 'receipt.json'), mode='prepare')
            descriptor = launcher.write_receipt(args, {})
            import json
            value = json.loads(Path(descriptor['path']).read_text(encoding='utf-8'))
            self.assertEqual(value['software']['workspace_root.py'],
                             launcher.desc(Path(launcher.__file__).with_name('workspace_root.py')))
            self.assertEqual(value['software']['retrieval_source_units.py'],
                             launcher.desc(Path(launcher.__file__).with_name('retrieval_source_units.py')))
            self.assertEqual(value['actualCallsAtReceipt']['modelWeightLoads'], 0)
            self.assertFalse(any('known legacy' in item for item in value['limits']))

    def test_startup_wrapping_preserves_frozen_math_and_model_paths(self):
        runtime = SimpleNamespace(embedding_cache_identity=lambda n: {'dimension': n})
        server = SimpleNamespace(model_runtime=lambda k: {'kind': k}, window_runtime=lambda: runtime,
                                 MODELS={'embedding': {'name': 'embedding'}, 'reranker': {'name': 'reranker'}})
        models = {'embedding': {'snapshot': '/explicit/embed'}, 'reranker': {'snapshot': '/explicit/rank'}}
        math = copy.deepcopy(launcher.MATH)
        launcher.configure_server(server, models, math)
        self.assertEqual(server.model_runtime('embedding')['executionMath'], launcher.MATH)
        self.assertEqual(runtime.embedding_cache_identity(1024)['executionMath'], launcher.MATH)
        self.assertEqual(server.model_path({'name': 'embedding'}), '/explicit/embed')
        self.assertEqual(server.model_path({'name': 'reranker'}), '/explicit/rank')


if __name__ == '__main__':
    unittest.main()
