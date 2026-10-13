"""Actual authored OOXML/CAS collection protocol tests, with no model calls."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _hash
from native_canonical_projection import produce_native
import native_projection_collection as collection
from native_projection_transport import publish_native_transport
from test_native_canonical_projection import fixture


class NativeProjectionCollectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = ArtifactStore(self.temp.name)
        self.bundle, self.adoption = fixture(self.store)
        self.internal = produce_native(self.bundle, self.adoption, artifact_store=self.store)
        self.public = publish_native_transport(self.internal, artifact_store=self.store)
        # Entire nested/blank table chunk fits; combining it with either adjacent
        # paragraph does not. This exercises a real cross-batch parent relation.
        self.root = collection.publish_native_collection(self.internal,
            artifact_store=self.store, max_batch_bytes=60000)

    def reseal(self, value):
        value['projectionSha256'] = _hash({key: item for key, item in value.items()
                                          if key != 'projectionSha256'})

    def rejects(self, root, code='native_collection_derived_payload_mismatch'):
        self.reseal(root)
        with self.assertRaisesRegex(CanonicalProjectionError, code):
            collection.validate_native_collection(root, artifact_store=self.store)

    def test_roundtrip_one_document_three_batches_exact_old_public(self):
        before = copy.deepcopy(self.internal)
        self.assertEqual(collection.hydrate_native_collection(self.root,
            artifact_store=self.store), self.public)
        self.assertEqual(self.internal, before)
        self.assertEqual(len(self.root['batches']), 3)
        self.assertEqual(self.root['documentId'], self.adoption['documentId'])
        self.assertEqual(self.root['publicProjectionSha256'], self.public['projectionSha256'])
        self.assertNotEqual(self.root['projectionSha256'], self.public['projectionSha256'])
        self.assertEqual(self.root['sourceHash'], self.public['sourceHash'])
        batches = [collection.read_native_collection_batch(self.root, i,
            artifact_store=self.store) for i in range(3)]
        self.assertEqual([chunk for batch in batches for chunk in batch['chunks']], self.public['chunks'])
        self.assertEqual([batch['firstUnitOrdinal'] for batch in batches], [0, 1, 2])
        self.assertEqual([row['unitIds'] for row in self.root['batches']], [['heading'], ['table'], ['header']])
        for batch, descriptor in zip(batches, self.root['batches']):
            self.assertEqual(batch['unitCount'], 1)
            self.assertEqual(batch['documentId'], self.root['documentId'])
            self.assertEqual(batch['sourceHash'], self.root['sourceHash'])
            self.assertEqual(self.store.read_asset(descriptor['artifactDescriptor']), _bytes(batch))
            self.assertLessEqual(len(_bytes(batch)), 60000)
            self.assertEqual(batch['unknowns'], [self.public['unknowns'][batch['firstUnitOrdinal']]])
        self.assertLessEqual(len(_bytes(self.root)), collection.MAX_SNAPSHOT_BYTES)

    def test_default_one_batch_and_partition_changes_no_public_identity(self):
        root = collection.publish_native_collection(self.internal, artifact_store=self.store)
        self.assertEqual(len(root['batches']), 1)
        self.assertNotEqual(root['projectionSha256'], self.root['projectionSha256'])
        self.assertEqual(root['publicProjectionSha256'], self.root['publicProjectionSha256'])
        self.assertEqual(collection.hydrate_native_collection(root, artifact_store=self.store), self.public)

    def test_complete_table_blank_nested_channels_and_cross_batch_parent_retained(self):
        batch = collection.read_native_collection_batch(self.root, 1, artifact_store=self.store)
        table = batch['chunks'][0]
        self.assertEqual(table, self.public['chunks'][1])
        self.assertEqual(len(table['parts']), 5)
        self.assertTrue(any(part['text'] == '' for part in table['parts']))
        outer, inner = table['tableGroup']['nativeTableTopology']
        self.assertEqual(outer['rows'][0]['cells'][0]['nestedTableIds'], [inner['tableId']])
        self.assertEqual(table['parts'][0]['nativeTextChannels'],
                         self.public['chunks'][1]['parts'][0]['nativeTextChannels'])
        header = json.loads(self.store.read_asset(self.root['headerRef']['artifactDescriptor']))
        self.assertEqual(header['canonical']['relations'], self.public['canonical']['relations'])
        self.assertEqual(header['manifest'], self.public['manifest'])
        self.assertEqual(self.root['canonicalClosure']['relationCount'], 1)
        self.assertEqual(self.root['canonicalClosure']['relationsSha256'],
                         _hash(self.public['canonical']['relations']))
        self.assertEqual(table['relations'][0]['toUnitId'], 'heading')

    def test_compact_manifest_metadata_is_explicit_and_full_manifest_hash_bound(self):
        self.assertEqual(self.root['manifestMetadataShape'], collection.MANIFEST_METADATA_SHAPE)
        self.assertEqual(self.root['manifestSha256'], _hash(self.public['manifest']))
        self.assertEqual(self.root['source'], self.public['source'])
        self.assertEqual(self.root['observerIdentity'], self.public['observerIdentity'])
        for key in ('units', 'tableGroups', 'relations', 'assets'):
            self.assertNotIn(key, self.root['manifestMetadata'])
        for key in ('scope', 'quality', 'reference', 'eligibleModules', 'module', 'role'):
            self.assertEqual(self.root['manifestMetadata'][key], self.public['manifest'][key])
        for key, ref in self.root['envelopeRefs'].items():
            self.assertEqual(ref['jsonPointer'], '/' + key)
            self.assertEqual(ref['payloadSha256'], _hash(self.public[key]))
        self.assertFalse(self.root['humanConfirmed'])
        self.assertFalse(self.root['semanticScopeVerified'])
        self.assertEqual(self.root['visualReturnStatus'], 'unavailable_pending_verified_rendition')

    def test_exact_largest_whole_unit_boundary_and_one_byte_below(self):
        table = collection.read_native_collection_batch(self.root, 1, artifact_store=self.store)
        limit = len(_bytes(table))
        root = collection.publish_native_collection(self.internal, artifact_store=self.store,
                                                     max_batch_bytes=limit)
        self.assertEqual(len(root['batches']), 3)
        self.assertEqual(root['batches'][1]['artifactDescriptor']['byteCount'], limit)
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_collection_unit_too_large') as error:
            collection.publish_native_collection(self.internal, artifact_store=self.store,
                                                 max_batch_bytes=limit - 1)
        self.assertEqual(error.exception.details['unitId'], 'table')
        self.assertEqual(error.exception.details['canonicalScope'], 'table_group')

    def test_limit_cannot_be_raised_or_invalid_and_no_root_written_on_failure(self):
        for limit in (True, 0, -1, 1.5, collection.MAX_SNAPSHOT_BYTES + 1):
            with self.subTest(limit=limit), patch.object(self.store, 'put_bytes',
                    side_effect=AssertionError('invalid cap must fail before capture')):
                with self.assertRaisesRegex(CanonicalProjectionError, 'native_collection_batch_limit_invalid'):
                    collection.publish_native_collection(self.internal,
                        artifact_store=self.store, max_batch_bytes=limit)
        stored_roots = []
        original_put = self.store.put_bytes
        def observe(raw):
            if raw.startswith(b'{') and json.loads(raw).get('schema') == collection.SCHEMA:
                stored_roots.append(raw)
            return original_put(raw)
        with patch.object(self.store, 'put_bytes', side_effect=observe):
            with self.assertRaisesRegex(CanonicalProjectionError, 'native_collection_unit_too_large'):
                collection.publish_native_collection(self.internal,
                    artifact_store=self.store, max_batch_bytes=100)
        self.assertEqual(stored_roots, [])

    def test_root_actual_descriptor_read_requires_all_source_bytes(self):
        descriptor = collection.native_collection_descriptor(self.root)
        self.assertEqual(collection.read_native_collection_root(descriptor,
            artifact_store=self.store), self.root)
        self.assertEqual(self.store.read_asset(descriptor), _bytes(self.root))
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_collection_root_too_large'):
            collection.read_native_collection_root({**descriptor,
                'byteCount': collection.MAX_SNAPSHOT_BYTES + 1}, artifact_store=self.store)
        source = self.root['envelopeRefs']['nativeCaptureRefs']
        self.assertEqual(source['jsonPointer'], '/nativeCaptureRefs')

    def test_batch_order_drop_duplicate_and_unit_order_mutations_rejected(self):
        mutations = [lambda root: root['batches'].reverse(),
                     lambda root: root['batches'].pop(),
                     lambda root: root['batches'].append(copy.deepcopy(root['batches'][0])),
                     lambda root: root['batches'][1].update(firstUnitOrdinal=0),
                     lambda root: root['batches'][1].update(ordinal=0),
                     lambda root: root['batches'][1].update(unitCount=2),
                     lambda root: root['batches'][1].update(unitIds=['heading']),
                     lambda root: root['batches'][1].update(unitOrderSha256='f' * 64)]
        for operation in mutations:
            root = copy.deepcopy(self.root)
            operation(root)
            self.rejects(root)

    def test_cross_revision_batch_and_root_identity_mutations_rejected(self):
        adoption = copy.deepcopy(self.adoption)
        adoption['documentId'] = 'AUTHORED-other-document'
        other = collection.publish_native_collection(produce_native(self.bundle,
            adoption, artifact_store=self.store), artifact_store=self.store, max_batch_bytes=60000)
        root = copy.deepcopy(self.root)
        root['batches'][1] = other['batches'][1]
        self.rejects(root)
        for key, value in (('documentId', 'wrong-document'), ('sourceSha256', 'e' * 64),
                           ('sourceHash', other['sourceHash']),
                           ('projectionRevisionSha256', other['projectionRevisionSha256']),
                           ('publicProjectionSha256', other['publicProjectionSha256']),
                           ('humanConfirmed', True), ('semanticScopeVerified', True),
                           ('visualReturnStatus', 'verified')):
            with self.subTest(key=key):
                root = copy.deepcopy(self.root)
                root[key] = value
                self.rejects(root)

    def test_actual_altered_batch_descriptor_even_resealed_payload_is_rejected(self):
        batch = collection.read_native_collection_batch(self.root, 1, artifact_store=self.store)
        batch['chunks'][0]['parts'].pop()  # Cannot remove blank or nested members.
        self.reseal(batch)
        descriptor = self.store.put_bytes(_bytes(batch))
        root = copy.deepcopy(self.root)
        root['batches'][1]['artifactDescriptor'] = descriptor
        root['batches'][1]['projectionSha256'] = batch['projectionSha256']
        self.rejects(root)

    def test_raw_actual_cas_tamper_any_family_blocks_root_hydrate_and_batch_reads(self):
        descriptors = [('internal', self.root['internalProjectionRef']['artifactDescriptor']),
            ('root', collection.native_collection_descriptor(self.root)),
            ('header', self.root['headerRef']['artifactDescriptor']),
            ('first_batch', self.root['batches'][0]['artifactDescriptor']),
            ('last_batch', self.root['batches'][-1]['artifactDescriptor']),
            ('source', self.public['nativeCaptureRefs']['sourcePackage']),
            ('adapter', self.public['nativeCaptureRefs']['adapterSource']),
            ('raw_observation', self.public['nativeCaptureRefs']['rawObservation']),
            ('raw_part', self.public['nativeCaptureRefs']['rawParts'][0]['artifactDescriptor']),
            ('table_inventory', self.public['chunks'][1]['tableGroup']['nativeTablesRef']['artifactDescriptor']),
            ('reference', self.public['sourceReferenceRef']['artifactDescriptor']),
            ('collection_code', self.root['collectionCodeRef']),
            ('transport_code', self.root['transportCodeRef'])]
        for family, descriptor in descriptors:
            with self.subTest(family=family):
                path = Path(self.temp.name) / descriptor['relativePath']
                raw = path.read_bytes()
                try:
                    path.write_bytes(b'changed actual bytes after publication')
                    with self.assertRaises(CanonicalProjectionError):
                        collection.read_native_collection_batch(self.root, 1, artifact_store=self.store)
                    with self.assertRaises(CanonicalProjectionError):
                        collection.hydrate_native_collection(self.root, artifact_store=self.store)
                finally:
                    path.write_bytes(raw)

    def test_every_read_full_cold_validation_no_capture_or_repair(self):
        with patch.object(self.store, 'put_bytes', side_effect=AssertionError('read cannot capture')):
            collection.validate_native_collection(self.root, artifact_store=self.store)
            collection.read_native_collection_root(collection.native_collection_descriptor(self.root),
                                                   artifact_store=self.store)
            collection.read_native_collection_batch(self.root, 0, artifact_store=self.store)
        missing = self.root['batches'][-1]['artifactDescriptor']
        path = Path(self.temp.name) / missing['relativePath']
        path.unlink()
        # Even reading the first batch revalidates the missing last one.
        with self.assertRaises(CanonicalProjectionError):
            collection.read_native_collection_batch(self.root, 0, artifact_store=self.store)
        self.assertFalse(path.exists())

    def test_missing_original_never_repaired_and_repeated_success_not_cache(self):
        collection.read_native_collection_batch(self.root, 0, artifact_store=self.store)
        path = Path(self.temp.name) / self.public['nativeCaptureRefs']['sourcePackage']['relativePath']
        path.unlink()
        with self.assertRaises(CanonicalProjectionError):
            collection.read_native_collection_batch(self.root, 0, artifact_store=self.store)
        self.assertFalse(path.exists())

    def test_header_cannot_drop_manifest_relations_or_unknowns_even_with_new_descriptor(self):
        original = json.loads(self.store.read_asset(self.root['headerRef']['artifactDescriptor']))
        for field in ('unknowns', 'canonical', 'manifest'):
            with self.subTest(field=field):
                header = copy.deepcopy(original)
                header.pop(field)
                descriptor = self.store.put_bytes(_bytes(header))
                root = copy.deepcopy(self.root)
                root['headerRef']['artifactDescriptor'] = descriptor
                root['headerRef']['payloadSha256'] = descriptor['sha256']
                self.rejects(root)

    def test_unknown_root_field_and_metadata_upgrade_are_not_silently_ignored(self):
        root = copy.deepcopy(self.root)
        root['manifest'] = self.public['manifest']
        self.rejects(root)
        root = copy.deepcopy(self.root)
        root['manifestMetadata']['quality']['semanticQuality'] = 'correct'
        self.rejects(root)
        root = copy.deepcopy(self.root)
        root['envelopeRefs']['unknowns']['payloadSha256'] = 'a' * 64
        self.rejects(root)

    def test_unsealed_wrong_schema_and_invalid_batch_ordinals_rejected(self):
        root = copy.deepcopy(self.root)
        root['sourceHash'] = 'f' * 64
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_collection_seal_mismatch'):
            collection.validate_native_collection(root, artifact_store=self.store)
        for field, value in (('schema', 'consense.canonical-projection.v2'), ('version', 'unknown-next-version')):
            root = copy.deepcopy(self.root)
            root[field] = value
            self.rejects(root, 'unsupported_native_collection_schema')
        for ordinal in (True, -1, 0.5, '0', 3):
            with self.subTest(ordinal=ordinal):
                with self.assertRaisesRegex(CanonicalProjectionError, 'native_collection_batch_ordinal_invalid'):
                    collection.read_native_collection_batch(self.root, ordinal, artifact_store=self.store)

    def test_unrecognized_actual_collection_code_refused(self):
        root = copy.deepcopy(self.root)
        root['collectionCodeRef'] = self.store.put_bytes(b'not current collection code')
        self.rejects(root, 'native_collection_code_not_current')

    def test_code_descriptor_scope_alias_or_extra_fields_cannot_redefine_identity(self):
        for key, code in (('collectionCodeRef', 'native_collection_code_identity_mismatch'),
                          ('transportCodeRef', 'native_transport_code_identity_mismatch')):
            for operation in (lambda ref: ref.update(identityScope='alias_scope'),
                              lambda ref: ref.update(uncommittedExtra='alias')):
                root = copy.deepcopy(self.root)
                operation(root[key])
                self.rejects(root, code)

    def test_exact_byte_estimation_matches_multibyte_content_and_count_digits(self):
        # Sizing-only pure layout probe; these repetitions are not a new adopted
        # document and never pass capture/source validation or enter an index.
        public = copy.deepcopy(self.public)
        public['chunks'] = [copy.deepcopy(self.public['chunks'][0]) for _ in range(12)]
        public['unknowns'] = [copy.deepcopy(self.public['unknowns'][0]) for _ in range(12)]
        for i, chunk in enumerate(public['chunks']):
            chunk['unitId'] = 'sizing-only-' + str(i)
            chunk['sourceOrder'] = i
            public['unknowns'][i]['unitId'] = chunk['unitId']
        batches = collection._partition(public, 100000)
        self.assertEqual([batch['unitCount'] for batch in batches], [10, 2])
        self.assertTrue(all(len(_bytes(batch)) <= 100000 for batch in batches))

    def test_unknowns_unknown_global_duplicate_missing_or_reordered_cannot_be_lost(self):
        for operation in (lambda public: public['unknowns'].reverse(),
                          lambda public: public['unknowns'].pop(),
                          lambda public: public['unknowns'].append({'message': 'unknown global item'}),
                          lambda public: public['unknowns'][1].update(unitId='heading')):
            with self.subTest(operation=operation):
                public = copy.deepcopy(self.public)
                operation(public)
                with self.assertRaisesRegex(CanonicalProjectionError,
                        'native_collection_unknowns_unit_order_mismatch'):
                    collection._partition(public, collection.MAX_SNAPSHOT_BYTES)

    def test_root_hard_cap_sizing_only_does_not_certify_source_adoption(self):
        public = copy.deepcopy(self.public)
        public['source']['sizingOnlyPayload'] = 'x' * collection.MAX_SNAPSHOT_BYTES
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_collection_root_too_large'):
            collection._layout(public, self.root['collectionCodeRef'], collection.MAX_SNAPSHOT_BYTES)


if __name__ == '__main__':
    unittest.main()
