package com.consense.web;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.minimax-cn.api-key=test-only-placeholder",
        "consense.vetting.semantic-topics=1","consense.vetting.project-reference-comparisons=0",
        "consense.vetting.responses.enabled=true","consense.vetting.responses.api-key="})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class LlmVettingProfileIntegrationTest {
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:llm_vetting_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get("target","llm-vetting-uploads",DB).toAbsolutePath().toString());}
    @Autowired MockMvc mvc;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;
    @MockBean HttpSupport http;

    @Test void selectedTransportFailurePersistsFailedRunAndPacketAuditWithoutLocalFallback() throws Exception {
        when(http.postJson(anyString(),anyString(),anyLong())).thenThrow(new IllegalStateException("External retrieval fixture unavailable"));
        when(miniMax.available()).thenReturn(true);
        when(miniMax.chatStructured(anyList(),any())).thenThrow(new com.consense.common.BizException("Selected profile quota exhausted"));
        String id=project();upload(id);
        JsonNode queued=postData("/api/vetting/"+id+"/runs","minimax-cn");JsonNode saved=await(id,queued.path("id").asText());
        assertEquals("FAILED",saved.path("status").asText());assertTrue(saved.path("error").asText().contains("quota"));
        assertEquals(queued.path("modelIdentity"),saved.path("modelIdentity"));assertTrue(saved.path("result").isMissingNode());
        assertEquals("failed",saved.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).path("status").asText());
        verify(miniMax,times(1)).chatStructured(anyList(),any());verifyNoInteractions(local);
    }

    @Test void absentHeaderRetainsLegacyResponsesBudgetAndLeavesConfiguredProfileIdentityUnknown() throws Exception {
        when(http.postJson(anyString(),anyString(),anyLong())).thenThrow(new IllegalStateException("External retrieval fixture unavailable"));
        String id=project();upload(id);
        JsonNode queued=JsonUtils.parse(mvc.perform(post("/api/vetting/"+id+"/runs")).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(queued.path("modelIdentity").isMissingNode());JsonNode saved=await(id,queued.path("id").asText());
        assertEquals("COMPLETED",saved.path("status").asText());assertTrue(saved.path("modelIdentity").isMissingNode());
        assertTrue(saved.path("result").path("modelIdentity").isMissingNode());assertEquals("MiniMax-M3",saved.path("result").path("model").asText());
        JsonNode packet=saved.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0);
        assertEquals("budget_unknown",packet.path("inputBudgetStatus").asText());assertFalse(packet.path("actualGatewayCallStarted").asBoolean());
        verifyNoInteractions(local,miniMax);
    }

    @Test void queuedSelectionsSurviveAnotherRequestAndActiveReuseReturnsOriginalIdentityDespiteLegacyResponsesFlag() throws Exception {
        when(http.postJson(anyString(),anyString(),anyLong())).thenThrow(new IllegalStateException("External retrieval fixture unavailable"));
        when(local.available()).thenReturn(true);when(local.chatModel()).thenReturn("legacy-local");when(local.chatStructured(anyList(),any())).thenReturn("[]");
        when(miniMax.available()).thenReturn(true);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(miniMax.chatStructured(anyList(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "[]";});
        String first=project(),second=project();upload(first);upload(second);
        JsonNode queued=postData("/api/vetting/"+first+"/runs","minimax-cn");
        try {
            assertEquals("minimax-cn",queued.path("modelIdentity").path("profileId").asText());
            assertTrue(entered.await(3,TimeUnit.SECONDS),"Selected MiniMax chat wire must dispatch, not the legacy Responses wire.");
            JsonNode reused=postData("/api/vetting/"+first+"/runs","local");assertEquals(queued.path("id"),reused.path("id"));assertEquals(queued.path("modelIdentity"),reused.path("modelIdentity"));
            JsonNode next=postData("/api/vetting/"+second+"/runs","local");assertEquals("local",next.path("modelIdentity").path("profileId").asText());
            release.countDown();JsonNode completed=await(first,queued.path("id").asText());JsonNode later=await(second,next.path("id").asText());
            assertEquals("COMPLETED",completed.path("status").asText());assertEquals(queued.path("modelIdentity"),completed.path("modelIdentity"));
            assertEquals("MiniMax-M3",completed.path("result").path("model").asText());assertEquals(queued.path("modelIdentity"),completed.path("result").path("modelIdentity"));
            assertEquals("COMPLETED",later.path("status").asText());assertEquals("local",later.path("result").path("modelIdentity").path("profileId").asText());
            assertEquals("budget_unknown",completed.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).path("inputBudgetStatus").asText());
            verify(miniMax,times(1)).chatStructured(anyList(),any());verify(local,times(1)).chatStructured(anyList(),any());
            assertFalse(completed.toString().contains("test-only-placeholder"));
        } finally {release.countDown();}
    }
    String project()throws Exception{String id="vet-route-"+UUID.randomUUID();mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY\"}")).andExpect(jsonPath("$.code").value(0));return id;}
    void upload(String id)throws Exception{byte[] bytes;try(XWPFDocument d=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){d.createParagraph().createRun().setText("NTT 1.1 The Contractor shall retain each original contract clause and reference.");d.write(out);bytes=out.toByteArray();}mvc.perform(multipart("/api/vetting/{id}/package/upload",id).file(new MockMultipartFile("files","NTT.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes))).andExpect(jsonPath("$.data.parsed").value(1));}
    JsonNode postData(String url,String profile)throws Exception{return JsonUtils.parse(mvc.perform(post(url).header("X-ConSense-Llm-Profile",profile)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    JsonNode getData(String url)throws Exception{return JsonUtils.parse(mvc.perform(get(url)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    JsonNode await(String project,String id)throws Exception{for(int i=0;i<150;i++){JsonNode job=getData("/api/vetting/"+project+"/runs/"+id);if(Arrays.asList("COMPLETED","FAILED").contains(job.path("status").asText()))return job;Thread.sleep(20);}throw new AssertionError("Bounded fixture job did not finish");}
}
