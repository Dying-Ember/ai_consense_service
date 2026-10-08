package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingSourceInventoryTest {
    static Chunk text(String id,String content) {
        Chunk c=new Chunk();c.setId(id);c.setContent(content);c.setDocumentId("document-A");c.setSourceHash("revision-A");c.setRole("tender");
        c.setAnchor("body/1/paragraph");c.setClauseHeadingLocation("body/1/paragraph");
        Part p=new Part();p.setText(content);p.setAnchor(c.getAnchor());p.setBlockId(id+"-paragraph");p.setEndOffset(content.length());
        c.setParts(new ArrayList<>(Collections.singletonList(p)));return c;
    }
    static Chunk table(String id,int... rowIndices) {
        Chunk c=text(id,"Observed table title");List<Part> parts=new ArrayList<>();StringBuilder body=new StringBuilder(c.getContent());
        for(int index:rowIndices) {
            TableRow row=new TableRow();row.setTableLocation("body/4");row.setRowIndex(index);row.setCells(Arrays.asList("row-"+index,"cell-value"));
            Part part=new Part();part.setTable(row);part.setText(String.join(" | ",row.getCells()));part.setAnchor("XYZ1 · body/4/table-row/"+index);
            part.setBlockId(id+"-row-"+index);part.setEndOffset(part.getText().length());parts.add(part);body.append('\n').append(part.getText());
        }
        c.setContent(body.toString());c.setParts(parts);return c;
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> records(List<Chunk> chunks) {
        return (List<Map<String,Object>>)VettingSourceInventory.describe(chunks).get("records");
    }
    static Map<String,Object> record(Chunk c){return records(Collections.singletonList(c)).get(0);}
    static void unknown(Chunk c){assertEquals("unknown",record(c).get("observedNativeTableRowPartsCount"));}

    @Test void describesOnlySelectedRecordsInOrderAndDoesNotMutateInputs() {
        Chunk selected=text("selected","Source first line\nSource continuation");Chunk notSelected=table("not-selected",0,1);
        String before=JsonUtils.write(selected);Map<String,Object> inventory=VettingSourceInventory.describe(Collections.singletonList(selected));
        assertEquals(Collections.singletonList("selected"),inventory.get("presentChunkIds"));
        assertEquals("Source first line",record(selected).get("firstLine"));assertEquals("source_text_only",record(selected).get("firstLineMeaning"));
        assertFalse(JsonUtils.write(inventory).contains(notSelected.getId()));assertEquals(before,JsonUtils.write(selected));
        assertEquals(true,inventory.get("selectedOnly"));assertEquals("unknown",inventory.get("fullSourceCoverage"));assertEquals("unknown",inventory.get("contractApplicability"));
    }
    @Test void nativeRowPartCountIsAnObservationAndNotACompleteTableOrLegalConclusion() {
        Map<String,Object> row=record(table("rows",0,1,2));assertEquals(3,row.get("observedNativeTableRowPartsCount"));
        assertEquals(3,((List<?>)row.get("observedNativeRowPartAnchors")).size());assertEquals(3,((List<?>)row.get("observedNativeRowPartBlockIds")).size());
        assertEquals("unknown",row.get("fullTableCompleteness"));assertTrue(row.get("tableCountScope").toString().contains("never full-table"));
    }
    @Test void standaloneLabelsAreGenericLiteralTextAndTitleOnlyDoesNotEstablishMissingTables() {
        for(String label:Arrays.asList("APPENDIX X.9/V","ANNEX Z-1","SCHEDULE Q/\u2163","appendix A TO ANNEX Z.2")) {
            Chunk title=text("title",label);Map<String,Object> row=record(title);
            assertEquals(label,row.get("standaloneLabel"));assertEquals("unknown",row.get("observedNativeTableRowPartsCount"));
            assertTrue(row.get("standaloneLabelMeaning").toString().contains("unknown"));
        }
        assertNull(record(text("inline","See APPENDIX X.9/V for the requirements.")).get("standaloneLabel"));
        assertNull(record(text("multiple-lines","APPENDIX X.9/V\nA following source provision")).get("standaloneLabel"));
    }
    @Test void scannerOrPipeTextCannotBePromotedIntoNativeTableMetadata() {
        Chunk scan=text("scan","Column A | Column B\nReply A | Reply B");scan.getParts().get(0).setAnchor("page/1/ocr-line/2");unknown(scan);
        assertTrue(((List<?>)record(scan).get("observedNativeRowPartAnchors")).isEmpty());
    }
    @Test void unavailablePartsNeverBecomeZeroRowsOrAnAbsenceClaim() {
        Chunk c=table("unavailable",0);c.setParts(null);unknown(c);c.setParts(Collections.emptyList());unknown(c);
        assertEquals("source_parts_unavailable",record(c).get("tableCountUnknownReason"));
    }
    @Test void multiChunkTableOrHeadingContinuationsKeepCountUnknown() {
        Chunk first=table("first",0),second=table("second",1);
        for(Map<String,Object> row:records(Arrays.asList(first,second)))assertEquals("unknown",row.get("observedNativeTableRowPartsCount"));
        second.getParts().get(0).getTable().setTableLocation("body/8");second.getParts().get(0).setAnchor("body/8/table-row/1");
        for(Map<String,Object> row:records(Arrays.asList(first,second)))assertEquals("source_heading_spans_multiple_selected_chunks",row.get("tableCountUnknownReason"));
    }
    @Test void identicalNativeLocationsRemainIsolatedByDocumentRevisionAndRole() {
        for(String difference:Arrays.asList("document","revision","role")) {
            Chunk a=table("a",0),b=table("b",1);
            if("document".equals(difference))b.setDocumentId("document-B");
            if("revision".equals(difference))b.setSourceHash("revision-B");
            if("role".equals(difference))b.setRole("standard");
            for(Map<String,Object> row:records(Arrays.asList(a,b)))assertEquals(1,row.get("observedNativeTableRowPartsCount"),difference);
        }
    }
    @Test void duplicateRowFragmentsAndPartialSlicesAreUnknown() {
        Chunk duplicate=table("duplicate",0,0);unknown(duplicate);
        Chunk partial=table("partial",0);partial.getParts().get(0).setStartOffset(1);unknown(partial);
        Chunk unavailable=table("unavailable",0);unavailable.getParts().get(0).setTable(null);unknown(unavailable);
    }
    @Test void nativeCountRequiresMatchingAnchorBlockCellsTextAndSelectedContent() {
        for(String missing:Arrays.asList("anchor","block","cells","content","endOffset","indexBoundary")) {
            Chunk c=table("candidate",1);Part p=c.getParts().get(0);
            if("anchor".equals(missing))p.setAnchor(null);if("block".equals(missing))p.setBlockId(null);
            if("cells".equals(missing))p.getTable().setCells(Arrays.asList("different","cells"));
            if("content".equals(missing))c.setContent("A different selected text");if("endOffset".equals(missing))p.setEndOffset(0);
            if("indexBoundary".equals(missing))p.setAnchor("body/4/table-row/10");unknown(c);
        }
    }
    @Test void missingSourceIdentityCannotJoinOrCertifyNativeTableCounts() {
        for(String missing:Arrays.asList("document","hash","role","anchor")) {
            Chunk c=table("candidate",0);if("document".equals(missing))c.setDocumentId(null);if("hash".equals(missing))c.setSourceHash(null);
            if("role".equals(missing))c.setRole(null);if("anchor".equals(missing))c.setAnchor(null);unknown(c);
        }
    }
    @Test void emptySelectionAndMissingTextPreserveUnknownCoverage() {
        Map<String,Object> empty=VettingSourceInventory.describe(Collections.emptyList());assertEquals(Collections.emptyList(),empty.get("presentChunkIds"));
        assertEquals("unknown",empty.get("fullSourceCoverage"));Chunk c=text("missing-text","text");c.setContent(null);c.setParts(null);
        assertNull(record(c).get("firstLine"));assertNull(record(c).get("standaloneLabel"));unknown(c);
    }
    @Test void malformedSelectedIdentitiesFailWithoutFabricatingOrDroppingRecords() {
        assertThrows(NullPointerException.class,()->VettingSourceInventory.describe(null));
        assertThrows(IllegalArgumentException.class,()->VettingSourceInventory.describe(Collections.singletonList(null)));
        Chunk a=text("same","a"),b=text("same","b");assertThrows(IllegalArgumentException.class,()->VettingSourceInventory.describe(Arrays.asList(a,b)));
        a.setId(null);assertThrows(IllegalArgumentException.class,()->VettingSourceInventory.describe(Collections.singletonList(a)));
    }
    @Test void positionedTableTextPartsRemainObservableWithoutCellMetadataAcrossSelectedFragmentsAndSources() {
        Chunk a=table("a",0),b=table("b",1);
        for(Chunk c:Arrays.asList(a,b))for(Part p:c.getParts()) {
            p.setTable(null);p.setBlockId("body:4:table-row:"+(c==a?0:1));
        }
        for(Map<String,Object> row:records(Arrays.asList(a,b))) {
            assertEquals(1,row.get("observedTableTextPartsCount"));assertEquals("unknown",row.get("observedNativeTableRowPartsCount"));
            assertEquals(1,((List<?>)row.get("rowTextPositions")).size());assertEquals("unknown",row.get("fullTableCompleteness"));
        }
        b.setDocumentId("other-document");b.setRole("standard");b.setSourceHash("other-revision");
        for(Map<String,Object> row:records(Arrays.asList(a,b)))assertEquals(1,row.get("observedTableTextPartsCount"));
    }
    @Test void tableTextPositionRequiresExactAnchorBlockAndUtf16SliceWithoutGuessingAbsence() {
        Chunk c=text("slice","\uD83D\uDE00");Part p=c.getParts().get(0);p.setAnchor("OWNER · body/4/table-row/1 @3");
        p.setBlockId("body:4:table-row:1");p.setStartOffset(3);p.setEndOffset(5);
        assertEquals(1,record(c).get("observedTableTextPartsCount"));assertEquals("unknown",record(c).get("observedNativeTableRowPartsCount"));
        for(String bad:Arrays.asList("block","padded-anchor","offset","utf16-length","unknown-source")) {
            Chunk changed=JsonUtils.read(JsonUtils.write(c),Chunk.class);Part row=changed.getParts().get(0);
            if("block".equals(bad))row.setBlockId("body:4:table-row:10");
            if("padded-anchor".equals(bad))row.setAnchor("OWNER · body/04/table-row/1 @3");
            if("offset".equals(bad))row.setAnchor("body/4/table-row/1 @2");
            if("utf16-length".equals(bad))row.setEndOffset(4);if("unknown-source".equals(bad))changed.setSourceHash(null);
            assertEquals("unknown",record(changed).get("observedTableTextPartsCount"),bad);
        }
        c.setParts(null);assertEquals("unknown",record(c).get("observedTableTextPartsCount"));
        assertEquals("unknown",record(text("title","APPENDIX X.9")).get("observedTableTextPartsCount"));
    }
}
