package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Agreed source-upload/generation/binding/export/body-save HTTP seam; real source bytes. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingDocumentBindingsIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final Path EVIDENCE=Paths.get(System.getProperty("consense.acceptance.bindingEvidence","target/binding-evidence"),DB).toAbsolutePath();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:draft_bindings_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->EVIDENCE.resolve("uploads").toString());
        r.add("consense.drafting.renderer-executable",()->System.getProperty("consense.acceptance.rendererExecutable",""));
        r.add("consense.drafting.renderer-work-root",()->System.getProperty("consense.acceptance.rendererWorkRoot",EVIDENCE.resolve("renderer").toString()));
    }
    @Autowired MockMvc mvc;
    @Autowired com.consense.config.ConsenseProperties properties;
    @SuppressWarnings("unchecked")
    private Map<String,Object> data(String response) { return (Map<String,Object>)JsonUtils.readMap(response).get("data"); }
    private String project()throws Exception {
        return String.valueOf(data(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(DraftBusinessRules.map("id","binding-"+UUID.randomUUID(),"nameZhHans","TEST ONLY 实际模板绑定","nameEn","TEST ONLY real-template bindings")))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("id"));
    }
    @SuppressWarnings("unchecked")
    @Test void sourceAndResultMarkersStayBoundToTheirSavedRevisionAfterBodyInsertion()throws Exception {
        String sourceDir=System.getProperty("consense.acceptance.bindingSourceDir");assumeTrue(sourceDir!=null,"Explicit frozen real templates required");
        String id=project();byte[] original=Files.readAllBytes(Paths.get(sourceDir,"NTT-source.docx"));
        for(String key:Arrays.asList("NTT","SCT","SCC"))mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,key).file(new MockMultipartFile("file",key+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",Files.readAllBytes(Paths.get(sourceDir,key+"-source.docx"))))).andExpect(jsonPath("$.data.parsed").value(1));
        Map<String,Object> source=data(mvc.perform(get("/api/drafting/{id}/templates/NTT/bindings",id).param("sourceSha256","60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c")).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.view").value("source")).andReturn().getResponse().getContentAsString());
        assertTrue(((List<?>)source.get("bindings")).size()>100,"Actual original NTT location coverage");
        List<Map<String,Object>> docs=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data");
        Map<String,Object> before=docs.get(0);
        List<Map<String,Object>> blocks=(List<Map<String,Object>>)before.get("blocks");
        Map<String,Object> block=blocks.stream().filter(b->Integer.valueOf(546).equals(b.get("paragraphOrdinal"))).findFirst().get();
        assertNotNull(block.get("bindingId"),"Editable body has persistent marker identity");
        String bindingId=String.valueOf(block.get("bindingId"));
        Map<String,Object> bindingBundle=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id).param("revisionId",String.valueOf(before.get("revisionId"))).param("docxSha256",String.valueOf(before.get("docxSha256")))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        assertEquals(before.get("docxSha256"),bindingBundle.get("docxSha256"));
        Map<String,Object> bound=((List<Map<String,Object>>)bindingBundle.get("bindings")).stream().filter(b->bindingId.equals(b.get("bindingId"))).findFirst().get();
        assertEquals("exact",bound.get("locationStatus"));if("pending".equals(bindingBundle.get("geometryStatus")))assertNull(bound.get("geometry"));
        byte[] beforeBytes=mvc.perform(get("/api/drafting/{id}/documents/NTT/export.docx",id)).andReturn().getResponse().getContentAsByteArray();
        assertTrue(mainXml(beforeBytes).contains("w:name=\""+bound.get("bookmarkName")+"\""));
        Map<String,Object> patch=DraftBusinessRules.map("revisionId",before.get("revisionId"),"docxSha256",before.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"expectedTextHash",block.get("textHash"),"text",block.get("text")+" TEST ONLY binding body amendment.","insertAfter",Collections.singletonList("TEST ONLY inserted continuation."))));
        Map<String,Object> after=data(mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        Map<String,Object> saved=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id).param("revisionId",String.valueOf(after.get("revisionId"))).param("docxSha256",String.valueOf(after.get("docxSha256")))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        Map<String,Object> rebound=((List<Map<String,Object>>)saved.get("bindings")).stream().filter(b->bindingId.equals(b.get("bindingId"))).findFirst().get();
        assertEquals(bound.get("bookmarkName"),rebound.get("bookmarkName"));assertEquals("exact",rebound.get("locationStatus"));assertEquals("body_edited",rebound.get("applicationStatus"));assertTrue(String.valueOf(rebound.get("text")).endsWith("TEST ONLY binding body amendment."));
        assertNotEquals(before.get("revisionId"),saved.get("revisionId"));assertEquals(after.get("docxSha256"),saved.get("docxSha256"));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id).param("revisionId",String.valueOf(before.get("revisionId")))).andExpect(jsonPath("$.code").value(4090));
        assertArrayEquals(original,mvc.perform(get("/api/drafting/{id}/templates/NTT/source",id)).andReturn().getResponse().getContentAsByteArray());
        Files.createDirectories(EVIDENCE);Files.write(EVIDENCE.resolve("binding-source.json"),JsonUtils.write(source).getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.write(EVIDENCE.resolve("binding-result-before.json"),JsonUtils.write(bindingBundle).getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.write(EVIDENCE.resolve("binding-result-saved.json"),JsonUtils.write(saved).getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.write(EVIDENCE.resolve("NTT-before.docx"),beforeBytes);
    }
    private static String mainXml(byte[] bytes)throws IOException {try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)if("word/document.xml".equals(entry.getName())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=zip.read(buffer))!=-1)out.write(buffer,0,n);return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}}throw new IOException("Missing main part");}

    @SuppressWarnings("unchecked")
    @Test void adoptedMultiFieldTargetReportsAppliedEditsAndPendingAlternativesRemainUnapplied()throws Exception {
        String sourceDir=System.getProperty("consense.acceptance.bindingSourceDir");assumeTrue(sourceDir!=null,"Explicit frozen real templates required");
        String id=project();for(String key:Arrays.asList("NTT","SCT","SCC"))mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,key).file(new MockMultipartFile("file",key+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",Files.readAllBytes(Paths.get(sourceDir,key+"-source.docx"))))).andExpect(jsonPath("$.data.parsed").value(1));
        for(String field:Arrays.asList("foundationIncluded","periodAtLeast39Months"))mvc.perform(put("/api/drafting/{id}/variables/{field}",id,field).contentType(MediaType.APPLICATION_JSON).content("{\"value\":true}")).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0));
        Map<String,Object> result=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        List<Map<String,Object>> rows=(List<Map<String,Object>>)result.get("bindings");
        Map<String,Object> bond=rows.stream().filter(row->Integer.valueOf(484).equals(row.get("sourceParagraphOrdinal"))).findFirst().get();
        assertTrue(((List<?>)bond.get("fieldKeys")).containsAll(Arrays.asList("foundationIncluded","periodAtLeast39Months")),"One target retains all causal inputs");
        assertEquals("applied",bond.get("applicationStatus"));assertTrue(String.valueOf(bond.get("text")).contains("Appendix G1a"));assertFalse(((List<?>)bond.get("operationIds")).isEmpty());
        Map<String,Object> pending=rows.stream().filter(row->Integer.valueOf(97).equals(row.get("sourceParagraphOrdinal"))).findFirst().get();
        assertTrue(((List<?>)pending.get("fieldKeys")).contains("electronicTendering"));assertEquals("unapplied",pending.get("applicationStatus"));assertEquals("exact",pending.get("locationStatus"));
        assertFalse(bond.containsKey("evidenceAnchors"),"Template locations do not impersonate correspondence evidence");
        Map<String,Object> source=data(mvc.perform(get("/api/drafting/{id}/templates/NTT/bindings",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        Map<String,Object> original=((List<Map<String,Object>>)source.get("bindings")).stream().filter(row->bond.get("bindingId").equals(row.get("bindingId"))).findFirst().get();
        assertTrue(String.valueOf(original.get("text")).contains("*G1/*G1a"));assertEquals(bond.get("sourceTextHash"),original.get("sourceTextHash"));
        Files.createDirectories(EVIDENCE);Files.write(EVIDENCE.resolve("adopted-and-pending-bindings.json"),JsonUtils.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    @Test void namedPdfPointsAppearOnlyForTheMatchingSavedRevisionAndSourceWorkingCopy()throws Exception {
        String sourceDir=System.getProperty("consense.acceptance.bindingSourceDir");assumeTrue(sourceDir!=null&&System.getProperty("consense.acceptance.rendererExecutable")!=null,"Explicit frozen templates and actual renderer required");
        String id=project();for(String key:Arrays.asList("NTT","SCT","SCC"))mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,key).file(new MockMultipartFile("file",key+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",Files.readAllBytes(Paths.get(sourceDir,key+"-source.docx"))))).andExpect(jsonPath("$.data.parsed").value(1));
        for(String field:Arrays.asList("foundationIncluded","periodAtLeast39Months"))mvc.perform(put("/api/drafting/{id}/variables/{field}",id,field).contentType(MediaType.APPLICATION_JSON).content("{\"value\":true}")).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0));
        Map<String,Object> pending=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        assertEquals("pending",pending.get("geometryStatus"));assertNull(pending.get("pdfSha256"));
        byte[] pdf=mvc.perform(get("/api/drafting/{id}/documents/NTT/preview.pdf",id).param("revisionId",String.valueOf(pending.get("revisionId")))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF)).andReturn().getResponse().getContentAsByteArray();
        Map<String,Object> located=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id).param("revisionId",String.valueOf(pending.get("revisionId"))).param("docxSha256",String.valueOf(pending.get("docxSha256")))).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.geometryStatus").value("ready")).andReturn().getResponse().getContentAsString());
        assertEquals(DraftPdfConverter.sha256(pdf),located.get("pdfSha256"));assertNotNull(located.get("renderProfileHash"));
        Map<String,Object> amendedBond=((List<Map<String,Object>>)located.get("bindings")).stream().filter(row->Integer.valueOf(484).equals(row.get("sourceParagraphOrdinal"))).findFirst().get();assertEquals("applied",amendedBond.get("applicationStatus"));assertTrue(String.valueOf(amendedBond.get("text")).contains("Appendix G1a"));assertNotNull(amendedBond.get("geometry"));
        assertParagraphStartNearPoint(pdf,amendedBond,"Tenderers are to particularly note");
        assertArrayEquals(pdf,mvc.perform(get("/api/drafting/{id}/documents/NTT/export.pdf",id).param("revisionId",String.valueOf(located.get("revisionId")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String executable=properties.getDrafting().getRendererExecutable();properties.getDrafting().setRendererExecutable(EVIDENCE.resolve("unavailable-renderer.com").toString());
        try {mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id)).andExpect(jsonPath("$.data.geometryStatus").value("pending")).andExpect(jsonPath("$.data.pdfSha256").doesNotExist());mvc.perform(get("/api/drafting/{id}/documents",id)).andExpect(jsonPath("$.data[0].pdfSha256").doesNotExist());}
        finally {properties.getDrafting().setRendererExecutable(executable);}
        int resolved=0;try(org.apache.pdfbox.pdmodel.PDDocument parsed=org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            for(Map<String,Object> row:(List<Map<String,Object>>)located.get("bindings"))if("exact".equals(row.get("locationStatus"))) {
                Map<String,Object> point=(Map<String,Object>)row.get("geometry");assertNotNull(point,"Every surviving actual marker should resolve");assertEquals("point",point.get("kind"));assertFalse(point.containsKey("width"),"A destination is not a text rectangle");
                org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination dest=parsed.getDocumentCatalog().findNamedDestinationPage(new org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination(String.valueOf(row.get("bookmarkName"))));
                assertNotNull(dest);assertEquals(dest.retrievePageNumber()+1,((Number)point.get("pageNumber")).intValue());assertEquals(parsed.getPage(dest.retrievePageNumber()).getCropBox().getWidth(),((Number)point.get("pageWidth")).floatValue(),0.02);resolved++;
            }
        }assertTrue(resolved>100);
        Map<String,Object> doc=((List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString()).get("data")).get(0);
        Map<String,Object> block=((List<Map<String,Object>>)doc.get("blocks")).stream().filter(b->Integer.valueOf(546).equals(b.get("paragraphOrdinal"))).findFirst().get();
        Map<String,Object> after=data(mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(DraftBusinessRules.map("revisionId",doc.get("revisionId"),"docxSha256",doc.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"bindingId",block.get("bindingId"),"expectedTextHash",block.get("textHash"),"text",block.get("text")+" TEST ONLY new PDF revision.")))))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        Map<String,Object> savedPending=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id)).andExpect(jsonPath("$.data.geometryStatus").value("pending")).andReturn().getResponse().getContentAsString());assertEquals(after.get("revisionId"),savedPending.get("revisionId"));assertNull(savedPending.get("pdfSha256"));
        byte[] sourcePdf=mvc.perform(get("/api/drafting/{id}/templates/NTT/preview.pdf",id).param("sourceSha256",String.valueOf(located.get("sourceSha256")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String,Object> source=data(mvc.perform(get("/api/drafting/{id}/templates/NTT/bindings",id)).andExpect(jsonPath("$.data.geometryStatus").value("ready")).andReturn().getResponse().getContentAsString());assertEquals(DraftPdfConverter.sha256(sourcePdf),source.get("pdfSha256"));assertEquals(located.get("renderProfileHash"),source.get("renderProfileHash"));
        Map<String,Object> originalBond=((List<Map<String,Object>>)source.get("bindings")).stream().filter(row->amendedBond.get("bindingId").equals(row.get("bindingId"))).findFirst().get();assertTrue(String.valueOf(originalBond.get("text")).contains("*G1/*G1a"));assertNotNull(originalBond.get("geometry"));
        assertParagraphStartNearPoint(sourcePdf,originalBond,"Tenderers are to particularly note");
        assertArrayEquals(Files.readAllBytes(Paths.get(sourceDir,"NTT-source.docx")),mvc.perform(get("/api/drafting/{id}/templates/NTT/source",id)).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/drafting/{id}/templates/NTT/preview.pdf",id).param("sourceSha256","wrong-source")).andExpect(jsonPath("$.code").value(4090));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id).param("docxSha256","wrong-docx")).andExpect(jsonPath("$.code").value(4090));
        Files.createDirectories(EVIDENCE);Files.write(EVIDENCE.resolve("NTT-result.pdf"),pdf);Files.write(EVIDENCE.resolve("NTT-source.pdf"),sourcePdf);Files.write(EVIDENCE.resolve("located-result.json"),JsonUtils.write(located).getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.write(EVIDENCE.resolve("located-source.json"),JsonUtils.write(source).getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.write(EVIDENCE.resolve("new-revision-pending.json"),JsonUtils.write(savedPending).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(put("/api/drafting/{id}/variables/foundationIncluded",id).contentType(MediaType.APPLICATION_JSON).content("{\"value\":false}")).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id).param("revisionId",String.valueOf(after.get("revisionId")))).andExpect(jsonPath("$.code").value(4009));
    }

    /** Compare the semantic first line's actual glyph placement, independently of the destination dictionary. */
    @SuppressWarnings("unchecked")
    private static void assertParagraphStartNearPoint(byte[] bytes,Map<String,Object> binding,String prefix)throws Exception {
        Map<String,Object> point=(Map<String,Object>)binding.get("geometry");List<double[]> starts=new ArrayList<>();
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
            org.apache.pdfbox.text.PDFTextStripper text=new org.apache.pdfbox.text.PDFTextStripper() {
                @Override protected void writeString(String line,List<org.apache.pdfbox.text.TextPosition> positions)throws java.io.IOException {
                    if(line.startsWith(prefix)&&!positions.isEmpty()){org.apache.pdfbox.text.TextPosition first=positions.get(0);starts.add(new double[]{getCurrentPageNo(),first.getXDirAdj(),first.getYDirAdj()});}
                    super.writeString(line,positions);
                }
            };text.setSortByPosition(true);text.getText(pdf);
        }
        assertEquals(1,starts.size(),"The actual paragraph first line is unique in the saved PDF");double[] start=starts.get(0);
        assertEquals(((Number)point.get("pageNumber")).intValue(),(int)start[0],"Navigation point belongs to the paragraph's actual physical page");
        assertEquals(((Number)point.get("x")).doubleValue(),start[1],2.0,"Navigation point starts beside the actual first glyph");
        double baselineGap=start[2]-((Number)point.get("y")).doubleValue();assertTrue(baselineGap>=0&&baselineGap<=18,"Navigation point lies above the actual first-line baseline, within one source text line");
    }

    @SuppressWarnings("unchecked")
    @Test void insertedBodyParagraphHasItsOwnMarkerAndMarkerIdentityOverridesAShiftedPathHint()throws Exception {
        String sourceDir=System.getProperty("consense.acceptance.bindingSourceDir");assumeTrue(sourceDir!=null,"Explicit frozen real templates required");
        String id=project();for(String key:Arrays.asList("NTT","SCT","SCC"))mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,key).file(new MockMultipartFile("file",key+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",Files.readAllBytes(Paths.get(sourceDir,key+"-source.docx"))))).andExpect(jsonPath("$.data.parsed").value(1));
        Map<String,Object> before=((List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data")).get(0);
        Map<String,Object> selected=((List<Map<String,Object>>)before.get("blocks")).stream().filter(b->Integer.valueOf(546).equals(b.get("paragraphOrdinal"))).findFirst().get();
        String inserted="TEST ONLY persistent new body paragraph.";
        Map<String,Object> after=data(mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(DraftBusinessRules.map("revisionId",before.get("revisionId"),"docxSha256",before.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",selected.get("id"),"bindingId",selected.get("bindingId"),"expectedTextHash",selected.get("textHash"),"text",selected.get("text"),"insertAfter",Collections.singletonList(inserted))))))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        Map<String,Object> added=((List<Map<String,Object>>)after.get("blocks")).stream().filter(b->inserted.equals(b.get("text"))).findFirst().get();assertNotNull(added.get("bindingId"),"New saved body text has a persistent identity");assertNotEquals(selected.get("bindingId"),added.get("bindingId"));
        Map<String,Object> bindings=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id)).andReturn().getResponse().getContentAsString());Map<String,Object> newBinding=((List<Map<String,Object>>)bindings.get("bindings")).stream().filter(b->added.get("bindingId").equals(b.get("bindingId"))).findFirst().get();assertEquals("body_added",newBinding.get("applicationStatus"));assertNull(newBinding.get("sourceParagraphId"),"An added paragraph must not fabricate an original source target");assertEquals(selected.get("bindingId"),newBinding.get("parentBindingId"));assertFalse(((List<?>)newBinding.get("operationIds")).isEmpty(),"Added locations retain their actual insertion operation");
        Map<String,Object> patch=DraftBusinessRules.map("revisionId",after.get("revisionId"),"docxSha256",after.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",selected.get("id"),"bindingId",added.get("bindingId"),"expectedTextHash",added.get("textHash"),"text",inserted+" Revised through marker.")));
        Map<String,Object> saved=data(mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        List<Map<String,Object>> savedBlocks=(List<Map<String,Object>>)saved.get("blocks");assertEquals(selected.get("text"),savedBlocks.stream().filter(b->selected.get("bindingId").equals(b.get("bindingId"))).findFirst().get().get("text"));assertEquals(inserted+" Revised through marker.",savedBlocks.stream().filter(b->added.get("bindingId").equals(b.get("bindingId"))).findFirst().get().get("text"));
        Map<String,Object> unknown=DraftBusinessRules.map("revisionId",saved.get("revisionId"),"docxSha256",saved.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",selected.get("id"),"bindingId","CSUnknownNotRegistered","expectedTextHash",selected.get("textHash"),"text","TEST ONLY must reject.")));
        mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(unknown))).andExpect(jsonPath("$.code").value(4090));
        Files.createDirectories(EVIDENCE);Files.write(EVIDENCE.resolve("new-paragraph-binding.json"),JsonUtils.write(bindings).getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.write(EVIDENCE.resolve("marker-authoritative-save.json"),JsonUtils.write(saved).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
