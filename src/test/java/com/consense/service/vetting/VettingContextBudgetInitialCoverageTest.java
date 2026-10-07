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

class VettingContextBudgetInitialCoverageTest {
    @Test void fixesInitialGapEvenWhenNewRankedOriginExposesAnotherGap() {
        Fixture f = new Fixture(80,80);
        VettingContextBudget.Result r = f.build();
        assertTrue(r.isExpanded()); assertTrue(r.isInitialChunksPreserved()); assertTrue(r.isInitialTargetRequestsPreserved());
        assertEquals(200,r.getEffectiveBudgetChars()); assertEquals(200,r.getSelection().getContentChars());
        assertEquals(Collections.singleton("a-tail"),r.getInitialMissingTargetIds());
        assertTrue(r.getExpandedCandidateInitialMissingTargetIds().isEmpty());
        assertEquals(Collections.singleton("b-tail"),r.getNewlyDiscoveredMissingTargetIds());
        assertEquals(Collections.singleton("b-tail"),r.getFinalMissingTargetIds());
        assertFalse(r.getSelection().getBlockContinuations().stream().filter(t->"b-head".equals(t.getOriginId())).findFirst().get().isObservedRangesTransported());
        assertEquals(Arrays.asList("a-head","a-tail","b-head"),ids(r.getSelection()));
    }

    @Test void newRankedCoreCanStillConsumeAllExpansionSoNoInitialImprovementIsRejected() throws Exception {
        // The defensive predicate must still reject an ordinary non-monotonic candidate with no initial improvement.
        Fixture f = new Fixture(110,0); VettingContextBudget.Result r = choose(f.first(),f.expanded());
        assertTrue(r.isExpansionAttempted()); assertFalse(r.isExpanded());
        assertEquals("expansion_did_not_improve_reference_coverage",r.getDecision());
        assertEquals(Collections.singleton("a-tail"),r.getExpandedCandidateInitialMissingTargetIds());
        assertEquals(Collections.singletonList("a-head"),ids(r.getSelection()));
        // A preserved head + its observed continuation would cost 120; this test records the existing greedy limit.
        assertEquals(120,f.aHead.getContent().length()+f.aTail.getContent().length());
    }

    @Test void preservesAstralNewlineAndEveryOriginalPartByteWhenAcceptingNewGap() {
        Fixture f = new Fixture(80,80); String original = "😀\n"+repeat('a',77);
        f.aHead.setContent(original); f.aHead.getParts().get(0).setText(original);
        VettingContextBudget.Result r = f.build(); assertTrue(r.isExpanded());
        Chunk selected = r.getSelection().getChunks().stream().filter(c->"a-head".equals(c.getId())).findFirst().get();
        assertSame(f.aHead,selected); assertEquals(original,selected.getContent()); assertEquals(80,original.length());
        assertEquals(0,selected.getParts().get(0).getStartOffset()); assertEquals(80,selected.getParts().get(0).getEndOffset());
        assertEquals("doc-A",selected.getDocumentId()); assertEquals("source-A",selected.getSourceHash());
        assertEquals("tender",selected.getRole()); assertEquals(VettingCorpus.NATIVE_TABLE_METADATA_VERSION,selected.getNativeTableMetadataVersion());
    }

    @Test void aSourceWithoutAnInitialLocatedGapDoesNotExpandToFillTheCeiling() {
        Fixture f = new Fixture(80,0); f.corpus = Collections.singletonList(f.aHead); f.ranked = f.corpus;
        VettingContextBudget.Result r = f.build(); assertFalse(r.isExpansionAttempted()); assertFalse(r.isExpanded());
        assertEquals(100,r.getEffectiveBudgetChars()); assertTrue(r.getNewlyDiscoveredMissingTargetIds().isEmpty());
    }

    @Test void cannotRemoveInitialOriginWhileClaimingItsTargetWasAdded() throws Exception {
        Fixture f = new Fixture(80,80); Selection first=f.first(),candidate=f.expanded();
        candidate.getChunks().removeIf(c->"a-head".equals(c.getId())); recount(candidate);
        VettingContextBudget.Result r=choose(first,candidate); assertFalse(r.isExpanded()); assertFalse(r.isInitialChunksPreserved());
        assertEquals("expansion_would_remove_initial_chunks",r.getDecision()); assertSame(first,r.getSelection());
    }

    @Test void sameIdCannotHideChangedSourceRevisionRoleVersionOrBody() throws Exception {
        List<Consumer<Chunk>> changes=Arrays.asList(c->c.setSourceHash("changed"),c->c.setDocumentId("another-doc"),c->c.setRole("standard"),
                c->c.setMetadataVersion("legacy"),c->c.setSegmentationVersion("legacy"),c->c.setNativeTableMetadataVersion("legacy"),
                c->c.setSourceQualityHash("changed"),c->c.setSourceQualityMetadataVersion("legacy"),c->{c.setContent(repeat('x',80));c.getParts().get(0).setText(c.getContent());},
                c->c.getParts().get(0).setStartOffset(1),c->c.getParts().get(0).setAnchor("changed location"));
        for(Consumer<Chunk> change:changes){Fixture f=new Fixture(80,80);Selection first=f.first(),candidate=f.expanded();Chunk changed=JsonUtils.read(JsonUtils.write(f.aHead),Chunk.class);change.accept(changed);candidate.getChunks().set(candidate.getChunks().indexOf(f.aHead),changed);recount(candidate);VettingContextBudget.Result r=choose(first,candidate);assertFalse(r.isExpanded());assertFalse(r.isInitialChunksPreserved());assertSame(first,r.getSelection());}
    }

    @Test void changingInitialRequiredTargetIsRejectedEvenIfOriginalTextRemains() throws Exception {
        Fixture f=new Fixture(80,80);Selection first=f.first(),candidate=f.expanded();
        VettingBlockContinuation.Trace t=candidate.getBlockContinuations().stream().filter(x->"a-head".equals(x.getOriginId())).findFirst().get();t.setRequiredChunkIds(Collections.singletonList("b-head"));
        VettingContextBudget.Result r=choose(first,candidate);assertFalse(r.isExpanded());assertTrue(r.isInitialChunksPreserved());assertFalse(r.isInitialTargetRequestsPreserved());
        assertEquals("expansion_would_change_initial_target_requests",r.getDecision());
    }

    @Test void deletingInitialClosureTraceCannotMasqueradeAsResolvedCoverage() throws Exception {
        Fixture f=new Fixture(80,80);Selection first=f.first(),candidate=f.expanded();candidate.getBlockContinuations().removeIf(x->"a-head".equals(x.getOriginId()));
        VettingContextBudget.Result r=choose(first,candidate);assertFalse(r.isExpanded());assertFalse(r.isInitialTargetRequestsPreserved());
    }

    @Test void changingInitialObservedRangeOrSourceIdentityIsRejected() throws Exception {
        for(boolean range:new boolean[]{false,true}){Fixture f=new Fixture(80,80);Selection first=f.first(),candidate=f.expanded();VettingBlockContinuation.Trace t=candidate.getBlockContinuations().stream().filter(x->"a-head".equals(x.getOriginId())).findFirst().get();if(range)t.setObservedRanges(Collections.singletonList(Arrays.asList(0,119)));else t.setSourceIdentity("another-revision");VettingContextBudget.Result r=choose(first,candidate);assertFalse(r.isExpanded());assertFalse(r.isInitialTargetRequestsPreserved());}
    }

    @Test void decreasingOnlyNewGapCannotPassWithoutImprovingInitialGap() throws Exception {
        Fixture f=new Fixture(110,80);Selection first=f.first(),candidate=f.expanded();
        candidate.getBlockContinuations().stream().filter(x->"b-head".equals(x.getOriginId())).forEach(x->{x.setInitiallySubmitted(false);x.setMissingChunkIds(Collections.emptyList());});
        VettingContextBudget.Result r=choose(first,candidate);assertFalse(r.isExpanded());assertEquals(Collections.singleton("a-tail"),r.getExpandedCandidateInitialMissingTargetIds());
    }

    @Test void missingFlagsCannotEraseTargetThatIsAbsentFromActualCandidateChunks() throws Exception {
        Fixture f=new Fixture(110,0);Selection first=f.first(),candidate=f.expanded();candidate.getBlockContinuations().forEach(t->t.setMissingChunkIds(Collections.emptyList()));
        VettingContextBudget.Result r=choose(first,candidate);assertFalse(r.isExpanded());assertEquals(Collections.singleton("a-tail"),r.getExpandedCandidateMissingTargetIds());
    }

    @Test void duplicateCandidateIdAndMisstatedBudgetCannotBeAccepted() throws Exception {
        Fixture f=new Fixture(80,80);Selection first=f.first(),duplicate=f.expanded();duplicate.getChunks().add(f.aHead);recount(duplicate);assertFalse(choose(first,duplicate).isExpanded());
        Selection misstated=f.expanded();misstated.setContentChars(1);VettingContextBudget.Result r=choose(first,misstated);assertFalse(r.isExpanded());assertEquals("expansion_exceeds_or_misstates_budget",r.getDecision());
    }

    @Test void knownReferenceIdentityCannotBeReplacedByAnotherReference() throws Exception {
        List<Chunk> corpus=VettingContextBudgetTest.parent(80);Selection first=new VettingContextBuilder(corpus).build("generic",Collections.singletonList(corpus.get(0)),Collections.emptyMap(),100),candidate=new VettingContextBuilder(corpus).build("generic",Collections.singletonList(corpus.get(0)),Collections.emptyMap(),300);
        candidate.getReferences().get(0).setTargetSourceIdentity("another-target-source");VettingContextBudget.Result r=choose(first,candidate,300);assertFalse(r.isExpanded());assertFalse(r.isInitialTargetRequestsPreserved());
    }

    private static final class Fixture {
        final Chunk aHead=chunk("a-head","A","block-a",80,0),aTail=chunk("a-tail","A","block-a",40,80),bHead,bTail;
        List<Chunk> corpus,ranked;
        Fixture(int bLength,int tailLength){bHead=chunk("b-head","B","block-b",bLength,0);bTail=tailLength==0?null:chunk("b-tail","B","block-b",tailLength,bLength);corpus=new ArrayList<>(Arrays.asList(aHead,aTail,bHead));if(bTail!=null)corpus.add(bTail);ranked=Arrays.asList(aHead,bHead);}
        Selection first(){return new VettingContextBuilder(corpus).build("generic",ranked,Collections.emptyMap(),100);}
        Selection expanded(){return new VettingContextBuilder(corpus).build("generic",ranked,Collections.emptyMap(),200);}
        VettingContextBudget.Result build(){return VettingContextBudget.build(new VettingContextBuilder(corpus),"generic",ranked,Collections.emptyMap(),100,200);}
    }
    private static Chunk chunk(String id,String doc,String block,int length,int start){Chunk c=new Chunk();c.setId(id);c.setDocumentId("doc-"+doc);c.setSourceHash("source-"+doc);c.setRole("tender");c.setFileKey("OTHER");c.setFileName("unknown-new-file.docx");c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);c.setSourceQualityHash("synthetic-quality-"+doc);c.setContent(repeat(doc.equals("A")?'a':'z',length));String anchor="body/"+block+(start>0?" @"+start:"");c.setAnchor(anchor);Part p=new Part();p.setBlockId(block);p.setAnchor(anchor);p.setText(c.getContent());p.setStartOffset(start);p.setEndOffset(start+length);p.setExtractionSource("docx");c.setParts(Collections.singletonList(p));return c;}
    @SuppressWarnings("unchecked") private static VettingContextBudget.Result choose(Selection first,Selection candidate,int ceiling)throws Exception{Method m=VettingContextBudget.class.getDeclaredMethod("missingLocatedTargets",Selection.class);m.setAccessible(true);VettingContextBudget.Result r=new VettingContextBudget.Result();r.setSelection(first);r.setInitialBudgetChars(100);r.setEffectiveBudgetChars(100);r.setExpansionCeilingChars(ceiling);r.setExpansionAttempted(true);r.setInitialMissingTargetIds(new LinkedHashSet<>((Set<String>)m.invoke(null,first)));r.setFinalMissingTargetIds(new LinkedHashSet<>(r.getInitialMissingTargetIds()));return VettingContextBudget.evaluateExpansion(r,first,candidate,ceiling);}
    private static VettingContextBudget.Result choose(Selection first,Selection candidate)throws Exception{return choose(first,candidate,200);}
    private static void recount(Selection s){s.setContentChars(s.getChunks().stream().mapToInt(c->c.getContent().length()).sum());}
    private static String repeat(char c,int n){char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
    private static List<String> ids(Selection s){return s.getChunks().stream().map(Chunk::getId).collect(Collectors.toList());}
}
