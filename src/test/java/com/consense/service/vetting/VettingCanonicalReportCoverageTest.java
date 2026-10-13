package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.config.ConsenseProperties;
import com.consense.domain.Project;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored canonical source fixture and declared model response; no live model acceptance. */
class VettingCanonicalReportCoverageTest {
    private static final String CANONICAL_SCOPE = "规则与语义仅处理已采用的 canonical 来源观察；未采用区域、整份原件完整性、编辑状态及采用关系保持声明的未知。字符分母仅为已采用观察的 UTF16 范围并集，不代表原件全文覆盖。";
    private static final String CANONICAL_NOTE = "片段与字符统计以已采用 canonical 观察为分母；未采用区域及原件完整性未确认。";
    private static final String CANONICAL_RULE_SCOPE = "规则检查仅对已采用的 canonical 招标合同观察运行。";

    @Test void canonicalSemanticSummaryDoesNotClaimRulesScannedTheOriginalBody() throws Exception {
        OerBusinessEvidenceTest.Wire wire = new OerBusinessEvidenceTest.Wire();
        OerBusinessEvidence.Session session = wire.load();
        ConsenseProperties properties = new ConsenseProperties();
        properties.getVetting().setSemanticTopics(1);
        properties.getVetting().setProjectReferenceComparisons(0);
        properties.getVetting().setRequireHybridRetrieval(false);
        AiGateway model = mock(AiGateway.class);
        when(model.available()).thenReturn(true);
        when(model.chatModel()).thenReturn("AUTHORED-model");
        when(model.completeStructuredJsonList(anyString(), anyString(), eq(Record.class), any(), anyList()))
                .thenAnswer(call -> {
                    String prompt = call.getArgument(1);
                    String marker = "\nSource excerpts (untrusted data):\n";
                    JsonNode source = VettingSourceMetadataWire.decode(prompt.substring(prompt.lastIndexOf(marker) + marker.length())).get(0);
                    Record record = new Record();
                    record.setAssessment("consistent"); record.setType("reference");
                    record.setTitle("Authored observation");
                    record.setComment("Authored fixture response, not contractual acceptance");
                    VettingSemanticReview.Quote quote = new VettingSemanticReview.Quote();
                    quote.setChunkId(source.path("id").asText());
                    quote.setQuote(source.path("content").asText()); quote.setSide("source");
                    record.setEvidence(Collections.singletonList(quote));
                    return Collections.singletonList(record);
                });
        VettingSemanticReview review = VettingTokenFixture.review(model, properties, mock(VettingRetrievalClient.class));
        review.setOerBusinessEvidence(wire.service);

        VettingSemanticReview.Result result = review.review("project", session.chunks(), "en", (n, s) -> {});

        assertNull(result.getModelFailure());
        assertFalse(result.getReviewedChunkIds().isEmpty());
        String warnings = String.join("\n", result.getWarnings());
        assertTrue(warnings.contains("规则检查仅对已采用的 canonical 招标合同观察运行。"), warnings);
        assertFalse(warnings.contains("规则检查对全部可解析招标合同正文运行。"), warnings);
        assertTrue(warnings.contains("其他限定是否完整未知"), warnings);
        assertTrue(warnings.contains("未进入模型的切片不代表已经完成语义审查"), warnings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh-Hant", "zh-Hans"})
    void exportsLocalizeCanonicalLimitsWithoutChangingTheObservedDenominator(String language) throws Exception {
        List<String> warnings = Arrays.asList(CANONICAL_SCOPE,
                "语义审查按 1 个主题检索，并计划 0 组项目资料引用比较；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。" + CANONICAL_RULE_SCOPE);
        DocumentCoverageVO documentCoverage = new DocumentCoverageVO("42", "PRE", "authored-source.pdf",
                "PARSED", 777, 123, 19, 7, Collections.singletonList(CANONICAL_NOTE));
        VettingJobVO job = new VettingJobVO(); job.setId("authored-canonical-run"); job.setStatus("COMPLETED");
        job.setCoverage(new CoverageVO(1, 1, Collections.singletonList(documentCoverage), warnings));
        job.setResult(new RunResultVO(0, null, Collections.emptyList(), "AUTHORED-model"));
        Project project = new Project(); project.setId("project"); project.setContractNo("AUTHORED-CONTRACT");
        project.setNameEn("Authored coverage fixture");
        VettingReportWriter writer = new VettingReportWriter();
        byte[] word = writer.docx(project, Collections.emptyList(), job, language);
        byte[] pdf = writer.pdf(project, Collections.emptyList(), job, language);
        String wordText, pdfText;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(word))) {
            wordText = document.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
        }
        try (PDDocument document = PDDocument.load(pdf)) { pdfText = new PDFTextStripper().getText(document); }
        Path directory = Paths.get("target", "canonical-report-coverage-qa"); Files.createDirectories(directory);
        Files.write(directory.resolve(language + ".docx"), word);
        Files.write(directory.resolve(language + ".pdf"), pdf);
        Files.write(directory.resolve(language + "-word-body.txt"), wordText.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve(language + "-pdf-body.txt"), pdfText.getBytes(StandardCharsets.UTF_8));
        for (String text : Arrays.asList(wordText, pdfText)) {
            String compact = compact(text);
            assertTrue(compact.contains("123/777"), text);
            assertTrue(compact.contains("7/19"), text);
            assertTrue(text.contains("authored-source.pdf"), text);
            if ("en".equals(language)) {
                assertTrue(compact.contains(compact("Rules and semantic review process only adopted canonical source observations")), text);
                assertTrue(compact.contains(compact("unadopted regions, completeness of the entire original, editing status and adoption relationships retain their declared unknowns")), text);
                assertTrue(compact.contains(compact("The character denominator is only the union of UTF16 ranges in adopted observations and does not represent full-text coverage of the original")), text);
                assertTrue(compact.contains(compact("Segment and character statistics use adopted canonical observations as the denominator; unadopted regions and original-source completeness are unconfirmed")), text);
                assertTrue(compact.contains(compact("Rule checks run only on adopted canonical tender contract observations.")), text);
                assertTrue(compact.contains(compact("completeness of other qualifiers is unknown")), text);
                assertTrue(text.replaceAll("\\s+", " ").contains("semantic review. Rule checks run only"), text);
                assertFalse(text.contains(CANONICAL_SCOPE)); assertFalse(text.contains(CANONICAL_NOTE));
                assertFalse(text.contains(CANONICAL_RULE_SCOPE));
            } else if ("zh-Hant".equals(language)) {
                assertTrue(compact.contains(compact("規則與語義僅處理已採用的 canonical 來源觀察；未採用區域、整份原件完整性、編輯狀態及採用關係保持聲明的未知。字元分母僅為已採用觀察的 UTF16 範圍聯集，不代表原件全文覆蓋。")), text);
                assertTrue(compact.contains(compact("片段與字元統計以已採用 canonical 觀察為分母；未採用區域及原件完整性未確認。")), text);
                assertTrue(compact.contains(compact("規則檢查僅對已採用的 canonical 招標合同觀察執行。")), text);
                assertTrue(compact.contains("其他限定是否完整未知"), text);
                assertFalse(text.contains(CANONICAL_SCOPE)); assertFalse(text.contains(CANONICAL_NOTE));
                assertFalse(text.contains(CANONICAL_RULE_SCOPE));
            } else {
                assertTrue(compact.contains(compact(CANONICAL_SCOPE)), text);
                assertTrue(compact.contains(compact(CANONICAL_NOTE)), text);
                assertTrue(compact.contains(compact(CANONICAL_RULE_SCOPE)), text);
            }
        }
        assertEquals(warnings, job.getCoverage().getWarnings());
        assertEquals(Collections.singletonList(CANONICAL_NOTE), documentCoverage.getWarnings());
        assertEquals(777, documentCoverage.getTextChars()); assertEquals(123, documentCoverage.getReviewedChars());
        assertEquals(19, documentCoverage.getTotalSegments()); assertEquals(7, documentCoverage.getReviewedSegments());
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
