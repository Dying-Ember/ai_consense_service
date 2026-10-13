package com.consense.service.vetting;

import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.domain.VettingFinding;
import com.consense.web.dto.VettingDtos.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingSampleReportAlignmentTest {
    @Test void competitionReportStartsWithProjectMetadataThenNumberedFiveColumnComments() throws Exception {
        VettingReportWriter writer=new VettingReportWriter();
        FindingVO sct=finding("SCT","05 - SCT.docx","SCT1(4)(d)","SCT/5");
        FindingVO ntt=finding("NTT","03 - NTT.docx","1(b)","NTT/2");
        ntt.setReviewRemarks("Not adopted: retain the project-specific requirement.");
        try(XWPFDocument d=new XWPFDocument(new ByteArrayInputStream(writer.docx(project(),Arrays.asList(sct,ntt),null,"zh-Hans")))) {
            assertEquals("Comments on Tender Documentation",d.getParagraphs().get(0).getText());
            List<XWPFTable> comments=d.getTables().stream().filter(t->t.getRow(0).getTableCells().size()==5).collect(Collectors.toList());
            assertEquals(2,comments.size(),"Actual report must use the sample's five-column comments table");
            assertEquals(Arrays.asList("", "Page", "Clause / Item", "Comments", "Remarks by Project Team (if any)"),texts(comments.get(0).getRow(0)));
            assertEquals("1.1",comments.get(0).getRow(1).getCell(0).getText());
            assertEquals("NTT/2",comments.get(0).getRow(1).getCell(1).getText());
            assertEquals("1(b)",comments.get(0).getRow(1).getCell(2).getText());
            assertTrue(comments.get(0).getRow(1).getCell(3).getText().contains("Please clarify this provision."));
            assertFalse(comments.get(0).getRow(1).getCell(3).getText().contains("Not adopted"));
            assertTrue(comments.get(0).getRow(1).getCell(4).getText().contains("Not adopted"));
            assertEquals("3.1",comments.get(1).getRow(1).getCell(0).getText());
            assertFalse(comments.get(0).getRow(1).getCell(3).getText().contains("中文不应覆盖英文报告"));
            assertTrue(d.getTables().stream().anyMatch(t->t.getText().contains("APPENDIX U TO SPECIFICATION")||t.getText().contains("APU.A010")));
        }
    }
    @Test void sameSourceKeyKeepsActualDocumentIdentityAndSourceEvidenceWinsOverReference() {
        FindingVO f=finding("SCC","10 - SCC.docx","SCC1","SCC/1");
        FindingEvidence src=f.getEvidence().get(0);
        FindingEvidence ref=new FindingEvidence("baseline","standard-id","SCC","06_Standard SCC.docx","P1","SCC1","Reference quote",true,"standardhash",null);
        f.setEvidence(Arrays.asList(ref,src));
        List<VettingReportPresentation.Table> tables=VettingReportPresentation.comments(Collections.singletonList(f),null);
        assertTrue(tables.get(0).getTitle().contains("10 - SCC.docx"));
        assertFalse(tables.get(0).getTitle().contains("06_Standard"));
    }
    @Test void checklistNeverMarksAbsentFindingsAsFulfilledAndRetainsAllTemplateRequirements() {
        List<VettingReportPresentation.Table> tables=VettingReportPresentation.checklist(Collections.emptyList());
        assertEquals(69,tables.stream().mapToInt(t->t.getRows().size()).sum());
        String all=tables.stream().map(t->t.getTitle()+t.getRows()).collect(Collectors.joining("\n"));
        assertTrue(all.contains("BILL NO. 1"));assertTrue(all.contains("BILL NO. 2"));
        assertTrue(all.contains("ANNEX 1"));assertTrue(all.contains("APU.A010"));
        assertFalse(all.contains("20240539"));assertFalse(all.contains("CA/10"));
        assertTrue(tables.stream().flatMap(t->t.getRows().stream()).allMatch(r->r.get(2).contains("Not checked")));
        List<VettingReportPresentation.Table> manual=VettingReportPresentation.checklist(Arrays.asList(
            new VettingChecklistCatalog.Result("APP-I-001","fulfilled","Checked against supplied specification."),
            new VettingChecklistCatalog.Result("APP-I-002","not_applicable","Excluded by the confirmed scope.")));
        assertTrue(manual.get(0).getRows().get(0).get(2).startsWith("Checked and fulfilled"));
        assertTrue(manual.get(1).getRows().get(0).get(2).startsWith("Not applicable"));
        assertTrue(manual.get(1).getRows().get(1).get(2).startsWith("Not checked"));
    }
    @Test void actualPdfAndWordHaveBusinessTablesBeforeAuditAppendixAndWriteQaFixtures() throws Exception {
        VettingReportWriter writer=new VettingReportWriter();FindingVO ntt=finding("NTT","03 - NTT.docx","1(b)","NTT/2");
        byte[] word=writer.docx(project(),Collections.singletonList(ntt),null,"en");
        byte[] pdf=writer.pdf(project(),Collections.singletonList(ntt),null,"en");
        try(PDDocument d=PDDocument.load(pdf)) {
            String text=new PDFTextStripper().getText(d).replaceAll("\\s+"," ");
            assertTrue(text.contains("Comments on Tender Documentation"));
            assertTrue(text.contains("Clause / Item"));assertTrue(text.contains("Remarks by Project Team"));
            assertTrue(text.indexOf("Suggested action")<text.indexOf("Run ID"));
            assertTrue(text.indexOf("APPENDIX I")<text.indexOf("APPENDIX II"));
            assertTrue(text.contains("Current-Contract-9"));assertFalse(text.contains("20240539"));
        }
        Path qa=Paths.get("target","report-sample-alignment-qa");Files.createDirectories(qa);
        Files.write(qa.resolve("competition-format.docx"),word);Files.write(qa.resolve("competition-format.pdf"),pdf);
    }
    @Test void checklistSectionHeaderIsNeverLeftWithoutItsFirstRequirement() throws Exception {
        byte[] pdf=new VettingReportWriter().pdf(project(),Collections.singletonList(finding("NTT","03 - NTT.docx","1(b)","NTT/2")),null,"en");
        try(PDDocument d=PDDocument.load(pdf)) {
            PDFTextStripper stripper=new PDFTextStripper();
            for(int page=1;page<=d.getNumberOfPages();page++) {
                stripper.setStartPage(page);stripper.setEndPage(page);
                String text=stripper.getText(d).replaceAll("\\s+"," ");
                if(text.matches(".*PRE\\.B3 (?:\\(Cont'd\\) )?PSS Clause.*"))
                    assertTrue(text.contains("PRE.B3.020")||text.contains("PRE.B3.040"),"PRE.B3 header orphaned on PDF page "+page);
            }
        }
    }
    @Test void legacyPdfEntryPointUsesSameCommentsTablesAndPreservesHumanRemarks() throws Exception {
        VettingFinding f=new VettingFinding();f.setCode("OLD-F1");f.setFileKey("NTT");f.setRefs("1(b)");f.setPageNo("NTT/2");
        f.setBodyEn("Please clarify this provision.");f.setReviewRemarks("Human reply retained.");f.setAddendumRequired(false);
        try(PDDocument d=PDDocument.load(new VettingPdfWriter(new VettingReportWriter()).write(project(),Collections.singletonList(f),"en"))) {
            String text=new PDFTextStripper().getText(d).replaceAll("\\s+"," ");
            assertTrue(text.contains("Comments on Tender Documentation"));assertTrue(text.contains("Clause / Item"));
            assertTrue(text.contains("Human reply retained."));assertTrue(text.contains("Include in tender addendum: Not required"));
            assertTrue(text.contains("APPENDIX I"));
        }
    }
    private static List<String> texts(XWPFTableRow row){return row.getTableCells().stream().map(XWPFTableCell::getText).collect(Collectors.toList());}
    private Project project(){Project p=new Project();p.setId("current-project");p.setContractNo("Current-Contract-9");p.setNameEn("Current project tender review");return p;}
    private FindingVO finding(String key,String filename,String clause,String page){
        FindingVO f=new FindingVO();f.setCode("internal-"+key);f.setFileKey(key);f.setRefs(clause);f.setPageNo(page);f.setStatus("Open");
        f.setTitle(LocalizedText.of("中文不应覆盖英文报告","中文不應覆蓋英文報告","Review this clause"));
        f.setBody(LocalizedText.of("中文不应覆盖英文报告","中文不應覆蓋英文報告","Please clarify this provision."));
        f.setSuggestion(LocalizedText.same("Please compare with the applicable standard."));
        f.setEvidence(Collections.singletonList(new FindingEvidence("source","tender-"+key,key,filename,page,clause,"Actual original source quotation.",true,"sourcehash",null)));
        return f;
    }
}
