"""Narrow selected-PDF to full-page RGB PNG lineage. No model or adoption.

Cold readers use known current code, actual CAS and exact installed package
bytes. Retained producer code is mandatory CAS provenance, never imported,
executed or authenticated as the current reader. Known-recipe RGB replay proves
the material transformation, not which producer historically ran. It does not
certify reference-raster geometry, reading order or legal meaning.
"""
from pathlib import Path
from io import BytesIO
from importlib import metadata
import copy
import json

from artifact_store import ArtifactStore
from pdf_selected_source import (SelectedSourceError, _require, _descriptor, _sha,
    canonical_bytes, digest, validate_selected_source)

SCHEMA = 'consense.selected-pdf-full-page-raster.v1'
RECIPE = {'version': 'pypdfium2-full-selected-page-300dpi-rgb-forms-v1',
    'library': 'pypdfium2', 'dpi': 300, 'scale': 300 / 72,
    'initForms': 'before_document_len_and_page_open', 'drawAnnots': True,
    'mayDrawForms': True, 'pageExtent': 'complete_page', 'crop': None,
    'extraRotation': 0, 'mode': 'RGB', 'comparison': 'complete_decoded_rgb_bytes'}


def _versions():
    return {name: metadata.version(name) for name in ('pypdfium2', 'Pillow')}


def _dependencies():
    import PIL
    import pypdfium2
    import pypdfium2_raw
    rows = []
    for name, module in (('PIL', PIL), ('pypdfium2', pypdfium2), ('pypdfium2_raw', pypdfium2_raw)):
        root = Path(module.__file__).resolve().parent
        for path in sorted(root.rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
                rows.append((name + '/' + path.relative_to(root).as_posix(), path.read_bytes()))
    # Native Pillow wheels may carry bundled codec dependencies adjacent to PIL.
    root = Path(PIL.__file__).resolve().parent.parent
    for directory in sorted(root.glob('pillow.libs')):
        for path in sorted(directory.rglob('*')):
            if path.is_file():
                rows.append(('pillow.libs/' + path.relative_to(directory).as_posix(), path.read_bytes()))
    return rows


def _render(selected_raw, provider_page_no, page_count):
    import pypdfium2 as pdfium
    document = pdfium.PdfDocument(selected_raw)
    try:
        document.init_forms()
        _require(len(document) == page_count, 'Actual selected PDF count differs during raster replay')
        page = document[provider_page_no - 1]
        try:
            width, height = page.get_size()
            frame = {'widthPt': width, 'heightPt': height, 'rotationDegrees': page.get_rotation()}
            bitmap = page.render(scale=RECIPE['scale'], draw_annots=True, may_draw_forms=True)
            try:
                image = bitmap.to_pil().convert('RGB')
                try:
                    return image.size, image.tobytes(), frame
                finally:
                    image.close()
            finally:
                bitmap.close()
        finally:
            page.close()
    finally:
        document.close()


def _png(raw):
    from PIL import Image
    with Image.open(BytesIO(raw)) as image:
        _require(image.format == 'PNG' and image.n_frames == 1 and image.mode == 'RGB',
                 'Selected raster must be one actual complete RGB PNG')
        image.load()
        return image.size, image.tobytes()


def _load(raw):
    def unique(items):
        out = {}
        for key, value in items:
            _require(key not in out, 'Duplicate selected/raster JSON key')
            out[key] = value
        return out
    return json.loads(raw.decode('utf-8'), object_pairs_hook=unique,
                      parse_constant=lambda v: (_ for _ in ()).throw(ValueError(v)))


def validate_selected_raster(manifest, store):
    _require(isinstance(store, ArtifactStore), 'Actual raster CAS required')
    keys = {'schema', 'selectionManifestArtifact', 'selectionManifestSha256',
            'selectedProviderPageNo', 'originalPhysicalPageNo', 'rasterInput',
            'renderRecipe', 'sourceFrame', 'producer', 'limitations', 'qualityAccepted', 'manifestSha256'}
    _require(isinstance(manifest, dict) and set(manifest) == keys and manifest['schema'] == SCHEMA,
             'Unknown or incomplete selected-raster manifest')
    _require(manifest['manifestSha256'] == digest({k: v for k, v in manifest.items() if k != 'manifestSha256'})
             and manifest['qualityAccepted'] is False and manifest['renderRecipe'] == RECIPE,
             'Selected raster seal/recipe/false quality claim differs')
    selection_raw = store.read_asset(manifest['selectionManifestArtifact'])
    selection = _load(selection_raw)
    report = validate_selected_source(selection, store)
    _require(selection['manifestSha256'] == manifest['selectionManifestSha256'],
             'Raster selection CAS differs from declared selection identity')
    number = manifest['selectedProviderPageNo']
    _require(type(number) is int and 1 <= number <= selection['providerInput']['pageCount'],
             'Actual selected input physical page required')
    row = selection['pageMap'][number - 1]
    _require(type(manifest['originalPhysicalPageNo']) is int and
             manifest['originalPhysicalPageNo'] == row['originalPhysicalPageNo'],
             'Raster selected page must map to its exact original physical page')
    producer = manifest['producer']
    _require(isinstance(producer, dict) and set(producer) ==
             {'codeArtifact', 'libraryVersions', 'dependencyFiles', 'dependencyInventorySha256'},
             'Exact known raster producer/dependencies required')
    _require(producer['libraryVersions'] == _versions(), 'Installed raster package versions differ')
    # Verify the retained bytes without executing or equating them to this
    # reader. New readers may replay old producer material; historical execution
    # authentication requires separate evidence and is not asserted here.
    store.read_asset(producer['codeArtifact'])
    dependencies = producer['dependencyFiles']
    _require(isinstance(dependencies, list) and bool(dependencies)
             and producer['dependencyInventorySha256'] == digest(dependencies),
             'Raster dependency inventory seal differs')
    observed = []
    for dep in dependencies:
        _require(isinstance(dep, dict) and set(dep) ==
                 {'relativePath', 'sha256', 'byteCount', 'artifactDescriptor'}, 'Unknown raster dependency record')
        raw = store.read_asset(dep['artifactDescriptor'])
        _require(_sha(raw) == dep['sha256'] and len(raw) == dep['byteCount'], 'Captured raster dependency differs')
        observed.append((dep['relativePath'], dep['sha256'], dep['byteCount']))
    _require(observed == [(name, _sha(raw), len(raw)) for name, raw in _dependencies()],
             'Exact installed raster package source/native dependency bytes differ')
    raster = manifest['rasterInput']
    _require(isinstance(raster, dict) and set(raster) == {'artifactDescriptor', 'sha256', 'byteCount',
             'width', 'height', 'mediaType', 'pageCount', 'mode', 'rgbPixelSha256'}, 'Actual complete raster identity required')
    _descriptor(raster['artifactDescriptor'], raster['sha256'], raster['byteCount'], 'raster')
    raw = store.read_asset(raster['artifactDescriptor'])
    _require(raster['mediaType'] == 'image/png' and raster['mode'] == 'RGB'
             and type(raster['pageCount']) is int and raster['pageCount'] == 1
             and type(raster['width']) is int and raster['width'] > 0
             and type(raster['height']) is int and raster['height'] > 0, 'One full provider PNG required')
    size, pixels = _png(raw)
    _require(size == (raster['width'], raster['height']) and _sha(pixels) == raster['rgbPixelSha256'],
             'Actual complete PNG pixels differ from declared raster')
    rendered_size, rendered, frame = _render(store.read_asset(selection['providerInput']['artifactDescriptor']),
                                             number, selection['providerInput']['pageCount'])
    _require(rendered_size == size and rendered == pixels and frame == manifest['sourceFrame'],
             'Raster is not the exact full selected PDF page under the pinned render recipe')
    return {'schema': 'consense.selected-raster-source-validation.v1', **report,
            'rasterManifestSha256': manifest['manifestSha256'], 'selectedProviderPageNo': number,
            'originalPhysicalPageNo': row['originalPhysicalPageNo'], 'inputRasterSha256': raster['sha256'],
            'rasterRgbPixelSha256': raster['rgbPixelSha256'], 'fullRasterReplay': 'verified_complete_rgb',
            'producerCodeProvenanceStatus': 'retained_cas_provenance_not_current_reader_authentication',
            'producerCodeSha256': producer['codeArtifact']['sha256'],
            'historicalProducerExecutionAuthenticated': False, 'producerCodeExecutedByReader': False,
            'geometryStatus': 'unknown', 'qualityAccepted': False}


def create_selected_raster(selection, provider_page_no, png_path, output_directory, store):
    """Capture existing actual PNG with fresh producer proof; no model invocation."""
    output = Path(output_directory)
    _require(not output.exists(), 'Selected raster output must be a new owned directory')
    validate_selected_source(selection, store)
    _require(type(provider_page_no) is int and 1 <= provider_page_no <= selection['providerInput']['pageCount'],
             'Valid selected-provider physical page required')
    png_path = Path(png_path)
    before = (png_path.read_bytes(), png_path.stat().st_mtime_ns)
    size, pixels = _png(before[0])
    _, _, frame = _render(store.read_asset(selection['providerInput']['artifactDescriptor']),
                          provider_page_no, selection['providerInput']['pageCount'])
    dependencies = [{'relativePath': name, 'sha256': _sha(raw), 'byteCount': len(raw),
                     'artifactDescriptor': store.put_bytes(raw, identity_scope='exact_raster_package_source_native_bytes')}
                    for name, raw in _dependencies()]
    raster = store.put_bytes(before[0], identity_scope='exact_actual_full_page_png_input_bytes')
    manifest = {'schema': SCHEMA,
        'selectionManifestArtifact': store.put_bytes(canonical_bytes(selection), identity_scope='canonical_selected_source_manifest'),
        'selectionManifestSha256': selection['manifestSha256'], 'selectedProviderPageNo': provider_page_no,
        'originalPhysicalPageNo': selection['pageMap'][provider_page_no - 1]['originalPhysicalPageNo'],
        'rasterInput': {'artifactDescriptor': raster, 'sha256': raster['sha256'], 'byteCount': raster['byteCount'],
             'width': size[0], 'height': size[1], 'mediaType': 'image/png', 'pageCount': 1, 'mode': 'RGB',
             'rgbPixelSha256': _sha(pixels)}, 'sourceFrame': frame, 'renderRecipe': copy.deepcopy(RECIPE),
        'producer': {'codeArtifact': store.put_file(Path(__file__)), 'libraryVersions': _versions(),
             'dependencyFiles': dependencies, 'dependencyInventorySha256': digest(dependencies)},
        'limitations': ['Complete RGB replay proves this PNG derives from its selected full page',
             'Retained producer code is CAS provenance, not historical execution or current reader authentication',
             'Reference raster mapping, semantic correctness and legal adoption remain unknown',
             'Package source/native bytes pinned; external OS ABI is not a hermetic image'], 'qualityAccepted': False}
    manifest['manifestSha256'] = digest(manifest)
    validate_selected_raster(manifest, store)
    _require(before == (png_path.read_bytes(), png_path.stat().st_mtime_ns), 'Existing actual PNG changed during capture')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'selected-raster-manifest.json').write_bytes(canonical_bytes(manifest))
    return manifest
