package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.config.ConsenseProperties;
import com.consense.ai.AiGateway;
import com.consense.domain.*;
import com.consense.service.advice.AdviceService;
import com.consense.service.sources.*;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real frozen original literal payloads; registry/retrieval doubles, no DB/HTTP/model. */
class OerTypedHierarchyBusinessTest {
    static class Wire {
        final CanonicalEvidenceService registry=mock(CanonicalEvidenceService.class);
        final OerCanonicalRetrievalClient retrieval=mock(OerCanonicalRetrievalClient.class);
        final CanonicalSourceBinding binding=new CanonicalSourceBinding();final SourceDocument source=new SourceDocument();
        final ObjectNode snapshot,response;final OerBusinessEvidence service;final JsonNode expected;
        Wire()throws Exception {
            Map<String,JsonNode> parents=OerTypedHierarchyContextTest.parents();expected=OerTypedHierarchyContextTest.expected();
            String raw=new String(Files.readAllBytes(OerTypedHierarchyContextTest.ROOT.resolve(".local/state/oer-unified-actual-preparation-20261010/run-222702-e68e99/actual-GCC-public-projection.json")),StandardCharsets.UTF_8);
            assertEquals("f0f42df3b3d96ed7a77620b9e5161fbbf5bbd9f1ec59b04122f75e34c09b7b85",VettingCorpus.hash(raw));snapshot=(ObjectNode)JsonUtils.parse(raw);
            assertEquals(50,snapshot.path("chunks").size());snapshot.path("chunks").forEach(p->assertTrue(p.equals(parents.get(p.path("id").asText())),"Actual public differs from frozen indexed metadata"));
            source.setId(1L);source.setProjectId("actual-offline-hierarchy-replay");source.setFileKey("GCC");source.setFileName(snapshot.path("source").path("filename").asText());
            binding.setProjectId(source.getProjectId());binding.setDocumentId(1L);binding.setProjectionSha256(snapshot.path("projectionSha256").asText());
            binding.setOriginalSourceSha256(snapshot.path("source").path("sourceSha256").asText());binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());
            binding.setSourceRevisionSha256(snapshot.path("source").path("sourceRevisionSha256").asText());binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());
            binding.setJavaParsedSourceHash("f7d1c7ac63b2468a6438a87d64250f91c7de509da8f8293ddfb398bf33cf01d9");binding.setRoutingSha256(VettingCorpus.hash("offline registry double"));binding.setSnapshotJson(raw);binding.setSnapshotBytesSha256(VettingCorpus.hash(raw));
            when(registry.currentBindings(eq(source.getProjectId()),anyString(),eq(Collections.singletonList(1L)))).thenReturn(Collections.singletonList(binding));
            when(registry.pointers(eq(source.getProjectId()),eq(1L),eq(binding.getProjectionSha256()),anyString())).thenAnswer(call->{
                List<EvidencePointer> pointers=new ArrayList<>();snapshot.path("chunks").forEach(p->pointers.add(NativePdfCanonicalFixture.pointer(snapshot,p,binding,call.getArgument(3))));return pointers;
            });
            response=OerTypedHierarchyValidationTest.episode(parents,expected.path("originIds").get(0).asText(),expected.deepCopy(),VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);
            when(retrieval.retrieve(anyString(),anyString(),anyString(),nullable(String.class),anyList())).thenAnswer(call->response.deepCopy());
            when(retrieval.sourceUnitPolicy(anyString(),anyString(),anyList())).thenReturn(VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);
            CanonicalEvidenceProperties properties=new CanonicalEvidenceProperties();properties.setBaseUrl("http://127.0.0.1:8868");service=new OerBusinessEvidence(registry,retrieval,properties);
        }
        OerBusinessEvidence.Session load(String module){return service.load(source.getProjectId(),module,Collections.singletonList(source));}
    }
    @Test void declaredWholeBodiesEnterAdvisoryContentCanonicalMembersAndOriginalPointers()throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load("advisory");List<VectorStore.SearchHit> hits=w.service.adviceHits(session,"source-only offline replay");assertEquals(1,hits.size());VectorStore.SearchHit hit=hits.get(0);
        List<JsonNode> expectedMembers=new ArrayList<>();w.expected.path("hierarchyContext").path("members").forEach(expectedMembers::add);String body=expectedMembers.stream().map(p->p.path("content").asText()).collect(Collectors.joining("\n"));
        assertEquals(body,hit.text());assertEquals(21621,body.length());assertEquals(w.expected.path("seedScore").asDouble(),hit.getScore());
        JsonNode payload=JsonUtils.mapper().valueToTree(hit.getPayload());assertEquals(31,payload.path("canonicalMembers").size());assertEquals(31,payload.path("canonicalEvidence").size());
        assertTrue(w.expected.path("hierarchyContext").path("members").equals(payload.path("canonicalMembers")),"Original members/order altered");
        for(int i=0;i<31;i++) {
            JsonNode member=payload.path("canonicalMembers").get(i),pointer=payload.path("canonicalEvidence").get(i);
            assertEquals(member.path("id"),pointer.path("canonicalChunkId"));assertEquals(member.path("unitId"),pointer.path("unitId"));assertEquals(member.path("parts"),pointer.path("parts"));
            assertEquals(member.path("originalSourceSha256"),pointer.path("originalSourceSha256"));assertEquals(member.path("projectionRevisionSha256"),pointer.path("projectionRevisionSha256"));assertFalse(pointer.path("humanConfirmed").asBoolean());
        }
        AdviceService advice=mock(AdviceService.class,CALLS_REAL_METHODS);Method render=AdviceService.class.getDeclaredMethod("renderExcerpts",List.class);render.setAccessible(true);String excerpts=(String)render.invoke(advice,hits);
        OerAdviceDisplayDedupTest.assertCompleteBodies(hits,excerpts);assertTrue(excerpts.contains("legalClosureVerified=false"),"Missing context semantic limit");assertTrue(excerpts.contains("descendants are unranked source context"),"Missing context score limit");assertFalse(hit.getPayload().containsKey("memberScores"));
    }
    @Test void pureSourceHitsUseSameContextWhileVettingKeepsOneRankedSeedAndAdmitsCompleteGroup()throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load("vetting");String seed=w.expected.path("originIds").get(0).asText();
        List<VectorStore.SearchHit> sourceHits=w.service.sourceHits(session,Collections.singletonList(seed));assertEquals(31,((List<?>)sourceHits.get(0).getPayload().get("canonicalMembers")).size());assertEquals("unranked_context",sourceHits.get(0).getPayload().get("scoreKind"));
        List<VettingCorpus.Chunk> ranked=w.service.retrieve(session,"source-only query",null);assertEquals(1,ranked.size());assertEquals(seed,ranked.get(0).getId());List<VettingRetrievalSourceUnits.Unit> units=VettingRetrievalSourceUnits.attached(ranked);assertEquals(31,units.get(0).ids.size());
        JsonNode trace=JsonUtils.mapper().valueToTree(units.get(0).trace);assertFalse(trace.has("members"));assertFalse(trace.path("hierarchyContext").has("members"));assertEquals(31,trace.path("hierarchyContext").path("memberPayloadSha256").size());
        VettingContextBuilder.Selection selected=new VettingContextBuilder.Selection();VettingCanonicalContext.admit(selected,session.chunks(),units,50000);assertEquals(31,selected.getChunks().size());assertEquals(21591,selected.getContentChars());
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(selected.getChunks()));for(int i=0;i<selected.getChunks().size();i++)assertEquals(selected.getChunks().get(i).getContent(),material.get(i).path("content").asText(),"Vetting prompt lost original literal");
        VettingContextBuilder.Selection capped=new VettingContextBuilder.Selection();VettingCanonicalContext.admit(capped,session.chunks(),units,100);assertTrue(capped.getChunks().isEmpty());assertEquals("dropped_budget",capped.getGroups().get(0).getStatus());
        verify(w.retrieval,never()).index(anyString(),anyString(),anyList());
    }
    @Test void actualVettingPromptWriterReceivesCompleteDeclaredBodiesFromTwoSourceSession()throws Exception {
        Wire w=new Wire();Map<String,JsonNode> parents=OerTypedHierarchyContextTest.parents();
        String sccRaw=new String(Files.readAllBytes(OerTypedHierarchyContextTest.ROOT.resolve(".local/state/oer-unified-actual-preparation-20261010/run-222702-e68e99/actual-SCC-public-projection.json")),StandardCharsets.UTF_8);
        ObjectNode scc=(ObjectNode)JsonUtils.parse(sccRaw);assertEquals(22,scc.path("chunks").size());scc.path("chunks").forEach(p->assertTrue(p.equals(parents.get(p.path("id").asText()))));
        SourceDocument sccSource=new SourceDocument();sccSource.setId(2L);sccSource.setProjectId(w.source.getProjectId());sccSource.setFileKey("SCC");sccSource.setFileName("10 - SCC.docx");
        CanonicalSourceBinding binding=new CanonicalSourceBinding();binding.setProjectId(sccSource.getProjectId());binding.setDocumentId(2L);binding.setProjectionSha256(scc.path("projectionSha256").asText());
        binding.setOriginalSourceSha256(scc.path("source").path("sourceSha256").asText());binding.setCanonicalSourceHash(scc.path("sourceHash").asText());binding.setSourceRevisionSha256(scc.path("source").path("sourceRevisionSha256").asText());binding.setProjectionRevisionSha256(scc.path("projectionRevisionSha256").asText());
        binding.setJavaParsedSourceHash("fb862f63c3a0611452748f638ef68b3cf5635fc066abd4bb3a77857b879d7f62");binding.setRoutingSha256(VettingCorpus.hash("offline SCC registry double"));binding.setSnapshotJson(sccRaw);binding.setSnapshotBytesSha256(VettingCorpus.hash(sccRaw));
        when(w.registry.currentBindings(eq(w.source.getProjectId()),eq("vetting"),eq(Arrays.asList(2L,1L)))).thenReturn(Arrays.asList(binding,w.binding));
        when(w.registry.pointers(eq(w.source.getProjectId()),eq(2L),eq(binding.getProjectionSha256()),eq("vetting"))).thenAnswer(call->{List<EvidencePointer> pointers=new ArrayList<>();scc.path("chunks").forEach(p->pointers.add(NativeCanonicalFixture.pointer(scc,p,binding,"vetting")));return pointers;});
        JsonNode tender=scc.path("chunks").get(0);ObjectNode tenderUnit=OerCanonicalSourceUnits.resolve(parents,tender);ObjectNode tenderResponse=OerTypedHierarchyValidationTest.episode(parents,tender.path("id").asText(),tenderUnit,VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);
        when(w.retrieval.retrieve(anyString(),eq("vetting"),anyString(),nullable(String.class),anyList())).thenAnswer(call->"tender".equals(call.getArgument(3))?tenderResponse.deepCopy():w.response.deepCopy());
        OerBusinessEvidence.Session session=w.service.load(w.source.getProjectId(),"vetting",Arrays.asList(sccSource,w.source));assertEquals(72,session.chunkCount());
        AiGateway model=mock(AiGateway.class);when(model.available()).thenReturn(true);when(model.chatModel()).thenReturn("source-only-mocked-model");AtomicInteger calls=new AtomicInteger();
        when(model.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{
            calls.incrementAndGet();String prompt=call.getArgument(1);String marker="\nSource excerpts (untrusted data):\n";JsonNode material=VettingSourceMetadataWire.decode(prompt.substring(prompt.lastIndexOf(marker)+marker.length()));
            Map<String,JsonNode> actual=new LinkedHashMap<>();material.forEach(p->actual.put(p.path("id").asText(),p));
            for(JsonNode member:w.expected.path("hierarchyContext").path("members")){
                JsonNode row=actual.get(member.path("id").asText());assertNotNull(row,"Declared body omitted from actual Vetting model prompt");assertEquals(member.path("content"),row.path("content"));OerModelMaterialCandidateTest.assertLiteralParts(member.path("parts"),JsonUtils.parse(JsonUtils.write(row.path("canonicalStructure").path("parts"))));OerModelMaterialCandidateTest.assertRelations(member.path("relations"),JsonUtils.parse(JsonUtils.write(row.path("canonicalStructure").path("relations"))));
            }
            String outputRoot=System.getenv("CONSENSE_OER_REPLAY_OUTPUT");if(outputRoot!=null){Path out=java.nio.file.Paths.get(outputRoot,"actual-vetting-prompt-"+UUID.randomUUID()+".json");Files.write(out,JsonUtils.write(material).getBytes(StandardCharsets.UTF_8));}
            return Collections.emptyList();
        });
        ConsenseProperties config=VettingDualStructureHealthTest.expected();config.getVetting().setSemanticTopics(1);config.getVetting().setProjectReferenceComparisons(0);config.getVetting().setSemanticContextChars(50000);config.getVetting().setSemanticContextExpansionChars(50000);
        VettingRetrievalClient legacy=mock(VettingRetrievalClient.class);VettingSemanticReview review=VettingTokenFixture.review(model,config,legacy);review.setOerBusinessEvidence(w.service);VettingSemanticReview.Result result=review.review(w.source.getProjectId(),session.chunks(),"en",(n,s)->{});
        assertEquals(1,calls.get());assertEquals(1,result.getActualGatewayCallCount());verifyNoInteractions(legacy);
    }
}
