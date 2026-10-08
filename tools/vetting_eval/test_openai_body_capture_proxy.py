"""Real stdlib ephemeral-loopback fixtures; zero actual model/application calls."""
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import socket
import threading
import time
import unittest
import uuid

import openai_body_capture_proxy as proxy

ROOT = Path(os.environ.get("PROXY_FIXTURE_ROOT", str(Path(__file__).resolve().parent / "fixture_evidence")))
PROJECT = "fixture-project-only"
RUN_ID = "323cc4b2-087a-470d-a332-740318a016ab"
REQUEST = b'{ "model" : "fixture-only", "stream": false, "messages":[{"role":"user","content":"UTF-8 \\u4e2d\\u6587"}], "seed":14276431447698355580 }\n'
RESPONSE = '{ "id":"fixture-native-id", "choices":[{"index":0,"message":{"content":"原文 complete"},"finish_reason":"stop"}], "usage":{"prompt_tokens":11,"completion_tokens":2}}\n'.encode("utf-8")


class Provider(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_):
        pass
    def do_GET(self):
        self.server.requests.append({"method": "GET", "path": self.path,
                                     "headers": list(self.headers.raw_items())})
        raw = b'{ "data" : [{"id":"fixture-only"}] }\n'
        self.send_response_only(503 if self.server.mode == "non200" else 200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Set-Cookie", "never-record-model-catalog-cookie")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)
        self.wfile.flush()
        self.close_connection = True
    def do_POST(self):
        self.server.requests.append({"body": self.rfile.read(int(self.headers["Content-Length"])),
                                     "headers": list(self.headers.raw_items())})
        mode = self.server.mode
        if mode == "headers_timeout":
            time.sleep(.15)
            return
        if mode == "header_disconnect":
            self.close_connection = True
            return
        raw = RESPONSE if mode != "empty" else b""
        self.send_response_only(503 if mode == "non200" else 200, "Service Unavailable" if mode == "non200" else "OK")
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("X-Correlation", "fixture-correlation")
        self.send_header("Set-Cookie", "never-record-response-cookie")
        self.send_header("X-Api-Key", "never-record-response-key")
        if mode == "chunked_partial":
            self.send_header("Transfer-Encoding", "chunked")
        else:
            self.send_header("Content-Length", str(len(raw) + (100 if mode == "partial" else 0)))
        self.send_header("Connection", "close")
        self.end_headers()
        if mode == "chunked_partial":
            self.wfile.write(b"8\r\nfragment\r\n")
            self.wfile.flush()
            self.close_connection = True
            return
        # Deliberately fragmented transport, while the entity remains identical.
        for piece in [raw[:7], raw[7:31], raw[31:]]:
            self.wfile.write(piece)
            self.wfile.flush()
        self.close_connection = True


class ProxyFixtures(unittest.TestCase):
    def setUp(self):
        self.root = ROOT / (proxy.sha(self._testMethodName.encode())[:6] + "_" + uuid.uuid4().hex[:8])
        self.root.mkdir(parents=True)
        (self.root / "fixture_identity.json").write_text(json.dumps({"testMethod": self._testMethodName}), encoding="utf-8")
        self.write_binding(PROJECT)
        self.provider = HTTPServer(("127.0.0.1", 0), Provider)
        self.provider.mode, self.provider.requests = "complete", []
        self.provider_thread = threading.Thread(target=self.provider.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
        self.provider_thread.start()
        self.recording = None

    def write_binding(self, project):
        (self.root / "active_run_binding.json").write_text(json.dumps({"runId": RUN_ID, "projectId": project,
            "requestedAtUtc": "2026-10-02T00:00:00Z", "appBase": "http://fixture.invalid"}), encoding="utf-8")

    def start_proxy(self, timeout=1, upstream_port=None):
        self.recording = proxy.RecordingServer(proxy.Config(self.root, PROJECT, self.root / "captures",
            upstream_timeout=timeout, client_read_timeout=.5,
            upstream_port=upstream_port or self.provider.server_port, listen_port=0, fixture_only=True))
        self.proxy_thread = threading.Thread(target=self.recording.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
        self.proxy_thread.start()

    def tearDown(self):
        if self.recording:
            self.recording.shutdown()
            self.recording.server_close()
            self.proxy_thread.join(2)
        self.provider.shutdown()
        self.provider.server_close()
        self.provider_thread.join(2)

    def call(self, raw=REQUEST, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.recording.server_port, timeout=2)
        connection.request("POST", "/v1/chat/completions", body=raw,
                           headers=headers or {"Content-Type": "application/json"})
        response = connection.getresponse()
        result = (response.status, response.getheaders(), response.read())
        connection.close()
        return result

    def record(self, index=0):
        for _ in range(200):
            files = sorted((self.root / "captures").glob("call_*/capture.json"))
            if len(files) > index:
                return json.loads(files[index].read_text(encoding="utf-8"))
            time.sleep(.005)
        self.fail("Capture completion evidence missing")

    def body(self, descriptor):
        raw = Path(descriptor["path"]).read_bytes()
        self.assertEqual(proxy.sha(raw), descriptor["sha256"])
        self.assertEqual(len(raw), descriptor["bytes"])
        return raw

    def test_full_fragmented_utf8_request_response_passthrough_and_high_seed(self):
        self.start_proxy()
        status, headers, raw = self.call(headers={"Content-Type": "application/json", "X-Correlation": "untouched",
            "Authorization": "never-record-auth", "Cookie": "never-record-request-cookie", "X-Api-Key": "never-record-request-key"})
        record = self.record()
        self.assertEqual((status, raw), (200, RESPONSE))
        self.assertEqual(self.provider.requests[0]["body"], REQUEST)
        self.assertIn(("X-Correlation", "untouched"), self.provider.requests[0]["headers"])
        self.assertIn(("Authorization", "never-record-auth"), self.provider.requests[0]["headers"])
        self.assertEqual(self.body(record["clientRequestBody"]), REQUEST)
        self.assertEqual(self.body(record["upstreamRequestBody"]), REQUEST)
        self.assertEqual(self.body(record["upstreamResponseBody"]), RESPONSE)
        self.assertEqual(self.body(record["clientResponseBodyPlanned"]), RESPONSE)
        self.assertTrue(record["stableRunBinding"])
        self.assertFalse(record["sharedNativeIdProven"])
        self.assertEqual(record["requestJsonAudit"]["clientSamplingOverrides"]["seed"], 14276431447698355580)
        self.assertTrue(record["upstreamResponseComplete"])
        self.assertTrue(record["requestBodyPreservedForUpstream"])
        self.assertTrue(record["responseEntityPreservedForClient"])
        for file in (self.root / "captures").rglob("*"):
            if file.is_file():
                self.assertNotIn(b"never-record", file.read_bytes(), str(file))

    def test_non200_response_status_headers_and_body_are_preserved(self):
        self.provider.mode = "non200"
        self.start_proxy()
        status, headers, raw = self.call()
        record = self.record()
        self.assertEqual((status, raw), (503, RESPONSE))
        self.assertIn(("Content-Type", "application/json; charset=utf-8"), headers)
        self.assertIn("upstream_non_200", record["failureCodes"])
        self.assertFalse(record["generatedProxyResponse"])

    def test_empty_200_body_is_returned_empty_and_flagged(self):
        self.provider.mode = "empty"
        self.start_proxy()
        self.assertEqual(self.call()[::2], (200, b""))
        record = self.record()
        self.assertIn("upstream_empty_body", record["failureCodes"])
        self.assertEqual(self.body(record["upstreamResponseBody"]), b"")

    def test_declared_length_disconnect_keeps_prefix_and_client_sees_incomplete(self):
        self.provider.mode = "partial"
        self.start_proxy()
        connection = http.client.HTTPConnection("127.0.0.1", self.recording.server_port, timeout=2)
        connection.request("POST", "/v1/chat/completions", body=REQUEST)
        response = connection.getresponse()
        with self.assertRaises(http.client.IncompleteRead) as caught:
            response.read()
        self.assertEqual(caught.exception.partial, RESPONSE)
        connection.close()
        record = self.record()
        self.assertEqual(self.body(record["upstreamResponseBody"]), RESPONSE)
        self.assertIn("upstream_response_incomplete", record["failureCodes"])
        self.assertFalse(record["upstreamResponseComplete"])

    def test_chunked_disconnect_preserves_entity_prefix_without_terminal_chunk(self):
        self.provider.mode = "chunked_partial"
        self.start_proxy()
        connection = http.client.HTTPConnection("127.0.0.1", self.recording.server_port, timeout=2)
        connection.request("POST", "/v1/chat/completions", body=REQUEST)
        response = connection.getresponse()
        with self.assertRaises(http.client.IncompleteRead) as caught:
            response.read()
        self.assertEqual(caught.exception.partial, b"fragment")
        connection.close()
        record = self.record()
        self.assertEqual(self.body(record["upstreamResponseBody"]), b"fragment")
        self.assertIn("upstream_response_incomplete", record["failureCodes"])

    def test_upstream_headers_timeout_is_distinct_generated_504(self):
        self.provider.mode = "headers_timeout"
        self.start_proxy(timeout=.025)
        self.assertEqual(self.call()[0], 504)
        record = self.record()
        self.assertIn("upstream_connect_or_headers_timeout", record["failureCodes"])
        self.assertTrue(record["upstreamAttempted"])
        self.assertIsNone(record["upstreamResponseStatus"])
        self.assertTrue(record["generatedProxyResponse"])

    def test_unavailable_upstream_is_failed_with_original_input_and_gateway_status(self):
        held = socket.socket()
        held.bind(("127.0.0.1", 0))  # Bound, non-listening fixture port cannot accept a provider request.
        try:
            self.start_proxy(upstream_port=held.getsockname()[1])
            self.assertIn(self.call()[0], {502, 504})
            record = self.record()
            self.assertTrue({"upstream_connect_or_headers_failure", "upstream_connect_or_headers_timeout"}.intersection(record["failureCodes"]))
            self.assertEqual(self.body(record["clientRequestBody"]), REQUEST)
            self.assertEqual(len(self.provider.requests), 0)
        finally:
            held.close()

    def test_client_fragment_disconnect_records_request_prefix_zero_upstream(self):
        self.start_proxy()
        client = socket.create_connection(("127.0.0.1", self.recording.server_port))
        client.sendall(b"POST /v1/chat/completions HTTP/1.1\r\nHost: fixture\r\nContent-Length: 90\r\n\r\npartial-body")
        client.shutdown(socket.SHUT_WR)
        while client.recv(8192):
            pass
        client.close()
        record = self.record()
        self.assertFalse(record["upstreamAttempted"])
        self.assertEqual(self.body(record["clientRequestBody"]), b"partial-body")
        self.assertIn("client_request_incomplete", record["failureCodes"])
        self.assertEqual(len(self.provider.requests), 0)

    def test_chunked_client_body_is_forwarded_entity_exact_not_json_reencoded(self):
        self.start_proxy()
        connection = http.client.HTTPConnection("127.0.0.1", self.recording.server_port, timeout=2)
        connection.request("POST", "/v1/chat/completions", body=iter([REQUEST[:10], REQUEST[10:]]),
                           headers={"Content-Type": "application/json"}, encode_chunked=True)
        response = connection.getresponse()
        self.assertEqual(response.read(), RESPONSE)
        connection.close()
        record = self.record()
        self.assertEqual(record["requestFraming"], "chunked_decoded")
        self.assertEqual(self.provider.requests[0]["body"], REQUEST)

    def test_binding_missing_or_other_project_does_not_claim_run_association(self):
        (self.root / "active_run_binding.json").unlink()
        self.start_proxy()
        self.call()
        first = self.record()
        self.assertFalse(first["stableRunBinding"])
        self.assertEqual(first["caseBindingAtStart"]["status"], "unavailable")
        self.write_binding("different-project")
        self.call()
        second = self.record(1)
        self.assertFalse(second["stableRunBinding"])
        self.assertEqual(second["caseBindingAtStart"]["status"], "project_mismatch")
        self.assertIsNone(second["caseBindingAtStart"]["runId"])

    def test_stream_true_rejected_without_body_modification_or_upstream_attempt(self):
        self.start_proxy()
        raw = b'{"model":"fixture-only","stream":true,"messages":[]}'
        self.assertEqual(self.call(raw=raw)[0], 400)
        record = self.record()
        self.assertFalse(record["upstreamAttempted"])
        self.assertEqual(self.body(record["clientRequestBody"]), raw)
        self.assertEqual(len(self.provider.requests), 0)

    def test_serial_ordinals_separate_files_health_never_calls_provider(self):
        self.start_proxy()
        connection = http.client.HTTPConnection("127.0.0.1", self.recording.server_port)
        connection.request("GET", "/health")
        response = connection.getresponse()
        health = json.loads(response.read())
        connection.close()
        self.assertEqual(health["upstreamHealth"], "not_probed")
        self.assertEqual(len(self.provider.requests), 0)
        self.call()
        self.call()
        self.assertEqual([self.record(i)["serialOrdinal"] for i in range(2)], [1, 2])
        self.assertNotEqual(self.record()["clientRequestBody"]["path"], self.record(1)["clientRequestBody"]["path"])

    def test_runtime_transport_port_override_is_rejected_without_listener(self):
        config = proxy.Config(self.root, PROJECT, self.root / "never-start", upstream_port=12345, listen_port=0)
        with self.assertRaises(ValueError):
            proxy.RecordingServer(config)
        self.assertFalse((self.root / "never-start").exists())

    def test_model_catalog_get_exact_bytes_real_provider_and_no_generation_ordinal(self):
        self.start_proxy()
        connection = http.client.HTTPConnection("127.0.0.1", self.recording.server_port)
        connection.request("GET", "/v1/models", headers={"Authorization": "never-record-model-auth"})
        response = connection.getresponse()
        raw = response.read()
        connection.close()
        self.assertEqual(response.status, 200)
        self.assertEqual(raw, b'{ "data" : [{"id":"fixture-only"}] }\n')
        self.assertEqual(self.provider.requests[0]["path"], "/v1/models")
        self.assertEqual(self.recording.ordinal, 0)
        paths = list((self.root / "captures").glob("availability_*/capture.json"))
        self.assertEqual(len(paths), 1)
        record = json.loads(paths[0].read_text())
        self.assertTrue(record["upstreamResponseComplete"])
        self.assertEqual(self.body(record["upstreamResponseBody"]), raw)
        self.assertEqual(self.body(record["clientResponseBody"]), raw)
        self.assertNotIn("never-record-model-auth", paths[0].read_text())
        self.assertNotIn("never-record-model-catalog-cookie", paths[0].read_text())

    def test_model_catalog_non200_is_preserved(self):
        self.provider.mode = "non200"
        self.start_proxy()
        connection = http.client.HTTPConnection("127.0.0.1", self.recording.server_port)
        connection.request("GET", "/v1/models")
        response = connection.getresponse()
        raw = response.read()
        connection.close()
        self.assertEqual(response.status, 503)
        self.assertEqual(raw, b'{ "data" : [{"id":"fixture-only"}] }\n')
        record = json.loads(next((self.root / "captures").glob("availability_*/capture.json")).read_text())
        self.assertIn("upstream_non_200", record["failureCodes"])
        self.assertEqual(self.recording.ordinal, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
