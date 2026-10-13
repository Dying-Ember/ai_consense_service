"""Local CUDA FP32 endpoint for MinerU's pinned, unquantized Qwen2-VL model.

Only inline images are accepted. The endpoint preserves generated layout tokens
and records actual request/image/model/output identities. It is an inference
adapter for the complete MinerU parser, not a standalone document parser.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import threading
import time
import uuid

from fastapi import FastAPI, HTTPException
from PIL import Image

MODEL = 'opendatalab/MinerU2.5-Pro-2605-1.2B'
REVISION = '08aaea840498d49ce16247b6263196cf02814885'
app = FastAPI(title='ConSense MinerU raw VLM adapter')
_lock = threading.Lock()
_model = _processor = None
_config = {}


def _hash(raw):
    return hashlib.sha256(raw).hexdigest()


def _require_cuda():
    if os.environ.get('CONSENSE_MODEL_DEVICE', 'cuda') != 'cuda':
        raise RuntimeError('MinerU raw VLM requires CONSENSE_MODEL_DEVICE=cuda; CPU fallback is disabled')
    if os.environ.get('CONSENSE_MODEL_DTYPE', 'float32') != 'float32':
        raise RuntimeError('MinerU raw VLM requires the pinned float32 recipe')
    import torch
    if not torch.cuda.is_available():
        raise RuntimeError('CUDA is required for MinerU raw VLM; CPU fallback is disabled')
    return torch


def _model_runtime(model):
    torch = _require_cuda()
    parameters = iter(model.parameters())
    first = next(parameters, None)
    if first is None or first.device.type != 'cuda' or first.dtype != torch.float32 or any(
            p.device.type != 'cuda' or p.dtype != torch.float32 for p in parameters):
        raise RuntimeError('Raw CUDA FP32 runtime differs from the configured recipe')
    return {'device': str(first.device), 'dtype': str(first.dtype).removeprefix('torch.')}


def _cuda_inputs(inputs):
    torch = _require_cuda()
    inputs = inputs.to('cuda')
    if any(torch.is_tensor(value) and value.device.type != 'cuda' for value in inputs.values()):
        raise RuntimeError('MinerU processor tensors did not move to CUDA')
    return inputs


def _load():
    global _model, _processor
    if _model is None:
        torch = _require_cuda()
        from transformers import AutoProcessor, Qwen2VLForConditionalGeneration
        torch.set_num_threads(_config['threads'])
        processor = AutoProcessor.from_pretrained(_config['snapshot'],
                                                  local_files_only=True, use_fast=False)
        model = Qwen2VLForConditionalGeneration.from_pretrained(
            _config['snapshot'], local_files_only=True, torch_dtype=torch.float32,
            attn_implementation='eager').eval().to('cuda')
        _model_runtime(model)
        _processor, _model = processor, model


@app.get('/health')
def health():
    try:
        _require_cuda()
        runtime = _model_runtime(_model) if _model is not None else {'device': 'cuda', 'dtype': 'float32'}
    except RuntimeError as error:
        raise HTTPException(503, str(error)) from error
    return {'status': 'ok', 'model': MODEL, 'revision': REVISION,
            'modelLoaded': _model is not None, **runtime,
            'quantization': None, 'offline': True}


@app.get('/v1/models')
def models():
    return {'object': 'list', 'data': [{'id': MODEL, 'object': 'model'}]}


def _messages(body):
    if not isinstance(body.get('messages'), list) or not body['messages']:
        raise ValueError('messages must be a nonempty array')
    messages, images, descriptors = [], [], []
    for message in body['messages']:
        role, content = message.get('role'), message.get('content')
        if role not in {'system', 'user', 'assistant'}:
            raise ValueError('Unsupported message role')
        parts = [{'type': 'text', 'text': content}] if isinstance(content, str) else content
        if not isinstance(parts, list):
            raise ValueError('Message content must be text or an array')
        converted = []
        for part in parts:
            if part.get('type') == 'text' and isinstance(part.get('text'), str):
                converted.append({'type': 'text', 'text': part['text']})
            elif part.get('type') == 'image_url':
                url = part.get('image_url', {}).get('url', '')
                if not isinstance(url, str) or not url.startswith('data:image/') or ';base64,' not in url:
                    raise ValueError('Only inline base64 images are supported')
                raw = base64.b64decode(url.split(';base64,', 1)[1], validate=True)
                if len(raw) > 25 * 1024 * 1024:
                    raise ValueError('Image exceeds 25 MiB')
                image = Image.open(io.BytesIO(raw))
                if image.width * image.height > 40_000_000:
                    raise ValueError('Image exceeds 40 million pixels')
                descriptors.append({'sha256': _hash(raw), 'bytes': len(raw),
                                    'width': image.width, 'height': image.height})
                images.append(image.convert('RGB'))
                converted.append({'type': 'image'})
            else:
                raise ValueError('Unsupported content part')
        messages.append({'role': role, 'content': converted})
    if not images:
        raise ValueError('MinerU recognition requires an image')
    return messages, images, descriptors


@app.post('/v1/chat/completions')
def completions(body: dict):
    if body.get('model') != MODEL:
        raise HTTPException(404, 'Unknown pinned model')
    if body.get('stream'):
        raise HTTPException(422, 'This parser adapter requires nonstream requests')
    try:
        messages, images, descriptors = _messages(body)
        cap = int(body.get('max_completion_tokens') or body.get('max_tokens') or 8192)
        if cap < 1 or cap > 8192:
            raise ValueError('max_completion_tokens must be 1..8192')
        temperature = float(body.get('temperature', 0))
        if temperature < 0:
            raise ValueError('temperature must be nonnegative')
    except (ValueError, TypeError, KeyError, OSError) as error:
        raise HTTPException(422, str(error)) from error
    started = time.perf_counter()
    request_id = uuid.uuid4().hex
    with _lock:
        try:
            _load()
            runtime = _model_runtime(_model)
        except RuntimeError as error:
            raise HTTPException(503, str(error)) from error
        import torch
        prompt = _processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        try:
            inputs = _cuda_inputs(_processor(text=[prompt], images=images, return_tensors='pt', padding=True))
        except RuntimeError as error:
            raise HTTPException(503, str(error)) from error
        input_tokens = inputs.input_ids.shape[1]
        if input_tokens >= 8192:
            raise HTTPException(422, 'Complete image and prompt exceed the pinned model context')
        effective_cap = min(cap, 8192 - input_tokens)
        from transformers import LogitsProcessor, LogitsProcessorList
        presence = float(body.get('presence_penalty', 0))
        frequency = float(body.get('frequency_penalty', 0))
        class GeneratedTokenPenalties(LogitsProcessor):
            def __call__(self, input_ids, scores):
                for index, sequence in enumerate(input_ids[:, input_tokens:]):
                    if sequence.numel():
                        counts = torch.bincount(sequence, minlength=scores.shape[-1]).to(scores.dtype)
                        scores[index] -= frequency * counts + presence * (counts > 0)
                return scores
        parameters = {'max_new_tokens': effective_cap, 'do_sample': temperature > 0,
                      'use_cache': True, 'repetition_penalty': float(body.get('repetition_penalty', 1.0)),
                      'no_repeat_ngram_size': int(body.get('no_repeat_ngram_size', body.get('vllm_xargs', {}).get('no_repeat_ngram_size', 0)))}
        if temperature > 0:
            parameters.update(temperature=temperature, top_p=float(body.get('top_p', 1.0)),
                              top_k=int(body.get('top_k', 50)))
        with torch.inference_mode():
            generated = _model.generate(**inputs, **parameters,
                                        logits_processor=LogitsProcessorList([GeneratedTokenPenalties()]))[0, input_tokens:]
        generated_ids = generated.tolist()
        eos = _model.generation_config.eos_token_id
        eos_ids = eos if isinstance(eos, list) else [eos]
        stopped = bool(generated_ids and generated_ids[-1] in eos_ids)
        text_ids = generated_ids[:-1] if stopped else generated_ids
        # Drop the completion terminator only. Parser-specific layout/special
        # tokens remain in the content; skip_special_tokens=True destroys them.
        content = _processor.tokenizer.decode(text_ids, skip_special_tokens=False)
        response = {'id': 'chatcmpl-' + request_id, 'object': 'chat.completion',
                    'model': MODEL, 'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': content},
                                               'finish_reason': 'stop' if stopped else 'length'}],
                    'usage': {'prompt_tokens': input_tokens, 'completion_tokens': len(generated_ids),
                              'total_tokens': input_tokens + len(generated_ids)}}
        evidence = {'requestId': request_id, 'model': MODEL, 'revision': REVISION,
                    **runtime, 'quantization': None,
                    'request': body, 'inputImages': descriptors, 'prompt': prompt,
                    'generation': parameters, 'outputTokenIds': generated_ids,
                    'penaltyMapping': {'presencePenalty': presence, 'frequencyPenalty': frequency,
                                       'history': 'generated_tokens_only', 'version': 'generated-token-count-penalties-v1'},
                    'response': response, 'elapsedSeconds': time.perf_counter() - started}
        evidence_root = Path(_config['evidence'])
        evidence_root.mkdir(parents=True, exist_ok=True)
        with (evidence_root / (request_id + '.json')).open('x', encoding='utf-8') as stream:
            json.dump(evidence, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        return response


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-receipt', required=True)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--port', type=int, default=18083)
    args = parser.parse_args()
    _require_cuda()
    receipt = json.loads(Path(args.model_receipt).read_text(encoding='utf-8'))
    identities = [row for row in receipt['models'] if row['name'] == MODEL and row['revision'] == REVISION]
    if len(identities) != 1 or args.threads < 1:
        raise ValueError('Exact pinned model identity and positive host threads are required')
    identity = identities[0]
    snapshot = Path(identity['snapshot'])
    for artifact in identity['files']:
        path = snapshot / artifact['relativePath']
        if path.stat().st_size != artifact['bytes']:
            raise ValueError('Pinned artifact size differs')
        with path.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != artifact['sha256']:
                raise ValueError('Pinned artifact SHA differs')
    _config.update(snapshot=str(snapshot), evidence=str(Path(args.evidence).resolve()), threads=args.threads)
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=args.port, workers=1)


if __name__ == '__main__':
    main()
