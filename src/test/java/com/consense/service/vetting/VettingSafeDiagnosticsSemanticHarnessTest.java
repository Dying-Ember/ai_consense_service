package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real executePacket/Gateway/probe/DTO boundaries; fake WireHttp only, no HTTP or generation. */
class VettingSafeDiagnosticsSemanticHarnessTest {
    @TempDir Path temp;
    static List<JsonNode> events(Path p)throws Exception {try(Stream<Path>s=Files.list(p)){return s.filter(x->x.getFileName().toString().matches("[0-9]{5}-.*\\.json")).sorted().map(x->{try{return JsonUtils.parse(new String(Files.readAllBytes(x),StandardCharsets.UTF_8));}catch(Exception e){throw new AssertionError(e);}}).collect(Collectors.toList());}}
    private VettingSemanticReview.Result execute(boolean counterFails,SemanticTopicVO audit,SemanticPacketVO packet,List<String> endpoints)throws Exception {
        ConsenseProperties props=VettingResponsesTransportTest.props();props.getVetting().setProbeDirectory(temp.toString());
        LlmClient local=mock(LlmClient.class);VettingSemanticReview review=new VettingSemanticReview(new AiGateway(local),props,mock(VettingRetrievalClient.class));
        VettingResponsesTransport.TypedTransportException failure=SafeDiagnosticsFixtureSupport.typed(counterFails?"response_headers":"response_body",counterFails?null:200,counterFails?0:7);
        review.setResponsesTransport(new VettingResponsesTransport(props,(endpoint,body,timeout,cap,auth)->{endpoints.add(endpoint);if(counterFails||endpoint.endsWith("/responses"))throw failure;return new VettingResponsesTransport.Response(200,"{\"object\":\"response.input_tokens\",\"input_tokens\":100}");}));
        Chunk c=SafeDiagnosticsFixtureSupport.chunk("seed","The shipping register shall be retained.");VettingContextBuilder.Selection selection=new VettingContextBuilder.Selection();selection.setChunks(Collections.singletonList(c));selection.setContentChars(c.getContent().length());
        packet.setSourceSnapshotSha256(VettingCorpus.hash(JsonUtils.write(selection.getChunks())));
        VettingSemanticReview.Result result=new VettingSemanticReview.Result();String run=counterFails?"synthetic-counter":"synthetic-generation";VettingReviewProbe probe=VettingReviewProbe.start(props,run,"synthetic-project");
        Method method=VettingSemanticReview.class.getDeclaredMethod("executePacket",VettingSemanticReview.Result.class,SemanticTopicVO.class,SemanticPacketVO.class,VettingContextBuilder.Selection.class,String.class,String.class,boolean.class,List.class,int.class,VettingReviewProbe.Call.class);method.setAccessible(true);
        method.invoke(review,result,audit,packet,selection,"synthetic shipment topic","en",true,Collections.emptyList(),1,probe.call(1,"synthetic shipment topic",null));
        // Synthetic source-request fixture for the real finish accounting, not source applicability evidence.
        VettingSourceRequestPacks.Result sourcePlan=new VettingSourceRequestPacks.Result();VettingSourceRequestPacks.Request request=new VettingSourceRequestPacks.Request();request.setId("synthetic-pending-request");request.setStatus("already_global");request.setRequiredChunkIds(Collections.singletonList("seed"));request.setEligibleOriginIds(Collections.singletonList("seed"));sourcePlan.getRequests().add(request);audit.getPacketAudits().add(packet);VettingPacketCoverage.finish(audit,sourcePlan);
        probe.finish("returned_normally");verifyNoInteractions(local);
        Path proof=Paths.get("target/safe-diagnostic-propagation");Files.createDirectories(proof);String stem=counterFails?"counter":"generation";
        Files.write(proof.resolve(stem+"-packet.json"),JsonUtils.write(packet).getBytes(StandardCharsets.UTF_8));Files.write(proof.resolve(stem+"-topic.json"),JsonUtils.write(audit).getBytes(StandardCharsets.UTF_8));
        for(JsonNode event:events(temp.resolve(run)))if(Arrays.asList("packet_input_budget","packet_finished","model_transport_failure").contains(event.path("phase").asText()))Files.write(proof.resolve(stem+"-"+event.path("phase").asText()+".json"),JsonUtils.write(event).getBytes(StandardCharsets.UTF_8));
        return result;
    }
    private SemanticPacketVO packet() {return VettingPacketCoverage.packet(1,0,"project_reference",Collections.singletonList(SafeDiagnosticsFixtureSupport.chunk("seed","The shipping register shall be retained.")),Collections.singletonList("synthetic-pending-request"));}
    @Test void finalCounterNativeFailureReachesPacketProbeAndDtoWhileGatewayRemainsUncalled()throws Exception {
        SemanticTopicVO audit=new SemanticTopicVO();audit.setStatus("not_submitted");SemanticPacketVO packet=packet();List<String> endpoints=new ArrayList<>();VettingSemanticReview.Result r=execute(true,audit,packet,endpoints);
        assertEquals(1,endpoints.size());assertTrue(endpoints.get(0).endsWith("/input_tokens"));assertEquals(0,r.getActualGatewayCallCount());assertFalse(packet.isActualGatewayCallStarted());assertEquals("not_submitted_budget_unknown",audit.getStatus());assertEquals("input_budget_unknown",audit.getFailureKind());assertEquals("token_observer_failed_IllegalStateException",audit.getError());assertTrue(r.getReviewedChunkIds().isEmpty());assertTrue(r.getCandidates().isEmpty());assertEquals(0,audit.getReturnedAssessments());
        JsonNode d=packet.getInputBudgetMetadata().get("failureDiagnostics");VettingSafeDiagnosticsPropagationTest.safe(d);assertEquals("response_headers",d.path("phase").textValue());
        SemanticPacketVO dto=JsonUtils.read(JsonUtils.write(packet),SemanticPacketVO.class);assertEquals(JsonUtils.write(packet.getInputBudgetMetadata()),JsonUtils.write(dto.getInputBudgetMetadata()));assertFalse(dto.isActualGatewayCallStarted());
        JsonNode event=events(temp.resolve("synthetic-counter")).stream().filter(x->"packet_input_budget".equals(x.path("phase").asText())).findFirst().get();assertEquals(d,event.path("observations").path("inputBudget").get("failureDiagnostics"));assertTrue(packet.getRequestIds().contains("synthetic-pending-request"));
        assertEquals(1,audit.getPendingSourceRequestCount());assertEquals("not_submitted",audit.getAggregateReviewStatus());assertEquals("budget_unknown",audit.getSourceRequests().get(0).getReviewExecutionStatus());assertFalse(audit.isSemanticScopeVerified());
    }
    @Test void nativeGenerationFailureReachesExistingModelMetadataAndProbeWithoutFakeAnswer()throws Exception {
        SemanticTopicVO audit=new SemanticTopicVO();audit.setStatus("not_submitted");SemanticPacketVO packet=packet();List<String>endpoints=new ArrayList<>();VettingSemanticReview.Result r=execute(false,audit,packet,endpoints);
        assertEquals(2,endpoints.size());assertTrue(endpoints.get(0).endsWith("/input_tokens"));assertTrue(endpoints.get(1).endsWith("/responses"));assertEquals(1,r.getActualGatewayCallCount());assertTrue(packet.isActualGatewayCallStarted());assertEquals("failed",audit.getStatus());assertEquals("transport_failure",audit.getFailureKind());assertEquals("failed",packet.getStatus());assertTrue(r.getCandidates().isEmpty());assertTrue(r.getReviewedChunkIds().isEmpty());assertEquals(0,audit.getReturnedAssessments());
        VettingSafeDiagnosticsPropagationTest.safe(audit.getModelResponseMetadata());assertEquals("response_body",audit.getModelResponseMetadata().path("phase").textValue());assertEquals(200,audit.getModelResponseMetadata().path("responseStatus").intValue());assertEquals(7,audit.getModelResponseMetadata().path("bytesRead").longValue());assertEquals(audit.getModelResponseMetadata(),packet.getModelResponseMetadata());
        SemanticPacketVO dto=JsonUtils.read(JsonUtils.write(packet),SemanticPacketVO.class);assertEquals(packet.getModelResponseMetadata(),dto.getModelResponseMetadata());
        JsonNode event=events(temp.resolve("synthetic-generation")).stream().filter(x->"model_transport_failure".equals(x.path("phase").asText())).findFirst().get();assertEquals(audit.getModelResponseMetadata(),event.path("observations").get("transportDiagnostics"));assertFalse(event.path("observations").path("formalAnswerAccepted").asBoolean());assertTrue(packet.getRequestIds().contains("synthetic-pending-request"));
        assertEquals(1,audit.getPendingSourceRequestCount());assertEquals("failed",audit.getAggregateReviewStatus());assertEquals("failed",audit.getSourceRequests().get(0).getReviewExecutionStatus());assertFalse(audit.isSemanticScopeVerified());
    }
}
