package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.*;
import com.consense.service.vetting.VettingSourceRequestPacks.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VettingTokenAwareRequestPacksTest {
    private Chunk c(String id,String doc,String clause,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileName(doc+".docx");c.setRole("tender");c.setSourceHash(VettingCorpus.hash(doc));c.setFileKey(clause==null?"OTHER":"AAA");c.setClauseId(clause);c.setClauseHeadingLocation(clause==null?null:"body/heading/"+clause);c.setContent(text);c.setAnchor("body/"+id+"/paragraph");
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARTIAL");q.setOcrQualityStatus("needs_review");q.setCoverageMetadataSha256(VettingCorpus.hash("synthetic-saved-provenance"));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setStartOffset(0);p.setEndOffset(text.length());p.setText(text);p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private List<Chunk> corpus() {return Arrays.asList(c("seed","terms","AAA.1","The shipment register shall follow AAA.1."),c("note-a","notice-a",null,"This source qualifies AAA.1 unless the delivery is inactive."),c("note-b","notice-b",null,"This source qualifies AAA.1 when the shipment is inspected."));}
    private VettingContextBuilder.Selection global(List<Chunk> c) {return new VettingContextBuilder(c).build("shipment",Collections.singletonList(c.get(0)),Collections.emptyMap(),c.get(0).getContent().length());}
    private Result source(List<Chunk> c,VettingContextBuilder.Selection g,int cap) {return new VettingSourceRequestPacks(c).plan("shipment",Collections.singletonList(c.get(0)),g,20000,cap);}
    private VettingTokenAwareRequestPacks.InputFactory factory() {return p->VettingInputBudget.input("synthetic","mock-model","complete synthetic system "+(p.getIndex()==0?"GLOBAL":"EXTRA"),VettingSourcePromptWire.write(VettingSourceMaterial.project(p.getChunks())),VettingOutputSchema.forChunkIds(p.getChunks().stream().map(Chunk::getId).collect(java.util.stream.Collectors.toList())),100,VettingCorpus.hash(JsonUtils.write(p.getChunks())));}
    private VettingInputBudget.Observation observed(VettingInputBudget.Input in) {VettingInputBudget.Observation o=VettingInputBudgetTest.fixtureObservation(in);o.setInputTokens(VettingSourcePromptWire.decode(in.getMessages().get(1).getContent()).size()*1000L);o.setEffectiveContextTokens(2500);return o;}
    @Test void fullyIdentifiedLongRequestsAreCountedAndTransportedBeyondLegacyCharacterCap() {
        String longText="This source qualifies AAA.1 unless delivery is inactive. "+String.join("",Collections.nCopies(30000,"x"));
        List<Chunk> chunks=new ArrayList<>(corpus());chunks.set(1,c("note-a","notice-a",null,longText));
        VettingContextBuilder.Selection g=global(chunks);Result original=source(chunks,g,8);
        assertTrue(original.getRequests().stream().anyMatch(r->"oversized".equals(r.getStatus())&&r.getUnknownReason()==null&&r.getRequiredChunkIds().contains("note-a")));
        AtomicInteger counters=new AtomicInteger();VettingTokenAwareRequestPacks.Plan plan=new VettingTokenAwareRequestPacks(chunks).repack(original,g,factory(),in->{counters.incrementAndGet();VettingInputBudget.Observation o=observed(in);o.setEffectiveContextTokens(100000);return o;});
        assertTrue(counters.get()>1);assertFalse(plan.isLegacyCharacterGateApplied());
        assertTrue(plan.getSourcePlan().getExtraPacks().stream().flatMap(p->p.getChunks().stream()).anyMatch(x->"note-a".equals(x.getId())&&longText.equals(x.getContent())));
        assertTrue(plan.getSourcePlan().getRequests().stream().filter(r->r.getUnknownReason()==null&&r.getRequiredChunkIds().contains("note-a")).allMatch(r->"transported_extra_pack".equals(r.getStatus())));
        assertTrue(plan.getRelatedGroups().stream().anyMatch(group->group.getRequestIds().size()>1&&"transported_related_group".equals(group.getOutcome())));
        assertEquals(1,plan.getSourcePlan().getExtraPacks().size());assertFalse(plan.isSemanticScopeVerified());
    }
    @Test void relatedRequestsStayTogetherWhenTheyFitAndUnknownWholeGroupDoesNotSubmitFragments() {
        List<Chunk> chunks=corpus();VettingContextBuilder.Selection g=global(chunks);Result original=source(chunks,g,8);
        VettingTokenAwareRequestPacks.Plan fit=new VettingTokenAwareRequestPacks(chunks).repack(original,g,factory(),in->{VettingInputBudget.Observation o=observed(in);o.setEffectiveContextTokens(100000);return o;});
        assertEquals(1,fit.getSourcePlan().getExtraPacks().size());Pack packet=fit.getSourcePlan().getExtraPacks().get(0);
        assertTrue(packet.getChunks().stream().map(Chunk::getId).collect(java.util.stream.Collectors.toSet()).containsAll(Arrays.asList("seed","note-a","note-b")));
        assertFalse(fit.getRelatedGroups().get(0).isSplitAfterObservedOverBudget());
        VettingTokenAwareRequestPacks.Plan unknown=new VettingTokenAwareRequestPacks(chunks).repack(original,g,factory(),in->{if(in.getMessages().get(1).getContent().contains("note-b"))throw new IllegalStateException("synthetic unknown");return observed(in);});
        assertTrue(unknown.getSourcePlan().getExtraPacks().isEmpty());assertTrue(unknown.getSourcePlan().getRequests().stream().filter(r->r.getUnknownReason()==null||"complete_input_budget_observation_unknown".equals(r.getUnknownReason())).allMatch(r->"budget_unknown".equals(r.getStatus())));
    }
    @Test void completeTokenEnvelopeSeparatesWholeRequestsDespiteSmallCharCost() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Result original=source(c,g,8);String before=JsonUtils.write(original);
        VettingTokenAwareRequestPacks.Plan plan=new VettingTokenAwareRequestPacks(c).repack(original,g,factory(),this::observed);
        assertEquals(2,plan.getSourcePlan().getExtraPacks().size());assertTrue(original.getExtraPacks().size()<plan.getSourcePlan().getExtraPacks().size(),"Char-only first-fit misses complete token envelope");
        for(Pack p:plan.getSourcePlan().getExtraPacks()){assertEquals(2,p.getChunks().size());assertTrue(p.getChunks().stream().anyMatch(x->"seed".equals(x.getId())));assertTrue(p.isTokenEnvelopeMeasured());assertEquals(2000,plan.getPackInputBudgets().get(p.getIndex()).getObservation().getInputTokens());}
        for(Request r:plan.getSourcePlan().getRequests())if("transported_extra_pack".equals(r.getStatus()))assertTrue(plan.getSourcePlan().getExtraPacks().get(r.getPackIndex()-1).getChunks().stream().map(Chunk::getId).collect(java.util.stream.Collectors.toList()).containsAll(r.getRequiredChunkIds()));
        assertEquals(before,JsonUtils.write(original));assertEquals(JsonUtils.write(g.getChunks()),JsonUtils.write(plan.getSourcePlan().getGlobalChunks()));assertFalse(plan.isPreviewIsFinalDispatchAuthority());
    }
    @Test void observerUnavailableKeepsEveryWholeRequestPendingWithoutConstructingFakeFits() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Result original=source(c,g,8);VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(original,g,factory(),null);
        assertTrue(p.getSourcePlan().getExtraPacks().isEmpty());assertTrue(p.getSourcePlan().getRequests().stream().allMatch(r->"budget_unknown".equals(r.getStatus())||r.getUnknownReason()!=null));assertFalse(p.getSourcePlan().isTokenEnvelopeMeasured());assertEquals(0,p.getActualPreviewObserverCalls());assertEquals(original.getRequests().size(),p.getSourcePlan().getRequests().size());
    }
    @Test void globalKnownOverPresenceCanBeReplannedIndependentlyWithoutGlobalReviewClaim() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=new VettingContextBuilder(c).build("shipment",Collections.singletonList(c.get(0)),Collections.emptyMap(),20000);Result original=source(c,g,8);
        VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(original,g,factory(),in->{VettingInputBudget.Observation o=observed(in);if(in.getMessages().get(0).getContent().contains("GLOBAL"))o.setInputTokens(3000);return o;});
        assertEquals("over_budget",p.getGlobalInputBudget().getStatus());assertTrue(p.getOriginalRequestTransportStatuses().containsValue("already_global"),JsonUtils.write(original));assertFalse(p.getSourcePlan().getRequests().stream().anyMatch(r->"already_global".equals(r.getStatus())));assertTrue(p.getSourcePlan().getRequests().stream().anyMatch(r->"transported_extra_pack".equals(r.getStatus())&&"already_global".equals(p.getOriginalRequestTransportStatuses().get(r.getId()))));assertEquals(JsonUtils.write(g.getChunks()),JsonUtils.write(p.getSourcePlan().getGlobalChunks()));
    }
    @Test void capAndKnownSingleRequestOverRemainDistinctAndUnsplit() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Result original=source(c,g,1);
        VettingTokenAwareRequestPacks.Plan capped=new VettingTokenAwareRequestPacks(c).repack(original,g,factory(),this::observed);assertEquals(1,capped.getSourcePlan().getExtraPacks().size());assertTrue(capped.getSourcePlan().getRequests().stream().anyMatch(r->"omitted_pack_cap".equals(r.getStatus())&&r.getMissingChunkIds().contains("note-b")));
        VettingTokenAwareRequestPacks.Plan over=new VettingTokenAwareRequestPacks(c).repack(original,g,factory(),in->{VettingInputBudget.Observation o=observed(in);if(in.getMessages().get(1).getContent().contains("note-b"))o.setInputTokens(3000);return o;});assertTrue(over.getSourcePlan().getRequests().stream().anyMatch(r->"over_budget".equals(r.getStatus())&&r.getMissingChunkIds().contains("note-b")));assertFalse(over.getSourcePlan().getExtraPacks().stream().flatMap(p->p.getChunks().stream()).anyMatch(x->"note-b".equals(x.getId())));
    }
    @Test void fullSourceRoleQualityAndMutableBindingCannotBeWeakenedForPacking() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Result original=source(c,g,8);VettingTokenAwareRequestPacks planner=new VettingTokenAwareRequestPacks(c);c.get(1).setSourceHash(VettingCorpus.hash("foreign-revision"));assertThrows(IllegalArgumentException.class,()->planner.repack(original,g,factory(),this::observed));
        List<Chunk> fresh=corpus();VettingContextBuilder.Selection good=global(fresh);Result bad=source(fresh,good,8);bad.getCanonicalPayloadHashes().put("note-a",VettingCorpus.hash("foreign-payload"));assertThrows(IllegalArgumentException.class,()->new VettingTokenAwareRequestPacks(fresh).repack(bad,good,factory(),this::observed));
    }
    @Test void aFactoryMutatingCandidatesOrReturningForeignSourceInputIsRejected() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Result original=source(c,g,8);
        assertThrows(IllegalArgumentException.class,()->new VettingTokenAwareRequestPacks(c).repack(original,g,p->{p.getChunks().get(0).setRole("standard");return factory().build(p);},this::observed));
        assertThrows(IllegalArgumentException.class,()->new VettingTokenAwareRequestPacks(c).repack(original,g,p->{VettingInputBudget.Input in=factory().build(p);in.setSourceSnapshotSha256(VettingCorpus.hash("foreign-input-source"));return in;},this::observed));
    }
    @Test void previewIdentityIsNotFinalAuthorityAndDriftDoesNotPassComparison() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Result original=source(c,g,8);AtomicInteger calls=new AtomicInteger();
        VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(original,g,factory(),in->{calls.incrementAndGet();return observed(in);});
        assertEquals(calls.get(),p.getActualPreviewObserverCalls());VettingInputBudget.Result preview=p.getPackInputBudgets().get(1),copy=JsonUtils.read(JsonUtils.write(preview),VettingInputBudget.Result.class);assertTrue(VettingTokenAwareRequestPacks.sameFinalObservation(preview,copy));copy.getObservation().setTokenizerIdentitySha256(VettingCorpus.hash("new-tokenizer"));assertFalse(VettingTokenAwareRequestPacks.sameFinalObservation(preview,copy));assertFalse(p.isPreviewIsFinalDispatchAuthority());
        assertTrue(p.getSourcePlan().getRequests().stream().allMatch(r->r.getEligibleOriginIds().equals(Collections.singletonList("seed"))));assertEquals(0,p.getSourcePlan().getAdditionalRetrievalCalls());assertEquals(0,p.getSourcePlan().getAdditionalModelCalls());
    }
    @Test void aDeclaredInputHashCannotReuseAnotherCompleteInputPreview() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Result original=source(c,g,8);java.util.concurrent.atomic.AtomicReference<String> first=new java.util.concurrent.atomic.AtomicReference<>();
        assertThrows(IllegalArgumentException.class,()->new VettingTokenAwareRequestPacks(c).repack(original,g,p->{VettingInputBudget.Input in=factory().build(p);if(first.get()==null)first.set(in.getSerializedInputSha256());else in.setSerializedInputSha256(first.get());return in;},this::observed));
    }
    @Test void providerTokenizerOrContextIdentityDriftAcrossPreviewsCannotAdmitARequest() {
        List<Chunk> c=corpus();VettingContextBuilder.Selection g=global(c);Result original=source(c,g,8);
        VettingTokenAwareRequestPacks.Plan p=new VettingTokenAwareRequestPacks(c).repack(original,g,factory(),in->{VettingInputBudget.Observation o=observed(in);if(!in.getMessages().get(0).getContent().contains("GLOBAL"))o.setTokenizerIdentitySha256(VettingCorpus.hash("changed-preview-tokenizer"));return o;});
        assertTrue(p.getSourcePlan().getExtraPacks().isEmpty(),"A different valid-looking tokenizer identity must fail closed");assertTrue(p.getSourcePlan().getRequests().stream().filter(r->!"already_global".equals(r.getStatus())).allMatch(r->"budget_unknown".equals(r.getStatus())||r.getUnknownReason()!=null));
    }
}
