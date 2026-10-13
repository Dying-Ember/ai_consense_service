package com.consense.service.vetting;
import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.*;
import com.consense.service.sources.*;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/** Pure complete public-wire business/projection replay; model, network and live DB are absent. */
class OerNativePdfFamilyBusinessTest {
    static class Wire {
        final CanonicalEvidenceService registry=mock(CanonicalEvidenceService.class);final OerCanonicalRetrievalClient client=mock(OerCanonicalRetrievalClient.class);
        final CanonicalSourceBinding binding;final JsonNode root;final SourceDocument document=new SourceDocument();final OerBusinessEvidence service;
        Wire(String fixture)throws Exception{this(fixture,false);}
        Wire(String fixture,boolean rawWire)throws Exception{
            binding=rawWire?NativePdfSourceFamilyFixture.bindingFromRaw(fixture):NativePdfSourceFamilyFixture.binding(fixture);root=JsonUtils.parse(binding.getSnapshotJson());document.setId(binding.getDocumentId());document.setProjectId(binding.getProjectId());document.setFileKey("GCC");
            when(registry.currentBindings(eq(binding.getProjectId()),anyString(),eq(Collections.singletonList(binding.getDocumentId())))).thenReturn(Collections.singletonList(binding));
            when(registry.pointers(eq(binding.getProjectId()),eq(binding.getDocumentId()),eq(binding.getProjectionSha256()),anyString())).thenAnswer(i->{List<EvidencePointer> pointers=new ArrayList<>();root.path("chunks").forEach(p->pointers.add(NativePdfSourceFamilyFixture.pointer(binding,root,p,i.getArgument(3))));return pointers;});
            when(client.sourceUnitPolicy(anyString(),anyString(),anyList())).thenReturn(VettingRetrievalSourceUnits.SHARED_NOTE_POLICY);
            CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:1");service=new OerBusinessEvidence(registry,client,config);
        }
        OerBusinessEvidence.Session load(String module){return service.load(binding.getProjectId(),module,Collections.singletonList(document));}
    }
    @Test void fullAuthoredV2FamilyLoadsForBothModulesWithUntouchedLiteralAndOrigin()throws Exception{
        for(String module:new String[]{"advisory","vetting"}){Wire w=new Wire("authored-family-v2.json");OerBusinessEvidence.Session session=w.load(module);assertEquals(2,session.chunkCount());
            for(int i=0;i<session.chunkCount();i++){VettingCorpus.Chunk view=session.chunks().get(i);assertEquals(w.root.path("chunks").get(i),view.getCanonicalPayload());assertNull(OerBusinessEvidence.canonicalViewStatus(view));assertEquals(view.getCanonicalPayload().path("sourceFamilyOriginRef"),view.getCanonicalEvidence().getSourceFamilyOriginRef());}
        }
    }
    @Test void newPinsParticipateInSessionSignatureAndInvalidateWhileProjectionAddressStays()throws Exception{
        for(boolean composition:new boolean[]{true,false}){Wire w=new Wire("authored-family-v1.json");OerBusinessEvidence.Session session=w.load("advisory");String before=session.identitySha256();if(composition)w.binding.setFamilyCompositionSha256("b".repeat(64));else w.binding.setFamilyComponentSetSha256("b".repeat(64));
            assertNotEquals(before,w.service.currentIdentity(w.binding.getProjectId(),"advisory",Collections.singletonList(w.document)));assertThrows(RuntimeException.class,()->w.service.assertCurrent(session));}
    }
    @Test void componentTaggedUnknownsShareOneSourceDeclarationAndSelectOnlyOwnedRegion()throws Exception{
        Wire w=new Wire("authored-family-v1.json");OerBusinessEvidence.Session session=w.load("advisory");OerModelMaterial.Request request=new OerModelMaterial.Request();int declarations=0;
        for(VettingCorpus.Chunk view:session.chunks()){
            JsonNode material=request.project(view.getCanonicalEvidence(),view.getCanonicalPayload(),view.getRole());if(material.has("sourceDeclaration")){declarations++;assertFalse(material.path("sourceDeclaration").path("limits").path("sourceQuality").path("legalQualityAccepted").asBoolean());assertEquals(6,material.path("sourceDeclaration").path("declaredUnknownCount").asInt());}
            assertEquals(view.getCanonicalPayload().path("sourceQuality"),material.path("sourceQuality"));assertEquals(view.getCanonicalPayload().path("adoption").path("unitId"),material.path("adoption").path("unitId"));
            assertEquals(1,material.path("unknowns").size());for(JsonNode unknown:material.path("unknowns"))assertEquals(view.getCanonicalPayload().path("sourceFamilyOriginRef").path("componentOrdinal"),unknown.path("componentOrdinal"));
            OerModelMaterialCandidateTest.assertLiteralParts(view.getCanonicalPayload().path("parts"),material.path("parts"));assertFalse(JsonUtils.write(material).contains("relativePath"));
        }assertEquals(1,declarations);
    }
    @Test void familyOriginAndBothPinsAndExistingRenderFieldsRoundTripWithoutNativeAlias()throws Exception{
        Wire w=new Wire("authored-family-v1.json");EvidencePointer original=w.load("advisory").chunks().get(0).getCanonicalEvidence();original.setSourcePageRenderRefs(JsonUtils.mapper().createArrayNode());original.setSourceRenderEvidenceIdentitySha256("c".repeat(64));original.setSourceRenderLimitations(JsonUtils.mapper().createArrayNode().add("unknown typed render"));
        EvidencePointer copy=JsonUtils.read(JsonUtils.write(original),EvidencePointer.class);assertEquals(original,copy);assertNull(copy.getNativePdfEvidence());assertEquals(original.getSourceFamilyOriginRef(),copy.getSourceFamilyOriginRef());
    }
    @Test void familyDisplayOriginPinAndUnknownDriftAreRejectedByBusinessView()throws Exception{
        for(String field:new String[]{"origin","pin","unknown","ledger"}){Wire w=new Wire("authored-family-v1.json");VettingCorpus.Chunk c=w.load("advisory").chunks().get(0);
            if(field.equals("origin"))((ObjectNode)c.getCanonicalEvidence().getSourceFamilyOriginRef()).put("componentOrdinal",1);else if(field.equals("pin"))c.getCanonicalEvidence().setFamilyComponentSetSha256("b".repeat(64));else if(field.equals("unknown"))((ArrayNode)c.getCanonicalEvidence().getUnknowns()).remove(0);else ((ObjectNode)c.getCanonicalEvidence().getNativePdfSourceFamilyEvidence().path("sourceFamilyComponents").get(0)).put("projectionSha256","b".repeat(64));
            assertNotNull(OerBusinessEvidence.canonicalViewStatus(c));}
    }
    @Test void allAuthoredV2SourceUnitContextsMatchFrozenPythonSummariesInOrder()throws Exception{
        JsonNode root=NativePdfSourceFamilyFixture.snapshot("authored-family-v2.json"),expected=NativePdfSourceFamilyFixture.snapshot("authored-family-v2-source-units.json");Map<String,JsonNode> parents=new LinkedHashMap<>();root.path("chunks").forEach(p->parents.put(p.path("id").asText(),p));int i=0;
        for(JsonNode p:parents.values()){
            JsonNode actual=OerCanonicalSourceUnits.resolveAttached(parents,p,true),wanted=expected.get(i++);
            for(String f:new String[]{"originIds","unitId","status","reason","requiredMemberIds","blockIds","observedRanges"})assertEquals(wanted.path(f),actual.path(f),p.path("id")+" "+f);
            for(String context:new String[]{"hierarchyContext","attachmentContext"})if(wanted.has(context))for(String f:new String[]{"policy","status","reason","contextId","requiredMemberIds","memberPayloadSha256","edgeDeclarations"})assertEquals(wanted.path(context).path(f),actual.path(context).path(f),p.path("id")+" "+context+" "+f);
        }assertEquals(root.path("chunks").size(),i);
    }
}
