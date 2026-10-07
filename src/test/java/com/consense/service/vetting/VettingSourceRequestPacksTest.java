package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingSourceRequestPacksTest {
    private Chunk chunk(String id,String doc,String clause,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileName(doc+".docx");c.setSourceHash(VettingCorpus.hash("raw:"+doc));c.setRole("tender");
        c.setFileKey(clause==null?"OTHER":clause.substring(0,3));c.setClauseId(clause);c.setClauseHeadingLocation(clause==null?null:"body/heading/"+clause);c.setContent(text);c.setAnchor("body/"+id+"/paragraph");
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARSED");q.setOcrQualityStatus("not_observed");q.setCoverageMetadataSha256(VettingCorpus.hash("test-parser-declaration"));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setStartOffset(0);p.setEndOffset(text.length());p.setText(text);p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private Chunk seed() {return chunk("seed","source","AAA.1","The field record shall be retained.");}
    private Chunk note(String id,String doc,String suffix) {return chunk(id,doc,null,"This note qualifies AAA.1; the field record is required unless inactive. "+suffix);}
    private VettingContextBuilder.Selection global(List<Chunk> c,List<Chunk> rank,int limit) {return new VettingContextBuilder(c).build("field record",rank,Collections.emptyMap(),limit);}
    private VettingSourceRequestPacks.Result plan(List<Chunk> c,List<Chunk> rank,VettingContextBuilder.Selection s,int limit,int cap) {return new VettingSourceRequestPacks(c).plan("field record",rank,s,limit,cap);}
    private Set<String> ids(List<Chunk> c){return c.stream().map(Chunk::getId).collect(Collectors.toCollection(LinkedHashSet::new));}
    private List<VettingSourceRequestPacks.Request> kind(VettingSourceRequestPacks.Result r,String kind){return r.getRequests().stream().filter(t->kind.equals(t.getKind())).collect(Collectors.toList());}

    @Test void alreadyAdmittedCoreMissingIncomingCreatesCompleteIndependentPacket() {
        Chunk seed=seed(),note=note("note","mail","Original terms remain source bound.");List<Chunk> c=Arrays.asList(seed,note);
        VettingContextBuilder.Selection s=global(c,Arrays.asList(seed),seed.getContent().length());String before=JsonUtils.write(s);
        assertEquals(Collections.singleton("seed"),ids(s.getChunks()));assertFalse(s.getIncomingReferences().get(0).isObservedRangesTransported());
        VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),s,500,8);
        assertEquals(1,r.getExtraPacks().size());assertEquals(new LinkedHashSet<>(Arrays.asList("seed","note")),ids(r.getExtraPacks().get(0).getChunks()));
        assertEquals("transported_extra_pack",kind(r,"incoming_literal").get(0).getStatus());assertEquals(before,JsonUtils.write(s));assertEquals(JsonUtils.write(s.getChunks()),JsonUtils.write(r.getGlobalChunks()));
        assertFalse(r.isSemanticScopeVerified());assertFalse(r.isTokenEnvelopeMeasured());assertEquals("unknown",kind(r,"incoming_literal").get(0).getApplicability());
    }
    @Test void aPriorLargeSourceDoesNotConsumeAnIndependentRequestsWholeContext() {
        Chunk seed=seed(),a=note("a","first",String.join("",Collections.nCopies(11,"Long source context. "))),b=note("b","second","Second source.");List<Chunk> c=Arrays.asList(seed,a,b);
        VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),global(c,Arrays.asList(seed),seed.getContent().length()),seed.getContent().length()+a.getContent().length(),8);
        assertEquals(2,r.getExtraPacks().size());assertTrue(ids(r.getExtraPacks().get(0).getChunks()).contains("a"));assertTrue(ids(r.getExtraPacks().get(1).getChunks()).containsAll(Arrays.asList("seed","b")));
        assertTrue(kind(r,"incoming_literal").stream().allMatch(t->"transported_extra_pack".equals(t.getStatus())));
    }
    @Test void indivisibleOriginalBlockCannotBePartiallyTransportedToFit() {
        Chunk seed=seed();List<Chunk> split=splitNote();List<Chunk> c=new ArrayList<>();c.add(seed);c.addAll(split);
        int limit=seed.getContent().length()+split.get(0).getContent().length();VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),global(c,Arrays.asList(seed),seed.getContent().length()),limit,8);
        assertTrue(r.getExtraPacks().isEmpty());assertTrue(kind(r,"incoming_literal").stream().allMatch(t->"oversized".equals(t.getStatus())));
        assertTrue(kind(r,"incoming_literal").stream().allMatch(t->t.getMissingChunkIds().containsAll(Arrays.asList("note","tail"))));
    }
    @Test void cappedPacketsKeepOmittedRequestsAndActualMissingIds() {
        Chunk seed=seed(),a=note("a","first","A"),b=note("b","second","B");List<Chunk> c=Arrays.asList(seed,a,b);
        VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),global(c,Arrays.asList(seed),seed.getContent().length()),seed.getContent().length()+a.getContent().length(),1);
        assertEquals(1,r.getExtraPacks().size());assertEquals("omitted_pack_cap",kind(r,"incoming_literal").get(1).getStatus());assertEquals(Collections.singletonList("b"),kind(r,"incoming_literal").get(1).getMissingChunkIds());
    }
    @Test void twoSplitEdgesCoLocateOneCompleteObservedBlockWithOriginalUtf16() {
        Chunk seed=seed();List<Chunk> split=splitNote();List<Chunk> c=new ArrayList<>();c.add(seed);c.addAll(split);
        VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),global(c,Arrays.asList(seed),seed.getContent().length()),1000,8);
        assertEquals(2,kind(r,"incoming_literal").size());assertEquals(1,r.getExtraPacks().size());assertEquals(3,r.getExtraPacks().get(0).getChunks().size());
        assertEquals(1,kind(r,"incoming_literal").get(0).getPackIndex());assertEquals(1,kind(r,"incoming_literal").get(1).getPackIndex());
        Chunk tail=r.getExtraPacks().get(0).getChunks().stream().filter(v->"tail".equals(v.getId())).findFirst().get();assertEquals(split.get(1).getParts(),tail.getParts());assertEquals(split.get(1).getContent(),tail.getContent());
    }
    @Test void derivedIncomingNoteNeverGeneratesAnUnrankedSecondHop() {
        Chunk seed=seed(),note=note("note","mail","See CCC.9 for a separate register."),other=chunk("other","other","CCC.9","The register shall be retained.");List<Chunk> c=Arrays.asList(seed,note,other);
        VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),global(c,Arrays.asList(seed),seed.getContent().length()),1000,8);
        assertTrue(r.getExtraPacks().stream().allMatch(p->!ids(p.getChunks()).contains("other")));assertTrue(r.getRequests().stream().noneMatch(t->t.getEligibleOriginIds().contains("note")));
    }
    @Test void independentlyOriginalRankedEpisodeIsExplicitAndDoesNotRewriteGlobalOrigin() {
        Chunk seed=seed(),note=note("note","mail","See CCC.9 for a separate register."),other=chunk("other","other","CCC.9","The register shall be retained.");List<Chunk> c=Arrays.asList(seed,note,other);List<Chunk> ranks=Arrays.asList(seed,note);
        VettingContextBuilder.Selection s=global(c,Arrays.asList(seed),1000);VettingSourceRequestPacks.Result r=plan(c,ranks,s,1000,8);
        assertEquals(Collections.singletonList("seed"),r.getGlobalEligibleOriginIds());
        assertTrue(r.getRequests().stream().filter(t->t.getEligibleOriginIds().contains("note")).allMatch(t->t.getObservationId().startsWith("independent_ranked_request_episode_")));
        assertTrue(r.getExtraPacks().stream().anyMatch(p->ids(p.getChunks()).contains("other")));
    }
    @Test void genuinelyUnadmittedUnownedRankedSeedRetainsItsLiteralAndWholeTargetFamily() {
        Chunk first=seed(),unowned=chunk("ranked","letter",null,"The archive shall follow CCC.9 for retained records."),t1=chunk("target1","lib","CCC.9","The register shall contain a record."),t2=chunk("target2","lib","CCC.9","Unless equipment is inactive the register is required.");List<Chunk> c=Arrays.asList(first,unowned,t1,t2);List<Chunk> rank=Arrays.asList(first,unowned);
        VettingContextBuilder.Selection s=global(c,rank,first.getContent().length());assertFalse(ids(s.getChunks()).contains("ranked"));VettingSourceRequestPacks.Result r=plan(c,rank,s,1000,8);
        VettingSourceRequestPacks.Request ref=kind(r,"outgoing_literal").stream().filter(t->t.getEligibleOriginIds().contains("ranked")).findFirst().get();
        assertTrue(ref.getRequiredChunkIds().containsAll(Arrays.asList("ranked","target1","target2")));assertTrue(r.getExtraPacks().stream().anyMatch(p->ids(p.getChunks()).containsAll(ref.getRequiredChunkIds())));assertNull(unowned.getClauseId());
    }
    @Test void sourceMutationAfterSnapshotFailsBeforePlanningAndProjectionMutationsAreIsolated() {
        Chunk seed=seed(),note=note("note","mail","");List<Chunk> c=Arrays.asList(seed,note);VettingContextBuilder.Selection s=global(c,Arrays.asList(seed),seed.getContent().length());VettingSourceRequestPacks p=new VettingSourceRequestPacks(c);
        note.getParts().get(0).setExtractionSource("ocr");assertThrows(IllegalArgumentException.class,()->p.plan("field record",Arrays.asList(seed),s));
        note.getParts().get(0).setExtractionSource("word");VettingSourceRequestPacks.Result r=p.plan("field record",Arrays.asList(seed),s);r.getExtraPacks().get(0).getChunks().get(0).getParts().get(0).setText("Output mutation");
        assertEquals(seed.getContent(),seed.getParts().get(0).getText());assertDoesNotThrow(()->p.plan("field record",Arrays.asList(seed),s));
    }
    @Test void sameIdHashAndTextWithTamperedNativePartIsNotCanonicalInput() {
        Chunk seed=seed();VettingContextBuilder.Selection s=global(Arrays.asList(seed),Arrays.asList(seed),1000);Chunk forged=JsonUtils.read(JsonUtils.write(seed),Chunk.class);forged.getParts().get(0).setAnchor("changed");
        assertThrows(IllegalArgumentException.class,()->plan(Arrays.asList(seed),Arrays.asList(forged),s,1000,8));s.setChunks(Arrays.asList(forged));assertThrows(IllegalArgumentException.class,()->plan(Arrays.asList(seed),Arrays.asList(seed),s,1000,8));
    }
    @Test void legacyQualityAndAmbiguousIncomingSourceAreExplicitUnknown() {
        Chunk seed=seed(),note=note("note","mail","");note.setSourceQualityHash(VettingCorpus.hash("forged"));List<Chunk> c=Arrays.asList(seed,note);
        VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),global(c,Arrays.asList(seed),seed.getContent().length()),1000,8);assertTrue(r.getExtraPacks().isEmpty());assertTrue(kind(r,"incoming_literal").stream().anyMatch(t->"unknown".equals(t.getStatus())));
        note.setSourceQualityHash(VettingSourceQuality.hash(note.getSourceQuality()));Chunk collision=note("collision","mail","");collision.setSourceHash(VettingCorpus.hash("different raw"));c=Arrays.asList(seed,note,collision);r=plan(c,Arrays.asList(seed),global(c,Arrays.asList(seed),seed.getContent().length()),1000,8);assertTrue(r.getExtraPacks().isEmpty());assertTrue(kind(r,"incoming_literal").stream().allMatch(t->"unknown".equals(t.getStatus())));
    }
    @Test void standardRoleAndPartialNeedsReviewQualityArePreserved() {
        Chunk seed=seed(),note=note("note","mail","");note.setRole("standard");note.getSourceQuality().setParseStatus("PARTIAL");note.setSourceQualityHash(VettingSourceQuality.hash(note.getSourceQuality()));List<Chunk> c=Arrays.asList(seed,note);
        VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),global(c,Arrays.asList(seed),seed.getContent().length()),1000,8);Chunk actual=r.getExtraPacks().get(0).getChunks().stream().filter(v->"note".equals(v.getId())).findFirst().get();
        assertEquals("standard",actual.getRole());assertEquals("PARTIAL",actual.getSourceQuality().getParseStatus());assertEquals(JsonUtils.write(note),JsonUtils.write(actual));assertFalse(r.isSemanticScopeVerified());
    }
    @Test void suppliedTruncatedTargetFamilyCannotBecomeACompletePacket() {
        Chunk seed=chunk("seed","source","AAA.1","The field record shall follow CCC.9."),a=chunk("a","lib","CCC.9","The record shall contain a schedule."),b=chunk("b","lib","CCC.9","Unless equipment is inactive the record is required.");List<Chunk> c=Arrays.asList(seed,a,b);
        VettingContextBuilder.Selection s=global(c,Arrays.asList(seed),seed.getContent().length());s.getReferences().get(0).setRequiredTargetIds(Collections.singletonList("a"));VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),s,1000,8);
        assertEquals("unknown",kind(r,"outgoing_literal").get(0).getStatus());assertEquals("incomplete_canonical_target_family",kind(r,"outgoing_literal").get(0).getUnknownReason());assertTrue(r.getExtraPacks().isEmpty());
    }
    @Test void weakExtensionMembershipCannotAuthorizeGlobalLiteralOrigin() {
        Chunk seed=chunk("seed","source","AAA.1","The field record shall follow CCC.9."),condition=chunk("condition","second","BBB.2","Unless equipment is inactive the field record is required."),target=chunk("target","lib","CCC.9","The record shall be retained.");List<Chunk> c=Arrays.asList(seed,condition,target);
        VettingContextBuilder.Selection s=new VettingContextBuilder.Selection();s.setChunks(Arrays.asList(seed,condition));s.setContentChars(seed.getContent().length()+condition.getContent().length());VettingContextBuilder.GroupTrace g=new VettingContextBuilder.GroupTrace();g.setRelation("shared_entity_extension");g.setStatus("selected");g.setCoreIds(Arrays.asList("seed","condition"));s.setGroups(Arrays.asList(g));
        VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),s,1000,8);assertTrue(r.getGlobalEligibleOriginIds().isEmpty());assertTrue(r.getRequests().stream().filter(t->"outgoing_literal".equals(t.getKind())).allMatch(t->t.getObservationId().startsWith("independent_ranked_request_episode_")));
    }
    @Test void stablePlansPreserveGlobalOrderAndRejectInvalidBudgetOrCost() {
        Chunk seed=seed(),note=note("note","mail","");List<Chunk> c=Arrays.asList(seed,note);VettingContextBuilder.Selection s=global(c,Arrays.asList(seed),seed.getContent().length());VettingSourceRequestPacks p=new VettingSourceRequestPacks(c);
        assertEquals(JsonUtils.write(p.plan("field record",Arrays.asList(seed),s,1000,8)),JsonUtils.write(p.plan("field record",Arrays.asList(seed),s,1000,8)));
        assertThrows(IllegalArgumentException.class,()->p.plan("field record",Arrays.asList(seed),s,-1,8));s.setContentChars(s.getContentChars()+1);assertThrows(IllegalArgumentException.class,()->p.plan("field record",Arrays.asList(seed),s,1000,8));
    }
    @Test void finalGenuineGroupDoesNotOverrideTypedFalseOriginAndIndependentEpisodeRecoversIt() {
        Chunk seed=chunk("ranked-seed","a","XYZ.8",pad("Log Packet (LP) shall contain a serial record. See AAA.1.",80));
        Chunk heavy=chunk("ranked-heavy","b","XYZ.9",pad("LP shall contain a serial record.",400)),condition=chunk("condition","c","XYZ.10",pad("LP shall contain a serial record unless the sensor is disabled.",80)),target=chunk("target","d","AAA.1",pad("Archive shall preserve serial records.",40));
        List<Chunk> corpus=Arrays.asList(seed,heavy,condition,target),ranked=Arrays.asList(seed,heavy);VettingContextBuilder b=new VettingContextBuilder(corpus);
        VettingContextBuilder.Selection first=b.build("serial record sensor log",ranked,Collections.emptyMap(),200),expanded=b.expandPreservingInitial("serial record sensor log",ranked,Collections.emptyMap(),800,first);
        assertTrue(expanded.getGroups().stream().anyMatch(g->"shared_entity".equals(g.getRelation())&&"selected".equals(g.getStatus())&&g.getCoreIds().contains(seed.getId())));
        assertFalse(expanded.getReferences().stream().anyMatch(t->seed.getId().equals(t.getOriginId())&&t.isInitiallySubmitted()));assertFalse(ids(expanded.getChunks()).contains("target"));
        VettingSourceRequestPacks.Result r=new VettingSourceRequestPacks(corpus).plan("serial record sensor log",ranked,expanded,1000,8);
        assertFalse(r.getGlobalEligibleOriginIds().contains(seed.getId()));VettingSourceRequestPacks.Request request=kind(r,"outgoing_literal").stream().filter(t->t.getEligibleOriginIds().contains(seed.getId())).findFirst().get();
        assertTrue(request.getObservationId().startsWith("independent_ranked_request_episode_"));assertEquals("transported_extra_pack",request.getStatus());assertTrue(r.getExtraPacks().stream().anyMatch(p->ids(p.getChunks()).containsAll(Arrays.asList(seed.getId(),"target"))));
    }
    @Test void completeNativeCellsAndEmptySlotsUseExistingProductionProjectionUnchanged() {
        Chunk seed=seed(),note=note("note","mail","");note.setRole("standard");List<String> cells=Arrays.asList(note.getContent(),"","Room | East 😀");note.setContent(String.join(" | ",cells));Part p=note.getParts().get(0);p.setText(note.getContent());p.setEndOffset(note.getContent().length());p.setBlockId("body:7:table-row:1");p.setAnchor("body/7/table-row/1");note.setAnchor(p.getAnchor());VettingCorpus.TableRow table=new VettingCorpus.TableRow();table.setTableLocation("body/7");table.setRowIndex(1);table.setCells(cells);p.setTable(table);
        List<Chunk> c=Arrays.asList(seed,note);VettingSourceRequestPacks.Result r=plan(c,Arrays.asList(seed),global(c,Arrays.asList(seed),seed.getContent().length()),1000,8);
        Chunk transported=r.getExtraPacks().get(0).getChunks().stream().filter(x->"note".equals(x.getId())).findFirst().get();assertEquals(JsonUtils.write(note),JsonUtils.write(transported));
        List<Map<String,Object>> expected=VettingSourceMaterial.project(Collections.singletonList(note)),actual=VettingSourceMaterial.project(Collections.singletonList(transported));assertEquals(expected,actual);assertFalse(((List<?>)actual.get(0).get("nativeRows")).isEmpty());
    }
    @Test void partialIncomingRequiredSetAndForeignPartVersionAreNeverClaimedComplete() {
        Chunk seed=seed();List<Chunk> split=splitNote();List<Chunk> corpus=new ArrayList<>();corpus.add(seed);corpus.addAll(split);VettingContextBuilder.Selection s=global(corpus,Arrays.asList(seed),seed.getContent().length());s.getIncomingReferences().forEach(t->t.setRequiredChunkIds(Collections.singletonList(t.getIncomingSourceId())));
        VettingSourceRequestPacks.Result r=plan(corpus,Arrays.asList(seed),s,1000,8);assertTrue(r.getExtraPacks().isEmpty());assertTrue(kind(r,"incoming_literal").stream().allMatch(t->"unknown".equals(t.getStatus())));
        split.get(1).setSegmentationVersion("unknown-version");s=global(corpus,Arrays.asList(seed),seed.getContent().length());r=plan(corpus,Arrays.asList(seed),s,1000,8);assertTrue(r.getExtraPacks().isEmpty());assertTrue(kind(r,"incoming_literal").stream().allMatch(t->"unknown".equals(t.getStatus())));
    }
    private String pad(String text,int size){StringBuilder b=new StringBuilder(text);while(b.length()<size)b.append('x');return b.toString();}
    private List<Chunk> splitNote() {
        String left="This note qualifies AAA.1; original field record 😀 is retained. ",right="The other note qualifies AAA.1 unless equipment is inactive.";
        Chunk a=chunk("note","mail",null,left),b=chunk("tail","mail",null,right);Part p=a.getParts().get(0),q=b.getParts().get(0);p.setBlockId("one-block");q.setBlockId("one-block");p.setAnchor("body/7/table-row/1");q.setAnchor("body/7/table-row/1 @"+left.length());q.setStartOffset(left.length());q.setEndOffset(left.length()+right.length());a.setAnchor(p.getAnchor());b.setAnchor(q.getAnchor());return Arrays.asList(a,b);
    }
}
