"""Read-only completed window-index audit; no server/model/Qdrant client import.

Requires the producer to be stopped and refuses WAL/journal storage. Immutable
SQLite reads bind every stored point to saved source windows and original parents.
The allowlisted pickle global is mapped to an inert object, never instantiated.
"""
import argparse
import base64
import hashlib
import io
import json
import math
from pathlib import Path
import pickle
import sqlite3
import struct
from token_windows import digest,verify_window_coverage,WindowError,fail


def desc(path):
    p=Path(path).resolve();h=hashlib.sha256()
    with p.open('rb')as stream:
        for raw in iter(lambda:stream.read(1024*1024),b''):h.update(raw)
    return {'path':str(p),'bytes':p.stat().st_size,'sha256':h.hexdigest()}


def read_json(path,sha):
    d=desc(path);fail(d['sha256']==sha,'AUDIT_INPUT_CHANGED','External descriptor SHA changed')
    return json.loads(Path(d['path']).read_text(encoding='utf-8')),d


class InertPoint:
    def __setstate__(self,state):self.state=state


class InertDecoder(pickle.Unpickler):
    def find_class(self,module,name):
        if(module,name)==('qdrant_client.http.models.models','PointStruct'):return InertPoint
        raise ValueError('Unapproved pickle global')
    def persistent_load(self,pid):raise ValueError('Persistent pickle reference forbidden')


def decode(raw):return InertDecoder(io.BytesIO(raw)).load()


def validate_metadata(meta,corpus,expected_signature):
    fail(meta.get('contract')=='source-bound-window-points-full-parent-results-v1' and meta.get('signature',{}).get('signatureVersion')==3,
         'OLD_INDEX_CONTRACT','Only a source-bound window index is accepted')
    fail(meta['chunks']==corpus and digest(corpus)==meta['signature']['corpus'] and digest(meta['signature'])==expected_signature,
         'PERSISTED_PARENT_IDENTITY','Metadata parents/signature differ from completed producer source')
    parents={p['id']:p for p in corpus};rows=meta['windows'];bound={w['id']:w for w in rows}
    fail(len(parents)==len(corpus)==meta['parentCount'] and len(rows)==len(bound)==meta['vectorPoints'] and digest(rows)==meta['windowManifestSha256'],
         'WINDOW_MANIFEST_MISMATCH','Parent/window count, IDs or manifest digest differ')
    grouped={p:[]for p in parents}
    for row in rows:
        fail(row['parentId']in grouped,'UNKNOWN_WINDOW_PARENT','Persisted window has no source parent');grouped[row['parentId']].append(row)
    for id_,ww in grouped.items():verify_window_coverage(parents[id_],ww,meta['signature']['windowRecipe']['max_tokens'])
    return parents,bound


def audit(args):
    fail(args.producer_exited,'PRODUCER_RUNNING','Immutable audit requires explicit stopped-producer assertion')
    out=Path(args.out).resolve();fail(not out.exists(),'OUTPUT_EXISTS','Fresh audit output required')
    result,rd=read_json(args.producer_result,args.result_sha256)
    fail(result.get('status')=='completed','INCOMPLETE_PRODUCER','A completed producer result is required')
    meta,md=read_json(args.metadata,args.metadata_sha256);cp=desc(args.corpus)
    fail(cp['sha256']==args.corpus_sha256,'AUDIT_INPUT_CHANGED','Raw corpus SHA changed')
    corpus=[json.loads(line)for line in Path(cp['path']).read_text(encoding='utf-8').splitlines()if line.strip()]
    # Pydantic producer removes top-level null values before signatures/index.
    corpus=sorted([{k:v for k,v in p.items()if v is not None}for p in corpus],key=lambda p:p['id'])
    parents,bound=validate_metadata(meta,corpus,result['indexSignature'])
    state=Path(args.state).resolve();db=(state/'qdrant/collection'/meta['collection']/'storage.sqlite').resolve()
    fail(db.is_relative_to(state) and db.is_file(),'INVALID_STATE_PATH','Saved collection SQLite must be inside declared state')
    fail({p.name for p in db.parent.iterdir()}=={'storage.sqlite'},'LIVE_JOURNAL','WAL/journal/other files need separate live-state audit')
    before=desc(db);qmeta=desc(state/'qdrant/meta.json');qm=json.loads(Path(qmeta['path']).read_text())
    fail(meta['collection']in qm['collections'],'COLLECTION_UNBOUND','Qdrant config does not bind saved collection')
    points=[];seen=set();dimension=args.dimension
    with sqlite3.connect(db.as_uri()+'?mode=ro&immutable=1',uri=True)as connection:
        fail(connection.execute('pragma quick_check').fetchone()[0]=='ok','SQLITE_INTEGRITY','SQLite quick_check failed')
        count=connection.execute('select count(*) from points').fetchone()[0]
        fail(count==len(bound),'PERSISTED_WINDOW_COUNT','Stored points differ from planned windows')
        for key,blob in connection.execute('select id,point from points'):
            stored=decode(blob);fail(isinstance(stored,InertPoint),'INVALID_STORED_POINT','Unexpected inert object')
            fields=stored.state['__dict__'];id_=str(fields['id']);payload=fields['payload']
            fail(id_ in bound and id_ not in seen and decode(base64.b64decode(key))==id_,'PERSISTED_POINT_ID','Stored key/window ID not bound')
            fail(payload=={**bound[id_],'indexSignature':result['indexSignature']},'PERSISTED_POINT_PAYLOAD','Stored window payload differs from exact manifest')
            vector=[float(v)for v in fields['vector']]
            fail(len(vector)==dimension and all(math.isfinite(v)for v in vector),'PERSISTED_VECTOR_INVALID','Persisted vector dimension/finite check failed')
            norm=sum(v*v for v in vector)**.5;fail(abs(norm-1)<=.005,'PERSISTED_VECTOR_NORM','Persisted embedding vector not normalized')
            raw=struct.pack('<'+'f'*len(vector),*vector);seen.add(id_)
            points.append({'windowId':id_,'parentId':payload['parentId'],'sourceHash':payload['sourceHash'],'role':payload['role'],
                           'payloadExact':True,'parentUtf16Span':[payload['parentStartUtf16'],payload['parentEndUtf16']],
                           'dimension':dimension,'vectorFloat32Sha256':hashlib.sha256(raw).hexdigest(),'normFloat64':norm})
    fail(set(bound)==seen and desc(db)==before,'PERSISTED_STATE_CHANGED','Stored scope incomplete or bytes changed during audit')
    out.mkdir(parents=True);ledger=out/'persisted_window_ledger.json';ledger.write_text(json.dumps(points,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    receipt={'protocol':'source-window-index-immutable-persistence-audit-v1','status':'completed',
             'inputs':{'producerResult':rd,'metadata':md,'corpus':cp,'sqlite':before,'qdrantConfig':qmeta},
             'parentCount':len(parents),'windowVectorCount':len(bound),'allWindowPayloadsExact':True,'allParentsZeroGapCovered':True,
             'dimension':dimension,'sqliteUnchanged':True,'quickCheck':'ok','producerExitedAssertedByCaller':True,
             'actualCounts':{'immutableSqliteReadConnection':1,'dbWrite':0,'index':0,'query':0,'encode':0,'predict':0,'modelLoad':0,'http':0,'ocr':0},
             'persistedVectorsEqualEncodeOutputs':'unknown; this tool verifies saved vectors/identity, not probe numerical equality',
             'freshQdrantClientReopen':'not_performed','semanticQuality':None,'outputs':{'windowLedger':desc(ledger)},'software':desc(__file__)}
    receipt_path=out/'audit_receipt.json';receipt_path.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'receipt':desc(receipt_path),'parents':len(parents),'windows':len(bound)}))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('producer-result','result-sha256','metadata','metadata-sha256','corpus','corpus-sha256','state','out'):p.add_argument('--'+name,required=True)
    p.add_argument('--producer-exited',action='store_true');p.add_argument('--dimension',type=int,default=1024)
    audit(p.parse_args())


if __name__=='__main__':main()
