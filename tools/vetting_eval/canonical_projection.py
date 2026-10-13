"""Project source-reviewed whole units without rewriting either parser candidate.

This seam runs no OCR, models, index, network, or business service. A supplied
source review is a caller-owned trust decision, never a human-authenticity claim.
Complete capture, actual reference bytes, mapping and whole-group membership are
checked before any canonical chunk is returned. Unreviewed properties stay unknown.
"""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import re

from token_windows import paragraph_map, utf16_length

SCHEMA = 'consense.canonical-projection.v1'
ADOPTION_SCHEMA = 'consense.canonical-adoption.v1'
VERSION = 'source-reviewed-whole-unit-projection-v1'
ROLES = {'tender', 'standard', 'project_fact', 'package_manifest'}
MODULES = {'drafting', 'vetting', 'advisory'}
ATTRIBUTES = {'text', 'geometry', 'tableTopology', 'assetBoundary', 'clauseHierarchy'}
UNKNOWN_ATTRIBUTES = ('semanticQuality', 'editState', 'blankMeaning', 'headerRelations',
                      'crossPageRelations', 'qualifiersComplete')


class CanonicalProjectionError(ValueError):
    def __init__(self, code, **details):
        self.code, self.details = code, details
        super().__init__(code)


def _fail(condition, code, **details):
    if not condition:
        raise CanonicalProjectionError(code, **details)


def _bytes(value):
    try:
        return json.dumps(value, sort_keys=True, ensure_ascii=False,
                          separators=(',', ':'), allow_nan=False).encode('utf-8')
    except (TypeError, ValueError) as error:
        raise CanonicalProjectionError('non_json_projection', reason=str(error)) from error


def _hash(value):
    return hashlib.sha256(_bytes(value)).hexdigest()


def _sha(value, field):
    _fail(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value),
          'invalid_sha256', field=field)
    return value


def _name(value, field):
    _fail(isinstance(value, str) and bool(value.strip()), 'missing_identity', field=field)
    return value


def _fields(value, allowed, code):
    _fail(isinstance(value, dict) and not set(value) - set(allowed), code,
          unknownFields=sorted(set(value) - set(allowed)) if isinstance(value, dict) else [])


def _pages(value, count, field):
    _fail(isinstance(value, list) and value and all(type(p) is int and 1 <= p <= count for p in value)
          and value == sorted(set(value)), 'invalid_physical_pages', field=field)
    return value


def _reference(reference, source, required_pages, artifact_store):
    _fields(reference, {'actor', 'method', 'sourceSha256', 'referenceVersion', 'evidenceSha256',
                        'physicalPages', 'artifactDescriptor', 'evidence'}, 'illegal_reference')
    _fail(reference.get('actor') == 'codex' and reference.get('method') == 'original_source_review',
          'source_review_required')
    _fail(reference.get('sourceSha256') == source['sourceSha256'], 'reference_source_mismatch')
    _name(reference.get('referenceVersion'), 'referenceVersion')
    _sha(reference.get('evidenceSha256'), 'reference.evidenceSha256')
    pages = _pages(reference.get('physicalPages'), source['pageCount'], 'reference.physicalPages')
    _fail(set(required_pages) <= set(pages), 'reference_page_coverage_missing')
    _fail(('artifactDescriptor' in reference) != ('evidence' in reference), 'actual_reference_bytes_required')
    if 'evidence' in reference:
        _fail(isinstance(reference['evidence'], dict) and bool(reference['evidence']), 'invalid_reference_evidence')
        _fail(_hash(reference['evidence']) == reference['evidenceSha256'], 'reference_bytes_hash_mismatch')
        embedded_source = reference['evidence'].get('sourceSha256')
        _fail(embedded_source in (None, source['sourceSha256']), 'reference_source_mismatch')
    else:
        descriptor = reference['artifactDescriptor']
        _fail(artifact_store is not None and isinstance(descriptor, dict), 'reference_store_required')
        _fail(descriptor.get('sha256') == reference['evidenceSha256'], 'reference_bytes_hash_mismatch')
        try:
            artifact_store.verify(descriptor)
        except (ValueError, OSError) as error:
            raise CanonicalProjectionError('reference_bytes_unavailable', reason=str(error)) from error
    return copy.deepcopy(reference)


def _quality(value):
    _fields(value, {'parseStatus', 'semanticQuality', 'attributes', 'notes', 'version'}, 'illegal_quality_override')
    _fail(value.get('parseStatus') == 'PARSED' and value.get('semanticQuality') == 'unknown',
          'unknown_quality_cannot_be_upgraded')
    attributes = value.get('attributes', {})
    _fail(isinstance(attributes, dict) and set(attributes) <= set(UNKNOWN_ATTRIBUTES)
          and all(status == 'unknown' for status in attributes.values()), 'unknown_quality_cannot_be_upgraded')
    result = copy.deepcopy(value)
    result['attributes'] = {name: 'unknown' for name in UNKNOWN_ATTRIBUTES}
    result['reviewMeaning'] = 'Explicit source-reviewed selection; no whole-source semantic verification'
    return result


def _member_index(bundle):
    runs, blocks = {}, {}
    for run in bundle['runs']:
        runs[run['runId']] = run
        for page in run['pages']:
            for block in page['blocks']:
                _fail(block['blockId'] not in blocks, 'duplicate_candidate_block')
                blocks[block['blockId']] = (run, page, block)
    return runs, blocks


def _table_groups(groups, blocks, required_pages, source):
    _fail(isinstance(groups, list), 'table_groups_required')
    result = {}
    for group in groups:
        _fields(group, {'tableGroupId', 'physicalPages', 'candidates'}, 'illegal_table_group')
        key = _name(group.get('tableGroupId'), 'tableGroupId')
        _fail(key not in result, 'duplicate_table_group')
        pages = _pages(group.get('physicalPages'), source['pageCount'], 'group.physicalPages')
        _fail(set(pages) <= set(required_pages), 'table_group_outside_projection_scope')
        _fail(isinstance(group.get('candidates'), dict) and group['candidates'], 'table_group_candidates_missing')
        for run_id, members in group['candidates'].items():
            _fail(isinstance(members, list) and members and all(isinstance(member, str) for member in members)
                  and len(set(members)) == len(members),
                  'invalid_table_group_members')
            _fail(all(isinstance(member, str) and member in blocks for member in members), 'unknown_table_member')
            found_pages = []
            for member in members:
                run, page, block = blocks[member]
                _fail(run['runId'] == run_id and 'table' in block, 'table_group_mixed_run_or_nontable')
                found_pages.append(page['physicalPageNo'])
            _fail(sorted(set(found_pages)) == pages, 'partial_table_group')
            _fail(found_pages == sorted(found_pages), 'table_group_source_order_required')
        result[key] = copy.deepcopy(group)
    return result


def _part(run, page, block):
    from structured_parser_evidence import project_bbox_to_original
    missing_image_text = block.get('text') is None and block.get('label') in {'image', 'figure', 'chart'}
    _fail(isinstance(block.get('text'), str) or missing_image_text,
          'unavailable_candidate_text', blockId=block['blockId'])
    geometry = project_bbox_to_original(run, page, block.get('bbox'))
    _fail(geometry.get('status') == 'verified', 'original_geometry_unknown',
          blockId=block['blockId'], geometry=geometry)
    text, physical = '' if missing_image_text else block['text'], page['physicalPageNo']
    anchor = 'page/' + str(physical) + '/' + block['blockId']
    result = {'blockId': block['blockId'], 'anchor': anchor, 'text': text,
              'candidateLabel': copy.deepcopy(block.get('label', 'unknown')),
              'startOffset': 0, 'endOffset': utf16_length(text), 'physicalPageNo': physical,
              'sourceSha256': run['source']['sourceSha256'], 'bbox': copy.deepcopy(geometry['xyxyNormalized']),
              'bboxConvention': 'normalized_xyxy_original_page', 'geometryEvidence': geometry,
              'candidateProvenance': {'runId': run['runId'], 'parser': run['parser'],
                  'parserIdentitySha256': run['identity']['identitySha256'],
                  'configSha256': run['identity']['configSha256'],
                  'raw': copy.deepcopy(block.get('provenance')), 'providerBlockId': block.get('providerBlockId')},
              'declaredRelationships': copy.deepcopy(block.get('relationships', {})),
              'qualityAttributes': {name: 'unknown' for name in UNKNOWN_ATTRIBUTES}, 'native': False}
    result['candidateTextStatus'] = 'unavailable_source_observation' if missing_image_text else 'parser_observed_unverified'
    if 'table' in block:
        table = block['table']
        _fail(table.get('structureStatus') != 'unknown' and isinstance(table.get('cells'), list)
              and table['cells'] and not table.get('issues'), 'unknown_table_structure')
        result['table'] = copy.deepcopy(table)
        result['cells'] = copy.deepcopy(table['cells'])
        result['nativeCell'], result['nativeTable'] = False, False
    return result


def _relations(rows, units, reference):
    _fail(isinstance(rows, list), 'relations_must_be_explicit')
    result, parents = [], {}
    for row in rows:
        _fields(row, {'type', 'fromUnitId', 'toUnitId', 'fromBlockId', 'toBlockId',
                      'fromCellId', 'toCellId'}, 'illegal_relation_override')
        _fail(row.get('type') in {'parent', 'continuation', 'header', 'qualifier', 'caption', 'sidebar', 'margin_note'}, 'unknown_relation_type')
        left, right = row.get('fromUnitId'), row.get('toUnitId')
        _fail(left in units and right in units, 'relation_member_unselected')
        for side, unit in (('from', units[left]), ('to', units[right])):
            block = row.get(side + 'BlockId')
            _fail(block is None or block in {p['blockId'] for p in unit['parts']}, 'relation_block_outside_unit')
            cell = row.get(side + 'CellId')
            cells = {c['cellId'] for p in unit['parts'] for c in p.get('cells', [])}
            _fail(cell is None or cell in cells, 'relation_cell_outside_unit')
        if row['type'] == 'parent':
            _fail(left != right and left not in parents, 'ambiguous_parent_relation')
            parents[left] = right
        result.append({**copy.deepcopy(row), 'status': 'explicit_source_reviewed',
                       'referenceSha256': reference['evidenceSha256']})
    for seed in parents:
        seen, cursor = set(), seed
        while cursor in parents:
            _fail(cursor not in seen, 'cyclic_parent_relation')
            seen.add(cursor)
            cursor = parents[cursor]
    _fail(len({_hash(row) for row in result}) == len(result), 'duplicate_relation')
    return result


def _assets(rows, units, runs, reference, artifact_store):
    from structured_parser_evidence import read_artifact_record
    _fail(isinstance(rows, list), 'assets_must_be_explicit')
    result = []
    for row in rows:
        _fields(row, {'assetId', 'unitId', 'runId', 'artifactName', 'physicalPages', 'mediaType',
                      'blockIds'}, 'illegal_asset_reference')
        unit = units.get(row.get('unitId'))
        _fail(unit is not None and row.get('runId') == unit['runId'], 'asset_adoption_mismatch')
        _fail('assetBoundary' in unit['reviewedAttributes'], 'asset_boundary_not_source_reviewed')
        pages = row.get('physicalPages')
        _fail(isinstance(pages, list) and len(pages) == 1 and type(pages[0]) is int
              and pages[0] in unit['physicalPages'], 'asset_page_mapping_unknown')
        member_parts = [p for p in unit['parts'] if p['physicalPageNo'] == pages[0]]
        ids = row.get('blockIds')
        _fail(isinstance(ids, list) and ids and len(set(ids)) == len(ids)
              and set(ids) <= {p['blockId'] for p in member_parts}, 'asset_block_mapping_unknown')
        _fail(row.get('mediaType') in {'image/png', 'image/jpeg', 'image/webp'}, 'unsupported_asset_media')
        page = next(p for p in runs[row['runId']]['pages'] if p['physicalPageNo'] == pages[0])
        _fail(row.get('artifactName') == page.get('inputArtifact', {}).get('name'), 'asset_location_unbound')
        record = runs[row['runId']].get('artifacts', {}).get(row.get('artifactName'))
        _fail(isinstance(record, dict), 'asset_bytes_unavailable')
        try:
            raw = read_artifact_record(record, artifact_store=artifact_store)
            descriptor = record.get('artifactDescriptor')
            if descriptor is None:
                _fail(artifact_store is not None, 'asset_store_required')
                descriptor = artifact_store.put_bytes(raw, identity_scope=record.get('identityScope', 'exact_supplied_bytes'))
        except (ValueError, OSError) as error:
            raise CanonicalProjectionError('asset_bytes_unavailable', reason=str(error)) from error
        identity = {'sourceSha256': runs[row['runId']]['source']['sourceSha256'], 'unitId': row['unitId'],
                    'runId': row['runId'], 'physicalPages': pages, 'blockIds': ids,
                    'sha256': record['sha256'], 'mediaType': row['mediaType']}
        asset_id = 'asset:' + _hash(identity)
        _fail(row.get('assetId') in (None, asset_id), 'asset_id_mismatch')
        result.append({**identity, 'assetId': asset_id, 'byteCount': record['byteCount'],
                       'artifactDescriptor': copy.deepcopy(descriptor), 'artifactName': row['artifactName'],
                       'sourceRegions': [{'blockId': p['blockId'], 'bbox': p['bbox'],
                                          'bboxConvention': p['bboxConvention']} for p in member_parts if p['blockId'] in ids],
                       'referenceSha256': reference['evidenceSha256'], 'humanConfirmed': False})
    _fail(len({a['assetId'] for a in result}) == len(result), 'duplicate_asset_reference')
    for unit in units.values():
        assets = [a for a in result if a['unitId'] == unit['unitId']]
        if assets and unit['scope'] == 'table_group':
            _fail(sorted({p for a in assets for p in a['physicalPages']}) == unit['physicalPages'],
                  'partial_table_group_assets')
            _fail({b for a in assets for b in a['blockIds']} == {p['blockId'] for p in unit['parts']},
                  'partial_table_group_asset_regions')
    return sorted(result, key=lambda a: (a['physicalPages'][0], a['unitId'], a['assetId']))


def _project_canonical(bundle, adoption_manifest, *, artifact_store=None):
    """Return sealed source-bound chunks; the explicit review never authenticates itself."""
    from structured_parser_evidence import assert_complete_dual
    _fail(isinstance(bundle, dict) and isinstance(bundle.get('source'), dict), 'invalid_dual_bundle')
    manifest = copy.deepcopy(adoption_manifest)
    _fields(manifest, {'schema', 'bundleSha256', 'sourceSha256', 'sourceRevisionSha256', 'documentId',
                      'role', 'module', 'sourceClass', 'sourcePurpose', 'eligibleModules', 'scope',
                      'quality', 'reference', 'tableGroups', 'units', 'relations', 'assets'}, 'illegal_adoption_manifest')
    _fail(manifest.get('schema') == ADOPTION_SCHEMA, 'unsupported_adoption_schema')
    source = bundle.get('source', {})
    _fail(manifest.get('bundleSha256') == bundle.get('bundleSha256'), 'stale_bundle_adoption')
    for name in ('sourceSha256', 'sourceRevisionSha256'):
        _sha(manifest.get(name), name)
        _fail(manifest[name] == source.get(name), 'source_adoption_mismatch', field=name)
    document_id = _name(manifest.get('documentId'), 'documentId')
    role, module = manifest.get('role'), manifest.get('module')
    _fail(role in ROLES and module in MODULES, 'invalid_document_role_or_module')
    purpose = manifest.get('sourcePurpose', 'unknown')
    _fail(purpose in {'standard_template', 'project_document', 'evaluation_answer', 'reference_standard',
                     'project_fact', 'package_manifest', 'unknown'}, 'invalid_source_purpose')
    eligible = manifest.get('eligibleModules', [module])
    _fail(isinstance(eligible, list) and eligible and all(isinstance(item, str) for item in eligible)
          and len(set(eligible)) == len(eligible)
          and set(eligible) <= MODULES and module in eligible, 'invalid_module_eligibility')
    _fail(purpose != 'evaluation_answer' and not (purpose == 'standard_template'
          and (module != 'drafting' or set(eligible) != {'drafting'})), 'module_source_not_eligible')
    scope = manifest.get('scope')
    _fields(scope, {'requiredPhysicalPages', 'scopePolicySha256'}, 'invalid_projection_scope')
    pages = _pages(scope.get('requiredPhysicalPages'), source.get('pageCount', 0), 'requiredPhysicalPages')
    _sha(scope.get('scopePolicySha256'), 'scopePolicySha256')
    try:
        completeness = assert_complete_dual(bundle, pages, artifact_store=artifact_store)
    except (ValueError, OSError) as error:
        raise CanonicalProjectionError('dual_capture_incomplete', reason=str(error)) from error
    reference = _reference(manifest.get('reference'), source, pages, artifact_store)
    quality = _quality(manifest.get('quality'))
    runs, blocks = _member_index(bundle)
    groups = _table_groups(manifest.get('tableGroups', []), blocks, pages, source)
    rows = manifest.get('units')
    _fail(isinstance(rows, list) and rows, 'explicit_adoption_required')
    units, used, used_groups = {}, set(), set()
    for row in rows:
        _fields(row, {'unitId', 'scope', 'runId', 'blockId', 'associatedBlockIds', 'tableGroupId',
                      'reviewedAttributes', 'clause'},
                'illegal_unit_adoption')
        uid = _name(row.get('unitId'), 'unitId')
        _fail(uid not in units, 'duplicate_unit_id')
        _fail(row.get('runId') in runs, 'unknown_adopted_run')
        attributes = row.get('reviewedAttributes')
        _fail(isinstance(attributes, list) and all(isinstance(item, str) for item in attributes)
              and len(set(attributes)) == len(attributes)
              and set(attributes) <= ATTRIBUTES and {'text', 'geometry'} <= set(attributes),
              'source_reviewed_attributes_required')
        if row.get('scope') == 'region':
            _fail('tableGroupId' not in row and row.get('blockId') in blocks, 'invalid_region_adoption')
            associated = row.get('associatedBlockIds', [])
            _fail(isinstance(associated, list) and all(isinstance(bid, str) and bid in blocks for bid in associated),
                  'invalid_associated_region')
            members = [row['blockId']] + associated
            _fail(len(set(members)) == len(members), 'duplicate_associated_region')
            _fail(all('table' not in blocks[bid][2] for bid in members), 'table_cannot_be_region_or_cell_adopted')
            _fail(len({blocks[bid][1]['physicalPageNo'] for bid in members}) == 1, 'associated_region_crosses_page')
        else:
            _fail(row.get('scope') == 'table_group' and 'blockId' not in row
                  and 'associatedBlockIds' not in row, 'whole_table_group_required')
            group = groups.get(row.get('tableGroupId'))
            _fail(group is not None and row['runId'] in group['candidates'], 'table_group_inventory_required')
            _fail(row['tableGroupId'] not in used_groups and 'tableTopology' in attributes, 'whole_group_review_required')
            members = group['candidates'][row['runId']]
            used_groups.add(row['tableGroupId'])
        parts = []
        for bid in members:
            _fail(bid not in used, 'candidate_adopted_twice')
            run, page, block = blocks[bid]
            _fail(run['runId'] == row['runId'] and page['physicalPageNo'] in pages, 'adoption_run_or_page_mismatch')
            part = _part(run, page, block)
            # Intersecting regions cannot be silently pasted into one source.
            for previous in [p for u in units.values() for p in u['parts']] + parts:
                a, b = previous['bbox'], part['bbox']
                if previous in parts and row['scope'] == 'region' and (
                        previous['candidateTextStatus'] == 'unavailable_source_observation' or
                        part['candidateTextStatus'] == 'unavailable_source_observation'):
                    continue  # Explicit figure/caption association; empty figure text is not pasted twice.
                _fail(previous['physicalPageNo'] != part['physicalPageNo'] or
                      min(a[2], b[2]) <= max(a[0], b[0]) or min(a[3], b[3]) <= max(a[1], b[1]),
                      'overlapping_region_adoption')
            parts.append(part)
            used.add(bid)
        _fail(any(p['text'].strip() for p in parts), 'empty_unit_no_retrievable_text')
        units[uid] = {**copy.deepcopy(row), 'parts': parts,
                      'physicalPages': sorted({p['physicalPageNo'] for p in parts}),
                      'reviewStatus': 'explicit_source_reviewed_selection',
                      'referenceSha256': reference['evidenceSha256'], 'humanConfirmed': False}
    relations = _relations(manifest.get('relations', []), units, reference)
    assets = _assets(manifest.get('assets', []), units, runs, reference, artifact_store)
    for unit in units.values():
        clause = unit.get('clause')
        if clause is not None:
            _fields(clause, {'clauseId', 'headingUnitId'}, 'illegal_clause_override')
            _name(clause.get('clauseId'), 'clauseId')
            heading = units.get(clause.get('headingUnitId'))
            _fail('clauseHierarchy' in unit['reviewedAttributes'] and heading is not None,
                  'clause_heading_unknown')
            _fail(heading.get('clause', {}).get('clauseId') == clause['clauseId']
                  and heading['clause'].get('headingUnitId') == heading['unitId'], 'clause_heading_conflict')
            unit['clauseHeadingLocation'] = heading['parts'][0]['anchor']
    identity = {'version': VERSION, 'projectionCodeSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'bundleSha256': bundle['bundleSha256'], 'manifestSha256': _hash(manifest),
                'sourceSha256': source['sourceSha256'], 'sourceRevisionSha256': source['sourceRevisionSha256'],
                'documentId': document_id, 'role': role, 'module': module, 'eligibleModules': eligible,
                'sourcePurpose': purpose, 'sourceClass': manifest.get('sourceClass', 'unknown'),
                'scopeSha256': _hash(scope), 'qualitySha256': _hash(quality),
                'modelSha256': _hash({key: value['identity'] for key, value in sorted(runs.items())})}
    canonical = {'units': list(units.values()), 'relations': relations, 'assetRefs': assets}
    source_hash = _hash({'projectionIdentity': identity, 'canonical': canonical})
    revision = _hash({'sourceHash': source_hash, 'projectionIdentity': identity})
    chunks = []
    for ordinal, unit in enumerate(units.values()):
        content = '\n'.join(p['text'] for p in unit['parts'])
        row = {'id': 'canonical:' + _hash({'revision': revision, 'unitId': unit['unitId']}),
               'documentId': document_id, 'role': role, 'content': content, 'parts': copy.deepcopy(unit['parts']),
               'anchor': unit['parts'][0]['anchor'], 'pageNo': unit['physicalPages'][0],
               'physicalPages': unit['physicalPages'], 'sourceSpans': [
                   {'physicalPageStart': p['physicalPageNo'], 'physicalPageEnd': p['physicalPageNo'],
                    'blockId': p['blockId'], 'bbox': p['bbox'], 'startOffset': p['startOffset'],
                    'endOffset': p['endOffset'], 'sourceSha256': source['sourceSha256']} for p in unit['parts']],
               'sourceHash': source_hash, 'sourceSha256': source['sourceSha256'],
               'originalSourceSha256': source['sourceSha256'], 'sourceRevisionSha256': source['sourceRevisionSha256'],
               'projectionRevisionSha256': revision, 'projectionVersion': VERSION,
               'metadataVersion': VERSION, 'segmentationVersion': 'explicit-whole-unit-source-order-v1',
               'nativeTableMetadataVersion': 'parser-observed-cells-not-native-v1',
               'sourceQualityMetadataVersion': 'source-review-attribute-preservation-v1',
               'sourceQualityHash': identity['qualitySha256'], 'sourceQuality': copy.deepcopy(quality),
               'unitId': unit['unitId'], 'sourceOrder': ordinal, 'canonicalScope': unit['scope'],
               'sourceClass': identity['sourceClass'], 'sourcePurpose': purpose, 'eligibleModules': eligible,
               'module': module, 'adoption': {k: copy.deepcopy(v) for k, v in unit.items() if k != 'parts'},
               'relations': [r for r in relations if unit['unitId'] in (r['fromUnitId'], r['toUnitId'])],
               'assetRefs': [a for a in assets if a['unitId'] == unit['unitId']],
               'humanConfirmed': False}
        if unit['scope'] == 'table_group':
            row['tableGroup'] = copy.deepcopy(groups[unit['tableGroupId']])
            row['tableGroup']['adoptedRunId'] = unit['runId']
        if unit.get('clause'):
            row['clauseId'] = unit['clause']['clauseId']
            row['clauseHeadingLocation'] = unit['clauseHeadingLocation']
        paragraph_map(row)
        chunks.append(row)
    result = {'schema': SCHEMA, 'projectionIdentity': identity, 'projectionRevisionSha256': revision,
              'sourceHash': source_hash, 'source': copy.deepcopy(source), 'manifest': manifest,
              'parserIdentities': {key: copy.deepcopy(value['identity']) for key, value in sorted(runs.items())},
              'canonical': canonical, 'chunks': chunks, 'assetRefs': assets, 'captureCompleteness': completeness,
              'unknowns': [{'unitId': u['unitId'], 'attributes': copy.deepcopy(quality['attributes'])} for u in units.values()],
              'humanConfirmed': False, 'semanticScopeVerified': False,
              'limitations': ['Caller-owned Codex source review is not human authentication',
                  'Only explicitly supplied relations; no automatic whole-library relation acceptance',
                  'Unknown header/blank/edit/semantic properties are not upgraded by text agreement']}
    result['projectionSha256'] = _hash(result)
    validate_projection(result)
    return result


def _validate_projection(projection):
    """Check a sealed snapshot internally; actual disk bytes are verified at access."""
    _fail(isinstance(projection, dict) and projection.get('schema') == SCHEMA, 'unsupported_projection_schema')
    _sha(projection.get('projectionSha256'), 'projectionSha256')
    _fail(projection['projectionSha256'] == _hash({k: v for k, v in projection.items() if k != 'projectionSha256'}),
          'projection_seal_mismatch')
    identity, canonical = projection['projectionIdentity'], projection['canonical']
    manifest = projection['manifest']
    _fail(identity['manifestSha256'] == _hash(manifest), 'projection_manifest_mismatch')
    for field in ('documentId', 'role', 'module'):
        _fail(identity[field] == manifest[field], 'projection_manifest_scope_mismatch')
    for field, default in (('sourcePurpose', 'unknown'), ('sourceClass', 'unknown'),
                           ('eligibleModules', [manifest['module']])):
        _fail(identity[field] == manifest.get(field, default), 'projection_manifest_scope_mismatch')
    _fail(identity['bundleSha256'] == manifest['bundleSha256']
          and identity['scopeSha256'] == _hash(manifest['scope'])
          and identity['qualitySha256'] == _hash(_quality(manifest['quality']))
          and identity['modelSha256'] == _hash(projection['parserIdentities']), 'projection_manifest_identity_mismatch')
    for parser_identity in projection['parserIdentities'].values():
        _fail(parser_identity['identitySha256'] == _hash({k: v for k, v in parser_identity.items()
              if k != 'identitySha256'}) and parser_identity['configSha256'] == _hash(parser_identity['config']),
              'projection_parser_identity_mismatch')
    source = projection['source']
    _fail(all(identity[name] == source[name] for name in ('sourceSha256', 'sourceRevisionSha256')),
          'projection_source_mismatch')
    _fail(projection['sourceHash'] == _hash({'projectionIdentity': identity, 'canonical': canonical}),
          'canonical_source_hash_mismatch')
    _fail(projection['projectionRevisionSha256'] == _hash({'sourceHash': projection['sourceHash'],
          'projectionIdentity': identity}), 'projection_revision_mismatch')
    _fail(projection.get('humanConfirmed') is False and projection.get('semanticScopeVerified') is False,
          'unknown_quality_cannot_be_upgraded')
    chunks, units = projection['chunks'], canonical['units']
    _fail(len(chunks) == len(units) and len({c['id'] for c in chunks}) == len(chunks), 'canonical_unit_chunk_mismatch')
    _fail(projection['assetRefs'] == canonical['assetRefs'], 'canonical_asset_mismatch')
    _fail(len({u['unitId'] for u in units}) == len(units), 'duplicate_unit_id')
    unit_map = {u['unitId']: u for u in units}
    reference = manifest['reference']
    _fail(canonical['relations'] == _relations(manifest.get('relations', []), unit_map, reference),
          'canonical_relation_mismatch')
    _fail(len({a['assetId'] for a in projection['assetRefs']}) == len(projection['assetRefs']),
          'duplicate_asset_reference')
    for asset in projection['assetRefs']:
        asset_identity = {k: asset[k] for k in ('sourceSha256', 'unitId', 'runId', 'physicalPages',
                                               'blockIds', 'sha256', 'mediaType')}
        _fail(asset['assetId'] == 'asset:' + _hash(asset_identity), 'asset_id_mismatch')
        unit = unit_map[asset['unitId']]
        _fail(asset['sourceSha256'] == source['sourceSha256'] and asset['runId'] == unit['runId']
              and asset['physicalPages'][0] in unit['physicalPages'], 'asset_adoption_mismatch')
        _fail(asset['sha256'] == asset['artifactDescriptor']['sha256']
              and asset['byteCount'] == asset['artifactDescriptor']['byteCount'], 'asset_descriptor_identity_mismatch')
        parts = [p for p in unit['parts'] if p['physicalPageNo'] == asset['physicalPages'][0]
                 and p['blockId'] in asset['blockIds']]
        _fail(set(asset['blockIds']) == {p['blockId'] for p in parts} and asset['sourceRegions'] == [
            {'blockId': p['blockId'], 'bbox': p['bbox'], 'bboxConvention': p['bboxConvention']} for p in parts],
            'asset_block_mapping_unknown')
    for ordinal, (chunk, unit) in enumerate(zip(chunks, units)):
        requested = manifest['units'][ordinal]
        _fail(all(unit.get(k) == v for k, v in requested.items()), 'canonical_adoption_mismatch')
        if unit['scope'] == 'region':
            _fail([p['blockId'] for p in unit['parts']] == [requested['blockId']]
                  + requested.get('associatedBlockIds', []) and len(unit['physicalPages']) == 1,
                  'canonical_adoption_mismatch')
        _fail(chunk['unitId'] == unit['unitId'] and chunk['sourceOrder'] == ordinal
              and chunk['parts'] == unit['parts'], 'canonical_unit_chunk_mismatch')
        _fail(chunk['adoption'] == {k: v for k, v in unit.items() if k != 'parts'}, 'canonical_adoption_mismatch')
        _fail(chunk['id'] == 'canonical:' + _hash({'revision': projection['projectionRevisionSha256'], 'unitId': unit['unitId']}),
              'canonical_chunk_id_mismatch')
        for field in ('documentId', 'role', 'module', 'eligibleModules', 'sourcePurpose', 'sourceClass'):
            _fail(chunk[field] == identity[field], 'canonical_chunk_scope_mismatch', field=field)
        for field in ('sourceSha256', 'sourceRevisionSha256'):
            _fail(chunk[field] == source[field], 'canonical_chunk_source_mismatch')
        _fail(chunk['sourceHash'] == projection['sourceHash'] and chunk['originalSourceSha256'] == source['sourceSha256']
              and chunk['projectionRevisionSha256'] == projection['projectionRevisionSha256'], 'canonical_chunk_source_mismatch')
        _fail(chunk['sourceQualityHash'] == _hash(chunk['sourceQuality']) == identity['qualitySha256'],
              'canonical_quality_hash_mismatch')
        _quality({k: v for k, v in chunk['sourceQuality'].items() if k != 'reviewMeaning'})
        _fail(chunk['content'] == '\n'.join(p['text'] for p in unit['parts']) and chunk['humanConfirmed'] is False,
              'canonical_content_mismatch')
        paragraph_map(chunk)
        _fail(chunk['physicalPages'] == sorted({p['physicalPageNo'] for p in chunk['parts']})
              and chunk['physicalPages'] == unit['physicalPages']
              and chunk['pageNo'] == chunk['physicalPages'][0]
              and chunk['anchor'] == chunk['parts'][0]['anchor'], 'canonical_page_span_mismatch')
        expected_spans = [{'physicalPageStart': p['physicalPageNo'], 'physicalPageEnd': p['physicalPageNo'],
                    'blockId': p['blockId'], 'bbox': p['bbox'], 'startOffset': p['startOffset'],
                    'endOffset': p['endOffset'], 'sourceSha256': source['sourceSha256']} for p in unit['parts']]
        _fail(chunk['sourceSpans'] == expected_spans, 'canonical_page_span_mismatch')
        _fail(chunk['assetRefs'] == [a for a in projection['assetRefs'] if a['unitId'] == chunk['unitId']],
              'canonical_asset_mismatch')
        _fail(chunk['relations'] == [r for r in canonical['relations']
              if unit['unitId'] in (r['fromUnitId'], r['toUnitId'])], 'canonical_relation_mismatch')
        if unit['scope'] == 'table_group':
            group = next(g for g in manifest['tableGroups'] if g['tableGroupId'] == unit['tableGroupId'])
            _fail(chunk['tableGroup'] == {**group, 'adoptedRunId': unit['runId']}
                  and [p['blockId'] for p in unit['parts']] == group['candidates'][unit['runId']]
                  and unit['physicalPages'] == group['physicalPages'], 'partial_table_group')
        if unit.get('clause'):
            heading = unit_map[unit['clause']['headingUnitId']]
            _fail(chunk.get('clauseId') == unit['clause']['clauseId']
                  and chunk.get('clauseHeadingLocation') == heading['parts'][0]['anchor']
                  == unit['clauseHeadingLocation'], 'clause_heading_conflict')
        for part in chunk['parts']:
            parser_identity = projection['parserIdentities'][part['candidateProvenance']['runId']]
            _fail(part['sourceSha256'] == source['sourceSha256']
                  and part['geometryEvidence']['status'] == 'verified'
                  and part['bbox'] == part['geometryEvidence']['xyxyNormalized']
                  and part['geometryEvidence']['targetFrame']['sourceSha256'] == source['sourceSha256']
                  and part['geometryEvidence']['targetFrame']['physicalPageNo'] == part['physicalPageNo']
                  and part['candidateProvenance']['parserIdentitySha256'] == parser_identity['identitySha256']
                  and part['candidateProvenance']['configSha256'] == parser_identity['configSha256'],
                  'canonical_part_mapping_mismatch')
            _fail(all(status == 'unknown' for status in part['qualityAttributes'].values()), 'unknown_quality_cannot_be_upgraded')
            _fail(part['candidateTextStatus'] in {'unavailable_source_observation', 'parser_observed_unverified'}
                  and (part['candidateTextStatus'] != 'unavailable_source_observation' or
                       (part['text'] == '' and part['candidateLabel'] in {'image', 'figure', 'chart'})),
                  'unknown_text_cannot_be_upgraded')
    return projection


def project_canonical(bundle, adoption_manifest, *, artifact_store=None):
    try:
        return _project_canonical(bundle, adoption_manifest, artifact_store=artifact_store)
    except CanonicalProjectionError:
        raise
    except (KeyError, TypeError, IndexError, ValueError) as error:
        raise CanonicalProjectionError('malformed_projection_input', reason=str(error)) from error


def validate_projection(projection):
    try:
        return _validate_projection(projection)
    except CanonicalProjectionError:
        raise
    except (KeyError, TypeError, IndexError, ValueError) as error:
        raise CanonicalProjectionError('malformed_projection_snapshot', reason=str(error)) from error


validate_canonical_projection = validate_projection
