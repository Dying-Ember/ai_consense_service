package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public original-template HTTP seam. Fixtures are uploaded through the real parser and storage. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingTemplateReadingIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final Path STORAGE=Paths.get("target","drafting-template-reading-uploads",DB).toAbsolutePath();
    private static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:draft_template_reading_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->STORAGE.toString());
    }
    @Autowired MockMvc mvc;

    @Test void uploadedDocxSourceReturnsItsOriginalBytesAndFilename() throws Exception {
        String id=project();byte[] uploaded=docx("Uploaded source paragraph.");
        replace(id,"NTT","uploaded template.docx",DOCX,uploaded);
        MockHttpServletResponse response=mvc.perform(get("/api/drafting/{id}/templates/NTT/source",id))
                .andExpect(content().contentTypeCompatibleWith(DOCX)).andReturn().getResponse();
        assertArrayEquals(uploaded,response.getContentAsByteArray());
        assertEquals("inline; filename*=UTF-8''uploaded%20template.docx",response.getHeader("Content-Disposition"));
    }

    @Test void readingIncludesNativeTableParagraphsAndDoesNotChangeTheSourceOrAdoptedValues() throws Exception {
        String id=project();byte[] uploaded=orderedDocx();
        replace(id,"SCT","current edition.docx",DOCX,uploaded);
        mvc.perform(put("/api/drafting/{id}/variables/siteInspectionStartDate",id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"2026-10-17\"}"))
                .andExpect(jsonPath("$.code").value(0));
        String variables=mvc.perform(get("/api/drafting/{id}/variables",id)).andReturn().getResponse().getContentAsString();
        String documents=mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/drafting/{id}/templates/SCT/reading",id))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.fileKey").value("SCT"))
                .andExpect(jsonPath("$.data.fileName").value("current edition.docx"))
                .andExpect(jsonPath("$.data.sourceHash").value(sha256(uploaded)))
                .andExpect(jsonPath("$.data.format").value("docx"))
                .andExpect(jsonPath("$.data.catalogueSourceVerified").value(false))
                .andExpect(jsonPath("$.data.paragraphs.length()").value(4))
                .andExpect(jsonPath("$.data.paragraphs[0].id").value("word/document.xml#/w:document[1]/w:body[1]/w:p[1]"))
                .andExpect(jsonPath("$.data.paragraphs[0].ordinal").value(1))
                .andExpect(jsonPath("$.data.paragraphs[0].text").value("Before table\tsecond part\nnext line"))
                .andExpect(jsonPath("$.data.paragraphs[1].id").value("word/document.xml#/w:document[1]/w:body[1]/w:tbl[1]/w:tr[1]/w:tc[1]/w:p[1]"))
                .andExpect(jsonPath("$.data.paragraphs[1].ordinal").value(2))
                .andExpect(jsonPath("$.data.paragraphs[1].text").value("Inside table"))
                .andExpect(jsonPath("$.data.paragraphs[2].ordinal").value(3))
                .andExpect(jsonPath("$.data.paragraphs[2].text").value(""))
                .andExpect(jsonPath("$.data.paragraphs[3].ordinal").value(4))
                .andExpect(jsonPath("$.data.paragraphs[3].text").value("After table"));
        assertArrayEquals(uploaded,mvc.perform(get("/api/drafting/{id}/templates/SCT/source",id))
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(variables,mvc.perform(get("/api/drafting/{id}/variables",id)).andReturn().getResponse().getContentAsString());
        assertEquals(documents,mvc.perform(get("/api/drafting/{id}/documents",id)).andReturn().getResponse().getContentAsString());
    }

    @Test void verifiedCatalogueEditionDependsOnCurrentBytesAndFileKeyInsteadOfFilename() throws Exception {
        String sourceDir=System.getProperty("consense.acceptance.sourceDir");
        assumeTrue(sourceDir!=null,"Actual competition sourceDir required for verified edition acceptance");
        String id=project();String[] keys={"NTT","SCT","SCC"};
        String[] names={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
        String[] hashes={"60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"};
        for(int i=0;i<keys.length;i++) {
            replace(id,keys[i],"renamed source.docx",DOCX,Files.readAllBytes(Paths.get(sourceDir,names[i])));
            mvc.perform(get("/api/drafting/{id}/templates/{key}/reading",id,keys[i]))
                    .andExpect(jsonPath("$.data.catalogueSourceVerified").value(true))
                    .andExpect(jsonPath("$.data.sourceHash").value(hashes[i]));
        }
        byte[] replacement=docx("Replacement keeps the filename and must lose historical paragraph ordinals.");
        replace(id,"NTT","renamed source.docx",DOCX,replacement);
        mvc.perform(get("/api/drafting/{id}/templates/NTT/reading",id))
                .andExpect(jsonPath("$.data.catalogueSourceVerified").value(false))
                .andExpect(jsonPath("$.data.sourceHash").value(sha256(replacement)))
                .andExpect(jsonPath("$.data.paragraphs.length()").value(1))
                .andExpect(jsonPath("$.data.paragraphs[0].text").value("Replacement keeps the filename and must lose historical paragraph ordinals."));
        replace(id,"SCT",names[1],DOCX,Files.readAllBytes(Paths.get(sourceDir,names[0])));
        mvc.perform(get("/api/drafting/{id}/templates/SCT/reading",id)).andExpect(jsonPath("$.data.catalogueSourceVerified").value(false));
    }

    @Test void uploadedPdfHasHonestReadingMetadataAndTheExistingPreviewStillReturnsARealPdf() throws Exception {
        String id=project();byte[] uploaded;
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=new org.apache.pdfbox.pdmodel.PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());pdf.save(out);uploaded=out.toByteArray();
        }
        replace(id,"NTT","uploaded source.pdf","application/pdf",uploaded);
        assertArrayEquals(uploaded,mvc.perform(get("/api/drafting/{id}/templates/NTT/source",id))
                .andExpect(content().contentTypeCompatibleWith("application/pdf")).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/drafting/{id}/templates/NTT/reading",id))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.format").value("pdf"))
                .andExpect(jsonPath("$.data.sourceHash").value(sha256(uploaded)))
                .andExpect(jsonPath("$.data.catalogueSourceVerified").value(false))
                .andExpect(jsonPath("$.data.paragraphs.length()").value(0));
        byte[] preview=mvc.perform(get("/api/drafting/{id}/templates/NTT/preview.pdf",id))
                .andExpect(content().contentTypeCompatibleWith("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(uploaded,preview);
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=org.apache.pdfbox.pdmodel.PDDocument.load(preview)) {assertEquals(1,pdf.getNumberOfPages());}
    }

    @Test void unsupportedSourceHasAnExplicitFormatAndRetainsTheOriginalEvidence() throws Exception {
        String id=project();byte[] uploaded="Unsupported source still has its original evidence.".getBytes(StandardCharsets.UTF_8);
        replace(id,"SCC","uploaded source.txt","text/plain",uploaded);
        mvc.perform(get("/api/drafting/{id}/templates/SCC/reading",id))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.format").value("unsupported"))
                .andExpect(jsonPath("$.data.fileName").value("uploaded source.txt"))
                .andExpect(jsonPath("$.data.sourceHash").value(sha256(uploaded)))
                .andExpect(jsonPath("$.data.catalogueSourceVerified").value(false))
                .andExpect(jsonPath("$.data.paragraphs.length()").value(0));
        assertArrayEquals(uploaded,mvc.perform(get("/api/drafting/{id}/templates/SCC/source",id))
                .andExpect(content().contentTypeCompatibleWith("application/octet-stream")).andReturn().getResponse().getContentAsByteArray());
    }

    @Test void anUnavailableStoredSourceTellsTheUserToUploadTheTemplateAgain() throws Exception {
        String id=project();replace(id,"NTT","test-only unavailable.docx",DOCX,docx("Test-only missing-file simulation."));
        // Simulate only the file-system boundary in this test's isolated storage root.
        Path stored;
        try(Stream<Path> files=Files.walk(STORAGE.resolve(id))) {stored=files.filter(Files::isRegularFile).findFirst().get();}
        Files.delete(stored);
        for(String endpoint:new String[]{"source","reading"}) {
            mvc.perform(get("/api/drafting/{id}/templates/NTT/"+endpoint,id))
                    .andExpect(jsonPath("$.code").value(4011))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SOURCE_UNAVAILABLE")))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Upload")));
        }
    }

    @Test void missingAndCorruptSourcesHaveExplicitErrorsAndKeepTheirRawEvidence() throws Exception {
        String id=project();
        for(String endpoint:new String[]{"source","reading"}) {
            mvc.perform(get("/api/drafting/{id}/templates/NTT/"+endpoint,id))
                    .andExpect(jsonPath("$.code").value(4011))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SOURCE_NOT_UPLOADED")));
        }
        byte[] brokenDocx="This is not a DOCX package.".getBytes(StandardCharsets.UTF_8);
        replace(id,"NTT","broken.docx",DOCX,brokenDocx);
        mvc.perform(get("/api/drafting/{id}/templates/NTT/reading",id))
                .andExpect(jsonPath("$.code").value(4013))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SOURCE_DOCX_INVALID")));
        assertArrayEquals(brokenDocx,mvc.perform(get("/api/drafting/{id}/templates/NTT/source",id))
                .andExpect(content().contentTypeCompatibleWith("application/octet-stream")).andReturn().getResponse().getContentAsByteArray());
        byte[] brokenPdf="%PDF-1.7\nThis PDF is damaged.".getBytes(StandardCharsets.UTF_8);
        replace(id,"SCT","broken.pdf","application/pdf",brokenPdf);
        mvc.perform(get("/api/drafting/{id}/templates/SCT/reading",id))
                .andExpect(jsonPath("$.code").value(4013))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SOURCE_PDF_INVALID")));
        assertArrayEquals(brokenPdf,mvc.perform(get("/api/drafting/{id}/templates/SCT/source",id))
                .andReturn().getResponse().getContentAsByteArray());
    }

    @Test void sourceFormatComesFromTheCurrentBytesInsteadOfTheFilenameOrUploadMime() throws Exception {
        String id=project();byte[] uploaded=docx("Native source survives a misleading filename and MIME.");
        replace(id,"SCC","mislabelled.txt","text/plain",uploaded);
        assertArrayEquals(uploaded,mvc.perform(get("/api/drafting/{id}/templates/SCC/source",id))
                .andExpect(content().contentTypeCompatibleWith(DOCX)).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/drafting/{id}/templates/SCC/reading",id))
                .andExpect(jsonPath("$.data.format").value("docx"))
                .andExpect(jsonPath("$.data.paragraphs[0].text").value("Native source survives a misleading filename and MIME."));
    }

    private String project() throws Exception {
        String id="template-reading-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.write(DraftBusinessRules.map("id",id,"nameZhHans","测试专用模板阅读","nameEn","Test-only template reading"))))
                .andExpect(jsonPath("$.code").value(0));
        return id;
    }
    private void replace(String id,String key,String name,String mime,byte[] bytes) throws Exception {
        mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,key)
                .file(new MockMultipartFile("file",name,mime,bytes))).andExpect(jsonPath("$.code").value(0));
    }
    private static byte[] docx(String text) throws Exception {
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText(text);word.write(out);return out.toByteArray();
        }
    }
    private static byte[] orderedDocx() throws Exception {
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            org.apache.poi.xwpf.usermodel.XWPFRun run=word.createParagraph().createRun();
            run.setText("Before table");run.addTab();run.setText("second part");run.addBreak();run.setText("next line");
            word.createTable(1,1).getRow(0).getCell(0).setText("Inside table");
            word.createParagraph();word.createParagraph().createRun().setText("After table");word.write(out);return out.toByteArray();
        }
    }
    private static String sha256(byte[] source) throws Exception {
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(source))hash.append(String.format("%02x",b));return hash.toString();
    }
}
