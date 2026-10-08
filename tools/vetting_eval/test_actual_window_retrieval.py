"""Synthetic file/DTO/runtime guards only, zero real inference/vector clients."""
import copy
from pathlib import Path
import json
import os
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import numpy as np
import actual_window_retrieval as d
import fresh_client_readback as r

ROOT=Path(r'C:\Coding\ConSense\tmp\vetting_foundation_hardening\token-windows-20261003T031738809608Z')


class PrepareGuards(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.parent={'id':'p','documentId':1,'role':'tender','content':'Generic source text.','sourceHash':'a'*64,'parts':[{'blockId':'body:1:paragraph','anchor':'body/1','text':'Generic source text.','startOffset':0,'endOffset':20}]}
        self.raw=self.root/'parents.jsonl';self.raw.write_text(json.dumps(self.parent)+'\n');self.cm=self.root/'corpus.json';d.write(self.cm,{'corpus':d.desc(self.raw),'chunkCount':1})
        self.q=self.root/'queries.json';d.write(self.q,{'requests':[{'originalTopicOrdinal':6,'request':{'projectId':'historical','query':'generic contract terms','limit':10,'candidates':50}}]})
        self.embed=self.root/'5617a9f61b028005a4858fdac845db406aefb181';self.rank=self.root/'953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e'
        for p in(self.embed,self.rank):p.mkdir();(p/'pytorch_model.bin').write_bytes(b'mock weights');(p/'tokenizer.json').write_text('mock tokenizer')
        self.args=SimpleNamespace(window_tooling=str(ROOT/'tooling_manifest.json'),window_tooling_sha256=d.TOOLING_SHA,corpus_manifest=str(self.cm),corpus_manifest_sha256=d.desc(self.cm)['sha256'],
            generic_query_plan=str(self.q),generic_query_plan_sha256=d.desc(self.q)['sha256'],embedding_snapshot=str(self.embed),rerank_snapshot=str(self.rank),project_id='new-project',workspace=r'C:\Coding\ConSense',
            out=str(self.root/'prepared'),candidates=50,limit=10,coverage_profile=True,signature_suite=False,fixed_cache_whitelist=None,fixed_cache_whitelist_sha256=None)
    def tearDown(self):self.tmp.cleanup()
    def prepared(self):
        d.prepare(self.args);plan=Path(self.args.out)/'approved_plan.json';return SimpleNamespace(plan=str(plan),plan_sha256=d.desc(plan)['sha256'])
    def test_prepare_has_zero_inference_and_explicit_profiles(self):
        args=self.prepared();plan=d.json_read(d.desc(args.plan));self.assertEqual(plan['expectedMainQueryCalls'],2);self.assertEqual(plan['actualCallsThisPrepare']['encode'],0)
        self.assertEqual([r['request']['candidates']for r in plan['requests']],[50,100]);self.assertTrue(all(r['request']['query']=='generic contract terms'for r in plan['requests']))
        self.assertEqual(d.validate_plan(args)[0]['parentCount'],1)
    def test_output_refuses_overwrite(self):
        self.prepared()
        with self.assertRaises(ValueError):d.prepare(self.args)
    def test_changed_corpus_external_sha_rejected(self):
        self.args.corpus_manifest_sha256='b'*64
        with self.assertRaises(ValueError):d.prepare(self.args)
        self.assertFalse(Path(self.args.out).exists())
    def test_changed_local_weight_rejected_before_run(self):
        args=self.prepared();(self.embed/'pytorch_model.bin').write_bytes(b'changed')
        with self.assertRaises(ValueError):d.validate_plan(args)
    def test_changed_driver_binding_rejected(self):
        args=self.prepared();plan=json.loads(Path(args.plan).read_text());plan['software']['sha256']='b'*64;Path(args.plan).write_text(json.dumps(plan));args.plan_sha256=d.desc(args.plan)['sha256']
        with self.assertRaises(ValueError):d.validate_plan(args)
    def test_model_revision_path_mismatch_rejected(self):
        self.args.embedding_snapshot=str(self.rank)
        with self.assertRaises(ValueError):d.prepare(self.args)
    def test_no_gold_can_enter_query(self):
        rows=[{'originalTopicOrdinal':1,'request':{'query':'generic','expected':'fixture answer'}}]
        with self.assertRaises(ValueError):d.normalize_requests(rows,'new',50,10)
    def test_flattened_wrapper_rejected(self):
        with self.assertRaises(ValueError):d.normalize_requests([{'originalTopicOrdinal':1,'query':'generic'}],'new',50,10)
    def test_query_limits_explicitly_validate(self):
        with self.assertRaises(ValueError):d.normalize_requests([{'originalTopicOrdinal':1,'request':{'query':'generic'}}],'new',10,20)
    def test_empty_queries_only_fixed_build(self):
        self.assertEqual(d.profile_requests([],'fixed',[{'id':'embed-only','candidates':50,'limit':10}],allow_empty=True),[])
        with self.assertRaises(ValueError):d.profile_requests([],'variable',[{'id':'query','candidates':50,'limit':10}])
    def test_duplicate_profile_ids_rejected(self):
        with self.assertRaises(ValueError):d.profile_requests([],'x',[{'id':'a','candidates':50,'limit':10}]*2,allow_empty=True)
    def test_unstopped_fresh_client_fails_before_copy_or_constructor(self):
        with self.assertRaises(ValueError):r.audit(SimpleNamespace(producer_exited=False))
    def test_encoded_artifacts_shape_and_scope_binding(self):
        path=self.root/'vector.npy'
        with path.open('wb')as stream:np.save(stream,np.asarray([[1.,0.]],dtype=np.float32),allow_pickle=False)
        result={'actualEncodedFloat32Groups':[{'scope':{'scope':'main'},'vectors':d.desc(path),'shape':[1,2],'windowIds':['w']}]}
        self.assertTrue(np.array_equal(r.expected_vectors(result)['w'],np.asarray([1.,0.],dtype=np.float32)))
        result['actualEncodedFloat32Groups'][0]['shape']=[1,3]
        with self.assertRaises(ValueError):r.expected_vectors(result)
    def test_byte_exact_copy_content_identity_independent_of_path(self):
        a=self.root/'a';b=self.root/'b';a.mkdir();b.mkdir();(a/'point').write_bytes(b'abc');(b/'point').write_bytes(b'abc')
        self.assertEqual(r.content_tree(r.tree(a)),r.content_tree(r.tree(b)))


class MathGuards(unittest.TestCase):
    def test_cuda_unavailable_never_falls_back(self):
        import sys
        with patch.dict(sys.modules,{'torch':SimpleNamespace(cuda=SimpleNamespace(is_available=lambda:False))}):
            with self.assertRaises(ValueError):d.set_math()
    def test_math_is_explicit_not_driver_defaults(self):
        self.assertEqual(d.MATH['float32MatmulPrecision'],'highest');self.assertFalse(d.MATH['cudaAllowTf32']);self.assertFalse(d.MATH['cudnnAllowTf32'])
        self.assertEqual(d.ENV['CONSENSE_MODEL_DTYPE'],'float32');self.assertEqual(d.ENV['HF_HUB_OFFLINE'],'1');self.assertEqual(d.ENV['TRANSFORMERS_OFFLINE'],'1')


class MockRunGuards(PrepareGuards):
    def fake_server(self,bad_payload=False,float64=False):
        s=SimpleNamespace();s.STATE=self.root/'actual/state';s.MODELS={'embedding':{'name':'embedding'},'reranker':{'name':'reranker'}}
        s._embedding=s._reranker=None;s._database=SimpleNamespace(close=lambda:None);s._window_observer=None
        s.model_runtime=lambda k:{'dtype':'float32','device':'cuda'};runtime=SimpleNamespace(embedding_cache_identity=lambda dim:{'runtimeDtype':'float32'})
        s.window_runtime=lambda:runtime;s.verify_loaded_model=lambda m,k:None;s.project_key=str;s.metadata_path=lambda k:s.STATE/'projects'/f'{k}.json'
        s.Chunk=SimpleNamespace(model_validate=lambda p:p);s.IndexRequest=lambda **kwargs:SimpleNamespace(**kwargs);s.RetrieveRequest=SimpleNamespace(model_validate=lambda q:SimpleNamespace(**q))
        s.embedding_model=s.rerank_model=lambda:None;s.encode_embeddings=lambda texts:np.asarray([[1.,0.]for t in texts],dtype=np.float64 if float64 else np.float32)
        s.rerank_candidates=lambda q,parents:np.asarray([.5 for p in parents]);s.parents={}
        def index(req):
            pp=req.chunks;s.parents[req.projectId]=pp;windows=[{'id':p['id']+'-window','parentId':p['id'],'content':p['content'],'role':p['role'],'sourceHash':p['sourceHash']}for p in pp]
            s._window_observer('source-window-plan',{'kind':'embedding','windowBindings':windows})
            values=s.encode_embeddings([p['content']for p in pp]);s._window_observer('window-index-upsert',{'vectors':values.tolist(),'windowIds':[w['id']for w in windows],'parentIds':[p['id']for p in pp],'dimension':2})
            signature={'corpus':d.digest(pp)};metadata={'signature':signature,'chunks':pp,'windows':windows,'parentCount':len(pp),'vectorPoints':len(windows)}
            s.metadata_path(req.projectId).parent.mkdir(parents=True,exist_ok=True);d.write(s.metadata_path(req.projectId),metadata)
            return {'signature':d.digest(signature),'vectorPoints':len(pp),'indexed':len(pp),'cached':False}
        def query(req):
            pp=s.parents[req.projectId];s.rerank_candidates(req.query,pp);payload=copy.deepcopy(pp[0])
            if bad_payload:payload['content']='not full original'
            return {'hits':[{'id':pp[0]['id'],'payload':payload,'score':.5}]}
        s.index=index;s.retrieve=query;return s
    def run_mock(self,bad=False,float64=False):
        args=self.prepared();plan,pd,parents,tooling=d.validate_plan(args);s=self.fake_server(bad,float64)
        sampler=SimpleNamespace(start=lambda:None,mark=lambda stage:None,close=lambda:{'fixtureOnly':True})
        runargs=SimpleNamespace(plan=args.plan,plan_sha256=args.plan_sha256,out=str(self.root/'actual'),experiment_id='synthetic-driver-guard')
        with patch.object(d,'validate_plan',return_value=(plan,pd,parents,tooling)),patch.object(d,'set_math',return_value={'math':d.MATH}),patch.object(d,'load_server',return_value=(s,ROOT/'frozen_software')),patch.object(d,'ResourceSampler',return_value=sampler):
            if bad or float64:
                with self.assertRaises(SystemExit):d.run(runargs)
            else:d.run(runargs)
        return json.loads((self.root/'actual/result.json').read_text())
    def test_main_mock_run_stores_original_float32_and_partial_ordinals(self):
        result=self.run_mock();self.assertEqual(result['status'],'completed');self.assertEqual(result['actualIndexAttempts'],1);self.assertEqual(result['actualRetrievalAttempts'],2)
        self.assertEqual(len(result['actualEncodedFloat32Groups']),1);self.assertTrue(result['qdrantClose']['completed']);self.assertEqual(result['actualNeuralForwardCounts']['embedding']['started'],0)
    def test_wrong_parent_payload_is_saved_then_fails(self):
        result=self.run_mock(bad=True);self.assertEqual(result['status'],'failed');self.assertEqual(result['actualRetrievalAttempts'],1)
        query=json.loads((self.root/'actual/query-0001.json').read_text());self.assertFalse(query['allReturnedParentsExact']);self.assertTrue(result['qdrantClose']['completed'])
    def test_float64_does_not_silently_cast_encoded_outputs(self):
        result=self.run_mock(float64=True);self.assertEqual(result['status'],'failed');self.assertEqual(result['actualIndexAttempts'],1);self.assertEqual(result['actualRetrievalAttempts'],0)
        self.assertIn('float32',result['failure']['message'])


if __name__=='__main__':unittest.main()
