"""Isolated server integration for source-bound windows. No startup inference.

Lazy tokenizer/identity preparation is separate from actual model loading.
Index/retrieve execute only when the caller explicitly invokes server endpoints.
"""
from pathlib import Path
import hashlib
import importlib.metadata
import json
import math
import os
import time
from token_windows import WindowRecipe, WindowError, windows, verify_window_coverage, aggregate_max, dense_expand, digest, text_sha, FullInputTokenizerGuard, fail
from fixed_vector_cache import lookup as fixed_lookup
from retrieval_source_units import select_source_units, POLICY as STRUCTURAL_SELECTION_POLICY
from embedding_compatibility import verified_embedding_algorithms

CONTRACT = "source-bound-window-points-full-parent-results-v1"
SIGNATURE_VERSION = 3
_file_hash_cache = {}
_loaded_source_hashes = {name:hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                        for name in ('window_runtime.py','token_windows.py','fixed_vector_cache.py','server.py','workspace_root.py','retrieval_source_units.py','embedding_compatibility.py')}


def file_identity(path):
    p=Path(path).resolve();stat=p.stat();key=(str(p),stat.st_size,stat.st_mtime_ns)
    if key not in _file_hash_cache:
        h=hashlib.sha256()
        with p.open('rb')as f:
            for raw in iter(lambda:f.read(1024*1024),b''):h.update(raw)
        _file_hash_cache[key]={'relativePath':p.name,'bytes':stat.st_size,'sha256':h.hexdigest()}
    return dict(_file_hash_cache[key])


class WindowRuntime:
    def __init__(self,server):
        self.s=server;self.tokenizers={};self.identities={};self.event_ordinals={}
        self.overlap=int(os.environ.get('CONSENSE_WINDOW_OVERLAP_TOKENS','64'))
        self.cache_root=os.environ.get('CONSENSE_FIXED_VECTOR_CACHE')
        self.cache_whitelist=None
        manifest=os.environ.get('CONSENSE_FIXED_VECTOR_CACHE_WHITELIST')
        expected=os.environ.get('CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256')
        fail(bool(self.cache_root)==bool(manifest), 'FIXED_CACHE_CONFIG', 'Cache root and explicit whitelist must both be provided or absent')
        if manifest:
            raw=Path(manifest).read_bytes();fail(expected and hashlib.sha256(raw).hexdigest()==expected,'FIXED_CACHE_CONFIG','Fixed whitelist external SHA is required')
            self.cache_whitelist=json.loads(raw)

    def emit(self,stage,payload):
        self.event_ordinals[stage]=self.event_ordinals.get(stage,0)+1
        observer=getattr(self.s,'_window_observer',None)
        if observer:observer(stage, {'stageOrdinal':self.event_ordinals[stage],**payload})

    def recipe(self,kind):
        return WindowRecipe(self.s.MODEL_SETTINGS[kind]['maxTokens'],self.overlap)

    def tokenizer(self,kind):
        if kind not in self.tokenizers:
            from transformers import AutoTokenizer
            root=Path(self.s.model_path(self.s.MODELS[kind])).resolve()
            tokenizer=AutoTokenizer.from_pretrained(str(root),local_files_only=True,use_fast=True)
            fail(tokenizer.is_fast,'OFFSET_TOKENIZER_REQUIRED','Fast offline tokenizer required')
            # Full model/tokenizer/config bytes are fingerprinted, never loaded
            # into a neural engine by this identity preparation.
            names=('tokenizer.json','tokenizer_config.json','special_tokens_map.json','sentencepiece.bpe.model','config.json','sentence_bert_config.json','modules.json','config_sentence_transformers.json','model.safetensors','pytorch_model.bin')
            files=[file_identity(root/name)|{'relativePath':name}for name in names if(root/name).is_file()]
            pooling=root/'1_Pooling/config.json'
            if pooling.is_file():files.append(file_identity(pooling)|{'relativePath':'1_Pooling/config.json'})
            identity={'model':self.s.MODELS[kind], 'resolvedSnapshot':str(root),'files':files,
                      'tokenizerClass':type(tokenizer).__name__,'fast':True,'backendSha256':text_sha(tokenizer.backend_tokenizer.to_str()),
                      'modelMaxLength':tokenizer.model_max_length,
                      'packages':{name:importlib.metadata.version(name)for name in ('transformers','tokenizers','sentence-transformers','torch')}}
            self.tokenizers[kind]=tokenizer;self.identities[kind]=identity
            self.emit('offline-tokenizer-identity',{'kind':kind,'identity':identity,'modelWeightLoads':0})
        return self.tokenizers[kind]

    def identity(self,kind):self.tokenizer(kind);return self.identities[kind]

    def algorithms(self):
        current={name:file_identity(Path(__file__).with_name(name))['sha256']for name in _loaded_source_hashes}
        fail(current==_loaded_source_hashes,'SOFTWARE_CHANGED_SINCE_IMPORT','Source bytes differ from the package actually imported')
        return dict(_loaded_source_hashes)

    def embedding_algorithms(self):
        return verified_embedding_algorithms(Path(__file__).parent, self.algorithms())

    def signature(self,chunks):
        runtime=self.s.model_runtime('embedding')
        return {'signatureVersion':self.signature_version(),'contract':CONTRACT,'embedding':self.s.MODELS['embedding'],'runtime':runtime,
                'normalized':True,'corpus':self.s.digest(chunks),'windowRecipe':self.recipe('embedding').__dict__,
                'embeddingPreprocessing':{'strip':True,'lowerCase':False,'version':'sentence-transformers-strip-no-lower-v1'},
                'tokenizerAndModelFiles':self.identity('embedding'),'algorithms':self.embedding_algorithms()}

    def signature_version(self):
        return SIGNATURE_VERSION

    def retrieval_recipe(self,*,observe_tokenizer_identity=True):
        return {'contract':CONTRACT,'denseAggregation':'max','rerankAggregation':'max','embeddingWindows':self.recipe('embedding').__dict__,
                'rerankWindows':self.recipe('reranker').__dict__,'rerankRuntime':self.s.model_runtime('reranker'),
                'rerankTokenizerAndModelFiles':self.identity('reranker')if observe_tokenizer_identity else None,'algorithms':self.algorithms(),
                **({}if observe_tokenizer_identity else {'tokenizerIdentityObserved':False,'identityScope':'configured recipe; tokenizer/model identity not loaded by health'}),
                'structuralSelection':{'policy':STRUCTURAL_SELECTION_POLICY,'topKScope':'unique_reliable_structural_units_or_unknown_single_seeds','closureMembersAreRanked':False,'scope':'canonical_observed_structure_only'},
                'limitations':'Max over windows favors long parents with more opportunities; structural closure proves only observed source ranges, not whole-clause semantic consistency. Top2-mean/length calibration is a separate unimplemented experiment.'}

    def embedding_cache_identity(self,dimension):
        # Absolute loader location is execution provenance, not portable model
        # content. The exact local model/tokenizer files and revisions remain.
        portable={k:v for k,v in self.identity('embedding').items()if k!='resolvedSnapshot'}
        return {'model':self.s.MODELS['embedding'],'tokenizer':portable,'windowRecipe':self.recipe('embedding').__dict__,
                'preprocessing':{'strip':True,'lowerCase':False,'algorithms':self.embedding_algorithms()},
                'runtimeDtype':self.s.model_runtime('embedding')['dtype'],'vectorDimension':dimension}

    def prepare_windows(self,chunks,kind,query=None):
        recipe=self.recipe(kind);tokenizer=self.tokenizer(kind);identity=self.identity(kind)
        rows=[]
        for c in chunks:rows.extend(windows(c,tokenizer,recipe,identity,query=query,embedding_strip=kind=='embedding'))
        fail(len({w['id']for w in rows})==len(rows),'WINDOW_ID_COLLISION','Window IDs must be globally unique in the project plan')
        self.emit('source-window-plan',{'kind':kind,'querySha256':text_sha(query)if query is not None else None,'parents':len(chunks),'windows':len(rows),
                                     'windowManifestSha256':digest(rows),'windowBindings':rows,'allParentsZeroGapCovered':True})
        return rows

    def guard(self,tokenizer,kind):
        return FullInputTokenizerGuard(tokenizer,self.s.MODEL_SETTINGS[kind]['maxTokens'],lambda p:self.emit('inference-full-input-tokenizer',{'kind':kind,**p}))

    def validate_metadata(self,metadata):
        fail(metadata.get('contract')==CONTRACT and metadata.get('signature',{}).get('signatureVersion')==self.signature_version(),'OLD_INDEX_CONTRACT','Old parent-vector index cannot be reused as a window index')
        rows=metadata['windows'];parents={p['id']:p for p in metadata['chunks']}
        fail(metadata['windowManifestSha256']==digest(rows)and metadata['vectorPoints']==len(rows),'WINDOW_MANIFEST_MISMATCH','Saved window manifest/hash/count differs')
        grouped={id_:[]for id_ in parents}
        fail(len({w['id']for w in rows})==len(rows),'WINDOW_ID_COLLISION','Saved windows duplicate IDs')
        for w in rows:
            fail(w['parentId']in parents,'UNKNOWN_WINDOW_PARENT','Window references absent parent')
            grouped[w['parentId']].append(w)
        for id_,ww in grouped.items():verify_window_coverage(parents[id_],ww,self.recipe('embedding').max_tokens)
        return parents,{w['id']:w for w in rows}

    def index(self,request):
        from qdrant_client import models
        import numpy as np
        s=self.s;key=s.project_key(request.projectId);chunks=sorted([c.model_dump(exclude_none=True)for c in request.chunks],key=lambda c:c['id'])
        s.validate_corpus(chunks);signed=self.signature(chunks);collection='project_'+s.digest(key)[:16]+'_'+s.digest(signed)[:16]
        with s.LOCK:
            path=s.metadata_path(key)
            if path.exists():
                existing=json.loads(path.read_text(encoding='utf-8'))
                if existing.get('signature')==signed and existing.get('contract')==CONTRACT and(not chunks or s.database().collection_exists(collection)):
                    self.validate_metadata(existing);s._projects[key]=existing
                    return {'projectId':key,'indexed':len(chunks),'vectorPoints':existing['vectorPoints'],'signature':s.digest(signed),'cached':True,'contract':CONTRACT}
            started=time.perf_counter();rows=self.prepare_windows(chunks,'embedding');cache_hits=encoded=0
            try:
                if chunks:
                    model,db=s.embedding_model(),s.database();dimension=int(model.get_sentence_embedding_dimension())
                    if not db.collection_exists(collection):db.create_collection(collection,vectors_config=models.VectorParams(size=dimension,distance=models.Distance.COSINE))
                    cache_identity=self.embedding_cache_identity(dimension)
                    for begin in range(0,len(rows),128):
                        group=rows[begin:begin+128];vectors=[None]*len(group);missing=[]
                        for i,w in enumerate(group):
                            eligible=self.cache_whitelist and any(x.get('sourceHash')==w['sourceHash']and x.get('role')==w['role']for x in self.cache_whitelist.get('sources',[]))
                            if self.cache_root and eligible:
                                vector,receipt=fixed_lookup(self.cache_root,w,self.cache_whitelist,cache_identity)
                                self.emit('fixed-vector-cache-read',{'windowId':w['id'],'parentId':w['parentId'],'receipt':receipt})
                                if vector is not None:vectors[i]=np.asarray(vector,dtype=np.float32);cache_hits+=1
                            if vectors[i]is None:missing.append(i)
                        if missing:
                            values=s.encode_embeddings([group[i]['content']for i in missing]);fail(len(values)==len(missing),'ENCODE_ROW_COUNT','Encoded vector count differs from windows')
                            encoded+=len(missing)
                            for i,v in zip(missing,values):vectors[i]=v
                        for vector in vectors:fail(len(vector)==dimension and np.isfinite(vector).all(),'INVALID_EMBEDDING_VECTOR','Embedding vectors require exact dimension and finite values')
                        db.upsert(collection,points=[models.PointStruct(id=w['id'],vector=v.tolist(),payload={**w,'indexSignature':s.digest(signed)})for w,v in zip(group,vectors)])
                        self.emit('window-index-upsert',{'begin':begin,'windowIds':[w['id']for w in group],'parentIds':[w['parentId']for w in group],
                                                        'vectors':np.asarray(vectors).tolist(),'dimension':dimension,'fixedCacheHitsCumulative':cache_hits,'encodedWindowsCumulative':encoded})
                    fail(db.count(collection,exact=True).count==len(rows),'INDEX_POINT_COUNT','Persisted vector point count differs from windows')
                metadata={'contract':CONTRACT,'projectId':key,'signature':signed,'collection':collection,'chunks':chunks,'windows':rows,'windowManifestSha256':digest(rows),
                          'vectorPoints':len(rows),'parentCount':len(chunks),'fixedCacheHits':cache_hits,'encodedWindows':encoded,'createdAt':time.time()}
                path.parent.mkdir(parents=True,exist_ok=True);pending=path.with_suffix('.json.pending');pending.write_text(json.dumps(metadata,ensure_ascii=False),encoding='utf-8');pending.replace(path)
                s._projects[key]=metadata
                return {'projectId':key,'indexed':len(chunks),'vectorPoints':len(rows),'fixedCacheHits':cache_hits,'encodedWindows':encoded,'signature':s.digest(signed),'cached':False,'contract':CONTRACT,'seconds':time.perf_counter()-started}
            except WindowError as e:raise s.HTTPException(503,{'kind':e.kind,'message':str(e),'metadata':e.metadata})from e
            except Exception as e:raise s.HTTPException(503,{'kind':'WINDOW_INDEX_UNAVAILABLE','type':type(e).__name__,'message':str(e)})from e

    def rerank(self,query,candidates):
        import numpy as np
        if not candidates:return np.asarray([],dtype=np.float32)
        rows=self.prepare_windows(candidates,'reranker',query);values=self.s.rerank_model().predict([(query,w['content'])for w in rows],
            batch_size=self.s.MODEL_SETTINGS['reranker']['batchSize'],show_progress_bar=False,convert_to_numpy=True)
        scores=np.asarray(values).reshape(-1);fail(len(scores)==len(rows)and np.isfinite(scores).all(),'RERANK_SCORE_COUNT','Rerank score count/finite values differ from pair windows')
        parents={c['id']:c for c in candidates};bound={w['id']:w for w in rows};aggregated=aggregate_max([(w['id'],float(v))for w,v in zip(rows,scores)],bound,parents)
        by_parent={r['id']:r['score']for r in aggregated}
        self.emit('rerank-window-scores',{'querySha256':text_sha(query),'pairsInActualInputOrder':[{'windowId':w['id'],'parentId':w['parentId'],'fullTokens':w['fullModelTokens']}for w in rows],
                                         'scoresInActualPairOrder':scores.tolist(),'parentsInTrueScoreOrder':[{'id':r['id'],'score':r['score'],'winningWindowId':r['winningWindowId']}for r in aggregated]})
        return np.asarray([by_parent[c['id']]for c in candidates])

    def retrieve(self,request):
        from qdrant_client import models
        from rank_bm25 import BM25Okapi
        import numpy as np
        s=self.s
        if request.limit>request.candidates:raise s.HTTPException(422,'limit cannot exceed candidates')
        if request.role is not None and request.role not in s.ROLES:raise s.HTTPException(422,'Unsupported evidence role')
        key=s.project_key(request.projectId)
        with s.LOCK:
            metadata=s.read_project(key);parents,bound=self.validate_metadata(metadata)
            scope=[c for c in metadata['chunks']if request.role is None or c['role']==request.role]
            if not scope:
                hits,units=select_source_units(metadata['chunks'],[],request.limit)
                return {'hits':hits,'sourceUnits':units,'mode':CONTRACT,'projectId':key,'indexSignature':s.digest(metadata['signature']),'emptyScope':True}
            try:
                started=time.perf_counter();tokenizer=self.tokenizer('embedding')
                full_query=len(tokenizer(request.query.strip(),add_special_tokens=True,truncation=False)['input_ids'])
                fail(full_query<=self.recipe('embedding').max_tokens,'QUERY_TOKEN_BUDGET_EXHAUSTED','Full embedding query exceeds frozen cap',fullTokens=full_query,maxTokens=self.recipe('embedding').max_tokens)
                vector=s.encode_embeddings([request.query])[0]
                filt=models.Filter(must=[models.FieldCondition(key='role',match=models.MatchValue(value=request.role))])if request.role else None
                eligible={id_:w for id_,w in bound.items()if request.role is None or w['role']==request.role}
                fetch_ordinal=0
                def fetch(limit):
                    nonlocal fetch_ordinal
                    fetch_ordinal+=1;points=s.database().query_points(metadata['collection'],query=vector.tolist(),limit=limit,query_filter=filt).points;result=[]
                    fail(len(points)==min(limit,len(eligible)),'DENSE_WINDOW_SCOPE_INCOMPLETE','Vector provider returned fewer points than declared role/window scope')
                    for p in points:
                        id_=str(p.id);fail(id_ in eligible,'UNKNOWN_DENSE_WINDOW','Dense point is outside bound role/window scope')
                        fail(p.payload=={**eligible[id_],'indexSignature':s.digest(metadata['signature'])},'DENSE_POINT_PAYLOAD_MISMATCH','Dense window payload differs from bound manifest')
                        result.append((id_,float(p.score)))
                    self.emit('dense-window-fetch',{'fetchOrdinal':fetch_ordinal,'requestedWindowLimit':limit,'actualWindowHits':[{'windowId':id_,'parentId':bound[id_]['parentId'],'score':score}for id_,score in result]})
                    return result
                dense_rows,receipt=dense_expand(fetch,eligible,parents,parent_limit=min(request.candidates,len(scope)),eligible_window_count=len(eligible))
                self.emit('dense-parent-candidates',{'requestedCandidates':request.candidates,'scopeParents':len(scope),'scopeWindows':len(eligible),'expansion':receipt,
                                                   'orderedParents':[{'id':r['id'],'score':r['score'],'winningWindowId':r['winningWindowId']}for r in dense_rows]})
                dense=[r['payload']for r in dense_rows];terms=[s.tokens(c['content'])for c in scope];lexical=[];lexical_scores=[]
                if any(terms):
                    lexical_scores=BM25Okapi(terms).get_scores(s.tokens(request.query));lexical=[scope[i]for i in np.argsort(-lexical_scores)[:request.candidates]if lexical_scores[i]>0]
                self.emit('parent-bm25-candidates',{'allScopeScores':[{'id':c['id'],'score':float(v)}for c,v in zip(scope,lexical_scores)],'orderedCandidateIds':[c['id']for c in lexical]})
                fused,lookup_={},{}
                for found in(dense,lexical):
                    for rank,c in enumerate(found,1):fused[c['id']]=fused.get(c['id'],0)+1/(s.RRF_CONSTANT+rank);lookup_[c['id']]=c
                candidate_ids=sorted(fused,key=lambda id_:(-fused[id_],id_))[:request.candidates];candidates=[lookup_[id_]for id_ in candidate_ids]
                self.emit('parent-rrf-candidates',{'constant':s.RRF_CONSTANT,'allFusedInOrder':[{'id':id_,'score':fused[id_]}for id_ in sorted(fused,key=lambda id_:(-fused[id_],id_))],'submittedParentIds':candidate_ids})
                scores=np.asarray(s.rerank_candidates(request.query,candidates)).reshape(-1);order=sorted(range(len(candidates)),key=lambda i:(-float(scores[i]),candidates[i]['id']))
                raw=[{'id':candidates[i]['id'],'score':float(scores[i]),'payload':candidates[i]}for i in order]
                hits,units=select_source_units(metadata['chunks'],raw,request.limit)
                self.emit('parent-final-ranking',{'allRerankedInTrueScoreOrder':[{'id':h['id'],'score':h['score']}for h in raw], 'returnedIds':[h['id']for h in hits], 'rawParentTopKIds':units['rawParentTopKIds'],'selectionPolicy':STRUCTURAL_SELECTION_POLICY})
                self.emit('source-unit-closure',units)
                return {'hits':hits,'sourceUnits':units,'mode':CONTRACT,'projectId':key,
                        'indexSignature':s.digest(metadata['signature']),'seconds':time.perf_counter()-started,'denseWindowExpansion':receipt,
                        'scoreMeaning':'Max pair-window reranker score, not evidence validity or probability of a contract defect'}
            except WindowError as e:raise s.HTTPException(422 if e.kind=='QUERY_TOKEN_BUDGET_EXHAUSTED' else 503,{'kind':e.kind,'message':str(e),'metadata':e.metadata})from e
            except s.HTTPException:raise
            except Exception as e:raise s.HTTPException(503,{'kind':'WINDOW_RETRIEVAL_UNAVAILABLE','type':type(e).__name__,'message':str(e)})from e
