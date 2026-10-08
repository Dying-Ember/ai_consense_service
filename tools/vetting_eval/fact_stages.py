"""Bounded source-first extraction/comparison experiment, separate from production.

Reads frozen source packs only, never answer fixtures. Exactly one extraction and
at most one comparison call per pack; failed extraction is not retried. Source
location checks validate text and native reply-cell bindings, not semantics.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import time

import requests

from resource_sample import ResourceSampler
from experiment_log import DEFAULT_ROOT, append_record, fingerprint

SOURCE_MARKER = "Source excerpts (untrusted data):\n"


def now():
    return datetime.now(timezone.utc).isoformat()


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def description(path):
    path = Path(path).resolve()
    raw = path.read_bytes()
    return {"path": str(path), "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def canonical_reference(value):
    return re.sub(r"\s+", "", value).upper()


def reference_related(first, second):
    first, second = canonical_reference(first), canonical_reference(second)
    return first == second or any(child.startswith(parent) and child[len(parent):len(parent)+1] in ("(", ".")
                                  for child, parent in ((first, second), (second, first)))


def extraction_schema(ids, references):
    def string(limit):
        return {"type": "string", "minLength": 1, "maxLength": limit}
    properties = {"referenceId": {"type": "string", "enum": references},
                  "purpose": string(220), "chunkId": {"type": "string", "enum": ids},
                  "sourceKind": {"type": "string", "enum": ["tender", "project_reply", "standard"]},
                  "value": string(700), "quote": {"type": "string", "minLength": 12, "maxLength": 1000}}
    return {"type": "array", "maxItems": 16, "items": {"type": "object", "additionalProperties": False,
            "properties": properties, "required": list(properties)}}


def validate_extractions(records, chunks, references):
    """Exact source substrings and original populated cells only; no purpose inference."""
    by_id = {chunk["id"]: chunk for chunk in chunks}
    accepted, rejected = [], []
    if not isinstance(records, list) or len(records) > 16:
        return [], [{"index": None, "reasons": ["extraction_shape_invalid"]}]
    for index, record in enumerate(records):
        reasons = []
        fields = ("referenceId", "purpose", "chunkId", "sourceKind", "value", "quote")
        if not isinstance(record, dict) or set(record) != set(fields) or any(not isinstance(record.get(k), str) or not record[k].strip() for k in fields):
            rejected.append({"index": index, "record": record, "reasons": ["extraction_shape_invalid"]})
            continue
        chunk = by_id.get(record["chunkId"])
        quote, value = record["quote"], record["value"]
        limits = {"purpose": 220, "value": 700, "quote": 1000}
        if any(len(record[k]) > limit for k, limit in limits.items()) or len(quote) < 12:
            reasons.append("extraction_length_invalid")
        if record["referenceId"] not in references:
            reasons.append("reference_not_requested")
        if not chunk:
            reasons.append("source_not_submitted")
        elif quote not in chunk["content"]:
            reasons.append("quote_not_exact_source_substring")
        if value not in quote:
            reasons.append("value_not_exact_quote_substring")
        kind = record["sourceKind"]
        expected_role = "project_fact" if kind == "project_reply" else kind
        if kind not in ("tender", "project_reply", "standard") or not chunk or chunk["role"] != expected_role:
            reasons.append("source_kind_mismatch")
        matched_cells = []
        if kind == "project_reply" and chunk:
            for part in chunk.get("parts", []):
                table = part.get("table")
                if not table or part.get("startOffset") != 0 or part.get("endOffset") != len(part.get("text", "").encode("utf-16-le")) // 2:
                    continue
                headers, cells = table.get("headers", []), table.get("cells", [])
                if len(headers) != len(cells) or " | ".join(cells) != part.get("text"):
                    continue
                names = [" ".join(header.lower().split()) for header in headers]
                if names.count("reply") != 1 or names.count("clause") != 1:
                    continue
                reply = cells[names.index("reply")]
                raw_reference = cells[names.index("clause")]
                # Resolve only comma shorthand explicitly present in the native cell.
                refs, parent = [], None
                for token in raw_reference.split(","):
                    ref = canonical_reference(token)
                    if ref.startswith("(") and parent:
                        ref = parent + ref
                    if re.fullmatch(r"[A-Z][A-Z._]*[0-9]+(?:\.[0-9]+)*(?:\([A-Z0-9]+\))*", ref):
                        refs.append(ref)
                        parent = re.sub(r"\([^()]+\)$", "", ref)
                    else:
                        refs = []
                        break
                if reply.strip() and value in reply and quote in part["text"] and any(reference_related(ref, record["referenceId"]) for ref in refs):
                    matched_cells.append({"blockId": part["blockId"], "anchor": part["anchor"],
                                          "replyColumn": names.index("reply"), "rawReference": raw_reference,
                                          "valueStartInReplyUtf16": len(reply[:reply.index(value)].encode("utf-16-le")) // 2})
            if not matched_cells:
                reasons.append("value_not_bound_to_populated_native_reply")
        if reasons:
            rejected.append({"index": index, "record": record, "reasons": reasons})
        else:
            accepted.append({**record, "sourceHash": chunk["sourceHash"], "anchor": chunk["anchor"],
                             "nativeReplyBindings": matched_cells,
                             "validationMeaning": "Literal source binding only; purpose and comparison semantics remain unverified."})
    return accepted, rejected


def model_call(url, body, prefix, sampler, observed=None):
    request_path = prefix.with_suffix(".request.bin")
    raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    request_path.write_bytes(raw)
    result = {"startedAtUtc": now(), "request": description(request_path), "placement": []}
    started = time.perf_counter()
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(requests.post, url + "/api/chat", data=raw,
                                 headers={"Content-Type": "application/json"}, timeout=(10, 240))
            while not future.done():
                try:
                    ps = requests.get(url + "/api/ps", timeout=3)
                    ps.raise_for_status()
                    result["placement"].append({"atUtc": now(), "value": ps.json()})
                except Exception as error:
                    result["placement"].append({"atUtc": now(), "error": str(error)})
                future_done = future.done()
                if not future_done:
                    time.sleep(1)
            response = future.result()
        response_path = prefix.with_suffix(".response.bin")
        response_path.write_bytes(response.content)
        result.update(httpStatus=response.status_code, response=description(response_path))
        response.raise_for_status()
        native = response.json()
        content = native["message"]["content"]
        prefix.with_suffix(".content.txt").write_text(content, encoding="utf-8")
        result.update(status="completed", nativeMetrics={key: native.get(key) for key in
                      ("model", "done", "done_reason", "prompt_eval_count", "prompt_eval_cached_count", "eval_count",
                       "prompt_eval_duration", "eval_duration", "total_duration", "load_duration")})
        if native.get("done_reason") == "length":
            raise ValueError("Output exhausted its budget; no complete assessment established")
        result["records"] = json.loads(content)
    except Exception as error:
        result.update(status="failed", error=f"{type(error).__name__}: {error}")
    result.update(finishedAtUtc=now(), wallSeconds=time.perf_counter()-started)
    save(prefix.with_suffix(".result.json"), result)
    if observed:
        observed(body, prefix, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--prompts", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", default="qwen2.5:3b")
    parser.add_argument("--ollama-url", default="http://127.0.0.1:11434")
    parser.add_argument("--log-root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--baseline-id", default=None)
    parser.add_argument("--parameter-snapshot", type=Path,
                        help="Verified OCR/RAG/runtime metadata JSON; omitted means not observed in this experiment")
    parser.add_argument("--temperature", type=float, default=.2)
    parser.add_argument("--num-ctx", type=int, default=12288)
    parser.add_argument("--extract-tokens", type=int, default=3072)
    parser.add_argument("--compare-tokens", type=int, default=2048)
    args = parser.parse_args()
    if not 0 <= args.temperature <= 2 or min(args.num_ctx, args.extract_tokens, args.compare_tokens) < 1:
        parser.error("temperature must be 0..2 and context/output budgets must be positive")
    args.out.mkdir(parents=True, exist_ok=False)
    prepared = load(args.prepared)
    prompts = load(args.prompts)
    assert len(prepared["inputs"]) == 3, "This bounded experiment permits three frozen packs only"
    summary = {"startedAtUtc": now(), "scope": "Separate two-stage experiment, not production deployment or full review.",
               "prepared": description(args.prepared), "prompts": description(args.prompts),
               "runner": description(__file__), "model": args.model, "maxChatCalls": 6, "chatCallsMade": 0,
               "results": [], "applicationCalls": 0, "indexCalls": 0, "ocrCalls": 0,
               "semanticAcceptance": "Pending independent source audit. Empty arrays and located values are not semantic acceptance."}
    record = {"schemaVersion": 1, "experimentId": args.out.name,
              "startedAtUtc": summary["startedAtUtc"], "finishedAtUtc": None, "status": "started",
              "scope": summary["scope"], "hypothesis": "Test source-first extraction followed by comparison; audit source/object bindings separately.",
              "baselineId": args.baseline_id, "changedFactors": None,
              "parameters": {"model": args.model, "temperature": args.temperature, "num_ctx": args.num_ctx,
                  "stages": {"extract": {"num_predict": args.extract_tokens}, "compare": {"num_predict": args.compare_tokens}},
                  "maxChatCalls": 6, "retryCount": 0, "stream": False,
                  "httpTimeoutSeconds": {"connect": 10, "read": 240}, "parallelChatCalls": 1,
                  "seed": None, "inheritedModelParameters": None,
                  "runtimeSnapshot": load(args.parameter_snapshot) if args.parameter_snapshot else None},
              "inputFingerprints": {"prepared": description(args.prepared), "prompts": description(args.prompts),
                  "runner": description(__file__), "parameterSnapshot": description(args.parameter_snapshot) if args.parameter_snapshot else None},
              "measurements": {"calls": [], "resources": None},
              "evaluation": {"status": "pending_independent_source_audit", "semanticAcceptance": summary["semanticAcceptance"],
                  "sourceChecks": [], "accuracy": None, "recall": None},
              "artifacts": [description(args.prepared), description(args.prompts), description(__file__)]}
    append_record(args.log_root, record, "started")
    def observed(body, prefix, result):
        record["measurements"]["calls"].append({"stage": prefix.name, "options": body["options"],
            "status": result["status"], "startedAtUtc": result["startedAtUtc"], "finishedAtUtc": result["finishedAtUtc"],
            "wallSeconds": result["wallSeconds"], "nativeMetrics": result.get("nativeMetrics"),
            "error": result.get("error"), "placement": result["placement"], "request": result["request"]})
        record["artifacts"].append(fingerprint(prefix.with_suffix(".result.json")))
        record["artifacts"].append(result["request"])
        if result.get("response"):
            record["artifacts"].append(result["response"])
        append_record(args.log_root, record, "call_finished")
    save(args.out / "summary.json", summary)
    sampler = ResourceSampler(args.out, "Six or fewer sequential 3B extraction/comparison experiment calls. Warm resident retrieval/OCR models; concurrent isolated API/report QA may use CPU. Whole-machine sampled peaks include this work; not isolated model memory or a full vetting benchmark.")
    sampler.start()
    try:
        ps = requests.get(args.ollama_url + "/api/ps", timeout=5)
        ps.raise_for_status()
        save(args.out / "native_ps_before.json", ps.json())
        assert not ps.json().get("models"), "Do not overlap other model calls"
        for binding in prepared["inputs"]:
            ordinal, clause = binding["ordinal"], binding["clause"]
            original_path = Path(binding["request"]["path"])
            assert description(original_path) == binding["request"]
            body = load(original_path)
            original_user = body["messages"][1]["content"]
            chunks = binding["fullOriginalChunks"]
            sources = json.loads(original_user.split(SOURCE_MARKER, 1)[1])
            assert sources == [{"id": c["id"], "file": c["fileKey"] + " · " + c["fileName"],
                               "role": c["role"], "anchor": c["anchor"], "content": c["content"]} for c in chunks]
            references = binding["productionPrompt"]["referenceIds"]
            save(args.out / f"source_binding_{ordinal:02}.json", binding)
            options = {"temperature": args.temperature, "num_ctx": args.num_ctx, "num_predict": args.extract_tokens}
            extract_body = {"model": args.model, "stream": False, "options": options,
                            "messages": [{"role": "system", "content": prompts["extractionSystem"]},
                                         {"role": "user", "content": original_user}],
                            "format": extraction_schema([c["id"] for c in chunks], references)}
            sampler.set_phase("extract:" + clause)
            print(json.dumps({"phase": "extract", "clause": clause}), flush=True)
            summary["chatCallsMade"] += 1
            extracted = model_call(args.ollama_url, extract_body, args.out / f"{ordinal:02}_extract", sampler, observed)
            row = {"ordinal": ordinal, "clause": clause, "extractionStatus": extracted["status"]}
            if extracted["status"] == "completed":
                valid, rejected = validate_extractions(extracted["records"], chunks, references)
                row.update(validExtractedFields=len(valid), rejectedFields=len(rejected))
                record["evaluation"]["sourceChecks"].append({"clause": clause, "acceptedFields": len(valid),
                                                          "rejectedFields": len(rejected), "semanticAccepted": None})
                save(args.out / f"{ordinal:02}_extraction_source_checks.json", {"accepted": valid, "rejected": rejected,
                     "meaning": "Text/native-cell binding only. Field purpose, object and reference interpretation still require source semantic review."})
                compact = [{key: item[key] for key in ("referenceId", "purpose", "chunkId", "sourceKind", "value", "quote")} for item in valid]
                extra = json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
                if len(extra) > 10000:
                    row["comparisonStatus"] = "not_submitted_extraction_context_exceeds_budget"
                else:
                    compare_body = {"model": args.model, "stream": False,
                                    "options": {"temperature": args.temperature, "num_ctx": args.num_ctx, "num_predict": args.compare_tokens},
                                    "format": body["format"], "messages": [
                                    {"role": "system", "content": prompts["comparisonSystem"]},
                                    {"role": "user", "content": "Prior model extractions with literal source binding (untrusted; purpose labels and meanings are not validated):\n"
                                     + extra + "\nAll original source material follows; omitted or rejected extractions do not remove facts from it:\n" + original_user}]}
                    sampler.set_phase("compare:" + clause)
                    print(json.dumps({"phase": "compare", "clause": clause, "sourceBoundFields": len(valid), "rejectedFields": len(rejected)}), flush=True)
                    summary["chatCallsMade"] += 1
                    compared = model_call(args.ollama_url, compare_body, args.out / f"{ordinal:02}_compare", sampler, observed)
                    row.update(comparisonStatus=compared["status"], returnedRecords=len(compared.get("records", [])))
            else:
                row["comparisonStatus"] = "not_submitted_extraction_failed"
            summary["results"].append(row)
            save(args.out / "summary.json", summary)
            print(json.dumps(row), flush=True)
        record["status"] = "completed_with_call_failures" if any(call["status"] == "failed" for call in record["measurements"]["calls"]) else "completed"
    except BaseException as error:
        record.update(status="failed", error=f"{type(error).__name__}: {error}")
        raise
    finally:
        summary.update(finishedAtUtc=now(), resources=sampler.stop())
        save(args.out / "summary.json", summary)
        record.update(finishedAtUtc=summary["finishedAtUtc"])
        record["measurements"].update(resources=summary["resources"], chatCallsMade=summary["chatCallsMade"])
        record["artifacts"].append(fingerprint(args.out / "summary.json"))
        append_record(args.log_root, record, "finished")
    assert summary["chatCallsMade"] <= 6


if __name__ == "__main__":
    main()
