package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingContextBuilder.ReferenceTrace;
import com.consense.service.vetting.VettingContextBuilder.Selection;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingReferenceClosureTest {
    @Test void sameParentFallbackRequiresAllThreeChunksAndNeverPretendsAnExactSubclause() {
        List<Chunk> corpus=parent();Selection s=build(corpus,Collections.singletonList(corpus.get(0)),20000);
        assertEquals(Arrays.asList("p0","p1","p2"),ids(s));ReferenceTrace ref=s.getReferences().get(0);
        assertEquals("full_parent_context_selected",ref.getStatus());assertTrue(ref.isFullParentContextSelected());
        assertFalse(ref.isExactSubclauseVerified());assertEquals("parent_fallback",ref.getResolutionMode());
        assertTrue(ref.getMissingTargetIds().isEmpty());assertEquals(12000,s.getContentChars());
        assertTrue(s.getUnresolvedComparisonIds().contains("p0"),"Parent transport completeness leaves exact subclause knowledge unknown.");
        assertEquals("unknown",s.getGroups().get(0).getQualifiersComplete());assertEquals("submitted_chunks_only",s.getCoverage());
    }
    @Test void tenThousandBudgetCannotClearSiblingReferenceUsingItsOwnRankedParentChunk() {
        List<Chunk> corpus=parent();Selection s=build(corpus,Collections.singletonList(corpus.get(0)),10000);ReferenceTrace ref=s.getReferences().get(0);
        assertEquals("blocked_budget",ref.getAdmissionStatus());assertEquals(8000,ref.getRequestedIncrementalChars());assertEquals(6000,ref.getBudgetRemainingAtAdmission());
        assertFalse(ref.isFullParentContextSelected());assertFalse(ref.isExactSubclauseVerified());assertTrue(ref.getMissingTargetIds().contains("p2"));
        assertTrue(s.getUnresolvedComparisonIds().contains("p0"));assertTrue(s.getContentChars()<=10000);
        for(Chunk c:s.getChunks())assertSame(c,corpus.stream().filter(x->x.getId().equals(c.getId())).findFirst().get());
    }
    @Test void exactSubclauseMetadataStillRequiresEveryContinuation() {
        Chunk origin=c("origin","doc","XYZ1","The item shall follow XYZ8(1).",100);
        Chunk first=c("first","doc","XYZ8(1)","First part of the requirement.",300);
        Chunk second=c("second","doc","XYZ8(1)","Remaining condition.",300);second.setClauseHeadingLocation(first.getClauseHeadingLocation());
        Selection s=build(Arrays.asList(origin,first,second),Collections.singletonList(origin),1000);
        assertEquals(Arrays.asList("origin","first","second"),ids(s));assertTrue(s.getReferences().get(0).isExactSubclauseVerified());
        assertEquals("exact_subclause_verified",s.getReferences().get(0).getStatus());
    }
    @Test void missingAndMultipleSourceRevisionsStayUnknown() {
        Chunk origin=c("origin","doc","XYZ1","The item shall follow XYZ8(1) and XYZ99(2).",100);
        Chunk old=c("old","same-document","XYZ8","Older definition.",200),newer=c("new","same-document","XYZ8","Newer definition.",200);newer.setSourceHash("revision-two");
        Selection s=build(Arrays.asList(origin,old,newer),Collections.singletonList(origin),2000);
        assertEquals(Collections.singletonList("origin"),ids(s));assertEquals(2,s.getReferences().size());
        assertEquals("ambiguous_source_or_edition",s.getReferences().get(0).getStatus());assertEquals("missing_target",s.getReferences().get(1).getStatus());
        assertTrue(s.getReferences().stream().allMatch(r->r.getRequiredTargetIds().isEmpty()));assertTrue(s.getUnresolvedComparisonIds().contains("origin"));
    }
    @Test void sameReferenceFromDifferentOriginsKeepsTheirOwnExplicitEditions() {
        Chunk first=c("a","tender-a","XYZ1","The item shall follow LIB clause 8, 2031 edition.",100);
        Chunk second=c("b","tender-b","XYZ2","The item shall follow LIB clause 8, 2037 edition.",100);
        Chunk old=c("old","old-source","LIB8","2031 edition. Definition.",150);old.setFileKey("LIB");old.setRole("standard");
        Chunk newer=c("new","new-source","LIB8","2037 edition. Definition.",150);newer.setFileKey("LIB");newer.setRole("standard");
        Selection s=build(Arrays.asList(first,second,old,newer),Arrays.asList(first,second),1000);
        assertEquals(2,s.getReferences().size());assertEquals(Collections.singletonList("old"),s.getReferences().get(0).getRequiredTargetIds());
        assertEquals(Collections.singletonList("new"),s.getReferences().get(1).getRequiredTargetIds());
    }
    @Test void queryHintCannotCreateSourceReferencesAndOneHopCannotFollowTargetText() {
        Chunk a=c("a","doc","XYZ1","The requirement shall follow XYZ8(1).",100);
        Chunk b=c("b","doc","XYZ8","The definition shall follow XYZ99(2).",200);
        Chunk next=c("next","doc","XYZ99","Further definition.",200);
        Selection s=new VettingContextBuilder(Arrays.asList(a,b,next)).build("XYZ99(2) DVD addendum",Collections.singletonList(a),Collections.emptyMap(),2000);
        assertEquals(Arrays.asList("a","b"),ids(s));assertEquals(1,s.getReferences().size());
        Chunk noRef=c("plain","doc","XYZ2","The register shall be retained.",100);
        assertTrue(new VettingContextBuilder(Arrays.asList(noRef,next)).build("XYZ99(2)",Collections.singletonList(noRef),Collections.emptyMap(),2000).getReferences().isEmpty());
    }
    @Test void droppedRankedOriginDoesNotExpandItsReferenceAndOrderIsStableWithoutTruncation() {
        Chunk a=c("a","doc","XYZ1","The obligation shall be followed.",700);
        Chunk dropped=c("drop","doc","XYZ2","The requirement shall follow XYZ8(1).",700);
        Chunk target=c("target","doc","XYZ8","The target definition.",100);
        List<Chunk> corpus=Arrays.asList(a,dropped,target);VettingContextBuilder builder=new VettingContextBuilder(corpus);
        Selection first=builder.build("definition",Arrays.asList(a,dropped),Collections.emptyMap(),800);
        Selection second=builder.build("definition",Arrays.asList(a,dropped),Collections.emptyMap(),800);
        assertEquals(Collections.singletonList("a"),ids(first));assertEquals(ids(first),ids(second));
        assertEquals("origin_not_eligible_at_closure_start",first.getReferences().get(0).getStatus());assertFalse(first.getReferences().get(0).isOriginSubmitted());
        assertFalse(first.getReferences().get(0).isInitiallySubmitted());
        assertEquals(700,first.getChunks().get(0).getContent().length());assertSame(a,first.getChunks().get(0));
    }
    @Test void neighbouringNumbersAndForeignOwnerMetadataCannotSatisfyAReference() {
        Chunk a=c("a","doc","XYZ1","The requirement shall follow XYZ8(1).",100);
        Chunk wrong=c("wrong","doc","XYZ8(10)","Unrelated numbered item.",100);
        Chunk foreign=c("foreign","foreign","XYZ8","Foreign document metadata.",100);foreign.setFileKey("LIB");
        Selection s=build(Arrays.asList(a,wrong,foreign),Collections.singletonList(a),1000);
        assertEquals(Collections.singletonList("a"),ids(s));assertEquals("missing_target",s.getReferences().get(0).getStatus());
    }
    @Test void duplicateParentOrExactSubclauseHeadingsAndMissingHeadingStayUnknown() {
        for(String clause:Arrays.asList("XYZ8","XYZ8(1)")) {
            Chunk a=c("a","doc","XYZ1","The requirement shall follow XYZ8(1).",100);
            Chunk first=c("first","doc",clause,"First possible definition.",150),second=c("second","doc",clause,"Second possible definition.",150);
            Selection s=build(Arrays.asList(a,first,second),Collections.singletonList(a),2000);
            assertEquals(Collections.singletonList("a"),ids(s));assertEquals("ambiguous_target_scope",s.getReferences().get(0).getStatus());
            assertFalse(s.getReferences().get(0).isExactSubclauseVerified());assertFalse(s.getReferences().get(0).isFullParentContextSelected());
            second.setClauseHeadingLocation(null);s=build(Arrays.asList(a,first,second),Collections.singletonList(a),2000);
            assertEquals("unknown_target_heading",s.getReferences().get(0).getStatus());assertEquals(Collections.singletonList("a"),ids(s));
        }
    }
    @Test void aRankedSeedImportedByParentClosureCannotStartASecondHop() {
        Chunk a=c("a","doc","XYZ1","The selected requirement shall follow XYZ8(1).",300);
        Chunk focus=c("focus","doc","XYZ8","The selected requirement is defined here.",200);
        Chunk imported=c("imported","doc","XYZ8","The Blue Inspector (BI) shall keep documents and follow XYZ99(1).",500);
        imported.setClauseHeadingLocation(focus.getClauseHeadingLocation());
        Chunk peer=c("peer","doc","XYZ40","The BI shall keep documents.",800);
        Chunk next=c("next","doc","XYZ99","The further definition.",100);
        Selection s=build(Arrays.asList(a,focus,imported,peer,next),Arrays.asList(a,focus,imported,peer),1100);
        assertEquals(Arrays.asList("a","focus","imported"),ids(s));
        ReferenceTrace ref=s.getReferences().stream().filter(r->r.getOriginId().equals("imported")).findFirst().get();
        assertFalse(ref.isInitiallySubmitted());assertTrue(ref.isOriginSubmitted());
        assertEquals("origin_not_eligible_at_closure_start",ref.getStatus());assertEquals("not_admitted",ref.getAdmissionStatus());
        assertFalse(ids(s).contains("next"));
    }
    @Test void explicitClosurePrecedesWeakLocalQualifierAndReferenceFillerCannotChain() {
        Chunk a=c("a","doc","XYZ1","The item shall follow XYZ8(1).",100);
        Chunk weak=c("weak","doc","XYZ1","Except when records are archived, retain the source.",1000);weak.setClauseHeadingLocation(a.getClauseHeadingLocation());
        Chunk b=c("b","doc","XYZ8","The definition shall follow LIB99(1).",600);
        Chunk cont=c("cont","doc","XYZ8","The remaining definition.",500);cont.setClauseHeadingLocation(b.getClauseHeadingLocation());
        Chunk next=c("next","standard","LIB99","The further requirement.",100);next.setFileKey("LIB");next.setRole("standard");
        Selection s=new VettingContextBuilder(Arrays.asList(a,weak,b,cont,next)).build("source",Collections.singletonList(a),Collections.singletonMap("standard",Collections.singletonList(next)),1300);
        assertEquals(Arrays.asList("a","b","cont"),ids(s));assertEquals(1200,s.getContentChars());assertEquals(1,s.getReferences().size());
        assertEquals("full_parent_context_selected",s.getReferences().get(0).getStatus());assertFalse(ids(s).contains("next"));
    }
    private static List<Chunk> parent() {
        Chunk a=c("p0","doc","XYZ22.304","The item shall follow XYZ22.304(6).",4000);
        Chunk b=c("p1","doc","XYZ22.304","The separate item (7) is defined here.",4000);
        Chunk d=c("p2","doc","XYZ22.304","The referenced item (6) is defined here.",4000);
        b.setClauseHeadingLocation(a.getClauseHeadingLocation());d.setClauseHeadingLocation(a.getClauseHeadingLocation());return Arrays.asList(a,b,d);
    }
    private static Selection build(List<Chunk> corpus,List<Chunk> ranked,int limit){return new VettingContextBuilder(corpus).build("source requirements",ranked,Collections.emptyMap(),limit);}
    private static List<String> ids(Selection s){return s.getChunks().stream().map(Chunk::getId).collect(Collectors.toList());}
    private static Chunk c(String id,String doc,String clause,String text,int length) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setSourceHash("revision-one");c.setRole("tender");c.setFileKey("XYZ");c.setFileName("source.docx");c.setClauseId(clause);c.setClauseHeadingLocation(id);c.setAnchor(id);
        StringBuilder b=new StringBuilder(text);while(b.length()<length)b.append(' ');c.setContent(b.toString());return c;
    }
}
