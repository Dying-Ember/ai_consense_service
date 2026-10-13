"""Project complete saved physical tables once; preserve the native baseline.

Raw glyph/edge streams are only scanned page-by-page. Complete observed matrices,
slots and geometry remain separate from vector payloads; no semantic owner is
inferred, no OCR is invoked, and acceptance remains false.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
from build_fixed_development_baseline import descriptor, read_descriptor, write_json
from token_windows import digest, paragraph_map, utf16_length

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-manifest',required=True);p.add_argument('--table-plan',required=True)
    p.add_argument('--out',required=True);args=p.parse_args()
    out=Path(args.out).resolve();out.mkdir(parents=True,exist_ok=False)
    manifest=json.loads(Path(args.base_manifest).read_bytes())
    plan=read_descriptor(manifest['inputPlan'])
    inputs=json.loads(Path(args.table_plan).read_bytes())['inputs']
    native=[]
    with Path(manifest['corpus']['path']).open(encoding='utf-8') as stream:
        for line in stream:native.append(json.loads(line))
    if descriptor(manifest['corpus']['path'])!=manifest['corpus']:raise ValueError('Native corpus changed')
    by_name={r['fileKey']:r for r in native if r['sourceQuality']['family']=='SL'}
    by_page={page:prof['name'] for prof in plan['slProfiles']+plan['slOldNine'] for page in prof['originalPhysicalPages']}
    complete=[];supplement=[];seen_pages=set();seen_table_ids=set();stream_reports=[]
    for raw_desc in inputs:
        h=hashlib.sha256();size=0;pages=0;tables=0
        with Path(raw_desc['path']).open('rb') as stream:
            for line in stream:
                h.update(line);size+=len(line)
                if not line.strip():continue
                page=json.loads(line);number=page['originalPage'];pages+=1
                if number in seen_pages:raise ValueError('Duplicate physical table page')
                seen_pages.add(number)
                if page['parentOriginalSourceSha256']!=plan['slParent']['sha256']:raise ValueError('SL parent changed')
                if number not in by_page:raise ValueError('Table outside required technical range')
                base=by_name[by_page[number]]
                for table in page['tables']:
                    tables+=1;index=table['tableIndex'];tid='physical-table:'+digest({'source':plan['slParent']['sha256'],'page':number,'table':index})
                    if tid in seen_table_ids:raise ValueError('Duplicate table identity')
                    seen_table_ids.add(tid)
                    artifact={'id':tid,'sectionId':page['sectionId'],'originalPage':number,'localPage':page['localPage'],
                        'coordinateFrame':page['coordinateFrame'],'originalSourceSha256':plan['slParent']['sha256'],
                        'rawPageArtifact':raw_desc,'table':table,'sourceQualityAccepted':False,
                        'semanticOwner':None,'footnoteOwner':None,'crossPageGroup':None}
                    artifact_path=out/(tid.split(':')[1]+'.json');ad=write_json(artifact_path,artifact)
                    complete.append({'id':tid,'originalPage':number,'tableIndex':index,'artifact':ad})
                    parts=[]
                    for ridx,matrix_row in enumerate(table['extractedMatrix']):
                        text='\t'.join('' if cell is None else str(cell) for cell in matrix_row)
                        if not text.strip():continue
                        bid=tid+'/row:'+str(ridx)
                        parts.append({'blockId':bid,'anchor':base['fileKey']+'/P'+str(number)+'/'+bid,
                            'text':text,'startOffset':0,'endOffset':utf16_length(text),'pageNo':number,
                            'derivedPageNo':page['localPage'],'sourceBlockId':bid,'bbox':table['bbox'],
                            'coordinateSpace':'PDF_points_crop_top_left','source':'saved_pdfplumber_physical_table_row_projection',
                            'sourcePartBasis':'exact extractedMatrix row joined with tab; None vs empty retained in complete table artifact'})
                    if not parts:continue
                    q={**base['sourceQuality'],'observationChannel':'official_default_physical_table','semanticScopeVerified':False}
                    row={k:v for k,v in base.items() if k not in ('clauseId','clauseHeadingLocation','observedClauseGroupId')}
                    row.update(id=tid,content='\n'.join(x['text'] for x in parts),parts=parts,
                        documentId=base['documentId']+':physical-tables',sourceHash=digest({'native':base['sourceHash'],'channel':'physical-tables-v1'}),
                        pageNo=number,anchor=parts[0]['anchor'],sourceQuality=q,sourceQualityHash=digest(q),
                        metadataVersion='saved-physical-table-development-baseline-v1',segmentationVersion='full-physical-table-row-projection-v1',
                        nativeTableMetadataVersion='complete-physical-table-artifact-v1',
                        observedNodeId=None,observedParentId=None,observedNodeKind='physical_table',observedSourceRole='physical_table',observedLabelRaw=None,
                        sourceArtifactReferences={'completePhysicalTable':ad,'rawPageTables':raw_desc,'nativeBaseline':manifest['corpus']},
                        originalPageNos=[number],tableArtifact=ad,tableIndex=index,parentPartOrdinal=1,
                        fullObservedParentPreserved=True,semanticOwner=None,footnoteOwner=None,crossPageGroup=None)
                    paragraph_map(row);supplement.append(row)
        if size!=raw_desc['bytes'] or h.hexdigest()!=raw_desc['sha256']:raise ValueError('Saved table stream changed')
        stream_reports.append({'input':raw_desc,'pages':pages,'tables':tables,'singleStreamHashVerified':True})
    if seen_pages!=set(range(1127,4301)):raise ValueError('Complete179 table page range differs')
    final=native+supplement;cp=out/'corpus.jsonl'
    with cp.open('x',encoding='utf-8',newline='\n') as stream:
        for row in final:stream.write(json.dumps(row,ensure_ascii=False,separators=(',',':'))+'\n')
    table_manifest=write_json(out/'table-artifacts.json',{'schema':'consense.complete-physical-table-artifacts.v1','tables':complete,
        'rawStreams':stream_reports,'sourceQualityAccepted':False,'semanticScopeVerified':False,'crossPageAccepted':False})
    result={**manifest,'corpus':descriptor(cp),'parentCount':len(final),'textCharacters':sum(len(c['content'])for c in final),
        'familyParents':dict(Counter(c['sourceQuality']['family']for c in final)),
        'nativeBaseline':descriptor(args.base_manifest),'nativeParentCount':len(native),'nativeTextAndPartsUnchanged':True,
        'physicalTableParents':len(supplement),'completePhysicalTableArtifacts':len(complete),'tableArtifacts':table_manifest,
        'tableRawSingleStreamReadBytes':sum(r['input']['bytes']for r in stream_reports),'largeRawExpandedOrRead':True,
        'rawReadPolicy':'one page at a time to project complete saved table objects; never entire stream in memory',
        'parserOCRModelDatabaseNetworkCalls':0,'tableProjectionCode':descriptor(__file__)}
    md=write_json(out/'corpus-manifest.json',result)
    print(json.dumps({'manifest':md,'nativeParents':len(native),'completeTableArtifacts':len(complete),'indexedTableParents':len(supplement),
        'finalParents':len(final),'textCharacters':result['textCharacters'],'pages':len(seen_pages),'qualityAccepted':False}),flush=True)

if __name__=='__main__':main()
