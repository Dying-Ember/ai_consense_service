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
    @Test void outputBudgetExhaustionHasItsOwnOutcomeAndNeverClaimsReviewedCoverage() {
        HttpSupport http = mock(HttpSupport.class);
        when(http.get(anyString(),anyLong())).thenReturn("{\"data\":[]}");
        when(http.postJson(anyString(),anyString(),anyLong(),isNull(),anyInt())).thenReturn(
                "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":1024,\"completion_tokens_details\":{\"reasoning_tokens\":1023}},\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"reasoning_content\":\"untrusted hidden text\"}}]}");
        ConsenseProperties props = properties();
        Chunk source = VettingContextBudgetTest.chunk("a","XYZ1","The original requirement shall be retained.",100);
        Result result = review(new AiGateway(new OpenAiLlmClient(props.getLlm(),http)),props,Collections.singletonList(source),Collections.singletonList(source));
        assertEquals("failed",result.getTopicAudits().get(0).getStatus());
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
        verify(http,times(1)).postJson(anyString(),anyString(),anyLong(),isNull(),eq(0));
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
        Result result = new VettingSemanticReview(ai,props,retrieval).review("fixture",corpus,"en",(n,s)->{});
        verify(retrieval,times(1)).index("fixture",corpus);
        verify(retrieval,times(1)).retrieve(eq("fixture"),anyString(),eq("tender"),eq(corpus));
        return result;
    }
}
