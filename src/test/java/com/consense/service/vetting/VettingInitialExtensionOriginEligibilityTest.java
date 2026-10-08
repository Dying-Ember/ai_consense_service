package com.consense.service.vetting;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingContextBuilder.Selection;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingInitialExtensionOriginEligibilityTest {
    private Chunk chunk(String id,String doc,String text,int chars) {
        StringBuilder b=new StringBuilder(text);while(b.length()<chars)b.append(' ');assertEquals(chars,b.length());
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setSourceHash(VettingCorpus.hash("synthetic-source:"+doc));c.setRole("tender");c.setFileKey("OTHER");c.setFileName("unseen-generic-"+doc+".txt");c.setClauseHeadingLocation("body/"+id+"/paragraph");c.setAnchor(c.getClauseHeadingLocation());c.setContent(b.toString());
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARSED");q.setOcrQualityStatus("not_observed");q.setCoverageMetadataSha256(VettingCorpus.hash("synthetic-source-quality:"+doc));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setText(c.getContent());p.setStartOffset(0);p.setEndOffset(chars);p.setExtractionSource("synthetic");c.setParts(Collections.singletonList(p));return c;
    }
    private List<String> ids(Selection s){return s.getChunks().stream().map(Chunk::getId).collect(Collectors.toList());}
    @Test void allThreeGenuineCoreRelationsKeepOneHopSourceClosure() {
        for(String relation:Arrays.asList("single_seed","shared_entity","explicit_reference")) {
            boolean shared="shared_entity".equals(relation),explicit="explicit_reference".equals(relation);
            Chunk seed=chunk("seed","doc-a",shared?"Log Packet (LP) shall contain a serial record. See BBB.1.":explicit?"See AAA.1 and BBB.1.":"See BBB.1.",80),peer=chunk("peer","doc-b",shared?"LP shall contain a serial record.":"Archive shall preserve serial records.",80),target=chunk("target","doc-c","See CCC.2.",40),third=chunk("third","doc-d","Archive shall preserve serial records.",40);
            peer.setFileKey("AAA");peer.setClauseId("AAA.1");target.setFileKey("BBB");target.setClauseId("BBB.1");third.setFileKey("CCC");third.setClauseId("CCC.2");
            List<Chunk> ranked="single_seed".equals(relation)?Collections.singletonList(seed):Arrays.asList(seed,peer);int initialLimit=ranked.size()*80;VettingContextBuilder b=new VettingContextBuilder(Arrays.asList(seed,peer,target,third));
            Selection first=b.build("serial record archive",ranked,Collections.emptyMap(),initialLimit);
            assertTrue(first.getGroups().stream().anyMatch(t->relation.equals(t.getRelation())&&"selected".equals(t.getStatus())),relation);
            assertTrue(first.getReferences().stream().anyMatch(t->seed.getId().equals(t.getOriginId())&&t.isInitiallySubmitted()),relation);
            assertFalse(ids(first).contains("target"),relation);
            Selection expanded=b.expandPreservingInitial("serial record archive",ranked,Collections.emptyMap(),initialLimit+80,first);
            assertTrue(ids(expanded).contains("target"),relation);assertFalse(ids(expanded).contains("third"),relation);
            assertTrue(expanded.getReferences().stream().anyMatch(t->seed.getId().equals(t.getOriginId())&&t.isInitiallySubmitted()),relation);
            assertFalse(expanded.getReferences().stream().anyMatch(t->target.getId().equals(t.getOriginId())),relation);
            for(Chunk c:first.getChunks())assertSame(c,expanded.getChunks().stream().filter(x->c.getId().equals(x.getId())).findFirst().get());
            assertTrue(expanded.getContentChars()<=initialLimit+80);assertEquals(expanded.getContentChars(),expanded.getChunks().stream().mapToInt(c->c.getContent().length()).sum());
        }
    }
    @Test void initialWeakExtensionCannotAuthorizeAnOriginallyDroppedRankedCore() {
        Chunk seed=chunk("ranked-seed","doc-a","Log Packet (LP) shall contain a serial record. See AAA.1.",80),heavy=chunk("ranked-heavy","doc-b","LP shall contain a serial record.",400),condition=chunk("condition","doc-c","LP shall contain a serial record unless the sensor is disabled.",80),target=chunk("outgoing-target","doc-d","Archive shall preserve serial records.",40);target.setFileKey("AAA");target.setClauseId("AAA.1");
        List<Chunk> corpus=Arrays.asList(seed,heavy,condition,target),ranked=Arrays.asList(seed,heavy);VettingContextBuilder b=new VettingContextBuilder(corpus);
        Selection first=b.build("serial record sensor log",ranked,Collections.emptyMap(),200);
        assertEquals(Arrays.asList("ranked-seed","condition"),ids(first));assertEquals(160,first.getContentChars());
        assertTrue(first.getGroups().stream().anyMatch(t->"shared_entity".equals(t.getRelation())&&"dropped_budget".equals(t.getStatus())&&t.getCoreIds().contains(seed.getId())));
        assertTrue(first.getGroups().stream().anyMatch(t->"shared_entity_extension".equals(t.getRelation())&&"selected".equals(t.getStatus())&&t.getCoreIds().contains(seed.getId())));
        assertFalse(first.getReferences().stream().anyMatch(t->seed.getId().equals(t.getOriginId())&&t.isInitiallySubmitted()));
        Selection expanded=b.expandPreservingInitial("serial record sensor log",ranked,Collections.emptyMap(),800,first);
        assertEquals(Arrays.asList("ranked-seed","condition","ranked-heavy"),ids(expanded));assertEquals(560,expanded.getContentChars());
        assertFalse(expanded.getReferences().stream().anyMatch(t->seed.getId().equals(t.getOriginId())&&t.isInitiallySubmitted()));
        for(Chunk c:first.getChunks())assertSame(c,expanded.getChunks().stream().filter(x->c.getId().equals(x.getId())).findFirst().get());
        assertSame(heavy,expanded.getChunks().get(2));assertTrue(expanded.getContentChars()<=800);
        assertEquals(560,expanded.getChunks().stream().mapToInt(c->c.getContent().length()).sum());
    }
}
