"""Authored wire-contract cases, not live Paddle/MinerU quality acceptance.

Exercise the public seam with complete HTTP/page/model structures and exact
transport bytes. No model, OCR engine, network, framework, or production API is
initialized. The values deliberately include negation, punctuation, blank
observations, merged headers, selected pages, and contradictory parser output.
"""
import base64
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from structured_parser_evidence import (ParserEvidenceError, build_dual_parser_evidence,
                                       normalize_parser_run, save_evidence_bundle,
                                       assert_complete_dual, project_bbox_to_original,
                                       read_artifact_record, _raw_output)
from artifact_store import ArtifactStore


SOURCE_BYTES = b'authored original document artifact, not a real model PDF input'
PAGE_BYTES = b'authored-byte-asset-not-a-model-result'
SOURCE = {'sourceFilename': 'AUTHORED-contract-wire.pdf', 'mediaType': 'application/pdf',
          'sourceSha256': hashlib.sha256(SOURCE_BYTES).hexdigest(), 'pageCount': 8}
HTML = ('<table><tr><th colspan="2">*Must not* — dimensions</th></tr>'
        '<tr><td rowspan="2">600 mm</td><td>HK$ 12,500</td></tr>'
        '<tr><td></td></tr></table>')


def identity(parser):
    return {'parserVersion': '1.6' if parser == 'paddleocr-vl' else '4.0.10',
            'engine': 'author-authored-contract-fixture-not-a-runtime',
            'models': [{'modelId': 'authored-wire-checkpoint-identity', 'revision': 'b' * 40,
                        'sha256': 'c' * 64}],
            'config': {'batchSize': 1, 'restructurePages': False, 'fixture': True}}


def mapping(parser, *, physical=3, index=0):
    return [{'parserPageIndex': index, 'physicalPageNo': physical, 'width': 1000,
             'height': 2000, 'coordinateUnit': 'pixel',
             'providerInputPageNo': physical if parser == 'mineru' else 1,
             'inputArtifactName': 'physical-page-3.png',
             'providerBboxFrame': 'raster' if parser == 'paddleocr-vl' else 'normalized',
             'originalPageTransform': {'kind': 'identity',
                 'inputFrame': {'coordinateFrame': 'raster', 'coordinateUnit': 'pixel',
                     'width': 1000, 'height': 2000, 'artifactName': 'physical-page-3.png',
                     'sha256': hashlib.sha256(PAGE_BYTES).hexdigest()},
                 'targetFrame': {'coordinateFrame': 'original_page', 'coordinateUnit': 'pixel',
                     'width': 1000, 'height': 2000, 'physicalPageNo': physical,
                     'sourceSha256': SOURCE['sourceSha256']}}}]


def provider_input(parser):
    return {'mediaType': 'application/pdf' if parser == 'mineru' else 'image/png',
            'inputSha256': hashlib.sha256(SOURCE_BYTES if parser == 'mineru' else PAGE_BYTES).hexdigest(),
            'inputArtifactName': 'authored-original-document.pdf' if parser == 'mineru' else 'physical-page-3.png',
            'pageCount': 8 if parser == 'mineru' else 1,
            'pageIndexMapMeaning': 'provider_input_document'}


def paddle_page(text='*must not* demolish — 600 mm', *, table=False):
    return {'input_path': 'authored-request.pdf', 'page_index': 0, 'parsing_res_list': [
        {'block_id': 'provider-reused-id', 'block_label': 'table' if table else 'text',
         'block_content': HTML if table else text, 'block_bbox': [100, 200, 900, 600],
         'block_order': 4, 'confidence': .9999},
        {'block_id': 'provider-reused-id', 'block_label': 'footnote',
         'block_content': 'Unless amended by PSS, clause 12 applies.',
         'block_bbox': [100, 1700, 900, 1800], 'block_order': 7}]}


def mineru_page(text='*must not* demolish — 600 mm', *, table=False):
    return {'page_idx': 0, 'blocks': [
        {'id': 'duplicate-provider-id', 'type': 'table' if table else 'text',
         'content': HTML if table else text, 'bbox': [.1, .1, .9, .3], 'index': 4, 'score': .01},
        {'id': 'duplicate-provider-id', 'type': 'footnote',
         'content': 'Unless amended by PSS, clause 12 applies.', 'bbox': [.1, .85, .9, .9], 'index': 7}]}


def run(parser, *, table=False, text='*must not* demolish — 600 mm', outputs=None, **kwargs):
    if outputs is None:
        outputs = {'page_json': paddle_page(text, table=table), 'model_output': {'stage': 'authored'}} if parser == 'paddleocr-vl' else {
            'model_json': {'schema': 'docvortex.model', 'schema_version': '2.0',
                           'pages': [mineru_page(text, table=table)], 'page_index_map': [2]},
            'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0',
                            'pages': [mineru_page(text, table=table)]},
            'structured_content': {'pages': [mineru_page(text, table=table)]},
            'model_output': {'unfiltered': {'text': text, 'source': 'authored'}}}
    page_map = kwargs.pop('page_map', mapping(parser))
    kwargs.setdefault('artifacts', {'physical-page-3.png': PAGE_BYTES, 'authored-original-document.pdf': SOURCE_BYTES})
    kwargs.setdefault('required_artifacts', ['physical-page-3.png'])
    kwargs.setdefault('provider_input_identity', provider_input(parser))
    kwargs.setdefault('terminal', {'status': 'completed', 'pageResults': [
        {'parserPageIndex': page['parserPageIndex'], 'status': 'completed'} for page in page_map]})
    return normalize_parser_run(parser, outputs, SOURCE, identity(parser), page_map, **kwargs)


def accepted_manifest(selected, *, table=False):
    block = selected['pages'][0]['blocks'][0]
    route = {'runId': selected['runId'], 'sourceSha256': selected['source']['sourceSha256'],
        'sourceRevisionSha256': selected['source']['sourceRevisionSha256'],
        'parserIdentitySha256': selected['identity']['identitySha256'],
        'configSha256': selected['identity']['configSha256'], 'blockId': block['blockId'],
        'scope': 'table_group' if table else 'region',
        'layoutFeatures': {'scanned': True, 'tableStructure': 'complete_group' if table else 'single_text_region'},
        'layoutFeatureEvidenceSha256': 'd' * 64,
        'acceptance': {'status': 'accepted', 'acceptedBy': 'codex',
                       'method': 'codex_original_visual_review',
                       'referenceVersion': 'AUTHORED-contract-reference-v1', 'evidenceSha256': 'e' * 64}}
    if table:
        route['tableGroupId'] = block['table']['tableGroupId']
    return {'schema': 'consense.parser-route-acceptance.v1', 'routes': [route]}


class StructuredParserEvidenceContractTest(unittest.TestCase):
    def test_paddle_http_pruned_page_uses_explicit_original_page_mapping(self):
        page = paddle_page()
        page.pop('input_path'); page.pop('page_index')
        wire = b'{"result":{"layoutParsingResults":[' + json.dumps({'prunedResult': page}, ensure_ascii=False).encode() + b']}}'
        result = run('paddleocr-vl', outputs={'page_json': wire, 'model_output': {'early': 'authored'}})
        self.assertEqual(3, result['pages'][0]['physicalPageNo'])
        self.assertEqual('*must not* demolish — 600 mm', result['pages'][0]['blocks'][0]['text'])
        self.assertEqual('/result/layoutParsingResults/0/prunedResult/parsing_res_list/0',
                         result['pages'][0]['blocks'][0]['provenance']['pointer'])
        raw = result['rawOutputs']['page_json']
        self.assertEqual(wire, base64.b64decode(raw['bytesBase64']))
        self.assertEqual(hashlib.sha256(wire).hexdigest(), raw['sha256'])
        self.assertEqual('exact_supplied_bytes', raw['identityScope'])

    def test_physical_page_is_never_guessed_from_pruned_output(self):
        with self.assertRaisesRegex(ParserEvidenceError, 'page_map'):
            normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE, identity('paddleocr-vl'), [])
        with self.assertRaisesRegex(ParserEvidenceError, 'providerBboxFrame'):
            incomplete = mapping('paddleocr-vl'); incomplete[0].pop('providerBboxFrame')
            normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE, identity('paddleocr-vl'), incomplete)

    def test_duplicate_provider_ids_do_not_collapse_blocks_and_auxiliary_evidence(self):
        result = run('paddleocr-vl')
        blocks = result['pages'][0]['blocks']
        self.assertNotEqual(blocks[0]['blockId'], blocks[1]['blockId'])
        self.assertEqual(blocks[0]['providerBlockId'], blocks[1]['providerBlockId'])
        self.assertEqual('footnote', blocks[1]['label'])
        self.assertEqual(7, blocks[1]['readingOrder']['declared'])
        self.assertEqual('raster', blocks[0]['bbox']['coordinateFrame'])
        self.assertEqual('pixel', blocks[0]['bbox']['coordinateUnit'])
        self.assertFalse(blocks[0]['native'])

    def test_mineru_keeps_early_and_derived_json_independently(self):
        result = run('mineru')
        self.assertEqual({'model_json', 'middle_json', 'structured_content', 'model_output'}, set(result['rawOutputs']))
        self.assertEqual('canonical_json_object', result['rawOutputs']['model_json']['identityScope'])
        self.assertEqual('middle_json', result['pages'][0]['provenance']['output'])
        self.assertEqual('/pages/0/blocks/0', result['pages'][0]['blocks'][0]['provenance']['pointer'])
        self.assertEqual('normalized', result['pages'][0]['blocks'][0]['bbox']['coordinateFrame'])
        self.assertIsNone(result['canonicalSelection'])
        self.assertFalse(result['humanConfirmed'])

    def test_table_header_spans_and_blank_observation_stay_unverified(self):
        result = run('paddleocr-vl', table=True)
        table = result['pages'][0]['blocks'][0]['table']
        self.assertEqual(HTML, table['html'])
        self.assertEqual([(0, 0, 1, 2), (1, 0, 2, 1), (1, 1, 1, 1), (2, 1, 1, 1)],
            [(c['row'], c['column'], c['rowSpan'], c['columnSpan']) for c in table['cells']])
        self.assertEqual('blank_meaning_unknown', table['cells'][-1]['textStatus'])
        self.assertTrue(all(c['headerStatus'] == 'unknown' and not c['nativeCell'] for c in table['cells']))
        self.assertEqual('whole_table_only', table['groupPolicy'])
        self.assertEqual('unknown', table['crossPageRelationsStatus'])

    def test_mineru_nested_whole_table_is_not_flattened_or_cell_fused(self):
        page = mineru_page(table=True)
        page['blocks'][0].pop('content')
        page['blocks'][0]['blocks'] = [{'type': 'table_body', 'lines': [{'spans': [{'type': 'table', 'html': HTML}]}]}]
        output = {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [page]}
        result = run('mineru', outputs={'middle_json': output})
        table = result['pages'][0]['blocks'][0]['table']
        self.assertEqual(HTML, table['html'])
        self.assertEqual('/pages/0/blocks/0/blocks/0/lines/0/spans/0/html', table['provenance']['htmlPointer'])
        self.assertEqual(4, len(table['cells']))
        self.assertEqual('incomplete', result['captureStatus'])
        self.assertIn('required_raw_outputs_missing', {i['code'] for i in result['issues']})

    def test_missing_asset_and_raw_stages_are_explicit_incomplete(self):
        result = normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE,
            identity('paddleocr-vl'), mapping('paddleocr-vl'), artifacts={}, required_artifacts=['missing-page.png'],
            capture_issues=[{'code': 'artifact_fetch_failed', 'status': 'unknown', 'artifact': 'missing-page.png'}])
        self.assertEqual('incomplete', result['captureStatus'])
        codes = {i['code'] for i in result['issues']}
        self.assertTrue({'required_artifacts_missing', 'earliest_model_output_not_supplied', 'artifact_fetch_failed'} <= codes)
        with self.assertRaisesRegex(ParserEvidenceError, 'named bytes'):
            normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE,
                identity('paddleocr-vl'), mapping('paddleocr-vl'), artifacts={'page': '/unread/path.png'})

    def test_oversize_asset_keeps_all_actual_bytes_inline_without_a_store(self):
        data = b'x' * (16 * 1024 * 1024 + 1)
        result = normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE,
            identity('paddleocr-vl'), mapping('paddleocr-vl'), artifacts={'page': data}, required_artifacts=['page'])
        artifact = result['artifacts']['page']
        self.assertEqual(hashlib.sha256(data).hexdigest(), artifact['sha256'])
        self.assertEqual(len(data), artifact['byteCount'])
        self.assertNotIn('contentOmitted', artifact)
        self.assertEqual(data, read_artifact_record(artifact))
        self.assertEqual('complete_bytes_captured', artifact['captureStatus'])
        self.assertEqual('incomplete', result['captureStatus'])

    def test_malformed_input_artifact_name_is_explicitly_unbound(self):
        for name in ([], {}, 3, None):
            with self.subTest(name=name):
                page_map = mapping('paddleocr-vl')
                page_map[0]['inputArtifactName'] = name
                result = normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE,
                    identity('paddleocr-vl'), page_map)
                self.assertIn('page_input_asset_not_bound', {issue['code'] for issue in result['issues']})
                self.assertNotIn('inputArtifact', result['pages'][0])
                self.assertEqual('incomplete', result['captureStatus'])

    def test_invalid_duplicate_json_retains_transport_without_partial_projection(self):
        wire = b'{"parsing_res_list":[],"parsing_res_list":[{"text":"lost negation"}]}'
        result = run('paddleocr-vl', outputs={'page_json': wire})
        self.assertEqual([], result['pages'])
        self.assertEqual(wire, base64.b64decode(result['rawOutputs']['page_json']['bytesBase64']))
        self.assertIn('invalid_json_retained', {i['code'] for i in result['issues']})

    def test_future_schema_and_unprojectable_content_preserve_raw_not_false_empty(self):
        output = {'schema': 'docvortex.middle', 'schema_version': '99.0', 'pages': [{'opaque': 'must not'}]}
        result = run('mineru', outputs={'middle_json': output})
        self.assertEqual(output, result['rawOutputs']['middle_json']['json'])
        self.assertEqual([], result['pages'])
        self.assertIn('unsupported_mineru_schema', {i['code'] for i in result['issues']})
        self.assertIn('requested_page_not_observed', {i['code'] for i in result['issues']})

    def test_invalid_bbox_keeps_text_and_raw_geometry_unknown(self):
        page = paddle_page(); page['parsing_res_list'][0]['block_bbox'] = [1, 2, 1500, 20]
        result = run('paddleocr-vl', outputs={'page_json': page})
        self.assertIsNone(result['pages'][0]['blocks'][0]['bbox'])
        self.assertEqual(page['parsing_res_list'][0]['block_content'], result['pages'][0]['blocks'][0]['text'])
        self.assertEqual(page, result['rawOutputs']['page_json']['json'])
        self.assertIn('bbox_out_of_declared_frame', {i['code'] for i in result['issues']})

    def test_identical_high_confidence_never_auto_selects_a_parser(self):
        bundle = build_dual_parser_evidence([run('paddleocr-vl'), run('mineru')])
        self.assertEqual('same_observation_unverified', bundle['observations'][0]['comparisonStatus'])
        self.assertIsNone(bundle['canonicalSelection'])
        self.assertEqual('unknown', bundle['observations'][0]['selectionStatus'])
        self.assertFalse(bundle['humanConfirmed'])

    def test_disagreement_is_kept_but_not_promoted_to_gold(self):
        bundle = build_dual_parser_evidence([run('paddleocr-vl', text='must not'), run('mineru', text='must')])
        self.assertEqual('different_observation_unverified', bundle['observations'][0]['comparisonStatus'])
        self.assertEqual('must not', bundle['runs'][0]['pages'][0]['blocks'][0]['text'])
        self.assertEqual('must', bundle['runs'][1]['pages'][0]['blocks'][0]['text'])
        self.assertIsNone(bundle['canonicalSelection'])

    def test_explicit_codex_review_selects_whole_table_and_preserves_other_parser(self):
        paddle, mineru = run('paddleocr-vl', table=True), run('mineru', table=True)
        bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=accepted_manifest(mineru, table=True))
        choice = bundle['canonicalSelection'][0]
        self.assertEqual(mineru['runId'], choice['runId'])
        self.assertEqual(4, len(choice['block']['table']['cells']))
        self.assertEqual('table_group', choice['scope'])
        self.assertFalse(choice['humanConfirmed'])
        self.assertEqual(2, len(bundle['runs']))
        self.assertEqual('accepted', bundle['routeAcceptance']['status'])

    def test_stale_config_revision_reference_and_cell_route_all_stay_unknown(self):
        paddle, mineru = run('paddleocr-vl', table=True), run('mineru', table=True)
        good = accepted_manifest(paddle, table=True)
        for mutation in ('sourceRevisionSha256', 'configSha256', 'evidenceSha256', 'cell'):
            with self.subTest(mutation=mutation):
                manifest = copy.deepcopy(good); route = manifest['routes'][0]
                if mutation == 'evidenceSha256':
                    route['acceptance'].pop('evidenceSha256')
                elif mutation == 'cell':
                    route['scope'] = 'cell'; route['cellId'] = paddle['pages'][0]['blocks'][0]['table']['cells'][0]['cellId']
                else:
                    route[mutation] = 'f' * 64
                bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=manifest)
                self.assertIsNone(bundle['canonicalSelection'])
                self.assertEqual('unknown', bundle['routeAcceptance']['routes'][0]['status'])

    def test_overlapping_different_parser_regions_cannot_be_fused(self):
        paddle, mineru = run('paddleocr-vl'), run('mineru')
        first = accepted_manifest(paddle); second = accepted_manifest(mineru)
        first['routes'] += second['routes']
        bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=first)
        self.assertEqual(1, len(bundle['canonicalSelection']))
        self.assertIn('overlapping_region_selection', bundle['routeAcceptance']['routes'][1]['reasons'])

    def test_identity_errors_and_changed_normalized_runs_fail_closed(self):
        with self.assertRaisesRegex(ParserEvidenceError, 'SHA-256'):
            bad = dict(SOURCE, sourceSha256='not-a-digest')
            normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, bad, identity('paddleocr-vl'), mapping('paddleocr-vl'))
        paddle = run('paddleocr-vl'); paddle['pages'][0]['blocks'][0]['text'] = 'tampered after capture'
        with self.assertRaisesRegex(ParserEvidenceError, 'immutable content hash'):
            build_dual_parser_evidence([paddle, run('mineru')])
        with self.assertRaisesRegex(ParserEvidenceError, 'DOCX'):
            bad = dict(SOURCE, mediaType='application/vnd.openxmlformats-officedocument.wordprocessingml.document')
            normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, bad, identity('paddleocr-vl'), mapping('paddleocr-vl'))

    def test_unpinned_model_cannot_gain_routing_by_confidence(self):
        ident = identity('paddleocr-vl'); ident['models'] = [{'modelId': 'unknown-revision'}]
        paddle = normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE, ident, mapping('paddleocr-vl'))
        bundle = build_dual_parser_evidence([paddle, run('mineru')], route_acceptance_manifest=accepted_manifest(paddle))
        self.assertIsNone(bundle['canonicalSelection'])
        self.assertIn('model_identity_not_pinned', bundle['routeAcceptance']['routes'][0]['reasons'])

    def test_deterministic_detached_ledger_and_immutable_file_persistence(self):
        original = run('paddleocr-vl')
        self.assertEqual(original, run('paddleocr-vl'))
        bundle = build_dual_parser_evidence([original, run('mineru')])
        original['pages'][0]['blocks'][0]['text'] = 'caller mutation'
        self.assertNotEqual('caller mutation', bundle['runs'][0]['pages'][0]['blocks'][0]['text'])
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'evidence.json'
            receipt = save_evidence_bundle(bundle, destination)
            raw = destination.read_bytes()
            self.assertEqual(receipt, save_evidence_bundle(bundle, destination))
            self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt['sha256'])
            self.assertEqual(bundle, json.loads(raw))
            changed = copy.deepcopy(bundle); changed['qualityStatus'] = 'changed'
            with self.assertRaisesRegex(ParserEvidenceError, 'immutable'):
                save_evidence_bundle(changed, destination)
            self.assertEqual(raw, destination.read_bytes())

    def test_selected_page_map_conflict_missing_page_and_empty_observation_are_not_blank(self):
        output = {'schema': 'docvortex.model', 'schema_version': '2.0',
                  'page_index_map': [6], 'pages': [mineru_page()]}
        result = run('mineru', outputs={'model_json': output})
        self.assertEqual([], result['pages'])
        self.assertIn('provider_input_page_map_disagrees', {i['code'] for i in result['issues']})
        self.assertIn('requested_page_not_observed', {i['code'] for i in result['issues']})
        empty = run('paddleocr-vl', outputs={'page_json': {'page_index': 0, 'parsing_res_list': []}})
        self.assertEqual('empty_observation_not_verified_blank', empty['pages'][0]['coverageStatus'])
        self.assertEqual('needs_review', empty['pages'][0]['qualityStatus'])

    def test_bbox_and_declared_reading_order_disagreement_is_visible(self):
        page = mineru_page(); page['blocks'][0]['bbox'][0] = .2
        output = {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [page]}
        result = run('mineru', outputs={'middle_json': output})
        bundle = build_dual_parser_evidence([run('paddleocr-vl'), result])
        self.assertEqual('different_observation_unverified', bundle['observations'][0]['comparisonStatus'])
        page = mineru_page(); page['blocks'][0]['index'] = 44
        result = run('mineru', outputs={'middle_json': dict(output, pages=[page])})
        bundle = build_dual_parser_evidence([run('paddleocr-vl'), result])
        self.assertEqual('different_observation_unverified', bundle['observations'][0]['comparisonStatus'])

    def test_malicious_table_span_is_unknown_without_expanding_an_enormous_grid(self):
        page = paddle_page(table=True)
        page['parsing_res_list'][0]['block_content'] = '<table><tr><td rowspan="10000" colspan="10000">600</td></tr></table>'
        result = run('paddleocr-vl', outputs={'page_json': page})
        table = result['pages'][0]['blocks'][0]['table']
        self.assertEqual('unknown', table['structureStatus'])
        self.assertIn('span_grid_budget_exceeded', table['issues'])
        self.assertEqual('600', table['cells'][0]['text'])
        self.assertIsNone(table['cells'][0]['rowSpan'])

    def test_capture_issues_and_artifact_identity_change_run_identity(self):
        first = run('paddleocr-vl')
        second = run('paddleocr-vl', capture_issues=[{'code': 'partial_response', 'status': 'unknown'}])
        self.assertNotEqual(first['runId'], second['runId'])
        self.assertEqual('incomplete', second['captureStatus'])
        self.assertEqual(b'authored-byte-asset-not-a-model-result',
                         base64.b64decode(first['artifacts']['physical-page-3.png']['bytesBase64']))

    def test_manual_reference_route_is_detached_from_mutable_manifest(self):
        paddle, mineru = run('paddleocr-vl'), run('mineru')
        manifest = accepted_manifest(paddle)
        manifest['routes'][0]['acceptance']['method'] = 'reference_evaluation'
        bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=manifest)
        manifest['routes'][0]['acceptance']['referenceVersion'] = 'changed-later'
        self.assertEqual('AUTHORED-contract-reference-v1', bundle['canonicalSelection'][0]['acceptance']['referenceVersion'])
        self.assertEqual('AUTHORED-contract-reference-v1', bundle['routeAcceptance']['manifest']['routes'][0]['acceptance']['referenceVersion'])

    def test_unmapped_crop_or_rotation_cannot_claim_original_page_route(self):
        for transform in (None, {'kind': 'affine', 'matrix': [1, 0, 50, 0, 1, 60]}):
            with self.subTest(transform=transform):
                page_map = mapping('paddleocr-vl')
                if transform is None:
                    page_map[0].pop('originalPageTransform')
                else:
                    page_map[0]['originalPageTransform'] = transform
                paddle = normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE,
                    identity('paddleocr-vl'), page_map)
                bundle = build_dual_parser_evidence([paddle, run('mineru')], route_acceptance_manifest=accepted_manifest(paddle))
                self.assertIsNone(bundle['canonicalSelection'])
                reasons = bundle['routeAcceptance']['routes'][0]['reasons']
                self.assertTrue('original_page_transform_missing' in reasons or any(
                    reason.startswith('original_transform_not_projected:') for reason in reasons))

    def test_malformed_transform_stays_unknown_without_breaking_dual_bundle(self):
        for transform in (None, [], 'identity', 1):
            with self.subTest(transform=transform):
                page_map = mapping('paddleocr-vl')
                page_map[0]['originalPageTransform'] = transform
                paddle = normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE,
                    identity('paddleocr-vl'), page_map,
                    artifacts={'physical-page-3.png': b'authored-input-page'})
                bundle = build_dual_parser_evidence([paddle, run('mineru')], route_acceptance_manifest=accepted_manifest(paddle))
                self.assertIsNone(bundle['canonicalSelection'])
                self.assertIn('original_page_transform_missing', bundle['routeAcceptance']['routes'][0]['reasons'])

    def test_accepted_route_requires_captured_input_asset(self):
        paddle = normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE,
            identity('paddleocr-vl'), mapping('paddleocr-vl'), artifacts={})
        bundle = build_dual_parser_evidence([paddle, run('mineru')], route_acceptance_manifest=accepted_manifest(paddle))
        self.assertIsNone(bundle['canonicalSelection'])
        self.assertIn('original_page_input_asset_not_captured', bundle['routeAcceptance']['routes'][0]['reasons'])

    def test_mineru_input_png_page_one_maps_to_original_pdf_page_three_without_rewriting_raw(self):
        page_map = mapping('mineru')
        page_map[0]['providerInputPageNo'] = 1
        input_identity = provider_input('paddleocr-vl')
        model = {'schema': 'docvortex.model', 'schema_version': '2.0',
                 'metadata': {'file_suffix': 'pdf'}, 'pages': [[{'provider': 'early block'}]], 'page_index_map': [0]}
        raw_model = json.dumps(model, separators=(',', ':')).encode('utf-8')
        outputs = {'model_json': raw_model,
                   'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [mineru_page()]},
                   'structured_content': {'pages': [mineru_page()]}, 'model_output': {'unfiltered': 'authored'}}
        result = normalize_parser_run('mineru', outputs, SOURCE, identity('mineru'), page_map,
            artifacts={'physical-page-3.png': PAGE_BYTES}, required_artifacts=['physical-page-3.png'],
            provider_input_identity=input_identity)
        page = result['pages'][0]
        self.assertEqual((3, 1), (page['physicalPageNo'], page['providerInputPageNo']))
        self.assertEqual(0, page['providerPageIndexMapObservation']['rawPageIndex'])
        self.assertEqual('provider_input_document', page['providerPageIndexMapObservation']['meaning'])
        self.assertEqual('caller_bound_input_page_to_original_page', page['inputToOriginalMappingStatus'])
        self.assertEqual(SOURCE['sourceSha256'], result['source']['sourceSha256'])
        self.assertEqual(hashlib.sha256(PAGE_BYTES).hexdigest(), result['providerInputIdentity']['inputSha256'])
        self.assertEqual(raw_model, base64.b64decode(result['rawOutputs']['model_json']['bytesBase64']))
        self.assertEqual('image/png', result['providerInputIdentity']['mediaType'])
        self.assertEqual('supplied_outputs_and_assets_captured', result['captureStatus'])

    def test_unknown_provider_input_frame_preserves_page_and_raw_map_without_guessing_pdf_page_one(self):
        outputs = {'model_json': {'schema': 'docvortex.model', 'schema_version': '2.0',
                   'pages': [mineru_page()], 'page_index_map': [0]},
                   'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [mineru_page()]}}
        page_map = mapping('mineru'); page_map[0]['providerInputPageNo'] = 1
        result = normalize_parser_run('mineru', outputs, SOURCE, identity('mineru'), page_map,
                                     artifacts={'physical-page-3.png': PAGE_BYTES})
        self.assertEqual(3, result['pages'][0]['physicalPageNo'])
        self.assertEqual('unknown', result['pages'][0]['inputToOriginalMappingStatus'])
        self.assertEqual([0], result['rawOutputs']['model_json']['json']['page_index_map'])
        self.assertNotIn('provider_input_page_map_disagrees', {issue['code'] for issue in result['issues']})
        bundle = build_dual_parser_evidence([run('paddleocr-vl'), result], route_acceptance_manifest=accepted_manifest(result))
        self.assertIsNone(bundle['canonicalSelection'])
        self.assertIn('provider_input_to_original_mapping_unbound', bundle['routeAcceptance']['routes'][0]['reasons'])

    def test_input_asset_hash_or_missing_input_page_cannot_authorize_original_page_route(self):
        for mutate in ('sha', 'missing_page', 'different_input_asset'):
            with self.subTest(mutate=mutate):
                page_map = mapping('paddleocr-vl'); input_identity = provider_input('paddleocr-vl')
                assets = {'physical-page-3.png': PAGE_BYTES}
                if mutate == 'sha': input_identity['inputSha256'] = 'a' * 64
                elif mutate == 'missing_page': page_map[0].pop('providerInputPageNo')
                else:
                    input_identity['inputArtifactName'] = 'different-input.png'
                    assets['different-input.png'] = PAGE_BYTES
                result = normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE,
                    identity('paddleocr-vl'), page_map, artifacts=assets, provider_input_identity=input_identity)
                self.assertEqual('unknown', result['pages'][0]['inputToOriginalMappingStatus'])
                bundle = build_dual_parser_evidence([result, run('mineru')], route_acceptance_manifest=accepted_manifest(result))
                self.assertIsNone(bundle['canonicalSelection'])
                self.assertIn('provider_input_to_original_mapping_unbound', bundle['routeAcceptance']['routes'][0]['reasons'])

    def test_provider_input_map_contradiction_is_retained_not_relabelled_as_original_source_page(self):
        page_map = mapping('mineru'); page_map[0]['providerInputPageNo'] = 1
        output = {'schema': 'docvortex.model', 'schema_version': '2.0', 'pages': [mineru_page()], 'page_index_map': [1]}
        result = normalize_parser_run('mineru', {'model_json': output}, SOURCE, identity('mineru'), page_map,
            artifacts={'physical-page-3.png': PAGE_BYTES}, provider_input_identity=provider_input('paddleocr-vl'))
        self.assertEqual([], result['pages'])
        self.assertEqual([1], result['rawOutputs']['model_json']['json']['page_index_map'])
        self.assertEqual(3, result['requestPageMap'][0]['physicalPageNo'])
        self.assertIn('provider_input_page_map_disagrees', {issue['code'] for issue in result['issues']})

    def test_input_page_outside_one_page_png_is_not_accepted_as_pdf_page_two(self):
        page_map = mapping('paddleocr-vl'); page_map[0]['providerInputPageNo'] = 2
        result = normalize_parser_run('paddleocr-vl', {'page_json': paddle_page()}, SOURCE,
            identity('paddleocr-vl'), page_map, artifacts={'physical-page-3.png': PAGE_BYTES},
            provider_input_identity=provider_input('paddleocr-vl'))
        self.assertEqual([], result['pages'])
        self.assertIn('provider_input_page_out_of_input_document', {issue['code'] for issue in result['issues']})

    def test_paddle_null_image_page_index_uses_bound_caller_mapping_and_preserves_vendor_bytes(self):
        page = paddle_page(); page['page_index'] = None; page['page_count'] = None
        raw_page = json.dumps(page, separators=(',', ':')).encode('utf-8')
        result = run('paddleocr-vl', outputs={'page_json': raw_page, 'model_output': {'raw': 'authored'}})
        projection = result['pages'][0]
        self.assertEqual((0, 1, 3), (projection['parserPageIndex'], projection['providerInputPageNo'], projection['physicalPageNo']))
        self.assertEqual({'rawPageIndex': None, 'status': 'caller_bound_single_image_null_provider_index'},
                         projection['providerPageIndexObservation'])
        self.assertEqual('caller_bound_input_page_to_original_page', projection['inputToOriginalMappingStatus'])
        self.assertEqual(raw_page, base64.b64decode(result['rawOutputs']['page_json']['bytesBase64']))
        self.assertIsNone(json.loads(raw_page)['page_index'])
        self.assertIsNone(json.loads(raw_page)['page_count'])
        self.assertEqual('needs_review', projection['qualityStatus'])

    def test_paddle_null_page_index_without_unique_captured_single_image_binding_remains_unknown(self):
        for scenario in ('missing_identity', 'hash_mismatch', 'pdf', 'multiple_input_pages', 'multiple_outputs', 'missing_input_page'):
            with self.subTest(scenario=scenario):
                page = paddle_page(); page['page_index'] = None
                page_map = mapping('paddleocr-vl'); input_identity = provider_input('paddleocr-vl')
                outputs = {'page_json': page}; assets = {'physical-page-3.png': PAGE_BYTES}
                if scenario == 'missing_identity': input_identity = None
                elif scenario == 'hash_mismatch': input_identity['inputSha256'] = 'a' * 64
                elif scenario == 'pdf': input_identity['mediaType'] = 'application/pdf'
                elif scenario == 'multiple_input_pages': input_identity['pageCount'] = 2
                elif scenario == 'multiple_outputs': outputs['page_json'] = [page, copy.deepcopy(page)]
                else: page_map[0].pop('providerInputPageNo')
                result = normalize_parser_run('paddleocr-vl', outputs, SOURCE, identity('paddleocr-vl'), page_map,
                    artifacts=assets, provider_input_identity=input_identity)
                self.assertEqual([], result['pages'])
                self.assertIn('page_index_invalid', {issue['code'] for issue in result['issues']})

    def test_mineru_middle_typed_text_content_preserves_exact_segments_and_labels(self):
        page = mineru_page(); page['blocks'][0].update(type='header', content=[
            {'type': 'text', 'content': 'APPENDIX B - '}, {'type': 'text', 'content': 'LIST OF CONTRACT DRAWINGS'}])
        result = run('mineru', outputs={'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [page]}})
        block = result['pages'][0]['blocks'][0]
        self.assertEqual('APPENDIX B - LIST OF CONTRACT DRAWINGS', block['text'])
        self.assertEqual('header', block['label'])
        self.assertEqual(page['blocks'][0]['content'], block['declaredContentObservations'])
        self.assertEqual('content/text/content', block['provenance']['textField'])

    def test_mineru_middle_table_body_content_is_a_whole_group_with_html_and_asset_observation(self):
        page = mineru_page(table=True)
        page['blocks'][0]['content'] = [{'type': 'table_body', 'image_path': 'images/page_0_table_6.jpg', 'content': HTML}]
        result = run('mineru', outputs={'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [page]}})
        block = result['pages'][0]['blocks'][0]
        self.assertEqual(HTML, block['table']['html'])
        self.assertEqual(4, len(block['table']['cells']))
        self.assertEqual('whole_table_only', block['table']['groupPolicy'])
        self.assertEqual('images/page_0_table_6.jpg', block['declaredContentObservations'][0]['image_path'])
        self.assertEqual('/pages/0/blocks/0/content/0/content', block['table']['provenance']['htmlPointer'])

    def test_mineru_unknown_mixed_content_and_multiple_tables_are_not_recursively_flattened(self):
        for content in ([], [{'type': 'unknown', 'content': 'do not guess'}],
                        [{'type': 'text', 'content': 'text'}, {'type': 'image', 'content': 'image'}],
                        [{'type': 'table_body', 'content': HTML}, {'type': 'table_body', 'content': HTML}]):
            with self.subTest(content=content):
                page = mineru_page(table=True); page['blocks'][0]['content'] = content
                result = run('mineru', outputs={'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [page]}})
                block = result['pages'][0]['blocks'][0]
                self.assertIsNone(block['text'])
                self.assertEqual('unknown', block['table']['structureStatus'])
                self.assertEqual(content, block['declaredContentObservations'])


class StructuredEvidenceStorageGeometryCompletionTest(unittest.TestCase):
    def test_external_files_are_captured_and_manifest_binds_source_parser_config(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'store')
            page_path, raw_path = Path(directory) / 'raster.png', Path(directory) / 'page.json'
            page_path.write_bytes(PAGE_BYTES)
            wire = json.dumps(paddle_page(), ensure_ascii=False).encode('utf-8')
            raw_path.write_bytes(wire)
            paddle = run('paddleocr-vl', outputs={'page_json': raw_path, 'model_output': b'{"authored":true}'},
                artifacts={'physical-page-3.png': page_path, 'authored-original-document.pdf': SOURCE_BYTES},
                artifact_store=store)
            mineru = run('mineru', artifact_store=store)
            self.assertNotIn('bytesBase64', paddle['rawOutputs']['page_json'])
            self.assertEqual(wire, read_artifact_record(paddle['rawOutputs']['page_json'], artifact_store=store))
            # Original input file changes do not alter the captured CAS copy.
            page_path.write_bytes(b'caller later changed file')
            self.assertEqual(PAGE_BYTES, read_artifact_record(paddle['artifacts']['physical-page-3.png'], artifact_store=store))
            manifest = paddle['captureManifest']
            self.assertEqual(SOURCE['sourceSha256'], manifest['sourceSha256'])
            self.assertEqual(paddle['identity']['identitySha256'], manifest['parserIdentitySha256'])
            self.assertEqual(paddle['identity']['configSha256'], manifest['configSha256'])
            self.assertEqual(paddle['runId'], manifest['runId'])
            bundle = build_dual_parser_evidence([paddle, mineru], artifact_store=store)
            self.assertTrue(assert_complete_dual(bundle, [3], artifact_store=store)['eligibleForCanonical'])
            destination = Path(directory) / 'evidence.json'
            save_evidence_bundle(bundle, destination, artifact_store=store)
            self.assertEqual(bundle, json.loads(destination.read_bytes()))
            with self.assertRaisesRegex(ParserEvidenceError, 'ArtifactStore'):
                save_evidence_bundle(bundle, Path(directory) / 'unverified.json')

    def test_more_than_64_mib_raw_retains_full_bytes_in_store_without_json_projection(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'store')
            raw_path = Path(directory) / 'large.raw'
            block = b'authored content\n' * (1024 * 64)
            expected = hashlib.sha256()
            with raw_path.open('wb') as stream:
                for _ in range(65):
                    stream.write(block)
                    expected.update(block)
            raw, decoded = _raw_output(raw_path, artifact_store=store)
            self.assertGreater(raw['byteCount'], 64 * 1024 * 1024)
            self.assertEqual(expected.hexdigest(), raw['sha256'])
            self.assertEqual('oversize_projection_not_attempted', raw['projectionStatus'])
            self.assertNotIn('contentOmitted', raw)
            self.assertIsNone(decoded)
            self.assertEqual(raw_path.stat().st_size, (store.root / raw['artifactDescriptor']['relativePath']).stat().st_size)
            self.assertEqual('verified', store.verify(raw['artifactDescriptor'])['status'])

    def test_projection_budget_does_not_discard_canonical_json_or_transport_bytes(self):
        values = (b'{"authored":"complete large raw"}', {'authored': 'complete large raw'})
        for value in values:
            raw, projection = _raw_output(value, max_bytes=4)
            expected = value if isinstance(value, bytes) else json.dumps(
                value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
            self.assertEqual(expected, read_artifact_record(raw))
            self.assertIsNone(projection)
            self.assertNotIn('contentOmitted', raw)

    def test_tampered_or_missing_external_bytes_block_canonical_and_persistence(self):
        for failure in ('tampered', 'missing'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                store = ArtifactStore(Path(directory) / 'store')
                paddle, mineru = run('paddleocr-vl', artifact_store=store), run('mineru', artifact_store=store)
                bundle = build_dual_parser_evidence([paddle, mineru], artifact_store=store)
                descriptor = paddle['artifacts']['physical-page-3.png']['artifactDescriptor']
                path = store.root / descriptor['relativePath']
                if failure == 'tampered':
                    path.write_bytes(b'tampered bytes')
                else:
                    path.unlink()
                with self.assertRaisesRegex(ParserEvidenceError, 'not complete'):
                    assert_complete_dual(bundle, artifact_store=store)
                with self.assertRaises(ParserEvidenceError):
                    save_evidence_bundle(bundle, Path(directory) / 'invalid.json', artifact_store=store)
                rebuilt = build_dual_parser_evidence([paddle, mineru], artifact_store=store,
                    route_acceptance_manifest=accepted_manifest(paddle))
                self.assertIsNone(rebuilt['canonicalSelection'])
                self.assertFalse(rebuilt['dualCompleteness']['eligibleForCanonical'])

    def test_legacy_missing_terminal_is_callable_but_cannot_be_canonical(self):
        paddle, mineru = run('paddleocr-vl', terminal=None), run('mineru')
        self.assertEqual('unverified', paddle['completeness']['terminalStatus'])
        self.assertEqual('complete', paddle['completeness']['materialStatus'])
        bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=accepted_manifest(paddle))
        self.assertIsNone(bundle['canonicalSelection'])
        self.assertEqual('unverified', bundle['dualCompleteness']['status'])
        with self.assertRaisesRegex(ParserEvidenceError, 'terminal'):
            assert_complete_dual(bundle)

    def test_failed_cancelled_unknown_missing_or_duplicate_page_terminals_are_not_complete(self):
        terminals = [
            {'status': 'failed', 'error': 'authored parser failure', 'pageResults': [{'parserPageIndex': 0, 'status': 'completed'}]},
            {'status': 'completed', 'pageResults': [{'parserPageIndex': 0, 'status': 'failed', 'error': 'authored page failure'}]},
            {'status': 'cancelled', 'pageResults': [{'parserPageIndex': 0, 'status': 'cancelled'}]},
            {'status': 'completed', 'pageResults': [{'parserPageIndex': 0, 'status': 'unknown'}]},
            {'status': 'completed', 'pageResults': []},
            {'status': 'completed', 'pageResults': [{'parserPageIndex': 0, 'status': 'completed'}] * 2},
            {'status': 'completed', 'pageResults': [{'parserPageIndex': 0, 'status': 'completed'}, {'parserPageIndex': 1, 'status': 'completed'}]},
        ]
        for terminal in terminals:
            with self.subTest(terminal=terminal):
                paddle = run('paddleocr-vl', terminal=terminal)
                self.assertEqual(terminal, paddle['terminal'])
                bundle = build_dual_parser_evidence([paddle, run('mineru')],
                    route_acceptance_manifest=accepted_manifest(paddle))
                self.assertIsNone(bundle['canonicalSelection'])
                self.assertFalse(bundle['dualCompleteness']['eligibleForCanonical'])
                with self.assertRaisesRegex(ParserEvidenceError, 'not complete'):
                    assert_complete_dual(bundle)

    def test_dual_request_sets_and_required_pages_are_complete_on_both_sides(self):
        page_map = mapping('mineru', physical=4)
        page_map[0]['providerInputPageNo'] = 3
        paddle, mineru = run('paddleocr-vl'), run('mineru', page_map=page_map)
        self.assertTrue(paddle['completeness']['eligibleForCanonical'])
        self.assertTrue(mineru['completeness']['eligibleForCanonical'])
        bundle = build_dual_parser_evidence([paddle, mineru], route_acceptance_manifest=accepted_manifest(paddle))
        self.assertIn('dual_request_physical_page_sets_differ', bundle['dualCompleteness']['reasons'])
        self.assertIsNone(bundle['canonicalSelection'])
        complete = build_dual_parser_evidence([paddle, run('mineru')])
        self.assertTrue(assert_complete_dual(complete, [3])['eligibleForCanonical'])
        for required in ([3, 4], [3, 3], [True], [0]):
            with self.assertRaises(ParserEvidenceError):
                assert_complete_dual(complete, required)

    def test_missing_observed_page_cannot_be_filled_by_completed_terminal(self):
        page_map = mapping('paddleocr-vl') + mapping('paddleocr-vl', physical=4, index=1)
        paddle = run('paddleocr-vl', page_map=page_map)
        self.assertEqual('incomplete', paddle['completeness']['materialStatus'])
        self.assertIn('requested_and_observed_page_sets_differ', paddle['completeness']['reasons'])
        self.assertEqual(2, len(paddle['terminal']['pageResults']))

    def test_semantic_blank_and_table_unknown_are_not_material_capture_failures(self):
        page = paddle_page(table=True)
        page['parsing_res_list'][0]['block_content'] = '<table><tr><td rowspan="100000">text</td></tr></table>'
        paddle = run('paddleocr-vl', outputs={'page_json': page, 'model_output': {'unfiltered': 'authored'}})
        self.assertEqual('unknown', paddle['pages'][0]['blocks'][0]['table']['structureStatus'])
        self.assertTrue(paddle['completeness']['eligibleForCanonical'])
        bundle = build_dual_parser_evidence([paddle, run('mineru')])
        self.assertTrue(assert_complete_dual(bundle)['eligibleForCanonical'])
        empty = run('paddleocr-vl', outputs={'page_json': {'page_index': 0, 'parsing_res_list': []}, 'model_output': {}})
        self.assertTrue(empty['completeness']['eligibleForCanonical'])
        self.assertEqual('empty_observation_not_verified_blank', empty['pages'][0]['coverageStatus'])

    def test_pt_raster_scale_projects_four_corners_into_original_page(self):
        page_map = mapping('paddleocr-vl')
        transform = page_map[0]['originalPageTransform']
        transform['kind'], transform['matrix'] = 'affine', [.5, 0, 0, 0, .5, 0]
        transform['targetFrame'].update(coordinateUnit='pt', width=500, height=1000)
        paddle = run('paddleocr-vl', page_map=page_map)
        page = paddle['pages'][0]
        projected = project_bbox_to_original(paddle, page, page['blocks'][0]['bbox'])
        self.assertEqual('verified', projected['status'])
        self.assertEqual([50, 100, 450, 300], projected['xyxyOriginal'])
        self.assertEqual([[50, 100], [450, 100], [450, 300], [50, 300]], projected['polygonOriginal'])
        self.assertEqual([.1, .1, .9, .3], projected['xyxyNormalized'])
        self.assertEqual('pt', projected['targetFrame']['coordinateUnit'])
        bundle = build_dual_parser_evidence([paddle, run('mineru')], route_acceptance_manifest=accepted_manifest(paddle))
        self.assertIsNotNone(bundle['canonicalSelection'])

    def test_provider_pt_frame_to_original_raster_is_explicit(self):
        page_map = mapping('paddleocr-vl')
        page_map[0].update(width=500, height=1000, coordinateUnit='pt', providerBboxFrame='page')
        transform = page_map[0]['originalPageTransform']
        transform.update(kind='affine', matrix=[2, 0, 0, 0, 2, 0])
        transform['inputFrame'].update(coordinateFrame='page', coordinateUnit='pt', width=500, height=1000)
        page = paddle_page()
        page['parsing_res_list'] = page['parsing_res_list'][:1]
        page['parsing_res_list'][0]['block_bbox'] = [50, 100, 450, 300]
        paddle = run('paddleocr-vl', page_map=page_map, outputs={'page_json': page, 'model_output': {}})
        page = paddle['pages'][0]
        projected = project_bbox_to_original(paddle, page, page['blocks'][0]['bbox'])
        self.assertEqual('verified', projected['status'])
        self.assertEqual([100, 200, 900, 600], projected['xyxyOriginal'])
        self.assertEqual([.1, .1, .9, .3], projected['xyxyNormalized'])

    def test_rotation_and_crop_map_corners_instead_of_normalizing_input_box(self):
        page_map = mapping('paddleocr-vl')
        transform = page_map[0]['originalPageTransform']
        transform.update(kind='affine', matrix=[0, -1, 2400, 1, 0, 200])
        transform['targetFrame'].update(width=3000, height=2000)
        paddle = run('paddleocr-vl', page_map=page_map)
        page = paddle['pages'][0]
        projected = project_bbox_to_original(paddle, page, page['blocks'][0]['bbox'])
        self.assertEqual('verified', projected['status'])
        self.assertEqual([[2200, 300], [2200, 1100], [1800, 1100], [1800, 300]], projected['polygonOriginal'])
        self.assertEqual([1800, 300, 2200, 1100], projected['xyxyOriginal'])
        for actual, expected in zip(projected['xyxyNormalized'], [.6, .15, 2200 / 3000, .55]):
            self.assertAlmostEqual(expected, actual)
        bundle = build_dual_parser_evidence([paddle, run('mineru')], route_acceptance_manifest=accepted_manifest(paddle))
        self.assertIsNotNone(bundle['canonicalSelection'])

    def test_affine_unknown_input_source_units_or_outside_target_never_gain_selection(self):
        for failure in ('input_sha', 'input_asset', 'source_sha', 'physical_page', 'unit', 'dimensions', 'singular', 'out_of_target'):
            with self.subTest(failure=failure):
                page_map = mapping('paddleocr-vl')
                transform = page_map[0]['originalPageTransform']
                transform.update(kind='affine', matrix=[1, 0, 0, 0, 1, 0])
                if failure == 'input_sha': transform['inputFrame']['sha256'] = 'a' * 64
                elif failure == 'input_asset': transform['inputFrame']['artifactName'] = 'not-captured.png'
                elif failure == 'source_sha': transform['targetFrame']['sourceSha256'] = 'a' * 64
                elif failure == 'physical_page': transform['targetFrame']['physicalPageNo'] = 4
                elif failure == 'unit': transform['inputFrame']['coordinateUnit'] = 'pt'
                elif failure == 'dimensions': transform['inputFrame']['width'] = 900
                elif failure == 'singular': transform['matrix'] = [0, 0, 0, 0, 0, 0]
                else: transform['matrix'] = [1, 0, 2000, 0, 1, 0]
                paddle = run('paddleocr-vl', page_map=page_map)
                page = paddle['pages'][0]
                self.assertEqual('unknown', project_bbox_to_original(paddle, page, page['blocks'][0]['bbox'])['status'])
                bundle = build_dual_parser_evidence([paddle, run('mineru')], route_acceptance_manifest=accepted_manifest(paddle))
                self.assertIsNone(bundle['canonicalSelection'])


if __name__ == '__main__':
    unittest.main()
