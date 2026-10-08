package com.consense.service.vetting;

import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingPacketReportTest {
    @Test void actualWordAndPdfShowAuthorityPendingFailureBudgetAndLegacyUnknown() throws Exception {
        Project project=new Project();project.setId("packet-report-fixture");project.setNameEn("Packet coverage fixture");project.setContractNo("TEST");
        CoverageVO coverage=new CoverageVO(0,0,Collections.emptyList(),Collections.emptyList());SemanticTopicVO current=new SemanticTopicVO();current.setTopicIndex(1);current.setTopic("Original observed source request");current.setStatus("completed_empty");current.setGlobalCallStatus("completed_empty");current.setAggregateReviewStatus("partial");current.setSourceRequestCount(7);current.setPendingSourceRequestCount(3);current.setExtraPacketCount(2);current.setFailedPacketCount(1);current.setNotSubmittedPacketCount(1);
        SemanticPacketVO failed=new SemanticPacketVO();failed.setPacketIndex(1);failed.setStatus("failed");failed.setFailureKind("output_budget_exhausted");failed.setInputBudgetStatus("observed_tokens");SemanticPacketVO unsubmitted=new SemanticPacketVO();unsubmitted.setPacketIndex(2);unsubmitted.setStatus("not_submitted_budget_unknown");unsubmitted.setInputBudgetStatus("budget_unknown");current.setPacketAudits(Arrays.asList(failed,unsubmitted));
        SemanticTopicVO old=new SemanticTopicVO();old.setTopicIndex(2);old.setTopic("Legacy global decode only");old.setStatus("completed");coverage.setSemanticTopics(Arrays.asList(current,old));VettingJobVO job=new VettingJobVO();job.setId("fixture-run");job.setCoverage(coverage);
        VettingReportWriter writer=new VettingReportWriter();Path out=Paths.get("target/packet-report-qa");Files.createDirectories(out);
        for(String lang:Arrays.asList("en","zh-Hans","zh-Hant")) {
            byte[] docx=writer.docx(project,Collections.emptyList(),job,lang),pdf=writer.pdf(project,Collections.emptyList(),job,lang);String word,pdfText;
            try(XWPFDocument d=new XWPFDocument(new ByteArrayInputStream(docx))){word=d.getParagraphs().stream().map(p->p.getText()).collect(Collectors.joining("\n"));}
            try(PDDocument d=PDDocument.load(pdf)){pdfText=new PDFTextStripper().getText(d);PDFRenderer renderer=new PDFRenderer(d);for(int page=0;page<d.getNumberOfPages();page++)ImageIO.write(renderer.renderImageWithDPI(page,100),"png",out.resolve(lang+"-page-"+(page+1)+".png").toFile());}
            Files.write(out.resolve(lang+".docx"),docx);Files.write(out.resolve(lang+".pdf"),pdf);
            for(String text:Arrays.asList(word,pdfText)){String compact=text.replaceAll("\\s+","");assertTrue(compact.contains("partial"));assertTrue(compact.contains("completed_empty"));assertTrue(compact.contains("scope_unknown_legacy"));assertTrue(compact.contains("3/7"));assertTrue(compact.contains("budget_unknown"));assertTrue(compact.contains("output_budget_exhausted"));assertFalse(compact.contains("confirmedconsistent"));}
            if("en".equals(lang)){assertTrue(word.contains("Global call status (call/decode only)"));assertTrue(word.contains("does not confirm the contract has no issues"));}
        }
        assertEquals("completed_empty",current.getStatus());assertEquals("partial",current.getAggregateReviewStatus());assertNull(old.getAggregateReviewStatus());
    }
}
