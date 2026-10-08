package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public upload/extract/variables/history tests; only the external model is doubled. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingQuoteGroundingIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:draft_quote_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","drafting-quote-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @MockBean LlmClient externalModel;
    @BeforeEach void modelAvailable() {
        when(externalModel.available()).thenReturn(true);
        when(externalModel.chatModel()).thenReturn("test-only-external-model");
    }

    @Test void originalCon8ReplyRejectsTrueAndRetainsTheSourceSupportedFalseRepair() throws Exception {
        String project=project();uploadOriginal(project,"2024-12-22_from Structural Engineer to QS.docx");
        String quote="Volumetric Precast Concrete Components are not used";
        when(externalModel.chat(anyList())).thenReturn(answer("volumetricPrecastComponents",true,quote),answer("volumetricPrecastComponents",false,quote));
        extract(project);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertTrue(report.path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
        assertEquals("accepted",report.path("decisions").get(1).path("status").asText());
        assertEquals("repair",report.path("parts").get(0).path("attempts").get(1).path("kind").asText());
        assertEquals("false",variable(project,"volumetricPrecastComponents").path("value").asText());
        assertFalse(variable(project,"volumetricPrecastComponents").path("confirmed").asBoolean());
        assertEquals(report,response(get("/api/drafting/{id}/variables/extract-traces/{run}",project,report.path("runId").asText())));
    }

    @Test void inspectionDateRequestCannotSupplyAnInventedDateButTheOriginalReplySuppliesBothRangeEndpoints() throws Exception {
        String project=project();uploadOriginal(project,"2024-12-19_from QS to Architect and Structrual Engineer.docx");
        String request="Architect to provide time period (between which date and which date) during which tenderers may inspect the site";
        String wrong=answer("siteInspectionStartDate","2026-01-01",request);
        when(externalModel.chat(anyList())).thenReturn(wrong,wrong);
        extract(project);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertTrue(report.path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
        JsonNode attempts=report.path("parts").get(0).path("attempts");
        assertEquals("primary",attempts.get(0).path("kind").asText());assertEquals("repair",attempts.get(1).path("kind").asText());
        for(int i=2;i<attempts.size();i++)assertEquals("recall",attempts.get(i).path("kind").asText(),"A repeated bad repair cannot recursively repair itself; any additional attempt is separately bounded context recall.");
        assertEquals("",variable(project,"siteInspectionStartDate").path("value").asText());
        assertEquals(0,variable(project,"siteInspectionStartDate").path("candidates").size());

        project=project();uploadOriginal(project,"2024-12-23_from Architect to QS.docx");
        String quote="Between 17th and 21st February 2025";
        when(externalModel.chat(anyList())).thenReturn("["+item("siteInspectionStartDate","2025-02-17",quote)+","+item("siteInspectionEndDate","2025-02-21",quote)+"]");
        extract(project);
        assertEquals("2025-02-17",variable(project,"siteInspectionStartDate").path("value").asText());
        assertEquals("2025-02-21",variable(project,"siteInspectionEndDate").path("value").asText());
        assertFalse(variable(project,"siteInspectionStartDate").path("confirmed").asBoolean());
    }

    @Test void originalArchitectPhoneRejectsOtherDigitsAndAllowsPhonePunctuationNormalization() throws Exception {
        String project=project();uploadOriginal(project,"2024-12-23_from Architect to QS.docx");
        String quote="For SCT7(1), the officer is A/T1 (Ms Emily T.L. WONG), Tel. 2761 6161.";
        when(externalModel.chat(anyList())).thenReturn(answer("projectArchitectPhone","9999 9999",quote),answer("projectArchitectPhone","2761-6161",quote));
        extract(project);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertTrue(report.path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
        assertEquals("accepted",report.path("decisions").get(1).path("status").asText());
        assertEquals("2761-6161",variable(project,"projectArchitectPhone").path("value").asText());
        assertFalse(variable(project,"projectArchitectPhone").path("confirmed").asBoolean());
    }

    @Test void directLiteralsMustBeInTheirQuoteWhileTypographyNumericScaleAndPartialIdentityRemainSupported() throws Exception {
        String project=project();String name="The Project Architect is Ms Emily T.L. WONG.";
        String rate="The photocopy rate up to A3 is HK$1.50 per page.";
        String identity="The Contract title is Council’s Works – Phase A.";
        uploadText(project,name+"\n"+rate+"\n"+identity+"\nJane Doe works on another project; its rate is HK$1.99 and contract number OTHER-9.");
        String wrong="["+item("projectArchitectName","Jane Doe",name)+","+item("photocopyRateUpToA3",1.99,rate)+","+
                item("contractTitle",Collections.singletonMap("number","OTHER-9"),identity)+"]";
        String repaired="["+item("projectArchitectName","Emily T.L. WONG",name)+","+item("photocopyRateUpToA3",1.5,rate)+","+
                item("contractTitle",Collections.singletonMap("title","Council's Works - Phase A"),identity)+"]";
        when(externalModel.chat(anyList())).thenReturn(wrong,repaired);
        extract(project);JsonNode report=trace(project);
        for(int i=0;i<3;i++)assertEquals("rejected",report.path("decisions").get(i).path("status").asText());
        assertEquals("Emily T.L. WONG",variable(project,"projectArchitectName").path("value").asText());
        assertEquals("1.5",variable(project,"photocopyRateUpToA3").path("value").asText());
        JsonNode contract=JsonUtils.parse(variable(project,"contractTitle").path("value").asText());
        assertEquals("Council's Works - Phase A",contract.path("title").asText());
        assertEquals("",contract.path("number").asText(),"A sourced partial identity does not invent its missing sibling.");
        assertFalse(variable(project,"contractTitle").path("confirmed").asBoolean());
    }

    @Test void swappedBillIdentitiesAreRejectedWhileOriginalRowPairsAndBillMetadataRemainEditableSuggestions() throws Exception {
        String project=project();String quote="Bill No. 1 | Preliminaries\nBill No. 2 | General Builder Works\nBill20 Other Works is a measured Bill of Quantities (BQ).";
        uploadText(project,quote);
        List<Map<String,Object>> swapped=Arrays.asList(bill("1","General Builder Works"),bill("2","Preliminaries"));
        Map<String,Object> measured=bill("20","Other Works");measured.put("type","BQ");
        List<Map<String,Object>> supported=Arrays.asList(bill("1","Preliminaries"),bill("2","General Builder Works"),measured);
        when(externalModel.chat(anyList())).thenReturn(answer("billNos",swapped,quote),answer("billNos",supported,quote));
        extract(project);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertTrue(report.path("decisions").get(0).path("codes").toString().contains("lexical_support_missing"));
        JsonNode rows=JsonUtils.parse(variable(project,"billNos").path("value").asText());
        assertEquals(3,rows.size());assertEquals("Preliminaries",rows.get(0).path("description").asText());
        assertEquals("General Builder Works",rows.get(1).path("description").asText());
        assertEquals("20",rows.get(2).path("number").asText());assertEquals("BQ",rows.get(2).path("type").asText());
        assertFalse(variable(project,"billNos").path("confirmed").asBoolean());
    }

    @Test void sharedMonthInspectionRangeDoesNotSupportTheEndDateAsItsStart() throws Exception {
        String project=project();uploadOriginal(project,"2024-12-23_from Architect to QS.docx");
        String quote="Between 17th and 21st February 2025";
        when(externalModel.chat(anyList())).thenReturn(answer("siteInspectionStartDate","2025-02-21",quote),answer("siteInspectionStartDate","2025-02-17",quote));
        extract(project);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertEquals("2025-02-17",variable(project,"siteInspectionStartDate").path("value").asText());
    }

    @Test void partialBillIdentifierDoesNotRequireOrInventAMissingDescription() throws Exception {
        String project=project();String quote="Bill20 is identified; its formal description is not supplied.";
        uploadText(project,quote);
        when(externalModel.chat(anyList())).thenReturn(answer("billNos",Collections.singletonList(Collections.singletonMap("number","20")),quote),"[]");
        extract(project);
        assertEquals("accepted",trace(project).path("decisions").get(0).path("status").asText());
        JsonNode rows=JsonUtils.parse(variable(project,"billNos").path("value").asText());
        assertEquals("20",rows.get(0).path("number").asText());assertFalse(rows.get(0).hasNonNull("description"));
        assertFalse(variable(project,"billNos").path("confirmed").asBoolean());
    }

    @Test void positiveCon8EvidenceAndUnresolvedFoundationRemainSeparateFromAHumanAdoptedValue() throws Exception {
        String project=project();String con8="Volumetric Precast Concrete Components are used.";
        String pending="Combined-foundation classification remains pending.";
        uploadText(project,con8+"\n"+pending);
        mvc.perform(put("/api/drafting/{id}/variables/volumetricPrecastComponents",project).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"false\",\"confirmed\":true}")).andExpect(jsonPath("$.code").value(0));
        when(externalModel.chat(anyList())).thenReturn("["+item("volumetricPrecastComponents",true,con8)+","+item("foundationIncluded",null,pending)+"]");
        extract(project);JsonNode report=trace(project);
        assertEquals("accepted",report.path("decisions").get(0).path("status").asText());
        assertEquals("unanswered",report.path("decisions").get(1).path("status").asText());
        assertTrue(report.path("decisions").get(1).path("codes").toString().contains("source_unresolved"));
        assertEquals("",variable(project,"foundationIncluded").path("value").asText());
        assertEquals("false",variable(project,"volumetricPrecastComponents").path("value").asText());
        assertTrue(variable(project,"volumetricPrecastComponents").path("manuallyEdited").asBoolean());
        assertEquals("true",variable(project,"volumetricPrecastComponents").path("candidates").get(0).path("value").asText());
    }

    @Test void sentenceFullStopDoesNotInvalidateASourceSupportedDecimal() throws Exception {
        String project=project();String quote="The photocopy rate is 1.50.";uploadText(project,quote);
        when(externalModel.chat(anyList())).thenReturn(answer("photocopyRateUpToA3",1.5,quote));extract(project);
        assertEquals("accepted",trace(project).path("decisions").get(0).path("status").asText());
        assertEquals("1.5",variable(project,"photocopyRateUpToA3").path("value").asText());
    }

    @Test void compactBillListsBindEachSemicolonEntryToItsOwnDescription() throws Exception {
        String project=project();String quote="The Bills are listed below: 1 Preliminaries; 2 Preambles & Rates.";uploadText(project,quote);
        when(externalModel.chat(anyList())).thenReturn(answer("billNos",Arrays.asList(bill("1","Preambles & Rates"),bill("2","Preliminaries")),quote),
                answer("billNos",Arrays.asList(bill("1","Preliminaries"),bill("2","Preambles & Rates")),quote));
        extract(project);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertEquals("accepted",report.path("decisions").get(1).path("status").asText());
        JsonNode rows=JsonUtils.parse(variable(project,"billNos").path("value").asText());
        assertEquals("Preliminaries",rows.get(0).path("description").asText());assertEquals("Preambles & Rates",rows.get(1).path("description").asText());
    }

    @Test void originalCon8RequestDoesNotEstablishEitherBooleanAnswer() throws Exception {
        String quote="Structural Engineer to provide tender submission related to Volumetric Precast Concrete Components if any";
        for(boolean value:Arrays.asList(true,false)) {
            String project=project();uploadOriginal(project,"2024-12-19_from QS to Architect and Structrual Engineer.docx");
            when(externalModel.chat(anyList())).thenReturn(answer("volumetricPrecastComponents",value,quote),answer("volumetricPrecastComponents",null,quote));
            extract(project);JsonNode report=trace(project);
            assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
            assertTrue(report.path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
            assertEquals("unanswered",report.path("decisions").get(1).path("status").asText());
            assertEquals("",variable(project,"volumetricPrecastComponents").path("value").asText());
            assertEquals(0,variable(project,"volumetricPrecastComponents").path("candidates").size());
        }
    }

    @Test void fullyWrittenAndLabelledInspectionDatesBindStartAndEndRoles() throws Exception {
        for(String quote:Arrays.asList("Between 17 February 2025 and 21 February 2025",
                "Site inspection start date: 17 February 2025; site inspection end date: 21 February 2025.",
                "Site inspection from 2025-02-17 to 2025-02-21.")) {
            String project=project();uploadText(project,quote);
            String wrong="["+item("siteInspectionStartDate","2025-02-21",quote)+","+item("siteInspectionEndDate","2025-02-17",quote)+"]";
            String correct="["+item("siteInspectionStartDate","2025-02-17",quote)+","+item("siteInspectionEndDate","2025-02-21",quote)+"]";
            when(externalModel.chat(anyList())).thenReturn(wrong,correct);extract(project);JsonNode report=trace(project);
            assertEquals("rejected",report.path("decisions").get(0).path("status").asText(),quote);
            assertEquals("rejected",report.path("decisions").get(1).path("status").asText(),quote);
            assertEquals("2025-02-17",variable(project,"siteInspectionStartDate").path("value").asText());
            assertEquals("2025-02-21",variable(project,"siteInspectionEndDate").path("value").asText());
        }
    }

    @Test void aProposedInspectionDateInsideAQuestionNeedsAnActualReplyEvenWhenItsLiteralOccurs() throws Exception {
        String project=project();String question="Please confirm whether the site inspection starts on 17 February 2025.";
        String reply="SCT8 | Architect to provide time period for site inspection | Between 17th and 21st February 2025";
        uploadText(project,question+"\n"+reply);
        when(externalModel.chat(anyList())).thenReturn(answer("siteInspectionStartDate","2025-02-17",question),answer("siteInspectionStartDate","2025-02-17",reply));
        extract(project);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertEquals("accepted",report.path("decisions").get(1).path("status").asText());
        assertEquals("2025-02-17",variable(project,"siteInspectionStartDate").path("value").asText());
    }

    @Test void aPhoneProposedInsideAQuestionNeedsTheActualContactReply() throws Exception {
        String project=project();String question="Please provide the architect's telephone number; is it 2761 6161?";
        String reply="SCT7 | Architect to confirm name, post and tel. | For SCT7(1), the officer is A/T1 (Ms Emily T.L. WONG), Tel. 2761 6161.";
        uploadText(project,question+"\n"+reply);
        when(externalModel.chat(anyList())).thenReturn(answer("projectArchitectPhone","2761 6161",question),answer("projectArchitectPhone","2761-6161",reply));
        extract(project);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertEquals("accepted",report.path("decisions").get(1).path("status").asText());
        assertEquals("2761-6161",variable(project,"projectArchitectPhone").path("value").asText());
    }

    @Test void futureCon8NegationRejectsTrueAndFuturePositiveUsageStillSupportsTrue() throws Exception {
        String project=project();String negative="Volumetric Precast Concrete Components will not be used.";uploadText(project,negative);
        when(externalModel.chat(anyList())).thenReturn(answer("volumetricPrecastComponents",true,negative),answer("volumetricPrecastComponents",false,negative));
        extract(project);
        assertEquals("rejected",trace(project).path("decisions").get(0).path("status").asText());
        assertEquals("false",variable(project,"volumetricPrecastComponents").path("value").asText());
        project=project();String positive="CON8 components will be used.";uploadText(project,positive);
        when(externalModel.chat(anyList())).thenReturn(answer("volumetricPrecastComponents",true,positive));extract(project);
        assertEquals("accepted",trace(project).path("decisions").get(0).path("status").asText());
        assertEquals("true",variable(project,"volumetricPrecastComponents").path("value").asText());
    }

    @Test void theActualBill12FormalDescriptionKeepsItsInternalSemicolon() throws Exception {
        String project=project();String name="04_2024-12-12_email_bill_numbers_and_descriptions_for_drafting.docx";
        try(InputStream source=getClass().getResourceAsStream("/drafting/harness/a10-replay/sources/"+name)) {
            assertNotNull(source);upload(project,name,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",org.springframework.util.StreamUtils.copyToByteArray(source));
        }
        String description="Site Safety; Environmental Management and Other Sundry Requirements (All Provisional)";
        String quote="12 | "+description;
        when(externalModel.chat(anyList())).thenReturn(answer("billNos",Collections.singletonList(bill("12",description)),quote),"[]");
        extract(project);
        assertEquals("accepted",trace(project).path("decisions").get(0).path("status").asText());
        assertEquals(description,JsonUtils.parse(variable(project,"billNos").path("value").asText()).get(0).path("description").asText());
    }

    @Test void literalQuestionsDoNotSupplyTextOrNumbersAndAnUnrelatedQuestionDoesNotEraseADeclaredTitle() throws Exception {
        String project=project();String name="Please confirm whether the Project Architect is Jane Doe?";
        String rate="Please confirm the photocopy rate is 1.50?";
        String title="The Contract title is Works A. Please confirm whether CON8 components are used?";
        uploadText(project,name+"\n"+rate+"\n"+title);
        when(externalModel.chat(anyList())).thenReturn("["+item("projectArchitectName","Jane Doe",name)+","+item("photocopyRateUpToA3",1.5,rate)+","+
                item("contractTitle",Collections.singletonMap("title","Works A"),title)+"]","[]");
        extract(project);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertEquals("rejected",report.path("decisions").get(1).path("status").asText());
        assertEquals("accepted",report.path("decisions").get(2).path("status").asText());
        assertEquals("",variable(project,"projectArchitectName").path("value").asText());
        assertEquals("",variable(project,"photocopyRateUpToA3").path("value").asText());
        assertEquals("Works A",JsonUtils.parse(variable(project,"contractTitle").path("value").asText()).path("title").asText());
        assertFalse(variable(project,"contractTitle").path("confirmed").asBoolean());
    }

    @Test void billConfirmationRequestsDoNotEstablishFullOrPartialIdentifiers() throws Exception {
        List<Map<String,Object>> values=Arrays.asList(bill("1","Preliminaries"),Collections.singletonMap("number","1"),Collections.singletonMap("description","Preliminaries"));
        String[] quotes={"Please confirm whether Bill No. 1 is Preliminaries?","Please confirm whether Bill No. 1 is included?",
                "Please confirm whether the Bill description is Preliminaries?"};
        for(int i=0;i<quotes.length;i++) {
            for(String quote:Arrays.asList(quotes[i],quotes[i].replace('?','.'))) {
                String project=project();uploadText(project,quote);
                when(externalModel.chat(anyList())).thenReturn(answer("billNos",Collections.singletonList(values.get(i)),quote),"[]");
                extract(project);JsonNode report=trace(project);
                assertEquals("rejected",report.path("decisions").get(0).path("status").asText(),quote);
                assertTrue(report.path("decisions").get(0).path("codes").toString().contains("lexical_support_missing"));
                assertEquals("",variable(project,"billNos").path("value").asText());
                assertEquals(0,variable(project,"billNos").path("candidates").size());
                assertEquals(2,report.path("parts").get(0).path("attempts").size());
                assertEquals(report,response(get("/api/drafting/{id}/variables/extract-traces/{run}",project,report.path("runId").asText())));
            }
        }
    }

    @Test void anUnrelatedQuestionDoesNotEraseDeclaredFullOrPartialBillIdentifiers() throws Exception {
        List<Map<String,Object>> values=Arrays.asList(bill("1","Preliminaries"),Collections.singletonMap("number","1"),Collections.singletonMap("description","Preliminaries"));
        String[] declarations={"Bill No. 1 is Preliminaries.","Bill No. 1 is identified; its formal description is not supplied.",
                "The Bill description is Preliminaries."};
        for(int i=0;i<declarations.length;i++) {
            String quote=declarations[i]+" Please confirm the site inspection dates?";
            String project=project();uploadText(project,quote);
            when(externalModel.chat(anyList())).thenReturn(answer("billNos",Collections.singletonList(values.get(i)),quote));
            extract(project);
            assertEquals("accepted",trace(project).path("decisions").get(0).path("status").asText(),quote);
            JsonNode row=JsonUtils.parse(variable(project,"billNos").path("value").asText()).get(0);
            for(String key:Arrays.asList("number","description"))assertEquals(values.get(i).containsKey(key)?values.get(i).get(key):"",row.path(key).asText());
            assertEquals(1,variable(project,"billNos").path("candidates").size());
            assertFalse(variable(project,"billNos").path("confirmed").asBoolean());
        }
    }

    private Map<String,Object> bill(String number,String description) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("number",number);row.put("description",description);return row;
    }

    private String answer(String key,Object value,String quote) {
        return "["+item(key,value,quote)+"]";
    }
    private String item(String key,Object value,String quote) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);
        item.put("reason","TEST ONLY controlled-model candidate");item.put("confidence",0.99);return JsonUtils.write(item);
    }
    private String project() throws Exception {
        String id="quote-test-only-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY quote grounding\"}"))
                .andExpect(jsonPath("$.code").value(0));return id;
    }
    private void uploadOriginal(String project,String name) throws Exception {
        byte[] bytes;String originals=System.getProperty("consense.acceptance.correspondenceDir");
        if(originals!=null)bytes=Files.readAllBytes(Paths.get(originals,name));
        else try(InputStream source=getClass().getResourceAsStream("/drafting/original-2c/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            assertNotNull(source,"Original correspondence fixture: "+name);byte[] buffer=new byte[8192];int n;
            while((n=source.read(buffer))!=-1)out.write(buffer,0,n);bytes=out.toByteArray();
        }
        upload(project,name,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes);
    }
    private void uploadText(String project,String text) throws Exception {
        upload(project,"test-only-grounding.txt","text/plain",text.getBytes(StandardCharsets.UTF_8));
    }
    private void upload(String project,String name,String type,byte[] bytes) throws Exception {
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",project).file(new MockMultipartFile("files",name,type,bytes)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
    }
    private void extract(String project) throws Exception {
        mvc.perform(post("/api/drafting/{id}/variables/extract",project)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }
    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return JsonUtils.parse(mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode trace(String project) throws Exception {return response(get("/api/drafting/{id}/variables/extract-trace",project));}
    private JsonNode variable(String project,String key) throws Exception {
        for(JsonNode input:response(get("/api/drafting/{id}/variables",project)))if(key.equals(input.path("key").asText()))return input;
        throw new AssertionError("Missing input: "+key);
    }
}
