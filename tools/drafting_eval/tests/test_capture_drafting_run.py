"""Offline GET-only capture boundaries; never contacts an application or model."""
from copy import deepcopy
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import capture_drafting_run as capture


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = capture.DEFAULT_FIXTURE
        catalog = capture.scorer.read(self.fixture / "02_reference_do_not_upload/input_catalogue_snapshot.json")["data"]
        keys = [f["key"] for g in catalog["groups"] for f in g["fields"]]
        sources, parts, inputs = [], [], []
        for index, path in enumerate(sorted((self.fixture / "01_upload_documents").glob("*.docx")), 1):
            text = "\n\n".join(capture.scorer.native_evidence(path)["projection"])
            sha = capture.digest("PROJECT_INPUT\nnull\nPARSED\n" + text)
            sources.append({"id": index, "category": "PROJECT_INPUT", "fileName": path.name, "hash": sha})
            parts.append({"sourceDocumentId": index, "sourceHash": sha, "fileName": path.name, "partIndex": 0, "sourceText": text})
            inputs.append({"id": index, "fileName": path.name, "status": "PARSED"})
        trace = {"runId": "offline-run", "harnessVersion": "offline-harness", "model": "MiniMax-M3",
                 "modelIdentity": {"profileId": "minimax-cn"}, "status": "completed", "stale": False,
                 "evidenceRevision": "revision", "parts": parts,
                 "startedAt": "2026-10-09T02:55:47Z", "finishedAt": "2026-10-09T03:00:17Z"}
        plan = {"ruleVersion": catalog["ruleVersion"], "evidenceRevision": "revision", "evidenceChangedSinceExtraction": False,
                "snapshotId": "snapshot", "savedSnapshotId": "snapshot", "preview": False,
                "sourceIdentities": sources, "effectiveValues": {}}
        values = {"trace": trace, "latestTrace": deepcopy(trace), "plan": plan, "inputs": inputs,
                  "catalog": catalog, "templates": [],
                  "variables": [{"key": k, "confirmed": False, "manuallyEdited": False} for k in keys]}
        self.bundle = {k: {"code": 0, "message": "ok", "data": v} for k, v in values.items()}

    def validate(self):
        return capture.validate(self.bundle, self.fixture, "offline-run", "offline-harness", "minimax-cn")

    def test_complete_full_source_identity_and_frozen_file_hashes(self):
        rows = self.validate()
        self.assertEqual(15, len(rows))
        self.assertTrue(all(len(row["sha256"]) == 64 and len(row["parsedSourceHash"]) == 64 for row in rows))

    def test_get_only_transport_and_non_success_response_rejected(self):
        class Opener:
            def open(inner, request, timeout):
                self.assertEqual("GET", request.get_method())
                self.assertIsNone(request.data)
                return io.BytesIO(b'{"code":0,"data":[]}')
        with patch.object(capture.urllib.request, "build_opener", return_value=Opener()):
            self.assertEqual([], capture.read_api("http://offline.invalid/api", "minimax-cn")["data"])
        with patch.object(capture.urllib.request, "build_opener") as build:
            build.return_value.open.return_value = io.BytesIO(b'{"code":5001,"message":"not saved"}')
            with self.assertRaises(ValueError): capture.read_api("http://offline.invalid/api", "minimax-cn")

    def test_output_requires_new_ignored_local_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            self.assertEqual((repo / ".local/new").resolve(), capture.output_path(repo / ".local/new", repo))
            for path in [repo / "docs/results", repo / ".local", repo / ".local/../exposed"]:
                with self.assertRaises(ValueError): capture.output_path(path, repo)
            (repo / ".local/existing").mkdir(parents=True)
            with self.assertRaises(ValueError): capture.output_path(repo / ".local/existing", repo)

    def test_wrong_latest_run_or_harness_rejected(self):
        self.bundle["latestTrace"]["data"]["runId"] = "another-run"
        with self.assertRaises(ValueError): self.validate()
        self.bundle["latestTrace"] = deepcopy(self.bundle["trace"])
        with self.assertRaises(ValueError): capture.validate(self.bundle, self.fixture, "offline-run", "wrong-harness", "minimax-cn")

    def test_evidence_drift_stale_adoption_and_catalogue_change_rejected(self):
        for target, key, value in [("trace", "stale", True), ("plan", "evidenceRevision", "changed"),
                                   ("plan", "snapshotId", "preview"), ("catalog", "ruleVersion", "wrong")]:
            saved = deepcopy(self.bundle)
            self.bundle[target]["data"][key] = value
            self.bundle["latestTrace"] = deepcopy(self.bundle["trace"])
            with self.assertRaises(ValueError): self.validate()
            self.bundle = saved
        self.bundle["variables"]["data"][0]["confirmed"] = True
        with self.assertRaises(ValueError): self.validate()

    def test_extra_input_and_missing_trace_part_rejected(self):
        saved = deepcopy(self.bundle)
        self.bundle["inputs"]["data"].append(deepcopy(self.bundle["inputs"]["data"][0]))
        with self.assertRaises(ValueError): self.validate()
        self.bundle = saved
        self.bundle["trace"]["data"]["parts"].pop()
        self.bundle["latestTrace"] = deepcopy(self.bundle["trace"])
        with self.assertRaises(ValueError): self.validate()

    def test_changed_full_source_rejected_even_when_rehashed(self):
        part = self.bundle["trace"]["data"]["parts"][0]
        part["sourceText"] += "\nAn invented appended answer."
        part["sourceHash"] = capture.digest("PROJECT_INPUT\nnull\nPARSED\n" + part["sourceText"])
        self.bundle["plan"]["data"]["sourceIdentities"][0]["hash"] = part["sourceHash"]
        self.bundle["latestTrace"] = deepcopy(self.bundle["trace"])
        with self.assertRaisesRegex(ValueError, "Full parsed input"): self.validate()

    def test_double_read_drift_rejected_before_files_are_saved(self):
        capture_root = capture.REPO / ".local"
        capture_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="capture-tests-", dir=capture_root) as temp:
            out = Path(temp) / "new-run"
            args = SimpleNamespace(base_url="http://offline.invalid:8080", project_id="offline-project", run_id="offline-run",
                                   profile="minimax-cn", fixture=self.fixture, expected_harness_version="offline-harness", out_dir=out)
            changed = deepcopy(self.bundle)
            changed["variables"]["data"][0]["value"] = "changed candidate"
            with patch.object(capture, "collect", side_effect=[self.bundle, changed]):
                with self.assertRaisesRegex(ValueError, "changed during capture"): capture.capture(args)
            self.assertFalse(out.exists())

    def test_stable_capture_saves_required_snapshots_and_seal(self):
        capture_root = capture.REPO / ".local"
        capture_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="capture-tests-", dir=capture_root) as temp:
            out = Path(temp) / "new-run"
            args = SimpleNamespace(base_url="http://offline.invalid:8080", project_id="offline-project", run_id="offline-run",
                                   profile="minimax-cn", fixture=self.fixture, expected_harness_version="offline-harness", out_dir=out)
            with patch.object(capture, "collect", side_effect=[self.bundle, deepcopy(self.bundle)]):
                result = capture.capture(args)
            self.assertEqual(15, len(result["inputFiles"]))
            self.assertEqual({"variables.json", "plan.json", "inputs.json", "catalog.json", "templates.json", "trace.json", "manifest.json", "capture-seal.json"}, {p.name for p in out.iterdir()})
            self.assertIn("not original uploaded binary", result["byteHashScope"])
            seal = json.loads((out / "capture-seal.json").read_text())
            self.assertTrue(all(capture.scorer.sha(out / name) == sha for name, sha in seal["files"].items()))


if __name__ == "__main__":
    unittest.main()
