"""Separate literal OCR scores and same-pool retrieval metrics, offline only."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re

try:
    from .dataset import read_json, sha256, validate_manifest, write_new
except ImportError:
    from dataset import read_json, sha256, validate_manifest, write_new


def edit_distance(left, right):
    previous = list(range(len(right) + 1))
    for index, value in enumerate(left, 1):
        current = [index]
        for other, expected in enumerate(right, 1):
            current.append(min(previous[other] + 1, current[-1] + 1,
                               previous[other - 1] + (value != expected)))
        previous = current
    return previous[-1]


def literal_score(target, output):
    if not isinstance(output, str):
        raise ValueError("OCR output must be the complete string")
    return {"rawExact": target == output,
            "whitespaceOnlyExact": "".join(target.split()) == "".join(output.split()),
            "referenceCharacters": len(target), "outputCharacters": len(output),
            "characterEditDistance": edit_distance(target, output),
            "characterErrorRate": edit_distance(target, output) / len(target) if target else None,
            "digitSequencesExact": re.findall(r"\d+", target) == re.findall(r"\d+", output),
            "semanticQuality": "not_scored_by_literal_metrics"}


def pool_digest(ids):
    return hashlib.sha256(json.dumps(sorted(ids), separators=(",", ":")).encode()).hexdigest()


def retrieval_score(rows, *, k):
    result = []
    for row in rows:
        pool, embedding, rerank = row["candidatePool"], row["embeddingOrder"], row["rerankOrder"]
        if len(pool) != len(set(pool)) or set(pool) != set(embedding) or set(pool) != set(rerank):
            raise ValueError("Rerank must evaluate the same unique complete candidate pool as embedding")
        if len(embedding) != len(pool) or len(rerank) != len(pool):
            raise ValueError("Duplicate/missing candidates cannot be hidden by set equality")
        if row.get("candidatePoolSha256") != pool_digest(pool):
            raise ValueError("Frozen candidate pool identity changed")
        judgments = row["sourceOnlyRelevanceGrades"]
        if set(judgments) != set(pool) or any(type(x) is not int or x < 0 or x > 3 for x in judgments.values()):
            raise ValueError("Every same-pool candidate needs source-first graded relevance")
        relevant_total = row["independentlyAnnotatedRelevantCorpusCount"]
        relevant_pool = sum(v > 0 for v in judgments.values())
        if type(relevant_total) is not int or relevant_total < relevant_pool or relevant_total <= 0:
            raise ValueError("Corpus recall needs an independent complete relevant denominator")
        def metrics(order):
            top = order[:k]
            ideal = sorted(judgments, key=lambda x: judgments[x], reverse=True)[:k]
            def dcg(values):
                return sum((2 ** judgments[v] - 1) / math.log2(i + 2) for i, v in enumerate(values))
            normalizer = dcg(ideal)
            return {"recallAtK": sum(judgments[v] > 0 for v in top) / relevant_total,
                    "reciprocalRank": next((1 / (i + 1) for i, v in enumerate(order) if judgments[v] > 0), 0),
                    "samePoolNdcgAtK": dcg(top) / normalizer if normalizer else 0}
        result.append({"queryId": row["queryId"], "candidatePoolSha256": pool_digest(pool),
                       "candidatePoolRelevantRecall": relevant_pool / relevant_total,
                       "embedding": metrics(embedding), "rerank": metrics(rerank),
                       "split": row["split"], "exposure": row["exposure"]})
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest", type=Path)
    p.add_argument("--ocr-outputs", type=Path)
    p.add_argument("--retrieval-results", type=Path)
    p.add_argument("--k", type=int, default=5)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    report = {"schema": "consense.oer-multistage-quality.v1", "qualityImprovementClaim": False}
    if args.ocr_outputs:
        if not args.manifest:
            p.error("--ocr-outputs requires --manifest")
        _, records, inventory = validate_manifest(args.manifest)
        data = read_json(args.ocr_outputs)
        outputs = data["outputs"]
        if data.get("manifestSha256") != inventory["manifestSha256"]:
            raise ValueError("Outputs must bind the exact source/target manifest")
        if len(outputs) != len({x["sampleId"] for x in outputs}):
            raise ValueError("Duplicate OCR output")
        by_id = {x["sampleId"]: x for x in outputs}
        if set(by_id) != {x["record"]["sampleId"] for x in records}:
            raise ValueError("Missing/extra outputs cannot disappear from the denominator")
        report["ocr"] = [{"sampleId": x["record"]["sampleId"], "task": x["record"]["task"],
                           "split": x["source"]["partition"], "exposure": x["source"]["exposure"],
                           "finishReason": by_id[x["record"]["sampleId"]]["finishReason"],
                           **literal_score(x["targetText"], by_id[x["record"]["sampleId"]]["text"])} for x in records]
        report["manifestSha256"] = inventory["manifestSha256"]
    if args.retrieval_results:
        if args.k <= 0:
            raise ValueError("k must be positive")
        report["retrieval"] = retrieval_score(read_json(args.retrieval_results)["queries"], k=args.k)
    if "ocr" not in report and "retrieval" not in report:
        p.error("At least one actual result file is required")
    write_new(args.output, report)
    print(json.dumps({"output": str(args.output), "ocrCount": len(report.get("ocr", [])),
                      "retrievalCount": len(report.get("retrieval", []))}))


if __name__ == "__main__":
    main()
