package com.consense.web;

import com.consense.ai.*;
import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.*;
import com.consense.service.vetting.*;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Public HTTP, durable H2 records and real bounded worker; model/retrieval are local fixtures. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.llm.num-ctx=524288","consense.llm.structured-thinking=false","consense.ocr.enabled=false","consense.vector.provider=memory","consense.vetting.semantic-topics=2","consense.vetting.project-reference-comparisons=0"})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class VettingRecoveryWorkflowTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:recovery_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get("target","recovery-uploads",ID).toAbsolutePath().toString());}
    @Autowired MockMvc http;
    @Autowired VettingService service;
    @Autowired ConsenseProperties props;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @Autowired VettingRunRepository runs;
    @Autowired VettingFindingRepository findings;
    @Autowired VettingPacketCheckpointRepository packets;
    @Autowired VettingReportSnapshotRepository snapshots;
    @Autowired VettingCheckpointService checkpoints;
    @Autowired VettingSemanticReview semantic;
    @Autowired ProjectService projectService;
    @Autowired StorageService storage;
    @Autowired DocumentParser parser;
    @Autowired VettingReportWriter writer;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired LlmProfiles profiles;
    @Autowired VettingChecklistService checklist;
    @MockBean AiGateway ai;
    @MockBean VettingRetrievalClient retrieval;
    private final AtomicReference<List<VettingCorpus.Chunk>> corpus=new AtomicReference<>();
    @BeforeEach void fixture(){
        semantic.setInputBudgetObserver(VettingTokenFixture::observe);
        props.getVetting().setSemanticTopics(2);props.getVetting().setProjectReferenceComparisons(0);props.getVetting().setProbeDirectory(null);
        when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("fixture-only");
        doAnswer(call->{corpus.set(call.getArgument(1));return null;}).when(retrieval).index(anyString(),anyList());
        when(retrieval.retrieve(anyString(),anyString(),anyString(),anyList())).thenAnswer(call->((List<VettingCorpus.Chunk>)call.getArgument(3)).stream().filter(c->call.getArgument(2).equals(c.getRole())).collect(Collectors.toList()));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->records("issue"));
    }
    @Test void aFailedSecondPacketKeepsTheVerifiedFirstPacketAndRejectsEveryFormalExport() throws Exception {
        String project=project();AtomicInteger calls=new AtomicInteger();
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{if(calls.incrementAndGet()==2)throw new IllegalStateException("fixture model interrupted");return records("issue");});
        String id=start(project);JsonNode saved=await(project,id,"FAILED");
        assertEquals("NEEDS_RERUN",saved.path("recoveryStatus").asText());assertEquals(1,saved.path("reliablePackets").size());
        assertEquals("issue",saved.path("reliablePackets").get(0).path("packet").path("checkState").asText());
        assertEquals(1,saved.path("reliablePackets").get(0).path("candidates").size());assertEquals("incomplete",saved.path("coverage").path("reviewCompleteness").asText());
        assertEquals(2,packets.findByRunIdOrderByUpdatedAtAsc(id).size());assertEquals("not_completed",saved.path("coverage").path("semanticTopics").get(1).path("checkState").asText());
        for(String format:Arrays.asList("json","pdf","docx")){org.springframework.mock.web.MockHttpServletResponse response=http.perform(get("/api/vetting/"+project+"/report."+format)).andReturn().getResponse();assertNull(response.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(response.getContentAsString()).path("code").asInt());}
    }
    @Test void allFormatsUseTheSavedSnapshotAndHumanEditsCreateANewRetainedRevision() throws Exception {
        String project=project();String id=start(project);await(project,id,"COMPLETED");
        byte[] json=http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsByteArray();JsonNode first=JsonUtils.parse(new String(json,StandardCharsets.UTF_8));String snapshot=first.path("reportSnapshotId").asText();assertTrue(snapshot.matches("[a-f0-9]{64}"));assertTrue(snapshots.findById(snapshot).isPresent());
        byte[] pdf=http.perform(get("/api/vetting/"+project+"/report.pdf")).andReturn().getResponse().getContentAsByteArray();try(PDDocument doc=PDDocument.load(pdf)){assertTrue(new PDFTextStripper().getText(doc).replaceAll("\\s","").contains(snapshot));}
        byte[] docx=http.perform(get("/api/vetting/"+project+"/report.docx")).andReturn().getResponse().getContentAsByteArray();try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(docx))){assertTrue(doc.getParagraphs().stream().anyMatch(p->p.getText().contains(snapshot)));}
        JsonNode loaded=first.path("findings").get(0);String code=loaded.path("code").asText();http.perform(put("/api/vetting/"+project+"/findings/"+code+"/review").contentType("application/json").content(reviewBody(loaded,"human retained remark"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        JsonNode changed=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsString());assertNotEquals(snapshot,changed.path("reportSnapshotId").asText());assertTrue(snapshots.findById(snapshot).isPresent());assertTrue(changed.path("findings").toString().contains("human retained remark"));
        SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);source.setTextContent(source.getTextContent()+" changed source");documents.saveAndFlush(source);
        org.springframework.mock.web.MockHttpServletResponse stale=http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse();assertNull(stale.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(stale.getContentAsString()).path("code").asInt());
    }
    @Test void reconstructedWorkerReusesSavedPacketAndAnExpiredOwnerCannotPublish() throws Exception {
        String project=project();AtomicInteger calls=new AtomicInteger();CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{if(calls.incrementAndGet()==2){waiting.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}return records("issue");});
        String id=start(project);assertTrue(waiting.await(10,TimeUnit.SECONDS));assertEquals(1,checkpoints.reliable(id).size());
        VettingRun interrupted=runs.findById(id).orElseThrow(AssertionError::new);String oldOwner=interrupted.getExecutionOwner();assertFalse(checkpoints.claim(id,oldOwner),"Duplicate recovery dispatch cannot reacquire an active lease");interrupted.setLeaseUntil(Instant.now().minusSeconds(1));runs.saveAndFlush(interrupted);
        VettingService restarted=new VettingService(projectService,storage,parser,props,documents,findings,runs,semantic,writer,transactions,profiles);restarted.setCheckpoints(checkpoints);restarted.setReportSnapshots(snapshots);restarted.setChecklistService(checklist);
        try{restarted.recoverInterruptedRuns();await(project,id,"COMPLETED");assertEquals(3,calls.get(),"Only the uncertain second packet is repeated; the verified first packet is restored");assertEquals(2,checkpoints.reliable(id).size());
            assertThrows(BizException.class,()->checkpoints.renew(id,oldOwner));release.countDown();Thread.sleep(100);assertEquals("COMPLETED",runs.findById(id).orElseThrow(AssertionError::new).getStatus());assertEquals(2,findings.findByProjectIdOrderByCodeAsc(project).size());
        }finally{release.countDown();restarted.stopWorker();}
    }
    @ParameterizedTest @ValueSource(strings={"source","recipe","retrieval_recipe","model","legacy_language_recipe","legacy_attempt_recipe"})
    void incompatibleInputsRequireRerunAndRetainVerifiedPackets(String changed)throws Exception{
        String project=project();AtomicInteger calls=new AtomicInteger();CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{if(calls.incrementAndGet()==2){waiting.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}return records("issue");});
        String id=start(project);assertTrue(waiting.await(10,TimeUnit.SECONDS));assertEquals(1,checkpoints.reliable(id).size());
        VettingRun saved=runs.findById(id).orElseThrow(AssertionError::new);saved.setLeaseUntil(Instant.now().minusSeconds(1));
        if("recipe".equals(changed))saved.setRecoveryContractSha256("incompatible-recipe");
        String legacyPacket=null;
        if("legacy_language_recipe".equals(changed)||"legacy_attempt_recipe".equals(changed)) {
            saved.setRecoveryContractSha256(previousRecipe("legacy_language_recipe".equals(changed)?"vetting-checkpoint-v4-bound-native-profile-minimum-256k":"vetting-checkpoint-v5-bound-native-profile-minimum-256k-language-origin-gate"));
            VettingPacketCheckpoint old=packets.findByRunIdOrderByUpdatedAtAsc(id).get(0);
            VettingCheckpointService.Saved accepted=JsonUtils.read(old.getResultJson(),VettingCheckpointService.Saved.class);
            if("legacy_language_recipe".equals(changed)){accepted.getCandidates().get(0).setType("language");accepted.getCandidates().get(0).setRuleId("semantic-language");}
            else accepted.getPacket().getAttempts().clear();
            legacyPacket=JsonUtils.write(accepted);old.setResultJson(legacyPacket);old.setResultSha256(VettingCorpus.hash(legacyPacket));packets.saveAndFlush(old);
        }
        if("model".equals(changed))saved.setModelIdentityJson(JsonUtils.write(new ModelIdentity("fixture","fixture","different fixture model",null,"test_only")));
        runs.saveAndFlush(saved);String previousRecipe=props.getVetting().getExpectedRetrievalRecipeFingerprint();
        if("retrieval_recipe".equals(changed))props.getVetting().setExpectedRetrievalRecipeFingerprint("changed-retrieval-recipe");
        if("source".equals(changed)){SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);source.setTextContent(source.getTextContent()+" changed");documents.saveAndFlush(source);}
        VettingService restarted=new VettingService(projectService,storage,parser,props,documents,findings,runs,semantic,writer,transactions,profiles);restarted.setCheckpoints(checkpoints);restarted.setReportSnapshots(snapshots);restarted.setChecklistService(checklist);
        try{restarted.recoverInterruptedRuns();JsonNode failed=await(project,id,"FAILED");assertEquals("NEEDS_RERUN",failed.path("recoveryStatus").asText());assertEquals(1,failed.path("reliablePackets").size());assertEquals(2,calls.get(),"No incompatible model call may resume");
            if(legacyPacket!=null){assertEquals(legacyPacket,packets.findByRunIdOrderByUpdatedAtAsc(id).get(0).getResultJson());assertEquals("legacy_language_recipe".equals(changed)?"language":"risk",failed.path("reliablePackets").get(0).path("candidates").get(0).path("type").asText());}}
        finally{release.countDown();restarted.stopWorker();props.getVetting().setExpectedRetrievalRecipeFingerprint(previousRecipe);}
    }
    /** Exact v4 recipe: retain its checkpoint as historical evidence, never restore it under the origin gate. */
    private String previousRecipe(String prefix){ConsenseProperties.Vetting v=props.getVetting();return VettingCorpus.hash(prefix+"\n"+JsonUtils.write(Arrays.asList(
            v.isSemanticEnabled(),v.getSemanticTopics(),v.getSemanticContextChars(),v.getSemanticContextExpansionChars(),v.getProjectReferenceComparisons(),v.getProjectReferenceContextChars(),
            v.getCandidateLimit(),v.getTopK(),v.isDiffAgainstBaseline(),v.isRequireHybridRetrieval(),v.getRetrievalUrl(),v.getExpectedRetrievalContract(),v.getExpectedRetrievalSignatureVersion(),v.getExpectedRetrievalGranularity(),
            v.getExpectedEmbeddingWindowMaxTokens(),v.getExpectedRerankWindowMaxTokens(),v.getExpectedWindowOverlapTokens(),v.getExpectedStructuralEmbeddingMaxTokens(),v.getExpectedStructuralRerankMaxTokens(),v.isExpectedStructuralRerankStrict(),
            v.getExpectedRetrievalRecipeFingerprint(),v.getExpectedRetrievalSignature(),v.isTokenBudgetObserverEnabled(),v.getTokenBudgetObserverRecipeSha256(),v.getMinimaxCnWire())));}
    @Test void corruptCheckpointCannotSupplyReliableOrFormalResults()throws Exception{
        String project=project();String id=start(project);await(project,id,"COMPLETED");VettingPacketCheckpoint corrupt=packets.findByRunIdOrderByUpdatedAtAsc(id).get(0);corrupt.setResultJson(corrupt.getResultJson()+" ");packets.saveAndFlush(corrupt);
        assertThrows(BizException.class,()->checkpoints.reliable(id));
        for(String format:Arrays.asList("json","pdf","docx")){org.springframework.mock.web.MockHttpServletResponse response=http.perform(get("/api/vetting/"+project+"/report."+format)).andReturn().getResponse();assertNull(response.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(response.getContentAsString()).path("code").asInt());}
    }
    @Test void sameSizeOriginalChangeWithPreservedTimestampRejectsAllFormatsAndANewRun()throws Exception{
        String project=project();SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);Path file=Paths.get(props.getStorageRoot(),project,"SCC.txt");Files.createDirectories(file.getParent());byte[] original=source.getTextContent().getBytes(StandardCharsets.UTF_8);Files.write(file,original);source.setStoragePath(file.toString());source.setSourceSha256(com.consense.service.sources.OerSourceMetadata.originalSha256(original));documents.saveAndFlush(source);
        String id=start(project);await(project,id,"COMPLETED");java.nio.file.attribute.FileTime before=Files.getLastModifiedTime(file);byte[] changed=original.clone();changed[0]=(byte)'X';Files.write(file,changed);Files.setLastModifiedTime(file,before);assertEquals(original.length,Files.size(file));
        for(String format:Arrays.asList("json","pdf","docx")){org.springframework.mock.web.MockHttpServletResponse response=http.perform(get("/api/vetting/"+project+"/report."+format)).andReturn().getResponse();assertNull(response.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(response.getContentAsString()).path("code").asInt());}
        assertThrows(BizException.class,()->service.startRun(project,"en"));assertEquals(2,checkpoints.reliable(id).size());
    }
    private List<VettingSemanticReview.Record> records(String assessment){VettingCorpus.Chunk source=corpus.get().stream().filter(c->"tender".equals(c.getRole())).findFirst().orElseThrow(AssertionError::new);VettingSemanticReview.Record r=new VettingSemanticReview.Record();r.setAssessment(assessment);r.setType("risk");r.setSeverity("medium");r.setTitle("Fixture assessment");r.setComment("Fixture record; no live model quality claim");VettingSemanticReview.Quote q=new VettingSemanticReview.Quote();q.setChunkId(source.getId());q.setSide("source");q.setQuote(source.getContent().trim());r.setEvidence(Collections.singletonList(q));return Collections.singletonList(r);}
    @ParameterizedTest @ValueSource(strings={"new_run","parsed_source","raw_bytes","generated_revision","missing_binding"})
    void obsoleteScreenWritesAreRejectedAtomicallyAndKeepHumanHistory(String change)throws Exception{
        String project=project();SourceDocument source=bindOriginal(project);String firstRun=start(project);await(project,firstRun,"COMPLETED");
        JsonNode loaded=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings")).andReturn().getResponse().getContentAsString()).path("data").get(0);String code=loaded.path("code").asText();
        String reviewUrl="/api/vetting/"+project+"/findings/"+code+"/review",statusUrl="/api/vetting/"+project+"/findings/"+code+"/status",checklistUrl="/api/vetting/"+project+"/runs/"+firstRun+"/checklist";
        http.perform(put(reviewUrl).contentType("application/json").content(reviewBody(loaded,"original human remark"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        http.perform(post(statusUrl).param("status","Assigned").param("expectedRunId",loaded.path("runId").asText()).param("expectedGeneratedRevisionSha256",loaded.path("generatedRevisionSha256").asText()).param("expectedSourceSnapshotSha256",loaded.path("sourceSnapshotSha256").asText())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        JsonNode checklistLoaded=JsonUtils.parse(http.perform(get(checklistUrl)).andReturn().getResponse().getContentAsString()).path("data");
        Map<String,Object> checklistBody=new LinkedHashMap<>();checklistBody.put("expectedRunId",firstRun);checklistBody.put("expectedSourceSnapshotSha256",checklistLoaded.path("sourceSnapshotSha256").asText());checklistBody.put("runSnapshotSha256",checklistLoaded.path("runSnapshotSha256").asText());checklistBody.put("items",Collections.singletonList(Map.of("id","APP-I-001","status","fulfilled","remarks","original checklist decision")));
        http.perform(put(checklistUrl).contentType("application/json").content(JsonUtils.write(checklistBody))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        if("new_run".equals(change)){String newer=start(project);assertNotEquals(firstRun,newer);await(project,newer,"COMPLETED");}
        if("parsed_source".equals(change)){source.setTextContent(source.getTextContent()+" changed scope");documents.saveAndFlush(source);}
        if("generated_revision".equals(change)){VettingFinding changed=findings.findByProjectIdAndCode(project,code).orElseThrow(AssertionError::new);changed.setBodyEn("Changed generated observation");findings.saveAndFlush(changed);}
        if("raw_bytes".equals(change)){Path file=Paths.get(source.getStoragePath());java.nio.file.attribute.FileTime time=Files.getLastModifiedTime(file);byte[] changed=Files.readAllBytes(file);changed[changed.length-1]^=1;Files.write(file,changed);Files.setLastModifiedTime(file,time);}
        String rejectedBody=reviewBody(loaded,"must not overwrite");
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder statusWrite=post(statusUrl).param("status","Handled");
        if("missing_binding".equals(change)){rejectedBody="{\"reviewRemarks\":\"must not overwrite\"}";checklistBody.remove("expectedRunId");checklistBody.remove("expectedSourceSnapshotSha256");}
        else statusWrite.param("expectedRunId",loaded.path("runId").asText()).param("expectedGeneratedRevisionSha256",loaded.path("generatedRevisionSha256").asText()).param("expectedSourceSnapshotSha256",loaded.path("sourceSnapshotSha256").asText());
        http.perform(put(reviewUrl).contentType("application/json").content(rejectedBody)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        http.perform(statusWrite).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        checklistBody.put("items",Collections.singletonList(Map.of("id","APP-I-001","status","unchecked","remarks","must not overwrite")));if("generated_revision".equals(change))checklistBody.put("expectedSourceSnapshotSha256","wrong displayed source");http.perform(put(checklistUrl).contentType("application/json").content(JsonUtils.write(checklistBody))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        VettingFinding retained=findings.findByProjectIdAndCode(project,code).orElseThrow(AssertionError::new);assertEquals("original human remark",retained.getReviewRemarks());assertEquals("reviewed",retained.getActionTaken());assertTrue(retained.getAddendumRequired());assertEquals("Assigned",retained.getStatus());
        JsonNode retainedChecklist=JsonUtils.parse(http.perform(get(checklistUrl)).andReturn().getResponse().getContentAsString()).path("data");assertEquals("fulfilled",retainedChecklist.path("items").get(0).path("status").asText());assertEquals("original checklist decision",retainedChecklist.path("items").get(0).path("remarks").asText());
    }
    @Test void originalHttpRequiresSavedRawIdentityAndRejectsSameSizeSameMtimeReplacement()throws Exception{
        String project=project();SourceDocument source=bindOriginal(project);String id=start(project);await(project,id,"COMPLETED");
        JsonNode finding=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings")).andReturn().getResponse().getContentAsString()).path("data").get(0);
        JsonNode evidence=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings/"+finding.path("code").asText()+"/evidence")).andReturn().getResponse().getContentAsString()).path("data").path("items").get(0);
        String raw=source.getSourceSha256(),revision=VettingCorpus.sourceHash(source),url="/api/vetting/"+project+"/sources/"+source.getId()+"/original";assertEquals(raw,evidence.path("originalSourceSha256").asText());
        byte[] before=Files.readAllBytes(Paths.get(source.getStoragePath()));assertArrayEquals(before,http.perform(get(url).param("sourceHash",revision).param("originalSourceSha256",raw)).andReturn().getResponse().getContentAsByteArray());
        http.perform(get(url).param("sourceHash",revision)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        http.perform(get(url).param("sourceHash",revision).param("originalSourceSha256","wrong original")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        Path file=Paths.get(source.getStoragePath());java.nio.file.attribute.FileTime time=Files.getLastModifiedTime(file);byte[] replacement=before.clone();replacement[replacement.length-1]^=1;Files.write(file,replacement);Files.setLastModifiedTime(file,time);
        org.springframework.mock.web.MockHttpServletResponse rejected=http.perform(get(url).param("sourceHash",revision).param("originalSourceSha256",raw)).andReturn().getResponse();assertEquals(4104,JsonUtils.parse(rejected.getContentAsString()).path("code").asInt());assertNull(rejected.getHeader("Content-Disposition"));assertFalse(Arrays.equals(replacement,rejected.getContentAsByteArray()));
    }
    @Test void uploadedRuleFindingReturnsItsCapturedOriginalThroughPublicHttp()throws Exception{
        String project=project();documents.deleteAll(documents.findByProjectIdOrderByIdAsc(project));when(ai.available()).thenReturn(false);
        byte[] uploaded;try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            doc.createParagraph().createRun().setText("SCC 9.1 Clause 25.2(4) of the General Conditions of Contract is deleted.");
            doc.createParagraph().createRun().setText("SCC 18.1 The contractor shall comply with GCC25.2(4) when applying for interim payment.");
            doc.write(out);uploaded=out.toByteArray();
        }
        http.perform(multipart("/api/vetting/"+project+"/package/upload").file(new org.springframework.mock.web.MockMultipartFile("files","SCC.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",uploaded)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        String run=start(project);await(project,run,"COMPLETED");
        JsonNode listed=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings")).andReturn().getResponse().getContentAsString()).path("data");assertEquals(1,listed.size());JsonNode finding=listed.get(0);assertEquals("rule",finding.path("source").asText());
        JsonNode evidence=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings/"+finding.path("code").asText()+"/evidence")).andReturn().getResponse().getContentAsString()).path("data").path("items");assertFalse(evidence.isEmpty());
        String raw=com.consense.service.sources.OerSourceMetadata.originalSha256(uploaded);SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);
        for(JsonNode item:evidence){assertEquals(String.valueOf(source.getId()),item.path("documentId").asText());assertEquals(VettingCorpus.sourceHash(source),item.path("sourceHash").asText());assertEquals(raw,item.path("originalSourceSha256").asText());}
        JsonNode item=evidence.get(0);String url="/api/vetting/"+project+"/sources/"+item.path("documentId").asText()+"/original";
        org.springframework.mock.web.MockHttpServletResponse original=http.perform(get(url).param("sourceHash",item.path("sourceHash").asText()).param("originalSourceSha256",item.path("originalSourceSha256").asText())).andReturn().getResponse();assertEquals(200,original.getStatus());assertNotNull(original.getHeader("Content-Disposition"));assertArrayEquals(uploaded,original.getContentAsByteArray());
        Path file=Paths.get(source.getStoragePath());java.nio.file.attribute.FileTime timestamp=Files.getLastModifiedTime(file);byte[] replaced=uploaded.clone();replaced[replaced.length-1]^=1;Files.write(file,replaced);Files.setLastModifiedTime(file,timestamp);
        org.springframework.mock.web.MockHttpServletResponse rejected=http.perform(get(url).param("sourceHash",item.path("sourceHash").asText()).param("originalSourceSha256",item.path("originalSourceSha256").asText())).andReturn().getResponse();assertEquals(4104,JsonUtils.parse(rejected.getContentAsString()).path("code").asInt());assertNull(rejected.getHeader("Content-Disposition"));
        verify(ai,never()).completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList());
    }
    private SourceDocument bindOriginal(String project)throws Exception{SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);Path file=Paths.get(props.getStorageRoot(),project,"SCC.txt");Files.createDirectories(file.getParent());byte[] bytes=source.getTextContent().getBytes(StandardCharsets.UTF_8);Files.write(file,bytes);source.setStoragePath(file.toString());source.setSourceSha256(com.consense.service.sources.OerSourceMetadata.originalSha256(bytes));return documents.saveAndFlush(source);}
    private static String reviewBody(JsonNode loaded,String remarks){return JsonUtils.write(Map.of("expectedRunId",loaded.path("runId").asText(),"expectedGeneratedRevisionSha256",loaded.path("generatedRevisionSha256").asText(),"expectedSourceSnapshotSha256",loaded.path("sourceSnapshotSha256").asText(),"reviewRemarks",remarks,"actionTaken","reviewed","addendumRequired",true));}
    private String project(){Project p=new Project();p.setId("recovery-"+UUID.randomUUID());p.setNameEn("Recovery fixture");p.setContractNo("TEST");projects.saveAndFlush(p);SourceDocument d=new SourceDocument();d.setProjectId(p.getId());d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("SCC");d.setFileName("SCC.txt");d.setParseStatus("PARSED");d.setTextContent("SCC 9.1 The contractor shall protect the works and maintain insurance throughout the contract.");d.setOcrUsed(false);documents.saveAndFlush(d);return p.getId();}
    private String start(String project)throws Exception{return JsonUtils.parse(http.perform(post("/api/vetting/"+project+"/runs")).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();}
    private JsonNode await(String project,String id,String status)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);JsonNode saved;do{saved=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/runs/"+id)).andReturn().getResponse().getContentAsString()).path("data");if(status.equals(saved.path("status").asText()))return saved;Thread.sleep(20);}while(System.nanoTime()<deadline);fail("Run did not reach "+status+": "+saved);return null;}
}
