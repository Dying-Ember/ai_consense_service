"""Authored input-wiring contracts; no claimed neural scores or source validity."""
import copy
import unittest
from test_attachment_context_guards import native_corpus, native_edge, native_node
from retrieval_source_units import SourceUnits, select_source_units
from structural_retrieval import _catalog, compare, StructuralRecipe
from test_explicit_margin_context_red import CharacterTokenizer
from token_windows import WindowRecipe, WindowError


def shared():
    rows = native_corpus()
    note, first = rows[:2]
    second = rows[3]
    edge = native_edge(note, second, 'sidebar')
    note['relations'].append(copy.deepcopy(edge))
    second['relations'].append(copy.deepcopy(edge))
    return rows


class SharedNoteGroupTests(unittest.TestCase):
    def test_either_endpoint_returns_all_declared_targets_as_exact_original_members(self):
        parents = shared()
        before = copy.deepcopy(parents)
        expected = [parents[i] for i in (0, 1, 3)]
        for seed in expected:
            context = SourceUnits(parents).resolve(seed)['attachmentContext']
            self.assertEqual(context['status'], 'complete_observed_attachment_context')
            self.assertEqual(context['requiredMemberIds'], [p['id'] for p in expected])
            self.assertEqual(context['members'], expected)
            self.assertEqual(len(context['edgeDeclarations']), 2)
            self.assertFalse(context['semanticScopeVerified'])
            self.assertFalse(context['legalClosureVerified'])
            self.assertEqual(context['qualifiersComplete'], 'unknown')
            self.assertIsNone(context['memberScores'])
        self.assertEqual(parents, before)

    def test_note_group_does_not_nominate_other_notes_from_derived_target(self):
        parents = shared()
        other = native_node('other-note', 'Separate marginal heading')
        foreign = native_node('foreign-target', 'A separate associated target')
        for target in (parents[3], foreign):
            edge = native_edge(other, target)
            other['relations'].append(copy.deepcopy(edge))
            target['relations'].append(copy.deepcopy(edge))
        parents += [other, foreign]
        context = SourceUnits(parents).resolve(parents[0])['attachmentContext']
        self.assertEqual(context['requiredMemberIds'], ['note', 'root', 'independent'])
        self.assertNotIn('other-note', context['requiredMemberIds'])
        self.assertNotIn('foreign-target', context['requiredMemberIds'])
        self.assertNotIn('child', context['requiredMemberIds'])

    def test_wrong_target_reference_rejects_entire_declared_group_before_scoring(self):
        for mutation in ('reference', 'region', 'scope'):
            parents = shared()
            if mutation == 'reference': parents[3]['referenceSha256'] = '8' * 64
            elif mutation == 'region': parents[3]['parts'][0]['referenceRegionId'] = 'not-owned'
            else: parents[3]['scopeSha256'] = '9' * 64
            calls = []
            with self.assertRaises(WindowError) as raised:
                compare('q', [parents[0]], parents, CharacterTokenizer(), {}, WindowRecipe(12, 2),
                        StructuralRecipe('dual-structure-v1', 128, 128), lambda pairs: calls.append(pairs))
            self.assertEqual(raised.exception.kind, 'STRUCTURAL_CONTEXT_UNAVAILABLE')
            self.assertEqual(calls, [])

    def test_unrelated_unknown_stays_local_and_complete_group_is_selectable(self):
        parents = shared()
        unrelated = native_node('island', 'Unrelated source branch')
        unrelated['relations'] = [{'type': 'sidebar', 'fromUnitId': 'missing-a', 'toUnitId': 'missing-b'}]
        parents.append(unrelated)
        resolved = SourceUnits(parents).resolve(parents[0])['attachmentContext']
        self.assertEqual(resolved['status'], 'complete_observed_attachment_context')
        self.assertEqual(resolved['requiredMemberIds'], ['note', 'root', 'independent'])
        self.assertEqual(SourceUnits(parents).resolve(unrelated)['attachmentContext']['status'], 'unknown')

    def test_complete_group_payload_returns_through_real_selector_without_scoring_derived_members(self):
        parents = shared()
        # Constant is an authored selector API input, not a model/quality measurement.
        raw = [{'id': parents[1]['id'], 'payload': parents[1], 'score': 0.37}]
        hits, evidence = select_source_units(parents, raw, 1)
        self.assertEqual(hits, raw)
        self.assertEqual(evidence['originalHitIds'], [parents[1]['id']])
        context = evidence['units'][0]['attachmentContext']
        self.assertEqual(context['members'], [parents[i] for i in (0, 1, 3)])
        self.assertIsNone(context['memberScores'])
        self.assertFalse(context['derivedMembersCanBecomeOrigins'])

    def test_full_shared_group_catalog_contains_original_parts_not_a_shortened_heading(self):
        parents = shared()
        contexts, bindings = _catalog(parents)
        binding = bindings[parents[0]['id']]
        self.assertEqual(binding['status'], 'complete_observed_unit')
        context = contexts[binding['contextId']]
        self.assertEqual(context['structuralUnit']['requiredMemberIds'], ['note', 'root', 'independent'])
        for index in (0, 1, 3):
            self.assertIn(parents[index]['content'], context['content'])
            self.assertTrue(any(o['part'] == parents[index]['parts'][0]
                                for o in context['structuralUnit']['originalPartOccurrences']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
