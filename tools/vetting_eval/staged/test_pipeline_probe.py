import hashlib
import json
import tempfile
import unittest
import subprocess
import sys
from types import SimpleNamespace
from unittest.mock import patch
from pathlib import Path
from pipeline_probe import PipelineProbe, TokenizerProbe

class FakeTokenizer:
    model_max_length = 4
    def __init__(self):
        self.calls = []
        self.last_actual = None
    def __call__(self, batch, **kwargs):
        self.calls.append((batch, kwargs.copy()))
        values = []
        for row in batch:
            texts = row if isinstance(row, (tuple, list)) else [row]
            values.append([1] + list(range(10, 10 + sum(len(text.split()) for text in texts))) + [2])
        if kwargs.get('truncation'):
            values = [row[:kwargs.get('max_length', self.model_max_length)] for row in values]
        features = {'input_ids': values, 'attention_mask': [[1] * len(row) for row in values]}
        if kwargs.get('truncation'):
            self.last_actual = features
        return features

class ProbeTests(unittest.TestCase):
    def warm_args(self, root):
        from test_staged_cache import Fixture
        import warm_staged_cache
        fixture=Fixture(root/'fixture')
        raw=root/'corpus.jsonl'
        raw.write_text(''.join(json.dumps(chunk)+'\n' for chunk in fixture.chunks),encoding='utf-8')
        fixture.plan['binding']={'fullOriginalChunks':warm_staged_cache.descriptor(raw)}
        fixture.plan_path.write_text(json.dumps(fixture.plan),encoding='utf-8')
        return SimpleNamespace(plan=fixture.plan_path,plan_sha=hashlib.sha256(fixture.plan_path.read_bytes()).hexdigest(),
            out=root/'output',execute=True,experiment_id='synthetic-start-failure',log_root=root/'registry',
            log_module=Path('C:/Coding/ConSense/ai_consense_service/tools/vetting_eval/experiment_log.py'))

    def test_started_registry_failure_preserves_actual_zero_prefix_and_does_not_initialize_server(self):
        import warm_staged_cache
        sys.path.insert(0,'C:/Coding/ConSense/ai_consense_service/tools/vetting_eval')
        import experiment_log
        with tempfile.TemporaryDirectory() as temp:
            args=self.warm_args(Path(temp))
            with patch.object(experiment_log,'append_record',side_effect=OSError('synthetic registry failure')),patch.object(warm_staged_cache,'import_local_server') as server:
                with self.assertRaises(OSError):warm_staged_cache.run(args)
            server.assert_not_called()
            failure=json.loads((args.out/'failure.json').read_text())
            self.assertEqual(failure['actualIndexAttempts'],0)
            self.assertEqual(failure['actualRetrievalAttempts'],0)
            self.assertEqual(failure['calls'],[])
            self.assertEqual(failure['error']['type'],'OSError')

    def test_existing_output_refusal_does_not_mutate_old_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            args=self.warm_args(Path(temp))
            args.out.mkdir()
            (args.out/'old_proof.json').write_text('{"completedCalls":64}')
            before={p.name:p.read_bytes() for p in args.out.iterdir()}
            result=subprocess.run([sys.executable,str(Path(__file__).with_name('warm_staged_cache.py')),'--plan',str(args.plan),'--plan-sha',args.plan_sha,'--out',str(args.out)],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            self.assertEqual({p.name:p.read_bytes() for p in args.out.iterdir()},before)

    def test_actual_tokenizer_args_return_identity_and_truncation_are_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            probe = PipelineProbe(Path(temp)/'probes', 'fixture')
            delegate = FakeTokenizer()
            wrapped = TokenizerProbe(delegate, probe, 'embedding')
            batch = ['one two three four five', 'one']
            result = wrapped(batch, truncation='longest_first', padding=True, max_length=4)
            self.assertIs(result, delegate.last_actual)
            self.assertIs(delegate.calls[0][0], batch)
            self.assertEqual(delegate.calls[0][1], {'truncation':'longest_first', 'padding':True, 'max_length':4})
            self.assertEqual(delegate.calls[1][1], {'truncation':False, 'padding':False})
            event = json.loads(next(probe.directory.glob('*-embedding-tokenizer-input.json')).read_text())
            self.assertEqual(event['payload']['fullTokenCounts'], [7,3])
            self.assertEqual(event['payload']['actualUnpaddedTokenCounts'], [4,3])
            self.assertEqual(event['payload']['tokensTruncated'], [3,0])

    def test_pair_inputs_order_and_attribute_writes_delegate(self):
        with tempfile.TemporaryDirectory() as temp:
            probe = PipelineProbe(Path(temp)/'probes', 'fixture')
            delegate = FakeTokenizer()
            wrapped = TokenizerProbe(delegate, probe, 'reranker')
            wrapped.model_max_length = 3
            self.assertEqual(delegate.model_max_length, 3)
            pairs = [('first query','first candidate'),('second query','second candidate')]
            features = wrapped(pairs, truncation=True, padding=True)
            self.assertIs(features, delegate.last_actual)
            event = json.loads(next(probe.directory.glob('*-reranker-tokenizer-input.json')).read_text())
            row = event['payload']['textBindingsInActualBatchOrder'][1]
            self.assertEqual([v['textSha256'] for v in row], [hashlib.sha256(v.encode()).hexdigest() for v in pairs[1]])

    def test_duplicate_text_sources_remain_ambiguous_and_vector_order_persisted(self):
        import numpy as np
        with tempfile.TemporaryDirectory() as temp:
            probe = PipelineProbe(Path(temp)/'probes', 'fixture')
            probe.bind_chunks([{'id':'one','content':'same text','role':'tender'}, {'id':'two','content':'same text','role':'standard'}])
            values = np.array([[1.,0.],[0.,1.]], dtype=np.float32)
            probe.vector_output(['same text','query text'], values, 0.5)
            event = json.loads(next(probe.directory.glob('*-embedding-output.json')).read_text())
            payload = event['payload']
            self.assertEqual([v['id'] for v in payload['inputsInOutputOrder'][0]['sourceMatches']], ['one','two'])
            self.assertEqual(payload['inputsInOutputOrder'][1]['sourceMatches'], [])
            np.testing.assert_array_equal(np.load(probe.directory/payload['vectors']['relativePath'],allow_pickle=False),values)
            manifest = probe.finish('completed', indexAttempts=0, retrievalAttempts=0)
            self.assertEqual(hashlib.sha256(Path(manifest['path']).read_bytes()).hexdigest(),manifest['sha256'])

    def test_write_failure_is_visible_and_never_changes_returned_features(self):
        with tempfile.TemporaryDirectory() as temp:
            probe = PipelineProbe(Path(temp)/'probes', 'fixture')
            delegate = FakeTokenizer()
            wrapped = TokenizerProbe(delegate, probe, 'embedding')
            (probe.directory/'000002-embedding-tokenizer-input.json').write_text('preserved')
            with self.assertRaises(FileExistsError):
                wrapped(['input'], truncation=True, padding=True)
            self.assertEqual((probe.directory/'000002-embedding-tokenizer-input.json').read_text(),'preserved')
            self.assertEqual(delegate.last_actual['input_ids'], [[1,10,2]])

    def test_original_tokenizer_failure_is_recorded_and_exception_identity_preserved(self):
        error = ValueError('synthetic original failure')
        class BrokenOriginal(FakeTokenizer):
            def __call__(self,*args,**kwargs):raise error
        with tempfile.TemporaryDirectory() as temp:
            probe=PipelineProbe(Path(temp)/'probes','fixture')
            with self.assertRaises(ValueError) as caught:
                TokenizerProbe(BrokenOriginal(),probe,'embedding')(['input'],truncation=True)
            self.assertIs(caught.exception,error)
            self.assertEqual([event['stage'] for event in probe.events], ['embedding-tokenizer-call-start','embedding-tokenizer-call-failure'])

    def test_full_diagnostic_failure_is_separate_from_original_tokenizer_success(self):
        class BrokenDiagnostic(FakeTokenizer):
            def __call__(self, batch, **kwargs):
                if kwargs.get('truncation') is False:
                    raise ValueError('synthetic diagnostic failure')
                return super().__call__(batch, **kwargs)
        with tempfile.TemporaryDirectory() as temp:
            probe = PipelineProbe(Path(temp)/'probes', 'fixture')
            delegate = BrokenDiagnostic()
            with self.assertRaisesRegex(RuntimeError, 'not classified as model failure'):
                TokenizerProbe(delegate,probe,'embedding')(['source'],truncation=True)
            event = json.loads(next(probe.directory.glob('*-embedding-probe-failure.json')).read_text())
            self.assertTrue(event['payload']['originalTokenizationSucceeded'])
            self.assertEqual(event['payload']['diagnosticFailure']['type'],'ValueError')
            self.assertEqual(delegate.last_actual['input_ids'], [[1,10,2]])

if __name__ == '__main__':
    unittest.main()
