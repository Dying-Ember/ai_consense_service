"""Authored artifact provenance boundaries; no dependency/model constructors."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import verify_retrieval_offline as audit


class ArtifactProvenance(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.wheels = self.root/'wheels'; self.wheels.mkdir()
        self.wheel = self.wheels/'sample_pkg-1.0-py3-none-any.whl'
        with zipfile.ZipFile(self.wheel, 'w') as archive:
            archive.writestr('sample_pkg-1.0.dist-info/METADATA', 'Name: sample-pkg\nVersion: 1.0\nLicense-Expression: MIT\n')
            archive.writestr('sample_pkg-1.0.dist-info/licenses/LICENSE', 'authored test license')
        self.sha = audit.descriptor(self.wheel)['sha256']
        self.lock = self.root/'lock.txt'; self.lock.write_text('sample-pkg==1.0 \\\n    --hash=sha256:'+self.sha+'\n')
        self.source = {'install': [{'metadata': {'name': 'sample-pkg', 'version': '1.0'}, 'download_info': {
            'url': 'https://files.pythonhosted.org/sample.whl', 'archive_info': {'hashes': {'sha256': self.sha}}}}]}
        self.report = self.root/'report.json'

    def tearDown(self):
        self.tmp.cleanup()

    def inventory(self):
        self.report.write_text(json.dumps(self.source))
        return audit.wheel_inventory(self.lock, self.report, self.wheels)

    def test_exact_hash_version_source_and_embedded_license(self):
        result = self.inventory()
        self.assertEqual(result[0]['file']['sha256'], self.sha)
        self.assertEqual(result[0]['licenseExpression'], 'MIT')
        self.assertEqual(len(result[0]['embeddedLicenseFiles']), 1)

    def test_changed_wheel_rejected(self):
        self.wheel.write_bytes(b'changed bytes')
        with self.assertRaises(zipfile.BadZipFile):
            self.inventory()

    def test_forged_download_source_hash_rejected(self):
        self.source['install'][0]['download_info']['archive_info']['hashes']['sha256'] = '0'*64
        with self.assertRaises(ValueError):
            self.inventory()

    def test_hashes_are_bound_to_their_package_stanza(self):
        second = self.wheels/'second-1.0-py3-none-any.whl'
        with zipfile.ZipFile(second, 'w') as archive:
            archive.writestr('second-1.0.dist-info/METADATA', 'Name: second\nVersion: 1.0\n')
        second_sha = audit.descriptor(second)['sha256']
        self.source['install'].append({'metadata': {'name': 'second', 'version': '1.0'}, 'download_info': {
            'url': 'https://files.pythonhosted.org/second.whl', 'archive_info': {'hashes': {'sha256': second_sha}}}})
        self.lock.write_text('sample-pkg==1.0 \\\n    --hash=sha256:'+self.sha+'\nsecond==1.0 --hash=sha256:'+second_sha+'\n')
        self.assertEqual(len(self.inventory()), 2)
        self.lock.write_text('sample-pkg==1.0 \\\n    --hash=sha256:'+second_sha+'\nsecond==1.0 --hash=sha256:'+self.sha+'\n')
        with self.assertRaisesRegex(ValueError, 'locked hash'):
            self.inventory()

    def test_duplicate_normalized_lock_entry_rejected(self):
        for version in ('1.0', '2.0'):
            self.lock.write_text('SAMPLE_pkg=='+version+' --hash=sha256:'+self.sha+'\n'
                                 'sample-pkg==1.0 --hash=sha256:'+self.sha+'\n')
            with self.assertRaisesRegex(ValueError, 'Duplicate normalized lock package'):
                self.inventory()

    def test_duplicate_normalized_source_entry_rejected(self):
        duplicate = json.loads(json.dumps(self.source['install'][0]))
        duplicate['metadata']['name'] = 'SAMPLE_pkg'
        self.source['install'].append(duplicate)
        with self.assertRaisesRegex(ValueError, 'Duplicate normalized source package'):
            self.inventory()

    def test_source_report_version_must_match_lock(self):
        self.source['install'][0]['metadata']['version'] = '2.0'
        with self.assertRaisesRegex(ValueError, 'Source package/version differs from lock'):
            self.inventory()

    def test_missing_package_or_wrong_locked_hash_rejected(self):
        self.lock.write_text('sample-pkg==1.0 --hash=sha256:'+'0'*64)
        with self.assertRaises(ValueError):
            self.inventory()
        self.source['install'] = []
        with self.assertRaises(ValueError):
            self.inventory()

    def test_unknown_or_credentialed_source_rejected(self):
        for url in ('https://unapproved.example/sample.whl', 'https://user:secret@files.pythonhosted.org/sample.whl'):
            self.source['install'][0]['download_info']['url'] = url
            with self.assertRaises(ValueError):
                self.inventory()

    def test_extra_wheel_rejected(self):
        (self.wheels/'duplicate.whl').write_bytes(self.wheel.read_bytes())
        with self.assertRaises(ValueError):
            self.inventory()

    def test_bundled_model_weight_rejected_before_import(self):
        with zipfile.ZipFile(self.wheel, 'a') as archive:
            archive.writestr('sample_pkg/models/default.onnx', b'authored fake weight')
        with self.assertRaisesRegex(ValueError, 'must not bundle model weights'):
            self.inventory()

    def test_vendored_package_metadata_is_not_the_wheel_identity(self):
        with zipfile.ZipFile(self.wheel, 'a') as archive:
            archive.writestr('sample_pkg/vendor/other-2.0.dist-info/METADATA', 'Name: other\nVersion: 2.0\n')
        self.sha = audit.descriptor(self.wheel)['sha256']
        self.lock.write_text('sample-pkg==1.0 --hash=sha256:'+self.sha)
        self.source['install'][0]['download_info']['archive_info']['hashes']['sha256'] = self.sha
        self.assertEqual(self.inventory()[0]['name'], 'sample-pkg')

    def test_python_startup_pth_is_distinct_from_binary_checkpoint(self):
        with zipfile.ZipFile(self.wheel, 'a') as archive:
            archive.writestr('_namespace_redirector.pth', '# authored namespace bootstrap\nimport authored_namespace\n')
        with zipfile.ZipFile(self.wheel) as archive:
            self.assertEqual(audit.bundled_weights(archive), [])
        with zipfile.ZipFile(self.wheel, 'a') as archive:
            archive.writestr('serialized.pth', b'PK\x03\x04authored fake checkpoint')
        with zipfile.ZipFile(self.wheel) as archive:
            self.assertEqual(audit.bundled_weights(archive), ['serialized.pth'])


if __name__ == '__main__':
    unittest.main()
