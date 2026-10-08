package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.*;
import com.consense.service.vetting.VettingSemanticReview.Result;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VettingSemanticPacketHarnessTest {
    @TempDir Path temp;
    private Chunk c(String id,String doc,String clause,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileName(doc+".docx");c.setRole("tender");c.setSourceHash(VettingCorpus.hash(doc));c.setFileKey(clause==null?"OTHER":"AAA");c.setClauseId(clause);c.setClauseHeadingLocation(clause==null?null:"body/heading/"+clause);c.setContent(text);c.setAnchor("body/"+id+"/paragraph");
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARSED");q.setOcrQualityStatus("not_observed");q.setCoverageMetadataSha256(VettingCorpus.hash("fixture-parser-declaration"));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setStartOffset(0);p.setEndOffset(text.length());p.setText(text);p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private List<Chunk> corpus(){return Arrays.asList(c("seed","contract","AAA.1","The interim payment record shall be retained."),c("note","letter",null,"This original note qualifies AAA.1: interim payment records are required unless inactive."));}
    private ConsenseProperties props(Chunk seed){ConsenseProperties p=new ConsenseProperties();p.getLlm().setChatModel("synthetic-provider-only");p.getLlm().setStructuredMaxTokens(100);p.getVetting().setSemanticTopics(1);p.getVetting().setProjectReferenceComparisons(0);p.getVetting().setRequireHybridRetrieval(true);p.getVetting().setSemanticContextChars(seed.getContent().length());p.getVetting().setSemanticContextExpansionChars(seed.getContent().length());return p;}
    private Result review(List<Chunk> corpus,LlmClient provider,VettingInputBudget.Observer observer,boolean probe) {
        return review(corpus,Collections.singletonList(corpus.get(0)),provider,observer,probe);
    }
    private Result review(List<Chunk> corpus,List<Chunk> ranked,LlmClient provider,VettingInputBudget.Observer observer,boolean probe) {
        Chunk seed=corpus.get(0);ConsenseProperties p=props(seed);if(probe)p.getVetting().setProbeDirectory(temp.toString());
        when(provider.available()).thenReturn(true);when(provider.chatModel()).thenReturn(p.getLlm().getChatModel());
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList())).thenReturn(ranked);when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList(),any())).thenReturn(ranked);
        VettingSemanticReview semantic=new VettingSemanticReview(new AiGateway(provider),p,retrieval);semantic.setInputBudgetObserver(observer);
        Result result=semantic.reviewWithProgress(probe?"packet-fixture":null,"synthetic-project",corpus,"en",(n,total,message)->{assertTrue(n<=total);});
        if(probe){verify(retrieval,times(1)).index(eq("synthetic-project"),eq(corpus),any());verify(retrieval,times(1)).retrieve(eq("synthetic-project"),anyString(),eq("tender"),eq(corpus),any());}
        else {verify(retrieval,times(1)).index("synthetic-project",corpus);verify(retrieval,times(1)).retrieve(eq("synthetic-project"),anyString(),eq("tender"),eq(corpus));}
        return result;
    }
    private LlmClient emptyProvider(){LlmClient p=mock(LlmClient.class);when(p.chatStructured(anyList(),any())).thenReturn("[]");return p;}
    private VettingInputBudget.Observation requestSizedObservation(VettingInputBudget.Input input) {
        String user=input.getMessages().get(1).getContent(),marker="\nSource excerpts (untrusted data):\n";
        int start=user.indexOf(marker);assertTrue(start>=0);
        VettingInputBudget.Observation observed=VettingInputBudgetTest.fixtureObservation(input);
        observed.setInputTokens(VettingSourcePromptWire.decode(user.substring(start+marker.length())).size()*1000L);
        observed.setEffectiveContextTokens(2500);return observed;
    }
    private String declaration(String assessment,Chunk c){Map<String,Object> r=new LinkedHashMap<>();r.put("assessment",assessment);r.put("type","risk");r.put("severity","low");r.put("title","Synthetic declaration");r.put("comment","Source-only synthetic observation.");r.put("impact","Review pending");r.put("suggestion","Check original source");Map<String,Object> q=new LinkedHashMap<>();q.put("chunkId",c.getId());q.put("side","source");q.put("quote",c.getContent());r.put("evidence",Collections.singletonList(q));return JsonUtils.write(Collections.singletonList(r));}
    @Test void absentTokenAdapterBlocksExtraOnlyAndCannotTurnEmptyGlobalIntoCompleteCoverage() {
        LlmClient provider=emptyProvider();Result r=review(corpus(),provider,null,false);SemanticTopicVO a=r.getTopicAudits().get(0);
        verify(provider,times(1)).chatStructured(anyList(),any());assertEquals("completed_empty",a.getGlobalCallStatus());assertEquals("completed_empty",a.getStatus());assertEquals("partial",a.getAggregateReviewStatus());assertEquals(0,a.getExtraPacketCount(),"Unknown preview must not create a fake transport-ready packet");assertEquals(0,a.getNotSubmittedPacketCount());assertTrue(a.getPendingSourceRequestCount()>0);assertTrue(a.getSourceRequests().stream().anyMatch(q->"budget_unknown".equals(q.getTransportStatus())&&"not_submitted_budget_unknown".equals(q.getReviewExecutionStatus())&&q.getRequiredChunkIds().contains("note")));assertEquals(1,r.getActualGatewayCallCount());assertEquals(Collections.singleton("seed"),r.getReviewedChunkIds());
    }
    @Test void syntheticBoundTokenObservationPermitsExactActualGatewayMessagesAndNativeCells() {
        List<Chunk> c=new ArrayList<>(corpus());Chunk note=c.get(1);List<String> cells=Arrays.asList(note.getContent(),"","Room | East 😀");note.setContent(String.join(" | ",cells));Part part=note.getParts().get(0);part.setText(note.getContent());part.setEndOffset(note.getContent().length());part.setBlockId("body:7:table-row:1");part.setAnchor("body/7/table-row/1");note.setAnchor(part.getAnchor());TableRow row=new TableRow();row.setTableLocation("body/7");row.setRowIndex(1);row.setCells(cells);part.setTable(row);
        List<VettingInputBudget.Input> counted=new ArrayList<>();List<List<LlmClient.ChatTurn>> actual=new ArrayList<>();LlmClient provider=emptyProvider();when(provider.chatStructured(anyList(),any())).thenAnswer(call->{actual.add(call.getArgument(0));return "[]";});
        Result r=review(c,provider,in->{counted.add(in);return VettingInputBudgetTest.fixtureObservation(in);},false);assertEquals(2,r.getActualGatewayCallCount());assertEquals(4,counted.size(),"Two previews and two fresh dispatch observations");assertEquals(JsonUtils.write(counted.get(0)),JsonUtils.write(counted.get(2)));assertEquals(JsonUtils.write(counted.get(1)),JsonUtils.write(counted.get(3)));assertEquals(JsonUtils.write(counted.get(2).getMessages()),JsonUtils.write(actual.get(0)));assertEquals(JsonUtils.write(counted.get(3).getMessages()),JsonUtils.write(actual.get(1)));String user=actual.get(1).get(1).getContent();String body=user.substring(user.indexOf("\nSource excerpts (untrusted data):\n")+"\nSource excerpts (untrusted data):\n".length());assertEquals(JsonUtils.write(VettingSourceMaterial.project(c)),JsonUtils.write(VettingSourcePromptWire.decode(body)),"Actual Gateway wire reconstructs full archived source metadata/cells/quality");assertTrue(user.contains("\"\",\"Room | East 😀\""));assertTrue(user.contains(note.getContent()));assertEquals(0,r.getTopicAudits().get(0).getPendingSourceRequestCount());assertEquals("observed_requests_decoded_scope_unknown",r.getTopicAudits().get(0).getAggregateReviewStatus());assertFalse(r.getTopicAudits().get(0).isSemanticScopeVerified());
    }
    @Test void observedOverBudgetNeverInvokesExtraProviderAndRetainsOverscopeLedger() {
        LlmClient provider=emptyProvider();Result r=review(corpus(),provider,in->{VettingInputBudget.Observation o=VettingInputBudgetTest.fixtureObservation(in);if(in.getMessages().get(1).getContent().contains("This original note"))o.setInputTokens(o.getEffectiveContextTokens()-o.getOutputReserveTokens()+1);return o;},false);verify(provider,times(1)).chatStructured(anyList(),any());assertEquals(1,r.getTopicAudits().get(0).getPacketAudits().size(),"Known over request is recorded before transport packet admission");assertTrue(r.getTopicAudits().get(0).getSourceRequests().stream().anyMatch(q->"over_budget".equals(q.getTransportStatus())&&"not_submitted_over_budget".equals(q.getReviewExecutionStatus())&&q.getMissingChunkIds().contains("note")));assertEquals(Collections.singleton("seed"),r.getReviewedChunkIds());assertEquals("partial",r.getTopicAudits().get(0).getAggregateReviewStatus());
    }
    @Test void extraProviderBudgetFailureIsTypedAndCannotBeHiddenBySuccessfulGlobal() {
        LlmClient provider=emptyProvider();when(provider.chatStructured(anyList(),any())).thenReturn("[]").thenThrow(new IncompleteModelResponseException("Synthetic output cap","{}",null,IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,JsonUtils.parse("{\"choices\":[{\"finish_reason\":\"length\"}],\"usage\":{\"completion_tokens\":100}}")));
        Result r=review(corpus(),provider,VettingInputBudgetTest::fixtureObservation,false);SemanticTopicVO a=r.getTopicAudits().get(0);assertEquals("completed_empty",a.getGlobalCallStatus());assertEquals("partial",a.getAggregateReviewStatus());assertEquals(1,a.getFailedPacketCount());assertEquals("output_budget_exhausted",a.getPacketAudits().get(1).getFailureKind());assertTrue(a.getSourceRequests().stream().anyMatch(q->"failed".equals(q.getReviewExecutionStatus())));assertEquals(Collections.singleton("seed"),r.getReviewedChunkIds());
    }
    @Test void modelConsistentDeclarationsNeverCancelAKnownOverBudgetRequest() {
        List<Chunk> c=new ArrayList<>(corpus());Chunk note=c.get(1);String text=note.getContent()+String.join("",Collections.nCopies(21000,"x"));note.setContent(text);note.getParts().get(0).setText(text);note.getParts().get(0).setEndOffset(text.length());LlmClient provider=emptyProvider();when(provider.chatStructured(anyList(),any())).thenReturn(declaration("consistent",c.get(0)));
        Result r=review(c,provider,input->{VettingInputBudget.Observation o=VettingInputBudgetTest.fixtureObservation(input);if(input.getMessages().get(1).getContent().contains(note.getContent()))o.setInputTokens(o.getEffectiveContextTokens()-o.getOutputReserveTokens()+1);return o;},false);SemanticTopicVO a=r.getTopicAudits().get(0);assertEquals(1,a.getConsistentAssessments());assertEquals("completed",a.getGlobalCallStatus());assertEquals("partial",a.getAggregateReviewStatus());assertTrue(a.getSourceRequests().stream().anyMatch(q->"over_budget".equals(q.getTransportStatus())&&q.getMissingChunkIds().contains("note")));assertTrue(a.getPendingSourceRequestCount()>0);verify(provider,times(1)).chatStructured(anyList(),any());
    }
    @Test void packCapRemainsPendingAfterAllEightOtherPacketsDecode() {
        List<Chunk> c=new ArrayList<>();c.add(corpus().get(0));for(int n=0;n<9;n++)c.add(c("note-"+n,"letter-"+n,null,"This source qualifies AAA.1: interim payment records are required unless inactive. "+String.join("",Collections.nCopies(11000,"x"))));LlmClient provider=emptyProvider();Result r=review(c,provider,this::requestSizedObservation,false);SemanticTopicVO a=r.getTopicAudits().get(0);assertEquals(8,a.getExtraPacketCount());assertEquals(9,r.getActualGatewayCallCount());verify(provider,times(9)).chatStructured(anyList(),any());assertTrue(a.getSourceRequests().stream().anyMatch(q->"omitted_pack_cap".equals(q.getTransportStatus())));assertTrue(a.getPendingSourceRequestCount()>0);assertEquals("partial",a.getAggregateReviewStatus());
    }
    @Test void anotherPacketsChunkCannotPassCurrentActualSchemaAndEvidenceGate() {
        List<Chunk> c=new ArrayList<>();c.add(corpus().get(0));for(int n=0;n<2;n++)c.add(c("note-"+n,"letter-"+n,null,"This source qualifies AAA.1: interim payment records are required unless inactive. "+String.join("",Collections.nCopies(11000,"x"))));LlmClient provider=emptyProvider();when(provider.chatStructured(anyList(),any())).thenReturn("[]",declaration("issue",c.get(2)),"[]");Result r=review(c,provider,this::requestSizedObservation,false);assertTrue(r.getCandidates().isEmpty());SemanticTopicVO a=r.getTopicAudits().get(0);assertEquals(1,a.getFailedPacketCount());assertEquals("structured_output_invalid",a.getPacketAudits().get(1).getFailureKind());assertFalse(r.getReviewedChunkIds().contains("note-0"));assertTrue(r.getReviewedChunkIds().contains("note-1"));assertEquals("partial",a.getAggregateReviewStatus());
    }
    @Test void probePersistsBoundPacketInputsTypedNotSubmissionAndAuthoritativeCoverage() throws Exception {
        LlmClient provider=emptyProvider();Result r=review(corpus(),provider,null,true);List<JsonNode> events;
        try(Stream<Path> stream=Files.list(temp.resolve("packet-fixture"))){events=stream.filter(p->p.getFileName().toString().matches("[0-9]{5}-.*\\.json")).map(p->{try{return JsonUtils.parse(new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new RuntimeException(e);}}).collect(Collectors.toList());}
        assertTrue(events.stream().anyMatch(e->"source_request_packet_plan".equals(e.path("phase").asText())&&e.path("observations").path("tokenPacking").path("attempts").toString().contains("budget_unknown")&&e.path("observations").path("plan").path("extraPacks").size()==0));assertTrue(events.stream().anyMatch(e->"topic_finished".equals(e.path("phase").asText())&&e.path("observations").path("aggregateReviewStatusAuthoritative").asBoolean()&&"partial".equals(e.path("observations").path("topicAudit").path("aggregateReviewStatus").asText())));assertTrue(events.stream().anyMatch(e->"packet_complete_serialized_input".equals(e.path("phase").asText())));assertEquals(1,r.getActualGatewayCallCount());
    }
    private String reference(Chunk origin,Chunk target) {
        Map<String,Object> r=new LinkedHashMap<>();r.put("assessment","issue");r.put("type","reference");r.put("severity","medium");r.put("title","Synthetic reference comparison");r.put("comment","Two source provisions require review");r.put("impact","Review source scope");r.put("suggestion","Check original passages");List<Map<String,Object>> evidence=new ArrayList<>();
        for(Chunk c:Arrays.asList(origin,target)){Map<String,Object> q=new LinkedHashMap<>();q.put("chunkId",c.getId());q.put("side","source");q.put("quote",c.getContent());evidence.add(q);}r.put("evidence",evidence);return JsonUtils.write(Collections.singletonList(r));
    }
    @Test void anIndependentRankedExactReferenceCanClearOnlyItsBoundExtraPacketScope() {
        Chunk seed=corpus().get(0),origin=c("independent-origin","equipment","AAA.3","Machinery operating records shall follow AAA.2."),target=c("exact-target","equipment","AAA.2","The referenced provision describes water testing records and their approval procedure.");List<Chunk> c=Arrays.asList(seed,origin,target);LlmClient provider=emptyProvider();
        when(provider.chatStructured(anyList(),any())).thenAnswer(call->{List<LlmClient.ChatTurn> turns=call.getArgument(0);String user=turns.get(1).getContent();return user.contains(origin.getContent())&&user.contains(target.getContent())?reference(origin,target):"[]";});
        Result r=review(c,Arrays.asList(seed,origin),provider,VettingInputBudgetTest::fixtureObservation,false);assertEquals(1,r.getCandidates().size());assertEquals("completed_empty",r.getTopicAudits().get(0).getGlobalCallStatus());assertFalse(r.getTopicAudits().get(0).getPacketAudits().get(0).getSubmittedChunkIds().contains(origin.getId()),"Original unadmitted origin remains absent from global transport");assertEquals(1,r.getCandidates().get(0).getEvidence().stream().map(FindingEvidence::getPacketId).distinct().count());assertTrue(r.getCandidates().get(0).getPacketId().contains("packet-1-input-"),"Candidate is bound to independently observed packet input");assertTrue(r.getTopicAudits().get(0).getSourceRequests().stream().anyMatch(q->"outgoing_literal".equals(q.getKind())&&q.getSourceTrace().path("resolutionMode").asText().equals("exact_clause")&&"decoded".equals(q.getReviewExecutionStatus())));
    }
    @Test void wholeParentTransportDoesNotTurnAnUnverifiedSubclauseIntoExactPacketScope() {
        Chunk seed=corpus().get(0),origin=c("independent-origin","equipment","AAA.3","Machinery operating records shall follow AAA.2(6)."),target=c("parent-target","equipment","AAA.2","The observed parent provision describes water testing records and their approval procedure.");List<Chunk> c=Arrays.asList(seed,origin,target);LlmClient provider=emptyProvider();
        when(provider.chatStructured(anyList(),any())).thenAnswer(call->{List<LlmClient.ChatTurn> turns=call.getArgument(0);String user=turns.get(1).getContent();return user.contains(origin.getContent())&&user.contains(target.getContent())?reference(origin,target):"[]";});
        Result r=review(c,Arrays.asList(seed,origin),provider,VettingInputBudgetTest::fixtureObservation,false);assertTrue(r.getCandidates().isEmpty());assertTrue(r.getTopicAudits().get(0).getPacketAudits().stream().anyMatch(p->p.getRejectedRecords()>0));assertTrue(r.getTopicAudits().get(0).getSourceRequests().stream().anyMatch(q->"outgoing_literal".equals(q.getKind())&&q.getSourceTrace().path("resolutionMode").asText().equals("parent_fallback")));assertFalse(r.getTopicAudits().get(0).isSemanticScopeVerified());
    }

    @Test void knownGlobalInputOverBudgetNeverStartsAnyProviderCall() {
        LlmClient provider=emptyProvider();Result r=review(corpus(),provider,in->{VettingInputBudget.Observation o=VettingInputBudgetTest.fixtureObservation(in);o.setEffectiveContextTokens(199);return o;},false);
        verify(provider,never()).chatStructured(anyList(),any());assertEquals(0,r.getActualGatewayCallCount());assertTrue(r.getReviewedChunkIds().isEmpty());assertEquals("not_submitted_over_budget",r.getTopicAudits().get(0).getGlobalCallStatus());assertEquals("not_submitted",r.getTopicAudits().get(0).getAggregateReviewStatus());assertTrue(r.getTopicAudits().get(0).getPendingSourceRequestCount()>0);
    }

    @Test void previewAndFinalObservationDriftRejectsAllBoundPacketsBeforeGateway() {
        Map<String,Integer> seen=new LinkedHashMap<>();LlmClient provider=emptyProvider();
        Result r=review(corpus(),provider,in->{int n=seen.merge(in.getSerializedInputSha256(),1,Integer::sum);VettingInputBudget.Observation o=VettingInputBudgetTest.fixtureObservation(in);if(n>1)o.setTokenizerIdentitySha256(VettingCorpus.hash("changed-synthetic-tokenizer"));return o;},false);
        verify(provider,never()).chatStructured(anyList(),any());assertEquals(0,r.getActualGatewayCallCount());assertTrue(seen.values().stream().allMatch(n->n==2),"Each admitted preview must be freshly counted before dispatch");assertTrue(r.getTopicAudits().get(0).getPacketAudits().stream().allMatch(p->"not_submitted_budget_unknown".equals(p.getStatus())&&"token_preview_final_observation_changed".equals(p.getError())));assertTrue(r.getTopicAudits().get(0).getPendingSourceRequestCount()>0);assertEquals("not_submitted",r.getTopicAudits().get(0).getAggregateReviewStatus());
    }

}
