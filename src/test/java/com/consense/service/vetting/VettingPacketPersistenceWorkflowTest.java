package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingSemanticReview.*;
import com.consense.web.dto.VettingDtos.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated unique in-memory H2 with saved fixture blocks and mock review; no parser/HTTP/model. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class VettingPacketPersistenceWorkflowTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:packet_persistence_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get("target/packet-persistence-uploads",ID).toAbsolutePath().toString());}
    @Autowired VettingService service;@Autowired ConsenseProperties props;@Autowired ProjectRepository projects;@Autowired SourceDocumentRepository documents;@Autowired VettingFindingRepository findings;@MockBean VettingSemanticReview semantic;
    @BeforeEach void settings(){props.getVetting().setMaxRiskFindings(20);props.getVetting().setServiceProbeDirectory(null);props.getVetting().setProbeDirectory(null);props.getVetting().setExportProbeDirectory(null);}
    private SourceDocument source(){Project p=new Project();p.setId("packet-"+UUID.randomUUID());p.setNameEn("Packet persistence fixture");p.setContractNo("TEST");projects.saveAndFlush(p);DocumentBlock block=new DocumentBlock();block.setId("fixture-block");block.setKind("paragraph");block.setLocation("body/fixture");block.setSource("fixture_saved_text");block.setText("The original register shall be retained.");SourceDocument s=new SourceDocument();s.setProjectId(p.getId());s.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);s.setReviewRole("tender");s.setFileKey("OTHER");s.setFileName("source-fixture.txt");s.setParseStatus("PARSED");s.setTextContent(block.getText());s.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));return documents.saveAndFlush(s);}
    private Candidate candidate(SourceDocument s,String variant){String sha=VettingCorpus.hash("synthetic-bound-source-snapshot:"+variant);String packet="topic-1-packet-1-"+sha;Candidate c=new Candidate();c.setRuleId("semantic-risk");c.setSource("model");c.setType("risk");c.setSeverity("high");c.setTitle("Same synthetic finding");c.setComment("Same source quotation, distinct source input scope.");c.setImpact("Manual review");c.setSuggestion("Check original");c.setPacketId(packet);c.setSourceSnapshotSha256(sha);FindingEvidence e=new FindingEvidence("source",String.valueOf(s.getId()),s.getFileKey(),s.getFileName(),null,"body/fixture",s.getTextContent(),true,VettingCorpus.sourceHash(s),null);e.setPacketId(packet);e.setPacketSourceSnapshotSha256(sha);c.setEvidence(Collections.singletonList(e));return c;}
    private Result result(Candidate... cs){Result r=new Result();r.setModel("synthetic-review-fixture");r.getCandidates().addAll(Arrays.asList(cs));SemanticTopicVO a=new SemanticTopicVO();a.setTopicIndex(1);a.setStatus("completed");a.setGlobalCallStatus("completed");a.setAggregateReviewStatus("partial");a.setSourceRequestCount(2);a.setPendingSourceRequestCount(1);r.getTopicAudits().add(a);return r;}
    @Test void exactPacketMetadataSurvivesDatabaseListJsonSourceDialogAndCoverage() throws Exception {
        SourceDocument s=source();Candidate a=candidate(s,"A"),b=candidate(s,"B");when(semantic.reviewWithProgress(eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class))).thenReturn(result(a,b));assertEquals(2,service.run(s.getProjectId(),"en").getTotal());List<FindingVO> vos=service.listFindings(s.getProjectId(),null,null,null,null,null);assertEquals(2,vos.size());assertNotEquals(vos.get(0).getFingerprint(),vos.get(1).getFingerprint());
        for(FindingVO vo:vos){FindingEvidence e=vo.getEvidence().get(0);assertNotNull(e.getPacketId());assertTrue(e.getPacketId().endsWith(e.getPacketSourceSnapshotSha256()));assertEquals(e.getPacketId(),service.evidence(s.getProjectId(),vo.getCode()).getItems().get(0).getPacketId());assertEquals(e.getPacketSourceSnapshotSha256(),service.evidence(s.getProjectId(),vo.getCode()).getItems().get(0).getPacketSourceSnapshotSha256());VettingFinding row=findings.findByProjectIdAndCode(s.getProjectId(),vo.getCode()).get();assertTrue(row.getEvidenceJson().contains(e.getPacketId()));}
        String json=new String(service.exportJson(s.getProjectId(),"en"),StandardCharsets.UTF_8);assertTrue(json.contains(a.getPacketId()));assertTrue(json.contains(b.getPacketId()));assertTrue(json.contains("aggregateReviewStatus"));assertEquals("partial",service.latestRun(s.getProjectId()).getCoverage().getSemanticTopics().get(0).getAggregateReviewStatus());
    }
    @Test void changedPacketSourceScopeNeverInheritsPreviousHumanDecisionAndExactScopeDoes() {
        SourceDocument s=source();AtomicReference<String> variant=new AtomicReference<>("A");when(semantic.reviewWithProgress(eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class))).thenAnswer(call->result(candidate(s,variant.get())));service.run(s.getProjectId(),"en");FindingVO old=service.listFindings(s.getProjectId(),null,null,null,null,null).get(0);service.updateStatus(s.getProjectId(),old.getCode(),"Handled");FindingVO manual=service.updateReview(s.getProjectId(),old.getCode(),new ReviewUpdateRequest("Review applies to packet A only","No amendment",false));service.run(s.getProjectId(),"en");FindingVO same=service.listFindings(s.getProjectId(),null,null,null,null,null).get(0);assertEquals(old.getCode(),same.getCode());assertEquals("Handled",same.getStatus());assertEquals(manual.getReviewRemarks(),same.getReviewRemarks());
        variant.set("B");service.run(s.getProjectId(),"en");FindingVO changed=service.listFindings(s.getProjectId(),null,null,null,null,null).get(0);assertNotEquals(old.getFingerprint(),changed.getFingerprint());assertEquals("Open",changed.getStatus());assertNull(changed.getReviewRemarks());assertNull(changed.getActionTaken());VettingFinding retained=findings.findByProjectIdAndCode(s.getProjectId(),old.getCode()).get();assertFalse(retained.getActive());assertEquals("Handled",retained.getStatus());assertEquals(manual.getReviewRemarks(),retained.getReviewRemarks());
    }
    @Test void legacyAndRuleFingerprintsAndEvidenceJsonRemainByteCompatibleButPartialScopeIsRejected() {
        SourceDocument s=source();Candidate c=candidate(s,"A");FindingEvidence e=c.getEvidence().get(0);c.setPacketId(null);c.setSourceSnapshotSha256(null);e.setPacketId(null);e.setPacketSourceSnapshotSha256(null);String legacyJson=JsonUtils.write(e);assertFalse(legacyJson.contains("packetId"));assertFalse(legacyJson.contains("packetSourceSnapshotSha256"));String side=s.getFileName().toLowerCase(Locale.ROOT)+"|"+VettingCorpus.sourceHash(s)+"|body/fixture|"+VettingCorpus.normalize(s.getTextContent());String expected=VettingCorpus.hash(c.getRuleId()+"\n"+side);assertEquals(expected,VettingService.fingerprint(c));assertEquals(legacyJson,JsonUtils.write(JsonUtils.read(legacyJson,FindingEvidence.class)));c.setSource("rule");c.setPacketId("ignored-rule-metadata");assertEquals(expected,VettingService.fingerprint(c));c.setSource("model");assertThrows(IllegalArgumentException.class,()->VettingService.fingerprint(c));
    }
}
