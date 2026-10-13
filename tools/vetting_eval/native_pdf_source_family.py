"""One explicitly derived source over complete, cold-verified native-PDF roots.

This does not join OCR runs, migrate native observations or review legal quality.
Exact child public bytes and their full actual-CAS proofs remain unchanged.
"""
from __future__ import annotations

import copy
import hashlib
from pathlib import Path
import re

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _fail, _hash
from competition_scope import CompetitionScopeError
from native_observation import _strict_json
from native_pdf_projection_transport import SCHEMA as CHILD_SCHEMA, STORAGE_SHAPE as CHILD_STORAGE
from native_pdf_projection_transport import MAX_SNAPSHOT_BYTES, validate_native_pdf_transport

SCHEMA = 'consense.native-pdf-source-family.v1'
SPEC_SCHEMA = 'consense.native-pdf-source-family-spec.v1'
VERSION = 'same-original-complete-native-pdf-components-v1'
PRODUCER = 'native_pdf_source_family'
STORAGE_SHAPE = 'cas-same-original-native-pdf-complete-components-public-v1'
REVISION_KIND = 'derived_same_original_component_set'
MAX_COMPONENTS = 16
_HERE = Path(__file__).resolve().parent
_DEPENDENCIES = ('native_pdf_source_family.py', 'native_pdf_projection_transport.py',
    'native_pdf_canonical_projection.py', 'native_pdf_table_projection.py', 'artifact_store.py', 'canonical_projection.py',
    'competition_scope.py', 'native_pdf_observation.py', 'source_reference.py',
    'structured_parser_evidence.py', 'token_windows.py', 'parser_capture.py')
_IMPORTED_BYTES = {name: (_HERE / name).read_bytes() for name in _DEPENDENCIES}
_IMPORTED_SHA = {name: hashlib.sha256(raw).hexdigest() for name, raw in _IMPORTED_BYTES.items()}
_SPEC_FIELDS = {'schema', 'documentId', 'originalSourceSha256', 'scopePolicySha256', 'components'}
_PIN_FIELDS = {'projectionSha256', 'publicBytesSha256', 'publicByteCount'}
_IDENTITY_FIELDS = {'id', 'unitId', 'documentId', 'sourceHash', 'projectionRevisionSha256',
    'sourceRevisionSha256', 'projectionVersion', 'metadataVersion', 'sourceQuality',
    'sourceQualityHash', 'sourceQualityMetadataVersion', 'producerKind', 'storageShape',
    'scopeSha256', 'module', 'eligibleModules', 'relations'}


def _boundary(operation):
    try:
        return operation()
    except CanonicalProjectionError:
        raise
    except (ValueError, OSError, KeyError, TypeError, IndexError, AttributeError) as error:
        raise CanonicalProjectionError('malformed_native_pdf_source_family', reason=str(error)) from error


def _sha(value, code):
    _fail(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None, code)
    return value


def _fields(value, fields, code):
    _fail(isinstance(value, dict) and set(value) == fields, code)


def _bounded(value, maximum):
    _fail(type(maximum) is int and 0 < maximum <= MAX_SNAPSHOT_BYTES,
          'source_family_public_limit_invalid')
    raw = _bytes(value)
    _fail(len(raw) <= maximum, 'source_family_public_too_large',
          byteCount=len(raw), maximumBytes=maximum)
    return raw


def source_family_candidate(value):
    """Independent markers forbid stripping the outer schema to gain fallback."""
    if isinstance(value, list):
        return any(source_family_candidate(row) for row in value)
    if not isinstance(value, dict):
        return False
    if (value.get('schema') in (SCHEMA, SPEC_SCHEMA) or value.get('producerKind') == PRODUCER
            or value.get('storageShape') == STORAGE_SHAPE or 'sourceFamilyOriginRef' in value
            or 'sourceFamilyComponents' in value or value.get('sourceRevisionKind') == REVISION_KIND
            or isinstance(value.get('id'), str) and value['id'].startswith('canonical-native-pdf-family:')):
        return True
    return any(source_family_candidate(row) for row in value.values() if isinstance(row, (dict, list)))


def _software(store, *, capture):
    refs = {}
    for name, raw in _IMPORTED_BYTES.items():
        _fail((_HERE / name).read_bytes() == raw, 'source_family_software_changed', dependency=name)
        if capture:
            refs[name] = store.put_bytes(raw)
        else:
            sha = _IMPORTED_SHA[name]
            refs[name] = {'schema': 'consense.artifact-descriptor.v1',
                'storage': 'content_addressed_disk', 'identityScope': 'exact_supplied_bytes',
                'sha256': sha, 'byteCount': len(raw),
                'relativePath': 'sha256/' + sha[:2] + '/' + sha + '.blob'}
            if capture is False:
                _fail(store.read_asset(refs[name]) == raw, 'source_family_actual_software_mismatch')
    return refs


def _spec(spec, policy, target_module):
    _fields(spec, _SPEC_FIELDS, 'source_family_spec_fields_invalid')
    _fail(spec['schema'] == SPEC_SCHEMA, 'source_family_spec_schema_invalid')
    _fail(isinstance(spec['documentId'], str) and spec['documentId'].strip(),
          'source_family_actual_document_required')
    _sha(spec['originalSourceSha256'], 'source_family_original_sha_invalid')
    _sha(spec['scopePolicySha256'], 'source_family_policy_sha_invalid')
    _fail(target_module in ('vetting', 'advisory'), 'source_family_module_invalid')
    _fail(spec['scopePolicySha256'] == policy.policyIdentity['manifestSha256'],
          'source_family_active_policy_mismatch')
    pins = spec['components']
    _fail(isinstance(pins, list) and 2 <= len(pins) <= MAX_COMPONENTS,
          'source_family_complete_components_required')
    seen = set()
    for pin in pins:
        _fields(pin, _PIN_FIELDS, 'source_family_component_pin_invalid')
        address = _sha(pin['projectionSha256'], 'source_family_component_address_invalid')
        _sha(pin['publicBytesSha256'], 'source_family_component_bytes_sha_invalid')
        _fail(type(pin['publicByteCount']) is int and 0 < pin['publicByteCount'] <= MAX_SNAPSHOT_BYTES,
              'source_family_component_bytes_limit_invalid')
        _fail(address not in seen, 'source_family_duplicate_component')
        seen.add(address)


def _children(spec, registry, store, policy, target_module):
    children, refs = [], []
    for pin in spec['components']:
        # A public root must exist in the registry as well as in CAS. A supplied
        # /index object or an unresolved-looking originRef is never authority.
        path = registry._path(pin['projectionSha256'])
        _fail(path.stat().st_size == pin['publicByteCount'], 'source_family_child_bytes_mismatch')
        raw = path.read_bytes()
        _fail(len(raw) <= MAX_SNAPSHOT_BYTES and hashlib.sha256(raw).hexdigest() == pin['publicBytesSha256'],
              'source_family_child_bytes_mismatch')
        parsed = _strict_json(raw)
        _fail(isinstance(parsed, dict) and parsed.get('schema') == CHILD_SCHEMA
              and parsed.get('producerKind') == 'native_pdf' and parsed.get('storageShape') == CHILD_STORAGE,
              'source_family_child_kind_invalid')
        # Pin the requested path before full reconstruction so a mid-read
        # locator retarget cannot hide behind an unchanged old target.
        if callable(getattr(registry, '_pin_frame_file', None)):
            registry._pin_frame_file(path, raw)
        # Always execute the original full actual-CAS child validator. No
        # registry fixture/caching adapter can attest a child by returning it.
        validate_native_pdf_transport(parsed, artifact_store=store)
        # Pin a known request frame too. The direct child validator is the one
        # authoritative cold read, avoiding a second registry hydration.
        identity = parsed['projectionIdentity']
        _fail(parsed['source']['sourceSha256'] == spec['originalSourceSha256'],
              'source_family_cross_original_component')
        _fail(parsed['manifest']['scope']['scopePolicySha256'] == spec['scopePolicySha256'],
              'source_family_child_policy_mismatch')
        _fail(target_module in identity['eligibleModules']
              and all(target_module in chunk['eligibleModules'] for chunk in parsed['chunks']),
              'source_family_child_module_excluded')
        scoped = policy.apply(parsed['chunks'], module=target_module)
        _fail(not scoped['excluded'] and scoped['included'] == parsed['chunks'],
              'source_family_child_scope_excluded')
        children.append(parsed)
        refs.append({'pin': copy.deepcopy(pin), 'raw': raw, 'path': path})
    return children, refs


def _current(refs):
    for ref in refs:
        _fail(ref['path'].read_bytes() == ref['raw'], 'source_family_child_changed_during_read')
    for name, raw in _IMPORTED_BYTES.items():
        _fail((_HERE / name).read_bytes() == raw, 'source_family_software_changed', dependency=name)


def _relations(child, unitmap):
    mapped = []
    for relation in child['canonical']['relations']:
        body = copy.deepcopy(relation)
        _fail(body.get('fromUnitId') in unitmap and body.get('toUnitId') in unitmap,
              'source_family_child_relation_endpoint_missing')
        body['fromUnitId'], body['toUnitId'] = unitmap[body['fromUnitId']], unitmap[body['toUnitId']]
        mapped.append(body)
    return mapped


def _layout(spec, children, refs, software, *, store, capture, target_module):
    first = children[0]
    # Physical source metadata is exact. Different native/dual revisions and
    # sourceClass spelling remain explicit component identities.
    physical = {key: value for key, value in first['source'].items() if key != 'sourceRevisionSha256'}
    source_ref = first['nativeCaptureRefs']['sourcePackage']
    _fail(source_ref['sha256'] == spec['originalSourceSha256'], 'source_family_original_descriptor_mismatch')
    _fail(store.read_asset(source_ref) and source_ref['byteCount'] == physical['byteCount'],
          'source_family_original_descriptor_mismatch')
    roles, purposes = {c['projectionIdentity']['role'] for c in children}, {
        c['projectionIdentity']['sourcePurpose'] for c in children}
    _fail(len(roles) == len(purposes) == 1, 'source_family_component_routing_conflict')
    _fail(purposes <= {'project_document', 'reference_standard'}, 'source_family_purpose_excluded')
    eligible = sorted(set.intersection(*(set(c['projectionIdentity']['eligibleModules']) for c in children)))
    _fail(target_module in eligible, 'source_family_module_excluded')
    # Consumer authorization was checked against every cold-validated child.
    # Ordered source declarations, rather than the reading process, define the
    # immutable family creation identity. Never choose a substitute module.
    creation_module = first['projectionIdentity']['module']
    _fail(creation_module in ('vetting', 'advisory'), 'source_family_declared_module_invalid')
    _fail(creation_module in eligible, 'source_family_declared_module_excluded')
    component_records, bodies, all_relations, assets = [], [], [], []
    unit_ids, clause_ids, blocks, regions, adopted_pages = set(), set(), set(), set(), set()
    for ordinal, (child, ref) in enumerate(zip(children, refs)):
        actual_physical = {key: value for key, value in child['source'].items() if key != 'sourceRevisionSha256'}
        _fail(actual_physical == physical and child['nativeCaptureRefs']['sourcePackage'] == source_ref,
              'source_family_component_physical_source_differs')
        chunks = child['chunks']
        _fail(bool(chunks), 'source_family_empty_child')
        descriptor = store.put_bytes(ref['raw']) if capture else {
            'schema': 'consense.artifact-descriptor.v1', 'storage': 'content_addressed_disk',
            'identityScope': 'exact_supplied_bytes', 'sha256': ref['pin']['publicBytesSha256'],
            'byteCount': ref['pin']['publicByteCount'],
            'relativePath': 'sha256/' + ref['pin']['publicBytesSha256'][:2] + '/' + ref['pin']['publicBytesSha256'] + '.blob'}
        if capture is False:
            _fail(store.read_asset(descriptor) == ref['raw'], 'source_family_child_actual_public_bytes_mismatch')
        record = {'ordinal': ordinal, 'publicRootRef': descriptor,
            **copy.deepcopy(ref['pin']), 'projectionIdentity': copy.deepcopy(child['projectionIdentity']),
            'source': copy.deepcopy(child['source']), 'sourceHash': child['sourceHash'],
            'projectionRevisionSha256': child['projectionRevisionSha256'],
            'scope': copy.deepcopy(child['manifest']['scope']),
            'unitCount': len(chunks), 'orderedChildIds': [c['id'] for c in chunks],
            'orderedChildUnitIds': [c['unitId'] for c in chunks],
            'fullChildrenSha256': _hash(chunks),
            'sourceQuality': copy.deepcopy(chunks[0]['sourceQuality']),
            'sourceQualityHash': chunks[0]['sourceQualityHash'],
            'captureCompleteness': copy.deepcopy(child['captureCompleteness']),
            'unadoptedGlyphsRef': copy.deepcopy(child['unadoptedGlyphsRef']),
            'unadoptedGlyphSummary': copy.deepcopy(child['unadoptedGlyphSummary']),
            'unobservedOriginalPhysicalPages': copy.deepcopy(child['unobservedOriginalPhysicalPages']),
            'wholeOriginalSourceCoverage': child['wholeOriginalSourceCoverage'],
            'limitations': copy.deepcopy(child['limitations'])}
        component_records.append(record)
        assets.extend(copy.deepcopy(child['assetRefs']))
        unitmap = {c['unitId']: 'family-unit:' + _hash({'component': child['projectionSha256'], 'unit': c['unitId']}) for c in chunks}
        child_relations = _relations(child, unitmap)
        all_relations += child_relations
        local_clauses, local_blocks, local_regions, local_pages = set(), set(), set(), set()
        for position, chunk in enumerate(chunks):
            _fail(chunk['unitId'] not in unit_ids, 'source_family_duplicate_unit')
            unit_ids.add(chunk['unitId'])
            if chunk.get('clauseId'): local_clauses.add(chunk['clauseId'])
            for part in chunk['parts']:
                local_blocks.add(part['blockId']); local_regions.add(part['referenceRegionId'])
                # Narrow v1 requires disjoint adopted physical pages across
                # components. Unknown native glyph identity migration cannot
                # be guessed to authorize two captures adopting the same page.
                # Do not repeatedly parse a 500MB internal object per part.
                local_pages.add(part['physicalPageNo'])
            body = {key: copy.deepcopy(value) for key, value in chunk.items() if key not in _IDENTITY_FIELDS}
            body.update(unitId=unitmap[chunk['unitId']], producerKind=PRODUCER, storageShape=STORAGE_SHAPE,
                metadataVersion=VERSION, projectionVersion=VERSION, sourceQualityMetadataVersion=VERSION,
                documentId=spec['documentId'], module=creation_module, eligibleModules=eligible,
                sourceFamilyOriginRef={'componentOrdinal': ordinal, 'childProjectionSha256': child['projectionSha256'],
                    'publicRootRef': descriptor, 'childChunkOrdinal': position, 'childChunkId': chunk['id'],
                    'childUnitId': chunk['unitId'], 'fullChildChunkSha256': _hash(chunk),
                    'childIdentity': {key: copy.deepcopy(chunk[key]) for key in _IDENTITY_FIELDS if key in chunk}},
                relations=[copy.deepcopy(r) for r in child_relations if
                    r['fromUnitId'] == unitmap[chunk['unitId']] or r['toUnitId'] == unitmap[chunk['unitId']]])
            bodies.append(body)
        _fail(not (clause_ids & local_clauses), 'source_family_duplicate_clause')
        _fail(not (blocks & local_blocks) and not (regions & local_regions), 'source_family_region_collision')
        _fail(not (adopted_pages & local_pages), 'source_family_adopted_page_overlap_requires_review')
        clause_ids |= local_clauses; blocks |= local_blocks; regions |= local_regions; adopted_pages |= local_pages
    _fail(len({asset['assetId'] for asset in assets}) == len(assets), 'source_family_asset_identity_collision')
    quality = {'version': VERSION, 'parseStatus': 'PARSED', 'semanticQuality': 'unknown',
        'humanConfirmed': False, 'wholeSourceAccepted': False, 'legalQualityAccepted': False,
        'meaning': 'source identity composition only; unchanged component qualities retained',
        'components': [{'ordinal': r['ordinal'], 'sourceQuality': r['sourceQuality'],
                         'sourceQualityHash': r['sourceQualityHash']} for r in component_records]}
    composition = {'schema': SPEC_SCHEMA, 'spec': copy.deepcopy(spec), 'sourceFamilyComponents': component_records,
        'scope': {'scopePolicySha256': spec['scopePolicySha256']}, 'quality': quality,
        'reference': {'actor': 'codex', 'method': 'component_identity_composition_review',
            'humanConfirmed': False, 'legalQualityAccepted': False}, 'software': software}
    revision = _hash({'version': VERSION, 'composition': composition})
    identity = {'documentId': spec['documentId'], 'sourceSha256': spec['originalSourceSha256'],
        'sourceRevisionSha256': revision, 'sourceRevisionKind': REVISION_KIND,
        'projectionVersion': VERSION, 'module': creation_module, 'eligibleModules': eligible,
        'role': next(iter(roles)), 'sourcePurpose': next(iter(purposes)),
        'sourceClass': first['projectionIdentity']['sourceClass'], 'compositionSha256': _hash(composition),
        'componentSetSha256': _hash(spec['components']), 'scopeSha256': _hash(composition['scope']),
        'qualitySha256': _hash(quality), 'projectionDependencySha256': _IMPORTED_SHA}
    for body in bodies:
        body.update(sourceRevisionSha256=revision, sourceRevisionKind=REVISION_KIND,
            scopeSha256=identity['scopeSha256'], sourceQuality=copy.deepcopy(quality), sourceQualityHash=_hash(quality))
    canonical = {'version': VERSION, 'units': [{
        'unitId': b['unitId'], 'chunkPreIdentitySha256': _hash(b),
        'partsSha256': _hash(b['parts']), 'originRefSha256': _hash(b['sourceFamilyOriginRef'])} for b in bodies],
        'relations': all_relations, 'assetRefs': assets}
    source_hash = _hash({'projectionIdentity': identity, 'canonical': canonical})
    projection_revision = _hash({'sourceHash': source_hash, 'projectionIdentity': identity})
    chunks = [{**body, 'id': 'canonical-native-pdf-family:' + _hash({'revision': projection_revision, 'unitId': body['unitId']}),
        'sourceHash': source_hash, 'projectionRevisionSha256': projection_revision} for body in bodies]
    result = {'schema': SCHEMA, 'producerKind': PRODUCER, 'storageShape': STORAGE_SHAPE,
        'projectionIdentity': identity, 'projectionRevisionSha256': projection_revision, 'sourceHash': source_hash,
        'source': {**physical, 'sourceRevisionSha256': revision, 'sourceRevisionKind': REVISION_KIND},
        'originalSourceRef': copy.deepcopy(source_ref), 'manifest': composition,
        'sourceFamilyComponents': component_records, 'canonical': canonical, 'chunks': chunks,
        'assetRefs': assets, 'humanConfirmed': False, 'semanticScopeVerified': False,
        'wholeOriginalSourceCoverage': 'unknown', 'visualReturnStatus': 'not_published_by_source_family',
        'limitations': ['Same original identity composition only; no new OCR run, legal acceptance, cross-component ancestry or visual publication.',
                        'Full per-component unadopted inventories remain separate; no undocumented glyph identity migration.']}
    result['projectionSha256'] = _hash(result)
    return result


def compose_native_pdf_source_family(spec, *, registry, artifact_store, policy, target_module='vetting',
                                     max_snapshot_bytes=MAX_SNAPSHOT_BYTES):
    def run():
        _fail(isinstance(artifact_store, ArtifactStore), 'source_family_actual_store_required')
        _spec(spec, policy, target_module); _bounded({}, max_snapshot_bytes)
        with artifact_store.validation_session():
            children, refs = _children(spec, registry, artifact_store, policy, target_module)
            # Full layout/cap validation happens before any family CAS writes.
            expected_software = _software(artifact_store, capture=None)
            planned = _layout(spec, children, refs, expected_software, store=artifact_store,
                              capture=None, target_module=target_module)
            _bounded(planned, max_snapshot_bytes)
            software = _software(artifact_store, capture=True)
            _fail(software == expected_software, 'source_family_software_descriptor_mismatch')
            result = _layout(spec, children, refs, software, store=artifact_store, capture=True, target_module=target_module)
            _fail(result == planned, 'source_family_capture_layout_mismatch')
            _current(refs)
        _current(refs)
        return validate_native_pdf_source_family(result, registry=registry, artifact_store=artifact_store,
            policy=policy, target_module=target_module, max_snapshot_bytes=max_snapshot_bytes)
    return _boundary(run)


def validate_native_pdf_source_family(root, *, registry, artifact_store, policy, target_module='vetting',
                                      max_snapshot_bytes=MAX_SNAPSHOT_BYTES):
    def run():
        _fail(isinstance(artifact_store, ArtifactStore), 'source_family_actual_store_required')
        _bounded(root, max_snapshot_bytes)
        _fail(isinstance(root, dict) and root.get('schema') == SCHEMA and root.get('producerKind') == PRODUCER
              and root.get('storageShape') == STORAGE_SHAPE, 'source_family_kind_invalid')
        _fail(root.get('projectionSha256') == _hash({k:v for k,v in root.items() if k!='projectionSha256'}),
              'source_family_public_seal_mismatch')
        spec = root['manifest']['spec']; _spec(spec, policy, target_module)
        with artifact_store.validation_session():
            children, refs = _children(spec, registry, artifact_store, policy, target_module)
            software = _software(artifact_store, capture=False)
            expected = _layout(spec, children, refs, software, store=artifact_store, capture=False, target_module=target_module)
            _fail(root == expected, 'source_family_derived_payload_mismatch')
            _current(refs)
        _current(refs)
        return copy.deepcopy(root)
    return _boundary(run)
