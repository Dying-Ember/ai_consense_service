"""CUDA-only OER retrieval entry point; canonical scope is checked, never filtered.

Run with scripts/oer-local.ps1 or configure the source-bound SL manifest before
starting this module. Importing it does not import server or load neural models.
Native/canonical chunks retain their quality declarations. Full scan-parser
candidate ledgers require an upstream, source-bound canonical projection; route
selection alone does not turn parser observations into verified contract facts.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import os
from contextlib import contextmanager
from pathlib import Path

from competition_scope import CompetitionScopeError, load_policy
from structural_retrieval import StructuralRecipe
from token_windows import WindowError, fail
from window_runtime import WindowRuntime
from workspace_root import resolve_workspace
from pydantic import BaseModel, ConfigDict, Field
from typing import Annotated


HERE = Path(__file__).resolve().parent
INPUT_CONTRACT = 'canonical-source-chunks-upstream-scope-and-parser-projection-v1'
PARSER_SCHEMA = 'consense.structured-parser-evidence.v1'
FRESH_RECIPE = 'scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1'
_WRAPPER_HASHES = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                   for name in ('oer_server.py', 'competition_scope.py', 'artifact_store.py',
                                'structured_parser_evidence.py', 'canonical_projection.py',
                                'oer_source_registry.py', 'oer_request_evidence.py')}


class ProjectionRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    bundle: dict
    adoptionManifest: dict


class CanonicalIndexRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    projectId: int | str
    projectionSha256: list[str] = Field(min_length=1, max_length=10000)
    module: str = 'vetting'



INDEX_STATUS_CONTRACT = 'read-only-existing-canonical-index-v1'
StatusSha = Annotated[str, Field(min_length=64, max_length=64, pattern=r'^[0-9a-f]{64}$')]

class CanonicalIndexStatusRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    projectId: int | Annotated[str, Field(min_length=1, max_length=256)]
    projectionSha256: list[StatusSha] = Field(min_length=1, max_length=10000)
    module: Annotated[str, Field(pattern=r'^(vetting|advisory)$')] = 'vetting'
    sourcePageEvidence: dict[StatusSha, StatusSha] = Field(default_factory=dict, max_length=10000)


def configure_environment(environ, workspace):
    """Return explicit startup settings; reject inherited incompatible recipes."""
    values = dict(environ)
    required = {'CONSENSE_MODEL_DEVICE': 'cuda', 'CONSENSE_MODEL_DTYPE': 'float32',
                'CONSENSE_MODEL_SERIAL_OFFLOAD': '1',
                'CONSENSE_RETRIEVAL_GRANULARITY': 'dual-structure-v1',
                'CONSENSE_EMBED_MAX_TOKENS': '512', 'CONSENSE_RERANK_MAX_TOKENS': '512',
                'CONSENSE_WINDOW_OVERLAP_TOKENS': '64',
                'CONSENSE_STRUCTURAL_EMBED_MAX_TOKENS': '2048',
                'CONSENSE_STRUCTURAL_RERANK_MAX_TOKENS': '2048',
                'CONSENSE_STRUCTURAL_RERANK_STRICT': 'true',
                'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'}
    for name, expected in required.items():
        if values.get(name) not in (None, '', expected):
            raise ValueError(f'{name} must be {expected} for the explicit OER entry point')
        values[name] = expected
    for name in ('CONSENSE_FIXED_VECTOR_CACHE', 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST',
                 'CONSENSE_FIXED_VECTOR_CACHE_WHITELIST_SHA256',
                 'CONSENSE_EMBEDDING_COMPATIBILITY_RECEIPT',
                 'CONSENSE_EMBEDDING_COMPATIBILITY_RECEIPT_SHA256'):
        if values.get(name):
            raise ValueError(f'{name} is incompatible with the fresh OER index recipe; clear it explicitly')
    for name in ('CONSENSE_EMBED_BATCH_SIZE', 'CONSENSE_RERANK_BATCH_SIZE'):
        values.setdefault(name, '2')
        try:
            if int(values[name]) < 1:
                raise ValueError()
        except (TypeError, ValueError) as error:
            raise ValueError(name + ' must be a positive integer') from error
    values.setdefault('CONSENSE_OER_TARGET_MODULE', 'vetting')
    if values['CONSENSE_OER_TARGET_MODULE'] not in ('drafting', 'vetting', 'advisory'):
        raise ValueError('CONSENSE_OER_TARGET_MODULE must be drafting, vetting or advisory')
    workspace = Path(workspace).resolve()
    values.setdefault('CONSENSE_RETRIEVAL_DATA', str(workspace / '.local/oer/retrieval-state'))
    if not isinstance(values['CONSENSE_RETRIEVAL_DATA'], str) or not values['CONSENSE_RETRIEVAL_DATA'].strip():
        raise ValueError('CONSENSE_RETRIEVAL_DATA must name a separate OER state directory')
    state = Path(values['CONSENSE_RETRIEVAL_DATA']).expanduser().resolve()
    if state in (workspace, (workspace / 'tmp/vetting_server').resolve()):
        raise ValueError('The OER entry point requires a separate state path from the legacy server')
    values['CONSENSE_RETRIEVAL_DATA'] = str(state)
    values.setdefault('HF_HOME', str(workspace / '.local/oer/hf'))
    values['CONSENSE_WORKSPACE_ROOT'] = str(workspace)
    if not values.get('CONSENSE_SL_SCOPE_MANIFEST'):
        raise ValueError('CONSENSE_SL_SCOPE_MANIFEST is required; prepare the original-source scope upstream')
    return values


def assert_canonical_scope(chunks, policy, *, module='vetting'):
    """Reject a whole unfiltered request before signatures, model calls or writes."""
    for chunk in chunks:
        candidate = chunk.get('parserEvidence')
        if (chunk.get('schema') == PARSER_SCHEMA
                or isinstance(candidate, dict) and candidate.get('schema') == PARSER_SCHEMA):
            raise WindowError('PARSER_CANDIDATE_REQUIRES_UPSTREAM_PROJECTION',
                              'Full dual-parser evidence is a candidate ledger, not a canonical chunk',
                              chunkId=chunk.get('id'), inputContract=INPUT_CONTRACT,
                              qualityStatusChanged=False,
                              required='source-bound canonical projection preserving unknown and review status')
    try:
        result = policy.apply(chunks, module=module)
    except CompetitionScopeError as error:
        raise WindowError('COMPETITION_SCOPE_INVALID',
                          'Source scope cannot be established; prepare canonical input upstream',
                          code=error.code, chunkId=error.chunk_id,
                          details=error.details, policyIdentity=policy.policyIdentity) from error
    if result['excluded']:
        raise WindowError('COMPETITION_SCOPE_UNFILTERED_REQUEST',
                          'Excluded inputs must be removed upstream before the Java corpus/index receipt is formed',
                          excluded=result['excluded'], policyIdentity=policy.policyIdentity,
                          suppliedChunks=len(chunks), inputUnchanged=True,
                          partialIndexWritten=False)
    return result


class OerWindowRuntime(WindowRuntime):
    """Scope and wrapper bytes are part of both index and health identities."""

    def __init__(self, backend, policy, *, target_module='vetting'):
        super().__init__(backend)
        self.scope_policy = policy
        fail(target_module in ('drafting', 'vetting', 'advisory'), 'INVALID_TARGET_MODULE',
             'OER index target must be drafting, vetting or advisory')
        self.target_module = target_module
        self.structural_recipe = StructuralRecipe('dual-structure-v1', 2048, 2048, True)
        self.overlap = 64
        fail(not self.cache_root, 'OER_STARTUP_RECIPE', 'OER cannot reuse a legacy fixed-vector cache')
        fail(backend.device() == 'cuda', 'OER_CUDA_REQUIRED',
             'OER requires CUDA; CPU execution and fallback are disabled')
        fail(backend.SERIAL_OFFLOAD is True, 'OER_STARTUP_RECIPE',
             'OER CUDA FP32 requires serial model offload')
        fail(backend.MODEL_DTYPE == 'float32' and all(
            backend.MODEL_SETTINGS[kind]['maxTokens'] == 512 for kind in ('embedding', 'reranker')),
            'OER_STARTUP_RECIPE', 'OER requires short 512-token windows and FP32')

    def algorithms(self):
        algorithms = super().algorithms()
        current = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                   for name in _WRAPPER_HASHES}
        fail(current == _WRAPPER_HASHES, 'SOFTWARE_CHANGED_SINCE_IMPORT',
             'Scope/wrapper bytes differ from the imported OER entry point')
        return {**algorithms, **current}

    def signature(self, chunks):
        assert_canonical_scope(chunks, self.scope_policy, module=self.target_module)
        return {**super().signature(chunks), 'competitionScope': self.scope_policy.policyIdentity,
                'oerInputContract': INPUT_CONTRACT, 'oerIndexRecipe': FRESH_RECIPE,
                'oerTargetModule': self.target_module}

    def retrieval_recipe(self, *, observe_tokenizer_identity=True):
        return {**super().retrieval_recipe(observe_tokenizer_identity=observe_tokenizer_identity),
                'competitionScope': self.scope_policy.policyIdentity,
                'oerInputContract': INPUT_CONTRACT, 'oerIndexRecipe': FRESH_RECIPE,
                'oerTargetModule': self.target_module}

    def validate_metadata(self, metadata):
        assert_canonical_scope(metadata['chunks'], self.scope_policy, module=self.target_module)
        return super().validate_metadata(metadata)


def create_app(policy, *, backend=None, artifact_store=None, source_registry=None, target_module='vetting'):
    """Create one isolated HTTP app; backend imports remain explicit and lazy."""
    from fastapi import FastAPI, HTTPException
    from fastapi.responses import Response
    from artifact_store import ArtifactStore
    from canonical_projection import project_canonical
    from oer_source_registry import CanonicalSourceRegistry
    from oer_request_evidence import RequestEvidence
    backend = importlib.import_module('server') if backend is None else backend
    fail(all(getattr(backend, name, None) is None for name in ('_embedding', '_reranker', '_database')),
         'OER_STARTUP_RECIPE', 'Start OER in its own process before any legacy model or database is loaded')
    backend._windows_runtime = OerWindowRuntime(backend, policy, target_module=target_module)
    app = FastAPI(title='ConSense explicit OER retrieval', version='1.0.0')
    artifact_store = artifact_store or ArtifactStore(Path(backend.STATE) / 'artifacts')
    source_registry = source_registry or CanonicalSourceRegistry(Path(backend.STATE) / 'canonical', artifact_store)
    app.state.artifact_store = artifact_store
    app.state.source_registry = source_registry
    journal = RequestEvidence(Path(backend.STATE) / 'run-evidence')
    previous_observer = getattr(backend, '_window_observer', None)
    def observe_run(stage, payload):
        if previous_observer is not None:
            previous_observer(stage, payload)
        journal.observe(stage, payload)
    backend._window_observer = observe_run
    app.state.request_evidence = journal

    def rejected(error):
        return HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata})

    def scope_receipt(chunks):
        result = assert_canonical_scope(chunks, policy, module=target_module)
        return {'policyIdentity': policy.policyIdentity, 'inputContract': INPUT_CONTRACT,
                'targetModule': target_module,
                'suppliedChunks': len(chunks), 'indexedChunks': len(chunks),
                'filtering': 'none; reject any excluded input',
                'unclassified': result['unclassified'], 'sourceQualityChanged': False}

    @contextmanager
    def source_frame():
        factory = getattr(source_registry, 'validation_frame', None)
        if factory is None:
            yield
            return
        try:
            with factory(software_guard=backend._windows_runtime.algorithms):
                yield
        except WindowError:
            raise
        except (ValueError, OSError) as error:
            raise WindowError('CANONICAL_REQUEST_FRAME_UNAVAILABLE',
                'Current complete source/CAS/software/binding proof cannot be verified',
                sourceQualityChanged=False) from error

    def health():
        result = backend.health()
        result['ocrEngine'] = 'External source-bound complete parsers; no OCR endpoint in this OER service'
        result['ocrRouteExposed'] = False
        result['loaded'].pop('ocr', None)
        result['limitations'] = [item for item in result['limitations']
                                if item != 'OCR does not interpret table cells or deletion state']
        result['oer'] = {'recipe': FRESH_RECIPE, 'inputContract': INPUT_CONTRACT,
                         'targetModule': target_module,
                         'scopePolicy': policy.policyIdentity, 'sourceQualityVerifiedByHealth': False,
                         'parserCandidatesAcceptedByRetrieval': False,
                         'canonicalProjectionEndpoint': '/project',
                         'indexStatusContract': INDEX_STATUS_CONTRACT,
                         'requestEvidence': 'private request/response and actual window/model events in service state',
                         'assetReturnContract': 'source-and-projection-bound-content-addressed-assets-v1',
                         'limitations': ['Health does not run either BGE model or certify source quality',
                             'SL requires original-file SHA and physical-page mapping from canonical upstream',
                             'Dual-parser candidates require upstream review/projection; unknown stays unknown']}
        if getattr(source_registry, 'validation_frame', None) is not None:
            reuse_policy = getattr(source_registry, 'derived_reconstruction_policy', None)
            result['oer']['requestSourceValidation'] = {
                'version': 'complete-actual-source-request-frame-v1',
                'routes': ['/index-canonical', '/index-canonical/status', '/retrieve'],
                'validationScope': 'one_normal_journalled_request',
                'completeReconstruction': ('cold_once_per_source_within_request_or_byte_bound_replay'
                    if reuse_policy is not None else 'once_per_source_within_request'),
                'publicWireRead': 'strict_bounded_each_reuse',
                'actualCasVerification': 'all_complete_used_files_at_entry_and_fresh_exit',
                'exitGuards': ['public_roots', 'revision_aliases', 'actual_cas', 'runtime_software'],
                'crossRequestVerdictReuse': False,
                'unrelatedOuterCasSession': 'reject_before_body',
                'lateFailure': 'failed_normal_journal_before_response',
                'healthPerformsSourceValidation': False}
            if reuse_policy is not None:
                result['oer']['requestSourceValidation']['crossRequestDerivedResultReuse'] = reuse_policy()
        return result

    def index(request):
        try:
            chunks = [chunk.model_dump(exclude_none=True) for chunk in request.chunks]
            receipt = scope_receipt(chunks)
            return {**backend.index(request), 'competitionScope': receipt}
        except WindowError as error:
            raise rejected(error) from error

    def retrieve(request):
        try:
            # Inspect persisted originals before read_project can calculate a
            # tokenizer-bound signature or any vector/query inference occurs.
            with source_frame():
                key = backend.project_key(request.projectId)
                with backend.LOCK:
                    stored = backend._projects.get(key)
                    if stored is None:
                        path = backend.metadata_path(key)
                        if path.exists():
                            stored = json.loads(path.read_text(encoding='utf-8'))
                    if stored is not None:
                        assert_canonical_scope(stored['chunks'], policy, module=target_module)
                    return {**backend.retrieve(request), 'competitionScope': {
                        'policyIdentity': policy.policyIdentity, 'inputContract': INPUT_CONTRACT,
                        'targetModule': target_module,
                        'sourceQualityChanged': False}}
        except WindowError as error:
            raise rejected(error) from error

    def project(request):
        try:
            fail(request.adoptionManifest.get('scope', {}).get('scopePolicySha256')
                 == policy.policyIdentity['manifestSha256'], 'PROJECTION_SCOPE_POLICY_MISMATCH',
                 'Adoption must bind the actual policy of this OER process')
            projection = project_canonical(request.bundle, request.adoptionManifest,
                                           artifact_store=artifact_store)
            module = projection['projectionIdentity']['module']
            assert_canonical_scope(projection['chunks'], policy, module=module)
            receipt = source_registry.publish(projection)
            return {**receipt, 'projection': projection, 'sourceQualityChangedByRegistry': False}
        except WindowError as error:
            raise rejected(error) from error
        except ValueError as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', 'CANONICAL_PROJECTION_REJECTED'),
                                      'message': str(error)}) from error

    def canonical_index(request):
        try:
            fail(request.module == target_module, 'INDEX_TARGET_MODULE_MISMATCH',
                 'This process indexes only its configured module; use the matching OER process',
                 requestedModule=request.module, targetModule=target_module)
            fail(len(set(request.projectionSha256)) == len(request.projectionSha256),
                 'DUPLICATE_CANONICAL_REVISION', 'One immutable projection can appear only once in a corpus')
            with source_frame():
                chunks = []
                for address in request.projectionSha256:
                    snapshot = source_registry.load(address)
                    fail(snapshot['manifest']['scope']['scopePolicySha256']
                         == policy.policyIdentity['manifestSha256'], 'PROJECTION_SCOPE_POLICY_MISMATCH',
                         'Re-project sources reviewed against a different scope policy before indexing',
                         projectionSha256=address)
                    chunks.extend(source_registry.chunks(address, module=request.module))
                assert_canonical_scope(chunks, policy, module=request.module)
                body = backend.IndexRequest(projectId=request.projectId, chunks=chunks)
                result = index(body)
                return {**result, 'canonicalProjections': request.projectionSha256,
                        'targetModule': request.module}
        except WindowError as error:
            raise rejected(error) from error
        except ValueError as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', 'CANONICAL_INDEX_REJECTED'),
                                      'message': str(error)}) from error

    def canonical_index_status(request):
        key = backend.project_key(request.projectId)
        try:
            fail(request.module == target_module, 'INDEX_TARGET_MODULE_MISMATCH',
                 'Status must use its configured module process')
            fail(len(set(request.projectionSha256)) == len(request.projectionSha256),
                 'DUPLICATE_CANONICAL_REVISION', 'Status requires exact unique selected projections')
            fail(set(request.sourcePageEvidence).issubset(request.projectionSha256),
                 'SOURCE_PAGE_INDEX_PINS_OUTSIDE_SCOPE', 'Every explicit pin must name a selected projection')
            runtime = backend._windows_runtime
            pins_context = getattr(runtime, 'source_page_index_pins', None)
            fail(pins_context is not None or not request.sourcePageEvidence,
                 'SOURCE_PAGE_INDEX_PINS_UNSUPPORTED', 'This runtime cannot validate source-page pins')
            token = pins_context.set(dict(sorted(request.sourcePageEvidence.items()))) if pins_context is not None else None
            try:
                with source_frame():
                    chunks = []
                    for address in request.projectionSha256:
                        snapshot = source_registry.load(address)
                        fail(snapshot['manifest']['scope']['scopePolicySha256'] == policy.policyIdentity['manifestSha256'],
                             'PROJECTION_SCOPE_POLICY_MISMATCH', 'Current sources must bind this exact active scope')
                        chunks.extend(source_registry.chunks(address, module=request.module))
                    assert_canonical_scope(chunks, policy, module=request.module)
                    if not runtime.structural_recipe.enabled:
                        chunks.sort(key=lambda chunk: chunk['id'])
                    with backend.LOCK:
                        # Never let a Java or Python memory receipt stand in for
                        # the current persisted metadata and normal vector seal.
                        backend._projects.pop(key, None)
                        path = backend.metadata_path(key)
                        def file_identity():
                            digest = hashlib.sha256()
                            with path.open('rb') as handle:
                                for block in iter(lambda: handle.read(1024 * 1024), b''):
                                    digest.update(block)
                            stat = path.stat()
                            return (stat.st_size, stat.st_mtime_ns, digest.hexdigest())
                        before = file_identity() if path.exists() else None
                        stored = backend.read_project(key)
                        fail(stored['chunks'] == chunks, 'INDEX_STATUS_CURRENT_SOURCE_MISMATCH',
                             'Complete persisted parents differ from the exact current selected sources')
                        runtime.validate_metadata(stored)
                        signed = runtime.signature(chunks)
                        fail(stored['signature'] == signed, 'INDEX_STATUS_CURRENT_RUNTIME_MISMATCH',
                             'Persisted signature differs from the exact current source/runtime recipe')
                        from index_integrity import verify_reuse
                        collection = 'project_' + backend.digest(key)[:16] + '_' + backend.digest(signed)[:16]
                        verify_reuse(backend.database() if chunks else None, stored, key=key,
                                     collection=collection, chunks=chunks, signature=signed)
                        fail(before is not None and before == file_identity(), 'INDEX_STATUS_METADATA_CHANGED',
                             'Persisted metadata changed during the read-only status operation')
                        return {'projectId': key, 'ready': True, 'readOnly': True,
                            'indexStatusContract': INDEX_STATUS_CONTRACT, 'indexed': len(chunks),
                            'vectorPoints': stored['vectorPoints'], 'signature': backend.digest(signed),
                            'contract': runtime.contract(), 'canonicalProjections': list(request.projectionSha256),
                            'targetModule': request.module, 'sourcePageEvidence': dict(sorted(request.sourcePageEvidence.items())),
                            'competitionScope': scope_receipt(chunks)}
            finally:
                if pins_context is not None:
                    pins_context.reset(token)
        except WindowError as error:
            backend._projects.pop(key, None)
            raise rejected(error) from error
        except HTTPException:
            backend._projects.pop(key, None)
            raise
        except (ValueError, OSError, KeyError, TypeError) as error:
            backend._projects.pop(key, None)
            raise HTTPException(422, {'kind': 'CANONICAL_INDEX_STATUS_UNAVAILABLE',
                'message': 'Existing exact source-bound index could not be verified'}) from error

    def canonical_snapshot(projection_sha256: str, module: str = 'vetting'):
        try:
            chunks = source_registry.chunks(projection_sha256, module=module)
            assert_canonical_scope(chunks, policy, module=module)
            return source_registry.load(projection_sha256)
        except (ValueError, WindowError) as error:
            raise HTTPException(404, 'Canonical source revision is unavailable') from error

    def source_asset(projection_sha256: str, source_sha256: str, asset_id: str, module: str = 'vetting'):
        try:
            chunks = source_registry.chunks(projection_sha256, module=module)
            assert_canonical_scope(chunks, policy, module=module)
            data, media, reference = source_registry.asset(projection_sha256, source_sha256,
                                                          asset_id, module=module)
            return Response(data, media_type=media, headers={'X-Content-Type-Options': 'nosniff',
                'ETag': '"' + reference['sha256'] + '"', 'Cache-Control': 'private, no-cache',
                'X-Source-Sha256': source_sha256, 'X-Projection-Sha256': projection_sha256})
        except (ValueError, WindowError, OSError) as error:
            raise HTTPException(404, 'Source-bound evidence asset is unavailable') from error

    # Avoid deferred dynamic request annotations: FastAPI must see the actual
    # backend Pydantic types when registering these explicit endpoints.
    index.__annotations__['request'] = backend.IndexRequest
    retrieve.__annotations__['request'] = backend.RetrieveRequest
    project.__annotations__['request'] = ProjectionRequest
    canonical_index.__annotations__['request'] = CanonicalIndexRequest
    canonical_index_status.__annotations__['request'] = CanonicalIndexStatusRequest
    index = journal.wrap('/index', index)
    retrieve = journal.wrap('/retrieve', retrieve)
    canonical_index = journal.wrap('/index-canonical', canonical_index)
    canonical_index_status = journal.wrap('/index-canonical/status', canonical_index_status)
    app.get('/health')(health)
    app.post('/index')(index)
    app.post('/retrieve')(retrieve)
    app.post('/project')(project)
    app.post('/index-canonical')(canonical_index)
    app.post('/index-canonical/status')(canonical_index_status)
    app.get('/canonical/{projection_sha256}')(canonical_snapshot)
    app.get('/canonical/{projection_sha256}/sources/{source_sha256}/assets/{asset_id}')(source_asset)
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8868)
    parser.add_argument('--scope-manifest')
    parser.add_argument('--scope-manifest-sha256')
    args = parser.parse_args()
    supplied = dict(os.environ)
    if args.scope_manifest:
        supplied['CONSENSE_SL_SCOPE_MANIFEST'] = args.scope_manifest
    if args.scope_manifest_sha256:
        supplied['CONSENSE_SL_SCOPE_MANIFEST_SHA256'] = args.scope_manifest_sha256
    values = configure_environment(supplied, resolve_workspace(HERE, supplied))
    policy = load_policy(values['CONSENSE_SL_SCOPE_MANIFEST'],
                         values.get('CONSENSE_SL_SCOPE_MANIFEST_SHA256'))
    os.environ.update(values)
    import uvicorn
    uvicorn.run(create_app(policy, target_module=values['CONSENSE_OER_TARGET_MODULE']),
                host=args.host, port=args.port, workers=1)


if __name__ == '__main__':
    main()
