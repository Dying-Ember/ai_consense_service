package com.consense.document;

import com.consense.common.BizException;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DocumentParserZipTest {

    @Test
    void legitimateCompressibleImageBelowOnePercentDoesNotDiscardContractText() throws Exception {
        byte[] image = new byte[106496];
        byte[] prefix = new byte[256];
        new Random(54).nextBytes(prefix);
        System.arraycopy(prefix, 0, image, 0, prefix.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Contract payment provision remains reviewable");
            document.addPictureData(image, Document.PICTURE_TYPE_EMF);
            document.write(output);
        }
        byte[] docx = output.toByteArray();
        double ratio = mediaInflateRatio(docx);
        assertTrue(ratio > .001 && ratio < .01, "Fixture must reproduce the default POI false positive: " + ratio);
        DocumentParser.ParsedDocument result = parser(new ConsenseProperties()).parse("tender.docx", docx);
        assertEquals("PARSED", result.getParseStatus());
        assertTrue(result.getCoverage().isComplete());
        assertTrue(result.getText().contains("Contract payment provision remains reviewable"));
    }

    @Test
    void singleEntryLimitCountsInflatedBytesWithoutDeclaredSize() throws Exception {
        ConsenseProperties props = new ConsenseProperties();
        props.getDocument().setMaxZipEntryBytes(32);
        byte[] zip = archive(new String[]{"word/media/image.emf"}, new int[]{33});
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            assertEquals(-1, input.getNextEntry().getSize());
        }
        rejects(props, zip, "entry expanded bytes exceed 32");
    }

    @Test
    void xmlLimitIsAppliedBeforeXmlMaterialization() throws Exception {
        ConsenseProperties props = new ConsenseProperties();
        props.getDocument().setMaxZipXmlBytes(32);
        rejects(props, archive(new String[]{"word/document.xml"}, new int[]{33}),
                "XML expanded bytes exceed 32");
    }

    @Test
    void totalLimitAccumulatesAcrossEntries() throws Exception {
        ConsenseProperties props = new ConsenseProperties();
        props.getDocument().setMaxZipTotalBytes(64);
        rejects(props, archive(new String[]{"word/media/a.emf", "word/media/b.emf"}, new int[]{32, 33}),
                "total expanded bytes exceed 64");
    }

    @Test
    void entryCountLimitIncludesZeroByteEntries() throws Exception {
        ConsenseProperties props = new ConsenseProperties();
        props.getDocument().setMaxZipEntries(2);
        rejects(props, archive(new String[]{"a", "b", "c"}, new int[]{0, 0, 0}),
                "entry count exceeds 2");
    }

    private DocumentParser parser(ConsenseProperties props) {
        return new DocumentParser(props, mock(OcrClient.class));
    }

    private void rejects(ConsenseProperties props, byte[] bytes, String message) {
        BizException error = assertThrows(BizException.class, () -> parser(props).parse("oversized.docx", bytes));
        assertEquals(4003, error.getCode());
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private byte[] archive(String[] names, int[] lengths) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                zip.write(new byte[lengths[i]]);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private double mediaInflateRatio(byte[] docx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                while (zip.read(buffer) != -1) { /* Reach the ZIP data descriptor. */ }
                if (entry.getName().startsWith("word/media/")) {
                    return entry.getCompressedSize() / (double) entry.getSize();
                }
            }
        }
        fail("No media entry in fixture");
        return 0;
    }
}
