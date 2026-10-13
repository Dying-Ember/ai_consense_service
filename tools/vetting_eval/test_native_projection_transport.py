"""Compact wire admission tests using actual Java authored capture, no model calls."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _hash
from native_canonical_projection import produce_native
from native_projection_transport import (MAX_SNAPSHOT_BYTES, STORAGE_SHAPE, VERSION,
    hydrate_native_transport, publish_native_transport, validate_native_transport)
from test_native_canonical_projection import fixture


class NativeProjectionTransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = ArtifactStore(self.temp.name)
        bundle, adoption = fixture(self.store)
        self.internal = produce_native(bundle, adoption, artifact_store=self.store)
        self.public = publish_native_transport(self.internal, artifact_store=self.store)

    def reseal(self, snapshot):
        snapshot['projectionSha256'] = _hash({key: value for key, value in snapshot.items() if key != 'projectionSha256'})

    def rejects(self, snapshot, code='native_public_derived_payload_mismatch'):
        self.reseal(snapshot)
        with self.assertRaisesRegex(CanonicalProjectionError, code):
            validate_native_transport(snapshot, artifact_store=self.store)

    def test_actual_internal_and_bundle_capture_roundtrip_full_membership(self):
        before = copy.deepcopy(self.internal)
        self.assertEqual(hydrate_native_transport(self.public, artifact_store=self.store), self.internal)
        self.assertEqual(self.internal, before)
        self.assertEqual(json.loads(self.store.read_asset(self.public['internalProjectionRef']['artifactDescriptor'])), self.internal)
        self.assertEqual(json.loads(self.store.read_asset(self.public['nativeObservationRef']['artifactDescriptor'])),
                         self.internal['nativeObservationBundle'])
        self.assertEqual(self.public['nativeCaptureRefs']['rawParts'], self.internal['nativeObservationBundle']['rawParts'])

    def test_compact_preserves_literal_utf16_unknown_and_edit_channels_without_raw_tokens_inline(self):
        first = self.public['chunks'][0]['parts'][0]
        original = self.internal['chunks'][0]['parts'][0]
        self.assertEqual(first['text'], original['text'])
        self.assertEqual(first['endOffset'], original['endOffset'])
        self.assertEqual(first['sourcePosition'], original['sourcePosition'])
        self.assertEqual(first['nativeTextChannels'], original['nativeObservation']['textChannels'])
        self.assertEqual(first['nativeEditingState'], original['nativeObservation']['editingState'])
        self.assertNotIn('nativeObservation', first)
        self.assertNotIn('tokens', first)
        self.assertEqual(first['nativeNodeRef']['payloadSha256'], _hash(original['nativeObservation']))
        self.assertEqual(first['nativeTokensRef']['payloadSha256'], _hash(original['nativeObservation']['tokens']))
        self.assertEqual(self.public['unknowns'], self.internal['unknowns'])
        self.assertFalse(self.public['humanConfirmed'])
        self.assertFalse(self.public['semanticScopeVerified'])

    def test_complete_table_topology_and_raw_inventory_actual_bytes_preserved(self):
        group = self.public['chunks'][1]['tableGroup']
        inventory = json.loads(self.store.read_asset(group['nativeTablesRef']['artifactDescriptor']))
        original = self.internal['chunks'][1]['tableGroup']['nativeTables']
        self.assertEqual(inventory, original)
        outer, inner = group['nativeTableTopology']
        self.assertEqual(outer['rows'][0]['cells'][0]['nestedTableIds'], [inner['tableId']])
        self.assertEqual(outer['rows'][0]['gridBeforeRaw']['val'], 'invalid')
        self.assertEqual(outer['rows'][0]['cells'][0]['gridSpanRaw']['val'], '2')
        self.assertNotIn('rawNode', outer['rows'][0]['cells'][0]['gridSpanRaw'])
        self.assertIn('rawNodeRef', outer['rows'][0]['cells'][0]['gridSpanRaw'])
        self.assertEqual(outer['rows'][1]['cells'][0]['vMergeRaw']['val'], None)
        self.assertEqual(outer['rows'][1]['cells'][0]['childParagraphIds'], original[0]['rows'][1]['cells'][0]['childParagraphIds'])
        self.assertEqual(group['nativeParagraphIds'], self.internal['manifest']['tableGroups'][0]['nativeParagraphIds'])
        self.assertNotIn('nativeTableNodes', self.public['chunks'][1]['adoption'])

    def test_public_source_revision_and_ids_independently_recompute(self):
        self.assertEqual(self.public['sourceHash'], _hash({'projectionIdentity': self.public['projectionIdentity'],
                                                         'canonical': self.public['canonical']}))
        revision = _hash({'sourceHash': self.public['sourceHash'], 'projectionIdentity': self.public['projectionIdentity']})
        self.assertEqual(self.public['projectionRevisionSha256'], revision)
        self.assertNotEqual(self.public['sourceHash'], self.internal['sourceHash'])
        for chunk, unit in zip(self.public['chunks'], self.public['canonical']['units']):
            body = {key: value for key, value in chunk.items() if key not in {'id', 'sourceHash', 'projectionRevisionSha256'}}
            self.assertEqual(unit['chunkPreIdentitySha256'], _hash(body))
            self.assertEqual(unit['partsSha256'], _hash(chunk['parts']))
            self.assertEqual(chunk['id'], 'canonical:' + _hash({'revision': revision, 'unitId': unit['unitId']}))
            self.assertEqual(chunk['storageShape'], STORAGE_SHAPE)
            self.assertEqual(chunk['projectionVersion'], VERSION)

    def test_public_snapshot_within_fixed_cap_and_smaller_than_internal(self):
        public_size, internal_size = len(_bytes(self.public)), len(_bytes(self.internal))
        self.assertLess(public_size, MAX_SNAPSHOT_BYTES)
        self.assertLess(public_size, internal_size)
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_public_snapshot_too_large'):
            publish_native_transport(self.internal, artifact_store=self.store, max_snapshot_bytes=public_size - 1)
        for cap in (0, True, MAX_SNAPSHOT_BYTES + 1):
            with self.assertRaisesRegex(CanonicalProjectionError, 'native_public_snapshot_limit_invalid'):
                validate_native_transport(self.public, artifact_store=self.store, max_snapshot_bytes=cap)

    def test_every_actual_cas_family_mutation_refuses_read(self):
        descriptors = [('internal', self.public['internalProjectionRef']['artifactDescriptor']),
                       ('bundle', self.public['nativeObservationRef']['artifactDescriptor']),
                       ('source', self.public['nativeCaptureRefs']['sourcePackage']),
                       ('adapter', self.public['nativeCaptureRefs']['adapterSource']),
                       ('raw', self.public['nativeCaptureRefs']['rawObservation']),
                       ('raw_part', self.public['nativeCaptureRefs']['rawParts'][0]['artifactDescriptor']),
                       ('table', self.public['chunks'][1]['tableGroup']['nativeTablesRef']['artifactDescriptor']),
                       ('table_nodes', self.public['chunks'][1]['adoption']['nativeTableNodesRef']['artifactDescriptor']),
                       ('reference', self.public['sourceReferenceRef']['artifactDescriptor']),
                       ('transport_code', self.public['transportCodeRef'])]
        for family, descriptor in descriptors:
            with self.subTest(family=family):
                path = Path(self.temp.name) / descriptor['relativePath']
                raw = path.read_bytes()
                try:
                    path.write_bytes(b'changed captured bytes')
                    with self.assertRaises(CanonicalProjectionError):
                        validate_native_transport(self.public, artifact_store=self.store)
                finally:
                    path.write_bytes(raw)

    def test_missing_bytes_not_repaired_during_read(self):
        descriptor = self.public['chunks'][1]['tableGroup']['nativeTablesRef']['artifactDescriptor']
        path = Path(self.temp.name) / descriptor['relativePath']
        path.unlink()
        with self.assertRaises(CanonicalProjectionError):
            validate_native_transport(self.public, artifact_store=self.store)
        self.assertFalse(path.exists())

    def test_every_read_revalidates_actual_bytes_without_capturing_or_caching(self):
        with patch.object(self.store, 'put_bytes', side_effect=AssertionError('read must not capture')):
            self.assertEqual(validate_native_transport(self.public, artifact_store=self.store), self.public)
        descriptor = self.public['nativeCaptureRefs']['rawParts'][0]['artifactDescriptor']
        path = Path(self.temp.name) / descriptor['relativePath']
        path.write_bytes(b'actual retained bytes changed after the preceding successful read')
        with self.assertRaises(CanonicalProjectionError):
            validate_native_transport(self.public, artifact_store=self.store)

    def test_counts_or_hash_only_cannot_replace_complete_table(self):
        changed = copy.deepcopy(self.public)
        changed['chunks'][1]['tableGroup']['nativeTableTopology'][0]['rows'][0]['cells'].pop()
        self.rejects(changed)
        changed = copy.deepcopy(self.public)
        changed['chunks'][1]['tableGroup']['nativeTablesRef'] = {'payloadSha256': changed['chunks'][1]['tableGroup']['nativeTablesRef']['payloadSha256']}
        self.rejects(changed)
        changed = copy.deepcopy(self.public)
        changed['chunks'][1]['tableGroup']['nativeParagraphIds'].pop()
        self.rejects(changed)

    def test_resealed_field_and_source_position_mutations_refused(self):
        for field, value in [('sourceHash', 'e' * 64), ('humanConfirmed', True), ('semanticScopeVerified', True),
                             ('visualReturnStatus', 'verified')]:
            changed = copy.deepcopy(self.public)
            changed[field] = value
            self.rejects(changed)
        changed = copy.deepcopy(self.public)
        changed['chunks'][0]['parts'][0]['sourcePosition']['partName'] = 'word/wrong.xml'
        self.rejects(changed)
        changed = copy.deepcopy(self.public)
        changed['chunks'][0]['parts'][0]['endOffset'] -= 1
        self.rejects(changed)
        changed = copy.deepcopy(self.public)
        changed['chunks'][0]['parts'][0]['nativeTokensRef']['payloadSha256'] = 'f' * 64
        self.rejects(changed)
        changed = copy.deepcopy(self.public)
        changed['chunks'][0]['parts'][0]['nativeNodeRef']['jsonPointer'] = '/nativeNodes/999'
        self.rejects(changed)
        changed = copy.deepcopy(self.public)
        changed['chunks'][0]['parts'][0]['nativePropertiesRef']['jsonPointer'] = '/wrong/channel'
        self.rejects(changed)

    def test_wrong_shape_version_and_internal_schema_not_public(self):
        for field, value in [('schema', 'consense.canonical-projection.v1'), ('storageShape', 'unknown-shape'),
                             ('producerKind', 'native_pdf')]:
            changed = copy.deepcopy(self.public)
            changed[field] = value
            self.rejects(changed, 'unsupported_native_public_storage_shape')
        changed = copy.deepcopy(self.public)
        changed['projectionIdentity']['version'] = 'unknown-next-version'
        self.rejects(changed, 'unsupported_native_public_projection_version')
        with self.assertRaisesRegex(CanonicalProjectionError, 'unsupported_native_public_storage_shape'):
            validate_native_transport(self.internal, artifact_store=self.store)

    def test_actual_but_unrecognized_transport_code_refused(self):
        changed = copy.deepcopy(self.public)
        descriptor = self.store.put_bytes(b'not the transport implementation')
        changed['transportCodeRef'] = descriptor
        changed['projectionIdentity']['projectionCodeSha256'] = descriptor['sha256']
        self.rejects(changed, 'native_transport_code_not_current')

    def test_document_role_scope_quality_or_adoption_change_yields_new_public_identity(self):
        bundle, manifest = fixture(self.store)
        for field, value in [('documentId', 'other-AUTHORED-db-id'), ('role', 'standard'), ('module', 'advisory')]:
            with self.subTest(field=field):
                changed = copy.deepcopy(manifest)
                changed[field] = value
                view = produce_native(bundle, changed, artifact_store=self.store)
                public = publish_native_transport(view, artifact_store=self.store)
                self.assertNotEqual(public['projectionSha256'], self.public['projectionSha256'])
                self.assertNotEqual(public['chunks'][0]['id'], self.public['chunks'][0]['id'])

    def test_no_native_page_bbox_or_fake_q10_assets(self):
        self.assertEqual(self.public['assetRefs'], [])
        self.assertEqual(self.public['visualReturnStatus'], 'unavailable_pending_verified_rendition')
        for chunk in self.public['chunks']:
            self.assertNotIn('pageNo', chunk)
            self.assertEqual(chunk['physicalPages'], [])
            for part in chunk['parts']:
                self.assertNotIn('physicalPageNo', part)
                self.assertNotIn('bbox', part)
                self.assertTrue(part['native'])


if __name__ == '__main__':
    unittest.main()
