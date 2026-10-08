"""Offline evidence-integrity checks; these are not model quality experiments."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from experiment_log import append_record, events, latest_records, parameter_difference, rebuild_unlocked, registry_lock
from fact_stages import model_call


def record(experiment_id="control", **changes):
    return {"schemaVersion": 1, "experimentId": experiment_id, "status": "started", "scope": "offline unit test",
            "parameters": {"model": "test-model", "temperature": .2}, "inputFingerprints": {"source": "fixed"},
            "measurements": {"wallSeconds": None}, "evaluation": {"semanticAcceptance": "pending"}, "artifacts": [], **changes}


class ExperimentLogTest(unittest.TestCase):
    def test_planned_started_completed_preserves_plan_bytes_and_call_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            planned = record(status="planned", measurements={"calls": []})
            proof = append_record(root, planned, "preregistered")
            plan_bytes = Path(proof["path"]).read_bytes()
            started = copy.deepcopy(planned)
            started["status"] = "started"
            started["measurements"]["calls"] = [{"stage": "first", "status": "completed", "requestSha256": "first-original"}]
            append_record(root, started, "started")
            completed = copy.deepcopy(started)
            completed["status"] = "completed"
            completed["measurements"]["calls"].append({"stage": "second", "status": "completed", "requestSha256": "second-original"})
            append_record(root, completed, "finished")
            self.assertEqual(plan_bytes, Path(proof["path"]).read_bytes())
            history = [event for _, event in events(root)]
            self.assertEqual(["planned", "started", "completed"], [event["record"]["status"] for event in history])
            self.assertEqual(["preregistered", "started", "finished"], [event["eventType"] for event in history])
            latest = latest_records(root)["control"]["record"]
            self.assertEqual(planned["parameters"], latest["parameters"])
            self.assertEqual(planned["inputFingerprints"], latest["inputFingerprints"])
            self.assertEqual(started["measurements"]["calls"], latest["measurements"]["calls"][:1])

    def test_planned_start_still_rejects_changed_parameters_or_fingerprints(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = record(status="planned")
            proof = append_record(root, original, "preregistered")
            original_bytes = Path(proof["path"]).read_bytes()
            for field in ("parameters", "inputFingerprints"):
                altered = copy.deepcopy(original)
                altered["status"] = "started"
                altered[field]["new"] = "different"
                with self.subTest(field=field), self.assertRaisesRegex(ValueError, field + " changed"):
                    append_record(root, altered, "started")
            self.assertEqual(1, len(events(root)))
            self.assertEqual(original_bytes, Path(proof["path"]).read_bytes())

    def test_started_completion_still_cannot_drop_or_rewrite_call_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            planned = record(status="planned", measurements={"calls": []})
            append_record(root, planned, "preregistered")
            started = copy.deepcopy(planned)
            started["status"] = "started"
            started["measurements"]["calls"] = [{"requestSha256": "immutable-original"}]
            proof = append_record(root, started, "started")
            saved = Path(proof["path"]).read_bytes()
            for calls in ([], [{"requestSha256": "rewritten"}]):
                changed = copy.deepcopy(started)
                changed["status"] = "completed"
                changed["measurements"]["calls"] = calls
                with self.subTest(calls=calls), self.assertRaisesRegex(ValueError, "drop or modify recorded calls"):
                    append_record(root, changed, "finished")
            self.assertEqual(2, len(events(root)))
            self.assertEqual(saved, Path(proof["path"]).read_bytes())

    def test_completed_or_failed_cannot_restart_even_with_started_event(self):
        for terminal in ("completed", "failed"):
            with self.subTest(terminal=terminal), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                finished = record(status=terminal)
                proof = append_record(root, finished, "finished")
                original_bytes = Path(proof["path"]).read_bytes()
                restarted = copy.deepcopy(finished)
                restarted["status"] = "started"
                with self.assertRaisesRegex(ValueError, "finished experiment"):
                    append_record(root, restarted, "started")
                self.assertEqual(1, len(events(root)))
                self.assertEqual(original_bytes, Path(proof["path"]).read_bytes())

    def test_only_explicit_started_event_bridges_plan_and_other_statuses_do_not(self):
        for status, event_type in (("planned", "observation"), ("prepared", "started"), ("unknown", "started")):
            with self.subTest(status=status, event=event_type), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                original = record(status=status)
                append_record(root, original, "preregistered")
                changed = copy.deepcopy(original)
                changed["status"] = "started"
                with self.assertRaisesRegex(ValueError, "finished experiment"):
                    append_record(root, changed, event_type)
                self.assertEqual(1, len(events(root)))

    def test_later_audit_keeps_failed_run_and_original_observations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = record(status="failed", measurements={"error": "timeout", "wallSeconds": 3})
            proof = append_record(root, original, "finished")
            original_bytes = Path(proof["path"]).read_bytes()
            audited = copy.deepcopy(original)
            audited["evaluation"] = {"semanticAcceptance": "not_evaluated", "reason": "No complete response"}
            append_record(root, audited, "independent_audit")
            self.assertEqual(original_bytes, Path(proof["path"]).read_bytes())
            self.assertEqual(2, len(events(root)))
            latest = latest_records(root)["control"]["record"]
            self.assertEqual("failed", latest["status"])
            self.assertEqual("timeout", latest["measurements"]["error"])
            self.assertEqual("not_evaluated", latest["evaluation"]["semanticAcceptance"])

    def test_parameter_or_input_change_requires_a_new_experiment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = record()
            append_record(root, original)
            for field in ("parameters", "inputFingerprints"):
                altered = copy.deepcopy(original)
                altered[field]["new"] = "changed"
                with self.assertRaises(ValueError):
                    append_record(root, altered)
            self.assertEqual(1, len(events(root)))

    def test_stale_audit_cannot_replace_completed_observations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stale = record()
            append_record(root, stale)
            completed = record(status="completed", measurements={"calls": [{"wallSeconds": 2}]})
            append_record(root, completed, "finished")
            stale["evaluation"] = {"semanticAcceptance": "reviewed"}
            with self.assertRaises(ValueError):
                append_record(root, stale, "independent_audit")
            self.assertEqual("completed", latest_records(root)["control"]["record"]["status"])

    def test_interrupted_pending_write_does_not_block_the_next_event(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            append_record(root, record())
            (root / "records" / "control" / "interrupted.pending").write_text('{"partial":', encoding="utf-8")
            append_record(root, record(status="failed"), "finished")
            self.assertEqual(2, len(events(root)))
            self.assertEqual("failed", latest_records(root)["control"]["record"]["status"])

    def test_view_recovers_persisted_event_without_index_and_preserves_unknown_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            append_record(root, record())
            # Simulates the recoverable state after record fsync but before index write.
            (root / "events.jsonl").write_text("", encoding="utf-8")
            with registry_lock(root):
                rebuild_unlocked(root)
            self.assertEqual(1, len((root / "events.jsonl").read_text(encoding="utf-8").splitlines()))
            view = json.loads((root / "comparison.json").read_text(encoding="utf-8"))
            self.assertIsNone(view["experiments"][0]["record"]["measurements"]["wallSeconds"])

    def test_comparison_computes_all_actual_changes_not_just_declared_factors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            append_record(root, record())
            append_record(root, record("candidate", baselineId="control", changedFactors=["model"],
                                       parameters={"model": "bigger", "temperature": 0, "num_ctx": 12288}))
            rows = json.loads((root / "comparison.json").read_text(encoding="utf-8"))["experiments"]
            candidate = next(row for row in rows if row["record"]["experimentId"] == "candidate")
            self.assertEqual({"/model", "/temperature", "/num_ctx"}, {row["parameter"] for row in candidate["parameterDifferences"]})

    def test_nested_parameter_and_literal_dot_key_cannot_hide_each_other(self):
        changes = parameter_difference({"a": {"b": 0}, "a.b": 2}, {"a": {"b": 1}, "a.b": 2})
        self.assertEqual(["/a/b"], [row["parameter"] for row in changes])

    def test_unsafe_id_is_rejected_before_files_are_created(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "registry"
            with self.assertRaises(ValueError):
                append_record(root, record("../outside"))
            self.assertFalse(root.exists())

    def test_failed_model_call_records_exact_request_options_and_failure_event(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = record()
            append_record(root / "registry", run)
            body = {"model": "test-model", "options": {"num_ctx": 8192, "num_predict": 100}, "stream": False}
            def observed(actual, prefix, result):
                run["measurements"] = {"options": actual["options"], "status": result["status"], "error": result["error"]}
                run["artifacts"] = [result["request"]]
                append_record(root / "registry", run, "call_finished")
            with patch("fact_stages.requests.post", side_effect=TimeoutError("offline simulated timeout")):
                result = model_call("http://unused.invalid", body, root / "failed_call", None, observed)
            self.assertEqual("failed", result["status"])
            stored = latest_records(root / "registry")["control"]["record"]
            self.assertEqual(body["options"], stored["measurements"]["options"])
            self.assertIn("offline simulated timeout", stored["measurements"]["error"])
            self.assertEqual(body, json.loads(Path(stored["artifacts"][0]["path"]).read_bytes()))


if __name__ == "__main__":
    unittest.main()
