package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VettingStrictHybridTest {
    private static final String SIGNATURE=String.join("",Collections.nCopies(64,"a"));
    @TempDir Path temp;
    static Chunk source(String id,String role,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId("doc-"+id);c.setRole(role);c.setContent(text);
        c.setSourceHash("hash-"+id);c.setAnchor("body/"+id);return c;
    }
    static JsonNode payload(Chunk chunk) {
        ObjectNode value=JsonUtils.mapper().valueToTree(chunk);
        List<String> nulls=new ArrayList<>();value.fields().forEachRemaining(f->{if(f.getValue().isNull())nulls.add(f.getKey());});
        nulls.forEach(value::remove);
        if(value.has("pageNo"))value.put("pageNo",Integer.parseInt(value.path("pageNo").asText().replace("P","")));
        return value;
    }
    static Map<String,Object> receipt(int count) { return VettingReviewProbe.map("projectId","p","indexed",count,"signature",SIGNATURE,"contract","source-bound-window-points-full-parent-results-v1","cached",true,"precomputed",true,"mode","sealed-precomputed-cache"); }
    static Map<String,Object> response(Chunk chunk) {
        return VettingReviewProbe.map("projectId","p","indexSignature",SIGNATURE,"mode","source-bound-window-points-full-parent-results-v1","precomputed",true,
            "cacheMode","sealed-precomputed-cache","hits",Collections.singletonList(VettingReviewProbe.map("id",chunk.getId(),"score",.4,"payload",payload(chunk))));
    }
    static ConsenseProperties strict() {
        ConsenseProperties p=new ConsenseProperties();p.getVetting().setRequireHybridRetrieval(true);p.getVetting().setSemanticTopics(1);return p;
    }
    static JsonNode currentHealth() {
        try(java.io.InputStream in=VettingStrictHybridTest.class.getResourceAsStream("/vetting/current_window_health.json")) {
            assertNotNull(in);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))>=0;)out.write(b,0,n);
            return JsonUtils.parse(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
        }catch(java.io.IOException e){throw new RuntimeException(e);}
    }
    static void healthResponse(HttpSupport http) {when(http.get(endsWith("/health"),anyLong())).thenReturn(JsonUtils.write(currentHealth()));}
    static void indexResponse(HttpSupport http,Map<String,Object> value) {healthResponse(http);when(http.postJson(endsWith("/index"),anyString(),anyLong())).thenReturn(JsonUtils.write(value));}
    static void retrieveResponse(HttpSupport http,Map<String,Object> value) {when(http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenReturn(JsonUtils.write(value));}
    @Test void defaultIsBackwardCompatibleAndStrictReceiptBindsSameSignature() {
        assertFalse(new ConsenseProperties().getVetting().isRequireHybridRetrieval());
        HttpSupport http=mock(HttpSupport.class);ConsenseProperties props=strict();
        Chunk c=source("one","tender","A supplied contract passage requiring source inspection.");c.setPageNo("P12");
        indexResponse(http,receipt(1));retrieveResponse(http,response(c));
        VettingRetrievalClient client=new VettingRetrievalClient(http,props);client.index("p",Collections.singletonList(c));
        assertEquals(Collections.singletonList(c),client.retrieve("p","the same exact query","tender",Collections.singletonList(c)));
    }
    @Test void invalidCountProjectSignatureAndExternalExpectedIdentityAreRejected() {
        Chunk c=source("one","tender","A supplied contract passage requiring source inspection.");
        for(String bad:Arrays.asList("count","project","signature","external")) {
            HttpSupport http=mock(HttpSupport.class);ConsenseProperties props=strict();Map<String,Object> r=receipt(1);
            if("count".equals(bad))r.put("indexed",0);
            if("project".equals(bad))r.put("projectId","other-project");
            if("signature".equals(bad))r.remove("signature");
            if("external".equals(bad))props.getVetting().setExpectedRetrievalSignature(String.join("",Collections.nCopies(64,"b")));
            indexResponse(http,r);VettingRetrievalClient client=new VettingRetrievalClient(http,props);
            assertThrows(IllegalStateException.class,()->client.index("p",Collections.singletonList(c)),bad);
        }
    }
    @Test void failedReindexCannotReuseFormerGoodReceipt() {
        HttpSupport http=mock(HttpSupport.class);ConsenseProperties props=strict();Chunk c=source("one","tender","Original supplied passage.");
        healthResponse(http);
        when(http.postJson(endsWith("/index"),anyString(),anyLong())).thenReturn(JsonUtils.write(receipt(1))).thenThrow(new IllegalStateException("cache changed"));
        retrieveResponse(http,response(c));VettingRetrievalClient client=new VettingRetrievalClient(http,props);client.index("p",Collections.singletonList(c));
        assertThrows(IllegalStateException.class,()->client.index("p",Collections.singletonList(c)));
        assertThrows(IllegalStateException.class,()->client.retrieve("p","query","tender",Collections.singletonList(c)));
        verify(http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }
    @Test void responseCorpusProjectModeUnknownIdRoleAndPayloadChangesCannotPass() {
        Chunk c=source("one","tender","Original supplied passage with unchanged value.");
        for(String bad:Arrays.asList("signature","project","mode","unknown-id","missing-id","role","sourceHash","content","duplicates")) {
            HttpSupport http=mock(HttpSupport.class);ConsenseProperties props=strict();indexResponse(http,receipt(1));Map<String,Object> value=response(c);
            if("signature".equals(bad))value.put("indexSignature",String.join("",Collections.nCopies(64,"b")));
            if("project".equals(bad))value.put("projectId","other");
            if("mode".equals(bad))value.put("mode","lexical");
            JsonNode tree=JsonUtils.parse(JsonUtils.write(value));ObjectNode hit=(ObjectNode)tree.path("hits").get(0);
            if("unknown-id".equals(bad))hit.put("id","unknown");
            if("missing-id".equals(bad))hit.remove("id");
            if("role".equals(bad))((ObjectNode)hit.path("payload")).put("role","standard");
            if("sourceHash".equals(bad))((ObjectNode)hit.path("payload")).put("sourceHash","changed");
            if("content".equals(bad))((ObjectNode)hit.path("payload")).put("content","A different source passage.");
            if("duplicates".equals(bad))((com.fasterxml.jackson.databind.node.ArrayNode)tree.path("hits")).add(hit.deepCopy());
            when(http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenReturn(JsonUtils.write(tree));
            VettingRetrievalClient client=new VettingRetrievalClient(http,props);client.index("p",Collections.singletonList(c));
            assertThrows(IllegalStateException.class,()->client.retrieve("p","query","tender",Collections.singletonList(c)),bad);
        }
    }
    @Test void correctOriginalPayloadFromAnotherRoleIsStillRejected() {
        HttpSupport http=mock(HttpSupport.class);ConsenseProperties props=strict();
        Chunk tender=source("one","tender","The original project passage."),standard=source("two","standard","The original standard passage.");
        indexResponse(http,receipt(2));retrieveResponse(http,response(standard));
        VettingRetrievalClient client=new VettingRetrievalClient(http,props);client.index("p",Arrays.asList(tender,standard));
        assertThrows(IllegalStateException.class,()->client.retrieve("p","query","tender",Arrays.asList(tender,standard)));
    }
    @Test void sameSizeSourceMutationAfterIndexFailsBeforeAnySemanticCall() {
        HttpSupport http=mock(HttpSupport.class);Chunk c=source("one","tender","Original supplied passage.");indexResponse(http,receipt(1));retrieveResponse(http,response(c));
        VettingRetrievalClient client=new VettingRetrievalClient(http,strict());client.index("p",Collections.singletonList(c));c.setContent("Changed same-source passage.");
        assertThrows(IllegalStateException.class,()->client.retrieve("p","query","tender",Collections.singletonList(c)));
        verify(http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }
    @Test void indexFailureOrRoleMissDoesNotSilentlyRunLexicalOrModel() {
        for(boolean indexFailure:Arrays.asList(true,false)) {
            AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);
            ConsenseProperties props=strict();VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
            Chunk tender=source("t","tender","The specified contract value requires source inspection.");
            Chunk standard=source("s","standard","The reference contract value requires source inspection.");
            if(indexFailure)doThrow(new IllegalStateException("cache corpus mismatch")).when(retrieval).index(eq("p"),anyList());
            else {
                when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(tender));
                when(retrieval.retrieve(eq("p"),anyString(),eq("standard"),anyList())).thenThrow(new IllegalStateException("no exact role query cache"));
            }
            assertThrows(IllegalStateException.class,()->new VettingSemanticReview(ai,props,retrieval).review("p",Arrays.asList(tender,standard),"en",(a,b)->{}));
            verify(ai,never()).completeStructuredJsonList(anyString(),anyString(),any(),any(),anyList());
        }
    }
    @Test void validEmptyHybridResultsRemainEmptyAndDefaultStillAllowsOldFallback() {
        for(boolean required:Arrays.asList(true,false)) {
            AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);
            ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);props.getVetting().setRequireHybridRetrieval(required);
            VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);Chunk c=source("one","tender","The payment interim certificate minimum amount is specified.");
            when(retrieval.retrieve(anyString(),anyString(),anyString(),anyList())).thenReturn(Collections.emptyList());
            when(ai.completeStructuredJsonList(anyString(),anyString(),any(),any(),anyList())).thenReturn(Collections.emptyList());
            VettingSemanticReview.Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Collections.singletonList(c),"en",(a,b)->{});
            assertEquals(required?Collections.emptySet():Collections.singleton("one"),result.getReviewedChunkIds());
            if(required)verify(ai,never()).completeStructuredJsonList(anyString(),anyString(),any(),any(),anyList());
        }
    }
    @Test void probeRequiresExplicitSafeIdentityAndFreshOutput() {
        ConsenseProperties props=strict();props.getVetting().setProbeDirectory(temp.toString());
        assertThrows(IllegalArgumentException.class,()->VettingReviewProbe.start(props,null,"p"));
        assertThrows(IllegalArgumentException.class,()->VettingReviewProbe.start(props,"../escape","p"));
        VettingReviewProbe.start(props,"run-one","p");
        assertThrows(IllegalStateException.class,()->VettingReviewProbe.start(props,"run-one","p"));
    }
    @Test void actualStagedFlowRecordsSourceSelectionAndPerRecordGateReasons() throws Exception {
        ConsenseProperties props=strict();props.getVetting().setProbeDirectory(temp.toString());
        Chunk c=source("one","tender","A supplied contract passage requiring source inspection.");
        HttpSupport http=mock(HttpSupport.class);indexResponse(http,receipt(1));retrieveResponse(http,response(c));
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("synthetic-test");
        Record rejected=new Record();rejected.setAssessment("issue");rejected.setType("risk");rejected.setTitle("Source review");rejected.setComment("Bounded test declaration.");
        VettingSemanticReview.Quote bad=new VettingSemanticReview.Quote();bad.setChunkId("one");bad.setSide("source");bad.setQuote("A fabricated source instruction never supplied.");rejected.setEvidence(Collections.singletonList(bad));
        Record observed=new Record();observed.setAssessment("insufficient_context");observed.setType("risk");observed.setTitle("Source context");observed.setComment("Bounded test declaration.");
        VettingSemanticReview.Quote quote=new VettingSemanticReview.Quote();quote.setChunkId("one");quote.setSide("source");quote.setQuote(c.getContent());observed.setEvidence(Collections.singletonList(quote));
        when(ai.completeStructuredJsonList(anyString(),anyString(),any(),any(),anyList(),anyMap())).thenAnswer(call->{
            ((List<String>)call.getArgument(4)).add("synthetic raw content");return Arrays.asList(rejected,observed);});
        VettingSemanticReview.Result result=new VettingSemanticReview(ai,props,new VettingRetrievalClient(http,props))
            .reviewWithProgress("explicit-run","p",Collections.singletonList(c),"en",(a,b,d)->{});
        assertEquals(1,result.getTopicAudits().get(0).getRejectedRecords());
        List<JsonNode> events;
        try(Stream<Path> stream=Files.list(temp.resolve("explicit-run"))) {
            events=stream.filter(path->path.toString().endsWith(".json")).map(path->{try{return JsonUtils.parse(new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new RuntimeException(e);}}).collect(Collectors.toList());
        }
        for(String phase:Arrays.asList("index_request","index_response","index_receipt_gate","retrieve_request","retrieve_response","hit_identity_gate",
            "context_selection","model_system","model_user","model_schema","model_raw_response","schema_gate","record_evidence_applicability_gate","semantic_review_finished"))
            assertTrue(events.stream().anyMatch(e->phase.equals(e.path("phase").asText())),phase);
        assertTrue(events.stream().allMatch(e->"explicit-run".equals(e.path("runId").asText())));
        List<JsonNode> gates=events.stream().filter(e->"record_evidence_applicability_gate".equals(e.path("phase").asText())).map(e->e.path("observations")).collect(Collectors.toList());
        assertEquals(2,gates.size());assertTrue(gates.stream().anyMatch(e->e.path("reasons").toString().contains("quote_not_located_by_current_evidence_policy")));
        assertTrue(gates.stream().anyMatch(e->"mechanically_accepted".equals(e.path("status").asText())&&!e.path("candidateEligible").asBoolean()));
        assertTrue(gates.stream().allMatch(e->e.path("semanticQualityAccepted").isNull()));
    }
}
