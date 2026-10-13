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
from retrieval_source_units import (select_source_units, POLICY as STRUCTURAL_SELECTION_POLICY,
                                    OBSERVED_POLICY as OBSERVED_SELECTION_POLICY, HIERARCHY_POLICY, ATTACHMENT_POLICY)
from embedding_compatibility import verified_embedding_algorithms
from structural_retrieval import (StructuralRecipe, CONTRACT as STRUCTURAL_CONTRACT,
                                  plan as structural_plan, compare as structural_compare,
                                  aggregate_points)
from preparation_cache import PreparationCache
from checkpoint_identity import checkpoint_inventory
from checkpoint_publication import CheckpointPublication
from index_integrity import inspect_points, verify_reuse, vector_sha256, PROTOCOL as INDEX_INTEGRITY_PROTOCOL

CONTRACT = "source-bound-window-points-full-parent-results-v1"
SIGNATURE_VERSION = 3
_loaded_source_hashes = {name:hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                        for name in ('window_runtime.py','token_windows.py','fixed_vector_cache.py','server.py','qdrant_backend.py','workspace_root.py','retrieval_source_units.py','embedding_compatibility.py','structural_retrieval.py','preparation_cache.py','checkpoint_identity.py','checkpoint_publication.py','index_integrity.py','migrated_qdrant_index.py','migrate_qdrant_server.py','fixed_development_baseline_runtime.py','verify_retrieval_offline.py')}


def file_identity(path):
    p=Path(path).resolve();h=hashlib.sha256();size=0
    with p.open('rb')as f:
        for raw in iter(lambda:f.read(1024*1024),b''):h.update(raw);size+=len(raw)
    return {'relativePath':p.name,'bytes':size,'sha256':h.hexdigest()}


class WindowRuntime:
    def __init__(self,server):
        self.s=server;self.tokenizers={};self.identities={};self.event_ordinals={}
        self.prepared_structures=PreparationCache()
        self.model_publications={};self.model_publication_settings={}
        for kind in ('embedding','reranker'):
            prefix='CONSENSE_'+kind.upper()+'_CHECKPOINT_MANIFEST'
            path,expected=os.environ.get(prefix),os.environ.get(prefix+'_SHA256')
            fail(bool(path)==bool(expected),'MODEL_PUBLICATION_CONFIG','Publication path and external SHA must be supplied together')
            if path:
                fail(getattr(server,'_'+('embedding' if kind=='embedding' else 'reranker'),None) is None,
                     'MODEL_PUBLICATION_ALREADY_LOADED','Configure model publication before any model instance is loaded')
                self.model_publications[kind]=CheckpointPublication(path,expected,server.MODELS[kind])
                self.model_publication_settings[kind]=(path,expected)
        if self.model_publications:self.bind_model_publications()
        self.structural_recipe=StructuralRecipe.from_environment(os.environ)
        self.overlap=int(os.environ.get('CONSENSE_WINDOW_OVERLAP_TOKENS','64'))
        self.cache_root=os.environ.get('CONSENSE_FIXED_VECTOR_CACHE')
        self.cache_whitelist=None
        manifest=os.environ.get('CONSENSE_FIXED_VECTOR_CACHE_WHITELIST')
        expected=os.environ.get('CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256')
        fail(bool(self.cache_root)==bool(manifest), 'FIXED_CACHE_CONFIG', 'Cache root and explicit whitelist must both be provided or absent')
        if manifest:
            raw=Path(manifest).read_bytes();fail(expected and hashlib.sha256(raw).hexdigest()==expected,'FIXED_CACHE_CONFIG','Fixed whitelist external SHA is required')
            self.cache_whitelist=json.loads(raw)

    def bind_model_publications(self):
        original_path=self.s.model_path
        def model_path(config):
            for kind,publication in self.model_publications.items():
                if config==publication.model:
                    self.publication_identity(kind)
                    return str(publication.snapshot)
            return original_path(config)
        self.s.model_path=model_path
        for kind,function_name in (('embedding','embedding_model'),('reranker','rerank_model')):
            if kind not in self.model_publications:continue
            original=getattr(self.s,function_name)
            def load(original=original,kind=kind):
                publication=self.publication_identity(kind)
                model=original()
                bound=getattr(model,'_consense_checkpoint_publication',None)
                fail(bound is None or bound==publication['publicationSha256'],'MODEL_INSTANCE_PUBLICATION_MISMATCH',
                     'Loaded model instance belongs to a different publication; start a new runtime')
                model._consense_checkpoint_publication=publication['publicationSha256']
                return model
            setattr(self.s,function_name,load)

    def publication_identity(self,kind):
        publication=self.model_publications[kind]
        prefix='CONSENSE_'+kind.upper()+'_CHECKPOINT_MANIFEST'
        fail((os.environ.get(prefix),os.environ.get(prefix+'_SHA256'))==self.model_publication_settings[kind]
             and self.s.MODELS[kind]==publication.model,'MODEL_PUBLICATION_VERSION_SWITCH',
             'Publication configuration changed; version switches require a new runtime')
        return publication.identity()

    def checkpoint_files(self,kind,root):
        if kind in self.model_publications:
            publication=self.publication_identity(kind)
            fail(root==self.model_publications[kind].snapshot.resolve(),'MODEL_PUBLICATION_LOADER_PATH_MISMATCH',
                 'Tokenizer and model loader must use the exact immutable published snapshot')
            return publication['files']
        return checkpoint_inventory(root,file_identity)

    def emit(self,stage,payload):
        self.event_ordinals[stage]=self.event_ordinals.get(stage,0)+1
        observer=getattr(self.s,'_window_observer',None)
        if observer:observer(stage, {'stageOrdinal':self.event_ordinals[stage],**payload})

    def recipe(self,kind):
        return WindowRecipe(self.s.MODEL_SETTINGS[kind]['maxTokens'],self.overlap)

    def contract(self):
        return STRUCTURAL_CONTRACT if self.structural_recipe.enabled else CONTRACT

    def selection_policy(self):
        return STRUCTURAL_SELECTION_POLICY if self.structural_recipe.enabled else OBSERVED_SELECTION_POLICY

    def inference_cap(self,kind):
        short=self.recipe(kind).max_tokens
        if not self.structural_recipe.enabled:return short
        longer=self.structural_recipe.long_embedding_tokens if kind=='embedding' else self.structural_recipe.context_rerank_tokens
        fail(longer>=short,'STRUCTURAL_RECIPE','Structural model budget must cover the baseline cap')
        return longer

    def tokenizer(self,kind):
        if kind not in self.tokenizers:
            from transformers import AutoTokenizer
            root=Path(self.s.model_path(self.s.MODELS[kind])).resolve()
            files=self.checkpoint_files(kind,root)
            tokenizer=AutoTokenizer.from_pretrained(str(root),local_files_only=True,use_fast=True)
            fail(tokenizer.is_fast,'OFFSET_TOKENIZER_REQUIRED','Fast offline tokenizer required')
            # Full model/tokenizer/config bytes are fingerprinted, never loaded
            # into a neural engine by this identity preparation.
            fail(files==self.checkpoint_files(kind,root),'MODEL_CHANGED_DURING_PREPARATION',
                 'Checkpoint/tokenizer/recipe bytes changed while preparing the offline tokenizer')
            identity={'model':self.s.MODELS[kind], 'resolvedSnapshot':str(root),'files':files,
                      'tokenizerClass':type(tokenizer).__name__,'fast':True,'backendSha256':text_sha(tokenizer.backend_tokenizer.to_str()),
                      'modelMaxLength':tokenizer.model_max_length,
                      **({'checkpointPublication':self.publication_identity(kind)}if kind in self.model_publications else {}),
                      'packages':{name:importlib.metadata.version(name)for name in ('transformers','tokenizers','sentence-transformers','torch')}}
            self.tokenizers[kind]=tokenizer;self.identities[kind]=identity
            self.emit('offline-tokenizer-identity',{'kind':kind,'identity':identity,'modelWeightLoads':0})
        return self.tokenizers[kind]

    def identity(self,kind):
        self.tokenizer(kind);identity=self.identities[kind]
        root=Path(self.s.model_path(self.s.MODELS[kind])).resolve()
        fail(str(root)==identity['resolvedSnapshot'] and self.s.MODELS[kind]==identity['model']
             and self.checkpoint_files(kind,root)==identity['files'],
             'MODEL_CHANGED_SINCE_PREPARATION','Checkpoint/tokenizer/shard/recipe bytes changed; restart with a frozen matching snapshot')
        return identity

    def algorithms(self):
        current={name:file_identity(Path(__file__).with_name(name))['sha256']for name in _loaded_source_hashes}
        fail(current==_loaded_source_hashes,'SOFTWARE_CHANGED_SINCE_IMPORT','Source bytes differ from the package actually imported')
        return dict(_loaded_source_hashes)

    def embedding_algorithms(self):
        return verified_embedding_algorithms(Path(__file__).parent, self.algorithms())

    def signature(self,chunks):
        runtime=self.s.model_runtime('embedding')
        return {'signatureVersion':self.signature_version(),'contract':self.contract(),'embedding':self.s.MODELS['embedding'],'runtime':runtime,
                'structuralSelectionPolicy':self.selection_policy(),
                'normalized':True,'corpus':self.s.digest(chunks),'windowRecipe':self.recipe('embedding').__dict__,
                'embeddingPreprocessing':{'strip':True,'lowerCase':False,'version':'sentence-transformers-strip-no-lower-v1'},
                'tokenizerAndModelFiles':self.identity('embedding'),'algorithms':self.embedding_algorithms(),
                **({'structuralRecipe':self.structural_recipe.identity(),'sourceOrderBasis':'canonical_request_order'} if self.structural_recipe.enabled else {})}

    def signature_version(self):
        return 4 if self.structural_recipe.enabled else SIGNATURE_VERSION

    def retrieval_recipe(self,*,observe_tokenizer_identity=True):
        return {'contract':self.contract(),'denseAggregation':'independent_lane_rrf' if self.structural_recipe.enabled else 'max','rerankAggregation':'observed_structure_context_max_with_same_pool_baseline' if self.structural_recipe.enabled else 'max','embeddingWindows':self.recipe('embedding').__dict__,
                'rerankWindows':self.recipe('reranker').__dict__,'rerankRuntime':self.s.model_runtime('reranker'),
                'rerankTokenizerAndModelFiles':self.identity('reranker')if observe_tokenizer_identity else None,'algorithms':self.algorithms(),
                **({}if observe_tokenizer_identity else {'tokenizerIdentityObserved':False,'identityScope':'configured recipe; tokenizer/model identity not loaded by health'}),
                'structuralSelection':{'policy':self.selection_policy(),
                    **({'hierarchyContextPolicy':HIERARCHY_POLICY, 'attachmentContextPolicy':ATTACHMENT_POLICY} if self.structural_recipe.enabled else {}),
                    'topKScope':'unique_reliable_structural_units_or_unknown_single_seeds','closureMembersAreRanked':False,'scope':'canonical_observed_structure_only'},
                **({'structuralRecipe':self.structural_recipe.identity(),'longEmbeddingMaxTokens':self.inference_cap('embedding'),'contextRerankMaxTokens':self.inference_cap('reranker')} if self.structural_recipe.enabled else {}),
                'limitations':'Max over groups favors long parents with more opportunities; observed structural context does not establish semantic completeness or quality improvement. Necessary-evidence recall and same-pool ranking require separate labels/evaluation.'}

    def embedding_cache_identity(self,dimension):
        # Absolute loader location is execution provenance, not portable model
        # content. The exact local model/tokenizer files and revisions remain.
        portable={k:v for k,v in self.identity('embedding').items()if k!='resolvedSnapshot'}
        return {'model':self.s.MODELS['embedding'],'tokenizer':portable,'windowRecipe':self.recipe('embedding').__dict__,
                'structuralSelectionPolicy':self.selection_policy(),
                'preprocessing':{'strip':True,'lowerCase':False,'algorithms':self.embedding_algorithms()},
                'runtimeDtype':self.s.model_runtime('embedding')['dtype'],'vectorDimension':dimension,
                **({'structuralRecipe':self.structural_recipe.identity()} if self.structural_recipe.enabled else {})}

    def prepare_windows(self,chunks,kind,query=None):
        recipe=self.recipe(kind);tokenizer=self.tokenizer(kind);identity=self.identity(kind)
        rows=[]
        for c in chunks:rows.extend(windows(c,tokenizer,recipe,identity,query=query,embedding_strip=kind=='embedding'))
        fail(len({w['id']for w in rows})==len(rows),'WINDOW_ID_COLLISION','Window IDs must be globally unique in the project plan')
        self.emit('source-window-plan',{'kind':kind,'querySha256':text_sha(query)if query is not None else None,'parents':len(chunks),'windows':len(rows),
                                     'windowManifestSha256':digest(rows),'windowBindings':rows,'allParentsZeroGapCovered':True})
        return rows

    def guard(self,tokenizer,kind):
        return FullInputTokenizerGuard(tokenizer,self.inference_cap(kind),lambda p:self.emit('inference-full-input-tokenizer',{'kind':kind,**p}))

    def prepared_structure(self,chunks):
        tokenizer=self.tokenizer('embedding');identity=self.identity('embedding')
        short=self.recipe('embedding');structure=self.structural_recipe
        key=digest({'chunks':chunks,'tokenizerAndModelFiles':identity,'runtime':self.s.model_runtime('embedding'),
                    'shortRecipe':short.__dict__,'structuralRecipe':structure.identity(),'algorithms':self.algorithms(),
                    'sourcePageEvidence':getattr(self,'source_page_index_pins',None).get() if hasattr(self,'source_page_index_pins') else None})
        return self.prepared_structures.compute(key,lambda:structural_plan(chunks,tokenizer,identity,short,structure))

    def validate_metadata(self,metadata):
        fail(metadata.get('contract')==self.contract() and metadata.get('signature',{}).get('signatureVersion')==self.signature_version(),'OLD_INDEX_CONTRACT','Index contract/mode differs from the explicitly configured recipe')
        rows=metadata['windows'];parents={p['id']:p for p in metadata['chunks']}
        fail(metadata['windowManifestSha256']==digest(rows)and metadata['vectorPoints']==len(rows),'WINDOW_MANIFEST_MISMATCH','Saved window manifest/hash/count differs')
        if self.structural_recipe.enabled:
            planned=self.prepared_structure(metadata['chunks'])
            fail(rows==planned['points'] and metadata.get('structureContexts')==planned['contexts'] and
                 metadata.get('structureBindings')==planned['bindings'] and metadata.get('structuralCoverage')==planned['coverage'],
                 'STRUCTURAL_MANIFEST_MISMATCH','Dual points, contexts or unknown coverage differ from canonical source plan')
            return parents,{w['id']:w for w in rows}
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
        s=self.s;key=s.project_key(request.projectId);chunks=[c.model_dump(exclude_none=True)for c in request.chunks]
        if not self.structural_recipe.enabled:chunks.sort(key=lambda c:c['id'])
        s.validate_corpus(chunks);signed=self.signature(chunks);collection='project_'+s.digest(key)[:16]+'_'+s.digest(signed)[:16]
        with s.LOCK:
            path=s.metadata_path(key)
            if path.exists():
                existing=json.loads(path.read_text(encoding='utf-8'))
                if existing.get('signature')==signed and existing.get('contract')==self.contract():
                    s._projects.pop(key,None)
                    self.validate_metadata(existing)
                    try:verify_reuse(s.database() if chunks else None,existing,key=key,collection=collection,chunks=chunks,signature=signed)
                    except WindowError:raise
                    except Exception as error:
                        raise WindowError('INDEX_REUSE_UNAVAILABLE','Complete persisted index integrity cannot be verified') from error
                    s._projects[key]=existing
                    return {'projectId':key,'indexed':len(chunks),'vectorPoints':existing['vectorPoints'],'signature':s.digest(signed),'cached':True,'contract':self.contract()}
            started=time.perf_counter();structure=None
            if self.structural_recipe.enabled:
                structure=self.prepared_structure(chunks)
                rows=structure['points'];self.emit('dual-granularity-source-plan',structure)
            else:rows=self.prepare_windows(chunks,'embedding')
            cache_hits=encoded=0;persisted_vector_hashes={};dimension=0
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
                        for window,vector in zip(group,vectors):persisted_vector_hashes[window['id']]=vector_sha256(vector.tolist(),dimension)
                        db.upsert(collection,points=[models.PointStruct(id=w['id'],vector=v.tolist(),payload={**w,'indexSignature':s.digest(signed)})for w,v in zip(group,vectors)])
                        self.emit('window-index-upsert',{'begin':begin,'windowIds':[w['id']for w in group],'parentIds':[w['parentId']for w in group],
                                                        'vectors':np.asarray(vectors).tolist(),'dimension':dimension,'fixedCacheHitsCumulative':cache_hits,'encodedWindowsCumulative':encoded})
                    fail(db.count(collection,exact=True).count==len(rows),'INDEX_POINT_COUNT','Persisted vector point count differs from windows')
                metadata={'contract':self.contract(),'projectId':key,'signature':signed,'collection':collection,'chunks':chunks,'windows':rows,'windowManifestSha256':digest(rows),
                          'vectorPoints':len(rows),'parentCount':len(chunks),'fixedCacheHits':cache_hits,'encodedWindows':encoded,'createdAt':time.time()}
                integrity=inspect_points(db,collection,rows,signed,dimension) if chunks else {
                    'schema':INDEX_INTEGRITY_PROTOCOL,'dimension':0,'dtype':'float32-le','pointCount':0,
                    'embeddingSignatureSha256':digest(signed),'pointsSha256':digest([])}
                integrity['persistedPointsSha256']=digest([{'id':key,'vectorSha256':persisted_vector_hashes[key]} for key in sorted(persisted_vector_hashes)])
                integrity['sealSha256']=digest(integrity)
                metadata['vectorIntegrity']=integrity
                if structure:metadata.update(structureContexts=structure['contexts'],structureBindings=structure['bindings'],structuralCoverage=structure['coverage'])
                path.parent.mkdir(parents=True,exist_ok=True);pending=path.with_suffix('.json.pending');pending.write_text(json.dumps(metadata,ensure_ascii=False),encoding='utf-8');pending.replace(path)
                s._projects[key]=metadata
                return {'projectId':key,'indexed':len(chunks),'vectorPoints':len(rows),'fixedCacheHits':cache_hits,'encodedWindows':encoded,'signature':s.digest(signed),'cached':False,'contract':self.contract(),'seconds':time.perf_counter()-started,
                        **({'structuralCoverage':structure['coverage']} if structure else {})}
            except WindowError as e:raise s.HTTPException(503,{'kind':e.kind,'message':str(e),'metadata':e.metadata})from e
            except Exception as e:raise s.HTTPException(503,{'kind':'WINDOW_INDEX_UNAVAILABLE','type':type(e).__name__,'message':str(e)})from e

    def rerank(self,query,candidates,all_parents=None):
        import numpy as np
        if not candidates:return np.asarray([],dtype=np.float32)
        if self.structural_recipe.enabled:
            fail(all_parents is not None,'MISSING_CANONICAL_STRUCTURE_SCOPE','Dual rerank needs the entire canonical source scope')
            def scorer(pairs):
                return np.asarray(self.s.rerank_model().predict(pairs,batch_size=self.s.MODEL_SETTINGS['reranker']['batchSize'],
                    show_progress_bar=False,convert_to_numpy=True)).reshape(-1)
            comparison=structural_compare(query,candidates,all_parents,self.tokenizer('reranker'),self.identity('reranker'),
                                          self.recipe('reranker'),self.structural_recipe,scorer)
            self.emit('rerank-same-pool-structure-comparison',comparison)
            return np.asarray([row['score']for row in comparison['results']])
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
                hits,units=select_source_units(metadata['chunks'],[],request.limit,
                                              hierarchy_context=self.structural_recipe.enabled)
                return {'hits':hits,'sourceUnits':units,'mode':self.contract(),'projectId':key,'indexSignature':s.digest(metadata['signature']),'emptyScope':True}
            try:
                started=time.perf_counter();tokenizer=self.tokenizer('embedding')
                full_query=len(tokenizer(request.query.strip(),add_special_tokens=True,truncation=False)['input_ids'])
                fail(full_query<=self.recipe('embedding').max_tokens,'QUERY_TOKEN_BUDGET_EXHAUSTED','Full embedding query exceeds frozen cap',fullTokens=full_query,maxTokens=self.recipe('embedding').max_tokens)
                vector=s.encode_embeddings([request.query])[0]
                eligible={id_:w for id_,w in bound.items()if request.role is None or w['role']==request.role}
                def dense_lane(granularity=None):
                    lane={id_:w for id_,w in eligible.items()if granularity is None or w.get('granularity')==granularity}
                    conditions=[]
                    if request.role:conditions.append(models.FieldCondition(key='role',match=models.MatchValue(value=request.role)))
                    if granularity:conditions.append(models.FieldCondition(key='granularity',match=models.MatchValue(value=granularity)))
                    filt=models.Filter(must=conditions)if conditions else None
                    fetch_ordinal=0
                    def fetch(limit):
                        nonlocal fetch_ordinal
                        fetch_ordinal+=1;points=s.database().query_points(metadata['collection'],query=vector.tolist(),limit=limit,query_filter=filt).points;result=[]
                        fail(len(points)==min(limit,len(lane)),'DENSE_WINDOW_SCOPE_INCOMPLETE','Vector provider returned fewer points than declared role/granularity scope')
                        for p in points:
                            id_=str(p.id);fail(id_ in lane,'UNKNOWN_DENSE_WINDOW','Dense point is outside bound role/granularity scope')
                            fail(p.payload=={**lane[id_],'indexSignature':s.digest(metadata['signature'])},'DENSE_POINT_PAYLOAD_MISMATCH','Dense window payload differs from bound manifest')
                            result.append((id_,float(p.score)))
                        self.emit('dense-window-fetch',{'granularity':granularity,'fetchOrdinal':fetch_ordinal,'requestedWindowLimit':limit,
                            'actualWindowHits':[{'windowId':id_,'parentId':bound[id_]['parentId'],'score':score}for id_,score in result]})
                        return result
                    options={'aggregator':aggregate_points}if granularity is not None else {}
                    rows,receipt=dense_expand(fetch,lane,parents,parent_limit=min(request.candidates,len(scope)),eligible_window_count=len(lane),**options)
                    self.emit('dense-parent-candidates',{'granularity':granularity,'requestedCandidates':request.candidates,'scopeParents':len(scope),
                        'scopeWindows':len(lane),'expansion':receipt,'orderedParents':[{'id':r['id'],'score':r['score'],'winningWindowId':r['winningWindowId']}for r in rows]})
                    return [row['payload']for row in rows],receipt
                if self.structural_recipe.enabled:
                    short,short_receipt=dense_lane('short');long,long_receipt=dense_lane('structure')
                    dense_pools=[short,long];receipt={'lanes':{'short':short_receipt,'structure':long_receipt},'fusion':'independent_lane_rrf'}
                else:
                    dense,receipt=dense_lane();dense_pools=[dense]
                terms=[s.tokens(c['content'])for c in scope];lexical=[];lexical_scores=[]
                if any(terms):
                    lexical_scores=BM25Okapi(terms).get_scores(s.tokens(request.query));lexical=[scope[i]for i in np.argsort(-lexical_scores)[:request.candidates]if lexical_scores[i]>0]
                self.emit('parent-bm25-candidates',{'allScopeScores':[{'id':c['id'],'score':float(v)}for c,v in zip(scope,lexical_scores)],'orderedCandidateIds':[c['id']for c in lexical]})
                fused,lookup_={},{}
                for found in(*dense_pools,lexical):
                    for rank,c in enumerate(found,1):fused[c['id']]=fused.get(c['id'],0)+1/(s.RRF_CONSTANT+rank);lookup_[c['id']]=c
                candidate_ids=sorted(fused,key=lambda id_:(-fused[id_],id_))[:request.candidates];candidates=[lookup_[id_]for id_ in candidate_ids]
                self.emit('parent-rrf-candidates',{'constant':s.RRF_CONSTANT,'allFusedInOrder':[{'id':id_,'score':fused[id_]}for id_ in sorted(fused,key=lambda id_:(-fused[id_],id_))],'submittedParentIds':candidate_ids})
                ranked=self.rerank(request.query,candidates,metadata['chunks'])if self.structural_recipe.enabled else s.rerank_candidates(request.query,candidates)
                scores=np.asarray(ranked).reshape(-1);order=sorted(range(len(candidates)),key=lambda i:(-float(scores[i]),candidates[i]['id']))
                raw=[{'id':candidates[i]['id'],'score':float(scores[i]),'payload':candidates[i]}for i in order]
                hits,units=select_source_units(metadata['chunks'],raw,request.limit,
                                              hierarchy_context=self.structural_recipe.enabled)
                self.emit('parent-final-ranking',{'allRerankedInTrueScoreOrder':[{'id':h['id'],'score':h['score']}for h in raw], 'returnedIds':[h['id']for h in hits], 'rawParentTopKIds':units['rawParentTopKIds'],'selectionPolicy':units['policy']})
                self.emit('source-unit-closure',units)
                return {'hits':hits,'sourceUnits':units,'mode':self.contract(),'projectId':key,
                        'indexSignature':s.digest(metadata['signature']),'seconds':time.perf_counter()-started,'denseWindowExpansion':receipt,
                        'scoreMeaning':'Observed structure-context relevance, compared with the same-pool short-window baseline; not evidence validity or defect probability'if self.structural_recipe.enabled else 'Max pair-window reranker score, not evidence validity or probability of a contract defect',
                        **({'structuralCoverage':metadata['structuralCoverage'],'rerankPolicy':'same_pool_observed_structure_context'}if self.structural_recipe.enabled else {})}
            except WindowError as e:raise s.HTTPException(422 if e.kind=='QUERY_TOKEN_BUDGET_EXHAUSTED' else 503,{'kind':e.kind,'message':str(e),'metadata':e.metadata})from e
            except s.HTTPException:raise
            except Exception as e:raise s.HTTPException(503,{'kind':'WINDOW_RETRIEVAL_UNAVAILABLE','type':type(e).__name__,'message':str(e)})from e
