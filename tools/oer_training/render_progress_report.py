"""Render an offline OER progress snapshot from an evidence-backed ledger.

This report never queries services, opens databases, reads originals or loads models.
Append a round and update the snapshot in docs/testing/oer-progress-ledger.json,
then run this script. Saved failure rounds are retained alongside successful rounds.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_LEDGER = ROOT / "docs/testing/oer-progress-ledger.json"
DEFAULT_OUTPUT = ROOT.parent / "outputs/oer-progress/index.html"


def validate(ledger: dict) -> None:
    if ledger.get("schema") != "consense.oer-progress-ledger.v1":
        raise ValueError("Unsupported progress ledger schema")
    evidence = ledger["evidence"]
    for ident, item in evidence.items():
        path = ROOT.parent / item["path"]
        if path.resolve().is_relative_to(ROOT.parent.resolve()) is False:
            raise ValueError(f"Evidence path escapes workspace: {ident}")
        if not path.is_file():
            raise ValueError(f"Missing saved evidence: {ident}: {path}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != item["sha256"]:
            raise ValueError(f"Saved evidence changed: {ident}")
    rounds = ledger["rounds"]
    ids = [r["id"] for r in rounds]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate round IDs")
    cards = ledger.get("documentScorecards", [])
    groups = rounds + ledger["sources"] + ledger["comparisons"] + ledger["milestones"] + cards
    for item in groups:
        for ident in item.get("evidence", []):
            if ident not in evidence:
                raise ValueError(f"Unknown evidence reference: {ident}")
    for comparison in ledger["comparisons"]:
        for metric in comparison["metrics"]:
            denominator = metric["denominator"]
            if denominator <= 0:
                raise ValueError("A quality ratio must have a positive denominator")
            if any(value < 0 or value > denominator for value in metric["values"]):
                raise ValueError("Quality numerator outside its denominator")
    if cards:
        catalogue = ledger["documentCatalogue"]
        inventory = json.loads((ROOT.parent / evidence[catalogue["inventoryEvidence"]]["path"]).read_text(encoding="utf-8"))
        card_ids = [card["id"] for card in cards]
        if len(card_ids) != len(set(card_ids)):
            raise ValueError("Duplicate document scorecard IDs")
        for cohort, count in catalogue["expectedCohortCounts"].items():
            actual = [card for card in cards if card["cohort"] == cohort]
            expected = [row for row in inventory["files"] if (
                row.get("fixedSetMember") if cohort == "fixed"
                else row.get("category") == "2b_Tender documents"
            )]
            expected_sources = {(row["relativePath"], row["sha256"]) for row in expected}
            actual_sources = {(card["sourceRelativePath"], card["sourceSha256"]) for card in actual}
            if len(actual) != count or len(expected) != count or actual_sources != expected_sources:
                raise ValueError(f"Incomplete or changed document catalogue: {cohort}")
        for card in cards:
            if card["cohort"] not in catalogue["expectedCohortCounts"]:
                raise ValueError("Unknown document cohort")
            if card["draftingOnly"] and card["metrics"]:
                raise ValueError("Drafting-only template cannot carry OER quality scores")
            # v1 is intentionally limited to this exposed-development snapshot.
            # Whole-document, blind or adopted scores need a separate reviewed
            # ledger contract before a later snapshot can claim those gates.
            if card["wholeDocumentAccepted"] or card["blindHoldout"]:
                raise ValueError("This snapshot has no full-document or blind acceptance evidence")
            metric_ids = [metric["id"] for metric in card["metrics"]]
            if len(metric_ids) != len(set(metric_ids)):
                raise ValueError("Duplicate document metric IDs")
            for metric in card["metrics"]:
                denominator = metric["denominator"]
                if type(denominator) is not int or denominator <= 0:
                    raise ValueError("Document quality denominator must be a positive integer")
                if not metric["scope"] or not metric["unit"] or not metric["evidence"]:
                    raise ValueError("Document metrics require a bounded scope, unit and evidence")
                if not metric["points"]:
                    raise ValueError("Document metrics require observed score points")
                for ident in metric["evidence"]:
                    if ident not in evidence:
                        raise ValueError(f"Unknown document metric evidence: {ident}")
                for point in metric["points"]:
                    numerator = point["numerator"]
                    if type(numerator) is not int or not 0 <= numerator <= denominator:
                        raise ValueError("Document quality numerator outside its denominator")
                    if point["stage"] not in {"baseline", "fit", "fullparser", "routing", "native", "diagnostic"}:
                        raise ValueError("Unknown document evaluation stage")
                    if point["adopted"] or type(point["weightUpdates"]) is not int or point["weightUpdates"] < 0:
                        raise ValueError("Unproven adoption or invalid update count in document score")
                    if metric["kind"] in {"routing", "native"} and point["weightUpdates"] != 0:
                        raise ValueError("Routing/native quality cannot be relabelled as weight training")
    if not ledger["snapshot"]["staticSnapshot"]:
        raise ValueError("This renderer only supports explicitly dated static snapshots")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", type=Path, default=DEFAULT_LEDGER)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    ledger = json.loads(args.ledger.read_text(encoding="utf-8"))
    validate(ledger)
    template = Path(__file__).with_name("progress-report-template.html").read_text(encoding="utf-8")
    marker = "<!-- OER_PROGRESS_DATA -->"
    if template.count(marker) != 1:
        raise ValueError("Template must have exactly one data marker")
    # Protect the script element even if a future source label contains HTML.
    serialized = json.dumps(ledger, ensure_ascii=False).replace("<", "\\u003c")
    rendered = template.replace(marker, f'<script id="progress-data" type="application/json">{serialized}</script>')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    args.output.with_name("progress-ledger.json").write_text(
        json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"output": str(args.output), "bytes": args.output.stat().st_size,
                      "rounds": len(ledger["rounds"]), "evidenceFilesVerified": len(ledger["evidence"]),
                      "snapshotAt": ledger["snapshot"]["asOf"],
                      "documentScorecards": len(ledger.get("documentScorecards", [])),
                      "documentQualityMetrics": sum(len(card["metrics"]) for card in ledger.get("documentScorecards", [])),
                      "liveQueries": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
