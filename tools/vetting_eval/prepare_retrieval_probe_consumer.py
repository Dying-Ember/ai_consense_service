"""Check a completed retrieval stage before serving it without inference engines.

Expected identities are explicit CLI inputs, never read as trust anchors from
the artifacts being checked. This is file-only: it opens no listener and runs
no embedding, ranking, OCR, or generative model.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib
import json
import os
from pathlib import Path
import sys


def describe(path: Path) -> dict:
    raw = path.read_bytes()
    return {"path": str(path.resolve()), "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest()}


def read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--tested-sha", required=True)
    parser.add_argument("--warm", type=Path, required=True)
    parser.add_argument("--expected-seal-sha", required=True)
    parser.add_argument("--expected-plan-sha", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    status = "failed"
    failure = None
    detail = {}
    try:
        tested_path = args.stage / "tested_manifest.json"
        if describe(tested_path)["sha256"] != args.tested_sha:
            raise RuntimeError("External tested-stage identity changed")
        tested = read(tested_path)
        for item in tested["sources"]:
            actual = describe(args.stage / item["name"])
            if (actual["bytes"], actual["sha256"]) != (item["bytes"], item["sha256"]):
                raise RuntimeError("Frozen tested source changed: " + item["name"])
        terminal = read(args.warm / "execution_terminal.json")
        if terminal["status"] != "completed" or terminal["childExitCode"] != 0 or not terminal["samplerStopped"]:
            raise RuntimeError("Warm execution has no successful terminal receipt")
        result = read(args.warm / "execution/result.json")
        if result["status"] != "completed" or result["actualIndexAttempts"] != 1 or result["actualRetrievalAttempts"] != 64:
            raise RuntimeError("Actual index/query counters are incomplete")
        state = (args.warm / "execution/state").resolve()
        environment = {
            "CONSENSE_RETRIEVAL_MODE": "cache_only",
            "CONSENSE_RETRIEVAL_DATA": str(state),
            "CONSENSE_CACHE_SEAL_SHA256": args.expected_seal_sha,
            "CONSENSE_CACHE_PLAN_SHA256": args.expected_plan_sha,
            "CONSENSE_CACHE_BUILD_DEVICE": "cuda",
            "CONSENSE_CACHE_BUILD_DTYPE": "float16",
            "CONSENSE_EMBED_MAX_TOKENS": "512",
            "CONSENSE_EMBED_BATCH_SIZE": "4",
            "CONSENSE_RERANK_MAX_TOKENS": "512",
            "CONSENSE_RERANK_BATCH_SIZE": "4",
            "CONSENSE_MODEL_OFFLINE": "1",
            "HF_HUB_OFFLINE": "1",
            "TRANSFORMERS_OFFLINE": "1",
        }
        os.environ.update(environment)
        sys.path.insert(0, str(args.stage.resolve()))
        server = importlib.import_module("server_staged")
        cache = server.sealed_cache()
        health = cache.health()
        forbidden = ("torch", "sentence_transformers", "transformers", "qdrant_client", "rank_bm25", "numpy", "paddle", "paddleocr")
        imported = sorted(name for name in sys.modules if name.split(".", 1)[0] in forbidden)
        if imported:
            raise RuntimeError("Cache-only preflight imported inference/storage engines")
        if health["indexed"] != 13293 or health["plannedQueries"] != 64:
            raise RuntimeError("Full case count differs from the frozen case")
        if health["activeModelDevice"] != "none" or health["liveRetrievalFallbackAllowed"]:
            raise RuntimeError("Cache consumer would use live inference")
        detail = {"health": health, "environment": environment,
                  "validatedResponseCount": len(cache.entries),
                  "validatedCompleteCorpusCount": len(cache.metadata["chunks"]),
                  "queryHandshakeRequired": True, "importedForbiddenModules": imported,
                  "testedStage": describe(tested_path),
                  "actualWarmResult": describe(args.warm / "execution/result.json"),
                  "actualWarmTerminal": describe(args.warm / "execution_terminal.json"),
                  "seal": describe(state / "sealed_query_cache.json")}
        status = "validated_for_cache_only_startup"
    except BaseException as error:
        failure = {"type": type(error).__name__, "message": str(error)}
        raise
    finally:
        receipt = {"status": status, "observedAtUtc": datetime.now(timezone.utc).isoformat(),
                   "failure": failure, "details": detail, "fileOnly": True,
                   "indexRetrievalModelHttpOcrCalls": 0,
                   "semanticQualityAccepted": None,
                   "limits": "Artifact identity validation does not assess retrieval relevance or semantic correctness.",
                   "preflightSoftware": describe(Path(__file__))}
        with (args.out / "consumer_preflight.json").open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(receipt, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
        print(json.dumps({"status": status, "receipt": str(args.out / "consumer_preflight.json"),
                          "failure": failure}, ensure_ascii=False))


if __name__ == "__main__":
    main()
