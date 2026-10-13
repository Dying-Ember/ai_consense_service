"""Authored OOXML contract fixtures; not original-file gold or model acceptance.

Embedded bytes came from the opt-in Java observer on a synthetic package. Tests
exercise producer provenance/admission, never derive source reference truth from
a parser. Reference text is deliberately different to catch gold writeback.
"""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from canonical_projection import CanonicalProjectionError, _hash
from native_observation import capture_native_observation
from native_canonical_projection import ADOPTION_SCHEMA, produce_native, validate_native_projection
from token_windows import paragraph_map, utf16_length

from test_native_fixture import fixture_bytes


def fixture(store, *, adapter_suffix=b''):
    export_raw, source, code = fixture_bytes()
    if adapter_suffix:
        code += adapter_suffix
        export = json.loads(export_raw)
        export['observerIdentity']['adapterCodeSha256'] = hashlib.sha256(code).hexdigest()
        export['source']['sourceRevisionSha256'] = _hash({'sourceSha256': export['source']['sourceSha256'],
                                                         'observerIdentity': export['observerIdentity']})
        export_raw = json.dumps(export, ensure_ascii=False, sort_keys=True).encode()
    bundle = capture_native_observation(export_raw, source, code, artifact_store=store)
    nodes = bundle['nativeNodes']
    standalone = [node for node in nodes if node['kind'] == 'paragraph' and not node.get('parentTableId')]
    tables = bundle['tableInventory']
    outer = tables[0]
    table_paras = [node for node in nodes if node['kind'] == 'paragraph' and node.get('parentTableId')]
    table_root = next(node for node in nodes if node['nodeId'] == outer['tableId'])
    reference = {'schema': 'consense.source-review-reference.v2',
        'referenceVersion': 'AUTHORED-native-v2-contract-not-original-gold',
        'actor': 'codex', 'method': 'original_source_review', 'humanConfirmed': False,
        'sourceSha256': bundle['source']['sourceSha256'], 'sourceMediaType': bundle['source']['mediaType'],
        'physicalPages': [], 'pages': [], 'split': 'authored_contract_fixture',
        'semanticQuality': 'unknown', 'coverage': {'wholeSourceAcceptance': False},
        'unknowns': ['Effective rendering, headers, legal meaning and Q10 remain unknown'],
        'regions': [{'regionId': 'heading', 'text': 'INDEPENDENT REFERENCE MUST NEVER REPLACE CANDIDATE',
                     'sourceAnchor': copy.deepcopy(standalone[0]['sourcePosition']), 'role': 'heading', 'clauseId': 'A1'},
                    {'regionId': 'table', 'text': 'Independent table reference, not candidate text',
                     'sourceAnchor': copy.deepcopy(table_root['sourcePosition']), 'role': 'native_table', 'clauseId': 'A1'},
                    {'regionId': 'header', 'text': 'Independent header review',
                     'sourceAnchor': copy.deepcopy(standalone[-1]['sourcePosition']), 'role': 'header'}],
        'nativeTables': [{'tableId': 'review-table', 'sourceAnchor': copy.deepcopy(table_root['sourcePosition']),
            'completeNativeTable': True, 'rootTableIds': [outer['tableId']],
            'nativeTableIds': [table['tableId'] for table in tables],
            'nativeParagraphIds': [node['nodeId'] for node in table_paras]}],
        'relations': [{'type': 'parent', 'fromRegionId': 'table', 'toRegionId': 'heading'}]}
    reference_raw = json.dumps(reference, ensure_ascii=False, indent=2).encode()
    descriptor = store.put_bytes(reference_raw)
    manifest = {'schema': ADOPTION_SCHEMA, 'bundleSha256': bundle['bundleSha256'],
        'sourceSha256': bundle['source']['sourceSha256'], 'sourceRevisionSha256': bundle['source']['sourceRevisionSha256'],
        'documentId': 'AUTHORED-db-document', 'role': 'tender', 'module': 'vetting',
        'eligibleModules': ['vetting', 'advisory'], 'sourcePurpose': 'project_document', 'sourceClass': 'project_pre',
        'scope': {'requiredPartNames': ['word/document.xml', 'word/oddHeader7.xml'], 'scopePolicySha256': 'a' * 64},
        'quality': {'parseStatus': 'PARSED', 'semanticQuality': 'unknown', 'version': 'authored-unverified',
                    'notes': 'Accepted local observation units, never whole document acceptance'},
        'reference': {'actor': 'codex', 'method': 'original_source_review', 'sourceSha256': bundle['source']['sourceSha256'],
            'referenceVersion': reference['referenceVersion'], 'artifactDescriptor': descriptor,
            'evidenceSha256': descriptor['sha256']},
        'tableGroups': [{'tableGroupId': 'whole-native-table', 'referenceTableId': 'review-table',
            'rootTableIds': [outer['tableId']], 'nativeTableIds': [table['tableId'] for table in tables],
            'nativeParagraphIds': [node['nodeId'] for node in table_paras]}],
        'units': [{'unitId': 'heading', 'scope': 'region', 'nodeId': standalone[0]['nodeId'],
                   'textChannel': 'observedRaw', 'referenceRegionIds': ['heading'],
                   'reviewedAttributes': ['text', 'nativePosition', 'clauseHierarchy'],
                   'clause': {'clauseId': 'A1', 'headingUnitId': 'heading'}},
                  {'unitId': 'table', 'scope': 'table_group', 'tableGroupId': 'whole-native-table',
                   'textChannel': 'nonDeletedNonDirectStrike', 'referenceRegionIds': ['table'],
                   'reviewedAttributes': ['text', 'nativePosition', 'tableInventory', 'clauseHierarchy'],
                   'clause': {'clauseId': 'A1', 'headingUnitId': 'heading'}},
                  {'unitId': 'header', 'scope': 'region', 'nodeId': standalone[-1]['nodeId'],
                   'textChannel': 'observedRaw', 'referenceRegionIds': ['header'],
                   'reviewedAttributes': ['text', 'nativePosition']}],
        'relations': [{'type': 'parent', 'fromUnitId': 'table', 'toUnitId': 'heading', 'referenceRelationIndex': 0}],
        'assets': []}
    return bundle, manifest


class NativeCanonicalProjectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = ArtifactStore(self.temp.name)
        self.bundle, self.manifest = fixture(self.store)

    def produce(self):
        return produce_native(self.bundle, self.manifest, artifact_store=self.store)

    def rejects(self, code):
        with self.assertRaisesRegex(CanonicalProjectionError, code):
            self.produce()

    def reseal(self, result):
        result['projectionSha256'] = _hash({key: value for key, value in result.items() if key != 'projectionSha256'})

    def change_reference(self, operation):
        raw = self.store.read_asset(self.manifest['reference']['artifactDescriptor'])
        reference = json.loads(raw)
        operation(reference)
        raw = json.dumps(reference, ensure_ascii=False, indent=2).encode('utf8')
        descriptor = self.store.put_bytes(raw)
        self.manifest['reference']['artifactDescriptor'] = descriptor
        self.manifest['reference']['evidenceSha256'] = descriptor['sha256']

    def test_exact_raw_characters_utf16_channels_no_gold_writeback(self):
        before = copy.deepcopy((self.bundle, self.manifest))
        result = self.produce()
        self.assertEqual(before, (self.bundle, self.manifest))
        heading = result['chunks'][0]
        self.assertEqual(heading['content'], '  A\u00a0\U0001f600 \tB\nunless active struck  deleted ')
        self.assertNotIn('INDEPENDENT REFERENCE', heading['content'])
        part = heading['parts'][0]
        self.assertEqual(part['endOffset'], utf16_length(heading['content']))
        self.assertGreater(part['endOffset'], len(heading['content']))
        self.assertEqual(part['nativeObservation']['textChannels']['deleted'], ' deleted ')
        self.assertEqual(part['nativeObservation']['textChannels']['directStrike'], ' struck ')
        self.assertEqual(part['candidateProvenance']['rawObservation'], self.bundle['rawObservation'])
        paragraph_map(heading)

    def test_entire_nested_blank_merge_inventory_retained_paragraphs_once(self):
        result = self.produce()
        chunk = result['chunks'][1]
        self.assertEqual(chunk['content'].count('inner text \U0001f600'), 1)
        self.assertEqual(chunk['content'].count('outer text'), 1)
        self.assertEqual(len(chunk['parts']), 5)
        self.assertEqual(chunk['tableGroup']['nativeTables'], self.bundle['tableInventory'])
        outer, inner = chunk['tableGroup']['nativeTables']
        self.assertEqual(outer['rows'][0]['cells'][0]['gridSpanRaw']['val'], '2')
        self.assertEqual(outer['rows'][0]['gridBeforeRaw']['val'], 'invalid')
        self.assertEqual(inner['parentCellId'], outer['rows'][0]['cells'][0]['cellId'])
        self.assertTrue(any(part['text'] == '' for part in chunk['parts']))
        self.assertTrue(all(part['nativeObservation']['kind'] == 'paragraph' for part in chunk['parts']))

    def test_no_word_pages_or_boxes_q10_explicitly_unavailable(self):
        result = self.produce()
        self.assertEqual(result['assetRefs'], [])
        self.assertEqual(result['visualReturnStatus'], 'unavailable_pending_verified_rendition')
        for chunk in result['chunks']:
            self.assertEqual(chunk['physicalPages'], [])
            self.assertNotIn('pageNo', chunk)
            for part in chunk['parts']:
                self.assertTrue(part['native'])
                self.assertNotIn('physicalPageNo', part)
                self.assertNotIn('bbox', part)
                self.assertEqual(part['originalGeometryStatus'], 'not_defined_for_native_docx')
        header = result['chunks'][-1]['parts'][0]
        self.assertEqual(header['sourcePosition']['partName'], 'word/oddHeader7.xml')
        self.assertEqual(header['sourcePosition']['partSha256'], next(row['sha256'] for row in self.bundle['rawParts']
                                                                      if row['name'] == 'word/oddHeader7.xml'))

    def test_source_hash_revision_chunks_independently_recompute(self):
        result = self.produce()
        self.assertEqual(result['sourceHash'], _hash({'projectionIdentity': result['projectionIdentity'],
                                                    'canonical': result['canonical']}))
        revision = _hash({'sourceHash': result['sourceHash'], 'projectionIdentity': result['projectionIdentity']})
        self.assertEqual(result['projectionRevisionSha256'], revision)
        for chunk in result['chunks']:
            self.assertEqual(chunk['id'], 'canonical:' + _hash({'revision': revision, 'unitId': chunk['unitId']}))
        self.assertEqual(validate_native_projection(result, artifact_store=self.store), result)

    def test_changed_adapter_adoption_scope_role_module_and_quality_each_changes_identity(self):
        first = self.produce()
        cases = [('documentId', 'AUTHORED-other-id'), ('role', 'standard'), ('module', 'advisory')]
        for field, value in cases:
            with self.subTest(field=field):
                manifest = copy.deepcopy(self.manifest)
                manifest[field] = value
                changed = produce_native(self.bundle, manifest, artifact_store=self.store)
                self.assertNotEqual(first['sourceHash'], changed['sourceHash'])
                self.assertNotEqual(first['chunks'][0]['id'], changed['chunks'][0]['id'])
        for nested, field, value in [('scope', 'scopePolicySha256', 'b' * 64), ('quality', 'notes', 'still unknown revised note')]:
            manifest = copy.deepcopy(self.manifest)
            manifest[nested][field] = value
            self.assertNotEqual(first['sourceHash'], produce_native(self.bundle, manifest, artifact_store=self.store)['sourceHash'])
        bundle, manifest = fixture(self.store, adapter_suffix=b'\n// authored alternative adapter bytes\n')
        self.assertNotEqual(first['sourceHash'], produce_native(bundle, manifest, artifact_store=self.store)['sourceHash'])
        self.assertNotEqual(first['sourceRevisionSha256'] if 'sourceRevisionSha256' in first else first['source']['sourceRevisionSha256'],
                            bundle['source']['sourceRevisionSha256'])

    def test_stale_source_bundle_or_revision_refused(self):
        for key in ('sourceSha256', 'sourceRevisionSha256', 'bundleSha256'):
            with self.subTest(key=key):
                prior = self.manifest[key]
                self.manifest[key] = 'c' * 64
                self.rejects('stale_bundle_adoption|source_adoption_mismatch')
                self.manifest[key] = prior

    def test_excluded_classes_and_answers_never_enter_modules(self):
        for name in ('pricing_bq', 'pricing_sor', 'pricing_general_summary', 'works_related_pss', 'sl_nontechnical', 'evaluation_answer'):
            with self.subTest(name=name):
                self.manifest['sourceClass'] = name
                self.rejects('module_source_not_eligible')
        self.manifest['sourceClass'] = 'project_pre'
        self.manifest['sourcePurpose'] = 'evaluation_answer'
        self.rejects('module_source_not_eligible')

    def test_standard_template_drafting_only(self):
        self.manifest['sourcePurpose'] = 'standard_template'
        self.rejects('module_source_not_eligible')
        self.manifest['module'] = 'drafting'
        self.manifest['eligibleModules'] = ['drafting']
        self.assertEqual(self.produce()['projectionIdentity']['module'], 'drafting')
        self.manifest['eligibleModules'].append('advisory')
        self.rejects('module_source_not_eligible')

    def test_run_or_table_paragraph_cannot_be_region(self):
        for kind in ('run', 'table'):
            self.manifest['units'][0]['nodeId'] = next(node['nodeId'] for node in self.bundle['nativeNodes'] if node['kind'] == kind)
            self.rejects('table_or_run_cannot_be_native_region')
        self.manifest['units'][0]['nodeId'] = self.manifest['tableGroups'][0]['nativeParagraphIds'][0]
        self.rejects('table_or_run_cannot_be_native_region')

    def test_partial_table_group_or_repeated_nested_root_refused(self):
        group = self.manifest['tableGroups'][0]
        removed = group['nativeParagraphIds'].pop()
        self.rejects('partial_native_table_group')
        group['nativeParagraphIds'].append(removed)
        group['rootTableIds'].append(group['nativeTableIds'][1])
        self.rejects('native_table_root_must_be_whole_outer_table')

    def test_group_without_native_inventory_review_refused(self):
        self.manifest['units'][1]['reviewedAttributes'].remove('tableInventory')
        self.rejects('whole_native_table_group_review_required')

    def test_repeat_adoption_and_scope_omission_refused(self):
        row = copy.deepcopy(self.manifest['units'][1])
        row['unitId'] = 'duplicate-table'
        self.manifest['units'].append(row)
        self.rejects('whole_native_table_group_review_required')
        self.manifest['units'].pop()
        self.manifest['scope']['requiredPartNames'] = ['word/document.xml']
        self.rejects('native_adoption_outside_scope')

    def test_unknown_quality_cannot_be_upgraded(self):
        self.manifest['quality']['semanticQuality'] = 'verified'
        self.rejects('unknown_quality_cannot_be_upgraded')

    def test_gold_text_or_geometry_override_not_allowed(self):
        for field, value in [('text', 'repaired golden answer'), ('physicalPageNo', 1), ('bbox', [0, 0, 1, 1])]:
            self.manifest['units'][0][field] = value
            self.rejects('illegal_native_unit_adoption')
            del self.manifest['units'][0][field]

    def test_missing_channel_and_clause_guess_refused(self):
        self.manifest['units'][0]['textChannel'] = 'effective_candidate_invented'
        self.rejects('native_text_channel_unavailable')
        self.manifest['units'][0]['textChannel'] = 'observedRaw'
        for row in self.manifest['units'][:2]:
            row['clause']['clauseId'] = 'invented-A99'
        self.rejects('clause_id_not_source_reviewed')

    def test_source_reference_wrong_hash_or_claim_refused(self):
        self.manifest['reference']['evidenceSha256'] = 'd' * 64
        self.rejects('reference_artifact_sha_mismatch')
        self.manifest['reference']['evidenceSha256'] = self.manifest['reference']['artifactDescriptor']['sha256']
        self.manifest['reference']['referenceVersion'] = 'stale-version'
        self.rejects('reference_descriptor_claim_mismatch')

    def test_reference_relation_must_support_explicit_parent(self):
        self.manifest['relations'][0]['type'] = 'qualifier'
        self.rejects('native_relation_reference_mismatch')

    def test_raw_part_actual_bytes_change_fails_every_read(self):
        result = self.produce()
        descriptor = self.bundle['rawParts'][0]['artifactDescriptor']
        path = Path(self.temp.name) / descriptor['relativePath']
        path.write_bytes(b'changed captured part')
        with self.assertRaises(CanonicalProjectionError):
            validate_native_projection(result, artifact_store=self.store)

    def test_source_package_actual_bytes_change_refuses_read(self):
        result = self.produce()
        descriptor = self.bundle['sourcePackage']
        path = Path(self.temp.name) / descriptor['relativePath']
        path.write_bytes(b'changed original package')
        with self.assertRaises(CanonicalProjectionError):
            validate_native_projection(result, artifact_store=self.store)

    def test_resealed_declared_capture_metadata_cannot_override_retained_raw(self):
        for key in ('nativeNodes', 'rawParts', 'tableInventory', 'quality'):
            with self.subTest(key=key):
                bundle = copy.deepcopy(self.bundle)
                if key == 'nativeNodes':
                    bundle[key][0]['textChannels']['observedRaw'] = 'fabricated text'
                elif key == 'rawParts':
                    bundle[key].pop()
                elif key == 'tableInventory':
                    bundle[key][0]['rows'][0]['cells'].pop()
                else:
                    bundle[key]['effectiveRenderingStatus'] = 'verified'
                bundle['bundleSha256'] = _hash({name: value for name, value in bundle.items() if name != 'bundleSha256'})
                manifest = copy.deepcopy(self.manifest)
                manifest['bundleSha256'] = bundle['bundleSha256']
                with self.assertRaises(CanonicalProjectionError):
                    produce_native(bundle, manifest, artifact_store=self.store)

    def test_reference_native_table_partial_or_false_human_claim_refused(self):
        self.change_reference(lambda reference: reference['nativeTables'][0]['nativeParagraphIds'].pop())
        self.rejects('native_table_reference_coverage_missing|malformed_native_projection_input')
        self.bundle, self.manifest = fixture(self.store)
        self.change_reference(lambda reference: reference.update(humanConfirmed=True))
        self.rejects('native_reference_original_geometry_or_human_claim')

    def test_reference_exact_part_and_clark_path_required_even_for_same_text(self):
        def change(reference):
            reference['regions'][0]['sourceAnchor'] = copy.deepcopy(reference['regions'][-1]['sourceAnchor'])
        self.change_reference(change)
        self.rejects('native_reference_anchor_mismatch|malformed_native_projection_input')

    def test_unreviewed_native_asset_cannot_be_displayed(self):
        self.manifest['assets'] = [{'assetId': 'made-up-word-page', 'physicalPages': [1], 'mediaType': 'image/png'}]
        self.rejects('native_rendition_assets_not_yet_supported')

    def test_manifest_order_must_remain_actual_native_order(self):
        self.manifest['units'] = list(reversed(self.manifest['units']))
        self.rejects('native_adopted_unit_source_order_required')

    def test_explicit_channel_changes_identity_but_does_not_claim_effective_text(self):
        first = self.produce()
        self.manifest['units'][0]['textChannel'] = 'nonDeletedNonDirectStrike'
        changed = self.produce()
        heading = changed['chunks'][0]
        self.assertEqual(heading['content'], '  A\u00a0\U0001f600 \tB\nunless active')
        self.assertNotEqual(first['sourceHash'], changed['sourceHash'])
        self.assertEqual(heading['parts'][0]['candidateTextStatus'], 'native_observed_unverified')
        self.assertEqual(heading['parts'][0]['nativeObservation']['editingState']['effectiveCandidateStatus'],
                         'unknown_style_inheritance_and_visibility_not_resolved')
        self.assertFalse(changed['semanticScopeVerified'])

    def test_resealed_chunk_metadata_or_utf16_mutation_cannot_pass(self):
        result = self.produce()
        for field, value in [('module', 'advisory'), ('content', 'fake repaired value'), ('physicalPages', [3])]:
            changed = copy.deepcopy(result)
            changed['chunks'][0][field] = value
            self.reseal(changed)
            with self.assertRaisesRegex(CanonicalProjectionError, 'native_projection_derived_payload_mismatch'):
                validate_native_projection(changed)
        changed = copy.deepcopy(result)
        changed['chunks'][0]['parts'][0]['endOffset'] -= 1
        self.reseal(changed)
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_projection_derived_payload_mismatch'):
            validate_native_projection(changed)

    def test_v1_or_unknown_v2_version_does_not_pass(self):
        result = self.produce()
        for field, value in [('schema', 'consense.canonical-projection.v1'), ('producerKind', 'native_pdf')]:
            changed = copy.deepcopy(result)
            changed[field] = value
            self.reseal(changed)
            with self.assertRaisesRegex(CanonicalProjectionError, 'unsupported_native_projection_schema'):
                validate_native_projection(changed)
        result['projectionIdentity']['version'] = 'invented-next-version'
        self.reseal(result)
        with self.assertRaisesRegex(CanonicalProjectionError, 'native_projection_derived_payload_mismatch'):
            validate_native_projection(result)

    def test_producer_code_change_generates_new_identity(self):
        first = self.produce()
        original = Path.read_bytes
        def altered(path):
            raw = original(path)
            return raw + b'\n# authored code mutation\n' if path.name == 'native_canonical_projection.py' else raw
        with patch.object(Path, 'read_bytes', altered):
            changed = self.produce()
        self.assertNotEqual(first['projectionIdentity']['projectionCodeSha256'], changed['projectionIdentity']['projectionCodeSha256'])
        self.assertNotEqual(first['sourceHash'], changed['sourceHash'])


if __name__ == '__main__':
    unittest.main()
