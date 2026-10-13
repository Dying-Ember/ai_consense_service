"""Independent actual-CAS PDF OER entry point; no model loaded at import."""
from __future__ import annotations

import argparse
import hashlib
import importlib
import os
from pathlib import Path

from pydantic import BaseModel, ConfigDict
from artifact_store import ArtifactStore, DESCRIPTOR_SCHEMA
from canonical_projection import _bytes
from competition_scope import load_policy
from native_pdf_canonical_projection import SCHEMA, _json, produce_native_pdf
from native_pdf_projection_transport import (STORAGE_SHAPE, VERSION, MAX_SNAPSHOT_BYTES,
                                            publish_native_pdf_transport)
from native_pdf_source_registry import NativePdfCanonicalSourceRegistry
from oer_native_server import NativeOerWindowRuntime
from oer_server import assert_canonical_scope, configure_environment, create_app
from token_windows import WindowError, fail
from workspace_root import resolve_workspace


HERE = Path(__file__).resolve().parent
MAX_MESSAGE_BYTES = MAX_SNAPSHOT_BYTES
NATIVE_PDF_ALGORITHMS = ('native_pdf_source_registry.py', 'oer_native_pdf_server.py',
    'native_pdf_canonical_projection.py', 'native_pdf_table_projection.py', 'native_pdf_projection_transport.py', 'native_pdf_observation.py',
    'parser_capture.py', 'pdf_selected_source.py', 'selected_source_raster.py')
_NATIVE_PDF_HASHES = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest() for name in NATIVE_PDF_ALGORITHMS}
NATIVE_PDF_CAPABILITY = {'schema': SCHEMA, 'producerKind': 'native_pdf', 'projectionVersion': VERSION,
    'storageShape': STORAGE_SHAPE, 'projectionEndpoint': '/project/native-pdf',
    'projectionInputs': 'actual-cas-three-descriptors-only', 'artifactReadValidation': 'actual-cas-every-read',
    'maximumMessageBytes': MAX_SNAPSHOT_BYTES, 'tableAndAppearanceText': 'source_reviewed_whole_table_groups; appearance_text_unsupported',
    'visualReturnStatus': 'not_published_by_native_pdf_transport'}


class NativePdfProjectionRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    nativeBundleRef: dict
    dualBundleRef: dict
    adoptionManifestRef: dict


def configure_native_pdf_environment(environ, workspace):
    values = dict(environ); workspace = Path(workspace).resolve()
    values.setdefault('CONSENSE_RETRIEVAL_DATA', str(workspace / '.local/oer/native-pdf-retrieval-state'))
    selected = Path(values['CONSENSE_RETRIEVAL_DATA']).expanduser().resolve()
    if selected in {(workspace / '.local/oer' / name).resolve() for name in ('retrieval-state', 'native-retrieval-state')}:
        raise ValueError('Native PDF OER requires separate state from scanned and DOCX defaults')
    return configure_environment(values, workspace)


class NativePdfOerWindowRuntime(NativeOerWindowRuntime):
    def algorithms(self):
        base = super().algorithms()
        current = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest() for name in NATIVE_PDF_ALGORITHMS}
        fail(current == _NATIVE_PDF_HASHES, 'SOFTWARE_CHANGED_SINCE_IMPORT',
             'Native PDF wrapper bytes changed; start a frozen matching process')
        return {**base, **current}


class _WireLimits:
    """Buffer bounded envelopes so a large public reply never starts sending."""
    def __init__(self, app, maximum, strict_json_paths=None):
        fail(type(maximum) is int and 1024 <= maximum <= MAX_SNAPSHOT_BYTES,
             'NATIVE_PDF_MESSAGE_LIMIT_INVALID', 'Public message limit must stay within the hard 16MiB cap')
        self.app, self.maximum = app, maximum
        self.strict_json_paths = frozenset(strict_json_paths or ('/project/native-pdf',))

    async def __call__(self, scope, receive, send):
        from fastapi.responses import JSONResponse
        if scope['type'] != 'http': return await self.app(scope, receive, send)
        async def reject(status, kind, message):
            response = JSONResponse({'detail': {'kind': kind, 'message': message}}, status_code=status)
            await response(scope, receive, send)
        body = bytearray()
        while True:
            event = await receive()
            if event['type'] != 'http.request': return
            incoming = event.get('body', b'')
            if len(body) + len(incoming) > self.maximum:
                return await reject(413, 'NATIVE_PDF_MESSAGE_TOO_LARGE', 'Public request exceeds the configured whole-message cap')
            body.extend(incoming)
            if not event.get('more_body', False): break
        if scope['path'] in self.strict_json_paths and body:
            try: _json(bytes(body))
            except ValueError:
                return await reject(422, 'NATIVE_PDF_REQUEST_JSON_INVALID', 'Request JSON must be strict and unambiguous')
        delivered = False
        async def replay():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {'type': 'http.request', 'body': bytes(body), 'more_body': False}
            return await receive()
        events, size, oversized = [], 0, False
        async def bounded_send(event):
            nonlocal size, oversized
            if event['type'] == 'http.response.body':
                size += len(event.get('body', b''))
                if size > self.maximum: oversized = True
            if not oversized: events.append(event)
        await self.app(scope, replay, bounded_send)
        if oversized:
            return await reject(422, 'NATIVE_PDF_MESSAGE_TOO_LARGE', 'Public response exceeds the configured whole-message cap')
        for event in events: await send(event)


def create_native_pdf_app(policy, *, backend=None, artifact_store=None, target_module='vetting',
                          source_registry=None, runtime_factory=None, app=None,
                          maximum_message_bytes=None, strict_json_paths=None):
    from fastapi import HTTPException
    backend = importlib.import_module('server') if backend is None else backend
    artifact_store = artifact_store or ArtifactStore(Path(backend.STATE) / 'artifacts')
    registry = source_registry or NativePdfCanonicalSourceRegistry(Path(backend.STATE) / 'canonical', artifact_store)
    if app is None:
        app = create_app(policy, backend=backend, artifact_store=artifact_store,
                         source_registry=registry, target_module=target_module)
    else:
        fail(app.state.source_registry is registry and app.state.artifact_store is artifact_store,
             'NATIVE_PDF_FACTORY_SOURCE_MISMATCH', 'Composed routes must share the exact registry and actual CAS store')
    backend._windows_runtime = (runtime_factory or NativePdfOerWindowRuntime)(
        backend, policy, source_registry=registry, target_module=target_module)
    maximum = MAX_MESSAGE_BYTES if maximum_message_bytes is None else maximum_message_bytes
    app.add_middleware(_WireLimits, maximum=maximum, strict_json_paths=strict_json_paths)
    old_health = next(route for route in app.routes if getattr(route, 'path', None) == '/health')
    old_endpoint = old_health.endpoint; app.router.routes.remove(old_health)
    def health():
        result = old_endpoint()
        result['oer']['nativePdfCanonical'] = {**NATIVE_PDF_CAPABILITY, 'maximumMessageBytes': maximum}
        result['oer']['limitations'].append('PDF native text preserves unknown visibility; whole-table groups require complete source review and AP text remains unsupported')
        return result
    app.get('/health')(health)

    def actual(descriptor):
        fail(isinstance(descriptor, dict) and set(descriptor) == {'schema', 'storage', 'sha256', 'byteCount', 'relativePath', 'identityScope'}
             and descriptor.get('schema') == DESCRIPTOR_SCHEMA, 'NATIVE_PDF_ACTUAL_DESCRIPTOR_REQUIRED',
             'Provide only a complete local actual-CAS artifact descriptor')
        value = _json(artifact_store.read_asset(descriptor))
        fail(isinstance(value, dict), 'NATIVE_PDF_CAS_OBJECT_REQUIRED', 'Actual native, dual and adoption CAS inputs must be JSON objects')
        return value

    def native_pdf_project(request):
        try:
            native, dual, adoption = [actual(getattr(request, field)) for field in
                                    ('nativeBundleRef', 'dualBundleRef', 'adoptionManifestRef')]
            fail(isinstance(adoption.get('scope'), dict), 'PROJECTION_SCOPE_POLICY_MISMATCH', 'PDF adoption requires its actual scope object')
            fail(adoption['scope'].get('scopePolicySha256') == policy.policyIdentity['manifestSha256'],
                 'PROJECTION_SCOPE_POLICY_MISMATCH', 'PDF adoption must bind the actual process scope policy')
            internal = produce_native_pdf(native, dual, adoption, artifact_store=artifact_store)
            public = publish_native_pdf_transport(internal, artifact_store=artifact_store)
            assert_canonical_scope(public['chunks'], policy, module=public['projectionIdentity']['module'])
            preview = {'projectionSha256': public['projectionSha256'], 'chunks': len(public['chunks']),
                       'assets': len(public['assetRefs']), 'immutable': True, 'qualityVerifiedByRegistry': False}
            result = {**preview, 'projection': public, 'sourceQualityChangedByRegistry': False}
            fail(len(_bytes(result)) <= maximum, 'NATIVE_PDF_MESSAGE_TOO_LARGE',
                 'Projection plus receipt envelope exceeds the whole-message cap')
            receipt = registry.publish(public)
            fail(receipt == preview, 'NATIVE_PDF_REGISTRY_RECEIPT_MISMATCH', 'Published receipt differs from the validated response envelope')
            return result
        except WindowError as error:
            raise HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error
        except (ValueError, OSError) as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', 'NATIVE_PDF_PROJECTION_REJECTED'), 'message': str(error)}) from error
    native_pdf_project.__annotations__['request'] = NativePdfProjectionRequest
    app.post('/project/native-pdf')(app.state.request_evidence.wrap('/project/native-pdf', native_pdf_project))
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1'); parser.add_argument('--port', type=int, default=8873)
    parser.add_argument('--scope-manifest'); parser.add_argument('--scope-manifest-sha256')
    args = parser.parse_args(); supplied = dict(os.environ)
    if args.scope_manifest: supplied['CONSENSE_SL_SCOPE_MANIFEST'] = args.scope_manifest
    if args.scope_manifest_sha256: supplied['CONSENSE_SL_SCOPE_MANIFEST_SHA256'] = args.scope_manifest_sha256
    values = configure_native_pdf_environment(supplied, resolve_workspace(HERE, supplied))
    policy = load_policy(values['CONSENSE_SL_SCOPE_MANIFEST'], values.get('CONSENSE_SL_SCOPE_MANIFEST_SHA256'))
    os.environ.update(values)
    app = create_native_pdf_app(policy, target_module=values['CONSENSE_OER_TARGET_MODULE'])
    import uvicorn
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == '__main__': main()
