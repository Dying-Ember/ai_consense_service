"""Load OCR dependencies and describe cache identity without starting inference."""
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import platform
import sys

_DLL_HANDLES = []
OCR_CACHE_SCHEMA = 2
# This exact installed/default Rec weight's embedded character metadata was
# observed in the completed CPU OCR experiment. Other offline Rec models must
# provide a local dictionary; preflight does not create an ONNX session.
KNOWN_EMBEDDED_REC_SHAS = {'6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884'}


def ocr_parameters(*, max_side_len=2000, text_score=0.5, box_thresh=0.5,
                   intra_threads=4, inter_threads=1):
    """Validate actual CPU settings; ORT silently ignores out-of-range threads."""
    if isinstance(max_side_len, bool) or not isinstance(max_side_len, int) or max_side_len < 32:
        raise ValueError('OCR max-side-len must be an integer of at least 32')
    for name, value in (('text-score', text_score), ('box-thresh', box_thresh)):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1:
            raise ValueError(f'OCR {name} must be finite and between 0 and 1')
    for name, value in (('intra-threads', intra_threads), ('inter-threads', inter_threads)):
        if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= (os.cpu_count() or 1):
            raise ValueError(f'OCR {name} must be an integer between 1 and the CPU count')
    return {'Global.max_side_len': max_side_len, 'Global.text_score': float(text_score),
            'Det.box_thresh': float(box_thresh),
            'EngineConfig.onnxruntime.intra_op_num_threads': intra_threads,
            'EngineConfig.onnxruntime.inter_op_num_threads': inter_threads}


def content_identity(path):
    path = Path(path)
    hasher = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(block)
    return {'bytes': path.stat().st_size, 'sha256': hasher.hexdigest()}


def identity_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                    ensure_ascii=False, allow_nan=False).encode('utf-8')).hexdigest()


def package_identity(name):
    """Bind installed versions and wheel manifests without importing native modules."""
    try:
        distribution = importlib.metadata.distribution(name)
    except importlib.metadata.PackageNotFoundError:
        return {'version': None, 'installed': False}
    record = distribution.read_text('RECORD')
    metadata = distribution.read_text('METADATA')
    return {'installed': True, 'version': distribution.version,
            'recordSha256': hashlib.sha256(record.encode('utf-8')).hexdigest() if record is not None else None,
            'metadataSha256': hashlib.sha256(metadata.encode('utf-8')).hexdigest() if metadata is not None else None}


def rapidocr_configuration():
    """Read the pinned library's configuration/routing; no RapidOCR import."""
    import yaml
    distribution = importlib.metadata.distribution('rapidocr')
    root = Path(distribution.locate_file('rapidocr'))
    config_path, routing_path = root / 'config.yaml', root / 'default_models.yaml'
    config = yaml.safe_load(config_path.read_text(encoding='utf-8'))
    routing = yaml.safe_load(routing_path.read_text(encoding='utf-8'))
    return root, config, routing


def configured_model_info(task, routing):
    """Resolve the pinned v6 multi-language route and legacy prefix routes.

    RapidOCR 3.9.2 routes v6 Det/Rec's configured ``ch`` language to
    ``multi_PP-OCRv6_*`` keys, not keys beginning with ``ch``.
    Unsupported/ambiguous future routes fail rather than guessing a weight.
    """
    choices = routing[task['engine_type']][task['ocr_version']][task['task_type']]
    if task['ocr_version'] == 'PP-OCRv6' and task['task_type'] in {'det', 'rec'}:
        key = f"multi_PP-OCRv6_{task['task_type']}_{task['model_type']}"
        if key not in choices:
            raise ValueError(f'Unsupported OCR model route: {key}')
        return key, choices[key]
    matches = [(key, value) for key, value in choices.items()
               if key.startswith(task['lang_type']) and task['model_type'] in key]
    if len(matches) != 1:
        raise ValueError('Ambiguous OCR model routing; freeze an explicit model_path')
    return matches[0]


def selected_model_files(model_directory, config, routing):
    result = {}
    # RapidOCR initializes all three sessions even when use_* is false.
    for section in ('Det', 'Cls', 'Rec'):
        task = config[section]
        if task['engine_type'] != 'onnxruntime':
            raise ValueError('Pilot OCR cache supports the configured ONNXRuntime CPU engine only')
        if task.get('model_path'):
            path = Path(task['model_path']).expanduser()
            model_key, expected = 'explicit_local_path', None
        else:
            model_key, model = configured_model_info(task, routing)
            path = Path(model_directory) / Path(model['model_dir']).name
            expected = model.get('SHA256')
        result[section] = {'path': path, 'modelKey': model_key, 'registrySha256': expected,
            'dictionary': Path(task['rec_keys_path']).expanduser() if task.get('rec_keys_path') else None}
    return result


def local_model_paths(model_directory):
    _, config, routing = rapidocr_configuration()
    return {section: str(item['path'].resolve()) for section, item in
            selected_model_files(model_directory, config, routing).items()}


def ocr_cache_recipe(model_directory, *, require_verified_defaults=False, **settings):
    """Hash local weights, full library configuration/code and effective parameters.

    Output/model-root paths are deliberately excluded: moving identical local
    weights does not change the recipe. Versions and installed wheel RECORDs
    describe dependency identity, not a hash of every external OS DLL.
    """
    params = ocr_parameters(**settings)
    root, config, routing = rapidocr_configuration()
    if any(config['EngineConfig']['onnxruntime'].get('use_' + provider, False)
           for provider in ('cuda', 'dml', 'cann', 'coreml')):
        raise ValueError('Pilot OCR cache requires the configured CPU-only ONNXRuntime providers')
    weights = {}
    for section, selected in selected_model_files(model_directory, config, routing).items():
        path, model_key = selected['path'], selected['modelKey']
        if not path.is_file():
            raise FileNotFoundError(f'Missing local OCR weight: {path}; prepare weights before offline OCR or cache reuse')
        weights[section] = {'modelKey': model_key, 'fileName': path.name, **content_identity(path)}
        expected = selected['registrySha256']
        if expected is not None:
            weights[section]['registrySha256'] = expected
            weights[section]['matchesRegistrySha256'] = weights[section]['sha256'].lower() == expected.lower()
            if require_verified_defaults and not weights[section]['matchesRegistrySha256']:
                raise RuntimeError(f'Offline OCR rejects corrupt default weight before initialization: {path}; prepare verified local weights')
        elif require_verified_defaults and model_key != 'explicit_local_path':
            raise RuntimeError(f'Offline OCR needs a registry SHA256 for default weight: {path}')
        if selected['dictionary'] is not None:
            dictionary = selected['dictionary']
            weights[section]['dictionary'] = {'fileName': dictionary.name, **content_identity(dictionary)}
        elif require_verified_defaults and section == 'Rec' and weights[section]['sha256'] not in KNOWN_EMBEDDED_REC_SHAS:
            raise RuntimeError('Offline recognition needs an explicit local Rec.rec_keys_path unless the exact weight has previously verified embedded-character metadata; preflight does not inspect it in a session')
    # Full RapidOCR Python/config resources are hashed from actual installed bytes.
    library_files = {path.relative_to(root).as_posix(): content_identity(path)
                     for path in sorted(root.rglob('*')) if path.is_file() and
                     path.suffix in {'.py', '.yaml', '.yml', '.json', '.txt'} and 'models' not in path.relative_to(root).parts}
    packages = {name: package_identity(name) for name in ('rapidocr', 'onnxruntime', 'onnxruntime-gpu',
                'onnxruntime-directml', 'pypdfium2',
                'Pillow', 'numpy', 'opencv-python', 'opencv-python-headless', 'pyclipper',
                'omegaconf', 'PyYAML', 'torch')}
    requested = os.environ.get('CONSENSE_VC_RUNTIME_DIR')
    dll_root = Path(requested) if requested else Path(sys.base_prefix).parent / 'native/poppler/Library/bin'
    dlls = {name: content_identity(dll_root / name) if (dll_root / name).is_file() else None
            for name in ('msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll')} if os.name == 'nt' else {}
    return {'schemaVersion': OCR_CACHE_SCHEMA, 'engine': 'RapidOCR/ONNXRuntime CPU',
            'parameters': params, 'modelWeights': weights, 'rapidocrFiles': library_files,
            'selectedProvider': 'CPUExecutionProvider',
            'packages': packages, 'runtime': {'python': sys.version, 'platform': platform.platform(),
                'machine': platform.machine(), 'cpuCount': os.cpu_count(),
                'runtimeHelper': content_identity(__file__), 'selectedVcRuntimeDlls': dlls},
            'identityLimits': ['Dependency wheels bound by installed version/METADATA/RECORD; external OS DLLs are not exhaustively hashed']}


def validate_ocr_cache(data, *, recipe, source_hash, key, page, dpi, cache):
    """Reject legacy/mismatched caches; validation never upgrades stored bytes."""
    valid = (data.get('ocrCacheSchemaVersion') == OCR_CACHE_SCHEMA and
             data.get('ocrCacheSignature') == identity_hash(recipe) and data.get('ocrCacheRecipe') == recipe and
             data.get('source_hash') == source_hash and data.get('key') == key and
             data.get('page') == page and data.get('dpi') == dpi)
    if not valid:
        raise RuntimeError(f'Stale or unsigned OCR cache: {cache}; run ocr --refresh with the requested recipe. Do not hand-patch signatures.')


def create_ocr_engine(model_directory=None, *, max_side_len=2000, text_score=0.5, box_thresh=0.5,
                      intra_threads=4, inter_threads=1, local_paths=None):
    params = ocr_parameters(max_side_len=max_side_len, text_score=text_score, box_thresh=box_thresh,
                            intra_threads=intra_threads, inter_threads=inter_threads)
    if local_paths is not None:
        if set(local_paths) != {'Det', 'Cls', 'Rec'}:
            raise ValueError('Local-only OCR initialization requires Det/Cls/Rec model paths')
        for section, requested_path in local_paths.items():
            path = Path(requested_path)
            if not path.is_file():
                raise FileNotFoundError(path)
            # Bypass the library's model-root downloader, including its repair path.
            params[section + '.model_path'] = str(path.resolve())
    if os.name == 'nt':
        requested = os.environ.get('CONSENSE_VC_RUNTIME_DIR')
        candidates = [Path(requested)] if requested else [Path(sys.base_prefix).parent / 'native/poppler/Library/bin']
        for candidate in candidates:
            if candidate.is_dir() and (candidate / 'msvcp140.dll').is_file():
                _DLL_HANDLES.append(os.add_dll_directory(str(candidate.resolve())))
    import torch  # Import shared Windows runtime dependencies before pyclipper.
    try:
        import onnxruntime
        from rapidocr import RapidOCR
    except ImportError as error:
        raise RuntimeError('OCR needs the official Microsoft VC++ x64 runtime on Windows. '
                           'Set CONSENSE_VC_RUNTIME_DIR to a trusted DLL directory or install '
                           'the redistributable. See https://onnxruntime.ai/docs/install/') from error
    if model_directory:
        params['Global.model_root_dir'] = str(model_directory)
    return RapidOCR(params=params)
