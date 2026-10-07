package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VettingProbeTimingTest {
    @TempDir Path temp;
    @Test void enabledResponsesSecretIsExcludedFromActualRunProbeButStillBindsForTransport() throws Exception {
        String sentinel="synthetic-secret-probe-boundary";
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setProbeDirectory(temp.toString());
        props.getVetting().getResponses().setEnabled(true);props.getVetting().getResponses().setApiKey(sentinel);
        VettingReviewProbe probe=VettingReviewProbe.start(props,"run-no-key","p");probe.finish("returned_normally");
        String recorded=new String(Files.readAllBytes(temp.resolve("run-no-key/00001-run_started.json")),StandardCharsets.UTF_8);
        assertFalse(recorded.contains(sentinel));assertFalse(JsonUtils.parse(recorded).path("observations").path("parameters").path("responses").has("apiKey"));
        assertEquals(sentinel,props.getVetting().getResponses().getApiKey());
        assertEquals(sentinel,JsonUtils.read("{\"apiKey\":\""+sentinel+"\"}",ConsenseProperties.Responses.class).getApiKey());
    }
    static JsonNode read(Path path) throws Exception { return JsonUtils.parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)); }
    static List<JsonNode> events(Path dir) throws Exception {
        try(Stream<Path> paths=Files.list(dir)) {
            return paths.filter(p->p.getFileName().toString().matches("[0-9]{5}-.*\\.json"))
                .sorted().map(p->{try{return read(p);}catch(Exception e){throw new RuntimeException(e);}}).collect(Collectors.toList());
        }
    }
    @Test void cumulativeProbeIoCostsAreSeparateFromMeasuredPhaseAndRunIdentityIsDurable() throws Exception {
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setProbeDirectory(temp.toString());
        VettingReviewProbe probe=VettingReviewProbe.start(props,"run-timing","p");
        VettingReviewProbe.Call call=probe.call(1,"Synthetic bounded scope","tender");
        try(VettingReviewProbe.Timer timer=call.measure("synthetic_operation","Contains probe event I/O only to test subtraction")) {
            call.text("source_observation",VettingReviewProbe.map("sourceChunkId","unit"),"Literal source text");
        }
        probe.finish("returned_normally");
        JsonNode summary=read(temp.resolve("run-timing/probe_overhead_manifest.json"));
        assertEquals("run-timing",summary.path("runId").asText());
        assertEquals(3,summary.path("eventCount").asInt());
        assertEquals(0,summary.path("observerFailureCount").asInt());
        assertTrue(summary.path("eventSerializationWallNanos").asLong()>0);
        assertTrue(summary.path("textEncodingAndHashWallNanos").asLong()>0);
        assertTrue(summary.path("artifactWriteWallNanos").asLong()>0);
        assertFalse(summary.path("summarySelfSerializationAndWriteIncluded").asBoolean());
        JsonNode totals=summary.path("stageTimingAggregates").path("synthetic_operation");
        assertEquals(1,totals.path("count").asInt());
        assertTrue(totals.path("probeOverheadDuringPhaseNanos").asLong()>0);
        assertEquals(totals.path("inclusiveWallNanos").asLong(),totals.path("wallNanos").asLong()+totals.path("probeOverheadDuringPhaseNanos").asLong());
        byte[] before=Files.readAllBytes(temp.resolve("run-timing/probe_overhead_manifest.json"));
        probe.finish("not_allowed_to_replace_finished_summary");
        assertArrayEquals(before,Files.readAllBytes(temp.resolve("run-timing/probe_overhead_manifest.json")));
    }
    @Test void strictFailedHttpHasTimingAndAbortedSummaryWithoutStartingModel() throws Exception {
        ConsenseProperties props=VettingStrictHybridTest.strict();props.getVetting().setProbeDirectory(temp.toString());
        HttpSupport http=mock(HttpSupport.class);
        VettingStrictHybridTest.healthResponse(http);
        when(http.postJson(anyString(),anyString(),anyLong())).thenThrow(new IllegalStateException("Synthetic cache miss"));
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);
        Chunk source=VettingStrictHybridTest.source("one","tender","Literal source clause for required hybrid retrieval.");
        assertThrows(IllegalStateException.class,()->new VettingSemanticReview(ai,props,new VettingRetrievalClient(http,props))
            .reviewWithProgress("run-failed","p",Collections.singletonList(source),"en",(a,b,c)->{}));
        List<JsonNode> events=events(temp.resolve("run-failed"));
        assertTrue(events.stream().anyMatch(e->"phase_timing".equals(e.path("phase").asText())&&"index_http".equals(e.path("observations").path("stage").asText())));
        assertEquals("aborted",read(temp.resolve("run-failed/probe_overhead_manifest.json")).path("status").asText());
        verify(ai,never()).completeStructuredJsonList(anyString(),anyString(),any(),any(),anyList(),anyMap());
    }
    @Test void gatewayTimedOverloadKeepsOriginalRequestAndDecodingAndSeparatesParse() {
        LlmClient provider=mock(LlmClient.class);when(provider.available()).thenReturn(true);
        when(provider.chatStructured(anyList(),any())).thenReturn("[\"literal output\"]");
        AiGateway gateway=new AiGateway(provider);
        JsonNode schema=JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        List<String> raw=new ArrayList<>();Map<String,Long> timings=new LinkedHashMap<>();
        assertEquals(Collections.singletonList("literal output"),gateway.completeStructuredJsonList("unchanged system","unchanged user",String.class,schema,raw,timings));
        assertEquals(Collections.singletonList("[\"literal output\"]"),raw);
        assertEquals(Collections.singletonList("literal output"),gateway.completeStructuredJsonList("unchanged system","unchanged user",String.class,schema));
        org.mockito.ArgumentCaptor<List> requests=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(provider,times(2)).chatStructured(requests.capture(),eq(schema));
        assertEquals(JsonUtils.write(requests.getAllValues().get(0)),JsonUtils.write(requests.getAllValues().get(1)));
        for(String phase:Arrays.asList("availability_check","schema_envelope_construction","prompt_log","structured_client_call","raw_content_capture_and_log","parse_list"))
            assertTrue(timings.containsKey(phase)&&timings.get(phase)>=0,phase);
    }
    @Test void malformedRawStillRaisesOriginalParseFailureButKeepsActualParseTiming() {
        LlmClient provider=mock(LlmClient.class);when(provider.available()).thenReturn(true);
        when(provider.chatStructured(anyList(),any())).thenReturn("{unfinished");
        AiGateway gateway=new AiGateway(provider);Map<String,Long> timings=new LinkedHashMap<>();List<String> raw=new ArrayList<>();
        assertThrows(RuntimeException.class,()->gateway.completeStructuredJsonList("system","user",String.class,JsonUtils.parse("{\"type\":\"array\"}"),raw,timings));
        assertEquals(Collections.singletonList("{unfinished"),raw);
        assertTrue(timings.containsKey("parse_list"));assertTrue(timings.containsKey("structured_client_call"));
        verify(provider,times(1)).chatStructured(anyList(),any());
    }
    @Test void successfulActualGatewayPathHasSeparateContextHttpGateAndParseStageObservations() throws Exception {
        ConsenseProperties props=VettingStrictHybridTest.strict();props.getVetting().setProbeDirectory(temp.toString());
        Chunk source=VettingStrictHybridTest.source("one","tender","Literal source clause for required hybrid retrieval.");
        HttpSupport http=mock(HttpSupport.class);VettingStrictHybridTest.indexResponse(http,VettingStrictHybridTest.receipt(1));
        VettingStrictHybridTest.retrieveResponse(http,VettingStrictHybridTest.response(source));
        LlmClient provider=mock(LlmClient.class);when(provider.available()).thenReturn(true);when(provider.chatModel()).thenReturn("synthetic-only");
        when(provider.chatStructured(anyList(),any())).thenReturn("[]");
        VettingSemanticReview.Result result=new VettingSemanticReview(new AiGateway(provider),props,new VettingRetrievalClient(http,props))
            .reviewWithProgress("run-success","p",Collections.singletonList(source),"en",(a,b,c)->{});
        assertEquals("completed_empty",result.getTopicAudits().get(0).getStatus());
        List<JsonNode> events=events(temp.resolve("run-success"));
        Set<String> stages=events.stream().filter(e->"phase_timing".equals(e.path("phase").asText()))
            .map(e->e.path("observations").path("stage").asText()).collect(Collectors.toSet());
        assertTrue(stages.containsAll(Arrays.asList("index_request_serialization","index_http","index_receipt_gate","index_corpus_fingerprint",
            "retrieve_current_corpus_fingerprint","retrieve_request_serialization","retrieve_http","retrieve_response_parse",
            "retrieve_response_identity_gate","retrieve_source_lookup_build","retrieve_hit_gates","context_builder_selection",
            "model_prompt_schema_construction","ai_gateway_call","semantic_schema_gate")));
        JsonNode gateway=events.stream().filter(e->"ai_gateway_phase_timings".equals(e.path("phase").asText())).findFirst().get().path("observations").path("phaseWallNanos");
        assertTrue(gateway.has("parse_list"));assertTrue(gateway.has("structured_client_call"));
        assertEquals("returned_normally",read(temp.resolve("run-success/probe_overhead_manifest.json")).path("status").asText());
        assertTrue(events.stream().filter(e->"phase_timing".equals(e.path("phase").asText())).allMatch(e->e.path("observations").path("wallNanos").asLong()>=0));
    }
}
