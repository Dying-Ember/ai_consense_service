"""Separate PDF registry branch; actual-CAS DOCX/scanned gates stay intact."""
import json
import os

from native_pdf_canonical_projection import SCHEMA
from native_pdf_projection_transport import STORAGE_SHAPE, validate_native_pdf_transport
from native_source_registry import NativeCanonicalSourceRegistry
from oer_source_registry import SourceRegistryError, _identity, _serialize


def _pdf_candidate(value):
    """Independent typed signals resist stripping just the producer marker."""
    if isinstance(value, list):
        return any(_pdf_candidate(row) for row in value)
    if not isinstance(value, dict): return False
    if (value.get('producerKind') == 'native_pdf'
            or isinstance(value.get('schema'), str) and value['schema'].startswith('consense.native-pdf-')
            or any(isinstance(value.get(key), str) and value[key].startswith('source-reviewed-native-pdf-')
                   for key in ('projectionVersion', 'metadataVersion', 'internalProjectionVersion'))
            or isinstance(value.get('storageShape'), str) and value['storageShape'].startswith(('cas-native-pdf-', 'full-native-pdf-'))
            or value.get('kind') == 'pdf_native_source_reviewed_region'
            or any(key in value for key in ('nativePdfRegionRef', 'nativeGlyphMembershipRef', 'nativeTextSpansRef',
                                           'unadoptedGlyphsRef', 'nativeTextSpans'))
            or isinstance(value.get('blockId'), str) and value['blockId'].startswith('native-pdf-region:')
            or isinstance(value.get('id'), str) and value['id'].startswith(('canonical-native-pdf:', 'canonical-native-pdf-public:'))):
        return True
    return any(_pdf_candidate(row) for row in value.values() if isinstance(row, (dict, list)))


class NativePdfCanonicalSourceRegistry(NativeCanonicalSourceRegistry):
    def __init__(self, root, artifact_store):
        super().__init__(root, artifact_store)
        prior_validator = self.validator
        def validate(snapshot):
            if not isinstance(snapshot, dict):
                raise SourceRegistryError('CANONICAL_SCHEMA_UNSUPPORTED', 'Canonical snapshot must be a JSON object')
            if (snapshot.get('schema') == SCHEMA and snapshot.get('producerKind') == 'native_pdf'
                    and snapshot.get('storageShape') == STORAGE_SHAPE):
                return validate_native_pdf_transport(snapshot, artifact_store=artifact_store)
            if _pdf_candidate(snapshot):
                raise SourceRegistryError('PDF_CANONICAL_PRODUCER_MISMATCH',
                    'PDF evidence requires its exact independent actual-CAS public schema')
            return prior_validator(snapshot)
        self.validator = validate

    def _pdf_revision_path(self, revision):
        path = self.root / 'native-pdf-revisions' / (_identity(revision, 'projectionRevisionSha256') + '.json')
        if not path.resolve().is_relative_to(self.root):
            raise SourceRegistryError('PDF_REVISION_PATH_INVALID', 'PDF revision locator must stay within its registry')
        return path

    def publish(self, projection):
        receipt = super().publish(projection)
        if projection.get('schema') == SCHEMA:
            alias = self._pdf_revision_path(projection['projectionRevisionSha256'])
            alias.parent.mkdir(parents=True, exist_ok=True)
            try:
                os.link(self._path(projection['projectionSha256']), alias)
            except FileExistsError:
                if alias.read_bytes() != self._path(projection['projectionSha256']).read_bytes():
                    raise SourceRegistryError('PDF_REVISION_CONFLICT', 'PDF revision already names different public bytes')
        return receipt

    def validate_native_chunks(self, chunks):
        ordinary, groups = [], {}
        for chunk in chunks:
            if _pdf_candidate(chunk):
                revision = _identity(chunk.get('projectionRevisionSha256'), 'projectionRevisionSha256')
                groups.setdefault(revision, []).append(chunk)
            else: ordinary.append(chunk)
        count = super().validate_native_chunks(ordinary)
        for revision, members in groups.items():
            try:
                locator = json.loads(self._pdf_revision_path(revision).read_text(encoding='utf-8'))
                snapshot = self.load(locator['projectionSha256'])
            except (OSError, ValueError, KeyError, TypeError) as error:
                raise SourceRegistryError('PDF_CANONICAL_SOURCE_UNAVAILABLE',
                    'PDF index source is unregistered or its actual CAS cannot be verified') from error
            if (snapshot.get('schema') != SCHEMA or snapshot.get('producerKind') != 'native_pdf'
                    or snapshot.get('storageShape') != STORAGE_SHAPE or snapshot.get('projectionRevisionSha256') != revision
                    or _serialize(locator) != _serialize(snapshot) or members != snapshot['chunks']):
                raise SourceRegistryError('PDF_INDEX_PARENT_MISMATCH',
                    'PDF index must retain exact complete published parents in source order')
        return count + len(groups)
