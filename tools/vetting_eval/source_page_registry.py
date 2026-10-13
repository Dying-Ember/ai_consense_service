"""Immutable typed source-page sidecars over the existing actual-CAS registry.

Sidecar admission is explicit and independent of parser/model input assets.
The complete source, proofs and image bytes are checked on every read. A
composite pin cannot fall back to a legacy asset or to a newer revision.
"""
from __future__ import annotations

import copy
import os
from pathlib import Path
import tempfile

from artifact_store import DESCRIPTOR_SCHEMA
from native_observation import _strict_json
from native_projection_transport import MAX_SNAPSHOT_BYTES
from oer_source_registry import SourceRegistryError, _identity, _serialize
from source_render_assets import validate_source_render_overlay


RECORD_SCHEMA = 'consense.source-page-render-registration.v1'
EVIDENCE_SCHEMA = 'consense.source-page-render-evidence.v1'
DESCRIPTOR_FIELDS = {'schema', 'storage', 'sha256', 'byteCount', 'relativePath', 'identityScope'}


def _require(condition, code, message):
    if not condition:
        raise SourceRegistryError(code, message)


class SourcePageRenderRegistry:
    def __init__(self, root, canonical_registry, artifact_store):
        self.root = Path(root).resolve()
        self.canonical_registry = canonical_registry
        self.artifact_store = artifact_store

    def _path(self, projection, evidence):
        projection = _identity(projection, 'projectionSha256')
        evidence = _identity(evidence, 'combinedEvidenceIdentitySha256')
        path = self.root / projection / (evidence + '.json')
        _require(path.resolve().is_relative_to(self.root), 'SOURCE_PAGE_PATH_INVALID',
                 'Source-page registration must stay within its immutable registry')
        return path

    def _prepare(self, projection_sha256, source_sha256, overlay_ref, *, module, evidence, policy_sha256):
        _identity(source_sha256, 'sourceSha256')
        _identity(evidence, 'combinedEvidenceIdentitySha256')
        _require(module in ('vetting', 'advisory'), 'SOURCE_PAGE_MODULE_EXCLUDED',
                 'Source-page evidence belongs to Vetting or Advisory')
        _require(isinstance(overlay_ref, dict) and set(overlay_ref) == DESCRIPTOR_FIELDS
                 and overlay_ref.get('schema') == DESCRIPTOR_SCHEMA
                 and type(overlay_ref.get('byteCount')) is int
                 and 0 <= overlay_ref['byteCount'] <= MAX_SNAPSHOT_BYTES,
                 'SOURCE_PAGE_ACTUAL_DESCRIPTOR_REQUIRED', 'Provide a bounded complete actual-CAS overlay descriptor')
        raw = self.artifact_store.read_asset(overlay_ref)
        _require(len(raw) <= MAX_SNAPSHOT_BYTES, 'SOURCE_PAGE_OVERLAY_TOO_LARGE',
                 'Source-page overlay exceeds the fixed 16MiB maximum')
        overlay = _strict_json(raw)
        _require(isinstance(overlay, dict), 'SOURCE_PAGE_OVERLAY_INVALID', 'Overlay must be a strict JSON object')
        projection = self.canonical_registry.load(projection_sha256)
        _require(projection.get('schema') == 'consense.canonical-projection.v1',
                 'SOURCE_PAGE_PROJECTION_UNSUPPORTED', 'Typed pages require an explicitly reviewed PDF projection')
        self.canonical_registry._module(projection, module)
        _require(all(module in chunk.get('eligibleModules', []) for chunk in projection['chunks']),
                 'SOURCE_PAGE_MODULE_EXCLUDED', 'Every adopted member must be eligible for this module')
        _require(projection['source']['sourceSha256'] == source_sha256,
                 'SOURCE_PAGE_SOURCE_MISMATCH', 'Source page belongs to a different original')
        _require(projection['manifest']['scope']['scopePolicySha256'] == policy_sha256,
                 'SOURCE_PAGE_POLICY_MISMATCH', 'Source pages must bind the policy of this process')
        _require(overlay.get('combinedEvidenceIdentitySha256') == evidence,
                 'SOURCE_PAGE_EVIDENCE_MISMATCH', 'Exact composite evidence identity is required')
        handles = validate_source_render_overlay(overlay, projection, self.artifact_store)
        # The frozen typed overlay reader admits one complete table group.
        units = projection['canonical']['units']
        _require(len(units) == 1, 'SOURCE_PAGE_GROUP_REQUIRED', 'One complete adopted table group is required')
        unit = units[0]
        cells_by_block = {part['blockId']: {cell['cellId'] for cell in part.get('cells', [])}
                          for part in unit['parts']}
        for relation in projection['canonical']['relations']:
            _require(relation.get('fromUnitId') == relation.get('toUnitId') == unit['unitId']
                     and relation.get('fromCellId') in cells_by_block.get(relation.get('fromBlockId'), set())
                     and relation.get('toCellId') in cells_by_block.get(relation.get('toBlockId'), set()),
                     'SOURCE_PAGE_RELATION_UNBOUND', 'Every continuation endpoint must belong to its exact adopted part')
        _require(len(handles) == len(unit['physicalPages'])
                 and len({handle['assetId'] for handle in handles}) == len(handles)
                 and {handle['physicalPageNo'] for handle in handles} == set(unit['physicalPages']),
                 'SOURCE_PAGE_GROUP_INCOMPLETE', 'All complete group pages must remain uniquely addressable')
        for handle in handles:
            _require(all(type(handle.get(field)) is int and handle[field] > 0
                         for field in ('physicalPageNo', 'widthPixels', 'heightPixels', 'byteCount'))
                     and handle.get('kind') == 'source_page_render'
                     and handle.get('sourceSha256') == source_sha256
                     and handle.get('sourceRevisionSha256') == projection['source']['sourceRevisionSha256']
                     and handle.get('combinedEvidenceIdentitySha256') == evidence
                     and handle.get('qualityAccepted') is False,
                     'SOURCE_PAGE_HANDLE_INVALID', 'Typed page handle identity and dimensions must be exact')
        return projection, overlay, handles

    def publish(self, projection_sha256, source_sha256, overlay_ref, *, module, evidence, policy_sha256):
        projection, overlay, handles = self._prepare(projection_sha256, source_sha256, overlay_ref,
            module=module, evidence=evidence, policy_sha256=policy_sha256)
        record = {'schema': RECORD_SCHEMA, 'projectionSha256': projection_sha256,
            'sourceSha256': source_sha256, 'sourceRevisionSha256': projection['source']['sourceRevisionSha256'],
            'scopePolicySha256': policy_sha256, 'combinedEvidenceIdentitySha256': evidence,
            'overlayRef': copy.deepcopy(overlay_ref), 'overlaySha256': overlay['overlaySha256']}
        payload = _serialize(record)
        path = self._path(projection_sha256, evidence)
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.source-pages-', delete=False) as file:
            temporary = Path(file.name)
            file.write(payload)
            file.flush()
            os.fsync(file.fileno())
        try:
            try:
                os.link(temporary, path)
            except FileExistsError:
                _require(path.read_bytes() == payload, 'SOURCE_PAGE_REGISTRATION_CONFLICT',
                         'Composite evidence is already registered with different exact sidecar bytes')
        finally:
            temporary.unlink(missing_ok=True)
        return {'schema': RECORD_SCHEMA, 'projectionSha256': projection_sha256,
            'sourceSha256': source_sha256, 'sourceRevisionSha256': record['sourceRevisionSha256'],
            'combinedEvidenceIdentitySha256': evidence, 'sourcePageRenders': len(handles),
            'immutable': True, 'qualityAccepted': False, 'wholeSourceAccepted': False}

    def _read(self, projection_sha256, source_sha256, *, module, evidence, policy_sha256):
        path = self._path(projection_sha256, evidence)
        try:
            raw = path.read_bytes()
            _require(len(raw) <= MAX_SNAPSHOT_BYTES, 'SOURCE_PAGE_RECORD_TOO_LARGE', 'Registration is oversized')
            record = _strict_json(raw)
        except (OSError, UnicodeError, ValueError) as error:
            raise SourceRegistryError('SOURCE_PAGE_REGISTRATION_UNAVAILABLE',
                                      'Exact composite source-page registration is unavailable') from error
        _require(isinstance(record, dict) and set(record) == {'schema', 'projectionSha256', 'sourceSha256',
                 'sourceRevisionSha256', 'scopePolicySha256', 'combinedEvidenceIdentitySha256', 'overlayRef', 'overlaySha256'}
                 and record.get('schema') == RECORD_SCHEMA and record.get('projectionSha256') == projection_sha256
                 and record.get('sourceSha256') == source_sha256
                 and record.get('scopePolicySha256') == policy_sha256
                 and record.get('combinedEvidenceIdentitySha256') == evidence,
                 'SOURCE_PAGE_REGISTRATION_IDENTITY_MISMATCH', 'Registration identity differs from its address')
        projection, overlay, handles = self._prepare(projection_sha256, source_sha256, record['overlayRef'],
            module=module, evidence=evidence, policy_sha256=policy_sha256)
        _require(record['sourceRevisionSha256'] == projection['source']['sourceRevisionSha256']
                 and record['overlaySha256'] == overlay['overlaySha256'],
                 'SOURCE_PAGE_REGISTRATION_IDENTITY_MISMATCH', 'Registered source revision or exact overlay changed')
        return projection, overlay, handles

    def metadata(self, projection_sha256, source_sha256, **pins):
        projection, overlay, handles = self._read(projection_sha256, source_sha256, **pins)
        refs = [{**handle, 'physicalPages': [handle['physicalPageNo']],
                 'sourceRegions': [{'blockId': region['blockId'], 'bbox': region['bboxOriginalNormalized'],
                                    'bboxConvention': region['bboxConvention']} for region in handle['imageRegions']],
                 'humanConfirmed': False, 'projectionSha256': projection_sha256} for handle in handles]
        unit = projection['canonical']['units'][0]
        return {'schema': EVIDENCE_SCHEMA, 'projectionSha256': projection_sha256,
            'sourceSha256': source_sha256, 'sourceRevisionSha256': projection['source']['sourceRevisionSha256'],
            'scopePolicySha256': overlay['scopePolicySha256'],
            'combinedEvidenceIdentitySha256': overlay['combinedEvidenceIdentitySha256'],
            'unitId': unit['unitId'], 'physicalPages': copy.deepcopy(unit['physicalPages']),
            'sourcePageRenderRefs': refs, 'relations': copy.deepcopy(projection['canonical']['relations']),
            'captureCompleteness': copy.deepcopy(projection['captureCompleteness']),
            'unknowns': copy.deepcopy(projection['unknowns']), 'limitations': copy.deepcopy(projection['limitations']),
            'upstreamRawCaptureStatus': {'status': 'not_carried_in_asset_packet', 'derivedByConsumer': False},
            'qualityAccepted': False, 'wholeSourceAccepted': False}

    def asset(self, projection_sha256, source_sha256, asset_id, **pins):
        _, _, handles = self._read(projection_sha256, source_sha256, **pins)
        matches = [handle for handle in handles if handle['assetId'] == asset_id]
        _require(len(matches) == 1, 'SOURCE_PAGE_ASSET_NOT_BOUND', 'Page is not bound to this exact composite evidence')
        handle = matches[0]
        return self.artifact_store.read_asset(handle['artifactDescriptor']), copy.deepcopy(handle)
