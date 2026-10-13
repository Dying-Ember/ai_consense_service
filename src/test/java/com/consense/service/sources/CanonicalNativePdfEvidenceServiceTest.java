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

/** Actual producer bytes/original PDF, bounded local HTTP registry double; no real Python HTTP or neural acceptance. */
class CanonicalNativePdfEvidenceServiceTest {
    @TempDir Path directory;
    HttpServer server;CanonicalEvidenceService service;SourceDocument document;ObjectNode snapshot,health;
    List<CanonicalSourceBinding> saved=new ArrayList<>();String upstream;boolean unavailable;int snapshotReads;
    @BeforeEach void setup() throws Exception {
        snapshot=NativePdfCanonicalFixture.snapshot();upstream=NativePdfCanonicalFixture.raw();
        document=new SourceDocument();document.setId(42L);document.setProjectId("pdf-test");document.setFileKey("GCC");document.setFileName("authored-native-pdf.pdf");
        document.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);document.setReviewRole("tender");document.setParseStatus("PARSED");
        document.setSourceSha256(snapshot.path("source").path("sourceSha256").asText());document.setTextContent("authored native PDF observation");document.setStructuredContentJson("[]");
        Path original=directory.resolve("authored-native-pdf.pdf");Files.write(original,NativePdfCanonicalFixture.bytes("authored-business-source.pdf"));document.setStoragePath(original.toString());
        OerDocumentIdentity route=new OerDocumentIdentity();route.setSourceSha256(document.getSourceSha256());route.setKind("GCC");route.setRole("tender");route.setSourcePurpose("project_document");
        route.setIdentityStatus("content_supported");route.setEligibleModules(Arrays.asList("vetting","advisory"));document.setOerIdentityJson(JsonUtils.write(route));
        SourceDocumentRepository documents=mock(SourceDocumentRepository.class);CanonicalSourceBindingRepository bindings=mock(CanonicalSourceBindingRepository.class);
        when(documents.findById(42L)).thenReturn(Optional.of(document));when(documents.findCatalogMetadataByProjectId("pdf-test")).thenAnswer(call->CatalogMetadataFixtures.from(Collections.singletonList(document)));
        when(documents.findVerificationMetadataById(42L)).thenAnswer(call->Optional.of(CatalogMetadataFixtures.verification(document)));
        when(bindings.findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString())).thenAnswer(call->saved.stream().filter(b->b.getProjectId().equals(call.getArgument(0))&&b.getDocumentId().equals(call.getArgument(1))&&b.getProjectionSha256().equals(call.getArgument(2))).findFirst());
        when(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(anyString(),anyLong())).thenAnswer(call->new ArrayList<>(saved));
        when(bindings.saveAndFlush(any())).thenAnswer(call->{CanonicalSourceBinding b=call.getArgument(0);b.setId((long)saved.size()+1);saved.add(b);return b;});
        health=JsonUtils.mapper().createObjectNode();ObjectNode oer=health.putObject("oer");oer.put("inputContract","canonical-source-chunks-upstream-scope-and-parser-projection-v1");
        oer.put("assetReturnContract","source-and-projection-bound-content-addressed-assets-v1");oer.put("canonicalProjectionEndpoint","/project");
        oer.putObject("scopePolicy").put("manifestSha256",snapshot.path("manifest").path("scope").path("scopePolicySha256").asText());oer.set("nativePdfCanonical",NativePdfCanonicalFixture.capability());
        health.putObject("retrieval").putObject("sourceWindows").set("algorithms",NativePdfCanonicalFixture.algorithms(snapshot));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
            boolean isHealth="/health".equals(exchange.getRequestURI().getPath());if(!isHealth)snapshotReads++;
            byte[] bytes=(isHealth?JsonUtils.write(health):upstream).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(!isHealth&&unavailable?404:200,bytes.length);
            try(OutputStream stream=exchange.getResponseBody()){stream.write(bytes);}
        });server.start();
        CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(directory.toString());service=new CanonicalEvidenceService(documents,bindings,new CanonicalEvidenceBackend(config),props);
    }
    @AfterEach void close(){if(server!=null)server.stop(0);}
    CanonicalEvidenceService.ReviewedRegistration review(String adoption){return new CanonicalEvidenceService.ReviewedRegistration(snapshot.path("projectionSha256").asText(),document.getSourceSha256(),VettingCorpus.sourceHash(document),
            snapshot.path("source").path("sourceRevisionSha256").asText(),snapshot.path("manifest").path("scope").path("scopePolicySha256").asText(),adoption,
            snapshot.path("manifest").path("reference").path("evidenceSha256").asText(),"vetting");}
    CanonicalSourceBinding register(){return service.registerReviewed("pdf-test",42L,review(snapshot.path("projectionIdentity").path("adoptionManifestSha256").asText()));}

    @Test void actualPublicRawSealAndBusinessIdAreAccepted() throws Exception {
        assertEquals(43078,upstream.getBytes(StandardCharsets.UTF_8).length);assertEquals(NativePdfCanonicalFixture.PUBLIC_SHA,CanonicalJson.sha(upstream.getBytes(StandardCharsets.UTF_8)));
        assertEquals(snapshot,CanonicalEvidenceService.validateSnapshot(upstream,snapshot.path("projectionSha256").asText()));
        CanonicalSourceBinding binding=register();assertEquals(upstream,binding.getSnapshotJson());assertEquals(1,saved.size());assertEquals(snapshot.path("projectionIdentity").path("adoptionManifestSha256").asText(),binding.getAdoptionManifestSha256());
        assertFalse(health.path("oer").has("nativeCanonical"));assertEquals("42",snapshot.path("projectionIdentity").path("documentId").asText());
    }

    @Test void rawNumberTokensDuplicatesAndHardSizeCapCannotBeNormalizedAway() {
        String address=snapshot.path("projectionSha256").asText();assertTrue(upstream.contains("200.0"));
        assertThrows(CanonicalEvidenceException.class,()->CanonicalEvidenceService.validateSnapshot(upstream.replaceFirst("200\\.0","200"),address));
        assertThrows(CanonicalEvidenceException.class,()->CanonicalEvidenceService.validateSnapshot(upstream.replaceFirst("\\{","{\"schema\":\"other\","),address));
        String tooLarge=String.join("",Collections.nCopies(NativePdfCanonicalContract.MAX_BYTES+1," "));
        CanonicalEvidenceException error=assertThrows(CanonicalEvidenceException.class,()->CanonicalEvidenceService.validateSnapshot(tooLarge,address));assertEquals("CANONICAL_SNAPSHOT_TOO_LARGE",error.getMessage());
    }

    @Test void pointerKeepsActualPdfPagesRefsAndUnknownDeclarationsThroughDto() {
        CanonicalSourceBinding binding=register();EvidencePointer pointer=service.pointer("pdf-test",42L,binding.getProjectionSha256(),"A1","advisory");
        assertEquals(NativePdfCanonicalContract.SCHEMA,pointer.getProjectionSchema());assertEquals(NativePdfCanonicalContract.PRODUCER,pointer.getProducerKind());
        assertEquals(NativePdfCanonicalContract.BBOX,pointer.getBboxConvention());assertEquals(Arrays.asList(1,2),pointer.getPhysicalPages());assertEquals("authored-native-pdf.pdf",pointer.getSourceFilename().asText());
        assertEquals(snapshot.path("chunks").get(0).path("parts"),pointer.getParts());assertEquals(snapshot.path("chunks").get(0).path("sourceSpans"),pointer.getSourceSpans());
        assertEquals(NativePdfCanonicalContract.displayDeclarations(snapshot),pointer.getNativePdfEvidence());assertEquals(snapshot.path("captureCompleteness").path("reference").path("unknowns"),pointer.getUnknowns());
        assertNull(pointer.getTableGroup());assertTrue(pointer.getAssetRefs().isEmpty());assertFalse(pointer.isHumanConfirmed());
        EvidencePointer roundTrip=JsonUtils.read(JsonUtils.write(pointer),EvidencePointer.class);assertEquals(pointer,roundTrip);
        assertEquals(snapshot.path("chunks"),service.chunks(binding,"advisory"));assertEquals(upstream,service.snapshot("pdf-test",42L,binding.getProjectionSha256(),"advisory"));
    }

    @Test void registrationRequiresActualCasAdoptionIdentityNotPublicManifestRewriteHash() {
        String wrong=CanonicalJson.fieldHash(upstream,"manifest");assertNotEquals(snapshot.path("projectionIdentity").path("adoptionManifestSha256").asText(),wrong);
        assertThrows(CanonicalEvidenceException.class,()->service.registerReviewed("pdf-test",42L,review(wrong)));assertTrue(saved.isEmpty());
    }

    @Test void pdfRegistrationRequiresDedicatedCapabilityAndBoundAlgorithmHashes() {
        List<Consumer<ObjectNode>> mutations=Arrays.asList(h->((ObjectNode)h.path("oer")).remove("nativePdfCanonical"),
                h->((ObjectNode)h.path("oer").path("nativePdfCanonical")).put("maximumMessageBytes",NativePdfCanonicalContract.MAX_BYTES+1),
                h->((ObjectNode)h.path("oer").path("nativePdfCanonical")).put("tableAndAppearanceText","supported"),
                h->((ObjectNode)h.path("retrieval").path("sourceWindows").path("algorithms")).remove("native_pdf_source_registry.py"),
                h->((ObjectNode)h.path("retrieval").path("sourceWindows").path("algorithms")).put("native_pdf_canonical_projection.py",VettingCorpus.hash("changed")));
        ObjectNode original=health.deepCopy();for(Consumer<ObjectNode> mutation:mutations){health.removeAll();health.setAll(original.deepCopy());mutation.accept(health);assertThrows(CanonicalEvidenceException.class,this::register);assertTrue(saved.isEmpty());}
    }

    @Test void everyPersistedReadAndDirectChunksReadRechecksRegistry() {
        CanonicalSourceBinding binding=register();int prior=snapshotReads;service.currentBindings("pdf-test","advisory",Collections.singletonList(42L));assertTrue(snapshotReads>prior);
        prior=snapshotReads;service.chunks(binding,"advisory");assertTrue(snapshotReads>prior);unavailable=true;
        assertThrows(CanonicalEvidenceException.class,()->service.chunks(binding,"advisory"));
        assertThrows(CanonicalEvidenceException.class,()->service.pointer("pdf-test",42L,binding.getProjectionSha256(),"A1","advisory"));
        assertThrows(CanonicalEvidenceException.class,()->service.snapshot("pdf-test",42L,binding.getProjectionSha256(),"advisory"));
    }

    @Test void changedOriginalAndChangedRegistryCannotReturnPersistedPdf() throws Exception {
        CanonicalSourceBinding binding=register();upstream=upstream.replace("Visible fi cafe","Visible fi CAFe");
        assertThrows(CanonicalEvidenceException.class,()->service.pointers("pdf-test",42L,binding.getProjectionSha256(),"vetting"));
        upstream=binding.getSnapshotJson();Files.write(Paths.get(document.getStoragePath()),new byte[]{1,2,3});
        assertThrows(CanonicalEvidenceException.class,()->service.chunks(binding,"vetting"));assertEquals(1,saved.size());
    }

    @Test void partialPdfMarkersNeverFallBackToScannedOrDocx() {
        ObjectNode changed=snapshot.deepCopy();changed.put("schema","consense.canonical-projection.v1");changed.remove("producerKind");changed.remove("storageShape");
        changed.remove("projectionSha256");String seal=CanonicalJson.hash(JsonUtils.write(changed),null);changed.put("projectionSha256",seal);
        CanonicalEvidenceException error=assertThrows(CanonicalEvidenceException.class,()->CanonicalEvidenceService.validateSnapshot(JsonUtils.write(changed),seal));assertTrue(error.getMessage().startsWith("CANONICAL_NATIVE_PDF_"));
    }

    @Test void storedBytesCannotChangeEvenWhenPublicDeclarationStillLooksValid() {
        CanonicalSourceBinding binding=register();binding.setSnapshotJson(binding.getSnapshotJson()+" ");
        assertThrows(CanonicalEvidenceException.class,()->service.chunks(binding,"vetting"));assertEquals(1,saved.size());
    }

    @Test void privateRasterDescriptorsDoNotEnableAnUnpublishedAssetRoute() {
        CanonicalSourceBinding binding=register();CanonicalEvidenceException error=assertThrows(CanonicalEvidenceException.class,()->service.asset("pdf-test",42L,binding.getProjectionSha256(),"asset:unpublished","advisory"));
        assertEquals("CANONICAL_ASSET_NOT_BOUND",error.getMessage());
    }
}
