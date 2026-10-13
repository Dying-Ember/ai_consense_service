"""File-captured outputs must survive native-consumer cold replay unchanged."""
import copy
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError
from native_pdf_canonical_projection import _rebuild_run
from parser_capture import canonical_digest, capture_parser_files, combine_parser_captures
from structured_parser_evidence import ParserEvidenceError
from test_parser_capture import capture_fixture
from test_structured_parser_evidence import mapping, provider_input


def reseal(run):
    provenance = run['fileCaptureProvenance']
    provenance['manifestSha256'] = canonical_digest(provenance['manifest'])
    run['runContentSha256'] = canonical_digest({k: v for k, v in run.items() if k != 'runContentSha256'})


class CapturedReplayTest(unittest.TestCase):
    def test_native_consumer_replays_independent_file_captured_children_with_aliases_intact(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            children = []
            for physical in (3, 4):
                folder = Path(directory) / str(physical)
                folder.mkdir()
                manifest = capture_fixture(folder)
                manifest['pageMappings'] = mapping('paddleocr-vl', physical=physical)
                manifest['pageMappings'][0]['providerInputPageNo'] = 1
                manifest['providerInputIdentity'] = provider_input('paddleocr-vl')
                children.append(capture_parser_files(manifest, store)[0])
            batch = combine_parser_captures(children, store)
            self.assertEqual(batch, _rebuild_run(batch, ArtifactStore(Path(directory) / 'cas')))
            self.assertEqual([3, 4], [page['physicalPageNo'] for page in batch['pages']])

    def test_both_actual_capture_formats_replay_from_cas_without_original_host_files(self):
        for parser in ('paddleocr-vl', 'mineru'):
            with self.subTest(parser=parser), tempfile.TemporaryDirectory() as directory:
                manifest = capture_fixture(directory, parser)
                store = ArtifactStore(Path(directory) / 'cas')
                run, _ = capture_parser_files(manifest, store)
                original = copy.deepcopy(run)
                for spec in [manifest['sourceArtifact'], *manifest['outputs'].values(), *manifest['assets'].values()]:
                    path = Path(spec['path'])
                    if path.exists(): path.unlink()
                self.assertEqual(original, _rebuild_run(run, ArtifactStore(Path(directory) / 'cas')))
                self.assertEqual(original, run)

    def test_resealed_manifest_cannot_change_binding_or_smuggle_extra_capture(self):
        for kind in ('source', 'output_hash', 'output_name', 'asset_name', 'identity', 'page_mapping',
                     'terminal', 'required_asset', 'provenance_flag', 'run_text'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                manifest = capture_fixture(directory)
                store = ArtifactStore(Path(directory) / 'cas')
                actual, _ = capture_parser_files(manifest, store)
                changed = copy.deepcopy(actual)
                value = changed['fileCaptureProvenance']['manifest']
                if kind == 'source': value['sourceArtifact']['sha256'] = 'f' * 64
                elif kind == 'output_hash': value['outputs']['page_json']['sha256'] = 'f' * 64
                elif kind == 'output_name': value['outputs']['unbound-output'] = value['outputs']['page_json']
                elif kind == 'asset_name': value['assets']['unbound-asset'] = next(iter(value['assets'].values()))
                elif kind == 'identity': value['identity']['engine'] = 'resealed-wrong-runtime'
                elif kind == 'page_mapping': value['pageMappings'][0]['physicalPageNo'] = 2
                elif kind == 'terminal': value['terminal']['status'] = 'failed'
                elif kind == 'required_asset': value['requiredArtifacts'].remove(value['sourceArtifact']['artifactName'])
                elif kind == 'provenance_flag': changed['fileCaptureProvenance']['vendorRawBytesReserialized'] = True
                else: changed['pages'][0]['blocks'][0]['text'] = 'resealed invented text'
                reseal(changed)
                with self.assertRaises((ParserEvidenceError, CanonicalProjectionError, KeyError)):
                    _rebuild_run(changed, store)

    def test_same_descriptor_must_read_actual_cas_again_after_prior_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(Path(directory) / 'cas')
            run, _ = capture_parser_files(capture_fixture(directory), store)
            self.assertEqual(run, _rebuild_run(run, store))
            descriptor = run['rawOutputs']['page_json']['artifactDescriptor']
            (store.root / descriptor['relativePath']).write_bytes(b'corrupted after successful read')
            with self.assertRaises((ParserEvidenceError, CanonicalProjectionError, ValueError)):
                _rebuild_run(run, store)


if __name__ == '__main__':
    unittest.main()
