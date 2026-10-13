"""Mixed registry reads actual authored CAS; no models or running service changes."""
import copy
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, project_canonical
from native_canonical_projection import produce_native
from native_projection_transport import publish_native_transport
from native_source_registry import NativeCanonicalSourceRegistry
from oer_source_registry import SourceRegistryError
from test_native_canonical_projection import fixture
from test_oer_source_registry import asset_fixture


class NativeRegistryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = ArtifactStore(Path(self.temp.name) / 'artifacts')
        self.registry = NativeCanonicalSourceRegistry(Path(self.temp.name) / 'canonical', self.store)
        bundle, manifest = fixture(self.store)
        self.internal = produce_native(bundle, manifest, artifact_store=self.store)
        self.public = publish_native_transport(self.internal, artifact_store=self.store)

    def test_publish_load_and_shared_modules_retain_exact_native_blank_group_and_unknown(self):
        receipt = self.registry.publish(self.public)
        self.assertEqual(3, receipt['chunks'])
        self.assertFalse(receipt['qualityVerifiedByRegistry'])
        self.assertEqual(self.public, self.registry.load(self.public['projectionSha256']))
        for module in ('vetting', 'advisory'):
            chunks = self.registry.chunks(self.public['projectionSha256'], module=module)
            self.assertEqual(self.public['chunks'], chunks)
            self.assertEqual(3, sum(part['text'] == '' for part in chunks[1]['parts']))
        with self.assertRaisesRegex(SourceRegistryError, 'different module'):
            self.registry.chunks(self.public['projectionSha256'], module='drafting')

    def test_each_read_rejects_cas_missing_or_corrupt_and_does_not_repair(self):
        self.registry.publish(self.public)
        descriptor = self.public['chunks'][1]['tableGroup']['nativeTablesRef']['artifactDescriptor']
        path = self.store.root / descriptor['relativePath']
        original = path.read_bytes()
        for value in (b'corrupt actual table bytes', None):
            if value is None:
                path.unlink()
            else:
                path.write_bytes(value)
            with self.assertRaises(CanonicalProjectionError):
                self.registry.load(self.public['projectionSha256'])
            self.assertEqual(value, path.read_bytes() if path.exists() else None)
            path.write_bytes(original)
        self.assertEqual(self.public, self.registry.load(self.public['projectionSha256']))

    def test_internal_view_and_unknown_schema_or_producer_are_not_public_snapshots(self):
        rejected = [self.internal, {**self.public, 'schema': 'consense.canonical-projection.v3'},
                    {**self.public, 'producerKind': 'scanned_pdf'}]
        for snapshot in rejected:
            with self.subTest(schema=snapshot['schema']), self.assertRaises(SourceRegistryError):
                self.registry.publish(snapshot)
        self.assertFalse(self.registry.root.exists())

    def test_scan_v1_still_uses_unchanged_whole_dual_parser_projection(self):
        bundle, manifest, expected = asset_fixture(self.store)
        scanned = project_canonical(bundle, manifest, artifact_store=self.store)
        self.registry.publish(scanned)
        self.assertEqual(scanned, self.registry.load(scanned['projectionSha256']))
        asset = scanned['assetRefs'][0]
        returned, _, _ = self.registry.asset(scanned['projectionSha256'], scanned['source']['sourceSha256'], asset['assetId'])
        self.assertEqual(expected[asset['artifactName']], returned)
        bad = copy.deepcopy(scanned)
        bad['producerKind'] = 'native_docx'
        with self.assertRaisesRegex(SourceRegistryError, 'cannot bypass'):
            self.registry.publish(bad)


if __name__ == '__main__':
    unittest.main()
