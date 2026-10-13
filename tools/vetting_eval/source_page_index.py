"""Explicit composite pins for indexing; source pages never alter sealed chunks."""
from contextvars import ContextVar
import copy
import json

from fastapi import HTTPException
from pydantic import Field
from oer_server import CanonicalIndexRequest
from oer_source_registry import _identity
from token_windows import WindowError, fail


CONTRACT = 'explicit-composite-index-pins-v1'


class SourcePageIndexRequest(CanonicalIndexRequest):
    sourcePageEvidence: dict[str, str] = Field(default_factory=dict)


def evidence_pins(value):
    fail(isinstance(value, dict), 'SOURCE_PAGE_INDEX_PINS_INVALID', 'Source-page pins must be an explicit map')
    for projection, composite in value.items():
        _identity(projection, 'projectionSha256')
        _identity(composite, 'combinedEvidenceIdentitySha256')
    return dict(sorted(value.items()))


class SourcePageIndexRuntime:
    """Cooperative wrapper: published CAS validity is checked outside plan caches."""
    def __init__(self, *args, **kwargs):
        self.source_page_index_pins = ContextVar('consense_source_page_index_pins', default=None)
        super().__init__(*args, **kwargs)

    def _read_source_page_pins(self, chunks):
        pins = evidence_pins(self.source_page_index_pins.get() or {})
        for projection_sha, composite in pins.items():
            projection = self.source_registry.load(projection_sha)
            expected = projection['chunks']
            selected = [chunk for chunk in chunks if chunk.get('documentId') == projection['projectionIdentity']['documentId']
                        and chunk.get('sourceSha256') == projection['source']['sourceSha256']]
            def complete_parents(values):
                parents = {}
                for chunk in values:
                    parent_id = chunk.get('id')
                    fail(isinstance(parent_id, str) and bool(parent_id) and parent_id not in parents,
                         'SOURCE_PAGE_INDEX_SOURCE_MISMATCH', 'Pinned canonical parents require unique exact identities')
                    parents[parent_id] = chunk
                return parents
            # The baseline index deliberately sorts parent UUIDs while the
            # structural recipe preserves canonical reading order. Exact
            # identity/payload membership is independent of those recipes;
            # super().signature still binds the actual complete corpus order.
            fail(complete_parents(selected) == complete_parents(expected), 'SOURCE_PAGE_INDEX_SOURCE_MISMATCH',
                 'Pinned page source must contain every exact complete canonical parent once')
            self.source_registry.source_pages._read(projection_sha, projection['source']['sourceSha256'],
                module=self.target_module, evidence=composite,
                policy_sha256=self.scope_policy.policyIdentity['manifestSha256'])
        return pins

    def _validate_source_page_pins(self, chunks):
        try:
            return self._read_source_page_pins(chunks)
        except WindowError:
            raise
        except (ValueError, OSError, KeyError, TypeError) as error:
            raise WindowError('SOURCE_PAGE_INDEX_EVIDENCE_UNAVAILABLE',
                              'Current composite source-page proof cannot be verified',
                              sourceQualityChanged=False) from error

    def signature(self, chunks):
        pins = self._validate_source_page_pins(chunks)
        base = super().signature(chunks)
        return {**base, **({'sourcePageEvidence': pins} if pins else {})}

    def retrieve(self, request):
        # The exact declared map is in the persisted signature, outside source
        # payloads. Restoring an index never guesses an available/newest sidecar.
        key = self.s.project_key(request.projectId)
        with self.s.LOCK:
            stored = self.s._projects.get(key)
            if stored is None:
                path = self.s.metadata_path(key)
                if path.exists():
                    stored = json.loads(path.read_text(encoding='utf-8'))
            pins = evidence_pins((stored or {}).get('signature', {}).get('sourcePageEvidence', {}))
            token = self.source_page_index_pins.set(pins)
            try:
                result = super().retrieve(request)
                current = self.s._projects[key]
                self._validate_source_page_pins(current['chunks'])
                return result
            finally:
                self.source_page_index_pins.reset(token)


def install_source_page_index_route(app, backend):
    old = next(route for route in app.routes if getattr(route, 'path', None) == '/index-canonical')
    endpoint = old.endpoint
    app.router.routes.remove(old)

    def index(request):
        try:
            pins = evidence_pins(request.sourcePageEvidence)
            fail(set(pins).issubset(request.projectionSha256), 'SOURCE_PAGE_INDEX_PINS_OUTSIDE_SCOPE',
                 'Every composite pin must name a selected projection')
            runtime = backend._windows_runtime
            token = runtime.source_page_index_pins.set(pins)
            try:
                result = endpoint(request)
                return {**result, 'sourcePageEvidence': copy.deepcopy(pins)}
            finally:
                runtime.source_page_index_pins.reset(token)
        except WindowError as error:
            raise HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error
        except (ValueError, KeyError, OSError) as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', 'SOURCE_PAGE_INDEX_PINS_REJECTED'),
                                      'message': str(error)}) from error

    index.__annotations__['request'] = SourcePageIndexRequest
    app.post('/index-canonical')(index)
