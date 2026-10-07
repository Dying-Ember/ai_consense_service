"""Meaningful stdlib-only mock tests; no real HTTP/model/credential access."""
import http.client
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import direct_api_material_control_session as control


def response(content='```json\n{"untouched": true}\n```', *, finish="stop", status=200):
    raw = control.canonical({"choices": [{"index": 0, "finish_reason": finish,
        "message": {"content": content, "reasoning_content": "raw reasoning\nline"}}],
        "usage": {"prompt_tokens": 11, "completion_tokens": 7}})
    return status, "OK", [("Content-Type", "application/json")], raw


class ControlTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.key = "fixture-credential-unique-937265"
        self.source = self.root / "source"
        self.source.mkdir()
        calls = []
        for ordinal in range(1, 20):
            directory = self.source / ("call_%02d" % ordinal)
            directory.mkdir()
            schema = b'{"type":"object"}'
            system = b"exact system\n" + schema
            user = ("exact user %d\n" % ordinal).encode("utf-8")
            messages = [{"role": "system", "content": system.decode()}, {"role": "user", "content": user.decode()}]
            original = {**control.BASELINE_PARAMETERS, "messages": messages}
            identities = {}
            for name, raw in (("messages", control.canonical(messages)), ("system", system), ("user", user), ("schema", schema)):
                identities[name] = control.fresh(directory / name, raw)
            actual = control.fresh(directory / "request.bin", control.canonical(original))
            calls.append({"ordinal": ordinal, "topic": "fixture topic %d" % ordinal, **identities,
                "inputProof": {"actualRequestEntity": actual, "messagesCanonicalSha256": identities["messages"]["sha256"],
                    "systemUtf8Sha256": identities["system"]["sha256"], "userUtf8Sha256": identities["user"]["sha256"],
                    "baselineClientParameters": control.BASELINE_PARAMETERS,
                    "callerContext": {"path": str(self.root / "DO_NOT_READ_GOLD")}}})
        source = control.fresh(self.source / "manifest.json", control.pretty({"status": "prepared_no_inference",
            "capturedInputCount": 19, "calls": calls, "sourceRunId": "fixture-run", "projectId": "fixture-project"}))
        self.prepared = control.prepare(source["path"], source["sha256"], self.root / "prepared",
            model="glm-5.3", thinking="enabled", reasoning_effort="high", max_tokens=16384)
        self.manifest = json.loads(Path(self.prepared["path"]).read_bytes())
        self.session_id = self.manifest["sessionRouting"]["id"]

    def tearDown(self):
        self.temporary.cleanup()

    def execute(self, through=5, transport=None):
        return control.run(self.prepared["path"], self.prepared["sha256"], through=through,
            transport=transport, environ={"CONSENSE_GO_API_KEY": self.key})

    def test_same_material_exact_wire_and_prefix_continuation(self):
        bodies = []
        def fake(raw, key, session_id):
            self.assertEqual(key, self.key)
            self.assertEqual(session_id, self.session_id)
            bodies.append(raw)
            return response()
        first = self.execute(transport=fake)
        self.assertEqual(first["completedOrdinals"], [1, 2, 3, 6, 11])
        self.assertEqual(len(bodies), 5)
        self.assertEqual(self.execute(transport=fake)["newDispatches"], 0)
        self.assertEqual(len(bodies), 5)
        second = self.execute(through=19, transport=fake)
        self.assertEqual(second["newDispatches"], 14)
        self.assertEqual(len(bodies), 19)
        for raw, ordinal in zip(bodies, control.ORDER):
            artifacts = self.manifest["calls"][ordinal - 1]["artifacts"]
            self.assertEqual(raw, control.checked(artifacts["request"]))
            actual = json.loads(raw)
            self.assertEqual(actual["messages"], json.loads(control.checked(artifacts["messages"])))
            self.assertEqual(actual["model"], "glm-5.3")
            self.assertEqual(actual["thinking"], {"type": "enabled"})
            self.assertEqual(actual["reasoning_effort"], "high")
            self.assertEqual(actual["max_tokens"], 16384)
            self.assertNotIn("response_format", actual)
        output = self.root / "prepared/runtime/attempts/call_01/choice_00_content.txt"
        self.assertEqual(output.read_bytes(), '```json\n{"untouched": true}\n```'.encode())
        self.assertEqual(len(control.checked_ledger(self.root / "prepared/runtime/ledger")), 19)

    def test_failure_stops_and_cannot_be_redispatched(self):
        bodies = []
        def fake(raw, key, session_id):
            bodies.append(raw)
            return response(finish="length")
        result = self.execute(transport=fake)
        self.assertEqual(result["status"], "stopped_on_failure")
        self.assertEqual(result["failedOrdinal"], 1)
        self.assertEqual(len(bodies), 1)
        with self.assertRaisesRegex(ValueError, "Failed attempt"):
            self.execute(through=19, transport=fake)
        self.assertEqual(len(bodies), 1)

    def test_response_and_exception_credential_isolation(self):
        content = "unchanged before " + self.key + " unchanged after"
        result = self.execute(transport=lambda raw, key, session_id: response(content, status=403))
        self.assertEqual(result["newDispatches"], 1)
        runtime = self.root / "prepared/runtime"
        for path in runtime.rglob("*"):
            if path.is_file():
                self.assertNotIn(self.key.encode(), path.read_bytes())
        proof = json.loads((runtime / "attempts/call_01/redaction_boundaries.json").read_bytes())
        self.assertFalse(proof["exactOriginalEntityStored"])
        self.assertEqual(proof["responseEntityOriginalSha256"], control.sha(response(content, status=403)[3]))
        self.assertEqual(len(proof["redactedRangesInOriginalEntity"]), 1)
        stored = (runtime / "attempts/call_01/choice_00_content.txt").read_text()
        self.assertEqual(stored, "unchanged before [REDACTED_CREDENTIAL] unchanged after")

    def test_secret_in_exception_is_redacted_and_interrupted_attempt_refused(self):
        def bad(raw, key, session_id):
            raise RuntimeError("mock failure " + key)
        self.execute(transport=bad)
        exception = self.root / "prepared/runtime/attempts/call_01/exception.json"
        self.assertNotIn(self.key.encode(), exception.read_bytes())
        # Removing completion evidence simulates a crash after the attempt was reserved.
        (exception.parent / "result.json").unlink()
        with self.assertRaises((ValueError, FileNotFoundError)):
            self.execute(transport=lambda raw, key, session_id: self.fail("Must not redispatch"))

    def test_real_transport_shape_without_network_or_redirect(self):
        class FakeResponse:
            status, reason = 302, "Found"
            read_count = 0
            def getheaders(self):
                return [("Location", "https://example.invalid/")]
            def read1(self, size):
                self.read_count += 1
                return b"redirect entity" if self.read_count == 1 else b""
        calls = []
        class FakeConnection:
            def __init__(self, host, **kwargs):
                calls.append(("init", host, kwargs))
            def connect(self):
                calls.append(("connect",))
            def putrequest(self, *args, **kwargs):
                calls.append(("request", args, kwargs))
            def putheader(self, name, value):
                calls.append(("header", name, value))
            def endheaders(self, raw):
                calls.append(("body", raw))
            def getresponse(self):
                return FakeResponse()
            def close(self):
                calls.append(("closed",))
        raw = control.checked(self.manifest["calls"][0]["artifacts"]["request"])
        with patch.object(http.client, "HTTPSConnection", FakeConnection):
            returned = control.direct_http(raw, self.key, self.session_id)
        self.assertEqual(returned[0], 302)
        self.assertEqual(calls[0][1], "opencode.ai")
        self.assertEqual(calls[0][2]["timeout"], 180)
        self.assertEqual([c for c in calls if c[0] == "body"], [("body", raw)])
        self.assertEqual(len([c for c in calls if c[0] == "request"]), 1)
        self.assertEqual([c for c in calls if c[0] == "request"][0][1], ("POST", "/zen/go/v1/chat/completions"))
        actual_headers = [list(c[1:]) for c in calls if c[0] == "header"]
        self.assertEqual(actual_headers[:-1], json.loads(control.checked(self.manifest["calls"][0]["artifacts"]["requestHeaders"])))
        self.assertEqual(actual_headers[-1], ["Authorization", "Bearer " + self.key])
        self.assertNotIn("x-opencode-client", [header[0] for header in actual_headers])
        self.assertIn(["User-Agent", "ConSense-vetting-eval/1.0"], actual_headers)

    def test_partial_entity_transport_failure_and_secret_filter(self):
        prefix = ("prefix " + self.key).encode()
        cause = TimeoutError("socket timeout " + self.key)
        def fake(raw, key, session_id):
            raise control.TransportFailure("body", cause, 200, "OK", [("X-Fixture", key)], prefix)
        result = self.execute(transport=fake)
        self.assertEqual(result["newDispatches"], 1)
        directory = self.root / "prepared/runtime/attempts/call_01"
        recorded = json.loads((directory / "result.json").read_bytes())
        self.assertEqual(recorded["failureStage"], "body")
        self.assertEqual(recorded["httpStatus"], 200)
        self.assertFalse(recorded["bodyComplete"])
        self.assertIsNone(recorded["responseEntityOriginalSha256"])
        self.assertEqual(recorded["responseEntityObservedSha256"], control.sha(prefix))
        for path in directory.iterdir():
            self.assertNotIn(self.key.encode(), path.read_bytes())
        guard = control.SecretFilter('mock-"quoted"-credential')
        value = json.dumps({"content": guard.secret}).encode()
        sanitized, boundaries = guard.bytes(value)
        self.assertEqual(len(boundaries), 1)
        self.assertEqual(json.loads(sanitized)["content"], "[REDACTED_CREDENTIAL]")

    def test_transport_read1_partial_bytes_survive_actual_failure_path(self):
        prefix = b"already received prefix"
        class FakeResponse:
            status, reason, length = 200, "OK", 40
            def __init__(self):
                self.read_count = 0
            def getheaders(self):
                return [("Content-Length", "40")]
            def read1(self, size):
                self.read_count += 1
                if self.read_count == 1:
                    return prefix
                raise http.client.IncompleteRead(b"tail", 4)
        class FakeConnection:
            def __init__(self, *args, **kwargs): pass
            def connect(self): pass
            def putrequest(self, *args, **kwargs): pass
            def putheader(self, *args): pass
            def endheaders(self, raw): pass
            def getresponse(self): return FakeResponse()
            def close(self): pass
        with patch.object(http.client, "HTTPSConnection", FakeConnection):
            with self.assertRaises(control.TransportFailure) as caught:
                control.direct_http(b"{}", self.key, self.session_id)
        failure = caught.exception
        self.assertEqual(failure.stage, "body")
        self.assertEqual(failure.status, 200)
        self.assertEqual(failure.headers, [("Content-Length", "40")])
        self.assertEqual(failure.body_partial, prefix + b"tail")

    def test_preparation_or_result_tampering_blocks_network(self):
        request = Path(self.manifest["calls"][0]["artifacts"]["request"]["path"])
        request.write_bytes(request.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            self.execute(transport=lambda raw, key, session_id: self.fail("Tampering must not dispatch"))

    def test_session_identity_is_frozen_and_invalid_session_refuses_before_transport(self):
        observed = []
        def fake(raw, key, session_id):
            observed.append(session_id)
            return response()
        self.execute(transport=fake)
        self.execute(through=19, transport=fake)
        self.assertEqual(observed, [self.session_id] * 19)
        for row in self.manifest["calls"]:
            headers = json.loads(control.checked(row["artifacts"]["requestHeaders"]))
            self.assertIn(["x-opencode-session", self.session_id], headers)
        for value in (None, "", "ses_fakeofficial", "not-a-uuid", "00000000-0000-0000-0000-000000000000", self.session_id + "\r\nInjected: value"):
            with self.assertRaises(ValueError):
                control.checked_session_id(value)
        tampered = dict(self.manifest)
        tampered["sessionId"] = "47227458-26ad-4b0e-b9c3-9b4a6a3ade5f"
        identity = control.fresh(self.root / "tampered_manifest.json", control.pretty(tampered))
        with self.assertRaisesRegex(ValueError, "identity differs"):
            control.run(identity["path"], identity["sha256"],
                transport=lambda *args: self.fail("Session tampering must not dispatch"), environ={})


if __name__ == "__main__":
    unittest.main()
