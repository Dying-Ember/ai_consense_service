package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact SCT6 parent/child scope: business applicability and editorial cleanup have separate owners. */
class DraftSctSubitemExecutionScopeTest {
    static final String SITE="sct-site-formation-submissions",DOMESTIC="sct-domestic-proposals",PAYMENT="sct-domestic-payment-proposal",LIABILITY="sct-domestic-submission-liability";
    static Map<String,Object> one(String id,Map<String,Object> values) {
        Map<String,Object> full=DraftBusinessRules.plan(values);
        Map<String,Object> a=DraftingPlanGenerationStateTest.action(full,id);
        List<Object> issues=new ArrayList<>();for(Object raw:DraftBusinessRules.list(full.get("unresolved")))if(id.equals(DraftBusinessRules.asMap(raw).get("id")))issues.add(raw);
        full.put("actions",new ArrayList<>(Collections.singletonList(a)));full.put("unresolved",issues);return full;
    }
    static String text(int... ordinals) {StringJoiner r=new StringJoiner("\n");for(int n:ordinals)r.add(DraftBusinessRules.standardParagraph("SCT",n));return r.toString();}
    static String normalized(String text) {return text.replaceAll("(?U)\\s+"," ").trim();}
    static byte[] original()throws Exception {return Files.readAllBytes(DraftDomesticRetainExecutionStateTest.capture().resolve("SCT-source.docx"));}
    static List<Map<String,Object>> bindings(TemplateIndex index,Map<String,Object> plan) {List<Map<String,Object>> source=DraftDocumentBindings.source("SCT",index);DraftDocumentBindings.associateTargets("SCT",index,plan,source);return source;}
    static Map<String,Object> row(List<Map<String,Object>> b,int ordinal) {return b.stream().filter(x->Integer.valueOf(ordinal).equals(x.get("sourceParagraphOrdinal"))).findFirst().orElseThrow(AssertionError::new);}
    static DocxEditResult compile(byte[] source,Map<String,Object> plan,List<Map<String,Object>> bindings) {return DraftFormattedRules.compile(source,"SCT",plan,DraftDocumentBindings.bookmarkNames(bindings));}
    static boolean hasOperation(List<Map<String,Object>> bindings,int ordinal,String id) {for(Object raw:DraftBusinessRules.list(row(bindings,ordinal).get("operationIds")))if(String.valueOf(raw).startsWith(id+"-"))return true;return false;}

    @Test void falseSiteFactSettlesNegativeButTrueOrUnknownCannotInventPreAdoption() {
        assertEquals("not_used",DraftingPlanGenerationStateTest.action(DraftBusinessRules.plan(DraftBusinessRules.map("siteFormationWorks",false)),SITE).get("action"));
        for(Map<String,Object> values:Arrays.asList(DraftBusinessRules.map("siteFormationWorks",true),new LinkedHashMap<String,Object>(),DraftBusinessRules.map("siteFormationWorks","unknown")))assertEquals("pending",DraftingPlanGenerationStateTest.action(DraftBusinessRules.plan(values),SITE).get("action"));
    }
    @Test void falseSiteClearsAllThreeChildrenAndKeepsTheCNumber() {
        Map<String,Object> p=one(SITE,DraftBusinessRules.map("siteFormationWorks",false));String emitted=DraftClauseRules.apply(text(849,851,854,856),"SCT",p);
        assertEquals("applied",DraftingPlanGenerationStateTest.action(p,SITE).get("applicationState"));assertTrue(emitted.contains("(c) Not used."));
        for(int n:new int[]{851,854,856})assertFalse(emitted.contains(DraftBusinessRules.standardParagraph("SCT",n)),"child P"+n+" must disappear with parent (c)");
    }
    @Test void domesticTrueRetainsCompleteParentAndBothChildrenWithoutBorrowingGuidance() {
        Map<String,Object> p=one(DOMESTIC,DraftBusinessRules.map("domesticBlocks",true));String original=text(859,862,864,871);
        assertEquals(original,DraftClauseRules.apply(original,"SCT",p));assertEquals("retained",DraftingPlanGenerationStateTest.action(p,DOMESTIC).get("applicationState"));
    }
    @Test void domesticFalseUsesNotUsedForTheWholeTwoAndClearsBothChildren() {
        Map<String,Object> p=one(DOMESTIC,DraftBusinessRules.map("domesticBlocks",false));String emitted=DraftClauseRules.apply(text(859,862,864,871),"SCT",p);
        assertEquals("applied",DraftingPlanGenerationStateTest.action(p,DOMESTIC).get("applicationState"));assertTrue(emitted.contains("(2) Not used"));
        for(int n:new int[]{864,871})assertFalse(emitted.contains(DraftBusinessRules.standardParagraph("SCT",n)));
        assertTrue(emitted.contains(DraftBusinessRules.standardParagraph("SCT",859)),"The business projection must not claim its separately owned editorial removal.");
    }
    @Test void changedDomesticChildFailsTheWholeParentScopeClosed() {
        for(boolean domestic:Arrays.asList(true,false)) {
            Map<String,Object> p=one(DOMESTIC,DraftBusinessRules.map("domesticBlocks",domestic));
            String source=text(859,862,864,871).replace("special terms of payment","changed project-specific terms");
            assertEquals(source,DraftClauseRules.apply(source,"SCT",p));assertEquals("unresolved",DraftingPlanGenerationStateTest.action(p,DOMESTIC).get("applicationState"));
        }
    }
    @Test void realFalseSiteExecutesExactTwoRowsAndLeavesNeighborBodiesIntact()throws Exception {
        byte[] original=original();Map<String,Object> p=one(SITE,DraftBusinessRules.map("siteFormationWorks",false));
        TemplateIndex source=new DocxTemplateEditor().inspect(original);List<Map<String,Object>> b=bindings(source,p);DocxEditResult result=compile(original,p,b);
        List<Map<String,Object>> generated=DraftDocumentBindings.generated(b,result);Path out=DraftDomesticRetainExecutionStateTest.out("site-false");
        Files.write(out.resolve("SCT-result.docx"),result.getDocxBytes());DraftDomesticRetainExecutionStateTest.write(out,"bindings.json",generated);DraftDomesticRetainExecutionStateTest.write(out,"ledger.json",result.getLedger());DraftDomesticRetainExecutionStateTest.write(out,"plan.json",p);
        assertEquals("applied",DraftingPlanGenerationStateTest.action(p,SITE).get("applicationState"));assertTrue(normalized(DraftFormattedRules.content(result.getIndex())).contains("(c) Not used."));
        for(int ordinal:new int[]{849,851,854,856})assertTrue(hasOperation(generated,ordinal,SITE),"Executed business receipt required for c scope P"+ordinal);
        assertTrue(DraftBusinessRules.list(row(generated,853).get("editorialOperationIds")).contains("SCT-editorial-note-36-P853"));assertFalse(hasOperation(generated,853,SITE));
        for(int n:new int[]{851,854,856})assertFalse(DraftFormattedRules.content(result.getIndex()).contains(DraftBusinessRules.standardParagraph("SCT",n)));
        for(int n:new int[]{839,846,862,864,871})assertTrue(NativeDocxComparison.retainsParagraph(original,n,result.getDocxBytes()),"Neighbor native body P"+n+" survives unchanged.");
        assertEquals(row(b,854).get("sourceParagraphId").toString().substring(0,row(b,854).get("sourceParagraphId").toString().lastIndexOf("/w:tc")),row(b,856).get("sourceParagraphId").toString().substring(0,row(b,856).get("sourceParagraphId").toString().lastIndexOf("/w:tc")));
    }
    @Test void realForeignEditionCannotBorrowAutomaticSiteNegative()throws Exception {
        byte[] original=original(),foreign;ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(original));ZipOutputStream zip=new ZipOutputStream(out)) {ZipEntry entry;while((entry=in.getNextEntry())!=null){zip.putNextEntry(new ZipEntry(entry.getName()));byte[] bytes=in.readAllBytes();zip.write(bytes);if(entry.getName().equals("docProps/core.xml"))zip.write(' ');zip.closeEntry();}}foreign=out.toByteArray();
        assertNotEquals(DraftPdfConverter.sha256(original),DraftPdfConverter.sha256(foreign));Map<String,Object> p=one(SITE,DraftBusinessRules.map("siteFormationWorks",false));
        TemplateIndex source=new DocxTemplateEditor().inspect(foreign);DocxEditResult result=compile(foreign,p,bindings(source,p));
        assertEquals("unresolved",DraftingPlanGenerationStateTest.action(p,SITE).get("applicationState"));
        for(int n:new int[]{849,851,854,856})assertTrue(NativeDocxComparison.retainsParagraph(foreign,n,result.getDocxBytes()));assertTrue(result.getLedger().stream().noneMatch(x->x.getOperationId().startsWith(SITE+"-")));
    }
    @Test void realDomesticWholeScopeAndPaymentChildHavePreciseAssociations()throws Exception {
        TemplateIndex index=new DocxTemplateEditor().inspect(original());Map<String,Object> full=DraftBusinessRules.plan(DraftBusinessRules.map("domesticBlocks",true,"precastFacadePermission",true));
        full.put("actions",new ArrayList<>(Arrays.asList(DraftingPlanGenerationStateTest.action(full,DOMESTIC),DraftingPlanGenerationStateTest.action(full,PAYMENT))));
        List<Map<String,Object>> b=bindings(index,full);
        for(int n:new int[]{859,862,864,871})assertTrue(DraftBusinessRules.list(row(b,n).get("actionIds")).contains(DOMESTIC),"Entire 2 scope includes heading, a, b and its own guidance.");
        for(int n:new int[]{859,862,864})assertFalse(DraftBusinessRules.list(row(b,n).get("actionIds")).contains(PAYMENT),"Payment child cannot claim parent heading, a or parent guidance.");
        assertEquals(Collections.singletonList("domesticBlocks"),row(b,864).get("fieldKeys"));
        assertTrue(DraftBusinessRules.list(row(b,871).get("actionIds")).contains(PAYMENT));
        for(int n:new int[]{849,851,854,856,886})assertTrue(DraftBusinessRules.list(row(b,n).get("actionIds")).isEmpty());
        for(boolean domestic:Arrays.asList(true,false)) {
            Map<String,Object> p=one(DOMESTIC,DraftBusinessRules.map("domesticBlocks",domestic));List<Map<String,Object>> source=bindings(index,p);DocxEditResult result=compile(original(),p,source);List<Map<String,Object>> generated=DraftDocumentBindings.generated(source,result);
            Path output=DraftDomesticRetainExecutionStateTest.out("domestic-"+domestic);Files.write(output.resolve("SCT-result.docx"),result.getDocxBytes());DraftDomesticRetainExecutionStateTest.write(output,"bindings.json",generated);DraftDomesticRetainExecutionStateTest.write(output,"plan.json",p);
            assertEquals(domestic?"retained":"applied",DraftingPlanGenerationStateTest.action(p,DOMESTIC).get("applicationState"));
            assertTrue(DraftBusinessRules.list(row(generated,859).get("editorialOperationIds")).contains("SCT-editorial-note-07-P859"));assertFalse(hasOperation(generated,859,DOMESTIC));
            for(int n:new int[]{862,864,871})if(domestic)assertTrue(NativeDocxComparison.retainsParagraph(original(),n,result.getDocxBytes()));else assertTrue(hasOperation(generated,n,DOMESTIC));
            for(int n:new int[]{849,851,854,856,886})assertTrue(NativeDocxComparison.retainsParagraph(original(),n,result.getDocxBytes()));
        }
    }
    @Test void liabilityRetainsOrAmendsOnlyTheExactContentControlledByEachFact() {
        String source=text(895,898,899),body=DraftBusinessRules.standardParagraph("SCT",898);
        for(boolean domestic:Arrays.asList(true,false))for(boolean permission:Arrays.asList(true,false)) {
            Map<String,Object> p=one(LIABILITY,DraftBusinessRules.map("domesticBlocks",domestic,"precastFacadePermission",permission));String emitted=DraftClauseRules.apply(source,"SCT",p);
            assertTrue(emitted.contains(DraftBusinessRules.standardParagraph("SCT",895)));assertTrue(emitted.contains(DraftBusinessRules.standardParagraph("SCT",899)),"Business projection cannot borrow the separately owned right-column instruction.");
            assertEquals(domestic&&permission?"retained":"applied",DraftingPlanGenerationStateTest.action(p,LIABILITY).get("applicationState"));
            if(!domestic){assertTrue(emitted.contains("(c) Not used"));assertFalse(emitted.contains("Any comments on"));}
            else if(permission)assertEquals(source,emitted);
            else assertTrue(emitted.contains(body.replace("(#and the submission for special terms of payment)","").replaceFirst("^\\*","")),"Only the identified payment parenthesis is removed; the rest of c survives.");
        }
    }
    @Test void liabilityUnknownFactsDoNotInventAChoiceButFalseParentDominates() {
        for(Map<String,Object> values:Arrays.asList(DraftBusinessRules.map("domesticBlocks",true),DraftBusinessRules.map("precastFacadePermission",false),new LinkedHashMap<String,Object>())) {
            Map<String,Object> p=one(LIABILITY,values);String source=text(895,898,899);assertEquals(source,DraftClauseRules.apply(source,"SCT",p));assertEquals("pending",DraftingPlanGenerationStateTest.action(p,LIABILITY).get("applicationState"));
        }
        Map<String,Object> p=one(LIABILITY,DraftBusinessRules.map("domesticBlocks",false));String emitted=DraftClauseRules.apply(text(895,898,899),"SCT",p);assertTrue(emitted.contains("(c) Not used"));
    }
    @Test void realLiabilitySeparatesWholeParentFromItsPaymentPhraseAndBothGuides()throws Exception {
        byte[] original=original();TemplateIndex index=new DocxTemplateEditor().inspect(original);
        List<org.junit.jupiter.api.function.Executable> assertions=new ArrayList<>();
        for(boolean domestic:Arrays.asList(true,false))for(boolean permission:Arrays.asList(true,false)) {
            Map<String,Object> p=one(LIABILITY,DraftBusinessRules.map("domesticBlocks",domestic,"precastFacadePermission",permission));List<Map<String,Object>> source=bindings(index,p);DocxEditResult result=compile(original,p,source);List<Map<String,Object>> generated=DraftDocumentBindings.generated(source,result);
            Path output=DraftDomesticRetainExecutionStateTest.out("liability-"+domestic+"-"+permission);Files.write(output.resolve("SCT-result.docx"),result.getDocxBytes());DraftDomesticRetainExecutionStateTest.write(output,"bindings.json",generated);DraftDomesticRetainExecutionStateTest.write(output,"ledger.json",result.getLedger());DraftDomesticRetainExecutionStateTest.write(output,"plan.json",p);
            assertions.add(()->assertEquals(domestic&&permission?"retained":"applied",DraftingPlanGenerationStateTest.action(p,LIABILITY).get("applicationState")));
            assertions.add(()->assertEquals(Collections.singletonList("domesticBlocks"),row(source,895).get("fieldKeys")));
            assertions.add(()->assertEquals(Collections.singletonList("precastFacadePermission"),row(source,899).get("fieldKeys")));
            for(int n:new int[]{895,899}) {
                assertions.add(()->assertFalse(hasOperation(generated,n,LIABILITY)));
                assertions.add(()->assertFalse(DraftBusinessRules.list(row(generated,n).get("editorialOperationIds")).isEmpty()));
            }
            assertions.add(()->assertEquals(!domestic||!permission,hasOperation(generated,898,LIABILITY)));
            assertions.add(()->assertTrue(NativeDocxComparison.retainsParagraph(original,889,result.getDocxBytes())));
            assertions.add(()->assertTrue(NativeDocxComparison.retainsParagraph(original,892,result.getDocxBytes())));
            assertions.add(()->assertTrue(NativeDocxComparison.retainsParagraph(original,901,result.getDocxBytes())));
            String emitted=String.valueOf(row(generated,898).get("text"));
            if(!domestic)assertions.add(()->assertEquals("(c) Not used",normalized(emitted)));
            else if(permission)assertions.add(()->assertEquals(index.mainParagraph(898).getText(),emitted));
            else assertions.add(()->assertEquals(normalized(index.mainParagraph(898).getText()).replace("(#and the submission for special terms of payment)","").replaceFirst("^\\*","").replace("  "," "),normalized(emitted)));
        }
        assertAll(assertions);
    }
}
