package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import java.util.List;

/** Compares saved template text without changing either source representation. */
final class DraftTemplateSourceText {
    private DraftTemplateSourceText() { }

    static boolean matches(String saved, DocumentParser.ParsedDocument fresh) {
        if (saved == null || fresh == null) return false;
        if (saved.equals(fresh.getText())) return true;
        List<DocumentBlock> blocks = fresh.getBlocks();
        if (blocks == null) return false;
        StringBuilder legacy = new StringBuilder();
        for (DocumentBlock block : blocks) {
            if (block == null) return false;
            if (!pureBlankTableRow(block) && !JsonUtils.isBlankText(block.getText())) {
                legacy.append(block.getText()).append("\n\n");
            }
        }
        // Exact legacy DocumentParser.joinBlocks projection; saved text stays byte-for-byte.
        String projected = legacy.toString().replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u00A0', ' ').replaceAll("[ \\t]+", " ")
                .replaceAll("\n{3,}", "\n\n").trim();
        return saved.equals(projected);
    }

    private static boolean pureBlankTableRow(DocumentBlock row) {
        if (!"table_row".equals(row.getKind()) || row.getCells() == null || row.getCells().isEmpty()) return false;
        for (String cell : row.getCells()) if (cell == null || !JsonUtils.isBlankText(cell)) return false;
        if (!JsonUtils.isBlankText(row.getOriginalText()) || !JsonUtils.isBlankText(row.getDeletedText())
                || !JsonUtils.isBlankText(row.getStrikeText())) return false;
        if (nonEmpty(row.getWordNumbering()) || nonEmpty(row.getWordSymbols()) || nonEmpty(row.getWordWarnings())) return false;
        if (!String.join(" | ", row.getCells()).equals(row.getText())) return false;
        if (row.getWordCells() != null) for (DocumentBlock.WordCell cell : row.getWordCells()) {
            if (cell == null || !JsonUtils.isBlankText(cell.getText()) || !JsonUtils.isBlankText(cell.getOriginalText())
                    || !JsonUtils.isBlankText(cell.getDeletedText()) || !JsonUtils.isBlankText(cell.getStrikeText())) return false;
        }
        return true;
    }

    private static boolean nonEmpty(List<?> values) { return values != null && !values.isEmpty(); }
}
