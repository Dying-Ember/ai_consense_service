"""One actual-CAS registry coordinating existing PDF, DOCX and scanned gates.

Collection roots are bounded HTTP identities; hydrated complete parents are an
internal retrieval view only. PDF signals are dispatched before the broader
native-DOCX candidate detector, which also matches PDF recipe/block prefixes.
"""
from contextlib import contextmanager
from contextvars import ContextVar
from collections import OrderedDict
import hashlib
import json
import os
from threading import RLock
from typing import NamedTuple
from dataclasses import asdict
from pathlib import Path
from competition_scope import CompetitionScopePolicy

from native_collection_registry import NativeCollectionSourceRegistry
from native_observation import _strict_json
from native_pdf_source_registry import NativePdfCanonicalSourceRegistry, _pdf_candidate
from native_projection_collection import SCHEMA as COLLECTION_SCHEMA, hydrate_native_collection
from native_projection_transport import MAX_SNAPSHOT_BYTES
from oer_source_registry import SourceRegistryError, _serialize
from source_page_registry import SourcePageRenderRegistry
from native_pdf_source_family import (SCHEMA as FAMILY_SCHEMA, source_family_candidate,
    validate_native_pdf_source_family)
from oer_source_registry import CanonicalSourceRegistry, _identity


class _DerivedResult(NamedTuple):
    serialized: bytes
    dependencies: tuple
    file_dependencies: tuple
    collection: bool
    retained_bytes: int


class _PdfRegistry(NativePdfCanonicalSourceRegistry):
    """The reused PDF locator gate reads through the same bounded strict wire reader."""
    def __init__(self, root, artifact_store, wire_reader, frame_active, frame_loader, pin_file):
        super().__init__(root, artifact_store)
        self._wire_reader = wire_reader
        self._frame_active, self._frame_loader, self._pin_file = frame_active, frame_loader, pin_file

    def load(self, address):
        if self._frame_active():
            return self._frame_loader(address)
        with self.artifact_store.validation_session():
            wire = self._wire_reader(address)
            self.validator(wire)
            return wire

    def _pdf_revision_path(self, revision):
        path = super()._pdf_revision_path(revision)
        self._pin_file(path)
        return path


class UnifiedCanonicalSourceRegistry(NativeCollectionSourceRegistry):
    def __init__(self, root, artifact_store, *, derived_cache_max_bytes=64 * 1024 * 1024,
                 derived_cache_max_entries=8, family_policy=None, family_target_module='vetting'):
        self._validation_frame = ContextVar('consense_unified_source_validation_frame', default=None)
        self._file_collectors = ContextVar('consense_unified_file_dependency_collectors', default=())
        if (type(derived_cache_max_bytes) is not int or derived_cache_max_bytes < 0
                or type(derived_cache_max_entries) is not int or derived_cache_max_entries < 0):
            raise ValueError('Derived-cache budgets must be nonnegative integers')
        self._derived_max_bytes = derived_cache_max_bytes
        self._derived_max_entries = derived_cache_max_entries
        self._derived_results = OrderedDict()
        self._derived_bytes = 0
        self._derived_lock = RLock()
        super().__init__(root, artifact_store)
        self.pdf_registry = _PdfRegistry(root, artifact_store, self._wire,
            lambda: self._validation_frame.get() is not None, self.load, self._pin_frame_file)
        self.source_pages = SourcePageRenderRegistry(self.root / 'source-pages', self, artifact_store)
        self.family_policy, self.family_target_module = family_policy, family_target_module
        prior_validator = self.validator
        def validate(snapshot):
            if source_family_candidate(snapshot):
                return self._validate_family(snapshot)
            return prior_validator(snapshot)
        self.validator = validate

    def _validate_family(self, snapshot):
        self._family_context()
        return validate_native_pdf_source_family(snapshot, registry=self,
            artifact_store=self.artifact_store, policy=self.family_policy,
            target_module=self.family_target_module)

    def _family_revision_path(self, revision):
        path = self.root / 'native-pdf-family-revisions' / (_identity(revision, 'projectionRevisionSha256') + '.json')
        self._pin_frame_file(path)
        return path

    def _validate_family_and_native_chunks(self, chunks):
        families, pdf, ordinary = {}, [], []
        for chunk in chunks:
            self._object(chunk)
            if source_family_candidate(chunk):
                revision = _identity(chunk.get('projectionRevisionSha256'), 'projectionRevisionSha256')
                families.setdefault(revision, []).append(chunk)
            elif _pdf_candidate(chunk):
                pdf.append(chunk)
            else:
                ordinary.append(chunk)
        count = super().validate_native_chunks(ordinary)
        count += self.pdf_registry.validate_native_chunks(pdf)
        for revision, members in families.items():
            try:
                locator = _strict_json(self._family_revision_path(revision).read_bytes())
                snapshot = self.load(locator['projectionSha256'])
            except (OSError, ValueError, KeyError, TypeError) as error:
                raise SourceRegistryError('SOURCE_FAMILY_SOURCE_UNAVAILABLE',
                    'Family source is unregistered or its complete child proof is invalid') from error
            if (snapshot.get('schema') != FAMILY_SCHEMA or snapshot.get('projectionRevisionSha256') != revision
                    or _serialize(locator) != _serialize(snapshot) or members != snapshot['chunks']):
                raise SourceRegistryError('SOURCE_FAMILY_INDEX_PARENT_MISMATCH',
                    'Family index must retain every complete exact derived parent in declared order')
        return count + len(families)

    def _derived_get(self, key):
        with self._derived_lock:
            result = self._derived_results.get(key)
            if result is not None:
                self._derived_results.move_to_end(key)
            return result

    def _derived_commit(self, pending):
        # Call only after complete current CAS/root/alias/software exit guards.
        # All retained payloads and keys are immutable private bytes. The byte
        # budget accounts payloads, descriptors and keys, not Python overhead;
        # the independent entry limit bounds that fixed per-entry overhead.
        with self._derived_lock:
            for key, result in pending.items():
                if (not self._derived_max_entries or result.retained_bytes > self._derived_max_bytes):
                    continue
                old = self._derived_results.pop(key, None)
                if old is not None:
                    self._derived_bytes -= old.retained_bytes
                self._derived_results[key] = result
                self._derived_bytes += result.retained_bytes
                while (len(self._derived_results) > self._derived_max_entries
                       or self._derived_bytes > self._derived_max_bytes):
                    _, removed = self._derived_results.popitem(last=False)
                    self._derived_bytes -= removed.retained_bytes

    def _fresh_dependencies(self, result):
        # Every complete actual object is freshly read/hashed on first use in
        # this frame and rehashed by its owning ArtifactStore at successful exit.
        # Only deterministic reconstruction is reused, never source validity.
        for raw in result.file_dependencies:
            dependency = json.loads(raw)
            path = Path(dependency['path'])
            actual = self._read_frame_file(path)
            if (str(path.resolve()), len(actual), hashlib.sha256(actual).hexdigest()) != (
                    dependency['resolvedPath'], dependency['byteCount'], dependency['sha256']):
                raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                    'A complete reconstruction file dependency differs from its exact prior bytes or location')
            self._pin_frame_file(path, actual)
        for raw in result.dependencies:
            self.artifact_store.read_asset(json.loads(raw))

    @contextmanager
    def _capture_file_dependencies(self):
        dependencies = {}
        token = self._file_collectors.set((*self._file_collectors.get(), dependencies))
        try:
            yield dependencies
        finally:
            self._file_collectors.reset(token)

    def _family_context(self):
        # Every field of the actual immutable policy matters: its abbreviated
        # public health identity does not enumerate metadata/excluded spans.
        if type(self.family_policy) is not CompetitionScopePolicy or self.family_target_module not in ('vetting', 'advisory'):
            raise SourceRegistryError('SOURCE_FAMILY_ACTIVE_POLICY_REQUIRED',
                'Family reconstruction requires the actual immutable policy and process module')
        return _serialize({'policy': asdict(self.family_policy),
            'policyIdentity': self.family_policy.policyIdentity, 'module': self.family_target_module,
            'registryRoot': str(self.root.resolve()), 'artifactRoot': str(self.artifact_store.root.resolve())})

    def _pin_family_context(self, frame):
        current = self._family_context()
        if frame['familyContext'] is not None and frame['familyContext'] != current:
            raise SourceRegistryError('CANONICAL_FRAME_POLICY_CHANGED',
                'Family policy, module or registry ownership changed during this request')
        frame['familyContext'] = current
        return current

    def _check_family_context(self, frame):
        if frame['familyContext'] is not None:
            self._pin_family_context(frame)

    def derived_reconstruction_policy(self):
        """Describe pure reuse without claiming this health read proves sources."""
        return {'version': 'immutable-byte-bound-derived-reconstruction-v1',
                'enabled': bool(self._derived_max_bytes and self._derived_max_entries),
                'maximumRetainedPayloadBytes': self._derived_max_bytes,
                'maximumEntries': self._derived_max_entries,
                'identity': ['exact_public_root_bytes', 'complete_current_runtime_software'],
                'fileDependencies': 'exact_bounded_child_root_and_locator_bytes_and_resolved_paths',
                'familyIdentity': 'complete_immutable_policy_module_and_registry_ownership',
                'completeActualByteDependencies': 'fresh_entry_and_successful_exit_each_request',
                'publishBoundary': 'after_all_successful_frame_exit_guards',
                'callerOwnership': 'fresh_public_root_or_detached_collection_materialization',
                'cachedSourceValidity': False, 'unframedReadsUseCache': False}

    def _pin_frame_file(self, path, raw=None):
        frame = self._validation_frame.get()
        if frame is None:
            return
        if raw is None:
            raw = self._read_frame_file(path)
        if len(raw) > MAX_SNAPSHOT_BYTES:
            raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE', 'Public source dependency exceeds the fixed 16MiB maximum')
        current = (len(raw), hashlib.sha256(raw).hexdigest())
        key = str(path.resolve())
        prior = frame['files'].get(key)
        if prior is not None and prior != current:
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                'Published root or revision binding changed during this request')
        frame['files'][key] = current
        # Preserve the requested locator as well as its actual resolved path;
        # checking only the old target would miss a symlink/alias retarget.
        locator = str(path.absolute())
        location = (key, *current)
        prior_location = frame['locations'].get(locator)
        if prior_location is not None and prior_location != location:
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                'Published source locator changed during this request')
        frame['locations'][locator] = location
        dependency = {'path': locator, 'resolvedPath': key, 'byteCount': current[0], 'sha256': current[1]}
        for collector in self._file_collectors.get():
            collector[locator] = dependency

    @staticmethod
    def _read_frame_file(path):
        try:
            if path.stat().st_size > MAX_SNAPSHOT_BYTES:
                raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE',
                    'Public source dependency exceeds the fixed 16MiB maximum')
            raw = path.read_bytes()
            if len(raw) > MAX_SNAPSHOT_BYTES:
                raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE',
                    'Public source dependency exceeds the fixed 16MiB maximum')
            return raw
        except OSError as error:
            raise SourceRegistryError('CANONICAL_FRAME_SOURCE_UNAVAILABLE',
                'Published source dependency is no longer available') from error

    @staticmethod
    def _check_frame_files(frame):
        for filename, expected in frame['locations'].items():
            path = Path(filename)
            raw = UnifiedCanonicalSourceRegistry._read_frame_file(path)
            if (str(path.resolve()), len(raw), hashlib.sha256(raw).hexdigest()) != expected:
                raise SourceRegistryError('CANONICAL_FRAME_SOURCE_CHANGED',
                    'Published root or revision binding changed during this request')

    @contextmanager
    def validation_frame(self, *, software_guard):
        """Fresh request-only proof with complete CAS/root/alias/software exit checks.

        Each use rereads its published wire. A bounded immutable derived result
        may survive the frame; its entire recorded actual-byte dependency
        closure must be freshly verified within every new complete frame.
        """
        if not callable(software_guard):
            raise SourceRegistryError('CANONICAL_FRAME_SOFTWARE_GUARD_REQUIRED',
                'A request frame requires the actual imported-runtime software guard')
        active = self._validation_frame.get()
        if active is not None:
            if active['guard'] != software_guard:
                raise SourceRegistryError('CANONICAL_FRAME_GUARD_MISMATCH',
                    'Nested source frame must retain the same runtime guard')
            yield
            return
        # A fresh request frame must own the outer actual-CAS boundary. An
        # unrelated preexisting session would defer its fresh exit rehash
        # beyond this frame and could let a successful response escape first.
        if self.artifact_store._read_session.get() is not None:
            raise SourceRegistryError('CANONICAL_FRAME_CAS_BOUNDARY_REQUIRED',
                'A fresh request frame requires its own complete actual-CAS exit boundary')
        identity = software_guard()
        frame = {'files': {}, 'values': {}, 'pending': {}, 'guard': software_guard,
                 'softwareIdentity': identity, 'softwareBytes': _serialize(identity),
                 'locations': {}, 'familyContext': None}
        token = self._validation_frame.set(frame)
        try:
            with self.artifact_store.validation_session():
                yield
                self._check_frame_files(frame)
                self._check_family_context(frame)
            # The existing session has now rehashed every complete actual CAS
            # file. Recheck roots/aliases/software after that final I/O too.
            self._check_frame_files(frame)
            self._check_family_context(frame)
            if _serialize(software_guard()) != frame['softwareBytes']:
                raise SourceRegistryError('CANONICAL_FRAME_SOFTWARE_CHANGED',
                    'Imported runtime software identity changed during this request')
            self._derived_commit(frame['pending'])
        finally:
            self._validation_frame.reset(token)
            frame['values'].clear()
            frame['pending'].clear()
            frame['files'].clear()
            frame['locations'].clear()

    def _revision_path(self, revision):
        path = super()._revision_path(revision)
        self._pin_frame_file(path)
        return path

    @staticmethod
    def _object(value):
        if not isinstance(value, dict):
            raise SourceRegistryError('CANONICAL_SCHEMA_UNSUPPORTED', 'Canonical snapshot must be a JSON object')

    def _wire(self, address):
        try:
            path = self._path(address)
            raw = path.read_bytes()
            if len(raw) > MAX_SNAPSHOT_BYTES:
                raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE', 'Public root exceeds the fixed 16MiB maximum')
            self._pin_frame_file(path, raw)
            wire = _strict_json(raw)
        except (OSError, UnicodeError, ValueError) as error:
            if isinstance(error, SourceRegistryError):
                raise
            raise SourceRegistryError('SNAPSHOT_UNAVAILABLE', 'Canonical snapshot is missing or unreadable') from error
        self._object(wire)
        if wire.get('projectionSha256') != address:
            raise SourceRegistryError('SNAPSHOT_IDENTITY_MISMATCH', 'Stored revision does not match its address')
        return wire

    def publish(self, projection):
        self._object(projection)
        if len(_serialize(projection)) > MAX_SNAPSHOT_BYTES:
            raise SourceRegistryError('CANONICAL_WIRE_TOO_LARGE', 'Public root exceeds the fixed 16MiB maximum')
        # Family's nested native references cannot fall through to a child gate.
        if source_family_candidate(projection):
            receipt = CanonicalSourceRegistry.publish(self, projection)
            alias = self._family_revision_path(projection['projectionRevisionSha256'])
            alias.parent.mkdir(parents=True, exist_ok=True)
            try:
                os.link(self._path(projection['projectionSha256']), alias)
            except FileExistsError:
                if alias.read_bytes() != self._path(projection['projectionSha256']).read_bytes():
                    raise SourceRegistryError('SOURCE_FAMILY_REVISION_CONFLICT',
                        'Family revision already refers to different exact bytes')
            return receipt
        # Independent PDF markers forbid fallback to the collection/DOCX/scan gates.
        if _pdf_candidate(projection):
            return self.pdf_registry.publish(projection)
        return super().publish(projection)

    def load(self, address):
        frame = self._validation_frame.get()
        if frame is not None:
            # Always perform the original strict bounded wire read, including
            # the current address and exact bytes. Only the expensive already
            # verified reconstruction is reused within this fresh frame.
            wire = self._wire(address)
            family = source_family_candidate(wire)
            family_context = self._pin_family_context(frame) if family else b''
            if address not in frame['values']:
                root_sha = frame['files'][str(self._path(address).resolve())][1]
                key = (address.encode(), root_sha.encode(), frame['softwareBytes'], family_context)
                result = self._derived_get(key)
                if result is not None:
                    self._fresh_dependencies(result)
                else:
                    with self._capture_file_dependencies() as files, self.artifact_store.capture_read_dependencies() as dependencies:
                        serialized = _serialize(self._load_current(address))
                    captured = tuple(_serialize(descriptor) for descriptor in dependencies.values())
                    captured_files = tuple(_serialize(files[path]) for path in sorted(files))
                    retained_bytes = len(serialized) + sum(map(len, captured)) + sum(map(len, captured_files)) + sum(map(len, key))
                    result = _DerivedResult(serialized, captured, captured_files,
                        wire.get('schema') == COLLECTION_SCHEMA, retained_bytes)
                    frame['pending'][key] = result
                frame['values'][address] = result
            result = frame['values'][address]
            # Ordinary snapshots already are the current strictly decoded root.
            # Return this fresh caller-owned tree, never a retained mutable tree.
            # Collections must materialize their larger internal hydrated view.
            return json.loads(result.serialized) if result.collection else wire
        with self.artifact_store.validation_session():
            return self._load_current(address)

    def _load_current(self, address):
        wire = self._wire(address)
        if source_family_candidate(wire):
            self._validate_family(wire)
            return wire
        if _pdf_candidate(wire):
            self.pdf_registry.validator(wire)
            return wire
        if wire.get('schema') == COLLECTION_SCHEMA:
            return hydrate_native_collection(wire, artifact_store=self.artifact_store)
        self.validator(wire)
        return wire

    def wire(self, address, *, module='vetting'):
        wire = self._wire(address)
        if source_family_candidate(wire):
            try:
                alias = self._read_frame_file(self._family_revision_path(wire['projectionRevisionSha256']))
                if len(alias) > MAX_SNAPSHOT_BYTES or alias != _serialize(wire):
                    raise SourceRegistryError('SOURCE_FAMILY_REVISION_MISMATCH',
                        'Family wire must retain its exact registered derived revision')
            except (OSError, KeyError, TypeError) as error:
                raise SourceRegistryError('SOURCE_FAMILY_REVISION_UNAVAILABLE',
                    'Exact family derived revision is unavailable') from error
        full = self.load(address)
        self._module(full, module)
        return wire, full['chunks']

    def validate_native_chunks(self, chunks):
        # Dispatch family before its nested child-PDF markers. Each old source
        # kind keeps its exact existing complete-parent proof gate.
        return self._validate_family_and_native_chunks(chunks)
