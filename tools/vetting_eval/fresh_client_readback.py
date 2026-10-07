"""Stopped-producer, byte-exact copied-state Qdrant-client reopening audit.

This explicit audit opens one new local vector client and scrolls stored points.
No model/server/query/encode/index/HTTP/application DB is imported or called.
"""
import argparse
import gc
import importlib.util
import json
from pathlib import Path
import shutil
import sys
from actual_window_retrieval import desc,bound,json_read,external,require,write,digest,utc,validate_cache_bundle


def tree(root):
    root=Path(root).resolve();return [{'relativePath':p.relative_to(root).as_posix(),'file':desc(p)}for p in sorted(root.rglob('*'))if p.is_file()]
def content_tree(rows):return [(r['relativePath'],r['file']['bytes'],r['file']['sha256'])for r in rows]


def expected_vectors(result):
    import numpy as np
    result_vectors={}
    for group in result['actualEncodedFloat32Groups']:
        if group['scope'].get('scope')!='main':continue
        matrix=np.load(bound(group['vectors']),allow_pickle=False)
        require(matrix.dtype==np.float32 and list(matrix.shape)==group['shape']and len(matrix)==len(group['windowIds']),'Original encoded float32 artifact invalid')
        for id_,row in zip(group['windowIds'],matrix):
            require(id_ not in result_vectors,'Duplicate main encoded window output');result_vectors[id_]=row
    return result_vectors


def audit(args):
    require(args.producer_exited,'Producer must be stopped before copying persisted state')
    rd=external(args.producer_result,args.result_sha256);result=json_read(rd);require(result['status']=='completed'and result['qdrantClose']['completed'],'Producer/close not completed')
    md=external(args.metadata,args.metadata_sha256);require(md==result['mainMetadata'],'Main metadata differs from producer bound descriptor')
    meta=json_read(md);require(digest(meta['signature'])==result['indexSignature'],'Main metadata signature differs from actual result')
    rows=meta['windows'];by_id={r['id']:r for r in rows};require(len(by_id)==len(rows)==meta['vectorPoints']==result['indexResult']['vectorPoints'],'Main window count not bound')
    parents={p['id']:p for p in meta['chunks']};require(len(parents)==meta['parentCount']==result['parentCount'],'Parent counts differ')
    plan=json_read(result['plan']);require(meta['chunks']==json_read(plan['normalizedParents'])and digest(meta['chunks'])==plan['normalizedCorpusSha256'],'Parent payload differs from approved original corpus')
    tooling=json_read(plan['inputs']['windowTooling']);token_source=next(row['frozen']for row in tooling['software']if row['relativePath']=='token_windows.py');bound(token_source)
    module_name='frozen_source_window_readback';spec=importlib.util.spec_from_file_location(module_name,token_source['path']);module=importlib.util.module_from_spec(spec);sys.modules[module_name]=module;spec.loader.exec_module(module)
    grouped={id_:[]for id_ in parents}
    for row in rows:
        require(row['parentId']in parents,'Stored window has unknown parent');grouped[row['parentId']].append(row)
    for id_,windows in grouped.items():module.verify_window_coverage(parents[id_],windows,meta['signature']['windowRecipe']['max_tokens'])
    vectors=expected_vectors(result);cache_rows=0
    if plan.get('fixedCacheReadBundle'):
        import numpy as np
        bundle=validate_cache_bundle(plan['fixedCacheReadBundle']);entries={r['key']:r for r in bundle['entries']}
        identity=bundle['embeddingIdentity'];portable={k:v for k,v in meta['signature']['tokenizerAndModelFiles'].items()if k!='resolvedSnapshot'}
        require(portable==identity['tokenizer']and meta['signature']['runtime']['executionMath']==identity['executionMath']and meta['signature']['algorithms']==identity['preprocessing']['algorithms'],'Current cache read model/math/algorithm identity differs')
        sys.path.insert(0,str(Path(token_source['path']).parent));from fixed_vector_cache import lookup
        whitelist=json_read(bundle['whitelist']);loaded={}
        for id_,window in by_id.items():
            if id_ in vectors:continue
            cached,receipt=lookup(bundle['root'],window,whitelist,identity);require(cached is not None and receipt['key']in entries,'Missing point has no actual fixed source vector lineage')
            row=entries[receipt['key']];source=row['encodedSource'];path=bound(source['artifact'])
            if str(path)not in loaded:loaded[str(path)]=np.load(path,allow_pickle=False)
            encoded=loaded[str(path)][source['rowIndex']];require(encoded.dtype==np.float32 and np.array_equal(encoded,np.asarray(cached,dtype=np.float32)),'Bound prebuilt original encoded vector differs from cache bytes')
            vectors[id_]=encoded;cache_rows+=1
    require(set(vectors)==set(by_id),'Original encode/cache outputs do not cover exact persisted main windows')
    state=Path(args.state).resolve();require((state/'qdrant/meta.json').is_file(),'Local state missing');original=tree(state)
    require(not any(r['relativePath'].endswith(('-wal','-shm','-journal'))for r in original),'Uncheckpointed state needs a separate audit')
    out=Path(args.out).resolve();require(not out.exists()and not out.is_relative_to(state),'Fresh audit directory must be outside producer state');out.mkdir(parents=True)
    copied=out/'copied_state';shutil.copytree(state,copied);copied_before=tree(copied);require(content_tree(original)==content_tree(copied_before),'State copy is not byte exact')
    provenance=write(out/'state_copy_provenance.json',{'protocol':'byte-exact-stopped-vector-state-copy-v1','sourceState':str(state),'copyState':str(copied),
        'sourceFiles':original,'copiedFilesBeforeClient':copied_before,'byteExactBeforeClient':True,'producerExitedAssertedByCaller':True,'producerResult':rd})
    ledger=[];seen=set();client=None;scrolls=0;failure=None;constructors=closes=0
    try:
        from qdrant_client import QdrantClient
        import numpy as np
        constructors+=1;client=QdrantClient(path=str(copied/'qdrant'));offset=None
        while True:
            points,offset=client.scroll(collection_name=meta['collection'],limit=args.page_size,offset=offset,with_payload=True,with_vectors=True);scrolls+=1
            for point in points:
                id_=str(point.id);require(id_ in by_id and id_ not in seen,'Fresh client returned unknown/duplicate main point')
                expected=by_id[id_];require(point.payload=={**expected,'indexSignature':result['indexSignature']},'Fresh point payload/source differs from saved window manifest')
                actual=np.asarray(point.vector,dtype=np.float32);encoded=vectors[id_];require(actual.shape==encoded.shape and np.isfinite(actual).all(),'Fresh vector shape/finite invalid')
                exact=bool(np.array_equal(actual,encoded));maxdiff=float(np.max(np.abs(actual.astype(np.float64)-encoded.astype(np.float64))))
                ledger.append({'windowId':id_,'parentId':expected['parentId'],'sourceHash':expected['sourceHash'],'role':expected['role'],'sourceSpans':expected['sourceSpans'],
                    'payloadExact':True,'dimension':len(actual),'equalsOriginalEncodedFloat32Elementwise':exact,'maxAbsDifference':maxdiff,
                    'actualVectorFloat32Sha256':__import__('hashlib').sha256(actual.tobytes()).hexdigest(),'encodedVectorFloat32Sha256':__import__('hashlib').sha256(encoded.tobytes()).hexdigest()})
                seen.add(id_)
            if offset is None:break
        require(seen==set(by_id),'Fresh client did not return every main window point')
    except BaseException as e:failure={'type':type(e).__name__,'message':str(e)}
    finally:
        if client is not None:
            closes+=1
            try:client.close()
            except BaseException as e:failure=failure or {'type':type(e).__name__,'message':str(e)}
            client=None;gc.collect()
    source_after=tree(state);require(content_tree(source_after)==content_tree(original),'Original producer state bytes changed')
    mismatches=sum(not row['equalsOriginalEncodedFloat32Elementwise']for row in ledger)
    ld=write(out/'fresh_point_ledger.json',ledger)
    receipt={'protocol':'fresh-local-vector-client-copied-state-readback-v1','status':'completed'if failure is None and mismatches==0 else'failed',
        'inputs':{'actualResult':rd,'mainMetadata':md,'stateCopyProvenance':provenance},'auditedAtUtc':utc(),'parentCount':len(parents),'plannedWindowCount':len(by_id),
        'observedWindowCount':len(seen),'cacheOriginalEncodedRows':cache_rows,'vectorElementwiseMismatchCount':mismatches,'allOriginalStateBytesUnchanged':True,'failure':failure,'ledger':ld,
        'freshClientCalls':{'constructor':constructors,'scroll':scrolls,'close':closes},'actualCounts':{'model':0,'encode':0,'predict':0,'index':0,'query':0,'HTTP':0,'applicationDB':0,'OCR':0},
        'scope':'Actual new local client opens a byte-exact copied stopped state; it may update only that copy. Full saved window payloads and original encoded float32 outputs are checked. No semantic quality claim.',
        'software':desc(__file__),'copiedStateAfterClient':tree(copied)}
    receipt_desc=write(out/'audit_receipt.json',receipt);print(json.dumps({'status':receipt['status'],'receipt':receipt_desc,'windows':len(seen),'vectorMismatches':mismatches}))
    if receipt['status']!='completed':raise SystemExit(2)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in('producer-result','result-sha256','metadata','metadata-sha256','state','out'):p.add_argument('--'+name,required=True)
    p.add_argument('--producer-exited',action='store_true');p.add_argument('--page-size',type=int,default=256);a=p.parse_args();require(1<=a.page_size<=1024,'Readback page size invalid');audit(a)


if __name__=='__main__':main()
