"""Deterministic saved-score/structure fixtures, no neural/vector/HTTP calls."""
import copy
import unittest
from retrieval_source_units import select_source_units


def parent(id_, block, text, clause=None, heading=None, start=0, doc='source'):
    return {'id': id_, 'documentId': doc, 'role': 'tender', 'sourceHash': 'a'*64,
            'metadataVersion': 'owner-clause-v2', 'segmentationVersion': 'standalone-section-boundary-v1',
            'nativeTableMetadataVersion': 'native-rows-and-cell-slices-v3',
            'sourceQualityMetadataVersion': 'synthetic-quality-v1', 'sourceQualityHash': 'b'*64,
            'sourceQuality': {'parseStatus': 'PARSED', 'semanticQuality': 'unknown'},
            'clauseId': clause, 'clauseHeadingLocation': heading, 'content': text,
            'parts': [{'text': text, 'blockId': block, 'anchor': block.replace(':','/')+((' @'+str(start)) if start else ''),
                       'startOffset': start, 'endOffset': start+len(text.encode('utf-16-le'))//2,
                       'tableSlice': {'unselectedText': None, 'selected': text}}]}


def hit(p, score):
    return {'id': p['id'], 'score': score, 'payload': p}


class StructuralSelectionTests(unittest.TestCase):
    def corpus(self):
        return [parent('a-head','body:1','A heading', 'A1','body/1'),
                parent('a-tail','body:2','Only when active', 'A1','body/1'),
                parent('b-head','body:3','B heading', 'B1','body/3')]

    def test_duplicate_unit_parent_hits_no_longer_starve_another_unit(self):
        p=self.corpus();raw=[hit(c,s) for c,s in zip(p,[.9,.8,.7])]
        before=copy.deepcopy(raw);selected,e=select_source_units(p,raw,2)
        self.assertEqual(e['rawParentTopKIds'],['a-head','a-tail'])
        self.assertEqual([h['id']for h in selected],['a-head','b-head'])
        self.assertEqual([h['score']for h in selected],[.9,.7]);self.assertEqual(raw,before)
        self.assertEqual(e['units'][0]['requiredMemberIds'],['a-head','a-tail'])
        self.assertEqual(e['skippedDuplicateUnitSeeds'][0]['originalRerankOrdinal'],2)
        self.assertIsNone(e['units'][0]['memberScores'])

    def test_tail_member_is_full_canonical_native_payload_without_score(self):
        p=self.corpus();selected,e=select_source_units(p,[hit(p[0],.9)],1)
        self.assertEqual(len(selected),1);self.assertEqual(e['units'][0]['members'][1],p[1])
        self.assertFalse(e['units'][0]['derivedMembersCanBecomeOrigins'])
        self.assertEqual(e['units'][0]['originIds'],['a-head'])

    def test_unowned_overlapping_utf16_blocks_zero_gap(self):
        a=parent('head','body:1','A😀BC');b=parent('tail','body:1','BC中文',start=3)
        selected,e=select_source_units([a,b],[hit(a,.2)],1)
        self.assertEqual(e['units'][0]['requiredMemberIds'],['head','tail'])
        self.assertEqual(e['units'][0]['observedRanges'][0]['endUtf16'],7)
        self.assertIsNone(e['units'][0]['observedRanges'][0]['sourceBlockComplete'])

    def test_heading_conflict_is_unknown_single_seed_not_dedup_or_partial(self):
        p=self.corpus();p[1]['clauseHeadingLocation']='body/2'
        selected,e=select_source_units(p,[hit(c,.9-i/10)for i,c in enumerate(p)],2)
        self.assertEqual([h['id']for h in selected],['a-head','a-tail'])
        self.assertTrue(all(u['status']=='unknown'and not u['members']for u in e['units']))

    def test_missing_source_quality_never_suppresses_unknown_seed(self):
        p=self.corpus();p[0].pop('sourceQualityHash');p[1].pop('sourceQualityHash')
        selected,e=select_source_units(p,[hit(c,.9-i/10)for i,c in enumerate(p)],2)
        self.assertEqual([h['id']for h in selected],['a-head','a-tail']);self.assertEqual(e['units'][0]['status'],'unknown')

    def test_changed_hash_role_quality_version_in_same_document_is_ambiguous(self):
        for field,value in [('sourceHash','c'*64),('role','standard'),('sourceQualityHash','d'*64),('metadataVersion','different')]:
            with self.subTest(field=field):
                p=self.corpus();p[1][field]=value;_,e=select_source_units(p,[hit(p[0],.9)],1)
                self.assertEqual(e['units'][0]['status'],'unknown');self.assertFalse(e['units'][0]['members'])

    def test_other_document_same_heading_never_joins(self):
        p=self.corpus();other=copy.deepcopy(p[1]);other['id']='foreign';other['documentId']='foreign'
        _,e=select_source_units(p+[other],[hit(p[0],.9)],1)
        self.assertNotIn('foreign',e['units'][0]['requiredMemberIds'])

    def test_overlap_conflict_or_missing_start_fails_closed(self):
        a=parent('head','body:1','ABC');b=parent('tail','body:1','Xd',start=2)
        for members in [[a,b],[parent('tail','body:1','ABC',start=2)]]:
            _,e=select_source_units(members,[hit(members[0],.2)],1)
            self.assertEqual(e['units'][0]['status'],'unknown');self.assertFalse(e['units'][0]['members'])

    def test_member_other_block_does_not_expand_second_hop(self):
        a=parent('head','body:1','ABC');b=parent('tail','body:1','BC',start=1)
        extra=parent('second-hop','body:2','External unit')
        b['parts'].extend(extra['parts']);b['content']='BC\nExternal unit'
        _,e=select_source_units([a,b,extra],[hit(a,.2)],1)
        self.assertEqual(e['units'][0]['requiredMemberIds'],['head','tail'])
        self.assertEqual(e['units'][0]['blockIds'],['body:1'])

    def test_changed_reranked_payload_rejected_and_empty_is_honest(self):
        p=self.corpus();bad=hit(p[0],.2);bad=copy.deepcopy(bad);bad['payload']['content']='changed'
        with self.assertRaises(ValueError):select_source_units(p,[bad],1)
        selected,e=select_source_units(p,[],2);self.assertEqual(selected,[]);self.assertEqual(e['units'],[])

    def test_duplicate_scored_seed_and_invalid_limit_are_rejected(self):
        p=self.corpus()
        with self.assertRaises(ValueError):select_source_units(p,[hit(p[0],.9),hit(p[0],.8)],2)
        with self.assertRaises(ValueError):select_source_units(p,[hit(p[0],.9)],-1)
        with self.assertRaises(ValueError):select_source_units(p,[hit(p[0],True)],1)
        with self.assertRaises(ValueError):select_source_units(p,[hit(p[0],float('nan'))],1)


if __name__=='__main__':unittest.main()
