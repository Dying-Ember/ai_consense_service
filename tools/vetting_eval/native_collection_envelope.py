"""Complete native public header over bounded, whole-metadata-record batches.

This is wire integrity, not source review or admission. The authoritative server
still cold-validates all original/CAS bytes. A remote consumer pins the root
address and reconstructs every header field without reading host-local CAS.
No string, unit, table group, metadata row or unknown record is cut to fit.
"""
from __future__ import annotations

import copy

from canonical_projection import CanonicalProjectionError, _bytes, _fail, _hash
from native_observation import _strict_json
from native_projection_transport import MAX_SNAPSHOT_BYTES, _descriptor

SCHEMA = 'consense.native-document-envelope-batch.v1'
MANIFEST_SCHEMA = 'consense.native-document-envelope-manifest.v1'
VERSION = 'complete-header-whole-metadata-records-v1'
GLOBAL_FIELDS = ('publicProjectionSha256', 'documentId', 'sourceSha256',
                 'sourceHash', 'projectionRevisionSha256')
# Arrays are paged only at a documented complete-record boundary. In particular,
# a manifest tableGroups row (all native IDs) is indivisible, including blanks
# and nested groups. Unrecognized fields remain whole in the core.
ARRAY_PATHS = tuple(sorted((
    '/manifest/units', '/manifest/tableGroups', '/manifest/relations', '/manifest/assets',
    '/canonical/units', '/canonical/relations', '/canonical/assetRefs',
    '/unknowns', '/limitations', '/assetRefs', '/nativeCaptureRefs/rawParts',
)))


def _global(public):
    return {'publicProjectionSha256': public['projectionSha256'],
            'documentId': public['projectionIdentity']['documentId'],
            'sourceSha256': public['source']['sourceSha256'],
            'sourceHash': public['sourceHash'],
            'projectionRevisionSha256': public['projectionRevisionSha256']}


def _parent(value, pointer):
    keys = pointer[1:].split('/')
    cursor = value
    for key in keys[:-1]:
        _fail(isinstance(cursor, dict) and key in cursor, 'native_envelope_pointer_missing')
        cursor = cursor[key]
    _fail(isinstance(cursor, dict), 'native_envelope_pointer_invalid')
    return cursor, keys[-1]


def _record_identity(record):
    return {'kind': record['kind'], 'jsonPointer': record['jsonPointer'],
            'itemOrdinal': record['itemOrdinal'], 'payloadSha256': _hash(record['value'])}


def _records(header):
    core = copy.deepcopy(header)
    arrays, rows = [], []
    for pointer in ARRAY_PATHS:
        parent, key = _parent(header, pointer)
        if key not in parent:
            continue
        values = parent[key]
        _fail(isinstance(values, list), 'native_envelope_array_required', jsonPointer=pointer)
        core_parent, core_key = _parent(core, pointer)
        core_parent[core_key] = []
        arrays.append({'jsonPointer': pointer, 'itemCount': len(values), 'payloadSha256': _hash(values)})
        rows.extend({'kind': 'array_item', 'jsonPointer': pointer, 'itemOrdinal': ordinal, 'value': value}
                    for ordinal, value in enumerate(values))
    records = [{'kind': 'header_core', 'jsonPointer': '', 'itemOrdinal': 0, 'value': core}, *rows]
    return records, arrays


def _batch(base, records):
    result = {**base, 'recordCount': len(records),
              'recordOrderSha256': _hash([_record_identity(row) for row in records]), 'records': records}
    result['projectionSha256'] = _hash(result)
    return result


def _base(identity, header_sha, ordinal, first):
    return {'schema': SCHEMA, 'version': VERSION, **identity, 'headerSha256': header_sha,
            'ordinal': ordinal, 'firstRecordOrdinal': first}


def build_envelope_layout(header, *, identity, maximum):
    """Pure deterministic layout; no source review, registry write or CAS read."""
    _fail(type(maximum) is int and 0 < maximum <= MAX_SNAPSHOT_BYTES,
          'native_envelope_limit_invalid')
    records, arrays = _records(header)
    header_sha = _hash(header)
    batches, current, inserted_bytes, first = [], [], 0, 0
    for record in records:
        record_bytes = len(_bytes(record))
        base = _base(identity, header_sha, len(batches), first)
        count = len(current) + 1
        empty = {**base, 'recordCount': count, 'recordOrderSha256': '0' * 64,
                 'records': [], 'projectionSha256': '0' * 64}
        candidate_bytes = len(_bytes(empty)) + inserted_bytes + record_bytes + count - 1
        if candidate_bytes > maximum and current:
            batch = _batch(base, current)
            _fail(len(_bytes(batch)) <= maximum, 'native_envelope_batch_too_large')
            batches.append(batch)
            first += len(current)
            current, inserted_bytes = [], 0
            base = _base(identity, header_sha, len(batches), first)
            empty = {**base, 'recordCount': 1, 'recordOrderSha256': '0' * 64,
                     'records': [], 'projectionSha256': '0' * 64}
            candidate_bytes = len(_bytes(empty)) + record_bytes
        _fail(candidate_bytes <= maximum, 'native_envelope_atomic_record_too_large',
              kind=record['kind'], jsonPointer=record['jsonPointer'], itemOrdinal=record['itemOrdinal'],
              byteCount=candidate_bytes, maximumBytes=maximum)
        current.append(record)
        inserted_bytes += record_bytes
    if current:
        batch = _batch(_base(identity, header_sha, len(batches), first), current)
        _fail(len(_bytes(batch)) <= maximum, 'native_envelope_batch_too_large')
        batches.append(batch)
    descriptions = [{'ordinal': batch['ordinal'], 'firstRecordOrdinal': batch['firstRecordOrdinal'],
                     'recordCount': batch['recordCount'], 'recordOrderSha256': batch['recordOrderSha256'],
                     'projectionSha256': batch['projectionSha256'],
                     'artifactDescriptor': _descriptor(_bytes(batch))} for batch in batches]
    manifest = {'schema': MANIFEST_SCHEMA, 'version': VERSION,
                'headerSha256': header_sha, 'headerByteCount': len(_bytes(header)),
                'coreSha256': _hash(records[0]['value']), 'recordCount': len(records),
                'recordOrderSha256': _hash([_record_identity(row) for row in records]),
                'arrays': arrays, 'maximumBatchBytes': maximum,
                'recordBoundary': 'whole-header-core-or-complete-array-record',
                'oversizeRecord': 'reject-without-splitting'}
    return manifest, descriptions, batches


def _sealed(value, code):
    _fail(isinstance(value, dict) and value.get('projectionSha256') == _hash({
        key: item for key, item in value.items() if key != 'projectionSha256'}), code)


def validate_wire_root(root, expected_projection_sha256):
    """Validate the pinned root without pretending to read its original/CAS."""
    _fail(isinstance(expected_projection_sha256, str) and len(expected_projection_sha256) == 64,
          'native_wire_expected_root_required')
    _fail(isinstance(root, dict) and root.get('schema') == 'consense.native-document.v1'
          and root.get('version') == 'whole-native-units-ordered-cas-document-v1',
          'unsupported_native_wire_collection')
    _fail(len(_bytes(root)) <= MAX_SNAPSHOT_BYTES, 'native_wire_root_too_large')
    _sealed(root, 'native_wire_root_seal_mismatch')
    _fail(root['projectionSha256'] == expected_projection_sha256, 'native_wire_root_identity_mismatch')
    maximum = root['batchPolicy']['maximumBatchBytes']
    _fail(type(maximum) is int and 0 < maximum <= MAX_SNAPSHOT_BYTES,
          'native_wire_batch_limit_invalid')
    manifest = root['completeEnvelope']
    _fail(manifest.get('schema') == MANIFEST_SCHEMA and manifest.get('version') == VERSION
          and manifest.get('maximumBatchBytes') == maximum
          and manifest.get('recordBoundary') == 'whole-header-core-or-complete-array-record'
          and manifest.get('oversizeRecord') == 'reject-without-splitting',
          'native_wire_envelope_manifest_invalid')
    _fail(manifest['headerSha256'] == root['headerRef']['payloadSha256']
          and manifest['headerSha256'] == root['headerRef']['artifactDescriptor']['sha256']
          and manifest['headerByteCount'] == root['headerRef']['artifactDescriptor']['byteCount'],
          'native_wire_header_identity_mismatch')
    _validate_ranges(root['batches'], 'firstUnitOrdinal', 'unitCount', root['canonicalClosure']['unitCount'])
    _validate_ranges(root['envelopeBatches'], 'firstRecordOrdinal', 'recordCount', manifest['recordCount'])
    all_ids = [unit for batch in root['batches'] for unit in batch['unitIds']]
    _fail(len(set(all_ids)) == len(all_ids)
          and len(all_ids) == root['canonicalClosure']['unitCount']
          and _hash(all_ids) == root['canonicalClosure']['unitOrderSha256'],
          'native_wire_unit_closure_mismatch')
    for batch in root['batches']:
        _fail(len(batch['unitIds']) == batch['unitCount']
              and _hash(batch['unitIds']) == batch['unitOrderSha256'], 'native_wire_unit_order_mismatch')
    arrays = manifest['arrays']
    _fail(isinstance(arrays, list) and [row['jsonPointer'] for row in arrays]
          == sorted({row['jsonPointer'] for row in arrays})
          and all(row['jsonPointer'] in ARRAY_PATHS and type(row['itemCount']) is int
                  and row['itemCount'] >= 0 for row in arrays), 'native_wire_array_manifest_invalid')
    _fail(manifest['recordCount'] == 1 + sum(row['itemCount'] for row in arrays),
          'native_wire_record_closure_mismatch')
    return root


def _validate_ranges(rows, first_field, count_field, total):
    _fail(isinstance(rows, list) and bool(rows) and type(total) is int and total > 0,
          'native_wire_batch_manifest_invalid')
    first = 0
    for ordinal, row in enumerate(rows):
        _fail(type(row.get('ordinal')) is int and row['ordinal'] == ordinal
              and type(row.get(first_field)) is int and row[first_field] == first
              and type(row.get(count_field)) is int and row[count_field] > 0,
              'native_wire_batch_range_invalid')
        first += row[count_field]
    _fail(first == total, 'native_wire_batch_closure_mismatch')


def read_wire_batch(root, description, raw, *, envelope=False):
    """Verify exact supplied bytes under the root's actual batch descriptor."""
    candidates = root['envelopeBatches' if envelope else 'batches']
    ordinal = description.get('ordinal')
    _fail(type(ordinal) is int and 0 <= ordinal < len(candidates)
          and description == candidates[ordinal], 'native_wire_batch_description_invalid')
    _fail(isinstance(raw, bytes) and len(raw) <= root['batchPolicy']['maximumBatchBytes'],
          'native_wire_batch_too_large')
    _fail(_descriptor(raw) == description['artifactDescriptor'], 'native_wire_batch_bytes_mismatch')
    batch = _strict_json(raw)
    _fail(_bytes(batch) == raw, 'native_wire_batch_not_canonical_json')
    _sealed(batch, 'native_wire_batch_seal_mismatch')
    _fail(batch['projectionSha256'] == description['projectionSha256'], 'native_wire_batch_identity_mismatch')
    for field in GLOBAL_FIELDS:
        _fail(batch[field] == root[field], 'native_wire_batch_source_mismatch', field=field)
    fields = ('ordinal', 'firstRecordOrdinal', 'recordCount', 'recordOrderSha256') if envelope else (
        'ordinal', 'firstUnitOrdinal', 'unitCount', 'unitOrderSha256')
    _fail(all(type(batch[field]) is type(description[field]) and batch[field] == description[field]
              for field in fields), 'native_wire_batch_range_invalid')
    _fail(batch['schema'] == (SCHEMA if envelope else 'consense.native-document-batch.v1')
          and batch['version'] == (VERSION if envelope else 'whole-native-units-ordered-cas-document-v1'),
          'native_wire_batch_schema_mismatch')
    if envelope:
        _fail(batch['headerSha256'] == root['completeEnvelope']['headerSha256']
              and isinstance(batch['records'], list) and len(batch['records']) == batch['recordCount']
              and _hash([_record_identity(row) for row in batch['records']]) == batch['recordOrderSha256'],
              'native_wire_envelope_record_mismatch')
    else:
        ids = [chunk['unitId'] for chunk in batch['chunks']]
        _fail(ids == description['unitIds'] and len(ids) == batch['unitCount']
              and _hash(ids) == batch['unitOrderSha256']
              and [row['unitId'] for row in batch['unknowns']] == ids,
              'native_wire_unit_order_mismatch')
    return batch


def consume_native_collection_wire(root, *, expected_projection_sha256, batch_reader, envelope_reader):
    """Reconstruct exact full public fields in memory from bounded wire only.

    Reader callbacks accept ordinal and return exact raw response bytes. The
    result is internal memory only and must not be served over the single cap.
    It is not original verification, legal applicability or semantic acceptance.
    """
    try:
        validate_wire_root(root, expected_projection_sha256)
        records = []
        for description in root['envelopeBatches']:
            batch = read_wire_batch(root, description, envelope_reader(description['ordinal']), envelope=True)
            records.extend(batch['records'])
        manifest = root['completeEnvelope']
        _fail(len(records) == manifest['recordCount']
              and _hash([_record_identity(row) for row in records]) == manifest['recordOrderSha256'],
              'native_wire_record_closure_mismatch')
        first = records[0]
        _fail(first['kind'] == 'header_core' and first['jsonPointer'] == '' and type(first['itemOrdinal']) is int
              and first['itemOrdinal'] == 0 and _hash(first['value']) == manifest['coreSha256'],
              'native_wire_header_core_invalid')
        header = copy.deepcopy(first['value'])
        cursor = 1
        for array in manifest['arrays']:
            parent, key = _parent(header, array['jsonPointer'])
            _fail(parent.get(key) == [], 'native_wire_header_core_array_invalid')
            values = []
            for ordinal in range(array['itemCount']):
                record = records[cursor]
                cursor += 1
                _fail(record['kind'] == 'array_item' and record['jsonPointer'] == array['jsonPointer']
                      and type(record['itemOrdinal']) is int and record['itemOrdinal'] == ordinal,
                      'native_wire_array_record_order_invalid')
                values.append(copy.deepcopy(record['value']))
            _fail(_hash(values) == array['payloadSha256'], 'native_wire_array_payload_mismatch')
            parent[key] = values
        _fail(cursor == len(records) and _descriptor(_bytes(header)) == root['headerRef']['artifactDescriptor'],
              'native_wire_complete_header_mismatch')
        # Re-decompose to reject missing/undeclared/duplicated arrays or records,
        # not merely a self-consistent supplier-created partial manifest.
        expected_records, expected_arrays = _records(header)
        _fail(records == expected_records and manifest['arrays'] == expected_arrays,
              'native_wire_complete_record_manifest_mismatch')
        chunks, unknowns = [], []
        for description in root['batches']:
            batch = read_wire_batch(root, description, batch_reader(description['ordinal']))
            chunks.extend(batch['chunks'])
            unknowns.extend(batch['unknowns'])
        _fail(unknowns == header['unknowns'], 'native_wire_unknowns_mismatch')
        public = {**header, 'chunks': chunks}
        _fail(public['projectionSha256'] == root['publicProjectionSha256']
              and _hash({key: value for key, value in public.items() if key != 'projectionSha256'})
              == root['publicProjectionSha256'], 'native_wire_complete_public_mismatch')
        _fail(_global(public) == {key: root[key] for key in GLOBAL_FIELDS}, 'native_wire_public_identity_mismatch')
        for field in ('projectionIdentity', 'source', 'observerIdentity', 'transportCodeRef',
                      'internalProjectionRef', 'nativeObservationQuality', 'humanConfirmed',
                      'semanticScopeVerified', 'visualReturnStatus'):
            _fail(type(root[field]) is type(public[field]) and root[field] == public[field],
                  'native_wire_root_public_metadata_mismatch', field=field)
        expected_metadata = {key: value for key, value in public['manifest'].items()
                             if key not in {'units', 'tableGroups', 'relations', 'assets'}}
        _fail(root['manifestMetadataShape'] == 'manifest-without-units-tableGroups-relations-assets-v1'
              and root['manifestMetadata'] == expected_metadata, 'native_wire_manifest_metadata_mismatch')
        expected_header_ref = {'artifactDescriptor': _descriptor(_bytes(header)), 'jsonPointer': '',
                               'payloadSha256': _hash(header), **_global(public)}
        _fail(root['headerRef'] == expected_header_ref, 'native_wire_header_reference_mismatch')
        for field in ('manifest', 'canonical', 'unknowns', 'limitations', 'nativeObservationRef',
                      'nativeCaptureRefs', 'sourceReferenceRef', 'captureCompleteness', 'nativeObservationQuality'):
            expected_ref = {**expected_header_ref, 'jsonPointer': '/' + field, 'payloadSha256': _hash(public[field])}
            _fail(root['envelopeRefs'][field] == expected_ref, 'native_wire_envelope_reference_mismatch', field=field)
        _fail(public['manifest'] and _hash(public['manifest']) == root['manifestSha256']
              and public['projectionIdentity']['manifestSha256'] == root['manifestSha256'],
              'native_wire_manifest_identity_mismatch')
        canonical = public['canonical']
        _fail(_hash(canonical['units']) == root['canonicalClosure']['commitmentsSha256']
              and len(canonical['relations']) == root['canonicalClosure']['relationCount']
              and _hash(canonical['relations']) == root['canonicalClosure']['relationsSha256']
              and _hash(canonical) == root['canonicalClosure']['canonicalSha256'],
              'native_wire_canonical_closure_mismatch')
        commitments, orders = [], []
        for chunk in chunks:
            _fail(chunk['sourceHash'] == root['sourceHash']
                  and chunk['projectionRevisionSha256'] == root['projectionRevisionSha256']
                  and chunk['documentId'] == root['documentId']
                  and chunk['id'] == 'canonical:' + _hash({'revision': root['projectionRevisionSha256'], 'unitId': chunk['unitId']}),
                  'native_wire_chunk_identity_mismatch')
            orders.append(chunk['sourceOrder'])
            body = {key: value for key, value in chunk.items() if key not in {'id', 'sourceHash', 'projectionRevisionSha256'}}
            unit = {'unitId': body['unitId'], 'scope': body['canonicalScope'], 'partsSha256': _hash(body['parts']),
                    'adoptionSha256': _hash(body['adoption']), 'chunkPreIdentitySha256': _hash(body)}
            if 'tableGroup' in body:
                unit['tableGroupSha256'] = _hash(body['tableGroup'])
            commitments.append(unit)
        _fail(all(type(order) is int for order in orders) and orders == sorted(set(orders)),
              'native_wire_source_order_mismatch')
        _fail(commitments == canonical['units'], 'native_wire_chunk_commitments_mismatch')
        _fail(public['sourceHash'] == _hash({'projectionIdentity': public['projectionIdentity'], 'canonical': canonical})
              and public['projectionRevisionSha256'] == _hash({'sourceHash': public['sourceHash'],
                  'projectionIdentity': public['projectionIdentity']}), 'native_wire_public_revision_mismatch')
        return public
    except CanonicalProjectionError:
        raise
    except (ValueError, OSError, KeyError, TypeError, IndexError, AttributeError) as error:
        raise CanonicalProjectionError('malformed_native_collection_wire', reason=str(error)) from error
