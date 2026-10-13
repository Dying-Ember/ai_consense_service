"""Versioned OER entry point with explicit native DOCX public transport support.

Uses the unchanged scanned routes/guards and an actual-CAS mixed registry.
Run in a separate process/state directory; importing does not load a model.
The native visual rendition is still unavailable and unknown stays unknown.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib
import os
from pathlib import Path

from artifact_store import ArtifactStore
from competition_scope import load_policy
from native_canonical_projection import SCHEMA, produce_native
from native_projection_transport import STORAGE_SHAPE, VERSION, publish_native_transport
from native_source_registry import NativeCanonicalSourceRegistry
from oer_server import (OerWindowRuntime, ProjectionRequest, assert_canonical_scope,
                        configure_environment, create_app)
from token_windows import WindowError, fail
from workspace_root import resolve_workspace


HERE = Path(__file__).resolve().parent
NATIVE_ALGORITHMS = ('native_source_registry.py', 'oer_native_server.py',
                     'native_canonical_projection.py', 'native_projection_transport.py',
                     'native_observation.py', 'source_reference.py')
_NATIVE_HASHES = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                  for name in NATIVE_ALGORITHMS}
NATIVE_CAPABILITY = {'schema': SCHEMA, 'producerKind': 'native_docx',
                    'projectionVersion': VERSION, 'storageShape': STORAGE_SHAPE,
                    'projectionEndpoint': '/project/native',
                    'artifactReadValidation': 'actual-cas-every-read'}


def configure_native_environment(environ, workspace):
    values = dict(environ)
    workspace = Path(workspace).resolve()
    legacy_default = workspace / '.local/oer/retrieval-state'
    values.setdefault('CONSENSE_RETRIEVAL_DATA', str(workspace / '.local/oer/native-retrieval-state'))
    if Path(values['CONSENSE_RETRIEVAL_DATA']).expanduser().resolve() == legacy_default.resolve():
        raise ValueError('Native OER requires a separate state directory from the scanned-only default')
    return configure_environment(values, workspace)


class NativeOerWindowRuntime(OerWindowRuntime):
    def __init__(self, backend, policy, *, source_registry, target_module='vetting'):
        self.source_registry = source_registry
        super().__init__(backend, policy, target_module=target_module)

    def _validate_native_sources(self, chunks):
        try:
            self.source_registry.validate_native_chunks(chunks)
        except (ValueError, OSError) as error:
            raise WindowError(getattr(error, 'code', 'NATIVE_CANONICAL_SOURCE_UNAVAILABLE'),
                              'Current native canonical source proof cannot be verified',
                              sourceQualityChanged=False) from error

    def signature(self, chunks):
        # server.read_project also invokes signature before query inference.
        self._validate_native_sources(chunks)
        return super().signature(chunks)

    def retrieve(self, request):
        result = super().retrieve(request)
        # A source mutation during a long query must not escape in its answer.
        stored = self.s._projects[self.s.project_key(request.projectId)]
        self._validate_native_sources(stored['chunks'])
        return result

    def algorithms(self):
        base = super().algorithms()
        current = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                   for name in NATIVE_ALGORITHMS}
        fail(current == _NATIVE_HASHES, 'SOFTWARE_CHANGED_SINCE_IMPORT',
             'Native wrapper bytes changed; start a frozen matching process')
        return {**base, **current}


def create_native_app(policy, *, backend=None, artifact_store=None, target_module='vetting'):
    from fastapi import HTTPException
    backend = importlib.import_module('server') if backend is None else backend
    artifact_store = artifact_store or ArtifactStore(Path(backend.STATE) / 'artifacts')
    registry = NativeCanonicalSourceRegistry(Path(backend.STATE) / 'canonical', artifact_store)
    app = create_app(policy, backend=backend, artifact_store=artifact_store,
                     source_registry=registry, target_module=target_module)
    backend._windows_runtime = NativeOerWindowRuntime(backend, policy, source_registry=registry,
                                                    target_module=target_module)

    old_health_route = next(route for route in app.routes if getattr(route, 'path', None) == '/health')
    old_health = old_health_route.endpoint
    app.router.routes.remove(old_health_route)

    def health():
        result = old_health()
        result['oer']['nativeCanonical'] = dict(NATIVE_CAPABILITY)
        result['oer']['limitations'].append('Native DOCX has exact OOXML positions; verified visual rendition is unavailable')
        return result
    app.get('/health')(health)

    def native_project(request):
        try:
            fail(request.adoptionManifest.get('scope', {}).get('scopePolicySha256')
                 == policy.policyIdentity['manifestSha256'], 'PROJECTION_SCOPE_POLICY_MISMATCH',
                 'Native adoption must bind this process scope policy')
            internal = produce_native(request.bundle, request.adoptionManifest, artifact_store=artifact_store)
            projection = publish_native_transport(internal, artifact_store=artifact_store)
            module = projection['projectionIdentity']['module']
            assert_canonical_scope(projection['chunks'], policy, module=module)
            receipt = registry.publish(projection)
            return {**receipt, 'projection': projection, 'sourceQualityChangedByRegistry': False}
        except WindowError as error:
            raise HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error
        except (ValueError, OSError) as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', 'NATIVE_PROJECTION_REJECTED'),
                                      'message': str(error)}) from error
    native_project.__annotations__['request'] = ProjectionRequest
    app.post('/project/native')(app.state.request_evidence.wrap('/project/native', native_project))
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8870)
    parser.add_argument('--scope-manifest')
    parser.add_argument('--scope-manifest-sha256')
    args = parser.parse_args()
    supplied = dict(os.environ)
    if args.scope_manifest:
        supplied['CONSENSE_SL_SCOPE_MANIFEST'] = args.scope_manifest
    if args.scope_manifest_sha256:
        supplied['CONSENSE_SL_SCOPE_MANIFEST_SHA256'] = args.scope_manifest_sha256
    values = configure_native_environment(supplied, resolve_workspace(HERE, supplied))
    policy = load_policy(values['CONSENSE_SL_SCOPE_MANIFEST'],
                         values.get('CONSENSE_SL_SCOPE_MANIFEST_SHA256'))
    os.environ.update(values)
    app = create_native_app(policy, target_module=values['CONSENSE_OER_TARGET_MODULE'])
    import uvicorn
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == '__main__':
    main()
