package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.*;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingCorpus;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real source bytes/local HTTP; the HTTP stub is not an actual Python/CAS acceptance claim. */
class CanonicalNativeEvidenceServiceTest {
    @TempDir Path directory;
    HttpServer server;CanonicalEvidenceService service;SourceDocument document;ObjectNode snapshot,health;
    List<CanonicalSourceBinding> saved=new ArrayList<>();String upstream;boolean unavailable;int snapshotReads;
    Map<Integer,byte[]> collectionBatches=new HashMap<>(),collectionEnvelopes=new HashMap<>();List<Integer> batchReads=new ArrayList<>(),envelopeReads=new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        snapshot=NativeCanonicalFixture.snapshot();upstream=new String(NativeCanonicalFixture.bytes("authored-native-public-v2.json"),StandardCharsets.UTF_8);
        document=new SourceDocument();document.setId(42L);document.setProjectId("native-test");document.setFileKey("PRE");
        document.setFileName("authored.docx");document.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);document.setReviewRole("tender");
        document.setParseStatus("PARSED");document.setSourceSha256(snapshot.path("source").path("sourceSha256").asText());document.setTextContent("authored parser observation");document.setStructuredContentJson("[]");
        Path original=directory.resolve("authored.docx");Files.write(original,NativeCanonicalFixture.bytes("authored-native-source.docx"));document.setStoragePath(original.toString());
        OerDocumentIdentity route=new OerDocumentIdentity();route.setSourceSha256(document.getSourceSha256());route.setKind("PRE");route.setRole("tender");route.setSourcePurpose("project_document");
        route.setIdentityStatus("content_supported");route.setEligibleModules(Arrays.asList("drafting","vetting","advisory"));document.setOerIdentityJson(JsonUtils.write(route));
        SourceDocumentRepository documents=mock(SourceDocumentRepository.class);CanonicalSourceBindingRepository bindings=mock(CanonicalSourceBindingRepository.class);
        when(documents.findById(42L)).thenReturn(Optional.of(document));when(documents.findVerificationMetadataById(42L)).thenAnswer(call->Optional.of(CatalogMetadataFixtures.verification(document)));when(documents.findCatalogMetadataByProjectId("native-test")).thenAnswer(call->CatalogMetadataFixtures.from(Collections.singletonList(document)));
        when(bindings.findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString())).thenAnswer(call->saved.stream().filter(b->b.getProjectId().equals(call.getArgument(0))&&b.getDocumentId().equals(call.getArgument(1))&&b.getProjectionSha256().equals(call.getArgument(2))).findFirst());
        when(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(anyString(),anyLong())).thenAnswer(call->new ArrayList<>(saved));
        when(bindings.saveAndFlush(any())).thenAnswer(call->{CanonicalSourceBinding b=call.getArgument(0);b.setId((long)saved.size()+1);saved.add(b);return b;});
        health=JsonUtils.mapper().createObjectNode();ObjectNode oer=health.putObject("oer");oer.put("inputContract","canonical-source-chunks-upstream-scope-and-parser-projection-v1");
        oer.put("assetReturnContract","source-and-projection-bound-content-addressed-assets-v1");oer.put("canonicalProjectionEndpoint","/project");
        oer.putObject("scopePolicy").put("manifestSha256",snapshot.path("manifest").path("scope").path("scopePolicySha256").asText());oer.set("nativeCanonical",NativeCanonicalFixture.capability());
        ObjectNode algorithms=health.putObject("retrieval").putObject("sourceWindows").putObject("algorithms");
        for(String name:NativeCanonicalContract.ALGORITHMS)algorithms.put(name,VettingCorpus.hash("authored "+name));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();boolean isHealth="/health".equals(path);if(!isHealth)snapshotReads++;
            byte[] bytes=(isHealth?JsonUtils.write(health):upstream).getBytes(StandardCharsets.UTF_8);int status=!isHealth&&unavailable?404:200;
            if(path.contains("/batches/")){int ordinal=Integer.parseInt(path.substring(path.lastIndexOf('/')+1));batchReads.add(ordinal);bytes=collectionBatches.get(ordinal);if(bytes==null){status=404;bytes=new byte[0];}}
            if(path.contains("/envelopes/")){int ordinal=Integer.parseInt(path.substring(path.lastIndexOf('/')+1));envelopeReads.add(ordinal);bytes=collectionEnvelopes.get(ordinal);if(bytes==null){status=404;bytes=new byte[0];}}
            exchange.sendResponseHeaders(status,bytes.length);try(OutputStream output=exchange.getResponseBody()){output.write(bytes);}});server.start();
        CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(directory.toString());service=new CanonicalEvidenceService(documents,bindings,new CanonicalEvidenceBackend(config),props);
    }
    @AfterEach void close(){if(server!=null)server.stop(0);}
    CanonicalSourceBinding register(){JsonNode manifest=snapshot.path(NativeDocumentCollectionContract.isCollection(snapshot)?"manifestMetadata":"manifest");return service.registerReviewed("native-test",42L,new CanonicalEvidenceService.ReviewedRegistration(
            snapshot.path("projectionSha256").asText(),document.getSourceSha256(),VettingCorpus.sourceHash(document),snapshot.path("source").path("sourceRevisionSha256").asText(),
            manifest.path("scope").path("scopePolicySha256").asText(),snapshot.path("projectionIdentity").path("manifestSha256").asText(),
            manifest.path("reference").path("evidenceSha256").asText(),"vetting"));}
    void collection() throws Exception{
        snapshot=NativeDocumentCollectionFixture.root();upstream=NativeDocumentCollectionFixture.rootRaw();
        ((ObjectNode)health.path("oer")).set("nativeDocumentCollection",NativeDocumentCollectionFixture.capability());
        for(String name:Arrays.asList("native_projection_collection.py","native_collection_registry.py","oer_native_collection_server.py","native_collection_envelope.py"))
            ((ObjectNode)health.path("retrieval").path("sourceWindows").path("algorithms")).put(name,VettingCorpus.hash("authored "+name));
        for(int ordinal=0;ordinal<3;ordinal++)collectionBatches.put(ordinal,NativeDocumentCollectionFixture.batch(ordinal));
        collectionEnvelopes.put(0,NativeCanonicalFixture.bytes("authored-complete-base-envelope-0.json"));
    }
    @Test void collectionAdoptionStoresOneRootAndSinglePointerReadsEveryWholeBatchAndEnvelope() throws Exception{
        collection();CanonicalSourceBinding binding=register();assertEquals(1,saved.size());assertEquals(NativeDocumentCollectionFixture.rootRaw().getBytes(StandardCharsets.UTF_8).length,binding.getSnapshotJson().getBytes(StandardCharsets.UTF_8).length);
        assertFalse(JsonUtils.parse(binding.getSnapshotJson()).has("chunks"));assertEquals(Arrays.asList(0,1,2),batchReads);assertEquals(Collections.singletonList(0),envelopeReads);batchReads.clear();envelopeReads.clear();
        EvidencePointer pointer=service.pointer("native-test",42L,binding.getProjectionSha256(),"table","advisory");
        assertEquals(Arrays.asList(0,1,2),batchReads);assertEquals(Collections.singletonList(0),envelopeReads);assertEquals(binding.getProjectionSha256(),pointer.getProjectionSha256());assertNotEquals(snapshot.path("publicProjectionSha256").asText(),pointer.getProjectionSha256());
        assertEquals(NativeCanonicalContract.SCHEMA,pointer.getProjectionSchema());assertEquals(NativeCanonicalContract.STORAGE,pointer.getStorageShape());
        assertNull(pointer.getBboxConvention());assertEquals(Collections.emptyList(),pointer.getPhysicalPages());assertEquals(2,pointer.getTableGroup().path("nativeTableTopology").size());
        assertEquals(NativeCanonicalFixture.snapshot().path("unknowns").get(1),pointer.getUnknowns().get(0));assertEquals(1,pointer.getUnknowns().size());
        assertEquals(NativeCanonicalFixture.snapshot().path("chunks"),service.chunks(binding,"advisory"));
        assertEquals(3,service.pointers("native-test",42L,binding.getProjectionSha256(),"advisory").size());
        assertEquals(snapshot,JsonUtils.parse(service.snapshot("native-test",42L,binding.getProjectionSha256(),"advisory")));assertFalse(JsonUtils.parse(binding.getSnapshotJson()).has("chunks"));
    }
    @Test void collectionMissingOrChangedBatchCannotCreateBindingOrReturnAnOldUnit() throws Exception{
        collection();collectionBatches.remove(2);assertThrows(CanonicalEvidenceException.class,this::register);assertTrue(saved.isEmpty());
        collectionBatches.put(2,NativeDocumentCollectionFixture.batch(2));CanonicalSourceBinding binding=register();byte[] changed=collectionBatches.get(1).clone();changed[changed.length-1]^=1;collectionBatches.put(1,changed);
        assertThrows(CanonicalEvidenceException.class,()->service.pointer("native-test",42L,binding.getProjectionSha256(),"table","vetting"));
        assertThrows(CanonicalEvidenceException.class,()->service.chunks(binding,"vetting"));assertEquals(1,saved.size());
    }
    @Test void collectionRegistrationRequiresDedicatedCapabilityAndFrozenAlgorithmSet() throws Exception{
        collection();((ObjectNode)health.path("oer")).remove("nativeDocumentCollection");assertThrows(CanonicalEvidenceException.class,this::register);assertTrue(saved.isEmpty());
        ((ObjectNode)health.path("oer")).set("nativeDocumentCollection",NativeDocumentCollectionFixture.capability());((ObjectNode)health.path("retrieval").path("sourceWindows").path("algorithms")).remove("native_projection_collection.py");
        assertThrows(CanonicalEvidenceException.class,this::register);assertTrue(saved.isEmpty());assertTrue(batchReads.isEmpty());
    }
    @Test void collectionMissingEnvelopeRejectsRegistrationAndEveryPersistedReadWithoutLegacyFallback() throws Exception{
        collection();byte[] envelope=collectionEnvelopes.remove(0);assertThrows(CanonicalEvidenceException.class,this::register);assertTrue(saved.isEmpty());
        collectionEnvelopes.put(0,envelope);CanonicalSourceBinding binding=register();collectionEnvelopes.remove(0);
        assertThrows(CanonicalEvidenceException.class,()->service.snapshot("native-test",42L,binding.getProjectionSha256(),"vetting"));
        assertThrows(CanonicalEvidenceException.class,()->service.currentBindings("native-test","vetting",Collections.singletonList(42L)));
        assertThrows(CanonicalEvidenceException.class,()->service.chunks(binding,"vetting"));
        assertThrows(CanonicalEvidenceException.class,()->service.pointer("native-test",42L,binding.getProjectionSha256(),"table","vetting"));
        assertThrows(CanonicalEvidenceException.class,()->service.pointers("native-test",42L,binding.getProjectionSha256(),"vetting"));assertEquals(1,saved.size());
    }
    @Test void actualCompactProducerSnapshotAndSourceRoundtripWithoutWordPages() {
        CanonicalSourceBinding b=register();assertEquals(90153,b.getSnapshotJson().getBytes(StandardCharsets.UTF_8).length);
        List<EvidencePointer> pointers=service.pointers("native-test",42L,b.getProjectionSha256(),"vetting");assertEquals(3,pointers.size());
        EvidencePointer table=pointers.get(1);assertEquals(NativeCanonicalContract.SCHEMA,table.getProjectionSchema());assertNull(table.getBboxConvention());assertEquals(Collections.emptyList(),table.getPhysicalPages());
        assertEquals(NativeCanonicalContract.VISUAL,table.getVisualReturnStatus());assertEquals(snapshot.path("chunks").get(1).path("tableGroup"),table.getTableGroup());
        assertEquals(2,table.getTableGroup().path("nativeTableTopology").size());assertEquals(3,table.getParts().findValues("text").stream().filter(n->n.isTextual()&&n.asText().isEmpty()).count());
        for(JsonNode part:table.getParts()){assertFalse(part.has("physicalPageNo"));assertFalse(part.has("bbox"));assertEquals(part.path("text").asText().length(),part.path("endOffset").asInt());}
        assertEquals(snapshot,JsonUtils.parse(service.snapshot("native-test",42L,b.getProjectionSha256(),"advisory")));
    }
    @Test void everyPersistedNativeReadRechecksRegistryAndFailsWithoutPrivateProof() {
        CanonicalSourceBinding b=register();int before=snapshotReads;service.currentBindings("native-test","vetting",Collections.singletonList(42L));assertTrue(snapshotReads>before);
        unavailable=true;assertThrows(CanonicalEvidenceException.class,()->service.pointer("native-test",42L,b.getProjectionSha256(),"table","vetting"));
        assertThrows(CanonicalEvidenceException.class,()->service.snapshot("native-test",42L,b.getProjectionSha256(),"vetting"));
    }
    @Test void v1HealthAndIncompleteNativeAlgorithmsCannotAdoptNative() {
        ((ObjectNode)health.path("oer")).remove("nativeCanonical");assertThrows(CanonicalEvidenceException.class,this::register);assertTrue(saved.isEmpty());
        ((ObjectNode)health.path("oer")).set("nativeCanonical",NativeCanonicalFixture.capability());((ObjectNode)health.path("retrieval").path("sourceWindows").path("algorithms")).remove("native_projection_transport.py");
        assertThrows(CanonicalEvidenceException.class,this::register);assertTrue(saved.isEmpty());
    }
    @Test void nativeResealDoesNotAllowPdfAliasesWrongVersionOrUnknownUpgrade() {
        for(Consumer<ObjectNode> mutation:Arrays.<Consumer<ObjectNode>>asList(
                n->n.put("producerKind","unknown"),n->n.put("storageShape","full_bundle"),n->n.put("semanticScopeVerified",true),
                n->((ObjectNode)n.path("projectionIdentity")).put("version","future"),
                n->((ObjectNode)n.path("chunks").get(0).path("parts").get(0)).putNull("physicalPageNo"),
                n->((ObjectNode)n.path("chunks").get(0)).put("pageNo",0),
                n->((ObjectNode)n.path("chunks").get(0).path("parts").get(0).path("textTransform")).put("textChannel","invented"))) {
            ObjectNode changed=snapshot.deepCopy();mutation.accept(changed);changed.remove("projectionSha256");String seal=CanonicalJson.hash(JsonUtils.write(changed),null);changed.put("projectionSha256",seal);
            assertThrows(CanonicalEvidenceException.class,()->CanonicalEvidenceService.validateSnapshot(JsonUtils.write(changed),seal));
        }
    }
    @Test void changedOriginalSourceAndChangedRegistrySnapshotCannotReturnOldNativeEvidence() throws Exception {
        CanonicalSourceBinding b=register();ObjectNode changed=snapshot.deepCopy();changed.put("unknownExtra","changed");changed.remove("projectionSha256");changed.put("projectionSha256",CanonicalJson.hash(JsonUtils.write(changed),null));upstream=JsonUtils.write(changed);
        assertThrows(CanonicalEvidenceException.class,()->service.snapshot("native-test",42L,b.getProjectionSha256(),"vetting"));
        upstream=b.getSnapshotJson();Files.write(Paths.get(document.getStoragePath()),new byte[]{1,2,3});assertThrows(CanonicalEvidenceException.class,()->service.pointer("native-test",42L,b.getProjectionSha256(),"table","vetting"));
    }
}
