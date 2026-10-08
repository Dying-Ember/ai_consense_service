"""Signature-bound precomputed retrieval. This module imports no inference engine."""
from __future__ import annotations
from pathlib import Path
from copy import deepcopy
import hashlib,json,math,time,re

SEAL_NAME='sealed_query_cache.json'

def digest(value):
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def file_descriptor(path:Path,root:Path):
    data=path.read_bytes()
    return {'relativePath':path.resolve().relative_to(root.resolve()).as_posix(),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}

class CacheError(RuntimeError):
    def __init__(self,message,status=409):super().__init__(message);self.status=status

def query_key(project_id,signature,recipe,query,role,limit,candidates):
    return digest({'projectId':str(project_id).strip(),'indexSignature':digest(signature),'recipe':recipe,
        'query':query,'role':role,'limit':limit,'candidates':candidates})

def read_bound(root:Path,descriptor):
    target=(root/descriptor['relativePath']).resolve()
    if not target.is_relative_to(root.resolve()):raise CacheError('Cache artifact path escaped sealed state')
    try:data=target.read_bytes()
    except OSError as error:raise CacheError('Required sealed cache artifact unavailable')from error
    if len(data)!=descriptor['bytes']or hashlib.sha256(data).hexdigest()!=descriptor['sha256']:
        raise CacheError('Sealed cache artifact changed')
    try:return json.loads(data)
    except (ValueError,UnicodeError)as error:raise CacheError('Invalid sealed JSON artifact')from error

def require_sha(value,label):
    if not isinstance(value,str)or re.fullmatch(r'[0-9a-f]{64}',value)is None:
        raise CacheError('An external expected '+label+' SHA256 is required',503)

def validate_request(request,project_id):
    if set(request)!={'projectId','query','role','limit','candidates'}or request['projectId']!=project_id:
        raise CacheError('Query plan request identity/project mismatch')
    if not isinstance(request['query'],str)or not request['query'].strip():
        raise CacheError('Query plan contains an empty query')
    if request['role']not in {'tender','standard','project_fact','package_manifest'}:
        raise CacheError('Query plan role is not explicit')
    for field,maximum in (('limit',100),('candidates',500)):
        value=request[field]
        if not isinstance(value,int)or isinstance(value,bool)or not 1<=value<=maximum:
            raise CacheError('Invalid query plan '+field)
    if request['limit']>request['candidates']:raise CacheError('Query limit exceeds candidate count')

def validate_plan(plan,metadata):
    if plan.get('planVersion')!=1 or plan.get('projectId')!=metadata['projectId']:
        raise CacheError('Approved query plan belongs to another project')
    if plan.get('normalizedCorpusSha256')!=digest(metadata['chunks'])or plan.get('chunkCount')!=len(metadata['chunks']):
        raise CacheError('Approved query plan belongs to another complete corpus')
    requests=plan.get('requests')
    if not isinstance(requests,list)or not requests:raise CacheError('Approved query plan is empty')
    for request in requests:validate_request(request,metadata['projectId'])
    if len({digest(r)for r in requests})!=len(requests):raise CacheError('Approved query plan contains duplicate requests')
    if plan.get('requestCount')!=len(requests):raise CacheError('Approved query plan count mismatch')
    return requests

def validate_hits(response,request,metadata):
    validate_request(request,metadata['projectId'])
    by_id={c['id']:c for c in metadata['chunks']};hits=response.get('hits')
    if response.get('projectId')!=request['projectId']or response.get('indexSignature')!=digest(metadata['signature']):
        raise CacheError('Precomputed response belongs to another corpus or project')
    if response.get('mode')!='dense-bm25-rerank'or not isinstance(hits,list)or len(hits)>request['limit']:
        raise CacheError('Invalid precomputed response shape')
    seen=set()
    for hit in hits:
        key=hit.get('id');source=by_id.get(key)
        if source is None or key in seen or hit.get('payload')!=source:
            raise CacheError('Precomputed hit payload/ID differs from complete source snapshot')
        seen.add(key)
        if request['role']is not None and source['role']!=request['role']:
            raise CacheError('Precomputed hit has the wrong source role')
        if isinstance(hit.get('score'),bool)or not isinstance(hit.get('score'),(int,float))or not math.isfinite(hit['score']):
            raise CacheError('Invalid precomputed score')

class SealedRetrieval:
    def __init__(self,state,expected_seal_sha256,expected_recipe,expected_plan_sha256):
        self.state=Path(state).resolve();self.handshakes=set();self.expected_seal_sha256=expected_seal_sha256
        require_sha(expected_seal_sha256,'seal');require_sha(expected_plan_sha256,'query-plan')
        try:raw=(self.state/SEAL_NAME).read_bytes()
        except OSError as error:raise CacheError('Retrieval cache is not sealed or completed',503)from error
        if hashlib.sha256(raw).hexdigest()!=expected_seal_sha256:
            raise CacheError('Cache seal differs from externally frozen identity')
        try:self.seal=json.loads(raw)
        except (ValueError,UnicodeError)as error:raise CacheError('Invalid cache seal JSON')from error
        if self.seal.get('sealVersion')!=1 or self.seal.get('complete')is not True:
            raise CacheError('Retrieval cache was not completed')
        if self.seal.get('recipe')!=expected_recipe:
            raise CacheError('Build/rerank/model/library/device/settings recipe changed')
        self.recipe=deepcopy(expected_recipe)
        self.metadata=read_bound(self.state,self.seal['metadata'])
        if self.seal['queryPlan']['sha256']!=expected_plan_sha256:
            raise CacheError('Query plan differs from externally frozen identity')
        self.plan=read_bound(self.state,self.seal['queryPlan'])
        requests=validate_plan(self.plan,self.metadata)
        signature=self.metadata.get('signature',{})
        required=expected_recipe['indexSignatureBase']
        if signature!={**required,'corpus':digest(self.metadata.get('chunks',[]))}:
            raise CacheError('Complete cached corpus/signature differs from the build recipe')
        if signature!=self.seal.get('indexSignature')or self.metadata.get('projectId')!=self.seal.get('projectId'):
            raise CacheError('Seal and index metadata disagree')
        chunks=self.metadata.get('chunks',[])
        if len(chunks)!=self.seal.get('indexedCount')or len({c['id']for c in chunks})!=len(chunks):
            raise CacheError('Index count/identity incomplete')
        receipt=read_bound(self.state,self.seal['indexReceipt'])
        if receipt.get('projectId')!=self.metadata['projectId']or receipt.get('indexed')!=len(chunks)or receipt.get('signature')!=digest(signature):
            raise CacheError('Completed index receipt does not bind full corpus')
        self.entries=self.seal.get('queries',{})
        if len(self.entries)!=len(requests)or len(self.entries)!=self.seal.get('plannedQueryCount'):
            raise CacheError('Query plan is incomplete')
        expected_requests={query_key(r['projectId'],signature,self.recipe,r['query'],r['role'],r['limit'],r['candidates']):r for r in requests}
        if set(expected_requests)!=set(self.entries):raise CacheError('Queries differ from the approved complete plan')
        # Validate every planned entry at startup; never silently serve a partial plan.
        for key,entry in self.entries.items():
            r=entry['request']
            if r!=expected_requests[key]:raise CacheError('Planned request was changed')
            if key!=query_key(r['projectId'],signature,self.recipe,r['query'],r['role'],r['limit'],r['candidates']):
                raise CacheError('Query-key identity changed')
            validate_hits(read_bound(self.state,entry['response']),r,self.metadata)

    def unchanged_seal(self):
        try:raw=(self.state/SEAL_NAME).read_bytes()
        except OSError as error:raise CacheError('Required cache seal unavailable')from error
        if hashlib.sha256(raw).hexdigest()!=self.expected_seal_sha256:
            raise CacheError('Cache seal changed after startup')

    def index(self,project_id,chunks):
        self.unchanged_seal()
        read_bound(self.state,self.seal['metadata'])
        key=str(project_id).strip()
        if key!=self.seal['projectId']:raise CacheError('Project has no completed sealed cache')
        normalized=sorted(chunks,key=lambda c:c['id'])
        if len({c['id']for c in normalized})!=len(normalized):raise CacheError('Current corpus has duplicate chunk IDs')
        current={**self.recipe['indexSignatureBase'],'corpus':digest(normalized)}
        if len(normalized)!=self.seal['indexedCount']or current!=self.seal['indexSignature']:
            raise CacheError('Current complete corpus differs from the precomputed index')
        # A real current-corpus handshake is required after every process start.
        self.handshakes.add(key)
        return {'projectId':key,'indexed':len(normalized),'signature':digest(current),'cached':True,
            'precomputed':True,'mode':'sealed-precomputed-cache','activeModelDevice':'none'}

    def retrieve(self,project_id,query,role,limit,candidates):
        self.unchanged_seal()
        started=time.perf_counter();key=str(project_id).strip()
        if key not in self.handshakes:raise CacheError('Current complete-corpus index handshake is required')
        identity=query_key(key,self.seal['indexSignature'],self.recipe,query,role,limit,candidates)
        entry=self.entries.get(identity)
        if entry is None:raise CacheError('No exact precomputed query/role/limit/candidates match')
        response=read_bound(self.state,entry['response'])
        validate_hits(response,entry['request'],self.metadata)
        value=deepcopy(response);value['precomputeSeconds']=value.pop('seconds',None)
        value.update(precomputed=True,cacheMode='sealed-precomputed-cache',activeModelDevice='none',seconds=time.perf_counter()-started)
        return value

    def health(self):
        self.unchanged_seal()
        return {'status':'ok','mode':'sealed-precomputed-cache','device':'none','offline':True,
            'embeddingLoaded':False,'rerankerLoaded':False,'ocrLoaded':False,'precomputed':True,
            'indexed':self.seal['indexedCount'],'plannedQueries':self.seal['plannedQueryCount'],
            'indexSignature':digest(self.seal['indexSignature']),'indexBuildRuntime':self.recipe['indexSignatureBase'],
            'activeModelDevice':'none','liveRetrievalFallbackAllowed':False}

def seal_completed(state,metadata_path,index_response,requests_and_responses,recipe,producer,approved_plan_path,expected_plan_sha256):
    state=Path(state).resolve();seal_path=state/SEAL_NAME
    if seal_path.exists():raise CacheError('Existing seal must never be replaced')
    metadata=json.loads(Path(metadata_path).read_text(encoding='utf-8'))
    require_sha(expected_plan_sha256,'query-plan')
    plan_source=Path(approved_plan_path)
    plan_raw=plan_source.read_bytes()
    if hashlib.sha256(plan_raw).hexdigest()!=expected_plan_sha256:raise CacheError('Approved plan was changed before sealing')
    plan=json.loads(plan_raw);expected_requests=validate_plan(plan,metadata)
    if [request for request,response in requests_and_responses]!=expected_requests:
        raise CacheError('Partial/reordered/different query warming cannot be sealed')
    if metadata['signature']!={**recipe['indexSignatureBase'],'corpus':digest(metadata['chunks'])}:
        raise CacheError('Actual completed index does not match producer recipe')
    if index_response['indexed']!=len(metadata['chunks'])or index_response['signature']!=digest(metadata['signature']):
        raise CacheError('Actual index receipt is incomplete')
    cache=state/'query_cache';cache.mkdir(exist_ok=False)
    plan_file=cache/'approved_query_plan.json';plan_file.write_bytes(plan_raw)
    index_file=cache/'index.response.json';index_file.write_text(json.dumps(index_response,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    entries={}
    for request,response in requests_and_responses:
        validate_hits(response,request,metadata)
        key=query_key(request['projectId'],metadata['signature'],recipe,request['query'],request['role'],request['limit'],request['candidates'])
        if key in entries:raise CacheError('Duplicate query plan entry')
        file=cache/(key+'.response.json');file.write_text(json.dumps(response,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        entries[key]={'request':deepcopy(request),'response':file_descriptor(file,state)}
    seal={'sealVersion':1,'complete':True,'projectId':metadata['projectId'],'indexedCount':len(metadata['chunks']),
        'indexSignature':metadata['signature'],'metadata':file_descriptor(Path(metadata_path),state),
        'indexReceipt':file_descriptor(index_file,state),'queryPlan':file_descriptor(plan_file,state),'plannedQueryCount':len(entries),'queries':entries,
        'recipe':deepcopy(recipe),'producer':deepcopy(producer),'claim':'Precomputed retrieval, not live model retrieval; scores are not evidence validity.'}
    with seal_path.open('x',encoding='utf-8',newline='\n')as f:json.dump(seal,f,ensure_ascii=False,indent=2);f.write('\n')
    expected=hashlib.sha256(seal_path.read_bytes()).hexdigest()
    SealedRetrieval(state,expected,recipe,expected_plan_sha256)
    return {'seal':str(seal_path),'expectedSealSha256':expected,'indexedCount':seal['indexedCount'],'precomputedQueries':len(entries)}
