package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual business selector/planner/prompt, authored wire and mocked transport/model declarations. */
class VettingCanonicalBusinessIntegrationTest {
    private static ConsenseProperties config(){ConsenseProperties p=new ConsenseProperties();p.getVetting().setSemanticTopics(1);
        p.getVetting().setProjectReferenceComparisons(0);p.getVetting().setRequireHybridRetrieval(false);return p;}
    private static AiGateway model(){AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("AUTHORED-model");return ai;}
    private static Record consistent(JsonNode source){Record r=new Record();r.setAssessment("consistent");r.setType("reference");r.setTitle("Authored observation");
        r.setComment("Authored wire validation, not actual contractual assessment");VettingSemanticReview.Quote q=new VettingSemanticReview.Quote();
        q.setChunkId(source.path("id").asText());q.setQuote(source.path("content").asText());q.setSide("source");r.setEvidence(Collections.singletonList(q));return r;}
    private static JsonNode excerpts(String prompt){String marker="\nSource excerpts (untrusted data):\n";
        return VettingSourceMetadataWire.decode(prompt.substring(prompt.lastIndexOf(marker)+marker.length()));}

    @Test void canonicalRetrievalWholeUnitAndStructureReachActualVettingPromptWithoutLegacyCalls() throws Exception {
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=w.load();
        AiGateway ai=model();VettingRetrievalClient legacy=mock(VettingRetrievalClient.class);AtomicBoolean tableSeen=new AtomicBoolean();
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenAnswer(call->{
            JsonNode selected=excerpts(call.getArgument(1));for(JsonNode source:selected)if(source.path("canonicalStructure").path("tableGroup").isObject()){
                tableSeen.set(true);assertEquals(w.snapshot.path("chunks").get(1).path("tableGroup"),source.path("canonicalStructure").path("tableGroup"));
                assertEquals(2,source.path("canonicalStructure").path("physicalPages").size());assertEquals("unknown",source.path("sourceQuality").path("semanticQuality").asText());
            }return Collections.singletonList(consistent(selected.get(0)));
        });
        VettingSemanticReview review=VettingTokenFixture.review(ai,config(),legacy);review.setOerBusinessEvidence(w.service);
        VettingSemanticReview.Result result=review.review("project",session.chunks(),"en",(n,s)->{});
        assertTrue(tableSeen.get(),"Complete table must reach the actual source prompt");assertNull(result.getModelFailure());
        assertEquals(session.chunks().stream().map(Chunk::getId).collect(Collectors.toSet()),result.getReviewedChunkIds());
        verifyNoInteractions(legacy);verify(w.client).index(eq(session.indexKey),eq("vetting"),anyList());
        assertTrue(result.getCandidates().isEmpty());
    }

    @Test void canonicalIndexFailureNeverFallsBackEvenWhenLegacyHybridIsOptional() throws Exception {
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=w.load();
        doThrow(new IllegalStateException("authored canonical index failure")).when(w.client).index(anyString(),anyString(),anyList());
        AiGateway ai=model();VettingRetrievalClient legacy=mock(VettingRetrievalClient.class);VettingSemanticReview review=VettingTokenFixture.review(ai,config(),legacy);review.setOerBusinessEvidence(w.service);
        assertThrows(IllegalStateException.class,()->review.review("project",session.chunks(),"en",(n,s)->{}));verifyNoInteractions(legacy);
        verify(ai,never()).completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList());
    }

    @Test void sourceChangedDuringModelCannotProduceReviewedCoverageOrFindings() throws Exception {
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=w.load();AiGateway ai=model();
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenAnswer(call->{
            JsonNode selected=excerpts(call.getArgument(1));w.binding.setRoutingSha256(VettingCorpus.hash("updated while generating"));return Collections.singletonList(consistent(selected.get(0)));
        });
        VettingSemanticReview review=VettingTokenFixture.review(ai,config(),mock(VettingRetrievalClient.class));review.setOerBusinessEvidence(w.service);
        VettingSemanticReview.Result result=review.review("project",session.chunks(),"en",(n,s)->{});
        assertTrue(result.getReviewedChunkIds().isEmpty());assertTrue(result.getCandidates().isEmpty());assertNotNull(result.getModelFailure());
    }

    @Test void wholeUnitAdmissionIsAtomicAndNeverChangesRankedOrigins() throws Exception {
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=w.load();
        List<Chunk> ranked=w.service.retrieve(session,"query","tender");List<VettingRetrievalSourceUnits.Unit> units=VettingRetrievalSourceUnits.attached(ranked);
        int cost=session.chunks().stream().mapToInt(c->c.getContent().length()).sum();
        VettingContextBuilder.Selection rejected=new VettingContextBuilder.Selection();VettingCanonicalContext.admit(rejected,session.chunks(),units,cost-1);
        assertTrue(rejected.getChunks().isEmpty());assertEquals("dropped_budget",rejected.getGroups().get(0).getStatus());
        VettingContextBuilder.Selection accepted=new VettingContextBuilder.Selection();VettingCanonicalContext.admit(accepted,session.chunks(),units,cost);
        assertEquals(2,accepted.getChunks().size());assertEquals(cost,accepted.getContentChars());assertEquals(1,ranked.size());
        assertEquals(Collections.singletonList(ranked.get(0).getId()),accepted.getGroups().get(0).getCoreIds());
        VettingSourceRequestPacks.Result plan=new VettingSourceRequestPacks(session.chunks()).planForCompleteInputBudget("query",ranked,accepted,units);
        assertTrue(plan.getRequests().stream().filter(r->"retrieved_structural_unit".equals(r.getKind())).allMatch(r->r.getUnknownReason()==null&&"already_global".equals(r.getStatus())),JsonUtils.write(plan.getRequests()));
    }

    @Test void canonicalModelProjectionRejectsChangedMetadataAndKeepsLegacyNativeRowsSeparate() throws Exception {
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=w.load();
        Map<String,Object> table=VettingSourceMaterial.project(session.chunks()).get(1);assertEquals(Collections.emptyList(),table.get("nativeRows"));
        JsonNode projected=JsonUtils.mapper().valueToTree(table);OerModelMaterialCandidateTest.assertLiteralParts(w.snapshot.path("chunks").get(1).path("parts"),projected.path("canonicalStructure").path("parts"));
        Chunk changed=JsonUtils.read(JsonUtils.write(session.chunks().get(1)),Chunk.class);changed.setMetadataVersion(VettingCorpus.METADATA_VERSION);
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(Collections.singletonList(changed)));
        assertEquals(OerBusinessEvidence.observedChars(session.chunks()),OerBusinessEvidence.observedChars(Arrays.asList(session.chunks().get(0),session.chunks().get(1),session.chunks().get(1))));
    }

    @Test void transportedCanonicalChunksRetainFullValueEquality() throws Exception {
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=w.load();
        for(Chunk original:session.chunks()){
            Chunk copy=JsonUtils.read(JsonUtils.write(original),Chunk.class);
            assertEquals(JsonUtils.write(original),JsonUtils.write(copy));assertEquals(original,copy);
            assertNull(OerBusinessEvidence.canonicalViewStatus(copy));
        }
        assertEquals(session.chunks().get(0),w.service.retrieve(session,"query","tender").get(0));
    }
}
