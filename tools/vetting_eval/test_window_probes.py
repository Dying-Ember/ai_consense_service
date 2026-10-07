"""Instrumented fake providers only; no real model or database calls."""
import copy
import importlib.util
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import numpy as np
from test_token_windows import CharacterTokenizer,parent
from token_windows import FullInputTokenizerGuard,WindowError,WindowRecipe,windows
from staged.pipeline_probe import PipelineProbe,TokenizerProbe,install_model_probes,text_sha


class ProbeTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.probe=PipelineProbe(Path(self.tmp.name)/'probe','synthetic-guard')
    def tearDown(self):self.tmp.cleanup()
    def event(self,stage):
        import json
        row=next(row for row in self.probe.events if row['stage']==stage)
        return json.loads((self.probe.directory/row['artifact']['relativePath']).read_text())
    def test_score_order_differs_from_input_order_and_ties_use_id(self):
        chunks=[parent(id_='b'),parent(id_='c'),parent(id_='a')]
        s=SimpleNamespace(embedding_model=lambda:None,rerank_model=lambda:None,encode_embeddings=lambda t:np.asarray([[1.,0.]]for _ in t),
                          rerank_candidates=lambda q,c:np.asarray([.1,.9,.9]),_embedding=None,_reranker=None)
        install_model_probes(s,self.probe);scores=s.rerank_candidates('generic query',chunks)
        event=self.event('reranker-output');payload=event['payload']
        self.assertEqual([r['id']for r in payload['candidateIdentitiesInInputOrder']],['b','c','a'])
        self.assertEqual([r['id']for r in payload['candidatesInScoreOrder']],['a','c','b'])
        self.assertEqual(payload['scoresInCandidateOrder'],[.1,.9,.9]);self.assertEqual(event['context']['activeInferenceCallId'],'reranker-1')
        self.assertEqual(scores.tolist(),[.1,.9,.9]);self.assertEqual(self.probe.context,{'operation':'startup'})
    def test_duplicate_text_source_binding_retains_ambiguity(self):
        chunks=[parent(id_='a'),parent(id_='b')];self.probe.bind_chunks(chunks)
        rows=windows(chunks[0],CharacterTokenizer(),WindowRecipe(10,2),{})
        self.probe.bind_windows(rows);self.probe.bind_windows(rows)
        original=self.probe.chunk_lookup[text_sha(chunks[0]['content'])]
        self.assertEqual({r['id']for r in original},{'a','b'})
        bound=self.probe.chunk_lookup[text_sha(rows[0]['content'].strip())]
        self.assertEqual(len(bound),1);self.assertEqual(bound[0]['parentId'],'a');self.assertEqual(bound[0]['sourceSpans'],rows[0]['sourceSpans'])
    def test_tokenizer_guard_feature_modes_and_real_counts(self):
        events=[];guard=FullInputTokenizerGuard(CharacterTokenizer(),10,events.append)
        proxy=TokenizerProbe(guard,self.probe,'embedding')
        features=proxy(['abc'],return_tensors='pt',truncation=True,max_length=1,padding=True)
        self.assertEqual(events[0]['featureMode'],'inference_tensor_features');self.assertEqual(events[1]['featureMode'],'tokenizer_only_features')
        self.assertEqual([e['ordinal']for e in events],[1,2])
        payload=self.event('embedding-tokenizer-input')['payload']
        self.assertEqual(payload['tokensTruncated'],[0]);self.assertEqual(payload['fullTokenCounts'],[5]);self.assertEqual(sum(features['attention_mask'][0]),5)
    def test_wrapper_ordinal_counts_failures_and_restores_context(self):
        def fail(q,c):raise RuntimeError('synthetic failure')
        s=SimpleNamespace(embedding_model=lambda:None,rerank_model=lambda:None,encode_embeddings=lambda t:None,rerank_candidates=fail,_embedding=None,_reranker=None)
        install_model_probes(s,self.probe)
        for _ in range(2):
            with self.assertRaises(RuntimeError):s.rerank_candidates('q',[parent()])
        self.assertEqual(self.probe.inference_ordinals['reranker'],2);self.assertEqual(self.probe.context,{'operation':'startup'})


class LoadPublicationTests(unittest.TestCase):
    def test_failed_model_validation_does_not_publish_invalid_model(self):
        path=Path(__file__).with_name('server.py');name='isolated_lifecycle_guard'
        spec=importlib.util.spec_from_file_location(name,path);s=importlib.util.module_from_spec(spec);sys.modules[name]=s;spec.loader.exec_module(s)
        module=SimpleNamespace(do_lower_case=False,tokenizer=CharacterTokenizer())
        class Embedding:
            default_prompt_name=None
            def __getitem__(self,i):return module
        fake=SimpleNamespace(SentenceTransformer=lambda *a,**k:Embedding(),CrossEncoder=lambda *a,**k:SimpleNamespace(tokenizer=CharacterTokenizer()))
        s.release_other_model=lambda kind:None;s.model_path=lambda c:'mock';s.model_options=lambda:('cpu',{})
        s._windows_runtime=SimpleNamespace(guard=lambda t,k:t)
        s.verify_loaded_model=lambda model,kind:(_ for _ in()).throw(WindowError('MODEL_RUNTIME_MISMATCH','synthetic'))
        with patch.dict(sys.modules,{'sentence_transformers':fake}):
            for method,field in [(s.embedding_model,'_embedding'),(s.rerank_model,'_reranker')]:
                with self.assertRaises(WindowError):method()
                self.assertIsNone(getattr(s,field))
        del sys.modules[name]


if __name__=='__main__':unittest.main()
