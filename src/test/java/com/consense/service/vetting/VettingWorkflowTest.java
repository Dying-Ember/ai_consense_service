package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.domain.VettingFinding;
import com.consense.domain.VettingRun;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.repository.VettingFindingRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.*;

/**
 * Committed database records, a real asynchronous service and real document/report writers.
 * Deliberately not transactional: the worker must see the uploaded source transaction.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "consense.llm.enabled=false",
        "consense.ocr.enabled=false",
        "consense.vector.provider=memory",
        "consense.vetting.semantic-enabled=true",
        "logging.level.com.consense=INFO"
})
@ActiveProfiles("h2")
class VettingWorkflowTest {
    private static final String DATABASE_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String DELETION = "SCC 9.1 Clause 25.2(4) of the General Conditions of Contract is deleted.";
    private static final String REFERENCE = "PRE 18.1 The contractor shall comply with GCC25.2(4) when applying for interim payment.";

    @DynamicPropertySource
    static void isolatedResources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:vetting_workflow_" + DATABASE_ID
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root", () -> Paths.get("target", "workflow-uploads", DATABASE_ID).toAbsolutePath().toString());
    }

    @Autowired private VettingService service;
    @Autowired private DocumentParser parser;
    @Autowired private ProjectRepository projects;
    @Autowired private SourceDocumentRepository documents;
    @Autowired private VettingFindingRepository findings;
    @Autowired private VettingRunRepository runs;
    @Autowired private ConsenseProperties props;
    @MockBean private AiGateway ai;
    @MockBean private VettingRetrievalClient retrieval;

    @BeforeEach
    void offlineModel() {
        props.getDocument().setProbeDirectory(null);
        props.getVetting().setExportProbeDirectory(null);
        props.getVetting().setRequireHybridRetrieval(false);props.getVetting().setProbeDirectory(null);
        when(ai.available()).thenReturn(false);
        when(ai.chatModel()).thenReturn("offline-test-model");
    }

    @Test
    void everyReportFormatObservesActualRunPayloadAndExactReturnedBytesWithoutChangingJson() throws Exception {
        String projectId=project("Report operation observation");
        source(projectId,SourceDocument.CATEGORY_VETTING_PACKAGE,"NTT","NTT.txt","NTT 1.1 Tenderers shall provide the original form with the stated source condition.");
        VettingJobVO run=complete(projectId,service.startRun(projectId,"en"));byte[] baseline=service.exportJson(projectId,"en");
        java.nio.file.Path root=Paths.get("target","export-probes",DATABASE_ID,projectId).toAbsolutePath();props.getVetting().setExportProbeDirectory(root.toString());
        Map<String,byte[]> reports=new LinkedHashMap<>();reports.put("json",service.exportJson(projectId,"en"));reports.put("docx",service.exportDocx(projectId,"en"));reports.put("pdf",service.exportPdf(projectId,"en"));
        assertArrayEquals(baseline,reports.get("json"),"Observation must not change original JSON payload ordering or bytes");
        int seen=0;Set<String> operationIds=new HashSet<>();
        try(java.util.stream.Stream<java.nio.file.Path> directories=java.nio.file.Files.list(root)) {
            for(java.nio.file.Path directory:directories.collect(Collectors.toList())) {
                List<JsonNode> records;
                try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.list(directory)) {
                    records=files.filter(p->p.getFileName().toString().matches("[0-9]{5}-[a-z_]+\\.json")).map(p->{try{return JsonUtils.parse(new String(java.nio.file.Files.readAllBytes(p),StandardCharsets.UTF_8));}catch(Exception e){throw new RuntimeException(e);}}).collect(Collectors.toList());
                }
                assertFalse(records.isEmpty());assertTrue(records.stream().allMatch(record->run.getId().equals(record.path("runId").asText())));
                JsonNode returned=records.stream().filter(record->"actual_returned_report_bytes".equals(record.path("phase").asText())).findFirst().get();String format=returned.path("format").asText();
                assertTrue(operationIds.add(returned.path("operationId").asText()));assertEquals(projectId,returned.path("projectId").asText());
                assertEquals(com.consense.document.DocumentParseProbe.sha256(reports.get(format)),returned.path("artifact").path("sha256").asText());
                assertArrayEquals(reports.get(format),java.nio.file.Files.readAllBytes(directory.resolve(returned.path("artifact").path("relativePath").asText())));
                JsonNode payload=records.stream().filter(record->"report_payload".equals(record.path("phase").asText())).findFirst().get();
                assertEquals(run.getId(),payload.path("observations").path("job").path("id").asText());
                assertEquals("returned_normally",JsonUtils.parse(new String(java.nio.file.Files.readAllBytes(directory.resolve("probe_overhead_manifest.json")),StandardCharsets.UTF_8)).path("status").asText());seen++;
            }
        }
        assertEquals(3,seen);assertEquals(run.getId(),service.latestRun(projectId).getId());
    }

    @Test
    void blockedReportKeepsOriginalFailureAndDoesNotInventACompletedRunIdentity() throws Exception {
        String projectId=project("No completed report identity");BizException original=assertThrows(BizException.class,()->service.exportJson(projectId,"en"));
        java.nio.file.Path root=Paths.get("target","blocked-export-probes",DATABASE_ID,projectId).toAbsolutePath();props.getVetting().setExportProbeDirectory(root.toString());
        BizException observed=assertThrows(BizException.class,()->service.exportJson(projectId,"en"));assertEquals(original.getCode(),observed.getCode());assertEquals(original.getMessage(),observed.getMessage());
        java.nio.file.Path directory;try(java.util.stream.Stream<java.nio.file.Path> directories=java.nio.file.Files.list(root)){directory=directories.findFirst().get();}
        JsonNode summary=JsonUtils.parse(new String(java.nio.file.Files.readAllBytes(directory.resolve("probe_overhead_manifest.json")),StandardCharsets.UTF_8));
        assertEquals("aborted",summary.path("status").asText());assertTrue(summary.path("runId").isNull());
        try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.list(directory)) {
            for(java.nio.file.Path path:files.filter(p->p.getFileName().toString().matches("[0-9]{5}-[a-z_]+\\.json")).collect(Collectors.toList())) {
                JsonNode event=JsonUtils.parse(new String(java.nio.file.Files.readAllBytes(path),StandardCharsets.UTF_8));assertTrue(event.path("runId").isNull());
                assertFalse(event.path("observations").path("runIdentityKnown").asBoolean());
            }
        }
        assertNull(service.latestRun(projectId));
    }

    @Test
    void vettingUploadUsesExplicitOriginalShaAndPersistedOwnerAndLaterChunksAreObserved() throws Exception {
        String projectId=project("Source operation observation");
        java.nio.file.Path probeRoot=Paths.get("target","document-probes",DATABASE_ID).toAbsolutePath();props.getDocument().setProbeDirectory(probeRoot.toString());
        byte[] original=word("The supplied contract contains an explicit original obligation and condition.");
        service.uploadPackage(projectId,Collections.singletonList(upload("source.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",original)));
        SourceDocument source=documents.findByProjectIdOrderByIdAsc(projectId).get(0);assertEquals("PARSED",source.getParseStatus());
        java.nio.file.Path parseDir;
        try(java.util.stream.Stream<java.nio.file.Path> dirs=java.nio.file.Files.list(probeRoot)) {parseDir=dirs.filter(p->p.getFileName().toString().startsWith("parse-")).findFirst().get();}
        JsonNode binding=JsonUtils.parse(new String(java.nio.file.Files.readAllBytes(parseDir.resolve("source_persisted_binding.json")),StandardCharsets.UTF_8));
        assertEquals(String.valueOf(source.getId()),binding.path("sourceId").asText());
        assertEquals(com.consense.document.DocumentParseProbe.sha256(original),binding.path("sourceSha256").asText());
        assertEquals(VettingCorpus.sourceHash(source),binding.path("parsedSourceHash").asText());
        RunResultVO result=service.run(projectId,"en");
        VettingJobVO run=service.latestRun(projectId);assertEquals("COMPLETED",run.getStatus());
        assertTrue(java.nio.file.Files.exists(probeRoot.resolve("corpus-"+run.getId()).resolve("probe_overhead_manifest.json")));
    }

    @Test
    void strictHybridFailureIsPersistedFailedWithExplicitRunProbeInsteadOfNewFindings() throws Exception {
        String projectId=project("Strict hybrid failure observation");
        service.uploadPackage(projectId,Collections.singletonList(upload("contract.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                word("The contract specifies an original source requirement for inspection."))));
        props.getVetting().setRequireHybridRetrieval(true);
        java.nio.file.Path probeRoot=Paths.get("target","workflow-probes",DATABASE_ID).toAbsolutePath();props.getVetting().setProbeDirectory(probeRoot.toString());
        when(ai.available()).thenReturn(true);
        doThrow(new IllegalStateException("Exact query cache/corpus identity unavailable")).when(retrieval).index(eq(projectId),anyList(),any(VettingReviewProbe.Call.class));
        VettingJobVO job=service.startRun(projectId,"en");
        for(int i=0;i<300;i++) {job=service.getRun(projectId,job.getId());if("FAILED".equals(job.getStatus()))break;Thread.sleep(10);}
        assertEquals("FAILED",job.getStatus());assertTrue(job.getError().contains("Required hybrid retrieval index failed"));
        assertTrue(findings.findByProjectIdOrderByCodeAsc(projectId).isEmpty());
        assertEquals(job.getId(),service.latestRun(projectId).getId());
        java.nio.file.Path runRoot=probeRoot.resolve(job.getId());assertTrue(java.nio.file.Files.isDirectory(runRoot));
        String trace;
        try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.list(runRoot)) {
            trace=files.filter(path->path.toString().endsWith(".json")).map(path->{try{return new String(java.nio.file.Files.readAllBytes(path),StandardCharsets.UTF_8);}catch(Exception e){throw new RuntimeException(e);}}).collect(Collectors.joining("\n"));
        }
        assertTrue(trace.contains("semantic_review_failed"));assertTrue(trace.contains(job.getId()));
        assertTrue(trace.contains("required_hybrid_index"));
    }

    @Test
    void originalFileIsBoundToItsProjectEvidenceRevisionAndVettingStorage() throws Exception {
        String projectId=project("Visual original review"),other=project("Other original review");
        byte[] original=word(DELETION);
        service.uploadPackage(projectId,Collections.singletonList(upload("SCC.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",original)));
        SourceDocument source=documents.findByProjectIdOrderByIdAsc(projectId).get(0);
        String revision=VettingCorpus.sourceHash(source);
        VettingService.OriginalSource served=service.originalSource(projectId,source.getId(),revision);
        assertArrayEquals(original,java.nio.file.Files.readAllBytes(served.getPath()));
        assertEquals(revision,served.getSourceRevision());assertEquals("SCC.docx",served.getFileName());
        assertThrows(BizException.class,()->service.originalSource(other,source.getId(),revision));
        assertThrows(BizException.class,()->service.originalSource(projectId,source.getId(),"stale-revision"));
        assertThrows(BizException.class,()->service.originalSource(projectId,source.getId(),null));
        source.setCategory("advice");documents.saveAndFlush(source);
        assertThrows(BizException.class,()->service.originalSource(projectId,source.getId(),revision));
        java.nio.file.Path outside=java.nio.file.Files.createTempFile("consense-outside-original-test-",".docx");
        try {
            source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setStoragePath(outside.toString());documents.saveAndFlush(source);
            assertThrows(BizException.class,()->service.originalSource(projectId,source.getId(),revision));
        } finally {java.nio.file.Files.deleteIfExists(outside);}
    }

    @Test
    void actualEmptyModelCallsPersistTheirLedgerAndRestoreItFromTheCommittedRun() throws Exception {
        String projectId=project("Persisted semantic call ledger");
        when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("controlled-local-model");
        when(ai.completeStructuredJsonList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(VettingSemanticReview.Record.class),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Collections.emptyList());
        service.uploadPackage(projectId,Collections.singletonList(upload("SCC.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",word(DELETION))));
        VettingJobVO completed=complete(projectId,service.startRun(projectId,"en"));
        assertEquals(VettingSemanticReview.maxTopics(),completed.getCoverage().getSemanticTopics().size());
        assertTrue(completed.getCoverage().getSemanticTopics().stream().anyMatch(t->"completed_empty".equals(t.getStatus())));
        VettingJobVO restored=service.getRun(projectId,completed.getId());
        assertEquals(JsonUtils.write(completed.getCoverage().getSemanticTopics()),JsonUtils.write(restored.getCoverage().getSemanticTopics()));
        for(SemanticTopicVO topic:restored.getCoverage().getSemanticTopics()) {
            assertEquals(0,topic.getAcceptedFindings());assertEquals(0,topic.getReturnedAssessments());
            assertTrue("completed_empty".equals(topic.getStatus())||"not_submitted".equals(topic.getStatus()));
        }
    }

    @Test
    void projectReferenceCallsExtendPersistedProgressAndRestoreTheirSourceLinkedLedger() throws Exception {
        String projectId = project("Project reference progress and ledger");
        SourceDocument tender = source(projectId, SourceDocument.CATEGORY_VETTING_PACKAGE, "SCT", "SCT.docx",
                "SCT37 Site inspection\nThe tenderer shall request access in writing at least nine days before the inspection.");
        service.uploadPackage(projectId, Collections.singletonList(upload("project-reply.txt", "text/plain",
                "For SCT37, the inspection dates are 11 to 15 November. The access request remains in writing."
                        .getBytes(StandardCharsets.UTF_8))), "project_fact");
        SourceDocument fact = documents.findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_VETTING_PACKAGE)
                .stream().filter(document -> "project-reply.txt".equals(document.getFileName())).findFirst().orElseThrow(AssertionError::new);
        when(ai.available()).thenReturn(true);
        when(ai.chatModel()).thenReturn("controlled-local-model");
        java.util.concurrent.CountDownLatch comparisonCall = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(ai.completeStructuredJsonList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(VettingSemanticReview.Record.class), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call -> {
                    if (call.<String>getArgument(1).startsWith("Audit topic: Project information comparison:")) {
                        comparisonCall.countDown();
                        if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release comparison call");
                    }
                    return Collections.emptyList();
                });
        VettingJobVO started = service.startRun(projectId, "en");
        try {
            assertTrue(comparisonCall.await(10, java.util.concurrent.TimeUnit.SECONDS), "The worker must reach the extra source-linked comparison call");
            VettingJobVO running = service.getRun(projectId, started.getId());
            assertEquals("RUNNING", running.getStatus());
            assertEquals(VettingSemanticReview.maxTopics() + 3, running.getTotalUnits(),
                    "The persisted total includes rules, sixteen topic calls, the source-linked call and saving");
            assertEquals(VettingSemanticReview.maxTopics() + 1, running.getCompletedUnits());
        } finally {
            release.countDown();
        }
        VettingJobVO completed = complete(projectId, started);
        assertEquals(VettingSemanticReview.maxTopics() + 3, completed.getTotalUnits());
        assertEquals(completed.getTotalUnits(), completed.getCompletedUnits());
        VettingJobVO restored = service.getRun(projectId, completed.getId());
        assertEquals(VettingSemanticReview.maxTopics() + 1, restored.getCoverage().getSemanticTopics().size());
        SemanticTopicVO comparison = restored.getCoverage().getSemanticTopics().stream()
                .filter(topic -> "project_reference".equals(topic.getReviewKind())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("source-fact-comparisons-v1", comparison.getSelectionStrategy());
        assertEquals(Collections.singletonList("SCT37"), comparison.getReferenceIds());
        assertEquals("completed_empty", comparison.getStatus());
        assertEquals(0, comparison.getAcceptedFindings());
        assertTrue(coverage(restored, tender).getReviewedSegments() > 0);
        assertTrue(coverage(restored, fact).getReviewedSegments() > 0);
        assertTrue(list(projectId).isEmpty(), "A completed empty comparison is not a finding");
        JsonNode exported = JsonUtils.parse(new String(service.exportJson(projectId, "en"), StandardCharsets.UTF_8));
        JsonNode last = exported.path("job").path("coverage").path("semanticTopics").get(VettingSemanticReview.maxTopics());
        assertEquals("project_reference", last.path("reviewKind").asText());
        assertEquals("SCT37", last.path("referenceIds").get(0).asText());
    }

    @Test
    void previouslyParsedOcrSourceGetsOriginalPageWarningsInCoverageWithoutReparsing() throws Exception {
        String projectId=project("Existing OCR coverage");
        SourceDocument scan=source(projectId,SourceDocument.CATEGORY_VETTING_PACKAGE,"FT","FT.txt","The tenderer shall provide the completed form and appointment schedule.");
        String hash=VettingCorpus.sourceHash(scan),structure=scan.getStructuredContentJson();
        scan.setOcrUsed(true);documents.saveAndFlush(scan);
        assertTrue(service.listFiles(projectId).stream().filter(f -> "FT.txt".equals(f.getFileName())).flatMap(f -> f.getWarnings().stream()).anyMatch(w -> w.contains("OCR")&&w.contains("删除线")&&w.contains("原始物理页")));
        VettingJobVO job=complete(projectId,service.startRun(projectId,"en"));
        assertTrue(coverage(job,scan).getWarnings().stream().anyMatch(w -> w.contains("OCR")&&w.contains("行列")&&w.contains("图形")));
        SourceDocument stored=documents.findById(scan.getId()).get();assertEquals(structure,stored.getStructuredContentJson());assertEquals(hash,VettingCorpus.sourceHash(stored));
    }

    @Test
    void uploadedPartialAndFailedPagesKeepEvidenceCoverageAndManualStatusAcrossRuns() throws Exception {
        String projectId = project("Evidence and coverage");
        service.uploadPackage(projectId, Arrays.asList(
                upload("SCC.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word(DELETION)),
                upload("PRE.pdf", "application/pdf", pdf(REFERENCE, true)),
                upload("FT.pdf", "application/pdf", pdf(null, false))));

        List<SourceDocument> uploaded = documents.findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_VETTING_PACKAGE);
        assertEquals(3, uploaded.size(), "A failed parse must remain in the source inventory");
        SourceDocument scc = byKey(uploaded, "SCC"), pre = byKey(uploaded, "PRE"), failed = byKey(uploaded, "FT");
        assertEquals("PARSED", scc.getParseStatus());
        assertEquals("PARTIAL", pre.getParseStatus());
        JsonNode missedPages = JsonUtils.parse(pre.getParseCoverageJson()).path("failedPages");
        assertEquals(1, missedPages.size());
        assertEquals(2, missedPages.get(0).asInt());
        assertEquals("FAILED", failed.getParseStatus());
        assertEquals(0, JsonUtils.parse(failed.getParseCoverageJson()).get("parsedPages").asInt());

        VettingJobVO first = complete(projectId, service.startRun(projectId, "en"));
        assertOfflineWarning(first);
        assertEquals(3, first.getCoverage().getTotalDocuments());
        DocumentCoverageVO failedCoverage = coverage(first, failed);
        assertEquals("FAILED", failedCoverage.getParseStatus());
        assertEquals(0, failedCoverage.getTextChars());
        assertEquals(0, failedCoverage.getReviewedChars());
        assertEquals(0, failedCoverage.getReviewedSegments());
        assertFalse(failedCoverage.getWarnings().isEmpty());
        DocumentCoverageVO partialCoverage = coverage(first, pre);
        assertEquals("PARTIAL", partialCoverage.getParseStatus());
        assertTrue(partialCoverage.getTextChars() > 0);
        assertFalse(partialCoverage.getWarnings().isEmpty(), "An unparsed page must not disappear from coverage");

        List<FindingVO> firstFindings = list(projectId);
        assertEquals(1, firstFindings.size());
        FindingVO issue = firstFindings.get(0);
        assertEquals("reference", issue.getGroup());
        assertEquals("rule", issue.getSource());
        assertEquals("verified", issue.getVerification());
        assertEquals(first.getId(), issue.getRunId());
        assertFalse(issue.getFingerprint().isEmpty());
        assertEvidence(issue, scc, DELETION, null);
        assertEvidence(issue, pre, REFERENCE, "P1");
        assertTrue(issue.getEvidence().stream().allMatch(FindingEvidence::isLocated));
        assertEquals(2, issue.getEvidence().stream().map(FindingEvidence::getDocumentId).distinct().count());

        EvidenceVO recovered = service.evidence(projectId, issue.getCode());
        assertTrue(recovered.isLocated());
        assertTrue(recovered.getItems().stream().anyMatch(e -> DELETION.equals(e.getQuote())));
        assertTrue(recovered.getItems().stream().anyMatch(e -> REFERENCE.equals(e.getQuote())));

        String otherProject = project("Foreign status access");
        assertThrows(BizException.class, () -> service.updateStatus(otherProject, issue.getCode(), "Handled"));
        assertThrows(BizException.class, () -> service.evidence(otherProject, issue.getCode()));

        assertThrows(BizException.class, () -> service.updateStatus(projectId, issue.getCode(), "invalid-status"));
        assertEquals(VettingFinding.STATUS_OPEN, list(projectId).get(0).getStatus());
        assertEquals(VettingFinding.STATUS_HANDLED, service.updateStatus(projectId, issue.getCode(), "Handled").getStatus());

        String remarks = "  HUMAN_REVIEW_ONLY_20261002: confirm the intended clause.\r\n人工复核记录 ✓  ";
        String action = "  HUMAN_ACTION_ONLY_20261002: draft correction not yet included in an addendum.  ";
        FindingVO reviewed = service.updateReview(projectId, issue.getCode(), new ReviewUpdateRequest(remarks, action, true));
        Instant reviewTime = reviewed.getReviewUpdatedAt();
        assertEquals(remarks, reviewed.getReviewRemarks());
        assertEquals(action, reviewed.getActionTaken());
        assertTrue(reviewed.getAddendumRequired());
        assertNotNull(reviewTime);
        assertEquals(VettingFinding.STATUS_HANDLED, reviewed.getStatus());
        assertEquals(issue.getBody().getEn(), reviewed.getBody().getEn(), "Human records cannot replace generated conclusions");

        List<String> actualPromptInputs = Collections.synchronizedList(new ArrayList<>());
        when(ai.available()).thenReturn(true);
        when(retrieval.retrieve(org.mockito.ArgumentMatchers.eq(projectId), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            String role = call.getArgument(2);
            List<VettingCorpus.Chunk> corpus = call.getArgument(3);
            return corpus.stream().filter(chunk -> role.equals(chunk.getRole())).collect(Collectors.toList());
        });
        when(ai.completeStructuredJsonList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(VettingSemanticReview.Record.class), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call -> {
                    actualPromptInputs.add(call.getArgument(0)); actualPromptInputs.add(call.getArgument(1));
                    return Collections.emptyList();
                });

        VettingJobVO second = complete(projectId, service.startRun(projectId, "en"));
        assertNotEquals(first.getId(), second.getId());
        FindingVO repeated = list(projectId).get(0);
        assertEquals(issue.getCode(), repeated.getCode());
        assertEquals(issue.getFingerprint(), repeated.getFingerprint());
        assertEquals(VettingFinding.STATUS_HANDLED, repeated.getStatus());
        assertEquals(remarks, repeated.getReviewRemarks());
        assertEquals(action, repeated.getActionTaken());
        assertTrue(repeated.getAddendumRequired());
        assertEquals(reviewTime, repeated.getReviewUpdatedAt(), "A rerun must not change the human review timestamp");
        assertEquals(second.getId(), repeated.getRunId());
        assertEquals(first.getId(), service.getRun(projectId, first.getId()).getId());
        assertEquals(second.getId(), service.latestRun(projectId).getId());
        assertFalse(actualPromptInputs.isEmpty(), "The rerun must exercise actual source prompt assembly with controlled model responses");
        assertTrue(actualPromptInputs.stream().noneMatch(input -> input.contains("HUMAN_REVIEW_ONLY_20261002")
                || input.contains("HUMAN_ACTION_ONLY_20261002")), "Human records must not enter model prompts");
        assertEquals(VettingCorpus.sourceHash(scc), VettingCorpus.sourceHash(documents.findById(scc.getId()).orElseThrow(AssertionError::new)));
        assertEquals(VettingCorpus.sourceHash(pre), VettingCorpus.sourceHash(documents.findById(pre.getId()).orElseThrow(AssertionError::new)));
        VettingFinding persisted = findings.findByProjectIdAndCode(projectId, issue.getCode()).orElseThrow(AssertionError::new);
        assertEquals(VettingFinding.STATUS_HANDLED, persisted.getStatus());
        assertEquals(second.getId(), persisted.getRunId());
        assertEquals(remarks, persisted.getReviewRemarks());
        assertEquals(action, persisted.getActionTaken());
        assertTrue(persisted.getAddendumRequired());
        assertEquals(reviewTime, persisted.getReviewUpdatedAt());

        JsonNode report = JsonUtils.parse(new String(service.exportJson(projectId, "en"), StandardCharsets.UTF_8));
        assertEquals("1.0", report.path("schemaVersion").asText());
        assertEquals(second.getId(), report.path("job").path("id").asText());
        assertEquals("COMPLETED", report.path("job").path("status").asText());
        assertEquals(second.getResult().getTotal(), report.path("findings").size());
        assertEquals(repeated.getCode(), report.path("findings").get(0).path("code").asText());
        assertEquals(VettingFinding.STATUS_HANDLED, report.path("findings").get(0).path("status").asText());
        assertEquals(2, report.path("findings").get(0).path("evidence").size());
        assertEquals(remarks, report.path("findings").get(0).path("reviewRemarks").asText());
        assertEquals(action, report.path("findings").get(0).path("actionTaken").asText());
        assertTrue(report.path("findings").get(0).path("addendumRequired").asBoolean());
        assertEquals(reviewTime, Instant.parse(report.path("findings").get(0).path("reviewUpdatedAt").asText()));
    }

    @Test
    void humanReviewFullReplacementAndStatusChangesPreserveRawTextAndGeneratedFields() {
        String projectId = project("Human review replacement");
        VettingFinding stored = manualFinding(projectId, "VT-001");
        assertNull(stored.getReviewRemarks()); assertNull(stored.getActionTaken());
        assertNull(stored.getAddendumRequired()); assertNull(stored.getReviewUpdatedAt());
        String remarks = "\t  复核意见 😀\r\nKeep user whitespace.  ";
        String action = "  A draft amendment was checked.\nIt has not been issued.  ";
        FindingVO first = service.updateReview(projectId, stored.getCode(), new ReviewUpdateRequest(remarks, action, true));
        Instant timestamp = first.getReviewUpdatedAt();
        VettingFinding restored = findings.findByProjectIdAndCode(projectId, stored.getCode()).orElseThrow(AssertionError::new);
        assertEquals(remarks, restored.getReviewRemarks()); assertEquals(action, restored.getActionTaken());
        assertEquals(timestamp, restored.getReviewUpdatedAt()); assertTrue(restored.getAddendumRequired());
        assertEquals("Generated observation", restored.getBodyEn());
        assertEquals("Generated recommendation", restored.getSuggestionEn());

        FindingVO assigned = service.updateStatus(projectId, stored.getCode(), "Assigned");
        assertEquals(remarks, assigned.getReviewRemarks()); assertEquals(action, assigned.getActionTaken());
        assertTrue(assigned.getAddendumRequired()); assertEquals(timestamp, assigned.getReviewUpdatedAt());
        FindingVO replacement = service.updateReview(projectId, stored.getCode(), new ReviewUpdateRequest("", null, false));
        assertEquals("", replacement.getReviewRemarks(), "Empty user text must not be silently changed to null");
        assertNull(replacement.getActionTaken()); assertFalse(replacement.getAddendumRequired());
        assertEquals(VettingFinding.STATUS_ASSIGNED, replacement.getStatus());
        FindingVO cleared = service.updateReview(projectId, stored.getCode(), new ReviewUpdateRequest(null, null, null));
        assertNull(cleared.getReviewRemarks()); assertNull(cleared.getActionTaken()); assertNull(cleared.getAddendumRequired());
        assertNotNull(cleared.getReviewUpdatedAt());
        assertEquals(VettingFinding.STATUS_ASSIGNED, cleared.getStatus());
        assertEquals("Generated observation", list(projectId).get(0).getBody().getEn());
    }

    @Test
    void invalidHumanReviewUpdatesAreAtomicProjectScopedAndCountUtf16Characters() {
        String projectId = project("Human review validation"), otherProject = project("Foreign review update");
        VettingFinding stored = manualFinding(projectId, "VT-001");
        String boundary = String.join("", Collections.nCopies(2000, "😀"));
        assertEquals(4000, boundary.length());
        FindingVO accepted = service.updateReview(projectId, stored.getCode(), new ReviewUpdateRequest(boundary, "Original action", false));
        Instant timestamp = accepted.getReviewUpdatedAt();
        assertEquals(boundary, accepted.getReviewRemarks(), "The exact 4000 UTF-16 boundary must be stored without clipping");
        assertThrows(BizException.class, () -> service.updateReview(projectId, stored.getCode(), new ReviewUpdateRequest(boundary + "x", "Changed action", true)));
        assertThrows(BizException.class, () -> service.updateReview(projectId, stored.getCode(), new ReviewUpdateRequest("Changed remarks", boundary + "x", true)));
        assertThrows(BizException.class, () -> service.updateReview(projectId, stored.getCode(), null));
        assertThrows(BizException.class, () -> service.updateReview(otherProject, stored.getCode(), new ReviewUpdateRequest("Foreign update", null, true)));
        assertThrows(BizException.class, () -> service.updateReview(projectId, "missing-code", new ReviewUpdateRequest(null, null, null)));
        VettingFinding unchanged = findings.findByProjectIdAndCode(projectId, stored.getCode()).orElseThrow(AssertionError::new);
        assertEquals(boundary, unchanged.getReviewRemarks()); assertEquals("Original action", unchanged.getActionTaken());
        assertFalse(unchanged.getAddendumRequired()); assertEquals(timestamp, unchanged.getReviewUpdatedAt());
        assertEquals(VettingFinding.STATUS_OPEN, unchanged.getStatus());
        assertEquals("Generated observation", unchanged.getBodyEn());
    }

    @Test
    void projectFactsAndBaselineReferencesCannotCreateTenderFindingsOrCrossProjectAccess() throws Exception {
        String projectId = project("Role isolation");
        source(projectId, SourceDocument.CATEGORY_VETTING_PACKAGE, "SCC", "SCC.docx", DELETION);
        source(projectId, SourceDocument.CATEGORY_PROJECT_INPUT, "PRE", "meeting-notes.txt", REFERENCE);
        source(projectId, SourceDocument.CATEGORY_STANDARD_TEMPLATE, "PRE", "standard-PRE.docx", REFERENCE);
        String otherProject = project("Other project");
        source(otherProject, SourceDocument.CATEGORY_VETTING_PACKAGE, "PRE", "PRE.docx", REFERENCE);

        VettingJobVO job = complete(projectId, service.startRun(projectId, "en"));
        assertTrue(list(projectId).isEmpty(), "Project notes and standard references are not active tender provisions");
        assertEquals(0, job.getResult().getTotal());
        assertTrue(findings.findByProjectIdOrderByCodeAsc(otherProject).isEmpty());
        assertNull(service.latestRun(otherProject));
        assertThrows(BizException.class, () -> service.getRun(otherProject, job.getId()));
        assertThrows(BizException.class, () -> service.getRun(projectId, "unknown-run"));
        assertThrows(BizException.class, () -> service.updateStatus(otherProject, "F0001", "Handled"));
    }

    @Test
    void uploadedFactsAndManifestAreStoredWithRolesAndDoNotBecomeTenderRules() throws Exception {
        String projectId = project("Uploaded source roles");
        service.uploadPackage(projectId, Collections.singletonList(upload("SCC.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word(DELETION))));
        service.uploadPackage(projectId, Collections.singletonList(upload("PRE meeting.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word(REFERENCE))), "project_fact");
        service.uploadPackage(projectId, Collections.singletonList(upload("directory.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word(REFERENCE))), "package_manifest");
        service.uploadPackage(projectId, Collections.singletonList(upload("GCC.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word("GCC 8.1 Effective standard condition."))), "auto");
        byte[] mail = ("From: tender@example.test\r\nSubject: GCC discussion\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + REFERENCE).getBytes(StandardCharsets.UTF_8);
        service.uploadPackage(projectId, Collections.singletonList(upload("GCC discussion.eml", "message/rfc822", mail)));
        List<SourceDocument> uploaded = documents.findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_VETTING_PACKAGE);
        assertEquals(5, uploaded.size());
        assertEquals("tender", uploaded.stream().filter(d -> "SCC.docx".equals(d.getFileName())).findFirst().get().getReviewRole());
        assertEquals("project_fact", uploaded.stream().filter(d -> "PRE meeting.docx".equals(d.getFileName())).findFirst().get().getReviewRole());
        SourceDocument manifest = uploaded.stream().filter(d -> "directory.docx".equals(d.getFileName())).findFirst().get();
        assertEquals("package_manifest", manifest.getReviewRole());
        assertEquals("INDEX", manifest.getFileKey());
        assertEquals("standard", uploaded.stream().filter(d -> "GCC.docx".equals(d.getFileName())).findFirst().get().getReviewRole());
        assertEquals("project_fact", uploaded.stream().filter(d -> "GCC discussion.eml".equals(d.getFileName())).findFirst().get().getReviewRole());
        assertThrows(BizException.class, () -> service.uploadPackage(projectId,
                Collections.singletonList(upload("invalid.txt", "text/plain", REFERENCE.getBytes(StandardCharsets.UTF_8))), "answer"));
        assertEquals(5, documents.findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_VETTING_PACKAGE).size());
        VettingJobVO job = complete(projectId, service.startRun(projectId, "en"));
        assertTrue(list(projectId).isEmpty(), "Fact emails and manifests may quote provisions without activating them");
        assertEquals(5, job.getCoverage().getTotalDocuments());
        for (SourceDocument d : uploaded) {
            if (Arrays.asList("project_fact", "package_manifest").contains(d.getReviewRole())) {
                DocumentCoverageVO c = coverage(job, d);
                assertTrue(c.getWarnings().stream().anyMatch(w -> w.contains("不作为有效合同条款")));
                assertFalse(c.getWarnings().stream().anyMatch(w -> w.contains("规则已扫描全部")));
            }
        }
        assertEquals("Project facts", service.listFiles(projectId).stream().filter(f -> "GCC discussion.eml".equals(f.getFileName())).findFirst().get().getRole().getEn());
        for (SourceDocument d : uploaded) {
            assertEquals(d.getReviewRole(), service.listFiles(projectId).stream()
                    .filter(f -> d.getFileName().equals(f.getFileName())).findFirst().get().getSourceRole());
        }
    }

    @Test
    void standardSccInstructionsDoNotDeleteActualTenderReferences() throws Exception {
        String projectId = project("Standard SCC is reference only");
        service.uploadPackage(projectId, Collections.singletonList(upload("standard SCC.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word(DELETION))), "standard");
        service.uploadPackage(projectId, Collections.singletonList(upload("PRE.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word(REFERENCE))), "tender");
        VettingJobVO referenceOnly = complete(projectId, service.startRun(projectId, "en"));
        assertTrue(list(projectId).isEmpty(), "A standard template deletion is not an actual tender amendment");
        SourceDocument standard = documents.findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_VETTING_PACKAGE)
                .stream().filter(d -> "standard SCC.docx".equals(d.getFileName())).findFirst().get();
        assertTrue(coverage(referenceOnly, standard).getWarnings().stream().anyMatch(w -> w.contains("规则未当作实际合同")));
        assertFalse(coverage(referenceOnly, standard).getWarnings().stream().anyMatch(w -> w.contains("规则已扫描全部")));
        service.uploadPackage(projectId, Collections.singletonList(upload("actual SCC.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word(DELETION))), "tender");
        complete(projectId, service.startRun(projectId, "en"));
        List<FindingVO> actual = list(projectId);
        assertEquals(1, actual.size());
        assertEquals("reference", actual.get(0).getGroup());
        assertFalse(actual.get(0).getEvidence().stream().anyMatch(e -> String.valueOf(standard.getId()).equals(e.getDocumentId())));
    }

    @Test
    void zeroFindingsStillExportsLimitsAndAnIncompleteLatestRunBlocksEveryReportFormat() throws Exception {
        String projectId = project("No findings recorded");
        source(projectId, SourceDocument.CATEGORY_VETTING_PACKAGE, "NTT", "NTT.txt",
                "NTT 1.1 Tenderers shall deliver the completed form by the specified tender closing time.");
        assertThrows(BizException.class, () -> service.exportJson(projectId, "en"));
        VettingJobVO job = complete(projectId, service.startRun(projectId, "en"));
        assertEquals(0, job.getResult().getTotal());
        assertTrue(list(projectId).isEmpty());
        assertOfflineWarning(job);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(service.exportDocx(projectId, "en")))) {
            String text = doc.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
            assertTrue(text.contains("No findings were recorded"));
            assertTrue(text.contains(job.getId()));
            assertTrue(text.contains("Document coverage"));
        }
        try (PDDocument doc = PDDocument.load(service.exportPdf(projectId, "en"))) {
            assertTrue(doc.getNumberOfPages() > 0);
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("No findings were recorded"));
            assertTrue(text.contains(job.getId()));
            assertTrue(text.contains("Document coverage"));
        }
        JsonNode report = JsonUtils.parse(new String(service.exportJson(projectId, "en"), StandardCharsets.UTF_8));
        assertEquals("1.0", report.path("schemaVersion").asText());
        assertEquals(job.getId(), report.path("job").path("id").asText());
        assertEquals(0, report.path("findings").size());
        assertFalse(report.path("job").path("coverage").path("warnings").isEmpty());

        VettingRun pending = new VettingRun(); pending.setId(UUID.randomUUID().toString()); pending.setProjectId(projectId);
        pending.setStatus("RUNNING"); pending.setPhase("review"); pending.setLang("en"); pending.setStartedAt(Instant.now().plusSeconds(10));
        runs.saveAndFlush(pending);
        try {
            assertEquals(pending.getId(), service.latestRun(projectId).getId());
            assertThrows(BizException.class, () -> service.exportJson(projectId, "en"));
            assertThrows(BizException.class, () -> service.exportDocx(projectId, "en"));
            assertThrows(BizException.class, () -> service.exportPdf(projectId, "en"));
        } finally {
            runs.deleteById(pending.getId());
        }
    }

    private String project(String name) {
        Project p = new Project(); p.setId("workflow-" + UUID.randomUUID()); p.setNameEn(name);
        p.setNameZhHans(name); p.setNameZhHant(name); p.setContractNo("WF-2026");
        projects.saveAndFlush(p); return p.getId();
    }

    private VettingFinding manualFinding(String projectId, String code) {
        VettingFinding finding = new VettingFinding();
        finding.setProjectId(projectId); finding.setCode(code); finding.setGroupKey("reference");
        finding.setBodyEn("Generated observation"); finding.setSuggestionEn("Generated recommendation");
        return findings.saveAndFlush(finding);
    }

    private SourceDocument source(String projectId, String category, String fileKey, String name, String text) throws Exception {
        DocumentParser.ParsedDocument parsed = parser.parse(name, name.endsWith(".docx") ? word(text) : text.getBytes(StandardCharsets.UTF_8));
        SourceDocument d = new SourceDocument(); d.setProjectId(projectId); d.setCategory(category);
        d.setFileKey(fileKey); d.setFileName(name); d.setTextContent(parsed.getText());
        d.setParseStatus(parsed.getParseStatus()); d.setPageCount(parsed.getPageCount()); d.setOcrUsed(parsed.isOcrUsed());
        d.setStructuredContentJson(JsonUtils.write(parsed.getBlocks())); d.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));
        return documents.saveAndFlush(d);
    }

    private static MockMultipartFile upload(String name, String type, byte[] content) {
        return new MockMultipartFile("files", name, type, content);
    }

    private static byte[] word(String text) throws Exception {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.createParagraph().createRun().setText(text); d.write(out); return out.toByteArray();
        }
    }

    private static byte[] pdf(String text, boolean addUnextractablePage) throws Exception {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); d.addPage(page);
            if (text != null) try (PDPageContentStream stream = new PDPageContentStream(d, page)) {
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 9); stream.newLineAtOffset(45, 730);
                stream.showText(text); stream.endText();
            } else try (PDPageContentStream stream = new PDPageContentStream(d,page)) {
                stream.addRect(45,700,20,20); stream.fill();
            }
            if (addUnextractablePage) {
                PDPage unextractable = new PDPage(); d.addPage(unextractable);
                try (PDPageContentStream stream = new PDPageContentStream(d,unextractable)) {
                    stream.addRect(45,700,20,20); stream.fill();
                }
            }
            d.save(out); return out.toByteArray();
        }
    }

    private VettingJobVO complete(String projectId, VettingJobVO started) throws Exception {
        assertNotNull(started); assertNotNull(started.getId());
        assertTrue(runs.findById(started.getId()).isPresent(), "The job must be committed before asynchronous work begins");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        VettingJobVO job;
        do {
            job = service.getRun(projectId, started.getId());
            if ("COMPLETED".equals(job.getStatus()) || "FAILED".equals(job.getStatus())) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals("COMPLETED", job.getStatus(), "Job did not complete: " + job.getMessage() + " / " + job.getError());
        assertNotNull(job.getStartedAt()); assertNotNull(job.getFinishedAt()); assertNotNull(job.getCoverage()); assertNotNull(job.getResult());
        return job;
    }

    private List<FindingVO> list(String projectId) {
        return service.listFindings(projectId, null, null, null, null, null);
    }

    private static SourceDocument byKey(List<SourceDocument> docs, String key) {
        return docs.stream().filter(d -> key.equals(d.getFileKey())).findFirst().orElseThrow(AssertionError::new);
    }

    private static DocumentCoverageVO coverage(VettingJobVO job, SourceDocument doc) {
        return job.getCoverage().getDocuments().stream().filter(c -> String.valueOf(doc.getId()).equals(c.getDocumentId()))
                .findFirst().orElseThrow(AssertionError::new);
    }

    private static void assertEvidence(FindingVO finding, SourceDocument doc, String expectedQuote, String expectedPage) {
        FindingEvidence e = finding.getEvidence().stream().filter(item -> String.valueOf(doc.getId()).equals(item.getDocumentId()))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(expectedQuote, e.getQuote());
        assertEquals(expectedPage, e.getPageNo());
        assertFalse(e.getAnchor().isEmpty());
        assertEquals(VettingCorpus.sourceHash(doc), e.getSourceHash());
        assertTrue(doc.getTextContent().contains(e.getQuote()));
    }

    private static void assertOfflineWarning(VettingJobVO job) {
        assertFalse(job.getCoverage().getWarnings().isEmpty());
        assertTrue(job.getResult().getMessages().stream().anyMatch(m -> m.contains("语义审查未运行")),
                "Offline completion must explicitly disclose that semantic review did not run");
    }
}
