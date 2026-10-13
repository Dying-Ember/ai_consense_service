"""One registered document identity over bounded whole-unit native batches.

The root is the public registration address. ``load`` is an internal-only full
view for the existing retrieval runtime; HTTP must use ``wire`` or ``batch``.
No batch can be registered independently or substitute for a complete source.
"""
from __future__ import annotations

import os
from pathlib import Path
import tempfile

from native_projection_collection import (SCHEMA as COLLECTION_SCHEMA,
    hydrate_native_collection, read_native_collection_batch, read_native_collection_envelope_batch)
from native_observation import _strict_json
from native_source_registry import NativeCanonicalSourceRegistry, _native_candidate
from oer_source_registry import SourceRegistryError, _identity, _serialize


class NativeCollectionSourceRegistry(NativeCanonicalSourceRegistry):
    def _wire(self, address):
        try:
            snapshot = _strict_json(self._path(address).read_bytes())
        except (OSError, UnicodeError, ValueError) as error:
            raise SourceRegistryError('SNAPSHOT_UNAVAILABLE', 'Canonical snapshot is missing or unreadable') from error
        if snapshot.get('projectionSha256') != address:
            raise SourceRegistryError('SNAPSHOT_IDENTITY_MISMATCH', 'Stored revision does not match its address')
        return snapshot

    @staticmethod
    def _module(snapshot, module):
        identity = snapshot['projectionIdentity']
        shared = (module in ('vetting', 'advisory') and identity.get('module') in ('vetting', 'advisory')
                  and module in identity.get('eligibleModules', []))
        if module not in ('drafting', 'vetting', 'advisory') or not (identity.get('module') == module or shared):
            raise SourceRegistryError('SNAPSHOT_MODULE_MISMATCH', 'Projection belongs to a different module')

    def publish(self, projection):
        if projection.get('schema') != COLLECTION_SCHEMA:
            return super().publish(projection)
        # Actual original, reviewed source and every batch are proved before a
        # root or revision locator is made visible. A read never recreates CAS.
        full = hydrate_native_collection(projection, artifact_store=self.artifact_store)
        address = _identity(projection.get('projectionSha256'), 'projectionSha256')
        payload = _serialize(projection)
        path = self._path(address)
        self.root.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=self.root, prefix='.collection-', delete=False) as file:
            temporary = Path(file.name)
            file.write(payload)
            file.flush()
            os.fsync(file.fileno())
        try:
            try:
                os.link(temporary, path)
            except FileExistsError:
                if path.read_bytes() != payload:
                    raise SourceRegistryError('SNAPSHOT_CONFLICT', 'A collection address has different bytes')
        finally:
            temporary.unlink(missing_ok=True)
        alias = self._revision_path(projection['projectionRevisionSha256'])
        alias.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.link(path, alias)
        except FileExistsError:
            # One global public revision may not silently select another
            # batching policy. The existing immutable address remains usable.
            if alias.read_bytes() != payload:
                raise SourceRegistryError('NATIVE_REVISION_CONFLICT', 'Native revision already refers to another root')
        return {'projectionSha256': address, 'chunks': len(full['chunks']),
                'batches': len(projection['batches']), 'assets': 0, 'immutable': True,
                'qualityVerifiedByRegistry': False}

    def load(self, address):
        wire = self._wire(address)
        if wire.get('schema') == COLLECTION_SCHEMA:
            # The full old public view is not sent as an HTTP snapshot. It
            # retains one global source identity and the complete source order.
            return hydrate_native_collection(wire, artifact_store=self.artifact_store)
        return super().load(address)

    def wire(self, address, *, module='vetting'):
        wire = self._wire(address)
        full = (hydrate_native_collection(wire, artifact_store=self.artifact_store)
                if wire.get('schema') == COLLECTION_SCHEMA else super().load(address))
        self._module(full, module)
        return wire, full['chunks']

    def batch(self, address, ordinal, *, module='vetting'):
        wire = self._wire(address)
        if wire.get('schema') != COLLECTION_SCHEMA:
            raise SourceRegistryError('NATIVE_COLLECTION_REQUIRED', 'Batch reads require a document root')
        batch = read_native_collection_batch(wire, ordinal, artifact_store=self.artifact_store)
        self._module(wire, module)
        return batch

    def envelope_batch(self, address, ordinal, *, module='vetting', include_chunks=False):
        wire = self._wire(address)
        if wire.get('schema') != COLLECTION_SCHEMA:
            raise SourceRegistryError('NATIVE_COLLECTION_REQUIRED', 'Header reads require a document root')
        batch = read_native_collection_envelope_batch(wire, ordinal, artifact_store=self.artifact_store,
                                                      include_chunks=include_chunks)
        self._module(wire, module)
        return batch

    def validate_native_chunks(self, chunks):
        groups = {}
        for chunk in chunks:
            if _native_candidate(chunk):
                groups.setdefault(chunk.get('projectionRevisionSha256'), []).append(chunk)
        for revision, members in groups.items():
            try:
                locator = _strict_json(self._revision_path(revision).read_bytes())
                address = locator['projectionSha256']
                actual_wire = self._wire(address)
                full = self.load(address)
            except (OSError, ValueError, KeyError, TypeError) as error:
                raise SourceRegistryError('NATIVE_CANONICAL_SOURCE_UNAVAILABLE',
                    'Native source is unregistered or its actual CAS cannot be verified') from error
            if (_serialize(locator) != _serialize(actual_wire)
                    or full.get('projectionRevisionSha256') != revision or members != full['chunks']):
                raise SourceRegistryError('NATIVE_INDEX_PARENT_MISMATCH',
                    'Native index must retain every exact complete unit in global source order')
        return len(groups)
