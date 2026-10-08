package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Candidate;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingServiceTest {
    @Test void nonBmpSplitBoundariesAndOverlapsCountOnlyOriginalUtf16Ranges() {
        String text = String.join("",Collections.nCopies(1199,"a")) + "\uD83D\uDE00"
                + String.join("",Collections.nCopies(198,"b")) + "\uD83D\uDE00"
                + String.join("",Collections.nCopies(2000,"c"));
        SourceDocument d = document(block("body:37",text));
        List<Chunk> chunks = VettingCorpus.chunks(Collections.singletonList(d));
        assertEquals(text.length(),VettingService.reviewedChars(d,chunks));
        assertEquals(2600,VettingService.reviewedChars(d,Arrays.asList(chunks.get(0),chunks.get(1))));
        for (Chunk c : chunks) for (VettingCorpus.Part p : c.getParts()) {
            assertEquals("body:37",p.getBlockId());
            assertEquals(text.substring(p.getStartOffset(),p.getEndOffset()),p.getText());
            assertFalse(Character.isLowSurrogate(p.getText().charAt(0)));
            assertFalse(Character.isHighSurrogate(p.getText().charAt(p.getText().length()-1)));
        }
    }
    @Test void explicitSourceOffsetsCountUtf16UnionRejectBadRangesAndKeepLegacyParts() {
        String text = "Original \uD83D\uDE00 source clause with an exception.";
        SourceDocument d = document(block("paragraph 3", text));
        Chunk c = VettingCorpus.chunks(Collections.singletonList(d)).get(0);
        assertEquals(text.length(), VettingService.reviewedChars(d, Arrays.asList(c,c)));
        VettingCorpus.Part original = c.getParts().get(0);
        assertEquals("paragraph 3", original.getBlockId()); assertEquals(text.length(), original.getEndOffset());
        original.setEndOffset(text.length() + 1); assertEquals(0, VettingService.reviewedChars(d, Collections.singletonList(c)));
        original.setEndOffset(text.length()); original.setStartOffset(-1); assertEquals(0, VettingService.reviewedChars(d, Collections.singletonList(c)));
        original.setStartOffset(0); original.setText("Invented clause"); assertEquals(0, VettingService.reviewedChars(d, Collections.singletonList(c)));
        original.setText(text); original.setBlockId("unknown-id"); assertEquals(0, VettingService.reviewedChars(d, Collections.singletonList(c)));
        original.setBlockId(null); original.setStartOffset(700); original.setEndOffset(900);
        assertEquals(text.length(), VettingService.reviewedChars(d, Collections.singletonList(c)), "Legacy parts use their exact location and text, not absent offset metadata");
    }
    @Test void recognizesCompetitionFileNamesAndKeepsUnknownDocuments() {
        assertEquals("FT",VettingService.matchFileKey("03_Form of Tender (FoT).pdf"));
        assertEquals("AA",VettingService.matchFileKey("07_Articles of Agreement (AoA).docx"));
        assertEquals("GCC",VettingService.matchFileKey("09_General Conditions of Contract.pdf"));
        assertEquals("GCC",VettingService.matchFileKey("Contract CoC.pdf"));
        assertEquals("SL",VettingService.matchFileKey("26_Specification Library (SL2022).pdf"));
        assertEquals("PRE",VettingService.matchFileKey("05_Preliminaries.pdf"));
        assertEquals("SP",VettingService.matchFileKey("07_Project Specification.docx"));
        assertEquals("APL",VettingService.matchFileKey("Appendix to Tender APL.pdf"));
        assertEquals("BQ",VettingService.matchFileKey("Bills of Quantities.pdf"));
        assertEquals("BQ",VettingService.matchFileKey("24_BQ.pdf"));
        assertEquals("GS",VettingService.matchFileKey("General Summary.xlsx"));
        assertEquals("OTHER",VettingService.matchFileKey("engineering sketch.pdf"));
    }

    @Test void fingerprintIgnoresEvidenceOrderAndFormattingButTracksSourceRevision() {
        FindingEvidence a=evidence("SCC.docx","digest-1","paragraph 9","Clause 7.1 is deleted.");
        FindingEvidence b=evidence("PRE.pdf","digest-2","page 6","Subject to GCC clause 7.1.");
        Candidate first=candidate(a,b),second=candidate(b,a);
        assertEquals(VettingService.fingerprint(first),VettingService.fingerprint(second));
        String original=VettingService.fingerprint(first);
        a.setQuote("Clause\n7.1\tis deleted.");
        assertEquals(original,VettingService.fingerprint(first));
        a.setSourceHash("new-digest");
        assertNotEquals(original,VettingService.fingerprint(first));
        a.setSourceHash("digest-1"); a.setQuote("Clause 7.2 is deleted.");
        assertNotEquals(original,VettingService.fingerprint(first));
    }

    @Test void countsUnionOfModelRangesRatherThanDuplicateOverlappingCharacters() {
        String table=String.join("",Collections.nCopies(180,"Effective source table text. "));
        SourceDocument doc=document(block("table 3",table),block("paragraph 41","FINAL-UNREVIEWED-PARAGRAPH"));
        List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(doc));
        assertTrue(chunks.size()>3);
        assertEquals(0,VettingService.reviewedChars(doc,Collections.emptyList()));
        assertEquals(1400,VettingService.reviewedChars(doc,Collections.singletonList(chunks.get(0))));
        assertEquals(2600,VettingService.reviewedChars(doc,Arrays.asList(chunks.get(0),chunks.get(1))));
        assertEquals(1400,VettingService.reviewedChars(doc,Arrays.asList(chunks.get(0),chunks.get(0))));
        assertEquals(table.length()+"FINAL-UNREVIEWED-PARAGRAPH".length(),VettingService.reviewedChars(doc,chunks));
    }

    @Test void duplicateSourceParagraphsHaveTheirOwnCoverageAndWordHasNoSyntheticPages() {
        SourceDocument doc=document(block("paragraph 1","A repeated effective source paragraph."),block("paragraph 2","A repeated effective source paragraph."));
        List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(doc));
        assertEquals(2*"A repeated effective source paragraph.".length(),VettingService.reviewedChars(doc,chunks));
        assertTrue(chunks.stream().allMatch(c -> c.getPageNo()==null));
    }

    private static Candidate candidate(FindingEvidence... evidence) { Candidate c=new Candidate();c.setRuleId("deleted_clause_reference");c.setEvidence(Arrays.asList(evidence));return c; }
    private static FindingEvidence evidence(String name,String hash,String anchor,String quote) { return new FindingEvidence("source","1","SCC",name,null,anchor,quote,true,hash,null); }
    private static DocumentBlock block(String location,String text) { DocumentBlock b=new DocumentBlock();b.setId(location);b.setLocation(location);b.setText(text);return b; }
    private static SourceDocument document(DocumentBlock... blocks) {
        SourceDocument d=new SourceDocument();d.setId(1L);d.setFileKey("PRE");d.setFileName("PRE.docx");d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setStructuredContentJson(JsonUtils.write(Arrays.asList(blocks)));d.setTextContent(Arrays.stream(blocks).map(DocumentBlock::getText).reduce("",(a,b)->a+"\n"+b));return d;
    }
}
