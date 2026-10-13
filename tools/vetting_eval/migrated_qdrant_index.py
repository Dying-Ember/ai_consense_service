"""Explicit read-only access to one externally pinned Server migration.

The historical index-build signature stays intact. Query model identity is
verified and observed separately; this does not certify semantic compatibility.
"""
from dataclasses import asdict
import json
import os
from pathlib import Path

from index_integrity import verify_reuse
from migrate_qdrant_server import validate_metadata
from token_windows import digest, fail
from verify_retrieval_offline import descriptor

ENV = 'CONSENSE_QDRANT_MIGRATION_RECEIPT'


def configured(environ):
    return bool(environ.get(ENV) or environ.get(ENV+'_SHA256'))


def artifact_path(value):
    text = str(value).replace('\\', '/')
    # shortcut: Windows receipts on Linux require the existing WSL /mnt/<drive> mounts.
    if os.name != 'nt' and len(text) > 2 and text[1] == ':':
        text = '/mnt/'+text[0].lower()+text[2:]
    return Path(text).resolve()


def bound(value):
    path = artifact_path(value['path']); actual = descriptor(path)
    fail(type(value['bytes']) is int and actual['bytes'] == value['bytes'] and actual['sha256'] == value['sha256'],
         'MIGRATED_INDEX_ARTIFACT_CHANGED', 'Pinned migration artifact bytes changed')
    return path


class MigratedIndex:
    def __init__(self, environ):
        self.environ = environ
        self.configuration = tuple(environ.get(name) for name in (ENV, ENV+'_SHA256', 'CONSENSE_QDRANT_URL'))
        path, expected, url = self.configuration
        fail(bool(path) and bool(expected) and bool(url), 'MIGRATED_INDEX_CONFIG',
             'Read-only migration requires receipt path, external SHA256 and Server URL')
        self.receipt_file = descriptor(artifact_path(path))
        fail(self.receipt_file['sha256'] == expected, 'MIGRATED_INDEX_RECEIPT_CHANGED', 'Migration receipt external SHA differs')
        self.receipt = json.loads(bound(self.receipt_file).read_bytes())
        r = self.receipt
        fail(r.get('schema') == 'consense.qdrant-server-migration.v1'
             and r.get('status') == 'completed_full_storage_readback_candidate_not_adopted'
             and r['target']['url'] == url and r.get('targetAdopted') is False
             and r.get('activeConfigurationChanged') is False and r.get('allOriginalPersistentStateBytesUnchanged') is True,
             'MIGRATED_INDEX_RECEIPT_INVALID', 'Complete unchanged-source migration bound to this Server URL required')
        self.frozen = json.loads(bound(r['frozenManifest']).read_bytes())
        self.metadata = json.loads(bound(r['candidateMetadata']).read_bytes())
        f, m = self.frozen, self.metadata
        source = {name:f['vectorIntegrity'][name] for name in ('schema','dimension','dtype','pointCount','embeddingSignatureSha256')}
        source['pointsSha256'] = f['vectorIntegrity']['persistedPointsSha256']
        fail(r.get('sourceVectorIntegrity') == source and r['targetVectorIntegrity'] == source,
             'MIGRATED_INDEX_SOURCE_VECTOR_CHANGED', 'Source cold persisted vectors and target full vector seal must equal the original recorded bytes')
        seal = {**r['targetVectorIntegrity'], 'persistedPointsSha256':r['targetVectorIntegrity']['pointsSha256']}
        seal['sealSha256'] = digest(seal)
        fail(f['schema'] == 'consense.frozen-fixed-development-baseline.v1'
             and m['collection'] == r['target']['collection'] and m['collection'] != f['collection']
             and m['vectorIntegrity'] == seal and r['indexSignature'] == f['indexSignature']
             and r['parentCount'] == m['parentCount'] and r['vectorWindowCount'] == m['vectorPoints'],
             'MIGRATED_INDEX_IDENTITY', 'Target collection, full metadata and reviewed seal differ')
        # Only these two fields change during migration; source identity remains the original freeze.
        validate_metadata(f, {**m, 'collection':f['collection'], 'vectorIntegrity':f['vectorIntegrity']})
        self.last_observation = None

    def read(self, key, runtime, database):
        fail(self.configuration == tuple(self.environ.get(name) for name in (ENV, ENV+'_SHA256', 'CONSENSE_QDRANT_URL')),
             'MIGRATED_INDEX_CONFIG_CHANGED', 'Migration configuration changed; start a new runtime')
        bound(self.receipt_file); bound(self.receipt['frozenManifest']); bound(self.receipt['candidateMetadata'])
        m = self.metadata
        fail(key == m['projectId'], 'MIGRATED_INDEX_PROJECT', 'Only the pinned migrated project can be queried')
        fail(m['contract'] == runtime.contract() and m['signature']['signatureVersion'] == runtime.signature_version()
             and asdict(runtime.recipe('embedding')) == m['signature']['windowRecipe'],
             'MIGRATED_INDEX_QUERY_RECIPE', 'Query contract or full source window recipe differs')
        identity = runtime.identity('embedding'); original = m['signature']['tokenizerAndModelFiles']
        fields = ('model', 'files', 'backendSha256', 'tokenizerClass', 'fast', 'modelMaxLength')
        fail(all(identity.get(name) == original.get(name) for name in fields),
             'MIGRATED_INDEX_QUERY_MODEL', 'Query model/tokenizer file content differs from the sealed index')
        for kind in ('embedding', 'reranker'):
            fail(kind in runtime.model_publications, 'MIGRATED_INDEX_QUERY_PUBLICATION', 'Both frozen model publications are required')
            publication = runtime.publication_identity(kind)
            fail(publication['manifestSha256'] == self.frozen['modelPublications'][kind]['sha256'],
                 'MIGRATED_INDEX_QUERY_PUBLICATION', 'Query model publication differs from the frozen release')
        actual = verify_reuse(database(), m, key=key, collection=m['collection'], chunks=m['chunks'], signature=m['signature'])
        fail(actual == self.receipt['targetVectorIntegrity'], 'MIGRATED_INDEX_VECTOR_CHANGED',
             'Actual complete Server vector seal differs from the reviewed migration')
        query_signature = runtime.signature(m['chunks'])
        self.last_observation = {'receipt':self.receipt_file, 'indexSignature':digest(m['signature']),
                                 'queryRuntimeSignature':digest(query_signature), 'queryIdentity':query_signature,
                                 'queryRecipe':runtime.retrieval_recipe(), 'target':self.receipt['target'],
                                 'fullVectorIntegrity':actual, 'readOnly':True, 'runtimeCompatibilityVerified':False}
        runtime.emit('migrated-server-index-read', self.last_observation)
        return m
