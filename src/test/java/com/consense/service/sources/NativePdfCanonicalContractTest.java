package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Exact Python-produced authored public wire; mutations below test declarations only, not CAS acceptance. */
class NativePdfCanonicalContractTest {
    private static final String FIXTURE = "/oer/native-pdf-contract/authored-native-pdf-public-v1.json";
    private static final String FIXTURE_SHA = "18b927d8c32d2ba8bca38b47be00ddf8b3b4fe2b8def59000251e46febbeacd6";
    private static final String OTHER_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test void actualPythonPublicWirePassesWithoutMutation() throws Exception {
        byte[] raw = bytes(); assertEquals(43123, raw.length); assertEquals(FIXTURE_SHA, CanonicalJson.sha(raw));
        ObjectNode original = fixture(), before = original.deepCopy();
        assertTrue(NativePdfCanonicalContract.isNativePdf(original));
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateEnvelope(original));
        for (JsonNode chunk : original.path("chunks")) {
            assertDoesNotThrow(() -> NativePdfCanonicalContract.validateChunk(chunk, original));
            assertDoesNotThrow(() -> NativePdfCanonicalContract.validateChunk(chunk, original.path("source").path("sourceSha256").asText()));
        }
        assertEquals(before, original);
    }

    @Test void docxAndScannedMarkersAreNotPdfSignals() {
        assertFalse(NativePdfCanonicalContract.isNativePdf(null));
        assertFalse(NativePdfCanonicalContract.isNativePdf(JsonUtils.parse("{\"producerKind\":\"native_docx\",\"projectionVersion\":\"native-docx-cas-public-projection-v2\",\"sourcePosition\":{\"kind\":\"native_docx_node_utf16\"}}")));
        assertFalse(NativePdfCanonicalContract.isNativePdf(JsonUtils.parse("{\"producerKind\":\"dual_parser\",\"sourcePosition\":{\"kind\":\"original_pdf_page_bbox\"}}")));
    }

    @Test void eachPartialPdfMarkerRoutesToStrictRejection() {
        String[] samples = {"{\"schema\":\"" + NativePdfCanonicalContract.SCHEMA + "\"}",
                "{\"producerKind\":\"native_pdf\"}", "{\"metadataVersion\":\"" + NativePdfCanonicalContract.VERSION + "\"}",
                "{\"storageShape\":\"" + NativePdfCanonicalContract.STORAGE + "\"}",
                "{\"nested\":[{\"nativeGlyphMembershipRef\":{}}]}",
                "{\"ref\":{\"kind\":\"native_pdf_literal_utf16_spans\"}}",
                "{\"sourcePosition\":{\"kind\":\"pdf_native_source_reviewed_region\"}}"};
        for (String raw : samples) {
            JsonNode sample = JsonUtils.parse(raw); assertTrue(NativePdfCanonicalContract.isNativePdf(sample));
            assertThrows(CanonicalEvidenceException.class, () -> NativePdfCanonicalContract.validateEnvelope(sample));
        }
    }

    @Test void schemaProducerStorageAndVersionMustAgree() throws Exception {
        rejected(v -> v.put("schema", "legacy"), "VERSION");
        rejected(v -> v.put("producerKind", "native_docx"), "VERSION");
        rejected(v -> object(v, "/projectionIdentity").put("projectionVersion", "legacy"), "VERSION");
        rejected(v -> chunk(v).put("metadataVersion", "legacy"), "CHUNK");
        rejected(v -> chunk(v).put("segmentationVersion", "legacy"), "CHUNK");
        rejected(v -> chunk(v).put("nativeTableMetadataVersion", "table-supported"), "CHUNK");
        rejected(v -> chunk(v).remove("nativeTableMetadataVersion"), "CHUNK");
    }

    @Test void publicContentCannotReplaceOrTrimLiteralParts() throws Exception {
        rejected(v -> chunk(v).put("content", "Answer text"), "TEXT");
        rejected(v -> part(v, 0).put("text", "Visible fi cafe "), "PARTS");
        rejected(v -> object(chunk(v), "/textAssembly").put("text", " "), "TEXT");
    }

    @Test void utf16CountsSurrogatePairsAsTwoCodeUnits() throws Exception {
        ObjectNode declaration = fixture(), p = part(declaration, 0), c = chunk(declaration);
        String literal = "Visible \uD83D\uDE00"; p.put("text", literal); p.put("endOffset", literal.length());
        object(c, "/sourceSpans/0").put("endOffset", literal.length());
        c.put("content", literal + "\n" + part(declaration, 1).path("text").asText());
        // This checks a mutated declaration, not a resealed Python/CAS artifact.
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateChunk(c, declaration));
        p.put("endOffset", literal.codePointCount(0, literal.length()));
        assertThrows(CanonicalEvidenceException.class, () -> NativePdfCanonicalContract.validateChunk(c, declaration));
    }

    @Test void sourceSpansMustRepeatExactBoundPartDeclarations() throws Exception {
        rejected(v -> object(chunk(v), "/sourceSpans/0").put("endOffset", 14), "SPANS");
        rejected(v -> object(chunk(v), "/sourceSpans/0/nativeTextSpansRef").put("payloadSha256", OTHER_SHA), "SPANS");
    }

    @Test void physicalPagesAndPositionsCannotBeInvented() throws Exception {
        rejected(v -> part(v, 0).put("physicalPageNo", 0), "PARTS");
        rejected(v -> object(part(v, 0), "/sourcePosition").put("physicalPageNo", 2), "POSITION");
        rejected(v -> object(part(v, 0), "/sourceReviewAnchor").put("sourceSha256", OTHER_SHA), "POSITION");
        rejected(v -> chunk(v).put("pageNo", 2), "PAGES");
        rejected(v -> array(chunk(v), "/physicalPages").remove(1), "PAGES");
    }

    @Test void typedReferenceKindPointerAndSourceIdentityAreStrict() throws Exception {
        rejected(v -> object(part(v, 0), "/nativePdfRegionRef").put("kind", "native_docx_node"), "REFERENCE");
        rejected(v -> object(part(v, 0), "/nativePdfRegionRef").put("jsonPointer", "/chunks/1/parts/0"), "REFERENCE");
        rejected(v -> object(part(v, 0), "/nativeGlyphMembershipRef").put("sourceRevisionSha256", OTHER_SHA), "REFERENCE");
        rejected(v -> object(part(v, 0), "/nativeTextSpansRef").put("internalProjectionSha256", OTHER_SHA), "REFERENCE");
        rejected(v -> object(v, "/manifest/regionReviewRef").put("jsonPointer", "/other"), "REFERENCE");
    }

    @Test void standalonePointersUseOneActualChunkOrdinal() throws Exception {
        ObjectNode value = fixture(), c = chunk(value); c.put("sourceOrder", 2);
        assertThrows(CanonicalEvidenceException.class, () -> NativePdfCanonicalContract.validateChunk(c, value.path("source").path("sourceSha256").asText()));
    }

    @Test void casDescriptorRejectsTraversalTypesAndIncorrectIdentityScope() throws Exception {
        rejected(v -> object(v, "/internalProjectionRef/artifactDescriptor").put("relativePath", "../secret"), "PROOF");
        rejected(v -> object(v, "/transportCodeRef").put("byteCount", "12297"), "PROOF");
        rejected(v -> object(v, "/internalProjectionRef/artifactDescriptor").put("identityScope", "canonical_json_object"), "PROOF");
        rejected(v -> object(part(v, 0), "/nativePdfRegionRef/artifactDescriptor").put("sha256", "not-a-sha"), "PROOF");
    }

    @Test void zeroByteAppearanceDescriptorIsAValidDeclarationShape() throws Exception {
        ObjectNode value = fixture(); object(value, "/nativeCaptureRefs/appearanceArtifacts/0/artifactDescriptor").put("byteCount", 0);
        // An empty AP stream is legal. Python still checks its actual bytes and seal on every read.
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateEnvelope(value));
    }

    @Test void semanticUnknownAndHumanClaimsCannotBeUpgraded() throws Exception {
        rejected(v -> v.put("humanConfirmed", true), "UNKNOWNS");
        rejected(v -> v.put("semanticScopeVerified", true), "UNKNOWNS");
        rejected(v -> object(chunk(v), "/sourceQuality/attributes").put("qualifiersComplete", "verified"), "UNKNOWNS");
        rejected(v -> object(v, "/captureCompleteness/native").put("effectiveRenderingVerified", true), "UNKNOWNS");
        rejected(v -> object(v, "/transportValidation").put("qualityCertified", true), "PROOF");
    }

    @Test void visualAndApPublicationClaimsAreRejected() throws Exception {
        rejected(v -> v.put("visualReturnStatus", "available"), "UNKNOWNS");
        rejected(v -> part(v, 0).put("nativeEffectiveVisibility", "pixel_verified"), "UNKNOWNS");
        rejected(v -> part(v, 0).put("apTextAdoption", "supported"), "UNKNOWNS");
        rejected(v -> part(v, 0).put("geometryStatus", "exact_glyph_verified"), "UNKNOWNS");
    }

    @Test void internalGlyphAndApInventoriesDoNotEnterPublicPart() throws Exception {
        rejected(v -> part(v, 0).putArray("capturedGlyphIds").add("hidden-glyph"), "PARTS");
        rejected(v -> part(v, 0).putArray("nativeTextSpans"), "PARTS");
        rejected(v -> part(v, 0).put("appearanceText", "Other stored value"), "PARTS");
    }

    @Test void derivedPdfFrameIsCheckedWithoutClaimingRasterProof() throws Exception {
        rejected(v -> array(part(v, 0), "/sourcePosition/sourceFrame/rawToRenderedCropNormalized").set(0, JsonUtils.mapper().getNodeFactory().numberNode(0.5)), "FRAME");
        rejected(v -> object(part(v, 0), "/sourcePosition/sourceFrame").put("actualRasterTransformStatus", "verified"), "FRAME");
        rejected(v -> object(part(v, 0), "/sourcePosition/sourceFrame").put("userUnit", 0), "FRAME");
        rejected(v -> array(part(v, 0), "/bbox").set(2, JsonUtils.mapper().getNodeFactory().numberNode(1.01)), "GEOMETRY");
    }

    @Test void layoutGeometryKeepsOneOriginalFrameAndBoundingPolygon() throws Exception {
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0/originalGeometry/targetFrame").put("physicalPageNo", 2), "LAYOUT");
        rejected(v -> array(part(v, 0), "/visualLayoutParts/0/originalGeometry/xyxyNormalized").set(2, JsonUtils.mapper().getNodeFactory().numberNode(0.75)), "LAYOUT");
        rejected(v -> array(part(v, 0), "/visualLayoutParts/0/originalGeometry/polygonOriginal/1").set(0, JsonUtils.mapper().getNodeFactory().numberNode(201)), "LAYOUT");
    }

    @Test void sameSizedRasterIsNotSameRasterIdentity() throws Exception {
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0/rasterMappingReview").put("inputRasterSha256", OTHER_SHA), "LAYOUT");
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0/rasterMappingReview").put("referenceRasterSha256", OTHER_SHA), "LAYOUT");
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0/rasterMappingReview").put("originalPageTransformSha256", OTHER_SHA), "LAYOUT");
    }

    @Test void producerOriginalRasterIdentityIsRetainedAndBound() throws Exception {
        ObjectNode value = fixture(), before;
        for (JsonNode c : value.path("chunks")) for (JsonNode p : c.path("parts"))
            for (JsonNode row : p.path("visualLayoutParts"))
                object(row, "/originalGeometry/targetFrame").set("originalPageRasterSha256", p.path("sourceReviewAnchor").path("renditionSha256"));
        before = value.deepCopy();
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateEnvelope(value));
        assertEquals(before, value);
    }

    @Test void producerReviewMetadataIsRetainedWithoutUpgradingClaims() throws Exception {
        ObjectNode value = fixture();
        reviewMetadata(value);
        ObjectNode before = value.deepCopy();
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateEnvelope(value));
        assertEquals(before, value);
    }

    @Test void optionalRasterIdentityRejectsMalformedDriftedAndUnknownFields() throws Exception {
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0/originalGeometry/targetFrame").put("originalPageRasterSha256", "not-sha"), "LAYOUT");
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0/originalGeometry/targetFrame").put("originalPageRasterSha256", OTHER_SHA), "LAYOUT");
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0/originalGeometry/targetFrame").putNull("originalPageRasterSha256"), "LAYOUT");
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0/originalGeometry/targetFrame").put("unknownRasterClaim", true), "LAYOUT");
    }

    @Test void relationReviewMetadataStillRejectsClaimUpgradeAndCopyDrift() throws Exception {
        relationRejected(v -> object(v, "/canonical/relations/0").put("humanConfirmed", true));
        relationRejected(v -> object(v, "/canonical/relations/0").put("humanConfirmed", "false"));
        relationRejected(v -> object(v, "/canonical/relations/0").put("reviewActor", "human"));
        relationRejected(v -> object(v, "/canonical/relations/0").put("basis", ""));
        relationRejected(v -> object(v, "/canonical/relations/0").putObject("sourceReview"));
        relationRejected(v -> object(v, "/canonical/relations/0").put("sameParentClause", 17));
        relationRejected(v -> object(v, "/canonical/relations/0").put("qualityCertified", true));
        // Copies remain exact: legal metadata cannot differ between envelope and a chunk.
        ObjectNode value = fixture(); reviewMetadata(value);
        object(chunk(value), "/relations/0").put("basis", "different_source_review");
        assertThrows(CanonicalEvidenceException.class, () -> NativePdfCanonicalContract.validateEnvelope(value));
    }

    @Test void bothVisualFamiliesAndOneSelectedRunAreRetained() throws Exception {
        rejected(v -> array(part(v, 0), "/visualLayoutParts").remove(1), "LAYOUT");
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0").put("selectedLayout", true), "LAYOUT");
        rejected(v -> object(part(v, 0), "/visualLayoutParts/0").put("runId", "other:run"), "LAYOUT");
    }

    @Test void validJsonPointerEscapesArePreservedInRawProvenance() throws Exception {
        ObjectNode value = fixture(); object(part(value, 0), "/visualLayoutParts/0/rawProvenance").put("pointer", "/pages/0/a~0b~1c");
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateEnvelope(value));
        object(part(value, 0), "/visualLayoutParts/0/rawProvenance").put("pointer", "/pages/0/a~2b");
        assertThrows(CanonicalEvidenceException.class, () -> NativePdfCanonicalContract.validateEnvelope(value));
    }

    @Test void adoptionKeepsWholeRegionsAndClauseHeading() throws Exception {
        rejected(v -> array(chunk(v), "/adoption/regionIds").remove(1), "ADOPTION");
        rejected(v -> object(chunk(v), "/adoption/clause").put("headingRegionId", "absent"), "ADOPTION");
        rejected(v -> chunk(v).put("clauseHeadingLocation", "pdf-physical/9/source-region/other"), "ADOPTION");
    }

    @Test void relationClosureAndCanonicalChunkCopiesMustAgree() throws Exception {
        rejected(v -> object(chunk(v), "/relations/0").put("toRegionId", "absent"), "RELATIONS");
        rejected(v -> { object(chunk(v), "/relations/0").put("toUnitId", "other").put("scope", "between_whole_units"); }, "RELATIONS");
        rejected(v -> array(chunk(v), "/relations").removeAll(), "RELATIONS");
        rejected(v -> object(v, "/canonical/relations/0").put("fromRegionId", "absent"), "RELATIONS");
    }

    @Test void excludedSourcesAndDraftingTemplatesStayOutsideVetting() throws Exception {
        for (String excluded : new String[]{"pricing_bq", "pricing_sor", "pricing_general_summary", "works_related_pss", "sl_nontechnical"}) {
            rejected(v -> { object(v, "/projectionIdentity").put("sourceClass", excluded); object(v, "/manifest").put("sourceClass", excluded); }, "SCOPE");
        }
        rejected(v -> { object(v, "/projectionIdentity").put("sourcePurpose", "standard_template"); object(v, "/manifest").put("sourcePurpose", "standard_template"); }, "SCOPE");
    }

    @Test void captureIdentityAndDependencySourceListAreBound() throws Exception {
        rejected(v -> object(v, "/observerIdentity/sourceCodeSha256").put("PdfNativeSupport.java", OTHER_SHA), "CAPTURE");
        rejected(v -> object(v, "/projectionIdentity/projectionDependencySha256").remove("artifact_store.py"), "PROOF");
        rejected(v -> part(v, 0).put("rawObservationSha256", OTHER_SHA), "CAPTURE");
        rejected(v -> object(v, "/captureCompleteness/native").put("sourceRevisionSha256", OTHER_SHA), "CAPTURE");
    }

    @Test void scopeAndCommitmentUnitsCannotSilentlyShrink() throws Exception {
        rejected(v -> array(v, "/canonical/units").removeAll(), "COMMITMENTS");
        rejected(v -> object(v, "/canonical/units/0").put("unitId", "other"), "COMMITMENTS");
        rejected(v -> array(v, "/manifest/scope/requiredPhysicalPages").remove(1), "SCOPE");
        rejected(v -> chunk(v).put("sourceOrder", -1), "CHUNK");
    }

    private static void rejected(Consumer<ObjectNode> mutation, String code) throws Exception {
        ObjectNode value = fixture(); mutation.accept(value);
        CanonicalEvidenceException error = assertThrows(CanonicalEvidenceException.class, () -> NativePdfCanonicalContract.validateEnvelope(value));
        assertEquals("CANONICAL_NATIVE_PDF_" + code + "_INVALID", error.getMessage());
    }
    private static void reviewMetadata(ObjectNode value) {
        for (JsonNode row : value.path("canonical").path("relations")) {
            ObjectNode relation = (ObjectNode) row;
            relation.put("basis", "printed_shared_parent_boundary").put("sourceReview", "original_page_raster_review")
                    .put("reviewActor", "codex").put("humanConfirmed", false).put("sameParentClause", "17.1(12)");
        }
        for (JsonNode c : value.path("chunks")) for (JsonNode row : c.path("relations")) {
            for (JsonNode reference : value.path("canonical").path("relations"))
                if (row.path("referenceRelationIndex").equals(reference.path("referenceRelationIndex")))
                    ((ObjectNode) row).setAll((ObjectNode) reference);
        }
    }
    private static void relationRejected(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode value = fixture(); reviewMetadata(value); mutation.accept(value);
        // Keep all producer copies equal so each negative isolates its own typed/claim gate.
        for (JsonNode c : value.path("chunks")) for (JsonNode row : c.path("relations"))
            for (JsonNode reference : value.path("canonical").path("relations"))
                if (row.path("referenceRelationIndex").equals(reference.path("referenceRelationIndex")))
                    ((ObjectNode) row).setAll((ObjectNode) reference);
        CanonicalEvidenceException error = assertThrows(CanonicalEvidenceException.class, () -> NativePdfCanonicalContract.validateEnvelope(value));
        assertEquals("CANONICAL_NATIVE_PDF_RELATIONS_INVALID", error.getMessage());
    }
    private static ObjectNode chunk(JsonNode root) { return object(root, "/chunks/0"); }
    private static ObjectNode part(JsonNode root, int index) { return object(chunk(root), "/parts/" + index); }
    private static ObjectNode object(JsonNode root, String pointer) { return (ObjectNode) root.at(pointer); }
    private static ArrayNode array(JsonNode root, String pointer) { return (ArrayNode) root.at(pointer); }
    private static ObjectNode fixture() throws Exception { return (ObjectNode) JsonUtils.parse(new String(bytes(), StandardCharsets.UTF_8)); }
    private static byte[] bytes() throws Exception {
        try (InputStream input = NativePdfCanonicalContractTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(input); ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); return output.toByteArray();
        }
    }
}
