"""Source-bound retrieval-internal windows; imports no model or vector client.

The caller supplies a fast tokenizer. Every actual model tokenizer call must
also be protected by FullInputTokenizerGuard: planning alone cannot guarantee
that an inference engine did not silently truncate a different feature path.
"""
from __future__ import annotations
from dataclasses import dataclass, asdict
import hashlib
import json
import math
import uuid

ALGORITHM_VERSION = "source-bound-token-windows-v1"
MAPPING_VERSION = "parent-parts-utf16-v1"
NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, "consense/retrieval/source-bound-token-windows-v1")


class WindowError(ValueError):
    def __init__(self, kind, message, **metadata):
        super().__init__(message)
        self.kind = kind
        self.metadata = metadata


def fail(condition, kind, message, **metadata):
    if not condition:
        raise WindowError(kind, message, **metadata)


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def text_sha(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def stable_parent_payload_sha(parent):
    # Source payloads survive rebinding database document IDs and project chunk
    # IDs. Every other field, including Parts/native cells/owner metadata, binds.
    return digest({k:v for k,v in parent.items() if k not in {'id','documentId'}})


def utf16_length(text):
    return len(text.encode("utf-16-le")) // 2


@dataclass(frozen=True)
class WindowRecipe:
    max_tokens: int = 512
    overlap_tokens: int = 64
    max_windows_per_parent: int = 10000
    aggregation: str = "max"
    version: str = ALGORITHM_VERSION

    def __post_init__(self):
        fail(type(self.max_tokens) is int and 4 <= self.max_tokens <= 8192, "INVALID_RECIPE", "max_tokens must be4..8192")
        fail(type(self.overlap_tokens) is int and 0 <= self.overlap_tokens < self.max_tokens, "INVALID_RECIPE", "overlap_tokens must be nonnegative and less than cap")
        fail(type(self.max_windows_per_parent) is int and self.max_windows_per_parent > 0, "INVALID_RECIPE", "window count guard must be positive")
        fail(self.aggregation == "max", "UNSUPPORTED_AGGREGATION", "Only explicit max aggregation is implemented in v1")


def one_tokens(tokenizer, text, pair=None, *, special=True, offsets=False):
    kwargs = {"add_special_tokens": special, "truncation": False, "padding": False}
    if offsets:
        kwargs["return_offsets_mapping"] = True
    result = tokenizer(text, pair, **kwargs) if pair is not None else tokenizer(text, **kwargs)
    ids = result["input_ids"]
    fail(isinstance(ids, list) and (not ids or isinstance(ids[0], int)), "TOKENIZER_SHAPE", "Expected one non-tensor sequence")
    return result


def paragraph_map(parent):
    """Map the producer's joined Parts to parent content; preserve Java UTF16.

    Only exact newline-joined Parts and its Java ASCII-trim projection are
    recognized. Any unsupported text envelope fails instead of inventing spans.
    """
    parts = parent.get("parts")
    fail(isinstance(parts, list) and parts, "MISSING_SOURCE_PARTS", "Source-bound windows require parent Parts")
    joined = "\n".join(p["text"] for p in parts)
    content = parent["content"]
    leading, trailing = 0, len(joined)
    if content != joined:
        while leading < trailing and ord(joined[leading]) <= 32:
            leading += 1
        while trailing > leading and ord(joined[trailing - 1]) <= 32:
            trailing -= 1
        fail(content == joined[leading:trailing], "UNMAPPED_PARENT_CONTENT", "Parent content is not exact/Java-trim newline-joined source Parts")
    mapped, position = [], 0
    for part in parts:
        text = part["text"]
        fail(type(part.get("startOffset")) is int and type(part.get("endOffset")) is int and part["endOffset"] - part["startOffset"] == utf16_length(text),
             "PART_UTF16_MISMATCH", "Part source span is not the UTF16 length of its text", blockId=part.get("blockId"))
        a, b = max(position, leading), min(position + len(text), trailing)
        if a < b:
            mapped.append({"parentStartCp": a - leading, "parentEndCp": b - leading, "partTextStartCp": a - position,
                           "part": part, "partTextParentOriginCp": position - leading})
        position += len(text) + 1
    return mapped, {"version": MAPPING_VERSION, "mode": "exact_join" if not leading and trailing == len(joined) else "java_trim_join",
                    "removedLeadingCodepoints": leading, "removedTrailingCodepoints": len(joined) - trailing}


def source_spans(start, end, mapped):
    rows = []
    for item in mapped:
        a, b = max(start, item["parentStartCp"]), min(end, item["parentEndCp"])
        if a >= b:
            continue
        part = item["part"]
        relative_a, relative_b = a - item["partTextParentOriginCp"], b - item["partTextParentOriginCp"]
        rows.append({"blockId": part["blockId"], "anchor": part.get("anchor"),
                     "sourceStartUtf16": part["startOffset"] + utf16_length(part["text"][:relative_a]),
                     "sourceEndUtf16": part["startOffset"] + utf16_length(part["text"][:relative_b]),
                     "textSha256": text_sha(part["text"][relative_a:relative_b])})
    return rows


def windows(parent, tokenizer, recipe, tokenizer_identity, *, query=None, embedding_strip=False):
    fail(getattr(tokenizer, "is_fast", False), "OFFSET_TOKENIZER_REQUIRED", "A fast tokenizer with explicit offsets is required")
    text = parent["content"]
    fail(isinstance(text, str) and text.strip(), "EMPTY_PARENT", "Parent content cannot be empty")
    fail(parent.get("id") and parent.get("sourceHash") and parent.get("documentId") is not None, "MISSING_PARENT_IDENTITY", "Parent ID/source hash/document must be explicit")
    mapped, construction = paragraph_map(parent)
    offsets_result = one_tokens(tokenizer, text, special=False, offsets=True)
    offsets = offsets_result["offset_mapping"]
    fail(len(offsets) == len(offsets_result["input_ids"]), "TOKENIZER_OFFSET_COUNT", "Token offset count differs")
    valid = [(int(a), int(b)) for a, b in offsets if b > a]
    fail(valid and all(0 <= a < b <= len(text) for a, b in valid), "UNKNOWN_TOKEN_OFFSETS", "Token offsets must use Unicode codepoint coordinates")
    fail(all(valid[i][0] <= valid[i + 1][0] for i in range(len(valid) - 1)), "NONMONOTONIC_TOKEN_OFFSETS", "Token offsets must be monotonic")
    query_tokens = len(one_tokens(tokenizer, query, special=False)["input_ids"]) if query is not None else 0
    special_tokens = tokenizer.num_special_tokens_to_add(pair=query is not None)
    available = recipe.max_tokens - query_tokens - special_tokens
    fail(available >= 1, "QUERY_TOKEN_BUDGET_EXHAUSTED", "Full query and special tokens leave no document token budget", queryTokens=query_tokens, specialTokens=special_tokens, maxTokens=recipe.max_tokens)
    identity_base = {"algorithm": recipe.version, "recipe": asdict(recipe), "tokenizer": tokenizer_identity,
                     "parentId": parent["id"], "parentContentSha256": text_sha(text), "sourceHash": parent["sourceHash"],
                     "documentId": str(parent["documentId"]), "role": parent["role"],
                     "querySha256": text_sha(query) if query is not None else None, "embeddingStrip": embedding_strip}
    result, token_start, start_cp = [], 0, 0
    while start_cp < len(text):
        fail(len(result) < recipe.max_windows_per_parent, "WINDOW_COUNT_GUARD", "All-or-fail window count guard reached; no omitted tail", parentId=parent["id"])
        proposed_end = min(len(valid), token_start + available)
        token_end = proposed_end
        while token_end > token_start:
            end_cp = len(text) if token_end == len(valid) else valid[token_end - 1][1]
            if end_cp <= start_cp:
                token_end -= 1
                continue
            piece = text[start_cp:end_cp]
            actual_text = piece.strip() if embedding_strip else piece
            count = len(one_tokens(tokenizer, query, actual_text)["input_ids"]) if query is not None else len(one_tokens(tokenizer, actual_text)["input_ids"])
            if count <= recipe.max_tokens:
                break
            token_end -= 1
        fail(token_end > token_start, "DOCUMENT_TOKEN_BUDGET_EXHAUSTED", "No advancing source-bound document window fits the full pair budget", parentId=parent["id"], startCp=start_cp)
        fail(piece.strip(), "EMPTY_MODEL_WINDOW", "Window contains only whitespace")
        window_id = str(uuid.uuid5(NAMESPACE, digest(identity_base | {"startUtf16": utf16_length(text[:start_cp]), "endUtf16": utf16_length(text[:end_cp])})))
        result.append({"id": window_id, "parentId": parent["id"], "parentContentSha256": text_sha(text),
                       "parentStablePayloadSha256": stable_parent_payload_sha(parent), "content": piece,
                       "operation": "rerank_pair" if query is not None else "embedding", "querySha256": text_sha(query) if query is not None else None,
                       "windowContentSha256": text_sha(piece), "documentId": parent["documentId"], "sourceHash": parent["sourceHash"], "role": parent["role"],
                       "parentStartCodepoint": start_cp, "parentEndCodepoint": end_cp,
                       "parentStartUtf16": utf16_length(text[:start_cp]), "parentEndUtf16": utf16_length(text[:end_cp]),
                       "sourceSpans": source_spans(start_cp, end_cp, mapped), "parentConstruction": construction,
                       "fullModelTokens": count, "queryTokens": query_tokens, "specialTokens": special_tokens,
                       "modelTextTransform": "strip" if embedding_strip else "identity", "modelTextSha256": text_sha(actual_text),
                       "algorithmVersion": recipe.version, "recipeFingerprint": digest(identity_base)})
        if end_cp == len(text):
            break
        effective_overlap = min(recipe.overlap_tokens, max(0, token_end - token_start - 1))
        next_token = max(token_start + 1, token_end - effective_overlap)
        next_start = min(end_cp, valid[next_token][0])
        fail(next_start > start_cp, "NONADVANCING_WINDOW", "Window offset failed to advance")
        token_start, start_cp = next_token, next_start
    verify_window_coverage(parent, result, recipe.max_tokens)
    return result


def verify_window_coverage(parent, rows, max_tokens):
    fail(rows and len({r["id"] for r in rows}) == len(rows), "WINDOW_ID_COLLISION", "Windows require unique IDs")
    cursor = 0
    mapped,construction=paragraph_map(parent)
    for row in sorted(rows, key=lambda r: r["parentStartCodepoint"]):
        a, b = row["parentStartCodepoint"], row["parentEndCodepoint"]
        fail(0 <= a < b <= len(parent["content"]) and a <= cursor, "WINDOW_COVERAGE_GAP", "Window coverage has a source gap")
        fail(row["parentId"] == parent["id"] and row["parentContentSha256"] == text_sha(parent["content"]), "WINDOW_PARENT_MISMATCH", "Window belongs to another parent")
        fail(row.get('sourceHash')==parent['sourceHash'] and row.get('role')==parent['role'] and row.get('documentId')==parent['documentId']
             and row.get('parentStablePayloadSha256')==stable_parent_payload_sha(parent), 'WINDOW_SOURCE_PAYLOAD_MISMATCH', 'Window source/role/native payload differs from parent')
        fail(row["content"] == parent["content"][a:b] and row["windowContentSha256"] == text_sha(row["content"]), "WINDOW_TEXT_MISMATCH", "Window text differs from original parent span")
        fail(row["parentStartUtf16"] == utf16_length(parent["content"][:a]) and row["parentEndUtf16"] == utf16_length(parent["content"][:b]), "WINDOW_UTF16_MISMATCH", "Window UTF16 span is incorrect")
        fail(row.get('sourceSpans')==source_spans(a,b,mapped) and row.get('parentConstruction')==construction,
             'WINDOW_SOURCE_SPANS_MISMATCH','Window Part/source UTF16 spans differ from actual parent projection')
        transformed=row['content'].strip() if row.get('modelTextTransform')=='strip' else row['content']
        fail(row.get('modelTextTransform') in {'identity','strip'} and row.get('modelTextSha256')==text_sha(transformed),
             'WINDOW_MODEL_TEXT_MISMATCH','Actual planned text transform/SHA differs')
        fail(row["fullModelTokens"] <= max_tokens, "TOKEN_BUDGET_EXCEEDED", "Window exceeds configured token budget")
        cursor = max(cursor, b)
    fail(cursor == len(parent["content"]), "WINDOW_COVERAGE_GAP", "Parent tail is not covered")
    return {"parentCodepoints": len(parent["content"]), "parentUtf16": utf16_length(parent["content"]), "windows": len(rows), "zeroGapCoverage": True}


def aggregate_max(window_scores, windows_by_id, parents_by_id):
    best = {}
    for window_id, raw_score in window_scores:
        fail(window_id in windows_by_id, "UNKNOWN_WINDOW_ID", "Ranked vector point has no bound window")
        row = windows_by_id[window_id]
        parent_id = row["parentId"]
        fail(parent_id in parents_by_id and row["parentContentSha256"] == text_sha(parents_by_id[parent_id]["content"]), "WINDOW_PARENT_MISMATCH", "Ranked window is not bound to current parent payload")
        score = float(raw_score)
        fail(math.isfinite(score), "NONFINITE_SCORE", "Window score must be finite")
        if parent_id not in best or score > best[parent_id]["score"]:
            best[parent_id] = {"id": parent_id, "score": score, "winningWindowId": window_id, "payload": parents_by_id[parent_id]}
    return sorted(best.values(), key=lambda r: (-r["score"], r["id"]))


def dense_expand(fetch, windows_by_id, parents_by_id, *, parent_limit, eligible_window_count, initial_limit=None):
    """Fetch sorted global window prefixes until enough unique parents or exhausted.

    Every query result is checked. Max aggregation's top-parent guarantee holds
    for exact sorted window prefixes; approximate vector search has no such
    exhaustive guarantee and must retain that boundary in its receipt.
    """
    fail(parent_limit > 0 and eligible_window_count >= 0, "INVALID_DENSE_LIMIT", "Dense limits must be valid")
    if eligible_window_count == 0:
        return [], {"fetches": [], "exhausted": True, "uniqueParents": 0, "completeRequestedParentCount": False}
    limit = min(eligible_window_count, initial_limit or parent_limit)
    attempts = []
    while True:
        points = fetch(limit)
        fail(len(points) <= limit and len({x[0] for x in points}) == len(points), "INVALID_DENSE_PREFIX", "Dense prefix has duplicate/too many window points")
        fail(all(float(points[i][1]) >= float(points[i + 1][1]) for i in range(len(points) - 1)), "INVALID_DENSE_PREFIX", "Dense prefix is not in score order")
        parents = aggregate_max(points, windows_by_id, parents_by_id)
        exhausted = limit >= eligible_window_count or len(points) < limit
        attempts.append({"requestedWindowLimit": limit, "returnedWindows": len(points), "uniqueParents": len(parents), "exhausted": exhausted,
                         "cutoffWindowScore": float(points[-1][1]) if points else None})
        if len(parents) >= parent_limit or exhausted:
            return parents[:parent_limit], {"fetches": attempts, "exhausted": exhausted, "uniqueParents": len(parents),
                                           "completeRequestedParentCount": len(parents) >= parent_limit,
                                           "boundary": "Exact prefix max aggregation. If provider uses approximate ANN, completeRequestedParentCount only describes cardinality, not exhaustive ranking quality."}
        limit = min(eligible_window_count, max(limit + 1, limit * 2))


class FullInputTokenizerGuard:
    """Disable hidden truncation at the real inference tokenizer boundary.

    It forwards the original text/pairs and padding but explicitly disables
    truncation. The returned attention masks must fit the frozen cap, otherwise
    execution fails before a model forward. Does not repair or clip inputs.
    """
    def __init__(self, wrapped, cap, observer=None):
        self.wrapped, self.cap, self.observer = wrapped, cap, observer
        self.ordinal = 0

    def __getattr__(self, name):
        return getattr(self.wrapped, name)

    def __call__(self, *args, **kwargs):
        self.ordinal += 1
        forwarded = dict(kwargs)
        requested = {k: forwarded.get(k) for k in ("truncation", "max_length", "padding")}
        forwarded["truncation"] = False
        forwarded.pop("max_length", None)
        result = self.wrapped(*args, **forwarded)
        masks = result.get("attention_mask")
        if hasattr(masks, "detach"):
            masks = masks.detach().cpu().tolist()
        elif hasattr(masks, "tolist"):
            masks = masks.tolist()
        fail(isinstance(masks, list), "TOKENIZER_GUARD_SHAPE", "Inference attention masks are required")
        if masks and isinstance(masks[0], int):
            masks = [masks]
        counts = [sum(row) for row in masks]
        fail(all(n <= self.cap for n in counts), "INFERENCE_TOKEN_BUDGET_EXCEEDED", "Full inference inputs exceed frozen cap; no truncation performed", ordinal=self.ordinal, cap=self.cap, fullTokenCounts=counts)
        if self.observer:
            self.observer({"ordinal": self.ordinal, "requestedOptions": requested, "effectiveTruncation": False,
                           "featureMode": "inference_tensor_features" if forwarded.get("return_tensors") == "pt" else "tokenizer_only_features",
                           "cap": self.cap, "fullTokenCounts": counts, "rows": len(counts)})
        return result
