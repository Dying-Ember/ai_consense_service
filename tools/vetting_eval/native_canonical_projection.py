"""Source-reviewed native DOCX projection; never an OCR or gold-text repair.

The independent v2 branch retains exact observation bytes and uses typed OOXML
positions. It deliberately produces no Word page images or guessed geometry.
Whole tables include their nested inventory; only paragraph channels enter the
index, once. The scanned v1 producer and its dual-parser admission are untouched.
"""
from __future__ import annotations

import copy
import hashlib
from pathlib import Path

from artifact_store import ArtifactStore
from canonical_projection import (CanonicalProjectionError, MODULES, ROLES,
                                  UNKNOWN_ATTRIBUTES, _bytes, _fail, _fields,
                                  _hash, _name, _quality, _sha)
from competition_scope import EXCLUDED_CLASSES, SOURCE_PURPOSES
from native_observation import (BUNDLE_SCHEMA, DOCX_MEDIA,
                                validate_native_bundle)
from source_reference import SCHEMA as REFERENCE_SCHEMA
from source_reference import read_source_reference, validate_source_reference
from token_windows import paragraph_map, utf16_length

SCHEMA = 'consense.canonical-projection.v2'
ADOPTION_SCHEMA = 'consense.native-canonical-adoption.v1'
VERSION = 'source-reviewed-native-whole-unit-projection-v2'
ATTRIBUTES = {'text', 'nativePosition', 'tableInventory', 'clauseHierarchy'}
GEOMETRY_STATUS = 'not_defined_for_native_docx'


def _boundary(operation, code):
    try:
        return operation()
    except CanonicalProjectionError:
        raise
    except (ValueError, OSError, KeyError, TypeError, IndexError, AttributeError) as error:
        raise CanonicalProjectionError(code, reason=str(error)) from error


def _ids(value, code, *, nonempty=False):
    _fail(isinstance(value, list) and (bool(value) or not nonempty)
          and all(isinstance(item, str) and item.strip() for item in value)
          and len(set(value)) == len(value), code)
    return value


def _bundle_integrity(bundle):
    _fail(isinstance(bundle, dict) and bundle.get('schema') == BUNDLE_SCHEMA
          and bundle.get('producerKind') == 'native_docx', 'unsupported_native_bundle')
    _fail(bundle.get('bundleSha256') == _hash({key: value for key, value in bundle.items()
                                             if key != 'bundleSha256'}), 'native_bundle_seal_mismatch')
    _fail(bundle.get('humanConfirmed') is False and bundle.get('semanticScopeVerified') is False,
          'unknown_quality_cannot_be_upgraded')
    source, observer = bundle['source'], bundle['observerIdentity']
    _sha(source['sourceSha256'], 'source.sourceSha256')
    _fail(source.get('mediaType') == DOCX_MEDIA
          and source.get('sourceRevisionSha256') == _hash({'sourceSha256': source['sourceSha256'],
                                                         'observerIdentity': observer}),
          'native_source_revision_mismatch')
    _fail(observer.get('configSha256') == _hash(observer['config']), 'native_observer_config_mismatch')
    _fail(bundle['sourcePackage']['sha256'] == source['sourceSha256']
          and bundle['adapterSource']['sha256'] == observer['adapterCodeSha256'], 'native_capture_identity_mismatch')


def _scope(manifest, bundle):
    _fields(manifest, {'schema', 'bundleSha256', 'sourceSha256', 'sourceRevisionSha256',
                      'documentId', 'role', 'module', 'eligibleModules', 'sourcePurpose',
                      'sourceClass', 'scope', 'quality', 'reference', 'units', 'relations',
                      'tableGroups', 'assets'}, 'illegal_native_adoption')
    _fail(manifest.get('schema') == ADOPTION_SCHEMA, 'unsupported_native_adoption_schema')
    _fail(manifest.get('bundleSha256') == bundle['bundleSha256'], 'stale_bundle_adoption')
    for field in ('sourceSha256', 'sourceRevisionSha256'):
        _fail(manifest.get(field) == bundle['source'][field], 'source_adoption_mismatch', field=field)
    _name(manifest.get('documentId'), 'documentId')
    module, role = manifest.get('module'), manifest.get('role')
    _fail(module in MODULES and role in ROLES, 'invalid_document_role_or_module')
    purpose = manifest.get('sourcePurpose', 'unknown')
    _fail(purpose in SOURCE_PURPOSES, 'invalid_source_purpose')
    eligible = _ids(manifest.get('eligibleModules', [module]), 'invalid_module_eligibility', nonempty=True)
    _fail(set(eligible) <= MODULES and module in eligible, 'invalid_module_eligibility')
    source_class = manifest.get('sourceClass', 'unknown')
    _name(source_class, 'sourceClass')
    _fail(source_class not in EXCLUDED_CLASSES and source_class != 'evaluation_answer'
          and purpose != 'evaluation_answer', 'module_source_not_eligible')
    template = purpose == 'standard_template' or source_class == 'drafting_standard_template'
    _fail(not template or purpose in {'standard_template', 'unknown'}
          and module == 'drafting' and eligible == ['drafting'], 'module_source_not_eligible')
    # The current SL physical-page policy belongs to its original PDF, not Word.
    _fail(source_class not in {'sl_library', 'sl_technical_library', 'specification_library'},
          'native_docx_cannot_replace_sl_pdf_scope')
    scope = manifest.get('scope')
    _fields(scope, {'requiredPartNames', 'scopePolicySha256'}, 'invalid_native_projection_scope')
    names = _ids(scope.get('requiredPartNames'), 'invalid_native_scope_parts', nonempty=True)
    _sha(scope.get('scopePolicySha256'), 'scopePolicySha256')
    _fail(set(names) <= {row['name'] for row in bundle['rawParts']}, 'native_scope_part_unavailable')
    _fail(manifest.get('assets', []) == [], 'native_rendition_assets_not_yet_supported')
    return copy.deepcopy(scope), eligible, purpose, source_class


def _reference(manifest, bundle, store):
    ref = manifest.get('reference')
    _fields(ref, {'actor', 'method', 'sourceSha256', 'referenceVersion', 'evidenceSha256',
                  'artifactDescriptor'}, 'illegal_native_reference')
    _fail(ref.get('actor') == 'codex' and ref.get('method') == 'original_source_review',
          'source_review_required')
    _fail(ref.get('sourceSha256') == bundle['source']['sourceSha256'], 'reference_source_mismatch')
    _sha(ref.get('evidenceSha256'), 'reference.evidenceSha256')
    raw = store.read_asset(ref['artifactDescriptor'])
    _fail(hashlib.sha256(raw).hexdigest() == ref['evidenceSha256'], 'reference_artifact_sha_mismatch')
    loaded = read_source_reference(raw, expected_sha256=ref['evidenceSha256'])
    _fail(loaded.input_schema == REFERENCE_SCHEMA, 'native_reference_v2_required')
    reference = loaded.reference
    _reference_claims(reference, ref, bundle)
    evidence = {bundle['source']['sourceSha256']: store.read_asset(bundle['sourcePackage'])}
    evidence.update({row['sha256']: store.read_asset(row['artifactDescriptor']) for row in bundle['rawParts']})
    validate_source_reference(reference, evidence_bytes=evidence)
    return reference


def _reference_claims(reference, descriptor, bundle):
    _fail(reference.get('schema') == REFERENCE_SCHEMA and reference.get('sourceMediaType') == DOCX_MEDIA
          and reference.get('humanConfirmed') is False
          and reference.get('physicalPages', []) == [] and reference.get('pages') == [],
          'native_reference_original_geometry_or_human_claim')
    _fail(all(reference.get(key) == descriptor.get(key) for key in
              ('actor', 'method', 'sourceSha256', 'referenceVersion')),
          'reference_descriptor_claim_mismatch')
    _fail(reference.get('sourceSha256') == bundle['source']['sourceSha256'], 'reference_source_mismatch')


def _index(bundle):
    nodes = bundle['nativeNodes']
    _fail(isinstance(nodes, list) and nodes, 'native_node_inventory_required')
    ids = [node['nodeId'] for node in nodes]
    _fail(len(set(ids)) == len(ids), 'duplicate_native_node')
    _fail([node['sourceOrder'] for node in nodes] == sorted({node['sourceOrder'] for node in nodes}),
          'native_source_order_required')
    by_id = {node['nodeId']: node for node in nodes}
    tables = {table['tableId']: table for table in bundle['tableInventory']}
    _fail(len(tables) == len(bundle['tableInventory'])
          and set(tables) == {node['nodeId'] for node in nodes if node['kind'] == 'table'},
          'native_table_inventory_incomplete')
    # The capture validator has verified these ancestry IDs against actual XML.
    # One pass builds all complete subtrees; a large main part is not reparsed
    # and rescanned separately for every selected table.
    table_members = {table_id: [] for table_id in tables}
    for node in nodes:
        cursor = node['nodeId'] if node['kind'] == 'table' else node.get('parentTableId')
        seen = set()
        while cursor is not None:
            _fail(cursor in tables and cursor not in seen, 'native_table_ancestry_missing_or_cyclic')
            seen.add(cursor)
            table_members[cursor].append(node)
            cursor = tables[cursor].get('parentTableId')
    return by_id, tables, table_members


def _table_group(roots, nodes, tables, table_members):
    _ids(roots, 'invalid_native_table_roots', nonempty=True)
    _fail(all(root in tables for root in roots), 'unknown_native_table')
    _fail([nodes[root]['sourceOrder'] for root in roots] == sorted(nodes[root]['sourceOrder'] for root in roots),
          'native_table_root_source_order_required')
    # Independently selected nested roots would duplicate their parent text.
    _fail(all(not tables[root].get('parentTableId') and not tables[root].get('parentCellId') for root in roots),
          'native_table_root_must_be_whole_outer_table')
    selected = [node for root in roots for node in table_members[root]]
    selected.sort(key=lambda node: node['sourceOrder'])
    selected_tables = [tables[node['nodeId']] for node in selected if node['kind'] == 'table']
    paragraphs = [node for node in selected if node['kind'] == 'paragraph']
    ids = {node['nodeId'] for node in selected}
    for table in selected_tables:
        _fail(table.get('inventoryComplete') is True
              and table.get('sourcePosition') == nodes[table['tableId']]['sourcePosition'],
              'partial_native_table')
        row_ids, cell_ids, paragraph_ids, nested_ids = [], [], [], []
        for ri, row in enumerate(table['rows']):
            row_id = row['rowId']
            _fail(row_id in ids and nodes[row_id]['kind'] == 'row' and row.get('rowIndex') == ri
                  and row.get('sourcePosition') == nodes[row_id]['sourcePosition'], 'partial_native_table')
            row_ids.append(row_id)
            for ci, cell in enumerate(row['cells']):
                cell_id = cell['cellId']
                _fail(cell_id in ids and nodes[cell_id]['kind'] == 'cell'
                      and cell.get('rowIndex') == ri and cell.get('cellIndex') == ci
                      and cell.get('sourcePosition') == nodes[cell_id]['sourcePosition'], 'partial_native_table')
                cell_ids.append(cell_id)
                _ids(cell.get('childParagraphIds'), 'partial_native_table')
                _ids(cell.get('nestedTableIds'), 'partial_native_table')
                paragraph_ids.extend(cell['childParagraphIds'])
                nested_ids.extend(cell['nestedTableIds'])
                _fail(all(pid in ids and nodes[pid]['kind'] == 'paragraph'
                          and nodes[pid].get('parentCellId') == cell_id for pid in cell['childParagraphIds']),
                      'partial_native_table')
                _fail(all(tid in ids and nodes[tid]['kind'] == 'table'
                          and tables[tid].get('parentCellId') == cell_id for tid in cell['nestedTableIds']),
                      'partial_native_table')
        tid = table['tableId']
        _fail(row_ids == [node['nodeId'] for node in selected if node['kind'] == 'row'
                          and node.get('parentTableId') == tid], 'partial_native_table')
        _fail(cell_ids == [node['nodeId'] for node in selected if node['kind'] == 'cell'
                           and node.get('parentTableId') == tid], 'partial_native_table')
        _fail(paragraph_ids == [node['nodeId'] for node in selected if node['kind'] == 'paragraph'
                                and node.get('parentTableId') == tid], 'partial_native_table')
        _fail(nested_ids == table['nestedTableIds'] == [t['tableId'] for t in selected_tables
                  if t.get('parentTableId') == tid], 'partial_native_table')
    return paragraphs, selected_tables, selected


def _part(node, channel, index, bundle):
    _fail(channel in node['textChannels'] and isinstance(node['textChannels'][channel], str),
          'native_text_channel_unavailable')
    text, position = node['textChannels'][channel], node['sourcePosition']
    _fail(not any(key in node or key in position for key in ('pageNo', 'physicalPageNo', 'bbox')),
          'native_docx_original_geometry_invented')
    _fail(position.get('kind') == 'docx_native'
          and position.get('sourceSha256') == bundle['source']['sourceSha256'], 'native_part_source_mismatch')
    anchor = 'docx-native:' + position['partName'] + ':' + position['nodePath']
    return {'blockId': node['nodeId'], 'nativeBlockId': node['nodeId'], 'anchor': anchor,
            'text': text, 'startOffset': 0, 'endOffset': utf16_length(text),
            'sourceSha256': bundle['source']['sourceSha256'], 'sourcePosition': copy.deepcopy(position),
            'native': True, 'originalGeometryStatus': GEOMETRY_STATUS,
            'candidateTextStatus': 'native_observed_unverified',
            'textTransform': {'version': 'native-channel-identity-utf16-v1', 'mode': 'identity',
                              'textChannel': channel, 'sourceStartUtf16': 0, 'sourceEndUtf16': utf16_length(text)},
            'candidateProvenance': {'observerIdentitySha256': _hash(bundle['observerIdentity']),
                'rawObservation': copy.deepcopy(bundle['rawObservation']), 'jsonPointer': '/nativeNodes/' + str(index),
                'nativeNodeId': node['nodeId'], 'textChannel': channel},
            'nativeObservation': copy.deepcopy(node),
            'qualityAttributes': {key: 'unknown' for key in UNKNOWN_ATTRIBUTES}}


def _units(manifest, bundle, reference, store=None):
    nodes, tables, table_members = _index(bundle)
    indices = {node['nodeId']: index for index, node in enumerate(bundle['nativeNodes'])}
    region_refs = {region['regionId']: region for region in reference['regions']}
    table_refs = {table['tableId']: table for table in reference.get('nativeTables', [])}
    groups = manifest.get('tableGroups', [])
    _fail(isinstance(groups, list), 'native_table_groups_required')
    group_map = {}
    for group in groups:
        _fields(group, {'tableGroupId', 'rootTableIds', 'nativeTableIds', 'nativeParagraphIds',
                        'referenceTableId'}, 'illegal_native_table_group')
        key = _name(group.get('tableGroupId'), 'tableGroupId')
        _fail(key not in group_map, 'duplicate_native_table_group')
        paragraphs, inventory, selected = _table_group(group.get('rootTableIds'), nodes, tables, table_members)
        _fail(group.get('nativeTableIds') == [table['tableId'] for table in inventory]
              and group.get('nativeParagraphIds') == [node['nodeId'] for node in paragraphs], 'partial_native_table_group')
        reviewed = table_refs.get(group.get('referenceTableId'))
        _fail(reviewed is not None and reviewed.get('completeNativeTable') is True
              and all(reviewed.get(field) == group.get(field) for field in
                      ('rootTableIds', 'nativeTableIds', 'nativeParagraphIds')), 'native_table_reference_coverage_missing')
        _fail(reviewed.get('sourceAnchor') == nodes[group['rootTableIds'][0]]['sourcePosition'],
              'native_table_reference_anchor_mismatch')
        group_map[key] = (group, paragraphs, inventory, selected)
    rows = manifest.get('units')
    _fail(isinstance(rows, list) and rows, 'explicit_native_adoption_required')
    used, used_tables, used_groups, units = set(), set(), set(), {}
    for row in rows:
        _fields(row, {'unitId', 'scope', 'nodeId', 'textChannel', 'tableGroupId', 'referenceRegionIds',
                      'reviewedAttributes', 'clause'}, 'illegal_native_unit_adoption')
        uid = _name(row.get('unitId'), 'unitId')
        _fail(uid not in units, 'duplicate_unit_id')
        attributes = _ids(row.get('reviewedAttributes'), 'source_reviewed_attributes_required', nonempty=True)
        _fail({'text', 'nativePosition'} <= set(attributes) <= ATTRIBUTES, 'source_reviewed_attributes_required')
        ref_ids = _ids(row.get('referenceRegionIds'), 'native_reference_regions_required', nonempty=True)
        _fail(set(ref_ids) <= set(region_refs), 'native_reference_region_missing')
        _name(row.get('textChannel'), 'textChannel')
        inventory = None
        if row.get('scope') == 'region':
            _fail('tableGroupId' not in row and row.get('nodeId') in nodes, 'invalid_native_region_adoption')
            node = nodes[row['nodeId']]
            _fail(node['kind'] == 'paragraph' and not node.get('parentTableId')
                  and not node.get('parentCellId'), 'table_or_run_cannot_be_native_region')
            _fail(len(ref_ids) == 1 and region_refs[ref_ids[0]]['sourceAnchor'] == node['sourcePosition'],
                  'native_reference_anchor_mismatch')
            paragraphs, observed = [node], [node]
        else:
            _fail(row.get('scope') == 'table_group' and 'nodeId' not in row
                  and row.get('tableGroupId') in group_map, 'whole_native_table_group_required')
            key = row['tableGroupId']
            _fail(key not in used_groups and 'tableInventory' in attributes, 'whole_native_table_group_review_required')
            used_groups.add(key)
            group, paragraphs, inventory, observed = group_map[key]
            _fail(not used_tables.intersection(group['nativeTableIds']), 'native_table_adopted_twice')
            used_tables.update(group['nativeTableIds'])
            root_positions = [nodes[root]['sourcePosition'] for root in group['rootTableIds']]
            _fail([region_refs[rid]['sourceAnchor'] for rid in ref_ids] == root_positions,
                  'native_table_reference_anchor_mismatch')
        _fail(all(node['sourcePosition']['partName'] in manifest['scope']['requiredPartNames'] for node in observed),
              'native_adoption_outside_scope')
        _fail(not used.intersection(node['nodeId'] for node in paragraphs), 'native_paragraph_adopted_twice')
        used.update(node['nodeId'] for node in paragraphs)
        parts = [_part(node, row['textChannel'], indices[node['nodeId']], bundle) for node in paragraphs]
        _fail(parts and any(part['text'].strip() for part in parts), 'empty_unit_no_retrievable_text')
        unit = {**copy.deepcopy(row), 'parts': parts, 'physicalPages': [],
                'originalGeometryStatus': GEOMETRY_STATUS, 'reviewStatus': 'explicit_source_reviewed_selection',
                'referenceSha256': manifest['reference']['evidenceSha256'], 'humanConfirmed': False}
        if inventory is not None:
            unit['nativeTables'] = copy.deepcopy(inventory)
            unit['nativeTableNodes'] = copy.deepcopy(observed)
        units[uid] = unit
    _fail(used_groups == set(group_map), 'unused_native_table_group_declaration')
    first_orders = [min(part['nativeObservation']['sourceOrder'] for part in unit['parts'])
                    for unit in units.values()]
    _fail(first_orders == sorted(first_orders), 'native_adopted_unit_source_order_required')
    return units


def _relations(manifest, units, reference):
    rows = manifest.get('relations', [])
    _fail(isinstance(rows, list), 'relations_must_be_explicit')
    result, parents = [], {}
    for row in rows:
        _fields(row, {'type', 'fromUnitId', 'toUnitId', 'fromBlockId', 'toBlockId',
                      'referenceRelationIndex'}, 'illegal_native_relation')
        kind, left, right = row.get('type'), row.get('fromUnitId'), row.get('toUnitId')
        _fail(kind in {'parent', 'continuation', 'header', 'qualifier', 'caption'}
              and left in units and right in units and left != right, 'native_relation_member_unselected')
        index = row.get('referenceRelationIndex')
        refs = reference.get('relations', [])
        _fail(type(index) is int and 0 <= index < len(refs), 'native_relation_source_review_missing')
        reviewed = refs[index]
        _fail(reviewed.get('type') == kind and reviewed.get('fromRegionId') in units[left]['referenceRegionIds']
              and reviewed.get('toRegionId') in units[right]['referenceRegionIds'], 'native_relation_reference_mismatch')
        for side, unit_id in [('from', left), ('to', right)]:
            _fail(row.get(side + 'BlockId') is None or row[side + 'BlockId'] in
                  {part['blockId'] for part in units[unit_id]['parts']}, 'native_relation_block_outside_unit')
        if kind == 'parent':
            _fail(left not in parents, 'ambiguous_parent_relation')
            parents[left] = right
        result.append({**copy.deepcopy(row), 'status': 'explicit_source_reviewed',
                       'referenceSha256': manifest['reference']['evidenceSha256']})
    for seed in parents:
        cursor, seen = seed, set()
        while cursor in parents:
            _fail(cursor not in seen, 'cyclic_parent_relation')
            seen.add(cursor)
            cursor = parents[cursor]
    _fail(len({_hash(row) for row in result}) == len(result), 'duplicate_relation')
    return result


def _assemble(bundle, manifest, reference, code_sha, store=None):
    _bundle_integrity(bundle)
    scope, eligible, purpose, source_class = _scope(manifest, bundle)
    _reference_claims(reference, manifest['reference'], bundle)
    quality = _quality(manifest['quality'])
    units = _units(manifest, bundle, reference, store)
    relations = _relations(manifest, units, reference)
    for unit in units.values():
        clause = unit.get('clause')
        if clause is not None:
            _fields(clause, {'clauseId', 'headingUnitId'}, 'illegal_clause_override')
            _name(clause.get('clauseId'), 'clauseId')
            heading = units.get(clause.get('headingUnitId'))
            _fail('clauseHierarchy' in unit['reviewedAttributes'] and heading is not None
                  and heading.get('clause') == {'clauseId': clause['clauseId'], 'headingUnitId': heading['unitId']},
                  'clause_heading_unknown_or_conflict')
            regions = {region['regionId']: region for region in reference['regions']}
            _fail(any(regions[rid].get('clauseId') == clause['clauseId'] for rid in unit['referenceRegionIds']),
                  'clause_id_not_source_reviewed')
            unit['clauseHeadingLocation'] = heading['parts'][0]['anchor']
    source = bundle['source']
    identity = {'version': VERSION, 'producerKind': 'native_docx', 'projectionCodeSha256': code_sha,
                'bundleSha256': bundle['bundleSha256'], 'manifestSha256': _hash(manifest),
                'sourceSha256': source['sourceSha256'], 'sourceRevisionSha256': source['sourceRevisionSha256'],
                'documentId': manifest['documentId'], 'role': manifest['role'], 'module': manifest['module'],
                'eligibleModules': eligible, 'sourcePurpose': purpose, 'sourceClass': source_class,
                'scopeSha256': _hash(scope), 'qualitySha256': _hash(quality),
                'observerIdentitySha256': _hash(bundle['observerIdentity'])}
    canonical = {'units': list(units.values()), 'relations': relations, 'assetRefs': []}
    source_hash = _hash({'projectionIdentity': identity, 'canonical': canonical})
    revision = _hash({'sourceHash': source_hash, 'projectionIdentity': identity})
    chunks = []
    for ordinal, unit in enumerate(units.values()):
        row = {'id': 'canonical:' + _hash({'revision': revision, 'unitId': unit['unitId']}),
               'content': '\n'.join(part['text'] for part in unit['parts']), 'parts': copy.deepcopy(unit['parts']),
               'anchor': unit['parts'][0]['anchor'], 'physicalPages': [], 'originalGeometryStatus': GEOMETRY_STATUS,
               'sourceSpans': [{'blockId': part['blockId'], 'sourcePosition': copy.deepcopy(part['sourcePosition']),
                                'startOffset': part['startOffset'], 'endOffset': part['endOffset'],
                                'sourceSha256': source['sourceSha256']} for part in unit['parts']],
               'sourceHash': source_hash, 'sourceSha256': source['sourceSha256'],
               'originalSourceSha256': source['sourceSha256'], 'sourceRevisionSha256': source['sourceRevisionSha256'],
               'projectionRevisionSha256': revision, 'projectionVersion': VERSION, 'metadataVersion': VERSION,
               'segmentationVersion': 'explicit-native-paragraph-whole-table-source-order-v1',
               'nativeTableMetadataVersion': 'complete-ooxml-inventory-no-rendered-grid-v1',
               'sourceQualityMetadataVersion': 'source-review-attribute-preservation-v1',
               'sourceQualityHash': identity['qualitySha256'], 'sourceQuality': copy.deepcopy(quality),
               'unitId': unit['unitId'], 'sourceOrder': ordinal, 'canonicalScope': unit['scope'],
               'adoption': {key: copy.deepcopy(value) for key, value in unit.items() if key != 'parts'},
               'relations': [rel for rel in relations if unit['unitId'] in (rel['fromUnitId'], rel['toUnitId'])],
               'assetRefs': [], 'humanConfirmed': False}
        row.update({key: copy.deepcopy(identity[key]) for key in ('documentId', 'role', 'module',
                    'eligibleModules', 'sourcePurpose', 'sourceClass')})
        if unit['scope'] == 'table_group':
            group = next(group for group in manifest['tableGroups'] if group['tableGroupId'] == unit['tableGroupId'])
            row['tableGroup'] = {**copy.deepcopy(group), 'physicalPages': [],
                                 'nativeTables': copy.deepcopy(unit['nativeTables']),
                                 'visualReturnStatus': 'unavailable_pending_verified_rendition'}
        if unit.get('clause'):
            row['clauseId'] = unit['clause']['clauseId']
            row['clauseHeadingLocation'] = unit['clauseHeadingLocation']
        paragraph_map(row)
        chunks.append(row)
    result = {'schema': SCHEMA, 'producerKind': 'native_docx', 'projectionIdentity': identity,
              'projectionRevisionSha256': revision, 'sourceHash': source_hash, 'source': copy.deepcopy(source),
              'manifest': copy.deepcopy(manifest), 'observerIdentity': copy.deepcopy(bundle['observerIdentity']),
              'nativeObservationQuality': copy.deepcopy(bundle['quality']),
              'nativeObservationBundle': copy.deepcopy(bundle), 'sourceReference': copy.deepcopy(reference),
              'canonical': canonical, 'chunks': chunks, 'assetRefs': [],
              'captureCompleteness': copy.deepcopy(bundle['validation']),
              'unknowns': [{'unitId': unit['unitId'], 'attributes': copy.deepcopy(quality['attributes'])}
                           for unit in units.values()], 'humanConfirmed': False, 'semanticScopeVerified': False,
              'visualReturnStatus': 'unavailable_pending_verified_rendition',
              'limitations': copy.deepcopy(bundle['limitations']) + [
                  'Codex source review does not certify human or whole-source semantic acceptance',
                  'Native XML inventory does not infer rendered grid, rowSpan, header or effective edit state',
                  'Original raw resources retained; no verified Word-to-rendition mapping or Q10 asset return']}
    result['projectionSha256'] = _hash(result)
    return result


def produce_native(bundle, explicit_adoption, *, artifact_store):
    """Project an explicit original-source review after every captured-byte check."""
    def run():
        _fail(isinstance(artifact_store, ArtifactStore), 'actual_artifact_store_required')
        validate_native_bundle(bundle, artifact_store=artifact_store)
        reference = _reference(explicit_adoption, bundle, artifact_store)
        result = _assemble(bundle, copy.deepcopy(explicit_adoption), reference,
                           hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), artifact_store)
        return validate_native_projection(result, artifact_store=artifact_store)
    return _boundary(run, 'malformed_native_projection_input')


def validate_native_projection(projection, *, artifact_store=None):
    """Recompute all derived fields; with CAS also re-read original/native/reference bytes.

    Registry integrations must supply artifact_store at every read. Pure internal
    validation cannot establish that retained original files remain available.
    """
    def run():
        _fail(isinstance(projection, dict) and projection.get('schema') == SCHEMA
              and projection.get('producerKind') == 'native_docx', 'unsupported_native_projection_schema')
        _fail(projection.get('projectionSha256') == _hash({key: value for key, value in projection.items()
                  if key != 'projectionSha256'}), 'projection_seal_mismatch')
        bundle, manifest = projection['nativeObservationBundle'], projection['manifest']
        reference = projection['sourceReference']
        if artifact_store is not None:
            _fail(isinstance(artifact_store, ArtifactStore), 'actual_artifact_store_required')
            validate_native_bundle(bundle, artifact_store=artifact_store)
            _fail(reference == _reference(manifest, bundle, artifact_store), 'native_retained_reference_mismatch')
        code = _sha(projection['projectionIdentity']['projectionCodeSha256'], 'projectionCodeSha256')
        expected = _assemble(bundle, manifest, reference, code, artifact_store)
        _fail(expected == projection, 'native_projection_derived_payload_mismatch')
        return projection
    return _boundary(run, 'malformed_native_projection_snapshot')
