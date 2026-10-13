package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.StorageService;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class DraftingTemplateSourceCompatibilityTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:draft_template_compat_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","draft-template-compat-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired DraftingService service;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @Autowired DocumentParser parser;
    @Autowired StorageService storage;
    @MockBean LlmClient model;

    private String project() {Project p=new Project();p.setId("compat-"+UUID.randomUUID());p.setNameZhHans("Compatibility");projects.save(p);return p.getId();}
    private SourceDocument template(String project,String key,byte[] bytes,String cached) {
        StorageService.StoredFile file=storage.store(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE,new MockMultipartFile("file",key+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes));
        DocumentParser.ParsedDocument parsed=parser.parse(key+".docx",bytes); SourceDocument s=new SourceDocument();s.setProjectId(project);s.setFileKey(key);s.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        s.setFileName(key+".docx");s.setStoragePath(file.getPath());s.setParseStatus("PARSED");s.setTextContent(cached==null?parsed.getText():cached);
        s.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));return sources.save(s);
    }
    private byte[] original()throws Exception {
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            XWPFTable t=word.createTable(2,2);t.getRow(0).getCell(0).setText("1.");t.getRow(0).getCell(1).setText("Original complete condition.");
            word.write(out);return out.toByteArray();
        }
    }
    @Test void preUpgradeCacheGeneratesAllThreeWithoutDeletingBlankTopologyOrCallingModel()throws Exception {
        String p=project();byte[] bytes=original();DocumentParser.ParsedDocument parsed=parser.parse("NTT.docx",bytes);
        assertEquals(2,parsed.getBlocks().size());assertEquals(2,parsed.getBlocks().get(1).getWordCells().size());
        String old="1. | Original complete condition.";
        assertNotEquals(old,parsed.getText(),"This is the actual metadata-only blank-row regression.");
        List<SourceDocument> saved=new ArrayList<>();for(String key:DraftBlueprint.draftFileKeys())saved.add(template(p,key,bytes,old));
        Map<Long,String> structuredBefore=new HashMap<>();for(SourceDocument row:saved)structuredBefore.put(row.getId(),row.getStructuredContentJson());
        assertEquals(3,service.generate(p,"en").size());
        for(SourceDocument row:saved) {
            assertEquals(old,sources.findById(row.getId()).orElseThrow().getTextContent(),"Compatibility must not rewrite saved provenance.");
            assertArrayEquals(bytes,storage.read(row.getStoragePath()));
            assertEquals(2,JsonUtils.parse(sources.findById(row.getId()).orElseThrow().getStructuredContentJson()).size());
            assertEquals(structuredBefore.get(row.getId()),sources.findById(row.getId()).orElseThrow().getStructuredContentJson(),"Full saved topology and text channels remain exact.");
        }
        verify(model,never()).chat(anyList());
    }
    @Test void genuineFlatClauseEditStillBlocksGenerationAndPublishesNoDraft()throws Exception {
        String p=project();byte[] bytes=original();
        for(String key:DraftBlueprint.draftFileKeys())template(p,key,bytes,"NTT".equals(key)?"1. | Edited obligation outside the original DOCX.":null);
        BizException failure=assertThrows(BizException.class,()->service.generate(p,"en"));
        assertTrue(failure.getMessage().contains("SOURCE_TEXT_DIVERGENCE"));
        assertTrue(service.listDocuments(p).stream().noneMatch(d->d.isGenerated()));verify(model,never()).chat(anyList());
    }
}
