package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public authored protocol fixtures and real bounded HTTP. No private original, model or quality gold. */
class CanonicalSourcePageContractTest {
    private HttpServer server;
    private CanonicalEvidenceProperties config;
    private CanonicalSourcePageService service;
    private EvidencePointer source;
    private ObjectNode metadata;
    private final Map<String,byte[]> images=new LinkedHashMap<>();
    private final List<String> requests=new ArrayList<>();
    private String identity=repeat('e'),badHeader;
    private int metadataReads;
    private boolean corruptBytes,changeAfterRead;

    @BeforeEach void setup() throws Exception {
        JsonNode fixture=JsonUtils.parse(new String(resource("authored-three-page-projection.json"),StandardCharsets.UTF_8));
        JsonNode chunk=null;for(JsonNode candidate:fixture.path("chunks"))if("table".equals(candidate.path("unitId").asText()))chunk=candidate;
        assertNotNull(chunk);
        source=new EvidencePointer();source.setProjectId("authored-project");source.setDocumentId(11L);source.setModule("advisory");
        source.setOriginalSourceSha256(fixture.path("source").path("sourceSha256").asText());
        source.setSourceRevisionSha256(fixture.path("source").path("sourceRevisionSha256").asText());
        source.setProjectionSha256(fixture.path("projectionSha256").asText());source.setUnitId("table");
        source.setParts(chunk.path("parts"));source.setAssetRefs(chunk.path("assetRefs"));source.setRelations(chunk.path("relations"));
        source.setUnknowns(fixture.path("unknowns"));source.setSourceQuality(chunk.path("sourceQuality"));
        List<Integer> pages=new ArrayList<>();for(JsonNode page:chunk.path("physicalPages"))pages.add(page.asInt());source.setPhysicalPages(pages);
        metadata=JsonUtils.mapper().createObjectNode();metadata.put("schema","consense.source-page-render-evidence.v1");
        metadata.put("projectionSha256",source.getProjectionSha256());metadata.put("sourceSha256",source.getOriginalSourceSha256());
        metadata.put("sourceRevisionSha256",source.getSourceRevisionSha256());metadata.put("combinedEvidenceIdentitySha256",identity);
        metadata.put("unitId",source.getUnitId());metadata.put("qualityAccepted",false);metadata.put("wholeSourceAccepted",false);
        metadata.set("relations",source.getRelations().deepCopy());metadata.set("unknowns",source.getUnknowns().deepCopy());
        metadata.set("captureCompleteness",JsonUtils.parse("{\"status\":\"complete\",\"eligibleForCanonical\":true}"));
        metadata.set("limitations",JsonUtils.parse("[\"authored protocol fixture; semantic quality not scored\"]"));
        ArrayNode refs=metadata.putArray("sourcePageRenderRefs");
        for(JsonNode old:source.getAssetRefs()){
            byte[] bytes=resource(old.path("artifactName").asText());BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
            ObjectNode ref=old.deepCopy();String id="source-render:"+CanonicalJson.sha(bytes);ref.put("assetId",id);ref.put("kind","source_page_render");
            ref.put("sourceRevisionSha256",source.getSourceRevisionSha256());ref.put("projectionSha256",source.getProjectionSha256());
            ref.put("combinedEvidenceIdentitySha256",identity);ref.put("physicalPageNo",old.path("physicalPages").get(0).asInt());
            ref.put("widthPixels",image.getWidth());ref.put("heightPixels",image.getHeight());ref.put("humanConfirmed",false);
            images.put(id,bytes);refs.add(ref);
        }
        config=new CanonicalEvidenceProperties();config.getSourcePageRenderEvidenceIdentities().put(source.getProjectionSha256(),identity);
        CanonicalEvidenceService canonical=mock(CanonicalEvidenceService.class);
        when(canonical.pointer(anyString(),anyLong(),anyString(),anyString(),anyString())).thenAnswer(call->
            JsonUtils.mapper().convertValue(source,EvidencePointer.class));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();requests.add(exchange.getRequestURI().toString());byte[] bytes;
            if(path.endsWith("/source-pages")){
                metadataReads++;ObjectNode body=metadata.deepCopy();
                if(changeAfterRead&&metadataReads>1)((ObjectNode)body.path("sourcePageRenderRefs").get(0)).put("byteCount",999);
                bytes=JsonUtils.write(body).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            }else{
                String id=path.substring(path.lastIndexOf('/')+1);bytes=images.get(id).clone();
                if(corruptBytes)bytes[bytes.length-1]^=1;
                exchange.getResponseHeaders().set("Content-Type","image/png");
                exchange.getResponseHeaders().set("ETag","\""+CanonicalJson.sha(images.get(id))+"\"");
                exchange.getResponseHeaders().set("X-Source-Sha256",source.getOriginalSourceSha256());
                exchange.getResponseHeaders().set("X-Projection-Sha256",source.getProjectionSha256());
                exchange.getResponseHeaders().set("X-Source-Revision-Sha256","revision".equals(badHeader)?repeat('a'):source.getSourceRevisionSha256());
                exchange.getResponseHeaders().set("X-Evidence-Identity-Sha256","identity".equals(badHeader)?repeat('a'):identity);
            }
            exchange.sendResponseHeaders(200,bytes.length);try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}finally{exchange.close();}
        });server.start();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        service=new CanonicalSourcePageService(canonical,new CanonicalEvidenceBackend(config),config);
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    @Test void unpinned_projection_keeps_legacy_pointer_and_makes_no_sidecar_request(){
        config.getSourcePageRenderEvidenceIdentities().clear();EvidencePointer pointer=pointer();
        assertNull(pointer.getSourcePageRenderRefs());assertEquals(source.getAssetRefs(),pointer.getAssetRefs());assertEquals(0,requests.size());
    }
    @Test void complete_source_pages_keep_model_input_assets_relations_unknowns_and_fixed_address(){
        EvidencePointer pointer=pointer();assertEquals(3,pointer.getSourcePageRenderRefs().size());
        assertEquals(source.getAssetRefs(),pointer.getAssetRefs());assertEquals(source.getRelations(),pointer.getRelations());assertEquals(source.getUnknowns(),pointer.getUnknowns());
        assertEquals(identity,pointer.getSourceRenderEvidenceIdentitySha256());assertEquals(1,requests.size());
        assertEquals("/canonical/"+source.getProjectionSha256()+"/sources/"+source.getOriginalSourceSha256()+"/source-pages?module=advisory&evidence="+identity,requests.get(0));
    }
    @Test void missing_page_wrong_membership_and_changed_relations_fail_closed(){
        ObjectNode original=metadata.deepCopy();((ArrayNode)metadata.path("sourcePageRenderRefs")).remove(1);reject("CANONICAL_SOURCE_RENDER_INCOMPLETE_GROUP");
        metadata=original.deepCopy();((ObjectNode)metadata.path("sourcePageRenderRefs").get(0)).putArray("blockIds").add("unadopted");reject("CANONICAL_SOURCE_PAGE_BLOCK_MEMBERSHIP_MISMATCH");
        metadata=original.deepCopy();metadata.putArray("relations");reject("CANONICAL_SOURCE_RENDER_UNKNOWN_OR_RELATION_MISMATCH");
    }
    @Test void verified_png_bytes_are_returned_then_fresh_metadata_is_rechecked(){
        JsonNode ref=metadata.path("sourcePageRenderRefs").get(0);CanonicalEvidenceBackend.Reply reply=asset(ref);
        assertArrayEquals(images.get(ref.path("assetId").asText()),reply.bytes);assertEquals(2,metadataReads);
        assertTrue(requests.get(1).contains("/source-pages/source-render:"));
    }
    @Test void wrong_source_revision_or_composite_image_header_is_rejected(){
        JsonNode ref=metadata.path("sourcePageRenderRefs").get(0);
        for(String header:Arrays.asList("revision","identity")){
            badHeader=header;CanonicalEvidenceException error=assertThrows(CanonicalEvidenceException.class,()->asset(ref));
            assertEquals("CANONICAL_SOURCE_PAGE_BYTES_MISMATCH",error.getMessage());
        }
    }
    @Test void native_docx_cannot_acquire_a_source_page_identity_even_with_empty_sidecar_refs(){
        source.setProjectionSchema("consense.native-canonical-projection.v1");source.setPhysicalPages(Collections.emptyList());
        source.setParts(JsonUtils.mapper().createArrayNode());metadata.putArray("sourcePageRenderRefs");
        reject("CANONICAL_SOURCE_PAGE_CONTRACT_REQUIRED");assertEquals(0,requests.size());
    }
    @Test void non_boolean_acceptance_and_fractional_dimensions_are_not_coerced(){
        ObjectNode original=metadata.deepCopy();metadata.put("qualityAccepted","false");reject("CANONICAL_SOURCE_RENDER_UNKNOWN_OR_RELATION_MISMATCH");
        metadata=original.deepCopy();((ObjectNode)metadata.path("sourcePageRenderRefs").get(0)).put("widthPixels",1.5);
        reject("CANONICAL_SOURCE_PAGE_REFERENCE_INVALID");
    }
    @Test void byte_corruption_and_sidecar_change_during_read_are_rejected(){
        JsonNode ref=metadata.path("sourcePageRenderRefs").get(0);corruptBytes=true;
        assertEquals("CANONICAL_SOURCE_PAGE_BYTES_MISMATCH",assertThrows(CanonicalEvidenceException.class,()->asset(ref)).getMessage());
        corruptBytes=false;metadataReads=0;changeAfterRead=true;
        assertEquals("CANONICAL_SOURCE_RENDER_CHANGED_DURING_READ",assertThrows(CanonicalEvidenceException.class,()->asset(ref)).getMessage());
    }
    private EvidencePointer pointer(){return service.pointer("authored-project",11L,source.getProjectionSha256(),"table","advisory");}
    private CanonicalEvidenceBackend.Reply asset(JsonNode ref){return service.asset("authored-project",11L,source.getProjectionSha256(),"table",ref.path("assetId").asText(),identity,"advisory");}
    private void reject(String reason){assertEquals(reason,assertThrows(CanonicalEvidenceException.class,this::pointer).getMessage());}
    private static byte[] resource(String name)throws IOException{try(InputStream in=CanonicalSourcePageContractTest.class.getResourceAsStream("/oer/canonical/"+name)){
        assertNotNull(in,name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    private static String repeat(char value){char[] chars=new char[64];Arrays.fill(chars,value);return new String(chars);}
}
