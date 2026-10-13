package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC/profile/async/H2 persistence with an in-memory native wire. No socket/model calls. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
    "consense.ocr.enabled=false","consense.vector.provider=memory","consense.llm.enabled=true",
    "consense.llm.num-ctx=524288","consense.llm.structured-thinking=false",
    "consense.llm.base-url=https://local-fixture.invalid","consense.minimax-cn.enabled=true",
    "consense.minimax-cn.base-url=http://relay-fixture.invalid","consense.minimax-cn.api-key=",
    "consense.vetting.semantic-topics=1","consense.vetting.project-reference-comparisons=0",
    "consense.vetting.minimax-cn-wire=responses","consense.vetting.responses.enabled=true",
    "consense.vetting.responses.api-key=RESPONSES_FAKE_ONLY","consense.vetting.responses.configured-context-tokens=262144",
    "consense.vetting.responses.safety-margin-tokens=500","consense.vetting.responses.estimate-policy=bound_provider_estimate_with_absolute_margin_v1"})
@ActiveProfiles("h2") @AutoConfigureMockMvc @Import(VettingExplicitResponsesWorkflowTest.Fixture.class)
class VettingExplicitResponsesWorkflowTest {
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:native_responses_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","native-responses-uploads",DB).toAbsolutePath().toString());
    }
    static class FakeWire implements VettingResponsesTransport.WireHttp {
        final List<String> endpoints=Collections.synchronizedList(new ArrayList<>()),bodies=Collections.synchronizedList(new ArrayList<>());
        final Queue<String> generationContents=new ConcurrentLinkedQueue<>();
        volatile Runnable generationHook;
        volatile int count=100;volatile String generation=VettingResponsesTransportTest.completed("[]");
        volatile CountDownLatch entered,release;
        void reset(){endpoints.clear();bodies.clear();generationContents.clear();generationHook=null;count=100;generation=VettingResponsesTransportTest.completed("[]");entered=null;release=null;}
        @Override public VettingResponsesTransport.Response post(String endpoint,String body,long timeout,int cap,String authorization) {
            assertEquals("Bearer RESPONSES_FAKE_ONLY",authorization);assertTrue(endpoint.startsWith("https://api.minimax.cn/v1/responses"));endpoints.add(endpoint);bodies.add(body);
            if(endpoint.endsWith("/input_tokens")) {
                CountDownLatch started=entered,blocked=release;
                if(started!=null){entered=null;started.countDown();try{assertTrue(blocked.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("fixture interrupted");}}
                return new VettingResponsesTransport.Response(200,"{\"object\":\"response.input_tokens\",\"input_tokens\":"+count+"}");
            }
            String content=generationContents.poll();Runnable hook=generationHook;if(hook!=null)hook.run();
            return new VettingResponsesTransport.Response(200,content==null?generation:VettingResponsesTransportTest.completed(content));
        }
    }
    @TestConfiguration static class Fixture {
        @Bean FakeWire nativeWire(){return new FakeWire();}
        @Bean @Primary VettingResponsesTransport nativeTransport(ConsenseProperties props,FakeWire wire){return new VettingResponsesTransport(props,wire);}
    }
    @Autowired MockMvc mvc;@Autowired ConsenseProperties props;@Autowired FakeWire wire;
    @Autowired VettingSemanticReview semantic;
    @MockBean(name="llmClient") LlmClient local;@MockBean(name="miniMaxChatClient") LlmClient miniMax;
    @MockBean HttpSupport http;
    @BeforeEach void reset() {
        semantic.setInputBudgetObserver(VettingTokenFixture::observe);
        wire.reset();props.getVetting().setMinimaxCnWire("responses");props.getVetting().getResponses().setEstimatePolicy(VettingResponsesTransport.POLICY);
        when(http.postJson(anyString(),anyString(),anyLong())).thenThrow(new IllegalStateException("fixture retrieval unavailable"));
    }
    @Test void frontendSelectionPersistsActualNativeWireIdentityAndPartialProviderEstimate()throws Exception {
        String id=project();upload(id);JsonNode queued=postData(id,"minimax-cn"),saved=await(id,queued.path("id").asText());
        assertEquals("COMPLETED",saved.path("status").asText());assertEquals("minimax-cn",queued.path("modelIdentity").path("profileId").asText());
        assertEquals(VettingResponsesTransport.WIRE,queued.path("modelIdentity").path("provider").asText());assertEquals("configured_vetting_wire",queued.path("modelIdentity").path("identityScope").asText());
        assertEquals(queued.path("modelIdentity"),saved.path("modelIdentity"));assertEquals(queued.path("modelIdentity"),saved.path("result").path("modelIdentity"));
        assertEquals("MiniMax-M3",saved.path("result").path("model").asText());
        JsonNode topic=saved.path("coverage").path("semanticTopics").get(0),packet=topic.path("packetAudits").get(0);
        assertEquals("partial",topic.path("aggregateReviewStatus").asText());assertFalse(topic.path("semanticScopeVerified").asBoolean());
        assertEquals("provider_estimated_fit",packet.path("inputBudgetStatus").asText());
        assertEquals(queued.path("modelIdentity").path("configurationSha256").asText(),packet.path("inputBudgetMetadata").path("observation").path("providerEstimate").path("profileSha256").asText());
        assertEquals(3,wire.endpoints.size());assertEquals(1,wire.endpoints.stream().filter(x->x.endsWith("/v1/responses")).count());
        for(String body:wire.bodies)assertEquals("none",JsonUtils.parse(body).path("reasoning").path("effort").asText());
        assertFalse(saved.toString().contains("RESPONSES_FAKE_ONLY"));verifyNoInteractions(local,miniMax);
    }
    @Test void queuedOperationSurvivesWireConfigChangeAndAnotherProfileWhileActiveReuseKeepsIdentity()throws Exception {
        when(local.available()).thenReturn(true);when(local.chatModel()).thenReturn("qwen2.5:14b");when(local.chatStructured(anyList(),any())).thenReturn("[]");
        String first=project(),second=project();upload(first);upload(second);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);wire.entered=entered;wire.release=release;
        JsonNode queued=postData(first,"minimax-cn");
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));props.getVetting().setMinimaxCnWire("chat-completions");
            JsonNode reused=postData(first,"local");assertEquals(queued.path("id"),reused.path("id"));assertEquals(queued.path("modelIdentity"),reused.path("modelIdentity"));
            JsonNode other=postData(second,"local");assertEquals("local",other.path("modelIdentity").path("profileId").asText());release.countDown();
            JsonNode saved=await(first,queued.path("id").asText()),next=await(second,other.path("id").asText());
            assertEquals("COMPLETED",saved.path("status").asText());assertEquals(queued.path("modelIdentity"),saved.path("result").path("modelIdentity"));
            assertEquals("COMPLETED",next.path("status").asText());assertEquals("local",next.path("result").path("modelIdentity").path("profileId").asText());
            assertEquals(1,wire.endpoints.stream().filter(x->x.endsWith("/v1/responses")).count());verify(local,times(1)).chatStructured(anyList(),any());verifyNoInteractions(miniMax);
        }finally{release.countDown();}
    }
    @Test void counterOverBudgetPersistsUnsubmittedPacketWithoutGenerationOrChatFallback()throws Exception {
        wire.count=262144;String id=project();upload(id);JsonNode queued=postData(id,"minimax-cn"),saved=await(id,queued.path("id").asText());
        JsonNode packet=saved.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0);
        assertEquals("over_budget",packet.path("inputBudgetStatus").asText());assertFalse(packet.path("actualGatewayCallStarted").asBoolean());
        assertTrue(wire.endpoints.stream().noneMatch(x->x.endsWith("/v1/responses")));verifyNoInteractions(local,miniMax);
    }
    @Test void incompleteGenerationPersistsFailedRunAndTypedPacketWithoutRepairOrFallback()throws Exception {
        wire.generation="{\"object\":\"response\",\"model\":\"MiniMax-M3\",\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}";
        String id=project();upload(id);JsonNode queued=postData(id,"minimax-cn"),saved=await(id,queued.path("id").asText());
        assertEquals("FAILED",saved.path("status").asText());assertEquals(queued.path("modelIdentity"),saved.path("modelIdentity"));
        assertEquals("output_budget_exhausted",saved.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).path("failureKind").asText());
        assertEquals(1,wire.endpoints.stream().filter(x->x.endsWith("/v1/responses")).count());verifyNoInteractions(local,miniMax);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"[]}","[{}]"})
    void malformedFirstResponseRetriesIdenticalWireWithDurableAttempts(String invalid)throws Exception {
        wire.generationContents.add(invalid);wire.generationContents.add("[]");
        String id=project();upload(id);JsonNode queued=postData(id,"minimax-cn"),saved=await(id,queued.path("id").asText());
        assertEquals("COMPLETED",saved.path("status").asText());
        JsonNode packet=saved.path("reliablePackets").get(0).path("packet"),attempts=packet.path("attempts");
        assertEquals(2,attempts.size());assertEquals("structured_output_invalid",attempts.get(0).path("status").asText());
        assertEquals(invalid,attempts.get(0).path("rawResponse").asText());assertEquals(VettingCorpus.hash(invalid),attempts.get(0).path("rawResponseSha256").asText());
        assertEquals("decoded",attempts.get(1).path("status").asText());assertEquals(VettingCorpus.hash("[]"),packet.path("rawResponseSha256").asText());
        assertEquals(attempts.get(0).path("input"),attempts.get(1).path("input"));
        assertEquals("provider_estimated_fit",attempts.get(1).path("inputBudget").path("status").asText());
        assertEquals(2,wire.endpoints.stream().filter(x->x.endsWith("/v1/responses")).count());
        assertEquals(3,wire.endpoints.stream().filter(x->x.endsWith("/input_tokens")).count());
        List<String> generationBodies=new ArrayList<>();for(int i=0;i<wire.endpoints.size();i++)if(wire.endpoints.get(i).endsWith("/v1/responses"))generationBodies.add(wire.bodies.get(i));
        assertEquals(generationBodies.get(0),generationBodies.get(1));
        assertEquals(1,saved.path("coverage").path("semanticTopics").size());assertEquals(3,saved.path("totalUnits").asInt());
        verifyNoInteractions(local,miniMax);
    }
    @Test void twoMalformedResponsesNeverConsumeThirdAndKeepBothFailuresInSql()throws Exception {
        wire.generationContents.add("[]}");wire.generationContents.add("[{}]");wire.generationContents.add("[]");
        String id=project();upload(id);JsonNode queued=postData(id,"minimax-cn"),saved=await(id,queued.path("id").asText());
        assertEquals("FAILED",saved.path("status").asText());assertEquals(0,saved.path("reliablePackets").size());
        JsonNode attempts=saved.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).path("attempts");
        assertEquals(2,attempts.size());assertEquals("[]}",attempts.get(0).path("rawResponse").asText());assertEquals("[{}]",attempts.get(1).path("rawResponse").asText());
        assertEquals(2,wire.endpoints.stream().filter(x->x.endsWith("/v1/responses")).count());assertEquals(1,wire.generationContents.size());
        for(String format:Arrays.asList("json","pdf","docx"))assertNotEquals(0,JsonUtils.parse(mvc.perform(get("/api/vetting/"+id+"/report."+format)).andReturn().getResponse().getContentAsString()).path("code").asInt());
    }
    @Test void budgetDriftAfterMalformedResponseStopsBeforeRetryGeneration()throws Exception {
        wire.generationContents.add("[]}");wire.generationContents.add("[]");wire.generationHook=()->wire.count=262144;
        String id=project();upload(id);JsonNode queued=postData(id,"minimax-cn"),saved=await(id,queued.path("id").asText());
        assertEquals("FAILED",saved.path("status").asText());assertEquals(1,wire.endpoints.stream().filter(x->x.endsWith("/v1/responses")).count());
        assertEquals(1,wire.generationContents.size());
        JsonNode attempts=saved.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).path("attempts");assertEquals(1,attempts.size());assertEquals("[]}",attempts.get(0).path("rawResponse").asText());
        JsonNode packet=saved.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0);assertEquals("over_budget",packet.path("inputBudgetStatus").asText());assertEquals("input_context_over_budget",packet.path("failureKind").asText());assertEquals(262144,packet.path("inputBudgetMetadata").path("observation").path("inputTokens").asInt());
    }
    String project()throws Exception {String id="native-"+UUID.randomUUID();mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY\"}")).andExpect(jsonPath("$.code").value(0));return id;}
    void upload(String id)throws Exception {byte[] bytes;try(XWPFDocument d=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){d.createParagraph().createRun().setText("NTT 1.1 The minimum amount for interim payment shall be twenty dollars.");d.write(out);bytes=out.toByteArray();}mvc.perform(multipart("/api/vetting/{id}/package/upload",id).file(new MockMultipartFile("files","NTT.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes))).andExpect(jsonPath("$.data.parsed").value(1));}
    JsonNode postData(String project,String profile)throws Exception {return JsonUtils.parse(mvc.perform(post("/api/vetting/{id}/runs",project).header("X-ConSense-Llm-Profile",profile)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    JsonNode await(String project,String run)throws Exception {for(int i=0;i<200;i++){JsonNode job=JsonUtils.parse(mvc.perform(get("/api/vetting/{id}/runs/{run}",project,run)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");if(Arrays.asList("COMPLETED","FAILED").contains(job.path("status").asText()))return job;Thread.sleep(20);}throw new AssertionError("bounded fixture run did not finish");}
}
