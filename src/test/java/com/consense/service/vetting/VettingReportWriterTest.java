package com.consense.service.vetting;

import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class VettingReportWriterTest {
    private final VettingReportWriter writer = new VettingReportWriter();

    @Test void exportedEvidenceLocationIsDistinctFromMeaningAndPreservesHumanRecords() throws Exception {
        FindingVO located = finding("LOCATED", "Handled");
        located.setReviewRemarks("The project team disputes this generated conclusion.");
        located.setActionTaken("Source comparison recorded; no wording amendment made.");
        located.setAddendumRequired(false);
        located.setReviewUpdatedAt(Instant.parse("2026-10-03T02:00:00Z"));
        FindingVO partial = finding("PARTIAL", "Assigned"); partial.setVerification("partial");
        partial.getEvidence().get(1).setLocated(false);
        FindingVO unlocated = finding("UNLOCATED", "Open"); unlocated.setVerification("unverified");
        unlocated.getEvidence().forEach(e -> e.setLocated(false));
        java.util.List<FindingVO> findings = Arrays.asList(located, partial, unlocated);
        VettingJobVO job = job();
        String unknown = "主题 7 局部比较窗口：预算略过 12 组；引用目标或邻接限定未补齐的片段 3 个。窗口不代表完整条款，未知限定须人工核对。";
        job.getCoverage().setWarnings(Arrays.asList("Missing BQ volumes", unknown));
        Path directory = Paths.get("target", "report-location-qa"); Files.createDirectories(directory);
        for (String lang : Arrays.asList("en", "zh-Hans", "zh-Hant")) {
            byte[] wordBytes = writer.docx(project(), findings, job, lang);
            byte[] pdfBytes = writer.pdf(project(), findings, job, lang);
            String word, pdf;
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(wordBytes))) {
                word = document.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
            }
            try (PDDocument document = PDDocument.load(pdfBytes)) { pdf = new PDFTextStripper().getText(document); }
            Files.write(directory.resolve(lang + ".docx"), wordBytes);
            Files.write(directory.resolve(lang + ".pdf"), pdfBytes);
            Files.write(directory.resolve(lang + "-word-body.txt"), word.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(directory.resolve(lang + "-pdf-body.txt"), pdf.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String text : Arrays.asList(word, pdf)) {
                String compact = text.replaceAll("\\s+", "");
                assertTrue(compact.contains("Theprojectteamdisputesthisgeneratedconclusion."));
                assertTrue(compact.contains("Sourcecomparisonrecorded;nowordingamendmentmade."));
                assertTrue(compact.contains("2026-10-03T02:00:00Z"));
                assertTrue(compact.contains("Sourceclausequotation")); assertTrue(compact.contains("Comparisonclausequotation"));
                assertTrue(compact.contains("MissingBQvolumes"));
                if ("en".equals(lang)) {
                    assertTrue(compact.contains("Evidencelocation:Evidencelocated"));
                    assertTrue(compact.contains("Evidencelocation:Evidencepartlylocated"));
                    assertTrue(compact.contains("Evidencelocation:Evidenceawaitinglocation"));
                    assertTrue(compact.contains("itdoesnotconfirmafinding'smeaningorconclusion"));
                    assertTrue(compact.contains("Reviewstatus:Handled")); assertTrue(compact.contains("Reviewstatus:Assigned"));
                    assertTrue(compact.contains("Reviewstatus:Open")); assertTrue(compact.contains("Includeintenderaddendum:Notrequired"));
                    assertTrue(compact.contains("Includeintenderaddendum:Undecided"));
                    assertTrue(compact.contains("unknownqualifiersrequirehumanreview"));
                    assertFalse(text.contains("Verified")); assertFalse(text.contains("Partly verified"));
                } else if ("zh-Hans".equals(lang)) {
                    assertTrue(compact.contains("证据定位:证据已定位")); assertTrue(compact.contains("证据定位:部分证据已定位"));
                    assertTrue(compact.contains("证据定位:证据待定位"));
                    assertTrue(compact.contains("不代表发现的内容或结论已经确认"));
                    assertTrue(compact.contains("人工状态:已处理")); assertTrue(compact.contains("人工状态:已分派"));
                    assertTrue(compact.contains("人工状态:待处理")); assertTrue(compact.contains("是否需纳入招标补遗:不需要"));
                    assertTrue(compact.contains("是否需纳入招标补遗:待决定"));
                    assertTrue(compact.contains("未知限定须人工核对"));
                    assertFalse(text.contains("已核验")); assertFalse(text.contains("部分核验"));
                } else {
                    assertTrue(compact.contains("證據定位:證據已定位")); assertTrue(compact.contains("證據定位:部分證據已定位"));
                    assertTrue(compact.contains("證據定位:證據待定位"));
                    assertTrue(compact.contains("不代表發現的內容或結論已經確認"));
                    assertTrue(compact.contains("人工狀態:已處理")); assertTrue(compact.contains("人工狀態:已分派"));
                    assertTrue(compact.contains("人工狀態:待處理")); assertTrue(compact.contains("是否需納入招標補遺:不需要"));
                    assertTrue(compact.contains("是否需納入招標補遺:待決定"));
                    assertTrue(compact.contains("未知限定須人工核對"));
                    assertFalse(text.contains("已核驗")); assertFalse(text.contains("部分核驗"));
                }
            }
        }
        assertEquals("verified", located.getVerification()); assertEquals("Handled", located.getStatus());
        assertEquals(false, located.getAddendumRequired()); assertNull(partial.getAddendumRequired());
        assertNull(unlocated.getReviewRemarks()); assertNull(unlocated.getReviewUpdatedAt());
        assertEquals(Arrays.asList("Missing BQ volumes", unknown), job.getCoverage().getWarnings());
    }

    @Test void evidenceLabelsStayWithTheirOpeningQuotationAcrossPageBoundaries() throws Exception {
        for (int padding = 0; padding < 24; padding++) {
            FindingVO item = finding("PAGINATION", "Open");
            item.setReviewRemarks(String.join("\n", Collections.nCopies(padding, "Project team review context retained in the exported report.")));
            item.getEvidence().get(0).setSide("layout-source");
            item.getEvidence().get(0).setQuote("BEGIN_SOURCE_QUOTATION " + String.join(" ", Collections.nCopies(120,
                    "The original source quotation must remain complete even when it continues on another page.")) + " END_SOURCE_QUOTATION");
            item.getEvidence().get(1).setSide("layout-reference");
            item.getEvidence().get(1).setQuote("BEGIN_REFERENCE_QUOTATION " + String.join(" ", Collections.nCopies(120,
                    "The corresponding reference quotation also retains all original source text.")) + " END_REFERENCE_QUOTATION");
            try (PDDocument document = PDDocument.load(writer.pdf(project(), Collections.singletonList(item), job(), "en"))) {
                PDFTextStripper stripper = new PDFTextStripper();
                String all = stripper.getText(document);
                assertTrue(all.contains("END_SOURCE_QUOTATION")); assertTrue(all.contains("END_REFERENCE_QUOTATION"));
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page); stripper.setEndPage(page);
                    String text = stripper.getText(document);
                    if (text.contains("Evidence [layout-source]")) assertTrue(text.contains("BEGIN_SOURCE_QUOTATION"),
                            "Source label orphaned at padding " + padding + ", page " + page);
                    if (text.contains("Evidence [layout-reference]")) assertTrue(text.contains("BEGIN_REFERENCE_QUOTATION"),
                            "Reference label orphaned at padding " + padding + ", page " + page);
                }
            }
        }
    }

    @Test void teamRepliesAndAllThreeAddendumDecisionsAreExportedIndependentlyOfStatus() throws Exception {
        FindingVO required = finding("REQUIRED", "Handled");
        required.setReviewRemarks("Accepted after project team review.\nPreserve the cited exception.");
        required.setActionTaken("Revised the reference in Addendum 2."); required.setAddendumRequired(true);
        required.setReviewUpdatedAt(java.time.Instant.parse("2026-10-02T03:00:00Z"));
        FindingVO notRequired = finding("NOT_REQUIRED", "Assigned"); notRequired.setAddendumRequired(false);
        notRequired.setReviewRemarks("Not adopted: the obligations concern different objects.");
        FindingVO undecided = finding("UNDECIDED", "Handled");
        java.util.List<FindingVO> findings = Arrays.asList(required, notRequired, undecided);
        String word;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(writer.docx(project(), findings, job(), "en")))) {
            word = document.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
        }
        String pdf;
        try (PDDocument document = PDDocument.load(writer.pdf(project(), findings, job(), "en"))) { pdf = new PDFTextStripper().getText(document); }
        for (String text : Arrays.asList(word, pdf)) {
            assertTrue(text.contains("Remarks by project team")); assertTrue(text.contains("Action taken"));
            assertTrue(text.contains("Accepted after project team review.")); assertTrue(text.contains("Preserve the cited exception."));
            assertTrue(text.contains("Revised the reference in Addendum 2.")); assertTrue(text.contains("different objects."));
            assertTrue(text.contains("Include in tender addendum: Required"));
            assertTrue(text.contains("Include in tender addendum: Not required"));
            assertTrue(text.contains("Include in tender addendum: Undecided"), "Handled alone does not decide addendum inclusion");
            assertTrue(text.contains("2026-10-02T03:00:00Z")); assertTrue(text.contains("Source clause quotation"));
        }
    }

    @Test
    void wordRetainsBothSidesAnchorsReviewStatusAndCoverageWarnings() throws Exception {
        byte[] bytes = writer.docx(project(), Collections.singletonList(finding("F1", "Handled")), job(), "en");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
            assertTrue(text.contains("Contract number: 20250101"));
            assertTrue(text.contains("Review status: Handled"));
            assertTrue(text.contains("Clause NTT10"));
            assertTrue(text.contains("Source clause quotation"));
            assertTrue(text.contains("Comparison clause quotation"));
            assertTrue(text.contains("baseline.docx"));
            assertTrue(text.contains("Missing BQ volumes"));
            assertTrue(text.contains("Unparsed scan pages"));
            assertEquals("Title", document.getParagraphs().get(0).getStyle());
        }
    }

    @Test
    void englishPdfPaginatesLongEvidenceWithoutDroppingFinalText() throws Exception {
        FindingVO finding = finding("F1", "Assigned");
        String text = String.join(" ", Collections.nCopies(1500, "The contractor shall clarify this clause in the tender package.")) + " END_OF_LONG_EVIDENCE";
        finding.getEvidence().get(0).setQuote(text);
        try (PDDocument document = PDDocument.load(writer.pdf(project(), Collections.singletonList(finding), job(), "en"))) {
            assertTrue(document.getNumberOfPages() > 5);
            String extracted = new PDFTextStripper().getText(document);
            assertTrue(extracted.contains("END_OF_LONG_EVIDENCE"));
            assertTrue(extracted.contains("Comparison clause quotation"));
            assertTrue(extracted.contains("Review status: Assigned"));
            assertTrue(extracted.contains("Page " + document.getNumberOfPages()));
        }
    }

    @Test
    void completedTaskWithZeroFindingsStillExportsItsLimits() throws Exception {
        try (PDDocument document = PDDocument.load(writer.pdf(project(), Collections.emptyList(), job(), "en"))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("No findings were recorded"));
            assertTrue(text.contains("Missing BQ volumes"));
            assertTrue(text.contains("Model: fixture-model"));
        }
    }

    @Test
    void sameProtocolKeyUsesPrimaryEvidenceDocumentIdentityAndActualFilename() throws Exception {
        FindingVO tender = finding("TENDER", "Open");
        tender.setFileKey("OTHER"); // The actual primary source key takes precedence.
        tender.setEvidence(Arrays.asList(
                new FindingEvidence("source", "tender-id", "SCC", "tender-scc.docx", null, "SCC1", "Tender-only quotation", true, "tender-hash", null),
                new FindingEvidence("baseline", "standard-id", "SCC", "standard-scc.docx", null, "SCC1", "Comparison quotation", true, "standard-hash", null)));
        FindingVO standard = finding("STANDARD", "Open");
        standard.setFileKey("SCC");
        standard.setEvidence(Collections.singletonList(new FindingEvidence("source", "standard-id", "SCC", "standard-scc.docx", null, "SCC2", "Standard-only quotation", true, "standard-hash", null)));
        VettingJobVO job = job();
        job.setCoverage(new CoverageVO(2, 2, Arrays.asList(
                new DocumentCoverageVO("standard-id", "SCC", "standard-scc.docx", "PARSED", 100, 100, 1, 1, Collections.emptyList()),
                new DocumentCoverageVO("tender-id", "SCC", "tender-scc.docx", "PARSED", 100, 100, 1, 1, Collections.emptyList())), Collections.emptyList()));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(writer.docx(project(), Arrays.asList(tender, standard), job, "en")))) {
            String text = document.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
            assertEquals(Arrays.asList("Document SCC  tender-scc.docx", "Document SCC  standard-scc.docx"),
                    document.getParagraphs().stream().map(p -> p.getText()).filter(t -> t.startsWith("Document ") && !t.equals("Document coverage")).collect(Collectors.toList()));
            int tenderHeader = text.indexOf("Document SCC  tender-scc.docx");
            int standardHeader = text.indexOf("Document SCC  standard-scc.docx");
            assertTrue(text.indexOf("TENDER  ") > tenderHeader && text.indexOf("TENDER  ") < standardHeader);
            assertTrue(text.indexOf("STANDARD  ") > standardHeader);
            assertTrue(text.contains("Tender-only quotation"));
            assertTrue(text.contains("Standard-only quotation"));
        }
        try (PDDocument document = PDDocument.load(writer.pdf(project(), Arrays.asList(tender, standard), job, "en"))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.indexOf("Document SCC  tender-scc.docx") < text.indexOf("TENDER  "));
            assertTrue(text.indexOf("Document SCC  standard-scc.docx") < text.indexOf("STANDARD  "));
            assertTrue(text.contains("Tender-only quotation"));
            assertTrue(text.contains("Standard-only quotation"));
        }
    }

    @Test
    void filenameIdentifiesDifferentSourcesWhenDocumentIdsAreAbsent() throws Exception {
        FindingVO first = finding("FIRST", "Open");
        first.setEvidence(Collections.singletonList(new FindingEvidence("source", null, "OTHER", "first.docx", null, "A1", "First quotation", true, "hash-a", null)));
        FindingVO second = finding("SECOND", "Open");
        second.setEvidence(Collections.singletonList(new FindingEvidence("source", null, "OTHER", "second.docx", null, "A1", "Second quotation", true, "hash-b", null)));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(writer.docx(project(), Arrays.asList(first, second), null, "en")))) {
            assertEquals(Arrays.asList("Document OTHER  first.docx", "Document OTHER  second.docx"),
                    document.getParagraphs().stream().map(p -> p.getText()).filter(t -> t.startsWith("Document ") && !t.equals("Document coverage")).collect(Collectors.toList()));
        }
    }

    @Test
    void legacyRecordOnlyUsesFilenameFromUniqueCoverageKey() throws Exception {
        FindingVO legacy = finding("LEGACY", "Open"); legacy.setEvidence(Collections.emptyList());
        VettingJobVO job = job();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(writer.docx(project(), Collections.singletonList(legacy), job, "en")))) {
            assertEquals(Collections.singletonList("Document NTT  NTT.docx"),
                    document.getParagraphs().stream().map(p -> p.getText()).filter(t -> t.startsWith("Document ") && !t.equals("Document coverage")).collect(Collectors.toList()));
        }
        job.setCoverage(new CoverageVO(2, 2, Arrays.asList(
                new DocumentCoverageVO("1", "NTT", "first-ntt.docx", "PARSED", 100, 100, 1, 1, Collections.emptyList()),
                new DocumentCoverageVO("2", "NTT", "second-ntt.docx", "PARSED", 100, 100, 1, 1, Collections.emptyList())), Collections.emptyList()));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(writer.docx(project(), Collections.singletonList(legacy), job, "en")))) {
            assertEquals(Collections.singletonList("Document NTT"),
                    document.getParagraphs().stream().map(p -> p.getText()).filter(t -> t.startsWith("Document ") && !t.equals("Document coverage")).collect(Collectors.toList()));
        }
    }

    @Test
    void localWindowLimitsKeepTheirCountsAndTranslateWithoutMutatingSnapshot() throws Exception {
        String warning = "主题 7 局部比较窗口：预算略过 12 组；引用目标或邻接限定未补齐的片段 3 个。窗口不代表完整条款，未知限定须人工核对。";
        VettingJobVO job = job(); job.getCoverage().setWarnings(Collections.singletonList(warning));
        for (String lang : Arrays.asList("en", "zh-Hant", "zh-Hans")) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(writer.docx(project(), Collections.emptyList(), job, lang)))) {
                String text = document.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
                if ("en".equals(lang)) {
                    assertTrue(text.contains("Topic 7 local comparison window: 12 groups omitted by the budget"));
                    assertTrue(text.contains("not fully included for 3 chunks"));
                    assertTrue(text.contains("unknown qualifiers require human review"));
                    assertFalse(text.contains(warning));
                } else if ("zh-Hant".equals(lang)) {
                    assertTrue(text.contains("主題 7 局部比較窗口：預算略過 12 組"));
                    assertTrue(text.contains("未補齊的片段 3 個"));
                } else assertTrue(text.contains(warning));
            }
        }
        assertEquals(Collections.singletonList(warning), job.getCoverage().getWarnings());
    }

    @Test
    void systemWarningsTranslateCountsAndDiagnosticsWhileQuotesAndFilenamesStayExact() throws Exception {
        String fileName = "报价$notes(2).docx";
        java.util.List<String> warnings = Arrays.asList(
                "资料类型：项目事实资料",
                "语义仅覆盖 7/13270 片段，其他片段未进入成功的模型调用。",
                "主题 12 未完成语义检查：opaque$reason(3).txt",
                "主题 9 的一条模型发现未通过逐字证据校验，已剔除。",
                "主题 13 的一条模型记录未通过证据或适用性校验，未采纳。",
                "无法连接 127.0.0.1:11434，请确认本地服务已启动（Read timed out）",
                "语义审查按 16 个主题检索；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。规则检查对全部可解析正文运行。",
                "语义审查按 16 个主题检索，并计划 3 组项目资料引用比较；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。",
                "项目资料引用 QRT99 未形成比较：tender_target_not_located；不能推断条款缺失或不适用。",
                "项目资料引用 QRT71 未形成比较：ambiguous_tender_source_or_scope；不能推断条款缺失或不适用。",
                "项目资料比较未提交：QRT72 (comparison limit)；预算或比较数量限制不代表已审查。",
                "项目资料引用 QRT98 未形成比较：未定位到对应招标条款；不能推断条款缺失或不适用。",
                "项目资料引用 QRT73 未形成比较：对应招标来源或范围未确认；不能推断条款缺失或不适用。",
                "项目资料比较 QRT74 未提交：超过本次比较数量限制；未提交材料不代表已审查。",
                "项目资料比较 QRT75 未提交：完整招标条款及项目资料超过单次上下文预算；未提交材料不代表已审查。",
                "规则检查对全部可解析招标合同正文运行。",
                "主题 15 局部比较窗口：预算略过 17 组、仅部分上下文提交 6 组；引用目标或邻接限定未补齐的片段 9 个。窗口不代表完整条款，未知限定须人工核对。",
                "主观风险按严重程度排序后限制为 4 条，另有 2 条未列入。");
        VettingJobVO job = job(); job.getCoverage().setWarnings(warnings);
        job.getCoverage().getDocuments().get(0).setFileName(fileName);
        job.getCoverage().getDocuments().get(0).setWarnings(Collections.singletonList("资料类型：标准参考文件"));
        FindingVO finding = finding("SOURCE", "Open");
        String originalQuote = "资料类型：项目事实资料; 主题 9 的一条模型发现未通过逐字证据校验，已剔除。 主题 13 的一条模型记录未通过证据或适用性校验，未采纳。";
        finding.getEvidence().get(0).setQuote(originalQuote);
        finding.setBody(LocalizedText.same("资料类型：招标合同条款"));
        for (String lang : Arrays.asList("en", "zh-Hant", "zh-Hans")) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(writer.docx(project(), Collections.singletonList(finding), job, lang)))) {
                String text = document.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
                assertTrue(text.contains(originalQuote));
                assertTrue(text.contains("资料类型：招标合同条款")); // Raw body never goes through warning localization.
                assertTrue(text.contains(fileName));
                assertTrue(text.contains("opaque$reason(3).txt"));
                assertTrue(text.contains("127.0.0.1:11434"));
                assertTrue(text.contains("Read timed out"));
                if ("en".equals(lang)) {
                    assertTrue(text.contains("Source role: Project facts"));
                    assertTrue(text.contains("Semantic review covered only 7/13270 excerpts"));
                    assertTrue(text.contains("Topic 12 did not complete semantic review: opaque$reason(3).txt"));
                    assertTrue(text.contains("A model finding for topic 9 failed exact source-quotation validation and was excluded."));
                    assertTrue(text.contains("A model record for topic 13 failed evidence or applicability validation and was not accepted."));
                    assertTrue(text.contains("Semantic review retrieves by 16 topics"));
                    assertTrue(text.contains("plans 3 project-reference comparisons"));
                    assertTrue(text.contains("Project reference QRT99 could not be compared because the tender provision was not located"));
                    assertTrue(text.contains("Project reference QRT71 could not be compared because its tender source or scope is ambiguous"));
                    assertTrue(text.contains("Project-reference comparison not submitted: QRT72 (comparison limit)"));
                    assertTrue(text.contains("Project reference QRT98 could not be compared because the tender provision was not located"));
                    assertTrue(text.contains("Project reference QRT73 could not be compared because its tender source or scope is ambiguous"));
                    assertTrue(text.contains("Project-reference comparison QRT74 was not submitted because the comparison limit was reached"));
                    assertTrue(text.contains("Project-reference comparison QRT75 was not submitted because the complete tender and project context exceeds the per-call budget"));
                    assertTrue(text.contains("Rule checks run on all parseable body text."));
                    assertTrue(text.contains("Rule checks run on all parseable tender contract body text."));
                    assertTrue(text.contains("limited to 4 findings; 2 additional findings were omitted"));
                    assertTrue(text.contains("Topic 15 local comparison window: 17 groups omitted by the budget, 6 groups submitted with partial context"));
                    assertTrue(text.contains("not fully included for 9 chunks"));
                } else if ("zh-Hant".equals(lang)) {
                    assertTrue(text.contains("資料類型：項目事實資料"));
                    assertTrue(text.contains("語義僅覆蓋 7/13270 片段"));
                    assertTrue(text.contains("主題 12 未完成語義檢查：opaque$reason(3).txt"));
                    assertTrue(text.contains("主題 9 的一條模型發現未通過逐字證據校驗，已剔除。"));
                    assertTrue(text.contains("主題 13 的一條模型記錄未通過證據或適用性校驗，未採納。"));
                    assertTrue(text.contains("限制為 4 條，另有 2 條未列入"));
                    assertTrue(text.contains("主題 15 局部比較窗口：預算略過 17 組、僅部分上下文提交 6 組"));
                    assertTrue(text.contains("未補齊的片段 9 個"));
                } else for (String warning : warnings) assertTrue(text.contains(warning));
            }
        }
        assertEquals(warnings, job.getCoverage().getWarnings());
        assertEquals(originalQuote, finding.getEvidence().get(0).getQuote());
        assertEquals(fileName, job.getCoverage().getDocuments().get(0).getFileName());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void chineseExportsRemainReadableAcrossPagesAndWriteVisualQaFixtures() throws Exception {
        FindingVO finding = finding("F1", "Handled");
        finding.setTitle(LocalizedText.same("检查合约保证金条款引用"));
        finding.setBody(LocalizedText.same("本次审查只使用提供的源文件，保留人工处理状态。"));
        finding.setSuggestion(LocalizedText.same("请核对标准模板版本，并明确适用的条款编号。"));
        finding.getEvidence().get(0).setQuote(String.join("", Collections.nCopies(130, "这是用于验证中文分页的完整原文证据。请核对施工期与保证金条款的规定，保留所有专业分包的对照文字。")) + "中文末尾标记");
        Path directory = Paths.get("target", "report-qa"); Files.createDirectories(directory);
        byte[] pdf = writer.pdf(project(), Collections.singletonList(finding), job(), "zh-Hans");
        Files.write(directory.resolve("vetting-report-zh.pdf"), pdf);
        Files.write(directory.resolve("vetting-report-zh.docx"), writer.docx(project(), Collections.singletonList(finding), job(), "zh-Hans"));
        try (PDDocument document = PDDocument.load(pdf)) {
            assertTrue(document.getNumberOfPages() >= 3);
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("检查合约保证金条款引用"));
            assertTrue(text.contains("中文末尾标记"));
            assertTrue(text.contains("人工状态: 已处理"));
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                ImageIO.write(renderer.renderImageWithDPI(page, 100), "png", directory.resolve("pdf-page-" + (page + 1) + ".png").toFile());
            }
        }
    }

    private Project project() {
        Project project = new Project(); project.setId("fixture-project"); project.setContractNo("20250101");
        project.setNameZhHans("东涌公营房屋发展项目"); project.setNameZhHant("東涌公營房屋發展項目"); project.setNameEn("Public housing tender review");
        return project;
    }

    private VettingJobVO job() {
        DocumentCoverageVO coverage = new DocumentCoverageVO("1", "NTT", "NTT.docx", "PARSED", 5000, 4000, 5, 4, Collections.singletonList("Unparsed scan pages"));
        VettingJobVO job = new VettingJobVO(); job.setId("fixture-run"); job.setStatus("COMPLETED");
        job.setFinishedAt(Instant.parse("2026-10-02T02:00:00Z"));
        job.setCoverage(new CoverageVO(1, 1, Collections.singletonList(coverage), Collections.singletonList("Missing BQ volumes")));
        job.setResult(new RunResultVO(1, null, Collections.emptyList(), "fixture-model"));
        return job;
    }

    private FindingVO finding(String code, String status) {
        FindingVO finding = new FindingVO(); finding.setCode(code); finding.setFileKey("NTT"); finding.setRefs("NTT10");
        finding.setTypes(Collections.singletonList("f2-iii")); finding.setStatus(status); finding.setSeverity("high"); finding.setScope("inter");
        finding.setTitle(LocalizedText.same("Bond clause references")); finding.setBody(LocalizedText.same("Check the form against the reference."));
        finding.setImpact(LocalizedText.same("An inconsistent reference may leave the applicable form unclear."));
        finding.setSuggestion(LocalizedText.same("Confirm the template version.")); finding.setSource("rule"); finding.setVerification("verified");
        finding.setLocation("NTT10");
        FindingEvidence source = new FindingEvidence("source", "1", "NTT", "NTT.docx", null, "Clause NTT10", "Source clause quotation", true, "hash1", null);
        FindingEvidence target = new FindingEvidence("baseline", "2", "BASELINE", "baseline.docx", "P3", "Clause SCC5", "Comparison clause quotation", true, "hash2", null);
        finding.setEvidence(Arrays.asList(source, target));
        return finding;
    }
}
