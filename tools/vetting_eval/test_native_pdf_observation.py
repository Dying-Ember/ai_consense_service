"""Actual authored Java output plus bounded native-PDF integrity negatives.

Synthetic mutations are validation probes, not new PDFBox observations or
competition quality scores. No model, online service or original is modified.
"""
import base64
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from artifact_store import ArtifactStore
from native_pdf_observation import (
    NativePdfObservationError, capture_native_pdf_observation, validate_native_pdf_bundle,
    validate_native_pdf_export, _channel, _frame_mapping, _source_frames, FRAME_READER_VERSION,
)
from test_native_pdf_fixture import actual_native_pdf_fixture, actual_partial_ap_fixture, MANIFEST


def canonical(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()


def sha(raw): return hashlib.sha256(raw).hexdigest()


def reseal(bundle):
    bundle['bundleSha256'] = sha(canonical({k:v for k,v in bundle.items() if k != 'bundleSha256'}))
    return bundle


def identity(export):
    observer = export['observerIdentity']
    observer['configSha256'] = sha(canonical(observer['config']))
    export['observerIdentitySha256'] = sha(canonical(observer))
    export['source']['sourceRevisionSha256'] = sha(('consense.native-pdf-source-revision.v1\n'+
        export['source']['sourceSha256']+'\n'+export['observerIdentitySha256']).encode())
    return export


class NativePdfObservationTests(unittest.TestCase):
    def setUp(self):
        self.raw,self.source,self.code = actual_native_pdf_fixture()
        self.export = json.loads(self.raw)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = ArtifactStore(Path(self.temp.name)/'cas')

    def capture(self):
        return capture_native_pdf_observation(self.raw,self.source,self.code,artifact_store=self.store)

    def reject(self,export):
        with self.assertRaises(NativePdfObservationError):
            validate_native_pdf_export(export,self.source,self.code)

    def test_actual_java_frozen_positive_retains_unknowns_and_counts(self):
        result = validate_native_pdf_export(self.export,self.source,self.code)
        self.assertEqual(48,result['pageGlyphCount'])
        self.assertEqual(3,result['formWidgetCount'])
        self.assertEqual([1,2],result['capturedSelectedPhysicalPages'])
        self.assertEqual(1,result['unknownAppearancePageCount'])
        for name in ['glyphSourceMembershipVerified','formSourceMembershipVerified','effectiveRenderingVerified',
                     'readingOrderVerified','semanticScopeVerified','humanConfirmed']:
            self.assertIs(False,result[name])
        self.assertEqual('unknown',result['semanticQuality'])
        self.assertEqual('not_performed',result['canonicalAdoption'])
        self.assertEqual({'encrypted':False,'extractionPermission':True},result['sourceAccessFlags'])
        self.assertEqual('environment_dependent_unverified',result['localFontResolution'])
        self.assertEqual(FRAME_READER_VERSION,result['frameReaderIdentity']['version'])

    def test_capture_preserves_exact_original_observer_code_and_ap_bytes(self):
        bundle = self.capture()
        self.assertEqual(self.source,self.store.read_asset(bundle['sourcePackage']))
        self.assertEqual(self.raw,self.store.read_asset(bundle['rawObservation']))
        self.assertEqual(MANIFEST['native-pdf-observation.json']['sha256'],bundle['rawObservation']['sha256'])
        self.assertEqual(self.export,bundle['observation'])
        for name,descriptor in bundle['observerSources'].items():
            self.assertEqual(self.code[name],self.store.read_asset(descriptor))
        forms = {f['id']:f for p in self.export['pageObservations'] for f in p['nativePdfEvidence']['formFields']}
        forms.update({f['id']:f for f in self.export['unassignedFormFields']})
        self.assertEqual(4,len(bundle['appearanceArtifacts']))
        for descriptor in bundle['appearanceArtifacts']:
            self.assertEqual(base64.b64decode(forms[descriptor['formId']][descriptor['kind']]),
                             self.store.read_asset(descriptor['artifactDescriptor']))

    def test_each_validation_cold_reads_every_descriptor_and_reparses_actual_pdf(self):
        bundle = self.capture()
        with patch.object(self.store,'read_asset',wraps=self.store.read_asset) as reads, \
             patch('native_pdf_observation._source_frames',wraps=_source_frames) as frames:
            validate_native_pdf_bundle(bundle,artifact_store=self.store)
            validate_native_pdf_bundle(bundle,artifact_store=self.store)
        self.assertEqual(2*(2+3+4),reads.call_count)
        self.assertEqual(2,frames.call_count)

    def test_cas_corruption_after_success_is_detected_on_next_cold_read(self):
        bundle = self.capture()
        validate_native_pdf_bundle(bundle,artifact_store=self.store)
        descriptors = [bundle['sourcePackage'],bundle['rawObservation'],*bundle['observerSources'].values(),
                       *[a['artifactDescriptor'] for a in bundle['appearanceArtifacts']]]
        for descriptor in descriptors:
            with self.subTest(sha=descriptor['sha256']):
                path = self.store.root/descriptor['relativePath']; original = path.read_bytes()
                path.write_bytes(original+b'altered')
                try:
                    with self.assertRaises(NativePdfObservationError):
                        validate_native_pdf_bundle(bundle,artifact_store=self.store)
                finally: path.write_bytes(original)

    def test_resealed_bundle_mirror_mutation_cannot_replace_raw_observation(self):
        bundle = self.capture()
        bundle['observation']['pageObservations'][0]['nativePdfEvidence']['glyphs'][0]['unicode'] = 'X'
        reseal(bundle)
        with self.assertRaises(NativePdfObservationError):
            validate_native_pdf_bundle(bundle,artifact_store=self.store)

    def test_resealed_appearance_descriptor_omission_reorder_or_wrong_bytes_rejected(self):
        original = self.capture()
        for mutation in ['omit','order','wrong','unknown_confirmation']:
            with self.subTest(mutation=mutation):
                bundle = copy.deepcopy(original)
                if mutation == 'omit': bundle['appearanceArtifacts'].pop()
                elif mutation == 'order': bundle['appearanceArtifacts'].reverse()
                elif mutation == 'wrong': bundle['appearanceArtifacts'][0]['artifactDescriptor'] = bundle['sourcePackage']
                else: bundle['humanConfirmed'] = True
                reseal(bundle)
                with self.assertRaises(NativePdfObservationError):
                    validate_native_pdf_bundle(bundle,artifact_store=self.store)

    def test_native_pdf_does_not_accept_docx_ocr_producer_or_schema_aliases(self):
        for key,value in [('producerKind','native_docx'),('producerKind','paddle'),('schema','consense.native-docx-observation.v1')]:
            changed = copy.deepcopy(self.export); changed[key] = value; self.reject(changed)
        bundle = self.capture(); bundle['producerKind'] = 'native_docx'; reseal(bundle)
        with self.assertRaises(NativePdfObservationError): validate_native_pdf_bundle(bundle,artifact_store=self.store)

    def test_original_bytes_and_three_named_code_inputs_are_mandatory(self):
        with self.assertRaises(NativePdfObservationError): validate_native_pdf_export(self.export,self.source+b'new',self.code)
        for mode in ['missing','extra','changed']:
            changed = copy.deepcopy(self.code)
            if mode == 'missing': changed.pop('PdfPageEvidence.java')
            elif mode == 'extra': changed['Other.java'] = b'other'
            else: changed['PdfPageEvidence.java'] += b'new'
            with self.subTest(mode=mode),self.assertRaises(NativePdfObservationError):
                validate_native_pdf_export(self.export,self.source,changed)

    def test_source_and_observer_revision_hash_changes_rejected(self):
        for field in ['sourceRevisionSha256','sourceSha256','pageCount','byteCount']:
            changed = copy.deepcopy(self.export)
            changed['source'][field] = '0'*64 if 'Sha256' in field else 999
            self.reject(changed)
        changed = copy.deepcopy(self.export); changed['observerIdentitySha256'] = '0'*64; self.reject(changed)
        changed = copy.deepcopy(self.export); changed['observerIdentity']['configSha256'] = '0'*64; self.reject(changed)
        for key,value in [('mediaType','image/png'),('name','different.pdf')]:
            changed = copy.deepcopy(self.export); changed['sourceArtifact'][key]=value; self.reject(changed)

    def test_resealed_unsupported_config_cannot_fetch_normalize_or_certify_fonts(self):
        cases = {'externalResources':'never_fetched','localFontResolution':'verified','ocr':'paddle',
                 'rasterRendering':'rendered','textChannel':'normalized_text','glyphOrder':'reading_order'}
        for key,value in cases.items():
            with self.subTest(key=key):
                changed = copy.deepcopy(self.export); changed['observerIdentity']['config'][key] = value
                self.reject(identity(changed))
        for key in ['apacheFontBox','pdfBoxFontMapperClass']:
            changed = copy.deepcopy(self.export); changed['observerIdentity']['dependencyVersions'].pop(key)
            self.reject(identity(changed))

    def test_actual_original_page_selection_count_and_access_flags_checked(self):
        for selected in [[2,1],[1,1],[1,3],[True,2],[]]:
            with self.subTest(selected=selected):
                changed = copy.deepcopy(self.export); changed['selectedPhysicalPages'] = selected
                changed['observerIdentity']['config']['selectedPhysicalPages'] = selected; self.reject(identity(changed))
        for key in ['encrypted','extractionPermission']:
            changed = copy.deepcopy(self.export); changed['invocation'][key] = not changed['invocation'][key]; self.reject(changed)

    def test_independent_original_page_frame_rejects_geometry_and_affine_changes(self):
        for key in ['mediaBox','cropBox','rawToRenderedCropNormalized','rawToRenderedCropPoints72','renderedCropSizePoints72']:
            changed = copy.deepcopy(self.export); changed['pageObservations'][0]['frame'][key][0] += 1; self.reject(changed)
        for key,value in [('rotation',0),('userUnit',1),('transformStatus','raster_verified')]:
            changed = copy.deepcopy(self.export); changed['pageObservations'][0]['frame'][key] = value; self.reject(changed)
        changed = copy.deepcopy(self.export); changed['pageObservations'][1]['nativePdfEvidence']['rotation']=False; self.reject(changed)

    def test_all_quarter_turn_affine_equations_and_user_unit_are_explicit(self):
        base = self.export['pageObservations'][0]['frame']
        for rotation,matrix in {0:[1,0,0,-1,-3,15],90:[0,1,1,0,-5,-3],
                                180:[-1,0,0,1,11,-5],270:[0,-1,-1,0,15,11]}.items():
            with self.subTest(rotation=rotation):
                frame = copy.deepcopy(base); frame.update({'cropBox':[3,5,8,10],'rotation':rotation,'userUnit':2})
                rw,rh = (8,10) if rotation%180 == 0 else (10,8)
                frame['rawToRenderedCropNormalized'] = [v/(rw if i%2==0 else rh) for i,v in enumerate(matrix)]
                frame['rawToRenderedCropPoints72'] = [v*2 for v in matrix]; frame['renderedCropSizePoints72'] = [rw*2,rh*2]
                _frame_mapping(frame)
                frame['rawToRenderedCropPoints72'][4] += 1
                with self.assertRaises(NativePdfObservationError): _frame_mapping(frame)

    def test_runtime_identity_has_no_optional_fallback(self):
        with patch('pypdf.__version__','other'),self.assertRaises(NativePdfObservationError):
            validate_native_pdf_export(self.export,self.source,self.code)

    def test_all_glyph_channel_cannot_omit_hidden_zero_alpha_or_outcrop_glyphs(self):
        status = {g['visibilityStatus'] for p in self.export['pageObservations'] for g in p['nativePdfEvidence']['glyphs']}
        self.assertTrue({'hidden_rendering_mode','hidden_zero_alpha','outside_crop'} <= status)
        for mutation in ['omit_span','normalize','offset','reorder_id','delete_glyph']:
            changed = copy.deepcopy(self.export); page = changed['pageObservations'][0]; channel = page['glyphOrderChannel']
            if mutation == 'omit_span': channel['spans'].pop()
            elif mutation == 'normalize': channel['text'] += '\n'
            elif mutation == 'offset': channel['spans'][0]['end'] += 1
            elif mutation == 'reorder_id': channel['spans'][0]['glyphId'] = channel['spans'][1]['glyphId']
            else: page['nativePdfEvidence']['glyphs'].pop()
            with self.subTest(mutation=mutation): self.reject(changed)

    def test_null_zero_length_supplementary_ligature_utf16_relations(self):
        glyphs = [{'id':'null','unicode':None},{'id':'emoji','unicode':'😀'},{'id':'cluster','unicode':'ffi'}]
        channel = copy.deepcopy(self.export['pageObservations'][0]['glyphOrderChannel'])
        channel.update({'text':'😀ffi','nullUnicodeObserved':True,'spans':[{'start':0,'end':0,'glyphId':'null'},
                        {'start':0,'end':2,'glyphId':'emoji'},{'start':2,'end':5,'glyphId':'cluster'}]})
        _channel(channel,glyphs,label='literal-null-cluster')
        channel['spans'][1]['end'] = 1
        with self.assertRaises(NativePdfObservationError): _channel(channel,glyphs,label='wrong-codepoints')

    def test_every_raw_style_field_requires_literal_type_and_explicit_nullable_presence(self):
        for key in ['xscale','fontEmbedded','bbox','unicode','clippingBoundsPdf','currentTransformationMatrix',
                    'fillColorComponents','fontSize','strokeColorSpace']:
            changed = copy.deepcopy(self.export); changed['pageObservations'][0]['nativePdfEvidence']['glyphs'][0].pop(key)
            with self.subTest(key=key): self.reject(changed)
        for key,value in [('xscale','12'),('renderingMode',True),('fillAlpha',float('nan')),('characterCodes',['86'])]:
            changed = copy.deepcopy(self.export); changed['pageObservations'][0]['nativePdfEvidence']['glyphs'][0][key] = value
            self.reject(changed)

    def test_hidden_style_and_effective_visibility_cannot_be_promoted(self):
        for key,value in [('effectiveVisibilityStatus','verified'),('eligibleForNativeText',True)]:
            changed = copy.deepcopy(self.export)
            glyph = next(g for p in changed['pageObservations'] for g in p['nativePdfEvidence']['glyphs'] if g['visibilityStatus']=='hidden_rendering_mode')
            glyph[key] = value
            if key == 'eligibleForNativeText': glyph['visibilityStatus'] = 'operator_visible_unverified'
            self.reject(changed)
        changed = copy.deepcopy(self.export); changed['quality']['semanticQuality'] = 'correct'; self.reject(changed)
        changed = copy.deepcopy(self.export); changed['quality']['humanConfirmed'] = True; self.reject(changed)

    def test_ap_stored_value_and_literal_eligible_channels_remain_independent(self):
        form = self.export['pageObservations'][0]['nativePdfEvidence']['formFields'][0]
        self.assertEqual('Stored value',form['fieldValue']); self.assertEqual('Visible value',form['appearanceValue'])
        self.assertEqual('  Visible value  ',form['appearanceGlyphOrderChannel']['text'])
        self.assertIs(True,form['conflict'])
        for key,value in [('appearanceValue','Stored value'),('conflict',False),('appearanceCoordinateStatus','page_transformed')]:
            changed = copy.deepcopy(self.export); changed['pageObservations'][0]['nativePdfEvidence']['formFields'][0][key]=value
            self.reject(changed)
        changed = copy.deepcopy(self.export)
        changed['pageObservations'][0]['nativePdfEvidence']['formFields'][0]['appearanceGlyphs'][0]['bbox']=[0,0,1,1]
        self.reject(changed)

    def test_missing_unknown_or_partial_ap_never_gets_complete_synthetic_evidence(self):
        for mutate in ['missing_stream','unknown_page_complete','missing_ap_glyphs','annotation_flags']:
            changed = copy.deepcopy(self.export); forms = changed['pageObservations'][0]['nativePdfEvidence']['formFields']
            if mutate == 'missing_stream': forms[0]['appearanceRawStreamBytesBase64']=None
            elif mutate == 'unknown_page_complete': changed['unassignedFormFields'][0]['appearanceGlyphObservationStatus']='observed_existing_normal_AP_operators_unreviewed'
            elif mutate == 'missing_ap_glyphs': forms[1]['appearanceGlyphs']=copy.deepcopy(forms[0]['appearanceGlyphs'])
            else: forms[0]['annotationFlags']='0'
            with self.subTest(mutate=mutate): self.reject(changed)
        changed = copy.deepcopy(self.export); form = changed['pageObservations'][0]['nativePdfEvidence']['formFields'][0]
        form['appearanceGlyphObservationStatus']='partial_existing_normal_AP_operator_failure'
        form['valueStatus']='appearance_observation_partial_unknown'
        changed['observationStatus']='partial_capture_preserved'
        result=validate_native_pdf_export(changed,self.source,self.code)
        self.assertIs(True,result['partialCapture']); self.assertEqual(1,result['partialAppearanceGlyphCount'])

    def test_forms_outside_selection_and_unassigned_keep_actual_page_separation(self):
        changed=copy.deepcopy(self.export)
        changed['documentFormFieldsOutsideSelection'].append(changed['pageObservations'][0]['nativePdfEvidence']['formFields'].pop())
        self.reject(changed)
        changed=copy.deepcopy(self.export); changed['unassignedFormFields'][0]['pageNo']=1; self.reject(changed)

    def test_partial_page_capture_preserved_and_cannot_be_promoted(self):
        changed=copy.deepcopy(self.export)
        changed['pageObservations'][0]['observationStatus']='partial_native_operator_observation_failure'
        changed['observationStatus']='partial_capture_preserved'
        result=validate_native_pdf_export(changed,self.source,self.code)
        self.assertEqual(1,result['partialPageCount']); self.assertIs(True,result['partialCapture'])
        changed['observationStatus']='observed_native_operators_unreviewed'; self.reject(changed)

    def test_actual_malformed_ap_cold_capture_retains_full_stream_and_observed_prefix(self):
        raw,source,code=actual_partial_ap_fixture()
        observation=json.loads(raw); form=observation['pageObservations'][0]['nativePdfEvidence']['formFields'][0]
        self.assertEqual('Retained',form['fieldValue']); self.assertEqual('Retained',form['appearanceValue'])
        self.assertEqual('Retained',form['appearanceGlyphOrderChannel']['text'])
        self.assertEqual(8,len(form['appearanceGlyphs']))
        self.assertEqual('appearance_observation_partial_unknown',form['valueStatus'])
        self.assertEqual('partial_existing_normal_AP_operator_failure',form['appearanceGlyphObservationStatus'])
        self.assertTrue(base64.b64decode(form['appearanceDecodedStreamBytesBase64']).endswith(b'\nI!'))
        bundle=capture_native_pdf_observation(raw,source,code,artifact_store=self.store)
        first=validate_native_pdf_bundle(bundle,artifact_store=self.store)
        second=validate_native_pdf_bundle(bundle,artifact_store=ArtifactStore(self.store.root))
        self.assertEqual(first,second); self.assertIs(True,first['partialCapture'])
        self.assertEqual(12,first['pageGlyphCount']); self.assertEqual(1,first['partialAppearanceGlyphCount'])
        self.assertEqual(raw,self.store.read_asset(bundle['rawObservation']))
        self.assertEqual(source,self.store.read_asset(bundle['sourcePackage']))
        self.assertIs(False,first['effectiveRenderingVerified'])

    def test_actual_malformed_ap_cannot_be_relabelled_complete_or_effectively_adopted(self):
        raw,source,code=actual_partial_ap_fixture(); observation=json.loads(raw)
        for mutation in ['whole_capture','field_value','ap_status']:
            changed=copy.deepcopy(observation); form=changed['pageObservations'][0]['nativePdfEvidence']['formFields'][0]
            if mutation=='whole_capture': changed['observationStatus']='observed_native_operators_unreviewed'
            elif mutation=='field_value': form['valueStatus']='appearance_text_unverified'
            else: form['appearanceGlyphObservationStatus']='observed_existing_normal_AP_operators_unreviewed'
            with self.subTest(mutation=mutation),self.assertRaises(NativePdfObservationError):
                validate_native_pdf_export(changed,source,code)

    def test_strict_raw_json_rejects_duplicate_trailing_nonfinite_invalid_utf8(self):
        raws=[b'{"schema":1,"schema":2}',self.raw+b' {}',b'{"x":NaN}',b'{"x":Infinity}',b'\xff']
        for raw in raws:
            with self.subTest(raw=raw[:30]),self.assertRaises(NativePdfObservationError):
                capture_native_pdf_observation(raw,self.source,self.code,artifact_store=self.store)

    def test_unknown_future_raw_fields_are_losslessly_retained_not_silently_dropped(self):
        changed=copy.deepcopy(self.export); changed['newRawObservation']={'literalUnknown':None,'rawNumbers':[1,2,3]}
        bundle=capture_native_pdf_observation(canonical(changed),self.source,self.code,artifact_store=self.store)
        self.assertEqual(changed['newRawObservation'],bundle['observation']['newRawObservation'])
        validate_native_pdf_bundle(bundle,artifact_store=self.store)

    def test_relational_integrity_does_not_claim_operator_membership_attestation(self):
        # A self-consistent different glyph cannot be detected without replaying
        # actual PDF operators. The bounded gate must explicitly report that gap.
        changed=copy.deepcopy(self.export); page=changed['pageObservations'][0]
        page['nativePdfEvidence']['glyphs'][0]['unicode']='X'
        page['glyphOrderChannel']['text']='X'+page['glyphOrderChannel']['text'][1:]
        result=validate_native_pdf_export(changed,self.source,self.code)
        self.assertIs(False,result['glyphSourceMembershipVerified'])
        self.assertIs(False,result['readingOrderVerified'])


if __name__ == '__main__': unittest.main()
