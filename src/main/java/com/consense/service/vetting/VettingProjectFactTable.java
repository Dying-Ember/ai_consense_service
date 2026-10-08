package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingCorpus.TableRow;
import lombok.Data;

import java.util.*;
import java.util.regex.Pattern;

/** Source-linked native table structure. These annotations are untrusted data, never findings or new quotations. */
public final class VettingProjectFactTable {
    private VettingProjectFactTable() {}

    @Data public static class Result {
        private List<Row> rows = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }

    @Data public static class Cell {
        private int columnIndex;
        /** UTF-16 offsets in the original row text (and therefore in the whole, unsplit Part). */
        private int startOffset, endOffset;
        private String rawText, rawHeader;
    }

    @Data public static class Row {
        private String sourceChunkId, sourceHash, documentId, fileKey, fileName;
        private String anchor, blockId, pageNo;
        private int partStartOffset, partEndOffset;
        private String tableLocation, headerBlockId, headerLocation;
        private int rowIndex;
        private String rawReference, rawRequest, rawReply, replyState;
        private Cell referenceCell, requestCell, replyCell;
        private List<Cell> cells;
        private List<String> referenceIds, matchedRequestedReferenceIds;
        private boolean derivedStructure = true, untrustedSource = true;
    }

    private static final Pattern REFERENCE = Pattern.compile("[A-Z][A-Z._]*[0-9]+(?:\\.[0-9]+)*(?:\\([A-Z0-9]+\\))*");
    private static final Pattern SHORTHAND = Pattern.compile("(?:\\([A-Z0-9]+\\))+");

    /** Uses native ordered cells only. Missing or ambiguous structure is a warning, not an inferred reply. */
    public static Result rows(List<Chunk> chunks, List<String> requestedReferenceIds) {
        Result result = new Result();
        List<String> requested = new ArrayList<>();
        for (String input : requestedReferenceIds == null ? Collections.<String>emptyList() : requestedReferenceIds) {
            String value = canonicalReference(input);
            if (value != null && REFERENCE.matcher(value).matches()) {
                if (!requested.contains(value)) requested.add(value);
            } else result.getWarnings().add("Project fact requested reference could not be parsed; no target was inferred.");
        }
        // Invalid filtering must not silently become an unfiltered request.
        if (requestedReferenceIds != null && !requestedReferenceIds.isEmpty() && requested.isEmpty()) return result;
        for (Chunk chunk : chunks == null ? Collections.<Chunk>emptyList() : chunks) {
            if (chunk == null || !"project_fact".equals(chunk.getRole())) continue;
            for (Part part : chunk.getParts() == null ? Collections.<Part>emptyList() : chunk.getParts()) {
                if (part == null) continue;
                TableRow table = part.getTable();
                if (table == null) {
                    if (nvl(part.getBlockId()).contains(":table-row:") || nvl(part.getAnchor()).contains("/table-row/"))
                        warning(result, chunk, part, "native table schema is unavailable; flattened text was not split");
                    continue;
                }
                String problem = validate(part, table);
                if (problem != null) { warning(result, chunk, part, problem); continue; }
                Map<String,Integer> columns = new LinkedHashMap<>();
                for (int index = 0; index < table.getHeaders().size(); index++) {
                    String role = headerRole(table.getHeaders().get(index));
                    if (role != null && columns.putIfAbsent(role, index) != null) {
                        problem = "duplicate recognized header role " + role; break;
                    }
                }
                if (problem != null) { warning(result, chunk, part, problem); continue; }
                if (!columns.containsKey("reference") || !columns.containsKey("request")) {
                    warning(result, chunk, part, "Clause and Required input columns were not explicitly identified"); continue;
                }
                if (part.getBlockId().equals(table.getHeaderBlockId())) continue; // Original header, not a request row.
                List<Cell> cells = cells(table);
                Cell reference = cells.get(columns.get("reference")), request = cells.get(columns.get("request"));
                List<String> references = references(reference.getRawText());
                if (references.isEmpty()) {
                    warning(result, chunk, part, "reference cell is empty or unsupported; no clause target was inferred"); continue;
                }
                List<String> matched = new ArrayList<>();
                for (String target : requested) if (references.stream().anyMatch(ref -> related(ref, target))) matched.add(target);
                if (!requested.isEmpty() && matched.isEmpty()) continue;
                Cell reply = columns.containsKey("reply") ? cells.get(columns.get("reply")) : null;
                Row row = new Row();
                row.setSourceChunkId(chunk.getId()); row.setSourceHash(chunk.getSourceHash()); row.setDocumentId(chunk.getDocumentId());
                row.setFileKey(chunk.getFileKey()); row.setFileName(chunk.getFileName());
                row.setAnchor(part.getAnchor()); row.setBlockId(part.getBlockId()); row.setPageNo(part.getPageNo());
                row.setPartStartOffset(part.getStartOffset()); row.setPartEndOffset(part.getEndOffset());
                row.setTableLocation(table.getTableLocation()); row.setHeaderBlockId(table.getHeaderBlockId()); row.setHeaderLocation(table.getHeaderLocation()); row.setRowIndex(table.getRowIndex());
                row.setCells(cells); row.setReferenceCell(reference); row.setRequestCell(request); row.setReplyCell(reply);
                row.setRawReference(reference.getRawText()); row.setRawRequest(request.getRawText()); row.setRawReply(reply == null ? null : reply.getRawText());
                row.setReplyState(reply == null ? "no_reply_column" : blank(reply.getRawText()) ? "blank" : "populated");
                row.setReferenceIds(references); row.setMatchedRequestedReferenceIds(matched); result.getRows().add(row);
            }
        }
        return result;
    }

    private static String validate(Part part, TableRow table) {
        if (table.getCells() == null || table.getHeaders() == null || table.getCells().isEmpty()
                || table.getCells().size() != table.getHeaders().size()
                || table.getCells().stream().anyMatch(Objects::isNull) || table.getHeaders().stream().anyMatch(Objects::isNull))
            return "native cells/header column counts are missing or inconsistent";
        if (!String.join(" | ", table.getCells()).equals(part.getText())) return "native cells do not match the original Part text";
        if (part.getStartOffset() != 0 || part.getEndOffset() != part.getText().length()) return "table Part is not a complete unsplit original row";
        if (blank(part.getBlockId()) || blank(table.getTableLocation()) || blank(table.getHeaderBlockId()) || blank(table.getHeaderLocation())
                || table.getRowIndex() < 0 || !table.getHeaderLocation().startsWith(table.getTableLocation() + "/table-row/")
                || !nvl(part.getAnchor()).endsWith(table.getTableLocation() + "/table-row/" + table.getRowIndex()))
            return "table/header source provenance is missing or inconsistent";
        return null;
    }

    private static List<Cell> cells(TableRow table) {
        List<Cell> out = new ArrayList<>(); int start = 0;
        for (int index = 0; index < table.getCells().size(); index++) {
            String raw = table.getCells().get(index); Cell value = new Cell();
            value.setColumnIndex(index); value.setRawText(raw); value.setRawHeader(table.getHeaders().get(index));
            value.setStartOffset(start); value.setEndOffset(start + raw.length()); out.add(value); start += raw.length() + 3;
        }
        return out;
    }

    private static List<String> references(String raw) {
        List<String> out = new ArrayList<>(); String parent = null;
        for (String token : nvl(raw).split("[,，]", -1)) {
            String value = canonicalReference(token);
            if (value != null && REFERENCE.matcher(value).matches()) {
                parent = value.replaceFirst("\\([^()]+\\)$", "");
            } else if (parent != null && value != null && SHORTHAND.matcher(value).matches()) value = parent + value;
            else return Collections.emptyList();
            if (!REFERENCE.matcher(value).matches()) return Collections.emptyList();
            if (!out.contains(value)) out.add(value);
        }
        return out;
    }

    private static boolean related(String first, String second) { return first.equals(second) || child(first, second) || child(second, first); }
    private static boolean child(String candidate, String parent) {
        if (!candidate.startsWith(parent) || candidate.length() == parent.length()) return false;
        char boundary = candidate.charAt(parent.length()); return boundary == '(' || boundary == '.';
    }
    private static String canonicalReference(String value) { return value == null ? null : value.replaceAll("[\\s\\p{Z}]+", "").toUpperCase(Locale.ROOT); }
    private static String headerRole(String value) {
        String header = nvl(value).replaceAll("[\\s\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);
        if ("clause".equals(header)) return "reference";
        if ("required input".equals(header)) return "request";
        if ("reply".equals(header)) return "reply";
        return null;
    }
    private static boolean blank(String value) { return nvl(value).replaceAll("[\\s\\p{Z}]+", "").isEmpty(); }
    private static String nvl(String value) { return value == null ? "" : value; }
    private static void warning(Result result, Chunk chunk, Part part, String reason) {
        result.getWarnings().add("Project fact table " + nvl(chunk.getId()) + " / " + nvl(part.getBlockId()) + " could not be parsed: " + reason + ".");
    }
}
