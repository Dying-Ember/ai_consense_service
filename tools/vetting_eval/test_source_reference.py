"""Authored reference/anchor contracts, not OCR or original semantic acceptance."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

from source_reference import (LEGACY_SCHEMA, SCHEMA, SourceReferenceError,
                              read_source_reference, validate_source_reference)


SOURCE_BYTES=b'%PDF-1.7 authored source-anchor fixture; not a parser input'
SOURCE_SHA=hashlib.sha256(SOURCE_BYTES).hexdigest()
PNG=b'\x89PNG\r\n\x1a\n'+b'\x00\x00\x00\rIHDR'+struct.pack('>II',100,200)+b'authored-dimension-contract'
IMAGE_SHA=hashlib.sha256(PNG).hexdigest()


def encoded(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,allow_nan=False).encode('utf-8')


def gct():
    return {'schema':LEGACY_SCHEMA,'referenceVersion':'gct1-complete-visible-page-source-review-20261009-v2',
        'actor':'codex','method':'original_source_review','humanConfirmed':False,
        'sourceSha256':SOURCE_SHA,'physicalPages':[1],'split':'development_historically_exposed',
        'historicalExposure':{'priorEngineeringParserExposure':True},
        'purpose':'authored fixture only','semanticQuality':'unknown',
        'coverage':{'wholeSourceAcceptance':False},
        'reviewImage':{'sha256':IMAGE_SHA,'width':100,'height':200,'renderDpi':300,
                       'path':'this-embedded-path-must-never-be-opened.png'},
        'regions':[{'regionId':'paragraph','pageNo':1,'ordinal':0,'clauseId':'GCT 1',
                    'text':'The tender must not omit conditions.','textStatus':'source_transcription',
                    'bboxNormalized':[.1,.2,.9,.4],'boundaryPrecision':'approximate_source_visual_extent'}],
        'relations':[],'unknowns':['not human confirmed'],
        'futureMetadata':{'projectValue':None,'certainty':'unknown'}}


def sl():
    data=gct()
    data['referenceVersion']='sl-amr-complete-original-3436-3438-source-review-20261010-v1'
    image=data.pop('reviewImage')
    data['physicalPages']=[2,3]
    data['regions']=[]
    parts=[]
    page_records=[]
    for page_no in (2,3):
        ids=[]
        header_id=f'p{page_no}-header'
        data['regions'].append({'regionId':header_id,'pageNo':page_no,'physicalPageNo':page_no,
            'role':'table_header','ordinal':0,'text':'Documents / Not Applicable',
            'bboxNormalized':[0,0,1,.1],'tableId':'table-a','columnStart':0,'columnSpan':4,'rowSpan':1,
            'boundaryPrecision':'approximate_source_visual_extent'})
        ids.append(header_id)
        cells=[]
        for column in range(4):
            rid=f'row1-p{page_no}-col{column}'
            text='Equipment, unless otherwise specified.' if column==1 else ''
            data['regions'].append({'regionId':rid,'pageNo':page_no,'physicalPageNo':page_no,
                'role':'table_description' if column==1 else 'table_cell','ordinal':len(ids),
                'text':text,'tableId':'table-a','logicalRowId':'row1',
                'columnStart':column,'columnSpan':1,'rowSpan':1,
                'bboxNormalized':[column/4,.2,(column+1)/4,.5],
                'bboxRaster':[column*25,40,(column+1)*25,100],
                'boundaryPrecision':'raster_rule_outline_approximation'})
            ids.append(rid)
            cells.append(rid)
        box_ids=[]
        if page_no==2:
            for column in (2,3):
                rid=f'row1-box{column}'
                data['regions'].append({'regionId':rid,'pageNo':2,'physicalPageNo':2,
                    'role':'checkbox','ordinal':len(ids),'text':'','tableId':'table-a','logicalRowId':'row1',
                    'selected':None,'selectionStatus':'unknown','visualState':'empty',
                    'bboxNormalized':[column/4+.01,.25,column/4+.1,.30],
                    'boundaryPrecision':'approximate_source_visual_extent'})
                ids.append(rid)
                box_ids.append(rid)
        part_id=f'row1-p{page_no}'
        parts.append({'partId':part_id,'logicalRowId':'row1','pageNo':page_no,
                      'cellRegionIds':cells,'descriptionRegionId':cells[1],
                      'checkboxIds':box_ids,'continuation':page_no==3})
        text='\n\n'.join(r['text'] for r in data['regions'] if r['pageNo']==page_no and r['text'])
        page_records.append({'physicalPageNo':page_no,'sourceImage':copy.deepcopy(image),
                             'regionIdsInReadingOrder':ids,'visiblePageText':text})
    data['pages']=page_records
    data['relations']=[{'type':'continuation_of','fromRegionId':'row1-p3-col1','toRegionId':'row1-p2-col1'}]
    data['tables']=[{'tableId':'table-a','columnCount':4,'physicalRowParts':parts,
                     'logicalRows':[{'logicalRowId':'row1','partIds':['row1-p2','row1-p3'],
                                     'checkboxIds':['row1-box2','row1-box3'],
                                     'selection':{'status':'unknown','notApplicable':None}}],
                     'pageParts':[{'physicalPageNo':p,'headerRegionIds':[f'p{p}-header'],
                                   'partIds':[f'row1-p{p}']} for p in (2,3)],
                     'headerCells':[{'columnStart':0,'columnSpan':4,'rowSpan':1,'text':''}]}]
    data['formFields']=[]
    data['formFieldGroups']=[]
    data['remarksGroups']=[]
    return data


def docx():
    source_sha=hashlib.sha256(b'authored original native DOCX bytes').hexdigest()
    return {'schema':SCHEMA,'referenceVersion':'authored-docx-native-contract-v2',
        'sourceSha256':source_sha,'sourceMediaType':'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'actor':'codex','method':'original_source_review','humanConfirmed':False,
        'split':'authored_contract_fixture','semanticQuality':'unknown',
        'pages':[],'relations':[],
        'regions':[{'regionId':'cell-native','text':'Unless approved.','role':'table_cell',
            'sourceAnchor':{'kind':'docx_native','sourceSha256':source_sha,'partName':'word/document.xml',
                'bodyElementIndex':4,'nodeKind':'cell',
                'tablePath':[{'tableIndex':0,'rowIndex':2,'cellIndex':1}]}}]}


class SourceReferenceTest(unittest.TestCase):
    def test_gct_exact_raw_bom_whitespace_unknowns_and_extra_metadata_are_retained(self):
        raw=b'\xef\xbb\xbf \n'+encoded(gct())+b'\n '
        before=raw
        capture=read_source_reference(raw,expected_sha256=hashlib.sha256(raw).hexdigest())
        self.assertEqual(before,capture.raw_bytes)
        self.assertEqual(LEGACY_SCHEMA,capture.input_schema)
        self.assertEqual('gct_single_review_image_v1',capture.adapter)
        self.assertEqual(SCHEMA,capture.reference['schema'])
        self.assertEqual(gct()['reviewImage']['sha256'],capture.reference['pages'][0]['sourceImage']['sha256'])
        self.assertEqual('unknown',capture.reference['semanticQuality'])
        self.assertFalse(capture.reference['humanConfirmed'])
        self.assertEqual(gct()['futureMetadata'],capture.reference['futureMetadata'])
        self.assertIsNone(capture.reference['regions'][0]['role'])
        self.assertTrue(any(r['path']=='futureMetadata' for r in capture.validation['unsupportedFields']))
        self.assertEqual('not_evaluated',capture.validation['semanticAcceptance'])
        self.assertFalse(capture.validation['embeddedPathsRead'])
        self.assertTrue(capture.validation['inputSha256Pinned'])

    def test_path_read_does_not_write_reference_and_raw_retains_input_version(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'reference.json'
            raw=encoded(gct())
            path.write_bytes(raw)
            capture=read_source_reference(path)
            self.assertEqual(raw,path.read_bytes())
            self.assertEqual(gct()['referenceVersion'],capture.input_reference_version)
            self.assertEqual(hashlib.sha256(raw).hexdigest(),capture.input_sha256)
            self.assertEqual(raw,capture.raw_bytes)

    def test_exact_input_sha_mismatch_and_unknown_v1_are_rejected(self):
        with self.assertRaisesRegex(SourceReferenceError,'SHA-256 mismatch'):
            read_source_reference(encoded(gct()),expected_sha256='0'*64)
        bad=gct()
        bad['referenceVersion']='another-source-v1-not-authorized-for-implicit-adaptation'
        with self.assertRaisesRegex(SourceReferenceError,'Unsupported v1'):
            read_source_reference(encoded(bad))

    def test_ambiguous_json_duplicate_keys_nonfinite_and_wrong_schema_fail(self):
        for raw in (b'{"x":1,"x":2}',b'{"x":NaN}',b'{"x":1e999}',b'not-json',b'[]'):
            with self.subTest(raw=raw),self.assertRaises(SourceReferenceError):
                read_source_reference(raw)
        bad=gct()
        bad['schema']='unknown'
        with self.assertRaisesRegex(SourceReferenceError,'Unsupported'):
            read_source_reference(encoded(bad))

    def test_actual_evidence_is_explicit_hash_verified_and_missing_bytes_stay_unverified(self):
        capture=read_source_reference(encoded(gct()))
        self.assertEqual('unverified',capture.validation['actualByteVerification'][SOURCE_SHA]['status'])
        report=validate_source_reference(capture.reference,evidence_bytes={SOURCE_SHA:SOURCE_BYTES,IMAGE_SHA:PNG})
        self.assertTrue(all(v['status']=='verified' for v in report['actualByteVerification'].values()))
        self.assertFalse(report['humanConfirmationVerified'])
        self.assertFalse(report['parserAdoptionPerformed'])
        self.assertFalse(report['unknownsUpgraded'])
        with self.assertRaisesRegex(SourceReferenceError,'actual byte SHA-256 mismatch'):
            validate_source_reference(capture.reference,evidence_bytes={IMAGE_SHA:PNG[:-1]+b'X'})

    def test_raster_dimensions_frame_and_normalized_geometry_are_bound(self):
        normalized=read_source_reference(encoded(sl())).reference
        bad=copy.deepcopy(normalized)
        bad['pages'][0]['sourceImage']['width']=999
        with self.assertRaisesRegex(SourceReferenceError,'PNG dimensions'):
            validate_source_reference(bad,evidence_bytes={IMAGE_SHA:PNG})
        bad=copy.deepcopy(normalized)
        bad['regions'][1]['bboxNormalized']=[.01,.2,.25,.5]
        with self.assertRaisesRegex(SourceReferenceError,'boxes disagree'):
            validate_source_reference(bad)
        bad=copy.deepcopy(normalized)
        bad['regions'][1]['bboxNormalized']=[0,.2,1.01,.5]
        with self.assertRaisesRegex(SourceReferenceError,'outside'):
            validate_source_reference(bad)

    def test_page_region_order_source_identity_and_relation_integrity_are_checked(self):
        good=read_source_reference(encoded(sl())).reference
        mutations=[lambda d:d['pages'][0]['regionIdsInReadingOrder'].append('missing'),
                   lambda d:d['regions'].append(copy.deepcopy(d['regions'][0])),
                   lambda d:d['regions'][0]['sourceAnchor'].update(sourceSha256='a'*64),
                   lambda d:d['relations'][0].update(toRegionId='missing'),
                   lambda d:d['pages'][0].update(visiblePageText='invented'),
                   lambda d:d['regions'][0].update(ordinal=99),
                   lambda d:d.update(physicalPages=[2])]
        for mutate in mutations:
            bad=copy.deepcopy(good)
            mutate(bad)
            with self.subTest(bad=bad),self.assertRaises(SourceReferenceError):
                validate_source_reference(bad)

    def test_sl_table_group_controls_and_qualifiers_are_not_upgraded(self):
        original=sl()
        capture=read_source_reference(encoded(original))
        self.assertEqual('sl_pages_and_table_group_v1',capture.adapter)
        self.assertEqual(original['tables'],capture.reference['tables'])
        self.assertEqual(original['relations'],capture.reference['relations'])
        boxes=[r for r in capture.reference['regions'] if r['role']=='checkbox']
        self.assertEqual(2,len(boxes))
        self.assertTrue(all(r['selected'] is None and r['selectionStatus']=='unknown' for r in boxes))
        self.assertTrue(any('genericGridTopology' in r['path'] for r in capture.validation['unsupportedFields']))
        self.assertEqual('unknown',capture.reference['tables'][0]['logicalRows'][0]['selection']['status'])

    def test_table_dangling_parts_spans_duplicates_and_false_continuation_controls_fail(self):
        good=read_source_reference(encoded(sl())).reference
        mutations=[lambda d:d['tables'][0]['logicalRows'][0]['partIds'].append('missing'),
                   lambda d:d['tables'][0]['physicalRowParts'][1]['checkboxIds'].append('row1-box2'),
                   lambda d:d['regions'][1].update(columnSpan=2),
                   lambda d:d['regions'][5].update(logicalRowId='wrong-owner'),
                   lambda d:d.update(relations=[])]
        for mutate in mutations:
            bad=copy.deepcopy(good)
            mutate(bad)
            with self.subTest(bad=bad),self.assertRaises(SourceReferenceError):
                validate_source_reference(bad)

    def test_native_v2_is_read_without_legacy_shape_guessing(self):
        good=read_source_reference(encoded(gct())).reference
        good['referenceVersion']='new-source-native-v2'
        raw=encoded(good)
        capture=read_source_reference(raw)
        self.assertEqual('native_v2',capture.adapter)
        self.assertEqual(SCHEMA,capture.input_schema)
        self.assertEqual(raw,capture.raw_bytes)
        self.assertEqual(good,capture.reference)

    def test_docx_native_body_table_cell_and_nested_paths_have_no_invented_pages(self):
        good=docx()
        good['regions'][0]['sourceAnchor']['tablePath'].append({'tableIndex':1,'rowIndex':0,'cellIndex':2})
        capture=read_source_reference(encoded(good))
        self.assertEqual([],capture.reference['pages'])
        self.assertEqual(good['regions'],capture.reference['regions'])
        self.assertTrue(any('.nativeLocation' in r['path'] for r in capture.validation['unknowns']))
        for field,value in (('pageNo',1),('physicalPageNo',1),('bboxNormalized',[0,0,1,1])):
            bad=copy.deepcopy(good)
            bad['regions'][0][field]=value
            with self.subTest(field=field),self.assertRaisesRegex(SourceReferenceError,'Native DOCX'):
                read_source_reference(encoded(bad))

    def test_docx_native_index_ambiguity_and_missing_cell_coordinates_fail(self):
        good=docx()
        mutations=[lambda d:d['regions'][0]['sourceAnchor'].update(bodyElementIndex=True),
                   lambda d:d['regions'][0]['sourceAnchor'].update(tablePath=[]),
                   lambda d:d['regions'][0]['sourceAnchor']['tablePath'][0].pop('cellIndex'),
                   lambda d:d['regions'][0]['sourceAnchor'].update(partName='word/header1.xml'),
                   lambda d:d['regions'][0]['sourceAnchor'].update(sourceSha256='a'*64)]
        for mutate in mutations:
            bad=copy.deepcopy(good)
            mutate(bad)
            with self.subTest(bad=bad),self.assertRaises(SourceReferenceError):
                read_source_reference(encoded(bad))

    def test_docx_rendition_explicitly_binds_original_and_pdf_page(self):
        good=docx()
        orig=good['sourceSha256']
        rendition_sha=hashlib.sha256(b'authored DOCX printed PDF bytes').hexdigest()
        page_id='render-p7'
        good['pages']=[{'pageId':page_id,'sourceAnchor':{'kind':'docx_rendition_page',
            'sourceSha256':orig,'renditionSha256':rendition_sha,'physicalPageNo':7,'mappingStatus':'unknown'},
            'sourceImage':{'sha256':IMAGE_SHA,'width':100,'height':200,
                'sourceRelation':{'kind':'docx_rendition_page_raster','originalSourceSha256':orig,
                    'renditionSha256':rendition_sha,'renditionPhysicalPageNo':7}},
            'regionIdsInReadingOrder':['cell-native']}]
        good['regions'][0]['renditionAnchor']={'kind':'docx_rendition_region','originalSourceSha256':orig,
            'renditionSha256':rendition_sha,'physicalPageNo':7,'pageId':page_id,
            'bboxNormalized':[.1,.2,.9,.3],'mappingStatus':'unknown'}
        capture=read_source_reference(encoded(good))
        self.assertEqual(good,capture.reference)
        self.assertTrue(any('.mapping' in r['path'] for r in capture.validation['unknowns']))
        for mutate in (lambda d:d['regions'][0]['renditionAnchor'].update(originalSourceSha256='a'*64),
                       lambda d:d['regions'][0]['renditionAnchor'].update(physicalPageNo=6),
                       lambda d:d['pages'][0].update(physicalPageNo=7)):
            bad=copy.deepcopy(good)
            mutate(bad)
            with self.subTest(bad=bad),self.assertRaises(SourceReferenceError):
                read_source_reference(encoded(bad))

    def test_unsupported_future_fields_and_generic_tables_are_preserved_unverified(self):
        good=docx()
        good['tables']=[{'tableId':'new-table','unrecognizedCells':[{'value':None}]}]
        good['newFeature']={'status':'unknown'}
        capture=read_source_reference(encoded(good))
        self.assertEqual(good,capture.reference)
        paths={r['path'] for r in capture.validation['unsupportedFields']}
        self.assertIn('newFeature',paths)
        self.assertIn('tables.new-table',paths)
        self.assertEqual('not_evaluated',capture.validation['semanticAcceptance'])

    def test_boolean_pages_dimensions_invalid_legacy_image_and_row_types_fail_closed(self):
        for mutate in (lambda d:d.update(physicalPages=[True]),
                       lambda d:d['reviewImage'].update(width=True),
                       lambda d:d.update(reviewImage=None),
                       lambda d:d['regions'][0].update(pageNo=True),
                       lambda d:d['regions'][0].update(ordinal='first')):
            bad=gct()
            mutate(bad)
            with self.subTest(bad=bad),self.assertRaises(SourceReferenceError):
                read_source_reference(encoded(bad))


if __name__=='__main__':
    unittest.main()
