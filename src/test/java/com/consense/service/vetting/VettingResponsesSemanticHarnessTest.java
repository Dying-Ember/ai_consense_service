package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.*;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual Java review orchestration with fake HTTP, original source packaging and strict Gateway. */
class VettingResponsesSemanticHarnessTest {
    private Chunk c(String id,String doc,String clause,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileName(doc+".docx");c.setRole("tender");c.setSourceHash(VettingCorpus.hash(doc));c.setFileKey(clause==null?"OTHER":"AAA");c.setClauseId(clause);c.setClauseHeadingLocation(clause==null?null:"body/heading/"+clause);c.setContent(text);c.setAnchor("body/"+id+"/paragraph");c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARSED");q.setOcrQualityStatus("not_observed");q.setCoverageMetadataSha256(VettingCorpus.hash("synthetic-parser-declaration"));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setStartOffset(0);p.setEndOffset(text.length());p.setText(text);p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private List<Chunk> corpus(){return Arrays.asList(c("seed","contract","AAA.1","The interim payment record shall be retained."),c("note","letter",null,"This original note qualifies AAA.1: interim payment records are required unless inactive."));}
    private ConsenseProperties props(List<Chunk> cs){ConsenseProperties p=VettingResponsesTransportTest.props();p.getVetting().setSemanticTopics(1);p.getVetting().setProjectReferenceComparisons(0);p.getVetting().setRequireHybridRetrieval(true);p.getVetting().setSemanticContextChars(cs.get(0).getContent().length());p.getVetting().setSemanticContextExpansionChars(cs.get(0).getContent().length());return p;}
    private VettingSemanticReview.Result review(List<Chunk> cs,ConsenseProperties p,VettingResponsesTransportTest.Http h,LlmClient local) {
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(cs.get(0)));
        VettingSemanticReview review=new VettingSemanticReview(new AiGateway(local),p,retrieval);review.setResponsesTransport(new VettingResponsesTransport(p,h));
        // The local counter must never be consulted when the explicit Responses profile is selected.
        review.setInputBudgetObserver(in->{throw new AssertionError("Local observer selected for Responses");});
        VettingSemanticReview.Result result=review.review("synthetic-remote-only",cs,"en",(n,text)->{});verify(retrieval,times(1)).index("synthetic-remote-only",cs);return result;
    }
    @Test void actualReviewUsesCompleteGlobalWireWithoutLegacyCharacterSplitOrLocalClient() {
        List<Chunk> cs=corpus();VettingResponsesTransportTest.Http h=new VettingResponsesTransportTest.Http();LlmClient local=mock(LlmClient.class);VettingSemanticReview.Result r=review(cs,props(cs),h,local);verifyNoInteractions(local);
        assertEquals(1,r.getActualGatewayCallCount());assertEquals(3,h.endpoints.size(),"Complete global preview/fresh counter/generation without character splitting");assertEquals(2,h.endpoints.stream().filter(s->s.endsWith("/input_tokens")).count());assertEquals(1,h.endpoints.stream().filter(s->s.endsWith("/responses")).count());
        SemanticTopicVO a=r.getTopicAudits().get(0);assertEquals("completed_empty",a.getGlobalCallStatus());assertEquals("partial",a.getAggregateReviewStatus(),"Provider estimates are not exact token/template or semantic completeness");assertFalse(a.isSemanticScopeVerified());assertEquals(0,a.getPendingSourceRequestCount());assertTrue(a.getSourceRequests().stream().allMatch(x->x.getReviewExecutionStatus().startsWith("decoded_provider_estimate")));assertEquals(new HashSet<>(Arrays.asList("seed","note")),r.getReviewedChunkIds());
        for(SemanticPacketVO packet:a.getPacketAudits()){assertEquals("provider_estimated_fit",packet.getInputBudgetStatus());assertFalse(packet.getInputBudgetMetadata().path("observation").path("completeChatTemplateAndSchemaObserved").booleanValue());assertEquals("provider_reported_estimate",packet.getInputBudgetMetadata().path("observation").path("providerEstimate").path("kind").textValue());}
        List<JsonNode> counts=new ArrayList<>(),generated=new ArrayList<>();for(int i=0;i<h.bodies.size();i++){JsonNode body=JsonUtils.parse(h.bodies.get(i));if(h.endpoints.get(i).endsWith("/input_tokens"))counts.add(body);else generated.add(body);}
        assertEquals(0,a.getExtraPacketCount());for(String key:Arrays.asList("model","instructions","input","reasoning"))assertEquals(counts.get(1).get(key),generated.get(0).get(key));
        assertTrue(generated.get(0).path("input").get(0).path("content").textValue().contains(cs.get(1).getContent()));assertTrue(generated.get(0).path("instructions").textValue().contains("\"seed\""));assertTrue(generated.get(0).path("instructions").textValue().contains("\"note\""));
    }
    @Test void remoteUnknownGlobalAndExtrasNeverFallBackToLocalOrGenerate() {
        List<Chunk> cs=corpus();ConsenseProperties p=props(cs);p.getVetting().getResponses().setEstimatePolicy(null);VettingResponsesTransportTest.Http h=new VettingResponsesTransportTest.Http();LlmClient local=mock(LlmClient.class);VettingSemanticReview.Result r=review(cs,p,h,local);verifyNoInteractions(local);assertEquals(0,r.getActualGatewayCallCount());assertTrue(h.endpoints.isEmpty());SemanticTopicVO a=r.getTopicAudits().get(0);assertEquals("not_submitted_budget_unknown",a.getGlobalCallStatus());assertEquals("not_submitted",a.getAggregateReviewStatus());assertTrue(a.getPendingSourceRequestCount()>0);assertTrue(r.getReviewedChunkIds().isEmpty());
    }
    @Test void counterFailurePreservesPendingScopeAndNeverProducesFakeEmptyAnswer() {
        List<Chunk> cs=corpus();VettingResponsesTransportTest.Http h=new VettingResponsesTransportTest.Http();h.status=403;LlmClient local=mock(LlmClient.class);VettingSemanticReview.Result r=review(cs,props(cs),h,local);verifyNoInteractions(local);assertEquals(0,r.getActualGatewayCallCount());assertTrue(h.endpoints.stream().allMatch(s->s.endsWith("/input_tokens")));assertTrue(r.getReviewedChunkIds().isEmpty());assertEquals("not_submitted_budget_unknown",r.getTopicAudits().get(0).getGlobalCallStatus());assertEquals(0,r.getTopicAudits().get(0).getReturnedAssessments());
    }
    @Test void knownRemoteOverBudgetBlocksAllActualGeneration() {
        List<Chunk> cs=corpus();VettingResponsesTransportTest.Http h=new VettingResponsesTransportTest.Http();h.count=50000;LlmClient local=mock(LlmClient.class);VettingSemanticReview.Result r=review(cs,props(cs),h,local);verifyNoInteractions(local);assertEquals(0,r.getActualGatewayCallCount());assertEquals("not_submitted_over_budget",r.getTopicAudits().get(0).getGlobalCallStatus());assertTrue(r.getTopicAudits().get(0).getSourceRequests().stream().anyMatch(x->"over_budget".equals(x.getTransportStatus())));assertTrue(h.endpoints.stream().allMatch(s->s.endsWith("/input_tokens")));
    }
    @Test void finalProviderEstimateDriftRejectsItsPacketBeforeActualGateway() {
        List<Chunk> cs=Collections.singletonList(corpus().get(0));VettingResponsesTransportTest.Http h=new VettingResponsesTransportTest.Http();h.counterHook=()->h.count=h.endpoints.size()==1?100:101;LlmClient local=mock(LlmClient.class);VettingSemanticReview.Result r=review(cs,props(cs),h,local);assertEquals(0,r.getActualGatewayCallCount());assertEquals(2,h.endpoints.size());SemanticTopicVO a=r.getTopicAudits().get(0);assertEquals("not_submitted_budget_unknown",a.getGlobalCallStatus());assertEquals("token_preview_final_observation_changed",a.getError());verifyNoInteractions(local);
    }
    @Test void finalTypedOutputFailureIsRetainedAfterValidCounterAndNotMarkedReviewed() {
        List<Chunk> cs=Collections.singletonList(corpus().get(0));VettingResponsesTransportTest.Http h=new VettingResponsesTransportTest.Http();h.generation="{\"object\":\"response\",\"model\":\"MiniMax-M3\",\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}";LlmClient local=mock(LlmClient.class);VettingSemanticReview.Result r=review(cs,props(cs),h,local);assertEquals(1,r.getActualGatewayCallCount());assertEquals("failed",r.getTopicAudits().get(0).getGlobalCallStatus());assertEquals("output_budget_exhausted",r.getTopicAudits().get(0).getFailureKind());assertTrue(r.getReviewedChunkIds().isEmpty());assertTrue(r.getCandidates().isEmpty());verifyNoInteractions(local);
    }
    @Test void disablingResponsesDuringNoPreviewCounterCannotSwitchPacketToLocalProvider() throws Exception {
        List<Chunk> cs=Collections.singletonList(corpus().get(0));ConsenseProperties p=props(cs);
        VettingResponsesTransportTest.Http h=new VettingResponsesTransportTest.Http();h.counterHook=()->p.getVetting().getResponses().setEnabled(false);
        LlmClient local=mock(LlmClient.class);when(local.chatStructured(anyList(),any())).thenReturn("[]");when(local.available()).thenReturn(true);
        VettingSemanticReview review=new VettingSemanticReview(new AiGateway(local),p,mock(VettingRetrievalClient.class));review.setResponsesTransport(new VettingResponsesTransport(p,h));
        VettingContextBuilder.Selection selection=new VettingContextBuilder.Selection();selection.setChunks(cs);selection.setContentChars(cs.get(0).getContent().length());
        SemanticTopicVO audit=new SemanticTopicVO();audit.setStatus("not_submitted");SemanticPacketVO packet=VettingPacketCoverage.packet(1,0,"project_reference",cs,Collections.emptyList());
        VettingSemanticReview.Result r=new VettingSemanticReview.Result();VettingReviewProbe probe=VettingReviewProbe.start(p,null,"synthetic-no-preview");
        java.lang.reflect.Method execute=VettingSemanticReview.class.getDeclaredMethod("executePacket",VettingSemanticReview.Result.class,SemanticTopicVO.class,SemanticPacketVO.class,VettingContextBuilder.Selection.class,String.class,String.class,boolean.class,List.class,int.class,VettingReviewProbe.Call.class);execute.setAccessible(true);
        execute.invoke(review,r,audit,packet,selection,"synthetic topic","en",true,Collections.emptyList(),1,probe.call(1,"synthetic topic",null));
        verifyNoInteractions(local);assertEquals(0,r.getActualGatewayCallCount());assertEquals(1,h.endpoints.size());
        assertEquals("not_submitted_budget_unknown",audit.getStatus());assertTrue(r.getReviewedChunkIds().isEmpty());
    }
}
