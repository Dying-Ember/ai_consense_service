"""Authored native PDF / declared dual observations; no neural quality score."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes, _hash
from native_pdf_observation import capture_native_pdf_observation
from native_pdf_canonical_projection import ADOPTION_SCHEMA, produce_native_pdf, validate_native_pdf_projection
from parser_capture import combine_parser_captures
from structured_parser_evidence import build_dual_parser_evidence, normalize_parser_run, read_artifact_record
from test_native_pdf_fixture import actual_native_pdf_fixture
from token_windows import utf16_length


RASTERS = Path(__file__).resolve().parents[2] / 'src/test/resources/oer/native-pdf-projection'


def fixture(store):
    raw, original, sources = actual_native_pdf_fixture()
    native = capture_native_pdf_observation(raw, original, sources, artifact_store=store)
    sha = native['source']['sourceSha256']
    provenance = json.loads((RASTERS / 'raster-fixture-provenance.json').read_bytes())
    assert provenance['sourcePdfSha256'] == sha
    images = [(RASTERS / ('authored-original-page-' + str(p) + '.png')).read_bytes() for p in (1, 2)]
    sizes = [struct.unpack('>II', image[16:24]) for image in images]
    source = {'sourceFilename': 'AUTHORED-original.pdf', 'sourceSha256': sha,
              'sourceRevisionSha256': sha, 'mediaType': 'application/pdf', 'pageCount': 2}
    runs = []
    for parser in ('paddleocr-vl', 'mineru'):
        maps, pages = [], []
        assets = {'original.pdf': original}
        for index, ((width, height), image) in enumerate(zip(sizes, images)):
            name = 'original-page-' + str(index + 1) + '.png'; assets[name] = image
            maps.append({'parserPageIndex': index, 'physicalPageNo': index + 1, 'providerInputPageNo': index + 1,
                         'width': width, 'height': height, 'coordinateUnit': 'pixel',
                         'inputArtifactName': name, 'providerBboxFrame': 'raster',
                         'originalPageTransform': {'kind': 'identity',
                             'inputFrame': {'coordinateFrame': 'raster', 'coordinateUnit': 'pixel',
                                            'width': width, 'height': height, 'artifactName': name,
                                            'sha256': hashlib.sha256(image).hexdigest()},
                             'targetFrame': {'coordinateFrame': 'original_page', 'coordinateUnit': 'pixel',
                                             'width': width, 'height': height, 'physicalPageNo': index + 1,
                                             'sourceSha256': sha}}})
            # Deliberately different from the actual native literal and reference text.
            if parser == 'paddleocr-vl':
                pages.append({'page_index': index, 'parsing_res_list': [
                    {'block_id': 'body', 'block_label': 'text', 'block_content': 'OCR typo candidate ' + str(index),
                     'block_bbox': [0, 0, width, height], 'block_order': 0}]})
            else:
                pages.append({'page_idx': index, 'blocks': [
                    {'id': 'body', 'type': 'text', 'content': 'Another OCR typo ' + str(index),
                     'bbox': [0, 0, width, height], 'index': 0}]})
        outputs = {'page_json': pages, 'model_output': {'AUTHORED': 'not a neural call'}} if parser == 'paddleocr-vl' else {
            'model_json': {'schema': 'docvortex.model', 'schema_version': '2.0', 'pages': pages, 'page_index_map': [0, 1]},
            'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': pages},
            'model_output': {'AUTHORED': 'not a neural call'}}
        runs.append(normalize_parser_run(parser, outputs, source,
                    {'parserVersion': 'AUTHORED-v1', 'engine': 'AUTHORED-no-inference',
                     'models': [{'modelId': parser, 'revision': 'AUTHORED-frozen'}], 'config': {'authored': True}}, maps,
                    artifacts=assets, required_artifacts=list(assets), artifact_store=store,
                    provider_input_identity={'mediaType': 'application/pdf', 'inputSha256': sha,
                         'inputArtifactName': 'original.pdf', 'pageCount': 2, 'pageIndexMapMeaning': 'provider_input_document'},
                    terminal={'status': 'completed', 'pageResults': [
                         {'parserPageIndex': index, 'status': 'completed'} for index in (0, 1)]}))
    dual = build_dual_parser_evidence(runs, artifact_store=store)
    reference = {'schema': 'consense.source-review-reference.v2', 'referenceVersion': 'AUTHORED-native-pdf-source-review',
        'sourceSha256': sha, 'sourceMediaType': 'application/pdf', 'actor': 'codex', 'method': 'original_source_review',
        'split': 'authored_contract_fixture', 'humanConfirmed': False, 'semanticQuality': 'unknown',
        'physicalPages': [1, 2], 'pages': [], 'regions': [],
        'relations': [{'type': 'continuation', 'fromRegionId': 'r1', 'toRegionId': 'r2'}]}
    regions, unadopted = [], []
    for index, (observation, image, (width, height)) in enumerate(zip(native['observation']['pageObservations'], images, sizes)):
        physical, rid = index + 1, 'r' + str(index + 1)
        ids = [g['id'] for g in observation['nativePdfEvidence']['glyphs']]
        chosen = ids[:15] if index == 0 else ids
        image_descriptor = store.put_bytes(image)
        page_id = 'pdf-physical-' + str(physical)
        reference['pages'].append({'pageId': page_id,
             'sourceAnchor': {'kind': 'pdf_physical_page', 'sourceSha256': sha, 'physicalPageNo': physical},
             'regionIdsInReadingOrder': [rid],
             'sourceImage': {'width': width, 'height': height, 'sha256': image_descriptor['sha256'],
                  'sourceRelation': {'kind': 'original_pdf_page_raster', 'originalSourceSha256': sha,
                                     'originalPhysicalPageNo': physical}}})
        reference['regions'].append({'regionId': rid, 'role': 'body', 'clauseId': 'A1', 'ordinal': 0,
             'text': 'INDEPENDENT REFERENCE TEXT MUST NEVER REPLACE NATIVE OR OCR', 'bboxNormalized': [0, 0, 1, 1],
             'sourceAnchor': {'kind': 'pdf_physical_region', 'sourceSha256': sha, 'physicalPageNo': physical,
                              'pageId': page_id, 'renditionSha256': image_descriptor['sha256'],
                              'geometryStatus': 'reviewed_whole_page_bounds_not_exact_glyph'}})
        regions.append({'regionId': rid, 'physicalPageNo': physical, 'capturedGlyphIds': chosen,
             'glyphIdsInReadingOrder': chosen, 'separators': [],
             'nativeFrameSha256': _hash(observation['frame']),
             'reviewedAttributes': ['membership', 'readingOrder', 'text', 'geometry'],
             'layout': {'selectedRunId': runs[0]['runId'], 'candidateBlockIds': {
                  run['runId']: [run['pages'][index]['blocks'][0]['blockId']] for run in runs},
                  'rasterMappingReview': {run['runId']: {'status': 'source_reviewed_declared',
                      'method': 'original_page_raster_geometry_review', 'referenceRasterSha256': image_descriptor['sha256'],
                      'inputRasterSha256': image_descriptor['sha256'], 'relationship': 'same_original_raster',
                      'originalPageTransformSha256': _hash(run['pages'][index]['mapping']['originalPageTransform'])}
                      for run in runs}}})
        unadopted.append({'physicalPageNo': physical, 'glyphIds': ids[len(chosen):], 'status': 'unadopted_unknown'})
    ref_descriptor = store.put_bytes(_bytes(reference))
    manifest = {'schema': ADOPTION_SCHEMA, 'nativeBundleSha256': native['bundleSha256'], 'dualBundleSha256': dual['bundleSha256'],
         'sourceSha256': sha, 'sourceRevisionSha256': native['source']['sourceRevisionSha256'],
         'documentId': 'AUTHORED-document', 'role': 'tender', 'module': 'vetting',
         'eligibleModules': ['vetting', 'advisory'], 'sourcePurpose': 'project_document', 'sourceClass': 'project_contract',
         'scope': {'requiredPhysicalPages': [1, 2], 'scopePolicySha256': 'a' * 64},
         'quality': {'parseStatus': 'PARSED', 'semanticQuality': 'unknown', 'version': 'AUTHORED-unknown'},
         'reference': {'actor': 'codex', 'method': 'original_source_review', 'sourceSha256': sha,
              'referenceVersion': reference['referenceVersion'], 'evidenceSha256': ref_descriptor['sha256'],
              'artifactDescriptor': ref_descriptor, 'pageImages': [store.put_bytes(image) for image in images]},
         'regionReview': {'schema': 'consense.native-pdf-region-review.v1', 'status': 'source_reviewed_declared',
              'nativeBundleSha256': native['bundleSha256'], 'referenceSha256': ref_descriptor['sha256'],
              'regions': regions, 'unadoptedGlyphs': unadopted},
         'units': [{'unitId': 'A1', 'scope': 'whole_text_unit', 'regionIds': ['r1', 'r2'],
                   'partSeparator': {'kind': 'reviewed_region_boundary', 'text': '\n'},
                   'clause': {'clauseId': 'A1', 'headingRegionId': 'r1'}}],
         'sourceRelationIndices': [0], 'tableGroups': [], 'assets': []}
    return native, dual, manifest


class NativePdfCanonicalProjectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.store = ArtifactStore(self.temporary.name)
        self.native, self.dual, self.manifest = fixture(self.store)

    def produce(self, manifest=None, native=None, dual=None):
        return produce_native_pdf(native or self.native, dual or self.dual,
                                  manifest or self.manifest, artifact_store=self.store)

    def reference(self):
        return json.loads(self.store.read_asset(self.manifest['reference']['artifactDescriptor']))

    def replace_reference(self, reference, manifest=None):
        manifest = manifest or self.manifest
        descriptor = self.store.put_bytes(_bytes(reference))
        manifest['reference']['artifactDescriptor'] = descriptor
        manifest['reference']['evidenceSha256'] = descriptor['sha256']
        manifest['regionReview']['referenceSha256'] = descriptor['sha256']

    def recapture_mutation(self, mutate):
        """Synthetic contract mutation, never a fresh Java/source observation."""
        raw, source, code = actual_native_pdf_fixture()
        observation = json.loads(raw)
        mutate(observation)
        native = capture_native_pdf_observation(_bytes(observation), source, code, artifact_store=self.store)
        self.manifest['nativeBundleSha256'] = native['bundleSha256']
        self.manifest['regionReview']['nativeBundleSha256'] = native['bundleSha256']
        return native

    def test_known_hidden_alpha_and_outcrop_glyphs_cannot_enter_adopted_prose(self):
        page = self.native['observation']['pageObservations'][0]
        for status in ('hidden_rendering_mode', 'hidden_zero_alpha', 'outside_crop'):
            with self.subTest(status=status):
                manifest = copy.deepcopy(self.manifest)
                gid = next(g['id'] for g in page['nativePdfEvidence']['glyphs'] if g['visibilityStatus'] == status)
                for key in ('capturedGlyphIds', 'glyphIdsInReadingOrder'):
                    manifest['regionReview']['regions'][0][key] = [*manifest['regionReview']['regions'][0][key], gid]
                manifest['regionReview']['unadoptedGlyphs'][0]['glyphIds'].remove(gid)
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_whole_layout_overlap_does_not_authorize_disjoint_native_glyphs(self):
        reference = self.reference()
        reference['regions'][0]['bboxNormalized'] = [0, 0, 0.02, 0.02]
        self.replace_reference(reference)
        with self.assertRaises(CanonicalProjectionError): self.produce()

    def test_unadopted_capture_order_and_physical_page_type_are_required(self):
        for mutation in ('reverse', 'bool'):
            with self.subTest(mutation=mutation):
                manifest = copy.deepcopy(self.manifest)
                row = manifest['regionReview']['unadoptedGlyphs'][0]
                if mutation == 'reverse': row['glyphIds'].reverse()
                else: row['physicalPageNo'] = True
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_partial_native_page_cannot_be_adopted_as_whole_region(self):
        def mutate(observation):
            observation['observationStatus'] = 'partial_capture_preserved'
            observation['pageObservations'][0]['observationStatus'] = 'partial_native_operator_observation_failure'
        native = self.recapture_mutation(mutate)
        with self.assertRaises(CanonicalProjectionError): self.produce(native=native)

    def test_stale_source_revision_native_dual_and_review_identity_are_rejected(self):
        paths = [('nativeBundleSha256',), ('dualBundleSha256',), ('sourceSha256',), ('sourceRevisionSha256',),
                 ('regionReview', 'nativeBundleSha256'), ('regionReview', 'referenceSha256'),
                 ('reference', 'evidenceSha256')]
        for path in paths:
            with self.subTest(path=path):
                manifest = copy.deepcopy(self.manifest)
                target = manifest
                for key in path[:-1]: target = target[key]
                target[path[-1]] = 'b' * 64
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_resealed_native_and_dual_mirrors_cannot_replace_actual_raw(self):
        native = copy.deepcopy(self.native)
        native['observation']['pageObservations'][0]['nativePdfEvidence']['glyphs'][0]['unicode'] = 'X'
        native['bundleSha256'] = _hash({k: v for k, v in native.items() if k != 'bundleSha256'})
        manifest = copy.deepcopy(self.manifest)
        manifest['nativeBundleSha256'] = manifest['regionReview']['nativeBundleSha256'] = native['bundleSha256']
        with self.assertRaises(CanonicalProjectionError): self.produce(manifest, native=native)
        runs = copy.deepcopy(self.dual['runs'])
        runs[0]['pages'][0]['blocks'][0]['text'] = 'resealed forged OCR normalized text'
        runs[0]['runContentSha256'] = _hash({k: v for k, v in runs[0].items() if k != 'runContentSha256'})
        dual = build_dual_parser_evidence(runs, artifact_store=self.store)
        manifest = copy.deepcopy(self.manifest); manifest['dualBundleSha256'] = dual['bundleSha256']
        with self.assertRaises(CanonicalProjectionError): self.produce(manifest, dual=dual)

    def test_cold_cas_corruption_and_missing_actual_assets_are_rejected(self):
        result = self.produce()
        descriptors = [self.native['sourcePackage'], self.native['rawObservation'],
            *self.native['observerSources'].values(), *self.manifest['reference']['pageImages'],
            self.manifest['reference']['artifactDescriptor'], *result['evidenceDescriptors'].values(),
            *[record['artifactDescriptor'] for run in self.dual['runs'] for record in run['rawOutputs'].values()]]
        for descriptor in descriptors:
            with self.subTest(sha=descriptor['sha256']):
                path = self.store.root / descriptor['relativePath']; raw = path.read_bytes()
                path.write_bytes(raw + b'changed-after-capture')
                try:
                    with self.assertRaises(CanonicalProjectionError):
                        validate_native_pdf_projection(result, artifact_store=self.store)
                finally: path.write_bytes(raw)
        descriptor = self.manifest['reference']['pageImages'][0]
        path = self.store.root / descriptor['relativePath']; raw = path.read_bytes(); path.unlink()
        try:
            with self.assertRaises(CanonicalProjectionError): validate_native_pdf_projection(result, artifact_store=self.store)
        finally: path.write_bytes(raw)

    def test_reference_source_page_raster_and_frame_bindings_are_required(self):
        for mutation in ('source', 'physical_page', 'reference_raster', 'frame', 'no_raster', 'missing_layout_run'):
            with self.subTest(mutation=mutation):
                manifest = copy.deepcopy(self.manifest); reference = self.reference()
                if mutation == 'source': reference['sourceSha256'] = 'b' * 64
                elif mutation == 'physical_page': reference['regions'][0]['sourceAnchor']['physicalPageNo'] = 2
                elif mutation == 'reference_raster': reference['regions'][0]['sourceAnchor']['renditionSha256'] = 'b' * 64
                elif mutation == 'frame': manifest['regionReview']['regions'][0]['nativeFrameSha256'] = 'b' * 64
                elif mutation == 'no_raster': manifest['reference']['pageImages'] = []
                else: manifest['regionReview']['regions'][0]['layout']['candidateBlockIds'].pop(self.dual['runs'][1]['runId'])
                self.replace_reference(reference, manifest)
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_reviewed_raster_relationship_cannot_be_missing_or_stale(self):
        run_id = self.dual['runs'][0]['runId']
        for mutation in ('missing', 'reference', 'input', 'transform', 'relation'):
            with self.subTest(mutation=mutation):
                manifest = copy.deepcopy(self.manifest)
                layout = manifest['regionReview']['regions'][0]['layout']
                if mutation == 'missing': del layout['rasterMappingReview']
                else:
                    key = {'reference': 'referenceRasterSha256', 'input': 'inputRasterSha256',
                           'transform': 'originalPageTransformSha256', 'relation': 'relationship'}[mutation]
                    layout['rasterMappingReview'][run_id][key] = 'b' * 64
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_glyph_membership_partition_is_complete_unique_and_on_actual_page(self):
        for mutation in ('omitted', 'duplicate', 'wrong_page', 'unknown', 'extra_slice', 'unadopted_duplicate', 'unadopted_missing', 'wrong_order'):
            with self.subTest(mutation=mutation):
                manifest = copy.deepcopy(self.manifest); row = manifest['regionReview']['regions'][0]
                if mutation == 'omitted':
                    row['capturedGlyphIds'] = row['capturedGlyphIds'][1:]
                    row['glyphIdsInReadingOrder'] = row['glyphIdsInReadingOrder'][1:]
                elif mutation == 'duplicate': row['capturedGlyphIds'] = [*row['capturedGlyphIds'], row['capturedGlyphIds'][0]]
                elif mutation == 'wrong_page':
                    row['capturedGlyphIds'] = [*row['capturedGlyphIds'], manifest['regionReview']['regions'][1]['capturedGlyphIds'][0]]
                elif mutation == 'unknown': row['capturedGlyphIds'] = ['not-an-actual-glyph']
                elif mutation == 'extra_slice': row['startOffset'] = 1
                elif mutation == 'unadopted_duplicate': manifest['regionReview']['unadoptedGlyphs'][0]['glyphIds'].append(row['capturedGlyphIds'][0])
                elif mutation == 'unadopted_missing': manifest['regionReview']['unadoptedGlyphs'].pop()
                else: row['capturedGlyphIds'] = list(reversed(row['capturedGlyphIds']))
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_null_surrogate_and_multichar_glyph_are_atomic_literal_spans(self):
        def mutate(observation):
            # These are synthetic raw-export contract probes, not claims about
            # the Unicode emitted from the unchanged authored PDF operators.
            page = observation['pageObservations'][0]
            glyphs = page['nativePdfEvidence']['glyphs']
            glyphs[0]['unicode'], glyphs[1]['unicode'], glyphs[2]['unicode'] = None, '😀', 'e\u0301'
            text, spans, end = [], [], 0
            for glyph in glyphs:
                value = glyph['unicode'] or ''; length = utf16_length(value)
                spans.append({'glyphId': glyph['id'], 'start': end, 'end': end + length})
                text.append(value); end += length
            page['glyphOrderChannel'].update(text=''.join(text), spans=spans, nullUnicodeObserved=True)
        native = self.recapture_mutation(mutate)
        result = self.produce(native=native)
        part = result['chunks'][0]['parts'][0]; spans = part['nativeTextSpans']
        self.assertTrue(part['text'].startswith('😀e\u0301ible'))
        self.assertEqual([(0, 0), (0, 2), (2, 4)], [(s['startOffset'], s['endOffset']) for s in spans[:3]])
        self.assertEqual('null_unicode_zero_span_unknown', spans[0]['unicodeStatus'])
        self.assertEqual(3, len({s['glyphId'] for s in spans[:3]}))

    def test_typed_separators_bind_only_consecutive_actual_glyphs(self):
        row = self.manifest['regionReview']['regions'][0]
        ids = row['glyphIdsInReadingOrder']
        separator = {'separatorId': 'source-boundary', 'kind': 'reviewed_separator', 'text': '\n',
                     'afterGlyphId': ids[0], 'beforeGlyphId': ids[1], 'reviewMethod': 'original_page_raster_boundary'}
        row['separators'] = [separator]
        result = self.produce()
        self.assertTrue(result['chunks'][0]['content'].startswith('V\nisible'))
        spans = result['chunks'][0]['parts'][0]['nativeTextSpans']
        self.assertEqual('reviewed_separator_not_glyph_observation', spans[1]['kind'])
        for mutation in ('nonadjacent', 'duplicate', 'word', 'tab', 'unreviewed'):
            with self.subTest(mutation=mutation):
                manifest = copy.deepcopy(self.manifest); rows = manifest['regionReview']['regions'][0]['separators']
                if mutation == 'nonadjacent': rows[0]['beforeGlyphId'] = ids[2]
                elif mutation == 'duplicate': rows.append(copy.deepcopy(rows[0]))
                elif mutation == 'word': rows[0]['text'] = 'corrected word'
                elif mutation == 'tab': rows[0]['text'] = '\t'
                else: rows[0]['reviewMethod'] = 'model_suggested'
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_required_relations_whole_units_and_cycles_are_closed(self):
        for mutation in ('omitted', 'bool_index', 'split_continuation', 'parent_cycle', 'continuation_cycle', 'sidebar_dangling_adoption'):
            with self.subTest(mutation=mutation):
                manifest = copy.deepcopy(self.manifest); reference = self.reference()
                if mutation == 'omitted': manifest['sourceRelationIndices'] = []
                elif mutation == 'bool_index': manifest['sourceRelationIndices'] = [False]
                elif mutation == 'split_continuation':
                    manifest['units'][0]['regionIds'] = ['r1']
                    manifest['units'].append({'unitId': 'tail', 'scope': 'whole_text_unit', 'regionIds': ['r2'],
                                             'partSeparator': {'kind': 'reviewed_region_boundary', 'text': '\n'}, 'clause': None})
                elif mutation in ('parent_cycle', 'continuation_cycle'):
                    kind = 'parent' if mutation == 'parent_cycle' else 'continuation'
                    reference['relations'] = [{'type': kind, 'fromRegionId': a, 'toRegionId': b} for a, b in [('r1', 'r2'), ('r2', 'r1')]]
                    manifest['sourceRelationIndices'] = [0, 1]
                else:
                    reference['relations'][0]['type'] = 'sidebar'
                    manifest['regionReview']['regions'].pop()
                    ids = manifest['regionReview']['unadoptedGlyphs'][1]
                    ids['glyphIds'] = [g['id'] for g in self.native['observation']['pageObservations'][1]['nativePdfEvidence']['glyphs']]
                    manifest['units'][0]['regionIds'] = ['r1']
                self.replace_reference(reference, manifest)
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_native_table_cell_ap_and_excluded_sources_are_controlled_refusals(self):
        for mutation in ('table', 'cell', 'ap', 'table_group', 'custom_asset', 'template', 'answer', 'sl', 'bq'):
            with self.subTest(mutation=mutation):
                manifest = copy.deepcopy(self.manifest); reference = self.reference()
                if mutation in ('table', 'cell'): reference['regions'][0]['role'] = mutation
                elif mutation == 'ap': manifest['regionReview']['regions'][0]['appearanceGlyphIds'] = ['form-appearance-glyph']
                elif mutation == 'table_group': manifest['tableGroups'] = [{'tableGroupId': 'unsupported'}]
                elif mutation == 'custom_asset': manifest['assets'] = [{'assetId': 'unsupported'}]
                elif mutation == 'template': manifest['sourcePurpose'] = 'standard_template'
                elif mutation == 'answer': manifest['sourcePurpose'] = 'evaluation_answer'
                elif mutation == 'sl': manifest['sourceClass'] = 'sl_technical_library'
                else: manifest['sourceClass'] = 'pricing_bq'
                self.replace_reference(reference, manifest)
                with self.assertRaises(CanonicalProjectionError): self.produce(manifest)

    def test_resealed_derived_payload_source_spans_unknowns_and_recipe_cannot_diverge(self):
        result = self.produce()
        for mutation in ('text', 'offset', 'glyph', 'unknown', 'reference_text', 'dependency', 'code'):
            with self.subTest(mutation=mutation):
                projection = copy.deepcopy(result)
                if mutation == 'text': projection['chunks'][0]['content'] = 'reference gold injected'
                elif mutation == 'offset': projection['chunks'][0]['parts'][0]['nativeTextSpans'][0]['endOffset'] += 1
                elif mutation == 'glyph': projection['chunks'][0]['parts'][0]['capturedGlyphIds'].pop()
                elif mutation == 'unknown': projection['semanticScopeVerified'] = True
                elif mutation == 'reference_text': projection['sourceReference']['regions'][0]['text'] = 'modified reference'
                elif mutation == 'dependency': projection['projectionIdentity']['projectionDependencySha256']['source_reference.py'] = 'b' * 64
                else: projection['projectionIdentity']['projectionCodeSha256'] = 'b' * 64
                projection['projectionSha256'] = _hash({k: v for k, v in projection.items() if k != 'projectionSha256'})
                with self.assertRaises(CanonicalProjectionError): validate_native_pdf_projection(projection, artifact_store=self.store)

    def test_independent_one_page_dual_captures_reconstruct_without_vendor_index_rewrite(self):
        batches = []
        for run in self.dual['runs']:
            children = []
            for index in (0, 1):
                mapping = copy.deepcopy(run['requestPageMap'][index])
                mapping.update(parserPageIndex=0, providerInputPageNo=1)
                name = mapping['inputArtifactName']
                image = self.store.read_asset(run['artifacts'][name]['artifactDescriptor'])
                if run['parser'] == 'paddleocr-vl':
                    page = json.loads(read_artifact_record(run['rawOutputs']['page_json'], artifact_store=self.store))[index]
                    page['page_index'] = 0
                    outputs = {'page_json': [page], 'model_output': {'AUTHORED': 'not a neural call'}}
                else:
                    page = json.loads(read_artifact_record(run['rawOutputs']['middle_json'], artifact_store=self.store))['pages'][index]
                    page['page_idx'] = 0
                    outputs = {'model_json': {'schema': 'docvortex.model', 'schema_version': '2.0', 'pages': [page], 'page_index_map': [0]},
                               'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': [page]},
                               'model_output': {'AUTHORED': 'not a neural call'}}
                children.append(normalize_parser_run(run['parser'], outputs, run['source'], run['identity'], [mapping],
                    artifacts={name: image}, required_artifacts=[name], artifact_store=self.store,
                    provider_input_identity={'mediaType': 'image/png', 'inputSha256': hashlib.sha256(image).hexdigest(),
                         'inputArtifactName': name, 'pageCount': 1, 'pageIndexMapMeaning': 'provider_input_document'},
                    terminal={'status': 'completed', 'pageResults': [{'parserPageIndex': 0, 'status': 'completed'}]}))
            batches.append(combine_parser_captures(children, self.store))
        dual = build_dual_parser_evidence(batches, artifact_store=self.store)
        self.manifest['dualBundleSha256'] = dual['bundleSha256']
        images = self.reference()['pages']
        for index, row in enumerate(self.manifest['regionReview']['regions']):
            sha = images[index]['sourceImage']['sha256']
            row['layout'] = {'selectedRunId': batches[0]['runId'],
                'candidateBlockIds': {run['runId']: [run['pages'][index]['blocks'][0]['blockId']] for run in batches},
                'rasterMappingReview': {run['runId']: {'status': 'source_reviewed_declared',
                     'method': 'original_page_raster_geometry_review', 'referenceRasterSha256': sha, 'inputRasterSha256': sha,
                     'originalPageTransformSha256': _hash(run['pages'][index]['mapping']['originalPageTransform']),
                     'relationship': 'same_original_raster'} for run in batches}}
        result = self.produce(dual=dual)
        self.assertEqual('Visible fi cafe\nSecond page', result['chunks'][0]['content'])
        self.assertEqual([1, 2], result['chunks'][0]['physicalPages'])
        self.assertEqual(0, result['dualParserBundle']['runs'][0]['independentInputCaptures'][1]['pages'][0]['parserPageIndex'])

    def test_unknown_partial_unadopted_page_does_not_drop_reliable_other_page(self):
        def mutate(observation):
            observation['observationStatus'] = 'partial_capture_preserved'
            observation['pageObservations'][1]['observationStatus'] = 'partial_native_operator_observation_failure'
        native = self.recapture_mutation(mutate)
        self.manifest['regionReview']['regions'].pop()
        self.manifest['regionReview']['unadoptedGlyphs'][1]['glyphIds'] = [
            g['id'] for g in native['observation']['pageObservations'][1]['nativePdfEvidence']['glyphs']]
        self.manifest['units'][0]['regionIds'] = ['r1']; self.manifest['sourceRelationIndices'] = []
        reference = self.reference(); reference['relations'] = []; self.replace_reference(reference)
        result = self.produce(native=native)
        self.assertEqual('Visible fi cafe', result['chunks'][0]['content'])
        self.assertEqual(1, result['captureCompleteness']['native']['partialPageCount'])
        self.assertEqual('unknown', result['wholeOriginalSourceCoverage'])

    def test_source_reviewed_parent_sidebar_and_whole_qualifier_keep_typed_relations(self):
        reference = self.reference()
        reference['relations'] = [{'type': kind, 'fromRegionId': 'r2', 'toRegionId': 'r1'} for kind in ('parent', 'sidebar')]
        self.manifest['sourceRelationIndices'] = [0, 1]
        self.manifest['units'][0]['regionIds'] = ['r1']
        self.manifest['units'].append({'unitId': 'sidebar', 'scope': 'whole_text_unit', 'regionIds': ['r2'],
             'partSeparator': {'kind': 'reviewed_region_boundary', 'text': '\n'}, 'clause': None})
        self.replace_reference(reference)
        result = self.produce()
        self.assertEqual(['Visible fi cafe', 'Second page'], [c['content'] for c in result['chunks']])
        self.assertEqual(['parent', 'sidebar'], [r['type'] for r in result['canonical']['relations']])
        self.assertTrue(all(r['scope'] == 'between_whole_units' for r in result['canonical']['relations']))
        reference['relations'] = [{'type': 'qualifier', 'fromRegionId': 'r1', 'toRegionId': 'r2'}]
        self.manifest['sourceRelationIndices'] = [0]
        self.manifest['units'] = [copy.deepcopy(self.manifest['units'][0])]
        self.manifest['units'][0]['regionIds'] = ['r1', 'r2']; self.replace_reference(reference)
        self.assertEqual('within_whole_unit', self.produce()['canonical']['relations'][0]['scope'])

    def test_actual_native_literal_and_layout_candidates_remain_distinct_from_gold(self):
        with tempfile.TemporaryDirectory() as folder:
            store = ArtifactStore(folder); native, dual, manifest = fixture(store)
            original_native, original_dual = copy.deepcopy(native), copy.deepcopy(dual)
            result = produce_native_pdf(native, dual, manifest, artifact_store=store)
            self.assertEqual('Visible fi cafe\nSecond page', result['chunks'][0]['content'])
            self.assertEqual('native_pdf', result['producerKind'])
            self.assertFalse(result['semanticScopeVerified'])
            self.assertEqual('unknown', result['chunks'][0]['sourceQuality']['semanticQuality'])
            self.assertEqual(original_native, native); self.assertEqual(original_dual, dual)
            self.assertEqual(result, validate_native_pdf_projection(result, artifact_store=store))
            self.assertTrue(result['unadoptedGlyphs'][0]['glyphIds'])
            self.assertIn('Hidden', native['observation']['pageObservations'][0]['glyphOrderChannel']['text'])
            self.assertNotIn('Hidden', result['chunks'][0]['content'])


if __name__ == '__main__': unittest.main()
