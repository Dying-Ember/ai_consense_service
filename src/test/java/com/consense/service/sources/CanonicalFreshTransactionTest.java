package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.*;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring proxies, H2/JPA, repeatable-read outer cache and independent committed writer thread. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({CanonicalEvidenceService.class,CanonicalCurrentSourceReader.class,CanonicalFreshTransactionTest.Config.class})
class CanonicalFreshTransactionTest {
    static final String URL="jdbc:h2:mem:canonical_fresh_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->URL);}
    @TestConfiguration static class Config {
        @Bean CanonicalEvidenceProperties canonicalTestProperties(){return new CanonicalEvidenceProperties();}
        @Bean CanonicalEvidenceBackend canonicalTestBackend(CanonicalEvidenceProperties properties){return new CanonicalEvidenceBackend(properties);}
    }
    @Autowired CanonicalEvidenceService service;
    @Autowired CanonicalCurrentSourceReader reader;
    @Autowired SourceDocumentRepository documents;
    @Autowired CanonicalSourceBindingRepository bindings;
    @Autowired ConsenseProperties properties;
    @Autowired CanonicalEvidenceProperties canonicalProperties;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TransactionAttributeSource transactionAttributes;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    private HttpServer server;
    private ExecutorService writers;
    private ExecutorService httpWorkers;
    private volatile String raw;
    private JsonNode fixture;
    private String projection;
    private Path original;
    private final Map<String,byte[]> images=new HashMap<>();
    private volatile CountDownLatch assetEntered,assetRelease;

    @BeforeEach void prepare() throws Exception {
        writers=Executors.newFixedThreadPool(2);
        raw=new String(resource("authored-three-page-projection.json"),StandardCharsets.UTF_8);fixture=JsonUtils.parse(raw);projection=fixture.path("projectionSha256").asText();
        original=directory.resolve("original.pdf");Files.write(original,resource("authored-source.pdf"));
        assertEquals(fixture.path("source").path("sourceSha256").asText(),CanonicalJson.sha(Files.readAllBytes(original)));
        properties.setStorageRoot(directory.toString());
        committed(()->{
            jdbc.update("DELETE FROM canonical_source_binding");jdbc.update("DELETE FROM source_document");entityManager.clear();
            jdbc.execute("ALTER TABLE source_document ALTER COLUMN id RESTART WITH 11");
            SourceDocument d=new SourceDocument();d.setProjectId("project-a");d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("PRE");
            d.setFileName("authored-project-PRE.pdf");d.setReviewRole("tender");d.setParseStatus("PARSED");d.setStoragePath(original.toString());
            d.setSourceSha256(fixture.path("source").path("sourceSha256").asText());d.setTextContent("AUTHORED current parser text — 😀");d.setStructuredContentJson("[]");
            OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(d.getSourceSha256());identity.setKind("PRE");identity.setSourcePurpose("project_document");
            identity.setRole("tender");identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("drafting","vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(identity));
            assertEquals(11L,documents.saveAndFlush(d).getId().longValue());
        });
        for(JsonNode a:fixture.path("assetRefs"))images.put(a.path("assetId").asText(),resource("page"+a.path("physicalPages").get(0).asInt()+".png"));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);httpWorkers=Executors.newFixedThreadPool(4);server.setExecutor(httpWorkers);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();byte[] response;
            if(path.equals("/health"))response=("{\"oer\":{\"inputContract\":\"canonical-source-chunks-upstream-scope-and-parser-projection-v1\",\"assetReturnContract\":\"source-and-projection-bound-content-addressed-assets-v1\",\"canonicalProjectionEndpoint\":\"/project\",\"scopePolicy\":{\"manifestSha256\":\""+repeat('d')+"\"}}}").getBytes(StandardCharsets.UTF_8);
            else if(!path.contains("/assets/"))response=raw.getBytes(StandardCharsets.UTF_8);
            else {
                String id=path.substring(path.lastIndexOf("/assets/")+8);JsonNode ref=null;for(JsonNode a:fixture.path("assetRefs"))if(id.equals(a.path("assetId").asText()))ref=a;
                if(ref==null){exchange.sendResponseHeaders(404,-1);exchange.close();return;}
                CountDownLatch entered=assetEntered,release=assetRelease;if(entered!=null){entered.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IOException("Writer did not release asset response");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
                response=images.get(id);exchange.getResponseHeaders().set("Content-Type",ref.path("mediaType").asText());exchange.getResponseHeaders().set("ETag","\""+ref.path("sha256").asText()+"\"");
                exchange.getResponseHeaders().set("X-Source-Sha256",fixture.path("source").path("sourceSha256").asText());exchange.getResponseHeaders().set("X-Projection-Sha256",path.split("/")[2]);
            }
            exchange.sendResponseHeaders(200,response.length);try(OutputStream out=exchange.getResponseBody()){out.write(response);}
        });server.start();canonicalProperties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());canonicalProperties.setTimeoutMs(30000);
        register();
    }
    @AfterEach void stop(){if(assetRelease!=null)assetRelease.countDown();if(server!=null)server.stop(0);if(writers!=null)writers.shutdownNow();if(httpWorkers!=null)httpWorkers.shutdownNow();}

    @Test void production_beans_are_proxied_and_public_reads_declare_independent_read_committed() throws Exception {
        assertTrue(AopUtils.isAopProxy(service));assertTrue(AopUtils.isAopProxy(reader));
        for(String name:Arrays.asList("currentBindings","snapshot","pointer","pointers","asset"))for(java.lang.reflect.Method method:CanonicalEvidenceService.class.getMethods())if(method.getName().equals(name)){
            org.springframework.transaction.interceptor.TransactionAttribute a=transactionAttributes.getTransactionAttribute(method,CanonicalEvidenceService.class);
            assertNotNull(a);assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,a.getPropagationBehavior());assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,a.getIsolationLevel());assertTrue(a.isReadOnly());
        }
        assertArrayEquals(images.get(assetId()),service.asset("project-a",11L,projection,assetId(),"vetting").bytes);
    }
    @Test void cached_outer_repeatable_read_cannot_hide_committed_parser_revision_from_any_public_read() {
        outer().execute(status->{
            SourceDocument stale=documents.findById(11L).get();String before=stale.getTextContent();bindings.findByProjectIdAndDocumentIdOrderByIdDesc("project-a",11L);
            commitOnWriter(()->jdbc.update("UPDATE source_document SET text_content=? WHERE id=11","COMMITTED new parser revision"));
            assertSame(stale,documents.findById(11L).get());assertEquals(before,stale.getTextContent());
            assertReason("CANONICAL_SOURCE_STALE",()->service.currentBindings("project-a","vetting",Collections.singletonList(11L)));
            assertReason("CANONICAL_SOURCE_STALE",()->service.snapshot("project-a",11L,projection,"vetting"));
            assertReason("CANONICAL_SOURCE_STALE",()->service.pointer("project-a",11L,projection,"table","vetting"));
            assertReason("CANONICAL_SOURCE_STALE",()->service.pointers("project-a",11L,projection,"vetting"));
            assertReason("CANONICAL_SOURCE_STALE",()->service.asset("project-a",11L,projection,assetId(),"vetting"));return null;
        });
    }
    @Test void current_bindings_see_new_adoption_while_old_explicit_pointer_and_snapshot_are_rejected() {
        outer().execute(status->{
            CanonicalSourceBinding stale=bindings.findByProjectIdAndDocumentIdOrderByIdDesc("project-a",11L).get(0);
            final String[] newer=new String[1];commitOnWriter(()->newer[0]=adoptNextProjection());
            assertEquals(stale.getId(),bindings.findById(stale.getId()).get().getId());
            assertEquals(newer[0],service.currentBindings("project-a","vetting",Collections.singletonList(11L)).get(0).getProjectionSha256());
            assertReason("CANONICAL_ADOPTION_SUPERSEDED",()->service.pointer("project-a",11L,projection,"table","vetting"));
            assertReason("CANONICAL_ADOPTION_SUPERSEDED",()->service.snapshot("project-a",11L,projection,"vetting"));return null;
        });
    }
    @Test void new_failed_same_name_upload_supersedes_cached_outer_catalog() {
        outer().execute(status->{
            documents.findById(11L);assertEquals(1,documents.findByProjectIdOrderByIdAsc("project-a").size());commitOnWriter(this::newerFailedUpload);
            assertReason("SOURCE_DOCUMENT_SUPERSEDED",()->service.currentBindings("project-a","vetting",Collections.singletonList(11L)));
            assertReason("SOURCE_DOCUMENT_SUPERSEDED",()->service.pointers("project-a",11L,projection,"vetting"));return null;
        });
    }
    @Test void committed_routing_change_is_not_hidden_by_outer_entity_manager() {
        outer().execute(status->{documents.findById(11L);commitOnWriter(()->jdbc.update("UPDATE source_document SET review_role='standard' WHERE id=11"));
            assertReason("CANONICAL_SOURCE_STALE",()->service.pointers("project-a",11L,projection,"vetting"));return null;});
    }
    @Test void asset_rechecks_parser_revision_after_remote_response_in_new_persistence_context() throws Exception {
        assetChangesDuringHttp(()->jdbc.update("UPDATE source_document SET text_content='revision during asset HTTP' WHERE id=11"),"CANONICAL_SOURCE_STALE");
    }
    @Test void asset_rechecks_latest_adoption_after_remote_response() throws Exception {
        assetChangesDuringHttp(()->adoptNextProjection(),"CANONICAL_ADOPTION_SUPERSEDED");
    }
    @Test void asset_rechecks_new_failed_upload_after_remote_response() throws Exception {
        assetChangesDuringHttp(this::newerFailedUpload,"SOURCE_DOCUMENT_SUPERSEDED");
    }
    @Test void asset_rehashes_actual_original_bytes_after_remote_response() throws Exception {
        assetChangesDuringHttp(()->{try{Files.write(original,"CHANGED original bytes".getBytes(StandardCharsets.UTF_8));}catch(IOException e){throw new IllegalStateException(e);}},"SOURCE_ORIGINAL_BYTES_CHANGED");
    }

    private void assetChangesDuringHttp(Runnable change,String reason) throws Exception {
        assetEntered=new CountDownLatch(1);assetRelease=new CountDownLatch(1);
        Future<?> call=writers.submit(()->outer().execute(status->{documents.findById(11L);bindings.findByProjectIdAndDocumentIdOrderByIdDesc("project-a",11L);return service.asset("project-a",11L,projection,assetId(),"vetting");}));
        assertTrue(assetEntered.await(10,TimeUnit.SECONDS));commitOnWriter(change);assetRelease.countDown();
        Throwable failure=assertThrows(ExecutionException.class,()->call.get(15,TimeUnit.SECONDS)).getCause();
        assertInstanceOf(CanonicalEvidenceException.class,failure);assertEquals(reason,failure.getMessage());
    }
    private void newerFailedUpload(){SourceDocument newer=new SourceDocument();newer.setProjectId("project-a");newer.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);newer.setFileName("AUTHORED-PROJECT-PRE.PDF");newer.setParseStatus("FAILED");assertTrue(documents.saveAndFlush(newer).getId()>11);}
    private String adoptNextProjection(){ObjectNode edited=(ObjectNode)JsonUtils.parse(raw);edited.put("authoredTransactionTestRevision",UUID.randomUUID().toString());edited.put("projectionSha256",CanonicalJson.hash(edited.toString(),"projectionSha256"));raw=edited.toString();register();return edited.path("projectionSha256").asText();}
    private CanonicalSourceBinding register(){JsonNode s=JsonUtils.parse(raw);SourceDocument d=documents.findById(11L).get();return service.registerReviewed("project-a",11L,new CanonicalEvidenceService.ReviewedRegistration(
        s.path("projectionSha256").asText(),d.getSourceSha256(),VettingCorpus.sourceHash(d),s.path("source").path("sourceRevisionSha256").asText(),repeat('d'),s.path("projectionIdentity").path("manifestSha256").asText(),s.path("manifest").path("reference").path("evidenceSha256").asText(),"vetting"));}
    private TransactionTemplate outer(){TransactionTemplate tx=new TransactionTemplate(transactionManager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx;}
    private void committed(Runnable work){TransactionTemplate tx=new TransactionTemplate(transactionManager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.execute(status->{work.run();return null;});}
    private void commitOnWriter(Runnable work){try{writers.submit(()->committed(work)).get(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("Independent committed writer failed",e);}}
    private String assetId(){return fixture.path("assetRefs").get(0).path("assetId").asText();}
    private static String repeat(char c){char[] chars=new char[64];Arrays.fill(chars,c);return new String(chars);}
    private static void assertReason(String reason,Runnable operation){assertEquals(reason,assertThrows(CanonicalEvidenceException.class,operation::run).getMessage());}
    private static byte[] resource(String name)throws IOException{try(InputStream in=CanonicalFreshTransactionTest.class.getResourceAsStream("/oer/canonical/"+name)){if(in==null)throw new IOException(name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
}
