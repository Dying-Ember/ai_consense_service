package com.consense.web;

import com.consense.ai.AiGateway;
import com.consense.document.DocumentParser;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.sources.ProjectDocumentScope;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real upload/MVC/SQL with authored sources; drawing identity cannot dispatch a detail parser. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class DrawingUploadHttpTest {
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:drawing_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","drawing-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @SpyBean DocumentParser parser;
    @MockBean AiGateway externalModel;
    String project;
    @BeforeEach void setup() {
        Project p=new Project();project="drawing-"+UUID.randomUUID();p.setId(project);p.setNameEn("Authored drawing scope");
        projects.saveAndFlush(p);clearInvocations(parser,externalModel);
    }

    @Test void explicitScannedDrawingStoresOriginalWithoutParserAndBothModulesExcludeIt() throws Exception {
        byte[] original="authored scanned drawing bytes".getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/vetting/"+project+"/package/upload")
                .file(new MockMultipartFile("files","sheet.pdf","application/pdf",original)).param("documentScope","drawing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.parsed").value(0)).andExpect(jsonPath("$.data.failed").value(0));
        assertDrawing(original);
        mvc.perform(get("/api/vetting/"+project+"/files")).andExpect(jsonPath("$.data[0].parsed").value(false));
        mvc.perform(get("/api/advice/"+project+"/sources")).andExpect(jsonPath("$.data[0].exclusionReason").value("drawing_outside_scope"));
        mvc.perform(post("/api/vetting/"+project+"/runs")).andExpect(jsonPath("$.code").value(4101));
        verifyNoInteractions(parser,externalModel);
    }

    @Test void nativeCadAndPdfDrawingMetadataAreIdentifiedBeforeDetailParsing() throws Exception {
        byte[] cad="AC1032 authored native drawing".getBytes(StandardCharsets.US_ASCII);
        upload("sheet.dwg",cad);assertDrawing(cad);
        try(PDDocument pdf=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());pdf.getDocumentInformation().setTitle("Engineering drawings");pdf.save(out);
            upload("attachment.pdf",out.toByteArray());
            assertEquals(2,documents.findByProjectIdAndCategoryOrderByIdAsc(project,SourceDocument.CATEGORY_VETTING_PACKAGE).size());
        }
        verifyNoInteractions(parser,externalModel);
    }

    @Test void contractDrawingRegisterTextRemainsProjectEvidence() throws Exception {
        byte[] text="Contract No: TEST/2046/01\nDrawings register and drawing references form part of this contract.".getBytes(StandardCharsets.UTF_8);
        upload("drawing-register.txt",text);
        SourceDocument source=documents.findByProjectIdAndCategoryOrderByIdAsc(project,SourceDocument.CATEGORY_VETTING_PACKAGE).get(0);
        assertEquals("PARSED",source.getParseStatus());assertTrue(source.getTextContent().contains("Drawings register"));
        assertEquals(1,ProjectDocumentScope.selected(project,Collections.singletonList(source),"fullset").size());
        verify(parser).parse(eq("drawing-register.txt"),any(byte[].class));
    }

    private void upload(String name,byte[] bytes) throws Exception {
        mvc.perform(multipart("/api/vetting/"+project+"/package/upload")
                .file(new MockMultipartFile("files",name,"application/octet-stream",bytes)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }
    private void assertDrawing(byte[] original) throws Exception {
        SourceDocument d=documents.findByProjectIdAndCategoryOrderByIdAsc(project,SourceDocument.CATEGORY_VETTING_PACKAGE).get(0);
        assertEquals("EXCLUDED",d.getParseStatus());assertEquals("DRAWING",d.getFileKey());assertEquals("",d.getTextContent());
        assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));
        assertEquals(OerSourceMetadata.originalSha256(original),d.getSourceSha256());
        assertEquals(Collections.emptyList(),OerSourceMetadata.identity(d).getEligibleModules());
        assertTrue(ProjectDocumentScope.selected(project,Collections.singletonList(d),"fullset","vetting").isEmpty());
        assertTrue(ProjectDocumentScope.selected(project,Collections.singletonList(d),"fullset","advisory").isEmpty());
    }
}
