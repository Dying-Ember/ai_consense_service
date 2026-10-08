package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VettingRetrievalSourceUnitsTest {
    private Chunk c(String id,String doc,String role,String clause,String block,String text){
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setSourceHash(VettingCorpus.hash(doc));c.setRole(role);c.setFileKey("AAA");c.setClauseId(clause);c.setClauseHeadingLocation(clause==null?null:"body/1");c.setContent(text);c.setAnchor(block.replace(':','/'));
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARSED");q.setOcrQualityStatus("not_observed");q.setCoverageMetadataSha256(VettingCorpus.hash("synthetic-source-provenance"));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        Part p=new Part();p.setText(text);p.setBlockId(block);p.setAnchor(c.getAnchor());p.setStartOffset(0);p.setEndOffset(text.length());p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private JsonNode response(Chunk seed,List<Chunk> members) {
        ObjectNode identity=JsonUtils.mapper().createObjectNode();JsonNode raw=JsonUtils.mapper().valueToTree(seed);for(String f:VettingRetrievalSourceUnits.FIELDS)identity.set(f,raw.path(f));
        List<Chunk> sorted=new ArrayList<>(members);sorted.sort(Comparator.comparing(Chunk::getId));List<Map<String,Object>> ranges=new ArrayList<>();
        sorted.stream().flatMap(c->c.getParts().stream()).sorted(Comparator.comparing(Part::getBlockId)).forEach(p->ranges.add(VettingReviewProbe.map("blockId",p.getBlockId(),"startUtf16",0,"endUtf16",p.getEndOffset(),"status","observed_canonical_ranges_zero_gap","sourceBlockComplete",null)));
        Map<String,Object> unit=VettingReviewProbe.map("unitId",VettingCorpus.hash("synthetic-unit-"+seed.getDocumentId()),"originIds",Collections.singletonList(seed.getId()),"kind","exact_clause_heading","status","complete_observed_unit","reason",null,"requiredMemberIds",sorted.stream().map(Chunk::getId).collect(Collectors.toList()),"members",sorted.stream().map(VettingRetrievalClient::normalizedPayload).collect(Collectors.toList()),"observedRanges",ranges,"sourceIdentity",identity,"clauseId",seed.getClauseId(),"headingLocation",seed.getClauseHeadingLocation(),"blockIds",sorted.stream().flatMap(c->c.getParts().stream()).map(Part::getBlockId).sorted().collect(Collectors.toList()),"derivedMembersCanBecomeOrigins",false,"memberScores",null,"originalRerankOrdinal",1,"seedScore",.9);
        Map<String,Object> out=VettingStrictHybridTest.response(seed);((Map<String,Object>)((List<?>)out.get("hits")).get(0)).put("score",.9);
        out.put("sourceUnits",VettingReviewProbe.map("policy",VettingRetrievalSourceUnits.POLICY,"scope","canonical_observed_structure_only","originalHitIds",Collections.singletonList(seed.getId()),"units",Collections.singletonList(unit),"derivedMembersCanBecomeOrigins",false));return JsonUtils.parse(JsonUtils.write(out));
    }
    private List<Chunk> read(JsonNode root,Chunk seed,List<Chunk> all){Map<String,Chunk> byId=new LinkedHashMap<>();all.forEach(c->byId.put(c.getId(),c));return VettingRetrievalSourceUnits.read(root,Collections.singletonList(seed),byId,seed.getRole(),true);}
    private VettingTokenAwareRequestPacks.InputFactory factory(){return p->VettingInputBudget.input("controlled","fixture-model","Original source inspection",VettingSourcePromptWire.write(VettingSourceMaterial.project(p.getChunks())),VettingOutputSchema.forChunkIds(p.getChunks().stream().map(Chunk::getId).collect(Collectors.toList())),100,VettingCorpus.hash(JsonUtils.write(p.getChunks())));}
    @Test void realClientListKeepsOnlyScoredSeedAndSourcePlanTokenCandidateKeepsWholeMembers(){
        Chunk head=c("a-head","terms","tender","AAA.1","body:1","AAA.1 The record shall be retained."),tail=c("a-tail","terms","tender","AAA.1","body:2","Unless dormant the record remains required; see CCC.9."),other=c("unranked-hop","other","tender","CCC.9","body:1","A separate archive.");List<Chunk> all=Arrays.asList(head,tail,other);
        HttpSupport http=mock(HttpSupport.class);ConsenseProperties props=VettingStrictHybridTest.strict();props.getVetting().setTopK(1);VettingStrictHybridTest.indexResponse(http,VettingStrictHybridTest.receipt(3));VettingStrictHybridTest.retrieveResponse(http,JsonUtils.readMap(JsonUtils.write(response(head,Arrays.asList(head,tail)))));
        VettingRetrievalClient client=new VettingRetrievalClient(http,props);client.index("p",all);List<Chunk> ranked=client.retrieve("p","retained record","tender",all);
        assertEquals(Collections.singletonList(head),ranked);assertEquals(1,ranked.size());
        VettingContextBuilder.Selection global=new VettingContextBuilder(all).build("record",ranked,Collections.emptyMap(),head.getContent().length());
        VettingSourceRequestPacks.Result plan=new VettingSourceRequestPacks(all).planForCompleteInputBudget("record",ranked,global,VettingRetrievalSourceUnits.attached(ranked));
        assertEquals(Collections.singletonList(head.getId()),plan.getOriginalRankedIds());assertEquals(Collections.singletonList(head.getId()),plan.getOriginalRetrievedIds());
        VettingSourceRequestPacks.Request unit=plan.getRequests().stream().filter(r->"retrieved_structural_unit".equals(r.getKind())).findFirst().get();assertEquals(Arrays.asList("a-head","a-tail"),unit.getRequiredChunkIds());
        List<VettingInputBudget.Input> seen=new ArrayList<>();VettingTokenAwareRequestPacks.Plan tokens=new VettingTokenAwareRequestPacks(all).repack(plan,global,factory(),in->{seen.add(in);VettingInputBudget.Observation o=VettingInputBudgetTest.fixtureObservation(in);o.setEffectiveContextTokens(100000);return o;});
        assertTrue(tokens.getSourcePlan().getExtraPacks().stream().anyMatch(p->p.getChunks().containsAll(Arrays.asList(head,tail))));assertTrue(seen.stream().anyMatch(in->in.getMessages().get(1).getContent().contains("Unless dormant")));
        assertTrue(tokens.getSourcePlan().getRequests().stream().noneMatch(r->r.getEligibleOriginIds().contains(tail.getId())));assertTrue(tokens.getSourcePlan().getExtraPacks().stream().flatMap(p->p.getChunks().stream()).noneMatch(c->other.getId().equals(c.getId())));
    }
    @Test void overAndUnknownCannotPublishWholeUnitFragment(){
        Chunk a=c("a","terms","tender","AAA.1","body:1","AAA.1 The register shall remain."),b=c("b","terms","tender","AAA.1","body:2","Unless inactive the register remains.");List<Chunk> all=Arrays.asList(a,b);List<Chunk> ranked=read(response(a,all),a,all);
        VettingContextBuilder.Selection g=new VettingContextBuilder(all).build("register",ranked,Collections.emptyMap(),a.getContent().length());VettingSourceRequestPacks.Result p=new VettingSourceRequestPacks(all).planForCompleteInputBudget("register",ranked,g,VettingRetrievalSourceUnits.attached(ranked));
        for(boolean unknown:Arrays.asList(false,true)){
            VettingTokenAwareRequestPacks.Plan t=new VettingTokenAwareRequestPacks(all).repack(p,g,factory(),in->{if(unknown)throw new IllegalStateException("controlled-unknown");VettingInputBudget.Observation o=VettingInputBudgetTest.fixtureObservation(in);o.setInputTokens(100000);o.setEffectiveContextTokens(1000);return o;});
            assertTrue(t.getSourcePlan().getExtraPacks().isEmpty());VettingSourceRequestPacks.Request r=t.getSourcePlan().getRequests().stream().filter(x->"retrieved_structural_unit".equals(x.getKind())).findFirst().get();assertEquals(unknown?"budget_unknown":"over_budget",r.getStatus());assertTrue(r.getMissingChunkIds().contains("b"));
        }
    }
    @Test void standardUnitTransportsAsContextWithoutCreatingTenderLiteralOrigin(){
        Chunk a=c("a","library","standard","AAA.1","body:1","AAA.1 The register."),b=c("b","library","standard","AAA.1","body:2","When dormant no entry is required.");List<Chunk> all=Arrays.asList(a,b),ranked=read(response(a,all),a,all);VettingContextBuilder.Selection g=new VettingContextBuilder.Selection();
        VettingSourceRequestPacks.Result p=new VettingSourceRequestPacks(all).planForCompleteInputBudget("register",Collections.emptyList(),g,VettingRetrievalSourceUnits.attached(ranked));assertTrue(p.getOriginalRankedIds().isEmpty());assertEquals(Collections.singletonList("a"),p.getOriginalRetrievedIds());
        VettingTokenAwareRequestPacks.Plan t=new VettingTokenAwareRequestPacks(all).repack(p,g,factory(),VettingInputBudgetTest::fixtureObservation);assertEquals(1,t.getSourcePlan().getExtraPacks().size());assertEquals(all,t.getSourcePlan().getExtraPacks().get(0).getChunks());assertTrue(t.getSourcePlan().getRequests().stream().allMatch(r->"retrieved_structural_unit".equals(r.getKind())));
    }
    @Test void omittedMemberChangedPayloadHeadingAndCrossSourceAreRejected(){
        Chunk a=c("a","terms","tender","AAA.1","body:1","AAA.1 Text."),b=c("b","terms","tender","AAA.1","body:2","A condition.");List<Chunk> all=Arrays.asList(a,b);
        for(String mutation:Arrays.asList("omit","payload","heading","source","origin","score")){
            JsonNode r=response(a,all);ObjectNode u=(ObjectNode)r.path("sourceUnits").path("units").get(0);
            if("omit".equals(mutation))((com.fasterxml.jackson.databind.node.ArrayNode)u.path("requiredMemberIds")).remove(1);
            if("payload".equals(mutation))((ObjectNode)u.path("members").get(1)).put("content","changed");
            if("heading".equals(mutation))u.put("headingLocation","body/other");
            if("source".equals(mutation))((ObjectNode)u.path("sourceIdentity")).put("sourceHash",VettingCorpus.hash("other"));
            if("origin".equals(mutation))((com.fasterxml.jackson.databind.node.ArrayNode)u.path("originIds")).set(0,JsonUtils.mapper().getNodeFactory().textNode("b"));
            if("score".equals(mutation))u.put("memberScores",.9);
            assertThrows(IllegalStateException.class,()->read(r,a,all),mutation);
        }
        JsonNode r=response(a,all);b.setClauseHeadingLocation("body/2");assertThrows(IllegalStateException.class,()->read(r,a,all));
    }
    @Test void advertisedEpisodeMissingFailsAndLegacyBareResponseRemainsBackwardCompatible(){
        Chunk a=c("a","terms","tender","AAA.1","body:1","AAA.1 Text.");JsonNode bare=JsonUtils.parse(JsonUtils.write(VettingStrictHybridTest.response(a)));Map<String,Chunk> map=Collections.singletonMap("a",a);
        assertThrows(IllegalStateException.class,()->VettingRetrievalSourceUnits.read(bare,Collections.singletonList(a),map,"tender",true));assertEquals(Collections.singletonList(a),VettingRetrievalSourceUnits.read(bare,Collections.singletonList(a),map,"tender",false));
    }
    @Test void forgedDifferentUnitIdsCannotLetTwoSeedsOccupyTheSameReliableUnit(){
        Chunk a=c("a","terms","tender","AAA.1","body:1","AAA.1 Text."),b=c("b","terms","tender","AAA.1","body:2","A condition.");List<Chunk> all=Arrays.asList(a,b);ObjectNode r=(ObjectNode)response(a,all);
        ((com.fasterxml.jackson.databind.node.ArrayNode)r.path("hits")).add(JsonUtils.mapper().valueToTree(VettingReviewProbe.map("id","b","score",.8,"payload",VettingRetrievalClient.normalizedPayload(b))));
        ObjectNode e=(ObjectNode)r.path("sourceUnits");((com.fasterxml.jackson.databind.node.ArrayNode)e.path("originalHitIds")).add("b");ObjectNode u=(ObjectNode)e.path("units").get(0).deepCopy();u.put("unitId",VettingCorpus.hash("forged-different-label"));u.put("originalRerankOrdinal",2);u.put("seedScore",.8);u.set("originIds",JsonUtils.mapper().valueToTree(Collections.singletonList("b")));((com.fasterxml.jackson.databind.node.ArrayNode)e.path("units")).add(u);
        Map<String,Chunk> map=new LinkedHashMap<>();all.forEach(c->map.put(c.getId(),c));assertThrows(IllegalStateException.class,()->VettingRetrievalSourceUnits.read(r,all,map,"tender",true));
    }
}
