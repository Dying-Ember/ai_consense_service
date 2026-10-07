"""Local OCR and dense/BM25/rerank API. Run one worker: python server.py."""
from __future__ import annotations

import argparse
import hashlib
import io
import inspect
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid

HERE = Path(__file__).resolve().parent
WORKSPACE = Path(os.environ.get('CONSENSE_WORKSPACE_ROOT', r'C:\Coding\ConSense')).resolve()
os.environ.setdefault('HF_HOME', str(WORKSPACE / 'tmp/vetting_models'))
os.environ.setdefault('HF_HUB_DISABLE_TELEMETRY', '1')
os.environ.setdefault('TOKENIZERS_PARALLELISM', 'false')

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel, ConfigDict, Field, field_validator
from staged_cache import CacheError, SealedRetrieval

CONFIG = json.loads((HERE / 'models.json').read_text(encoding='utf-8'))
MODELS = {key: CONFIG[key] for key in ('embedding', 'reranker')}
ROLES = {'tender', 'standard', 'project_fact', 'package_manifest'}


def model_settings(environ):
    """Resolve independent startup settings without importing an inference engine."""
    result = {}
    for kind, prefix in (('embedding', 'CONSENSE_EMBED'), ('reranker', 'CONSENSE_RERANK')):
        selected = {}
        for field, suffix, default in (('maxTokens', 'MAX_TOKENS', 512), ('batchSize', 'BATCH_SIZE', 4)):
            name = prefix + '_' + suffix
            try:
                value = int(environ.get(name, str(default)))
            except (ValueError, TypeError) as error:
                raise ValueError(name + ' must be a positive integer') from error
            if value <= 0 or (field == 'maxTokens' and value > 8192):
                raise ValueError(name + (' must be between 1 and 8192' if field == 'maxTokens' else ' must be positive'))
            selected[field] = value
        result[kind] = selected
    return result


MODEL_SETTINGS = model_settings(os.environ)
# Compatibility fields describe embedding only; independent values are in runtime.
MAX_TOKENS = MODEL_SETTINGS['embedding']['maxTokens']
BATCH_SIZE = MODEL_SETTINGS['embedding']['batchSize']
RRF_CONSTANT = 60
STATE = Path(os.environ.get('CONSENSE_RETRIEVAL_DATA', str(WORKSPACE / 'tmp/vetting_server'))).resolve()
OFFLINE = os.environ.get('CONSENSE_MODEL_OFFLINE', '1').lower() not in {'0', 'false', 'no'}
LOCK = threading.RLock()
_embedding = _reranker = _ocr = _database = None
_projects = {}
_selected_device = None
app = FastAPI(title='ConSense staged vetting retrieval', version='1.0.0')
STAGED_MODE = os.environ.get('CONSENSE_RETRIEVAL_MODE', 'live')
if STAGED_MODE not in {'live', 'cache_only'}:
    raise ValueError('CONSENSE_RETRIEVAL_MODE must be live or cache_only')
_sealed_cache = None
PROBE = None

def probe(stage, payload):
    if PROBE is not None:
        return PROBE.emit(stage, payload)


def staged_recipe():
    # This introspection reads distribution metadata/source files, never imports models.
    import importlib.metadata
    import platform
    from staged_cache import digest
    if STAGED_MODE == 'cache_only':
        selected = os.environ.get('CONSENSE_CACHE_BUILD_DEVICE')
        dtype = os.environ.get('CONSENSE_CACHE_BUILD_DTYPE')
        if selected not in {'cuda', 'cpu'} or dtype != ('float16' if selected == 'cuda' else 'float32'):
            raise CacheError('Explicit frozen index build device/dtype required', 503)
        embedding = {**MODEL_SETTINGS['embedding'], **MODELS['embedding'], 'device': selected, 'dtype': dtype}
        reranker = {**MODEL_SETTINGS['reranker'], **MODELS['reranker'], 'device': selected, 'dtype': dtype}
    else:
        embedding, reranker = model_runtime('embedding'), model_runtime('reranker')
    tracked = {
        'rank_bm25': ['rank_bm25.py'],
        'sentence-transformers': ['sentence_transformers/SentenceTransformer.py', 'sentence_transformers/cross_encoder/CrossEncoder.py', 'sentence_transformers/models/Transformer.py'],
        'transformers': ['transformers/modeling_utils.py', 'transformers/tokenization_utils_base.py'],
        'qdrant-client': ['qdrant_client/local/qdrant_local.py'],
    }
    installed_sources = {}
    for distribution, paths in tracked.items():
        installed = importlib.metadata.distribution(distribution)
        for relative in paths:
            path = Path(installed.locate_file(relative))
            installed_sources[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    packages = {name: importlib.metadata.version(name) for name in ('torch', 'sentence-transformers', 'transformers', 'rank_bm25', 'qdrant-client', 'numpy', 'pydantic')}
    retrieval = {'requestDefaults': {'candidates': 50, 'limit': 10}, 'rrfConstant': 60,
        'rrfWeights': {'dense': 1, 'bm25': 1},
        'bm25': {'implementation': 'rank_bm25.BM25Okapi', 'parameterSource': 'installed library defaults', 'k1': 1.5, 'b': 0.75, 'epsilon': 0.25},
        'tokenPattern': r'[a-z0-9]+(?:[./()-][a-z0-9]+)*', 'roleFilter': True, 'storage': 'Qdrant Python local', 'workers': 1}
    if STAGED_MODE == 'live' and retrieval != retrieval_settings():
        raise CacheError('Installed retrieval defaults changed before precomputation')
    return {'indexSignatureBase': {'signatureVersion': 2, 'embedding': MODELS['embedding'],
        'maxTokens': embedding['maxTokens'], 'batchSize': embedding['batchSize'],
        'device': embedding['device'], 'dtype': embedding['dtype'], 'normalized': True},
        'reranker': reranker, 'retrieval': retrieval, 'packages': packages,
        'installedSourceSha256': installed_sources,
        'pythonVersion': platform.python_version(),
        'producerSources': {'serverSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'cacheModuleSha256': hashlib.sha256((HERE/'staged_cache.py').read_bytes()).hexdigest(),
            'modelConfigSha256': digest(MODELS),
            'probeModuleSha256': hashlib.sha256((HERE/'pipeline_probe.py').read_bytes()).hexdigest()}}

def sealed_cache():
    global _sealed_cache
    if _sealed_cache is None:
        _sealed_cache = SealedRetrieval(STATE, os.environ.get('CONSENSE_CACHE_SEAL_SHA256'), staged_recipe(),
            os.environ.get('CONSENSE_CACHE_PLAN_SHA256'))
    return _sealed_cache

def cache_call(method, *args):
    try:
        return getattr(sealed_cache(), method)(*args)
    except CacheError as error:
        raise HTTPException(error.status, str(error)) from error



class Chunk(BaseModel):
    model_config = ConfigDict(extra='allow')
    id: str
    content: str = Field(min_length=1, max_length=200000)
    role: str = 'tender'
    fileKey: str | None = None
    documentId: int | str | None = None
    anchor: str | None = None
    pageNo: int | None = None

    @field_validator('pageNo', mode='before')
    @classmethod
    def physical_page_number(cls, value):
        # The Java evidence DTO uses P12; normalize it to the same physical page
        # integer accepted from direct Python callers. Never accept Word anchors
        # or infer a page from a clause identifier.
        if value is None:
            return None
        if isinstance(value, str) and re.fullmatch(r'P?[1-9]\d*', value):
            return int(value.removeprefix('P'))
        if isinstance(value, int) and not isinstance(value, bool) and value > 0:
            return value
        raise ValueError('pageNo must be a positive physical page integer or P-prefixed page number')


class IndexRequest(BaseModel):
    projectId: int | str
    chunks: list[Chunk] = Field(max_length=50000)


class RetrieveRequest(BaseModel):
    projectId: int | str
    query: str = Field(min_length=1, max_length=20000)
    role: str | None = None
    limit: int = Field(default=10, ge=1, le=100)
    candidates: int = Field(default=50, ge=1, le=500)


def project_key(value):
    key = str(value).strip()
    if not key or len(key) > 200:
        raise HTTPException(422, 'Invalid projectId')
    return key


def digest(value):
    serialized = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(serialized.encode('utf-8')).hexdigest()


def tokens(value):
    return re.findall(r'[a-z0-9]+(?:[./()-][a-z0-9]+)*', value.casefold())


def device():
    global _selected_device
    if _selected_device is not None:
        return _selected_device
    import torch
    choice = os.environ.get('CONSENSE_MODEL_DEVICE', 'auto')
    if choice not in {'auto', 'cpu', 'cuda'}:
        raise RuntimeError('CONSENSE_MODEL_DEVICE must be auto, cpu or cuda')
    if choice == 'cuda' and not torch.cuda.is_available():
        raise RuntimeError('CUDA explicitly requested but unavailable')
    torch.set_num_threads(4)
    _selected_device = ('cuda' if torch.cuda.is_available() else 'cpu') if choice == 'auto' else choice
    return _selected_device


def model_options():
    import torch
    selected = device()
    return selected, {'torch_dtype': torch.float16 if selected == 'cuda' else torch.float32}


def model_runtime(kind):
    selected = device()
    return {**MODEL_SETTINGS[kind], **MODELS[kind], 'device': selected,
            'dtype': 'float16' if selected == 'cuda' else 'float32'}


def retrieval_settings():
    from rank_bm25 import BM25Okapi
    parameters = inspect.signature(BM25Okapi).parameters
    return {'requestDefaults': {'candidates': 50, 'limit': 10},
            'rrfConstant': RRF_CONSTANT, 'rrfWeights': {'dense': 1, 'bm25': 1},
            'bm25': {'implementation': 'rank_bm25.BM25Okapi',
                     'parameterSource': 'installed library defaults',
                     **{key: parameters[key].default for key in ('k1', 'b', 'epsilon')}},
            'tokenPattern': r'[a-z0-9]+(?:[./()-][a-z0-9]+)*', 'roleFilter': True,
            'storage': 'Qdrant Python local', 'workers': 1}


def model_path(config):
    # An absolute snapshot path prevents Transformers adapter probes reaching
    # Hugging Face even when individual loaders receive local_files_only=True.
    from huggingface_hub import snapshot_download
    return snapshot_download(config['name'], revision=config['revision'], local_files_only=OFFLINE)


def embedding_model():
    global _embedding
    if _embedding is None:
        from sentence_transformers import SentenceTransformer
        selected, options = model_options()
        _embedding = SentenceTransformer(model_path(MODELS['embedding']),
            device=selected, model_kwargs=options, local_files_only=OFFLINE)
        _embedding.max_seq_length = MODEL_SETTINGS['embedding']['maxTokens']
    return _embedding


def rerank_model():
    global _reranker
    if _reranker is None:
        from sentence_transformers import CrossEncoder
        selected, options = model_options()
        _reranker = CrossEncoder(model_path(MODELS['reranker']),
            device=selected, model_kwargs=options, max_length=MODEL_SETTINGS['reranker']['maxTokens'], local_files_only=OFFLINE)
    return _reranker


def encode_embeddings(texts):
    return embedding_model().encode(texts, batch_size=MODEL_SETTINGS['embedding']['batchSize'],
        normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False)


def rerank_candidates(query, candidates):
    return rerank_model().predict([(query, c['content']) for c in candidates],
        batch_size=MODEL_SETTINGS['reranker']['batchSize'], show_progress_bar=False, convert_to_numpy=True)


def database():
    global _database
    if _database is None:
        from qdrant_client import QdrantClient
        STATE.mkdir(parents=True, exist_ok=True)
        _database = QdrantClient(path=str(STATE / 'qdrant'))
    return _database


def metadata_path(key):
    return STATE / 'projects' / (digest(key) + '.json')


def signature(chunks):
    runtime = model_runtime('embedding')
    return {'signatureVersion': 2, 'embedding': MODELS['embedding'],
            'maxTokens': runtime['maxTokens'], 'batchSize': runtime['batchSize'],
            'device': runtime['device'], 'dtype': runtime['dtype'],
            'normalized': True, 'corpus': digest(chunks)}


def read_project(key):
    stored = _projects.get(key)
    if stored is None:
        path = metadata_path(key)
        if not path.exists():
            raise HTTPException(404, 'Project is not indexed')
        stored = json.loads(path.read_text(encoding='utf-8'))
    if stored.get('projectId') != key or stored.get('signature') != signature(stored['chunks']):
        raise HTTPException(409, 'Index signature changed; re-index current documents')
    if stored['chunks'] and not database().collection_exists(stored['collection']):
        raise HTTPException(409, 'Index collection is missing; re-index current documents')
    _projects[key] = stored
    return stored


def validate_corpus(chunks):
    ids = set()
    for chunk in chunks:
        if chunk['id'] in ids:
            raise HTTPException(422, 'Duplicate chunk id: ' + chunk['id'])
        ids.add(chunk['id'])
        if chunk['role'] not in ROLES:
            raise HTTPException(422, 'Unsupported evidence role')
        if any(field in chunk for field in ('expected', 'gold_provenance', 'prohibited_claims')):
            raise HTTPException(422, 'Evaluation answers cannot enter the evidence index')
        for field in ('path', 'source', 'fileName', 'filename', 'sourcePath'):
            path = str(chunk.get(field, '')).replace('\\', '/').casefold()
            if any(marker in path for marker in ('2d_tender doc vetting comments',
                    '2e_questions and answers', 'vetting_cases.json')):
                raise HTTPException(422, 'Evaluation material cannot enter the evidence index')
        if not chunk['id'].strip() or not chunk['content'].strip():
            raise HTTPException(422, 'Chunk ids and contents must be nonempty')


@app.get('/health')
def health():
    if STAGED_MODE == 'cache_only':
        return cache_call('health')
    import torch
    return {'status': 'ok', 'mode': 'local', 'device': device(), 'offline': OFFLINE,
        'models': MODELS, 'maxTokens': MODEL_SETTINGS['embedding']['maxTokens'],
        'batchSize': MODEL_SETTINGS['embedding']['batchSize'],
        'runtime': {kind: model_runtime(kind) for kind in ('embedding', 'reranker')},
        'retrieval': retrieval_settings(), 'indexSignatureVersion': 2,
        'loaded': {'embedding': _embedding is not None, 'reranker': _reranker is not None, 'ocr': _ocr is not None},
        'cudaAllocatedMiB': round(torch.cuda.memory_allocated()/2**20, 1) if torch.cuda.is_available() else None,
        'ocrEngine': 'RapidOCR CPU baseline',
        'limitations': ['OCR does not interpret table cells or deletion state',
            'Configured token caps truncate model inputs; caller must preserve full evidence',
            'Legacy maxTokens/batchSize health fields describe embedding only',
            'Run one server worker with Qdrant local persistence']}


@app.post('/ocr')
def ocr(file: UploadFile = File(...)):
    if STAGED_MODE == 'cache_only':
        raise HTTPException(503, 'OCR is unavailable in sealed retrieval mode; use the separate parser/OCR service')
    from ocr_runtime import create_ocr_engine
    global _ocr
    from PIL import Image, UnidentifiedImageError
    raw = file.file.read(25*1024*1024+1)
    if len(raw) > 25*1024*1024:
        raise HTTPException(413, 'Image exceeds 25 MiB')
    try:
        image = Image.open(io.BytesIO(raw))
        if image.width*image.height > 40000000:
            raise HTTPException(413, 'Image exceeds 40 million pixels')
        image = image.convert('RGB')
    except (UnidentifiedImageError, OSError) as error:
        raise HTTPException(422, 'Upload a readable raster page image') from error
    try:
        with LOCK:
            if _ocr is None:
                _ocr = create_ocr_engine()
            started = time.perf_counter()
            result = _ocr(image)
            lines = []
            if result.txts is not None:
                for text, score, box in zip(result.txts, result.scores, result.boxes):
                    xs, ys = [float(p[0]) for p in box], [float(p[1]) for p in box]
                    left, top = max(0., min(xs)), max(0., min(ys))
                    right, bottom = min(float(image.width), max(xs)), min(float(image.height), max(ys))
                    lines.append({'text': text, 'confidence': float(score),
                        'bbox': [left, top, max(0., right-left), max(0., bottom-top)]})
            return {'text': '\n'.join(line['text'] for line in lines), 'lines': lines,
                'imageWidth': image.width, 'imageHeight': image.height,
                'seconds': time.perf_counter()-started, 'engine': 'RapidOCR CPU baseline',
                'limitations': ['No table-cell or deletion-state interpretation']}
    except Exception as error:
        raise HTTPException(503, f'OCR unavailable: {type(error).__name__}: {error}') from error


@app.post('/index')
def index(request: IndexRequest):
    if STAGED_MODE == 'cache_only':
        chunks = sorted([chunk.model_dump(exclude_none=True) for chunk in request.chunks], key=lambda x: x['id'])
        validate_corpus(chunks)
        return cache_call('index', project_key(request.projectId), chunks)
    from qdrant_client import models
    key = project_key(request.projectId)
    chunks = sorted([chunk.model_dump(exclude_none=True) for chunk in request.chunks], key=lambda x: x['id'])
    validate_corpus(chunks)
    signed = signature(chunks)
    if PROBE is not None:
        PROBE.bind_chunks(chunks)
    probe('index-input', {'projectId':key, 'signature':signed, 'chunks':chunks, 'chunkCount':len(chunks)})
    collection = 'project_' + digest(key)[:16] + '_' + digest(signed)[:16]
    with LOCK:
        path = metadata_path(key)
        if path.exists():
            existing = json.loads(path.read_text(encoding='utf-8'))
            if existing.get('signature') == signed and (not chunks or database().collection_exists(collection)):
                _projects[key] = existing
                return {'projectId': key, 'indexed': len(chunks), 'signature': digest(signed), 'cached': True}
        started = time.perf_counter()
        try:
            if chunks:
                model, db = embedding_model(), database()
                if not db.collection_exists(collection):
                    db.create_collection(collection, vectors_config=models.VectorParams(
                        size=int(model.get_sentence_embedding_dimension()), distance=models.Distance.COSINE))
                for begin in range(0, len(chunks), 128):
                    group = chunks[begin:begin+128]
                    if PROBE is not None:
                        PROBE.set_context('index',projectId=key,queryOrdinal=None,groupBegin=begin,groupCount=len(group))
                    probe('index-embedding-group', {'begin':begin, 'count':len(group), 'chunkIds':[c['id'] for c in group]})
                    vectors = encode_embeddings([c['content'] for c in group])
                    upsert_started = time.perf_counter()
                    db.upsert(collection, points=[models.PointStruct(
                        id=str(uuid.uuid5(uuid.NAMESPACE_URL, key+':'+chunk['id'])),
                        vector=vector.tolist(), payload=chunk) for chunk, vector in zip(group, vectors)])
                    probe('index-upsert-group', {'begin':begin, 'chunkIds':[c['id'] for c in group], 'wallSeconds':time.perf_counter()-upsert_started, 'storedCount':db.count(collection,exact=True).count})
                if db.count(collection, exact=True).count != len(chunks):
                    raise RuntimeError('Indexed count differs from submitted corpus')
            metadata = {'projectId': key, 'signature': signed, 'collection': collection,
                        'chunks': chunks, 'createdAt': time.time()}
            path.parent.mkdir(parents=True, exist_ok=True)
            pending = path.with_suffix('.json.pending')
            pending.write_text(json.dumps(metadata, ensure_ascii=False), encoding='utf-8')
            pending.replace(path)
            _projects[key] = metadata
            return {'projectId': key, 'indexed': len(chunks), 'signature': digest(signed),
                    'cached': False, 'seconds': time.perf_counter()-started}
        except Exception as error:
            raise HTTPException(503, f'Index unavailable: {type(error).__name__}: {error}') from error


@app.post('/retrieve')
def retrieve(request: RetrieveRequest):
    if STAGED_MODE == 'cache_only':
        return cache_call('retrieve', project_key(request.projectId), request.query, request.role, request.limit, request.candidates)
    import numpy as np
    from qdrant_client import models
    from rank_bm25 import BM25Okapi
    if request.limit > request.candidates:
        raise HTTPException(422, 'limit cannot exceed candidates')
    if request.role is not None and request.role not in ROLES:
        raise HTTPException(422, 'Unsupported evidence role')
    key = project_key(request.projectId)
    with LOCK:
        metadata = read_project(key)
        scope = [c for c in metadata['chunks'] if request.role is None or c['role'] == request.role]
        probe('retrieve-input', {'request':request.model_dump(), 'indexSignature':digest(metadata['signature']), 'roleEligibleCount':len(scope), 'eligibleIds':[c['id'] for c in scope]})
        if not scope:
            return {'hits': [], 'mode': 'dense-bm25-rerank', 'projectId': key, 'indexSignature': digest(metadata['signature'])}
        try:
            started = time.perf_counter()
            vector = encode_embeddings([request.query])[0]
            filt = models.Filter(must=[models.FieldCondition(key='role', match=models.MatchValue(value=request.role))]) if request.role else None
            dense_started = time.perf_counter()
            dense_points = database().query_points(metadata['collection'], query=vector.tolist(),
                limit=request.candidates, query_filter=filt).points
            dense = [point.payload for point in dense_points]
            probe('dense-candidates', {'wallSeconds':time.perf_counter()-dense_started,
                  'orderedCandidates':[{'rank':rank,'id':p.payload['id'],'score':float(p.score),
                    'documentId':p.payload.get('documentId'),'role':p.payload.get('role'),'sourceHash':p.payload.get('sourceHash'),
                    'contentSha256':hashlib.sha256(p.payload['content'].encode('utf-8')).hexdigest()} for rank,p in enumerate(dense_points,1)]})
            lexical_started = time.perf_counter()
            terms = [tokens(c['content']) for c in scope]
            lexical_scores = np.zeros(len(scope))
            lexical = []
            if any(terms):
                lexical_scores = BM25Okapi(terms).get_scores(tokens(request.query))
                lexical = [scope[i] for i in np.argsort(-lexical_scores)[:request.candidates] if lexical_scores[i] > 0]
            probe('bm25-candidates', {'wallSeconds':time.perf_counter()-lexical_started, 'queryTerms':tokens(request.query),
                  'allScopeScores':[{'id':c['id'],'score':float(lexical_scores[j])} for j,c in enumerate(scope)],
                  'orderedCandidateIds':[c['id'] for c in lexical]})
            scores, lookup = {}, {}
            for found in (dense, lexical):
                for rank, chunk in enumerate(found, 1):
                    scores[chunk['id']] = scores.get(chunk['id'], 0.) + 1./(RRF_CONSTANT+rank)
                    lookup[chunk['id']] = chunk
            candidates = [lookup[k] for k in sorted(scores, key=scores.get, reverse=True)[:request.candidates]]
            probe('rrf-candidates', {'constant':RRF_CONSTANT, 'allFusedInOrder':[{'id':k,'score':scores[k]} for k in sorted(scores,key=scores.get,reverse=True)], 'submittedToRerankIds':[c['id'] for c in candidates]})
            ranking = rerank_candidates(request.query, candidates)
            raw_scores = np.asarray(ranking).reshape(-1)
            order = np.argsort(-raw_scores)[:request.limit]
            probe('retrieval-final-ranking', {'allRerankedInOrder':[{'id':candidates[i]['id'],'score':float(raw_scores[i]),'rank':rank} for rank,i in enumerate(np.argsort(-raw_scores),1)], 'returnedIds':[candidates[i]['id'] for i in order], 'limit':request.limit})
            return {'hits': [{'id': candidates[i]['id'], 'score': float(raw_scores[i]), 'payload': candidates[i]} for i in order],
                'mode': 'dense-bm25-rerank', 'projectId': key, 'indexSignature': digest(metadata['signature']),
                'seconds': time.perf_counter()-started,
                'scoreMeaning': 'reranker score, not evidence validity or probability of a contract defect'}
        except Exception as error:
            raise HTTPException(503, f'Retrieval unavailable: {type(error).__name__}: {error}') from error


if __name__ == '__main__':
    if STAGED_MODE == 'cache_only':
        sealed_cache()  # Startup refusal before opening a listener if the seal/recipe is invalid.
    import uvicorn
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8868)
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port, workers=1)
