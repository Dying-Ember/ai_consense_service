"""Migrate a sealed Local development baseline into a NEW Server collection.

Only a byte-exact, independently owned copy is opened by Qdrant Local. Server
receives explicit vectors/payloads, never Local database files. No model loads,
original parsing, active configuration changes or collection deletion occurs.
"""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import importlib.metadata
import json
from pathlib import Path
import shutil
import time
from urllib.parse import urlsplit

from fixed_development_baseline_runtime import bound, desc, write
from index_integrity import inspect_points, verify_reuse, vector_sha256
from token_windows import digest, verify_window_coverage


def require(condition, message):
    if not condition:
        raise ValueError(message)


def tree(root):
    root = Path(root).resolve()
    # The active owner's OS lock is transient control data and unreadable on Windows.
    return [{'relativePath': p.relative_to(root).as_posix(), **desc(p)}
            for p in sorted(root.rglob('*')) if p.is_file() and p.relative_to(root).as_posix() != 'qdrant/.lock']


def tree_content(rows):
    return [(r['relativePath'], r['bytes'], r['sha256']) for r in rows]


def stable_copy(source, destination):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    require(not destination.exists() and not destination.is_relative_to(source)
            and not source.is_relative_to(destination), 'Copy must own a fresh separate directory')
    before = tree(source)
    require(before and not any(r['relativePath'].endswith(('-wal', '-shm', '-journal')) for r in before),
            'Uncheckpointed or empty Local state cannot be copied')
    shutil.copytree(source, destination, ignore=lambda directory, names:
                    ['.lock'] if Path(directory).resolve() == source/'qdrant' and '.lock' in names else [])
    copied, after = tree(destination), tree(source)
    require(tree_content(before) == tree_content(copied) == tree_content(after),
            'Local source changed during copy or copy is not byte exact')
    return before


def read_points(client, collection, count, page_size):
    offset, cursors, observed = None, set(), 0
    for _ in range((count+page_size-1)//page_size+1):
        records, next_offset = client.scroll(collection, limit=page_size, offset=offset,
                                              with_payload=True, with_vectors=True)
        observed += len(records)
        require(len(records) <= page_size and observed <= count, 'Point paging exceeds the frozen count')
        yield records
        if next_offset is None:
            require(observed == count, 'Point paging omitted frozen records')
            return
        marker = (type(next_offset).__name__, str(next_offset))
        require(records and marker not in cursors, 'Point paging must progress without repeated cursors')
        cursors.add(marker)
        offset = next_offset
    raise ValueError('Point paging exceeded its frozen count bound')


def validate_metadata(frozen, meta):
    signature = digest(meta['signature'])
    require(signature == frozen['indexSignature'] and meta['collection'] == frozen['collection'],
            'Frozen collection/embedding signature differs')
    require(meta['vectorIntegrity'] == frozen['vectorIntegrity'], 'Frozen vector seal differs')
    parents = {p['id']: p for p in meta['chunks']}
    windows = {w['id']: w for w in meta['windows']}
    require(len(parents) == len(meta['chunks']) == meta['parentCount'] == frozen['coverage']['totalParents'],
            'Complete parent count or identity differs')
    require(len(windows) == len(meta['windows']) == meta['vectorPoints'] == frozen['persistedVectorCount']
            == frozen['coverage']['embeddingWindows'], 'Complete vector-window count or identity differs')
    require(digest(meta['chunks']) == meta['signature']['corpus'], 'Full source corpus differs from embedding identity')
    require(digest(meta['windows']) == meta['windowManifestSha256'], 'Full window manifest changed')
    grouped = defaultdict(list)
    for window in windows.values():
        require(window['parentId'] in parents, 'Window has an absent parent')
        grouped[window['parentId']].append(window)
    require(set(grouped) == set(parents), 'Every complete parent requires vector windows')
    for key, rows in grouped.items():
        verify_window_coverage(parents[key], rows, meta['signature']['windowRecipe']['max_tokens'])
    originals = {d['sha256']: d for d in frozen['originalSources'].values()}
    for parent in parents.values():
        require(parent['originalSourceSha256'] in originals
                and Path(parent['sourcePath']).resolve() == Path(originals[parent['originalSourceSha256']]['path']).resolve(),
                'Parent original source identity is outside the frozen manifest')
    return parents, windows


def compare_vector(source, target, dimension):
    import numpy as np
    source_hash, target_hash = vector_sha256(source, dimension), vector_sha256(target, dimension)
    a, b = np.asarray(source, dtype=np.float64), np.asarray(target, dtype=np.float64)
    difference = float(np.max(np.abs(a-b)))
    cosine_delta = float(abs(1.0 - np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b))))
    # Cosine Server may renormalize float32 input; this is not byte equality.
    require(difference <= 1e-6 and cosine_delta <= 1e-6, 'Server vector changed beyond float32 cosine normalization tolerance')
    return {'sourceFloat32Sha256': source_hash, 'targetFloat32Sha256': target_hash,
            'float32BytesExact': source_hash == target_hash, 'maxAbsDifference': difference,
            'cosineDelta': cosine_delta}


def migrate(args):
    from qdrant_client import QdrantClient, models
    import numpy as np
    out, source = Path(args.out).resolve(), None
    url = urlsplit(args.server_url)
    require(url.scheme == 'http' and url.hostname in ('127.0.0.1', 'localhost', '::1')
            and url.port and not url.username and not url.password and url.path in ('', '/'),
            'Development migration requires an explicit credential-free loopback HTTP Server URL')
    require(1 <= args.batch_size <= 256, 'Migration batch size must be 1..256')
    manifest_desc = desc(args.frozen_manifest)
    require(manifest_desc['sha256'] == args.frozen_sha256, 'Frozen manifest external SHA differs')
    frozen = json.loads(Path(args.frozen_manifest).read_bytes())
    require(frozen['schema'] == 'consense.frozen-fixed-development-baseline.v1', 'Unsupported frozen baseline schema')
    metadata_file = bound(frozen['persistedMetadata'])
    meta = json.loads(metadata_file.read_bytes())
    parents, windows = validate_metadata(frozen, meta)
    require(args.collection != frozen['collection'] and args.collection.startswith('oer_server_'),
            'Target must be a distinct named oer_server_ collection')
    source = Path(frozen['state']).resolve()
    require(not out.exists() and not out.is_relative_to(source) and not source.is_relative_to(out),
            'Migration output must own a fresh directory separate from Local05')
    out.mkdir(parents=True)
    receipt = {'schema': 'consense.qdrant-server-migration.v1', 'status': 'running',
               'frozenManifest': manifest_desc, 'originalMetadata': frozen['persistedMetadata'],
               'target': {'url': args.server_url, 'collection': args.collection},
               'software': desc(__file__), 'clientVersion': importlib.metadata.version('qdrant-client'),
               'parentCount': len(parents), 'vectorWindowCount': len(windows),
               'embeddingSignature': meta['signature'], 'indexSignature': frozen['indexSignature'],
               'vectorDimension': frozen['vectorIntegrity']['dimension'], 'distance': 'Cosine',
               'sourceNormalization': frozen['embedding']['normalization'], 'sourceDtype': 'float32-le',
               'targetTransform': 'Server cosine float32 renormalization; exact hashes and bounded numerical comparison recorded separately',
               'activeConfigurationChanged': False, 'targetAdopted': False,
               'semanticModelQueryAccepted': False, 'businessAccepted': False, 'H800Accepted': False}
    old_tree, local, remote = None, None, None
    started = time.perf_counter()
    try:
        remote = QdrantClient(url=args.server_url, timeout=120)
        require(not remote.collection_exists(args.collection), 'Target collection already exists; never overwrite it')
        receipt['serverVersion'] = remote.info().version
        copied = out/'copied-local-state'
        old_tree = stable_copy(source, copied)
        receipt['copyProvenance'] = write(out/'copy-provenance.json', {'sourceState': str(source),
                'copiedState': str(copied), 'sourceFiles': old_tree, 'copiedFiles': tree(copied),
                'persistentStateByteExactBeforeClient': True, 'persistentSourceStableAcrossCopy': True,
                'excludedTransientOwnerLock': 'qdrant/.lock; original lock retained and never opened'})
        require(desc(copied/metadata_file.relative_to(source))['sha256'] == frozen['persistedMetadata']['sha256'],
                'Copied metadata differs from the frozen metadata')
        local = QdrantClient(path=str(copied/'qdrant'))
        receipt['sourceVectorIntegrity'] = verify_reuse(local, meta, key=meta['projectId'],
                collection=meta['collection'], chunks=meta['chunks'], signature=meta['signature'])
        dimension = frozen['vectorIntegrity']['dimension']
        remote.create_collection(args.collection, vectors_config=models.VectorParams(size=dimension, distance=models.Distance.COSINE))
        identifiers = list(windows)
        positions = {key: i for i, key in enumerate(identifiers)}
        matrix = np.lib.format.open_memmap(out/'source-vectors.npy', mode='w+', dtype=np.float32,
                                          shape=(len(windows), dimension))
        seen = set()
        for records in read_points(local, meta['collection'], len(windows), args.batch_size):
            for point in records:
                key = str(point.id)
                require(key in windows and key not in seen and point.payload == {**windows[key], 'indexSignature': frozen['indexSignature']},
                        'Export point identity/payload differs from frozen window')
                matrix[positions[key]] = point.vector
                seen.add(key)
            remote.upsert(args.collection, points=[models.PointStruct(id=p.id, vector=p.vector, payload=p.payload) for p in records], wait=True)
        require(seen == set(windows), 'Export did not include all vectors')
        matrix.flush()
        local.close()
        local = None
        receipt['exportedVectors'] = desc(out/'source-vectors.npy')
        require(remote.count(args.collection, exact=True).count == len(windows), 'Target exact count differs')
        actual = inspect_points(remote, args.collection, meta['windows'], meta['signature'], dimension)
        receipt['targetVectorIntegrity'] = actual
        ledger, seen = [], set()
        for records in read_points(remote, args.collection, len(windows), args.batch_size):
            for point in records:
                key = str(point.id)
                require(key in windows and key not in seen and point.payload == {**windows[key], 'indexSignature': frozen['indexSignature']},
                        'Readback point identity/payload differs from frozen window')
                comparison = compare_vector(matrix[positions[key]].tolist(), point.vector, dimension)
                ledger.append({'id': key, 'parentId': windows[key]['parentId'], 'sourceHash': windows[key]['sourceHash'],
                               'payloadSha256': digest(point.payload), **comparison})
                seen.add(key)
        require(seen == set(windows), 'Readback did not include all vectors')
        require(remote.count(args.collection, exact=True).count == len(windows)
                and actual['pointsSha256'] == digest([{'id': r['id'], 'vectorSha256': r['targetFloat32Sha256']}
                                                      for r in sorted(ledger, key=lambda r: r['id'])]),
                'Target vector state changed between complete readback passes')
        receipt['fullPointLedger'] = write(out/'point-readback-ledger.json', ledger)
        receipt['exactFloat32Vectors'] = sum(r['float32BytesExact'] for r in ledger)
        receipt['maximumVectorDifference'] = max(r['maxAbsDifference'] for r in ledger)
        receipt['maximumCosineDelta'] = max(r['cosineDelta'] for r in ledger)
        probes = []
        for family in ('GCC', 'GCT', 'SL'):
            window = next(w for w in windows.values() if w['documentId'].split(':')[0] == family)
            points = remote.query_points(args.collection, query=matrix[positions[window['id']]].tolist(),
                                         limit=10, with_payload=True).points
            require(points and any(str(p.id) == window['id'] for p in points), 'Self-vector query lost its source point')
            probes.append({'kind': 'frozen-document-self-vector-storage-query', 'family': family,
                           'windowId': window['id'], 'queryVectorSha256': vector_sha256(matrix[positions[window['id']]].tolist(), dimension),
                           'results': [{'id': str(p.id), 'score': p.score, 'parentId': p.payload['parentId']} for p in points]})
        receipt['actualStorageQueries'] = write(out/'storage-query-probes.json', probes)
        assets = {**frozen['originalSources'], **{'logicalTable:'+r['id']: r['artifact'] for r in frozen.get('adoptedLogicalTables', [])}}
        receipt['originalAndTableReadback'] = [{'kind': kind, 'file': desc(bound(artifact)), 'frozenBytesExact': True}
                                               for kind, artifact in assets.items()]
        target_meta = {**meta, 'collection': args.collection, 'vectorIntegrity': {**actual,
                       'persistedPointsSha256': actual['pointsSha256']}}
        target_meta['vectorIntegrity']['sealSha256'] = digest(target_meta['vectorIntegrity'])
        receipt['candidateMetadata'] = write(out/'candidate-metadata.json', target_meta)
        receipt['candidateConfiguration'] = write(out/'candidate-config.json', {
                'CONSENSE_QDRANT_URL': args.server_url, 'collection': args.collection,
                'metadata': receipt['candidateMetadata'], 'originalEmbeddingSignature': frozen['indexSignature'],
                'selectionStatus': 'candidate-not-active', 'runtimeCompatibilityVerified': False})
        receipt['rollbackConfiguration'] = write(out/'rollback-config.json', {
                'CONSENSE_QDRANT_URL': None, 'CONSENSE_RETRIEVAL_DATA': str(source),
                'collection': frozen['collection'], 'metadata': frozen['persistedMetadata'],
                'accessRule': 'Existing Local05 service remains its sole owner; never open a second Local client'})
        receipt['status'] = 'completed_full_storage_readback_candidate_not_adopted'
    except Exception as error:
        receipt.update(status='failed_partial_candidate_preserved', error={'type': type(error).__name__, 'message': str(error)})
    finally:
        for client in (local, remote):
            if client is not None:
                try:
                    client.close()
                except Exception as error:
                    receipt.update(status='failed_client_close', error={'type': type(error).__name__, 'message': str(error)})
        if old_tree is not None:
            receipt['allOriginalPersistentStateBytesUnchanged'] = tree_content(old_tree) == tree_content(tree(source))
            receipt['excludedTransientOwnerLock'] = 'qdrant/.lock; retained, not opened or byte verified'
            if not receipt['allOriginalPersistentStateBytesUnchanged']:
                receipt.update(status='failed_original_state_changed', error={'type': 'ValueError', 'message': 'Original state changed after copy'})
        receipt.update(wallSeconds=time.perf_counter()-started, completedAtUtc=datetime.now(timezone.utc).isoformat())
        receipt_desc = write(out/'migration-receipt.json', receipt)
        print(json.dumps({'status': receipt['status'], 'receipt': receipt_desc}, ensure_ascii=False), flush=True)
    require(receipt['status'] == 'completed_full_storage_readback_candidate_not_adopted', 'Migration did not pass storage readback')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ('frozen-manifest', 'frozen-sha256', 'server-url', 'collection', 'out'):
        p.add_argument('--'+key, required=True)
    p.add_argument('--batch-size', type=int, default=128)
    migrate(p.parse_args())


if __name__ == '__main__':
    main()
