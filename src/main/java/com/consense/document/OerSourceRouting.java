package com.consense.document;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** General source classification; filenames and isolated body keywords are only candidates. */
public final class OerSourceRouting {
    private static final List<String> MODULES = Collections.unmodifiableList(Arrays.asList("drafting", "vetting", "advisory"));
    private static final List<String> PURPOSES = Arrays.asList("standard_template", "project_document", "reference_standard",
            "project_fact", "package_manifest", "evaluation_answer", "unknown");
    private final Map<String, FixedSource> fixedSources = new HashMap<>();

    public OerSourceRouting() { this(Collections.emptyList()); }
    public OerSourceRouting(List<FixedSource> fixedSources) {
        for (FixedSource source : fixedSources) {
            String sha = validSha(source.sourceSha256);
            if (sha == null || !PURPOSES.contains(source.sourcePurpose)) throw new IllegalArgumentException("Invalid fixed source identity");
            if ("standard_template".equals(source.sourcePurpose) && !Arrays.asList("NTT", "SCT", "SCC").contains(source.kind))
                throw new IllegalArgumentException("Only NTT/SCT/SCC can be registered as a drafting standard template");
            if (this.fixedSources.put(sha, source) != null) throw new IllegalArgumentException("Duplicate fixed source SHA");
        }
    }

    /** Identity only: a drawing label or native format must precede every detail parser. */
    public static boolean isDrawing(String documentScope, String fileName, byte[] bytes) {
        if ("drawing".equals(documentScope)) return true;
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String header = new String(bytes, 0, Math.min(bytes.length, 64), java.nio.charset.StandardCharsets.US_ASCII);
        if (name.endsWith(".dwg") && header.matches("(?s)^AC10[0-9]{2}.*")) return true;
        if (name.endsWith(".dxf") && (header.startsWith("AutoCAD Binary DXF")
                || header.matches("(?s)^\\s*0\\s+SECTION\\s+.*"))) return true;
        if (header.startsWith("%PDF-")) {
            try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
                org.apache.pdfbox.pdmodel.PDDocumentInformation info = pdf.getDocumentInformation();
                return drawingLabel(info.getTitle()) || drawingLabel(info.getSubject());
            } catch (java.io.IOException invalidPdf) { return false; }
        }
        return false;
    }

    private static boolean drawingLabel(String label) {
        return label != null && label.trim().matches("(?i)(?:engineering |technical |施工)?(?:drawings?|图纸|圖紙)");
    }

    public OerDocumentIdentity route(String fileName, String sourceSha256, DocumentParser.ParsedDocument parsed) {
        OerDocumentIdentity result = new OerDocumentIdentity();
        String sha = validSha(sourceSha256); result.setSourceSha256(sha);
        if (sha == null || (parsed != null && parsed.getSourceSha256() != null && !sha.equalsIgnoreCase(parsed.getSourceSha256()))) {
            result.setIdentityStatus("source_identity_mismatch");
            result.getIdentityEvidence().add(new OerDocumentIdentity.Evidence("byte_identity", null, "Missing/invalid original SHA or parsed original-byte identity differs"));
            return result;
        }
        FixedSource fixed = fixedSources.get(sha);
        if (fixed != null) {
            result.setKind(fixed.kind); result.setSourcePurpose(fixed.sourcePurpose); result.setRole(fixed.role);
            result.setVersion(fixed.version); result.setIdentityStatus("sha_verified");
            result.setEligibleModules(eligible(fixed.kind, fixed.sourcePurpose));
            result.getIdentityEvidence().add(new OerDocumentIdentity.Evidence("fixed_source_sha256", null, "Exact original bytes match the supplied source manifest"));
            return result;
        }
        String title = firstTitle(parsed), content = parsed == null || parsed.getText() == null ? "" : parsed.getText();
        String titleKind = titleKind(title);
        if (titleKind != null) {
            result.getIdentityEvidence().add(new OerDocumentIdentity.Evidence("native_or_parsed_title", "first_content_block", "Title supports " + titleKind + "; content quality and applicability remain separate"));
            String purpose = "unknown";
            if (Arrays.asList("NTT", "SCT", "SCC").contains(titleKind)) {
                if (projectIdentifier(content)) purpose = "project_document";
                else result.getCandidates().add(new OerDocumentIdentity.Candidate(titleKind, "standard_template", "An unfilled title alone does not establish a standard template"));
            } else if ("PRE".equals(titleKind) && projectIdentifier(content)) purpose = "project_document";
            else if (Arrays.asList("GCC", "GS").contains(titleKind)) purpose = "reference_standard";
            else if ("GCT".equals(titleKind) && content.matches("(?is).*\\b(?:tenderer|tender|contract)\\b.*")) purpose = "project_document";
            else if (Arrays.asList("BQ", "SOR", "GENERAL_SUMMARY").contains(titleKind)
                    && Pattern.compile("(?i)\\b(?:quantity|rate|amount|price|pricing|bill|total|sum)\\b").matcher(content).find()) purpose = "project_document";
            // PRE is deliberately not caught by technical-PSS title rules.
            else if ("WORKS_PSS".equals(titleKind) && Pattern.compile("(?i)\\b(?:materials|workmanship|construction|installation|technical)\\b").matcher(content).find()) purpose = "project_document";
            if (!"unknown".equals(purpose)) {
                result.setKind(titleKind); result.setSourcePurpose(purpose); result.setIdentityStatus("content_supported");
                result.setRole("reference_standard".equals(purpose) ? "standard" : "tender");
                result.setEligibleModules(eligible(titleKind, purpose));
            } else result.getCandidates().add(new OerDocumentIdentity.Candidate(titleKind, purpose, "Purpose needs actual project/standard identity evidence"));
        }
        String filenameKind = filenameCandidate(fileName);
        if (filenameKind != null && !Objects.equals(filenameKind, result.getKind()))
            result.getCandidates().add(new OerDocumentIdentity.Candidate(filenameKind, "unknown", "Filename is a clue, never the adoption or module decision"));
        if (result.getIdentityEvidence().isEmpty()) result.getIdentityEvidence().add(new OerDocumentIdentity.Evidence("unresolved", null, "No sufficient source identity evidence; candidates and original source remain available"));
        return result;
    }

    private static List<String> eligible(String kind, String purpose) {
        if (Arrays.asList("BQ", "SOR", "GENERAL_SUMMARY", "WORKS_PSS", "SL_NON_TECHNICAL").contains(kind)
                || "evaluation_answer".equals(purpose)) return Collections.emptyList();
        if ("standard_template".equals(purpose)) return Collections.singletonList("drafting");
        if ("unknown".equals(purpose)) return null;
        return MODULES;
    }
    private static String validSha(String value) { return value != null && value.matches("(?i)[0-9a-f]{64}") ? value.toLowerCase(Locale.ROOT) : null; }
    private static boolean projectIdentifier(String text) {
        return Pattern.compile("(?im)^\\s*(?:contract|tender|project)\\s*(?:no\\.?|number|reference|ref\\.?|title)\\s*[:：#]\\s*(?!(?:not provided|to be completed|insert|unknown|n/a)\\b)(?![.＿_\\- ]*(?:$|\\[))(?:[\\p{L}\\p{N}][\\p{L}\\p{N}/._ -]{2,})\\s*$").matcher(text).find();
    }
    private static String firstTitle(DocumentParser.ParsedDocument parsed) {
        if (parsed == null) return "";
        StringBuilder title = new StringBuilder(); int lines = 0;
        if (parsed.getBlocks() != null) for (DocumentBlock block : parsed.getBlocks()) {
            String text = block.getText();
            if (text != null) for (String line : text.split("\\n")) if (!line.trim().isEmpty()) {
                title.append(line.trim()).append('\n'); if (++lines >= 4) return title.toString();
            }
        }
        if (lines > 0) return title.toString();
        String text = parsed.getText();
        if (text != null) for (String line : text.replaceFirst("(?s)^--- P\\d+ ---\\s*", "").split("\\n")) if (!line.trim().isEmpty()) {
            title.append(line.trim()).append('\n'); if (++lines >= 4) break;
        }
        return title.toString();
    }
    private static String titleKind(String title) {
        // A PRE cover commonly prints PARTICULAR SPECIFICATION and PRELIMINARIES on separate lines.
        for (String line : title.split("\\n")) { String kind = titledKind(line); if (kind != null) {
            if ("WORKS_PSS".equals(kind) && Pattern.compile("(?im)^\\s*preliminaries(?:\\s+specification)?\\s*$").matcher(title).find()) return "PRE";
            return kind;
        } }
        return null;
    }
    private static String filenameCandidate(String name) {
        if (name == null) return null;
        String value = name.replace('_', ' ').replace('-', ' ').replaceFirst("(?i)\\.[a-z0-9]+$", "");
        for (String kind : Arrays.asList("NTT", "SCT", "SCC", "GCT", "GCC", "PRE", "GS", "SL", "BQ", "SOR"))
            if (Pattern.compile("(?i)(?:^|\\W)" + kind + "(?:$|\\W)").matcher(value).find()) return kind;
        return titledKind(value);
    }
    private static String titledKind(String title) {
        String value = title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        value = value.trim().replaceFirst("^(?:[0-9]+[. )-]*|appendix [a-z0-9]+[. :-]*)", "").trim();
        if (value.matches("notice to tenderers?(?: \\(ntt\\))?")) return "NTT";
        if (value.matches("special conditions? of tender(?: \\(sct\\))?")) return "SCT";
        if (value.matches("special conditions? of contract(?: \\(scc\\))?")) return "SCC";
        if (value.matches("general conditions? of tender(?: \\(gct\\))?")) return "GCT";
        if (value.matches("general conditions? of contract(?: \\(gcc\\))?")) return "GCC";
        if (value.matches("general specification(?: for [a-z /-]+)?")) return "GS";
        if (value.matches("specification library(?: [0-9]{4})?")) return "SL";
        if (value.matches("(?:particular specification[-: ]*)?preliminaries(?: specification)?")) return "PRE";
        if (value.matches("bills? of quantities")) return "BQ";
        if (value.matches("schedule of rates")) return "SOR";
        if (value.matches("general summary")) return "GENERAL_SUMMARY";
        if (value.matches("particular specification(?:[-: ]+(?:architectural|structural|building services|civil|foundation|demolition|drainage|external|landscape)(?: works)?)?")) return "WORKS_PSS";
        return null;
    }

    /** Supplied trusted source registry, versioned outside this classifier; no project hashes embedded in code. */
    public static final class FixedSource {
        private final String sourceSha256, kind, sourcePurpose, role, version;
        public FixedSource(String sourceSha256, String kind, String sourcePurpose, String role, String version) {
            this.sourceSha256 = sourceSha256; this.kind = kind; this.sourcePurpose = sourcePurpose; this.role = role; this.version = version;
        }
    }
}
