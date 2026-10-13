"""Explicit complete-page PDF inputs retain their full parent source identity.

No model, renderer, network, or parser is executed here. A known extraction
recipe is replayed from actual parent bytes on every validation. The retained
producer source is provenance and is never imported or executed by a reader.
Page-object equality is separate from rendered pixels and source coordinates.
"""
from __future__ import annotations

import copy
from decimal import Decimal
import hashlib
from io import BytesIO
import json
import math
from pathlib import Path
import re

from artifact_store import ArtifactStore

SCHEMA = 'consense.pdf-selected-source.v1'
RECIPE = {'version': 'pypdf-complete-selected-pages-v1', 'library': 'pypdf',
          'libraryVersion': '6.19.0', 'operation': 'PdfWriter.append',
          'pageExtent': 'complete_source_page', 'importOutline': False,
          'excludedFields': [], 'sourceOrderPreserved': True,
          'payloadNumericComparison': 'exact_decimal_value',
          'flattenForms': False, 'rasterized': False}
EMPTY_USER_PASSWORD_SCHEMA = 'consense.pdf-selected-source.empty-user-password.v2'
EMPTY_USER_PASSWORD_RECIPE = {**RECIPE,
    'version': 'pypdf-complete-selected-pages-empty-user-password-v2',
    'encryptedSource': 'successful_empty_user_password_only_preserve_original_bytes'}
SCHEMA_RECIPES = {SCHEMA: RECIPE, EMPTY_USER_PASSWORD_SCHEMA: EMPTY_USER_PASSWORD_RECIPE}
SUPPORTED_SCHEMAS = frozenset(SCHEMA_RECIPES)
RENDER_RECIPE = {'version': 'selected-pages-render-request-v1',
                 'pageExtent': 'complete_page', 'annotations': 'include',
                 'formsInitialization': 'before_page_open',
                 'cropAndRotation': 'retain_source_page_properties',
                 'executionStatus': 'not_executed', 'pixelValidationStatus': 'not_run'}


class SelectedSourceError(ValueError):
    pass


def _require(condition, message):
    if not condition:
        raise SelectedSourceError(message)


def canonical_bytes(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _reader(raw, recipe=RECIPE):
    import pypdf
    _require(pypdf.__version__ == RECIPE['libraryVersion'],
             'Selected-source replay requires the exact pypdf dependency version')
    reader = pypdf.PdfReader(BytesIO(raw), strict=True)
    if recipe == RECIPE:
        _require(not reader.is_encrypted, 'Encrypted PDF needs a separately reviewed extraction recipe')
    elif recipe == EMPTY_USER_PASSWORD_RECIPE:
        _require(reader.is_encrypted, 'Empty-user-password recipe requires actual encrypted parent bytes')
        result = reader.decrypt('')
        _require(int(result) == 1 and result.name == 'USER_PASSWORD',
                 'Explicit empty user password must authenticate as USER_PASSWORD/1; no guessing')
    else:
        raise SelectedSourceError('Unknown selected-source read recipe')
    return reader


def _pages(selected, count):
    _require(type(count) is int and count > 0, 'Positive actual parent page count required')
    _require(isinstance(selected, list) and bool(selected) and
             all(type(p) is int and 1 <= p <= count for p in selected),
             'Explicit one-based complete physical pages must be within the parent')
    _require(selected == sorted(set(selected)),
             'Selected physical pages must be unique and preserve original order')
    return selected


def _frame(page):
    def box(rect):
        values = [float(v) for v in rect]
        _require(len(values) == 4 and all(math.isfinite(v) for v in values), 'Invalid PDF page box')
        return values
    rotation, unit = page.get('/Rotate', 0), page.get('/UserUnit', 1)
    _require(type(rotation) is not bool and isinstance(rotation, int) and rotation % 90 == 0,
             'Unsupported PDF rotation')
    _require(type(unit) is not bool and isinstance(unit, (int, float)) and math.isfinite(unit) and unit > 0,
             'Invalid PDF UserUnit')
    return {'mediaBox': box(page.mediabox), 'cropBox': box(page.cropbox),
            'trimBox': box(page.trimbox), 'bleedBox': box(page.bleedbox),
            'artBox': box(page.artbox), 'rotation': int(rotation), 'userUnit': float(unit),
            'coordinateUnit': 'pt', 'originalTransformStatus': 'unknown'}


def _object(value, ancestors=None):
    """Compare visible page payload; document links are explicitly not certified."""
    from pypdf.generic import (ArrayObject, BooleanObject, ByteStringObject,
                              DictionaryObject, NullObject, StreamObject)
    value = value.get_object() if callable(getattr(value, 'get_object', None)) else value
    ancestors = set() if ancestors is None else ancestors
    if id(value) in ancestors:
        return {'cycle': 'retained_graph_cycle'}
    lineage = ancestors | {id(value)}
    if isinstance(value, StreamObject):
        raw = value.get_data()
        return {'decodedStreamSha256': _sha(raw), 'decodedStreamByteCount': len(raw),
                'dictionary': {str(k): _object(v, lineage) for k, v in value.items()
                               if k not in ('/Length', '/Filter', '/DecodeParms')}}
    if isinstance(value, (DictionaryObject, dict)):
        if value.get('/Type') == '/Page':
            return {'documentPageLink': 'unknown'}
        return {str(k): _object(v, lineage) for k, v in value.items()
                if k not in ('/P', '/Parent', '/Kids')}
    if isinstance(value, (ArrayObject, list)):
        return [_object(v, lineage) for v in value]
    if isinstance(value, ByteStringObject):
        return {'byteString': bytes(value).hex()}
    if isinstance(value, BooleanObject):
        return bool(value.value)
    if isinstance(value, NullObject) or value is None:
        return None
    if isinstance(value, str):
        return str(value)
    if isinstance(value, (int, float)):
        # PDF writers may serialize 24.0 as 24. Compare exact numeric values,
        # without turning resource-graph validation into raw PDF token equality.
        number = Decimal(str(value))
        _require(number.is_finite(), 'Non-finite PDF payload number')
        return {'pdfNumber': '0' if number == 0 else format(number.normalize(), 'f')}
    raise SelectedSourceError('Unsupported PDF payload type: ' + type(value).__name__)


def _page_payload(page):
    contents = page.get_contents()
    raw = b'' if contents is None else contents.get_data()
    annotations = []
    for reference in page.get('/Annots', []):
        annotation = reference.get_object()
        inherited, node, seen = {}, annotation, set()
        while node is not None:
            _require(id(node) not in seen, 'Cyclic annotation parent chain')
            seen.add(id(node))
            for key in ('/FT', '/V', '/DV', '/Ff', '/DA', '/Q', '/T', '/TU', '/TM'):
                if key in node and key not in inherited:
                    inherited[key] = _object(node[key])
            parent = node.get('/Parent')
            node = parent.get_object() if parent is not None else None
        annotations.append({'annotation': _object(annotation), 'inheritedFieldState': inherited})
    return {'frame': _frame(page), 'contentSha256': _sha(raw), 'contentByteCount': len(raw),
            'resourcesSha256': digest(_object(page.get('/Resources', {}))),
            'annotationsSha256': digest(annotations), 'annotationCount': len(annotations),
            'comparisonScope': 'decoded_content_resources_annotations_and_inherited_field_state',
            'documentLinksStatus': 'unknown', 'pixelEquivalenceStatus': 'not_verified'}


def verify_actual_full_pdf_input_binding(parent_raw, provider_raw, parent_count, provider_count, mappings):
    """Verify unchanged full-source bytes and their physical page identities.

    Matching content cannot prove lineage between repeated equal pages. A
    differing derived PDF requires the existing typed producer/CAS replay,
    regardless of deleted declarations, and is not admitted by this branch.
    """
    _require(parent_raw == provider_raw, 'Derived PDF requires complete typed selected-source proof')
    parent = _input_binding_reader(parent_raw)
    _require(type(parent_count) is int and parent_count == len(parent.pages),
             'Original source page count differs from actual PDF')
    _require(type(provider_count) is int and provider_count == len(parent.pages),
             'Provider input page count differs from actual PDF')
    _require(isinstance(mappings, list) and bool(mappings), 'Actual PDF input requires explicit page mappings')
    for row in mappings:
        _require(isinstance(row, dict), 'Actual PDF page mapping is not an object')
        physical, input_page = row.get('physicalPageNo'), row.get('providerInputPageNo')
        _require(type(physical) is int and 1 <= physical <= len(parent.pages)
                 and type(input_page) is int and 1 <= input_page <= len(parent.pages),
                 'Actual PDF mapping is outside the parent or provider physical pages')
        _require(physical == input_page,
                 'The original PDF input cannot map a page to a different original physical page')


def _input_binding_reader(raw):
    """Read legacy actual inputs, including existing empty-user-password PDFs.

    This is distinct from the selected producer's explicit versioned recipe.
    Its default plain-v1 path still refuses encrypted parents; the separately
    selected v2 recipe requires USER_PASSWORD/1. No password is persisted or guessed.
    """
    import pypdf
    from pypdf.errors import PyPdfError
    _require(pypdf.__version__ == RECIPE['libraryVersion'],
             'Actual PDF input validation requires the exact pypdf dependency version')
    try:
        reader = pypdf.PdfReader(BytesIO(raw), strict=True)
        if reader.is_encrypted:
            _require(bool(reader.decrypt('')), 'Actual PDF input is not readable with its empty user password')
        return reader
    except PyPdfError as error:
        raise SelectedSourceError('Actual PDF input cannot be read: ' + str(error)) from error


def _derive(parent_raw, selected, recipe=RECIPE):
    import pypdf
    parent = _reader(parent_raw, recipe)
    _pages(selected, len(parent.pages))
    writer = pypdf.PdfWriter()
    writer.append(parent, pages=[p - 1 for p in selected], import_outline=False, excluded_fields=[])
    output = BytesIO()
    writer.write(output)
    raw = output.getvalue()
    derived = _reader(raw)
    _require(len(derived.pages) == len(selected), 'Derived input does not contain the complete selected page set')
    rows = []
    for index, physical in enumerate(selected):
        parent_payload, derived_payload = _page_payload(parent.pages[physical - 1]), _page_payload(derived.pages[index])
        _require(parent_payload == derived_payload,
                 'Extraction changed selected page content, resources, annotations, form state or page properties')
        rows.append({'providerPageIndex': index, 'providerInputPageNo': index + 1,
                     'originalPhysicalPageNo': physical, 'parentPage': parent_payload,
                     'derivedPage': derived_payload,
                     'mappingStatus': 'verified_page_object_clone_not_pixel_or_affine_validation',
                     'originalTransformStatus': 'unknown'})
    return raw, len(parent.pages), rows


def _dependency_files():
    import pypdf
    root = Path(pypdf.__file__).resolve().parent
    return [(p.relative_to(root).as_posix(), p.read_bytes()) for p in sorted(root.rglob('*.py'))]


def _descriptor(descriptor, sha, count, label):
    _require(isinstance(descriptor, dict) and descriptor.get('schema') == 'consense.artifact-descriptor.v1'
             and descriptor.get('storage') == 'content_addressed_disk'
             and descriptor.get('sha256') == sha and descriptor.get('byteCount') == count
             and descriptor.get('relativePath') == 'sha256/' + sha[:2] + '/' + sha + '.blob'
             and isinstance(descriptor.get('identityScope'), str) and bool(descriptor['identityScope']),
             label + ' requires a matching portable CAS descriptor')


def _validate_shape(manifest):
    keys = {'schema', 'parentSource', 'providerInput', 'selectedPhysicalPages', 'pageMap',
            'extractionRecipe', 'renderRecipe', 'producer', 'limitations', 'qualityAccepted', 'manifestSha256'}
    _require(isinstance(manifest, dict) and set(manifest) == keys and isinstance(manifest['schema'], str)
             and manifest['schema'] in SUPPORTED_SCHEMAS,
             'Unknown or incomplete selected-source manifest')
    _require(manifest['manifestSha256'] == digest({k: v for k, v in manifest.items() if k != 'manifestSha256'}),
             'Selected-source manifest seal changed')
    _require(manifest['extractionRecipe'] == SCHEMA_RECIPES[manifest['schema']] and manifest['renderRecipe'] == RENDER_RECIPE
             and manifest['qualityAccepted'] is False, 'Unknown extraction/render recipe or false quality claim')
    parent, provider = manifest['parentSource'], manifest['providerInput']
    _require(isinstance(parent, dict) and set(parent) == {'sourceFilename', 'sourceSha256',
             'sourceRevisionSha256', 'byteCount', 'pageCount', 'mediaType', 'artifactDescriptor'},
             'Explicit complete parent identity required')
    _require(isinstance(provider, dict) and set(provider) == {'inputSha256', 'byteCount',
             'pageCount', 'mediaType', 'artifactDescriptor'}, 'Explicit derived provider identity required')
    for value, key in ((parent, 'sourceSha256'), (provider, 'inputSha256')):
        _require(isinstance(value[key], str) and re.fullmatch('[0-9a-f]{64}', value[key]) is not None
                 and type(value['byteCount']) is int and value['byteCount'] > 0
                 and value['mediaType'] == 'application/pdf', 'Pinned actual PDF bytes required')
        _descriptor(value['artifactDescriptor'], value[key], value['byteCount'], key)
    _require(isinstance(parent['sourceFilename'], str) and bool(parent['sourceFilename']) and
             parent['sourceRevisionSha256'] == parent['sourceSha256'], 'Parent revision must bind exact original bytes')
    selected = _pages(manifest['selectedPhysicalPages'], parent['pageCount'])
    _require(type(provider['pageCount']) is int and provider['pageCount'] == len(selected),
             'Provider input must contain every requested physical page')
    _require(isinstance(manifest['pageMap'], list) and len(manifest['pageMap']) == len(selected),
             'One-to-one complete provider/parent map required')
    for index, physical in enumerate(selected):
        row = manifest['pageMap'][index]
        _require(isinstance(row, dict) and type(row.get('providerPageIndex')) is int
                 and row['providerPageIndex'] == index and type(row.get('providerInputPageNo')) is int
                 and row['providerInputPageNo'] == index + 1 and type(row.get('originalPhysicalPageNo')) is int
                 and row['originalPhysicalPageNo'] == physical, 'Invalid provider/parent page map')
    producer = manifest['producer']
    _require(isinstance(producer, dict) and set(producer) == {'codeArtifact', 'dependencyFiles', 'dependencyInventorySha256'}
             and isinstance(producer['dependencyFiles'], list) and bool(producer['dependencyFiles']),
             'Pinned producer and exact extraction dependency inventory required')
    code = producer['codeArtifact']
    _require(isinstance(code, dict) and isinstance(code.get('sha256'), str)
             and re.fullmatch('[0-9a-f]{64}', code['sha256']) is not None
             and type(code.get('byteCount')) is int and code['byteCount'] > 0,
             'Producer requires its pinned nonempty source artifact')
    _descriptor(code, code['sha256'], code['byteCount'], 'producer')
    _require(all(isinstance(row, dict) and set(row) == {'relativePath', 'sha256', 'byteCount', 'artifactDescriptor'}
                 for row in producer['dependencyFiles']), 'Malformed exact dependency inventory')
    _require(producer['dependencyInventorySha256'] == digest(producer['dependencyFiles']),
             'Extraction dependency inventory changed')


def _validate_bytes(manifest, parent_raw, provider_raw):
    _validate_shape(manifest)
    for identity, raw, key in ((manifest['parentSource'], parent_raw, 'sourceSha256'),
                               (manifest['providerInput'], provider_raw, 'inputSha256')):
        _require(_sha(raw) == identity[key] and len(raw) == identity['byteCount'],
                 'Actual parent/provider PDF differs from pinned bytes')
    dependencies = _dependency_files()
    observed = [(row.get('relativePath'), row.get('sha256'), row.get('byteCount'))
                for row in manifest['producer']['dependencyFiles']]
    _require(observed == [(name, _sha(raw), len(raw)) for name, raw in dependencies],
             'Current extraction dependency bytes differ from known replay recipe')
    for row in manifest['producer']['dependencyFiles']:
        _descriptor(row.get('artifactDescriptor'), row['sha256'], row['byteCount'], 'dependency')
    expected, count, rows = _derive(parent_raw, manifest['selectedPhysicalPages'],
                                   SCHEMA_RECIPES[manifest['schema']])
    _require(count == manifest['parentSource']['pageCount'], 'Declared parent count differs from actual PDF')
    _require(expected == provider_raw and rows == manifest['pageMap'],
             'Derived bytes or complete source-page mapping do not reproduce from actual parent')
    result = {'status': 'verified', 'manifestSha256': manifest['manifestSha256'],
            'parentSourceSha256': manifest['parentSource']['sourceSha256'],
            'providerInputSha256': manifest['providerInput']['inputSha256'],
            'parentPageCount': count, 'providerPageCount': len(rows),
            'selectedPhysicalPages': copy.deepcopy(manifest['selectedPhysicalPages']),
            'geometryStatus': 'unknown', 'qualityAccepted': False}
    if manifest['schema'] == EMPTY_USER_PASSWORD_SCHEMA:
        parent = _reader(parent_raw, EMPTY_USER_PASSWORD_RECIPE)
        flags = int(parent.trailer['/Encrypt']['/P'])
        result['actualSourceAuthentication'] = {'isEncrypted': True, 'method': "PdfReader.decrypt('')",
            'returnName': 'USER_PASSWORD', 'returnInteger': 1, 'rawPdfP': flags,
            'actualUserAccessPermissions': int(parent.user_access_permissions),
            'copyExtractionBit': bool(flags & (1 << 4)), 'originalPermissionFlagsChanged': False}
    return result


def verify_selected_source_files(manifest, parent_path, provider_path):
    """Worker pre/postflight: fresh actual bytes and deterministic extraction."""
    return _validate_bytes(manifest, Path(parent_path).read_bytes(), Path(provider_path).read_bytes())


def validate_selected_source(manifest, artifact_store):
    """Cold replay, entirely from CAS; never open former producer host paths."""
    _require(isinstance(artifact_store, ArtifactStore), 'Selected-source validation requires actual CAS')
    _validate_shape(manifest)
    producer = manifest['producer']
    code = producer['codeArtifact']
    _require(isinstance(code, dict), 'Producer source CAS is required')
    artifact_store.read_asset(code)
    for row in producer['dependencyFiles']:
        artifact_store.read_asset(row['artifactDescriptor'])
    return _validate_bytes(manifest,
        artifact_store.read_asset(manifest['parentSource']['artifactDescriptor']),
        artifact_store.read_asset(manifest['providerInput']['artifactDescriptor']))


def copy_selected_source_artifacts(manifest, source_store, target_store):
    """Copy every bound producer/input CAS byte into a new parser capture store."""
    _require(isinstance(target_store, ArtifactStore), 'Copy requires an actual target CAS')
    validate_selected_source(manifest, source_store)
    descriptors = [manifest['parentSource']['artifactDescriptor'],
                   manifest['providerInput']['artifactDescriptor'], manifest['producer']['codeArtifact']]
    descriptors += [row['artifactDescriptor'] for row in manifest['producer']['dependencyFiles']]
    for descriptor in descriptors:
        captured = target_store.put_bytes(source_store.read_asset(descriptor),
                                         identity_scope=descriptor['identityScope'])
        _require(captured == descriptor, 'Copied selected-source CAS identity changed')
    return validate_selected_source(manifest, target_store)


def create_selected_source(parent_path, selected_physical_pages, output_directory, artifact_store,
                           *, expected_source_sha256, expected_page_count, source_schema=SCHEMA):
    """Create one provider PDF from an explicit full-page set; original read only."""
    _require(isinstance(artifact_store, ArtifactStore), 'Actual artifact store required')
    parent_path, output = Path(parent_path).resolve(), Path(output_directory).resolve()
    _require(not output.exists(), 'Selected-source output must be a new owned directory')
    _require(source_schema in SUPPORTED_SCHEMAS, 'Explicit known source schema required')
    recipe = SCHEMA_RECIPES[source_schema]
    parent_raw = parent_path.read_bytes()
    parent_mtime = parent_path.stat().st_mtime_ns
    _require(_sha(parent_raw) == expected_source_sha256, 'Original PDF SHA differs before extraction')
    provider_raw, actual_count, rows = _derive(parent_raw, selected_physical_pages, recipe)
    _require(type(expected_page_count) is int and actual_count == expected_page_count,
             'Expected complete parent page count differs from actual PDF')
    dependencies = [{'relativePath': name, 'sha256': _sha(raw), 'byteCount': len(raw),
                     'artifactDescriptor': artifact_store.put_bytes(raw, identity_scope='exact_pypdf_dependency_source_bytes')}
                    for name, raw in _dependency_files()]
    manifest = {'schema': source_schema,
        'parentSource': {'sourceFilename': parent_path.name, 'sourceSha256': _sha(parent_raw),
            'sourceRevisionSha256': _sha(parent_raw), 'byteCount': len(parent_raw),
            'pageCount': actual_count, 'mediaType': 'application/pdf',
            'artifactDescriptor': artifact_store.put_bytes(parent_raw, identity_scope='exact_original_parent_pdf_bytes')},
        'providerInput': {'inputSha256': _sha(provider_raw), 'byteCount': len(provider_raw),
            'pageCount': len(rows), 'mediaType': 'application/pdf',
            'artifactDescriptor': artifact_store.put_bytes(provider_raw, identity_scope='exact_derived_complete_selected_pdf_bytes')},
        'selectedPhysicalPages': copy.deepcopy(selected_physical_pages), 'pageMap': rows,
        'extractionRecipe': copy.deepcopy(recipe), 'renderRecipe': copy.deepcopy(RENDER_RECIPE),
        'producer': {'codeArtifact': artifact_store.put_bytes(Path(__file__).read_bytes(),
                      identity_scope='exact_selected_source_producer_source_bytes'),
                     'dependencyFiles': dependencies, 'dependencyInventorySha256': digest(dependencies)},
        'limitations': ['Selected input is not the full parent PDF',
            'Page object clone validation is not rendered-pixel, affine or legal-content acceptance',
            'Document-level links/tag trees/bookmarks are not source-reviewed by this recipe',
            'The complete requested set is explicit; table-group discovery is not performed'],
        'qualityAccepted': False}
    manifest['manifestSha256'] = digest(manifest)
    validate_selected_source(manifest, artifact_store)
    _require(parent_path.read_bytes() == parent_raw, 'Original PDF changed during selection')
    _require(parent_path.stat().st_mtime_ns == parent_mtime, 'Original PDF mtime changed during selection')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'provider-input.pdf').write_bytes(provider_raw)
    (output / 'selected-source-manifest.json').write_bytes(canonical_bytes(manifest))
    return manifest
