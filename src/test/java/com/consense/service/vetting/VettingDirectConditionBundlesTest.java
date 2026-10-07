package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.*;
import com.consense.service.vetting.VettingSourceRequestPacks.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit synthetic trusted observations; no retrieved/gold case or real token counts. */
class VettingDirectConditionBundlesTest {
    private Chunk chunk(String id,String doc,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileName(doc+".docx");c.setRole("tender");c.setSourceHash(VettingCorpus.hash(doc));c.setFileKey("OTHER");c.setContent(text);c.setAnchor("body/"+id+"/paragraph");
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARTIAL");q.setOcrQualityStatus("needs_review");q.setCoverageMetadataSha256(VettingCorpus.hash("synthetic provenance"));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setText(text);p.setEndOffset(text.length());p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private List<Chunk> corpus() {return Arrays.asList(chunk("origin-a","source-a","Registered sensor A."),chunk("origin-b","source-b","Registered sensor B."),chunk("incoming","incoming","The two registrations require inspection."),chunk("condition-a","condition-a","Subject to the recorded operating condition."),chunk("condition-b","condition-b","Subject to the recorded operating condition."),chunk("sibling","other-note","An independent incoming registration notice."),chunk("large-target","target","A long external source requirement."));}
    private Request request(int ordinal,String kind,List<String> origins,List<String> required,List<Chunk> chunks) {
        Request r=new Request();r.setId("request-"+ordinal);r.setOrdinal(ordinal);r.setKind(kind);r.setEligibleOriginIds(origins);r.setRequiredChunkIds(required);r.setContentChars(chunks.stream().filter(c->required.contains(c.getId())).mapToInt(c->c.getContent().length()).sum());return r;
    }
    private Result source(List<Chunk> chunks,VettingContextBuilder.Selection global,List<Request> requests) {
        Result s=new Result();s.setTopic("synthetic registrations");s.setOriginalRankedIds(Arrays.asList("origin-a","origin-b"));s.setGlobalSelectionHash(VettingCorpus.hash(JsonUtils.write(global)));s.setGlobalChunks(global.getChunks());s.setRequests(requests);s.setExtraPackCap(8);s.setPerPackChars(Integer.MAX_VALUE);for(Chunk c:chunks)s.getCanonicalPayloadHashes().put(c.getId(),VettingCorpus.hash(JsonUtils.write(c)));return s;
    }
    private VettingContextBuilder.Selection global(List<Chunk> chunks) {VettingContextBuilder.Selection s=new VettingContextBuilder.Selection();s.setChunks(Collections.singletonList(chunks.get(0)));s.setContentChars(chunks.get(0).getContent().length());return s;}
    private List<Request> requests(List<Chunk> c) {return Arrays.asList(request(1,"incoming_literal",Arrays.asList("origin-a","origin-b"),Arrays.asList("origin-a","origin-b","incoming"),c),request(2,"outgoing_literal",Collections.singletonList("origin-a"),Arrays.asList("origin-a","large-target"),c),request(3,"incoming_literal",Collections.singletonList("origin-b"),Arrays.asList("origin-b","sibling"),c),request(4,"observed_entity_qualifier",Collections.singletonList("origin-a"),Arrays.asList("origin-a","condition-a"),c),request(5,"observed_entity_qualifier",Collections.singletonList("origin-b"),Arrays.asList("origin-b","condition-b"),c));}
    private Set<String> ids(VettingInputBudget.Input in) {return new LinkedHashSet<>(JsonUtils.readMap(in.getMessages().get(1).getContent()).keySet());}
    private VettingTokenAwareRequestPacks.InputFactory factory() {return p->{Map<String,Object> body=new LinkedHashMap<>();for(Chunk c:p.getChunks())body.put(c.getId(),c);return VettingInputBudget.input("synthetic","mock","synthetic system "+p.getIndex(),JsonUtils.write(body),JsonUtils.mapper().createObjectNode(),10,VettingCorpus.hash(JsonUtils.write(p.getChunks())));};}
    private VettingInputBudget.Observation observation(VettingInputBudget.Input in,long tokens) {VettingInputBudget.Observation o=VettingInputBudgetTest.fixtureObservation(in);o.setInputTokens(tokens);o.setEffectiveContextTokens(100);return o;}
    @Test void observedOverComponentProducesDirectWholeConditionBundleWithoutAllSiblingRequests() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);List<Set<String>> seen=new ArrayList<>();Set<String> expected=new LinkedHashSet<>(Arrays.asList("origin-a","origin-b","incoming","condition-a","condition-b"));
        VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(source(c,g,requests(c)),g,factory(),in->{Set<String> members=ids(in);seen.add(members);if(members.size()==7)return observation(in,100);return null;});
        assertTrue(seen.contains(expected),"The original whole incoming request and both directly attached conditions must reach the complete Input factory");
        assertTrue(p.getSourcePlan().getExtraPacks().isEmpty());assertTrue(p.getSourcePlan().getRequests().stream().allMatch(r->"budget_unknown".equals(r.getStatus())));assertFalse(p.isSemanticScopeVerified());
    }
    @Test void aDirectBundleFitRetainsWholeRequestsAndDoesNotBorrowSameTextFromAnotherSource() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Set<String> expected=new LinkedHashSet<>(Arrays.asList("origin-a","origin-b","incoming","condition-a","condition-b"));
        VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(source(c,g,requests(c)),g,factory(),in->{Set<String> members=ids(in);if(members.size()==7)return observation(in,100);if(members.equals(expected))return observation(in,20);return null;});
        assertEquals(1,p.getSourcePlan().getExtraPacks().size());Pack pack=p.getSourcePlan().getExtraPacks().get(0);assertEquals(expected,new LinkedHashSet<>(pack.getChunks().stream().map(Chunk::getId).collect(Collectors.toList())));assertTrue(pack.getRequestIds().containsAll(Arrays.asList("request-1","request-4","request-5")));
        for(Chunk actual:pack.getChunks())assertEquals(JsonUtils.write(c.stream().filter(x->x.getId().equals(actual.getId())).findFirst().get()),JsonUtils.write(actual));
        assertFalse(pack.getChunks().stream().anyMatch(x->"sibling".equals(x.getId())||"large-target".equals(x.getId())));
    }
    @Test void unknownWholeComponentNeverPermitsLocalSubgroupFallback() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);List<Set<String>> seen=new ArrayList<>();VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(source(c,g,requests(c)),g,factory(),in->{seen.add(ids(in));return null;});assertEquals(2,seen.size());assertTrue(p.getSourcePlan().getExtraPacks().isEmpty());
    }
    @Test void unknownJointBudgetCannotPublishFittingIndividualFragments() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(source(c,g,requests(c)),g,factory(),in->{Set<String> members=ids(in);if(members.size()==7)return observation(in,100);if(members.size()<=3)return observation(in,20);return null;});assertFalse(p.getSourcePlan().getExtraPacks().stream().anyMatch(pack->pack.getRequestIds().contains("request-1")),"The unmeasured focal condition bundle cannot publish its separately fitting incoming fragment");assertEquals("budget_unknown",p.getSourcePlan().getRequests().get(0).getStatus());
    }
    @Test void laterUnknownBundleCannotEraseEarlierSharedConditionPacketMembership() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);List<Request> r=Arrays.asList(request(1,"incoming_literal",Collections.singletonList("origin-a"),Arrays.asList("origin-a","incoming"),c),request(2,"incoming_literal",Collections.singletonList("origin-a"),Arrays.asList("origin-a","sibling"),c),request(3,"observed_entity_qualifier",Collections.singletonList("origin-a"),Arrays.asList("origin-a","condition-a"),c));
        VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(source(c,g,r),g,factory(),in->{Set<String> m=ids(in);if(m.contains("incoming")&&m.contains("sibling"))return observation(in,100);if(m.equals(new LinkedHashSet<>(Arrays.asList("origin-a","incoming","condition-a"))))return observation(in,20);return null;});
        Request shared=p.getSourcePlan().getRequests().get(2);assertEquals("transported_extra_pack",shared.getStatus());assertEquals(1,shared.getPackIndex());assertTrue(shared.getMissingChunkIds().isEmpty());assertEquals("budget_unknown",p.getSourcePlan().getRequests().get(1).getStatus());assertTrue(p.getSourcePlan().getExtraPacks().get(0).getRequestIds().contains(shared.getId()));
    }
    @Test void originBridgeAndConditionEvidenceNeverPropagateAnotherOriginsSiblingOrCondition() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);List<Request> r=Arrays.asList(request(1,"incoming_literal",Collections.singletonList("origin-a"),Arrays.asList("origin-a","incoming"),c),request(2,"incoming_literal",Arrays.asList("origin-a","origin-b"),Arrays.asList("origin-a","origin-b","sibling"),c),request(3,"observed_entity_qualifier",Collections.singletonList("origin-a"),Arrays.asList("origin-a","condition-a"),c),request(4,"observed_entity_qualifier",Collections.singletonList("origin-b"),Arrays.asList("origin-b","condition-b"),c));
        Set<String> expected=new LinkedHashSet<>(Arrays.asList("origin-a","incoming","condition-a"));VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(source(c,g,r),g,factory(),in->{Set<String> m=ids(in);if(m.size()==6)return observation(in,100);return m.equals(expected)?observation(in,20):null;});
        assertEquals(1,p.getSourcePlan().getExtraPacks().size());assertEquals(expected,new LinkedHashSet<>(p.getSourcePlan().getExtraPacks().get(0).getChunks().stream().map(Chunk::getId).collect(Collectors.toList())));assertEquals(Collections.singletonList("origin-a"),p.getConditionBundles().get(0).getEligibleOriginIds());assertFalse(p.getConditionBundles().get(0).isConditionCanBecomeOrigin());
    }
    @Test void knownOverJointBundleCannotBeClaimedCoveredBySeparatelyFittingFragments() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(source(c,g,requests(c)),g,factory(),in->observation(in,ids(in).size()<=2?20:100));
        assertTrue(p.getSourcePlan().getExtraPacks().isEmpty());assertEquals("over_budget",p.getSourcePlan().getRequests().get(0).getStatus());assertTrue(p.getConditionBundles().stream().allMatch(b->"over_budget".equals(b.getOutcome())&&b.getSelectedPackIndex()==0));assertTrue(p.getConditionBundles().stream().allMatch(b->b.getRequiredChunkIds().containsAll(p.getSourcePlan().getRequests().get(Integer.parseInt(b.getFocalRequestId().substring(8))-1).getRequiredChunkIds())));
    }
    @Test void unresolvedObservedConditionRemainsVisibleAndBlocksJointCandidate() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);List<Request> r=new ArrayList<>(requests(c));Request missing=request(6,"observed_entity_qualifier",Collections.singletonList("origin-a"),Collections.singletonList("origin-a"),c);missing.setStatus("unknown");missing.setUnknownReason("synthetic_incomplete_condition_native_block");r.add(missing);
        VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(source(c,g,r),g,factory(),in->{if(ids(in).size()==7)return observation(in,100);return observation(in,20);});
        assertFalse(p.getSourcePlan().getExtraPacks().stream().anyMatch(pack->pack.getRequestIds().contains("request-1")));assertTrue(p.getConditionBundles().stream().anyMatch(b->"request-1".equals(b.getFocalRequestId())&&b.getConditionRequestIds().contains("request-6")&&"unknown_source_condition".equals(b.getOutcome())));assertEquals("synthetic_incomplete_condition_native_block",p.getOriginalRequestSourceUnknownReasons().get("request-6"));
    }
    @Test void capFailurePreservesPublishedSharedConditionAndSeparateBundleDisposition() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Result s=source(c,g,requests(c));s.setExtraPackCap(1);VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(s,g,factory(),in->observation(in,ids(in).size()>5?100:20));
        assertEquals(1,p.getSourcePlan().getExtraPacks().size());assertEquals("omitted_pack_cap",p.getSourcePlan().getRequests().get(1).getStatus());assertEquals("transported_extra_pack",p.getSourcePlan().getRequests().get(3).getStatus());assertEquals(1,p.getSourcePlan().getRequests().get(3).getPackIndex());assertTrue(p.getConditionBundles().stream().anyMatch(b->"request-2".equals(b.getFocalRequestId())&&"omitted_pack_cap".equals(b.getOutcome())));
    }
}
