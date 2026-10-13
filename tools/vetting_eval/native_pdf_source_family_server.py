"""Normal bounded HTTP publication of complete same-original PDF families.

Only an actual-CAS family spec is accepted. The existing producer and registry
retain all original child identities; this route performs no semantic review.
"""
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict

from artifact_store import DESCRIPTOR_SCHEMA
from native_observation import _strict_json
from native_pdf_source_family import (REVISION_KIND, compose_native_pdf_source_family)
from native_projection_transport import MAX_SNAPSHOT_BYTES
from oer_server import assert_canonical_scope
from oer_source_registry import _serialize
from token_windows import WindowError, fail


ENDPOINT = '/project/native-pdf-family'
DESCRIPTOR_FIELDS = {'schema', 'storage', 'sha256', 'byteCount', 'relativePath', 'identityScope'}


class NativePdfSourceFamilyRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    sourceFamilySpecRef: dict


def install_native_pdf_source_family_route(app, registry, artifact_store, policy, *,
        target_module, maximum_message_bytes=MAX_SNAPSHOT_BYTES, software_guard):
    fail(app.state.source_registry is registry and app.state.artifact_store is artifact_store,
         'SOURCE_FAMILY_FACTORY_MISMATCH', 'Family publication must share the normal registry and CAS')
    fail(registry.family_policy is policy and registry.family_target_module == target_module,
         'SOURCE_FAMILY_FACTORY_MISMATCH', 'Family publication must share the exact active policy and module')

    def family_project(request):
        try:
            ref = request.sourceFamilySpecRef
            fail(isinstance(ref, dict) and set(ref) == DESCRIPTOR_FIELDS
                 and ref.get('schema') == DESCRIPTOR_SCHEMA
                 and type(ref.get('byteCount')) is int
                 and 0 <= ref['byteCount'] <= maximum_message_bytes,
                 'SOURCE_FAMILY_SPEC_DESCRIPTOR_REQUIRED', 'Provide one bounded complete actual-CAS family spec descriptor')
            with registry.validation_frame(software_guard=software_guard):
                raw = artifact_store.read_asset(ref)
                fail(len(raw) <= maximum_message_bytes, 'SOURCE_FAMILY_SPEC_TOO_LARGE',
                     'Complete family spec exceeds the configured whole-message cap')
                spec = _strict_json(raw)
                fail(isinstance(spec, dict), 'SOURCE_FAMILY_SPEC_OBJECT_REQUIRED',
                     'Actual family spec must be a strict JSON object')
                projection = compose_native_pdf_source_family(spec, registry=registry,
                    artifact_store=artifact_store, policy=policy, target_module=target_module,
                    max_snapshot_bytes=maximum_message_bytes)
                assert_canonical_scope(projection['chunks'], policy, module=target_module)
                preview = {'projectionSha256': projection['projectionSha256'],
                           'chunks': len(projection['chunks']), 'assets': len(projection['assetRefs']),
                           'immutable': True, 'qualityVerifiedByRegistry': False}
                identity = projection['projectionIdentity']
                result = {**preview, 'projection': projection, 'sourceQualityChangedByRegistry': False,
                    'sourceRevisionKind': REVISION_KIND,
                    'sourceRevisionSha256': identity['sourceRevisionSha256'],
                    'projectionRevisionSha256': projection['projectionRevisionSha256'],
                    'familyCompositionSha256': identity['compositionSha256'],
                    'familyComponentSetSha256': identity['componentSetSha256'],
                    'visualReturnStatus': projection['visualReturnStatus']}
                fail(len(_serialize(result)) <= maximum_message_bytes, 'SOURCE_FAMILY_MESSAGE_TOO_LARGE',
                     'Complete family projection plus typed receipt exceeds the whole-message cap')
            # Creating the new immutable revision cannot occur inside a frame
            # that requires every referenced revision path to exist already.
            # Publisher has its full cold proof gate; a separate fresh frame
            # then admits exact spec/public-root/alias/CAS before any response.
            receipt = registry.publish(projection)
            fail(receipt == preview, 'SOURCE_FAMILY_REGISTRY_RECEIPT_MISMATCH',
                 'Published receipt differs from the complete validated response')
            with registry.validation_frame(software_guard=software_guard):
                fail(artifact_store.read_asset(ref) == raw, 'SOURCE_FAMILY_SPEC_CHANGED',
                     'Actual family spec changed during publication')
                wire, chunks = registry.wire(projection['projectionSha256'], module=target_module)
                fail(wire == projection and chunks == projection['chunks'], 'SOURCE_FAMILY_READBACK_MISMATCH',
                     'Published family must read back as the same complete immutable wire')
                fail(registry.family_policy is policy and registry.family_target_module == target_module,
                     'SOURCE_FAMILY_FACTORY_MISMATCH', 'Active family policy or module changed during publication')
            return result
        except WindowError as error:
            raise HTTPException(422, {'kind': error.kind, 'message': str(error), 'metadata': error.metadata}) from error
        except (ValueError, OSError, KeyError, TypeError) as error:
            raise HTTPException(422, {'kind': getattr(error, 'code', getattr(error, 'kind', 'SOURCE_FAMILY_PROJECTION_REJECTED')),
                                      'message': str(error)}) from error

    family_project.__annotations__['request'] = NativePdfSourceFamilyRequest
    app.post(ENDPOINT)(app.state.request_evidence.wrap(ENDPOINT, family_project))
