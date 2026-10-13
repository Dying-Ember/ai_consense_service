package com.consense.web;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.repository.EvidenceChunkRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.OerBusinessEvidence;
import com.consense.service.vetting.OerCanonicalRetrievalClient;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.VettingSourceMetadataWire;
import com.consense.service.advice.AdviceService;
import com.consense.service.advice.AdviceModelProperties;
import com.consense.service.advice.AdviceResponsesTransport;
import com.consense.config.ConsenseProperties;
import com.consense.service.sources.NativeCanonicalFixture;
import com.consense.service.sources.NativeCanonicalContract;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Advisory HTTP/catalog/history with the shared canonical seam and neural LLM doubled.
 * Private Session/IndexState constructors build authored test carriers only; this is not a seal/parser acceptance test. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.llm.chat-model=canonical-test-chat",
        "consense.llm.num-ctx=262144","consense.llm.structured-thinking=false",
        "consense.minimax-cn.api-key=AUTHORED-responses-no-real-key","consense.minimax-cn.base-url=https://api.minimax.cn",
        "consense.minimax-cn.chat-model=MiniMax-M3","consense.minimax-cn.temperature=0.2",
        "consense.minimax-cn.structured-max-tokens=16384","consense.minimax-cn.structured-open-ai-thinking-mode=disabled"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class AdviceCanonicalIntegrationTest {
    private static final String DB=UUID.randomUUID().toString(),SHA=VettingCorpus.hash("AUTHORED TEST ONLY");
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:h2:mem:advice_canonical_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","advice-canonical-tests",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired SourceDocumentRepository documents;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdviceService advice;
    @Autowired ConsenseProperties deployment;
    @Autowired AdviceModelProperties adviceModel;
    @Autowired AdviceResponsesTransport originalResponses;
    @MockBean OerBusinessEvidence canonical;
    @MockBean VectorStore vectors;
    @MockBean EvidenceChunkRepository legacyChunks;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMaxChat;
    @MockBean com.consense.service.vetting.VettingTokenBudgetCliObserver counter;
    private final List<String> prompts=new ArrayList<>();
    private List<VectorStore.SearchHit> selectedHits,contextHits;
    private OerBusinessEvidence.Session loaded;

    @BeforeEach void doubles() throws Exception {
        com.consense.service.advice.AdviceBudgetFixture.bind(counter,local);
        prompts.clear();selectedHits=new ArrayList<>();contextHits=new ArrayList<>();loaded=null;
        adviceModel.setMinimaxWire("chat");
        ReflectionTestUtils.setField(advice,"adviceResponses",originalResponses);
        when(canonical.enabled()).thenReturn(true);
        when(canonical.load(anyString(),eq("advisory"),anyList())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Source load cannot inherit a remote-operation transaction");
            loaded=session(call.getArgument(0),call.getArgument(2));return loaded;
        });
        when(canonical.indexStatus(any())).thenAnswer(call->indexState(true,SHA,((OerBusinessEvidence.Session)call.getArgument(0)).chunkCount(),null));
        // Current scalar status seam replaces the historical load/indexStatus pair; authored carrier only.
        when(canonical.currentIndexStatus(anyString(),eq("advisory"),any())).thenAnswer(call->{
            java.util.function.Supplier<List<SourceDocument>> reader=call.getArgument(2);
            OerBusinessEvidence.Session s=canonical.load(call.getArgument(0),"advisory",reader.get());
            Constructor<OerBusinessEvidence.CurrentIndexStatus> c=OerBusinessEvidence.CurrentIndexStatus.class.getDeclaredConstructor(OerBusinessEvidence.Session.class,OerCanonicalRetrievalClient.IndexState.class);
            c.setAccessible(true);return c.newInstance(s,canonical.indexStatus(s));
        });
        when(canonical.indexStatusSelected(any(),anyList())).thenAnswer(call->canonical.indexStatus(call.getArgument(0)));

        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return null;}).when(canonical).ensureIndex(any());
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return null;}).when(canonical).index(any());
        when(canonical.adviceHits(any(),anyString())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return selectedHits;});
        when(canonical.sourceHits(any(),anyList())).thenAnswer(call->contextHits);
        doAnswer(call->{OerBusinessEvidence.Session s=call.getArgument(0);List<SourceDocument> fresh=call.getArgument(1);
            if(!s.documentIds.equals(ids(fresh)))throw new IllegalStateException("AUTHORED SOURCE SET CHANGED");
            for(SourceDocument d:fresh)if(s.chunks().stream().noneMatch(c->String.valueOf(d.getId()).equals(c.getDocumentId())&&Objects.equals(d.getTextContent(),c.getContent())))throw new IllegalStateException("AUTHORED SOURCE REVISION CHANGED");
            return null;
        }).when(canonical).assertSelected(any(),anyList());
        doAnswer(call->{canonical.assertSelected(call.getArgument(0),call.getArgument(1));return null;}).when(canonical).assertSelectedScope(any(),anyList());

        when(local.available()).thenReturn(true);when(local.chatModel()).thenReturn("canonical-test-chat");
        when(local.chat(anyList())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"LLM must run outside the history write transaction");
            prompts.add(JsonUtils.write(call.getArgument(0)));
            return "{\"grounded\":true,\"answer\":\"AUTHORED supported answer\",\"citations\":[\"[1]\"]}";
        });
    }

    @Test void scopeIsLoadedBeforeIndexingAndLegacyAdaptersAreNeverUsed() throws Exception {
        String p=project();SourceDocument ntt=source(p,"NTT","project NTT.txt","tender","NTT TEST"),scc=source(p,"SCC","project SCC.txt","tender","SCC TEST");
        source(p,"NTT","standard template.txt","tender","TEMPLATE TEST","STANDARD_TEMPLATE");
        source(p,"BQ","Bills of Quantities.txt","tender","PRICING TEST");
        mvc.perform(post("/api/advice/{p}/index/rebuild",p).param("scope","contract")).andExpect(jsonPath("$.code").value(0));
        assertEquals(Collections.singletonList(scc.getId()),loaded.documentIds);String contractKey=loaded.indexKey;
        mvc.perform(post("/api/advice/{p}/index/rebuild",p).param("scope","tender")).andExpect(jsonPath("$.code").value(0));
        assertEquals(Collections.singletonList(ntt.getId()),loaded.documentIds);assertNotEquals(contractKey,loaded.indexKey);
        mvc.perform(get("/api/advice/{p}/index/status",p)).andExpect(jsonPath("$.data.sourceScope").value("fullset"));
        assertEquals(2,loaded.documentIds.size());verifyNoInteractions(vectors,legacyChunks);verify(local,never()).embed(anyList());
    }

    @Test void receiptAndHealthFailureStatusDoesNotExposeExternalExceptionOrConsultQdrant() throws Exception {
        String p=project();source(p,"SCC","SCC.txt","tender","TEST");
        doReturn(indexState(false,null,1,"Authorization Bearer TEST CANARY")).when(canonical).indexStatus(any());
        JsonNode data=data(get("/api/advice/{p}/index/status",p).param("scope","contract"));
        assertFalse(data.path("vectorReady").asBoolean());assertFalse(data.path("embeddingReady").asBoolean());assertEquals(0,data.path("indexed").asInt());
        assertEquals("canonical_index_unavailable",data.path("indexReason").asText());assertFalse(data.toString().contains("TEST CANARY"));
        verifyNoInteractions(vectors,legacyChunks);verify(local,never()).embed(anyList());
    }

    @Test void missingCurrentBindingNeverFallsBackAndSavesNoChatMessages() throws Exception {
        String p=project();source(p,"SCC","SCC.txt","tender","TEST");
        when(canonical.load(anyString(),eq("advisory"),anyList())).thenThrow(new IllegalStateException("unregistered"));
        mvc.perform(ask(p,"contract")).andExpect(jsonPath("$.code").value(4204));
        assertEquals(0,data(get("/api/advice/{p}/messages",p)).size());verify(local,never()).chat(anyList());verifyNoInteractions(vectors,legacyChunks);
    }

    @Test void latestFailedSourceIsRetainedInCanonicalSelectionRatherThanSilentlyOmitted() throws Exception {
        String p=project();source(p,"SCC","SCC.txt","tender","old usable");SourceDocument latest=source(p,"SCC","SCC.txt","tender","");
        latest.setParseStatus("FAILED");documents.saveAndFlush(latest);
        mvc.perform(get("/api/advice/{p}/index/status",p).param("scope","contract")).andExpect(jsonPath("$.code").value(0));
        assertEquals(Collections.singletonList(latest.getId()),loaded.documentIds);
    }

    @Test void completeMembersPointersUnknownsAndScoreMeaningSurviveAnswerAndHistory() throws Exception {
        String p=project();SourceDocument s=source(p,"SCC","SCC.txt","tender","JAVA PARSE TEST");
        selectedHits=Collections.singletonList(hit(s,"tender","[parent]\n[table on page 2]","complete_observed_unit",null,"ranked"));
        JsonNode answer=data(ask(p,"contract"));assertTrue(answer.path("grounded").asBoolean());
        JsonNode citation=answer.path("sources").get(0);assertEquals(2,citation.path("canonicalEvidence").size());
        assertEquals("unknown",citation.path("canonicalSourceUnit").path("qualifiersComplete").asText());
        assertFalse(citation.path("canonicalSourceUnit").path("semanticScopeVerified").asBoolean());
        assertEquals("actual_reranked_seed",citation.path("scoreMeaning").asText());
        assertTrue(String.join("\n",prompts).contains("[table on page 2]"));assertTrue(String.join("\n",prompts).contains("AUTHORED unknown relationship"));
        s.setTextContent("NEW VERSION MUST NOT REPLACE HISTORY");documents.saveAndFlush(s);
        JsonNode history=data(get("/api/advice/{p}/messages",p));assertEquals(answer.path("sources"),history.get(1).path("sources"));
        assertFalse(history.get(1).path("sources").toString().contains("NEW VERSION"));verifyNoInteractions(vectors,legacyChunks);
    }

    @Test void standardAdoptionContextComesFromTheSameSessionAndIsUnranked() throws Exception {
        String p=project();SourceDocument library=source(p,"SL","Specification Library SL2022.txt","standard","LIBRARY"),composition=source(p,"SP","24 - The Specification.txt","tender","Specification Library SL2022 adopted except PRE");
        selectedHits=Collections.singletonList(hit(library,"standard","LIBRARY CLAUSE TEST","complete_observed_unit",null,"ranked"));
        contextHits=Collections.singletonList(hit(composition,"tender","Specification Library SL2022 adopted except PRE","complete_observed_unit",null,"context"));
        when(local.chat(anyList())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());prompts.add(JsonUtils.write(call.getArgument(0)));return "{\"grounded\":true,\"answer\":\"TEST adopted\",\"citations\":[\"[1]\",\"[2]\"]}";});
        JsonNode answer=data(ask(p,"fullset"));assertTrue(answer.path("grounded").asBoolean());
        assertTrue(answer.path("sources").get(0).path("applicabilityContextComplete").asBoolean());
        assertEquals("unranked_context",answer.path("sources").get(1).path("scoreMeaning").asText());
        verify(canonical).sourceHits(eq(loaded),eq(Collections.singletonList("chunk-"+composition.getId())));verifyNoInteractions(legacyChunks,vectors);
    }

    @Test void missingAdoptionOriginsAndUnknownUnitCannotEstablishProjectApplicability() throws Exception {
        String p=project();SourceDocument library=source(p,"SL","Specification Library SL2022.txt","standard","LIBRARY");
        selectedHits=Collections.singletonList(hit(library,"standard","LIBRARY CLAUSE TEST","complete_observed_unit",null,"ranked"));
        JsonNode missing=data(ask(p,"fullset"));assertFalse(missing.path("grounded").asBoolean());assertFalse(missing.path("sources").get(0).path("applicabilityContextComplete").asBoolean());
        selectedHits=Collections.singletonList(hit(library,"standard","VISIBLE BUT INCOMPLETE","unknown","observed_block_start_or_gap_unknown","ranked"));
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST\",\"citations\":[\"[1]\"],\"basisScope\":\"reference_only\"}");
        JsonNode uncertain=data(ask(p,"fullset"));assertFalse(uncertain.path("grounded").asBoolean());assertEquals("unknown",uncertain.path("sources").get(0).path("canonicalSourceUnit").path("status").asText());
    }

    @Test void missingOrPartialResolvedContextCannotPassTheAdoptionGate() throws Exception {
        String p=project();SourceDocument library=source(p,"SL","Specification Library SL2022.txt","standard","LIBRARY");
        SourceDocument first=source(p,"SP","The Specification part one.txt","tender","Specification Library adopted with exceptions");
        source(p,"SP","The Specification part two.txt","tender","Specification Library edition and amendments");
        selectedHits=Collections.singletonList(hit(library,"standard","LIBRARY","complete_observed_unit",null,"ranked"));
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST\",\"citations\":[\"[1]\",\"[2]\"]}");
        JsonNode empty=data(ask(p,"fullset"));assertFalse(empty.path("grounded").asBoolean());assertFalse(empty.path("sources").get(0).path("applicabilityContextComplete").asBoolean());
        contextHits=Collections.singletonList(hit(first,"tender",first.getTextContent(),"complete_observed_unit",null,"context"));
        JsonNode partial=data(ask(p,"fullset"));assertFalse(partial.path("grounded").asBoolean());assertFalse(partial.path("sources").get(0).path("applicabilityContextComplete").asBoolean());
    }

    @Test void referenceOnlyNeedsAnActualStandardCitation() throws Exception {
        String p=project();SourceDocument fact=source(p,"OTHER","project meeting.txt","project_fact","BACKGROUND TEST");
        selectedHits=Collections.singletonList(hit(fact,"project_fact","BACKGROUND TEST","complete_observed_unit",null,"ranked"));
        when(local.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"TEST\",\"citations\":[\"[1]\"],\"basisScope\":\"reference_only\"}");
        assertFalse(data(ask(p,"fullset")).path("grounded").asBoolean());
        SourceDocument library=source(p,"SL","Specification Library SL2022.txt","standard","LIBRARY TEST");
        selectedHits=Collections.singletonList(hit(library,"standard","LIBRARY TEST","complete_observed_unit",null,"ranked"));
        assertTrue(data(ask(p,"fullset")).path("grounded").asBoolean());
    }

    @Test void anOversizedContextUnitIsOmittedAsAWholeAndCannotPassTheAdoptionGate() throws Exception {
        String p=project();SourceDocument library=source(p,"SL","Specification Library SL2022.txt","standard","LIBRARY"),composition=source(p,"SP","24 - The Specification.txt","tender","Specification Library adoption");
        selectedHits=Collections.singletonList(hit(library,"standard","LIBRARY","complete_observed_unit",null,"ranked"));
        StringBuilder text=new StringBuilder("Specification Library ");while(text.length()<13000)text.append("COMPLETE UNIT TEST ");
        contextHits=Collections.singletonList(hit(composition,"tender",text.toString(),"complete_observed_unit",null,"context"));
        JsonNode response=data(ask(p,"fullset"));assertFalse(response.path("grounded").asBoolean());assertEquals(1,response.path("sources").size());assertFalse(String.join("\n",prompts).contains("COMPLETE UNIT TEST"));
    }

    @Test void sourceAddedDuringChatRejectsTheOldAnswerAndDoesNotSaveEitherMessage() throws Exception {
        String p=project();SourceDocument original=source(p,"SCC","SCC.txt","tender","TEST");
        selectedHits=Collections.singletonList(hit(original,"tender","TEST","complete_observed_unit",null,"ranked"));
        when(local.chat(anyList())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());source(p,"PRE","new PRE.txt","tender","NEW CURRENT SOURCE");return "{\"grounded\":true,\"answer\":\"STALE\",\"citations\":[\"[1]\"]}";});
        mvc.perform(ask(p,"contract")).andExpect(jsonPath("$.code").value(4204));assertEquals(0,data(get("/api/advice/{p}/messages",p)).size());
    }

    @Test void aRemoteModelFailureLeavesNoOrphanUserMessage() throws Exception {
        String p=project();SourceDocument original=source(p,"SCC","SCC.txt","tender","TEST");
        selectedHits=Collections.singletonList(hit(original,"tender","TEST","complete_observed_unit",null,"ranked"));
        when(local.chat(anyList())).thenThrow(new IllegalStateException("APIKEY TEST ONLY"));
        mvc.perform(ask(p,"contract")).andExpect(jsonPath("$.code").value(4203));assertEquals(0,data(get("/api/advice/{p}/messages",p)).size());
    }

    @Test void directDatabaseRevisionDuringChatCannotBeHiddenByTheRequestEntityCache() throws Exception {
        String p=project();SourceDocument original=source(p,"SCC","SCC.txt","tender","BEFORE CHAT");
        selectedHits=Collections.singletonList(hit(original,"tender","BEFORE CHAT","complete_observed_unit",null,"ranked"));
        when(local.chat(anyList())).thenAnswer(call->{
            jdbc.update("update source_document set text_content=? where id=?","NEW DATABASE REVISION",original.getId());
            return "{\"grounded\":true,\"answer\":\"OLD\",\"citations\":[\"[1]\"]}";
        });
        mvc.perform(ask(p,"contract")).andExpect(jsonPath("$.code").value(4204));assertEquals(0,data(get("/api/advice/{p}/messages",p)).size());
    }

    @Test void actualNativePointersRenderAccuratePartsAndUnavailableVisualsWithoutGuessingPdfPage() throws Exception {
        ObjectNode snapshot=NativeCanonicalFixture.snapshot();CanonicalSourceBinding binding=new CanonicalSourceBinding();binding.setProjectId("AUTHORED-native");binding.setDocumentId(42L);
        binding.setOriginalSourceSha256(snapshot.path("source").path("sourceSha256").asText());binding.setSourceRevisionSha256(snapshot.path("source").path("sourceRevisionSha256").asText());
        binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());binding.setProjectionSha256(snapshot.path("projectionSha256").asText());binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());binding.setJavaParsedSourceHash(SHA);
        JsonNode chunk=snapshot.path("chunks").get(1);EvidencePointer pointer=NativeCanonicalFixture.pointer(snapshot,chunk,binding,"advisory");
        ObjectNode unit=JsonUtils.mapper().createObjectNode();unit.put("status","complete_observed_unit");unit.put("qualifiersComplete","unknown");unit.put("semanticScopeVerified",false);
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("fileLabel","PRE · authored.docx");payload.put("documentId","42");payload.put("sourceRole","tender");payload.put("sourceHash",SHA);
        payload.put("pageNo","");payload.put("anchor",chunk.path("anchor").asText());payload.put("content",chunk.path("content").asText());payload.put("canonicalSourceUnit",unit);
        payload.put("canonicalEvidence",Collections.singletonList(pointer));payload.put("scoreKind","actual_reranked_seed");
        java.lang.reflect.Method render=AdviceService.class.getDeclaredMethod("renderExcerpts",List.class);render.setAccessible(true);
        String text=(String)render.invoke(advice,Collections.singletonList(new VectorStore.SearchHit(chunk.path("id").asText(),0.9,payload)));
        assertTrue(text.contains("original physical pages and PDF boxes are not defined"));assertTrue(text.contains("word/document.xml"));String marker="\nSelected canonical model material (untrusted observations):\n";
        JsonNode decoded=VettingSourceMetadataWire.decode(text.substring(text.lastIndexOf(marker)+marker.length()).trim());
        JsonNode selected=decoded.path("materialDictionary").get(0);
        assertEquals(pointer.getParts().get(0).path("textTransform").path("textChannel"),selected.path("parts").get(0).path("textTransform").path("textChannel"));
        assertEquals("nonDeletedNonDirectStrike",selected.path("parts").get(0).path("textTransform").path("textChannel").asText());
        assertEquals(pointer.getAdoption().path("textChannel"),selected.path("adoption").path("textChannel"));
        assertTrue(text.contains(NativeCanonicalContract.VISUAL));assertFalse(text.contains("physicalPage=P0"));assertTrue(text.contains("inner text 😀"));verify(local,never()).chat(anyList());
    }
    /** Synthetic Responses wire double; all real model adapters stay unused. */
    private final class ResponsesHttp implements AdviceResponsesTransport.WireHttp {
        final List<String> endpoints=new ArrayList<>(),bodies=new ArrayList<>();
        final Deque<String> generations=new ArrayDeque<>();
        Runnable afterCounter,afterGeneration;
        public AdviceResponsesTransport.Response post(String endpoint,String body,long timeout,int cap,String auth){
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("Bearer AUTHORED-responses-no-real-key",auth);endpoints.add(endpoint);bodies.add(body);
            if(endpoint.equals("https://api.minimax.cn/v1/responses/input_tokens")){
                if(afterCounter!=null)afterCounter.run();
                return new AdviceResponsesTransport.Response(200,"{\"object\":\"response.input_tokens\",\"input_tokens\":1000}");
            }
            assertEquals("https://api.minimax.cn/v1/responses",endpoint);
            if(afterGeneration!=null)afterGeneration.run();
            String answer=generations.isEmpty()?"{\"grounded\":true,\"answer\":\"AUTHORED supported Responses answer\",\"citations\":[\"[1]\"]}":generations.removeFirst();
            return new AdviceResponsesTransport.Response(200,"{\"object\":\"response\",\"model\":\"MiniMax-M3\",\"status\":\"completed\",\"truncation\":\"disabled\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":"+JsonUtils.write(answer)+"}]}]}");
        }
    }
    private ResponsesHttp responses(long context){
        adviceModel.setMinimaxWire("responses");ConsenseProperties.Responses cfg=adviceModel.getResponses();
        cfg.setEnabled(true);cfg.setConfiguredContextTokens(context);cfg.setSafetyMarginTokens(1024);cfg.setEstimatePolicy(AdviceResponsesTransport.POLICY);
        ResponsesHttp http=new ResponsesHttp();ReflectionTestUtils.setField(advice,"adviceResponses",new AdviceResponsesTransport(deployment,adviceModel,http));return http;
    }
    private MockHttpServletRequestBuilder responsesAsk(String p){return ask(p,"contract").header("X-ConSense-Llm-Profile","minimax-cn");}
    @Test void responsesSourceAddedDuringCounterKeepsSourceErrorAndSavesNoExchange() throws Exception {
        String p=project();SourceDocument d=source(p,"SCC","SCC.txt","tender","TEST");selectedHits=Collections.singletonList(hit(d,"tender","TEST","complete_observed_unit",null,"ranked"));
        ResponsesHttp http=responses(262144);http.afterCounter=()->source(p,"PRE","new PRE.txt","tender","NEW CURRENT SOURCE");
        mvc.perform(responsesAsk(p)).andExpect(jsonPath("$.code").value(4204));assertEquals(1,http.bodies.size());assertEquals(0,data(get("/api/advice/{p}/messages",p)).size());verifyNoInteractions(miniMaxChat);verify(local,never()).chat(anyList());
    }
    @Test void responsesSourceRevisionDuringGenerationKeepsSourceErrorAndSavesNoExchange() throws Exception {
        String p=project();SourceDocument d=source(p,"SCC","SCC.txt","tender","TEST");selectedHits=Collections.singletonList(hit(d,"tender","TEST","complete_observed_unit",null,"ranked"));
        ResponsesHttp http=responses(262144);http.afterGeneration=()->jdbc.update("update source_document set text_content=? where id=?","NEW DATABASE REVISION",d.getId());
        mvc.perform(responsesAsk(p)).andExpect(jsonPath("$.code").value(4204));assertEquals(2,http.bodies.size());assertEquals(0,data(get("/api/advice/{p}/messages",p)).size());verifyNoInteractions(miniMaxChat);verify(local,never()).chat(anyList());
    }
    @Test void responsesUnknownBudgetDoesNotDispatchOrCreateOrphanMessages() throws Exception {
        String p=project();SourceDocument d=source(p,"SCC","SCC.txt","tender","TEST");selectedHits=Collections.singletonList(hit(d,"tender","TEST","complete_observed_unit",null,"ranked"));ResponsesHttp http=responses(0);
        mvc.perform(responsesAsk(p)).andExpect(jsonPath("$.code").value(4205));assertTrue(http.bodies.isEmpty());assertEquals(0,data(get("/api/advice/{p}/messages",p)).size());verifyNoInteractions(miniMaxChat);verify(local,never()).chat(anyList());
    }
    @Test void bareResponsesAskWithSub256KContextCannotDispatchCounterOrGeneration() throws Exception {
        String p=project();SourceDocument d=source(p,"SCC","SCC.txt","tender","TEST");selectedHits=Collections.singletonList(hit(d,"tender","TEST","complete_observed_unit",null,"ranked"));ResponsesHttp http=responses(32768);
        mvc.perform(responsesAsk(p)).andExpect(jsonPath("$.code").value(4205)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("256K")));
        assertTrue(http.bodies.isEmpty());assertEquals(0,data(get("/api/advice/{p}/messages",p)).size());verifyNoInteractions(miniMaxChat);verify(local,never()).chatStructured(anyList(),any());
    }
    @Test void responsesCompletionUsesSameProjectionAndReportsItsRealRouteIdentity() throws Exception {
        String p=project();SourceDocument d=source(p,"SCC","SCC.txt","tender","COMPLETE RESPONSE MATERIAL");selectedHits=Collections.singletonList(hit(d,"tender",d.getTextContent(),"complete_observed_unit",null,"ranked"));ResponsesHttp http=responses(262144);
        JsonNode result=data(responsesAsk(p));assertTrue(result.path("grounded").asBoolean());assertEquals("MiniMax-M3",result.path("model").asText());assertEquals(AdviceResponsesTransport.WIRE,result.path("modelIdentity").path("provider").asText());
        assertEquals(2,http.bodies.size());JsonNode counter=JsonUtils.parse(http.bodies.get(0)),generation=JsonUtils.parse(http.bodies.get(1));for(String field:Arrays.asList("model","instructions","input","reasoning"))assertEquals(counter.get(field),generation.get(field));
        String instructions=counter.path("instructions").asText();assertTrue(instructions.contains("```mermaid")&&instructions.contains("flowchart TD or flowchart LR")&&instructions.contains("double-quoted labels for every node and decision")&&instructions.contains("#quot;")&&instructions.contains("evidence-supported steps and citations")&&instructions.contains("no HTML, links, directives or styling"),"The final counted and generated system input constrains Mermaid syntax without adding unsupported steps");
        assertTrue(generation.path("input").get(0).path("content").asText().contains("COMPLETE RESPONSE MATERIAL"));assertEquals(2,data(get("/api/advice/{p}/messages",p)).size());verifyNoInteractions(miniMaxChat);verify(local,never()).chat(anyList());
    }
    @Test void responsesMalformedJsonRetryRecountsCompleteMaterialsBeforeSecondGeneration() throws Exception {
        String p=project();SourceDocument d=source(p,"SCC","SCC.txt","tender","COMPLETE RETRY MATERIAL");selectedHits=Collections.singletonList(hit(d,"tender",d.getTextContent(),"complete_observed_unit",null,"ranked"));ResponsesHttp http=responses(262144);http.generations.add("not JSON");
        assertTrue(data(responsesAsk(p)).path("grounded").asBoolean());assertEquals(4,http.bodies.size());JsonNode first=JsonUtils.parse(http.bodies.get(1)),retryCounter=JsonUtils.parse(http.bodies.get(2)),retryGeneration=JsonUtils.parse(http.bodies.get(3));
        assertEquals(first.path("input").get(0).path("content").asText()+AdviceResponsesTransport.RETRY_INSTRUCTION,retryGeneration.path("input").get(0).path("content").asText());for(String field:Arrays.asList("model","instructions","input","reasoning"))assertEquals(retryCounter.get(field),retryGeneration.get(field));assertEquals(2,data(get("/api/advice/{p}/messages",p)).size());verifyNoInteractions(miniMaxChat);verify(local,never()).chat(anyList());
    }
    private String project() throws Exception {String p="canonical-advice-"+UUID.randomUUID();mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+p+"\",\"nameZhHans\":\"AUTHORED ADVICE TEST\"}")).andExpect(jsonPath("$.code").value(0));return p;}
    private SourceDocument source(String p,String key,String name,String role,String text){return source(p,key,name,role,text,"VETTING_PACKAGE");}
    private SourceDocument source(String p,String key,String name,String role,String text,String category){SourceDocument d=new SourceDocument();d.setProjectId(p);d.setCategory(category);d.setFileKey(key);d.setFileName(name);d.setReviewRole(role);d.setParseStatus("PARSED");d.setTextContent(text);d.setCreatedAt(Instant.now());return documents.saveAndFlush(d);}
    private MockHttpServletRequestBuilder ask(String p,String scope){return post("/api/advice/{p}/ask",p).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Explain the TEST ONLY evidence?\",\"scope\":\""+scope+"\"}");}
    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception{return JsonUtils.parse(mvc.perform(request).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    private List<Long> ids(List<SourceDocument> sources){List<Long> ids=new ArrayList<>();for(SourceDocument source:sources)ids.add(source.getId());return ids;}
    private OerBusinessEvidence.Session session(String p,List<SourceDocument> selected) throws Exception {
        List<CanonicalSourceBinding> bindings=new ArrayList<>();Map<String,VettingCorpus.Chunk> views=new LinkedHashMap<>();
        for(SourceDocument d:selected){CanonicalSourceBinding b=new CanonicalSourceBinding();b.setProjectId(p);b.setDocumentId(d.getId());b.setProjectionSha256(VettingCorpus.hash("TEST projection "+d.getId()));b.setSnapshotJson("{}");bindings.add(b);
            VettingCorpus.Chunk c=new VettingCorpus.Chunk();c.setId("chunk-"+d.getId());c.setDocumentId(String.valueOf(d.getId()));c.setFileName(d.getFileName());c.setRole(d.getReviewRole());c.setContent(d.getTextContent());views.put(c.getId(),c);}
        Constructor<OerBusinessEvidence.Session> constructor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);constructor.setAccessible(true);return constructor.newInstance(p,"advisory",ids(selected),bindings,views,Collections.emptyMap());
    }
    private OerCanonicalRetrievalClient.IndexState indexState(boolean ready,String signature,int count,String reason) throws Exception {Constructor<OerCanonicalRetrievalClient.IndexState> c=OerCanonicalRetrievalClient.IndexState.class.getDeclaredConstructor(boolean.class,String.class,int.class,String.class);c.setAccessible(true);return c.newInstance(ready,signature,count,reason);}
    private VectorStore.SearchHit hit(SourceDocument source,String role,String content,String status,String reason,String selection){
        ObjectNode unit=JsonUtils.mapper().createObjectNode();unit.put("unitId","unit-"+source.getId());unit.put("status",status);if(reason==null)unit.putNull("reason");else unit.put("reason",reason);unit.put("qualifiersComplete","unknown");unit.put("semanticScopeVerified",false);unit.putArray("requiredMemberIds").add("chunk-"+source.getId());unit.putArray("members");
        List<EvidencePointer> pointers=new ArrayList<>();for(int page=1;page<=2;page++){EvidencePointer pointer=new EvidencePointer();pointer.setProjectId(source.getProjectId());pointer.setDocumentId(source.getId());pointer.setUnitId("member-"+page);pointer.setModule("advisory");pointer.setOriginalSourceSha256(SHA);pointer.setCanonicalSourceHash(SHA);pointer.setProjectionSha256(SHA);pointer.setProjectionRevisionSha256(SHA);pointer.setPhysicalPages(Collections.singletonList(page));pointer.setProducerKind("native_docx");pointer.setParts(JsonUtils.mapper().createArrayNode());pointer.setSourceSpans(JsonUtils.mapper().createArrayNode());pointer.setRelations(JsonUtils.mapper().createArrayNode());
            ObjectNode unknown=JsonUtils.mapper().createObjectNode();unknown.put("unitId",pointer.getUnitId());unknown.putObject("attributes").putObject("relationship").put("status","unknown").put("reason","AUTHORED unknown relationship");pointer.setUnknowns(JsonUtils.mapper().createArrayNode().add(unknown));pointers.add(pointer);}
        // Pointers for one exact source carry its identical full unknown declaration, with local unit selection later.
        com.fasterxml.jackson.databind.node.ArrayNode allUnknowns=JsonUtils.mapper().createArrayNode();
        for(EvidencePointer pointer:pointers)allUnknowns.add(pointer.getUnknowns().get(0).deepCopy());
        for(EvidencePointer pointer:pointers)pointer.setUnknowns(allUnknowns.deepCopy());
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("projectId",source.getProjectId());payload.put("documentId",String.valueOf(source.getId()));payload.put("sourceHash",SHA);payload.put("sourceRole",role);payload.put("fileLabel",source.getFileKey()+" · "+source.getFileName());payload.put("pageNo","P1, P2");payload.put("anchor","AUTHORED native location");payload.put("content",content);payload.put("canonicalEvidence",pointers);payload.put("canonicalSourceUnit",unit);payload.put("scoreKind","context".equals(selection)?"unranked_context":"actual_reranked_seed");return new VectorStore.SearchHit("chunk-"+source.getId(),.75,payload);
    }
}
