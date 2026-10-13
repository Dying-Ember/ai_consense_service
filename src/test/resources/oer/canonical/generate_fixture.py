"""Generate AUTHORED three-page wire data; no real OCR, model or original acceptance.

Run from ai_consense_service. Existing production normalizer/projector seals this
synthetic fixture. Disk descriptors are generated in a temporary artifact store;
the fixture HTTP server uses the separately included authored PNG bytes.
"""
import copy
import hashlib
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path("tools/vetting_eval").resolve()))
from artifact_store import ArtifactStore
from canonical_projection import project_canonical
from structured_parser_evidence import build_dual_parser_evidence, normalize_parser_run, read_artifact_record
from test_oer_source_registry import PNG, SOURCE_BYTES, asset_fixture

original, manifest, expected = asset_fixture()
expected["page5.png"] = PNG + b"AUTHORED-page5.png"
runs = []
with tempfile.TemporaryDirectory() as directory:
    store = ArtifactStore(Path(directory))
    for old in original["runs"]:
        artifacts = {k: read_artifact_record(v) for k, v in old["artifacts"].items()}
        artifacts["page5.png"] = expected["page5.png"]
        maps = copy.deepcopy(old["requestPageMap"])
        new = copy.deepcopy(maps[-1])
        new.update(parserPageIndex=2, physicalPageNo=5, providerInputPageNo=5, inputArtifactName="page5.png")
        transform = new["originalPageTransform"]
        transform["inputFrame"].update(artifactName="page5.png", sha256=hashlib.sha256(expected["page5.png"]).hexdigest())
        transform["targetFrame"]["physicalPageNo"] = 5
        maps.append(new)
        outputs = {k: json.loads(read_artifact_record(v)) for k, v in old["rawOutputs"].items()}
        if old["parser"] == "paddleocr-vl":
            page = copy.deepcopy(outputs["page_json"][-1])
            page["page_index"] = 2
            outputs["page_json"].append(page)
        else:
            for name in ("model_json", "middle_json"):
                page = copy.deepcopy(outputs[name]["pages"][-1])
                page["page_idx"] = 2
                outputs[name]["pages"].append(page)
            outputs["model_json"]["page_index_map"] = [2, 3, 4]
        terminal = copy.deepcopy(old["terminal"])
        terminal["pageResults"].append({"parserPageIndex": 2, "status": "completed"})
        runs.append(normalize_parser_run(old["parser"], outputs, old["source"], old["identity"], maps,
            artifacts=artifacts, required_artifacts=list(artifacts), provider_input_identity=old["providerInputIdentity"],
            terminal=terminal, artifact_store=store))
    bundle = build_dual_parser_evidence(runs, artifact_store=store)
    manifest.update(documentId="11", bundleSha256=bundle["bundleSha256"])
    manifest["scope"]["requiredPhysicalPages"] = [3, 4, 5]
    manifest["reference"]["physicalPages"] = [3, 4, 5]
    manifest["tableGroups"][0]["physicalPages"] = [3, 4, 5]
    manifest["tableGroups"][0]["candidates"] = {r["runId"]: [p["blocks"][1]["blockId"] for p in r["pages"]] for r in runs}
    manifest["units"][0].update(runId=runs[0]["runId"], blockId=runs[0]["pages"][0]["blocks"][0]["blockId"])
    manifest["units"][1]["runId"] = runs[1]["runId"]
    manifest["assets"] = [{"unitId": "table", "runId": runs[1]["runId"],
        "artifactName": "page" + str(p["physicalPageNo"]) + ".png", "physicalPages": [p["physicalPageNo"]],
        "mediaType": "image/png", "blockIds": [p["blocks"][1]["blockId"]]} for p in runs[1]["pages"]]
    projection = project_canonical(bundle, manifest, artifact_store=store)
    root = Path("src/test/resources/oer/canonical")
    (root / "authored-three-page-projection.json").write_text(json.dumps(projection, ensure_ascii=False, separators=(",", ":")), encoding="utf8")
    (root / "authored-source.pdf").write_bytes(SOURCE_BYTES)
    for name, data in expected.items():
        (root / name).write_bytes(data)
    print(json.dumps({"projectionSha256": projection["projectionSha256"], "assets": len(projection["assetRefs"]),
        "pages": projection["chunks"][1]["physicalPages"], "warning": "authored wire fixture only"}))
