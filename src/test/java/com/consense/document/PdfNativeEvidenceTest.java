package com.consense.document;

import com.consense.config.ConsenseProperties;
import com.consense.common.JsonUtils;
import com.consense.ocr.OcrClient;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Anonymous generated PDFs: operator evidence, not real-original visual acceptance. */
class PdfNativeEvidenceTest {
    private DocumentParser parser(OcrClient ocr, boolean enabled) {
        ConsenseProperties props = new ConsenseProperties(); props.getOcr().setEnabled(enabled); props.getOcr().setTextLayerMinChars(1);
        return new DocumentParser(props, ocr);
    }
    private static byte[] save(PDDocument document) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); document.save(bytes); return bytes.toByteArray();
    }
    private static void text(PDPageContentStream stream, String text, RenderingMode mode, int x, int y) throws Exception {
        stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12); stream.setRenderingMode(mode); stream.newLineAtOffset(x, y); stream.showText(text); stream.endText();
    }
    private static void scannedMark(PDDocument document, PDPage page, PDPageContentStream stream) throws Exception {
        BufferedImage image = new BufferedImage(400, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0,0,400,240);
        graphics.setColor(Color.BLACK); graphics.drawString("Actual visible scan",20,40); graphics.dispose();
        stream.drawImage(LosslessFactory.createFromImage(document, image), 10, 10, 200, 120);
    }
    @Test void longInvisibleHistoricalSearchLayerDoesNotSatisfyNativeCoverage() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                scannedMark(document, page, stream); text(stream, String.join(" ", Collections.nCopies(50, "OLD WRONG OCR")), RenderingMode.NEITHER, 25, 700);
            }
            bytes = save(document);
        }
        OcrClient ocr = mock(OcrClient.class); DocumentParser.ParsedDocument parsed = parser(ocr, false).parse("scan.pdf", bytes);
        assertEquals("FAILED", parsed.getParseStatus()); assertTrue(parsed.getText().isEmpty()); verifyNoInteractions(ocr);
        PdfPageEvidence raw = parsed.getNativePdfEvidence().get(0); assertTrue(raw.getGlyphs().size() > 200);
        assertTrue(raw.getGlyphs().stream().allMatch(g -> "hidden_rendering_mode".equals(g.getVisibilityStatus()) && !g.isEligibleForNativeText()));
        assertTrue(raw.getGlyphs().stream().allMatch(g -> g.getCharacterCodes() != null && g.getTextMatrix() != null));
        assertEquals(DocumentParseProbe.sha256(bytes), parsed.getSourceSha256());
    }
    @Test void mixedVisibleAndHiddenTextRetainsEveryOriginalGlyphButOnlyVisibleEffectiveText() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                text(stream, "VISIBLE CONTRACT TEXT", RenderingMode.FILL, 30,700); text(stream, "INVISIBLE OLD TEXT", RenderingMode.NEITHER,30,660);
            }
            bytes = save(document);
        }
        DocumentParser.ParsedDocument parsed = parser(mock(OcrClient.class), false).parse("mixed.pdf",bytes);
        assertEquals("PARSED", parsed.getParseStatus()); assertTrue(parsed.getText().contains("VISIBLE CONTRACT TEXT")); assertFalse(parsed.getText().contains("INVISIBLE"));
        assertTrue(parsed.getNativePdfEvidence().get(0).getGlyphs().stream().anyMatch(g -> !g.isEligibleForNativeText()));
        assertTrue(parsed.getBlocks().stream().allMatch(b -> "operator_visible_unverified".equals(b.getNativeVisibilityStatus())));
        assertTrue(parsed.getBlocks().stream().flatMap(b -> b.getPdfGlyphs().stream()).allMatch(PdfPageEvidence.PdfGlyph::isEligibleForNativeText));
    }
    @Test void zeroAlphaHiddenTextAndRotatedCroppedVisibleGlyphCoordinatesAreExplicit() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300,300)); page.setCropBox(new PDRectangle(30,50,200,100)); page.setRotation(90); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document,page)) {
                text(stream,"VISIBLE",RenderingMode.FILL,50,90); stream.saveGraphicsState();
                PDExtendedGraphicsState state = new PDExtendedGraphicsState(); state.setNonStrokingAlphaConstant(0f); stream.setGraphicsStateParameters(state);
                text(stream,"HIDDEN ALPHA",RenderingMode.FILL,50,110); stream.restoreGraphicsState();
            }
            bytes = save(document);
        }
        DocumentParser.ParsedDocument parsed = parser(mock(OcrClient.class),false).parse("rotated.pdf",bytes); PdfPageEvidence raw = parsed.getNativePdfEvidence().get(0);
        assertTrue(parsed.getText().contains("VISIBLE")); assertFalse(parsed.getText().contains("HIDDEN")); assertEquals(90, raw.getRotation());
        assertArrayEquals(new double[]{30,50,200,100},raw.getCropBox()); assertTrue(raw.getGlyphs().stream().anyMatch(g -> "hidden_zero_alpha".equals(g.getVisibilityStatus())));
        for (PdfPageEvidence.PdfGlyph glyph : raw.getGlyphs()) if (glyph.isEligibleForNativeText()) {
            assertNotNull(glyph.getBbox()); for (double value : glyph.getBbox()) assertTrue(value >= 0 && value <= 1);
        }
        assertTrue(raw.getLimitations().stream().anyMatch(s -> s.contains("does not verify raster")));
    }
    @Test void ocrAdoptionPreservesNativeGlyphsAndNativeBlocksSeparately() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document,page)) { scannedMark(document,page,stream); text(stream,"Short native",RenderingMode.FILL,30,700); }
            bytes = save(document);
        }
        OcrClient ocr = mock(OcrClient.class); when(ocr.available()).thenReturn(true); when(ocr.recognize(any())).thenReturn(new OcrClient.OcrResult("Selected OCR output",0.9,Collections.emptyList()));
        DocumentParser.ParsedDocument parsed = parser(ocr,true).parse("mixed-scan.pdf",bytes);
        assertEquals("ocr", parsed.getPages().get(0).getStatus()); assertTrue(parsed.getText().contains("Selected OCR"));
        assertTrue(parsed.getNativePdfEvidence().get(0).getNativeBlocks().stream().anyMatch(b -> b.getText().contains("Short native")));
        assertFalse(parsed.getNativePdfEvidence().get(0).getGlyphs().isEmpty());
    }
    @Test void existingFormValueAndAppearanceAreReadWithoutRegenerationAndConflictsArePreserved() throws Exception {
        byte[] bytes = formPdf("Stored value", "Visible value", false, true);
        DocumentParser.ParsedDocument parsed = parser(mock(OcrClient.class),false).parse("form.pdf",bytes);
        PdfPageEvidence.PdfFormField field = parsed.getNativePdfEvidence().get(0).getFormFields().get(0);
        assertEquals("Stored value",field.getFieldValue()); assertEquals("Visible value",field.getAppearanceValue(),field.getLimitations().toString()); assertTrue(field.isConflict());
        assertEquals("field_appearance_conflict",field.getValueStatus()); assertEquals(Integer.valueOf(1),field.getPageNo());
        assertNotNull(field.getBbox()); assertNotNull(field.getRectanglePdf()); assertTrue(parsed.getCoverage().getNeedsReviewPages().contains(1));
        assertFalse(parsed.getText().contains("Stored value")); assertFalse(parsed.getText().contains("Visible value"));
        assertTrue(field.getAppearanceGlyphs().stream().allMatch(g -> g.getBbox() == null && g.getCoordinateSpace().startsWith("appearance_stream")));
        try (PDDocument original = PDDocument.load(bytes)) {
            PDAcroForm originalForm = new PDAcroForm(original,(COSDictionary)original.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.ACRO_FORM));
            PDField unchanged = originalForm.getField("TestField"); assertEquals("Stored value", unchanged.getValueAsString());
        }
        assertEquals(DocumentParseProbe.sha256(bytes), parsed.getSourceSha256());
    }
    @Test void matchingFormAppearanceCanEnterTextWhileMissingAndHiddenAppearancesCannot() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            DocumentParser.ParsedDocument parsed = parser(mock(OcrClient.class),false).parse("form.pdf",formPdf("Matching value","Matching value",mode==2,mode!=1));
            PdfPageEvidence.PdfFormField field = parsed.getNativePdfEvidence().get(0).getFormFields().get(0);
            if (mode == 0) { assertEquals("appearance_text_unverified", field.getValueStatus()); assertTrue(parsed.getText().contains("Matching value")); }
            else { assertFalse(parsed.getText().contains("Matching value")); assertEquals(mode==1 ? "appearance_missing" : "not_visible",field.getValueStatus()); }
        }
    }
    @Test void legacyConstructorsAndNonPdfOriginalByteHashesRemainCompatible() {
        DocumentParser.ParsedDocument old = new DocumentParser.ParsedDocument("old",Collections.emptyList(),0,false,"old");
        assertNull(old.getSourceSha256()); assertTrue(old.getNativePdfEvidence().isEmpty());
        byte[] first="equal visible text".getBytes(StandardCharsets.UTF_8), second="equal visible text\n".getBytes(StandardCharsets.UTF_8);
        DocumentParser parser=parser(mock(OcrClient.class),false); DocumentParser.ParsedDocument a=parser.parse("a.txt",first), b=parser.parse("b.txt",second);
        assertEquals(a.getText(),b.getText()); assertNotEquals(a.getSourceSha256(),b.getSourceSha256());
    }
    @Test void missingAppearanceIsNotSynthesizedIntoTheOcrRasterFromStoredValue() throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        DocumentParser.ParsedDocument parsed=parser(ocr,false).parse("missing-appearance.pdf",formPdf("Stored but not drawn","unused",false,false,false));
        PdfPageEvidence.PdfFormField field=parsed.getNativePdfEvidence().get(0).getFormFields().get(0);
        assertFalse(field.isAppearancePresent()); assertEquals("Stored but not drawn",field.getFieldValue());
        assertTrue(parsed.getText().isEmpty()); assertEquals("blank",parsed.getPages().get(0).getStatus());
        assertTrue(parsed.getCoverage().getNeedsReviewPages().contains(1)); verifyNoInteractions(ocr);
    }
    @Test void successfulOcrKeepsBlankConflictingMissingMatchingAndNontextWidgetsAsOriginalObservations() throws Exception {
        List<byte[]> fixtures = Arrays.asList(formPdf("Stored value","Visible value",false,true),
                formPdf("Matching value","Matching value",false,true),
                formPdf("Stored but not drawn","unused",false,false), formPdf("","",false,true), checkboxPdf());
        List<String> statuses = Arrays.asList("field_appearance_conflict","appearance_text_unverified",
                "appearance_missing","empty_or_non_text_appearance","empty_or_non_text_appearance");
        for (int i = 0; i < fixtures.size(); i++) {
            byte[] bytes=fixtures.get(i); String originalHash=DocumentParseProbe.sha256(bytes);
            ConsenseProperties props=new ConsenseProperties(); props.getOcr().setTextLayerMinChars(1000);
            OcrClient ocr=mock(OcrClient.class); when(ocr.available()).thenReturn(true);
            when(ocr.recognize(any())).thenReturn(new OcrClient.OcrResult("Selected OCR output",.9,Collections.emptyList()));
            DocumentParser.ParsedDocument parsed=new DocumentParser(props,ocr).parse("form-scan.pdf",bytes);
            assertEquals("ocr",parsed.getPages().get(0).getStatus()); assertEquals("Selected OCR output",parsed.getPages().get(0).getText());
            assertEquals(Collections.singletonList(1),parsed.getCoverage().getNeedsReviewPages());
            List<DocumentBlock> widgets=new ArrayList<>();
            for(DocumentBlock b:parsed.getBlocks())if("pdf_form_widget".equals(b.getKind()))widgets.add(b);
            assertEquals(1,widgets.size(),"OCR must preserve each existing widget exactly once");
            PdfPageEvidence.PdfFormField raw=parsed.getNativePdfEvidence().get(0).getFormFields().get(0);
            DocumentBlock widget=widgets.get(0); assertEquals(JsonUtils.write(raw),JsonUtils.write(widget.getPdfFormField()));
            assertEquals(statuses.get(i),raw.getValueStatus()); assertEquals("native_form",widget.getSource());
            assertEquals(raw.getVisibilityStatus(),widget.getNativeVisibilityStatus()); assertArrayEquals(raw.getBbox(),widget.getBbox());
            assertEquals(i==1?"Matching value":"",widget.getText());
            assertEquals(raw.getAppearanceValue()==null?"":raw.getAppearanceValue(),widget.getOriginalText());
            assertEquals(1,parsed.getNativePdfEvidence().get(0).getNativeBlocks().stream().filter(b->"pdf_form_widget".equals(b.getKind())).count());
            assertEquals(originalHash,parsed.getSourceSha256()); assertEquals(originalHash,DocumentParseProbe.sha256(bytes));
            if(i==4) { assertEquals("Btn",raw.getFieldType()); assertEquals("Yes",raw.getAppearanceState());
                assertEquals("Yes",raw.getFieldValue()); assertTrue(raw.isAppearancePresent()); assertTrue(raw.getAppearanceGlyphs().isEmpty());
                assertNotNull(raw.getAppearanceRawStreamBytesBase64()); }
            verify(ocr).recognize(any());
        }
    }
    private static byte[] checkboxPdf() throws Exception {
        try(PDDocument document=new PDDocument()) {
            PDPage page=new PDPage(); document.addPage(page);
            try(PDPageContentStream stream=new PDPageContentStream(document,page)) { text(stream,"VISIBLE BODY CONTRACT TEXT",RenderingMode.FILL,30,700); }
            PDAcroForm acro=new PDAcroForm(document); document.getDocumentCatalog().setAcroForm(acro);
            PDCheckBox field=new PDCheckBox(acro); field.setPartialName("ExistingChoice"); field.getCOSObject().setName(COSName.V,"Yes"); acro.getFields().add(field);
            PDAnnotationWidget widget=field.getWidgets().get(0); widget.setPage(page); widget.setRectangle(new PDRectangle(30,600,20,20));
            widget.setAppearanceState("Yes"); page.getAnnotations().add(widget);
            PDAppearanceStream ap=new PDAppearanceStream(document); ap.setBBox(new PDRectangle(20,20)); ap.setResources(new PDResources());
            try(PDPageContentStream stream=new PDPageContentStream(document,ap)) { stream.addRect(3,3,10,10); stream.fill(); }
            COSDictionary states=new COSDictionary(); states.setItem(COSName.getPDFName("Yes"),ap);
            PDAppearanceDictionary appearances=new PDAppearanceDictionary(); appearances.getCOSObject().setItem(COSName.N,states); widget.setAppearance(appearances);
            return save(document);
        }
    }
    private static byte[] formPdf(String stored,String visible,boolean hidden,boolean appearance) throws Exception {
        return formPdf(stored,visible,hidden,appearance,true);
    }
    private static byte[] formPdf(String stored,String visible,boolean hidden,boolean appearance,boolean body) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            if(body)try (PDPageContentStream stream = new PDPageContentStream(document,page)) { text(stream,"VISIBLE BODY CONTRACT TEXT",RenderingMode.FILL,30,700); }
            PDAcroForm acro = new PDAcroForm(document); document.getDocumentCatalog().setAcroForm(acro); acro.setNeedAppearances(true);
            PDTextField field = new PDTextField(acro); field.setPartialName("TestField"); field.getCOSObject().setString(COSName.V,stored); acro.getFields().add(field);
            PDAnnotationWidget widget=field.getWidgets().get(0); widget.setPage(page); widget.setRectangle(new PDRectangle(30,600,200,20)); widget.setHidden(hidden); page.getAnnotations().add(widget);
            if (appearance) {
                PDAppearanceStream ap = new PDAppearanceStream(document); ap.setBBox(new PDRectangle(200,20)); ap.setResources(new PDResources());
                try (PDPageContentStream stream = new PDPageContentStream(document,ap)) { text(stream,visible,RenderingMode.FILL,2,5); }
                PDAppearanceDictionary dict = new PDAppearanceDictionary(); dict.setNormalAppearance(ap); widget.setAppearance(dict);
            }
            return save(document);
        }
    }
}
