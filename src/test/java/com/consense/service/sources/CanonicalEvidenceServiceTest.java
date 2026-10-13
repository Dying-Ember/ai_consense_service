package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.*;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingCorpus;
import com.consense.web.CanonicalEvidenceController;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real local HTTP + authored Python-produced sealed wire data; no neural/source-quality claim. */
class CanonicalEvidenceServiceTest {
    @TempDir Path directory;
    private HttpServer server;
    private SourceDocument document;
    private SourceDocumentRepository documents;
    private CanonicalSourceBindingRepository bindings;
    private CanonicalEvidenceService service;
    private CanonicalEvidenceProperties config;
    private String raw;
    private JsonNode fixture;
    private final Map<String,byte[]> images=new HashMap<>();
    private final List<CanonicalSourceBinding> saved=new ArrayList<>();
    private List<SourceDocument> catalog;
    private String assetFailure,healthOverride;
    private int remoteCalls;

    @BeforeEach void prepare() throws Exception {
        raw=new String(resource("authored-three-page-projection.json"),StandardCharsets.UTF_8);fixture=JsonUtils.parse(raw);
        document=new SourceDocument();document.setId(11L);document.setProjectId("project-a");document.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        document.setFileKey("PRE");document.setFileName("authored-project-PRE.pdf");document.setReviewRole("tender");document.setParseStatus("PARSED");
        document.setSourceSha256(fixture.path("source").path("sourceSha256").asText());document.setTextContent("AUTHORED current parser text — 😀");document.setStructuredContentJson("[]");
        Path original=directory.resolve("original.pdf");Files.write(original,resource("authored-source.pdf"));document.setStoragePath(original.toString());
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(document.getSourceSha256());identity.setKind("PRE");
        identity.setSourcePurpose("project_document");identity.setRole("tender");identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("drafting","vetting","advisory"));
        document.setOerIdentityJson(JsonUtils.write(identity));catalog=new ArrayList<>(Collections.singletonList(document));
        documents=mock(SourceDocumentRepository.class);bindings=mock(CanonicalSourceBindingRepository.class);
        when(documents.findById(11L)).thenAnswer(invocation->Optional.of(document));
        when(documents.findVerificationMetadataById(11L)).thenAnswer(invocation->Optional.of(CatalogMetadataFixtures.verification(document)));
        when(documents.findCatalogMetadataByProjectId("project-a")).thenAnswer(invocation->CatalogMetadataFixtures.from(catalog));
        when(bindings.findByProjectIdAndDocumentIdAndProjectionSha256(anyString(),anyLong(),anyString())).thenAnswer(invocation->saved.stream()
                .filter(b->b.getProjectId().equals(invocation.getArgument(0))&&b.getDocumentId().equals(invocation.getArgument(1))&&b.getProjectionSha256().equals(invocation.getArgument(2))).findFirst());
        when(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(anyString(),anyLong())).thenAnswer(invocation->{List<CanonicalSourceBinding> rows=new ArrayList<>();
            for(CanonicalSourceBinding b:saved)if(b.getProjectId().equals(invocation.getArgument(0))&&b.getDocumentId().equals(invocation.getArgument(1)))rows.add(b);
            Collections.reverse(rows);return rows;});
        when(bindings.saveAndFlush(any(CanonicalSourceBinding.class))).thenAnswer(invocation->{CanonicalSourceBinding row=invocation.getArgument(0);row.setId((long)saved.size()+1);saved.add(row);return row;});
        for(JsonNode ref:fixture.path("assetRefs"))images.put(ref.path("assetId").asText(),resource("page"+ref.path("physicalPages").get(0).asInt()+".png"));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{remoteCalls++;String path=exchange.getRequestURI().getPath();byte[] response;int status=200;
            if(path.equals("/health")){
                String health=healthOverride!=null?healthOverride:"{\"oer\":{\"inputContract\":\"canonical-source-chunks-upstream-scope-and-parser-projection-v1\",\"assetReturnContract\":\"source-and-projection-bound-content-addressed-assets-v1\",\"canonicalProjectionEndpoint\":\"/project\",\"scopePolicy\":{\"manifestSha256\":\""+repeat('d')+"\"}}}";
                response=health.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            }else if(!path.contains("/assets/")){response=raw.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");}
            else{
                String id=path.substring(path.lastIndexOf("/assets/")+8);JsonNode ref=null;for(JsonNode r:fixture.path("assetRefs"))if(id.equals(r.path("assetId").asText()))ref=r;
                if(ref==null||"missing".equals(assetFailure)){status=404;response=new byte[0];}
                else{
                    response=images.get(id).clone();if("bytes".equals(assetFailure))response[response.length-1]^=1;
                    exchange.getResponseHeaders().set("Content-Type","media".equals(assetFailure)?"image/jpeg":ref.path("mediaType").asText());
                    exchange.getResponseHeaders().set("ETag","etag".equals(assetFailure)?"\"wrong\"":"\""+ref.path("sha256").asText()+"\"");
                    exchange.getResponseHeaders().set("X-Source-Sha256","source".equals(assetFailure)?repeat('a'):document.getSourceSha256());
                    exchange.getResponseHeaders().set("X-Projection-Sha256","projection".equals(assetFailure)?repeat('a'):fixture.path("projectionSha256").asText());
                }
            }
            if("redirect".equals(assetFailure)){status=302;exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/forbidden");}
            exchange.sendResponseHeaders(status,response.length);try(OutputStream out=exchange.getResponseBody()){out.write(response);}
        });server.start();
        config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(directory.toString());
        service=new CanonicalEvidenceService(documents,bindings,new CanonicalEvidenceBackend(config),props);
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}

    @Test void python_seal_full_field_roundtrip_and_three_page_pointer_preserve_unknown() {
        CanonicalSourceBinding row=register("vetting");assertEquals(raw,row.getSnapshotJson());
        assertEquals(raw,service.snapshot("project-a",11L,address(),"advisory"));
        assertEquals(fixture,JsonUtils.parse(row.getSnapshotJson()));
        EvidencePointer pointer=service.pointer("project-a",11L,address(),"table","advisory");
        assertEquals(Arrays.asList(3,4,5),pointer.getPhysicalPages());assertEquals(3,pointer.getAssetRefs().size());
        assertEquals("normalized_xyxy_original_page",pointer.getBboxConvention());
        assertEquals(fixture.path("source").path("sourceFilename"),pointer.getSourceFilename());
        assertEquals(fixture.path("chunks").get(1).path("sourceOrder"),pointer.getSourceOrder());
        assertEquals("unknown",pointer.getSourceQuality().path("semanticQuality").asText());assertFalse(pointer.isHumanConfirmed());
        assertEquals(fixture.path("chunks").get(1).path("parts"),pointer.getParts());
        for(JsonNode ref:fixture.path("assetRefs"))assertArrayEquals(images.get(ref.path("assetId").asText()),service.asset("project-a",11L,address(),ref.path("assetId").asText(),"advisory").bytes);
        assertEquals(1,saved.size());assertSame(row,register("advisory"));
    }
    @Test void unknown_future_json_fields_are_preserved_with_python_number_tokens() {
        ObjectNode edited=(ObjectNode)fixture.deepCopy();edited.putObject("futureExtension").put("文字","😀").putNull("notKnown");raw=reseal(edited);
        CanonicalSourceBinding row=register("vetting");assertEquals(raw,row.getSnapshotJson());assertTrue(service.snapshot("project-a",11L,address(),"vetting").contains("futureExtension"));
    }
    @Test void batch_pointers_validate_one_current_source_and_preserve_all_addresses(){
        register("vetting");clearInvocations(documents,bindings);int before=remoteCalls;
        List<EvidencePointer> pointers=service.pointers("project-a",11L,address(),"advisory");
        assertEquals(fixture.path("chunks").size(),pointers.size());
        assertEquals(fixture.path("chunks").get(1).path("parts"),pointers.get(1).getParts());
        assertEquals(Arrays.asList(3,4,5),pointers.get(1).getPhysicalPages());assertEquals(before,remoteCalls);
        verify(documents,never()).findById(11L);verify(documents,times(1)).findVerificationMetadataById(11L);verify(documents,times(1)).findCatalogMetadataByProjectId("project-a");
        document.setTextContent("new parsed revision");assertReason("CANONICAL_SOURCE_STALE",()->service.pointers("project-a",11L,address(),"advisory"));
    }
    @Test void changed_parser_revision_invalidates_before_asset_http() {
        register("vetting");int before=remoteCalls;document.setTextContent("changed parsed revision");
        assertReason("CANONICAL_SOURCE_STALE",()->service.asset("project-a",11L,address(),assetId(),"vetting"));assertEquals(before,remoteCalls);
    }
    @Test void original_file_byte_change_and_missing_original_fail_without_fallback() throws Exception {
        register("vetting");Files.write(Paths.get(document.getStoragePath()),"different original".getBytes(StandardCharsets.UTF_8));
        assertReason("SOURCE_ORIGINAL_BYTES_CHANGED",()->service.pointer("project-a",11L,address(),"table","vetting"));
        Files.delete(Paths.get(document.getStoragePath()));assertReason("SOURCE_ORIGINAL_UNAVAILABLE",()->service.snapshot("project-a",11L,address(),"vetting"));
    }
    @Test void newer_failed_upload_supersedes_old_snapshot() {
        register("vetting");SourceDocument newer=new SourceDocument();newer.setId(12L);newer.setProjectId("project-a");newer.setCategory(document.getCategory());newer.setFileName("AUTHORED-PROJECT-PRE.PDF");newer.setParseStatus("FAILED");catalog.add(newer);
        assertReason("SOURCE_DOCUMENT_SUPERSEDED",()->service.currentBindings("project-a","vetting",Collections.singletonList(11L)));
    }
    @Test void routing_and_module_changes_invalidate_old_binding() {
        register("vetting");document.setDocumentScope("pricing");assertReason("SOURCE_MODULE_EXCLUDED",()->service.snapshot("project-a",11L,address(),"vetting"));
        document.setDocumentScope(null);document.setReviewRole("standard");assertReason("CANONICAL_SOURCE_STALE",()->service.snapshot("project-a",11L,address(),"vetting"));
    }
    @Test void templates_are_drafting_only_even_if_same_bytes_or_forged_candidate_eligibility() {
        document.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        assertReason("SOURCE_TEMPLATE_DRAFTING_ONLY",()->register("vetting"));assertReason("SOURCE_TEMPLATE_DRAFTING_ONLY",()->register("advisory"));assertEquals(0,remoteCalls);
    }
    @Test void unknown_actual_source_eligibility_does_not_inherit_snapshot_claim() {
        OerDocumentIdentity identity=OerSourceMetadata.identity(document);identity.setEligibleModules(null);document.setOerIdentityJson(JsonUtils.write(identity));
        assertReason("SOURCE_MODULE_ELIGIBILITY_UNRESOLVED_OR_EXCLUDED",()->register("vetting"));assertTrue(saved.isEmpty());
    }
    @Test void project_and_document_id_are_explicit_and_not_same_hash_aliases() {
        assertReason("SOURCE_DOCUMENT_UNAVAILABLE",()->service.registerReviewed("project-b",11L,review("vetting")));
        ObjectNode edited=(ObjectNode)fixture.deepCopy();((ObjectNode)edited.get("projectionIdentity")).put("documentId","99");((ObjectNode)edited.get("manifest")).put("documentId","99");
        for(JsonNode chunk:edited.path("chunks"))((ObjectNode)chunk).put("documentId","99");((ObjectNode)edited.get("projectionIdentity")).put("manifestSha256",CanonicalJson.hash(edited.get("manifest").toString(),null));raw=reseal(edited);
        assertReason("CANONICAL_DOCUMENT_BINDING_MISMATCH",()->register("vetting"));assertTrue(saved.isEmpty());
    }
    @Test void explicit_review_expectations_and_health_capability_are_required() {
        CanonicalEvidenceService.ReviewedRegistration valid=review("vetting");
        CanonicalEvidenceService.ReviewedRegistration incorrect=new CanonicalEvidenceService.ReviewedRegistration(valid.projectionSha256,valid.originalSourceSha256,valid.javaParsedSourceHash,valid.sourceRevisionSha256,valid.scopePolicySha256,repeat('a'),valid.reviewReferenceSha256,valid.module);
        assertReason("CANONICAL_SOURCE_REVIEW_MISMATCH",()->service.registerReviewed("project-a",11L,incorrect));
        healthOverride="{\"oer\":{}}";assertReason("OER_CANONICAL_CAPABILITY_MISMATCH",()->register("vetting"));assertTrue(saved.isEmpty());
    }
    @Test void parser_bundle_or_tampered_seal_is_never_automatically_adopted() {
        raw=raw.replace("consense.canonical-projection.v1","consense.structured-parser-evidence.v1");
        assertReason("CANONICAL_SEAL_MISMATCH",()->register("vetting"));assertTrue(saved.isEmpty());
    }
    @Test void missing_third_page_asset_metadata_rejects_complete_table_group() {
        ObjectNode edited=(ObjectNode)fixture.deepCopy();((ArrayNode)edited.get("assetRefs")).remove(2);((ArrayNode)edited.path("chunks").get(1).get("assetRefs")).remove(2);raw=reseal(edited);
        assertReason("CANONICAL_TABLE_GROUP_ASSETS_INCOMPLETE",()->register("vetting"));assertTrue(saved.isEmpty());
    }
    @Test void missing_third_page_bytes_fail_on_that_reference_without_returning_another_page() {
        register("vetting");String third=fixture.path("assetRefs").get(2).path("assetId").asText();assetFailure="missing";
        assertReason("OER_EVIDENCE_UPSTREAM_UNAVAILABLE",()->service.asset("project-a",11L,address(),third,"vetting"));
    }
    @Test void all_asset_headers_media_and_actual_bytes_must_match() {
        register("vetting");for(String failure:Arrays.asList("source","projection","etag","media","bytes")){
            assetFailure=failure;assertReason("CANONICAL_ASSET_BYTES_MISMATCH",()->service.asset("project-a",11L,address(),assetId(),"vetting"));
        }
    }
    @Test void path_injection_unknown_asset_redirect_and_size_limits_cannot_be_used() {
        register("vetting");int before=remoteCalls;
        assertReason("CANONICAL_ASSET_NOT_BOUND",()->service.asset("project-a",11L,address(),"../../outside","vetting"));assertEquals(before,remoteCalls);
        assetFailure="redirect";assertReason("OER_EVIDENCE_UPSTREAM_UNAVAILABLE",()->service.asset("project-a",11L,address(),assetId(),"vetting"));
        assetFailure=null;config.setMaxAssetBytes(8);assertReason("OER_EVIDENCE_TOO_LARGE",()->service.asset("project-a",11L,address(),assetId(),"vetting"));
        config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/arbitrary/path");assertReason("OER_EVIDENCE_CONFIG_INVALID",()->register("vetting"));
    }
    @Test void selected_corpus_is_complete_and_latest_stale_adoption_never_falls_back() {
        register("vetting");assertEquals(address(),service.currentBindings("project-a","advisory",Collections.singletonList(11L)).get(0).getProjectionSha256());
        assertReason("CANONICAL_SELECTED_DOCUMENT_UNREGISTERED",()->service.currentBindings("project-a","vetting",Arrays.asList(11L,12L)));
        assertReason("CANONICAL_SELECTED_DOCUMENTS_INVALID",()->service.currentBindings("project-a","vetting",Arrays.asList(11L,11L)));
        CanonicalSourceBinding newer=new CanonicalSourceBinding();newer.setProjectId("project-a");newer.setDocumentId(11L);newer.setProjectionSha256(repeat('a'));saved.add(newer);
        assertThrows(CanonicalEvidenceException.class,()->service.currentBindings("project-a","vetting",Collections.singletonList(11L)));
    }
    @Test void stored_json_tampering_and_source_unavailability_fail_closed() {
        CanonicalSourceBinding row=register("vetting");row.setSnapshotJson(raw+" ");assertReason("CANONICAL_STORED_BYTES_CHANGED",()->service.snapshot("project-a",11L,address(),"vetting"));
        row.setSnapshotJson(raw);when(documents.findById(11L)).thenReturn(Optional.empty());when(documents.findVerificationMetadataById(11L)).thenReturn(Optional.empty());assertReason("SOURCE_DOCUMENT_UNAVAILABLE",()->service.snapshot("project-a",11L,address(),"vetting"));
    }
    @Test void controller_returns_actual_verified_asset_pointer_and_http_failure_without_public_registration() throws Exception {
        register("vetting");MockMvc mvc=MockMvcBuilders.standaloneSetup(new CanonicalEvidenceController(service)).build();
        String base="/api/evidence/project-a/documents/11/canonical/"+address();
        mvc.perform(get(base+"/units/table").param("module","advisory")).andExpect(status().isOk()).andExpect(jsonPath("$.data.physicalPages[2]").value(5)).andExpect(jsonPath("$.data.sourceQuality.semanticQuality").value("unknown"));
        mvc.perform(get(base+"/assets/"+assetId()).param("module","advisory")).andExpect(status().isOk()).andExpect(header().string("X-Projection-Sha256",address())).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(content().bytes(images.get(assetId())));
        mvc.perform(post(base).contentType("application/json").content(raw)).andExpect(status().isNotFound());
        document.setTextContent("new parsed revision");mvc.perform(get(base+"/assets/"+assetId()).param("module","vetting")).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("CANONICAL_SOURCE_STALE"));
    }

    private CanonicalSourceBinding register(String module){return service.registerReviewed("project-a",11L,review(module));}
    private CanonicalEvidenceService.ReviewedRegistration review(String module){return new CanonicalEvidenceService.ReviewedRegistration(address(),document.getSourceSha256(),VettingCorpus.sourceHash(document),fixture.path("source").path("sourceRevisionSha256").asText(),repeat('d'),fixture.path("projectionIdentity").path("manifestSha256").asText(),fixture.path("manifest").path("reference").path("evidenceSha256").asText(),module);}
    private String address(){return fixture.path("projectionSha256").asText();}
    private String assetId(){return fixture.path("assetRefs").get(0).path("assetId").asText();}
    private String reseal(ObjectNode edited){edited.put("projectionSha256",CanonicalJson.hash(edited.toString(),"projectionSha256"));fixture=edited;return edited.toString();}
    private static String repeat(char c){char[] chars=new char[64];Arrays.fill(chars,c);return new String(chars);}
    private static void assertReason(String reason,Runnable operation){assertEquals(reason,assertThrows(CanonicalEvidenceException.class,operation::run).getMessage());}
    private static byte[] resource(String name) throws IOException {try(InputStream input=CanonicalEvidenceServiceTest.class.getResourceAsStream("/oer/canonical/"+name)){if(input==null)throw new IOException("Missing fixture "+name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
}
