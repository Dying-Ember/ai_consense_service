package com.consense.document;

import com.consense.config.ConsenseProperties;
import com.consense.ai.HttpSupport;
import com.consense.ocr.OcrClient;
import com.consense.ocr.PaddleOcrClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFEndnote;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentParserTest {

    @Test
    void docxNumericStyleIdsResolveToActualHeadingAndContentsNames() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createStyles();
            CTStyle toc = CTStyle.Factory.newInstance(); toc.setStyleId("73"); toc.addNewName().setVal("toc 2");
            CTStyle heading = CTStyle.Factory.newInstance(); heading.setStyleId("84"); heading.addNewName().setVal("heading 4");
            document.getStyles().addStyle(new XWPFStyle(toc)); document.getStyles().addStyle(new XWPFStyle(heading));
            XWPFParagraph contents = document.createParagraph(); contents.setStyle("73"); contents.createRun().setText("XYZ.Z7.ANNEX42.N Source title 9");
            XWPFParagraph body = document.createParagraph(); body.setStyle("84"); body.createRun().setText("XYZ.Z7.ANNEX42.N Source title");
            document.write(output);
        }
        DocumentParser.ParsedDocument result = new DocumentParser(new ConsenseProperties(),mock(OcrClient.class)).parse("styles.docx",output.toByteArray());
        assertEquals("73",result.getBlocks().get(0).getParagraphStyle()); assertEquals("toc 2",result.getBlocks().get(0).getParagraphStyleName());
        assertEquals("84",result.getBlocks().get(1).getParagraphStyle()); assertEquals("heading 4",result.getBlocks().get(1).getParagraphStyleName());
    }

    @Test
    void completelyWhitePhysicalPagesAreParsedWithoutOcrEvenWhenDisabled() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(72, 72)));
            document.addPage(new PDPage(new PDRectangle(72, 72)));
            document.save(output);
        }
        ConsenseProperties properties = new ConsenseProperties(); properties.getOcr().setEnabled(false);
        OcrClient ocr = mock(OcrClient.class);
        DocumentParser.ParsedDocument result = new DocumentParser(properties, ocr).parse("blank.pdf", output.toByteArray());
        assertEquals("PARSED", result.getParseStatus()); assertTrue(result.getCoverage().isComplete());
        assertEquals(2, result.getCoverage().getParsedPages()); assertEquals(java.util.Arrays.asList(1,2), result.getCoverage().getBlankPages());
        assertEquals(0, result.getCoverage().getOcrPages()); assertTrue(result.getCoverage().getFailedPages().isEmpty());
        assertTrue(result.getText().isEmpty()); assertTrue(result.getBlocks().isEmpty());
        assertTrue(result.getPages().stream().allMatch(p -> "blank".equals(p.getStatus())));
        verifyNoInteractions(ocr);
    }

    @Test
    void visibleVectorAndVeryLightMarksAreNotBlankWithoutOcr() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = PDDocument.load(pdf(1, true))) {
            PDPage vector = new PDPage(new PDRectangle(72,72)); document.addPage(vector);
            try (PDPageContentStream stream = new PDPageContentStream(document, vector)) {
                stream.setNonStrokingColor(new Color(254,254,254)); stream.addRect(15,15,4,4); stream.fill();
            }
            document.save(output);
        }
        ConsenseProperties properties = new ConsenseProperties(); properties.getOcr().setEnabled(false);
        OcrClient ocr = mock(OcrClient.class);
        DocumentParser.ParsedDocument result = new DocumentParser(properties, ocr).parse("light-vector.pdf", output.toByteArray());
        assertEquals("PARTIAL", result.getParseStatus()); assertFalse(result.getCoverage().isComplete());
        assertEquals(Collections.singletonList(2), result.getCoverage().getFailedPages());
        assertTrue(result.getCoverage().getBlankPages().isEmpty()); assertEquals(1, result.getCoverage().getParsedPages());
        verifyNoInteractions(ocr);
    }

    @Test
    void visibleScannedTextWithEmptyOcrIsFailedRatherThanBlank() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(72,72)); document.addPage(page);
            BufferedImage image = new BufferedImage(400,200,BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0,0,400,200);
            graphics.setColor(Color.BLACK); graphics.drawString("Visible scanned clause",20,80); graphics.dispose();
            try (PDPageContentStream stream = new PDPageContentStream(document,page)) {
                stream.drawImage(LosslessFactory.createFromImage(document,image),0,0,72,36);
            }
            document.save(output);
        }
        OcrClient ocr = mock(OcrClient.class); when(ocr.available()).thenReturn(true);
        when(ocr.recognize(any())).thenReturn(new OcrClient.OcrResult("",0,Collections.emptyList()));
        DocumentParser.ParsedDocument result = new DocumentParser(new ConsenseProperties(),ocr).parse("scan.pdf",output.toByteArray());
        assertEquals("FAILED",result.getParseStatus()); assertFalse(result.getCoverage().isComplete());
        assertEquals(Collections.singletonList(1),result.getCoverage().getFailedPages());
        assertEquals(0,result.getCoverage().getParsedPages()); assertTrue(result.getCoverage().getBlankPages().isEmpty());
        verify(ocr).recognize(any());
    }

    @Test
    void aSingleExtractedDigitIsNeverConfirmedBlank() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(72,72)); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document,page)) {
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA,5); stream.newLineAtOffset(20,20); stream.showText("1"); stream.endText();
            }
            document.save(output);
        }
        ConsenseProperties properties = new ConsenseProperties(); properties.getOcr().setEnabled(false);
        DocumentParser.ParsedDocument result = new DocumentParser(properties,mock(OcrClient.class)).parse("one-digit.pdf",output.toByteArray());
        assertEquals("PARTIAL",result.getParseStatus()); assertEquals(Collections.singletonList(1),result.getCoverage().getFailedPages());
        assertTrue(result.getCoverage().getBlankPages().isEmpty()); assertTrue(result.getText().contains("1"));
    }

    @Test
    void docxFootnotesAndEndnotesRetainIdsAndExcludeInactiveTextAndSeparators() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BigInteger footnoteId;
        BigInteger endnoteId;
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Main contract clause");
            XWPFFootnote footnote = document.createFootnote();
            footnoteId = footnote.getId();
            XWPFParagraph paragraph = footnote.createParagraph();
            paragraph.createRun().setText("Applicable footnote exception");
            paragraph.getCTP().addNewDel().addNewR().addNewDelText().setStringValue("Deleted footnote alternative");
            XWPFFootnote separator = document.createFootnote();
            separator.getCTFtnEdn().setId(BigInteger.ZERO);
            separator.createParagraph().createRun().setText("Separator is not a provision");
            XWPFEndnote endnote = document.createEndnote();
            endnoteId = endnote.getId();
            XWPFParagraph endParagraph = endnote.createParagraph();
            endParagraph.createRun().setText("Applicable endnote qualification");
            XWPFRun struck = endParagraph.createRun();
            struck.setText("Struck endnote option");
            struck.setStrikeThrough(true);
            document.write(output);
        }
        DocumentParser.ParsedDocument result = new DocumentParser(new ConsenseProperties(), mock(OcrClient.class))
                .parse("notes.docx", output.toByteArray());
        DocumentBlock footnote = result.getBlocks().stream().filter(b -> "footnote".equals(b.getKind())).findFirst().get();
        DocumentBlock endnote = result.getBlocks().stream().filter(b -> "endnote".equals(b.getKind())).findFirst().get();
        assertEquals("word-footnote/" + footnoteId, footnote.getLocation());
        assertEquals("word-endnote/" + endnoteId, endnote.getLocation());
        assertNull(footnote.getPageNo());
        assertNull(endnote.getPageNo());
        assertEquals("Deleted footnote alternative", footnote.getDeletedText());
        assertEquals("Struck endnote option", endnote.getStrikeText());
        assertTrue(result.getText().contains("Applicable footnote exception"));
        assertTrue(result.getText().contains("Applicable endnote qualification"));
        assertFalse(result.getText().contains("Deleted footnote alternative"));
        assertFalse(result.getText().contains("Struck endnote option"));
        assertFalse(result.getText().contains("Separator is not a provision"));
    }

    @Test
    void nativePdfBoxesFollowPhysicalPageRotation() throws Exception {
        byte[] unrotated = pdf(1, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = PDDocument.load(unrotated)) {
            document.getPage(0).setRotation(90);
            document.save(output);
        }
        DocumentParser parser = new DocumentParser(new ConsenseProperties(), mock(OcrClient.class));
        double[] original = parser.parse("native.pdf", unrotated).getBlocks().get(0).getBbox();
        double[] rotated = parser.parse("native.pdf", output.toByteArray()).getBlocks().get(0).getBbox();
        assertArrayEquals(new double[]{1 - original[1] - original[3], original[0], original[3], original[2]}, rotated, .001);
    }

    @Test
    void localOcrProtocolUsesFileMultipartAndPreservesPixelCoordinates() {
        ConsenseProperties.Ocr cfg = new ConsenseProperties.Ocr();
        cfg.setMode("local");
        cfg.setBaseUrl("http://localhost:8868");
        HttpSupport http = mock(HttpSupport.class);
        when(http.get(eq("http://localhost:8868/health"), anyLong())).thenReturn("{\"ok\":true}");
        when(http.postMultipart(eq("http://localhost:8868/ocr"), any(), anyLong()))
                .thenReturn("{\"text\":\"SCT5 Envelope 1\",\"lines\":[{\"text\":\"SCT5 Envelope 1\","
                        + "\"confidence\":0.95,\"bbox\":[10,20,100,30]}],\"imageWidth\":200,\"imageHeight\":200}");
        PaddleOcrClient client = new PaddleOcrClient(cfg, http);
        assertTrue(client.available());
        OcrClient.OcrResult result = client.recognize(new byte[]{1});
        assertEquals(1, result.getLines().size());
        assertEquals("SCT5 Envelope 1", result.getText());
        assertArrayEquals(new double[]{10, 20, 100, 30}, result.getLines().get(0).getBbox());
        verify(http).postMultipart(eq("http://localhost:8868/ocr"), argThat(body ->
                body.part(0).headers().get("Content-Disposition").contains("name=\"file\"")), anyLong());
    }

    @Test
    void mixedPdfUsesOcrOnlyOnMissingPageAndReportsFailure() throws Exception {
        OcrClient ocr = mock(OcrClient.class);
        when(ocr.available()).thenReturn(true);
        when(ocr.recognize(any())).thenThrow(new IllegalStateException("OCR fixture failure"));
        DocumentParser.ParsedDocument result = new DocumentParser(new ConsenseProperties(), ocr)
                .parse("mixed.pdf", pdf(2, true));
        assertEquals("PARTIAL", result.getParseStatus());
        assertEquals(Collections.singletonList(2), result.getCoverage().getFailedPages());
        assertEquals(1, result.getCoverage().getParsedPages());
        assertFalse(result.getCoverage().isComplete());
        assertTrue(result.getText().contains("--- P1 ---"));
        assertEquals("ocr_failed", result.getPages().get(1).getStatus());
        assertTrue(result.getBlocks().stream().anyMatch(b -> b.getPageNo() == 1 && b.getBbox() != null));
        verify(ocr, times(1)).recognize(any());
    }

    @Test
    void scansBeyondSixtyPagesAreNotSilentlySkippedAndBoxesAreNormalized() throws Exception {
        ConsenseProperties props = new ConsenseProperties();
        props.getOcr().setMaxOcrPages(1); // Legacy cap must not make a file appear fully parsed.
        OcrClient ocr = mock(OcrClient.class);
        when(ocr.available()).thenReturn(true);
        when(ocr.recognize(any())).thenReturn(new OcrClient.OcrResult("Tender evidence", .9,
                Collections.singletonList(new OcrClient.OcrLine("Tender evidence", .9, new double[]{20, 20, 80, 20}))));
        DocumentParser.ParsedDocument result = new DocumentParser(props, ocr).parse("scan.pdf", pdf(61, false));
        assertEquals("PARTIAL", result.getParseStatus(), "Full extraction of OCR pages is not accuracy verification");
        assertTrue(result.getCoverage().isComplete(), "Keep the independently meaningful physical coverage guarantee");
        assertEquals("needs_review", result.getCoverage().getOcrQualityStatus());
        assertEquals(61, result.getCoverage().getNeedsReviewPages().size());
        assertEquals(Integer.valueOf(61), result.getCoverage().getNeedsReviewPages().get(60));
        assertFalse(result.getCoverage().isOcrQualityPageScopeUnknown());
        assertEquals(61, result.getCoverage().getOcrPages());
        assertEquals(61, result.getCoverage().getParsedPages());
        assertEquals(61, result.getPages().size());
        assertEquals(Integer.valueOf(61), result.getBlocks().get(60).getPageNo());
        assertArrayEquals(new double[]{.1, .1, .4, .1}, result.getBlocks().get(0).getBbox(), .001);
        verify(ocr, times(61)).recognize(any());
    }

    @Test
    void unavailableOcrCannotReportEmptyScanAsComplete() throws Exception {
        OcrClient ocr = mock(OcrClient.class);
        when(ocr.available()).thenReturn(false);
        DocumentParser.ParsedDocument result = new DocumentParser(new ConsenseProperties(), ocr)
                .parse("scan.pdf", pdf(2, false));
        assertEquals("FAILED", result.getParseStatus());
        assertFalse(result.getCoverage().isComplete());
        assertEquals(2, result.getCoverage().getFailedPages().size());
        assertEquals(0, result.getCoverage().getParsedPages());
        verify(ocr, never()).recognize(any());
    }

    @Test
    void docxRetainsTableAndInactiveRunLocationsWithoutInventingPages() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText("Applicable BSSSC ");
            XWPFRun struck = paragraph.createRun();
            struck.setText("Inactive NSC");
            struck.setStrikeThrough(true);
            document.createTable(1, 2).getRow(0).getCell(0).setText("Contract period");
            document.getTables().get(0).getRow(0).getCell(1).setText("39 months");
            document.write(output);
        }
        DocumentParser.ParsedDocument result = new DocumentParser(new ConsenseProperties(), mock(OcrClient.class))
                .parse("tender.docx", addDeletedParagraph(output.toByteArray()));
        assertEquals("PARSED", result.getParseStatus());
        assertEquals(0, result.getPageCount());
        assertTrue(result.getPages().isEmpty());
        assertTrue(result.getBlocks().stream().allMatch(b -> b.getPageNo() == null));
        assertTrue(result.getText().contains("Applicable BSSSC"));
        assertFalse(result.getText().contains("Inactive NSC"));
        assertFalse(result.getText().contains("Deleted alternative"));
        assertTrue(result.getBlocks().stream().anyMatch(b -> b.getStrikeText().equals("Inactive NSC")));
        assertTrue(result.getBlocks().stream().anyMatch(b -> b.getDeletedText().equals("Deleted alternative")));
        DocumentBlock table = result.getBlocks().stream().filter(b -> "table_row".equals(b.getKind())).findFirst().get();
        assertEquals(2, table.getCells().size());
        assertEquals("39 months", table.getCells().get(1));
        assertTrue(table.getLocation().contains("/table-row/0"));
    }

    private byte[] pdf(int count, boolean nativeFirst) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < count; i++) {
                PDPage page = new PDPage(new PDRectangle(72, 72));
                document.addPage(page);
                if (i == 0 && nativeFirst) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(PDType1Font.HELVETICA, 2);
                        stream.newLineAtOffset(2, 40);
                        stream.showText("GCC14.1 contractor interim statements include all contract value and payment details.");
                        stream.endText();
                    }
                } else {
                    // A page lacking extractable text must still contain visible content
                    // to represent a scan/OCR candidate instead of a confirmed blank page.
                    try (PDPageContentStream stream = new PDPageContentStream(document,page)) {
                        stream.setNonStrokingColor(Color.BLACK); stream.addRect(20,20,5,5); stream.fill();
                    }
                }
            }
            document.save(out);
        }
        return out.toByteArray();
    }

    private byte[] addDeletedParagraph(byte[] docx) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(docx));
             ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                int read;
                while ((read = input.read(buffer)) != -1) data.write(buffer, 0, read);
                byte[] content = data.toByteArray();
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = new String(content, StandardCharsets.UTF_8);
                    xml = xml.replace("</w:body>", "<w:p><w:del w:id=\"1\" w:author=\"Fixture\">"
                            + "<w:r><w:delText>Deleted alternative</w:delText></w:r></w:del></w:p></w:body>");
                    content = xml.getBytes(StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(content);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
