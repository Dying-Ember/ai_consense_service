package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.CanonicalSourceBindingRepository;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Pure authored wire and mocked catalog/backend; no TCP, live DB, model or original acceptance. */
class CanonicalCompleteCollectionServiceTest {
    @Test void reviewedRegistrationActuallyReadsEveryCompleteEnvelopeBeforeSavingRoot() throws Exception {
        JsonNode root=NativeDocumentCollectionFixture.root();
        ObjectNode health=JsonUtils.mapper().createObjectNode(),oer=health.putObject("oer");
        oer.put("inputContract","canonical-source-chunks-upstream-scope-and-parser-projection-v1");
        oer.put("assetReturnContract","source-and-projection-bound-content-addressed-assets-v1");
        oer.put("canonicalProjectionEndpoint","/project");
        oer.putObject("scopePolicy").put("manifestSha256",root.path("manifestMetadata").path("scope").path("scopePolicySha256").asText());
        oer.set("nativeCanonical",NativeCanonicalFixture.capability());
        ObjectNode capability=NativeDocumentCollectionFixture.capability();
        capability.put("envelopeBatchSchema","consense.native-document-envelope-batch.v1");
        capability.put("envelopeVersion","complete-header-whole-metadata-records-v1");
        capability.put("envelopeBatchEndpoint","/canonical/{projectionSha256}/envelopes/{ordinal}");
        capability.put("completeHeaderTransport","root-enumerated-whole-metadata-record-batches");
        oer.set("nativeDocumentCollection",capability);
        ObjectNode algorithms=health.putObject("retrieval").putObject("sourceWindows").putObject("algorithms");
        for(String name:NativeCanonicalContract.ALGORITHMS)algorithms.put(name,VettingCorpus.hash("authored "+name));
        for(String name:Arrays.asList("native_projection_collection.py","native_collection_registry.py","oer_native_collection_server.py","native_collection_envelope.py"))
            algorithms.put(name,VettingCorpus.hash("authored "+name));
        List<Integer> envelopes=new ArrayList<>(),batches=new ArrayList<>();Map<Integer,byte[]> envelopeBytes=new HashMap<>(),chunkBytes=new HashMap<>();
        envelopeBytes.put(0,NativeCanonicalFixture.bytes("authored-complete-base-envelope-0.json"));
        for(int n=0;n<3;n++)chunkBytes.put(n,NativeDocumentCollectionFixture.batch(n));
        CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class,call->{
            String method=call.getMethod().getName();
            if(method.equals("health"))return JsonUtils.write(health);
            if(method.equals("snapshot"))return NativeDocumentCollectionFixture.rootRaw();
            if(method.equals("batch")){int n=call.getArgument(1);batches.add(n);return NativeDocumentCollectionFixture.reply(chunkBytes.get(n));}
            if(method.equals("envelope")){int n=call.getArgument(1);envelopes.add(n);return NativeDocumentCollectionFixture.reply(envelopeBytes.get(n));}
            throw new AssertionError("Unexpected backend method "+method);
        });
        SourceDocument document=new SourceDocument();document.setId(42L);document.setProjectId("authored-complete");
        document.setSourceSha256(root.path("sourceSha256").asText());document.setReviewRole("tender");document.setTextContent("authored parser text");document.setStructuredContentJson("[]");
        OerDocumentIdentity route=new OerDocumentIdentity();route.setSourceSha256(document.getSourceSha256());route.setKind("PRE");route.setRole("tender");
        route.setIdentityStatus("content_supported");route.setSourcePurpose("project_document");route.setEligibleModules(Arrays.asList("vetting","advisory"));
        document.setOerIdentityJson(JsonUtils.write(route));
        CanonicalCurrentSourceReader sources=mock(CanonicalCurrentSourceReader.class);
        when(sources.currentDocument("authored-complete",42L,"vetting")).thenReturn(document);
        CanonicalSourceBindingRepository bindings=mock(CanonicalSourceBindingRepository.class);
        when(bindings.findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString())).thenReturn(Optional.empty());
        when(bindings.saveAndFlush(any())).thenAnswer(c->c.getArgument(0));
        CanonicalEvidenceService service=new CanonicalEvidenceService(bindings,backend,sources);
        JsonNode manifest=root.path("manifestMetadata");
        CanonicalSourceBinding binding=service.registerReviewed("authored-complete",42L,new CanonicalEvidenceService.ReviewedRegistration(
            root.path("projectionSha256").asText(),document.getSourceSha256(),VettingCorpus.sourceHash(document),root.path("source").path("sourceRevisionSha256").asText(),
            manifest.path("scope").path("scopePolicySha256").asText(),root.path("manifestSha256").asText(),manifest.path("reference").path("evidenceSha256").asText(),"vetting"));
        assertEquals(Collections.singletonList(0),envelopes,"Chunks alone do not consume complete global metadata");
        assertEquals(Arrays.asList(0,1,2),batches);
        assertEquals(root,JsonUtils.parse(binding.getSnapshotJson()));assertFalse(JsonUtils.parse(binding.getSnapshotJson()).has("chunks"));
        when(bindings.findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString())).thenReturn(Optional.of(binding));
        when(bindings.findByProjectIdAndDocumentIdOrderByIdDesc("authored-complete",42L)).thenReturn(Collections.singletonList(binding));
        String projection=binding.getProjectionSha256();JsonNode expected=NativeCanonicalFixture.snapshot();
        List<Runnable> operations=Arrays.asList(
            ()->assertEquals(root,JsonUtils.parse(service.snapshot("authored-complete",42L,projection,"vetting"))),
            ()->assertEquals(Collections.singletonList(binding),service.currentBindings("authored-complete","vetting",Collections.singletonList(42L))),
            ()->assertEquals(expected.path("chunks"),service.chunks(binding,"vetting")),
            ()->{com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=service.pointer("authored-complete",42L,projection,"table","vetting");
                assertEquals(expected.path("chunks").get(1).path("tableGroup"),pointer.getTableGroup());assertEquals(projection,pointer.getProjectionSha256());assertEquals(1,pointer.getUnknowns().size());},
            ()->assertEquals(3,service.pointers("authored-complete",42L,projection,"vetting").size()));
        for(Runnable operation:operations){envelopes.clear();batches.clear();operation.run();
            assertEquals(Collections.singletonList(0),envelopes);assertEquals(Arrays.asList(0,1,2),batches);}
        // Each public operation must cold-read again; a header lost after adoption
        // cannot be hidden by a stored root or a previously successful read.
        byte[] actual=envelopeBytes.remove(0);
        for(Runnable operation:operations)assertThrows(CanonicalEvidenceException.class,operation::run);
        envelopeBytes.put(0,actual);
        // Selecting the table still verifies a nonselected body batch.
        chunkBytes.remove(2);
        assertThrows(CanonicalEvidenceException.class,()->service.pointer("authored-complete",42L,projection,"table","vetting"));
    }
}
