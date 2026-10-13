"""Normal HTTP status over authored CAS/fake vectors; no live API or model."""
import copy, hashlib, json
from fastapi.testclient import TestClient
from oer_unified_server import create_unified_app
from test_source_page_index import SourcePageIndexHttpTests
from test_token_windows import CharacterTokenizer
from unittest.mock import patch

class CanonicalIndexStatusTests(SourcePageIndexHttpTests):
    def cold_restart(self):
        self.client.close();self.backend._projects={}
        self.app=create_unified_app(self.policy,backend=self.backend)
        self.rt=self.backend._windows_runtime
        self.rt.tokenizer=lambda kind:CharacterTokenizer()
        self.rt.identity=lambda kind:{'kind':kind,'tokenizerSha256':'AUTHORED-only'}
        self.client=TestClient(self.app)
    def status(self,**changes):
        body={'projectId':'AUTHORED-pins-index','module':'vetting','projectionSha256':[self.projection['projectionSha256']]}
        body.update(changes)
        return self.client.post('/index-canonical/status',json=body)
    def test_cold_status_restores_existing_exact_index_without_index_model_encode_or_db_write(self):
        indexed=self.index();self.assertEqual(200,indexed.status_code,indexed.text)
        path=self.backend.metadata_path('AUTHORED-pins-index')
        before=(path.read_bytes(),path.stat().st_mtime_ns,len(self.encodes),copy.deepcopy(self.db.collections))
        self.cold_restart()
        forbidden=lambda *a,**kw:(_ for _ in ()).throw(AssertionError('status invoked write/model/index'))
        self.backend.embedding_model=forbidden;self.backend.rerank_model=forbidden;self.backend.encode_embeddings=forbidden
        self.backend.index=forbidden;self.db.upsert=forbidden;self.db.create_collection=forbidden
        response=self.status();self.assertEqual(200,response.status_code,response.text)
        result=response.json();self.assertTrue(result['ready']);self.assertTrue(result['readOnly'])
        self.assertEqual('read-only-existing-canonical-index-v1',result['indexStatusContract'])
        self.assertEqual(indexed.json()['signature'],result['signature'])
        self.assertEqual(before[:3],(path.read_bytes(),path.stat().st_mtime_ns,len(self.encodes)))
        self.assertEqual(before[3],self.db.collections)
        self.assertNotIn('chunks',result);self.assertNotIn('windows',result)
        self.assertLess(len(response.content),16777216)

    def test_warm_status_detects_changed_actual_vector_and_clears_ready_without_write(self):
        indexed=self.index();self.assertEqual(200,indexed.status_code,indexed.text)
        saved=self.backend._projects['AUTHORED-pins-index']
        point=next(iter(self.db.collections[saved['collection']].values()));point.vector=[0.,1.]
        before=len(self.encodes);response=self.status()
        self.assertNotEqual(200,response.status_code,response.text)
        self.assertNotIn('AUTHORED-pins-index',self.backend._projects);self.assertEqual(before,len(self.encodes))
    def test_cold_status_rejects_metadata_and_actual_point_corruption_without_encode(self):
        cases=('missing_point','extra_payload','changed_vector','missing_seal','changed_seal','changed_parent','changed_project','changed_window','missing_collection','malformed_metadata')
        for case in cases:
            with self.subTest(case=case):
                fixture=CanonicalIndexStatusTests('test_cold_status_restores_existing_exact_index_without_index_model_encode_or_db_write');fixture.setUp()
                try:
                    self.assertEqual(200,fixture.index().status_code)
                    key='AUTHORED-pins-index';path=fixture.backend.metadata_path(key);saved=json.loads(path.read_text(encoding='utf-8'))
                    points=fixture.db.collections[saved['collection']];point=next(iter(points.values()))
                    if case=='missing_point':points.pop(next(iter(points)))
                    elif case=='extra_payload':point.payload['undeclared']='changed'
                    elif case=='changed_vector':point.vector=[0.,1.]
                    elif case=='missing_seal':saved.pop('vectorIntegrity')
                    elif case=='changed_seal':saved['vectorIntegrity']['pointsSha256']='0'*64
                    elif case=='changed_parent':saved['chunks'][0]['sourceHash']='0'*64
                    elif case=='changed_project':saved['projectId']='wrong-project'
                    elif case=='changed_window':saved['windows'][0]['content']+=' changed'
                    elif case=='missing_collection':fixture.db.collections.pop(saved['collection'])
                    if case=='malformed_metadata':path.write_text('{} trailing',encoding='utf-8')
                    else:path.write_text(json.dumps(saved),encoding='utf-8')
                    fixture.cold_restart();before=len(fixture.encodes)
                    response=fixture.status();self.assertNotEqual(200,response.status_code,(case,response.text))
                    self.assertNotIn(key,fixture.backend._projects);self.assertEqual(before,len(fixture.encodes))
                finally:fixture.tearDown()
    def test_missing_persisted_metadata_cannot_restore_from_warm_memory(self):
        self.assertEqual(200,self.index().status_code)
        self.backend.metadata_path('AUTHORED-pins-index').unlink()
        before=len(self.encodes);response=self.status()
        self.assertEqual(404,response.status_code,response.text);self.assertEqual(before,len(self.encodes))
        self.assertNotIn('AUTHORED-pins-index',self.backend._projects)
    def test_status_rejects_current_runtime_and_scope_drift_without_encode(self):
        self.assertEqual(200,self.index().status_code);before=len(self.encodes)
        self.rt.identity=lambda kind:{'kind':kind,'tokenizerSha256':'different-current-files'}
        response=self.status();self.assertNotEqual(200,response.status_code,response.text);self.assertEqual(before,len(self.encodes))
        self.rt.identity=lambda kind:{'kind':kind,'tokenizerSha256':'AUTHORED-only'}
        from dataclasses import replace
        self.policy=replace(self.policy,manifest_sha256='0'*64)
        self.cold_restart()
        response=self.status();self.assertNotEqual(200,response.status_code,response.text);self.assertEqual(before,len(self.encodes))
    def test_status_rejects_missing_sources_duplicate_projection_malformed_and_outside_pin(self):
        self.assertEqual(200,self.index().status_code);before=len(self.encodes)
        address=self.projection['projectionSha256']
        for changes in ({'module':'advisory'},{'projectionSha256':[address,address]},
                        {'projectionSha256':['0'*64]},{'projectionSha256':['bad']},
                        {'sourcePageEvidence':{'0'*64:'1'*64}},{'projectId':'x'*257},{'undeclared':'extra'}):
            response=self.status(**changes);self.assertNotEqual(200,response.status_code,(changes,response.text))
        body=json.dumps({'projectId':'AUTHORED-pins-index','projectionSha256':[address],'module':'vetting'})
        for raw in (body+' {}',body[:-1]+',"module":"vetting"}'):
            response=self.client.post('/index-canonical/status',content=raw,headers={'content-type':'application/json'})
            self.assertNotEqual(200,response.status_code,response.text)
        self.assertEqual(before,len(self.encodes))
    def test_status_rejects_source_cas_mutation_before_receipt(self):
        self.assertEqual(200,self.index().status_code);before=len(self.encodes)
        store=self.app.state.artifact_store
        descriptor=self.projection['nativeCaptureRefs']['sourcePackage']
        source=store.root/descriptor['relativePath'];original=source.read_bytes()
        source.write_bytes(original+b'changed')
        response=self.status();self.assertNotEqual(200,response.status_code,response.text)
        self.assertEqual(before,len(self.encodes));self.assertNotIn('AUTHORED-pins-index',self.backend._projects)
