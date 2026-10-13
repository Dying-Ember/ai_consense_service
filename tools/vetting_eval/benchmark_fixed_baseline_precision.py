"""Bounded real CUDA FP32/FP16 probe; never opens a vector database."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
from fixed_development_baseline_runtime import settings, corpus, desc, write

QUERIES=[
    'GCC variation instruction and notice for additional payment and extension of time',
    'GCT tender submission acceptance contract conditions and tender validity period',
    'SL concrete compressive strength testing sampling requirements and acceptance criteria']

def worker(args):
    import torch
    import numpy as np
    manifest=json.loads(Path(args.manifest).read_bytes())
    source=json.loads(Path(manifest['inputPlan']['path']).read_bytes())
    manifest['inputPlanWorkspace']=source['workspace']
    server=settings(manifest,args.state,16,args.dtype)
    rows=sorted([server.Chunk.model_validate(r).model_dump(exclude_none=True)for r in corpus(manifest)],key=lambda r:r['id'])
    windows=server.window_runtime().prepare_windows(rows,'embedding')
    ordered=sorted(windows,key=lambda w:(w['fullModelTokens'],w['id']))
    chosen=[ordered[round(i*(len(ordered)-1)/95)]for i in range(96)]
    if len({w['id']for w in chosen})!=96:raise ValueError('Sample requires96 unique real windows')
    torch.set_num_threads(4);torch.set_float32_matmul_precision('highest')
    torch.backends.cuda.matmul.allow_tf32=False;torch.backends.cudnn.allow_tf32=False
    if not torch.cuda.is_available():raise ValueError('CUDA unavailable')
    torch.cuda.reset_peak_memory_stats();torch.cuda.synchronize();start=time.perf_counter()
    server.embedding_model();torch.cuda.synchronize();loaded=time.perf_counter()-start
    start=time.perf_counter();vectors=server.encode_embeddings([w['content']for w in chosen]);torch.cuda.synchronize()
    elapsed=time.perf_counter()-start
    start=time.perf_counter();queries=server.encode_embeddings(QUERIES);torch.cuda.synchronize();query_elapsed=time.perf_counter()-start
    output=Path(args.out);output.mkdir(parents=True,exist_ok=False)
    np.savez(output/'vectors.npz',documents=vectors,queries=queries)
    value={'dtype':args.dtype,'selectedWindowIds':[w['id']for w in chosen],
        'parentIds':[w['parentId']for w in chosen],'fullModelTokens':[w['fullModelTokens']for w in chosen],
        'queries':QUERIES,'actualDocumentWindows':96,'actualQueryEncodes':3,
        'modelLoadSeconds':loaded,'encodeSeconds':elapsed,'querySeconds':query_elapsed,
        'gpu':torch.cuda.get_device_name(0),'peakAllocatedBytes':torch.cuda.max_memory_allocated(),
        'peakReservedBytes':torch.cuda.max_memory_reserved(),'vectorFile':desc(output/'vectors.npz'),
        'databaseOpens':0,'fullCorpusQualityAccepted':False}
    write(output/'worker-receipt.json',value);print(json.dumps(value),flush=True)

def run(args):
    if not args.cuda_lease:raise ValueError('RootCUDA lease required')
    out=Path(args.out);out.mkdir(parents=True,exist_ok=False)
    results={}
    for dtype in ('float32','float16'):
        command=[sys.executable,__file__,'worker','--manifest',args.manifest,'--state',args.state,
            '--dtype',dtype,'--out',str(out/dtype)]
        with (out/(dtype+'.stdout.log')).open('x',encoding='utf-8')as stdout,(out/(dtype+'.stderr.log')).open('x',encoding='utf-8')as stderr:
            result=subprocess.run(command,stdout=stdout,stderr=stderr)
        if result.returncode:raise ValueError(dtype+'actual benchmark failed; log retained')
        results[dtype]=json.loads((out/dtype/'worker-receipt.json').read_bytes())
    import numpy as np
    a=np.load(out/'float32'/'vectors.npz');b=np.load(out/'float16'/'vectors.npz')
    if results['float32']['selectedWindowIds']!=results['float16']['selectedWindowIds']:raise ValueError('Precision sample differs')
    cosine=np.sum(a['documents']*b['documents'],axis=1);qc=np.sum(a['queries']*b['queries'],axis=1)
    orders=[]
    for i,query in enumerate(QUERIES):
        scores32=a['documents']@a['queries'][i];scores16=b['documents']@b['queries'][i]
        top32=np.argsort(-scores32)[:10].tolist();top16=np.argsort(-scores16)[:10].tolist()
        common=len(set(top32)&set(top16));topone=(top32[0]==top16[0])
        orders.append({'query':query,'fp32Top10SamplePositions':top32,'fp16Top10SamplePositions':top16,
            'top10Common':common,'topOneSame':topone,'maximumScoreDifference':float(np.max(np.abs(scores32-scores16)))})
    numerical=bool(cosine.min()>=0.999 and qc.min()>=0.999)
    ordering=all(o['topOneSame']and o['top10Common']>=9 for o in orders)
    faster=results['float16']['encodeSeconds']<results['float32']['encodeSeconds']
    receipt={'schema':'consense.bounded-fp32-fp16-baseline-probe.v1','cudaLease':args.cuda_lease,
        'manifest':desc(args.manifest),'code':desc(__file__),'runtimeEntry':desc(Path(__file__).with_name('fixed_development_baseline_runtime.py')),
        'results':results,'minimumDocumentCosine':float(cosine.min()),'minimumQueryCosine':float(qc.min()),
        'querySampleOrders':orders,'sampleNumericalPass':numerical,'sampleOrderingPass':ordering,
        'fp16Faster':faster,'candidateFP16Admissible':numerical and ordering and faster,
        'selectedWindowCount':96,'queryCount':3,'databaseOpens':0,'fullCorpusRetrievalQualityAccepted':False,
        'normalizationAndStoredVectorDtype':'float32','comparisonScope':'bounded96 real windows; no source-label or full-corpus quality claim'}
    print(json.dumps({'receipt':write(out/'precision-comparison.json',receipt),**{k:v for k,v in receipt.items()if k in
        ('minimumDocumentCosine','minimumQueryCosine','sampleOrderingPass','fp16Faster','candidateFP16Admissible')}}),flush=True)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=('worker','run'))
    p.add_argument('--manifest',required=True);p.add_argument('--state',required=True);p.add_argument('--out',required=True)
    p.add_argument('--dtype',choices=('float32','float16'));p.add_argument('--cuda-lease')
    args=p.parse_args();worker(args)if args.mode=='worker'else run(args)
if __name__=='__main__':main()
