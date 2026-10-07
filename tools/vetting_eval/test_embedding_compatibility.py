"""Controlled identity/AST fixtures. No model, tokenizer, vector DB or HTTP calls."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import embedding_compatibility as compat
from token_windows import WindowError, digest
import window_runtime as current

ROOT = Path(__file__).resolve().parents[2]
RECEIPT_PATH = ROOT / 'compatibility_receipt.json'


def legacy_module():
    spec = importlib.util.spec_from_file_location('controlled_legacy_window_runtime', ROOT / 'legacy_core/window_runtime.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def fixture_runtime(cls):
    model = {'name': 'controlled-offline-fixture', 'revision': 'fixture-revision'}
    server = SimpleNamespace(MODELS={'embedding': model, 'reranker': model}, digest=digest,
                             MODEL_SETTINGS={'embedding': {'maxTokens': 512}, 'reranker': {'maxTokens': 512}},
                             model_runtime=lambda kind: {'dtype': 'float32', 'device': 'cuda', 'batchSize': 4, 'serialOffload': True, 'executionMath': {'tf32': False}})
    runtime = cls(server)
    for kind in ('embedding', 'reranker'):
        runtime.tokenizers[kind] = object()
        runtime.identities[kind] = {'model': model, 'resolvedSnapshot': '/controlled/same-snapshot',
                                   'files': [{'relativePath': 'tokenizer.json', 'sha256': 'a' * 64, 'bytes': 1}],
                                   'backendSha256': 'b' * 64, 'packages': {'controlled': 'fixture-only'}}
    return runtime


class ExplicitLegacyCompatibility(unittest.TestCase):
    def setUp(self):
        self.env = {compat.ENV_PATH: str(RECEIPT_PATH), compat.ENV_SHA: compat.descriptor(RECEIPT_PATH)['sha256']}
        self.clean = patch.dict(os.environ, {compat.ENV_PATH: '', compat.ENV_SHA: '',
                                            'CONSENSE_FIXED_VECTOR_CACHE': '', 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST': '',
                                            'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256': '', 'CONSENSE_WINDOW_OVERLAP_TOKENS': '64'})
        self.clean.start(); self.addCleanup(self.clean.stop)
        self.old = fixture_runtime(legacy_module().WindowRuntime)
        self.new = fixture_runtime(current.WindowRuntime)
        self.corpus = [{'id': 'fixture-a', 'documentId': 1, 'sourceHash': 'c' * 64, 'role': 'tender',
                        'content': 'General clause. 新材料 🌱', 'parts': [{'blockId': 'paragraph-1', 'text': 'General clause. 新材料 🌱'}]}]

    def test_no_receipt_conservatively_keeps_new_signature(self):
        self.assertNotEqual(self.old.signature(self.corpus), self.new.signature(self.corpus))
        self.assertNotEqual(self.old.embedding_cache_identity(1024), self.new.embedding_cache_identity(1024))
        self.assertEqual(self.new.signature(self.corpus)['algorithms'], self.new.algorithms())

    def test_explicit_source_proof_preserves_embedding_only(self):
        with patch.dict(os.environ, self.env):
            self.assertEqual(self.old.signature(self.corpus), self.new.signature(self.corpus))
            self.assertEqual(self.old.embedding_cache_identity(1024), self.new.embedding_cache_identity(1024))
            before = self.old.retrieval_recipe(observe_tokenizer_identity=False)
            after = self.new.retrieval_recipe(observe_tokenizer_identity=False)
            self.assertNotEqual(before, after)
            self.assertNotEqual(before['algorithms'], after['algorithms'])
            self.assertEqual(after['algorithms'], self.new.algorithms())
            self.assertEqual(after['structuralSelection']['policy'], 'unique-source-structural-unit-seeds-v1')

    def test_reuse_does_not_erase_corpus_model_window_math_identity(self):
        with patch.dict(os.environ, self.env):
            before = self.old.signature(self.corpus)
            changed = copy.deepcopy(self.corpus); changed[0]['content'] += ' revision'
            self.assertNotEqual(before, self.new.signature(changed))
            self.new.overlap = 63
            self.assertNotEqual(before, self.new.signature(self.corpus))
            self.new.overlap = 64
            self.new.identities['embedding']['files'][0]['sha256'] = 'd' * 64
            self.assertNotEqual(before, self.new.signature(self.corpus))
            self.new.identities['embedding']['files'][0]['sha256'] = 'a' * 64
            self.new.s.model_runtime = lambda kind: {'dtype': 'float16', 'device': 'cuda'}
            self.assertNotEqual(before, self.new.signature(self.corpus))
            self.assertNotEqual(self.old.embedding_cache_identity(1024), self.new.embedding_cache_identity(512))

    def test_missing_external_binding_and_changed_receipt_rejected(self):
        with patch.dict(os.environ, {compat.ENV_PATH: str(RECEIPT_PATH)}):
            with self.assertRaises(WindowError): self.new.signature(self.corpus)
        with patch.dict(os.environ, {**self.env, compat.ENV_SHA: '0' * 64}):
            with self.assertRaises(WindowError): self.new.embedding_cache_identity(1024)

    def mutated_copy(self, directory, name, edit):
        receipt = json.loads(RECEIPT_PATH.read_text(encoding='utf-8'))
        copied = Path(directory) / 'current'; copied.mkdir()
        for n, value in receipt['currentCore'].items():
            shutil.copyfile(value['path'], copied / n)
        path = copied / name; path.write_text(edit(path.read_text(encoding='utf-8')), encoding='utf-8', newline='\n')
        receipt['currentCore'] = {n: compat.descriptor(copied / n) for n in receipt['currentCore']}
        return copied, receipt

    def test_changed_encode_dependency_refuses_even_rehashed_descriptor(self):
        with tempfile.TemporaryDirectory() as directory:
            root, receipt = self.mutated_copy(directory, 'server.py', lambda s: s.replace('def encode_embeddings(texts):', 'def encode_embeddings(texts):\n    changed_embedding_path = True'))
            with self.assertRaisesRegex(WindowError, 'Embedding dependency bytes changed'):
                compat.validate_proof(receipt, root)

    def test_changed_index_method_ast_refuses_even_rehashed_descriptor(self):
        with tempfile.TemporaryDirectory() as directory:
            root, receipt = self.mutated_copy(directory, 'window_runtime.py', lambda s: s.replace('    def index(self,request):', '    def index(self,request):\n        changed_upsert_path = True'))
            with self.assertRaisesRegex(WindowError, 'method AST changed: index'):
                compat.validate_proof(receipt, root)

    def test_query_selector_and_signature_rewrites_are_not_open_ended(self):
        for name, edit in (
            ('retrieval_source_units.py', lambda s: s + '\nunknown_algorithm = True\n'),
            ('window_runtime.py', lambda s: s.replace("'normalized':True", "'normalized':False")),
            ('launch_window_retrieval.py', lambda s: s.replace("'workers'", "'workers'" ) + '\nunknown_launch_behavior = True\n')):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root, receipt = self.mutated_copy(directory, name, edit)
                with self.assertRaises(WindowError): compat.validate_proof(receipt, root)

    def test_fake_old_hash_or_duplicate_receipt_key_refused(self):
        receipt = json.loads(RECEIPT_PATH.read_text(encoding='utf-8'))
        receipt['proof']['legacyAlgorithmHashes']['window_runtime.py'] = '0' * 64
        with self.assertRaisesRegex(WindowError, 'Stored proof differs'):
            compat.validate_proof(receipt, Path(current.__file__).parent)
        with self.assertRaises(WindowError): json.loads('{"proof":1,"proof":2}', object_pairs_hook=compat.unique_object)


if __name__ == '__main__':
    unittest.main()
