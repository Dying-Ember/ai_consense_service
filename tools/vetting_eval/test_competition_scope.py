"""Offline page-scope contracts; no model acceptance or index writes.

Use the original 4300-page hierarchy boundaries with deliberately different
source identity. No fixture text serves as OCR truth or library semantics.
"""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from competition_scope import CompetitionScopeError, load_policy


SOURCE_SHA = '1' * 64
TECHNICAL_STARTS = [1127, 1856, 2152, 2603, 2659, 2758, 2831, 3912, 4139]


def manifest():
    value = {'schema': 'consense.sl-library-scope.v1', 'scopeVersion': 'AUTHORED-scope-contract-v1',
             'source': {'sourceSha256': SOURCE_SHA, 'pageCount': 4300},
             'pageNumbering': {'basis': 'original_pdf_physical_page', 'indexBase': 1, 'rangeEndsInclusive': True},
             'includedPageSpans': [{'physicalPageStart': 1127, 'physicalPageEnd': 4300}],
             'metadataSpans': [{'physicalPageStart': 1, 'physicalPageEnd': 21}],
             'excludedSpans': [{'physicalPageStart': 22, 'physicalPageEnd': 1126}]}
    seal(value)
    return value


def seal(value):
    body = {key: part for key, part in value.items() if key != 'manifestContentSha256'}
    value['manifestContentSha256'] = hashlib.sha256(json.dumps(body, ensure_ascii=False, sort_keys=True,
                                                             separators=(',', ':')).encode('utf-8')).hexdigest()


def sl(page, *, chunk_id='sl', **fields):
    return {'id': chunk_id, 'fileKey': 'SL', 'sourceHash': 'f' * 64,
            'originalSourceSha256': SOURCE_SHA, 'pageNo': page,
            'content': 'General requirements — APE/APH; BQ reference; must not erase this text.  ', **fields}


class CompetitionScopeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'scope.json'
        self.write(manifest())
        self.policy = load_policy(self.path)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, value):
        self.path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

    def assert_error(self, code, chunks):
        with self.assertRaises(CompetitionScopeError) as captured:
            self.policy.apply(chunks)
        self.assertEqual(code, captured.exception.code)

    def test_actual_library_boundaries_and_risk_pages_route_by_page_not_words(self):
        pages = [1, 21, 22, 492, 1126, 1127, 1329, *TECHNICAL_STARTS[1:], 3437, 3438, 4300]
        chunks = [sl(page, chunk_id=f'page-{page}') for page in pages]
        before = copy.deepcopy(chunks)
        result = self.policy.apply(chunks)
        self.assertEqual([page for page in pages if page >= 1127], [chunk['pageNo'] for chunk in result['included']])
        self.assertEqual([1, 21, 22, 492, 1126], [trace['physicalPages'][0] for trace in result['excluded']])
        self.assertEqual(before, chunks)
        self.assertTrue(all(any(value is original for original in chunks) for value in result['included']))
        self.assertEqual([], result['unclassified'])

    def test_declared_classes_exclude_files_and_keep_project_pre(self):
        classes = ['pricing_bq', 'pricing_sor', 'pricing_general_summary', 'works_related_pss', 'sl_nontechnical', 'project_pre']
        chunks = [{'id': label, 'documentClass': label, 'content': 'Ordinary original text'} for label in classes]
        result = self.policy.apply(chunks)
        self.assertEqual(['project_pre'], [chunk['id'] for chunk in result['included']])
        self.assertEqual(classes[:-1], [trace['chunkId'] for trace in result['excluded']])
        self.assertEqual([], result['unclassified'])

    def test_ntt_sct_references_to_excluded_documents_never_classify_the_source(self):
        chunks = [{'id': key, 'fileKey': key, 'content': 'Refer to BQ, General Summary, PRE and works-related PSS.'}
                  for key in ('NTT', 'SCT', 'SCC')]
        result = self.policy.apply(chunks)
        self.assertEqual(chunks, result['included'])
        self.assertEqual([], result['excluded'])
        self.assertEqual(['NTT', 'SCT', 'SCC'], [trace['fileKey'] for trace in result['unclassified']])

    def test_unknown_ordinary_sources_remain_explicitly_unclassified(self):
        ordinary = {'id': 'unknown', 'sourceClass': 'new_correspondence_type', 'content': 'No excluded files present'}
        result = self.policy.apply([ordinary])
        self.assertIs(ordinary, result['included'][0])
        self.assertEqual('unclassified_retained', result['unclassified'][0]['scopeStatus'])
        self.assertEqual([], result['excluded'])

    def test_standard_contract_templates_are_drafting_only_project_versions_remain(self):
        for module in ('vetting', 'advisory'):
            with self.subTest(module=module):
                templates = [{'id': key, 'fileKey': key, 'sourcePurpose': 'standard_template',
                              'eligibleModules': ['drafting'], 'content': 'Untouched template'}
                             for key in ('NTT', 'SCT', 'SCC')]
                project = {'id': 'project-scc', 'fileKey': 'SCC', 'sourcePurpose': 'project_document',
                           'eligibleModules': ['drafting', 'vetting', 'advisory'], 'content': 'Actual project terms'}
                before = copy.deepcopy(templates + [project])
                result = self.policy.apply(templates + [project], module=module)
                self.assertEqual([project], result['included'])
                self.assertEqual(['standard_template_drafting_only'] * 3,
                                 [row['reason'] for row in result['excluded']])
                self.assertEqual(before, templates + [project])
                self.assertEqual(templates, self.policy.apply(templates, module='drafting')['included'])

    def test_category_does_not_exclude_gcc_and_unknown_filename_does_not_prove_template(self):
        gcc = {'id': 'gcc', 'fileKey': 'GCC', 'category': 'STANDARD_TEMPLATE', 'content': 'General terms'}
        unknown = {'id': 'new', 'fileKey': 'NTT', 'fileName': 'NTT standard template.docx', 'content': 'Unknown'}
        template = {'id': 'template', 'fileKey': 'NTT', 'category': 'STANDARD_TEMPLATE', 'content': 'Template'}
        result = self.policy.apply([gcc, unknown, template])
        self.assertEqual([gcc, unknown], result['included'])
        self.assertEqual('standard_template_drafting_only', result['excluded'][0]['reason'])
        self.assertEqual(['gcc', 'new'], [row['chunkId'] for row in result['unclassified']])

    def test_evaluation_answers_are_isolated_and_module_membership_is_enforced(self):
        answer = {'id': 'answer', 'sourcePurpose': 'evaluation_answer', 'eligibleModules': ['vetting'],
                  'content': 'Evaluation only'}
        restricted = {'id': 'restricted', 'sourcePurpose': 'project_document', 'eligibleModules': ['advisory'],
                      'content': 'Original'}
        result = self.policy.apply([answer, restricted])
        self.assertEqual([], result['included'])
        self.assertEqual(['evaluation_answer_isolated', 'module_not_eligible'],
                         [row['reason'] for row in result['excluded']])
        self.assertEqual([restricted], self.policy.apply([restricted], module='advisory')['included'])

    def test_invalid_or_conflicting_module_identity_is_rejected(self):
        for fields, code in (({'sourcePurpose': {}}, 'invalid_source_purpose'),
                             ({'eligibleModules': 'vetting'}, 'invalid_eligible_modules'),
                             ({'eligibleModules': ['vetting', 'vetting']}, 'invalid_eligible_modules'),
                             ({'eligibleModules': ['review']}, 'invalid_eligible_modules'),
                             ({'fileKey': 'NTT', 'category': 'STANDARD_TEMPLATE',
                               'sourcePurpose': 'project_document'}, 'conflicting_declared_source_purpose')):
            with self.subTest(fields=fields):
                self.assert_error(code, [{'id': 'invalid', 'content': 'Original', **fields}])

    def test_changed_or_missing_sl_sha_cannot_use_this_edition_page_numbers(self):
        self.assert_error('sl_source_hash_does_not_match_manifest', [sl(1127, originalSourceSha256='2' * 64)])
        self.assert_error('sl_original_source_identity_missing', [sl(1127, originalSourceSha256=None)])
        self.assert_error('sl_source_hash_does_not_match_manifest', [sl(22, originalSourceSha256='2' * 64, sourceClass='sl_nontechnical')])

    def test_canonical_content_hash_never_substitutes_for_original_file_sha(self):
        source = sl(1127)
        self.assertIs(source, self.policy.apply([source])['included'][0])
        source.pop('originalSourceSha256')
        source['sourceHash'] = SOURCE_SHA
        self.assert_error('sl_original_source_identity_missing', [source])
        source['originalHashMeaning'] = 'original_file_bytes_sha256'
        self.assertIs(source, self.policy.apply([source])['included'][0])
        source['originalHashMeaning'] = 'canonical_text_and_structure'
        self.assert_error('sl_original_source_identity_missing', [source])

    def test_original_sha_aliases_must_agree_and_are_separate_from_content_identity(self):
        source = sl(1127, originalSourceSha256=None, sourceSha256=SOURCE_SHA)
        self.assertIs(source, self.policy.apply([source])['included'][0])
        self.assert_error('sl_original_source_identity_conflict', [sl(1127, sourceSha256='2' * 64)])
        self.assert_error('sl_original_source_hash_invalid', [sl(1127, originalSourceSha256='not-a-sha')])

    def test_sl_class_without_sl_filekey_still_requires_exact_sha_and_physical_page(self):
        source = sl(1127, fileKey='renamed_library', sourceClass='specification_library')
        self.assertIs(source, self.policy.apply([source])['included'][0])
        source.pop('pageNo')
        self.assert_error('sl_physical_page_mapping_missing', [source])

    def test_missing_printed_or_invalid_page_never_infers_a_physical_page(self):
        for page in (None, 'CLA/1', 'P1126-1127', 0, True, 1127.0):
            with self.subTest(page=page):
                code = 'sl_physical_page_mapping_missing' if page is None else 'physical_page_number_missing_or_invalid'
                self.assert_error(code, [sl(page, anchor='pdf-page/1127')])
        self.assert_error('sl_physical_page_outside_source', [sl(4301)])
        source = sl('P1127')
        self.assertIs(source, self.policy.apply([source])['included'][0])

    def test_mixed_scope_chunk_requires_upstream_split_and_retains_entire_content(self):
        source = sl(1126, parts=[{'pageNo': 1126, 'text': 'excluded'}, {'pageNo': 1127, 'text': 'included'}])
        before = copy.deepcopy(source)
        self.assert_error('mixed_scope_chunk_requires_upstream_split', [source])
        self.assertEqual(before, source)
        self.assert_error('mixed_scope_chunk_requires_upstream_split', [sl(21, parts=[{'pageNo': 21}, {'pageNo': 22}])])

    def test_all_parts_must_be_page_mapped_and_bound_to_same_source(self):
        self.assert_error('physical_page_number_missing_or_invalid', [sl(1127, parts=[{'pageNo': 1127}, {'text': 'unmapped'}])])
        self.assert_error('sl_part_original_source_hash_conflict', [sl(1127, parts=[{'pageNo': 1127, 'originalSourceSha256': '2' * 64}])])
        self.assert_error('sl_part_canonical_source_hash_conflict', [sl(1127, parts=[{'pageNo': 1127, 'sourceHash': '2' * 64}])])
        self.assert_error('sl_part_document_identity_conflict', [sl(1127, documentId=1, parts=[{'pageNo': 1127, 'documentId': 2}])])
        self.assert_error('sl_chunk_page_conflicts_with_parts', [sl(1126, parts=[{'pageNo': 1127}])])
        source = sl(1855, parts=[{'pageNo': 1855}, {'pageNo': 1856}])
        self.assertIs(source, self.policy.apply([source])['included'][0])

    def test_contradictory_declared_scope_is_unknown_not_a_silent_override(self):
        self.assert_error('conflicting_declared_source_scope', [{'id': 'pre', 'sourceClass': 'works_related_pss',
                          'documentClass': 'project_pre', 'content': 'do not silently drop project PRE'}])

    def test_manifest_file_and_content_pin_both_reject_drift(self):
        pinned = hashlib.sha256(self.path.read_bytes()).hexdigest()
        self.assertEqual(pinned, load_policy(self.path, pinned).manifest_sha256)
        with self.assertRaisesRegex(CompetitionScopeError, 'file_sha256_mismatch'):
            load_policy(self.path, '9' * 64)
        changed = manifest(); changed['source']['sourceSha256'] = '2' * 64
        self.write(changed)
        with self.assertRaisesRegex(CompetitionScopeError, 'content_sha256_mismatch'):
            load_policy(self.path)

    def test_manifest_gap_overlap_library_and_explicit_page_conflicts_rejected(self):
        for code, mutate in (
            ('scope_manifest_page_partition_gap', lambda value: value['includedPageSpans'][0].update(physicalPageStart=1128)),
            ('scope_manifest_page_span_overlap', lambda value: value['metadataSpans'][0].update(physicalPageEnd=22)),
            ('scope_manifest_library_span_mismatch', lambda value: value.update(includedLibraries=[{'physicalPageStart': 1127, 'physicalPageEnd': 4299}])),
            ('scope_manifest_explicit_pages_mismatch', lambda value: value.update(includedPhysicalPages=[1127])),
        ):
            with self.subTest(code=code):
                value = manifest(); mutate(value); seal(value); self.write(value)
                with self.assertRaises(CompetitionScopeError) as captured:
                    load_policy(self.path)
                self.assertEqual(code, captured.exception.code)

    def test_unresolved_or_nonphysical_manifest_cannot_authorize_scope(self):
        for code, mutate in (
            ('scope_manifest_library_boundaries_unresolved', lambda value: value.update(unknownLibraryBoundaries=['undetermined'])),
            ('scope_manifest_physical_page_numbering_required', lambda value: value['pageNumbering'].update(basis='printed_page_label')),
            ('scope_manifest_physical_page_numbering_required', lambda value: value['pageNumbering'].update(indexBase=True)),
        ):
            with self.subTest(code=code):
                value = manifest(); mutate(value); seal(value); self.write(value)
                with self.assertRaises(CompetitionScopeError) as captured:
                    load_policy(self.path)
                self.assertEqual(code, captured.exception.code)

    def test_no_partial_result_when_later_sl_chunk_is_unclassifiable(self):
        chunks = [{'id': 'ordinary', 'content': 'original retained'}, sl(1127, originalSourceSha256=None)]
        before = copy.deepcopy(chunks)
        self.assert_error('sl_original_source_identity_missing', chunks)
        self.assertEqual(before, chunks)

    def test_identity_result_cannot_mutate_the_loaded_policy(self):
        identity = self.policy.policyIdentity
        identity['slIncludedPageSpans'][0]['physicalPageStart'] = 1
        self.assertEqual(1127, self.policy.policyIdentity['slIncludedPageSpans'][0]['physicalPageStart'])
        with self.assertRaises(AttributeError):
            self.policy.source_sha256 = '3' * 64


if __name__ == '__main__':
    unittest.main()
