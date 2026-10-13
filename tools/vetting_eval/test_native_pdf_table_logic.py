"""Authored logical proposal controls, not source-quality scores."""
import copy
import unittest

from native_pdf_table_logic import recover_page, recover_table


def authored():
    glyphs, origins = [], []
    grid = [['Item', 'Condition', 'Value'], ['1', 'Use only if required', ''],
            ['', 'except where stated', 'NA'], ['2', 'Second item', 'No']]
    for row, values in enumerate(grid):
        for col, text in enumerate(values):
            ids = []
            for index, letter in enumerate(text):
                gid = len(glyphs); ids.append(gid)
                glyphs.append({'glyphId': gid, 'text': letter,
                               'bbox': [col * 100 + index + 1, row * 20 + 1, col * 100 + index + 2, row * 20 + 8]})
            origins.append({'row': row, 'col': col, 'rowSpan': 1, 'colSpan': 1, 'text': text,
                            'bbox': [col * 100, row * 20, (col + 1) * 100, (row + 1) * 20],
                            'glyphIds': ids, 'glyphOverlapIds': ids, 'spanKnown': True})
    for index, letter in enumerate('Notes: subject to parent clause'):
        glyphs.append({'glyphId': len(glyphs), 'text': letter, 'bbox': [index + 1, 91, index + 2, 98]})
    return {'tableId': 'authored', 'origins': origins, 'bbox': [0, 0, 300, 80]}, {'glyphs': glyphs}


class NativePdfTableLogicTest(unittest.TestCase):
    def test_parenthesized_acronym_is_not_a_second_numbering_column(self):
        table, source = authored()
        table['origins'][4]['text'] = '(NA)'
        table['origins'][10]['text'] = '(NA)'
        result = recover_table(table, source)
        self.assertEqual(0, result['numberingColumnProposal'])
        self.assertEqual('(NA)', result['cells'][4]['text'])
        self.assertIsNotNone(result['logicalTable'])

    def test_multiline_header_is_one_reversible_row_and_caption_notes_stay_outside(self):
        table, source = authored()
        for cell in table['origins']: cell['row'] += 3
        prefix = []
        for row, values in enumerate([['Caption', '', ''], ['Index', 'Requirement', 'Stored in'], ['', '', 'the'], ['', '', 'database']]):
            if row == 1: continue  # Existing authored complete header is the anchor.
            for col, text in enumerate(values):
                prefix.append({'row': row, 'col': col, 'rowSpan': 1, 'colSpan': 1, 'text': text,
                               'bbox': [col * 100, row * 20 - 60, (col + 1) * 100, row * 20 - 40],
                               'glyphIds': [], 'glyphOverlapIds': [], 'spanKnown': True})
        # Put the existing complete header before two sparse continuations.
        for cell in table['origins'][:3]: cell['row'] = 1
        table['origins'] = prefix + table['origins']
        result = recover_table(table, source)['logicalTable']
        self.assertEqual([[1, 2, 3], [4, 5], [6]], result['physicalRows'])
        self.assertEqual('Value\nthe\ndatabase', result['origins'][2]['text'])
        self.assertEqual(3, len(result['excludedPhysicalCellIds']))
        self.assertEqual(3, len(result['origins'][2]['physicalCellIds']))
        self.assertFalse(result['completeTableGroupAccepted'])

    def test_numbered_continuation_header_blank_no_na_and_conditions_stay_distinct(self):
        table, source = authored(); saved = copy.deepcopy(table)
        candidate = recover_page([table], source); result = candidate['tables'][0]
        self.assertEqual([0], result['headerRows'])
        self.assertEqual([[0], [1, 2], [3]], [row['physicalRows'] for row in result['logicalRows']])
        self.assertEqual('unknown_source_blank', result['cells'][5]['valueState'])
        self.assertEqual(['NA', 'No'], [result['cells'][index]['text'] for index in (8, 11)])
        self.assertEqual('unknown', candidate['conditionCandidates'][0]['owner'])
        self.assertEqual([], result['coverage']['missing'])
        self.assertFalse(candidate['completeSourceGroupAccepted']); self.assertEqual(saved, table)

    def test_omitted_column_is_visible_against_source_not_repaired(self):
        table, source = authored(); table['origins'] = [cell for cell in table['origins'] if cell['col'] != 2]
        result = recover_table(table, source)
        self.assertTrue(result['coverage']['missing'])
        self.assertEqual(8, len(result['cells']))
        self.assertFalse(result['coverage']['wholeTableAccepted'])

    def test_provider_text_and_empty_grid_disagreement_is_retained(self):
        table, source = authored(); table['origins'][1]['text'] = ''
        table['origins'][8]['text'] = 'gold-repair'
        result = recover_table(table, source)
        self.assertEqual('unknown_source_blank', result['cells'][1]['valueState'])
        self.assertEqual('Condition', result['cells'][1]['sourceGlyphText'])
        self.assertEqual('gold-repair', result['cells'][8]['text'])
        self.assertEqual('NA', result['cells'][8]['sourceGlyphText'])
        self.assertTrue(result['issues'])

    def test_missing_source_link_refuses_and_duplicate_link_is_visible(self):
        table, source = authored(); table['origins'][0]['glyphIds'].append(99999)
        with self.assertRaises(ValueError): recover_table(table, source)
        table, source = authored(); table['origins'][1]['glyphIds'].append(table['origins'][0]['glyphIds'][0])
        self.assertTrue(recover_table(table, source)['coverage']['duplicate'])


if __name__ == '__main__': unittest.main()
