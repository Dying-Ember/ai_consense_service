"""Real HTTP indexed-source seam; authored CAS/fake vectors, no neural forward.

Persistent Qdrant integration below uses only fresh owned storage and fake encode.
"""
import copy
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
import uuid

from qdrant_client import QdrantClient, models
from fastapi.testclient import TestClient
from oer_unified_server import create_unified_app
from test_source_page_index import SourcePageIndexHttpTests
from test_token_windows import CharacterTokenizer
from test_window_server import FakeDb
from index_integrity import inspect_points
from token_windows import digest, WindowError


class CachedVectorIntegrityTests(SourcePageIndexHttpTests):
    def cold_restart(self):
        self.client.close()
        self.backend._projects={}
        self.app=create_unified_app(self.policy,backend=self.backend)
        self.rt=self.backend._windows_runtime
        self.rt.tokenizer=lambda kind:CharacterTokenizer()
        self.rt.identity=lambda kind:{'kind':kind,'tokenizerSha256':'AUTHORED-only'}
        self.client=TestClient(self.app)

    def run_corruption(self, case):
        response=self.index()
        self.assertEqual(200,response.status_code,response.text)
        key='AUTHORED-pins-index'
        path=self.backend.metadata_path(key)
        saved=json.loads(path.read_text(encoding='utf-8'))
        points=self.db.collections[saved['collection']]
        p=next(iter(points.values()))
        before=len(self.encodes)
        if case=='missing_point':points.pop(next(iter(points)))
        elif case=='extra_point':
            extra=copy.deepcopy(p);extra.id=str(uuid.uuid4());points[str(extra.id)]=extra
        elif case=='empty_collection':points.clear()
        elif case=='missing_collection':self.db.collections.pop(saved['collection'])
        elif case=='wrong_id':
            points.pop(str(p.id));p.id=str(uuid.uuid4());points[str(p.id)]=p
        elif case=='wrong_payload':p.payload['indexSignature']='0'*64
        elif case=='extra_payload_field':p.payload['undeclared']='wrong'
        elif case=='changed_window_content':p.payload['content']+=' unrelated text'
        elif case=='changed_source_pin':p.payload['sourceHash']='0'*64
        elif case=='missing_payload':p.payload=None
        elif case=='nan_vector':p.vector=[float('nan'),0.]
        elif case=='infinite_vector':p.vector=[float('inf'),0.]
        elif case=='wrong_dimension':p.vector=[1.]
        elif case=='empty_vector':p.vector=[]
        elif case=='same_dimension_finite_changed_vector':p.vector=[0.,1.]
        elif case=='zero_vector':p.vector=[0.,0.]
        elif case=='missing_seal':saved.pop('vectorIntegrity',None)
        elif case=='seal_wrong_dimension':saved.setdefault('vectorIntegrity',{})['dimension']=1
        elif case=='seal_wrong_digest':saved.setdefault('vectorIntegrity',{})['pointsSha256']='0'*64
        elif case=='seal_wrong_signature':saved.setdefault('vectorIntegrity',{})['embeddingSignatureSha256']='0'*64
        elif case=='wrong_metadata_project':saved['projectId']='wrong-project'
        elif case=='wrong_metadata_collection':saved['collection']='wrong-collection'
        elif case=='wrong_metadata_parent_count':saved['parentCount']=999
        elif case=='wrong_metadata_chunks':saved['chunks'][0]['sourceHash']='0'*64
        elif case=='duplicate_metadata_window':saved['windows'].append(copy.deepcopy(saved['windows'][0]))
        else:raise AssertionError(case)
        path.write_text(json.dumps(saved),encoding='utf-8')
        self.cold_restart()
        response=self.index()
        self.assertNotEqual(200,response.status_code,(case,response.text))
        self.assertEqual(before,len(self.encodes),'Restore corruption must not rebuild/encode')
        self.assertNotIn(key,self.backend._projects,'Reject must not publish readiness')

    def test_normal_seal_is_written_and_restart_reuses_without_model_or_encode(self):
        response=self.index();self.assertEqual(200,response.status_code,response.text)
        saved=json.loads(self.backend.metadata_path('AUTHORED-pins-index').read_text(encoding='utf-8'))
        self.assertIn('vectorIntegrity',saved)
        before=len(self.encodes)
        self.cold_restart()
        self.backend.embedding_model=lambda:(_ for _ in ()).throw(AssertionError('model load during reuse'))
        response=self.index();self.assertEqual(200,response.status_code,response.text)
        self.assertTrue(response.json()['cached']);self.assertEqual(before,len(self.encodes))

    def test_warm_corruption_clears_previous_in_memory_ready_metadata(self):
        response=self.index();self.assertEqual(200,response.status_code,response.text)
        key='AUTHORED-pins-index';saved=self.backend._projects[key]
        self.db.collections[saved['collection']].clear()
        before=len(self.encodes)
        response=self.index();self.assertNotEqual(200,response.status_code)
        self.assertNotIn(key,self.backend._projects);self.assertEqual(before,len(self.encodes))

    def test_old_algorithm_identity_normally_reindexes_once_without_old_seal_upgrade(self):
        response=self.index();self.assertEqual(200,response.status_code,response.text)
        key='AUTHORED-pins-index';path=self.backend.metadata_path(key)
        saved=json.loads(path.read_text(encoding='utf-8'));old_collection=saved['collection']
        saved['signature']['algorithms']['window_runtime.py']='0'*64
        saved.pop('vectorIntegrity');path.write_text(json.dumps(saved),encoding='utf-8')
        oldpoints=copy.deepcopy(self.db.collections[old_collection]);before=len(self.encodes)
        self.cold_restart()
        response=self.index();self.assertEqual(200,response.status_code,response.text)
        self.assertFalse(response.json()['cached']);self.assertGreater(len(self.encodes),before)
        current=json.loads(path.read_text(encoding='utf-8'));self.assertIn('vectorIntegrity',current)
        self.assertEqual(oldpoints,self.db.collections[old_collection])
        after=len(self.encodes);response=self.index()
        self.assertTrue(response.json()['cached']);self.assertEqual(after,len(self.encodes))


for case in ('missing_point','extra_point','empty_collection','missing_collection','wrong_id',
             'wrong_payload','extra_payload_field','changed_window_content','changed_source_pin','missing_payload',
             'nan_vector','infinite_vector','wrong_dimension','empty_vector','same_dimension_finite_changed_vector',
             'zero_vector','missing_seal','seal_wrong_dimension','seal_wrong_digest','seal_wrong_signature',
             'wrong_metadata_project','wrong_metadata_collection','wrong_metadata_parent_count',
             'wrong_metadata_chunks','duplicate_metadata_window'):
    def test(self,case=case):self.run_corruption(case)
    setattr(CachedVectorIntegrityTests,'test_reject_'+case,test)


class ActualQdrantRestartTests(CachedVectorIntegrityTests):
    """Fresh isolated Qdrant Local persistence; no live path/real embeddings."""
    def test_real_qdrant_cold_reopen_reuses_all_persisted_points_without_encode(self):
        dbpath=self.root/'owned-qdrant'
        self.actual=QdrantClient(path=str(dbpath))
        self.backend.database=lambda:self.actual
        try:
            response=self.index();self.assertEqual(200,response.status_code,response.text)
            before=len(self.encodes)
            self.actual.close()
            self.actual=QdrantClient(path=str(dbpath))
            self.cold_restart()
            self.backend.embedding_model=lambda:(_ for _ in ()).throw(AssertionError('no model load'))
            response=self.index();self.assertEqual(200,response.status_code,response.text)
            self.assertTrue(response.json()['cached']);self.assertEqual(before,len(self.encodes))
        finally:self.actual.close()

    def test_real_qdrant_nontrivial_float32_vectors_both_warm_and_cold_reuse(self):
        import numpy as np
        vector=np.array([.12345678,.98765432],dtype=np.float32)
        vector/=np.linalg.norm(vector)
        def encode(texts):
            self.encodes.append(list(texts))
            return np.asarray([vector.copy() for _ in texts],dtype=np.float32)
        self.backend.encode_embeddings=encode
        self.test_real_qdrant_cold_reopen_reuses_all_persisted_points_without_encode()

    def test_real_qdrant_finite_changed_vector_after_cold_reopen_is_rejected(self):
        dbpath=self.root/'owned-qdrant'
        self.actual=QdrantClient(path=str(dbpath));self.backend.database=lambda:self.actual
        try:
            response=self.index();self.assertEqual(200,response.status_code,response.text)
            saved=self.backend._projects['AUTHORED-pins-index'];collection=saved['collection']
            point=self.actual.scroll(collection,limit=1,with_payload=True,with_vectors=True)[0][0]
            before=len(self.encodes)
            self.actual.upsert(collection,points=[models.PointStruct(id=point.id,payload=point.payload,vector=[0.,1.])])
            self.actual.close();self.actual=QdrantClient(path=str(dbpath));self.cold_restart()
            response=self.index();self.assertNotEqual(200,response.status_code,response.text)
            self.assertEqual('INDEX_VECTOR_INTEGRITY',response.json()['detail']['kind'])
            self.assertEqual(before,len(self.encodes))
        finally:self.actual.close()


class CompletePointPagingTests(unittest.TestCase):
    def setUp(self):
        self.db=FakeDb();self.db.create_collection('owned',vectors_config=models.VectorParams(size=2,distance=models.Distance.COSINE))
        self.signature={'AUTHORED-only':'signature'}
        self.windows=[{'id':str(uuid.UUID(int=i+1)),'content':str(i),'sourceHash':'AUTHORED-source'} for i in range(600)]
        self.db.upsert('owned',points=[models.PointStruct(id=w['id'],payload={**w,'indexSignature':digest(self.signature)},vector=[1.,0.]) for w in self.windows])

    def inspect(self):return inspect_points(self.db,'owned',self.windows,self.signature,2)

    def test_reads_complete_three_page_denominator(self):self.assertEqual(600,self.inspect()['pointCount'])

    def test_second_page_changed_payload_rejected(self):
        self.db.collections['owned'][self.windows[300]['id']].payload['content']='wrong'
        with self.assertRaisesRegex(WindowError,'payload'):self.inspect()

    def test_repeated_cursor_rejected(self):
        original=self.db.scroll
        self.db.scroll=lambda *a,**kw:(original(*a,**{**kw,'offset':None})[0],self.windows[255]['id'])
        with self.assertRaises(WindowError):self.inspect()

    def test_count_changed_after_last_read_rejected(self):
        original=self.db.count;calls=[]
        def count(*a,**kw):
            calls.append(1);return SimpleNamespace(count=600 if len(calls)==1 else 601)
        self.db.count=count
        with self.assertRaises(WindowError):self.inspect()

    def test_wrong_collection_schema_rejected(self):
        self.db.vector_params['owned']=models.VectorParams(size=2,distance=models.Distance.DOT)
        with self.assertRaises(WindowError):self.inspect()
