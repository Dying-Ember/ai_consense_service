package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JPA reader SQL and fresh transactions; small authored CLOBs, no live DB/service/model. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import(CanonicalCurrentSourceReader.class)
public class CanonicalCatalogMetadataJpaTest {
    static final String PROJECT="authored-metadata-catalog";
    static final String URL="jdbc:h2:mem:catalog_metadata_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",()->URL);
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());
    }
    @Autowired CanonicalCurrentSourceReader reader;
    @Autowired SourceDocumentRepository documents;
    @Autowired ConsenseProperties properties;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @TempDir Path directory;
    private Long currentId;
    private String expectedText,expectedStructure,expectedNative;

    @BeforeEach void prepare() throws Exception {
        properties.setStorageRoot(directory.toString());
        expectedText="AUTHORED current parser text — 😀\r\n";
        expectedStructure="[{\"paragraphStyleName\":\"Heading 1\",\"text\":\"AUTHORED 😀\",\"cells\":[\"\",null]}]";
        expectedNative="{\"authoredNativeEvidence\":true,\"unknown\":null}";
        Path original=directory.resolve("current-original.pdf");Files.write(original,"AUTHORED original bytes 😀".getBytes(StandardCharsets.UTF_8));
        String sourceSha=CanonicalJson.sha(Files.readAllBytes(original));
        committed(()->{
            documents.deleteAll();documents.flush();entityManager.clear();
            SourceDocument current=new SourceDocument();current.setProjectId(PROJECT);current.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
            current.setFileKey("PRE");current.setFileName("AUTHORED-PRE.pdf");current.setReviewRole("tender");current.setParseStatus("PARTIAL");
            current.setStoragePath(original.toString());current.setSourceSha256(sourceSha);current.setTextContent(expectedText);
            current.setStructuredContentJson(expectedStructure);current.setNativePdfEvidenceJson(expectedNative);current.setParseCoverageJson("{\"partial\":true}");
            OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(sourceSha);identity.setKind("PRE");identity.setSourcePurpose("project_document");
            identity.setRole("tender");identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("drafting","vetting","advisory"));
            current.setOerIdentityJson(JsonUtils.write(identity));currentId=documents.saveAndFlush(current).getId();
            SourceDocument unrelated=new SourceDocument();unrelated.setProjectId(PROJECT);unrelated.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
            unrelated.setFileKey("SCC");unrelated.setFileName("UNRELATED-SCC.pdf");unrelated.setParseStatus("FAILED");
            String large=repeat("AUTHORED unrelated CLOB 😀\n",32768);
            unrelated.setTextContent(large);unrelated.setStructuredContentJson(large);unrelated.setNativePdfEvidenceJson(large);
            unrelated.setOerIdentityJson(large);unrelated.setParseCoverageJson(large);documents.saveAndFlush(unrelated);
        });
        SqlCapture.statements.clear();entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();
    }

    @Test void reader_catalog_and_complete_verification_snapshot_do_not_hydrate_retained_archive() throws Exception {
        SourceDocument current=reader.currentDocument(PROJECT,currentId,"advisory");
        assertEquals(expectedText,current.getTextContent());assertEquals(expectedStructure,current.getStructuredContentJson());
        assertNull(current.getNativePdfEvidenceJson());assertEquals("PARTIAL",current.getParseStatus());
        List<String> selects=sourceSelects();
        String catalog=selects.stream().filter(s->s.matches("(?s).*where\\s+[^ ]*project_id\\s*=.*")).findFirst().orElseThrow(()->new AssertionError("No actual project catalog query observed: "+selects));
        String byId=selects.stream().filter(s->s.matches("(?s).*where\\s+[^ ]*\\.id\\s*=.*")).findFirst().orElseThrow(()->new AssertionError("Requested complete entity query missing"));
        assertFalse(byId.contains("native_pdf_evidence_json"),"Verification must not hydrate the unused retained archive");
        assertTrue(byId.contains("text_content")&&byId.contains("structured_content_json")&&byId.contains("oer_identity_json"),"Complete verification inputs must remain");
        System.out.println("CATALOG_SQL_FEEDBACK "+catalog);
        System.out.println("CATALOG_ENTITY_LOAD_COUNT "+entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityLoadCount());
        Set<String> columns=new LinkedHashSet<>();
        String selected=catalog.substring(catalog.indexOf("select ")+7,catalog.indexOf(" from "));
        for(String column:selected.split(",")){String field=column.trim().split("\\s+")[0];columns.add(field.substring(field.lastIndexOf('.')+1));}
        assertEquals(new LinkedHashSet<>(Arrays.asList("id","project_id","category","file_key","file_name")),columns,"Catalog SQL must select exactly metadata, never any source payload or eligibility fields");
        assertEquals(0,entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityLoadCount(),"No source entity/unused archive may be materialized for verification or supersession");
        committed(()->assertEquals(expectedNative,documents.findById(currentId).get().getNativePdfEvidenceJson(),"Retained archive stays stored unchanged"));
    }

    @Test void fresh_catalog_rejects_new_failed_upload_despite_cached_outer_repeatable_read() {
        TransactionTemplate outer=new TransactionTemplate(transactionManager);outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status->{
            SourceDocument cached=documents.findById(currentId).get();documents.findByProjectIdOrderByIdAsc(PROJECT);
            committed(()->{SourceDocument newer=new SourceDocument();newer.setProjectId(PROJECT);newer.setCategory(SourceDocument.CATEGORY_VETTING_SUPPLEMENT);newer.setFileName("  authored-pre.PDF  ");newer.setParseStatus("FAILED");documents.saveAndFlush(newer);});
            assertSame(cached,documents.findById(currentId).get());
            assertEquals("SOURCE_DOCUMENT_SUPERSEDED",assertThrows(CanonicalEvidenceException.class,()->reader.currentDocument(PROJECT,currentId,"advisory")).getMessage());
            return null;
        });
    }

    @ParameterizedTest @MethodSource("supersessionCases")
    void catalog_keeps_module_category_and_failed_pending_supersession(String module,String category,String parseStatus,boolean superseded) {
        committed(()->{SourceDocument newer=new SourceDocument();newer.setProjectId(PROJECT);newer.setCategory(category);newer.setFileName("  authored-pre.PDF  ");newer.setParseStatus(parseStatus);documents.saveAndFlush(newer);});
        if(superseded)assertEquals("SOURCE_DOCUMENT_SUPERSEDED",assertThrows(CanonicalEvidenceException.class,()->reader.currentDocument(PROJECT,currentId,module)).getMessage());
        else assertEquals(currentId,reader.currentDocument(PROJECT,currentId,module).getId());
    }
    static Stream<Arguments> supersessionCases(){
        List<Arguments> cases=new ArrayList<>();
        for(String module:Arrays.asList("drafting","vetting","advisory"))
            for(String category:Arrays.asList(SourceDocument.CATEGORY_VETTING_PACKAGE,SourceDocument.CATEGORY_VETTING_SUPPLEMENT,SourceDocument.CATEGORY_PROJECT_INPUT,SourceDocument.CATEGORY_STANDARD_TEMPLATE))
                for(String status:Arrays.asList("FAILED","PENDING"))cases.add(Arguments.of(module,category,status,"drafting".equals(module)||category.startsWith("VETTING_")));
        return cases.stream();
    }
    @Test void catalog_query_is_project_bound_and_filename_normalization_does_not_change_extensions_or_prefixes(){
        committed(()->{
            SourceDocument otherProject=new SourceDocument();otherProject.setProjectId("other-authored-project");otherProject.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);otherProject.setFileName("AUTHORED-PRE.PDF");documents.saveAndFlush(otherProject);
            for(String name:Arrays.asList("AUTHORED-PRE.docx","dir/AUTHORED-PRE.pdf")){SourceDocument differentName=new SourceDocument();differentName.setProjectId(PROJECT);differentName.setCategory(SourceDocument.CATEGORY_VETTING_SUPPLEMENT);differentName.setFileName(name);documents.saveAndFlush(differentName);}
        });
        assertEquals(currentId,reader.currentDocument(PROJECT,currentId,"advisory").getId());
        assertTrue(documents.findCatalogMetadataByProjectId(PROJECT).stream().allMatch(row->PROJECT.equals(row.getProjectId())));
    }
    @Test void full_and_metadata_catalogs_keep_stable_key_order_and_null_id_semantics(){
        List<SourceDocument> full=new ArrayList<>();
        full.add(row(10L,"X","PRE"));full.add(row(20L,"Y","pre"));full.add(row(30L," x ","PRE"));
        full.add(row(null,"null-id",null));full.add(row(40L,"padded-key"," PRE "));full.add(row(50L,"unknown-key","custom"));
        List<SourceDocument> shells=new ArrayList<>();
        for(SourceDocumentRepository.CatalogMetadata metadata:CatalogMetadataFixtures.from(full)){SourceDocument d=new SourceDocument();d.setId(metadata.getId());d.setProjectId(metadata.getProjectId());d.setCategory(metadata.getCategory());d.setFileKey(metadata.getFileKey());d.setFileName(metadata.getFileName());shells.add(d);}
        List<Long> expected=Arrays.asList(30L,20L,null,40L,50L);
        assertEquals(expected,ids(ProjectDocumentScope.currentCatalog(PROJECT,full)));assertEquals(expected,ids(ProjectDocumentScope.currentCatalog(PROJECT,shells)));
    }
    private SourceDocument row(Long id,String name,String key){SourceDocument d=new SourceDocument();d.setId(id);d.setProjectId(PROJECT);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileName(name);d.setFileKey(key);return d;}
    private List<Long> ids(List<SourceDocument> rows){List<Long> ids=new ArrayList<>();for(SourceDocument row:rows)ids.add(row.getId());return ids;}

    private List<String> sourceSelects(){List<String> result=new ArrayList<>();for(String sql:SqlCapture.statements){String normalized=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();if(normalized.startsWith("select ")&&normalized.contains(" from source_document "))result.add(normalized);}return result;}
    private void committed(Runnable work){TransactionTemplate tx=new TransactionTemplate(transactionManager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.execute(status->{work.run();return null;});}
    private static String repeat(String value,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(value);return out.toString();}
}
