"""Prepare, index and serve a new isolated saved-normal development baseline.

Reuses the existing source-bound tokenizer windows, BGE-M3, Qdrant local writer
and /retrieve API. It never opens an existing live state or canonical registry.
"""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import traceback

def desc(path):
    p = Path(path).resolve(); h = hashlib.sha256()
    with p.open('rb') as stream:
        for piece in iter(lambda: stream.read(1024*1024), b''): h.update(piece)
    return {'path': str(p), 'bytes': p.stat().st_size, 'sha256': h.hexdigest()}

def bound(d):
    actual = desc(d['path'])
    if actual['sha256'] != d['sha256'] or actual['bytes'] != d['bytes']:
        raise ValueError('Baseline artifact changed: '+d['path'])
    return Path(d['path'])

def write(path, value):
    with Path(path).open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2); stream.write('\n')
    return desc(path)

def settings(manifest, state, batch_size, dtype='float32'):
    root = Path(state).resolve()
    if not root.is_relative_to(Path('C:/Users/Administrator/.codex/local-state/consense-oer/fixed-best-baseline-20261012').resolve()):
        raise ValueError('Only task-owned new baseline state is allowed')
    os.environ.update({'CONSENSE_WORKSPACE_ROOT': manifest['inputPlanWorkspace'],
        'CONSENSE_RETRIEVAL_DATA': str(root), 'CONSENSE_MODEL_DEVICE':'cuda',
        'CONSENSE_MODEL_DTYPE':dtype, 'CONSENSE_MODEL_SERIAL_OFFLOAD':'1',
        'CONSENSE_EMBED_MAX_TOKENS':'512', 'CONSENSE_RERANK_MAX_TOKENS':'512',
        'CONSENSE_EMBED_BATCH_SIZE':str(batch_size), 'CONSENSE_RERANK_BATCH_SIZE':'4',
        'CONSENSE_WINDOW_OVERLAP_TOKENS':'64', 'CONSENSE_RETRIEVAL_GRANULARITY':'windows-v1',
        'HF_HUB_OFFLINE':'1', 'TRANSFORMERS_OFFLINE':'1', 'HF_HUB_DISABLE_TELEMETRY':'1',
        'TOKENIZERS_PARALLELISM':'false'})
    for key in ('CONSENSE_FIXED_VECTOR_CACHE', 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST',
        'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256', 'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST',
        'CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST_SHA256', 'CONSENSE_RERANKER_CHECKPOINT_MANIFEST',
        'CONSENSE_RERANKER_CHECKPOINT_MANIFEST_SHA256'):
        os.environ.pop(key, None)
    publications = manifest['models'].get('publications')
    if not isinstance(publications, dict) or set(publications) != {'embedding', 'reranker'}:
        raise ValueError('Both existing immutable model publications are required')
    for kind, publication in publications.items():
        prefix = 'CONSENSE_' + kind.upper() + '_CHECKPOINT_MANIFEST'
        os.environ[prefix] = publication['manifestPath']
        os.environ[prefix + '_SHA256'] = publication['manifestSha256']
    import server
    server.model_path = lambda config: manifest['models']['embeddingSnapshot' if config['name'] == server.MODELS['embedding']['name'] else 'rerankerSnapshot']
    import numpy as np
    def encode_float32_normalized(texts):
        raw = server.embedding_model().encode(texts,
            batch_size=server.MODEL_SETTINGS['embedding']['batchSize'],
            normalize_embeddings=False, convert_to_numpy=True, show_progress_bar=False)
        value = np.asarray(raw, dtype=np.float32)
        norms = np.linalg.norm(value, axis=1, keepdims=True)
        if not np.isfinite(value).all() or not np.isfinite(norms).all() or (norms <= 0).any():
            raise ValueError('Embedding must be finite and have positive FP32 norm')
        return value / norms
    server.encode_embeddings = encode_float32_normalized
    runtime = server.window_runtime(); original_signature = runtime.signature
    entry_identity = desc(__file__)
    def development_signature(chunks):
        value = original_signature(chunks)
        value['developmentRuntimeRecipe'] = {'entryCodeSha256':entry_identity['sha256'],
            'GPUModelDtype':dtype,'embeddingNormalization':'numpy-float32-l2-v1',
            'persistedVectorDtype':'float32-le', 'developmentBaseline':True,
            'nativeCorpusProducer':manifest['code'],
            'tableProjectionProducer':manifest.get('tableProjectionCode')}
        return value
    runtime.signature = development_signature
    return server

def corpus(manifest):
    path = bound(manifest['corpus'])
    rows = []
    with path.open(encoding='utf-8') as stream:
        for line in stream:
            if line.strip(): rows.append(json.loads(line))
    if len(rows) != manifest['parentCount']: raise ValueError('Parent count differs')
    return rows

def prepare(args):
    out = Path(args.out).resolve(); out.mkdir(parents=True, exist_ok=False)
    manifest_desc = desc(args.corpus_manifest); manifest = json.loads(Path(args.corpus_manifest).read_bytes())
    source_plan = json.loads(bound(manifest['inputPlan']).read_bytes())
    manifest['inputPlanWorkspace'] = source_plan['workspace']
    state = Path(args.state).resolve()
    if state.exists(): raise ValueError('Preparation needs a fresh never-opened state path')
    server = settings(manifest, state, args.batch_size, args.dtype)
    rows = sorted([server.Chunk.model_validate(r).model_dump(exclude_none=True) for r in corpus(manifest)], key=lambda x:x['id'])
    server.validate_corpus(rows); runtime = server.window_runtime(); signed = runtime.signature(rows)
    windows = runtime.prepare_windows(rows, 'embedding')
    token_sum = sum(r['fullModelTokens'] for r in windows)
    plan = {'schema':'consense.fixed-development-baseline-runtime-plan.v1',
        'corpusManifest': manifest_desc, 'projectId': manifest['projectId'],
        'state': str(state), 'inputPlanWorkspace':manifest['inputPlanWorkspace'],
        'models':manifest['models'], 'batchSize':args.batch_size, 'dtype':args.dtype,
        'embeddingNormalization':'numpy-float32-l2-v1','persistedVectorDtype':'float32-le',
        'signature':signed, 'indexSignatureSha256':server.digest(signed),
        'parentCount':len(rows), 'windowCount':len(windows), 'fullModelTokens':token_sum,
        'maximumWindowTokens':max(r['fullModelTokens'] for r in windows),
        'windowTokensHistogram':dict(Counter((r['fullModelTokens']//64)*64 for r in windows)),
        'software':{name:desc(Path(__file__).with_name(name)) for name in
          ('fixed_development_baseline_runtime.py','server.py','qdrant_backend.py','window_runtime.py','token_windows.py',
           'retrieval_source_units.py','index_integrity.py','samples.json')},
        'finalQualityAccepted':False, 'canonicalRegistryPublished':False,
        'GPUModelLoads':0, 'databaseOpens':0, 'preparedAtUtc':datetime.now(timezone.utc).isoformat()}
    pd = write(out/'runtime-plan.json',plan)
    print(json.dumps({'plan':pd,'parents':len(rows),'windows':len(windows),'tokens':token_sum,
          'indexSignature':plan['indexSignatureSha256'],'GPUModelLoads':0,'databaseOpens':0}), flush=True)

def load_plan(args):
    d = desc(args.plan)
    if args.expected_plan_sha256 and d['sha256'] != args.expected_plan_sha256:
        raise ValueError('Plan SHA differs from the reviewed plan')
    plan = json.loads(Path(args.plan).read_bytes())
    for artifact in plan['software'].values(): bound(artifact)
    manifest = json.loads(bound(plan['corpusManifest']).read_bytes())
    manifest['inputPlanWorkspace'] = plan['inputPlanWorkspace']
    server = settings(manifest, plan['state'], plan['batchSize'], plan['dtype'])
    return plan, manifest, server, d

def index(args):
    if not args.cuda_lease: raise ValueError('Root CUDA lease is mandatory for actual indexing')
    out = Path(args.out).resolve(); out.mkdir(parents=True, exist_ok=False)
    plan, manifest, server, pd = load_plan(args)
    state = Path(plan['state'])
    if state.exists(): raise ValueError('Actual indexing must own a fresh state; no replacing live collections')
    rows = corpus(manifest); started = time.perf_counter(); events = []; result = None
    receipt = {'schema':'consense.fixed-development-baseline-actual-index.v1','plan':pd,
        'cudaLease':args.cuda_lease,'status':'running','actualExitCode':None,
        'finalQualityAccepted':False,'canonicalRegistryPublished':False,'state':plan['state']}
    def observer(stage,payload):
        if stage == 'window-index-upsert':
            event={'stage':stage,'begin':payload['begin'],
                'encodedWindows':payload['encodedWindowsCumulative'],
                'elapsedSeconds':time.perf_counter()-started}
            events.append(event); print(json.dumps(event),flush=True)
        elif stage == 'window-model-loaded':
            events.append({'stage':stage,**payload})
    server._window_observer = observer
    try:
        import torch
        if not torch.cuda.is_available(): raise ValueError('CUDA unavailable; no CPU fallback')
        torch.set_num_threads(4); torch.set_float32_matmul_precision('highest')
        torch.backends.cuda.matmul.allow_tf32=False; torch.backends.cudnn.allow_tf32=False
        torch.cuda.reset_peak_memory_stats()
        receipt['gpu']={'name':torch.cuda.get_device_name(0),'freeTotalBeforeBytes':list(torch.cuda.mem_get_info()),
                        'torch':torch.__version__,'CUDA':torch.version.cuda}
        normalized=sorted([server.Chunk.model_validate(r).model_dump(exclude_none=True) for r in rows], key=lambda x:x['id'])
        if server.signature(normalized) != plan['signature']: raise ValueError('Actual source/model signature changed')
        result=server.index(server.IndexRequest(projectId=plan['projectId'], chunks=rows))
        if (result['indexed']!=plan['parentCount'] or result['vectorPoints']!=plan['windowCount']
            or result['signature']!=plan['indexSignatureSha256']): raise ValueError('Actual index cardinality/signature differs')
        metadata_path=server.metadata_path(plan['projectId']); metadata=json.loads(metadata_path.read_bytes())
        receipt.update(status='completed_persisted_development_baseline',actualExitCode=0,result=result,
            persistedMetadata=desc(metadata_path),collection=metadata['collection'],
            persistedPointCount=server.database().count(metadata['collection'],exact=True).count,
            vectorIntegrity=metadata['vectorIntegrity'],indexSignature=result['signature'],
            gpuPeakAllocatedBytes=torch.cuda.max_memory_allocated(),gpuPeakReservedBytes=torch.cuda.max_memory_reserved())
    except Exception as error:
        receipt.update(status='failed_partial_state_preserved',actualExitCode=1,
            error={'type':type(error).__name__,'message':str(error),'traceback':traceback.format_exc()})
    finally:
        if server._database is not None:
            server._database.close(); receipt['qdrantClosed']=True
        receipt.update(wallSeconds=time.perf_counter()-started,events=events,
            completedAtUtc=datetime.now(timezone.utc).isoformat())
        rd=write(out/'actual-index-receipt.json',receipt)
        print(json.dumps({'receipt':rd,'status':receipt['status'],'actualExitCode':receipt['actualExitCode'],
            'seconds':receipt['wallSeconds'],'indexSignature':receipt.get('indexSignature')}),flush=True)
    if receipt['actualExitCode']: raise SystemExit(receipt['actualExitCode'])

def serve(args):
    plan, manifest, server, _ = load_plan(args)
    if not server.metadata_path(plan['projectId']).is_file(): raise ValueError('A complete actual baseline index is required')
    if args.host != '127.0.0.1': raise ValueError('Baseline query validation is loopback-only')
    import uvicorn
    uvicorn.run(server.app,host=args.host,port=args.port,workers=1)

def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('mode',choices=('prepare','index','serve'))
    p.add_argument('--corpus-manifest');p.add_argument('--plan');p.add_argument('--expected-plan-sha256')
    p.add_argument('--out');p.add_argument('--state');p.add_argument('--batch-size',type=int,default=16)
    p.add_argument('--dtype',choices=('float32','float16'),default='float32')
    p.add_argument('--cuda-lease');p.add_argument('--host',default='127.0.0.1');p.add_argument('--port',type=int,default=8876)
    args=p.parse_args()
    if args.mode=='prepare':prepare(args)
    elif args.mode=='index':index(args)
    else:serve(args)

if __name__=='__main__':main()
