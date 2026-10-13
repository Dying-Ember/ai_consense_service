package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentParser;
import com.consense.document.OerDocumentIdentity;
import com.consense.document.OerSourceRouting;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/** Persist original identity and native candidates without accepting OCR or changing explicit user roles. */
public final class OerSourceMetadata {
    private OerSourceMetadata() {}

    private static class FixedRegistry {
        private static final OerSourceRouting ROUTER = load();
        private static OerSourceRouting load() {
            try (InputStream stream = OerSourceMetadata.class.getResourceAsStream("/oer-fixed-sources.json")) {
                if (stream == null) throw new IllegalStateException("Missing frozen OER source registry");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int size;
                while((size=stream.read(buffer))!=-1)bytes.write(buffer,0,size);
                JsonNode registry = JsonUtils.parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                if (!"consense.fixed-source-routing.v1".equals(registry.path("schema").asText())
                        || !"exact_original_file_bytes_sha256".equals(registry.path("identityMeaning").asText())
                        || !registry.path("sources").isArray()) throw new IllegalStateException("Invalid frozen OER source registry");
                List<OerSourceRouting.FixedSource> sources = new ArrayList<>();
                for (JsonNode source : registry.get("sources")) sources.add(new OerSourceRouting.FixedSource(
                        source.path("sourceSha256").asText(), source.path("kind").asText(),
                        source.path("sourcePurpose").asText(), source.path("role").isNull() ? null : source.path("role").asText(),
                        source.path("version").asText()));
                return new OerSourceRouting(sources);
            } catch (Exception failure) { throw new IllegalStateException("Cannot load frozen OER source registry", failure); }
        }
    }

    public static void capture(SourceDocument document, DocumentParser.ParsedDocument parsed, byte[] originalBytes) {
        capture(document, parsed, originalBytes, FixedRegistry.ROUTER);
    }

    /** Retain the uploaded original and its exclusion identity without producing parser evidence. */
    public static void captureDrawing(SourceDocument document, byte[] originalBytes) {
        String digest = originalSha256(originalBytes);
        if (document.getSourceSha256() != null && !digest.equals(document.getSourceSha256()))
            throw new IllegalArgumentException("Drawing source bytes changed before identity was persisted");
        OerDocumentIdentity identity = new OerDocumentIdentity();
        identity.setSourceSha256(digest); identity.setKind("DRAWING"); identity.setSourcePurpose("drawing");
        identity.setIdentityStatus("drawing".equals(document.getDocumentScope()) ? "explicit_scope" : "format_supported");
        identity.setEligibleModules(java.util.Collections.emptyList());
        identity.getIdentityEvidence().add(new OerDocumentIdentity.Evidence(
                "drawing".equals(document.getDocumentScope()) ? "upload_document_scope" : "native_format_or_metadata",
                null, "Drawing file is outside parsing and business evidence scope; no detail parser was called"));
        document.setSourceSha256(digest); document.setFileKey("DRAWING");
        document.setOerIdentityJson(JsonUtils.write(identity));
    }

    /** The parser binds its own input bytes; legacy mock results cannot inherit old upload identities. */
    public static void captureParsed(SourceDocument document, DocumentParser.ParsedDocument parsed) {
        String digest=parsed==null?null:parsed.getSourceSha256();
        if(digest==null) {
            document.setSourceSha256(null); document.setOerIdentityJson(null);
            document.setNativePdfEvidenceJson(parsed==null?null:JsonUtils.write(parsed.getNativePdfEvidence()));
            return;
        }
        persist(document,parsed,digest,FixedRegistry.ROUTER);
    }

    public static void capture(SourceDocument document, DocumentParser.ParsedDocument parsed,
                               byte[] originalBytes, OerSourceRouting router) {
        String digest = originalSha256(originalBytes);
        if (document.getSourceSha256() != null && !digest.equals(document.getSourceSha256()))
            throw new IllegalArgumentException("Uploaded source identity changed before parsed metadata was persisted");
        persist(document,parsed,digest,router);
    }

    private static void persist(SourceDocument document, DocumentParser.ParsedDocument parsed,
                                String digest, OerSourceRouting router) {
        OerDocumentIdentity identity = router.route(document.getFileName(), digest, parsed);
        if ("source_identity_mismatch".equals(identity.getIdentityStatus()))
            throw new IllegalArgumentException("Parsed metadata belongs to different source bytes");
        document.setSourceSha256(digest);
        document.setOerIdentityJson(JsonUtils.write(identity));
        document.setNativePdfEvidenceJson(parsed == null || parsed.getNativePdfEvidence() == null ? null
                : JsonUtils.write(parsed.getNativePdfEvidence()));
        if (!SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(document.getCategory())
                && !"UNKNOWN".equals(identity.getKind()) && !"EVALUATION_ANSWER".equals(identity.getKind()))
            document.setFileKey(identity.getKind());
        // reviewRole is the user's explicit input; the routed role is retained
        // separately and used only when there is no valid explicit role.
    }

    public static OerDocumentIdentity identity(SourceDocument document) {
        if (JsonUtils.isBlankText(document.getOerIdentityJson())) return null;
        OerDocumentIdentity identity = JsonUtils.read(document.getOerIdentityJson(), OerDocumentIdentity.class);
        if (document.getSourceSha256() == null || !document.getSourceSha256().equals(identity.getSourceSha256()))
            throw new IllegalArgumentException("Persisted OER identity does not match its uploaded source");
        return identity;
    }

    /** Reclassify exact retained original bytes without recapturing or clearing any parser evidence. */
    public static OerDocumentIdentity refreshedFixedIdentity(SourceDocument document, String verifiedOriginalSha256) {
        if (verifiedOriginalSha256 == null || !verifiedOriginalSha256.equals(document.getSourceSha256()))
            throw new IllegalArgumentException("Retained original identity changed before routing refresh");
        // No fabricated ParsedDocument: only a trusted exact-byte registry entry can resolve this refresh.
        return FixedRegistry.ROUTER.route(document.getFileName(), verifiedOriginalSha256, null);
    }

    /** null means unresolved; a non-null reason excludes the source from this module. */
    public static String moduleExclusion(SourceDocument document, String module) {
        if (!Arrays.asList("drafting", "vetting", "advisory").contains(module))
            throw new IllegalArgumentException("Unknown OER business module");
        OerDocumentIdentity identity = identity(document);
        if (identity == null) return null;
        if ("evaluation_answer".equals(identity.getSourcePurpose())) return "evaluation_answer_isolated";
        if ("standard_template".equals(identity.getSourcePurpose()) && !"drafting".equals(module))
            return "drafting_standard_template";
        if (identity.getEligibleModules() != null && !identity.getEligibleModules().contains(module))
            return "source_not_eligible_for_" + module;
        return null;
    }

    public static String originalSha256(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Original source bytes are required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest) value.append(String.format("%02x", b & 255));
            return value.toString();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
