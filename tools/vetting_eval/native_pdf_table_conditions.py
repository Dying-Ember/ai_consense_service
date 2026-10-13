"""Reversible same-page source observations and explicit marker associations.

Layout association never certifies legal applicability. Ambiguous owners and
cross-page continuations remain unassigned with the complete source inventory.
"""
from __future__ import annotations

from collections import Counter
import re


NOTE_HEADER = re.compile(r'^\s*notes?\s*[:.]?\s*$', re.I)
NOTE_ITEM = re.compile(r'^\s*\((\d+)\)\s*')
NOTE_MARKER = re.compile(r'\bnotes?\s*\((\d+(?:\s*[,;]\s*\d+)*)\)', re.I)


def source_lines(glyphs):
    lines = []
    for glyph in glyphs:
        box = glyph['bbox']; center = (box[1] + box[3]) / 2
        line = next((line for line in lines if abs(line['center'] - center) <= max(1, (box[3] - box[1]) / 3)), None)
        if line is None:
            line = {'center': center, 'glyphs': []}; lines.append(line)
        line['glyphs'].append(glyph)
    result = []
    for line in sorted(lines, key=lambda line: line['center']):
        ordered = sorted(line['glyphs'], key=lambda glyph: (glyph['bbox'][0], glyph['glyphId']))
        boxes = [glyph['bbox'] for glyph in ordered]
        sizes = [glyph.get('raw', {}).get('size') for glyph in ordered]
        known_sizes = [round(size, 2) for size in sizes if type(size) in (int, float)]
        result.append({'text': ''.join(glyph['text'] or '' for glyph in ordered),
            'glyphIds': [glyph['glyphId'] for glyph in ordered],
            'bbox': [min(box[0] for box in boxes), min(box[1] for box in boxes), max(box[2] for box in boxes), max(box[3] for box in boxes)],
            'fontSize': Counter(known_sizes).most_common(1)[0][0] if known_sizes else None,
            'lineAssembly': 'native_glyph_geometry_proposal_with_original_ids'})
    return result


def _member(mid, role, lines):
    boxes = [line['bbox'] for line in lines]
    return {'memberId': mid, 'role': role, 'text': '\n'.join(line['text'] for line in lines),
            'glyphIds': [gid for line in lines for gid in line['glyphIds']], 'lines': lines,
            'bbox': [min(box[0] for box in boxes), min(box[1] for box in boxes), max(box[2] for box in boxes), max(box[3] for box in boxes)],
            'membershipStatus': 'source_layout_proposal_not_reviewed'}


def observe_groups(logical_tables, observation):
    glyphs = {glyph['glyphId']: glyph for glyph in observation['glyphs']}
    if len(logical_tables) != 1:
        return {'groups': [], 'unassignedGlyphIds': sorted(glyphs), 'status': 'unknown_unique_table_owner_not_established'}
    table = logical_tables[0]; tid = table['tableId']
    occupied = {gid for cell in table['origins'] for gid in cell['glyphIds']}
    if not occupied or not occupied <= set(glyphs):
        return {'groups': [], 'unassignedGlyphIds': sorted(glyphs), 'status': 'unknown_table_source_membership'}
    lines = source_lines([glyph for gid, glyph in glyphs.items() if gid not in occupied])
    body_top = min(glyphs[gid]['bbox'][1] for gid in occupied)
    body_bottom = max(glyphs[gid]['bbox'][3] for gid in occupied)
    members, relations, used = [], [], set(occupied)
    before = [line for line in lines if line['bbox'][3] <= body_top]
    caption = []
    for line in reversed(before):
        next_top = caption[0]['bbox'][1] if caption else body_top
        height = line['bbox'][3] - line['bbox'][1]
        if next_top - line['bbox'][3] > max(1, height * 1.5): break
        if caption and line['fontSize'] != caption[0]['fontSize']: break
        caption.insert(0, line)
    if caption:
        member = _member(tid + ':caption-layout', 'caption_layout_proposal', caption); members.append(member)
        used.update(member['glyphIds'])
        relations.append({'type': 'caption_of', 'fromMemberId': member['memberId'], 'toTableId': tid,
                          'status': 'single_table_adjacent_layout_proposal'})
    after = [line for line in lines if line['bbox'][1] >= body_bottom]
    headers = [index for index, line in enumerate(after) if NOTE_HEADER.fullmatch(line['text'])]
    if len(headers) != 1:
        return {'groups': [{'tableId': tid, 'members': members, 'relations': relations,
                'wholeSourceGroupAccepted': False, 'unknown': ['notes_owner', 'cross_page_members', 'legal_applicability']}],
                'unassignedGlyphIds': sorted(set(glyphs) - used), 'status': 'unknown_unique_notes_header_not_established'}
    start = headers[0]; note_size = after[start]['fontSize']
    selected = []
    for line in after:
        if selected and note_size is not None and line['fontSize'] is not None and line['fontSize'] != note_size:
            break
        selected.append(line)
    if len(selected) <= start:
        return {'groups': [], 'unassignedGlyphIds': sorted(set(glyphs) - occupied), 'status': 'unknown_notes_style_boundary'}
    member = _member(tid + ':notes-layout', 'complete_adjacent_notes_context_proposal', selected)
    members.append(member); used.update(member['glyphIds'])
    relations.append({'type': 'note_applies_to', 'fromMemberId': member['memberId'], 'toTableId': tid,
                      'status': 'single_table_adjacent_explicit_notes_header_proposal'})
    notes, active = {}, None
    for line in selected[start + 1:]:
        match = NOTE_ITEM.match(line['text'])
        if match:
            active = {'number': int(match.group(1)), 'lines': []}
            notes.setdefault(active['number'], []).append(active)
        if active is not None: active['lines'].append(line)
    for cell in table['origins']:
        for marker in NOTE_MARKER.finditer(cell.get('text') or ''):
            for value in re.split(r'[,;]', marker.group(1)):
                number = int(value); definitions = notes.get(number, [])
                if len(definitions) == 1:
                    relations.append({'type': 'note_applies_to', 'fromMemberId': member['memberId'],
                        'toCell': {'tableId': tid, 'row': cell['row'], 'col': cell['col']}, 'sourceNoteMarker': number,
                        'sourceMarkerExpression': marker.group(0), 'booleanMeaning': 'literal_expression_retained_not_inferred',
                        'definitionGlyphIds': [gid for line in definitions[0]['lines'] for gid in line['glyphIds']],
                        'status': 'same_page_unique_explicit_marker_definition'})
    star_lines = [line for line in selected if line['text'].lstrip().startswith('*')]
    starred = [cell for cell in table['origins'] if re.search(r'\*\s*$', cell.get('text') or '')]
    if len(star_lines) == 1 and starred:
        star = _member(tid + ':star-marker', 'explicit_star_condition_proposal', star_lines)
        star['sharedWithinMemberId'] = member['memberId']; members.append(star)
        for cell in starred:
            relations.append({'type': 'condition_applies_to', 'fromMemberId': star['memberId'],
                'toCell': {'tableId': tid, 'row': cell['row'], 'col': cell['col']},
                'status': 'same_page_unique_explicit_star_definition'})
    return {'groups': [{'tableId': tid, 'members': members, 'relations': relations,
            'wholeSourceGroupAccepted': False, 'unknown': ['cross_page_members', 'legal_applicability', 'source_pixel_visibility']}],
            'unassignedGlyphIds': sorted(set(glyphs) - used), 'status': 'observed_same_page_proposals_unreviewed'}
