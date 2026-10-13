"""Generic source-only graphs and authored model adapters; no gold or neural call."""
import copy
import unittest

from retrieval_source_units import SourceUnits, select_source_units, HIERARCHY_POLICY
from structural_retrieval import _catalog, plan, compare, StructuralRecipe
from test_retrieval_source_units import parent, hit
from test_token_windows import CharacterTokenizer
from token_windows import WindowRecipe, WindowError, digest, verify_window_coverage


def node(identity, text):
    row = parent(identity, 'body:'+identity, text, identity, 'body/'+identity)
    row.update(producerKind='native_pdf', canonicalScope='whole_text_unit', unitId='unit:'+identity,
               originalSourceSha256='c'*64, projectionRevisionSha256='d'*64,
               sourceRevisionSha256='e'*64, scopeSha256='f'*64,
               adoption={'regionIds': ['region:'+identity]}, relations=[],
               physicalPages=[1], humanConfirmed=False, assetRefs=[{'authored': identity}])
    row['parts'][0]['authoredTableImagePointer'] = {'unknown': True, 'nativeNode': identity}
    return row


def edge(child, parent):
    return {'type': 'parent', 'scope': 'between_whole_units',
            'fromUnitId': child['unitId'], 'toUnitId': parent['unitId'],
            'fromRegionId': child['adoption']['regionIds'][0],
            'toRegionId': parent['adoption']['regionIds'][0],
            'status': 'source_reviewed_declared', 'sourceReview': 'AUTHORED-observed-parent',
            'basis': 'AUTHORED-explicit-parent-not-clause-guess', 'humanConfirmed': False}


def corpus():
    title = node('z-title', 'A title')
    child = node('b-body', 'Provide a report.')
    nested = node('a-condition', 'Only when work continues.')
    unrelated = node('x-other', 'Unrelated evidence')
    relation = edge(child, title)
    title['relations'].append(copy.deepcopy(relation)); child['relations'].append(relation)
    relation = edge(nested, child)
    child['relations'].append(copy.deepcopy(relation)); nested['relations'].append(relation)
    return [title, child, nested, unrelated]


class TypedParentContextTests(unittest.TestCase):
    def resolve(self, parents):
        return SourceUnits(parents).resolve(parents[0])

    def context(self, parents):
        return self.resolve(parents)['hierarchyContext']

    def test_old_exact_identity_and_members_are_not_chapter_legal_completeness(self):
        p = corpus(); original = copy.deepcopy(p)
        c = SourceUnits(p); observed = c._resolve_observed(p[0]); resolved = c.resolve(p[0])
        self.assertEqual(observed, {k:v for k,v in resolved.items() if k != 'hierarchyContext'})
        context = resolved['hierarchyContext']
        self.assertEqual(context['status'], 'complete_observed_hierarchy_context')
        self.assertEqual(context['requiredMemberIds'], [p['id'] for p in original[:3]])
        self.assertEqual(context['members'], original[:3])
        self.assertFalse(context['semanticScopeVerified']); self.assertFalse(context['legalClosureVerified'])
        self.assertEqual(context['qualifiersComplete'], 'unknown'); self.assertIsNone(context['memberScores'])
        self.assertEqual(p, original)

    def test_context_order_follows_source_order_not_lexical_id_or_clause_prefix(self):
        p = corpus(); p = [p[0], p[2], p[1], p[3]]
        self.assertEqual(self.context(p)['requiredMemberIds'], ['z-title', 'a-condition', 'b-body'])
        no_edges = copy.deepcopy(p)
        for item in no_edges: item['relations'] = []
        self.assertNotIn('hierarchyContext', self.resolve(no_edges))

    def test_selection_never_adds_context_members_as_scored_or_reranked_origins(self):
        p = corpus(); raw = [hit(p[0], .9), hit(p[3], .5)]; original = copy.deepcopy(raw)
        selected, episode = select_source_units(p, raw, 2)
        self.assertEqual(selected, original)
        self.assertEqual(episode['originalHitIds'], ['z-title', 'x-other'])
        self.assertEqual(episode['units'][0]['originIds'], ['z-title'])
        self.assertFalse(episode['units'][0]['hierarchyContext']['derivedMembersCanBecomeOrigins'])
        self.assertEqual(raw, original)

    def test_structure_inputs_contain_descendants_and_real_overlap_mapping(self):
        p = corpus(); tokenizer = CharacterTokenizer(); recipe = StructuralRecipe('dual-structure-v1', 24, 24)
        result = plan(p, tokenizer, {'tokenizer': 'AUTHORED-character'}, WindowRecipe(12, 2), recipe)
        binding = result['bindings'][p[0]['id']]
        context = next(c for c in result['contexts'] if c['id'] == 'structure:'+binding['contextId'])
        self.assertEqual(context['content'], '\n'.join(item['content'] for item in p[:3]))
        self.assertEqual(context['structuralUnit']['requiredMemberIds'], [item['id'] for item in p[:3]])
        rows = [w for w in result['points'] if w['granularity'] == 'structure' and w['parentId'] == context['id']]
        verify_window_coverage(context, rows, recipe.long_embedding_tokens)
        by_id = {item['id']: item for item in p}
        for row in rows:
            seed = by_id[row['retrievalSeedParentId']]
            self.assertIn(seed['id'], row['originParentIds'])
            self.assertTrue(any(part['blockId'] == span['blockId'] and
                min(part['endOffset'], span['sourceEndUtf16']) > max(part['startOffset'], span['sourceStartUtf16'])
                for part in seed['parts'] for span in row['sourceSpans']))
        # These are existing original parents with short lanes, not context-only
        # origins invented by resolve/select/compare and not fake title overlap.
        self.assertTrue({row['retrievalSeedParentId'] for row in rows} <= {item['id'] for item in p})

    def test_same_pool_compare_uses_context_body_without_candidate_injection(self):
        p = corpus(); seen = []
        def authored_scorer(pairs):
            seen.extend(pairs)
            return [.8 if 'Only when' in passage else .1 for _,passage in pairs]
        result = compare('q', [p[0]], p, CharacterTokenizer(), {'tokenizer':'AUTHORED-character'},
                         WindowRecipe(12, 2), StructuralRecipe('dual-structure-v1', 60, 60), authored_scorer)
        self.assertEqual([r['id'] for r in result['results']], ['z-title'])
        self.assertEqual([r['id'] for r in result['candidatePool']], ['z-title'])
        self.assertTrue(any('Only when' in passage for _,passage in seen))
        self.assertEqual(result['results'][0]['score'], .8)
        self.assertFalse(result['qualityImprovementEstablished'])

    def unknown(self, p, reason):
        context = self.context(p)
        self.assertEqual(context['status'], 'unknown'); self.assertEqual(context['reason'], reason)
        self.assertEqual(context['members'], []); self.assertEqual(context['requiredMemberIds'], [])
        return context

    def test_missing_endpoint_cross_source_and_revision_are_unknown(self):
        for mutate in ('missing', 'source', 'revision', 'duplicate'):
            p = corpus()
            if mutate == 'missing': p = [p[0], p[2], p[3]]
            elif mutate == 'source': p[1]['documentId'] = 'foreign'
            elif mutate == 'revision': p[1]['projectionRevisionSha256'] = '1'*64
            else:
                duplicate = copy.deepcopy(p[1]); duplicate['id'] = 'other-copy'; p.append(duplicate)
            with self.subTest(mutate=mutate):
                self.unknown(p, 'hierarchy_parent_endpoint_missing_ambiguous_or_cross_scope')

    def test_full_source_multiple_parents_disconnected_cycle_and_self_edge(self):
        p = corpus(); p[3]['relations'].append(edge(p[1], p[3]))
        self.unknown(p, 'hierarchy_multiple_whole_unit_parents')
        p = corpus(); a, b = node('island-a','Island A'), node('island-b','Island B')
        a['relations'] = [edge(a,b)]; b['relations'] = [edge(b,a)]; p.extend([a,b])
        self.assertEqual(self.context(p)['status'], 'complete_observed_hierarchy_context')
        broken = SourceUnits(p).resolve(a)['hierarchyContext']
        self.assertEqual(broken['status'], 'unknown'); self.assertEqual(broken['reason'], 'hierarchy_whole_unit_cycle')
        p = corpus(); p[0]['relations'].append(edge(p[0],p[0]))
        self.unknown(p, 'hierarchy_between_whole_unit_self_edge')

    def test_bad_declaration_conflict_or_region_is_unknown_not_silently_skipped(self):
        p = corpus(); p[0]['relations'][0]['status'] = 'unreviewed'
        self.unknown(p, 'hierarchy_parent_declaration_untrusted')
        p = corpus(); p[0]['relations'][0]['basis'] = 'conflicting-basis'
        self.unknown(p, 'hierarchy_parent_declarations_conflict')
        p = corpus(); p[0]['relations'][0]['fromRegionId'] = 'not-observed'
        self.unknown(p, 'hierarchy_parent_region_endpoint_differs')

    def test_unknown_graph_prevents_structure_scoring_before_authored_scorer(self):
        p = corpus(); p[0]['relations'][0]['status'] = 'unreviewed'; calls=[]
        with self.assertRaises(WindowError):
            compare('q', [p[0]], p, CharacterTokenizer(), {'tokenizer':'AUTHORED-character'},
                    WindowRecipe(12,2), StructuralRecipe('dual-structure-v1',60,60), lambda pairs:calls.append(pairs))
        self.assertEqual(calls, [])

    def test_internal_parent_self_edge_keeps_valid_cross_unit_closure(self):
        p = corpus(); internal = edge(p[0],p[0]); internal['scope'] = 'within_whole_unit'
        p[0]['relations'].append(internal)
        self.assertEqual(self.context(p)['status'], 'complete_observed_hierarchy_context')

    def test_same_source_independent_branch_missing_endpoint_does_not_pause_good(self):
        p = corpus(); isolated = node('island-root','Independent branch')
        missing = node('missing-child','Not adopted')
        isolated['relations'].append(edge(missing,isolated)); p.append(isolated)
        self.assertEqual(self.context(p)['status'], 'complete_observed_hierarchy_context')
        self.assertEqual(SourceUnits(p).resolve(isolated)['hierarchyContext']['status'], 'unknown')

    def test_unattributable_bad_declaration_makes_scope_unknown(self):
        p = corpus(); unrelated_owner = p[3]
        unrelated_owner['relations'].append({'type':'parent','scope':'between_whole_units',
            'fromUnitId':'not-observed-a','toUnitId':'not-observed-b',
            'fromRegionId':'not-observed-a','toRegionId':'not-observed-b'})
        self.unknown(p,'hierarchy_parent_declaration_untrusted')

    def test_invalid_dependent_descendant_pauses_ancestor_even_when_not_seed(self):
        p = corpus(); missing = node('missing-child','Not adopted')
        p[2]['relations'].append(edge(missing,p[2]))
        self.unknown(p,'hierarchy_parent_endpoint_missing_ambiguous_or_cross_scope')

    def test_non_native_scanned_and_collection_old_behavior(self):
        for producer in ('native_docx', 'scanned_pdf', 'native_docx_collection', None):
            p = corpus()
            for item in p: item['producerKind'] = producer
            with self.subTest(producer=producer):
                self.assertNotIn('hierarchyContext', self.resolve(p))

    def test_context_identity_binds_payload_edges_order_and_unknown_not_fake_confidence(self):
        p = corpus(); first = self.context(p)
        self.assertEqual(first['policy'], HIERARCHY_POLICY)
        self.assertEqual(first['memberPayloadSha256'], [{'id':row['id'],'sha256':digest(row)} for row in p[:3]])
        p2 = copy.deepcopy(p); p2[1]['assetRefs'].append({'anotherAuthoredPointer':True})
        self.assertNotEqual(first['contextId'], self.context(p2)['contextId'])
        p2 = [p[0],p[2],p[1],p[3]]
        self.assertNotEqual(first['contextId'], self.context(p2)['contextId'])
        p2 = copy.deepcopy(p)
        for item in p2:
            for relation in item['relations']: relation['humanConfirmed'] = True
        confirmed = self.context(p2)
        self.assertEqual(confirmed['status'], 'complete_observed_hierarchy_context')
        self.assertTrue(all(e['relation']['humanConfirmed'] for e in confirmed['edgeDeclarations']))
        self.assertFalse(confirmed['semanticScopeVerified']); self.assertFalse(confirmed['legalClosureVerified'])


if __name__ == '__main__': unittest.main()
