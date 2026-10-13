"""Authored replay-copy regressions; not model/source semantic acceptance."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from artifact_store import ArtifactStore, ArtifactStoreError
from canonical_projection import CanonicalProjectionError, _bytes, _hash
import native_canonical_projection as docx
import native_pdf_canonical_projection as pdf
from native_projection_transport import publish_native_transport, hydrate_native_transport
from native_pdf_projection_transport import publish_native_pdf_transport, hydrate_native_pdf_transport
from test_native_canonical_projection import fixture as docx_fixture
from test_native_pdf_canonical_projection import fixture as pdf_fixture


class ValidationReplayCopyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.store = ArtifactStore(self.temporary.name)

    def make_docx(self):
        bundle, manifest = docx_fixture(self.store)
        return docx.produce_native(bundle, manifest, artifact_store=self.store), (bundle, manifest)

    def make_pdf(self):
        native, dual, manifest = pdf_fixture(self.store)
        return pdf.produce_native_pdf(native, dual, manifest, artifact_store=self.store), (native, dual, manifest)

    def observe_private_replay(self, module, projection):
        original_assemble, original_copy = module._assemble, copy.deepcopy
        active_roots, observed_copy_roots, assembly_roots = [], [], []

        def assemble(*args, **kwargs):
            roots = list(args[:2] if module is pdf else args[:1])
            active_roots[:] = roots
            assembly_roots.extend(roots)
            try:
                return original_assemble(*args, **kwargs)
            finally:
                active_roots.clear()

        def counted_copy(value, *args, **kwargs):
            if any(value is root for root in active_roots):
                observed_copy_roots.append(value)
            return original_copy(value, *args, **kwargs)

        # DOCX's generic validator receives caller-owned input. Its strict
        # fresh hydration seam is the only eligible private replay here.
        public = publish_native_transport(projection, artifact_store=self.store) if module is docx else None
        validate = pdf.validate_native_pdf_projection if module is pdf else docx.validate_native_projection
        before = _bytes(projection)
        with patch.object(module, '_assemble', assemble), patch.object(copy, 'deepcopy', counted_copy):
            result = hydrate_native_transport(public, artifact_store=self.store) if module is docx else validate(projection, artifact_store=self.store)
        if module is pdf:
            self.assertIs(result, projection)
        else:
            self.assertEqual(result, projection)
            self.assertIsNot(result['nativeObservationBundle'], projection['nativeObservationBundle'])
        self.assertEqual(before, _bytes(projection))
        return observed_copy_roots, assembly_roots

    def test_docx_hydration_retains_default_full_bundle_copy(self):
        projection, _ = self.make_docx()
        copied, roots = self.observe_private_replay(docx, projection)
        self.assertEqual(1, len(roots))
        self.assertEqual(1, len(copied), 'DOCX caller-owned validation must retain its defensive copy')

    def test_pdf_replay_does_not_duplicate_complete_native_and_dual_bundles(self):
        projection, _ = self.make_pdf()
        copied, roots = self.observe_private_replay(pdf, projection)
        self.assertEqual(2, len(roots))
        self.assertEqual(0, len(copied), 'private validation replay copied complete native/dual PDF inputs')
        # Freshly decoded replay roots do not escape through the returned projection.
        for root in roots:
            self.assertIsNot(root, projection['nativeObservationBundle'])
            self.assertIsNot(root, projection['dualParserBundle'])

    def test_docx_default_producer_retains_owned_copy_and_does_not_mutate_inputs(self):
        bundle, manifest = docx_fixture(self.store)
        before = _bytes({'bundle': bundle, 'manifest': manifest})
        result = docx.produce_native(bundle, manifest, artifact_store=self.store)
        self.assertEqual(before, _bytes({'bundle': bundle, 'manifest': manifest}))
        self.assertIsNot(result['nativeObservationBundle'], bundle)
        self.assertIsNot(result['manifest'], manifest)
        bundle['source']['sourceFilename'] = 'mutated caller'
        self.assertNotEqual(bundle['source'], result['nativeObservationBundle']['source'])

    def test_pdf_default_producer_retains_owned_copies_and_does_not_mutate_inputs(self):
        native, dual, manifest = pdf_fixture(self.store)
        before = _bytes({'native': native, 'dual': dual, 'manifest': manifest})
        result = pdf.produce_native_pdf(native, dual, manifest, artifact_store=self.store)
        self.assertEqual(before, _bytes({'native': native, 'dual': dual, 'manifest': manifest}))
        self.assertIsNot(result['nativeObservationBundle'], native)
        self.assertIsNot(result['dualParserBundle'], dual)
        self.assertIsNot(result['manifest'], manifest)
        native['source']['filename'] = 'mutated caller'
        dual['qualityStatus'] = 'mutated caller'
        self.assertNotEqual(native['source'], result['nativeObservationBundle']['source'])
        self.assertNotEqual(dual['qualityStatus'], result['dualParserBundle']['qualityStatus'])

    def test_docx_hydration_inputs_do_not_escape_into_a_second_hydration(self):
        internal, _ = self.make_docx()
        public = publish_native_transport(internal, artifact_store=self.store)
        before = _bytes(public)
        first = hydrate_native_transport(public, artifact_store=self.store)
        first['nativeObservationBundle']['source']['sourceFilename'] = 'mutated private result'
        second = hydrate_native_transport(public, artifact_store=self.store)
        self.assertEqual(internal, second)
        self.assertEqual(before, _bytes(public))
        self.assertIsNot(first['nativeObservationBundle'], second['nativeObservationBundle'])

    def test_pdf_hydration_inputs_do_not_escape_into_a_second_hydration(self):
        internal, _ = self.make_pdf()
        public = publish_native_pdf_transport(internal, artifact_store=self.store)
        before = _bytes(public)
        first = hydrate_native_pdf_transport(public, artifact_store=self.store)
        first['nativeObservationBundle']['source']['filename'] = 'mutated private result'
        first['dualParserBundle']['qualityStatus'] = 'mutated private result'
        second = hydrate_native_pdf_transport(public, artifact_store=self.store)
        self.assertEqual(internal, second)
        self.assertEqual(before, _bytes(public))
        self.assertIsNot(first['nativeObservationBundle'], second['nativeObservationBundle'])
        self.assertIsNot(first['dualParserBundle'], second['dualParserBundle'])

    def reject_changed_cas(self, make, validate, during_exit):
        projection, _ = make()
        descriptor = projection['nativeObservationBundle']['sourcePackage']
        target = self.store._path(descriptor)
        saved = target.read_bytes()
        changed = bytes([saved[0] ^ 1]) + saved[1:]
        try:
            if during_exit:
                with self.assertRaises(ArtifactStoreError):
                    with self.store.validation_session():
                        validate(projection, artifact_store=self.store)
                        target.write_bytes(changed)
            else:
                target.write_bytes(changed)
                with self.assertRaises((ArtifactStoreError, CanonicalProjectionError, ValueError)):
                    with self.store.validation_session():
                        validate(projection, artifact_store=self.store)
        finally:
            target.write_bytes(saved)
        with self.store.validation_session():
            self.assertIs(validate(projection, artifact_store=self.store), projection)

    def test_docx_cas_mutation_before_entry_is_rejected(self):
        self.reject_changed_cas(self.make_docx, docx.validate_native_projection, False)

    def test_pdf_cas_mutation_before_entry_is_rejected(self):
        self.reject_changed_cas(self.make_pdf, pdf.validate_native_pdf_projection, False)

    def test_docx_cas_mutation_before_exit_is_rejected(self):
        self.reject_changed_cas(self.make_docx, docx.validate_native_projection, True)

    def test_pdf_cas_mutation_before_exit_is_rejected(self):
        self.reject_changed_cas(self.make_pdf, pdf.validate_native_pdf_projection, True)

    def reject_changed_derived(self, make, validate):
        projection, _ = make()
        projection['chunks'][0]['content'] += ' invented legal condition'
        projection['projectionSha256'] = _hash({k: v for k, v in projection.items() if k != 'projectionSha256'})
        with self.assertRaises(CanonicalProjectionError):
            validate(projection, artifact_store=self.store)

    def test_docx_resealed_derived_content_is_still_rejected(self):
        self.reject_changed_derived(self.make_docx, docx.validate_native_projection)

    def test_pdf_resealed_derived_content_is_still_rejected(self):
        self.reject_changed_derived(self.make_pdf, pdf.validate_native_pdf_projection)


if __name__ == '__main__':
    unittest.main(verbosity=2)
