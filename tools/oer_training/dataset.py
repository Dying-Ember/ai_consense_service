"""Offline, source-separated OCR supervision. Never imports or loads a model."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re

SCHEMA = "consense.oer-training-manifest.v1"
PARTITIONS = {"train", "dev", "test", "quarantine"}
TASKS = {"text": "Text Recognition:", "table": "Table Recognition:"}
OTSL = ("<fcel>", "<ecel>", "<lcel>", "<ucel>", "<xcel>", "<nl>")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    return json.loads(path.read_bytes().decode("utf-8-sig"), object_pairs_hook=unique)


def write_new(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def official_path(value: str) -> bool:
    # Windows paths must also be understood when preflight runs on Linux.
    return any(re.match(r"^2[de](?:$|[_\s-])", part, re.I)
               for part in re.split(r"[/\\]", value))


def artifact(base: Path, descriptor: dict, *, verify_bytes=True) -> Path:
    if not isinstance(descriptor, dict) or not isinstance(descriptor.get("path"), str):
        raise ValueError("Artifact needs path, bytes and sha256")
    p = Path(descriptor["path"])
    p = p if p.is_absolute() else base / p
    if not p.is_file() or p.stat().st_size != descriptor.get("bytes"):
        raise ValueError(f"Artifact missing/size changed: {p}")
    if not re.fullmatch(r"[0-9a-f]{64}", descriptor.get("sha256", "")):
        raise ValueError(f"Invalid artifact digest: {p}")
    if verify_bytes and sha256(p) != descriptor["sha256"]:
        raise ValueError(f"Artifact digest changed: {p}")
    return p.resolve()


def validate_manifest(path: Path, *, verify_source_bytes=True):
    path = path.resolve()
    manifest = read_json(path)
    if manifest.get("schema") != SCHEMA:
        raise ValueError("Unsupported training manifest schema")
    if manifest.get("purpose") not in {"compatibility_only", "quality_training"}:
        raise ValueError("A truthful training purpose is required")
    sources = manifest.get("sources", [])
    samples = manifest.get("samples", [])
    if not sources or not samples:
        raise ValueError("No supervised sources/samples; inventory is not training data")
    by_id, parent, identities = {}, {}, {}

    def find(value):
        while parent[value] != value:
            parent[value] = parent[parent[value]]
            value = parent[value]
        return value

    def union(left, right):
        parent[find(left)] = find(right)

    for source in sources:
        sid = source.get("sourceId")
        if not isinstance(sid, str) or not sid or sid in by_id:
            raise ValueError("Source IDs must be unique nonempty strings")
        if source.get("partition") not in PARTITIONS:
            raise ValueError(f"Explicit partition required: {sid}")
        for key in ("sourceFamily", "editionFamily", "nearDuplicateGroup", "exposure"):
            if not isinstance(source.get(key), str) or not source[key]:
                raise ValueError(f"Explicit {key} required: {sid}")
        src = source.get("original", {})
        official = official_path(src.get("path", "")) or source.get("route") == "official_evaluation_only"
        if official and source["partition"] != "quarantine":
            raise ValueError(f"Official 2d/2e material cannot enter training/retrieval: {sid}")
        if not official and source.get("route") != "competition_in_scope":
            raise ValueError(f"Explicit in-scope routing required: {sid}")
        artifact(path.parent, src, verify_bytes=verify_source_bytes)
        by_id[sid], parent[sid] = source, sid
        # Union source versions, original aliases, derived/crop families and any
        # reviewer-declared near duplicate cluster before making partitions.
        keys = [("original", src["sha256"])]
        keys += [(key, source[key]) for key in ("sourceFamily", "editionFamily", "nearDuplicateGroup")]
        keys += [("original", digest) for digest in source.get("derivedFromOriginalSha256", [])]
        keys += [("content", digest) for digest in source.get("nearDuplicateContentDigests", [])]
        for key in keys:
            if key in identities:
                union(sid, identities[key])
            else:
                identities[key] = sid
    groups = {}
    for sid, source in by_id.items():
        groups.setdefault(find(sid), []).append(source)
    for members in groups.values():
        if len({member["partition"] for member in members}) != 1:
            raise ValueError("Same-source/near-duplicate/derived family crosses partitions")
    seen, resolved = set(), []
    for record in samples:
        rid, sid = record.get("sampleId"), record.get("sourceId")
        if not isinstance(rid, str) or not rid or rid in seen or sid not in by_id:
            raise ValueError("Sample IDs must be unique and reference a declared source")
        seen.add(rid)
        source = by_id[sid]
        if source["partition"] == "quarantine":
            raise ValueError("Official answers may be catalogued but never become supervised samples")
        if record.get("task") not in TASKS:
            raise ValueError("Pilot supports text/table recognition, not layout or relation SFT")
        if type(record.get("originalPhysicalPageNo")) is not int or record["originalPhysicalPageNo"] < 1:
            raise ValueError("One-based original physical page required")
        if record.get("coverage") != "complete_task_unit" or record.get("unknownRanges") != []:
            raise ValueError("Incomplete/unknown supervision cannot be silently made a full target")
        review = record.get("sourceReview", {})
        if review.get("method") != "source_only" or not review.get("actor"):
            raise ValueError("Supervision must be source-first, never candidate-derived")
        if type(review.get("humanConfirmed")) is not bool:
            raise ValueError("humanConfirmed needs literal true/false, not an implied confirmation")
        artifact(path.parent, review.get("artifact", {}))
        image = artifact(path.parent, record.get("image", {}))
        target = artifact(path.parent, record.get("target", {}))
        if official_path(str(image)) or official_path(str(target)) or official_path(str(review["artifact"]["path"])):
            raise ValueError("Official answers cannot enter the supervised artifact closure")
        text = target.read_bytes().decode("utf-8")
        if not text or "\ufffd" in text:
            raise ValueError("Empty/invalid target")
        transform = record.get("imageTransform", {})
        if transform.get("kind") not in {"original_full_page", "original_crop"}:
            raise ValueError("Original image/crop provenance required")
        if not isinstance(transform.get("originalNormalizedBbox"), list) or len(transform["originalNormalizedBbox"]) != 4:
            raise ValueError("Complete image transform bounds required")
        bounds = transform["originalNormalizedBbox"]
        if any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1 for v in bounds) or not (bounds[0] < bounds[2] and bounds[1] < bounds[3]):
            raise ValueError("Original image transform must stay within finite normalized page bounds")
        if record["task"] == "table" and (not text.endswith("<nl>") or "<fcel>" not in text):
            raise ValueError("MinerU table pilot requires complete raw OTSL, not HTML")
        resolved.append({"record": record, "source": source, "imagePath": image,
                         "targetPath": target, "targetText": text,
                         "prompt": TASKS[record["task"]]})
    counts = {part: sum(x["source"]["partition"] == part for x in resolved) for part in PARTITIONS}
    if manifest["purpose"] == "quality_training" and (not counts["train"] or not counts["dev"]):
        raise ValueError("Quality training requires independent-family train and dev supervision")
    inventory = {
        "schema": "consense.oer-training-preflight.v1", "purpose": manifest["purpose"],
        "manifestSha256": sha256(path), "sourceCount": len(sources), "sampleCount": len(resolved),
        "sourceGroupCount": len(groups), "samplePartitions": counts,
        "groupPartitions": [{"sourceIds": [m["sourceId"] for m in members],
                             "partition": members[0]["partition"]} for members in groups.values()],
        "sourceBytesVerified": verify_source_bytes,
        "blindTestSourceCount": sum(s["partition"] == "test" and s["exposure"] == "never_exposed" for s in sources),
        "exposedTestSourceCount": sum(s["partition"] == "test" and s["exposure"] != "never_exposed" for s in sources),
        "fullTargetBytes": sum(x["record"]["target"]["bytes"] for x in resolved),
        "humanConfirmedSampleCount": sum(x["record"]["sourceReview"]["humanConfirmed"] for x in resolved),
        "qualityImprovementClaim": False,
    }
    return manifest, resolved, inventory


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("manifest", type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--skip-original-byte-hash", action="store_true",
                   help="CPU inventory only; records sourceBytesVerified=false; trainer always hashes originals")
    args = p.parse_args()
    _, _, inventory = validate_manifest(args.manifest, verify_source_bytes=not args.skip_original_byte_hash)
    write_new(args.output, inventory)
    print(json.dumps(inventory, ensure_ascii=False))


if __name__ == "__main__":
    main()
