"""Actual PDF anti-downgrade contracts; authored outputs, no OCR or network."""
import copy
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore
from parser_capture import (capture_parser_files, rebuild_file_capture,
                            canonical_digest, _file_capture_provenance, file_spec)
from structured_parser_evidence import (ParserEvidenceError, assert_complete_dual,
    build_dual_parser_evidence)
from test_pdf_selected_source import Fixture


def without_selection(manifest, *, remove_asset=False):
    value = copy.deepcopy(manifest)
    binding = value.pop('selectedSource')
    if remove_asset:
        value['assets'].pop(binding['artifactName'])
        value['requiredArtifacts'].remove(binding['artifactName'])
    return value


def seal_run(run):
    run['runContentSha256'] = canonical_digest({k: v for k, v in run.items()
                                               if k != 'runContentSha256'})
    return run


class PdfInputClosureTest(unittest.TestCase):
    def test_double_marker_deletion_cannot_replay_or_admit_dual(self):
        with tempfile.TemporaryDirectory() as root:
            fixture = Fixture(root)
            store = ArtifactStore(Path(root) / 'dual-cas')
            altered = []
            for parser in ('paddleocr-vl', 'mineru'):
                manifest, _ = fixture.capture(parser, store)
                run = capture_parser_files(manifest, store)[0]
                run.pop('selectedSourceProvenance')
                manifest = run['fileCaptureProvenance']['manifest']
                manifest.pop('selectedSource')
                run['fileCaptureProvenance'] = _file_capture_provenance(manifest)
                seal_run(run)
                with self.subTest(parser=parser):
                    with self.assertRaises(ParserEvidenceError):
                        rebuild_file_capture(run, store)
                altered.append(run)
            bundle = build_dual_parser_evidence(altered, artifact_store=store)
            with self.assertRaises(ParserEvidenceError):
                assert_complete_dual(bundle, [2, 3, 5], artifact_store=store)

    def test_missing_selection_declaration_and_false_claimed_map_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            fixture = Fixture(root)
            for parser in ('paddleocr-vl', 'mineru'):
                manifest, store = fixture.capture(parser)
                value = without_selection(manifest)
                value['pageMappings'][0]['physicalPageNo'] = 1
                with self.subTest(parser=parser), self.assertRaises(ParserEvidenceError):
                    capture_parser_files(value, store)

    def test_removing_entire_selection_asset_still_cannot_falsify_physical_map(self):
        with tempfile.TemporaryDirectory() as root:
            fixture = Fixture(root)
            for parser in ('paddleocr-vl', 'mineru'):
                manifest, store = fixture.capture(parser)
                value = without_selection(manifest, remove_asset=True)
                value['pageMappings'][0]['physicalPageNo'] = 1
                with self.subTest(parser=parser), self.assertRaises(ParserEvidenceError):
                    capture_parser_files(value, store)

    def test_derived_pdf_cannot_use_matched_content_instead_of_typed_source_proof(self):
        with tempfile.TemporaryDirectory() as root:
            fixture = Fixture(root)
            for parser in ('paddleocr-vl', 'mineru'):
                manifest, store = fixture.capture(parser)
                value = without_selection(manifest, remove_asset=True)
                with self.subTest(parser=parser), self.assertRaises(ParserEvidenceError):
                    capture_parser_files(value, store)

    def test_selection_schema_detected_by_actual_bytes_after_asset_rename(self):
        with tempfile.TemporaryDirectory() as root:
            fixture = Fixture(root)
            manifest, store = fixture.capture('mineru')
            value = without_selection(manifest)
            value['assets']['innocent-resource.bin'] = value['assets'].pop('selected-source.json')
            value['requiredArtifacts'].remove('selected-source.json')
            value['requiredArtifacts'].append('innocent-resource.bin')
            with self.assertRaises(ParserEvidenceError):
                capture_parser_files(value, store)

    def test_same_actual_source_pdf_cannot_claim_other_original_pages(self):
        with tempfile.TemporaryDirectory() as root:
            fixture = Fixture(root)
            manifest, store = fixture.capture('mineru')
            value = without_selection(manifest, remove_asset=True)
            value['assets']['provider-input.pdf'] = file_spec(fixture.source, capture_scope='actual_original_input')
            value['providerInputIdentity'].update(inputSha256=manifest['source']['sourceSha256'], pageCount=6)
            for n, mapping in enumerate(value['pageMappings']):
                mapping['providerInputPageNo'] = n + 1
            with self.assertRaises(ParserEvidenceError):
                capture_parser_files(value, store)

    def test_actual_pdf_page_counts_cannot_be_resealed_as_false_metadata(self):
        with tempfile.TemporaryDirectory() as root:
            fixture = Fixture(root)
            manifest, store = fixture.capture('mineru')
            value = without_selection(manifest, remove_asset=True)
            value['source']['pageCount'] = 7
            value['assets']['provider-input.pdf'] = file_spec(fixture.source, capture_scope='actual_original_input')
            value['providerInputIdentity'].update(inputSha256=manifest['source']['sourceSha256'], pageCount=7)
            for mapping in value['pageMappings']:
                mapping['providerInputPageNo'] = mapping['physicalPageNo']
            with self.assertRaises(ParserEvidenceError):
                capture_parser_files(value, store)

    def test_pdf_input_cannot_be_relabelled_as_an_independent_image(self):
        with tempfile.TemporaryDirectory() as root:
            fixture = Fixture(root)
            manifest, store = fixture.capture('mineru')
            value = without_selection(manifest, remove_asset=True)
            value['providerInputIdentity']['mediaType'] = 'image/png'
            value['pageMappings'][0]['physicalPageNo'] = 1
            with self.assertRaises(ParserEvidenceError):
                capture_parser_files(value, store)


    def test_unmodified_full_original_pdf_and_explicit_page_subset_replays_unchanged(self):
        import json
        from pdf_selected_source import canonical_bytes
        with tempfile.TemporaryDirectory() as root:
            fixture = Fixture(root)
            for parser in ('paddleocr-vl', 'mineru'):
                manifest, store = fixture.capture(parser)
                value = without_selection(manifest, remove_asset=True)
                value['assets']['provider-input.pdf'] = file_spec(fixture.source, capture_scope='actual_original_input')
                value['providerInputIdentity'].update(inputSha256=manifest['source']['sourceSha256'], pageCount=6)
                for mapping in value['pageMappings']:
                    mapping['providerInputPageNo'] = mapping['physicalPageNo']
                if parser == 'mineru':
                    path = Path(value['outputs']['model_json']['path'])
                    output = json.loads(path.read_bytes())
                    output['page_index_map'] = [1, 2, 4]
                    path.write_bytes(canonical_bytes(output))
                    value['outputs']['model_json'] = file_spec(path, capture_scope='authored_vendor_bytes_not_model_quality')
                run = capture_parser_files(value, store)[0]
                with self.subTest(parser=parser):
                    self.assertTrue(run['completeness']['eligibleForCanonical'])
                    self.assertEqual(run, rebuild_file_capture(run, store))
                    self.assertNotIn('selectedSourceProvenance', run)
                    self.assertEqual('unknown', run['pages'][0]['originalPageGeometryStatus'])

    def test_repeated_equal_pages_do_not_prove_a_markerless_derived_physical_origin(self):
        from unittest.mock import patch
        from pypdf import PdfWriter, PdfReader
        from pdf_selected_source import _page_payload
        def blank_source(path):
            writer = PdfWriter()
            for _ in range(6): writer.add_blank_page(width=300, height=420)
            with path.open('wb') as stream: writer.write(stream)
        with tempfile.TemporaryDirectory() as root, patch('test_pdf_selected_source.source_pdf', blank_source):
            fixture = Fixture(root, selected=[2])
            reader = PdfReader(fixture.source)
            self.assertEqual(_page_payload(reader.pages[0]), _page_payload(reader.pages[1]))
            for parser in ('paddleocr-vl', 'mineru'):
                manifest, store = fixture.capture(parser)
                value = without_selection(manifest, remove_asset=True)
                value['pageMappings'][0]['physicalPageNo'] = 1
                with self.subTest(parser=parser), self.assertRaises(ParserEvidenceError):
                    capture_parser_files(value, store)


    def test_pdf_prefix_or_fake_image_signature_cannot_open_the_legacy_image_exception(self):
        for prefix in (b'\n', b'\x89PNG\r\n\x1a\n'):
            with self.subTest(prefix=prefix), tempfile.TemporaryDirectory() as root:
                fixture = Fixture(root, selected=[2])
                manifest, store = fixture.capture('paddleocr-vl')
                value = without_selection(manifest, remove_asset=True)
                path = Path(value['assets']['provider-input.pdf']['path'])
                path.write_bytes(prefix + path.read_bytes())
                spec = file_spec(path, capture_scope='authored_disguised_derived_pdf_negative')
                value['assets']['provider-input.pdf'] = spec
                value['providerInputIdentity'].update(inputSha256=spec['sha256'], mediaType='image/png')
                value['pageMappings'][0]['physicalPageNo'] = 1
                with self.assertRaises(ParserEvidenceError):
                    capture_parser_files(value, store)


if __name__ == '__main__':
    unittest.main()
