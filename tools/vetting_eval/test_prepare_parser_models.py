"""Public preparation contracts using independent, literal upstream manifests.

The SDK boundary is mocked. Every artifact contains tiny synthetic bytes;
no downloaded model, vendor Python code, or inference runtime is loaded.
"""
import hashlib
from io import StringIO
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import prepare_oer_models as subject


PADDLE_VL = (
    'PaddlePaddle/PaddleOCR-VL-1.6',
    'c5630abae1d940eafe0697512a0325494b02ab42',
    (
        'LICENSE', 'README.md', 'added_tokens.json', 'chat_template.jinja',
        'config.json', 'configuration_paddleocr_vl.py', 'generation_config.json',
        'image_processing_paddleocr_vl.py', 'inference.yml', 'model.safetensors',
        'modeling_paddleocr_vl.py', 'preprocessor_config.json',
        'processing_paddleocr_vl.py', 'processor_config.json',
        'special_tokens_map.json', 'tokenizer.json', 'tokenizer.model',
        'tokenizer_config.json',
    ),
)
PADDLE_LAYOUT = (
    'PaddlePaddle/PP-DocLayoutV3',
    '241f8bdfc77a7c7bee915a5057aaee58c235a8d3',
    ('README.md', 'inference.json', 'inference.pdiparams', 'inference.yml'),
)
MINERU_SMALL = (
    'opendatalab/MinerU-4_models_onnx',
    '358310b4f64b95f9fefc372ad899356e4111f376',
    (
        'Layout/PP-DocLayoutV2/inference.onnx',
        'Layout/PP-DocLayoutV2/inference.yml',
        'MFR/pp_formulanet_plus_m/PP-FormulaNet_plus-M.onnx',
        'MFR/pp_formulanet_plus_m/PP-FormulaNet_plus-M_inference.yml',
        'OCR/paddleocr/ch_PP-OCRv6_small_rec_infer.onnx',
        'OCR/paddleocr/ch_PP-OCRv6_small_rec_inference.yml',
        'OCR/paddleocr/ch_PP-OCRv6_tiny_det_infer.onnx',
        'OCR/paddleocr/ch_PP-OCRv6_tiny_det_inference.yml',
        'OCR/paddleocr/seal_PP-OCRv4_det_infer.onnx',
        'OCR/paddleocr/seal_PP-OCRv4_det_inference.yml',
        'README.md', 'Table/PP-LCNet_x1_0_table_cls.onnx',
        'Table/slanet-plus.onnx', 'Table/unet.onnx', 'manifest.json',
    ),
)
PARSER_ROLES = (
    ('paddle-vl', PADDLE_VL), ('paddle-layout', PADDLE_LAYOUT),
    ('mineru-small', MINERU_SMALL),
)


class ParserPreparationTests(unittest.TestCase):
    def snapshot(self, root, spec):
        siblings = []
        for relative in spec[2]:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            payload = ('synthetic pinned artifact: ' + relative + '\n').encode('utf-8')
            path.write_bytes(payload)
            digest = hashlib.sha256(payload).hexdigest()
            blob = hashlib.sha1(b'blob ' + str(len(payload)).encode('ascii') + b'\0' + payload).hexdigest()
            is_lfs = path.suffix in {'.safetensors', '.onnx', '.pdiparams'}
            siblings.append(SimpleNamespace(
                rfilename=relative,
                lfs=SimpleNamespace(sha256=digest) if is_lfs else None,
                blob_id='synthetic-lfs-pointer' if is_lfs else blob,
            ))
        return siblings

    def prepare(self, root, kind, spec, siblings):
        with patch('huggingface_hub.HfApi') as api, patch(
                'huggingface_hub.snapshot_download', return_value=str(root)) as download:
            api.return_value.model_info.return_value = SimpleNamespace(
                sha=spec[1], siblings=siblings)
            receipt = subject.prepare(kind, root.parent / 'cache')
            api.return_value.model_info.assert_called_once_with(
                spec[0], revision=spec[1], files_metadata=True)
            self.assertEqual(download.call_args.args, (spec[0],))
            self.assertEqual(download.call_args.kwargs['revision'], spec[1])
            self.assertEqual(set(download.call_args.kwargs['allow_patterns']), set(spec[2]))
            self.assertEqual({row['relativePath'] for row in receipt['files']}, set(spec[2]))
            self.assertEqual(receipt['name'], spec[0])
            self.assertEqual(receipt['revision'], spec[1])
            self.assertEqual(receipt['modelWeightLoads'], 0)
            return receipt

    def test_paddle_vl_prepares_custom_processor_and_tokenizer_closure(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'snapshot'
            root.mkdir()
            siblings = self.snapshot(root, PADDLE_VL)
            self.prepare(root, 'paddle-vl', PADDLE_VL, siblings)

    def test_paddle_vl_rejects_unpinned_processor_before_download(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'snapshot'
            root.mkdir()
            siblings = self.snapshot(root, PADDLE_VL)
            (root / 'image_processing_paddleocr_vl.py').unlink()
            siblings = [row for row in siblings if row.rfilename != 'image_processing_paddleocr_vl.py']
            with patch('huggingface_hub.HfApi') as api, patch(
                    'huggingface_hub.snapshot_download', return_value=str(root)) as download:
                api.return_value.model_info.return_value = SimpleNamespace(
                    sha=PADDLE_VL[1], siblings=siblings)
                with self.assertRaisesRegex(RuntimeError, 'Required pinned upstream artifact missing: image_processing_paddleocr_vl.py'):
                    subject.prepare('paddle-vl', root.parent / 'cache')
                download.assert_not_called()

    def test_paddle_vl_rejects_download_without_required_tokenizer_model(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'snapshot'
            root.mkdir()
            siblings = self.snapshot(root, PADDLE_VL)
            (root / 'tokenizer.model').unlink()
            with patch('huggingface_hub.HfApi') as api, patch(
                    'huggingface_hub.snapshot_download', return_value=str(root)):
                api.return_value.model_info.return_value = SimpleNamespace(
                    sha=PADDLE_VL[1], siblings=siblings)
                with self.assertRaisesRegex(RuntimeError, 'Required downloaded artifact missing: tokenizer.model'):
                    subject.prepare('paddle-vl', root.parent / 'cache')

    def test_paddle_layout_prepares_native_graph_parameters_and_preprocessing(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'snapshot'
            root.mkdir()
            siblings = self.snapshot(root, PADDLE_LAYOUT)
            self.prepare(root, 'paddle-layout', PADDLE_LAYOUT, siblings)

    def test_mineru_auxiliaries_prepare_layout_ocr_formula_and_table_bundle(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'snapshot'
            root.mkdir()
            siblings = self.snapshot(root, MINERU_SMALL)
            self.prepare(root, 'mineru-small', MINERU_SMALL, siblings)

    def test_selected_role_rejects_pinned_but_unselected_cache_file(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'snapshot'
            root.mkdir()
            siblings = self.snapshot(root, PADDLE_LAYOUT)
            extra = root / '.gitattributes'
            payload = b'*.pdiparams filter=lfs\n'
            extra.write_bytes(payload)
            blob = hashlib.sha1(b'blob ' + str(len(payload)).encode('ascii') + b'\0' + payload).hexdigest()
            siblings.append(SimpleNamespace(rfilename='.gitattributes', lfs=None, blob_id=blob))
            with patch('huggingface_hub.HfApi') as api, patch(
                    'huggingface_hub.snapshot_download', return_value=str(root)):
                api.return_value.model_info.return_value = SimpleNamespace(
                    sha=PADDLE_LAYOUT[1], siblings=siblings)
                with self.assertRaisesRegex(RuntimeError, 'Local artifact is outside required role assets: .gitattributes'):
                    subject.prepare('paddle-layout', root.parent / 'cache')

    def test_cache_symlink_receipt_does_not_claim_portable_offline_runtime(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'snapshot'
            root.mkdir()
            siblings = self.snapshot(root, PADDLE_LAYOUT)
            weight = root / 'inference.pdiparams'
            blob = root.parent / 'blob-bytes'
            blob.write_bytes(weight.read_bytes())
            weight.unlink()
            weight.symlink_to(blob)
            receipt = self.prepare(root, 'paddle-layout', PADDLE_LAYOUT, siblings)
            self.assertEqual(receipt['snapshotKind'], 'huggingface_cache_snapshot')
            self.assertTrue(receipt['containsSnapshotSymlinks'])
            self.assertFalse(receipt['offlinePackageVerified'])
            self.assertFalse(receipt['runtimeDependencyClosureVerified'])
            self.assertTrue(receipt['requiredRepositoryAssetClosureVerified'])
            self.assertEqual(set(receipt['requiredRepositoryFiles']), set(PADDLE_LAYOUT[2]))
            row = next(row for row in receipt['files'] if row['relativePath'] == 'inference.pdiparams')
            self.assertTrue(row['isCacheSymlink'])
            self.assertEqual(row['sha256'], hashlib.sha256(blob.read_bytes()).hexdigest())

    def test_lfs_without_sha256_cannot_downgrade_to_git_blob_identity(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'snapshot'
            root.mkdir()
            siblings = self.snapshot(root, PADDLE_LAYOUT)
            row = next(row for row in siblings if row.rfilename == 'inference.pdiparams')
            payload = (root / row.rfilename).read_bytes()
            row.lfs = SimpleNamespace(sha256=None)
            row.blob_id = hashlib.sha1(b'blob ' + str(len(payload)).encode('ascii') + b'\0' + payload).hexdigest()
            with patch('huggingface_hub.HfApi') as api, patch(
                    'huggingface_hub.snapshot_download', return_value=str(root)):
                api.return_value.model_info.return_value = SimpleNamespace(
                    sha=PADDLE_LAYOUT[1], siblings=siblings)
                with self.assertRaisesRegex(RuntimeError, 'Missing upstream LFS SHA256: inference.pdiparams'):
                    subject.prepare('paddle-layout', root.parent / 'cache')

    def reject(self, root, kind, spec, siblings, message, before_download=False):
        with patch('huggingface_hub.HfApi') as api, patch(
                'huggingface_hub.snapshot_download', return_value=str(root)) as download:
            api.return_value.model_info.return_value = SimpleNamespace(
                sha=spec[1], siblings=siblings)
            with self.assertRaises(RuntimeError) as caught:
                subject.prepare(kind, root.parent / 'cache')
            self.assertEqual(str(caught.exception), message)
            if before_download:
                download.assert_not_called()

    def test_every_required_parser_asset_is_enforced_in_upstream_manifest(self):
        for kind, spec in PARSER_ROLES:
            for missing in spec[2]:
                with self.subTest(kind=kind, missing=missing), TemporaryDirectory() as directory:
                    root = Path(directory) / 'snapshot'
                    root.mkdir()
                    siblings = self.snapshot(root, spec)
                    siblings = [row for row in siblings if row.rfilename != missing]
                    self.reject(root, kind, spec, siblings,
                                'Required pinned upstream artifact missing: ' + missing,
                                before_download=True)

    def test_every_required_parser_asset_is_enforced_in_local_download(self):
        for kind, spec in PARSER_ROLES:
            for missing in spec[2]:
                with self.subTest(kind=kind, missing=missing), TemporaryDirectory() as directory:
                    root = Path(directory) / 'snapshot'
                    root.mkdir()
                    siblings = self.snapshot(root, spec)
                    (root / missing).unlink()
                    self.reject(root, kind, spec, siblings,
                                'Required downloaded artifact missing: ' + missing)

    def test_each_parser_role_rejects_modified_lfs_weight_bytes(self):
        weights = (
            ('paddle-vl', PADDLE_VL, 'model.safetensors'),
            ('paddle-layout', PADDLE_LAYOUT, 'inference.pdiparams'),
            ('mineru-small', MINERU_SMALL, 'Table/unet.onnx'),
        )
        for kind, spec, relative in weights:
            with self.subTest(kind=kind), TemporaryDirectory() as directory:
                root = Path(directory) / 'snapshot'
                root.mkdir()
                siblings = self.snapshot(root, spec)
                (root / relative).write_bytes(b'changed synthetic weight bytes')
                self.reject(root, kind, spec, siblings, 'Upstream LFS hash differs: ' + relative)

    def test_each_parser_role_rejects_modified_non_lfs_helper_bytes(self):
        helpers = (
            ('paddle-vl', PADDLE_VL, 'processing_paddleocr_vl.py'),
            ('paddle-layout', PADDLE_LAYOUT, 'inference.yml'),
            ('mineru-small', MINERU_SMALL, 'manifest.json'),
        )
        for kind, spec, relative in helpers:
            with self.subTest(kind=kind), TemporaryDirectory() as directory:
                root = Path(directory) / 'snapshot'
                root.mkdir()
                siblings = self.snapshot(root, spec)
                (root / relative).write_bytes(b'changed synthetic helper bytes')
                self.reject(root, kind, spec, siblings, 'Upstream Git blob identity differs: ' + relative)

    def test_each_parser_role_rejects_unknown_non_lfs_identity(self):
        for kind, spec in PARSER_ROLES:
            with self.subTest(kind=kind), TemporaryDirectory() as directory:
                root = Path(directory) / 'snapshot'
                root.mkdir()
                siblings = self.snapshot(root, spec)
                row = next(row for row in siblings if row.rfilename == 'README.md')
                row.blob_id = None
                self.reject(root, kind, spec, siblings, 'Missing upstream Git blob identity: README.md')

    def test_parser_revision_mismatch_stops_before_download(self):
        for kind, spec in PARSER_ROLES:
            with self.subTest(kind=kind), TemporaryDirectory() as directory:
                root = Path(directory) / 'snapshot'
                root.mkdir()
                siblings = self.snapshot(root, spec)
                with patch('huggingface_hub.HfApi') as api, patch('huggingface_hub.snapshot_download') as download:
                    api.return_value.model_info.return_value = SimpleNamespace(sha='not-the-pinned-revision', siblings=siblings)
                    with self.assertRaisesRegex(RuntimeError, 'Resolved model revision differs'):
                        subject.prepare(kind, root.parent / 'cache')
                    download.assert_not_called()

    def test_cli_prepares_all_three_roles_with_repository_only_receipt(self):
        with TemporaryDirectory() as directory:
            run = Path(directory)
            roots = {}
            infos = {}
            for kind, spec in PARSER_ROLES:
                root = run / kind
                root.mkdir()
                siblings = self.snapshot(root, spec)
                roots[spec[0]] = str(root)
                infos[spec[0]] = SimpleNamespace(sha=spec[1], siblings=siblings)
            output = run / 'receipt.json'
            with patch('huggingface_hub.HfApi') as api, patch(
                    'huggingface_hub.snapshot_download', side_effect=lambda name, **kwargs: roots[name]), patch('sys.stdout', StringIO()) as stdout:
                api.return_value.model_info.side_effect = lambda name, **kwargs: infos[name]
                subject.main(['--cache', str(run / 'cache'), '--receipt', str(output),
                              '--models', 'paddle-vl', 'paddle-layout', 'mineru-small'])
                printed = json.loads(stdout.getvalue())
            receipt = json.loads(output.read_text(encoding='utf-8'))
            self.assertEqual(receipt['status'], 'downloaded_and_byte_verified_no_inference')
            self.assertEqual([row['kind'] for row in receipt['models']], ['paddle-vl', 'paddle-layout', 'mineru-small'])
            self.assertEqual(printed['models'], [spec[0] for _, spec in PARSER_ROLES])
            for row, (_, spec) in zip(receipt['models'], PARSER_ROLES):
                self.assertEqual({asset['relativePath'] for asset in row['files']}, set(spec[2]))
                self.assertEqual(row['modelWeightLoads'], 0)
                self.assertTrue(row['requiredRepositoryAssetClosureVerified'])
                self.assertFalse(row['offlinePackageVerified'])
                self.assertFalse(row['runtimeDependencyClosureVerified'])

    def test_cli_existing_receipt_is_preserved_before_any_sdk_call(self):
        with TemporaryDirectory() as directory:
            run = Path(directory)
            output = run / 'receipt.json'
            original = b'previous failure evidence\n'
            output.write_bytes(original)
            with patch('huggingface_hub.HfApi') as api, patch('huggingface_hub.snapshot_download') as download:
                with self.assertRaisesRegex(ValueError, 'Use a fresh receipt path'):
                    subject.main(['--cache', str(run / 'cache'), '--receipt', str(output), '--models', 'paddle-vl'])
                api.assert_not_called()
                download.assert_not_called()
            self.assertEqual(output.read_bytes(), original)

    def test_cli_incomplete_role_does_not_write_success_or_dispatch_later_role(self):
        with TemporaryDirectory() as directory:
            run = Path(directory)
            roots = {}
            infos = {}
            for kind, spec in PARSER_ROLES:
                root = run / kind
                root.mkdir()
                siblings = self.snapshot(root, spec)
                roots[spec[0]] = str(root)
                infos[spec[0]] = SimpleNamespace(sha=spec[1], siblings=siblings)
            (run / 'paddle-layout' / 'inference.yml').unlink()
            output = run / 'receipt.json'
            with patch('huggingface_hub.HfApi') as api, patch(
                    'huggingface_hub.snapshot_download', side_effect=lambda name, **kwargs: roots[name]) as download:
                api.return_value.model_info.side_effect = lambda name, **kwargs: infos[name]
                with self.assertRaisesRegex(RuntimeError, 'Required downloaded artifact missing: inference.yml'):
                    subject.main(['--cache', str(run / 'cache'), '--receipt', str(output),
                                  '--models', 'paddle-vl', 'paddle-layout', 'mineru-small'])
                self.assertEqual([call.args[0] for call in download.call_args_list],
                                 ['PaddlePaddle/PaddleOCR-VL-1.6', 'PaddlePaddle/PP-DocLayoutV3'])
            self.assertFalse(output.exists())

    def test_linked_asset_directory_cannot_claim_an_incomplete_verified_receipt(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'snapshot'
            root.mkdir()
            siblings = self.snapshot(root, MINERU_SMALL)
            table = root / 'Table'
            external = root.parent / 'shared-table-cache'
            table.rename(external)
            table.symlink_to(external, target_is_directory=True)
            self.reject(root, 'mineru-small', MINERU_SMALL, siblings,
                        'Required verified artifact missing: Table/PP-LCNet_x1_0_table_cls.onnx')


if __name__ == '__main__':
    unittest.main()
