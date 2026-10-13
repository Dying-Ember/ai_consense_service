"""Actual authored PDF/Java capture and declared dual outputs; no model calls."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _hash
from native_pdf_canonical_projection import produce_native_pdf
from native_pdf_projection_transport import (MAX_SNAPSHOT_BYTES, STORAGE_SHAPE, VERSION,
    hydrate_native_pdf_transport, publish_native_pdf_transport, validate_native_pdf_transport)
from native_projection_transport import validate_native_transport
from test_native_pdf_canonical_projection import fixture


class NativePdfProjectionTransportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.store = ArtifactStore(self.temporary.name)
        native, dual, manifest = fixture(self.store)
        self.internal = produce_native_pdf(native, dual, manifest, artifact_store=self.store)
        self.public = publish_native_pdf_transport(self.internal, artifact_store=self.store)

    def resolve(self, ref):
        value = json.loads(self.store.read_asset(ref['artifactDescriptor']))
        for step in ref['jsonPointer'].split('/')[1:]:
            step = step.replace('~1', '/').replace('~0', '~')
            value = value[int(step)] if isinstance(value, list) else value[step]
        self.assertEqual(_hash(value), ref['payloadSha256'])
        self.assertEqual(self.internal['source']['sourceSha256'], ref['sourceSha256'])
        return value

    def rejects_resealed(self, mutate):
        public = copy.deepcopy(self.public)
        mutate(public)
        public['projectionSha256'] = _hash({key: value for key, value in public.items() if key != 'projectionSha256'})
        with self.assertRaises(CanonicalProjectionError):
            validate_native_pdf_transport(public, artifact_store=self.store)

    def test_compact_wire_hydrates_complete_actual_inputs_without_mutation(self):
        original = copy.deepcopy(self.internal)
        self.assertEqual(self.internal, hydrate_native_pdf_transport(self.public, artifact_store=self.store))
        self.assertEqual(self.internal, original)
        self.assertNotIn('nativeObservationBundle', self.public)
        self.assertNotIn('dualParserBundle', self.public)
        self.assertNotIn('sourceReference', self.public)
        self.assertNotIn('regionReview', self.public['manifest'])
        for key, internal_key in [('nativeObservationRef', 'nativeObservationBundle'), ('dualParserBundleRef', 'dualParserBundle')]:
            self.assertEqual(json.loads(self.store.read_asset(self.public[key]['artifactDescriptor'])), self.internal[internal_key])
        self.assertEqual(self.resolve(self.public['manifest']['regionReviewRef']), self.internal['manifest']['regionReview'])
        self.assertEqual(self.resolve(self.public['unadoptedGlyphsRef']), self.internal['unadoptedGlyphs'])

    def test_literal_spans_geometry_and_separate_layout_survive_compaction(self):
        self.assertEqual('Visible fi cafe\nSecond page', self.public['chunks'][0]['content'])
        for actual, public in zip(self.internal['chunks'][0]['parts'], self.public['chunks'][0]['parts']):
            for field in ('text', 'startOffset', 'endOffset', 'sourcePosition', 'bbox', 'bboxConvention',
                          'visualLayoutParts', 'geometryStatus', 'nativeEffectiveVisibility', 'apTextAdoption'):
                self.assertEqual(actual[field], public[field])
            self.assertEqual('UTF16', public['offsetUnit'])
            self.assertEqual(actual['capturedGlyphIds'], self.resolve(public['nativeGlyphMembershipRef']))
            self.assertEqual(actual['nativeTextSpans'], self.resolve(public['nativeTextSpansRef']))
            self.assertEqual(actual, self.resolve(public['nativePdfRegionRef']))
            self.assertNotIn('capturedGlyphIds', public)
            self.assertNotIn('nativeTextSpans', public)
        self.assertFalse(self.public['humanConfirmed'])
        self.assertFalse(self.public['semanticScopeVerified'])
        self.assertEqual('unknown', self.public['wholeOriginalSourceCoverage'])
        self.assertEqual('not_published_by_native_pdf_transport', self.public['visualReturnStatus'])
        self.assertEqual(self.internal['canonical']['relations'], self.public['canonical']['relations'])
        self.assertEqual(self.internal['captureCompleteness'], self.public['captureCompleteness'])

    def test_public_identity_and_whole_unit_commitments_recompute(self):
        self.assertEqual(_hash({'projectionIdentity': self.public['projectionIdentity'], 'canonical': self.public['canonical']}),
                         self.public['sourceHash'])
        revision = _hash({'sourceHash': self.public['sourceHash'], 'projectionIdentity': self.public['projectionIdentity']})
        self.assertEqual(revision, self.public['projectionRevisionSha256'])
        self.assertNotEqual(self.internal['sourceHash'], self.public['sourceHash'])
        for chunk, commitment in zip(self.public['chunks'], self.public['canonical']['units']):
            body = {key: value for key, value in chunk.items() if key not in {'id', 'sourceHash', 'projectionRevisionSha256'}}
            self.assertEqual(_hash(body), commitment['chunkPreIdentitySha256'])
            self.assertEqual(_hash(chunk['parts']), commitment['partsSha256'])
            self.assertEqual(STORAGE_SHAPE, chunk['storageShape'])
            self.assertEqual(VERSION, chunk['metadataVersion'])

    def test_public_fixed_cap_cannot_be_raised_or_silently_drop_a_whole_unit(self):
        size = len(_bytes(self.public))
        self.assertLess(size, len(_bytes(self.internal)))
        self.assertLess(size, MAX_SNAPSHOT_BYTES)
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_pdf_public_snapshot_too_large'):
            publish_native_pdf_transport(self.internal, artifact_store=self.store, max_snapshot_bytes=size - 1)
        for cap in (0, True, MAX_SNAPSHOT_BYTES + 1):
            with self.subTest(cap=cap), self.assertRaisesRegex(CanonicalProjectionError, 'native_pdf_public_snapshot_limit_invalid'):
                validate_native_pdf_transport(self.public, artifact_store=self.store, max_snapshot_bytes=cap)
        self.rejects_resealed(lambda p: p['chunks'][0]['parts'].pop())
        self.rejects_resealed(lambda p: p['chunks'].clear())

    def test_each_actual_capture_family_tamper_rejects_after_prior_green_read(self):
        descriptors = [('internal', self.public['internalProjectionRef']['artifactDescriptor']),
                       ('nativeBundle', self.public['nativeObservationRef']['artifactDescriptor']),
                       ('dualBundle', self.public['dualParserBundleRef']['artifactDescriptor']),
                       ('adoption', self.public['adoptionManifestRef']),
                       ('source', self.public['nativeCaptureRefs']['sourcePackage']),
                       ('raw', self.public['nativeCaptureRefs']['rawObservation']),
                       ('code', self.public['transportCodeRef']),
                       ('reference', self.public['sourceReferenceRef']['artifactDescriptor']),
                       ('raster', self.public['sourceReferenceRef']['pageImages'][0])]
        descriptors += [('observer:' + key, value) for key, value in self.public['nativeCaptureRefs']['observerSources'].items()]
        descriptors += [('AP:' + str(index), value['artifactDescriptor'])
                        for index, value in enumerate(self.public['nativeCaptureRefs']['appearanceArtifacts'])]
        for family, descriptor in descriptors:
            with self.subTest(family=family):
                path = Path(self.temporary.name) / descriptor['relativePath']
                raw = path.read_bytes()
                try:
                    self.assertEqual(self.public, validate_native_pdf_transport(self.public, artifact_store=self.store))
                    path.write_bytes(b'actual retained evidence changed')
                    with self.assertRaises(CanonicalProjectionError):
                        validate_native_pdf_transport(self.public, artifact_store=self.store)
                finally:
                    path.write_bytes(raw)

    def test_reads_never_recreate_missing_cas_or_capture_new_bytes(self):
        with patch.object(self.store, 'put_bytes', side_effect=AssertionError('validation must not capture')):
            self.assertEqual(self.public, validate_native_pdf_transport(self.public, artifact_store=self.store))
        path = Path(self.temporary.name) / self.public['internalProjectionRef']['artifactDescriptor']['relativePath']
        path.unlink()
        with self.assertRaises(CanonicalProjectionError):
            validate_native_pdf_transport(self.public, artifact_store=self.store)
        self.assertFalse(path.exists())

    def test_resealed_payload_positions_ref_pointers_and_unknown_upgrades_refused(self):
        changes = [lambda p: p['chunks'][0].update(content='OCR or gold repaired text'),
                   lambda p: p['chunks'][0]['parts'][0].update(endOffset=1),
                   lambda p: p['chunks'][0]['parts'][0]['sourcePosition'].update(physicalPageNo=2),
                   lambda p: p['chunks'][0]['parts'][0]['nativeTextSpansRef'].update(jsonPointer='/wrong/channel'),
                   lambda p: p['chunks'][0]['parts'][0]['nativeGlyphMembershipRef'].update(payloadSha256='f' * 64),
                   lambda p: p['canonical']['relations'].clear(),
                   lambda p: p['manifest']['scope'].update(requiredPhysicalPages=[1]),
                   lambda p: p.update(humanConfirmed=True),
                   lambda p: p.update(semanticScopeVerified=True),
                   lambda p: p.update(wholeOriginalSourceCoverage='complete'),
                   lambda p: p['chunks'][0]['parts'][0].update(nativeEffectiveVisibility='verified'),
                   lambda p: p['chunks'][0]['parts'][0].update(apTextAdoption='adopted')]
        for index, change in enumerate(changes):
            with self.subTest(change=index): self.rejects_resealed(change)

    def test_internal_or_docx_or_scanned_shape_cannot_enter_pdf_wire(self):
        for field, value in [('schema', 'consense.canonical-projection.v1'), ('producerKind', 'native_docx'),
                             ('storageShape', self.internal['storageShape'])]:
            with self.subTest(field=field): self.rejects_resealed(lambda p: p.update({field: value}))
        with self.assertRaises(CanonicalProjectionError):
            validate_native_pdf_transport(self.internal, artifact_store=self.store)
        with self.assertRaises(CanonicalProjectionError):
            validate_native_transport(self.public, artifact_store=self.store)

    def test_transport_descriptor_cannot_reseal_an_alternate_identity_scope_or_extra_fields(self):
        self.rejects_resealed(lambda p: p['transportCodeRef'].update(identityScope='unrelated_scope'))
        self.rejects_resealed(lambda p: p['transportCodeRef'].update(unverifiedAnnotation=True))


if __name__ == '__main__': unittest.main()
