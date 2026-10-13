"""Compact, source-bound public wire for the validated native DOCX view.

Every full observation, token and native inventory remains actual CAS bytes.
Only adopted channels/positions and complete group topology enter public chunks.
Reads hydrate and verify the internal view before exact public reconstruction;
this first version deliberately has no warm validation cache.
"""
from __future__ import annotations

import copy
import hashlib
from pathlib import Path

from artifact_store import ArtifactStore, DESCRIPTOR_SCHEMA
from canonical_projection import CanonicalProjectionError, _bytes, _fail, _hash
from native_canonical_projection import SCHEMA, validate_native_projection
from native_observation import _strict_json

STORAGE_SHAPE = 'cas-native-observation-adopted-public-v1'
VERSION = 'source-reviewed-native-cas-public-projection-v1'
COMMITMENTS_VERSION = 'native-public-whole-unit-commitments-v1'
MAX_SNAPSHOT_BYTES = 16777216


def _boundary(operation, code):
    try:
        return operation()
    except CanonicalProjectionError:
        raise
    except (ValueError, OSError, KeyError, TypeError, IndexError, AttributeError) as error:
        raise CanonicalProjectionError(code, reason=str(error)) from error


def _store_required(store):
    _fail(isinstance(store, ArtifactStore), 'actual_artifact_store_required')


def _size(snapshot, limit):
    _fail(type(limit) is int and 0 < limit <= MAX_SNAPSHOT_BYTES, 'native_public_snapshot_limit_invalid')
    raw = _bytes(snapshot)
    _fail(len(raw) <= limit, 'native_public_snapshot_too_large', byteCount=len(raw), maximumBytes=limit)
    return raw


def _descriptor(raw):
    digest = hashlib.sha256(raw).hexdigest()
    return {'schema': DESCRIPTOR_SCHEMA, 'storage': 'content_addressed_disk', 'sha256': digest,
            'byteCount': len(raw), 'relativePath': 'sha256/' + digest[:2] + '/' + digest + '.blob',
            'identityScope': 'exact_supplied_bytes'}


def _payload(value, store, capture):
    raw = _bytes(value)
    descriptor = _descriptor(raw)
    if capture:
        _fail(store.put_bytes(raw) == descriptor, 'native_transport_capture_descriptor_mismatch')
    else:
        # A read never repairs/recreates a missing captured artifact.
        _fail(store.read_asset(descriptor) == raw, 'native_transport_actual_payload_mismatch')
    return descriptor


def _reference(descriptor, pointer, value, internal):
    return {'artifactDescriptor': copy.deepcopy(descriptor), 'jsonPointer': pointer,
            'payloadSha256': _hash(value), 'sourceSha256': internal['source']['sourceSha256'],
            'sourceRevisionSha256': internal['source']['sourceRevisionSha256'],
            'observerIdentitySha256': internal['projectionIdentity']['observerIdentitySha256']}


def _raw_property(value, descriptor, pointer, internal):
    _fail(isinstance(value, dict), 'native_inventory_raw_property_malformed')
    result = {key: copy.deepcopy(item) for key, item in value.items() if key != 'rawNode'}
    if 'rawNode' in value:
        result['rawNodeRef'] = _reference(descriptor, pointer + '/rawNode', value['rawNode'], internal)
    return result


def _table_topology(tables, descriptor, internal):
    result = []
    for ti, table in enumerate(tables):
        base = '/' + str(ti)
        public = {key: copy.deepcopy(value) for key, value in table.items() if key not in {'rows', 'gridColumns'}}
        public['nativeInventoryRef'] = _reference(descriptor, base, table, internal)
        if 'gridColumns' in table:
            public['gridColumnsRef'] = _reference(descriptor, base + '/gridColumns', table['gridColumns'], internal)
        public['rows'] = []
        for ri, row in enumerate(table['rows']):
            path = base + '/rows/' + str(ri)
            public_row = {key: copy.deepcopy(value) for key, value in row.items()
                          if key not in {'cells', 'gridBeforeRaw', 'gridAfterRaw'}}
            for key in ('gridBeforeRaw', 'gridAfterRaw'):
                if key in row:
                    public_row[key] = _raw_property(row[key], descriptor, path + '/' + key, internal)
            public_row['cells'] = []
            for ci, cell in enumerate(row['cells']):
                cell_path = path + '/cells/' + str(ci)
                public_cell = {key: copy.deepcopy(value) for key, value in cell.items()
                               if key not in {'gridSpanRaw', 'vMergeRaw', 'hMergeRaw'}}
                for key in ('gridSpanRaw', 'vMergeRaw', 'hMergeRaw'):
                    if key in cell:
                        public_cell[key] = _raw_property(cell[key], descriptor, cell_path + '/' + key, internal)
                public_row['cells'].append(public_cell)
            public['rows'].append(public_row)
        result.append(public)
    return result


def _part(part, internal):
    public = {key: copy.deepcopy(value) for key, value in part.items() if key != 'nativeObservation'}
    node = part['nativeObservation']
    raw = part['candidateProvenance']['rawObservation']
    pointer = part['candidateProvenance']['jsonPointer']
    public['nativeNodeRef'] = {**_reference(raw, pointer, node, internal), 'nativeNodeId': node['nodeId'],
                               'sourcePosition': copy.deepcopy(node['sourcePosition'])}
    # These fields affect evidence interpretation and must remain directly visible.
    public['nativeTextChannels'] = copy.deepcopy(node['textChannels'])
    public['nativeEditingState'] = copy.deepcopy(node['editingState'])
    public['nativeLimitations'] = copy.deepcopy(node['limitations'])
    public['nativeSemanticQuality'] = node['semanticQuality']
    public['nativePropertiesRef'] = _reference(raw, pointer + '/rawProperties', node['rawProperties'], internal)
    public['nativeTokensRef'] = _reference(raw, pointer + '/tokens', node['tokens'], internal)
    return public


def _public_body(chunk, internal, store, capture):
    excluded = {'id', 'sourceHash', 'projectionRevisionSha256', 'parts', 'adoption', 'tableGroup'}
    body = {key: copy.deepcopy(value) for key, value in chunk.items() if key not in excluded}
    body['projectionVersion'] = VERSION
    body['metadataVersion'] = VERSION
    body['storageShape'] = STORAGE_SHAPE
    body['parts'] = [_part(part, internal) for part in chunk['parts']]
    adoption = chunk['adoption']
    body['adoption'] = {key: copy.deepcopy(value) for key, value in adoption.items()
                        if key not in {'nativeTables', 'nativeTableNodes'}}
    if 'tableGroup' in chunk:
        group = chunk['tableGroup']
        tables = group['nativeTables']
        table_descriptor = _payload(tables, store, capture)
        node_descriptor = _payload(adoption['nativeTableNodes'], store, capture)
        inventory = _reference(table_descriptor, '', tables, internal)
        body['adoption']['nativeTablesRef'] = copy.deepcopy(inventory)
        body['adoption']['nativeTableNodesRef'] = _reference(node_descriptor, '', adoption['nativeTableNodes'], internal)
        body['tableGroup'] = {key: copy.deepcopy(value) for key, value in group.items() if key != 'nativeTables'}
        body['tableGroup']['nativeTablesRef'] = copy.deepcopy(inventory)
        body['tableGroup']['nativeTableTopology'] = _table_topology(tables, table_descriptor, internal)
    return body


def _assemble(internal, store, code_ref, *, capture):
    bundle = internal['nativeObservationBundle']
    internal_descriptor = _payload(internal, store, capture)
    bundle_descriptor = _payload(bundle, store, capture)
    reference = internal['sourceReference']
    bodies = [_public_body(chunk, internal, store, capture) for chunk in internal['chunks']]
    commitments = []
    for body in bodies:
        unit = {'unitId': body['unitId'], 'scope': body['canonicalScope'],
                'partsSha256': _hash(body['parts']), 'adoptionSha256': _hash(body['adoption']),
                'chunkPreIdentitySha256': _hash(body)}
        if 'tableGroup' in body:
            unit['tableGroupSha256'] = _hash(body['tableGroup'])
        commitments.append(unit)
    canonical = {'version': COMMITMENTS_VERSION, 'units': commitments,
                 'relations': copy.deepcopy(internal['canonical']['relations']), 'assetRefs': []}
    identity = copy.deepcopy(internal['projectionIdentity'])
    identity.update(version=VERSION, storageShape=STORAGE_SHAPE, projectionCodeSha256=code_ref['sha256'],
                    internalProjectionSha256=internal['projectionSha256'],
                    internalProjectionCodeSha256=internal['projectionIdentity']['projectionCodeSha256'],
                    internalSnapshotBytesSha256=internal_descriptor['sha256'],
                    nativeBundleBytesSha256=bundle_descriptor['sha256'])
    source_hash = _hash({'projectionIdentity': identity, 'canonical': canonical})
    revision = _hash({'sourceHash': source_hash, 'projectionIdentity': identity})
    chunks = [{**body, 'id': 'canonical:' + _hash({'revision': revision, 'unitId': body['unitId']}),
               'sourceHash': source_hash, 'projectionRevisionSha256': revision} for body in bodies]
    result = {'schema': SCHEMA, 'producerKind': 'native_docx', 'storageShape': STORAGE_SHAPE,
              'projectionIdentity': identity, 'projectionRevisionSha256': revision, 'sourceHash': source_hash,
              'source': copy.deepcopy(internal['source']), 'manifest': copy.deepcopy(internal['manifest']),
              'observerIdentity': copy.deepcopy(internal['observerIdentity']),
              'transportCodeRef': copy.deepcopy(code_ref),
              'internalProjectionRef': {'artifactDescriptor': internal_descriptor,
                  'projectionSha256': internal['projectionSha256'], 'sourceHash': internal['sourceHash'],
                  'projectionRevisionSha256': internal['projectionRevisionSha256']},
              'nativeObservationRef': {'artifactDescriptor': bundle_descriptor, 'bundleSha256': bundle['bundleSha256']},
              'nativeCaptureRefs': {key: copy.deepcopy(bundle[key]) for key in
                                   ('sourcePackage', 'adapterSource', 'rawObservation', 'rawParts')},
              'sourceReferenceRef': {**copy.deepcopy(internal['manifest']['reference']),
                  'sourceMediaType': reference['sourceMediaType'], 'humanConfirmed': False},
              'nativeObservationQuality': copy.deepcopy(internal['nativeObservationQuality']),
              'canonical': canonical, 'chunks': chunks, 'assetRefs': [],
              'captureCompleteness': copy.deepcopy(internal['captureCompleteness']),
              'unknowns': copy.deepcopy(internal['unknowns']), 'humanConfirmed': False,
              'semanticScopeVerified': False, 'visualReturnStatus': internal['visualReturnStatus'],
              'limitations': copy.deepcopy(internal['limitations']),
              'transportValidation': {'mode': 'cold_full_cas_and_internal_reconstruction',
                                      'derivedValidationCache': 'not_implemented', 'qualityCertified': False}}
    result['projectionSha256'] = _hash(result)
    return result


def publish_native_transport(internal_projection, *, artifact_store, max_snapshot_bytes=MAX_SNAPSHOT_BYTES):
    """Capture complete bytes and create a compact public snapshot within the cap."""
    def run():
        _store_required(artifact_store)
        validate_native_projection(internal_projection, artifact_store=artifact_store)
        _fail(internal_projection.get('assetRefs') == [], 'native_transport_rendition_assets_unsupported')
        code = Path(__file__).read_bytes()
        code_ref = artifact_store.put_bytes(code)
        result = _assemble(internal_projection, artifact_store, code_ref, capture=True)
        _size(result, max_snapshot_bytes)
        return validate_native_transport(result, artifact_store=artifact_store, max_snapshot_bytes=max_snapshot_bytes)
    return _boundary(run, 'malformed_native_transport_input')


def hydrate_native_transport(public_projection, *, artifact_store, max_snapshot_bytes=MAX_SNAPSHOT_BYTES):
    """Verify the exact public commitment and return its actual validated internal view."""
    def run():
        _store_required(artifact_store)
        _fail(isinstance(public_projection, dict) and public_projection.get('schema') == SCHEMA
              and public_projection.get('producerKind') == 'native_docx'
              and public_projection.get('storageShape') == STORAGE_SHAPE,
              'unsupported_native_public_storage_shape')
        _size(public_projection, max_snapshot_bytes)
        _fail(public_projection.get('projectionSha256') == _hash({key: value for key, value in public_projection.items()
                        if key != 'projectionSha256'}), 'projection_seal_mismatch')
        identity = public_projection['projectionIdentity']
        _fail(identity.get('version') == VERSION and identity.get('storageShape') == STORAGE_SHAPE,
              'unsupported_native_public_projection_version')
        code_ref = public_projection['transportCodeRef']
        actual_code = artifact_store.read_asset(code_ref)
        _fail(identity.get('projectionCodeSha256') == hashlib.sha256(actual_code).hexdigest(),
              'native_transport_code_identity_mismatch')
        _fail(actual_code == Path(__file__).read_bytes(), 'native_transport_code_not_current')
        raw = artifact_store.read_asset(public_projection['internalProjectionRef']['artifactDescriptor'])
        internal = _strict_json(raw)
        validate_native_projection(internal, artifact_store=artifact_store)
        _fail(internal.get('assetRefs') == [], 'native_transport_rendition_assets_unsupported')
        expected = _assemble(internal, artifact_store, code_ref, capture=False)
        _fail(expected == public_projection, 'native_public_derived_payload_mismatch')
        return internal
    return _boundary(run, 'malformed_native_public_snapshot')


def validate_native_transport(public_projection, *, artifact_store, max_snapshot_bytes=MAX_SNAPSHOT_BYTES):
    """Every read proves actual CAS bytes and complete group membership, without cache."""
    hydrate_native_transport(public_projection, artifact_store=artifact_store, max_snapshot_bytes=max_snapshot_bytes)
    return public_projection
