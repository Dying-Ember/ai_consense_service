"""File-only foundation audit. Never imports a model, vector client or HTTP client.

Support coverage is an evaluator-only diagnostic, not contract-defect accuracy.
Inputs are externally hash-bound receipts from an already completed execution.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
import math
import pathlib
import re
import statistics
import sys
import zipfile
from datetime import datetime, timezone
from xml.etree import ElementTree as ET

PROTOCOL = "vetting-foundation-file-audit-v1"
INPUT_PROTOCOL = "vetting-foundation-audit-inputs-v1"
FIXTURE_PROTOCOL = "vetting-foundation-native-support-fixtures-v1"
STAGES = ("dense", "bm25", "rrf_candidate", "rerank_input", "rerank_all", "returned", "final_context")
ZERO_CALLS = {k: 0 for k in ("documentParse", "ocr", "embedding", "rerank", "generation", "index", "query", "http", "databaseRead", "databaseWrite")}


class AuditError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise AuditError(message)


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def file_sha(path):
    h = hashlib.sha256()
    with pathlib.Path(path).open("rb") as f:
        for buf in iter(lambda: f.read(1024 * 1024), b""):
            h.update(buf)
    return h.hexdigest()


def descriptor(path):
    p = pathlib.Path(path).resolve()
    return {"path": str(p), "bytes": p.stat().st_size, "sha256": file_sha(p)}


def no_duplicates(pairs):
    obj = {}
    for k, v in pairs:
        require(k not in obj, "duplicate JSON key: " + k)
        obj[k] = v
    return obj


def loads(data):
    return json.loads(data, object_pairs_hook=no_duplicates,
                      parse_constant=lambda value: (_ for _ in ()).throw(AuditError("nonfinite JSON: " + value)))


def verified_path(desc, root=None):
    require(isinstance(desc, dict), "descriptor must be an object")
    if root is not None:
        require("relativePath" in desc, "probe descriptor missing relativePath")
        p = (root / desc["relativePath"]).resolve()
        require(p.is_relative_to(root.resolve()), "probe path escapes root")
    else:
        require(isinstance(desc.get("path"), str), "descriptor missing path")
        p = pathlib.Path(desc["path"]).resolve()
    require(p.is_file(), "missing artifact: " + str(p))
    require(type(desc.get("bytes")) is int and p.stat().st_size == desc["bytes"], "artifact byte count mismatch: " + str(p))
    require(desc.get("sha256") == file_sha(p), "artifact SHA mismatch: " + str(p))
    return p


def read_desc(desc, root=None):
    return loads(verified_path(desc, root).read_text(encoding="utf-8-sig"))


def read_external(path, expected_sha):
    d = descriptor(path)
    require(d["sha256"] == expected_sha, "external SHA mismatch")
    return read_desc(d), d


def ids_unique(ids, corpus, label):
    require(isinstance(ids, list) and len(set(ids)) == len(ids), label + ": duplicate/invalid IDs")
    require(all(x in corpus for x in ids), label + ": ID outside corpus")
    return ids


def text_sha(text):
    return sha_bytes(text.encode("utf-8"))


def canonical_digest(value):
    return text_sha(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False))


def without_nulls(value):
    if isinstance(value, dict):
        return {k: without_nulls(v) for k, v in value.items() if v is not None}
    if isinstance(value, list):
        return [without_nulls(v) for v in value]
    return value


def verify_identity(row, corpus):
    require(row.get("id") in corpus, "ranking identity outside corpus")
    c = corpus[row["id"]]
    for k in ("documentId", "role", "sourceHash"):
        if k in row:
            require(str(row[k]) == str(c[k]), "ranking " + k + " mismatch")
    if "contentSha256" in row:
        require(row["contentSha256"] == text_sha(c["content"]), "ranking content SHA mismatch")


def finite(value):
    require(isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value), "invalid ranking score")
    return float(value)


def merge_intervals(intervals):
    merged = []
    for start, end in sorted(intervals):
        require(type(start) is int and type(end) is int and 0 <= start <= end, "invalid part interval")
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(end, merged[-1][1])
        else:
            merged.append([start, end])
    return merged


def fully_covers(intervals, start, end):
    return any(a <= start and b >= end for a, b in merge_intervals(intervals))


def block_payload(source):
    rows = read_desc(source["blocksArtifact"])
    require(isinstance(rows, list), "blocks must be an array")
    result = {}
    for row in rows:
        require(row["id"] not in result, "duplicate source block")
        result[row["id"]] = row["text"]
    return result


def read_corpus(cm):
    p = verified_path(cm["corpus"])
    corpus = {}
    sources = {str(s["documentId"]): s for s in cm["sources"]}
    require(len(sources) == len(cm["sources"]), "duplicate source descriptors")
    blocks = {k: block_payload(v) for k, v in sources.items()}
    for line in p.read_text(encoding="utf-8").splitlines():
        c = loads(line)
        require(c["id"] not in corpus, "duplicate corpus ID")
        doc = str(c["documentId"])
        require(doc in sources, "chunk source not declared")
        require(c["sourceHash"] == sources[doc]["newDerivedSourceHash"], "chunk source hash mismatch")
        require(c["role"] == sources[doc]["role"], "chunk role differs from declared source")
        require(isinstance(c.get("parts"), list) and c["parts"], "chunk has no source parts")
        for part in c["parts"]:
            require(part["blockId"] in blocks[doc], "unknown source block")
            start, end = part["startOffset"], part["endOffset"]
            require(type(start) is int and type(end) is int and 0 <= start <= end <= len(blocks[doc][part["blockId"]]), "invalid source offset")
            require(blocks[doc][part["blockId"]][start:end] == part["text"], "part text differs from saved parsed source")
        corpus[c["id"]] = c
    require(len(corpus) == cm["chunkCount"], "corpus count mismatch")
    return corpus, sources, blocks


def native_fixture_binding(target, sources, blocks, native_cache):
    """Return unknown for explicitly unbound legacy observations. Never remap silently."""
    if target.get("bindingStatus") != "bound_native_source_observation":
        return {"status": "unknown", "reason": target.get("unknownReason", "not bound")}
    doc = str(target["documentId"])
    if doc not in sources:
        return {"status": "unknown", "reason": "document_not_in_this_scope"}
    source = sources[doc]
    if target["rawSourceSha256"] != source["originalBytes"]["sha256"]:
        return {"status": "unknown", "reason": "raw_source_identity_changed"}
    verified_path(source["originalBytes"])
    if doc not in native_cache:
        with zipfile.ZipFile(source["originalBytes"]["path"]) as z:
            raw = z.read("word/document.xml")
        root = ET.fromstring(raw)
        ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
        native_cache[doc] = (list(root.find("w:body", ns)), sha_bytes(raw), ns)
    children, xml_sha, ns = native_cache[doc]
    require(xml_sha == target["nativeDocumentXmlSha256"], "native document.xml identity differs")
    for observation in target["nativeObservations"]:
        element = children[observation["bodyChildIndex"]]
        actual = "".join(n.text or "" for n in element.findall(".//w:t", ns))
        require(text_sha(actual) == observation["textNodesSha256"], "native body text identity mismatch")
        for literal in observation.get("literals", []):
            require(literal in actual, "native observed literal missing")
        if observation.get("requiresNumberingProperties"):
            require(element.find(".//w:numPr", ns) is not None, "native numbering properties missing")
    for req in target["requiredParts"]:
        text = blocks[doc].get(req["blockId"])
        if text is None:
            return {"status": "unknown", "reason": "saved_parser_block_identity_changed"}
        require(0 <= req["startOffset"] <= req["endOffset"] <= len(text), "fixture offset out of range")
        require(text_sha(text[req["startOffset"]:req["endOffset"]]) == req["textSha256"], "fixture source text changed")
    return {"status": "bound", "sourceHash": source["newDerivedSourceHash"], "documentId": doc,
            "nativeDocumentXmlSha256": xml_sha}


def support_for_ids(target, binding, ids, corpus):
    if binding["status"] != "bound":
        return {"status": "unknown", "reason": binding["reason"], "coverage": None, "rank": None}
    coverage, matching = [], []
    for req in target["requiredParts"]:
        spans, support = [], []
        for rank, id_ in enumerate(ids, 1):
            c = corpus[id_]
            if str(c["documentId"]) != binding["documentId"] or c["sourceHash"] != binding["sourceHash"]:
                continue
            for part in c["parts"]:
                if part["blockId"] == req["blockId"] and part["endOffset"] > req["startOffset"] and part["startOffset"] < req["endOffset"]:
                    spans.append([part["startOffset"], part["endOffset"]])
                    if id_ not in support:
                        support.append(id_)
                    if (rank, id_) not in matching:
                        matching.append((rank, id_))
        coverage.append({"blockId": req["blockId"], "startOffset": req["startOffset"], "endOffset": req["endOffset"],
                         "covered": fully_covers(spans, req["startOffset"], req["endOffset"]), "supportingChunkIds": support})
    return {"status": "observed", "coverage": all(x["covered"] for x in coverage),
            "coveredParts": sum(x["covered"] for x in coverage), "requiredParts": len(coverage),
            "rank": min((x[0] for x in matching), default=None), "matchingChunkIdsInRankOrder": [x[1] for x in sorted(matching)],
            "parts": coverage, "rankMeaning": "first supporting chunk, not necessarily whole provision coverage"}


def stats(values):
    if not values:
        return {"count": 0, "min": None, "max": None, "mean": None, "p95": None}
    vals = sorted(values)
    pos = .95 * (len(vals) - 1)
    lo, hi = math.floor(pos), math.ceil(pos)
    return {"count": len(vals), "min": vals[0], "max": vals[-1], "mean": statistics.mean(vals),
            "p95": vals[lo] + (vals[hi] - vals[lo]) * (pos - lo)}


def token_event_rows(event, corpus):
    p, ctx = event["payload"], event["context"]
    arrays = [p[k] for k in ("fullTokenCounts", "actualUnpaddedTokenCounts", "tokensTruncated", "textBindingsInActualBatchOrder")]
    require(len({len(x) for x in arrays}) == 1, "token array lengths differ")
    rows = []
    for full, actual, lost, bindings in zip(*arrays):
        require(all(type(x) is int and x >= 0 for x in (full, actual, lost)), "invalid token count")
        require(full - actual == lost, "token loss arithmetic differs")
        candidate_ids, transforms = [], []
        ambiguous = False
        for text_binding in bindings:
            matches = text_binding["sourceMatches"]
            ambiguous |= len(matches) > 1
            for match in matches:
                verify_identity(match, corpus)
                original = corpus[match["id"]]["content"]
                matched_transform = next((name for name, value in (("identity", original), ("strip", original.strip()))
                                          if text_sha(value) == text_binding["textSha256"] and len(value) == text_binding["chars"]), None)
                require(matched_transform is not None, "token source text identity/strip binding mismatch")
                transforms.append({"chunkId": match["id"], "observedTextTransform": matched_transform})
                if match["id"] not in candidate_ids:
                    candidate_ids.append(match["id"])
        rows.append({"eventOrdinal": event["eventOrdinal"], "kind": "embedding" if event["stage"].startswith("embedding") else "reranker",
                     "operation": ctx["operation"], "queryOrdinal": ctx.get("queryOrdinal"), "fullTokens": full,
                     "actualTokens": actual, "truncatedTokens": lost, "ambiguousSourceBinding": ambiguous,
                     "candidateSourceIds": candidate_ids, "sourceTextTransformBindings": transforms,
                     "actualTokenizerOptions": p["actualTokenizerOptions"],
                     "effectiveModelMaxLength": p["effectiveModelMaxLength"]})
    return rows


def collect_probes(cpu, corpus):
    probe = read_desc(cpu["pipelineProbeManifest"])
    root = pathlib.Path(cpu["pipelineProbeManifest"]["path"]).resolve().parent
    require(probe["status"] == "completed", "probe not completed")
    artifacts = {}
    for desc in probe["artifacts"]:
        name = desc["relativePath"]
        require(name not in artifacts, "duplicate probe artifact")
        verified_path(desc, root)
        artifacts[name] = desc
    rows, stage_payloads, stage_counts, index_check = [], {}, {}, None
    relevant = {"dense-candidates", "bm25-candidates", "rrf-candidates", "reranker-call-start", "reranker-output", "retrieval-final-ranking"}
    for entry in probe["events"]:
        require(entry["artifact"] == artifacts.get(entry["artifact"]["relativePath"]), "event artifact differs from manifest")
        stage = entry["stage"]
        stage_counts[stage] = stage_counts.get(stage, 0) + 1
        if stage in relevant or stage in {"embedding-tokenizer-input", "reranker-tokenizer-input", "index-input"}:
            event = read_desc(entry["artifact"], root)
            require(event["stage"] == stage and event["eventOrdinal"] == entry["ordinal"], "event identity differs")
            ctx = event["context"]
            require(ctx["projectId"] == cpu["projectId"], "probe project differs")
            if stage == "index-input":
                require(index_check is None, "duplicate index-input")
                data = event["payload"]
                chunks = data["chunks"]
                ids_unique([c["id"] for c in chunks], corpus, "index input")
                require(len(chunks) == len(corpus) == data["chunkCount"], "index/corpus size differs")
                require([c["id"] for c in chunks] == sorted(corpus), "index normalized chunk order differs")
                require(all(without_nulls(c) == without_nulls(corpus[c["id"]]) for c in chunks), "index full input payload differs from corpus")
                corpus_digest = canonical_digest(chunks)
                require(data["signature"]["corpus"] == corpus_digest == cpu["normalizedCorpusSha256"], "normalized corpus signature differs")
                require(canonical_digest(data["signature"]) == cpu["indexSignature"], "index signature arithmetic differs")
                index_check = {"rows": len(chunks), "normalizedCorpusSha256": corpus_digest, "signatureFields": data["signature"],
                               "indexSignatureComputed": canonical_digest(data["signature"]), "fullInputPayloadIdentityVerified": True}
            elif stage in relevant:
                key = (ctx["queryOrdinal"], stage)
                require(key not in stage_payloads, "duplicate query stage")
                stage_payloads[key] = (event["payload"], ctx)
            else:
                rows.extend(token_event_rows(event, corpus))
    require(index_check is not None, "missing index-input probe")
    return stage_payloads, rows, {"events": len(probe["events"]), "artifacts": len(artifacts), "stageCounts": stage_counts, "indexInputVerification": index_check,
                                  "verification": "SHA/bytes of every listed probe artifact; parsed ranking and tokenizer events only"}


def rankings_for(call, payloads, corpus, request):
    q = call["ordinal"]
    def stage(name):
        require((q, name) in payloads, "missing ranking probe: " + name)
        payload, context = payloads[(q, name)]
        require(context.get("request") == request, "probe query/request differs")
        return payload
    dense = stage("dense-candidates")["orderedCandidates"]
    for row in dense:
        verify_identity(row, corpus)
        finite(row["score"])
    require(all(dense[i]["score"] >= dense[i + 1]["score"] for i in range(len(dense) - 1)), "dense order differs")
    bm = stage("bm25-candidates")
    bm_scores = {x["id"]: finite(x["score"]) for x in bm["allScopeScores"]}
    require(len(bm_scores) == len(bm["allScopeScores"]), "duplicate BM25 score ID")
    ids_unique(list(bm_scores), corpus, "bm25 score scope")
    bm_ids = ids_unique(bm["orderedCandidateIds"], corpus, "bm25 candidates")
    expected_scope = {id_ for id_, c in corpus.items() if request.get("role") is None or c["role"] == request["role"]}
    require(set(bm_scores) == expected_scope, "BM25 saved score scope differs from role-filtered corpus")
    require(all(bm_scores[bm_ids[i]] >= bm_scores[bm_ids[i + 1]] for i in range(len(bm_ids) - 1)), "BM25 candidate order differs")
    rrf = stage("rrf-candidates")
    rrf_ids = ids_unique(rrf["submittedToRerankIds"], corpus, "RRF candidates")
    dense_ids = ids_unique([x["id"] for x in dense], corpus, "dense candidates")
    require(set(dense_ids) <= expected_scope and set(bm_ids) <= expected_scope, "candidate role filter differs")
    constant = finite(rrf["constant"])
    require(constant > 0, "invalid RRF constant")
    scores = {}
    for sequence in (dense_ids, bm_ids):
        for rank, id_ in enumerate(sequence, 1):
            scores[id_] = scores.get(id_, 0) + 1 / (constant + rank)
    fused = rrf["allFusedInOrder"]
    ids_unique([x["id"] for x in fused], corpus, "RRF fused")
    require(set(scores) == {x["id"] for x in fused}, "RRF union differs")
    for row in fused:
        require(abs(finite(row["score"]) - scores[row["id"]]) < 1e-12, "RRF arithmetic differs")
    require(all(fused[i]["score"] >= fused[i + 1]["score"] for i in range(len(fused) - 1)), "RRF order differs")
    require(rrf_ids == [x["id"] for x in fused[:request["candidates"]]], "RRF submitted prefix differs")
    rerank_start = stage("reranker-call-start")
    rerank_in = rerank_start["candidateIdentitiesInInputOrder"]
    for row in rerank_in:
        verify_identity(row, corpus)
    input_ids = ids_unique([x["id"] for x in rerank_in], corpus, "reranker input")
    require(input_ids == rrf_ids, "reranker input differs from actual RRF submission")
    require(rerank_start["querySha256"] == text_sha(request["query"]), "reranker query SHA differs")
    output = stage("reranker-output")
    require(output["querySha256"] == text_sha(request["query"]), "reranker output query SHA differs")
    score_values = [finite(x) for x in output["scoresInCandidateOrder"]]
    require(len(score_values) == len(input_ids), "rerank score count differs")
    score_by_id = dict(zip(input_ids, score_values))
    rank = stage("retrieval-final-ranking")
    all_rows = rank["allRerankedInOrder"]
    all_ids = ids_unique([x["id"] for x in all_rows], corpus, "rerank all")
    require(set(all_ids) == set(input_ids), "rerank output membership differs")
    for row in all_rows:
        require(abs(finite(row["score"]) - score_by_id[row["id"]]) < 1e-12, "rerank score mapping differs")
    require(all(all_rows[i]["score"] >= all_rows[i + 1]["score"] for i in range(len(all_rows) - 1)), "rerank order differs")
    output_candidate_ids = [x["id"] for x in output["candidatesInScoreOrder"]]
    require(output_candidate_ids in (input_ids, all_ids), "rerank candidate field cannot be bound to input or score order")
    candidate_field_order = "input_order" if output_candidate_ids == input_ids else "score_order"
    for row in output["candidatesInScoreOrder"]:
        verify_identity(row, corpus)
    returned = ids_unique(rank["returnedIds"], corpus, "returned")
    require(returned == all_ids[:request["limit"]] == call["orderedIds"], "topK prefix differs")
    response = read_desc(call["responseArtifact"])
    hits = response.get("hits", response.get("chunks")) if isinstance(response, dict) else response
    require(isinstance(hits, list), "unexpected retrieval response")
    require([x["id"] for x in hits] == returned, "response order differs")
    for hit in hits:
        c = corpus[hit["id"]]
        payload = hit.get("payload", hit)
        require(payload["id"] == hit["id"], "returned payload ID differs from hit ID")
        require(without_nulls(payload) == without_nulls(c), "returned full payload differs from corpus (null omission allowed)")
    return {"dense": dense_ids, "bm25": bm_ids, "rrf_candidate": rrf_ids, "rerank_input": input_ids,
            "rerank_all": all_ids, "returned": returned}, {"rrfConstant": constant, "candidateCounts": {"dense": len(dense_ids), "bm25": len(bm_ids), "rrfUnion": len(fused), "submitted": len(rrf_ids)}, "arithmeticAndOrderVerified": True,
            "rerankerProbeCandidatesInScoreOrderObservedOrder": candidate_field_order,
            "probeNamingWarning": "candidatesInScoreOrder holds input order; final sorted scores are verified independently" if candidate_field_order == "input_order" and input_ids != all_ids else None}


def load_context(material_desc, cpu_desc, corpus_desc, cpu, corpus):
    if material_desc is None:
        return {}, {"status": "unknown", "reason": "context material not supplied"}
    m = read_desc(material_desc)
    require(m["retrievalResult"]["sha256"] == cpu_desc["sha256"] and m["corpusManifest"]["sha256"] == corpus_desc["sha256"], "context input binding differs")
    require(m["projectId"] == cpu["projectId"] and m["indexSignature"] == cpu["indexSignature"], "context project/index differs")
    contexts = {}
    cpu_calls = {c["ordinal"]: c for c in cpu["calls"] if c["kind"] == "retrieve"}
    for call in m["calls"]:
        q = call["dispatchOrdinal"]
        require(q in cpu_calls, "context query not executed")
        require(call["requestArtifact"] == cpu_calls[q]["requestArtifact"] and call["responseArtifact"] == cpu_calls[q]["responseArtifact"], "context request/response differs")
        selection = read_desc(call["selection"])
        chunks = selection["chunks"]
        ids = ids_unique([c["id"] for c in chunks], corpus, "context selection")
        require(ids == call["submittedChunkIds"], "context submitted ID order differs")
        for c in chunks:
            original = corpus[c["id"]]
            for key in ("content", "documentId", "sourceHash", "parts"):
                require(c.get(key) == original.get(key), "context full chunk differs: " + key)
        reference_rows = []
        for ref in selection.get("references", []):
            required = ids_unique(ref["requiredTargetIds"], corpus, "reference required")
            submitted = ids_unique(ref["submittedTargetIds"], corpus, "reference submitted")
            missing = ids_unique(ref["missingTargetIds"], corpus, "reference missing")
            require(set(submitted) == set(required) & set(ids), "reference submitted does not equal exact selected intersection")
            require(set(missing) == set(required) - set(ids), "reference missing does not equal exact selected difference")
            require(ref["originId"] in corpus and ref["originSubmitted"] == (ref["originId"] in ids), "reference origin membership differs")
            if required:
                owners = {(str(corpus[x]["documentId"]), corpus[x]["sourceHash"], corpus[x].get("clauseId"), corpus[x].get("clauseHeadingLocation")) for x in required}
                require(len(owners) == 1, "reference target owner is ambiguous")
                owner = next(iter(owners))
                require(owner[3] == ref["targetHeadingLocation"], "reference target heading differs")
                roles = {corpus[x]["role"] for x in required}
                require(len(roles) == 1, "reference target role is ambiguous")
                require(ref["targetSourceIdentity"] == "|".join((owner[0], owner[1], next(iter(roles)), str(owner[3]))), "reference target source identity differs")
                canonical_owner = re.sub(r"\s+", "", owner[2] or "").upper()
                canonical_family = re.sub(r"\.[A-Z]$", "", canonical_owner)
                require(ref["targetClauseId"] == canonical_family, "reference target clause family differs")
                if ref.get("fullParentContextSelected"):
                    require(not missing and set(required) == {x for x, c in corpus.items() if (str(c["documentId"]), c["sourceHash"], c.get("clauseId"), c.get("clauseHeadingLocation")) == owner}, "full parent claim lacks complete same-owner set")
            reference_rows.append({k: ref.get(k) for k in ("originId", "reference", "targetClauseId", "targetSourceIdentity", "targetHeadingLocation", "resolutionMode", "status", "admissionStatus", "requiredTargetIds", "submittedTargetIds", "missingTargetIds", "requestedIncrementalChars", "budgetRemainingAtAdmission", "originSubmitted", "fullParentContextSelected", "exactSubclauseVerified")})
        contexts[q] = {"ids": ids, "contentChars": selection["contentChars"], "references": reference_rows,
                       "qualifierCompleteness": call.get("qualifierCompleteness", "unknown")}
    return contexts, {"status": "verified_saved_material", "settings": m.get("contextBuilderSettings"), "calls": len(contexts),
                      "referencesRequested": [c.get("referencesRequested") for c in m["calls"]], "scope": m.get("coverageInterpretation")}


def import_persistence(desc, corpus_desc, cpu_desc):
    if desc is None:
        return {"status": "unknown", "reason": "no hash-bound prior persistence receipt supplied", "freshClientReopenVerified": None}
    p = read_desc(desc)
    require(p["status"] == "completed", "prior persistence audit not completed")
    require(p["inputs"]["corpusManifest"]["sha256"] == corpus_desc["sha256"] and p["inputs"]["actualCpuResult"]["sha256"] == cpu_desc["sha256"], "prior persistence receipt applies to another execution")
    return {"status": "imported_bound_prior_audit", "receipt": desc, "persistedRecords": p["persistedRecords"],
            "persistedVectorsExactlyEqualActualIndexOutputs": p["persistedVectorsExactlyEqualActualIndexOutputs"],
            "vectorDimension": p["vectorDimension"], "quickCheck": p["quickCheck"], "allNewPartOffsetsExact": p["allNewPartOffsetsExact"],
            "sqliteBytesUnchangedAfterPriorAudit": p["sqliteBytesUnchangedAfterAudit"], "freshClientReopenVerified": None,
            "boundary": "Prior audit verified immutable SQLite/inert point payloads and saved index vectors. This tool does not open any DB or revalidate vectors."}


def source_identity(desc):
    if desc is None:
        return {"status": "unknown"}
    p = verified_path(desc)
    source = p.read_text(encoding="utf-8-sig")
    tree = ast.parse(source)
    names = ("digest", "tokens", "model_runtime", "retrieval_settings", "embedding_model", "rerank_model", "encode_embeddings", "rerank_candidates", "signature", "read_project", "index", "retrieve")
    functions = []
    for n in tree.body:
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name in names:
            segment = ast.get_source_segment(source, n)
            functions.append({"name": n.name, "firstLine": n.lineno, "lastLine": n.end_lineno, "sourceSha256": text_sha(segment)})
    signature_node = next((n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "signature"), None)
    keys = sorted({k.value for n in ast.walk(signature_node) if isinstance(n, ast.Dict) for k in n.keys if isinstance(k, ast.Constant) and isinstance(k.value, str)}) if signature_node else []
    return {"status": "read_only_source_observation", "source": desc, "functions": functions, "signatureLiteralKeys": keys,
            "fingerprintGapObserved": "algorithm/preprocessing/tokenizer byte identity is not an explicit signature key" if "signatureVersion" in keys and not any("algorithm" in k.lower() or "preprocess" in k.lower() for k in keys) else None,
            "badCacheReuseObserved": False,
            "recommendation": "Before introducing a new normalization, source envelope, token window or embedding instruction, include explicit versioned preprocessing/algorithm and resolved tokenizer/model artifact identities in the embedding signature. Bind retrieval/BM25/RRF/rerank algorithm identity separately to retrieval result/cache identity. Do not infer a stale-cache incident from this missing field alone."}


def audit(inputs, input_desc):
    require(inputs.get("protocol") == INPUT_PROTOCOL, "unsupported input protocol")
    cm_desc, cpu_desc, fix_desc = (inputs[k] for k in ("corpusManifest", "cpuResult", "fixtures"))
    cm, cpu, fixture = map(read_desc, (cm_desc, cpu_desc, fix_desc))
    require(cpu["status"] == "completed", "CPU execution is incomplete")
    require(cpu["projectId"] == cm["projectId"], "CPU/corpus project differs")
    plan = read_desc(cpu["plan"])
    require(plan["corpusManifest"]["sha256"] == cm_desc["sha256"], "CPU plan corpus differs")
    require(fixture["protocol"] == FIXTURE_PROTOCOL and fixture.get("evaluatorOnly") is True, "fixtures must be evaluator-only observations")
    require(fixture.get("semanticDefectGold") is None, "foundation fixtures must not supply defect gold")
    for receipt in fixture.get("publicSourceObservationReceipts", []):
        verified_path(receipt)
    corpus, sources, blocks = read_corpus(cm)
    payloads, token_rows, probes = collect_probes(cpu, corpus)
    contexts, context_meta = load_context(inputs.get("contextMaterial"), cpu_desc, cm_desc, cpu, corpus)
    native_cache, target_bindings, targets = {}, {}, fixture["targets"]
    require(len({t["id"] for t in targets}) == len(targets), "duplicate fixture ID")
    for target in targets:
        target_bindings[target["id"]] = native_fixture_binding(target, sources, blocks, native_cache)
    target_rows, query_rows = [], []
    for call in cpu["calls"]:
        if call["kind"] != "retrieve":
            continue
        wrapper = read_desc(call["requestArtifact"])
        require(set(wrapper) == {"originalTopicOrdinal", "request"}, "retrieval request wrapper differs")
        require(wrapper == plan["requests"][call["ordinal"] - 1], "executed request wrapper differs from approved plan")
        require(wrapper["originalTopicOrdinal"] == call["originalTopicOrdinal"], "request topic identity differs")
        request = wrapper["request"]
        require(request["projectId"] == cpu["projectId"], "query project differs")
        stage_ids, checks = rankings_for(call, payloads, corpus, request)
        context = contexts.get(call["ordinal"])
        stage_ids["final_context"] = context["ids"] if context else None
        query_rows.append({"queryOrdinal": call["ordinal"], "originalTopicOrdinal": call["originalTopicOrdinal"],
                           "query": request["query"], "querySha256": text_sha(request["query"]), "role": request.get("role"),
                           "limit": request["limit"], "candidates": request["candidates"], "request": call["requestArtifact"],
                           "stageIds": stage_ids, "checks": checks, "contextContentChars": context["contentChars"] if context else None,
                           "literalReferenceClosure": context["references"] if context else None,
                           "qualifierCompleteness": context["qualifierCompleteness"] if context else "unknown"})
        for target in targets:
            if call["originalTopicOrdinal"] not in target["originalTopicOrdinals"]:
                continue
            binding = target_bindings[target["id"]]
            per_stage = {name: support_for_ids(target, binding, ids, corpus) if ids is not None else {"status": "unknown", "reason": "no_context_material", "coverage": None, "rank": None} for name, ids in stage_ids.items()}
            corpus_coverage = support_for_ids(target, binding, list(corpus), corpus)
            observed_token_rows = [r for r in token_rows if r["candidateSourceIds"] and set(r["candidateSourceIds"]) & set(corpus_coverage.get("matchingChunkIdsInRankOrder", []))]
            target_rows.append({"targetId": target["id"], "label": target["label"], "queryOrdinal": call["ordinal"],
                                "originalTopicOrdinal": call["originalTopicOrdinal"], "binding": binding, "corpus": corpus_coverage,
                                "stages": per_stage, "tokenizerRows": observed_token_rows,
                                "interpretation": "Necessary support observation only; ranks/coverage do not label an issue or prove semantic accuracy."})
    metric_rows = []
    for q in query_rows:
        rows = [t for t in target_rows if t["queryOrdinal"] == q["queryOrdinal"]]
        for name in STAGES:
            known = [r["stages"][name] for r in rows if r["stages"][name]["coverage"] is not None]
            metric_rows.append({"queryOrdinal": q["queryOrdinal"], "stage": name, "coveredTargets": sum(x["coverage"] for x in known),
                                "boundObservedTargets": len(known), "unknownTargets": len(rows) - len(known),
                                "supportCoverage": sum(x["coverage"] for x in known) / len(known) if known else None,
                                "semanticPrecision": None, "semanticRecall": None, "ndcg": None,
                                "denominatorMeaning": "only the fixed evaluator source-support observations assigned to this topic; not all relevant provisions or defect gold"})
    token_summary = []
    for kind, operation in sorted({(r["kind"], r["operation"]) for r in token_rows}):
        selected = [r for r in token_rows if (r["kind"], r["operation"]) == (kind, operation)]
        token_summary.append({"kind": kind, "operation": operation, "rows": len(selected),
                              "fullTokens": stats([r["fullTokens"] for r in selected]), "actualTokens": stats([r["actualTokens"] for r in selected]),
                              "truncatedRows": sum(r["truncatedTokens"] > 0 for r in selected), "totalTruncatedTokens": sum(r["truncatedTokens"] for r in selected),
                              "maxTruncatedTokens": max((r["truncatedTokens"] for r in selected), default=0),
                              "ambiguouslyBoundRows": sum(r["ambiguousSourceBinding"] for r in selected)})
    return {"protocol": PROTOCOL, "status": "completed_file_only_audit", "preparedAtUtc": datetime.now(timezone.utc).isoformat(),
            "inputs": input_desc, "software": descriptor(__file__), "fixtures": fix_desc, "corpusManifest": cm_desc, "cpuResult": cpu_desc,
            "indexSignature": cpu["indexSignature"], "projectId": cpu["projectId"], "chunkCount": len(corpus),
            "metadataVersion": cm.get("metadataVersion"), "segmentationVersion": cm.get("segmentationVersion"),
            "historicalParameters": cpu.get("parameters"), "historicalModelFiles": cpu.get("modelFiles"),
            "historicalActualExecution": {k: cpu.get(k) for k in ("actualIndexAttempts", "actualRetrievalAttempts", "actualDocumentParseCalls", "actualOcrCalls", "actualLocalVectorDbIndexAttempts", "observedModelBoundaryCounts", "wholeWallSeconds", "changedFactors")},
            "actualCallsThisAudit": ZERO_CALLS.copy(), "probeVerification": probes, "tokenSummary": token_summary,
            "tokenRows": token_rows, "queries": query_rows, "targets": target_rows, "layerMetrics": metric_rows,
            "contextVerification": context_meta, "persistence": import_persistence(inputs.get("persistenceAudit"), cm_desc, cpu_desc),
            "sourceIdentityMeaning": inputs.get("sourceIdentityMeaning", "Current read-only source observation, not claimed as historical pre-bound runtime bytes."),
            "algorithmIdentity": source_identity(inputs.get("retrievalSource")), "semanticQuality": {"accepted": None, "precision": None, "recall": None, "reason": "No adjudicated defect labels. Native evidence support and contract-defect semantics are separate."},
            "limitations": ["Saved actual execution only; no new live parse/index/query/model/OCR/network/database call.",
                            "Native body text and parser block/offset bindings are checked, not rendered page/table layout.",
                            "Full parent closure applies only to the declared source/hash/heading target set; exact subclause metadata and global qualifiers can remain unknown.",
                            "Imported persistence audit is not a new database reopen test.",
                            "Unbound historical 44-source observations stay unknown and are excluded from observed denominators."]}


def compare(baseline, candidate, b_desc, c_desc):
    require(baseline["protocol"] == candidate["protocol"] == PROTOCOL, "comparison protocol differs")
    require(baseline["fixtures"]["sha256"] == candidate["fixtures"]["sha256"], "comparison fixture identity differs")
    bq = {q["originalTopicOrdinal"]: q for q in baseline["queries"]}
    cq = {q["originalTopicOrdinal"]: q for q in candidate["queries"]}
    require(set(bq) == set(cq), "comparison topic scopes differ")
    for topic in bq:
        require((bq[topic]["querySha256"], bq[topic]["role"]) == (cq[topic]["querySha256"], cq[topic]["role"]), "comparison query/role differs")
    bt = {(t["targetId"], t["originalTopicOrdinal"]): t for t in baseline["targets"]}
    ct = {(t["targetId"], t["originalTopicOrdinal"]): t for t in candidate["targets"]}
    require(set(bt) == set(ct), "comparison target scope differs")
    deltas = []
    for key in sorted(bt):
        for stage in STAGES:
            b, c = bt[key]["stages"][stage], ct[key]["stages"][stage]
            deltas.append({"targetId": key[0], "originalTopicOrdinal": key[1], "stage": stage,
                           "baselineCoverage": b["coverage"], "candidateCoverage": c["coverage"],
                           "baselineRank": b["rank"], "candidateRank": c["rank"],
                           "rankDelta": c["rank"] - b["rank"] if c["rank"] is not None and b["rank"] is not None else None,
                           "coverageChanged": b["coverage"] != c["coverage"] if None not in (b["coverage"], c["coverage"]) else None})
    fields = ("indexSignature", "corpusManifest", "metadataVersion", "segmentationVersion", "historicalParameters", "historicalModelFiles", "contextVerification", "algorithmIdentity")
    return {"protocol": "vetting-foundation-file-comparison-v1", "status": "completed", "baseline": b_desc, "candidate": c_desc,
            "sameFixtures": baseline["fixtures"], "changedObservedFactors": [k for k in fields if baseline.get(k) != candidate.get(k)],
            "deltas": deltas, "baselineLayerMetrics": baseline["layerMetrics"], "candidateLayerMetrics": candidate["layerMetrics"],
            "baselineTokens": baseline["tokenSummary"], "candidateTokens": candidate["tokenSummary"], "actualCallsThisComparison": ZERO_CALLS.copy(),
            "causalConclusion": None, "semanticAccuracyConclusion": None,
            "interpretation": "Same fixture/query/role diagnostic comparison; changed factors and stochastic execution prohibit an automatic causal or semantic-quality conclusion."}


def save_fresh(out, result):
    path = pathlib.Path(out)
    require(not path.exists(), "output directory already exists")
    path.mkdir(parents=True)
    dest = path / "result.json"
    dest.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    receipt = {"protocol": "foundation-file-audit-output-receipt-v1", "result": descriptor(dest), "software": descriptor(__file__),
               "actualCalls": ZERO_CALLS.copy(), "freshOutputDirectory": True}
    (path / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    a = sub.add_parser("audit")
    a.add_argument("--inputs", required=True)
    a.add_argument("--inputs-sha256", required=True)
    a.add_argument("--out", required=True)
    c = sub.add_parser("compare")
    for name in ("baseline", "candidate"):
        c.add_argument("--" + name, required=True)
        c.add_argument("--" + name + "-sha256", required=True)
    c.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    if args.command == "audit":
        value, desc = read_external(args.inputs, args.inputs_sha256)
        result = audit(value, desc)
    else:
        b, bd = read_external(args.baseline, args.baseline_sha256)
        c, cd = read_external(args.candidate, args.candidate_sha256)
        result = compare(b, c, bd, cd)
    receipt = save_fresh(args.out, result)
    print(json.dumps({"status": result["status"], "result": receipt["result"], "actualCalls": ZERO_CALLS}, ensure_ascii=True))


if __name__ == "__main__":
    try:
        main()
    except (AuditError, KeyError, TypeError, IndexError, json.JSONDecodeError, OSError, zipfile.BadZipFile, ET.ParseError) as exc:
        print(json.dumps({"status": "rejected", "error": str(exc), "actualCalls": ZERO_CALLS}), file=sys.stderr)
        sys.exit(2)
