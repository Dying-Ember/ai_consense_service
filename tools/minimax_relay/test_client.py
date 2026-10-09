import importlib.util
import contextlib
import io
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("relay_client", Path(__file__).with_name("client.py"))
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


class RelayClientTests(unittest.TestCase):
    def test_service_root_and_dedicated_token_can_be_reused_without_official_key(self):
        settings = client.client_settings({"CONSENSE_MINIMAX_BASE_URL": "http://127.0.0.1:8092/",
                                           "CONSENSE_MINIMAX_RELAY_API_KEY": "SYNTHETIC_RELAY_TOKEN"})
        self.assertEqual(settings, {"base_url": "http://127.0.0.1:8092/v1", "api_key": "SYNTHETIC_RELAY_TOKEN",
                                    "timeout": 1900, "max_retries": 0})

    def test_sdk_base_requires_exact_v1_without_embedded_credentials_or_redirect_targets(self):
        for base in ("http://127.0.0.1:8092", "http://token@127.0.0.1:8092/v1",
                     "http://127.0.0.1:8092/v1?target=elsewhere", "http://127.0.0.1:8092/v1#fragment"):
            with self.subTest(base=base), self.assertRaises(ValueError):
                client.client_settings({"OPENAI_BASE_URL": base, "OPENAI_API_KEY": "SYNTHETIC_RELAY_TOKEN"})

    def test_endpoint_and_token_from_different_groups_are_rejected(self):
        combinations = [
            {"OPENAI_BASE_URL": "http://other.example/v1", "CONSENSE_MINIMAX_RELAY_API_KEY": "SYNTHETIC_RELAY_TOKEN"},
            {"CONSENSE_MINIMAX_BASE_URL": "http://127.0.0.1:8092", "OPENAI_API_KEY": "SYNTHETIC_OTHER_TOKEN"},
            {"CONSENSE_MINIMAX_BASE_URL": "http://127.0.0.1:8092", "CONSENSE_MINIMAX_RELAY_API_KEY": "SYNTHETIC_RELAY_TOKEN",
             "OPENAI_BASE_URL": "http://other.example/v1"},
            {"CONSENSE_MINIMAX_BASE_URL": "http://127.0.0.1:8092", "CONSENSE_MINIMAX_RELAY_API_KEY": "SYNTHETIC_RELAY_TOKEN",
             "OPENAI_API_KEY": "SYNTHETIC_OTHER_TOKEN"},
        ]
        for values in combinations:
            with self.subTest(keys=list(values)), self.assertRaises(ValueError):
                client.client_settings(values)

    def test_explicit_backend_only_file_owns_the_entire_configuration(self):
        environment = {"OPENAI_BASE_URL": "http://other.example/v1", "OPENAI_API_KEY": "SYNTHETIC_OTHER_TOKEN",
                       "OPENAI_MODEL": "OTHER_MODEL"}
        file_values = {"CONSENSE_MINIMAX_BASE_URL": "http://127.0.0.1:8092",
                       "CONSENSE_MINIMAX_RELAY_API_KEY": "SYNTHETIC_RELAY_TOKEN"}
        settings = client.resolve_settings(environment, file_values)
        self.assertEqual(settings["base_url"], "http://127.0.0.1:8092/v1")
        self.assertEqual(settings["api_key"], "SYNTHETIC_RELAY_TOKEN")
        for incomplete in ({}, {"CONSENSE_MINIMAX_BASE_URL": "http://127.0.0.1:8092"},
                           {"CONSENSE_MINIMAX_RELAY_API_KEY": "SYNTHETIC_RELAY_TOKEN"}):
            with self.subTest(keys=list(incomplete)), self.assertRaises(ValueError):
                client.resolve_settings(environment, incomplete)

    def test_blank_optional_sdk_aliases_reuse_the_complete_backend_group(self):
        settings = client.client_settings({"CONSENSE_MINIMAX_BASE_URL": "http://127.0.0.1:8092",
                                           "CONSENSE_MINIMAX_RELAY_API_KEY": "SYNTHETIC_RELAY_TOKEN",
                                           "OPENAI_BASE_URL": "", "OPENAI_API_KEY": ""})
        self.assertEqual(settings["base_url"], "http://127.0.0.1:8092/v1")
        self.assertEqual(settings["api_key"], "SYNTHETIC_RELAY_TOKEN")

    def test_a_complete_sdk_group_remains_usable_without_an_env_file(self):
        settings = client.resolve_settings({"OPENAI_BASE_URL": "http://127.0.0.1:8092/v1",
                                            "OPENAI_API_KEY": "SYNTHETIC_RELAY_TOKEN"})
        self.assertEqual(settings["base_url"], "http://127.0.0.1:8092/v1")
        self.assertEqual(settings["api_key"], "SYNTHETIC_RELAY_TOKEN")

    def test_cli_uses_backend_only_file_without_inheriting_an_existing_editor_sdk_endpoint(self):
        captured = []
        class FakeOpenAI:
            def __init__(self, **settings):
                captured.append(settings)
                self.chat = SimpleNamespace(completions=SimpleNamespace(create=lambda **request:
                    SimpleNamespace(choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(content="OK"))])))
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False
        with tempfile.TemporaryDirectory() as temporary:
            env_path, prompt_path = Path(temporary) / ".env.local", Path(temporary) / "prompt.txt"
            env_path.write_text("CONSENSE_MINIMAX_BASE_URL=http://127.0.0.1:8092\n"
                                "CONSENSE_MINIMAX_RELAY_API_KEY=SYNTHETIC_RELAY_TOKEN\n", encoding="utf-8")
            prompt_path.write_text("Reply exactly OK", encoding="utf-8")
            with patch.dict(client.os.environ, {"OPENAI_BASE_URL": "http://other.example/v1",
                                               "OPENAI_API_KEY": "SYNTHETIC_OTHER_TOKEN"}, clear=True), \
                 patch.dict(client.sys.modules, {"openai": SimpleNamespace(OpenAI=FakeOpenAI)}), \
                 patch.object(client.sys, "argv", ["client.py", "--env-file", str(env_path), "--prompt-file", str(prompt_path)]), \
                 contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(client.main(), 0)
            self.assertEqual(captured[0]["base_url"], "http://127.0.0.1:8092/v1")
            self.assertEqual(captured[0]["api_key"], "SYNTHETIC_RELAY_TOKEN")
            self.assertEqual(output.getvalue().strip(), "OK")

    def test_request_is_text_only_non_streaming_and_uses_separated_reasoning(self):
        body = client.request_body("Review this code", [("example.py", "print('synthetic')")], 4096)
        self.assertFalse(body["stream"])
        self.assertEqual(body["n"], 1)
        self.assertEqual(body["model"], "MiniMax-M3")
        self.assertEqual(body["extra_body"], {"thinking": {"type": "disabled"}, "reasoning_split": True})
        self.assertIn("example.py", body["messages"][1]["content"])
        self.assertIn("print('synthetic')", body["messages"][1]["content"])
        self.assertNotIn("tools", body)
        for budget in (0, 16385):
            with self.assertRaises(ValueError):
                client.request_body("Review", [], budget)

    def test_env_file_is_literal_and_does_not_accept_official_key_or_execute_shell_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / ".env.local"
            path.write_text('# test\nOPENAI_API_KEY="SYNTHETIC_RELAY_TOKEN"\n'
                            'CONSENSE_MINIMAX_BASE_URL=http://127.0.0.1:8092\n'
                            'MiniMaxCN_Token_Plan_API_Key=SYNTHETIC_OFFICIAL_KEY\n'
                            'UNRECOGNIZED=$(echo anything)\n', encoding="utf-8")
            self.assertEqual(client.load_env_file(path), {"OPENAI_API_KEY": "SYNTHETIC_RELAY_TOKEN",
                                                         "CONSENSE_MINIMAX_BASE_URL": "http://127.0.0.1:8092"})


if __name__ == "__main__":
    unittest.main()
