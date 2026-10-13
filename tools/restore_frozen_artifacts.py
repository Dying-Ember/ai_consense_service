"""Verify a release's frozen bytes; optionally restore into a new directory."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import tempfile


def safe_path(name):
    if not isinstance(name, str):
        raise ValueError('Archive paths must be strings')
    path = PurePosixPath(name)
    if (not name or not path.parts or path.is_absolute()
            or str(path) != name or any(part in ('.', '..') for part in path.parts)
            or any(character in '\\:<>|\"?*' or ord(character) < 32 for character in name)
            or any(part.rstrip(' .') != part or re.fullmatch(
                r'(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?', part, re.I)
                for part in path.parts)):
        raise ValueError('Unsafe archive path: ' + str(name))
    return path


def descriptor(row):
    if (not isinstance(row, dict) or type(row.get('bytes')) is not int or row['bytes'] < 0
            or not re.fullmatch(r'[0-9a-f]{64}', row.get('sha256', ''))):
        raise ValueError('Invalid file descriptor')


def copy_checked(source, target, expected, total=None):
    digest = hashlib.sha256()
    length = 0
    for block in iter(lambda: source.read(1024 * 1024), b''):
        length += len(block)
        if length > expected['bytes']:
            raise ValueError('File exceeds declared size')
        digest.update(block)
        if total is not None:
            total.update(block)
        if target is not None:
            target.write(block)
    if length != expected['bytes'] or digest.hexdigest() != expected['sha256']:
        raise ValueError('File size or SHA256 differs from manifest')


def restore(manifest_path, parts_dir, destination=None, expected_manifest_sha256=None):
    manifest_bytes = Path(manifest_path).read_bytes()
    if expected_manifest_sha256 is not None and hashlib.sha256(manifest_bytes).hexdigest() != expected_manifest_sha256:
        raise ValueError('Manifest differs from trusted SHA256')
    manifest = json.loads(manifest_bytes)
    if not isinstance(manifest, dict) or manifest.get('schema') != 'consense.frozen-artifacts/v1':
        raise ValueError('Unsupported frozen-artifact manifest')
    destination = Path(destination) if destination is not None else None
    if destination is not None and (not destination.is_absolute()
            or destination.exists() or destination.is_symlink()):
        raise ValueError('Destination must be a new absolute path')
    files = {}
    for row in manifest['files']:
        name = str(safe_path(row['path']))
        descriptor(row)
        if name in files or PurePosixPath(name).parts[0].casefold() in ('path-map.json', 'restored.json'):
            raise ValueError('Duplicate or reserved archive path')
        files[name] = row
    if any(str(parent) in files for name in files for parent in PurePosixPath(name).parents):
        raise ValueError('Archive file conflicts with a parent directory')
    path_map = manifest.get('pathMap', {})
    if not isinstance(path_map, dict) or any(value not in files for value in path_map.values()):
        raise ValueError('Path map must refer to declared files')
    archive = manifest['archive']
    descriptor(archive)
    part_names = set()
    total = hashlib.sha256()
    length = 0
    with tempfile.TemporaryFile() as assembled:
        for part in archive['parts']:
            name = str(safe_path(part['name']))
            descriptor(part)
            if '/' in name or name in part_names:
                raise ValueError('Duplicate or nonlocal part name')
            part_names.add(name)
            with (Path(parts_dir) / name).open('rb') as source:
                copy_checked(source, assembled, part, total)
            length += part['bytes']
        if length != archive['bytes'] or total.hexdigest() != archive['sha256']:
            raise ValueError('Assembled archive differs from manifest')
        assembled.seek(0)
        with tarfile.open(fileobj=assembled, mode='r:*') as package:
            members = package.getmembers()
            names = [str(safe_path(member.name)) for member in members]
            if (len(names) != len(set(names)) or set(names) != set(files)
                    or any(not member.isfile() or member.size != files[member.name]['bytes']
                           for member in members)):
                raise ValueError('Archive members differ from declared regular files')
            # Validate every member before creating the requested destination.
            for member in members:
                with package.extractfile(member) as source:
                    copy_checked(source, None, files[member.name])
            if destination is not None:
                destination.mkdir(parents=True, exist_ok=False)
                for member in members:
                    target = destination.joinpath(*PurePosixPath(member.name).parts)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with package.extractfile(member) as source, target.open('xb') as output:
                        copy_checked(source, output, files[member.name])
                (destination / 'path-map.json').write_text(json.dumps(
                    {key: str(destination / value) for key, value in path_map.items()},
                    ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        result = {'manifestSha256': hashlib.sha256(manifest_bytes).hexdigest(),
                  'archiveSha256': archive['sha256'], 'verifiedFiles': len(files),
                  'restoredTo': str(destination) if destination else None,
                  'runtimeStarted': False, 'absoluteSourcePathsRewritten': False}
        if destination is not None:
            (destination / 'RESTORED.json').write_text(
                json.dumps(result, indent=2) + '\n', encoding='utf-8')
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--manifest-sha256', required=True, help='Trusted SHA256 from the reviewed PR')
    parser.add_argument('--parts-dir', required=True)
    parser.add_argument('--destination', help='New absolute directory; omit to verify only')
    args = parser.parse_args()
    try:
        print(json.dumps(restore(args.manifest, args.parts_dir, args.destination, args.manifest_sha256), indent=2))
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError) as error:
        parser.exit(2, str(error) + '\n')


if __name__ == '__main__':
    main()
