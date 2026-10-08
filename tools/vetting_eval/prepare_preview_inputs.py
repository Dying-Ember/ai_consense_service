"""File-only bindings for tokenizer diagnostics; not an actual index plan."""
import argparse
import hashlib
import json
from pathlib import Path
from preview_token_windows import desc,read_bound


def prepare(corpus,corpus_sha,audit,audit_sha,old_plan,old_plan_sha,out):
    c,a,p=desc(corpus),desc(audit),desc(old_plan)
    if (c['sha256'],a['sha256'],p['sha256'])!=(corpus_sha,audit_sha,old_plan_sha):raise ValueError('External SHA mismatch')
    receipt=read_bound(a);old=read_bound(p)
    if receipt.get('corpus')!=c or receipt.get('status')!='complete':raise ValueError('Completed source audit does not bind this corpus')
    rows=[json.loads(line)for line in Path(c['path']).read_text(encoding='utf-8').splitlines()]
    if not rows or len({r['id']for r in rows})!=len(rows) or len(rows)!=receipt['chunkCount']:raise ValueError('Duplicate/empty/count mismatch')
    requests=old['requests']
    for row in requests:
        if set(row)!={'originalTopicOrdinal','request'} or not isinstance(row['request'].get('query'),str):raise ValueError('Saved generic request wrapper mismatch')
        if any(k in row['request']for k in ('expected','gold','evaluator','answer')):raise ValueError('Evaluator fields forbidden')
    directory=Path(out).resolve();directory.mkdir(parents=True,exist_ok=False)
    manifest={'protocol':'source-audit-bound-tokenizer-preview-corpus-v1','status':'saved_corpus_no_new_parse',
              'corpus':c,'chunkCount':len(rows),'sourceAudit':a,'actualParse':0,'actualOcr':0,'actualIndex':0,
              'scope':'Saved audited parents are read for tokenizer-only window planning; not a live/fresh corpus build or index.'}
    cp=directory/'corpus_manifest.json';cp.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    plan={'protocol':'tokenizer-only-saved-generic-query-plan-v1','status':'prepared_no_inference','corpusManifest':desc(cp),
          'historicalApprovedPlan':p,'requests':requests,'actualEncode':0,'actualPredict':0,'actualIndex':0,'actualQuery':0,
          'scope':'Byte-identical saved generic query inputs, no evaluator fixtures or answers; old project ID is historical input only.'}
    pp=directory/'query_plan.json';pp.write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return {'corpusManifest':desc(cp),'queryPlan':desc(pp)}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('corpus','corpus-sha256','audit','audit-sha256','old-plan','old-plan-sha256','out'):p.add_argument('--'+name,required=True)
    a=p.parse_args();print(json.dumps(prepare(a.corpus,a.corpus_sha256,a.audit,a.audit_sha256,a.old_plan,a.old_plan_sha256,a.out)))


if __name__=='__main__':main()
