package com.consense.web;

import com.consense.common.JsonUtils;
import com.consense.domain.SourceDocument;
import com.consense.config.ConsenseProperties;
import com.consense.service.sources.CanonicalCurrentSourceReader;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalEvidenceException;
import com.consense.service.advice.AdviceService;
import com.consense.service.vetting.VettingService;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.util.AopTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Select only this class's seven methods: the inherited file-list methods are already covered. */
class BusinessSourceJpaBoundaryTest extends VettingFileCatalogJpaHttpTest {
    @Autowired AdviceService advice;
    @Autowired VettingService vetting;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsenseProperties props;
    @Autowired CanonicalCurrentSourceReader current;
    @Autowired CanonicalEvidenceService registration;

    SourceDocument complete(String name,String key){
        SourceDocument d=source(name,key,"Source literal \uD83D\uDE00\t with trailing text " + String.join("",Collections.nCopies(2000,"full contract ")));
        d.setStructuredContentJson("[ {\"text\":\"source literal\",\"location\":\"original paragraph 301\"} ]" + String.join("",Collections.nCopies(4000," ")));
        d.setStoragePath("authored-isolated-original/path.docx");d.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        d.setSizeBytes(17071L);d.setCreatedAt(Instant.parse("2026-10-10T01:02:03Z"));d.setPageCount(31);d.setOcrUsed(true);
        d.setParseCoverageJson("{\"complete\":false,\"limitations\":[\"original unknown\"]}");
        d.setParseMessage("original partial message");d.setParseStatus("PARTIAL");d.setReviewRole("tender");d.setDocumentScope("package");
        d.setSourceSha256(String.join("",Collections.nCopies(64,"a")));
        d.setOerIdentityJson("{\"sourceSha256\":\""+d.getSourceSha256()+"\",\"sourcePurpose\":\"project_document\",\"eligibleModules\":[\"vetting\",\"advisory\"]}");
        return documents.saveAndFlush(d);
    }
    void businessSql(){
        List<String> statements=new ArrayList<>();
        for(String s:SqlCapture.statements){String lower=s.toLowerCase(Locale.ROOT);if(lower.startsWith("select ")&&lower.contains(" from source_document "))statements.add(lower);}
        assertEquals(1,statements.size(),statements.toString());String sql=statements.get(0);
        assertFalse(sql.contains("native_pdf_evidence_json"),"business source read hydrated unused native archive: "+sql);
        assertTrue(sql.contains("text_content"),"complete business text must remain");
        assertTrue(sql.contains("structured_content_json"),"complete business structure must remain");
        assertTrue(sql.contains("oer_identity_json"),"source routing identity must remain");
        assertEquals(0,emf.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount());
        System.out.println("BUSINESS_SOURCE_EXACT_SQL "+sql);
    }
    void exact(SourceDocument expected,SourceDocument actual){
        assertEquals(expected.getId(),actual.getId());assertEquals(expected.getProjectId(),actual.getProjectId());
        assertEquals(expected.getCategory(),actual.getCategory());assertEquals(expected.getFileKey(),actual.getFileKey());assertEquals(expected.getFileName(),actual.getFileName());
        assertEquals(expected.getContentType(),actual.getContentType());assertEquals(expected.getSizeBytes(),actual.getSizeBytes());assertEquals(expected.getStoragePath(),actual.getStoragePath());
        assertEquals(expected.getSourceSha256(),actual.getSourceSha256());assertEquals(expected.getOerIdentityJson(),actual.getOerIdentityJson());
        assertEquals(expected.getReviewRole(),actual.getReviewRole());assertEquals(expected.getDocumentScope(),actual.getDocumentScope());
        assertEquals(expected.getParseStatus(),actual.getParseStatus());assertEquals(expected.getParseMessage(),actual.getParseMessage());
        assertEquals(expected.getPageCount(),actual.getPageCount());assertEquals(expected.getOcrUsed(),actual.getOcrUsed());assertEquals(expected.getCreatedAt(),actual.getCreatedAt());
        assertEquals(expected.getTextContent(),actual.getTextContent());assertEquals(expected.getStructuredContentJson(),actual.getStructuredContentJson());assertEquals(expected.getParseCoverageJson(),actual.getParseCoverageJson());
        assertEquals(VettingCorpus.sourceHash(expected),VettingCorpus.sourceHash(actual));
        assertNull(actual.getNativePdfEvidenceJson(),"business snapshot must not contain the unused archive");
    }
    @Test void adviceSourcesReachesRealJpaWithoutNativeArchiveHydration()throws Exception{
        SourceDocument expected=complete("10 - SCC.docx","SCC");clear();
        JsonNode r=JsonUtils.parse(mvc.perform(get("/api/advice/"+project+"/sources")).andReturn().getResponse().getContentAsString());
        assertEquals(0,r.path("code").asInt(),r.toString());assertEquals(1,r.path("data").size());
        assertEquals(VettingCorpus.sourceHash(expected),r.path("data").get(0).path("sourceHash").asText());businessSql();
    }
    @Test void freshAdviceSelectionPreservesCompleteInputsAndReadsChangedRevision(){
        SourceDocument expected=complete("10 - SCC.docx","SCC");clear();
        List<SourceDocument> first=ReflectionTestUtils.invokeMethod((Object)AopTestUtils.getTargetObject(advice),"freshSelectedDocuments",project,"fullset");
        assertEquals(1,first.size());exact(expected,first.get(0));businessSql();
        String newText=expected.getTextContent()+"\nNEW ORIGINAL END";
        String newStructure=expected.getStructuredContentJson()+"\n\t";
        jdbc.update("update source_document set text_content=?, structured_content_json=? where id=?",newText,newStructure,expected.getId());
        clear();List<SourceDocument> second=ReflectionTestUtils.invokeMethod((Object)AopTestUtils.getTargetObject(advice),"freshSelectedDocuments",project,"fullset");
        assertEquals(newText,second.get(0).getTextContent());assertEquals(newStructure,second.get(0).getStructuredContentJson());
        assertNotEquals(VettingCorpus.sourceHash(first.get(0)),VettingCorpus.sourceHash(second.get(0)));businessSql();
    }
    @Test void vettingSourceSetPreservesCompleteVerificationInputsAndStoredArchive(){
        SourceDocument expected=complete("10 - SCC.docx","SCC");String archive=expected.getNativePdfEvidenceJson();clear();
        List<SourceDocument> selected=ReflectionTestUtils.invokeMethod((Object)AopTestUtils.getTargetObject(vetting),"sourceSet",project);
        assertEquals(1,selected.size());exact(expected,selected.get(0));businessSql();
        assertEquals(archive,jdbc.queryForObject("select native_pdf_evidence_json from source_document where id=?",String.class,expected.getId()));
        org.mockito.Mockito.verifyNoInteractions(externalLlm,actualParser,actualOer);
    }
    @Test void businessSelectionKeepsFailedSupersessionAndModuleExclusions(){
        complete("10 - SCC.docx","SCC");SourceDocument newer=complete(" 10 - scc.DOCX ","SCC");newer.setParseStatus("FAILED");newer.setTextContent(null);documents.saveAndFlush(newer);
        SourceDocument excluded=complete("07 - GCC.pdf","GCC");excluded.setOerIdentityJson("{\"sourceSha256\":\""+excluded.getSourceSha256()+"\",\"sourcePurpose\":\"project_document\",\"eligibleModules\":[\"advisory\"]}");documents.saveAndFlush(excluded);
        clear();List<SourceDocument> selected=ReflectionTestUtils.invokeMethod((Object)AopTestUtils.getTargetObject(vetting),"sourceSet",project);
        assertEquals(1,selected.size());assertEquals(newer.getId(),selected.get(0).getId());assertEquals("FAILED",selected.get(0).getParseStatus());assertNull(selected.get(0).getTextContent());businessSql();
        clear();List<SourceDocument> adviceSelected=ReflectionTestUtils.invokeMethod((Object)AopTestUtils.getTargetObject(advice),"freshSelectedDocuments",project,"fullset");
        assertEquals(2,adviceSelected.size());assertEquals(newer.getId(),adviceSelected.get(0).getId());businessSql();
    }
    SourceDocument actualOriginal(String name)throws Exception{
        SourceDocument d=complete(name,"SCC");String bytes="Authored source original for isolated source gates "+d.getId();
        Path root=Paths.get(props.getStorageRoot());Files.createDirectories(root);Path original=root.resolve("source-"+d.getId()+".docx");
        Files.write(original,bytes.getBytes(StandardCharsets.UTF_8));d.setStoragePath(original.toAbsolutePath().toString());d.setSourceSha256(VettingCorpus.hash(bytes));
        d.setOerIdentityJson("{\"sourceSha256\":\""+d.getSourceSha256()+"\",\"sourcePurpose\":\"project_document\",\"eligibleModules\":[\"drafting\",\"vetting\",\"advisory\"]}");
        return documents.saveAndFlush(d);
    }
    void registrationSql(boolean archive){
        List<String> rows=new ArrayList<>();for(String s:SqlCapture.statements)if(s.toLowerCase(Locale.ROOT).startsWith("select ")&&s.contains(" from source_document "))rows.add(s.toLowerCase(Locale.ROOT));
        assertEquals(2,rows.size(),rows.toString());
        assertEquals(archive,rows.stream().anyMatch(s->s.contains("native_pdf_evidence_json")),"registration source query archive boundary "+rows);
        assertTrue(rows.stream().anyMatch(s->s.contains("text_content")&&s.contains("structured_content_json")));
        long loads=emf.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount();
        if(archive)assertEquals(1,loads);else assertEquals(0,loads);
        System.out.println("REGISTRATION_SOURCE_EXACT_SQL "+rows);
    }
    @Test void normalRegisterRejectsStaleParsedHashWithoutHydratingArchive()throws Exception{
        SourceDocument d=actualOriginal("10 - SCC.docx");String sha=String.join("",Collections.nCopies(64,"b"));clear();
        CanonicalEvidenceException rejected=assertThrows(CanonicalEvidenceException.class,()->registration.registerReviewed(project,d.getId(),
            new CanonicalEvidenceService.ReviewedRegistration(sha,d.getSourceSha256(),sha,sha,sha,sha,sha,"advisory")));
        assertEquals("SOURCE_REVIEW_REVISION_STALE",rejected.getMessage());registrationSql(false);
    }
    @Test void draftingCurrentDocumentRetainsItsEntityAndArchive()throws Exception{
        SourceDocument d=actualOriginal("10 - SCC.docx");clear();SourceDocument fresh=current.currentDocument(project,d.getId(),"drafting");
        assertEquals(d.getNativePdfEvidenceJson(),fresh.getNativePdfEvidenceJson());assertEquals(d.getStructuredContentJson(),fresh.getStructuredContentJson());registrationSql(true);
    }
    @Test void currentSourceStillRejectsChangedOriginalBytes()throws Exception{
        SourceDocument d=actualOriginal("10 - SCC.docx");Files.write(Paths.get(d.getStoragePath()),"changed original bytes".getBytes(StandardCharsets.UTF_8));clear();
        CanonicalEvidenceException rejected=assertThrows(CanonicalEvidenceException.class,()->current.currentDocument(project,d.getId(),"vetting"));
        assertEquals("SOURCE_ORIGINAL_BYTES_CHANGED",rejected.getMessage());registrationSql(false);
    }
}
