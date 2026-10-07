package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingContextBuilder.Selection;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingBlockContinuationTest {
    @Test void selectedSplitRowRequiresItsTailWithoutInventingAClauseOrHeader() {
        String source = "The submission shall identify persons. " + "x".repeat(1380) + " Minimum staffing: two persons.";
        Chunk head = chunk("head", "row", source.substring(0,1400),0);
        Chunk tail = chunk("tail", "row", source.substring(1200),1200);
        Selection s = select(Arrays.asList(head,tail),Collections.singletonList(head),10000);
        assertEquals(Arrays.asList("head","tail"),ids(s));
        assertEquals(source.substring(1200),s.getChunks().get(1).getContent());
        assertSame(tail,s.getChunks().get(1));
        assertNull(tail.getClauseId()); assertNull(tail.getParts().get(0).getTable());
        VettingBlockContinuation.Trace t=s.getBlockContinuations().get(0);
        assertTrue(t.isObservedRangesTransported());assertFalse(t.isSourceBlockCompleteKnown());
        assertEquals("unknown",t.getQualifiersComplete());assertEquals("observed_corpus_ranges_only",t.getCoverage());
        assertEquals(source.length(),t.getObservedEndOffset());assertEquals("submitted_chunks_only",s.getCoverage());
    }

    @Test void reversedRankedTailRecoversEveryObservedSliceAndDoesNotTruncateBody() {
        String raw="The supplier shall retain "+"z".repeat(4400);
        List<Chunk> corpus=new ArrayList<>();for(int start=0;start<raw.length();start+=1200)corpus.add(chunk("c"+start,"row",raw.substring(start,Math.min(start+1400,raw.length())),start));
        Chunk tail=corpus.get(corpus.size()-1);Selection s=select(corpus,Collections.singletonList(tail),10000);
        assertEquals(corpus.size(),s.getChunks().size());assertEquals(tail.getId(),s.getChunks().get(0).getId());
        assertTrue(s.getBlockContinuations().get(0).isObservedRangesTransported());
        for(Chunk c:corpus)assertSame(c,s.getChunks().stream().filter(x->x.getId().equals(c.getId())).findFirst().get());
    }
    @Test void budgetLeavesLocatedMissingTailExplicitAndSingleExpansionCanAddIt() {
        String raw="The supplier shall retain "+"x".repeat(1420);
        Chunk h=chunk("h","row",raw.substring(0,1400),0),t=chunk("t","row",raw.substring(1200),1200);
        List<Chunk> corpus=Arrays.asList(h,t);Selection initial=select(corpus,Collections.singletonList(h),1500);
        assertEquals(Collections.singletonList("h"),ids(initial));assertEquals("blocked_budget",initial.getBlockContinuations().get(0).getAdmissionStatus());
        assertEquals(Collections.singletonList("t"),initial.getBlockContinuations().get(0).getMissingChunkIds());
        assertFalse(initial.getGroups().get(0).isFullySubmitted());assertTrue(initial.getUnresolvedComparisonIds().contains("h"));
        VettingContextBudget.Result result=VettingContextBudget.build(new VettingContextBuilder(corpus),"supplier",Collections.singletonList(h),Collections.emptyMap(),1500,2000);
        assertTrue(result.isExpanded());assertTrue(result.isInitialChunksPreserved());assertEquals("located_source_context_added",result.getDecision());
        assertEquals(Collections.singleton("t"),result.getInitialMissingTargetIds());assertTrue(result.getFinalMissingTargetIds().isEmpty());
    }
    @Test void droppedCoreCannotStartContinuationClosure() {
        Chunk h=chunk("h","row","The supplier shall retain "+"x".repeat(1400),0),t=chunk("t","row","tail",1200);
        Selection s=select(Arrays.asList(h,t),Collections.singletonList(h),1000);
        assertTrue(s.getChunks().isEmpty());assertFalse(s.getBlockContinuations().get(0).isInitiallySubmitted());
        assertEquals("origin_not_eligible_at_closure_start",s.getBlockContinuations().get(0).getStatus());
    }
    @Test void continuationCannotFollowItsLiteralReferenceToAnotherBlock() {
        String raw="The supplier shall retain "+"x".repeat(1380)+" XYZ99(1).";
        Chunk h=chunk("h","row",raw.substring(0,1400),0),t=chunk("t","row",raw.substring(1200),1200);
        h.setFileKey("XYZ");t.setFileKey("XYZ");
        Chunk third=chunk("third","different","Additional requirement.",0);third.setFileKey("XYZ");third.setClauseId("XYZ99");third.setClauseHeadingLocation("third");
        Selection s=select(Arrays.asList(h,t,third),Collections.singletonList(h),10000);
        assertEquals(Arrays.asList("h","t"),ids(s));assertTrue(s.getReferences().isEmpty());
    }
    @Test void sourceHashRoleDocumentAndBlockIdsPreventCrossBinding() {
        for(String field:Arrays.asList("hash","role","document","block")) {
            Chunk h=chunk("h","row","The supplier shall retain "+"x".repeat(1380),0),t=chunk("t","row","wrong",1200);
            if(field.equals("hash"))t.setSourceHash("revision-two");if(field.equals("role"))t.setRole("standard");
            if(field.equals("document"))t.setDocumentId("other-doc");if(field.equals("block"))t.getParts().get(0).setBlockId("other-row");
            Selection s=select(Arrays.asList(h,t),Collections.singletonList(h),10000);
            assertEquals(Collections.singletonList("h"),ids(s),field);
        }
    }
    @Test void overlapDisagreementAndDuplicateAnchorStayUnknown() {
        Chunk h=chunk("h","row","The supplier shall retain "+"x".repeat(1380),0),t=chunk("t","row","different text",1200);
        Selection s=select(Arrays.asList(h,t),Collections.singletonList(h),10000);
        assertEquals("inconsistent_source_overlap",s.getBlockContinuations().get(0).getStatus());assertEquals(Collections.singletonList("h"),ids(s));
        t=chunk("t","row",h.getContent().substring(1200),1200);t.getParts().get(0).setAnchor("body/table[8]/row[2] @1200");
        s=select(Arrays.asList(h,t),Collections.singletonList(h),10000);
        assertEquals("ambiguous_source_block",s.getBlockContinuations().get(0).getStatus());assertEquals(Collections.singletonList("h"),ids(s));
    }
    @Test void gapMissingStartAndInvalidOffsetDoNotPretendTransportComplete() {
        Chunk h=chunk("h","row","The supplier shall retain.",0),t=chunk("t","row","after a gap",1200);
        Selection s=select(Arrays.asList(h,t),Collections.singletonList(h),10000);
        assertEquals("observed_range_gap",s.getBlockContinuations().get(0).getStatus());assertFalse(s.getBlockContinuations().get(0).isObservedRangesTransported());
        s=select(Collections.singletonList(t),Collections.singletonList(t),10000);assertEquals("missing_observed_block_start",s.getBlockContinuations().get(0).getStatus());
        t.getParts().get(0).setEndOffset(9999);s=select(Arrays.asList(h,t),Collections.singletonList(h),10000);
        assertEquals("invalid_source_mapping",s.getBlockContinuations().get(0).getStatus());
    }
    @Test void partBodyMismatchDuplicatePartAndMixedMetadataAreRejectedAsUnknown() {
        for(String fault:Arrays.asList("body","duplicate","version")) {
            String raw="The supplier shall retain "+"x".repeat(1380);
            Chunk h=chunk("h","row",raw,0),t=chunk("t","row",raw.substring(1200),1200);
            if(fault.equals("body"))t.setContent("changed");
            if(fault.equals("duplicate")){t.setParts(Arrays.asList(t.getParts().get(0),t.getParts().get(0)));t.setContent(t.getContent()+"\n"+t.getContent());}
            if(fault.equals("version"))t.setSegmentationVersion("different-v");
            Selection s=select(Arrays.asList(h,t),Collections.singletonList(h),10000);assertEquals(Collections.singletonList("h"),ids(s),fault);
            assertTrue(s.getBlockContinuations().get(0).getRequiredChunkIds().isEmpty());
        }
    }
    @Test void correctAstralSlicesUseUtf16WhileBrokenSurrogatesRemainUnknown() {
        String raw="The supplier shall retain 中文😀|空槽 "+"x".repeat(1380);
        Chunk h=chunk("h","row",raw.substring(0,1400),0),t=chunk("t","row",raw.substring(1200),1200);
        Selection s=select(Arrays.asList(h,t),Collections.singletonList(h),10000);assertEquals(raw.length(),s.getBlockContinuations().get(0).getObservedEndOffset());
        h=chunk("h","row","The supplier shall retain.\uD83D",0);t=chunk("t","row","\uDE00tail",h.getContent().length());
        s=select(Arrays.asList(h,t),Collections.singletonList(h),10000);assertEquals("invalid_source_mapping",s.getBlockContinuations().get(0).getStatus());
    }
    @Test void weakLocalWindowCannotImportAnotherRevisionOrRoleUsingTheSameHeading() {
        for(String field:Arrays.asList("hash","role")) {
            Chunk h=chunk("h","head","The supplier shall retain.",0),other=chunk("other","different","Except under another contract.",0);
            h.setClauseHeadingLocation("same-heading");other.setClauseHeadingLocation("same-heading");
            if(field.equals("hash"))other.setSourceHash("revision-two");else other.setRole("standard");
            assertEquals(Collections.singletonList("h"),ids(select(Arrays.asList(h,other),Collections.singletonList(h),10000)));
        }
    }
    @Test void duplicateIdsUnknownIdsAndChangedRankedPayloadFailBeforeSelection() {
        Chunk h=chunk("h","row","The supplier shall retain.",0);
        assertThrows(IllegalArgumentException.class,()->new VettingContextBuilder(Arrays.asList(h,h)));
        Chunk changed=chunk("h","row","Different wording.",0),unknown=chunk("missing","row",h.getContent(),0);
        VettingContextBuilder b=new VettingContextBuilder(Collections.singletonList(h));
        assertThrows(IllegalArgumentException.class,()->b.build("supplier",Collections.singletonList(changed),Collections.emptyMap(),10000));
        assertThrows(IllegalArgumentException.class,()->b.build("supplier",Collections.singletonList(unknown),Collections.emptyMap(),10000));
        assertThrows(IllegalArgumentException.class,()->b.build("supplier",Collections.singletonList(h),Collections.singletonMap("standard",Collections.singletonList(unknown)),10000));
    }

    private static Chunk chunk(String id,String block,String text,int offset) {
        Chunk c = new Chunk(); c.setId(id);c.setDocumentId("doc");c.setSourceHash("revision-one");
        c.setRole("tender");c.setFileKey("OTHER");c.setFileName("source.docx");c.setContent(text);
        Part p = new Part();p.setBlockId(block);p.setText(text);p.setStartOffset(offset);p.setEndOffset(offset+text.length());
        p.setAnchor("body/table[4]/row[9] @"+offset);c.setAnchor(p.getAnchor());c.setParts(Collections.singletonList(p));return c;
    }
    private static Selection select(List<Chunk> corpus,List<Chunk> ranked,int limit) {
        return new VettingContextBuilder(corpus).build("submission",ranked,Collections.emptyMap(),limit);
    }
    private static List<String> ids(Selection s) {return s.getChunks().stream().map(Chunk::getId).collect(Collectors.toList());}
}
