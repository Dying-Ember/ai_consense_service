#!/usr/bin/env python3
"""GET-only capture of an already completed frontend Drafting fixture run.

Never uploads, extracts, adopts, edits, previews, generates or calls a model.
Snapshots are allowed only under this backend repository's ignored .local/.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

import validate_drafting_fixture as scorer

REPO = Path(__file__).resolve().parents[2]
DEFAULT_FIXTURE = Path(__file__).resolve().parent / "fixtures/round5"


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def read_api(url, profile):
    request = urllib.request.Request(url, method="GET", headers={
        "Accept": "application/json", "X-ConSense-Llm-Profile": profile})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=60) as response:
        body = response.read(64 * 1024 * 1024 + 1)
    require(len(body) <= 64 * 1024 * 1024, "API snapshot exceeds 64 MiB")
    result = json.loads(body.decode("utf-8-sig"))
    require(isinstance(result, dict) and result.get("code") == 0 and "data" in result,
            "Require a successful ApiResponse; no raw error body is saved")
    return result


def output_path(path, repo=REPO):
    resolved = path.resolve()
    allowed = (repo / ".local").resolve()
    require(resolved.is_relative_to(allowed) and resolved != allowed,
            "--out-dir must be a new directory underneath this repository's .local/")
    require(not resolved.exists(), "Output exists; use a new directory to preserve evidence")
    return resolved


def collect(prefix, run_id, profile):
    routes = {"trace": "/variables/extract-traces/" + urllib.parse.quote(run_id, safe=""),
              "variables": "/variables", "plan": "/plan", "inputs": "/inputs",
              "catalog": "/catalog", "templates": "/templates",
              "latestTrace": "/variables/extract-trace"}
    return {name: read_api(prefix + route, profile) for name, route in routes.items()}


def validate(bundle, fixture, run_id, harness, profile):
    values = {name: envelope["data"] for name, envelope in bundle.items()}
    trace, plan, catalog = (values[k] for k in ["trace", "plan", "catalog"])
    require(trace == values["latestTrace"], "Selected run is not the unchanged latest run")
    require(trace.get("runId") == run_id and trace.get("harnessVersion") == harness,
            "Wrong runId or harnessVersion")
    require(trace.get("status") == "completed" and trace.get("stale") is False,
            "Require a completed non-stale extraction")
    require(trace.get("modelIdentity", {}).get("profileId") == profile,
            "Trace provider identity does not match the requested profile")
    if profile == "minimax-cn":
        require(trace.get("model") == "MiniMax-M3", "Require MiniMax-M3 for this profile")
    require(trace.get("evidenceRevision") and trace["evidenceRevision"] == plan.get("evidenceRevision")
            and plan.get("evidenceChangedSinceExtraction") is False,
            "Trace/current plan evidence revision mismatch")
    require(plan.get("snapshotId") and plan["snapshotId"] == plan.get("savedSnapshotId")
            and plan.get("preview") is False, "Require the current saved, non-preview plan")
    reference = fixture / "02_reference_do_not_upload"
    truth = scorer.read(reference / "expected_values.json")
    expected = truth["expectedInputs"]
    scorer.fixture_holdouts(expected, catalog)
    require(catalog.get("ruleVersion") == truth.get("catalogueRuleVersion")
            and plan.get("ruleVersion") == catalog["ruleVersion"], "Unexpected API catalogue version")
    require(all(v.get("confirmed") is False and v.get("manuallyEdited") is False
                for v in values["variables"] if v.get("key") in expected),
            "Use an unadopted, unedited exam project")
    require(not any(k in plan.get("effectiveValues", {}) for k in expected),
            "Exam contains effective adopted values")
    files = sorted((fixture / "01_upload_documents").glob("*.docx"))
    require(len(files) == 15, "Fixture must contain exactly 15 DOCX files")
    seal = scorer.read(fixture / "transfer-manifest.json")
    sealed = {Path(row["path"]).name: row for row in seal["files"] if row["path"].startswith("01_upload_documents/")}
    require(set(sealed) == {p.name for p in files} and all(scorer.sha(p) == sealed[p.name]["sha256"]
            and p.stat().st_size == sealed[p.name]["bytes"] for p in files), "Frozen fixture byte hashes changed")
    require(Counter(i.get("fileName") for i in values["inputs"]) == Counter(p.name for p in files)
            and all(i.get("status") == "PARSED" for i in values["inputs"]),
            "Current project must contain exactly the 15 successfully parsed fixture inputs")
    sources = [s for s in plan.get("sourceIdentities", []) if s.get("category") == "PROJECT_INPUT"]
    require(Counter(s.get("fileName") for s in sources) == Counter(p.name for p in files)
            and {str(s["id"]) for s in sources} == {str(i["id"]) for i in values["inputs"]},
            "Current project source identity mismatch")
    parts = trace.get("parts", [])
    require({str(p.get("sourceDocumentId")) for p in parts} == {str(s["id"]) for s in sources},
            "Trace does not cover all current input identities")
    by_name = {p.name: p for p in files}
    identities = []
    for source in sources:
        selected = sorted((p for p in parts if str(p["sourceDocumentId"]) == str(source["id"])), key=lambda p: p["partIndex"])
        require([p["partIndex"] for p in selected] == list(range(len(selected))), "Missing/duplicate source part indices")
        text = "".join(p["sourceText"] for p in selected)
        source_hash = digest("PROJECT_INPUT\nnull\nPARSED\n" + text)
        require(source.get("hash") == source_hash and all(p.get("sourceHash") == source_hash
                and p.get("fileName") == source["fileName"] for p in selected), "Source text/hash/filename drift")
        native = "\n".join(scorer.native_evidence(by_name[source["fileName"]])["projection"])
        require(scorer.native_projection(text) == scorer.native_projection(native),
                "Full parsed input differs from the local DOCX native projection")
        path = by_name[source["fileName"]]
        identities.append({"name": path.name, "path": "01_upload_documents/" + path.name,
                           "bytes": path.stat().st_size, "sha256": scorer.sha(path),
                           "sourceDocumentId": source["id"], "parsedSourceHash": source_hash})
    return sorted(identities, key=lambda row: row["name"])


def capture(args):
    out = output_path(args.out_dir)
    url = urllib.parse.urlsplit(args.base_url)
    require(url.scheme in {"http", "https"} and url.hostname and not url.username
            and not url.password and not url.query and not url.fragment, "Invalid credential-free backend base URL")
    fixture = args.fixture.resolve()
    prefix = args.base_url.rstrip("/") + "/api/drafting/" + urllib.parse.quote(args.project_id, safe="")
    first = collect(prefix, args.run_id, args.profile)
    identities = validate(first, fixture, args.run_id, args.expected_harness_version, args.profile)
    second = collect(prefix, args.run_id, args.profile)
    require(first == second, "Project/run/catalogue changed during capture; nothing saved")
    require(identities == validate(second, fixture, args.run_id, args.expected_harness_version, args.profile),
            "Local fixture changed during capture")
    trace = first["trace"]["data"]
    started = datetime.fromisoformat(trace["startedAt"].replace("Z", "+00:00"))
    finished = datetime.fromisoformat(trace["finishedAt"].replace("Z", "+00:00"))
    require(started.tzinfo and finished.tzinfo and finished >= started, "Invalid run timestamps")
    manifest = {"schemaVersion": 1, "projectId": args.project_id, "runId": args.run_id,
                "stage": "SIM15", "status": "completed", "model": trace.get("model"),
                "expectedHarnessVersion": args.expected_harness_version, "inputFiles": identities,
                "startedAt": trace["startedAt"], "finishedAt": trace["finishedAt"],
                "elapsedSeconds": (finished - started).total_seconds(),
                "capturedAtUtc": datetime.now(timezone.utc).isoformat(), "errors": [],
                "captureType": "GET-only double read of latest completed fixed run",
                "byteHashScope": "Local frozen fixture DOCX bytes. API exposes parsed-source identity, not original uploaded binary bytes.",
                "scope": "Variable identification only; no application mutation, adoption, generation or model call. Double read detects persisted drift, not an atomic transaction."}
    out.mkdir(parents=True, exist_ok=False)
    for name in ["variables", "plan", "inputs", "catalog", "templates", "trace"]:
        (out / (name + ".json")).write_text(json.dumps(first[name], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    seal = {"runId": args.run_id, "files": {p.name: scorer.sha(p) for p in sorted(out.glob("*.json"))}}
    (out / "capture-seal.json").write_text(json.dumps(seal, indent=2) + "\n", encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Backend root, e.g. http://127.0.0.1:8080")
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--run-id", required=True, help="Exact completed run ID from the frontend/API history")
    parser.add_argument("--expected-harness-version", required=True)
    parser.add_argument("--profile", choices=["minimax-cn", "local"], default="minimax-cn")
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = capture(args)
    except (OSError, ValueError, KeyError, TypeError, urllib.error.URLError) as exc:
        parser.exit(2, "Capture rejected: " + str(exc) + "\n")
    print(json.dumps({"status": result["status"], "runId": result["runId"], "inputFiles": len(result["inputFiles"])}))


if __name__ == "__main__":
    main()
