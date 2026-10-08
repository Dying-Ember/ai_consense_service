package com.consense.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.NumberingDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class DocxNumberingResolverTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test
    void sourceStartFiveRestoresEAndContinuesThroughFWithDefinitionProvenance() throws Exception {
        try (XWPFDocument document = document(level(0, 5, "lowerLetter", "(%1)", ""), "")) {
            XWPFParagraph first = paragraph(document, 14, 0), second = paragraph(document, 14, 0);
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            DocxNumberingResolver.NumberingInfo e = resolver.resolve(first.getCTP().getDomNode());
            assertTrue(e.isResolved()); assertTrue(e.isNumbered()); assertEquals("(e)", e.getLabel());
            assertEquals("14", e.getNumId()); assertEquals("10", e.getAbstractNumId());
            assertEquals(0, e.getLevel()); assertEquals(5L, e.getValue());
            assertEquals("lowerLetter", e.getFormat()); assertEquals("(%1)", e.getLevelText());
            assertEquals("paragraph", e.getSource()); assertEquals("resolved", e.getResolutionStatus());
            assertEquals("(f)", resolver.resolve(second.getCTP().getDomNode()).getLabel());
        }
    }

    @Test
    void startOverrideAndSeparateNumInstancesDoNotShareCounters() throws Exception {
        String override = "<w:lvlOverride w:ilvl='0'><w:startOverride w:val='7'/></w:lvlOverride>";
        try (XWPFDocument document = document(level(0, 5, "decimal", "%1.", ""), override)) {
            document.getNumbering().addNum(BigInteger.TEN, BigInteger.valueOf(15));
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            assertEquals("7.", resolver.resolve(paragraph(document, 14, 0).getCTP().getDomNode()).getLabel());
            assertEquals("5.", resolver.resolve(paragraph(document, 15, 0).getCTP().getDomNode()).getLabel());
            assertEquals("8.", resolver.resolve(paragraph(document, 14, 0).getCTP().getDomNode()).getLabel());
        }
    }

    @Test
    void fullLevelOverrideReplacesTheAbstractFormatAndText() throws Exception {
        String override = "<w:lvlOverride w:ilvl='0'>" + level(0, 2, "upperRoman", "[%1]", "") + "</w:lvlOverride>";
        try (XWPFDocument document = document(level(0, 5, "lowerLetter", "(%1)", ""), override)) {
            assertEquals("[II]", new DocxNumberingResolver(document).resolve(paragraph(document, 14, 0).getCTP().getDomNode()).getLabel());
        }
    }

    @Test
    void hierarchicalPlaceholdersUseEachReferencedLevelsFormatAndDefaultRestart() throws Exception {
        String levels = level(0, 1, "decimal", "%1.", "") + level(1, 1, "lowerLetter", "%1(%2)", "");
        try (XWPFDocument document = document(levels, "")) {
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            assertEquals("1.", label(resolver, document, 0));
            assertEquals("1(a)", label(resolver, document, 1));
            assertEquals("1(b)", label(resolver, document, 1));
            assertEquals("2.", label(resolver, document, 0));
            assertEquals("2(a)", label(resolver, document, 1));
        }
    }

    @Test
    void explicitRestartZeroPreservesChildCounterAcrossParentChanges() throws Exception {
        String levels = level(0, 1, "decimal", "%1.", "")
                + level(1, 1, "lowerRoman", "%2)", "<w:lvlRestart w:val='0'/>");
        try (XWPFDocument document = document(levels, "")) {
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            assertEquals("1.", label(resolver, document, 0)); assertEquals("i)", label(resolver, document, 1));
            assertEquals("2.", label(resolver, document, 0)); assertEquals("ii)", label(resolver, document, 1));
        }
    }

    @Test
    void explicitRestartParentUsesOneBasedIndexRatherThanPreviousLevel() throws Exception {
        String levels = level(0, 1, "decimal", "%1.", "")
                + level(1, 1, "decimal", "%2.", "")
                + level(2, 1, "decimal", "%3)", "<w:lvlRestart w:val='1'/>");
        try (XWPFDocument document = document(levels, "")) {
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            label(resolver, document, 0); label(resolver, document, 1); assertEquals("1)", label(resolver, document, 2));
            label(resolver, document, 1); assertEquals("2)", label(resolver, document, 2));
            label(resolver, document, 0); assertEquals("1)", label(resolver, document, 2));
        }
    }

    @Test
    void linkedParagraphStylesInheritNumIdButIgnoreStyleIlvl() throws Exception {
        String levels = level(0, 1, "decimal", "%1.", "")
                + level(1, 3, "lowerLetter", "(%2)", "<w:pStyle w:val='ListBase'/>");
        try (XWPFDocument document = document(levels, "")) {
            document.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='" + W + "'>"
                    + "<w:style w:type='paragraph' w:styleId='ListBase'><w:pPr><w:numPr><w:ilvl w:val='0'/><w:numId w:val='14'/></w:numPr></w:pPr></w:style>"
                    + "<w:style w:type='paragraph' w:styleId='Derived'><w:basedOn w:val='ListBase'/></w:style></w:styles>").getStyles());
            XWPFParagraph paragraph = document.createParagraph(); paragraph.setStyle("Derived");
            DocxNumberingResolver.NumberingInfo info = new DocxNumberingResolver(document).resolve(paragraph.getCTP().getDomNode());
            assertEquals("(c)", info.getLabel()); assertEquals(1, info.getLevel()); assertEquals("paragraph_style", info.getSource());
        }
    }

    @Test
    void explicitNumIdZeroCancelsInheritedNumberingWithoutAdvancingIt() throws Exception {
        try (XWPFDocument document = document(level(0, 1, "decimal", "%1.", ""), "")) {
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            DocxNumberingResolver.NumberingInfo cancelled = resolver.resolve(paragraph(document, 0, 0).getCTP().getDomNode());
            assertFalse(cancelled.isNumbered()); assertTrue(cancelled.isResolved());
            assertEquals("cancelled", cancelled.getResolutionStatus()); assertEquals("0", cancelled.getNumId());
            assertEquals("1.", label(resolver, document, 0));
        }
    }

    @Test
    void absentParagraphStyleUsesTheActualDefaultStyleAndItsLinkedLevel() throws Exception {
        try (XWPFDocument created = document(level(0, 4, "decimal", "%1.", "<w:pStyle w:val='DefaultList'/>") , "")) {
            created.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='" + W + "'>"
                    + "<w:style w:type='paragraph' w:styleId='Other'/><w:style w:type='paragraph' w:default='1' w:styleId='DefaultList'>"
                    + "<w:pPr><w:numPr><w:numId w:val='14'/></w:numPr></w:pPr></w:style></w:styles>").getStyles());
            created.createParagraph().createRun().setText("inherited default list");
            try (XWPFDocument loaded = roundTrip(created)) {
                DocxNumberingResolver.NumberingInfo info = new DocxNumberingResolver(loaded).resolve(loaded.getParagraphs().get(0).getCTP().getDomNode());
                assertEquals("4.", info.getLabel()); assertEquals("paragraph_style", info.getSource()); assertTrue(info.isResolved());
            }
        }
    }

    @Test
    void docDefaultsNumPrIsInheritedEvenWithoutAnExplicitOrDefaultStyle() throws Exception {
        try (XWPFDocument created = document(level(0, 2, "decimal", "%1.", ""), "")) {
            created.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='" + W + "'><w:docDefaults>"
                    + "<w:pPrDefault><w:pPr><w:numPr><w:numId w:val='14'/><w:ilvl w:val='0'/></w:numPr></w:pPr></w:pPrDefault>"
                    + "</w:docDefaults></w:styles>").getStyles());
            created.createParagraph().createRun().setText("document default list");
            try (XWPFDocument loaded = roundTrip(created)) {
                DocxNumberingResolver.NumberingInfo info = new DocxNumberingResolver(loaded).resolve(loaded.getParagraphs().get(0).getCTP().getDomNode());
                assertEquals("2.", info.getLabel()); assertEquals("doc_defaults", info.getSource()); assertTrue(info.isResolved());
            }
        }
    }

    @Test
    void fontSpecificLowByteLabelIsUnresolvedAndDoesNotPretendToBeAscii() throws Exception {
        try (XWPFDocument document = document(level(0, 1, "bullet", "v", "<w:rPr><w:rFonts w:ascii='Wingdings'/></w:rPr>"), "")) {
            DocxNumberingResolver.NumberingInfo info = new DocxNumberingResolver(document).resolve(paragraph(document, 14, 0).getCTP().getDomNode());
            assertFalse(info.isResolved()); assertEquals("font_specific_level_symbol", info.getReason()); assertEquals("", info.getLabel());
        }
    }

    @Test
    void malformedExplicitDocDefaultLevelIsUnresolvedInsteadOfAssumingZero() throws Exception {
        try (XWPFDocument created = document(level(0, 2, "decimal", "%1.", ""), "")) {
            created.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='" + W + "'><w:docDefaults>"
                    + "<w:pPrDefault><w:pPr><w:numPr><w:numId w:val='14'/><w:ilvl w:val='garbage'/></w:numPr></w:pPr></w:pPrDefault>"
                    + "</w:docDefaults></w:styles>").getStyles());
            created.createParagraph().createRun().setText("malformed default level");
            try (XWPFDocument loaded = roundTrip(created)) {
                try (java.io.InputStream stream = loaded.getStyles().getPackagePart().getInputStream()) {
                    assertTrue(StylesDocument.Factory.parse(stream).xmlText().contains("garbage"), "fixture must retain the malformed literal");
                }
                DocxNumberingResolver.NumberingInfo info = new DocxNumberingResolver(loaded).resolve(loaded.getParagraphs().get(0).getCTP().getDomNode());
                assertTrue(info.isNumbered()); assertFalse(info.isResolved(), info.getReason() + ":" + info.getLabel()); assertEquals("invalid_default_level", info.getReason());
                assertEquals("", info.getLabel());
            }
        }
    }

    @Test
    void repeatedXmlBeansDomAccessAndTableCellAccessDoNotAdvanceTwice() throws Exception {
        try (XWPFDocument created = document(level(0, 1, "decimal", "%1.", ""), "")) {
            XWPFTableCell cell = created.createTable(1, 1).getRow(0).getCell(0);
            XWPFParagraph paragraph = cell.getParagraphs().get(0); paragraph.setNumID(BigInteger.valueOf(14)); paragraph.setNumILvl(BigInteger.ZERO);
            paragraph(created, 14, 0);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); created.write(bytes);
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes.toByteArray()))) {
                DocxNumberingResolver resolver = new DocxNumberingResolver(document);
                XWPFParagraph tableParagraph = document.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0);
                DocxNumberingResolver.NumberingInfo first = resolver.resolve(tableParagraph.getCTP().getDomNode());
                assertSame(first, resolver.resolve(tableParagraph.getCTP().getDomNode()));
                org.w3c.dom.Node row = document.getTables().get(0).getRow(0).getCtRow().getDomNode();
                org.w3c.dom.Node sameParagraph = row.getFirstChild();
                while (sameParagraph != null && !"tc".equals(sameParagraph.getLocalName())) sameParagraph = sameParagraph.getNextSibling();
                sameParagraph = sameParagraph.getFirstChild();
                while (sameParagraph != null && !"p".equals(sameParagraph.getLocalName())) sameParagraph = sameParagraph.getNextSibling();
                assertSame(first, resolver.resolve(sameParagraph));
                assertEquals("2.", resolver.resolve(document.getParagraphs().get(0).getCTP().getDomNode()).getLabel());
            }
        }
    }

    @Test
    void newStoriesHaveIndependentCountersAndParagraphCaches() throws Exception {
        try (XWPFDocument document = document(level(0, 1, "decimal", "%1.", ""), "")) {
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            assertEquals("1.", label(resolver, document, 0));
            DocxNumberingResolver story = resolver.newStory(); assertEquals("1.", label(story, document, 0));
            assertEquals("2.", label(resolver, document, 0));
        }
    }

    @Test
    void unsupportedFormatPrivateUseGlyphAndMissingInstanceAreExplicit() throws Exception {
        try (XWPFDocument document = document(level(0, 1, "chineseCounting", "%1)", "")
                + level(1, 1, "bullet", "&#xF0B7;", ""), "")) {
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            DocxNumberingResolver.NumberingInfo unsupported = resolver.resolve(paragraph(document, 14, 0).getCTP().getDomNode());
            assertTrue(unsupported.isNumbered()); assertFalse(unsupported.isResolved()); assertEquals("", unsupported.getLabel());
            assertTrue(unsupported.getReason().contains("unsupported")); assertEquals("chineseCounting", unsupported.getFormat());
            assertEquals("font_specific_level_symbol", resolver.resolve(paragraph(document, 14, 1).getCTP().getDomNode()).getReason());
            assertEquals("missing_numbering_instance", resolver.resolve(paragraph(document, 999, 0).getCTP().getDomNode()).getReason());
        }
    }

    @Test
    void legalNumberingConvertsReferencedLetterLevelToDecimal() throws Exception {
        String levels = level(0, 2, "upperLetter", "%1.", "")
                + level(1, 3, "lowerRoman", "%1.%2", "<w:isLgl/>");
        try (XWPFDocument document = document(levels, "")) {
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            assertEquals("B.", label(resolver, document, 0)); assertEquals("2.3", label(resolver, document, 1));
        }
    }

    @Test
    void unresolvedLetterRangeDoesNotWrapToAFalseLabel() throws Exception {
        try (XWPFDocument document = document(level(0, 26, "lowerLetter", "(%1)", ""), "")) {
            DocxNumberingResolver resolver = new DocxNumberingResolver(document);
            assertEquals("(z)", label(resolver, document, 0));
            DocxNumberingResolver.NumberingInfo next = resolver.resolve(paragraph(document, 14, 0).getCTP().getDomNode());
            assertFalse(next.isResolved()); assertEquals("letter_counter_out_of_supported_range", next.getReason());
        }
    }

    private static String label(DocxNumberingResolver resolver, XWPFDocument document, int level) {
        return resolver.resolve(paragraph(document, 14, level).getCTP().getDomNode()).getLabel();
    }
    private static XWPFParagraph paragraph(XWPFDocument document, int id, int level) {
        XWPFParagraph paragraph = document.createParagraph(); paragraph.setNumID(BigInteger.valueOf(id));
        paragraph.setNumILvl(BigInteger.valueOf(level)); paragraph.createRun().setText("source clause"); return paragraph;
    }
    private static XWPFDocument document(String levels, String overrides) throws Exception {
        XWPFDocument document = new XWPFDocument();
        document.createNumbering().setNumbering(NumberingDocument.Factory.parse("<w:numbering xmlns:w='" + W + "'>"
                + "<w:abstractNum w:abstractNumId='10'>" + levels + "</w:abstractNum>"
                + "<w:num w:numId='14'><w:abstractNumId w:val='10'/>" + overrides + "</w:num></w:numbering>").getNumbering());
        // POI setNumbering sets CTNumbering but does not populate its public
        // getNums/getAbstractNums lists until the package is read. Resolver
        // fixtures therefore cross the same load boundary as the real parser.
        XWPFDocument loaded = roundTrip(document); document.close(); return loaded;
    }
    private static XWPFDocument roundTrip(XWPFDocument document) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); document.write(bytes);
        return new XWPFDocument(new ByteArrayInputStream(bytes.toByteArray()));
    }
    private static String level(int index, int start, String format, String text, String extra) {
        return "<w:lvl w:ilvl='" + index + "'><w:start w:val='" + start + "'/><w:numFmt w:val='" + format
                + "'/><w:lvlText w:val='" + text + "'/>" + extra + "</w:lvl>";
    }
}
