"""Whole header/manifest wire regressions; authored contracts, no model calls."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _hash
from native_collection_envelope import (build_envelope_layout, consume_native_collection_wire,
                                       read_wire_batch, validate_wire_root)
import native_collection_envelope as envelope_protocol
from native_canonical_projection import produce_native
from native_collection_registry import NativeCollectionSourceRegistry
import native_projection_collection as collection
from native_projection_collection import publish_native_collection, read_native_collection_envelope_batch
from native_projection_transport import _descriptor, publish_native_transport
from test_native_canonical_projection import fixture


class CompleteNativeWireTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = ArtifactStore(self.temp.name + '/artifacts')
        self.bundle, self.adoption = fixture(self.store)
        self.internal = produce_native(self.bundle, self.adoption, artifact_store=self.store)
        self.public = publish_native_transport(self.internal, artifact_store=self.store)
        self.root = publish_native_collection(self.internal, artifact_store=self.store, max_batch_bytes=60000)
        self.registry = NativeCollectionSourceRegistry(self.temp.name + '/canonical', self.store)
        self.registry.publish(self.root)

    def raws(self, root=None):
        root = self.root if root is None else root
        return ({row['ordinal']: self.store.read_asset(row['artifactDescriptor']) for row in root['batches']},
                {row['ordinal']: self.store.read_asset(row['artifactDescriptor']) for row in root['envelopeBatches']})

    def consume(self, root, chunks, envelopes, *, expected=None):
        return consume_native_collection_wire(root, expected_projection_sha256=expected or root['projectionSha256'],
            batch_reader=chunks.__getitem__, envelope_reader=envelopes.__getitem__)

    def sizing_layout(self):
        # Pure wire layout, NOT an accepted/new adopted source: stresses header
        # capacity using whole opaque limitation records without any source read.
        public = copy.deepcopy(self.public)
        public['limitations'].extend('sizing-only unknown ' + str(i) + ' 值' * 6000 for i in range(5))
        public['projectionSha256'] = _hash({k: v for k, v in public.items() if k != 'projectionSha256'})
        root, header, chunks, envelopes = collection._layout(public, self.root['collectionCodeRef'], 60000)
        return root, header, public, {b['ordinal']: _bytes(b) for b in chunks}, {b['ordinal']: _bytes(b) for b in envelopes}

    @staticmethod
    def reseal(value):
        value['projectionSha256'] = _hash({k: v for k, v in value.items() if k != 'projectionSha256'})

    def forge_batch(self, root, raw_map, ordinal, operation, *, envelope):
        batch = json.loads(raw_map[ordinal])
        operation(batch)
        self.reseal(batch)
        raw = _bytes(batch)
        raw_map[ordinal] = raw
        description = root['envelopeBatches' if envelope else 'batches'][ordinal]
        description['artifactDescriptor'] = _descriptor(raw)
        description['projectionSha256'] = batch['projectionSha256']
        self.reseal(root)

    def test_registered_root_enumerates_bounded_complete_global_envelope(self):
        with tempfile.TemporaryDirectory() as root:
            store = ArtifactStore(root + '/artifacts')
            bundle, adoption = fixture(store)
            internal = produce_native(bundle, adoption, artifact_store=store)
            projection = publish_native_collection(internal, artifact_store=store,
                                                  max_batch_bytes=60000)
            registry = NativeCollectionSourceRegistry(root + '/canonical', store)
            registry.publish(projection)
            wire, _ = registry.wire(projection['projectionSha256'])
            # A CAS-only headerRef does not provide a bounded client transport.
            # Consumers must be able to enumerate ALL header portions from root.
            self.assertIn('envelopeBatches', wire,
                          'Complete manifest/global relations/limits have no bounded wire route')
            self.assertTrue(callable(getattr(registry, 'envelope_batch', None)))

    def test_complete_wire_exact_public_header_and_unchanged_old_chunks(self):
        chunks, envelopes = self.raws()
        with patch.object(self.store, 'read_asset', side_effect=AssertionError('client has no local CAS')):
            reconstructed = self.consume(self.root, chunks, envelopes)
        self.assertEqual(self.public, reconstructed)
        self.assertEqual(self.public['chunks'], reconstructed['chunks'])
        self.assertEqual(self.public['manifest'], reconstructed['manifest'])
        self.assertEqual(self.public['canonical']['relations'], reconstructed['canonical']['relations'])
        self.assertEqual(self.public['limitations'], reconstructed['limitations'])
        self.assertEqual(self.public['unknowns'], reconstructed['unknowns'])
        self.assertFalse(reconstructed['humanConfirmed'])
        self.assertFalse(reconstructed['semanticScopeVerified'])
        table = reconstructed['chunks'][1]
        self.assertEqual(5, len(table['parts']))
        self.assertEqual(2, len(table['tableGroup']['nativeTableTopology']))
        self.assertEqual(3, sum(part['text'] == '' for part in table['parts']))
        self.assertEqual(self.public['chunks'][1]['parts'][0]['nativeTextChannels'],
                         table['parts'][0]['nativeTextChannels'])

    def test_large_header_enumerated_all_batches_bounded_and_value_equal(self):
        root, header, public, chunks, envelopes = self.sizing_layout()
        self.assertGreater(len(_bytes(header)), 60000)
        self.assertGreater(len(envelopes), 1)
        self.assertEqual(len(envelopes), len(root['envelopeBatches']))
        requested = []
        result = consume_native_collection_wire(root, expected_projection_sha256=root['projectionSha256'],
            batch_reader=chunks.__getitem__, envelope_reader=lambda ordinal: (requested.append(ordinal), envelopes[ordinal])[1])
        self.assertEqual(list(range(len(envelopes))), requested)
        self.assertEqual(public, result)
        self.assertTrue(all(len(raw) <= 60000 for raw in [*chunks.values(), *envelopes.values()]))
        self.assertEqual(len(_bytes(header)), root['completeEnvelope']['headerByteCount'])
        self.assertEqual(_hash(header), root['completeEnvelope']['headerSha256'])
        self.assertLessEqual(len(_bytes(root)), collection.MAX_SNAPSHOT_BYTES)

    def test_unit_and_complete_tablegroup_metadata_records_never_split(self):
        records = [record for raw in self.raws()[1].values() for record in json.loads(raw)['records']]
        groups = [record for record in records if record['jsonPointer'] == '/manifest/tableGroups']
        self.assertEqual(1, len(groups))
        self.assertEqual(self.public['manifest']['tableGroups'][0], groups[0]['value'])
        unknowns = [record for record in records if record['jsonPointer'] == '/unknowns']
        self.assertEqual(self.public['unknowns'], [record['value'] for record in unknowns])
        self.assertTrue(any(record['jsonPointer'] == '/canonical/relations' for record in records))

    def test_envelope_records_exact_limit_and_next_byte_rejection(self):
        header = {k: v for k, v in self.public.items() if k != 'chunks'}
        first = build_envelope_layout(header, identity=collection._global(self.public), maximum=60000)[2][0]
        core_only = {k: v for k, v in first.items() if k not in ('projectionSha256', 'records', 'recordCount', 'recordOrderSha256')}
        maximum = len(_bytes(envelope_protocol._batch(core_only, first['records'][:1])))
        manifest, descriptions, batches = build_envelope_layout(header,
            identity=collection._global(self.public), maximum=maximum)
        self.assertTrue(all(len(_bytes(batch)) <= maximum for batch in batches))
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_envelope_atomic_record_too_large'):
            build_envelope_layout(header, identity=collection._global(self.public), maximum=maximum - 1)
        # Large complete limitation/unknown/core rows are never truncated.
        for pointer in ('limitations', 'unknowns'):
            oversized = copy.deepcopy(header)
            oversized[pointer].append('whole sizing-only row' * 4000)
            with self.assertRaisesRegex(CanonicalProjectionError, 'native_envelope_atomic_record_too_large'):
                build_envelope_layout(oversized, identity=collection._global(self.public), maximum=60000)
        oversized = copy.deepcopy(header)
        oversized['source']['unrecognizedOpaqueMetadata'] = '值' * 6000
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_envelope_atomic_record_too_large'):
            build_envelope_layout(oversized, identity=collection._global(self.public), maximum=10000)
        for limit in (True, 0, -1, 1.0, collection.MAX_SNAPSHOT_BYTES + 1):
            with self.assertRaisesRegex(CanonicalProjectionError, 'native_envelope_limit_invalid'):
                build_envelope_layout(header, identity=collection._global(self.public), maximum=limit)

    def test_wire_root_must_be_pinned_not_just_self_sealed(self):
        chunks, envelopes = self.raws()
        altered = copy.deepcopy(self.root)
        altered['documentId'] = 'different-source-document'
        self.reseal(altered)
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_wire_root_identity_mismatch'):
            self.consume(altered, chunks, envelopes, expected=self.root['projectionSha256'])
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_wire_expected_root_required'):
            validate_wire_root(self.root, None)

    def test_missing_duplicate_reordered_out_of_range_batch_manifest_rejected(self):
        root, _, _, chunks, envelopes = self.sizing_layout()
        for field in ('batches', 'envelopeBatches'):
            for action in ('drop', 'reverse', 'duplicate', 'gap', 'ordinal', 'count_bool'):
                with self.subTest(field=field, action=action):
                    altered = copy.deepcopy(root)
                    rows = altered[field]
                    if action == 'drop': rows.pop()
                    elif action == 'reverse': rows.reverse()
                    elif action == 'duplicate': rows.append(copy.deepcopy(rows[0]))
                    elif action == 'gap': rows[0]['firstRecordOrdinal' if field == 'envelopeBatches' else 'firstUnitOrdinal'] = 1
                    elif action == 'ordinal': rows[0]['ordinal'] = -1
                    else: rows[0]['recordCount' if field == 'envelopeBatches' else 'unitCount'] = True
                    self.reseal(altered)
                    with self.assertRaises(CanonicalProjectionError): self.consume(altered, chunks, envelopes)

    def test_omitted_envelope_raw_not_repaired_and_reader_order_wrong_fails(self):
        root, _, _, chunks, envelopes = self.sizing_layout()
        for action in ('drop', 'reverse', 'duplicate'):
            with self.subTest(action=action):
                changed = dict(envelopes)
                if action == 'drop': changed.pop(max(changed))
                elif action == 'reverse': changed = dict(zip(changed, reversed(list(changed.values()))))
                else: changed[max(changed)] = changed[0]
                with self.assertRaises(CanonicalProjectionError): self.consume(root, chunks, changed)

    def test_raw_tamper_wrong_source_and_resealed_content_changes_rejected(self):
        for envelope in (False, True):
            for field in ('documentId', 'sourceSha256', 'sourceHash', 'projectionRevisionSha256',
                          'publicProjectionSha256'):
                with self.subTest(envelope=envelope, field=field):
                    root = copy.deepcopy(self.root)
                    chunks, envelopes = self.raws()
                    raws = envelopes if envelope else chunks
                    self.forge_batch(root, raws, 0, lambda b: b.update({field: 'other' if field == 'documentId' else 'f' * 64}), envelope=envelope)
                    with self.assertRaisesRegex(CanonicalProjectionError, 'native_wire_batch_source_mismatch'):
                        self.consume(root, chunks, envelopes)
        root = copy.deepcopy(self.root)
        chunks, envelopes = self.raws()
        self.forge_batch(root, envelopes, 0, lambda b: b['records'][0]['value']['limitations'].append('forged'), envelope=True)
        # It is not enough to reseal the payload and replace its root descriptor.
        with self.assertRaises(CanonicalProjectionError): self.consume(root, chunks, envelopes)
        chunks, envelopes = self.raws()
        envelopes[0] = envelopes[0][:-1] + b' '
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_wire_batch_bytes_mismatch'):
            self.consume(self.root, chunks, envelopes)

    def test_array_and_record_manifest_omission_duplication_order_cannot_be_resealed(self):
        root, _, _, chunks, envelopes = self.sizing_layout()
        for action in ('omit', 'duplicate', 'reverse', 'count', 'hash', 'core', 'unknown_path'):
            with self.subTest(action=action):
                altered = copy.deepcopy(root)
                arrays = altered['completeEnvelope']['arrays']
                if action == 'omit': arrays.pop()
                elif action == 'duplicate': arrays.append(copy.deepcopy(arrays[0]))
                elif action == 'reverse': arrays.reverse()
                elif action == 'count': arrays[0]['itemCount'] += 1
                elif action == 'hash': arrays[0]['payloadSha256'] = 'f' * 64
                elif action == 'core': altered['completeEnvelope']['coreSha256'] = 'f' * 64
                else: arrays[0]['jsonPointer'] = '/uncommitted/array'
                self.reseal(altered)
                with self.assertRaises(CanonicalProjectionError): self.consume(altered, chunks, envelopes)

    def test_registry_cold_read_every_header_and_unit_actual_cas_no_cache_no_repair(self):
        self.registry.envelope_batch(self.root['projectionSha256'], 0)
        descriptors = [self.root['batches'][-1]['artifactDescriptor'],
                       self.root['envelopeBatches'][-1]['artifactDescriptor'],
                       self.root['headerRef']['artifactDescriptor'],
                       self.bundle['sourcePackage']]
        for descriptor in descriptors:
            with self.subTest(descriptor=descriptor['sha256']):
                path = self.store.root / descriptor['relativePath']
                raw = path.read_bytes()
                path.unlink()
                try:
                    with patch.object(self.store, 'put_bytes', side_effect=AssertionError('read cannot repair')):
                        with self.assertRaises(CanonicalProjectionError): self.registry.envelope_batch(self.root['projectionSha256'], 0)
                        with self.assertRaises(CanonicalProjectionError): self.registry.batch(self.root['projectionSha256'], 0)
                    self.assertFalse(path.exists())
                finally: path.write_bytes(raw)

    def test_registry_envelope_source_module_gate_and_invalid_ordinals(self):
        for ordinal in (True, -1, 0.5, '0', len(self.root['envelopeBatches'])):
            with self.assertRaisesRegex(CanonicalProjectionError, 'native_envelope_batch_ordinal_invalid'):
                read_native_collection_envelope_batch(self.root, ordinal, artifact_store=self.store)
        with self.assertRaises(ValueError): self.registry.envelope_batch(self.root['projectionSha256'], 0, module='drafting')
        envelope, chunks = self.registry.envelope_batch(self.root['projectionSha256'], 0, include_chunks=True)
        self.assertEqual(self.public['chunks'], chunks)
        self.assertEqual(self.store.read_asset(self.root['envelopeBatches'][0]['artifactDescriptor']), _bytes(envelope))

    def test_current_envelope_code_and_descriptor_alias_cold_rejected(self):
        for action in ('fake', 'alias', 'extra'):
            root = copy.deepcopy(self.root)
            if action == 'fake': root['envelopeCodeRef'] = self.store.put_bytes(b'not current envelope code')
            elif action == 'alias': root['envelopeCodeRef']['identityScope'] = 'alias'
            else: root['envelopeCodeRef']['uncommittedExtra'] = 'extra'
            self.reseal(root)
            with self.assertRaises(CanonicalProjectionError): collection.validate_native_collection(root, artifact_store=self.store)

    def test_resealed_root_metadata_and_references_cannot_disagree_with_complete_header(self):
        chunks, envelopes = self.raws()
        for operation in (lambda root: root['source'].update(sourceFilename='wrong.docx'),
                          lambda root: root['manifestMetadata']['scope'].update(scopePolicySha256='f' * 64),
                          lambda root: root.update(humanConfirmed=True),
                          lambda root: root['envelopeRefs']['limitations'].update(payloadSha256='f' * 64),
                          lambda root: root['headerRef'].update(jsonPointer='/wrong')):
            root = copy.deepcopy(self.root)
            operation(root)
            self.reseal(root)
            with self.assertRaises(CanonicalProjectionError): self.consume(root, chunks, envelopes)


if __name__ == '__main__':
    unittest.main()
