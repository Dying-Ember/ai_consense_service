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
import sys

HERE = Path(__file__).resolve().parent
from workspace_root import resolve_workspace
WORKSPACE = resolve_workspace(HERE, os.environ)
os.environ.setdefault('HF_HOME', str(WORKSPACE / 'tmp/vetting_models'))
os.environ.setdefault('HF_HUB_DISABLE_TELEMETRY', '1')
os.environ.setdefault('TOKENIZERS_PARALLELISM', 'false')

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel, ConfigDict, Field, field_validator
from ocr_runtime import create_ocr_engine

from window_runtime import WindowRuntime
from token_windows import WindowError
MODEL_DTYPE = os.environ.get('CONSENSE_MODEL_DTYPE', 'float32')
if MODEL_DTYPE not in {'float32', 'float16'}:
    raise ValueError('CONSENSE_MODEL_DTYPE must be explicit float32 or float16')
SERIAL_OFFLOAD = os.environ.get('CONSENSE_MODEL_SERIAL_OFFLOAD', '1') == '1'
_windows_runtime = None
_window_observer = None

def window_runtime():
    global _windows_runtime
    if _windows_runtime is None:
        _windows_runtime = WindowRuntime(sys.modules[__name__])
    return _windows_runtime

def release_other_model(target):
    global _embedding, _reranker
    if device() != 'cuda' or not SERIAL_OFFLOAD:
        return
    other = 'reranker' if target == 'embedding' else 'embedding'
    loaded = _reranker if other == 'reranker' else _embedding
    if loaded is None:
        return
    import gc, torch
    torch.cuda.synchronize()
    before = {'allocated': torch.cuda.memory_allocated(), 'reserved': torch.cuda.memory_reserved()}
    if other == 'reranker':
        _reranker = None
    else:
        _embedding = None
    del loaded
    gc.collect()
    torch.cuda.empty_cache()
    window_runtime().emit('serial-model-offload', {'releasedKind': other, 'targetKind': target, 'beforeBytes': before,
        'afterBytes': {'allocated': torch.cuda.memory_allocated(), 'reserved': torch.cuda.memory_reserved()}})

def verify_loaded_model(model, kind):
    parameter = next(model.parameters())
    expected = model_runtime(kind)
    if str(parameter.dtype) != 'torch.' + expected['dtype'] or parameter.device.type != expected['device']:
        raise WindowError('MODEL_RUNTIME_MISMATCH', 'Loaded model dtype/device differs from frozen runtime')
    window_runtime().emit('window-model-loaded', {'kind': kind, 'runtime': expected, 'actualDtype': str(parameter.dtype),
        'actualDevice': parameter.device.type})


CONFIG = json.loads((HERE / 'samples.json').read_text(encoding='utf-8'))
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
OFFLINE = True  # Isolated window recipe never downloads model artifacts.
LOCK = threading.RLock()
_embedding = _reranker = _ocr = _database = None
_projects = {}
_selected_device = None
app = FastAPI(title='ConSense local vetting models', version='1.0.0')


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


LATIN_TOKEN_PATTERN = r'[a-z0-9]+(?:[./()-][a-z0-9]+)*'
# Fixed code-point ranges, independent of installed Unicode tables. Include
# Han common/extension/compatibility blocks, common kana and Hangul blocks.
CJK_TOKEN_RANGES = (
    r'\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff'
    r'\U00020000-\U0002a6df\U0002a700-\U0002b73f'
    r'\U0002b740-\U0002b81f\U0002b820-\U0002ceaf'
    r'\U0002ceb0-\U0002ebef\U0002ebf0-\U0002ee5f'
    r'\U0002f800-\U0002fa1f\U00030000-\U0003134f'
    r'\U00031350-\U000323af'
    r'\u3040-\u309f\u30a0-\u30ff\u31f0-\u31ff\uff66-\uff9f'
    r'\u1100-\u11ff\u3130-\u318f\ua960-\ua97f\uac00-\ud7af\ud7b0-\ud7ff'
)
TOKEN_PATTERN = '(?P<latin>' + LATIN_TOKEN_PATTERN + ')|(?P<cjk>[' + CJK_TOKEN_RANGES + ']+)'
TOKENIZER_ALGORITHM = 'casefold-latin-v1-cjk-unigram-bigram-source-order-v1'
_LEXICAL_TOKEN_RE = re.compile(TOKEN_PATTERN)


def tokens(value):
    result = []
    for match in _LEXICAL_TOKEN_RE.finditer(value.casefold()):
        run = match.group('cjk')
        if run is None:
            result.append(match.group('latin'))
            continue
        # At each source position emit the character, then its adjacent bigram.
        # Preserve repeated occurrences and do not bridge punctuation/Latin.
        for position, character in enumerate(run):
            result.append(character)
            if position + 1 < len(run):
                result.append(run[position:position + 2])
    return result


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
    if selected == 'cpu' and MODEL_DTYPE != 'float32':
        raise ValueError('CPU recipe requires float32; float16 is a separate unsupported CPU recipe')
    return selected, {'torch_dtype': getattr(torch, MODEL_DTYPE)}


def model_runtime(kind):
    selected = device()
    return {**MODEL_SETTINGS[kind], **MODELS[kind], 'device': selected, 'dtype': MODEL_DTYPE,
            'serialOffload': selected == 'cuda' and SERIAL_OFFLOAD}


def retrieval_settings(*, observe_tokenizer_identity=True):
    from rank_bm25 import BM25Okapi
    parameters = inspect.signature(BM25Okapi).parameters
    return {'requestDefaults': {'candidates': 50, 'limit': 10},
            'rrfConstant': RRF_CONSTANT, 'rrfWeights': {'dense': 1, 'bm25': 1},
            'bm25': {'implementation': 'rank_bm25.BM25Okapi',
                     'parameterSource': 'installed library defaults',
                     **{key: parameters[key].default for key in ('k1', 'b', 'epsilon')}},
            'tokenPattern': TOKEN_PATTERN, 'tokenizerAlgorithm': TOKENIZER_ALGORITHM,
            'latinTokenPattern': LATIN_TOKEN_PATTERN, 'roleFilter': True,
            'storage': 'Qdrant Python local', 'workers': 1,
            'sourceWindows': (window_runtime().retrieval_recipe()if observe_tokenizer_identity
                              else window_runtime().retrieval_recipe(observe_tokenizer_identity=False))}


def model_path(config):
    # An absolute snapshot path prevents Transformers adapter probes reaching
    # Hugging Face even when individual loaders receive local_files_only=True.
    from huggingface_hub import snapshot_download
    return snapshot_download(config['name'], revision=config['revision'], local_files_only=OFFLINE)


def embedding_model():
    global _embedding
    release_other_model('embedding')
    if _embedding is None:
        from sentence_transformers import SentenceTransformer
        selected, options = model_options()
        loaded = SentenceTransformer(model_path(MODELS['embedding']), device=selected, model_kwargs=options, local_files_only=True)
        loaded.max_seq_length = MODEL_SETTINGS['embedding']['maxTokens']
        if loaded[0].do_lower_case or getattr(loaded, 'default_prompt_name', None):
            raise WindowError('EMBEDDING_PREPROCESSING_MISMATCH', 'Recipe requires strip/no lower case/no implicit prompt')
        loaded[0].tokenizer = window_runtime().guard(loaded[0].tokenizer, 'embedding')
        verify_loaded_model(loaded, 'embedding')
        # A failed dtype/device/preprocessing check must never publish a model
        # that the next call could reuse without repeating validation.
        _embedding = loaded
    return _embedding


def rerank_model():
    global _reranker
    release_other_model('reranker')
    if _reranker is None:
        from sentence_transformers import CrossEncoder
        selected, options = model_options()
        loaded = CrossEncoder(model_path(MODELS['reranker']), device=selected, model_kwargs=options,
            max_length=MODEL_SETTINGS['reranker']['maxTokens'], local_files_only=True)
        loaded.tokenizer = window_runtime().guard(loaded.tokenizer, 'reranker')
        verify_loaded_model(loaded, 'reranker')
        _reranker = loaded
    return _reranker


def encode_embeddings(texts):
    return embedding_model().encode(texts, batch_size=MODEL_SETTINGS['embedding']['batchSize'],
        normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False)


def rerank_candidates(query, candidates):
    return window_runtime().rerank(query, candidates)


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
    return window_runtime().signature(chunks)


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
    import torch
    return {'status': 'ok', 'mode': 'local', 'device': device(), 'offline': OFFLINE,
        'models': MODELS, 'maxTokens': MODEL_SETTINGS['embedding']['maxTokens'],
        'batchSize': MODEL_SETTINGS['embedding']['batchSize'],
        'runtime': {kind: model_runtime(kind) for kind in ('embedding', 'reranker')},
        'retrieval': retrieval_settings(observe_tokenizer_identity=False),
        'indexSignatureVersion': window_runtime().signature_version(),
        'loaded': {'embedding': _embedding is not None, 'reranker': _reranker is not None, 'ocr': _ocr is not None},
        'cudaAllocatedMiB': round(torch.cuda.memory_allocated()/2**20, 1) if torch.cuda.is_available() else None,
        'ocrEngine': 'RapidOCR CPU baseline',
        'limitations': ['OCR does not interpret table cells or deletion state',
            'Source-bound windows count query/special tokens; oversized queries fail without silent clipping',
            'Legacy maxTokens/batchSize health fields describe embedding only',
            'Run one server worker with Qdrant local persistence']}


@app.post('/ocr')
def ocr(file: UploadFile = File(...)):
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
    try:
        return window_runtime().index(request)
    except WindowError as error:
        raise HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error


@app.post('/retrieve')
def retrieve(request: RetrieveRequest):
    return window_runtime().retrieve(request)


if __name__ == '__main__':
    import uvicorn
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8868)
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port, workers=1)
