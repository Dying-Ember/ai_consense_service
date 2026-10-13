package com.consense.service.sources;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.OerBusinessEvidence;
import com.consense.service.vetting.VettingCorpus;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.*;

/** Real original bytes, explicitly authored persisted parse fixtures, isolated MVC/JPA; no fresh parsing or source quality claim. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
    "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory",
    "consense.oer-evidence.base-url=","spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc @ActiveProfiles("h2")
@EnabledIfSystemProperty(named="consense.test.specification-source",matches=".+")
class OerRoutingRefreshJpaTest {
    static final String DB=UUID.randomUUID().toString().replace("-","");
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:routing_refresh_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","routing-refresh-uploads",DB).toAbsolutePath().toString());
        r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());
    }
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @Autowired EntityManagerFactory emf;
    @Autowired ConsenseProperties properties;
    @Autowired CanonicalCurrentSourceReader current;
    @MockBean AiGateway ai;
    @SpyBean DocumentParser parser;
    @SpyBean OerBusinessEvidence oer;
    SourceDocument d;
    byte[] original;

    @BeforeEach void fixture() throws Exception {
        String project="refresh-"+UUID.randomUUID();Project p=new Project();p.setId(project);p.setNameEn("Authored routing refresh fixture");projects.saveAndFlush(p);
        original=Files.readAllBytes(Paths.get(System.getProperty("consense.test.specification-source")));
        assertEquals("3fa27b8e63e55f881260800a56e7a07218c1c1ed927e98292decd54af924b24e",OerSourceMetadata.originalSha256(original));
        Path folder=Paths.get(properties.getStorageRoot(),project);Files.createDirectories(folder);
        Path file=folder.resolve("24 - The Specification.docx");Files.write(file,original);
        d=new SourceDocument();d.setCreatedAt(java.time.Instant.parse("2026-10-10T01:02:03Z"));d.setProjectId(project);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setFileName("24 - The Specification.docx");d.setFileKey("SP");d.setParseStatus("PARSED");
        d.setStoragePath(file.toString());d.setSourceSha256(OerSourceMetadata.originalSha256(original));
        d.setTextContent("AUTHORED persisted parse fixture, not a new source parse.\r\n Literal \uD83D\uDE00\t ");
        d.setStructuredContentJson("[ {\"text\":\"complete original-shaped fixture \uD83D\uDE00\",\"location\":\"paragraph 17\"} ]"+String.join("",Collections.nCopies(4000," ")));
        d.setNativePdfEvidenceJson("{ INTENTIONALLY UNPARSEABLE RETAINED ARCHIVE "+String.join("",Collections.nCopies(32768,"retained-original")));
        d.setReviewRole("project_fact");d.setDocumentScope(null);d.setPageCount(7);d.setOcrUsed(true);
        d.setParseMessage("retained message");d.setParseCoverageJson("{\"complete\":false,\"limitations\":[\"retained unknown\"]}");
        d.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");d.setSizeBytes((long)original.length);
        d.setOerIdentityJson("{\"schemaVersion\":\"oer-source-identity-v1\",\"sourceSha256\":\""+d.getSourceSha256()+"\",\"sourcePurpose\":\"unknown\",\"kind\":\"UNKNOWN\"}");
        d=documents.saveAndFlush(d);clear();
    }
    void clear(){SqlCapture.statements.clear();emf.unwrap(SessionFactory.class).getStatistics().clear();clearInvocations(ai,parser,oer);}
    Map<String,String> request(){Map<String,String> r=new LinkedHashMap<>();r.put("expectedOriginalSha256",d.getSourceSha256());r.put("expectedParsedSourceHash",VettingCorpus.sourceHash(d));r.put("expectedRoutingSha256",CanonicalEvidenceService.routingHash(d));return r;}
    org.springframework.mock.web.MockHttpServletResponse refresh(Map<String,String> request)throws Exception {
        return mvc.perform(post("/api/evidence/"+d.getProjectId()+"/documents/"+d.getId()+"/routing/refresh")
            .contentType("application/json").content(JsonUtils.write(request))).andReturn().getResponse();
    }
    void rejected(int status,String reason)throws Exception {
        String previous=d.getOerIdentityJson();clear();org.springframework.mock.web.MockHttpServletResponse response=refresh(request());
        assertEquals(status,response.getStatus(),response.getContentAsString());assertEquals(reason,JsonUtils.parse(response.getContentAsString()).path("message").asText());
        assertTrue(SqlCapture.statements.stream().noneMatch(s->s.toLowerCase(Locale.ROOT).startsWith("update source_document")));
        assertEquals(previous,documents.findById(d.getId()).get().getOerIdentityJson());verifyNoInteractions(ai,parser,oer);
    }
    @Test void unknown_source_becomes_qualified_without_hydrating_or_rewriting_retained_parse_evidence()throws Exception {
        assertThrows(CanonicalEvidenceException.class,()->current.currentDocument(d.getProjectId(),d.getId(),"vetting"));clear();
        org.springframework.mock.web.MockHttpServletResponse response=refresh(request());assertEquals(200,response.getStatus(),response.getContentAsString());
        assertEquals(0,JsonUtils.parse(response.getContentAsString()).path("code").asInt());
        List<String> observed=new ArrayList<>(SqlCapture.statements);
        for(String sql:observed)assertFalse(sql.toLowerCase(Locale.ROOT).contains("native_pdf_evidence_json"),sql);
        List<String> writes=new ArrayList<>();for(String sql:observed)if(sql.toLowerCase(Locale.ROOT).startsWith("update source_document"))writes.add(sql);
        assertEquals(1,writes.size());String changed=writes.get(0).toLowerCase(Locale.ROOT).split(" where ")[0];
        assertTrue(changed.matches("update source_document set oer_identity_json=\\?"),changed);
        assertEquals(0,emf.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount());
        SourceDocument saved=documents.findById(d.getId()).get();
        assertEquals(d.getNativePdfEvidenceJson(),saved.getNativePdfEvidenceJson());assertEquals(d.getTextContent(),saved.getTextContent());assertEquals(d.getStructuredContentJson(),saved.getStructuredContentJson());
        assertEquals(d.getSourceSha256(),saved.getSourceSha256());assertEquals(d.getFileKey(),saved.getFileKey());assertEquals(d.getCategory(),saved.getCategory());
        assertEquals(d.getReviewRole(),saved.getReviewRole());assertEquals(d.getDocumentScope(),saved.getDocumentScope());assertEquals(d.getParseStatus(),saved.getParseStatus());
        assertEquals(d.getParseMessage(),saved.getParseMessage());assertEquals(d.getParseCoverageJson(),saved.getParseCoverageJson());assertEquals(d.getPageCount(),saved.getPageCount());assertEquals(d.getOcrUsed(),saved.getOcrUsed());
        assertEquals(d.getContentType(),saved.getContentType());assertEquals(d.getSizeBytes(),saved.getSizeBytes());assertEquals(d.getCreatedAt(),saved.getCreatedAt());assertEquals(d.getStoragePath(),saved.getStoragePath());
        assertEquals("sha_verified",OerSourceMetadata.identity(saved).getIdentityStatus());assertEquals("project_fact",VettingCorpus.sourceRole(saved));
        assertEquals(VettingCorpus.sourceHash(d),VettingCorpus.sourceHash(saved));
        assertEquals(d.getId(),current.currentDocument(d.getProjectId(),d.getId(),"vetting").getId());assertEquals(d.getId(),current.currentDocument(d.getProjectId(),d.getId(),"advisory").getId());
        verifyNoInteractions(ai,parser,oer);System.out.println("ROUTING_REFRESH_ACTUAL_SQL "+writes.get(0));
    }
    @Test void second_refresh_is_idempotent_without_any_database_update()throws Exception {
        assertEquals(200,refresh(request()).getStatus());d=documents.findById(d.getId()).get();clear();
        org.springframework.mock.web.MockHttpServletResponse response=refresh(request());assertEquals(200,response.getStatus());
        assertFalse(JsonUtils.parse(response.getContentAsString()).path("data").path("changed").asBoolean());
        assertTrue(SqlCapture.statements.stream().noneMatch(s->s.toLowerCase(Locale.ROOT).startsWith("update source_document")));
    }
    @Test void original_bytes_changed_rejects_without_metadata_write()throws Exception {byte[] changed=original.clone();changed[0]^=1;Files.write(Paths.get(d.getStoragePath()),changed);rejected(409,"SOURCE_ORIGINAL_BYTES_CHANGED");}
    @Test void changed_parsed_content_invalidates_request()throws Exception {Map<String,String> old=request();d.setTextContent(d.getTextContent()+"different");documents.saveAndFlush(d);clear();assertEquals(409,refresh(old).getStatus());}
    @Test void changed_routing_or_explicit_role_invalidates_request()throws Exception {Map<String,String> old=request();d.setReviewRole("tender");documents.saveAndFlush(d);clear();assertEquals(409,refresh(old).getStatus());}
    @Test void newer_failed_same_name_source_still_supersedes_this_successful_parse()throws Exception {SourceDocument newer=new SourceDocument();newer.setProjectId(d.getProjectId());newer.setCategory(d.getCategory());newer.setFileKey("SP");newer.setFileName(" 24 - the specification.DOCX ");newer.setParseStatus("FAILED");documents.saveAndFlush(newer);rejected(409,"SOURCE_DOCUMENT_SUPERSEDED");}
    @Test void explicit_pricing_scope_is_not_overridden_by_exact_registry()throws Exception {d.setDocumentScope("pricing");documents.saveAndFlush(d);rejected(403,"SOURCE_MODULE_EXCLUDED");}
    @Test void known_kind_mismatch_cannot_rename_source_or_change_catalogue()throws Exception {d.setFileKey("PRE");documents.saveAndFlush(d);rejected(409,"SOURCE_ROUTING_KIND_CHANGED");}
    @Test void original_outside_storage_root_is_rejected()throws Exception {d.setStoragePath(System.getProperty("consense.test.specification-source"));documents.saveAndFlush(d);rejected(409,"SOURCE_ORIGINAL_UNAVAILABLE");}
    @Test void standard_template_category_is_never_mutated_by_review_refresh()throws Exception {d.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);documents.saveAndFlush(d);rejected(403,"SOURCE_REFRESH_REVIEW_ONLY");}
    @Test void unregistered_changed_original_remains_unknown_even_with_known_name()throws Exception {byte[] bytes="AUTHORED unregistered source".getBytes(StandardCharsets.UTF_8);Files.write(Paths.get(d.getStoragePath()),bytes);d.setSourceSha256(OerSourceMetadata.originalSha256(bytes));d.setOerIdentityJson(null);documents.saveAndFlush(d);rejected(403,"SOURCE_ROUTING_IDENTITY_UNRESOLVED");}
    @Test void invalid_or_missing_request_hash_is_rejected()throws Exception {Map<String,String> bad=request();bad.remove("expectedRoutingSha256");clear();org.springframework.mock.web.MockHttpServletResponse response=refresh(bad);assertEquals(200,response.getStatus());assertEquals(4001,JsonUtils.parse(response.getContentAsString()).path("code").asInt());assertTrue(SqlCapture.statements.stream().noneMatch(s->s.toLowerCase(Locale.ROOT).startsWith("update source_document")));}
}
