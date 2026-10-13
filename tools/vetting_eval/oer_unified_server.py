"""Isolated mixed-source OER entry point with one registry/CAS per module.

Reuses the existing scan, DOCX, document-collection and native-PDF factories.
No source is adopted by composition, and no model is loaded at import.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib
import os
from pathlib import Path

from artifact_store import ArtifactStore
from competition_scope import load_policy
from native_projection_transport import MAX_SNAPSHOT_BYTES
from oer_native_collection_server import NativeCollectionRuntime, create_collection_app
from oer_native_pdf_server import NativePdfOerWindowRuntime, create_native_pdf_app
from oer_server import configure_environment
from token_windows import fail
from unified_source_registry import UnifiedCanonicalSourceRegistry
from source_page_server import install_source_page_routes
from source_page_index import SourcePageIndexRuntime, install_source_page_index_route
from workspace_root import resolve_workspace
from native_pdf_source_family_server import ENDPOINT as FAMILY_ENDPOINT, install_native_pdf_source_family_route


HERE = Path(__file__).resolve().parent
UNIFIED_ALGORITHMS = ('unified_source_registry.py', 'oer_unified_server.py',
                      'source_page_registry.py', 'source_page_server.py', 'source_render_assets.py',
                      'source_page_index.py', 'parser_capture.py', 'native_pdf_source_family.py',
                      'native_pdf_source_family_server.py')
_UNIFIED_HASHES = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest() for name in UNIFIED_ALGORITHMS}
UNIFIED_CAPABILITY = {
    'version': 'actual-cas-mixed-source-registry-v1', 'registrationIdentity': 'one-source-root',
    'sourceKinds': ['scanned_pdf', 'native_docx_snapshot', 'native_docx_collection', 'native_pdf', 'native_pdf_source_family'],
    'artifactReadValidation': 'actual-cas-unique-per-validation-session-with-exit-rehash',
    'artifactReadValidationScope': 'canonical_load', 'maximumMessageBytes': MAX_SNAPSHOT_BYTES,
    'modulePolicy': 'process-target-and-source-eligible-modules', 'semanticAcceptanceByComposition': False}


class UnifiedOerWindowRuntime(SourcePageIndexRuntime, NativePdfOerWindowRuntime, NativeCollectionRuntime):
    # Both algorithm wrappers cooperate through super(), retaining both fixed
    # software identities and NativeOerWindowRuntime's pre/post-query CAS gates.
    def algorithms(self):
        base = super().algorithms()
        current = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest() for name in UNIFIED_ALGORITHMS}
        fail(current == _UNIFIED_HASHES, 'SOFTWARE_CHANGED_SINCE_IMPORT',
             'Unified wrapper bytes changed; start a frozen matching process')
        # Pure replay must retain the cold native-PDF reader's actual dependency
        # check, including at the successful request exit after full CAS rehash.
        import pypdf
        from native_pdf_observation import FRAME_READER_VERSION
        fail(pypdf.__version__ == FRAME_READER_VERSION, 'NATIVE_PDF_RUNTIME_DEPENDENCY_MISMATCH',
             'Native PDF verification requires its declared pypdf runtime version')
        dependency = hashlib.sha256(('pypdf==' + pypdf.__version__).encode()).hexdigest()
        return {**base, **current, 'nativePdfFrameReaderIdentity': dependency}


def configure_unified_environment(environ, workspace):
    values = dict(environ)
    workspace = Path(workspace).resolve()
    values.setdefault('CONSENSE_RETRIEVAL_DATA', str(workspace / '.local/oer/unified-retrieval-state'))
    selected = Path(values['CONSENSE_RETRIEVAL_DATA']).expanduser().resolve()
    old = ('retrieval-state', 'native-retrieval-state', 'native-collection-retrieval-state', 'native-pdf-retrieval-state')
    if selected in {(workspace / '.local/oer' / name).resolve() for name in old}:
        raise ValueError('Unified OER requires separate state from existing default services')
    return configure_environment(values, workspace)


def create_unified_app(policy, *, backend=None, artifact_store=None, target_module='vetting',
                       maximum_message_bytes=MAX_SNAPSHOT_BYTES):
    fail(type(maximum_message_bytes) is int and 1024 <= maximum_message_bytes <= MAX_SNAPSHOT_BYTES,
         'UNIFIED_MESSAGE_LIMIT_INVALID', 'Unified messages must stay within the fixed 16MiB maximum')
    backend = importlib.import_module('server') if backend is None else backend
    artifact_store = artifact_store or ArtifactStore(Path(backend.STATE) / 'artifacts')
    registry = UnifiedCanonicalSourceRegistry(Path(backend.STATE) / 'canonical', artifact_store,
        family_policy=policy, family_target_module=target_module)
    app = create_collection_app(policy, backend=backend, artifact_store=artifact_store,
        target_module=target_module, source_registry=registry, runtime_factory=UnifiedOerWindowRuntime,
        canonical_snapshot_frame=lambda: registry.validation_frame(
            software_guard=backend._windows_runtime.algorithms))
    app = create_native_pdf_app(policy, backend=backend, artifact_store=artifact_store,
        target_module=target_module, source_registry=registry, runtime_factory=UnifiedOerWindowRuntime,
        app=app, maximum_message_bytes=maximum_message_bytes,
        strict_json_paths=('/project', '/project/native', '/project/native-collection', '/project/native-pdf', FAMILY_ENDPOINT,
                           '/index', '/index-canonical', '/index-canonical/status', '/retrieve'))
    install_native_pdf_source_family_route(app, registry, artifact_store, policy,
        target_module=target_module, maximum_message_bytes=maximum_message_bytes,
        software_guard=backend._windows_runtime.algorithms)
    install_source_page_routes(app, registry, policy, target_module=target_module)
    install_source_page_index_route(app, backend)
    old_health = next(route for route in app.routes if getattr(route, 'path', None) == '/health')
    endpoint = old_health.endpoint
    app.router.routes.remove(old_health)
    def health():
        result = endpoint()
        # Only this composition's canonical load is scoped; publishing retains
        # its existing every-read validators. Standalone constants stay honest.
        for key in ('nativeCanonical', 'nativeDocumentCollection', 'nativePdfCanonical'):
            result['oer'][key] = {**result['oer'][key],
                'artifactReadValidation': UNIFIED_CAPABILITY['artifactReadValidation'],
                'artifactReadValidationScope': UNIFIED_CAPABILITY['artifactReadValidationScope']}
        result['oer']['unifiedCanonical'] = {**UNIFIED_CAPABILITY, 'maximumMessageBytes': maximum_message_bytes}
        result['oer']['sourcePageRenderContract'] = 'consense.source-page-render-evidence.v1'
        result['oer']['sourcePageIndexContract'] = 'explicit-composite-index-pins-v1'
        result['oer']['nativePdfSourceFamily'] = {
            'schema': 'consense.native-pdf-source-family.v1', 'version': 'same-original-complete-native-pdf-components-v1',
            'producerKind': 'native_pdf_source_family', 'sourceRevisionKind': 'derived_same_original_component_set',
            'componentContract': 'complete-cold-actual-cas-native-pdf-public-roots',
            'maximumMessageBytes': maximum_message_bytes, 'crossComponentRelations': 'unsupported',
            'adoptedCoverage': 'disjoint-physical-pages-required-v1',
            'wholeSourceAccepted': False, 'semanticAcceptanceByComposition': False,
            'projectEndpoint': FAMILY_ENDPOINT, 'projectionInputs': 'actual-cas-family-spec-only',
            'sourcePageRenderStatus': 'unsupported'}
        return result
    app.get('/health')(health)
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8874)
    parser.add_argument('--scope-manifest')
    parser.add_argument('--scope-manifest-sha256')
    args = parser.parse_args()
    supplied = dict(os.environ)
    if args.scope_manifest:
        supplied['CONSENSE_SL_SCOPE_MANIFEST'] = args.scope_manifest
    if args.scope_manifest_sha256:
        supplied['CONSENSE_SL_SCOPE_MANIFEST_SHA256'] = args.scope_manifest_sha256
    values = configure_unified_environment(supplied, resolve_workspace(HERE, supplied))
    policy = load_policy(values['CONSENSE_SL_SCOPE_MANIFEST'], values.get('CONSENSE_SL_SCOPE_MANIFEST_SHA256'))
    os.environ.update(values)
    import uvicorn
    uvicorn.run(create_unified_app(policy, target_module=values['CONSENSE_OER_TARGET_MODULE']),
                host=args.host, port=args.port)


if __name__ == '__main__':
    main()
