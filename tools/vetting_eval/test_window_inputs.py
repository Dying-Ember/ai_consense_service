"""Synthetic file/identity guards; no real SQLite connection or model calls."""
import copy
import json
from pathlib import Path
import pickle
from types import SimpleNamespace
import tempfile
import unittest
from prepare_preview_inputs import prepare,desc
from audit_window_persistence import validate_metadata,decode,audit
from token_windows import windows,WindowRecipe,digest,WindowError
from test_token_windows import parent,CharacterTokenizer


class InputGuards(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.p=parent()
        self.corpus=self.root/'corpus.jsonl';self.corpus.write_text(json.dumps(self.p)+'\n')
        self.receipt=self.root/'audit.json';self.receipt.write_text(json.dumps({'status':'complete','chunkCount':1,'corpus':desc(self.corpus)}))
        self.plan=self.root/'plan.json';self.plan.write_text(json.dumps({'requests':[{'originalTopicOrdinal':1,'request':{'query':'generic'}}]}))
    def tearDown(self):self.tmp.cleanup()
    def prep(self):return prepare(self.corpus,desc(self.corpus)['sha256'],self.receipt,desc(self.receipt)['sha256'],self.plan,desc(self.plan)['sha256'],self.root/'out')
    def test_file_only_wrappers_bind_exact_source_and_generic_query(self):
        result=self.prep();p=json.loads(Path(result['queryPlan']['path']).read_text());self.assertEqual(p['requests'],json.loads(self.plan.read_text())['requests']);self.assertEqual(p['actualIndex'],0)
    def test_changed_audit_source_rejected(self):
        self.receipt.write_text(json.dumps({'status':'complete','chunkCount':1,'corpus':{'bad':'identity'}}))
        with self.assertRaises(ValueError):self.prep()
    def test_duplicate_parent_rejected(self):
        self.corpus.write_text(self.corpus.read_text()*2);self.receipt.write_text(json.dumps({'status':'complete','chunkCount':2,'corpus':desc(self.corpus)}))
        with self.assertRaises(ValueError):self.prep()
    def test_flattened_query_wrapper_rejected(self):
        self.plan.write_text(json.dumps({'requests':[{'originalTopicOrdinal':1,'query':'generic'}]}))
        with self.assertRaises(ValueError):self.prep()
    def test_output_refuses_overwrite(self):
        self.prep()
        with self.assertRaises(FileExistsError):self.prep()
    def test_inert_pickle_rejects_arbitrary_globals_without_calling_them(self):
        with self.assertRaises(ValueError):decode(pickle.dumps(print))
        self.assertEqual(decode(pickle.dumps('window-id')),'window-id')
    def test_persisted_metadata_exact_binding_and_no_gap(self):
        rows=windows(self.p,CharacterTokenizer(),WindowRecipe(10,2),{})
        signature={'signatureVersion':3,'corpus':digest([self.p]),'windowRecipe':{'max_tokens':10}}
        meta={'contract':'source-bound-window-points-full-parent-results-v1','signature':signature,'chunks':[self.p],
              'windows':rows,'windowManifestSha256':digest(rows),'parentCount':1,'vectorPoints':len(rows)}
        parents,bound=validate_metadata(meta,[self.p],digest(signature));self.assertEqual(len(parents),1);self.assertEqual(len(bound),len(rows))
        changed=copy.deepcopy(meta);changed['windows'][0]['sourceHash']='wrong';changed['windowManifestSha256']=digest(changed['windows'])
        with self.assertRaises(WindowError):validate_metadata(changed,[self.p],digest(signature))
    def test_unstopped_producer_fails_before_file_or_sqlite_reads(self):
        with self.assertRaises(WindowError)as e:audit(SimpleNamespace(producer_exited=False))
        self.assertEqual(e.exception.kind,'PRODUCER_RUNNING')


if __name__=='__main__':unittest.main()
