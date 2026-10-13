"""Exact native reference addresses/membership, separate from semantic grading."""
import copy
import json
import unittest

from source_reference import SourceReferenceError, validate_source_reference
from test_native_fixture import fixture_bytes


def fixture_reference():
    raw, source, _ = fixture_bytes()
    export = json.loads(raw)
    nodes = export['nativeNodes']
    roots = [node for node in nodes if node['kind'] == 'table' and not node.get('parentTableId')]
    root = roots[0]
    root_path = root['sourcePosition']['nodePath']
    def inside(node):
        return node['sourcePosition']['partName'] == root['sourcePosition']['partName'] and (
            node['sourcePosition']['nodePath'] == root_path or node['sourcePosition']['nodePath'].startswith(root_path + '/'))
    reference = {'schema': 'consense.source-review-reference.v2', 'referenceVersion': 'authored-exact-native-anchor-test',
        'actor': 'codex', 'method': 'original_source_review', 'humanConfirmed': False,
        'split': 'authored_contract_fixture', 'sourceSha256': export['source']['sourceSha256'],
        'sourceMediaType': export['source']['mediaType'], 'physicalPages': [], 'pages': [], 'relations': [],
        'regions': [{'regionId': node['nodeId'], 'sourceAnchor': copy.deepcopy(node['sourcePosition']),
                     'text': node['textChannels']['observedRaw'], 'role': 'native_paragraph'}
                    for node in nodes if node['kind'] == 'paragraph'],
        'nativeTables': [{'tableId': 'reviewed-native-root', 'sourceAnchor': copy.deepcopy(root['sourcePosition']),
                          'completeNativeTable': True, 'rootTableIds': [root['nodeId']],
                          'nativeTableIds': [node['nodeId'] for node in nodes if node['kind'] == 'table' and inside(node)],
                          'nativeParagraphIds': [node['nodeId'] for node in nodes if node['kind'] == 'paragraph' and inside(node)]}]}
    return reference, source


class NativeSourceReferenceTest(unittest.TestCase):
    def setUp(self):
        self.reference, self.source = fixture_reference()

    def validate(self, reference=None, *, actual=True):
        return validate_source_reference(self.reference if reference is None else reference,
            evidence_bytes={self.reference['sourceSha256']: self.source} if actual else None)

    def rejected(self, operation):
        value = copy.deepcopy(self.reference)
        operation(value)
        with self.assertRaises(SourceReferenceError):
            self.validate(value)

    def test_actual_original_parts_header_and_nested_table_membership_are_verified(self):
        report = self.validate()
        details = report['nativeAnchorVerification']
        self.assertTrue(any(region['sourceAnchor']['partName'] != 'word/document.xml' for region in self.reference['regions']))
        self.assertTrue(all(row['status'] == 'verified_from_actual_original_package' for row in details.values()))
        self.assertEqual(details['nativeTables.reviewed-native-root']['nativeMembershipStatus'], 'complete_original_xml_roots_verified')
        self.assertEqual(report['semanticAcceptance'], 'not_evaluated')
        self.assertFalse(report['humanConfirmationVerified'])

    def test_missing_actual_bytes_are_unverified_and_not_completeness_success(self):
        details = self.validate(actual=False)['nativeAnchorVerification']
        self.assertTrue(all(row['status'] == 'typed_shape_validated_actual_source_unavailable' for row in details.values()))
        self.assertNotIn('nativeMembershipStatus', details['nativeTables.reviewed-native-root'])

    def test_typed_anchor_binds_exact_actual_part_bytes_and_xml_path(self):
        self.rejected(lambda value: value['regions'][0]['sourceAnchor'].__setitem__('partSha256', 'f' * 64))
        self.rejected(lambda value: value['regions'][0]['sourceAnchor'].__setitem__('nodePath', '/{x}document[1]/{x}p[1]'))
        self.rejected(lambda value: value['regions'][0]['sourceAnchor'].__setitem__('partName', '../word/document.xml'))

    def test_native_anchor_cannot_mix_word_pages_boxes_and_legacy_body_indices(self):
        for field, data in (('pageNo', 1), ('physicalPageNo', 1), ('bbox', [0, 0, 1, 1]), ('bodyElementIndex', 0)):
            self.rejected(lambda value, field=field, data=data: value['regions'][0]['sourceAnchor'].__setitem__(field, data))

    def test_entire_native_group_must_include_nested_tables_and_blank_paragraphs(self):
        self.rejected(lambda value: value['nativeTables'][0]['nativeTableIds'].pop())
        self.rejected(lambda value: value['nativeTables'][0]['nativeParagraphIds'].pop())
        self.rejected(lambda value: value['nativeTables'][0]['nativeParagraphIds'].append('invented'))

    def test_native_roots_cannot_overlap_and_anchor_must_identify_first_root(self):
        self.rejected(lambda value: value['nativeTables'][0]['rootTableIds'].append(value['nativeTables'][0]['nativeTableIds'][-1]))
        self.rejected(lambda value: value['nativeTables'][0].__setitem__('sourceAnchor', copy.deepcopy(value['regions'][0]['sourceAnchor'])))

    def test_native_table_requires_explicit_complete_root_declaration(self):
        self.rejected(lambda value: value['nativeTables'][0].__setitem__('completeNativeTable', False))
        self.rejected(lambda value: value['nativeTables'][0].__setitem__('rootTableIds', []))

    def test_reference_text_remains_independent_annotation_not_candidate_repair(self):
        value = copy.deepcopy(self.reference)
        value['regions'][0]['text'] = 'Independent annotation; never adopted candidate text'
        report = self.validate(value)
        self.assertEqual(report['semanticAcceptance'], 'not_evaluated')
        self.assertTrue(any(row['path'].endswith('nativeTextSemantics') for row in report['unknowns']))


if __name__ == '__main__':
    unittest.main()
