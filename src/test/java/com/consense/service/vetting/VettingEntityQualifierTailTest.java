package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingEntityQualifierTailTest {
    @Test void rankedPeerDoesNotHideAnUnrankedQualifierInAnotherScope() {
        Chunk seed=chunk("seed","doc","XYZ11.1","body/1","The Cedar Site Observer (CSO) shall attend the appointment inspection.");
        Chunk peer=chunk("ranked","doc","XYZ11.2","body/2","The CSO shall attend the appointment inspection and submit records.");
        Chunk tail=chunk("qualifier","doc","XYZ18.4","body/40","Except for the appointment inspection closure, the CSO shall attend and submit the appointment record.");
        VettingContextBuilder.Selection selection=build(Arrays.asList(seed,peer,tail),Arrays.asList(seed,peer),10000);
        assertTrue(ids(selection).containsAll(Arrays.asList("seed","ranked","qualifier")));
        assertEquals("submitted_chunks_only",selection.getCoverage());
        assertTrue(selection.getGroups().stream().allMatch(g -> "unknown".equals(g.getQualifiersComplete())));
        assertTrue(selection.getGroups().stream().anyMatch(g -> g.getCoreIds().contains("qualifier")&&"observed_entity_qualifier_terms".equals(g.getRelationStrength())));
    }

    @Test void unrelatedQualifiersWithoutSharedLocalTopicDoNotEnter() {
        Chunk seed=chunk("seed","doc","XYZ11.1","body/1","The Cedar Site Observer (CSO) shall attend the appointment inspection.");
        Chunk peer=chunk("ranked","doc","XYZ11.2","body/2","The CSO shall attend the appointment inspection.");
        Chunk noise=chunk("noise","doc","XYZ18.4","body/40","Unless warehouse permits expire, the CSO must arrange emergency security transport.");
        assertFalse(ids(build(Arrays.asList(seed,peer,noise),Arrays.asList(seed,peer),10000)).contains("noise"));
    }

    @Test void staleRevisionAndReferenceRolesCannotSupplyTenderQualifierTail() {
        Chunk seed=chunk("seed","doc","XYZ11.1","body/1","The Cedar Site Observer (CSO) shall attend the appointment inspection.");
        Chunk peer=chunk("ranked","doc","XYZ11.2","body/2","The CSO shall attend the appointment inspection.");
        Chunk stale=chunk("stale","doc","XYZ18.4","body/40","Except for inspection closure, the CSO shall attend the appointment.");stale.setSourceHash("old-revision");
        Chunk standard=chunk("standard","library","XYZ18.4","page/1","Except for inspection closure, the CSO shall attend the appointment.");standard.setRole("standard");
        Set<String> ids=ids(build(Arrays.asList(seed,peer,stale,standard),Arrays.asList(seed,peer),10000));
        assertFalse(ids.contains("stale"));assertFalse(ids.contains("standard"));
    }

    @Test void boundedWeakQualifierCannotRemoveOriginalRankedCoresOrExceedBudget() {
        Chunk seed=chunk("seed","doc","XYZ11.1","body/1","The Cedar Site Observer (CSO) shall attend the appointment inspection.");
        Chunk peer=chunk("ranked","doc","XYZ11.2","body/2","The CSO shall attend the appointment inspection.");
        Chunk tail=chunk("tail","doc","XYZ18.4","body/40","Except for inspection closure, the CSO shall attend the appointment."+String.join("",Collections.nCopies(100,"x")));
        int limit=seed.getContent().length()+peer.getContent().length();
        VettingContextBuilder.Selection selection=build(Arrays.asList(seed,peer,tail),Arrays.asList(seed,peer),limit);
        assertEquals(new LinkedHashSet<>(Arrays.asList("seed","ranked")),ids(selection));assertEquals(limit,selection.getContentChars());
    }

    private static VettingContextBuilder.Selection build(List<Chunk> corpus,List<Chunk> ranked,int limit) {
        return new VettingContextBuilder(corpus).build("appointment inspection",ranked,Collections.emptyMap(),limit);
    }
    private static Set<String> ids(VettingContextBuilder.Selection selection) {
        return selection.getChunks().stream().map(Chunk::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }
    private static Chunk chunk(String id,String doc,String clause,String anchor,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileKey("XYZ");c.setFileName("XYZ.docx");c.setRole("tender");
        c.setSourceHash("current-revision");c.setClauseId(clause);c.setClauseHeadingLocation(anchor);c.setAnchor(anchor);c.setContent(text);
        Part p=new Part();p.setBlockId(anchor);p.setAnchor(anchor);p.setText(text);p.setStartOffset(0);p.setEndOffset(text.length());c.setParts(Collections.singletonList(p));return c;
    }
}
