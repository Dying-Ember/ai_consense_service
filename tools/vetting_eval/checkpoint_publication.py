"""Append-only model publications and loaded-instance identity.

This lifecycle differs from source-evidence CAS: a model runtime owns one
immutable published snapshot. Version changes require a new runtime. After
the first complete byte verification, online checks read the small externally
pinned manifest, rather than rehashing gigabytes during every query.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile

from token_windows import digest, fail
from checkpoint_identity import checkpoint_inventory


SCHEMA = 'consense.immutable-model-publication.v1'
MAX_MANIFEST_BYTES = 1024 * 1024


def _json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            fail(key not in result, 'MODEL_PUBLICATION_INVALID', 'Duplicate model publication field')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def _bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def _validate_declared_files(snapshot, files):
    """Check config-declared shards against the full copied inventory.

    The publisher already hashes bytes while copying, and the first runtime
    verification hashes every file below. This preflight only parses the
    small shard indexes; it does not add another multi-GB hash pass.
    """
    snapshot = Path(snapshot).resolve()
    inventory = {row['relativePath']: row for row in files}
    def copied_identity(path):
        name = Path(path).resolve().relative_to(snapshot).as_posix()
        fail(name in inventory, 'MODEL_PUBLICATION_DECLARED_FILE_MISSING',
             'Every declared checkpoint file must be in the complete publication inventory')
        return inventory[name]
    checkpoint_inventory(snapshot, copied_identity)


def publish_checkpoint(source, destination, model):
    """Copy actual bytes into a fresh sealed tree; never link mutable weights."""
    source, destination = Path(source).resolve(), Path(destination).resolve()
    fail(source.is_dir(), 'MODEL_PUBLICATION_SOURCE_INVALID', 'Model source must be an existing snapshot directory')
    fail(not destination.is_relative_to(source), 'MODEL_PUBLICATION_DESTINATION_INVALID',
         'Publication destination must not be inside the source snapshot')
    destination.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.model-publication-', dir=destination))
    snapshot = temporary / 'snapshot'
    snapshot.mkdir()
    files = []
    try:
        for path in sorted(source.rglob('*')):
            if not path.is_file():
                continue
            name = path.relative_to(source).as_posix()
            target = snapshot / name
            target.parent.mkdir(parents=True, exist_ok=True)
            sha, size = hashlib.sha256(), 0
            with path.open('rb') as incoming, target.open('xb') as outgoing:
                while raw := incoming.read(1024 * 1024):
                    sha.update(raw)
                    size += len(raw)
                    outgoing.write(raw)
                outgoing.flush()
                os.fsync(outgoing.fileno())
            files.append({'relativePath': name, 'bytes': size, 'sha256': sha.hexdigest()})
        fail(bool(files), 'MODEL_PUBLICATION_EMPTY', 'Model publication cannot be empty')
        _validate_declared_files(snapshot, files)
        body = {'schema': SCHEMA, 'model': model, 'snapshotRelativePath': 'snapshot',
                'files': files, 'lifecycle': 'immutable-snapshot-new-runtime-for-version-switch'}
        body['publicationSha256'] = digest(body)
        payload = _bytes(body)
        fail(len(payload) <= MAX_MANIFEST_BYTES, 'MODEL_PUBLICATION_TOO_LARGE', 'Model publication manifest exceeds 1MiB')
        (temporary / 'manifest.json').write_bytes(payload)
        target_root = destination / body['publicationSha256']
        if target_root.exists():
            fail((target_root / 'manifest.json').read_bytes() == payload,
                 'MODEL_PUBLICATION_CONFLICT', 'Publication identity already has different manifest bytes')
            # An existing publication is verified before idempotent reuse.
            existing = CheckpointPublication(target_root / 'manifest.json', hashlib.sha256(payload).hexdigest(), model)
            existing.verify_snapshot()
        else:
            temporary.rename(target_root)
            for path in target_root.rglob('*'):
                if path.is_file():
                    path.chmod(0o444)
        return {'manifestPath': str(target_root / 'manifest.json'),
            'manifestSha256': hashlib.sha256(payload).hexdigest(),
            'publicationSha256': body['publicationSha256'], 'snapshotPath': str(target_root / 'snapshot'),
            'files': len(files), 'totalFileBytes': sum(row['bytes'] for row in files),
            'versionSwitchRequiresNewRuntime': True}
    finally:
        if temporary.exists():
            fail(temporary.resolve().parent == destination and temporary.name.startswith('.model-publication-'),
                 'MODEL_PUBLICATION_CLEANUP_INVALID', 'Temporary publication cleanup must stay inside its explicit destination')
            shutil.rmtree(temporary)


class CheckpointPublication:
    def __init__(self, manifest_path, expected_manifest_sha256, model):
        self.path = Path(manifest_path).resolve()
        self.expected = expected_manifest_sha256
        fail(isinstance(self.expected, str) and re.fullmatch(r'[0-9a-f]{64}', self.expected),
             'MODEL_PUBLICATION_EXTERNAL_SHA_REQUIRED', 'Model publication requires the exact external manifest SHA')
        self.model, self.verified = model, False
        self.manifest = self.fresh_manifest()
        self.snapshot = self.path.parent / 'snapshot'
        fail(self.path.parent.name == self.manifest['publicationSha256'] and self.snapshot.is_dir(),
             'MODEL_PUBLICATION_PATH_INVALID', 'Model snapshot must live under its immutable publication identity')

    def fresh_manifest(self):
        try:
            raw = self.path.read_bytes()
            fail(len(raw) <= MAX_MANIFEST_BYTES and hashlib.sha256(raw).hexdigest() == self.expected,
                 'MODEL_PUBLICATION_CHANGED', 'Published model manifest changed; a new runtime is required')
            value = _json(raw)
        except (OSError, UnicodeError, ValueError) as error:
            fail(False, 'MODEL_PUBLICATION_UNAVAILABLE', 'Published model manifest is unavailable', cause=str(error))
        fail(isinstance(value, dict) and set(value) == {'schema', 'model', 'snapshotRelativePath', 'files',
             'lifecycle', 'publicationSha256'} and value['schema'] == SCHEMA and value['model'] == self.model
             and value['snapshotRelativePath'] == 'snapshot'
             and value['lifecycle'] == 'immutable-snapshot-new-runtime-for-version-switch'
             and value['publicationSha256'] == digest({key: item for key, item in value.items() if key != 'publicationSha256'}),
             'MODEL_PUBLICATION_INVALID', 'Model publication identity, model or lifecycle differs')
        files = value['files']
        fail(isinstance(files, list) and bool(files) and len(files) <= 100000,
             'MODEL_PUBLICATION_INVALID', 'Model publication requires a bounded complete file inventory')
        names = []
        for row in files:
            name = row.get('relativePath') if isinstance(row, dict) else None
            fail(isinstance(row, dict) and set(row) == {'relativePath', 'bytes', 'sha256'}
                 and isinstance(name, str) and name and '\\' not in name and ':' not in name
                 and not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
                 and type(row['bytes']) is int and row['bytes'] >= 0
                 and isinstance(row['sha256'], str) and re.fullmatch(r'[0-9a-f]{64}', row['sha256']),
                 'MODEL_PUBLICATION_INVALID', 'Model publication file descriptors must be exact portable identities')
            names.append(name)
        fail(names == sorted(set(names)), 'MODEL_PUBLICATION_INVALID', 'Model publication file inventory must be sorted and unique')
        return value

    def verify_snapshot(self):
        from window_runtime import file_identity
        current = self.fresh_manifest()
        actual_names = sorted(path.relative_to(self.snapshot).as_posix() for path in self.snapshot.rglob('*') if path.is_file())
        fail(actual_names == [row['relativePath'] for row in current['files']],
             'MODEL_PUBLICATION_FILES_CHANGED', 'Published model file inventory is incomplete or changed')
        _validate_declared_files(self.snapshot, current['files'])
        for row in current['files']:
            path = (self.snapshot / row['relativePath']).resolve()
            fail(path.is_relative_to(self.snapshot.resolve()) and {**file_identity(path), 'relativePath': row['relativePath']} == row,
                 'MODEL_PUBLICATION_BYTES_CHANGED', 'Actual published model bytes differ from the manifest')
        self.fresh_manifest()
        self.verified = True
        return current

    def identity(self):
        current = self.fresh_manifest()
        if not self.verified:
            current = self.verify_snapshot()
        return {'schema': SCHEMA, 'manifestSha256': self.expected,
                'publicationSha256': current['publicationSha256'],
                'lifecycle': current['lifecycle'], 'files': current['files']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    parser.add_argument('--destination', required=True)
    parser.add_argument('--model-json', required=True)
    args = parser.parse_args()
    print(json.dumps(publish_checkpoint(args.source, args.destination, _json(args.model_json.encode('utf-8'))), ensure_ascii=False))


if __name__ == '__main__':
    main()
