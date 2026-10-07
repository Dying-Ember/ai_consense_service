"""Hash-bound offline GPU window retrieval; prepare is file/DTO validation only.

Real encode/index/query executes only in the explicit run subcommand. No LLM,
downloads, HTTP service, application DB or automatic recipe fallback exists.
"""
import argparse
from datetime import datetime,timezone
import hashlib
import importlib.metadata
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

TOOLING_SHA='9ef793286f056a9c115f9a79d7cc16be5d353354f787c1d2af5d40067e2be562'
MODEL_FILES=('tokenizer.json','tokenizer_config.json','special_tokens_map.json','sentencepiece.bpe.model','config.json','sentence_bert_config.json','modules.json','config_sentence_transformers.json','model.safetensors','pytorch_model.bin','1_Pooling/config.json')
PACKAGES=('torch','transformers','tokenizers','sentence-transformers','qdrant-client','numpy','rank-bm25','psutil','fastapi','pydantic','huggingface-hub')
MATH={'float32MatmulPrecision':'highest','cudaAllowTf32':False,'cudnnAllowTf32':False,'cudnnBenchmark':False,'deterministicAlgorithms':False,'threads':4,'interopThreads':1}
ENV={'CONSENSE_MODEL_DEVICE':'cuda','CONSENSE_MODEL_DTYPE':'float32','CONSENSE_MODEL_SERIAL_OFFLOAD':'1','CONSENSE_EMBED_MAX_TOKENS':'512','CONSENSE_RERANK_MAX_TOKENS':'512','CONSENSE_EMBED_BATCH_SIZE':'4','CONSENSE_RERANK_BATCH_SIZE':'4','CONSENSE_WINDOW_OVERLAP_TOKENS':'64','HF_HUB_OFFLINE':'1','TRANSFORMERS_OFFLINE':'1','HF_HUB_DISABLE_TELEMETRY':'1','TOKENIZERS_PARALLELISM':'false'}


def utc():return datetime.now(timezone.utc).isoformat()
def require(condition,message):
    if not condition:raise ValueError(message)
def digest(value):return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()
def desc(path):
    p=Path(path).resolve();h=hashlib.sha256()
    with p.open('rb')as stream:
        for raw in iter(lambda:stream.read(1024*1024),b''):h.update(raw)
    return {'path':str(p),'bytes':p.stat().st_size,'sha256':h.hexdigest()}
def bound(d):require(desc(d['path'])==d,'Changed bound file');return Path(d['path'])
def json_read(d):return json.loads(bound(d).read_text(encoding='utf-8'))
def write(path,value):
    with Path(path).open('x',encoding='utf-8',newline='\n')as stream:json.dump(value,stream,ensure_ascii=False,indent=2,allow_nan=False);stream.write('\n')
    return desc(path)
def external(path,sha):d=desc(path);require(d['sha256']==sha,'External SHA mismatch');return d
def versions():return {name:importlib.metadata.version(name)for name in PACKAGES}


def load_server(tooling):
    files={row['relativePath']:row['frozen']for row in tooling['software']}
    for d in files.values():bound(d)
    root=bound(files['server.py']).parent;sys.path.insert(0,str(root))
    name='frozen_gpu_window_server';spec=importlib.util.spec_from_file_location(name,root/'server.py')
    server=importlib.util.module_from_spec(spec);sys.modules[name]=server;spec.loader.exec_module(server)
    return server,root


def model_identity(root):
    root=Path(root).resolve();files=[]
    for name in MODEL_FILES:
        path=root/name
        if path.is_file():files.append({'relativePath':name,'file':desc(path)})
    require(any(row['relativePath']in {'model.safetensors','pytorch_model.bin'}for row in files),'Local model weights required')
    require(any(row['relativePath']=='tokenizer.json'for row in files),'Local fast tokenizer required')
    return {'snapshot':str(root),'files':files}


def validate_cache_bundle(descriptor):
    bundle=json_read(descriptor);require(bundle.get('protocol')=='actual-fixed-source-window-vector-cache-bundle-v1','Read cache bundle protocol invalid')
    whitelist=json_read(bundle['whitelist']);require(whitelist.get('protocol')=='fixed-source-vector-cache-whitelist-v1','Read cache whitelist invalid')
    sources={(s.get('sourceHash'),s.get('role')):s for s in whitelist['sources']}
    require(len(sources)==len(whitelist['sources'])and all(s.get('storageTier')=='fixed_competition_material'for s in sources.values()),'Cache source tier/role duplicate or invalid')
    root=Path(bundle['root']).resolve();checked=set();matrices={}
    for entry in bundle['entries']:
        key=entry['key'];require(len(key)==64 and all(c in'0123456789abcdef'for c in key),'Invalid content cache key')
        if key in checked:continue
        for name in('manifest','vector'):
            path=bound(entry[name]);require(path.is_relative_to(root/key),'Cache artifact escapes exact content key')
        manifest=json_read(entry['manifest']);require(manifest['key']==key and digest(manifest['identity'])==key,'Cache manifest content identity mismatch')
        identity=manifest['identity'];source=sources.get((identity.get('sourceHash'),identity.get('role')))
        require(source is not None and source['rawSourceSha256']==identity['rawSourceSha256']and identity['embeddingIdentity']==bundle['embeddingIdentity'],'Cache source/model does not match explicit whitelist/bundle')
        require(entry['vector']['sha256']==manifest['vector']['sha256']and entry['vector']['bytes']==manifest['vector']['bytes'],'Externally bound cache vector differs from manifest')
        bound(entry['encodedSource']['artifact']);require(type(entry['encodedSource']['rowIndex'])is int and entry['encodedSource']['rowIndex']>=0,'Cache original encoded row binding invalid')
        import numpy as np
        original=entry['encodedSource']['artifact'];matrix=matrices.get(original['path'])
        if matrix is None:matrix=np.load(bound(original),allow_pickle=False);matrices[original['path']]=matrix
        require(matrix.dtype==np.float32 and matrix.ndim==2 and entry['encodedSource']['rowIndex']<len(matrix),'Original cache encoded matrix/row invalid')
        encoded=matrix[entry['encodedSource']['rowIndex']];cached=np.frombuffer(bound(entry['vector']).read_bytes(),dtype='<f4')
        require(manifest['dtype']=='float32-le'and manifest['dimension']==bundle['embeddingIdentity']['vectorDimension']and encoded.shape==cached.shape and len(cached)==manifest['dimension']
                and np.isfinite(cached).all()and np.array_equal(encoded,cached),'Cache vector differs from original actual encoded float32 source')
        checked.add(key)
    require(len(checked)==bundle['uniqueCacheKeys']and len(bundle['entries'])==bundle['windowCount'],'Cache bundle count not bound')
    return bundle


def normalize_requests(rows,project,candidates,limit,allow_empty=False):
    require(type(candidates)is int and type(limit)is int and 1<=limit<=100 and limit<=candidates<=500,'Candidate/final recipe invalid')
    result=[]
    for ordinal,row in enumerate(rows,1):
        require(set(row)=={'originalTopicOrdinal','request'} and type(row['originalTopicOrdinal'])is int,'Generic request wrapper invalid')
        original=row['request'];require(isinstance(original,dict),'Request must be object')
        require(not any(k in original for k in ('gold','expected','answer','evaluator','expectedEvidence')),'Evaluator data forbidden')
        require(set(original)<= {'projectId','query','role','candidates','limit'},'Unrecognized query field')
        require(isinstance(original.get('query'),str)and 1<=len(original['query'])<=20000,'Query text invalid')
        require(original.get('role')in {None,'tender','standard','project_fact','package_manifest'},'Query role invalid')
        req={**original,'projectId':project,'candidates':candidates,'limit':limit}
        result.append({'ordinal':ordinal,'originalTopicOrdinal':row['originalTopicOrdinal'],'request':req,'historicalRequest':original,
                       'declaredChangedFields':[k for k in('projectId','candidates','limit')if original.get(k)!=req[k]]})
    require(result or allow_empty,'At least one generic query required');return result


def profile_requests(rows,project,profiles,allow_empty=False):
    require(profiles and len({p['id']for p in profiles})==len(profiles),'Unique retrieval profiles required')
    result=[]
    for profile in profiles:
        require(set(profile)=={'id','candidates','limit'},'Retrieval profile shape invalid')
        for row in normalize_requests(rows,project,profile['candidates'],profile['limit'],allow_empty=allow_empty):
            result.append({**row,'ordinal':len(result)+1,'profileId':profile['id']})
    return result


def prepare(args):
    out=Path(args.out).resolve();require(not out.exists(),'Fresh prepare output required')
    td=external(args.window_tooling,args.window_tooling_sha256)
    tooling=json_read(td);require(tooling.get('protocol')=='isolated-source-token-window-retrieval-tooling-v1','Window core manifest protocol invalid')
    cd=external(args.corpus_manifest,args.corpus_manifest_sha256);cm=json_read(cd)
    corpus_d=cm['corpus'];raw=bound(corpus_d);rows=[json.loads(line)for line in raw.read_text(encoding='utf-8').splitlines()if line.strip()]
    require(len(rows)==cm['chunkCount']and len({r['id']for r in rows})==len(rows)>0,'Corpus count/unique IDs invalid')
    require(all(not any(k in r for k in('expected','gold','answer','evaluator_only','prohibited_claims'))for r in rows),'Evaluator fields forbidden')
    os.environ.update(ENV);os.environ['CONSENSE_WORKSPACE_ROOT']=str(Path(args.workspace).resolve())
    server,root=load_server(tooling)
    normalized=sorted([server.Chunk.model_validate(r).model_dump(exclude_none=True)for r in rows],key=lambda r:r['id']);server.validate_corpus(normalized)
    qd=external(args.generic_query_plan,args.generic_query_plan_sha256);queries=json_read(qd)
    profiles=[{'id':'primary','candidates':args.candidates,'limit':args.limit}]
    if args.coverage_profile:profiles.append({'id':'coverage100x20','candidates':100,'limit':20})
    requests=profile_requests(queries['requests'],args.project_id,profiles,allow_empty=bool(args.fixed_cache_whitelist))
    for row in requests:server.RetrieveRequest.model_validate(row['request'])
    models={kind:model_identity(path)for kind,path in [('embedding',args.embedding_snapshot),('reranker',args.rerank_snapshot)]}
    for kind,identity in models.items():require(Path(identity['snapshot']).name==server.MODELS[kind]['revision'],'Model revision path differs from frozen config')
    cache=None;read_cache=None
    read_bundle=getattr(args,'fixed_cache_read_bundle',None);read_sha=getattr(args,'fixed_cache_read_bundle_sha256',None)
    if read_bundle:
        require(not args.fixed_cache_whitelist and not args.signature_suite,'Cache reuse, cache build and variable signature suite are separate recipes')
        read_cache=external(read_bundle,read_sha);validate_cache_bundle(read_cache)
    require(not read_sha or read_cache is not None,'Read bundle SHA without path')
    if args.fixed_cache_whitelist:
        wd=external(args.fixed_cache_whitelist,args.fixed_cache_whitelist_sha256);whitelist=json_read(wd)
        require(whitelist.get('protocol')=='fixed-source-vector-cache-whitelist-v1','Fixed whitelist protocol invalid')
        keys=[(r.get('sourceHash'),r.get('role'))for r in whitelist['sources']]
        require(len(set(keys))==len(keys),'Fixed whitelist duplicate source/role')
        require(all(r.get('storageTier')=='fixed_competition_material'and isinstance(r.get('rawSourceSha256'),str)and len(r['rawSourceSha256'])==64 for r in whitelist['sources']),'Fixed source tier/raw SHA invalid')
        require(all((p['sourceHash'],p['role'])in keys for p in normalized),'Fixed build corpus includes a non-whitelisted variable source')
        require(not args.signature_suite,'Signature fixtures are a separate variable-source diagnostic, not fixed cache contents')
        cache=wd
    require(not args.fixed_cache_whitelist_sha256 or cache is not None,'Whitelist SHA without path')
    out.mkdir(parents=True);np=write(out/'normalized_parents.json',normalized)
    result={'protocol':'offline-gpu-source-window-retrieval-plan-v1','status':'prepared_no_inference','preparedAtUtc':utc(),
            'projectId':args.project_id,'inputs':{'windowTooling':td,'corpusManifest':cd,'rawCorpus':corpus_d,'genericQueryPlan':qd},
            'normalizedParents':np,'normalizedCorpusSha256':digest(normalized),'parentCount':len(normalized),'requests':requests,
            'models':models,'configuredModels':server.MODELS,'environment':ENV,'workspace':str(Path(args.workspace).resolve()),'packages':versions(),
            'math':MATH,'resourceSamplingSeconds':15,'candidateProfiles':profiles,'fixedCacheBuildWhitelist':cache,'fixedCacheReadBundle':read_cache,'fixedCacheReadEnabled':read_cache is not None,
            'signatureSuite':bool(args.signature_suite),'signatureSuiteScope':'At most three original parents; separate controlled add/remove/change/cross-project index fixtures, no legal quality claims'if args.signature_suite else None,
            'actualCallsThisPrepare':{'modelWeightLoads':0,'encode':0,'predict':0,'index':0,'query':0,'HTTP':0,'appDB':0,'OCR':0},
            'software':desc(__file__),'coreSoftwareDirectory':str(root),'expectedMainIndexCalls':1,'expectedMainQueryCalls':len(requests),
            'runtimeFactor':'GPU float32 explicit math + source windows + current source corpus; comparison against old CPU receipts is not a pure single-factor experiment.'}
    manifest=write(out/'approved_plan.json',result);print(json.dumps({'plan':manifest,'parents':len(normalized),'queries':len(requests)}))


def validate_plan(args):
    pd=external(args.plan,args.plan_sha256);plan=json_read(pd)
    require(plan.get('protocol')=='offline-gpu-source-window-retrieval-plan-v1'and plan.get('status')=='prepared_no_inference','Wrong plan protocol/status')
    require(plan['software']==desc(__file__),'Driver differs from prepared source')
    require(plan['math']==MATH and plan['environment']==ENV and plan['fixedCacheReadEnabled']==bool(plan.get('fixedCacheReadBundle')),'Frozen GPU recipe changed')
    require(plan['packages']==versions(),'Python packages changed')
    for d in plan['inputs'].values():bound(d)
    parents=json_read(plan['normalizedParents']);require(digest(parents)==plan['normalizedCorpusSha256']and len(parents)==plan['parentCount'],'Normalized parents changed')
    tooling=json_read(plan['inputs']['windowTooling']);require(tooling.get('protocol')=='isolated-source-token-window-retrieval-tooling-v1','Window tooling manifest changed')
    for row in tooling['software']:bound(row['frozen'])
    for model in plan['models'].values():
        for row in model['files']:bound(row['file'])
    old=json_read(plan['inputs']['genericQueryPlan'])
    expected=profile_requests(old['requests'],plan['projectId'],plan['candidateProfiles'],allow_empty=bool(plan['fixedCacheBuildWhitelist']))
    require(expected==plan['requests'],'Query plan not byte/field-bound')
    if plan['fixedCacheBuildWhitelist']:bound(plan['fixedCacheBuildWhitelist'])
    if plan.get('fixedCacheReadBundle'):validate_cache_bundle(plan['fixedCacheReadBundle'])
    return plan,pd,parents,tooling


class ResourceSampler:
    def __init__(self,out,seconds):self.out=Path(out);self.seconds=seconds;self.stop_event=threading.Event();self.rows=[];self.stage='startup';self.lock=threading.Lock();self.thread=None
    def sample(self):
        import psutil
        p=psutil.Process();mem=p.memory_info();cpu=p.cpu_times();ram=psutil.virtual_memory()
        row={'timestampUtc':utc(),'stage':self.stage,'pid':p.pid,'rssBytes':mem.rss,'vmsBytes':mem.vms,'cpuUserSeconds':cpu.user,'cpuSystemSeconds':cpu.system,
             'systemAvailableRamBytes':ram.available,'systemTotalRamBytes':ram.total,'gpu':None,'truePeakKnown':False}
        try:
            r=subprocess.run(['nvidia-smi','--query-gpu=index,name,memory.total,memory.used,utilization.gpu','--format=csv,noheader,nounits'],capture_output=True,text=True,timeout=5)
            row['gpu']={'status':'observed'if r.returncode==0 else'unknown','csv':r.stdout.strip()if r.returncode==0 else None,'exitCode':r.returncode}
        except Exception as e:row['gpu']={'status':'unknown','exceptionType':type(e).__name__}
        with self.lock:
            self.rows.append(row)
            with(self.out/'resource_samples.jsonl').open('a',encoding='utf-8')as stream:stream.write(json.dumps(row,ensure_ascii=False)+'\n')
    def start(self):
        self.sample()
        def loop():
            while not self.stop_event.wait(self.seconds):self.sample()
        self.thread=threading.Thread(target=loop,name='retrieval-resource-sampler',daemon=True);self.thread.start()
    def mark(self,stage):self.stage=stage;self.sample()
    def close(self):
        self.stop_event.set()
        if self.thread:self.thread.join(timeout=6)
        self.sample()
        return {'samples':len(self.rows),'observedMaxRssBytes':max((r['rssBytes']for r in self.rows),default=None),'truePeakKnown':False,
                'artifact':desc(self.out/'resource_samples.jsonl'),'software':desc(__file__),'intervalSeconds':self.seconds}


def set_math():
    import torch
    require(torch.cuda.is_available(),'CUDA requested but unavailable; no CPU fallback')
    torch.set_num_threads(MATH['threads']);torch.set_num_interop_threads(MATH['interopThreads'])
    torch.set_float32_matmul_precision(MATH['float32MatmulPrecision']);torch.backends.cuda.matmul.allow_tf32=MATH['cudaAllowTf32']
    torch.backends.cudnn.allow_tf32=MATH['cudnnAllowTf32'];torch.backends.cudnn.benchmark=MATH['cudnnBenchmark'];torch.use_deterministic_algorithms(MATH['deterministicAlgorithms'])
    actual={'float32MatmulPrecision':torch.get_float32_matmul_precision(),'cudaAllowTf32':torch.backends.cuda.matmul.allow_tf32,
            'cudnnAllowTf32':torch.backends.cudnn.allow_tf32,'cudnnBenchmark':torch.backends.cudnn.benchmark,'deterministicAlgorithms':torch.are_deterministic_algorithms_enabled(),
            'threads':torch.get_num_threads(),'interopThreads':torch.get_num_interop_threads()}
    require(actual==MATH,'Actual math flags differ from frozen recipe')
    return {'math':actual,'torchVersion':torch.__version__,'cudaBuild':torch.version.cuda,'cudnnVersion':torch.backends.cudnn.version(),
            'gpuName':torch.cuda.get_device_name(0),'gpuTotalMemoryBytes':torch.cuda.get_device_properties(0).total_memory,'device':'cuda','dtype':'float32'}


def signature_fixtures(server,parents,project,invoke_index):
    import copy
    import uuid
    require(len(parents)>=2,'Actual signature suite needs at least two parents')
    small=parents[:min(3,len(parents))];pid=project+'-signature-fixtures';results=[]
    def record(label,pp,pid_=pid):
        result=invoke_index(pid_,pp,scope='signature_fixture',label=label);results.append({'label':label,'result':result});return result
    first=record('initial_one_parent',small[:1]);same=record('unchanged_cached',small[:1]);require(same['cached']and same['signature']==first['signature'],'Unchanged small index did not cache')
    added=record('add_original_parent',small);require(not added['cached']and added['signature']!=first['signature'],'Add invalidation failed')
    removed=record('remove_original_parent',small[:1]);require(not removed['cached']and removed['signature']!=added['signature'],'Remove invalidation failed')
    changed=copy.deepcopy(small[0]);changed['content']+='\nControlled synthetic index revision.'
    changed['parts']=[{'blockId':'synthetic:index-revision:1','anchor':'synthetic/index-revision/1','text':changed['content'],'startOffset':0,'endOffset':len(changed['content'].encode('utf-16-le'))//2,
                      'extractionMethod':'controlled_synthetic_index_fixture'}]
    changed['sourceHash']=digest({'syntheticIndexMutation':True,'oldSourceHash':changed['sourceHash'],'content':changed['content']})
    changed['syntheticIndexFixture']=True
    revision=record('change_payload_same_parent_id',[changed]);require(not revision['cached']and revision['signature']!=removed['signature'],'Change invalidation failed')
    crossed=record('cross_project_new_state_binding',small[:1],project+'-cross-project-fixture');require(not crossed['cached'],'Cross-project reused old index')
    require(server.read_project(pid)['collection']!=server.read_project(project+'-cross-project-fixture')['collection'],'Projects share a collection')
    return {'scope':'Actual small synthetic/index-transport fixtures, not native quality gold','operations':results,'passed':True}


def run(args):
    # All input/source/model byte checks precede the reservation and any engine.
    plan,pd,parents,tooling=validate_plan(args);out=Path(args.out).resolve();require(not out.exists(),'Fresh actual output required')
    out.mkdir(parents=True);write(out/'reservation.json',{'plan':pd,'reservedAtUtc':utc(),'retries':0,'concurrency':1})
    os.environ.update(plan['environment']);os.environ['CONSENSE_WORKSPACE_ROOT']=plan['workspace'];os.environ['CONSENSE_RETRIEVAL_DATA']=str(out/'state')
    os.environ['HF_HOME']=str(Path(plan['workspace'])/'tmp/vetting_models')
    for key in('CONSENSE_FIXED_VECTOR_CACHE','CONSENSE_FIXED_VECTOR_CACHE_WHITELIST','CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256'):os.environ.pop(key,None)
    read_bundle=validate_cache_bundle(plan['fixedCacheReadBundle'])if plan.get('fixedCacheReadBundle')else None
    if read_bundle:
        os.environ['CONSENSE_FIXED_VECTOR_CACHE']=read_bundle['root'];os.environ['CONSENSE_FIXED_VECTOR_CACHE_WHITELIST']=read_bundle['whitelist']['path']
        os.environ['CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256']=read_bundle['whitelist']['sha256']
    sampler=ResourceSampler(out,plan['resourceSamplingSeconds']);sampler.start();started=time.perf_counter();server=None;probe=None;counts={'index':0,'query':0};operations=[];encoded=[];window_groups=[]
    result={'protocol':'offline-gpu-source-window-retrieval-result-v1','status':'running','plan':pd,'projectId':plan['projectId'],'parentCount':len(parents),
            'actualGenerationCalls':0,'actualHTTP':0,'actualApplicationDB':0,'actualOCR':0,'actualDocumentParse':0,'actualDownloadedFiles':0,'retries':0,'concurrency':1,'software':desc(__file__)}
    try:
        sampler.mark('math_and_runtime');result['runtime']=set_math();server,core=load_server(tooling)
        original_runtime=server.model_runtime
        server.model_runtime=lambda kind:{**original_runtime(kind),'executionMath':result['runtime']['math']}
        window_runtime=server.window_runtime();original_cache_identity=window_runtime.embedding_cache_identity
        window_runtime.embedding_cache_identity=lambda dimension:{**original_cache_identity(dimension),'executionMath':result['runtime']['math']}
        # Absolute local snapshots resolve to the exact pre-fingerprinted files;
        # no Hub resolver or adapter probe may choose a different location.
        server.model_path=lambda c:plan['models']['embedding'if c['name']==server.MODELS['embedding']['name']else'reranker']['snapshot']
        from staged.pipeline_probe import PipelineProbe,install_model_probes
        import numpy as np
        probe=PipelineProbe(out/'probes',args.experiment_id);probe.bind_chunks(parents);install_model_probes(server,probe)
        forward_counts={'embedding':{'started':0,'completed':0},'reranker':{'started':0,'completed':0}};base_verify=server.verify_loaded_model
        def verify_and_observe(model,kind):
            base_verify(model,kind)
            engine=model._first_module().auto_model if kind=='embedding'else model.model
            def before(module,args_,kwargs_):
                forward_counts[kind]['started']+=1;ordinal=forward_counts[kind]['started']
                key=str(probe.context.get('operation'))+':'+str(probe.context.get('scope'))+':'+str(probe.context.get('profileId',probe.context.get('label')))
                counter=forward_counts[kind].setdefault('countsByScope',{}).setdefault(key,{'started':0,'completed':0});counter['started']+=1
                features={name:value.detach().cpu().numpy()for name,value in kwargs_.items()if hasattr(value,'detach')}
                require('input_ids'in features and 'attention_mask'in features,'Actual forward feature envelope unsupported')
                used=features['attention_mask'].sum(axis=1).tolist();require(all(n<=512 for n in used),'Actual forward exceeds frozen full-token cap')
                path=out/f'forward-features-{kind}-{ordinal:06d}.npz'
                with path.open('xb')as stream:np.savez(stream,**features)
                probe.emit('actual-neural-forward-start',{'kind':kind,'forwardOrdinal':ordinal,'engineClass':type(module).__name__,'features':desc(path),
                    'featureNames':sorted(features),'unpaddedFullTokenCounts':used,'inputOriginalUnchanged':True})
            def after(module,args_,kwargs_,output):
                forward_counts[kind]['completed']+=1;ordinal=forward_counts[kind]['completed'];items=dict(output)if hasattr(output,'items')else{}
                key=str(probe.context.get('operation'))+':'+str(probe.context.get('scope'))+':'+str(probe.context.get('profileId',probe.context.get('label')))
                counter=forward_counts[kind].setdefault('countsByScope',{}).setdefault(key,{'started':0,'completed':0});counter['completed']+=1
                metadata={name:{'shape':list(value.shape),'dtype':str(value.dtype),'device':str(value.device)}for name,value in items.items()if hasattr(value,'shape')}
                logits=None
                if hasattr(output,'logits'):
                    path=out/f'forward-logits-{kind}-{ordinal:06d}.npy'
                    with path.open('xb')as stream:np.save(stream,output.logits.detach().cpu().numpy(),allow_pickle=False)
                    logits=desc(path)
                probe.emit('actual-neural-forward-complete',{'kind':kind,'forwardOrdinal':ordinal,'tensorMetadata':metadata,'logits':logits,
                    'rawHiddenStatesStored':False,'boundary':'Full actual features and final encoded vectors retained; large hidden states described by shape/dtype/device.'})
            engine.register_forward_pre_hook(before,with_kwargs=True);engine.register_forward_hook(after,with_kwargs=True)
        server.verify_loaded_model=verify_and_observe
        observer=server._window_observer;active={'rows':[],'cursor':0};scope={'operation':'startup'}
        def observe(stage,payload):
            if stage=='source-window-plan'and payload['kind']=='embedding':
                rows=payload['windowBindings']
                if read_bundle:
                    from fixed_vector_cache import lookup
                    whitelist=json_read(read_bundle['whitelist']);identity=window_runtime.embedding_cache_identity(read_bundle['embeddingIdentity']['vectorDimension']);missing=[];hits=0
                    for row in rows:
                        eligible=any(s.get('sourceHash')==row['sourceHash']and s.get('role')==row['role']for s in whitelist['sources'])
                        vector,receipt=lookup(read_bundle['root'],row,whitelist,identity)if eligible else(None,{'status':'variable_source'})
                        if vector is None:missing.append(row)
                        else:hits+=1
                    result['cacheEncodeLineagePreflight']={'actualScope':dict(scope),'windows':len(rows),'boundCacheHits':hits,'windowsNeedingEncode':len(missing)};rows=missing
                active.update(rows=rows,cursor=0)
            if stage=='window-index-upsert':
                path=out/f'upsert-vectors-{len(window_groups)+1:04d}.npy';matrix=np.asarray(payload['vectors'],dtype=np.float32)
                with path.open('xb')as stream:np.save(stream,matrix,allow_pickle=False)
                artifact=desc(path);window_groups.append({'scope':dict(scope),'windowIds':payload['windowIds'],'parentIds':payload['parentIds'],'vectors':artifact,'dimension':payload['dimension']})
                payload={k:v for k,v in payload.items()if k!='vectors'}|{'vectorsFloat32Artifact':artifact}
            observer(stage,payload)
        server._window_observer=observe;original_encode=server.encode_embeddings
        def encode(texts):
            values=original_encode(texts)
            if scope['operation']=='index':
                begin=active['cursor'];rows=active['rows'][begin:begin+len(texts)];require([r['content']for r in rows]==list(texts),'Encoded window input order differs from current plan')
                matrix=np.asarray(values);require(matrix.dtype==np.float32,'Actual encoded outputs must be float32, no silent cast')
                path=out/f'encoded-float32-{len(encoded)+1:04d}.npy'
                with path.open('xb')as stream:np.save(stream,matrix,allow_pickle=False)
                encoded.append({'scope':dict(scope),'windowIds':[r['id']for r in rows],'parentIds':[r['parentId']for r in rows],'vectors':desc(path),'shape':list(matrix.shape),'dtype':str(matrix.dtype)})
                active['cursor']+=len(texts)
            return values
        server.encode_embeddings=encode
        def invoke_index(project,pp,scope_='main',label='main_index',**kwargs):
            scope.clear();scope.update(operation='index',scope=kwargs.get('scope',scope_),label=label,projectId=project);probe.set_context(**scope);sampler.mark('index:'+label);counts['index']+=1
            tick=time.perf_counter();answer=server.index(server.IndexRequest(projectId=project,chunks=[server.Chunk.model_validate(p)for p in pp]));elapsed=time.perf_counter()-tick
            operations.append({'operation':'index','ordinal':counts['index'],'scope':dict(scope),'seconds':elapsed,'result':answer});return answer
        main=invoke_index(plan['projectId'],parents);result['indexSignature']=main['signature'];result['indexResult']=main
        for row in plan['requests']:
            scope.clear();scope.update(operation='query',scope='main',queryOrdinal=row['ordinal'],profileId=row['profileId'],originalTopicOrdinal=row['originalTopicOrdinal'],projectId=plan['projectId']);probe.set_context(**scope);sampler.mark('query:'+str(row['ordinal']))
            counts['query']+=1;tick=time.perf_counter();answer=server.retrieve(server.RetrieveRequest.model_validate(row['request']));elapsed=time.perf_counter()-tick
            by_id={p['id']:p for p in parents}
            exact=all(hit['id']in by_id and hit['payload']==by_id[hit['id']]for hit in answer['hits'])
            artifact=write(out/f'query-{row["ordinal"]:04d}.json',{'request':row,'response':answer,'seconds':elapsed,'allReturnedParentsExact':exact})
            operations.append({'operation':'query','ordinal':counts['query'],'originalTopicOrdinal':row['originalTopicOrdinal'],'scope':'main','seconds':elapsed,'artifact':artifact})
            require(exact,'Returned query payload is not full original parent')
        if plan['signatureSuite']:result['signatureSuite']=signature_fixtures(server,parents,plan['projectId'],invoke_index)
        if plan['fixedCacheBuildWhitelist']:
            sampler.mark('fixed_cache_artifact_build');from fixed_vector_cache import store,lookup
            whitelist=json_read(plan['fixedCacheBuildWhitelist']);cache=out/'fixed_vector_cache';cache.mkdir();metadata=server.read_project(plan['projectId']);by_window={w['id']:w for w in metadata['windows']}
            identity=server.window_runtime().embedding_cache_identity(int(main['vectorPoints']and server.embedding_model().get_sentence_embedding_dimension()))
            entries=[];keys=set()
            for group in encoded:
                if group['scope']['scope']!='main':continue
                matrix=np.load(bound(group['vectors']),allow_pickle=False)
                for row_index,(id_,vector) in enumerate(zip(group['windowIds'],matrix)):
                    window=by_window[id_];existing,receipt=lookup(cache,window,whitelist,identity)
                    if existing is None:manifest=store(cache,window,vector.tolist(),whitelist,identity);key=manifest['key']
                    else:
                        require(np.array_equal(np.asarray(existing,dtype=np.float32),vector),'Duplicate fixed cache content key has different actual vector');key=receipt['key']
                    keys.add(key);entries.append({'windowId':id_,'parentId':window['parentId'],'key':key,
                        'manifest':desc(cache/key/'manifest.json'),'vector':desc(cache/key/'vector.f32le'),
                        'encodedSource':{'artifact':group['vectors'],'rowIndex':row_index}})
            result['fixedCacheBundle']=write(out/'fixed_cache_manifest.json',{'protocol':'actual-fixed-source-window-vector-cache-bundle-v1','whitelist':plan['fixedCacheBuildWhitelist'],'embeddingIdentity':identity,
                'parentCount':len(parents),'windowCount':len(entries),'uniqueCacheKeys':len(keys),'entries':entries,'root':str(cache),'runtime':result['runtime'],'parentIdsReusable':False})
        result['status']='completed'
    except BaseException as error:
        result['status']='failed';result['failure']={'type':type(error).__name__,'kind':getattr(error,'kind',None),'message':str(error),'detail':getattr(error,'detail',None),'noRecipeFallback':True}
    finally:
        sampler.mark('producer_close');close={'attempted':False,'completed':False}
        if server and server._database is not None:
            close['attempted']=True
            try:server._database.close();server._database=None;close['completed']=True
            except BaseException as e:close['failure']={'type':type(e).__name__,'message':str(e)};result['status']='failed'
        result['qdrantClose']=close
        if probe:result['pipelineProbeManifest']=probe.finish(result['status'],actualIndexMethodCalls=counts['index'],actualQueryMethodCalls=counts['query'],internalNeuralForwardCounts=None)
        result['actualIndexAttempts']=counts['index'];result['actualRetrievalAttempts']=counts['query'];result['operations']=operations
        result['actualNeuralForwardCounts']=forward_counts if 'forward_counts'in locals()else None
        if server:
            result['producerState']=str(server.STATE)
            main_metadata=server.metadata_path(server.project_key(plan['projectId']))
            result['mainMetadata']=desc(main_metadata)if main_metadata.is_file()else None
        result['actualEncodedFloat32Groups']=encoded;result['actualWindowUpsertGroups']=window_groups;result['resources']=sampler.close();result['wallSeconds']=time.perf_counter()-started
        result['softwareAndRuntimeFactorBoundary']='No semantic accuracy/gold evaluation. Actual method/wrapper calls differ from unknown internal forward counts. Instrumented timings include tokenizer/probe/sampler overhead.'
        manifest=write(out/'result.json',result);print(json.dumps({'status':result['status'],'result':manifest,'actualIndex':counts['index'],'actualQuery':counts['query']}))
    if result['status']!='completed':raise SystemExit(2)


def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    a=sub.add_parser('prepare')
    for name in('window-tooling','corpus-manifest','corpus-manifest-sha256','generic-query-plan','generic-query-plan-sha256','embedding-snapshot','rerank-snapshot','project-id','workspace','out'):a.add_argument('--'+name,required=True)
    a.add_argument('--window-tooling-sha256',default=TOOLING_SHA);a.add_argument('--candidates',type=int,default=50);a.add_argument('--limit',type=int,default=10);a.add_argument('--signature-suite',action='store_true');a.add_argument('--coverage-profile',action='store_true')
    a.add_argument('--fixed-cache-whitelist');a.add_argument('--fixed-cache-whitelist-sha256');a.add_argument('--fixed-cache-read-bundle');a.add_argument('--fixed-cache-read-bundle-sha256');a.set_defaults(action=prepare)
    for cmd in('validate','run'):
        a=sub.add_parser(cmd);a.add_argument('--plan',required=True);a.add_argument('--plan-sha256',required=True)
        if cmd=='run':a.add_argument('--out',required=True);a.add_argument('--experiment-id',required=True);a.set_defaults(action=run)
        else:a.set_defaults(action=lambda a:print(json.dumps({'status':'validated_no_inference','plan':validate_plan(a)[1]})))
    args=p.parse_args();args.action(args)


if __name__=='__main__':main()
