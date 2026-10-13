"""Cold capture/validation of actual Java PDFBox observations, never OCR adoption.

The original PDF, full raw observer output and all named observer source files
are actual CAS bytes. pypdf independently checks original page frames; glyphs,
forms and existing AP channels retain the observer's complete literal evidence.
This does not independently replay PDFBox operators, certify raster visibility,
infer reading order, adopt source regions or change the native DOCX contract.
"""
from __future__ import annotations

import base64
import copy
import hashlib
import io
import json
import math
import re
import struct

from artifact_store import ArtifactStore, ArtifactStoreError

EXPORT_SCHEMA = 'consense.native-pdf-observation.v1'
BUNDLE_SCHEMA = 'consense.native-pdf-observation-bundle.v1'
PDF_MEDIA = 'application/pdf'
PRODUCER = 'native_pdf'
CHANNEL = 'observedGlyphUnicodeAll'
ORDERING = 'operator_capture_order_not_reading_order'
AP_COORDINATES = 'appearance_stream_local_matrix_no_page_box'
FRAME_CONVENTION = 'pdf_default_user_space_xywh_unscaled_user_unit'
OBSERVER_SOURCES = {'OerNativePdfObservation.java', 'PdfNativeSupport.java', 'PdfPageEvidence.java'}
FRAME_READER_VERSION = '6.19.0'  # Existing requirements.txt pin; no fallback PDF engine.


class NativePdfObservationError(ValueError):
    """Unsupported/stale native PDF bytes, observer shape or literal relation."""


def _require(condition, message):
    if not condition: raise NativePdfObservationError(message)


def _bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'),
                      allow_nan=False).encode('utf-8')


def _sha(value): return hashlib.sha256(value).hexdigest()


def _utf16(value): return len(value.encode('utf-16-le')) // 2


def _strict_json(raw):
    _require(isinstance(raw, bytes), 'Raw PDF observer output must be actual bytes')
    def pairs(values):
        result = {}
        for k, v in values:
            _require(k not in result, 'Duplicate observer JSON key: ' + k)
            result[k] = v
        return result
    try:
        result = json.loads(raw.decode('utf-8-sig'), object_pairs_hook=pairs,
                            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
        _bytes(result)
        return result
    except NativePdfObservationError: raise
    except (ValueError, TypeError, UnicodeError, OverflowError) as error:
        raise NativePdfObservationError('One finite strict UTF-8 observer JSON value required') from error


def _finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def _vector(value, count, label, *, optional=False):
    if optional and value is None: return
    _require(isinstance(value, list) and len(value) == count and all(_finite(x) for x in value),
             label + ' must retain a finite numeric vector')


def _sha_string(value, label):
    _require(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value), label+' must be lowercase SHA-256')


def _f32(value): return struct.unpack('>f', struct.pack('>f', float(value)))[0]


def _source_frames(source_bytes, selected):
    """Read actual PDF page tree; convert COS coordinates with PDFBox float arithmetic."""
    import pypdf
    from pypdf import PdfReader
    _require(pypdf.__version__ == FRAME_READER_VERSION, 'Cold frame validation requires declared pypdf=='+FRAME_READER_VERSION)
    reader = PdfReader(io.BytesIO(source_bytes), strict=True)
    # PDFBox and pypdf can observe an existing empty-user-password document.
    # Preserve original encryption/extraction flags; do not relabel access as unrestricted.
    encrypted = reader.is_encrypted
    extraction_permission = not encrypted or bool(int(reader.user_access_permissions) & 16)
    frames = {}
    for physical in selected:
        page = reader.pages[physical-1]
        media = [_f32(x) for x in page.mediabox]
        crop = [_f32(x) for x in page.cropbox]
        # PDFBox effective crop is the intersection of inherited crop/media boxes.
        crop = [max(media[0],crop[0]),max(media[1],crop[1]),min(media[2],crop[2]),min(media[3],crop[3])]
        xywh = lambda b: [b[0],b[1],_f32(b[2]-b[0]),_f32(b[3]-b[1])]
        rotation = int(page.get('/Rotate', 0))
        rotation = rotation % 360 if rotation % 90 == 0 else 0
        user_unit = _f32(page.get('/UserUnit', 1))
        if user_unit <= 0: user_unit = 1.0
        frames[physical] = {'mediaBox':xywh(media),'cropBox':xywh(crop),
                            'rotation':rotation,'userUnit':user_unit}
    return len(reader.pages), frames, {'encrypted':encrypted,'extractionPermission':extraction_permission}


def _frame_mapping(frame):
    x,y,width,height = frame['cropBox']; rotation = frame['rotation'] % 360; unit = frame['userUnit']
    matrices = {0:[1,0,0,-1,-x,height+y],90:[0,1,1,0,-y,-x],
                180:[-1,0,0,1,width+x,-y],270:[0,-1,-1,0,height+y,width+x]}
    rw,rh = (width,height) if rotation % 180 == 0 else (height,width)
    _require(frame.get('rasterDpi') == 'not_invoked' and frame.get('actualRasterTransformStatus') == 'unknown_no_raster_produced'
             and frame.get('matrixConvention') == 'a_b_c_d_e_f_xprime_a_x_plus_c_y_plus_e_yprime_b_x_plus_d_y_plus_f'
             and frame.get('glyphBboxConvention') == 'normalized_rendered_crop_top_left_xywh_pdfbox_derived_not_pixel_verified'
             and frame.get('frameObservation') == 'effective_PDFBox_page_values_original_dictionary_preserved_in_source_bytes',
             'Page-frame math cannot certify actual raster mapping')
    if rotation not in matrices or rw <= 0 or rh <= 0 or unit <= 0:
        _require(frame.get('rawToRenderedCropNormalized') is None and frame.get('rawToRenderedCropPoints72') is None
                 and frame.get('transformStatus') == 'unknown_unsupported_or_invalid_frame', 'Invalid frame transform remains unknown')
        return
    matrix = matrices[rotation]
    expected = {'rawToRenderedCropNormalized':[v/(rw if i%2 == 0 else rh) for i,v in enumerate(matrix)],
                'rawToRenderedCropPoints72':[v*unit for v in matrix],'renderedCropSizePoints72':[rw*unit,rh*unit]}
    for key,values in expected.items():
        _vector(frame.get(key),len(values),'frame.'+key)
        _require(all(math.isclose(a,b,rel_tol=1e-12,abs_tol=1e-12) for a,b in zip(frame[key],values)), 'Derived raw/page-frame transform changed: '+key)
    _require(frame.get('transformStatus') == 'derived_page_frame_mapping_not_raster_verified', 'Derived mapping cannot become raster-verified')


def _channel(channel, glyphs, *, label, appearance=False):
    _require(isinstance(channel, dict) and channel.get('textChannel') == CHANNEL
             and channel.get('offsetUnit') == 'UTF16' and channel.get('orderingStatus') == ORDERING,
             label+' must retain explicit glyph capture order, not inferred reading order')
    spans = channel.get('spans')
    _require(isinstance(spans,list) and len(spans) == len(glyphs), label+' must include every glyph including zero-length observations')
    end, text = 0, []
    for glyph, span in zip(glyphs,spans):
        unicode = glyph.get('unicode')
        _require(unicode is None or isinstance(unicode,str), label+' glyph Unicode must be literal or null')
        observed = '' if unicode is None else unicode
        expected_end = end + _utf16(observed)
        _require(isinstance(span,dict) and type(span.get('start')) is int and type(span.get('end')) is int
                 and span['start'] == end and span['end'] == expected_end and span.get('glyphId') == glyph['id'],
                 label+' has a missing/reordered glyph or altered UTF16 range')
        end = expected_end; text.append(observed)
    _require(channel.get('text') == ''.join(text), label+' text cannot be normalized, OCR-replaced or omit hidden glyphs')
    _require(channel.get('nullUnicodeObserved') is any(g.get('unicode') is None for g in glyphs)
             and channel.get('nullUnicodeConvention') == 'null_unicode_retained_on_glyph_zero_length_span_no_invented_character',
             label+' must retain null-Unicode observation and zero-length convention')


def _glyph(glyph, ids, *, label, appearance=False):
    _require(isinstance(glyph,dict) and isinstance(glyph.get('id'),str) and glyph['id'] and glyph['id'] not in ids,
             label+' glyph IDs must be unique')
    ids.add(glyph['id'])
    _require('unicode' in glyph and (glyph['unicode'] is None or isinstance(glyph['unicode'],str)), label+' literal Unicode is required')
    _require(isinstance(glyph.get('characterCodes'),list) and all(type(v) is int and v >= 0 for v in glyph['characterCodes']),
             label+' character codes must be retained without string conversion')
    _require('fontName' in glyph and (glyph['fontName'] is None or isinstance(glyph['fontName'],str)), label+' original font observation required')
    _vector(glyph.get('textMatrix'),6,label+'.textMatrix')
    _vector(glyph.get('currentTransformationMatrix'),6,label+'.currentTransformationMatrix')
    _vector(glyph.get('pdfboxPosition'),10,label+'.pdfboxPosition')
    _vector(glyph.get('clippingBoundsPdf'),4,label+'.clippingBoundsPdf')
    for key in ['fillColorComponents','strokeColorComponents']:
        _require(isinstance(glyph.get(key),list) and all(_finite(v) for v in glyph[key]), label+' raw color components required')
    for key in ['fontSize','fontSizeInPt','xscale','yscale','widthOfSpace','direction','textStateFontSize',
                'characterSpacing','wordSpacing','horizontalScaling','leading','rise','lineWidth']:
        _require(_finite(glyph.get(key)), label+' literal text/graphics state required: '+key)
    for key in ['fillColorSpace','strokeColorSpace','fontSubtype']:
        _require(key in glyph and (glyph[key] is None or isinstance(glyph[key],str)), label+' raw style field required: '+key)
    _require('fontEmbedded' in glyph and (glyph['fontEmbedded'] is None or type(glyph['fontEmbedded']) is bool), label+' raw fontEmbedded state required')
    _require(glyph.get('effectiveVisibilityStatus') == 'unknown_not_raster_verified'
             and isinstance(glyph.get('limitations'),list) and glyph['limitations'], label+' cannot promote raw style/clip to effective visibility')
    _require('bbox' in glyph, label+' raw bbox observation is required, including explicit null')
    _vector(glyph['bbox'],4,label+'.bbox',optional=True)
    _require(type(glyph.get('renderingMode')) is int and 0 <= glyph['renderingMode'] <= 7
             and _finite(glyph.get('fillAlpha')) and _finite(glyph.get('strokeAlpha'))
             and type(glyph.get('eligibleForNativeText')) is bool, label+' literal operator/style state required')
    mode = glyph['renderingMode']; painted = mode not in (3,7)
    fill, stroke = mode in (0,2,4,6), mode in (1,2,5,6)
    opaque = (fill and glyph['fillAlpha'] > 0) or (stroke and glyph['strokeAlpha'] > 0)
    status = glyph.get('visibilityStatus')
    _require(status in {'hidden_rendering_mode','hidden_zero_alpha','outside_crop','operator_visible_unverified'},
             label+' cannot promote operator evidence to verified visibility')
    _require((status == 'operator_visible_unverified') == glyph['eligibleForNativeText'], label+' eligibility/visibility observation differs')
    if not painted: _require(status == 'hidden_rendering_mode', label+' hidden render mode cannot become native eligible')
    elif not opaque: _require(status == 'hidden_zero_alpha', label+' zero-alpha paint cannot become native eligible')
    if appearance:
        _require(glyph.get('coordinateSpace') == AP_COORDINATES and glyph.get('bbox') is None,
                 label+' AP glyph has no verified original-page box')
    else:
        _require(glyph.get('coordinateSpace') == 'normalized_rendered_crop_top_left', label+' page glyph coordinate convention required')


def _java_trim(value): return re.sub(r'^[\x00-\x20]+|[\x00-\x20]+$', '', value)


def _forms(forms, physical, source_count, glyph_ids, form_ids, *, label):
    _require(isinstance(forms,list), label+' complete form inventory array required')
    for form in forms:
        _require(isinstance(form,dict) and isinstance(form.get('id'),str) and form['id'] and form['id'] not in form_ids,
                 label+' field/widget identities must be unique')
        form_ids.add(form['id'])
        page = form.get('pageNo')
        _require(page is None or type(page) is int and 1 <= page <= source_count, label+' physical page must be actual or unknown')
        if physical is not None: _require(page == physical, label+' widget page owner differs')
        for key in ['fieldName','fieldType','fieldValue','appearanceValue','appearanceState']:
            _require(key in form and (form[key] is None or isinstance(form[key],str)), label+' retains independent field/AP value: '+key)
        for key in ['needAppearances','appearancePresent','conflict']:
            _require(type(form.get(key)) is bool, label+' boolean form state required: '+key)
        for key in ['bbox','rectanglePdf','appearanceBbox']:
            _require(key in form, label+' explicit null/numeric form geometry required: '+key)
            _vector(form.get(key),4,label+'.'+key,optional=True)
        _require('appearanceMatrix' in form and 'pageNo' in form, label+' explicit AP matrix/page relation required')
        _vector(form.get('appearanceMatrix'),6,label+'.appearanceMatrix',optional=True)
        _require('annotationFlags' in form and (type(form['annotationFlags']) is int and form['annotationFlags'] >= 0
                 or form['annotationFlags'] is None and form.get('visibilityStatus') == 'widget_missing'),
                 label+' actual widget annotation flags required')
        _require(form.get('visibilityStatus') in {'widget_missing','hidden_widget','widget_page_unknown','operator_visible_unverified'}
                 and form.get('valueStatus') in {'field_appearance_conflict','not_visible','appearance_missing','widget_page_unknown','empty_or_non_text_appearance','appearance_text_unverified','appearance_observation_partial_unknown'},
                 label+' form/AP effective display remains unverified')
        widget_present = form['annotationFlags'] is not None
        widget_visible = widget_present and not (form['annotationFlags'] & 35)  # invisible/hidden/no-view
        visibility = ('widget_missing' if not widget_present else 'hidden_widget' if not widget_visible
                      else 'widget_page_unknown' if page is None else 'operator_visible_unverified')
        _require(form['visibilityStatus'] == visibility, label+' widget flags/page visibility observation differs')
        ap_glyphs = form.get('appearanceGlyphs')
        _require(isinstance(ap_glyphs,list), label+' AP glyphs must be retained')
        for glyph in ap_glyphs: _glyph(glyph,glyph_ids,label=label+'.'+form['id'],appearance=True)
        _channel(form.get('appearanceGlyphOrderChannel'),ap_glyphs,label=label+'.AP',appearance=True)
        _require(form.get('appearanceCoordinateStatus') == 'stream_local_no_established_widget_page_transform',
                 label+' cannot infer AP original-page transform')
        _require(form.get('appearanceStreamObservationStatus') in {'not_present','existing_normal_AP_bytes_observed','partial_existing_normal_AP_bytes_failure'},
                 label+' existing AP stream status must be explicit')
        ap_status = form.get('appearanceGlyphObservationStatus')
        _require(ap_status in {'not_present','observed_existing_normal_AP_operators_unreviewed',
                              'partial_existing_normal_AP_operator_failure','not_observed_widget_page_unknown'},
                 label+' AP glyph observation status must preserve failure/unknown')
        if not form['appearancePresent']:
            _require(ap_status == form['appearanceStreamObservationStatus'] == 'not_present' and not ap_glyphs,
                     label+' absent AP cannot be observed or gain fabricated glyphs')
        elif page is None:
            _require(ap_status == 'not_observed_widget_page_unknown' and not ap_glyphs and form['bbox'] is None,
                     label+' unknown widget page/AP glyph mapping remains unobserved')
        else:
            _require(ap_status in {'observed_existing_normal_AP_operators_unreviewed','partial_existing_normal_AP_operator_failure'},
                     label+' existing assigned AP needs explicit actual/partial operator observation')
        for key in ['appearanceRawStreamBytesBase64','appearanceDecodedStreamBytesBase64']:
            _require(key in form and (form[key] is None or isinstance(form[key],str)), label+' existing AP raw/decoded bytes required')
            if form[key] is not None: base64.b64decode(form[key],validate=True)
        stream_status = form['appearanceStreamObservationStatus']
        if stream_status == 'existing_normal_AP_bytes_observed':
            _require(form['appearancePresent'] and all(form[k] is not None for k in
                     ['appearanceRawStreamBytesBase64','appearanceDecodedStreamBytesBase64']),
                     label+' complete existing AP raw/decoded capture requires actual bytes')
        elif stream_status == 'not_present':
            _require(not form['appearancePresent'] and all(form[k] is None for k in
                     ['appearanceRawStreamBytesBase64','appearanceDecodedStreamBytesBase64']),
                     label+' absent AP cannot gain synthesized raw/decoded streams')
        else:
            _require(form['appearancePresent'], label+' partial existing AP capture requires an actual existing AP')
        # Existing Java StringBuilder appends the literal string "null" for a null
        # Unicode position in its legacy eligible AP channel. The independent
        # all-glyph channel above retains null with a zero span, no invented char.
        expected_value = _java_trim(''.join('null' if g['unicode'] is None else g['unicode']
                                          for g in ap_glyphs if g['eligibleForNativeText']))
        _require(form['appearanceValue'] == expected_value or form['appearanceValue'] is None and not ap_glyphs,
                 label+' legacy eligible AP text must stay separate from all-glyph channel')
        compare = lambda t: re.sub(r'\s+', ' ', _java_trim(t or ''), flags=re.ASCII)
        conflict = form['appearancePresent'] and bool(compare(form['appearanceValue'])) and compare(form['fieldValue']) != compare(form['appearanceValue'])
        _require(form['conflict'] == conflict, label+' stored value/AP conflict observation differs')
        partial_ap = stream_status.startswith('partial_') or ap_status.startswith('partial_')
        expected_status = ('appearance_observation_partial_unknown' if partial_ap else 'field_appearance_conflict' if conflict
                           else 'not_visible' if not widget_visible else 'appearance_missing' if not form['appearancePresent']
                           else 'widget_page_unknown' if page is None else 'empty_or_non_text_appearance' if not compare(form['appearanceValue'])
                           else 'appearance_text_unverified')
        _require(form['valueStatus'] == expected_status, label+' partial/unknown AP cannot be promoted to a complete value')
        _require(isinstance(form.get('limitations'),list) and form['limitations'], label+' form/AP limitations required')


def validate_native_pdf_export(export, source_bytes, observer_sources):
    """Validate bound actual bytes/frames/relations; not PDFBox execution attestation."""
    try: return _validate_native_pdf_export(export,source_bytes,observer_sources)
    except NativePdfObservationError: raise
    except Exception as error:
        raise NativePdfObservationError('Invalid native PDF observer shape, source or frame: '+type(error).__name__) from error


def _validate_native_pdf_export(export, source_bytes, observer_sources):
    _require(isinstance(export,dict) and export.get('schema') == EXPORT_SCHEMA and export.get('producerKind') == PRODUCER,
             'Separate native_pdf observer schema required; DOCX/OCR aliases are not accepted')
    _require(isinstance(source_bytes,bytes) and source_bytes.startswith(b'%PDF-'), 'Actual original PDF bytes required')
    _require(isinstance(observer_sources,dict) and set(observer_sources) == OBSERVER_SOURCES
             and all(isinstance(v,bytes) and v for v in observer_sources.values()), 'Complete actual named observer source bytes required')
    source, observer = export.get('source'),export.get('observerIdentity')
    _require(isinstance(source,dict) and source.get('mediaType') == PDF_MEDIA
             and source.get('sourceSha256') == _sha(source_bytes) and source.get('byteCount') == len(source_bytes)
             and isinstance(source.get('filename'),str) and source['filename'].strip(), 'Original PDF identity is missing/stale')
    supplied = export.get('sourceArtifact')
    _require(isinstance(supplied,dict) and supplied.get('sha256') == _sha(source_bytes)
             and supplied.get('byteCount') == len(source_bytes)
             and supplied.get('name') == source['filename'] and supplied.get('mediaType') == PDF_MEDIA
             and base64.b64decode(supplied['bytesBase64'],validate=True) == source_bytes,
             'Retained original PDF artifact differs from actual supplied bytes')
    _require(isinstance(observer,dict) and observer.get('observerVersion') == 'pdfbox-native-observation-1'
             and observer.get('adapter') == 'com.consense.document.OerNativePdfObservation'
             and isinstance(observer.get('dependencyVersions'),dict) and observer['dependencyVersions']
             and isinstance(observer.get('config'),dict), 'Resolved observer/source/dependency identity required')
    _require(all(isinstance(observer['dependencyVersions'].get(k),str) and observer['dependencyVersions'][k]
                 for k in ['apachePdfBox','apacheFontBox','pdfBoxFontMapperClass','jacksonDatabind','javaRuntime']),
             'Actual PDFBox/FontBox/font mapper/runtime identity must be retained')
    _require(observer.get('sourceCodeSha256') == {k:_sha(v) for k,v in observer_sources.items()}, 'Observer source-code identity mismatch')
    _require(observer.get('configSha256') == _sha(_bytes(observer['config'])), 'Resolved observer configuration changed')
    observer_hash = _sha(_bytes(observer))
    _require(export.get('observerIdentitySha256') == observer_hash, 'Observer identity SHA changed')
    source_rev = _sha(('consense.native-pdf-source-revision.v1\n'+source['sourceSha256']+'\n'+observer_hash).encode())
    _require(source.get('sourceRevisionSha256') == source_rev, 'Original PDF/observer revision changed')
    quality = export.get('quality')
    _require(isinstance(quality,dict) and quality.get('semanticQuality') == quality.get('effectiveRenderingStatus') == 'unknown'
             and quality.get('humanConfirmed') is False and quality.get('semanticScopeVerified') is False
             and quality.get('canonicalAdoption') == export.get('canonicalAdoption') == 'not_performed',
             'PDF observation cannot certify effective display, semantics or adoption')
    _require(isinstance(export.get('captureLimitations'),list)
             and isinstance(quality.get('limitations'),list) and quality['limitations'], 'Explicit native PDF limitations required')
    _require(export.get('observationStatus') in {'observed_native_operators_unreviewed','partial_capture_preserved'},
             'Native observer capture status must remain explicit and unreviewed')
    selected = export.get('selectedPhysicalPages')
    _require(isinstance(selected,list) and selected and len(set(selected)) == len(selected)
             and all(type(v) is int and v >= 1 for v in selected) and selected == sorted(selected), 'Explicit increasing unique actual physical page selection required')
    config = observer['config']; invocation = export.get('invocation')
    _require(config.get('selectedPhysicalPages') == selected and config.get('pageNumberConvention') == 'one_based_original_physical_page'
             and config.get('glyphOrder') == ORDERING and config.get('textChannel') == 'all_literal_pdfbox_glyph_unicode_no_normalization'
             and config.get('textOffsetConvention') == 'UTF16_start_inclusive_end_exclusive_null_unicode_empty_zero_span'
             and config.get('formObservationScope') == 'whole_document_existing_fields_widgets_selected_normal_AP'
             and config.get('appearanceCoordinates') == AP_COORDINATES and config.get('normalizedBlocks') == 'not_produced'
             and config.get('externalResources') == 'no_network_fetch_by_adapter'
             and config.get('localFontResolution') == 'PDFBox_default_font_mapper_environment_dependent_unverified'
             and all(config.get(k) == 'not_invoked' for k in ['rasterRendering','ocr','llm']), 'Observer resolved config cannot substitute OCR, normalize text or synthesize AP')
    _require(isinstance(invocation,dict) and invocation.get('pageTextScope') == 'selected_physical_pages_only'
             and invocation.get('formObservationScope') == config['formObservationScope']
             and all(invocation.get(k) == 'not_invoked' for k in ['rasterRendering','ocr','llm'])
             and type(invocation.get('encrypted')) is bool and type(invocation.get('extractionPermission')) is bool,
             'Invocation must preserve actual source access and no OCR/model/render execution')
    count,frames,access = _source_frames(source_bytes,selected)
    _require(all(invocation.get(k) is v for k,v in access.items()), 'Invocation differs from actual PDF encryption/extraction permission flags')
    _require(type(source.get('pageCount')) is int and source['pageCount'] == count and all(p <= count for p in selected), 'Original PDF page count/selection mismatch')
    pages = export.get('pageObservations')
    _require(isinstance(pages,list) and [p.get('physicalPage') for p in pages] == selected,
             'Full selected page observations must retain actual page order')
    glyph_ids,form_ids = set(),set()
    total_glyphs,utf16_chars = 0,0
    for page in pages:
        physical = page['physicalPage']; frame = page.get('frame'); evidence = page.get('nativePdfEvidence')
        _require(isinstance(frame,dict) and frame.get('coordinateConvention') == FRAME_CONVENTION
                 and all(frame.get(k) == v for k,v in frames[physical].items()), 'Page frame differs from actual original PDF')
        _vector(frame.get('mediaBox'),4,'frame.mediaBox'); _vector(frame.get('cropBox'),4,'frame.cropBox')
        _require(type(frame.get('rotation')) is int and _finite(frame.get('userUnit')), 'Page frame values require literal finite numeric types')
        _frame_mapping(frame)
        _require(isinstance(evidence,dict) and evidence.get('schemaVersion') == 'pdf-native-evidence-v1'
                 and type(evidence.get('pageNo')) is int and evidence.get('pageNo') == physical and evidence.get('cropBox') == frame['cropBox']
                 and evidence.get('mediaBox') == frame['mediaBox']
                 and evidence.get('rotation') == frame['rotation'] and evidence.get('userUnit') == frame['userUnit']
                 and evidence.get('coordinateSpace') == 'normalized_rendered_crop_top_left', 'Native PDF evidence page/frame mismatch')
        _vector(evidence.get('mediaBox'),4,'evidence.mediaBox'); _vector(evidence.get('cropBox'),4,'evidence.cropBox')
        _require(type(evidence.get('rotation')) is int and _finite(evidence.get('userUnit')), 'Native evidence frame must retain literal numeric types')
        _require(page.get('observationStatus') in {'observed_native_operators_unreviewed','partial_native_operator_observation_failure'},
                 'Page operator observation status must preserve failures')
        _require(isinstance(evidence.get('limitations'),list) and evidence['limitations'], 'Operator visibility limitations required')
        glyphs = evidence.get('glyphs')
        _require(isinstance(glyphs,list), 'Complete glyph capture array required')
        for glyph in glyphs: _glyph(glyph,glyph_ids,label='page '+str(physical))
        _channel(page.get('glyphOrderChannel'),glyphs,label='page '+str(physical))
        relations = page.get('lineRelations')
        _require(isinstance(relations,dict) and relations.get('status') == 'not_observed' and relations.get('lines') == []
                 and isinstance(relations.get('limitation'),str) and relations['limitation'], 'Glyph capture cannot infer source reading-order/line relations')
        # Existing native blocks, if captured, remain literal but this producer does not synthesize them.
        _require(evidence.get('nativeBlocks') == [], 'This observer must not synthesize normalized/native/OCR line blocks')
        _forms(evidence.get('formFields'),physical,count,glyph_ids,form_ids,label='page '+str(physical))
        total_glyphs += len(glyphs); utf16_chars += _utf16(page['glyphOrderChannel']['text'])
    outside = export.get('documentFormFieldsOutsideSelection'); unknown = export.get('unassignedFormFields')
    _forms(outside,None,count,glyph_ids,form_ids,label='outside-selected-pages')
    _forms(unknown,None,count,glyph_ids,form_ids,label='unassigned-page')
    _require(all(f.get('pageNo') is not None and f['pageNo'] not in selected for f in outside)
             and all(f.get('pageNo') is None for f in unknown), 'Outside-selection/unknown widget page separation changed')
    all_forms = [f for p in pages for f in p['nativePdfEvidence']['formFields']] + outside + unknown
    partial_pages = sum(p['observationStatus'] == 'partial_native_operator_observation_failure' for p in pages)
    partial_ap = any(f['appearanceGlyphObservationStatus'].startswith('partial_') or
                     f['appearanceStreamObservationStatus'].startswith('partial_') for f in all_forms)
    _require(not (partial_pages or partial_ap) or export['observationStatus'] == 'partial_capture_preserved',
             'Partial page/AP operators cannot be promoted to complete capture')
    return {'schema':'consense.native-pdf-observation-validation.v1','producerKind':PRODUCER,
            'sourceSha256':source['sourceSha256'],'sourceRevisionSha256':source_rev,
            'observerIdentitySha256':observer_hash,'originalBytesVerified':True,'observerSourceBytesVerified':True,
            'frameReaderIdentity':{'library':'pypdf','version':FRAME_READER_VERSION,
                                   'scope':'actual_original_page_dictionary_no_operator_glyph_replay'},
            'originalPageFramesVerified':True,'glyphUtf16RelationsVerified':True,'capturedSelectedPhysicalPages':selected,
            'sourceAccessFlags':access,
            'localFontResolution':'environment_dependent_unverified','networkFetchByAdapter':'not_performed',
            'partialPageCount':partial_pages,
            'partialAppearanceGlyphCount':sum(f['appearanceGlyphObservationStatus'] == 'partial_existing_normal_AP_operator_failure' for f in all_forms),
            'partialAppearanceByteCount':sum(f['appearanceStreamObservationStatus'] == 'partial_existing_normal_AP_bytes_failure' for f in all_forms),
            'unknownAppearancePageCount':sum(f['appearanceGlyphObservationStatus'] == 'not_observed_widget_page_unknown' for f in all_forms),
            'originalPageCount':count,'pageGlyphCount':total_glyphs,'pageGlyphUtf16Chars':utf16_chars,'formWidgetCount':len(form_ids),
            'observationStatus':export['observationStatus'],'partialCapture':export['observationStatus'] == 'partial_capture_preserved',
            'glyphSourceMembershipVerified':False,'formSourceMembershipVerified':False,'effectiveRenderingVerified':False,
            'readingOrderVerified':False,'semanticQuality':'unknown','semanticScopeVerified':False,'humanConfirmed':False,
            'canonicalAdoption':'not_performed'}


def _appearance_streams(export):
    forms = [f for page in export['pageObservations'] for f in page['nativePdfEvidence']['formFields']]
    forms += export['documentFormFieldsOutsideSelection'] + export['unassignedFormFields']
    for form in forms:
        for key in ['appearanceRawStreamBytesBase64','appearanceDecodedStreamBytesBase64']:
            if form[key] is not None: yield form['id'],key,base64.b64decode(form[key],validate=True)


def capture_native_pdf_observation(export_bytes, source_bytes, observer_sources, *, artifact_store):
    """Retain exact observer/source bytes in CAS; no parsing/model process is run."""
    _require(isinstance(artifact_store,ArtifactStore), 'An actual ArtifactStore is required')
    export = _strict_json(export_bytes)
    validation = validate_native_pdf_export(export,source_bytes,observer_sources)
    bundle = {'schema':BUNDLE_SCHEMA,'producerKind':PRODUCER,'source':copy.deepcopy(export['source']),
              'sourcePackage':artifact_store.put_bytes(source_bytes),'rawObservation':artifact_store.put_bytes(export_bytes),
              'observerSources':{name:artifact_store.put_bytes(raw) for name,raw in observer_sources.items()},
              'appearanceArtifacts':[{'formId':form_id,'kind':key,'artifactDescriptor':artifact_store.put_bytes(raw)}
                                     for form_id,key,raw in _appearance_streams(export)],
              'observation':copy.deepcopy(export),'validation':validation,'semanticScopeVerified':False,'humanConfirmed':False}
    bundle['bundleSha256'] = _sha(_bytes(bundle))
    validate_native_pdf_bundle(bundle,artifact_store=artifact_store)
    return bundle


def validate_native_pdf_bundle(bundle, *, artifact_store):
    """Every invocation cold-reads every CAS byte and reparses actual PDF frames."""
    try:
        _require(isinstance(bundle,dict) and bundle.get('schema') == BUNDLE_SCHEMA and bundle.get('producerKind') == PRODUCER
                 and isinstance(artifact_store,ArtifactStore), 'Separate native_pdf bundle and ArtifactStore required')
        _require(bundle.get('bundleSha256') == _sha(_bytes({k:v for k,v in bundle.items() if k != 'bundleSha256'})), 'Native PDF bundle seal changed')
        source_bytes = artifact_store.read_asset(bundle['sourcePackage'])
        export = _strict_json(artifact_store.read_asset(bundle['rawObservation']))
        sources = {name:artifact_store.read_asset(d) for name,d in bundle['observerSources'].items()}
        validation = validate_native_pdf_export(export,source_bytes,sources)
        expected_streams = list(_appearance_streams(export)); streams = bundle.get('appearanceArtifacts')
        _require(isinstance(streams,list) and len(streams) == len(expected_streams), 'All existing AP raw/decoded streams must be captured')
        for item,(form_id,key,raw) in zip(streams,expected_streams):
            _require(item.get('formId') == form_id and item.get('kind') == key
                     and artifact_store.read_asset(item['artifactDescriptor']) == raw, 'Existing AP stream CAS differs from retained observation')
        _require(bundle.get('observation') == export and bundle.get('source') == export['source']
                 and bundle.get('validation') == validation and bundle.get('semanticScopeVerified') is False
                 and bundle.get('humanConfirmed') is False, 'Bundle differs from retained original observation/unknown acceptance')
        return validation
    except NativePdfObservationError: raise
    except (KeyError,TypeError,ValueError,AttributeError,IndexError,ArtifactStoreError) as error:
        raise NativePdfObservationError('Invalid/missing/stale native PDF bundle or actual CAS bytes') from error
