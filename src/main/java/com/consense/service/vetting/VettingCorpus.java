package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Immutable source snapshots and location-preserving chunks shared by retrieval and review. */
public final class VettingCorpus {
    public static final String METADATA_VERSION = "owner-clause-v2";
    /** Segmentation identity is separate from the unchanged owner metadata shape. */
    public static final String SEGMENTATION_VERSION = "standalone-section-boundary-v1";
    /** Native row payload changes require new retrieval identities even when source text is unchanged. */
    public static final String NATIVE_TABLE_METADATA_VERSION = "native-rows-and-cell-slices-v3";
    public static final String SOURCE_QUALITY_METADATA_VERSION = VettingSourceQuality.VERSION;
    private VettingCorpus() {}
    @Data public static class Chunk {
        private String id, content, role, fileKey, documentId, fileName, anchor, pageNo, sourceHash;
        private List<Double> bbox;
        private List<Part> parts;
        private String clauseId, clauseHeadingLocation, metadataVersion, segmentationVersion, nativeTableMetadataVersion;
        private String sourceQualityMetadataVersion, sourceQualityHash;
        private VettingSourceQuality.Info sourceQuality;
    }
    @Data public static class Part {
        private String text, anchor, pageNo, blockId, extractionSource;
        private int startOffset, endOffset;
        private List<Double> bbox;
        /** Native cells are structural metadata, never inferred from flattened text or OCR. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private TableRow table;
        /** Only the selected UTF16 intersections, never complete unselected row values. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private NativeTableSlice tableSlice;
    }
    @Data public static class NativeTableSlice {
        private String version, sourceIdentityHash, tableLocation, rowTextSha256, cellLayoutSha256;
        private int rowIndex, rowUtf16Length, cellCount;
        private List<NativeCellLayout> cellLayout;
        private List<NativeCellSlice> cellSlices;
    }
    @Data public static class NativeCellLayout {
        private int columnIndex, rowStartOffset, rowEndOffset;
        private String cellTextSha256;
    }
    @Data public static class NativeCellSlice {
        private int columnIndex, rowStartOffset, rowEndOffset;
        private String text, textSha256;
    }
    @Data public static class TableRow {
        private String tableLocation, headerBlockId, headerLocation;
        /** The first native row is a compatibility input for project facts, never a generic legal/layout header claim. */
        private String headerBasis;
        private int rowIndex;
        private List<String> cells, headers;
    }
    private static final String NUMBER = "\\d+(?:\\.\\d+)*(?:\\([a-z0-9]+\\))*";
    private static final Pattern BARE_HEADING = Pattern.compile("^(" + NUMBER + ")(?=\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_HEADING_STYLE = Pattern.compile("(?i)(?:heading|标题|標題)[ _-]*[1-9]?");
    private static final Pattern WORD_TITLE_STYLE = Pattern.compile("(?i)(?:title|subtitle|副标题|副標題)");
    private static final Pattern CONTENTS_STYLE = Pattern.compile("(?i)(?:toc|contents|tableofcontents)[ _-]*[0-9]*");
    private static final Pattern CONTINUED_TITLE = Pattern.compile("(?i)\\(\\s*cont(?:inued|[’']?d|\\.)\\s*\\)");
    // A complete standalone title establishes a boundary, never a clause identifier.
    // Qualified labels may have dots/slashes/hyphens and Unicode Roman numerals.
    private static final String SECTION_LABEL = "[\\p{L}\\p{N}]+(?:[./-][\\p{L}\\p{N}]+)*";
    private static final Pattern UNNUMBERED_BOUNDARY = Pattern.compile(
            "(?:APPENDIX|ANNEX|SCHEDULE)\\s+" + SECTION_LABEL
                    + "(?:\\s+TO\\s+(?:APPENDIX|ANNEX|SCHEDULE)\\s+" + SECTION_LABEL + ")?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Explicit vetted roles override filename inference; legacy uploads retain safe defaults. */
    public static String sourceRole(SourceDocument d) {
        String explicit = nvl(d.getReviewRole()).trim().toLowerCase(Locale.ROOT);
        if (Arrays.asList("tender", "standard", "project_fact", "package_manifest").contains(explicit)) return explicit;
        if (SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(d.getCategory())) return "standard";
        String filename = nvl(d.getFileName()).toLowerCase(Locale.ROOT);
        if (SourceDocument.CATEGORY_PROJECT_INPUT.equals(d.getCategory())
                || filename.endsWith(".msg") || filename.endsWith(".eml") || filename.endsWith(".emlx")) return "project_fact";
        if (Arrays.asList("GCC", "SL").contains(nvl(d.getFileKey()).toUpperCase(Locale.ROOT))) return "standard";
        return "tender";
    }

    public static List<DocumentBlock> blocks(SourceDocument d) {
        if (!JsonUtils.isBlankText(d.getStructuredContentJson()))
            return JsonUtils.readList(d.getStructuredContentJson(), DocumentBlock.class);
        // Old uploads have no layout metadata. Preserve offsets, never invent pages.
        List<DocumentBlock> out = new ArrayList<>();
        String text = nvl(d.getTextContent());
        for (int start = 0; start < text.length(); start += 1000) {
            DocumentBlock b = new DocumentBlock(); b.setId("offset:" + start);
            b.setKind("legacy"); b.setLocation("character " + start);
            b.setText(text.substring(start, Math.min(start + 1000, text.length())));
            b.setSource("legacy-text"); out.add(b);
        }
        return out;
    }
    public static String sourceHash(SourceDocument d) {
        String structure = nvl(d.getStructuredContentJson());
        // Style-name enrichment changes segmentation metadata, not the source revision.
        // Leave legacy JSON byte-for-byte intact; compact enriched snapshots serialize
        // back to their exact pre-enrichment form after this one new field is removed.
        if (structure.contains("\"paragraphStyleName\"")) {
            JsonNode tree = JsonUtils.parse(structure); boolean enriched = false;
            if (tree.isArray()) for (JsonNode block : tree) {
                if (block.isObject() && block.has("paragraphStyleName")) {
                    ((ObjectNode) block).remove("paragraphStyleName"); enriched = true;
                }
            }
            if (enriched) structure = JsonUtils.write(tree);
        }
        return hash(nvl(d.getTextContent()) + "\n" + structure);
    }
    public static List<Chunk> chunks(List<SourceDocument> docs) {
        List<Chunk> out = new ArrayList<>();
        for (SourceDocument d : docs) {
            String clause = "", headingLocation = null; List<DocumentBlock> group = new ArrayList<>(); int length = 0;
            String digest = sourceHash(d);
            String owner = nvl(d.getFileKey()).trim().toUpperCase(Locale.ROOT);
            List<DocumentBlock> sourceBlocks = blocks(d);
            VettingSourceQuality.Info quality = VettingSourceQuality.from(d, sourceBlocks);
            Map<String,TableRow> nativeTables = nativeTables(sourceBlocks, "project_fact".equals(sourceRole(d)));
            Pattern ownedHeading = owner.isEmpty() || "OTHER".equals(owner) ? null : Pattern.compile(
                    "^(" + Pattern.quote(owner) + "(?:\\.[A-Z]+)?\\s*\\.?\\s*" + NUMBER
                            + "(?:\\.[A-Z]+" + NUMBER + ")*(?:\\.\\s*[A-Z](?=\\s|$))?)(?=\\s|\\.[A-Z]|[A-Z\\p{IsHan}]|$)", Pattern.CASE_INSENSITIVE);
            for (DocumentBlock b : sourceBlocks) {
                if (nvl(b.getText()).trim().isEmpty()) continue;
                String detected = heading(b, owner, ownedHeading);
                boolean supplementary = Arrays.asList("header", "footer", "footnote", "endnote").contains(b.getKind());
                String nextClause = supplementary ? "" : detected != null ? detected : clause;
                boolean continued = detected != null && detected.equals(clause) && continuedTitle(b);
                boolean headingChanged = (detected != null && !continued) || supplementary;
                boolean pageChanged = !group.isEmpty() && !Objects.equals(b.getPageNo(), group.get(0).getPageNo());
                if (!group.isEmpty() && (length + b.getText().length() > 1400 || pageChanged || headingChanged)) {
                    emit(out, d, digest, clause, headingLocation, group, nativeTables, quality); group = new ArrayList<>(); length = 0;
                }
                clause = nextClause;
                if (headingChanged) headingLocation = clause.isEmpty() ? null : b.getLocation();
                // Very long tables/pages are split with offsets into the same source block.
                if (b.getText().length() > 1400) {
                    if (!group.isEmpty()) { emit(out, d, digest, clause, headingLocation, group, nativeTables, quality); group.clear(); length = 0; }
                    for (int step = 0; step < b.getText().length(); step += 1200) {
                        int start = step, end = Math.min(start + 1400, b.getText().length());
                        if (start > 0 && Character.isLowSurrogate(b.getText().charAt(start))
                                && Character.isHighSurrogate(b.getText().charAt(start - 1))) start--;
                        if (end < b.getText().length() && Character.isHighSurrogate(b.getText().charAt(end - 1))
                                && Character.isLowSurrogate(b.getText().charAt(end))) end--;
                        DocumentBlock part = new DocumentBlock(); part.setId(b.getId() + ":" + start);
                        part.setLocation(b.getLocation() + " @" + start); part.setPageNo(b.getPageNo());
                        part.setText(b.getText().substring(start, end)); part.setSource(b.getSource());
                        part.setBbox(b.getBbox()); emit(out, d, digest, clause, headingLocation, Collections.singletonList(part), nativeTables, quality);
                    }
                } else { group.add(b); length += b.getText().length() + 1; }
            }
            if (!group.isEmpty()) emit(out, d, digest, clause, headingLocation, group, nativeTables, quality);
        }
        return out;
    }
    private static Map<String,TableRow> nativeTables(List<DocumentBlock> blocks, boolean projectFactCompatibility) {
        Pattern rowLocation = Pattern.compile("^(.+)/table-row/(\\d+)$");
        Map<String,Integer> blockIds = new HashMap<>(), positions = new HashMap<>();
        for (DocumentBlock block : blocks) {
            blockIds.merge(nvl(block.getId()), 1, Integer::sum);
            String position = nativeRowPosition(block, rowLocation);
            if (position != null) positions.merge(position, 1, Integer::sum);
        }
        Map<String,DocumentBlock> headers = new HashMap<>();
        for (DocumentBlock block : blocks) {
            Matcher location = rowLocation.matcher(nvl(block.getLocation()));
            String position = nativeRowPosition(block, rowLocation);
            if (nativeCellsMatch(block) && uniqueNativeIdentity(block, position, blockIds, positions)
                    && location.matches() && "0".equals(location.group(2)))
                headers.put(location.group(1), block);
        }
        Map<String,TableRow> result = new HashMap<>();
        for (DocumentBlock block : blocks) {
            List<String> cells = block.getCells();
            Matcher location = rowLocation.matcher(nvl(block.getLocation()));
            String position = nativeRowPosition(block, rowLocation);
            if (!nativeCellsMatch(block) || !uniqueNativeIdentity(block, position, blockIds, positions) || !location.matches()) continue;
            int rowIndex;
            try { rowIndex = Integer.parseInt(location.group(2)); }
            catch (NumberFormatException unavailableRowPosition) { continue; }
            TableRow row = new TableRow(); row.setTableLocation(location.group(1));
            row.setRowIndex(rowIndex); row.setCells(new ArrayList<>(cells)); row.setHeaderBasis("unknown");
            DocumentBlock header = headers.get(location.group(1));
            if (projectFactCompatibility && header != null) {
                row.setHeaders(new ArrayList<>(header.getCells())); row.setHeaderBlockId(header.getId()); row.setHeaderLocation(header.getLocation());
                row.setHeaderBasis("project_fact_first_native_row_heuristic_compatibility");
            }
            result.put(block.getId(), row);
        }
        return result;
    }
    private static String nativeRowPosition(DocumentBlock block, Pattern rowLocation) {
        if (!"table_row".equals(block.getKind())) return null;
        Matcher location = rowLocation.matcher(nvl(block.getLocation()));
        if (!location.matches()) return null;
        try { return location.group(1) + "/table-row/" + Integer.parseInt(location.group(2)); }
        catch (NumberFormatException unavailableRowPosition) { return null; }
    }
    private static boolean uniqueNativeIdentity(DocumentBlock block, String position,
                                                Map<String,Integer> blockIds, Map<String,Integer> positions) {
        return position != null && Integer.valueOf(1).equals(blockIds.get(nvl(block.getId())))
                && Integer.valueOf(1).equals(positions.get(position));
    }
    private static boolean nativeCellsMatch(DocumentBlock block) {
        String source = nvl(block.getSource()).trim().toLowerCase(Locale.ROOT);
        List<String> cells = block.getCells();
        // Historical saved native table rows may lack the source label; OCR/PDF/legacy blocks do not establish native cells.
        return "table_row".equals(block.getKind()) && (source.isEmpty() || "docx".equals(source))
                && !nvl(block.getId()).trim().isEmpty() && cells != null && !cells.isEmpty()
                && cells.stream().noneMatch(Objects::isNull) && String.join(" | ", cells).equals(block.getText());
    }
    private static String heading(DocumentBlock block, String owner, Pattern ownedHeading) {
        String kind = nvl(block.getKind()), style = nvl(block.getParagraphStyleName()).isEmpty()
                ? nvl(block.getParagraphStyle()) : block.getParagraphStyleName(), text = nvl(block.getText()).trim();
        boolean paragraph = "paragraph".equals(kind);
        boolean pdf = Arrays.asList("pdf_line", "ocr_line").contains(kind);
        boolean table = "table_row".equals(kind);
        if ((!paragraph && !pdf && !table) || CONTENTS_STYLE.matcher(style).matches()) return null;
        if (!table && UNNUMBERED_BOUNDARY.matcher(text).matches()) return "";
        if (table) {
            // Contract titles can occupy one table row, followed by empty layout cells.
            // Numbered schedules and rows with multiple populated cells are not headings.
            List<String> populated = block.getCells() == null ? Collections.emptyList() : block.getCells().stream()
                    .filter(cell -> !nvl(cell).trim().isEmpty()).collect(Collectors.toList());
            String payload = (populated.isEmpty() ? text : populated.get(0)).trim();
            // A title and its following paragraphs may share the same cell. Only the
            // first source line declares the scope; later in-cell references cannot.
            String title = payload.split("\\r?\\n", 2)[0].replaceFirst("(?:\\s*\\|\\s*)+$", "").trim();
            // A native cell can contain an appendix identifier as an attachment
            // value. It cannot independently establish a document section boundary.
            if (ownedHeading == null || title.isEmpty() || title.length() > 200 || title.contains("|") || title.contains("...")) return null;
            Matcher explicit = ownedHeading.matcher(title);
            if (!explicit.find()) return null;
            String remainder = title.substring(explicit.end()).trim();
            if (remainder.isEmpty() || remainder.matches("(?is).*\\b(?:shall|must|may|will|refers?|pursuant)\\b.*")
                    || remainder.matches("(?is)^(?:[,;:/()]|and\\b|or\\b|to\\b).*")
                    || remainder.matches("(?s).*\\s\\d+\\s*$")) return null;
            // A second populated cell may qualify applicability. It establishes a
            // boundary, but cannot establish one unqualified clause scope.
            if (populated.size() > 1) return "";
            return explicit.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        }
        if (text.matches("(?s).*\\.{3,}.*")) return null;
        if (ownedHeading != null) {
            Matcher explicit = ownedHeading.matcher(text);
            if (explicit.find()) return explicit.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        }
        // Title/Subtitle is source structure, not proof that a bare date/number is
        // a contract clause. Stop inherited ownership until an explicit heading.
        if (paragraph && WORD_TITLE_STYLE.matcher(style).matches()) return "";
        boolean styledHeading = paragraph && WORD_HEADING_STYLE.matcher(style).matches();
        Matcher bare = BARE_HEADING.matcher(text);
        if (bare.find() && !owner.isEmpty() && !"OTHER".equals(owner)) {
            String number = bare.group(1), rest = text.substring(bare.end()).trim();
            // Unstyled prose and table entries do not declare a global clause. Native PDF
            // main-clause titles may lack OOXML styles, so accept short titles, not sentences.
            boolean pdfTitle = pdf && number.matches("\\d+(?:\\.\\d+)+") && !rest.isEmpty()
                    && Character.isUpperCase(rest.charAt(0)) && rest.length() <= 160 && !rest.endsWith(".")
                    && !rest.matches("(?is).*\\b(?:shall|must|may|will|under|pursuant|subject|refers?)\\b.*");
            if (styledHeading || pdfTitle) return owner + number.toUpperCase(Locale.ROOT);
        }
        // An unnumbered Word section heading bounds the preceding clause's scope.
        return styledHeading ? "" : null;
    }

    private static boolean continuedTitle(DocumentBlock block) {
        // Repeated table/paragraph titles continue the original source scope only when
        // the source explicitly marks them as continued. New headings still bound it.
        String firstLine = nvl(block.getText()).split("\\r?\\n", 2)[0];
        return CONTINUED_TITLE.matcher(firstLine).find();
    }

    private static void emit(List<Chunk> out, SourceDocument d, String digest, String clause, String headingLocation, List<DocumentBlock> blocks,
                             Map<String,TableRow> nativeTables, VettingSourceQuality.Info quality) {
        String qualityHash = VettingSourceQuality.hash(quality);
        Chunk c = new Chunk(); DocumentBlock b = blocks.get(0);
        c.setId(UUID.nameUUIDFromBytes((METADATA_VERSION + "|" + SEGMENTATION_VERSION + "|" + NATIVE_TABLE_METADATA_VERSION + "|" + SOURCE_QUALITY_METADATA_VERSION + "|" + qualityHash + "|" + sourceRole(d) + "|" + d.getId() + "|" + digest + "|" + blocks.stream().map(DocumentBlock::getId).collect(Collectors.joining(","))).getBytes(StandardCharsets.UTF_8)).toString());
        c.setContent(blocks.stream().map(DocumentBlock::getText).collect(Collectors.joining("\n")));
        c.setDocumentId(String.valueOf(d.getId())); c.setFileKey(nvl(d.getFileKey())); c.setFileName(d.getFileName());
        c.setRole(sourceRole(d));
        c.setMetadataVersion(METADATA_VERSION); c.setSegmentationVersion(SEGMENTATION_VERSION);
        c.setNativeTableMetadataVersion(NATIVE_TABLE_METADATA_VERSION);
        c.setSourceQualityMetadataVersion(SOURCE_QUALITY_METADATA_VERSION); c.setSourceQualityHash(qualityHash);
        c.setSourceQuality(JsonUtils.read(JsonUtils.write(quality), VettingSourceQuality.Info.class));
        c.setClauseId(clause.isEmpty() ? null : clause); c.setClauseHeadingLocation(headingLocation);
        String prefix = clause.isEmpty() ? "" : clause + " · ";
        c.setAnchor(prefix + nvl(b.getLocation()) + (blocks.size() > 1 ? " … " + nvl(blocks.get(blocks.size()-1).getLocation()) : ""));
        c.setPageNo(b.getPageNo() == null ? null : "P" + b.getPageNo()); c.setSourceHash(digest);
        if (blocks.size() == 1 && b.getBbox() != null) c.setBbox(Arrays.stream(b.getBbox()).boxed().collect(Collectors.toList()));
        List<Part> parts = new ArrayList<>();
        for (DocumentBlock item : blocks) {
            Part p = new Part(); p.setText(item.getText()); p.setExtractionSource(item.getSource()); p.setAnchor(prefix + nvl(item.getLocation()));
            Matcher offset = Pattern.compile("^(.*) @(\\d+)$").matcher(nvl(item.getLocation()));
            boolean split = offset.matches();
            int start = split ? Integer.parseInt(offset.group(2)) : 0;
            p.setBlockId(split ? nvl(item.getId()).replaceFirst(":\\d+$", "") : item.getId());
            p.setStartOffset(start); p.setEndOffset(start + nvl(item.getText()).length());
            // A split row does not expose complete cells; do not attach values outside its submitted source slice.
            if (!split) p.setTable(nativeTables.get(item.getId()));
            else if (nativeTables.get(p.getBlockId()) != null)
                p.setTableSlice(VettingNativeCellSlices.slice(c, p, nativeTables.get(p.getBlockId())));
            p.setPageNo(item.getPageNo() == null ? null : "P" + item.getPageNo());
            if (item.getBbox() != null) p.setBbox(Arrays.stream(item.getBbox()).boxed().collect(Collectors.toList()));
            parts.add(p);
        }
        c.setParts(parts);
        out.add(c);
    }
    public static FindingEvidence evidence(Chunk c, String side, String quote) {
        boolean located = quote != null && quote.trim().length() >= 12 && normalize(c.getContent()).contains(normalize(quote));
        String anchor = c.getAnchor(), page = c.getPageNo(); List<Double> bbox = c.getBbox();
        if (located && c.getParts() != null) for (Part part : c.getParts()) {
            if (normalize(part.getText()).contains(normalize(quote))) { anchor = part.getAnchor(); page = part.getPageNo(); bbox = part.getBbox(); break; }
        }
        return new FindingEvidence(side, c.getDocumentId(), c.getFileKey(), c.getFileName(), page, anchor, quote, located, c.getSourceHash(), bbox);
    }
    public static String normalize(String text) { return nvl(text).replaceAll("[\\s\\p{Z}]+", " ").trim(); }
    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format("%02x", b & 255)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String nvl(String text) { return text == null ? "" : text; }
}
