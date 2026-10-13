"""Real authored PDF page/input contracts; no neural model or production calls."""
import copy
import hashlib
from io import BytesIO
import json
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore, ArtifactStoreError
from parser_capture import (CAPTURE_SCHEMA, canonical_digest, capture_parser_files,
                            file_spec, rebuild_file_capture)
from pdf_selected_source import (SelectedSourceError, canonical_bytes, create_selected_source,
    copy_selected_source_artifacts, digest, validate_selected_source, verify_selected_source_files)
from structured_parser_evidence import (ParserEvidenceError, assert_complete_dual,
    build_dual_parser_evidence, project_bbox_to_original)
from test_structured_parser_evidence import identity


def source_pdf(path):
    from pypdf import PdfWriter
    from pypdf.generic import (ArrayObject, BooleanObject, DecodedStreamObject, DictionaryObject,
        FloatObject, NameObject, NumberObject, RectangleObject, TextStringObject)
    writer = PdfWriter()
    for n in range(6):
        page = writer.add_blank_page(width=300 + n * 10, height=420 + n * 10)
        font = DictionaryObject({NameObject('/Type'): NameObject('/Font'),
            NameObject('/Subtype'): NameObject('/Type1'), NameObject('/BaseFont'): NameObject('/Helvetica')})
        page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'):
            DictionaryObject({NameObject('/F1'): writer._add_object(font)})})
        stream = DecodedStreamObject()
        stream.set_data(('0.1 0.3 0.7 rg 20 40 %s 20 re f\nBT /F1 12 Tf 30 100 Td (Page %s must not omit condition.) Tj ET' % (n + 10, n + 1)).encode())
        page[NameObject('/Contents')] = writer._add_object(stream)
        if n == 2:
            page.cropbox = RectangleObject([10, 15, 300, 425])
            page[NameObject('/Rotate')] = NumberObject(90)
            page[NameObject('/UserUnit')] = FloatObject(1.25)
            appearance = DecodedStreamObject()
            appearance.set_data(b'0 1 0 rg 0 0 100 20 re f')
            appearance[NameObject('/BBox')] = RectangleObject([0, 0, 100, 20])
            appearance[NameObject('/Type')] = NameObject('/XObject')
            appearance[NameObject('/Subtype')] = NameObject('/Form')
            widget = DictionaryObject({NameObject('/Type'): NameObject('/Annot'),
                NameObject('/Subtype'): NameObject('/Widget'), NameObject('/FT'): NameObject('/Tx'),
                NameObject('/T'): TextStringObject('Populated fixture'), NameObject('/V'): TextStringObject('ALPHA-37'),
                NameObject('/Rect'): RectangleObject([35, 140, 135, 160]), NameObject('/F'): NumberObject(4),
                NameObject('/AP'): DictionaryObject({NameObject('/N'): writer._add_object(appearance)})})
            widget_ref = writer._add_object(widget)
            page[NameObject('/Annots')] = ArrayObject([widget_ref])
            writer._root_object[NameObject('/AcroForm')] = writer._add_object(DictionaryObject({
                NameObject('/Fields'): ArrayObject([widget_ref]), NameObject('/NeedAppearances'): BooleanObject(False)}))
    with path.open('wb') as stream:
        writer.write(stream)


class Fixture:
    def __init__(self, root, selected=None):
        self.root = Path(root)
        self.source = self.root / 'original.pdf'
        source_pdf(self.source)
        self.source_bytes = self.source.read_bytes()
        self.store = ArtifactStore(self.root / 'selection-cas')
        self.output = self.root / 'selection'
        self.manifest = create_selected_source(self.source, [2, 3, 5] if selected is None else selected,
            self.output, self.store, expected_source_sha256=hashlib.sha256(self.source_bytes).hexdigest(),
            expected_page_count=6)

    def reseal(self, value):
        value['manifestSha256'] = digest({k: v for k, v in value.items() if k != 'manifestSha256'})
        return value

    def capture(self, parser, target_store=None):
        target_store = target_store or ArtifactStore(self.root / ('capture-' + parser))
        copy_selected_source_artifacts(self.manifest, self.store, target_store)
        directory = self.root / ('files-' + parser)
        directory.mkdir()
        count = self.manifest['providerInput']['pageCount']
        pages = [{'page_index': n, 'parsing_res_list': [{'block_label': 'text',
            'block_content': 'Authored observation %s' % n, 'block_bbox': [0, 0, 20, 20]}]}
            for n in range(count)] if parser == 'paddleocr-vl' else [
            {'page_idx': n, 'blocks': [{'type': 'text', 'content': 'Authored observation %s' % n,
             'bbox': [0, 0, .2, .2]}]} for n in range(count)]
        raw = {'page_json': pages, 'model_output': {'authored': True}} if parser == 'paddleocr-vl' else {
            'model_json': {'schema': 'docvortex.model', 'schema_version': '2.0', 'pages': pages,
                           'page_index_map': list(range(count))},
            'middle_json': {'schema': 'docvortex.middle', 'schema_version': '2.0', 'pages': pages},
            'model_output': {'authored': True}}
        outputs = {}
        for name, value in raw.items():
            path = directory / (name + '.json')
            path.write_bytes(canonical_bytes(value))
            outputs[name] = file_spec(path, capture_scope='authored_vendor_bytes_not_model_quality')
        selected_file = self.output / 'selected-source-manifest.json'
        provider_file = self.output / 'provider-input.pdf'
        assets = {'selected-source.json': file_spec(selected_file, capture_scope='derived_selected_source_manifest'),
                  'provider-input.pdf': file_spec(provider_file, capture_scope='actual_complete_provider_input')}
        parent = self.manifest['parentSource']
        mappings = [{'parserPageIndex': row['providerPageIndex'], 'providerInputPageNo': row['providerInputPageNo'],
            'physicalPageNo': row['originalPhysicalPageNo'], 'width': 300, 'height': 420,
            'coordinateUnit': 'pt', 'providerBboxFrame': 'page' if parser == 'paddleocr-vl' else 'normalized',
            'inputArtifactName': 'provider-input.pdf', 'originalTransformStatus': 'unknown'}
            for row in self.manifest['pageMap']]
        manifest = {'schema': CAPTURE_SCHEMA, 'parser': parser,
            'source': {k: parent[k] for k in ('sourceFilename', 'sourceSha256', 'sourceRevisionSha256', 'pageCount', 'mediaType')},
            'sourceArtifact': {**file_spec(self.source, capture_scope='actual_full_parent_pdf'), 'artifactName': 'original.pdf'},
            'selectedSource': {'artifactName': 'selected-source.json', 'manifestSha256': self.manifest['manifestSha256']},
            'identity': identity(parser), 'outputs': outputs, 'assets': assets,
            'requiredArtifacts': ['original.pdf', *assets], 'pageMappings': mappings,
            'providerInputIdentity': {'inputSha256': self.manifest['providerInput']['inputSha256'],
                'pageCount': count, 'mediaType': 'application/pdf', 'inputArtifactName': 'provider-input.pdf',
                'pageIndexMapMeaning': 'provider_input_document'},
            'terminal': {'status': 'completed', 'pageResults': [{'parserPageIndex': n, 'status': 'completed'} for n in range(count)]}}
        return manifest, target_store


class SelectedSourceContractTest(unittest.TestCase):
    def test_complete_noncontiguous_source_selection_preserves_parent_and_populated_form(self):
        from pypdf import PdfReader
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root)
            report = validate_selected_source(f.manifest, f.store)
            self.assertEqual((6, 3, [2, 3, 5]), (report['parentPageCount'], report['providerPageCount'], report['selectedPhysicalPages']))
            derived = PdfReader(f.output / 'provider-input.pdf')
            self.assertEqual('ALPHA-37', derived.get_fields()['Populated fixture']['/V'])
            self.assertEqual((90, 1.25), (derived.pages[1].get('/Rotate'), derived.pages[1].get('/UserUnit')))
            self.assertEqual([10, 15, 300, 425], list(derived.pages[1].cropbox))
            self.assertEqual(f.source_bytes, f.source.read_bytes())
            self.assertEqual('unknown', report['geometryStatus'])

    def test_same_full_pages_have_equal_cpu_pixels_with_forms_initialized(self):
        import pypdfium2
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root)
            with pypdfium2.PdfDocument(f.source) as parent, pypdfium2.PdfDocument(f.output / 'provider-input.pdf') as derived:
                parent.init_forms(); derived.init_forms()
                for index, physical in enumerate([2, 3, 5]):
                    original_page, input_page = parent[physical - 1], derived[index]
                    try:
                        original_bitmap, input_bitmap = original_page.render(scale=1), input_page.render(scale=1)
                        try:
                            a, b = original_bitmap.to_pil(), input_bitmap.to_pil()
                            self.assertEqual((a.size, a.mode, a.tobytes()), (b.size, b.mode, b.tobytes()))
                        finally:
                            original_bitmap.close(); input_bitmap.close()
                    finally:
                        original_page.close(); input_page.close()

    def test_different_selected_set_has_new_identity(self):
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root)
            next_manifest = create_selected_source(f.source, [1, 4, 6], f.root / 'next', f.store,
                expected_source_sha256=f.manifest['parentSource']['sourceSha256'], expected_page_count=6)
            self.assertNotEqual(f.manifest['manifestSha256'], next_manifest['manifestSha256'])
            self.assertNotEqual(f.manifest['providerInput']['inputSha256'], next_manifest['providerInput']['inputSha256'])

    def test_pdf_integral_float_resource_token_rewrite_preserves_numeric_value(self):
        with tempfile.TemporaryDirectory() as root:
            objects = [b'<< /Type /Catalog /Pages 2 0 R >>',
                b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
                b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 420] /Resources << /Font << /F1 4 0 R >> >> >>',
                b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FontMatrix [24.0 -0.0 1] >>']
            raw = b'%PDF-1.7\n'; offsets = [0]
            for n, value in enumerate(objects, 1):
                offsets.append(len(raw)); raw += ('%s 0 obj\n' % n).encode() + value + b'\nendobj\n'
            xref = len(raw)
            raw += b'xref\n0 5\n0000000000 65535 f \n'
            raw += b''.join(('%010d 00000 n \n' % offset).encode() for offset in offsets[1:])
            raw += ('trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n%s\n%%%%EOF\n' % xref).encode()
            original = Path(root) / 'numeric-resource.pdf'; original.write_bytes(raw)
            output = Path(root) / 'selected'
            manifest = create_selected_source(original, [1], output, ArtifactStore(Path(root) / 'cas'),
                expected_source_sha256=hashlib.sha256(raw).hexdigest(), expected_page_count=1)
            self.assertIn(b'24.0 -0.0 1', raw)
            self.assertNotIn(b'24.0 -0.0 1', (output / 'provider-input.pdf').read_bytes())
            self.assertEqual(manifest['pageMap'][0]['parentPage']['resourcesSha256'],
                             manifest['pageMap'][0]['derivedPage']['resourcesSha256'])

    def test_valid_blank_page_without_resources_can_be_selected_without_fabrication(self):
        from pypdf import PdfWriter
        from pypdf.generic import NameObject
        with tempfile.TemporaryDirectory() as root:
            writer = PdfWriter(); page = writer.add_blank_page(width=200, height=300)
            del page[NameObject('/Resources')]
            original = Path(root) / 'blank.pdf'
            with original.open('wb') as stream: writer.write(stream)
            manifest = create_selected_source(original, [1], Path(root) / 'selected', ArtifactStore(Path(root) / 'cas'),
                expected_source_sha256=hashlib.sha256(original.read_bytes()).hexdigest(), expected_page_count=1)
            self.assertEqual(0, manifest['pageMap'][0]['parentPage']['contentByteCount'])
            self.assertFalse(manifest['qualityAccepted'])

    def test_host_preflight_rejects_missing_producer_or_malformed_dependency_even_with_new_seal(self):
        for kind in ('producer', 'dependency'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                f = Fixture(root); value = copy.deepcopy(f.manifest)
                if kind == 'producer': value['producer']['codeArtifact'] = None
                else:
                    value['producer']['dependencyFiles'][0] = 'unsupported'
                    value['producer']['dependencyInventorySha256'] = digest(value['producer']['dependencyFiles'])
                f.reseal(value)
                with self.assertRaises(SelectedSourceError):
                    verify_selected_source_files(value, f.source, f.output / 'provider-input.pdf')

    def test_invalid_selection_empty_boolean_duplicate_unsorted_or_outside_is_rejected(self):
        for pages in ([], [True], [0], [7], [3, 3], [5, 2], '2,3,5'):
            with self.subTest(pages=pages), tempfile.TemporaryDirectory() as root:
                source = Path(root) / 'source.pdf'; source_pdf(source)
                with self.assertRaises(SelectedSourceError):
                    create_selected_source(source, pages, Path(root) / 'output', ArtifactStore(Path(root) / 'cas'),
                        expected_source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(), expected_page_count=6)

    def test_actual_parent_page_count_false_or_boolean_is_rejected(self):
        for count in (3, True):
            with self.subTest(count=count), tempfile.TemporaryDirectory() as root:
                source = Path(root) / 'source.pdf'; source_pdf(source)
                with self.assertRaises(SelectedSourceError):
                    create_selected_source(source, [2, 3, 5], Path(root) / 'out', ArtifactStore(Path(root) / 'cas'),
                        expected_source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(), expected_page_count=count)

    def test_host_bytes_are_rechecked_after_prior_pass(self):
        for side in ('parent', 'provider'):
            with self.subTest(side=side), tempfile.TemporaryDirectory() as root:
                f = Fixture(root)
                provider = f.output / 'provider-input.pdf'
                verify_selected_source_files(f.manifest, f.source, provider)
                (f.source if side == 'parent' else provider).write_bytes(b'changed after pass')
                with self.assertRaises(SelectedSourceError):
                    verify_selected_source_files(f.manifest, f.source, provider)

    def test_resealed_missing_duplicate_false_map_or_fake_parent_count_cannot_pass(self):
        for change in ('missing', 'duplicate', 'boolean', 'wrong_original', 'wrong_provider', 'parent_count', 'frame', 'recipe'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as root:
                f = Fixture(root); value = copy.deepcopy(f.manifest)
                if change == 'missing': value['pageMap'].pop()
                elif change == 'duplicate': value['pageMap'][1] = value['pageMap'][0]
                elif change == 'boolean': value['pageMap'][0]['providerPageIndex'] = False
                elif change == 'wrong_original': value['pageMap'][1]['originalPhysicalPageNo'] = 4
                elif change == 'wrong_provider': value['pageMap'][1]['providerInputPageNo'] = 1
                elif change == 'parent_count': value['parentSource']['pageCount'] = 7
                elif change == 'frame': value['pageMap'][0]['derivedPage']['frame']['rotation'] = 90
                else: value['extractionRecipe']['flattenForms'] = True
                f.reseal(value)
                with self.assertRaises(SelectedSourceError): validate_selected_source(value, f.store)

    def test_provider_pdf_from_other_selected_pages_fails_even_when_new_sha_is_resealed(self):
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root)
            different = create_selected_source(f.source, [1, 3, 6], f.root / 'different', f.store,
                expected_source_sha256=f.manifest['parentSource']['sourceSha256'], expected_page_count=6)
            altered = copy.deepcopy(f.manifest); altered['providerInput'] = different['providerInput']; f.reseal(altered)
            with self.assertRaises(SelectedSourceError): validate_selected_source(altered, f.store)

    def test_every_cas_input_producer_and_dependency_revalidated_without_host_files(self):
        for side in ('parent', 'provider', 'producer', 'dependency'):
            with self.subTest(side=side), tempfile.TemporaryDirectory() as root:
                f = Fixture(root)
                f.source.unlink(); (f.output / 'provider-input.pdf').unlink()
                validate_selected_source(f.manifest, f.store)
                descriptor = (f.manifest['parentSource']['artifactDescriptor'] if side == 'parent' else
                    f.manifest['providerInput']['artifactDescriptor'] if side == 'provider' else
                    f.manifest['producer']['codeArtifact'] if side == 'producer' else
                    f.manifest['producer']['dependencyFiles'][0]['artifactDescriptor'])
                (f.store.root / descriptor['relativePath']).write_bytes(b'changed in CAS')
                with self.assertRaises((SelectedSourceError, ArtifactStoreError)):
                    validate_selected_source(f.manifest, f.store)

    def test_output_existing_cannot_be_reused_or_original_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root)
            for output in (f.output, f.source):
                with self.assertRaises(SelectedSourceError):
                    create_selected_source(f.source, [1], output, f.store,
                        expected_source_sha256=f.manifest['parentSource']['sourceSha256'], expected_page_count=6)
            self.assertEqual(f.source_bytes, f.source.read_bytes())

    def test_cas_copy_keeps_all_exact_producer_descriptors(self):
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root); target = ArtifactStore(f.root / 'target-cas')
            report = copy_selected_source_artifacts(f.manifest, f.store, target)
            self.assertEqual(f.manifest['manifestSha256'], report['manifestSha256'])
            f.source.unlink()
            self.assertEqual(report, validate_selected_source(f.manifest, target))


class SelectedCaptureContractTest(unittest.TestCase):
    def test_both_parsers_take_same_complete_provider_input_and_preserve_full_parent_identity(self):
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root); target = ArtifactStore(f.root / 'dual-cas'); runs = []
            for parser in ('paddleocr-vl', 'mineru'):
                manifest, _ = f.capture(parser, target)
                run, receipt = capture_parser_files(manifest, target)
                self.assertEqual(6, run['source']['pageCount'])
                self.assertEqual(3, run['providerInputIdentity']['pageCount'])
                self.assertEqual([2, 3, 5], [p['physicalPageNo'] for p in run['pages']])
                self.assertTrue(receipt['completeness']['eligibleForCanonical'])
                self.assertEqual('unknown', run['selectedSourceProvenance']['geometryStatus'])
                runs.append(run)
            bundle = build_dual_parser_evidence(runs, artifact_store=target)
            assert_complete_dual(bundle, [2, 3, 5], artifact_store=target)
            self.assertEqual(runs[0]['providerInputIdentity']['inputSha256'], runs[1]['providerInputIdentity']['inputSha256'])
            self.assertEqual('unknown', project_bbox_to_original(runs[1], runs[1]['pages'][0],
                runs[1]['pages'][0]['blocks'][0]['bbox'])['status'])

    def test_capture_replays_from_cas_after_all_original_host_paths_removed(self):
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root); manifest, store = f.capture('mineru')
            run, _ = capture_parser_files(manifest, store)
            for spec in [manifest['sourceArtifact'], *manifest['assets'].values(), *manifest['outputs'].values()]:
                Path(spec['path']).unlink(missing_ok=True)
            self.assertEqual(run, rebuild_file_capture(run, ArtifactStore(store.root)))

    def test_partial_capture_wrong_parent_or_wrong_provider_identity_fails(self):
        for kind in ('missing_page', 'boolean', 'wrong_parent', 'derived_as_parent', 'provider_count', 'wrong_provider', 'source_name'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                f = Fixture(root); manifest, store = f.capture('mineru')
                if kind == 'missing_page': manifest['pageMappings'].pop()
                elif kind == 'boolean': manifest['pageMappings'][0]['parserPageIndex'] = False
                elif kind == 'wrong_parent': manifest['source']['pageCount'] = 7
                elif kind == 'derived_as_parent': manifest['source']['sourceSha256'] = f.manifest['providerInput']['inputSha256']
                elif kind == 'provider_count': manifest['providerInputIdentity']['pageCount'] = 2
                elif kind == 'wrong_provider': manifest['providerInputIdentity']['inputSha256'] = 'f' * 64
                else: manifest['source']['sourceFilename'] = 'False full source.pdf'
                with self.assertRaises(ParserEvidenceError): capture_parser_files(manifest, store)

    def test_source_selection_manifest_required_asset_and_seal_cannot_be_removed(self):
        for kind in ('required', 'asset', 'seal'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                f = Fixture(root); manifest, store = f.capture('mineru')
                if kind == 'required': manifest['requiredArtifacts'].remove('selected-source.json')
                elif kind == 'asset': manifest['assets'].pop('selected-source.json')
                else: manifest['selectedSource']['manifestSha256'] = 'f' * 64
                with self.assertRaises(ParserEvidenceError): capture_parser_files(manifest, store)

    def test_resealed_capture_provenance_cannot_delete_selection_or_change_page_map(self):
        for kind in ('delete', 'map', 'producer'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                f = Fixture(root); manifest, store = f.capture('mineru')
                run, _ = capture_parser_files(manifest, store)
                altered = copy.deepcopy(run); value = altered['fileCaptureProvenance']['manifest']
                if kind == 'delete': value.pop('selectedSource')
                elif kind == 'map': value['pageMappings'][0]['physicalPageNo'] = 1
                else: altered['selectedSourceProvenance']['producer']['codeArtifact']['sha256'] = 'f' * 64
                altered['fileCaptureProvenance']['manifestSha256'] = canonical_digest(value)
                altered['runContentSha256'] = canonical_digest({k: v for k, v in altered.items() if k != 'runContentSha256'})
                with self.assertRaises(ParserEvidenceError): rebuild_file_capture(altered, store)

    def test_cas_selection_dependency_drift_after_capture_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root); manifest, store = f.capture('mineru'); run, _ = capture_parser_files(manifest, store)
            rebuild_file_capture(run, store)
            dependency = f.manifest['producer']['dependencyFiles'][0]['artifactDescriptor']
            (store.root / dependency['relativePath']).write_bytes(b'corrupt dependency after previous validation')
            with self.assertRaises((ParserEvidenceError, ArtifactStoreError)): rebuild_file_capture(run, store)

    def test_dual_material_gate_reads_selection_cas_again_after_prior_success(self):
        with tempfile.TemporaryDirectory() as root:
            f = Fixture(root); store = ArtifactStore(f.root / 'dual-cas'); runs = []
            for parser in ('paddleocr-vl', 'mineru'):
                manifest, _ = f.capture(parser, store)
                runs.append(capture_parser_files(manifest, store)[0])
            bundle = build_dual_parser_evidence(runs, artifact_store=store)
            assert_complete_dual(bundle, [2, 3, 5], artifact_store=store)
            dependency = f.manifest['producer']['dependencyFiles'][0]['artifactDescriptor']
            (store.root / dependency['relativePath']).write_bytes(b'changed after complete dual admission')
            with self.assertRaises(ParserEvidenceError): assert_complete_dual(bundle, [2, 3, 5], artifact_store=store)

    def test_selected_marker_removal_or_resealed_wrong_map_cannot_downgrade_dual_gate(self):
        for kind in ('marker', 'map', 'provider'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                f = Fixture(root); store = ArtifactStore(f.root / 'dual-cas'); runs = []
                for parser in ('paddleocr-vl', 'mineru'):
                    manifest, _ = f.capture(parser, store)
                    runs.append(capture_parser_files(manifest, store)[0])
                if kind == 'marker': runs[0].pop('selectedSourceProvenance')
                elif kind == 'map': runs[0]['requestPageMap'][0]['physicalPageNo'] = 1
                else: runs[0]['providerInputIdentity']['pageCount'] = 2
                runs[0]['runContentSha256'] = canonical_digest({k:v for k,v in runs[0].items() if k != 'runContentSha256'})
                bundle = build_dual_parser_evidence(runs, artifact_store=store)
                with self.assertRaises(ParserEvidenceError): assert_complete_dual(bundle, [2, 3, 5], artifact_store=store)


if __name__ == '__main__':
    unittest.main()
