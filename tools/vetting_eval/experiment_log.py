"""Append-only local experiment records and reproducible comparison views.

Records are immutable full snapshots. Later source audits append another event;
they never edit the original observations. No model, OCR or index API is called.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import copy
import csv
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import time
import uuid

DEFAULT_ROOT = Path(__file__).resolve().parents[3] / "tmp" / "vetting_experiment_logs"
ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,159}\Z")
REQUIRED = {"schemaVersion", "experimentId", "status", "scope", "parameters",
            "inputFingerprints", "measurements", "evaluation", "artifacts"}


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def fingerprint(path):
    path = Path(path).resolve()
    raw = path.read_bytes()
    return {"path": str(path), "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


@contextmanager
def registry_lock(root, timeout=30):
    root.mkdir(parents=True, exist_ok=True)
    with (root / ".registry.lock").open("a+b") as stream:
        stream.seek(0, os.SEEK_END)
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        started = time.monotonic()
        while True:
            try:
                stream.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except (BlockingIOError, OSError):
                if time.monotonic() - started > timeout:
                    raise TimeoutError("Experiment registry is busy; no record was overwritten")
                time.sleep(.05)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def validate_record(record):
    if not isinstance(record, dict):
        raise ValueError("Experiment record must be an object")
    if not REQUIRED.issubset(record):
        raise ValueError("Missing experiment record fields: " + str(sorted(REQUIRED - set(record))))
    if record["schemaVersion"] != 1 or not isinstance(record["experimentId"], str) or not ID.fullmatch(record["experimentId"]):
        raise ValueError("Unsupported schema version or unsafe experiment ID")
    for field in ("parameters", "inputFingerprints", "measurements", "evaluation"):
        if not isinstance(record[field], dict):
            raise ValueError(field + " must be an object; use null for unknown values")
    if not isinstance(record["artifacts"], list):
        raise ValueError("artifacts must be an array")
    # Validate serializability before creating any evidence file.
    json_bytes(record)


def events(root):
    # Read immutable files, not the derived index. This recovers an event if a
    # process stops after fsync(record) but before appending events.jsonl.
    result = []
    for path in sorted((root / "records").glob("*/*.json")):
        item = json.loads(path.read_text(encoding="utf-8"))
        result.append((path, item))
    return sorted(result, key=lambda row: (row[1]["recordedAtUtc"], row[1]["eventId"]))


def latest_records(root):
    latest = {}
    for path, event in events(root):
        latest[event["record"]["experimentId"]] = {"record": event["record"],
                                                     "eventId": event["eventId"], "eventPath": str(path)}
    return latest


def atomic_write(path, raw):
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with temporary.open("xb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def flatten(value, prefix=()):
    result = {}
    if isinstance(value, dict) and value:
        for key in sorted(value):
            result.update(flatten(value[key], prefix + (key,)))
    else:
        result[prefix] = value
    return result


def parameter_difference(current, baseline):
    first, second = flatten(baseline), flatten(current)
    return [{"parameter": "/" + "/".join(str(part).replace("~", "~0").replace("/", "~1") for part in key),
             "baseline": first.get(key), "current": second.get(key),
             "baselinePresent": key in first, "currentPresent": key in second}
            for key in sorted(set(first) | set(second))
            if key not in first or key not in second or first[key] != second[key]]


def display(value):
    if value is None:
        return "未记录"
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return str(value)


def compact_metrics(record):
    measurements, evaluation = record["measurements"], record["evaluation"]
    calls = measurements.get("actualCallMetrics", measurements.get("calls", []))
    resources = measurements.get("wholeMachineSampledResources", measurements.get("resources")) or {}
    peaks = resources.get("sampledGpuMemoryPeaks", [])
    wall = sum(call["wallSeconds"] for call in calls if isinstance(call.get("wallSeconds"), (int, float))) if calls else None
    audit = evaluation.get("actualAuditSummary", evaluation.get("stage2", evaluation))
    quote_count = audit.get("quotes", audit.get("quotedItems", audit.get("stage2QuoteCount")))
    located = audit.get("locatedQuotes", audit.get("stage2LocatedQuotes"))
    return {"calls": len(calls) if calls else measurements.get("actualCalls", measurements.get("chatCallsMade")),
            "callWallSecondsSum": round(wall, 3) if wall is not None else None,
            "sampledWholeGpuPeakMiB": max((p["memoryUsedMiB"] for p in peaks), default=None),
            "locatedQuotes": located, "quoteCount": quote_count,
            "qualityAccepted": evaluation.get("qualityAccepted", False if evaluation.get("semanticAcceptance") == "not_accepted" else None)}


def rebuild_unlocked(root):
    latest = latest_records(root)
    comparisons = []
    for experiment_id, snapshot in latest.items():
        record = snapshot["record"]
        baseline = latest.get(record.get("baselineId"))
        comparisons.append({**snapshot, "compactMetrics": compact_metrics(record),
            "parameterDifferences": parameter_difference(record["parameters"], baseline["record"]["parameters"]) if baseline else None,
            "comparisonLimit": "参数差异不证明因果；输入、提示词、并发负载和评价范围仍须核对。来源定位通过不等于语义正确。"})
    comparisons.sort(key=lambda row: (row["record"].get("startedAtUtc") or "", row["record"]["experimentId"]))
    atomic_write(root / "comparison.json", json_bytes({"schemaVersion": 1, "generatedAtUtc": utc_now(), "experiments": comparisons}))
    columns = ["experimentId", "status", "baselineId", "changedFactors", "parameters", "measurements", "evaluation", "eventPath"]
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=columns)
    writer.writeheader()
    for row in comparisons:
        record = row["record"]
        writer.writerow({key: display(row["eventPath"] if key == "eventPath" else record.get(key)) for key in columns})
    atomic_write(root / "comparison.csv", stream.getvalue().encode("utf-8-sig"))
    lines = ["# 本机 vetting 实验对照日志", "", "每条记录保留原始参数、输入指纹、观测值和证据路径。后续审核只追加事件；CSV 和本页是可重建的视图。未知值保留为 null。", "",
             "来源校验、语义判断、覆盖范围分别记录；空输出不计作正确，未评价项目不进入准确率。采样显存包含同时运行的进程，不能当作模型独占峰值。", "",
             "| 实验 | 调用数 | 调用耗时合计 s | 整机采样显存 MiB | 引文定位 | 语义质量通过 | 状态 / 基线 |", "|---|---:|---:|---:|---|---|---|"]
    def cell(value):
        return display(value).replace("|", "\\|").replace("\n", " ")
    for row in comparisons:
        record = row["record"]
        metrics = row["compactMetrics"]
        quote = f"{metrics['locatedQuotes']}/{metrics['quoteCount']}" if metrics["quoteCount"] is not None else None
        quality = "否" if metrics["qualityAccepted"] is False else "是" if metrics["qualityAccepted"] is True else "待评价"
        lines.append("| " + " | ".join(cell(v) for v in (record["experimentId"], metrics["calls"], metrics["callWallSecondsSum"],
                      metrics["sampledWholeGpuPeakMiB"], quote, quality, record["status"] + " / " + display(record.get("baselineId")))) + " |")
    for row in comparisons:
        record = row["record"]
        calls = record["measurements"].get("actualCallMetrics", record["measurements"].get("calls", []))
        call_view = []
        for call in calls:
            native = call.get("nativeMetrics") or {}
            call_view.append({"stage": call.get("stage"), "clause": call.get("clause"),
                              "status": call.get("status"), "wallSeconds": call.get("wallSeconds"),
                              "promptTokens": native.get("prompt_eval_count"), "outputTokens": native.get("eval_count"),
                              "doneReason": native.get("done_reason"), "error": call.get("error")})
        evaluation = record["evaluation"]
        audit = evaluation.get("actualAuditSummary", evaluation)
        evaluation_view = {key: audit[key] for key in ("status", "summary", "semanticAcceptance", "stage1", "stage2",
                           "fieldOutcomeStates", "semanticChecklistOutcomeStates", "supportedDefectsEstablished",
                           "accuracy", "recall", "judgment", "interpretation") if key in audit}
        evaluation_view.update(qualityAccepted=row["compactMetrics"]["qualityAccepted"])
        lines.extend(["", "## " + record["experimentId"], "", "不可变事件文件：`" + row["eventPath"] + "`", "",
                      "假设：" + display(record.get("hypothesis")), "", "范围：" + display(record["scope"]), "",
                      "```json", json.dumps({"parameters": record["parameters"], "parameterDifferences": row["parameterDifferences"],
                      "observations": row["compactMetrics"], "calls": call_view, "evaluation": evaluation_view}, ensure_ascii=False, indent=2), "```",
                      "", "完整输入指纹、placement、采样条件和审核证据见不可变事件文件及 comparison.json。"])
    atomic_write(root / "comparison.md", ("\n".join(lines) + "\n").encode("utf-8"))
    # Rebuild index too, so an interrupted append cannot hide a persisted event.
    index = [{**fingerprint(path), "eventId": event["eventId"], "eventType": event["eventType"],
              "experimentId": event["record"]["experimentId"], "recordedAtUtc": event["recordedAtUtc"]}
             for path, event in events(root)]
    atomic_write(root / "events.jsonl", ("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in index)).encode("utf-8"))
    return len(comparisons)


def append_unlocked(root, record, event_type):
    validate_record(record)
    previous = latest_records(root).get(record["experimentId"])
    if previous:
        old = previous["record"]
        for key in ("parameters", "inputFingerprints"):
            if old[key] != record[key]:
                raise ValueError(key + " changed under an existing experiment ID; create a new experiment")
        if event_type == "independent_audit":
            keys = (set(old) | set(record)) - {"evaluation", "artifacts"}
            if any(old.get(key) != record.get(key) for key in keys):
                raise ValueError("Audit snapshot is stale or changes observations; reload latest under the registry lock")
        # A preregistered plan may begin once; terminal or other snapshots
        # cannot be replayed as a new running experiment under the same ID.
        starting_plan = old["status"] == "planned" and event_type == "started"
        if old["status"] != "started" and record["status"] == "started" and not starting_plan:
            raise ValueError("Cannot replace a finished experiment with an earlier running snapshot")
        old_calls = old["measurements"].get("calls", [])
        new_calls = record["measurements"].get("calls", [])
        if new_calls[:len(old_calls)] != old_calls:
            raise ValueError("Cannot drop or modify recorded calls")
    event = {"schemaVersion": 1, "eventId": uuid.uuid4().hex, "recordedAtUtc": utc_now(),
             "eventType": event_type, "record": copy.deepcopy(record)}
    directory = root / "records" / record["experimentId"]
    directory.mkdir(parents=True, exist_ok=True)
    filename = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ-") + event["eventId"] + ".json"
    path = directory / filename
    # Only complete, fsynced JSON becomes visible to events(). An interrupted
    # *.pending write is diagnostic debris, never treated as evidence.
    temporary = path.with_suffix(".pending")
    with temporary.open("xb") as stream:
        stream.write(json_bytes(event))
        stream.flush()
        os.fsync(stream.fileno())
    os.rename(temporary, path)
    rebuild_unlocked(root)
    return fingerprint(path)


def append_record(root, record, event_type="observation"):
    root = Path(root).resolve()
    validate_record(record)
    with registry_lock(root):
        return append_unlocked(root, record, event_type)


def append_audit(root, experiment_id, evaluation_path):
    root = Path(root).resolve()
    if not ID.fullmatch(experiment_id):
        raise ValueError("Unsafe experiment ID")
    with registry_lock(root):
        record = copy.deepcopy(latest_records(root)[experiment_id]["record"])
        path = Path(evaluation_path).resolve()
        raw = path.read_bytes()
        record["evaluation"] = json.loads(raw)
        record["artifacts"].append({"path": str(path), "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
        return append_unlocked(root, record, "independent_audit")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    sub = parser.add_subparsers(dest="command", required=True)
    register = sub.add_parser("register", help="Import verified historical record snapshots")
    register.add_argument("--input", type=Path, required=True)
    annotate = sub.add_parser("audit", help="Append an independent evaluation without changing observations")
    annotate.add_argument("--experiment-id", required=True)
    annotate.add_argument("--evaluation", type=Path, required=True)
    sub.add_parser("compare", help="Rebuild derived views from immutable events")
    args = parser.parse_args()
    if args.command == "register":
        records = json.loads(args.input.read_text(encoding="utf-8"))
        for record in records if isinstance(records, list) else [records]:
            print(json.dumps(append_record(args.root, record, "historical_import"), ensure_ascii=False))
    elif args.command == "audit":
        print(json.dumps(append_audit(args.root, args.experiment_id, args.evaluation), ensure_ascii=False))
    else:
        with registry_lock(args.root):
            print(json.dumps({"experiments": rebuild_unlocked(args.root), "root": str(args.root.resolve())}))


if __name__ == "__main__":
    main()
