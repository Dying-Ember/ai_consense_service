"""Integrity/membership mutations of a real Java capture of authored OOXML.

No official answers, OCR model, rendering or semantic accuracy assertions.
"""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from artifact_store import ArtifactStore
from native_observation import (NativeObservationError, WORD_NS, STRICT_WORD_NS,
    _bytes, _sha, _tree, _local, capture_native_observation, native_node_id,
    validate_native_bundle, validate_native_export)
from test_native_fixture import fixture_bytes


class NativeObservationTest(unittest.TestCase):
    def setUp(self):
        self.raw, self.source, self.adapter = fixture_bytes()
        self.export = json.loads(self.raw)

    def check(self, value=None):
        return validate_native_export(self.export if value is None else value, self.source, self.adapter)

    def rejected(self, operation):
        value = copy.deepcopy(self.export)
        operation(value)
        with self.assertRaises(NativeObservationError):
            self.check(value)

    def token(self, export):
        return next(node for node in export['nativeNodes'] if node['kind'] == 'paragraph' and node['tokens'])['tokens'][0]

    def test_actual_java_capture_checks_bytes_tokens_all_stories_and_full_table_inventory(self):
        report = self.check()
        for field in ('originalBytesVerified', 'nativePositionsVerified', 'nativeTokenChannelsVerified',
                      'nativeInventoryMembershipVerified', 'observerRevisionVerified'):
            self.assertTrue(report[field])
        self.assertFalse(report['displayedTextVerified'])
        self.assertFalse(report['humanConfirmed'])
        self.assertEqual(report['semanticQuality'], 'unknown')
        self.assertEqual(report['tableCount'], 2)

    def test_original_source_and_actual_adapter_bytes_must_match(self):
        for source, adapter in ((self.source + b'changed', self.adapter), (self.source, self.adapter + b'changed')):
            with self.assertRaises(NativeObservationError):
                validate_native_export(self.export, source, adapter)

    def test_revision_and_resolved_config_cannot_be_restamped_arbitrarily(self):
        self.rejected(lambda value: value['source'].__setitem__('sourceRevisionSha256', 'a' * 64))
        self.rejected(lambda value: value['observerIdentity']['config'].__setitem__('externalResources', 'fetched'))

    def test_exact_node_id_includes_part_name_even_for_identical_part_bytes(self):
        args = ('paragraph', 'a' * 64, 'b' * 64, '/{x}hdr[1]/{x}p[1]')
        self.assertNotEqual(native_node_id(*args, part_name='word/header1.xml'), native_node_id(*args, part_name='word/header2.xml'))
        self.rejected(lambda value: value['nativeNodes'][0].__setitem__('nodeId', 'native-' + 'a' * 64))

    def test_invented_word_page_or_bbox_is_rejected(self):
        self.rejected(lambda value: value['nativeNodes'][0]['sourcePosition'].__setitem__('pageNo', 1))
        self.rejected(lambda value: value['nativeNodes'][0].__setitem__('bbox', [0, 0, 1, 1]))

    def test_changed_package_part_bytes_and_forged_media_type_are_rejected(self):
        self.rejected(lambda value: value['rawParts'][0].__setitem__('bytesBase64', 'Zg=='))
        self.rejected(lambda value: value['rawParts'][0].__setitem__('mediaType', 'application/pdf'))

    def test_fake_native_position_and_missing_node_fail_actual_xml_membership(self):
        self.rejected(lambda value: value['nativeNodes'][0]['sourcePosition'].__setitem__('nodePath', '/{x}document[99]'))
        self.rejected(lambda value: value['nativeNodes'].pop())

    def test_literal_control_or_token_sequence_cannot_be_rewritten(self):
        self.rejected(lambda value: self.token(value).__setitem__('rawDomText', 'normalized evidence'))
        self.rejected(lambda value: self.token(value).__setitem__('encodedText', 'normalized evidence'))
        self.rejected(lambda value: next(node for node in value['nativeNodes'] if node['tokens'])['tokens'].pop())

    def test_utf16_offset_and_boolean_inclusion_types_are_checked(self):
        self.rejected(lambda value: self.token(value)['channelOffsets']['observedRaw'].__setitem__('end', 999))
        self.rejected(lambda value: self.token(value)['channelOffsets']['observedRaw'].__setitem__('start', False))
        self.rejected(lambda value: self.token(value)['channelOffsets']['observedRaw'].__setitem__('included', 1))
        emoji_token = next(token for node in self.export['nativeNodes'] for token in node['tokens'] if '\U0001f600' in token['encodedText'])
        text = emoji_token['encodedText']
        offsets = emoji_token['channelOffsets']['observedRaw']
        self.assertEqual(offsets['end'] - offsets['start'], len(text.encode('utf-16-le')) // 2)
        self.assertGreater(len(text.encode('utf-16-le')) // 2, len(text))

    def test_deleted_and_direct_strike_channels_do_not_become_effective_contract_text(self):
        paragraphs = [node for node in self.export['nativeNodes'] if node['kind'] == 'paragraph']
        self.assertTrue(any(node['textChannels']['deleted'] for node in paragraphs))
        self.assertTrue(any(node['textChannels']['directStrike'] for node in paragraphs))
        self.rejected(lambda value: next(token for node in value['nativeNodes'] for token in node['tokens'] if token['deletedOrMovedFrom']).__setitem__('deletedOrMovedFrom', False))
        self.rejected(lambda value: next(token for node in value['nativeNodes'] for token in node['tokens'] if token['directStruck']).__setitem__('directStruck', False))
        self.rejected(lambda value: self.token(value).__setitem__('effectiveRenderingStatus', 'confirmed'))

    def test_channel_concatenation_is_derived_from_exact_tokens(self):
        self.rejected(lambda value: value['nativeNodes'][0]['textChannels'].__setitem__('observedRaw', 'corrected spelling'))
        self.rejected(lambda value: value['nativeNodes'][0]['textChannels'].__setitem__('deleted', 'invented deletion'))

    def test_missing_blank_cell_or_nested_table_inventory_is_rejected(self):
        self.rejected(lambda value: value['tableInventory'][0]['rows'][0]['cells'].pop())
        self.rejected(lambda value: value['tableInventory'][0].__setitem__('nestedTableIds', []))
        self.rejected(lambda value: value['tableInventory'].pop())

    def test_row_membership_and_native_merge_property_are_bound_to_original_xml(self):
        self.rejected(lambda value: value['tableInventory'][0]['rows'][0].__setitem__('rowId', value['tableInventory'][1]['rows'][0]['rowId']))
        self.rejected(lambda value: value['tableInventory'][0]['rows'][0]['cells'][0]['gridSpanRaw'].__setitem__('val', '99'))

    def test_missing_header_story_and_invented_parent_relation_are_rejected(self):
        self.rejected(lambda value: value['storyInventory'].pop())
        self.rejected(lambda value: next(node for node in value['nativeNodes'] if node.get('parentCellId')).__setitem__('parentCellId', 'invented'))

    def test_captured_bundle_surfaces_quality_stories_and_retains_all_actual_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            store = ArtifactStore(root)
            bundle = capture_native_observation(self.raw, self.source, self.adapter, artifact_store=store)
            self.assertEqual(store.read_asset(bundle['sourcePackage']), self.source)
            self.assertEqual(store.read_asset(bundle['rawObservation']), self.raw)
            self.assertEqual(bundle['quality'], self.export['quality'])
            self.assertEqual(bundle['limitations'], self.export['quality']['limitations'])
            self.assertEqual(bundle['storyInventory'], self.export['storyInventory'])
            self.assertEqual(validate_native_bundle(bundle, artifact_store=store), bundle['validation'])

    def test_resealed_bundle_still_checks_retained_raw_and_cas_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            store = ArtifactStore(root)
            bundle = capture_native_observation(self.raw, self.source, self.adapter, artifact_store=store)
            changed = copy.deepcopy(bundle)
            changed['nativeNodes'][0]['textChannels']['observedRaw'] = 'different text'
            changed['bundleSha256'] = _sha(_bytes({key: value for key, value in changed.items() if key != 'bundleSha256'}))
            with self.assertRaises(NativeObservationError):
                validate_native_bundle(changed, artifact_store=store)
            descriptor = bundle['sourcePackage']
            (Path(root) / descriptor['relativePath']).write_bytes(b'changed stored source')
            with self.assertRaises(NativeObservationError):
                validate_native_bundle(bundle, artifact_store=store)

    def test_unknown_quality_cannot_be_promoted_to_human_or_rendered_acceptance(self):
        self.rejected(lambda value: value['quality'].__setitem__('humanConfirmed', True))
        self.rejected(lambda value: value['quality'].__setitem__('effectiveRenderingStatus', 'confirmed'))

    def test_utf16_dtd_and_internal_entity_documents_are_rejected(self):
        xml = '<!DOCTYPE x [<!ENTITY y "expanded">]><x>&y;</x>'
        for encoding in ('utf-8', 'utf-16'):
            with self.assertRaises(NativeObservationError):
                _tree(xml.encode(encoding))

    def test_strict_word_namespace_is_recognized_without_fabricating_geometry(self):
        raw = ('<w:document xmlns:w="' + STRICT_WORD_NS + '"><w:body><w:p><w:r><w:t>x</w:t></w:r></w:p></w:body></w:document>').encode()
        positions, _ = _tree(raw)
        self.assertEqual([_local(node) for node in positions.values()], ['document', 'body', 'p', 'r', 't'])

    def test_malformed_json_and_bundle_shapes_fail_with_named_errors(self):
        with tempfile.TemporaryDirectory() as root:
            store = ArtifactStore(root)
            for raw in (b'{"schema":1,"schema":2}', b'{"number":NaN}', b'[]'):
                with self.assertRaises(NativeObservationError):
                    capture_native_observation(raw, self.source, self.adapter, artifact_store=store)
            with self.assertRaises(NativeObservationError):
                validate_native_bundle({'schema': 'consense.native-observation-bundle.v1'}, artifact_store=store)


if __name__ == '__main__':
    unittest.main()
