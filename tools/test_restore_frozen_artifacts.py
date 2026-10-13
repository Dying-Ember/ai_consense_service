"""Authored archive-boundary checks; no database, model, service or network calls."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

import restore_frozen_artifacts as delivery


class RestoreFrozenArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.manifest = self.root / 'manifest.json'
        self.destination = self.root / 'restored'

    def package(self, name='models/publication/snapshot/model.bin', content=b'frozen weights', link=False):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w:gz') as archive:
            member = tarfile.TarInfo(name)
            member.size = len(content)
            if link:
                member.type = tarfile.SYMTYPE
                member.linkname = '../../outside'
                member.size = 0
            archive.addfile(member, None if link else io.BytesIO(content))
        data = buffer.getvalue()
        parts = []
        for ordinal, part in enumerate((data[:53], data[53:]), 1):
            part_name = 'frozen.tar.gz.part%03d' % ordinal
            (self.root / part_name).write_bytes(part)
            parts.append({'name': part_name, 'bytes': len(part),
                          'sha256': hashlib.sha256(part).hexdigest()})
        manifest = {'schema': 'consense.frozen-artifacts/v1',
                    'archive': {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                                'parts': parts},
                    'files': [{'path': name, 'bytes': len(content),
                               'sha256': hashlib.sha256(content).hexdigest()}],
                    'pathMap': {'D:/original/model.bin': name}}
        self.manifest.write_text(json.dumps(manifest), encoding='utf-8')
        return manifest

    def test_verify_then_restore_preserves_bytes_and_source_path_mapping(self):
        self.package()
        verified = delivery.restore(self.manifest, self.root, expected_manifest_sha256=
                                    hashlib.sha256(self.manifest.read_bytes()).hexdigest())
        self.assertEqual(verified['verifiedFiles'], 1)
        self.assertFalse(self.destination.exists())
        restored = delivery.restore(self.manifest, self.root, self.destination)
        self.assertEqual((self.destination / 'models/publication/snapshot/model.bin').read_bytes(), b'frozen weights')
        self.assertFalse(restored['runtimeStarted'])
        self.assertFalse(restored['absoluteSourcePathsRewritten'])
        self.assertEqual(json.loads((self.destination / 'path-map.json').read_text(encoding='utf-8')),
                         {'D:/original/model.bin': str(self.destination / 'models/publication/snapshot/model.bin')})
        self.assertEqual(json.loads((self.destination / 'RESTORED.json').read_text()), restored)

    def test_damaged_part_rejected_before_destination_is_created(self):
        self.package()
        (self.root / 'frozen.tar.gz.part001').write_bytes(b'corrupt')
        with self.assertRaises(ValueError):
            delivery.restore(self.manifest, self.root, self.destination)
        self.assertFalse(self.destination.exists())

    def test_unsafe_tar_members_and_links_rejected_before_writing(self):
        for name, link in (('.', False), ('../escape', False), ('/absolute', False), ('C:/outside', False),
                           ('models/CON.bin', False), ('PATH-MAP.JSON', False),
                           ('RESTORED.JSON', False), ('models/file', True)):
            with self.subTest(name=name, link=link):
                self.package(name, link=link)
                with self.assertRaises(ValueError):
                    delivery.restore(self.manifest, self.root, self.destination)
                self.assertFalse(self.destination.exists())

    def test_existing_destination_is_untouched(self):
        self.package()
        self.destination.mkdir()
        original = self.destination / 'user-file'
        original.write_bytes(b'keep')
        with self.assertRaises(ValueError):
            delivery.restore(self.manifest, self.root, self.destination)
        self.assertEqual(original.read_bytes(), b'keep')
        self.assertEqual(list(self.destination.iterdir()), [original])

    def test_changed_manifest_and_member_hash_are_rejected(self):
        manifest = self.package()
        with self.assertRaises(ValueError):
            delivery.restore(self.manifest, self.root, self.destination, '0' * 64)
        manifest['files'][0]['sha256'] = '0' * 64
        self.manifest.write_text(json.dumps(manifest), encoding='utf-8')
        with self.assertRaises(ValueError):
            delivery.restore(self.manifest, self.root, self.destination)
        self.assertFalse(self.destination.exists())

    def test_reordered_parts_are_rejected(self):
        manifest = self.package()
        manifest['archive']['parts'].reverse()
        self.manifest.write_text(json.dumps(manifest), encoding='utf-8')
        with self.assertRaises(ValueError):
            delivery.restore(self.manifest, self.root, self.destination)
        self.assertFalse(self.destination.exists())


if __name__ == '__main__':
    unittest.main()
