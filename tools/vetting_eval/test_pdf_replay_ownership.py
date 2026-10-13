"""Actual-CAS replay roots are private; input mutation cannot share proof state."""
import ast
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _bytes
import native_pdf_canonical_projection as pdf
from test_native_pdf_canonical_projection import fixture


def mutable_ids(value):
    result = set()
    if isinstance(value, dict):
        result.add(id(value))
        for child in value.values():
            result.update(mutable_ids(child))
    elif isinstance(value, list):
        result.add(id(value))
        for child in value:
            result.update(mutable_ids(child))
    return result


class PdfReplayOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.store = ArtifactStore(self.temporary.name)
        native, dual, adoption = fixture(self.store)
        self.projection = pdf.produce_native_pdf(native, dual, adoption, artifact_store=self.store)

    def test_native_caller_mutation_after_expected_seal_is_rejected(self):
        assemble = pdf._assemble

        def mutate_after_expected_seal(*args, **kwargs):
            expected = assemble(*args, **kwargs)
            self.projection['nativeObservationBundle']['source']['filename'] += ' changed caller'
            return expected

        with patch.object(pdf, '_assemble', mutate_after_expected_seal):
            with self.assertRaises(CanonicalProjectionError):
                pdf.validate_native_pdf_projection(self.projection, artifact_store=self.store)

    def test_dual_caller_mutation_after_expected_seal_is_rejected(self):
        assemble = pdf._assemble

        def mutate_after_expected_seal(*args, **kwargs):
            expected = assemble(*args, **kwargs)
            self.projection['dualParserBundle']['qualityStatus'] += ' changed caller'
            return expected

        with patch.object(pdf, '_assemble', mutate_after_expected_seal):
            with self.assertRaises(CanonicalProjectionError):
                pdf.validate_native_pdf_projection(self.projection, artifact_store=self.store)

    def test_every_replay_has_disjoint_complete_mutable_inputs(self):
        assemble = pdf._assemble
        roots = []

        def record_private(*args, **kwargs):
            roots.append(args[:3])
            return assemble(*args, **kwargs)

        before = _bytes(self.projection)
        with patch.object(pdf, '_assemble', record_private):
            for _ in range(2):
                self.assertIs(pdf.validate_native_pdf_projection(self.projection, artifact_store=self.store), self.projection)
        caller_ids = mutable_ids(self.projection)
        self.assertEqual(2, len(roots))
        ids = []
        for replay in roots:
            for root in replay:
                private_ids = mutable_ids(root)
                self.assertTrue(private_ids)
                self.assertFalse(private_ids & caller_ids)
                for prior in ids:
                    self.assertFalse(private_ids & prior)
                ids.append(private_ids)
        roots[0][0]['source']['filename'] = 'discarded private native'
        roots[0][1]['qualityStatus'] = 'discarded private dual'
        self.assertEqual(before, _bytes(self.projection))
        self.assertIs(pdf.validate_native_pdf_projection(self.projection, artifact_store=self.store), self.projection)

    def test_replay_failure_has_no_state_that_changes_later_producer_defaults(self):
        assemble = pdf._assemble
        with patch.object(pdf, '_assemble', side_effect=RuntimeError('owned replay failure')):
            with self.assertRaises(RuntimeError):
                pdf.validate_native_pdf_projection(self.projection, artifact_store=self.store)
        native, dual, adoption = fixture(self.store)
        result = pdf.produce_native_pdf(native, dual, adoption, artifact_store=self.store)
        self.assertIsNot(result['nativeObservationBundle'], native)
        self.assertIsNot(result['dualParserBundle'], dual)
        self.assertIs(assemble, pdf._assemble)

    def test_only_fresh_decode_validator_callsite_enables_replay(self):
        tree = ast.parse(Path(pdf.__file__).read_text(encoding='utf-8'))
        callers = []
        for function in [node for node in tree.body if isinstance(node, ast.FunctionDef)]:
            for node in ast.walk(function):
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == '_assemble':
                    if any(keyword.arg == '_validation_replay' and isinstance(keyword.value, ast.Constant)
                           and keyword.value.value is True for keyword in node.keywords):
                        callers.append(function.name)
        self.assertEqual(['validate_native_pdf_projection'], callers)


if __name__ == '__main__':
    unittest.main(verbosity=2)
