package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
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

/** Public project/upload/extraction/adoption/report APIs; only the external model is doubled. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingTradeConflictIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:draft_trade_conflict_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","drafting-trade-conflict-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired DraftingService service;
    @MockBean LlmClient externalModel;
    @BeforeEach void modelAvailable() {
        when(externalModel.available()).thenReturn(true);
        when(externalModel.chatModel()).thenReturn("test-only-trade-conflict-model");
    }

    @Test void differentCompleteTradeSetsRemainCandidatesForUserChoice() throws Exception {
        String project=project();
        String electrical="Selected trades: Electrical. This is the complete selected trade list; no other trade is included.";
        String lift="Selected trades: Lift. This is the complete selected trade list; no other trade is included.";
        upload(project,"test-only-complete-electrical.txt",electrical);
        upload(project,"test-only-complete-lift.txt",lift);
        when(externalModel.chat(anyList())).thenReturn(reply(Arrays.asList("Electrical"),electrical),reply(Arrays.asList("Lift"),lift));
        JsonNode extracted=extract(project);
        JsonNode input=find(extracted,"subcontractors");
        assertEquals("conflict",input.path("adoptionState").asText());
        assertEquals("",input.path("value").asText());
        assertFalse(input.path("confirmed").asBoolean());
        assertFalse(input.path("manuallyEdited").asBoolean());
        assertEquals(input,variable(project));
        assertEquals(2,input.path("candidates").size());
        assertCandidate(input.path("candidates").get(0),"[\"Electrical\"]",electrical,"test-only-complete-electrical.txt");
        assertCandidate(input.path("candidates").get(1),"[\"Lift\"]",lift,"test-only-complete-lift.txt");
        assertEquals("candidate_conflict",find(trace(project).path("fields"),"subcontractors").path("status").asText());
        JsonNode adopted=response(put("/api/drafting/{id}/variables/subcontractors",project).contentType(MediaType.APPLICATION_JSON).content(DraftAdoptionTestPayload.candidate(service,project,"subcontractors",1)));
        assertEquals("[\"Lift\"]",adopted.path("value").asText());
        assertTrue(adopted.path("confirmed").asBoolean());
        assertEquals("adopted",adopted.path("adoptionState").asText());
        assertEquals(lift,adopted.path("source").asText());
        when(externalModel.chat(anyList())).thenReturn(reply(Arrays.asList("Electrical"),electrical),reply(Arrays.asList("Lift"),lift));
        extract(project);
        assertEquals(adopted,variable(project),"Re-extraction of the same alternatives retains the user's adopted candidate.");
    }

    @Test void explicitPartialTradeConfirmationsCanCombineWithoutCollapsingCompositeOptions() throws Exception {
        String project=project();
        String electrical="Selected trades: Electrical. This is a partial trade confirmation.";
        String lift="Selected trades: Lift and escalator. This is an additional selected trade.";
        upload(project,"test-only-partial-electrical.txt",electrical);
        upload(project,"test-only-additional-lift-escalator.txt",lift);
        when(externalModel.chat(anyList())).thenReturn(reply(Arrays.asList("Electrical"),electrical),reply(Arrays.asList("Lift and escalator"),lift));
        extract(project);
        JsonNode input=variable(project);
        assertEquals("[\"Electrical\",\"Lift and escalator\"]",input.path("value").asText());
        assertEquals("suggested",input.path("adoptionState").asText());
        assertFalse(input.path("confirmed").asBoolean());
        assertEquals(2,input.path("candidates").size());
        assertCandidate(input.path("candidates").get(1),"[\"Lift and escalator\"]",lift,"test-only-additional-lift-escalator.txt");
        assertEquals("suggested",find(trace(project).path("fields"),"subcontractors").path("status").asText());
    }

    @Test void partialFactWithinACompleteSetDoesNotCreateAFalseConflictInEitherSourceOrder() throws Exception {
        String partial="Selected trades: Lift and escalator. This is a partial trade confirmation, not the full list.";
        String complete="Selected trades: Lift and escalator; Electrical. This is the complete selected trade list.";
        for(boolean completeFirst:Arrays.asList(true,false)) {
            String project=project();
            upload(project,"test-only-complete-and-partial.txt",partial+"\n"+complete);
            Map<String,Object> full=candidate(Arrays.asList("Lift and escalator","Electrical"),complete);
            Map<String,Object> detail=candidate(Arrays.asList("Lift and escalator"),partial);
            when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(completeFirst?Arrays.asList(full,detail):Arrays.asList(detail,full)));
            extract(project);
            JsonNode input=variable(project);
            assertEquals("[\"Electrical\",\"Lift and escalator\"]",input.path("value").asText());
            assertEquals("suggested",input.path("adoptionState").asText());
            assertFalse(input.path("confirmed").asBoolean());
            assertEquals("suggested",find(trace(project).path("fields"),"subcontractors").path("status").asText());
        }
    }

    @Test void explicitCompleteScopeCannotBeEnlargedByAnUnapprovedAdditionalTrade() throws Exception {
        String project=project();
        String complete="Selected trades: Electrical. This is the complete selected trade list. An additional selected trade would require separate approval.";
        String partial="Selected trades: Lift. This is a partial trade confirmation.";
        upload(project,"test-only-complete-scope-with-approval.txt",complete+"\n"+partial);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate(Arrays.asList("Electrical"),complete),candidate(Arrays.asList("Lift"),partial))));
        extract(project);
        assertEquals("conflict",variable(project).path("adoptionState").asText());
        assertEquals("",variable(project).path("value").asText());
        assertEquals("candidate_conflict",find(trace(project).path("fields"),"subcontractors").path("status").asText());
    }

    @Test void explicitNoneAndANonemptyCompleteListConflictInEitherCandidateOrder() throws Exception {
        String none="Selected trade list: none. No specialist subcontract trades are included in this Contract.";
        String electrical="Selected trades: Electrical. This is the complete selected trade list.";
        for(boolean noneFirst:Arrays.asList(true,false)) {
            String project=project();upload(project,"test-only-none-or-electrical.txt",none+"\n"+electrical);
            Map<String,Object> noTrades=candidate(Collections.emptyList(),none);
            Map<String,Object> selected=candidate(Arrays.asList("Electrical"),electrical);
            when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(noneFirst?Arrays.asList(noTrades,selected):Arrays.asList(selected,noTrades)));
            extract(project);JsonNode input=variable(project);
            assertEquals("",input.path("value").asText());
            assertEquals("conflict",input.path("adoptionState").asText());
            assertEquals(2,input.path("candidates").size());
            assertEquals("candidate_conflict",find(trace(project).path("fields"),"subcontractors").path("status").asText());
            JsonNode adopted=response(put("/api/drafting/{id}/variables/subcontractors",project).contentType(MediaType.APPLICATION_JSON)
                    .content(DraftAdoptionTestPayload.candidate(service,project,"subcontractors",noneFirst?0:1)));
            assertEquals("[]",adopted.path("value").asText());
            assertTrue(adopted.path("confirmed").asBoolean());
            assertEquals("adopted",adopted.path("adoptionState").asText());
        }
    }

    @Test void equalCompleteSetsWithDifferentOptionOrderRemainOneSuggestedValue() throws Exception {
        String project=project();
        String quote="Selected trades: Lift and escalator; Electrical. This is the complete selected trade list.";
        upload(project,"test-only-same-complete-set.txt",quote);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(
                candidate(Arrays.asList("Lift and escalator","Electrical"),quote),candidate(Arrays.asList("Electrical","Lift and escalator"),quote))));
        extract(project);JsonNode input=variable(project);
        assertEquals("[\"Electrical\",\"Lift and escalator\"]",input.path("value").asText());
        assertEquals("suggested",input.path("adoptionState").asText());
        assertFalse(input.path("confirmed").asBoolean());
        assertEquals(1,input.path("candidates").size(),"Repeated normalized answers from the same source and source hash are one candidate, not independent evidence.");
        assertCandidate(input.path("candidates").get(0),"[\"Electrical\",\"Lift and escalator\"]",quote,"test-only-same-complete-set.txt");
        JsonNode report=trace(project);
        assertEquals(2,report.path("decisions").size(),"Keep both original model items in the immutable diagnostics.");
        for(JsonNode decision:report.path("decisions")) {
            assertEquals("accepted",decision.path("status").asText());
            assertEquals(input.path("value").asText(),decision.path("normalizedValue").asText());
        }
        assertNotEquals(report.path("decisions").get(0).path("rawValue"),report.path("decisions").get(1).path("rawValue"),"Original option orders remain auditable.");
        assertEquals("suggested",find(report.path("fields"),"subcontractors").path("status").asText());
    }

    @Test void conflictingReextractionPreservesManualAdoptionAndImmutableReportUntilSourceResolution() throws Exception {
        String project=project();
        String electrical="Selected trades: Electrical. This is the complete selected trade list.";
        String lift="Selected trades: Lift. This is the complete selected trade list.";
        upload(project,"test-only-manual-alternative-electrical.txt",electrical);
        upload(project,"test-only-manual-alternative-lift.txt",lift);
        response(put("/api/drafting/{id}/variables/subcontractors",project).contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.write(Collections.singletonMap("value","[\"Fire services and water pump\"]"))));
        when(externalModel.chat(anyList())).thenReturn(reply(Arrays.asList("Electrical"),electrical),reply(Arrays.asList("Lift"),lift));
        extract(project);JsonNode input=variable(project);
        assertEquals("[\"Fire services and water pump\"]",input.path("value").asText());
        assertTrue(input.path("manuallyEdited").asBoolean());
        assertTrue(input.path("confirmed").asBoolean());
        assertTrue(input.path("reviewRequired").asBoolean());
        assertEquals("needs_review",input.path("adoptionState").asText());
        JsonNode conflictTrace=trace(project);String conflictRun=conflictTrace.path("runId").asText();
        assertEquals("candidate_conflict",find(conflictTrace.path("fields"),"subcontractors").path("status").asText());
        Long removed=input.path("candidates").get(1).path("sourceDocumentId").asLong();
        response(delete("/api/drafting/{id}/inputs/{sourceId}",project,removed));
        when(externalModel.chat(anyList())).thenReturn(reply(Arrays.asList("Electrical"),electrical));
        extract(project);
        JsonNode resolved=variable(project);
        assertEquals("[\"Fire services and water pump\"]",resolved.path("value").asText());
        assertTrue(resolved.path("reviewRequired").asBoolean());
        assertEquals(1,resolved.path("candidates").size());
        assertEquals("suggested",find(trace(project).path("fields"),"subcontractors").path("status").asText());
        JsonNode historic=response(get("/api/drafting/{id}/variables/extract-traces/{runId}",project,conflictRun));
        assertEquals(conflictTrace.path("decisions"),historic.path("decisions"));
        assertEquals(conflictTrace.path("rawResponses"),historic.path("rawResponses"));
        assertTrue(historic.path("stale").asBoolean());
        JsonNode reviewed=response(put("/api/drafting/{id}/variables/subcontractors",project).contentType(MediaType.APPLICATION_JSON).content(DraftAdoptionTestPayload.reviewed(service,project,"subcontractors",null)));
        assertEquals("adopted",reviewed.path("adoptionState").asText());
        assertFalse(reviewed.path("reviewRequired").asBoolean());
        assertEquals("[\"Fire services and water pump\"]",reviewed.path("value").asText());
    }

    private void assertCandidate(JsonNode candidate,String value,String quote,String fileName) {
        assertEquals(value,candidate.path("value").asText());
        assertEquals(quote,candidate.path("sourceQuote").asText());
        assertEquals(fileName,candidate.path("fileName").asText());
        assertTrue(candidate.path("sourceDocumentId").asLong()>0);
        assertTrue(candidate.path("sourceHash").asText().matches("[a-f0-9]{64}"));
    }
    private String reply(List<String> value,String quote) {
        return JsonUtils.write(Collections.singletonList(candidate(value,quote)));
    }
    private Map<String,Object> candidate(List<String> value,String quote) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key","subcontractors");item.put("value",value);
        item.put("sourceQuote",quote);item.put("reason","TEST ONLY source-supported trade candidate");item.put("confidence",1);
        return item;
    }
    private String project() throws Exception {
        String id="trade-conflict-test-only-"+UUID.randomUUID();
        response(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY trade conflict\"}"));return id;
    }
    private void upload(String project,String name,String text) throws Exception {
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",project).file(new MockMultipartFile("files",name,"text/plain",text.getBytes(StandardCharsets.UTF_8))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
    }
    private JsonNode extract(String project) throws Exception {return response(post("/api/drafting/{id}/variables/extract",project));}
    private JsonNode trace(String project) throws Exception {return response(get("/api/drafting/{id}/variables/extract-trace",project));}
    private JsonNode variable(String project) throws Exception {return find(response(get("/api/drafting/{id}/variables",project)),"subcontractors");}
    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return JsonUtils.parse(mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode find(JsonNode items,String key) {
        for(JsonNode item:items)if(key.equals(item.path("key").asText()))return item;
        throw new AssertionError("Missing input or field: "+key);
    }
}
