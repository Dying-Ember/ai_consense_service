package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingSourceRequestConditionPacksTest {
    private Chunk c(String id,String doc,String clause,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileName(doc+".docx");c.setRole("tender");c.setSourceHash(VettingCorpus.hash(doc));
        c.setFileKey(clause.substring(0,3));c.setClauseId(clause);c.setClauseHeadingLocation("heading/"+clause);c.setContent(text);c.setAnchor("body/"+id+"/paragraph");
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARSED");q.setOcrQualityStatus("not_observed");q.setCoverageMetadataSha256(VettingCorpus.hash("fixture-declaration"));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setStartOffset(0);p.setEndOffset(text.length());p.setText(text);p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private List<Chunk> corpus() {return Arrays.asList(
        c("seed","alpha","AAA.1","Log Packet (LP) shall contain a serial sensor record."),
        c("peer","beta","BBB.1","LP shall contain a serial sensor record."),
        c("condition","gamma","CCC.1","LP shall contain a serial sensor record unless the sensor is disabled."));}
    private String topic(){return "serial sensor record";}
    private List<Chunk> rank(List<Chunk> c){return Arrays.asList(c.get(0),c.get(1));}
    private VettingContextBuilder.Selection global(List<Chunk> c){return new VettingContextBuilder(c).build(topic(),rank(c),Collections.emptyMap(),c.get(0).getContent().length()+c.get(1).getContent().length());}
    private VettingSourceRequestPacks.Result plan(List<Chunk> c,VettingContextBuilder.Selection s,int limit,int cap){return new VettingSourceRequestPacks(c).plan(topic(),rank(c),s,limit,cap);}
    private List<VettingSourceRequestPacks.Request> conditions(VettingSourceRequestPacks.Result r){return r.getRequests().stream().filter(x->"observed_entity_qualifier".equals(x.getKind())).collect(Collectors.toList());}
    private Set<String> ids(List<Chunk> c){return c.stream().map(Chunk::getId).collect(Collectors.toCollection(LinkedHashSet::new));}
    private void assertDroppedCondition(VettingContextBuilder.Selection s){assertTrue(s.getGroups().stream().anyMatch(g->"observed_entity_qualifier_terms".equals(g.getRelationStrength())&&"dropped_budget".equals(g.getStatus())));}

    @Test void droppedObservedConditionPairBecomesAtomicRequestWithoutLiteral() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection s=global(c);assertDroppedCondition(s);String before=JsonUtils.write(s);
        VettingSourceRequestPacks.Result r=plan(c,s,500,8);assertFalse(conditions(r).isEmpty());
        for(VettingSourceRequestPacks.Request q:conditions(r)){assertEquals("transported_extra_pack",q.getStatus());assertEquals("unknown",q.getQualifiersComplete());assertFalse(q.isSemanticScopeVerified());assertEquals(2,q.getRequiredChunkIds().size());assertFalse(q.getEligibleOriginIds().contains("condition"));assertTrue(r.getExtraPacks().stream().anyMatch(p->ids(p.getChunks()).containsAll(q.getRequiredChunkIds())));}
        assertEquals(before,JsonUtils.write(s));assertEquals(JsonUtils.write(s.getChunks()),JsonUtils.write(r.getGlobalChunks()));assertEquals(1,JsonUtils.readMap(JsonUtils.write(r)).get("conditionCandidateObservations"));
    }
    @Test void bothPairMembersNativeBlockClosuresAreWholeAndWeakNeighboursAreExcluded() {
        List<Chunk> c=new ArrayList<>(corpus());Chunk condition=c.get(2),tail=c("tail","gamma","CCC.1","The serial sensor exception continues with an original condition.");
        Part head=condition.getParts().get(0),part=tail.getParts().get(0);head.setBlockId("original-row");part.setBlockId("original-row");head.setAnchor("body/7/table-row/1");condition.setAnchor(head.getAnchor());part.setStartOffset(head.getEndOffset());part.setEndOffset(head.getEndOffset()+tail.getContent().length());part.setAnchor(head.getAnchor()+" @"+head.getEndOffset());tail.setAnchor(part.getAnchor());c.add(tail);
        Chunk weak=c("weak","alpha","AAA.2","A neighbouring serial record paragraph is soft context.");c.add(weak);VettingContextBuilder.Selection s=global(c);s.getGroups().stream().filter(g->"observed_entity_qualifier_terms".equals(g.getRelationStrength())).forEach(g->g.setContextIds(Collections.singletonList("weak")));
        VettingSourceRequestPacks.Result r=plan(c,s,1000,8);assertFalse(conditions(r).isEmpty());for(VettingSourceRequestPacks.Request q:conditions(r)){assertTrue(q.getRequiredChunkIds().containsAll(Arrays.asList("condition","tail")));assertFalse(q.getRequiredChunkIds().contains("weak"));}
    }
    @Test void oversizedOrCappedConditionRequestsKeepExactMissingIds() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection s=global(c);VettingSourceRequestPacks.Result over=plan(c,s,c.get(2).getContent().length(),8),cap=plan(c,s,500,0);
        assertFalse(conditions(over).isEmpty());assertTrue(conditions(over).stream().allMatch(q->"oversized".equals(q.getStatus())&&q.getMissingChunkIds().contains("condition")));
        assertTrue(conditions(cap).stream().allMatch(q->"omitted_pack_cap".equals(q.getStatus())&&q.getMissingChunkIds().contains("condition")));assertTrue(over.getExtraPacks().isEmpty());assertTrue(cap.getExtraPacks().isEmpty());
    }
    @Test void conditionLiteralCannotCreateSecondHopOrNewEligibleOrigin() {
        List<Chunk> c=new ArrayList<>(corpus());c.get(2).setContent(c.get(2).getContent()+" See DDD.9.");Part p=c.get(2).getParts().get(0);p.setText(c.get(2).getContent());p.setEndOffset(p.getText().length());c.add(c("second-hop","delta","DDD.9","The unrelated archive shall contain a backup."));
        VettingSourceRequestPacks.Result r=plan(c,global(c),1000,8);assertFalse(conditions(r).isEmpty());assertTrue(r.getExtraPacks().stream().noneMatch(x->ids(x.getChunks()).contains("second-hop")));assertTrue(r.getRequests().stream().noneMatch(x->x.getEligibleOriginIds().contains("condition")));
    }
    @Test void fabricatedPairOrWrongRelationCannotBorrowAConditionObservation() {
        List<Chunk> c=new ArrayList<>(corpus());c.add(c("unrelated","omega","EEE.8","An unrelated archive shall contain a backup unless inactive."));VettingContextBuilder.Selection s=global(c);
        s.getGroups().stream().filter(g->"observed_entity_qualifier_terms".equals(g.getRelationStrength())).forEach(g->g.setCoreIds(Arrays.asList("seed","unrelated")));VettingSourceRequestPacks.Result r=plan(c,s,1000,8);
        assertFalse(conditions(r).isEmpty());assertTrue(conditions(r).stream().allMatch(q->"unknown".equals(q.getStatus())&&"condition_pair_not_observed_by_current_builder".equals(q.getUnknownReason())));assertTrue(r.getExtraPacks().isEmpty());
        s=global(c);s.getGroups().stream().filter(g->"observed_entity_qualifier_terms".equals(g.getRelationStrength())).forEach(g->g.setRelation("shared_entity"));assertTrue(conditions(plan(c,s,1000,8)).isEmpty());
    }
    @Test void sourceCollisionAndUnknownQualityRefuseWholeConditionTransport() {
        List<Chunk> c=new ArrayList<>(corpus());Chunk collision=c("collision","gamma","CCC.2","LP shall contain a serial sensor record unless inactive.");collision.setSourceHash(VettingCorpus.hash("foreign version"));c.add(collision);VettingSourceRequestPacks.Result r=plan(c,global(c),1000,8);assertFalse(conditions(r).isEmpty());assertTrue(conditions(r).stream().allMatch(q->"unknown".equals(q.getStatus())));assertTrue(r.getExtraPacks().isEmpty());
        c=corpus();c.get(2).setSourceQualityHash(VettingCorpus.hash("forged quality"));r=plan(c,global(c),1000,8);assertFalse(conditions(r).isEmpty());assertTrue(conditions(r).stream().allMatch(q->"unknown".equals(q.getStatus())));
    }
    @Test void fullGlobalConditionPairNeedsNoAdditionalTransportAndStaysUnknown() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection s=new VettingContextBuilder(c).build(topic(),rank(c),Collections.emptyMap(),1000);VettingSourceRequestPacks.Result r=plan(c,s,1000,8);
        assertFalse(conditions(r).isEmpty());assertTrue(conditions(r).stream().allMatch(q->"already_global".equals(q.getStatus())));assertTrue(r.getExtraPacks().isEmpty());assertEquals("unknown",r.getQualifiersComplete());
    }
    @Test void stableSingleCandidateSnapshotDoesNotMultiplyWithConditionPairs() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection s=global(c);VettingSourceRequestPacks p=new VettingSourceRequestPacks(c);VettingSourceRequestPacks.Result a=p.plan(topic(),rank(c),s,500,8),b=p.plan(topic(),rank(c),s,500,8);
        assertFalse(conditions(a).isEmpty());assertEquals(1,JsonUtils.readMap(JsonUtils.write(a)).get("conditionCandidateObservations"));assertEquals(JsonUtils.write(a),JsonUtils.write(b));assertEquals(0,a.getAdditionalModelCalls());assertEquals(0,a.getAdditionalRetrievalCalls());
    }
}
