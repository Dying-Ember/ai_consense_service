"""Download the exact OER model revisions and record their actual local bytes.

This preparation command is separate from the offline serving process. It never
uploads source documents, changes service settings, or loads model weights.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path


MODELS = {
    'embedding': ('BAAI/bge-m3', '5617a9f61b028005a4858fdac845db406aefb181'),
    'reranker': ('BAAI/bge-reranker-v2-m3', '953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e'),
    'mineru-raw': ('opendatalab/MinerU2.5-Pro-2605-1.2B', '08aaea840498d49ce16247b6263196cf02814885'),
    'paddle-vl': ('PaddlePaddle/PaddleOCR-VL-1.6', 'c5630abae1d940eafe0697512a0325494b02ab42'),
    'paddle-layout': ('PaddlePaddle/PP-DocLayoutV3', '241f8bdfc77a7c7bee915a5057aaee58c235a8d3'),
    'mineru-small': ('opendatalab/MinerU-4_models_onnx', '358310b4f64b95f9fefc372ad899356e4111f376'),
}
FILES = [
    'config.json', 'config_sentence_transformers.json', 'modules.json',
    'sentence_bert_config.json', '1_Pooling/config.json',
    'tokenizer.json', 'tokenizer_config.json', 'special_tokens_map.json',
    'sentencepiece.bpe.model', 'vocab.json', 'merges.txt', 'added_tokens.json',
    'preprocessor_config.json', 'processor_config.json', 'chat_template.json',
    'chat_template.jinja', 'generation_config.json',
    'model.safetensors', 'pytorch_model.bin',
    'model.safetensors.index.json', 'model-*.safetensors',
]
ROLE_FILES = {
    'paddle-vl': (
        'LICENSE', 'README.md', 'added_tokens.json', 'chat_template.jinja',
        'config.json', 'configuration_paddleocr_vl.py', 'generation_config.json',
        'image_processing_paddleocr_vl.py', 'inference.yml', 'model.safetensors',
        'modeling_paddleocr_vl.py', 'preprocessor_config.json',
        'processing_paddleocr_vl.py', 'processor_config.json',
        'special_tokens_map.json', 'tokenizer.json', 'tokenizer.model',
        'tokenizer_config.json',
    ),
    'paddle-layout': (
        'README.md', 'inference.json', 'inference.pdiparams', 'inference.yml',
    ),
    'mineru-small': (
        'Layout/PP-DocLayoutV2/inference.onnx',
        'Layout/PP-DocLayoutV2/inference.yml',
        'MFR/pp_formulanet_plus_m/PP-FormulaNet_plus-M.onnx',
        'MFR/pp_formulanet_plus_m/PP-FormulaNet_plus-M_inference.yml',
        'OCR/paddleocr/ch_PP-OCRv6_small_rec_infer.onnx',
        'OCR/paddleocr/ch_PP-OCRv6_small_rec_inference.yml',
        'OCR/paddleocr/ch_PP-OCRv6_tiny_det_infer.onnx',
        'OCR/paddleocr/ch_PP-OCRv6_tiny_det_inference.yml',
        'OCR/paddleocr/seal_PP-OCRv4_det_infer.onnx',
        'OCR/paddleocr/seal_PP-OCRv4_det_inference.yml',
        'README.md', 'Table/PP-LCNet_x1_0_table_cls.onnx',
        'Table/slanet-plus.onnx', 'Table/unet.onnx', 'manifest.json',
    ),
}


def sha256(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def git_blob_sha1(path):
    """Git's byte identity for non-LFS artifacts, including the blob header."""
    result = hashlib.sha1()
    result.update(('blob ' + str(path.stat().st_size) + '\0').encode('ascii'))
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def prepare(kind, cache):
    from huggingface_hub import HfApi, snapshot_download
    name, revision = MODELS[kind]
    info = HfApi().model_info(name, revision=revision, files_metadata=True)
    if info.sha != revision:
        raise RuntimeError('Resolved model revision differs from the pinned revision')
    upstream = {entry.rfilename: entry for entry in info.siblings}
    required = ROLE_FILES.get(kind, ())
    for relative in required:
        if relative not in upstream:
            raise RuntimeError('Required pinned upstream artifact missing: ' + relative)
    root = Path(snapshot_download(name, revision=revision, cache_dir=str(cache),
                                  allow_patterns=ROLE_FILES.get(kind, FILES), max_workers=2)).resolve()
    for relative in required:
        if not (root / relative).is_file():
            raise RuntimeError('Required downloaded artifact missing: ' + relative)
    artifacts = []
    for path in sorted(root.rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        entry = upstream.get(relative)
        if entry is None:
            raise RuntimeError('Local artifact has no pinned upstream identity: ' + relative)
        if required and relative not in required:
            raise RuntimeError('Local artifact is outside required role assets: ' + relative)
        actual = sha256(path)
        lfs = getattr(entry, 'lfs', None)
        expected = getattr(lfs, 'sha256', None)
        if lfs is not None and not expected:
            raise RuntimeError('Missing upstream LFS SHA256: ' + relative)
        if expected and expected != actual:
            raise RuntimeError('Upstream LFS hash differs: ' + relative)
        blob_id = getattr(entry, 'blob_id', None)
        actual_blob_id = None
        if not expected:
            if not blob_id:
                raise RuntimeError('Missing upstream Git blob identity: ' + relative)
            actual_blob_id = git_blob_sha1(path)
            if actual_blob_id != blob_id:
                raise RuntimeError('Upstream Git blob identity differs: ' + relative)
        artifacts.append({'relativePath': relative, 'bytes': path.stat().st_size,
                          'isCacheSymlink': path.is_symlink(),
                          'sha256': actual, 'upstreamLfsSha256': expected,
                          'upstreamGitBlobId': blob_id, 'actualGitBlobId': actual_blob_id,
                          'upstreamVerification': 'lfs_sha256' if expected else 'git_blob_sha1'})
    verified = {row['relativePath'] for row in artifacts}
    for relative in required:
        if relative not in verified:
            raise RuntimeError('Required verified artifact missing: ' + relative)
    if not required and not any(row['relativePath'] in {'model.safetensors', 'pytorch_model.bin',
                                      'model.safetensors.index.json'} for row in artifacts):
        raise RuntimeError('No pinned model weights were downloaded')
    return {'kind': kind, 'name': name, 'revision': revision,
            'snapshot': str(root), 'snapshotKind': 'huggingface_cache_snapshot',
            'containsSnapshotSymlinks': any(row['isCacheSymlink'] for row in artifacts),
            'requiredRepositoryFiles': list(required),
            'requiredRepositoryAssetClosureVerified': bool(required),
            'offlinePackageVerified': False, 'runtimeDependencyClosureVerified': False,
            'files': artifacts, 'modelWeightLoads': 0}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', required=True)
    parser.add_argument('--receipt', required=True)
    parser.add_argument('--models', nargs='+', choices=MODELS, required=True)
    args = parser.parse_args(argv)
    receipt = Path(args.receipt).resolve()
    if receipt.exists():
        raise ValueError('Use a fresh receipt path; existing evidence is not replaced')
    cache = Path(args.cache).resolve()
    cache.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault('HF_HUB_DISABLE_TELEMETRY', '1')
    value = {'status': 'downloaded_and_byte_verified_no_inference',
             'models': [prepare(kind, cache) for kind in args.models]}
    receipt.parent.mkdir(parents=True, exist_ok=True)
    with receipt.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps({'status': value['status'], 'receipt': str(receipt),
                      'models': [row['name'] for row in value['models']]}))


if __name__ == '__main__':
    main()
