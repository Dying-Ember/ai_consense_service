package com.consense.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Version;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Actual PDFBox operator observation. No OCR, rendering, legacy normalized blocks or adoption. */
public final class OerNativePdfObservation {
    public static final String SCHEMA = "consense.native-pdf-observation.v1";
    public static final String VERSION = "pdfbox-native-observation-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final List<String> OBSERVER_SOURCES = Collections.unmodifiableList(Arrays.asList(
            "OerNativePdfObservation.java", "PdfNativeSupport.java", "PdfPageEvidence.java"));

    private OerNativePdfObservation() {}

    /** Source/code bytes and selected one-based physical pages are supplied explicitly. */
    public static ObjectNode observe(String filename, byte[] sourceBytes, Map<String, byte[]> sourceCode,
                                     List<Integer> selectedPages) throws IOException {
        if (filename == null || filename.isEmpty() || sourceBytes == null || sourceBytes.length == 0)
            throw new IllegalArgumentException("Actual original PDF filename and bytes are required");
        if (sourceCode == null || !sourceCode.keySet().equals(new java.util.HashSet<>(OBSERVER_SOURCES)))
            throw new IllegalArgumentException("The complete actual observer source set is required");
        for (String name : OBSERVER_SOURCES) if (sourceCode.get(name) == null || sourceCode.get(name).length == 0)
            throw new IllegalArgumentException("Missing actual observer source bytes: " + name);
        ObjectNode out = JSON.createObjectNode(); out.put("schema", SCHEMA); out.put("producerKind", "native_pdf");
        String sourceSha = sha256(sourceBytes);
        try (PDDocument document = PDDocument.load(sourceBytes)) {
            List<Integer> pages = validatePages(selectedPages, document.getNumberOfPages());
            ObjectNode config = JSON.createObjectNode(); config.set("selectedPhysicalPages", JSON.valueToTree(pages));
            config.put("pageNumberConvention", "one_based_original_physical_page");
            config.put("glyphOrder", "operator_capture_order_not_reading_order");
            config.put("textChannel", "all_literal_pdfbox_glyph_unicode_no_normalization");
            config.put("textOffsetConvention", "UTF16_start_inclusive_end_exclusive_null_unicode_empty_zero_span");
            config.put("formObservationScope", "whole_document_existing_fields_widgets_selected_normal_AP");
            config.put("appearanceCoordinates", "appearance_stream_local_matrix_no_page_box");
            config.put("rasterRendering", "not_invoked"); config.put("ocr", "not_invoked"); config.put("llm", "not_invoked");
            config.put("normalizedBlocks", "not_produced"); config.put("externalResources", "no_network_fetch_by_adapter");
            config.put("localFontResolution", "PDFBox_default_font_mapper_environment_dependent_unverified");
            ObjectNode identity = out.putObject("observerIdentity"); identity.put("observerVersion", VERSION);
            identity.put("adapter", "com.consense.document.OerNativePdfObservation");
            ObjectNode dependencies = identity.putObject("dependencyVersions");
            dependencies.put("apachePdfBox", Version.getVersion());
            String fontBoxVersion = org.apache.fontbox.ttf.TTFParser.class.getPackage().getImplementationVersion();
            dependencies.put("apacheFontBox", fontBoxVersion == null ? "unknown_missing_package_version" : fontBoxVersion);
            dependencies.put("pdfBoxFontMapperClass", org.apache.pdfbox.pdmodel.font.FontMappers.instance().getClass().getName());
            dependencies.put("jacksonDatabind", JSON.version().toFullString());
            dependencies.put("javaRuntime", System.getProperty("java.runtime.version"));
            ObjectNode hashes = identity.putObject("sourceCodeSha256");
            for (String name : new TreeMap<>(sourceCode).keySet()) hashes.put(name, sha256(sourceCode.get(name)));
            identity.set("config", config); identity.put("configSha256", sha256(canonicalBytes(config)));
            String identitySha = sha256(canonicalBytes(identity)); out.put("observerIdentitySha256", identitySha);
            ObjectNode source = out.putObject("source"); source.put("filename", filename); source.put("sourceSha256", sourceSha);
            source.put("byteCount", sourceBytes.length); source.put("mediaType", "application/pdf");
            source.put("pageCount", document.getNumberOfPages());
            source.put("sourceRevisionSha256", sha256(("consense.native-pdf-source-revision.v1\n" + sourceSha + "\n" + identitySha).getBytes(StandardCharsets.UTF_8)));
            out.set("sourceArtifact", artifact(filename, "application/pdf", sourceBytes));
            out.set("selectedPhysicalPages", JSON.valueToTree(pages));
            ObjectNode invocation = out.putObject("invocation"); invocation.put("pageTextScope", "selected_physical_pages_only");
            invocation.put("formObservationScope", config.path("formObservationScope").asText());
            invocation.put("rasterRendering", "not_invoked"); invocation.put("ocr", "not_invoked"); invocation.put("llm", "not_invoked");
            invocation.put("encrypted", document.isEncrypted()); invocation.put("extractionPermission", document.getCurrentAccessPermission().canExtractContent());
            ArrayNode observations = out.putArray("pageObservations");
            ArrayNode unassigned = out.putArray("unassignedFormFields");
            ArrayNode outsideSelection = out.putArray("documentFormFieldsOutsideSelection");
            List<PdfPageEvidence> evidence = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) evidence.add(PdfNativeSupport.page(i + 1, document.getPage(i)));
            ArrayNode captureLimitations = out.putArray("captureLimitations");
            boolean partial = false;
            try { PdfNativeSupport.collectForms(document, evidence); }
            catch (IOException | RuntimeException failure) {
                partial = true; captureLimitations.add("Partial form observation failure; original bytes and previously captured fields retained: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            for (PdfPageEvidence current : evidence) for (PdfPageEvidence.PdfFormField field : current.getFormFields())
                if (field.getAppearanceStreamObservationStatus().startsWith("partial_") || field.getAppearanceGlyphObservationStatus().startsWith("partial_")) partial = true;
            for (Integer physicalPage : pages) {
                PdfPageEvidence current = evidence.get(physicalPage - 1); PDPage page = document.getPage(physicalPage - 1);
                String status = "observed_native_operators_unreviewed";
                try { new GlyphStripper(physicalPage, page, current).getText(document); }
                catch (IOException | RuntimeException failure) {
                    partial = true; status = "partial_native_operator_observation_failure";
                    current.getLimitations().add("Native glyph extraction failed after retained partial capture: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
                ObjectNode row = observations.addObject(); row.put("physicalPage", physicalPage); row.put("observationStatus", status);
                row.set("frame", frame(page)); row.set("nativePdfEvidence", evidenceJson(current));
                row.set("glyphOrderChannel", glyphChannel(current.getGlyphs()));
                ObjectNode relations = row.putObject("lineRelations"); relations.put("status", "not_observed");
                relations.putArray("lines"); relations.put("limitation", "No inferred lines, separators, reading order, clauses or tables were synthesized from operator capture order.");
            }
            for (PdfPageEvidence current : evidence) {
                if (current.getPageNo() == 0) for (PdfPageEvidence.PdfFormField field : current.getFormFields()) unassigned.add(formJson(field));
                else if (!pages.contains(current.getPageNo())) for (PdfPageEvidence.PdfFormField field : current.getFormFields()) outsideSelection.add(formJson(field));
            }
            out.put("observationStatus", partial ? "partial_capture_preserved" : "observed_native_operators_unreviewed");
            out.put("canonicalAdoption", "not_performed");
            ObjectNode quality = out.putObject("quality"); quality.put("semanticQuality", "unknown");
            quality.put("effectiveRenderingStatus", "unknown"); quality.put("humanConfirmed", false);
            quality.put("semanticScopeVerified", false); quality.put("canonicalAdoption", "not_performed");
            ArrayNode limits = quality.putArray("limitations");
            for (String limit : Arrays.asList("operator_visibility_is_not_pixel_visibility", "exact_clipping_occlusion_optional_content_and_contrast_unknown",
                    "operator_order_is_not_reading_order", "glyph_unicode_font_mapping_semantics_unverified", "AP_stream_local_coordinates_have_no_established_widget_page_transform",
                    "stored_field_V_and_existing_AP_are_independent", "no_appearance_regeneration_or_flattening", "no_lines_clauses_or_table_topology_adopted",
                    "no_raster_assets_or_OCR_or_LLM_invocations", "source_semantic_scope_applicability_and_legal_quality_unknown",
                    "unselected_page_text_unobserved_original_bytes_retained", "nonembedded_font_metrics_may_depend_on_unverified_local_PDFBox_font_resolution")) limits.add(limit);
        }
        return out;
    }

    private static List<Integer> validatePages(List<Integer> requested, int count) {
        if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("Explicit nonempty physical page selection required");
        for (Integer page : requested) if (page == null) throw new IllegalArgumentException("Physical page cannot be null");
        List<Integer> result = new ArrayList<>(requested); List<Integer> sorted = new ArrayList<>(requested); Collections.sort(sorted);
        if (!result.equals(sorted)) throw new IllegalArgumentException("Physical pages must be strictly increasing");
        Integer prior = null;
        for (Integer page : result) {
            if (page == null || page < 1 || page > count || page.equals(prior)) throw new IllegalArgumentException("Invalid or duplicate physical page: " + page);
            prior = page;
        }
        return result;
    }

    private static final class GlyphStripper extends PDFTextStripper {
        private final PDPage page; private final PdfPageEvidence evidence; private final int pageNo;
        GlyphStripper(int pageNo, PDPage page, PdfPageEvidence evidence) throws IOException {
            this.pageNo = pageNo; this.page = page; this.evidence = evidence;
            setStartPage(pageNo); setEndPage(pageNo); setSortByPosition(false); setSuppressDuplicateOverlappingText(false);
        }
        @Override protected void processTextPosition(TextPosition position) {
            evidence.getGlyphs().add(PdfNativeSupport.glyph(position, getGraphicsState(), page,
                    "pdf-page/" + pageNo + "/glyph/" + evidence.getGlyphs().size()));
        }
    }

    private static ObjectNode evidenceJson(PdfPageEvidence evidence) {
        ObjectNode value = JSON.valueToTree(evidence); ArrayNode fields = value.putArray("formFields");
        for (PdfPageEvidence.PdfFormField field : evidence.getFormFields()) fields.add(formJson(field));
        return value;
    }

    private static ObjectNode formJson(PdfPageEvidence.PdfFormField field) {
        ObjectNode value = JSON.valueToTree(field); value.set("appearanceGlyphOrderChannel", glyphChannel(field.getAppearanceGlyphs()));
        value.put("appearanceCoordinateStatus", "stream_local_no_established_widget_page_transform");
        return value;
    }

    static ObjectNode glyphChannel(List<PdfPageEvidence.PdfGlyph> glyphs) {
        ObjectNode channel = JSON.createObjectNode(); channel.put("textChannel", "observedGlyphUnicodeAll");
        channel.put("offsetUnit", "UTF16"); channel.put("orderingStatus", "operator_capture_order_not_reading_order");
        StringBuilder text = new StringBuilder(); ArrayNode spans = channel.putArray("spans"); boolean nullUnicode = false;
        for (PdfPageEvidence.PdfGlyph glyph : glyphs) {
            int start = text.length(); if (glyph.getUnicode() == null) nullUnicode = true; else text.append(glyph.getUnicode());
            ObjectNode span = spans.addObject(); span.put("start", start); span.put("end", text.length()); span.put("glyphId", glyph.getId());
        }
        channel.put("text", text.toString()); channel.put("nullUnicodeObserved", nullUnicode);
        channel.put("nullUnicodeConvention", "null_unicode_retained_on_glyph_zero_length_span_no_invented_character");
        return channel;
    }

    private static ObjectNode frame(PDPage page) {
        ObjectNode frame = JSON.createObjectNode(); double[] crop = PdfNativeSupport.rect(page.getCropBox());
        frame.set("mediaBox", JSON.valueToTree(PdfNativeSupport.rect(page.getMediaBox()))); frame.set("cropBox", JSON.valueToTree(crop));
        frame.put("rotation", page.getRotation()); frame.put("userUnit", page.getUserUnit());
        frame.put("coordinateConvention", "pdf_default_user_space_xywh_unscaled_user_unit");
        frame.put("glyphBboxConvention", "normalized_rendered_crop_top_left_xywh_pdfbox_derived_not_pixel_verified");
        frame.put("matrixConvention", "a_b_c_d_e_f_xprime_a_x_plus_c_y_plus_e_yprime_b_x_plus_d_y_plus_f");
        frame.put("frameObservation", "effective_PDFBox_page_values_original_dictionary_preserved_in_source_bytes");
        int rotation = Math.floorMod(page.getRotation(), 360); double[] matrix;
        double x = crop[0], y = crop[1], width = crop[2], height = crop[3];
        switch (rotation) {
            case 0: matrix = new double[]{1, 0, 0, -1, -x, height + y}; break;
            case 90: matrix = new double[]{0, 1, 1, 0, -y, -x}; break;
            case 180: matrix = new double[]{-1, 0, 0, 1, width + x, -y}; break;
            case 270: matrix = new double[]{0, -1, -1, 0, height + y, width + x}; break;
            default: matrix = null;
        }
        double renderedWidth = rotation % 180 == 0 ? width : height;
        double renderedHeight = rotation % 180 == 0 ? height : width;
        if (matrix == null || renderedWidth <= 0 || renderedHeight <= 0 || page.getUserUnit() <= 0) {
            frame.putNull("rawToRenderedCropNormalized"); frame.putNull("rawToRenderedCropPoints72");
            frame.put("transformStatus", "unknown_unsupported_or_invalid_frame");
        } else {
            double[] normalized = matrix.clone(), physical = matrix.clone();
            for (int i = 0; i < 6; i++) { normalized[i] /= (i % 2 == 0 ? renderedWidth : renderedHeight); physical[i] *= page.getUserUnit(); }
            frame.set("rawToRenderedCropNormalized", JSON.valueToTree(normalized)); frame.set("rawToRenderedCropPoints72", JSON.valueToTree(physical));
            frame.set("renderedCropSizePoints72", JSON.valueToTree(new double[]{renderedWidth * page.getUserUnit(), renderedHeight * page.getUserUnit()}));
            frame.put("transformStatus", "derived_page_frame_mapping_not_raster_verified");
        }
        frame.put("rasterDpi", "not_invoked"); frame.put("actualRasterTransformStatus", "unknown_no_raster_produced");
        return frame;
    }

    /** CREATE_NEW exports preserve existing successful and failed artifacts. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: original.pdf fresh-output.json physical-pages-csv actual-observer-java-source-directory");
        Path source = Paths.get(args[0]), target = Paths.get(args[1]), codeDirectory = Paths.get(args[3]);
        List<Integer> pages = new ArrayList<>(); for (String page : args[2].split(",")) pages.add(Integer.valueOf(page));
        Map<String, byte[]> code = new LinkedHashMap<>(); for (String name : OBSERVER_SOURCES) code.put(name, Files.readAllBytes(codeDirectory.resolve(name)));
        ObjectNode value = observe(source.getFileName().toString(), Files.readAllBytes(source), code, pages);
        try (java.io.OutputStream output = new BufferedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) { JSON.writeValue(output, value); }
        System.out.println(target.toAbsolutePath());
    }

    private static ObjectNode artifact(String name, String mediaType, byte[] bytes) {
        ObjectNode artifact = JSON.createObjectNode(); artifact.put("name", name); artifact.put("mediaType", mediaType);
        artifact.put("sha256", sha256(bytes)); artifact.put("byteCount", bytes.length);
        artifact.put("bytesBase64", Base64.getEncoder().encodeToString(bytes)); return artifact;
    }

    public static String sha256(byte[] bytes) {
        try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result = new StringBuilder();
            for (byte b : hash) result.append(String.format(Locale.ROOT, "%02x", b & 255)); return result.toString();
        } catch (Exception failure) { throw new IllegalStateException("Cannot hash actual bytes", failure); }
    }
    static byte[] canonicalBytes(JsonNode value) throws IOException { return JSON.writeValueAsBytes(sorted(value)); }
    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode(); List<String> names = new ArrayList<>(); value.fieldNames().forEachRemaining(names::add);
            Collections.sort(names); for (String name : names) result.set(name, sorted(value.get(name))); return result;
        }
        if (value.isArray()) { ArrayNode result = JSON.createArrayNode(); for (JsonNode member : value) result.add(sorted(member)); return result; }
        return value;
    }
}
