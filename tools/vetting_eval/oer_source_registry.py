"""Immutable canonical snapshots and source-bound asset retrieval.

Only the upstream canonical projector can create a snapshot. This registry does
not review OCR or choose a parser. Every read rechecks the projection seal and
every asset read rechecks its content-addressed bytes; a stale revision never
silently resolves to the newest document or to another image with similar text.
"""
from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import re
import tempfile


class SourceRegistryError(ValueError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)


def _identity(value, name):
    if not isinstance(value, str) or not re.fullmatch(r'[a-f0-9]{64}', value):
        raise SourceRegistryError('INVALID_IDENTITY', name + ' must be a lowercase SHA-256')
    return value


def _serialize(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'),
                      allow_nan=False).encode('utf-8')


def _validate(projection):
    from canonical_projection import validate_projection
    validate_projection(projection)


class CanonicalSourceRegistry:
    def __init__(self, root, artifact_store, *, validator=None):
        self.root = Path(root).expanduser().resolve()
        self.artifact_store = artifact_store
        self.validator = validator or _validate

    def _path(self, projection_sha256):
        name = _identity(projection_sha256, 'projectionSha256') + '.json'
        path = self.root / name
        if not path.resolve().is_relative_to(self.root):
            raise SourceRegistryError('SNAPSHOT_PATH_INVALID', 'Snapshot is outside the registry')
        return path

    def publish(self, projection):
        """Persist the exact sealed snapshot; never replace an existing revision."""
        snapshot = copy.deepcopy(projection)
        self.validator(snapshot)
        address = _identity(snapshot.get('projectionSha256'), 'projectionSha256')
        payload = _serialize(snapshot)
        self.root.mkdir(parents=True, exist_ok=True)
        path = self._path(address)
        if path.exists():
            if _serialize(self.load(address)) != payload:
                raise SourceRegistryError('SNAPSHOT_CONFLICT', 'A projection revision already has different bytes')
        else:
            with tempfile.NamedTemporaryFile(dir=self.root, prefix='.publish-', delete=False) as file:
                temporary = Path(file.name)
                file.write(payload)
                file.flush()
                os.fsync(file.fileno())
            try:
                # A hard link provides create-if-absent semantics rather than
                # replacing a revision written by another process.
                try:
                    os.link(temporary, path)
                except FileExistsError:
                    if path.read_bytes() != payload:
                        raise SourceRegistryError('SNAPSHOT_CONFLICT', 'Concurrent revision differs')
            finally:
                temporary.unlink(missing_ok=True)
        return {'projectionSha256': address, 'chunks': len(snapshot['chunks']),
                'assets': len(snapshot.get('assetRefs', [])), 'immutable': True,
                'qualityVerifiedByRegistry': False}

    def load(self, projection_sha256):
        path = self._path(projection_sha256)
        try:
            snapshot = json.loads(path.read_text(encoding='utf-8'))
        except (OSError, UnicodeError, ValueError) as error:
            raise SourceRegistryError('SNAPSHOT_UNAVAILABLE', 'Canonical snapshot is missing or unreadable') from error
        self.validator(snapshot)
        if snapshot.get('projectionSha256') != projection_sha256:
            raise SourceRegistryError('SNAPSHOT_IDENTITY_MISMATCH', 'Stored revision does not match its address')
        return snapshot

    def chunks(self, projection_sha256, *, module='vetting'):
        snapshot = self.load(projection_sha256)
        declared = snapshot['projectionIdentity'].get('module')
        shared_review = (module in ('vetting', 'advisory') and declared in ('vetting', 'advisory')
                         and all(module in chunk.get('eligibleModules', []) for chunk in snapshot['chunks']))
        if module not in ('vetting', 'advisory', 'drafting') or not (declared == module or shared_review):
            raise SourceRegistryError('SNAPSHOT_MODULE_MISMATCH', 'Projection belongs to a different module')
        return copy.deepcopy(snapshot['chunks'])

    def asset(self, projection_sha256, source_sha256, asset_id, *, module='vetting'):
        """Return only an asset explicitly bound to this source and revision."""
        _identity(source_sha256, 'sourceSha256')
        snapshot = self.load(projection_sha256)
        self.chunks(projection_sha256, module=module)
        matches = [ref for ref in snapshot.get('assetRefs', []) if ref.get('assetId') == asset_id]
        if len(matches) != 1 or matches[0].get('sourceSha256') != source_sha256:
            raise SourceRegistryError('ASSET_NOT_BOUND', 'Asset is not uniquely bound to this source revision')
        reference = matches[0]
        descriptor = reference.get('artifactDescriptor')
        if (not isinstance(descriptor, dict) or reference.get('sha256') != descriptor.get('sha256')
                or reference.get('byteCount') != descriptor.get('byteCount')):
            raise SourceRegistryError('ASSET_DESCRIPTOR_MISMATCH', 'Asset identity differs from its descriptor')
        media_type = reference.get('mediaType')
        if media_type not in ('image/png', 'image/jpeg', 'image/webp', 'application/pdf'):
            raise SourceRegistryError('ASSET_MEDIA_UNSUPPORTED', 'Only original image/PDF evidence can be displayed')
        data = self.artifact_store.read_asset(descriptor)
        valid_magic = (media_type == 'image/png' and data.startswith(b'\x89PNG\r\n\x1a\n')
                       or media_type == 'image/jpeg' and data.startswith(b'\xff\xd8\xff')
                       or media_type == 'application/pdf' and data.startswith(b'%PDF-')
                       or media_type == 'image/webp' and data[:4] == b'RIFF' and data[8:12] == b'WEBP')
        if not valid_magic:
            raise SourceRegistryError('ASSET_MEDIA_MISMATCH', 'Asset bytes do not match the declared media type')
        return data, media_type, copy.deepcopy(reference)
