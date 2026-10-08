"""Task-local, serial, non-streaming OpenAI HTTP entity-body recorder.

Runtime transport is fixed to loopback 18084 -> 18082. This captures decoded
HTTP entity bytes, not TCP packets or chunk framing. It never parses and
re-serializes provider bodies. Test fixtures may use ephemeral loopback ports.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re
import socket
import sys
import time
import uuid

LISTEN_HOST, LISTEN_PORT = "127.0.0.1", 18084
UPSTREAM_HOST, UPSTREAM_PORT = "127.0.0.1", 18082
ALLOWED_POST_PATHS = {"/v1/chat/completions", "/chat/completions"}
HOP_HEADERS = {"connection", "keep-alive", "proxy-connection", "te", "trailer",
               "transfer-encoding", "upgrade", "proxy-authenticate", "proxy-authorization"}
SECRET_NAME = re.compile(r"authorization|cookie|token|key|credential|secret|session|csrf|signature", re.I)


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def durable(path, raw):
    """Create an immutable evidence file; never overwrite a prior attempt."""
    with Path(path).open("xb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    return {"path": str(Path(path).resolve()), "bytes": len(raw), "sha256": sha(raw)}


def json_evidence(path, value):
    return durable(path, (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))


def safe_headers(items):
    # Values for credential-like names are neither persisted nor included in
    # exceptions. Names/order/duplicate counts remain auditable.
    return [{"name": name, "redacted": True} if SECRET_NAME.search(name)
            else {"name": name, "value": value, "redacted": False}
            for name, value in items]


def forwarded_headers(items):
    nominated = {name.strip().lower() for key, value in items if key.lower() == "connection"
                 for name in value.split(",")}
    return [(key, value) for key, value in items
            if key.lower() not in HOP_HEADERS | nominated | {"content-length"}]


def body_json_audit(raw, kind):
    """Derived only: original bytes remain in .bin files, including errors."""
    result = {"entityBytes": len(raw), "sha256": sha(raw), "utf8Valid": False,
              "jsonParsed": False, "schemaAccepted": None, "semanticAccepted": None}
    try:
        value = json.loads(raw.decode("utf-8"))
        result.update(utf8Valid=True, jsonParsed=True, jsonType=type(value).__name__)
        if isinstance(value, dict):
            if kind == "request":
                result.update(model=value.get("model"), stream=value.get("stream"),
                              streamFalseExplicit=value.get("stream") is False,
                              messageCount=len(value["messages"]) if isinstance(value.get("messages"), list) else None,
                              messagesCanonicalSha256=sha(json.dumps(value.get("messages"), ensure_ascii=False,
                                  sort_keys=True, separators=(",", ":")).encode("utf-8")),
                              requestedMaxTokens=value.get("max_tokens"),
                              clientSamplingOverrides={k: value[k] for k in ("temperature", "top_p", "top_k", "min_p",
                                  "presence_penalty", "frequency_penalty", "seed") if k in value})
            else:
                result["usage"] = value.get("usage")
                result["providerId"] = value.get("id")
                choices = value.get("choices")
                if isinstance(choices, list):
                    result["choices"] = [{"index": choice.get("index"),
                                          "finishReason": choice.get("finish_reason"),
                                          "contentUtf8Sha256": sha(choice.get("message", {}).get("content", "").encode("utf-8"))
                                          if isinstance(choice.get("message", {}).get("content"), str) else None}
                                         for choice in choices if isinstance(choice, dict)]
    except UnicodeDecodeError:
        result["parseFailure"] = "invalid_utf8"
    except (ValueError, TypeError, AttributeError):
        result["utf8Valid"] = True
        result["parseFailure"] = "invalid_json_or_unexpected_shape"
    return result


@dataclass(frozen=True)
class Config:
    case_root: Path
    project_id: str
    capture_root: Path
    upstream_timeout: float = 600.0
    client_read_timeout: float = 30.0
    max_request_bytes: int = 402653184
    upstream_port: int = UPSTREAM_PORT
    listen_port: int = LISTEN_PORT
    fixture_only: bool = False

    def validate(self):
        if not self.project_id or any(c in self.project_id for c in "\r\n\0"):
            raise ValueError("Expected project identity is required")
        if min(self.upstream_timeout, self.client_read_timeout, self.max_request_bytes) <= 0:
            raise ValueError("Timeouts and maximum request bytes must be positive")
        root, capture = self.case_root.resolve(), self.capture_root.resolve()
        if not root.is_dir() or capture == root or root not in capture.parents:
            raise ValueError("Capture directory must be a fresh child of an existing configured case root")
        if not self.fixture_only and (self.upstream_port != UPSTREAM_PORT or self.listen_port != LISTEN_PORT):
            raise ValueError("Runtime ports are fixed to loopback 18084 -> 18082")
        if self.fixture_only and (self.upstream_port in {18082, 18084} or self.listen_port not in {0}):
            raise ValueError("Fixture mode requires ephemeral ports and cannot touch runtime listeners")


def binding(config):
    path = config.case_root.resolve() / "active_run_binding.json"
    result = {"configuredCaseRoot": str(config.case_root.resolve()), "expectedProjectId": config.project_id,
              "bindingPath": str(path), "bound": False, "status": "unavailable", "runId": None}
    try:
        raw = path.read_bytes()
        result["bindingFile"] = {"bytes": len(raw), "sha256": sha(raw)}
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict) or not isinstance(value.get("runId"), str):
            result["status"] = "invalid_binding"
        elif value.get("projectId") != config.project_id:
            result["status"] = "project_mismatch"
        else:
            uuid.UUID(value["runId"])
            result.update(bound=True, status="bound", runId=value["runId"], projectId=value["projectId"],
                          requestedAtUtc=value.get("requestedAtUtc"), appBase=value.get("appBase"))
    except FileNotFoundError:
        pass
    except (OSError, ValueError, TypeError, UnicodeDecodeError):
        result["status"] = "invalid_or_unreadable_binding"
    return result


class EntityReadFailure(Exception):
    def __init__(self, code, partial):
        super().__init__(code)
        self.code, self.partial = code, bytes(partial)


class RecordingServer(HTTPServer):
    allow_reuse_address = False

    def __init__(self, config):
        config.validate()
        self.config = config
        self.ordinal = 0
        self.capture_root = config.capture_root.resolve()
        self.capture_root.mkdir(parents=True, exist_ok=False)
        self.source_identity = {"path": str(Path(__file__).resolve()), "sha256": sha(Path(__file__).read_bytes())}
        super().__init__((LISTEN_HOST, config.listen_port), Handler)
        json_evidence(self.capture_root / "proxy_start.json", {
            "schemaVersion": 1, "startedAtUtc": now(), "pid": os.getpid(),
            "listen": {"host": LISTEN_HOST, "port": self.server_port},
            "upstream": {"host": UPSTREAM_HOST, "port": config.upstream_port},
            "fixtureOnly": config.fixture_only, "serialHttpServer": True,
            "upstreamTimeoutSeconds": config.upstream_timeout, "clientReadTimeoutSeconds": config.client_read_timeout,
            "maxRequestBytes": config.max_request_bytes, "sourceIdentity": self.source_identity,
            "bodyScope": "HTTP entity bytes; transfer chunk framing is decoded, not a TCP wire capture",
            "credentialHeaderValuesRecorded": False, "caseBindingAtStartup": binding(config),
            "nativeJoin": "No shared ID claimed. Serial-order/time/model/usage candidate join; native log has no body hashes."})

    def event(self, row):
        with (self.capture_root / "capture_events.jsonl").open("ab") as stream:
            stream.write((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
            stream.flush()
            os.fsync(stream.fileno())


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        # Default console access logging may reveal a query or header; metadata
        # is written explicitly using the allowlisted method and path below.
        pass

    def _exact(self, size, collected):
        while size:
            try:
                item = self.rfile.read1(min(size, 65536))
            except (OSError, TimeoutError):
                raise EntityReadFailure("client_request_read_timeout_or_disconnect", collected)
            if not item:
                raise EntityReadFailure("client_request_incomplete", collected)
            collected.extend(item)
            size -= len(item)

    def _request_entity(self, items):
        lengths = [v for k, v in items if k.lower() == "content-length"]
        transfers = [v for k, v in items if k.lower() == "transfer-encoding"]
        payload = bytearray()
        if transfers:
            if lengths or len(transfers) != 1 or transfers[0].lower().strip() != "chunked":
                raise EntityReadFailure("unsupported_or_ambiguous_request_framing", payload)
            while True:
                try:
                    line = self.rfile.readline(8193)
                    if not line.endswith(b"\r\n") or len(line) > 8192:
                        raise ValueError()
                    size = int(line.split(b";", 1)[0].strip(), 16)
                    if size < 0 or len(payload) + size > self.server.config.max_request_bytes:
                        raise ValueError()
                    if size == 0:
                        # Discard trailers without recording their potentially
                        # sensitive values. Bound their count/line length.
                        for _ in range(100):
                            trailer = self.rfile.readline(8193)
                            if trailer == b"\r\n":
                                return bytes(payload), "chunked_decoded"
                            if not trailer or len(trailer) > 8192 or not trailer.endswith(b"\r\n"):
                                raise ValueError()
                        raise ValueError()
                    self._exact(size, payload)
                    if self.rfile.read(2) != b"\r\n":
                        raise ValueError()
                except EntityReadFailure:
                    raise
                except (ValueError, OSError, TimeoutError):
                    raise EntityReadFailure("client_chunked_request_incomplete_or_invalid", payload)
        if len(lengths) != 1:
            raise EntityReadFailure("missing_or_duplicate_content_length", payload)
        try:
            if not re.fullmatch(r"[0-9]+", lengths[0]):
                raise ValueError()
            length = int(lengths[0])
            if length > self.server.config.max_request_bytes:
                raise ValueError()
        except ValueError:
            raise EntityReadFailure("invalid_or_oversize_content_length", payload)
        self._exact(length, payload)
        return bytes(payload), "content_length"

    def do_GET(self):
        if self.path == "/health":
            raw = (json.dumps({"status": "ready", "fixtureOnly": self.server.config.fixture_only,
                              "serial": True, "caseBinding": binding(self.server.config),
                              "upstreamHealth": "not_probed", "modelCalls": "not_inferred_from_health"}) + "\n").encode()
            self._send(200, "OK", [("Content-Type", "application/json")], raw, len(raw))
        elif self.path == "/v1/models":
            self._model_catalog()
        else:
            self._send(404, "Not Found", [], b"", 0)

    def _model_catalog(self):
        """Forward the Java provider availability check; no generation is run."""
        directory = self.server.capture_root / ("availability_" + uuid.uuid4().hex[:12])
        directory.mkdir()
        started = time.monotonic()
        record = {"schemaVersion": 1, "method": "GET", "path": "/v1/models",
                  "startedAtUtc": now(), "caseBinding": binding(self.server.config),
                  "bodyScope": "HTTP entity bytes; availability check, not model generation",
                  "fixtureOnly": self.server.config.fixture_only,
                  "requestHeaders": safe_headers(list(self.headers.raw_items())),
                  "failureCodes": [], "upstreamResponseComplete": False,
                  "clientWriteComplete": False}
        json_evidence(directory / "attempt_started.json", record)
        connection = None
        raw, headers, status, reason, declared = b"", [], 502, "Bad Gateway", 0
        try:
            connection = http.client.HTTPConnection(UPSTREAM_HOST, self.server.config.upstream_port,
                                                    timeout=self.server.config.upstream_timeout)
            connection.putrequest("GET", "/v1/models", skip_host=True, skip_accept_encoding=True)
            for key, value in forwarded_headers(list(self.headers.raw_items())):
                connection.putheader(key, value)
            connection.putheader("Connection", "close")
            connection.endheaders()
            response = connection.getresponse()
            headers, status, reason = response.getheaders(), response.status, response.reason
            record.update(upstreamResponseStatus=status, upstreamResponseHeaders=safe_headers(headers))
            original_length = response.length
            parts = bytearray()
            try:
                while True:
                    piece = response.read1(65536)
                    if not piece:
                        break
                    parts.extend(piece)
                if original_length is not None and len(parts) != original_length:
                    record["failureCodes"].append("upstream_response_incomplete")
                else:
                    record["upstreamResponseComplete"] = True
            except http.client.IncompleteRead as error:
                parts.extend(error.partial)
                record["failureCodes"].append("upstream_response_incomplete")
            except (OSError, TimeoutError, http.client.HTTPException):
                record["failureCodes"].append("upstream_response_read_timeout_or_disconnect")
            raw = bytes(parts)
            declared = original_length if original_length is not None else (len(raw) if record["upstreamResponseComplete"] else None)
            if status != 200:
                record["failureCodes"].append("upstream_non_200")
            record["upstreamResponseBody"] = durable(directory / "upstream_response.bin", raw)
        except (OSError, TimeoutError, http.client.HTTPException):
            record["failureCodes"].append("availability_upstream_connect_or_headers_failed")
        finally:
            if connection is not None:
                connection.close()
            record["clientResponseBody"] = durable(directory / "client_response.bin", raw)
            record["clientResponseStatus"] = status
            try:
                self._send(status, reason, headers, raw, declared)
                record["clientWriteComplete"] = True
            except (OSError, TimeoutError):
                record["failureCodes"].append("client_response_write_failed")
            record.update(finishedAtUtc=now(), wallSeconds=time.monotonic()-started)
            json_evidence(directory / "capture.json", record)
            self.server.event({"event": "availability_finished", "path": "/v1/models",
                               "captureId": directory.name, "status": status,
                               "atUtc": record["finishedAtUtc"], "generativeModelCalls": 0})

    def _send(self, status, reason, headers, raw, declared_length):
        self.send_response_only(status, reason)
        for name, value in forwarded_headers(headers):
            self.send_header(name, value)
        # http.client removes HTTP transfer framing. Reframe identical entity
        # bytes, preserving the original larger CL for incomplete fixed bodies.
        # None means an incomplete chunked/EOF body: send its captured entity
        # prefix as a chunk and close without a terminal chunk (not a made-up CL).
        self.send_header("Transfer-Encoding" if declared_length is None else "Content-Length",
                         "chunked" if declared_length is None else str(declared_length))
        self.send_header("Connection", "close")
        self.end_headers()
        if declared_length is None:
            if raw:
                self.wfile.write(f"{len(raw):x}\r\n".encode("ascii") + raw + b"\r\n")
        else:
            self.wfile.write(raw)
        self.wfile.flush()
        self.close_connection = True

    def do_POST(self):
        # Reject path/query injection without storing it (queries can contain
        # credentials). No arbitrary destination or query is permitted.
        if self.path not in ALLOWED_POST_PATHS:
            self._send(404, "Not Found", [], b"", 0)
            return
        self.connection.settimeout(self.server.config.client_read_timeout)
        self.server.ordinal += 1
        ordinal = self.server.ordinal
        directory = self.server.capture_root / f"call_{ordinal:04d}_{uuid.uuid4().hex[:12]}"
        directory.mkdir()
        started = time.monotonic()
        record = {"schemaVersion": 1, "captureId": directory.name, "serialOrdinal": ordinal,
                  "startedAtUtc": now(), "method": "POST", "path": self.path,
                  "bodyScope": "HTTP entity bytes", "fixtureOnly": self.server.config.fixture_only,
                  "caseBindingAtStart": binding(self.server.config),
                  "requestHeaders": safe_headers(list(self.headers.raw_items())),
                  "upstreamAttempted": False, "upstreamResponseStatus": None,
                  "failureCodes": [], "upstreamResponseComplete": False,
                  "clientWriteComplete": False, "clientResponseStatus": None,
                  "nativeRequestId": None, "sharedNativeIdProven": False}
        json_evidence(directory / "attempt_started.json", record)
        self.server.event({"event": "started", "captureId": directory.name, "serialOrdinal": ordinal,
                           "atUtc": record["startedAtUtc"], "caseBinding": record["caseBindingAtStart"]})
        client_body, upstream_body, outgoing_body = b"", b"", b""
        response_headers, response_status, response_reason, declared = [], 502, "Bad Gateway", 0
        connection = None
        try:
            client_body, framing = self._request_entity(list(self.headers.raw_items()))
            record["requestFraming"] = framing
            request_audit = body_json_audit(client_body, "request")
            record["requestJsonAudit"] = request_audit
            # The intended capture is non-streaming JSON. Rejections do not
            # silently change a streaming request into a non-streaming one.
            if not request_audit["jsonParsed"] or not request_audit.get("streamFalseExplicit"):
                record["failureCodes"].append("request_not_explicit_nonstreaming_json")
                response_status, response_reason = 400, "Bad Request"
                outgoing_body = b'{"error":{"message":"Capture proxy requires explicit stream:false JSON"}}\n'
            else:
                # Persist full input before model attempt so a crash cannot
                # erase the input that reached the provider.
                record["clientRequestBody"] = durable(directory / "client_request.bin", client_body)
                record["upstreamRequestBody"] = durable(directory / "upstream_request.bin", client_body)
                outgoing_headers = forwarded_headers(list(self.headers.raw_items()))
                outgoing_headers += [("Content-Length", str(len(client_body))), ("Connection", "close")]
                record["upstreamRequestHeaders"] = safe_headers(outgoing_headers)
                record["upstreamAttempted"] = True
                record["upstreamAttemptStartedAtUtc"] = now()
                json_evidence(directory / "upstream_attempt.json", record)
                self.server.event({"event": "upstream_attempt", "captureId": directory.name,
                                   "serialOrdinal": ordinal, "atUtc": record["upstreamAttemptStartedAtUtc"],
                                   "requestBodySha256": sha(client_body)})
                connection = http.client.HTTPConnection(UPSTREAM_HOST, self.server.config.upstream_port,
                                                        timeout=self.server.config.upstream_timeout)
                connection.putrequest("POST", self.path, skip_host=True, skip_accept_encoding=True)
                for key, value in outgoing_headers:
                    connection.putheader(key, value)
                connection.endheaders(client_body)
                response = connection.getresponse()
                response_status, response_reason = response.status, response.reason
                response_headers = response.getheaders()
                record.update(upstreamResponseStatus=response.status, upstreamResponseReason=response.reason,
                              upstreamResponseHeaders=safe_headers(response_headers), upstreamHeadersReceivedAtUtc=now())
                parts = bytearray()
                original_length = response.length
                try:
                    while True:
                        piece = response.read1(65536)
                        if not piece:
                            break
                        parts.extend(piece)
                    if original_length is not None and len(parts) != original_length:
                        record["failureCodes"].append("upstream_response_incomplete")
                    else:
                        record["upstreamResponseComplete"] = True
                except http.client.IncompleteRead as exc:
                    parts.extend(exc.partial)
                    record["failureCodes"].append("upstream_response_incomplete")
                except (OSError, TimeoutError, http.client.HTTPException):
                    record["failureCodes"].append("upstream_response_read_timeout_or_disconnect")
                upstream_body = outgoing_body = bytes(parts)
                declared = original_length if original_length is not None else len(upstream_body)
                if response_status != 200:
                    record["failureCodes"].append("upstream_non_200")
                if not upstream_body:
                    record["failureCodes"].append("upstream_empty_body")
                record["upstreamResponseJsonAudit"] = body_json_audit(upstream_body, "response")
                # Reframe a captured incomplete prefix without fabricating
                # Content-Length or a terminal chunk (see _send).
                if not record["upstreamResponseComplete"] and original_length is None:
                    declared = None
        except EntityReadFailure as exc:
            client_body = exc.partial
            record["failureCodes"].append(exc.code)
            response_status, response_reason = 400, "Bad Request"
            outgoing_body = b'{"error":{"message":"Incomplete or unsupported HTTP request body"}}\n'
        except (socket.timeout, TimeoutError):
            record["failureCodes"].append("upstream_connect_or_headers_timeout")
            response_status, response_reason = 504, "Gateway Timeout"
            outgoing_body = b'{"error":{"message":"Upstream timeout before response headers"}}\n'
        except (OSError, http.client.HTTPException):
            record["failureCodes"].append("upstream_connect_or_headers_failure")
            response_status, response_reason = 502, "Bad Gateway"
            outgoing_body = b'{"error":{"message":"Upstream connection failed before response headers"}}\n'
        except Exception as exc:
            # Do not persist arbitrary exception messages: third-party details
            # could contain a header value. Class + phase/codes suffice here.
            record["failureCodes"].append("proxy_internal_error")
            record["internalErrorClass"] = type(exc).__name__
            response_status, response_reason = 502, "Bad Gateway"
            outgoing_body = b'{"error":{"message":"Capture proxy internal failure"}}\n'
        finally:
            if connection:
                connection.close()
            if "clientRequestBody" not in record:
                record["clientRequestBody"] = durable(directory / "client_request.bin", client_body)
            if record["upstreamResponseStatus"] is not None:
                record["upstreamResponseBody"] = durable(directory / "upstream_response.bin", upstream_body)
            record["clientResponseBodyPlanned"] = durable(directory / "client_response.bin", outgoing_body)
            record["generatedProxyResponse"] = record["upstreamResponseStatus"] is None
            record["clientResponseStatus"] = response_status
            if record["generatedProxyResponse"]:
                response_headers = [("Content-Type", "application/json")]
                declared = len(outgoing_body)
            # Body was persisted before writing. A broken client socket does
            # not prove that all planned bytes reached the client.
            try:
                self._send(response_status, response_reason, response_headers, outgoing_body, declared)
                record["clientWriteComplete"] = True
            except (OSError, TimeoutError):
                record["failureCodes"].append("client_response_write_failed")
            record["caseBindingAtEnd"] = binding(self.server.config)
            first, last = record["caseBindingAtStart"], record["caseBindingAtEnd"]
            record["stableRunBinding"] = bool(first["bound"] and last["bound"] and first["runId"] == last["runId"])
            record["requestBodyPreservedForUpstream"] = bool(record.get("upstreamRequestBody") and
                record["clientRequestBody"]["sha256"] == record["upstreamRequestBody"]["sha256"])
            record["responseEntityPreservedForClient"] = bool(record["upstreamResponseStatus"] is not None and
                record["upstreamResponseBody"]["sha256"] == record["clientResponseBodyPlanned"]["sha256"])
            record["finishedAtUtc"], record["wallSeconds"] = now(), time.monotonic() - started
            record["status"] = "captured" if not record["failureCodes"] else "failed_capture_or_provider"
            descriptor = json_evidence(directory / "capture.json", record)
            self.server.event({"event": "finished", "captureId": directory.name, "serialOrdinal": ordinal,
                               "atUtc": record["finishedAtUtc"], "status": record["status"],
                               "failureCodes": record["failureCodes"], "record": descriptor})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case-root", required=True, type=Path)
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--capture-root", required=True, type=Path)
    parser.add_argument("--upstream-timeout", default=600.0, type=float)
    parser.add_argument("--client-read-timeout", default=30.0, type=float)
    args = parser.parse_args()
    server = RecordingServer(Config(args.case_root, args.project_id, args.capture_root,
                                    args.upstream_timeout, args.client_read_timeout))
    print(json.dumps({"proxy": "ready", "listener": "127.0.0.1:18084", "upstream": "127.0.0.1:18082",
                      "captureRoot": str(server.capture_root), "pid": os.getpid()}), flush=True)
    try:
        server.serve_forever(poll_interval=.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        json_evidence(server.capture_root / "proxy_stop.json", {"stoppedAtUtc": now(), "pid": os.getpid(),
                      "acceptedPostAttempts": server.ordinal, "normalFinallyReached": True})


if __name__ == "__main__":
    main()
