"""Capture already-produced parser files without model or parser execution.

A file manifest pins original source bytes, actual provider input, every named
raw output/resource, runtime identity, explicit page frames and terminal state.
Vendor bytes are copied and hashed as files, never reconstructed from decoded
JSON. A capture manifest or receipt has its own canonical-JSON identity and is
never described as vendor transport. Pickles are archived, never deserialized.
Typed selected-PDF PNG inputs additionally replay their complete CPU raster;
this does not execute OCR, accept reference geometry or adopt legal content.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re

from artifact_store import ArtifactStore
from structured_parser_evidence import (ParserEvidenceError, normalize_parser_run,
                                       save_evidence_bundle, SCHEMA, _capture_manifest,
                                       _run_completeness)


CAPTURE_SCHEMA = 'consense.parser-file-capture.v1'
AGGREGATION_VERSION = 'independent-onepage-capture-batch-v1'


def canonical_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
        separators=(',', ':'), allow_nan=False).encode('utf-8')).hexdigest()


def file_spec(path, *, capture_scope, expected_sha256=None, expected_byte_count=None):
    """Describe an actual file, optionally checking a pre-existing receipt."""
    path = Path(path)
    digest, count = hashlib.sha256(), 0
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            count += len(chunk)
    sha = digest.hexdigest()
    if expected_sha256 is not None and sha != expected_sha256:
        raise ParserEvidenceError('File SHA-256 differs from its existing receipt: ' + str(path))
    if expected_byte_count is not None and count != expected_byte_count:
        raise ParserEvidenceError('File byteCount differs from its existing receipt: ' + str(path))
    return {'path': str(path), 'sha256': sha, 'byteCount': count, 'captureScope': capture_scope}


def _capture_spec(spec, store, base):
    if not isinstance(spec, dict) or not isinstance(spec.get('path'), str) or not spec['path']:
        raise ParserEvidenceError('Capture requires an actual file path and pinned identity')
    if not isinstance(spec.get('sha256'), str) or not re.fullmatch(r'[0-9a-f]{64}', spec['sha256']):
        raise ParserEvidenceError('Capture file requires lowercase SHA-256')
    if type(spec.get('byteCount')) is not int or spec['byteCount'] < 0:
        raise ParserEvidenceError('Capture file requires a nonnegative byteCount')
    if not isinstance(spec.get('captureScope'), str) or not spec['captureScope']:
        raise ParserEvidenceError('Capture scope must distinguish actual vendor files from derived records')
    path = Path(spec['path'])
    if not path.is_absolute():
        path = (base / path).resolve()
        if not path.is_relative_to(base):
            raise ParserEvidenceError('Relative capture path escapes its manifest base directory')
    descriptor = store.put_file(path)
    if descriptor['sha256'] != spec['sha256'] or descriptor['byteCount'] != spec['byteCount']:
        raise ParserEvidenceError('Capture file changed or does not match pinned SHA-256/byteCount: ' + str(path))
    return descriptor


def capture_parser_files(manifest, artifact_store, *, base_directory=None):
    """Return (normalized run, capture receipt) from a pinned file inventory."""
    if not isinstance(artifact_store, ArtifactStore):
        raise ParserEvidenceError('Capture requires an ArtifactStore')
    if not isinstance(manifest, dict) or manifest.get('schema') != CAPTURE_SCHEMA:
        raise ParserEvidenceError('Unsupported parser file capture manifest')
    value = copy.deepcopy(manifest)
    base = Path(base_directory or '.').resolve()
    source = value.get('source')
    source_spec = value.get('sourceArtifact')
    if not isinstance(source, dict) or not isinstance(source_spec, dict):
        raise ParserEvidenceError('Capture requires original source identity and actual original bytes')
    original = _capture_spec(source_spec, artifact_store, base)
    if original['sha256'] != source.get('sourceSha256'):
        raise ParserEvidenceError('Captured original source SHA-256 disagrees with source identity')
    source_name = source_spec.get('artifactName')
    if not isinstance(source_name, str) or not source_name:
        raise ParserEvidenceError('sourceArtifact requires a stable artifactName')
    outputs, artifacts = {}, {source_name: original}
    for name, spec in value.get('outputs', {}).items():
        outputs[name] = _capture_spec(spec, artifact_store, base)
    for name, spec in value.get('assets', {}).items():
        descriptor = _capture_spec(spec, artifact_store, base)
        if name in artifacts and artifacts[name] != descriptor:
            raise ParserEvidenceError('Original source asset name conflicts with supplied inventory')
        artifacts[name] = descriptor
    required = value.get('requiredArtifacts')
    if not isinstance(required, list) or source_name not in required:
        raise ParserEvidenceError('Original source must be included in explicit requiredArtifacts')
    selection = _selected_source_binding(value, original, artifacts, artifact_store)
    run = normalize_parser_run(value.get('parser'), outputs, source, value.get('identity'),
        value.get('pageMappings'), artifacts=artifacts, required_artifacts=required,
        provider_input_identity=value.get('providerInputIdentity'), terminal=value.get('terminal'),
        capture_issues=value.get('captureIssues'), artifact_store=artifact_store)
    if selection is not None:
        run['selectedSourceProvenance'] = selection
    run['fileCaptureProvenance'] = _file_capture_provenance(value)
    if selection is not None:
        run['completeness'] = _run_completeness(run, artifact_store)
    run['runContentSha256'] = canonical_digest({k: v for k, v in run.items() if k != 'runContentSha256'})
    receipt = {'schema': 'consense.parser-file-capture-receipt.v1', 'runId': run['runId'],
        'runContentSha256': run['runContentSha256'], 'sourceSha256': run['source']['sourceSha256'],
        'manifestSha256': canonical_digest(value), 'sourceDescriptor': original,
        'rawOutputCount': len(outputs), 'artifactCount': len(artifacts),
        'rawOutputBytes': sum(record['byteCount'] for record in run['rawOutputs'].values()),
        'artifactBytes': sum(record['byteCount'] for record in run['artifacts'].values()),
        'completeness': copy.deepcopy(run['completeness']), 'terminal': copy.deepcopy(run['terminal']),
        'captureLimitations': copy.deepcopy(value.get('captureLimitations', [])),
        'parserExecutedDuringCapture': False, 'vendorRawBytesReserialized': False,
        'qualityAccepted': False}
    receipt['receiptSha256'] = canonical_digest(receipt)
    return run, receipt


def _actual_asset_bytes(record, store):
    from structured_parser_evidence import read_artifact_record
    if isinstance(record, dict) and record.get('schema') == 'consense.artifact-descriptor.v1':
        if not isinstance(store, ArtifactStore):
            raise ParserEvidenceError('Actual input validation requires its ArtifactStore')
        return store.read_asset(record)
    return read_artifact_record(record, artifact_store=store)


def _ambiguous_selected_lineage(raw):
    """Classify a rejected JSON object's top-level claims without accepting it.

    Retain every object pair so an ordinary later schema cannot erase a known
    first declaration. Only root keys/schema values count; nested captions,
    filenames and strings containing schema words do not. This relaxed pass
    never supplies capture data or changes the retained original bytes.
    """
    class ObjectPairs(tuple):
        pass
    decoder = json.JSONDecoder(object_pairs_hook=ObjectPairs,
                               parse_constant=lambda value: None)
    try:
        # Replacement is only for rejecting recognizable metadata, not for
        # interpreting/accepting invalid UTF-8 as a valid source record. A full
        # first object also keeps trailing junk from hiding its root claims.
        value, _ = decoder.raw_decode(raw.decode('utf-8', errors='replace').lstrip())
    except ValueError:
        return False
    if not isinstance(value, ObjectPairs):
        return False
    schemas = (item for key, item in value if key == 'schema')
    if any(isinstance(item, str) and
           item.startswith(('consense.pdf-selected-source.',
                            'consense.selected-pdf-full-page-raster.')) for item in schemas):
        return True
    keys = {key for key, item in value}
    return ({'parentSource', 'providerInput', 'selectedPhysicalPages', 'pageMap',
             'extractionRecipe', 'producer'} <= keys or
            {'selectionManifestArtifact', 'selectionManifestSha256', 'selectedProviderPageNo',
             'rasterInput', 'renderRecipe', 'producer'} <= keys)


def _source_manifest_assets(artifacts, store):
    """Discover captured selection schema from complete bytes, never a filename."""
    from pdf_selected_source import SUPPORTED_SCHEMAS
    from selected_source_raster import SCHEMA as RASTER_SCHEMA
    from structured_parser_evidence import _unique_object
    selected, rasters = [], []
    for name, record in artifacts.items():
        raw = _actual_asset_bytes(record, store)
        if not raw.lstrip().startswith(b'{'):
            continue
        try:
            value = json.loads(raw.decode('utf-8'), object_pairs_hook=_unique_object,
                               parse_constant=lambda v: (_ for _ in ()).throw(ValueError(v)))
        except (UnicodeError, ValueError) as error:
            if _ambiguous_selected_lineage(raw):
                raise ParserEvidenceError(
                    'Malformed or ambiguous selected-source/raster metadata cannot use the legacy image branch') from error
            continue  # Other captured resources are retained, not interpreted.
        if not isinstance(value, dict):
            continue
        schema = value.get('schema')
        if isinstance(schema, str) and schema in SUPPORTED_SCHEMAS:
            selected.append(name)
        elif schema == RASTER_SCHEMA:
            rasters.append(name)
        elif isinstance(schema, str) and (schema.startswith('consense.pdf-selected-source.') or
                schema.startswith('consense.selected-pdf-full-page-raster.')):
            raise ParserEvidenceError('Unknown selected-source/raster version cannot use the legacy image branch')
        elif ({'parentSource', 'providerInput', 'selectedPhysicalPages', 'pageMap', 'extractionRecipe', 'producer'} <= set(value)
              or {'selectionManifestArtifact', 'selectionManifestSha256', 'selectedProviderPageNo',
                  'rasterInput', 'renderRecipe', 'producer'} <= set(value)):
            raise ParserEvidenceError('Selection/raster payload cannot be hidden by removing or relabelling its schema')
    return selected, rasters


def _selected_manifest_assets(artifacts, store):
    return _source_manifest_assets(artifacts, store)[0]


def _verify_actual_pdf_input(source, provider, mappings, artifacts, store):
    """Close PDF input against immutable original bytes, even without metadata markers."""
    from pdf_selected_source import (_input_binding_reader, SelectedSourceError, verify_actual_full_pdf_input_binding)
    name = provider.get('inputArtifactName')
    if name not in artifacts:
        raise ParserEvidenceError('Actual provider input is unavailable for source-page validation')
    provider_raw = _actual_asset_bytes(artifacts[name], store)
    parent_record = next((record for record in artifacts.values()
                          if record.get('sha256') == source.get('sourceSha256')), None)
    parent_raw = _actual_asset_bytes(parent_record, store) if parent_record is not None else None
    # A PDF header is a byte fact. Relabelling a PDF as a PNG does not select
    # the legitimate independent-image branch. Native DOCX->PDF is separate.
    provider_pdf = provider_raw.startswith(b'%PDF-')
    parent_pdf = parent_raw is not None and parent_raw.startswith(b'%PDF-')
    try:
        if provider_pdf and provider.get('mediaType') != 'application/pdf':
            raise ParserEvidenceError('Actual PDF input cannot be relabelled as an independent image')
        if parent_pdf:
            if source.get('mediaType') != 'application/pdf':
                raise ParserEvidenceError('Actual original PDF cannot be relabelled as a non-PDF source')
            if not provider_pdf:
                if len(_input_binding_reader(parent_raw).pages) != source.get('pageCount'):
                    raise ParserEvidenceError('Original source page count differs from actual PDF')
                _verify_actual_single_page_image(provider_raw, provider, mappings)
                return  # Existing independently captured image child semantics remain.
            verify_actual_full_pdf_input_binding(parent_raw, provider_raw, source.get('pageCount'),
                                                 provider.get('pageCount'), mappings)
        elif provider_pdf and source.get('mediaType') == 'application/pdf':
            raise ParserEvidenceError('A PDF provider input requires the actual full original PDF bytes')
    except (SelectedSourceError, ValueError, TypeError, KeyError, OSError) as error:
        raise ParserEvidenceError('Actual source/provider PDF page closure failed: ' + str(error)) from error


def _verify_actual_single_page_image(raw, provider, mappings):
    """The legacy image exception must be a real single image, not a relabelled PDF."""
    from io import BytesIO
    from PIL import Image
    formats = {'image/png': 'PNG', 'image/jpeg': 'JPEG', 'image/tiff': 'TIFF', 'image/webp': 'WEBP'}
    if (provider.get('mediaType') not in formats or type(provider.get('pageCount')) is not int
            or provider['pageCount'] != 1 or not isinstance(mappings, list) or len(mappings) != 1
            or mappings[0].get('providerInputPageNo') != 1):
        raise ParserEvidenceError('The retained image route requires one complete independent image/page mapping')
    try:
        with Image.open(BytesIO(raw)) as image:
            if (image.format != formats[provider['mediaType']] or image.n_frames != 1
                    or image.width <= 0 or image.height <= 0):
                raise ParserEvidenceError('Actual independent image differs from its declared format or page count')
            image.verify()
    except (OSError, ValueError, SyntaxError) as error:
        raise ParserEvidenceError('Actual independent image bytes cannot be verified: ' + str(error)) from error


def verify_source_input_capture(run, artifact_store):
    """Fresh material gate independent of optional typed-selection markers."""
    provenance = run.get('fileCaptureProvenance', {})
    manifest = provenance.get('manifest', {}) if isinstance(provenance, dict) else {}
    assets = run.get('artifacts', {})
    selected_assets, raster_assets = _source_manifest_assets(assets, artifact_store)
    if ('selectedSourceProvenance' in run or
            isinstance(manifest, dict) and ('selectedSource' in manifest or 'selectedRasterSource' in manifest) or
            selected_assets or raster_assets):
        return verify_selected_capture_binding(run, artifact_store)
    _verify_actual_pdf_input(run['source'], run.get('providerInputIdentity', {}),
                             run['requestPageMap'], assets, artifact_store)


def _selected_source_binding(manifest, original, artifacts, store):
    """Selected PDFs must close against the full parent, not replace it."""
    discovered, rasters = _source_manifest_assets(artifacts, store)
    if 'selectedRasterSource' in manifest:
        if 'selectedSource' in manifest:
            raise ParserEvidenceError('Actual provider cannot simultaneously be selected PDF and selected PNG')
        return _selected_raster_binding(manifest, original, artifacts, store, discovered, rasters)
    if rasters:
        raise ParserEvidenceError('Captured selected raster requires its complete typed PNG lineage declaration')
    if 'selectedSource' not in manifest:
        if discovered:
            raise ParserEvidenceError('Captured selected-source schema requires its complete selection declaration')
        _verify_actual_pdf_input(manifest['source'], manifest.get('providerInputIdentity', {}),
                                 manifest.get('pageMappings'), artifacts, store)
        return None
    from pdf_selected_source import validate_selected_source, SelectedSourceError
    binding = manifest['selectedSource']
    if (not isinstance(binding, dict) or set(binding) != {'artifactName', 'manifestSha256'}
            or not isinstance(binding['artifactName'], str)
            or binding['artifactName'] not in artifacts
            or binding['artifactName'] not in manifest.get('requiredArtifacts', [])):
        raise ParserEvidenceError('Selected source requires its complete captured manifest asset')
    if discovered != [binding['artifactName']]:
        raise ParserEvidenceError('Selected-source declaration differs from actual captured schema inventory')
    try:
        def unique(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise ValueError('Duplicate selected-source JSON key')
                result[key] = value
            return result
        selected = json.loads(store.read_asset(artifacts[binding['artifactName']]).decode('utf-8'),
                              object_pairs_hook=unique,
                              parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
        validation = validate_selected_source(selected, store)
    except (SelectedSourceError, ValueError, OSError, UnicodeError) as error:
        raise ParserEvidenceError('Selected-source actual CAS binding failed: ' + str(error)) from error
    parent, provider = selected['parentSource'], selected['providerInput']
    source = manifest['source']
    provider_identity = manifest.get('providerInputIdentity', {})
    provider_name = provider_identity.get('inputArtifactName')
    if (binding['manifestSha256'] != selected['manifestSha256']
            or any(original.get(k) != parent['artifactDescriptor'].get(k) for k in ('sha256', 'byteCount'))
            or source.get('sourceSha256') != parent['sourceSha256']
            or source.get('sourceRevisionSha256', source.get('sourceSha256')) != parent['sourceRevisionSha256']
            or type(source.get('pageCount')) is not int or source['pageCount'] != parent['pageCount']
            or source.get('sourceFilename') != parent['sourceFilename']
            or source.get('mediaType') != 'application/pdf'
            or provider_identity.get('inputSha256') != provider['inputSha256']
            or type(provider_identity.get('pageCount')) is not int
            or provider_identity['pageCount'] != provider['pageCount']
            or provider_identity.get('mediaType') != 'application/pdf'
            or provider_identity.get('pageIndexMapMeaning') != 'provider_input_document'
            or provider_name not in artifacts
            or any(artifacts[provider_name].get(k) != provider['artifactDescriptor'].get(k)
                   for k in ('sha256', 'byteCount'))):
        raise ParserEvidenceError('Selected input differs from captured full parent or actual provider PDF')
    expected_map = [(row['providerPageIndex'], row['providerInputPageNo'], row['originalPhysicalPageNo'])
                    for row in selected['pageMap']]
    mappings = manifest.get('pageMappings')
    if (not isinstance(mappings, list) or any(not isinstance(row, dict) for row in mappings)
            or [(row.get('parserPageIndex'), row.get('providerInputPageNo'), row.get('physicalPageNo'))
                for row in mappings] != expected_map
            or any(any(type(row.get(k)) is not int for k in
                ('parserPageIndex', 'providerInputPageNo', 'physicalPageNo')) for row in mappings)):
        raise ParserEvidenceError('Selected capture must map every complete input page to its exact parent physical page')
    return {'schema': 'consense.selected-source-capture-binding.v1', **validation,
            'selectionArtifactName': binding['artifactName'], 'pageMap': copy.deepcopy(selected['pageMap']),
            'producer': copy.deepcopy(selected['producer'])}


def _selected_raster_binding(manifest, original, artifacts, store, discovered, rasters):
    from selected_source_raster import validate_selected_raster, _load
    from pdf_selected_source import SelectedSourceError
    binding = manifest['selectedRasterSource']
    keys = {'selectionArtifactName', 'selectionManifestSha256', 'rasterArtifactName', 'rasterManifestSha256'}
    if (not isinstance(binding, dict) or set(binding) != keys or
            discovered != [binding.get('selectionArtifactName')] or
            rasters != [binding.get('rasterArtifactName')] or
            any(binding[k] not in manifest.get('requiredArtifacts', []) for k in
                ('selectionArtifactName', 'rasterArtifactName'))):
        raise ParserEvidenceError('Selected PNG lineage requires its complete captured selection/raster inventory')
    try:
        selection_asset = artifacts[binding['selectionArtifactName']]
        raster_asset = artifacts[binding['rasterArtifactName']]
        selected = _load(store.read_asset(selection_asset))
        raster = _load(store.read_asset(raster_asset))
        validation = validate_selected_raster(raster, store)
    except (SelectedSourceError, ValueError, OSError, UnicodeError, KeyError, TypeError) as error:
        raise ParserEvidenceError('Selected PNG actual CAS lineage replay failed: ' + str(error)) from error
    parent, input_raster = selected['parentSource'], raster['rasterInput']
    source, provider = manifest['source'], manifest.get('providerInputIdentity', {})
    name = provider.get('inputArtifactName')
    if (binding['selectionManifestSha256'] != selected['manifestSha256'] or
            binding['rasterManifestSha256'] != raster['manifestSha256'] or
            raster['selectionManifestSha256'] != selected['manifestSha256'] or
            any(selection_asset.get(k) != raster['selectionManifestArtifact'].get(k) for k in ('sha256', 'byteCount')) or
            any(original.get(k) != parent['artifactDescriptor'].get(k) for k in ('sha256', 'byteCount')) or
            any(source.get(k) != parent[k] for k in ('sourceFilename', 'sourceSha256', 'pageCount', 'mediaType')) or
            source.get('sourceRevisionSha256', source.get('sourceSha256')) != parent['sourceRevisionSha256'] or
            type(source.get('pageCount')) is not int or provider.get('mediaType') != 'image/png' or
            type(provider.get('pageCount')) is not int or provider['pageCount'] != 1 or
            provider.get('inputSha256') != input_raster['sha256'] or
            provider.get('pageIndexMapMeaning') != 'provider_input_document' or name not in artifacts or
            any(artifacts[name].get(k) != input_raster['artifactDescriptor'].get(k) for k in ('sha256', 'byteCount'))):
        raise ParserEvidenceError('Selected PNG capture differs from actual full parent, selection or raster input')
    mappings = manifest.get('pageMappings')
    if (not isinstance(mappings, list) or len(mappings) != 1 or not isinstance(mappings[0], dict) or
            any(type(mappings[0].get(k)) is not int for k in
                ('parserPageIndex', 'providerInputPageNo', 'physicalPageNo', 'width', 'height')) or
            mappings[0]['parserPageIndex'] != 0 or mappings[0]['providerInputPageNo'] != 1 or
            mappings[0]['physicalPageNo'] != raster['originalPhysicalPageNo'] or
            mappings[0]['width'] != input_raster['width'] or mappings[0]['height'] != input_raster['height'] or
            mappings[0].get('coordinateUnit') != 'pixel' or mappings[0].get('providerBboxFrame') != 'raster' or
            mappings[0].get('inputArtifactName') != name):
        raise ParserEvidenceError('Selected PNG must retain its actual independent page/index/frame and original physical page')
    return {'schema': 'consense.selected-raster-source-capture-binding.v1', **validation,
            'selectionArtifactName': binding['selectionArtifactName'], 'rasterArtifactName': binding['rasterArtifactName'],
            'pageMap': copy.deepcopy(selected['pageMap']), 'providerInputKind': 'selected_full_page_png',
            'producer': copy.deepcopy(selected['producer']), 'rasterProducer': copy.deepcopy(raster['producer'])}


def verify_selected_capture_binding(run, artifact_store):
    """Fresh selection CAS is required for material admission as well as replay."""
    from structured_parser_evidence import _source
    provenance = run.get('fileCaptureProvenance', {})
    manifest = provenance.get('manifest')
    if not isinstance(manifest, dict) or not ('selectedSource' in manifest or 'selectedRasterSource' in manifest):
        raise ParserEvidenceError('Selected-source marker cannot be separated from its captured manifest')
    if provenance != _file_capture_provenance(manifest):
        raise ParserEvidenceError('Selected-source capture provenance differs from the full manifest')
    if run.get('source') != _source(manifest.get('source', {})) or run.get('requestPageMap') != manifest.get('pageMappings'):
        raise ParserEvidenceError('Selected run parent or page map differs from its captured manifest')
    provider = manifest.get('providerInputIdentity', {})
    if any(run.get('providerInputIdentity', {}).get(key) != provider.get(key) for key in
           ('inputSha256', 'inputArtifactName', 'pageCount', 'mediaType', 'pageIndexMapMeaning')):
        raise ParserEvidenceError('Selected run provider identity differs from its captured manifest')
    assets = {name: record.get('artifactDescriptor') for name, record in run.get('artifacts', {}).items()}
    source_name = manifest.get('sourceArtifact', {}).get('artifactName')
    if source_name not in assets:
        raise ParserEvidenceError('Selected capture parent CAS is unavailable')
    expected = _selected_source_binding(manifest, assets[source_name], assets, artifact_store)
    if run.get('selectedSourceProvenance') != expected:
        raise ParserEvidenceError('Selected-source provenance was removed or differs from actual CAS replay')
    return expected


def _file_capture_provenance(manifest):
    return {'schema': CAPTURE_SCHEMA, 'manifest': copy.deepcopy(manifest),
        'manifestSha256': canonical_digest(manifest),
        'manifestIdentityScope': 'canonical_capture_manifest_object',
        'vendorRawBytesReserialized': False, 'parserExecutedDuringCapture': False,
        'captureLimitations': copy.deepcopy(manifest.get('captureLimitations', []))}


def rebuild_file_capture(run, artifact_store):
    """Replay a file capture from actual CAS bytes; host paths remain provenance.

    Capture-time file paths are never reopened or repaired. Every supplied
    manifest binding must match a freshly read CAS descriptor, and normalized
    observations and complete capture metadata must reproduce the original run.
    """
    if not isinstance(artifact_store, ArtifactStore) or not isinstance(run, dict):
        raise ParserEvidenceError('File capture replay requires an actual ArtifactStore and run')
    provenance = run.get('fileCaptureProvenance')
    manifest = provenance.get('manifest') if isinstance(provenance, dict) else None
    if not isinstance(manifest, dict) or manifest.get('schema') != CAPTURE_SCHEMA:
        raise ParserEvidenceError('File capture replay requires its complete capture manifest')
    if provenance != _file_capture_provenance(manifest):
        raise ParserEvidenceError('File capture provenance differs from its manifest')
    if run.get('runContentSha256') != canonical_digest({k: v for k, v in run.items() if k != 'runContentSha256'}):
        raise ParserEvidenceError('File captured run changed after capture')

    def bound(spec, record):
        if (not isinstance(spec, dict) or not isinstance(spec.get('path'), str) or not spec['path']
                or not isinstance(spec.get('sha256'), str) or not re.fullmatch(r'[0-9a-f]{64}', spec['sha256'])
                or type(spec.get('byteCount')) is not int or spec['byteCount'] < 0
                or not isinstance(spec.get('captureScope'), str) or not spec['captureScope']):
            raise ParserEvidenceError('File capture replay requires every pinned file identity and scope')
        descriptor = record.get('artifactDescriptor') if isinstance(record, dict) else None
        raw = artifact_store.read_asset(descriptor)
        if len(raw) != spec['byteCount'] or hashlib.sha256(raw).hexdigest() != spec['sha256']:
            raise ParserEvidenceError('Capture manifest differs from actual CAS file bytes')
        return copy.deepcopy(descriptor)

    original = manifest.get('sourceArtifact')
    source_name = original.get('artifactName') if isinstance(original, dict) else None
    source = manifest.get('source')
    outputs, assets = manifest.get('outputs', {}), manifest.get('assets', {})
    required = manifest.get('requiredArtifacts')
    if (not isinstance(source_name, str) or not source_name or not isinstance(source, dict)
            or not isinstance(outputs, dict) or not isinstance(assets, dict)
            or not isinstance(required, list) or source_name not in required
            or set(run.get('rawOutputs', {})) != set(outputs)
            or set(run.get('artifacts', {})) != set(assets) | {source_name}):
        raise ParserEvidenceError('Capture manifest inventory or original source binding differs')
    captured_original = bound(original, run['artifacts'][source_name])
    if captured_original['sha256'] != source.get('sourceSha256'):
        raise ParserEvidenceError('Capture original CAS bytes disagree with source identity')
    captured_outputs = {name: bound(spec, run['rawOutputs'][name]) for name, spec in outputs.items()}
    captured_assets = {name: bound(spec, run['artifacts'][name]) for name, spec in assets.items()}
    captured_assets[source_name] = captured_original
    selection = _selected_source_binding(manifest, captured_original, captured_assets, artifact_store)
    rebuilt = normalize_parser_run(manifest.get('parser'), captured_outputs, source, manifest.get('identity'),
        manifest.get('pageMappings'), artifacts=captured_assets, required_artifacts=required,
        provider_input_identity=manifest.get('providerInputIdentity'), terminal=manifest.get('terminal'),
        capture_issues=manifest.get('captureIssues'), artifact_store=artifact_store)
    if selection is not None:
        rebuilt['selectedSourceProvenance'] = selection
    rebuilt['fileCaptureProvenance'] = _file_capture_provenance(manifest)
    if selection is not None:
        rebuilt['completeness'] = _run_completeness(rebuilt, artifact_store)
    rebuilt['runContentSha256'] = canonical_digest({k: v for k, v in rebuilt.items() if k != 'runContentSha256'})
    if rebuilt != run:
        raise ParserEvidenceError('File captured observation does not reproduce from actual CAS and manifest')
    return rebuilt


def _batch_parts(children, *, replay_aggregation=None):
    if not isinstance(children, list) or not children:
        raise ParserEvidenceError('An independent capture batch requires child runs')
    source, identity, parser = children[0]['source'], children[0]['identity'], children[0]['parser']
    seen = set()
    for child in children:
        if child.get('schema') != SCHEMA or child.get('kind') != 'parser_run' or child.get('independentInputCaptures') is not None:
            raise ParserEvidenceError('Independent batches accept single normalized runs, not nested batches')
        if child.get('runContentSha256') != canonical_digest({k: v for k, v in child.items() if k != 'runContentSha256'}):
            raise ParserEvidenceError('Captured child run immutable hash changed')
        if child['source'] != source or child['parser'] != parser:
            raise ParserEvidenceError('Independent captures must bind the same original source and parser')
        if child['identity'] != identity:
            raise ParserEvidenceError('Independent captures cannot mix model/runtime/config identities')
        if len(child['requestPageMap']) != 1 or len(child['pages']) != 1:
            raise ParserEvidenceError('Batch children must each be a captured one-page input')
        page, mapping = child['pages'][0], child['requestPageMap'][0]
        physical = mapping['physicalPageNo']
        if physical in seen:
            raise ParserEvidenceError('Independent capture batch contains duplicate physical pages')
        seen.add(physical)
        input_identity = child.get('providerInputIdentity', {})
        if input_identity.get('mediaType') not in ('image/png', 'image/jpeg', 'image/tiff', 'image/webp') or (
                input_identity.get('pageCount') != 1 or mapping.get('providerInputPageNo') != 1 or
                page.get('providerInputPageNo') != 1 or
                page.get('parserPageIndex') != mapping.get('parserPageIndex')):
            raise ParserEvidenceError('Batch must preserve actual one-page provider image inputs and indexes')
    aggregation = {'version': AGGREGATION_VERSION,
        'kind': 'logical_capture_batch_of_independent_inputs',
        'childContentSha256': [child['runContentSha256'] for child in children],
        'physicalPageOrder': [child['pages'][0]['physicalPageNo'] for child in children]}
    if replay_aggregation is None:
        aggregation['codeSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    else:
        # Producer byte identity is retained provenance, not the current reader
        # implementation or an authenticity certificate. All derivable fields
        # still come from the freshly reconstructed children of a known recipe.
        if (not isinstance(replay_aggregation, dict)
                or set(replay_aggregation) != set(aggregation) | {'codeSha256'}
                or any(replay_aggregation[key] != value for key, value in aggregation.items())
                or not isinstance(replay_aggregation.get('codeSha256'), str)
                or not re.fullmatch(r'[0-9a-f]{64}', replay_aggregation['codeSha256'])
                or any(type(page) is not int for page in replay_aggregation['physicalPageOrder'])):
            raise ParserEvidenceError('Unsupported or inconsistent retained aggregation identity')
        aggregation['codeSha256'] = replay_aggregation['codeSha256']
    run_id = parser + ':batch:' + canonical_digest({'source': source, 'identity': identity, 'aggregation': aggregation})
    raw, assets, pages, maps, aliases, inputs, required = {}, {}, [], [], [], [], []
    for index, child in enumerate(children):
        namespace = 'capture_' + str(index).zfill(4)
        raw_alias = {name: namespace + '__' + name for name in child['rawOutputs']}
        asset_alias = {name: 'capture/' + str(index).zfill(4) + '/' + name for name in child['artifacts']}
        raw.update({raw_alias[name]: copy.deepcopy(record) for name, record in child['rawOutputs'].items()})
        assets.update({asset_alias[name]: copy.deepcopy(record) for name, record in child['artifacts'].items()})
        required.extend(asset_alias[name] for name in child.get('requiredArtifacts', []) or [])
        page = copy.deepcopy(child['pages'][0])
        old_page = page['pageId']
        page_id = run_id + '/page/' + str(page['physicalPageNo'])
        block_alias = {block['blockId']: page_id + '/block/' + str(n) for n, block in enumerate(page['blocks'])}
        page.update(pageId=page_id, parserPageIndex=index)
        page['captureBatchIndexMeaning'] = 'caller_batch_ordinal_not_vendor_document_index'
        page['childCaptureProvenance'] = {'runId': child['runId'], 'pageId': old_page,
                                       'parserPageIndex': child['pages'][0]['parserPageIndex']}
        page['provenance']['output'] = raw_alias[page['provenance']['output']]
        mapping = page['mapping']
        mapping['parserPageIndex'] = index
        mapping['childParserPageIndex'] = child['requestPageMap'][0]['parserPageIndex']
        mapping['inputArtifactName'] = asset_alias[mapping['inputArtifactName']]
        transform = mapping.get('originalPageTransform')
        if isinstance(transform, dict) and isinstance(transform.get('inputFrame'), dict):
            name = transform['inputFrame'].get('artifactName')
            if name in asset_alias:
                transform['inputFrame']['artifactName'] = asset_alias[name]
        if isinstance(page.get('inputArtifact'), dict):
            page['inputArtifact']['name'] = asset_alias[page['inputArtifact']['name']]
        for block in page['blocks']:
            old = block['blockId']
            block['blockId'] = block_alias[old]
            block['childBlockId'] = old
            block['provenance']['output'] = raw_alias[block['provenance']['output']]
            if 'table' in block:
                table = block['table']
                table['tableGroupId'] = block['blockId'] + '/table'
                table['provenance']['output'] = raw_alias[table['provenance']['output']]
                for n, cell in enumerate(table['cells']):
                    cell['childCellId'] = cell['cellId']
                    cell['cellId'] = block['blockId'] + '/cell/' + str(n)
                    cell['provenance']['output'] = raw_alias[cell['provenance']['output']]
        pages.append(page)
        maps.append(copy.deepcopy(mapping))
        local_input = copy.deepcopy(child['providerInputIdentity'])
        local_input['inputArtifactName'] = asset_alias[local_input['inputArtifactName']]
        inputs.append({'batchParserPageIndex': index, 'childRunId': child['runId'],
                       'inputIdentity': local_input, 'physicalPageNo': page['physicalPageNo']})
        aliases.append({'namespace': namespace, 'childRunId': child['runId'],
            'childRunContentSha256': child['runContentSha256'], 'rawOutputAliases': raw_alias,
            'artifactAliases': asset_alias, 'childParserPageIndex': child['pages'][0]['parserPageIndex'],
            'batchParserPageIndex': index, 'physicalPageNo': page['physicalPageNo'],
            'childPageId': old_page, 'batchPageId': page_id, 'blockAliases': block_alias})
    failed = any(child.get('terminal', {}).get('status') in ('failed', 'cancelled') for child in children)
    terminal = {'status': 'failed' if failed else 'completed' if all(
                child.get('terminal', {}).get('status') == 'completed' for child in children) else 'unknown',
                'pageResults': [{'parserPageIndex': index,
                    'status': child.get('terminal', {}).get('pageResults', [{}])[0].get('status', 'unknown')
                        if child.get('terminal', {}).get('pageResults') else 'unknown',
                    'childRunId': child['runId']} for index, child in enumerate(children)]}
    provider = {'kind': 'independent_one_page_image_inputs', 'captureStatus': 'actual_input_bytes_captured',
                'pageIndexMapMeaning': 'each_child_provider_input_document', 'inputs': inputs,
                'notASingleProviderDocument': True}
    return {'runId': run_id, 'source': copy.deepcopy(source), 'identity': copy.deepcopy(identity), 'parser': parser,
            'rawOutputs': raw, 'artifacts': assets, 'pages': pages, 'requestPageMap': maps,
            'providerInputIdentity': provider, 'requiredArtifacts': required,
            'terminal': terminal, 'aggregationIdentity': aggregation, 'captureAliasMap': aliases}


def verify_independent_capture_batch(run, artifact_store):
    """Recompute every alias/frame/identity from immutable child captures."""
    children = run.get('independentInputCaptures')
    aggregation = run.get('aggregationIdentity')
    if not isinstance(aggregation, dict):
        raise ParserEvidenceError('Independent batch replay requires its retained aggregation identity')
    expected = _batch_parts(children, replay_aggregation=aggregation)
    if any(run.get(key) != value for key, value in expected.items()):
        raise ParserEvidenceError('Independent batch alias/identity/page mapping is not recomputable from its children')
    if any(child.get('captureManifest') != _capture_manifest(child) for child in children):
        raise ParserEvidenceError('Independent child source/parser/config/asset manifest changed')
    completeness = [_run_completeness(child, artifact_store) for child in children]
    reasons = [child['runId'] + ':' + reason for child, result in zip(children, completeness) for reason in result['reasons']]
    eligible = not reasons
    material = all(result['materialStatus'] == 'complete' for result in completeness)
    failed = any(result['terminalStatus'] == 'failed' for result in completeness)
    return {'status': 'complete' if eligible else 'incomplete' if not material or failed else 'unverified',
        'materialStatus': 'complete' if material else 'incomplete',
        'terminalStatus': 'failed' if failed else 'verified' if eligible else 'unverified',
        'eligibleForCanonical': eligible, 'reasons': reasons,
        'requestedParserPageIndices': [page['parserPageIndex'] for page in expected['requestPageMap']],
        'observedParserPageIndices': [page['parserPageIndex'] for page in expected['pages']],
        'requestedPhysicalPages': sorted(page['physicalPageNo'] for page in expected['pages']),
        'independentChildCompleteness': completeness, 'oneProviderDocumentClaimed': False}


def _combine_parser_captures(runs, artifact_store, *, replay_aggregation=None):
    children = copy.deepcopy(runs)
    result = {'schema': SCHEMA, 'kind': 'parser_run', **_batch_parts(children, replay_aggregation=replay_aggregation),
        'independentInputCaptures': children, 'captureIssues': None,
        'captureStatus': 'supplied_outputs_and_assets_captured',
        'issues': [{'code': 'independent_child_capture_issue', 'childRunId': child['runId'],
                    'issue': copy.deepcopy(issue), 'status': 'unknown'} for child in children for issue in child['issues']],
        'qualityStatus': 'needs_review', 'humanConfirmed': False, 'canonicalSelection': None,
        'limitations': ['Logical batch of independently captured one-page image calls; no synthetic vendor JSON',
            'Batch parserPageIndex is caller ordering; original provider indexes remain in each child',
            'Only requested physical pages are captured; this is not full-source-library acceptance',
            'Semantic, blank, header and cross-page relationships require independent source review']}
    result['captureManifest'] = _capture_manifest(result)
    result['completeness'] = verify_independent_capture_batch(result, artifact_store)
    if not result['completeness']['eligibleForCanonical']:
        result['captureStatus'] = 'incomplete'
    result['runContentSha256'] = canonical_digest(result)
    return result


def combine_parser_captures(runs, artifact_store):
    """Make a new logical batch with the current producer's byte identity."""
    return _combine_parser_captures(runs, artifact_store)


def rebuild_independent_capture_batch(run, rebuilt_children, artifact_store):
    """Replay a known batch after its consumer cold-reconstructed every child.

    Preserve the stored opaque producer identity without executing old code or
    authenticating historical provenance. Recompute all aliases, metadata and
    completeness with this reader, require intact seals and full object equality.
    """
    if (not isinstance(run, dict) or not isinstance(artifact_store, ArtifactStore)
            or not isinstance(run.get('aggregationIdentity'), dict)
            or not isinstance(rebuilt_children, list)
            or rebuilt_children != run.get('independentInputCaptures')
            or run.get('runContentSha256') != canonical_digest({k: v for k, v in run.items() if k != 'runContentSha256'})):
        raise ParserEvidenceError('Independent batch replay requires intact parent and reconstructed children')
    rebuilt = _combine_parser_captures(rebuilt_children, artifact_store,
                                      replay_aggregation=run['aggregationIdentity'])
    if rebuilt != run:
        raise ParserEvidenceError('Independent batch does not reproduce from its reconstructed children and retained identity')
    return rebuilt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest')
    parser.add_argument('--store', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    manifest_path = Path(args.manifest).resolve()
    value = json.loads(manifest_path.read_text(encoding='utf-8'))
    store = ArtifactStore(args.store)
    run, receipt = capture_parser_files(value, store, base_directory=manifest_path.parent)
    save_evidence_bundle(run, args.output, artifact_store=store)
    print(json.dumps(receipt, ensure_ascii=False, sort_keys=True))


if __name__ == '__main__':
    main()
