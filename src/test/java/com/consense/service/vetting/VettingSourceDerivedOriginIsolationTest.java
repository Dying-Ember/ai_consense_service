package com.consense.service.vetting;
import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingContextBuilder.Selection;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingSourceDerivedOriginIsolationTest {
    private String padded(String s,int n) {StringBuilder b=new StringBuilder(s);while(b.length()<n)b.append('x');assertEquals(n,b.length());return b.toString();}
    private Chunk c(String id,String doc,String clause,String text,int chars) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileName(doc+".docx");c.setRole("tender");c.setSourceHash(VettingCorpus.hash("raw:"+doc));c.setFileKey(clause==null?"OTHER":clause.substring(0,3));c.setClauseId(clause);c.setClauseHeadingLocation(clause==null?null:"body/"+id+"/heading");c.setAnchor("body/"+id+"/paragraph");c.setContent(padded(text,chars));
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARSED");q.setOcrQualityStatus("not_observed");q.setCoverageMetadataSha256(VettingCorpus.hash("synthetic-parser-declaration"));c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setText(c.getContent());p.setStartOffset(0);p.setEndOffset(c.getContent().length());p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private void join(Chunk head,Chunk tail) {
        Part a=head.getParts().get(0),b=tail.getParts().get(0);a.setBlockId("one-source-block");a.setAnchor("body/7/paragraph");head.setAnchor(a.getAnchor());b.setBlockId("one-source-block");b.setStartOffset(head.getContent().length());b.setEndOffset(head.getContent().length()+tail.getContent().length());b.setAnchor("body/7/paragraph @"+head.getContent().length());tail.setAnchor(b.getAnchor());
    }
    private List<String> ids(Selection s) {return s.getChunks().stream().map(Chunk::getId).collect(Collectors.toList());}
    private void sourcePreserved(Selection first,Selection expanded) {
        Map<String,Chunk> byid=expanded.getChunks().stream().collect(Collectors.toMap(Chunk::getId,x->x));for(Chunk original:first.getChunks())assertEquals(original,byid.get(original.getId()));
        Set<String> initial=new LinkedHashSet<>(ids(first));assertEquals(ids(first),expanded.getChunks().stream().map(Chunk::getId).filter(initial::contains).collect(Collectors.toList()));
    }
    private boolean eligible(Selection s,String id) {return s.getReferences().stream().anyMatch(t->id.equals(t.getOriginId())&&t.isInitiallySubmitted());}
    @Test void initialIncomingDerivedRankedTailNeverPromotesAfterZeroCostGroupAdmission() {
        Chunk seed=c("seed","seed-doc","AAA.1","A field record shall be submitted.",80),head=c("note-head","note-doc",null,"AAA.1; ",16),tail=c("ranked-tail","note-doc",null,"Alpha Beta Corporation (ABC) shall follow CCC.9.",100),heavy=c("heavy","heavy-doc","DDD.1","ABC shall observe an independent restriction.",1000),third=c("second-hop","third-doc","CCC.9","The independent ledger shall be provided.",60),missing=c("first-missing","other-note",null,"AAA.1 unless the field record is inactive.",500);
        join(head,tail);List<Chunk> corpus=Arrays.asList(seed,head,tail,heavy,third,missing),ranked=Arrays.asList(seed,tail,heavy);VettingContextBuilder b=new VettingContextBuilder(corpus);Selection first=b.build("field record",ranked,Collections.emptyMap(),300),expanded=b.expandPreservingInitial("field record",ranked,Collections.emptyMap(),2000,first);
        assertEquals(Arrays.asList("seed","note-head","ranked-tail"),ids(first));assertFalse(eligible(first,"ranked-tail"));assertFalse(eligible(expanded,"ranked-tail"));assertFalse(ids(expanded).contains("second-hop"));assertTrue(ids(expanded).containsAll(Arrays.asList("first-missing","heavy")));sourcePreserved(first,expanded);
        assertTrue(eligible(expanded,"seed")==eligible(first,"seed"));
    }
    @Test void initialContinuationDerivedRankedTailNeverPromotesAfterZeroCostGroupAdmission() {
        Chunk seed=c("seed","source","AAA.1","A field record shall follow EEE.1.",80),tail=c("ranked-tail","source","AAA.1","Alpha Beta Corporation (ABC) shall follow CCC.9.",100),heavy=c("heavy","heavy-doc","DDD.1","ABC shall observe an independent restriction.",1000),third=c("second-hop","third-doc","CCC.9","The independent ledger shall be provided.",60),missing=c("first-missing","missing-doc","EEE.1","A separate source requirement shall be observed.",500);
        tail.setClauseHeadingLocation(seed.getClauseHeadingLocation());join(seed,tail);List<Chunk> corpus=Arrays.asList(seed,tail,heavy,third,missing),ranked=Arrays.asList(seed,tail,heavy);VettingContextBuilder b=new VettingContextBuilder(corpus);Selection first=b.build("field record",ranked,Collections.emptyMap(),300);assertEquals(Arrays.asList("seed","ranked-tail"),ids(first));assertFalse(eligible(first,"ranked-tail"));
        VettingContextBudget.Result r=VettingContextBudget.build(b,"field record",ranked,Collections.emptyMap(),300,2000);assertTrue(r.isExpanded());assertFalse(eligible(r.getSelection(),"ranked-tail"));assertFalse(ids(r.getSelection()).contains("second-hop"));assertTrue(r.getSelection().getChunks().containsAll(first.getChunks()));assertTrue(ids(r.getSelection()).containsAll(Arrays.asList("first-missing","heavy")));sourcePreserved(first,r.getSelection());
    }
    @Test void newlyTransportedOutgoingTargetCannotBecomeANewRankedOutgoingOrigin() {
        Chunk seed=c("seed","seed-doc","AAA.1","Alpha Beta Corporation (ABC) shall use BBB.1.",80),peer=c("peer","peer-doc","DDD.1","ABC shall.",10),target=c("ranked-target","target-doc","BBB.1","Another register shall follow CCC.9.",80),third=c("second-hop","third-doc","CCC.9","A ledger shall.",20);List<Chunk> corpus=Arrays.asList(seed,peer,target,third),ranked=Arrays.asList(seed,peer,target);VettingContextBuilder b=new VettingContextBuilder(corpus);Selection first=b.build("register",ranked,Collections.emptyMap(),100);
        assertEquals(Arrays.asList("seed","peer"),ids(first));assertFalse(eligible(first,"ranked-target"));VettingContextBudget.Result r=VettingContextBudget.build(b,"register",ranked,Collections.emptyMap(),100,200);assertTrue(r.isExpanded());assertEquals(Arrays.asList("seed","peer","ranked-target"),ids(r.getSelection()));assertFalse(eligible(r.getSelection(),"ranked-target"));assertTrue(r.isInitialTargetRequestsPreserved());sourcePreserved(first,r.getSelection());
    }
}
