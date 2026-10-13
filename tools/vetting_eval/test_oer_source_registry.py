"""Immutable registry contracts with authored images and real disk artifacts.

The source-review record is explicitly authored wire data, not a neural result
or real original-page acceptance. No model, service, GPU, or network starts.
"""
import base64
import copy
import json
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore, ArtifactStoreError
from canonical_projection import CanonicalProjectionError, project_canonical
from oer_source_registry import CanonicalSourceRegistry, SourceRegistryError
from structured_parser_evidence import (build_dual_parser_evidence, normalize_parser_run,
                                       read_artifact_record)
from test_canonical_projection import fixture


# A tiny authored PNG; distinct trailing authored identifiers are harmless to
# the image container and ensure that wrong-page returns differ byte-for-byte.
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1cAAAAASUVORK5CYII=')
SOURCE_BYTES = b'%PDF-1.7\nAUTHORED-source-wire-not-a-parsed-original\n%%EOF\n'


def asset_fixture(store=None, *, source_bytes=SOURCE_BYTES, valid_images=True):
    """Return a freshly normalized/sealed dual fixture with actual asset bytes.

    expected_bytes maps page3.png/page4.png to their captured bytes. Passing a
    store also externalizes every raw output and input artifact, so HTTP tests
    can exercise the real disk descriptor path rather than an injected reader.
    """
    original, manifest = fixture(source_bytes=source_bytes)
    expected_bytes = {name: PNG + ('AUTHORED-' + name).encode('ascii') if valid_images else
                      ('AUTHORED-not-a-PNG-' + name).encode('ascii')
                      for name in ('page3.png', 'page4.png')}
    runs, new_by_old, block_by_old = [], {}, {}
    for old in original['runs']:
        artifacts = {name: read_artifact_record(record) for name, record in old['artifacts'].items()}
        artifacts.update(expected_bytes)
        mappings = copy.deepcopy(old['requestPageMap'])
        import hashlib
        for mapping in mappings:
            frame = mapping['originalPageTransform']['inputFrame']
            frame['sha256'] = hashlib.sha256(artifacts[frame['artifactName']]).hexdigest()
        captured = normalize_parser_run(old['parser'],
            {name: read_artifact_record(record) for name, record in old['rawOutputs'].items()},
            old['source'], old['identity'], mappings, artifacts=artifacts,
            required_artifacts=old['requiredArtifacts'],
            provider_input_identity=old['providerInputIdentity'], terminal=old['terminal'],
            artifact_store=store)
        runs.append(captured)
        new_by_old[old['runId']] = captured['runId']
        for previous_page, current_page in zip(old['pages'], captured['pages']):
            for previous_block, current_block in zip(previous_page['blocks'], current_page['blocks']):
                block_by_old[previous_block['blockId']] = current_block['blockId']
    for group in manifest['tableGroups']:
        group['candidates'] = {new_by_old[run_id]: [block_by_old[block_id] for block_id in members]
                               for run_id, members in group['candidates'].items()}
    for unit in manifest['units']:
        unit['runId'] = new_by_old[unit['runId']]
        if 'blockId' in unit:
            unit['blockId'] = block_by_old[unit['blockId']]
    bundle = build_dual_parser_evidence(runs, artifact_store=store)
    manifest['bundleSha256'] = bundle['bundleSha256']
    chosen = runs[1]
    manifest['assets'] = [{'unitId': 'table', 'runId': chosen['runId'],
        'artifactName': 'page' + str(page['physicalPageNo']) + '.png',
        'physicalPages': [page['physicalPageNo']], 'mediaType': 'image/png',
        'blockIds': [page['blocks'][1]['blockId']]} for page in chosen['pages']]
    return bundle, manifest, expected_bytes


class CanonicalSourceRegistryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = ArtifactStore(self.root / 'artifacts')
        self.registry = CanonicalSourceRegistry(self.root / 'registry', self.store)
        self.bundle, self.manifest, self.expected_bytes = asset_fixture(self.store)

    def tearDown(self):
        self.temporary.cleanup()

    def project(self):
        return project_canonical(self.bundle, self.manifest, artifact_store=self.store)

    def publish(self):
        snapshot = self.project()
        self.registry.publish(snapshot)
        return snapshot

    def test_publish_load_are_immutable_idempotent_and_detached(self):
        snapshot = self.project()
        receipt = self.registry.publish(snapshot)
        address = snapshot['projectionSha256']
        path = self.registry.root / (address + '.json')
        original_bytes = path.read_bytes()
        self.assertEqual(receipt, self.registry.publish(copy.deepcopy(snapshot)))
        self.assertEqual(snapshot, self.registry.load(address))
        self.assertTrue(receipt['immutable'])
        self.assertFalse(receipt['qualityVerifiedByRegistry'])
        loaded = self.registry.load(address)
        loaded['chunks'][0]['content'] = 'caller mutation'
        snapshot['chunks'][0]['content'] = 'caller changed original object'
        self.assertNotEqual('caller mutation', self.registry.load(address)['chunks'][0]['content'])
        self.assertNotEqual('caller changed original object', self.registry.load(address)['chunks'][0]['content'])
        self.assertEqual(original_bytes, path.read_bytes())
        with self.assertRaises(ValueError):
            self.registry.publish(snapshot)
        self.assertEqual(original_bytes, path.read_bytes())
        self.assertEqual([], list(self.registry.root.glob('.publish-*')))

    def test_vetting_advisory_share_only_explicitly_eligible_snapshot(self):
        snapshot = self.publish()
        address = snapshot['projectionSha256']
        self.assertEqual(self.registry.chunks(address, module='vetting'),
                         self.registry.chunks(address, module='advisory'))
        self.manifest['eligibleModules'] = ['vetting']
        private = self.publish()
        self.assertNotEqual(private['projectionSha256'], address)
        self.registry.chunks(private['projectionSha256'], module='vetting')
        with self.assertRaisesRegex(SourceRegistryError, 'different module'):
            self.registry.chunks(private['projectionSha256'], module='advisory')
        self.manifest.update(module='advisory', eligibleModules=['advisory'])
        advisory = self.publish()
        self.registry.chunks(advisory['projectionSha256'], module='advisory')
        with self.assertRaises(SourceRegistryError):
            self.registry.chunks(advisory['projectionSha256'], module='vetting')

    def test_drafting_template_does_not_share_with_review_modules(self):
        reviewed = self.publish()
        with self.assertRaises(SourceRegistryError):
            self.registry.chunks(reviewed['projectionSha256'], module='drafting')
        self.manifest.update(module='drafting', eligibleModules=['drafting'], sourcePurpose='standard_template')
        template = self.publish()
        self.registry.chunks(template['projectionSha256'], module='drafting')
        for module in ('vetting', 'advisory', 'unknown'):
            with self.subTest(module=module), self.assertRaises(SourceRegistryError):
                self.registry.chunks(template['projectionSha256'], module=module)
        ref = template['assetRefs'][0]
        with self.assertRaises(SourceRegistryError):
            self.registry.asset(template['projectionSha256'], ref['sourceSha256'], ref['assetId'], module='advisory')

    def test_assets_return_actual_bytes_for_exact_page_source_revision_and_eligible_module(self):
        snapshot = self.publish()
        for ref in snapshot['assetRefs']:
            with self.subTest(page=ref['physicalPages'][0]):
                raw, media, returned = self.registry.asset(snapshot['projectionSha256'],
                    snapshot['source']['sourceSha256'], ref['assetId'], module='advisory')
                self.assertEqual(self.expected_bytes[ref['artifactName']], raw)
                self.assertEqual('image/png', media)
                self.assertEqual(ref, returned)
                returned['physicalPages'][0] = 999
                self.assertEqual(ref, self.registry.asset(snapshot['projectionSha256'],
                    ref['sourceSha256'], ref['assetId'])[2])
        self.assertNotEqual(snapshot['assetRefs'][0]['sha256'], snapshot['assetRefs'][1]['sha256'])

    def test_wrong_source_missing_revision_and_unknown_asset_cannot_fall_back(self):
        snapshot = self.publish()
        address, source, asset = snapshot['projectionSha256'], snapshot['source']['sourceSha256'], snapshot['assetRefs'][0]['assetId']
        for request in ((address, 'a' * 64, asset), (address, source, 'not-a-bound-asset'),
                        ('f' * 64, source, asset)):
            with self.subTest(request=request), self.assertRaises(SourceRegistryError):
                self.registry.asset(*request)
        for bad_address in ('../snapshot', 'a' * 63, 'A' * 64):
            with self.assertRaises(SourceRegistryError):
                self.registry.load(bad_address)
        with self.assertRaises(SourceRegistryError):
            self.registry.asset(address, '../source', asset)

    def test_two_source_revisions_never_return_new_asset_for_old_reference(self):
        first = self.publish()
        self.bundle, self.manifest, _ = asset_fixture(self.store, source_bytes=SOURCE_BYTES + b'AUTHORED-source-revision-two')
        second = self.publish()
        self.assertNotEqual(first['projectionSha256'], second['projectionSha256'])
        old_asset, new_asset = first['assetRefs'][0], second['assetRefs'][0]
        self.assertNotEqual(old_asset['assetId'], new_asset['assetId'])
        with self.assertRaises(SourceRegistryError):
            self.registry.asset(second['projectionSha256'], second['source']['sourceSha256'], old_asset['assetId'])
        with self.assertRaises(SourceRegistryError):
            self.registry.asset(first['projectionSha256'], second['source']['sourceSha256'], old_asset['assetId'])
        raw, _, ref = self.registry.asset(first['projectionSha256'], first['source']['sourceSha256'], old_asset['assetId'])
        self.assertEqual(self.expected_bytes[old_asset['artifactName']], raw)
        self.assertEqual(old_asset, ref)

    def test_asset_tamper_or_missing_file_fails_after_valid_snapshot_load(self):
        for failure in ('tamper', 'missing'):
            with self.subTest(failure=failure):
                snapshot = self.publish()
                ref = snapshot['assetRefs'][0]
                path = self.store.root / ref['artifactDescriptor']['relativePath']
                expected = self.expected_bytes[ref['artifactName']]
                if failure == 'tamper':
                    path.write_bytes(b'x' * len(expected))
                else:
                    path.unlink()
                # Snapshot metadata can remain readable; asset bytes cannot be
                # called captured merely because their original hash remains.
                self.registry.load(snapshot['projectionSha256'])
                with self.assertRaises(ArtifactStoreError):
                    self.registry.asset(snapshot['projectionSha256'], ref['sourceSha256'], ref['assetId'])
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(expected)

    def test_declared_image_media_mismatch_and_unsupported_tiff_are_rejected(self):
        for media, code in (('image/jpeg', 'ASSET_MEDIA_MISMATCH'),
                            ('image/webp', 'ASSET_MEDIA_MISMATCH')):
            with self.subTest(media=media):
                for row in self.manifest['assets']:
                    row['mediaType'] = media
                snapshot = self.publish()
                ref = snapshot['assetRefs'][0]
                with self.assertRaises(SourceRegistryError) as caught:
                    self.registry.asset(snapshot['projectionSha256'], ref['sourceSha256'], ref['assetId'])
                self.assertEqual(code, caught.exception.code)
        for row in self.manifest['assets']:
            row['mediaType'] = 'image/tiff'
        with self.assertRaisesRegex(CanonicalProjectionError, 'unsupported_asset_media'):
            self.project()
        self.bundle, self.manifest, _ = asset_fixture(self.store, valid_images=False)
        snapshot = self.publish()
        ref = snapshot['assetRefs'][0]
        with self.assertRaises(SourceRegistryError) as caught:
            self.registry.asset(snapshot['projectionSha256'], ref['sourceSha256'], ref['assetId'])
        self.assertEqual('ASSET_MEDIA_MISMATCH', caught.exception.code)

    def test_snapshot_tamper_and_authentic_snapshot_at_wrong_address_are_rejected(self):
        first = self.publish()
        path = self.registry.root / (first['projectionSha256'] + '.json')
        original = path.read_bytes()
        tampered = copy.deepcopy(first)
        tampered['chunks'][0]['content'] = 'altered contract requirement'
        path.write_text(json.dumps(tampered), encoding='utf-8')
        with self.assertRaises(ValueError):
            self.registry.load(first['projectionSha256'])
        path.write_bytes(original)
        self.manifest['quality']['version'] = 'authored-quality-revision-two'
        second = self.project()
        path.write_text(json.dumps(second), encoding='utf-8')
        with self.assertRaises(SourceRegistryError) as caught:
            self.registry.load(first['projectionSha256'])
        self.assertEqual('SNAPSHOT_IDENTITY_MISMATCH', caught.exception.code)

    def test_missing_or_non_json_snapshot_is_unavailable(self):
        with self.assertRaises(SourceRegistryError) as caught:
            self.registry.load('f' * 64)
        self.assertEqual('SNAPSHOT_UNAVAILABLE', caught.exception.code)
        snapshot = self.publish()
        path = self.registry.root / (snapshot['projectionSha256'] + '.json')
        path.write_bytes(b'not JSON\xff')
        with self.assertRaises(SourceRegistryError) as caught:
            self.registry.load(snapshot['projectionSha256'])
        self.assertEqual('SNAPSHOT_UNAVAILABLE', caught.exception.code)


if __name__ == '__main__':
    unittest.main()
