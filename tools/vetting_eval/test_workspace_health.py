"""Pure root/metadata tests; no actual tokenizer/model/HTTP/index/DB calls."""
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from workspace_root import resolve_workspace
from window_runtime import WindowRuntime
from token_windows import WindowError
import window_runtime as runtime_module

path = Path(__file__).with_name('server.py')
spec = importlib.util.spec_from_file_location('metadata_health_server_test', path)
server = importlib.util.module_from_spec(spec); sys.modules[spec.name] = server; spec.loader.exec_module(server)


class WorkspaceHealthTests(unittest.TestCase):
    def test_repository_default_is_workspace_not_parent_coding_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); here = root / 'arbitrary-service/tools/vetting_eval'; here.mkdir(parents=True)
            (root / 'arbitrary-service/pom.xml').write_text('<project/>')
            self.assertEqual(resolve_workspace(here, {}), root.resolve())

    def test_explicit_repository_root_wins(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.assertEqual(resolve_workspace(path.parent, {'CONSENSE_WORKSPACE_ROOT': str(root)}), root.resolve())

    def test_isolated_stage_root_is_explicit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); here = root / 'many/levels/isolated/frozen_software'; here.mkdir(parents=True)
            self.assertEqual(resolve_workspace(here, {'CONSENSE_WORKSPACE_ROOT': str(root)}), root.resolve())

    def test_portable_arbitrary_extraction_depth_is_explicit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); here = root / 'package/sources/runtime'; here.mkdir(parents=True)
            self.assertEqual(resolve_workspace(here, {'CONSENSE_WORKSPACE_ROOT': str(root)}), root.resolve())

    def test_unknown_layout_fails_instead_of_guessing_parent(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                resolve_workspace(folder, {})

    def test_empty_explicit_root_rejected(self):
        for value in ('', '   ', None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                resolve_workspace(path.parent, {'CONSENSE_WORKSPACE_ROOT': value})

    def test_missing_explicit_root_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                resolve_workspace(path.parent, {'CONSENSE_WORKSPACE_ROOT': str(Path(folder) / 'missing')})

    def test_root_with_unicode_astral_and_spaces(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / '项目 😀 spaces'; root.mkdir()
            self.assertEqual(resolve_workspace(path.parent, {'CONSENSE_WORKSPACE_ROOT': str(root)}), root.resolve())

    def health(self, version=None):
        rt = WindowRuntime(server)
        rt.identity = lambda kind: (_ for _ in ()).throw(AssertionError('No tokenizer identity load'))
        rt.tokenizer = lambda kind: (_ for _ in ()).throw(AssertionError('No tokenizer'))
        with patch.object(server, '_windows_runtime', rt), patch.object(server, 'device', return_value='cpu'), \
             patch.object(server, 'embedding_model', side_effect=AssertionError('No model')), \
             patch.object(server, 'rerank_model', side_effect=AssertionError('No model')), \
             patch.object(server, 'database', side_effect=AssertionError('No database')):
            if version is None:
                return server.health()
            with patch.object(runtime_module, 'SIGNATURE_VERSION', version):
                return server.health()

    def test_health_uses_actual_shared_signature_version(self):
        self.assertEqual(self.health()['indexSignatureVersion'], 3)
        self.assertEqual(self.health(version=9)['indexSignatureVersion'], 9)

    def test_health_does_not_load_tokenizer_or_neural_models(self):
        result = self.health(); windows = result['retrieval']['sourceWindows']
        self.assertFalse(windows['tokenizerIdentityObserved'])
        self.assertIsNone(windows['rerankTokenizerAndModelFiles'])
        self.assertEqual(windows['embeddingWindows']['max_tokens'], server.MODEL_SETTINGS['embedding']['maxTokens'])

    def test_observed_retrieval_recipe_preserves_explicit_identity_path(self):
        rt = WindowRuntime(server); calls = []; rt.identity = lambda kind: calls.append(kind) or {'fixture': kind}
        with patch.object(server, 'device', return_value='cpu'):
            full = rt.retrieval_recipe(); lazy = rt.retrieval_recipe(observe_tokenizer_identity=False)
        self.assertEqual(calls, ['reranker'])
        self.assertEqual(full['rerankTokenizerAndModelFiles'], {'fixture': 'reranker'})
        self.assertIsNone(lazy['rerankTokenizerAndModelFiles'])

    def test_runtime_binds_workspace_helper_and_rejects_changed_loaded_bytes(self):
        rt = WindowRuntime(server)
        self.assertIn('workspace_root.py', rt.algorithms())
        original = runtime_module.file_identity
        def changed(path):
            value = original(path)
            return {**value, 'sha256': '0' * 64} if Path(path).name == 'workspace_root.py' else value
        with patch.object(runtime_module, 'file_identity', side_effect=changed), self.assertRaises(WindowError) as error:
            rt.algorithms()
        self.assertEqual(error.exception.kind, 'SOFTWARE_CHANGED_SINCE_IMPORT')


if __name__ == '__main__':
    unittest.main()
