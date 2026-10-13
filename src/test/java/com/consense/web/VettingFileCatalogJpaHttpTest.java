package com.consense.web;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.document.DocumentParser;
import com.consense.document.ParseCoverage;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.OerBusinessEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Actual file-list MVC/JPA seam: archive hydration must fail the SQL check, even on a small fixture. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
    "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory",
    "consense.oer-evidence.base-url=","spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class VettingFileCatalogJpaHttpTest {
    static final String DB=UUID.randomUUID().toString().replace("-","");
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:h2:mem:file_catalog_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","file-catalog-uploads",DB).toAbsolutePath().toString());
        r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());
    }
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @Autowired EntityManagerFactory emf;
    @MockBean AiGateway externalLlm;
    @SpyBean DocumentParser actualParser;
    @SpyBean OerBusinessEvidence actualOer;
    String project;
    @BeforeEach void setup(){
        project="files-"+UUID.randomUUID();Project p=new Project();p.setId(project);p.setNameEn("Authored source file catalogue");projects.saveAndFlush(p);
    }
    SourceDocument source(String name,String key,String text){
        SourceDocument d=new SourceDocument();d.setProjectId(project);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setFileName(name);d.setFileKey(key);d.setParseStatus("PARSED");d.setTextContent(text);
        d.setNativePdfEvidenceJson("{BROKEN "+String.join("",Collections.nCopies(32768,"unused-native-archive")));
        d.setStructuredContentJson("{BROKEN STRUCTURE IS NOT A FILE LIST INPUT}");
        documents.saveAndFlush(d);return d;
    }
    void clear(){SqlCapture.statements.clear();emf.unwrap(SessionFactory.class).getStatistics().clear();org.mockito.Mockito.clearInvocations(externalLlm,actualParser,actualOer);}
    JsonNode response()throws Exception{org.springframework.mock.web.MockHttpServletResponse r=mvc.perform(get("/api/vetting/"+project+"/files")).andReturn().getResponse();assertEquals(200,r.getStatus());return JsonUtils.parse(r.getContentAsString());}
    JsonNode data()throws Exception{JsonNode r=response();assertEquals(0,r.path("code").asInt(),r.toString());return r.path("data");}
    void assertNoArchiveHydration(){
        List<String> sql=new ArrayList<>();for(String raw:SqlCapture.statements){String s=raw.toLowerCase(Locale.ROOT);if(s.startsWith("select ")&&s.contains(" from source_document "))sql.add(s);}
        assertEquals(1,sql.size());assertFalse(sql.get(0).contains("native_pdf_evidence_json"),"file list hydrated native archive: "+sql.get(0));
        assertFalse(sql.get(0).contains("structured_content_json"),"file list hydrated full source structure");
        assertEquals(0,emf.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount());
        org.mockito.Mockito.verifyNoInteractions(externalLlm,actualParser,actualOer);
        System.out.println("FILE_LIST_EXACT_SQL "+sql.get(0));
    }
    @Test void realFileListingDoesNotHydrateUnusedNativeArchiveOrFullStructure()throws Exception{
        source("10 - SCC.docx","SCC","Contractual clause with \uD83D\uDE00 and an exception.");clear();JsonNode d=data();
        assertEquals(1,d.size());assertEquals("Contractual clause with \uD83D\uDE00 and an exception.".length(),d.get(0).path("textChars").asInt());
        assertTrue(d.get(0).path("parsed").asBoolean());assertNoArchiveHydration();
    }
    @Test void latestFailedUploadStillSupersedesOldSuccessfulParseAndPreservesFileOrder()throws Exception{
        source("10 - SCC.docx","SCC","Old source must not become current again.");
        SourceDocument failed=source(" 10 - scc.DOCX ","SCC",null);failed.setParseStatus("FAILED");failed.setParseMessage("Authored failed parse");documents.saveAndFlush(failed);
        source("03 - NTT.docx",null,"Current tender text.");clear();JsonNode d=data();assertEquals(2,d.size());
        assertEquals("NTT",d.get(0).path("key").asText());assertEquals("FAILED",d.get(1).path("parseStatus").asText());
        assertFalse(d.get(1).path("parsed").asBoolean());assertEquals(0,d.get(1).path("textChars").asInt());assertEquals("Authored failed parse",d.get(1).path("basis").asText());assertNoArchiveHydration();
    }
    @Test void fileListKeepsExistingRoutingExclusionsAndExplicitRoles()throws Exception{
        source("10 - SCC.docx","SCC","Current project SCC.");SourceDocument g=source("GCC.pdf","GCC","Current GCC standard.");g.setReviewRole("project_fact");documents.saveAndFlush(g);
        source("Bills of Quantities.pdf","BQ","Excluded pricing.");source("Project PSS - Concrete works.pdf","SP","Excluded technical PSS.");source("Q&A.docx","OTHER","Excluded answer.");
        SourceDocument t=source("NTT template.docx","NTT","Excluded standard template.");t.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);documents.saveAndFlush(t);
        SourceDocument a=source("advisory-only.docx","OTHER","Outside vetting module.");a.setSourceSha256("source-a");a.setOerIdentityJson("{\"sourceSha256\":\"source-a\",\"sourcePurpose\":\"project_document\",\"eligibleModules\":[\"advisory\"]}");documents.saveAndFlush(a);
        clear();JsonNode d=data();assertEquals(2,d.size());assertEquals("SCC",d.get(0).path("key").asText());assertEquals("project_fact",d.get(1).path("sourceRole").asText());assertNoArchiveHydration();
    }
    @Test void parseWarningsOcrAndExactWhitespaceSemanticsRemainVisible()throws Exception{
        SourceDocument d=source("10 - SCC.docx","SCC","\t\r\n  ");d.setParseStatus("PARTIAL");d.setPageCount(9);d.setOcrUsed(true);
        ParseCoverage c=new ParseCoverage();c.setComplete(false);c.setFailedPages(Arrays.asList(2,7));c.setLimitations(Collections.singletonList("Authored limitation"));d.setParseCoverageJson(JsonUtils.write(c));documents.saveAndFlush(d);
        clear();JsonNode row=data().get(0);assertEquals(5,row.path("textChars").asInt());assertFalse(row.path("parsed").asBoolean());assertEquals(9,row.path("pageCount").asInt());assertTrue(row.path("ocrUsed").asBoolean());
        assertTrue(row.path("warnings").toString().contains("Authored limitation"));assertTrue(row.path("warnings").toString().contains("[2, 7]"));assertTrue(row.path("warnings").toString().contains("解析不完整"));assertTrue(row.path("warnings").toString().contains("OCR"));assertNoArchiveHydration();
    }
    @Test void invalidIdentityStillFailsClosedInsteadOfListingAsEligible()throws Exception{
        SourceDocument d=source("10 - SCC.docx","SCC","Current text.");d.setSourceSha256("actual-source");d.setOerIdentityJson("{\"sourceSha256\":\"different-source\",\"eligibleModules\":[\"vetting\"]}");documents.saveAndFlush(d);
        clear();JsonNode r=response();assertNotEquals(0,r.path("code").asInt());assertTrue(r.path("message").asText().contains("identity"));assertNoArchiveHydration();
    }
    @Test void foreignProjectAndAbsentProjectCannotReadThisSourceCatalogue()throws Exception{
        source("10 - SCC.docx","SCC","Project isolated source.");String original=project;project="files-"+UUID.randomUUID();Project p=new Project();p.setId(project);projects.saveAndFlush(p);clear();assertEquals(0,data().size());assertNoArchiveHydration();
        project="absent-"+original;clear();assertEquals(4004,response().path("code").asInt());assertTrue(SqlCapture.statements.stream().noneMatch(s->s.contains(" from source_document ")));
    }
}
