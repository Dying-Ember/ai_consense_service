package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingCorpusTest {
    @Test void projectFactNativeCellsRetainEmptyRepliesAndHeaderProvenanceWithoutChangingSourceTextOrIdentity() {
        DocumentBlock header = table("body/4/table-row/0", "Clause", "Required input", "Reply");
        DocumentBlock populated = table("body/4/table-row/1", "XYZ7", "Planner to provide address 🏠", "Room 8 | East Wing");
        DocumentBlock blank = table("body/4/table-row/2", "XYZ8", "Planner to provide inspection dates", "");
        SourceDocument source = doc(41L, "OTHER", header, populated, blank);
        String originalStructure = source.getStructuredContentJson(), sourceHash = VettingCorpus.sourceHash(source);
        List<Chunk> plain = VettingCorpus.chunks(Collections.singletonList(source));
        source.setReviewRole("project_fact");
        List<Chunk> facts = VettingCorpus.chunks(Collections.singletonList(source));
        assertEquals(plain.size(), facts.size());
        for (int i = 0; i < plain.size(); i++) {
            assertNotEquals(plain.get(i).getId(), facts.get(i).getId());
            assertEquals(plain.get(i).getContent(), facts.get(i).getContent());
            assertEquals(sourceHash, facts.get(i).getSourceHash());
            assertTrue(plain.get(i).getParts().stream().allMatch(part -> part.getTable() != null));
            assertTrue(plain.get(i).getParts().stream().allMatch(part -> part.getTable().getHeaders() == null));
            assertTrue(JsonUtils.write(plain.get(i).getParts()).contains("\"table\""));
        }
        List<VettingCorpus.Part> parts = new ArrayList<>(); facts.forEach(chunk -> parts.addAll(chunk.getParts()));
        assertEquals(populated.getCells(), parts.get(1).getTable().getCells());
        assertEquals("Room 8 | East Wing", parts.get(1).getTable().getCells().get(2));
        assertEquals("", parts.get(2).getTable().getCells().get(2));
        assertEquals(header.getCells(), parts.get(2).getTable().getHeaders());
        assertEquals(header.getId(), parts.get(2).getTable().getHeaderBlockId());
        assertEquals(header.getLocation(), parts.get(2).getTable().getHeaderLocation());
        assertEquals("body/4", parts.get(2).getTable().getTableLocation());
        assertEquals(2, parts.get(2).getTable().getRowIndex());
        assertEquals(originalStructure, source.getStructuredContentJson());
    }
    @Test void partialOrInconsistentTableRowsNeverExposeCompleteReplyMetadata() {
        DocumentBlock header = table("body/4/table-row/0", "Clause", "Required input", "Reply");
        DocumentBlock longRow = table("body/4/table-row/1", "XYZ7", "Planner to provide a value", String.join("", Collections.nCopies(100, "Long original reply. ")));
        DocumentBlock inconsistent = table("body/4/table-row/2", "XYZ8", "Planner to provide a date", "20 January");
        inconsistent.setText("The flattened source has different content.");
        SourceDocument source = doc(42L, "OTHER", header, longRow, inconsistent); source.setReviewRole("project_fact");
        List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(source));
        int splitParts = 0;
        for (Chunk chunk : chunks) for (VettingCorpus.Part part : chunk.getParts()) {
            if (part.getBlockId().equals(longRow.getId())) { splitParts++; assertNull(part.getTable()); }
            if (part.getBlockId().equals(inconsistent.getId())) assertNull(part.getTable());
        }
        assertTrue(splitParts >= 2);
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("The flattened source has different content.")));
    }
    @Test void ownedTableTitlesRestoreClauseScopesAndExplicitContinuationsKeepTheFirstLocation() {
        for (String marker : Arrays.asList("(Cont’d)", "(Cont'd)", "(Continued)")) {
            String owner = "ZQX";
            DocumentBlock first = block("table:1", owner + "6 Other submissions required with tender", null); first.setKind("table_row");
            first.setCells(Collections.singletonList(first.getText()));
            DocumentBlock body = block("table:2", "The following information shall be submitted with the tender.", null); body.setKind("table_row");
            DocumentBlock continued = block("table:3", owner + "6 Other submissions required with tender " + marker + " | ", null); continued.setKind("table_row");
            continued.setCells(Arrays.asList(owner + "6 Other submissions required with tender " + marker, ""));
            DocumentBlock tail = block("table:4", "Further original source requirements.", null); tail.setKind("table_row");
            DocumentBlock next = block("table:5", owner + "7 Inspection of Drawings and documents etc.\nThe officer shall arrange the inspection.\nInput deadline: ... days", null); next.setKind("table_row");
            next.setCells(Collections.singletonList(next.getText()));
            SourceDocument source = doc(9L, owner, first, body, continued, tail, next);
            String revision = VettingCorpus.sourceHash(source), originalStructure = source.getStructuredContentJson();
            List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(source));
            assertEquals(2, chunks.size());
            assertEquals(owner + "6", chunks.get(0).getClauseId());
            assertEquals(first.getLocation(), chunks.get(0).getClauseHeadingLocation());
            assertEquals(owner + "7", chunks.get(1).getClauseId());
            assertEquals(next.getLocation(), chunks.get(1).getClauseHeadingLocation());
            assertTrue(chunks.get(0).getContent().contains(continued.getText()));
            assertTrue(chunks.get(1).getContent().contains("The officer shall arrange the inspection."));
            assertTrue(chunks.stream().allMatch(chunk -> revision.equals(chunk.getSourceHash()) && chunk.getPageNo() == null));
            assertEquals(originalStructure, source.getStructuredContentJson());
            Map<String,DocumentBlock> originals = new HashMap<>();
            for (DocumentBlock original : Arrays.asList(first, body, continued, tail, next)) originals.put(original.getId(), original);
            for (Chunk chunk : chunks) for (VettingCorpus.Part part : chunk.getParts()) {
                DocumentBlock original = originals.get(part.getBlockId());
                assertEquals(original.getText().substring(part.getStartOffset(), part.getEndOffset()), part.getText());
            }
            assertEquals("owner-clause-v2", chunks.get(0).getMetadataVersion());
        }
    }

    @Test void tableScheduleCellsContentsAndSourceReferencesCannotDeclareOwnedHeadings() {
        DocumentBlock heading = block("heading", "XYZ6 Tender submissions", null); heading.setKind("table_row");
        List<DocumentBlock> rows = new ArrayList<>(); rows.add(heading);
        List<String> rejected = Arrays.asList("GCC7 Foreign reference title", "7 Bare schedule number",
                "XYZ7 | Inspection particulars", "XYZ8", "XYZ9 The Contractor shall provide documents.",
                "XYZ10 Submission particulars 24", "An inspection schedule\nXYZ11 A later reference title",
                "XYZ12 The schedule refers to other information.", "XYZ3(4) and XYZ3(6)", "XYZ3(4), (6)");
        for (int i = 0; i < rejected.size(); i++) {
            DocumentBlock row = block("row" + i, rejected.get(i), null); row.setKind("table_row"); rows.add(row);
        }
        DocumentBlock toc = block("toc", "XYZ13 Other submission title", null); toc.setKind("table_row"); toc.setParagraphStyleName("toc 2"); rows.add(toc);
        DocumentBlock multiple = block("multiple", "XYZ14 Inspection particulars", null); multiple.setKind("table_row");
        multiple.setCells(Arrays.asList("XYZ14 Inspection particulars", "Another populated cell")); rows.add(multiple);
        List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(doc(4L, "XYZ", rows.toArray(new DocumentBlock[0]))));
        for (Chunk chunk : chunks) {
            if (chunk.getParts().stream().anyMatch(part -> "multiple".equals(part.getBlockId()))) {
                assertNull(chunk.getClauseId()); assertNull(chunk.getClauseHeadingLocation());
            } else {
                assertEquals("XYZ6", chunk.getClauseId()); assertEquals("heading", chunk.getClauseHeadingLocation());
            }
        }
        Set<String> retained = new HashSet<>(); chunks.forEach(chunk -> chunk.getParts().forEach(part -> retained.add(part.getBlockId())));
        assertEquals(new HashSet<>(Arrays.asList("heading", "row0", "row1", "row2", "row3", "row4", "row5", "row6", "row7", "row8", "row9", "toc", "multiple")), retained);
        SourceDocument unknownOwner = doc(5L, "OTHER", heading);
        assertNull(VettingCorpus.chunks(Collections.singletonList(unknownOwner)).get(0).getClauseId());
    }

    @Test void sameClauseNumberWithoutAnExplicitContinuationStillStartsANewSourceScope() {
        DocumentBlock first = block("first", "XYZ6 Submission details", null); first.setKind("table_row");
        DocumentBlock second = block("second", "XYZ6 Other submission details", null); second.setKind("table_row");
        List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(doc(4L, "XYZ", first, second)));
        assertEquals(2, chunks.size());
        assertEquals("first", chunks.get(0).getClauseHeadingLocation());
        assertEquals("second", chunks.get(1).getClauseHeadingLocation());
        assertNotEquals(chunks.get(0).getId(), chunks.get(1).getId());
    }

    @Test void unstyledStandaloneAppendixLabelsEndThePriorClauseWithoutInventingAnAppendixIdentifier() {
        for (String label : Arrays.asList("APPENDIX B", "ANNEX II", "SCHEDULE 3")) {
            DocumentBlock heading = block("heading", "XYZ24 Disclosure requirements", null); heading.setKind("table_row");
            DocumentBlock reference = block("reference", "This requirement is subject to APPENDIX B.", null); reference.setKind("paragraph");
            DocumentBlock boundary = block("boundary", label, null); boundary.setKind("paragraph");
            DocumentBlock body = block("appendix-body", "Independent original appendix requirements.", null); body.setKind("paragraph");
            List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(doc(7L, "XYZ", heading, reference, boundary, body)));
            assertEquals(2, chunks.size()); assertEquals("XYZ24", chunks.get(0).getClauseId());
            assertTrue(chunks.get(0).getContent().contains(reference.getText()));
            assertNull(chunks.get(1).getClauseId()); assertNull(chunks.get(1).getClauseHeadingLocation());
            assertTrue(chunks.get(1).getContent().startsWith(label));
            assertEquals("boundary", chunks.get(1).getParts().get(0).getBlockId());
            assertNull(chunks.get(1).getPageNo());
        }
    }

    @Test void parsedTableClauseIdentifiersAllowTheProductionSelectorToBringInSourceLinkedProjectFacts() {
        DocumentBlock title = block("table-title", "JKV31 Submission inspection particulars", null); title.setKind("table_row");
        DocumentBlock body = block("table-body", "The officer shall arrange the inspection of submitted documents.", null); body.setKind("table_row");
        SourceDocument tender = doc(8L, "JKV", title, body);
        SourceDocument fact = doc(9L, "OTHER", block("email-body", "The project email confirms that JKV31 uses the west inspection office.", null));
        fact.setReviewRole("project_fact");
        List<Chunk> corpus = VettingCorpus.chunks(Arrays.asList(tender, fact));
        Chunk core = corpus.stream().filter(chunk -> "tender".equals(chunk.getRole())).findFirst().get();
        Chunk email = corpus.stream().filter(chunk -> "project_fact".equals(chunk.getRole())).findFirst().get();
        VettingContextBuilder.Selection selected = new VettingContextBuilder(corpus).build("inspection particulars",
                Collections.singletonList(core), Collections.singletonMap("project_fact", Collections.singletonList(email)), 10000);
        assertEquals(Arrays.asList(core.getId(), email.getId()), selected.getChunks().stream().map(Chunk::getId).collect(java.util.stream.Collectors.toList()));
        assertEquals("JKV31", core.getClauseId()); assertNull(email.getClauseId());
        assertTrue(selected.getGroups().stream().allMatch(group -> !group.getCoreIds().contains(email.getId())));
        assertTrue(selected.getUnresolvedComparisonIds().isEmpty());
    }

    @Test void styleNameEnrichmentKeepsLegacyRevisionHashesButSourceAndLocationsStillChangeThem() {
        DocumentBlock block = block("body:37","XYZ8.14 Effective contract wording.",null);
        block.setParagraphStyle("84"); block.setParagraphStyleName("heading 4");
        SourceDocument d = doc(1L,"XYZ",block);
        com.fasterxml.jackson.databind.JsonNode legacy = JsonUtils.parse(d.getStructuredContentJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)legacy.get(0)).remove("paragraphStyleName");
        String legacyJson = JsonUtils.write(legacy);
        String expected = VettingCorpus.hash(d.getTextContent()+"\n"+legacyJson);
        assertEquals(expected,VettingCorpus.sourceHash(d));
        block.setParagraphStyleName("toc 3"); d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));
        assertEquals(expected,VettingCorpus.sourceHash(d));
        d.setStructuredContentJson("  "+legacyJson+"\n");
        assertEquals(VettingCorpus.hash(d.getTextContent()+"\n  "+legacyJson+"\n"),VettingCorpus.sourceHash(d),"Legacy JSON remains byte-for-byte unchanged");
        block.setLocation("body/38/paragraph"); d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));
        assertNotEquals(expected,VettingCorpus.sourceHash(d));
        block.setLocation("body:37"); block.setText("Revised effective contract wording."); d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));
        assertNotEquals(expected,VettingCorpus.sourceHash(d));
    }
    @Test void ownedHeadingsBoundClausesWhileReferencesAndTablesDoNotChangeTheirOwner() {
        DocumentBlock first = block("h1", "xxx.K83.17.P GCC 9.4 coordination details", null); first.setKind("paragraph");
        DocumentBlock body = block("p1", "First clause obligation.", null); body.setKind("paragraph");
        DocumentBlock foreign = block("p2", "GCC93.4 Original reference details.", null); foreign.setKind("paragraph");
        DocumentBlock prose = block("p3", "Clause XXX.K83.19.P is referred to for submissions.", null); prose.setKind("paragraph");
        DocumentBlock table = block("t1", "93.4(2) | GCC Clause No. | Reference information", null); table.setKind("table_row");
        DocumentBlock second = block("h2", "XXX.K83.18.2(a)New title without space", null); second.setKind("paragraph");
        SourceDocument d = doc(1L,"XXX",first,body,foreign,prose,table,second,block("p4","Second clause obligation.",null));
        List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(d));
        assertEquals(2, chunks.size());
        assertEquals("XXX.K83.17.P", chunks.get(0).getClauseId());
        assertEquals("h1", chunks.get(0).getClauseHeadingLocation());
        assertEquals("XXX.K83.18.2(A)", chunks.get(1).getClauseId());
        assertTrue(chunks.get(0).getParts().stream().filter(p -> "t1".equals(p.getBlockId())).allMatch(p -> p.getAnchor().startsWith("XXX.K83.17.P")));
        assertTrue(chunks.stream().allMatch(c -> c.getPageNo() == null));
    }

    @Test void samePageHeadingChangesAndSupplementaryNotesKeepExactLocations() {
        DocumentBlock first = block("h1", "XYZ4.801First clause", 8); first.setKind("pdf_line");
        DocumentBlock second = block("h2", "XYZ4.802.3(2)Second clause", 8); second.setKind("pdf_line");
        DocumentBlock note = block("fn", "Footnote with source qualifications.", null); note.setKind("footnote");
        List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(doc(1L,"XYZ",first,second,note)));
        assertEquals(3, chunks.size());
        assertEquals("XYZ4.801", chunks.get(0).getClauseId()); assertEquals("P8", chunks.get(0).getPageNo());
        assertEquals("XYZ4.802.3(2)", chunks.get(1).getClauseId()); assertEquals("P8", chunks.get(1).getPageNo());
        assertNull(chunks.get(2).getClauseId()); assertNull(chunks.get(2).getClauseHeadingLocation());
        assertEquals("fn", chunks.get(2).getParts().get(0).getBlockId());
    }

    @Test void unstyledBareReferencesAndContentsCannotDeclareAClause() {
        DocumentBlock bare = block("table-number", "88.9(4) Payment reference", null); bare.setKind("paragraph");
        DocumentBlock contents = block("toc", "XYZ88.10 Other title 14", null); contents.setKind("paragraph"); contents.setParagraphStyle("73"); contents.setParagraphStyleName("toc 2");
        assertTrue(VettingCorpus.chunks(Collections.singletonList(doc(1L,"XYZ",bare,contents))).stream().allMatch(c -> c.getClauseId() == null));
        DocumentBlock heading = block("h", "88.11 Proper heading", null); heading.setKind("paragraph"); heading.setParagraphStyle("Heading3");
        Chunk chunk = VettingCorpus.chunks(Collections.singletonList(doc(1L,"XYZ",heading))).get(0);
        assertEquals("XYZ88.11", chunk.getClauseId());
    }
    @Test void intermediateAlphanumericNamespacesAndNumericStyleIdsRetainCompleteIdentifiers() {
        DocumentBlock first = block("h1","XYZ.Z7.ANNEX42. n Source title",null);
        first.setKind("paragraph"); first.setParagraphStyle("84"); first.setParagraphStyleName("heading 4");
        DocumentBlock next = block("h2","Next unnumbered section",null);
        next.setKind("paragraph"); next.setParagraphStyle("83"); next.setParagraphStyleName("heading 3");
        List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(doc(1L,"XYZ",first,block("p1","Source obligation.",null),next,block("p2","Other content.",null))));
        assertEquals(2,chunks.size()); assertEquals("XYZ.Z7.ANNEX42.N",chunks.get(0).getClauseId());
        assertNull(chunks.get(1).getClauseId()); assertNull(chunks.get(1).getClauseHeadingLocation());
    }
    @Test void headingTitleWordsCannotDonateTheirFirstLetterToTheIdentifier() {
        DocumentBlock first = block("h1","XYZ1.23.Payment terms",null); first.setKind("paragraph");
        DocumentBlock second = block("h2","XYZ1.24.NNext ambiguous title",null); second.setKind("paragraph");
        DocumentBlock third = block("h3","XYZ1.25 Nominated subcontracts",null); third.setKind("paragraph");
        List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(doc(1L,"XYZ",first,second,third)));
        assertEquals(Arrays.asList("XYZ1.23","XYZ1.24","XYZ1.25"),
                Arrays.asList(chunks.get(0).getClauseId(),chunks.get(1).getClauseId(),chunks.get(2).getClauseId()));
    }
    @Test void explicitRolesOverrideInferenceAndLegacyMailNeverBecomesTenderOrStandard() {
        SourceDocument gcc = doc(1L,"GCC",block("p1","Standard payment provision.",null));
        assertEquals("standard", VettingCorpus.sourceRole(gcc));
        gcc.setReviewRole(" project_fact ");
        assertEquals("project_fact", VettingCorpus.sourceRole(gcc));
        assertEquals("project_fact", VettingCorpus.chunks(Collections.singletonList(gcc)).get(0).getRole());
        gcc.setReviewRole(null); gcc.setFileName("GCC discussion.eml");
        assertEquals("project_fact", VettingCorpus.sourceRole(gcc));
        SourceDocument manifest = doc(2L,"PRE",block("p1","Tender package directory only.",null));
        manifest.setReviewRole("package_manifest");
        assertEquals("package_manifest", VettingCorpus.chunks(Collections.singletonList(manifest)).get(0).getRole());
        manifest.setReviewRole("unsupported-untrusted-value");
        assertEquals("tender", VettingCorpus.sourceRole(manifest));
        manifest.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);
        assertEquals("project_fact", VettingCorpus.sourceRole(manifest));
        manifest.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        assertEquals("standard", VettingCorpus.sourceRole(manifest));
    }
    @Test void maintainsPhysicalPagesAndNeverInfersWordPages() {
        SourceDocument word = doc(1L,"SCC",block("p1","SCC 7.1 A clause in the Word document.", null),block("p2","Another paragraph.",null));
        SourceDocument pdf = doc(2L,"FT",block("page8","Tender validity 180 days.",8),block("page9","Execution signature details.",9));
        List<Chunk> chunks = VettingCorpus.chunks(Arrays.asList(word,pdf));
        assertNull(chunks.get(0).getPageNo()); assertTrue(chunks.get(0).getAnchor().contains("p1"));
        assertTrue(chunks.stream().anyMatch(c -> "P8".equals(c.getPageNo())));
        assertTrue(chunks.stream().anyMatch(c -> "P9".equals(c.getPageNo())));
        assertFalse(chunks.stream().filter(c -> "2".equals(c.getDocumentId())).anyMatch(c -> c.getContent().contains("180") && c.getContent().contains("signature")));
    }
    @Test void longTablesRetainFinalRowsAndOnlyWhitespaceToleranceForEvidence() {
        String text = String.join("", Collections.nCopies(230,"Original source table row. ")) + "FINAL-TABLE-ROW";
        SourceDocument d = doc(1L,"PRE",block("table3",text,null));
        List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(d));
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("FINAL-TABLE-ROW")));
        Chunk first = chunks.get(0);
        assertTrue(VettingCorpus.evidence(first,"A","Original\nsource table\trow.").isLocated());
        assertFalse(VettingCorpus.evidence(first,"A","Original invented table row.").isLocated());
        assertFalse(VettingCorpus.evidence(first,"A","table").isLocated());
        assertFalse(VettingCorpus.evidence(first,"A","ORIGINAL source table row.").isLocated());
    }
    @Test void sourceRevisionsProduceNewChunkIdsAndKeepDeleteMetadataOutOfRetrieval() {
        DocumentBlock b = block("p1","Effective contract wording.",null); b.setDeletedText("Withdrawn secret wording"); b.setStrikeText("Unused option");
        SourceDocument d = doc(7L,"NTT",b); Chunk first = VettingCorpus.chunks(Collections.singletonList(d)).get(0);
        assertFalse(first.getContent().contains("Withdrawn"));
        b.setText("Revised effective contract wording."); d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(b))); d.setTextContent(b.getText());
        Chunk second = VettingCorpus.chunks(Collections.singletonList(d)).get(0);
        assertNotEquals(first.getSourceHash(),second.getSourceHash()); assertNotEquals(first.getId(),second.getId());
    }
    private static SourceDocument doc(Long id,String key,DocumentBlock... blocks) {
        SourceDocument d = new SourceDocument(); d.setId(id); d.setFileKey(key); d.setFileName(key+".docx"); d.setCategory("VETTING_PACKAGE");
        d.setTextContent(Arrays.stream(blocks).map(DocumentBlock::getText).reduce("",(a,b)->a+"\n"+b)); d.setStructuredContentJson(JsonUtils.write(Arrays.asList(blocks))); return d;
    }
    private static DocumentBlock block(String id,String text,Integer page) { DocumentBlock b = new DocumentBlock(); b.setId(id); b.setLocation(id); b.setText(text); b.setPageNo(page); return b; }
    private static DocumentBlock table(String location, String... cells) {
        DocumentBlock block = block(location, String.join(" | ", cells), null); block.setKind("table_row");
        block.setCells(Arrays.asList(cells)); return block;
    }
}
