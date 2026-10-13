"""Whole authored child CAS is reread each frame; only reconstruction is reused.

No production registry, original competition evidence, model or GPU is used.
"""
import copy
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import hashlib
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStoreError
from canonical_projection import CanonicalProjectionError, _hash
from oer_source_registry import SourceRegistryError, _serialize
import native_pdf_source_family as family_module
import unified_source_registry as registry_module
from unified_source_registry import UnifiedCanonicalSourceRegistry
import test_native_pdf_source_family as base


class NativePdfSourceFamilyCacheTest(unittest.TestCase):
    setUpClass = classmethod(base.NativePdfSourceFamilyTest.setUpClass.__func__)
    tearDownClass = classmethod(base.NativePdfSourceFamilyTest.tearDownClass.__func__)
    child = classmethod(base.NativePdfSourceFamilyTest.child.__func__)
    pin = classmethod(base.NativePdfSourceFamilyTest.pin.__func__)

    def setUp(self):
        self.registry = self.new_registry()
        self.address = self.family['projectionSha256']
        self.software = {Path(module.__file__): Path(module.__file__).read_bytes()
                         for module in (family_module, registry_module)}

    def new_registry(self, **kwargs):
        return UnifiedCanonicalSourceRegistry(self.base/'registry', self.store,
            family_policy=self.policy, family_target_module='vetting', **kwargs)

    def guard(self):
        for path, raw in self.software.items():
            if path.read_bytes() != raw:
                raise SourceRegistryError('AUTHORED_SOFTWARE_CHANGED', 'Actually imported test runtime changed')
        return {path.name: hashlib.sha256(raw).hexdigest() for path, raw in self.software.items()}

    def framed(self, registry=None, *, parents=False):
        registry = registry or self.registry
        with registry.validation_frame(software_guard=self.guard):
            result = registry.load(self.address)
            if parents:
                self.assertEqual(1, registry.validate_native_chunks(result['chunks']))
        return result

    def warm(self):
        self.assertEqual(self.family, self.framed(parents=True))
        self.assertEqual(1, len(self.registry._derived_results))

    def test_family_reader_closure_rejects_changed_table_dependency(self):
        self.assertIn('native_pdf_table_projection.py', family_module._DEPENDENCIES)
        self.warm(); original = Path.read_bytes
        def changed(path):
            raw = original(path)
            return raw + b'\n# authored changed table reader\n' if path.name == 'native_pdf_table_projection.py' else raw
        with patch.object(Path, 'read_bytes', changed), self.assertRaises(CanonicalProjectionError) as error:
            family_module._software(self.store, capture=None)
        self.assertEqual('source_family_software_changed', error.exception.code)

    def test_independent_advisory_registry_reads_vetting_family_without_consumer_rewrite(self):
        # setUpClass used the real authored producer and publisher under the
        # Vetting configuration. A different query on that same registry would
        # miss the bug: this is a fresh independently configured registry.
        advisory = UnifiedCanonicalSourceRegistry(self.base/'registry', self.store,
            family_policy=self.policy, family_target_module='advisory')
        self.assertIsNot(self.registry, advisory)
        self.assertEqual('vetting', self.registry.family_target_module)
        self.assertEqual('advisory', advisory.family_target_module)
        expected_bytes = _serialize(self.family)
        expected_sha = hashlib.sha256(expected_bytes).hexdigest()
        original_child = family_module.validate_native_pdf_transport
        with patch.object(family_module, 'validate_native_pdf_transport', wraps=original_child) as child:
            for expected_calls in (2, 2):
                loaded = self.framed(advisory)
                self.assertEqual(self.address, loaded['projectionSha256'])
                self.assertEqual(expected_bytes, _serialize(loaded))
                self.assertEqual(expected_sha, hashlib.sha256(_serialize(loaded)).hexdigest())
                self.assertEqual('vetting', loaded['projectionIdentity']['module'])
                self.assertEqual(expected_calls, child.call_count)
        # Publishing that same ordered spec from the other configuration also
        # preserves its canonical address and every origin/quality field.
        with advisory.validation_frame(software_guard=self.guard):
            recomposed = family_module.compose_native_pdf_source_family(self.original_spec,
                registry=advisory, artifact_store=self.store, policy=self.policy,
                target_module='advisory')
            self.assertEqual(expected_bytes, _serialize(recomposed))
            advisory.publish(recomposed)
        fresh_vetting = self.new_registry()
        self.assertEqual(expected_bytes, _serialize(self.framed(fresh_vetting)))


    def test_cold_and_warm_retain_exact_whole_cas_entry_exit_without_child_reconstruction(self):
        traces = []
        original_read = self.store._read_uncached
        original_child = family_module.validate_native_pdf_transport
        with patch.object(family_module, 'validate_native_pdf_transport', wraps=original_child) as child:
            for _ in range(2):
                trace = []
                def read(descriptor, *, retain):
                    trace.append((descriptor['sha256'], descriptor['byteCount'], retain))
                    return original_read(descriptor, retain=retain)
                with patch.object(self.store, '_read_uncached', side_effect=read):
                    self.assertEqual(self.family, self.framed(parents=True))
                traces.append(trace)
            self.assertEqual(2, child.call_count, 'The warm frame must not reconstruct either child again')
        self.assertEqual(set(traces[0]), set(traces[1]))
        for trace in traces:
            entered = {(sha, size) for sha, size, retain in trace if retain}
            exited = {(sha, size) for sha, size, retain in trace if not retain}
            self.assertEqual(entered, exited)
            self.assertGreater(len(entered), 10)
        result = next(iter(self.registry._derived_results.values()))
        dependencies = [json.loads(raw) for raw in result.file_dependencies]
        committed = {row['path'] for row in dependencies}
        self.assertIn(str(self.registry._path(self.address).absolute()), committed)
        for child in self.children:
            self.assertIn(str(self.registry._path(child['projectionSha256']).absolute()), committed)
        calculated = len(result.serialized) + sum(map(len, result.dependencies)) + sum(map(len, result.file_dependencies))
        calculated += sum(map(len, next(iter(self.registry._derived_results))))
        self.assertEqual(calculated, result.retained_bytes)
        print('FAMILY_FRAME_ACTUAL_PROOF ' + json.dumps({
            'coldChildValidations': 2, 'warmChildValidations': 0,
            'actualCasTracesEach': [len(trace) for trace in traces],
            'distinctCompleteCasMembers': len({(sha, size) for sha, size, _ in traces[0]}),
            'distinctCompleteCasBytes': sum(size for _, size in {(sha, size) for sha, size, _ in traces[0]}),
            'committedFileDependencies': len(result.file_dependencies),
            'retainedBytesIncludingAllCommitments': result.retained_bytes,
            'completeParentCount': len(self.family['chunks']),
            'equalCompleteActualCasTrace': set(traces[0]) == set(traces[1]),
            'qualityOrWholeSourcePromoted': False}))

    def test_warm_child_root_same_length_restored_mtime_fails_before_any_reconstruction(self):
        self.warm()
        path = self.registry._path(self.children[0]['projectionSha256'])
        raw, stat = path.read_bytes(), path.stat()
        changed = raw.replace(b'Visible', b'Changed', 1)
        self.assertNotEqual(raw, changed); self.assertEqual(len(raw), len(changed))
        try:
            path.write_bytes(changed); os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            with patch.object(family_module, 'validate_native_pdf_transport') as child:
                with self.assertRaisesRegex(SourceRegistryError, 'dependency differs'):
                    self.framed()
                child.assert_not_called()
        finally:
            path.write_bytes(raw)

    def test_warm_complete_child_files_swapped_or_deleted_are_not_proved_by_cas_copies(self):
        self.warm()
        first = self.registry._path(self.children[0]['projectionSha256'])
        second = self.registry._path(self.children[1]['projectionSha256'])
        raw_first, raw_second = first.read_bytes(), second.read_bytes()
        try:
            first.write_bytes(raw_second); second.write_bytes(raw_first)
            with self.assertRaises(SourceRegistryError): self.framed()
            first.write_bytes(raw_first); second.write_bytes(raw_second)
            second.unlink()
            with self.assertRaises(SourceRegistryError): self.framed()
        finally:
            first.write_bytes(raw_first); second.write_bytes(raw_second)

    def test_warm_resealed_child_is_a_new_identity_not_a_permitted_old_pin(self):
        self.warm()
        path = self.registry._path(self.children[1]['projectionSha256']); raw = path.read_bytes()
        changed = copy.deepcopy(self.children[1]); changed['humanConfirmed'] = True
        changed['projectionSha256'] = _hash({k:v for k,v in changed.items() if k!='projectionSha256'})
        try:
            path.write_bytes(_serialize(changed))
            with self.assertRaises(SourceRegistryError): self.framed()
        finally:
            path.write_bytes(raw)

    def test_warm_missing_or_tampered_actual_child_cas_still_fails(self):
        self.warm()
        descriptor = self.children[1]['internalProjectionRef']['artifactDescriptor']
        path = self.store._path(descriptor); raw = path.read_bytes()
        try:
            path.write_bytes(raw[:-1] + (b'x' if raw[-1:] != b'x' else b'y'))
            with self.assertRaises(ArtifactStoreError): self.framed()
            path.unlink()
            with self.assertRaises(ArtifactStoreError): self.framed()
        finally:
            path.write_bytes(raw)

    def test_warm_family_revision_alias_is_fresh_and_required_for_parent_source_gate(self):
        self.warm()
        alias = self.registry._family_revision_path(self.family['projectionRevisionSha256'])
        root = self.registry._path(self.address)
        try:
            alias.unlink(); alias.write_bytes(_serialize({'projectionSha256':self.address, 'forged':True}))
            with self.assertRaises(SourceRegistryError): self.framed(parents=True)
            alias.unlink()
            with self.assertRaises((SourceRegistryError, OSError)): self.framed(parents=True)
        finally:
            alias.unlink(missing_ok=True); os.link(root, alias)

    def test_warm_child_root_change_after_cas_exit_cannot_escape_successful_frame(self):
        self.warm()
        path = self.registry._path(self.children[1]['projectionSha256']); raw = path.read_bytes()
        original = self.store.validation_session
        @contextmanager
        def changed_after_cas():
            with original(): yield
            path.write_bytes(raw + b' ')
        try:
            with patch.object(self.store, 'validation_session', changed_after_cas):
                with self.assertRaises(SourceRegistryError): self.framed()
        finally:
            path.write_bytes(raw)

    def test_family_policy_module_and_owner_changes_cannot_reuse_old_context(self):
        self.warm()
        original_child = family_module.validate_native_pdf_transport
        previous = self.registry.family_policy
        try:
            # The abbreviated health identity omits this field. Bind the full
            # immutable object even when this ordinary-source fixture remains valid.
            self.registry.family_policy = replace(previous, metadata_spans=((1,2),))
            with patch.object(family_module, 'validate_native_pdf_transport', wraps=original_child) as child:
                self.assertEqual(self.family, self.framed()); self.assertEqual(2, child.call_count)
            self.registry.family_policy = replace(previous, manifest_sha256='f'*64)
            with self.assertRaises(CanonicalProjectionError): self.framed()
        finally:
            self.registry.family_policy = previous
        # A shared source remains byte-identical for another eligible consumer,
        # but changing process context must execute a new complete cold proof.
        try:
            self.registry.family_target_module = 'advisory'
            with patch.object(family_module, 'validate_native_pdf_transport', wraps=original_child) as child:
                self.assertEqual(self.family, self.framed()); self.assertEqual(2, child.call_count)
                self.assertEqual(self.family, self.framed()); self.assertEqual(2, child.call_count)
        finally:
            self.registry.family_target_module = 'vetting'
        for module in ('drafting',):
            try:
                self.registry.family_target_module = module
                with self.assertRaises((CanonicalProjectionError, SourceRegistryError)): self.framed()
            finally:
                self.registry.family_target_module = 'vetting'
        owner = self.registry.root
        empty = self.base/'other-registry-owner'; empty.mkdir(exist_ok=True)
        (empty/(self.address+'.json')).write_bytes(_serialize(self.family))
        try:
            self.registry.root = empty.resolve()
            with self.assertRaises((CanonicalProjectionError, OSError)): self.framed()
        finally:
            self.registry.root = owner

    def test_family_policy_or_module_change_during_frame_is_rejected_at_exit(self):
        self.warm()
        previous = self.registry.family_policy
        try:
            with self.assertRaises(SourceRegistryError):
                with self.registry.validation_frame(software_guard=self.guard):
                    self.registry.load(self.address)
                    self.registry.family_policy = replace(previous, metadata_spans=((1,2),))
        finally:
            self.registry.family_policy = previous
        try:
            with self.assertRaises(SourceRegistryError):
                with self.registry.validation_frame(software_guard=self.guard):
                    self.registry.load(self.address)
                    self.registry.family_target_module = 'advisory'
        finally:
            self.registry.family_target_module = 'vetting'

    def test_warm_software_changed_on_disk_is_not_hidden_by_private_reconstruction(self):
        self.warm()
        path = Path(registry_module.__file__); raw = path.read_bytes()
        try:
            path.write_bytes(raw + b'\n# authored temporary software drift\n')
            with self.assertRaises(SourceRegistryError): self.framed()
        finally:
            path.write_bytes(raw)

    def test_returned_callers_and_concurrent_frames_do_not_share_mutable_payloads(self):
        self.warm()
        altered = self.framed(); altered['chunks'][0]['content'] = 'caller mutation'
        altered['sourceFamilyComponents'].clear()
        self.assertEqual(self.family, self.framed())
        with ThreadPoolExecutor(max_workers=2) as executor:
            first, second = list(executor.map(lambda _: self.framed(), range(2)))
        first['chunks'].clear()
        self.assertEqual(self.family, second)
        self.assertIsNone(self.registry._validation_frame.get())
        self.assertEqual((), self.registry._file_collectors.get())

    def test_failed_frame_does_not_publish_pending_pure_reconstruction(self):
        with self.assertRaisesRegex(RuntimeError, 'authored body failure'):
            with self.registry.validation_frame(software_guard=self.guard):
                self.registry.load(self.address)
                raise RuntimeError('authored body failure')
        self.assertEqual(0, len(self.registry._derived_results))
        self.assertIsNone(self.registry._validation_frame.get())

    def test_retention_budget_includes_file_commitments_and_cap_does_not_truncate_roots(self):
        bounded = self.new_registry(derived_cache_max_bytes=len(_serialize(self.family)))
        original_child = family_module.validate_native_pdf_transport
        with patch.object(family_module, 'validate_native_pdf_transport', wraps=original_child) as child:
            self.framed(bounded); self.framed(bounded)
            self.assertEqual(4, child.call_count)
        self.assertEqual(0, len(bounded._derived_results))
        self.warm()
        path = self.registry._path(self.children[0]['projectionSha256']); raw = path.read_bytes()
        try:
            path.write_bytes(raw + b' ' * (family_module.MAX_SNAPSHOT_BYTES - len(raw) + 1))
            with self.assertRaises(SourceRegistryError) as caught: self.framed()
            self.assertEqual('CANONICAL_WIRE_TOO_LARGE', caught.exception.code)
        finally:
            path.write_bytes(raw)


if __name__ == '__main__': unittest.main()
