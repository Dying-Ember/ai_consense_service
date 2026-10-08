"""Bind an explicit fixed-source lifecycle to a saved, quality-bearing corpus.

Pure files only: no parser, model, OCR, vector client or filename classification.
The externally SHA-bound emission proof must bind both SourceDocuments and corpus.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

def require(value, message):
    if not value: raise ValueError(message)

def fingerprint(path):
    path = Path(path).resolve(); h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1024*1024), b''): h.update(b)
    return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': h.hexdigest()}

def bound(path, sha):
    d = fingerprint(path); require(d['sha256'] == sha, 'External input SHA differs')
    return d, json.loads(Path(d['path']).read_bytes())

def prepare(lifecycle, proof, documents, corpus):
    require(fingerprint(documents['path']) == documents and fingerprint(corpus['path']) == corpus, 'Bound source input bytes changed')
    require(lifecycle['protocol'] == 'vetting-fixed-source-lifecycle-v1', 'Explicit fixed lifecycle required')
    require(proof['protocol'] == 'source-quality-metadata-corpus-proof-v1', 'Quality corpus emission proof required')
    matches = [r for r in proof['replays'] if r['input'] == documents and r['runs']['quality']['corpus'] == corpus]
    require(len(matches) == 1, 'SourceDocuments/corpus not uniquely bound by emission proof')
    replay = matches[0]
    require(replay['sourceHashContentOwnersAnchorsUtf16NativeRowsExactlyPreserved'], 'Original source ranges are not confirmed')
    source_docs = json.loads(Path(documents['path']).read_bytes())
    require(isinstance(source_docs, list), 'SourceDocuments must be a list')
    docs = {str(d['id']): d for d in source_docs}
    approved = lifecycle['fixedSources']; ids = {str(s['historicalDocumentId']) for s in approved}
    require(len(ids) == len(approved) == len(docs) == len(source_docs) and ids == set(docs), 'Fixed sources/document identities differ')
    corpus_sources = {}; chunks = 0
    with Path(corpus['path']).open(encoding='utf-8') as f:
        for line in f:
            if not line.strip(): continue
            c = json.loads(line); chunks += 1; doc = str(c['documentId'])
            require(doc in ids, 'Variable or unapproved source in fixed corpus')
            require(c.get('sourceQualityMetadataVersion') and c.get('sourceQualityHash') and isinstance(c.get('sourceQuality'), dict), 'Source quality metadata required')
            identity = (c['sourceHash'], c['role'])
            corpus_sources.setdefault(doc, set()).add(identity)
    require(chunks == replay['chunkCount'] and set(corpus_sources) == ids, 'Fixed corpus count/source coverage differs')
    rows = []
    for approved_source in approved:
        doc_id = str(approved_source['historicalDocumentId']); doc = docs[doc_id]
        original = approved_source['original']; require(fingerprint(original['path']) == original, 'Fixed original bytes changed')
        stored = fingerprint(doc['storagePath'])
        require(stored['sha256'] == original['sha256'] and stored['bytes'] == original['bytes'], 'Parsed source does not match explicit original')
        require(doc['fileKey'] == approved_source['fileKey'] and doc['reviewRole'] == approved_source['businessRole'], 'Business role/file key changed')
        identities = corpus_sources[doc_id]; require(len(identities) == 1, 'Mixed revisions in fixed source corpus')
        source_hash, role = next(iter(identities)); require(role == approved_source['businessRole'], 'Corpus role differs')
        require(len(source_hash) == 64 and all(x in '0123456789abcdef' for x in source_hash), 'Derived source SHA invalid')
        rows.append({'sourceHash': source_hash, 'role': role, 'storageTier': 'fixed_competition_material',
            'rawSourceSha256': original['sha256'], 'referenceKey': approved_source['referenceKey'],
            'preparedDocumentIdObservation': doc_id, 'documentIdsReusable': False})
    require(fingerprint(documents['path']) == documents and fingerprint(corpus['path']) == corpus, 'Source inputs changed while planning')
    return {'protocol': 'fixed-source-vector-cache-whitelist-v1', 'sources': rows,
        'identityPolicy': 'Explicit user-approved lifecycle originals only; business roles preserved; project parent IDs must be rebound',
        'qualityCorpus': corpus, 'sourceDocuments': documents, 'freshParseOcrNeuralIndexQueryCalls': 0}

def main():
    p = argparse.ArgumentParser()
    for name in ('lifecycle', 'emission-proof', 'source-documents', 'corpus'):
        p.add_argument('--'+name, required=True); p.add_argument('--'+name+'-sha256', required=True)
    p.add_argument('--out', required=True); a = p.parse_args()
    ld, lifecycle = bound(a.lifecycle, a.lifecycle_sha256); pd, proof = bound(a.emission_proof, a.emission_proof_sha256)
    dd = fingerprint(a.source_documents); cd = fingerprint(a.corpus)
    require(dd['sha256'] == a.source_documents_sha256 and cd['sha256'] == a.corpus_sha256, 'Corpus or SourceDocuments external SHA differs')
    out = Path(a.out).resolve(); require(not out.exists(), 'Fresh output directory required')
    whitelist = prepare(lifecycle, proof, dd, cd); out.mkdir(parents=True)
    for name, value in [('fixed_cache_whitelist.json', whitelist), ('binding_receipt.json', {
        'protocol': 'fixed-cache-input-binding-v1', 'inputs': {'lifecycle': ld, 'emissionProof': pd, 'sourceDocuments': dd, 'corpus': cd},
        'tool': fingerprint(__file__), 'fixedSourceCount': len(whitelist['sources']), 'newModelCalls': 0, 'newIndexQueryCalls': 0})]:
        with (out/name).open('x', encoding='utf-8') as f: f.write(json.dumps(value, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'whitelist': fingerprint(out/'fixed_cache_whitelist.json'), 'receipt': fingerprint(out/'binding_receipt.json')}))

if __name__ == '__main__': main()
