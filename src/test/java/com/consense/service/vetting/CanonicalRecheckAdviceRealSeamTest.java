package com.consense.service.vetting;

import com.consense.ai.LlmClient;
import com.consense.common.*;
import com.consense.config.*;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.advice.AdviceService;
import com.consense.service.sources.*;
import com.consense.web.dto.AdviceDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic authored actual-Java/frozen-Python producer fixtures. Real Spring/JPA/source gates and
 * local fake registry/retrieval HTTP; all neural consumers are explicit doubles. No online services. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "spring.jpa.show-sql=false","consense.ocr.enabled=false","consense.vector.provider=memory",
        "consense.llm.chat-model=synthetic-recheck-chat","consense.llm.structured-thinking=false",
        "consense.llm.num-ctx=524288","logging.level.org.hibernate.SQL=OFF"})
@ActiveProfiles("h2")
class CanonicalRecheckAdviceRealSeamTest {
    static final String PROJECT="authored-recheck-project", POLICY=repeat('a'), INPUT="canonical-source-chunks-upstream-scope-and-parser-projection-v1";
    static final String SIGNATURE=VettingCorpus.hash("SYNTHETIC RECHECK INDEX FIXTURE");
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:recheck_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get(System.getProperty("maven.multiModuleProjectDirectory",System.getProperty("user.dir")),"target","authored-recheck-storage",DB).toAbsolutePath().toString());}
    @Autowired AdviceService advice;
    @Autowired ConsenseProperties props;
    @Autowired CanonicalEvidenceProperties canonicalProps;
    @Autowired OerCanonicalRetrievalClient canonicalRetrieval;
    @Autowired SourceDocumentRepository documents;
    @Autowired CanonicalSourceBindingRepository bindings;
    @Autowired ProjectRepository projects;
    @SpyBean ChatMessageRepository history;
    @Autowired JdbcTemplate jdbc;
    @SpyBean CanonicalEvidenceService evidence;
    @SpyBean CanonicalCurrentSourceReader reader;
    @SpyBean OerBusinessEvidence business;
    @MockBean(name="llmClient") LlmClient llm;
    @MockBean VettingTokenBudgetCliObserver tokenObserver;
    @TempDir Path temporary;
    HttpServer server;ExecutorService httpWorkers;
    final Map<Long,ObjectNode> snapshots=new LinkedHashMap<>();
    final Map<Long,String> rawSnapshots=new LinkedHashMap<>();
    final Map<Long,Path> originals=new LinkedHashMap<>();
    final Map<Long,Path> casTamperTargets=new LinkedHashMap<>();
    final Map<Long,JsonNode> casLedgers=new LinkedHashMap<>();
    final List<String> events=Collections.synchronizedList(new ArrayList<>());
    final Map<Long,AtomicInteger> perDocumentSnapshotReads=new ConcurrentHashMap<>();
    final AtomicReference<Runnable> healthMutation=new AtomicReference<>();
    final AtomicInteger healthReads=new AtomicInteger();int healthMutationOrdinal=1;
    final AtomicInteger snapshotReads=new AtomicInteger(),indexCalls=new AtomicInteger(),retrieveCalls=new AtomicInteger(),chatCalls=new AtomicInteger();
    final AtomicReference<Runnable> indexMutation=new AtomicReference<>(),retrieveMutation=new AtomicReference<>(),snapshotMutation=new AtomicReference<>(),chatMutation=new AtomicReference<>();
    final AtomicReference<JsonNode> indexRequest=new AtomicReference<>();
    final Map<String,ObjectNode> remoteIndexes=new ConcurrentHashMap<>();
    ObjectNode health;

    @BeforeEach void setup() throws Exception {
        when(tokenObserver.observe(any())).thenAnswer(call->VettingTokenFixture.observe(call.getArgument(0)));
        snapshots.clear();rawSnapshots.clear();originals.clear();casTamperTargets.clear();casLedgers.clear();events.clear();perDocumentSnapshotReads.clear();healthMutation.set(null);
        indexMutation.set(null);retrieveMutation.set(null);snapshotMutation.set(null);chatMutation.set(null);
        indexRequest.set(null);remoteIndexes.clear();canonicalProps.getSourcePageRenderEvidenceIdentities().clear();
        jdbc.update("DELETE FROM chat_message");jdbc.update("DELETE FROM canonical_source_binding");jdbc.update("DELETE FROM source_document");jdbc.update("DELETE FROM project");
        jdbc.execute("ALTER TABLE source_document ALTER COLUMN id RESTART WITH 42");jdbc.execute("ALTER TABLE chat_message ALTER COLUMN id RESTART WITH 1");
        props.setStorageRoot(temporary.toString());props.getVetting().setRequireHybridRetrieval(true);
        props.getVetting().setExpectedRetrievalContract(VettingRetrievalHealth.STRUCTURAL_CONTRACT);
        props.getVetting().setExpectedRetrievalSignatureVersion(4);props.getVetting().setExpectedRetrievalGranularity("dual-structure-v1");
        props.getVetting().setTopK(1);props.getVetting().setCandidateLimit(10);
        Project project=new Project();project.setId(PROJECT);project.setNameZhHans("AUTHORED synthetic source checks");projects.saveAndFlush(project);
        addFixture(42L,"pdf","GCC");addFixture(43L,"docx","PRE");
        health=healthFixture();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);httpWorkers=Executors.newFixedThreadPool(4);server.setExecutor(httpWorkers);
        server.createContext("/",exchange->{
            byte[] response;int status=200;String path=exchange.getRequestURI().getPath();
            try {
                if("/health".equals(path)){events.add("health:start");if(healthReads.incrementAndGet()==healthMutationOrdinal)runOnce(healthMutation);response=JsonUtils.write(health).getBytes(StandardCharsets.UTF_8);events.add("health:end");}
                else if(path.startsWith("/canonical/")){
                    snapshotReads.incrementAndGet();runOnce(snapshotMutation);
                    Long id=idForProjection(path.substring("/canonical/".length()));
                    perDocumentSnapshotReads.computeIfAbsent(id,key->new AtomicInteger()).incrementAndGet();events.add("cas:"+id+":start");
                    verifyActualCas(id);response=rawSnapshots.get(id).getBytes(StandardCharsets.UTF_8);events.add("cas:"+id+":end");
                }else if("/index-canonical".equals(path)){
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());indexCalls.incrementAndGet();
                    JsonNode request=JsonUtils.parse(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));
                    indexRequest.set(request.deepCopy());
                    events.add("index:start");runOnce(indexMutation);events.add("index:end");ObjectNode result=JsonUtils.mapper().createObjectNode();
                    result.put("projectId",request.path("projectId").asText());result.put("indexed",4);result.put("signature",SIGNATURE);
                    result.put("contract",VettingRetrievalHealth.STRUCTURAL_CONTRACT);result.put("targetModule","advisory");
                    result.set("canonicalProjections",request.path("projectionSha256").deepCopy());result.set("competitionScope",scope());
                    result.set("sourcePageEvidence",request.has("sourcePageEvidence")?request.path("sourcePageEvidence").deepCopy():JsonUtils.mapper().createObjectNode());
                    remoteIndexes.put(request.path("projectId").asText(),result.deepCopy());
                    response=JsonUtils.write(result).getBytes(StandardCharsets.UTF_8);
                }else if("/index-canonical/status".equals(path)){
                    JsonNode request=JsonUtils.parse(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));events.add("status:start");
                    ObjectNode stored=remoteIndexes.get(request.path("projectId").asText());
                    ObjectNode result=stored==null?JsonUtils.mapper().createObjectNode():stored.deepCopy();
                    result.put("ready",stored!=null);result.put("readOnly",true);result.put("vectorPoints",stored==null?0:4);
                    result.put("indexStatusContract","read-only-existing-canonical-index-v1");
                    response=JsonUtils.write(result).getBytes(StandardCharsets.UTF_8);events.add("status:end");
                }else if("/retrieve".equals(path)){
                    retrieveCalls.incrementAndGet();JsonNode request=JsonUtils.parse(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));
                    events.add("retrieve:start");runOnce(retrieveMutation);events.add("retrieve:end");response=JsonUtils.write(retrieveFixture(request.path("projectId").asText())).getBytes(StandardCharsets.UTF_8);
                }else{status=404;response="{\"synthetic\":\"unknown path\"}".getBytes(StandardCharsets.UTF_8);}
            }catch(Throwable failed){status=502;response=("{\"syntheticError\":\""+failed.getClass().getSimpleName()+"\"}").getBytes(StandardCharsets.UTF_8);}
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,response.length);
            try(OutputStream out=exchange.getResponseBody()){out.write(response);}
        });server.start();String url="http://127.0.0.1:"+server.getAddress().getPort();canonicalProps.setBaseUrl(url);canonicalProps.setTimeoutMs(30000);canonicalProps.getRetrievalBaseUrls().put("advisory",url);
        for(Long id:snapshots.keySet())register(id);
        assertTrue(AopUtils.isAopProxy(evidence));assertTrue(AopUtils.isAopProxy(reader));
        when(llm.available()).thenReturn(true);when(llm.chatModel()).thenReturn("synthetic-recheck-chat");
        doAnswer(call->{CanonicalSourceBinding binding=call.getArgument(0);events.add("source:"+binding.getDocumentId()+":start");Object result=call.callRealMethod();events.add("source:"+binding.getDocumentId()+":end");return result;}).when(reader).assertCurrent(any(),anyString());
        doAnswer(call->{events.add("selected-scope:start");Object result=call.callRealMethod();events.add("selected-scope:end");return result;}).when(business).assertSelected(any(),anyList());
        org.mockito.stubbing.Answer<?> repositoryDelegate=mockingDetails(history).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call->{ChatMessage message=call.getArgument(0);String event="persist-"+message.getRole();events.add(event+":start");Object result=repositoryDelegate.answer(call);events.add(event+":end");return result;}).when(history).save(any(ChatMessage.class));
        when(llm.chat(anyList())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());chatCalls.incrementAndGet();events.add("llm:start");runOnce(chatMutation);events.add("llm:end");return "{\"grounded\":true,\"answer\":\"AUTHORED supported literal\",\"citations\":[\"[1]\"]}";});
        when(llm.chatStructured(anyList(),any())).thenAnswer(call->llm.chat(call.getArgument(0)));
        resetCounters();
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);if(httpWorkers!=null)httpWorkers.shutdownNow();}

    @Test void successfulRebuildRecordsFullEquivalentResponseAndActualBoundaryCounts() throws Exception {
        long started=System.nanoTime();IndexStatusVO result=advice.rebuildIndex(PROJECT,"fullset");long wall=System.nanoTime()-started;
        assertTrue(result.isVectorReady());assertEquals(4,result.getIndexed());
        int rounds=(int)mockingDetails(evidence).getInvocations().stream().filter(i->i.getMethod().getName().equals("currentBindings")).count();
        int gates=(int)mockingDetails(reader).getInvocations().stream().filter(i->i.getMethod().getName().equals("assertCurrent")).count();
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("sourceKind","synthetic_authored_actual_java_frozen_python_producer");
        receipt.put("currentBindingsRounds",rounds);receipt.put("snapshotGetCount",snapshotReads.get());receipt.put("freshSourceGateCount",gates);
        receipt.put("indexCalls",indexCalls.get());receipt.put("retrieveCalls",retrieveCalls.get());receipt.put("neuralCalls",0);receipt.put("llmDoubleCalls",chatCalls.get());receipt.put("wallNanos",wall);
        receipt.put("latencyIsSynthetic",true);receipt.set("completeStatusResponse",JsonUtils.mapper().valueToTree(result));
        receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));receipt.set("perDocumentSnapshotReads",JsonUtils.mapper().valueToTree(snapshotCounts()));
        receipt.put("verifiedFrameRounds",mockingDetails(evidence).getInvocations().stream().filter(i->i.getMethod().getName().equals("verifiedBindings")).count());write("successful-rebuild.json",receipt);
        boolean optimized=Boolean.parseBoolean(System.getProperty("consense.recheck.expectOptimized","true"));
        boolean staged=Boolean.parseBoolean(System.getProperty("consense.recheck.expectStage","true"));
        assertEquals(staged?0:optimized?7:10,rounds,"real successful Advice chain currentBindings budget");
        assertEquals(staged?22:optimized?18:24,snapshotReads.get(),"service + client real local fake registry GET count");
        assertEquals(staged?78:optimized?32:66,gates,"actual fresh proxied reader source gate count");
        if(staged){assertEquals(11,snapshotCounts().get("42"));assertEquals(11,snapshotCounts().get("43"));assertEquals(3,receipt.path("verifiedFrameRounds").asInt());assertWaitGuards("health");assertWaitGuards("index");assertWaitGuards("status");}
        assertEquals(1,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        assertFalse(indexRequest.get().has("sourcePageEvidence"),"legacy unpinned request remains unchanged");
        String baseline=System.getProperty("consense.recheck.baselineResponse");
        if(baseline!=null){JsonNode old=JsonUtils.parse(new String(Files.readAllBytes(Paths.get(baseline)),StandardCharsets.UTF_8));
            assertEquals(old.path("completeStatusResponse"),receipt.path("completeStatusResponse"),"all JSON fields remain identical to old freeze");}
    }

    @Test void explicitCompositeCrossesTheActualLocalHttpClientWithoutEnteringSealedSources(){
        String projection=snapshots.get(42L).path("projectionSha256").asText(),reviewed=VettingCorpus.hash("AUTHORED transport-only composite");
        canonicalProps.getSourcePageRenderEvidenceIdentities().put(projection,reviewed);
        List<CanonicalSourceBinding> selected=evidence.currentBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        List<String> original=new ArrayList<>();selected.forEach(binding->original.add(binding.getSnapshotJson()));
        canonicalRetrieval.index("authored-http-explicit-pins","advisory",selected);
        assertEquals(reviewed,indexRequest.get().path("sourcePageEvidence").path(projection).asText());
        assertEquals(1,indexRequest.get().path("sourcePageEvidence").size());assertFalse(indexRequest.get().has("chunks"));
        for(int i=0;i<selected.size();i++)assertEquals(original.get(i),selected.get(i).getSnapshotJson());
        assertEquals(1,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        canonicalProps.getSourcePageRenderEvidenceIdentities().clear();
        assertFalse(canonicalRetrieval.indexStatus("authored-http-explicit-pins","advisory",selected).ready);
    }

    static Stream<Arguments> sourceMutations(){return Stream.of(42L,43L).flatMap(id->Stream.of("original_same_length_restored_mtime","db_parsed","binding_revision","cas_same_length_restored_mtime").map(kind->Arguments.of(id,kind)));}
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringIndexRefusesReadyAndSavesZeroHistory(Long id,String kind){
        indexMutation.set(()->mutate(id,kind));assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertEquals(1,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("index-"+id+"-"+kind);
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftBeforePublicSourceReadRefusesWithoutIndexOrHistory(Long id,String kind){
        mutate(id,kind);assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("before-"+id+"-"+kind);
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringRetrievalPreventsLlmAndHistory(Long id,String kind){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();retrieveMutation.set(()->mutate(id,kind));
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertEquals(1,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("retrieve-"+id+"-"+kind);
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringLlmDoublePreventsHistory(Long id,String kind){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();chatMutation.set(()->mutate(id,kind));
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertEquals(1,chatCalls.get());assertEquals(0,history.count());writeNegative("chat-"+id+"-"+kind);
    }
    @Test void newSelectedScopeMemberDuringIndexIsDetectedAfterRemoteBoundary(){
        indexMutation.set(this::newScopeMember);assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertEquals(1,indexCalls.get());assertEquals(0,history.count());writeNegative("index-new-selected-member");
    }
    @Test void newSelectedScopeMemberDuringLlmDoublePreventsHistory(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();chatMutation.set(this::newScopeMember);
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertEquals(1,chatCalls.get());assertEquals(0,history.count());writeNegative("chat-new-selected-member");
    }
    @Test void newSelectedScopeMemberDuringRetrievalPreventsLlmAndHistory(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();retrieveMutation.set(this::newScopeMember);
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertEquals(1,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("retrieve-new-selected-member");
    }
    @Test void dbRevisionImmediatelyBeforeHistoryGatePreventsAtomicHistoryWrites(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();AtomicBoolean changed=new AtomicBoolean();
        doAnswer(call->{if(chatCalls.get()==1&&changed.compareAndSet(false,true))mutate(43L,"db_parsed");return call.callRealMethod();}).when(business).assertSelected(any(),anyList());
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertTrue(changed.get());assertEquals(1,chatCalls.get());assertEquals(0,history.count());writeNegative("before-history-gate-db-revision");
    }
    @Test void publicSnapshotRechecksDbRevisionAfterItsRemoteResponse(){
        snapshotMutation.set(()->mutate(43L,"db_parsed"));
        assertThrows(RuntimeException.class,()->evidence.snapshot(PROJECT,43L,snapshots.get(43L).path("projectionSha256").asText(),"advisory"));
        assertEquals(1,snapshotReads.get());assertEquals(0,history.count());writeNegative("snapshot-post-db-revision");
    }
    @Test void directPdfChunksRetainTheirIndependentPreSourceGate(){
        CanonicalSourceBinding binding=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,42L).get(0);mutate(42L,"original_same_length_restored_mtime");
        assertThrows(RuntimeException.class,()->evidence.chunks(binding,"advisory"));assertEquals(0,snapshotReads.get());assertEquals(0,history.count());writeNegative("direct-pdf-chunks-pre-original");
    }
    @Test void successfulAskKeepsLiteralWholeUnitsUnknownAndTwoAtomicHistoryRows(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();AskResponseVO result=advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset"));
        assertTrue(result.isGrounded());assertEquals(1,chatCalls.get());assertEquals(2,history.count());
        JsonNode source=JsonUtils.mapper().valueToTree(result).path("sources").get(0);
        assertFalse(source.path("canonicalSourceUnit").path("semanticScopeVerified").asBoolean());
        assertEquals("unknown",source.path("canonicalSourceUnit").path("qualifiersComplete").asText());
        assertEquals(snapshots.get(42L).path("chunks").get(0).path("content").asText(),source.path("content").asText());
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.set("completeResponse",JsonUtils.mapper().valueToTree(result));receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));
        receipt.put("historyRows",history.count());receipt.put("llmIsDouble",true);receipt.set("perDocumentSnapshotReads",JsonUtils.mapper().valueToTree(snapshotCounts()));write("successful-ask.json",receipt);
        assertWaitGuards("health");assertWaitGuards("retrieve");assertWaitGuards("llm");
        List<String> sequence=new ArrayList<>(events);int lastModel=sequence.lastIndexOf("llm:end"),firstSave=sequence.indexOf("persist-user:start");
        assertTrue(lastModel>=0&&firstSave>lastModel);for(long id:new long[]{42,43}){assertTrue(sequence.subList(lastModel+1,firstSave).contains("source:"+id+":end"));assertTrue(sequence.subList(lastModel+1,firstSave).contains("cas:"+id+":end"));}
        assertTrue(sequence.contains("persist-assistant:end"));
    }


    @Test void independentStatusCallRecordsPerDocumentReadsAndUnchangedFullResponse(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();long start=System.nanoTime();IndexStatusVO result=advice.status(PROJECT,"fullset");
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("sourceKind","synthetic_actual_spring_h2_local_http");receipt.put("latencyIsSynthetic",true);receipt.put("wallNanos",System.nanoTime()-start);
        receipt.set("perDocumentSnapshotReads",JsonUtils.mapper().valueToTree(snapshotCounts()));receipt.put("snapshotGetCount",snapshotReads.get());receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));
        receipt.set("completeStatusResponse",JsonUtils.mapper().valueToTree(result));write("independent-status.json",receipt);
        assertEquals(JsonUtils.parse("{\"chunks\":4,\"indexed\":4,\"vectorProvider\":\"canonical-oer\",\"collection\":\"authored-recheck-project::canonical::advisory::f48ae8581a939125f62e205e448c47322e921d442cd354cca04721356af0304d\",\"embedModel\":\"BAAI/bge-m3\",\"llmModel\":\"synthetic-recheck-chat\",\"llmReady\":true,\"vectorReady\":true,\"ocrReady\":false,\"message\":{\"zhHans\":\"当前范围的规范证据索引就绪，共 4 个来源切片。\",\"zhHant\":\"當前範圍的規範證據索引就緒，共 4 個來源切片。\",\"en\":\"Canonical evidence index ready for the selected scope: 4 chunks.\"},\"stale\":false,\"eligibleDocuments\":2,\"indexedDocuments\":2,\"embeddingReady\":true,\"evidenceBackend\":\"canonical\",\"sourceScope\":\"fullset\",\"indexKey\":\"authored-recheck-project::canonical::advisory::f48ae8581a939125f62e205e448c47322e921d442cd354cca04721356af0304d\",\"indexSignature\":\"48f27e00d69a2c9b34799ae48a0b32896d8ad42228df3f883d5f5d2e2d71d35d\"}"),JsonUtils.mapper().valueToTree(result),"complete stage02 ready response remains identical");
        assertTrue(result.isVectorReady());assertEquals(8,snapshotReads.get());assertEquals(4,snapshotCounts().get("42"));assertEquals(4,snapshotCounts().get("43"));assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());assertWaitGuards("health");assertWaitGuards("status");
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringHealthPreventsIndexAndHistory(Long id,String kind){
        healthMutation.set(()->mutate(id,kind));assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("health-before-index-"+id+"-"+kind);
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringStatusHealthRefusesReady(Long id,String kind){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();healthMutation.set(()->mutate(id,kind));IndexStatusVO result=advice.status(PROJECT,"fullset");
        assertFalse(result.isVectorReady());assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("health-status-"+id+"-"+kind);
    }
    @Test void newEligibleSelectedScopeMemberDuringStatusHealthRefusesOldReadyResponse(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();healthMutation.set(this::newScopeMember);IndexStatusVO result=advice.status(PROJECT,"fullset");
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("sourceKind","synthetic_actual_spring_h2_local_http");receipt.put("eligibleCatalogDocumentsAfterWait",documents.count());
        receipt.set("completeStatusResponse",JsonUtils.mapper().valueToTree(result));receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));receipt.put("indexCalls",indexCalls.get());receipt.put("llmDoubleCalls",chatCalls.get());receipt.put("historyRows",history.count());
        write("new-selected-scope-during-status-health.json",receipt);
        assertEquals(3,documents.count());assertFalse(result.isVectorReady(),"health wait introduced a new eligible different-name PRE; old selected-source receipt cannot be ready");
        assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringRetrieveHealthPreventsRetrievalLlmAndHistory(Long id,String kind){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();healthMutationOrdinal=2;healthMutation.set(()->mutate(id,kind));
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED full native unit","fullset")));
        assertEquals(2,healthReads.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("health-before-retrieve-"+id+"-"+kind);
    }
    @Test void frameReturnsDetachedBindingsSnapshotsAndPointersAndPerformsFreshClose(){
        String projection=snapshots.get(42L).path("projectionSha256").asText();CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        evidence.assertFrameOwner(frame,PROJECT,"advisory");String before=JsonUtils.write(frame.snapshot(42L,projection,"advisory"));
        assertThrows(UnsupportedOperationException.class,()->frame.bindings().clear());frame.bindings().get(0).setOriginalSourceSha256(repeat('f'));
        ((ObjectNode)frame.snapshot(42L,projection,"advisory")).put("humanConfirmed",true);
        String pointerBefore=JsonUtils.write(frame.pointers(42L,projection,"advisory"));com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=frame.pointers(42L,projection,"advisory").get(0);
        pointer.setHumanConfirmed(true);((ArrayNode)pointer.getParts()).removeAll();pointer.getPhysicalPages().clear();
        assertEquals(before,JsonUtils.write(frame.snapshot(42L,projection,"advisory")));assertEquals(pointerBefore,JsonUtils.write(frame.pointers(42L,projection,"advisory")));
        assertEquals(snapshots.get(42L).path("source").path("sourceSha256").asText(),frame.bindings().get(0).getOriginalSourceSha256());
        assertEquals(2,snapshotReads.get());frame.close();assertEquals(4,snapshotReads.get());frame.close();assertEquals(4,snapshotReads.get());
        assertThrows(RuntimeException.class,frame::bindings);assertThrows(RuntimeException.class,()->frame.snapshot(42L,projection,"advisory"));assertEquals(0,history.count());
    }
    @ParameterizedTest @MethodSource("sourceMutations") void frameCloseRefusesActualSourceCasAndCommittedBindingDriftAndAlwaysClears(Long id,String kind){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));mutate(id,kind);
        assertThrows(RuntimeException.class,frame::close);assertThrows(RuntimeException.class,frame::bindings);frame.close();assertEquals(0,history.count());
    }
    @Test void frameRejectsWrongThreadProjectModuleProjectionDocumentAndServiceOwner()throws Exception{
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));String projection=snapshots.get(42L).path("projectionSha256").asText();
        ExecutorService other=Executors.newSingleThreadExecutor();try{Future<Boolean> future=other.submit(()->{
            assertThrows(RuntimeException.class,frame::bindings);assertThrows(RuntimeException.class,frame::close);assertThrows(RuntimeException.class,frame::revalidate);return true;});assertTrue(future.get(10,TimeUnit.SECONDS));}finally{other.shutdownNow();}
        assertThrows(RuntimeException.class,()->frame.snapshot(42L,projection,"vetting"));assertThrows(RuntimeException.class,()->frame.snapshot(42L,repeat('b'),"advisory"));
        assertThrows(RuntimeException.class,()->frame.snapshot(999L,projection,"advisory"));assertThrows(RuntimeException.class,()->evidence.assertFrameOwner(frame,"another","advisory"));
        CanonicalEvidenceService another=new CanonicalEvidenceService(documents,bindings,new CanonicalEvidenceBackend(canonicalProps),props);
        assertThrows(RuntimeException.class,()->another.assertFrameOwner(frame,PROJECT,"advisory"));frame.close();assertEquals(4,snapshotReads.get());assertEquals(0,history.count());
        for(java.lang.reflect.Constructor<?> constructor:CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructors())if(!constructor.isSynthetic())assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        for(java.lang.reflect.Field field:OerBusinessEvidence.Session.class.getDeclaredFields())assertNotEquals(CanonicalEvidenceService.VerifiedBindingFrame.class,field.getType());
    }
    @Test void exceptionalStageClosesAndSubsequentStageReadsFreshCas(){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        assertThrows(IllegalArgumentException.class,()->{try(CanonicalEvidenceService.VerifiedBindingFrame retained=frame){throw new IllegalArgumentException("synthetic callback failed");}});
        assertThrows(RuntimeException.class,frame::bindings);assertEquals(4,snapshotReads.get());
        try(CanonicalEvidenceService.VerifiedBindingFrame fresh=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L))){assertEquals(6,snapshotReads.get());assertEquals(2,fresh.bindings().size());}
        assertEquals(8,snapshotReads.get());assertEquals(0,history.count());
    }
    @Test void bindingRevisionDuringFirstFrameCasCannotHideInFactoryPersistenceContext(){
        snapshotMutation.set(()->mutate(42L,"binding_revision"));
        assertThrows(RuntimeException.class,()->evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L)));
        assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @Test void bindingRevisionDuringCloseCasCannotHideInClosePersistenceContext(){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        snapshotMutation.set(()->mutate(42L,"binding_revision"));assertThrows(RuntimeException.class,frame::close);
        assertThrows(RuntimeException.class,frame::bindings);assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @Test void pointerTableGroupDeepCopyIsPureAndPreservesExplicitNull()throws Exception{
        java.lang.reflect.Method method=CanonicalEvidenceService.class.getDeclaredMethod("pointer",CanonicalSourceBinding.class,JsonNode.class,JsonNode.class,String.class);method.setAccessible(true);
        CanonicalSourceBinding binding=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,42L).get(0);ObjectNode source=snapshots.get(42L).deepCopy();ObjectNode chunk=(ObjectNode)source.path("chunks").get(0);
        // This authored unsealed alias probe exercises only DTO detachment; never registry/source acceptance.
        chunk.putObject("tableGroup").put("authoredAliasProbe","complete");com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=(com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer)method.invoke(null,binding,source,chunk,"advisory");
        ((ObjectNode)pointer.getTableGroup()).put("authoredAliasProbe","changed");assertEquals("complete",chunk.path("tableGroup").path("authoredAliasProbe").asText());
        chunk.putNull("tableGroup");pointer=(com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer)method.invoke(null,binding,source,chunk,"advisory");assertTrue(pointer.getTableGroup().isNull());
        chunk.remove("tableGroup");pointer=(com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer)method.invoke(null,binding,source,chunk,"advisory");assertNull(pointer.getTableGroup());assertEquals(0,snapshotReads.get());
    }

    void addFixture(Long id,String kind,String key)throws Exception{
        String stem="authored-"+kind+id;String raw=new String(resource(stem+"-public.json"),StandardCharsets.UTF_8);ObjectNode s=(ObjectNode)JsonUtils.parse(raw);
        assertEquals(String.valueOf(id),s.path("projectionIdentity").path("documentId").asText());
        CanonicalEvidenceService.validateSnapshot(raw,s.path("projectionSha256").asText());snapshots.put(id,s);rawSnapshots.put(id,raw);
        Path original=temporary.resolve(stem+("pdf".equals(kind)?".pdf":".docx"));Files.write(original,resource(stem+"-source."+("pdf".equals(kind)?"pdf":"docx")));originals.put(id,original);
        assertEquals(s.path("source").path("sourceSha256").asText(),shaBytes(Files.readAllBytes(original)));
        JsonNode ledger=JsonUtils.parse(new String(resource(kind+"-cas-ledger.json"),StandardCharsets.UTF_8));casLedgers.put(id,ledger);
        for(JsonNode descriptor:ledger){String relative=descriptor.path("relativePath").asText();Path destination=temporary.resolve(relative);Files.createDirectories(destination.getParent());Files.write(destination,resource(relative));}
        casTamperTargets.put(id,temporary.resolve(ledger.get(0).path("relativePath").asText()));
        SourceDocument d=new SourceDocument();d.setProjectId(PROJECT);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey(key);d.setFileName(stem+("pdf".equals(kind)?".pdf":".docx"));
        d.setReviewRole("tender");d.setParseStatus("PARSED");d.setStoragePath(original.toString());d.setSourceSha256(s.path("source").path("sourceSha256").asText());
        d.setTextContent("AUTHORED synthetic Java parsed revision "+id);d.setStructuredContentJson("[]");
        OerDocumentIdentity route=new OerDocumentIdentity();route.setSourceSha256(d.getSourceSha256());route.setKind(key);route.setSourcePurpose("project_document");route.setRole("tender");
        route.setIdentityStatus("content_supported");route.setEligibleModules(Arrays.asList("vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(route));
        assertEquals(id,documents.saveAndFlush(d).getId());
    }
    void register(Long id){JsonNode s=snapshots.get(id);SourceDocument d=documents.findById(id).get();
        evidence.registerReviewed(PROJECT,id,new CanonicalEvidenceService.ReviewedRegistration(s.path("projectionSha256").asText(),d.getSourceSha256(),VettingCorpus.sourceHash(d),
            s.path("source").path("sourceRevisionSha256").asText(),POLICY,s.path("projectionIdentity").path(id==42L?"adoptionManifestSha256":"manifestSha256").asText(),s.path("manifest").path("reference").path("evidenceSha256").asText(),"advisory"));}
    ObjectNode healthFixture()throws Exception{
        ObjectNode h=VettingDualStructureHealthTest.health();OerCudaHealthFixtures.apply(h);ObjectNode scope=JsonUtils.mapper().createObjectNode();scope.put("manifestSha256",POLICY);ObjectNode oer=h.putObject("oer");
        oer.put("inputContract",INPUT);oer.put("assetReturnContract","source-and-projection-bound-content-addressed-assets-v1");oer.put("recipe","scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1");
        oer.put("targetModule","advisory");oer.put("sourceQualityVerifiedByHealth",false);oer.put("parserCandidatesAcceptedByRetrieval",false);oer.put("canonicalProjectionEndpoint","/project");oer.set("scopePolicy",scope);
        oer.put("indexStatusContract","read-only-existing-canonical-index-v1");
        oer.set("nativeCanonical",NativeCanonicalFixture.capability());oer.set("nativePdfCanonical",NativePdfCanonicalFixture.capability());
        ObjectNode windows=(ObjectNode)h.path("retrieval").path("sourceWindows");windows.set("competitionScope",scope.deepCopy());windows.put("oerInputContract",INPUT);windows.put("oerTargetModule","advisory");
        ObjectNode algorithms=(ObjectNode)windows.path("algorithms");
        for(String name:Arrays.asList("oer_server.py","competition_scope.py","artifact_store.py","structured_parser_evidence.py","canonical_projection.py","oer_source_registry.py","oer_request_evidence.py"))algorithms.put(name,VettingCorpus.hash("AUTHORED health "+name));
        for(String name:NativeCanonicalContract.ALGORITHMS)algorithms.put(name,VettingCorpus.hash("AUTHORED health "+name));
        algorithms.setAll(NativePdfCanonicalFixture.algorithms(snapshots.get(42L)));oer.put("sourcePageIndexContract","explicit-composite-index-pins-v1");return h;
    }
    ObjectNode scope(){ObjectNode s=JsonUtils.mapper().createObjectNode();s.put("targetModule","advisory");s.put("inputContract",INPUT);s.put("sourceQualityChanged",false);s.putObject("policyIdentity").put("manifestSha256",POLICY);return s;}
    ObjectNode retrieveFixture(String key)throws Exception{
        ObjectNode result=(ObjectNode)VettingDualStructureHealthTest.resource("authored_canonical_wire.json").path("retrieve").deepCopy();result.put("projectId",key);result.put("indexSignature",SIGNATURE);
        result.put("mode",VettingRetrievalHealth.STRUCTURAL_CONTRACT);result.set("competitionScope",scope());JsonNode seed=snapshots.get(42L).path("chunks").get(0);
        ObjectNode hit=result.putArray("hits").addObject();hit.put("id",seed.path("id").asText());hit.put("score",0.9);hit.set("payload",seed.deepCopy());
        Map<String,JsonNode> parents=new LinkedHashMap<>();for(JsonNode s:snapshots.values())for(JsonNode c:s.path("chunks"))parents.put(c.path("id").asText(),c);
        ObjectNode unit=OerCanonicalSourceUnits.resolve(parents,seed);unit.put("originalRerankOrdinal",1);unit.put("seedScore",0.9);
        ObjectNode episode=(ObjectNode)result.path("sourceUnits");episode.putArray("originalHitIds").add(seed.path("id").asText());episode.putArray("units").add(unit);return result;
    }
    void mutate(Long id,String kind){
        try{
            if("original_same_length_restored_mtime".equals(kind))changeExactLengthRestoredMtime(originals.get(id));
            else if("cas_same_length_restored_mtime".equals(kind))changeExactLengthRestoredMtime(casTamperTargets.get(id));
            else if("db_parsed".equals(kind))jdbc.update("UPDATE source_document SET structured_content_json=? WHERE id=?","[\"COMMITTED synthetic changed parsed bytes\"]",id);
            else if("binding_revision".equals(kind))jdbc.update("UPDATE canonical_source_binding SET projection_revision_sha256=? WHERE document_id=?",VettingCorpus.hash("changed binding revision "+id),id);
            else throw new IllegalArgumentException(kind);
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    void newScopeMember(){SourceDocument d=new SourceDocument();d.setProjectId(PROJECT);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("PRE");d.setFileName("new-current-project-PRE.txt");d.setParseStatus("PARSED");d.setReviewRole("tender");d.setTextContent("NEW scope source");documents.saveAndFlush(d);}
    void verifyActualCas(Long id)throws IOException{for(JsonNode record:casLedgers.get(id)){byte[] actual=Files.readAllBytes(temporary.resolve(record.path("relativePath").asText()));
        if(actual.length!=record.path("byteCount").asInt()||!shaBytes(actual).equals(record.path("sha256").asText()))throw new IOException("SYNTHETIC actual CAS bytes changed");}}
    Long idForProjection(String projection){return snapshots.entrySet().stream().filter(e->e.getValue().path("projectionSha256").asText().equals(projection)).map(Map.Entry::getKey).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown projection"));}
    Map<String,Integer> snapshotCounts(){Map<String,Integer> result=new TreeMap<>();perDocumentSnapshotReads.forEach((id,count)->result.put(String.valueOf(id),count.get()));return result;}
    void resetCounters(){snapshotReads.set(0);indexCalls.set(0);retrieveCalls.set(0);chatCalls.set(0);healthReads.set(0);healthMutationOrdinal=1;perDocumentSnapshotReads.clear();events.clear();clearInvocations(evidence,reader,business,llm,history);}
    void assertWaitGuards(String operation){List<String> sequence=new ArrayList<>(events);boolean seen=false;
        for(int i=0;i<sequence.size();i++)if(sequence.get(i).equals(operation+":start")){seen=true;int end=sequence.subList(i+1,sequence.size()).indexOf(operation+":end");assertTrue(end>=0);end+=i+1;
            int previous=-1;for(int j=i-1;j>=0;j--)if(sequence.get(j).equals("health:end")||sequence.get(j).equals("index:end")||sequence.get(j).equals("status:end")||sequence.get(j).equals("retrieve:end")||sequence.get(j).equals("llm:end")){previous=j;break;}
            int next=sequence.size();for(int j=end+1;j<sequence.size();j++)if(sequence.get(j).equals("health:start")||sequence.get(j).equals("index:start")||sequence.get(j).equals("status:start")||sequence.get(j).equals("retrieve:start")||sequence.get(j).equals("llm:start")){next=j;break;}
            int casEnd=next;
            // Read-only health/status share full CAS validation before publishing the status.
            if("health".equals(operation)&&next<sequence.size()&&sequence.get(next).equals("status:start")){
                int statusEnd=sequence.subList(next+1,sequence.size()).indexOf("status:end");assertTrue(statusEnd>=0);statusEnd+=next+1;casEnd=sequence.size();
                for(int j=statusEnd+1;j<sequence.size();j++)if(sequence.get(j).equals("health:start")||sequence.get(j).equals("index:start")||sequence.get(j).equals("status:start")||sequence.get(j).equals("retrieve:start")||sequence.get(j).equals("llm:start")){casEnd=j;break;}
            }
            for(long id:new long[]{42,43}){assertTrue(sequence.subList(previous+1,i).contains("source:"+id+":end"),operation+" missing fresh original/DB pre guard for "+id);
                assertTrue(sequence.subList(end+1,next).contains("source:"+id+":end"),operation+" missing fresh original/DB post guard for "+id);
                assertTrue(sequence.subList(end+1,casEnd).contains("cas:"+id+":end"),operation+" missing post full CAS for "+id);}}
        assertTrue(seen,"missing actual external "+operation+" wait");}

    void writeNegative(String label){ObjectNode r=JsonUtils.mapper().createObjectNode();r.put("label",label);r.put("synthetic",true);r.put("indexCalls",indexCalls.get());r.put("retrieveCalls",retrieveCalls.get());r.put("llmDoubleCalls",chatCalls.get());r.put("neuralCalls",0);r.put("historyRows",history.count());r.put("result","rejected");write(label+".json",r);}
    void write(String name,JsonNode json){String root=System.getProperty("consense.recheck.output");if(root==null)return;try{Path path=Paths.get(root).resolve(name);Files.createDirectories(path.getParent());Files.write(path,JsonUtils.write(json).getBytes(StandardCharsets.UTF_8));}catch(IOException e){throw new IllegalStateException(e);}}
    static void runOnce(AtomicReference<Runnable> action){Runnable work=action.getAndSet(null);if(work!=null)work.run();}
    static void changeExactLengthRestoredMtime(Path path)throws IOException{FileTime modified=Files.getLastModifiedTime(path);byte[] bytes=Files.readAllBytes(path);long size=bytes.length;bytes[bytes.length/2]^=1;Files.write(path,bytes);Files.setLastModifiedTime(path,modified);assertEquals(size,Files.size(path));assertEquals(modified,Files.getLastModifiedTime(path));}
    static byte[] resource(String name)throws IOException{try(InputStream in=CanonicalRecheckAdviceRealSeamTest.class.getResourceAsStream("/oer/recheck/"+name)){if(in==null)throw new IOException(name);return readAll(in);}}
    static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);return out.toByteArray();}
    static String repeat(char c){char[] values=new char[64];Arrays.fill(values,c);return new String(values);}
    static String shaBytes(byte[] bytes){try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(Locale.ROOT,"%02x",value&255));return hex.toString();}catch(Exception error){throw new IllegalStateException(error);}}

    @Test @DirtiesContext(methodMode=DirtiesContext.MethodMode.BEFORE_METHOD)
    void singleFrameMissingRemoteIndexStatusRefusesWithFreshBoundaryCounts(){
        resetCounters();long begin=System.nanoTime();IndexStatusVO result=advice.status(PROJECT,"fullset");
        assertFalse(result.isVectorReady());assertEquals("canonical_index_unavailable",result.getIndexReason());
        assertEquals(JsonUtils.parse("{\"chunks\":4,\"indexed\":0,\"vectorProvider\":\"canonical-oer\",\"collection\":\"authored-recheck-project::canonical::advisory::f48ae8581a939125f62e205e448c47322e921d442cd354cca04721356af0304d\",\"embedModel\":\"BAAI/bge-m3\",\"llmModel\":\"synthetic-recheck-chat\",\"llmReady\":true,\"vectorReady\":false,\"ocrReady\":false,\"message\":{\"zhHans\":\"当前范围的规范证据索引未就绪，请刷新索引并核对来源及检索服务。\",\"zhHant\":\"當前範圍的規範證據索引未就緒，請刷新索引並核對來源及檢索服務。\",\"en\":\"Canonical evidence index is not ready for the selected scope. Refresh it and check current source bindings and the retrieval service.\"},\"stale\":true,\"eligibleDocuments\":2,\"indexedDocuments\":0,\"embeddingReady\":false,\"evidenceBackend\":\"canonical\",\"sourceScope\":\"fullset\",\"indexKey\":\"authored-recheck-project::canonical::advisory::f48ae8581a939125f62e205e448c47322e921d442cd354cca04721356af0304d\",\"indexReason\":\"canonical_index_unavailable\"}"),JsonUtils.mapper().valueToTree(result),"complete missing remote-index response refuses readiness");
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("schema","consense.single-frame-status-author.v1");
        receipt.put("sourceKind","synthetic_actual_spring_h2_local_http");receipt.put("latencyIsSynthetic",true);receipt.put("wallNanos",System.nanoTime()-begin);
        receipt.set("perDocumentSnapshotReads",JsonUtils.mapper().valueToTree(snapshotCounts()));receipt.put("snapshotGetCount",snapshotReads.get());
        Map<String,Long> sourceCounts=new LinkedHashMap<>();for(long id:new long[]{42,43})sourceCounts.put(String.valueOf(id),events.stream().filter(e->e.equals("source:"+id+":start")).count());
        receipt.set("perDocumentSourceReaderCalls",JsonUtils.mapper().valueToTree(sourceCounts));receipt.put("healthCalls",healthReads.get());
        receipt.put("indexCalls",indexCalls.get());receipt.put("retrieveCalls",retrieveCalls.get());receipt.put("llmDoubleCalls",chatCalls.get());receipt.put("historyRows",history.count());
        receipt.put("verifiedFrameRounds",mockingDetails(evidence).getInvocations().stream().filter(i->i.getMethod().getName().equals("verifiedBindings")).count());
        receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));receipt.set("completeStatusResponse",JsonUtils.mapper().valueToTree(result));write("single-frame-missing-status.json",receipt);
        assertEquals(4,snapshotReads.get());assertEquals(Integer.valueOf(2),snapshotCounts().get("42"));assertEquals(Integer.valueOf(2),snapshotCounts().get("43"));
        assertEquals(1,healthReads.get());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        assertEquals(1,receipt.path("verifiedFrameRounds").asInt());for(long count:sourceCounts.values())assertEquals(8L,count);assertWaitGuards("health");assertWaitGuards("status");
    }
    @Test void newEligibleSelectedScopeMemberDuringStatusExitCasRefusesOldReadyResponse(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();healthMutation.set(()->snapshotMutation.set(this::newScopeMember));
        IndexStatusVO result=advice.status(PROJECT,"fullset");assertEquals(3,documents.count());assertFalse(result.isVectorReady());
        assertEquals("current_sources_unavailable",result.getIndexReason());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        writeNegative("status-new-scope-during-exit-cas");
    }
    @Test void newEligibleSelectedScopeMemberDuringStatusEntryCasRefusesBeforeHealth(){
        snapshotMutation.set(this::newScopeMember);IndexStatusVO result=advice.status(PROJECT,"fullset");
        assertEquals(3,documents.count());assertFalse(result.isVectorReady());assertEquals("current_sources_unavailable",result.getIndexReason());
        assertEquals(0,healthReads.get());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        writeNegative("status-new-scope-during-entry-cas");
    }
    @Test void statusScalarResultCannotCarryAFrameAndNextRequestRechecksActualCas(){
        for(java.lang.reflect.Field field:OerBusinessEvidence.CurrentIndexStatus.class.getDeclaredFields()){
            assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()));
            assertTrue(field.getType()==String.class||field.getType()==int.class||field.getType()==OerCanonicalRetrievalClient.IndexState.class);
        }
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();assertTrue(advice.status(PROJECT,"fullset").isVectorReady());assertEquals(8,snapshotReads.get());
        resetCounters();mutate(42L,"cas_same_length_restored_mtime");IndexStatusVO result=advice.status(PROJECT,"fullset");
        assertFalse(result.isVectorReady());assertTrue(snapshotReads.get()>0,"new request must read actual CAS again");
        assertEquals(0,healthReads.get());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        writeNegative("status-new-request-fresh-cas");
    }

}
