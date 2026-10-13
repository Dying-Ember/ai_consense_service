package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.*;
import com.consense.service.sources.*;
import com.consense.service.advice.AdviceService;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual PDF producer wire and Python source-unit fixture; registry/retrieval and neural consumers doubled. */
class OerNativePdfBusinessEvidenceTest {
    static class Wire {
        final ObjectNode snapshot=NativePdfCanonicalFixture.snapshot();
        final CanonicalSourceBinding binding=new CanonicalSourceBinding();
        final SourceDocument source=new SourceDocument();
        final CanonicalEvidenceService registry=mock(CanonicalEvidenceService.class);
        final OerCanonicalRetrievalClient retrieval=mock(OerCanonicalRetrievalClient.class);
        final OerBusinessEvidence service;
        Wire() throws Exception {
            source.setId(42L);source.setProjectId("pdf-business");source.setFileKey("GCC");source.setFileName("authored-native-pdf.pdf");
            binding.setProjectId(source.getProjectId());binding.setDocumentId(source.getId());binding.setProjectionSha256(snapshot.path("projectionSha256").asText());
            binding.setOriginalSourceSha256(snapshot.path("source").path("sourceSha256").asText());binding.setJavaParsedSourceHash(VettingCorpus.hash("authored Java PDF parsed revision"));
            binding.setRoutingSha256(VettingCorpus.hash("authored route"));binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());
            binding.setSourceRevisionSha256(snapshot.path("source").path("sourceRevisionSha256").asText());binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());
            binding.setSnapshotJson(NativePdfCanonicalFixture.raw());binding.setSnapshotBytesSha256(NativePdfCanonicalFixture.PUBLIC_SHA);
            CanonicalEvidenceProperties properties=new CanonicalEvidenceProperties();properties.setBaseUrl("http://127.0.0.1:8868");
            when(registry.currentBindings(eq(source.getProjectId()),anyString(),eq(Collections.singletonList(42L)))).thenReturn(Collections.singletonList(binding));
            when(registry.pointers(eq(source.getProjectId()),eq(42L),eq(binding.getProjectionSha256()),anyString())).thenAnswer(call->{
                List<EvidencePointer> result=new ArrayList<>();for(JsonNode chunk:snapshot.path("chunks"))result.add(NativePdfCanonicalFixture.pointer(snapshot,chunk,binding,call.getArgument(3)));return result;
            });
            when(retrieval.sourceUnitPolicy(anyString(),anyString(),anyList())).thenReturn(VettingRetrievalSourceUnits.POLICY);service=new OerBusinessEvidence(registry,retrieval,properties);
        }
        OerBusinessEvidence.Session load(String module){return service.load(source.getProjectId(),module,Collections.singletonList(source));}
    }

    @Test void pdfBusinessViewKeepsRealPagesLiteralRangesAndAllDeclarations() throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load("vetting");assertEquals(1,session.chunkCount());Chunk view=session.chunks().get(0);
        assertEquals(w.snapshot.path("chunks").get(0),view.getCanonicalPayload());assertNull(OerBusinessEvidence.canonicalViewStatus(view));
        assertEquals("P1–P2",view.getPageNo());assertEquals("P1",view.getParts().get(0).getPageNo());assertEquals("P2",view.getParts().get(1).getPageNo());
        assertEquals(Arrays.asList(0d,0d,1d,1d),view.getParts().get(0).getBbox());assertNull(view.getBbox());
        assertEquals(NativePdfCanonicalContract.displayDeclarations(w.snapshot),view.getCanonicalEvidence().getNativePdfEvidence());
        assertEquals("native-pdf-no-table-adoption-v1",view.getNativeTableMetadataVersion());assertEquals(26,OerBusinessEvidence.observedChars(session.chunks()));
        assertEquals(view,JsonUtils.read(JsonUtils.write(view),Chunk.class));verifyNoInteractions(w.retrieval);
    }

    @Test void pythonSourceUnitMatchesJavaAndKeepsUnknownWholeSourceCoverage() throws Exception {
        Wire w=new Wire();JsonNode expected=JsonUtils.parse(new String(NativePdfCanonicalFixture.bytes("authored-business-source-unit.json"),StandardCharsets.UTF_8));
        Map<String,JsonNode> parents=new LinkedHashMap<>();w.snapshot.path("chunks").forEach(c->parents.put(c.path("id").asText(),c));
        assertEquals(expected,OerCanonicalSourceUnits.resolve(parents,w.snapshot.path("chunks").get(0)));assertEquals("complete_observed_unit",expected.path("status").asText());
        assertFalse(expected.path("semanticScopeVerified").asBoolean());assertEquals("unknown",expected.path("qualifiersComplete").asText());
    }

    @Test void vettingPromptMaterialPreservesPdfRefsGeometryAndUnknowns() throws Exception {
        Wire w=new Wire();Chunk view=w.load("vetting").chunks().get(0);Map<String,Object> material=VettingSourceMaterial.project(Collections.singletonList(view)).get(0);
        JsonNode structured=JsonUtils.mapper().valueToTree(material).path("canonicalStructure");
        assertEquals(NativePdfCanonicalContract.BBOX,structured.path("bboxConvention").asText());OerModelMaterialCandidateTest.assertLiteralParts(view.getCanonicalPayload().path("parts"),structured.path("parts"));
        assertEquals(view.getCanonicalEvidence().getNativePdfEvidence().get("limitations"),structured.path("sourceDeclaration").path("limits").path("limitations"));assertTrue(structured.path("assetRefs").isEmpty());
        assertEquals(NativePdfCanonicalContract.VISUAL,structured.path("visualReturnStatus").asText());assertFalse(JsonUtils.write(material).contains("nativeGlyphMembershipRef"));assertFalse(JsonUtils.write(material).contains("captureCompleteness"));
        assertEquals("not_applicable_to_canonical_structure",material.get("nativeRowsStatus"));
    }

    @Test void pdfViewCannotUseScannedOrDocxIdentityOrUpgradeCaptureClaims() throws Exception {
        for(String mutation:Arrays.asList("schema","producer","bbox","unknowns","pixelClaim","page")){
            Wire w=new Wire();Chunk view=w.load("vetting").chunks().get(0);EvidencePointer pointer=view.getCanonicalEvidence();
            if("schema".equals(mutation))pointer.setProjectionSchema("consense.canonical-projection.v1");
            if("producer".equals(mutation))pointer.setProducerKind("native_docx");
            if("bbox".equals(mutation))pointer.setBboxConvention("normalized_xyxy_original_page");
            if("unknowns".equals(mutation))((ObjectNode)pointer.getNativePdfEvidence()).put("semanticScopeVerified",true);
            if("pixelClaim".equals(mutation))((ObjectNode)pointer.getNativePdfEvidence().path("captureCompleteness").path("native")).put("effectiveRenderingVerified",true);
            if("page".equals(mutation))view.setPageNo("P99");
            assertNotNull(OerBusinessEvidence.canonicalViewStatus(view),mutation);assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(Collections.singletonList(view)),mutation);
        }
    }

    @Test void currentSessionCannotIndexMutatedPdfDisplayOrRestoreItAfterFailure() throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load("vetting");EvidencePointer pointer=session.chunks().get(0).getCanonicalEvidence();JsonNode prior=pointer.getNativePdfEvidence().deepCopy();
        ((ObjectNode)pointer.getNativePdfEvidence()).put("humanConfirmed",true);assertThrows(IllegalStateException.class,()->w.service.index(session));
        pointer.setNativePdfEvidence(prior);assertThrows(IllegalStateException.class,()->w.service.index(session));verifyNoInteractions(w.retrieval);
    }

    @Test void advisorySourceHitsAndTextIncludeOriginalPdfPositionsWithoutInventedAssetSupport() throws Exception {
        Wire w=new Wire();OerBusinessEvidence.Session session=w.load("advisory");List<VectorStore.SearchHit> hits=w.service.sourceHits(session,Collections.singletonList(session.chunks().get(0).getId()));
        assertEquals(1,hits.size());assertEquals("P1, P2",hits.get(0).getPayload().get("pageNo"));
        AdviceService advice=mock(AdviceService.class,CALLS_REAL_METHODS);Method render=AdviceService.class.getDeclaredMethod("renderExcerpts",List.class);render.setAccessible(true);
        String text=(String)render.invoke(advice,hits);assertTrue(text.contains("Native PDF source-reviewed regions:"));assertTrue(text.contains("pdf_native_source_reviewed_region"));
        assertTrue(text.contains("effective visibility and whole-source semantics remain unknown"));assertTrue(text.contains(NativePdfCanonicalContract.VISUAL));assertFalse(text.contains("capturedSelectedPhysicalPages"));assertFalse(text.contains("captureCompleteness"));
        assertFalse(text.contains("Native DOCX observations:"));verify(w.retrieval).sourceUnitPolicy(anyString(),anyString(),anyList());verifyNoMoreInteractions(w.retrieval);
    }

    @Test void optionalPdfDeclarationsStayNullInExistingScannedAndDocxChunkRoundtrips() throws Exception {
        for(boolean docx:Arrays.asList(false,true)){
            OerBusinessEvidenceTest.Wire old=new OerBusinessEvidenceTest.Wire(docx);for(Chunk chunk:old.load().chunks()){
                assertNull(chunk.getCanonicalEvidence().getNativePdfEvidence());Chunk copy=JsonUtils.read(JsonUtils.write(chunk),Chunk.class);
                assertEquals(chunk,copy);assertNull(copy.getCanonicalEvidence().getNativePdfEvidence());assertNull(OerBusinessEvidence.canonicalViewStatus(copy));
            }
        }
    }

    @Test void missingOrChangedPdfDisplayDeclarationsCannotLoadAView() throws Exception {
        Wire w=new Wire();EvidencePointer changed=NativePdfCanonicalFixture.pointer(w.snapshot,w.snapshot.path("chunks").get(0),w.binding,"vetting");changed.setNativePdfEvidence(null);
        when(w.registry.pointers(anyString(),anyLong(),anyString(),eq("vetting"))).thenReturn(Collections.singletonList(changed));assertThrows(IllegalStateException.class,()->w.load("vetting"));
    }
}
