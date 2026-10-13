"""Normal ASGI family routes with complete authored native-PDF/CAS proofs.

No production service, competition original, real GPU/model or TCP is used.
"""
import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from canonical_projection import _bytes
from native_pdf_source_family import SPEC_SCHEMA, compose_native_pdf_source_family
from native_pdf_source_family_server import ENDPOINT
from oer_source_registry import _serialize
from oer_unified_server import create_unified_app
from test_native_pdf_canonical_projection import fixture
import test_native_pdf_source_family as family_fixture
import native_pdf_source_family as family_producer
from test_token_windows import CharacterTokenizer
import test_oer_server as base_tests


class NativePdfSourceFamilyHttpTests(unittest.TestCase):
    tearDown = base_tests.OerHttpContractTest.tearDown

    def setUp(self):
        base_tests.OerHttpContractTest.setUp(self)
        self.client.close()
        self.app = create_unified_app(self.policy, backend=self.backend)
        self.client = TestClient(self.app)
        self.rt = self.backend._windows_runtime
        self.rt.tokenizer = lambda kind: CharacterTokenizer()
        self.rt.identity = lambda kind: {'kind': kind, 'tokenizerSha256': 'authored-contract'}
        self.store, self.registry = self.app.state.artifact_store, self.app.state.source_registry
        self.native, self.dual, self.original_manifest = fixture(self.store)
        self.original_manifest['scope']['scopePolicySha256'] = self.policy.policyIdentity['manifestSha256']
        self.children = [self.child(page) for page in (1, 2)]
        self.spec = {'schema': SPEC_SCHEMA, 'documentId': '9001',
            'originalSourceSha256': self.children[0]['source']['sourceSha256'],
            'scopePolicySha256': self.policy.policyIdentity['manifestSha256'],
            'components': [self.pin(child) for child in self.children]}
        self.spec_ref = self.store.put_bytes(_bytes(self.spec), identity_scope='canonical_json_object')
        self.body = {'sourceFamilySpecRef': self.spec_ref}
        self.expected = compose_native_pdf_source_family(self.spec, registry=self.registry,
            artifact_store=self.store, policy=self.policy)

    def child(self, page, **kwargs):
        return family_fixture.NativePdfSourceFamilyTest.child.__func__(self, page, **kwargs)

    def pin(self, child):
        return family_fixture.NativePdfSourceFamilyTest.pin.__func__(self, child)

    def publish(self, body=None, client=None):
        return (client or self.client).post(ENDPOINT, json=body or self.body)

    def published(self):
        response = self.publish()
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(self.expected, response.json()['projection'])
        return response

    def canonical(self, module='vetting'):
        return self.client.get('/canonical/' + self.expected['projectionSha256'] + '?module=' + module)

    def roots(self):
        return {path.name for path in self.registry.root.glob('*.json')}

    def test_health_declares_installed_typed_endpoint_and_unsupported_family_render(self):
        with patch.object(self.rt, 'tokenizer', side_effect=AssertionError('model forbidden')):
            response = self.client.get('/health')
        self.assertEqual(200, response.status_code)
        capability = response.json()['oer']['nativePdfSourceFamily']
        self.assertEqual(ENDPOINT, capability['projectEndpoint'])
        self.assertEqual('derived_same_original_component_set', capability['sourceRevisionKind'])
        self.assertEqual('actual-cas-family-spec-only', capability['projectionInputs'])
        self.assertEqual('unsupported', capability['sourcePageRenderStatus'])
        self.assertEqual({'schema', 'version', 'producerKind', 'sourceRevisionKind', 'componentContract',
            'maximumMessageBytes', 'crossComponentRelations', 'adoptedCoverage', 'wholeSourceAccepted',
            'semanticAcceptanceByComposition', 'projectEndpoint', 'projectionInputs', 'sourcePageRenderStatus'}, set(capability))
        self.assertFalse(capability['wholeSourceAccepted'])
        self.assertRegex(response.json()['retrieval']['sourceWindows']['algorithms']['native_pdf_source_family_server.py'], r'^[a-f0-9]{64}$')
        self.assertEqual([], self.encodes)
        self.assertEqual([], self.predictions)
        print('FAMILY_NORMAL_HTTP_CAPABILITY_PROOF=' + json.dumps(capability, sort_keys=True))

    def test_normal_publication_has_distinct_family_pins_and_preserves_complete_child_identity(self):
        result = self.published().json()
        identity = self.expected['projectionIdentity']
        self.assertEqual(identity['compositionSha256'], result['familyCompositionSha256'])
        self.assertEqual(identity['componentSetSha256'], result['familyComponentSetSha256'])
        self.assertEqual(identity['sourceRevisionSha256'], result['sourceRevisionSha256'])
        self.assertEqual(self.expected['projectionRevisionSha256'], result['projectionRevisionSha256'])
        self.assertNotIn('adoptionManifestSha256', result)
        self.assertNotIn('referenceReviewSha256', result)
        for child, parent in zip(self.children, result['projection']['chunks']):
            self.assertEqual(child['chunks'][0]['adoption'], parent['adoption'])
            self.assertEqual(child['chunks'][0]['parts'], parent['parts'])
            self.assertEqual(child['chunks'][0]['id'], parent['sourceFamilyOriginRef']['childChunkId'])
        self.assertFalse(result['qualityVerifiedByRegistry'])
        self.assertFalse(result['sourceQualityChangedByRegistry'])
        self.assertFalse(result['projection']['humanConfirmed'])
        self.assertEqual('unknown', result['projection']['wholeOriginalSourceCoverage'])
        self.assertEqual([], self.encodes)

    def test_complete_wire_shared_module_and_idempotent_publication_use_the_normal_frame(self):
        self.published()
        first = self.canonical()
        second = self.canonical('advisory')
        self.assertEqual((200, 200), (first.status_code, second.status_code))
        self.assertEqual(_serialize(self.expected), first.content)
        self.assertEqual(first.content, second.content)
        self.assertEqual(self.expected['projectionSha256'], first.headers['x-projection-sha256'])
        self.assertEqual(self.published().json()['projection'], self.expected)
        self.assertEqual(404, self.canonical('drafting').status_code)
        for suffix in ('/batches/0', '/envelopes/0'):
            self.assertEqual(404, self.client.get('/canonical/' + self.expected['projectionSha256'] + suffix).status_code)

    def test_fresh_normal_get_and_warm_normal_get_rehash_the_same_complete_actual_cas(self):
        self.published()
        fresh = create_unified_app(self.policy, backend=self.backend, artifact_store=self.store)
        original_read = self.store._read_uncached
        traces = []
        with TestClient(fresh) as client, patch.object(family_producer, 'validate_native_pdf_transport',
                wraps=family_producer.validate_native_pdf_transport) as child:
            counts = []
            for module in ('vetting', 'advisory'):
                trace = []
                def read(descriptor, *, retain):
                    trace.append((descriptor['sha256'], descriptor['byteCount'], retain))
                    return original_read(descriptor, retain=retain)
                with patch.object(self.store, '_read_uncached', side_effect=read):
                    response = client.get('/canonical/' + self.expected['projectionSha256'] + '?module=' + module)
                self.assertEqual(200, response.status_code, response.text)
                self.assertEqual(_serialize(self.expected), response.content)
                traces.append(trace)
                counts.append(child.call_count)
        self.assertEqual([2, 2], counts, 'Warm request must not reconstruct either complete child')
        self.assertEqual(set(traces[0]), set(traces[1]))
        for trace in traces:
            self.assertTrue(trace)
            entry = {(sha, size) for sha, size, retain in trace if retain}
            exit_reads = {(sha, size) for sha, size, retain in trace if not retain}
            self.assertEqual(entry, exit_reads)
        members = {(sha, size) for sha, size, _ in traces[0]}
        print('AUTHORED_FAMILY_NORMAL_HTTP_CAS_PROOF=' + json.dumps({
            'normalCanonicalGets': 2, 'coldChildValidations': counts[0],
            'warmNewChildValidations': counts[1] - counts[0],
            'completeResponseByteCount': len(_serialize(self.expected)),
            'completeResponseSha256': hashlib.sha256(_serialize(self.expected)).hexdigest(),
            'distinctCompleteCasMembers': len(members), 'distinctCompleteCasBytes': sum(size for _, size in members),
            'casReadHashCallsEach': [len(trace) for trace in traces], 'entryExitAndColdWarmMemberSetsExact': True,
            'competitionOriginalsRead': 0, 'qualityOrWholeSourcePromoted': False}, sort_keys=True))

    def test_outer_request_strict_json_extra_fields_and_inline_spec_are_rejected_before_publication(self):
        before = self.roots()
        for body in ({'sourceFamilySpecRef': self.spec_ref, 'adoptionManifestRef': {}},
                     {'sourceFamilySpecRef': self.spec}, {'sourceFamilySpecRef': {**self.spec_ref, 'url': 'https://invalid.example'}}):
            response = self.publish(body)
            self.assertEqual(422, response.status_code, response.text)
        raw = _serialize(self.body)[:-1] + b',"sourceFamilySpecRef":{}}'
        self.assertEqual(422, self.client.post(ENDPOINT, content=raw, headers={'Content-Type': 'application/json'}).status_code)
        self.assertEqual(before, self.roots())

    def test_actual_spec_duplicate_json_and_changed_descriptor_reject_without_legacy_fallback(self):
        before = self.roots()
        raw = _serialize(self.spec)[:-1] + b',"documentId":"9002"}'
        duplicate = self.store.put_bytes(raw)
        self.assertEqual(422, self.publish({'sourceFamilySpecRef': duplicate}).status_code)
        for field, value in (('sha256', 'f' * 64), ('byteCount', self.spec_ref['byteCount'] + 1), ('byteCount', True)):
            ref = {**self.spec_ref, field: value}
            self.assertEqual(422, self.publish({'sourceFamilySpecRef': ref}).status_code)
        self.assertEqual(before, self.roots())

    def test_child_public_pins_and_component_set_cannot_be_omitted_reordered_or_substituted(self):
        before = self.roots()
        invalid = []
        for field, value in (('projectionSha256', 'f' * 64), ('publicBytesSha256', 'f' * 64), ('publicByteCount', 1)):
            spec = copy.deepcopy(self.spec); spec['components'][0][field] = value; invalid.append(spec)
        spec = copy.deepcopy(self.spec); spec['components'] = spec['components'][:1]; invalid.append(spec)
        spec = copy.deepcopy(self.spec); spec['components'][1] = copy.deepcopy(spec['components'][0]); invalid.append(spec)
        for spec in invalid:
            ref = self.store.put_bytes(_bytes(spec))
            self.assertEqual(422, self.publish({'sourceFamilySpecRef': ref}).status_code)
        # An explicit different complete order is a different immutable family,
        # never a mutation of the old source identity.
        spec = copy.deepcopy(self.spec); spec['components'].reverse()
        ref = self.store.put_bytes(_bytes(spec))
        changed = self.publish({'sourceFamilySpecRef': ref})
        self.assertEqual(200, changed.status_code, changed.text)
        self.assertNotEqual(self.expected['projectionSha256'], changed.json()['projectionSha256'])
        self.assertNotEqual(self.expected['projectionIdentity']['componentSetSha256'], changed.json()['familyComponentSetSha256'])
        self.assertTrue(before < self.roots())

    def test_wrong_policy_original_or_empty_identity_is_rejected(self):
        before = self.roots()
        for field, value in (('scopePolicySha256', 'f' * 64), ('originalSourceSha256', 'f' * 64), ('documentId', '')):
            spec = copy.deepcopy(self.spec); spec[field] = value
            ref = self.store.put_bytes(_bytes(spec))
            self.assertEqual(422, self.publish({'sourceFamilySpecRef': ref}).status_code)
        self.assertEqual(before, self.roots())

    def test_standard_template_component_cannot_use_family_publication_to_enter_vetting(self):
        template = self.child(2, purpose='standard_template')
        spec = copy.deepcopy(self.spec)
        spec['components'][1] = self.pin(template)
        ref = self.store.put_bytes(_bytes(spec))
        before = self.roots()
        response = self.publish({'sourceFamilySpecRef': ref})
        self.assertEqual(422, response.status_code, response.text)
        self.assertEqual(before, self.roots())

    def test_normal_index_status_and_retrieve_keep_complete_family_parents(self):
        self.published()
        request = {'projectId': 'authored-family', 'projectionSha256': [self.expected['projectionSha256']], 'module': 'vetting'}
        indexed = self.client.post('/index-canonical', json=request)
        self.assertEqual(200, indexed.status_code, indexed.text)
        stored = self.backend._projects[self.backend.project_key('authored-family')]['chunks']
        self.assertEqual(self.expected['chunks'], stored)
        status = self.client.post('/index-canonical/status', json=request)
        self.assertEqual(200, status.status_code, status.text)
        self.assertTrue(status.json()['ready'])
        result = self.client.post('/retrieve', json={'projectId': 'authored-family', 'query': 'Visible', 'candidates': 10, 'limit': 10})
        self.assertEqual(200, result.status_code, result.text)
        expected = {chunk['id']: chunk for chunk in self.expected['chunks']}
        self.assertTrue(result.json()['hits'])
        for hit in result.json()['hits']:
            self.assertEqual(expected[hit['id']], hit['payload'])
        self.assertEqual(set(expected), {hit['id'] for hit in result.json()['hits']})

    def test_whole_response_cap_rejects_without_publishing_a_partial_family(self):
        before = self.roots()
        bounded = create_unified_app(self.policy, backend=self.backend, artifact_store=self.store, maximum_message_bytes=4096)
        with TestClient(bounded) as client:
            response = self.publish(client=client)
        self.assertEqual(422, response.status_code, response.text)
        self.assertNotIn('projection', response.json())
        self.assertEqual(before, self.roots())

    def test_family_revision_missing_or_retargeted_cannot_reach_normal_canonical_get(self):
        self.published()
        path = self.registry._family_revision_path(self.expected['projectionRevisionSha256'])
        raw = path.read_bytes()
        path.unlink()
        self.assertEqual(404, self.canonical().status_code)
        path.write_bytes(_serialize(self.children[0]))
        self.assertEqual(404, self.canonical().status_code)
        path.write_bytes(raw + b' ' * (16 * 1024 * 1024 + 1 - len(raw)))
        self.assertEqual(404, self.canonical().status_code)
        path.write_bytes(raw)
        self.assertEqual(200, self.canonical().status_code)

    def test_family_source_pages_publication_metadata_and_assets_are_explicitly_unsupported(self):
        self.published()
        path = '/canonical/' + self.expected['projectionSha256'] + '/sources/' + self.spec['originalSourceSha256'] + '/source-pages'
        query = '?module=vetting&evidence=' + 'e' * 64
        pages = self.registry.source_pages
        with patch.object(pages, 'publish', side_effect=AssertionError('legacy page publication forbidden')), \
             patch.object(pages, 'metadata', side_effect=AssertionError('legacy page metadata forbidden')), \
             patch.object(pages, 'asset', side_effect=AssertionError('legacy page asset forbidden')):
            responses = [self.client.post(path + query, json={'overlayRef': self.spec_ref}),
                         self.client.get(path + query), self.client.get(path + '/fake-page' + query)]
        for response in responses:
            self.assertEqual(409, response.status_code, response.text)
            self.assertEqual('SOURCE_FAMILY_RENDER_UNSUPPORTED', response.json()['detail']['kind'])
        self.assertEqual('not_published_by_source_family', self.canonical().json()['visualReturnStatus'])

    def test_spec_actual_bytes_changed_after_final_readback_fail_before_accepted_response(self):
        original_wire = self.registry.wire
        path = self.store.root / self.spec_ref['relativePath']
        raw = path.read_bytes()
        def changed(*args, **kwargs):
            result = original_wire(*args, **kwargs)
            path.write_bytes(raw + b' ')
            return result
        try:
            with patch.object(self.registry, 'wire', side_effect=changed):
                response = self.publish()
            self.assertEqual(422, response.status_code, response.text)
            self.assertNotIn('projection', response.json())
        finally:
            path.write_bytes(raw)

    def test_canonical_final_software_guard_drift_withholds_complete_family_response(self):
        self.published()
        identity = self.rt.algorithms()
        changed = {**identity, 'native_pdf_source_family_server.py': 'f' * 64}
        with patch.object(self.rt, 'algorithms', side_effect=[identity, changed]):
            response = self.canonical()
        self.assertEqual(404, response.status_code, response.text)
        self.assertNotIn('chunks', response.json())

    def test_ordinary_native_pdf_publication_and_exact_get_keep_old_receipt_semantics(self):
        body = {name: self.store.put_bytes(_bytes(value), identity_scope='canonical_json_object')
                for name, value in zip(('nativeBundleRef', 'dualBundleRef', 'adoptionManifestRef'),
                                      (self.native, self.dual, self.original_manifest))}
        response = self.client.post('/project/native-pdf', json=body)
        self.assertEqual(200, response.status_code, response.text)
        result = response.json()
        self.assertNotIn('familyCompositionSha256', result)
        self.assertNotIn('familyComponentSetSha256', result)
        wire = self.client.get('/canonical/' + result['projectionSha256'])
        self.assertEqual(200, wire.status_code, wire.text)
        self.assertEqual(_serialize(result['projection']), wire.content)
        self.assertEqual('native_pdf', result['projection']['producerKind'])
