package com.consense.web;

import com.consense.domain.Project;
import com.consense.domain.VettingRun;
import com.consense.common.JsonUtils;
import com.consense.repository.ProjectRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.seed.DemoDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Paths;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual REST plus isolated H2 persistence, without seed data or model/parser calls. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class VettingChecklistWorkflowTest {
    private static final String ISOLATION=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:checklist_"+ISOLATION+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target/checklist-uploads",ISOLATION).toAbsolutePath().toString());
    }
    @MockBean DemoDataInitializer seed;
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired VettingRunRepository runs;

    @Test void completeReviewWithNoFindingsLeavesEveryChecklistRequirementUnchecked() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        mvc.perform(get(url(run)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value(run.getId()))
                .andExpect(jsonPath("$.data.writable").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value("APP-I-001"))
                .andExpect(jsonPath("$.data.items[0].status").value("unchecked"))
                .andExpect(jsonPath("$.data.items[0].remarks").value(""));
    }

    @Test void explicitHumanCountercheckPersistsOnlyForItsExactCompletedRun() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        JsonNode initial=read(run);
        String remarks="  Reviewed by the Contract Team.\r\nNot an issued addendum.  ";
        Map<String,Object> update=new LinkedHashMap<>();update.put("runSnapshotSha256",initial.path("runSnapshotSha256").asText());update.put("expectedRunId",run.getId());update.put("expectedSourceSnapshotSha256",initial.path("sourceSnapshotSha256").asText());
        Map<String,Object> item=new LinkedHashMap<>();item.put("id","APP-I-001");item.put("status","fulfilled");item.put("remarks",remarks);
        update.put("items",Arrays.asList(item));
        mvc.perform(put(url(run)).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(update)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].status").value("fulfilled"))
                .andExpect(jsonPath("$.data.items[0].remarks").value(remarks));
        JsonNode saved=read(run);
        assertEquals("fulfilled",saved.path("items").get(0).path("status").asText());
        assertEquals(remarks,saved.path("items").get(0).path("remarks").asText());
        assertFalse(saved.path("items").get(0).path("updatedAt").isNull());
        assertEquals("unchecked",saved.path("items").get(1).path("status").asText());
        VettingRun next=newRun(run.getProjectId(),"{\"total\":0}");
        assertEquals("unchecked",read(next).path("items").get(0).path("status").asText());
        assertNotEquals(saved.path("runSnapshotSha256").asText(),read(next).path("runSnapshotSha256").asText());
    }

    @Test void staleSnapshotAndUnknownRowsCannotPartiallyReplaceHumanCounterchecks() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String binding=read(run).path("runSnapshotSha256").asText();
        save(run,binding,Collections.singletonList(row("APP-I-001","fulfilled","Original human decision")))
                .andExpect(jsonPath("$.code").value(0));
        save(run,String.join("",Collections.nCopies(64,"0")),Collections.singletonList(row("APP-I-001","unchecked","Must not overwrite")))
                .andExpect(jsonPath("$.code").value(4123));
        assertEquals("fulfilled",read(run).path("items").get(0).path("status").asText());
        save(run,binding,Arrays.asList(row("APP-I-001","unchecked","Must not partially save"),row("APP-I-999","fulfilled","Unknown requirement")))
                .andExpect(jsonPath("$.code").value(4121));
        assertEquals("Original human decision",read(run).path("items").get(0).path("remarks").asText());
        save(run,binding,Arrays.asList(row("APP-I-001","unchecked","First"),row("APP-I-001","not_applicable","Contradictory duplicate")))
                .andExpect(jsonPath("$.code").value(4121));
        assertEquals("fulfilled",read(run).path("items").get(0).path("status").asText());
    }

    @Test void unfinishedRunsAndOtherProjectsCannotAcceptCounterchecks() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        VettingRun other=completedRun("{\"total\":0}");
        mvc.perform(get("/api/vetting/"+other.getProjectId()+"/runs/"+run.getId()+"/checklist"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(4102));
        run.setStatus("RUNNING");runs.saveAndFlush(run);
        assertFalse(read(run).path("writable").asBoolean());
        save(run,read(run).path("runSnapshotSha256").asText(),Collections.singletonList(row("APP-I-001","fulfilled","Premature")))
                .andExpect(jsonPath("$.code").value(4104));
        assertEquals("unchecked",read(run).path("items").get(0).path("status").asText());
    }

    @Test void rowUpdatesKeepOtherDecisionsAndSupportManualNotApplicableAndReset() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String binding=read(run).path("runSnapshotSha256").asText();
        save(run,binding,Collections.singletonList(row("APP-I-001","fulfilled","A"))).andExpect(jsonPath("$.code").value(0));
        save(run,binding,Collections.singletonList(row("APP-I-002","not_applicable","Human reviewed scope"))).andExpect(jsonPath("$.code").value(0));
        assertEquals("fulfilled",read(run).path("items").get(0).path("status").asText());
        assertEquals("not_applicable",read(run).path("items").get(1).path("status").asText());
        save(run,binding,Collections.singletonList(row("APP-I-001","unchecked","Recheck requested"))).andExpect(jsonPath("$.code").value(0));
        assertEquals("unchecked",read(run).path("items").get(0).path("status").asText());
        assertEquals("not_applicable",read(run).path("items").get(1).path("status").asText());
    }

    @Test void invalidStatesAndOversizedRemarksLeavePreviouslySavedRowsIntact() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String binding=read(run).path("runSnapshotSha256").asText();
        save(run,binding,Collections.singletonList(row("APP-I-001","fulfilled","Existing decision"))).andExpect(jsonPath("$.code").value(0));
        save(run,binding,Collections.singletonList(row("APP-I-001","Handled","Not a checklist state"))).andExpect(jsonPath("$.code").value(4001));
        save(run,binding,Collections.singletonList(row("APP-I-001","unchecked",String.join("",Collections.nCopies(4001,"x")))))
                .andExpect(jsonPath("$.code").value(4001));
        assertEquals("fulfilled",read(run).path("items").get(0).path("status").asText());
        assertEquals("Existing decision",read(run).path("items").get(0).path("remarks").asText());
    }

    @Test void downloadedReportsContainTheSameExplicitHumanChecklistDecisions() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        String binding=read(run).path("runSnapshotSha256").asText();
        String checkedRemarks="Reviewed applicable scope with the Contract Team.";
        String excludedRemarks="Not applicable to this selected contract after manual countercheck.";
        save(run,binding,Arrays.asList(row("APP-I-001","fulfilled",checkedRemarks),row("APP-I-002","not_applicable",excludedRemarks)))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode report=JsonUtils.mapper().readTree(download(run,"json"));
        assertEquals(run.getId(),report.path("job").path("id").asText());
        assertEquals("fulfilled",report.path("checklistResults").get(0).path("status").asText());
        assertEquals(checkedRemarks,report.path("checklistResults").get(0).path("remarks").asText());
        assertEquals("not_applicable",report.path("checklistResults").get(1).path("status").asText());
        assertEquals(excludedRemarks,report.path("checklistResults").get(1).path("remarks").asText());
        assertEquals("unchecked",report.path("checklistResults").get(2).path("status").asText());
        String tableJson=report.path("checklistTables").toString();
        assertTrue(tableJson.contains("Checked and fulfilled"));
        assertTrue(tableJson.contains("Not applicable"));
        assertTrue(tableJson.contains("Not checked - human confirmation required"));
        assertTrue(tableJson.contains(checkedRemarks));assertTrue(tableJson.contains(excludedRemarks));
        try(XWPFDocument docx=new XWPFDocument(new ByteArrayInputStream(download(run,"docx")))) {
            String tableText=docx.getTables().stream().flatMap(t->t.getRows().stream()).flatMap(r->r.getTableCells().stream())
                    .map(c->c.getText()).reduce("",(a,b)->a+"\n"+b);
            assertTrue(tableText.contains(checkedRemarks));assertTrue(tableText.contains(excludedRemarks));
            assertTrue(tableText.contains("Checked and fulfilled"));
            assertTrue(tableText.contains("Not checked - human confirmation required"));
        }
        try(PDDocument pdf=PDDocument.load(download(run,"pdf"))) {
            String text=new PDFTextStripper().getText(pdf).replaceAll("\\s+"," ");
            assertTrue(text.contains(checkedRemarks));assertTrue(text.contains(excludedRemarks));
            assertTrue(text.contains("Checked and fulfilled"));
            assertTrue(text.contains("Not checked - human confirmation required"));
        }
    }

    @Test void samplePricingCounterchecksRemainVisibleWithoutExpandingCompetitionScope() throws Exception {
        VettingRun run=completedRun("{\"total\":0}");
        JsonNode checklist=read(run).path("items");
        assertEquals(69,checklist.size());
        assertEquals("pricing_document_excluded",checklist.get(57).path("reviewScope").asText());
        assertTrue(checklist.get(57).path("scopeNote").asText().contains("outside the competition scope"));
        assertEquals(checklist.get(57).path("scopeNote").asText(),checklist.get(57).path("scopeNoteText").path("en").asText());
        assertFalse(checklist.get(57).path("scopeNoteText").path("zhHans").asText().isEmpty());
        assertFalse(checklist.get(57).path("scopeNoteText").path("zhHant").asText().isEmpty());
        assertEquals("unchecked",checklist.get(57).path("status").asText());
        assertEquals("mixed_contract_pricing",checklist.get(54).path("reviewScope").asText());
        assertEquals("in_scope",checklist.get(0).path("reviewScope").asText());
        JsonNode report=JsonUtils.mapper().readTree(download(run,"json"));
        assertTrue(report.path("checklistTables").toString().contains("Outside scope: pricing-document countercheck."));
        assertTrue(report.path("checklistTables").toString().contains("PRE / contract wording only. Pricing excluded."));
    }

    private byte[] download(VettingRun run,String format) throws Exception {
        return mvc.perform(get("/api/vetting/"+run.getProjectId()+"/report."+format).param("lang","zh-CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private org.springframework.test.web.servlet.ResultActions save(VettingRun run,String binding,java.util.List<Map<String,Object>> items) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>();body.put("runSnapshotSha256",binding);body.put("items",items);body.put("expectedRunId",run.getId());body.put("expectedSourceSnapshotSha256",run.getSourceSnapshotSha256());
        return mvc.perform(put(url(run)).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body))).andExpect(status().isOk());
    }
    private Map<String,Object> row(String id,String state,String remarks) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",id);row.put("status",state);row.put("remarks",remarks);return row;
    }

    private JsonNode read(VettingRun run) throws Exception {
        String response=mvc.perform(get(url(run))).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonUtils.mapper().readTree(response).path("data");
    }

    private VettingRun completedRun(String result) {
        Project p=new Project();p.setId("checklist-"+UUID.randomUUID());p.setNameEn("Checklist workflow fixture");p.setContractNo("TEST");
        projects.saveAndFlush(p);
        return newRun(p.getId(),result);
    }
    private VettingRun newRun(String projectId,String result) {
        VettingRun run=new VettingRun();run.setId(UUID.randomUUID().toString());run.setProjectId(projectId);
        run.setStatus("COMPLETED");run.setStartedAt(Instant.now());run.setFinishedAt(Instant.now());
        run.setCoverageJson("{\"documents\":[]}");run.setSourceSnapshotSha256(com.consense.service.vetting.VettingCorpus.hash("competition-review-package-v1\n[]"));run.setResultJson(result);runs.saveAndFlush(run);return run;
    }
    private String url(VettingRun run) {return "/api/vetting/"+run.getProjectId()+"/runs/"+run.getId()+"/checklist";}
}
