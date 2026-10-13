package com.consense.service.sources;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.document.OerDocumentIdentity;
import com.consense.repository.CanonicalSourceBindingRepository;
import com.consense.service.vetting.VettingCorpus;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** Mocked source/CAS boundaries with the exact frozen Python family wire; no live registration. */
class CanonicalNativePdfFamilyEvidenceServiceTest {
    static class Wire {
        final String raw;final JsonNode root;final SourceDocument document=new SourceDocument();
        final CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);final CanonicalCurrentSourceReader reader=mock(CanonicalCurrentSourceReader.class);
        final CanonicalSourceBindingRepository repository=mock(CanonicalSourceBindingRepository.class);final CanonicalEvidenceService service;
        final List<CanonicalSourceBinding> rows=new ArrayList<>();final ObjectNode health=JsonUtils.mapper().createObjectNode();
        Wire()throws Exception{
            raw=NativePdfSourceFamilyFixture.raw("authored-family-v1.json");root=JsonUtils.parse(raw);
            document.setId(9001L);document.setProjectId("family-project");document.setFileKey("PRE");document.setReviewRole(root.path("projectionIdentity").path("role").asText());document.setSourceSha256(root.path("source").path("sourceSha256").asText());
            document.setTextContent("authored parsed text");document.setStructuredContentJson("[]");
            OerDocumentIdentity id=new OerDocumentIdentity();id.setSourceSha256(document.getSourceSha256());id.setSourcePurpose(root.path("projectionIdentity").path("sourcePurpose").asText());id.setRole(document.getReviewRole());id.setIdentityStatus("content_supported");id.setEligibleModules(Arrays.asList("vetting","advisory"));document.setOerIdentityJson(JsonUtils.write(id));
            when(reader.currentDocument(eq("family-project"),eq(9001L),anyString())).thenReturn(document);
            ObjectNode oer=health.putObject("oer");oer.put("inputContract","canonical-source-chunks-upstream-scope-and-parser-projection-v1");oer.put("assetReturnContract","source-and-projection-bound-content-addressed-assets-v1");oer.put("canonicalProjectionEndpoint","/project");oer.putObject("scopePolicy").put("manifestSha256",root.path("manifest").path("scope").path("scopePolicySha256").asText());oer.set("nativePdfSourceFamily",NativePdfSourceFamilyFixture.capability());
            ObjectNode algorithms=health.putObject("retrieval").putObject("sourceWindows").putObject("algorithms");root.path("manifest").path("software").fields().forEachRemaining(e->algorithms.set(e.getKey(),e.getValue().path("sha256")));
            for(String f:Arrays.asList("unified_source_registry.py","retrieval_source_units.py","oer_unified_server.py"))algorithms.put(f,"a".repeat(64));
            when(backend.health()).thenAnswer(i->JsonUtils.write(health));when(backend.snapshot(anyString(),anyString())).thenReturn(raw);
            when(repository.findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString())).thenAnswer(i->rows.stream().findFirst());
            when(repository.findByProjectIdAndDocumentIdOrderByIdDesc(anyString(),anyLong())).thenAnswer(i->new ArrayList<>(rows));
            when(repository.saveAndFlush(any())).thenAnswer(i->{CanonicalSourceBinding row=i.getArgument(0);row.setId(1L);rows.add(row);return row;});service=new CanonicalEvidenceService(repository,backend,reader);
        }
        CanonicalEvidenceService.ReviewedRegistration review(String adoption,String reference,String composition,String componentSet){return new CanonicalEvidenceService.ReviewedRegistration(root.path("projectionSha256").asText(),document.getSourceSha256(),VettingCorpus.sourceHash(document),root.path("source").path("sourceRevisionSha256").asText(),root.path("manifest").path("scope").path("scopePolicySha256").asText(),adoption,reference,composition,componentSet,"advisory");}
        CanonicalSourceBinding register(){return service.registerReviewed("family-project",9001L,review(null,null,root.path("projectionIdentity").path("compositionSha256").asText(),root.path("projectionIdentity").path("componentSetSha256").asText()));}
    }
    @Test void typedFamilyRegistrationStoresExactSnapshotWithoutBorrowingChildReviewPins()throws Exception{
        Wire w=new Wire();CanonicalSourceBinding row=w.register();assertEquals(w.raw,row.getSnapshotJson());assertNull(row.getAdoptionManifestSha256());assertNull(row.getReviewReferenceSha256());assertNotNull(row.getFamilyCompositionSha256());
        List<EvidencePointer> pointers=w.service.pointers("family-project",9001L,row.getProjectionSha256(),"advisory");assertEquals(2,pointers.size());
        for(int i=0;i<2;i++){EvidencePointer p=pointers.get(i);assertEquals(w.root.path("chunks").get(i).path("sourceFamilyOriginRef"),p.getSourceFamilyOriginRef());assertEquals(NativePdfSourceFamilyContract.unknowns(w.root),p.getUnknowns());assertNull(p.getNativePdfEvidence());assertEquals(NativePdfSourceFamilyContract.VISUAL,p.getVisualReturnStatus());}
        verify(w.reader,times(1)).assertSourceCurrent(eq(row),eq("advisory"));verify(w.backend,times(2)).snapshot(anyString(),anyString());
    }
    @Test void familyRejectsLegacyHashAliasAndMissingFamilyPinBeforePersisting()throws Exception{
        Wire w=new Wire();String c=w.root.path("projectionIdentity").path("compositionSha256").asText(),s=w.root.path("projectionIdentity").path("componentSetSha256").asText();
        assertThrows(RuntimeException.class,()->w.service.registerReviewed("family-project",9001L,w.review(c,s,c,s)));assertThrows(RuntimeException.class,()->w.service.registerReviewed("family-project",9001L,w.review(null,null,null,s)));verify(w.repository,never()).saveAndFlush(any());
    }
    @Test void driftedStoredFamilyPinsCannotReuseAnExistingImmutableRegistration()throws Exception{
        Wire w=new Wire();CanonicalSourceBinding row=w.register();row.setFamilyCompositionSha256("b".repeat(64));assertThrows(RuntimeException.class,w::register);assertThrows(RuntimeException.class,()->w.service.pointers("family-project",9001L,row.getProjectionSha256(),"advisory"));
    }
    @Test void frameCopiesNewPinsAndEveryPointerBeforeFreshExitCheck()throws Exception{
        Wire w=new Wire();CanonicalSourceBinding row=w.register();clearInvocations(w.backend);
        try(CanonicalEvidenceService.VerifiedBindingFrame frame=w.service.verifiedBindings("family-project","advisory",Collections.singletonList(9001L))){
            assertEquals(row.getFamilyCompositionSha256(),frame.bindings().get(0).getFamilyCompositionSha256());assertEquals(2,frame.pointers(9001L,row.getProjectionSha256(),"advisory").size());frame.pointers(9001L,row.getProjectionSha256(),"advisory");verify(w.backend,times(1)).snapshot(anyString(),anyString());
        }
        verify(w.backend,times(2)).snapshot(anyString(),anyString());
    }
    @Test void frameRejectsNewPinChangesAfterRemoteWaitWithoutChangingProjectionSha()throws Exception{
        for(boolean composition:new boolean[]{true,false}){Wire w=new Wire();CanonicalSourceBinding row=w.register();CanonicalEvidenceService.VerifiedBindingFrame frame=w.service.verifiedBindings("family-project","advisory",Collections.singletonList(9001L));
            if(composition)row.setFamilyCompositionSha256("b".repeat(64));else row.setFamilyComponentSetSha256("b".repeat(64));assertThrows(RuntimeException.class,frame::close);}
    }
    @Test void familyCapabilityCannotClaimProjectEndpointOrWholeLegalAcceptance()throws Exception{
        Wire w=new Wire();((ObjectNode)w.health.path("oer").path("nativePdfSourceFamily")).put("projectEndpoint","/project/family");assertThrows(RuntimeException.class,w::register);verify(w.repository,never()).saveAndFlush(any());
    }
}
