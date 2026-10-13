"""Small authored route contracts, with explicit proof boundary substitution.

These fixtures test immutable publication and HTTP identity enforcement. They
do not certify a real PDF or replace the separate actual-CAS packet replay.
No model, GPU, listener, user database or index is used.
"""
import copy
import hashlib
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from artifact_store import ArtifactStore
from native_collection_registry import NativeCollectionSourceRegistry
from oer_source_registry import _serialize
from source_page_registry import SourcePageRenderRegistry
from source_page_server import install_source_page_routes
from test_oer_source_registry import PNG


class SourcePageRouteTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = ArtifactStore(self.root / 'cas')
        self.source_ref = self.store.put_bytes(b'AUTHORED actual source bytes')
        self.image_ref = self.store.put_bytes(PNG)
        self.projection_sha, self.evidence, self.policy_sha = 'a' * 64, 'c' * 64, 'd' * 64
        self.source_sha = self.source_ref['sha256']
        self.projection = {'schema': 'consense.canonical-projection.v1',
            'projectionSha256': self.projection_sha,
            'projectionIdentity': {'module': 'vetting', 'eligibleModules': ['vetting', 'advisory']},
            'source': {'sourceSha256': self.source_sha, 'sourceRevisionSha256': 'b' * 64},
            'manifest': {'scope': {'scopePolicySha256': self.policy_sha}},
            'chunks': [{'eligibleModules': ['vetting', 'advisory']}],
            'canonical': {'units': [{'unitId': 'AUTHORED-table', 'physicalPages': [1],
                'parts': [{'blockId': 'AUTHORED-block', 'cells': [{'cellId': 'AUTHORED-cell'}]}]}],
                'relations': []}, 'captureCompleteness': {'complete': False},
            'unknowns': ['AUTHORED unresolved quality'], 'limitations': ['AUTHORED route contract only']}
        self.handle = {'assetId': 'source-render:' + 'f' * 64, 'kind': 'source_page_render',
            'sourceSha256': self.source_sha, 'sourceRevisionSha256': 'b' * 64,
            'combinedEvidenceIdentitySha256': self.evidence, 'physicalPageNo': 1,
            'widthPixels': 1, 'heightPixels': 1, 'byteCount': len(PNG), 'mediaType': 'image/png',
            'sha256': self.image_ref['sha256'], 'artifactDescriptor': self.image_ref,
            'qualityAccepted': False, 'unitId': 'AUTHORED-table', 'blockIds': ['AUTHORED-block'],
            'imageRegions': [{'blockId': 'AUTHORED-block', 'bboxOriginalNormalized': [0, 0, 1, 1],
                             'bboxConvention': 'normalized_xyxy_original_page'}]}
        self.overlay = {'combinedEvidenceIdentitySha256': self.evidence,
            'scopePolicySha256': self.policy_sha, 'overlaySha256': 'e' * 64}
        self.overlay_ref = self.store.put_bytes(_serialize(self.overlay))
        self.canonical = SimpleNamespace(load=lambda address: copy.deepcopy(self.projection),
                                        _module=NativeCollectionSourceRegistry._module)
        self.pages = SourcePageRenderRegistry(self.root / 'pages', self.canonical, self.store)
        self.canonical.source_pages = self.pages
        def authored_proof_boundary(overlay, projection, store):
            store.read_asset(self.source_ref)
            store.read_asset(self.image_ref)
            return [copy.deepcopy(self.handle)]
        self.proof_boundary = patch('source_page_registry.validate_source_render_overlay',
                                    side_effect=authored_proof_boundary)
        self.scope_boundary = patch('source_page_server.assert_canonical_scope')
        self.proof_boundary.start()
        self.scope_boundary.start()
        self.app = FastAPI()
        install_source_page_routes(self.app, self.canonical,
            SimpleNamespace(policyIdentity={'manifestSha256': self.policy_sha}), target_module='vetting')
        self.client = TestClient(self.app)
        self.path = '/canonical/' + self.projection_sha + '/sources/' + self.source_sha + '/source-pages'
        self.query = '?module=advisory&evidence=' + self.evidence

    def tearDown(self):
        self.client.close()
        self.proof_boundary.stop()
        self.scope_boundary.stop()
        self.temp.cleanup()

    def publish(self, body=None, query=None):
        return self.client.post(self.path + (self.query if query is None else query),
            json={'overlayRef': self.overlay_ref} if body is None else body)

    def test_explicit_admission_idempotent_metadata_and_png_headers(self):
        first = self.publish()
        self.assertEqual(200, first.status_code, first.text)
        path = self.pages._path(self.projection_sha, self.evidence)
        original = path.read_bytes()
        self.assertEqual(first.json(), self.publish().json())
        self.assertEqual(original, path.read_bytes())
        response = self.client.get(self.path + self.query)
        self.assertEqual(200, response.status_code, response.text)
        data = response.json()
        self.assertIs(False, data['qualityAccepted'])
        self.assertIs(False, data['wholeSourceAccepted'])
        self.assertEqual(self.projection['unknowns'], data['unknowns'])
        self.assertEqual([1], data['sourcePageRenderRefs'][0]['physicalPages'])
        image = self.client.get(self.path + '/' + self.handle['assetId'] + self.query)
        self.assertEqual(200, image.status_code, image.text)
        self.assertEqual(PNG, image.content)
        for response in (first, response, image):
            self.assertEqual('b' * 64, response.headers['x-source-revision-sha256'])
            self.assertEqual(self.evidence, response.headers['x-evidence-identity-sha256'])
            self.assertEqual(self.projection_sha, response.headers['x-projection-sha256'])
            self.assertEqual(self.source_sha, response.headers['x-source-sha256'])
        self.assertEqual('"' + hashlib.sha256(PNG).hexdigest() + '"', image.headers['etag'])

    def test_missing_wrong_duplicate_or_extra_query_pins_reject_without_downgrade(self):
        self.assertEqual(200, self.publish().status_code)
        for query in ('?module=advisory', '?evidence=' + self.evidence,
                self.query + '&module=advisory', self.query + '&unexpected=1',
                '?module=drafting&evidence=' + self.evidence,
                '?module=advisory&evidence=' + '9' * 64):
            with self.subTest(query=query):
                self.assertEqual(409, self.client.get(self.path + query).status_code)
                self.assertEqual(409, self.client.get(self.path + '/' + self.handle['assetId'] + query).status_code)

    def test_unregistered_composite_unknown_page_and_wrong_source_reject(self):
        self.assertEqual(409, self.client.get(self.path + self.query).status_code)
        self.assertEqual(200, self.publish().status_code)
        self.assertEqual(409, self.client.get(self.path + '/source-render:' + '0' * 64 + self.query).status_code)
        self.assertEqual(409, self.client.get(self.path.replace(self.source_sha, '1' * 64) + self.query).status_code)

    def test_sidecar_body_strict_json_exact_descriptor_and_immutable_conflict(self):
        for body in ({'overlayRef': self.overlay_ref, 'extra': 1}, {'overlay': self.overlay},
                {'overlayRef': {**self.overlay_ref, 'extra': 1}}):
            self.assertEqual(409, self.publish(body).status_code)
        raw = b'{"overlayRef":null,"overlayRef":' + _serialize(self.overlay_ref) + b'}'
        response = self.client.post(self.path + self.query, content=raw, headers={'Content-Type': 'application/json'})
        self.assertEqual(409, response.status_code)
        self.assertEqual(200, self.publish().status_code)
        other = self.store.put_bytes(_serialize({**self.overlay, 'differentExactBytes': True}))
        self.assertEqual(409, self.publish({'overlayRef': other}).status_code)

    def test_same_size_source_mutation_with_restored_mtime_invalidates_prior_read(self):
        self.assertEqual(200, self.publish().status_code)
        self.assertEqual(200, self.client.get(self.path + self.query).status_code)
        source = self.store.root / self.source_ref['relativePath']
        stat, raw = source.stat(), source.read_bytes()
        source.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
        os.utime(source, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        self.assertEqual(409, self.client.get(self.path + self.query).status_code)
        self.assertEqual(409, self.client.get(self.path + '/' + self.handle['assetId'] + self.query).status_code)

    def test_image_drift_rejects_and_no_bad_registration_is_created(self):
        image = self.store.root / self.image_ref['relativePath']
        image.write_bytes(b'x' * len(PNG))
        self.assertEqual(409, self.publish().status_code)
        self.assertFalse(self.pages._path(self.projection_sha, self.evidence).exists())

    def test_native_projection_policy_module_and_boolean_dimension_corruption_reject(self):
        for field, value in (('physicalPageNo', True), ('widthPixels', 0), ('heightPixels', 1.0),
                             ('qualityAccepted', 0), ('sourceRevisionSha256', '7' * 64)):
            previous = self.handle[field]
            self.handle[field] = value
            with self.subTest(field=field):
                self.assertEqual(409, self.publish().status_code)
            self.handle[field] = previous
        previous = self.projection['schema']
        self.projection['schema'] = 'consense.native-canonical-projection.v1'
        self.assertEqual(409, self.publish().status_code)
        self.projection['schema'] = previous
        self.projection['manifest']['scope']['scopePolicySha256'] = '7' * 64
        self.assertEqual(409, self.publish().status_code)

    def test_relation_endpoint_must_belong_to_its_exact_part(self):
        self.projection['canonical']['relations'] = [{'fromUnitId': 'AUTHORED-table',
            'toUnitId': 'AUTHORED-table', 'fromBlockId': 'AUTHORED-other', 'toBlockId': 'AUTHORED-block',
            'fromCellId': 'AUTHORED-cell', 'toCellId': 'AUTHORED-cell'}]
        self.assertEqual(409, self.publish().status_code)


if __name__ == '__main__':
    unittest.main()
