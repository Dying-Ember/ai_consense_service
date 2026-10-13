package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.document.ParseCoverage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class DraftTemplateSourceTextTest {
    private static DocumentBlock paragraph(String text) {
        DocumentBlock block = new DocumentBlock(); block.setKind("paragraph"); block.setText(text); return block;
    }
    private static DocumentBlock blankRow() {
        DocumentBlock row = new DocumentBlock(); row.setKind("table_row");
        row.setCells(Arrays.asList("", "", "")); row.setText(" |  | ");
        row.setWordCells(Arrays.asList(new DocumentBlock.WordCell(), new DocumentBlock.WordCell(), new DocumentBlock.WordCell()));
        return row;
    }
    private static DocumentParser.ParsedDocument parsed(String text, List<DocumentBlock> blocks) {
        return new DocumentParser.ParsedDocument(text, Collections.emptyList(), 0, false, "", blocks, new ParseCoverage());
    }
    private static DocumentParser.ParsedDocument withRow(DocumentBlock row) {
        return parsed("Clause.\n\n" + row.getText().trim(), Arrays.asList(paragraph("Clause."), row));
    }
    @Test void exactCurrentProjectionRemainsAcceptedEvenWithContentfulMetadata() {
        DocumentBlock row=blankRow(); row.setDeletedText("deleted obligation");
        DocumentParser.ParsedDocument fresh=withRow(row);
        assertTrue(DraftTemplateSourceText.matches(fresh.getText(),fresh));
    }
    @Test void legacyBlankRowProjectionDoesNotMutateAnyBlocksOrChannels() {
        DocumentBlock row=blankRow(); DocumentParser.ParsedDocument fresh=withRow(row);
        String before=JsonUtils.write(fresh.getBlocks());
        assertTrue(DraftTemplateSourceText.matches("Clause.",fresh));
        assertEquals(before,JsonUtils.write(fresh.getBlocks())); assertEquals(2,fresh.getBlocks().size());
        assertEquals(3,row.getWordCells().size()); assertEquals(" |  | ",row.getText());
    }
    @Test void compatibilityUsesTheExactHistoricalJoinAndNormalization() {
        DocumentBlock row=blankRow();
        DocumentParser.ParsedDocument fresh=parsed("current differs",Arrays.asList(
                paragraph("  A\t\tB\u00a0C\r\n\r\n\r\nD  "),row,paragraph("  E  ")));
        assertTrue(DraftTemplateSourceText.matches("A B C\n\nD \n\n E",fresh));
    }
    @Test void whitespaceOnlyCellsAndWordChannelsRemainBlank() {
        DocumentBlock row=blankRow(); row.setCells(Arrays.asList(" ","\t",""));
        row.setText(String.join(" | ",row.getCells())); row.getWordCells().get(0).setText(" \t");
        assertTrue(DraftTemplateSourceText.matches("Clause.",withRow(row)));
    }
    @Test void legacyBlankRowsWithoutWordCellsRemainCompatible() {
        DocumentBlock row=blankRow();row.setWordCells(Collections.emptyList());
        assertTrue(DraftTemplateSourceText.matches("Clause.",withRow(row)));
    }
    static Stream<Arguments> retainedRowCases() {
        return Stream.of(
            item("literal pipes paragraph",r->r.setKind("paragraph")),
            item("literal compact pipes paragraph",r->{r.setKind("paragraph");r.setText("| |");}),
            item("non-row kind",r->r.setKind("TABLE_ROW")),
            item("missing cells",r->r.setCells(null)),
            item("empty cells",r->r.setCells(Collections.emptyList())),
            item("null cell cannot erase literal null",r->{r.setCells(Arrays.asList("",null));r.setText(" | null");}),
            item("partial nonblank cells",r->{r.setCells(Arrays.asList("","obligation",""));r.setText(" | obligation | ");}),
            item("original channel",r->r.setOriginalText("original obligation")),
            item("deleted channel",r->r.setDeletedText("deleted obligation")),
            item("strike channel",r->r.setStrikeText("struck obligation")),
            item("number metadata",r->r.setWordNumbering(Collections.singletonList(new DocumentBlock.WordNumbering()))),
            item("symbol metadata",r->r.setWordSymbols(Collections.singletonList(new DocumentBlock.WordSymbol()))),
            item("warning metadata",r->r.setWordWarnings(Collections.singletonList("unresolved row"))),
            item("row text not exact cell join",r->r.setText("| |")),
            item("word-cell effective channel",r->r.getWordCells().get(0).setText("cell obligation")),
            item("word-cell original channel",r->r.getWordCells().get(0).setOriginalText("cell original")),
            item("word-cell deleted channel",r->r.getWordCells().get(0).setDeletedText("cell deleted")),
            item("word-cell strike channel",r->r.getWordCells().get(0).setStrikeText("cell struck")),
            item("null word-cell metadata",r->r.setWordCells(Collections.singletonList(null)))
        );
    }
    private static Arguments item(String name,Consumer<DocumentBlock> change) {return Arguments.of(name,change);}
    @ParameterizedTest(name="{0}") @MethodSource("retainedRowCases")
    void nonblankOrUnprovenRowsCannotBeOmitted(String name,Consumer<DocumentBlock> change) {
        DocumentBlock row=blankRow();change.accept(row);
        assertFalse(DraftTemplateSourceText.matches("Clause.",withRow(row)),name);
    }
    static Stream<String> changedSavedCases() {return Stream.of(" Clause.","Clause. ","Clause.\n","Clause.\t","Clause.\u00a0","Clause.\r\n","Edited clause.");}
    @ParameterizedTest @MethodSource("changedSavedCases")
    void savedTextIsNeverNormalizedToMakeItMatch(String saved) {
        assertFalse(DraftTemplateSourceText.matches(saved,withRow(blankRow())));
    }
    @Test void unavailableOrMalformedFreshRepresentationsDoNotGrantCompatibility() {
        assertFalse(DraftTemplateSourceText.matches(null,withRow(blankRow())));
        assertFalse(DraftTemplateSourceText.matches("Clause.",null));
        assertFalse(DraftTemplateSourceText.matches("Clause.",parsed("different",null)));
        assertFalse(DraftTemplateSourceText.matches("Clause.",parsed("different",Arrays.asList(paragraph("Clause."),null))));
    }
}
