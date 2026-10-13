"""Authored source-review wire tests; no neural, HTTP, or original-page claims."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import (ADOPTION_SCHEMA, CanonicalProjectionError,
                                  project_canonical, validate_projection)
from structured_parser_evidence import build_dual_parser_evidence, normalize_parser_run
from structural_retrieval import StructuralRecipe, plan
from test_token_windows import CharacterTokenizer
from token_windows import WindowRecipe, verify_window_coverage


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
        separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def fixture(*, revision='b' * 40, source_bytes=b'authored-source-original-PDF-wire',
            mineru_pages=(3, 4), transform_unknown=False, figure=False):
    source = {'sourceFilename': 'AUTHORED-project.pdf', 'mediaType': 'application/pdf',
              'sourceSha256': hashlib.sha256(source_bytes).hexdigest(), 'pageCount': 5}
    input_artifacts = {'original.pdf': source_bytes,
                       'page3.png': b'authored-page3-raster-bytes',
                       'page4.png': b'authored-page4-raster-bytes'}
    runs = []
    for parser, physical_pages in [('paddleocr-vl', (3, 4)), ('mineru', mineru_pages)]:
        maps, outputs_pages = [], []
        for index, physical in enumerate(physical_pages):
            name = 'page' + str(physical) + '.png'
            transform = {'kind': 'affine', 'matrix': [1, 0, 0, 0, 1, 0],
                'inputFrame': {'coordinateFrame': 'raster', 'coordinateUnit': 'pixel',
                    'width': 1000, 'height': 2000, 'artifactName': name,
                    'sha256': hashlib.sha256(input_artifacts[name]).hexdigest()},
                'targetFrame': {'coordinateFrame': 'original_page', 'coordinateUnit': 'pixel',
                    'width': 1000, 'height': 2000, 'physicalPageNo': physical,
                    'sourceSha256': source['sourceSha256']}}
            if transform_unknown:
                transform.pop('inputFrame')
            maps.append({'parserPageIndex': index, 'physicalPageNo': physical,
                'width': 1000, 'height': 2000, 'coordinateUnit': 'pixel',
                'providerInputPageNo': physical, 'inputArtifactName': name,
                'providerBboxFrame': 'raster', 'originalPageTransform': transform})
            html = ('<table><tr><th>Item</th><th>Offer</th></tr>'
                    '<tr><td>' + str(physical) + ' — Must not 😀</td><td></td></tr></table>')
            blocks = [
                {'block_id': 'heading', 'block_label': 'text', 'block_content': 'A1 Contract requirements',
                 'block_bbox': [10, 10, 900, 100], 'block_order': 0},
                {'block_id': 'table', 'block_label': 'image' if figure else 'table',
                 'block_content': None if figure else html,
                 'block_bbox': [100, 200, 900, 1600], 'block_order': 1},
                {'block_id': 'footnote', 'block_label': 'footnote',
                 'block_content': 'Only when active; exception remains.',
                 'block_bbox': [100, 1700, 900, 1800], 'block_order': 2}]
            if parser == 'paddleocr-vl':
                outputs_pages.append({'page_index': index, 'parsing_res_list': blocks})
            else:
                outputs_pages.append({'page_idx': index, 'blocks': [
                    {'id': b['block_id'], 'type': b['block_label'], 'content': b['block_content'],
                     'bbox': b['block_bbox'], 'index': b['block_order'], 'score': .01} for b in blocks]})
        outputs = {'page_json': outputs_pages, 'model_output': {'stage': 'authored-not-a-neural-output'}}
        if parser == 'mineru':
            outputs = {'model_json': {'schema': 'docvortex.model', 'schema_version': '2.0',
                           'pages': outputs_pages, 'page_index_map': [p - 1 for p in physical_pages]},
                       'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': outputs_pages},
                       'model_output': {'stage': 'authored-not-a-neural-output'}}
        identity = {'parserVersion': 'AUTHORED-v1', 'engine': 'AUTHORED-no-inference',
                    'models': [{'modelId': parser + '-fixture', 'revision': revision}],
                    'config': {'authoredFixture': True}}
        run = normalize_parser_run(parser, outputs, source, identity, maps,
            artifacts=input_artifacts, required_artifacts=list(input_artifacts),
            provider_input_identity={'mediaType': 'application/pdf', 'inputSha256': source['sourceSha256'],
                'inputArtifactName': 'original.pdf', 'pageCount': 5,
                'pageIndexMapMeaning': 'provider_input_document'},
            terminal={'status': 'completed', 'pageResults': [
                {'parserPageIndex': index, 'status': 'completed'} for index in range(len(maps))]})
        runs.append(run)
    bundle = build_dual_parser_evidence(runs)
    manifest = {'schema': ADOPTION_SCHEMA, 'bundleSha256': bundle['bundleSha256'],
        'sourceSha256': source['sourceSha256'], 'sourceRevisionSha256': source['sourceSha256'],
        'documentId': 'authored-document', 'role': 'tender', 'module': 'vetting',
        'sourcePurpose': 'project_document', 'eligibleModules': ['vetting', 'advisory'],
        'sourceClass': 'project_pre', 'scope': {'requiredPhysicalPages': [3, 4], 'scopePolicySha256': 'd' * 64},
        'quality': {'parseStatus': 'PARSED', 'semanticQuality': 'unknown', 'version': 'authored-quality-v1'},
        'reference': {'actor': 'codex', 'method': 'original_source_review',
            'sourceSha256': source['sourceSha256'], 'referenceVersion': 'AUTHORED-source-review-not-gold',
            'physicalPages': [3, 4], 'evidence': {'sourceSha256': source['sourceSha256'],
                'purpose': 'authored wire record; not an actual source review'}},
        'tableGroups': [{'tableGroupId': 'authored-two-page-table', 'physicalPages': [3, 4],
            'candidates': {r['runId']: [p['blocks'][1]['blockId'] for p in r['pages']] for r in runs}}],
        'units': [{'unitId': 'heading', 'scope': 'region', 'runId': runs[0]['runId'],
                   'blockId': runs[0]['pages'][0]['blocks'][0]['blockId'],
                   'reviewedAttributes': ['text', 'geometry', 'clauseHierarchy'],
                   'clause': {'clauseId': 'A1', 'headingUnitId': 'heading'}},
                  {'unitId': 'table', 'scope': 'table_group', 'runId': runs[1]['runId'],
                   'tableGroupId': 'authored-two-page-table',
                   'reviewedAttributes': ['text', 'geometry', 'tableTopology', 'assetBoundary', 'clauseHierarchy'],
                   'clause': {'clauseId': 'A1', 'headingUnitId': 'heading'}}],
        'relations': [{'type': 'parent', 'fromUnitId': 'table', 'toUnitId': 'heading'}], 'assets': []}
    manifest['reference']['evidenceSha256'] = digest(manifest['reference']['evidence'])
    if figure:
        manifest['tableGroups'] = []
        manifest['relations'] = []
        manifest['units'] = [{'unitId': 'figure-caption', 'scope': 'region', 'runId': runs[1]['runId'],
            'blockId': runs[1]['pages'][0]['blocks'][0]['blockId'],
            'associatedBlockIds': [runs[1]['pages'][0]['blocks'][1]['blockId']],
            'reviewedAttributes': ['text', 'geometry', 'assetBoundary']}]
    return bundle, manifest


class CanonicalProjectionTests(unittest.TestCase):
    def setUp(self):
        self.bundle, self.manifest = fixture()

    def project(self, **kwargs):
        return project_canonical(self.bundle, self.manifest, **kwargs)

    def rejects(self, code):
        with self.assertRaisesRegex(CanonicalProjectionError, code):
            self.project()

    def test_complete_whole_group_preserves_two_pages_cells_blank_unknown_and_source_order(self):
        before = copy.deepcopy((self.bundle, self.manifest))
        result = self.project()
        self.assertEqual((self.bundle, self.manifest), before)
        heading, table = result['chunks']
        self.assertEqual(table['physicalPages'], [3, 4])
        self.assertEqual([p['physicalPageNo'] for p in table['parts']], [3, 4])
        self.assertEqual(table['content'], '\n'.join(p['text'] for p in table['parts']))
        self.assertIn('Must not 😀', table['content'])
        self.assertNotEqual(table['sourceHash'], table['sourceSha256'])
        self.assertEqual(table['sourceSha256'], table['originalSourceSha256'])
        self.assertEqual(table['clauseHeadingLocation'], heading['anchor'])
        self.assertEqual(table['adoption']['runId'], self.bundle['runs'][1]['runId'])
        for part in table['parts']:
            self.assertEqual(part['bbox'], [.1, .1, .9, .8])
            self.assertFalse(part['nativeTable'])
            self.assertEqual(part['table']['headerRelationsStatus'], 'unknown')
            self.assertEqual(part['cells'][-1]['textStatus'], 'blank_meaning_unknown')
            self.assertIsNone(part['cells'][-1]['bbox'])
        self.assertFalse(result['humanConfirmed'])
        self.assertFalse(result['semanticScopeVerified'])
        self.assertEqual(validate_projection(result), result)

    def test_projection_passes_exact_utf16_and_structural_window_contract(self):
        result = self.project()
        recipe = WindowRecipe(36, 4)
        planned = plan(result['chunks'], CharacterTokenizer(), {'fixture': True}, recipe,
                       StructuralRecipe('dual-structure-v1', 512, 512))
        self.assertFalse(planned['coverage']['contextUnavailable'])
        self.assertEqual(len(planned['contexts']), 1)
        self.assertIn('Must not 😀', planned['contexts'][0]['content'])
        for parent in result['chunks']:
            rows = [p for p in planned['points'] if p['granularity'] == 'short' and p['parentId'] == parent['id']]
            verify_window_coverage(parent, rows, recipe.max_tokens)

    def test_missing_second_parser_page_rejects_before_selection(self):
        self.bundle, self.manifest = fixture(mineru_pages=(3,))
        self.rejects('dual_capture_incomplete')

    def test_bundle_or_raw_tamper_is_rejected(self):
        self.bundle['runs'][0]['pages'][0]['blocks'][0]['text'] = 'tampered'
        self.rejects('dual_capture_incomplete')

    def test_stale_source_and_bundle_adoptions_are_rejected(self):
        for key, code in [('sourceSha256', 'source_adoption_mismatch'),
                          ('sourceRevisionSha256', 'source_adoption_mismatch'),
                          ('bundleSha256', 'stale_bundle_adoption')]:
            with self.subTest(key=key):
                self.bundle, self.manifest = fixture()
                self.manifest[key] = 'a' * 64
                self.rejects(code)

    def test_partial_or_reordered_table_inventory_is_rejected(self):
        run_id = self.bundle['runs'][1]['runId']
        self.manifest['tableGroups'][0]['candidates'][run_id].pop()
        self.rejects('partial_table_group')
        self.bundle, self.manifest = fixture()
        run_id = self.bundle['runs'][1]['runId']
        self.manifest['tableGroups'][0]['candidates'][run_id].reverse()
        self.rejects('table_group_source_order_required')

    def test_table_unit_cannot_select_a_block_cell_or_page_subset(self):
        for override in ({'members': ['only-one']}, {'cells': []}, {'blockId': 'one'}, {'physicalPages': [3]}):
            with self.subTest(override=override):
                self.bundle, self.manifest = fixture()
                self.manifest['units'][1].update(override)
                self.rejects('illegal_unit_adoption|whole_table_group_required')

    def test_region_route_cannot_adopt_table_or_mix_parsers_inside_group(self):
        self.manifest['units'][0]['blockId'] = self.bundle['runs'][0]['pages'][0]['blocks'][1]['blockId']
        self.rejects('table_cannot_be_region_or_cell_adopted')
        self.bundle, self.manifest = fixture()
        key = self.bundle['runs'][1]['runId']
        self.manifest['tableGroups'][0]['candidates'][key][1] = self.bundle['runs'][0]['pages'][1]['blocks'][1]['blockId']
        self.rejects('table_group_mixed_run_or_nontable')

    def test_unknown_geometry_and_unknown_upgrade_fail_closed(self):
        self.bundle, self.manifest = fixture(transform_unknown=True)
        self.rejects('original_geometry_unknown')
        self.bundle, self.manifest = fixture()
        self.manifest['quality']['semanticQuality'] = 'verified'
        self.rejects('unknown_quality_cannot_be_upgraded')
        self.bundle, self.manifest = fixture()
        self.manifest['quality']['attributes'] = {'editState': 'verified'}
        self.rejects('unknown_quality_cannot_be_upgraded')
        self.bundle, self.manifest = fixture()
        self.manifest['units'][1]['qualityOverrides'] = {'blankMeaning': 'confirmed_blank'}
        self.rejects('illegal_unit_adoption')

    def test_review_requires_codex_original_source_and_actual_evidence_bytes(self):
        for change, code in [({'actor': 'model'}, 'source_review_required'),
                             ({'method': 'confidence'}, 'source_review_required'),
                             ({'humanConfirmed': True}, 'illegal_reference'),
                             ({'evidenceSha256': 'a' * 64}, 'reference_bytes_hash_mismatch')]:
            with self.subTest(change=change):
                self.bundle, self.manifest = fixture()
                self.manifest['reference'].update(change)
                self.rejects(code)
        self.bundle, self.manifest = fixture()
        self.manifest['reference'].pop('evidence')
        self.rejects('actual_reference_bytes_required')

    def test_reference_descriptor_actual_sha_is_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            raw = b'authored actual reference bytes; not source gold'
            descriptor = store.put_bytes(raw)
            self.manifest['reference'].pop('evidence')
            self.manifest['reference']['artifactDescriptor'] = descriptor
            self.manifest['reference']['evidenceSha256'] = hashlib.sha256(raw).hexdigest()
            self.project(artifact_store=store)
            Path(directory, descriptor['relativePath']).write_bytes(b'changed bytes')
            with self.assertRaisesRegex(CanonicalProjectionError, 'reference_bytes_unavailable'):
                self.project(artifact_store=store)

    def test_module_template_and_evaluation_answer_boundary_is_enforced(self):
        self.manifest['sourcePurpose'] = 'standard_template'
        self.rejects('module_source_not_eligible')
        self.manifest.update(module='drafting', eligibleModules=['drafting'])
        self.project()
        self.manifest['sourcePurpose'] = 'evaluation_answer'
        self.rejects('module_source_not_eligible')

    def test_role_scope_quality_model_projection_and_source_change_revision(self):
        baseline = self.project()
        changes = [lambda m: m.update(role='standard'),
                   lambda m: m['scope'].update(scopePolicySha256='a' * 64),
                   lambda m: m['quality'].update(version='quality-v2'),
                   lambda m: m.update(eligibleModules=['vetting']),
                   lambda m: m.update(sourceClass='project_other')]
        for change in changes:
            with self.subTest(change=change):
                self.bundle, self.manifest = fixture()
                change(self.manifest)
                updated = self.project()
                self.assertNotEqual(updated['projectionRevisionSha256'], baseline['projectionRevisionSha256'])
                self.assertNotEqual(updated['sourceHash'], baseline['sourceHash'])
        for options in ({'revision': 'e' * 40}, {'source_bytes': b'other actual original bytes'}):
            self.bundle, self.manifest = fixture(**options)
            self.assertNotEqual(self.project()['projectionRevisionSha256'], baseline['projectionRevisionSha256'])
        self.bundle, self.manifest = fixture()
        with patch('canonical_projection.VERSION', 'explicit-next-projection-version'):
            self.assertNotEqual(self.project()['projectionRevisionSha256'], baseline['projectionRevisionSha256'])

    def test_explicit_relations_reject_unknown_members_and_cycles(self):
        self.manifest['relations'][0]['toUnitId'] = 'not-selected'
        self.rejects('relation_member_unselected')
        self.bundle, self.manifest = fixture()
        self.manifest['relations'].append({'type': 'parent', 'fromUnitId': 'heading', 'toUnitId': 'table'})
        self.rejects('cyclic_parent_relation')

    def add_assets(self):
        run_id = self.bundle['runs'][1]['runId']
        self.manifest['assets'] = [{'unitId': 'table', 'runId': run_id, 'artifactName': 'page' + str(p) + '.png',
            'physicalPages': [p], 'mediaType': 'image/png', 'blockIds': [page['blocks'][1]['blockId']]}
            for p, page in zip((3, 4), self.bundle['runs'][1]['pages'])]

    def test_complete_table_assets_are_ordered_actual_and_source_bound(self):
        self.add_assets()
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            result = self.project(artifact_store=store)
            assets = result['assetRefs']
            self.assertEqual([a['physicalPages'] for a in assets], [[3], [4]])
            self.assertEqual(assets, result['chunks'][1]['assetRefs'])
            for asset in assets:
                self.assertTrue(asset['assetId'].startswith('asset:'))
                self.assertEqual(asset['sourceSha256'], result['source']['sourceSha256'])
                raw = store.read_asset(asset['artifactDescriptor'])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), asset['sha256'])
                self.assertEqual(len(raw), asset['byteCount'])

    def test_partial_group_assets_and_cross_unit_asset_mapping_are_rejected(self):
        self.add_assets()
        self.manifest['assets'].pop()
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(CanonicalProjectionError, 'partial_table_group_assets'):
                self.project(artifact_store=ArtifactStore(directory))
        self.bundle, self.manifest = fixture()
        self.add_assets()
        self.manifest['assets'][0]['blockIds'] = [self.bundle['runs'][0]['pages'][0]['blocks'][0]['blockId']]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(CanonicalProjectionError, 'asset_block_mapping_unknown'):
                self.project(artifact_store=ArtifactStore(directory))

    def test_snapshot_seal_and_internal_source_mapping_are_validated(self):
        result = self.project()
        changed = copy.deepcopy(result)
        changed['chunks'][0]['content'] = 'invented correction'
        with self.assertRaisesRegex(CanonicalProjectionError, 'projection_seal_mismatch'):
            validate_projection(changed)
        changed['projectionSha256'] = digest({k: v for k, v in changed.items() if k != 'projectionSha256'})
        with self.assertRaisesRegex(CanonicalProjectionError, 'canonical_content_mismatch'):
            validate_projection(changed)

    def test_resealed_chunk_geometry_parts_or_spans_cannot_diverge_from_canonical_units(self):
        result = self.project()
        for field in ('parts', 'sourceSpans'):
            with self.subTest(field=field):
                changed = copy.deepcopy(result)
                changed['chunks'][0][field][0]['bbox'] = [0, 0, 1, 1]
                changed['projectionSha256'] = digest({k: v for k, v in changed.items() if k != 'projectionSha256'})
                with self.assertRaisesRegex(CanonicalProjectionError, 'canonical_unit_chunk_mismatch|canonical_page_span_mismatch'):
                    validate_projection(changed)

    def test_figure_caption_association_keeps_unavailable_image_text_and_correct_region(self):
        self.bundle, self.manifest = fixture(figure=True)
        run = self.bundle['runs'][1]
        image = run['pages'][0]['blocks'][1]
        self.manifest['assets'] = [{'unitId': 'figure-caption', 'runId': run['runId'],
            'artifactName': 'page3.png', 'physicalPages': [3], 'mediaType': 'image/png',
            'blockIds': [image['blockId']]}]
        with tempfile.TemporaryDirectory() as directory:
            result = self.project(artifact_store=ArtifactStore(directory))
        chunk = result['chunks'][0]
        self.assertEqual(chunk['parts'][1]['text'], '')
        self.assertEqual(chunk['parts'][1]['candidateTextStatus'], 'unavailable_source_observation')
        self.assertEqual(chunk['parts'][1]['candidateLabel'], 'image')
        self.assertEqual(chunk['content'], 'A1 Contract requirements\n')
        self.assertEqual(chunk['assetRefs'][0]['sourceRegions'][0]['blockId'], image['blockId'])
        self.assertEqual(chunk['assetRefs'][0]['sourceRegions'][0]['bbox'], [.1, .1, .9, .8])

    def test_figure_association_cannot_duplicate_cross_page_run_or_table(self):
        for pick, code in [('duplicate', 'duplicate_associated_region'),
                           ('page', 'associated_region_crosses_page'),
                           ('run', 'adoption_run_or_page_mismatch')]:
            with self.subTest(pick=pick):
                self.bundle, self.manifest = fixture(figure=True)
                row = self.manifest['units'][0]
                if pick == 'duplicate':
                    row['associatedBlockIds'].append(row['blockId'])
                elif pick == 'page':
                    row['associatedBlockIds'] = [self.bundle['runs'][1]['pages'][1]['blocks'][1]['blockId']]
                else:
                    row['associatedBlockIds'] = [self.bundle['runs'][0]['pages'][0]['blocks'][1]['blockId']]
                self.rejects(code)
        self.bundle, self.manifest = fixture()
        self.manifest['units'][0]['associatedBlockIds'] = [self.bundle['runs'][0]['pages'][0]['blocks'][1]['blockId']]
        self.rejects('table_cannot_be_region_or_cell_adopted')

    def test_all_non_answer_source_purposes_are_explicitly_supported(self):
        for purpose in ('reference_standard', 'project_fact', 'package_manifest', 'unknown'):
            with self.subTest(purpose=purpose):
                self.manifest['sourcePurpose'] = purpose
                self.assertEqual(self.project()['chunks'][0]['sourcePurpose'], purpose)

    def test_wrong_page_artifact_and_tiff_media_cannot_be_accepted_assets(self):
        self.add_assets()
        self.manifest['assets'][0]['artifactName'] = 'page4.png'
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(CanonicalProjectionError, 'asset_location_unbound'):
                self.project(artifact_store=ArtifactStore(directory))
        self.bundle, self.manifest = fixture()
        self.add_assets()
        self.manifest['assets'][0]['mediaType'] = 'image/tiff'
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(CanonicalProjectionError, 'unsupported_asset_media'):
                self.project(artifact_store=ArtifactStore(directory))


if __name__ == '__main__':
    unittest.main()
