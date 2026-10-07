package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Generic software fixtures; no competition source text or evaluation transcription is used. */
class OcrQualityPropagationTest {
    private OcrClient availableOcr(String text, double score) {
        OcrClient ocr = mock(OcrClient.class);
        when(ocr.available()).thenReturn(true);
        when(ocr.recognize(any())).thenReturn(new OcrClient.OcrResult(text, score,
                Collections.singletonList(new OcrClient.OcrLine(text, score, new double[]{20, 20, 80, 20}))));
        return ocr;
    }

    @Test void highConfidenceNonemptyOcrStillRequiresOriginalReviewWithoutLosingPhysicalCoverage() throws Exception {
        String text = "Unverified arbitrary scan line (ii";
        OcrClient ocr = availableOcr(text, 1.0);
        DocumentParser.ParsedDocument parsed = new DocumentParser(new ConsenseProperties(), ocr).parse("unknown-scan.pdf", scans(1));
        assertEquals("PARTIAL", parsed.getParseStatus());
        assertTrue(parsed.getCoverage().isComplete(), "All physical pages were extracted, independently of accuracy");
        assertEquals(1, parsed.getCoverage().getParsedPages());
        assertEquals(1, parsed.getCoverage().getOcrPages());
        assertTrue(parsed.getCoverage().getFailedPages().isEmpty());
        JsonNode coverage = JsonUtils.parse(JsonUtils.write(parsed.getCoverage()));
        assertEquals("needs_review", coverage.path("ocrQualityStatus").asText());
        assertEquals("[1]", coverage.path("needsReviewPages").toString());
        assertFalse(coverage.path("ocrQualityPageScopeUnknown").asBoolean());
        assertEquals(text, parsed.getPages().get(0).getText());
        assertEquals(text, parsed.getBlocks().get(0).getText());
        assertArrayEquals(new double[]{.1, .1, .4, .1}, parsed.getBlocks().get(0).getBbox(), .001);
        verify(ocr, times(1)).recognize(any());
    }

    @Test void onlyKnownOcrPagesNeedReviewInNativeBlankScanMixture() throws Exception {
        OcrClient ocr = availableOcr("Independent scanned requirement.", .01);
        DocumentParser.ParsedDocument parsed = new DocumentParser(new ConsenseProperties(), ocr)
                .parse("mixture.pdf", DocumentParseProbeTest.pdf(true));
        assertEquals("PARTIAL", parsed.getParseStatus());
        assertTrue(parsed.getCoverage().isComplete());
        assertEquals(Collections.singletonList(2), parsed.getCoverage().getBlankPages());
        assertEquals("[3]", JsonUtils.parse(JsonUtils.write(parsed.getCoverage())).path("needsReviewPages").toString());
        assertEquals(Arrays.asList("native", "blank", "ocr"), Arrays.asList(parsed.getPages().get(0).getStatus(),
                parsed.getPages().get(1).getStatus(), parsed.getPages().get(2).getStatus()));
        verify(ocr, times(1)).recognize(any());
    }

    @Test void failedPageRemainsFailedWhileSuccessfulOcrPageRequiresReview() throws Exception {
        OcrClient ocr = availableOcr("Extracted uncertain text.", .95);
        when(ocr.recognize(any())).thenReturn(new OcrClient.OcrResult("Extracted uncertain text.", .95, Collections.emptyList()))
                .thenThrow(new IllegalStateException("Synthetic OCR transport failure"));
        DocumentParser.ParsedDocument parsed = new DocumentParser(new ConsenseProperties(), ocr).parse("two-scans.pdf", scans(2));
        assertEquals("PARTIAL", parsed.getParseStatus());
        assertFalse(parsed.getCoverage().isComplete());
        assertEquals(Collections.singletonList(2), parsed.getCoverage().getFailedPages());
        assertEquals("[1]", JsonUtils.parse(JsonUtils.write(parsed.getCoverage())).path("needsReviewPages").toString());
        assertEquals(1, parsed.getCoverage().getOcrPages());
        verify(ocr, times(2)).recognize(any());
    }

    @Test void legacyOcrFlagCannotInventPhysicalReviewPagesOrClaimQualityConfirmation() {
        DocumentParser.ParsedDocument legacy = new DocumentParser.ParsedDocument("Legacy OCR text.",
                Collections.singletonList(new DocumentParser.PageText(1, "Legacy OCR text.")), 4, true, "Historical extraction");
        assertEquals("PARTIAL", legacy.getParseStatus());
        assertTrue(legacy.getCoverage().isComplete(), "Preserve legacy physical extraction declaration");
        JsonNode json = JsonUtils.parse(JsonUtils.write(legacy.getCoverage()));
        assertEquals("needs_review", json.path("ocrQualityStatus").asText());
        assertEquals("[]", json.path("needsReviewPages").toString());
        assertTrue(json.path("ocrQualityPageScopeUnknown").asBoolean(), "Unknown OCR page identity must remain explicit");
    }

    @Test void nativeLegacyAndConfirmedWhitePagesDoNotAcquireOcrQualityClaims() throws Exception {
        DocumentParser.ParsedDocument nativeResult = new DocumentParser.ParsedDocument("Native source.", Collections.emptyList(), 0, false, "Native");
        assertEquals("PARSED", nativeResult.getParseStatus());
        assertTrue(JsonUtils.parse(JsonUtils.write(nativeResult.getCoverage())).path("ocrQualityStatus").isNull());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) { pdf.addPage(new PDPage(new PDRectangle(72, 72))); pdf.save(out); }
        OcrClient ocr = mock(OcrClient.class);
        DocumentParser.ParsedDocument blank = new DocumentParser(new ConsenseProperties(), ocr).parse("blank.pdf", out.toByteArray());
        assertEquals("PARSED", blank.getParseStatus());
        assertTrue(blank.getCoverage().isComplete());
        assertEquals(Collections.singletonList(1), blank.getCoverage().getBlankPages());
        assertEquals("[]", JsonUtils.parse(JsonUtils.write(blank.getCoverage())).path("needsReviewPages").toString());
        verifyNoInteractions(ocr);
    }

    @Test void partialLegacyPageIdentityAndInvalidPageNumbersRemainUnknown() {
        ParseCoverage coverage = new ParseCoverage(); coverage.setComplete(true); coverage.setTotalPages(4);
        coverage.setParsedPages(4); coverage.setOcrPages(3);
        DocumentParser.ParsedDocument saved = new DocumentParser.ParsedDocument("Historical extracted content.",
                Arrays.asList(new DocumentParser.PageText(2, "Known OCR.", "ocr"),
                        new DocumentParser.PageText(99, "Invalid page identity.", "ocr")),
                4, true, "Historical", Collections.emptyList(), coverage);
        assertEquals("PARTIAL", saved.getParseStatus());
        assertEquals(Collections.singletonList(2), coverage.getNeedsReviewPages());
        assertTrue(coverage.isOcrQualityPageScopeUnknown());
        assertEquals(3, coverage.getOcrPages(), "Do not fabricate or rewrite historical OCR page totals");
        ParseCoverage reread = JsonUtils.read(JsonUtils.write(coverage), ParseCoverage.class);
        assertEquals(coverage, reread, "Coverage persists additive quality metadata without changing physical fields");
    }

    @Test void emptyOcrIsFailureNotEvidenceOfAVisuallyBlankPage() throws Exception {
        OcrClient ocr = availableOcr("", 1.0);
        DocumentParser.ParsedDocument parsed = new DocumentParser(new ConsenseProperties(), ocr).parse("visible-mark.pdf", scans(1));
        assertEquals("FAILED", parsed.getParseStatus());
        assertFalse(parsed.getCoverage().isComplete());
        assertEquals(Collections.singletonList(1), parsed.getCoverage().getFailedPages());
        assertTrue(parsed.getCoverage().getBlankPages().isEmpty(), "Only original all-white rendered pixels confirm blank pages");
        assertTrue(parsed.getCoverage().getNeedsReviewPages().isEmpty(), "Failed extraction has no successful OCR page claim");
        verify(ocr, times(1)).recognize(any());
    }

    private byte[] scans(int count) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < count; i++) {
                PDPage page = new PDPage(new PDRectangle(72, 72)); pdf.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
                    stream.setNonStrokingColor(Color.BLACK); stream.addRect(20, 20, 5, 5); stream.fill();
                }
            }
            pdf.save(out);
        }
        return out.toByteArray();
    }
}
