package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingSemanticReview.*;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual H2 persistence and exports with fixture candidates; no model, OCR or network. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class VettingGeneratedRevisionWorkflowTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:generated_revision_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","generated-revision-uploads",ID).toAbsolutePath().toString());
    }
    @Autowired VettingService service;
    @Autowired ConsenseProperties props;
    @Autowired DocumentParser parser;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @Autowired VettingFindingRepository findings;
    @MockBean VettingSemanticReview semantic;
    @BeforeEach void resetFixture() {props.getVetting().setServiceProbeDirectory(null);props.getVetting().setProbeDirectory(null);props.getVetting().setExportProbeDirectory(null);props.getVetting().setMaxRiskFindings(20);}

    @ParameterizedTest
    @ValueSource(strings={"comment","title","impact","suggestion","severity","type"})
    void changedGeneratedObservationDoesNotInheritAnEarlierHumanDecision(String changedField) throws Exception {
        SourceDocument doc=source();String project=doc.getProjectId();AtomicReference<String> variant=new AtomicReference<>("");
        when(semantic.reviewWithProgress(eq(project),anyList(),eq("en"),any(ReviewProgress.class)))
                .thenAnswer(call->result(doc,variant.get()));
        FindingVO first=run(project);service.updateStatus(project,first.getCode(),"Handled");
        FindingVO reviewed=service.updateReview(project,first.getCode(),new ReviewUpdateRequest(" old manual \r\n原文😀 "," old action ",false));
        Instant reviewedAt=reviewed.getReviewUpdatedAt();String originalRun=first.getRunId();
        variant.set(changedField);FindingVO changed=run(project);
        assertEquals(first.getFingerprint(),changed.getFingerprint(),"This regression has identical rule and evidence identity");
        assertNotEquals(first.getCode(),changed.getCode(),"A new generated observation must require its own human review");
        assertEquals("Open",changed.getStatus());assertNull(changed.getReviewRemarks());assertNull(changed.getActionTaken());
        assertNull(changed.getAddendumRequired());assertNull(changed.getReviewUpdatedAt());
        VettingFinding old=findings.findByProjectIdAndCode(project,first.getCode()).orElseThrow(AssertionError::new);
        assertFalse(old.getActive());assertEquals("Handled",old.getStatus());assertEquals(originalRun,old.getRunId());
        assertEquals(reviewed.getReviewRemarks(),old.getReviewRemarks());assertEquals(reviewed.getActionTaken(),old.getActionTaken());
        assertEquals(Boolean.FALSE,old.getAddendumRequired());assertEquals(reviewedAt,old.getReviewUpdatedAt());
        assertEquals(first.getBody().getEn(),old.getBodyEn());
        JsonNode report=JsonUtils.parse(new String(service.exportJson(project,"en"),StandardCharsets.UTF_8));
        assertEquals(1,report.path("findings").size());assertEquals(changed.getCode(),report.path("findings").get(0).path("code").asText());
        assertTrue(report.path("findings").get(0).path("reviewRemarks").isNull());
        service.updateStatus(project,changed.getCode(),"Assigned");
        FindingVO newManual=service.updateReview(project,changed.getCode(),new ReviewUpdateRequest(" new review "," new action ",true));
        FindingVO repeated=run(project);
        assertEquals(changed.getCode(),repeated.getCode(),"An identical regenerated observation must reuse its own revision");
        assertEquals("Assigned",repeated.getStatus());assertEquals(newManual.getReviewRemarks(),repeated.getReviewRemarks());
        assertEquals(newManual.getReviewUpdatedAt(),repeated.getReviewUpdatedAt());assertEquals(Boolean.TRUE,repeated.getAddendumRequired());
        assertEquals(2,findings.findByProjectIdOrderByCodeAsc(project).size());
    }

    @Test void returningToAnExactEarlierRevisionPreservesThatRevisionsHistory() throws Exception {
        SourceDocument doc=source();String project=doc.getProjectId();AtomicReference<String> variant=new AtomicReference<>("");
        when(semantic.reviewWithProgress(eq(project),anyList(),eq("en"),any(ReviewProgress.class))).thenAnswer(call->result(doc,variant.get()));
        FindingVO original=run(project);service.updateStatus(project,original.getCode(),"Handled");
        FindingVO manual=service.updateReview(project,original.getCode(),new ReviewUpdateRequest(" Original version only ",null,false));
        variant.set("comment");FindingVO second=run(project);assertNotEquals(original.getCode(),second.getCode());
        variant.set("");FindingVO returned=run(project);
        assertEquals(original.getCode(),returned.getCode());assertEquals("Handled",returned.getStatus());
        assertEquals(manual.getReviewRemarks(),returned.getReviewRemarks());assertEquals(manual.getReviewUpdatedAt(),returned.getReviewUpdatedAt());
        assertEquals(2,findings.findByProjectIdOrderByCodeAsc(project).size());
    }

    @Test void incompleteLegacyGeneratedContentDoesNotCertifyANewObservation() throws Exception {
        SourceDocument doc=source();String project=doc.getProjectId();
        when(semantic.reviewWithProgress(eq(project),anyList(),eq("en"),any(ReviewProgress.class))).thenAnswer(call->result(doc,""));
        FindingVO original=run(project);service.updateStatus(project,original.getCode(),"Handled");
        service.updateReview(project,original.getCode(),new ReviewUpdateRequest(" Legacy manual ",null,null));
        VettingFinding legacy=findings.findByProjectIdAndCode(project,original.getCode()).orElseThrow(AssertionError::new);
        legacy.setBodyEn(null);findings.saveAndFlush(legacy);
        FindingVO current=run(project);assertNotEquals(original.getCode(),current.getCode());assertEquals("Open",current.getStatus());
        assertNull(current.getReviewRemarks());assertNull(current.getAddendumRequired());
        VettingFinding retained=findings.findByProjectIdAndCode(project,original.getCode()).orElseThrow(AssertionError::new);
        assertEquals(" Legacy manual ",retained.getReviewRemarks());assertFalse(retained.getActive());assertNull(retained.getBodyEn());
    }

    private FindingVO run(String project) {assertEquals(1,service.run(project,"en").getTotal());return service.listFindings(project,null,null,null,null,null).get(0);}
    private SourceDocument source() throws Exception {
        Project p=new Project();p.setId("revision-"+UUID.randomUUID());p.setNameEn("Generated revision fixture");p.setContractNo("TEST");projects.saveAndFlush(p);
        String text="NTT 1.1 The fixture register shall be retained.";DocumentParser.ParsedDocument parsed=parser.parse("NTT.txt",text.getBytes(StandardCharsets.UTF_8));
        SourceDocument doc=new SourceDocument();doc.setProjectId(p.getId());doc.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);doc.setFileKey("NTT");doc.setFileName("NTT.txt");
        doc.setTextContent(parsed.getText());doc.setParseStatus(parsed.getParseStatus());doc.setPageCount(parsed.getPageCount());doc.setOcrUsed(false);
        doc.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));doc.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));return documents.saveAndFlush(doc);
    }
    private static Result result(SourceDocument doc,String changedField) {
        Candidate c=new Candidate();c.setRuleId("fixture-stable-evidence");c.setType("risk");c.setSeverity("high");c.setTitle("Fixture original title");
        c.setComment("Fixture original observation");c.setImpact("Fixture original impact");c.setSuggestion("Fixture original suggestion");c.setSource("model");
        c.setEvidence(Collections.singletonList(new FindingEvidence("source",String.valueOf(doc.getId()),doc.getFileKey(),doc.getFileName(),null,"body fixture",doc.getTextContent(),true,VettingCorpus.sourceHash(doc),null)));
        switch(changedField) {
            case "comment":c.setComment("Fixture changed observation");break;
            case "title":c.setTitle("Fixture changed title");break;
            case "impact":c.setImpact("Fixture changed impact");break;
            case "suggestion":c.setSuggestion("Fixture changed suggestion");break;
            case "severity":c.setSeverity("low");break;
            case "type":c.setType("language");break;
            default:break;
        }
        Result result=new Result();result.setModel("fixture-only");result.getCandidates().add(c);return result;
    }
}
