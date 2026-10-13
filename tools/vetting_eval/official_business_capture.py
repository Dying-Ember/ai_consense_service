"""Capture a frozen official Advice/Vetting run; generation and grading stay separate.

This collector does not upload, adopt, rebuild, change review/status, read answers,
retry requests, or infer correctness from HTTP success. See the companion docs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


SAFE_HEADERS = frozenset({"content-type", "content-length", "content-disposition",
                          "request-id", "x-request-id", "x-minimax-request-id",
                          "x-amzn-requestid"})
QUESTION_IDS = frozenset({"1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "2.1", "2.2"})


class CaptureError(RuntimeError):
    """A collector-owned reason; never contains external exception/body text."""


def require(ok, reason):
    if not ok:
        raise CaptureError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def utc():
    return datetime.now(timezone.utc).isoformat()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate_json_key")
        result[key] = value
    return result


def decode(data):
    try:
        return json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique_object)
    except CaptureError:
        raise
    except (ValueError, UnicodeError):
        raise CaptureError("invalid_json_response_or_manifest") from None


def frozen(path, expected_sha):
    path = Path(path)
    require(not any(re.match(r"^2[de](?:_|\s|$)", part, re.I) for part in path.parts),
            "answer_directory_is_not_a_generation_input")
    require(bool(re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha or "")), "invalid_frozen_sha")
    data = path.read_bytes()
    require(digest(data) == expected_sha.lower(), "frozen_input_sha_mismatch")
    return decode(data)


@dataclass
class RawReply:
    status: int
    headers: dict
    body: bytes


class UrlTransport:
    kind = "urllib_http"

    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    def request(self, method, url, headers, body, timeout):
        request = Request(url, data=body, headers=headers, method=method)
        try:
            with build_opener(self.NoRedirect()).open(request, timeout=timeout) as response:
                return RawReply(response.status, dict(response.headers.items()), response.read())
        except HTTPError as response:
            # Save first failure bytes; never retry or print an upstream body.
            return RawReply(response.code, dict(response.headers.items()), response.read())


class Capture:
    def __init__(self, args, transport=None, sleeper=time.sleep, monotonic=time.monotonic):
        self.args = args
        self.transport = transport or UrlTransport()
        self.sleep = sleeper
        self.monotonic = monotonic
        self.out = Path(args.out)
        self.output_created = False
        self.receipt = {"schema": "consense.official-business-capture.v1", "status": "started",
                        "mode": args.mode, "startedAt": utc(), "requests": [], "questions": [],
                        "qualityAssessment": "not_scored", "generationReadsAnswersOrLabels": False,
                        "transportKind": self.transport.kind,
                        "uploadsOrAdoptionsOrIndexRebuildsOrReviewWrites": False}

    def save(self):
        (self.out / "capture-receipt.json").write_text(
            json.dumps(self.receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def exchange(self, label, method, route, body=None, envelope=True):
        number = len(self.receipt["requests"]) + 1
        stem = f"{number:03d}-{label}"
        data = None if body is None else json.dumps(body, ensure_ascii=False,
                                                    separators=(",", ":")).encode("utf-8")
        if data is not None:
            (self.out / (stem + "-request.bin")).write_bytes(data)
        record = {"label": label, "method": method, "route": route, "startedAt": utc(),
                  "requestBodySha256": None if data is None else digest(data)}
        self.receipt["requests"].append(record)
        self.save()
        started = self.monotonic()
        try:
            reply = self.transport.request(method, self.base + route,
                    {"X-ConSense-Llm-Profile": self.args.profile,
                     "Content-Type": "application/json; charset=utf-8"}, data, self.args.timeout)
        except Exception as error:
            record.update(finishedAt=utc(), elapsedSeconds=self.monotonic() - started,
                          transportFailureType=type(error).__name__)
            self.save()
            raise CaptureError("transport_failed_first_attempt") from None
        raw_path = self.out / (stem + "-response.bin")
        raw_path.write_bytes(reply.body)
        record.update(finishedAt=utc(), elapsedSeconds=self.monotonic() - started,
                      status=reply.status, responseBytes=len(reply.body), responseSha256=digest(reply.body),
                      rawResponseFile=raw_path.name,
                      safeHeaders={str(k).lower(): str(v) for k, v in reply.headers.items()
                                   if str(k).lower() in SAFE_HEADERS})
        self.save()
        require(reply.status == 200, "http_first_attempt_failed")
        if not envelope:
            return reply.body
        result = decode(reply.body)
        require(isinstance(result, dict) and type(result.get("code")) is int
                and result["code"] == 0 and "data" in result,
                "business_envelope_failed")
        return result["data"]

    def identity(self, value):
        require(isinstance(value, dict) and value.get("profileId") == self.args.profile
                and value.get("model") == self.args.model, "returned_model_identity_mismatch")
        return value

    def prepare(self):
        args = self.args
        require(args.execute is True, "execute_switch_required")
        url = urlsplit(args.base_url)
        require(url.scheme == "http" and url.hostname in ("localhost", "127.0.0.1")
                and url.port not in (None, 80, 8080) and not url.username and not url.password
                and not url.query and not url.fragment and url.path in ("", "/"),
                "explicit_isolated_localhost_java_required")
        require(bool(re.fullmatch(r"[A-Za-z0-9_-]{1,100}", args.project_id))
                and not args.project_id.lower().startswith("demo"), "isolated_project_id_required")
        require(args.profile and args.model, "explicit_profile_and_model_required")
        require(args.timeout > 0 and args.max_wait > 0 and 0 < args.poll_interval <= 30,
                "invalid_timeout_or_poll_policy")
        require(not self.out.exists(), "new_output_directory_required")
        self.out.mkdir(parents=True, exist_ok=False)
        self.output_created = True
        self.base = args.base_url.rstrip("/")
        self.receipt.update(baseUrl=self.base, projectId=args.project_id, profileId=args.profile,
                            expectedModel=args.model, sourcesManifestSha256=args.sources_sha256.lower())
        self.save()
        manifest = frozen(args.sources_manifest, args.sources_sha256)
        require(isinstance(manifest, dict) and manifest.get("projectId") == args.project_id,
                "frozen_project_identity_mismatch")
        require(manifest.get("sourceScope") == "fullset", "frozen_fullset_scope_required")
        documents = manifest.get("documents")
        require(isinstance(documents, list) and len(documents) >= 2, "at_least_two_frozen_sources_required")
        self.expected = {}
        for document in documents:
            require(isinstance(document, dict), "invalid_frozen_source")
            doc_id = str(document.get("documentId", ""))
            require(bool(re.fullmatch(r"[1-9][0-9]*", doc_id)) and doc_id not in self.expected
                    and isinstance(document.get("fileName"), str) and document["fileName"]
                    and bool(re.fullmatch(r"[0-9a-f]{64}", document.get("javaParsedSourceHash", ""))),
                    "invalid_or_duplicate_frozen_source_identity")
            self.expected[doc_id] = document
        self.manifest = manifest
        self.advice = "/api/advice/" + quote(args.project_id, safe="")
        self.vetting = "/api/vetting/" + quote(args.project_id, safe="")
        self.receipt["offlineProvenanceNotLiveVerified"] = {
            doc_id: sorted(set(doc) - {"documentId", "fileName", "javaParsedSourceHash", "sourceRole"})
            for doc_id, doc in self.expected.items()}
        if args.mode == "advice":
            require(args.questions and args.questions_sha256, "frozen_question_only_input_required")
            questions = frozen(args.questions, args.questions_sha256)
            require(isinstance(questions, dict) and questions.get("answerTextIncluded") is False
                    and questions.get("originalAnswerDocumentProductionAllowed") is False
                    and questions.get("uniqueTopLevelQuestions") == 8, "question_only_flags_invalid")
            rows = questions.get("questions")
            require(isinstance(rows, list) and len(rows) == 8
                    and {q.get("questionId") for q in rows if isinstance(q, dict)} == QUESTION_IDS,
                    "eight_unique_official_question_ids_required")
            require(all(q.get("sameQuestionAnswerAllowedInPrompt") is False
                        and isinstance(q.get("queryTextVisualTranscription"), str)
                        and q["queryTextVisualTranscription"].strip() for q in rows),
                    "question_only_row_invalid")
            require(not args.question_id or args.question_id in QUESTION_IDS, "unknown_question_id")
            self.questions = [q for q in rows if not args.question_id or q["questionId"] == args.question_id]
            self.receipt.update(questionsSha256=args.questions_sha256.lower(),
                                split=questions.get("split"),
                                requestedQuestionIds=[q["questionId"] for q in self.questions])

    def sources(self, label):
        rows = self.exchange(label, "GET", self.advice + "/sources?scope=fullset")
        require(isinstance(rows, list) and all(isinstance(row, dict) and type(row.get("included")) is bool
                for row in rows), "live_source_catalogue_invalid")
        included = [row for row in rows if isinstance(row, dict) and row.get("included") is True]
        actual = {str(row.get("documentId")): row for row in included}
        require(len(actual) == len(included) == len(self.expected) and set(actual) == set(self.expected),
                "live_source_id_set_differs_from_frozen")
        for doc_id, expected in self.expected.items():
            observed = actual[doc_id]
            require(observed.get("fileName") == expected["fileName"]
                    and observed.get("sourceHash") == expected["javaParsedSourceHash"],
                    "live_source_name_or_parsed_hash_differs_from_frozen")
            if "sourceRole" in expected:
                require(observed.get("sourceRole") == expected["sourceRole"], "live_source_role_differs")
        self.receipt["sourceIdentityGate"] = {"documentCount": len(actual),
            "liveVerifiedFields": ["documentId", "fileName", "javaParsedSourceHash", "sourceRole_if_supplied"],
            "catalogueModule": "advisory", "contractualCorrectness": "not_scored"}

    def advice_index(self, label):
        expected = self.manifest.get("adviceIndex")
        require(isinstance(expected, dict) and expected.get("indexKey") and expected.get("indexSignature"),
                "frozen_advice_index_identity_required")
        observed = self.exchange(label, "GET", self.advice + "/index/status?scope=fullset")
        require(isinstance(observed, dict) and observed.get("evidenceBackend") == "canonical"
                and observed.get("sourceScope") == "fullset" and observed.get("vectorReady") is True
                and observed.get("embeddingReady") is True and observed.get("stale") is False
                and observed.get("eligibleDocuments") == len(self.expected)
                and observed.get("indexedDocuments") == len(self.expected)
                and observed.get("indexKey") == expected["indexKey"]
                and observed.get("indexSignature") == expected["indexSignature"],
                "live_canonical_index_not_current_frozen_scope")
        self.receipt["adviceIndexGate"] = {key: observed.get(key) for key in
            ("indexKey", "indexSignature", "evidenceBackend", "eligibleDocuments", "indexedDocuments")}

    def collect_advice(self):
        self.advice_index("index-before")
        for question in self.questions:
            qid = question["questionId"]
            response = self.exchange("question-" + qid, "POST", self.advice + "/ask", {
                "question": question["queryTextVisualTranscription"], "scope": "fullset",
                "replyToMessageId": None})
            require(isinstance(response, dict), "invalid_advice_result")
            require(response.get("sourceScope") == "fullset", "returned_advice_source_scope_mismatch")
            identity = self.identity(response.get("modelIdentity"))
            row = {"questionId": qid, "expectedOutputKind": question.get("expectedOutputKind"),
                   "modelIdentity": identity, "grounded": response.get("grounded"),
                   "messageId": response.get("messageId"), "textAssessment": "not_scored"}
            if question.get("expectedOutputKind") == "flowchart":
                row["graphOutputStatus"] = ("present_unvalidated" if isinstance(response.get("flowchart"), dict)
                                            else "unsupported")
                row["graphAssessment"] = "not_scored"
            self.receipt["questions"].append(row)
            self.save()
            self.sources("sources-after-" + qid)
            self.advice_index("index-after-" + qid)

    def job_identity(self, job, terminal=False):
        expected = self.manifest.get("vetting")
        require(isinstance(expected, dict) and expected.get("sourceSnapshotSha256")
                and expected.get("canonicalCorpusIdentitySha256"), "frozen_vetting_identity_required")
        require(isinstance(job, dict) and job.get("id") == self.job_id
                and job.get("sourceSnapshotSha256") == expected["sourceSnapshotSha256"]
                and job.get("sourceSetStale") is False, "vetting_job_source_or_id_changed")
        self.identity(job.get("modelIdentity"))
        if terminal:
            coverage = job.get("coverage")
            require(job.get("status") == "COMPLETED" and isinstance(coverage, dict)
                    and coverage.get("canonicalCorpusIdentitySha256") == expected["canonicalCorpusIdentitySha256"],
                    "completed_vetting_corpus_identity_mismatch")

    def latest_fence(self, label):
        latest = self.exchange(label, "GET", self.vetting + "/runs/latest")
        self.job_identity(latest, terminal=True)

    def collect_vetting(self):
        # No public API accepts adoption; the frozen corpus is prepared separately.
        expected = self.manifest.get("vetting")
        require(isinstance(expected, dict) and all(isinstance(expected.get(field), str)
                and re.fullmatch(r"[0-9a-f]{64}", expected[field])
                for field in ("sourceSnapshotSha256", "canonicalCorpusIdentitySha256")),
                "frozen_vetting_identity_required")
        previous = self.exchange("latest-before-start", "GET", self.vetting + "/runs/latest")
        require(previous is None or (isinstance(previous, dict)
                and previous.get("status") in ("COMPLETED", "FAILED", "CANCELLED", "ERROR")),
                "existing_active_vetting_job_is_not_a_new_run")
        job = self.exchange("vetting-start", "POST", self.vetting + "/runs?lang=en")
        require(isinstance(job, dict) and isinstance(job.get("id"), str) and job["id"], "missing_vetting_job_id")
        require(previous is None or previous.get("id") != job["id"], "vetting_start_reused_previous_job")
        self.job_id = job["id"]
        self.receipt["jobId"] = self.job_id
        deadline = self.monotonic() + self.args.max_wait
        poll = 0
        while True:
            self.job_identity(job)
            self.receipt["lastJob"] = job
            self.save()
            if job.get("status") == "COMPLETED":
                self.job_identity(job, terminal=True)
                break
            require(job.get("status") not in ("FAILED", "CANCELLED", "ERROR"), "vetting_terminal_failure")
            require(self.monotonic() < deadline, "vetting_wait_timeout")
            self.sleep(self.args.poll_interval)
            poll += 1
            job = self.exchange(f"vetting-poll-{poll}", "GET", self.vetting + "/runs/" + quote(self.job_id, safe=""))
        self.latest_fence("latest-before-findings")
        findings = self.exchange("findings", "GET", self.vetting + "/findings")
        require(isinstance(findings, list), "invalid_findings_result")
        self.receipt["findingCount"] = len(findings)  # Zero is captured, never graded as No comment.
        self.exchange("metrics", "GET", self.vetting + "/metrics")
        self.latest_fence("latest-after-findings")
        for extension in ("json", "docx", "pdf"):
            self.latest_fence("latest-before-report-" + extension)
            data = self.exchange("report-" + extension, "GET", self.vetting + "/report." + extension + "?lang=en",
                                 envelope=False)
            if extension == "json":
                report = decode(data)
                require(isinstance(report, dict), "invalid_report_json")
                self.job_identity(report.get("job"), terminal=True)
            else:
                require(data.startswith(b"PK\x03\x04" if extension == "docx" else b"%PDF-"), "invalid_report_magic")
            (self.out / ("report." + extension)).write_bytes(data)
            self.latest_fence("latest-after-report-" + extension)
        self.sources("sources-after-vetting")
        self.receipt["reportJobIdentityFence"] = "latest_id_before_and_after_each_export_not_atomic_run_address"
        self.receipt["coverageAssessment"] = "captured_in_lastJob_not_scored"

    def run(self):
        try:
            self.prepare()
            self.sources("sources-before")
            if self.args.mode == "advice":
                self.collect_advice()
            else:
                self.collect_vetting()
            self.receipt["status"] = "collected_unscored"
        except BaseException as error:
            self.receipt.update(status="failed", failureType=type(error).__name__,
                failureReason=str(error) if isinstance(error, CaptureError) else "local_collector_failure")
            raise
        finally:
            if self.output_created:
                self.receipt["finishedAt"] = utc()
                self.save()


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode", choices=("advice", "vetting"))
    p.add_argument("--execute", action="store_true", help="Perform the explicitly selected actual business run")
    for name in ("base-url", "project-id", "profile", "model", "sources-manifest", "sources-sha256", "out"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--questions")
    p.add_argument("--questions-sha256")
    p.add_argument("--question-id", choices=sorted(QUESTION_IDS))
    p.add_argument("--timeout", type=float, default=2100)
    p.add_argument("--max-wait", type=float, default=7200)
    p.add_argument("--poll-interval", type=float, default=2)
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        Capture(args).run()
    except Exception as error:
        # Actual failure bodies are in the ignored run; no credentials/body on stdout.
        print(json.dumps({"status": "failed", "failureType": type(error).__name__,
                          "receipt": str(Path(args.out) / "capture-receipt.json")}, ensure_ascii=False))
        return 1
    print(json.dumps({"status": "collected_unscored", "receipt": str(Path(args.out) / "capture-receipt.json")},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
