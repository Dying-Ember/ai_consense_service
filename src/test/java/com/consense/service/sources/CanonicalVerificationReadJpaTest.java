package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.*;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.*;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SQL/JPA/fresh reader and authored local HTTP registration; no live DB/model/source-quality claim. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({CanonicalEvidenceService.class,CanonicalCurrentSourceReader.class,CanonicalVerificationReadJpaTest.Config.class})
public class CanonicalVerificationReadJpaTest {
    static final String PROJECT="project-a",URL="jdbc:h2:mem:verify_read_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    static final Set<String> VERIFY_COLUMNS=new LinkedHashSet<>(Arrays.asList("id","project_id","category","file_key","file_name","review_role","document_scope","parse_status","oer_identity_json","source_sha256","storage_path","text_content","structured_content_json"));
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @TestConfiguration static class Config {
        @Bean CanonicalEvidenceProperties canonicalTestProperties(){return new CanonicalEvidenceProperties();}
        @Bean CanonicalEvidenceBackend canonicalTestBackend(CanonicalEvidenceProperties p){return new CanonicalEvidenceBackend(p);}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->URL);r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());}
    @Autowired CanonicalCurrentSourceReader reader;
    @Autowired CanonicalEvidenceService service;
    @Autowired SourceDocumentRepository documents;
    @Autowired CanonicalSourceBindingRepository bindings;
    @Autowired ConsenseProperties properties;
    @Autowired CanonicalEvidenceProperties canonicalProperties;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    private HttpServer server;
    private CanonicalSourceBinding binding;
    private SourceDocument originalDocument;
    private String nativeJson,raw;
    private JsonNode fixture;

    @BeforeEach void prepare() throws Exception {
        raw=new String(resource("authored-three-page-projection.json"),StandardCharsets.UTF_8);fixture=JsonUtils.parse(raw);
        Path original=directory.resolve("original.pdf");Files.write(original,resource("authored-source.pdf"));properties.setStorageRoot(directory.toString());
        nativeJson="{\"authoredUnusedNativePayload\":\""+repeatText("NATIVE 😀",65536)+"\"}";
        committed(()->{
            jdbc.update("DELETE FROM canonical_source_binding");jdbc.update("DELETE FROM source_document");entityManager.clear();jdbc.execute("ALTER TABLE source_document ALTER COLUMN id RESTART WITH 11");
            SourceDocument d=new SourceDocument();d.setProjectId(PROJECT);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("PRE");d.setFileName("authored-project-PRE.pdf");
            d.setReviewRole("tender");d.setDocumentScope("package");d.setParseStatus("PARTIAL");d.setStoragePath(original.toString());d.setContentType("application/pdf");d.setSizeBytes(999L);
            d.setSourceSha256(CanonicalJson.sha(read(original)));d.setTextContent("AUTHORED current text 😀\r\n");
            d.setStructuredContentJson("[{\"paragraphStyleName\":\"Heading 1\",\"text\":\"AUTHORED 😀\",\"unknown\":null}]");d.setNativePdfEvidenceJson(nativeJson);d.setParseCoverageJson("{\"partial\":true}");
            OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(d.getSourceSha256());identity.setKind("PRE");identity.setSourcePurpose("project_document");identity.setRole("tender");
            identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("drafting","vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(identity));originalDocument=documents.saveAndFlush(d);
            binding=new CanonicalSourceBinding();binding.setProjectId(PROJECT);binding.setDocumentId(11L);binding.setOriginalSourceSha256(d.getSourceSha256());binding.setJavaParsedSourceHash(VettingCorpus.sourceHash(d));binding.setRoutingSha256(CanonicalEvidenceService.routingHash(d));
            binding.setProjectionSha256(repeat('a'));binding.setSourceRevisionSha256(repeat('a'));binding.setProjectionRevisionSha256(repeat('a'));binding.setCanonicalSourceHash(repeat('a'));binding.setScopePolicySha256(repeat('a'));
            binding.setAdoptionManifestSha256(repeat('a'));binding.setReviewReferenceSha256(repeat('a'));binding.setSnapshotJson("{\"authored\":true}");binding.setSnapshotBytesSha256(CanonicalJson.sha(binding.getSnapshotJson().getBytes(StandardCharsets.UTF_8)));
            binding.setDeclaredModule("advisory");binding.setEligibleModulesJson("[\"drafting\",\"vetting\",\"advisory\"]");bindings.saveAndFlush(binding);
        });
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();String result=path.equals("/health")?"{\"oer\":{\"inputContract\":\"canonical-source-chunks-upstream-scope-and-parser-projection-v1\",\"assetReturnContract\":\"source-and-projection-bound-content-addressed-assets-v1\",\"canonicalProjectionEndpoint\":\"/project\",\"scopePolicy\":{\"manifestSha256\":\""+repeat('d')+"\"}}}":raw;
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}});
        server.start();canonicalProperties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());canonicalProperties.setTimeoutMs(30000);clearFeedback();
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}

    @Test void assert_source_current_sql_reads_exact_verification_fields_without_native_payload_or_source_entity(){
        reader.assertSourceCurrent(binding,"advisory");assertVerificationSql("assertSourceCurrent");
    }
    @Test void assert_current_sql_keeps_adoption_check_without_loading_native_payload(){
        reader.assertCurrent(binding,"advisory");assertVerificationSql("assertCurrent");assertTrue(SqlCapture.statements.stream().anyMatch(sql->sql.contains("canonical_source_binding")));
    }
    @Test void current_document_reads_complete_verification_fields_and_preserves_retained_archive(){
        SourceDocument d=reader.currentDocument(PROJECT,11L,"advisory");assertNull(d.getNativePdfEvidenceJson());assertNull(d.getParseCoverageJson());
        assertEquals(originalDocument.getStructuredContentJson(),d.getStructuredContentJson());assertEquals(originalDocument.getTextContent(),d.getTextContent());
        assertEquals(originalDocument.getSourceSha256(),d.getSourceSha256());assertEquals(originalDocument.getStoragePath(),d.getStoragePath());assertVerificationSql("currentDocument");
        committed(()->{SourceDocument retained=documents.findById(11L).orElseThrow();assertEquals(nativeJson,retained.getNativePdfEvidenceJson());assertEquals(originalDocument.getParseCoverageJson(),retained.getParseCoverageJson());assertEquals("application/pdf",retained.getContentType());assertEquals(999L,retained.getSizeBytes());});
    }
    @Test void actual_registration_keeps_full_initial_source_read_and_verification_only_after_remote_response(){
        CanonicalSourceBinding registered=service.registerReviewed(PROJECT,11L,new CanonicalEvidenceService.ReviewedRegistration(fixture.path("projectionSha256").asText(),originalDocument.getSourceSha256(),VettingCorpus.sourceHash(originalDocument),fixture.path("source").path("sourceRevisionSha256").asText(),repeat('d'),fixture.path("projectionIdentity").path("manifestSha256").asText(),fixture.path("manifest").path("reference").path("evidenceSha256").asText(),"vetting"));
        assertEquals(raw,registered.getSnapshotJson());List<String> queries=byIdSelects();assertEquals(2,queries.size());
        System.out.println("REGISTER_FULL_SQL_FEEDBACK "+queries.get(0));System.out.println("REGISTER_POST_HTTP_SQL_FEEDBACK "+queries.get(1));
        assertEquals(VERIFY_COLUMNS,columns(queries.get(0)));assertEquals(VERIFY_COLUMNS,columns(queries.get(1)));assertEquals(0,sourceLoads());
    }
    @ParameterizedTest @CsvSource({"text_content,CANONICAL_SOURCE_STALE","structured_content_json,CANONICAL_SOURCE_STALE","parse_status,CANONICAL_SOURCE_STALE","review_role,CANONICAL_SOURCE_STALE","file_key,CANONICAL_SOURCE_STALE","document_scope,SOURCE_MODULE_EXCLUDED","category,SOURCE_TEMPLATE_DRAFTING_ONLY","file_name,SOURCE_MODULE_EXCLUDED","project_id,SOURCE_DOCUMENT_UNAVAILABLE","storage_path,SOURCE_ORIGINAL_UNAVAILABLE"})
    void required_field_mutations_still_reject(String column,String reason){
        Map<String,String> changes=new HashMap<>();changes.put("text_content","AUTHORED changed 😀");changes.put("structured_content_json","[{\"text\":\"AUTHORED changed\"}]");changes.put("parse_status","FAILED");changes.put("review_role","standard");changes.put("file_key","GCC");changes.put("document_scope","pricing");changes.put("category",SourceDocument.CATEGORY_STANDARD_TEMPLATE);changes.put("file_name","authored answer sheet.pdf");changes.put("project_id","other-authored-project");changes.put("storage_path",directory.resolve("missing.pdf").toString());
        committed(()->jdbc.update("UPDATE source_document SET "+column+"=? WHERE id=11",changes.get(column)));clearFeedback();
        assertEquals(reason,assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void paragraph_style_name_normalization_remains_the_same_and_other_structure_bytes_still_matter(){
        committed(()->jdbc.update("UPDATE source_document SET structured_content_json=? WHERE id=11",originalDocument.getStructuredContentJson().replace("Heading 1","Heading 2")));assertDoesNotThrow(()->reader.assertSourceCurrent(binding,"advisory"));
        committed(()->jdbc.update("UPDATE source_document SET structured_content_json=? WHERE id=11",originalDocument.getStructuredContentJson().replace("AUTHORED 😀","AUTHORED changed 😀")));
        assertEquals("CANONICAL_SOURCE_STALE",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void original_bytes_identity_and_module_eligibility_remain_independent_gates() throws Exception {
        byte[] bytes=Files.readAllBytes(Paths.get(originalDocument.getStoragePath()));bytes[bytes.length/2]^=1;Files.write(Paths.get(originalDocument.getStoragePath()),bytes);
        assertEquals("SOURCE_ORIGINAL_BYTES_CHANGED",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void changed_identity_or_declared_binding_eligibility_does_not_inherit_the_projection(){
        OerDocumentIdentity identity=JsonUtils.read(originalDocument.getOerIdentityJson(),OerDocumentIdentity.class);identity.setEligibleModules(Collections.singletonList("drafting"));committed(()->jdbc.update("UPDATE source_document SET oer_identity_json=? WHERE id=11",JsonUtils.write(identity)));
        assertEquals("SOURCE_MODULE_ELIGIBILITY_UNRESOLVED_OR_EXCLUDED",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void source_sha_mismatch_is_still_rejected_by_identity_before_original_hash(){
        committed(()->jdbc.update("UPDATE source_document SET source_sha256=? WHERE id=11",repeat('b')));
        assertEquals("SOURCE_ROUTING_STALE",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void null_source_id_keeps_the_repository_nonnull_contract(){
        binding.setDocumentId(null);assertThrows(IllegalArgumentException.class,()->reader.assertSourceCurrent(binding,"advisory"));
    }
    @Test void real_deleted_source_is_unavailable_for_both_verification_and_full_document_reads(){
        committed(()->jdbc.update("DELETE FROM source_document WHERE id=11"));clearFeedback();
        assertEquals("SOURCE_DOCUMENT_UNAVAILABLE",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
        assertEquals("SOURCE_DOCUMENT_UNAVAILABLE",assertThrows(CanonicalEvidenceException.class,()->reader.currentDocument(PROJECT,11L,"advisory")).getMessage());
    }
    private void assertVerificationSql(String label){List<String> selects=byIdSelects();assertEquals(1,selects.size());String sql=selects.get(0);System.out.println("VERIFY_SQL_FEEDBACK "+label+" "+sql);System.out.println("VERIFY_SOURCE_ENTITY_LOAD_COUNT "+sourceLoads());assertEquals(VERIFY_COLUMNS,columns(sql));assertEquals(0,sourceLoads());}
    private List<String> byIdSelects(){List<String> found=new ArrayList<>();for(String sql:SqlCapture.statements){String s=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();if(s.startsWith("select ")&&s.contains(" from source_document ")&&s.matches("(?s).*where\\s+[^ ]*\\.id\\s*=.*"))found.add(s);}return found;}
    private Set<String> columns(String sql){Set<String> result=new LinkedHashSet<>();String selected=sql.substring(sql.indexOf("select ")+7,sql.indexOf(" from "));for(String col:selected.split(",")){String field=col.trim().split("\\s+")[0];result.add(field.substring(field.lastIndexOf('.')+1));}return result;}
    private long sourceLoads(){return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount();}
    private void clearFeedback(){SqlCapture.statements.clear();entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();}
    private void committed(Runnable work){TransactionTemplate tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.execute(status->{work.run();return null;});}
    private static byte[] read(Path path){try{return Files.readAllBytes(path);}catch(IOException e){throw new IllegalStateException(e);}}
    private static byte[] resource(String name)throws IOException{try(InputStream in=CanonicalVerificationReadJpaTest.class.getResourceAsStream("/oer/canonical/"+name)){if(in==null)throw new IOException(name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];for(int n;(n=in.read(buf))!=-1;)out.write(buf,0,n);return out.toByteArray();}}
    private static String repeat(char c){char[] chars=new char[64];Arrays.fill(chars,c);return new String(chars);}
    private static String repeatText(String value,int count){StringBuilder out=new StringBuilder();for(int n=0;n<count;n++)out.append(value);return out.toString();}
}
