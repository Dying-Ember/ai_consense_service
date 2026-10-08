package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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

/** Public extraction/persistence seam with real AiGateway and only the external model doubled. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
@AutoConfigureMockMvc
class DraftingValueDecodingIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final String TRADES="[\"Electrical\",\"Fire services and water pump\",\"Lift\"]";
    private static final String TRADE_QUOTE="Building services trades | Electrical; Fire services and water pump; Lift | SIM05";
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:draft_value_decoding_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","drafting-value-decoding-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @MockBean LlmClient externalModel;

    @BeforeEach void modelAvailable() {
        when(externalModel.available()).thenReturn(true);
        when(externalModel.chatModel()).thenReturn("captured-qwen2.5:7b-instruct-q4_K_M");
    }

    @Test void capturedCompleteJsonWithNativeTradeArrayProducesSupportedUnadoptedCandidate() throws Exception {
        String project=project();
        upload(project,"captured-sim08-source.docx",resource("captured-sim08-source.txt"));
        when(externalModel.chat(anyList())).thenReturn(resource("captured-sim08-response.json"));

        mvc.perform(post("/api/drafting/{id}/variables/extract",project))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        JsonNode trades=variable(project,"subcontractors");
        assertEquals(TRADES,trades.path("value").asText());
        assertFalse(trades.path("confirmed").asBoolean());
        assertFalse(trades.path("manuallyEdited").asBoolean());
        JsonNode candidate=trades.path("candidates").get(0);
        assertEquals(TRADES,candidate.path("value").asText());
        assertEquals(TRADE_QUOTE,candidate.path("sourceQuote").asText());
        assertEquals("captured-sim08-source.docx",candidate.path("fileName").asText());
        assertTrue(candidate.path("sourceDocumentId").asLong()>0);
        assertTrue(candidate.path("sourceHash").asText().matches("[a-f0-9]{64}"));
        assertEquals(1.0,candidate.path("confidence").asDouble());
        JsonNode plan=response(get("/api/drafting/{id}/plan",project));
        assertFalse(plan.path("data").path("effectiveValues").has("subcontractors"),
                "A supported model candidate remains outside generation until adopted.");
        mvc.perform(put("/api/drafting/{id}/variables/subcontractors",project).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"[\\\"Electrical\\\"]\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/drafting/{id}/variables/extract",project)).andExpect(jsonPath("$.code").value(0));
        JsonNode adopted=variable(project,"subcontractors");
        assertEquals("[\"Electrical\"]",adopted.path("value").asText(),"Successful native-value extraction preserves the user's adopted scope.");
        assertTrue(adopted.path("manuallyEdited").asBoolean());
        assertTrue(adopted.path("confirmed").asBoolean());
        assertEquals(TRADES,adopted.path("candidates").get(0).path("value").asText());
    }

    @Test void nativeObjectScalarsAndEmptyListKeepExistingEscapedStringUnknownAndValidationBehavior() throws Exception {
        String project=project();
        String quote="Contract C-2026/10 is entitled Works A; no foundation works and no specialist subcontract trades; photocopy rate 1.25. Other inputs remain unknown.";
        upload(project,"simulated-value-shapes.docx",quote);
        String contract="{\"number\":\"C-2026/10\",\"title\":\"Works A\"}";
        Map<String,Object> object=new LinkedHashMap<>();object.put("number","C-2026/10");object.put("title","Works A");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(
                candidate("contractTitle",object,quote),candidate("foundationIncluded",false,quote),
                candidate("subcontractors",java.util.Collections.emptyList(),quote),candidate("photocopyRateUpToA3",1.25,quote),
                candidate("contractPeriodMonths",null,quote),candidate("worksSubjectToExcision","",quote),
                candidate("electronicTendering",object,quote))));
        mvc.perform(post("/api/drafting/{id}/variables/extract",project)).andExpect(jsonPath("$.code").value(0));
        assertEquals(contract,variable(project,"contractTitle").path("value").asText());
        assertEquals("false",variable(project,"foundationIncluded").path("value").asText());
        assertEquals("[]",variable(project,"subcontractors").path("value").asText());
        assertEquals("1.25",variable(project,"photocopyRateUpToA3").path("value").asText());
        for(String key:Arrays.asList("contractPeriodMonths","worksSubjectToExcision","electronicTendering")) {
            assertEquals("",variable(project,key).path("value").asText());
            assertTrue(variable(project,key).path("candidates").isEmpty(),"Unknowns and invalid choice objects do not become answers.");
        }

        // The old wire contract uses strings containing escaped JSON; those suggestions stay identical.
        JsonNode before=response(get("/api/drafting/{id}/variables",project));
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(
                candidate("contractTitle",contract,quote),candidate("foundationIncluded","false",quote),
                candidate("subcontractors","[]",quote),candidate("photocopyRateUpToA3","1.25",quote),
                candidate("contractPeriodMonths",null,quote),candidate("worksSubjectToExcision","",quote),
                candidate("electronicTendering",object,quote))));
        mvc.perform(post("/api/drafting/{id}/variables/extract",project)).andExpect(jsonPath("$.code").value(0));
        assertEquals(before,response(get("/api/drafting/{id}/variables",project)),"Native and escaped forms produce the same persisted public state.");
    }

    @Test void malformedLaterEvidenceResponseCannotPartiallyReplaceCandidatesOrManualAdoption() throws Exception {
        String project=project();
        String quote="No foundation works. Building services trades are Electrical.";
        upload(project,"simulated-first.docx",quote);
        String first=JsonUtils.write(Arrays.asList(candidate("foundationIncluded",false,quote),
                candidate("subcontractors",Arrays.asList("Electrical"),quote)));
        when(externalModel.chat(anyList())).thenReturn(first);
        mvc.perform(post("/api/drafting/{id}/variables/extract",project)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/drafting/{id}/variables/foundationIncluded",project).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"true\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.manuallyEdited").value(true));
        upload(project,"simulated-second.docx","Second correspondence remains incomplete.");
        JsonNode before=response(get("/api/drafting/{id}/variables",project));
        String changed=JsonUtils.write(Arrays.asList(candidate("subcontractors",Arrays.asList("Lift"),quote)));
        String malformed="[{\"key\":\"foundationIncluded\",\"value\":false";
        when(externalModel.chat(anyList())).thenReturn(changed,malformed,malformed);

        mvc.perform(post("/api/drafting/{id}/variables/extract",project))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(5002));

        assertEquals(before,response(get("/api/drafting/{id}/variables",project)),
                "Failure after an earlier valid response must retain every candidate and manual adopted value atomically.");
        assertEquals("true",variable(project,"foundationIncluded").path("value").asText());
        assertTrue(variable(project,"foundationIncluded").path("manuallyEdited").asBoolean());
        assertEquals("[\"Electrical\"]",variable(project,"subcontractors").path("value").asText());
    }

    private Map<String,Object> candidate(String key,Object value,String quote) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);
        item.put("sourceQuote",quote);item.put("reason","Simulated wire-contract check");item.put("confidence",1);
        return item;
    }

    private String project() throws Exception {
        String id="value-decoding-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\""+id+"\",\"nameZhHans\":\"Simulated decoding test\"}"))
                .andExpect(jsonPath("$.code").value(0));
        return id;
    }
    private void upload(String project,String name,String text) throws Exception {
        byte[] bytes;
        try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for(String line:text.split("\\R",-1))document.createParagraph().createRun().setText(line);
            document.write(out);bytes=out.toByteArray();
        }
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",project)
                .file(new MockMultipartFile("files",name,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
    }
    private JsonNode variable(String project,String key) throws Exception {
        for(JsonNode input:response(get("/api/drafting/{id}/variables",project)).path("data"))
            if(key.equals(input.path("key").asText()))return input;
        throw new AssertionError("Missing required input: "+key);
    }
    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return JsonUtils.parse(mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString());
    }
    private String resource(String name) throws Exception {
        try(InputStream in=getClass().getResourceAsStream("/drafting/a10/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            assertNotNull(in,"Captured simulated fixture must be present.");
            byte[] buffer=new byte[4096];int read;
            while((read=in.read(buffer))!=-1)out.write(buffer,0,read);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
