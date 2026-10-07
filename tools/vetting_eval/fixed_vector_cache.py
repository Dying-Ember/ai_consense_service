"""Offline content-addressed fixed-material vectors, without parent-ID reuse.
Only an explicit SHA/sourceHash/role whitelist enables reads or writes.
No model, database or pickle is imported. Vectors are finite float32 bytes.
"""
import hashlib
import json
import math
from pathlib import Path
import struct
from token_windows import WindowError, fail, digest, text_sha

CACHE_VERSION = "fixed-content-vector-cache-v1"


def approved_source(window, whitelist):
    fail(whitelist.get("protocol") == "fixed-source-vector-cache-whitelist-v1", "INVALID_FIXED_WHITELIST", "Fixed cache requires an explicit whitelist protocol")
    matches = [s for s in whitelist.get("sources", []) if s.get("sourceHash") == window["sourceHash"] and s.get("role") == window["role"]]
    fail(len(matches) == 1, "SOURCE_NOT_FIXED_WHITELISTED", "Source/role is not uniquely whitelisted for fixed cache")
    source = matches[0]
    fail(source.get("storageTier") == "fixed_competition_material" and isinstance(source.get("rawSourceSha256"), str) and len(source["rawSourceSha256"]) == 64,
         "INVALID_FIXED_WHITELIST", "Whitelist requires raw source SHA and explicit fixed storage tier")
    fail(window.get("operation") == "embedding" and window.get("querySha256") is None, "NOT_FIXED_EMBEDDING_WINDOW", "Query-dependent rerank windows are not prebuilt embedding vectors")
    return source


def cache_identity(window, whitelist, embedding_identity):
    source = approved_source(window, whitelist)
    required = ("model", "tokenizer", "windowRecipe", "preprocessing", "runtimeDtype", "vectorDimension")
    fail(all(k in embedding_identity for k in required), "INCOMPLETE_CACHE_MODEL_IDENTITY", "Model/tokenizer/window/preprocessing/dtype/dimension identities are mandatory")
    # Parent IDs, project IDs and DOCX paths deliberately remain outside the
    # content identity. Full original source/payload/span identities remain in it.
    return {"version": CACHE_VERSION, "rawSourceSha256": source["rawSourceSha256"], "sourceHash": window["sourceHash"], "role": window["role"],
            "parentContentSha256": window["parentContentSha256"], "parentStablePayloadSha256":window['parentStablePayloadSha256'],
            "windowContentSha256": window["windowContentSha256"],
            "modelTextSha256": window["modelTextSha256"], "parentStartUtf16": window["parentStartUtf16"], "parentEndUtf16": window["parentEndUtf16"],
            "sourceSpans": window["sourceSpans"], "parentConstruction": window["parentConstruction"], "embeddingIdentity": embedding_identity}


def validate_vector(values, expected_dimension):
    vector = [float(v) for v in values]
    fail(len(vector) == expected_dimension and all(math.isfinite(v) for v in vector), "INVALID_CACHE_VECTOR", "Vector dimension/finite values do not match")
    fail(abs(sum(v * v for v in vector) - 1.0) <= .005, "UNNORMALIZED_CACHE_VECTOR", "Embedding cache only accepts normalized vectors")
    return vector


def store(root, window, vector, whitelist, embedding_identity):
    identity = cache_identity(window, whitelist, embedding_identity)
    key = digest(identity)
    vector = validate_vector(vector, embedding_identity["vectorDimension"])
    raw = struct.pack("<" + "f" * len(vector), *vector)
    directory = Path(root).resolve() / key
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "vector.f32le").write_bytes(raw)
    manifest = {"protocol": CACHE_VERSION, "key": key, "identity": identity, "dimension": len(vector), "dtype": "float32-le",
                "vector": {"relativePath": "vector.f32le", "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()},
                "preparedParentIdObservation": window["parentId"], "parentIdsReusable": False}
    (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def lookup(root, window, whitelist, embedding_identity):
    identity = cache_identity(window, whitelist, embedding_identity)
    key = digest(identity)
    directory = Path(root).resolve() / key
    manifest_path = directory / "manifest.json"
    if not manifest_path.exists():
        return None, {"status": "miss", "key": key}
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    fail(manifest.get("protocol") == CACHE_VERSION and manifest.get("key") == key and manifest.get("identity") == identity, "FIXED_CACHE_IDENTITY_MISMATCH", "Cache manifest does not match exact source/model/recipe identity")
    desc = manifest["vector"]
    path = (directory / desc["relativePath"]).resolve()
    fail(path.is_relative_to(directory), "FIXED_CACHE_PATH_ESCAPE", "Cache vector path escapes its content key")
    raw = path.read_bytes()
    dimension = embedding_identity["vectorDimension"]
    fail(manifest["dtype"] == "float32-le" and manifest["dimension"] == dimension and len(raw) == desc["bytes"] == dimension * 4 and hashlib.sha256(raw).hexdigest() == desc["sha256"],
         "FIXED_CACHE_BYTES_MISMATCH", "Cache vector SHA/dtype/dimension differs")
    vector = validate_vector(struct.unpack("<" + "f" * dimension, raw), dimension)
    return vector, {"status": "hit", "key": key, "newProjectParentId": window["parentId"], "preparedParentIdObservation": manifest["preparedParentIdObservation"], "parentPayloadFromCurrentProjectRequired": True}
