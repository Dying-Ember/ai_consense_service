package com.consense.web;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.minimax-cn.api-key=test-only-placeholder"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class LlmRoutingIntegrationTest {
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:h2:mem:llm_routing_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","llm-routing-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;

    @Test void failedSelectedOperationKeepsManualValueAndItsPersistedFailureIdentityWithoutFallback() throws Exception {
        String id=project();upload(id,"failure.docx","No foundation works are included.");
        mvc.perform(put("/api/drafting/{id}/variables/foundationIncluded",id).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"true\"}"))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode before=getData("/api/drafting/"+id+"/variables");
        when(miniMax.available()).thenReturn(true);when(miniMax.chat(anyList())).thenThrow(new com.consense.common.BizException("Selected profile authentication failed"));
        mvc.perform(post("/api/drafting/{id}/variables/extract",id).header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.code").value(4000)).andExpect(jsonPath("$.message").value("Selected profile authentication failed"));
        JsonNode saved=getData("/api/drafting/"+id+"/variables/extract-trace");assertEquals("failed",saved.path("status").asText());
        assertEquals("minimax-cn",saved.path("modelIdentity").path("profileId").asText());
        JsonNode reread=getData("/api/drafting/"+id+"/variables/extract-traces/"+saved.path("runId").asText());assertEquals(saved,reread);
        assertEquals(before,getData("/api/drafting/"+id+"/variables"));
        mvc.perform(get("/api/drafting/{id}/variables/extract-traces",id).header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.data[0].modelIdentity.profileId").value("minimax-cn"));
        verify(miniMax,times(1)).chat(anyList());verifyNoInteractions(local);
    }

    @Test void explicitRepairTransportFailureIsVisibleAndPreservesItsOriginalRejection() throws Exception {
        String id=project();upload(id,"repair-failure.docx","Contract Z-7 is entitled Works B.");
        when(miniMax.available()).thenReturn(true);
        when(miniMax.chat(anyList())).thenReturn("[{\"key\":\"contractTitle\",\"value\":\"Works B\",\"sourceQuote\":\"Contract Z-7 is entitled Works B.\",\"confidence\":1}]")
                .thenThrow(new com.consense.common.BizException("Selected profile repair quota exhausted"));
        mvc.perform(post("/api/drafting/{id}/variables/extract",id).header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.code").value(4000));
        JsonNode saved=getData("/api/drafting/"+id+"/variables/extract-trace");assertEquals("failed",saved.path("status").asText());
        assertEquals("invalid_value_shape",saved.path("decisions").get(0).path("codes").get(0).asText());
        assertEquals(2,saved.path("parts").get(0).path("attempts").size());
        assertEquals("failed",saved.path("parts").get(0).path("attempts").get(1).path("status").asText());
        verify(miniMax,times(2)).chat(anyList());verifyNoInteractions(local);
    }

    @Test void retriesAndRepairStayOnSelectedChatDespiteAnotherRequestAndHistoryRetainsConfiguredIdentity() throws Exception {
        String first=project(),other=project();String source="Contract Z-7 is entitled Works B. No foundation works.";
        upload(first,"operation.docx",source);upload(other,"other-operation.docx",source);
        when(local.available()).thenReturn(true);when(local.chatModel()).thenReturn("legacy-local-test");when(local.chat(anyList())).thenReturn("[]");
        when(miniMax.available()).thenReturn(true);when(miniMax.chatModel()).thenReturn("MiniMax-M3");
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        when(miniMax.chat(anyList())).thenAnswer(invocation->{
            int call=calls.getAndIncrement();
            if(call==0){mvc.perform(post("/api/drafting/{id}/variables/extract",other).header("X-ConSense-Llm-Profile","local")).andExpect(jsonPath("$.code").value(0));return "[{\"key\":";}
            if(call==1)return "[{\"key\":\"contractTitle\",\"value\":\"Works B\",\"sourceQuote\":\"Contract Z-7 is entitled Works B.\",\"confidence\":1}]";
            return "[{\"key\":\"contractTitle\",\"value\":{\"number\":\"Z-7\",\"title\":\"Works B\"},\"sourceQuote\":\"Contract Z-7 is entitled Works B.\",\"confidence\":1}]";
        });
        mvc.perform(post("/api/drafting/{id}/variables/extract",first).header("X-ConSense-Llm-Profile","minimax-cn")).andExpect(jsonPath("$.code").value(0));
        assertEquals(3,calls.get());JsonNode trace=getData("/api/drafting/"+first+"/variables/extract-trace");
        assertEquals("MiniMax-M3",trace.path("model").asText());assertEquals("minimax-cn",trace.path("modelIdentity").path("profileId").asText());
        assertEquals("openai",trace.path("modelIdentity").path("provider").asText());assertEquals("configured_profile",trace.path("modelIdentity").path("identityScope").asText());
        assertTrue(trace.path("modelIdentity").path("configurationSha256").asText().matches("[a-f0-9]{64}"));
        assertEquals(3,trace.path("parts").get(0).path("attempts").size());
        JsonNode stored=getData("/api/drafting/"+first+"/variables/extract-traces/"+trace.path("runId").asText());assertEquals(trace.path("modelIdentity"),stored.path("modelIdentity"));
        assertEquals("local",getData("/api/drafting/"+other+"/variables/extract-trace").path("modelIdentity").path("profileId").asText());
        assertFalse(trace.toString().contains("test-only-placeholder"));
        mvc.perform(post("/api/drafting/{id}/variables/extract",other)).andExpect(jsonPath("$.code").value(0));
        assertEquals("legacy-local-test",getData("/api/drafting/"+other+"/variables/extract-trace").path("model").asText());
        verify(local,times(2)).chat(anyList());
    }
    String project()throws Exception{String id="route-"+UUID.randomUUID();mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY\"}")).andExpect(jsonPath("$.code").value(0));return id;}
    void upload(String id,String name,String source)throws Exception{byte[] bytes;try(XWPFDocument d=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){d.createParagraph().createRun().setText(source);d.write(out);bytes=out.toByteArray();}mvc.perform(multipart("/api/drafting/{id}/inputs/upload",id).file(new MockMultipartFile("files",name,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes))).andExpect(jsonPath("$.data.parsed").value(1));}
    JsonNode getData(String url)throws Exception{return JsonUtils.parse(mvc.perform(get(url)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
}
