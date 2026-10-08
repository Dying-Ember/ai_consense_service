package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingProjectReferencePackTest {
    @Test void projectReferencesNominateUnrankedOriginalTenderAndKeepRequestBlankAndReplyAsContext() {
        for (String owner : Arrays.asList("QRT", "LXZ", "HNV")) {
            Chunk tender=chunk("target","contract",owner,owner+"71","tender","(1) The officer is named.\n(2) The documents may be inspected at the project office.");
            Chunk tail=chunk("tail","contract",owner,owner+"71","tender","(3) Except when access is restricted, inspect the drawings at the project office.");
            Chunk standard=chunk("standard","template",owner,owner+"71","standard","(1) The officer is *[name].\n(2) The documents may be inspected at *[address].\n(3) Inspect at *[address].");
            Chunk request=chunk("request","mail1","OTHER",null,"project_fact",owner+"71(1), (2), (3) | Please confirm the officer and inspection address.");
            Chunk blank=chunk("blank","mail2","OTHER",null,"project_fact",owner+"71(1), (2), (3) | Required input | ");
            Chunk reply=chunk("reply","mail3","OTHER",null,"project_fact","For "+owner+"71(2), Specification Library 2022 Edition may be inspected at the project office.");
            List<Chunk> corpus=Arrays.asList(tender,tail,standard,request,blank,reply);
            VettingReviewPackBuilder.Result result=new VettingReviewPackBuilder(corpus).buildFactComparisons(10000,16);
            assertEquals(1,result.getTotalFactComparisons()); assertEquals(1,result.getPacks().size());
            VettingReviewPackBuilder.Pack pack=result.getPacks().get(0);
            assertEquals(Arrays.asList("target","tail"),pack.getCoreIds());
            assertEquals(new LinkedHashSet<>(Arrays.asList(owner+"71(1)",owner+"71(2)",owner+"71(3)")),new LinkedHashSet<>(pack.getReferenceIds()));
            assertEquals(new LinkedHashSet<>(Arrays.asList("target","tail","request","blank","reply","standard")),ids(pack));
            assertTrue(pack.getUnresolvedComparisonIds().isEmpty()); assertEquals("unknown",pack.getQualifiersComplete());
            for (Chunk selected:pack.getChunks()) assertSame(corpus.stream().filter(c->c.getId().equals(selected.getId())).findFirst().orElseThrow(),selected);
            assertEquals(blank.getContent(),pack.getChunks().stream().filter(c->c.getId().equals("blank")).findFirst().orElseThrow().getContent());
            assertTrue(pack.getChunks().stream().filter(c->pack.getCoreIds().contains(c.getId())).allMatch(c->"tender".equals(c.getRole())));
        }
    }

    @Test void sameNumberInTwoTenderSourcesRemainsAmbiguousAndForeignOwnerDoesNotBecomeTarget() {
        Chunk first=chunk("first","contract1","QRT","QRT71","tender","The officer shall inspect the works.");
        Chunk second=chunk("second","contract2","QRT","QRT71","tender","Another officer shall inspect separate works.");
        Chunk foreign=chunk("foreign","other","ABC","QRT99","tender","A reference mentions another namespace.");
        Chunk fact=chunk("fact","mail","OTHER",null,"project_fact","QRT71 | Confirm the officer.\nQRT99 | Confirm the address.");
        VettingReviewPackBuilder.Result result=new VettingReviewPackBuilder(Arrays.asList(first,second,foreign,fact)).buildFactComparisons(10000,16);
        assertTrue(result.getPacks().isEmpty());
        assertEquals("ambiguous_tender_source_or_scope",result.getUnresolvedFactReferences().get("QRT71"));
        assertEquals("tender_target_not_located",result.getUnresolvedFactReferences().get("QRT99"));
    }

    @Test void requiredTenderAndAllProjectMessagesAreAtomicWhileStandardBudgetOmissionIsExplicit() {
        Chunk tender=chunk("target","contract","QRT","QRT71","tender","The officer shall inspect the works.");
        Chunk fact1=chunk("fact1","mail1","OTHER",null,"project_fact","QRT71 | Required input.");
        Chunk fact2=chunk("fact2","mail2","OTHER",null,"project_fact","QRT71 | Confirmed project input.");
        Chunk standard=chunk("standard","template","QRT","QRT71","standard",String.join("",Collections.nCopies(100,"Original qualifier. ")));
        List<Chunk> corpus=Arrays.asList(tender,standard,fact1,fact2);
        int required=tender.getContent().length()+fact1.getContent().length()+fact2.getContent().length();
        VettingReviewPackBuilder.Result tooSmall=new VettingReviewPackBuilder(corpus).buildFactComparisons(required-1,16);
        assertTrue(tooSmall.getPacks().isEmpty()); assertEquals(1,tooSmall.getOmittedFactReferences().size());
        VettingReviewPackBuilder.Pack pack=new VettingReviewPackBuilder(corpus).buildFactComparisons(required,16).getPacks().get(0);
        assertEquals(new LinkedHashSet<>(Arrays.asList("target","fact1","fact2")),ids(pack));
        assertEquals(Collections.singletonList("standard"),pack.getOmittedSupportIds());
        assertEquals(Collections.singletonList("target"),pack.getUnresolvedComparisonIds());
        assertTrue(pack.getLinks().stream().anyMatch(l->"standard".equals(l.getTargetId())&&"dropped_budget".equals(l.getStatus())));
    }

    @Test void nonexistentSubparagraphAndCompetingStandardVersionsCannotBeReportedComplete() {
        Chunk tender=chunk("target","contract","QRT","QRT71","tender","(1) An officer shall inspect the works.");
        Chunk first=chunk("old","old","QRT","QRT71","standard","(1) Previous source wording.");
        Chunk second=chunk("new","new","QRT","QRT71","standard","(1) Revised source wording.");
        Chunk fact=chunk("fact","mail","OTHER",null,"project_fact","QRT71(9) | Confirm the address.");
        VettingReviewPackBuilder.Pack pack=new VettingReviewPackBuilder(Arrays.asList(tender,first,second,fact)).buildFactComparisons(10000,16).getPacks().get(0);
        assertEquals(new LinkedHashSet<>(Arrays.asList("target","fact")),ids(pack));
        assertTrue(pack.getUnresolvedReferences().stream().anyMatch(s->s.contains("subparagraph_not_located")));
        assertTrue(pack.getUnresolvedReferences().stream().anyMatch(s->s.contains("ambiguous_standard")));
        assertEquals(Collections.singletonList("target"),pack.getUnresolvedComparisonIds());
    }

    @Test void comparisonCapAndUnknownIntegerTargetAreVisibleAndEditionCodesAreNotTargets() {
        Chunk first=chunk("a","contract","QRT","QRT71","tender","The officer shall inspect the works.");
        Chunk second=chunk("b","contract","QRT","QRT72","tender","The architect shall provide access.");
        Chunk library=chunk("lib","library","LIB","LIB31.4","standard","Original library clause.");
        Chunk fact=chunk("fact","mail","OTHER",null,"project_fact","QRT71 | First input.\nQRT72 | Second input.\nQRT99 | Missing target.\nLIB2022 Edition contains the reference specification.");
        VettingReviewPackBuilder.Result result=new VettingReviewPackBuilder(Arrays.asList(first,second,library,fact)).buildFactComparisons(10000,1);
        assertEquals(3,result.getTotalFactComparisons()); assertEquals(1,result.getPacks().size());
        assertTrue(result.getOmittedFactReferences().stream().anyMatch(s->s.startsWith("QRT72")));
        assertEquals("tender_target_not_located",result.getUnresolvedFactReferences().get("QRT99"));
        assertFalse(result.getUnresolvedFactReferences().containsKey("LIB2022"));
    }

    private Set<String> ids(VettingReviewPackBuilder.Pack pack) {return pack.getChunks().stream().map(Chunk::getId).collect(Collectors.toCollection(LinkedHashSet::new));}
    private Chunk chunk(String id,String doc,String owner,String clause,String role,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileKey(owner);c.setFileName(doc+".docx");c.setSourceHash("hash-"+doc);c.setRole(role);c.setContent(text);c.setClauseId(clause);
        c.setClauseHeadingLocation(clause==null?null:"heading/"+clause);c.setAnchor("body/"+id);c.setMetadataVersion(VettingCorpus.METADATA_VERSION);
        Part p=new Part();p.setText(text);p.setAnchor(c.getAnchor());p.setBlockId(id);p.setStartOffset(0);p.setEndOffset(text.length());c.setParts(Collections.singletonList(p));return c;
    }
}
