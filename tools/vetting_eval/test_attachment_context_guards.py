"""Source-bound negative contracts and input wiring; no models/HTTP/training."""
import copy
import unittest

from test_explicit_margin_context_red import corpus, node, context_for, CharacterTokenizer
from retrieval_source_units import SourceUnits, select_source_units, ATTACHMENT_POLICY, OBSERVED_POLICY, POLICY
from structural_retrieval import _catalog, plan, compare, StructuralRecipe, COMBINED_CONTEXT_POLICY
from token_windows import WindowRecipe, WindowError, digest, utf16_length, verify_window_coverage, windows


def native_node(identity, text):
    p = node(identity, text, 'block:' + identity)
    p.update(producerKind='native_pdf', canonicalScope='whole_text_unit', scopeSha256='f' * 64,
             referenceSha256='e' * 64)
    p['adoption'] = {'scope': 'whole_text_unit', 'unitId': p['unitId'], 'regionIds': ['region:' + identity]}
    p['parts'][0]['referenceRegionId'] = 'region:' + identity
    return p


def native_edge(note, target, kind='margin_note'):
    return {'type': kind, 'scope': 'between_whole_units', 'fromUnitId': note['unitId'], 'toUnitId': target['unitId'],
            'fromRegionId': note['adoption']['regionIds'][0], 'toRegionId': target['adoption']['regionIds'][0],
            'status': 'source_reviewed_declared', 'sourceReview': 'AUTHORED-review',
            'basis': 'AUTHORED-physical-source-relation', 'humanConfirmed': False}


def native_corpus():
    note, root, child, independent = [native_node(i, text) for i, text in [
        ('note', 'See conditions'), ('root', 'Provide drawings.'),
        ('child', 'Only sealed originals.'), ('independent', 'Unrelated body.')]]
    attachment = native_edge(note, root)
    note['relations'].append(copy.deepcopy(attachment)); root['relations'].append(copy.deepcopy(attachment))
    relation = native_edge(child, root, 'parent')
    root['relations'].append(copy.deepcopy(relation)); child['relations'].append(copy.deepcopy(relation))
    return [note, root, child, independent]


class AttachmentGuardTests(unittest.TestCase):
    def unknown(self, parents, identity='note-a'):
        seed = next(p for p in parents if p['id'] == identity)
        context = SourceUnits(parents).resolve(seed)['attachmentContext']
        self.assertEqual(context['status'], 'unknown')
        self.assertEqual(context['requiredMemberIds'], [])
        self.assertEqual(context['members'], [])
        self.assertEqual(context['edgeDeclarations'], [])
        self.assertFalse(context['semanticScopeVerified']); self.assertFalse(context['legalClosureVerified'])
        return context

    def test_source_revision_quality_and_document_mismatch_are_unknown(self):
        for field, value in [('documentId', 'foreign'), ('sourceHash', '1' * 64),
                             ('originalSourceSha256', '2' * 64), ('sourceSha256', '3' * 64),
                             ('sourceRevisionSha256', '4' * 64), ('projectionRevisionSha256', '5' * 64),
                             ('sourceQualityHash', '6' * 64), ('metadataVersion', 'foreign-version')]:
            with self.subTest(field=field):
                p = corpus(); p[1][field] = value
                self.unknown(p)
        p = corpus(); p[1]['sourceQuality']['semanticQuality'] = 'invented'
        self.unknown(p)

    def test_missing_and_ambiguous_targets_do_not_create_partial_context(self):
        p = corpus(); del p[1]; self.unknown(p)
        p = corpus(); duplicate = copy.deepcopy(p[1]); duplicate['id'] = 'ambiguous-copy'; p.append(duplicate)
        self.unknown(p)

    def test_bad_endpoint_types_are_local_unknown_and_never_unhashable_graph_keys(self):
        for field in ('fromUnitId', 'toUnitId'):
            for value in ([], {}, 1, None):
                with self.subTest(field=field, value=value):
                    p = corpus(); p[0]['relations'][0][field] = value
                    self.unknown(p)
                    self.assertEqual(SourceUnits(p).resolve(p[2])['attachmentContext']['status'], 'complete_observed_attachment_context')
        p = corpus(); p[0]['originalSourceSha256'] = {'invalid': True}
        self.unknown(p)
        self.assertEqual(SourceUnits(p).resolve(p[2])['attachmentContext']['status'], 'complete_observed_attachment_context')

    def test_wrong_review_reference_status_and_block_endpoints_are_unknown(self):
        for mutate in ('reference', 'adoption-reference', 'status', 'from-block', 'to-block', 'part-source'):
            with self.subTest(mutate=mutate):
                p = corpus()
                if mutate == 'reference': p[0]['relations'][0]['referenceSha256'] = '7' * 64
                elif mutate == 'adoption-reference': p[1]['adoption']['referenceSha256'] = '7' * 64
                elif mutate == 'status': p[0]['relations'][0]['status'] = 'confidence_high'
                elif mutate == 'from-block': p[0]['relations'][0]['fromBlockId'] = 'not-owned'
                elif mutate == 'to-block': p[0]['relations'][0]['toBlockId'] = 'not-owned'
                else: p[1]['parts'][0]['sourceSha256'] = '7' * 64
                self.unknown(p)

    def test_conflicting_duplicates_stay_unknown_and_reviewed_multiple_targets_return_whole_group(self):
        p = corpus(); p[0]['relations'][0]['basis'] = 'conflicts-with-published-copy'
        self.unknown(p)
        p = corpus(); relation = copy.deepcopy(p[0]['relations'][0])
        relation.update(toUnitId=p[3]['unitId'], toBlockId=p[3]['parts'][0]['blockId'])
        p[0]['relations'].append(relation)
        context = SourceUnits(p).resolve(p[0])['attachmentContext']
        self.assertEqual(context['status'], 'complete_observed_attachment_context')
        self.assertEqual(context['requiredMemberIds'], ['note-a', 'body-a', 'body-b'])
        self.assertEqual(context['members'], [p[0], p[1], p[3]])
        self.assertEqual(len(context['edgeDeclarations']), 2)
        self.assertFalse(context['semanticScopeVerified'])
        self.assertFalse(context['legalClosureVerified'])
        unrelated = node('island', 'Unrelated branch', 'page/3/island')
        self.assertNotIn('attachmentContext', SourceUnits(p + [unrelated]).resolve(unrelated))

    def test_malformed_disconnected_declaration_does_not_invalidate_good_pair(self):
        p = corpus(); isolated = node('island', 'Unknown branch', 'page/3/island')
        isolated['relations'] = [{'type': 'margin_note', 'fromUnitId': 'missing-a', 'toUnitId': 'missing-b'}]
        p.append(isolated)
        self.assertEqual(SourceUnits(p).resolve(p[0])['attachmentContext']['status'], 'complete_observed_attachment_context')
        self.unknown(p, 'island')

    def test_note_does_not_follow_derived_target_other_attachment_or_same_text_sibling(self):
        p = corpus(); relation = copy.deepcopy(p[0]['relations'][0])
        relation.update(fromUnitId=p[1]['unitId'], fromBlockId=p[1]['parts'][0]['blockId'],
                        toUnitId=p[3]['unitId'], toBlockId=p[3]['parts'][0]['blockId'])
        p[1]['relations'].append(relation); p[3]['relations'].append(copy.deepcopy(relation))
        context = SourceUnits(p).resolve(p[0])['attachmentContext']
        self.assertEqual(context['requiredMemberIds'], ['note-a', 'body-a'])
        self.assertEqual(len(context['edgeDeclarations']), 1)
        self.assertNotIn('body-b', context['requiredMemberIds']); self.assertNotIn('note-b', context['requiredMemberIds'])

    def test_native_reviewed_region_adapter_closes_both_directions_with_complete_payloads(self):
        p = native_corpus()
        for seed in p[:2]:
            context = SourceUnits(p).resolve(seed)['attachmentContext']
            self.assertEqual(context['policy'], ATTACHMENT_POLICY)
            self.assertEqual(context['requiredMemberIds'], ['note', 'root'])
            self.assertEqual(context['members'], p[:2])
            self.assertEqual(context['memberPayloadSha256'], [{'id': m['id'], 'sha256': digest(m)} for m in p[:2]])

    def test_native_region_reference_scope_or_unreviewed_basis_fail_closed(self):
        for mutate in ('region', 'part-region', 'reference', 'scope', 'basis', 'human', 'producer'):
            with self.subTest(mutate=mutate):
                p = native_corpus()
                if mutate == 'region': p[0]['relations'][0]['toRegionId'] = 'not-owned'
                elif mutate == 'part-region': p[1]['parts'][0]['referenceRegionId'] = 'not-owned'
                elif mutate == 'reference': p[1]['referenceSha256'] = '8' * 64
                elif mutate == 'scope': p[0]['relations'][0]['scope'] = 'within_whole_unit'
                elif mutate == 'basis': p[0]['relations'][0]['basis'] = ''
                elif mutate == 'human': p[0]['relations'][0]['humanConfirmed'] = 'true'
                else: p[1]['producerKind'] = 'scanned_pdf'
                self.unknown(p, 'note')

    def test_independent_hierarchy_attachment_union_does_not_recurse_note_to_target_descendants(self):
        p = native_corpus(); before = copy.deepcopy(p)
        resolved = SourceUnits(p).resolve(p[1])
        self.assertEqual(resolved['hierarchyContext']['requiredMemberIds'], ['root', 'child'])
        self.assertEqual(resolved['attachmentContext']['requiredMemberIds'], ['note', 'root'])
        context = context_for(p, 'root')
        self.assertEqual(context['structuralUnit']['requiredMemberIds'], ['note', 'root', 'child'])
        self.assertEqual(context['structuralUnit']['combinedContextPolicy'], COMBINED_CONTEXT_POLICY)
        note_context = context_for(p, 'note')
        self.assertEqual(note_context['structuralUnit']['requiredMemberIds'], ['note', 'root'])
        self.assertNotIn(p[2]['content'], note_context['content'])
        self.assertEqual(p, before)

    def test_either_unknown_context_prevents_strict_scoring(self):
        for broken_kind in ('attachment', 'hierarchy'):
            with self.subTest(kind=broken_kind):
                p = native_corpus(); calls = []
                if broken_kind == 'attachment': p[0]['relations'][0]['basis'] = ''
                else: p[1]['relations'][1]['status'] = 'unreviewed'
                with self.assertRaises(WindowError) as raised:
                    compare('q', [p[1]], p, CharacterTokenizer(), {}, WindowRecipe(12, 2),
                            StructuralRecipe('dual-structure-v1', 128, 128), lambda pairs: calls.append(pairs))
                self.assertEqual(raised.exception.kind, 'STRUCTURAL_CONTEXT_UNAVAILABLE'); self.assertEqual(calls, [])

    def test_complete_unicode_parts_assets_and_unknowns_have_full_window_coverage(self):
        p = corpus(); p[1]['content'] += ' 😀中文 boundary'
        p[1]['parts'][0]['text'] = p[1]['content']; p[1]['parts'][0]['endOffset'] = utf16_length(p[1]['content'])
        before = copy.deepcopy(p)
        resolved = SourceUnits(p).resolve(p[0])['attachmentContext']
        self.assertEqual(resolved['members'], p[:2])
        result = plan(p, CharacterTokenizer(), {}, WindowRecipe(12, 2), StructuralRecipe('dual-structure-v1', 24, 24))
        for context in result['contexts']:
            rows = [w for w in result['points'] if w['granularity'] == 'structure' and w['parentId'] == context['id']]
            verify_window_coverage(context, rows, 24)
            self.assertTrue(all(w['retrievalSeedParentId'] in w['originParentIds'] for w in rows))
        self.assertEqual(p, before)

    def test_context_capacity_guard_fails_before_any_authored_scorer(self):
        p = corpus(); p[1]['content'] = p[1]['parts'][0]['text'] = 'A' * 400
        p[1]['parts'][0]['endOffset'] = 400; calls = []
        with self.assertRaises(WindowError):
            compare('q', [p[0]], p, CharacterTokenizer(), {}, WindowRecipe(12, 2, 1),
                    StructuralRecipe('dual-structure-v1', 24, 24), lambda pairs: calls.append(pairs))
        self.assertEqual(calls, [])

    def test_same_pool_baseline_stays_original_short_recipe_and_scores_are_only_adapter_values(self):
        p = corpus(); calls = []; identity = {'tokenizer': 'authored'}; short = WindowRecipe(12, 2)
        def adapter(pairs):
            calls.append(copy.deepcopy(pairs)); return [.37] * len(pairs)
        result = compare('q', [p[0]], p, CharacterTokenizer(), identity, short,
                         StructuralRecipe('dual-structure-v1', 128, 128), adapter)
        expected = windows(p[0], CharacterTokenizer(), short, identity, query='q')
        self.assertEqual(calls[0], [('q', w['content']) for w in expected])
        self.assertEqual([r['id'] for r in result['candidatePool']], ['note-a'])
        self.assertEqual([r['id'] for r in result['results']], ['note-a'])
        self.assertEqual(result['results'][0]['score'], .37)
        self.assertTrue(any(p[1]['content'] in text for _, text in calls[1]))

    def test_selection_preserves_observed_identity_and_never_promotes_derived_members_to_scored_seeds(self):
        p = corpus(); raw = [{'id': p[0]['id'], 'score': .4, 'payload': p[0]}]
        observed = SourceUnits(p)._resolve_observed(p[0])
        selected, output = select_source_units(p, raw, 1)
        self.assertEqual(selected, raw); self.assertEqual(output['policy'], POLICY)
        actual = output['units'][0]
        self.assertEqual(actual['unitId'], observed['unitId']); self.assertEqual(actual['requiredMemberIds'], [p[0]['id']])
        self.assertEqual(actual['attachmentContext']['requiredMemberIds'], [p[0]['id'], p[1]['id']])
        self.assertFalse(actual['attachmentContext']['derivedMembersCanBecomeOrigins']); self.assertIsNone(actual['attachmentContext']['memberScores'])
        selected_old, old = select_source_units(p, raw, 1, hierarchy_context=False)
        self.assertEqual(selected_old, raw); self.assertEqual(old['policy'], OBSERVED_POLICY)
        self.assertNotIn('attachmentContext', old['units'][0])

    def test_context_identity_binds_order_edge_unknowns_and_pointer_changes(self):
        p = corpus(); first = SourceUnits(p).resolve(p[0])['attachmentContext']['contextId']
        for mutate in ('order', 'asset', 'unknown', 'edge'):
            changed = copy.deepcopy(p)
            if mutate == 'order': changed = [changed[1], changed[0], *changed[2:]]
            elif mutate == 'asset': changed[1]['assetRefs'].append({'new': True})
            elif mutate == 'unknown': changed[1]['parts'][0]['authoredTablePointer']['preserveUnknown'] = None
            else:
                for row in changed[:2]: row['relations'][0]['humanConfirmed'] = False
            with self.subTest(mutate=mutate):
                self.assertNotEqual(first, SourceUnits(changed).resolve(next(row for row in changed if row['id'] == 'note-a'))['attachmentContext']['contextId'])


if __name__ == '__main__': unittest.main(verbosity=2)
