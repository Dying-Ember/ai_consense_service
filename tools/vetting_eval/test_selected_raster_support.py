"""Authored full-page fixtures only: no real contract render/model/business API."""
from pathlib import Path
import copy
import hashlib
import json
import tempfile
import unittest

from artifact_store import ArtifactStore, ArtifactStoreError
from parser_capture import (CAPTURE_SCHEMA, capture_parser_files, rebuild_file_capture,
                            file_spec, _selected_source_binding)
from pdf_selected_source import (SCHEMA, RECIPE, EMPTY_USER_PASSWORD_SCHEMA, SelectedSourceError,
    create_selected_source, canonical_bytes, digest, validate_selected_source)
from selected_source_raster import create_selected_raster, validate_selected_raster
from structured_parser_evidence import ParserEvidenceError
from test_pdf_selected_source import source_pdf
from test_structured_parser_evidence import identity


def encrypted_pdf(source, output, password=''):
    from pypdf import PdfReader, PdfWriter
    from pypdf.constants import UserAccessPermissions
    writer = PdfWriter()
    writer.append(PdfReader(source), import_outline=False)
    writer.encrypt(user_password=password, owner_password='authored-separate-owner-password',
                   permissions_flag=UserAccessPermissions.PRINT)
    with output.open('wb') as stream:
        writer.write(stream)


def own_render(selected_path, provider_page_no, output):
    import pypdfium2
    doc = pypdfium2.PdfDocument(selected_path)
    try:
        doc.init_forms()
        page = doc[provider_page_no - 1]
        try:
            bitmap = page.render(scale=300 / 72, draw_annots=True, may_draw_forms=True)
            try:
                image = bitmap.to_pil().convert('RGB')
                try:
                    image.save(output)
                    return image.size
                finally:
                    image.close()
            finally:
                bitmap.close()
        finally:
            page.close()
    finally:
        doc.close()


class SelectedRasterSupportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        plain = cls.root / 'plain.pdf'; source_pdf(plain)
        cls.source = cls.root / 'encrypted.pdf'; encrypted_pdf(plain, cls.source)
        cls.store = ArtifactStore(cls.root / 'cas')
        cls.selection = create_selected_source(cls.source, [2, 3, 5], cls.root / 'selection', cls.store,
            expected_source_sha256=hashlib.sha256(cls.source.read_bytes()).hexdigest(), expected_page_count=6,
            source_schema=EMPTY_USER_PASSWORD_SCHEMA)
        cls.png = cls.root / 'selected-page-two.png'
        width, height = own_render(cls.root / 'selection/provider-input.pdf', 2, cls.png)
        cls.raster = create_selected_raster(cls.selection, 2, cls.png, cls.root/'raster', cls.store)
        outputs = {}
        for name, value in {'page_json': {'page_index': 0, 'parsing_res_list': [
            {'block_label': 'text', 'block_content': 'Authored output only, not actual OCR quality.', 'block_bbox': [1, 1, 20, 20]}]},
            'model_output': {'authoredFixtureOnly': True}}.items():
            path = cls.root / (name + '.json'); path.write_bytes(canonical_bytes(value))
            outputs[name] = file_spec(path, capture_scope='authored_fixture_not_model_result')
        assets = {'parent.pdf': file_spec(cls.source, capture_scope='authored_encrypted_complete_parent'),
            'input.png': file_spec(cls.png, capture_scope='authored_complete_full_page_input'),
            'selection.json': file_spec(cls.root/'selection/selected-source-manifest.json', capture_scope='actual_fixture_selected_source'),
            'raster.json': file_spec(cls.root/'raster/selected-raster-manifest.json', capture_scope='actual_fixture_raster_lineage')}
        parent = cls.selection['parentSource']
        cls.manifest = {'schema': CAPTURE_SCHEMA, 'parser': 'paddleocr-vl',
            'source': {k: parent[k] for k in ('sourceFilename', 'sourceSha256', 'sourceRevisionSha256', 'pageCount', 'mediaType')},
            'sourceArtifact': {**assets['parent.pdf'], 'artifactName': 'parent.pdf'},
            'assets': assets, 'outputs': outputs, 'requiredArtifacts': list(assets), 'identity': identity('paddleocr-vl'),
            'selectedRasterSource': {'selectionArtifactName': 'selection.json', 'selectionManifestSha256': cls.selection['manifestSha256'],
                 'rasterArtifactName': 'raster.json', 'rasterManifestSha256': cls.raster['manifestSha256']},
            'providerInputIdentity': {'inputSha256': cls.raster['rasterInput']['sha256'], 'inputArtifactName': 'input.png',
                 'pageCount': 1, 'mediaType': 'image/png', 'pageIndexMapMeaning': 'provider_input_document'},
            'pageMappings': [{'parserPageIndex': 0, 'providerInputPageNo': 1, 'physicalPageNo': 3,
                'width': width, 'height': height, 'coordinateUnit': 'pixel', 'providerBboxFrame': 'raster',
                'inputArtifactName': 'input.png', 'originalTransformStatus': 'unknown'}],
            'terminal': {'status': 'completed', 'pageResults': [{'parserPageIndex': 0, 'status': 'completed'}]}}

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def capture(self, manifest):
        return capture_parser_files(manifest, self.store, base_directory=self.root)

    def seal(self, manifest):
        manifest['manifestSha256'] = digest({k: v for k, v in manifest.items() if k != 'manifestSha256'})
        return manifest

    def test_explicit_encrypted_v2_source_replays_actual_permission_bytes(self):
        from pypdf import PdfReader
        before = (self.source.read_bytes(), self.source.stat().st_mtime_ns)
        report = validate_selected_source(self.selection, self.store)
        reader = PdfReader(self.source); result = reader.decrypt('')
        self.assertEqual(('USER_PASSWORD', 1), (result.name, int(result)))
        self.assertFalse(bool(int(reader.trailer['/Encrypt']['/P']) & (1 << 4)))
        self.assertEqual(before, (self.source.read_bytes(), self.source.stat().st_mtime_ns))
        self.assertEqual(('verified', 'unknown', False), (report['status'], report['geometryStatus'], report['qualityAccepted']))

    def test_full_png_lineage_normal_capture_and_cold_replay(self):
        run, receipt = self.capture(self.manifest)
        self.assertEqual(run, rebuild_file_capture(run, self.store))
        self.assertEqual('image/png', run['providerInputIdentity']['mediaType'])
        self.assertEqual(1, run['providerInputIdentity']['pageCount'])
        self.assertEqual(3, run['pages'][0]['physicalPageNo'])
        self.assertEqual('verified_complete_rgb', run['selectedSourceProvenance']['fullRasterReplay'])
        self.assertEqual('unknown', run['selectedSourceProvenance']['geometryStatus'])
        self.assertFalse(receipt['qualityAccepted'])

    def test_missing_retained_producer_code_cas_rejected(self):
        value = copy.deepcopy(self.raster)
        missing = self.store.put_bytes(b'authored unique missing retained producer code fixture')
        (self.store.root / missing['relativePath']).unlink()
        value['producer']['codeArtifact'] = missing
        with self.assertRaisesRegex(ArtifactStoreError, 'missing or unreadable'):
            validate_selected_raster(self.seal(value), self.store)

    def test_tampered_retained_producer_code_cas_rejected(self):
        value = copy.deepcopy(self.raster)
        changed = self.store.put_bytes(b'authored unique tampered retained producer code fixture')
        path = self.store.root / changed['relativePath']
        path.write_bytes(path.read_bytes() + b' actual bytes changed')
        value['producer']['codeArtifact'] = changed
        with self.assertRaisesRegex(ArtifactStoreError, 'does not match its descriptor'):
            validate_selected_raster(self.seal(value), self.store)

    def test_different_readable_retained_code_is_material_provenance_only(self):
        value = copy.deepcopy(self.raster)
        retained = self.store.put_bytes(b"raise AssertionError('retained code must never be executed')\n")
        self.assertNotEqual(self.raster['producer']['codeArtifact']['sha256'], retained['sha256'])
        value['producer']['codeArtifact'] = retained
        report = validate_selected_raster(self.seal(value), self.store)
        self.assertEqual('verified_complete_rgb', report['fullRasterReplay'])
        self.assertEqual('retained_cas_provenance_not_current_reader_authentication',
                         report['producerCodeProvenanceStatus'])
        self.assertEqual(retained['sha256'], report['producerCodeSha256'])
        self.assertIs(False, report['historicalProducerExecutionAuthenticated'])
        self.assertIs(False, report['producerCodeExecutedByReader'])
        self.assertEqual('unknown', report['geometryStatus'])
        self.assertIs(False, report['qualityAccepted'])

    def test_old_legal_independent_image_branch_retained_without_selection_claim(self):
        value = copy.deepcopy(self.manifest); value.pop('selectedRasterSource')
        for name in ('selection.json', 'raster.json'):
            value['assets'].pop(name); value['requiredArtifacts'].remove(name)
        run, receipt = self.capture(value)
        self.assertNotIn('selectedSourceProvenance', run)
        self.assertEqual('unknown', run['pages'][0]['originalPageGeometryStatus'])
        self.assertFalse(receipt['qualityAccepted'])

    def test_marker_removal_cannot_ignore_actual_selection_or_raster_asset(self):
        value = copy.deepcopy(self.manifest); value.pop('selectedRasterSource')
        with self.assertRaises(ParserEvidenceError): self.capture(value)

    def test_missing_selection_or_raster_asset_rejected(self):
        for name in ('selection.json', 'raster.json'):
            with self.subTest(name=name):
                value = copy.deepcopy(self.manifest); value['assets'].pop(name)
                with self.assertRaises(ParserEvidenceError): self.capture(value)

    def test_downgrading_png_to_selected_pdf_marker_rejected(self):
        value = copy.deepcopy(self.manifest); value.pop('selectedRasterSource')
        value['selectedSource'] = {'artifactName': 'selection.json', 'manifestSha256': self.selection['manifestSha256']}
        with self.assertRaises(ParserEvidenceError): self.capture(value)

    def test_marker_or_capture_page_index_and_frame_mismatches_rejected(self):
        cases = [('physicalPageNo', 5), ('physicalPageNo', True), ('parserPageIndex', 1), ('providerInputPageNo', 2),
                 ('width', self.raster['rasterInput']['width'] - 1), ('coordinateUnit', 'pt')]
        for key, replacement in cases:
            with self.subTest(key=key, replacement=replacement):
                value = copy.deepcopy(self.manifest); value['pageMappings'][0][key] = replacement
                with self.assertRaises(ParserEvidenceError): self.capture(value)

    def test_typed_bindings_and_provider_identity_changes_rejected(self):
        for kind in ('selection', 'raster', 'provider-sha', 'provider-count', 'provider-media', 'parent'):
            with self.subTest(kind=kind):
                value = copy.deepcopy(self.manifest)
                if kind in ('selection', 'raster'):
                    value['selectedRasterSource'][kind + 'ManifestSha256'] = 'f' * 64
                elif kind == 'provider-sha': value['providerInputIdentity']['inputSha256'] = 'f' * 64
                elif kind == 'provider-count': value['providerInputIdentity']['pageCount'] = 2
                elif kind == 'provider-media': value['providerInputIdentity']['mediaType'] = 'application/pdf'
                else: value['source']['sourceSha256'] = 'f' * 64
                with self.assertRaises(ParserEvidenceError): self.capture(value)

    def test_same_dimensions_modified_pixels_fail_fresh_rgb_replay(self):
        from PIL import Image
        value = copy.deepcopy(self.raster)
        altered = self.root/'one-pixel-altered.png'
        with Image.open(self.png) as image:
            image.load(); color = image.getpixel((0, 0)); image.putpixel((0, 0), tuple(255-v for v in color)); image.save(altered)
            raw_pixels = image.tobytes()
        descriptor = self.store.put_file(altered)
        value['rasterInput'].update(artifactDescriptor=descriptor, sha256=descriptor['sha256'], byteCount=descriptor['byteCount'],
                                   rgbPixelSha256=hashlib.sha256(raw_pixels).hexdigest())
        with self.assertRaisesRegex(SelectedSourceError, 'not the exact full'):
            validate_selected_raster(self.seal(value), self.store)

    def test_wrong_selected_page_cannot_reuse_same_png(self):
        value = copy.deepcopy(self.raster)
        value.update(selectedProviderPageNo=3, originalPhysicalPageNo=5)
        with self.assertRaises(SelectedSourceError): validate_selected_raster(self.seal(value), self.store)

    def test_crop_cannot_be_declared_complete_even_with_updated_size(self):
        from PIL import Image
        value = copy.deepcopy(self.raster); target = self.root/'cropped.png'
        with Image.open(self.png) as image:
            cropped = image.crop((0, 0, image.width // 2, image.height)); cropped.save(target)
            size, pixels = cropped.size, cropped.tobytes()
        descriptor = self.store.put_file(target)
        value['rasterInput'].update(artifactDescriptor=descriptor, sha256=descriptor['sha256'], byteCount=descriptor['byteCount'],
                                   width=size[0], height=size[1], rgbPixelSha256=hashlib.sha256(pixels).hexdigest())
        with self.assertRaises(SelectedSourceError): validate_selected_raster(self.seal(value), self.store)

    def test_mutated_raster_recipe_or_dependency_not_admitted(self):
        for kind in ('recipe', 'versions', 'dependency', 'quality'):
            with self.subTest(kind=kind):
                value = copy.deepcopy(self.raster)
                if kind == 'recipe': value['renderRecipe']['dpi'] = 150
                elif kind == 'versions': value['producer']['libraryVersions']['pypdfium2'] = 'wrong'
                elif kind == 'quality': value['qualityAccepted'] = True
                else:
                    dep = value['producer']['dependencyFiles'][0]
                    changed = self.store.put_bytes(b'not actual installed dependency bytes')
                    dep.update(sha256=changed['sha256'], byteCount=changed['byteCount'], artifactDescriptor=changed)
                    value['producer']['dependencyInventorySha256'] = digest(value['producer']['dependencyFiles'])
                with self.assertRaises(SelectedSourceError): validate_selected_raster(self.seal(value), self.store)

    def test_nonempty_password_is_not_guessed(self):
        nonempty = self.root/'nonempty.pdf'; encrypted_pdf(self.root/'plain.pdf', nonempty, 'fixture-secret')
        with self.assertRaises(SelectedSourceError):
            create_selected_source(nonempty, [1], self.root/'forbidden-nonempty', self.store,
                expected_source_sha256=hashlib.sha256(nonempty.read_bytes()).hexdigest(), expected_page_count=6,
                source_schema=EMPTY_USER_PASSWORD_SCHEMA)

    def test_old_plain_recipe_does_not_gain_encrypted_fallback(self):
        with self.assertRaises(SelectedSourceError):
            create_selected_source(self.source, [1], self.root/'forbidden-default-encrypted', self.store,
                expected_source_sha256=self.selection['parentSource']['sourceSha256'], expected_page_count=6)
        value = copy.deepcopy(self.selection); value['schema'] = SCHEMA; value['extractionRecipe'] = copy.deepcopy(RECIPE)
        with self.assertRaises(SelectedSourceError): validate_selected_source(self.seal(value), self.store)

    def test_unknown_selected_version_cannot_fall_back_to_plain_image(self):
        value = copy.deepcopy(self.manifest); value.pop('selectedRasterSource')
        unknown = copy.deepcopy(self.selection); unknown['schema'] = 'consense.pdf-selected-source.unknown-v99'
        path = self.root/'unknown-schema.json'; path.write_bytes(canonical_bytes(self.seal(unknown)))
        value['assets']['selection.json'] = file_spec(path, capture_scope='authored_unknown_schema_negative')
        value['assets'].pop('raster.json'); value['requiredArtifacts'].remove('raster.json')
        with self.assertRaises(ParserEvidenceError): self.capture(value)

    def test_selection_and_raster_schema_removal_or_unrelated_relabelling_rejected(self):
        for name, original in (('selection.json', self.selection), ('raster.json', self.raster)):
            for replacement in (None, 'unrelated-resource', {'looks': 'ordinary-json'}):
                with self.subTest(name=name, replacement=replacement):
                    value = copy.deepcopy(self.manifest); value.pop('selectedRasterSource')
                    other = 'raster.json' if name == 'selection.json' else 'selection.json'
                    value['assets'].pop(other); value['requiredArtifacts'].remove(other)
                    artifact = copy.deepcopy(original)
                    if replacement is None: artifact.pop('schema')
                    else: artifact['schema'] = replacement
                    path = self.root / ('schema-hidden-' + name)
                    path.write_bytes(canonical_bytes(self.seal(artifact)))
                    value['assets'][name] = file_spec(path, capture_scope='authored_schema_hidden_negative')
                    with self.assertRaises(ParserEvidenceError): self.capture(value)


if __name__ == '__main__':
    unittest.main()
