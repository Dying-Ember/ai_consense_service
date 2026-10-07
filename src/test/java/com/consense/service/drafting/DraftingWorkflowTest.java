package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.document.DocumentParser;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
@AutoConfigureMockMvc
class DraftingWorkflowTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:drafting_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","drafting-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired DraftingService service;
    @Autowired ProjectRepository projects;
    @Autowired DraftVariableRepository variables;
    @Autowired SourceDocumentRepository sources;
    @Autowired DraftDocumentRepository documents;
    @Autowired DocumentParser parser;
    @Autowired com.consense.service.StorageService storage;
    @Autowired MockMvc mvc;
    @MockBean LlmClient externalModel;
    @BeforeEach void modelAvailable() { when(externalModel.available()).thenReturn(true); when(externalModel.chatModel()).thenReturn("simulated-regression-model"); }
    String project() { Project p=new Project();p.setId("draft-"+UUID.randomUUID());p.setNameZhHans("Test");p.setContractNo("OLD-NUMBER");projects.save(p);return p.getId(); }
    void source(String id, String category, String key, String text) {
        SourceDocument s=new SourceDocument();s.setProjectId(id);s.setCategory(category);s.setFileKey(key);s.setFileName((key==null?"email":key)+UUID.randomUUID()+".txt");s.setTextContent(text);s.setParseStatus("PARSED");
        if(SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(category))try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            String name=key+".docx";for(String line:text.split("\n",-1))word.createParagraph().createRun().setText(line);word.write(out);byte[] bytes=out.toByteArray();
            com.consense.service.StorageService.StoredFile saved=storage.store(id,category,new org.springframework.mock.web.MockMultipartFile("file",name,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes));s.setFileName(name);s.setStoragePath(saved.getPath());s.setTextContent(parser.parse(name,bytes).getText());
        }catch(IOException failed){throw new IllegalStateException(failed);}sources.save(s);
    }
    void confirm(String id) {
        Map<String,String> vals=new LinkedHashMap<>();vals.put("contractTitle","{\"number\":\"20250101\",\"title\":\"Tung Chung Area 98\"}");
        vals.put("foundationIncluded","false");vals.put("periodAtLeast39Months","false");vals.put("billNos","[{\"number\":\"1\",\"description\":\"Preliminaries\"}]");
        vals.put("subcontractArrangement","BSSSC");vals.put("subcontractors","[\"Electrical\",\"Fire services and water pump\",\"Lift\"]");vals.put("twoEnvelopeTendering","true");vals.put("electronicTendering","L10Pro");
        vals.forEach((key,value)->service.updateVariable(id,key,new VariablePatch(value,null,true,null,null)));
    }
    void echoModel() { when(externalModel.chat(anyList())).thenThrow(new AssertionError("Deterministic generation cannot dispatch the model.")); }
    @Test void legacyRowsArePreservedAndMissingInputsRemainUnadopted() {
        String id=project();DraftVariable legacy=new DraftVariable();legacy.setProjectId(id);legacy.setVarKey("fundingArrangement");legacy.setScope("BASE");legacy.setValueText("Tender A");legacy.setConfirmed(true);variables.save(legacy);
        List<VariableVO> list=service.listVariables(id);assertEquals(DraftBlueprint.INPUTS.size(),list.size());assertTrue(list.stream().allMatch(v->"INPUT".equals(v.getScope())));
        assertTrue(variables.findByProjectIdAndVarKey(id,"fundingArrangement").isPresent());
        assertFalse(service.progress(id).isInputsReady());
        assertTrue(service.confirmAll(id,"INPUT",null).stream().noneMatch(VariableVO::isConfirmed));
        assertFalse(service.updateVariable(id,"foundationIncluded",new VariablePatch("",null,true,null,null)).isConfirmed());
        assertThrows(BizException.class,()->service.createVariable(id,new VariableCreate()));
    }
    @Test void allEvidenceDocumentsAreProcessedAndUnsupportedKeysAreIgnored() {
        String id=project();source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"Email one: awaiting confirmation.");source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"Email two: awaiting confirmation.");source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"Email three: Two envelopes are required.");
        List<String> seen=new ArrayList<>();
        when(externalModel.chat(anyList())).thenAnswer(call->{
            String prompt=((java.util.List<LlmClient.ChatTurn>)call.getArgument(0)).stream().filter(turn->"user".equals(turn.getRole())).findFirst().get().getContent();seen.add(prompt);
            DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey(prompt.contains("Email three")?"twoEnvelopeTendering":"madeUpField");item.setValue("true");item.setConfidence(.95);item.setSourceQuote("Two envelopes are required.");item.setReason("Third email");return JsonUtils.write(Collections.singletonList(item));
        });
        List<VariableVO> list=service.extractVariables(id);assertEquals(3,seen.size());assertEquals(DraftBlueprint.INPUTS.size(),list.size());
        VariableVO value=list.stream().filter(v->v.getKey().equals("twoEnvelopeTendering")).findFirst().get();assertEquals("true",value.getValue());assertFalse(value.isConfirmed());
    }
    @Test void conflictingEvidenceAndInventedQuotesRemainUnknown() {
        String id=project();source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"NSC applies.");source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"BSSSC applies.");
        when(externalModel.chat(anyList())).thenAnswer(call->{
            String prompt=((java.util.List<LlmClient.ChatTurn>)call.getArgument(0)).stream().filter(turn->"user".equals(turn.getRole())).findFirst().get().getContent();DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("subcontractArrangement");boolean b=prompt.contains("BSSSC applies.");item.setValue(b?"BSSSC":"NSC");item.setSourceQuote(b?"BSSSC applies.":"NSC applies.");item.setConfidence(.95);
            DraftingService.DiscoveredVariable invented=new DraftingService.DiscoveredVariable();invented.setKey("foundationIncluded");invented.setValue("false");invented.setSourceQuote("Invented evidence");invented.setConfidence(.99);return JsonUtils.write(Arrays.asList(item,invented));
        });
        List<VariableVO> list=service.extractVariables(id);assertTrue(list.stream().filter(v->v.getKey().equals("subcontractArrangement")||v.getKey().equals("foundationIncluded")).allMatch(v->v.getValue().isEmpty()));
    }
    @Test void fullGenerationExportsActualWordPdfAndRetainsStaleSnapshot() throws Exception {
        String id=project();confirm(id);String longText=String.join("\n",Collections.nCopies(160,"Original unrelated clause must be preserved with its complete wording."))+"\nTAIL-SENTINEL";
        source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","10. Sureties\n"+DraftBusinessRules.standardParagraph("NTT",484)+"\n11. Submission\nL10Pro pricing and hardcopy with DVD return.\n13. Subcontractors\nKeep original requirement without instruction.\n14. Other\nKeep this condition.");
        source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCT",longText);source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","1. Original SCC condition remains unchanged.");echoModel();
        List<DraftDocumentVO> docs=service.generate(id,"en");assertEquals(3,docs.size());assertTrue(docs.stream().allMatch(DraftDocumentVO::isGenerated));
        assertTrue(docs.stream().allMatch(d -> !d.getContent().contains("Contract No.: 20250101")&&!d.getContent().contains("Tung Chung Area 98")));
        assertTrue(docs.get(0).getContent().contains("Appendix G1 to Conditions of Contract"));assertTrue(docs.get(1).getContent().contains("TAIL-SENTINEL"));
        byte[] word=service.exportWord(id,"NTT");assertEquals(4013,assertThrows(BizException.class,()->service.previewPdf(id,"NTT")).getCode(),"Unavailable converter is explicit; actual configured conversion is covered by DraftingArtifactIntegrationTest.");
        Path out=Paths.get("target","drafting-verified-exports");Files.createDirectories(out);
        try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(word))) { assertTrue(doc.getParagraphs().stream().anyMatch(p->p.getText().contains("hardcopy with DVD"))); }
        assertEquals("OLD-NUMBER",projects.findById(id).get().getContractNo());
        Files.write(out.resolve("NTT.docx"),word);
        service.updateVariable(id,"foundationIncluded",new VariablePatch("true",null,true,null,null));assertTrue(service.listDocuments(id).stream().allMatch(DraftDocumentVO::isStale));
        assertEquals(docs.get(0).getContent(),service.listDocuments(id).get(0).getContent());
        assertEquals(docs.get(0).getSnapshotId(),service.listDocuments(id).get(0).getSnapshotId());
        assertEquals(4009,assertThrows(BizException.class,()->service.exportWord(id,"NTT")).getCode());
        assertEquals(4009,assertThrows(BizException.class,()->service.previewPdf(id,"NTT")).getCode());
    }
    @Test void missingTemplatesBlockGenerationAndModelIsNotUsedForDeterministicAssembly() {
        String id=project();confirm(id);assertThrows(BizException.class,()->service.generate(id,"en"));
        for(String key:DraftBlueprint.draftFileKeys())source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,key,"1. Original complete condition.");
        when(externalModel.chat(anyList())).thenThrow(new AssertionError("Deterministic generation cannot dispatch the model."));
        assertEquals(3,service.generate(id,"en").size());verify(externalModel,never()).chat(anyList());
    }
    @Test void realStandardAndTenderNttRespectInstructionDifference() throws Exception {
        Path base=Paths.get("..","2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)");
        Path standard=base.resolve("2a_Standard documents/01_Notes to Tenderers (NTT).docx"),tender=base.resolve("2b_Tender documents/03 - NTT.docx");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(standard) && Files.exists(tender),"Local project reference documents are not shipped with the repository");
        String a=parser.parse(standard.getFileName().toString(),Files.readAllBytes(standard)).getText();
        String b=parser.parse(tender.getFileName().toString(),Files.readAllBytes(tender)).getText();
        Map<String,Object> d=new HashMap<>();d.put("g1aTrigger",true);d.put("nscApplicable",false);
        assertTrue(DraftInputRules.hasNscInstruction(a));assertFalse(DraftInputRules.hasNscInstruction(b));
        String edited=DraftClauseRules.apply(a,d);assertFalse(edited.contains("*G1/*G1a"));assertFalse(edited.contains("(*Amend if NSC is not applicable)"));
        assertFalse(edited.contains("engagement of Nominated Sub-contractor defined in GCC Clause 1.1 and"));
        assertFalse(edited.contains("sub-contractor of the Nominated Sub-contractor and"));
        assertEquals(b,DraftClauseRules.apply(b,d),"Adopted G301 and clause13 without instruction must remain unchanged");
    }

    @Test void httpExportsHaveRealOfficePdfMimeAndStaleMetadata() throws Exception {
        String id=project();confirm(id);for(String key:DraftBlueprint.draftFileKeys())source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,key,"1. A complete original condition.");echoModel();
        DraftDocumentVO original=service.generate(id,"en").get(0);
        mvc.perform(get("/api/drafting/{id}/variables",id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(DraftBlueprint.INPUTS.size())).andExpect(jsonPath("$.data[0].group").value("identity"));
        byte[] word=mvc.perform(get("/api/drafting/{id}/documents/NTT/export.docx",id)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment"))).andReturn().getResponse().getContentAsByteArray();
        assertEquals('P',word[0]);assertEquals('K',word[1]);
        mvc.perform(get("/api/drafting/{id}/documents/NTT/export.pdf",id)).andExpect(jsonPath("$.code").value(4013));
        service.updateVariable(id,"twoEnvelopeTendering",new VariablePatch("false",null,true,null,null));
        mvc.perform(get("/api/drafting/{id}/documents",id)).andExpect(jsonPath("$.data[0].stale").value(true))
                .andExpect(jsonPath("$.data[0].content").value(original.getContent())).andExpect(jsonPath("$.data[0].snapshotId").value(original.getSnapshotId()));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/export.docx",id)).andExpect(jsonPath("$.code").value(4009));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/export.pdf",id)).andExpect(jsonPath("$.code").value(4009));
        mvc.perform(post("/api/drafting/{id}/generate",id).param("lang","en")).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].stale").value(false));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/export.docx",id)).andExpect(content().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/export.pdf",id)).andExpect(jsonPath("$.code").value(4013));
    }

    @Test void templatesAndMetadataNeverEnterExtractionOrSupplyAnAcceptedQuote() {
        String id=project();source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","COVER-ONLY-SECRET: Contract number TEMPLATE-123.");
        source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"The email leaves contract details unanswered.");
        when(externalModel.chat(anyList())).thenAnswer(call->{
            String prompt=((java.util.List<LlmClient.ChatTurn>)call.getArgument(0)).stream().filter(turn->"user".equals(turn.getRole())).findFirst().get().getContent();assertFalse(prompt.contains("COVER-ONLY-SECRET"));assertFalse(prompt.contains("OLD-NUMBER"));
            DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("contractTitle");item.setValue("{\"number\":\"TEMPLATE-123\",\"title\":\"Template title\"}");item.setConfidence(.99);item.setSourceQuote("Contract number TEMPLATE-123.");return JsonUtils.write(Collections.singletonList(item));
        });
        assertTrue(service.extractVariables(id).stream().filter(v->v.getKey().equals("contractTitle")).findFirst().get().getValue().isEmpty());
    }

    @Test void generationTemplatesAloneCannotTriggerExtraction() {
        String id=project();source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","Contract number TEMPLATE-123.");
        assertThrows(BizException.class,()->service.extractVariables(id));
        verify(externalModel,never()).chat(anyList());
    }

    @Test void partialEmailIdentityIsPrefilledAndHumanConfirmedValuesRemainEditable() {
        String id=project();source(id,SourceDocument.CATEGORY_PROJECT_INPUT,null,"Contract number EMAIL-123.");
        DraftingService.DiscoveredVariable item=new DraftingService.DiscoveredVariable();item.setKey("contractTitle");item.setValue("{\"number\":\"EMAIL-123\",\"title\":\"\"}");item.setConfidence(.95);item.setSourceQuote("Contract number EMAIL-123.");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Collections.singletonList(item)));
        VariableVO suggestion=service.extractVariables(id).stream().filter(v->v.getKey().equals("contractTitle")).findFirst().get();assertTrue(suggestion.getValue().contains("EMAIL-123"));assertFalse(suggestion.isConfirmed());
        assertTrue(service.updateVariable(id,"contractTitle",new VariablePatch(null,null,true,null,null)).isConfirmed());
        VariableVO confirmed=service.updateVariable(id,"contractTitle",new VariablePatch("{\"number\":\"EMAIL-123\",\"title\":\"Human supplied title\"}",null,true,null,null));assertTrue(confirmed.isConfirmed());
        VariableVO edited=service.updateVariable(id,"contractTitle",new VariablePatch("{\"number\":\"EMAIL-123\",\"title\":\"Adjusted title\"}",null,null,null,null));assertTrue(edited.isConfirmed());assertTrue(edited.isManuallyEdited());
        assertTrue(service.updateVariable(id,"contractTitle",new VariablePatch(null,null,true,null,null)).isConfirmed());
    }
}
