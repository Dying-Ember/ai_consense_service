"""Lossless, content addressed parser artifacts with verified reads.

Descriptors carry portable relative paths, never the store's host directory.
File and stream ingestion hashes while copying; a hash-only claim is not a
captured artifact. This store makes no model or source-quality assertion.
"""
import hashlib
import copy
from contextlib import contextmanager
from contextvars import ContextVar
import os
from pathlib import Path, PurePosixPath
import re
import tempfile


DESCRIPTOR_SCHEMA = 'consense.artifact-descriptor.v1'
CHUNK_BYTES = 1024 * 1024


class ArtifactStoreError(ValueError):
    """An invalid descriptor, unavailable artifact, or changed captured bytes."""


class ArtifactStore:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self._read_session = ContextVar('consense_actual_cas_validation_session', default=None)
        self._read_collectors = ContextVar('consense_actual_cas_dependency_collectors', default=())

    def _inside(self, path):
        resolved = path.resolve()
        if not resolved.is_relative_to(self.root):
            raise ArtifactStoreError('Artifact path must stay within the store')
        return resolved

    def _path(self, descriptor):
        if not isinstance(descriptor, dict) or descriptor.get('schema') != DESCRIPTOR_SCHEMA:
            raise ArtifactStoreError('Unsupported artifact descriptor schema')
        if descriptor.get('storage') != 'content_addressed_disk':
            raise ArtifactStoreError('Unsupported artifact storage')
        digest = descriptor.get('sha256')
        if not isinstance(digest, str) or not re.fullmatch(r'[0-9a-f]{64}', digest):
            raise ArtifactStoreError('Artifact sha256 must be lowercase SHA-256 hex')
        size = descriptor.get('byteCount')
        if isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise ArtifactStoreError('Artifact byteCount must be a nonnegative integer')
        relative = descriptor.get('relativePath')
        expected = 'sha256/' + digest[:2] + '/' + digest + '.blob'
        if not isinstance(relative, str) or relative != expected or PurePosixPath(relative).is_absolute():
            raise ArtifactStoreError('Artifact path must be its content addressed relative path within the store')
        if not isinstance(descriptor.get('identityScope'), str) or not descriptor['identityScope']:
            raise ArtifactStoreError('Artifact identityScope must be explicit')
        return self._inside(self.root / relative)

    def put_bytes(self, value, *, identity_scope='exact_supplied_bytes'):
        if not isinstance(value, bytes):
            raise ArtifactStoreError('put_bytes requires bytes')
        from io import BytesIO
        return self.put_stream(BytesIO(value), identity_scope=identity_scope)

    def put_file(self, path, *, identity_scope='exact_supplied_bytes'):
        with Path(path).open('rb') as stream:
            return self.put_stream(stream, identity_scope=identity_scope)

    def put_stream(self, stream, *, identity_scope='exact_supplied_bytes'):
        if not isinstance(identity_scope, str) or not identity_scope:
            raise ArtifactStoreError('Artifact identity scope must be explicit')
        if not callable(getattr(stream, 'read', None)):
            raise ArtifactStoreError('put_stream requires a binary readable stream')
        fd, temporary = tempfile.mkstemp(prefix='.capture-', dir=self.root)
        digest, size = hashlib.sha256(), 0
        try:
            with os.fdopen(fd, 'wb') as output:
                while True:
                    chunk = stream.read(CHUNK_BYTES)
                    if chunk == b'':
                        break
                    if not isinstance(chunk, bytes):
                        raise ArtifactStoreError('Artifact stream must yield bytes')
                    output.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
                output.flush()
                os.fsync(output.fileno())
            sha = digest.hexdigest()
            descriptor = {'schema': DESCRIPTOR_SCHEMA, 'storage': 'content_addressed_disk',
                          'sha256': sha, 'byteCount': size,
                          'relativePath': 'sha256/' + sha[:2] + '/' + sha + '.blob',
                          'identityScope': identity_scope}
            destination = self._path(descriptor)
            destination.parent.mkdir(parents=True, exist_ok=True)
            # Recheck after creating parents; existing symlinks may not escape.
            destination = self._path(descriptor)
            try:
                os.link(temporary, destination)
            except FileExistsError:
                self.verify(descriptor)
            return descriptor
        finally:
            Path(temporary).unlink(missing_ok=True)

    def _read_uncached(self, descriptor, *, retain):
        path = self._path(descriptor)
        digest, size, chunks = hashlib.sha256(), 0, []
        try:
            with path.open('rb') as stream:
                if not path.is_file():
                    raise ArtifactStoreError('Artifact is not a regular file')
                while chunk := stream.read(CHUNK_BYTES):
                    digest.update(chunk)
                    size += len(chunk)
                    if retain:
                        chunks.append(chunk)
        except OSError as error:
            raise ArtifactStoreError('Captured artifact is missing or unreadable: ' + descriptor['relativePath']) from error
        if size != descriptor['byteCount'] or digest.hexdigest() != descriptor['sha256']:
            raise ArtifactStoreError('Captured artifact SHA-256 or byteCount does not match its descriptor')
        return b''.join(chunks) if retain else {
            'status': 'verified', 'sha256': descriptor['sha256'], 'byteCount': size}

    @contextmanager
    def validation_session(self):
        """One complete validation snapshot, with fresh exit rehash.

        Cached immutable bytes are private to this nested context.
        No verdict or byte cache survives the outer boundary; source
        mutation/missing CAS before successful exit refuses all output.
        """
        active = self._read_session.get()
        if active is not None:
            yield
            return
        cache = {}
        token = self._read_session.set(cache)
        try:
            yield
            for descriptor, _ in cache.values():
                self._read_uncached(descriptor, retain=False)
        finally:
            self._read_session.reset(token)
            cache.clear()

    @contextmanager
    def capture_read_dependencies(self):
        """Record descriptors actually read, including hits in an owned session.

        This records a pure computation's complete byte dependencies; it does
        not verify a future read or retain raw bytes across sessions. Nested
        collectors all see the same successful reads and remain context-local.
        """
        dependencies = {}
        token = self._read_collectors.set((*self._read_collectors.get(), dependencies))
        try:
            yield dependencies
        finally:
            self._read_collectors.reset(token)

    def _read(self, descriptor, *, retain):
        result = self._read_verified(descriptor, retain=retain)
        if self._read_collectors.get():
            key = (str(self._path(descriptor)), descriptor['sha256'], descriptor['byteCount'])
            for dependencies in self._read_collectors.get():
                if key not in dependencies:
                    dependencies[key] = copy.deepcopy(descriptor)
        return result

    def _read_verified(self, descriptor, *, retain):
        cache = self._read_session.get()
        if cache is None:
            return self._read_uncached(descriptor, retain=retain)
        # Validate every supplied descriptor/path, even for cached bytes.
        path = self._path(descriptor)
        key = (str(path), descriptor['sha256'], descriptor['byteCount'])
        if key not in cache:
            raw = self._read_uncached(descriptor, retain=True)
            cache[key] = (copy.deepcopy(descriptor), raw)
        raw = cache[key][1]
        return raw if retain else {'status': 'verified', 'sha256': descriptor['sha256'],
                                   'byteCount': descriptor['byteCount']}

    def verify(self, descriptor):
        """Stream the entire stored content and verify its portable descriptor."""
        return self._read(descriptor, retain=False)

    def read_asset(self, descriptor):
        """Read actual bytes only after full SHA-256 and byte count verification."""
        return self._read(descriptor, retain=True)
