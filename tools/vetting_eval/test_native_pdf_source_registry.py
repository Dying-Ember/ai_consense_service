"""Dedicated native PDF registry contracts, with authored actual CAS only."""
import copy
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore
from canonical_projection import project_canonical
from native_canonical_projection import produce_native
from native_projection_transport import publish_native_transport
from native_pdf_canonical_projection import produce_native_pdf
from native_pdf_projection_transport import publish_native_pdf_transport
from native_pdf_source_registry import NativePdfCanonicalSourceRegistry
from test_native_pdf_canonical_projection import fixture
from test_native_canonical_projection import fixture as docx_fixture
from test_oer_source_registry import asset_fixture


class NativePdfRegistryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.store = ArtifactStore(Path(self.temporary.name) / 'artifacts')
        self.registry = NativePdfCanonicalSourceRegistry(Path(self.temporary.name) / 'canonical', self.store)
        native, dual, adoption = fixture(self.store)
        self.internal = produce_native_pdf(native, dual, adoption, artifact_store=self.store)
        self.public = publish_native_pdf_transport(self.internal, artifact_store=self.store)

    def test_registered_pdf_keeps_exact_parents_and_shared_module_unknowns(self):
        with self.assertRaises(ValueError): self.registry.validate_native_chunks(self.public['chunks'])
        receipt = self.registry.publish(self.public)
        self.assertFalse(receipt['qualityVerifiedByRegistry'])
        self.assertEqual(self.public, self.registry.load(self.public['projectionSha256']))
        self.assertEqual(1, self.registry.validate_native_chunks(self.public['chunks']))
        self.assertEqual(self.public['chunks'], self.registry.chunks(self.public['projectionSha256'], module='advisory'))
        with self.assertRaises(ValueError): self.registry.chunks(self.public['projectionSha256'], module='drafting')
        changed = copy.deepcopy(self.public['chunks']); changed[0]['parts'][0]['text'] = 'invented'
        with self.assertRaises(ValueError): self.registry.validate_native_chunks(changed)

    def test_pdf_internal_or_mixed_producer_markers_cannot_take_other_schema_gate(self):
        for snapshot in [self.internal, {**self.public, 'producerKind': 'native_docx'},
                         {**self.public, 'schema': 'consense.canonical-projection.v1', 'producerKind': 'scanned_pdf'}]:
            with self.subTest(schema=snapshot['schema']), self.assertRaises(ValueError): self.registry.publish(snapshot)
        self.assertFalse(self.registry.root.exists())

    def test_each_pdf_load_and_chunk_validation_cold_reads_actual_cas_and_locator(self):
        self.registry.publish(self.public)
        descriptor = self.public['nativeCaptureRefs']['rawObservation']
        path = self.store.root / descriptor['relativePath']; raw = path.read_bytes(); path.write_bytes(b'bad raw')
        try:
            with self.assertRaises(ValueError): self.registry.load(self.public['projectionSha256'])
            with self.assertRaises(ValueError): self.registry.validate_native_chunks(self.public['chunks'])
            self.assertEqual(b'bad raw', path.read_bytes())
        finally: path.write_bytes(raw)
        self.registry._pdf_revision_path(self.public['projectionRevisionSha256']).unlink()
        with self.assertRaises(ValueError): self.registry.validate_native_chunks(self.public['chunks'])

    def test_legacy_scan_and_actual_cas_docx_keep_their_existing_validators(self):
        # The cold source gate reads the declared five-page PDF, so the old
        # authored wire string is no longer a valid source-identity fixture.
        from io import BytesIO
        from pypdf import PdfWriter
        writer = PdfWriter()
        for _ in range(5): writer.add_blank_page(width=300, height=600)
        source = BytesIO(); writer.write(source)
        bundle, adoption, _ = asset_fixture(self.store, source_bytes=source.getvalue())
        scanned = project_canonical(bundle, adoption, artifact_store=self.store)
        bundle, adoption = docx_fixture(self.store)
        docx = publish_native_transport(produce_native(bundle, adoption, artifact_store=self.store), artifact_store=self.store)
        for public in (scanned, docx, self.public):
            self.registry.publish(public)
            self.assertEqual(public, self.registry.load(public['projectionSha256']))
        self.assertEqual(2, self.registry.validate_native_chunks(docx['chunks'] + self.public['chunks'] + scanned['chunks']))


if __name__ == '__main__': unittest.main()
