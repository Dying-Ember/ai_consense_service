"""Observational stage probes. No models, network clients or evaluation answers.

The tokenizer proxy returns the original features unchanged. Additional full
tokenization is measured separately and never supplied to the model.
"""
from __future__ import annotations
import hashlib
import json
import os
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

def utc():
    return datetime.now(timezone.utc).isoformat()

def text_sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()

def identity(chunk):
    return {key: chunk.get(key) for key in ('id', 'documentId', 'role', 'fileKey', 'anchor', 'sourceHash', 'pageNo')} | {'contentSha256': text_sha(chunk['content'])} | {
        key:chunk[key] for key in ('parentId','parentContentSha256','parentStartUtf16','parentEndUtf16','sourceSpans','operation') if key in chunk}

def plain(value):
    if hasattr(value, 'detach'):
        return value.detach().cpu().tolist()
    if hasattr(value, 'tolist'):
        return value.tolist()
    return value

class PipelineProbe:
    def __init__(self, directory, experiment_id):
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=False)
        self.experiment_id = experiment_id
        self.sequence = 0
        self.events = []
        self.artifacts = []
        self.context = {'operation': 'startup'}
        self.chunk_lookup = {}
        self.lock = threading.RLock()
        self.overhead_seconds = 0.0
        self.inference_ordinals = {'embedding':0,'reranker':0}

    def set_context(self, operation, **values):
        self.context = {'operation': operation, **values}

    def bind_chunks(self, chunks):
        # Preserve duplicate-text ambiguity rather than inventing a unique ID.
        self.chunk_lookup = {}
        for chunk in chunks:
            key = text_sha(chunk['content'].strip())
            self.chunk_lookup.setdefault(key, []).append(identity(chunk))

    def bind_windows(self, windows):
        for window in windows:
            key = text_sha(window['content'].strip())
            item = identity(window)
            if item not in self.chunk_lookup.setdefault(key, []):
                self.chunk_lookup[key].append(item)

    def descriptor(self, path):
        raw = path.read_bytes()
        result = {'relativePath': path.relative_to(self.directory).as_posix(), 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
        self.artifacts.append(result)
        return result

    def emit(self, stage, payload):
        tick = time.perf_counter()
        with self.lock:
            self.sequence += 1
            record = {'schemaVersion': 1, 'eventOrdinal': self.sequence, 'timestampUtc': utc(),
                      'experimentId': self.experiment_id, 'context': self.context.copy(),
                      'stage': stage, 'payload': payload,
                      'claim': 'Observed pipeline data; mechanical measurements do not establish source meaning or contract validity.'}
            path = self.directory / f'{self.sequence:06d}-{stage}.json'
            with path.open('x', encoding='utf-8', newline='\n') as stream:
                json.dump(record, stream, ensure_ascii=False, indent=2, allow_nan=False)
                stream.write('\n')
            artifact = self.descriptor(path)
            self.events.append({'ordinal': self.sequence, 'stage': stage, 'artifact': artifact})
            with (self.directory / 'events.jsonl').open('a', encoding='utf-8', newline='\n') as stream:
                stream.write(json.dumps({'ordinal': self.sequence, 'stage': stage, **artifact}, ensure_ascii=False) + '\n')
                stream.flush()
            self.overhead_seconds += time.perf_counter() - tick
        return artifact

    def vector_output(self, texts, vectors, elapsed):
        import numpy as np
        tick = time.perf_counter()
        values = np.asarray(vectors)
        path = self.directory / f'vectors-{self.sequence + 1:06d}.npy'
        with path.open('xb') as stream:
            np.save(stream, values, allow_pickle=False)
        norms = np.linalg.norm(values.astype(np.float64), axis=1)
        inputs = [{'textSha256': text_sha(text), 'sourceMatches': self.chunk_lookup.get(text_sha(text.strip()), [])} for text in texts]
        artifact = self.descriptor(path)
        self.overhead_seconds += time.perf_counter() - tick
        self.emit('embedding-output', {'inputsInOutputOrder': inputs, 'shape': list(values.shape),
                 'dtype': str(values.dtype), 'allFinite': bool(np.isfinite(values).all()),
                 'vectorNorms': norms.tolist(), 'vectors': artifact, 'modelCallWallSeconds': elapsed})

    def finish(self, status, **values):
        result = {'schemaVersion': 1, 'status': status, 'finishedAtUtc': utc(),
                  'experimentId': self.experiment_id, 'events': self.events,
                  'artifacts': self.artifacts, 'probeWriteAndAnalysisSeconds': self.overhead_seconds,
                  'timingBoundary': 'Model-call wall times include active tokenizer probe overhead; full tokenization and writes are separately measured. These are instrumented runs, not a clean performance control.',
                  **values}
        path = self.directory / 'probe_manifest.json'
        with path.open('x', encoding='utf-8', newline='\n') as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write('\n')
        return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}

class TokenizerProbe:
    def __init__(self, delegate, probe, kind):
        self.delegate, self.probe, self.kind = delegate, probe, kind

    def __getattr__(self, name):
        return getattr(self.delegate, name)

    def __setattr__(self, name, value):
        if name in {'delegate', 'probe', 'kind'}:
            object.__setattr__(self, name, value)
        else:
            setattr(self.delegate, name, value)

    def __call__(self, *args, **kwargs):
        self.probe.emit(self.kind + '-tokenizer-call-start', {'actualTokenizerOptions':kwargs,
                        'textInputShapes':[type(value).__name__ for value in args],
                        'inputTextSha256':[[text_sha(str(text)) for text in value] if isinstance(value,(list,tuple)) else text_sha(str(value)) for value in args]})
        started = time.perf_counter()
        try:
            features = self.delegate(*args, **kwargs)
        except BaseException as error:
            self.probe.emit(self.kind + '-tokenizer-call-failure', {'originalTokenizationSucceeded':False,
                            'originalExceptionType':type(error).__name__, 'originalExceptionMessage':str(error)})
            raise
        actual_elapsed = time.perf_counter() - started
        tick = time.perf_counter()
        actual = {key: plain(value) for key, value in features.items()}
        full_options = dict(kwargs)
        full_options.update(truncation=False, padding=False)
        for key in ('return_tensors', 'max_length', 'pad_to_multiple_of', 'return_overflowing_tokens', 'stride'):
            full_options.pop(key, None)
        try:
            full = self.delegate(*args, **full_options)
        except BaseException as error:
            self.probe.emit(self.kind + '-probe-failure', {'originalTokenizationSucceeded': True,
                            'actualFeaturesBeforeDeviceTransfer': actual,
                            'actualTokenizerOptions': kwargs,
                            'diagnosticFailure': {'type':type(error).__name__, 'message':str(error)},
                            'failureClass': 'instrumentation_failure_after_successful_tokenization'})
            raise RuntimeError('Tokenizer instrumentation failed after original tokenizer success; not classified as model failure') from error
        full_ids = plain(full['input_ids'])
        if full_ids and isinstance(full_ids[0], int):
            full_ids = [full_ids]
        actual_ids = actual['input_ids']
        if actual_ids and isinstance(actual_ids[0], int):
            actual_ids = [actual_ids]
        masks = actual.get('attention_mask')
        if masks and isinstance(masks[0], int):
            masks = [masks]
        used = [sum(mask) for mask in masks] if masks is not None else [len(row) for row in actual_ids]
        lengths = [len(row) for row in full_ids]
        if len(lengths) != len(used):
            raise RuntimeError('Tokenizer probe full/actual batch sizes differ; not silently omitting the probe')
        text_arguments = []
        for arg in args:
            if isinstance(arg, (list, tuple)):
                text_arguments.append(arg)
            elif isinstance(arg, str):
                text_arguments.append([arg])
            else:
                raise TypeError('Unsupported actual tokenizer input; preserve the failure')
        text_bindings = []
        for row_index in range(len(used)):
            row = []
            for column in text_arguments:
                value = column[row_index]
                texts = value if isinstance(value, (list, tuple)) else [value]
                row.extend({'textSha256': text_sha(str(text)), 'chars': len(str(text)),
                            'sourceMatches': self.probe.chunk_lookup.get(text_sha(str(text).strip()), [])} for text in texts)
            text_bindings.append(row)
        analysis_elapsed = time.perf_counter() - tick
        self.probe.overhead_seconds += analysis_elapsed
        self.probe.emit(self.kind + '-tokenizer-input', {'textBindingsInActualBatchOrder': text_bindings,
                        'actualTokenizerOptions': kwargs, 'effectiveModelMaxLength': self.delegate.model_max_length,
                        'actualFeaturesBeforeDeviceTransfer': actual, 'fullTokenCounts': lengths,
                        'actualUnpaddedTokenCounts': used, 'tokensTruncated': [max(0, full - actual) for full, actual in zip(lengths, used)],
                        'actualTokenizerWallSeconds': actual_elapsed, 'extraFullTokenizationAndAnalysisSeconds': analysis_elapsed,
                        'returnIdentityUnchanged': True})
        return features

def install_model_probes(server, probe):
    # Bind runtime window plans before actual inference. Feature/guard ordinals
    # describe tokenizer boundaries; internal neural forward counts stay unknown.
    def observe_window(stage, payload):
        if stage == 'source-window-plan':
            probe.bind_windows(payload['windowBindings'])
        probe.emit(stage, payload)
    server._window_observer = observe_window
    original_embedding, original_reranker = server.embedding_model, server.rerank_model
    original_encode, original_rank = server.encode_embeddings, server.rerank_candidates

    def embedding():
        was_loaded = server._embedding is not None
        started = time.perf_counter()
        model = original_embedding()
        module = model._first_module()
        if not isinstance(module.tokenizer, TokenizerProbe):
            module.tokenizer = TokenizerProbe(module.tokenizer, probe, 'embedding')
        if not was_loaded:
            probe.emit('embedding-model-load', {'wallSeconds': time.perf_counter() - started,
                       'runtime': server.model_runtime('embedding'), 'embeddingDimension': model.get_sentence_embedding_dimension(),
                       'actualMaxSequenceLength': model.max_seq_length})
        return model

    def reranker():
        was_loaded = server._reranker is not None
        started = time.perf_counter()
        model = original_reranker()
        if not isinstance(model.tokenizer, TokenizerProbe):
            model.tokenizer = TokenizerProbe(model.tokenizer, probe, 'reranker')
        if not was_loaded:
            probe.emit('reranker-model-load', {'wallSeconds': time.perf_counter() - started,
                       'runtime': server.model_runtime('reranker'),
                       'actualTokenizerMaxLength': model.tokenizer.model_max_length})
        return model

    def encode(texts):
        original_context = probe.context.copy()
        probe.inference_ordinals['embedding'] += 1
        probe.set_context(**{**original_context,'activeInferenceCallId':'embedding-'+str(probe.inference_ordinals['embedding'])})
        probe.emit('embedding-call-start', {'inputTextOrder':[{'textSha256':text_sha(text),'sourceMatches':probe.chunk_lookup.get(text_sha(text.strip()),[])} for text in texts]})
        started = time.perf_counter()
        try:
            vectors = original_encode(texts)
            probe.vector_output(texts, vectors, time.perf_counter() - started)
            return vectors
        except BaseException as error:
            probe.emit('embedding-call-failure', {'exceptionType':type(error).__name__,'exceptionMessage':str(error),'stageCompletionUnknown':True})
            raise
        finally:
            probe.context = original_context

    def rank(query, candidates):
        original_context = probe.context.copy()
        probe.inference_ordinals['reranker'] += 1
        probe.set_context(**{**original_context,'activeInferenceCallId':'reranker-'+str(probe.inference_ordinals['reranker'])})
        probe.emit('reranker-call-start', {'querySha256':text_sha(query),'candidateIdentitiesInInputOrder':[identity(chunk) for chunk in candidates]})
        started = time.perf_counter()
        try:
            scores = original_rank(query, candidates)
            probe.emit('reranker-output', {'querySha256': text_sha(query),
                       'candidateIdentitiesInInputOrder': [identity(chunk) for chunk in candidates],
                       'candidatesInScoreOrder': [identity(candidates[i]) for i in sorted(range(len(candidates)), key=lambda i:(-float(scores[i]),candidates[i]['id']))],
                       'scoresInCandidateOrder': plain(scores), 'modelCallWallSeconds': time.perf_counter() - started,
                       'scoreMeaning': 'Parent max over actual activated pair-window CrossEncoder scores, not contract-defect probability.'})
            return scores
        except BaseException as error:
            probe.emit('reranker-call-failure', {'exceptionType':type(error).__name__,'exceptionMessage':str(error),'stageCompletionUnknown':True})
            raise
        finally:
            probe.context = original_context

    server.embedding_model, server.rerank_model = embedding, reranker
    server.encode_embeddings, server.rerank_candidates = encode, rank
