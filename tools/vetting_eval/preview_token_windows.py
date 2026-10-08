"""Offline tokenizer-only window preparation, never loads model weights.

Uses a hash-bound corpus/approved query plan and local frozen tokenizer files.
Output is diagnostic input planning, not an index or fresh retrieval execution.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
import time
from token_windows import WindowRecipe, WindowError, windows, verify_window_coverage, digest


def desc(path):
    p=Path(path).resolve();raw=p.read_bytes();return {"path":str(p),"bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest()}


def read_bound(descriptor):
    actual=desc(descriptor['path'])
    if actual!=descriptor:raise ValueError('Descriptor mismatch')
    return json.loads(Path(actual['path']).read_text(encoding='utf-8'))


class CountingTokenizer:
    def __init__(self,wrapped):self.wrapped=wrapped;self.calls=0
    def __getattr__(self,name):return getattr(self.wrapped,name)
    def __call__(self,*args,**kwargs):self.calls+=1;return self.wrapped(*args,**kwargs)


def local_tokenizer(path):
    from transformers import AutoTokenizer
    tokenizer=CountingTokenizer(AutoTokenizer.from_pretrained(path,local_files_only=True,use_fast=True))
    root=Path(path).resolve()
    artifacts=[desc(root/name) for name in ('tokenizer.json','tokenizer_config.json','special_tokens_map.json','sentencepiece.bpe.model') if(root/name).is_file()]
    identity={"resolvedSnapshot":str(root),"files":artifacts,"fast":tokenizer.is_fast,"backendSha256":hashlib.sha256(tokenizer.backend_tokenizer.to_str().encode()).hexdigest(),
              "modelMaxLength":tokenizer.model_max_length,"class":type(tokenizer.wrapped).__name__}
    return tokenizer,identity


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['corpus-manifest','corpus-sha256','approved-plan','plan-sha256','embedding-tokenizer','rerank-tokenizer','out']:p.add_argument('--'+name,required=True)
    p.add_argument('--max-tokens',type=int,default=512);p.add_argument('--overlap-tokens',type=int,default=64)
    a=p.parse_args();out=Path(a.out)
    if out.exists():raise ValueError('Fresh output directory required')
    cm_desc=desc(a.corpus_manifest);plan_desc=desc(a.approved_plan)
    if cm_desc['sha256']!=a.corpus_sha256 or plan_desc['sha256']!=a.plan_sha256:raise ValueError('External SHA mismatch')
    cm=read_bound(cm_desc);plan=read_bound(plan_desc)
    if plan['corpusManifest']!=cm_desc:raise ValueError('Plan/corpus mismatch')
    raw=Path(cm['corpus']['path'])
    if desc(raw)!=cm['corpus']:raise ValueError('Corpus bytes changed')
    parents=[json.loads(l) for l in raw.read_text(encoding='utf-8').splitlines()]
    if len({c['id']for c in parents})!=len(parents) or len(parents)!=cm['chunkCount']:raise ValueError('Corpus duplicate/count mismatch')
    start=time.perf_counter();embed,ei=local_tokenizer(a.embedding_tokenizer);rerank,ri=local_tokenizer(a.rerank_tokenizer)
    recipe=WindowRecipe(a.max_tokens,a.overlap_tokens);rows=[];failures=[];parent_ledger=[]
    for parent in parents:
        try:
            ww=windows(parent,embed,recipe,ei,embedding_strip=True);coverage=verify_window_coverage(parent,ww,recipe.max_tokens)
            rows.extend(ww);parent_ledger.append({'parentId':parent['id'],'sourceHash':parent['sourceHash'],'documentId':parent['documentId'],'windowIds':[w['id']for w in ww],'coverage':coverage})
        except WindowError as e:failures.append({'parentId':parent['id'],'kind':e.kind,'metadata':e.metadata,'message':str(e)})
    # Query inputs are approved generic requests, never evaluator observations.
    queries=[]
    for item in plan['requests']:
        req=item['request'];query=req['query'];query_summary={'originalTopicOrdinal':item['originalTopicOrdinal'],'querySha256':hashlib.sha256(query.encode()).hexdigest(),'pairWindows':0,'parentsCovered':0,'failures':[]}
        for parent in parents:
            if req.get('role') is not None and req['role']!=parent['role']:continue
            try:
                ww=windows(parent,rerank,recipe,ri,query=query);query_summary['pairWindows']+=len(ww);query_summary['parentsCovered']+=1
            except WindowError as e:query_summary['failures'].append({'parentId':parent['id'],'kind':e.kind,'metadata':e.metadata})
        queries.append(query_summary)
    out.mkdir(parents=True)
    (out/'embedding_windows.jsonl').write_text(''.join(json.dumps(w,ensure_ascii=False)+'\n'for w in rows),encoding='utf-8')
    (out/'parent_coverage.json').write_text(json.dumps(parent_ledger,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    result={'protocol':'offline-tokenizer-only-source-window-plan-v1','status':'complete'if not failures and all(not q['failures']for q in queries)else'planning_failed',
            'preparedAtUtc':datetime.now(timezone.utc).isoformat(),'inputs':{'corpusManifest':cm_desc,'approvedPlan':plan_desc},'recipe':recipe.__dict__,
            'tokenizers':{'embedding':ei,'rerank':ri},'parentCount':len(parents),'coveredParentCount':len(parent_ledger),'windowCount':len(rows),'windowManifestSha256':digest(rows),
            'embeddingWindowMaxFullTokens':max((w['fullModelTokens']for w in rows),default=None),'failures':failures,'queries':queries,
            'tokenizerCalls':{'embedding':embed.calls,'rerank':rerank.calls},'artifacts':{'windows':desc(out/'embedding_windows.jsonl'),'parentCoverage':desc(out/'parent_coverage.json')},
            'actualModelWeightLoads':0,'actualEmbeddingEncode':0,'actualRerankPredict':0,'actualIndex':0,'actualQuery':0,'actualHttp':0,'actualDb':0,'actualOcr':0,
            'software':[desc(__file__),desc(Path(__file__).with_name('token_windows.py'))], 'wallSeconds':time.perf_counter()-start,
            'scope':'All declared parents/pairs planned by actual offline tokenizer only. No index, vector or retrieval quality result. Source spans and full caps are all-or-fail.'}
    (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'parents':len(parents),'covered':len(parent_ledger),'windows':len(rows),'failureCount':len(failures),'result':desc(out/'result.json')}))
    if result['status']!='complete':sys.exit(2)


if __name__=='__main__':main()
