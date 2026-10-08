"""Freeze and serially dispatch blind same-material HTTP reference calls.

Preparation reads only fingerprint-bound request/messages/system/user/schema inputs.
No answer fixture, SDK, agent harness, retry, redirect or parameter fallback is used.
Each ordinal is dispatched at most once, including interrupted/failed attempts.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import ssl
import time
import uuid


ENDPOINT = "https://opencode.ai/zen/go/v1/chat/completions"
HOST = "opencode.ai"
REQUEST_PATH = "/zen/go/v1/chat/completions"
TIMEOUT_SECONDS = 180
USER_AGENT = "ConSense-vetting-eval/1.0"
RECIPE_VERSION = 2
ORDER = [1, 2, 3, 6, 11] + [n for n in range(1, 20) if n not in (1, 2, 3, 6, 11)]
BASELINE_PARAMETERS = {"model": "bonsai2-27b-vetting", "temperature": .2,
                       "stream": False, "n": 1, "max_tokens": 8192}
DEFAULT_SOURCE_SHA256 = "c247d315d5ee316bb7628c89f5bf74b7f88d8301407fe49e64c64cc5ce9ae8f8"


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def pretty(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def descriptor(path, raw=None):
    path = Path(path).resolve()
    raw = path.read_bytes() if raw is None else raw
    return {"path": str(path), "bytes": len(raw), "sha256": sha(raw)}


def checked(expected):
    path = Path(expected["path"]).resolve()
    raw = path.read_bytes()
    if len(raw) != expected["bytes"] or sha(raw) != expected["sha256"]:
        raise ValueError("Input fingerprint differs: " + str(path))
    return raw


def checked_manifest(path, expected_sha256):
    raw = Path(path).read_bytes()
    if sha(raw) != expected_sha256.lower():
        raise ValueError("Manifest fingerprint differs")
    return json.loads(raw), descriptor(path, raw)


def fresh(path, raw):
    path = Path(path)
    with path.open("xb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    return descriptor(path, raw)


def parameter_changes(baseline, current):
    return [{"parameter": k, "baselinePresent": k in baseline,
             "baseline": baseline.get(k), "currentPresent": k in current,
             "current": current.get(k)} for k in sorted(set(baseline) | set(current))
            if k not in baseline or k not in current or baseline[k] != current[k]]


def checked_session_id(value):
    try:
        parsed = uuid.UUID(value)
    except (ValueError, TypeError, AttributeError):
        raise ValueError("Session ID must be a canonical UUID4 owned by this experiment") from None
    if parsed.version != 4 or str(parsed) != value:
        raise ValueError("Session ID must be a canonical UUID4 owned by this experiment")
    return value


def request_headers(raw, session_id):
    checked_session_id(session_id)
    return [["Host", HOST], ["User-Agent", USER_AGENT], ["x-opencode-session", session_id],
            ["Content-Type", "application/json"], ["Accept", "application/json"],
            ["Content-Length", str(len(raw))]]


def prepare(source_manifest, source_manifest_sha256, out, *, model="glm-5.3",
            thinking="enabled", reasoning_effort="high", max_tokens=16384, session_id=None):
    source, source_identity = checked_manifest(source_manifest, source_manifest_sha256)
    if source.get("status") != "prepared_no_inference" or source.get("capturedInputCount") != 19:
        raise ValueError("Expected the frozen nineteen-input preparation")
    if [row["ordinal"] for row in source["calls"]] != list(range(1, 20)):
        raise ValueError("Expected exactly ordinals 1 through 19")
    if thinking not in ("enabled", "disabled") or reasoning_effort not in (None, "low", "medium", "high"):
        raise ValueError("Unsupported explicit reasoning configuration")
    if not model or any(c.isspace() for c in model) or max_tokens < 1:
        raise ValueError("Invalid model or output budget")
    if model == "glm-5.3" and thinking != "enabled":
        raise ValueError("The preregistered glm-5.3 reference requires thinking enabled")
    parameters = {**BASELINE_PARAMETERS, "model": model, "max_tokens": max_tokens,
                  "thinking": {"type": thinking}}
    session_id = checked_session_id(str(uuid.uuid4()) if session_id is None else session_id)
    if reasoning_effort is not None:
        parameters["reasoning_effort"] = reasoning_effort
    validated = []
    for row in source["calls"]:
        # Only these input artifacts are opened; callerContext/callerSchema/output paths are never read.
        original_raw = checked(row["inputProof"]["actualRequestEntity"])
        original = json.loads(original_raw)
        messages_raw, system_raw, user_raw, schema_raw = [checked(row[k])
                                                       for k in ("messages", "system", "user", "schema")]
        baseline = {k: v for k, v in original.items() if k != "messages"}
        if baseline != BASELINE_PARAMETERS or baseline != row["inputProof"]["baselineClientParameters"]:
            raise ValueError("Unexpected baseline request parameters")
        messages = original["messages"]
        if len(messages) != 2 or [m.get("role") for m in messages] != ["system", "user"]:
            raise ValueError("Unexpected message roles")
        if canonical(messages) != messages_raw:
            raise ValueError("Original request messages differ from frozen messages")
        if messages[0]["content"].encode("utf-8") != system_raw or messages[1]["content"].encode("utf-8") != user_raw:
            raise ValueError("Original UTF-8 prompt content differs")
        if schema_raw.decode("utf-8") not in messages[0]["content"]:
            raise ValueError("Frozen schema text is not embedded verbatim in system")
        proof = row["inputProof"]
        for key, raw in (("messagesCanonicalSha256", messages_raw), ("systemUtf8Sha256", system_raw),
                         ("userUtf8Sha256", user_raw)):
            if proof[key] != sha(raw):
                raise ValueError("Frozen prompt proof differs")
        # Copy the original body, retaining message object shape/order and all unchanged settings.
        target = dict(original)
        target.update(parameters)
        if canonical(target["messages"]) != messages_raw:
            raise ValueError("Target messages changed")
        validated.append((row, original_raw, canonical(target), messages_raw, system_raw, user_raw, schema_raw))
    # Fully validate all inputs before creating any output.
    out = Path(out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    frozen_software = fresh(out / "direct_api_material_control_session.py.frozen", Path(__file__).read_bytes())
    rows = []
    for row, original_raw, body_raw, messages_raw, system_raw, user_raw, schema_raw in validated:
        directory = out / ("call_%02d" % row["ordinal"])
        directory.mkdir()
        artifacts = {}
        for key, name, raw in (("originalRequest", "original_request.bin", original_raw),
                               ("request", "request.bin", body_raw), ("messages", "messages.json", messages_raw),
                               ("system", "system.txt", system_raw), ("user", "user.txt", user_raw),
                               ("schema", "schema.json", schema_raw)):
            artifacts[key] = fresh(directory / name, raw)
        artifacts["requestHeaders"] = fresh(directory / "request_headers.json", pretty(request_headers(body_raw, session_id)))
        rows.append({"ordinal": row["ordinal"], "topic": row["topic"], "artifacts": artifacts,
                     "sourceInputProof": row["inputProof"], "messagesUnchanged": True,
                     "schemaUtf8Sha256": sha(schema_raw)})
    manifest = {"schemaVersion": 1, "recipeVersion": RECIPE_VERSION, "status": "prepared_no_inference", "preparedAtUtc": utc_now(),
                "sourceManifest": source_identity, "sourceRunId": source["sourceRunId"],
                "projectId": source["projectId"], "inputCount": 19, "calls": rows,
                "dispatchOrder": ORDER, "initialReviewPrefixLength": 5,
                "endpoint": ENDPOINT, "timeoutSeconds": TIMEOUT_SECONDS, "timeoutKind": "socket_timeout_not_absolute_wall_limit",
                "transport": "stdlib http.client HTTPSConnection; direct TLS; no proxies; no redirects",
                "maximumAttemptsPerOrdinal": 1, "retries": 0, "concurrency": 1,
                "targetParameters": parameters, "baselineParameters": BASELINE_PARAMETERS,
                "sessionId": session_id, "requestPublicHeaders": request_headers(b"", session_id)[:-1],
                "requestPublicHeadersNote": "These public headers are common to all calls; each fingerprint-bound requestHeaders artifact also includes its exact Content-Length. Authorization value is never stored.",
                "sessionRouting": {"header": "x-opencode-session", "id": session_id,
                    "scope": "One experiment; stable across all nineteen independent requests and prefix continuation",
                    "generation": "Canonical uuid4 owned by this experiment; not an OpenCode CLI session identity",
                    "rotationOrFallbackAllowed": False, "userAgent": USER_AGENT,
                    "documentation": "https://opencode.ai/docs/go/#where-can-i-use-it",
                    "officialCode": "https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/session/llm/request.ts#L173-L183"},
                "changedParameters": parameter_changes(BASELINE_PARAMETERS, parameters),
                "controlPurpose": "Blind same-material strong-model workflow reference with no Codex extra prompt; provider, model, reasoning and output budget differ, so this is not a single-factor parameter benchmark.",
                "readBoundary": "Fingerprint-bound request/messages/system/user/schema only. No baseline response, findings, Bonsai outputs or gold. Evaluation happens after blind outputs are frozen.",
                "software": frozen_software, "externalApiCalls": 0, "sourceAnswerFixtureReads": 0,
                "semanticQualityAccepted": None}
    return fresh(out / "prepared_manifest.json", pretty(manifest))


class SecretFilter:
    """Remove only the injected credential and JSON-escaped representations of it."""
    def __init__(self, secret):
        if not secret or any(c in secret for c in "\r\n"):
            raise ValueError("Missing or invalid CONSENSE_GO_API_KEY")
        self.secret = secret
        values = {secret.encode("utf-8"), json.dumps(secret, ensure_ascii=True)[1:-1].encode("utf-8"),
                  json.dumps(secret, ensure_ascii=False)[1:-1].encode("utf-8")}
        self.patterns = sorted(values, key=len, reverse=True)

    def bytes(self, raw):
        matches = []
        for pattern in self.patterns:
            start = 0
            while True:
                pos = raw.find(pattern, start)
                if pos < 0:
                    break
                matches.append((pos, pos + len(pattern)))
                start = pos + len(pattern)
        spans = []
        for start, end in sorted(matches):
            if spans and start <= spans[-1][1]:
                spans[-1] = (spans[-1][0], max(spans[-1][1], end))
            else:
                spans.append((start, end))
        sanitized = raw
        for start, end in reversed(spans):
            sanitized = sanitized[:start] + b"[REDACTED_CREDENTIAL]" + sanitized[end:]
        return sanitized, [{"startByte": start, "endByteExclusive": end,
                            "reason": "Injected API credential"} for start, end in spans]

    def text(self, value):
        return self.bytes(str(value).encode("utf-8", errors="replace"))[0].decode("utf-8")


class TransportFailure(Exception):
    def __init__(self, stage, cause, status=None, reason=None, headers=None, body_partial=b""):
        super().__init__("Direct HTTP failed during " + stage)
        self.stage, self.original_cause = stage, cause
        self.status, self.reason = status, reason
        self.headers, self.body_partial = headers or [], body_partial


def direct_http(raw, key, session_id):
    """One POST; http.client neither retries nor follows redirects."""
    headers = request_headers(raw, session_id) + [["Authorization", "Bearer " + key]]
    connection = None
    stage, status, reason, response_headers, parts = "connect", None, None, [], []
    try:
        connection = http.client.HTTPSConnection(HOST, port=443, timeout=TIMEOUT_SECONDS,
                                               context=ssl.create_default_context())
        connection.connect()
        stage = "send"
        connection.putrequest("POST", REQUEST_PATH, skip_host=True, skip_accept_encoding=True)
        for name, value in headers:
            connection.putheader(name, value)
        connection.endheaders(raw)
        stage = "headers"
        response = connection.getresponse()
        status, reason, response_headers = response.status, response.reason, response.getheaders()
        stage = "body"
        # Preserve entity bytes as delivered by HTTP framing. No content decoding or JSON rewriting.
        while True:
            try:
                part = response.read1(65536)
            except http.client.IncompleteRead as exc:
                if exc.partial:
                    parts.append(exc.partial)
                raise
            if not part:
                break
            parts.append(part)
        if getattr(response, "length", None):
            raise http.client.IncompleteRead(b"", response.length)
        return status, reason, response_headers, b"".join(parts)
    except (Exception, KeyboardInterrupt) as exc:
        raise TransportFailure(stage, exc, status, reason, response_headers, b"".join(parts)) from exc
    finally:
        if connection is not None:
            connection.close()


def safe_causes(exc, guard):
    rows, visited = [], set()
    current = exc
    while current is not None and id(current) not in visited and len(rows) < 8:
        visited.add(id(current))
        rows.append({"type": guard.text(type(current).__name__), "message": guard.text(current)})
        current = getattr(current, "original_cause", None) or current.__cause__ or current.__context__
    return rows


def store_response(directory, artifacts, guard, response_raw, headers, *, body_complete):
    sanitized, boundaries = guard.bytes(response_raw)
    name = "response_entity.bin" if body_complete else "response_entity_partial.bin"
    artifacts["responseEntity"] = fresh(directory / name, sanitized)
    header_rows = [[guard.text(name), "[REDACTED_HEADER]" if name.lower() in ("authorization", "proxy-authorization")
                    else guard.text(value)] for name, value in headers]
    artifacts["responseHeaders"] = fresh(directory / "response_headers.json", pretty(header_rows))
    artifacts["redactionBoundaries"] = fresh(directory / "redaction_boundaries.json", pretty({
        "bodyComplete": body_complete, "responseEntityObservedSha256": sha(response_raw),
        "responseEntityObservedBytes": len(response_raw), "storedEntitySha256": sha(sanitized),
        "storedEntityBytes": len(sanitized), "exactObservedEntityStored": not bool(boundaries),
        "exactOriginalEntityStored": body_complete and not bool(boundaries),
        "responseEntityOriginalSha256": sha(response_raw) if body_complete else None,
        "responseEntityOriginalBytes": len(response_raw) if body_complete else None,
        "redactedRangesInObservedEntity": boundaries,
        "redactedRangesInOriginalEntity": boundaries if body_complete else None,
        "headerRedactionPolicy": "Remove injected credential values and all Authorization/Proxy-Authorization response header values"}))
    return sanitized, {"bodyComplete": body_complete, "responseEntityObservedSha256": sha(response_raw),
                        "responseEntityObservedBytes": len(response_raw),
                        "responseEntityOriginalSha256": sha(response_raw) if body_complete else None,
                        "responseEntityOriginalBytes": len(response_raw) if body_complete else None}


def ledger(directory, event, details):
    directory.mkdir(exist_ok=True)
    paths = sorted(directory.glob("*.json"))
    previous = descriptor(paths[-1]) if paths else None
    value = {"schemaVersion": 1, "event": event, "recordedAtUtc": utc_now(),
             "previousEvent": previous, **details}
    return fresh(directory / ("%05d_%s_%s.json" % (len(paths) + 1, event, uuid.uuid4().hex)), pretty(value))


@contextmanager
def exclusive_run(root):
    lock = root / "run.lock"
    try:
        fresh(lock, pretty({"pid": os.getpid(), "startedAtUtc": utc_now()}))
    except FileExistsError:
        raise ValueError("Run lock exists; concurrent or interrupted dispatch requires manual audit") from None
    try:
        yield
    finally:
        lock.unlink()


def validate_prepared(manifest):
    if manifest.get("schemaVersion") != 1 or manifest.get("inputCount") != 19 or manifest.get("dispatchOrder") != ORDER:
        raise ValueError("Unsupported prepared protocol")
    if manifest.get("recipeVersion") != RECIPE_VERSION:
        raise ValueError("This runner requires a fresh session-header recipe; old attempts cannot be resumed")
    session = manifest.get("sessionRouting", {})
    checked_session_id(session.get("id"))
    if (session.get("header") != "x-opencode-session" or session.get("userAgent") != USER_AGENT
            or session.get("rotationOrFallbackAllowed") is not False or manifest.get("sessionId") != session["id"]
            or manifest.get("requestPublicHeaders") != request_headers(b"", session["id"])[:-1]):
        raise ValueError("Prepared honest client/session identity differs")
    if (manifest.get("endpoint") != ENDPOINT or manifest.get("timeoutSeconds") != TIMEOUT_SECONDS
            or manifest.get("maximumAttemptsPerOrdinal") != 1 or manifest.get("retries") != 0
            or manifest.get("concurrency") != 1):
        raise ValueError("Prepared transport protocol differs")
    if sha(Path(__file__).read_bytes()) != manifest["software"]["sha256"]:
        raise ValueError("Runner differs from prepared software; create a fresh preparation")
    checked(manifest["software"])
    if [row["ordinal"] for row in manifest["calls"]] != list(range(1, 20)):
        raise ValueError("Prepared call identities differ")
    for row in manifest["calls"]:
        artifacts = row["artifacts"]
        originals = {k: checked(v) for k, v in artifacts.items()}
        original, target = json.loads(originals["originalRequest"]), json.loads(originals["request"])
        if {k: v for k, v in original.items() if k != "messages"} != BASELINE_PARAMETERS:
            raise ValueError("Prepared baseline envelope differs")
        expected = dict(original)
        expected.update(manifest["targetParameters"])
        if canonical(expected) != originals["request"]:
            raise ValueError("Prepared target body differs")
        if originals["requestHeaders"] != pretty(request_headers(originals["request"], session["id"])):
            raise ValueError("Prepared target headers differ")
        if canonical(original["messages"]) != originals["messages"] or canonical(target["messages"]) != originals["messages"]:
            raise ValueError("Prepared messages differ")
        if (target["messages"][0]["content"].encode("utf-8") != originals["system"]
                or target["messages"][1]["content"].encode("utf-8") != originals["user"]
                or originals["schema"].decode("utf-8") not in target["messages"][0]["content"]):
            raise ValueError("Prepared prompt/schema differs")


def checked_ledger(events):
    previous = None
    completed = {}
    for index, path in enumerate(sorted(events.glob("*.json")), 1):
        if not path.name.startswith("%05d_" % index):
            raise ValueError("Ledger sequence differs")
        event = json.loads(path.read_bytes())
        if event.get("previousEvent") != previous:
            raise ValueError("Ledger hash chain differs")
        if previous:
            checked(previous)
        if event.get("event") == "attempt_completed":
            ordinal = event["ordinal"]
            if ordinal in completed:
                raise ValueError("Duplicate completed ordinal in ledger")
            completed[ordinal] = event["result"]
            checked(event["result"])
        previous = descriptor(path)
    return completed


def completed_prefix(manifest, attempts, events):
    result_identities = checked_ledger(events)
    valid_names = {"call_%02d" % ordinal for ordinal in ORDER}
    if any(p.name not in valid_names for p in attempts.iterdir()):
        raise ValueError("Unexpected attempt directory")
    completed = []
    for ordinal in ORDER:
        directory = attempts / ("call_%02d" % ordinal)
        if not directory.exists():
            if any((attempts / ("call_%02d" % later)).exists() for later in ORDER[len(completed) + 1:]):
                raise ValueError("Attempt history is not a contiguous preregistered prefix")
            return completed
        result_path = directory / "result.json"
        if not result_path.exists():
            raise ValueError("Unfinished attempt exists; automatic redispatch is forbidden")
        if ordinal not in result_identities or descriptor(result_path) != result_identities[ordinal]:
            raise ValueError("Attempt result is not fingerprint-bound in ledger")
        result = json.loads(result_path.read_bytes())
        if result.get("ordinal") != ordinal or result.get("status") != "succeeded":
            raise ValueError("Failed attempt exists; this preparation cannot redispatch or continue")
        if result.get("requestSha256") != manifest["calls"][ordinal - 1]["artifacts"]["request"]["sha256"]:
            raise ValueError("Attempt request identity differs")
        for artifact in result["artifacts"].values():
            checked(artifact)
        completed.append(ordinal)
    return completed


def run(prepared_manifest, prepared_manifest_sha256, *, through=5, transport=None, environ=None):
    if through not in (5, 19):
        raise ValueError("--through must be the preregistered initial 5 or complete 19")
    manifest, manifest_identity = checked_manifest(prepared_manifest, prepared_manifest_sha256)
    validate_prepared(manifest)
    root = Path(prepared_manifest).resolve().parent / "runtime"
    root.mkdir(exist_ok=True)
    attempts, events = root / "attempts", root / "ledger"
    attempts.mkdir(exist_ok=True)
    transport = direct_http if transport is None else transport
    with exclusive_run(root):
        completed = completed_prefix(manifest, attempts, events)
        if len(completed) >= through:
            return {"status": "prefix_already_complete", "completedOrdinals": completed,
                    "newDispatches": 0, "semanticQualityAccepted": None}
        # The credential is read only after all artifact/prefix validations pass.
        key = (os.environ if environ is None else environ).get("CONSENSE_GO_API_KEY", "")
        guard = SecretFilter(key)
        # No credential may already be embedded in a prepared request artifact.
        for row in manifest["calls"]:
            if (guard.bytes(checked(row["artifacts"]["request"]))[1]
                    or guard.bytes(checked(row["artifacts"]["requestHeaders"]))[1]):
                raise ValueError("Credential overlaps prepared input; refusing dispatch")
        session_id = manifest["sessionRouting"]["id"]
        invocation = uuid.uuid4().hex
        ledger(events, "run_started", {"invocationId": invocation, "preparedManifest": manifest_identity,
                                      "completedPrefix": completed, "through": through,
                                      "semanticQualityAccepted": None})
        dispatched = 0
        for ordinal in ORDER[len(completed):through]:
            row = manifest["calls"][ordinal - 1]
            raw = checked(row["artifacts"]["request"])
            directory = attempts / ("call_%02d" % ordinal)
            directory.mkdir(exist_ok=False)  # Reserved before POST; never automatically reuse.
            artifacts = {"requestEntity": fresh(directory / "request_entity.bin", raw)}
            artifacts["requestHeaders"] = fresh(directory / "request_headers.json", pretty({
                "method": "POST", "endpoint": ENDPOINT,
                "headers": json.loads(checked(row["artifacts"]["requestHeaders"])), "authorizationHeaderPresent": True,
                "preparedHeaders": row["artifacts"]["requestHeaders"], "sessionId": session_id,
                "authorizationValueStored": False, "timeoutSeconds": TIMEOUT_SECONDS,
                "timeoutKind": "socket_timeout_not_absolute_wall_limit",
                "redirectsAllowed": False, "retries": 0}))
            started = utc_now()
            ledger(events, "attempt_started", {"invocationId": invocation, "ordinal": ordinal,
                   "request": artifacts["requestEntity"], "dispatchIndex": len(completed) + 1})
            before = time.monotonic()
            dispatched += 1
            result = {"ordinal": ordinal, "status": "failed", "startedAtUtc": started,
                      "requestSha256": sha(raw), "requestBytes": len(raw), "httpStatus": None, "sessionId": session_id,
                      "usage": None, "finishReasons": [], "responseModel": None, "responseId": None,
                      "bodyComplete": False, "semanticQualityAccepted": None}
            try:
                status, reason, headers, response_raw = transport(raw, key, session_id)
                result.update(httpStatus=status, httpReason=guard.text(reason))
                sanitized, observed = store_response(directory, artifacts, guard, response_raw, headers, body_complete=True)
                result.update(observed)
                payload = json.loads(sanitized)
                result.update(responseModel=payload.get("model"), responseId=payload.get("id"))
                result["usage"] = payload.get("usage")
                choices = payload.get("choices", [])
                result["finishReasons"] = [choice.get("finish_reason") for choice in choices]
                extracts = []
                for index, choice in enumerate(choices):
                    message = choice.get("message", {})
                    extracts.append({"index": choice.get("index", index), "content": message.get("content"),
                                     "reasoning": message.get("reasoning"), "reasoning_content": message.get("reasoning_content"),
                                     "finish_reason": choice.get("finish_reason")})
                    for field in ("content", "reasoning", "reasoning_content"):
                        value = message.get(field)
                        if isinstance(value, str):
                            artifacts["choice%d_%s" % (index, field)] = fresh(
                                directory / ("choice_%02d_%s.txt" % (index, field)), value.encode("utf-8"))
                artifacts["responseFields"] = fresh(directory / "response_fields.json", pretty(extracts))
                if not 200 <= status < 300:
                    result["failureKind"] = "http_status"
                elif (len(choices) != 1 or not isinstance(choices[0].get("message", {}).get("content"), str)
                      or not choices[0]["message"]["content"] or choices[0].get("finish_reason") != "stop"):
                    result["failureKind"] = "incomplete_or_unexpected_response"
                else:
                    result["status"] = "succeeded"
            except (Exception, KeyboardInterrupt) as exc:
                result["failureKind"] = "exception"
                if isinstance(exc, TransportFailure):
                    result.update(failureStage=exc.stage, httpStatus=exc.status, httpReason=guard.text(exc.reason))
                    _, observed = store_response(directory, artifacts, guard, exc.body_partial, exc.headers, body_complete=False)
                    result.update(observed)
                artifacts["exception"] = fresh(directory / "exception.json", pretty({
                    "type": guard.text(type(exc).__name__), "message": guard.text(exc), "causes": safe_causes(exc, guard),
                    "credentialRedactionApplied": True, "tracebackStored": False}))
            finally:
                result.update(completedAtUtc=utc_now(), wallSeconds=time.monotonic() - before, artifacts=artifacts)
                result_artifact = fresh(directory / "result.json", pretty(result))
                ledger(events, "attempt_completed", {"invocationId": invocation, "ordinal": ordinal,
                                                      "status": result["status"], "result": result_artifact})
            if result["status"] != "succeeded":
                ledger(events, "run_stopped", {"invocationId": invocation, "failedOrdinal": ordinal,
                                               "completedOrdinals": completed, "newDispatches": dispatched})
                return {"status": "stopped_on_failure", "failedOrdinal": ordinal,
                        "completedOrdinals": completed, "newDispatches": dispatched,
                        "semanticQualityAccepted": None}
            completed.append(ordinal)
        ledger(events, "prefix_completed", {"invocationId": invocation, "completedOrdinals": completed,
                                              "newDispatches": dispatched, "semanticQualityAccepted": None})
        return {"status": "prefix_completed", "completedOrdinals": completed, "newDispatches": dispatched,
                "semanticQualityAccepted": None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare", help="Freeze all nineteen inputs; makes no API calls")
    prep.add_argument("--source-manifest", type=Path, required=True)
    prep.add_argument("--source-manifest-sha256", default=DEFAULT_SOURCE_SHA256)
    prep.add_argument("--out", type=Path, required=True)
    prep.add_argument("--model", default="glm-5.3")
    prep.add_argument("--thinking", choices=("enabled", "disabled"), default="enabled")
    prep.add_argument("--reasoning-effort", choices=("low", "medium", "high", "none"), default="high")
    prep.add_argument("--max-tokens", type=int, default=16384)
    prep.add_argument("--session-id", help="Explicit canonical UUID4; omission generates one at prepare, never at run")
    execute = commands.add_parser("run", help="Dispatch once per ordinal from CONSENSE_GO_API_KEY")
    execute.add_argument("--prepared-manifest", type=Path, required=True)
    execute.add_argument("--prepared-manifest-sha256", required=True)
    execute.add_argument("--through", type=int, choices=(5, 19), default=5)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            answer = prepare(args.source_manifest, args.source_manifest_sha256, args.out, model=args.model,
                             thinking=args.thinking, reasoning_effort=None if args.reasoning_effort == "none" else args.reasoning_effort,
                             max_tokens=args.max_tokens, session_id=args.session_id)
        else:
            answer = run(args.prepared_manifest, args.prepared_manifest_sha256, through=args.through)
        print(json.dumps(answer, ensure_ascii=False, allow_nan=False))
        return 2 if answer.get("status") == "stopped_on_failure" else 0
    except Exception as exc:
        # The CLI never prints exception messages, environment values or tracebacks.
        print(json.dumps({"status": "refused", "exceptionType": type(exc).__name__,
                          "message": "Inspect immutable artifacts; no automatic retry or parameter change occurred."}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
