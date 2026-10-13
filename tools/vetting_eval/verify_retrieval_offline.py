"""Audit exact Linux wheels and exercise CPU imports without models or databases."""
import argparse
from email.parser import BytesParser
from email.policy import default
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import re
import sys
from urllib.parse import urlsplit
import zipfile


def descriptor(path):
    path = Path(path).resolve(); sha = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b''):
            sha.update(chunk)
    return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': sha.hexdigest()}


def normalized(name):
    return re.sub(r'[-_.]+', '-', name.lower())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def bundled_weights(archive):
    found = []
    for member in archive.namelist():
        name = member.lower()
        if name.endswith('.pth') and '/' not in member and archive.getinfo(member).file_size <= 4096:
            try:
                lines = archive.read(member).decode('utf-8').splitlines()
                if all(not line.strip() or line.startswith(('#', 'import ')) for line in lines):
                    continue  # Python startup file, not a serialized tensor checkpoint.
            except UnicodeDecodeError:
                pass
        if name.endswith(('.onnx', '.safetensors', '.pt', '.pth', '.ckpt')) or Path(name).name in ('pytorch_model.bin', 'model.bin'):
            found.append(member)
    return found


def wheel_inventory(lock, source_report, wheelhouse):
    text = Path(lock).read_text()
    expected = {}
    for stanza in re.sub(r'\\[ \t]*\n', ' ', text).splitlines():
        stanza = stanza.strip()
        if not stanza or stanza.startswith('#'):
            continue
        entry = re.fullmatch(r'([A-Za-z0-9._-]+)==([^\s\\;]+)((?:\s+--hash=sha256:[0-9a-f]{64})+)', stanza)
        require(entry, 'Exact package/version with SHA256 hashes required in each lock stanza')
        name, version, hashes = entry.groups(); name = normalized(name)
        require(name not in expected, 'Duplicate normalized lock package: '+name)
        expected[name] = {'version': version, 'hashes': set(re.findall(r'--hash=sha256:([0-9a-f]{64})', hashes))}
    require(expected, 'Nonempty complete hash lock required')
    sources = {}
    for row in json.loads(Path(source_report).read_bytes())['install']:
        name = normalized(row['metadata']['name'])
        require(name not in sources, 'Duplicate normalized source package: '+name)
        require(name in expected and row['metadata'].get('version') == expected[name]['version'],
                'Source package/version differs from lock')
        sources[name] = row
    require(set(sources) == set(expected), 'Source report must cover the complete lock exactly')
    wheels = {}
    for path in sorted(Path(wheelhouse).glob('*.whl')):
        file = descriptor(path)
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            weight_candidates = bundled_weights(archive)
            require(not weight_candidates, 'Retrieval wheel must not bundle model weights: '+str(weight_candidates))
            metadata = [name for name in names if name.count('/') == 1 and name.endswith('.dist-info/METADATA')]
            require(len(metadata) == 1, 'Wheel requires one package METADATA')
            fields = BytesParser(policy=default).parsebytes(archive.read(metadata[0]))
            name, version = normalized(fields['Name']), fields['Version']
            require(name in expected and name not in wheels and version == expected[name]['version'], 'Wheel package/version differs from lock')
            source = sources[name]['download_info']
            remote = urlsplit(source['url'])
            require(remote.scheme == 'https' and remote.hostname in ('files.pythonhosted.org', 'download.pytorch.org', 'download-r2.pytorch.org', 'pypi.nvidia.com')
                    and not remote.username and not remote.password, 'Official credential-free wheel source required')
            require(file['sha256'] == source['archive_info']['hashes']['sha256']
                    and file['sha256'] in expected[name]['hashes'], 'Wheel differs from download source or locked hash')
            license_names = [member for member in names
                             if ('/licenses/' in member or Path(member).name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE')))
                             and not member.endswith('/')]
            wheels[name] = {'name': fields['Name'], 'version': version, 'file': file, 'sourceUrl': source['url'],
                            'bundledWeightCandidates': weight_candidates,
                            'licenseExpression': fields.get('License-Expression'), 'licenseMetadata': fields.get('License'),
                            'licenseClassifiers': [value for value in fields.get_all('Classifier', []) if value.startswith('License ::')],
                            'embeddedLicenseFiles': [{'member': member, 'sha256': hashlib.sha256(archive.read(member)).hexdigest()}
                                                     for member in license_names]}
    require(set(wheels) == set(expected), 'Wheelhouse must contain exactly one artifact per locked package')
    return list(wheels.values())


def smoke():
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', CUDA_VISIBLE_DEVICES='')
    def forbidden(*args, **kwargs):
        raise AssertionError('Offline smoke must not construct models, GPU contexts or vector databases')
    import torch
    torch.cuda.init = torch.cuda._lazy_init = forbidden
    import transformers
    import sentence_transformers
    import qdrant_client
    import uvicorn
    import pypdf
    import pypdfium2
    import docx
    from rank_bm25 import BM25Okapi
    qdrant_client.QdrantClient.__init__ = forbidden
    sentence_transformers.SentenceTransformer.__init__ = forbidden
    transformers.AutoModel.from_pretrained = transformers.AutoTokenizer.from_pretrained = classmethod(forbidden)
    import server
    require(all(getattr(server, name) is None for name in ('_embedding', '_reranker', '_ocr', '_database')),
            'Server import must not initialize models or vector databases')
    require(not torch.cuda.is_initialized(), 'Offline smoke must not initialize CUDA')
    require(torch.tensor([1., 2.], device='cpu').sum().item() == 3., 'CPU tensor check failed')
    require(len(BM25Okapi([['contract', 'notice'], ['rain', 'water']]).get_scores(['notice'])) == 2, 'BM25 check failed')
    require(server.MODELS['embedding']['revision'] == '5617a9f61b028005a4858fdac845db406aefb181', 'Embedding revision differs')
    require(server.MODELS['reranker']['revision'] == '953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e', 'Reranker revision differs')
    return {'CPUtensorCheck': True, 'BM25Check': True, 'serverImport': True,
            'primaryPackageImports': ['torch', 'transformers', 'sentence_transformers', 'qdrant_client',
                                      'rank_bm25', 'fastapi', 'uvicorn', 'pypdf', 'pypdfium2', 'docx'],
            'modelConstructors': 0, 'vectorDBConstructors': 0, 'GPUContexts': 0,
            'OCRCalls': 0, 'explicitModelDownloads': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('lock', 'source-report', 'wheelhouse', 'out'):
        parser.add_argument('--'+name, required=True)
    args = parser.parse_args()
    wheels = wheel_inventory(args.lock, args.source_report, args.wheelhouse)
    for wheel in wheels:
        require(importlib.metadata.version(wheel['name']) == wheel['version'], 'Installed package differs from exact wheel lock')
    result = {'schema': 'consense.offline-retrieval-artifact-audit.v1', 'status': 'completed',
              'software': descriptor(__file__), 'lock': descriptor(args.lock), 'sourceReport': descriptor(args.source_report),
              'python': sys.version, 'platform': platform.platform(), 'libc': platform.libc_ver(),
              'venv': sys.prefix, 'wheelCount': len(wheels), 'wheelBytes': sum(w['file']['bytes'] for w in wheels),
              'wheels': wheels, 'smoke': smoke(), 'modelQualityAccepted': False,
              'targetOfflineBootAccepted': False, 'OCIscanAccepted': False, 'H800Accepted': False}
    with Path(args.out).open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2); stream.write('\n')
    print(json.dumps({'status': result['status'], 'wheelCount': len(wheels), 'receipt': descriptor(args.out)}))


if __name__ == '__main__':
    main()
