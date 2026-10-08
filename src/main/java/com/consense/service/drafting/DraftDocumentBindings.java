package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.web.dto.DraftingDtos.DocumentBindingsVO;
import com.consense.domain.DraftPdfArtifact;
import com.consense.common.BizException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.*;
import java.io.IOException;
import java.util.*;

/** Persistent template targets; paragraph paths are hints, bookmark identity is authoritative. */
final class DraftDocumentBindings {
    private DraftDocumentBindings() { }

    static List<Map<String,Object>> source(String fileKey,TemplateIndex index) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Paragraph paragraph:index.getMainParagraphs())if(!paragraph.getCatalogText().trim().isEmpty()) {
            String name="CS"+DraftAdoption.hash(fileKey+"|"+index.getSourceSha256()+"|"+paragraph.getId()).substring(0,28);
            rows.add(DraftBusinessRules.map("bindingId",name,"bookmarkName",name,"sourceParagraphId",paragraph.getId(),
                    "sourceParagraphOrdinal",paragraph.getOrdinal(),"sourceText",paragraph.getText(),"sourceTextHash",DraftAdoption.hash(paragraph.getText()),
                    "fieldKeys",Collections.emptyList(),"actionIds",Collections.emptyList(),"operationIds",Collections.emptyList(),
                    "applicationStatus","unchanged","locationStatus","exact","geometry",null));
        }
        return rows;
    }
    static Map<String,String> bookmarkNames(List<Map<String,Object>> rows) {
        Map<String,String> names=new LinkedHashMap<>();for(Map<String,Object> row:rows)names.put(String.valueOf(row.get("sourceParagraphId")),String.valueOf(row.get("bookmarkName")));return names;
    }
    static void associateTargets(String fileKey,TemplateIndex source,Map<String,Object> plan,List<Map<String,Object>> rows) {
        if(!DraftTemplateReadingSources.catalogueSourceVerified(fileKey,source.getSourceSha256()))return;
        for(Map<String,Object> row:rows) {
            int ordinal=((Number)row.get("sourceParagraphOrdinal")).intValue();Set<String> fields=new LinkedHashSet<>(),actions=new LinkedHashSet<>();
            for(Object entry:DraftBusinessRules.list(plan.get("actions"))) {
                Map<String,Object> action=DraftBusinessRules.asMap(entry);
                if(!fileKey.equals(action.get("document"))||!paragraphOrdinals(String.valueOf(action.get("paragraphs"))).contains(ordinal))continue;
                actions.add(String.valueOf(action.get("id")));for(Object key:DraftBusinessRules.list(action.get("inputKeys")))fields.add(String.valueOf(key));
            }
            row.put("fieldKeys",new ArrayList<>(fields));row.put("actionIds",new ArrayList<>(actions));
            if(!actions.isEmpty())row.put("applicationStatus","unapplied");
        }
    }
    private static Set<Integer> paragraphOrdinals(String text) {
        Set<Integer> ordinals=new LinkedHashSet<>();java.util.regex.Matcher range=java.util.regex.Pattern.compile("P(\\d+)(?:\\s*[–—-]\\s*P?(\\d+))?").matcher(text);
        while(range.find()) {int start=Integer.parseInt(range.group(1)),end=range.group(2)==null?start:Integer.parseInt(range.group(2));if(end>=start&&end<10000)for(int ordinal=start;ordinal<=end;ordinal++)ordinals.add(ordinal);}
        return ordinals;
    }
    static byte[] sourceWorkingCopy(byte[] original,List<Map<String,Object>> rows) {
        return sourceWorkingCopy(original,rows,null);
    }
    static byte[] sourceWorkingCopy(byte[] original,List<Map<String,Object>> rows,NativeLayoutPlan layout) {
        return sourceWorkingCopyResult(original,rows,layout).getDocxBytes();
    }
    static DocxEditResult sourceWorkingCopyResult(byte[] original,List<Map<String,Object>> rows,NativeLayoutPlan layout){return new DocxTemplateEditor().applyWithParagraphBookmarks(original,new SourceEditBatch(DraftPdfConverter.sha256(original),Collections.emptyList()),bookmarkNames(rows),false,layout);}
    static List<Map<String,Object>> generated(List<Map<String,Object>> source,DocxEditResult result) {
        List<Map<String,Object>> rows=reconcile(source,result.getIndex(),false);
        appendAdded(rows,result,false);
        relateInsertions(rows,source,result.getLedger(),false);
        for(Map<String,Object> row:rows) {
            Set<String> operations=new LinkedHashSet<>();String sourceId=String.valueOf(row.get("sourceParagraphId"));
            if(row.get("sourceParagraphId")==null)for(Object operation:DraftBusinessRules.list(row.get("operationIds")))operations.add(String.valueOf(operation));
            else for(AppliedEdit edit:result.getLedger())for(TargetChange change:edit.getTargets())if(change.getSource().getParagraphIds().contains(sourceId))operations.add(edit.getOperationId());
            Set<String> appliedActions=new LinkedHashSet<>();for(Object action:DraftBusinessRules.list(row.get("actionIds")))for(String operation:operations)if(operation.equals(action)||operation.startsWith(action+"-"))appliedActions.add(String.valueOf(action));
            row.put("operationIds",new ArrayList<>(operations));row.put("appliedActionIds",new ArrayList<>(appliedActions));row.put("documentEditStatus",operations.isEmpty()?"unchanged":"applied");
            if(!appliedActions.isEmpty()||DraftBusinessRules.list(row.get("actionIds")).isEmpty()&&!operations.isEmpty())row.put("applicationStatus","applied");
            if(!operations.isEmpty()&&"missing".equals(row.get("locationStatus")))row.put("locationStatus","removed");
        }
        return rows;
    }
    static List<Map<String,Object>> bodyEdited(List<Map<String,Object>> previous,DocxEditResult result) {
        List<Map<String,Object>> rows=reconcile(previous,result.getIndex(),true);appendAdded(rows,result,true);relateInsertions(rows,previous,result.getLedger(),true);
        Map<String,Map<String,Object>> prior=new HashMap<>();for(Map<String,Object> row:previous)prior.put(String.valueOf(row.get("bindingId")),row);
        for(Map<String,Object> row:rows) {
            Map<String,Object> old=prior.get(String.valueOf(row.get("bindingId")));if(old==null)continue;Set<String> operations=new LinkedHashSet<>();
            for(AppliedEdit edit:result.getLedger())for(TargetChange change:edit.getTargets())if(change.getSource().getParagraphIds().contains(String.valueOf(old.get("resultParagraphId"))))operations.add(edit.getOperationId());
            row.put("bodyOperationIds",new ArrayList<>(operations));
        }
        return rows;
    }
    private static void relateInsertions(List<Map<String,Object>> rows,List<Map<String,Object>> anchors,List<AppliedEdit> ledger,boolean bodyEdit) {
        for(Map<String,Object> row:rows) {
            if(row.get("sourceParagraphId")!=null)continue;
            Set<String> operations=new LinkedHashSet<>();Set<Map<String,Object>> parents=new LinkedHashSet<>();
            for(AppliedEdit edit:ledger)if(edit.getInsertedParagraphIds().contains(String.valueOf(row.get("resultParagraphId")))) {
                operations.add(edit.getOperationId());for(TargetChange target:edit.getTargets())for(Map<String,Object> anchor:anchors)if(target.getSource().getParagraphIds().contains(String.valueOf(anchor.get(bodyEdit?"resultParagraphId":"sourceParagraphId"))))parents.add(anchor);
            }
            if(operations.isEmpty())continue;row.put("operationIds",new ArrayList<>(operations));
            if(parents.size()==1)row.put("parentBindingId",parents.iterator().next().get("bindingId"));
            if(!bodyEdit) {Set<String> fields=new LinkedHashSet<>(),actions=new LinkedHashSet<>();for(Map<String,Object> parent:parents){for(Object field:DraftBusinessRules.list(parent.get("fieldKeys")))fields.add(String.valueOf(field));for(Object action:DraftBusinessRules.list(parent.get("actionIds")))actions.add(String.valueOf(action));}row.put("fieldKeys",new ArrayList<>(fields));row.put("actionIds",new ArrayList<>(actions));}
        }
    }
    static List<Map<String,Object>> reconcile(List<Map<String,Object>> previous,TemplateIndex current,boolean bodyEdit) {
        Map<String,List<Paragraph>> markers=markers(current);List<Map<String,Object>> rows=new ArrayList<>();
        for(Map<String,Object> old:previous) {
            Map<String,Object> row=new LinkedHashMap<>(old);row.put("geometry",null);
            List<Paragraph> targets=markers.getOrDefault(String.valueOf(row.get("bookmarkName")),Collections.emptyList());
            if(targets.size()==1) {
                Paragraph p=targets.get(0);row.put("resultParagraphId",p.getId());row.put("resultParagraphOrdinal",p.getOrdinal());
                row.put("text",p.getText());row.put("textHash",DraftAdoption.hash(p.getText()));
                row.put("locationStatus",p.getCatalogText().isEmpty()?"removed":"exact");
                if(bodyEdit&&!Objects.equals(old.get("textHash"),row.get("textHash")))row.put("applicationStatus","body_edited");
            } else {
                row.put("resultParagraphId",null);row.put("resultParagraphOrdinal",null);row.put("text",null);row.put("textHash",null);
                row.put("locationStatus",targets.isEmpty()?("removed".equals(old.get("locationStatus"))?"removed":"missing"):"conflicted");
            }
            rows.add(row);
        }
        return rows;
    }
    /** Only paragraphs recorded as inserted by this editor may acquire a new sidecar identity. */
    private static void appendAdded(List<Map<String,Object>> rows,DocxEditResult result,boolean bodyEdit) {
        Set<String> known=new HashSet<>(),inserted=new HashSet<>();for(Map<String,Object> row:rows)known.add(String.valueOf(row.get("bookmarkName")));for(AppliedEdit edit:result.getLedger())inserted.addAll(edit.getInsertedParagraphIds());
        for(Map.Entry<String,List<Paragraph>> marker:markers(result.getIndex()).entrySet())if(!known.contains(marker.getKey())&&marker.getValue().size()==1&&inserted.contains(marker.getValue().get(0).getId())) {
            Paragraph paragraph=marker.getValue().get(0);rows.add(DraftBusinessRules.map("bindingId",marker.getKey(),"bookmarkName",marker.getKey(),"sourceParagraphId",null,"sourceParagraphOrdinal",null,"sourceText",null,"sourceTextHash",null,
                    "resultParagraphId",paragraph.getId(),"resultParagraphOrdinal",paragraph.getOrdinal(),"text",paragraph.getText(),"textHash",DraftAdoption.hash(paragraph.getText()),"fieldKeys",Collections.emptyList(),"actionIds",Collections.emptyList(),"appliedActionIds",Collections.emptyList(),"operationIds",Collections.emptyList(),
                    "applicationStatus",bodyEdit?"body_added":"generated_added","documentEditStatus","applied","locationStatus","exact","geometry",null));
        }
    }
    static Map<String,List<Paragraph>> markers(TemplateIndex index) {
        Map<String,List<Paragraph>> found=new LinkedHashMap<>();Map<String,Paragraph> paragraphs=new HashMap<>();for(Paragraph paragraph:index.getMainParagraphs())paragraphs.put(paragraph.getId(),paragraph);
        for(Feature feature:index.getFeatures())if("BOOKMARK".equals(feature.getKind())&&feature.getName()!=null&&feature.getName().matches("CS[A-Za-z0-9]{1,38}")&&feature.isComplete()) {
            String ancestor=feature.getStartId();while(!paragraphs.containsKey(ancestor)&&ancestor.lastIndexOf('/')>=0)ancestor=ancestor.substring(0,ancestor.lastIndexOf('/'));
            Paragraph paragraph=paragraphs.get(ancestor);if(paragraph!=null&&feature.getEndId().startsWith(paragraph.getId()+"/"))found.computeIfAbsent(feature.getName(),name->new ArrayList<>()).add(paragraph);
        }
        return found;
    }
    static List<Map<String,Object>> blocks(TemplateIndex index,List<Map<String,Object>> bindings) {
        List<Map<String,Object>> blocks=DraftFormattedRules.blocks(index);Map<String,String> names=new HashMap<>();
        for(Map<String,Object> binding:bindings)if("exact".equals(binding.get("locationStatus")))names.put(String.valueOf(binding.get("resultParagraphId")),String.valueOf(binding.get("bindingId")));
        for(Map<String,Object> block:blocks)block.put("bindingId",names.get(String.valueOf(block.get("id"))));return blocks;
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> read(String json) {
        return JsonUtils.isBlankText(json)?Collections.emptyList():(List)JsonUtils.readList(json,Map.class);
    }
    static DocumentBindingsVO bundle(String fileKey,String view,String sourceHash,String revisionId,String docxHash,List<Map<String,Object>> rows) {
        DocumentBindingsVO vo=new DocumentBindingsVO();vo.setFileKey(fileKey);vo.setView(view);vo.setSourceSha256(sourceHash);vo.setRevisionId(revisionId);vo.setDocxSha256(docxHash);
        for(Map<String,Object> row:rows){row.put("geometry",null);row.put("geometryStatus","exact".equals(row.get("locationStatus"))?"pending":"unavailable");}
        vo.setGeometryStatus("pending");vo.setBindings(rows);return vo;
    }
    /** PDFBox resolves both legacy /Dests dictionaries and /Names trees using the canonical name. */
    static DocumentBindingsVO locate(DocumentBindingsVO bundle,DraftPdfArtifact artifact) {
        if(artifact==null)return bundle;
        byte[] bytes=artifact.getPdfBytes();
        if(!bundle.getDocxSha256().equals(artifact.getDocxSha256())||!artifact.getPdfSha256().equals(DraftPdfConverter.sha256(bytes)))throw new BizException(4013,"BINDING_PDF_IDENTITY_MISMATCH");
        try(PDDocument pdf=PDDocument.load(bytes)) {
            for(Map<String,Object> row:bundle.getBindings()) {
                row.put("geometry",null);row.put("geometryStatus","unavailable");
                if(!"exact".equals(row.get("locationStatus")))continue;
                PDPageDestination destination=pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination(String.valueOf(row.get("bookmarkName"))));
                if(!(destination instanceof PDPageXYZDestination))continue;
                int pageIndex=destination.retrievePageNumber();if(pageIndex<0||pageIndex>=pdf.getNumberOfPages())continue;
                COSArray coordinates=destination.getCOSObject();COSBase left=coordinates.getObject(2),top=coordinates.getObject(3);
                if(!(left instanceof COSNumber)||!(top instanceof COSNumber))continue; // Null XYZ values are viewport instructions, not an observed point.
                PDPage page=pdf.getPage(pageIndex);PDRectangle box=page.getCropBox();int rotation=((page.getRotation()%360)+360)%360;
                double x=((COSNumber)left).doubleValue()-box.getLowerLeftX(),y=((COSNumber)top).doubleValue()-box.getLowerLeftY(),width=box.getWidth(),height=box.getHeight(),viewX,viewY;
                if(rotation==0){viewX=x;viewY=height-y;}else if(rotation==90){viewX=y;viewY=x;}else if(rotation==180){viewX=width-x;viewY=y;}else if(rotation==270){viewX=height-y;viewY=width-x;}else continue;
                double viewWidth=rotation%180==0?width:height,viewHeight=rotation%180==0?height:width;
                if(!Double.isFinite(viewX)||!Double.isFinite(viewY)||viewX<0||viewY<0||viewX>viewWidth||viewY>viewHeight)continue;
                row.put("geometry",DraftBusinessRules.map("kind","point","pageNumber",pageIndex+1,"x",viewX,"y",viewY,"pageWidth",viewWidth,"pageHeight",viewHeight,"unit","pt","origin","top-left","pageBox","crop","rotation",rotation));row.put("geometryStatus","ready");
            }
            bundle.setPdfSha256(artifact.getPdfSha256());bundle.setRenderProfileHash(artifact.getRenderProfileHash());bundle.setGeometryStatus("ready");return bundle;
        }catch(IOException invalid){throw new BizException(4013,"BINDING_PDF_INVALID: The saved preview could not be read.");}
    }
}
