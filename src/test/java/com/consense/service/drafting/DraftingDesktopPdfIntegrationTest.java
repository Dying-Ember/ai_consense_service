package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual free engine through the agreed source-upload/preview/binding HTTP seam. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class DraftingDesktopPdfIntegrationTest {
    private static final String RUN=UUID.randomUUID().toString();
    private static final Path EVIDENCE=Paths.get(System.getProperty("consense.acceptance.desktopEvidence","target/desktop-evidence"),RUN).toAbsolutePath();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:desktop_pdf_"+RUN+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->EVIDENCE.resolve("uploads").toString());
        r.add("consense.drafting.renderer-kind",()->"desktop-x2t");
        r.add("consense.drafting.renderer-executable",()->System.getProperty("consense.acceptance.desktopExecutable",""));
        r.add("consense.drafting.renderer-asset-root",()->System.getProperty("consense.acceptance.desktopAssetRoot",""));
        r.add("consense.drafting.renderer-font-cache",()->System.getProperty("consense.acceptance.desktopFontCache",""));
        r.add("consense.drafting.renderer-work-root",()->System.getProperty("consense.acceptance.desktopWorkRoot",EVIDENCE.resolve("renderer").toString()));
    }
    @Autowired MockMvc mvc;
    @Autowired com.consense.config.ConsenseProperties properties;
    @SuppressWarnings("unchecked") private Map<String,Object> data(String response) {return (Map<String,Object>)JsonUtils.readMap(response).get("data");}
    private String project()throws Exception {
        return String.valueOf(data(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(DraftBusinessRules.map("id","desktop-"+UUID.randomUUID(),"nameZhHans","TEST ONLY 免费转换","nameEn","TEST ONLY free desktop converter")))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("id"));
    }
    @SuppressWarnings("unchecked")
    @Test void originalPreviewUsesTheExplicitFreeEngineAndNeverExposesItsTransientMarkerLinks()throws Exception {
        String sources=System.getProperty("consense.acceptance.bindingSourceDir");
        assumeTrue(sources!=null&&System.getProperty("consense.acceptance.desktopExecutable")!=null,"Explicit actual templates and portable free runtime are required");
        String id=project();byte[] original=Files.readAllBytes(Paths.get(sources,"NTT-source.docx"));
        mvc.perform(multipart("/api/drafting/{id}/templates/NTT/replace",id).file(new MockMultipartFile("file","NTT.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",original))).andExpect(jsonPath("$.data.parsed").value(1));
        Map<String,Object> pending=data(mvc.perform(get("/api/drafting/{id}/templates/NTT/bindings",id)).andExpect(jsonPath("$.data.geometryStatus").value("pending")).andReturn().getResponse().getContentAsString());
        org.springframework.test.web.servlet.MvcResult preview=mvc.perform(get("/api/drafting/{id}/templates/NTT/preview.pdf",id).param("sourceSha256",String.valueOf(pending.get("sourceSha256")))).andReturn();
        Files.createDirectories(EVIDENCE);Files.write(EVIDENCE.resolve("first-preview-response.bin"),preview.getResponse().getContentAsByteArray());
        status().isOk().match(preview);content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF).match(preview);
        byte[] pdf=preview.getResponse().getContentAsByteArray();
        Map<String,Object> located=data(mvc.perform(get("/api/drafting/{id}/templates/NTT/bindings",id)).andExpect(jsonPath("$.data.geometryStatus").value("ready")).andReturn().getResponse().getContentAsString());
        assertEquals(DraftPdfConverter.sha256(pdf),located.get("pdfSha256"));assertNotNull(located.get("renderProfileHash"));
        Map<String,Object> body=((List<Map<String,Object>>)located.get("bindings")).stream().filter(row->Integer.valueOf(546).equals(row.get("sourceParagraphOrdinal"))).findFirst().get();
        assertNotNull(body.get("geometry"),"The actual existing paragraph marker must locate the actual PDF");
        assertActualParagraphNear(pdf,body,"The tenderer's attention is drawn");
        try(PDDocument parsed=PDDocument.load(pdf)) {
            assertEquals(11,parsed.getNumberOfPages(),"Observed frozen NTT/free-runtime layout, not a Word-layout certification");
            for(org.apache.pdfbox.pdmodel.PDPage page:parsed.getPages())for(PDAnnotation annotation:page.getAnnotations())if(annotation instanceof PDAnnotationLink) {
                org.apache.pdfbox.pdmodel.interactive.action.PDAction action=((PDAnnotationLink)annotation).getAction();
                if(action instanceof PDActionURI)assertFalse(((PDActionURI)action).getURI().startsWith("consense-binding:"),"Temporary marker links must never reach the reader");
            }
        }
        assertArrayEquals(original,mvc.perform(get("/api/drafting/{id}/templates/NTT/source",id)).andReturn().getResponse().getContentAsByteArray());
        Files.write(EVIDENCE.resolve("NTT-source.pdf"),pdf);Files.write(EVIDENCE.resolve("located-source.json"),JsonUtils.write(located).getBytes(StandardCharsets.UTF_8));
    }
    @SuppressWarnings("unchecked")
    @Test void sctIndentedBodyDoesNotExposeAWhitespaceOnlyLocation()throws Exception {
        String sources=System.getProperty("consense.acceptance.bindingSourceDir");assumeTrue(sources!=null&&System.getProperty("consense.acceptance.desktopExecutable")!=null,"Explicit actual templates and portable free runtime required");
        String id=project();byte[] original=Files.readAllBytes(Paths.get(sources,"SCT-source.docx"));
        mvc.perform(multipart("/api/drafting/{id}/templates/SCT/replace",id).file(new MockMultipartFile("file","SCT.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",original))).andExpect(jsonPath("$.data.parsed").value(1));
        byte[] pdf=mvc.perform(get("/api/drafting/{id}/templates/SCT/preview.pdf",id)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF)).andReturn().getResponse().getContentAsByteArray();
        Map<String,Object> bundle=data(mvc.perform(get("/api/drafting/{id}/templates/SCT/bindings",id)).andExpect(jsonPath("$.data.geometryStatus").value("ready")).andReturn().getResponse().getContentAsString());
        Map<String,Object> body=((List<Map<String,Object>>)bundle.get("bindings")).stream().filter(row->Integer.valueOf(285).equals(row.get("sourceParagraphOrdinal"))).findFirst().get();
        Files.createDirectories(EVIDENCE);Files.write(EVIDENCE.resolve("SCT-indented-source.pdf"),pdf);write(EVIDENCE.resolve("SCT-indented-source-bindings.json"),bundle);
        assertArrayEquals(original,mvc.perform(get("/api/drafting/{id}/templates/SCT/source",id)).andReturn().getResponse().getContentAsByteArray());assertNoMarkerActions(pdf);
        if(body.get("geometry")==null)assertEquals("unavailable",body.get("geometryStatus"),"No annotation for the visible first run must be honestly unavailable");
        else assertActualParagraphNear(pdf,body,"Schedule of Proportions");
    }
    @SuppressWarnings("unchecked")
    @Test void threeOriginalsAndSavedDraftsUseExactRevisionBytesAndRemainHonestAfterBodySaveOrEngineChange()throws Exception {
        String sources=System.getProperty("consense.acceptance.bindingSourceDir");assumeTrue(sources!=null&&System.getProperty("consense.acceptance.desktopExecutable")!=null,"Explicit actual templates and free runtime required");
        String id=project();Map<String,byte[]> originals=new LinkedHashMap<>();Path evidence=Files.createDirectories(EVIDENCE.resolve("six-http-artifacts"));
        for(String key:Arrays.asList("NTT","SCT","SCC")){byte[] bytes=Files.readAllBytes(Paths.get(sources,key+"-source.docx"));originals.put(key,bytes);mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,key).file(new MockMultipartFile("file",key+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes))).andExpect(jsonPath("$.data.parsed").value(1));}
        for(String field:Arrays.asList("foundationIncluded","periodAtLeast39Months"))mvc.perform(put("/api/drafting/{id}/variables/{field}",id,field).contentType(MediaType.APPLICATION_JSON).content("{\"value\":true}")).andExpect(jsonPath("$.code").value(0));
        List<Map<String,Object>> docs=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data");
        String profileHash=null;
        for(String key:Arrays.asList("NTT","SCT","SCC")) {
            Map<String,Object> doc=docs.stream().filter(row->key.equals(row.get("fileKey"))).findFirst().get();String revision=String.valueOf(doc.get("revisionId"));
            byte[] exactDocx=mvc.perform(get("/api/drafting/{id}/documents/{key}/export.docx",id,key).param("revisionId",revision)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();assertEquals(doc.get("docxSha256"),DraftPdfConverter.sha256(exactDocx));
            byte[] pdf=mvc.perform(get("/api/drafting/{id}/documents/{key}/preview.pdf",id,key).param("revisionId",revision)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF)).andReturn().getResponse().getContentAsByteArray();assertNoMarkerActions(pdf);
            assertArrayEquals(pdf,mvc.perform(get("/api/drafting/{id}/documents/{key}/export.pdf",id,key).param("revisionId",revision)).andReturn().getResponse().getContentAsByteArray());
            assertArrayEquals(exactDocx,mvc.perform(get("/api/drafting/{id}/documents/{key}/export.docx",id,key).param("revisionId",revision)).andReturn().getResponse().getContentAsByteArray());
            Map<String,Object> result=data(mvc.perform(get("/api/drafting/{id}/documents/{key}/bindings",id,key).param("revisionId",revision)).andExpect(jsonPath("$.data.geometryStatus").value("ready")).andReturn().getResponse().getContentAsString());assertEquals(doc.get("docxSha256"),result.get("docxSha256"));assertEquals(DraftPdfConverter.sha256(pdf),result.get("pdfSha256"));
            if(profileHash==null)profileHash=String.valueOf(result.get("renderProfileHash"));else assertEquals(profileHash,result.get("renderProfileHash"));
            byte[] sourcePdf=mvc.perform(get("/api/drafting/{id}/templates/{key}/preview.pdf",id,key).param("sourceSha256",DraftPdfConverter.sha256(originals.get(key)))).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF)).andReturn().getResponse().getContentAsByteArray();assertNoMarkerActions(sourcePdf);
            Map<String,Object> source=data(mvc.perform(get("/api/drafting/{id}/templates/{key}/bindings",id,key)).andExpect(jsonPath("$.data.geometryStatus").value("ready")).andReturn().getResponse().getContentAsString());assertEquals(profileHash,source.get("renderProfileHash"));assertEquals(DraftPdfConverter.sha256(sourcePdf),source.get("pdfSha256"));
            assertArrayEquals(originals.get(key),mvc.perform(get("/api/drafting/{id}/templates/{key}/source",id,key)).andReturn().getResponse().getContentAsByteArray());
            Files.write(evidence.resolve(key+"-source.pdf"),sourcePdf);Files.write(evidence.resolve(key+"-result.pdf"),pdf);Files.write(evidence.resolve(key+"-result.docx"),exactDocx);write(evidence.resolve(key+"-source-bindings.json"),source);write(evidence.resolve(key+"-result-bindings.json"),result);
            if("SCC".equals(key))assertTrue(((List<Map<String,Object>>)result.get("bindings")).stream().anyMatch(row->"exact".equals(row.get("locationStatus"))&&row.get("geometry")==null),"Protected/non-addressable original targets remain explicitly unavailable");
        }
        Map<String,Object> before=docs.stream().filter(row->"NTT".equals(row.get("fileKey"))).findFirst().get();
        Map<String,Object> nttBindings=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        Object targetId=((List<Map<String,Object>>)nttBindings.get("bindings")).stream().filter(row->Integer.valueOf(546).equals(row.get("sourceParagraphOrdinal"))).findFirst().get().get("bindingId");
        Map<String,Object> block=((List<Map<String,Object>>)before.get("blocks")).stream().filter(row->targetId.equals(row.get("bindingId"))).findFirst().get();
        String editedText=block.get("text")+"\nTEST ONLY\tfree-engine saved body revision.";
        Map<String,Object> patch=DraftBusinessRules.map("revisionId",before.get("revisionId"),"docxSha256",before.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"bindingId",block.get("bindingId"),"expectedTextHash",block.get("textHash"),"text",editedText)));
        Map<String,Object> saved=data(mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());assertNotEquals(before.get("revisionId"),saved.get("revisionId"));
        assertEquals(editedText,((List<Map<String,Object>>)saved.get("blocks")).stream().filter(row->block.get("bindingId").equals(row.get("bindingId"))).findFirst().get().get("text"),"The sanctioned LF/Tab body save must read back through the same public document interface");
        Map<String,Object> pending=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id)).andExpect(jsonPath("$.data.geometryStatus").value("pending")).andReturn().getResponse().getContentAsString());assertNull(pending.get("pdfSha256"));assertTrue(((List<Map<String,Object>>)pending.get("bindings")).stream().allMatch(row->row.get("geometry")==null));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/preview.pdf",id).param("revisionId",String.valueOf(before.get("revisionId")))).andExpect(jsonPath("$.code").value(4090));mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(patch))).andExpect(jsonPath("$.code").value(4090));
        byte[] savedDocx=mvc.perform(get("/api/drafting/{id}/documents/NTT/export.docx",id).param("revisionId",String.valueOf(saved.get("revisionId")))).andReturn().getResponse().getContentAsByteArray();assertEquals(saved.get("docxSha256"),DraftPdfConverter.sha256(savedDocx));
        byte[] savedPdf=mvc.perform(get("/api/drafting/{id}/documents/NTT/preview.pdf",id).param("revisionId",String.valueOf(saved.get("revisionId")))).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF)).andReturn().getResponse().getContentAsByteArray();assertNoMarkerActions(savedPdf);
        try(PDDocument pdf=PDDocument.load(savedPdf)){StringBuilder words=new StringBuilder();for(int page=1;page<=pdf.getNumberOfPages();page++)for(org.apache.pdfbox.text.TextPosition p:physicalGlyphs(pdf,page))words.append(p.getUnicode().replaceAll("[^A-Za-z]",""));assertTrue(words.toString().contains("TESTONLYfreeenginesavedbodyrevision"),"Saved words must survive PDF line/run fragmentation; native saved bytes are checked separately");}
        Map<String,Object> located=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id)).andExpect(jsonPath("$.data.geometryStatus").value("ready")).andReturn().getResponse().getContentAsString());Map<String,Object> edited=((List<Map<String,Object>>)located.get("bindings")).stream().filter(row->block.get("bindingId").equals(row.get("bindingId"))).findFirst().get();assertEquals("body_edited",edited.get("applicationStatus"));assertNotNull(edited.get("geometry"));assertActualParagraphNear(savedPdf,edited,"The tenderer's attention is drawn");
        assertArrayEquals(savedPdf,mvc.perform(get("/api/drafting/{id}/documents/NTT/export.pdf",id).param("revisionId",String.valueOf(saved.get("revisionId")))).andReturn().getResponse().getContentAsByteArray());assertArrayEquals(savedDocx,mvc.perform(get("/api/drafting/{id}/documents/NTT/export.docx",id)).andReturn().getResponse().getContentAsByteArray());
        Files.write(evidence.resolve("NTT-saved.docx"),savedDocx);Files.write(evidence.resolve("NTT-saved.pdf"),savedPdf);write(evidence.resolve("NTT-saved-bindings.json"),located);write(evidence.resolve("NTT-new-revision-pending.json"),pending);
        String kind=properties.getDrafting().getRendererKind();properties.getDrafting().setRendererKind("unavailable-explicit-engine");
        try{mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id)).andExpect(jsonPath("$.data.geometryStatus").value("pending")).andExpect(jsonPath("$.data.pdfSha256").doesNotExist());mvc.perform(get("/api/drafting/{id}/documents/NTT/preview.pdf",id)).andExpect(jsonPath("$.code").value(4013)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NOT_CONFIGURED")));}
        finally{properties.getDrafting().setRendererKind(kind);}
        for(String key:originals.keySet())assertArrayEquals(originals.get(key),mvc.perform(get("/api/drafting/{id}/templates/{key}/source",id,key)).andReturn().getResponse().getContentAsByteArray());
    }
    private static void write(Path path,Object value)throws Exception {Files.write(path,JsonUtils.write(value).getBytes(StandardCharsets.UTF_8));}
    private static void assertNoMarkerActions(byte[] bytes)throws Exception {
        assertFalse(new String(bytes,StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT).contains("consense-binding:"),"These actual template PDFs must not retain conversion-only URI strings");
        try(PDDocument pdf=PDDocument.load(bytes)) {assertTrue(pdf.getNumberOfPages()>0);assertNull(pdf.getDocumentCatalog().getOpenAction());for(org.apache.pdfbox.pdmodel.PDPage page:pdf.getPages())for(PDAnnotation annotation:page.getAnnotations())if(annotation instanceof PDAnnotationLink){org.apache.pdfbox.pdmodel.interactive.action.PDAction action=((PDAnnotationLink)annotation).getAction();if(action instanceof PDActionURI)assertFalse(((PDActionURI)action).getURI().toLowerCase(Locale.ROOT).startsWith("consense-binding:"));}}
    }
    @SuppressWarnings("unchecked") static void assertActualParagraphNear(byte[] bytes,Map<String,Object> binding,String prefix)throws Exception {
        Map<String,Object> point=(Map<String,Object>)binding.get("geometry");List<org.apache.pdfbox.text.TextPosition> glyphs=new ArrayList<>(),letters=new ArrayList<>();StringBuilder text=new StringBuilder();int page=((Number)point.get("pageNumber")).intValue();
        try(PDDocument pdf=PDDocument.load(bytes)){glyphs.addAll(physicalGlyphs(pdf,page));}
        for(org.apache.pdfbox.text.TextPosition p:glyphs)for(char c:p.getUnicode().toCharArray())if(c>='A'&&c<='Z'||c>='a'&&c<='z'){text.append(Character.toLowerCase(c));letters.add(p);}
        String expected=prefix.replaceAll("[^A-Za-z]","").toLowerCase(Locale.ROOT);boolean near=false;for(int i=text.indexOf(expected);i>=0;i=text.indexOf(expected,i+1)){org.apache.pdfbox.text.TextPosition p=letters.get(i);if(Math.abs(p.getXDirAdj()-((Number)point.get("x")).doubleValue())<=18&&Math.abs(p.getYDirAdj()-((Number)point.get("y")).doubleValue())<=18)near=true;}
        assertTrue(near,"Named point must be near this paragraph's independently extracted first glyph, not merely inside the page; matching text="+text.indexOf(expected)+" point="+point);
    }
    private static List<org.apache.pdfbox.text.TextPosition> physicalGlyphs(PDDocument pdf,int page)throws Exception {
        List<org.apache.pdfbox.text.TextPosition> glyphs=new ArrayList<>();org.apache.pdfbox.text.PDFTextStripper stripper=new org.apache.pdfbox.text.PDFTextStripper(){@Override protected void writeString(String fragment,List<org.apache.pdfbox.text.TextPosition> positions)throws java.io.IOException{glyphs.addAll(positions);super.writeString(fragment,positions);}};stripper.setStartPage(page);stripper.setEndPage(page);stripper.getText(pdf);
        // This renderer's font metrics can make PDFBox line grouping interleave the following line.
        // Use independent physical glyph baselines, not decoder fragments or destination dictionaries.
        glyphs.sort(Comparator.comparingDouble(org.apache.pdfbox.text.TextPosition::getYDirAdj).thenComparingDouble(org.apache.pdfbox.text.TextPosition::getXDirAdj));return glyphs;
    }
}
