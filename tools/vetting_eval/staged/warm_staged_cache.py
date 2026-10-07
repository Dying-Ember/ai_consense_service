"""Explicit GPU precomputation; default is file-only preparation, never inference.

One fresh index, then the approved exact query list, serially with zero retries.
Calls the unchanged staged live endpoint functions in-process (no HTTP benchmark).
"""
from __future__ import annotations
import argparse,copy,hashlib,importlib.util,json,os,sys,time,traceback
from datetime import datetime,timezone
from pathlib import Path
from staged_cache import CacheError,digest,seal_completed

HERE=Path(__file__).resolve().parent

def now():return datetime.now(timezone.utc).isoformat()

def descriptor(path):
    path=Path(path).resolve();raw=path.read_bytes()
    return {'path':str(path),'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}

def write_json(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')

def checked_plan(path,expected_sha):
    actual=descriptor(path)
    if actual['sha256']!=expected_sha:raise CacheError('Externally approved query plan changed')
    plan=json.loads(path.read_text(encoding='utf-8'))
    if plan['planVersion']!=1 or plan['requestCount']!=len(plan['requests'])or not plan['requests']:
        raise CacheError('Incomplete approved plan')
    if len({digest(request)for request in plan['requests']})!=len(plan['requests']):raise CacheError('Duplicate approved query')
    for request in plan['requests']:
        if request['projectId']!=plan['projectId']:raise CacheError('Plan includes another project')
    return plan,actual

def import_local_server():
    spec=importlib.util.spec_from_file_location('server_staged_live',HERE/'server_staged.py')
    module=importlib.util.module_from_spec(spec);sys.modules[spec.name]=module;spec.loader.exec_module(module)
    return module

def model_files(server):
    """Resolve pinned LOCAL snapshots, and hash their bytes before inference.

    The complete inventory is kept as a producer artifact. Cache-only has no
    weight-loading dependency; it verifies the externally bound seal instead.
    """
    inventory={}
    for kind,config in server.MODELS.items():
        snapshot=Path(server.model_path(config)).resolve()
        files=[]
        for path in sorted(snapshot.rglob('*')):
            if path.is_file():
                sha=hashlib.sha256();size=0
                with path.open('rb')as stream:
                    for block in iter(lambda:stream.read(8*1024*1024),b''):sha.update(block);size+=len(block)
                files.append({'relativePath':path.relative_to(snapshot).as_posix(),'bytes':size,'sha256':sha.hexdigest()})
        if not files:raise CacheError('Pinned local model snapshot is empty')
        inventory[kind]={'config':config,'resolvedSnapshot':str(snapshot),'files':files}
    return inventory

def run(args):
    # Preparation reads only small frozen descriptors. Full corpus/model bytes are
    # checked only during an explicitly authorized --execute stage.
    plan,plan_identity=checked_plan(args.plan,args.plan_sha)
    if args.out.exists():raise CacheError('Fresh warming output directory required')
    args.out.mkdir(parents=True)
    args.output_created_by_this_invocation = True
    server_identity=descriptor(HERE/'server_staged.py');cache_identity=descriptor(HERE/'staged_cache.py')
    parameters={'mode':'gpu-precompute','device':'cuda','dtype':'float16','embeddingMaxTokens':512,
        'embeddingBatchSize':4,'rerankerMaxTokens':512,'rerankerBatchSize':4,'retries':0,
        'queryCount':len(plan['requests']),'execution':bool(args.execute),'precomputedNotLive':True}
    initial={'status':'prepared_only','startedAtUtc':now(),'parameters':parameters,'plan':plan_identity,
        'producerSources':{'server':server_identity,'cache':cache_identity,'driver':descriptor(__file__)},
        'actualIndexAttempts':0,'actualRetrievalAttempts':0,'calls':[],
        'evaluation':{'semanticQualityAccepted':None,'indexAndQueryResultsObserved':False},
        'noConsumerModels':'Consumer startup and every miss are sealed-file only; no live fallback.'}
    write_json(args.out/'preparation.json',initial)
    if not args.execute:
        print(json.dumps({'status':'prepared_only','out':str(args.out),'queryCount':len(plan['requests']),'index_model_http_calls':0}))
        return initial
    # Environment changes are confined to this new process; no running server is touched.
    state=args.out/'state'
    if state.exists():raise CacheError('Fresh index/cache state required')
    overrides={'CONSENSE_RETRIEVAL_MODE':'live','CONSENSE_RETRIEVAL_DATA':str(state),
        'CONSENSE_MODEL_DEVICE':'cuda','CONSENSE_MODEL_OFFLINE':'1','HF_HUB_OFFLINE':'1',
        'CONSENSE_EMBED_MAX_TOKENS':'512','CONSENSE_EMBED_BATCH_SIZE':'4',
        'CONSENSE_RERANK_MAX_TOKENS':'512','CONSENSE_RERANK_BATCH_SIZE':'4'}
    os.environ.update(overrides)
    sys.path.insert(0,str(args.log_module.parent))
    import experiment_log
    record={'schemaVersion':1,'experimentId':args.experiment_id,'status':'started','scope':'full-corpus GPU retrieval precomputation',
        'startedAtUtc':initial['startedAtUtc'],'finishedAtUtc':None,
        'hypothesis':'Serial GPU index and frozen production query results can be consumed without resident retrieval weights during Bonsai review.',
        'changedFactors':['execution_device','phase_separation','precomputed_retrieval_consumption'],
        'baselineId':None,'parameters':copy.deepcopy(parameters),
        'inputFingerprints':{'plan':copy.deepcopy(plan_identity),'sourceCorpus':copy.deepcopy(plan['binding']['fullOriginalChunks']),
            'server':copy.deepcopy(server_identity),'cache':copy.deepcopy(cache_identity)},
        'measurements':{'indexAttempts':0,'retrievalAttempts':0,'calls':[]},
        'evaluation':copy.deepcopy(initial['evaluation']),'artifacts':[]}
    def event(kind):experiment_log.append_record(args.log_root,copy.deepcopy(record),event_type=kind)
    server=None;probe_collector=None
    current=copy.deepcopy(initial);current['status']='started'
    try:
        event('started')
        server=import_local_server()
        from pipeline_probe import PipelineProbe, install_model_probes
        probe_collector=PipelineProbe(args.out/'pipeline_probes',args.experiment_id)
        server.PROBE=probe_collector
        install_model_probes(server,probe_collector)
        raw_source=Path(plan['binding']['fullOriginalChunks']['path'])
        if descriptor(raw_source)!=plan['binding']['fullOriginalChunks']:raise CacheError('Full original corpus artifact changed')
        chunks=[]
        with raw_source.open(encoding='utf-8')as stream:
            for line in stream:
                if line.strip():chunks.append(server.Chunk.model_validate(json.loads(line)))
        normalized=sorted([chunk.model_dump(exclude_none=True)for chunk in chunks],key=lambda chunk:chunk['id'])
        server.validate_corpus(normalized)
        if digest(normalized)!=plan['normalizedCorpusSha256']or len(chunks)!=plan['chunkCount']:
            raise CacheError('Actual complete corpus differs from the approved plan')
        recipe=server.staged_recipe()
        if recipe['indexSignatureBase']['device']!='cuda' or recipe['indexSignatureBase']['dtype']!='float16':
            raise CacheError('Explicit GPU build recipe unavailable; no CPU fallback allowed')
        weights=model_files(server);write_json(args.out/'model_files.json',weights)
        write_json(args.out/'recipe.json',recipe)
        record['measurements']['modelFiles']=descriptor(args.out/'model_files.json')
        probe_collector.set_context('index',projectId=plan['projectId'],queryOrdinal=None)
        started=now();record['measurements']['indexAttempts']=1;current['actualIndexAttempts']=1
        event('index_attempt_started')
        tick=time.perf_counter();receipt=server.index(server.IndexRequest(projectId=plan['projectId'],chunks=chunks))
        index_call={'kind':'index','startedAtUtc':started,'finishedAtUtc':now(),'wallSeconds':time.perf_counter()-tick,
            'response':receipt,'actualSourceChunkCount':len(chunks),'recipeSignature':digest(recipe)}
        write_json(args.out/'index.response.json',receipt);record['measurements']['calls'].append(index_call);event('index_finished')
        responses=[]
        for ordinal,request in enumerate(plan['requests'],1):
            started=now();record['measurements']['retrievalAttempts']=ordinal;current['actualRetrievalAttempts']=ordinal
            probe_collector.set_context('retrieve',projectId=plan['projectId'],queryOrdinal=ordinal,request=request)
            event('retrieve_attempt_started');tick=time.perf_counter()
            response=server.retrieve(server.RetrieveRequest.model_validate(request))
            call={'ordinal':ordinal,'kind':'retrieve','request':copy.deepcopy(request),'startedAtUtc':started,
                'finishedAtUtc':now(),'wallSeconds':time.perf_counter()-tick,'responseSignature':digest(response),
                'orderedIds':[hit['id']for hit in response['hits']]}
            call_dir=args.out/f'query_{ordinal:02d}';call_dir.mkdir()
            write_json(call_dir/'request.json',request);write_json(call_dir/'response.json',response)
            call['requestArtifact']=descriptor(call_dir/'request.json');call['responseArtifact']=descriptor(call_dir/'response.json')
            record['measurements']['calls'].append(call);responses.append((copy.deepcopy(request),response));event('retrieve_finished')
            print(json.dumps({'finishedRetrievals':ordinal,'planned':len(plan['requests']),'role':request['role']}),flush=True)
        # All index/query receipts and actual full weights are bound by the seal;
        # consumer needs a separately reviewed expected seal SHA, never discovers it itself.
        probe_manifest=probe_collector.finish('completed',indexAttempts=1,retrievalAttempts=len(responses))
        producer={'pipelineProbeManifest':probe_manifest,'recipe':recipe,'frozenSources':initial['producerSources'],'weights':weights,'weightManifestArtifact':descriptor(args.out/'model_files.json'),
            'completedIndexAndQueries':True,'calls':copy.deepcopy(record['measurements']['calls']),
            'executionKind':'actual staged live endpoint functions in-process; zero HTTP calls'}
        seal=seal_completed(state,server.metadata_path(plan['projectId']),receipt,responses,recipe,producer,args.plan,args.plan_sha)
        current.update(status='completed',finishedAtUtc=now(),seal=seal,recipe=recipe,
            calls=copy.deepcopy(record['measurements']['calls']),evaluation={'semanticQualityAccepted':None,'indexAndQueryResultsObserved':True})
        record.update(status='completed',finishedAtUtc=current['finishedAtUtc']);record['measurements']['seal']=copy.deepcopy(seal)
        record['evaluation']=copy.deepcopy(current['evaluation']);record['artifacts'].append(descriptor(state/'sealed_query_cache.json'))
        write_json(args.out/'result.json',current);event('completed')
        return current
    except BaseException as error:
        current['calls']=copy.deepcopy(record['measurements']['calls'])
        current['actualIndexAttempts']=record['measurements']['indexAttempts']
        current['actualRetrievalAttempts']=record['measurements']['retrievalAttempts']
        current.update(status='failed',finishedAtUtc=now(),error={'type':type(error).__name__,'message':str(error),'traceback':traceback.format_exc()})
        write_json(args.out/'failure.json',current)
        record.update(status='failed',finishedAtUtc=current['finishedAtUtc']);record['measurements']['failure']=copy.deepcopy(current['error'])
        record['artifacts'].append(descriptor(args.out/'failure.json'))
        if probe_collector is not None and not (probe_collector.directory/'probe_manifest.json').exists():
            try:
                probe_manifest=probe_collector.finish('failed',indexAttempts=record['measurements']['indexAttempts'],retrievalAttempts=record['measurements']['retrievalAttempts'])
                record['artifacts'].append(probe_manifest)
            except BaseException as diagnostic_error:
                write_json(args.out/'failure_probe_observation.json',{'originalFailurePreserved':descriptor(args.out/'failure.json'),'diagnosticError':{'type':type(diagnostic_error).__name__,'message':str(diagnostic_error)},'actualIndexAttempts':record['measurements']['indexAttempts'],'actualRetrievalAttempts':record['measurements']['retrievalAttempts']})
        event('failed');raise
    finally:
        if server is not None and server._database is not None:server._database.close()
        # This CLI exits after warming; CUDA context is released by process exit.

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan',type=Path,required=True);parser.add_argument('--plan-sha',required=True)
    parser.add_argument('--out',type=Path,required=True);parser.add_argument('--execute',action='store_true')
    parser.add_argument('--experiment-id',default='staged-retrieval-prepare-only')
    parser.add_argument('--log-root',type=Path,default=Path(r'C:\Coding\ConSense\tmp\vetting_experiment_logs'))
    parser.add_argument('--log-module',type=Path,default=Path(r'C:\Coding\ConSense\ai_consense_service\tools\vetting_eval\experiment_log.py'))
    args=parser.parse_args()
    try:run(args)
    except BaseException as error:
        # A pre-ledger filesystem/argument failure must also remain reviewable.
        # Never write into a pre-existing output, or overwrite the run's failure.
        if args.out.exists() and not getattr(args, 'output_created_by_this_invocation', False):
            raise  # Never add a failure marker to a previous experiment's directory.
        if not args.out.exists():
            args.out.mkdir(parents=True)
        if not (args.out/'failure.json').exists() and not (args.out/'bootstrap_failure.json').exists():
            write_json(args.out/'bootstrap_failure.json',{'status':'failed','observedAtUtc':now(),
                'actualIndexAttempts':None,'actualRetrievalAttempts':None,'error':{'type':type(error).__name__,'message':str(error)},
                'operations':'Failure without a terminal sidecar; counts require registry/per-query inspection, not assumed zero'})
        raise

if __name__=='__main__':main()
