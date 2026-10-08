package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingIncomingLiteralContextTest {
    private Chunk chunk(String id,String doc,String clause,String heading,String body) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileName(doc+".docx");c.setSourceHash(VettingCorpus.hash("raw:"+doc));c.setRole("tender");
        c.setFileKey(clause==null?"OTHER":clause.substring(0,3));c.setClauseId(clause);c.setClauseHeadingLocation(heading);c.setAnchor("body/"+id+"/paragraph");c.setContent(body);
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        VettingSourceQuality.Info quality=new VettingSourceQuality.Info();quality.setParseStatus("PARSED");quality.setOcrQualityStatus("not_observed");quality.setCoverageMetadataSha256(VettingCorpus.hash("test-only-parser-declaration"));
        c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);c.setSourceQuality(quality);c.setSourceQualityHash(VettingSourceQuality.hash(quality));
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setText(body);p.setStartOffset(0);p.setEndOffset(body.length());p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    private Chunk seed() {return chunk("seed","source","AAA.1","body/1/paragraph","The Contractor shall provide a field record.");}
    private Chunk note() {return chunk("note","note-source",null,null,"This note qualifies AAA.1; the field record is required unless equipment is inactive.");}
    private VettingContextBuilder.Selection build(List<Chunk> corpus,List<Chunk> ranked,int limit) {return new VettingContextBuilder(corpus).build("field record",ranked,Collections.emptyMap(),limit);}
    private Set<String> ids(VettingContextBuilder.Selection s) {return s.getChunks().stream().map(Chunk::getId).collect(Collectors.toCollection(LinkedHashSet::new));}
    private VettingIncomingLiteralContext.Trace incoming(VettingContextBuilder.Selection s) {return s.getIncomingReferences().stream().filter(t->"note".equals(t.getIncomingSourceId())).findFirst().orElseThrow(()->new AssertionError("incoming trace absent"));}

    @Test void sourceBoundUnownedTenderNoteIsTransportedWithUnknownApplicability() {
        Chunk seed=seed(),note=note();VettingContextBuilder.Selection s=build(Arrays.asList(seed,note),Arrays.asList(seed),10000);
        assertEquals(new LinkedHashSet<>(Arrays.asList("seed","note")),ids(s));assertSame(note,s.getChunks().get(1));assertNull(note.getClauseId());assertNull(note.getClauseHeadingLocation());
        VettingIncomingLiteralContext.Trace trace=incoming(s);assertEquals("incoming_literal_to_initial_ranked_clause",trace.getDirection());assertEquals(Arrays.asList("seed"),trace.getInitialOriginIds());assertTrue(trace.isObservedRangesTransported());
        assertEquals("unknown",trace.getApplicability());assertEquals("unknown",trace.getQualifiersComplete());assertFalse(trace.isLegalApplicabilityVerified());assertFalse(trace.isSourceBlockCompleteKnown());
        assertEquals(seed.getContent().length()+note.getContent().length(),s.getContentChars());assertEquals(note.getParts().get(0).getText(),note.getContent());
    }
    @Test void incomingNoteCannotPropagateItsOtherLiteralReference() {
        Chunk seed=seed(),note=note(),second=chunk("second","other-source","CCC.9","body/9/paragraph","The register shall be inspected.");
        note=chunk("note","note-source",null,null,note.getContent()+" See CCC.9 for a separate register.");
        VettingContextBuilder.Selection s=build(Arrays.asList(seed,note,second),Arrays.asList(seed),10000);
        assertEquals(new LinkedHashSet<>(Arrays.asList("seed","note")),ids(s));assertFalse(ids(s).contains("second"));assertTrue(s.getReferences().stream().noneMatch(t->"note".equals(t.getOriginId())));
        assertEquals(1,s.getIncomingReferences().size());
    }
    @Test void explicitSourceRoleIsPreservedAndNotManufacturedFromLifecycle() {
        Chunk seed=seed(),note=note();note.setRole("standard");VettingContextBuilder.Selection s=build(Arrays.asList(seed,note),Arrays.asList(seed),10000);
        assertTrue(ids(s).contains("note"));assertEquals("standard",s.getChunks().stream().filter(c->c.getId().equals("note")).findFirst().get().getRole());assertTrue(incoming(s).getIncomingSourceIdentity().contains("|standard|"));
    }
    @Test void twoSourcesWithTheSameTargetClauseRemainAmbiguous() {
        Chunk seed=seed(),other=chunk("other","another-source","AAA.1","body/2/paragraph","The field record shall contain another schedule."),note=note();
        VettingContextBuilder.Selection s=build(Arrays.asList(seed,other,note),Arrays.asList(seed),10000);
        assertEquals(Collections.singleton("seed"),ids(s));assertEquals("ambiguous_source_or_edition",incoming(s).getStatus());assertFalse(incoming(s).isInitiallyConnected());
    }
    @Test void explicitEditionUsesExistingResolverAndDoesNotChooseLatest() {
        Chunk seed=seed(),other=chunk("other","another-source","AAA.1","body/2/paragraph","The field record shall contain another schedule.");seed.setFileName("Library 2031 edition.docx");other.setFileName("Library 2037 edition.docx");
        Chunk note=chunk("note","note-source",null,null,"The record under AAA.1, edition 2031, is required unless equipment is inactive.");
        VettingContextBuilder.Selection s=build(Arrays.asList(seed,other,note),Arrays.asList(seed),10000);assertTrue(ids(s).contains("note"));assertFalse(ids(s).contains("other"));assertEquals(Collections.singletonList("seed"),incoming(s).getResolvedTargetFamilyIds());
    }
    @Test void sameClauseDifferentHeadingsDoNotBecomeAUniqueIncomingTarget() {
        Chunk seed=seed(),duplicate=chunk("duplicate","source","AAA.1","body/99/paragraph","The field record shall contain another schedule."),note=note();
        VettingContextBuilder.Selection s=build(Arrays.asList(seed,duplicate,note),Arrays.asList(seed),10000);assertFalse(ids(s).contains("note"));assertEquals("ambiguous_target_scope",incoming(s).getStatus());
    }
    @Test void foreignHashOnOneDocumentIdentityIsNotSilentlyAccepted() {
        Chunk seed=seed(),note=note(),collision=chunk("collision","note-source",null,null,"A separate source fragment.");collision.setSourceHash(VettingCorpus.hash("different-original"));
        VettingContextBuilder.Selection s=build(Arrays.asList(seed,note,collision),Arrays.asList(seed),10000);assertFalse(ids(s).contains("note"));assertEquals("ambiguous_incoming_document_identity",incoming(s).getStatus());
    }
    @Test void legacyOrForgedQualityCannotBePromotedByAnIncomingLiteral() {
        for(boolean missing:new boolean[]{true,false}) {
            Chunk seed=seed(),note=note();if(missing)note.setSourceQuality(null);else note.setSourceQualityHash(VettingCorpus.hash("forged-quality"));
            VettingContextBuilder.Selection s=build(Arrays.asList(seed,note),Arrays.asList(seed),10000);assertFalse(ids(s).contains("note"));assertEquals("unknown_incoming_quality_or_version_identity",incoming(s).getStatus());
        }
    }
    @Test void sourcePartOffsetsAndBodyMustMatchBeforeAdmission() {
        Chunk seed=seed(),note=note();note.getParts().get(0).setEndOffset(note.getContent().length()-1);
        VettingContextBuilder.Selection s=build(Arrays.asList(seed,note),Arrays.asList(seed),10000);assertFalse(ids(s).contains("note"));assertEquals("unknown_incoming_part_mapping",incoming(s).getStatus());
    }
    private List<Chunk> splitNote() {
        String body="This note qualifies AAA.1; the field record is required unless equipment is inactive. Preserve the complete original source observation.";
        int cut=65;Chunk first=chunk("note","note-source",null,null,body.substring(0,cut)),last=chunk("tail","note-source",null,null,body.substring(cut));
        for(Chunk c:Arrays.asList(first,last)) {Part p=c.getParts().get(0);p.setBlockId("one-source-block");p.setAnchor("body/7/table-row/1"+(c==first?"":" @"+cut));c.setAnchor(p.getAnchor());}
        last.getParts().get(0).setStartOffset(cut);last.getParts().get(0).setEndOffset(body.length());return Arrays.asList(first,last);
    }
    @Test void splitOriginalBlockIsOneAtomicBudgetRequestWithoutTruncation() {
        Chunk seed=seed();List<Chunk> parts=splitNote(),corpus=new ArrayList<>();corpus.add(seed);corpus.addAll(parts);
        int small=seed.getContent().length()+parts.get(0).getContent().length();VettingContextBuilder.Selection s=build(corpus,Arrays.asList(seed),small);
        assertEquals(Collections.singleton("seed"),ids(s));assertEquals(new LinkedHashSet<>(Arrays.asList("note","tail")),new LinkedHashSet<>(incoming(s).getMissingChunkIds()));assertEquals("blocked_budget",incoming(s).getAdmissionStatus());assertEquals(2,incoming(s).getRequiredChunkIds().size());
        VettingContextBuilder.Selection full=build(corpus,Arrays.asList(seed),10000);assertEquals(new LinkedHashSet<>(Arrays.asList("seed","note","tail")),ids(full));assertTrue(incoming(full).isObservedRangesTransported());assertFalse(incoming(full).getBlockObservations().get(0).isSourceBlockCompleteKnown());
        assertEquals(corpus.stream().mapToInt(c->c.getContent().length()).sum(),full.getContentChars());for(Chunk original:corpus)assertSame(original,full.getChunks().stream().filter(c->c.getId().equals(original.getId())).findFirst().get());
    }
    @Test void inconsistentObservedSplitBoundaryRemainsUnknown() {
        Chunk seed=seed();List<Chunk> parts=splitNote();parts.get(1).getParts().get(0).setStartOffset(parts.get(1).getParts().get(0).getStartOffset()+3);parts.get(1).getParts().get(0).setEndOffset(parts.get(1).getParts().get(0).getEndOffset()+3);parts.get(1).getParts().get(0).setAnchor("body/7/table-row/1 @68");parts.get(1).setAnchor("body/7/table-row/1 @68");
        List<Chunk> corpus=new ArrayList<>();corpus.add(seed);corpus.addAll(parts);VettingContextBuilder.Selection s=build(corpus,Arrays.asList(seed),10000);
        assertEquals(Collections.singleton("seed"),ids(s));assertEquals("incoming_block_boundary_unknown",incoming(s).getStatus());assertTrue(incoming(s).getRequiredChunkIds().isEmpty());assertFalse(incoming(s).isObservedRangesTransported());
    }
    @Test void queryHintsAndContentsNeverManufactureAnIncomingSourceEdge() {
        Chunk seed=seed(),hint=chunk("hint","hint-source",null,null,"The field record is required unless equipment is inactive."),contents=chunk("contents","contents-source",null,null,"AAA.1 ................................ 27");
        VettingContextBuilder.Selection s=new VettingContextBuilder(Arrays.asList(seed,hint,contents)).build("See AAA.1 and the hint note",Arrays.asList(seed),Collections.emptyMap(),10000);
        assertEquals(Collections.singleton("seed"),ids(s));assertTrue(s.getIncomingReferences().isEmpty());
    }
    @Test void repeatedQueriesHaveStableFreshTracesAndNoSourceMutation() {
        Chunk seed=seed(),note=note();String original=com.consense.common.JsonUtils.write(Arrays.asList(seed,note));VettingContextBuilder b=new VettingContextBuilder(Arrays.asList(seed,note));
        VettingContextBuilder.Selection first=b.build("field record",Arrays.asList(seed),Collections.emptyMap(),10000),second=b.build("field record",Arrays.asList(seed),Collections.emptyMap(),10000);
        assertEquals(com.consense.common.JsonUtils.write(first),com.consense.common.JsonUtils.write(second));assertNotSame(first.getIncomingReferences().get(0),second.getIncomingReferences().get(0));assertEquals(original,com.consense.common.JsonUtils.write(Arrays.asList(seed,note)));
    }
    @Test void incomingMissingHasItsOwnTraceWithoutPretendingBudgetAlreadyIntegratesIt() {
        // The combined Budget now integrates incoming requests. Preserve this trace-only assertion at a hard, non-expandable ceiling.
        Chunk seed=seed(),note=note();VettingContextBuilder b=new VettingContextBuilder(Arrays.asList(seed,note));VettingContextBudget.Result result=VettingContextBudget.build(b,"field record",Arrays.asList(seed),Collections.emptyMap(),seed.getContent().length(),seed.getContent().length());
        assertFalse(result.isExpansionAttempted());assertEquals(Collections.singleton("note"),new LinkedHashSet<>(incoming(result.getSelection()).getMissingChunkIds()));assertFalse(result.getSelection().getGroups().get(0).isFullySubmitted());assertTrue(result.getSelection().getUnresolvedComparisonIds().contains("seed"));
    }
    @Test void sourceMutationAfterIndexingCannotReuseCachedProvenance() {
        for(String mutation:Arrays.asList("hash","quality","parts")) {
            Chunk seed=seed(),note=note();VettingContextBuilder b=new VettingContextBuilder(Arrays.asList(seed,note));
            if("hash".equals(mutation))note.setSourceHash(VettingCorpus.hash("different-raw-source"));
            else if("quality".equals(mutation))note.getSourceQuality().setParseStatus("PARTIAL");
            else note.getParts().get(0).setAnchor("body/changed/paragraph");
            VettingContextBuilder.Selection s=b.build("field record",Arrays.asList(seed),Collections.emptyMap(),10000);
            assertFalse(ids(s).contains("note"));assertEquals("incoming_source_changed_since_snapshot",incoming(s).getStatus());assertFalse(incoming(s).isInitiallyConnected());
        }
    }
    @Test void mutationOfAnUnrankedTargetFamilyMemberCannotReuseResolutionCache() {
        Chunk seed=seed(),family=chunk("family","source","AAA.1","body/1/paragraph","The record shall include a schedule."),note=note();VettingContextBuilder b=new VettingContextBuilder(Arrays.asList(seed,family,note));
        assertTrue(ids(b.build("field record",Arrays.asList(seed),Collections.emptyMap(),10000)).contains("note"));
        family.getParts().get(0).setAnchor("body/changed/paragraph");
        VettingContextBuilder.Selection s=b.build("field record",Arrays.asList(seed),Collections.emptyMap(),10000);
        assertFalse(ids(s).contains("note"));assertEquals("unknown_target_family_source_identity",incoming(s).getStatus());assertFalse(incoming(s).isInitiallyConnected());
    }
    @Test void continuationMemberMutationAfterSnapshotCannotEnterObservedClosure() {
        Chunk seed=seed();List<Chunk> parts=splitNote(),corpus=new ArrayList<>();corpus.add(seed);corpus.addAll(parts);VettingContextBuilder b=new VettingContextBuilder(corpus);
        parts.get(1).getParts().get(0).setExtractionSource("ocr");VettingContextBuilder.Selection s=b.build("field record",Arrays.asList(seed),Collections.emptyMap(),10000);
        assertEquals(Collections.singleton("seed"),ids(s));assertEquals("incoming_block_source_identity_unknown",incoming(s).getStatus());assertFalse(incoming(s).isObservedRangesTransported());
    }
}
