"""Export pinned preparation receipts to independent, byte-verified model files.

This is a model asset export, not a complete Linux runtime/release or quality
acceptance. It makes no network requests, changes no source files, and loads no
neural models. Existing exports are never overwritten. Failed copies retain an
incomplete marker and cannot be accepted by the verification command.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re

from prepare_oer_models import MODELS, ROLE_FILES, git_blob_sha1, sha256


SCHEMA = 'consense.offline-model-assets.v1'
HASH = re.compile(r'[0-9a-f]{64}')
FILE_FIELDS = ('relativePath', 'bytes', 'sha256', 'upstreamLfsSha256',
               'upstreamGitBlobId', 'actualGitBlobId', 'upstreamVerification',
               'isCacheSymlink')


def _digest(value, label):
    if not isinstance(value, str) or not HASH.fullmatch(value):
        raise ValueError(label + ' must be lowercase SHA-256')
    return value


def _relative(value):
    if not isinstance(value, str) or not value or '\\' in value or ':' in value:
        raise ValueError('Model file requires a portable relative POSIX path')
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ('', '.', '..') for part in value.split('/')):
        raise ValueError('Model file path escapes or is not canonical')
    return value


def _file_identity(path, row):
    if type(row.get('bytes')) is not int or row['bytes'] < 0:
        raise ValueError('Model file requires an integer byte count')
    expected = _digest(row.get('sha256'), 'Model file hash')
    if not path.is_file() or path.stat().st_size != row['bytes'] or sha256(path) != expected:
        raise ValueError('Model file bytes differ from receipt: ' + row['relativePath'])
    method = row.get('upstreamVerification')
    if method == 'lfs_sha256':
        if row.get('upstreamLfsSha256') != expected:
            raise ValueError('Model file upstream LFS identity differs')
    elif method == 'git_blob_sha1':
        blob = row.get('upstreamGitBlobId')
        if not isinstance(blob, str) or not re.fullmatch(r'[0-9a-f]{40}', blob) or (
                row.get('actualGitBlobId') != blob or git_blob_sha1(path) != blob):
            raise ValueError('Model file upstream Git blob identity differs')
    else:
        raise ValueError('Model file lacks verified upstream identity')


def _model_records(value):
    if value.get('status') != 'downloaded_and_byte_verified_no_inference':
        raise ValueError('Expected a pinned model preparation receipt')
    models = value.get('models')
    if not isinstance(models, list) or not models:
        raise ValueError('Preparation receipt requires model records')
    seen = set()
    records = []
    for model in models:
        if not isinstance(model, dict):
            raise ValueError('Invalid model record')
        kind = model.get('kind')
        if not isinstance(kind, str) or kind not in MODELS or (
                (model.get('name'), model.get('revision')) != MODELS[kind]):
            raise ValueError('Preparation receipt differs from pinned model identity')
        if kind in seen:
            raise ValueError('Preparation receipt has duplicate model records')
        seen.add(kind)
        location = model.get('snapshot')
        if not isinstance(location, str) or not Path(location).is_absolute():
            raise ValueError('Preparation snapshot must be an actual absolute local path')
        source = Path(location).resolve(strict=True)
        files = model.get('files')
        if not source.is_dir() or not isinstance(files, list) or not files:
            raise ValueError('Model snapshot/files are absent')
        names, selected = set(), []
        for row in files:
            if not isinstance(row, dict):
                raise ValueError('Invalid model file record')
            relative = _relative(row.get('relativePath'))
            if relative in names:
                raise ValueError('Preparation receipt has duplicate model files')
            names.add(relative)
            selected.append(row)
        required = ROLE_FILES.get(kind, ())
        if required and names != set(required):
            raise ValueError('Model receipt differs from required repository file closure')
        if not required and not names.intersection({'model.safetensors', 'pytorch_model.bin',
                                                    'model.safetensors.index.json'}):
            raise ValueError('Model receipt has no pinned weight files')
        records.append((model, source, sorted(selected, key=lambda row: row['relativePath'])))
    return sorted(records, key=lambda row: row[0]['kind'])


def _copy_checked(source, target, expected):
    """Dereference cache links; validate exactly the bytes actually written."""
    digest, count = hashlib.sha256(), 0
    with source.open('rb') as incoming, target.open('xb') as outgoing:
        while block := incoming.read(1024 * 1024):
            outgoing.write(block)
            digest.update(block)
            count += len(block)
    if count != expected['bytes'] or digest.hexdigest() != expected['sha256']:
        raise ValueError('Model source changed while exporting: ' + expected['relativePath'])


def materialize(receipt_path, receipt_sha256, output):
    receipt_path = Path(receipt_path).resolve(strict=True)
    raw = receipt_path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != _digest(receipt_sha256, 'Preparation receipt SHA'):
        raise ValueError('Preparation receipt SHA differs')
    models = _model_records(json.loads(raw))
    output = Path(output).resolve()
    if output.exists():
        raise FileExistsError('Use a fresh export directory; existing assets are not replaced')
    for _, snapshot, files in models:
        if output.is_relative_to(snapshot):
            raise ValueError('Export cannot be inside source snapshot')
        for row in files:
            _file_identity(snapshot / row['relativePath'], row)
    output.mkdir(parents=True, exist_ok=False)
    marker = output / '.incomplete'
    marker.write_bytes(b'Model export not yet verified; not a release.\n')
    manifest = dict(schema=SCHEMA, scope='model_repository_assets_only',
        preparationReceiptSha256=receipt_sha256,
        runtimeDependencyClosureVerified=False, targetRuntimeAccepted=False,
        modelWeightLoads=0, networkRequests=0, models=[])
    for model, snapshot, files in models:
        kind = model['kind']
        exported = []
        for row in files:
            relative = 'models/' + kind + '/' + row['relativePath']
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            _copy_checked(snapshot / row['relativePath'], target, row)
            _file_identity(target, row)
            exported.append({**{key: row[key] for key in FILE_FIELDS if key in row},
                             'bundlePath': relative})
        manifest['models'].append(dict(kind=kind, name=model['name'],
            revision=model['revision'], directory='models/' + kind, files=exported))
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, sort_keys=True,
        indent=2, allow_nan=False) + '\n').encode('utf-8')
    manifest_path = output / 'manifest.json'
    with manifest_path.open('xb') as stream:
        stream.write(manifest_bytes)
    # Verification can now use only the export. No source-cache path is used.
    manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
    verify(output, manifest_sha, _allow_incomplete=True)
    marker.unlink()
    return dict(status='independent_model_files_verified_not_runtime_acceptance',
        output=str(output), manifestSha256=manifest_sha,
        modelCount=len(models), fileCount=sum(len(row[2]) for row in models))


def verify(output, manifest_sha256, *, _allow_incomplete=False):
    output = Path(output).resolve(strict=True)
    if not output.is_dir() or ((output / '.incomplete').exists() and not _allow_incomplete):
        raise ValueError('Model export is incomplete')
    raw = (output / 'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest() != _digest(manifest_sha256, 'Export manifest SHA'):
        raise ValueError('Export manifest SHA differs')
    manifest = json.loads(raw)
    if manifest.get('schema') != SCHEMA or manifest.get('scope') != 'model_repository_assets_only':
        raise ValueError('Unsupported model export manifest')
    _digest(manifest.get('preparationReceiptSha256'), 'Preparation receipt SHA')
    if manifest.get('runtimeDependencyClosureVerified') is not False or (
            manifest.get('targetRuntimeAccepted') is not False) or any(
            type(manifest.get(key)) is not int or manifest[key] != 0
            for key in ('modelWeightLoads', 'networkRequests')):
        raise ValueError('Model export scope and runtime claims are inconsistent')
    # Reuse the model receipt validator; only portable export roots are supplied.
    models = [{**model, 'snapshot': str(output / model['directory'])}
              for model in manifest.get('models', [])]
    records = _model_records(dict(status='downloaded_and_byte_verified_no_inference', models=models))
    allowed = {'manifest.json'}
    if _allow_incomplete:
        allowed.add('.incomplete')
    for model, snapshot, files in records:
        if model['directory'] != 'models/' + model['kind']:
            raise ValueError('Model directory differs from portable identity')
        for row in files:
            if set(row) - set(FILE_FIELDS) - {'bundlePath'}:
                raise ValueError('Unknown model export file metadata')
            expected = model['directory'] + '/' + row['relativePath']
            if row.get('bundlePath') != expected:
                raise ValueError('Model bundle path differs from file identity')
            allowed.add(expected)
            _file_identity(snapshot / row['relativePath'], row)
    actual = set()
    for path in output.rglob('*'):
        if path.is_symlink():
            raise ValueError('Independent model export contains a symlink')
        if path.is_file():
            actual.add(path.relative_to(output).as_posix())
    if actual != allowed:
        raise ValueError('Export file inventory differs from manifest')
    return dict(status='independent_model_files_verified_not_runtime_acceptance',
        modelCount=len(records), fileCount=sum(len(row[2]) for row in records))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    export = commands.add_parser('export')
    export.add_argument('--receipt', required=True)
    export.add_argument('--receipt-sha256', required=True)
    export.add_argument('--output', required=True)
    check = commands.add_parser('verify')
    check.add_argument('--output', required=True)
    check.add_argument('--manifest-sha256', required=True)
    args = parser.parse_args(argv)
    result = (materialize(args.receipt, args.receipt_sha256, args.output)
        if args.command == 'export' else verify(args.output, args.manifest_sha256))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
