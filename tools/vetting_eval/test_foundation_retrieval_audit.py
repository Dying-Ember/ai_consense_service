"""Synthetic file fixtures test audit mechanics, never native contract accuracy."""
import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("foundation_retrieval_audit", pathlib.Path(__file__).with_name("foundation_retrieval_audit.py"))
f = importlib.util.module_from_spec(spec)
spec.loader.exec_module(f)


class FoundationGuards(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.corpus = {"a": {"id": "a", "documentId": "1", "role": "tender", "sourceHash": "s", "content": "abc",
                              "parts": [{"blockId": "b", "startOffset": 0, "endOffset": 3, "text": "abc"}]},
                       "b": {"id": "b", "documentId": "1", "role": "tender", "sourceHash": "s", "content": "def",
                              "parts": [{"blockId": "b", "startOffset": 3, "endOffset": 6, "text": "def"}]}}
        self.target = {"requiredParts": [{"blockId": "b", "startOffset": 0, "endOffset": 6}]}
        self.binding = {"status": "bound", "documentId": "1", "sourceHash": "s"}

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, value):
        p = self.root / name
        p.write_text(json.dumps(value), encoding="utf-8")
        return f.descriptor(p)

    def test_duplicate_json_key_rejected(self):
        with self.assertRaises(f.AuditError): f.loads('{"a":1,"a":2}')

    def test_nonfinite_json_rejected(self):
        with self.assertRaises(f.AuditError): f.loads('{"a":NaN}')

    def test_descriptor_tamper_rejected(self):
        d = self.write("x.json", {"x": 1})
        pathlib.Path(d["path"]).write_text('{"x":2}', encoding="utf-8")
        with self.assertRaises(f.AuditError): f.read_desc(d)

    def test_external_sha_rejected(self):
        d = self.write("x.json", {})
        with self.assertRaises(f.AuditError): f.read_external(d["path"], "0" * 64)

    def test_probe_path_escape_rejected(self):
        d = self.write("x.json", {})
        d = {"relativePath": "../x.json", "bytes": d["bytes"], "sha256": d["sha256"]}
        with self.assertRaises(f.AuditError): f.verified_path(d, self.root / "nested")

    def test_unknown_id_rejected(self):
        with self.assertRaises(f.AuditError): f.ids_unique(["outside"], self.corpus, "scope")

    def test_duplicate_id_rejected(self):
        with self.assertRaises(f.AuditError): f.ids_unique(["a", "a"], self.corpus, "scope")

    def test_nonfinite_score_rejected(self):
        with self.assertRaises(f.AuditError): f.finite(float("inf"))

    def test_bool_score_rejected(self):
        with self.assertRaises(f.AuditError): f.finite(True)

    def test_wrong_source_identity_rejected(self):
        with self.assertRaises(f.AuditError): f.verify_identity({"id": "a", "sourceHash": "wrong"}, self.corpus)

    def test_wrong_content_sha_rejected(self):
        with self.assertRaises(f.AuditError): f.verify_identity({"id": "a", "contentSha256": "wrong"}, self.corpus)

    def test_partial_target_is_not_complete(self):
        r = f.support_for_ids(self.target, self.binding, ["a"], self.corpus)
        self.assertFalse(r["coverage"])
        self.assertEqual(r["rank"], 1)

    def test_adjacent_parts_join_without_truncation(self):
        r = f.support_for_ids(self.target, self.binding, ["b", "a"], self.corpus)
        self.assertTrue(r["coverage"])

    def test_gap_is_not_complete(self):
        self.corpus["b"]["parts"][0]["startOffset"] = 4
        self.assertFalse(f.support_for_ids(self.target, self.binding, ["a", "b"], self.corpus)["coverage"])

    def test_new_chunk_ids_are_matched_by_source_parts(self):
        new = {"new1": dict(self.corpus["a"], id="new1"), "new2": dict(self.corpus["b"], id="new2")}
        self.assertTrue(f.support_for_ids(self.target, self.binding, ["new1", "new2"], new)["coverage"])

    def test_other_source_does_not_cover(self):
        self.corpus["b"]["sourceHash"] = "different"
        self.assertFalse(f.support_for_ids(self.target, self.binding, ["a", "b"], self.corpus)["coverage"])

    def test_unbound_fixture_is_unknown(self):
        r = f.support_for_ids({}, {"status": "unknown", "reason": "unbound"}, ["a"], self.corpus)
        self.assertIsNone(r["coverage"])

    def test_native_source_change_is_unknown(self):
        r = f.native_fixture_binding({"bindingStatus": "bound_native_source_observation", "documentId": "1", "rawSourceSha256": "old"}, {"1": {"originalBytes": {"sha256": "new"}}}, {}, {})
        self.assertEqual(r["status"], "unknown")

    def test_missing_source_is_unknown(self):
        r = f.native_fixture_binding({"bindingStatus": "bound_native_source_observation", "documentId": "2"}, {}, {}, {})
        self.assertEqual(r["status"], "unknown")

    def test_persistence_absent_is_unknown_not_zero(self):
        self.assertEqual(f.import_persistence(None, {}, {})["status"], "unknown")

    def test_persistence_wrong_execution_rejected(self):
        d = self.write("p.json", {"status": "completed", "inputs": {"corpusManifest": {"sha256": "other"}, "actualCpuResult": {"sha256": "c"}}})
        with self.assertRaises(f.AuditError): f.import_persistence(d, {"sha256": "b"}, {"sha256": "c"})

    def test_token_loss_arithmetic_rejected(self):
        e = {"stage": "embedding-tokenizer-input", "eventOrdinal": 1, "context": {"operation": "index"},
             "payload": {"fullTokenCounts": [7], "actualUnpaddedTokenCounts": [4], "tokensTruncated": [2], "textBindingsInActualBatchOrder": [[]]}}
        with self.assertRaises(f.AuditError): f.token_event_rows(e, self.corpus)

    def test_token_binding_tamper_rejected(self):
        e = {"stage": "embedding-tokenizer-input", "eventOrdinal": 1, "context": {"operation": "index"},
             "payload": {"fullTokenCounts": [7], "actualUnpaddedTokenCounts": [4], "tokensTruncated": [3],
                         "textBindingsInActualBatchOrder": [[{"textSha256": "wrong", "chars": 3, "sourceMatches": [{"id": "a", "contentSha256": "wrong"}]}]]}}
        with self.assertRaises(f.AuditError): f.token_event_rows(e, self.corpus)

    def result(self):
        stages = {s: {"coverage": True, "rank": 1} for s in f.STAGES}
        return {"protocol": f.PROTOCOL, "fixtures": {"sha256": "f"}, "queries": [{"originalTopicOrdinal": 1, "querySha256": "q", "role": "tender"}],
                "targets": [{"targetId": "x", "originalTopicOrdinal": 1, "stages": stages}], "layerMetrics": [], "tokenSummary": []}

    def test_compare_changed_fixture_rejected(self):
        a, b = self.result(), self.result(); b["fixtures"]["sha256"] = "other"
        with self.assertRaises(f.AuditError): f.compare(a, b, {}, {})

    def test_compare_changed_query_rejected(self):
        a, b = self.result(), self.result(); b["queries"][0]["querySha256"] = "other"
        with self.assertRaises(f.AuditError): f.compare(a, b, {}, {})

    def test_compare_unknown_not_failure_or_improvement(self):
        a, b = self.result(), self.result(); b["targets"][0]["stages"]["dense"] = {"coverage": None, "rank": None}
        r = f.compare(a, b, {}, {}); row = next(x for x in r["deltas"] if x["stage"] == "dense")
        self.assertIsNone(row["coverageChanged"]); self.assertIsNone(row["rankDelta"])

    def test_compare_does_not_assume_good_rank(self):
        a, b = self.result(), self.result(); b["targets"][0]["stages"]["dense"]["rank"] = 20
        r = f.compare(a, b, {}, {}); self.assertEqual(r["deltas"][0]["rankDelta"], 19); self.assertIsNone(r["semanticAccuracyConclusion"])

    def test_fresh_output_refuses_overwrite(self):
        p = self.root / "out"; p.mkdir()
        with self.assertRaises(f.AuditError): f.save_fresh(p, {})

    def test_readonly_source_identity_has_ast_line_ranges(self):
        p = self.root / "source.py"; p.write_text("def signature(chunks):\n    return {'signatureVersion': 2, 'corpus': 'x'}\n", encoding="utf-8")
        r = f.source_identity(f.descriptor(p)); self.assertFalse(r["badCacheReuseObserved"]); self.assertEqual(r["functions"][0]["firstLine"], 1)

    def ranking_fixture(self):
        request = {"projectId": "p", "query": "contract", "role": "tender", "limit": 1, "candidates": 2}
        identity = lambda id_: {"id": id_, "documentId": "1", "role": "tender", "sourceHash": "s", "contentSha256": f.text_sha(self.corpus[id_]["content"])}
        context = {"request": request}
        fused = [{"id": id_, "score": 2 / (60 + rank)} for rank, id_ in enumerate(("a", "b"), 1)]
        stages = {
            "dense-candidates": {"orderedCandidates": [dict(identity("a"), score=.9), dict(identity("b"), score=.2)]},
            "bm25-candidates": {"allScopeScores": [{"id":"a", "score":2}, {"id":"b", "score":1}], "orderedCandidateIds":["a","b"]},
            "rrf-candidates": {"constant":60, "allFusedInOrder":fused, "submittedToRerankIds":["a","b"]},
            "reranker-call-start": {"querySha256":f.text_sha("contract"), "candidateIdentitiesInInputOrder":[identity("a"),identity("b")]},
            "reranker-output": {"querySha256":f.text_sha("contract"), "scoresInCandidateOrder":[.1,.8], "candidatesInScoreOrder":[identity("a"),identity("b")]},
            "retrieval-final-ranking": {"allRerankedInOrder":[{"id":"b", "score":.8},{"id":"a", "score":.1}], "returnedIds":["b"]}}
        payloads = {(1,k):(v, context) for k,v in stages.items()}
        response = self.write("response.json", {"hits":[{"id":"b", "payload":self.corpus["b"]}]})
        call = {"ordinal":1, "orderedIds":["b"], "responseArtifact":response}
        return request, payloads, call

    def test_actual_layer_order_verified_with_misnamed_probe(self):
        request, payloads, call = self.ranking_fixture()
        stages, checks = f.rankings_for(call, payloads, self.corpus, request)
        self.assertEqual(stages["rerank_input"], ["a","b"])
        self.assertEqual(stages["returned"], ["b"])
        self.assertEqual(checks["rerankerProbeCandidatesInScoreOrderObservedOrder"], "input_order")
        self.assertIsNotNone(checks["probeNamingWarning"])

    def test_rrf_changed_arithmetic_rejected(self):
        request, payloads, call = self.ranking_fixture(); payloads[(1,"rrf-candidates")][0]["allFusedInOrder"][0]["score"] += .1
        with self.assertRaises(f.AuditError): f.rankings_for(call, payloads, self.corpus, request)

    def test_rerank_input_order_change_rejected(self):
        request, payloads, call = self.ranking_fixture(); payloads[(1,"reranker-call-start")][0]["candidateIdentitiesInInputOrder"].reverse()
        with self.assertRaises(f.AuditError): f.rankings_for(call, payloads, self.corpus, request)

    def test_changed_returned_payload_rejected(self):
        request, payloads, call = self.ranking_fixture()
        changed = copy.deepcopy(self.corpus["b"]); changed["parts"][0]["text"] = "wrong"
        call["responseArtifact"] = self.write("changed.json", {"hits":[{"id":"b", "payload":changed}]})
        with self.assertRaises(f.AuditError): f.rankings_for(call, payloads, self.corpus, request)

    def test_bm25_wrong_scope_rejected(self):
        request, payloads, call = self.ranking_fixture(); payloads[(1,"bm25-candidates")][0]["allScopeScores"] = [{"id":"a","score":2}]
        with self.assertRaises(f.AuditError): f.rankings_for(call, payloads, self.corpus, request)

    def test_strip_binding_is_observed_not_invented(self):
        self.corpus["a"]["content"] = " abc "
        identity = {"id":"a", "contentSha256": f.text_sha(" abc ")}
        e = {"stage":"embedding-tokenizer-input", "eventOrdinal":1,"context":{"operation":"index"},
             "payload":{"fullTokenCounts":[5],"actualUnpaddedTokenCounts":[5],"tokensTruncated":[0],
                        "textBindingsInActualBatchOrder":[[{"textSha256":f.text_sha("abc"),"chars":3,"sourceMatches":[identity]}]],
                        "actualTokenizerOptions":{"max_length":512},"effectiveModelMaxLength":8192}}
        r = f.token_event_rows(e, self.corpus)
        self.assertEqual(r[0]["sourceTextTransformBindings"][0]["observedTextTransform"], "strip")

    def test_unknown_token_transform_rejected(self):
        self.corpus["a"]["content"] = "ABC"
        e = {"stage":"embedding-tokenizer-input", "eventOrdinal":1,"context":{"operation":"index"},
             "payload":{"fullTokenCounts":[5],"actualUnpaddedTokenCounts":[5],"tokensTruncated":[0],
                        "textBindingsInActualBatchOrder":[[{"textSha256":f.text_sha("abc"),"chars":3,"sourceMatches":[{"id":"a","contentSha256":f.text_sha("ABC")}]}]]}}
        with self.assertRaises(f.AuditError): f.token_event_rows(e, self.corpus)


if __name__ == "__main__":
    unittest.main()
