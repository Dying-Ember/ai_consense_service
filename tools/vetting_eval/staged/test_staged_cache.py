"""Pure-CPU synthetic regressions; no inference, original DB or external HTTP."""
from __future__ import annotations
from copy import deepcopy
from pathlib import Path
import builtins,hashlib,importlib.util,json,os,sys,tempfile,unittest
from unittest.mock import patch
from staged_cache import CacheError,SealedRetrieval,digest,file_descriptor,seal_completed,validate_hits

HERE=Path(__file__).resolve().parent
BLOCKED={'torch','sentence_transformers','transformers','qdrant_client','rank_bm25','ocr_runtime','rapidocr','onnxruntime','huggingface_hub','numpy'}

def load_server():
    os.environ.update(CONSENSE_RETRIEVAL_MODE='cache_only',CONSENSE_CACHE_BUILD_DEVICE='cuda',CONSENSE_CACHE_BUILD_DTYPE='float16',
        CONSENSE_EMBED_MAX_TOKENS='512',CONSENSE_RERANK_MAX_TOKENS='512',CONSENSE_EMBED_BATCH_SIZE='4',CONSENSE_RERANK_BATCH_SIZE='4')
    spec=importlib.util.spec_from_file_location('synthetic_cache_only_server',HERE/'server_staged.py')
    module=importlib.util.module_from_spec(spec);sys.modules[spec.name]=module;spec.loader.exec_module(module);return module

def fake_recipe():
    return {'indexSignatureBase':{'signatureVersion':2,'embedding':{'name':'synthetic/embedding','revision':'r1'},
        'maxTokens':512,'batchSize':4,'device':'cuda','dtype':'float16','normalized':True},
        'reranker':{'name':'synthetic/reranker','revision':'r2','maxTokens':512,'batchSize':4,'device':'cuda','dtype':'float16'},
        'packages':{'torch':'test'},'retrieval':{'rrfConstant':60},'producerSources':{'serverSha256':'test'}}

class Fixture:
    def __init__(self,root,recipe=None):
        self.root=root;self.recipe=deepcopy(recipe or fake_recipe())
        self.chunks=[{'id':'a','content':'Original project requirement text.','role':'tender','documentId':1,'sourceHash':'source-a',
            'parts':[{'text':'Original project requirement text.','startOffset':0,'endOffset':34,'table':{'cells':['A','B']}}]},
            {'id':'b','content':'Original supporting requirement.','role':'standard','documentId':2,'sourceHash':'source-b'}]
        self.signature={**self.recipe['indexSignatureBase'],'corpus':digest(self.chunks)}
        self.metadata={'projectId':'synthetic-project','signature':self.signature,'collection':'synthetic-collection','chunks':self.chunks}
        self.metadata_path=root/'projects'/'metadata.json';self.metadata_path.parent.mkdir(parents=True)
        self.metadata_path.write_text(json.dumps(self.metadata),encoding='utf-8')
        self.receipt={'projectId':'synthetic-project','indexed':2,'signature':digest(self.signature),'cached':False,'seconds':1.2}
        self.requests=[{'projectId':'synthetic-project','query':'Find original requirements.','role':role,'limit':10,'candidates':50}for role in ('tender','standard')]
        self.responses=[{'projectId':'synthetic-project','indexSignature':digest(self.signature),'mode':'dense-bm25-rerank',
            'hits':[{'id':c['id'],'score':.4,'payload':deepcopy(c)}],'seconds':.2}for c in self.chunks]
        self.plan={'planVersion':1,'projectId':'synthetic-project','normalizedCorpusSha256':digest(self.chunks),'chunkCount':2,
            'requestCount':2,'requests':deepcopy(self.requests)}
        self.plan_path=root/'plan.json';self.plan_path.write_text(json.dumps(self.plan),encoding='utf-8')
        self.plan_sha=hashlib.sha256(self.plan_path.read_bytes()).hexdigest();self.sealed=None
    def seal(self,pairs=None):
        self.sealed=seal_completed(self.root,self.metadata_path,self.receipt,pairs if pairs is not None else list(zip(self.requests,self.responses)),
            self.recipe,{'synthetic':True},self.plan_path,self.plan_sha)
        return self
    def open(self,recipe=None,seal_sha=None,plan_sha=None):
        return SealedRetrieval(self.root,seal_sha or self.sealed['expectedSealSha256'],recipe or self.recipe,plan_sha or self.plan_sha)
    def reanchor(self,edit):
        path=self.root/'sealed_query_cache.json';value=json.loads(path.read_bytes());edit(value)
        path.write_text(json.dumps(value),encoding='utf-8');self.sealed['expectedSealSha256']=hashlib.sha256(path.read_bytes()).hexdigest()

class CacheTests(unittest.TestCase):
    def setUp(self):self.temp=tempfile.TemporaryDirectory();self.fixture=Fixture(Path(self.temp.name))
    def tearDown(self):self.temp.cleanup()
    def test_roundtrip_complete_handshake_and_precomputed_labels(self):
        f=self.fixture.seal();cache=f.open();receipt=cache.index('synthetic-project',deepcopy(f.chunks))
        self.assertTrue(receipt['cached']);self.assertEqual(receipt['activeModelDevice'],'none')
        result=cache.retrieve(**{'project_id':'synthetic-project','query':f.requests[0]['query'],'role':'tender','limit':10,'candidates':50})
        self.assertEqual(result['hits'],f.responses[0]['hits']);self.assertEqual(result['precomputeSeconds'],.2)
        self.assertTrue(result['precomputed']);self.assertEqual(cache.health()['indexBuildRuntime']['device'],'cuda')
        result['hits'][0]['payload']['content']='caller mutation'
        self.assertEqual(cache.retrieve('synthetic-project',f.requests[0]['query'],'tender',10,50)['hits'],f.responses[0]['hits'])
    def test_retrieve_requires_current_complete_corpus_handshake(self):
        cache=self.fixture.seal().open()
        with self.assertRaisesRegex(CacheError,'handshake'):cache.retrieve('synthetic-project',self.fixture.requests[0]['query'],'tender',10,50)
    def test_all_query_identity_changes_fail_closed(self):
        f=self.fixture.seal();cache=f.open();cache.index('synthetic-project',f.chunks)
        for query,role,limit,candidates in [('Find original requirements. ','tender',10,50),('new query','tender',10,50),
            (f.requests[0]['query'],'project_fact',10,50),(f.requests[0]['query'],'tender',9,50),(f.requests[0]['query'],'tender',10,51)]:
            with self.subTest(query=query,role=role,limit=limit,candidates=candidates):
                with self.assertRaises(CacheError):cache.retrieve('synthetic-project',query,role,limit,candidates)
        with self.assertRaises(CacheError):cache.retrieve('other-project',f.requests[0]['query'],'tender',10,50)
    def test_same_count_content_source_role_and_table_changes_refused(self):
        f=self.fixture.seal();cache=f.open()
        for field in ('content','sourceHash','id','role','table'):
            chunks=deepcopy(f.chunks)
            if field=='table':chunks[0]['parts'][0]['table']['cells'][1]='different cell'
            else:chunks[0][field]='different'
            with self.subTest(field=field):
                with self.assertRaises(CacheError):cache.index('synthetic-project',chunks)
        with self.assertRaises(CacheError):cache.index('synthetic-project',[f.chunks[0],f.chunks[0]])
        with self.assertRaises(CacheError):cache.index('another-project',f.chunks)
    def test_model_revision_embedding_rerank_batch_tokens_device_and_package_mismatch(self):
        f=self.fixture.seal()
        paths=[('indexSignatureBase','maxTokens'),('indexSignatureBase','batchSize'),('indexSignatureBase','device'),('indexSignatureBase','dtype'),
            ('reranker','maxTokens'),('reranker','batchSize'),('reranker','revision'),('packages','torch'),('retrieval','rrfConstant')]
        for parent,field in paths:
            recipe=deepcopy(f.recipe);recipe[parent][field]='changed'
            with self.subTest(parent=parent,field=field):
                with self.assertRaisesRegex(CacheError,'recipe'):f.open(recipe=recipe)
        recipe=deepcopy(f.recipe);recipe['indexSignatureBase']['embedding']['revision']='changed'
        with self.assertRaises(CacheError):f.open(recipe=recipe)
    def test_external_seal_and_plan_sha_mandatory(self):
        f=self.fixture.seal()
        for seal,plan in [(None,f.plan_sha),(f.sealed['expectedSealSha256'],None),('0'*64,f.plan_sha),(f.sealed['expectedSealSha256'],'0'*64)]:
            with self.subTest(seal=seal,plan=plan):
                with self.assertRaises(CacheError):SealedRetrieval(f.root,seal,f.recipe,plan)
    def test_partial_reordered_and_duplicate_queries_cannot_be_sealed(self):
        f=self.fixture
        for pairs in [list(zip(f.requests[:1],f.responses[:1])),list(reversed(list(zip(f.requests,f.responses)))),[(f.requests[0],f.responses[0])]*2]:
            with self.subTest(length=len(pairs)):
                with self.assertRaisesRegex(CacheError,'Partial'):f.seal(pairs)
        self.assertFalse((f.root/'sealed_query_cache.json').exists())
    def test_partial_receipt_and_legacy_metadata_rejected(self):
        f=self.fixture;f.receipt['indexed']=1
        with self.assertRaises(CacheError):f.seal()
        f.receipt['indexed']=2;f.metadata['signature'].pop('signatureVersion');f.metadata_path.write_text(json.dumps(f.metadata))
        with self.assertRaises(CacheError):f.seal()
    def test_full_metadata_tamper_detected_at_startup_and_handshake(self):
        f=self.fixture.seal();cache=f.open();f.metadata_path.write_text('{}')
        with self.assertRaises(CacheError):f.open()
        with self.assertRaises(CacheError):cache.index('synthetic-project',f.chunks)
    def test_response_tamper_detected_at_startup_and_each_retrieve(self):
        f=self.fixture.seal();cache=f.open();cache.index('synthetic-project',f.chunks)
        seal=json.loads((f.root/'sealed_query_cache.json').read_text());entry=next(iter(seal['queries'].values()))
        (f.root/entry['response']['relativePath']).write_text('{}')
        with self.assertRaises(CacheError):f.open()
        with self.assertRaises(CacheError):cache.retrieve('synthetic-project',entry['request']['query'],entry['request']['role'],10,50)
    def test_seal_tamper_after_startup_detected_even_health(self):
        f=self.fixture.seal();cache=f.open();(f.root/'sealed_query_cache.json').write_text('{}')
        with self.assertRaises(CacheError):cache.health()
    def test_seal_disallows_replacement(self):
        f=self.fixture.seal()
        with self.assertRaisesRegex(CacheError,'never'):f.seal()
    def test_signed_partial_plan_and_entries_disagree(self):
        f=self.fixture.seal();f.reanchor(lambda seal:seal['queries'].pop(next(iter(seal['queries']))))
        with self.assertRaisesRegex(CacheError,'incomplete'):f.open()
    def test_signed_directory_escape_and_invalid_receipt_rejected(self):
        f=self.fixture.seal();f.reanchor(lambda seal:seal['indexReceipt'].update(relativePath='../outside.json'))
        with self.assertRaisesRegex(CacheError,'escaped'):f.open()
    def test_wrong_project_payload_role_unknown_id_and_score_refused(self):
        f=self.fixture
        for kind in ('project','signature','role','payload','id','score'):
            response=deepcopy(f.responses[0]);request=deepcopy(f.requests[0])
            if kind=='project':request['projectId']='different-project';response['projectId']='different-project'
            elif kind=='signature':response['indexSignature']='0'*64
            elif kind=='role':request['role']='standard'
            elif kind=='payload':response['hits'][0]['payload']['content']='changed'
            elif kind=='id':response['hits'][0]['id']='absent'
            elif kind=='score':response['hits'][0]['score']=float('nan')
            with self.subTest(kind=kind):
                with self.assertRaises(CacheError):validate_hits(response,request,f.metadata)

class EndpointNoInferenceTests(unittest.TestCase):
    def test_cache_only_import_startup_health_handshake_hit_misses_and_ocr_without_model_imports(self):
        original=builtins.__import__;blocked=[]
        def guarded(name,*args,**kwargs):
            if name.split('.')[0]in BLOCKED:blocked.append(name);raise AssertionError('Inference import in cache-only: '+name)
            return original(name,*args,**kwargs)
        with tempfile.TemporaryDirectory()as root,patch.dict(os.environ,{},clear=False),patch('builtins.__import__',side_effect=guarded):
            server=load_server();recipe=server.staged_recipe();fixture=Fixture(Path(root),recipe).seal()
            server.STATE=fixture.root
            os.environ['CONSENSE_CACHE_SEAL_SHA256']=fixture.sealed['expectedSealSha256'];os.environ['CONSENSE_CACHE_PLAN_SHA256']=fixture.plan_sha
            for name in ('embedding_model','rerank_model','device','database'):
                setattr(server,name,lambda:(_ for _ in ()).throw(AssertionError('Inference helper invoked')))
            from fastapi.testclient import TestClient
            with TestClient(server.app)as client:
                self.assertEqual(client.get('/health').status_code,200)
                self.assertEqual(client.post('/retrieve',json=fixture.requests[0]).status_code,409)
                self.assertEqual(client.post('/index',json={'projectId':'synthetic-project','chunks':fixture.chunks}).status_code,200)
                hit=client.post('/retrieve',json=fixture.requests[0]);self.assertEqual(hit.status_code,200)
                self.assertTrue(hit.json()['precomputed']);self.assertEqual(hit.json()['activeModelDevice'],'none')
                changed=deepcopy(fixture.requests[0]);changed['query']+=' changed'
                self.assertEqual(client.post('/retrieve',json=changed).status_code,409)
                self.assertEqual(client.post('/ocr',files={'file':('fake.png',b'no image')}).status_code,503)
                changed=deepcopy(fixture.chunks);changed[0]['sourceHash']='stale'
                self.assertEqual(client.post('/index',json={'projectId':'synthetic-project','chunks':changed}).status_code,409)
                self.assertTrue(all(getattr(server,name)is None for name in ('_embedding','_reranker','_ocr','_database')))
            self.assertEqual(blocked,[])
    def test_changed_current_recipe_refused_before_health_success_without_inference(self):
        server=load_server()
        with tempfile.TemporaryDirectory()as root:
            fixture=Fixture(Path(root),server.staged_recipe()).seal();server.STATE=fixture.root
            with patch.dict(os.environ,{'CONSENSE_CACHE_SEAL_SHA256':fixture.sealed['expectedSealSha256'],
                'CONSENSE_CACHE_PLAN_SHA256':fixture.plan_sha,'CONSENSE_CACHE_BUILD_DEVICE':'cpu','CONSENSE_CACHE_BUILD_DTYPE':'float32'}):
                with self.assertRaises(CacheError):server.sealed_cache()

class WarmPreparationTests(unittest.TestCase):
    def test_actual_logger_contract_with_serial_fake_warming_and_no_models(self):
        import warm_staged_cache
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory()as temporary:
            root=Path(temporary);fixture=Fixture(root/'input')
            raw=root/'corpus.jsonl';raw.write_text(''.join(json.dumps(c)+'\n'for c in fixture.chunks),encoding='utf-8')
            fixture.plan['binding']={'fullOriginalChunks':warm_staged_cache.descriptor(raw)}
            fixture.plan_path.write_text(json.dumps(fixture.plan),encoding='utf-8')
            fixture.plan_sha=hashlib.sha256(fixture.plan_path.read_bytes()).hexdigest()
            args=SimpleNamespace(plan=fixture.plan_path,plan_sha=fixture.plan_sha,out=root/'output',execute=True,
                log_module=Path(r'C:\Coding\ConSense\ai_consense_service\tools\vetting_eval\experiment_log.py'),
                log_root=root/'registry',experiment_id='synthetic-warm-logger')
            class FakeChunk:
                @staticmethod
                def model_validate(value):return FakeChunk(value)
                def __init__(self,value):self.value=value
                def model_dump(self,**kwargs):return self.value
            class FakeServer:
                _database=None
                _embedding=None
                _reranker=None
                @staticmethod
                def embedding_model():raise AssertionError('Synthetic warm fixture must not load embedding')
                @staticmethod
                def rerank_model():raise AssertionError('Synthetic warm fixture must not load reranker')
                @staticmethod
                def encode_embeddings(texts):raise AssertionError('Synthetic warm fixture must not encode')
                @staticmethod
                def rerank_candidates(query,candidates):raise AssertionError('Synthetic warm fixture must not rank')
                def staged_recipe(self):return fixture.recipe
                Chunk=FakeChunk
                @staticmethod
                def validate_corpus(chunks):pass
                @staticmethod
                def IndexRequest(**kwargs):return kwargs
                RetrieveRequest=SimpleNamespace(model_validate=lambda value:value)
                def metadata_path(self,key):return args.out/'state/projects/metadata.json'
                def index(self,request):
                    path=self.metadata_path('synthetic-project');path.parent.mkdir(parents=True)
                    path.write_text(json.dumps(fixture.metadata),encoding='utf-8');return fixture.receipt
                def retrieve(self,request):return fixture.responses[fixture.requests.index(request)]
            with patch.dict(os.environ,{},clear=False),patch.object(warm_staged_cache,'import_local_server',return_value=FakeServer()),\
                patch.object(warm_staged_cache,'model_files',return_value={'syntheticOnly':True}):
                result=warm_staged_cache.run(args)
            self.assertEqual(result['status'],'completed');self.assertEqual(result['actualRetrievalAttempts'],2)
            events=[json.loads(p.read_text())for p in (args.log_root/'records').rglob('*.json')]
            self.assertEqual({e['record']['status']for e in events},{'started','completed'})
            self.assertEqual(sum(e['eventType']=='index_attempt_started'for e in events),1)
            self.assertEqual(sum(e['eventType']=='retrieve_attempt_started'for e in events),2)
            self.assertTrue((args.out/'state/sealed_query_cache.json').exists())
    def test_warm_default_prepare_never_imports_server_or_models(self):
        import warm_staged_cache
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory()as temporary:
            fixture=Fixture(Path(temporary)/'input');args=SimpleNamespace(plan=fixture.plan_path,plan_sha=fixture.plan_sha,
                out=Path(temporary)/'output',execute=False)
            with patch.object(warm_staged_cache,'import_local_server',side_effect=AssertionError('Live server imported')):
                result=warm_staged_cache.run(args)
            self.assertEqual(result['actualIndexAttempts'],0);self.assertEqual(result['actualRetrievalAttempts'],0)
            self.assertEqual(result['status'],'prepared_only')

if __name__=='__main__':unittest.main(verbosity=2)
