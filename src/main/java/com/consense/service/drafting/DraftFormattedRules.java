package com.consense.service.drafting;

import com.consense.common.*;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.util.*;

/** Source-bound formatting/compiler seam; decisions remain owned by DraftClauseRules. */
final class DraftFormattedRules {
    private static final Map<String,Object> GUIDANCE=guidance();
    private static Map<String,Object> guidance() {
        try(InputStream in=DraftFormattedRules.class.getResourceAsStream("/drafting/guidance-compiler-bindings.json")) {
            if(in==null)throw new IOException("Missing verified guidance bindings");
            return JsonUtils.mapper().readValue(in,Map.class);
        }catch(IOException failure){throw new IllegalStateException("Verified guidance catalog unavailable",failure);}
    }
    static DocxEditResult compile(byte[] original,String key,Map<String,Object> plan) {
        return compile(original,key,plan,Collections.emptyMap());
    }
    static DocxEditResult compile(byte[] original,String key,Map<String,Object> plan,Map<String,String> bookmarks) {
        return compile(original,key,plan,bookmarks,null);
    }
    static DocxEditResult compile(byte[] original,String key,Map<String,Object> plan,Map<String,String> bookmarks,NativeLayoutPlan layout) {
        DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex index=editor.inspect(original);List<Edit> edits=new ArrayList<>();
        DraftSourceEditCompiler compiler=new DraftSourceEditCompiler(index,key);
        DraftClauseRules.apply(compiler.projection(),key,plan,compiler);
        List<Map<String,Object>> verified=new ArrayList<>();
        for(Object raw:DraftBusinessRules.list(GUIDANCE.get("operations"))) {
            Map<String,Object> op=DraftBusinessRules.asMap(raw);if(!key.equals(op.get("sourceKey")))continue;
            if(!index.getSourceSha256().equals(op.get("sourceSha256"))) {
                addIssue(plan,key,"EDITORIAL_SOURCE_EDITION_UNVERIFIED","Verified preparer-note bindings do not match this uploaded DOCX edition.");break;
            }
            Paragraph p=index.mainParagraph(((Number)op.get("p")).intValue());
            if(!p.getCatalogText().equals(op.get("exactText"))||!DraftAdoption.hash(p.getCatalogText()).equals(op.get("textSha256")))throw new BizException(4012,"SOURCE_BINDING_MISMATCH: "+op.get("id"));
            verified.add(op);
            // These four audited rows contain only this bound preparer note and plain empty cells.
            if("SCT".equals(key)&&"clear_text".equals(op.get("operation"))&&Arrays.asList(55,476,547,582).contains(p.getOrdinal()))compiler.removeVerifiedGuidanceRow(String.valueOf(op.get("id")),p);
        }
        edits.addAll(compiler.edits());Set<String> selected=compiler.changedIds();
        for(Map<String,Object> op:verified) {
            Paragraph p=index.mainParagraph(((Number)op.get("p")).intValue());
            String id=String.valueOf(op.get("id"));
            if(selected.contains(p.getId()))continue;
            if("clear_text".equals(op.get("operation")))edits.add(Edit.clearParagraphAndNumbering(id,p));
            else if("remove_prefix_marker".equals(op.get("operation")))edits.add(Edit.replaceCatalogSpan(id,p,0,1,"*",""));
            else throw new BizException(4012,"UNSUPPORTED_GUIDANCE_OPERATION: "+id);
        }
        if("SCT".equals(key))keepVerifiedFractionTogether(index,selected,edits,plan);
        return editor.applyWithParagraphBookmarks(original,new SourceEditBatch(index.getSourceSha256(),edits),bookmarks,!bookmarks.isEmpty(),layout);
    }
    private static void keepVerifiedFractionTogether(TemplateIndex index,Set<String> selected,List<Edit> edits,Map<String,Object> plan) {
        // The audited fraction splits after earlier branches are removed. The assessed renderer ignores
        // nested-row controls; only its containing source row needs a cantSplit pagination property.
        if(!"7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4".equals(index.getSourceSha256())) {
            addIssue(plan,"SCT","SOURCE_LAYOUT_COHESION_UNVERIFIED","The audited preliminaries fraction does not match this uploaded DOCX edition.");return;
        }
        Paragraph label=index.mainParagraph(806),numerator=index.mainParagraph(810),denominator=index.mainParagraph(815);
        NodeAnchor table=index.anchor(label.getTableId());
        if(!"Percentage of Preliminaries".equals(label.getCatalogText())||!"Total of Bill No. 1".equals(numerator.getCatalogText())||!"Amount of Builder’s Works".equals(denominator.getCatalogText())||table.getParagraphIds().size()!=11||!table.getId().equals(numerator.getTableId())||!table.getId().equals(denominator.getTableId()))throw new BizException(4012,"SOURCE_FORMULA_BINDING_MISMATCH");
        NodeAnchor row=index.anchor(index.mainParagraph(796).getRowId());
        if(row.getParagraphIds().size()!=23||!row.getParagraphIds().containsAll(table.getParagraphIds())||!row.getId().equals(index.mainParagraph(818).getRowId()))throw new BizException(4012,"SOURCE_FORMULA_BINDING_MISMATCH");
        for(String id:row.getParagraphIds())if(selected.contains(id))throw new BizException(4012,"SOURCE_FORMULA_COHESION_SCOPE_CHANGED");
        edits.add(Edit.keepRowTogether("sct-preliminaries-fraction-cohesion",row));
    }
    static void addIssue(Map<String,Object> plan,String key,String code,String message) {
        DraftBusinessRules.list(plan.get("unresolved")).add(DraftBusinessRules.map("id","format-"+key+"-"+code,"kind","SourceFormat","document",key,"code",code,"message",com.consense.common.LocalizedText.of(message,message,message)));
    }
    static List<Map<String,Object>> blocks(TemplateIndex index) {
        List<Map<String,Object>> result=new ArrayList<>();
        Set<String> protectedFields=fieldProtectedIds(index);
        for(Paragraph p:index.getMainParagraphs()) {
            String unsupported=blockUnsupportedReason(p,protectedFields.contains(p.getId()));
            result.add(DraftBusinessRules.map("id",p.getId(),"text",p.getText(),"textHash",DraftAdoption.hash(p.getText()),"paragraphOrdinal",p.getOrdinal(),"cellId",p.getCellId(),"editable",unsupported==null,"unsupportedReason",unsupported));
        }
        return result;
    }
    static boolean fieldProtected(TemplateIndex index,Paragraph p) {
        return fieldProtectedIds(index).contains(p.getId());
    }
    static String blockUnsupportedReason(TemplateIndex index,Paragraph p) {
        return blockUnsupportedReason(p,fieldProtected(index,p));
    }
    private static String blockUnsupportedReason(Paragraph p,boolean field) {
        return field?"FIELD_TEXT_EDIT_UNSUPPORTED":p.getCatalogText().isEmpty()?"EMPTY_SPAN_UNSUPPORTED":null;
    }
    static boolean hasNativeMath(TemplateIndex index,Paragraph p) {
        for(Feature f:index.getFeatures())if("OMML".equals(f.getKind())&&f.getStartId().startsWith(p.getId()+"/"))return true;
        return false;
    }
    private static Set<String> fieldProtectedIds(TemplateIndex index) {
        Set<String> result=new HashSet<>();
        for(Feature f:index.getFeatures())if("FIELD".equals(f.getKind())&&"word/document.xml".equals(f.getPart())) {
            int start=-1,end=-1;
            for(Paragraph candidate:index.getMainParagraphs()) {
                if(f.getStartId().startsWith(candidate.getId()+"/"))start=candidate.getOrdinal();
                if(f.getEndId()!=null&&f.getEndId().startsWith(candidate.getId()+"/"))end=candidate.getOrdinal();
            }
            if(start>0)for(Paragraph p:index.getMainParagraphs())if(p.getOrdinal()>=start&&(end<0||p.getOrdinal()<=end))result.add(p.getId());
        }
        return result;
    }
    static String content(TemplateIndex index) {StringJoiner content=new StringJoiner("\n");for(Paragraph p:index.getMainParagraphs())content.add(p.getText());return content.toString();}
}
