"""Fake inference/vector providers only; zero real model/index/query/DB calls."""
import copy
import importlib.util
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
import uuid
import numpy as np
from test_token_windows import CharacterTokenizer,parent
from token_windows import WindowError,windows,WindowRecipe
from window_runtime import WindowRuntime,CONTRACT

path=Path(__file__).with_name('server.py')
spec=importlib.util.spec_from_file_location('isolated_window_server_test',path);s=importlib.util.module_from_spec(spec);sys.modules[spec.name]=s;spec.loader.exec_module(s)


class FakeDb:
    def __init__(self):self.collections={};self.fetch_limits=[]
    def collection_exists(self,name):return name in self.collections
    def create_collection(self,name,**kwargs):self.collections[name]={}
    def upsert(self,name,points):
        for p in points:self.collections[name][str(p.id)]=p
    def count(self,name,**kwargs):return SimpleNamespace(count=len(self.collections[name]))
    def query_points(self,name,query,limit,query_filter=None):
        self.fetch_limits.append(limit)
        role=query_filter.must[0].match.value if query_filter else None
        selected=[p for p in self.collections[name].values()if role is None or p.payload['role']==role]
        selected=sorted(selected,key=lambda p:(-len(p.payload['content']),str(p.id)))[:limit]
        return SimpleNamespace(points=[SimpleNamespace(id=p.id,payload=copy.deepcopy(p.payload),score=len(p.payload['content'])/1000)for p in selected])


class FakeModel:
    def get_sentence_embedding_dimension(self):return 2
    def predict(self,pairs,**kwargs):return np.asarray([len(p[1])/1000 for p in pairs])


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.db=FakeDb();self.events=[];self.encodes=[];s.STATE=Path(self.tmp.name);s._projects={};s._selected_device='cpu';s.MODEL_DTYPE='float32'
        self.rt=WindowRuntime(s);self.rt.overlap=2
        self.rt.tokenizer=lambda kind:CharacterTokenizer();self.rt.identity=lambda kind:{'kind':kind,'tokenizerSha256':'test'}
        self.rt.algorithms=lambda:{'all':'mock source identity'}
        s._windows_runtime=self.rt;s._window_observer=lambda stage,payload:self.events.append((stage,payload));s.database=lambda:self.db;s.embedding_model=lambda:FakeModel();s.rerank_model=lambda:FakeModel()
        def encode(texts):self.encodes.append(list(texts));return np.asarray([[1.,0.]for _ in texts],dtype=np.float32)
        s.encode_embeddings=encode
        self.old_settings=copy.deepcopy(s.MODEL_SETTINGS);s.MODEL_SETTINGS={'embedding':{'maxTokens':10,'batchSize':4},'reranker':{'maxTokens':10,'batchSize':4}}
        self.p=parent('abcdefghijklmnop',str(uuid.uuid4()),source_hash='a'*64)
    def tearDown(self):s.MODEL_SETTINGS=self.old_settings;self.tmp.cleanup()
    def index(self,chunks=None,project='one'):return s.index(s.IndexRequest(projectId=project,chunks=[s.Chunk.model_validate(c)for c in(chunks or[self.p])]))
    def query(self,project='one',**kwargs):return s.retrieve(s.RetrieveRequest(projectId=project,query='q',limit=1,candidates=2,**kwargs))
    def test_index_points_are_windows_not_original_parents(self):
        r=self.index();self.assertGreater(r['vectorPoints'],r['indexed']);self.assertEqual(r['indexed'],1);self.assertEqual(r['contract'],CONTRACT)
    def test_query_returns_original_parent_id_content_cells(self):
        self.index();result=self.query();hit=result['hits'][0]
        self.assertEqual(hit['id'],self.p['id']);self.assertEqual(hit['payload']['content'],self.p['content']);self.assertEqual(hit['payload']['cells'],['unaltered'])
        self.assertNotIn('parentId',hit['payload'])
    def test_window_payload_and_manifest_are_bound(self):
        self.index();metadata=s.read_project('one');self.rt.validate_metadata(metadata)
        c=self.db.collections[metadata['collection']]
        # Tamper every mock point so the fetched score-ordered prefix necessarily
        # reaches a changed payload; a point outside that prefix is unobserved.
        for point in c.values():point.payload['content']='wrong'
        with self.assertRaises(s.HTTPException)as e:self.query()
        self.assertEqual(e.exception.detail['kind'],'DENSE_POINT_PAYLOAD_MISMATCH')
    def test_missing_window_point_fails_instead_of_complete_topk(self):
        self.index();metadata=s.read_project('one');c=self.db.collections[metadata['collection']];c.clear()
        with self.assertRaises(s.HTTPException)as e:self.query()
        self.assertEqual(e.exception.detail['kind'],'DENSE_WINDOW_SCOPE_INCOMPLETE')
    def test_old_signature_rejected(self):
        self.index();metadata=s._projects['one'];metadata['signature']['signatureVersion']=2
        with self.assertRaises(s.HTTPException)as e:s.read_project('one')
        self.assertEqual(e.exception.status_code,409)
    def test_add_remove_change_invalidates_full_corpus_signature(self):
        p2=parent('中文表格😀xyz',str(uuid.uuid4()),source_hash='b'*64)
        a=self.rt.signature([self.p]);b=self.rt.signature([self.p,p2]);c=self.rt.signature([p2]);changed=copy.deepcopy(self.p);changed['sourceHash']='c'*64
        self.assertNotEqual(a,b);self.assertNotEqual(b,c);self.assertNotEqual(a,self.rt.signature([changed]))
    def test_changed_role_invalidates_signature(self):
        changed=copy.deepcopy(self.p);changed['role']='standard';self.assertNotEqual(self.rt.signature([self.p]),self.rt.signature([changed]))
    def test_changed_recipe_invalidates_signature(self):
        a=self.rt.signature([self.p]);self.rt.overlap=1;self.assertNotEqual(a,self.rt.signature([self.p]))
    def test_changed_dtype_invalidates_signature(self):
        a=self.rt.signature([self.p]);s.MODEL_DTYPE='float16';self.assertNotEqual(a,self.rt.signature([self.p]));s.MODEL_DTYPE='float32'
    def test_fixed_cache_portable_identity_excludes_loader_location_only(self):
        self.rt.identity=lambda kind:{'resolvedSnapshot':'C:/local','files':[{'sha256':'abc'}],'backendSha256':'tokenizer'}
        a=self.rt.embedding_cache_identity(2)
        self.rt.identity=lambda kind:{'resolvedSnapshot':'/h800/models','files':[{'sha256':'abc'}],'backendSha256':'tokenizer'}
        self.assertEqual(a,self.rt.embedding_cache_identity(2))
        self.rt.identity=lambda kind:{'resolvedSnapshot':'/h800/models','files':[{'sha256':'changed'}],'backendSha256':'tokenizer'}
        self.assertNotEqual(a,self.rt.embedding_cache_identity(2))
    def test_project_collections_isolate_same_parent_id(self):
        self.index(project='one');self.index(project='two');self.assertNotEqual(s._projects['one']['collection'],s._projects['two']['collection'])
    def test_unchanged_index_cache_exposes_real_point_count(self):
        first=self.index();n=len(self.encodes);second=self.index();self.assertTrue(second['cached']);self.assertEqual(second['vectorPoints'],first['vectorPoints']);self.assertEqual(n,len(self.encodes))
    def test_new_project_does_not_reuse_old_parent_vector_index(self):
        self.index(project='one');n=len(self.encodes);self.index(project='two');self.assertGreater(len(self.encodes),n)
    def test_long_query_fails_before_embedding(self):
        self.index();n=len(self.encodes)
        with self.assertRaises(s.HTTPException)as e:s.retrieve(s.RetrieveRequest(projectId='one',query='abcdefghijklmnopqrstuvwxyz',limit=1,candidates=2))
        self.assertEqual(e.exception.detail['kind'],'QUERY_TOKEN_BUDGET_EXHAUSTED');self.assertEqual(n,len(self.encodes))
    def test_parent_rerank_score_order_is_explicit(self):
        self.index();self.query();stage=next(p for stage,p in self.events if stage=='rerank-window-scores')
        self.assertEqual(len(stage['pairsInActualInputOrder']),len(stage['scoresInActualPairOrder']));self.assertEqual(stage['parentsInTrueScoreOrder'][0]['id'],self.p['id'])
    def test_role_filter_isolates_parent_and_window(self):
        p2=parent('其他standardtext',str(uuid.uuid4()),role='standard',source_hash='b'*64);self.index([self.p,p2]);r=self.query(role='standard');self.assertEqual(r['hits'][0]['id'],p2['id'])
    def test_window_manifest_tamper_fails(self):
        self.index();metadata=copy.deepcopy(s._projects['one']);metadata['windows'][0]['parentId']='other'
        with self.assertRaises(WindowError):self.rt.validate_metadata(metadata)
    def test_empty_scope_no_model_call(self):
        self.index();n=len(self.encodes);r=self.query(role='standard');self.assertEqual(r['hits'],[]);self.assertEqual(n,len(self.encodes))
    def test_original_native_part_payload_is_retained(self):
        self.p['parts'][0]['cells']=[{'column':1,'text':'中文😀'}];self.index();r=self.query();self.assertEqual(r['hits'][0]['payload']['parts'],self.p['parts'])


if __name__=='__main__':unittest.main()
