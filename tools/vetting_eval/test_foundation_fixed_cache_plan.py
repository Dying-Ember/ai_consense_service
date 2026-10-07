"""Scope and stale-input guards for fixed cache binding; no inference imports."""
import copy,json,tempfile,unittest
from pathlib import Path
import foundation_fixed_cache_plan as plan

class FixedCachePlanTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        raw=self.root/'same-name.txt';raw.write_bytes(b'Original fixed content')
        self.docs=[dict(id=7,storagePath=str(raw),fileKey='FIXTURE',reviewRole='standard')]
        self.docpath=self.root/'docs.json';self.docpath.write_text(json.dumps(self.docs),encoding='utf-8')
        self.corpuspath=self.root/'corpus.jsonl';self.corpuspath.write_text(json.dumps(dict(id='p',documentId='7',sourceHash='a'*64,
            role='standard',sourceQualityMetadataVersion='test-v1',sourceQualityHash='b'*64,sourceQuality=dict(textAccuracy='unverified')))+'\n',encoding='utf-8')
        self.dd=plan.fingerprint(self.docpath);self.cd=plan.fingerprint(self.corpuspath)
        self.life=dict(protocol='vetting-fixed-source-lifecycle-v1',fixedSources=[dict(historicalDocumentId='7',original=plan.fingerprint(raw),
            referenceKey='explicit-fixed',fileKey='FIXTURE',businessRole='standard')])
        self.proof=dict(protocol='source-quality-metadata-corpus-proof-v1',replays=[dict(input=self.dd,runs=dict(quality=dict(corpus=self.cd)),
            sourceHashContentOwnersAnchorsUtf16NativeRowsExactlyPreserved=True,chunkCount=1)])
    def test_explicit_source_keeps_role_and_rebinds_only_declared_fixed_hash(self):
        result=plan.prepare(self.life,self.proof,self.dd,self.cd);row=result['sources'][0]
        self.assertEqual('standard',row['role']);self.assertEqual('a'*64,row['sourceHash']);self.assertFalse(row['documentIdsReusable'])
        self.assertEqual('fixed_competition_material',row['storageTier'])
    def test_same_filename_with_revised_original_bytes_is_rejected(self):
        Path(self.docs[0]['storagePath']).write_bytes(b'Revised text')
        with self.assertRaisesRegex(ValueError,'original bytes'):plan.prepare(self.life,self.proof,self.dd,self.cd)
    def test_stale_corpus_descriptor_is_rejected_before_any_cache_plan(self):
        self.corpuspath.write_text('{}\n',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'input bytes'):plan.prepare(self.life,self.proof,self.dd,self.cd)
    def test_non_whitelisted_variable_source_is_rejected(self):
        row=json.loads(self.corpuspath.read_text());row['documentId']='99';self.corpuspath.write_text(json.dumps(row)+'\n')
        cd=plan.fingerprint(self.corpuspath);proof=copy.deepcopy(self.proof);proof['replays'][0]['runs']['quality']['corpus']=cd
        with self.assertRaisesRegex(ValueError,'unapproved'):plan.prepare(self.life,proof,self.dd,cd)
    def test_matching_raw_source_cannot_silently_change_business_role(self):
        self.life['fixedSources'][0]['businessRole']='tender'
        with self.assertRaisesRegex(ValueError,'role/file'):plan.prepare(self.life,self.proof,self.dd,self.cd)
    def test_missing_quality_metadata_is_rejected(self):
        row=json.loads(self.corpuspath.read_text());del row['sourceQualityHash'];self.corpuspath.write_text(json.dumps(row)+'\n')
        cd=plan.fingerprint(self.corpuspath);proof=copy.deepcopy(self.proof);proof['replays'][0]['runs']['quality']['corpus']=cd
        with self.assertRaisesRegex(ValueError,'quality'):plan.prepare(self.life,proof,self.dd,cd)
    def test_absent_or_duplicate_emission_proof_is_rejected(self):
        for rows in ([],self.proof['replays']*2):
            proof=copy.deepcopy(self.proof);proof['replays']=rows
            with self.subTest(count=len(rows)),self.assertRaisesRegex(ValueError,'uniquely'):plan.prepare(self.life,proof,self.dd,self.cd)

if __name__=='__main__':unittest.main()
