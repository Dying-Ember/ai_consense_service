"""Explicit mixed canonical registry; native public snapshots require actual CAS.

The scanned-only registry and running entry point stay unchanged. An internal
native view is not an HTTP snapshot: only the sealed, bounded public transport
is admitted here, and every inherited read invokes the actual-byte validator.
"""
import json
import os

from canonical_projection import validate_projection
from native_canonical_projection import SCHEMA
from native_projection_transport import STORAGE_SHAPE, validate_native_transport
from oer_source_registry import CanonicalSourceRegistry, SourceRegistryError, _identity, _serialize


def _native_candidate(chunk):
    # Producer/version/ref declarations are independent signals. Removing only
    # a convenience geometry flag must not bypass the actual-CAS source gate.
    if (chunk.get('producerKind') == 'native_docx' or chunk.get('schema') == SCHEMA
            or any(isinstance(chunk.get(key), str) and chunk[key].startswith('source-reviewed-native-')
                   for key in ('projectionVersion', 'metadataVersion'))
            or isinstance(chunk.get('storageShape'), str) and chunk['storageShape'].startswith('cas-native-')
            or chunk.get('originalGeometryStatus') == 'not_defined_for_native_docx'):
        return True
    for field in ('parts', 'sourceSpans'):
        for value in chunk.get(field) or []:
            if isinstance(value, dict) and (value.get('native') is True
                    or isinstance(value.get('sourcePosition'), dict) and value['sourcePosition'].get('kind') == 'docx_native'
                    or any(key in value for key in ('nativeNodeRef', 'nativePropertiesRef', 'nativeTokensRef', 'nativeTextChannels'))
                    or isinstance(value.get('blockId'), str) and value['blockId'].startswith('native-')):
                return True
    group = chunk.get('tableGroup')
    return isinstance(group, dict) and any(key in group for key in ('nativeTablesRef', 'nativeTableTopology'))


class NativeCanonicalSourceRegistry(CanonicalSourceRegistry):
    def __init__(self, root, artifact_store):
        def validate(snapshot):
            if snapshot.get('schema') == 'consense.canonical-projection.v1':
                if snapshot.get('producerKind') == 'native_docx' or 'storageShape' in snapshot:
                    raise SourceRegistryError('CANONICAL_PRODUCER_MISMATCH',
                                              'Native data cannot bypass the scanned projection validator')
                return validate_projection(snapshot)
            if (snapshot.get('schema') == SCHEMA
                    and snapshot.get('producerKind') == 'native_docx'
                    and snapshot.get('storageShape') == STORAGE_SHAPE):
                return validate_native_transport(snapshot, artifact_store=artifact_store)
            raise SourceRegistryError('CANONICAL_SCHEMA_UNSUPPORTED',
                                      'Only scanned v1 or actual-CAS native public v2 is supported')
        super().__init__(root, artifact_store, validator=validate)

    def _revision_path(self, revision):
        return self.root / 'native-revisions' / (_identity(revision, 'projectionRevisionSha256') + '.json')

    def publish(self, projection):
        receipt = super().publish(projection)
        if projection.get('schema') == SCHEMA:
            alias = self._revision_path(projection['projectionRevisionSha256'])
            alias.parent.mkdir(parents=True, exist_ok=True)
            try:
                os.link(self._path(projection['projectionSha256']), alias)
            except FileExistsError:
                if alias.read_bytes() != self._path(projection['projectionSha256']).read_bytes():
                    raise SourceRegistryError('NATIVE_REVISION_CONFLICT', 'Native revision already refers to different public bytes')
        return receipt

    def validate_native_chunks(self, chunks):
        """Re-open published revisions before trusting persisted index payloads.

        The revision hard link is an immutable locator, not a validation cache.
        The addressed snapshot still rechecks every actual CAS artifact. Missing
        registration cannot turn an arbitrary /index payload into native proof.
        """
        groups = {}
        for chunk in chunks:
            if _native_candidate(chunk):
                groups.setdefault(chunk.get('projectionRevisionSha256'), []).append(chunk)
        for revision, members in groups.items():
            try:
                locator = json.loads(self._revision_path(revision).read_text(encoding='utf-8'))
                snapshot = self.load(locator['projectionSha256'])
            except (OSError, ValueError, KeyError, TypeError) as error:
                raise SourceRegistryError('NATIVE_CANONICAL_SOURCE_UNAVAILABLE',
                                          'Native index source is unregistered or its actual CAS cannot be verified') from error
            if (snapshot.get('schema') != SCHEMA or snapshot.get('projectionRevisionSha256') != revision
                    or _serialize(locator) != _serialize(snapshot) or members != snapshot['chunks']):
                raise SourceRegistryError('NATIVE_INDEX_PARENT_MISMATCH',
                                          'Native index must retain exact complete published parents in source order')
        return len(groups)
