"""Actual source-asset bytes, authored full PNG only; no model/main writes."""
import copy
import json
from pathlib import Path
import unittest

from parser_capture import file_spec, rebuild_file_capture
from pdf_selected_source import canonical_bytes
from structured_parser_evidence import ParserEvidenceError, read_artifact_record
from test_selected_raster_support import SelectedRasterSupportTests as Fixture


class LineageJsonGuardContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        Fixture.setUpClass()
        cls.support = Fixture()

    @classmethod
    def tearDownClass(cls):
        Fixture.tearDownClass()

    def capture_lineage(self, transform, rename=False):
        manifest = copy.deepcopy(self.support.manifest)
        manifest.pop('selectedRasterSource')
        for index, (name, payload) in enumerate((('selection.json', self.support.selection), ('raster.json', self.support.raster))):
            raw = transform(copy.deepcopy(payload))
            path = self.support.root / ('lineage-guard-' + str(index) + '.data')
            path.write_bytes(raw)
            asset_name = 'unrelated-binary-' + str(index) if rename else name
            if rename:
                manifest['assets'].pop(name)
                manifest['requiredArtifacts'].remove(name)
                manifest['requiredArtifacts'].append(asset_name)
            manifest['assets'][asset_name] = file_spec(path, capture_scope='authored_ambiguous_lineage_guard')
        return self.support.capture(manifest)

    @staticmethod
    def duplicate_schema(payload, first=None, last=None):
        original = payload.pop('schema')
        first = original if first is None else first
        last = original if last is None else last
        return (b'{"schema":' + json.dumps(first).encode() + b',"schema":' + json.dumps(last).encode()
                + b',' + canonical_bytes(payload)[1:])

    @staticmethod
    def nested_duplicate(payload, remove_schema=False):
        if remove_schema:
            payload.pop('schema')
        # A duplicate is inside producer, not the top-level schema. Preserve all
        # original required lineage fields; no parent/PNG/recipe/frame changes.
        payload['producer']['independentDuplicateProbe'] = 0
        return canonical_bytes(payload).replace(b'"independentDuplicateProbe":0',
            b'"independentDuplicateProbe":0,"independentDuplicateProbe":0')

    def test_original_same_schema_duplicate_probe_refused(self):
        with self.assertRaises(ParserEvidenceError):
            self.capture_lineage(self.duplicate_schema)

    def test_duplicate_known_schema_first_or_last_refused(self):
        for position in ('first', 'last'):
            with self.subTest(position=position):
                def altered(payload):
                    original = payload['schema']
                    return self.duplicate_schema(payload,
                        first=original if position == 'first' else 'ordinary.resource.v1',
                        last=original if position == 'last' else 'ordinary.resource.v1')
                with self.assertRaises(ParserEvidenceError):
                    self.capture_lineage(altered)

    def test_duplicate_unknown_lineage_version_overwritten_by_ordinary_schema_refused(self):
        def altered(payload):
            prefix = 'consense.pdf-selected-source.' if 'parentSource' in payload else 'consense.selected-pdf-full-page-raster.'
            return self.duplicate_schema(payload, first=prefix + 'unknown-v999', last='ordinary.resource.v1')
        with self.assertRaises(ParserEvidenceError):
            self.capture_lineage(altered)

    def test_nested_duplicate_with_known_top_level_schema_refused(self):
        with self.assertRaises(ParserEvidenceError):
            self.capture_lineage(self.nested_duplicate)

    def test_nested_duplicate_schemaless_top_level_lineage_structure_refused(self):
        with self.assertRaises(ParserEvidenceError):
            self.capture_lineage(lambda payload: self.nested_duplicate(payload, remove_schema=True))

    def test_nonfinite_nested_json_with_lineage_schema_refused(self):
        for number in (float('nan'), float('inf'), float('-inf')):
            with self.subTest(value=str(number)):
                def altered(payload):
                    payload['independentNonfiniteProbe'] = number
                    return json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()
                with self.assertRaises(ParserEvidenceError):
                    self.capture_lineage(altered)

    def test_complete_lineage_object_with_trailing_invalid_bytes_refused(self):
        with self.assertRaises(ParserEvidenceError):
            self.capture_lineage(lambda payload: canonical_bytes(payload) + b' trailing-not-json')

    def test_unrelated_names_do_not_hide_known_ambiguous_lineage(self):
        with self.assertRaises(ParserEvidenceError):
            self.capture_lineage(self.duplicate_schema, rename=True)

    def test_plain_resources_with_nested_caption_claims_and_invalid_json_retained(self):
        cases = [
            b'{"schema":"ordinary.a","schema":"ordinary.b","caption":{"schema":"consense.pdf-selected-source.v999"}}',
            b'{"caption":{"schema":"consense.selected-pdf-full-page-raster.v1","x":1,"x":2},"score":NaN}',
            b'{"caption":"consense.pdf-selected-source.v1 parentSource providerInput pageMap"}',
            b'{"schema":"ordinary.resource.v1","score":Infinity}',
            b'{"caption":"ordinary incomplete resource"',
        ]
        for index, raw in enumerate(cases):
            with self.subTest(case=index):
                manifest = copy.deepcopy(self.support.manifest)
                manifest.pop('selectedRasterSource')
                for name in ('selection.json', 'raster.json'):
                    manifest['assets'].pop(name); manifest['requiredArtifacts'].remove(name)
                path = self.support.root / ('ordinary-resource-' + str(index) + '.bytes')
                path.write_bytes(raw)
                # An intentionally suggestive filename must not create lineage.
                name = 'selected-source-manifest.json'
                manifest['assets'][name] = file_spec(path, capture_scope='authored_ordinary_resource_raw_bytes')
                manifest['requiredArtifacts'].append(name)
                run, receipt = self.support.capture(manifest)
                self.assertNotIn('selectedSourceProvenance', run)
                self.assertEqual(raw, read_artifact_record(run['artifacts'][name], artifact_store=self.support.store))
                self.assertEqual(run, rebuild_file_capture(run, self.support.store))
                self.assertFalse(receipt['qualityAccepted'])

    def test_nested_complete_lineage_shape_does_not_classify_plain_resource(self):
        raw = json.dumps({'caption': self.support.selection, 'notes': [{'schema': self.support.raster['schema']}]},
                         sort_keys=True, separators=(',', ':')).encode()
        manifest = copy.deepcopy(self.support.manifest); manifest.pop('selectedRasterSource')
        for name in ('selection.json', 'raster.json'):
            manifest['assets'].pop(name); manifest['requiredArtifacts'].remove(name)
        path = self.support.root / 'ordinary-nested-complete-object.json'; path.write_bytes(raw)
        manifest['assets']['ordinary.json'] = file_spec(path, capture_scope='authored_caption_with_nested_lineage_object')
        manifest['requiredArtifacts'].append('ordinary.json')
        run, receipt = self.support.capture(manifest)
        self.assertNotIn('selectedSourceProvenance', run)
        self.assertEqual(raw, read_artifact_record(run['artifacts']['ordinary.json'], artifact_store=self.support.store))
        self.assertFalse(receipt['qualityAccepted'])
