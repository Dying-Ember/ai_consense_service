package com.consense.service.vetting;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingContextBuilder.Selection;
import com.consense.common.JsonUtils;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingObservedQualifierPriorityTest {
    private Chunk chunk(String id,String document,String text,int chars,String heading) {
        StringBuilder body=new StringBuilder(text);while(body.length()<chars)body.append(' ');assertEquals(chars,body.length());
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(document);c.setSourceHash(VettingCorpus.hash("synthetic-source:"+document));c.setRole("tender");c.setFileKey("OTHER");c.setFileName("unseen-generic-"+document+".txt");c.setClauseHeadingLocation(heading);c.setAnchor("body/"+id+"/paragraph");c.setContent(body.toString());
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARSED");q.setOcrQualityStatus("not_observed");q.setCoverageMetadataSha256(VettingCorpus.hash("synthetic-source-quality:"+document));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setText(c.getContent());p.setStartOffset(0);p.setEndOffset(chars);p.setExtractionSource("synthetic");c.setParts(Collections.singletonList(p));return c;
    }
    private List<Chunk> fixture() {
        return Arrays.asList(chunk("ranked-a","doc-a","Log Packet (LP) shall contain a serial record.",160,"body/1/paragraph"),chunk("weak-neighbour-a","doc-a","Background material describes cabinet paint.",160,"body/1/paragraph"),chunk("ranked-b","doc-b","LP shall contain a serial record.",160,"body/2/paragraph"),chunk("weak-neighbour-b","doc-b","Background material describes label colour.",160,"body/2/paragraph"),chunk("condition","doc-c","LP shall contain a serial record unless the sensor is disabled.",180,"body/3/paragraph"));
    }
    private Selection build(List<Chunk> c,int chars) {return new VettingContextBuilder(c).build("serial record sensor log",Arrays.asList(c.get(0),c.get(2)),Collections.emptyMap(),chars);}
    private List<String> ids(Selection s) {return s.getChunks().stream().map(Chunk::getId).collect(Collectors.toList());}
    private void verifySource(List<Chunk> original,Selection s) {Map<String,Chunk> byid=original.stream().collect(Collectors.toMap(Chunk::getId,x->x));for(Chunk c:s.getChunks())assertSame(byid.get(c.getId()),c);assertEquals(s.getContentChars(),s.getChunks().stream().mapToInt(c->c.getContent().length()).sum());}
    @Test void exactGenericFixtureTransportsWholeObservedQualifierBeforeWeakNeighbours() {
        List<Chunk> c=fixture();Selection s=build(c,750);assertEquals(Arrays.asList("ranked-a","ranked-b","condition","weak-neighbour-a"),ids(s));assertEquals(660,s.getContentChars());verifySource(c,s);
        VettingContextBuilder.GroupTrace e=s.getGroups().stream().filter(g->"observed_entity_qualifier_terms".equals(g.getRelationStrength())&&g.getCoreIds().contains("ranked-a")).findFirst().get();assertEquals("selected",e.getStatus());assertEquals(340,e.getIncrementalChars());assertTrue(e.isFullySubmitted());assertEquals("unknown",e.getQualifiersComplete());
        assertTrue(s.getReferences().isEmpty());assertTrue(s.getIncomingReferences().isEmpty());assertTrue(s.getBlockContinuations().isEmpty());
    }
    @Test void insufficientBudgetRejectsWholeQualifierPackageAndKeepsOriginalCores() {
        List<Chunk> c=fixture();Selection s=build(c,600);assertEquals(Arrays.asList("ranked-a","ranked-b"),ids(s));assertEquals(320,s.getContentChars());assertTrue(s.getGroups().stream().filter(g->"observed_entity_qualifier_terms".equals(g.getRelationStrength())).allMatch(g->"dropped_budget".equals(g.getStatus())));assertTrue(s.getDroppedIds().contains("condition"));verifySource(c,s);
    }
    @Test void qualifierContextLiteralDoesNotCreateASecondHopOrigin() {
        List<Chunk> c=new ArrayList<>(fixture());Chunk condition=chunk("condition","doc-c","LP shall contain a serial record unless the sensor is disabled. See AAA.9.",180,"body/3/paragraph"),third=chunk("third","doc-d","The ledger shall be inspected.",60,"body/9/paragraph");third.setFileKey("AAA");third.setClauseId("AAA.9");c.set(4,condition);c.add(third);
        Selection s=build(c,750);assertTrue(ids(s).contains("condition"));assertFalse(ids(s).contains("third"));assertTrue(s.getReferences().stream().noneMatch(t->"condition".equals(t.getOriginId())));assertEquals(Arrays.asList("ranked-a","ranked-b"),ids(s).subList(0,2));verifySource(c,s);
    }
    @Test void otherWeakEntityExtensionsRemainAfterWeakNeighbourFill() {
        Chunk a=chunk("ranked-a","doc-a","Log Packet (LP) shall contain a serial record.",160,"body/1/paragraph"),neighbour=chunk("weak","doc-a","Background material describes cabinet paint.",160,"body/1/paragraph"),other=chunk("other","doc-b","LP shall contain a serial record.",160,"body/2/paragraph");Selection s=new VettingContextBuilder(Arrays.asList(a,neighbour,other)).build("serial record",Collections.singletonList(a),Collections.emptyMap(),400);
        assertEquals(Arrays.asList("ranked-a","weak"),ids(s));VettingContextBuilder.GroupTrace e=s.getGroups().stream().filter(g->"shared_entity_extension".equals(g.getRelation())).findFirst().get();assertNotEquals("observed_entity_qualifier_terms",e.getRelationStrength());assertEquals("dropped_budget",e.getStatus());assertEquals(160,e.getIncrementalChars());assertEquals(320,s.getContentChars());
    }
    @Test void rankedSourcePayloadStillRequiresCanonicalIdentity() {
        List<Chunk> c=fixture();Chunk foreign=JsonUtils.read(JsonUtils.write(c.get(0)),Chunk.class);foreign.setSourceHash(VettingCorpus.hash("replacement-source"));assertThrows(IllegalArgumentException.class,()->new VettingContextBuilder(c).build("serial record",Arrays.asList(foreign,c.get(2)),Collections.emptyMap(),750));
    }
}
