package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingContextBuilder.Selection;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingIncomingContextBudgetTest {
    private static String padded(String prefix,int length) {StringBuilder s=new StringBuilder(prefix);while(s.length()<length)s.append('x');assertEquals(length,s.length());return s.toString();}
    private Chunk chunk(String id,String doc,String clause,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileName(doc+".docx");c.setSourceHash(VettingCorpus.hash("raw:"+doc));c.setRole("tender");
        c.setFileKey(clause==null?"OTHER":clause.substring(0,3));c.setClauseId(clause);c.setClauseHeadingLocation(clause==null?null:"body/1/paragraph");c.setAnchor("body/"+id+"/paragraph");c.setContent(text);
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARSED");q.setOcrQualityStatus("not_observed");q.setCoverageMetadataSha256(VettingCorpus.hash("synthetic-parser-declaration"));
        c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setText(text);p.setStartOffset(0);p.setEndOffset(text.length());p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private Chunk seed() {return chunk("seed","seed-doc","AAA.1",padded("The Contractor shall submit a field record. ",80));}
    private Chunk note() {return chunk("note","note-doc",null,padded("AAA.1 unless recording is inactive. ",40));}
    private Chunk noteTail(Chunk note) {
        Chunk tail=chunk("note-tail","note-doc",null,padded("Remaining source observation. ",40));
        Part a=note.getParts().get(0),b=tail.getParts().get(0);a.setBlockId("one-note-block");a.setAnchor("body/7/paragraph");note.setAnchor(a.getAnchor());
        b.setBlockId("one-note-block");b.setStartOffset(40);b.setEndOffset(80);b.setAnchor("body/7/paragraph @40");tail.setAnchor(b.getAnchor());return tail;
    }
    private List<String> ids(Selection s) {return s.getChunks().stream().map(Chunk::getId).collect(Collectors.toList());}
    private VettingContextBuilder builder(Chunk... chunks) {return new VettingContextBuilder(Arrays.asList(chunks));}
    private Selection first(VettingContextBuilder b,Chunk seed) {return b.build("field record",Collections.singletonList(seed),Collections.emptyMap(),100);}
    private Selection candidate(VettingContextBuilder b,Chunk seed,Selection first) {return b.expandPreservingInitial("field record",Collections.singletonList(seed),Collections.emptyMap(),200,first);}
    private VettingContextBudget.Result choose(Selection first,Selection candidate) {
        VettingContextBudget.Result r=new VettingContextBudget.Result();r.setSelection(first);r.setInitialBudgetChars(100);r.setEffectiveBudgetChars(100);r.setExpansionCeilingChars(200);r.setExpansionAttempted(true);r.setInitialMissingTargetIds(new LinkedHashSet<>(Arrays.asList("note")));r.setFinalMissingTargetIds(new LinkedHashSet<>(r.getInitialMissingTargetIds()));
        return VettingContextBudget.evaluateExpansion(r,first,candidate,200);
    }
    @Test void incomingOnlyLocatedGapTriggersOneExpansionWithoutAnyOutgoingReference() {
        Chunk seed=seed(),note=note();VettingContextBudget.Result r=VettingContextBudget.build(builder(seed,note),"field record",Collections.singletonList(seed),Collections.emptyMap(),100,200);
        assertTrue(r.isExpansionAttempted());assertTrue(r.isExpanded());assertEquals(Arrays.asList("seed","note"),ids(r.getSelection()));assertEquals(120,r.getSelection().getContentChars());
        assertEquals(Collections.singleton("note"),r.getInitialMissingTargetIds());assertTrue(r.getFinalMissingTargetIds().isEmpty());assertTrue(r.isInitialTargetRequestsPreserved());
        assertEquals("located_incoming_literal_context_added",r.getDecision());assertTrue(r.getSelection().getReferences().isEmpty());assertEquals("unknown",r.getSelection().getIncomingReferences().get(0).getApplicability());
    }
    @Test void preservedIncomingClosurePrecedesNewRankedCore() {
        Chunk seed=seed(),note=note(),core=chunk("core","core-doc","CCC.1",padded("A register shall contain serial codes. ",110));
        VettingContextBudget.Result r=VettingContextBudget.build(builder(seed,note,core),"field record",Arrays.asList(seed,core),Collections.emptyMap(),100,200);
        assertTrue(r.isExpanded());assertEquals(Arrays.asList("seed","note"),ids(r.getSelection()));assertTrue(r.getSelection().getDroppedIds().contains("core"));assertTrue(r.getFinalMissingTargetIds().isEmpty());
    }
    @Test void newIncomingOriginGapRemainsSeparateAfterInitialGapIsClosed() {
        Chunk seed=seed(),note=note(),next=chunk("next","next-doc","BBB.1",padded("A measuring register shall contain serial codes. ",60)),newNote=chunk("new-note","new-note-doc",null,padded("BBB.1 unless measuring is inactive. ",90));
        VettingContextBudget.Result r=VettingContextBudget.build(builder(seed,note,next,newNote),"field record",Arrays.asList(seed,next),Collections.emptyMap(),100,200);
        assertTrue(r.isExpanded());assertEquals(Arrays.asList("seed","note","next"),ids(r.getSelection()));assertTrue(r.getExpandedCandidateInitialMissingTargetIds().isEmpty());
        assertEquals(Collections.singleton("new-note"),r.getNewlyDiscoveredMissingTargetIds());assertEquals(Collections.singleton("new-note"),r.getFinalMissingTargetIds());assertEquals(180,r.getSelection().getContentChars());
    }
    @Test void deletedInitialIncomingRequestCannotBeAcceptedAsClosed() {
        Chunk seed=seed(),note=note();VettingContextBuilder b=builder(seed,note);Selection f=first(b,seed),c=candidate(b,seed,f);c.getIncomingReferences().clear();VettingContextBudget.Result r=choose(f,c);
        assertFalse(r.isExpanded());assertFalse(r.isInitialTargetRequestsPreserved());assertEquals("expansion_would_change_initial_target_requests",r.getDecision());assertSame(f,r.getSelection());
    }
    @Test void sourceDirectionEligibleOriginsTargetsAndRangesArePartOfInitialIncomingIdentity() {
        List<Consumer<VettingIncomingLiteralContext.Trace>> changes=Arrays.asList(t->t.setDirection("outgoing"),t->t.setIncomingSourceIdentity("other-source"),t->t.setIncomingSourceAnchor("body/changed"),t->t.setTargetSourceIdentity("other-target"),t->t.setTargetHeadingLocation("body/changed"),t->t.setInitialOriginIds(Collections.singletonList("alien")),t->t.setRequiredChunkIds(Collections.singletonList("alien")),t->t.setResolvedTargetFamilyIds(Collections.singletonList("alien")),t->t.getBlockObservations().get(0).setObservedRanges(Collections.singletonList(Arrays.asList(0,39))),t->t.getBlockObservations().get(0).setSourceIdentity("another-range-source"));
        for(Consumer<VettingIncomingLiteralContext.Trace> change:changes) {Chunk seed=seed(),note=note(),tail=noteTail(note);VettingContextBuilder b=builder(seed,note,tail);Selection f=first(b,seed),c=candidate(b,seed,f);change.accept(c.getIncomingReferences().get(0));VettingContextBudget.Result r=choose(f,c);assertFalse(r.isExpanded());assertFalse(r.isInitialTargetRequestsPreserved());assertSame(f,r.getSelection());}
    }
    @Test void missingIncomingUsesRequiredIdsAndActualSubmittedBodyInsteadOfSummaryBooleans() throws Exception {
        Chunk seed=seed(),note=note();Selection f=first(builder(seed,note),seed);VettingIncomingLiteralContext.Trace t=f.getIncomingReferences().get(0);
        t.setInitiallyConnected(false);t.setObservedRangesTransported(true);t.setIncomingSourceSubmitted(true);t.setMissingChunkIds(Collections.emptyList());t.setSubmittedChunkIds(Collections.singletonList("note"));
        Method m=VettingContextBudget.class.getDeclaredMethod("missingLocatedTargets",Selection.class);m.setAccessible(true);
        assertEquals(Collections.singleton("note"),m.invoke(null,f));
    }
    @Test void incomingNoteOtherLiteralNeverBecomesAnOutgoingOrReverseSecondHop() {
        Chunk seed=seed(),note=chunk("note","note-doc",null,padded("AAA.1 and CCC.9 unless inactive. ",40)),third=chunk("third","third-doc","CCC.9","A ledger shall contain serial codes."),secondNote=chunk("second-note","second-note-doc",null,"CCC.9 unless the ledger is inactive.");
        VettingContextBudget.Result r=VettingContextBudget.build(builder(seed,note,third,secondNote),"field record",Collections.singletonList(seed),Collections.emptyMap(),100,200);
        assertTrue(r.isExpanded());assertEquals(Arrays.asList("seed","note"),ids(r.getSelection()));assertTrue(r.getSelection().getReferences().stream().noneMatch(t->"note".equals(t.getOriginId())));assertEquals(1,r.getSelection().getIncomingReferences().size());
    }
    @Test void unchangedIncomingGapAtCeilingRemainsMissingAndDoesNotClaimExpansion() {
        Chunk seed=seed(),note=chunk("note","note-doc",null,padded("AAA.1 unless recording is inactive. ",140));VettingContextBudget.Result r=VettingContextBudget.build(builder(seed,note),"field record",Collections.singletonList(seed),Collections.emptyMap(),100,200);
        assertTrue(r.isExpansionAttempted());assertFalse(r.isExpanded());assertEquals(Collections.singleton("note"),r.getFinalMissingTargetIds());assertEquals(Collections.singletonList("seed"),ids(r.getSelection()));assertTrue(r.isInitialTargetRequestsPreserved());
    }
    @Test void unknownEmptyDeclaredIncomingObservationCannotDisappearDuringOtherImprovement() {
        Chunk seed=seed(),note=note();VettingContextBuilder b=builder(seed,note);Selection f=first(b,seed),c=candidate(b,seed,f);
        f.getIncomingReferences().get(0).setRequiredChunkIds(Collections.emptyList());f.getIncomingReferences().get(0).setInitiallyConnected(false);
        c.getIncomingReferences().clear();VettingContextBudget.Result r=choose(f,c);assertFalse(r.isExpanded());assertFalse(r.isInitialTargetRequestsPreserved());
    }
    @Test void rankedButInitiallyUnadmittedIncomingContinuationCannotBecomeAnOutgoingOrigin() {
        Chunk seed=seed(),head=chunk("note","note-doc",null,padded("AAA.1; ",16)),tail=chunk("note-tail","note-doc",null,padded("See CCC.9 unless the ledger is inactive. ",55)),third=chunk("third","third-doc","CCC.9",padded("A ledger shall contain serial codes. ",40));
        Part a=head.getParts().get(0),b=tail.getParts().get(0);a.setBlockId("one-note-block");a.setAnchor("body/7/paragraph");head.setAnchor(a.getAnchor());
        b.setBlockId("one-note-block");b.setStartOffset(16);b.setEndOffset(71);b.setAnchor("body/7/paragraph @16");tail.setAnchor(b.getAnchor());
        VettingContextBudget.Result r=VettingContextBudget.build(builder(seed,head,tail,third),"field record",Arrays.asList(seed,tail),Collections.emptyMap(),100,200);
        assertTrue(r.isExpanded());assertEquals(Arrays.asList("seed","note","note-tail"),ids(r.getSelection()));
        assertTrue(r.getSelection().getReferences().stream().filter(t->"note-tail".equals(t.getOriginId())).noneMatch(VettingContextBuilder.ReferenceTrace::isInitiallySubmitted));
    }
}
