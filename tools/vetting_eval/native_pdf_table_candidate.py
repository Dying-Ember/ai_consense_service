"""Independent full-source native table proposals, never active publication.

This isolated entry reads only its source manifest, listed complete-unit PDFs,
its own code, and installed-library metadata. It never opens reference answers.
No PDF is modified and no table region/column/page-specific rule is accepted.
"""
from __future__ import annotations

import argparse
import contextlib
import dataclasses
import hashlib
import importlib.metadata
import inspect
import io
import json
import logging
import math
import os
from pathlib import Path
import platform
import sys
import tempfile
import time
import traceback
import warnings
from datetime import datetime, timezone


from native_pdf_table_logic import recover_page

SCHEMA = "consense.native-pdf-table-capture.v1"
FRAME = "PDF_points_crop_top_left"
METHODS = {
    "pdfplumber_lines_lines": {"provider": "pdfplumber", "settings": {
        "vertical_strategy": "lines", "horizontal_strategy": "lines"}},
    "camelot_network": {"provider": "camelot", "flavor": "network"},
    "camelot_stream": {"provider": "camelot", "flavor": "stream"},
}


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def file_identity(path):
    path = Path(path).resolve(strict=True)
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return {"path": str(path), "bytes": path.stat().st_size,
            "sha256": digest.hexdigest()}


def safe(value):
    """Retain non-JSON objects visibly rather than silently dropping fields."""
    if value is None or isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else {"nonFiniteFloat": repr(value)}
    if isinstance(value, dict):
        return {str(key): safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [safe(item) for item in value]
    if dataclasses.is_dataclass(value):
        return safe(dataclasses.asdict(value))
    if hasattr(value, "item"):
        try:
            return safe(value.item())
        except (TypeError, ValueError):
            pass
    if isinstance(value, bytes):
        return {"type": "bytes", "hex": value.hex()}
    return {"type": type(value).__name__, "repr": repr(value)}


def write_json(path, value):
    with Path(path).open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(safe(value), handle, ensure_ascii=False, indent=2, allow_nan=False)
        handle.write("\n")


def write_line(handle, value):
    handle.write(json.dumps(safe(value), ensure_ascii=False, allow_nan=False) + "\n")
    handle.flush()


def text_state(text):
    if text is None:
        return "none"
    if text == "":
        return "empty_string"
    if isinstance(text, str) and text.isspace():
        return "whitespace_only"
    return "nonempty"


def bbox_transform(bbox, point_transform):
    if bbox is None:
        return None
    x0, y0, x1, y1 = map(float, bbox)
    points = [point_transform(x, y) for x, y in
              ((x0, y0), (x0, y1), (x1, y0), (x1, y1))]
    return [min(p[0] for p in points), min(p[1] for p in points),
            max(p[0] for p in points), max(p[1] for p in points)]


def matrix_apply(matrix, x, y):
    a, b, c, d, e, f = matrix
    return a * x + c * y + e, b * x + d * y + f


def matrix_inverse(matrix):
    a, b, c, d, e, f = matrix
    determinant = a * d - b * c
    if determinant == 0:
        raise ValueError("Coordinate matrix is singular")
    return (d / determinant, -b / determinant, -c / determinant,
            a / determinant, (c * f - d * e) / determinant,
            (b * e - a * f) / determinant)


def media_matrix(media, rotation, screen=False):
    """PLAyA page CTM, matching installed playa/page.py set_initial_ctm.

    The first rotation is followed by normalization to the rotated MediaBox.
    This supports nonzero MediaBox origins and Camelot's auto de-rotation.
    """
    x0, y0, x1, y1 = media
    width, height = x1 - x0, y1 - y0
    rotation %= 360
    matrices = {0: (1, 0, 0, 1, 0, 0),
                90: (0, -1, 1, 0, 0, width),
                180: (-1, 0, 0, -1, width, height),
                270: (0, 1, -1, 0, height, 0)}
    if rotation not in matrices:
        raise ValueError(f"Unsupported PDF rotation: {rotation}")
    matrix = matrices[rotation]
    bounds = bbox_transform(media, lambda x, y: matrix_apply(matrix, x, y))
    a, b, c, d, e, f = matrix
    if screen:
        return (a, -b, c, -d, e - bounds[0], bounds[3] - f)
    return (a, b, c, d, e - bounds[0], f - bounds[1])


def glyph_links(bbox, glyphs):
    """Geometric observations; these are not claimed as native text assignment."""
    if bbox is None:
        return {"glyphIds": [], "glyphOverlapIds": [],
                "glyphAssociation": "unavailable"}
    x0, y0, x1, y1 = bbox
    centers, overlaps = [], []
    for glyph in glyphs:
        gx0, gy0, gx1, gy1 = glyph["bbox"]
        mx, my = (gx0 + gx1) / 2, (gy0 + gy1) / 2
        if x0 <= mx < x1 and y0 <= my < y1:
            centers.append(glyph["glyphId"])
        if min(x1, gx1) > max(x0, gx0) and min(y1, gy1) > max(y0, gy0):
            overlaps.append(glyph["glyphId"])
    return {"glyphIds": centers, "glyphOverlapIds": overlaps,
            "glyphAssociation": "pdfplumber_source_geometry_only",
            "glyphAssignmentKnown": False}


def record_origin(row, col, row_span, col_span, text, bbox, raw_bbox,
                  glyphs, **extra):
    return {"row": row, "col": col, "rowSpan": row_span,
            "colSpan": col_span, "text": text, "textState": text_state(text),
            "bbox": bbox, "rawBbox": raw_bbox,
            **glyph_links(bbox, glyphs), **extra}


@contextlib.contextmanager
def capture():
    stdout, stderr, log = io.StringIO(), io.StringIO(), io.StringIO()
    handler = logging.StreamHandler(log)
    handler.setFormatter(logging.Formatter("%(name)s %(levelname)s %(message)s"))
    logging.getLogger().addHandler(handler)
    started = time.perf_counter()
    observed = {"startedAtUtc": utc_now()}
    try:
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr), \
                warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            yield observed
    finally:
        logging.getLogger().removeHandler(handler)
        observed.update({"endedAtUtc": utc_now(),
                         "elapsedSeconds": time.perf_counter() - started,
                         "stdout": stdout.getvalue(), "stderr": stderr.getvalue(),
                         "logging": log.getvalue(),
                         "warnings": [{"category": w.category.__name__,
                                       "message": str(w.message),
                                       "filename": w.filename, "line": w.lineno}
                                      for w in caught]})


def exception_record(error):
    return {"type": type(error).__name__, "message": str(error),
            "traceback": traceback.format_exc()}


def page_identity(unit, local_page, geometry):
    return {"schema": SCHEMA, "sectionId": unit["sectionId"],
            "sourceKey": unit["sectionId"], "localPage": local_page,
            "originalPage": unit["originalPages"][local_page - 1],
            "sourceSha256": unit["originalCompleteUnitPdf"]["sha256"],
            "inputPdf": unit["originalCompleteUnitPdf"]["path"],
            "coordinateSpace": FRAME, **geometry}


def observe_source_page(page):
    crop, media = list(map(float, page.cropbox)), list(map(float, page.mediabox))
    transform = lambda x, y: (x - crop[0], y - crop[1])
    geometry = {"pageWidth": crop[2] - crop[0], "pageHeight": crop[3] - crop[1],
                "rotation": page.rotation,
                "pdfplumberPageWidth": page.width, "pdfplumberPageHeight": page.height,
                "pdfplumberMediaBox": media, "pdfplumberCropBox": crop,
                "pdfplumberPageBbox": list(page.bbox),
                "originalPdfMediaBox": list(map(float, page.page_obj.mediabox)),
                "originalPdfCropBox": list(map(float, page.page_obj.cropbox or page.page_obj.mediabox)),
                "originalPdfRotate": int(page.page_obj.rotate or 0),
                "pdfUserUnit": page.page_obj.attrs.get("UserUnit", 1),
                "sourceCoordinateTransform": {
                    "from": "pdfplumber_rotated_media_top_left",
                    "to": FRAME, "translation": [-crop[0], -crop[1]],
                    "inputCroppingPerformed": False}}
    glyphs = []
    for index, char in enumerate(page.chars):
        bbox = [char["x0"], char["top"], char["x1"], char["bottom"]]
        glyphs.append({"glyphId": index, "text": char.get("text"),
                       "bbox": bbox_transform(bbox, transform),
                       "rawBbox": bbox, "raw": char})
    graphics = {name: getattr(page, name) for name in ("lines", "rects", "curves", "images")}
    return geometry, glyphs, {"glyphs": glyphs, "graphics": graphics,
                              "sourceCharsAreNativeTextLayerObservations": True,
                              "sourceTextLayerQualityAccepted": False}


def extract_pdfplumber(page, method, glyphs, geometry):
    settings = METHODS[method]["settings"]
    finder = page.debug_tablefinder(table_settings=settings)
    crop = geometry["pdfplumberCropBox"]
    transform = lambda x, y: (x - crop[0], y - crop[1])
    output = []
    for number, table in enumerate(finder.tables):
        native_grid, extract_error = None, None
        try:
            native_grid = table.extract(**finder.settings.text_settings)
        except Exception as error:
            extract_error = exception_record(error)
        xs = sorted({float(cell[i]) for cell in table.cells for i in (0, 2)})
        ys = sorted({float(cell[i]) for cell in table.cells for i in (1, 3)})
        origins, raw_slots = [], []
        observed_bboxes = set()
        for raw_row, row in enumerate(table.rows):
            for raw_col, cell in enumerate(row.cells):
                text = native_grid[raw_row][raw_col] if native_grid is not None else None
                raw_slots.append({"row": raw_row, "col": raw_col, "bbox": cell,
                                  "text": text, "textState": text_state(text),
                                  "nativeCellPresent": cell is not None})
                if cell is None:
                    continue
                box = tuple(map(float, cell))
                if box in observed_bboxes:
                    raise ValueError("pdfplumber returned duplicate cell bbox")
                observed_bboxes.add(box)
                col, row_id = xs.index(box[0]), ys.index(box[1])
                col_span, row_span = xs.index(box[2]) - col, ys.index(box[3]) - row_id
                origins.append(record_origin(row_id, col, row_span, col_span,
                    text, bbox_transform(box, transform), list(box), glyphs,
                    spanKnown=True, spanObservation="cell_bbox_over_all_native_grid_edges",
                    providerRow=raw_row, providerCol=raw_col))
        output.append({"tableId": f"{method}:table:{number}",
                       "bbox": bbox_transform(table.bbox, transform),
                       "rawBbox": table.bbox, "rowCount": max(0, len(ys) - 1),
                       "colCount": max(0, len(xs) - 1), "origins": origins,
                       "gridXEdges": [x - crop[0] for x in xs],
                       "gridYEdges": [y - crop[1] for y in ys],
                       "rawGrid": native_grid, "rawSlots": raw_slots,
                       "extractionError": extract_error,
                       "rawCoordinateSpace": "pdfplumber_rotated_media_top_left",
                       "coordinateTransform": geometry["sourceCoordinateTransform"],
                       "textTransformByRunner": "none",
                       "completeTableGroupAccepted": False})
    return output, {"effectiveTableSettings": safe(finder.settings),
                    "edges": finder.edges, "intersections": finder.intersections,
                    "cells": finder.cells,
                    "diagnosticsAreNotSourceTruth": True}


def native_textlines(table, transform):
    lines = []
    for line_index, line in enumerate(getattr(table, "textlines", [])):
        raw_bbox = getattr(line, "bbox", None)
        chars = []
        for child_index, child in enumerate(line):
            box = getattr(child, "bbox", None)
            chars.append({"childIndex": child_index, "type": type(child).__name__,
                          "text": child.get_text() if hasattr(child, "get_text") else None,
                          "rawBbox": box,
                          "bbox": bbox_transform(box, transform) if box is not None else None,
                          "fontname": getattr(child, "fontname", None),
                          "size": getattr(child, "size", None),
                          "matrix": getattr(child, "matrix", None)})
        lines.append({"lineIndex": line_index, "type": type(line).__name__,
                      "text": line.get_text(), "rawBbox": raw_bbox,
                      "bbox": bbox_transform(raw_bbox, transform), "children": chars})
    return lines


def extract_camelot(pdf_path, local_page, method, glyphs, geometry):
    import camelot
    flavor = METHODS[method]["flavor"]
    # No kwargs beyond the fixed flavor and exhaustive page enumeration.
    tables = camelot.read_pdf(str(pdf_path), pages=str(local_page), flavor=flavor)
    output = []
    for number, table in enumerate(tables):
        original_rotation = geometry["originalPdfRotate"] % 360
        auto_rotation = getattr(table, "rotation", "")
        delta = {"": 0, "clockwise": -90, "anticlockwise": 90}.get(auto_rotation)
        if delta is None:
            raise ValueError(f"Unrecognized Camelot auto rotation: {auto_rotation!r}")
        parser_rotation = (original_rotation + delta) % 360
        media = geometry["originalPdfMediaBox"]
        parser_matrix = media_matrix(media, parser_rotation)
        inverse = matrix_inverse(parser_matrix)
        display_matrix = media_matrix(media, original_rotation, screen=True)
        pp_media, pp_crop = geometry["pdfplumberMediaBox"], geometry["pdfplumberCropBox"]
        offset = [pp_media[0] - pp_crop[0], pp_media[1] - pp_crop[1]]
        def transform(x, y):
            raw_x, raw_y = matrix_apply(inverse, x, y)
            tx, ty = matrix_apply(display_matrix, raw_x, raw_y)
            return tx + offset[0], ty + offset[1]
        transform_description = {"from": "camelot_playa_rotated_media_bottom_left",
            "to": FRAME, "originalPdfRotation": original_rotation,
            "camelotAutoRotation": auto_rotation, "parserRotation": parser_rotation,
            "parserMatrix": parser_matrix, "inverseParserMatrix": inverse,
            "originalDisplayMatrix": display_matrix, "displayToCropTranslation": offset,
            "parserPdfSize": getattr(table, "pdf_size", None),
            "inputCroppingPerformed": False}
        origins, raw_grid = [], []
        for row_index, row in enumerate(table.cells):
            raw_row = []
            for col_index, cell in enumerate(row):
                raw_box = [cell.x1, cell.y1, cell.x2, cell.y2]
                box = bbox_transform(raw_box, transform)
                span_known = not (cell.hspan or cell.vspan)
                origins.append(record_origin(row_index, col_index, 1, 1,
                    cell.text, box, raw_box, glyphs, spanKnown=span_known,
                    spanObservation="unit_native_cell; merge_owner_not_exposed",
                    nativeHspan=bool(cell.hspan), nativeVspan=bool(cell.vspan),
                    nativeEdges={key: bool(getattr(cell, key)) for key in
                                 ("left", "right", "top", "bottom")}))
                raw_row.append(cell.text)
            raw_grid.append(raw_row)
        output.append({"tableId": f"{method}:table:{number}",
                       "bbox": bbox_transform(table._bbox, transform),
                       "rawBbox": table._bbox, "rowCount": len(table.cells),
                       "colCount": max((len(row) for row in table.cells), default=0),
                       "origins": origins, "rawGrid": raw_grid,
                       "providerGrid": table.data,
                       "providerGridTextTransform": "Camelot Table.data applies cell.text.strip()",
                       "rawRows": table.rows, "rawCols": table.cols,
                       "rawTextBoxes": getattr(table, "_text", None),
                       "nativeTextLines": native_textlines(table, transform),
                       "rawParse": safe(getattr(table, "parse", None)),
                       "rawParseDetails": safe(getattr(table, "parse_details", None)),
                       "diagnostics": {"parsingReport": table.parsing_report,
                                       "confidence": getattr(table, "confidence", None),
                                       "diagnosticsAreNotSourceTruth": True},
                       "rawCoordinateSpace": "camelot_playa_rotated_media_bottom_left",
                       "coordinateTransform": transform_description,
                       "nativeFlavor": table.flavor, "nativePage": table.page,
                       "nativeOrder": table.order, "textTransformByRunner": "none",
                       "completeTableGroupAccepted": False})
    return output, {"call": {"api": "camelot.read_pdf", "completePdfPath": str(pdf_path),
                             "pages": str(local_page), "flavor": flavor,
                             "otherKwargs": {}},
                    "diagnosticsAreNotSourceTruth": True}


def installed_versions():
    return {d.metadata["Name"]: d.version for d in importlib.metadata.distributions()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    # Reusing a result directory would blend two runs; never overwrite evidence.
    output.mkdir(parents=True, exist_ok=False)
    temporary = output / "tmp"
    temporary.mkdir()
    tempfile.tempdir = str(temporary)
    os.environ["TMP"] = str(temporary)
    os.environ["TEMP"] = str(temporary)
    started = time.perf_counter()
    terminal = {"schema": SCHEMA, "startedAtUtc": utc_now(), "status": "running",
                "sourceQualityAccepted": False, "baselineAdopted": False,
                "goldFilesRead": 0, "gpuCalls": 0, "methods": {},
                "sourceErrors": [], "sourceChecks": []}
    source_file = file_identity(args.sources)
    source_manifest = json.loads(args.sources.read_text(encoding="utf-8-sig"))
    units = source_manifest["units"]
    if not units or any(len(u["originalPages"]) != u["pageCount"] for u in units):
        raise ValueError("Source manifest page mappings are inconsistent")
    expected = sum(u["pageCount"] for u in units)
    if expected != source_manifest["completePageCount"]:
        raise ValueError("Complete manifest page total is inconsistent")
    for unit in units:
        actual = file_identity(unit["originalCompleteUnitPdf"]["path"])
        expected_identity = unit["originalCompleteUnitPdf"]
        if any(actual[key] != expected_identity[key] for key in ("bytes", "sha256")):
            raise ValueError(f"Source identity mismatch: {unit['sectionId']}")
        terminal["sourceChecks"].append({"sectionId": unit["sectionId"],
                                         "before": actual})
    import pdfplumber
    import camelot
    from pdfplumber.table import TableSettings
    from camelot.parsers import Stream, Network
    recipe = {"schema": SCHEMA, "createdAtUtc": utc_now(), "device": "CPU",
              "sources": source_file, "runner": file_identity(__file__),
              "versions": installed_versions(), "python": sys.version,
              "platform": platform.platform(), "executable": sys.executable,
              "methods": METHODS, "methodSelectionPolicy": "none; save all outputs",
              "candidateFusion": False, "normalizationByRunner": "none",
              "pdfplumberDefaultSettings": safe(TableSettings.resolve({})),
              "camelotParserSignatures": {c.__name__: str(inspect.signature(c))
                                           for c in (Stream, Network)},
              "camelotReadPdfSignature": str(inspect.signature(camelot.read_pdf)),
              "coordinateSpace": FRAME, "completePageCount": expected,
              "sourceScope": "every page in every complete manifest unit",
              "tableAreas": None, "columns": None, "sourcePdfsReadOnly": True,
              "mlEnabled": False, "modelDownloads": False,
              "oldCandidatesRead": False, "referenceAnswersRead": False,
              "logicalRecipe": file_identity(Path(__file__).with_name("native_pdf_table_logic.py")),
              "conditionRecipe": file_identity(Path(__file__).with_name("native_pdf_table_conditions.py")),
              "projectionContract": "source-reviewed whole table groups only; candidates cannot self-adopt",
              "implementationOrigin": "source-preserving observer reused from isolated run-2112-01"}
    code_directory = output / "source-code"
    code_directory.mkdir()
    recipe["retainedRecipeCode"] = {}
    for name in (Path(__file__).name, "native_pdf_table_logic.py", "native_pdf_table_conditions.py"):
        retained = code_directory / name
        retained.write_bytes(Path(__file__).with_name(name).read_bytes())
        recipe["retainedRecipeCode"][name] = file_identity(retained)
    write_json(output / "run-recipe.json", recipe)
    method_files = {method: (output / f"{method}.pages.jsonl").open(
        "x", encoding="utf-8", newline="\n") for method in METHODS}
    source_observations = (output / "source-observations.pages.jsonl").open(
        "x", encoding="utf-8", newline="\n")
    terminal["expectedPagesPerMethod"] = expected
    terminal["methods"] = {method: {"visitedPages": 0, "succeededPages": 0,
                                    "failedPages": 0, "tableCount": 0,
                                    "elapsedSeconds": 0.0} for method in METHODS}
    try:
        for unit in units:
            pdf_path = Path(unit["originalCompleteUnitPdf"]["path"])
            with pdfplumber.open(pdf_path) as document:
                if len(document.pages) != unit["pageCount"]:
                    raise ValueError(f"PDF page count mismatch: {unit['sectionId']}")
                for local_page, page in enumerate(document.pages, 1):
                    observation_error, glyphs, geometry, observations = None, [], {}, {}
                    with capture() as source_execution:
                        try:
                            geometry, glyphs, observations = observe_source_page(page)
                        except Exception as error:
                            observation_error = exception_record(error)
                    base = page_identity(unit, local_page, geometry)
                    write_line(source_observations, {**base, **observations,
                               "execution": source_execution, "error": observation_error,
                               "status": "failed" if observation_error else "succeeded"})
                    if observation_error:
                        terminal["sourceErrors"].append({"sectionId": unit["sectionId"],
                                                       "localPage": local_page,
                                                       "error": observation_error})
                    for method, specification in METHODS.items():
                        tables, diagnostics, error_record = [], {}, None
                        with capture() as execution:
                            try:
                                if observation_error:
                                    raise RuntimeError("Source glyph/coordinate observation failed")
                                if specification["provider"] == "pdfplumber":
                                    tables, diagnostics = extract_pdfplumber(page, method, glyphs, geometry)
                                else:
                                    tables, diagnostics = extract_camelot(pdf_path, local_page, method, glyphs, geometry)
                                table_errors = [t["extractionError"] for t in tables
                                                if t.get("extractionError") is not None]
                                if table_errors:
                                    error_record = {"type": "TableExtractionErrors",
                                        "message": "Detected table geometry retained; one or more table text extractions failed",
                                        "tables": table_errors}
                            except Exception as error:
                                error_record = exception_record(error)
                        for table in tables:
                            table["tableId"] = f"{unit['sectionId']}:{local_page}:{table['tableId']}"
                        logical = None
                        if not error_record:
                            try:
                                logical = recover_page(tables, observations)
                            except Exception as error:
                                error_record = exception_record(error)
                        result = {**base, "method": method, "tables": tables, "logicalCandidate": logical,
                                  "tableCount": len(tables), "diagnostics": diagnostics,
                                  "status": "failed" if error_record else "succeeded",
                                  "execution": execution, "error": error_record,
                                  "sourceObservationRef": {"file": "source-observations.pages.jsonl",
                                      "sectionId": unit["sectionId"], "localPage": local_page},
                                  "completeTableGroupAccepted": False}
                        write_line(method_files[method], result)
                        summary = terminal["methods"][method]
                        summary["visitedPages"] += 1
                        summary["failedPages" if error_record else "succeededPages"] += 1
                        summary["tableCount"] += len(tables)
                        summary["elapsedSeconds"] += execution["elapsedSeconds"]
                        print(json.dumps({"sectionId": unit["sectionId"], "localPage": local_page,
                                          "originalPage": base["originalPage"], "method": method,
                                          "status": result["status"], "tables": len(tables),
                                          "seconds": execution["elapsedSeconds"]}), flush=True)
                    page.close()
        for check, unit in zip(terminal["sourceChecks"], units):
            check["after"] = file_identity(unit["originalCompleteUnitPdf"]["path"])
            check["unchanged"] = check["before"] == check["after"]
            if not check["unchanged"]:
                raise ValueError(f"Source changed during run: {unit['sectionId']}")
        terminal["status"] = "completed_with_errors" if any(
            s["failedPages"] for s in terminal["methods"].values()) else "completed"
    except BaseException as error:
        terminal["status"] = "failed"
        terminal["fatalError"] = exception_record(error)
    finally:
        for handle in method_files.values():
            handle.close()
        source_observations.close()
        terminal["endedAtUtc"] = utc_now()
        terminal["elapsedSeconds"] = time.perf_counter() - started
        terminal["outputs"] = [file_identity(path) for path in sorted(output.glob("*.jsonl"))]
        terminal["runRecipe"] = file_identity(output / "run-recipe.json")
        write_json(output / "terminal.json", terminal)
        print(json.dumps({"terminalStatus": terminal["status"], "output": str(output),
                          "elapsedSeconds": terminal["elapsedSeconds"]}), flush=True)
    return 0 if terminal["status"] == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
