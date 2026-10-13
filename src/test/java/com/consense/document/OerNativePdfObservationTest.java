package com.consense.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Authored PDFs verify raw observation contracts, not actual-original visual or legal acceptance. */
public class OerNativePdfObservationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Map<String, byte[]> code() throws Exception {
        return code(Paths.get("src/main/java/com/consense/document"));
    }
    private static Map<String, byte[]> code(Path directory) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : OerNativePdfObservation.OBSERVER_SOURCES) result.put(name, Files.readAllBytes(directory.resolve(name)));
        return result;
    }
    private static void text(PDPageContentStream stream, String value, RenderingMode mode, float x, float y) throws Exception {
        stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12); stream.setRenderingMode(mode);
        stream.newLineAtOffset(x, y); stream.showText(value); stream.endText();
    }
    private static byte[] save(PDDocument document) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
    }
    private static byte[] fixture() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage first = new PDPage(new PDRectangle(400, 400)); first.setCropBox(new PDRectangle(30, 50, 200, 200));
            first.setRotation(90); first.setUserUnit(2); document.addPage(first);
            try (PDPageContentStream stream = new PDPageContentStream(document, first)) {
                text(stream, "Visible fi cafe", RenderingMode.FILL, 40, 180);
                text(stream, "Hidden", RenderingMode.NEITHER, 40, 150);
                stream.saveGraphicsState(); PDExtendedGraphicsState state = new PDExtendedGraphicsState();
                state.setNonStrokingAlphaConstant(0f); stream.setGraphicsStateParameters(state);
                text(stream, "ZeroAlpha", RenderingMode.FILL, 40, 120); stream.restoreGraphicsState();
                text(stream, "OutCrop", RenderingMode.FILL, 280, 300);
            }
            PDPage second = new PDPage(new PDRectangle(300, 500)); second.setRotation(270); document.addPage(second);
            try (PDPageContentStream stream = new PDPageContentStream(document, second)) { text(stream, "Second page", RenderingMode.FILL, 20, 450); }
            PDAcroForm acro = new PDAcroForm(document); document.getDocumentCatalog().setAcroForm(acro); acro.setNeedAppearances(true);
            addForm(document, acro, first, "Conflict", "Stored value", "  Visible value  ", true, true);
            addForm(document, acro, first, "MissingAP", "Stored only", null, true, false);
            addForm(document, acro, null, "UnknownPage", "Unknown stored", "Unknown appearance", false, true);
            return save(document);
        }
    }
    private static void addForm(PDDocument document, PDAcroForm acro, PDPage page, String name, String stored,
                                String shown, boolean attached, boolean appearance) throws Exception {
        PDTextField field = new PDTextField(acro); field.setPartialName(name); field.getCOSObject().setString(COSName.V, stored); acro.getFields().add(field);
        PDAnnotationWidget widget = field.getWidgets().get(0); widget.setRectangle(new PDRectangle(40, 70, 160, 20));
        if (attached) { widget.setPage(page); page.getAnnotations().add(widget); }
        if (appearance) {
            PDAppearanceStream ap = new PDAppearanceStream(document); ap.setBBox(new PDRectangle(160, 20)); ap.setResources(new PDResources());
            try (PDPageContentStream stream = new PDPageContentStream(document, ap)) { text(stream, shown, RenderingMode.FILL, 2, 5); }
            PDAppearanceDictionary dict = new PDAppearanceDictionary(); dict.setNormalAppearance(ap); widget.setAppearance(dict);
        }
    }

    @Test void literalGlyphsStyleIdentityAndUnknownArePreservedWithoutNormalizationOrAdoption() throws Exception {
        byte[] bytes = fixture(); byte[] before = bytes.clone(); ObjectNode observation = OerNativePdfObservation.observe("authored.pdf", bytes, code(), Arrays.asList(1));
        assertArrayEquals(before, bytes); assertEquals("native_pdf", observation.path("producerKind").asText());
        assertEquals(OerNativePdfObservation.SCHEMA, observation.path("schema").asText());
        assertArrayEquals(bytes, Base64.getDecoder().decode(observation.path("sourceArtifact").path("bytesBase64").asText()));
        assertEquals(OerNativePdfObservation.sha256(bytes), observation.path("source").path("sourceSha256").asText());
        assertEquals("2.0.31", observation.path("observerIdentity").path("dependencyVersions").path("apachePdfBox").asText());
        assertEquals(OerNativePdfObservation.sha256(OerNativePdfObservation.canonicalBytes(observation.path("observerIdentity"))), observation.path("observerIdentitySha256").asText());
        JsonNode page = observation.path("pageObservations").get(0); String all = page.path("glyphOrderChannel").path("text").asText();
        assertEquals("Visible fi cafeHiddenZeroAlphaOutCrop", all); assertEquals(1, observation.path("pageObservations").size());
        assertEquals("not_observed", page.path("lineRelations").path("status").asText());
        assertEquals(0, page.path("nativePdfEvidence").path("nativeBlocks").size());
        boolean hidden = false, alpha = false, outcrop = false;
        for (JsonNode glyph : page.path("nativePdfEvidence").path("glyphs")) {
            assertTrue(glyph.path("characterCodes").isArray()); assertEquals(6, glyph.path("textMatrix").size());
            assertEquals(6, glyph.path("currentTransformationMatrix").size()); assertEquals(10, glyph.path("pdfboxPosition").size());
            assertTrue(glyph.path("fontSize").asDouble() > 0); assertTrue(glyph.path("fontSizeInPt").asDouble() > 0);
            assertEquals("unknown_not_raster_verified", glyph.path("effectiveVisibilityStatus").asText());
            assertTrue(glyph.path("limitations").size() >= 2);
            hidden |= "hidden_rendering_mode".equals(glyph.path("visibilityStatus").asText());
            alpha |= "hidden_zero_alpha".equals(glyph.path("visibilityStatus").asText());
            outcrop |= "outside_crop".equals(glyph.path("visibilityStatus").asText());
        }
        assertTrue(hidden && alpha && outcrop); checkChannel(page.path("glyphOrderChannel"), page.path("nativePdfEvidence").path("glyphs"));
        assertFalse(observation.path("quality").path("humanConfirmed").asBoolean());
        assertFalse(observation.path("quality").path("semanticScopeVerified").asBoolean());
        assertEquals("unknown", observation.path("quality").path("effectiveRenderingStatus").asText());
        assertEquals("not_performed", observation.path("quality").path("canonicalAdoption").asText());
        assertEquals("not_invoked", observation.path("invocation").path("ocr").asText());
    }

    @Test void exactCropRotationUserUnitAndDerivedMatricesRemainDistinctFromRasterProof() throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int angle : new int[]{0, 90, 180, 270}) {
                PDPage page = new PDPage(new PDRectangle(300, 400)); page.setCropBox(new PDRectangle(30, 50, 200, 100));
                page.setUserUnit(2); page.setRotation(angle); document.addPage(page);
            }
            ObjectNode observation = OerNativePdfObservation.observe("frames.pdf", save(document), code(), Arrays.asList(1, 2, 3, 4));
            double[][] normalizedCorners = {{0, 1}, {0, 0}, {1, 0}, {1, 1}};
            for (int i = 0; i < 4; i++) {
                JsonNode frame = observation.path("pageObservations").get(i).path("frame");
                assertEquals(i * 90, frame.path("rotation").asInt()); assertEquals(2, frame.path("userUnit").asDouble());
                assertEquals("[30.0,50.0,200.0,100.0]", frame.path("cropBox").toString());
                double[] mapped = apply(frame.path("rawToRenderedCropNormalized"), 30, 50);
                assertArrayEquals(normalizedCorners[i], mapped, 1e-10);
                assertEquals("derived_page_frame_mapping_not_raster_verified", frame.path("transformStatus").asText());
                assertEquals("unknown_no_raster_produced", frame.path("actualRasterTransformStatus").asText());
            }
        }
    }

    @Test void storedFieldExistingApLiteralBytesConflictAndUnknownPageAreIndependent() throws Exception {
        ObjectNode observation = OerNativePdfObservation.observe("forms.pdf", fixture(), code(), Arrays.asList(1));
        JsonNode fields = observation.path("pageObservations").get(0).path("nativePdfEvidence").path("formFields");
        JsonNode conflict = fields.get(0), missing = fields.get(1); assertEquals("Stored value", conflict.path("fieldValue").asText());
        assertEquals("Visible value", conflict.path("appearanceValue").asText()); assertTrue(conflict.path("conflict").asBoolean());
        assertEquals("  Visible value  ", conflict.path("appearanceGlyphOrderChannel").path("text").asText());
        assertTrue(Base64.getDecoder().decode(conflict.path("appearanceRawStreamBytesBase64").asText()).length > 0);
        String decoded = new String(Base64.getDecoder().decode(conflict.path("appearanceDecodedStreamBytesBase64").asText()), StandardCharsets.ISO_8859_1);
        assertTrue(decoded.contains("Visible value"));
        checkChannel(conflict.path("appearanceGlyphOrderChannel"), conflict.path("appearanceGlyphs"));
        for (JsonNode glyph : conflict.path("appearanceGlyphs")) {
            assertTrue(glyph.path("bbox").isNull()); assertEquals("appearance_stream_local_matrix_no_page_box", glyph.path("coordinateSpace").asText());
        }
        assertFalse(missing.path("appearancePresent").asBoolean()); assertEquals("Stored only", missing.path("fieldValue").asText());
        assertEquals(0, missing.path("appearanceGlyphOrderChannel").path("spans").size());
        assertEquals(1, observation.path("unassignedFormFields").size()); JsonNode unknown = observation.path("unassignedFormFields").get(0);
        assertTrue(unknown.path("pageNo").isNull()); assertEquals("widget_page_unknown", unknown.path("valueStatus").asText());
        assertTrue(unknown.path("appearancePresent").asBoolean()); assertEquals(0, unknown.path("appearanceGlyphs").size());
        assertTrue(unknown.path("appearanceDecodedStreamBytesBase64").isTextual());
        assertEquals("not_observed_widget_page_unknown", unknown.path("appearanceGlyphObservationStatus").asText());
        assertEquals("observed_existing_normal_AP_operators_unreviewed", conflict.path("appearanceGlyphObservationStatus").asText());
        assertTrue(unknown.path("limitations").toString().contains("no physical widget page"));
    }

    @Test void explicitPageSelectionAndCompleteSourceCodeBindingRejectInvalidSelections() throws Exception {
        byte[] bytes = fixture(); Map<String, byte[]> code = code();
        for (List<Integer> pages : Arrays.asList(Arrays.asList(0), Arrays.asList(3), Arrays.asList(1, 1), Arrays.asList(2, 1), new ArrayList<Integer>()))
            assertThrows(IllegalArgumentException.class, () -> OerNativePdfObservation.observe("bad.pdf", bytes, code, pages));
        Map<String, byte[]> missingCode = new LinkedHashMap<>(code); missingCode.remove("PdfNativeSupport.java");
        assertThrows(IllegalArgumentException.class, () -> OerNativePdfObservation.observe("bad.pdf", bytes, code, Arrays.asList((Integer) null)));
        assertThrows(IllegalArgumentException.class, () -> OerNativePdfObservation.observe("bad.pdf", bytes, missingCode, Arrays.asList(1)));
        ObjectNode first = OerNativePdfObservation.observe("same.pdf", bytes, code, Arrays.asList(1));
        ObjectNode second = OerNativePdfObservation.observe("same.pdf", bytes, code, Arrays.asList(2));
        assertEquals(first.path("source").path("sourceSha256"), second.path("source").path("sourceSha256"));
        assertNotEquals(first.path("source").path("sourceRevisionSha256"), second.path("source").path("sourceRevisionSha256"));
        assertEquals(2, second.path("pageObservations").get(0).path("physicalPage").asInt());
        assertEquals("Second page", second.path("pageObservations").get(0).path("glyphOrderChannel").path("text").asText());
        assertEquals(2, second.path("documentFormFieldsOutsideSelection").size());
    }

    @Test void malformedExistingApRetainsCapturedLiteralGlyphsButCannotAdoptPartialValue() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) { text(stream, "Visible body", RenderingMode.FILL, 30, 700); }
            PDAcroForm form = new PDAcroForm(document); document.getDocumentCatalog().setAcroForm(form);
            addForm(document, form, page, "PartialAP", "Retained", "Retained", true, true);
            PDAppearanceStream appearance = form.getField("PartialAP").getWidgets().get(0).getNormalAppearanceStream();
            byte[] existing;
            try (java.io.InputStream input = appearance.getCOSObject().createInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] block = new byte[256]; int count;
                while ((count = input.read(block)) != -1) output.write(block, 0, count); existing = output.toByteArray();
            }
            try (java.io.OutputStream output = appearance.getCOSObject().createOutputStream()) {
                // PDFBox tolerates many unclosed strings/arrays. This malformed ID token
                // deterministically throws after the actual preceding text operators.
                output.write(existing); output.write("\nI!".getBytes(StandardCharsets.ISO_8859_1));
            }
            bytes = save(document);
        }
        ObjectNode observation = OerNativePdfObservation.observe("partial-AP.pdf", bytes, code(), Arrays.asList(1));
        JsonNode field = observation.path("pageObservations").get(0).path("nativePdfEvidence").path("formFields").get(0);
        assertEquals("partial_existing_normal_AP_operator_failure", field.path("appearanceGlyphObservationStatus").asText());
        assertEquals("Retained", field.path("appearanceGlyphOrderChannel").path("text").asText());
        assertEquals("appearance_observation_partial_unknown", field.path("valueStatus").asText());
        assertEquals("partial_capture_preserved", observation.path("observationStatus").asText());
        assertTrue(field.path("appearanceDecodedStreamBytesBase64").isTextual());
        assertFalse(observation.path("quality").path("humanConfirmed").asBoolean());
        com.consense.config.ConsenseProperties properties = new com.consense.config.ConsenseProperties();
        properties.getOcr().setEnabled(false); properties.getOcr().setTextLayerMinChars(1);
        com.consense.ocr.OcrClient ocr = org.mockito.Mockito.mock(com.consense.ocr.OcrClient.class);
        DocumentParser.ParsedDocument parsed = new DocumentParser(properties, ocr).parse("partial-AP.pdf", bytes);
        assertTrue(parsed.getText().contains("Visible body")); assertFalse(parsed.getText().contains("Retained"));
        PdfPageEvidence.PdfFormField original = parsed.getNativePdfEvidence().get(0).getFormFields().get(0);
        assertEquals("Retained", original.getFieldValue()); assertEquals("Retained", original.getAppearanceValue());
        assertEquals("appearance_observation_partial_unknown", original.getValueStatus());
        assertTrue(parsed.getCoverage().getNeedsReviewPages().contains(1));
        assertTrue(parsed.getNativePdfEvidence().get(0).getNativeBlocks().stream()
                .filter(block -> block.getPdfFormField() != null).allMatch(block -> block.getText().isEmpty()));
        org.mockito.Mockito.verifyNoInteractions(ocr);
    }

    @Test void utf16ClusterAndEmptyUnknownGlyphSpansDoNotNormalizeOrInventCharacters() {
        List<PdfPageEvidence.PdfGlyph> glyphs = new ArrayList<>();
        for (String value : new String[]{"fi", "\uD83D\uDE00", null, "", " \n"}) {
            PdfPageEvidence.PdfGlyph glyph = new PdfPageEvidence.PdfGlyph(); glyph.setId("glyph/" + glyphs.size()); glyph.setUnicode(value); glyphs.add(glyph);
        }
        JsonNode channel = OerNativePdfObservation.glyphChannel(glyphs); assertEquals("fi\uD83D\uDE00 \n", channel.path("text").asText());
        assertEquals(5, channel.path("spans").size()); assertEquals(4, channel.path("spans").get(2).path("start").asInt());
        assertEquals(4, channel.path("spans").get(2).path("end").asInt()); assertTrue(channel.path("nullUnicodeObserved").asBoolean());
        checkChannel(channel, JSON.valueToTree(glyphs));
    }

    private static void checkChannel(JsonNode channel, JsonNode glyphs) {
        assertEquals(glyphs.size(), channel.path("spans").size()); String text = channel.path("text").asText(); int position = 0;
        for (int i = 0; i < glyphs.size(); i++) {
            JsonNode glyph = glyphs.get(i), span = channel.path("spans").get(i); String literal = glyph.path("unicode").isNull() ? "" : glyph.path("unicode").asText();
            assertEquals(glyph.path("id").asText(), span.path("glyphId").asText()); assertEquals(position, span.path("start").asInt());
            position += literal.length(); assertEquals(position, span.path("end").asInt());
            assertEquals(literal, text.substring(span.path("start").asInt(), position));
        }
        assertEquals(text.length(), position);
    }
    private static double[] apply(JsonNode matrix, double x, double y) {
        return new double[]{matrix.get(0).asDouble() * x + matrix.get(2).asDouble() * y + matrix.get(4).asDouble(),
                matrix.get(1).asDouble() * x + matrix.get(3).asDouble() * y + matrix.get(5).asDouble()};
    }

    /** Emits an authored actual PDF plus the real Java observation for independent Python tests. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: fresh-fixture-output-dir actual-observer-java-source-directory");
        Path directory = Paths.get(args[0]); Files.createDirectory(directory); byte[] bytes = fixture();
        Files.write(directory.resolve("authored-native-pdf.pdf"), bytes, StandardOpenOption.CREATE_NEW);
        Map<String, byte[]> sources = code(Paths.get(args[1]));
        for (Map.Entry<String, byte[]> source : sources.entrySet()) Files.write(directory.resolve(source.getKey()), source.getValue(), StandardOpenOption.CREATE_NEW);
        ObjectNode observation = OerNativePdfObservation.observe("authored-native-pdf.pdf", bytes, sources, Arrays.asList(1, 2));
        Files.write(directory.resolve("native-pdf-observation.json"), JSON.writeValueAsBytes(observation), StandardOpenOption.CREATE_NEW);
        System.out.println(directory.toAbsolutePath());
    }
}
