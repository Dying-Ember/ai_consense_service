package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** WWQS 01:49:44-01:50:52: preserve standard wording, review project additions. */
class VettingStandardLanguageBoundaryTest {
    @Test void unchangedStandardGrammarDoesNotBecomeAProjectLanguageFinding() {
        assertTrue(review("The Contractor are responsible for the Works.", "language").getCandidates().isEmpty());
    }
    @Test void differentStandardFragmentDoesNotConfirmProjectAddedWording() {
        VettingSemanticReview.Result result=review("The Contractor are responsible for the additional harbour inspections.", "language");
        assertTrue(result.getCandidates().isEmpty());
        assertTrue(result.getWarnings().stream().anyMatch(s->s.contains("项目新增或改写未确认")));
    }
    @Test void standardWordingDoesNotSuppressSubstantiveRiskReview() {
        assertEquals(1, review("The Contractor are responsible for the Works.", "risk").getCandidates().size());
    }
    private VettingSemanticReview.Result review(String adopted, String type) {
        AiGateway ai = mock(AiGateway.class);
        when(ai.available()).thenReturn(true); when(ai.chatModel()).thenReturn("fixture-only");
        ConsenseProperties props = new ConsenseProperties(); props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval = mock(VettingRetrievalClient.class);
        Chunk tender = chunk("adopted", "tender", adopted);
        Chunk standard = chunk("baseline", "standard", "The Contractor are responsible for the Works.");
        when(retrieval.retrieve(eq("p"), anyString(), eq("tender"), anyList())).thenReturn(Collections.singletonList(tender));
        when(retrieval.retrieve(eq("p"), anyString(), eq("standard"), anyList())).thenReturn(Collections.singletonList(standard));
        Record record = new Record(); record.setAssessment("issue"); record.setType(type);
        record.setTitle("Review source wording"); record.setComment("Confirm this source wording.");
        Quote quote = new Quote(); quote.setChunkId("adopted"); quote.setQuote(adopted);
        record.setEvidence(Collections.singletonList(quote));
        when(ai.completeStructuredJsonList(anyString(), anyString(), eq(Record.class), any(), anyList()))
            .thenReturn(Collections.singletonList(record));
        return VettingTokenFixture.review(ai, props, retrieval).review("p", Arrays.asList(tender, standard), "en", (n,s)->{});
    }
    private Chunk chunk(String id, String role, String text) {
        Chunk c = new Chunk(); c.setId(id); c.setDocumentId(id); c.setSourceHash("fixture-hash");
        c.setFileName(id + ".docx"); c.setFileKey("SCC"); c.setClauseId("SCC81.4");
        c.setRole(role); c.setAnchor("body/11/paragraph"); c.setContent(text);
        return c;
    }
}
