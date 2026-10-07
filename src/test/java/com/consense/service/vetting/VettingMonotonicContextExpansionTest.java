package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.SourceDocument;
import com.consense.document.DocumentBlock;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingContextBuilder.Selection;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingMonotonicContextExpansionTest {
    @Test void initialContinuationHasPriorityOverNew110CharacterRankedCore() {
        Fixture f=new Fixture(110,0);Selection first=f.first();VettingContextBudget.Result r=f.budget();
        assertTrue(r.isExpanded());assertEquals(200,r.getEffectiveBudgetChars());assertEquals(120,r.getSelection().getContentChars());
        assertEquals(Arrays.asList("a-head","a-tail"),ids(r.getSelection()));assertSame(f.aHead,r.getSelection().getChunks().get(0));
        assertEquals(Collections.singleton("a-tail"),r.getInitialMissingTargetIds());assertTrue(r.getExpandedCandidateInitialMissingTargetIds().isEmpty());
        assertTrue(r.getFinalMissingTargetIds().isEmpty());assertTrue(r.isInitialChunksPreserved());assertEquals(ids(first),Collections.singletonList("a-head"));
        assertTrue(r.getSelection().getDroppedIds().contains("b-head"));
    }

    @Test void ordinaryWindowRemainsGreedyButPreservedExpansionClosesFixedInitialGap() {
        Fixture f=new Fixture(110,0);Selection ordinary=f.builder().build("generic",f.ranked,Collections.emptyMap(),200);
        assertEquals(Arrays.asList("a-head","b-head"),ids(ordinary));assertEquals(190,ordinary.getContentChars());
        Selection expanded=f.builder().expandPreservingInitial("generic",f.ranked,Collections.emptyMap(),200,f.first());
        assertEquals(Arrays.asList("a-head","a-tail"),ids(expanded));assertEquals(120,expanded.getContentChars());
    }

    @Test void newOriginUnknownGapIsRetainedAfterFixedInitialGapImproves() {
        Fixture f=new Fixture(80,80);VettingContextBudget.Result r=f.budget();assertTrue(r.isExpanded());
        assertEquals(Arrays.asList("a-head","a-tail","b-head"),ids(r.getSelection()));
        assertTrue(r.getExpandedCandidateInitialMissingTargetIds().isEmpty());assertEquals(Collections.singleton("b-tail"),r.getNewlyDiscoveredMissingTargetIds());
        assertEquals(Collections.singleton("b-tail"),r.getFinalMissingTargetIds());
        VettingBlockContinuation.Trace t=r.getSelection().getBlockContinuations().stream().filter(x->"b-head".equals(x.getOriginId())).findFirst().get();
        assertTrue(t.isInitiallySubmitted());assertTrue(t.isOriginSubmitted());assertFalse(t.isObservedRangesTransported());assertEquals("blocked_budget",t.getAdmissionStatus());
    }

    @Test void initialOrderExceptionAndAstralUtf16PartsRemainExact() {
        Fixture f=new Fixture(110,0);String original="😀\n"+repeat('a',77);f.aHead.setContent(original);f.aHead.getParts().get(0).setText(original);
        Chunk exception=chunk("exception","A","except-block",20,0);exception.setContent("Except when archived");exception.getParts().get(0).setText(exception.getContent());exception.getParts().get(0).setEndOffset(exception.getContent().length());
        f.aHead.setClauseHeadingLocation("body/shared");f.aTail.setClauseHeadingLocation("body/shared");exception.setClauseHeadingLocation("body/shared");
        f.corpus=Arrays.asList(f.aHead,exception,f.aTail,f.bHead);Selection first=f.first();assertEquals(Arrays.asList("a-head","exception"),ids(first));
        Selection expanded=f.builder().expandPreservingInitial("generic",f.ranked,Collections.emptyMap(),200,first);assertEquals(Arrays.asList("a-head","exception","a-tail"),ids(expanded));
        assertSame(f.aHead,expanded.getChunks().get(0));assertSame(exception,expanded.getChunks().get(1));assertEquals(original,expanded.getChunks().get(0).getContent());
        assertEquals(0,expanded.getChunks().get(0).getParts().get(0).getStartOffset());assertEquals(80,expanded.getChunks().get(0).getParts().get(0).getEndOffset());
        assertEquals(140,expanded.getContentChars());
    }

    @Test void fixedLiteralParentContextComesBeforeNewRankedCoreAndStaysUnknownSubclause() {
        Chunk origin=clause("origin","XYZ1","The source shall refer to XYZ9(1).",80),t1=clause("t1","XYZ9","Definition begins.",20),t2=clause("t2","XYZ9","Definition ends.",20),other=clause("other","XYZ20","A separate rule shall remain.",110);t2.setClauseHeadingLocation(t1.getClauseHeadingLocation());
        List<Chunk> corpus=Arrays.asList(origin,t1,t2,other);VettingContextBuilder builder=new VettingContextBuilder(corpus);List<Chunk> ranked=Arrays.asList(origin,other);
        Selection first=builder.build("generic",ranked,Collections.emptyMap(),100),expanded=builder.expandPreservingInitial("generic",ranked,Collections.emptyMap(),200,first);
        assertEquals(Collections.singletonList("origin"),ids(first));assertEquals(Arrays.asList("origin","t1","t2"),ids(expanded));
        assertTrue(expanded.getReferences().get(0).isFullParentContextSelected());assertFalse(expanded.getReferences().get(0).isExactSubclauseVerified());
        assertEquals("parent_fallback",expanded.getReferences().get(0).getResolutionMode());assertEquals(120,expanded.getContentChars());
    }

    @Test void unrankedLiteralTargetCannotBecomeAnOutgoingSecondHopOrigin() {
        Chunk origin=clause("origin","XYZ1","The source shall refer to XYZ9.",80),target=clause("target","XYZ9","The source shall refer to XYZ10.",40),secondHop=clause("second","XYZ10","The further definition ends.",40);
        List<Chunk> corpus=Arrays.asList(origin,target,secondHop);VettingContextBuilder builder=new VettingContextBuilder(corpus);List<Chunk> ranked=Collections.singletonList(origin);
        Selection first=builder.build("generic",ranked,Collections.emptyMap(),100),expanded=builder.expandPreservingInitial("generic",ranked,Collections.emptyMap(),200,first);
        assertEquals(Arrays.asList("origin","target"),ids(expanded));assertFalse(ids(expanded).contains("second"));assertEquals(1,expanded.getReferences().size());assertEquals("origin",expanded.getReferences().get(0).getOriginId());
    }

    @Test void selectedClosureTargetIsNotEligibleIfItsRankedPairWasNotAdmitted() {
        Chunk origin=clause("origin","XYZ1","The source begins here.",80),target=clause("target","XYZ1","New Register (NR) shall comply with XYZ10.",70),peer=clause("peer","XYZ20","New Register (NR) shall retain its separate condition.",120),second=clause("second","XYZ10","A further definition shall remain.",40);
        target.getParts().get(0).setBlockId(origin.getParts().get(0).getBlockId());target.getParts().get(0).setAnchor(origin.getParts().get(0).getAnchor()+" @80");target.getParts().get(0).setStartOffset(80);target.getParts().get(0).setEndOffset(150);target.setAnchor(target.getParts().get(0).getAnchor());
        List<Chunk> corpus=Arrays.asList(origin,target,peer,second),ranked=Arrays.asList(origin,target,peer);VettingContextBuilder builder=new VettingContextBuilder(corpus);Selection first=builder.build("generic",ranked,Collections.emptyMap(),100),expanded=builder.expandPreservingInitial("generic",ranked,Collections.emptyMap(),200,first);
        assertEquals(Collections.singletonList("origin"),ids(first));assertTrue(ids(expanded).contains("target"));assertFalse(ids(expanded).contains("peer"));assertFalse(ids(expanded).contains("second"));
        VettingContextBuilder.ReferenceTrace t=expanded.getReferences().stream().filter(x->"target".equals(x.getOriginId())).findFirst().get();assertTrue(t.isOriginSubmitted());assertFalse(t.isInitiallySubmitted());assertEquals("origin_not_eligible_at_closure_start",t.getStatus());
    }

    @Test void noInitialLocatedGapMeansBudgetDoesNotAttemptExpansion() {
        Chunk only=chunk("only","A","whole",80,0);VettingContextBudget.Result r=VettingContextBudget.build(new VettingContextBuilder(Collections.singletonList(only)),"generic",Collections.singletonList(only),Collections.emptyMap(),100,200);assertFalse(r.isExpansionAttempted());assertFalse(r.isExpanded());assertEquals(100,r.getEffectiveBudgetChars());assertEquals(Collections.singletonList("only"),ids(r.getSelection()));
    }

    @Test void tooSmallCeilingOrMisstatedInitialCharsIsRejectedBeforeReservation() {
        Fixture f=new Fixture(110,0);Selection first=f.first();assertThrows(IllegalArgumentException.class,()->f.builder().expandPreservingInitial("generic",f.ranked,Collections.emptyMap(),79,first));
        first.setContentChars(1);assertThrows(IllegalArgumentException.class,()->f.builder().expandPreservingInitial("generic",f.ranked,Collections.emptyMap(),200,first));
    }

    @Test void foreignOrDuplicateInitialChunkCannotBeReserved() {
        Fixture f=new Fixture(110,0);Selection foreign=f.first();Chunk external=copy(f.aHead);external.setId("unknown-foreign-ID");foreign.setChunks(Collections.singletonList(external));assertThrows(IllegalArgumentException.class,()->f.builder().expandPreservingInitial("generic",f.ranked,Collections.emptyMap(),200,foreign));
        Selection duplicate=f.first();duplicate.setChunks(Arrays.asList(f.aHead,f.aHead));duplicate.setContentChars(160);assertThrows(IllegalArgumentException.class,()->f.builder().expandPreservingInitial("generic",f.ranked,Collections.emptyMap(),200,duplicate));
    }

    @Test void sameInitialIdCannotHideChangedBodySourceRoleVersionOrUtf16Position() {
        List<Consumer<Chunk>> changes=Arrays.asList(c->c.setContent(repeat('q',80)),c->c.setSourceHash("other"),c->c.setDocumentId("foreign"),c->c.setRole("standard"),c->c.setMetadataVersion("legacy"),c->c.setSourceQualityHash("other"),c->c.getParts().get(0).setStartOffset(1),c->c.getParts().get(0).setAnchor("other location"));
        for(Consumer<Chunk> change:changes){Fixture f=new Fixture(110,0);Selection initial=f.first();Chunk bad=copy(f.aHead);change.accept(bad);initial.setChunks(Collections.singletonList(bad));assertThrows(IllegalArgumentException.class,()->f.builder().expandPreservingInitial("generic",f.ranked,Collections.emptyMap(),200,initial));}
    }

    @Test void foreignRankedPayloadIsRejectedByExistingCanonicalInput() {
        Fixture f=new Fixture(110,0);Chunk bad=copy(f.bHead);bad.setSourceHash("foreign");assertThrows(IllegalArgumentException.class,()->f.builder().expandPreservingInitial("generic",Arrays.asList(f.aHead,bad),Collections.emptyMap(),200,f.first()));
    }

    @Test void initialCanonicalChunksCannotMixOneDocumentRevisionRoleOrQualityScope() {
        for(Consumer<Chunk> conflict:Arrays.<Consumer<Chunk>>asList(c->c.setSourceHash("changed"),c->c.setRole("standard"),c->c.setNativeTableMetadataVersion("legacy"),c->c.setSourceQualityHash("another-quality"))){Fixture f=new Fixture(80,0);Chunk mixed=copy(f.bHead);mixed.setDocumentId(f.aHead.getDocumentId());mixed.setSourceHash(f.aHead.getSourceHash());mixed.setSourceQualityHash(f.aHead.getSourceQualityHash());conflict.accept(mixed);List<Chunk> corpus=Arrays.asList(f.aHead,f.aTail,mixed);Selection initial=new Selection();initial.setChunks(Arrays.asList(f.aHead,mixed));initial.setContentChars(160);assertThrows(IllegalArgumentException.class,()->new VettingContextBuilder(corpus).expandPreservingInitial("generic",Collections.singletonList(f.aHead),Collections.emptyMap(),200,initial));}
    }

    @Test void newlyAdmittedCanonicalChunkCannotMixTheReservedDocumentRevision() {
        Fixture f=new Fixture(80,0);f.bHead.setDocumentId(f.aHead.getDocumentId());f.bHead.setSourceHash("different-same-document-revision");assertThrows(IllegalArgumentException.class,()->f.builder().expandPreservingInitial("generic",f.ranked,Collections.emptyMap(),200,f.first()));
    }

    @Test void wholeSelectedNativeEmptyPipeAstralCellsAndMetadataRemainExact() {
        DocumentBlock b=new DocumentBlock();b.setId("body:1:table-row:0");b.setLocation("body/1/table-row/0");b.setKind("table_row");b.setSource("docx");b.setCells(Arrays.asList("literal | cell","","😀"));b.setText(String.join(" | ",b.getCells()));SourceDocument d=new SourceDocument();d.setId(901L);d.setReviewRole("tender");d.setFileKey("OTHER");d.setFileName("unseen-native-table.docx");d.setParseStatus("PARSED");d.setOcrUsed(false);d.setParseCoverageJson("{\"totalPages\":0,\"parsedPages\":0,\"ocrPages\":0,\"complete\":true}");d.setTextContent(b.getText());d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(b)));Chunk nativeRow=VettingCorpus.chunks(Collections.singletonList(d)).get(0);Chunk other=chunk("other","other","whole",110,0);List<Chunk> corpus=Arrays.asList(nativeRow,other);VettingContextBuilder builder=new VettingContextBuilder(corpus);Selection initial=builder.build("generic",Collections.singletonList(nativeRow),Collections.emptyMap(),100),expanded=builder.expandPreservingInitial("generic",Arrays.asList(nativeRow,other),Collections.emptyMap(),200,initial);assertSame(nativeRow,expanded.getChunks().get(0));assertNotNull(nativeRow.getParts().get(0).getTable());assertEquals(b.getCells(),expanded.getChunks().get(0).getParts().get(0).getTable().getCells());assertEquals(nativeRow.getSourceHash(),expanded.getChunks().get(0).getSourceHash());assertEquals(nativeRow.getSourceQualityHash(),expanded.getChunks().get(0).getSourceQualityHash());assertEquals(b.getText().length(),expanded.getChunks().get(0).getParts().get(0).getEndOffset());assertEquals(1,JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(expanded.getChunks().get(0)))).get(0).path("nativeRows").size());
    }

    private static final class Fixture {
        final Chunk aHead=chunk("a-head","A","block-a",80,0),aTail=chunk("a-tail","A","block-a",40,80),bHead,bTail;
        List<Chunk> corpus,ranked;
        Fixture(int bLength,int tailLength){bHead=chunk("b-head","B","block-b",bLength,0);bTail=tailLength==0?null:chunk("b-tail","B","block-b",tailLength,bLength);corpus=new ArrayList<>(Arrays.asList(aHead,aTail,bHead));if(bTail!=null)corpus.add(bTail);ranked=Arrays.asList(aHead,bHead);}
        VettingContextBuilder builder(){return new VettingContextBuilder(corpus);}
        Selection first(){return builder().build("generic",ranked,Collections.emptyMap(),100);}
        VettingContextBudget.Result budget(){return VettingContextBudget.build(builder(),"generic",ranked,Collections.emptyMap(),100,200);}
    }
    private static Chunk chunk(String id,String doc,String block,int length,int start){Chunk c=new Chunk();c.setId(id);c.setDocumentId("doc-"+doc);c.setSourceHash("source-"+doc);c.setRole("tender");c.setFileKey("OTHER");c.setFileName("new-unknown-file.docx");c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);c.setSourceQualityHash("fixture-quality-"+doc);c.setContent(repeat('a',length));c.setAnchor("body/"+block+(start>0?" @"+start:""));Part p=new Part();p.setBlockId(block);p.setAnchor(c.getAnchor());p.setText(c.getContent());p.setStartOffset(start);p.setEndOffset(start+length);p.setExtractionSource("docx");c.setParts(Collections.singletonList(p));return c;}
    private static Chunk clause(String id,String clause,String content,int length){Chunk c=chunk(id,"reference-source",id,length,0);c.setFileKey("XYZ");c.setClauseId(clause);c.setClauseHeadingLocation("body/"+clause);StringBuilder s=new StringBuilder(content);while(s.length()<length)s.append(' ');c.setContent(s.toString());c.getParts().get(0).setText(c.getContent());c.getParts().get(0).setEndOffset(c.getContent().length());return c;}
    private static Chunk copy(Chunk c){return JsonUtils.read(JsonUtils.write(c),Chunk.class);}
    private static String repeat(char c,int n){char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
    private static List<String> ids(Selection s){return s.getChunks().stream().map(Chunk::getId).collect(Collectors.toList());}
}
