"""Producer byte identity survives a newer reader without authenticating provenance."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError
from native_pdf_canonical_projection import _rebuild_run
import parser_capture
from parser_capture import canonical_digest, capture_parser_files, combine_parser_captures
from structured_parser_evidence import ParserEvidenceError
from test_parser_capture import capture_fixture
from test_structured_parser_evidence import mapping, provider_input


def reseal(run):
    run['runContentSha256'] = canonical_digest({k: v for k, v in run.items() if k != 'runContentSha256'})


def historical_batch(directory):
    root = Path(directory)
    store = ArtifactStore(root / 'cas')
    children = []
    for physical in (3, 4):
        folder = root / str(physical)
        folder.mkdir()
        manifest = capture_fixture(folder)
        manifest['pageMappings'] = mapping('paddleocr-vl', physical=physical)
        manifest['pageMappings'][0]['providerInputPageNo'] = 1
        manifest['providerInputIdentity'] = provider_input('paddleocr-vl')
        children.append(capture_parser_files(manifest, store)[0])
    # Authored historical producer bytes are hashed, never imported/executed.
    # The actual production combine seam emits identity while the simulated old
    # producer file is present; after the context the reader has different bytes.
    producer = root / 'historical-producer.py'
    producer.write_bytes(Path(parser_capture.__file__).read_bytes() + b'\n# authored earlier producer identity\n')
    with mock.patch.object(parser_capture, '__file__', str(producer)):
        batch = combine_parser_captures(children, store)
    assert batch['aggregationIdentity']['codeSha256'] == hashlib.sha256(producer.read_bytes()).hexdigest()
    assert batch['aggregationIdentity']['codeSha256'] != hashlib.sha256(Path(parser_capture.__file__).read_bytes()).hexdigest()
    return batch, store, producer


class ProducerReaderIdentityReplayTest(unittest.TestCase):
    def test_historical_producer_batch_replays_with_new_reader_entire_object_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            batch, store, producer = historical_batch(directory)
            original = copy.deepcopy(batch)
            producer.unlink()  # Replay never authenticates/reopens a producer host path.
            for child in batch['independentInputCaptures']:
                m = child['fileCaptureProvenance']['manifest']
                for spec in [m['sourceArtifact'], *m['outputs'].values(), *m['assets'].values()]:
                    Path(spec['path']).unlink(missing_ok=True)
            self.assertEqual(original, _rebuild_run(batch, ArtifactStore(store.root)))
            self.assertEqual(original, batch)

    def test_direct_completeness_verifier_uses_historical_producer_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            batch, store, _ = historical_batch(directory)
            from parser_capture import verify_independent_capture_batch
            self.assertEqual(batch['completeness'], verify_independent_capture_batch(batch, store))

    def test_public_new_production_uses_current_file_hash_and_has_no_replay_argument(self):
        with tempfile.TemporaryDirectory() as directory:
            old, store, _ = historical_batch(directory)
            fresh = combine_parser_captures(old['independentInputCaptures'], store)
            self.assertEqual(hashlib.sha256(Path(parser_capture.__file__).read_bytes()).hexdigest(),
                             fresh['aggregationIdentity']['codeSha256'])
            self.assertNotEqual(old['runId'], fresh['runId'])
            with self.assertRaises(TypeError):
                combine_parser_captures(old['independentInputCaptures'], store,
                                        replay_aggregation=old['aggregationIdentity'])

    def test_invalid_or_resealed_aggregation_identity_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            batch, store, _ = historical_batch(directory)
            for kind in ('unknown_version', 'wrong_kind', 'bad_sha', 'bool_sha', 'missing_sha',
                         'changed_producer', 'child_hash', 'page_order', 'extra_key', 'missing_aggregation'):
                with self.subTest(kind=kind):
                    changed = copy.deepcopy(batch)
                    agg = changed['aggregationIdentity']
                    if kind == 'unknown_version': agg['version'] = 'future-unsupported'
                    elif kind == 'wrong_kind': agg['kind'] = 'single_provider_document'
                    elif kind == 'bad_sha': agg['codeSha256'] = 'bad'
                    elif kind == 'bool_sha': agg['codeSha256'] = True
                    elif kind == 'missing_sha': agg.pop('codeSha256')
                    elif kind == 'changed_producer': agg['codeSha256'] = 'f' * 64
                    elif kind == 'child_hash': agg['childContentSha256'][0] = 'f' * 64
                    elif kind == 'page_order': agg['physicalPageOrder'].reverse()
                    elif kind == 'extra_key': agg['extra'] = 'must not survive'
                    else: changed.pop('aggregationIdentity')
                    reseal(changed)
                    with self.assertRaises((ParserEvidenceError, CanonicalProjectionError, KeyError, TypeError)):
                        _rebuild_run(changed, store)

    def test_resealed_parent_alias_blocks_manifest_and_completeness_tamper_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            batch, store, _ = historical_batch(directory)
            for kind in ('alias', 'parent_block', 'required_asset', 'capture_manifest', 'completeness', 'terminal'):
                with self.subTest(kind=kind):
                    changed = copy.deepcopy(batch)
                    if kind == 'alias': changed['captureAliasMap'][0]['batchParserPageIndex'] = 99
                    elif kind == 'parent_block': changed['pages'][0]['blocks'][0]['text'] = 'invented'
                    elif kind == 'required_asset': changed['requiredArtifacts'].pop()
                    elif kind == 'capture_manifest': changed['captureManifest']['unbound'] = 'metadata'
                    elif kind == 'completeness': changed['completeness']['eligibleForCanonical'] = False
                    else: changed['terminal']['status'] = 'failed'
                    reseal(changed)
                    with self.assertRaises((ParserEvidenceError, CanonicalProjectionError, KeyError)):
                        _rebuild_run(changed, store)

    def test_resealed_child_observation_cannot_override_actual_cas(self):
        with tempfile.TemporaryDirectory() as directory:
            batch, store, _ = historical_batch(directory)
            changed = copy.deepcopy(batch)
            child = changed['independentInputCaptures'][0]
            child['pages'][0]['blocks'][0]['text'] = 'invented child'
            reseal(child)
            changed['aggregationIdentity']['childContentSha256'][0] = child['runContentSha256']
            reseal(changed)
            with self.assertRaises((ParserEvidenceError, CanonicalProjectionError)):
                _rebuild_run(changed, store)

    def test_cas_is_freshly_read_after_historical_replay_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            batch, store, _ = historical_batch(directory)
            self.assertEqual(batch, _rebuild_run(batch, store))
            descriptor = batch['independentInputCaptures'][0]['rawOutputs']['page_json']['artifactDescriptor']
            (store.root / descriptor['relativePath']).write_bytes(b'changed after pass')
            with self.assertRaises((ParserEvidenceError, CanonicalProjectionError, ValueError)):
                _rebuild_run(batch, ArtifactStore(store.root))


if __name__ == '__main__':
    unittest.main()
