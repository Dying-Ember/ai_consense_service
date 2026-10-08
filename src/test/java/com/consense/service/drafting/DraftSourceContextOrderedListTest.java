package com.consense.service.drafting;

import com.consense.web.dto.DraftingDtos.ExtractionContextVO;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Original captured source only: no reference answer or live model/DB calls. */
class DraftSourceContextOrderedListTest {
    private static String recordedSource() throws Exception {
        try(InputStream input=DraftSourceContextOrderedListTest.class.getResourceAsStream("/drafting/harness/ordered-list/SIM11-source.txt")) {
            assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    @Test void everyIntersectingRecordedCoreSeesTheWholeListAndItsSourceQualification() throws Exception {
        String source=recordedSource();
        String title="Other waterproofing areas specified in Specification";
        String last="18. All areas with waterproofing membranes, waterproofing layers or waterproofing systems.";
        String qualification="Full 18 area list copied from PRE.B6.590; retain actual numbering, wording and catch-all item 18.";
        for(int[] core:new int[][]{{0,2933},{2933,source.length()}}) {
            ExtractionContextVO context=DraftSourceContext.primary(source,core[0],core[1]);
            assertTrue(context.getSourceText().contains(title),"A source title must travel with every intersecting core.");
            assertTrue(context.getSourceText().contains("1.Kitchens;"),"The later core needs the list's beginning.");
            assertTrue(context.getSourceText().contains(last),"The earlier core must receive the final item.");
            assertTrue(context.getSourceText().contains(qualification),"Retain the adjacent source statement rather than inventing a summary.");
            assertNull(context.getStopReason());
            assertTrue(context.getSourceText().length()<=DraftSourceContext.WINDOW_BUDGET);
            assertTrue(context.getSourceStart()<=core[0]&&context.getSourceEnd()>=core[1]);
            assertEquals(source.substring(context.getSourceStart(),context.getSourceEnd()),context.getSourceText());
        }
    }

    @Test void recallFromALateItemRetainsTheBeginningInsteadOfCreatingAPartialList() throws Exception {
        String source=recordedSource();int cue=source.indexOf("12.Suspended manholes");
        ExtractionContextVO context=DraftSourceContext.recall(source,cue,Collections.singletonList("aTextField"),"missing_output");
        assertTrue(context.getSourceText().contains("1.Kitchens;"));
        assertTrue(context.getSourceText().contains("18. All areas"));
        assertTrue(context.getSourceText().contains("Full 18 area list copied"));
        assertNull(context.getStopReason());
        assertEquals(source.substring(context.getSourceStart(),context.getSourceEnd()),context.getSourceText());
    }

    @Test void aQuotedFragmentInsideACutNumberedUnitIsNotCompleteSourceContext() throws Exception {
        String source=recordedSource();int start=source.indexOf("12.Suspended"),end=source.indexOf("17.Potable");
        ExtractionContextVO cut=new ExtractionContextVO("original_neighbors",Collections.emptyList(),start,end,source.substring(start,end),null);
        assertFalse(DraftSourceContext.completeQuote(source,cut,"13.G/F level slabs;","text"));
        ExtractionContextVO whole=DraftSourceContext.primary(source,0,2933);
        assertTrue(DraftSourceContext.completeQuote(source,whole,"13.G/F level slabs;","text"),"Quote/value completeness is checked separately from source-window completeness.");
    }

    @Test void recordedUnitMetadataUsesOriginalOffsetsAndCannotBeMutated() throws Exception {
        String source=recordedSource();List<DraftOrderedSourceLists.Unit> units=DraftOrderedSourceLists.units(source);
        assertEquals(1,units.size());DraftOrderedSourceLists.Unit unit=units.get(0);
        assertTrue(unit.validSequence);
        assertEquals(18,unit.entries.size());
        assertEquals(source.indexOf("1.Kitchens;"),unit.listStart);
        assertTrue(source.substring(unit.start,unit.end).contains("Full 18 area list copied"));
        for(int i=0;i<unit.entries.size();i++) {
            DraftOrderedSourceLists.Entry entry=unit.entries.get(i);
            assertEquals(i+1,entry.number);
            assertEquals(source.substring(entry.markerEnd,entry.end).trim(),entry.text);
            assertTrue(entry.start<entry.markerEnd&&entry.markerEnd<entry.end);
        }
        assertThrows(UnsupportedOperationException.class,()->units.clear());
        assertThrows(UnsupportedOperationException.class,()->unit.entries.clear());
    }

    @Test void semicolonAndMultilineNumberedListsKeepTheirEntireLogicalUnit() {
        for(String source:new String[]{
                "Selected work areas\nAreas: 1.Kitchen; 2) Bathroom; 3. Pump room.\nThe list is the specified scope.\nNext unrelated topic\nAnother answer: Yes",
                "Selected work areas\n1. The first item continues\non a second line;\n\n2. The second item;\n  its indented detail also applies.\n\n3. The final item.\nNext unrelated topic\nAnother answer: Yes"}) {
            List<DraftOrderedSourceLists.Unit> units=DraftOrderedSourceLists.units(source);assertEquals(1,units.size());
            DraftOrderedSourceLists.Unit unit=units.get(0);assertEquals(3,unit.entries.size());
            int cue=unit.entries.get(1).start;
            ExtractionContextVO window=DraftSourceContext.primary(source,cue,unit.entries.get(1).end);
            assertTrue(window.getSourceStart()<=unit.start&&window.getSourceEnd()>=unit.end);
            assertTrue(window.getSourceText().contains("Selected work areas"));assertNull(window.getStopReason());
            assertEquals(source.substring(window.getSourceStart(),window.getSourceEnd()),window.getSourceText());
        }
    }

    @Test void separateTopicsAndInterruptedSequencesAreNeverCombinedAsOneList() {
        String source="First scope\n1. Alpha;\n2. Bravo.\n\nSeparate scope heading\n\n3. Charlie;\n4. Delta.\n\nRestarted list\n1. Echo;\n2. Foxtrot.";
        List<DraftOrderedSourceLists.Unit> units=DraftOrderedSourceLists.units(source);assertEquals(3,units.size());
        assertEquals("Alpha;",units.get(0).entries.get(0).text);
        assertEquals("Bravo.",units.get(0).entries.get(1).text);
        assertEquals(3,units.get(1).entries.get(0).number);
        assertFalse(source.substring(units.get(0).listStart,units.get(0).listEnd).contains("Charlie"));
        assertEquals(1,units.get(2).entries.get(0).number);
        assertTrue(DraftOrderedSourceLists.units("Version 1.2 is in use.\n2024.01.01\nBill number | Description\n1 | Alpha\n2 | Bravo").isEmpty());
        assertTrue(DraftOrderedSourceLists.units("First area: 1. Alpha; Unrelated area: 2. Bravo.").isEmpty(),"A new inline label is a new source topic even when the numbers happen to follow.");
    }

    @Test void oversizedListsRemainBoundedAndReportInsufficientContextInsteadOfSilentlyTruncating() {
        StringBuilder builder=new StringBuilder("Specified areas for this scope\n");
        for(int i=1;i<=40;i++)builder.append(i).append(". ").append("Exact original detail ".repeat(14)).append(";\n\n");
        String source=builder.toString();assertEquals(1,DraftOrderedSourceLists.units(source).size());
        ExtractionContextVO primary=DraftSourceContext.primary(source,0,2000);
        ExtractionContextVO recall=DraftSourceContext.recall(source,source.indexOf("20. "),Collections.singletonList("aTextField"),"missing_output");
        for(ExtractionContextVO context:new ExtractionContextVO[]{primary,recall}) {
            assertEquals("context_insufficient",context.getStopReason());assertTrue(context.getSourceText().length()<=6000);
            assertEquals(source.substring(context.getSourceStart(),context.getSourceEnd()),context.getSourceText());
        }
        String quote=source.substring(source.indexOf("1. "),source.indexOf("2. ")).trim();
        assertFalse(DraftSourceContext.completeQuote(source,primary,quote,"text"));
        // The same finite budget also applies when the entire oversized list is on a single line.
        String inline=source.replace("\n", " ");
        ExtractionContextVO inlineRecall=DraftSourceContext.recall(inline,inline.indexOf("20. "),Collections.singletonList("aTextField"),"missing_output");
        assertEquals("context_insufficient",inlineRecall.getStopReason());assertTrue(inlineRecall.getSourceText().length()<=6000);
    }

    @Test void ordinaryUnnumberedSourceAndCoreTextArePreserved() {
        String source="Adopted wording for this contract\nUse the following wording.\nAll selected specialists shall execute the Works.\n";
        assertTrue(DraftOrderedSourceLists.units(source).isEmpty());
        ExtractionContextVO context=DraftSourceContext.primary(source,0,source.length());
        assertEquals(0,context.getSourceStart());assertEquals(source.length(),context.getSourceEnd());assertEquals(source,context.getSourceText());
        assertNull(context.getStopReason());
        assertTrue(DraftSourceContext.completeQuote(source,context,"All selected specialists shall execute the Works.","text"));
    }

    @Test void gapsAndDuplicateNumbersStayInOneSourceBlockRatherThanBecomingAcceptableSubLists() {
        for(String source:new String[]{"Specified scope\n1. Alpha;\n3. Charlie;\n4. Delta;\n5. Echo.",
                "Specified scope\n1. Alpha;\n2. Bravo;\n2. Duplicate;\n3. Charlie."}) {
            List<DraftOrderedSourceLists.Unit> units=DraftOrderedSourceLists.units(source);
            assertEquals(1,units.size(),"A malformed contiguous list is one source block, not several valid-looking alternatives.");
            assertEquals(4,units.get(0).entries.size(),"Every malformed original marker must remain visible to validation.");
            assertEquals(source.indexOf("1. Alpha"),units.get(0).listStart);
            assertFalse(units.get(0).validSequence);
        }
        List<DraftOrderedSourceLists.Unit> minimal=DraftOrderedSourceLists.units("Scope\n1. Alpha;\n3. Charlie.");
        assertEquals(1,minimal.size());assertEquals(2,minimal.get(0).entries.size());assertFalse(minimal.get(0).validSequence);
        List<DraftOrderedSourceLists.Unit> empty=DraftOrderedSourceLists.units("Scope\n1. Alpha;\n2.\n3. Charlie.");
        assertEquals(1,empty.size());assertEquals(3,empty.get(0).entries.size());assertFalse(empty.get(0).validSequence);
        List<DraftOrderedSourceLists.Unit> numeric=DraftOrderedSourceLists.units("Scope\n1. 2024 procurement;\n2. 2025 construction.");
        assertEquals(1,numeric.size());assertEquals(2,numeric.get(0).entries.size());assertTrue(numeric.get(0).validSequence);
    }

    @Test void anOuterControlHeadingTravelsWithTheImmediateFieldHeading() {
        String source="Pending alternatives\nOther waterproofing areas specified in Specification\n1. Kitchen;\n2. Bathroom;\n3. Plant room.";
        DraftOrderedSourceLists.Unit unit=DraftOrderedSourceLists.units(source).get(0);
        assertTrue(source.substring(unit.start,unit.end).contains("Pending alternatives"),"The enclosing source status must not disappear behind the field title.");
        assertTrue(source.substring(unit.start,unit.end).contains("Other waterproofing areas specified in Specification"));
    }

    public static void main(String[] args) throws Exception {
        DraftSourceContextOrderedListTest test=new DraftSourceContextOrderedListTest();
        test.everyIntersectingRecordedCoreSeesTheWholeListAndItsSourceQualification();
        test.recallFromALateItemRetainsTheBeginningInsteadOfCreatingAPartialList();
        test.aQuotedFragmentInsideACutNumberedUnitIsNotCompleteSourceContext();
        test.recordedUnitMetadataUsesOriginalOffsetsAndCannotBeMutated();
        test.semicolonAndMultilineNumberedListsKeepTheirEntireLogicalUnit();
        test.separateTopicsAndInterruptedSequencesAreNeverCombinedAsOneList();
        test.oversizedListsRemainBoundedAndReportInsufficientContextInsteadOfSilentlyTruncating();
        test.ordinaryUnnumberedSourceAndCoreTextArePreserved();
        test.gapsAndDuplicateNumbersStayInOneSourceBlockRatherThanBecomingAcceptableSubLists();
        test.anOuterControlHeadingTravelsWithTheImmediateFieldHeading();
        String source=recordedSource();
        for(int[] core:new int[][]{{0,2933},{2933,source.length()}}) {
            ExtractionContextVO context=DraftSourceContext.primary(source,core[0],core[1]);
            System.out.println("core="+core[0]+".."+core[1]+", original window="+context.getSourceStart()+".."+context.getSourceEnd()+", chars="+context.getSourceText().length());
        }
        System.out.println("DraftSourceContextOrderedListTest captured-source replay PASS");
    }
}
