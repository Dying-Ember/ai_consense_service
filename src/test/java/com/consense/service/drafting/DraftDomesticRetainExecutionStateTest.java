package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.domain.DraftArtifact;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/** A retained SCT4 body cannot borrow an editorial-only row removal as a business edit. */
class DraftDomesticRetainExecutionStateTest {
    static final String ID="sct-domestic-entire-clause";
    static Map<String,Object> action(String decision) {
        return DraftBusinessRules.map("id",ID,"document","SCT","clause","SCT4","paragraphs","P547–576","action",decision,"decisionAction",decision,"inputKeys",Collections.singletonList("domesticBlocks"));
    }
    static Map<String,Object> plan(Map<String,Object> a) {
        return DraftBusinessRules.map("actions",new ArrayList<>(Collections.singletonList(a)),"unresolved",new ArrayList<>(),"effectiveValues",new LinkedHashMap<>());
    }
    static Path capture() {
        String dir=System.getProperty("consense.audit.planCaptureDir");
        assumeTrue(dir!=null,"Explicit preserved source capture required; no model or business DB.");
        return Paths.get(dir);
    }
    static Path out(String seam)throws Exception {
        String dir=System.getProperty("consense.audit.domesticRetainResultDir");
        assumeTrue(dir!=null,"Explicit isolated replay output directory required.");
        Path out=Paths.get(dir).resolve(seam);Files.createDirectories(out);return out;
    }
    static Map<String,Object> capturedPlan(Path c)throws Exception {
        return DraftBusinessRules.asMap(JsonUtils.readMap(Files.readString(c.resolve("plan-api.json"))).get("data"));
    }
    static boolean matchingBusinessOperation(List<Map<String,Object>> bindings) {
        for(Map<String,Object> b:bindings) {
            if(DraftBusinessRules.list(b.get("appliedActionIds")).contains(ID))return true;
            for(Object raw:DraftBusinessRules.list(b.get("operationIds")))if(String.valueOf(raw).equals(ID)||String.valueOf(raw).startsWith(ID+"-"))return true;
        }
        return false;
    }
    static void write(Path out,String name,Object value)throws Exception {
        Files.writeString(out.resolve(name),JsonUtils.write(value),StandardCharsets.UTF_8);
    }
    static void guidanceReceipt(List<Map<String,Object>> bindings) {
        Map<String,Object> b=bindings.stream().filter(x->Integer.valueOf(547).equals(x.get("sourceParagraphOrdinal"))).findFirst().orElseThrow(AssertionError::new);
        List<Object> operations=DraftBusinessRules.list(b.get("operationIds"));
        assertTrue(operations.stream().anyMatch(x->String.valueOf(x).startsWith("SCT-editorial-note-")&&String.valueOf(x).endsWith("-P547-remove-scope")),"Verified guidance-only row still has its own executed receipt.");
        assertFalse(DraftBusinessRules.list(b.get("appliedActionIds")).contains(ID),"Editorial removal cannot impersonate a business body edit.");
    }

    @Test void retainedBodyDoesNotProduceBusinessSourceChangesFromItsSeparateGuidance() {
        Map<String,Object> a=action("retain"),p=plan(a);
        String source=DraftBusinessRules.standardParagraph("SCT",547)+"\n"+DraftBusinessRules.standardParagraph("SCT",554);
        List<DraftClauseRules.SourceChange> changes=new ArrayList<>();
        String emitted=DraftClauseRules.apply(source,"SCT",p,(adopted,items,changed)->changes.addAll(items));
        assertEquals("retained",a.get("applicationState"),"Checking SCT4 body retention is not an applied business edit.");
        assertTrue(changes.isEmpty(),"The P547 guidance row is owned by the source-bound editorial compiler.");
        assertEquals(source,emitted);
    }
    @Test void missingSubstantiveBodyStillFailsClosed() {
        Map<String,Object> a=action("retain"),p=plan(a);
        String source=DraftBusinessRules.standardParagraph("SCT",547);
        assertEquals(source,DraftClauseRules.apply(source,"SCT",p));
        assertEquals("unresolved",a.get("applicationState"));assertEquals("pending",a.get("action"));
    }
    @Test void explicitNegativeStillEditsTheVerifiedWholeBody() {
        Map<String,Object> a=action("not_used"),p=plan(a);
        String source=DraftBusinessRules.standardParagraph("SCT",550)+"\n"+DraftBusinessRules.standardParagraph("SCT",554)+"\nSCT5 Separate next body\nUNRELATED";
        String emitted=DraftClauseRules.apply(source,"SCT",p);
        assertEquals("applied",a.get("applicationState"));
        assertTrue(emitted.contains("SCT4 Not used"));assertTrue(emitted.contains("SCT5 Separate next body\nUNRELATED"));
        assertFalse(emitted.contains(DraftBusinessRules.standardParagraph("SCT",554)));
    }
    @Test void realSctSourceCompileRetainsBusinessBodyAndRecordsEditorialRowSeparately()throws Exception {
        Path c=capture(),out=out("source");Map<String,Object> p=capturedPlan(c);
        byte[] original=Files.readAllBytes(c.resolve("SCT-source.docx"));
        TemplateIndex index=new DocxTemplateEditor().inspect(original);
        List<Map<String,Object>> source=DraftDocumentBindings.source("SCT",index);
        DraftDocumentBindings.associateTargets("SCT",index,p,source);
        DocxEditResult generated=DraftFormattedRules.compile(original,"SCT",p,DraftDocumentBindings.bookmarkNames(source),DraftNativeLayout.plan(original,"SCT","libreoffice"));
        List<Map<String,Object>> bindings=DraftDocumentBindings.generated(source,generated);
        Files.write(out.resolve("SCT-result.docx"),generated.getDocxBytes());
        write(out,"plan.json",p);write(out,"bindings.json",bindings);write(out,"ledger.json",generated.getLedger());write(out,"field-impacts.json",generated.getAffectedFeatures());
        assertEquals("retained",DraftingPlanGenerationStateTest.action(p,ID).get("applicationState"));
        assertFalse(matchingBusinessOperation(bindings));guidanceReceipt(bindings);
        assertTrue(NativeDocxComparison.retainsParagraph(original,554,generated.getDocxBytes()),"The retained substantive paragraph remains byte-equivalent native XML apart from added bindings.");
    }
    @Test void realThreeGenerationAndImmediateLivePlanAgreeWithRetainedBody()throws Exception {
        Path c=capture(),out=out("generation");DraftingPlanGenerationStateTest.Fixture f=new DraftingPlanGenerationStateTest.Fixture();
        Map<String,Object> values=JsonUtils.readMap(Files.readString(c.resolve("confirmed-values.json")));values.forEach(f::value);
        for(String key:DraftBlueprint.draftFileKeys())f.source(key,Files.readAllBytes(c.resolve(key+"-source.docx")));
        f.service.listVariables(DraftingPlanGenerationStateTest.PROJECT);
        String frozenInputs=JsonUtils.write(f.variables.values());Map<String,Object> before=f.service.plan(DraftingPlanGenerationStateTest.PROJECT,null);
        assertEquals(3,f.service.generate(DraftingPlanGenerationStateTest.PROJECT,"en").size());
        Map<String,Object> saved=JsonUtils.readMap(f.documents.get("SCT").getInputSnapshotJson());Map<String,Object> live=f.service.plan(DraftingPlanGenerationStateTest.PROJECT,null);
        for(String key:DraftBlueprint.draftFileKeys()) {
            DraftArtifact artifact=f.artifacts.get(f.documents.get(key).getRevisionId());
            Files.write(out.resolve(key+"-result.docx"),artifact.getDocxBytes());
            Files.writeString(out.resolve(key+"-bindings.json"),artifact.getBindingsJson());
            Files.writeString(out.resolve(key+"-ledger.json"),artifact.getLedgerJson());
            Files.writeString(out.resolve(key+"-blocks.json"),artifact.getBlocksJson());
            Files.writeString(out.resolve(key+"-field-impacts.json"),artifact.getFieldImpactsJson());
            Files.writeString(out.resolve(key+"-content.txt"),f.documents.get(key).getContent());
        }
        write(out,"before-plan.json",before);write(out,"saved-plan.json",saved);write(out,"live-plan.json",live);
        write(out,"inputs-after.json",f.variables.values());
        assertEquals(frozenInputs,JsonUtils.write(f.variables.values()));verifyNoInteractions(f.model);
        assertEquals("saved_generation",DraftBusinessRules.asMap(live.get("applicationStateSource")).get("kind"));
        for(String key:DraftBlueprint.draftFileKeys())assertEquals(DraftingPlanGenerationStateTest.states(saved,key),DraftingPlanGenerationStateTest.states(live,key));
        assertEquals(saved.get("unresolved"),live.get("unresolved"));
        assertEquals("retained",DraftingPlanGenerationStateTest.action(saved,ID).get("applicationState"));
        assertEquals("retained",DraftingPlanGenerationStateTest.action(live,ID).get("applicationState"));
        DraftArtifact sct=f.artifacts.get(f.documents.get("SCT").getRevisionId());
        List<Map<String,Object>> bindings=DraftDocumentBindings.read(sct.getBindingsJson());
        assertFalse(matchingBusinessOperation(bindings));guidanceReceipt(bindings);
        assertEquals("not_used",DraftingPlanGenerationStateTest.action(live,"sct-site-formation-submissions").get("action"));
        assertEquals("applied",DraftingPlanGenerationStateTest.action(live,"sct-site-formation-submissions").get("applicationState"),"The adopted false prerequisite now executes the complete c negative scope.");
    }
}
