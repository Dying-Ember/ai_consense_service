package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.domain.Project;
import com.consense.repository.ProjectRepository;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public HTTP generation, persistence and original-package exports; no model/layout doubles. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingArtifactIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final String[] NAMES={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
    private static final String[] KEYS={"NTT","SCT","SCC"};
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:draft_artifact_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","drafting-artifact-uploads",DB).toAbsolutePath().toString());
        r.add("consense.drafting.renderer-executable",()->System.getProperty("consense.acceptance.rendererExecutable",""));
        r.add("consense.drafting.renderer-work-root",()->Paths.get(System.getProperty("consense.acceptance.pdfWorkRoot","target/drafting-pdf-profiles/"+DB)).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc; @Autowired ProjectRepository projects; @Autowired com.consense.config.ConsenseProperties properties; @Autowired org.springframework.jdbc.core.JdbcTemplate fixtureDatabase; @MockBean AiGateway ai;
    String project() {Project p=new Project();p.setId("artifact-"+UUID.randomUUID());p.setNameEn("Mechanical acceptance");p.setContractNo("UNADOPTED-PROJECT-METADATA");projects.save(p);return p.getId();}
    byte[] original(int i)throws IOException {String path=System.getProperty("consense.acceptance.sourceDir");assumeTrue(path!=null,"Actual standards required");return Files.readAllBytes(Paths.get(path,NAMES[i]));}
    void upload(String id)throws Exception {for(int i=0;i<3;i++)mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,KEYS[i]).file(new MockMultipartFile("file",NAMES[i],"application/vnd.openxmlformats-officedocument.wordprocessingml.document",original(i)))).andExpect(jsonPath("$.data.parsed").value(1));}
    void value(String id,String key,String value)throws Exception {mvc.perform(put("/api/drafting/{id}/variables/{key}",id,key).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(Collections.singletonMap("value",value)))).andExpect(jsonPath("$.code").value(0));}
    @SuppressWarnings("unchecked") List<Map<String,Object>> generate(String id)throws Exception {return (List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data");}
    byte[] word(String id,String key)throws Exception {
        byte[] bytes=mvc.perform(get("/api/drafting/{id}/documents/{key}/export.docx",id,key)).andExpect(content().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).andReturn().getResponse().getContentAsByteArray();
        String evidence=System.getProperty("consense.acceptance.artifactEvidence");if(evidence!=null){Path out=Paths.get(evidence);Files.createDirectories(out);Path artifact=out.resolve(key+"-"+DraftPdfConverter.sha256(bytes)+".docx");if(!Files.exists(artifact))Files.write(artifact,bytes,StandardOpenOption.CREATE_NEW);}return bytes;
    }
    static Map<String,byte[]> parts(byte[] docx)throws IOException {Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(docx))){ZipEntry e;byte[] buffer=new byte[8192];while((e=in.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);result.put(e.getName(),out.toByteArray());}}return result;}
    static void opaquePartsIdentical(byte[] original,byte[] emitted)throws IOException {Map<String,byte[]> a=parts(original),b=parts(emitted);assertEquals(a.keySet(),b.keySet(),"Complete original package topology");for(String name:a.keySet())if(!"word/document.xml".equals(name))assertArrayEquals(a.get(name),b.get(name),name);}
    @Test void generatedRevisionUsesOriginalPackageAndKeepsContractIdentityAsMetadata()throws Exception {
        String id=project();upload(id);value(id,"contractTitle","{\"number\":\"ADOPTED-TEST-123\",\"title\":\"Mechanical test title\"}");
        List<Map<String,Object>> docs=generate(id);assertEquals(3,docs.size());assertEquals(1,docs.stream().map(d->d.get("snapshotId")).distinct().count());
        for(int i=0;i<3;i++) {
            byte[] bytes=word(id,KEYS[i]);opaquePartsIdentical(original(i),bytes);
            Map<String,Object> doc=docs.get(i);assertNotNull(doc.get("revisionId"));assertEquals(DraftPdfConverter.sha256(bytes),doc.get("docxSha256"));
            assertEquals(DraftPdfConverter.sha256(original(i)),doc.get("sourceSha256"));
            String body=String.valueOf(doc.get("content"));assertFalse(body.contains("Contract No.: ADOPTED-TEST-123"));assertFalse(body.contains("Mechanical test title"));
            assertFalse(body.contains("[Guidance Note:"));
        }
    }
    @Test void adoptedValuesFillTheirActualRunsAndPreserveUnrelatedSourceFormatting()throws Exception {
        String id=project();upload(id);value(id,"foundationIncluded","true");value(id,"periodAtLeast39Months","true");value(id,"twoEnvelopeTendering","true");
        value(id,"photocopyRateUpToA3","0");value(id,"photocopyRateAboveA3","2");
        value(id,"projectArchitectPost","Architect");value(id,"projectArchitectSalutation","Ms");value(id,"projectArchitectName","Mechanical Test Person");value(id,"projectArchitectPhone","12345678");
        List<Map<String,Object>> docs=generate(id);
        com.consense.document.DocxTemplateEditor editor=new com.consense.document.DocxTemplateEditor();
        byte[] ntt=word(id,"NTT"),sct=word(id,"SCT");
        assertTrue(editor.inspect(ntt).mainParagraph(484).getCatalogText().contains("Appendix G1a"));
        assertFalse(editor.inspect(ntt).mainParagraph(484).getCatalogText().contains("*G1/*G1a"));
        assertTrue(editor.inspect(sct).getMainParagraphs().stream().anyMatch(p->p.getCatalogText().contains("rate of $0 per page")&&p.getCatalogText().contains("rate of $2 per page")));
        assertTrue(editor.inspect(sct).getMainParagraphs().stream().anyMatch(p->p.getCatalogText().contains("Architect (Ms Mechanical Test Person), telephone 12345678.")));
        opaquePartsIdentical(original(0),ntt);opaquePartsIdentical(original(1),sct);
        assertTrue(NativeDocxComparison.retainsParagraph(original(0),546,ntt),"Unrelated paragraph content, runs and formatting stay exact except complete, unique, newly added binding pairs");
    }
    private static final String SITE_APPLICATION="by written application to the Housing Department officer referred to in Condition SCT7(1) above at least seven days in advance.";
    private static final String DISTINCT_SITE_RESTRICTION="TEST ONLY: Visitors must wear safety helmets and be escorted by the site officer.";
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> siteVisitRestrictionCases() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("exact-standard-only",SITE_APPLICATION,""),
                org.junit.jupiter.params.provider.Arguments.of("standard-and-distinct",SITE_APPLICATION+" "+DISTINCT_SITE_RESTRICTION,DISTINCT_SITE_RESTRICTION),
                org.junit.jupiter.params.provider.Arguments.of("different-seven-day-condition",SITE_APPLICATION.replace("seven days","seven working days"),SITE_APPLICATION.replace("seven days","seven working days")));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("siteVisitRestrictionCases")
    @SuppressWarnings("unchecked")
    void sctInspectionDoesNotRepeatRetainedApplicationSentence(String scenario,String restriction,String expectedAdditional)throws Exception {
        String id=project();upload(id);value(id,"siteInspectionStartDate","2025-02-17");value(id,"siteInspectionEndDate","2025-02-21");
        List<Map<String,Object>> adopted=Collections.singletonList(DraftBusinessRules.map("text",restriction));String rawInput=JsonUtils.write(adopted);value(id,"siteVisitRestrictions",rawInput);
        String beforePlan=mvc.perform(get("/api/drafting/{id}/plan",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        Map<String,Object> sct=generate(id).get(1);byte[] source=original(1),emitted=word(id,"SCT");
        String variables=mvc.perform(get("/api/drafting/{id}/variables",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String afterPlan=mvc.perform(get("/api/drafting/{id}/plan",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        com.consense.document.DocxTemplateEditor editor=new com.consense.document.DocxTemplateEditor();String body=editor.inspect(emitted).getMainParagraphs().stream().map(p->p.getCatalogText()).collect(java.util.stream.Collectors.joining("\n"));
        String evidence=System.getProperty("sctSiteVisit.evidence");if(evidence!=null){Path out=Paths.get(evidence,scenario);Files.createDirectories(out);Files.write(out.resolve("SCT-source.docx"),source);Files.write(out.resolve("SCT-result.docx"),emitted);Files.writeString(out.resolve("variables-after-generation.json"),variables);Files.writeString(out.resolve("plan-before-generation.json"),beforePlan);Files.writeString(out.resolve("plan-after-generation.json"),afterPlan);Files.writeString(out.resolve("generated-sct.json"),JsonUtils.write(sct));Files.writeString(out.resolve("SCT-text.txt"),body);}
        assertEquals(1,java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(SITE_APPLICATION)).matcher(body).results().count(),"Retained exact seven-days application sentence appears once: "+scenario);
        if(!expectedAdditional.isEmpty())assertEquals(1,java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(expectedAdditional)).matcher(body).results().count(),"Different actual restriction remains exact: "+scenario);
        assertTrue(body.contains("2025-02-17 and 2025-02-21"));opaquePartsIdentical(source,emitted);
        Map<String,Object> stored=((List<Map<String,Object>>)JsonUtils.readMap(variables).get("data")).stream().filter(v->"siteVisitRestrictions".equals(v.get("key"))).findFirst().orElseThrow();assertEquals(rawInput,stored.get("value"),"Generation does not rewrite the original adopted input");
        for(String plan:Arrays.asList(beforePlan,afterPlan)){Map<String,Object> data=DraftBusinessRules.asMap(JsonUtils.readMap(plan).get("data"));Map<String,Object> action=DraftBusinessRules.list(data.get("actions")).stream().map(DraftBusinessRules::asMap).filter(a->"sct-site-inspection".equals(a.get("id"))).findFirst().orElseThrow();assertEquals("amend",action.get("decisionAction"));assertEquals(JsonUtils.write(adopted),JsonUtils.write(DraftBusinessRules.asMap(action.get("value")).get("restrictions")),"The business amendment plan keeps all adopted restriction text");}
        assertEquals(DraftPdfConverter.sha256(emitted),sct.get("docxSha256"));assertEquals(DraftPdfConverter.sha256(source),sct.get("sourceSha256"));assertFalse(JsonUtils.write(sct.get("unresolved")).contains("sct-site-inspection"));
    }
    @Test void wholeAdoptedClausesReplaceCompleteSourceScopesAndKeepTheirNeighbors()throws Exception {
        String id=project();upload(id);value(id,"targetOverrides",JsonUtils.write(Arrays.asList(
                DraftBusinessRules.map("actionId","sct-tender-system","action","amend","value","SCT5 Adopted procedure\nOnly this mechanical test procedure applies."),
                DraftBusinessRules.map("actionId","scc-weather-8303","action","not_used"))));
        List<Map<String,Object>> docs=generate(id);String sct=String.valueOf(docs.get(1).get("content")),scc=String.valueOf(docs.get(2).get("content"));
        assertTrue(sct.contains("Only this mechanical test procedure applies."));assertFalse(sct.contains("may be treated as a tendering irregularity"));assertTrue(sct.contains("SCT6"));
        assertTrue(scc.replaceAll("(?U)\\s+"," ").contains("SCC8.303 Not used"),"Native heading tabs remain: "+JsonUtils.write(docs.get(2).get("unresolved")));assertFalse(scc.contains(DraftBusinessRules.standardParagraph("SCC",874)));assertTrue(scc.contains("SCC8.304"));
        opaquePartsIdentical(original(1),word(id,"SCT"));opaquePartsIdentical(original(2),word(id,"SCC"));
    }
    @SuppressWarnings("unchecked")
    @Test void previewAndPdfExportUseTheFrozenDocxAndPublishOneCachedPdfIdentity()throws Exception {
        assumeTrue(System.getProperty("consense.acceptance.rendererExecutable")!=null,"Explicit renderer required");
        String id=project();upload(id);Map<String,Object> frozen=generate(id).get(0);byte[] docx=word(id,"NTT");
        byte[] preview=mvc.perform(get("/api/drafting/{id}/documents/NTT/preview.pdf",id)).andExpect(content().contentTypeCompatibleWith("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=org.apache.pdfbox.pdmodel.PDDocument.load(preview)) {
            assertFalse(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf).contains("Generated by ConSense"),"The source controls all cover/body styles");
            assertTrue(pdf.getNumberOfPages()>10,"Original NTT page settings survive actual conversion");
        }
        byte[] exported=mvc.perform(get("/api/drafting/{id}/documents/NTT/export.pdf",id)).andExpect(content().contentTypeCompatibleWith("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(preview,exported);assertArrayEquals(docx,word(id,"NTT"));
        List<Map<String,Object>> listed=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString()).get("data");
        assertEquals(frozen.get("revisionId"),listed.get(0).get("revisionId"));assertEquals(DraftPdfConverter.sha256(preview),listed.get(0).get("pdfSha256"));assertNotNull(listed.get(0).get("renderProfileHash"));assertEquals("UNVERIFIED",listed.get(0).get("fieldStatus"));
    }
    @SuppressWarnings("unchecked")
    @Test void invalidBlocksAreAtomicAndAFontOnlySourceReplacementStalesTheSavedRevision()throws Exception {
        String id=project();upload(id);Map<String,Object> frozen=generate(id).get(0);byte[] previous=word(id,"NTT");
        Map<String,Object> invalid=DraftBusinessRules.map("revisionId",frozen.get("revisionId"),"docxSha256",frozen.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id","word/document.xml#nonexistent","expectedTextHash","wrong","text","Unsafe")));
        mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(invalid))).andExpect(jsonPath("$.code").value(4012));assertArrayEquals(previous,word(id,"NTT"));
        Map<String,byte[]> changed=parts(original(0));String styles=new String(changed.get("word/styles.xml"),java.nio.charset.StandardCharsets.UTF_8);assertTrue(styles.contains("Times New Roman"));changed.put("word/styles.xml",styles.replace("Times New Roman","Georgia").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> entry:changed.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        mvc.perform(multipart("/api/drafting/{id}/templates/NTT/replace",id).file(new MockMultipartFile("file",NAMES[0],"application/vnd.openxmlformats-officedocument.wordprocessingml.document",out.toByteArray()))).andExpect(jsonPath("$.data.parsed").value(1));
        List<Map<String,Object>> listed=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString()).get("data");assertEquals(true,listed.get(0).get("stale"));assertEquals(frozen.get("revisionId"),listed.get(0).get("revisionId"));assertEquals(frozen.get("content"),listed.get(0).get("content"));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/export.docx",id)).andExpect(jsonPath("$.code").value(4009));
    }
    @Test void wholeClauseWithNativeFormulaRemainsUnresolvedAndPreservesAllSourceEquations()throws Exception {
        String id=project();upload(id);value(id,"targetOverrides",JsonUtils.write(Collections.singletonList(DraftBusinessRules.map("actionId","scc-specialist-SCC20.302","action","not_used"))));
        Map<String,Object> scc=generate(id).get(2);assertTrue(JsonUtils.write(scc.get("unresolved")).contains("SOURCE_FORMULA_SCOPE_REQUIRES_STRUCTURAL_REVIEW"));
        com.consense.document.DocxTemplateEditor editor=new com.consense.document.DocxTemplateEditor();assertEquals(4,editor.inspect(word(id,"SCC")).getFeatures().stream().filter(f->"OMML".equals(f.getKind())).count());assertFalse(String.valueOf(scc.get("content")).replaceAll("(?U)\\s+"," ").contains("SCC20.302 Not used"));
    }
    @SuppressWarnings("unchecked")
    @Test void sourceBoundBodyEditPublishesANewRevisionAndRejectsReplayedOrFlatEdits()throws Exception {
        String id=project();upload(id);Map<String,Object> old=generate(id).get(0);
        Map<String,Object> block=((List<Map<String,Object>>)old.get("blocks")).stream().filter(p->Integer.valueOf(546).equals(p.get("paragraphOrdinal"))).findFirst().get();
        String edited=String.valueOf(block.get("text"))+" Mechanical body amendment.";
        Map<String,Object> patch=DraftBusinessRules.map("revisionId",old.get("revisionId"),"docxSha256",old.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"expectedTextHash",block.get("textHash"),"text",edited,"insertAfter",Arrays.asList("First adopted continuation paragraph.","Second adopted continuation paragraph."))));
        byte[] previous=word(id,"NTT");
        Map<String,Object> updated=(Map<String,Object>)JsonUtils.readMap(mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data");
        assertNotEquals(old.get("revisionId"),updated.get("revisionId"));assertNotEquals(old.get("docxSha256"),updated.get("docxSha256"));assertEquals(old.get("snapshotId"),updated.get("snapshotId"));assertEquals(old.get("sourceSha256"),updated.get("sourceSha256"));assertEquals(true,updated.get("contentEdited"));
        assertTrue(String.valueOf(updated.get("content")).contains("Mechanical body amendment."));assertTrue(String.valueOf(updated.get("content")).contains("First adopted continuation paragraph.\nSecond adopted continuation paragraph."));opaquePartsIdentical(previous,word(id,"NTT"));
        mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(4090));
        mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Unsafe global replacement\"}")).andExpect(jsonPath("$.code").value(4012));
        assertEquals(updated.get("docxSha256"),DraftPdfConverter.sha256(word(id,"NTT")));
    }
    @SuppressWarnings("unchecked")
    @Test void nativeControlOnlySourceBlocksAreProtectedWhileOrdinaryTextAroundMathStaysEditable()throws Exception {
        String id=project();upload(id);List<Map<String,Object>> documents=generate(id);
        for(Map<String,Object> document:documents) {
            List<Map<String,Object>> blocks=(List<Map<String,Object>>)document.get("blocks");List<Map<String,Object>> controls=new ArrayList<>();
            for(Map<String,Object> block:blocks)if(String.valueOf(block.get("text")).matches("[\\t\\r\\n]+"))controls.add(block);
            assertFalse(controls.isEmpty(),"Actual source has native control-only paragraphs");
            for(Map<String,Object> block:controls){assertEquals(false,block.get("editable"),"A native control is not an editable ordinary text span: "+block.get("id"));if(!"FIELD_TEXT_EDIT_UNSUPPORTED".equals(block.get("unsupportedReason")))assertEquals("EMPTY_SPAN_UNSUPPORTED",block.get("unsupportedReason"));}
        }
        Map<String,Object> math=((List<Map<String,Object>>)documents.get(2).get("blocks")).stream().filter(p->Integer.valueOf(1409).equals(p.get("paragraphOrdinal"))).findFirst().get();assertTrue(String.valueOf(math.get("text")).contains("(i.e."));assertEquals(true,math.get("editable"),"Visible ordinary text beside native math remains supported");
    }
    @SuppressWarnings("unchecked")
    @Test void aForgedNativeControlOnlyReplacementRejectsTheEntireBodyBatchBeforePublishing()throws Exception {
        String id=project();upload(id);Map<String,Object> old=generate(id).get(0);byte[] previous=word(id,"NTT");List<Map<String,Object>> blocks=(List<Map<String,Object>>)old.get("blocks");
        Map<String,Object> ordinary=blocks.stream().filter(p->Integer.valueOf(546).equals(p.get("paragraphOrdinal"))).findFirst().get(),control=blocks.stream().filter(p->String.valueOf(p.get("text")).matches("[\\t\\r\\n]+")).findFirst().get();
        Map<String,Object> patch=DraftBusinessRules.map("revisionId",old.get("revisionId"),"docxSha256",old.get("docxSha256"),"blocks",Arrays.asList(DraftBusinessRules.map("id",ordinary.get("id"),"expectedTextHash",ordinary.get("textHash"),"text",ordinary.get("text")+" Must remain unapplied."),DraftBusinessRules.map("id",control.get("id"),"expectedTextHash",control.get("textHash"),"text","Unsupported native control replacement")));
        mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(4012)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("EMPTY_SPAN_UNSUPPORTED")));
        List<Map<String,Object>> listed=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString()).get("data");assertEquals(old.get("revisionId"),listed.get(0).get("revisionId"));assertEquals(old.get("content"),listed.get(0).get("content"));assertArrayEquals(previous,word(id,"NTT"));
    }
    @SuppressWarnings("unchecked")
    @Test void preCorrectionCapabilityFlagsAreRefreshedWithoutRebindingTheFrozenArtifact()throws Exception {
        String id=project();upload(id);Map<String,Object> old=generate(id).get(0);byte[] previous=word(id,"NTT");List<Map<String,Object>> legacyBlocks=(List<Map<String,Object>>)old.get("blocks");
        Map<String,Object> control=legacyBlocks.stream().filter(p->String.valueOf(p.get("text")).matches("[\\t\\r\\n]+")).findFirst().get();control.put("editable",true);control.put("unsupportedReason",null);String legacyId="test-only-old-capabilities-"+UUID.randomUUID();
        // Migration fixture only: append a copy with the pre-correction flags in this isolated H2 DB.
        // Assertions observe public GET/save/export; the original immutable artifact remains untouched.
        fixtureDatabase.update("insert into draft_artifact (id,project_id,file_key,snapshot_id,source_sha256,docx_sha256,docx_bytes,ledger_json,blocks_json,field_impacts_json,parent_revision_id,created_at) select ?,project_id,file_key,snapshot_id,source_sha256,docx_sha256,docx_bytes,ledger_json,?,field_impacts_json,parent_revision_id,created_at from draft_artifact where id=?",legacyId,JsonUtils.write(legacyBlocks),old.get("revisionId"));fixtureDatabase.update("update draft_document set revision_id=? where project_id=? and file_key='NTT'",legacyId,id);
        List<Map<String,Object>> listed=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data");Map<String,Object> current=listed.get(0),refreshed=((List<Map<String,Object>>)current.get("blocks")).stream().filter(p->control.get("id").equals(p.get("id"))).findFirst().get();
        assertEquals(false,refreshed.get("editable"));assertEquals("EMPTY_SPAN_UNSUPPORTED",refreshed.get("unsupportedReason"));for(String field:Arrays.asList("docxSha256","sourceSha256","snapshotId","content"))assertEquals(old.get(field),current.get(field),field);assertEquals(legacyId,current.get("revisionId"));assertArrayEquals(previous,word(id,"NTT"));
        Map<String,Object> patch=DraftBusinessRules.map("revisionId",legacyId,"docxSha256",old.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",control.get("id"),"expectedTextHash",control.get("textHash"),"text","Forged control replacement")));mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(4012)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("EMPTY_SPAN_UNSUPPORTED")));
        List<Map<String,Object>> after=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString()).get("data");assertEquals(legacyId,after.get(0).get("revisionId"));assertArrayEquals(previous,word(id,"NTT"));
    }
    @SuppressWarnings("unchecked")
    @Test void aProtectedEmptyBlockRejectsTheEntireBodyBatchWithoutPublishingARevision()throws Exception {
        String id=project();upload(id);Map<String,Object> old=generate(id).get(0);byte[] previous=word(id,"NTT");
        List<Map<String,Object>> blocks=(List<Map<String,Object>>)old.get("blocks");
        Map<String,Object> ordinary=blocks.stream().filter(p->Integer.valueOf(546).equals(p.get("paragraphOrdinal"))).findFirst().get();
        Map<String,Object> empty=blocks.stream().filter(p->Boolean.FALSE.equals(p.get("editable"))&&"".equals(p.get("text"))).findFirst().get();
        Map<String,Object> patch=DraftBusinessRules.map("revisionId",old.get("revisionId"),"docxSha256",old.get("docxSha256"),"blocks",Arrays.asList(
                DraftBusinessRules.map("id",ordinary.get("id"),"expectedTextHash",ordinary.get("textHash"),"text",ordinary.get("text")+" Must remain unapplied."),
                DraftBusinessRules.map("id",empty.get("id"),"expectedTextHash",empty.get("textHash"),"text","")));
        mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(4012));
        List<Map<String,Object>> listed=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString()).get("data");
        assertEquals(old.get("revisionId"),listed.get(0).get("revisionId"));assertEquals(old.get("content"),listed.get(0).get("content"));assertArrayEquals(previous,word(id,"NTT"));
    }
    @SuppressWarnings("unchecked")
    @Test void visibleTextAroundNativeMathCanBeAmendedButCannotBeClearedIntoAnOrphanFormula()throws Exception {
        String id=project();upload(id);
        // Test-only copy of the actual SCC equation paragraph; the original package is never written.
        Map<String,byte[]> sourceParts=parts(original(2));
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        org.w3c.dom.Document xml=factory.newDocumentBuilder().parse(new ByteArrayInputStream(sourceParts.get("word/document.xml")));
        String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        org.w3c.dom.Element paragraph=(org.w3c.dom.Element)xml.getElementsByTagNameNS(w,"p").item(1408);
        assertEquals(1,paragraph.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","oMath").getLength());
        org.w3c.dom.Element run=xml.createElementNS(w,"w:r"),text=xml.createElementNS(w,"w:t");text.setTextContent("Mechanical surrounding formula text.");run.appendChild(text);paragraph.appendChild(run);
        ByteArrayOutputStream documentXml=new ByteArrayOutputStream();javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(new javax.xml.transform.dom.DOMSource(xml),new javax.xml.transform.stream.StreamResult(documentXml));sourceParts.put("word/document.xml",documentXml.toByteArray());
        ByteArrayOutputStream copied=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(copied)){for(Map.Entry<String,byte[]> entry:sourceParts.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        mvc.perform(multipart("/api/drafting/{id}/templates/SCC/replace",id).file(new MockMultipartFile("file",NAMES[2],"application/vnd.openxmlformats-officedocument.wordprocessingml.document",copied.toByteArray()))).andExpect(jsonPath("$.data.parsed").value(1));
        Map<String,Object> old=generate(id).get(2);byte[] previous=word(id,"SCC");
        Map<String,Object> block=((List<Map<String,Object>>)old.get("blocks")).stream().filter(p->Integer.valueOf(1409).equals(p.get("paragraphOrdinal"))).findFirst().get();
        assertEquals(true,block.get("editable"),"Safe ordinary text spans remain editable beside native math");
        Map<String,Object> clear=DraftBusinessRules.map("revisionId",old.get("revisionId"),"docxSha256",old.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"expectedTextHash",block.get("textHash"),"text","")));
        mvc.perform(put("/api/drafting/{id}/documents/SCC",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(clear))).andExpect(jsonPath("$.code").value(4012));
        List<Map<String,Object>> listed=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString()).get("data");assertEquals(old.get("revisionId"),listed.get(2).get("revisionId"));assertArrayEquals(previous,word(id,"SCC"));
        ((Map<String,Object>)((List<?>)clear.get("blocks")).get(0)).put("text",block.get("text")+" Adopted ordinary-span amendment.");
        mvc.perform(put("/api/drafting/{id}/documents/SCC",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(clear))).andExpect(jsonPath("$.code").value(0));
        byte[] amended=word(id,"SCC");opaquePartsIdentical(previous,amended);
        org.w3c.dom.Document before=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(previous).get("word/document.xml"))),after=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(amended).get("word/document.xml")));
        org.w3c.dom.NodeList oldMath=before.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","oMath"),newMath=after.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","oMath");assertEquals(4,oldMath.getLength());assertEquals(oldMath.getLength(),newMath.getLength());for(int i=0;i<oldMath.getLength();i++)assertTrue(oldMath.item(i).isEqualNode(newMath.item(i)),"All native equations retain their complete XML subtree");
    }
    @Test void uploadedDocxTemplatePreviewReturnsAReadableFormattedPdf()throws Exception {
        assumeTrue(System.getProperty("consense.acceptance.rendererExecutable")!=null,"Explicit renderer required");
        String id=project();
        mvc.perform(multipart("/api/drafting/{id}/templates/NTT/replace",id).file(new MockMultipartFile("file",NAMES[0],"application/vnd.openxmlformats-officedocument.wordprocessingml.document",original(0)))).andExpect(jsonPath("$.data.parsed").value(1));
        org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(get("/api/drafting/{id}/templates/NTT/preview.pdf",id)).andExpect(content().contentTypeCompatibleWith("application/pdf")).andReturn().getResponse();
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=org.apache.pdfbox.pdmodel.PDDocument.load(response.getContentAsByteArray())) {
            assertTrue(pdf.getNumberOfPages()>10,"Uploaded NTT retains its source page settings");
            assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf).toUpperCase(Locale.ROOT).replaceAll("(?U)\\s+"," ").contains("NOTES TO TENDERERS"));
        }
        assertTrue(response.getHeader("Content-Disposition").endsWith(".pdf"),"PDF preview filename describes its actual representation");
    }
    @Test void uploadedDocxTemplatePreviewWithoutARendererReturnsAnExplicitCapabilityError()throws Exception {
        String id=project();
        mvc.perform(multipart("/api/drafting/{id}/templates/NTT/replace",id).file(new MockMultipartFile("file",NAMES[0],"application/vnd.openxmlformats-officedocument.wordprocessingml.document",original(0)))).andExpect(jsonPath("$.data.parsed").value(1));
        String configured=properties.getDrafting().getRendererExecutable();properties.getDrafting().setRendererExecutable(null);
        try {mvc.perform(get("/api/drafting/{id}/templates/NTT/preview.pdf",id)).andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.code").value(4013)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NOT_CONFIGURED")));}
        finally {properties.getDrafting().setRendererExecutable(configured);}
    }
    @Test void anUploadedReadablePdfTemplateRemainsAnExactPdfWithoutAConverter()throws Exception {
        String id=project();ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=new org.apache.pdfbox.pdmodel.PDDocument()){pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());pdf.save(out);}
        byte[] uploaded=out.toByteArray();mvc.perform(multipart("/api/drafting/{id}/templates/NTT/replace",id).file(new MockMultipartFile("file","test-only-source.pdf","application/pdf",uploaded))).andExpect(jsonPath("$.code").value(0));
        String configured=properties.getDrafting().getRendererExecutable();properties.getDrafting().setRendererExecutable(null);
        try {byte[] preview=mvc.perform(get("/api/drafting/{id}/templates/NTT/preview.pdf",id)).andExpect(content().contentTypeCompatibleWith("application/pdf")).andReturn().getResponse().getContentAsByteArray();assertArrayEquals(uploaded,preview);}
        finally {properties.getDrafting().setRendererExecutable(configured);}
    }
    @SuppressWarnings("unchecked")
    @Test void sourceFieldRangesAndNativeMathClearRejectForgedSavesAndPreserveTheRevision()throws Exception {
        String id=project();upload(id);Map<String,Object> old=generate(id).get(2);byte[] previous=word(id,"SCC");List<Map<String,Object>> blocks=(List<Map<String,Object>>)old.get("blocks");
        com.consense.document.DocxTemplateEditor.TemplateIndex index=new com.consense.document.DocxTemplateEditor().inspect(previous);
        com.consense.document.DocxTemplateEditor.Paragraph fieldParagraph=index.getMainParagraphs().stream().filter(p->index.getFeatures().stream().anyMatch(f->"FIELD".equals(f.getKind())&&f.getStartId().startsWith(p.getId()+"/"))).findFirst().get();
        Map<String,Object> field=blocks.stream().filter(p->fieldParagraph.getId().equals(p.get("id"))).findFirst().get(),math=blocks.stream().filter(p->Integer.valueOf(1409).equals(p.get("paragraphOrdinal"))).findFirst().get();
        assertEquals(false,field.get("editable"));assertEquals("FIELD_TEXT_EDIT_UNSUPPORTED",field.get("unsupportedReason"));assertEquals(true,math.get("editable"),"Actual equation paragraph also contains visible ordinary text, which remains editable");
        for(Map<String,Object> block:Arrays.asList(field,math)) {
            Map<String,Object> patch=DraftBusinessRules.map("revisionId",old.get("revisionId"),"docxSha256",old.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"expectedTextHash",block.get("textHash"),"text","")));
            mvc.perform(put("/api/drafting/{id}/documents/SCC",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(4012));assertArrayEquals(previous,word(id,"SCC"));
        }
        List<Map<String,Object>> listed=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString()).get("data");assertEquals(old.get("revisionId"),listed.get(2).get("revisionId"));
    }
    @Test void clearedVerifiedSccGuidanceDoesNotLeaveAutomaticNumbersAndKeepsContractualNumbering()throws Exception {
        String id=project();upload(id);generate(id);byte[] emitted=word(id,"SCC");opaquePartsIdentical(original(2),emitted);
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        org.w3c.dom.Document before=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(original(2)).get("word/document.xml"))),after=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(emitted).get("word/document.xml")));
        String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";org.w3c.dom.NodeList originalParagraphs=before.getElementsByTagNameNS(w,"p"),emittedParagraphs=after.getElementsByTagNameNS(w,"p");Set<Integer> removedGuidance=new HashSet<>(Arrays.asList(598,815,929,1076,1078,2010,2014));
        assertEquals(originalParagraphs.getLength(),emittedParagraphs.getLength(),"Guidance clear retains the source topology");
        for(int ordinal=1;ordinal<=originalParagraphs.getLength();ordinal++) {
            org.w3c.dom.Element a=(org.w3c.dom.Element)originalParagraphs.item(ordinal-1),b=(org.w3c.dom.Element)emittedParagraphs.item(ordinal-1);org.w3c.dom.NodeList sourceNumbers=a.getElementsByTagNameNS(w,"numPr"),outputNumbers=b.getElementsByTagNameNS(w,"numPr");
            if(removedGuidance.contains(ordinal)) {assertEquals(1,sourceNumbers.getLength());assertEquals(0,outputNumbers.getLength(),"Removed preparer note must not leave an automatic label at source P"+ordinal);}
            else {assertEquals(sourceNumbers.getLength(),outputNumbers.getLength());for(int n=0;n<sourceNumbers.getLength();n++)assertTrue(sourceNumbers.item(n).isEqualNode(outputNumbers.item(n)),"Unrelated source numbering P"+ordinal);}
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"L10Pro","Hardcopy"})
    void removedSctAlternativesAndSubmissionRowsLeaveNoBranchLabelsOrContinuationFurniture(String mode)throws Exception {
        String id=project();upload(id);value(id,"electronicTendering",mode);value(id,"twoEnvelopeTendering","true");
        for(String key:Arrays.asList("contractorDesignedFoundations","siteFormationWorks","domesticBlocks","precastFacadePermission"))value(id,key,"false");
        Map<String,Object> sct=generate(id).get(1);Set<String> required=new HashSet<>(Arrays.asList("sct-issue-alternative","sct-pricing-return-alternative","sct-envelope-price-documents","sct-envelope-foundation","sct-envelope-site-formation","sct-envelope-domestic","sct-envelope-special-payment"));
        for(Object raw:DraftBusinessRules.list(sct.get("unresolved"))){Map<String,Object> issue=DraftBusinessRules.asMap(raw);assertFalse(required.contains(issue.get("actionId")),"Explicit source-bound branch/row removal must apply: "+JsonUtils.write(issue));}
        byte[] emitted=word(id,"SCT");opaquePartsIdentical(original(1),emitted);
        com.consense.document.DocxTemplateEditor editor=new com.consense.document.DocxTemplateEditor();com.consense.document.DocxTemplateEditor.TemplateIndex before=editor.inspect(original(1)),after=editor.inspect(emitted);
        String heading="SCT1Tender documents and other information issued";
        assertEquals(before.getMainParagraphs().stream().filter(p->p.getCatalogText().contains(heading)).count()-4,after.getMainParagraphs().stream().filter(p->p.getCatalogText().contains(heading)).count(),"The removed Hardcopy primary/continued headers belong to that branch");
        com.consense.document.DocxTemplateEditor.Paragraph retained=before.mainParagraph("L10Pro".equals(mode)?225:387);
        assertTrue(NativeDocxComparison.retainsParagraph(original(1),retained.getOrdinal(),emitted),"Retained alternative paragraph and all properties survive exactly, apart from complete added binding pairs");
        assertTrue(after.getMainParagraphs().stream().anyMatch(p->p.getCatalogText().contains("SCT6")),"Following source clause survives");
        int removedStart="L10Pro".equals(mode)?229:58,removedEnd="L10Pro".equals(mode)?387:225;
        for(int ordinal=removedStart;ordinal<=removedEnd;ordinal++) {String sourceText=before.mainParagraph(ordinal).getCatalogText();if(sourceText.startsWith("March 2020"))assertFalse(after.getMainParagraphs().stream().anyMatch(p->p.getCatalogText().equals(sourceText)),"Removed branch must not retain its manual page furniture P"+ordinal);}
        int[] adoptedMarkers="L10Pro".equals(mode)?new int[]{478,508,642,668}:new int[]{516,533,685,708};
        for(int ordinal:adoptedMarkers) {String sourceText=before.mainParagraph(ordinal).getCatalogText();assertTrue(sourceText.startsWith("*"));assertFalse(after.getMainParagraphs().stream().anyMatch(p->p.getCatalogText().equals(sourceText)),"Resolved branch marker must disappear P"+ordinal);assertTrue(after.getMainParagraphs().stream().anyMatch(p->p.getCatalogText().equals(sourceText.substring(1))),"Selected branch content remains P"+ordinal);}
        for(int ordinal:new int[]{7,417,418})assertTrue(NativeDocxComparison.retainsParagraph(original(1),ordinal,emitted),"Preface/retained SCT2 source furniture remains outside removal scope P"+ordinal);
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        org.w3c.dom.Document originalXml=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(original(1)).get("word/document.xml"))),outputXml=NativeDocxComparison.withoutAddedBindings(originalXml,factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(emitted).get("word/document.xml"))));String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        for(int ordinal:adoptedMarkers) {
            org.w3c.dom.Element expected=(org.w3c.dom.Element)originalXml.getElementsByTagNameNS(w,"p").item(ordinal-1).cloneNode(true);org.w3c.dom.Node firstText=expected.getElementsByTagNameNS(w,"t").item(0);assertTrue(firstText.getTextContent().startsWith("*"));firstText.setTextContent(firstText.getTextContent().substring(1));
            boolean exact=false;org.w3c.dom.NodeList candidates=outputXml.getElementsByTagNameNS(w,"p");for(int i=0;i<candidates.getLength();i++)if(expected.isEqualNode(candidates.item(i))){exact=true;break;}assertTrue(exact,"Marker-only amendment preserves every remaining source title run/property/control P"+ordinal);
        }
        org.w3c.dom.NodeList originalRows=originalXml.getElementsByTagNameNS(w,"tr"),outputRows=outputXml.getElementsByTagNameNS(w,"tr");
        assertTrue(outputRows.getLength()<originalRows.getLength()-4,"Explicit removed submission rows must disappear as complete rows, without empty bordered cells");
        org.w3c.dom.NodeList paragraphs=outputXml.getElementsByTagNameNS(w,"p");for(int i=0;i<paragraphs.getLength();i++){org.w3c.dom.Element p=(org.w3c.dom.Element)paragraphs.item(i);StringBuilder text=new StringBuilder();org.w3c.dom.NodeList texts=p.getElementsByTagNameNS(w,"t");for(int j=0;j<texts.getLength();j++)text.append(texts.item(j).getTextContent());if(text.toString().trim().isEmpty())assertEquals(0,p.getElementsByTagNameNS(w,"numPr").getLength(),"No automatic label survives a removed SCT branch");}
    }
    @Test void ordinaryNegativeSctChoicesReplaceStarredHeadingsWithoutCrossingTheirNativeTabs()throws Exception {
        String id=project();upload(id);value(id,"domesticBlocks","false");value(id,"precastFacadePermission","false");Map<String,Object> sct=generate(id).get(1);
        for(Object raw:DraftBusinessRules.list(sct.get("unresolved"))){Map<String,Object> issue=DraftBusinessRules.asMap(raw);assertFalse(Arrays.asList("sct-domestic-entire-clause","sct-precast-payment-clause").contains(issue.get("actionId")),"Verified ordinary negative choice must apply: "+JsonUtils.write(issue));}
        com.consense.document.DocxTemplateEditor editor=new com.consense.document.DocxTemplateEditor();byte[] emitted=word(id,"SCT");com.consense.document.DocxTemplateEditor.TemplateIndex before=editor.inspect(original(1)),after=editor.inspect(emitted);
        for(int ordinal:new int[]{550,1015}) {String clause=ordinal==550?"SCT4":"SCT10";assertTrue(before.mainParagraph(ordinal).getText().startsWith("*"+clause+"\t"));assertTrue(after.getMainParagraphs().stream().anyMatch(p->(clause+"\tNot used").equals(p.getText().replaceFirst("\\s+$",""))));}
        assertFalse(after.getMainParagraphs().stream().anyMatch(p->p.getCatalogText().contains("SCT4")&&p.getCatalogText().contains("Cont’d")),"Discarded SCT4 continuation title must not survive Not used");
        assertFalse(String.valueOf(sct.get("content")).contains("SCT5(3)(h)"),"Removed SCT10 leaves no internal reference to its discarded submission row");
        assertTrue(after.getMainParagraphs().size()<before.getMainParagraphs().size()-55,"Not used scopes remove their blank rows and native body separators, retaining the numbered source heading");
        assertFalse(after.getMainParagraphs().stream().anyMatch(p->p.getCatalogText().equals(before.mainParagraph(564).getCatalogText())),"Discarded SCT4 continuation page furniture must not survive");
        for(int ordinal:new int[]{585,1048})assertTrue(NativeDocxComparison.retainsParagraph(original(1),ordinal,emitted),"Following SCT5/SCT11 source headings remain intact");opaquePartsIdentical(original(1),emitted);
    }
    @Test void aNativeControlOutsideTheRemovedBranchIsRetainedAndMakesRowPromotionUnresolved()throws Exception {
        String id=project();upload(id);Map<String,byte[]> copiedParts=parts(original(1));String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        org.w3c.dom.Document xml=factory.newDocumentBuilder().parse(new ByteArrayInputStream(copiedParts.get("word/document.xml")));org.w3c.dom.Element p=(org.w3c.dom.Element)xml.getElementsByTagNameNS(w,"p").item(543);
        assertEquals("",p.getTextContent());org.w3c.dom.Element run=xml.createElementNS(w,"w:r");run.setAttributeNS(w,"w:rsidR","0A090001");run.appendChild(xml.createElementNS(w,"w:tab"));p.appendChild(run);
        ByteArrayOutputStream documentXml=new ByteArrayOutputStream();javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(new javax.xml.transform.dom.DOMSource(xml),new javax.xml.transform.stream.StreamResult(documentXml));copiedParts.put("word/document.xml",documentXml.toByteArray());ByteArrayOutputStream copied=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(copied)){for(Map.Entry<String,byte[]> entry:copiedParts.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        mvc.perform(multipart("/api/drafting/{id}/templates/SCT/replace",id).file(new MockMultipartFile("file",NAMES[1],"application/vnd.openxmlformats-officedocument.wordprocessingml.document",copied.toByteArray()))).andExpect(jsonPath("$.data.parsed").value(1));value(id,"electronicTendering","L10Pro");
        Map<String,Object> sct=generate(id).get(1);boolean guarded=false;for(Object raw:DraftBusinessRules.list(sct.get("unresolved"))){Map<String,Object> issue=DraftBusinessRules.asMap(raw);if("sct-pricing-return-alternative".equals(issue.get("actionId"))&&JsonUtils.write(issue).contains("SOURCE_ROW_SCOPE_INCOMPLETE"))guarded=true;}assertTrue(guarded,"An unselected native control is not a disposable blank separator");
        byte[] emitted=word(id,"SCT");opaquePartsIdentical(copied.toByteArray(),emitted);org.w3c.dom.Document output=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(emitted).get("word/document.xml")));org.w3c.dom.NodeList runs=output.getElementsByTagNameNS(w,"r");boolean retained=false;for(int i=0;i<runs.getLength();i++){org.w3c.dom.Element r=(org.w3c.dom.Element)runs.item(i);if("0A090001".equals(r.getAttributeNS(w,"rsidR"))){assertEquals(1,r.getElementsByTagNameNS(w,"tab").getLength());retained=true;}}assertTrue(retained,"The untouched outside-scope native control survives exactly");
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void discardedSccMeasurementOptionRemovesItsNativePageBreaksAndKeepsTheOtherOption(boolean allProvisional)throws Exception {
        String id=project();upload(id);value(id,"pricingScheme","BQ_SOR");value(id,"allBqQuantitiesProvisional",String.valueOf(allProvisional));Map<String,Object> scc=generate(id).get(2);
        for(Object raw:DraftBusinessRules.list(scc.get("unresolved")))assertNotEquals("scc-mixed-measurement",DraftBusinessRules.asMap(raw).get("actionId"),"Verified measurement option must apply");
        com.consense.document.DocxTemplateEditor editor=new com.consense.document.DocxTemplateEditor();byte[] emitted=word(id,"SCC");com.consense.document.DocxTemplateEditor.TemplateIndex before=editor.inspect(original(2)),after=editor.inspect(emitted);
        int start=allProvisional?1017:1029,end=allProvisional?1027:1061,retained=allProvisional?1050:1021;
        assertTrue(after.getMainParagraphs().size()<=before.getMainParagraphs().size()-(end-start+1),"The discarded option is structurally absent, including empty native separators");
        for(int ordinal:new int[]{retained,1028,1062,1063})assertTrue(NativeDocxComparison.retainsParagraph(original(2),ordinal,emitted),"Adopted option and outside clause boundaries stay exact P"+ordinal);
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        org.w3c.dom.Document a=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(original(2)).get("word/document.xml"))),b=NativeDocxComparison.withoutAddedBindings(a,factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(emitted).get("word/document.xml"))));int removedBreaks=0;
        org.w3c.dom.NodeList ps=a.getElementsByTagNameNS(w,"p");for(int ordinal=start;ordinal<=end;ordinal++){org.w3c.dom.NodeList breaks=((org.w3c.dom.Element)ps.item(ordinal-1)).getElementsByTagNameNS(w,"br");for(int i=0;i<breaks.getLength();i++)if("page".equals(((org.w3c.dom.Element)breaks.item(i)).getAttributeNS(w,"type")))removedBreaks++;}
        assertEquals(pageBreaks(a,w)-removedBreaks,pageBreaks(b,w),"Only discarded-option native page breaks are removed");String math="http://schemas.openxmlformats.org/officeDocument/2006/math";org.w3c.dom.NodeList am=a.getElementsByTagNameNS(math,"oMath"),bm=b.getElementsByTagNameNS(math,"oMath");assertEquals(4,am.getLength());assertEquals(4,bm.getLength());for(int i=0;i<am.getLength();i++)assertTrue(am.item(i).isEqualNode(bm.item(i)),"Unrelated source formula remains exact");
        for(String kind:Arrays.asList("sectPr","bookmarkStart","bookmarkEnd","fldChar","instrText")){org.w3c.dom.NodeList sourceNodes=a.getElementsByTagNameNS(w,kind),outputNodes=b.getElementsByTagNameNS(w,kind);assertEquals(sourceNodes.getLength(),outputNodes.getLength(),kind);for(int i=0;i<sourceNodes.getLength();i++)assertTrue(sourceNodes.item(i).isEqualNode(outputNodes.item(i)),"Unrelated native "+kind+" remains exact");}opaquePartsIdentical(original(2),emitted);
    }
    static int pageBreaks(org.w3c.dom.Document xml,String w){int count=0;org.w3c.dom.NodeList nodes=xml.getElementsByTagNameNS(w,"br");for(int i=0;i<nodes.getLength();i++)if("page".equals(((org.w3c.dom.Element)nodes.item(i)).getAttributeNS(w,"type")))count++;return count;}
    @Test void verifiedStandaloneGuidanceRowsAreRemovedWithoutCleaningInheritedPrefaceSeparators()throws Exception {
        String id=project();upload(id);generate(id);byte[] emitted=word(id,"SCT");com.consense.document.DocxTemplateEditor editor=new com.consense.document.DocxTemplateEditor();com.consense.document.DocxTemplateEditor.TemplateIndex before=editor.inspect(original(1)),after=editor.inspect(emitted);int removedParagraphs=0;
        for(int ordinal:new int[]{55,476,547,582})removedParagraphs+=before.anchor(before.mainParagraph(ordinal).getRowId()).getParagraphIds().size();
        assertEquals(before.getMainParagraphs().size()-removedParagraphs,after.getMainParagraphs().size(),"Only the four independently verified standalone preparer-note rows are structurally removed");
        assertEquals(before.getAnchors().stream().filter(n->"tr".equals(n.getKind())).count()-4,after.getAnchors().stream().filter(n->"tr".equals(n.getKind())).count());
        for(int ordinal=18;ordinal<=52;ordinal++)assertTrue(NativeDocxComparison.retainsParagraph(original(1),ordinal,emitted),"Inherited preface blank separators and its source page break stay intact P"+ordinal);
        opaquePartsIdentical(original(1),emitted);
    }
    @Test void theSourcePreliminariesFractionStaysOnOnePdfPageAfterAdoptedBranchRemoval()throws Exception {
        assumeTrue(System.getProperty("consense.acceptance.rendererExecutable")!=null,"Explicit actual layout engine required");String id=project();upload(id);
        Map<String,Object> fixture;try(InputStream in=getClass().getResourceAsStream("/drafting/correction03-simulated-pagination-values.json")){assertNotNull(in);fixture=JsonUtils.mapper().readValue(in,Map.class);}
        for(Map.Entry<String,Object> entry:DraftBusinessRules.asMap(fixture.get("values")).entrySet())value(id,entry.getKey(),String.valueOf(entry.getValue()));generate(id);byte[] emitted=word(id,"SCT");opaquePartsIdentical(original(1),emitted);
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        org.w3c.dom.Document source=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(original(1)).get("word/document.xml"))),output=factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(emitted).get("word/document.xml")));
        org.w3c.dom.Element sourceRow=(org.w3c.dom.Element)source.getElementsByTagNameNS(w,"p").item(795).getParentNode().getParentNode(),outputRow=null;String exactSourceText=paragraphLeafText(sourceRow,w);org.w3c.dom.NodeList rows=output.getElementsByTagNameNS(w,"tr");
        for(int i=0;i<rows.getLength();i++)if(exactSourceText.equals(paragraphLeafText((org.w3c.dom.Element)rows.item(i),w))){assertNull(outputRow,"Containing source row has one exact match");outputRow=(org.w3c.dom.Element)rows.item(i);}assertNotNull(outputRow);
        org.w3c.dom.Element normalized=(org.w3c.dom.Element)outputRow.cloneNode(true),properties=(org.w3c.dom.Element)normalized.getFirstChild();assertEquals("trPr",properties.getLocalName());assertEquals(1,properties.getChildNodes().getLength());assertEquals("cantSplit",properties.getFirstChild().getLocalName());assertEquals("1",((org.w3c.dom.Element)properties.getFirstChild()).getAttributeNS(w,"val"));normalized.removeChild(properties);assertTrue(sourceRow.isEqualNode(normalized),"All original native fraction/pricing XML stays exact, apart from its single containing-row cohesion control");
        byte[] pdf=mvc.perform(get("/api/drafting/{id}/documents/SCT/preview.pdf",id)).andExpect(content().contentTypeCompatibleWith("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        int numeratorPage=-1,denominatorPage=-1;try(org.apache.pdfbox.pdmodel.PDDocument document=org.apache.pdfbox.pdmodel.PDDocument.load(pdf)){
            for(int page=1;page<=document.getNumberOfPages();page++){org.apache.pdfbox.text.PDFTextStripper text=new org.apache.pdfbox.text.PDFTextStripper();text.setStartPage(page);text.setEndPage(page);String content=text.getText(document).replaceAll("(?U)\\s+"," ");if(numeratorPage<0&&content.contains("Total of Bill No. 1"))numeratorPage=page;if(denominatorPage<0&&content.contains("Amount of Builder’s Works"))denominatorPage=page;}
        }
        assertTrue(numeratorPage>0&&denominatorPage>0,"The source formula is actually present in readable PDF");assertEquals(numeratorPage,denominatorPage,"The native source fraction numerator and denominator must remain on one page");
        String evidence=System.getProperty("consense.acceptance.artifactEvidence");if(evidence!=null){Path dir=Paths.get(evidence);Files.createDirectories(dir);String docxHash=DraftPdfConverter.sha256(emitted),pdfHash=DraftPdfConverter.sha256(pdf);Path artifact=dir.resolve("SCT-"+pdfHash+".pdf");if(!Files.exists(artifact))Files.write(artifact,pdf,StandardOpenOption.CREATE_NEW);Path manifest=dir.resolve("fraction-"+docxHash+".json");if(!Files.exists(manifest))Files.write(manifest,JsonUtils.write(DraftBusinessRules.map("sourceSha256",DraftPdfConverter.sha256(original(1)),"docxSha256",docxHash,"pdfSha256",pdfHash,"numeratorPage",numeratorPage,"denominatorPage",denominatorPage,"rowScope","/w:document/w:body/w:tbl[33]/w:tr[5]","delta","one direct w:trPr/w:cantSplit w:val=1")).getBytes(java.nio.charset.StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
    }
    static String paragraphLeafText(org.w3c.dom.Element node,String w){StringBuilder text=new StringBuilder();org.w3c.dom.NodeList leaves=node.getElementsByTagNameNS(w,"t");for(int i=0;i<leaves.getLength();i++)text.append(leaves.item(i).getTextContent());return text.toString();}
}
