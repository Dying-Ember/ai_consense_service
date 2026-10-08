"""Explicit fixed-source lifecycle manifest; no filename classifier or inference."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

PROTOCOL = "vetting-fixed-source-lifecycle-v1"

class ManifestError(ValueError):
    pass

def require(value, message):
    if not value:
        raise ManifestError(message)

def fingerprint(path):
    path = Path(path).resolve()
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(part)
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": h.hexdigest()}

def build(dataset_root, fixed_spec, inventory):
    """Register exact original bytes. Historical parse metadata is never fresh QA."""
    root = Path(dataset_root).resolve(strict=True)
    require(root.is_dir(), "Dataset root must be a directory")
    require(isinstance(fixed_spec, list) and fixed_spec, "An explicit fixed-source list is required")
    require(isinstance(inventory.get("sources"), list), "Source inventory is required")
    registered, used_keys, used_documents, used_paths = [], set(), set(), set()
    for item in fixed_spec:
        require(isinstance(item, dict), "Fixed-source spec must contain objects")
        key = item.get("referenceKey")
        require(isinstance(key, str) and key and key not in used_keys, "Fixed reference keys must be unique")
        relative = Path(item.get("datasetRelativePath", ""))
        require(str(relative) not in ("", ".") and not relative.is_absolute(), "Fixed source path must be relative")
        original = (root / relative).resolve(strict=True)
        require(original.is_relative_to(root) and original.is_file(), "Fixed source escapes the dataset root")
        require(original not in used_paths, "Duplicate fixed original path")
        identity = fingerprint(original)
        require(identity["sha256"] == item.get("expectedRawSha256"), "Fixed original SHA differs from the approved spec")
        matches = [r for r in inventory["sources"] if str(r.get("documentId")) == str(item.get("historicalDocumentId"))]
        require(len(matches) == 1, "Fixed source requires one explicit historical document binding")
        old = matches[0]
        require(old.get("original", {}).get("sha256") == identity["sha256"], "Historical original binding differs")
        historical_original = fingerprint(old["original"]["path"])
        require(historical_original["sha256"] == identity["sha256"], "Historical stored original differs")
        require(old.get("role") in ("standard", "tender", "project_fact", "package_manifest"), "Unknown business role")
        document = str(old["documentId"])
        require(document not in used_documents, "Duplicate historical fixed source")
        used_keys.add(key); used_paths.add(original); used_documents.add(document)
        registered.append({"referenceKey": key, "storageTier": "fixed_reference", "datasetRelativePath": relative.as_posix(),
            "original": identity, "historicalStoredOriginal": historical_original,
            "historicalDocumentId": document, "businessRole": old["role"], "fileKey": old.get("fileKey"),
            "historicalParse": {"savedSourceHash": old.get("savedSourceHash"), "status": old.get("savedParseStatus"),
                "structuredJsonSha256": old.get("savedStructuredJsonSha256"), "coverage": old.get("savedParseCoverage"),
                "provenance": "frozen_historical_metadata_only", "fidelityAccepted": None, "ocrAccuracy": None},
            "adoptedByProject": "unknown", "freshParsingEmbeddingPackagingComplete": False})
    variable = [{"historicalDocumentId": str(r["documentId"]), "storageTier": "project_variable", "businessRole": r["role"],
        "fileKey": r.get("fileKey"), "originalSha256": r["original"]["sha256"]} for r in inventory["sources"] if str(r["documentId"]) not in used_documents]
    return {"protocol": PROTOCOL, "fixedSources": registered, "variableBaselineSources": variable,
        "identityPolicy": "explicit_original_sha_allowlist_not_filename", "businessRolesReclassified": False,
        "vectorCachePolicy": "source_and_recipe_bound_rebind_parent_ids_per_project", "freshParseOcrEmbeddingRerankCalls": 0,
        "portableVectorBundleComplete": False, "h800Validated": False}

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset-root", required=True, type=Path)
    p.add_argument("--fixed-spec", required=True, type=Path)
    p.add_argument("--inventory", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()
    result = build(args.dataset_root, json.loads(args.fixed_spec.read_bytes()), json.loads(args.inventory.read_bytes()))
    result["inputFingerprints"] = {"fixedSpec": fingerprint(args.fixed_spec), "inventory": fingerprint(args.inventory), "tool": fingerprint(__file__)}
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"manifest": fingerprint(args.output), "fixedSources": len(result["fixedSources"]), "variableBaselineSources": len(result["variableBaselineSources"]), "actualNewInferenceCalls": 0}))

if __name__ == "__main__":
    main()
