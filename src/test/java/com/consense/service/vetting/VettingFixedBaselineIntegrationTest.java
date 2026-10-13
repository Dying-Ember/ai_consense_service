package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.vector.VectorStore.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VettingFixedBaselineIntegrationTest {
    @Test void fixedCatalogSurvivesTheExistingChunkCopiesAndKeepsRawMetadataOutOfModelMaterial() {
        Fixture fixture = new Fixture();
        FixedVettingReferences catalog = FixedVettingReferences.retrieve(fixture.client,"payment",10);
        Chunk copied = JsonUtils.read(JsonUtils.write(catalog.chunks().get(0)),Chunk.class);
        List<Map<String,Object>> excerpts = VettingSourceMaterial.project(Collections.singletonList(copied),catalog);
        assertEquals("standard",excerpts.get(0).get("role"));
        assertEquals("fixed_development_baseline",excerpts.get(0).get("evidenceBackend"));
        assertEquals("unconfirmed_requires_real_project_adoption",excerpts.get(0).get("projectApplicability"));
        assertEquals(false,excerpts.get(0).get("sourceQualityAccepted"));
        assertEquals("unknown",excerpts.get(0).get("qualifiersComplete"));
        assertEquals(fixture.standard.getContent(),excerpts.get(0).get("content"));
        assertFalse(JsonUtils.write(excerpts).contains("secret-raw-layout-audit"));
        assertFalse(JsonUtils.write(copied).contains("secret-raw-layout-audit"));
        assertNull(copied.getCanonicalEvidence());assertNull(copied.getCanonicalPayload());
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(Collections.singletonList(copied)));
        copied.setContent("Changed reference wording that did not come from the frozen baseline.");
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(Collections.singletonList(copied),catalog));
    }

    @Test void fixedReferencesJoinTheTopicCitationCatalogWithoutBecomingProjectDefectsOrProjectIndexPoints() {
        Fixture fixture = new Fixture();
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
        props.getVetting().setSemanticContextChars(20000);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk tender=chunk("project-tender","tender","SCT","SCT8.1","The interim payment certificate minimum shall be checked under GCC clause 14.2.");
        when(retrieval.retrieve(eq("project"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(tender));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenAnswer(call->{
            String prompt=call.getArgument(1), marker="\nSource excerpts (untrusted data):\n";
            JsonNode excerpts=VettingSourceMetadataWire.decode(prompt.substring(prompt.lastIndexOf(marker)+marker.length()));
            JsonNode fixed=null;
            for(JsonNode row:excerpts)if(fixture.standard.getId().equals(row.path("id").asText()))fixed=row;
            assertNotNull(fixed,"The fixed ID must be supplied in the same packet's citation catalog");
            assertEquals("standard",fixed.path("role").asText());
            assertFalse(fixed.path("sourceQualityAccepted").asBoolean(true));
            assertTrue(fixed.path("projectApplicability").asText().contains("unconfirmed"));
            Record standardOnly=record("issue","risk",quote(fixture.standard));
            Record unconfirmedConflict=record("issue","conflict",quote(tender),quote(fixture.standard));
            Record context=record("insufficient_context","risk",quote(tender),quote(fixture.standard));
            return Arrays.asList(standardOnly,unconfirmedConflict,context);
        });
        VettingSemanticReview review=VettingTokenFixture.review(ai,props,retrieval);review.setFixedBaselineRetrievalClient(fixture.client);
        VettingSemanticReview.Result result=review.review("project",Collections.singletonList(tender),"en",(n,s)->{});
        assertTrue(result.getCandidates().isEmpty(),"Fixed references alone and unconfirmed project adoption cannot establish a project defect");
        assertEquals(1,result.getActualGatewayCallCount());
        assertTrue(result.getReviewedChunkIds().contains(fixture.standard.getId()));
        assertEquals(2,result.getTopicAudits().get(0).getRejectedRecords());
        JsonNode savedPointer=result.getTopicAudits().get(0).getPacketAudits().get(0).getFixedBaselineEvidence().get(0);
        assertEquals("fixed_development_baseline",savedPointer.path("evidenceBackend").asText());assertEquals(fixture.standard.getId(),savedPointer.path("parentId").asText());
        assertEquals("unconfirmed",savedPointer.path("projectApplicability").asText());assertEquals("unknown",savedPointer.path("qualifiersComplete").asText());
        assertFalse(savedPointer.toString().contains("secret-raw-layout-audit"));
        verify(retrieval).index(eq("project"),argThat(chunks->chunks.size()==1&&"tender".equals(chunks.get(0).getRole())));
        verify(fixture.client).search(eq("vetting"),anyString(),eq(props.getVetting().getTopK()));
        // First attempt checks fixed sources before checkpoint access, immediately before dispatch, and after decoding.
        verify(fixture.client,times(3)).assertCurrent(argThat(hits->hits.size()==1&&fixture.hit.getId().equals(hits.get(0).getId())));
    }

    @Test void configuredCanonicalProjectFailureCannotBeBypassedByTheFixedReferenceLane() {
        Fixture fixture=new Fixture();
        OerBusinessEvidence oer=mock(OerBusinessEvidence.class);when(oer.enabled()).thenReturn(true);
        VettingSemanticReview review=VettingTokenFixture.review(mock(AiGateway.class),new ConsenseProperties(),mock(VettingRetrievalClient.class));
        review.setOerBusinessEvidence(oer);review.setFixedBaselineRetrievalClient(fixture.client);
        assertThrows(IllegalStateException.class,()->review.review("project",Collections.singletonList(chunk("p","tender","SCT","SCT1","The project source shall be verified before any model operation.")),"en",(n,s)->{}));
        verify(fixture.client,never()).search(anyString(),anyString(),anyInt());
    }

    @Test void fixedRetrievalFailureIsNotSilentlyConvertedIntoLegacyStandardEvidence() {
        Fixture fixture=new Fixture();
        when(fixture.client.search(anyString(),anyString(),anyInt())).thenThrow(new IllegalStateException("fixed_reference_signature_changed"));
        assertThrows(IllegalStateException.class,()->FixedVettingReferences.retrieve(fixture.client,"payment",10));
    }

    @Test void aMultiParentObservedReferenceIsEitherAdmittedWholeOrRecordedAsOmitted() {
        Fixture fixture=new Fixture();
        Chunk second=chunk("fixed-gcc-continuation","standard","GCC","GCC14.2","Provided that the certified amount is subject to the complete stated exceptions.");
        Map<String,Object> secondRaw=JsonUtils.readMap(JsonUtils.write(second));
        secondRaw.put("fixedDevelopmentBaseline",true);
        SearchHit secondHit=new SearchHit(second.getId(),0.8,secondRaw);
        JsonNode group=JsonUtils.parse("{\"unitId\":\"whole-payment-unit\",\"requiredMemberIds\":[\"fixed-gcc-payment\",\"fixed-gcc-continuation\"]}");
        FixedReferenceEvidence firstEvidence=new FixedReferenceEvidence(fixture.hit.getId(),fixture.hit.getScore(),JsonUtils.parse(JsonUtils.write(fixture.hit.getPayload())),null,group);
        FixedReferenceEvidence secondEvidence=new FixedReferenceEvidence(secondHit.getId(),secondHit.getScore(),JsonUtils.parse(JsonUtils.write(secondRaw)),null,group);
        when(fixture.client.search(anyString(),anyString(),anyInt())).thenReturn(Arrays.asList(fixture.hit,secondHit));
        when(fixture.client.evidence(any())).thenAnswer(call->fixture.hit.getId().equals(((SearchHit)call.getArgument(0)).getId())?firstEvidence:secondEvidence);
        FixedVettingReferences catalog=FixedVettingReferences.retrieve(fixture.client,"payment",10);
        Chunk tender=chunk("project","tender","SCT","SCT8","The interim payment provisions shall be checked against the project amendments.");
        VettingContextBuilder.Selection small=new VettingContextBuilder.Selection();small.setChunks(new ArrayList<>(Collections.singletonList(tender)));small.setContentChars(tender.getContent().length());
        catalog.admit(small,tender.getContent().length()+fixture.standard.getContent().length());
        assertEquals(1,small.getChunks().size());assertEquals("dropped_budget",small.getGroups().get(0).getStatus());
        assertTrue(small.getDroppedIds().containsAll(Arrays.asList(fixture.hit.getId(),secondHit.getId())));
        VettingContextBuilder.Selection large=new VettingContextBuilder.Selection();large.setChunks(new ArrayList<>(Collections.singletonList(tender)));large.setContentChars(tender.getContent().length());
        catalog.admit(large,10000);
        assertEquals(3,large.getChunks().size());assertTrue(large.getGroups().get(0).isFullySubmitted());
        assertEquals(second.getContent(),large.getChunks().get(2).getContent());
        assertEquals("unknown",large.getGroups().get(0).getQualifiersComplete());
        JsonNode savedGroup=catalog.pointer(fixture.hit.getId()).path("sourceUnit");
        assertEquals("whole-payment-unit",savedGroup.path("unitId").asText());assertEquals(2,savedGroup.path("requiredMemberIds").size());assertFalse(savedGroup.has("members"));
    }

    private static final class Fixture {
        final Chunk standard=chunk("fixed-gcc-payment","standard","GCC","GCC14.2","The interim payment certificate shall be subject to the minimum payment amount.");
        final FixedBaselineRetrievalClient client=mock(FixedBaselineRetrievalClient.class);
        final SearchHit hit;
        Fixture() {
            Map<String,Object> raw=JsonUtils.readMap(JsonUtils.write(standard));
            raw.put("fixedDevelopmentBaseline",true);raw.put("rawLayoutAudit","secret-raw-layout-audit");
            hit=new SearchHit(standard.getId(),0.9,raw);
            FixedReferenceEvidence evidence=new FixedReferenceEvidence(hit.getId(),hit.getScore(),JsonUtils.parse(JsonUtils.write(raw)),null,null);
            when(client.enabled()).thenReturn(true);when(client.search(anyString(),anyString(),anyInt())).thenReturn(Collections.singletonList(hit));
            when(client.evidence(any())).thenReturn(evidence);
            when(client.toModelMaterial(anyList(),anyInt())).thenAnswer(call->FixedBaselineModelMaterial.render(Collections.singletonList(evidence),call.getArgument(1)));
        }
    }
    private static Chunk chunk(String id,String role,String owner,String clause,String content) {
        Chunk c=new Chunk();c.setId(id);c.setRole(role);c.setDocumentId(id+"-source");c.setFileKey(owner);c.setFileName(owner+".pdf");
        c.setSourceHash(VettingCorpus.hash(id+"-source"));c.setContent(content);c.setAnchor("page/1/paragraph");c.setPageNo("1");
        c.setClauseId(clause);c.setClauseHeadingLocation("page/1/paragraph");
        Part p=new Part();p.setText(content);p.setAnchor(c.getAnchor());p.setPageNo("1");p.setBlockId(id+"-block");p.setStartOffset(0);p.setEndOffset(content.length());
        c.setParts(Collections.singletonList(p));return c;
    }
    private static Quote quote(Chunk chunk){Quote q=new Quote();q.setChunkId(chunk.getId());q.setSide(chunk.getRole());q.setQuote(chunk.getContent());return q;}
    private static Record record(String assessment,String type,Quote... quotes){Record r=new Record();r.setAssessment(assessment);r.setType(type);r.setSeverity("medium");r.setTitle("Review supporting provisions");r.setComment("Compare the supplied project source and preserve unresolved adoption.");r.setEvidence(Arrays.asList(quotes));return r;}
}
