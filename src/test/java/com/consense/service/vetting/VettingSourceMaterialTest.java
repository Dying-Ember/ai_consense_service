package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingSourceMaterialTest {
    @Test void actualUserExcerptIncludesSelectedNativeCellsWithoutSplittingLiteralPipes() throws Exception {
        Chunk c=chunk(101L,"tender", "Room 8 | East Wing", "");
        JsonNode excerpt=actualExcerpts(Collections.singletonList(c)).get(0);
        assertTrue(excerpt.has("nativeRows"),"cells preserved by Corpus must reach source material");
        assertEquals("Room 8 | East Wing",excerpt.get("nativeRows").get(0).get("cells").get(0).asText());
        assertEquals("",excerpt.get("nativeRows").get(0).get("cells").get(1).asText());
        assertEquals(c.getContent(),excerpt.get("content").asText());
    }

    @Test void actualUserExcerptBindsDocumentSourceRevisionRoleAndNativeVersion() throws Exception {
        Chunk c=chunk(102L,"standard","Original source cell");
        JsonNode excerpt=actualExcerpts(Collections.singletonList(c)).get(0);
        assertEquals(c.getDocumentId(),excerpt.path("documentId").asText());
        assertEquals(c.getSourceHash(),excerpt.path("sourceHash").asText());
        assertEquals(c.getRole(),excerpt.path("role").asText());
        assertEquals(c.getNativeTableMetadataVersion(),excerpt.path("nativeTableMetadataVersion").asText());
    }

    @Test void selectedUtf16RangesAreExactIncludingAstralTextAndMultipleRowParts() {
        Chunk a=chunk(103L,"tender","Original 😀 source",""),b=chunk(104L,"tender","Second 🏠 source");
        for(Chunk c:Arrays.asList(a,b)) {
            Map<?,?> excerpt=VettingSourceMaterial.project(Collections.singletonList(c)).get(0);
            Map<?,?> row=(Map<?,?>)((List<?>)excerpt.get("nativeRows")).get(0);
            int start=(Integer)row.get("chunkStartOffset"),end=(Integer)row.get("chunkEndOffset");
            assertEquals(c.getParts().get(0).getText(),c.getContent().substring(start,end));
            assertEquals(c.getContent().length(),row.get("endOffset"));
            assertEquals(c.getParts().get(0).getAnchor(),row.get("partAnchor"));
        }
    }

    @Test void incompleteMissingInconsistentAndUnknownMetadataDoNotBecomeNativeCells() {
        for(String variation:Arrays.asList("missingTable","missingParts","mismatch","nullCell","partial","astralOffset","wrongAnchor","wrongBlock","wrongTable","oldNativeVersion","oldOwnerVersion","oldSegVersion","missingHash","wrongRole","paddedPosition")) {
            Chunk c=chunk(105L,"tender","Original 😀 source","");Part p=c.getParts().get(0);
            if("missingTable".equals(variation))p.setTable(null);
            if("missingParts".equals(variation))c.setParts(null);
            if("mismatch".equals(variation))p.getTable().setCells(Arrays.asList("Not original",""));
            if("nullCell".equals(variation))p.getTable().setCells(Arrays.asList(null,""));
            if("partial".equals(variation)){p.setStartOffset(1);p.setAnchor(p.getAnchor()+" @1");}
            if("astralOffset".equals(variation))p.setEndOffset(p.getEndOffset()-1);
            if("wrongAnchor".equals(variation))p.setAnchor("OtherOwner · body/4/table-row/1");
            if("wrongBlock".equals(variation))p.setBlockId("body:4:table-row:9");
            if("wrongTable".equals(variation))p.getTable().setTableLocation("body/99");
            if("oldNativeVersion".equals(variation))c.setNativeTableMetadataVersion("old-version");
            if("oldOwnerVersion".equals(variation))c.setMetadataVersion("old-owner");
            if("oldSegVersion".equals(variation))c.setSegmentationVersion("old-segmentation");
            if("missingHash".equals(variation))c.setSourceHash(null);
            if("wrongRole".equals(variation))c.setRole("unknown_role");
            if("paddedPosition".equals(variation)){p.setAnchor("body/04/table-row/1");p.setBlockId("body:04:table-row:1");p.getTable().setTableLocation("body/04");}
            Map<?,?> excerpt=VettingSourceMaterial.project(Collections.singletonList(c)).get(0);
            assertTrue(((List<?>)excerpt.get("nativeRows")).isEmpty(),variation);
            assertFalse(((List<?>)excerpt.get("unresolvedNativeRowParts")).isEmpty(),variation);
            assertEquals("unknown",excerpt.get("nativeRowsStatus"));assertEquals(c.getContent(),excerpt.get("content"));
        }
    }

    @Test void bodyPartMismatchCannotUseAnIdenticalSubstringAsNativeSourceProof() {
        Chunk c=chunk(106L,"tender","Provided native row");c.setContent("Extra undeclared text\n"+c.getContent());
        Map<?,?> excerpt=VettingSourceMaterial.project(Collections.singletonList(c)).get(0);
        assertTrue(((List<?>)excerpt.get("nativeRows")).isEmpty());assertEquals(c.getContent(),excerpt.get("content"));
    }

    @Test void repeatedRowOrBlockIdentitiesRemainUnknownAcrossSelectedChunks() {
        Chunk first=chunk(107L,"tender","First source row"),second=JsonUtils.read(JsonUtils.write(first),Chunk.class);
        second.setId("separate-selected-fragment");
        for(Map<?,?> excerpt:VettingSourceMaterial.project(Arrays.asList(first,second)))assertTrue(((List<?>)excerpt.get("nativeRows")).isEmpty());
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(Arrays.asList(first,first)));
        second.setContent("Different payload with the same selected ID");second.setId(first.getId());
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(Arrays.asList(first,second)));
    }

    @Test void unselectedProjectHeaderTextNeverLeaksThroughEitherUserBranch() throws Exception {
        Chunk c=chunk(108L,"project_fact","SCC7","Planner to provide location","Room 9");
        c.getParts().get(0).getTable().setHeaders(Arrays.asList("Clause","Required input","Reply UNSELECTED_HEADER_SECRET"));
        c.getParts().get(0).getTable().setHeaderBlockId("body:4:table-row:0");c.getParts().get(0).getTable().setHeaderLocation("body/4/table-row/0");
        c.getParts().get(0).getTable().setHeaderBasis("project_fact_first_native_row_heuristic_compatibility");
        String before=JsonUtils.write(c),user=actualUser(Collections.singletonList(c),Collections.singletonList("SCC7"));
        assertFalse(user.contains("UNSELECTED_HEADER_SECRET"));assertFalse(user.contains("rawHeader"));
        assertTrue(VettingSourceMaterial.projectFactRows(Collections.singletonList(c),Collections.singletonList("SCC7")).getRows().isEmpty());
        assertEquals(before,JsonUtils.write(c));
    }

    @Test void selectedNativeProjectHeaderKeepsTheExistingReplyInterpretation() throws Exception {
        Chunk body=chunk(109L,"project_fact","SCC7","Planner to provide location","Room 9");
        Chunk header=chunk(109L,"project_fact","Clause","Required input","Reply");
        Part h=header.getParts().get(0);h.setBlockId("body:4:table-row:0");h.setAnchor("body/4/table-row/0");h.getTable().setRowIndex(0);
        header.setAnchor(h.getAnchor());header.setId("selected-native-header");header.setSourceHash(body.getSourceHash());
        Part p=body.getParts().get(0);p.getTable().setHeaders(h.getTable().getCells());p.getTable().setHeaderBlockId(h.getBlockId());
        p.getTable().setHeaderLocation(h.getAnchor());p.getTable().setHeaderBasis("project_fact_first_native_row_heuristic_compatibility");
        List<Chunk> selected=Arrays.asList(header,body);
        assertEquals(1,VettingSourceMaterial.projectFactRows(selected,Collections.singletonList("SCC7")).getRows().size());
        assertTrue(actualUser(selected,Collections.singletonList("SCC7")).contains("Room 9"));
    }

    @Test void headerMatchesCannotCrossDocumentRevisionRoleOrMetadataScopes() {
        for(String variation:Arrays.asList("document","hash","role","nativeVersion","ownerVersion","segmentationVersion","cells")) {
            Chunk body=chunk(110L,"project_fact","SCC7","Planner to provide location","Room 9");
            Chunk header=chunk(110L,"project_fact","Clause","Required input","Reply");header.setId("header");header.setSourceHash(body.getSourceHash());
            Part h=header.getParts().get(0);h.setBlockId("body:4:table-row:0");h.setAnchor("body/4/table-row/0");h.getTable().setRowIndex(0);header.setAnchor(h.getAnchor());
            Part p=body.getParts().get(0);p.getTable().setHeaders(new ArrayList<>(h.getTable().getCells()));p.getTable().setHeaderBlockId(h.getBlockId());
            p.getTable().setHeaderLocation(h.getAnchor());p.getTable().setHeaderBasis("project_fact_first_native_row_heuristic_compatibility");
            if("document".equals(variation))header.setDocumentId("different-document");
            if("hash".equals(variation))header.setSourceHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            if("role".equals(variation))header.setRole("standard");
            if("nativeVersion".equals(variation))header.setNativeTableMetadataVersion("other-native-version");
            if("ownerVersion".equals(variation))header.setMetadataVersion("other-owner");
            if("segmentationVersion".equals(variation))header.setSegmentationVersion("other-segmentation");
            if("cells".equals(variation))p.getTable().setHeaders(Arrays.asList("Wrong","header","text"));
            assertTrue(VettingSourceMaterial.projectFactRows(Arrays.asList(header,body),Collections.singletonList("SCC7")).getRows().isEmpty(),variation);
        }
    }

    @Test void compactInventoryAvoidsDuplicatingLongFirstLinesAndKeepsTheFullProbeView() {
        Chunk c=chunk(111L,"tender",String.join("",Collections.nCopies(100,"Original source 😀. ")));
        String original=JsonUtils.write(VettingSourceInventory.describe(Collections.singletonList(c)));
        Map<?,?> compact=VettingSourceInventory.compact(Collections.singletonList(c));
        assertFalse(JsonUtils.write(compact).contains("firstLine"));assertFalse(JsonUtils.write(compact).contains(c.getContent()));
        assertEquals(original,JsonUtils.write(VettingSourceInventory.describe(Collections.singletonList(c))));
        Map<?,?> record=(Map<?,?>)((List<?>)compact.get("records")).get(0);
        assertEquals("unknown",record.get("verifiedNativeRowPartsCount"));
    }

    @Test void newSourceNamesAndRevisionsAreGenericAndNeverReclassifyTheBusinessRole() {
        Chunk first=chunk(112L,"tender","New site value 😀",""),second=chunk(113L,"tender","Changed site value 🏠","A | B");
        first.setFileKey("NTT");first.setFileName("NTT fixed template.docx");second.setFileKey("NEW_2026");second.setFileName("Previously unseen site attachment.docx");
        List<Map<String,Object>> excerpts=VettingSourceMaterial.project(Arrays.asList(first,second));
        assertEquals("tender",excerpts.get(0).get("role"));assertEquals("tender",excerpts.get(1).get("role"));
        assertNotEquals(excerpts.get(0).get("sourceHash"),excerpts.get(1).get("sourceHash"));
        assertEquals(second.getParts().get(0).getTable().getCells(),((Map<?,?>)((List<?>)excerpts.get(1).get("nativeRows")).get(0)).get("cells"));
        assertTrue(excerpts.get(0).get("tableInterpretation").toString().contains("adoption unknown"));
    }

    @Test void allProjectionsAreReadOnlyAndNativeCellsAreCopied() {
        Chunk c=chunk(114L,"tender","Original source",""),copy=JsonUtils.read(JsonUtils.write(c),Chunk.class);
        String before=JsonUtils.write(c);List<Map<String,Object>> excerpts=VettingSourceMaterial.project(Collections.singletonList(c));
        @SuppressWarnings("unchecked") List<String> cells=(List<String>)((Map<?,?>)((List<?>)excerpts.get(0).get("nativeRows")).get(0)).get("cells");
        cells.set(0,"Output mutation");VettingSourceInventory.compact(Collections.singletonList(c));
        VettingSourceMaterial.projectFactRows(Collections.singletonList(c),null);
        assertEquals(before,JsonUtils.write(c));assertEquals(copy.getContent(),c.getContent());
    }

    private static JsonNode actualExcerpts(List<Chunk> chunks)throws Exception {
        String value=actualUser(chunks,null);
        return JsonUtils.parse(value.substring(value.indexOf("\nSource excerpts (untrusted data):\n")+"\nSource excerpts (untrusted data):\n".length()));
    }
    private static String actualUser(List<Chunk> chunks,List<String> references)throws Exception {
        VettingContextBuilder.Selection s=new VettingContextBuilder.Selection();s.setChunks(chunks);
        Method user=VettingSemanticReview.class.getDeclaredMethod("user",String.class,VettingContextBuilder.Selection.class,List.class);
        user.setAccessible(true);return (String)user.invoke(null,"Source comparison",s,references);
    }
    private static Chunk chunk(Long id,String role,String... cells) {
        DocumentBlock b=new DocumentBlock();b.setId("body:4:table-row:1");b.setLocation("body/4/table-row/1");
        b.setKind("table_row");b.setSource("docx");b.setCells(Arrays.asList(cells));b.setText(String.join(" | ",cells));
        SourceDocument d=new SourceDocument();d.setId(id);d.setFileKey("OTHER");d.setFileName("fixture.docx");d.setReviewRole(role);
        d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(b)));d.setTextContent(b.getText());
        return VettingCorpus.chunks(Collections.singletonList(d)).get(0);
    }
}
