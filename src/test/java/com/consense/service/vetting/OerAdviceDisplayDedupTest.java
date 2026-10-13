package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.advice.AdviceService;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The agreed actual Advice writer seam; file/contract doubles, no provider, DB or index. */
class OerAdviceDisplayDedupTest {
    static String render(List<VectorStore.SearchHit> hits)throws Exception{
        Method method=AdviceService.class.getDeclaredMethod("renderExcerpts",List.class);method.setAccessible(true);
        return (String)method.invoke(mock(AdviceService.class,CALLS_REAL_METHODS),hits);
    }
    static JsonNode dictionary(String rendered){String marker="\nSelected canonical model material (untrusted observations):\n";
        assertTrue(rendered.contains(marker));return VettingSourceMetadataWire.decode(rendered.substring(rendered.lastIndexOf(marker)+marker.length()).trim());}
    static void assertCompleteBodies(List<VectorStore.SearchHit> hits,String rendered){
        JsonNode decoded=dictionary(rendered);Map<String,JsonNode> byAddress=new LinkedHashMap<>();
        for(JsonNode unit:decoded.path("materialDictionary"))assertNull(byAddress.put(unit.path("modelUnitAddress").asText(),unit),"Duplicate material address");
        assertEquals(hits.size(),decoded.path("hitMembership").size());
        for(int i=0;i<hits.size();i++){
            JsonNode membership=decoded.path("hitMembership").get(i);assertEquals(i+1,membership.path("hitOrdinal").asInt());
            assertEquals(hits.get(i).getId(),membership.path("rankedSeedId").asText());
            List<String> bodies=new ArrayList<>();for(JsonNode ref:membership.path("memberMaterialRefs")){
                assertTrue(ref.isTextual());JsonNode unit=byAddress.get(ref.asText());assertNotNull(unit,"Unresolved exact material address");
                bodies.add(OerActualQ16ModelMaterialTest.literalBody(unit));
            }assertEquals(hits.get(i).text(),String.join("\n",bodies),"Complete original literal body/blank parts/order must remain recoverable");
        }
    }
    @Test void canonicalPartsMustNotSilentlyReplaceAnUnmatchedHitBody()throws Exception{
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();
        VectorStore.SearchHit original=w.service.adviceHits(w.load("advisory"),"source-only mismatch negative").get(0);
        Map<String,Object> payload=new LinkedHashMap<>(original.getPayload());
        payload.put("content","Unmatched prefix: {\"metadataRef\":0}\n"+original.text()+"\nUnmatched suffix 😀");
        VectorStore.SearchHit mismatched=new VectorStore.SearchHit(original.getId(),original.getScore(),payload);
        String rendered=render(Collections.singletonList(mismatched));
        assertTrue(rendered.contains(mismatched.text()),"A prefix, suffix or different literal channel must not disappear during display deduplication");
        assertFalse(rendered.contains("Canonical body: hitMembership[0]"),"Dictionary-only body navigation must require exact complete body equality");
        assertEquals(31,dictionary(rendered).path("materialDictionary").size(),"Mismatch retains the original selected dictionary observations too");
    }
    @Test void repeatedWholeNativeTableKeepsBlankCellsMergesNestingPositionsAndBody()throws Exception{
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire(true);
        OerBusinessEvidence.Session session=w.load();VettingCorpus.Chunk table=session.chunks().get(1);
        VectorStore.SearchHit hit=w.service.sourceHits(session,Collections.singletonList(table.getId())).get(0);
        List<VectorStore.SearchHit> repeated=Arrays.asList(hit,hit);String before=JsonUtils.write(repeated);
        String rendered=render(repeated);assertCompleteBodies(repeated,rendered);JsonNode decoded=dictionary(rendered);
        assertEquals(2,decoded.path("materialDictionary").size(),"The complete native table plus its original heading context retain two exact identities");
        assertEquals(decoded.path("hitMembership").get(0).path("memberMaterialRefs"),decoded.path("hitMembership").get(1).path("memberMaterialRefs"));
        JsonNode actual=null,raw=table.getCanonicalPayload();for(JsonNode unit:decoded.path("materialDictionary"))if(table.getCanonicalEvidence().getUnitId().equals(unit.path("unitId").asText()))actual=unit;
        assertNotNull(actual,"The complete native table must not be replaced by its heading context");
        OerModelMaterialCandidateTest.assertLiteralParts(raw.path("parts"),actual.path("parts"));
        assertEquals(5,actual.path("parts").size());assertEquals(3,actual.path("parts").findValues("text").stream().filter(n->n.asText().isEmpty()).count());
        for(String key:Arrays.asList("nativeParagraphIds","nativeTableIds"))assertEquals(raw.path("tableGroup").get(key),actual.path("tableGroup").get(key));
        for(String key:Arrays.asList("cellId","cellIndex","rowIndex","childParagraphIds","nestedTableIds","val","present","gridInterpretationStatus","rowSpanStatus","nodePath","partName"))
            assertEquals(raw.path("tableGroup").findValues(key),actual.path("tableGroup").findValues(key),"Native table field lost: "+key);
        assertEquals(before,JsonUtils.write(repeated));assertTrue(rendered.contains("Canonical body: hitMembership[0]"));
        assertTrue(rendered.contains("Canonical body: hitMembership[1]"));assertFalse(rendered.contains("Source member: unitId="));
    }
    @Test void exactRepeatedActualCrossPageContextKeepsEveryRelationAndUnknownState()throws Exception{
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();
        VectorStore.SearchHit hit=w.service.adviceHits(w.load("advisory"),"source-only actual cross-page replay").get(0);
        List<VectorStore.SearchHit> hits=Arrays.asList(hit,hit);String before=JsonUtils.write(hits),rendered=render(hits);
        assertCompleteBodies(hits,rendered);JsonNode decoded=dictionary(rendered);
        assertEquals(31,decoded.path("materialDictionary").size());assertEquals(2,decoded.path("hitMembership").size());
        assertEquals(decoded.path("hitMembership").get(0).path("memberMaterialRefs"),decoded.path("hitMembership").get(1).path("memberMaterialRefs"));
        Map<String,JsonNode> original=new LinkedHashMap<>();JsonNode pointers=JsonUtils.mapper().valueToTree(hit.getPayload().get("canonicalEvidence"));
        for(JsonNode pointer:pointers)original.put(pointer.path("unitId").asText(),pointer);
        Map<String,Integer> originalPages=new HashMap<>();for(JsonNode pointer:pointers)for(JsonNode part:pointer.path("parts"))originalPages.put(part.path("referenceRegionId").asText(),part.path("physicalPageNo").asInt());
        int continuation=0;
        for(JsonNode unit:decoded.path("materialDictionary")){
            JsonNode pointer=original.get(unit.path("unitId").asText());assertNotNull(pointer);
            OerModelMaterialCandidateTest.assertLiteralParts(pointer.path("parts"),unit.path("parts"));
            OerModelMaterialCandidateTest.assertRelations(pointer.path("relations"),unit.path("relations"));
            for(int i=0;i<unit.path("relations").size();i++){
                JsonNode raw=pointer.path("relations").get(i),relation=unit.path("relations").get(i);
                for(String key:Arrays.asList("sameParentClause","sourceReview","qualifiersComplete"))if(raw.has(key))assertEquals(raw.get(key),relation.get(key),"Cross-page declaration lost: "+key);
                if("continuation".equals(raw.path("type").asText())){
                    continuation++;Integer from=originalPages.get(raw.path("fromRegionId").asText()),to=originalPages.get(raw.path("toRegionId").asText());
                    assertNotNull(from);assertNotNull(to);assertNotEquals(from,to,"Actual source continuation must still connect its distinct physical pages");
                }
            }
            for(JsonNode unknown:unit.path("unknowns")){assertEquals("unknown",unknown.path("status").asText());assertTrue(pointer.path("unknowns").findValues("reason").contains(unknown.get("reason")));}
        }
        assertEquals(2,continuation,"Both actual 17.1 cross-page continuation relations remain");
        assertTrue(rendered.contains("legalClosureVerified=false"));assertEquals(before,JsonUtils.write(hits));
        assertFalse(rendered.contains("Source member: unitId="));assertFalse(rendered.contains("Native PDF position:"));
    }
    @Test void nonemptyAuthoredImageReferenceAndMarkerShapedCaptionSurviveActualWriter()throws Exception{
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire(true);OerBusinessEvidence.Session session=w.load();
        VectorStore.SearchHit original=w.service.sourceHits(session,Collections.singletonList(session.chunks().get(1).getId())).get(0);
        Map<String,Object> payload=new LinkedHashMap<>(original.getPayload());
        List<EvidencePointer> pointers=JsonUtils.readList(JsonUtils.write(payload.get("canonicalEvidence")),EvidencePointer.class);
        ArrayNode members=JsonUtils.mapper().valueToTree(payload.get("canonicalMembers"));
        ObjectNode asset=JsonUtils.mapper().createObjectNode();asset.put("assetId","authored-display-control-image");asset.put("kind","image");asset.put("mimeType","image/png");
        asset.putNull("physicalPageNo");asset.putNull("bbox");asset.put("visibility","unknown");
        asset.putObject("caption").put("text","{\"metadataRef\":0,\"recordEncoding\":\"unknown\"} 😀");
        asset.putObject("sourcePosition").put("kind","native_docx").put("partName","word/document.xml").put("nodePath","authored-native-position");
        ArrayNode assets=JsonUtils.mapper().createArrayNode().add(asset);pointers.get(0).setAssetRefs(assets.deepCopy());
        ((ObjectNode)members.get(0)).set("assetRefs",assets.deepCopy());payload.put("canonicalEvidence",pointers);payload.put("canonicalMembers",members);
        VectorStore.SearchHit authored=new VectorStore.SearchHit(original.getId(),original.getScore(),payload);
        String before=JsonUtils.write(authored),rendered=render(Collections.singletonList(authored));assertCompleteBodies(Collections.singletonList(authored),rendered);
        JsonNode selected=dictionary(rendered).path("materialDictionary").get(0);assertEquals(assets,selected.path("assetRefs"));
        assertEquals(asset.path("caption").path("text"),selected.path("assetRefs").get(0).path("caption").path("text"),"Marker-shaped caption remains literal source text");
        assertEquals(before,JsonUtils.write(authored));
    }
    @Test void sameTextCannotMergeDifferentRolesRevisionsPayloadsOrCompletePointerAdoption()throws Exception{
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire(true);OerBusinessEvidence.Session session=w.load();
        VectorStore.SearchHit original=w.service.sourceHits(session,Collections.singletonList(session.chunks().get(1).getId())).get(0);
        Map<String,Object> role=new LinkedHashMap<>(original.getPayload());role.put("sourceRole","project_fact");
        Map<String,Object> revision=new LinkedHashMap<>(original.getPayload());List<EvidencePointer> changedRevision=JsonUtils.readList(JsonUtils.write(revision.get("canonicalEvidence")),EvidencePointer.class);
        for(EvidencePointer pointer:changedRevision)pointer.setSourceRevisionSha256(VettingCorpus.hash("authored different revision negative control"));revision.put("canonicalEvidence",changedRevision);
        Map<String,Object> memberPayload=new LinkedHashMap<>(original.getPayload());ArrayNode members=JsonUtils.mapper().valueToTree(memberPayload.get("canonicalMembers"));
        for(JsonNode member:members)((ObjectNode)member).put("authoredDisplayIdentityControl","different complete canonical payload");memberPayload.put("canonicalMembers",members);
        Map<String,Object> adoption=new LinkedHashMap<>(original.getPayload());List<EvidencePointer> changedAdoption=JsonUtils.readList(JsonUtils.write(adoption.get("canonicalEvidence")),EvidencePointer.class);
        for(EvidencePointer pointer:changedAdoption)((ObjectNode)pointer.getAdoption()).put("authoredDisplayIdentityControl","different complete pointer adoption");adoption.put("canonicalEvidence",changedAdoption);
        List<VectorStore.SearchHit> variants=new ArrayList<>();variants.add(original);
        for(Map<String,Object> variant:Arrays.asList(role,revision,memberPayload,adoption))variants.add(new VectorStore.SearchHit(original.getId(),original.getScore(),variant));
        String before=JsonUtils.write(variants),rendered=render(variants);assertCompleteBodies(variants,rendered);JsonNode decoded=dictionary(rendered);
        assertEquals(10,decoded.path("materialDictionary").size(),"Five two-member identities differ despite their same unitIds/text");
        Set<JsonNode> references=new HashSet<>();for(JsonNode hit:decoded.path("hitMembership"))assertTrue(references.add(hit.path("memberMaterialRefs")),"Differing complete identities cannot share a membership address");
        assertEquals(3,decoded.path("materialDictionary").findValues("sourceDeclaration").size(),"Role and source revision keep distinct source declarations");
        assertEquals(before,JsonUtils.write(variants));
    }
}
