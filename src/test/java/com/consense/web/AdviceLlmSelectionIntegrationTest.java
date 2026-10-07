package com.consense.web;

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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP/persistence/retrieval with only the two external LLM adapters doubled. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.vector.stuffing-limit=1",
        "consense.llm.chat-model=local-test-chat","consense.llm.embed-model=fixed-test-embedding",
        "consense.minimax-cn.api-key=advice-test-only-canary"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class AdviceLlmSelectionIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:advice_llm_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","advice-llm-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;

    @BeforeEach void adapters() {
        when(local.available()).thenReturn(true);
        when(local.chatModel()).thenReturn("local-test-chat");
        when(local.embedModel()).thenReturn("fixed-test-embedding");
        when(local.embed(anyList())).thenAnswer(call->{
            List<float[]> vectors=new ArrayList<>();
            for(Object ignored:call.<List<?>>getArgument(0))vectors.add(new float[]{1,0});
            return vectors;
        });
        when(miniMax.available()).thenReturn(true);
        when(miniMax.chatModel()).thenReturn("MiniMax-M3");
    }

    @Test void selectedProviderOutageIsNotReportedAsNoEvidenceOrRerouted() throws Exception {
        String id=projectWithIndex();
        when(miniMax.available()).thenReturn(false);
        clearInvocations(local,miniMax);
        mvc.perform(post("/api/advice/{id}/index/rebuild",id).header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.embedModel").value("fixed-test-embedding"))
                .andExpect(jsonPath("$.data.chunks").value(1));
        verify(local).embed(anyList());
        verify(miniMax,never()).embed(anyList());
        mvc.perform(post("/api/advice/{id}/ask",id).header("X-ConSense-Llm-Profile","minimax-cn")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"What is the synthetic clause?\"}"))
                .andExpect(jsonPath("$.code").value(5001));
        assertEquals(0,getData("/api/advice/"+id+"/messages").size());
        verify(local,never()).chat(anyList());verify(miniMax,never()).chat(anyList());
    }

    @Test void answerRetryKeepsSelectedProviderAndHistoryPreservesItsSafeIdentity() throws Exception {
        String id=projectWithIndex(),other=projectWithIndex();
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"Local answer TEST ONLY\"}");
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        when(miniMax.chat(anyList())).thenAnswer(invocation->{
            if(calls.getAndIncrement()==0) {
                ask(other,"local").andExpect(jsonPath("$.data.content.en").value("Local answer TEST ONLY"));
                return "{\"grounded\":";
            }
            return "{\"grounded\":true,\"answer\":\"MiniMax answer TEST ONLY\"}";
        });
        JsonNode answer=JsonUtils.parse(ask(id,"minimax-cn").andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content.en").value("MiniMax answer TEST ONLY"))
                .andExpect(jsonPath("$.data.modelIdentity.profileId").value("minimax-cn"))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(2,calls.get());assertEquals("MiniMax-M3",answer.path("model").asText());
        assertEquals("configured_profile",answer.path("modelIdentity").path("identityScope").asText());
        assertTrue(answer.path("modelIdentity").path("configurationSha256").asText().matches("[a-f0-9]{64}"));
        assertFalse(answer.toString().contains("advice-test-only-canary"));
        JsonNode messages=JsonUtils.parse(mvc.perform(get("/api/advice/{id}/messages",id)
                .header("X-ConSense-Llm-Profile","local")).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(2,messages.size());assertEquals(answer.path("modelIdentity"),messages.get(1).path("modelIdentity"));
        assertEquals("local",getData("/api/advice/"+other+"/messages").get(1).path("modelIdentity").path("profileId").asText());
        verify(miniMax,never()).embed(anyList());
    }

    @Test void trueNoEvidenceResponseDoesNotClaimAChatModelRan() throws Exception {
        String id=projectWithIndex();
        when(local.embed(anyList())).thenReturn(Collections.singletonList(new float[]{0,1}));
        when(miniMax.available()).thenReturn(false);
        ask(id,"minimax-cn").andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.grounded").value(false))
                .andExpect(jsonPath("$.data.title.en").value("I don't know"))
                .andExpect(jsonPath("$.data.model").doesNotExist())
                .andExpect(jsonPath("$.data.modelIdentity").doesNotExist());
        JsonNode history=getData("/api/advice/"+id+"/messages");
        assertEquals(2,history.size());assertTrue(history.get(1).path("modelIdentity").isMissingNode());
        verify(miniMax,never()).chat(anyList());verify(local,never()).chat(anyList());
    }

    @Test void selectedProviderFailureIsVisibleWithoutExposingItsErrorCredentialOrCallingLocalChat() throws Exception {
        String id=projectWithIndex();
        when(miniMax.chat(anyList())).thenThrow(new IllegalStateException("Authorization Bearer advice-test-only-canary; synthetic quota failure"));
        JsonNode error=JsonUtils.parse(ask(id,"minimax-cn").andExpect(jsonPath("$.code").value(4203))
                .andReturn().getResponse().getContentAsString());
        assertFalse(error.toString().contains("advice-test-only-canary"));
        assertTrue(error.path("message").asText().contains("minimax-cn"));
        assertEquals(0,getData("/api/advice/"+id+"/messages").size());
        verify(local,never()).chat(anyList());
    }

    @Test void requiredFixedEmbeddingOutageIsVisibleWithoutUsingCloudEmbeddingOrChat() throws Exception {
        String id=projectWithIndex();
        when(local.available()).thenReturn(false);
        ask(id,"minimax-cn").andExpect(jsonPath("$.code").value(4203));
        assertEquals(0,getData("/api/advice/"+id+"/messages").size());
        verify(miniMax,never()).embed(anyList());verify(miniMax,never()).chat(anyList());
    }

    @Test void modelDispatchedNoBasisAnswerRetainsIdentityUnlikeDeterministicNoHitResponse() throws Exception {
        String id=projectWithIndex();
        when(miniMax.chat(anyList())).thenReturn("{\"grounded\":false,\"missingReason\":\"Synthetic evidence does not answer this question\"}");
        JsonNode answer=JsonUtils.parse(ask(id,"minimax-cn").andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.grounded").value(false))
                .andExpect(jsonPath("$.data.modelIdentity.profileId").value("minimax-cn"))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(answer.path("modelIdentity"),getData("/api/advice/"+id+"/messages").get(1).path("modelIdentity"));
    }

    @Test void headerAbsentAdviceKeepsLegacyChatAndTheFixedEmbeddingIndex() throws Exception {
        String id=projectWithIndex();
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"Legacy deployment answer TEST ONLY\"}");
        mvc.perform(post("/api/advice/{id}/ask",id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"What is the synthetic clause?\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content.en").value("Legacy deployment answer TEST ONLY"))
                .andExpect(jsonPath("$.data.model").value("local-test-chat"));
        mvc.perform(get("/api/advice/{id}/index/status",id).header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.data.embedModel").value("fixed-test-embedding"))
                .andExpect(jsonPath("$.data.chunks").value(1));
        verify(miniMax,never()).chat(anyList());verify(miniMax,never()).embed(anyList());
    }

    private org.springframework.test.web.servlet.ResultActions ask(String id,String profile) throws Exception {
        return mvc.perform(post("/api/advice/{id}/ask",id).header("X-ConSense-Llm-Profile",profile)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"What is the synthetic clause?\"}"));
    }

    private String projectWithIndex() throws Exception {
        String id="advice-synthetic-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\""+id+"\",\"nameZhHans\":\"ADVICE LLM SWITCH TEST ONLY\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",id).file(new MockMultipartFile("files",
                "ADVICE-SYNTHETIC-TEST-ONLY.txt","text/plain","TEST ONLY. SCC4.1: synthetic order of precedence is set by this test clause.".getBytes(StandardCharsets.UTF_8))))
                .andExpect(jsonPath("$.data.parsed").value(1));
        mvc.perform(post("/api/advice/{id}/index/rebuild",id)).andExpect(jsonPath("$.code").value(0));
        return id;
    }
    private JsonNode getData(String url) throws Exception {
        return JsonUtils.parse(mvc.perform(get(url)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");
    }
}
