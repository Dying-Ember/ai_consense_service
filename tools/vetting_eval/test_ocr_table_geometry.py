"""Exercise geometry and evidence preservation without OCR/model initialization."""
import unittest
from PIL import Image, ImageDraw
from ocr_table_geometry import recover_ruled_tables


def grid(*, rotated=False):
    image = Image.new('RGB', (300, 240), 'white')
    draw = ImageDraw.Draw(image)
    for x in (30, 150, 270):
        draw.line([(x, 30), (x, 210)], fill='black', width=2)
    for y in (30, 90, 150, 210):
        draw.line([(30, y), (270, y)], fill='black', width=2)
    return image.transpose(Image.Transpose.ROTATE_90) if rotated else image


def line(identity, text, box):
    x0, y0, x1, y1 = box
    return {'id': identity, 'text': text, 'confidence': .95,
            'polygon': [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]}


class RuledTableEvidenceTest(unittest.TestCase):
    def test_grid_recovers_pair_in_same_row_and_unknown_cell_stays_unknown(self):
        lines = [line('left', 'negation *must not*', [50, 110, 125, 132]),
                 line('right', '600', [170, 110, 220, 132])]
        result = recover_ruled_tables(grid(), lines)
        self.assertEqual(1, len(result['tables']))
        table = result['tables'][0]
        matched = [c for c in table['cells'] if c['sourceLineIds']]
        self.assertEqual(2, len(matched))
        self.assertEqual(matched[0]['row'], matched[1]['row'])
        self.assertNotEqual(matched[0]['column'], matched[1]['column'])
        self.assertEqual(['negation *must not*', '600'], [c['text'] for c in matched])
        self.assertTrue(all(c['textStatus'] == 'no_assigned_ocr_text_unknown'
                            for c in table['cells'] if not c['sourceLineIds']))
        self.assertFalse(table['nativeTable'])
        self.assertFalse(table['semanticStructureVerified'])
        self.assertTrue(all(c['headerStatus'] == 'unknown' for c in table['cells']))

    def test_text_without_closed_grid_is_not_invented_as_a_table(self):
        image = Image.new('RGB', (300, 240), 'white')
        result = recover_ruled_tables(image, [line('one', '2.0 M 680', [40, 70, 120, 90])])
        self.assertEqual([], result['tables'])
        self.assertEqual('no_ruled_candidate', result['status'])

    def test_padded_ocr_box_keeps_dominant_cell_without_cropping_text(self):
        result = recover_ruled_tables(grid(), [line('padded', 'full original line', [50, 84, 125, 107])])
        assigned = [c for c in result['tables'][0]['cells'] if c['sourceLineIds']]
        self.assertEqual(1, len(assigned))
        self.assertEqual(1, assigned[0]['row'])
        self.assertEqual('full original line', assigned[0]['text'])
        self.assertTrue(assigned[0]['textMayCrossRasterCellBoundaries'])
        self.assertEqual('padded', assigned[0]['sourceLineAssignments'][0]['sourceLineId'])
        self.assertTrue(assigned[0]['sourceLineAssignments'][0]['competingCells'])

    def test_line_spanning_cells_is_explicitly_unassigned_not_silently_lost(self):
        result = recover_ruled_tables(grid(), [line('spans', 'unresolved', [90, 110, 210, 132])])
        table = result['tables'][0]
        ids = table['ambiguousSourceLineIds'] + table['unassignedSourceLineIds']
        self.assertIn('spans', ids)
        self.assertTrue(all(not c['sourceLineIds'] for c in table['cells']))

    def test_wrong_coordinate_frame_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'outside'):
            recover_ruled_tables(grid(), [line('outside', 'bad', [290, 40, 360, 90])])
        with self.assertRaisesRegex(ValueError, 'finite'):
            recover_ruled_tables(grid(), [line('nonfinite', 'bad', [40, 40, float('nan'), 90])])

    def test_rotated_input_uses_its_actual_coordinate_frame(self):
        result = recover_ruled_tables(grid(rotated=True), [])
        self.assertEqual([240, 300], result['imageSize'])
        self.assertEqual(1, len(result['tables']))
        self.assertEqual(4, len(result['tables'][0]['columnBoundaries']))
        self.assertEqual(3, len(result['tables'][0]['rowBoundaries']))


if __name__ == '__main__': unittest.main()
