package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.*;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.service.sources.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual PDF/public/selector fixtures; Java HTTP and neural index/ranking scores are explicit doubles. */
class OerNativePdfRetrievalClientTest {
    static class Wire {
        final HttpSupport http=mock(HttpSupport.class);final CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);
        final ConsenseProperties properties=VettingDualStructureHealthTest.expected();final CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();
        final ObjectNode snapshot=NativePdfCanonicalFixture.snapshot(),health=VettingDualStructureHealthTest.health();
        final ObjectNode index,response;final CanonicalSourceBinding binding=new CanonicalSourceBinding();final OerCanonicalRetrievalClient client;
        Wire() throws Exception {
            OerCudaHealthFixtures.apply(health);
            JsonNode prototype=VettingDualStructureHealthTest.resource("authored_canonical_wire.json");index=prototype.path("index").deepCopy();response=prototype.path("retrieve").deepCopy();
            String policy=snapshot.path("manifest").path("scope").path("scopePolicySha256").asText();ObjectNode scope=JsonUtils.mapper().createObjectNode();scope.put("manifestSha256",policy);
            ObjectNode oer=health.putObject("oer");oer.put("inputContract","canonical-source-chunks-upstream-scope-and-parser-projection-v1");oer.put("assetReturnContract","source-and-projection-bound-content-addressed-assets-v1");
            oer.put("recipe","scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1");oer.put("targetModule","vetting");oer.put("sourceQualityVerifiedByHealth",false);oer.put("parserCandidatesAcceptedByRetrieval",false);
            oer.put("canonicalProjectionEndpoint","/project");oer.put("indexStatusContract","read-only-existing-canonical-index-v1");oer.set("scopePolicy",scope.deepCopy());oer.set("nativePdfCanonical",NativePdfCanonicalFixture.capability());
            ObjectNode windows=(ObjectNode)health.path("retrieval").path("sourceWindows");windows.set("competitionScope",scope.deepCopy());windows.put("oerInputContract","canonical-source-chunks-upstream-scope-and-parser-projection-v1");windows.put("oerTargetModule","vetting");
            ObjectNode algorithms=(ObjectNode)windows.path("algorithms");for(String name:Arrays.asList("oer_server.py","competition_scope.py","artifact_store.py","structured_parser_evidence.py","canonical_projection.py","oer_source_registry.py","oer_request_evidence.py"))algorithms.put(name,VettingCorpus.hash("authored "+name));
            algorithms.setAll(NativePdfCanonicalFixture.algorithms(snapshot));index.put("projectId","pdf-client");index.put("indexed",1);index.putArray("canonicalProjections").add(snapshot.path("projectionSha256").asText());
            ((ObjectNode)index.path("competitionScope").path("policyIdentity")).put("manifestSha256",policy);response.put("projectId","pdf-client");
            ((ObjectNode)response.path("competitionScope").path("policyIdentity")).put("manifestSha256",policy);
            JsonNode seed=snapshot.path("chunks").get(0);ObjectNode hit=response.putArray("hits").addObject();hit.put("id",seed.path("id").asText());hit.put("score",0.9);hit.set("payload",seed.deepCopy());
            ObjectNode unit=(ObjectNode)JsonUtils.parse(new String(NativePdfCanonicalFixture.bytes("authored-business-source-unit.json"),StandardCharsets.UTF_8));unit.put("originalRerankOrdinal",1);unit.put("seedScore",0.9);
            ObjectNode episode=(ObjectNode)response.path("sourceUnits");episode.putArray("originalHitIds").add(seed.path("id").asText());episode.putArray("units").add(unit);
            config.setBaseUrl("http://127.0.0.1:8868");properties.getVetting().setTopK(1);properties.getVetting().setCandidateLimit(10);
            binding.setProjectId("pdf-client");binding.setDocumentId(42L);binding.setProjectionSha256(snapshot.path("projectionSha256").asText());binding.setOriginalSourceSha256(snapshot.path("source").path("sourceSha256").asText());
            binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());binding.setJavaParsedSourceHash(VettingCorpus.hash("authored Java parsed"));binding.setRoutingSha256(VettingCorpus.hash("authored route"));
            binding.setSnapshotJson(NativePdfCanonicalFixture.raw());binding.setSnapshotBytesSha256(NativePdfCanonicalFixture.PUBLIC_SHA);
            when(backend.snapshot(binding.getProjectionSha256(),"vetting")).thenReturn(NativePdfCanonicalFixture.raw());
            when(http.get(endsWith("/health"),anyLong())).thenAnswer(c->JsonUtils.write(health));when(http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenAnswer(c->JsonUtils.write(index));
            when(http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(c->{ObjectNode status=index.deepCopy();status.put("ready",true);status.put("readOnly",true);status.put("vectorPoints",status.path("indexed").asInt());status.put("indexStatusContract","read-only-existing-canonical-index-v1");return JsonUtils.write(status);});
            when(http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenAnswer(c->JsonUtils.write(response));client=new OerCanonicalRetrievalClient(http,properties,config,backend);
        }
        void index(){client.index("pdf-client","vetting",Collections.singletonList(binding));}
        JsonNode query(){return client.retrieve("pdf-client","vetting","authored native PDF query","tender",Collections.singletonList(binding));}
    }

    @Test void actualPublicParentsAndPythonWholeSourceUnitPassIndexAndRetrieveWithoutLegacyConversion() throws Exception {
        Wire w=new Wire();w.index();assertEquals(w.response,w.query());assertTrue(w.client.indexStatus("pdf-client","vetting",Collections.singletonList(w.binding)).ready);
        assertEquals(w.snapshot.path("chunks").get(0),w.response.path("hits").get(0).path("payload"));assertEquals("complete_observed_unit",w.response.path("sourceUnits").path("units").get(0).path("status").asText());
        verify(w.backend,atLeast(3)).snapshot(w.binding.getProjectionSha256(),"vetting");ArgumentCaptor<String> sent=ArgumentCaptor.forClass(String.class);
        verify(w.http).postJson(endsWith("/index-canonical"),sent.capture(),anyLong());assertFalse(JsonUtils.parse(sent.getValue()).has("chunks"));assertEquals(w.index.path("canonicalProjections"),JsonUtils.parse(sent.getValue()).path("projectionSha256"));
        verify(w.http,never()).postJson(endsWith("/index"),anyString(),anyLong());
    }

    @Test void missingPdfCapabilityOrAlgorithmStopsBeforeIndexing() throws Exception {
        Wire w=new Wire();((ObjectNode)w.health.path("oer")).remove("nativePdfCanonical");assertThrows(RuntimeException.class,w::index);verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        ((ObjectNode)w.health.path("oer")).set("nativePdfCanonical",NativePdfCanonicalFixture.capability());((ObjectNode)w.health.path("retrieval").path("sourceWindows").path("algorithms")).remove("native_pdf_observation.py");
        assertThrows(RuntimeException.class,w::index);verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
    }

    @Test void missingColdRegistryProofInvalidatesReceiptAndCannotReachRetrieve() throws Exception {
        Wire w=new Wire();w.index();when(w.backend.snapshot(anyString(),eq("vetting"))).thenThrow(new IllegalStateException("actual CAS unavailable"));
        assertThrows(RuntimeException.class,w::query);assertFalse(w.client.indexStatus("pdf-client","vetting",Collections.singletonList(w.binding)).ready);
        when(w.backend.snapshot(anyString(),eq("vetting"))).thenReturn(NativePdfCanonicalFixture.raw());assertThrows(RuntimeException.class,w::query);
        verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }

    @Test void changedRankedPartsOrSourceUnitsCannotReplaceWholeActualPublicParents() throws Exception {
        Wire w=new Wire();w.index();((ObjectNode)w.response.path("hits").get(0).path("payload").path("parts").get(0)).put("nativeEffectiveVisibility","verified");assertThrows(IllegalStateException.class,w::query);
        Wire other=new Wire();other.index();((ObjectNode)other.response.path("sourceUnits").path("units").get(0)).put("semanticScopeVerified",true);assertThrows(IllegalStateException.class,other::query);
    }

    @Test void pdfCapabilityChangedAfterIndexInvalidatesCurrentRecipe() throws Exception {
        Wire w=new Wire();w.index();((ObjectNode)w.health.path("oer").path("nativePdfCanonical")).put("artifactReadValidation","cache-only");assertThrows(RuntimeException.class,w::query);
        assertFalse(w.client.indexStatus("pdf-client","vetting",Collections.singletonList(w.binding)).ready);verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }
}
