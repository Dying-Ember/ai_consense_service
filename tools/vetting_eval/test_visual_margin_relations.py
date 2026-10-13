"""Margin-note relations must bind exact selected whole blocks without rewrite."""
import copy
import unittest

from canonical_projection import CanonicalProjectionError, project_canonical, validate_projection
from test_canonical_projection import fixture


class VisualMarginRelationsTests(unittest.TestCase):
    def setup_case(self, relation_type='margin_note'):
        bundle, manifest = fixture()
        run = bundle['runs'][0]
        note = run['pages'][0]['blocks'][2]
        manifest['units'].append({'unitId': 'margin', 'scope': 'region', 'runId': run['runId'],
            'blockId': note['blockId'], 'reviewedAttributes': ['text', 'geometry']})
        heading = manifest['units'][0]['blockId']
        manifest['relations'].append({'type': relation_type, 'fromUnitId': 'margin', 'toUnitId': 'heading',
            'fromBlockId': note['blockId'], 'toBlockId': heading})
        return bundle, manifest, note

    def test_margin_note_preserves_text_and_exact_selected_block_endpoints(self):
        bundle, manifest, note = self.setup_case()
        before = copy.deepcopy(bundle)
        result = project_canonical(bundle, manifest)
        relation = result['canonical']['relations'][-1]
        self.assertEqual('margin_note', relation['type'])
        self.assertEqual(note['blockId'], relation['fromBlockId'])
        unit = next(u for u in result['canonical']['units'] if u['unitId'] == 'margin')
        self.assertEqual(note['text'], unit['parts'][0]['text'])
        self.assertEqual('explicit_source_reviewed', relation['status'])
        self.assertFalse(result['humanConfirmed'])
        self.assertEqual(before, bundle)
        self.assertEqual(result, validate_projection(result))

    def test_sidebar_is_retained_as_a_distinct_visual_relation(self):
        bundle, manifest, _ = self.setup_case('sidebar')
        result = project_canonical(bundle, manifest)
        self.assertEqual('sidebar', result['canonical']['relations'][-1]['type'])

    def test_note_cannot_point_to_unselected_unit(self):
        bundle, manifest, _ = self.setup_case()
        manifest['relations'][-1]['toUnitId'] = 'not-selected'
        with self.assertRaises(CanonicalProjectionError) as failure:
            project_canonical(bundle, manifest)
        self.assertEqual('relation_member_unselected', failure.exception.code)

    def test_note_cannot_point_to_a_different_block_of_another_unit(self):
        bundle, manifest, note = self.setup_case()
        manifest['relations'][-1]['toBlockId'] = note['blockId']
        with self.assertRaises(CanonicalProjectionError) as failure:
            project_canonical(bundle, manifest)
        self.assertEqual('relation_block_outside_unit', failure.exception.code)

    def test_unrecognized_relation_still_rejected(self):
        bundle, manifest, _ = self.setup_case('invented_operative_rule')
        with self.assertRaises(CanonicalProjectionError) as failure:
            project_canonical(bundle, manifest)
        self.assertEqual('unknown_relation_type', failure.exception.code)


if __name__ == '__main__':
    unittest.main()
