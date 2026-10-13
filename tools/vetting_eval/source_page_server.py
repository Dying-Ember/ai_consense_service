"""Bounded HTTP admission and exact typed source-page evidence routes."""
from fastapi import HTTPException, Request
from fastapi.responses import Response
from native_observation import _strict_json
from oer_source_registry import _identity, _serialize
from oer_server import assert_canonical_scope
from token_windows import WindowError
from native_pdf_source_family import source_family_candidate
from oer_source_registry import SourceRegistryError


def install_source_page_routes(app, registry, policy, *, target_module):
    pages = registry.source_pages
    policy_sha = policy.policyIdentity['manifestSha256']

    def pins(request, module, evidence):
        try:
            _identity(evidence, 'combinedEvidenceIdentitySha256')
            pairs = list(request.query_params.multi_items())
            if len(pairs) != 2 or {key for key, _ in pairs} != {'module', 'evidence'}:
                raise ValueError('Exact module and composite evidence query parameters are required')
            if module not in ('vetting', 'advisory'):
                raise ValueError('Source-page module is excluded')
        except ValueError as error:
            raise HTTPException(409, {'kind': 'SOURCE_PAGE_IDENTITY_REQUIRED', 'message': str(error)}) from error
        return {'module': module, 'evidence': evidence, 'policy_sha256': policy_sha}

    def headers(projection, source, revision, evidence):
        return {'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'private, no-cache',
            'X-Source-Sha256': source, 'X-Projection-Sha256': projection,
            'X-Source-Revision-Sha256': revision, 'X-Evidence-Identity-Sha256': evidence}

    def scoped(projection_sha256, module):
        # A source-page proof cannot admit an excluded original into a module.
        projection = registry.load(projection_sha256)
        assert_canonical_scope(projection['chunks'], policy, module=module)
        if source_family_candidate(projection):
            raise SourceRegistryError('SOURCE_FAMILY_RENDER_UNSUPPORTED',
                'Typed family source-page rendering is not implemented; visual return remains not published and unknown')

    def rejected(error):
        return HTTPException(409, {'kind': getattr(error, 'code', getattr(error, 'kind', 'SOURCE_PAGE_REJECTED')),
                                   'message': str(error)})

    async def publish(request: Request, projection_sha256: str, source_sha256: str,
                      module: str = target_module, evidence: str | None = None):
        exact = pins(request, module, evidence)
        try:
            body = _strict_json(await request.body())
            if not isinstance(body, dict) or set(body) != {'overlayRef'}:
                raise ValueError('Provide exactly one actual-CAS overlayRef')
            scoped(projection_sha256, module)
            receipt = pages.publish(projection_sha256, source_sha256, body['overlayRef'], **exact)
            return Response(_serialize(receipt), media_type='application/json',
                headers=headers(projection_sha256, source_sha256, receipt['sourceRevisionSha256'], evidence))
        except (ValueError, WindowError, OSError, KeyError, TypeError) as error:
            raise rejected(error) from error

    def metadata(request: Request, projection_sha256: str, source_sha256: str,
                 module: str = target_module, evidence: str | None = None):
        exact = pins(request, module, evidence)
        try:
            scoped(projection_sha256, module)
            data = pages.metadata(projection_sha256, source_sha256, **exact)
            return Response(_serialize(data), media_type='application/json',
                headers=headers(projection_sha256, source_sha256, data['sourceRevisionSha256'], evidence))
        except (ValueError, WindowError, OSError, KeyError, TypeError) as error:
            raise rejected(error) from error

    def asset(request: Request, projection_sha256: str, source_sha256: str, asset_id: str,
              module: str = target_module, evidence: str | None = None):
        exact = pins(request, module, evidence)
        try:
            scoped(projection_sha256, module)
            raw, handle = pages.asset(projection_sha256, source_sha256, asset_id, **exact)
            return Response(raw, media_type='image/png', headers={**headers(projection_sha256, source_sha256,
                handle['sourceRevisionSha256'], evidence), 'ETag': '"' + handle['sha256'] + '"'})
        except (ValueError, WindowError, OSError, KeyError, TypeError) as error:
            raise rejected(error) from error

    path = '/canonical/{projection_sha256}/sources/{source_sha256}/source-pages'
    app.post(path)(publish)
    app.get(path)(metadata)
    app.get(path + '/{asset_id}')(asset)
