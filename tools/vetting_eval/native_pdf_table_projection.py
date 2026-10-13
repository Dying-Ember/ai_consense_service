"""Explicit source-reviewed native PDF table topology and original-page assets.

This seam accepts no provider text or inferred blanks. Table literals come from
the already reviewed same-version glyph partition; source quality stays unknown.
"""
from __future__ import annotations

import copy

from canonical_projection import _fail, _fields, _name


VERSION = 'source-reviewed-native-pdf-whole-table-groups-v1'
MAX_GRID_SLOTS = 100000  # Resource cap on declarations, not an assumed table shape.


def table_region_ids(manifest):
    groups = manifest.get('tableGroups')
    _fail(isinstance(groups, list), 'native_pdf_table_groups_required')
    return {fragment['regionId'] for group in groups for fragment in group['fragments']}


def _integer(value, code, minimum=0):
    _fail(type(value) is int and value >= minimum, code)
    return value


def project_table_groups(manifest, parts, reference, native):
    refs = {region['regionId']: region for region in reference['regions']}
    tables = {table['tableId']: table for table in reference.get('tables', [])}
    glyphs = {glyph['id']: glyph for page in native['observation']['pageObservations']
              for glyph in page['nativePdfEvidence']['glyphs']}
    result, owned = {}, set()
    for group in manifest['tableGroups']:
        _fields(group, {'tableGroupId', 'referenceTableId', 'regionIds', 'fragments', 'logicalRows',
                        'reviewedAttributes'}, 'illegal_native_pdf_table_group')
        gid = _name(group.get('tableGroupId'), 'tableGroupId')
        ids = group.get('regionIds')
        _fail(gid not in result and isinstance(ids, list) and ids and len(ids) == len(set(ids))
              and set(ids) <= set(parts) and not owned.intersection(ids), 'native_pdf_table_group_membership_invalid')
        _fail(set(group.get('reviewedAttributes', [])) == {'tableInventory', 'cellMembership', 'blankCells', 'logicalRows', 'conditions'},
              'native_pdf_whole_table_source_review_required')
        source = tables.get(group.get('referenceTableId'))
        _fail(source is not None and source.get('completeSourceGroup') is True and source.get('regionIds') == ids,
              'native_pdf_whole_table_reference_missing')
        # All source-declared members and related conditions must enter one unit.
        required = {rid for rid, region in refs.items() if region.get('tableId') == group['referenceTableId']}
        _fail(required == set(ids), 'native_pdf_source_table_members_incomplete')
        fragments, row_refs, cell_ids = [], set(), set()
        for fragment in group['fragments']:
            _fields(fragment, {'regionId', 'rowCount', 'columnCount', 'headerRows', 'cells'}, 'illegal_native_pdf_table_fragment')
            rid = fragment.get('regionId')
            _fail(rid in ids and refs[rid].get('role') in {'table', 'native_table'}
                  and rid not in {f['regionId'] for f in fragments}, 'native_pdf_table_fragment_source_missing')
            rows = _integer(fragment.get('rowCount'), 'native_pdf_table_rows_invalid', 1)
            cols = _integer(fragment.get('columnCount'), 'native_pdf_table_columns_invalid', 1)
            _fail(rows * cols <= MAX_GRID_SLOTS, 'native_pdf_table_topology_resource_limit')
            header = fragment.get('headerRows')
            _fail(isinstance(header, list) and all(type(row) is int and 0 <= row < rows for row in header)
                  and header == sorted(set(header)), 'native_pdf_table_headers_invalid')
            used_slots, used_glyphs, cells = set(), set(), []
            for cell in fragment['cells']:
                _fields(cell, {'cellId', 'row', 'col', 'rowSpan', 'colSpan', 'glyphIds', 'bboxNormalized', 'valueState'},
                        'illegal_native_pdf_table_cell')
                cid = _name(cell.get('cellId'), 'cellId')
                row, col = [_integer(cell.get(key), 'native_pdf_cell_position_invalid') for key in ('row', 'col')]
                rs, cs = [_integer(cell.get(key), 'native_pdf_cell_span_invalid', 1) for key in ('rowSpan', 'colSpan')]
                _fail(row + rs <= rows and col + cs <= cols and cid not in cell_ids, 'native_pdf_cell_span_or_identity_invalid')
                cell_ids.add(cid)
                slots = {(r, c) for r in range(row, row + rs) for c in range(col, col + cs)}
                _fail(not used_slots.intersection(slots), 'native_pdf_cell_slots_overlap'); used_slots |= slots
                members = cell.get('glyphIds')
                _fail(isinstance(members, list) and len(members) == len(set(members))
                      and set(members) <= set(parts[rid]['capturedGlyphIds']) and not used_glyphs.intersection(members),
                      'native_pdf_cell_glyph_membership_invalid')
                member_set = set(members)
                order = [s['glyphId'] for s in parts[rid]['nativeTextSpans'] if s['kind'] == 'native_glyph' and s['glyphId'] in member_set]
                _fail(members == order, 'native_pdf_cell_reading_order_not_reviewed_region_order')
                box = cell.get('bboxNormalized'); parent = parts[rid]['bbox']
                _fail(isinstance(box, list) and len(box) == 4 and all(type(v) in (int, float) for v in box)
                      and parent[0] <= box[0] < box[2] <= parent[2] and parent[1] <= box[1] < box[3] <= parent[3],
                      'native_pdf_cell_source_bounds_invalid')
                for member in members:
                    gx, gy, gw, gh = glyphs[member]['bbox']
                    _fail(box[0] <= gx + gw / 2 <= box[2] and box[1] <= gy + gh / 2 <= box[3],
                          'native_pdf_cell_glyph_geometry_disjoint')
                _fail(cell.get('valueState') == ('literal' if members else 'source_reviewed_blank'), 'native_pdf_cell_blank_unknown_conflict')
                used_glyphs.update(members)
                text_parts = []
                for span in parts[rid]['nativeTextSpans']:
                    if span['kind'] == 'native_glyph' and span['glyphId'] in member_set:
                        text_parts.append(glyphs[span['glyphId']]['unicode'] or '')
                    elif span['kind'] == 'reviewed_separator_not_glyph_observation':
                        separator = span['separator']
                        if separator['afterGlyphId'] in member_set and separator['beforeGlyphId'] in member_set:
                            text_parts.append(separator['text'])
                text = ''.join(text_parts)
                cells.append({**copy.deepcopy(cell), 'text': text, 'textChannel': 'same_version_literal_native_glyphs',
                              'sourceRegionId': rid, 'sourceAnchor': copy.deepcopy(parts[rid]['sourceReviewAnchor']),
                              'unicodeStatus': 'unknown_null_unicode_present' if any(glyphs[m]['unicode'] is None for m in members) else 'literal_observed'})
            _fail(used_slots == {(r, c) for r in range(rows) for c in range(cols)}, 'native_pdf_table_column_or_row_missing')
            _fail(used_glyphs == set(parts[rid]['capturedGlyphIds']), 'native_pdf_table_original_glyph_missing')
            row_refs |= {(rid, row) for row in range(rows) if row not in header}
            fragments.append({**copy.deepcopy(fragment), 'cells': cells, 'physicalPageNo': parts[rid]['physicalPageNo']})
        _fail(fragments and {f['columnCount'] for f in fragments} == {fragments[0]['columnCount']},
              'native_pdf_table_continuation_columns_unknown')
        used_rows, logical_ids = set(), set()
        for row in group['logicalRows']:
            _fields(row, {'logicalRowId', 'physicalRows'}, 'illegal_native_pdf_logical_row')
            lid = _name(row.get('logicalRowId'), 'logicalRowId')
            physical = row.get('physicalRows')
            _fail(lid not in logical_ids and isinstance(physical, list) and physical, 'native_pdf_logical_row_identity_invalid')
            logical_ids.add(lid)
            pairs = []
            for part in physical:
                _fields(part, {'regionId', 'row'}, 'illegal_native_pdf_physical_row')
                pairs.append((part.get('regionId'), _integer(part.get('row'), 'native_pdf_physical_row_invalid')))
            _fail(len(pairs) == len(set(pairs)) and set(pairs) <= row_refs and not used_rows.intersection(pairs),
                  'native_pdf_logical_row_membership_invalid')
            positions = [(ids.index(rid), r) for rid, r in pairs]
            _fail(positions == sorted(positions), 'native_pdf_logical_row_source_order_invalid')
            for (left, _), (right, _) in zip(pairs, pairs[1:]):
                if left != right:
                    _fail(any(rel['type'] == 'continuation' and rel['fromRegionId'] == left and rel['toRegionId'] == right
                              for rel in reference.get('relations', [])), 'native_pdf_cross_page_row_source_relation_missing')
            _fail(all(left != right or following == previous + 1 for (left, previous), (right, following) in zip(pairs, pairs[1:])),
                  'native_pdf_same_page_logical_row_nonconsecutive')
            used_rows.update(pairs)
        _fail(used_rows == row_refs, 'native_pdf_logical_rows_incomplete')
        owned.update(ids)
        result[gid] = {**copy.deepcopy(group), 'fragments': fragments, 'sourceQuality': 'unknown',
                       'completeness': {'required': ids, 'present': ids, 'missing': [], 'unknown': ['legal_semantics', 'pixel_verified_visibility']}}
    return result


def project_page_assets(manifest, reference, store):
    pages = {page['sourceAnchor']['physicalPageNo']: page for page in reference['pages']}
    result, ids = [], set()
    for asset in manifest['assets']:
        _fields(asset, {'assetId', 'kind', 'physicalPageNo', 'artifactDescriptor'}, 'illegal_native_pdf_asset')
        aid = _name(asset.get('assetId'), 'assetId'); page = pages.get(asset.get('physicalPageNo'))
        _fail(aid not in ids and page is not None and asset.get('kind') == 'original_page'
              and asset.get('physicalPageNo') in manifest['scope']['requiredPhysicalPages'], 'native_pdf_original_page_asset_required')
        ids.add(aid); descriptor = asset['artifactDescriptor']; raw = store.read_asset(descriptor)
        _fail(descriptor in manifest['reference']['pageImages'] and descriptor['sha256'] == page['sourceImage']['sha256'],
              'native_pdf_original_page_asset_identity_mismatch')
        media = 'image/png' if raw.startswith(b'\x89PNG\r\n\x1a\n') else 'image/jpeg' if raw.startswith(b'\xff\xd8\xff') else None
        _fail(media is not None, 'native_pdf_original_page_asset_media_unsupported')
        result.append({**copy.deepcopy(asset), 'sourceSha256': manifest['sourceSha256'],
                       'sha256': descriptor['sha256'], 'byteCount': descriptor['byteCount'], 'mediaType': media,
                       'physicalPages': [asset['physicalPageNo']],
                       'sourceRelation': copy.deepcopy(page['sourceImage']['sourceRelation']),
                       'geometryStatus': 'whole_original_page_no_unverified_crop', 'quality': 'unknown'})
    return result
