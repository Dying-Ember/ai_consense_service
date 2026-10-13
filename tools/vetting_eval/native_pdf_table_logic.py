"""Source-preserving logical proposals; geometry/text agreement is not quality.

No proposal is an adoption. In particular, an empty returned grid cell cannot
prove a source blank, and a complete grid cannot prove complete source members.
"""
from __future__ import annotations

import copy
import re

from native_pdf_table_conditions import observe_groups


SCHEMA = 'consense.native-pdf-table-candidate.v1'
ITEM = re.compile(r'^\s*(\d+(?:\.\d+)*[.)]?|\((?:\d+|[A-Za-z]|[ivxlcdmIVXLCDM]+)\))\s*$')
CONDITION = re.compile(r'^\s*(?:notes?\s*[:.]|\*|abbreviations?\s*[:.]|where\b|provided\b)', re.I)


def _literal(value):
    return value if isinstance(value, str) else ''


def _overlaps(a, b):
    return min(a[2], b[2]) > max(a[0], b[0]) and min(a[3], b[3]) > max(a[1], b[1])


def _words(value):
    # Diagnostic only; original strings and glyphs remain untouched.
    return ''.join(_literal(value).split())


def _numbered_grid(table, rows, logical_rows, numbering_col):
    """One-provider header/body proposal, with no source-driven crop or repair."""
    if numbering_col is None or any(cell['rowSpan'] != 1 or cell['colSpan'] != 1 for row in rows.values() for cell in row):
        return None
    columns = sorted({cell['col'] for row in rows.values() for cell in row})
    body = [row for row in logical_rows if row['role'] == 'numbered_row_proposal']
    if len(columns) < 2 or len(body) < 2:
        return None
    first = body[0]['physicalRows'][0]
    headers = [index for index, row in rows.items() if index < first
               and [cell['col'] for cell in row] == columns and all(_literal(cell.get('text')).strip() for cell in row)]
    if not headers:
        return None
    header_start = max(headers)
    header = list(range(header_start, first))
    if any(index not in rows for index in header):
        return None
    selected, previous = [], first - 1
    for row in body:
        if row['physicalRows'][0] != previous + 1:
            break
        selected.append(row); previous = row['physicalRows'][-1]
    if len(selected) < 2:
        return None
    groups = [header, *[row['physicalRows'] for row in selected]]
    origins, used = [], set()
    for row_index, members in enumerate(groups):
        for col in columns:
            source = [next((cell for cell in rows[index] if cell['col'] == col), None) for index in members]
            if any(cell is None or cell.get('bbox') is None for cell in source):
                return None
            ids = [cell['physicalCellId'] for cell in source]; used.update(ids)
            boxes = [cell['bbox'] for cell in source]
            known = all(isinstance(cell.get('text'), str) and cell.get('spanKnown') for cell in source)
            origins.append({'row': row_index, 'col': columns.index(col), 'rowSpan': 1, 'colSpan': 1,
                'text': '\n'.join(cell['text'] for cell in source) if known else None,
                'bbox': [min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes)],
                'spanKnown': known, 'physicalCellIds': ids,
                'glyphIds': [gid for cell in source for gid in cell.get('glyphIds', [])],
                'transform': 'same_provider_column_preserving_physical_row_group',
                'sourceMembershipStatus': 'proposal_not_source_reviewed'})
    return {'tableId': table['tableId'] + ':numbered-logical', 'rowCount': len(groups), 'colCount': len(columns),
            'bbox': [min(cell['bbox'][0] for cell in origins), min(cell['bbox'][1] for cell in origins),
                     max(cell['bbox'][2] for cell in origins), max(cell['bbox'][3] for cell in origins)],
            'origins': origins, 'physicalRows': groups, 'numberingColumn': numbering_col,
            'providerTableId': table['tableId'],
            'excludedPhysicalCellIds': [cell['physicalCellId'] for row in rows.values() for cell in row if cell['physicalCellId'] not in used],
            'excludedMembership': 'preserved_in_whole_provider_output_unknown_role',
            'textTransform': 'original_cell_literals_with_explicit_row_separators_no_repair',
            'completeTableGroupAccepted': False}


def recover_table(table, observation):
    """Retain every physical cell and make reversible numbered-row proposals."""
    glyphs = {g['glyphId']: g for g in observation['glyphs']}
    cells, owned, issues = [], {}, []
    for index, origin in enumerate(table['origins']):
        cell = copy.deepcopy(origin)
        cell['physicalCellId'] = table['tableId'] + ':cell:' + str(index)
        ids = cell.get('glyphIds', [])
        if any(gid not in glyphs for gid in ids):
            raise ValueError('Cell points outside complete source glyph observation')
        for gid in ids:
            owned.setdefault(gid, []).append(cell['physicalCellId'])
        literal = ''.join(_literal(glyphs[gid]['text']) for gid in ids)
        text = _literal(cell.get('text'))
        overlaps = cell.get('glyphOverlapIds', [])
        cell.update(sourceGlyphText=literal,
                    textAgreement='whitespace_diagnostic_only' if _words(literal) == _words(text) else 'disagreement',
                    valueState='observed_literal' if text.strip() else 'unknown_source_blank',
                    mergeStatus='provider_geometry_proposal' if cell.get('spanKnown') else 'unknown',
                    sourceMembershipStatus='geometric_candidate_not_source_reviewed')
        if text.strip() and not ids or _words(literal) != _words(text):
            issues.append({'kind': 'cell_text_source_disagreement', 'physicalCellId': cell['physicalCellId']})
        if set(overlaps) - set(ids):
            issues.append({'kind': 'glyph_crosses_cell_boundary', 'physicalCellId': cell['physicalCellId'],
                           'glyphIds': sorted(set(overlaps) - set(ids))})
        cells.append(cell)
    box = table.get('bbox')
    inside = {gid for gid, glyph in glyphs.items() if box is not None and _overlaps(box, glyph['bbox'])}
    missing = sorted(inside - set(owned))
    duplicate = sorted(gid for gid, owners in owned.items() if len(owners) != 1)
    rows = {}
    for cell in cells:
        rows.setdefault(cell['row'], []).append(cell)
    for row in rows.values():
        row.sort(key=lambda cell: cell['col'])
    # A column is a numbering proposal only if it has multiple explicit item
    # labels; no fixed column number, target value, page or bbox is accepted.
    columns = {}
    for row_id, row in rows.items():
        for cell in row:
            match = ITEM.fullmatch(_literal(cell.get('text')))
            if match:
                columns.setdefault(cell['col'], []).append((row_id, match.group(1)))
    numbered = [col for col, labels in columns.items() if len(labels) >= 2]
    numbering_col = numbered[0] if len(numbered) == 1 else None
    logical, active, first_item = [], None, None
    for row_id, row in sorted(rows.items()):
        label_cell = next((cell for cell in row if cell['col'] == numbering_col), None)
        label = ITEM.fullmatch(_literal(label_cell.get('text'))) if label_cell else None
        if label:
            first_item = row_id if first_item is None else first_item
            active = {'logicalRowId': table['tableId'] + ':logical:' + str(row_id),
                      'itemLiteral': label.group(1), 'physicalRows': [row_id],
                      'role': 'numbered_row_proposal', 'membershipStatus': 'unknown_requires_source_review'}
            logical.append(active)
        elif (active is not None and label_cell is not None and not _literal(label_cell.get('text')).strip()
              and row_id == active['physicalRows'][-1] + 1 and any(_literal(c.get('text')).strip() for c in row)):
            active['physicalRows'].append(row_id)
        else:
            active = None
            logical.append({'logicalRowId': table['tableId'] + ':logical:' + str(row_id),
                            'itemLiteral': None, 'physicalRows': [row_id], 'role': 'unresolved_row',
                            'membershipStatus': 'unknown_requires_source_review'})
    header_rows = [row for row in sorted(rows) if first_item is not None and row < first_item]
    for row in logical:
        if all(r in header_rows for r in row['physicalRows']):
            row['role'] = 'header_proposal'
    grid = _numbered_grid(table, rows, logical, numbering_col)
    return {'schema': SCHEMA, 'tableId': table['tableId'], 'cells': cells, 'logicalRows': logical,
            'logicalTable': grid,
            'headerRows': header_rows, 'numberingColumnProposal': numbering_col,
            'coverage': {'required': sorted(inside), 'present': sorted(set(owned) & inside),
                         'missing': missing, 'duplicate': duplicate,
                         'outsideTableGlyphIds': sorted(set(glyphs) - inside),
                         'wholeSourceMembership': 'unknown', 'wholeTableAccepted': False},
            'issues': issues, 'publicationStatus': 'unpublished_candidate',
            'conditions': {'required': 'unknown', 'present': [], 'missing': [], 'unknown': ['source_boundary', 'notes_owner', 'parent_clause', 'cross_page_members']},
            'providerTablePreserved': True, 'candidateFusion': False}


def recover_page(tables, observation):
    proposals = [recover_table(table, observation) for table in tables]
    # Preserve entire outside-source lines as possible conditions, never append
    # them to the nearest table or manufacture a condition from a keyword.
    outside = set(g['glyphId'] for g in observation['glyphs'])
    for proposal in proposals:
        outside -= set(proposal['coverage']['required'])
    lines = []
    for glyph in observation['glyphs']:
        if glyph['glyphId'] not in outside:
            continue
        box = glyph['bbox']
        current = next((line for line in lines if abs(line['top'] - box[1]) <= max(1, (box[3] - box[1]) / 4)), None)
        if current is None:
            current = {'top': box[1], 'glyphs': []}; lines.append(current)
        current['glyphs'].append(glyph)
    candidates = []
    for line in sorted(lines, key=lambda line: line['top']):
        ordered = sorted(line['glyphs'], key=lambda glyph: glyph['bbox'][0])
        literal = ''.join(_literal(glyph['text']) for glyph in ordered)
        if CONDITION.search(literal):
            candidates.append({'text': literal, 'glyphIds': [g['glyphId'] for g in ordered],
                               'role': 'condition_line_proposal', 'owner': 'unknown',
                               'lineAssembly': 'geometry_proposal_original_glyphs_retained'})
    logical_tables = [proposal['logicalTable'] for proposal in proposals if proposal['logicalTable'] is not None]
    return {'schema': SCHEMA, 'tables': proposals,
            'logicalTables': logical_tables, 'observationGroups': observe_groups(logical_tables, observation),
            'conditionCandidates': candidates,
            'unassignedSourceGlyphIds': sorted(outside), 'completeSourceGroupAccepted': False,
            'quality': 'unknown_not_independently_source_scored'}
