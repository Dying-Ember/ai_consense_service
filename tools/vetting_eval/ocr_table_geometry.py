"""Recover candidate cells from ruled raster tables and existing OCR polygons.

This module reads no document labels or expected text. A raster cell remains an
unverified candidate; an empty OCR assignment does not establish a blank cell.
"""
import math

ALGORITHM_VERSION = 'ruled-raster-cell-candidates-v1'


def _cluster(values, tolerance):
    groups = []
    for value in sorted(values):
        if groups and abs(value - sum(groups[-1]) / len(groups[-1])) <= tolerance:
            groups[-1].append(value)
        else:
            groups.append([value])
    return [sum(group) / len(group) for group in groups]


def _bbox(polygon):
    if not isinstance(polygon, (list, tuple)) or len(polygon) < 3:
        raise ValueError('An OCR line needs a polygon in input-image coordinates')
    points = [(float(p[0]), float(p[1])) for p in polygon]
    if any(not math.isfinite(v) for point in points for v in point):
        raise ValueError('OCR coordinates must be finite')
    return [min(p[0] for p in points), min(p[1] for p in points),
            max(p[0] for p in points), max(p[1] for p in points)]


def _overlap(first, second):
    return max(0, min(first[2], second[2]) - max(first[0], second[0])) * max(
        0, min(first[3], second[3]) - max(first[1], second[1]))


def _neighbors(first, second, tolerance):
    # Shared raster boundaries connect actual cells, not arbitrary nearby text.
    x_overlap = min(first[2], second[2]) - max(first[0], second[0])
    y_overlap = min(first[3], second[3]) - max(first[1], second[1])
    return ((abs(first[2] - second[0]) <= tolerance or abs(second[2] - first[0]) <= tolerance)
            and y_overlap > tolerance) or ((abs(first[3] - second[1]) <= tolerance or
            abs(second[3] - first[1]) <= tolerance) and x_overlap > tolerance)


def recover_ruled_tables(image, lines, *, horizontal_kernel_fraction=0.02,
                         vertical_kernel_fraction=0.02, boundary_tolerance=6,
                         minimum_cell_width=35, minimum_cell_height=14):
    """Return geometrical candidate tables without modifying OCR text.

    ``image`` is the exact raster submitted for recognition; every ``polygon``
    must use its coordinates. Rotation/crop provenance belongs to the caller.
    Unruled tables, broken grids and semantic headers are outside this parser.
    """
    import cv2
    import numpy as np
    if not 0 < horizontal_kernel_fraction < 1 or not 0 < vertical_kernel_fraction < 1:
        raise ValueError('Morphology kernel fractions must be between zero and one')
    if boundary_tolerance < 0 or minimum_cell_width < 1 or minimum_cell_height < 1:
        raise ValueError('Invalid raster-cell geometry settings')
    raster = np.asarray(image)
    if raster.ndim == 3:
        gray = cv2.cvtColor(raster, cv2.COLOR_RGB2GRAY)
    elif raster.ndim == 2:
        gray = raster
    else:
        raise ValueError('A table raster must be a grayscale or RGB image')
    height, width = gray.shape
    if width * height > 40_000_000:
        raise ValueError('Raster exceeds 40 million pixels')
    normalized = []
    for ordinal, line in enumerate(lines):
        polygon = line.get('polygon')
        box = _bbox(polygon)
        if box[0] < 0 or box[1] < 0 or box[2] > width or box[3] > height:
            raise ValueError('OCR polygon is outside the submitted raster')
        normalized.append({'id': line.get('id', f'line-{ordinal}'), 'text': str(line['text']),
                           'polygon': polygon, 'box': box, 'confidence': line.get('confidence')})
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    horizontal_length = max(15, round(width * horizontal_kernel_fraction))
    vertical_length = max(15, round(height * vertical_kernel_fraction))
    horizontal = cv2.morphologyEx(binary, cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (horizontal_length, 1)))
    vertical = cv2.morphologyEx(binary, cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (1, vertical_length)))
    grid = cv2.bitwise_or(horizontal, vertical)
    contours, hierarchy = cv2.findContours(grid, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
    rects = []
    if hierarchy is not None:
        for contour, tree in zip(contours, hierarchy[0]):
            if tree[3] < 0:  # A cell is a closed hole inside a raster grid.
                continue
            x, y, w, h = cv2.boundingRect(contour)
            if w < minimum_cell_width or h < minimum_cell_height:
                continue
            if cv2.contourArea(contour) / (w * h) < 0.90:
                continue
            rects.append([x, y, x + w, y + h])
    remaining = set(range(len(rects))); components = []
    while remaining:
        seed = remaining.pop(); component = [seed]; pending = [seed]
        while pending:
            current = pending.pop()
            connected = [n for n in remaining if _neighbors(rects[current], rects[n], boundary_tolerance)]
            for n in connected:
                remaining.remove(n); pending.append(n); component.append(n)
        components.append(component)
    tables = []
    for component in components:
        cells = [rects[n] for n in component]
        xs = _cluster([v for cell in cells for v in (cell[0], cell[2])], boundary_tolerance)
        ys = _cluster([v for cell in cells for v in (cell[1], cell[3])], boundary_tolerance)
        if len(xs) < 3 or len(ys) < 3 or len(cells) < 6:
            continue
        table_id = f'table-{len(tables)}'
        assignments = {n: [] for n in range(len(cells))}; ambiguous = []; unassigned = []
        table_box = [min(c[0] for c in cells), min(c[1] for c in cells),
                     max(c[2] for c in cells), max(c[3] for c in cells)]
        for line in normalized:
            area = (line['box'][2] - line['box'][0]) * (line['box'][3] - line['box'][1])
            if area <= 0:
                continue
            fractions = [_overlap(line['box'], cell) / area for cell in cells]
            ranked = sorted(range(len(fractions)), key=lambda n: fractions[n], reverse=True)
            first = ranked[0] if ranked else None
            best = fractions[first] if first is not None else 0
            second = fractions[ranked[1]] if len(ranked) > 1 else 0
            # OCR boxes often pad above/below a small cell. Require dominant
            # overlap, so near-equal spanning lines remain unresolved.
            if best >= 0.50 and best - second >= 0.25:
                assignments[first].append(line)
            elif sum(fraction >= 0.20 for fraction in fractions) > 1:
                ambiguous.append(line['id'])
            elif _overlap(line['box'], table_box) > 0:
                unassigned.append(line['id'])
        rows = []
        for n, cell in enumerate(cells):
            left, right = [min(range(len(xs)), key=lambda k: abs(xs[k] - v)) for v in (cell[0], cell[2])]
            top, bottom = [min(range(len(ys)), key=lambda k: abs(ys[k] - v)) for v in (cell[1], cell[3])]
            assigned = sorted(assignments[n], key=lambda line: (line['box'][1], line['box'][0]))
            evidence = []
            for line in assigned:
                area = (line['box'][2] - line['box'][0]) * (line['box'][3] - line['box'][1])
                competing = [{'cellId': f'{table_id}-cell-{other}',
                    'overlapFraction': _overlap(line['box'], other_cell) / area}
                    for other, other_cell in enumerate(cells) if other != n and _overlap(line['box'], other_cell) > 0]
                evidence.append({'sourceLineId': line['id'], 'originalPolygon': line['polygon'],
                    'ocrConfidence': line['confidence'], 'dominantOverlapFraction': _overlap(line['box'], cell) / area,
                    'competingCells': competing, 'assignmentStatus': 'dominant_overlap_candidate'})
            rows.append({'id': f'{table_id}-cell-{n}', 'row': top, 'column': left,
                'rowSpan': bottom - top, 'columnSpan': right - left,
                'bboxXYXY': cell, 'text': '\n'.join(line['text'] for line in assigned),
                'sourceLineIds': [line['id'] for line in assigned],
                'sourceLineAssignments': evidence,
                'textMayCrossRasterCellBoundaries': any(e['competingCells'] for e in evidence),
                'textStatus': 'ocr_observed_unverified' if assigned else 'no_assigned_ocr_text_unknown',
                'headerStatus': 'unknown', 'nativeCell': False})
        tables.append({'id': table_id, 'status': 'needs_review', 'nativeTable': False,
            'bboxXYXY': table_box,
            'rowBoundaries': ys, 'columnBoundaries': xs,
            'cells': sorted(rows, key=lambda cell: (cell['row'], cell['column'])),
            'ambiguousSourceLineIds': ambiguous, 'unassignedSourceLineIds': unassigned,
            'semanticStructureVerified': False})
    return {'algorithm': ALGORITHM_VERSION, 'status': 'candidates' if tables else 'no_ruled_candidate',
        'imageSize': [width, height], 'tables': tables,
        'parameters': {'horizontalKernelPixels': horizontal_length, 'verticalKernelPixels': vertical_length,
            'boundaryTolerancePixels': boundary_tolerance, 'minimumCellWidth': minimum_cell_width,
            'minimumCellHeight': minimum_cell_height, 'minimumTextOverlapFraction': 0.50,
            'minimumDominanceMargin': 0.25, 'ambiguousOverlapFraction': 0.20},
        'limitations': ['Raster candidates are not native or human-confirmed cells',
            'No assigned OCR text does not establish a blank cell', 'Unruled or broken grids can be missed',
            'Headers, units, adoption and reading order require independent verification']}
