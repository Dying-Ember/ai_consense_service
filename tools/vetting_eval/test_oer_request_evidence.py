import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from oer_request_evidence import RequestEvidence, _inspect_events


class RequestIsolationTest(unittest.TestCase):
    def test_concurrent_request_events_never_share_a_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            journal = RequestEvidence(temporary)
            def run(number):
                with journal.operation('/retrieve', {'number': number}):
                    for index in range(12):
                        journal.observe('model-input', {'number': number, 'index': index})
            with ThreadPoolExecutor(max_workers=4) as pool:
                list(pool.map(run, range(8)))
            receipts = list(Path(temporary).glob('*/receipt.json'))
            self.assertEqual(8, len(receipts))
            for path in receipts:
                directory = path.parent
                request = json.loads((directory / 'request.json').read_text(encoding='utf-8'))
                raw = (directory / 'events.jsonl').read_bytes()
                events = [json.loads(line) for line in raw.splitlines()]
                self.assertEqual(list(range(1, 13)), [e['ordinal'] for e in events])
                self.assertTrue(all(e['payload']['number'] == request['number'] for e in events))
                self.assertEqual(hashlib.sha256(raw).hexdigest(), json.loads(path.read_text())['events']['sha256'])
            journal.observe('outside-request', {'mustNotBeStored': True})
            self.assertEqual(8, len(list(Path(temporary).iterdir())))

    def test_failure_receipt_resets_context_and_keeps_observed_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            journal = RequestEvidence(temporary)
            with self.assertRaisesRegex(ValueError, 'inference failed'):
                with journal.operation('/retrieve', {'query': 'visible contract text'}):
                    journal.observe('actual-pair', {'fullTokens': 123})
                    raise ValueError('inference failed')
            receipt = json.loads(next(Path(temporary).glob('*/receipt.json')).read_text())
            self.assertEqual('failed', receipt['terminalStatus'])
            self.assertEqual(1, receipt['events']['count'])
            self.assertEqual('ValueError', receipt['error']['type'])
            self.assertIsNone(journal.active.get())

    def test_failed_event_encoding_does_not_invent_a_persisted_event(self):
        with tempfile.TemporaryDirectory() as temporary:
            journal = RequestEvidence(temporary)
            with self.assertRaises(ValueError):
                with journal.operation('/retrieve', {'query': 'source text'}):
                    journal.observe('scores', {'invalidScore': float('nan')})
            path = next(Path(temporary).glob('*/receipt.json'))
            receipt = json.loads(path.read_text())
            self.assertEqual('failed', receipt['terminalStatus'])
            self.assertEqual(0, receipt['events']['count'])
            self.assertEqual(0, receipt['events']['byteCount'])
            self.assertEqual(hashlib.sha256(b'').hexdigest(), receipt['events']['sha256'])
            self.assertEqual('complete', receipt['events']['integrityStatus'])

    def test_partial_file_append_is_hashed_and_declared_interrupted(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'events.jsonl'
            raw = b'{"ordinal":1,"stage":"first"}\n{"ordinal":2,"stage":"part'
            path.write_bytes(raw)
            receipt = _inspect_events(path)
            self.assertEqual(1, receipt['count'])
            self.assertEqual(len(raw), receipt['byteCount'])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt['sha256'])
            self.assertEqual('malformed_or_interrupted', receipt['integrityStatus'])


if __name__ == '__main__':
    unittest.main()
