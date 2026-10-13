"""Bounded native document wire, preserving one source identity and whole units.

The complete legacy public view is reconstructed only in process memory. Its
header (including the full adoption and cross-unit relations) and each ordered
whole-unit batch are retained as actual CAS bytes. Every read validates the
complete native source and reconstructs all commitments; no validation cache or
read-time repair is permitted. This module does not register batches as sources.
"""
from __future__ import annotations

import copy
from pathlib import Path

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _fail, _hash
from native_canonical_projection import validate_native_projection
from native_observation import _strict_json
import native_projection_transport as transport
import native_collection_envelope as envelope

SCHEMA = 'consense.native-document.v1'
BATCH_SCHEMA = 'consense.native-document-batch.v1'
VERSION = 'whole-native-units-ordered-cas-document-v1'
MAX_SNAPSHOT_BYTES = transport.MAX_SNAPSHOT_BYTES
MANIFEST_METADATA_SHAPE = 'manifest-without-units-tableGroups-relations-assets-v1'


def _boundary(operation, code):
    try:
        return operation()
    except CanonicalProjectionError:
        raise
    except (ValueError, OSError, KeyError, TypeError, IndexError, AttributeError) as error:
        raise CanonicalProjectionError(code, reason=str(error)) from error


def _store_required(store):
    _fail(isinstance(store, ArtifactStore), 'actual_artifact_store_required')


def _limit(value):
    _fail(type(value) is int and 0 < value <= MAX_SNAPSHOT_BYTES,
          'native_collection_batch_limit_invalid')
    return value


def _seal(value):
    value['projectionSha256'] = _hash(value)
    return value


def _sealed(value, code):
    _fail(isinstance(value, dict) and value.get('projectionSha256') == _hash({
        key: item for key, item in value.items() if key != 'projectionSha256'}), code)


def _bounded(value, maximum, code):
    raw = _bytes(value)
    _fail(len(raw) <= maximum, code, byteCount=len(raw), maximumBytes=maximum)
    return raw


def native_collection_descriptor(collection):
    """Describe supplied root bytes; this alone does not establish admission."""
    return transport._descriptor(_bytes(collection))


def _global(public):
    return {'publicProjectionSha256': public['projectionSha256'],
            'documentId': public['projectionIdentity']['documentId'],
            'sourceSha256': public['source']['sourceSha256'],
            'sourceHash': public['sourceHash'],
            'projectionRevisionSha256': public['projectionRevisionSha256']}


def _batch_base(public, ordinal, first):
    return {'schema': BATCH_SCHEMA, 'version': VERSION, **_global(public),
            'ordinal': ordinal, 'firstUnitOrdinal': first}


def _batch(base, chunks, unknowns):
    return _seal({**base, 'unitCount': len(chunks),
                  'unitOrderSha256': _hash([chunk['unitId'] for chunk in chunks]),
                  'chunks': chunks, 'unknowns': unknowns})


def _partition(public, maximum):
    """Greedy source-order packing with exact canonical JSON byte arithmetic.

    Only the full chunk is indivisible. A placeholder SHA has the same JSON byte
    length as the final seal. Computing chunk bytes once avoids reserializing a
    growing PRE batch for every paragraph; final serialization still checks it.
    """
    maximum = _limit(maximum)
    chunks = public['chunks']
    unknowns = public['unknowns']
    _fail(isinstance(unknowns, list)
          and [row.get('unitId') for row in unknowns] == [chunk['unitId'] for chunk in chunks],
          'native_collection_unknowns_unit_order_mismatch')
    batches, current, current_unknowns, content_bytes, first = [], [], [], 0, 0
    for chunk, unknown in zip(chunks, unknowns):
        chunk_bytes = len(_bytes(chunk)) + len(_bytes(unknown))
        base = _batch_base(public, len(batches), first)
        count = len(current) + 1
        empty = {**base, 'unitCount': count, 'unitOrderSha256': '0' * 64,
                 'chunks': [], 'unknowns': [], 'projectionSha256': '0' * 64}
        # Empty [] is retained in the envelope; inserted members and commas are
        # exactly their canonical serialization with no whitespace.
        candidate_bytes = len(_bytes(empty)) + content_bytes + chunk_bytes + 2 * (count - 1)
        if candidate_bytes > maximum and current:
            completed = _batch(base, current, current_unknowns)
            _bounded(completed, maximum, 'native_collection_batch_too_large')
            batches.append(completed)
            first += len(current)
            current, current_unknowns, content_bytes = [], [], 0
            base = _batch_base(public, len(batches), first)
            empty = {**base, 'unitCount': 1, 'unitOrderSha256': '0' * 64,
                     'chunks': [], 'unknowns': [], 'projectionSha256': '0' * 64}
            candidate_bytes = len(_bytes(empty)) + chunk_bytes
        _fail(candidate_bytes <= maximum, 'native_collection_unit_too_large',
              unitId=chunk['unitId'], sourceOrder=chunk['sourceOrder'],
              byteCount=candidate_bytes, maximumBytes=maximum,
              canonicalScope=chunk['canonicalScope'])
        current.append(chunk)
        current_unknowns.append(unknown)
        content_bytes += chunk_bytes
    if current:
        final = _batch(_batch_base(public, len(batches), first), current, current_unknowns)
        _bounded(final, maximum, 'native_collection_batch_too_large')
        batches.append(final)
    _fail(bool(batches), 'native_collection_empty_document')
    return batches


def _header_ref(descriptor, pointer, value, public):
    return {'artifactDescriptor': copy.deepcopy(descriptor), 'jsonPointer': pointer,
            'payloadSha256': _hash(value), **_global(public)}


def _layout(public, collection_code, maximum):
    """Build all payloads before writing collection artifacts to the store."""
    header = {key: value for key, value in public.items() if key != 'chunks'}
    header_descriptor = transport._descriptor(_bytes(header))
    batches = _partition(public, maximum)
    descriptors = []
    for batch in batches:
        descriptors.append({'ordinal': batch['ordinal'],
            'firstUnitOrdinal': batch['firstUnitOrdinal'], 'unitCount': batch['unitCount'],
            'unitIds': [chunk['unitId'] for chunk in batch['chunks']],
            'unitOrderSha256': batch['unitOrderSha256'],
            'projectionSha256': batch['projectionSha256'],
            'artifactDescriptor': transport._descriptor(_bytes(batch))})
    manifest = public['manifest']
    root = {'schema': SCHEMA, 'version': VERSION, **_global(public),
        'projectionIdentity': copy.deepcopy(public['projectionIdentity']),
        'source': copy.deepcopy(public['source']),
        'observerIdentity': copy.deepcopy(public['observerIdentity']),
        'manifestMetadataShape': MANIFEST_METADATA_SHAPE,
        'manifestMetadata': {key: copy.deepcopy(value) for key, value in manifest.items()
                             if key not in {'units', 'tableGroups', 'relations', 'assets'}},
        'manifestSha256': _hash(manifest),
        'collectionCodeRef': copy.deepcopy(collection_code),
        'transportCodeRef': copy.deepcopy(public['transportCodeRef']),
        'internalProjectionRef': copy.deepcopy(public['internalProjectionRef']),
        'headerRef': _header_ref(header_descriptor, '', header, public),
        'envelopeCodeRef': transport._descriptor(Path(envelope.__file__).read_bytes()),
        'envelopeRefs': {key: _header_ref(header_descriptor, '/' + key, public[key], public)
            for key in ('manifest', 'canonical', 'unknowns', 'limitations',
                        'nativeObservationRef', 'nativeCaptureRefs', 'sourceReferenceRef',
                        'captureCompleteness', 'nativeObservationQuality')},
        'nativeObservationQuality': copy.deepcopy(public['nativeObservationQuality']),
        'humanConfirmed': False, 'semanticScopeVerified': False,
        'visualReturnStatus': public['visualReturnStatus'],
        'canonicalClosure': {'unitCount': len(public['chunks']),
            'unitOrderSha256': _hash([chunk['unitId'] for chunk in public['chunks']]),
            'commitmentsSha256': _hash(public['canonical']['units']),
            'relationCount': len(public['canonical']['relations']),
            'relationsSha256': _hash(public['canonical']['relations']),
            'canonicalSha256': _hash(public['canonical'])},
        'batchPolicy': {'maximumBatchBytes': maximum,
            'maximumRootBytes': MAX_SNAPSHOT_BYTES,
            'unitBoundary': 'complete-adopted-native-unit',
            'sourceOrder': 'unchanged-public-chunk-order',
            'oversizeUnit': 'reject-without-splitting'},
        'batches': descriptors,
        'transportValidation': {'mode': 'cold-full-source-cas-every-collection-read',
            'derivedValidationCache': 'not_implemented', 'qualityCertified': False,
            'fullPublicHydration': 'internal-memory-only-not-a-bounded-wire-snapshot'}}
    # Preserve the old whole-unit/root failures before the additive header gate.
    _bounded({**root, 'projectionSha256': '0' * 64}, MAX_SNAPSHOT_BYTES,
             'native_collection_root_too_large')
    envelope_manifest, envelope_descriptors, envelope_batches = envelope.build_envelope_layout(
        header, identity=_global(public), maximum=maximum)
    root.update(completeEnvelope=envelope_manifest, envelopeBatches=envelope_descriptors)
    _seal(root)
    _bounded(root, MAX_SNAPSHOT_BYTES, 'native_collection_root_too_large')
    return root, header, batches, envelope_batches


def _collection_payloads(root, header, batches, envelope_batches, store, *, capture):
    # Publication validates the whole layout before writing header/batches/root;
    # reading requires all of them already present. Missing CAS is never healed.
    transport._payload(header, store, capture)
    for batch in batches:
        transport._payload(batch, store, capture)
    for batch in envelope_batches:
        transport._payload(batch, store, capture)
    transport._payload(root, store, capture)


def publish_native_collection(internal_projection, *, artifact_store,
                              max_batch_bytes=MAX_SNAPSHOT_BYTES):
    """Capture one complete document as an ordered bounded root and batches."""
    def run():
        _store_required(artifact_store)
        maximum = _limit(max_batch_bytes)
        validate_native_projection(internal_projection, artifact_store=artifact_store)
        _fail(internal_projection.get('assetRefs') == [],
              'native_transport_rendition_assets_unsupported')
        code_ref = artifact_store.put_bytes(Path(__file__).read_bytes())
        transport_code = artifact_store.put_bytes(Path(transport.__file__).read_bytes())
        artifact_store.put_bytes(Path(envelope.__file__).read_bytes())
        # _assemble deliberately has no old single-public-snapshot _size call.
        public = transport._assemble(internal_projection, artifact_store, transport_code,
                                     capture=True)
        root, header, batches, envelope_batches = _layout(public, code_ref, maximum)
        _collection_payloads(root, header, batches, envelope_batches, artifact_store, capture=True)
        return root
    return _boundary(run, 'malformed_native_collection_input')


def _verify(collection, store):
    _store_required(store)
    _fail(isinstance(collection, dict) and collection.get('schema') == SCHEMA
          and collection.get('version') == VERSION, 'unsupported_native_collection_schema')
    _bounded(collection, MAX_SNAPSHOT_BYTES, 'native_collection_root_too_large')
    _sealed(collection, 'native_collection_seal_mismatch')
    maximum = _limit(collection['batchPolicy']['maximumBatchBytes'])
    code_ref = collection['collectionCodeRef']
    actual_code = store.read_asset(code_ref)
    _fail(actual_code == Path(__file__).read_bytes(),
          'native_collection_code_not_current')
    _fail(code_ref == transport._descriptor(actual_code), 'native_collection_code_identity_mismatch')
    envelope_code = collection['envelopeCodeRef']
    actual_envelope_code = store.read_asset(envelope_code)
    _fail(actual_envelope_code == Path(envelope.__file__).read_bytes(),
          'native_envelope_code_not_current')
    _fail(envelope_code == transport._descriptor(actual_envelope_code),
          'native_envelope_code_identity_mismatch')
    transport_code = collection['transportCodeRef']
    actual_transport_code = store.read_asset(transport_code)
    _fail(actual_transport_code == Path(transport.__file__).read_bytes(),
          'native_transport_code_not_current')
    _fail(transport_code == transport._descriptor(actual_transport_code),
          'native_transport_code_identity_mismatch')
    raw = store.read_asset(collection['internalProjectionRef']['artifactDescriptor'])
    internal = _strict_json(raw)
    validate_native_projection(internal, artifact_store=store)
    _fail(internal.get('assetRefs') == [], 'native_transport_rendition_assets_unsupported')
    public = transport._assemble(internal, store, transport_code, capture=False)
    expected, header, batches, envelope_batches = _layout(public, code_ref, maximum)
    _fail(collection == expected, 'native_collection_derived_payload_mismatch')
    _collection_payloads(expected, header, batches, envelope_batches, store, capture=False)
    return public, batches, envelope_batches


def validate_native_collection(collection, *, artifact_store):
    """Cold verification of the complete document, including every stored batch."""
    _boundary(lambda: _verify(collection, artifact_store), 'malformed_native_collection_snapshot')
    return collection


def hydrate_native_collection(collection, *, artifact_store):
    """Return the complete legacy public view for internal memory consumption.

    This result can exceed 16 MiB and must not be served as the old public wire.
    """
    return _boundary(lambda: _verify(collection, artifact_store)[0],
                     'malformed_native_collection_snapshot')


def read_native_collection_batch(collection, ordinal, *, artifact_store):
    """Read one bounded batch after complete source/header/all-batch validation."""
    def run():
        _fail(type(ordinal) is int and ordinal >= 0, 'native_collection_batch_ordinal_invalid')
        _, batches, _ = _verify(collection, artifact_store)
        _fail(ordinal < len(batches), 'native_collection_batch_ordinal_invalid')
        return batches[ordinal]
    return _boundary(run, 'malformed_native_collection_batch_read')


def read_native_collection_envelope_batch(collection, ordinal, *, artifact_store, include_chunks=False):
    """Bounded complete header records after original/CAS/all-batch cold proof."""
    def run():
        _fail(type(ordinal) is int and ordinal >= 0, 'native_envelope_batch_ordinal_invalid')
        public, _, batches = _verify(collection, artifact_store)
        _fail(ordinal < len(batches), 'native_envelope_batch_ordinal_invalid')
        return (batches[ordinal], public['chunks']) if include_chunks else batches[ordinal]
    return _boundary(run, 'malformed_native_collection_envelope_read')


def read_native_collection_root(descriptor, *, artifact_store):
    """Load actual root bytes and cold-verify the complete source and batches."""
    def run():
        _store_required(artifact_store)
        _fail(isinstance(descriptor, dict) and type(descriptor.get('byteCount')) is int
              and 0 <= descriptor['byteCount'] <= MAX_SNAPSHOT_BYTES,
              'native_collection_root_too_large')
        root = _strict_json(artifact_store.read_asset(descriptor))
        _verify(root, artifact_store)
        return root
    return _boundary(run, 'malformed_native_collection_root_read')
