"""Internal native PDF text / actual dual visual observations, without gold repair.

This independent recipe accepts only source-reviewed whole prose units. Actual
PDFBox glyph Unicode is the text channel; parser blocks are separate whole-block
layout evidence. Explicit whole-table groups retain reviewed glyph topology;
AP-to-page text remains unsupported. Every
validation cold-reads CAS and reconstructs all derived fields, not just seals.
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
from pathlib import Path

from artifact_store import ArtifactStore
from canonical_projection import (CanonicalProjectionError, MODULES, ROLES, _bytes,
                                  _fail, _fields, _hash, _name, _pages, _quality, _sha)
from competition_scope import EXCLUDED_CLASSES, SOURCE_PURPOSES
from native_pdf_observation import validate_native_pdf_bundle
from native_pdf_table_projection import (project_page_assets, project_table_groups, table_region_ids,
                                        VERSION as TABLE_VERSION)
from parser_capture import rebuild_independent_capture_batch, rebuild_file_capture
from source_reference import SCHEMA as REFERENCE_SCHEMA, read_source_reference, validate_source_reference
from structured_parser_evidence import (assert_complete_dual, build_dual_parser_evidence,
                                       normalize_parser_run, project_bbox_to_original, read_artifact_record)
from token_windows import paragraph_map, utf16_length

SCHEMA = 'consense.native-pdf-canonical-projection.v1'
ADOPTION_SCHEMA = 'consense.native-pdf-canonical-adoption.v1'
REVIEW_SCHEMA = 'consense.native-pdf-region-review.v1'
LEGACY_VERSION = 'source-reviewed-native-pdf-prose-and-visual-layout-v1'
VERSION = 'source-reviewed-native-pdf-prose-and-visual-layout-v2'
TABLE_PROJECTION_VERSION = 'source-reviewed-native-pdf-prose-tables-and-page-assets-v3'
LEGACY_PROJECTION_DEPENDENCIES = ('artifact_store.py', 'canonical_projection.py', 'competition_scope.py',
                          'native_pdf_observation.py', 'parser_capture.py', 'source_reference.py',
                          'structured_parser_evidence.py', 'token_windows.py')
PROJECTION_DEPENDENCIES = (*LEGACY_PROJECTION_DEPENDENCIES,
                           'pdf_selected_source.py', 'selected_source_raster.py')
VERSION_DEPENDENCIES = {LEGACY_VERSION: LEGACY_PROJECTION_DEPENDENCIES, VERSION: PROJECTION_DEPENDENCIES,
                       TABLE_PROJECTION_VERSION: (*PROJECTION_DEPENDENCIES, 'native_pdf_table_projection.py')}
STORAGE_SHAPE = 'full-native-pdf-joint-internal-v1'
RELATIONS = {'parent', 'continuation', 'qualifier', 'caption', 'header', 'sidebar', 'margin_note'}


def _boundary(operation, code):
    try:
        return operation()
    except CanonicalProjectionError:
        raise
    except (ValueError, OSError, KeyError, TypeError, IndexError, AttributeError) as error:
        raise CanonicalProjectionError(code, reason=str(error)) from error


def _ids(value, code, *, nonempty=False):
    _fail(isinstance(value, list) and (bool(value) or not nonempty)
          and all(isinstance(x, str) and x.strip() for x in value) and len(value) == len(set(value)), code)
    return value


def _json(raw):
    def pairs(values):
        result = {}
        for key, value in values:
            _fail(key not in result, 'duplicate_retained_json_key'); result[key] = value
        return result
    value = json.loads(raw.decode('utf-8-sig'), object_pairs_hook=pairs,
                       parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
    _bytes(value)
    return value


def _rebuild_run(run, store):
    if run.get('independentInputCaptures') is not None:
        rebuilt = rebuild_independent_capture_batch(run,
            [_rebuild_run(child, store) for child in run['independentInputCaptures']], store)
    elif run.get('fileCaptureProvenance') is not None:
        rebuilt = rebuild_file_capture(run, store)
    else:
        def descriptors(records):
            result = {}
            for name, record in records.items():
                read_artifact_record(record, artifact_store=store)
                _fail(isinstance(record.get('artifactDescriptor'), dict), 'dual_actual_cas_descriptor_required')
                result[name] = copy.deepcopy(record['artifactDescriptor'])
            return result
        rebuilt = normalize_parser_run(run['parser'], descriptors(run['rawOutputs']), run['source'], run['identity'],
             run['requestPageMap'], artifacts=descriptors(run['artifacts']), required_artifacts=run['requiredArtifacts'],
             capture_issues=run['captureIssues'], provider_input_identity=run['providerInputIdentity'],
             terminal=run['terminal'], artifact_store=store)
    _fail(rebuilt == run, 'dual_normalized_observation_differs_from_actual_raw')
    return rebuilt


def _dual(bundle, native, pages, store):
    complete = assert_complete_dual(bundle, pages, artifact_store=store)
    for field in ('sourceSha256', 'mediaType', 'pageCount'):
        _fail(bundle['source'].get(field) == native['source'].get(field), 'native_visual_source_mismatch', field=field)
    runs = [_rebuild_run(run, store) for run in bundle['runs']]
    rebuilt = build_dual_parser_evidence(runs, route_acceptance_manifest=bundle.get('routeAcceptance', {}).get('manifest'),
                                         artifact_store=store)
    _fail(rebuilt == bundle, 'dual_bundle_not_recomputable_from_actual_capture')
    return complete


def _scope(manifest, native):
    _fields(manifest, {'schema', 'nativeBundleSha256', 'dualBundleSha256', 'sourceSha256', 'sourceRevisionSha256',
        'documentId', 'role', 'module', 'eligibleModules', 'sourcePurpose', 'sourceClass', 'scope', 'quality',
        'reference', 'regionReview', 'units', 'sourceRelationIndices', 'tableGroups', 'assets'}, 'illegal_native_pdf_adoption')
    _fail(manifest.get('schema') == ADOPTION_SCHEMA and manifest.get('nativeBundleSha256') == native['bundleSha256'],
          'stale_native_pdf_adoption')
    for field in ('sourceSha256', 'sourceRevisionSha256'):
        _fail(manifest.get(field) == native['source'][field], 'native_pdf_adoption_source_mismatch', field=field)
    _name(manifest.get('documentId'), 'documentId')
    module, role, purpose = manifest.get('module'), manifest.get('role'), manifest.get('sourcePurpose')
    eligible = _ids(manifest.get('eligibleModules'), 'invalid_module_eligibility', nonempty=True)
    _fail(module in MODULES and role in ROLES and purpose in SOURCE_PURPOSES
          and set(eligible) <= MODULES and module in eligible, 'invalid_native_pdf_source_role')
    source_class = _name(manifest.get('sourceClass'), 'sourceClass')
    _fail(source_class not in EXCLUDED_CLASSES and source_class != 'evaluation_answer' and purpose != 'evaluation_answer',
          'module_source_not_eligible')
    template = purpose == 'standard_template' or source_class == 'drafting_standard_template'
    _fail(not template or purpose in {'standard_template', 'unknown'} and module == 'drafting' and eligible == ['drafting'],
          'module_source_not_eligible')
    _fail(source_class not in {'sl_library', 'sl_technical_library', 'specification_library'},
          'native_pdf_sl_scope_not_yet_supported')
    _fields(manifest.get('scope'), {'requiredPhysicalPages', 'scopePolicySha256'}, 'invalid_native_pdf_scope')
    pages = _pages(manifest['scope'].get('requiredPhysicalPages'), native['source']['pageCount'], 'requiredPhysicalPages')
    _sha(manifest['scope'].get('scopePolicySha256'), 'scopePolicySha256')
    _fail(set(pages) <= set(native['observation']['selectedPhysicalPages']), 'native_page_capture_missing')
    _fail(isinstance(manifest.get('tableGroups'), list) and isinstance(manifest.get('assets'), list),
          'native_pdf_tables_and_assets_must_be_explicit')
    return pages, _quality(manifest.get('quality'))


def _reference(manifest, native, pages, store):
    declaration = manifest.get('reference')
    _fields(declaration, {'actor', 'method', 'sourceSha256', 'referenceVersion', 'evidenceSha256',
                         'artifactDescriptor', 'pageImages'}, 'illegal_native_pdf_reference')
    raw = store.read_asset(declaration['artifactDescriptor'])
    _fail(hashlib.sha256(raw).hexdigest() == declaration.get('evidenceSha256'), 'native_pdf_reference_sha_mismatch')
    loaded = read_source_reference(raw, expected_sha256=declaration['evidenceSha256'])
    reference = loaded.reference
    _fail(loaded.input_schema == REFERENCE_SCHEMA and reference.get('sourceMediaType') == 'application/pdf'
          and reference.get('sourceSha256') == native['source']['sourceSha256'] and reference.get('humanConfirmed') is False,
          'actual_pdf_source_reference_v2_required')
    _fail(declaration.get('actor') == 'codex' and declaration.get('method') == 'original_source_review'
          and all(reference.get(key) == declaration.get(key) for key in ('actor', 'method', 'sourceSha256', 'referenceVersion')),
          'native_pdf_reference_claim_mismatch')
    _fail(set(pages) <= set(reference['physicalPages']), 'source_review_page_missing')
    evidence = {native['source']['sourceSha256']: store.read_asset(native['sourcePackage'])}
    images = declaration['pageImages']
    _fail(isinstance(images, list) and len({image['sha256'] for image in images}) == len(images), 'reference_page_images_required')
    for image in images: evidence[image['sha256']] = store.read_asset(image)
    _fail({page['sourceImage']['sha256'] for page in reference['pages']} <= set(evidence), 'actual_reference_raster_missing')
    report = validate_source_reference(reference, evidence_bytes=evidence)
    return reference, report


def _box(value, code):
    _fail(isinstance(value, list) and len(value) == 4 and all(type(x) in (int, float) and math.isfinite(x) for x in value)
          and 0 <= value[0] < value[2] <= 1 and 0 <= value[1] < value[3] <= 1, code)
    return value


def _native_region_geometry(ids, glyphs, page, region, reference_page):
    frame = page['frame']
    _fail(page['observationStatus'] == 'observed_native_operators_unreviewed',
          'partial_native_page_cannot_supply_whole_adopted_region')
    _fail(frame.get('transformStatus') == 'derived_page_frame_mapping_not_raster_verified',
          'native_region_page_frame_unknown')
    width, height = frame['renderedCropSizePoints72']
    image = reference_page['sourceImage']
    # PDF renderers round page dimensions to whole pixels. This validates only
    # the declared rendered-crop aspect ratio, never pixel-visible glyph extent.
    _fail(abs(image['width'] - image['height'] * width / height) <= 1.01,
          'reference_raster_native_crop_aspect_mismatch')
    rx0, ry0, rx1, ry1 = region['bboxNormalized']
    for gid in ids:
        glyph = glyphs[gid][0]
        _fail(glyph['eligibleForNativeText'] is True and glyph['visibilityStatus'] == 'operator_visible_unverified',
              'known_non_native_eligible_glyph_cannot_enter_prose', glyphId=gid)
        box = glyph['bbox']
        _fail(glyph['coordinateSpace'] == 'normalized_rendered_crop_top_left'
              and isinstance(box, list) and len(box) == 4
              and all(type(v) in (int, float) and math.isfinite(v) for v in box)
              and box[2] >= 0 and box[3] >= 0, 'native_glyph_region_geometry_unknown', glyphId=gid)
        x, y, w, h = box
        _fail((rx0 <= x <= rx1 if w == 0 else min(rx1, x + w) > max(rx0, x))
              and (ry0 <= y <= ry1 if h == 0 else min(ry1, y + h) > max(ry0, y)),
              'native_glyph_source_region_disjoint', glyphId=gid)


def _layout(row, region, reference_page, dual, *, allow_table=False):
    declaration = row.get('layout')
    _fields(declaration, {'selectedRunId', 'candidateBlockIds', 'rasterMappingReview'}, 'illegal_native_pdf_layout_binding')
    runs = {run['runId']: run for run in dual['runs']}
    _fail(declaration.get('selectedRunId') in runs and isinstance(declaration.get('candidateBlockIds'), dict)
          and set(declaration['candidateBlockIds']) == set(runs), 'both_layout_candidates_required')
    raster_reviews = declaration.get('rasterMappingReview')
    _fail(isinstance(raster_reviews, dict) and set(raster_reviews) == set(runs), 'reviewed_raster_relationship_required')
    result = []
    for run_id in sorted(declaration['candidateBlockIds']):
        ids = declaration['candidateBlockIds'][run_id]
        _ids(ids, 'whole_layout_blocks_required', nonempty=True)
        run = runs[run_id]
        page = next((p for p in run['pages'] if p['physicalPageNo'] == row['physicalPageNo']), None)
        _fail(page is not None, 'visual_page_capture_missing')
        raster_review = raster_reviews[run_id]
        _fields(raster_review, {'status', 'method', 'referenceRasterSha256', 'inputRasterSha256',
                               'originalPageTransformSha256', 'relationship'}, 'illegal_visual_raster_review')
        transform = page['mapping'].get('originalPageTransform')
        input_sha = page['inputArtifact']['sha256']; reference_sha = reference_page['sourceImage']['sha256']
        _fail(isinstance(transform, dict) and raster_review.get('status') == 'source_reviewed_declared'
              and raster_review.get('method') == 'original_page_raster_geometry_review'
              and raster_review.get('referenceRasterSha256') == reference_sha
              and raster_review.get('inputRasterSha256') == input_sha
              and raster_review.get('originalPageTransformSha256') == _hash(transform),
              'visual_raster_review_identity_mismatch')
        _fail((transform.get('kind') == 'identity' and input_sha == reference_sha
               and raster_review.get('relationship') == 'same_original_raster')
              or (transform.get('kind') == 'affine'
                  and raster_review.get('relationship') == 'source_reviewed_affine_to_reference_raster'),
              'same_dimensions_do_not_establish_original_raster_relationship')
        blocks = {b['blockId']: b for b in page['blocks']}
        for bid in ids:
            _fail(bid in blocks, 'visual_block_not_in_same_original_page')
            block = blocks[bid]
            _fail(allow_table or ('table' not in block and block['label'] not in {'table', 'table_body', 'table_cell'}),
                  'native_pdf_table_branch_not_supported')
            projected = project_bbox_to_original(run, page, block['bbox'])
            _fail(projected.get('status') == 'verified', 'visual_original_geometry_unknown')
            target = projected['targetFrame']; image = reference_page['sourceImage']
            _fail(target['coordinateUnit'] == 'pixel' and target['width'] == image['width'] and target['height'] == image['height'],
                  'visual_reference_raster_frame_mismatch')
            a, b = region['bboxNormalized'], projected['xyxyNormalized']
            _fail(min(a[2], b[2]) > max(a[0], b[0]) and min(a[3], b[3]) > max(a[1], b[1]), 'visual_source_region_disjoint')
            result.append({'runId': run_id, 'blockId': bid, 'physicalPageNo': row['physicalPageNo'],
                 'selectedLayout': run_id == declaration['selectedRunId'], 'wholeBlockSha256': _hash(block),
                 'actualCandidateTextSha256': _hash(block.get('text')), 'rawProvenance': copy.deepcopy(block['provenance']),
                 'originalGeometry': projected, 'rasterMappingReview': copy.deepcopy(raster_review),
                 'sourceRegionMappingStatus': 'source_reviewed_declared_box_overlap_not_exact_extent'})
    return result


def _native_text(row, glyphs):
    order = _ids(row.get('glyphIdsInReadingOrder'), 'reviewed_glyph_reading_order_required', nonempty=True)
    _fail(set(order) == set(row['capturedGlyphIds']), 'reviewed_glyph_membership_incomplete')
    separators = row.get('separators')
    _fail(isinstance(separators, list), 'typed_separators_required')
    by_pair, separator_ids = {}, set()
    for separator in separators:
        _fields(separator, {'separatorId', 'kind', 'text', 'afterGlyphId', 'beforeGlyphId', 'reviewMethod'}, 'illegal_native_pdf_separator')
        pair = (separator.get('afterGlyphId'), separator.get('beforeGlyphId'))
        sid = _name(separator.get('separatorId'), 'separatorId')
        _fail(separator.get('kind') == 'reviewed_separator' and separator.get('text') in {' ', '\n'}
              and separator.get('reviewMethod') == 'original_page_raster_boundary' and sid not in separator_ids
              and pair in list(zip(order, order[1:])) and pair not in by_pair, 'typed_separator_source_review_missing')
        separator_ids.add(sid); by_pair[pair] = separator
    text, spans, end = [], [], 0
    for index, gid in enumerate(order):
        glyph, raw_span, pointer = glyphs[gid]
        literal = '' if glyph['unicode'] is None else glyph['unicode']
        length = utf16_length(literal)
        spans.append({'kind': 'native_glyph', 'glyphId': gid, 'rawObservationPointer': pointer,
             'originalUtf16Span': copy.deepcopy(raw_span), 'startOffset': end, 'endOffset': end + length,
             'unicodeStatus': 'null_unicode_zero_span_unknown' if glyph['unicode'] is None else 'literal_observed',
             'effectiveVisibilityStatus': glyph['effectiveVisibilityStatus'], 'operatorVisibilityStatus': glyph['visibilityStatus']})
        text.append(literal); end += length
        pair = (gid, order[index + 1]) if index + 1 < len(order) else None
        if pair in by_pair:
            separator = by_pair[pair]; value = separator['text']; length = utf16_length(value)
            spans.append({'kind': 'reviewed_separator_not_glyph_observation', 'separator': copy.deepcopy(separator),
                          'startOffset': end, 'endOffset': end + length})
            text.append(value); end += length
    return ''.join(text), spans


def _regions(manifest, native, dual, reference, required):
    review = manifest.get('regionReview')
    _fields(review, {'schema', 'status', 'nativeBundleSha256', 'referenceSha256', 'regions', 'unadoptedGlyphs'}, 'illegal_native_pdf_region_review')
    _fail(review.get('schema') == REVIEW_SCHEMA and review.get('status') == 'source_reviewed_declared'
          and review.get('nativeBundleSha256') == native['bundleSha256']
          and review.get('referenceSha256') == manifest['reference']['evidenceSha256'], 'stale_native_pdf_region_review')
    observed, glyphs, order = {}, {}, {}
    for pi, page in enumerate(native['observation']['pageObservations']):
        physical = page['physicalPage']; observed[physical] = page
        for gi, (glyph, span) in enumerate(zip(page['nativePdfEvidence']['glyphs'], page['glyphOrderChannel']['spans'])):
            gid = glyph['id']; order[gid] = (physical, gi)
            glyphs[gid] = (glyph, span, '/pageObservations/' + str(pi) + '/nativePdfEvidence/glyphs/' + str(gi))
    ref_regions = {region['regionId']: region for region in reference['regions']}
    ref_pages = {page['pageId']: page for page in reference['pages']}
    table_regions = table_region_ids(manifest)
    table_members = {rid for group in manifest['tableGroups'] for rid in group['regionIds']}
    result, used = {}, set()
    rows = review.get('regions'); _fail(isinstance(rows, list) and rows, 'explicit_pdf_region_review_required')
    for row in rows:
        _fields(row, {'regionId', 'physicalPageNo', 'capturedGlyphIds', 'glyphIdsInReadingOrder', 'separators',
                      'nativeFrameSha256', 'reviewedAttributes', 'layout'}, 'illegal_native_pdf_region')
        rid = _name(row.get('regionId'), 'regionId'); physical = row.get('physicalPageNo')
        _fail(rid in ref_regions and rid not in result and type(physical) is int and physical in required, 'reviewed_pdf_region_missing')
        region = ref_regions[rid]; anchor = region['sourceAnchor']; page = observed[physical]
        _fail(anchor['physicalPageNo'] == physical and
              (rid in table_regions or region.get('role') not in {'table', 'cell', 'native_table', 'table_cell'})
              and (region.get('tableId') is None or rid in table_members), 'native_pdf_region_page_or_table_unsupported')
        _box(region.get('bboxNormalized'), 'source_region_measured_geometry_required')
        ids = _ids(row.get('capturedGlyphIds'), 'complete_native_region_members_required', nonempty=True)
        _fail(all(gid in glyphs and order[gid][0] == physical and gid not in used for gid in ids)
              and ids == sorted(ids, key=lambda gid: order[gid]), 'native_glyph_page_membership_or_capture_order_mismatch')
        _fail(row.get('nativeFrameSha256') == _hash(page['frame']), 'native_review_frame_mismatch')
        _fail(set(_ids(row.get('reviewedAttributes'), 'source_review_attributes_required')) == {'membership', 'readingOrder', 'text', 'geometry'},
              'source_review_attributes_required')
        _native_region_geometry(ids, glyphs, page, region, ref_pages[anchor['pageId']])
        text, spans = _native_text(row, glyphs)
        layout = _layout(row, region, ref_pages[anchor['pageId']], dual, allow_table=rid in table_regions)
        used.update(ids)
        result[rid] = {'blockId': 'native-pdf-region:' + _hash({'bundle': native['bundleSha256'], 'regionId': rid}),
             'referenceRegionId': rid, 'physicalPageNo': physical, 'text': text, 'startOffset': 0, 'endOffset': utf16_length(text),
             'anchor': 'pdf-physical/' + str(physical) + '/source-region/' + rid,
             'sourceSha256': native['source']['sourceSha256'], 'rawObservationSha256': native['rawObservation']['sha256'],
             'sourcePosition': {'kind': 'pdf_native_source_reviewed_region', 'physicalPageNo': physical,
                  'sourceSha256': native['source']['sourceSha256'], 'sourceFrame': copy.deepcopy(page['frame'])},
             'bbox': copy.deepcopy(region['bboxNormalized']), 'bboxConvention': 'source_reviewed_original_raster_normalized_xyxy',
             'sourceReviewAnchor': copy.deepcopy(anchor), 'capturedGlyphIds': copy.deepcopy(ids), 'nativeTextSpans': spans,
             'visualLayoutParts': layout, 'geometryStatus': 'source_reviewed_region_and_declared_visual_transform_not_exact_glyph',
             'nativeEffectiveVisibility': 'unknown_not_raster_verified', 'apTextAdoption': 'not_supported'}
    unadopted = review.get('unadoptedGlyphs'); _fail(isinstance(unadopted, list), 'explicit_unadopted_glyph_inventory_required')
    _fail(len(unadopted) == len(observed)
          and all(isinstance(r, dict) and type(r.get('physicalPageNo')) is int for r in unadopted)
          and [r['physicalPageNo'] for r in unadopted] == list(observed), 'unadopted_page_inventory_incomplete')
    for row in unadopted:
        _fields(row, {'physicalPageNo', 'glyphIds', 'status'}, 'illegal_unadopted_glyph_declaration')
        ids = _ids(row.get('glyphIds'), 'invalid_unadopted_glyph_ids')
        _fail(row.get('status') == 'unadopted_unknown' and all(gid in glyphs and order[gid][0] == row['physicalPageNo']
              and gid not in used for gid in ids) and ids == sorted(ids, key=lambda gid: order[gid]),
              'unadopted_glyph_membership_mismatch')
        used.update(ids)
    _fail(used == set(glyphs), 'full_observed_glyph_inventory_not_accounted')
    return result


def _units(manifest, parts, reference, table_groups):
    rows = manifest.get('units'); _fail(isinstance(rows, list) and rows, 'explicit_whole_text_units_required')
    positions = {rid: (page['sourceAnchor']['physicalPageNo'], index) for page in reference['pages']
                 for index, rid in enumerate(page['regionIdsInReadingOrder'])}
    units, owners = [], {}
    for row in rows:
        _fields(row, {'unitId', 'scope', 'regionIds', 'partSeparator', 'clause', 'tableGroupId'}, 'illegal_native_pdf_whole_unit')
        uid = _name(row.get('unitId'), 'unitId'); ids = _ids(row.get('regionIds'), 'whole_unit_regions_required', nonempty=True)
        table_group = table_groups.get(row.get('tableGroupId'))
        _fail((row.get('scope') == 'whole_text_unit' and 'tableGroupId' not in row
               or row.get('scope') == 'whole_table_group' and table_group is not None and ids == table_group['regionIds'])
              and uid not in {u['unitId'] for u in units}
              and all(rid in parts and rid not in owners for rid in ids), 'native_pdf_unit_membership_incomplete_or_duplicate')
        _fail(ids == sorted(ids, key=lambda rid: positions[rid]), 'whole_unit_source_region_reading_order_mismatch')
        _fail(row.get('partSeparator') == {'kind': 'reviewed_region_boundary', 'text': '\n'}, 'typed_region_separator_required')
        clause = row.get('clause')
        if clause is not None:
            _fields(clause, {'clauseId', 'headingRegionId'}, 'illegal_native_pdf_clause')
            _name(clause.get('clauseId'), 'clauseId')
            _fail(clause.get('headingRegionId') in ids and next(r for r in reference['regions']
                  if r['regionId'] == clause['headingRegionId']).get('clauseId') == clause['clauseId'], 'native_pdf_clause_source_review_missing')
        for rid in ids: owners[rid] = uid
        unit = {**copy.deepcopy(row), 'parts': [copy.deepcopy(parts[rid]) for rid in ids],
                'physicalPages': sorted({parts[rid]['physicalPageNo'] for rid in ids})}
        if table_group is not None: unit['nativeTables'] = [copy.deepcopy(table_group)]
        units.append(unit)
    _fail({u.get('tableGroupId') for u in units if u.get('tableGroupId')} == set(table_groups),
          'native_pdf_table_group_not_wholly_adopted')
    table_members = {rid for group in table_groups.values() for rid in group['regionIds']}
    _fail(all(u['scope'] == 'whole_table_group' or not table_members.intersection(u['regionIds']) for u in units),
          'native_pdf_table_member_adopted_as_prose')
    _fail(set(owners) == set(parts), 'reviewed_native_region_not_whole_unit_adopted')
    _fail([positions[u['regionIds'][0]] for u in units] == sorted(positions[u['regionIds'][0]] for u in units), 'unit_source_order_mismatch')
    required_relations = [i for i, r in enumerate(reference.get('relations', []))
                          if r['fromRegionId'] in owners or r['toRegionId'] in owners]
    declared_indices = manifest.get('sourceRelationIndices')
    _fail(isinstance(declared_indices, list) and all(type(i) is int for i in declared_indices)
          and declared_indices == required_relations, 'source_review_relations_incomplete')
    relations, parents, continuations, predecessors, relation_keys = [], {}, {}, {}, set()
    for index in required_relations:
        relation = reference['relations'][index]; left, right = relation['fromRegionId'], relation['toRegionId']
        _fail(relation['type'] in RELATIONS and left in owners and right in owners, 'whole_unit_parent_qualifier_continuation_not_closed')
        _fail(relation['type'] not in {'continuation', 'qualifier'} or owners[left] == owners[right],
              'continuation_or_qualifier_split_across_whole_units')
        key = (relation['type'], left, right)
        _fail(key not in relation_keys, 'duplicate_native_pdf_relation'); relation_keys.add(key)
        if relation['type'] == 'continuation':
            _fail(positions[left] < positions[right] and left not in continuations and right not in predecessors,
                  'cyclic_or_ambiguous_native_pdf_continuation')
            continuations[left] = right; predecessors[right] = left
        if relation['type'] == 'parent':
            _fail(left not in parents, 'ambiguous_native_pdf_parent'); parents[left] = right
        relations.append({**copy.deepcopy(relation), 'fromUnitId': owners[left], 'toUnitId': owners[right],
              'referenceRelationIndex': index, 'status': 'source_reviewed_declared',
              'scope': 'within_whole_unit' if owners[left] == owners[right] else 'between_whole_units'})
    for seed in parents:
        cursor, seen = seed, set()
        while cursor in parents:
            _fail(cursor not in seen, 'cyclic_native_pdf_parent'); seen.add(cursor); cursor = parents[cursor]
    return units, relations


def _assemble(native, dual, manifest, descriptors, store, *, replay_identity=None, _validation_replay=False):
    _fail(isinstance(store, ArtifactStore), 'actual_artifact_store_required')
    native_validation = validate_native_pdf_bundle(native, artifact_store=store)
    pages, quality = _scope(manifest, native)
    _fail(manifest.get('dualBundleSha256') == dual['bundleSha256'], 'stale_native_pdf_visual_adoption')
    dual_validation = _dual(dual, native, pages, store)
    reference, reference_validation = _reference(manifest, native, pages, store)
    parts = _regions(manifest, native, dual, reference, pages)
    table_groups = project_table_groups(manifest, parts, reference, native)
    assets = project_page_assets(manifest, reference, store)
    units, relations = _units(manifest, parts, reference, table_groups)
    version = TABLE_PROJECTION_VERSION if table_groups or assets else VERSION
    if replay_identity is not None:
        _fail(isinstance(replay_identity, dict)
              and replay_identity.get('projectionVersion') in VERSION_DEPENDENCIES,
              'unsupported_native_pdf_retained_producer_identity')
        version = replay_identity['projectionVersion']
        _fail(not (table_groups or assets) or version == TABLE_PROJECTION_VERSION,
              'native_pdf_table_adoption_requires_table_recipe_version')
    dependency_names = VERSION_DEPENDENCIES[version]
    identity = {'projectionVersion': version,
        'nativeBundleSha256': native['bundleSha256'], 'dualBundleSha256': dual['bundleSha256'],
        'referenceSha256': manifest['reference']['evidenceSha256'], 'adoptionManifestSha256': descriptors['adoption']['sha256'],
        'sourceSha256': native['source']['sourceSha256'], 'sourceRevisionSha256': native['source']['sourceRevisionSha256'],
        'dualSourceRevisionSha256': dual['source']['sourceRevisionSha256'], 'scopeSha256': _hash(manifest['scope']),
        'qualitySha256': _hash(quality), **{k: copy.deepcopy(manifest[k]) for k in
          ('documentId', 'role', 'module', 'eligibleModules', 'sourcePurpose', 'sourceClass')}}
    if replay_identity is None:
        identity.update(projectionCodeSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            projectionDependencySha256={name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                                       for name in dependency_names})
    else:
        # Known-recipe replay preserves opaque producer/dependency identities.
        # They are provenance, not reader bytes or authenticity certification;
        # every input and derived field is still cold-reconstructed above/below.
        _fail(isinstance(replay_identity, dict)
              and set(replay_identity) == set(identity) | {'projectionCodeSha256', 'projectionDependencySha256'}
              and replay_identity.get('projectionVersion') == version,
              'unsupported_native_pdf_retained_producer_identity')
        dependencies = replay_identity['projectionDependencySha256']
        _fail(isinstance(dependencies, dict) and set(dependencies) == set(dependency_names),
              'native_pdf_retained_dependency_identity_shape')
        identity.update(projectionCodeSha256=_sha(replay_identity['projectionCodeSha256'], 'projectionCodeSha256'),
                        projectionDependencySha256={name: _sha(dependencies[name], name)
                                                    for name in dependency_names})
        _fail(identity == replay_identity, 'native_pdf_retained_producer_identity_mismatch')
    canonical = {'units': units, 'relations': relations, 'assetRefs': assets}
    source_hash = _hash({'projectionIdentity': identity, 'canonical': canonical})
    revision = _hash({'sourceHash': source_hash, 'projectionIdentity': identity})
    chunks = []
    for ordinal, unit in enumerate(units):
        chunk = {'id': 'canonical-native-pdf:' + _hash({'revision': revision, 'unitId': unit['unitId']}),
            'unitId': unit['unitId'], 'content': '\n'.join(part['text'] for part in unit['parts']),
            'parts': copy.deepcopy(unit['parts']), 'physicalPages': unit['physicalPages'], 'pageNo': unit['physicalPages'][0],
            'anchor': unit['parts'][0]['anchor'], 'sourceOrder': ordinal, 'canonicalScope': unit['scope'],
            'sourceHash': source_hash, 'projectionRevisionSha256': revision, 'projectionVersion': version,
            'metadataVersion': version, 'segmentationVersion': 'whole-source-reviewed-pdf-region-units-v1',
            'nativeTableMetadataVersion': TABLE_VERSION if unit.get('nativeTables') else 'native-pdf-no-table-adoption-v1',
            'sourceQualityMetadataVersion': 'native-pdf-observation-and-source-review-unknown-v1',
            'sourceQualityHash': identity['qualitySha256'], 'sourceQuality': copy.deepcopy(quality),
            'sourceSpans': [{'blockId': p['blockId'], 'startOffset': p['startOffset'], 'endOffset': p['endOffset'],
                'sourcePosition': p['sourcePosition'], 'nativeTextSpans': p['nativeTextSpans']} for p in unit['parts']],
            'adoption': {k: copy.deepcopy(v) for k, v in unit.items() if k != 'parts'},
            'relations': [r for r in relations if unit['unitId'] in (r['fromUnitId'], r['toUnitId'])],
            'textAssembly': copy.deepcopy(unit['partSeparator']),
            'assetRefs': [copy.deepcopy(asset) for asset in assets if asset['physicalPageNo'] in unit['physicalPages']], 'humanConfirmed': False,
            'originalSourceSha256': native['source']['sourceSha256'], **copy.deepcopy(identity)}
        if unit.get('clause'):
            chunk['clauseId'] = unit['clause']['clauseId']
            chunk['clauseHeadingLocation'] = parts[unit['clause']['headingRegionId']]['anchor']
        if unit.get('nativeTables'): chunk['nativeTables'] = copy.deepcopy(unit['nativeTables'])
        paragraph_map(chunk); chunks.append(chunk)
    observed = native['observation']['selectedPhysicalPages']
    result = {'schema': SCHEMA, 'producerKind': 'native_pdf', 'storageShape': STORAGE_SHAPE,
        'projectionIdentity': identity, 'projectionRevisionSha256': revision, 'sourceHash': source_hash,
        'source': copy.deepcopy(native['source']), 'evidenceDescriptors': copy.deepcopy(descriptors),
        # The validator just strictly decoded these two inputs from actual CAS.
        # Its expected projection is discarded after full equality; no caller
        # owns these roots. Normal producer outputs retain defensive copies.
        'nativeObservationBundle': native if _validation_replay else copy.deepcopy(native),
        'dualParserBundle': dual if _validation_replay else copy.deepcopy(dual),
        'sourceReference': reference, 'manifest': copy.deepcopy(manifest), 'canonical': canonical, 'chunks': chunks,
        'assetRefs': assets, 'captureCompleteness': {'native': native_validation, 'dual': dual_validation, 'reference': reference_validation},
        'unadoptedGlyphs': copy.deepcopy(manifest['regionReview']['unadoptedGlyphs']),
        'unobservedOriginalPhysicalPages': [p for p in range(1, native['source']['pageCount'] + 1) if p not in observed],
        'semanticScopeVerified': False, 'humanConfirmed': False, 'wholeOriginalSourceCoverage': 'unknown',
        'limitations': ['Only explicitly adopted native prose regions enter the text index; unadopted regions remain unknown',
            'Literal glyph capture is not independently replayed against PDF operators; effective visibility and legal meaning remain unknown',
            'Reading order and typed separators are explicit source-review declarations, never raw operator observations',
            'Existing forms/AP and styles remain in complete raw CAS evidence; AP-to-page text, native tables and cells are unsupported',
            'Native glyph geometry is derived from actual page frames, not independently pixel-verified raster visibility',
            'Internal full-evidence snapshot is not a public transport or a claim of deployed business acceptance']}
    if version == TABLE_PROJECTION_VERSION:
        result['limitations'][0] = 'Only explicitly source-reviewed whole units enter this candidate projection; unadopted regions remain unknown'
        result['limitations'][3] = 'Table topology and blanks require complete source review; provider grids do not prove complete source coverage and AP text remains unsupported'
    result['projectionSha256'] = _hash(result)
    return result


def produce_native_pdf(native_bundle, dual_bundle, explicit_adoption, *, artifact_store):
    def run():
        _fail(isinstance(artifact_store, ArtifactStore), 'actual_artifact_store_required')
        descriptors = {key: artifact_store.put_bytes(_bytes(value), identity_scope='canonical_json_object') for key, value in
                       (('nativeBundle', native_bundle), ('dualBundle', dual_bundle), ('adoption', explicit_adoption))}
        result = _assemble(native_bundle, dual_bundle, copy.deepcopy(explicit_adoption), descriptors, artifact_store)
        return validate_native_pdf_projection(result, artifact_store=artifact_store)
    return _boundary(run, 'malformed_native_pdf_projection_input')


def validate_native_pdf_projection(projection, *, artifact_store):
    def run():
        _fail(isinstance(artifact_store, ArtifactStore) and isinstance(projection, dict) and projection.get('schema') == SCHEMA
              and projection.get('producerKind') == 'native_pdf' and projection.get('storageShape') == STORAGE_SHAPE,
              'separate_native_pdf_internal_projection_required')
        _fail(projection.get('projectionSha256') == _hash({k: v for k, v in projection.items() if k != 'projectionSha256'}),
              'native_pdf_projection_seal_mismatch')
        descriptors = projection['evidenceDescriptors']
        _fields(descriptors, {'nativeBundle', 'dualBundle', 'adoption'}, 'native_pdf_input_cas_required')
        native, dual, adoption = [_json(artifact_store.read_asset(descriptors[key])) for key in ('nativeBundle', 'dualBundle', 'adoption')]
        _fail(native == projection['nativeObservationBundle'] and dual == projection['dualParserBundle'] and adoption == projection['manifest'],
              'native_pdf_retained_input_differs_from_actual_cas')
        _fail(isinstance(projection.get('projectionIdentity'), dict), 'native_pdf_retained_producer_identity_required')
        expected = _assemble(native, dual, adoption, descriptors, artifact_store,
                             replay_identity=projection['projectionIdentity'], _validation_replay=True)
        _fail(expected == projection, 'native_pdf_derived_payload_mismatch')
        return projection
    return _boundary(run, 'malformed_native_pdf_projection_snapshot')
