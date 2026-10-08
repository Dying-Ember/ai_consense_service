package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingNativeCellSlicesTest {
    @Test void longSelectedRowReconstructsNativeCellsWithoutGivingAnyFragmentTheWholeRow() {
        String large=String.join("",Collections.nCopies(1580,"A😀"))+"Z";
        List<String> cells=Arrays.asList("First | value","",large);List<Chunk> chunks=chunks(91,"tender",cells);
        assertEquals(4,chunks.size());String whole=String.join(" | ",cells);
        for(Chunk c:chunks)for(Part p:c.getParts()) {
            assertNull(p.getTable());assertNotNull(p.getTableSlice());
            assertEquals(whole.substring(p.getStartOffset(),p.getEndOffset()),p.getText());
            for(NativeCellSlice s:p.getTableSlice().getCellSlices())assertEquals(whole.substring(s.getRowStartOffset(),s.getRowEndOffset()),s.getText());
        }
        List<Map<String,Object>> rows=rows(chunks);assertEquals(1,rows.size());Map<String,Object> row=rows.get(0);
        assertEquals("complete_selected_row",row.get("status"));assertEquals(whole,row.get("text"));
        List<?> projected=(List<?>)row.get("cells");assertEquals(3,projected.size());
        for(int i=0;i<3;i++)assertEquals(cells.get(i),((Map<?,?>)projected.get(i)).get("text"));
        assertEquals(4,((List<?>)row.get("contributorChunkIds")).size());
        assertTrue(VettingSourceMaterial.project(chunks).stream().allMatch(e->((List<?>)e.get("nativeRows")).isEmpty()));
    }

    @Test void unselectedMiddleCannotLeakWholeCellOrRowAndEmptyCellNeedsSelectedBoundaries() {
        List<Chunk> all=chunks(92,"standard",Arrays.asList("First | value","",repeat("X",4741)));
        List<Chunk> partial=Arrays.asList(all.get(0),all.get(3));Map<String,Object> row=rows(partial).get(0);
        assertEquals("partial_selected_row",row.get("status"));assertFalse(row.containsKey("text"));
        List<?> cells=(List<?>)row.get("cells");assertEquals("",((Map<?,?>)cells.get(1)).get("text"));
        assertFalse(((Map<?,?>)cells.get(2)).containsKey("text"));assertFalse(((List<?>)row.get("missingUtf16Ranges")).isEmpty());
        Map<String,Object> tail=rows(Collections.singletonList(all.get(3))).get(0);
        assertFalse(((Map<?,?>)((List<?>)tail.get("cells")).get(1)).containsKey("text"),"knowing a zero-length column is not observing its source boundary");
        assertTrue(all.get(3).getParts().get(0).getTableSlice().getCellSlices().stream().noneMatch(s->s.getColumnIndex()==1));
    }

    @Test void sourceRolesRevisionsAndUnknownNewFilenamesStayIsolated() {
        for(String role:Arrays.asList("tender","standard","project_fact","package_manifest")) {
            List<Chunk> a=chunks(93,role,Arrays.asList("field",repeat("A",3000)));
            List<Chunk> b=chunks(93,role,Arrays.asList("field",repeat("B",3000)));
            assertNotEquals(a.get(0).getSourceHash(),b.get(0).getSourceHash());
            Map<String,Object> full=rows(a).get(0);assertEquals(role,full.get("role"));assertEquals("complete_selected_row",full.get("status"));
            List<Map<String,Object>> mixed=rows(Arrays.asList(a.get(0),b.get(b.size()-1)));
            assertEquals(2,mixed.size());assertTrue(mixed.stream().allMatch(r->"partial_selected_row".equals(r.get("status"))));
        }
    }

    @Test void overlappingFragmentsMustAgreeAndDuplicateExactRangesAreDeduplicatedForCoverage() {
        List<Chunk> all=chunks(94,"tender",Arrays.asList("field",repeat("C",3000)));
        Chunk copy=clone(all.get(0));copy.setId("exact-repeated-range");List<Chunk> duplicates=new ArrayList<>(all);duplicates.add(copy);
        assertEquals("complete_selected_row",rows(duplicates).get(0).get("status"));
        Chunk changed=clone(all.get(1));Part p=changed.getParts().get(0);
        p.setText("Z"+p.getText().substring(1));changed.setContent(p.getText());
        NativeCellSlice s=p.getTableSlice().getCellSlices().get(0);s.setText("Z"+s.getText().substring(1));s.setTextSha256(VettingCorpus.hash(s.getText()));
        List<Chunk> conflict=new ArrayList<>(all);conflict.set(1,changed);
        assertTrue(rows(conflict).isEmpty());assertTrue(unknown(conflict).contains("conflicting_selected_utf16_overlap"));
    }

    @Test void missingLayoutOrUnsupportedVersionDoesNotBecomeACompleteRow() {
        for(String variant:Arrays.asList("missing","version","identity","layout","slice","range","body","quality")) {
            List<Chunk> all=chunks(95,"tender",Arrays.asList("field",repeat("D",3000)));Part p=all.get(1).getParts().get(0);
            if("missing".equals(variant))p.setTableSlice(null);
            if("version".equals(variant))p.getTableSlice().setVersion("unknown-future-version");
            if("identity".equals(variant))all.get(1).setSourceHash(repeat("0",64));
            if("layout".equals(variant))p.getTableSlice().getCellLayout().get(1).setRowStartOffset(1);
            if("slice".equals(variant))p.getTableSlice().getCellSlices().get(0).setText("Unselected replacement text");
            if("range".equals(variant))p.setEndOffset(p.getEndOffset()+1);
            if("body".equals(variant))all.get(1).setContent("Unrelated submitted body");
            if("quality".equals(variant))all.get(1).setSourceQualityHash(repeat("0",64));
            assertTrue(unknown(all).length()>0,variant);
            assertTrue(rows(all).stream().noneMatch(r->"complete_selected_row".equals(r.get("status"))),variant);
        }
    }

    @Test void longOcrLegacyAndInconsistentNativeCellsNeverAcquireInferredColumns() {
        for(String variant:Arrays.asList("ocr","legacy","join")) {
            DocumentBlock b=row(Arrays.asList("source",repeat("E",3000)));
            if("ocr".equals(variant))b.setSource("ocr");if("legacy".equals(variant))b.setKind("legacy");
            if("join".equals(variant))b.setText(b.getText()+"Changed raw body");
            List<Chunk> c=VettingCorpus.chunks(Collections.singletonList(source(96,"tender",b)));
            assertTrue(c.stream().flatMap(x->x.getParts().stream()).allMatch(p->p.getTableSlice()==null&&p.getTable()==null));assertTrue(rows(c).isEmpty());
        }
    }

    @Test void nativeLayoutIsForwardDerivedAndAstralUtf16OffsetsStaySourceExact() {
        List<String> cells=Arrays.asList("Inside | is text",repeat("😀|",1100),"Trailing");List<Chunk> all=chunks(97,"tender",cells);
        Map<String,Object> row=rows(all).get(0);List<?> result=(List<?>)row.get("cells");assertEquals(3,result.size());
        for(int i=0;i<cells.size();i++)assertEquals(cells.get(i),((Map<?,?>)result.get(i)).get("text"));
        for(Chunk c:all)for(Part p:c.getParts()) {
            assertFalse(Character.isLowSurrogate(p.getText().charAt(0)));assertFalse(Character.isHighSurrogate(p.getText().charAt(p.getText().length()-1)));
            assertEquals(p.getEndOffset()-p.getStartOffset(),p.getText().length());
        }
    }

    @Test void metadataSaltChangesIdsButNotSourceBodyOwnerQualityOrPhysicalOffsets() {
        List<Chunk> all=chunks(98,"tender",Arrays.asList("field",repeat("F",3000)));String hash=all.get(0).getSourceHash();
        for(Chunk c:all) {
            assertEquals("native-rows-and-cell-slices-v3",c.getNativeTableMetadataVersion());assertEquals(hash,c.getSourceHash());
            String blockId=c.getParts().get(0).getBlockId()+":"+c.getParts().get(0).getStartOffset();
            String oldSalt=String.join("|",c.getMetadataVersion(),c.getSegmentationVersion(),"native-rows-all-roles-v1",c.getSourceQualityMetadataVersion(),
                    c.getSourceQualityHash(),c.getRole(),c.getDocumentId(),c.getSourceHash(),blockId);
            assertNotEquals(UUID.nameUUIDFromBytes(oldSalt.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),c.getId());
            assertEquals("PENDING",c.getSourceQuality().getParseStatus());assertNull(c.getClauseId());
        }
    }

    @Test void portableDocumentBindingRetainsCellContentIdentityAndWholeSelectedNativeRow() {
        List<Chunk> original=chunks(101,"tender",Arrays.asList("field | source","",repeat("G",3000)));
        List<Chunk> mapped=original.stream().map(VettingNativeCellSlicesTest::clone).collect(Collectors.toList());
        for(Chunk c:mapped){c.setDocumentId("new-document-101");c.setId("new-parent-"+c.getId());}
        Map<String,Object> a=rows(original).get(0);List<Map<String,Object>> projected=rows(mapped);
        assertEquals(1,projected.size(),"database binding must not discard valid native cell relationships");
        Map<String,Object> b=projected.get(0);assertEquals("complete_selected_row",b.get("status"));
        assertEquals(a.get("sourceIdentityHash"),b.get("sourceIdentityHash"));assertEquals(a.get("cells"),stripContributors((List<?>)b.get("cells"),(List<?>)a.get("cells")));
        assertEquals("new-document-101",b.get("documentId"));assertEquals(a.get("text"),b.get("text"));
    }

    @Test void samePortableLayoutCannotCombinePartialRangesFromDifferentActualDocuments() {
        List<Chunk> original=chunks(102,"standard",Arrays.asList("field","",repeat("H",3000)));
        List<Chunk> mapped=original.stream().map(VettingNativeCellSlicesTest::clone).collect(Collectors.toList());
        for(Chunk c:mapped){c.setDocumentId("new-document-102");c.setId("new-parent-"+c.getId());}
        List<Chunk> mixed=new ArrayList<>();mixed.add(original.get(0));mixed.addAll(mapped.subList(1,mapped.size()));
        List<Map<String,Object>> observed=rows(mixed);assertEquals(2,observed.size());
        assertTrue(observed.stream().allMatch(r->"partial_selected_row".equals(r.get("status"))));
        assertTrue(observed.stream().noneMatch(r->r.containsKey("text")));assertTrue(unknown(mixed).replace("[]","").isEmpty());
    }

    @Test void roleAndSourceRevisionRemainInPortableCellIdentity() {
        List<Chunk> a=chunks(103,"tender",Arrays.asList("field",repeat("I",3000)));
        List<Chunk> otherRole=chunks(104,"standard",Arrays.asList("field",repeat("I",3000)));
        List<Chunk> otherRevision=chunks(105,"tender",Arrays.asList("field",repeat("J",3000)));
        String hash=a.get(0).getParts().get(0).getTableSlice().getSourceIdentityHash();
        assertNotEquals(hash,otherRole.get(0).getParts().get(0).getTableSlice().getSourceIdentityHash());
        assertNotEquals(hash,otherRevision.get(0).getParts().get(0).getTableSlice().getSourceIdentityHash());
        assertEquals(a.get(0).getSourceHash(),otherRole.get(0).getSourceHash());
    }

    private static Object stripContributors(List<?> newCells,List<?> oldCells) {
        for(int i=0;i<newCells.size();i++) {
            Map<String,Object> n=(Map<String,Object>)newCells.get(i),o=(Map<String,Object>)oldCells.get(i);
            n.put("selectedSlices",o.get("selectedSlices"));
        }
        return newCells;
    }

    private static String unknown(List<Chunk> c){return VettingSourceMaterial.project(c).stream().map(e->JsonUtils.write(e.get("unresolvedNativeCellSlices"))).collect(Collectors.joining());}
    private static List<Map<String,Object>> rows(List<Chunk> c){List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> e:VettingSourceMaterial.project(c))out.addAll((List<Map<String,Object>>)e.get("reconstructedNativeRows"));return out;}
    private static Chunk clone(Chunk c){return JsonUtils.read(JsonUtils.write(c),Chunk.class);}
    private static String repeat(String s,int n){return String.join("",Collections.nCopies(n,s));}
    private static List<Chunk> chunks(long id,String role,List<String> cells){return VettingCorpus.chunks(Collections.singletonList(source(id,role,row(cells))));}
    private static DocumentBlock row(List<String> cells){DocumentBlock b=new DocumentBlock();b.setId("body:7:table-row:3");b.setLocation("body/7/table-row/3");b.setKind("table_row");b.setSource("docx");b.setCells(cells);b.setText(String.join(" | ",cells));return b;}
    private static SourceDocument source(long id,String role,DocumentBlock b){SourceDocument d=new SourceDocument();d.setId(id);d.setFileKey("OTHER");d.setFileName("unknown-new-material-"+id+".docx");d.setCategory("VETTING_PACKAGE");d.setReviewRole(role);d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(b)));d.setTextContent(b.getText()+"\n");return d;}
}
