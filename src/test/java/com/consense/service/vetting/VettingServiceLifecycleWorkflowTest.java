package com.consense.service.vetting;

import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingSemanticReview.*;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real async workflow and unique in-memory H2; SemanticReview is a fixture, no HTTP/model/OCR. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class VettingServiceLifecycleWorkflowTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:h2:mem:lifecycle_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","lifecycle-uploads",ID).toAbsolutePath().toString());
    }
    @Autowired VettingService service;
    @Autowired ConsenseProperties props;
    @Autowired DocumentParser parser;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @Autowired VettingRunRepository runs;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @SpyBean VettingFindingRepository findings;
    @MockBean VettingSemanticReview semantic;
    @BeforeEach void resetFixture(){props.getVetting().setServiceProbeDirectory(null);props.getVetting().setProbeDirectory(null);props.getVetting().setExportProbeDirectory(null);props.getVetting().setMaxRiskFindings(1);props.getDocument().setProbeDirectory(null);}

    @Test void defaultOffAndObservedRerunKeepSelectionAndAllHumanFields() throws Exception {
        String project=project();SourceDocument scc=source(project,"SCC","SCC.txt","SCC 9.1 Clause 25.2(4) of the General Conditions of Contract is deleted.");
        source(project,"PRE","PRE.txt","PRE 18.1 The contractor shall comply with GCC25.2(4) when applying for interim payment.");
        when(semantic.reviewWithProgress(eq(project),anyList(),eq("en"),any(ReviewProgress.class))).thenAnswer(call->{Result result=result(scc,true);call.<ReviewProgress>getArgument(3).update(3,3,"fixture progress");return result;});
        Path root=probeRoot(project);RunResultVO baseline=service.run(project,"en");assertFalse(Files.exists(root));
        List<VettingFinding> original=findings.findByProjectIdOrderByCodeAsc(project).stream().filter(VettingFinding::getActive).collect(Collectors.toList());
        assertTrue(original.size()>=2);VettingFinding human=original.get(0);
        service.updateStatus(project,human.getCode(),"Handled");
        FindingVO manual=service.updateReview(project,human.getCode(),new ReviewUpdateRequest(" 原文\nreview "," action\n保留 ",false));
        props.getVetting().setServiceProbeDirectory(root.toString());RunResultVO observed=service.run(project,"en");
        assertEquals(baseline.getTotal(),observed.getTotal());assertEquals(original.stream().map(VettingFinding::getFingerprint).collect(Collectors.toList()),
                findings.findByProjectIdOrderByCodeAsc(project).stream().filter(VettingFinding::getActive).map(VettingFinding::getFingerprint).collect(Collectors.toList()));
        FindingVO saved=service.listFindings(project,null,null,null,null,null).stream().filter(f->f.getCode().equals(human.getCode())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Handled",saved.getStatus());assertEquals(manual.getReviewRemarks(),saved.getReviewRemarks());assertEquals(manual.getActionTaken(),saved.getActionTaken());
        assertEquals(Boolean.FALSE,saved.getAddendumRequired());assertEquals(manual.getReviewUpdatedAt(),saved.getReviewUpdatedAt());
        VettingJobVO job=service.latestRun(project);List<JsonNode> events=events(root,job.getId());
        assertEquals(job.getId(),one(events,"service_run_bound").path("runId").asText());
        assertEquals(2,one(events,"rule_sources_constructed").path("observations").path("sources").size());
        assertFalse(one(events,"rule_engine_raw_outputs").path("observations").path("outputs").isEmpty());
        assertEquals(4,one(events,"semantic_result_raw_candidates").path("observations").path("result").path("candidates").size());
        List<JsonNode> decisions=phase(events,"candidate_filter_decision");assertEquals(original.size()+3,decisions.size());
        assertEquals(1,decisions.stream().filter(e->"duplicate_fingerprint".equals(e.path("observations").path("reason").asText())).count());
        assertEquals(2,decisions.stream().filter(e->"risk_cap".equals(e.path("observations").path("reason").asText())).count());
        assertEquals(observed.getTotal(),one(events,"final_selected_candidates").path("observations").path("candidatesByFingerprint").size());
        JsonNode records=one(events,"merge_pending_commit_records").path("observations");assertFalse(records.path("committed").asBoolean());assertEquals(observed.getTotal(),records.path("findingVos").size());
        assertTrue(one(events,"merge_and_complete_transaction_returned").path("observations").path("committed").asBoolean());
        assertTrue(phase(events,"progress_transaction_returned").stream().anyMatch(e->e.path("observations").path("totalUnits").asInt()==5));
        assertEquals("completed",one(events,"service_lifecycle_terminal").path("observations").path("status").asText());
        JsonNode persistedHuman=null;for(JsonNode row:records.path("currentRecords"))if(human.getCode().equals(row.path("code").asText()))persistedHuman=row;
        assertNotNull(persistedHuman);assertEquals(manual.getReviewRemarks(),persistedHuman.path("reviewRemarks").asText());assertFalse(persistedHuman.path("addendumRequired").asBoolean());
    }
    @Test void candidateSnapshotsPreserveSourceBeforeOriginalNormalization() throws Exception {
        String project=project();SourceDocument doc=source(project,"NTT","NTT.txt","NTT 1.1 The contractor shall retain the original source description.");
        when(semantic.reviewWithProgress(eq(project),anyList(),eq("en"),any(ReviewProgress.class))).thenAnswer(call->{Result r=result(doc,false);r.getCandidates().get(0).setSource("fixture-provider");return r;});
        Path root=probeRoot(project);props.getVetting().setServiceProbeDirectory(root.toString());service.run(project,"en");List<JsonNode> events=events(root,service.latestRun(project).getId());
        assertEquals("fixture-provider",one(events,"semantic_result_raw_candidates").path("observations").path("result").path("candidates").get(0).path("source").asText());
        JsonNode decision=one(events,"candidate_filter_decision").path("observations");assertEquals("fixture-provider",decision.path("sourceBeforeNormalization").asText());assertEquals("model",decision.path("candidate").path("source").asText());
        assertEquals("model",service.listFindings(project,null,null,null,null,null).get(0).getSource());
    }
    @Test void repeatedSameCandidateObjectRetainsEveryOriginalOccurrenceInTheDecisionTrace() throws Exception {
        String project=project();SourceDocument doc=source(project,"NTT","NTT.txt","NTT 1.1 The source fixture remains immutable during observation.");
        when(semantic.reviewWithProgress(eq(project),anyList(),eq("en"),any(ReviewProgress.class))).thenAnswer(call->{Result r=result(doc,false);r.getCandidates().add(r.getCandidates().get(0));return r;});
        Path root=probeRoot(project);props.getVetting().setServiceProbeDirectory(root.toString());assertEquals(1,service.run(project,"en").getTotal());
        List<JsonNode> decisions=phase(events(root,service.latestRun(project).getId()),"candidate_filter_decision");assertEquals(2,decisions.size());
        for(JsonNode event:decisions){JsonNode original=event.path("observations").path("originalOrdinals");assertEquals(2,original.size());assertEquals(0,original.get(0).asInt());assertEquals(1,original.get(1).asInt());}
        assertEquals("retained",decisions.get(0).path("observations").path("decision").asText());assertEquals("duplicate_fingerprint",decisions.get(1).path("observations").path("reason").asText());
    }
    @Test void actualSemanticFailureKeepsCauseSuppressedAndCommittedFailureStatus() throws Exception {
        String project=project();source(project,"NTT","NTT.txt","NTT 1.1 Original text remains available after a failed review.");
        RuntimeException cause=new RuntimeException("fixture semantic failure",new java.io.IOException("underlying fixture cause"));cause.addSuppressed(new IllegalStateException("original suppressed marker"));
        when(semantic.reviewWithProgress(eq(project),anyList(),eq("en"),any(ReviewProgress.class))).thenThrow(cause);
        Path root=probeRoot(project);props.getVetting().setServiceProbeDirectory(root.toString());BizException outward=assertThrows(BizException.class,()->service.run(project,"en"));
        assertEquals("fixture semantic failure",outward.getMessage());VettingJobVO run=service.latestRun(project);assertEquals("FAILED",run.getStatus());
        List<JsonNode> events=events(root,run.getId());JsonNode tree=one(events,"service_run_failure").path("observations").path("originalFailure");
        assertEquals("fixture semantic failure",tree.path("message").asText());assertEquals("underlying fixture cause",tree.path("cause").path("message").asText());assertEquals("original suppressed marker",tree.path("suppressed").get(0).path("message").asText());
        assertEquals("FAILED",one(events,"failed_run_pending_commit").path("observations").path("run").path("status").asText());
        assertTrue(one(events,"fail_run_transaction_returned").path("observations").path("transactionReturnedNormally").asBoolean());assertTrue(phase(events,"merge_and_complete_transaction_returned").isEmpty());
    }
    @Test void originalPersistenceFailureRollsBackRecordsAndHasNoFalseCommitEvent() throws Exception {
        String project=project();SourceDocument doc=source(project,"NTT","NTT.txt","NTT 1.1 Source identity and existing human records are retained.");
        when(semantic.reviewWithProgress(eq(project),anyList(),eq("en"),any(ReviewProgress.class))).thenAnswer(call->result(doc,false));service.run(project,"en");
        FindingVO before=service.listFindings(project,null,null,null,null,null).get(0);service.updateReview(project,before.getCode(),new ReviewUpdateRequest("original manual","done",true));
        List<VettingFinding> previous=findings.findByProjectIdOrderByCodeAsc(project);String previousRun=previous.get(0).getRunId();
        doThrow(new IllegalStateException("fixture saveAll rejected",new java.io.IOException("storage fixture cause"))).when(findings).saveAll(any());
        Path root=probeRoot(project);props.getVetting().setServiceProbeDirectory(root.toString());assertThrows(BizException.class,()->service.run(project,"en"));
        VettingJobVO run=service.latestRun(project);List<JsonNode> events=events(root,run.getId());assertEquals("FAILED",run.getStatus());
        assertTrue(phase(events,"merge_and_complete_transaction_returned").isEmpty());assertTrue(phase(events,"merge_pending_commit_records").isEmpty());
        assertEquals("fixture saveAll rejected",one(events,"service_run_failure").path("observations").path("originalFailure").path("message").asText());
        assertTrue(phase(events,"phase_timing").stream().anyMatch(e->"merge_and_complete_transaction".equals(e.path("observations").path("stage").asText())&&!e.path("observations").path("completed").asBoolean()));
        VettingFinding restored=findings.findByProjectIdAndCode(project,before.getCode()).orElseThrow(AssertionError::new);assertTrue(Boolean.TRUE.equals(restored.getActive()));assertEquals(previousRun,restored.getRunId());assertEquals("original manual",restored.getReviewRemarks());assertEquals(Boolean.TRUE,restored.getAddendumRequired());
    }
    @Test void unusableObserverDirectoryCannotTurnBusinessSuccessIntoFailure() throws Exception {
        String project=project();SourceDocument doc=source(project,"NTT","NTT.txt","NTT 1.1 Optional observer failure must not modify generated records.");
        when(semantic.reviewWithProgress(eq(project),anyList(),eq("en"),any(ReviewProgress.class))).thenAnswer(call->result(doc,false));Path file=probeRoot(project);Files.createDirectories(file.getParent());Files.write(file,new byte[]{1});
        props.getVetting().setServiceProbeDirectory(file.toString());assertEquals(1,service.run(project,"en").getTotal());assertEquals("COMPLETED",service.latestRun(project).getStatus());assertEquals(1,service.listFindings(project,null,null,null,null,null).size());assertEquals(1,Files.size(file));
    }
    @Test void restartRecoveryRecordsActualRunAndProjectWithoutTouchingCompletedRun() throws Exception {
        String project=project();VettingRun pending=run(project,"RUNNING"),queued=run(project,"QUEUED"),completed=run(project,"COMPLETED");
        runs.saveAll(Arrays.asList(pending,queued,completed));Path root=probeRoot(project);props.getVetting().setServiceProbeDirectory(root.toString());service.recoverInterruptedRuns();
        assertEquals("FAILED",runs.findById(pending.getId()).orElseThrow(AssertionError::new).getStatus());assertEquals("interrupted",runs.findById(queued.getId()).orElseThrow(AssertionError::new).getPhase());assertEquals("COMPLETED",runs.findById(completed.getId()).orElseThrow(AssertionError::new).getStatus());
        for(VettingRun old:Arrays.asList(pending,queued)){List<JsonNode> events=events(root,old.getId());JsonNode before=one(events,"interrupted_run_before_recovery");assertEquals(project,before.path("projectId").asText());assertEquals(old.getStatus(),before.path("observations").path("run").path("status").asText());assertTrue(one(events,"restart_recovery_transaction_returned").path("observations").path("committed").asBoolean());}
        assertTrue(events(root,completed.getId()).isEmpty());
    }
    @Test void recoveryParticipatingInOuterTransactionDoesNotClaimCommitWhenCallerRollsBack() throws Exception {
        String project=project();VettingRun pending=run(project,"RUNNING");runs.saveAndFlush(pending);
        Path root=probeRoot(project);props.getVetting().setServiceProbeDirectory(root.toString());
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
            service.recoverInterruptedRuns();
            assertEquals("FAILED",runs.findById(pending.getId()).orElseThrow(AssertionError::new).getStatus());
            status.setRollbackOnly();return null;
        });
        assertEquals("RUNNING",runs.findById(pending.getId()).orElseThrow(AssertionError::new).getStatus(),"Original REQUIRED recovery participates in caller rollback");
        List<JsonNode> events=events(root,pending.getId());JsonNode observation=one(events,"restart_recovery_transaction_returned").path("observations");
        assertTrue(observation.path("transactionReturnedNormally").asBoolean());assertTrue(observation.path("participatingOuterTransaction").asBoolean());
        assertTrue(observation.path("committed").isNull());assertEquals("unknown_outer_transaction_still_active",observation.path("commitObservation").asText());
        assertEquals("recovery_transaction_returned",one(events,"service_lifecycle_terminal").path("observations").path("status").asText());
    }
    private static Result result(SourceDocument doc,boolean extra){Result r=new Result();r.setModel("fixture-only");r.setPlannedCallCount(3);Candidate high=candidate(doc,"A","high");r.getCandidates().add(high);if(extra){r.getCandidates().add(candidate(doc,"A","high"));r.getCandidates().add(candidate(doc,"B","medium"));r.getCandidates().add(candidate(doc,"C","low"));}return r;}
    private static Candidate candidate(SourceDocument doc,String suffix,String severity){Candidate c=new Candidate();c.setRuleId("fixture-"+suffix);c.setType("risk");c.setSeverity(severity);c.setTitle("Fixture "+suffix);c.setComment("Fixture output, not a real contract assertion");c.setSource("model");c.setEvidence(Collections.singletonList(new FindingEvidence("source",String.valueOf(doc.getId()),doc.getFileKey(),doc.getFileName(),null,"body fixture",doc.getTextContent(),true,VettingCorpus.sourceHash(doc),null)));return c;}
    private String project(){Project p=new Project();p.setId("lifecycle-"+UUID.randomUUID());p.setNameEn("Lifecycle fixture");p.setContractNo("TEST");projects.saveAndFlush(p);return p.getId();}
    private SourceDocument source(String project,String key,String name,String text) throws Exception{DocumentParser.ParsedDocument parsed=parser.parse(name,text.getBytes(StandardCharsets.UTF_8));SourceDocument d=new SourceDocument();d.setProjectId(project);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey(key);d.setFileName(name);d.setTextContent(parsed.getText());d.setParseStatus(parsed.getParseStatus());d.setPageCount(parsed.getPageCount());d.setOcrUsed(false);d.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));d.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));return documents.saveAndFlush(d);}
    private static VettingRun run(String project,String status){VettingRun r=new VettingRun();r.setId(UUID.randomUUID().toString());r.setProjectId(project);r.setLang("en");r.setStartedAt(Instant.now());r.setStatus(status);r.setPhase("fixture");return r;}
    private static Path probeRoot(String project){return Paths.get("target","lifecycle-probes",ID,project).toAbsolutePath();}
    private static List<JsonNode> events(Path root,String runId) throws Exception {List<JsonNode> out=new ArrayList<>();if(!Files.exists(root)||!Files.isDirectory(root))return out;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);boolean terminal=false;
        do{try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path file:files.filter(p->p.getFileName().toString().equals("service_probe_manifest.json")).collect(Collectors.toList()))if(runId.equals(JsonUtils.parse(Files.readString(file,StandardCharsets.UTF_8)).path("runId").asText()))terminal=true;}if(!terminal)Thread.sleep(10);}while(!terminal&&System.nanoTime()<deadline);try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path file:files.filter(p->p.getFileName().toString().matches("\\d{5}-.+\\.json")).sorted().collect(Collectors.toList())){JsonNode event=JsonUtils.parse(Files.readString(file,StandardCharsets.UTF_8));if(runId.equals(event.path("runId").asText()))out.add(event);}}return out;}
    private static List<JsonNode> phase(List<JsonNode> events,String name){return events.stream().filter(e->name.equals(e.path("phase").asText())).collect(Collectors.toList());}
    private static JsonNode one(List<JsonNode> events,String phase){List<JsonNode> result=phase(events,phase);assertEquals(1,result.size(),"Expected one "+phase);return result.get(0);}
}
