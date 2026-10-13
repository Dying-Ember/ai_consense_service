"""Portable model export contracts; synthetic files, no downloads or inference."""
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import materialize_oer_models as subject
from prepare_oer_models import MODELS, git_blob_sha1


class PortableModelTests(unittest.TestCase):
    def fixture(self, root):
        snapshot = root / 'hf' / 'snapshot'
        snapshot.mkdir(parents=True)
        files = []
        for relative, data in [('config.json', b'{"synthetic":true}\n'),
                               ('model.safetensors', b'synthetic weights')]:
            path = snapshot / relative
            path.write_bytes(data)
            blob = git_blob_sha1(path)
            files.append(dict(relativePath=relative, bytes=len(data),
                sha256=hashlib.sha256(data).hexdigest(), upstreamLfsSha256=None,
                upstreamGitBlobId=blob, actualGitBlobId=blob,
                upstreamVerification='git_blob_sha1'))
        name, revision = MODELS['embedding']
        receipt = root / 'preparation.json'
        value = dict(status='downloaded_and_byte_verified_no_inference', models=[dict(
            kind='embedding', name=name, revision=revision, snapshot=str(snapshot),
            files=files, modelWeightLoads=0)])
        receipt.write_text(json.dumps(value), encoding='utf-8')
        return receipt, snapshot, value

    def export(self, receipt, output):
        return subject.materialize(receipt, hashlib.sha256(receipt.read_bytes()).hexdigest(), output)

    def rewrite(self, receipt, value):
        receipt.write_text(json.dumps(value), encoding='utf-8')

    def test_export_survives_source_cache_removed_and_retains_original(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, snapshot, _ = self.fixture(root)
            original = (snapshot / 'model.safetensors').read_bytes()
            result = self.export(receipt, root / 'export')
            self.assertEqual((snapshot / 'model.safetensors').read_bytes(), original)
            snapshot.rename(root / 'cache-unavailable')
            verified = subject.verify(root / 'export', result['manifestSha256'])
            self.assertEqual(verified['modelCount'], 1)
            self.assertEqual(verified['fileCount'], 2)
            self.assertFalse((root / 'export' / '.incomplete').exists())
            self.assertNotIn(str(snapshot), (root / 'export' / 'manifest.json').read_text('utf-8'))

    def test_linked_cache_bytes_are_exported_as_regular_files(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, snapshot, _ = self.fixture(root)
            weights = snapshot / 'model.safetensors'
            blob = root / 'external-blob'
            weights.rename(blob)
            try:
                weights.symlink_to(blob)
            except OSError as error:
                self.skipTest('OS does not permit symlink creation: ' + str(error))
            self.export(receipt, root / 'export')
            exported = root / 'export/models/embedding/model.safetensors'
            self.assertFalse(exported.is_symlink())
            self.assertEqual(exported.read_bytes(), blob.read_bytes())

    def test_changed_receipt_rejected_before_output_creation(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, _, _ = self.fixture(root)
            with self.assertRaisesRegex(ValueError, 'receipt SHA'):
                subject.materialize(receipt, '0' * 64, root / 'export')
            self.assertFalse((root / 'export').exists())

    def test_wrong_revision_and_duplicate_model_rejected(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, _, value = self.fixture(root)
            value['models'][0]['revision'] = '0' * 40
            self.rewrite(receipt, value)
            with self.assertRaisesRegex(ValueError, 'pinned model'):
                self.export(receipt, root / 'wrong')
            value['models'][0]['revision'] = MODELS['embedding'][1]
            value['models'].append(value['models'][0])
            self.rewrite(receipt, value)
            with self.assertRaisesRegex(ValueError, 'duplicate model'):
                self.export(receipt, root / 'duplicate')

    def test_path_escape_backslash_and_duplicate_files_rejected(self):
        for bad in ['../escape', '/absolute', 'C:/absolute', 'sub\\bad', 'a//b', 'a/./b']:
            with self.subTest(bad=bad), TemporaryDirectory() as tmp:
                root = Path(tmp)
                receipt, _, value = self.fixture(root)
                value['models'][0]['files'][0]['relativePath'] = bad
                self.rewrite(receipt, value)
                with self.assertRaises(ValueError):
                    self.export(receipt, root / 'export')
                self.assertFalse((root / 'export').exists())
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, _, value = self.fixture(root)
            value['models'][0]['files'].append(value['models'][0]['files'][0])
            self.rewrite(receipt, value)
            with self.assertRaisesRegex(ValueError, 'duplicate model file'):
                self.export(receipt, root / 'export')

    def test_wrong_source_bytes_and_missing_file_rejected(self):
        for mode in ['changed', 'missing']:
            with self.subTest(mode=mode), TemporaryDirectory() as tmp:
                root = Path(tmp)
                receipt, snapshot, _ = self.fixture(root)
                file = snapshot / 'config.json'
                if mode == 'changed':
                    file.write_bytes(b'changed')
                else:
                    file.unlink()
                with self.assertRaises((ValueError, FileNotFoundError)):
                    self.export(receipt, root / 'export')
                self.assertFalse((root / 'export').exists())

    def test_existing_destination_never_overwritten(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, _, _ = self.fixture(root)
            output = root / 'existing'
            output.mkdir()
            sentinel = output / 'old'
            sentinel.write_bytes(b'old complete generation')
            with self.assertRaises(FileExistsError):
                self.export(receipt, output)
            self.assertEqual(sentinel.read_bytes(), b'old complete generation')

    def test_change_during_copy_retains_incomplete_export_and_never_publishes(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, snapshot, _ = self.fixture(root)
            output = root / 'export'
            copier = subject._copy_checked

            def mutate_before_copy(source, destination, row):
                source.write_bytes(b'modified after preflight')
                return copier(source, destination, row)

            with patch.object(subject, '_copy_checked', side_effect=mutate_before_copy):
                with self.assertRaisesRegex(ValueError, 'changed while exporting'):
                    self.export(receipt, output)
            self.assertTrue((output / '.incomplete').exists())
            self.assertFalse((output / 'manifest.json').exists())
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                subject.verify(output, '0' * 64)
            self.assertTrue(snapshot.exists())

    def test_no_export_inside_snapshot(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, snapshot, _ = self.fixture(root)
            with self.assertRaisesRegex(ValueError, 'inside source snapshot'):
                self.export(receipt, snapshot / 'export')

    def test_paddle_required_set_and_original_model_weight_gate_preserved(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, _, value = self.fixture(root)
            value['models'][0]['kind'] = 'paddle-layout'
            value['models'][0]['name'], value['models'][0]['revision'] = MODELS['paddle-layout']
            self.rewrite(receipt, value)
            with self.assertRaisesRegex(ValueError, 'required repository file closure'):
                self.export(receipt, root / 'incomplete-role')
            value['models'][0]['kind'] = 'embedding'
            value['models'][0]['name'], value['models'][0]['revision'] = MODELS['embedding']
            value['models'][0]['files'] = value['models'][0]['files'][:1]
            self.rewrite(receipt, value)
            with self.assertRaisesRegex(ValueError, 'no pinned weight files'):
                self.export(receipt, root / 'missing-weights')

    def test_verify_rejects_changed_missing_extra_and_incomplete(self):
        for mode in ['changed', 'missing', 'extra', 'incomplete', 'manifest']:
            with self.subTest(mode=mode), TemporaryDirectory() as tmp:
                root = Path(tmp)
                receipt, _, _ = self.fixture(root)
                output = root / 'export'
                result = self.export(receipt, output)
                if mode == 'changed':
                    (output / 'models/embedding/config.json').write_bytes(b'changed')
                elif mode == 'missing':
                    (output / 'models/embedding/config.json').unlink()
                elif mode == 'extra':
                    (output / 'unlisted').write_bytes(b'extra')
                elif mode == 'incomplete':
                    (output / '.incomplete').write_bytes(b'not published')
                else:
                    (output / 'manifest.json').write_bytes(b'{}')
                with self.assertRaises((ValueError, FileNotFoundError)):
                    subject.verify(output, result['manifestSha256'])

    def test_unknown_receipt_file_metadata_does_not_leak_source_paths_into_export(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            receipt, snapshot, value = self.fixture(root)
            value['models'][0]['files'][0]['sourceSnapshot'] = str(snapshot)
            self.rewrite(receipt, value)
            output = root / 'export'
            result = self.export(receipt, output)
            manifest = (output / 'manifest.json').read_text('utf-8')
            self.assertNotIn('sourceSnapshot', manifest)
            self.assertNotIn(str(snapshot), manifest)
            subject.verify(output, result['manifestSha256'])

    def test_verify_rejects_inconsistent_scope_claims_even_with_new_manifest_digest(self):
        for key, value in [('runtimeDependencyClosureVerified', True),
                           ('targetRuntimeAccepted', True), ('modelWeightLoads', 17),
                           ('networkRequests', 19), ('modelWeightLoads', False),
                           ('preparationReceiptSha256', 'not-a-sha')]:
            with self.subTest(key=key, value=value), TemporaryDirectory() as tmp:
                root = Path(tmp)
                receipt, _, _ = self.fixture(root)
                output = root / 'export'
                self.export(receipt, output)
                path = output / 'manifest.json'
                manifest = json.loads(path.read_bytes())
                manifest[key] = value
                raw = json.dumps(manifest).encode('utf-8')
                path.write_bytes(raw)
                with self.assertRaises(ValueError):
                    subject.verify(output, hashlib.sha256(raw).hexdigest())


if __name__ == '__main__':
    unittest.main()
