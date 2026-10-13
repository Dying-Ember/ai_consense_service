"""In-memory transport contracts; no sockets, services, neural calls or databases."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlsplit

from official_business_capture import Capture, CaptureError, QUESTION_IDS, RawReply, digest


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def api(value):
    return RawReply(200, {"Content-Type": "application/json"}, encoded({"code": 0, "data": value}))


class MemoryTransport:
    kind = "in_memory_no_http"

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def request(self, method, url, headers, body, timeout):
        route = urlsplit(url).path + ("?" + urlsplit(url).query if urlsplit(url).query else "")
        self.calls.append((method, route, headers, body))
        if not self.responses:
            raise AssertionError("Unexpected collector request")
        expected, response = self.responses.pop(0)
        if route != expected:
            raise AssertionError("Unexpected collector route")
        if isinstance(response, Exception):
            raise response
        return response


class OfficialCaptureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.manifest = {"projectId": "official-isolated-test", "sourceScope": "fullset",
            "documents": [{"documentId": "1", "fileName": "GCC.pdf", "javaParsedSourceHash": "a" * 64,
                           "rawSourceSha256": "c" * 64},
                          {"documentId": "2", "fileName": "SCC.docx", "javaParsedSourceHash": "b" * 64}],
            "adviceIndex": {"indexKey": "actual-index-key", "indexSignature": "d" * 64},
            "vetting": {"sourceSnapshotSha256": "e" * 64, "canonicalCorpusIdentitySha256": "f" * 64}}
        self.questions = {"answerTextIncluded": False, "originalAnswerDocumentProductionAllowed": False,
            "uniqueTopLevelQuestions": 8, "split": "development_historically_exposed", "questions": [
                {"questionId": qid, "sameQuestionAnswerAllowedInPrompt": False,
                 "queryTextVisualTranscription": "Summarise the actual clause " + qid,
                 "expectedOutputKind": "flowchart" if qid == "1.6" else "text"}
                for qid in sorted(QUESTION_IDS)]}
        self.expected_rows = [{"documentId": d["documentId"], "fileName": d["fileName"],
                               "sourceHash": d["javaParsedSourceHash"], "included": True}
                              for d in self.manifest["documents"]]
        self.index = {"indexKey": "actual-index-key", "indexSignature": "d" * 64,
                      "evidenceBackend": "canonical", "sourceScope": "fullset", "vectorReady": True,
                      "embeddingReady": True, "stale": False, "eligibleDocuments": 2, "indexedDocuments": 2}
        self.identity = {"profileId": "minimax-cn", "model": "MiniMax-M3", "provider": "minimax"}
        self.response = {"modelIdentity": self.identity, "grounded": True, "messageId": 42,
                         "sourceScope": "fullset",
                         "content": {"en": "First captured text; no graph."}, "citations": ["[1]"]}
        self.advice = "/api/advice/official-isolated-test"
        self.vetting = "/api/vetting/official-isolated-test"

    def args(self, mode="advice", **overrides):
        sources = self.root / "frozen-sources.json"
        questions = self.root / "questions-only.json"
        sources.write_bytes(encoded(self.manifest))
        questions.write_bytes(encoded(self.questions))
        value = SimpleNamespace(mode=mode, execute=True, base_url="http://127.0.0.1:18083",
            project_id="official-isolated-test", profile="minimax-cn", model="MiniMax-M3",
            sources_manifest=str(sources), sources_sha256=digest(sources.read_bytes()),
            questions=str(questions), questions_sha256=digest(questions.read_bytes()), question_id="1.6",
            out=str(self.root / "new-run"), timeout=1.0, max_wait=10.0, poll_interval=0.01)
        for key, item in overrides.items():
            setattr(value, key, item)
        return value

    def advice_responses(self, result=None):
        return [(self.advice + "/sources?scope=fullset", api(self.expected_rows)),
                (self.advice + "/index/status?scope=fullset", api(self.index)),
                (self.advice + "/ask", api(self.response if result is None else result)),
                (self.advice + "/sources?scope=fullset", api(self.expected_rows)),
                (self.advice + "/index/status?scope=fullset", api(self.index))]

    def run_with(self, args, responses):
        transport = MemoryTransport(responses)
        capture = Capture(args, transport=transport, sleeper=lambda seconds: None)
        capture.run()
        self.assertFalse(transport.responses)
        return capture.receipt, transport

    def read_receipt(self, args):
        return json.loads((Path(args.out) / "capture-receipt.json").read_text(encoding="utf-8"))

    def test_first_question_16_text_is_saved_and_graph_unsupported_without_grading(self):
        args = self.args()
        receipt, transport = self.run_with(args, self.advice_responses())
        self.assertEqual("collected_unscored", receipt["status"])
        self.assertEqual("unsupported", receipt["questions"][0]["graphOutputStatus"])
        self.assertEqual("not_scored", receipt["questions"][0]["textAssessment"])
        self.assertEqual("in_memory_no_http", receipt["transportKind"])
        self.assertEqual(["rawSourceSha256"], receipt["offlineProvenanceNotLiveVerified"]["1"])
        posted = json.loads(transport.calls[2][3])
        self.assertEqual({"question", "scope", "replyToMessageId"}, set(posted))
        self.assertIsNone(posted["replyToMessageId"])
        request = receipt["requests"][2]
        raw = (Path(args.out) / request["rawResponseFile"]).read_bytes()
        self.assertEqual(api(self.response).body, raw)
        self.assertEqual(digest(raw), request["responseSha256"])

    def test_graph_field_present_is_unvalidated_not_correct(self):
        response = copy.deepcopy(self.response)
        response["flowchart"] = {"nodes": [], "edges": []}
        receipt, _ = self.run_with(self.args(), self.advice_responses(response))
        self.assertEqual("present_unvalidated", receipt["questions"][0]["graphOutputStatus"])
        self.assertEqual("not_scored", receipt["qualityAssessment"])

    def test_single_source_freeze_rejects_before_any_transport(self):
        self.manifest["documents"] = self.manifest["documents"][:1]
        args = self.args()
        transport = MemoryTransport([])
        with self.assertRaisesRegex(CaptureError, "at_least_two"):
            Capture(args, transport).run()
        self.assertFalse(transport.calls)
        self.assertEqual("failed", self.read_receipt(args)["status"])

    def test_exact_source_hash_mismatch_stops_before_generation(self):
        bad = copy.deepcopy(self.expected_rows)
        bad[1]["sourceHash"] = "0" * 64
        args = self.args()
        transport = MemoryTransport([(self.advice + "/sources?scope=fullset", api(bad))])
        with self.assertRaisesRegex(CaptureError, "parsed_hash"):
            Capture(args, transport).run()
        self.assertEqual(1, len(transport.calls))

    def test_added_source_is_not_silently_ignored(self):
        bad = self.expected_rows + [{"documentId": "3", "included": True}]
        args = self.args()
        transport = MemoryTransport([(self.advice + "/sources?scope=fullset", api(bad))])
        with self.assertRaisesRegex(CaptureError, "id_set"):
            Capture(args, transport).run()

    def test_stale_or_different_index_stops_before_generation(self):
        bad = dict(self.index, indexSignature="0" * 64)
        args = self.args()
        transport = MemoryTransport([(self.advice + "/sources?scope=fullset", api(self.expected_rows)),
            (self.advice + "/index/status?scope=fullset", api(bad))])
        with self.assertRaisesRegex(CaptureError, "canonical_index"):
            Capture(args, transport).run()
        self.assertFalse(any(call[0] == "POST" for call in transport.calls))

    def test_model_identity_mismatch_keeps_raw_and_fails(self):
        response = dict(self.response, modelIdentity={"profileId": "bonsai", "model": "other"})
        args = self.args()
        transport = MemoryTransport(self.advice_responses(response)[:3])
        with self.assertRaisesRegex(CaptureError, "model_identity"):
            Capture(args, transport).run()
        receipt = self.read_receipt(args)
        self.assertEqual(200, receipt["requests"][-1]["status"])
        self.assertEqual("failed", receipt["status"])

    def test_529_saves_exact_failure_bytes_safe_headers_and_does_not_retry(self):
        args = self.args()
        reply = RawReply(529, {"Content-Type": "application/json", "X-Request-ID": "actual-request",
            "Authorization": "secret", "Set-Cookie": "secret", "X-Api-Key": "secret"}, b'{"error":"overloaded"}')
        transport = MemoryTransport([(self.advice + "/sources?scope=fullset", reply)])
        with self.assertRaisesRegex(CaptureError, "http_first_attempt"):
            Capture(args, transport).run()
        receipt = self.read_receipt(args)
        record = receipt["requests"][0]
        self.assertEqual({"content-type": "application/json", "x-request-id": "actual-request"}, record["safeHeaders"])
        self.assertEqual(reply.body, (Path(args.out) / record["rawResponseFile"]).read_bytes())
        self.assertEqual(1, len(transport.calls))

    def test_existing_run_is_never_overwritten_even_in_failure_finally(self):
        args = self.args()
        Path(args.out).mkdir()
        existing = Path(args.out) / "capture-receipt.json"
        existing.write_bytes(b"preserve first run")
        with self.assertRaisesRegex(CaptureError, "new_output"):
            Capture(args, MemoryTransport([])).run()
        self.assertEqual(b"preserve first run", existing.read_bytes())

    def test_missing_execute_switch_has_no_transport_or_output(self):
        args = self.args(execute=False)
        transport = MemoryTransport([])
        with self.assertRaisesRegex(CaptureError, "execute_switch"):
            Capture(args, transport).run()
        self.assertFalse(transport.calls)
        self.assertFalse(Path(args.out).exists())

    def test_question_answer_flag_or_frozen_sha_rejects_before_transport(self):
        self.questions["answerTextIncluded"] = True
        args = self.args()
        transport = MemoryTransport([])
        with self.assertRaisesRegex(CaptureError, "question_only"):
            Capture(args, transport).run()
        self.assertFalse(transport.calls)

    def test_frozen_question_bytes_changed_rejects_before_transport(self):
        args = self.args()
        Path(args.questions).write_bytes(encoded(dict(self.questions, split="changed")))
        transport = MemoryTransport([])
        with self.assertRaisesRegex(CaptureError, "frozen_input_sha"):
            Capture(args, transport).run()
        self.assertFalse(transport.calls)

    def test_all_eight_questions_are_independent_new_question_posts(self):
        args = self.args(question_id=None)
        responses = [(self.advice + "/sources?scope=fullset", api(self.expected_rows)),
                     (self.advice + "/index/status?scope=fullset", api(self.index))]
        for unused in sorted(QUESTION_IDS):
            responses.extend([(self.advice + "/ask", api(self.response)),
                              (self.advice + "/sources?scope=fullset", api(self.expected_rows)),
                              (self.advice + "/index/status?scope=fullset", api(self.index))])
        receipt, transport = self.run_with(args, responses)
        posts = [json.loads(call[3]) for call in transport.calls if call[0] == "POST"]
        self.assertEqual(8, len(posts))
        self.assertTrue(all(post["replyToMessageId"] is None for post in posts))
        self.assertEqual(QUESTION_IDS, {row["questionId"] for row in receipt["questions"]})

    def test_after_answer_source_drift_retains_first_answer_but_run_fails(self):
        responses = self.advice_responses()
        drift = copy.deepcopy(self.expected_rows)
        drift[0]["sourceHash"] = "0" * 64
        responses[3] = (self.advice + "/sources?scope=fullset", api(drift))
        args = self.args()
        with self.assertRaises(CaptureError):
            Capture(args, MemoryTransport(responses[:4])).run()
        receipt = self.read_receipt(args)
        self.assertEqual("failed", receipt["status"])
        self.assertEqual("1.6", receipt["questions"][0]["questionId"])

    def completed_job(self, **updates):
        job = {"id": "new-real-job", "status": "COMPLETED", "sourceSnapshotSha256": "e" * 64,
               "sourceSetStale": False, "modelIdentity": self.identity,
               "coverage": {"canonicalCorpusIdentitySha256": "f" * 64,
                   "semanticTopics": [{"aggregateReviewStatus": "partial", "semanticScopeVerified": False}]}}
        job.update(updates)
        return job

    def vetting_responses(self):
        job = self.completed_job()
        responses = [(self.advice + "/sources?scope=fullset", api(self.expected_rows)),
            (self.vetting + "/runs/latest", api(None)), (self.vetting + "/runs?lang=en", api(job)),
            (self.vetting + "/runs/latest", api(job)), (self.vetting + "/findings", api([])),
            (self.vetting + "/metrics", api({"total": 0})), (self.vetting + "/runs/latest", api(job))]
        for ext, body in (("json", encoded({"job": job, "findings": []})),
                          ("docx", b"PK\x03\x04actual binary fixture"), ("pdf", b"%PDF-actual binary fixture")):
            responses.extend([(self.vetting + "/runs/latest", api(job)),
                (self.vetting + "/report." + ext + "?lang=en", RawReply(200, {}, body)),
                (self.vetting + "/runs/latest", api(job))])
        responses.append((self.advice + "/sources?scope=fullset", api(self.expected_rows)))
        return responses

    def test_zero_findings_and_partial_are_collected_without_no_comment_claim(self):
        args = self.args(mode="vetting")
        receipt, transport = self.run_with(args, self.vetting_responses())
        self.assertEqual(0, receipt["findingCount"])
        self.assertEqual("partial", receipt["lastJob"]["coverage"]["semanticTopics"][0]["aggregateReviewStatus"])
        self.assertEqual("collected_unscored", receipt["status"])
        self.assertTrue((Path(args.out) / "report.json").exists())
        self.assertFalse(any("/status" in call[1] or "/review" in call[1] for call in transport.calls))

    def test_existing_active_job_blocks_start(self):
        args = self.args(mode="vetting")
        transport = MemoryTransport([(self.advice + "/sources?scope=fullset", api(self.expected_rows)),
            (self.vetting + "/runs/latest", api({"id": "someone-elses-job", "status": "RUNNING"}))])
        with self.assertRaisesRegex(CaptureError, "existing_active"):
            Capture(args, transport).run()
        self.assertFalse(any(call[0] == "POST" for call in transport.calls))

    def test_latest_job_drift_never_labels_another_job_report_as_this_run(self):
        args = self.args(mode="vetting")
        responses = self.vetting_responses()[:8]
        responses[-1] = (self.vetting + "/runs/latest", api(self.completed_job(id="different-job")))
        transport = MemoryTransport(responses)
        with self.assertRaisesRegex(CaptureError, "source_or_id"):
            Capture(args, transport).run()
        self.assertFalse(any("/report." in call[1] for call in transport.calls))

    def test_latest_job_changes_during_export_retains_raw_but_run_fails(self):
        args = self.args(mode="vetting")
        responses = self.vetting_responses()[:10]
        responses[-1] = (self.vetting + "/runs/latest", api(self.completed_job(id="newer-job")))
        with self.assertRaisesRegex(CaptureError, "source_or_id"):
            Capture(args, MemoryTransport(responses)).run()
        self.assertEqual("failed", self.read_receipt(args)["status"])
        self.assertTrue((Path(args.out) / "report.json").exists())

    def test_missing_vetting_frozen_corpus_never_starts_generation(self):
        del self.manifest["vetting"]["canonicalCorpusIdentitySha256"]
        args = self.args(mode="vetting")
        transport = MemoryTransport([(self.advice + "/sources?scope=fullset", api(self.expected_rows))])
        with self.assertRaisesRegex(CaptureError, "frozen_vetting_identity"):
            Capture(args, transport).run()
        self.assertFalse(any(call[0] == "POST" for call in transport.calls))

    def test_failed_job_preserves_actual_terminal_state_and_stops_without_exports(self):
        args = self.args(mode="vetting")
        job = self.completed_job(status="FAILED")
        responses = [(self.advice + "/sources?scope=fullset", api(self.expected_rows)),
            (self.vetting + "/runs/latest", api(None)), (self.vetting + "/runs?lang=en", api(job))]
        transport = MemoryTransport(responses)
        with self.assertRaisesRegex(CaptureError, "terminal_failure"):
            Capture(args, transport).run()
        self.assertEqual("FAILED", self.read_receipt(args)["lastJob"]["status"])
        self.assertFalse(any("/report." in call[1] for call in transport.calls))


if __name__ == "__main__":
    unittest.main()
