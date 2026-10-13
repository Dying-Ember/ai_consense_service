"""Isolated OER entry point for one native document with bounded whole-unit batches.

Old scan and native services retain their bytes and state directories. Actual
CAS is revalidated for root, batch, persisted-index reads and after retrieval.
This transport does not certify source semantics or provide DOCX rendition.
"""
from __future__ import annotations

from contextlib import nullcontext

import argparse
import hashlib
import importlib
import os
from pathlib import Path

from artifact_store import ArtifactStore
from competition_scope import load_policy
from native_canonical_projection import produce_native
from native_collection_registry import NativeCollectionSourceRegistry
from native_observation import _strict_json
from native_projection_collection import SCHEMA as COLLECTION_SCHEMA, publish_native_collection
from native_projection_transport import MAX_SNAPSHOT_BYTES, publish_native_transport
from oer_native_server import (NATIVE_CAPABILITY, NativeOerWindowRuntime,
                               configure_native_environment)
from oer_server import ProjectionRequest, assert_canonical_scope, create_app
from pydantic import BaseModel, ConfigDict
from token_windows import WindowError, fail
from workspace_root import resolve_workspace


HERE = Path(__file__).resolve().parent
COLLECTION_ALGORITHMS = ('native_projection_collection.py', 'native_collection_registry.py',
                         'native_collection_envelope.py',
                         'oer_native_collection_server.py')
_COLLECTION_HASHES = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                      for name in COLLECTION_ALGORITHMS}
COLLECTION_CAPABILITY = {
    'schema': COLLECTION_SCHEMA, 'version': 'whole-native-units-ordered-cas-document-v1',
    'batchSchema': 'consense.native-document-batch.v1',
    'projectionEndpoint': '/project/native-collection',
    'batchEndpoint': '/canonical/{projectionSha256}/batches/{ordinal}',
    'envelopeBatchSchema': 'consense.native-document-envelope-batch.v1',
    'envelopeVersion': 'complete-header-whole-metadata-records-v1',
    'envelopeBatchEndpoint': '/canonical/{projectionSha256}/envelopes/{ordinal}',
    'completeHeaderTransport': 'root-enumerated-whole-metadata-record-batches',
    'registrationIdentity': 'one-document-root', 'maximumResponseBytes': MAX_SNAPSHOT_BYTES,
    'maximumRootBytes': MAX_SNAPSHOT_BYTES, 'maximumBatchBytes': MAX_SNAPSHOT_BYTES,
    'unitPolicy': 'whole-adopted-unit-source-order', 'artifactReadValidation': 'actual-cas-every-read'}


class NativeCollectionRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    internalProjectionDescriptor: dict


def configure_collection_environment(environ, workspace):
    values = dict(environ)
    workspace = Path(workspace).resolve()
    values.setdefault('CONSENSE_RETRIEVAL_DATA', str(workspace / '.local/oer/native-collection-retrieval-state'))
    if Path(values['CONSENSE_RETRIEVAL_DATA']).expanduser().resolve() == (workspace / '.local/oer/native-retrieval-state').resolve():
        raise ValueError('Native collection OER requires separate state from the old native default')
    return configure_native_environment(values, workspace)


class NativeCollectionRuntime(NativeOerWindowRuntime):
    def algorithms(self):
        base = super().algorithms()
        current = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                   for name in COLLECTION_ALGORITHMS}
        fail(current == _COLLECTION_HASHES, 'SOFTWARE_CHANGED_SINCE_IMPORT',
             'Native collection wrapper bytes changed; start a frozen matching process')
        return {**base, **current}


def create_collection_app(policy, *, backend=None, artifact_store=None, target_module='vetting',
                          source_registry=None, runtime_factory=None, canonical_snapshot_frame=None):
    from fastapi import HTTPException, Response
    from oer_source_registry import _serialize
    backend = importlib.import_module('server') if backend is None else backend
    artifact_store = artifact_store or ArtifactStore(Path(backend.STATE) / 'artifacts')
    registry = source_registry or NativeCollectionSourceRegistry(Path(backend.STATE) / 'canonical', artifact_store)
    app = create_app(policy, backend=backend, artifact_store=artifact_store,
                     source_registry=registry, target_module=target_module)
    backend._windows_runtime = (runtime_factory or NativeCollectionRuntime)(
        backend, policy, source_registry=registry, target_module=target_module)

    old_health_route = next(route for route in app.routes if getattr(route, 'path', None) == '/health')
    old_health = old_health_route.endpoint
    app.router.routes.remove(old_health_route)
    # The inherited snapshot route would return an internal full hydrated view.
    # Replace it with a bounded wire route; legacy scanned/native views remain.
    old_snapshot = next(route for route in app.routes if getattr(route, 'path', None) == '/canonical/{projection_sha256}')
    app.router.routes.remove(old_snapshot)

    def health():
        result = old_health()
        result['oer']['nativeCanonical'] = dict(NATIVE_CAPABILITY)
        result['oer']['nativeDocumentCollection'] = dict(COLLECTION_CAPABILITY)
        result['oer']['limitations'].append('Native document batches retain whole units; verified visual rendition is unavailable')
        return result

    def projection_policy(projection):
        manifest = projection['manifest']
        fail(manifest['scope']['scopePolicySha256'] == policy.policyIdentity['manifestSha256'],
             'PROJECTION_SCOPE_POLICY_MISMATCH', 'Native adoption must bind the actual process scope policy')
        assert_canonical_scope(projection['chunks'], policy, module=projection['projectionIdentity']['module'])

    def projection_error(error):
        if isinstance(error, WindowError):
            return HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata})
        return HTTPException(422, {'kind': getattr(error, 'code', 'NATIVE_PROJECTION_REJECTED'), 'message': str(error)})

    def projection_reply(receipt, projection):
        # The request journal retains a JSON value. Check the complete reply,
        # including its receipt wrapper, before FastAPI serializes that value.
        value = {**receipt, 'projection': projection, 'sourceQualityChangedByRegistry': False}
        fail(len(_serialize(value)) <= MAX_SNAPSHOT_BYTES, 'NATIVE_WIRE_RESPONSE_TOO_LARGE',
             'Complete publication reply exceeds the fixed transport maximum')
        return value

    def native_project(request):
        try:
            internal = produce_native(request.bundle, request.adoptionManifest, artifact_store=artifact_store)
            projection_policy(internal)
            projection = publish_native_transport(internal, artifact_store=artifact_store)
            receipt = registry.publish(projection)
            return projection_reply(receipt, projection)
        except (WindowError, ValueError, OSError) as error:
            raise projection_error(error) from error

    def collection_project(request):
        try:
            # A typed actual-CAS descriptor replaces a 400MB inline bundle; it
            # grants no arbitrary URL or filesystem read and performs no review.
            raw = artifact_store.read_asset(request.internalProjectionDescriptor)
            internal = _strict_json(raw)
            projection_policy(internal)
            root = publish_native_collection(internal, artifact_store=artifact_store)
            receipt = registry.publish(root)
            return projection_reply(receipt, root)
        except (WindowError, ValueError, OSError, KeyError, TypeError) as error:
            raise projection_error(error) from error

    def response(value, address):
        raw = _serialize(value)
        fail(len(raw) <= MAX_SNAPSHOT_BYTES, 'NATIVE_WIRE_RESPONSE_TOO_LARGE',
             'Root or complete batch exceeds the fixed transport maximum')
        return Response(raw, media_type='application/json', headers={
            'X-Projection-Sha256': address, 'Cache-Control': 'private, no-cache',
            'ETag': '"' + hashlib.sha256(raw).hexdigest() + '"', 'X-Content-Type-Options': 'nosniff'})

    def canonical_snapshot(projection_sha256: str, module: str = 'vetting'):
        try:
            # Only an explicitly composed caller supplies this frame. Build the
            # complete bounded response within it and return after every source,
            # actual-CAS and runtime exit guard succeeds, before ASGI can send.
            with canonical_snapshot_frame() if canonical_snapshot_frame is not None else nullcontext():
                wire, chunks = registry.wire(projection_sha256, module=module)
                assert_canonical_scope(chunks, policy, module=module)
                reply = response(wire, projection_sha256)
            return reply
        except (WindowError, ValueError, OSError) as error:
            raise HTTPException(404, 'Canonical source revision is unavailable') from error

    def canonical_batch(projection_sha256: str, ordinal: int, module: str = 'vetting'):
        try:
            batch = registry.batch(projection_sha256, ordinal, module=module)
            assert_canonical_scope(batch['chunks'], policy, module=module)
            return response(batch, projection_sha256)
        except (WindowError, ValueError, OSError) as error:
            raise HTTPException(404, 'Complete source-bound batch is unavailable') from error

    def canonical_envelope(projection_sha256: str, ordinal: int, module: str = 'vetting'):
        try:
            batch, chunks = registry.envelope_batch(projection_sha256, ordinal, module=module,
                                                    include_chunks=True)
            assert_canonical_scope(chunks, policy, module=module)
            return response(batch, projection_sha256)
        except (WindowError, ValueError, OSError) as error:
            raise HTTPException(404, 'Complete source-bound header batch is unavailable') from error

    native_project.__annotations__['request'] = ProjectionRequest
    collection_project.__annotations__['request'] = NativeCollectionRequest
    app.get('/health')(health)
    app.get('/canonical/{projection_sha256}')(canonical_snapshot)
    app.get('/canonical/{projection_sha256}/batches/{ordinal}')(canonical_batch)
    app.get('/canonical/{projection_sha256}/envelopes/{ordinal}')(canonical_envelope)
    app.post('/project/native')(app.state.request_evidence.wrap('/project/native', native_project))
    app.post('/project/native-collection')(app.state.request_evidence.wrap('/project/native-collection', collection_project))
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8871)
    parser.add_argument('--scope-manifest')
    parser.add_argument('--scope-manifest-sha256')
    args = parser.parse_args()
    supplied = dict(os.environ)
    if args.scope_manifest:
        supplied['CONSENSE_SL_SCOPE_MANIFEST'] = args.scope_manifest
    if args.scope_manifest_sha256:
        supplied['CONSENSE_SL_SCOPE_MANIFEST_SHA256'] = args.scope_manifest_sha256
    values = configure_collection_environment(supplied, resolve_workspace(HERE, supplied))
    policy = load_policy(values['CONSENSE_SL_SCOPE_MANIFEST'], values.get('CONSENSE_SL_SCOPE_MANIFEST_SHA256'))
    os.environ.update(values)
    import uvicorn
    uvicorn.run(create_collection_app(policy, target_module=values['CONSENSE_OER_TARGET_MODULE']),
                host=args.host, port=args.port)


if __name__ == '__main__':
    main()
