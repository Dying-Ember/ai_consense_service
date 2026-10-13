package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.config.ConsenseProperties;
import com.consense.domain.*;
import com.consense.service.sources.*;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual run04 sourceHits business seam; registry/client doubles are explicit. */
class OerSharedNoteBusinessPolicyAuthorTest {
    static class Wire {
        String policy=OerSharedNoteAuthorDifferentialTest.NEW_POLICY;
        final CanonicalEvidenceService registry=mock(CanonicalEvidenceService.class);
        final OerCanonicalRetrievalClient retrieval=mock(OerCanonicalRetrievalClient.class,call->{
            if("sourceUnitPolicy".equals(call.getMethod().getName()))return policy;
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
        final SourceDocument source=new SourceDocument();final CanonicalSourceBinding binding=new CanonicalSourceBinding();
        final ObjectNode snapshot;final OerBusinessEvidence service;final Map<String,JsonNode> parents=new LinkedHashMap<>();
        Wire()throws Exception {
            String raw=new String(Files.readAllBytes(Path.of(System.getenv("CONSENSE_OER_REPLAY_ROOT"),".local/state/oer-main-source-page-20261010/producer-readdress-owned-01/run-04-current/GCC/public-projection.json")),StandardCharsets.UTF_8);
            assertEquals("b4870340c22d6e28a52b013854052e8556f180dcd04391546536b88784588a2e",VettingCorpus.hash(raw));snapshot=(ObjectNode)JsonUtils.parse(raw);
            snapshot.path("chunks").forEach(p->parents.put(p.path("id").asText(),p));assertEquals(50,parents.size());
            source.setId(236L);source.setProjectId("author-actual-shared-business");source.setFileKey("GCC");source.setFileName("09_General Conditions of Contract.pdf");
            binding.setProjectId(source.getProjectId());binding.setDocumentId(236L);binding.setProjectionSha256(snapshot.path("projectionSha256").asText());
            binding.setOriginalSourceSha256(snapshot.path("source").path("sourceSha256").asText());binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());
            binding.setSourceRevisionSha256(snapshot.path("source").path("sourceRevisionSha256").asText());binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());
            binding.setJavaParsedSourceHash(VettingCorpus.hash("author original parsed binding double"));binding.setRoutingSha256(VettingCorpus.hash("author routing double"));binding.setSnapshotJson(raw);binding.setSnapshotBytesSha256(VettingCorpus.hash(raw));
            when(registry.currentBindings(eq(source.getProjectId()),anyString(),eq(Collections.singletonList(236L)))).thenReturn(Collections.singletonList(binding));
            when(registry.pointers(eq(source.getProjectId()),eq(236L),eq(binding.getProjectionSha256()),anyString())).thenAnswer(call->{
                List<com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer> out=new ArrayList<>();snapshot.path("chunks").forEach(p->out.add(NativePdfCanonicalFixture.pointer(snapshot,p,binding,call.getArgument(3))));return out;});
            when(retrieval.attachmentContextEnabled()).thenReturn(true);
            CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:8868");service=new OerBusinessEvidence(registry,retrieval,config);
        }
        OerBusinessEvidence.Session load(){return service.load(source.getProjectId(),"advisory",Collections.singletonList(source));}
    }
    @Test void everyActualSharedSeedUsesV4PolicyAtRealContextOnlyBusinessCall()throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();JsonNode dataset=OerSharedNoteAuthorDifferentialTest.fixture().path("datasets").get(0);
        for(JsonNode row:dataset.path("cases")) {
            String seed=row.path("seedId").asText();VectorStore.SearchHit hit=w.service.sourceHits(session,Collections.singletonList(seed)).get(0);
            JsonNode payload=JsonUtils.mapper().valueToTree(hit.getPayload()),expected=row.path("expectedResolved").path("attachmentContext");
            assertEquals(expected.path("members"),payload.path("canonicalMembers"),seed);
            assertEquals(expected,row.path("expectedResolved").path("attachmentContext"));
            assertEquals(expected,payload.path("canonicalSourceUnit").path("attachmentContext"));
            assertEquals("unranked_context",payload.path("scoreKind").asText());assertEquals(0d,hit.getScore());
            assertEquals(expected.path("members").size(),payload.path("canonicalEvidence").size());
            for(int i=0;i<expected.path("members").size();i++)assertEquals(expected.path("members").get(i).path("parts"),payload.path("canonicalEvidence").get(i).path("parts"));
        }
        verify(w.retrieval,never()).index(anyString(),anyString(),anyList());verify(w.retrieval,never()).retrieve(anyString(),anyString(),anyString(),nullable(String.class),anyList());
    }
    @Test void oldV3AndUnknownPoliciesCannotUseV4SharedMembership()throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load();String seed=OerSharedNoteAuthorDifferentialTest.fixture().path("datasets").get(0).path("cases").get(0).path("seedId").asText();
        w.policy=VettingRetrievalSourceUnits.ATTACHMENT_POLICY;
        JsonNode old=JsonUtils.mapper().valueToTree(w.service.sourceHits(session,Collections.singletonList(seed)).get(0).getPayload());
        assertEquals("unknown",old.path("canonicalSourceUnit").path("attachmentContext").path("status").asText());assertEquals(1,old.path("canonicalMembers").size());
        for(String unknown:Arrays.asList(null,"unknown-v5","")){w.policy=unknown;assertThrows(IllegalStateException.class,()->w.service.sourceHits(session,Collections.singletonList(seed)));}
    }
    static Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static Object privateClientWire()throws Exception {Constructor<?> c=Class.forName("com.consense.service.vetting.OerCanonicalRetrievalClientTest$Wire").getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}
    static void healthPolicy(Object wire,boolean shared)throws Exception {
        ObjectNode health=(ObjectNode)field(wire,"health"),window=(ObjectNode)health.path("retrieval").path("sourceWindows"),selection=(ObjectNode)window.path("structuralSelection");
        window.put("contract",VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_CONTRACT);((ObjectNode)window.path("structuralRecipe")).put("version",shared?OerSharedNoteAuthorDifferentialTest.NEW_VERSION:VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_VERSION);
        selection.put("policy",shared?OerSharedNoteAuthorDifferentialTest.NEW_POLICY:VettingRetrievalSourceUnits.ATTACHMENT_POLICY);selection.put("hierarchyContextPolicy",OerCanonicalHierarchyContext.POLICY);selection.put("attachmentContextPolicy",shared?OerSharedNoteAuthorDifferentialTest.NEW_ATTACHMENT:OerCanonicalAttachmentContext.POLICY);
        ((ConsenseProperties)field(wire,"properties")).getVetting().setExpectedRetrievalContract(VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_CONTRACT);
        ((ObjectNode)field(wire,"index")).put("contract",VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_CONTRACT);
    }
    @Test void realPolicyAccessorReadsActualHealthAndRejectsChangedIndexedCapability()throws Exception {
        Object wire=privateClientWire();healthPolicy(wire,true);OerCanonicalRetrievalClient client=(OerCanonicalRetrievalClient)field(wire,"client");CanonicalSourceBinding binding=(CanonicalSourceBinding)field(wire,"binding");
        Method accessor=OerCanonicalRetrievalClient.class.getMethod("sourceUnitPolicy",String.class,String.class,List.class);
        assertEquals(OerSharedNoteAuthorDifferentialTest.NEW_POLICY,accessor.invoke(client,"test-canonical","vetting",Collections.singletonList(binding)));
        client.index("test-canonical","vetting",Collections.singletonList(binding));
        assertEquals(OerSharedNoteAuthorDifferentialTest.NEW_POLICY,accessor.invoke(client,"test-canonical","vetting",Collections.singletonList(binding)));
        healthPolicy(wire,false);InvocationTargetException rejected=assertThrows(InvocationTargetException.class,()->accessor.invoke(client,"test-canonical","vetting",Collections.singletonList(binding)));
        assertInstanceOf(IllegalStateException.class,rejected.getCause());assertFalse(client.indexStatus("test-canonical","vetting",Collections.singletonList(binding)).ready);
    }
}
