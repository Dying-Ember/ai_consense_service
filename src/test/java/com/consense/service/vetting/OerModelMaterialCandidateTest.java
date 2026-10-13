package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.advice.AdviceService;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual frozen source literals; no HTTP, DB, model or answer files. */
class OerModelMaterialCandidateTest {
    @Test void dictionarySharingRequiresExactCompletePointerCanonicalPayloadAndRole()throws Exception{
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();VettingCorpus.Chunk c=w.load("vetting").chunks().get(3);
        OerModelMaterial.Request r=new OerModelMaterial.Request();JsonNode first=r.project(c.getCanonicalEvidence(),c.getCanonicalPayload(),"standard");
        JsonNode repeated=r.project(c.getCanonicalEvidence(),c.getCanonicalPayload(),"standard");assertEquals(first.path("modelUnitAddress"),repeated.path("modelUnitRef"));assertFalse(repeated.has("parts"));
        JsonNode otherRole=r.project(c.getCanonicalEvidence(),c.getCanonicalPayload(),"project_fact");assertTrue(otherRole.has("parts"));assertNotEquals(first.path("modelUnitAddress"),otherRole.path("modelUnitAddress"));
        com.fasterxml.jackson.databind.node.ObjectNode authoredVariant=c.getCanonicalPayload().deepCopy();authoredVariant.put("authoredIdentityVariant","negative sharing control only");
        JsonNode otherPayload=r.project(c.getCanonicalEvidence(),authoredVariant,"standard");assertTrue(otherPayload.has("parts"));assertNotEquals(first.path("modelUnitAddress"),otherPayload.path("modelUnitAddress"));
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer authoredPointer=JsonUtils.read(JsonUtils.write(c.getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)authoredPointer.getAdoption()).put("authoredAdoptionVariant","negative sharing control only");
        JsonNode otherAdoption=r.project(authoredPointer,c.getCanonicalPayload(),"standard");assertTrue(otherAdoption.has("parts"));assertNotEquals(first.path("modelUnitAddress"),otherAdoption.path("modelUnitAddress"));
    }
    static void assertLiteralParts(JsonNode original,JsonNode projected){
        assertEquals(original.size(),projected.size(),"Whole-unit part inventory must remain");
        for(int i=0;i<original.size();i++){
            JsonNode raw=original.get(i),model=projected.get(i);
            for(String key:Arrays.asList("text","anchor","blockId","startOffset","endOffset","offsetUnit","physicalPageNo","bbox","bboxConvention","referenceRegionId","textChannel","nativeEditingState","nativeSemanticQuality","nativeLimitations","nativeEffectiveVisibility","geometryStatus","qualityAttributes"))
                if(raw.has(key))assertEquals(JsonUtils.write(raw.get(key)),JsonUtils.write(model.get(key)),"Original business field lost: "+key);
            for(String key:Arrays.asList("kind","physicalPageNo","partName","nodePath","partSha256","sourceSha256"))if(raw.path("sourcePosition").has(key))
                assertEquals(raw.path("sourcePosition").get(key),model.path("sourcePosition").get(key),"Original source position lost: "+key);
            if(raw.path("textTransform").has("textChannel"))assertEquals(raw.path("textTransform").get("textChannel"),model.path("textTransform").get("textChannel"));
            if(raw.has("cells"))assertEquals(raw.get("cells"),model.get("cells"),"Whole original table cells must remain");
        }
    }
    static void assertRelations(JsonNode original,JsonNode projected){
        assertEquals(original.size(),projected.size());
        for(int i=0;i<original.size();i++)for(String key:Arrays.asList("type","status","scope","basis","humanConfirmed","fromUnitId","toUnitId","fromRegionId","toRegionId","fromBlockId","toBlockId","fromCellId","toCellId","condition","qualifiers","exceptions"))
            if(original.get(i).has(key))assertEquals(original.get(i).get(key),projected.get(i).get(key),"Relation semantic endpoint or declaration lost: "+key);
    }
    @Test void docxUnknownsKeepExactSelectedUnitAttributesAndUnsupportedSchemasReject()throws Exception{
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire(true);List<VettingCorpus.Chunk> chunks=w.load().chunks();
        OerModelMaterial.Request request=new OerModelMaterial.Request();
        for(VettingCorpus.Chunk c:chunks){JsonNode projected=request.project(c.getCanonicalEvidence());List<JsonNode> expected=new ArrayList<>();
            for(JsonNode u:c.getCanonicalEvidence().getUnknowns())if(c.getCanonicalEvidence().getUnitId().equals(u.path("unitId").asText()))expected.add(u);
            assertEquals(JsonUtils.mapper().valueToTree(expected),projected.path("unknowns"));assertLiteralParts(c.getCanonicalPayload().path("parts"),projected.path("parts"));
        }
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer p=JsonUtils.read(JsonUtils.write(chunks.get(0).getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)p.getUnknowns().get(0)).put("unrecognizedMeaning","Keep me unknown");
        assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request().project(p));
    }
    @Test void exactPdfRegionUnknownsAreLocalWhileSourceUnknownSummariesNeverUpgradeState()throws Exception{
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();OerModelMaterial.Request request=new OerModelMaterial.Request();
        int declarations=0,local=0;for(VettingCorpus.Chunk c:w.load("vetting").chunks()){
            JsonNode p=request.project(c.getCanonicalEvidence());if(p.has("sourceDeclaration")){declarations++;assertFalse(p.path("sourceDeclaration").path("limits").path("semanticScopeVerified").asBoolean());assertEquals("unknown",p.path("sourceDeclaration").path("limits").path("wholeOriginalSourceCoverage").asText());}
            Set<String> regions=new HashSet<>();for(JsonNode part:c.getCanonicalEvidence().getParts())regions.add(part.path("referenceRegionId").asText());
            for(JsonNode u:p.path("unknowns")){local++;assertEquals("unknown",u.path("status").asText());assertTrue(regions.stream().anyMatch(r->u.path("path").asText().startsWith("regions."+r+".")));assertTrue(c.getCanonicalEvidence().getUnknowns().findValues("reason").contains(u.get("reason")));}
        }assertEquals(1,declarations);assertTrue(local>0);
    }
    @Test void nativeWholeTableRetainsBlanksMergeValuesNestingAndOriginalCellPositions()throws Exception{
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire(true);VettingCorpus.Chunk table=w.load().chunks().get(1);
        JsonNode raw=table.getCanonicalPayload(),projected=new OerModelMaterial.Request().project(table.getCanonicalEvidence());
        assertLiteralParts(raw.path("parts"),projected.path("parts"));assertEquals(5,projected.path("parts").size());
        assertEquals(3,projected.path("parts").findValues("text").stream().filter(n->n.asText().isEmpty()).count());
        for(String key:Arrays.asList("nativeParagraphIds","nativeTableIds"))assertEquals(raw.path("tableGroup").get(key),projected.path("tableGroup").get(key));
        for(String key:Arrays.asList("cellId","cellIndex","rowIndex","childParagraphIds","nestedTableIds","val","present","gridInterpretationStatus","rowSpanStatus","nodePath","partName"))
            assertEquals(raw.path("tableGroup").findValues(key),projected.path("tableGroup").findValues(key),"Whole-table semantic structure lost: "+key);
        assertFalse(JsonUtils.write(projected).contains("observerIdentitySha256"));assertFalse(JsonUtils.write(projected).contains("relativePath"));
    }
    @Test void actualAdviceWriterKeepsCompleteBodyWithoutSerializingWholeSourceCaptureForEachMember() throws Exception {
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();
        List<VectorStore.SearchHit> hits=w.service.adviceHits(w.load("advisory"),"source-only replay");
        String immutable=JsonUtils.write(hits);AdviceService advice=mock(AdviceService.class,CALLS_REAL_METHODS);
        Method render=AdviceService.class.getDeclaredMethod("renderExcerpts",List.class);render.setAccessible(true);
        String prompt=(String)render.invoke(advice,hits);
        Path out=Paths.get(System.getenv("CONSENSE_OER_REPLAY_OUTPUT"));
        Files.write(out.resolve("actual-advice-model-material.txt"),prompt.getBytes(StandardCharsets.UTF_8));
        OerAdviceDisplayDedupTest.assertCompleteBodies(hits,prompt);
        assertTrue(prompt.contains("Canonical body: hitMembership[0]"),"Complete literal body must be directly navigable in the same request dictionary");
        assertTrue(prompt.contains("legalClosureVerified=false"));assertTrue(prompt.contains("descendants are unranked source context"));
        assertEquals(immutable,JsonUtils.write(hits),"Projection must not mutate the public source evidence");
        assertFalse(prompt.contains("\"captureCompleteness\""),"Whole-source capture repeated in model input");
        assertFalse(prompt.contains("\"nativeGlyphMembershipRef\""),"CAS proof belongs to the source verifier");
        assertTrue(prompt.length()<250000,"Actual 31-member source context metadata dominates model request: "+prompt.length());
    }
    @Test void actualVettingModelMaterialKeepsBodiesButUsesASeparateBoundedProjection()throws Exception {
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();
        List<VettingCorpus.Chunk> chunks=w.load("vetting").chunks();String immutable=JsonUtils.write(chunks);
        List<Map<String,Object>> projected=VettingSourceMaterial.project(chunks);String wire=VettingSourceMetadataWire.write(projected);
        JsonNode decoded=VettingSourceMetadataWire.decode(wire);
        for(int i=0;i<chunks.size();i++)assertEquals(chunks.get(i).getContent(),decoded.get(i).path("content").asText());
        assertEquals(immutable,JsonUtils.write(chunks));
        assertFalse(wire.contains("captureCompleteness"),"Request sharing still encodes whole-source proof tree");
        assertFalse(wire.contains("nativeGlyphMembershipRef"));
        assertTrue(wire.length()<400000,"Selected field projection must precede wire budgeting: "+wire.length());
    }
}
