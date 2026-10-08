package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingReviewPackBuilderTest {
    @Test void openedQualifyingListIsCompletedBySourceOrderAndNotAnArbitraryNeighbourCount() {
        Random random=new Random(823110);
        for(int trial=0;trial<12;trial++) {
            String owner=""+(char)('F'+random.nextInt(12))+"ZQ",clause=owner+(40+random.nextInt(80))+".7";
            Chunk core=chunk("core","a",owner,clause,"Except for the following personnel:\na. Amber Reviewer;\nb. Beryl Reviewer;");
            List<Chunk> corpus=new ArrayList<>();corpus.add(core);
            for(char item='c';item<='g';item++)corpus.add(chunk("list"+item,"a",owner,clause,item+". A qualified reviewer may serve separate works;"));
            Chunk second=chunk("second","b",owner,owner+"99.8","The reviewer shall attend all work requiring inspection.");corpus.add(second);
            VettingReviewPackBuilder.Result result=builder(corpus).build(Arrays.asList(core,second),Collections.emptyMap(),10000,2);
            VettingReviewPackBuilder.Pack pack=result.getPacks().get(0);
            assertEquals(7,pack.getChunks().size());
            assertTrue(pack.getChunks().stream().anyMatch(c->c.getId().equals("listg")),"A five-block list continuation must not stop at +/-1 or +/-2");
            assertTrue(pack.getUnresolvedComparisonIds().isEmpty());
            assertEquals("identified_scope_fragments_complete",pack.getScopeCoverage());
            assertEquals("unknown",pack.getQualifiersComplete(),"Matching identified fragments do not establish complete higher-level applicability");
            for(Chunk c:pack.getChunks())assertSame(corpus.stream().filter(x->x.getId().equals(c.getId())).findFirst().orElseThrow(),c);
        }
    }

    @Test void differentHeadingRevisionAndUnlinkedCommonActorNeverBecomeContinuation() {
        Chunk a=chunk("a","doc","XYZ","XYZ51.3","Except for the following personnel:\na. Amber Reviewer;\nb. Beryl Reviewer;");
        Chunk otherHeading=chunk("heading","doc","XYZ","XYZ51.4","c. The Contract Manager shall arrange independent training.");
        Chunk revision=chunk("revision","doc","XYZ","XYZ51.3","c. A reviewer may work elsewhere;");revision.setSourceHash("changed-revision");
        Chunk peer=chunk("peer","other","XYZ","XYZ52.3","The reviewer shall attend every inspection.");
        Chunk noisy=chunk("noisy","elsewhere","XYZ","XYZ53.8",repeat("Contract Manager shall administer unrelated training. ",100));
        List<Chunk> corpus=Arrays.asList(a,otherHeading,revision,peer,noisy);
        VettingReviewPackBuilder.Pack pack=builder(corpus).build(Arrays.asList(a,peer),Collections.emptyMap(),1000,2).getPacks().get(0);
        assertEquals(Arrays.asList("a","peer"),ids(pack));
        assertFalse(pack.getLinks().stream().anyMatch(l->Arrays.asList("heading","revision","noisy").contains(l.getTargetId())));
    }

    @Test void explicitTargetAndReverseFactReferenceAreSubmittedWithTheirActualRoles() {
        Chunk a=chunk("a","a","QXZ","QXZ71.9","The reviewers shall work according to LIB clause 83.4.");
        Chunk b=chunk("b","b","QXZ","QXZ72.6","Two reviewers shall attend the works.");
        Chunk target=chunk("target","library","LIB","LIB83.4.P","Reviewers may perform other work unless site attendance is required.");target.setRole("standard");
        Chunk fact=chunk("fact","mail","OTHER",null,"Reply: QXZ71.9 concerns attendance during the relevant inspection.");fact.setRole("project_fact");
        Chunk unrelated=chunk("unrelated","mail2","OTHER",null,"The project dates and unrelated deliveries are recorded here.");unrelated.setRole("project_fact");
        Map<String,List<Chunk>> references=new LinkedHashMap<>();references.put("standard",Collections.singletonList(target));references.put("project_fact",Arrays.asList(fact,unrelated));
        VettingReviewPackBuilder.Pack pack=builder(Arrays.asList(a,b,target,fact,unrelated)).build(Arrays.asList(a,b),references,2000,2).getPacks().get(0);
        assertTrue(ids(pack).containsAll(Arrays.asList("target","fact")));assertFalse(ids(pack).contains("unrelated"));
        assertEquals(Arrays.asList("a","b"),pack.getCoreIds());
        assertTrue(pack.getLinks().stream().anyMatch(l->"explicit_reference".equals(l.getRelation())&&"target".equals(l.getTargetId())));
        assertTrue(pack.getLinks().stream().anyMatch(l->"fact_clause_reference".equals(l.getRelation())&&"fact".equals(l.getTargetId())));
        assertSame(target,pack.getChunks().stream().filter(c->c.getId().equals("target")).findFirst().orElseThrow());
    }

    @Test void ambiguousStandardEditionsAreNotReintroducedAsRoleFiller() {
        Chunk a=chunk("a","contract","QXZ","QXZ71.9","The reviewers shall comply with LIB clause 83.4.");
        Chunk b=chunk("b","contract","QXZ","QXZ72.6","Reviewers shall record the inspection.");
        Chunk old=chunk("old","old","LIB","LIB83.4.P","2013 edition: Reviewers shall provide older records.");old.setRole("standard");old.setFileName("Library 2013 edition.pdf");
        Chunk current=chunk("current","new","LIB","LIB83.4.A","2022 edition: Reviewers shall provide revised records.");current.setRole("standard");current.setFileName("Library 2022 edition.pdf");
        VettingReviewPackBuilder.Pack pack=builder(Arrays.asList(a,b,old,current)).build(Arrays.asList(a,b),Collections.singletonMap("standard",Arrays.asList(old,current)),1000,2).getPacks().get(0);
        assertEquals(Arrays.asList("a","b"),ids(pack));assertTrue(pack.getUnresolvedReferences().stream().anyMatch(x->x.contains("ambiguous_source_or_edition")));
        assertEquals("unknown",pack.getQualifiersComplete());
    }

    @Test void explicitSourceEditionPrecedesSameDocumentAndTenderRolePreference() {
        for(String owner:Arrays.asList("LXR","FVZ","QRK")) {
            Chunk a=chunk("a","current",owner,owner+"81.2","The reviewer shall follow "+owner+" clause 83.4, 2013 edition.");a.setFileName("Library 2022 edition.docx");
            Chunk b=chunk("b","other",owner,owner+"82.6","The reviewer shall record the inspection.");
            Chunk current=chunk("current","current",owner,owner+"83.4.P","The reviewer shall provide revised records.");current.setFileName("Library 2022 edition.docx");
            Chunk old=chunk("old","standard",owner,owner+"83.4.A","The reviewer shall provide older records.");old.setRole("standard");old.setFileName("Library 2013 edition.pdf");
            VettingReviewPackBuilder.Pack pack=builder(Arrays.asList(a,b,current,old)).build(Arrays.asList(a,b),Collections.singletonMap("standard",Collections.singletonList(old)),2000,2).getPacks().get(0);
            assertTrue(ids(pack).contains("old"));assertFalse(ids(pack).contains("current"),"A same-document tender does not override an explicit edition");
            assertTrue(pack.getUnresolvedReferences().isEmpty());
        }
    }

    @Test void foreignOwnerMetadataAndIncidentalClauseYearCannotResolveAnExplicitEdition() {
        Chunk a=chunk("a","contract","QXZ","QXZ71.9","The reviewers shall comply with LIB clause 83.4, 2013 edition.");
        Chunk b=chunk("b","contract","QXZ","QXZ72.6","Reviewers shall record the inspection.");
        Chunk foreign=chunk("foreign","other","QXZ","LIB83.4.P","2013 edition: a foreign namespace mention shall not become its source owner.");foreign.setFileName("Other 2013 edition.docx");
        Chunk incidental=chunk("incidental","lib","LIB","LIB83.4.P","The 2013 edition of another publication is referred to here.");incidental.setFileName("Library 2022 edition.pdf");incidental.setRole("standard");
        VettingReviewPackBuilder.Pack pack=builder(Arrays.asList(a,b,foreign,incidental)).build(Arrays.asList(a,b),Collections.singletonMap("standard",Collections.singletonList(incidental)),2000,2).getPacks().get(0);
        assertEquals(Arrays.asList("a","b"),ids(pack));
        assertTrue(pack.getUnresolvedReferences().stream().anyMatch(x->x.contains("explicit_edition_target_not_located")));
    }

    @Test void parentScopeRequiresTheActualReferencedSubparagraphToBeLocated() {
        Chunk a=chunk("a","contract","QXZ","QXZ71.9","The reviewers shall comply with LIB clause 83.4(99).");
        Chunk b=chunk("b","contract","QXZ","QXZ72.6","Reviewers shall record the inspection.");
        Chunk parent=chunk("parent","lib","LIB","LIB83.4.P","(1) The reviewer shall keep records.\nClause 83.4(99) is mentioned without its provision.");parent.setRole("standard");
        VettingReviewPackBuilder.Pack absent=builder(Arrays.asList(a,b,parent)).build(Arrays.asList(a,b),Collections.emptyMap(),2000,2).getPacks().get(0);
        assertFalse(ids(absent).contains("parent"));assertTrue(absent.getUnresolvedReferences().stream().anyMatch(x->x.contains("parent_scope_candidate_subparagraph_not_located")));
        assertTrue(absent.getUnresolvedComparisonIds().contains("a"));
        Chunk located=chunk("located","lib","LIB","LIB83.4.P","(99) The reviewer shall retain the original inspection record.");located.setRole("standard");
        VettingReviewPackBuilder.Pack present=builder(Arrays.asList(a,b,located)).build(Arrays.asList(a,b),Collections.emptyMap(),2000,2).getPacks().get(0);
        assertTrue(ids(present).contains("located"));assertTrue(present.getUnresolvedReferences().isEmpty());
        Chunk nested=chunk("nested","contract","QXZ","QXZ71.9","The reviewers shall comply with LIB clause 83.4(2)(z).");
        VettingReviewPackBuilder.Pack unknownNested=builder(Arrays.asList(nested,b,located)).build(Arrays.asList(nested,b),Collections.emptyMap(),2000,2).getPacks().get(0);
        assertFalse(ids(unknownNested).contains("located"));assertFalse(unknownNested.getUnresolvedReferences().isEmpty());
    }

    @Test void rankedReverseReferencePreservesTheOtherSideBeforeUnrankedLongContext() {
        for(String owner:Arrays.asList("ZTR","FXM","QVP")) {
            Chunk first=chunk("first","a",owner,owner+"71.8","The reviewer shall allow the minimum review period.");
            Chunk second=chunk("second","b",owner,owner+"72.9","The reviewer shall keep design records.");
            Chunk third=chunk("third","c",owner,owner+"73.7","The reviewer shall coordinate the design.");
            Chunk fourth=chunk("fourth","d",owner,owner+"74.8","The reviewer shall report any missing records.");
            Chunk peer=chunk("peer","e",owner,owner+"75.9","The alternative design period shall apply according to "+owner+" clause 71.8.");
            Chunk context=chunk("context","a",owner,owner+"71.8",repeat("Unrelated detailed administrative conditions shall be recorded. ",30));
            VettingReviewPackBuilder.Pack pack=builder(Arrays.asList(first,context,second,third,fourth,peer)).build(Arrays.asList(first,second,third,fourth,peer),Collections.emptyMap(),700,2).getPacks().get(0);
            assertTrue(ids(pack).contains("peer"));assertFalse(ids(pack).contains("context"));
            assertTrue(pack.getLinks().stream().anyMatch(l->"peer".equals(l.getTargetId())&&"ranked_reverse_reference".equals(l.getRelation())));
        }
    }

    @Test void rankedNotCoreAndActuallyUnsubmittedAreDifferentAndUnknownIsPerOrigin() {
        List<Chunk> seeds=new ArrayList<>();for(int i=0;i<7;i++)seeds.add(chunk("c"+i,"same","XYZ","XYZ81.3","The reviewer shall retain original record "+i+"."));
        VettingReviewPackBuilder.Result all=builder(seeds).build(seeds,Collections.emptyMap(),2000,2);
        assertEquals(Collections.singletonList("c6"),all.getNotCoreRankedIds());assertTrue(all.getOmittedRankedIds().isEmpty(),"A submitted short-scope chunk cannot be reported as omitted");
        Chunk a=chunk("a","contract","XYZ","XYZ81.3","The reviewer shall follow LIB clause 99.1.");
        Chunk extra=chunk("extra","contract","XYZ","XYZ81.3","The supervisor shall also follow LIB clause 99.1.");
        Chunk b=chunk("b","other","XYZ","XYZ82.3","The reviewer shall record the inspection.");
        Chunk library=chunk("library","lib","LIB","LIB99.2","The reviewer shall keep records.");library.setRole("standard");
        VettingReviewPackBuilder.Pack pack=builder(Arrays.asList(a,extra,b,library)).build(Arrays.asList(a,b),Collections.emptyMap(),2000,2).getPacks().get(0);
        assertTrue(ids(pack).contains("extra"));assertTrue(pack.getUnresolvedComparisonIds().containsAll(Arrays.asList("a","extra")));
        assertTrue(pack.getLinks().stream().anyMatch(l->"extra".equals(l.getOriginId())&&"LIB99.1".equals(l.getReferenceId())&&"target_not_located".equals(l.getStatus())));
    }

    @Test void budgetDropsRemainExplicitAndCannotClaimWholeScopeOrInventAQuote() {
        Chunk a=chunk("a","doc","XYZ","XYZ51.3","Except for the following personnel:\na. Amber Reviewer;\nb. Beryl Reviewer;");
        Chunk continuation=chunk("continuation","doc","XYZ","XYZ51.3","c. "+repeat("A reviewer has additional conditional responsibilities. ",20));
        Chunk b=chunk("b","other","XYZ","XYZ52.6","The reviewer shall attend every inspection.");
        List<Chunk> corpus=Arrays.asList(a,continuation,b);
        VettingReviewPackBuilder.Pack pack=builder(corpus).build(Arrays.asList(a,b),Collections.emptyMap(),250,2).getPacks().get(0);
        assertTrue(pack.getContentChars()<=250);assertEquals(Arrays.asList("a","b"),ids(pack));
        assertTrue(pack.getOmittedScopeChunks().get("a").contains("continuation"));assertTrue(pack.getOmittedSupportIds().contains("continuation"));
        assertTrue(pack.getUnresolvedComparisonIds().contains("a"));assertEquals("unknown",pack.getQualifiersComplete());
        assertEquals(a.getContent(),pack.getChunks().get(0).getContent());assertEquals(a.getParts(),pack.getChunks().get(0).getParts());
    }

    @Test void totalPackCapKeepsRankedPrefixAndReportsTheUnreviewedRemainder() {
        List<Chunk> seeds=new ArrayList<>();for(int i=0;i<10;i++)seeds.add(chunk("c"+i,"d"+i,"XYZ","XYZ"+(80+i)+".3","The reviewer shall attend the required work."));
        VettingReviewPackBuilder.Result result=builder(seeds).build(seeds,Collections.emptyMap(),1000,99);
        assertEquals(2,result.getPacks().size());assertEquals(2,result.getMaxPacks());assertEquals(Arrays.asList("c6","c7","c8","c9"),result.getOmittedRankedIds());
        assertEquals(Arrays.asList("c0","c1","c2"),result.getPacks().get(0).getCoreIds());assertEquals(Arrays.asList("c3","c4","c5"),result.getPacks().get(1).getCoreIds());
        assertTrue(result.getPacks().stream().allMatch(p->p.getCoreIds().size()>=2&&p.getCoreIds().size()<=3));
    }

    @Test void splitOriginalBlockClosureRetainsUtf16OffsetsAndOverlappingFullChunks() {
        String full="A reviewer shall record an inspection \uD83D\uDE80 with all original details and source references.";
        Chunk a=chunk("a","doc","XYZ","XYZ71.6",full.substring(0,58)),tail=chunk("tail","doc","XYZ","XYZ71.6",full.substring(40));
        Part left=a.getParts().get(0);left.setBlockId("original");left.setStartOffset(0);left.setEndOffset(58);
        Part right=tail.getParts().get(0);right.setBlockId("original");right.setStartOffset(40);right.setEndOffset(full.length());
        Chunk b=chunk("b","other","XYZ","XYZ72.6","The reviewer shall attend the recorded inspection.");
        VettingReviewPackBuilder.Pack pack=builder(Arrays.asList(a,tail,b)).build(Arrays.asList(a,b),Collections.emptyMap(),1000,2).getPacks().get(0);
        assertTrue(ids(pack).contains("tail"));assertEquals(a.getContent().length()+tail.getContent().length()+b.getContent().length(),pack.getContentChars());
        assertSame(right,pack.getChunks().stream().filter(c->c.getId().equals("tail")).findFirst().orElseThrow().getParts().get(0));
        assertEquals(full.substring(40),right.getText());assertEquals(40,right.getStartOffset());
    }

    @Test void flattenedContentsDoNotConsumePackCoresAndTamperedRetrievedContentIsRejected() {
        Chunk toc=chunk("toc","doc","XYZ","XYZ71.6","XYZ81.1 Minimum review days 17\nXYZ81.2 Minimum review days 19\nXYZ81.3 Minimum review days 23");
        Chunk a=chunk("a","doc","XYZ","XYZ71.6","The reviewer shall record the inspection."),b=chunk("b","other","XYZ","XYZ72.6","The reviewer shall attend the inspection.");
        VettingReviewPackBuilder builder=builder(Arrays.asList(toc,a,b));
        VettingReviewPackBuilder.Result result=builder.build(Arrays.asList(toc,a,b),Collections.emptyMap(),1000,2);
        assertEquals(Collections.singletonList("toc"),result.getExcludedRankedIds());assertFalse(ids(result.getPacks().get(0)).contains("toc"));
        Chunk tampered=chunk("a","doc","XYZ","XYZ71.6","The reviewer shall perform invented obligations.");
        assertThrows(IllegalArgumentException.class,()->builder.build(Arrays.asList(tampered,b),Collections.emptyMap(),1000,2));
    }

    private VettingReviewPackBuilder builder(List<Chunk> chunks) {return new VettingReviewPackBuilder(chunks);}
    private List<String> ids(VettingReviewPackBuilder.Pack pack) {List<String> ids=new ArrayList<>();for(Chunk c:pack.getChunks())ids.add(c.getId());return ids;}
    private String repeat(String s,int n) {StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append(s);return b.toString();}
    private Chunk chunk(String id,String doc,String owner,String clause,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(doc);c.setFileKey(owner);c.setFileName(doc+".docx");c.setSourceHash("hash-"+doc);c.setRole("tender");c.setContent(text);c.setClauseId(clause);c.setClauseHeadingLocation(clause==null?null:"heading/"+clause);c.setAnchor("body/"+id);c.setMetadataVersion(VettingCorpus.METADATA_VERSION);
        Part p=new Part();p.setText(text);p.setAnchor(c.getAnchor());p.setBlockId(id);p.setStartOffset(0);p.setEndOffset(text.length());c.setParts(Collections.singletonList(p));return c;
    }
}
