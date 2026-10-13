"""Whole-document registry checks with actual authored CAS; no neural model."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError
from native_canonical_projection import produce_native
from native_collection_registry import NativeCollectionSourceRegistry
from native_projection_collection import publish_native_collection
from oer_source_registry import SourceRegistryError
from test_native_canonical_projection import fixture


class NativeCollectionRegistryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = ArtifactStore(Path(self.temp.name) / 'artifacts')
        self.registry = NativeCollectionSourceRegistry(Path(self.temp.name) / 'registry', self.store)
        bundle, adoption = fixture(self.store)
        self.internal = produce_native(bundle, adoption, artifact_store=self.store)
        self.root = publish_native_collection(self.internal, artifact_store=self.store, max_batch_bytes=60000)
        self.address = self.root['projectionSha256']

    def test_one_registration_covers_three_ordered_batches_and_one_global_identity(self):
        receipt = self.registry.publish(self.root)
        self.assertEqual((3, 3), (receipt['chunks'], receipt['batches']))
        self.assertEqual([self.address + '.json'], [path.name for path in self.registry.root.glob('*.json')])
        for module in ('vetting', 'advisory'):
            wire, chunks = self.registry.wire(self.address, module=module)
            self.assertEqual(self.root, wire)
            self.assertNotIn('chunks', wire)
            batches = [self.registry.batch(self.address, i, module=module) for i in range(3)]
            self.assertEqual(chunks, [chunk for batch in batches for chunk in batch['chunks']])
            self.assertEqual(1, self.registry.validate_native_chunks(chunks))
            self.assertEqual({self.root['sourceHash']}, {chunk['sourceHash'] for chunk in chunks})
        with self.assertRaises(SourceRegistryError):
            self.registry.batch(self.address, 0, module='drafting')

    def test_batch_cannot_publish_as_a_document_and_partial_or_reordered_index_rejects(self):
        self.registry.publish(self.root)
        chunks = self.registry.chunks(self.address)
        batch = self.registry.batch(self.address, 0)
        with self.assertRaises((SourceRegistryError, ValueError)):
            self.registry.publish(batch)
        for altered in (chunks[1:], list(reversed(chunks)), [{**chunks[0], 'content': 'changed'}] + chunks[1:]):
            with self.assertRaisesRegex(SourceRegistryError, 'global source order'):
                self.registry.validate_native_chunks(altered)

    def test_actual_missing_batch_invalidates_root_all_batches_and_index_without_repair(self):
        self.registry.publish(self.root)
        chunks = self.registry.chunks(self.address)
        descriptor = self.root['batches'][2]['artifactDescriptor']
        path = self.store.root / descriptor['relativePath']
        path.unlink()
        for operation in (lambda: self.registry.wire(self.address), lambda: self.registry.batch(self.address, 0),
                          lambda: self.registry.validate_native_chunks(chunks)):
            with self.assertRaises((ValueError, OSError)):
                operation()
            self.assertFalse(path.exists())

    def test_locator_drift_missing_and_root_address_mismatch_cannot_retarget(self):
        self.registry.publish(self.root)
        chunks = self.registry.chunks(self.address)
        alias = self.registry._revision_path(self.root['projectionRevisionSha256'])
        alias.unlink()
        altered = copy.deepcopy(self.root)
        altered['projectionSha256'] = 'b' * 64
        alias.write_text(json.dumps(altered), encoding='utf-8')
        with self.assertRaises(SourceRegistryError):
            self.registry.validate_native_chunks(chunks)
        alias.unlink()
        with self.assertRaises(SourceRegistryError):
            self.registry.validate_native_chunks(chunks)

    def test_original_cas_corruption_after_valid_read_withholds_memory_index(self):
        self.registry.publish(self.root)
        chunks = self.registry.chunks(self.address)
        descriptor = self.internal['nativeObservationBundle']['sourcePackage']
        (self.store.root / descriptor['relativePath']).write_bytes(b'not-the-authored-original')
        with self.assertRaises(SourceRegistryError):
            self.registry.validate_native_chunks(chunks)
        with self.assertRaises(CanonicalProjectionError):
            self.registry.load(self.address)


if __name__ == '__main__':
    unittest.main()
