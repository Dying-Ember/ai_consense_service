package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** One full current SCC output checks the related references and TOC alongside all reviewed negatives. */
class DraftCurrentNegativeCombinedReplayTest {
    static final List<String> CHILDREN=Arrays.asList("scc-general-fluctuation-references","scc-safety-pricing-SCC20.303","scc-advance-fluctuation-references","scc-advance-repayment-standard","scc-railway-plan-inspection","scc-railway-eot-option","scc-foundation-version-reference","scc-ovt-number-protection","scc-ovt-number-liability","scc-ovt-number-proviso","scc-definition-collection","scc-training-numbering");
    @Test void currentSourceSavedDocxSynchronizesAllNegativeBodiesTitlesAndRelatedReferences()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("consense.audit.captureDir")!=null&&!System.getProperty("consense.audit.captureDir").isBlank()&&System.getProperty("consense.audit.runDir")!=null&&!System.getProperty("consense.audit.runDir").isBlank(),"Actual-source replay requires explicit captureDir and a unique runDir; no original or user answers are committed.");
        Path capture=Paths.get(System.getProperty("consense.audit.captureDir"));
        Map<String,Object> values=DraftBusinessRules.asMap(DraftBusinessRules.asMap(JsonUtils.readMap(Files.readString(capture.resolve("plan-api.json"))).get("data")).get("inputValues"));
        String before=JsonUtils.write(values);Map<String,Object> plan=DraftBusinessRules.plan(values);
        Path output=Paths.get(System.getProperty("consense.audit.runDir")).resolve("actual-output");Files.createDirectories(output);
        TemplateIndex sct=new DocxTemplateEditor().inspect(Files.readAllBytes(capture.resolve("SCT-source.docx")));
        List<Map<String,Object>> sctBindings=DraftDocumentBindings.source("SCT",sct);DraftDocumentBindings.associateTargets("SCT",sct,plan,sctBindings);
        DocxTemplateEditor.DocxEditResult sctResult=DraftFormattedRules.compile(Files.readAllBytes(capture.resolve("SCT-source.docx")),"SCT",plan,DraftDocumentBindings.bookmarkNames(sctBindings));
        Files.write(output.resolve("SCT-current-combined.docx"),sctResult.getDocxBytes());Files.writeString(output.resolve("SCT-generated-bindings.json"),JsonUtils.write(DraftDocumentBindings.generated(sctBindings,sctResult)));
        assertEquals("applied",action(plan,"sct-foundation-assessment").get("applicationState"));
        assertFalse(sctResult.getIndex().getMainParagraphs().stream().anyMatch(p->p.getCatalogText().equals(DraftBusinessRules.standardParagraph("SCT",848))));
        TemplateIndex source=new DocxTemplateEditor().inspect(Files.readAllBytes(capture.resolve("SCC-source.docx")));
        List<Map<String,Object>> bindings=DraftDocumentBindings.source("SCC",source);DraftDocumentBindings.associateTargets("SCC",source,plan,bindings);
        DocxTemplateEditor.DocxEditResult result=DraftFormattedRules.compile(Files.readAllBytes(capture.resolve("SCC-source.docx")),"SCC",plan,DraftDocumentBindings.bookmarkNames(bindings));
        List<Map<String,Object>> generated=DraftDocumentBindings.generated(bindings,result);Files.writeString(output.resolve("SCC-generated-bindings.json"),JsonUtils.write(generated));
        Files.write(output.resolve("SCC-negative-combined.docx"),result.getDocxBytes());
        Files.writeString(output.resolve("plan-after.json"),JsonUtils.write(plan));
        Map<String,Object> receipt=DraftBusinessRules.map("sourceSha256",source.getSourceSha256(),"resultSha256",result.getDocxSha256(),"parents",DraftCurrentNegativeOutputReplayTest.ordinaryParents().map(id->action(plan,id)).collect(Collectors.toList()),"children",CHILDREN.stream().map(id->action(plan,id)).collect(Collectors.toList()),"affectedFeatures",result.getAffectedFeatures(),"ledger",result.getLedger(),"confirmedInputsUnchanged",before.equals(JsonUtils.write(values)),"recognitionCalls",0,"databaseWrites",0);
        Files.writeString(output.resolve("output-receipt.json"),JsonUtils.write(receipt));
        List<Paragraph> paragraphs=result.getIndex().getMainParagraphs();
        for(String id:DraftCurrentNegativeOutputReplayTest.ordinaryParents().collect(Collectors.toList())){
            Map<String,Object> a=action(plan,id);assertEquals("applied",a.get("applicationState"),id+" "+JsonUtils.write(a));
            DraftClauseRules.NegativeScope scope=DraftClauseRules.negativeScope(a);assertNotNull(scope,id);
            String toc=scope.clause+"\tNot used\t"+source.mainParagraph(scope.toc).getText().substring(source.mainParagraph(scope.toc).getText().lastIndexOf('\t')+1);
            assertTrue(paragraphs.stream().anyMatch(p->toc.equals(p.getText())),id+" exact native TOC label/page field text");
            assertTrue(paragraphs.stream().filter(p->!p.getText().equals(toc)).anyMatch(p->p.getText().replaceAll("(?U)\\s+"," ").trim().equals(scope.clause+" Not used")),id+" actual body heading");
            int appliedRows=0;for(Map<String,Object> row:generated)if(DraftBusinessRules.list(row.get("operationIds")).stream().anyMatch(op->String.valueOf(op).startsWith(id+"-"))){appliedRows++;assertTrue(DraftBusinessRules.list(row.get("appliedActionIds")).contains(id),id+" actual operation must be attached to its exact action");assertTrue(DraftBusinessRules.list(row.get("fieldKeys")).containsAll(DraftBusinessRules.list(a.get("inputKeys"))),id+" actual operation must be attached to its confirmed input fields");}
            assertTrue(appliedRows>0,id+" actual operation bindings");Map<String,Object> tocBinding=generated.stream().filter(row->Objects.equals(scope.toc,row.get("sourceParagraphOrdinal"))).findFirst().orElseThrow();assertTrue(DraftBusinessRules.list(tocBinding.get("appliedActionIds")).contains(id),id+" TOC operation binding");
            if(scope.last<2074){Map<String,Object> boundaryBinding=generated.stream().filter(row->Objects.equals(scope.last+1,row.get("sourceParagraphOrdinal"))).findFirst().orElseThrow();assertFalse(DraftBusinessRules.list(boundaryBinding.get("actionIds")).contains(id),id+" must not claim the following chapter/clause as its own target");}
            if(scope.last<2074){String boundary=DraftBusinessRules.standardParagraph("SCC",scope.last+1);for(String otherId:DraftCurrentNegativeOutputReplayTest.ordinaryParents().collect(Collectors.toList())){DraftClauseRules.NegativeScope other=DraftClauseRules.negativeScope(action(plan,otherId));if(other!=null&&other.first==scope.last+1)boundary=other.clause+" Not used";}String expectedBoundary=boundary.replaceAll("(?U)\\s+","");assertTrue(paragraphs.stream().anyMatch(p->p.getCatalogText().replaceAll("(?U)\\s+","").equals(expectedBoundary)),id+" following chapter/clause boundary must survive");}
        }
        for(String id:CHILDREN)assertTrue(Arrays.asList("applied","retained").contains(action(plan,id).get("applicationState")),id+" "+JsonUtils.write(action(plan,id)));
        String fluctuations=String.valueOf(generated.stream().filter(row->Objects.equals(1430,row.get("sourceParagraphOrdinal"))).findFirst().orElseThrow().get("text"));assertTrue(fluctuations.contains("SCC20.302")&&fluctuations.contains("SCC20.304"));assertFalse(fluctuations.contains("SCC20.303"),"General price-adjustment reference must exclude the Not used specialist clause");
        String definitions=String.valueOf(generated.stream().filter(row->Objects.equals(244,row.get("sourceParagraphOrdinal"))).findFirst().orElseThrow().get("text"));for(String retained:Arrays.asList("SCC1.301","SCC1.302","SCC1.303","SCC1.307"))assertTrue(definitions.contains(retained));for(String excluded:Arrays.asList("SCC1.304","SCC1.305","SCC1.306"))assertFalse(definitions.contains(excluded));String unrelatedTail=DraftBusinessRules.standardParagraph("SCC",244).substring(DraftBusinessRules.standardParagraph("SCC",244).indexOf(", SCC3.301"));assertTrue(definitions.endsWith(unrelatedTail));
        assertFalse(paragraphs.stream().anyMatch(p->p.getCatalogText().equals(DraftBusinessRules.standardParagraph("SCC",955))),"Excision obligation must actually leave the saved DOCX");
        for(Feature field:source.getFeatures())if("FIELD".equals(field.getKind())&&field.getInstruction()!=null&&field.getInstruction().trim().startsWith("PAGEREF "))assertTrue(result.getIndex().getFeatures().stream().anyMatch(f->"FIELD".equals(f.getKind())&&Objects.equals(field.getInstruction(),f.getInstruction())&&f.isComplete()),"Retain exact complete native PAGEREF "+field.getInstruction());
        String body=DraftFormattedRules.content(result.getIndex());
        assertTrue(body.contains("SCC20.302")&&body.contains("SCC20.304"));
        assertTrue(paragraphs.stream().anyMatch(p->p.getCatalogText().equals(DraftBusinessRules.standardParagraph("SCC",1665))));
        assertEquals(before,JsonUtils.write(values));
    }
    private static Map<String,Object> action(Map<String,Object> plan,String id){return DraftBusinessRules.list(plan.get("actions")).stream().map(DraftBusinessRules::asMap).filter(a->id.equals(a.get("id"))).findFirst().orElseThrow();}
}
