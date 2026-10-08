package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingNativeTableMetadataTest {
    @Test void everyVettedRoleRetainsValidatedOrderedSourceCells() {
        DocumentBlock title = row("body/4/table-row/0", "Observed first row");
        DocumentBlock values = row("body/4/table-row/1", "Address", "Room 8 | East Wing", "");
        for (String role : Arrays.asList("tender", "standard", "project_fact", "package_manifest")) {
            SourceDocument source = document(71L, role, title, values);
            String original = source.getStructuredContentJson(), hash = VettingCorpus.sourceHash(source);
            List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(source));
            Part part = part(chunks, values.getId());
            assertNotNull(part.getTable(), "validated cells must survive role " + role);
            assertEquals(values.getCells(), part.getTable().getCells());
            assertEquals("Room 8 | East Wing", part.getTable().getCells().get(1));
            assertEquals("", part.getTable().getCells().get(2));
            assertEquals(values.getText(), part.getText());
            assertEquals(0, part.getStartOffset()); assertEquals(values.getText().length(), part.getEndOffset());
            assertEquals(original, source.getStructuredContentJson());
            assertEquals(hash, VettingCorpus.sourceHash(source));
            assertTrue(chunks.stream().allMatch(c -> role.equals(c.getRole())));
            if (!"project_fact".equals(role)) {
                assertNull(part.getTable().getHeaders(), "a native first row is not a confirmed generic header");
                assertNull(part.getTable().getHeaderBlockId()); assertNull(part.getTable().getHeaderLocation());
            }
        }
    }

    @Test void roleChangesCannotReuseAnOldChunkPayloadIdentity() {
        SourceDocument source = document(72L, "tender", row("body/5/table-row/0", "Clause", "Reply"));
        Chunk tender = VettingCorpus.chunks(Collections.singletonList(source)).get(0);
        source.setReviewRole("project_fact");
        Chunk fact = VettingCorpus.chunks(Collections.singletonList(source)).get(0);
        assertNotEquals(tender.getId(), fact.getId(), "different role interpretation must not share a cached payload identity");
        assertEquals(tender.getContent(), fact.getContent()); assertEquals(tender.getSourceHash(), fact.getSourceHash());
    }

    @Test void projectFactInterpretationRemainsExplicitlyHeuristicAndRoleScoped() {
        DocumentBlock header = row("body/8/table-row/0", "Clause", "Required input", "Reply");
        DocumentBlock value = row("body/8/table-row/1", "SCC7", "Planner to provide address", "Room 9");
        List<Chunk> facts = VettingCorpus.chunks(Collections.singletonList(document(73L,"project_fact",header,value)));
        Part fact = part(facts, value.getId());
        assertEquals(header.getCells(), fact.getTable().getHeaders());
        assertEquals(header.getId(), fact.getTable().getHeaderBlockId());
        assertEquals("project_fact_first_native_row_heuristic_compatibility", fact.getTable().getHeaderBasis());
        assertEquals(1, VettingProjectFactTable.rows(facts, Collections.singletonList("SCC7")).getRows().size());
        List<Chunk> tenders = VettingCorpus.chunks(Collections.singletonList(document(74L,"tender",header,value)));
        assertEquals("unknown", part(tenders,value.getId()).getTable().getHeaderBasis());
        assertNull(part(tenders,value.getId()).getTable().getHeaders());
        assertTrue(VettingProjectFactTable.rows(tenders,Collections.singletonList("SCC7")).getRows().isEmpty());
    }

    @Test void longRowsNeverAttachCellsOutsideTheSelectedUtf16Slice() {
        DocumentBlock longRow = row("body/9/table-row/1", "SCC8", String.join("",Collections.nCopies(1000,"A😀")), "Last value");
        for(String role:Arrays.asList("tender","standard","project_fact","package_manifest")) {
            List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(document(75L,role,longRow)));
            int parts=0;
            for(Chunk c:chunks)for(Part p:c.getParts()) {
                parts++; assertNull(p.getTable()); assertEquals(longRow.getId(),p.getBlockId());
                assertEquals(longRow.getText().substring(p.getStartOffset(),p.getEndOffset()),p.getText());
                assertFalse(Character.isLowSurrogate(p.getText().charAt(0)));
                assertFalse(Character.isHighSurrogate(p.getText().charAt(p.getText().length()-1)));
            }
            assertTrue(parts>1);
            assertTrue(chunks.get(chunks.size()-1).getContent().endsWith("Last value"));
        }
    }

    @Test void missingInconsistentLegacyOcrAndInvalidLocationsRemainUnknown() {
        for(String variant:Arrays.asList("empty","nullCells","nullCell","mismatch","ocr","pdf","legacySource","legacy","ocrKind","badLocation","negativeRow","overflowRow")) {
            DocumentBlock b=row("body/10/table-row/1","Source","Original answer");
            if("empty".equals(variant))b.setCells(Collections.emptyList());
            if("nullCells".equals(variant))b.setCells(null);
            if("nullCell".equals(variant))b.setCells(Arrays.asList("Source",null));
            if("mismatch".equals(variant))b.setText("Different source text remains intact.");
            if("ocr".equals(variant))b.setSource("ocr");
            if("pdf".equals(variant))b.setSource("pdf-native");
            if("legacySource".equals(variant))b.setSource("legacy");
            if("legacy".equals(variant))b.setKind("legacy");
            if("ocrKind".equals(variant))b.setKind("ocr_line");
            if("badLocation".equals(variant))b.setLocation("page/1/line/2");
            if("negativeRow".equals(variant))b.setLocation("body/10/table-row/-1");
            if("overflowRow".equals(variant))b.setLocation("body/10/table-row/99999999999999999999999999");
            for(String role:Arrays.asList("tender","project_fact")) {
                List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(document(76L,role,b)));
                assertNull(part(chunks,b.getId()).getTable(),variant+" must not fabricate native structure");
                assertEquals(b.getText(),part(chunks,b.getId()).getText());
            }
        }
        SourceDocument legacy=document(76L,"tender"); legacy.setStructuredContentJson(null); legacy.setTextContent("Legacy source | has no native cell metadata.");
        assertTrue(VettingCorpus.chunks(Collections.singletonList(legacy)).stream().flatMap(c->c.getParts().stream()).allMatch(p->p.getTable()==null));
    }

    @Test void reusedTablePositionsAcrossDocumentsKeepCellsAndHeadersIsolated() {
        DocumentBlock firstHeader=row("body/11/table-row/0","Clause","Reply");
        DocumentBlock first=row("body/11/table-row/1","SCC7","First document reply");
        DocumentBlock secondHeader=row("body/11/table-row/0","Clause","Required input","Reply");
        DocumentBlock second=row("body/11/table-row/1","SCC7","Different request","Second document reply");
        List<Chunk> chunks=VettingCorpus.chunks(Arrays.asList(document(77L,"project_fact",firstHeader,first),document(78L,"tender",secondHeader,second)));
        for(Chunk c:chunks)for(Part p:c.getParts())if(p.getBlockId().equals(first.getId())) {
            if("77".equals(c.getDocumentId())) {
                assertEquals(first.getCells(),p.getTable().getCells()); assertEquals(firstHeader.getCells(),p.getTable().getHeaders());
                assertEquals("project_fact",c.getRole());
            } else {
                assertEquals("78",c.getDocumentId()); assertEquals(second.getCells(),p.getTable().getCells());
                assertNull(p.getTable().getHeaders()); assertEquals("tender",c.getRole());
            }
        }
        assertEquals(chunks.size(),chunks.stream().map(Chunk::getId).distinct().count());
    }

    @Test void nativePayloadVersionChangesAllOldIdentitiesWithoutChangingSourceOrOwners() {
        DocumentBlock heading=new DocumentBlock(); heading.setId("body:0"); heading.setLocation("body/0");
        heading.setKind("paragraph"); heading.setSource("docx"); heading.setText("NTT7 Submission particulars");
        DocumentBlock value=row("body/1/table-row/0","Field","Original value 😀");
        SourceDocument source=document(79L,"tender",heading,value); source.setFileKey("NTT");
        String structure=source.getStructuredContentJson(), hash=VettingCorpus.sourceHash(source);
        List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        for(Chunk c:chunks) {
            assertEquals(VettingCorpus.NATIVE_TABLE_METADATA_VERSION,c.getNativeTableMetadataVersion());
            assertEquals(hash,c.getSourceHash()); assertEquals("NTT7",c.getClauseId()); assertEquals("body/0",c.getClauseHeadingLocation());
            String oldSalt="owner-clause-v2|standalone-section-boundary-v1|79|"+hash+"|"+c.getParts().stream().map(Part::getBlockId).reduce((a,b)->a+","+b).get();
            assertNotEquals(UUID.nameUUIDFromBytes(oldSalt.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),c.getId());
        }
        assertEquals(structure,source.getStructuredContentJson()); assertEquals(value.getText(),part(chunks,value.getId()).getText());
        assertEquals(value.getText().length(),part(chunks,value.getId()).getEndOffset());
    }

    @Test void duplicateBlockIdsNeverAttachAnotherRowsCellsToAnyPart() {
        DocumentBlock first=row("body/12/table-row/1","First","one"), second=row("body/13/table-row/2","Second","two");
        first.setId("duplicate");second.setId("duplicate");
        List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(document(80L,"tender",first,second)));
        assertEquals(2,chunks.stream().flatMap(c->c.getParts().stream()).count());
        assertTrue(chunks.stream().flatMap(c->c.getParts().stream()).allMatch(p->p.getTable()==null));
        assertTrue(chunks.stream().anyMatch(c->c.getContent().contains(first.getText())&&c.getContent().contains(second.getText())));
    }

    @Test void duplicateRowPositionsRemainUnknownEvenWithDifferentBlockIds() {
        DocumentBlock first=row("body/14/table-row/1","First","one"), second=row("body/14/table-row/1","Second","two");
        second.setId("different-native-block");
        List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(document(81L,"standard",first,second)));
        assertTrue(chunks.stream().flatMap(c->c.getParts().stream()).allMatch(p->p.getTable()==null));
        assertEquals(2,chunks.stream().flatMap(c->c.getParts().stream()).count());
    }

    @Test void ambiguousFirstRowsNeverBecomeProjectFactHeaderAnnotations() {
        DocumentBlock firstHeader=row("body/15/table-row/0","Clause","Reply"), secondHeader=row("body/15/table-row/0","Misleading header","Other field");
        secondHeader.setId("second-native-header-block");
        DocumentBlock body=row("body/15/table-row/1","SCC7","Actual reply");
        List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(document(82L,"project_fact",firstHeader,secondHeader,body)));
        assertNull(part(chunks,firstHeader.getId()).getTable());assertNull(part(chunks,secondHeader.getId()).getTable());
        Part value=part(chunks,body.getId()); assertNotNull(value.getTable());assertEquals(body.getCells(),value.getTable().getCells());
        assertNull(value.getTable().getHeaders());assertEquals("unknown",value.getTable().getHeaderBasis());
    }

    private static Part part(List<Chunk> chunks, String id) {
        return chunks.stream().flatMap(c -> c.getParts().stream()).filter(p -> id.equals(p.getBlockId())).findFirst().get();
    }
    private static DocumentBlock row(String location, String... cells) {
        DocumentBlock b = new DocumentBlock(); b.setId(location.replace('/', ':')); b.setLocation(location);
        b.setKind("table_row"); b.setSource("docx"); b.setCells(Arrays.asList(cells)); b.setText(String.join(" | ", cells)); return b;
    }
    private static SourceDocument document(Long id, String role, DocumentBlock... blocks) {
        SourceDocument d = new SourceDocument(); d.setId(id); d.setFileKey("OTHER"); d.setFileName("source-"+id+".docx");
        d.setReviewRole(role); d.setCategory("VETTING_PACKAGE"); d.setStructuredContentJson(JsonUtils.write(Arrays.asList(blocks)));
        StringBuilder text = new StringBuilder(); for (DocumentBlock b : blocks) text.append(b.getText()).append('\n');
        d.setTextContent(text.toString()); return d;
    }
}
