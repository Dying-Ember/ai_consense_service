"""Caller-owned projection must not share mutable replay proof input."""
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError
import native_canonical_projection as docx
from test_native_canonical_projection import fixture


class DocxReplayOwnershipTest(unittest.TestCase):
    def test_caller_mutation_after_expected_seal_must_be_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ArtifactStore(directory)
            bundle, adoption = fixture(store)
            projection = docx.produce_native(bundle, adoption, artifact_store=store)
            assemble = docx._assemble

            def mutate_caller_after_expected_seal(*args, **kwargs):
                expected = assemble(*args, **kwargs)
                projection['nativeObservationBundle']['source']['sourceFilename'] += ' caller mutation during comparison'
                return expected

            with patch.object(docx, '_assemble', mutate_caller_after_expected_seal):
                with self.assertRaises(CanonicalProjectionError):
                    docx.validate_native_projection(projection, artifact_store=store)


if __name__ == '__main__':
    unittest.main(verbosity=2)
