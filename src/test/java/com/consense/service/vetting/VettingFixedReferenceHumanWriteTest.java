package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.vector.VectorStore;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** A fixed-only source change must invalidate all public human writes, without changing project bytes. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class VettingFixedReferenceHumanWriteTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:fixed_human_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");}
    @Autowired MockMvc http;
    @Autowired ProjectRepository projects;
    @Autowired VettingRunRepository runs;
    @Autowired VettingFindingRepository findings;
    @MockBean FixedBaselineRetrievalClient fixed;

    @Test void fixedOnlyDriftRejectsStatusReviewAndChecklistWithoutOverwritingHistory()throws Exception{
        String project="fixed-human-"+ID,runId=UUID.randomUUID().toString();
        Project p=new Project();p.setId(project);p.setNameEn("Fixed human fixture");p.setContractNo("TEST");projects.saveAndFlush(p);
        VettingRun run=new VettingRun();run.setId(runId);run.setProjectId(project);run.setStatus("COMPLETED");run.setStartedAt(Instant.now());run.setCoverageJson("{\"documents\":[]}");run.setResultJson("{}");run.setSourceSnapshotSha256(VettingCorpus.hash("competition-review-package-v1\n[]"));runs.saveAndFlush(run);
        JsonNode payload=JsonUtils.parse("{\"fixedBaselineManifestSha256\":\"fixture-manifest\",\"fixedBaselineVersion\":\"fixture-version\",\"originalSourceSha256\":\"fixture-original\",\"pageNo\":1,\"sourceQuality\":{\"family\":\"GCC\"}}");
        FixedReferenceEvidence reference=new FixedReferenceEvidence("fixed-gcc",0,payload,null,null);
        VectorStore.SearchHit hit=reference.asHit();when(fixed.readOnlyHitById("fixed-gcc")).thenReturn(hit);when(fixed.evidence(hit)).thenReturn(reference);
        FindingEvidence evidence=new FindingEvidence();evidence.setFixedBaselineEvidence(reference.pointer());
        VettingFinding finding=new VettingFinding();finding.setProjectId(project);finding.setCode("VT-001");finding.setRunId(runId);finding.setActive(true);finding.setStatus("Open");finding.setGroupKey("risk");finding.setScope("intra");finding.setSeverity("medium");finding.setTitleEn("Fixture fixed reference");finding.setCreatedAt(Instant.now());finding.setEvidenceJson(JsonUtils.write(Collections.singletonList(evidence)));findings.saveAndFlush(finding);
        JsonNode loaded=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings")).andReturn().getResponse().getContentAsString()).path("data").get(0);
        String base="/api/vetting/"+project+"/findings/VT-001",checklist="/api/vetting/"+project+"/runs/"+runId+"/checklist";
        Map<String,Object> review=new LinkedHashMap<>();review.put("expectedRunId",runId);review.put("expectedGeneratedRevisionSha256",loaded.path("generatedRevisionSha256").asText());review.put("expectedSourceSnapshotSha256",run.getSourceSnapshotSha256());review.put("reviewRemarks","original human remark");review.put("actionTaken","reviewed");review.put("addendumRequired",true);
        http.perform(put(base+"/review").contentType("application/json").content(JsonUtils.write(review))).andExpect(jsonPath("$.code").value(0));
        JsonNode initial=JsonUtils.parse(http.perform(get(checklist)).andReturn().getResponse().getContentAsString()).path("data");assertTrue(initial.path("writable").asBoolean());
        Map<String,Object> body=new LinkedHashMap<>();body.put("expectedRunId",runId);body.put("expectedSourceSnapshotSha256",run.getSourceSnapshotSha256());body.put("runSnapshotSha256",initial.path("runSnapshotSha256").asText());body.put("items",Collections.singletonList(Map.of("id","APP-I-001","status","fulfilled","remarks","original checklist decision")));
        http.perform(put(checklist).contentType("application/json").content(JsonUtils.write(body))).andExpect(jsonPath("$.code").value(0));
        when(fixed.readOnlyHitById("fixed-gcc")).thenThrow(new IllegalStateException("fixed_reference_original_sha_changed"));
        review.put("reviewRemarks","must not overwrite");http.perform(put(base+"/review").contentType("application/json").content(JsonUtils.write(review))).andExpect(jsonPath("$.code").value(4104));
        http.perform(post(base+"/status").param("status","Handled").param("expectedRunId",runId).param("expectedGeneratedRevisionSha256",loaded.path("generatedRevisionSha256").asText()).param("expectedSourceSnapshotSha256",run.getSourceSnapshotSha256())).andExpect(jsonPath("$.code").value(4104));
        body.put("items",Collections.singletonList(Map.of("id","APP-I-001","status","unchecked","remarks","must not overwrite")));http.perform(put(checklist).contentType("application/json").content(JsonUtils.write(body))).andExpect(jsonPath("$.code").value(4104));
        VettingFinding retained=findings.findByProjectIdAndCode(project,"VT-001").orElseThrow(AssertionError::new);assertEquals("Open",retained.getStatus());assertEquals("original human remark",retained.getReviewRemarks());assertEquals("reviewed",retained.getActionTaken());assertTrue(retained.getAddendumRequired());
        JsonNode preserved=JsonUtils.parse(http.perform(get(checklist)).andReturn().getResponse().getContentAsString()).path("data");assertFalse(preserved.path("writable").asBoolean());assertEquals("fulfilled",preserved.path("items").get(0).path("status").asText());assertEquals("original checklist decision",preserved.path("items").get(0).path("remarks").asText());
    }
}
