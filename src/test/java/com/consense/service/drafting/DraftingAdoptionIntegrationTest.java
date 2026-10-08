package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.common.BizException;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.web.dto.DraftingDtos.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP/service/JPA/export seams; no external model or production database. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
@AutoConfigureMockMvc
class DraftingAdoptionIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:draft_adoption_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","drafting-adoption-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired DraftingService service;
    @Autowired ProjectRepository projects;
    @Autowired DraftVariableRepository variables;
    @Autowired SourceDocumentRepository sources;
    @Autowired DraftDocumentRepository documents;
    @Autowired com.consense.service.StorageService storage;
    @Autowired com.consense.document.DocumentParser parser;
    @Autowired MockMvc mvc;
    @MockBean LlmClient externalModel;
    @BeforeEach void modelAvailable() { when(externalModel.available()).thenReturn(true); when(externalModel.chatModel()).thenReturn("simulated-regression-model"); }

    String project() {
        Project p=new Project();p.setId("adoption-"+UUID.randomUUID());p.setNameZhHans("Test");p.setContractNo("PROJECT-METADATA-MUST-NOT-SUPPLY-INPUT");projects.save(p);return p.getId();
    }
    SourceDocument source(String id,String category,String key,String name,String text) {
        SourceDocument s=new SourceDocument();s.setProjectId(id);s.setCategory(category);s.setFileKey(key);s.setFileName(name);s.setTextContent(text);s.setParseStatus("PARSED");
        if(SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(category))try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for(String line:text.split("\n",-1))word.createParagraph().createRun().setText(line);word.write(out);byte[] bytes=out.toByteArray();
            com.consense.service.StorageService.StoredFile saved=storage.store(id,category,new MockMultipartFile("file",name,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes));s.setStoragePath(saved.getPath());s.setTextContent(parser.parse(name,bytes).getText());
        }catch(IOException failed){throw new IllegalStateException(failed);}
        return sources.save(s);
    }
    void putValue(String id,String key,String value) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>();body.put("value",value);
        mvc.perform(put("/api/drafting/{id}/variables/{key}",id,key).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }
    VariableVO variable(String id,String key) {return service.listVariables(id).stream().filter(v->key.equals(v.getKey())).findFirst().get();}
    @SuppressWarnings("unchecked") Map<String,Object> effective(String id) {return (Map<String,Object>)service.plan(id,null).get("effectiveValues");}

    void uploadActualStandards(String id) throws Exception {
        String configured=System.getProperty("consense.acceptance.sourceDir");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured!=null,"Actual standard DOCX directory is required.");
        String[] names={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
        String[] keys={"NTT","SCT","SCC"};
        for(int i=0;i<keys.length;i++)mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,keys[i])
                .file(new MockMultipartFile("file",names[i],"application/vnd.openxmlformats-officedocument.wordprocessingml.document",Files.readAllBytes(Paths.get(configured,names[i])))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
    }
    DraftDocumentVO generated(String id,String key) throws Exception {
        mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0));
        return service.listDocuments(id).stream().filter(doc->key.equals(doc.getFileKey())).findFirst().get();
    }
    @Test void wholeManualOverridesReplaceAllActualClauseObligationsAndKeepAdjacentClauses() throws Exception {
        String id=project();uploadActualStandards(id);
        putValue(id,"targetOverrides",JsonUtils.write(Arrays.asList(
                DraftBusinessRules.map("actionId","sct-tender-system","action","amend","value","SCT5 Adopted procedure\nOnly the exact adopted project procedure applies."),
                DraftBusinessRules.map("actionId","scc-weather-8303","action","not_used"))));
        DraftDocumentVO sct=generated(id,"SCT"),scc=service.listDocuments(id).stream().filter(doc->"SCC".equals(doc.getFileKey())).findFirst().get();
        assertTrue(sct.getContent().contains("Only the exact adopted project procedure applies."));
        assertFalse(sct.getContent().contains("may be treated as a tendering irregularity"),"Whole SCT5 adoption must remove the old (10) obligation, beyond the catalog's P592 anchor.");
        assertTrue(sct.getContent().contains("SCT6"),"The adjacent SCT6 must survive.");
        assertTrue(scc.getContent().replaceAll("(?U)\\s+"," ").contains("SCC8.303 Not used"));
        assertFalse(scc.getContent().contains(DraftBusinessRules.standardParagraph("SCC",874)),"The whole SCC8.303 (4) obligation must be removed.");
        assertFalse(scc.getContent().contains(DraftBusinessRules.standardParagraph("SCC",876)),"The whole SCC8.303 (iv) obligation must be removed.");
        assertTrue(scc.getContent().contains("SCC8.304"),"The adjacent SCC8.304 must survive.");
    }
    @Test void incompleteRequiredRowsAreStoredForCorrectionButExcludedFromActualGeneration() throws Exception {
        String id=project();uploadActualStandards(id);
        putValue(id,"siteInspectionStartDate","2026-10-10");putValue(id,"siteInspectionEndDate","2026-10-15");
        String incomplete="[{\"text\":null}]";putValue(id,"siteVisitRestrictions",incomplete);
        assertEquals(incomplete,variable(id,"siteVisitRestrictions").getValue(),"Manual incomplete rows remain available for correction.");
        DraftDocumentVO sct=generated(id,"SCT");
        assertFalse(effective(id).containsKey("siteVisitRestrictions"),"An incomplete required cell cannot enter the effective generation snapshot.");
        assertTrue(sct.getUnresolved().stream().anyMatch(item->"input-siteVisitRestrictions".equals(item.get("id"))&&"InputInvalid".equals(item.get("kind"))),"The correction path must identify the invalid input.");
        assertFalse(sct.getContent().matches("(?s).*\\bnull\\b.*"),"Unknown cells cannot become literal null in the formal SCT.");
        putValue(id,"siteVisitRestrictions","[{\"text\":\"Bring protective footwear.\"}]");
        assertTrue(generated(id,"SCT").getContent().contains("Bring protective footwear."),"Corrected complete rows are adopted normally.");
        putValue(id,"siteVisitRestrictions","[]");
        assertEquals(Collections.emptyList(),effective(id).get("siteVisitRestrictions"),"Explicit None remains valid.");
    }
    @Test void customWholeSct5KeepsTechnicalReferenceUnresolvedUntilExactTargetAdoption() throws Exception {
        String id=project();uploadActualStandards(id);putValue(id,"twoEnvelopeTendering","true");
        Map<String,Object> procedure=DraftBusinessRules.map("actionId","sct-tender-system","action","amend","value","SCT5 Adopted procedure\n(4) Technical proposals are submitted in Envelope 1 under this adopted procedure.");
        putValue(id,"targetOverrides",JsonUtils.write(Collections.singletonList(procedure)));
        DraftDocumentVO scc=generated(id,"SCC");
        assertTrue(scc.getUnresolved().stream().anyMatch(item->"scc-technical-proposal-reference".equals(item.get("id"))),"A custom whole SCT5 leaves its final submission structure unverified and needs the exact SCC target decision.");
        assertTrue(scc.getContent().contains("SCT_____________"),"The original SCC1.301 placeholder must survive while its final SCT reference is unknown.");
        assertFalse(scc.getContent().contains("SCT5(3)"),"Two-envelope alone cannot justify the standard SCT5(3) position after a custom procedure.");
        String technical="Technical Proposals means proposals submitted in Envelope 1 under SCT5(4) accepted in the Letter of Acceptance.";
        Map<String,Object> exact=DraftBusinessRules.map("actionId","scc-technical-proposal-reference","action","amend","value",technical);
        putValue(id,"targetOverrides",JsonUtils.write(Arrays.asList(procedure,exact)));
        String resolved=generated(id,"SCC").getContent();
        assertTrue(resolved.contains(technical),"The business user's exact technical-definition decision resolves this draft without guessing structure.");
        assertTrue(resolved.contains("means the Articles of Agreement, the Tender, the Letter of Acceptance (including the Technical Proposals)"),"The neighboring Contract definition survives the exact Technical Proposals target edit.");
        assertTrue(resolved.contains("means the letter issued by or on behalf of the Employer for the acceptance of the Tender"),"The neighboring Letter of Acceptance definition survives.");
    }

    @Test void httpCatalogueInitialisesUnknownsAndPreviewDoesNotPersistHiddenCaches() throws Exception {
        String id=project();
        mvc.perform(get("/api/drafting/{id}/catalog",id)).andExpect(jsonPath("$.data.groups[0].id").value("identity"));
        mvc.perform(get("/api/drafting/{id}/variables",id)).andExpect(jsonPath("$.data[0].adoptionState").value("missing"));
        putValue(id,"foundationIncluded","false");putValue(id,"subcontractArrangement","BSSSC");putValue(id,"subcontractors","[\"Electrical\"]");
        assertTrue(effective(id).containsKey("subcontractors"));
        putValue(id,"subcontractArrangement","NSC");assertFalse(effective(id).containsKey("subcontractors"));
        assertEquals("[\"Electrical\"]",variable(id,"subcontractors").getValue());
        mvc.perform(post("/api/drafting/{id}/plan",id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"foundationIncluded\":\"true\",\"subcontractArrangement\":\"BSSSC\"}}"))
                .andExpect(jsonPath("$.data.preview").value(true)).andExpect(jsonPath("$.data.inputValues.foundationIncluded").value(true));
        assertEquals("false",variables.findByProjectIdAndVarKey(id,"foundationIncluded").get().getValueText());
        assertEquals("NSC",variable(id,"subcontractArrangement").getValue());
        putValue(id,"subcontractArrangement","BSSSC");assertTrue(effective(id).containsKey("subcontractors"));
        putValue(id,"oldValuableTrees","[]");assertTrue(variable(id,"oldValuableTrees").isManuallyEdited());
        assertEquals("[]",variable(id,"oldValuableTrees").getValue());
    }

    @Test void candidatesPersistAndUnrelatedEditsCannotClearDateReview() throws Exception {
        String id=project();source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"initial.docx","Visit dates: 2026-10-10 to 2026-10-12.");
        when(externalModel.chat(anyList())).thenAnswer(call->{
            String prompt=((java.util.List<LlmClient.ChatTurn>)call.getArgument(0)).stream().filter(turn->"user".equals(turn.getRole())).findFirst().get().getContent();boolean revised=prompt.contains("2026-10-15");
            String first=revised?"2026-10-15":"2026-10-10",last=revised?"2026-10-17":"2026-10-12";
            List<DraftingService.DiscoveredVariable> out=new ArrayList<>();
            for(String key:Arrays.asList("siteInspectionStartDate","siteInspectionEndDate")) {
                DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey(key);item.setValue(key.endsWith("StartDate")?first:last);
                item.setSourceQuote("Visit dates: "+first+" to "+last+".");item.setConfidence(.95);out.add(item);
            }return JsonUtils.write(out);
        });
        service.extractVariables(id);assertFalse(variable(id,"siteInspectionStartDate").isConfirmed());
        assertEquals(1,variable(id,"siteInspectionStartDate").getCandidates().size());
        assertNotNull(variables.findByProjectIdAndVarKey(id,"siteInspectionStartDate").get().getCandidatesJson());
        putValue(id,"siteInspectionStartDate","2026-10-10");putValue(id,"siteInspectionEndDate","2026-10-12");
        source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"revised.docx","Visit dates: 2026-10-15 to 2026-10-17.");
        service.extractVariables(id);
        assertEquals("2026-10-10",variable(id,"siteInspectionStartDate").getValue());
        assertTrue(variable(id,"siteInspectionStartDate").isReviewRequired());assertTrue(variable(id,"siteInspectionEndDate").isReviewRequired());
        putValue(id,"siteVisitRestrictions","[{\"text\":\"Arrive before 10:00.\"}]");
        assertTrue(variable(id,"siteInspectionStartDate").isReviewRequired());assertTrue(variable(id,"siteInspectionEndDate").isReviewRequired());
        putValue(id,"siteInspectionStartDate","2026-10-10");assertFalse(variable(id,"siteInspectionStartDate").isReviewRequired());assertTrue(variable(id,"siteInspectionEndDate").isReviewRequired());
        mvc.perform(put("/api/drafting/{id}/variables/siteInspectionEndDate",id).contentType(MediaType.APPLICATION_JSON).content("{\"candidateIndex\":1}"))
                .andExpect(jsonPath("$.data.value").value("2026-10-17")).andExpect(jsonPath("$.data.reviewRequired").value(false));
        service.extractVariables(id);assertFalse(variable(id,"siteInspectionEndDate").isReviewRequired(),"Unchanged sources must not re-open a completed review.");
        assertEquals("2026-10-17",variable(id,"siteInspectionEndDate").getValue());
    }

    @Test void unadoptedIdentityCannotLeakAndThreeEnglishDraftsFreezeOneSnapshot() throws Exception {
        String id=project();source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"email.docx","Contract number SUGGESTED-ONLY, title Suggested title.");
        DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("contractTitle");item.setValue("{\"number\":\"SUGGESTED-ONLY\",\"title\":\"Suggested title\"}");item.setSourceQuote("Contract number SUGGESTED-ONLY, title Suggested title.");item.setConfidence(.95);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Collections.singletonList(item)));
        service.extractVariables(id);assertFalse(effective(id).containsKey("contractTitle"));
        assertEquals(1,variable(id,"contractTitle").getCandidates().size(),"A source-supported identity suggestion exists but remains unadopted.");
        clearInvocations(externalModel); // The existing assertion concerns generation, after the prior extraction.
        for(String key:DraftBlueprint.draftFileKeys())source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,key,key+".docx","1. Original English source condition.\n"+String.join("\n",Collections.nCopies(180,"Unrelated original wording must be preserved completely."))+"\nTAIL-"+key);
        mvc.perform(post("/api/drafting/{id}/generate",id).param("lang","zh-Hans")).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.length()").value(3));
        List<DraftDocumentVO> drafts=service.listDocuments(id);assertEquals(1,drafts.stream().map(DraftDocumentVO::getSnapshotId).distinct().count());
        for(DraftDocumentVO draft:drafts) {
            assertFalse(draft.isStale());assertFalse(draft.getUnresolved().isEmpty());assertTrue(draft.getContent().contains("TAIL-"+draft.getFileKey()));
            assertTrue(draft.getContent().contains("Original English source condition."));assertFalse(draft.getContent().contains("SUGGESTED-ONLY"));
            byte[] bytes=mvc.perform(get("/api/drafting/{id}/documents/{key}/export.docx",id,draft.getFileKey()))
                    .andExpect(content().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).andReturn().getResponse().getContentAsByteArray();
            try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(bytes))) {assertTrue(doc.getParagraphs().stream().anyMatch(p->p.getText().contains("TAIL-"+draft.getFileKey())));}
        }
        verify(externalModel,never()).chat(anyList());
        String frozen=documents.findByProjectIdAndFileKey(id,"NTT").get().getInputSnapshotJson();
        putValue(id,"contractTitle","{\"number\":\"HUMAN-NEW\",\"title\":\"Adopted project title\"}");
        assertTrue(service.listDocuments(id).stream().allMatch(DraftDocumentVO::isStale));
        assertEquals(frozen,documents.findByProjectIdAndFileKey(id,"NTT").get().getInputSnapshotJson());
        String readBack=mvc.perform(get("/api/drafting/{id}/documents",id)).andExpect(jsonPath("$.data[0].stale").value(true))
                .andReturn().getResponse().getContentAsString();
        assertEquals(drafts.get(0).getContent(),JsonUtils.parse(readBack).get("data").get(0).get("content").asText());
        assertFalse(JsonUtils.parse(readBack).get("data").get(0).get("content").asText().contains("HUMAN-NEW"));
        assertEquals("PROJECT-METADATA-MUST-NOT-SUPPLY-INPUT",projects.findById(id).get().getContractNo());
    }

    @Test void staleDraftIsReadableButCannotBeEditedPreviewedOrExportedUntilRegenerated() throws Exception {
        String id=project();putValue(id,"contractTitle","{\"number\":\"INITIAL-123\",\"title\":\"Original adopted identity\"}");
        for(String key:DraftBlueprint.draftFileKeys())source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,key,key+".docx","1. Original English source provision.");
        mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0));
        String originalContent=service.listDocuments(id).get(0).getContent();
        String originalSnapshot=service.listDocuments(id).get(0).getSnapshotId();
        putValue(id,"contractTitle","{\"number\":\"REVISED-456\",\"title\":\"Revised adopted identity\"}");
        assertStaleDraftApiIsReadOnly(id,originalContent,originalSnapshot);

        mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].stale").value(false));
        DraftDocumentVO current=service.listDocuments(id).get(0);String edited="Business user's current English draft.";
        mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(bodyPatch(current,edited))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.contentEdited").value(true)).andExpect(jsonPath("$.data.stale").value(false));
        byte[] word=mvc.perform(get("/api/drafting/{id}/documents/NTT/export.docx",id))
                .andExpect(content().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).andReturn().getResponse().getContentAsByteArray();
        try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(word))) {assertTrue(doc.getParagraphs().stream().anyMatch(p->p.getText().contains("Business user's current English draft.")));}
        mvc.perform(get("/api/drafting/{id}/documents/NTT/preview.pdf",id)).andExpect(jsonPath("$.code").value(4013));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/download",id)).andExpect(content().contentTypeCompatibleWith("text/markdown"));

        // A replacement source also invalidates the current snapshot without erasing the user's saved body.
        String editedSnapshot=service.listDocuments(id).get(0).getSnapshotId();
        replaceWordTemplate(id,"NTT","1. Revised English source provision.");
        assertStaleDraftApiIsReadOnly(id,service.listDocuments(id).get(0).getContent(),editedSnapshot);
        mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].stale").value(false));
        assertTrue(service.listDocuments(id).get(0).getContent().contains("Revised English source provision."));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/export.docx",id))
                .andExpect(content().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    void assertStaleDraftApiIsReadOnly(String id,String frozenContent,String frozenSnapshot) throws Exception {
        mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"This must not replace a stale body.\"}"))
                .andExpect(jsonPath("$.code").value(4009));
        for(String path:Arrays.asList("preview.pdf","export.pdf","export.docx","download"))
            mvc.perform(get("/api/drafting/{id}/documents/NTT/"+path,id)).andExpect(jsonPath("$.code").value(4009));
        String readBack=mvc.perform(get("/api/drafting/{id}/documents",id)).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].stale").value(true)).andReturn().getResponse().getContentAsString();
        assertEquals(frozenContent,JsonUtils.parse(readBack).get("data").get(0).get("content").asText());
        assertEquals(frozenSnapshot,JsonUtils.parse(readBack).get("data").get(0).get("snapshotId").asText());
    }

    @Test void multipartSourceChangesReviewOnlyLinkedValuesAndRejectDeletedCandidates() throws Exception {
        String id=project();SourceDocument initial=source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"rates.docx","The photocopy rate is 1.50.");
        DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("photocopyRateUpToA3");item.setValue("1.50");item.setSourceQuote("The photocopy rate is 1.50.");item.setConfidence(.95);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Collections.singletonList(item)));
        service.extractVariables(id);putValue(id,"photocopyRateUpToA3","1.50");putValue(id,"siteInspectionStartDate","2026-10-10");
        byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {word.createParagraph().createRun().setText("The photocopy rate is 2.00.");word.write(out);bytes=out.toByteArray();}
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",id).file(new MockMultipartFile("files","rates.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
        assertEquals("1.5",variable(id,"photocopyRateUpToA3").getValue());assertTrue(variable(id,"photocopyRateUpToA3").isReviewRequired());
        assertFalse(variable(id,"siteInspectionStartDate").isReviewRequired());
        mvc.perform(delete("/api/drafting/{id}/inputs/{source}",id,initial.getId())).andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/drafting/{id}/variables/photocopyRateUpToA3",id).contentType(MediaType.APPLICATION_JSON).content("{\"candidateIndex\":0}"))
                .andExpect(jsonPath("$.code").value(4007));
        assertFalse(variable(id,"siteInspectionStartDate").isReviewRequired());
    }

    @Test void badTargetDecisionIsNotSilentlySavedAndGoodTextActuallyChangesTheSource() throws Exception {
        String id=project();service.listVariables(id);
        mvc.perform(put("/api/drafting/{id}/variables/targetOverrides",id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"[{\\\"actionId\\\":\\\"unknown-target\\\",\\\"action\\\":\\\"amend\\\",\\\"value\\\":\\\"Exact text\\\"}]\"}"))
                .andExpect(jsonPath("$.code").value(4007));
        Map<String,Object> edit=new LinkedHashMap<>();edit.put("actionId","NTT-13-SUBCONTRACT");edit.put("action","amend");edit.put("adoptedText","Exact project-specific requirement adopted by the business user.");
        putValue(id,"targetOverrides",JsonUtils.write(Collections.singletonList(edit)));
        String original=DraftBusinessRules.standardParagraph("NTT",546);
        source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","NTT.docx","13. Sub-contracting\n"+original+"\n"+DraftBusinessRules.standardParagraph("NTT",547)+"\n14. Other\nUnrelated provision must remain.\n");
        source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCT","SCT.docx","1. Original SCT provision.");
        source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","SCC.docx","1. Original SCC provision.");
        String content=service.generate(id,"en").get(0).getContent();
        assertTrue(content.contains("Exact project-specific requirement adopted by the business user."));assertFalse(content.contains(original));
        assertTrue(content.contains("Unrelated provision must remain."));
    }

    @Test void repeatedBillExtractionKeepsStableCandidatesAndDoesNotReopenReview() throws Exception {
        String id=project();source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"bills.docx","The Bills are listed below: 1 Preliminaries; 2 Preambles & Rates.");
        DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("billNos");
        item.setValue("[{\"number\":\"1\",\"description\":\"Preliminaries\"},{\"number\":\"2\",\"description\":\"Preambles & Rates\"}]");
        item.setSourceQuote("The Bills are listed below: 1 Preliminaries; 2 Preambles & Rates.");item.setConfidence(.95);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Collections.singletonList(item)));
        service.extractVariables(id);String suggested=variable(id,"billNos").getCandidates().get(0).getValue();
        putValue(id,"billNos",suggested);String candidates=variables.findByProjectIdAndVarKey(id,"billNos").get().getCandidatesJson();
        service.extractVariables(id);assertEquals(candidates,variables.findByProjectIdAndVarKey(id,"billNos").get().getCandidatesJson());
        assertFalse(variable(id,"billNos").isReviewRequired());assertEquals("Preambles & Rates",JsonUtils.parse(variable(id,"billNos").getValue()).get(1).get("description").asText());
    }

    @Test void aHumanEditCommittedWhileTheModelIsRunningWinsOverItsCandidate() throws Exception {
        String id=project();source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"fees.docx","The photocopy rate is 1.50.");
        DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("photocopyRateUpToA3");item.setValue("1.50");item.setSourceQuote("The photocopy rate is 1.50.");item.setConfidence(.95);
        ExecutorService human=Executors.newSingleThreadExecutor();
        try {
            when(externalModel.chat(anyList())).thenAnswer(call->{
                human.submit(()->service.updateVariable(id,"photocopyRateUpToA3",new VariablePatch("2.75",null,null,null,null))).get(15,TimeUnit.SECONDS);
                assertEquals("2.75",variables.findByProjectIdAndVarKey(id,"photocopyRateUpToA3").get().getValueText(),"Human transaction must have committed before model persistence.");
                return JsonUtils.write(Collections.singletonList(item));
            });
            service.extractVariables(id);
            VariableVO result=variable(id,"photocopyRateUpToA3");
            assertEquals("2.75",result.getValue());assertTrue(result.isManuallyEdited());assertTrue(result.isConfirmed());
            assertEquals("1.5",result.getCandidates().get(0).getValue());
            assertTrue(result.isReviewRequired(),"The new conflicting candidate is marked for review, without overriding the adopted human value.");
        } finally {human.shutdownNow();}
    }

    @Test void explicitAdoptionAndReviewOfTheCurrentSuggestionPreservesModelProvenance() throws Exception {
        String id=project();source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"fees.docx","The photocopy rate is 1.50.");
        DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("photocopyRateUpToA3");item.setValue("1.50");item.setSourceQuote("The photocopy rate is 1.50.");item.setConfidence(.95);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Collections.singletonList(item)));
        service.extractVariables(id);assertFalse(effective(id).containsKey("photocopyRateUpToA3"));
        mvc.perform(put("/api/drafting/{id}/variables/photocopyRateUpToA3",id).contentType(MediaType.APPLICATION_JSON).content("{\"reviewed\":true,\"confirmed\":true}"))
                .andExpect(jsonPath("$.data.confirmed").value(true)).andExpect(jsonPath("$.data.manuallyEdited").value(false));
        assertEquals(1.5,((Number)effective(id).get("photocopyRateUpToA3")).doubleValue());
        assertFalse(DraftAdoption.adoptedSources(variables.findByProjectIdAndVarKey(id,"photocopyRateUpToA3").get()).isEmpty());
    }

    @Test void aPostGenerationContentEditRecordsItsFactAndRetainsTheFrozenSourceSnapshot() throws Exception {
        String id=project();putValue(id,"foundationIncluded","false");
        for(String key:DraftBlueprint.draftFileKeys())source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,key,key+".docx","1. Original English provision.");
        service.generate(id,"en");DraftDocument before=documents.findByProjectIdAndFileKey(id,"NTT").get();
        String frozen=before.getInputSnapshotJson(),snapshot=before.getSnapshotId();
        mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(bodyPatch(service.listDocuments(id).get(0),"Business user's edited English draft."))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.contentEdited").value(true));
        DraftDocument after=documents.findByProjectIdAndFileKey(id,"NTT").get();
        assertEquals(snapshot,after.getSnapshotId());assertEquals(frozen,after.getInputSnapshotJson());
        assertEquals("false",variable(id,"foundationIncluded").getValue());assertFalse(service.listDocuments(id).get(0).isStale());
    }

    @Test void freeEnglishTextIsPreservedIncludingLiteralQuotesAndNumericLookingStrings() throws Exception {
        String id=project();String value="  \"Other title\"  ";
        putValue(id,"projectArchitectOtherTitle",value);
        assertEquals(value,variable(id,"projectArchitectOtherTitle").getValue());
        putValue(id,"projectArchitectPhone","12.50");
        assertEquals("12.50",effective(id).get("projectArchitectPhone"));
    }

    @Test void changedEvidenceDuringTheModelCallRejectsItsStaleWriteBackWithoutOverridingHumanInput() throws Exception {
        String id=project();SourceDocument original=source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"fees.docx","The photocopy rate is 1.50.");
        putValue(id,"photocopyRateUpToA3","2.75");
        ExecutorService editor=Executors.newSingleThreadExecutor();
        try {
            when(externalModel.chat(anyList())).thenAnswer(call->{
                editor.submit(()->{SourceDocument latest=sources.findById(original.getId()).get();latest.setTextContent("The photocopy rate is 2.00.");return sources.save(latest);}).get(15,TimeUnit.SECONDS);
                DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("photocopyRateUpToA3");item.setValue("1.50");item.setSourceQuote("The photocopy rate is 1.50.");item.setConfidence(.95);return JsonUtils.write(Collections.singletonList(item));
            });
            assertEquals(4007,assertThrows(BizException.class,()->service.extractVariables(id)).getCode());
            assertEquals("2.75",variable(id,"photocopyRateUpToA3").getValue());
            assertTrue(variable(id,"photocopyRateUpToA3").getCandidates().isEmpty());
            assertEquals(true,service.plan(id,null).get("evidenceChangedSinceExtraction"));
        } finally {editor.shutdownNow();}
    }

    @Test void aPartialAdoptedIdentityKeepsItsKnownSiblingAndMarksTheMissingOne() throws Exception {
        String id=project();putValue(id,"contractTitle","{\"number\":\"KNOWN-123\",\"title\":null}");
        for(String key:DraftBlueprint.draftFileKeys())source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,key,key+".docx","1. Complete original source condition.");
        List<DraftDocumentVO> drafts=service.generate(id,"en");
        assertTrue(drafts.stream().allMatch(d->d.getContent().contains("Complete original source condition.")&&!d.getContent().contains("Contract No.: KNOWN-123")));
        assertTrue(drafts.get(0).getUnresolved().stream().anyMatch(item->"InputMissing".equals(item.get("kind"))&&String.valueOf(item.get("inputKeys")).contains("contractTitle")));
    }

    @SuppressWarnings("unchecked")
    @Test void replacingARealUploadReviewsEachTargetAndReadoptingOneCannotCertifyTheOther() throws Exception {
        String id=project();String original="10. Sureties\n"+DraftBusinessRules.standardParagraph("NTT",484)+"\n13. Sub-contracting\n"+DraftBusinessRules.standardParagraph("NTT",546)+"\n14. Other\nInitial unrelated provision.";
        replaceWordTemplate(id,"NTT",original);
        Map<String,Object> bond=new LinkedHashMap<>();bond.put("actionId","NTT-10-BOND-REFERENCE");bond.put("action","amend");bond.put("adoptedText","The business user adopts the project-specific bond wording.");
        Map<String,Object> trades=new LinkedHashMap<>();trades.put("actionId","NTT-13-SUBCONTRACT");trades.put("action","amend");trades.put("adoptedText","The business user adopts the project-specific subcontract requirement.");
        putValue(id,"targetOverrides",JsonUtils.write(Arrays.asList(bond,trades)));
        List<Map<String,Object>> saved=(List)JsonUtils.readList(variable(id,"targetOverrides").getValue(),Map.class);
        assertNotNull(saved.get(0).get("sourceRevision"));assertNotNull(saved.get(1).get("sourceRevision"));
        assertEquals(0,targetReviews(id).size());
        replaceWordTemplate(id,"NTT",original+"\nChanged unrelated provision changes the adopted source identity.");
        assertEquals(2,targetReviews(id).size());
        // The unchanged record keeps its frozen revision. The explicitly re-adopted record is newly built.
        putValue(id,"targetOverrides",JsonUtils.write(Arrays.asList(saved.get(1),bond)));
        List<Map<String,Object>> reviews=targetReviews(id);assertEquals(1,reviews.size());
        assertEquals("NTT-13-SUBCONTRACT",reviews.get(0).get("actionId"));
        List<Map<String,Object>> current=(List)JsonUtils.readList(variable(id,"targetOverrides").getValue(),Map.class);
        assertEquals(saved.get(1).get("sourceRevision"),current.get(0).get("sourceRevision"));
        assertNotEquals(saved.get(0).get("sourceRevision"),current.get(1).get("sourceRevision"));
        source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCT","SCT.docx","1. Original English SCT provision.");
        source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","SCC.docx","1. Original English SCC provision.");
        assertEquals(3,service.generate(id,"en").size(),"A stale adopted target is a review notice, not a generation gate.");
        assertTrue(service.listDocuments(id).get(0).getUnresolved().stream().anyMatch(item->"target_review".equals(item.get("kind"))&&"NTT-13-SUBCONTRACT".equals(item.get("actionId"))));
    }
    void replaceWordTemplate(String id,String key,String text) throws Exception {
        byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {for(String line:text.split("\n"))word.createParagraph().createRun().setText(line);word.write(out);bytes=out.toByteArray();}
        mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,key).file(new MockMultipartFile("file",key+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
    }
    Map<String,Object> bodyPatch(DraftDocumentVO doc,String text) {
        Map<String,Object> block=doc.getBlocks().stream().filter(p->Boolean.TRUE.equals(p.get("editable"))).findFirst().get();
        return DraftBusinessRules.map("revisionId",doc.getRevisionId(),"docxSha256",doc.getDocxSha256(),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"expectedTextHash",block.get("textHash"),"text",text)));
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> targetReviews(String id) {
        return ((List<Map<String,Object>>)service.plan(id,null).get("unresolved")).stream().filter(item->"target_review".equals(item.get("kind"))).collect(java.util.stream.Collectors.toList());
    }

    @Test void complementaryBillCandidatesMergeByPricingTypeAndNumberWhileConflictingFormalNamesRemainSelectable() throws Exception {
        String id=project();source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"first.docx","Bill 1 - Preliminaries | Bill of Quantities (BQ).");
        SourceDocument second=source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"second.docx","Bill 1 - Safety & Environmental Payments | Schedule of Rates (SOR); Bill 2 - Building Works – Domestic Blocks | Bill of Quantities (BQ).");
        when(externalModel.chat(anyList())).thenAnswer(call->{
            String prompt=((java.util.List<LlmClient.ChatTurn>)call.getArgument(0)).stream().filter(turn->"user".equals(turn.getRole())).findFirst().get().getContent();boolean first=prompt.contains("Source document: first.docx"),conflict=prompt.contains("Bill 1 - Revised formal description");
            DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("billNos");item.setConfidence(.95);
            if(first){item.setValue("[{\"type\":\"BQ\",\"number\":\"1\",\"description\":\"Preliminaries\"}]");item.setSourceQuote("Bill 1 - Preliminaries | Bill of Quantities (BQ).");}
            else {item.setValue("[{\"type\":\""+(conflict?"BQ":"SOR")+"\",\"number\":\"1\",\"description\":\""+(conflict?"Revised formal description":"Safety & Environmental Payments")+"\"},{\"type\":\"BQ\",\"number\":\"2\",\"description\":\"Building Works – Domestic Blocks\"}]");
                item.setSourceQuote(conflict?"Bill 1 - Revised formal description | Bill of Quantities (BQ); Bill 2 - Building Works – Domestic Blocks | Bill of Quantities (BQ).":"Bill 1 - Safety & Environmental Payments | Schedule of Rates (SOR); Bill 2 - Building Works – Domestic Blocks | Bill of Quantities (BQ).");}
            return JsonUtils.write(Collections.singletonList(item));
        });
        service.extractVariables(id);VariableVO suggested=variable(id,"billNos");
        assertEquals(3,JsonUtils.parse(suggested.getValue()).size());assertFalse(suggested.isConfirmed());assertFalse(effective(id).containsKey("billNos"));
        assertEquals("Safety & Environmental Payments",JsonUtils.parse(suggested.getValue()).get(1).get("description").asText());
        assertEquals("Building Works – Domestic Blocks",JsonUtils.parse(suggested.getValue()).get(2).get("description").asText());
        second.setTextContent("Bill 1 - Revised formal description | Bill of Quantities (BQ); Bill 2 - Building Works – Domestic Blocks | Bill of Quantities (BQ).");sources.save(second);
        service.extractVariables(id);VariableVO conflicting=variable(id,"billNos");
        assertEquals("",conflicting.getValue());assertEquals("conflict",conflicting.getAdoptionState());assertEquals(2,conflicting.getCandidates().size());
        assertTrue(conflicting.getCandidates().stream().anyMatch(candidate->candidate.getValue().contains("Preliminaries")));
        assertTrue(conflicting.getCandidates().stream().anyMatch(candidate->candidate.getValue().contains("Revised formal description")));
        assertFalse(effective(id).containsKey("billNos"));
    }
}
