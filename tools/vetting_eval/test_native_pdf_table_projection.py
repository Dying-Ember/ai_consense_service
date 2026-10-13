"""Authored topology declaration controls, not actual table/source acceptance."""
import copy
import json
import tempfile
import unittest

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _hash
from native_pdf_canonical_projection import produce_native_pdf, validate_native_pdf_projection
from native_pdf_projection_transport import publish_native_pdf_transport, hydrate_native_pdf_transport
from native_pdf_source_registry import NativePdfCanonicalSourceRegistry
from test_native_pdf_canonical_projection import fixture


class NativePdfTableProjectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.store = ArtifactStore(self.temporary.name + '/cas')
        self.native, self.dual, self.manifest = fixture(self.store)
        reference = json.loads(self.store.read_asset(self.manifest['reference']['artifactDescriptor']))
        for region in reference['regions']: region['tableId'] = 'AUTHORED-table'
        reference['regions'][0]['role'] = 'table'
        reference['tables'] = [{'tableId': 'AUTHORED-table', 'completeSourceGroup': True, 'regionIds': ['r1', 'r2']}]
        descriptor = self.store.put_bytes(_bytes(reference))
        self.manifest['reference'].update(artifactDescriptor=descriptor, evidenceSha256=descriptor['sha256'])
        self.manifest['regionReview']['referenceSha256'] = descriptor['sha256']
        ids = self.manifest['regionReview']['regions'][0]['glyphIdsInReadingOrder']
        cells = [{'cellId': 'AUTHORED-cell', 'row': 0, 'col': 0, 'rowSpan': 1, 'colSpan': 1,
                  'glyphIds': ids, 'bboxNormalized': [0, 0, 0.9, 1], 'valueState': 'literal'},
                 {'cellId': 'AUTHORED-blank', 'row': 0, 'col': 1, 'rowSpan': 1, 'colSpan': 1,
                  'glyphIds': [], 'bboxNormalized': [0.9, 0, 1, 1], 'valueState': 'source_reviewed_blank'}]
        self.manifest['tableGroups'] = [{'tableGroupId': 'AUTHORED-group', 'referenceTableId': 'AUTHORED-table',
            'regionIds': ['r1', 'r2'], 'reviewedAttributes': ['tableInventory', 'cellMembership', 'blankCells', 'logicalRows', 'conditions'],
            'fragments': [{'regionId': 'r1', 'rowCount': 1, 'columnCount': 2, 'headerRows': [], 'cells': cells}],
            'logicalRows': [{'logicalRowId': 'AUTHORED-row', 'physicalRows': [{'regionId': 'r1', 'row': 0}]}]}]
        self.manifest['units'][0].update(scope='whole_table_group', tableGroupId='AUTHORED-group')
        self.manifest['assets'] = [{'assetId': 'AUTHORED-page-' + str(index + 1), 'kind': 'original_page',
                                   'physicalPageNo': index + 1, 'artifactDescriptor': descriptor}
                                  for index, descriptor in enumerate(self.manifest['reference']['pageImages'])]

    def produce(self, manifest=None):
        return produce_native_pdf(self.native, self.dual, manifest or self.manifest, artifact_store=self.store)

    def test_complete_group_preserves_native_literal_blank_notes_and_original_assets(self):
        projection = self.produce(); chunk = projection['chunks'][0]
        self.assertEqual('whole_table_group', chunk['canonicalScope'])
        self.assertEqual('Visible fi cafe\nSecond page', chunk['content'])
        cells = chunk['nativeTables'][0]['fragments'][0]['cells']
        self.assertEqual(['Visible fi cafe', ''], [cell['text'] for cell in cells])
        self.assertEqual('source_reviewed_blank', cells[1]['valueState'])
        self.assertEqual(['r1', 'r2'], chunk['nativeTables'][0]['completeness']['required'])
        self.assertEqual(2, len(projection['assetRefs'])); self.assertFalse(projection['semanticScopeVerified'])
        self.assertEqual(projection, validate_native_pdf_projection(projection, artifact_store=self.store))

    def test_public_transport_and_registry_return_exact_original_page(self):
        internal = self.produce(); public = publish_native_pdf_transport(internal, artifact_store=self.store)
        self.assertIn('nativeTableGroupsRef', public['chunks'][0]); self.assertNotIn('nativeTables', public['chunks'][0])
        self.assertEqual(internal, hydrate_native_pdf_transport(public, artifact_store=self.store))
        registry = NativePdfCanonicalSourceRegistry(self.temporary.name + '/registry', self.store)
        registry.publish(public)
        asset = public['assetRefs'][0]
        returned = registry.asset(public['projectionSha256'], public['source']['sourceSha256'], asset['assetId'])
        self.assertEqual(self.store.read_asset(asset['artifactDescriptor']), returned[0])

    def test_missing_column_glyph_condition_and_false_blank_refuse_without_repair(self):
        for mutation in ('column', 'glyph', 'condition', 'blank', 'prose', 'overlap', 'row', 'boolean_row', 'huge_grid'):
            with self.subTest(mutation=mutation):
                manifest = copy.deepcopy(self.manifest); group = manifest['tableGroups'][0]
                cells = group['fragments'][0]['cells']
                if mutation == 'column': cells.pop()
                elif mutation == 'glyph': cells[0]['glyphIds'].pop()
                elif mutation == 'condition': group['regionIds'].pop()
                elif mutation == 'blank': cells[1]['valueState'] = 'unknown'
                elif mutation == 'prose': manifest['units'][0].update(scope='whole_text_unit'); manifest['units'][0].pop('tableGroupId')
                elif mutation == 'overlap': cells[1]['col'] = 0
                elif mutation == 'boolean_row': group['logicalRows'][0]['physicalRows'][0]['row'] = False
                elif mutation == 'huge_grid': group['fragments'][0]['columnCount'] = 1000000000
                else: group['logicalRows'] = []
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_table_cell_keeps_reviewed_separator_in_same_cell(self):
        region = self.manifest['regionReview']['regions'][0]; ids = region['glyphIdsInReadingOrder']
        region['separators'] = [{'separatorId': 'AUTHORED-line', 'kind': 'reviewed_separator', 'text': '\n',
                                'afterGlyphId': ids[0], 'beforeGlyphId': ids[1],
                                'reviewMethod': 'original_page_raster_boundary'}]
        self.assertTrue(self.produce()['chunks'][0]['nativeTables'][0]['fragments'][0]['cells'][0]['text'].startswith('V\nisible'))

    def test_stale_asset_and_resealed_derived_table_text_are_rejected(self):
        manifest = copy.deepcopy(self.manifest); manifest['assets'][0]['physicalPageNo'] = 2
        with self.assertRaises(CanonicalProjectionError): self.produce(manifest)
        projection = self.produce(); projection['chunks'][0]['nativeTables'][0]['fragments'][0]['cells'][0]['text'] = 'repair'
        projection['projectionSha256'] = _hash({k: v for k, v in projection.items() if k != 'projectionSha256'})
        with self.assertRaises(CanonicalProjectionError): validate_native_pdf_projection(projection, artifact_store=self.store)

    def test_multirow_merged_header_keeps_all_slots_and_original_glyphs(self):
        fragment = self.manifest['tableGroups'][0]['fragments'][0]
        ids = fragment['cells'][0]['glyphIds']
        header = copy.deepcopy(fragment['cells'][0]); header.update(cellId='header', colSpan=2, glyphIds=ids[:8], bboxNormalized=[0, 0, 1, 1])
        body = copy.deepcopy(fragment['cells'][0]); body.update(row=1, glyphIds=ids[8:])
        blank = copy.deepcopy(fragment['cells'][1]); blank.update(row=1)
        fragment.update(rowCount=2, headerRows=[0], cells=[header, body, blank])
        self.manifest['tableGroups'][0]['logicalRows'][0]['physicalRows'][0]['row'] = 1
        projected = self.produce()['chunks'][0]['nativeTables'][0]['fragments'][0]
        self.assertEqual(2, projected['cells'][0]['colSpan']); self.assertEqual([0], projected['headerRows'])
        self.assertEqual('Visible fi cafe', ''.join(cell['text'] for cell in projected['cells']))

    def test_crosspage_logical_row_requires_same_source_continuation_relation(self):
        reference = json.loads(self.store.read_asset(self.manifest['reference']['artifactDescriptor']))
        reference['regions'][1]['role'] = 'table'
        descriptor = self.store.put_bytes(_bytes(reference))
        self.manifest['reference'].update(artifactDescriptor=descriptor, evidenceSha256=descriptor['sha256'])
        self.manifest['regionReview']['referenceSha256'] = descriptor['sha256']
        group = self.manifest['tableGroups'][0]
        tail = copy.deepcopy(group['fragments'][0]); tail['regionId'] = 'r2'
        tail['cells'][0].update(cellId='tail', glyphIds=self.manifest['regionReview']['regions'][1]['glyphIdsInReadingOrder'])
        tail['cells'][1]['cellId'] = 'tail-blank'
        group['fragments'].append(tail)
        group['logicalRows'][0]['physicalRows'].append({'regionId': 'r2', 'row': 0})
        self.assertEqual(2, len(self.produce()['chunks'][0]['nativeTables'][0]['fragments']))
        reference['relations'] = []
        descriptor = self.store.put_bytes(_bytes(reference))
        self.manifest['reference'].update(artifactDescriptor=descriptor, evidenceSha256=descriptor['sha256'])
        self.manifest['regionReview']['referenceSha256'] = descriptor['sha256']; self.manifest['sourceRelationIndices'] = []
        with self.assertRaises(CanonicalProjectionError): self.produce()


if __name__ == '__main__': unittest.main()
