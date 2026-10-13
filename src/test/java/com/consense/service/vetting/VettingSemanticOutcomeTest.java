package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Result;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VettingSemanticOutcomeTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"risk,consistent","language,consistent","risk,not_applicable","language,not_applicable"})
    void aMissingNecessaryMemberRejectsOnlyItsDependentAssessment(String type,String assessment){
        Chunk affected=VettingContextBudgetTest.chunk("affected","XYZ1","Except as specified in XYZ99(2), interim payment shall not apply to this contract.",150);
        Chunk reliable=VettingContextBudgetTest.chunk("reliable","XYZ2","The contractor shall keep the performance security valid throughout the contract.",150);
        VettingSemanticReview.Record dependent=reference(affected,affected);dependent.setType(type);dependent.setAssessment(assessment);dependent.setComment("This scoped provision is consistent or explicitly excluded, subject to its referenced exception.");
        VettingSemanticReview.Record independent=reference(reliable,reliable);independent.setType("risk");independent.setComment("Reliable independent scope requires business review.");
        AiGateway ai=response(dependent);when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenReturn(Arrays.asList(dependent,independent));
        List<Chunk> chunks=Arrays.asList(affected,reliable);Result result=review(ai,properties(),chunks,chunks);
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=result.getTopicAudits().get(0).getPacketAudits().get(0);
        assertEquals(1,packet.getRejectedRecords());assertEquals(0,packet.getVerifiedConsistentAssessments());assertEquals(0,packet.getVerifiedNotApplicableAssessments());assertTrue(packet.getVerifiedAssessments().isEmpty());
        assertEquals(1,result.getCandidates().size());assertEquals(reliable.getContent().trim(),result.getCandidates().get(0).getEvidence().get(0).getQuote());
        assertEquals("issue",packet.getCheckState(),"An unrelated reliable assessment continues despite the missing member in A");
    }
    @Test void outputBudgetExhaustionHasItsOwnOutcomeAndNeverClaimsReviewedCoverage() {
        HttpSupport http = mock(HttpSupport.class);
        when(http.get(anyString(),anyLong())).thenReturn("{\"data\":[]}");
        when(http.postJson(anyString(),anyString(),anyLong(),isNull(),anyInt())).thenReturn(
                "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":1024,\"completion_tokens_details\":{\"reasoning_tokens\":1023}},\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"reasoning_content\":\"untrusted hidden text\"}}]}");
        ConsenseProperties props = properties();
        Chunk source = VettingContextBudgetTest.chunk("a","XYZ1","The original requirement shall be retained.",100);
        Result result = review(new AiGateway(new OpenAiLlmClient(props.getLlm(),http)),props,Collections.singletonList(source),Collections.singletonList(source));
        assertEquals("failed",result.getTopicAudits().get(0).getStatus());
        assertEquals("not_completed",result.getTopicAudits().get(0).getCheckState());
        assertEquals("output_budget_exhausted",result.getTopicAudits().get(0).getFailureKind());
        assertNotNull(result.getTopicAudits().get(0).getModelResponseMetadata());
        assertFalse(result.getTopicAudits().get(0).getModelResponseMetadata().toString().contains("untrusted hidden text"));
        assertNull(result.getTopicAudits().get(0).getRawResponseSha256());
        assertTrue(result.getReviewedChunkIds().isEmpty()); assertTrue(result.getCandidates().isEmpty());
        verify(http,times(1)).postJson(anyString(),anyString(),anyLong(),isNull(),eq(0));
    }

    @Test void anActualEmptyArrayRemainsACompletedEmptyAssessmentWithNoFinding() {
        HttpSupport http = mock(HttpSupport.class);
        when(http.get(anyString(),anyLong())).thenReturn("{\"data\":[]}");
        when(http.postJson(anyString(),anyString(),anyLong(),isNull(),anyInt())).thenReturn(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}]}");
        ConsenseProperties props = properties();
        Chunk source = VettingContextBudgetTest.chunk("a","XYZ1","The original requirement shall be retained.",100);
        Result result = review(new AiGateway(new OpenAiLlmClient(props.getLlm(),http)),props,Collections.singletonList(source),Collections.singletonList(source));
        assertEquals("completed_empty",result.getTopicAudits().get(0).getStatus());
        assertEquals("not_completed",result.getTopicAudits().get(0).getCheckState(),"An empty model array is no explicit pass assessment");
        assertNull(result.getTopicAudits().get(0).getFailureKind()); assertEquals(0,result.getTopicAudits().get(0).getAcceptedFindings());
        assertEquals(Collections.singleton("a"),result.getReviewedChunkIds()); assertTrue(result.getCandidates().isEmpty());
    }

    @Test void wrappedEmptyResultIsAnInvalidOutputNotACompletedEmptyAssessment() {
        HttpSupport http = mock(HttpSupport.class);
        when(http.get(anyString(),anyLong())).thenReturn("{\"data\":[]}");
        when(http.postJson(anyString(),anyString(),anyLong(),isNull(),anyInt())).thenReturn(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"items\\\":[]}\"}}]}");
        ConsenseProperties props = properties();
        Chunk source = VettingContextBudgetTest.chunk("a","XYZ1","The original requirement shall be retained.",100);
        Result result = review(new AiGateway(new OpenAiLlmClient(props.getLlm(),http)),props,Collections.singletonList(source),Collections.singletonList(source));
        assertEquals("failed",result.getTopicAudits().get(0).getStatus());
        assertEquals("structured_output_invalid",result.getTopicAudits().get(0).getFailureKind());
        assertTrue(result.getReviewedChunkIds().isEmpty()); assertTrue(result.getCandidates().isEmpty());
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=result.getTopicAudits().get(0).getPacketAudits().get(0);
        assertEquals(2,packet.getAttempts().size());assertTrue(packet.getAttempts().stream().allMatch(a->"structured_output_invalid".equals(a.getStatus())&&"SCHEMA_MISMATCH".equals(a.getValidationReason())));
        assertEquals(2,result.getActualGatewayCallCount());
        verify(http,times(2)).postJson(anyString(),anyString(),anyLong(),isNull(),eq(0));
    }

    @Test void theProductionReviewUsesExpandedWindowAndKeepsUnknownSubclauseScope() {
        ConsenseProperties props = properties(); props.getVetting().setSemanticContextChars(10000);
        List<Chunk> corpus = VettingContextBudgetTest.parent(4000);
        AiGateway ai = mock(AiGateway.class); when(ai.available()).thenReturn(true); when(ai.chatModel()).thenReturn("fixture");
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call -> {
            String prompt = call.getArgument(1);
            assertTrue(prompt.contains("referenced subsection (6)")); assertTrue(prompt.contains("\"clauseHeadingLocation\""));
            assertTrue(prompt.contains("qualifiersComplete=unknown")); return Collections.emptyList();
        });
        Result result = review(ai,props,corpus,Collections.singletonList(corpus.get(0)));
        assertTrue(result.getTopicAudits().get(0).isContextExpanded()); assertTrue(result.getTopicAudits().get(0).isContextExpansionAttempted());
        assertEquals(20000,result.getTopicAudits().get(0).getEffectiveContextBudgetChars());
        assertEquals(10000,result.getTopicAudits().get(0).getInitialContextBudgetChars());
        assertEquals(0,result.getTopicAudits().get(0).getMissingLocatedReferenceTargets());
        assertTrue(result.getTopicAudits().get(0).getUnresolvedChunkIds().contains("a"));
        assertEquals(3,result.getReviewedChunkIds().size());
    }

    @Test void aBareAppendixLabelCannotTurnAnAbsenceDeclarationIntoAReferenceFinding() {
        ConsenseProperties props=properties();
        Chunk origin=VettingContextBudgetTest.chunk("origin","XYZ1","The implementation details shall follow APPENDIX XYZ/A.",70);
        Chunk label=VettingContextBudgetTest.chunk("label","XYZ2","APPENDIX XYZ/A",15);
        List<Chunk> chunks=Arrays.asList(origin,label);
        Result result=review(response(reference(origin,label)),props,chunks,chunks);
        assertEquals("completed_with_rejections",result.getTopicAudits().get(0).getStatus());
        assertEquals(1,result.getTopicAudits().get(0).getRejectedRecords());
        assertTrue(result.getCandidates().isEmpty());
    }

    @Test void aReferenceIssueWithTwoSubstantiveSourcePassagesRemainsEligibleForReview() {
        ConsenseProperties props=properties();
        Chunk origin=VettingContextBudgetTest.chunk("origin","XYZ1","The implementation details shall follow APPENDIX XYZ/A.",70);
        Chunk target=VettingContextBudgetTest.chunk("target","XYZ2","This appendix records flushing results and does not describe the referenced functions.",100);
        List<Chunk> chunks=Arrays.asList(origin,target);
        Result result=review(response(reference(origin,target)),props,chunks,chunks);
        assertEquals("completed",result.getTopicAudits().get(0).getStatus());
        assertEquals(1,result.getCandidates().size());
        assertEquals("issue",result.getTopicAudits().get(0).getCheckState());
    }

    @Test void onlyEvidenceVerifiedDeclarationsCanBecomePassOrInsufficientEvidence() {
        Chunk source=VettingContextBudgetTest.chunk("source","SCC","The contractor shall retain insurance throughout the contract.",80);
        for(String assessment:Arrays.asList("consistent","insufficient_context")){
            VettingSemanticReview.Record record=reference(source,source);record.setAssessment(assessment);record.setType("risk");
            Result result=review(response(record),properties(),Collections.singletonList(source),Collections.singletonList(source));
            assertEquals("consistent".equals(assessment)?"checked_pass":"insufficient_evidence",result.getTopicAudits().get(0).getPacketAudits().get(0).getCheckState());assertTrue(result.getCandidates().isEmpty());
            record.getEvidence().get(0).setQuote("A quotation absent from the original source.");
            Result rejected=review(response(record),properties(),Collections.singletonList(source),Collections.singletonList(source));assertEquals("not_completed",rejected.getTopicAudits().get(0).getCheckState());
        }
    }

    @Test void decodingAndPromptDoNotReintroduceAThreeFindingCap() {
        assertFalse(VettingOutputSchema.forChunkIds(Collections.singleton("source")).has("maxItems"));
    }

    @Test void explicitNotApplicableKeepsItsScopeEvidenceAndCannotHideUnknownCoverage(){
        Chunk source=VettingContextBudgetTest.chunk("source","SCC","Interim payment under GCC clause 14.2 shall not apply to this contract; the stated fixed payment schedule governs.",140);
        VettingSemanticReview.Record record=reference(source,source);record.setAssessment("not_applicable");record.setType("risk");record.setTitle("Interim payment exclusion");record.setComment("The quoted project clause explicitly excludes this interim-payment provision from this contract.");
        Result result=review(response(record),properties(),Collections.singletonList(source),Collections.singletonList(source));
        com.consense.web.dto.VettingDtos.SemanticTopicVO topic=result.getTopicAudits().get(0);com.consense.web.dto.VettingDtos.SemanticPacketVO packet=topic.getPacketAudits().get(0);
        assertEquals("not_applicable",packet.getCheckState());assertEquals(1,packet.getVerifiedNotApplicableAssessments());assertTrue(result.getCandidates().isEmpty());
        assertEquals(record.getComment(),packet.getVerifiedAssessments().get(0).getComment());assertEquals(source.getSourceHash(),packet.getVerifiedAssessments().get(0).getEvidence().get(0).getSourceHash());assertEquals(packet.getPacketId(),packet.getVerifiedAssessments().get(0).getEvidence().get(0).getPacketId());
        assertTrue(topic.getPendingSourceRequestCount()>0);assertEquals("not_completed",topic.getCheckState(),"Scoped NA cannot mark an unknown topic complete");
    }

    @Test void notApplicableWithoutAValidLocatedScopeCannotBecomeAnAutomaticState(){
        Chunk source=VettingContextBudgetTest.chunk("source","SCC","Interim payment shall not apply to this contract; the fixed payment schedule governs.",120);
        VettingSemanticReview.Record record=reference(source,source);record.setAssessment("not_applicable");record.setType("risk");record.getEvidence().get(0).setQuote("There is no matching exclusion in the supplied project source.");
        Result absent=review(response(record),properties(),Collections.singletonList(source),Collections.singletonList(source));assertEquals("not_completed",absent.getTopicAudits().get(0).getPacketAudits().get(0).getCheckState());assertEquals(0,absent.getTopicAudits().get(0).getVerifiedNotApplicableAssessments());
        record.getEvidence().get(0).setQuote(source.getContent());source.setSourceHash(null);
        Result unidentified=review(response(record),properties(),Collections.singletonList(source),Collections.singletonList(source));assertEquals("not_completed",unidentified.getTopicAudits().get(0).getPacketAudits().get(0).getCheckState());assertEquals(0,unidentified.getTopicAudits().get(0).getVerifiedNotApplicableAssessments());
    }

    private static AiGateway response(VettingSemanticReview.Record record) {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("fixture");
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenReturn(Collections.singletonList(record));
        return ai;
    }
    private static VettingSemanticReview.Record reference(Chunk first,Chunk second) {
        VettingSemanticReview.Record r=new VettingSemanticReview.Record();r.setAssessment("issue");r.setType("reference");r.setSeverity("medium");
        r.setTitle("Check reference");r.setComment("The quoted reference requires source verification.");
        List<VettingSemanticReview.Quote> quotes=new ArrayList<>();
        for(Chunk c:Arrays.asList(first,second)){VettingSemanticReview.Quote q=new VettingSemanticReview.Quote();q.setChunkId(c.getId());q.setSide("source");q.setQuote(c.getContent().trim());quotes.add(q);}
        r.setEvidence(quotes);return r;
    }

    private static ConsenseProperties properties() {
        ConsenseProperties p = new ConsenseProperties(); p.getVetting().setSemanticTopics(1); p.getVetting().setProjectReferenceComparisons(0); return p;
    }
    private static Result review(AiGateway ai, ConsenseProperties props,List<Chunk> corpus,List<Chunk> ranked) {
        VettingRetrievalClient retrieval = mock(VettingRetrievalClient.class);
        when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList())).thenReturn(ranked);
        Result result = VettingTokenFixture.review(ai,props,retrieval).review("fixture",corpus,"en",(n,s)->{});
        verify(retrieval,times(1)).index("fixture",corpus);
        verify(retrieval,times(1)).retrieve(eq("fixture"),anyString(),eq("tender"),eq(corpus));
        return result;
    }
}
