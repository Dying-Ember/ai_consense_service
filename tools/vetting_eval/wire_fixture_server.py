"""Real current Python health and empty-scope retrieval, loopback transport fixtures.

Index/nonempty responses are explicitly transport fixtures, not live vector/query operations.
No neural/tokenizer/vector client/application DB is instantiated.
"""
from pathlib import Path
import json,sys,os,hashlib,threading,copy,time
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
P=Path(__file__).resolve().parent
# This test tool uses the actual repository runtime next to it. An isolated
# service snapshot uses the same tools/vetting_eval + pom.xml layout.
if not(P.name=='vetting_eval' and P.parent.name=='tools' and (P.parents[1]/'pom.xml').is_file()):
 raise ValueError('Wire fixture must be in a service repository tools/vetting_eval directory')
for required in ['server.py','window_runtime.py','token_windows.py','workspace_root.py','fixed_vector_cache.py','ocr_runtime.py','samples.json']:
 if not(P/required).is_file():raise ValueError('Missing current repository runtime file: '+required)
sys.path.insert(0,str(P))
from workspace_root import resolve_workspace
ROOT=resolve_workspace(P,{})
OUT=Path(sys.argv[1]).resolve();OUT.mkdir(exist_ok=False)
os.environ.update({'CONSENSE_WORKSPACE_ROOT':str(ROOT),'CONSENSE_MODEL_DEVICE':'cpu','CONSENSE_MODEL_DTYPE':'float32','HF_HUB_OFFLINE':'1','TRANSFORMERS_OFFLINE':'1','HF_HUB_DISABLE_TELEMETRY':'1','TOKENIZERS_PARALLELISM':'false','CONSENSE_RETRIEVAL_DATA':str(OUT/'unused_state')})
import server as s
assert Path(s.__file__).resolve()==P/'server.py','Fixture imported a foreign runtime'
from window_runtime import WindowRuntime,CONTRACT
from retrieval_source_units import select_source_units
from token_windows import digest
rt=WindowRuntime(s);s._windows_runtime=rt
counts={'actualHealthMethods':0,'actualEmptyScopeRetrieveMethods':0,'fixtureIndexRequests':0,'fixtureNonemptyRetrieve':0,'actualIndexCalls':0,'actualNeuralForward':0,'actualTokenizerLoads':0,'actualVectorDb':0,'actualOCR':0}
for name in ['embedding_model','encode_embeddings','rerank_model','rerank_candidates','database','ocr']:
 if hasattr(s,name):
  def forbidden(*a,**kw):raise AssertionError('Neural/vector/OCR forbidden in wire fixture')
  setattr(s,name,forbidden)
records=[];LOCK=threading.Lock();states={}
def save(p,o):
 with p.open('x',encoding='utf8')as f:json.dump(o,f,ensure_ascii=False,indent=2);f.write('\n')
def ledger(method,path,body,status,response):
 with LOCK:
  row={'ordinal':len(records)+1,'method':method,'path':path,'rawRequestBody':body,'status':status,'response':response,'transportFixtureOnly':True};records.append(row);save(OUT/('%04d-wire.json'%row['ordinal']),row)
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def answer(self,status,o,raw):
  ledger(self.command,self.path,raw,status,o);b=json.dumps(o,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
 def do_GET(self):
  case=self.path.split('/')[1]if self.path.count('/')>1 else'normal'
  if self.path.endswith('/health'):
   counts['actualHealthMethods']+=1;o=s.health()
   if case=='wrong_version':o['indexSignatureVersion']=2
   if case=='bad_recipe':o['retrieval']['sourceWindows']['embeddingWindows']['max_tokens']=513
   if case=='changed_recipe':o['retrieval']['rrfConstant']=777
   if case=='wrong_contract':o['retrieval']['sourceWindows']['contract']='legacy-parent-vector-v2'
   return self.answer(200,o,'')
  if self.path=='/shutdown':
   self.answer(200,{'counts':counts},'');threading.Thread(target=self.server.shutdown,daemon=True).start();return
  self.answer(404,{'error':'not found'},'')
 def do_POST(self):
  raw=self.rfile.read(int(self.headers.get('Content-Length','0'))).decode();body=json.loads(raw);case=self.path.split('/')[1]
  if self.path.endswith('/index'):
   request=s.IndexRequest.model_validate(body);chunks=sorted([c.model_dump(exclude_none=True)for c in request.chunks],key=lambda c:c['id']);s.validate_corpus(chunks)
   signature=digest({'fixture':True,'contract':CONTRACT,'chunks':chunks,'projectId':body['projectId']});states[(case,str(body['projectId']))]={'chunks':chunks,'signature':signature}
   counts['fixtureIndexRequests']+=1;o={'projectId':str(body['projectId']),'indexed':len(chunks),'signature':signature,'cached':False,'contract':CONTRACT}
   if case=='bad_index_contract':o['contract']='wrong-contract'
   if case=='index_fail':return self.answer(503,{'detail':{'kind':'FIXTURE_INDEX_UNAVAILABLE'}},raw)
   return self.answer(200,o,raw)
  if self.path.endswith('/retrieve'):
   request=s.RetrieveRequest.model_validate(body);state=states[(case,str(body['projectId']))]
   if case=='retrieve_fail':return self.answer(503,{'detail':{'kind':'FIXTURE_RETRIEVAL_UNAVAILABLE'}},raw)
   # Actual runtime early empty-scope branch, on explicit fixture metadata.
   # The scope contains no parents, so no vector/model/tokenizer method runs.
   metadata={'contract':CONTRACT,'projectId':str(body['projectId']),'signature':{'signatureVersion':3,'fixture':True},'chunks':[], 'windows':[], 'windowManifestSha256':digest([]),'vectorPoints':0}
   s.read_project=lambda key:metadata;counts['actualEmptyScopeRetrieveMethods']+=1;o=rt.retrieve(request)
   # Index receipt/signature here is an HTTP fixture, never claimed an actual indexed corpus.
   o['indexSignature']=state['signature']
   if case=='legacy_mode':o['mode']='dense-bm25-rerank'
   if case=='bad_mode':o['mode']='lexical'
   if case=='wrong_signature':o['indexSignature']='b'*64
   if case=='hit' or case=='changed_payload':
    counts['fixtureNonemptyRetrieve']+=1;payload=copy.deepcopy(next(c for c in state['chunks']if request.role is None or c['role']==request.role))
    # An injected ranked transport fixture still carries the actual pure-source
    # structural episode. The previous actual empty-scope episode has no seeds.
    o['hits'],o['sourceUnits']=select_source_units(state['chunks'],[{'id':payload['id'],'score':.4,'payload':payload}],request.limit);o.pop('emptyScope',None)
    if case=='changed_payload':o['hits'][0]['payload']['content']+=' changed'
   return self.answer(200,o,raw)
  self.answer(404,{'error':'not found'},raw)
server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
save(OUT/'server_started.json',{'url':'http://127.0.0.1:'+str(server.server_port),'fixtureServer':str(Path(__file__).resolve()),'runtimeRoot':str(P),'workspaceRoot':str(ROOT),'interpreter':str(Path(sys.executable).resolve()),'runtimeFileSha256':{name:hashlib.sha256((P/name).read_bytes()).hexdigest()for name in ['server.py','window_runtime.py','token_windows.py','workspace_root.py','fixed_vector_cache.py','ocr_runtime.py','samples.json']},'healthUsesActualRuntime':True,'indexIsFixtureNotActualVectorIndex':True})
print(json.dumps({'url':'http://127.0.0.1:'+str(server.server_port)}),flush=True)
try:server.serve_forever()
finally:
 server.server_close();assert s._embedding is None and s._reranker is None and s._database is None and not rt.tokenizers
 save(OUT/'server_final.json',{'counts':counts,'requests':len(records),'unusedStateCreated':(OUT/'unused_state').exists(),'modelVectorTokenizerEnginesLoaded':False})
