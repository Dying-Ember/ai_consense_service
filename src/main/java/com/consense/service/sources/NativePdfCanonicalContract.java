package com.consense.service.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.consense.common.JsonUtils;
import org.springframework.http.HttpStatus;

import java.util.*;

/**
 * Dedicated public native-PDF declaration contract, without registry/CAS I/O.
 * This checks public literal assembly and bound declarations, never proves the
 * referenced PDFBox glyphs, images, hidden state or actual CAS bytes in Java.
 * The caller must check original raw JSON seal/size and use Python cold CAS validation.
 */
public final class NativePdfCanonicalContract {
    private NativePdfCanonicalContract() {}
    public static final String SCHEMA = "consense.native-pdf-canonical-projection.v1";
    public static final String PRODUCER = "native_pdf";
    public static final String VERSION = "source-reviewed-native-pdf-cas-public-projection-v1";
    public static final String STORAGE = "cas-native-pdf-joint-adopted-public-v1";
    public static final String INTERNAL_VERSION = "source-reviewed-native-pdf-prose-and-visual-layout-v1";
    public static final String INTERNAL_V2 = "source-reviewed-native-pdf-prose-and-visual-layout-v2";
    public static final String POSITION = "pdf_native_source_reviewed_region";
    public static final String GEOMETRY = "source_reviewed_region_and_declared_visual_transform_not_exact_glyph";
    public static final String BBOX = "source_reviewed_original_raster_normalized_xyxy";
    public static final String VISUAL = "not_published_by_native_pdf_transport";
    public static final int MAX_BYTES = 16777216;
    public static final List<String> ALGORITHMS = Collections.unmodifiableList(Arrays.asList("native_pdf_source_registry.py", "oer_native_pdf_server.py", "native_pdf_canonical_projection.py", "native_pdf_projection_transport.py", "native_pdf_observation.py"));
    private static final Set<String> MODULES = set("drafting", "vetting", "advisory");
    private static final Set<String> EXCLUDED = set("pricing_bq", "pricing_sor", "pricing_general_summary", "works_related_pss", "sl_nontechnical", "evaluation_answer", "sl_library", "sl_technical_library", "specification_library");
    private static final Set<String> OBSERVER_SOURCES = set("OerNativePdfObservation.java", "PdfNativeSupport.java", "PdfPageEvidence.java");
    private static final Set<String> PROJECTION_DEPENDENCIES = set("artifact_store.py", "canonical_projection.py", "competition_scope.py", "native_pdf_observation.py", "parser_capture.py", "source_reference.py", "structured_parser_evidence.py", "token_windows.py");
    private static final Set<String> UNKNOWN_ATTRIBUTES = set("semanticQuality", "editState", "blankMeaning", "headerRelations", "crossPageRelations", "qualifiersComplete");

    /** A partial marker is a signal to reject through this contract, not permission to fall back to scanned/DOCX. */
    public static boolean isNativePdf(JsonNode value) {
        if (value == null) return false;
        Deque<JsonNode> pending = new ArrayDeque<>(); pending.add(value);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeFirst();
            if (node.isObject()) {
                if (SCHEMA.equals(node.path("schema").asText()) || PRODUCER.equals(node.path("producerKind").asText())
                        || node.path("projectionVersion").asText().contains("native-pdf")
                        || node.path("metadataVersion").asText().contains("native-pdf")
                        || node.path("internalProjectionVersion").asText().contains("native-pdf")
                        || node.path("sourceQualityMetadataVersion").asText().contains("native-pdf")
                        || node.path("storageShape").asText().contains("native-pdf")
                        || node.path("kind").asText().startsWith("native_pdf_")
                        || POSITION.equals(node.path("kind").asText())
                        || node.path("blockId").asText().startsWith("native-pdf-region:")
                        || node.path("id").asText().startsWith("canonical-native-pdf:") || node.path("id").asText().startsWith("canonical-native-pdf-public:")
                        || node.has("nativePdfRegionRef") || node.has("nativeGlyphMembershipRef") || node.has("nativeTextSpansRef") || node.has("unadoptedGlyphsRef") || node.has("nativeTextSpans")) return true;
                node.elements().forEachRemaining(pending::addLast);
            } else if (node.isArray()) node.elements().forEachRemaining(pending::addLast);
        }
        return false;
    }

    public static void validateCapability(JsonNode oer) {
        JsonNode capability = oer.path("nativePdfCanonical");
        require(SCHEMA.equals(capability.path("schema").asText()) && PRODUCER.equals(capability.path("producerKind").asText())
                && VERSION.equals(capability.path("projectionVersion").asText()) && STORAGE.equals(capability.path("storageShape").asText())
                && "/project/native-pdf".equals(capability.path("projectionEndpoint").asText())
                && "actual-cas-three-descriptors-only".equals(capability.path("projectionInputs").asText())
                && NativeCanonicalContract.actualArtifactReadPolicy(capability.path("artifactReadValidation"))
                && "unsupported".equals(capability.path("tableAndAppearanceText").asText()) && VISUAL.equals(capability.path("visualReturnStatus").asText())
                && positiveInt(capability.path("maximumMessageBytes")) && capability.path("maximumMessageBytes").asInt() <= MAX_BYTES, "CAPABILITY");
    }

    public static void validateAlgorithms(JsonNode algorithms) {
        for (String name : ALGORITHMS) sha(algorithms.path(name));
    }

    public static void validateAlgorithms(JsonNode algorithms, JsonNode identity) {
        validateAlgorithms(algorithms);
        same(algorithms.path("native_pdf_projection_transport.py"), identity.path("projectionCodeSha256"), "CAPABILITY");
        same(algorithms.path("native_pdf_canonical_projection.py"), identity.path("internalProjectionCodeSha256"), "CAPABILITY");
        same(algorithms.path("native_pdf_observation.py"), identity.path("projectionDependencySha256").path("native_pdf_observation.py"), "CAPABILITY");
        dependencies(identity.path("projectionDependencySha256"),identity.path("internalProjectionVersion").asText());
        if(INTERNAL_V2.equals(identity.path("internalProjectionVersion").asText()))for(String name:Arrays.asList("pdf_selected_source.py","selected_source_raster.py"))same(algorithms.path(name),identity.path("projectionDependencySha256").path(name),"CAPABILITY");
    }

    /** Source-bound display declarations; no private bytes are hydrated or quality claims upgraded. */
    public static ObjectNode displayDeclarations(JsonNode snapshot) {
        ObjectNode result = JsonUtils.mapper().createObjectNode();
        for (String field : Arrays.asList("humanConfirmed", "semanticScopeVerified", "wholeOriginalSourceCoverage", "visualReturnStatus", "limitations", "captureCompleteness", "unadoptedGlyphSummary", "unadoptedGlyphsRef", "unobservedOriginalPhysicalPages", "transportValidation")) result.set(field, snapshot.path(field).deepCopy());
        return result;
    }

    public static void validateDisplayDeclarations(JsonNode value) {
        fields(value, set("humanConfirmed", "semanticScopeVerified", "wholeOriginalSourceCoverage", "visualReturnStatus", "limitations", "captureCompleteness", "unadoptedGlyphSummary", "unadoptedGlyphsRef", "unobservedOriginalPhysicalPages", "transportValidation"), "UNKNOWNS");
        require(falseBoolean(value.path("humanConfirmed")) && falseBoolean(value.path("semanticScopeVerified")) && "unknown".equals(value.path("wholeOriginalSourceCoverage").asText()) && VISUAL.equals(value.path("visualReturnStatus").asText()) && value.path("limitations").isArray() && value.path("limitations").size() > 0, "UNKNOWNS");
        JsonNode nativeQuality = value.path("captureCompleteness").path("native");
        require("unknown".equals(nativeQuality.path("semanticQuality").asText()) && falseBoolean(nativeQuality.path("semanticScopeVerified")) && falseBoolean(nativeQuality.path("humanConfirmed")), "UNKNOWNS");
        for (String field : Arrays.asList("glyphSourceMembershipVerified", "formSourceMembershipVerified", "effectiveRenderingVerified", "readingOrderVerified")) require(falseBoolean(nativeQuality.path(field)), "UNKNOWNS");
        require("cold_full_cas_and_internal_reconstruction".equals(value.path("transportValidation").path("mode").asText()) && "not_implemented".equals(value.path("transportValidation").path("derivedValidationCache").asText()) && falseBoolean(value.path("transportValidation").path("qualityCertified")), "PROOF");
    }

    public static void validateEnvelope(JsonNode value) {
        fields(value, set("schema", "producerKind", "storageShape", "projectionIdentity", "projectionRevisionSha256", "projectionSha256", "sourceHash", "source", "manifest", "transportCodeRef", "internalProjectionRef", "nativeObservationRef", "dualParserBundleRef", "adoptionManifestRef", "nativeCaptureRefs", "observerIdentity", "sourceReferenceRef", "canonical", "chunks", "assetRefs", "unadoptedGlyphsRef", "unadoptedGlyphSummary", "captureCompleteness", "unobservedOriginalPhysicalPages", "humanConfirmed", "semanticScopeVerified", "wholeOriginalSourceCoverage", "visualReturnStatus", "limitations", "transportValidation"), "ENVELOPE");
        require(SCHEMA.equals(value.path("schema").asText()) && PRODUCER.equals(value.path("producerKind").asText()) && STORAGE.equals(value.path("storageShape").asText()), "VERSION");
        JsonNode identity = value.path("projectionIdentity"), source = value.path("source"), manifest = value.path("manifest");
        require(identity.isObject() && VERSION.equals(identity.path("projectionVersion").asText()) && STORAGE.equals(identity.path("storageShape").asText()) && knownInternal(identity.path("internalProjectionVersion").asText()), "VERSION");
        fields(source, set("filename", "sourceSha256", "sourceRevisionSha256", "mediaType", "byteCount", "pageCount"), "SOURCE");
        String sha = sha(source.path("sourceSha256")); String revision = sha(source.path("sourceRevisionSha256"));
        require(text(source.path("filename")) && "application/pdf".equals(source.path("mediaType").asText()) && positiveLong(source.path("byteCount")) && positiveInt(source.path("pageCount")), "SOURCE");
        int count = source.path("pageCount").asInt();
        for (String field : Arrays.asList("sourceHash", "projectionRevisionSha256", "projectionSha256")) sha(value.path(field));
        for (String field : Arrays.asList("projectionCodeSha256", "internalProjectionSha256", "internalProjectionCodeSha256", "internalSnapshotBytesSha256", "nativeBundleSha256", "dualBundleSha256", "dualSourceRevisionSha256", "referenceSha256", "adoptionManifestSha256", "scopeSha256", "qualitySha256")) sha(identity.path(field));
        dependencies(identity.path("projectionDependencySha256"),identity.path("internalProjectionVersion").asText());
        require(sha.equals(identity.path("sourceSha256").asText()) && revision.equals(identity.path("sourceRevisionSha256").asText()), "IDENTITY");
        fields(manifest, set("schema", "nativeBundleSha256", "dualBundleSha256", "sourceSha256", "sourceRevisionSha256", "documentId", "role", "module", "eligibleModules", "sourcePurpose", "sourceClass", "scope", "quality", "reference", "regionReviewRef", "units", "sourceRelationIndices", "tableGroups", "assets"), "MANIFEST");
        require("consense.native-pdf-canonical-adoption.v1".equals(manifest.path("schema").asText()) && empty(manifest.path("tableGroups")) && empty(manifest.path("assets")), "MANIFEST");
        for (String field : Arrays.asList("nativeBundleSha256", "dualBundleSha256", "sourceSha256", "sourceRevisionSha256", "documentId", "role", "module", "eligibleModules", "sourcePurpose", "sourceClass")) same(identity.path(field), manifest.path(field), "IDENTITY");
        module(identity);
        fields(manifest.path("scope"), set("requiredPhysicalPages", "scopePolicySha256"), "SCOPE");
        Set<Integer> required = pages(manifest.path("scope").path("requiredPhysicalPages"), count, false); sha(manifest.path("scope").path("scopePolicySha256"));
        quality(manifest.path("quality"), false);
        require(falseBoolean(value.path("humanConfirmed")) && falseBoolean(value.path("semanticScopeVerified")) && "unknown".equals(value.path("wholeOriginalSourceCoverage").asText()) && empty(value.path("assetRefs")) && "not_published_by_native_pdf_transport".equals(value.path("visualReturnStatus").asText()), "UNKNOWNS");
        JsonNode validation = value.path("transportValidation");
        fields(validation, set("mode", "derivedValidationCache", "qualityCertified"), "PROOF");
        require("cold_full_cas_and_internal_reconstruction".equals(validation.path("mode").asText()) && "not_implemented".equals(validation.path("derivedValidationCache").asText()) && falseBoolean(validation.path("qualityCertified")), "PROOF");
        require(value.path("limitations").isArray() && value.path("limitations").size() > 0, "UNKNOWNS");
        for (JsonNode limitation : value.path("limitations")) require(text(limitation), "UNKNOWNS");

        JsonNode internal = value.path("internalProjectionRef"), nativeRef = value.path("nativeObservationRef"), dualRef = value.path("dualParserBundleRef");
        fields(internal, set("artifactDescriptor", "projectionSha256", "sourceHash", "projectionRevisionSha256"), "PROOF");
        descriptor(internal.path("artifactDescriptor"), "exact_supplied_bytes");
        same(identity.path("internalProjectionSha256"), internal.path("projectionSha256"), "PROOF");
        same(identity.path("internalSnapshotBytesSha256"), internal.path("artifactDescriptor").path("sha256"), "PROOF");
        sha(internal.path("sourceHash")); sha(internal.path("projectionRevisionSha256"));
        bundleRef(nativeRef, identity.path("nativeBundleSha256")); bundleRef(dualRef, identity.path("dualBundleSha256"));
        descriptor(value.path("transportCodeRef"), "exact_supplied_bytes"); same(identity.path("projectionCodeSha256"), value.path("transportCodeRef").path("sha256"), "PROOF");
        descriptor(value.path("adoptionManifestRef"), "canonical_json_object"); same(identity.path("adoptionManifestSha256"), value.path("adoptionManifestRef").path("sha256"), "PROOF");
        captures(value, source);
        Set<String> images = reference(value, sha);
        typedRef(manifest.path("regionReviewRef"), sha, revision, identity.path("internalProjectionSha256"), value.path("adoptionManifestRef"), "/regionReview", "native_pdf_source_region_review");
        typedRef(value.path("unadoptedGlyphsRef"), sha, revision, identity.path("internalProjectionSha256"), internal.path("artifactDescriptor"), "/unadoptedGlyphs", "native_pdf_unadopted_unknown_inventory");

        JsonNode nativeQuality = value.path("captureCompleteness").path("native");
        require("consense.native-pdf-observation-validation.v1".equals(nativeQuality.path("schema").asText()) && PRODUCER.equals(nativeQuality.path("producerKind").asText()) && falseBoolean(nativeQuality.path("humanConfirmed")) && falseBoolean(nativeQuality.path("semanticScopeVerified")) && "unknown".equals(nativeQuality.path("semanticQuality").asText()), "UNKNOWNS");
        for (String field : Arrays.asList("glyphSourceMembershipVerified", "formSourceMembershipVerified", "effectiveRenderingVerified", "readingOrderVerified")) require(falseBoolean(nativeQuality.path(field)), "UNKNOWNS");
        require("not_performed".equals(nativeQuality.path("canonicalAdoption").asText()) && count == nativeQuality.path("originalPageCount").asInt(), "CAPTURE");
        same(source.path("sourceSha256"), nativeQuality.path("sourceSha256"), "CAPTURE"); same(source.path("sourceRevisionSha256"), nativeQuality.path("sourceRevisionSha256"), "CAPTURE");
        Set<Integer> observed = pages(nativeQuality.path("capturedSelectedPhysicalPages"), count, false);
        require(observed.containsAll(required), "SCOPE"); same(nativeQuality.path("capturedSelectedPhysicalPages"), value.path("observerIdentity").path("config").path("selectedPhysicalPages"), "CAPTURE");
        Set<Integer> absent = pages(value.path("unobservedOriginalPhysicalPages"), count, true); Set<Integer> complement = new TreeSet<>(); for (int page = 1; page <= count; page++) if (!observed.contains(page)) complement.add(page); require(complement.equals(absent), "SCOPE");
        JsonNode summary = value.path("unadoptedGlyphSummary"); require(summary.isArray() && summary.size() == observed.size(), "CAPTURE"); Set<Integer> summarized = new HashSet<>();
        for (JsonNode row : summary) { fields(row, set("physicalPageNo", "glyphCount", "status"), "CAPTURE"); require(positiveInt(row.path("physicalPageNo")) && observed.contains(row.path("physicalPageNo").asInt()) && summarized.add(row.path("physicalPageNo").asInt()) && nonnegativeInt(row.path("glyphCount")) && "unadopted_unknown".equals(row.path("status").asText()), "CAPTURE"); }
        JsonNode canonical = value.path("canonical"); fields(canonical, set("version", "units", "relations", "assetRefs"), "COMMITMENTS"); require("native-pdf-public-whole-prose-commitments-v1".equals(canonical.path("version").asText()) && empty(canonical.path("assetRefs")) && canonical.path("relations").isArray(), "COMMITMENTS");
        JsonNode chunks = value.path("chunks"), units = canonical.path("units"), adoptions = manifest.path("units");
        require(chunks.isArray() && chunks.size() > 0 && units.isArray() && units.size() == chunks.size() && adoptions.isArray() && adoptions.size() == chunks.size(), "COMMITMENTS");
        Set<String> ids = new HashSet<>(), unitIds = new HashSet<>(); Map<String, String> regionUnits = new HashMap<>(); Set<Integer> adoptedPages = new TreeSet<>();
        for (int index = 0; index < chunks.size(); index++) {
            JsonNode chunk = chunks.get(index), commitment = units.get(index);
            validateChunk(chunk, value);
            require(nonnegativeInt(chunk.path("sourceOrder")) && chunk.path("sourceOrder").asInt() == index && ids.add(chunk.path("id").asText()) && unitIds.add(chunk.path("unitId").asText()), "ORDER");
            fields(commitment, set("unitId", "scope", "partsSha256", "adoptionSha256", "chunkPreIdentitySha256"), "COMMITMENTS"); same(commitment.path("unitId"), chunk.path("unitId"), "COMMITMENTS"); same(commitment.path("scope"), chunk.path("canonicalScope"), "COMMITMENTS");
            for (String field : Arrays.asList("partsSha256", "adoptionSha256", "chunkPreIdentitySha256")) sha(commitment.path(field));
            JsonNode adoption = chunk.path("adoption");
            for (Iterator<String> names = adoptions.get(index).fieldNames(); names.hasNext();) { String name = names.next(); same(adoptions.get(index).path(name), adoption.path(name), "ADOPTION"); }
            for (JsonNode part : chunk.path("parts")) {
                require(images.contains(part.path("sourceReviewAnchor").path("renditionSha256").asText()), "REFERENCE");
                require(regionUnits.put(part.path("referenceRegionId").asText(), chunk.path("unitId").asText()) == null, "ADOPTION");
            }
            Set<Integer> physical = pages(chunk.path("physicalPages"), count, false); require(required.containsAll(physical), "SCOPE"); adoptedPages.addAll(physical);
        }
        require(!adoptedPages.isEmpty(), "SCOPE"); relations(canonical.path("relations"), unitIds);
        for (JsonNode relation : canonical.path("relations")) {
            require(relation.path("fromUnitId").asText().equals(regionUnits.get(relation.path("fromRegionId").asText()))
                    && relation.path("toUnitId").asText().equals(regionUnits.get(relation.path("toRegionId").asText())), "RELATIONS");
        }
        for (JsonNode chunk : chunks) {
            List<JsonNode> expected = new ArrayList<>(), actual = new ArrayList<>();
            for (JsonNode relation : canonical.path("relations")) if (chunk.path("unitId").equals(relation.path("fromUnitId")) || chunk.path("unitId").equals(relation.path("toUnitId"))) expected.add(relation);
            chunk.path("relations").forEach(actual::add); require(expected.equals(actual), "RELATIONS");
        }
    }

    /** Standalone declaration validation; envelope overload also binds every pointer to its actual public context. */
    public static void validateChunk(JsonNode chunk, String sourceSha) {
        require(sourceSha != null && sourceSha.matches("[a-f0-9]{64}"), "SOURCE");
        fields(chunk, set("id", "unitId", "content", "parts", "physicalPages", "pageNo", "anchor", "sourceOrder", "canonicalScope", "sourceHash", "projectionRevisionSha256", "projectionVersion", "metadataVersion", "segmentationVersion", "nativeTableMetadataVersion", "sourceQualityMetadataVersion", "sourceQualityHash", "sourceQuality", "sourceSpans", "adoption", "relations", "textAssembly", "assetRefs", "humanConfirmed", "originalSourceSha256", "documentId", "role", "module", "eligibleModules", "sourcePurpose", "sourceClass", "producerKind", "storageShape", "sourceSha256", "sourceRevisionSha256", "projectionCodeSha256", "internalProjectionVersion", "internalProjectionCodeSha256", "nativeBundleSha256", "dualBundleSha256", "referenceSha256", "adoptionManifestSha256", "dualSourceRevisionSha256", "scopeSha256", "qualitySha256", "projectionDependencySha256", "clauseId", "clauseHeadingLocation"), "CHUNK");
        require(PRODUCER.equals(chunk.path("producerKind").asText()) && VERSION.equals(chunk.path("projectionVersion").asText()) && VERSION.equals(chunk.path("metadataVersion").asText()) && STORAGE.equals(chunk.path("storageShape").asText()) && knownInternal(chunk.path("internalProjectionVersion").asText()) && "whole-source-reviewed-pdf-region-units-v1".equals(chunk.path("segmentationVersion").asText()) && "native-pdf-observation-and-source-review-unknown-v1".equals(chunk.path("sourceQualityMetadataVersion").asText()) && "whole_text_unit".equals(chunk.path("canonicalScope").asText()) && nonnegativeInt(chunk.path("sourceOrder")) && empty(chunk.path("assetRefs")) && falseBoolean(chunk.path("humanConfirmed")), "CHUNK");
        require(sourceSha.equals(chunk.path("sourceSha256").asText()) && sourceSha.equals(chunk.path("originalSourceSha256").asText()) && text(chunk.path("unitId")) && chunk.path("id").asText().matches("canonical-native-pdf-public:[a-f0-9]{64}"), "CHUNK");
        require("native-pdf-no-table-adoption-v1".equals(chunk.path("nativeTableMetadataVersion").asText()), "CHUNK");
        for (String field : Arrays.asList("sourceRevisionSha256", "sourceHash", "projectionRevisionSha256", "projectionCodeSha256", "internalProjectionCodeSha256", "sourceQualityHash", "qualitySha256", "scopeSha256", "referenceSha256", "nativeBundleSha256", "dualBundleSha256", "adoptionManifestSha256", "dualSourceRevisionSha256")) sha(chunk.path(field));
        same(chunk.path("sourceQualityHash"), chunk.path("qualitySha256"), "UNKNOWNS"); quality(chunk.path("sourceQuality"), true); module(chunk);
        dependencies(chunk.path("projectionDependencySha256"),chunk.path("internalProjectionVersion").asText());
        JsonNode assembly = chunk.path("textAssembly"); fields(assembly, set("kind", "text"), "TEXT"); require("reviewed_region_boundary".equals(assembly.path("kind").asText()) && "\n".equals(assembly.path("text").asText()), "TEXT");
        JsonNode parts = chunk.path("parts"), spans = chunk.path("sourceSpans"); require(parts.isArray() && parts.size() > 0 && spans.isArray() && spans.size() == parts.size(), "PARTS");
        Set<String> blocks = new HashSet<>(), regions = new HashSet<>(); Set<Integer> physical = new TreeSet<>(); List<String> literal = new ArrayList<>(); JsonNode descriptor = null, internalSha = null; String sourceRevision = chunk.path("sourceRevisionSha256").asText();
        for (int index = 0; index < parts.size(); index++) {
            JsonNode part = parts.get(index); part(part, sourceSha, sourceRevision); require(blocks.add(part.path("blockId").asText()) && regions.add(part.path("referenceRegionId").asText()), "PARTS");
            physical.add(part.path("physicalPageNo").asInt()); literal.add(part.path("text").asText());
            JsonNode ref = part.path("nativePdfRegionRef"); if (descriptor == null) { descriptor = ref.path("artifactDescriptor"); internalSha = ref.path("internalProjectionSha256"); }
            same(descriptor, ref.path("artifactDescriptor"), "REFERENCE"); same(internalSha, ref.path("internalProjectionSha256"), "REFERENCE");
            String pointer = "/chunks/" + chunk.path("sourceOrder").asInt() + "/parts/" + index; descriptor(ref.path("artifactDescriptor"), "exact_supplied_bytes");
            typedRef(ref, sourceSha, sourceRevision, internalSha, descriptor, pointer, "native_pdf_whole_region");
            typedRef(part.path("nativeGlyphMembershipRef"), sourceSha, sourceRevision, internalSha, descriptor, pointer + "/capturedGlyphIds", "native_pdf_glyph_membership");
            typedRef(part.path("nativeTextSpansRef"), sourceSha, sourceRevision, internalSha, descriptor, pointer + "/nativeTextSpans", "native_pdf_literal_utf16_spans");
            JsonNode span = spans.get(index); fields(span, set("blockId", "startOffset", "endOffset", "sourcePosition", "nativeTextSpansRef"), "SPANS"); for (String field : Arrays.asList("blockId", "startOffset", "endOffset", "sourcePosition", "nativeTextSpansRef")) same(span.path(field), part.path(field), "SPANS");
        }
        require(chunk.path("content").isTextual() && String.join("\n", literal).equals(chunk.path("content").asText()) && literal.stream().anyMatch(text -> !text.trim().isEmpty()), "TEXT");
        require(physical.equals(pages(chunk.path("physicalPages"), Integer.MAX_VALUE, false)) && positiveInt(chunk.path("pageNo")) && chunk.path("pageNo").asInt() == physical.iterator().next(), "PAGES"); same(chunk.path("anchor"), parts.get(0).path("anchor"), "POSITION");
        JsonNode adoption = chunk.path("adoption"); fields(adoption, set("unitId", "scope", "regionIds", "partSeparator", "clause", "physicalPages"), "ADOPTION"); same(adoption.path("unitId"), chunk.path("unitId"), "ADOPTION"); same(adoption.path("scope"), chunk.path("canonicalScope"), "ADOPTION"); same(adoption.path("physicalPages"), chunk.path("physicalPages"), "ADOPTION"); same(adoption.path("partSeparator"), assembly, "ADOPTION");
        require(adoption.path("regionIds").isArray() && adoption.path("regionIds").size() == parts.size(), "ADOPTION"); for (int index = 0; index < parts.size(); index++) same(adoption.path("regionIds").get(index), parts.get(index).path("referenceRegionId"), "ADOPTION");
        if (adoption.has("clause") && !adoption.path("clause").isNull()) { fields(adoption.path("clause"), set("clauseId", "headingRegionId"), "ADOPTION"); same(adoption.path("clause").path("clauseId"), chunk.path("clauseId"), "ADOPTION"); JsonNode heading = null; for (JsonNode part : parts) if (part.path("referenceRegionId").equals(adoption.path("clause").path("headingRegionId"))) heading = part; require(heading != null, "ADOPTION"); same(heading.path("anchor"), chunk.path("clauseHeadingLocation"), "ADOPTION"); }
        else require(!chunk.has("clauseId") && !chunk.has("clauseHeadingLocation"), "ADOPTION");
        require(chunk.path("relations").isArray(), "RELATIONS");
        for (JsonNode relation : chunk.path("relations")) {
            Set<String> linkedUnits = set(relation.path("fromUnitId").asText(), relation.path("toUnitId").asText());
            require(linkedUnits.contains(chunk.path("unitId").asText()), "RELATIONS");
            relations(Collections.singletonList(relation), linkedUnits);
            if (chunk.path("unitId").equals(relation.path("fromUnitId"))) require(regions.contains(relation.path("fromRegionId").asText()), "RELATIONS");
            if (chunk.path("unitId").equals(relation.path("toUnitId"))) require(regions.contains(relation.path("toRegionId").asText()), "RELATIONS");
        }
    }

    public static void validateChunk(JsonNode chunk, JsonNode envelope) {
        require(envelope != null && envelope.isObject(), "ENVELOPE"); validateChunk(chunk, envelope.path("source").path("sourceSha256").asText());
        JsonNode identity = envelope.path("projectionIdentity");
        for (String field : Arrays.asList("documentId", "role", "module", "eligibleModules", "sourcePurpose", "sourceClass", "sourceSha256", "sourceRevisionSha256", "projectionCodeSha256", "internalProjectionVersion", "internalProjectionCodeSha256", "nativeBundleSha256", "dualBundleSha256", "referenceSha256", "adoptionManifestSha256", "dualSourceRevisionSha256", "scopeSha256", "qualitySha256", "projectionDependencySha256")) same(chunk.path(field), identity.path(field), "IDENTITY");
        for (String field : Arrays.asList("sourceHash", "projectionRevisionSha256")) same(chunk.path(field), envelope.path(field), "IDENTITY");
        int ordinal = chunk.path("sourceOrder").asInt(-1); require(ordinal >= 0, "ORDER");
        for (int index = 0; index < chunk.path("parts").size(); index++) {
            JsonNode part = chunk.path("parts").get(index); require(part.path("physicalPageNo").asInt() <= envelope.path("source").path("pageCount").asInt(), "PAGES");
            typedRef(part.path("nativePdfRegionRef"), envelope.path("source").path("sourceSha256").asText(), envelope.path("source").path("sourceRevisionSha256").asText(), identity.path("internalProjectionSha256"), envelope.path("internalProjectionRef").path("artifactDescriptor"), "/chunks/" + ordinal + "/parts/" + index, "native_pdf_whole_region");
            same(part.path("rawObservationSha256"), envelope.path("nativeCaptureRefs").path("rawObservation").path("sha256"), "CAPTURE");
        }
    }

    private static void part(JsonNode part, String source, String revision) {
        fields(part, set("blockId", "referenceRegionId", "physicalPageNo", "text", "startOffset", "endOffset", "anchor", "sourceSha256", "rawObservationSha256", "sourcePosition", "bbox", "bboxConvention", "sourceReviewAnchor", "visualLayoutParts", "geometryStatus", "nativeEffectiveVisibility", "apTextAdoption", "nativePdfRegionRef", "nativeGlyphMembershipRef", "nativeTextSpansRef", "textChannel", "offsetUnit"), "PARTS");
        require(part.path("blockId").asText().matches("native-pdf-region:[a-f0-9]{64}") && text(part.path("referenceRegionId")) && source.equals(part.path("sourceSha256").asText()) && positiveInt(part.path("physicalPageNo")) && part.path("text").isTextual() && nonnegativeInt(part.path("startOffset")) && part.path("startOffset").asInt() == 0 && nonnegativeInt(part.path("endOffset")) && part.path("endOffset").asInt() == part.path("text").asText().length(), "PARTS");
        sha(part.path("rawObservationSha256")); require("UTF16".equals(part.path("offsetUnit").asText()) && "literal_native_glyphs_with_explicit_source_reviewed_separators".equals(part.path("textChannel").asText()) && "unknown_not_raster_verified".equals(part.path("nativeEffectiveVisibility").asText()) && "not_supported".equals(part.path("apTextAdoption").asText()) && "source_reviewed_region_and_declared_visual_transform_not_exact_glyph".equals(part.path("geometryStatus").asText()) && "source_reviewed_original_raster_normalized_xyxy".equals(part.path("bboxConvention").asText()), "UNKNOWNS");
        box(part.path("bbox"), 1, 1); int physical = part.path("physicalPageNo").asInt(); JsonNode position = part.path("sourcePosition"); fields(position, set("kind", "physicalPageNo", "sourceSha256", "sourceFrame"), "POSITION");
        require(POSITION.equals(position.path("kind").asText()) && source.equals(position.path("sourceSha256").asText()) && position.path("physicalPageNo").equals(part.path("physicalPageNo")), "POSITION"); frame(position.path("sourceFrame"));
        JsonNode anchor = part.path("sourceReviewAnchor"); require(anchor.isObject() && "pdf_physical_region".equals(anchor.path("kind").asText()) && source.equals(anchor.path("sourceSha256").asText()) && anchor.path("physicalPageNo").equals(part.path("physicalPageNo")) && text(anchor.path("pageId")), "POSITION"); sha(anchor.path("renditionSha256"));
        require(("pdf-physical/" + physical + "/source-region/" + part.path("referenceRegionId").asText()).equals(part.path("anchor").asText()), "POSITION"); layout(part, source, physical);
    }

    private static void layout(JsonNode part, String source, int physical) {
        JsonNode layouts = part.path("visualLayoutParts"); require(layouts.isArray() && layouts.size() >= 2, "LAYOUT"); Set<String> runs = new HashSet<>(), families = new HashSet<>(), selected = new HashSet<>(), blocks = new HashSet<>();
        for (JsonNode row : layouts) {
            fields(row, set("runId", "blockId", "physicalPageNo", "selectedLayout", "wholeBlockSha256", "actualCandidateTextSha256", "rawProvenance", "originalGeometry", "sourceRegionMappingStatus", "rasterMappingReview"), "LAYOUT");
            String run = row.path("runId").asText(); require(text(row.path("blockId")) && text(row.path("runId")) && row.path("physicalPageNo").isIntegralNumber() && row.path("physicalPageNo").asInt() == physical && row.path("selectedLayout").isBoolean() && blocks.add(run + "\n" + row.path("blockId").asText()), "LAYOUT");
            String family = run.startsWith("mineru:") ? "mineru" : run.startsWith("paddleocr-vl:") ? "paddleocr-vl" : ""; require(!family.isEmpty(), "LAYOUT"); runs.add(run); families.add(family); if (row.path("selectedLayout").asBoolean()) selected.add(run);
            sha(row.path("wholeBlockSha256")); sha(row.path("actualCandidateTextSha256")); JsonNode provenance = row.path("rawProvenance"); require(provenance.isObject() && text(provenance.path("output")) && text(provenance.path("textField")) && pointer(provenance.path("pointer")), "LAYOUT");
            require("source_reviewed_declared_box_overlap_not_exact_extent".equals(row.path("sourceRegionMappingStatus").asText()), "LAYOUT");
            JsonNode geometry = row.path("originalGeometry"), target = geometry.path("targetFrame"); require("verified".equals(geometry.path("status").asText()) && "explicit_input_and_source_bound_transform".equals(geometry.path("reason").asText()) && "original_page".equals(target.path("coordinateFrame").asText()) && "pixel".equals(target.path("coordinateUnit").asText()) && source.equals(target.path("sourceSha256").asText()) && positiveInt(target.path("physicalPageNo")) && target.path("physicalPageNo").asInt() == physical && positiveNumber(target.path("width")) && positiveNumber(target.path("height")), "LAYOUT");
            fields(geometry, set("status", "reason", "xyxyNormalized", "xyxyOriginal", "polygonOriginal", "targetFrame", "transformSha256"), "LAYOUT");
            fields(target, set("coordinateFrame", "coordinateUnit", "physicalPageNo", "sourceSha256", "width", "height", "originalPageRasterSha256"), "LAYOUT");
            box(geometry.path("xyxyNormalized"), 1, 1); box(geometry.path("xyxyOriginal"), target.path("width").asDouble(), target.path("height").asDouble()); sha(geometry.path("transformSha256"));
            for (int index = 0; index < 4; index++) require(close(geometry.path("xyxyNormalized").get(index).asDouble(), geometry.path("xyxyOriginal").get(index).asDouble() / target.path(index % 2 == 0 ? "width" : "height").asDouble()), "LAYOUT");
            JsonNode polygon = geometry.path("polygonOriginal"); require(polygon.isArray() && polygon.size() >= 3, "LAYOUT");
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (JsonNode point : polygon) { vector(point, 2); double px = point.get(0).asDouble(), py = point.get(1).asDouble(); require(px >= 0 && px <= target.path("width").asDouble() && py >= 0 && py <= target.path("height").asDouble(), "LAYOUT"); minX = Math.min(minX, px); minY = Math.min(minY, py); maxX = Math.max(maxX, px); maxY = Math.max(maxY, py); }
            double[] bounds = {minX, minY, maxX, maxY}; for (int index = 0; index < 4; index++) require(close(bounds[index], geometry.path("xyxyOriginal").get(index).asDouble()), "LAYOUT");
            require(overlap(part.path("bbox"), geometry.path("xyxyNormalized")), "LAYOUT");
            JsonNode review = row.path("rasterMappingReview"); fields(review, set("status", "method", "referenceRasterSha256", "inputRasterSha256", "originalPageTransformSha256", "relationship"), "LAYOUT");
            require("source_reviewed_declared".equals(review.path("status").asText()) && "original_page_raster_geometry_review".equals(review.path("method").asText()), "LAYOUT"); sha(review.path("inputRasterSha256")); same(review.path("referenceRasterSha256"), part.path("sourceReviewAnchor").path("renditionSha256"), "LAYOUT"); same(review.path("originalPageTransformSha256"), geometry.path("transformSha256"), "LAYOUT");
            // The visual producer retains this optional original-frame identity. A declared hash
            // must bind the reviewed original raster; it is never a new pixel-quality assertion.
            if (target.has("originalPageRasterSha256")) {
                JsonNode raster = target.path("originalPageRasterSha256");
                require(raster.isTextual() && raster.asText().matches("[a-f0-9]{64}"), "LAYOUT");
                same(raster, review.path("referenceRasterSha256"), "LAYOUT");
            }
            String relationship = review.path("relationship").asText(); require("source_reviewed_affine_to_reference_raster".equals(relationship) || "same_original_raster".equals(relationship) && review.path("inputRasterSha256").equals(review.path("referenceRasterSha256")), "LAYOUT");
        }
        require(runs.size() == 2 && families.size() == 2 && selected.size() == 1, "LAYOUT");
    }

    private static void frame(JsonNode frame) {
        fields(frame, set("mediaBox", "cropBox", "rotation", "userUnit", "coordinateConvention", "glyphBboxConvention", "matrixConvention", "rawToRenderedCropNormalized", "rawToRenderedCropPoints72", "renderedCropSizePoints72", "transformStatus", "rasterDpi", "actualRasterTransformStatus", "frameObservation"), "FRAME");
        require(frame.isObject() && "pdf_default_user_space_xywh_unscaled_user_unit".equals(frame.path("coordinateConvention").asText()) && "derived_page_frame_mapping_not_raster_verified".equals(frame.path("transformStatus").asText()) && "unknown_no_raster_produced".equals(frame.path("actualRasterTransformStatus").asText()) && "not_invoked".equals(frame.path("rasterDpi").asText()) && positiveNumber(frame.path("userUnit")) && frame.path("rotation").isIntegralNumber() && set("0", "90", "180", "270").contains(frame.path("rotation").asText()), "FRAME");
        require("normalized_rendered_crop_top_left_xywh_pdfbox_derived_not_pixel_verified".equals(frame.path("glyphBboxConvention").asText()) && "a_b_c_d_e_f_xprime_a_x_plus_c_y_plus_e_yprime_b_x_plus_d_y_plus_f".equals(frame.path("matrixConvention").asText()) && "effective_PDFBox_page_values_original_dictionary_preserved_in_source_bytes".equals(frame.path("frameObservation").asText()), "FRAME");
        vector(frame.path("mediaBox"), 4); vector(frame.path("cropBox"), 4); require(frame.path("mediaBox").get(2).asDouble() > 0 && frame.path("mediaBox").get(3).asDouble() > 0 && frame.path("cropBox").get(2).asDouble() > 0 && frame.path("cropBox").get(3).asDouble() > 0, "FRAME");
        vector(frame.path("rawToRenderedCropNormalized"), 6); vector(frame.path("rawToRenderedCropPoints72"), 6); vector(frame.path("renderedCropSizePoints72"), 2);
        double x = frame.path("cropBox").get(0).asDouble(), y = frame.path("cropBox").get(1).asDouble(), w = frame.path("cropBox").get(2).asDouble(), h = frame.path("cropBox").get(3).asDouble(), unit = frame.path("userUnit").asDouble(); int rotation = frame.path("rotation").asInt();
        double[] matrix = rotation == 0 ? new double[]{1, 0, 0, -1, -x, h + y} : rotation == 90 ? new double[]{0, 1, 1, 0, -y, -x} : rotation == 180 ? new double[]{-1, 0, 0, 1, w + x, -y} : new double[]{0, -1, -1, 0, h + y, w + x};
        double rw = rotation % 180 == 0 ? w : h, rh = rotation % 180 == 0 ? h : w;
        for (int index = 0; index < 6; index++) { require(close(frame.path("rawToRenderedCropNormalized").get(index).asDouble(), matrix[index] / (index % 2 == 0 ? rw : rh)) && close(frame.path("rawToRenderedCropPoints72").get(index).asDouble(), matrix[index] * unit), "FRAME"); }
        require(close(frame.path("renderedCropSizePoints72").get(0).asDouble(), rw * unit) && close(frame.path("renderedCropSizePoints72").get(1).asDouble(), rh * unit), "FRAME");
    }

    private static void captures(JsonNode value, JsonNode source) {
        JsonNode captures = value.path("nativeCaptureRefs"), observer = value.path("observerIdentity"); fields(captures, set("sourcePackage", "rawObservation", "observerSources", "appearanceArtifacts"), "CAPTURE");
        descriptor(captures.path("sourcePackage"), "exact_supplied_bytes"); descriptor(captures.path("rawObservation"), "exact_supplied_bytes"); same(source.path("sourceSha256"), captures.path("sourcePackage").path("sha256"), "CAPTURE"); same(source.path("byteCount"), captures.path("sourcePackage").path("byteCount"), "CAPTURE");
        require("pdfbox-native-observation-1".equals(observer.path("observerVersion").asText()) && "com.consense.document.OerNativePdfObservation".equals(observer.path("adapter").asText()), "CAPTURE"); sha(observer.path("configSha256"));
        JsonNode codes = observer.path("sourceCodeSha256"), sources = captures.path("observerSources"); require(codes.isObject() && sources.isObject() && names(codes).equals(OBSERVER_SOURCES) && names(sources).equals(OBSERVER_SOURCES), "CAPTURE");
        for (String name : OBSERVER_SOURCES) { descriptor(sources.path(name), "exact_supplied_bytes"); same(codes.path(name), sources.path(name).path("sha256"), "CAPTURE"); }
        require(captures.path("appearanceArtifacts").isArray(), "CAPTURE"); Set<String> ids = new HashSet<>(); for (JsonNode appearance : captures.path("appearanceArtifacts")) { fields(appearance, set("formId", "kind", "artifactDescriptor"), "CAPTURE"); require(text(appearance.path("formId")) && set("appearanceRawStreamBytesBase64", "appearanceDecodedStreamBytesBase64").contains(appearance.path("kind").asText()) && ids.add(appearance.path("formId").asText() + "\n" + appearance.path("kind").asText()), "CAPTURE"); descriptor(appearance.path("artifactDescriptor"), "exact_supplied_bytes"); }
    }

    private static Set<String> reference(JsonNode value, String source) {
        JsonNode reference = value.path("sourceReferenceRef"), declared = value.path("manifest").path("reference"); require(reference.isObject() && falseBoolean(reference.path("humanConfirmed")) && "application/pdf".equals(reference.path("sourceMediaType").asText()) && "codex".equals(reference.path("actor").asText()) && "original_source_review".equals(reference.path("method").asText()) && source.equals(reference.path("sourceSha256").asText()) && text(reference.path("referenceVersion")), "REFERENCE");
        descriptor(reference.path("artifactDescriptor"), null); sha(reference.path("evidenceSha256")); same(reference.path("evidenceSha256"), reference.path("artifactDescriptor").path("sha256"), "REFERENCE"); same(reference.path("evidenceSha256"), value.path("projectionIdentity").path("referenceSha256"), "REFERENCE");
        require(declared.isObject(), "REFERENCE"); for (Iterator<String> fields = declared.fieldNames(); fields.hasNext();) { String field = fields.next(); same(declared.path(field), reference.path(field), "REFERENCE"); }
        JsonNode images = reference.path("pageImages"); require(images.isArray() && images.size() > 0, "REFERENCE"); Set<String> result = new HashSet<>(); for (JsonNode image : images) { descriptor(image, "exact_supplied_bytes"); require(result.add(image.path("sha256").asText()), "REFERENCE"); } return result;
    }

    private static void relations(Iterable<JsonNode> rows, Set<String> units) {
        for (JsonNode row : rows) {
            fields(row, set("fromUnitId", "toUnitId", "fromRegionId", "toRegionId", "referenceRelationIndex", "status", "type", "scope", "sameParentClause", "sourceReview", "basis", "humanConfirmed", "reviewActor"), "RELATIONS");
            require(units.contains(row.path("fromUnitId").asText()) && units.contains(row.path("toUnitId").asText()) && text(row.path("fromRegionId")) && text(row.path("toRegionId")) && nonnegativeInt(row.path("referenceRelationIndex")) && "source_reviewed_declared".equals(row.path("status").asText()) && set("parent", "continuation", "qualifier", "caption", "header", "sidebar", "margin_note").contains(row.path("type").asText()), "RELATIONS");
            String scope = row.path("fromUnitId").equals(row.path("toUnitId")) ? "within_whole_unit" : "between_whole_units";
            require(scope.equals(row.path("scope").asText()), "RELATIONS");
            if (set("continuation", "qualifier").contains(row.path("type").asText())) require("within_whole_unit".equals(scope), "RELATIONS");
            // Preserve source-review provenance without treating it as a semantic or human gate.
            for (String field : Arrays.asList("sameParentClause", "sourceReview", "basis"))
                if (row.has(field)) require(text(row.path(field)), "RELATIONS");
            if (row.has("humanConfirmed")) require(falseBoolean(row.path("humanConfirmed")), "RELATIONS");
            if (row.has("reviewActor")) require("codex".equals(row.path("reviewActor").asText()) && row.path("reviewActor").isTextual(), "RELATIONS");
        }
    }
    private static void typedRef(JsonNode ref, String source, String revision, JsonNode internalSha, JsonNode descriptor, String pointer, String kind) {
        fields(ref, set("kind", "artifactDescriptor", "jsonPointer", "payloadSha256", "sourceSha256", "sourceRevisionSha256", "internalProjectionSha256"), "REFERENCE"); descriptor(ref.path("artifactDescriptor"), null); sha(ref.path("payloadSha256")); sha(ref.path("internalProjectionSha256")); require(kind.equals(ref.path("kind").asText()) && source.equals(ref.path("sourceSha256").asText()) && revision.equals(ref.path("sourceRevisionSha256").asText()) && pointer.equals(ref.path("jsonPointer").asText()), "REFERENCE"); same(internalSha, ref.path("internalProjectionSha256"), "REFERENCE"); same(descriptor, ref.path("artifactDescriptor"), "REFERENCE");
    }
    private static void bundleRef(JsonNode value, JsonNode seal) { fields(value, set("artifactDescriptor", "bundleSha256"), "PROOF"); descriptor(value.path("artifactDescriptor"), "canonical_json_object"); same(value.path("bundleSha256"), seal, "PROOF"); }
    private static void descriptor(JsonNode value, String scope) { fields(value, set("schema", "storage", "sha256", "byteCount", "relativePath", "identityScope"), "PROOF"); String hash = sha(value.path("sha256")); require("consense.artifact-descriptor.v1".equals(value.path("schema").asText()) && "content_addressed_disk".equals(value.path("storage").asText()) && nonnegativeLong(value.path("byteCount")) && ("sha256/" + hash.substring(0, 2) + "/" + hash + ".blob").equals(value.path("relativePath").asText()) && (scope == null ? set("exact_supplied_bytes", "canonical_json_object").contains(value.path("identityScope").asText()) : scope.equals(value.path("identityScope").asText())), "PROOF"); }
    private static boolean knownInternal(String version){return INTERNAL_VERSION.equals(version)||INTERNAL_V2.equals(version);}
    private static void dependencies(JsonNode value,String version) {
        require(knownInternal(version),"VERSION");Set<String> expected=new HashSet<>(PROJECTION_DEPENDENCIES);
        if(INTERNAL_V2.equals(version))expected.addAll(Arrays.asList("pdf_selected_source.py","selected_source_raster.py"));
        require(value.isObject()&&names(value).equals(expected),"PROOF");for(JsonNode digest:value)sha(digest);
    }
    private static void module(JsonNode value) { String module = value.path("module").asText(), purpose = value.path("sourcePurpose").asText(), sourceClass = value.path("sourceClass").asText(); require(MODULES.contains(module) && set("tender", "standard", "project_fact", "package_manifest").contains(value.path("role").asText()) && text(value.path("documentId")) && text(value.path("sourceClass")) && !EXCLUDED.contains(sourceClass) && set("standard_template", "project_document", "reference_standard", "project_fact", "package_manifest", "unknown").contains(purpose), "SCOPE"); JsonNode eligible = value.path("eligibleModules"); require(eligible.isArray() && eligible.size() > 0, "SCOPE"); Set<String> modules = new HashSet<>(); for (JsonNode item : eligible) require(item.isTextual() && MODULES.contains(item.asText()) && modules.add(item.asText()), "SCOPE"); require(modules.contains(module), "SCOPE"); if ("standard_template".equals(purpose) || "drafting_standard_template".equals(sourceClass)) require("drafting".equals(module) && modules.equals(set("drafting")) && set("standard_template", "unknown").contains(purpose), "SCOPE"); }
    private static void quality(JsonNode value, boolean expanded) { fields(value, set("parseStatus", "semanticQuality", "attributes", "notes", "version", "reviewMeaning"), "UNKNOWNS"); require("PARSED".equals(value.path("parseStatus").asText()) && "unknown".equals(value.path("semanticQuality").asText()) && text(value.path("version")), "UNKNOWNS"); JsonNode attributes = value.path("attributes"); if (expanded) require(attributes.isObject() && names(attributes).equals(UNKNOWN_ATTRIBUTES), "UNKNOWNS"); else require(attributes.isMissingNode() || attributes.isObject() && UNKNOWN_ATTRIBUTES.containsAll(names(attributes)), "UNKNOWNS"); if (attributes.isObject()) for (JsonNode item : attributes) require(item.isTextual() && "unknown".equals(item.asText()), "UNKNOWNS"); }
    private static Set<Integer> pages(JsonNode value, int maximum, boolean emptyAllowed) { require(value.isArray() && (emptyAllowed || value.size() > 0), "PAGES"); Set<Integer> result = new TreeSet<>(); int prior = 0; for (JsonNode page : value) { require(positiveInt(page) && page.asInt() <= maximum && page.asInt() > prior, "PAGES"); result.add(page.asInt()); prior = page.asInt(); } return result; }
    private static void box(JsonNode box, double width, double height) { vector(box, 4); double x0 = box.get(0).asDouble(), y0 = box.get(1).asDouble(), x1 = box.get(2).asDouble(), y1 = box.get(3).asDouble(); require(0 <= x0 && x0 < x1 && x1 <= width && 0 <= y0 && y0 < y1 && y1 <= height, "GEOMETRY"); }
    private static boolean overlap(JsonNode a, JsonNode b) { return Math.min(a.get(2).asDouble(), b.get(2).asDouble()) > Math.max(a.get(0).asDouble(), b.get(0).asDouble()) && Math.min(a.get(3).asDouble(), b.get(3).asDouble()) > Math.max(a.get(1).asDouble(), b.get(1).asDouble()); }
    private static void vector(JsonNode value, int size) { require(value.isArray() && value.size() == size, "FRAME"); for (JsonNode item : value) require(item.isNumber() && Double.isFinite(item.asDouble()), "FRAME"); }
    private static boolean close(double a, double b) { return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= 1e-10 * Math.max(1, Math.max(Math.abs(a), Math.abs(b))); }
    private static boolean positiveNumber(JsonNode value) { return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() > 0; }
    private static boolean pointer(JsonNode value) { return value.isTextual() && value.asText().startsWith("/") && !value.asText().matches(".*~(?:[^01]|$).*"); }
    private static boolean nonnegativeLong(JsonNode value) { return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0; }
    private static boolean positiveLong(JsonNode value) { return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0; }
    private static boolean positiveInt(JsonNode value) { return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0; }
    private static boolean nonnegativeInt(JsonNode value) { return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0; }
    private static boolean text(JsonNode value) { return value.isTextual() && !value.asText().trim().isEmpty(); }
    private static boolean empty(JsonNode value) { return value.isArray() && value.size() == 0; }
    private static boolean falseBoolean(JsonNode value) { return value.isBoolean() && !value.asBoolean(); }
    private static String sha(JsonNode value) { require(value.isTextual() && value.asText().matches("[a-f0-9]{64}"), "PROOF"); return value.asText(); }
    private static void same(JsonNode a, JsonNode b, String code) { require(a != null && b != null && !a.isMissingNode() && !b.isMissingNode() && a.equals(b), code); }
    private static Set<String> names(JsonNode value) { Set<String> result = new HashSet<>(); value.fieldNames().forEachRemaining(result::add); return result; }
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void fields(JsonNode value, Set<String> allowed, String code) { require(value != null && value.isObject() && allowed.containsAll(names(value)), code); }
    private static void require(boolean value, String code) { if (!value) throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY, "CANONICAL_NATIVE_PDF_" + code + "_INVALID"); }
}
