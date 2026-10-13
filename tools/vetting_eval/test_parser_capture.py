"""Pinned file and independent-input batch capture, without parser execution."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore
from parser_capture import (CAPTURE_SCHEMA, canonical_digest, capture_parser_files,
                            combine_parser_captures, file_spec)
from structured_parser_evidence import (ParserEvidenceError, assert_complete_dual,
    build_dual_parser_evidence, read_artifact_record, project_bbox_to_original)
from test_structured_parser_evidence import (SOURCE, SOURCE_BYTES, PAGE_BYTES, run,
                                            mapping, provider_input)


def capture_fixture(directory, parser='paddleocr-vl'):
    root = Path(directory)
    fixture_run = run(parser)
    outputs, assets = {}, {}
    original = root / 'original.pdf'
    original.write_bytes(SOURCE_BYTES)
    for name, record in fixture_run['rawOutputs'].items():
        raw = read_artifact_record(record)
        # Vary whitespace while preserving the vendor JSON observation.
        wire = json.dumps(json.loads(raw), ensure_ascii=False, indent=3).replace('\n', '\r\n').encode('utf-8')
        path = root / (name + '.json')
        path.write_bytes(wire)
        outputs[name] = file_spec(path, capture_scope='authored_vendor_file_exact_bytes')
    for name, record in fixture_run['artifacts'].items():
        path = root / name
        path.write_bytes(read_artifact_record(record))
        assets[name] = file_spec(path, capture_scope='authored_actual_input_bytes')
    return {'schema': CAPTURE_SCHEMA, 'parser': parser, 'source': fixture_run['source'],
        'sourceArtifact': {**file_spec(original, capture_scope='original_source_bytes'), 'artifactName': 'original-source.pdf'},
        'identity': fixture_run['identity'], 'providerInputIdentity': fixture_run['providerInputIdentity'],
        'pageMappings': fixture_run['requestPageMap'], 'outputs': outputs, 'assets': assets,
        'requiredArtifacts': sorted(list(assets) + ['original-source.pdf']),
        'terminal': fixture_run['terminal'],
        'captureLimitations': ['Authored wire fixture; internal model tensors not supplied']}


def independent_run(parser, physical, *, terminal=None, identity_revision=None):
    mappings = mapping(parser, physical=physical)
    mappings[0]['providerInputPageNo'] = 1
    outputs = None
    if parser == 'mineru':
        base = run('mineru')
        outputs = {name: json.loads(read_artifact_record(record)) for name, record in base['rawOutputs'].items()}
        outputs['model_json']['page_index_map'] = [0]
    result = run(parser, page_map=mappings, outputs=outputs,
        provider_input_identity=provider_input('paddleocr-vl'),
        **({'terminal': terminal} if terminal is not None else {}))
    if identity_revision:
        result['identity']['models'][0]['revision'] = identity_revision
        result['runContentSha256'] = canonical_digest({k: v for k, v in result.items() if k != 'runContentSha256'})
    return result


class ParserFileCaptureTest(unittest.TestCase):
    def test_vendor_whitespace_and_all_original_resource_bytes_remain_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = capture_fixture(directory)
            store = ArtifactStore(Path(directory) / 'store')
            captured, receipt = capture_parser_files(manifest, store)
            for name, spec in manifest['outputs'].items():
                self.assertEqual(Path(spec['path']).read_bytes(), read_artifact_record(captured['rawOutputs'][name], artifact_store=store))
                self.assertEqual('exact_supplied_bytes', captured['rawOutputs'][name]['identityScope'])
            self.assertTrue(captured['completeness']['eligibleForCanonical'])
            self.assertFalse(receipt['vendorRawBytesReserialized'])
            self.assertFalse(receipt['parserExecutedDuringCapture'])
            self.assertFalse(receipt['qualityAccepted'])
            self.assertEqual(manifest['captureLimitations'], captured['fileCaptureProvenance']['captureLimitations'])
            self.assertEqual(SOURCE_BYTES, store.read_asset(receipt['sourceDescriptor']))
            self.assertEqual(captured, capture_parser_files(manifest, store)[0])

    def test_missing_changed_file_false_source_identity_or_unpinned_spec_fails_capture(self):
        for failure in ('missing', 'changed', 'source', 'hash', 'bytes'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                manifest = capture_fixture(directory)
                if failure == 'missing': Path(manifest['outputs']['page_json']['path']).unlink()
                elif failure == 'changed': Path(manifest['outputs']['page_json']['path']).write_bytes(b'changed raw')
                elif failure == 'source': manifest['source']['sourceSha256'] = 'f' * 64
                elif failure == 'hash': manifest['outputs']['page_json'].pop('sha256')
                else: manifest['outputs']['page_json']['byteCount'] = True
                with self.assertRaises((ParserEvidenceError, OSError)):
                    capture_parser_files(manifest, ArtifactStore(Path(directory) / 'store'))

    def test_relative_paths_are_portable_and_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = capture_fixture(directory)
            for spec in [manifest['sourceArtifact'], *manifest['outputs'].values(), *manifest['assets'].values()]:
                spec['path'] = Path(spec['path']).name
            captured, _ = capture_parser_files(manifest, ArtifactStore(Path(directory) / 'store'), base_directory=directory)
            self.assertTrue(captured['completeness']['eligibleForCanonical'])
            manifest['sourceArtifact']['path'] = '../outside.pdf'
            with self.assertRaisesRegex(ParserEvidenceError, 'escapes'):
                capture_parser_files(manifest, ArtifactStore(Path(directory) / 'store'), base_directory=directory)

    def test_existing_receipt_hash_and_size_are_checked_before_spec_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'artifact'
            path.write_bytes(b'original vendor file')
            for kwargs in ({'expected_sha256': 'f' * 64}, {'expected_byte_count': 1}):
                with self.assertRaisesRegex(ParserEvidenceError, 'existing receipt'):
                    file_spec(path, capture_scope='vendor_raw', **kwargs)

    def test_failed_terminal_and_missing_required_stage_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = capture_fixture(directory)
            manifest['terminal']['status'] = 'failed'
            manifest['terminal']['error'] = 'authored failure after output'
            manifest['outputs'].pop('model_output')
            captured, receipt = capture_parser_files(manifest, ArtifactStore(Path(directory) / 'store'))
            self.assertEqual('failed', receipt['terminal']['status'])
            self.assertEqual('incomplete', captured['completeness']['status'])
            self.assertFalse(captured['completeness']['eligibleForCanonical'])


class IndependentCaptureBatchTest(unittest.TestCase):
    def test_batch_keeps_each_vendor_file_input_and_original_index_with_bijective_aliases(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            children = [independent_run('mineru', physical) for physical in (3, 4)]
            batch = combine_parser_captures(children, store)
            self.assertTrue(batch['completeness']['eligibleForCanonical'])
            self.assertTrue(batch['providerInputIdentity']['notASingleProviderDocument'])
            self.assertEqual([3, 4], batch['aggregationIdentity']['physicalPageOrder'])
            self.assertEqual([0, 1], [p['parserPageIndex'] for p in batch['pages']])
            self.assertEqual([0, 0], [p['childCaptureProvenance']['parserPageIndex'] for p in batch['pages']])
            self.assertEqual([1, 1], [p['providerInputPageNo'] for p in batch['pages']])
            self.assertNotIn('middle_json', batch['rawOutputs'])
            for child, alias in zip(children, batch['captureAliasMap']):
                for name, parent_name in alias['rawOutputAliases'].items():
                    self.assertEqual(read_artifact_record(child['rawOutputs'][name]),
                                     read_artifact_record(batch['rawOutputs'][parent_name]))
                self.assertEqual({p['blocks'][0]['blockId'] for p in child['pages']},
                                 {key for key in alias['blockAliases'] if key.endswith('/block/0')})
            for page in batch['pages']:
                self.assertEqual('verified', project_bbox_to_original(batch, page, page['blocks'][0]['bbox'])['status'])
            self.assertEqual(batch, combine_parser_captures(children, store))

    def test_batches_from_each_parser_form_complete_dual_for_selected_pages_only(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            runs = [combine_parser_captures([independent_run(parser, p) for p in (3, 4)], store)
                    for parser in ('paddleocr-vl', 'mineru')]
            bundle = build_dual_parser_evidence(runs, artifact_store=store)
            self.assertTrue(assert_complete_dual(bundle, [3, 4], artifact_store=store)['eligibleForCanonical'])
            self.assertEqual(8, bundle['source']['pageCount'])
            self.assertEqual([3, 4], bundle['dualCompleteness']['requiredPhysicalPages'])
            self.assertIsNone(bundle['canonicalSelection'])

    def test_mixed_model_source_duplicate_page_or_tampered_child_is_rejected(self):
        for failure in ('model', 'source', 'duplicate', 'child_tamper'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                children = [independent_run('paddleocr-vl', p) for p in (3, 4)]
                if failure == 'model':
                    children[1]['identity']['models'][0]['revision'] = 'a' * 40
                    children[1]['runContentSha256'] = canonical_digest({k: v for k, v in children[1].items() if k != 'runContentSha256'})
                elif failure == 'source':
                    children[1]['source']['sourceSha256'] = 'a' * 64
                    children[1]['runContentSha256'] = canonical_digest({k: v for k, v in children[1].items() if k != 'runContentSha256'})
                elif failure == 'duplicate': children[1] = copy.deepcopy(children[0])
                else: children[1]['pages'][0]['blocks'][0]['text'] = 'changed after capture'
                with self.assertRaises(ParserEvidenceError):
                    combine_parser_captures(children, ArtifactStore(directory))

    def test_failed_or_unknown_child_keeps_failure_and_cannot_be_canonical(self):
        for status in ('failed', 'unknown'):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                terminal = {'status': status, 'pageResults': [{'parserPageIndex': 0, 'status': status}]}
                children = [independent_run('paddleocr-vl', 3), independent_run('paddleocr-vl', 4, terminal=terminal)]
                batch = combine_parser_captures(children, ArtifactStore(directory))
                self.assertFalse(batch['completeness']['eligibleForCanonical'])
                self.assertEqual(status, batch['independentInputCaptures'][1]['terminal']['status'])

    def test_parent_alias_tamper_even_with_resealed_parent_is_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            paddle = combine_parser_captures([independent_run('paddleocr-vl', p) for p in (3, 4)], store)
            mineru = combine_parser_captures([independent_run('mineru', p) for p in (3, 4)], store)
            paddle['captureAliasMap'][0]['batchParserPageIndex'] = 99
            paddle['runContentSha256'] = canonical_digest({k: v for k, v in paddle.items() if k != 'runContentSha256'})
            bundle = build_dual_parser_evidence([paddle, mineru], artifact_store=store)
            self.assertFalse(bundle['dualCompleteness']['eligibleForCanonical'])
            with self.assertRaisesRegex(ParserEvidenceError, 'not complete'):
                assert_complete_dual(bundle, artifact_store=store)


if __name__ == '__main__':
    unittest.main()
