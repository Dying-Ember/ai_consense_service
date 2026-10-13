"""Read lossless source-review references and validate declared anchors.

Only two historical v1 shapes have explicit adapters. New references should use
v2. Integrity is not transcription quality, human confirmation or parser
adoption. Paths inside references are retained but never followed by this
module; callers may supply actual evidence bytes by their declared SHA.
"""
import copy
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import io
import zipfile


SCHEMA = 'consense.source-review-reference.v2'
LEGACY_SCHEMA = 'consense.source-review-reference.v1'
LEGACY_ADAPTERS = {
    'gct1-complete-visible-page-source-review-20261009-v2': 'gct_single_review_image_v1',
    'sl-amr-complete-original-3436-3438-source-review-20261010-v1': 'sl_pages_and_table_group_v1',
}


class SourceReferenceError(ValueError):
    """Unsupported reference shape, ambiguous identity or failed integrity."""


@dataclass(frozen=True)
class SourceReference:
    reference: dict
    raw_bytes: bytes
    input_sha256: str
    input_schema: str
    input_reference_version: str
    adapter: str
    validation: dict


def _require(condition, message):
    if not condition:
        raise SourceReferenceError(message)


def _sha(value, label):
    _require(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value),
             label + ' must be lowercase SHA-256 hex')
    return value


def _integer(value, label, minimum=0):
    _require(isinstance(value, int) and not isinstance(value, bool) and value >= minimum,
             label + ' must be an integer >= ' + str(minimum))
    return value


def _text(value, label):
    _require(isinstance(value, str) and bool(value.strip()), label + ' must be a nonempty string')
    return value


def _list(value, label):
    _require(isinstance(value, list), label + ' must be an array')
    return value


def _box(value, label, limits):
    _require(isinstance(value, list) and len(value) == 4, label + ' must have four coordinates')
    _require(all(isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)
                 for x in value), label + ' must contain finite numeric coordinates')
    x0, y0, x1, y1 = value
    _require(0 <= x0 < x1 <= limits[0] and 0 <= y0 < y1 <= limits[1],
             label + ' lies outside its declared coordinate frame')


def _unique_ids(records, key, label):
    result = {}
    for row in _list(records, label):
        _require(isinstance(row, dict), label + ' entries must be objects')
        id_ = _text(row.get(key), label + '.' + key)
        _require(id_ not in result, 'Duplicate ' + label + ' ID: ' + id_)
        result[id_] = row
    return result


def _strict_json(raw):
    def pairs(values):
        output = {}
        for key, value in values:
            _require(key not in output, 'Duplicate JSON key: ' + key)
            output[key] = value
        return output
    def finite_float(value):
        result = float(value)
        _require(math.isfinite(result), 'Non-finite JSON number: ' + value)
        return result
    try:
        result = json.loads(raw.decode('utf-8-sig'), object_pairs_hook=pairs,
                            parse_float=finite_float,
                            parse_constant=lambda value: (_ for _ in ()).throw(
                                SourceReferenceError('Non-finite JSON constant: ' + value)))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise SourceReferenceError('Reference must be unambiguous UTF-8 JSON') from error
    _require(isinstance(result, dict), 'Reference root must be an object')
    return result


def _pdf_page(page_no, source_sha, image, page=None):
    _integer(page_no, 'legacy physicalPageNo', 1)
    _require(isinstance(image, dict), 'Legacy page requires a source-image object')
    page = copy.deepcopy(page or {})
    page['pageId'] = 'pdf-physical-' + str(page_no)
    page['physicalPageNo'] = page_no
    page['sourceAnchor'] = {'kind': 'pdf_physical_page', 'sourceSha256': source_sha,
                            'physicalPageNo': page_no, 'status': 'declared'}
    page['sourceImage'] = copy.deepcopy(image)
    page['sourceImage']['sourceRelation'] = {
        'kind': 'original_pdf_page_raster', 'originalSourceSha256': source_sha,
        'originalPhysicalPageNo': page_no, 'status': 'declared_not_semantically_verified'}
    return page


def _adapt_v1(data, adapter):
    document = copy.deepcopy(data)
    source_sha = _sha(data.get('sourceSha256'), 'sourceSha256')
    physical = _list(data.get('physicalPages'), 'physicalPages')
    for page in physical:
        _integer(page, 'physicalPages entry', 1)
    _require(len(physical) == len(set(physical)), 'Duplicate physicalPages')
    if adapter == 'gct_single_review_image_v1':
        _require(len(physical) == 1 and isinstance(data.get('reviewImage'), dict) and 'pages' not in data,
                 'GCT legacy adapter requires one physical page and one reviewImage')
        page_no = physical[0]
        page = _pdf_page(page_no, source_sha, data['reviewImage'])
        ordered = _list(data.get('regions'), 'regions')
        for row in ordered:
            _require(isinstance(row, dict), 'Legacy regions must be objects')
            _integer(row.get('ordinal'), 'legacy region ordinal')
        ordered = sorted(ordered, key=lambda r: r['ordinal'])
        page['regionIdsInReadingOrder'] = [r.get('regionId') for r in ordered]
        page['readingOrderEvidence'] = {'status': 'declared', 'method': 'existing_region_ordinals'}
        document['pages'] = [page]
    else:
        _require('reviewImage' not in data and isinstance(data.get('pages'), list)
                 and isinstance(data.get('tables'), list),
                 'SL legacy adapter requires pages[] and preserved table groups')
        _require(all(isinstance(p, dict) for p in data['pages']), 'Legacy pages must be objects')
        document['pages'] = [_pdf_page(p.get('physicalPageNo'), source_sha, p.get('sourceImage'), p)
                             for p in data['pages']]
        for page in document['pages']:
            page['readingOrderEvidence'] = {'status': 'declared', 'method': 'existing_page_region_order'}
    images = {p['physicalPageNo']: p['sourceImage'] for p in document['pages']}
    for region in _list(document.get('regions'), 'regions'):
        _require(isinstance(region, dict), 'Legacy regions must be objects')
        page_no = region.get('pageNo')
        _integer(page_no, 'legacy region pageNo', 1)
        _require(page_no in images, 'Legacy region page does not belong to the declared source pages')
        _require(region.get('physicalPageNo', page_no) == page_no, 'Conflicting region page aliases')
        region['sourceAnchor'] = {
            'kind': 'pdf_physical_region', 'sourceSha256': source_sha,
            'physicalPageNo': page_no, 'pageId': 'pdf-physical-' + str(page_no),
            'renditionSha256': images[page_no].get('sha256'),
            'coordinateFrame': 'raster_top_left_normalized',
            'geometryStatus': region.get('boundaryPrecision', 'unknown')}
        if 'role' not in region:
            region['role'] = None
            region['roleStatus'] = 'unknown_not_supplied_by_legacy_reference'
    document['schema'] = SCHEMA
    document['sourceMediaType'] = 'application/pdf'
    return document


def read_source_reference(path_or_bytes, *, expected_sha256=None):
    """Capture exact input bytes, explicitly adapt v1 or read v2, then validate.

    The returned raw_bytes never changes, including whitespace/BOM. Its SHA,
    original schema/version and adapter stay separate from the normalized v2
    document. Embedded source/image paths are never opened implicitly.
    """
    if isinstance(path_or_bytes, bytes):
        raw = path_or_bytes
    elif isinstance(path_or_bytes, (str, Path)):
        try:
            raw = Path(path_or_bytes).read_bytes()
        except OSError as error:
            raise SourceReferenceError('Reference file is missing or unreadable') from error
    else:
        raise SourceReferenceError('Reference input must be a path or exact bytes')
    digest = hashlib.sha256(raw).hexdigest()
    if expected_sha256 is not None:
        _require(digest == _sha(expected_sha256, 'expected_sha256'), 'Input reference SHA-256 mismatch')
    data = _strict_json(raw)
    schema, version = data.get('schema'), _text(data.get('referenceVersion'), 'referenceVersion')
    if schema == LEGACY_SCHEMA:
        adapter = LEGACY_ADAPTERS.get(version)
        _require(adapter is not None, 'Unsupported v1 reference version; author new references as v2')
        normalized = _adapt_v1(data, adapter)
    elif schema == SCHEMA:
        adapter, normalized = 'native_v2', copy.deepcopy(data)
    else:
        raise SourceReferenceError('Unsupported source reference schema')
    report = validate_source_reference(normalized)
    report['inputSha256Pinned'] = expected_sha256 is not None
    return SourceReference(normalized, raw, digest, schema, version, adapter, report)


def _docx_anchor(anchor, source_sha, label, *, source_bytes=None, _part_cache=None):
    _require(anchor.get('sourceSha256') == source_sha, label + ' source SHA does not match original DOCX')
    _require(anchor.get('kind') == 'docx_native', label + ' must be a docx_native anchor')
    if 'nodePath' in anchor or 'partSha256' in anchor:
        part = _text(anchor.get('partName'), label + '.partName')
        _require(not part.startswith('/') and '\\' not in part
                 and all(segment not in ('', '.', '..') for segment in part.split('/')), label + ' invalid native package part name')
        digest = _sha(anchor.get('partSha256'), label + '.partSha256')
        path = _text(anchor.get('nodePath'), label + '.nodePath')
        _require(path.startswith('/{') and re.search(r'\[[1-9][0-9]*\]$', path), label + ' requires an exact expanded-name XML path')
        _require(not any(key in anchor for key in ('pageNo', 'physicalPageNo', 'bbox', 'bodyElementIndex', 'tablePath')),
                 label + ' exact native anchor cannot mix physical pages/boxes or legacy index addresses')
        if source_bytes is None:
            return {'status': 'typed_shape_validated_actual_source_unavailable', 'partSha256': digest}
        from native_observation import _tree, WORD_NAMESPACES, NativeObservationError
        try:
            cache = {} if _part_cache is None else _part_cache
            key = (source_sha, hashlib.sha256(source_bytes).hexdigest(), part)
            if key not in cache:
                with zipfile.ZipFile(io.BytesIO(source_bytes)) as package:
                    entries = [entry for entry in package.infolist() if not entry.is_dir()]
                    _require(len(entries) == len({entry.filename for entry in entries}), label + ' original package has duplicate entries')
                    part_bytes = package.read(part)
                actual_part_sha = hashlib.sha256(part_bytes).hexdigest()
                _require(actual_part_sha == digest, label + ' part SHA differs from actual original package')
                positions, _ = _tree(part_bytes)
                cache[key] = (actual_part_sha, positions)
            actual_part_sha, positions = cache[key]
            _require(actual_part_sha == digest, label + ' part SHA differs from actual original package')
        except (KeyError, OSError, RuntimeError, zipfile.BadZipFile, NativeObservationError) as error:
            raise SourceReferenceError(label + ' native package/XML part is unavailable or unsupported') from error
        node = positions.get(path)
        _require(node is not None and node.tag.startswith('{') and node.tag[1:].split('}', 1)[0] in WORD_NAMESPACES,
                 label + ' native XML path does not identify an actual Word element')
        return {'status': 'verified_from_actual_original_package', 'partSha256': digest}
    _require(anchor.get('partName') == 'word/document.xml', label + ' currently supports main-document native anchors only')
    _integer(anchor.get('bodyElementIndex'), label + '.bodyElementIndex')
    node = anchor.get('nodeKind')
    _require(node in ('paragraph', 'table', 'cell'), label + ' nodeKind must be paragraph, table or cell')
    path = _list(anchor.get('tablePath', []), label + '.tablePath')
    if node in ('table', 'cell'):
        _require(bool(path), label + ' table/cell anchor requires tablePath')
    for i, level in enumerate(path):
        _require(isinstance(level, dict), label + '.tablePath levels must be objects')
        _integer(level.get('tableIndex'), label + '.tablePath.tableIndex')
        has_row, has_cell = 'rowIndex' in level, 'cellIndex' in level
        _require(has_row == has_cell, label + ' rowIndex and cellIndex must appear together')
        if has_row:
            _integer(level['rowIndex'], label + '.tablePath.rowIndex')
            _integer(level['cellIndex'], label + '.tablePath.cellIndex')
        _require(i == len(path)-1 or has_row, label + ' nested table must be located within an actual parent cell')
    if node == 'cell':
        _require('cellIndex' in path[-1], label + ' cell anchor requires native row and cell indices')
    return {'status': 'legacy_declared_indices_only_actual_tree_not_verified'}


def validate_source_reference(reference, *, evidence_bytes=None):
    """Validate identities, positions and links; supplied actual bytes verify SHA.

    evidence_bytes is an optional explicit {declared_sha256: actual_bytes} map.
    Missing bytes remain unverified. This function never reads source paths or
    converts unknowns, author claims or integrity into semantic acceptance.
    """
    try:
        return _validate_source_reference(reference, evidence_bytes=evidence_bytes)
    except SourceReferenceError:
        raise
    except (TypeError, KeyError, AttributeError, IndexError) as error:
        raise SourceReferenceError('Malformed reference structure; explicit typed anchors/lists are required') from error


def _validate_source_reference(reference, *, evidence_bytes):
    _require(isinstance(reference, dict) and reference.get('schema') == SCHEMA, 'Validation requires a v2 reference')
    source_sha = _sha(reference.get('sourceSha256'), 'sourceSha256')
    _text(reference.get('referenceVersion'), 'referenceVersion')
    _text(reference.get('actor'), 'actor')
    _text(reference.get('method'), 'method')
    _text(reference.get('split'), 'split')
    _require(isinstance(reference.get('humanConfirmed'), bool), 'humanConfirmed must be explicit boolean')
    media = reference.get('sourceMediaType')
    _require(media in ('application/pdf', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'),
             'sourceMediaType must identify PDF or native DOCX')
    evidence_bytes = {} if evidence_bytes is None else evidence_bytes
    _require(isinstance(evidence_bytes, dict), 'evidence_bytes must be an explicit SHA-to-bytes map')
    unknowns, unsupported, verification = [], [], {}

    def unknown(path, reason):
        unknowns.append({'path': path, 'status': 'unknown', 'reason': reason})

    def actual(digest, label):
        _sha(digest, label)
        if digest not in evidence_bytes:
            verification[digest] = {'status': 'unverified', 'reason': 'actual bytes not supplied explicitly'}
            return None
        raw = evidence_bytes[digest]
        _require(isinstance(raw, bytes), label + ' supplied evidence must be actual bytes')
        _require(hashlib.sha256(raw).hexdigest() == digest, label + ' actual byte SHA-256 mismatch')
        verification[digest] = {'status': 'verified', 'byteCount': len(raw)}
        return raw

    source_bytes = actual(source_sha, 'sourceSha256')
    # Fresh verified source bytes define this validation-local tree cache.
    # A subsequent validation cannot reuse locations or accept old bytes.
    docx_part_cache = {}
    native_anchor_verification = {}
    pages = _unique_ids(reference.get('pages'), 'pageId', 'pages')
    regions = _unique_ids(reference.get('regions'), 'regionId', 'regions')
    _require(bool(regions), 'Reference must contain source regions')
    page_positions = set()
    for id_, page in pages.items():
        anchor = page.get('sourceAnchor')
        _require(isinstance(anchor, dict), 'Page requires explicit sourceAnchor: ' + id_)
        _require(anchor.get('sourceSha256') == source_sha, 'Page belongs to a different original source')
        kind = anchor.get('kind')
        _require(kind in ('pdf_physical_page', 'docx_rendition_page'), 'Unsupported page anchor kind')
        page_no = _integer(anchor.get('physicalPageNo'), 'page physicalPageNo', 1)
        if kind == 'pdf_physical_page':
            _require(media == 'application/pdf', 'Native DOCX cannot acquire original physical Word page numbers')
            _require(page.get('physicalPageNo', page_no) == page_no, 'Page aliases disagree')
            position = (kind, source_sha, page_no)
        else:
            _require(media.endswith('wordprocessingml.document'), 'DOCX rendition page requires original DOCX media type')
            rendition_sha = _sha(anchor.get('renditionSha256'), 'DOCX rendition SHA')
            _require(rendition_sha != source_sha, 'DOCX rendition must have a distinct byte identity')
            _text(anchor.get('mappingStatus'), 'DOCX rendition mappingStatus')
            _require('physicalPageNo' not in page and 'pageNo' not in page,
                     'DOCX rendition page number must stay inside its rendition anchor')
            actual(rendition_sha, 'DOCX PDF rendition SHA')
            position = (kind, rendition_sha, page_no)
            unknown('pages.' + id_ + '.sourceAnchor.mapping', 'Declared DOCX-to-rendition relationship has not been independently reviewed')
        _require(position not in page_positions, 'Duplicate source page position')
        page_positions.add(position)
        image = page.get('sourceImage')
        if image is None:
            unknown('pages.' + id_ + '.sourceImage', 'No raster artifact supplied')
        else:
            _require(isinstance(image, dict), 'sourceImage must be an object or null')
            width = _integer(image.get('width'), 'sourceImage.width', 1)
            height = _integer(image.get('height'), 'sourceImage.height', 1)
            raw = actual(image.get('sha256'), 'sourceImage.sha256')
            relation = image.get('sourceRelation')
            _require(isinstance(relation, dict) and relation.get('originalSourceSha256') == source_sha,
                     'Source image requires explicit original source relationship')
            if kind == 'pdf_physical_page':
                _require(relation.get('kind') == 'original_pdf_page_raster' and relation.get('originalPhysicalPageNo') == page_no,
                         'Source raster page mapping does not match original physical PDF page')
            else:
                _require(relation.get('kind') == 'docx_rendition_page_raster'
                         and relation.get('renditionSha256') == anchor['renditionSha256']
                         and relation.get('renditionPhysicalPageNo') == page_no,
                         'DOCX raster must identify its actual PDF rendition page')
            if raw is not None and raw.startswith(b'\x89PNG\r\n\x1a\n'):
                _require(len(raw) >= 24 and raw[12:16] == b'IHDR', 'Malformed PNG dimension header')
                _require(struct.unpack('>II', raw[16:24]) == (width, height), 'PNG dimensions differ from declared raster frame')
            elif raw is not None:
                unsupported.append({'path': 'pages.' + id_ + '.sourceImage.dimensions',
                                    'status': 'preserved_not_verified', 'reason': 'Only PNG header dimensions are checked by this module'})
        _list(page.get('regionIdsInReadingOrder'), 'page reading order')
        _require(all(isinstance(rid,str) for rid in page['regionIdsInReadingOrder']), 'Reading order IDs must be strings')

    pdf_region_ids = set()
    if 'physicalPages' in reference:
        physical = _list(reference['physicalPages'], 'physicalPages')
        for page_no in physical:
            _integer(page_no, 'physicalPages entry', 1)
        _require(len(physical)==len(set(physical)), 'Duplicate physicalPages')
        if media == 'application/pdf':
            _require(set(physical)=={p['sourceAnchor']['physicalPageNo'] for p in pages.values()},
                     'physicalPages differ from declared source page anchors')
        else:
            _require(not physical, 'Native DOCX cannot declare original physical Word pages')
    for id_, region in regions.items():
        _require(isinstance(region.get('text'), str), 'Region text must preserve a string, including empty text')
        anchor = region.get('sourceAnchor')
        _require(isinstance(anchor, dict) and anchor.get('sourceSha256') == source_sha,
                 'Region requires an anchor to the same original source')
        if region.get('role') is None:
            unknown('regions.' + id_ + '.role', 'Region role was not supplied; not inferred from ID/text')
        else:
            _text(region['role'], 'region role')
        if anchor.get('kind') == 'docx_native':
            _require(media.endswith('wordprocessingml.document'), 'docx_native region requires native DOCX source')
            native_anchor_verification['regions.' + id_] = _docx_anchor(anchor, source_sha,
                'regions.' + id_ + '.sourceAnchor', source_bytes=source_bytes, _part_cache=docx_part_cache)
            _require(region.get('pageNo') is None and region.get('physicalPageNo') is None,
                     'Native DOCX regions cannot be assigned invented Word physical pages')
            _require(region.get('bboxNormalized') is None and region.get('bboxRaster') is None,
                     'Native DOCX position cannot use a rendition bbox as original coordinates')
            if native_anchor_verification['regions.' + id_]['status'] == 'verified_from_actual_original_package':
                unknown('regions.' + id_ + '.nativeTextSemantics', 'Exact native anchor was verified; reference transcription and effective visual/legal meaning were not evaluated')
            else:
                unknown('regions.' + id_ + '.nativeLocation', 'Native OOXML location is a declaration without actual tree verification')
            rendition = region.get('renditionAnchor')
            if rendition is not None:
                _require(isinstance(rendition, dict) and rendition.get('kind') == 'docx_rendition_region',
                         'DOCX region rendition anchor must be explicit')
                _require(rendition.get('originalSourceSha256') == source_sha, 'DOCX rendition region original source mismatch')
                target = pages.get(rendition.get('pageId'))
                _require(target is not None and target['sourceAnchor']['kind'] == 'docx_rendition_page',
                         'DOCX rendition region has no matching rendition page')
                target_anchor = target['sourceAnchor']
                _require(rendition.get('renditionSha256') == target_anchor['renditionSha256']
                         and rendition.get('physicalPageNo') == target_anchor['physicalPageNo'],
                         'DOCX rendition region mapping does not match rendition page')
                _text(rendition.get('mappingStatus'), 'DOCX region rendition mappingStatus')
                if rendition.get('bboxNormalized') is not None:
                    _box(rendition['bboxNormalized'], 'DOCX rendition bbox', (1, 1))
                    _require(target.get('sourceImage') is not None, 'DOCX rendition bbox requires its declared raster frame')
                unknown('regions.' + id_ + '.renditionAnchor.mapping', 'Native-to-rendition mapping remains a declaration')
        else:
            _require(media == 'application/pdf' and anchor.get('kind') == 'pdf_physical_region', 'Unsupported region source anchor kind')
            page = pages.get(anchor.get('pageId'))
            _require(page is not None and page['sourceAnchor']['kind'] == 'pdf_physical_page', 'PDF region has no original physical page')
            page_no = page['sourceAnchor']['physicalPageNo']
            for alias in ('pageNo','physicalPageNo'):
                if alias in region:
                    _integer(region[alias], 'region '+alias, 1)
            _require(anchor.get('physicalPageNo') == page_no
                     and region.get('pageNo', page_no) == page_no
                     and region.get('physicalPageNo', page_no) == page_no, 'Region physical page mappings disagree')
            pdf_region_ids.add(id_)
            image = page.get('sourceImage')
            if region.get('bboxNormalized') is not None:
                _box(region['bboxNormalized'], 'region bboxNormalized', (1, 1))
                _require(image is not None and anchor.get('renditionSha256') == image['sha256'],
                         'Normalized raster bbox requires matching source-image identity')
            if region.get('bboxRaster') is not None:
                _require(image is not None, 'Raster bbox requires its raster dimensions')
                _box(region['bboxRaster'], 'region bboxRaster', (image['width'], image['height']))
                if region.get('bboxNormalized') is not None:
                    expected = [region['bboxRaster'][i] / image['width' if i % 2 == 0 else 'height'] for i in range(4)]
                    _require(all(abs(a-b) <= 1e-7 for a,b in zip(expected, region['bboxNormalized'])), 'Raster and normalized boxes disagree')
            if region.get('bboxNormalized') is None and region.get('bboxRaster') is None:
                unknown('regions.' + id_ + '.geometry', 'Source position has no measured box')
            precision = anchor.get('geometryStatus', 'unknown')
            unknown('regions.' + id_ + '.geometryAccuracy', 'Declared boundary precision is preserved without exact-glyph proof: ' + str(precision))
        if region.get('selectionStatus') == 'unknown' or region.get('semanticState') == 'unknown':
            unknown('regions.' + id_ + '.semanticState', 'Unknown selection/value is preserved; visual emptiness is not a logical answer')
        known_region = {'regionId','text','role','roleStatus','textStatus','pageNo','physicalPageNo','ordinal','clauseId',
            'bboxNormalized','bboxRaster','boundaryPrecision','sourceAnchor','renditionAnchor','formFieldId',
            'visualState','semanticValue','semanticState','ruleBboxRaster','ruleBoundaryPrecision',
            'tableId','logicalRowId','columnStart','columnSpan','rowSpan','continuation','title','bulletCount',
            'selected','selectionStatus','semanticMeaning','interiorDarkPixelCount','interiorPixelCount',
            'remarkLetter','remarksGroupId','appliesToTableId'}
        for field in sorted(set(region)-known_region):
            unsupported.append({'path':'regions.'+id_+'.'+field,'status':'preserved_not_interpreted',
                                'reason':'Region extension outside the minimal integrity contract'})

    ordered = set()
    for id_, page in pages.items():
        ids = page['regionIdsInReadingOrder']
        _require(len(ids) == len(set(ids)), 'Duplicate regions in page reading order')
        for index, rid in enumerate(ids):
            _require(rid in regions, 'Reading order refers to missing region')
            region = regions[rid]
            region_anchor = region['sourceAnchor']
            located = region_anchor.get('pageId') == id_ or region.get('renditionAnchor', {}).get('pageId') == id_
            _require(located, 'Reading order region belongs to another page')
            _require(rid not in ordered, 'Region appears in more than one page reading order')
            if region_anchor.get('kind') == 'pdf_physical_region' and 'ordinal' in region:
                _require(_integer(region['ordinal'], 'region ordinal') == index, 'Page reading order and region ordinals disagree')
            ordered.add(rid)
        if 'visiblePageText' in page:
            expected = '\n\n'.join(regions[rid]['text'] for rid in ids if regions[rid]['text'])
            _require(page['visiblePageText'] == expected, 'visiblePageText differs from declared source region order/text')
    _require(pdf_region_ids <= ordered, 'PDF regions are missing from their page reading order')
    for relation in _list(reference.get('relations', []), 'relations'):
        _require(isinstance(relation, dict), 'Relation must be an object')
        _text(relation.get('type'), 'relation type')
        _require(relation.get('fromRegionId') in regions and relation.get('toRegionId') in regions,
                 'Relation has dangling source region endpoint')
        _require(relation['fromRegionId'] != relation['toRegionId'], 'Self-referential source relation')
    _validate_groups(reference, regions, pages, unsupported)
    _validate_native_tables(reference, source_sha, source_bytes, media, native_anchor_verification, docx_part_cache)
    known = {'schema','referenceVersion','actor','method','humanConfirmed','split','sourceSha256','sourcePath',
             'sourceMediaType','physicalPages','pages','regions','relations','tables','formFields','formFieldGroups',
             'remarksGroups','coverage','historicalExposure','purpose','unknowns','semanticQuality','clauseId',
             'sourceReviewedBeforeCurrentParserInspection','sourceTranscriptionBasedOn','candidateAcceptance',
             'geometryEvidence','revisionEvidence','reviewImage','nativeTables'}
    for field in sorted(set(reference)-known):
        unsupported.append({'path':field,'status':'preserved_not_interpreted','reason':'Outside the minimal declared-anchor/identity validation contract'})
    unknown('semanticQuality', 'Input semantic quality/human confirmation claims were preserved; this module performs no semantic acceptance')
    return {'schema':'consense.source-reference-integrity-report.v1',
            'status':'core_integrity_validated','sourceSha256':source_sha,
            'pageCount':len(pages),'regionCount':len(regions),
            'actualByteVerification':verification,'unknowns':unknowns,
            'nativeAnchorVerification':native_anchor_verification,
            'unsupportedFields':unsupported,'semanticAcceptance':'not_evaluated',
            'humanConfirmationVerified':False,'parserAdoptionPerformed':False,
            'unknownsUpgraded':False,'embeddedPathsRead':False}


def _validate_native_tables(reference, source_sha, source_bytes, media, verification, docx_part_cache):
    tables = _unique_ids(reference.get('nativeTables', []), 'tableId', 'nativeTables')
    if not tables:
        return
    _require(media.endswith('wordprocessingml.document'), 'Native table references require original DOCX')
    graph = {}
    if source_bytes is not None:
        from native_observation import _xml_context, _local, native_node_id, NATIVE_TAGS, NativeObservationError
        try:
            with zipfile.ZipFile(io.BytesIO(source_bytes)) as package:
                for entry in package.infolist():
                    if entry.is_dir() or not entry.filename.startswith('word/') or not entry.filename.endswith('.xml'):
                        continue
                    raw = package.read(entry)
                    context = _xml_context(raw)
                    if _local(context['root']) not in ('document', 'hdr', 'ftr', 'footnotes', 'endnotes'):
                        continue
                    part_sha = hashlib.sha256(raw).hexdigest()
                    kinds = {local: kind for kind, local in NATIVE_TAGS.items()}
                    for path, node in context['positions'].items():
                        if _local(node) in kinds:
                            id_ = native_node_id(kinds[_local(node)], source_sha, part_sha, path, part_name=entry.filename)
                            graph[id_] = {'kind': kinds[_local(node)], 'partName': entry.filename, 'nodePath': path,
                                          'context': context, 'partSha256': part_sha}
        except (OSError, RuntimeError, zipfile.BadZipFile, NativeObservationError) as error:
            raise SourceReferenceError('Original native table package/XML is unavailable or unsupported') from error
    for id_, table in tables.items():
        label = 'nativeTables.' + id_
        _require(isinstance(table.get('sourceAnchor'), dict), label + ' exact source anchor required')
        anchor = table['sourceAnchor']
        _require('nodePath' in anchor and 'partSha256' in anchor, label + ' requires exact native anchor')
        verification[label] = _docx_anchor(anchor, source_sha, label, source_bytes=source_bytes, _part_cache=docx_part_cache)
        _require(table.get('completeNativeTable') is True, label + ' must explicitly describe entire native roots')
        values = {}
        for field in ('rootTableIds', 'nativeTableIds', 'nativeParagraphIds'):
            values[field] = _list(table.get(field), label + '.' + field)
            _require(all(isinstance(value, str) and value for value in values[field])
                     and len(values[field]) == len(set(values[field])), label + ' native IDs must be explicit and unique')
        roots = values['rootTableIds']
        _require(bool(roots) and set(roots) <= set(values['nativeTableIds']), label + ' complete roots required')
        if source_bytes is None:
            continue
        _require(all(root in graph and graph[root]['kind'] == 'table' for root in roots), label + ' roots do not identify original native tables')
        first = graph[roots[0]]
        _require(anchor['partName'] == first['partName'] and anchor['partSha256'] == first['partSha256']
                 and anchor['nodePath'] == first['nodePath'], label + ' anchor differs from first actual native root')
        def within(node, root):
            return node['partName'] == root['partName'] and (node['nodePath'] == root['nodePath']
                   or node['nodePath'].startswith(root['nodePath'] + '/'))
        _require(not any(within(graph[root], graph[other]) for root in roots for other in roots if root != other),
                 label + ' roots overlap by nested native ancestry')
        for field, kind in (('nativeTableIds', 'table'), ('nativeParagraphIds', 'paragraph')):
            expected = [node_id for node_id, node in graph.items() if node['kind'] == kind and any(within(node, graph[root]) for root in roots)]
            _require(set(values[field]) == set(expected), label + ' omits or invents complete original members: ' + field)
        verification[label]['nativeMembershipStatus'] = 'complete_original_xml_roots_verified'


def _validate_groups(reference, regions, pages, unsupported):
    def refs(ids, label):
        _list(ids, label)
        _require(all(isinstance(id_, str) for id_ in ids), label + ' IDs must be strings')
        _require(len(ids) == len(set(ids)), label + ' contains duplicate IDs')
        _require(all(rid in regions for rid in ids), label + ' has dangling region references')

    tables = _unique_ids(reference.get('tables', []), 'tableId', 'tables')
    for id_, table in tables.items():
        if 'physicalRowParts' not in table or 'logicalRows' not in table:
            unsupported.append({'path':'tables.'+id_, 'status':'preserved_not_fully_validated',
                'reason':'Only the explicit logical-row/physical-part group topology currently has link validation'})
            continue
        count = _integer(table.get('columnCount'), 'table columnCount', 1)
        parts = _unique_ids(table['physicalRowParts'], 'partId', 'table physicalRowParts')
        rows = _unique_ids(table['logicalRows'], 'logicalRowId', 'table logicalRows')
        owned_parts = set()
        for row_id, row in rows.items():
            ids = _list(row.get('partIds'), 'logical row partIds')
            _require(all(isinstance(part_id, str) for part_id in ids), 'Logical row part IDs must be strings')
            _require(bool(ids) and len(ids)==len(set(ids)), 'Logical row requires unique physical parts')
            for part_id in ids:
                _require(part_id in parts and parts[part_id].get('logicalRowId') == row_id,
                         'Logical row refers to missing or differently owned physical part')
                _require(part_id not in owned_parts, 'Physical row part belongs to multiple logical rows')
                owned_parts.add(part_id)
            refs(row.get('checkboxIds', []), 'logical row checkboxIds')
            for rid in row.get('checkboxIds', []):
                _require(regions[rid].get('logicalRowId') == row_id and regions[rid].get('tableId') == id_,
                         'Checkbox does not belong to its declared logical row/table')
        _require(owned_parts == set(parts), 'Table contains orphan physical row parts')
        for part in parts.values():
            _integer(part.get('pageNo'), 'physical row part pageNo', 1)
            _require(part.get('logicalRowId') in rows, 'Physical row has no logical owner')
            refs(part.get('cellRegionIds'), 'physical row cellRegionIds')
            refs(part.get('checkboxIds', []), 'physical row checkboxIds')
            _require(part.get('descriptionRegionId') in regions, 'Physical row description reference is missing')
            _require(part['descriptionRegionId'] in part['cellRegionIds'], 'Physical description is not a cell of that row part')
            columns = []
            for rid in part['cellRegionIds']:
                cell = regions[rid]
                _require(cell.get('tableId') == id_ and cell.get('logicalRowId') == part['logicalRowId'], 'Cell owner mismatch')
                _require(cell.get('pageNo') == part.get('pageNo'), 'Cell/physical part page mismatch')
                start = _integer(cell.get('columnStart'), 'cell columnStart')
                span = _integer(cell.get('columnSpan'), 'cell columnSpan', 1)
                _integer(cell.get('rowSpan'), 'cell rowSpan', 1)
                _require(start+span <= count, 'Cell span exceeds table column count')
                columns.extend(range(start,start+span))
            _require(sorted(columns)==list(range(count)), 'Physical table row columns overlap or are incomplete')
            if part.get('continuation') is True:
                _require(not part.get('checkboxIds'), 'Continuation part must not invent additional checkbox IDs')
                previous_descriptions = [p.get('descriptionRegionId') for p in parts.values()
                    if p.get('logicalRowId') == part['logicalRowId'] and p.get('pageNo',0) < part.get('pageNo',0)]
                _require(any(r.get('type') == 'continuation_of'
                    and r.get('fromRegionId') == part['descriptionRegionId']
                    and r.get('toRegionId') in previous_descriptions for r in reference.get('relations',[])),
                    'Continuation part has no same-item source relationship to an earlier page part')
        for page_part in _list(table.get('pageParts', []), 'table pageParts'):
            refs(page_part.get('headerRegionIds'), 'table headerRegionIds')
            _require(all(regions[rid].get('tableId')==id_ for rid in page_part['headerRegionIds']), 'Header belongs to another table')
            _require(any(p['sourceAnchor']['physicalPageNo']==page_part.get('physicalPageNo') for p in pages.values()), 'Table part has no declared physical page')
            _require(all(pid in parts for pid in page_part.get('partIds', [])), 'Table page references missing row part')
        for header in _list(table.get('headerCells', []), 'table headerCells'):
            start = _integer(header.get('columnStart'), 'header columnStart')
            span = _integer(header.get('columnSpan'), 'header columnSpan', 1)
            _integer(header.get('rowSpan'), 'header rowSpan', 1)
            _require(start+span <= count, 'Header span exceeds table columns')
        unsupported.append({'path':'tables.'+id_+'.genericGridTopology','status':'unknown',
            'reason':'Logical/physical references and column spans checked; arbitrary row grids, nested tables and vertical merge correctness not inferred'})
    fields = _unique_ids(reference.get('formFields', []), 'fieldId', 'formFields')
    for field in fields.values():
        _require(field.get('labelRegionId') in regions, 'Form field has no label region')
        refs(field.get('blankRegionIds', []), 'form blankRegionIds')
    for group in _list(reference.get('formFieldGroups', []), 'formFieldGroups'):
        _require(all(id_ in fields for id_ in group.get('fieldIds', [])), 'Form group has dangling field ID')
    for group in _list(reference.get('remarksGroups', []), 'remarksGroups'):
        _require(group.get('headingRegionId') in regions, 'Remarks group heading is missing')
        refs(group.get('remarkRegionIds'), 'remarks regionIds')
        _require(group.get('appliesToTableId') in tables, 'Remarks group has dangling table ID')
