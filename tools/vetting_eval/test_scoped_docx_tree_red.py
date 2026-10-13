"""Repeated anchors reuse one actual part tree within one source validation."""
import copy
import unittest
from unittest.mock import patch
import native_observation
from test_native_source_reference import fixture_reference
from source_reference import validate_source_reference, SourceReferenceError


class ScopedDocxTreeTests(unittest.TestCase):
    def test_repeated_region_and_table_anchors_reuse_only_actual_same_part_tree(self):
        reference, source = fixture_reference()
        part_names = {r['sourceAnchor']['partName'] for r in reference['regions']}
        with patch('native_observation._tree', wraps=native_observation._tree) as tree:
            report = validate_source_reference(reference, evidence_bytes={reference['sourceSha256']: source})
            self.assertEqual(tree.call_count, len(part_names))
            self.assertFalse(report['unknownsUpgraded'])
            self.assertEqual(report['semanticAcceptance'], 'not_evaluated')
            self.assertFalse(report['humanConfirmationVerified'])
            validate_source_reference(reference, evidence_bytes={reference['sourceSha256']: source})
            self.assertEqual(tree.call_count, 2 * len(part_names))

    def test_cached_part_does_not_certify_wrong_digest_path_or_fresh_source_bytes(self):
        reference, source = fixture_reference()
        validate_source_reference(reference, evidence_bytes={reference['sourceSha256']: source})
        for mutation in ('digest', 'path', 'bytes'):
            changed = copy.deepcopy(reference)
            data = source
            if mutation == 'digest': changed['regions'][-1]['sourceAnchor']['partSha256'] = 'f' * 64
            elif mutation == 'path': changed['regions'][-1]['sourceAnchor']['nodePath'] = '/{x}document[1]/{x}p[1]'
            else: data = b'changed source'
            with self.subTest(mutation=mutation), self.assertRaises(SourceReferenceError):
                validate_source_reference(changed, evidence_bytes={reference['sourceSha256']: data})

    def test_no_actual_source_stays_unverified_despite_previous_real_validation(self):
        reference, source = fixture_reference()
        validate_source_reference(reference, evidence_bytes={reference['sourceSha256']: source})
        report = validate_source_reference(reference)
        self.assertTrue(all(r['status'] == 'typed_shape_validated_actual_source_unavailable'
                            for r in report['nativeAnchorVerification'].values()))


if __name__ == '__main__': unittest.main(verbosity=2)
