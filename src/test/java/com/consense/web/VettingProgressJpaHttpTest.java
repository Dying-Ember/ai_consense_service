package com.consense.web;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.document.DocumentParser;
import com.consense.service.vetting.OerBusinessEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real MVC, service, JPA and isolated H2; authored poison data exposes accidental heavy reads. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
    "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory",
    "consense.oer-evidence.base-url=","spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class VettingProgressJpaHttpTest {
    static final String DB=UUID.randomUUID().toString().replace("-","");
    static final String PROJECT="progress-project",RUN="progress-run";
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:h2:mem:progress_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","progress-uploads",DB).toAbsolutePath().toString());
        r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());
    }
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired VettingRunRepository runs;
    @Autowired VettingFindingRepository findings;
    @Autowired SourceDocumentRepository documents;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;
    @MockBean AiGateway externalLlm;
    @SpyBean DocumentParser actualParser;
    @SpyBean OerBusinessEvidence actualOer;

    @BeforeEach void seed(){
        jdbc.update("DELETE FROM vetting_finding");jdbc.update("DELETE FROM vetting_run");jdbc.update("DELETE FROM source_document");jdbc.update("DELETE FROM project");
        jdbc.execute("ALTER TABLE source_document ALTER COLUMN id RESTART WITH 101");
        Project p=new Project();p.setId(PROJECT);p.setNameEn("Authored progress test");projects.saveAndFlush(p);
        SourceDocument d=new SourceDocument();d.setProjectId(PROJECT);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setFileKey("SCC");d.setFileName("SCC.txt");d.setReviewRole("tender");d.setDocumentScope("package");d.setParseStatus("PARSED");
        d.setTextContent("Authored SCC 1.1 unchanged source text.");d.setStructuredContentJson("[{\"paragraphStyleName\":\"Heading 1\",BROKEN]");
        d.setNativePdfEvidenceJson(String.join("",Collections.nCopies(32768,"unused-heavy-archive")));documents.saveAndFlush(d);
        VettingRun r=new VettingRun();r.setId(RUN);r.setProjectId(PROJECT);r.setStatus("RUNNING");r.setPhase("semantic");r.setCompletedUnits(17);r.setTotalUnits(19);
        r.setMessage("Authored running state");r.setStartedAt(Instant.parse("2026-10-10T00:00:00Z"));r.setSourceSnapshotSha256(String.join("",Collections.nCopies(64,"a")));
        r.setCoverageJson("{}");r.setResultJson(null);r.setModelIdentityJson("{\"profileId\":\"authored\",\"provider\":\"minimax\",\"model\":\"MiniMax-M3\",\"configurationSha256\":\"authored-configuration\",\"identityScope\":\"captured\"}");runs.saveAndFlush(r);
        clearFeedback();
    }

    @Test void findingListLoadsSharedRunOncePerRequestWithoutCrossRequestCache()throws Exception {
        for(int i=1;i<=3;i++) {
            VettingFinding f=new VettingFinding();f.setProjectId(PROJECT);f.setCode("AUTHORED-"+i);
            f.setGroupKey("risk");f.setRunId(RUN);f.setEvidenceJson("[]");findings.saveAndFlush(f);
        }
        for(char snapshot: new char[]{'a','b'}) {
            String expected=String.join("",Collections.nCopies(64,String.valueOf(snapshot)));
            jdbc.update("UPDATE vetting_run SET source_snapshot_sha256=? WHERE id=?",expected,RUN);
            clearFeedback();JsonNode response=call("/findings");assertEquals(0,response.path("code").asInt(),response.toString());
            assertEquals(3,response.path("data").size());
            for(JsonNode finding:response.path("data")) {
                assertEquals(RUN,finding.path("runId").asText());
                assertEquals(expected,finding.path("sourceSnapshotSha256").asText());
            }
            List<String> sql=selects("vetting_run");long runLoads=loads(VettingRun.class);
            System.out.println("FINDING_LIST_RUN_SQL "+JsonUtils.write(sql)+" ENTITY_LOADS="+runLoads);
            assertEquals(1,sql.size(),"Findings sharing a run need one actual SELECT per independent HTTP request");
            assertEquals(1,runLoads,"The request transaction may reuse its managed run, never a cross-request cache");
        }
    }

    @Test void sameSavedRunLegacyReadsSourceAndParsesItsHashWhileProgressDoesNeither() throws Exception {
        JsonNode legacy=call("/runs/"+RUN);assertNotEquals(0,legacy.path("code").asInt());assertTrue(legacy.path("message").asText().contains("JSON"));
        assertTrue(selects("source_document").size()>0);assertEquals(0,loads(SourceDocument.class),"Business sources are detached selected fields; unrelated native archives are not hydrated");
        assertTrue(columns(selects("vetting_run").get(0)).containsAll(Arrays.asList("result_json","coverage_json")));
        System.out.println("LEGACY_SAME_RUN_SQL "+JsonUtils.write(SqlCapture.statements));clearFeedback();
        JsonNode progress=call("/runs/"+RUN+"/progress");assertEquals(0,progress.path("code").asInt(),progress.toString());
        assertEquals("RUNNING",progress.path("data").path("status").asText());assertEquals("NOT_CHECKED",progress.path("data").path("sourceVerification").asText());
        assertLeanSql();
    }

    @ParameterizedTest @ValueSource(strings={"QUEUED","RUNNING","COMPLETED","FAILED"})
    void everySavedStateRemainsUnverifiedAndIgnoresPoisonReportAndCoverage(String state)throws Exception {
        String poison="{BROKEN "+String.join("",Collections.nCopies(65536,"authored-unused"));
        jdbc.update("UPDATE vetting_run SET status=?,result_json=?,coverage_json=?,error=?,finished_at=? WHERE id=?",state,poison,poison,"FAILED".equals(state)?"Authored model timeout":null,
                Arrays.asList("COMPLETED","FAILED").contains(state)?java.sql.Timestamp.from(Instant.parse("2026-10-10T00:10:00Z")):null,RUN);
        clearFeedback();JsonNode response=call("/runs/"+RUN+"/progress");assertEquals(0,response.path("code").asInt());JsonNode data=response.path("data");
        Set<String> fields=new LinkedHashSet<>();data.fieldNames().forEachRemaining(fields::add);
        assertEquals(new LinkedHashSet<>(Arrays.asList("id","status","phase","completedUnits","totalUnits","message","error","startedAt","finishedAt","sourceSnapshotSha256","modelIdentity","recoveryStatus","sourceVerification")),fields);
        assertEquals(state,data.path("status").asText());assertEquals("NOT_CHECKED",data.path("sourceVerification").asText());assertEquals(17,data.path("completedUnits").asInt());assertEquals(19,data.path("totalUnits").asInt());
        assertEquals("2026-10-10T00:00:00Z",data.path("startedAt").asText());assertEquals("MiniMax-M3",data.path("modelIdentity").path("model").asText());
        assertEquals("FAILED".equals(state)?"Authored model timeout":null,data.path("error").isNull()?null:data.path("error").asText());
        assertLeanSql();
    }

    @Test void latestUsesProjectAndStartedAtOrderingAndAbsentLatestStillReturnsNull()throws Exception {
        Project other=new Project();other.setId("other-project");projects.saveAndFlush(other);
        VettingRun newest=new VettingRun();newest.setId("newest-run");newest.setProjectId(PROJECT);newest.setStatus("FAILED");newest.setPhase("failed");newest.setStartedAt(Instant.parse("2026-10-10T01:00:00Z"));runs.saveAndFlush(newest);
        VettingRun foreign=new VettingRun();foreign.setId("foreign-run");foreign.setProjectId("other-project");foreign.setStatus("COMPLETED");foreign.setStartedAt(Instant.parse("2026-10-10T02:00:00Z"));runs.saveAndFlush(foreign);
        clearFeedback();JsonNode latest=call("/runs/latest/progress");assertEquals(0,latest.path("code").asInt());assertEquals("newest-run",latest.path("data").path("id").asText());assertTrue(latest.path("data").path("modelIdentity").isNull());assertLeanSql();
        String latestSql=selects("vetting_run").get(0);assertTrue(latestSql.contains("order by"));assertTrue(latestSql.contains("started_at desc"));assertTrue(latestSql.contains("limit"));
        jdbc.update("DELETE FROM vetting_run WHERE project_id=?",PROJECT);clearFeedback();JsonNode absent=call("/runs/latest/progress");assertEquals(0,absent.path("code").asInt());assertTrue(absent.path("data").isNull()||absent.path("data").isMissingNode());assertLeanSql();
    }

    @Test void unknownAndCrossProjectRunIdsRetainProjectScopedErrorsWithoutReadingSources()throws Exception {
        Project other=new Project();other.setId("other-project");projects.saveAndFlush(other);clearFeedback();
        JsonNode cross=callPath("/api/vetting/other-project/runs/"+RUN+"/progress");assertEquals(4102,cross.path("code").asInt());assertLeanSql();
        clearFeedback();assertEquals(4102,call("/runs/unknown/progress").path("code").asInt());assertLeanSql();
        clearFeedback();assertEquals(4004,callPath("/api/vetting/absent-project/runs/"+RUN+"/progress").path("code").asInt());assertEquals(0,selects("vetting_run").size());assertEquals(0,selects("source_document").size());
        clearFeedback();assertEquals(4004,callPath("/api/vetting/absent-project/runs/latest/progress").path("code").asInt());assertEquals(0,selects("vetting_run").size());
    }

    @Test void completedProgressCannotBypassChangedSourceReportOrLegacyDetailGate()throws Exception {
        // Independent exact-byte golden, authored before this regression (not a production hash helper).
        jdbc.update("UPDATE source_document SET structured_content_json='[]' WHERE id=101");
        jdbc.update("UPDATE vetting_run SET status='COMPLETED',source_snapshot_sha256=? WHERE id=?","aad2c86945b557b247e1cdd61f6cf7413994706c2821092ec6bd44c60d0155cb",RUN);
        clearFeedback();long detailStart=System.nanoTime();JsonNode before=call("/runs/"+RUN);long detailNanos=System.nanoTime()-detailStart;assertEquals(0,before.path("code").asInt());assertFalse(before.path("data").path("sourceSetStale").asBoolean());
        assertEquals(0,loads(SourceDocument.class));assertEquals(1,loads(VettingRun.class));List<String> detailSql=new ArrayList<>(SqlCapture.statements);
        clearFeedback();long progressStart=System.nanoTime();JsonNode same=call("/runs/"+RUN+"/progress");long progressNanos=System.nanoTime()-progressStart;assertEquals(before.path("data").path("id"),same.path("data").path("id"));assertEquals(before.path("data").path("sourceSnapshotSha256"),same.path("data").path("sourceSnapshotSha256"));assertLeanSql();
        Map<String,Object> comparison=new LinkedHashMap<>();comparison.put("runId",RUN);comparison.put("sameSourceSnapshot",same.path("data").path("sourceSnapshotSha256").asText());comparison.put("legacyWallNanos",detailNanos);comparison.put("progressWallNanos",progressNanos);comparison.put("legacySql",detailSql);comparison.put("progressSql",new ArrayList<>(SqlCapture.statements));comparison.put("boundary","Single authored small H2 request pair; no live/large-source throughput claim");System.out.println("SAME_VALID_RUN_CALLSITE_COMPARISON "+JsonUtils.write(comparison));
        jdbc.update("UPDATE source_document SET text_content='Authored SCC changed contractual obligations.' WHERE id=101");clearFeedback();
        JsonNode progress=call("/runs/"+RUN+"/progress");assertEquals("COMPLETED",progress.path("data").path("status").asText());assertEquals("NOT_CHECKED",progress.path("data").path("sourceVerification").asText());assertLeanSql();
        clearFeedback();JsonNode after=call("/runs/"+RUN);assertEquals(0,after.path("code").asInt());assertTrue(after.path("data").path("sourceSetStale").asBoolean());assertEquals(0,loads(SourceDocument.class));
        clearFeedback();JsonNode report=call("/report.json");assertEquals(4104,report.path("code").asInt());assertTrue(selects("source_document").size()>0);assertEquals(0,loads(SourceDocument.class));
        System.out.println("CHANGED_SOURCE_REPORT_GATE_SQL "+JsonUtils.write(SqlCapture.statements));
    }

    private JsonNode call(String suffix)throws Exception{return callPath("/api/vetting/"+PROJECT+suffix);}
    private JsonNode callPath(String path)throws Exception{org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(get(path)).andReturn().getResponse();assertEquals(200,response.getStatus());return JsonUtils.parse(response.getContentAsString());}
    private void clearFeedback(){SqlCapture.statements.clear();emf.unwrap(SessionFactory.class).getStatistics().clear();org.mockito.Mockito.clearInvocations(externalLlm,actualParser,actualOer);}
    private long loads(Class<?> type){return emf.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(type.getName()).getLoadCount();}
    private List<String> selects(String table){List<String> out=new ArrayList<>();for(String raw:SqlCapture.statements){String sql=raw.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();if(sql.startsWith("select ")&&sql.contains(" from "+table+" "))out.add(sql);}return out;}
    private Set<String> columns(String sql){Set<String> out=new LinkedHashSet<>();for(String value:sql.substring(7,sql.indexOf(" from ")).split(",")){String column=value.trim().split("\\s+")[0];out.add(column.substring(column.lastIndexOf('.')+1));}return out;}
    private void assertLeanSql(){
        assertEquals(0,selects("source_document").size());assertEquals(0,loads(SourceDocument.class));assertEquals(0,loads(VettingRun.class));
        List<String> sql=selects("vetting_run");assertEquals(1,sql.size());assertEquals(new LinkedHashSet<>(Arrays.asList("id","status","phase","completed_units","total_units","message","error","started_at","finished_at","source_snapshot_sha256","model_identity_json","recovery_status")),columns(sql.get(0)));
        org.mockito.Mockito.verifyNoInteractions(externalLlm,actualParser,actualOer);System.out.println("LEAN_PROGRESS_SQL "+sql.get(0));
    }
}
