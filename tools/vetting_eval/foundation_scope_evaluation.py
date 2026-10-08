"""Independent, file-only native range transport audit. Never imported by retrieval.

Fixtures are observations of exact native source ranges, not contract-defect gold.
This tool consumes completed actual run artifacts and makes no model/database calls.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Iterable

PROTOCOL = "source-bound-native-range-transport-audit-v1"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def canonical_digest(value):
    return sha_bytes(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(",",":")).encode("utf-8"))


def descriptor(path):
    path = Path(path).resolve()
    data = path.read_bytes()
    return {"path": str(path), "bytes": len(data), "sha256": sha_bytes(data)}


def checked(desc, *, json_document=True):
    path = Path(desc["path"]).resolve()
    data = path.read_bytes()
    require(sha_bytes(data) == desc["sha256"], f"SHA mismatch: {path}")
    require(desc.get("bytes", len(data)) == len(data), f"size mismatch: {path}")
    return json.loads(data.decode("utf-8-sig")) if json_document else data


def utf16len(text):
    return len(text.encode("utf-16-le")) // 2


def utf16slice(text, start, end):
    require(isinstance(start, int) and not isinstance(start, bool), "invalid UTF16 start")
    require(isinstance(end, int) and not isinstance(end, bool), "invalid UTF16 end")
    require(0 <= start <= end <= utf16len(text), "UTF16 range outside text")
    # Strict decode also rejects a boundary through a surrogate pair.
    return text.encode("utf-16-le")[2 * start:2 * end].decode("utf-16-le")


def merged(ranges: Iterable[tuple[int, int]]):
    result = []
    for start, end in sorted(set(ranges)):
        require(start <= end, "reversed interval")
        if start == end:
            continue
        if result and start <= result[-1][1]:
            result[-1][1] = max(end, result[-1][1])
        else:
            result.append([start, end])
    return result


def gaps(start, end, ranges):
    result, cursor = [], start
    for left, right in merged(ranges):
        left, right = max(left, start), min(right, end)
        if left >= right:
            continue
        if left > cursor:
            result.append([cursor, left])
        cursor = max(cursor, right)
    if cursor < end:
        result.append([cursor, end])
    return result


def same_source(parent, target):
    return (str(parent.get("documentId")) == str(target["documentId"])
            and parent.get("sourceHash") == target["currentDerivedSourceHash"]
            and parent.get("role") == target["businessRole"])


def validate_target(target):
    require(target.get("evaluationOnly") is True, "target must be evaluation-only")
    require(bool(target.get("currentDerivedSourceHash")), "missing source revision")
    require(target.get("requiredParts"), "empty required native ranges")
    seen = set()
    for part in target["requiredParts"]:
        key = part["blockId"], part["startOffsetUtf16"], part["endOffsetUtf16"]
        require(key not in seen, "duplicate target range")
        seen.add(key)
        start, end = key[1:]
        require(isinstance(start, int) and not isinstance(start, bool) and start >= 0, "bad target start")
        require(isinstance(end, int) and not isinstance(end, bool) and end > start, "bad target end")
        text = part["exactText"]
        require(utf16len(text) == end - start, "target UTF16 length mismatch")
        require(sha_bytes(text.encode("utf-8")) == part["textUtf8Sha256"], "target text SHA mismatch")


def parent_coverage(target, parent):
    """Verify source/block/offset and exact intersection, never quote-only matching."""
    output = [[] for _ in target["requiredParts"]]
    if not same_source(parent, target):
        return output
    for part in parent.get("parts", []):
        block, start, end = part.get("blockId"), part.get("startOffset"), part.get("endOffset")
        if block not in {p["blockId"] for p in target["requiredParts"]}:
            continue
        require(isinstance(part.get("text"), str), "matching native part lacks text")
        require(isinstance(start, int) and not isinstance(start, bool), "part start missing")
        require(isinstance(end, int) and not isinstance(end, bool) and end >= start, "part end missing")
        require(utf16len(part["text"]) == end - start, "native part UTF16 span length mismatch")
        for ordinal, expected in enumerate(target["requiredParts"]):
            if expected["blockId"] != block:
                continue
            left = max(start, expected["startOffsetUtf16"])
            right = min(end, expected["endOffsetUtf16"])
            if left >= right:
                continue
            observed = utf16slice(part["text"], left-start, right-start)
            truth = utf16slice(expected["exactText"], left-expected["startOffsetUtf16"], right-expected["startOffsetUtf16"])
            require(observed == truth, f"source range text mismatch: {target['id']} {block}")
            output[ordinal].append((left, right))
    return output


def coverage(target, parent_ids, per_parent, *, role=None, corpus_source_present=True):
    ids = list(parent_ids)
    require(len(ids) == len(set(ids)), "duplicate ranked parent IDs")
    ranges = [[] for _ in target["requiredParts"]]
    contributors = []
    for rank, parent_id in enumerate(ids, 1):
        contributions = per_parent.get(parent_id)
        if contributions and any(contributions):
            contributors.append({"id": parent_id, "rank": rank})
            for index, spans in enumerate(contributions):
                ranges[index].extend(spans)
    rows = []
    for part, spans in zip(target["requiredParts"], ranges):
        start, end = part["startOffsetUtf16"], part["endOffsetUtf16"]
        missing = gaps(start, end, spans)
        rows.append({"blockId": part["blockId"], "requiredUtf16": [start,end],
                     "requiredTextSha256": part["textUtf8Sha256"], "coveredUtf16": merged(spans),
                     "missingUtf16": missing, "complete": not missing})
    status = "covered" if all(row["complete"] for row in rows) else "missing_native_ranges"
    if role is not None and role != target["businessRole"]:
        status = "different_query_role_no_recall_expectation"
    elif not corpus_source_present:
        status = "source_revision_not_in_corpus"
    return {"status": status, "allRequiredRangesCovered": all(r["complete"] for r in rows),
            "observedParentCount": len(ids), "contributorsInObservedOrder": contributors, "ranges": rows}


def window_coverage(target, windows, parents):
    ranges = [[] for _ in target["requiredParts"]]
    contributing = []
    for window in windows:
        if not same_source(window, target):
            continue
        parent = parents.get(window["parentId"])
        require(parent is not None and same_source(parent, target), "window parent source mismatch")
        for span in window.get("sourceSpans", []):
            for index, expected in enumerate(target["requiredParts"]):
                if span["blockId"] != expected["blockId"]:
                    continue
                start, end = span["sourceStartUtf16"], span["sourceEndUtf16"]
                # Validate the complete span against its actual parent part, including its saved SHA.
                possible = [p for p in parent["parts"] if p.get("blockId") == span["blockId"]
                            and p.get("startOffset", -1) <= start <= end <= p.get("endOffset", -1)]
                texts = [utf16slice(p["text"],start-p["startOffset"],end-p["startOffset"]) for p in possible]
                require(any(sha_bytes(t.encode("utf-8")) == span["textSha256"] for t in texts), "window span SHA not bound to parent")
                left, right = max(start,expected["startOffsetUtf16"]), min(end,expected["endOffsetUtf16"])
                if left < right:
                    ranges[index].append((left,right))
                    contributing.append(window["id"])
    pseudo = {"all-indexed-windows": ranges}
    value = coverage(target,["all-indexed-windows"],pseudo,
                     corpus_source_present=any(same_source(p,target) for p in parents.values()))
    value["observedWindowCount"] = len(windows)
    value["contributingWindowIds"] = list(dict.fromkeys(contributing))
    return value


def _without_nulls(value):
    if isinstance(value,dict):
        return {key:_without_nulls(item) for key,item in value.items() if item is not None}
    if isinstance(value,list):
        return [_without_nulls(item) for item in value]
    return value


def _canonical_checks(row, parent, *, material=False):
    fields = ["id","documentId","sourceHash","role","content","sourceQualityHash",
              "metadataVersion","segmentationVersion","nativeTableMetadataVersion","sourceQualityMetadataVersion","sourceQuality"]
    if not material:
        fields.append("parts")
    for name in fields:
        observed = row.get(name)
        if material and name=="sourceQuality" and isinstance(observed,dict):
            observed=dict(observed)
            require(observed.pop("observationScope",None)=="stored_parser_declarations_only; textAccuracy_unverified; legalCoverage_unknown","material quality observation scope mismatch")
        require(_without_nulls(observed) == _without_nulls(parent.get(name)), f"canonical returned/selected payload mismatch: {name}")
    if material:
        expected = [{"partAnchor":p.get("anchor"),"blockId":p.get("blockId"),
                     "startOffset":p.get("startOffset"),"endOffset":p.get("endOffset"),
                     "extractionSource":p.get("extractionSource")} for p in parent.get("parts", [])]
        require(row.get("selectedPartExtractionObservations") == expected, "material source part observations mismatch")


def evaluate(result_desc, targets_desc, material_desc=None):
    result, fixtures = checked(result_desc), checked(targets_desc)
    require(result.get("status") == "completed", "only completed actual retrieval runs accepted")
    require(result.get("actualGenerationCalls") == 0, "this audit accepts the isolated retrieval boundary")
    require(fixtures.get("evaluatorOnly") is True and fixtures.get("semanticDefectGold") is False, "not an independent native observation fixture")
    targets = fixtures["targets"]
    require(len({t["id"] for t in targets}) == len(targets), "duplicate targets")
    for target in targets:
        validate_target(target)
    plan = checked(result["plan"])
    checked(plan["inputs"]["rawCorpus"], json_document=False)
    canonical = checked(plan["normalizedParents"])
    if isinstance(canonical, dict):
        canonical = canonical["chunks"]
    parents = {p["id"]:p for p in canonical}
    require(len(parents) == len(canonical), "duplicate corpus parent")
    metadata = checked(result["mainMetadata"])
    require(isinstance(metadata["signature"],dict),"expected structured index signature")
    require(metadata["projectId"] == result["projectId"] and canonical_digest(metadata["signature"]) == result["indexSignature"], "index/result identity mismatch")
    require(metadata["chunks"] == canonical, "index metadata parent corpus mismatch")
    windows = metadata["windows"]
    require(len({w["id"] for w in windows}) == len(windows), "duplicate window id")
    per_target = {t["id"]:{pid:parent_coverage(t,p) for pid,p in parents.items() if same_source(p,t)} for t in targets}
    corpus_scope = {t["id"]:any(same_source(p,t) for p in parents.values()) for t in targets}
    baseline = [{"targetId": t["id"], "corpus": coverage(t,list(parents),per_target[t["id"]],corpus_source_present=corpus_scope[t["id"]]),
                 "allIndexedWindowSpans":window_coverage(t,windows,parents)} for t in targets]
    manifest = checked(result["pipelineProbeManifest"])
    probe_dir = Path(result["pipelineProbeManifest"]["path"]).parent
    selected_stages = {"dense-parent-candidates","parent-bm25-candidates","parent-rrf-candidates","parent-final-ranking"}
    events, tokenizer = {}, []
    for record in manifest["events"]:
        stage = record["stage"]
        if stage not in selected_stages | {"embedding-tokenizer-input","reranker-tokenizer-input"}:
            continue
        artifact = record["artifact"]
        path = (probe_dir / artifact["relativePath"]).resolve()
        require(path.is_relative_to(probe_dir.resolve()), "probe path escapes manifest root")
        event = checked({"path":str(path),"sha256":artifact["sha256"],"bytes":artifact["bytes"]})
        require(event["stage"] == stage and event["eventOrdinal"] == record["ordinal"], "probe identity mismatch")
        ctx, payload = event["context"], event["payload"]
        if stage.endswith("tokenizer-input"):
            full, actual, lost = payload["fullTokenCounts"], payload["actualUnpaddedTokenCounts"], payload["tokensTruncated"]
            require(len(full)==len(actual)==len(lost), "tokenizer count binding mismatch")
            require(all(isinstance(x,int) and x>=0 for x in full+actual+lost), "invalid token count")
            require(all(f-a==l for f,a,l in zip(full,actual,lost)), "truncation arithmetic mismatch")
            require(all(a<=payload["effectiveModelMaxLength"] for a in actual), "model input above effective budget")
            tokenizer.append({"eventOrdinal":record["ordinal"],"stage":stage,"context":ctx,
                              "inputRows":len(full),"tokensLost":sum(lost),"rowsTruncated":sum(l>0 for l in lost),
                              "maxFullTokens":max(full,default=0),"maxActualTokens":max(actual,default=0)})
        elif ctx.get("operation")=="query" and ctx.get("scope")=="main":
            ordinal=ctx["queryOrdinal"]
            require(stage not in events.setdefault(ordinal,{}), "duplicate query stage")
            events[ordinal][stage] = event
    requests = {r["ordinal"]:r for r in plan["requests"]}
    require(len(requests)==len(plan["requests"]), "duplicate request ordinal")
    query_rows=[]
    observed=set()
    for operation in result["operations"]:
        if operation["operation"] != "query" or operation.get("scope") != "main":
            continue
        query=checked(operation["artifact"])
        request=query["request"]
        ordinal=request["ordinal"]
        require(ordinal not in observed and requests.get(ordinal)==request,"query request differs from approved plan")
        observed.add(ordinal)
        stage_events=events.get(ordinal,{})
        require(set(stage_events)==selected_stages,"missing real query ranking probe stage")
        for e in stage_events.values():
            require(e["context"]["projectId"]==result["projectId"] and e["context"]["profileId"]==request["profileId"],"query probe scope mismatch")
        hits=query["response"]["hits"]
        for hit in hits:
            require(hit["id"] in parents,"returned unknown parent")
            _canonical_checks(hit["payload"],parents[hit["id"]])
        dense=stage_events["dense-parent-candidates"]["payload"]
        lexical=stage_events["parent-bm25-candidates"]["payload"]
        rrf=stage_events["parent-rrf-candidates"]["payload"]
        final=stage_events["parent-final-ranking"]["payload"]
        stages={"denseParents":[r["id"] for r in dense["orderedParents"]],
                "lexicalCandidates":lexical["orderedCandidateIds"],
                "rrfAllFused":[r["id"] for r in rrf["allFusedInOrder"]],
                "submittedToReranker":rrf["submittedParentIds"],
                "rerankedAll":[r["id"] for r in final["allRerankedInTrueScoreOrder"]],
                "returnedToHarness":[h["id"] for h in hits]}
        require(final["returnedIds"]==stages["returnedToHarness"],"returned parent order not actual final ranking")
        require(set(stages["rerankedAll"])==set(stages["submittedToReranker"]),"reranker lost or added parent")
        for name,ids in stages.items():
            require(all(pid in parents and parents[pid]["role"]==request["request"]["role"] for pid in ids),f"stage wrong source role or unknown id: {name}")
        query_rows.append({"ordinal":ordinal,"profileId":request["profileId"],"originalTopicOrdinal":request["originalTopicOrdinal"],
                           "request":request["request"],"queryArtifact":operation["artifact"],
                           "targets":[{"targetId":t["id"],"stages":{name:coverage(t,ids,per_target[t["id"]],role=request["request"]["role"],corpus_source_present=corpus_scope[t["id"]]) for name,ids in stages.items()}} for t in targets]})
    require(observed==set(requests),"not every approved query has a completed actual artifact")
    material_rows=[]
    if material_desc:
        material_receipt=checked(material_desc)
        require(material_receipt["actualRetrievalResult"]==result_desc,"material producer bound a different actual result")
        require(material_receipt["newGenerationCalls"]==0 and material_receipt["newQueryCalls"]==0,"material must be file-only production replay")
        for group in material_receipt["groups"]:
            selection,material=checked(group["selection"]),checked(group["material"])
            require(selection["selectedIdsInSubmittedOrder"]==group["selectedIdsInSubmittedOrder"],"selection receipt order mismatch")
            final_chunks=selection["budgetResult"]["selection"]["chunks"]
            require([p["id"] for p in final_chunks]==group["selectedIdsInSubmittedOrder"],"selected chunk order mismatch")
            require([p["id"] for p in material]==group["selectedIdsInSubmittedOrder"],"material order mismatch")
            for chunk in selection["initialSelection"]["chunks"]+final_chunks:
                require(chunk["id"] in parents,"selection unknown parent")
                _canonical_checks(chunk,parents[chunk["id"]])
            for row in material:
                _canonical_checks(row,parents[row["id"]],material=True)
            stages={"initialContext":group["initialSelectedIds"],"finalContext":group["selectedIdsInSubmittedOrder"],"submittedSourceMaterial":[p["id"] for p in material]}
            material_rows.append({"groupOrdinal":group["groupOrdinal"],"profileId":group["profileId"],"sourceScope":group["sourceScope"],
                                  "originalTopicOrdinal":group["originalTopicOrdinal"],"requestOrdinalsByRole":group["requestOrdinalsByRole"],
                                  "effectiveBudgetChars":group["effectiveBudgetChars"],"selectedBodyUtf16Chars":group["selectedBodyUtf16Chars"],
                                  "targets":[{"targetId":t["id"],"stages":{name:coverage(t,ids,per_target[t["id"]],corpus_source_present=corpus_scope[t["id"]]) for name,ids in stages.items()}} for t in targets]})
    return {"protocol":PROTOCOL,"status":"completed_independent_file_only_native_range_transport_audit",
            "actualRetrievalResult":result_desc,"evaluationOnlyTargets":targets_desc,"productionMaterialReceipt":material_desc,
            "corpusParents":len(parents),"indexedWindows":len(windows),"observedQueries":len(query_rows),"observedMaterialGroups":len(material_rows),
            "corpusAndWindows":baseline,"queries":query_rows,"productionMaterial":material_rows,
            "actualTokenizerInputs":{"events":tokenizer,"tokensLost":sum(e["tokensLost"] for e in tokenizer),"rowsTruncated":sum(e["rowsTruncated"] for e in tokenizer)},
            "newModelCalls":0,"newQueryCalls":0,"newIndexCalls":0,"newGenerationCalls":0,"newHTTPCalls":0,"newApplicationDatabaseCalls":0,
            "evaluationFixtureOnlyInIndependentEvaluator":True,"semanticDefectAccuracy":None,"overallRetrievalRecall":None,
            "legalApplicability": "unknown", "acceptance": None,
            "scope":"Exact observed native ranges transported through saved actual stages. No per-query relevance or global legal-completeness labels are available. Targets absent in a query are observations, not an overall recall denominator."}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--result",required=True,type=Path)
    parser.add_argument("--result-sha256",required=True)
    parser.add_argument("--targets",required=True,type=Path)
    parser.add_argument("--targets-sha256",required=True)
    parser.add_argument("--material-receipt",type=Path)
    parser.add_argument("--material-sha256")
    parser.add_argument("--output",required=True,type=Path)
    args=parser.parse_args()
    require(bool(args.material_receipt)==bool(args.material_sha256),"material path and external SHA must be paired")
    require(not args.output.exists(),"audit output already exists")
    result_desc={**descriptor(args.result),"sha256":args.result_sha256}
    target_desc={**descriptor(args.targets),"sha256":args.targets_sha256}
    material_desc={**descriptor(args.material_receipt),"sha256":args.material_sha256} if args.material_receipt else None
    output=evaluate(result_desc,target_desc,material_desc)
    output["evaluatorSoftware"]=descriptor(__file__)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("x",encoding="utf-8",newline="\n") as stream:
        json.dump(output,stream,ensure_ascii=False,indent=2)
        stream.write("\n")
    print(json.dumps({"output":descriptor(args.output),"queries":output["observedQueries"],"materialGroups":output["observedMaterialGroups"],"tokensLost":output["actualTokenizerInputs"]["tokensLost"]},ensure_ascii=False))


if __name__ == "__main__":
    main()
