"""Authored source-marker and full unassigned-inventory controls."""
import copy
import unittest

from native_pdf_table_conditions import observe_groups


def authored(extra_note=False, extra_star=False):
    glyphs = []
    def line(text, y, x=50, size=10):
        ids = []
        for index, letter in enumerate(text):
            gid = len(glyphs); ids.append(gid)
            glyphs.append({'glyphId': gid, 'text': letter, 'bbox': [x + index * 5, y, x + index * 5 + 4, y + size], 'raw': {'size': size}})
        return ids
    line('Authored caption', 80)
    origins = []
    for row, values in enumerate([['Item', 'Requirement'], ['1.', 'Note (1)'], ['2.', 'Casting*']]):
        for col, text in enumerate(values):
            origins.append({'row': row, 'col': col, 'text': text, 'glyphIds': line(text, 100 + row * 20, 50 + col * 150)})
    line('Only when installed', 175)
    line('Note', 195)
    line('(1) Important condition.', 215)
    if extra_note: line('(1) Another definition.', 230)
    line('* Only if required.', 245)
    if extra_star: line('* Another condition.', 260)
    line('Page 1', 280, size=8)
    return {'tableId': 'AUTHORED', 'origins': origins}, {'glyphs': glyphs}


class NativePdfTableConditionsTest(unittest.TestCase):
    def test_comma_marker_list_keeps_each_definition_and_original_expression(self):
        table, source = authored(extra_note=True)
        table['origins'][3]['text'] = 'Notes (1, 2)'
        for glyph in source['glyphs']:
            if glyph['bbox'][1] == 230 and glyph['text'] == '1': glyph['text'] = '2'
        result = observe_groups([table], source)
        links = [relation for relation in result['groups'][0]['relations'] if 'sourceNoteMarker' in relation]
        self.assertEqual([1, 2], [relation['sourceNoteMarker'] for relation in links])
        self.assertTrue(all(relation['sourceMarkerExpression'] == 'Notes (1, 2)' for relation in links))
        self.assertTrue(all(relation['booleanMeaning'].endswith('not_inferred') for relation in links))

    def test_unique_note_and_star_markers_keep_same_source_ids_and_semantics_unknown(self):
        table, source = authored(); before = copy.deepcopy(source)
        result = observe_groups([table], source); group = result['groups'][0]
        links = [relation for relation in group['relations'] if 'toCell' in relation]
        self.assertEqual(['note_applies_to', 'condition_applies_to'], [relation['type'] for relation in links])
        self.assertEqual({'tableId': 'AUTHORED', 'row': 1, 'col': 1}, links[0]['toCell'])
        self.assertEqual(1, links[0]['sourceNoteMarker']); self.assertTrue(links[0]['definitionGlyphIds'])
        self.assertFalse(group['wholeSourceGroupAccepted'])
        self.assertIn('cross_page_members', group['unknown'])
        notes = next(member for member in group['members'] if member['role'] == 'complete_adjacent_notes_context_proposal')
        self.assertIn('Only when installed', notes['text']); self.assertNotIn('Page 1', notes['text'])
        self.assertEqual(before, source)
        used = set(gid for member in group['members'] for gid in member['glyphIds'])
        used |= {gid for cell in table['origins'] for gid in cell['glyphIds']}
        self.assertEqual(set(range(len(source['glyphs']))), used | set(result['unassignedGlyphIds']))

    def test_duplicate_definitions_do_not_make_cell_relationships(self):
        table, source = authored(extra_note=True, extra_star=True)
        result = observe_groups([table], source)
        self.assertFalse(any('toCell' in relation for relation in result['groups'][0]['relations']))

    def test_multiple_tables_keep_all_source_members_unassigned(self):
        table, source = authored(); other = copy.deepcopy(table); other['tableId'] = 'OTHER'
        result = observe_groups([table, other], source)
        self.assertEqual([], result['groups']); self.assertEqual(list(range(len(source['glyphs']))), result['unassignedGlyphIds'])

    def test_no_explicit_notes_header_does_not_infer_owner_from_word_occurrence(self):
        table, source = authored()
        for glyph in source['glyphs']:
            if glyph['bbox'][1] == 195: glyph['text'] = 'x'
        result = observe_groups([table], source)
        self.assertFalse(any(relation['type'] == 'note_applies_to' for group in result['groups'] for relation in group['relations']))
        self.assertIn('unknown', result['status'])


if __name__ == '__main__': unittest.main()
