package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.UUID;
import java.util.stream.Stream;
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

/** Existing correspondence DELETE contract, observed through HTTP and the original-file storage boundary. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingCorrespondenceRemovalIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final Path STORAGE=Paths.get("target","drafting-correspondence-removal-uploads",DB).toAbsolutePath();
    private static final String QUOTE="The photocopy rate up to A3 is HK$1.50 per page.";
    private static final byte[] ORIGINAL=QUOTE.getBytes(StandardCharsets.UTF_8);
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:draft_correspondence_removal_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->STORAGE.toString());
    }
    @Autowired MockMvc mvc;
    @MockBean LlmClient externalModel;
    @BeforeEach void modelAvailable() {
        when(externalModel.available()).thenReturn(true);
        when(externalModel.chatModel()).thenReturn("test-only-correspondence-removal");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Collections.singletonList(
                DraftBusinessRules.map("key","photocopyRateUpToA3","value",1.5,"sourceQuote",QUOTE,
                        "reason","TEST ONLY controlled source candidate","confidence",0.99))));
    }

    @Test void removalDeactivatesTheInputAndReviewsLinkedAdoptedValuesWhileKeepingOriginalEvidence() throws Exception {
        String id=project();long input=uploadAndAdopt(id);Path stored=originalFile(id);
        assertArrayEquals(ORIGINAL,Files.readAllBytes(stored));
        JsonNode adopted=variable(id,"photocopyRateUpToA3");
        assertEquals("1.5",adopted.path("value").asText());assertFalse(adopted.path("reviewRequired").asBoolean());

        mvc.perform(delete("/api/drafting/{id}/inputs/{input}",id,input)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/drafting/{id}/inputs",id)).andExpect(jsonPath("$.data.length()").value(0));
        JsonNode reviewed=variable(id,"photocopyRateUpToA3");
        assertEquals("1.5",reviewed.path("value").asText());assertTrue(reviewed.path("reviewRequired").asBoolean());
        assertEquals(adopted.path("candidates"),reviewed.path("candidates"),"Source-linked suggestions remain inspectable evidence.");
        JsonNode unrelated=variable(id,"siteInspectionStartDate");
        assertEquals("2026-10-17",unrelated.path("value").asText());assertFalse(unrelated.path("reviewRequired").asBoolean());
        assertArrayEquals(ORIGINAL,Files.readAllBytes(stored),"Removal preserves the originally stored correspondence bytes.");
        mvc.perform(put("/api/drafting/{id}/variables/photocopyRateUpToA3",id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"candidateIndex\":0}"))
                .andExpect(jsonPath("$.code").value(4007));
    }

    @Test void failedRemovalRetainsTheActiveCorrespondenceAndItsAdoptedValues() throws Exception {
        String id=project();long input=uploadAndAdopt(id);Path stored=originalFile(id);
        JsonNode beforeInputs=response(get("/api/drafting/{id}/inputs",id));
        JsonNode beforeValue=variable(id,"photocopyRateUpToA3");
        mvc.perform(delete("/api/drafting/{id}/inputs/{input}",project(),input))
                .andExpect(jsonPath("$.code").value(4011));
        assertEquals(beforeInputs,response(get("/api/drafting/{id}/inputs",id)));
        assertEquals(beforeValue,variable(id,"photocopyRateUpToA3"));
        assertFalse(variable(id,"siteInspectionStartDate").path("reviewRequired").asBoolean());
        assertArrayEquals(ORIGINAL,Files.readAllBytes(stored));
    }

    private String project() throws Exception {
        String id="correspondence-removal-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.write(DraftBusinessRules.map("id",id,"nameZhHans","测试专用沟通资料移除"))))
                .andExpect(jsonPath("$.code").value(0));return id;
    }
    private long uploadAndAdopt(String id) throws Exception {
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",id)
                .file(new MockMultipartFile("files","test-only-rates.txt","text/plain",ORIGINAL)))
                .andExpect(jsonPath("$.data.parsed").value(1));
        JsonNode inputs=response(get("/api/drafting/{id}/inputs",id));
        assertEquals("test-only-rates.txt",inputs.get(0).path("fileName").asText());
        mvc.perform(post("/api/drafting/{id}/variables/extract",id)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/drafting/{id}/variables/photocopyRateUpToA3",id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"candidateIndex\":0}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/drafting/{id}/variables/siteInspectionStartDate",id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"2026-10-17\"}"))
                .andExpect(jsonPath("$.code").value(0));
        return inputs.get(0).path("id").asLong();
    }
    private Path originalFile(String id) throws Exception {
        try(Stream<Path> files=Files.walk(STORAGE.resolve(id))) {return files.filter(Files::isRegularFile).findFirst().get();}
    }
    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return JsonUtils.parse(mvc.perform(request).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode variable(String id,String key) throws Exception {
        for(JsonNode input:response(get("/api/drafting/{id}/variables",id)))if(key.equals(input.path("key").asText()))return input;
        throw new AssertionError("Missing input "+key);
    }
}
