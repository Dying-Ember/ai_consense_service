package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class VettingContextBuilderTest {
    @Test void selectionJsonRoundtripPreservesOrderedDroppedAndUnresolvedIds() {
        VettingContextBuilder.Selection selection = new VettingContextBuilder.Selection();
        selection.setDroppedIds(new LinkedHashSet<>(Arrays.asList("z", "a", "m")));
        selection.setUnresolvedComparisonIds(new LinkedHashSet<>(Arrays.asList("c", "b", "a")));
        String original = JsonUtils.write(selection);
        VettingContextBuilder.Selection restored = JsonUtils.read(original, VettingContextBuilder.Selection.class);
        assertEquals(new ArrayList<>(selection.getDroppedIds()), new ArrayList<>(restored.getDroppedIds()));
        assertEquals(new ArrayList<>(selection.getUnresolvedComparisonIds()), new ArrayList<>(restored.getUnresolvedComparisonIds()));
        assertEquals(LinkedHashSet.class, restored.getDroppedIds().getClass());
        assertEquals(LinkedHashSet.class, restored.getUnresolvedComparisonIds().getClass());
        assertEquals(original, JsonUtils.write(restored));
        assertEquals(VettingCorpus.hash(original), VettingCorpus.hash(JsonUtils.write(restored)));
    }

    @Test void randomSourceEntitiesAndNamespacesKeepRankedPairsWithoutLowerRankedNoise() {
        Random random=new Random(6193);
        String[] names={"Amber","Birch","Cobalt","Dawn","Elm","Frost"},roles={"Observer","Planner","Supervisor"};
        for(int iteration=0;iteration<20;iteration++) {
            String first=names[random.nextInt(names.length)],last=roles[random.nextInt(roles.length)],entity=first+" "+last;
            String acronym=""+first.charAt(0)+last.charAt(0),owner=""+(char)('A'+random.nextInt(26))+(char)('A'+random.nextInt(26))+(char)('A'+random.nextInt(26));
            Chunk a=chunk("a","doc",owner,owner+(30+iteration)+".71","body/3",pad("The "+entity+" ("+acronym+") total 3 shall be appointed.",700));
            Chunk b=chunk("b","doc",owner,owner+(70+iteration)+".29","body/7",pad("The "+acronym+" shall be appointed full time with a minimum of 4 positions.",700));
            Chunk noise=chunk("noise","other",owner,owner+"4.71","body/2",pad("The material log shall be maintained in the office.",1300));
            VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,b,noise)).build("resource appointment",Arrays.asList(a,b,noise),Collections.emptyMap(),1500);
            assertEquals(new LinkedHashSet<>(Arrays.asList("a","b")),ids(selected));
            assertEquals(1400,selected.getContentChars()); assertTrue(selected.getDroppedIds().contains("noise"));
            assertTrue(selected.getGroups().stream().anyMatch(g -> "shared_entity".equals(g.getRelation())&&"selected".equals(g.getStatus())));
            assertSame(a,selected.getChunks().get(0));assertSame(b,selected.getChunks().get(1));
        }
    }

    @Test void accordingToReferenceKeepsTheTargetAndAdjacentExceptionWithoutCrossingScope() {
        Chunk a=chunk("a","doc","XYZ","XYZ41.7","body/8","The liaison shall attend according to XYZ83.27.");
        Chunk b=chunk("b","doc","XYZ","XYZ83.27.P","body/20","The liaison shall attend for the scheduled site inspections.");
        Chunk exception=chunk("except","doc","XYZ","XYZ83.27.P","body/21","Except when the site is closed, the liaison shall attend the inspection.");
        exception.setClauseHeadingLocation(b.getClauseHeadingLocation());
        Chunk next=chunk("unrelated","doc","XYZ","XYZ83.28","body/22","The warehouse permits shall be issued for a different scope.");
        List<Chunk> corpus=Arrays.asList(a,b,exception,next);
        VettingContextBuilder.Selection selected=new VettingContextBuilder(corpus).build("attendance condition",Collections.singletonList(a),Collections.emptyMap(),10000);
        assertEquals(new LinkedHashSet<>(Arrays.asList("a","b","except")),ids(selected)); assertFalse(ids(selected).contains("unrelated"));
        assertTrue(selected.getGroups().get(0).getUnresolvedReferences().isEmpty());
        assertEquals("local_window",selected.getGroups().get(0).getScopeCoverage());assertEquals("unknown",selected.getGroups().get(0).getQualifiersComplete());
        for(Chunk c:selected.getChunks()) {assertSame(c,corpus.stream().filter(x -> x.getId().equals(c.getId())).findFirst().get());assertEquals(c.getContent(),c.getParts().get(0).getText());}
    }

    @Test void anOversizedPairIsDroppedTogetherWithUnknownScopeAndNoPartialQuote() {
        String emoji="\uD83D\uDE00";
        Chunk a=chunk("a","doc","RST","RST51.8","body/4",pad("The Azure Observer (AO) total 2 "+emoji+" shall be provided.",1400));
        Chunk b=chunk("b","doc","RST","RST82.13","body/7",pad("The AO shall be provided, at least 1.",1400));
        VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,b)).build("appointment",Arrays.asList(a,b),Collections.emptyMap(),1500);
        assertTrue(selected.getChunks().isEmpty()); assertEquals(new HashSet<>(Arrays.asList("a","b")),selected.getDroppedIds());
        assertEquals("dropped_budget",selected.getGroups().get(0).getStatus()); assertEquals(2800,selected.getGroups().get(0).getIncrementalChars());
        assertEquals("unknown",selected.getGroups().get(0).getQualifiersComplete());assertEquals(1400,a.getContent().length());assertTrue(a.getContent().contains(emoji));
    }

    @Test void contentsAndUnrelatedReferenceNoiseCannotReserveBudgetAheadOfThePair() {
        Chunk a=chunk("a","doc","QRT","QRT11.7","body/4",pad("The Azure Planner (AP) shall total 2 appointments.",700));
        Chunk b=chunk("b","doc","QRT","QRT31.9","body/9",pad("The AP shall total at least 1 appointment.",700));
        Chunk toc=chunk("toc","standard","LIB","LIB19.2","body/contents","Minimum days for Azure Planner (AP) ................. 51\nAppointments ....................................... 73");toc.setRole("standard");
        Chunk unrelated=chunk("reference","standard","LIB","LIB42.3","body/30",pad("The records for waterproofing shall be stored in the archive.",1300));unrelated.setRole("standard");
        Map<String,List<Chunk>> refs=Collections.singletonMap("standard",Arrays.asList(toc,unrelated));
        VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,b,toc,unrelated)).build("appointments",Arrays.asList(a,b),refs,10000);
        assertEquals(new LinkedHashSet<>(Arrays.asList("a","b")),ids(selected));assertEquals(1400,selected.getContentChars());
    }

    @Test void theSameShortFormWithDifferentExplicitMeaningsDoesNotInventAnEntityPair() {
        Chunk a=chunk("a","one","ABC","ABC18.7","body/8","The Amber Surveyor (AS) shall assist the manager.");
        Chunk b=chunk("b","two","ABC","ABC91.4","body/9","The Architectural Supervisor (AS) shall represent the manager.");
        Chunk shortOnly=chunk("short","three","ABC","ABC97.4","body/12","The AS shall maintain the office register.");
        VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,b,shortOnly)).build("personnel scope",Arrays.asList(a,b,shortOnly),Collections.emptyMap(),10000);
        assertTrue(selected.getGroups().stream().noneMatch(g -> "shared_entity".equals(g.getRelation())));
    }

    @Test void aLongClauseOnlyContributesLocalNeighboursAndRepeatedGroupsDoNotDoubleCount() {
        List<Chunk> corpus=new ArrayList<>();
        Chunk a=chunk("a","doc","XYZ","XYZ21.9","body/0",pad("The Birch Observer (BO) shall total 2 appointments.",700));corpus.add(a);
        for(int i=1;i<30;i++) {Chunk c=chunk("window"+i,"doc","XYZ","XYZ21.9","body/"+i,pad("Local source material for inspection.",700));c.setClauseHeadingLocation(a.getClauseHeadingLocation());corpus.add(c);}
        Chunk b=chunk("b","doc","XYZ","XYZ83.4","body/40",pad("The BO shall total at least 1 appointment.",700));corpus.add(b);
        VettingContextBuilder.Selection selected=new VettingContextBuilder(corpus).build("appointment",Arrays.asList(a,b,a),Collections.emptyMap(),3000);
        assertTrue(ids(selected).containsAll(Arrays.asList("a","b","window1")));assertFalse(ids(selected).contains("window29"));
        assertEquals(selected.getChunks().stream().mapToInt(c -> c.getContent().length()).sum(),selected.getContentChars());
        assertEquals(ids(selected).size(),selected.getChunks().size());
    }

    @Test void ambiguousStandardEditionsLeaveTheReferenceUnresolvedRatherThanChooseTheLatest() {
        Chunk a=chunk("a","tender","ABC","ABC31.8","body/2","The review shall follow LIB clause 71.9.");
        Chunk old=chunk("old","standard-old","LIB","LIB71.9","page3","The reviewer shall provide a minimum processing allowance.");old.setRole("standard");old.setFileName("Library 2031 edition.pdf");
        Chunk newer=chunk("new","standard-new","LIB","LIB71.9","page3","The reviewer shall provide another processing allowance.");newer.setRole("standard");newer.setFileName("Library 2037 edition.pdf");
        VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,old,newer)).build("review allowance",Collections.singletonList(a),Collections.singletonMap("standard",Arrays.asList(newer,old)),10000);
        assertEquals(Collections.singleton("a"),ids(selected));assertTrue(selected.getUnresolvedComparisonIds().contains("a"));
        assertEquals(Collections.singletonList("LIB71.9"),selected.getGroups().get(0).getUnresolvedReferences());
    }

    @Test void overlappingGroupsDoNotTurnOneSubmittedPartnerIntoACompletedDroppedPair() {
        Chunk a=chunk("a","doc","XYZ","XYZ19.8","body/1",pad("The Azure Observer (AO) shall be appointed.",2000));
        Chunk b=chunk("b","doc","XYZ","XYZ48.2","body/2",pad("The AO shall be appointed alongside the Birch Planner (BP).",500));
        Chunk c=chunk("c","doc","XYZ","XYZ91.3","body/3",pad("The BP shall be appointed.",500));
        VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,b,c)).build("appointments",Arrays.asList(a,b,c),Collections.emptyMap(),1200);
        assertEquals(new LinkedHashSet<>(Arrays.asList("b","c")),ids(selected));
        VettingContextBuilder.GroupTrace dropped=selected.getGroups().stream().filter(g -> g.getCoreIds().contains("a")).findFirst().get();
        assertEquals("dropped_budget",dropped.getStatus());assertEquals(1,dropped.getSubmittedCoreCount());assertFalse(dropped.isFullySubmitted());
        assertTrue(selected.getGroups().stream().filter(g -> g.getCoreIds().equals(Arrays.asList("b","c"))).allMatch(VettingContextBuilder.GroupTrace::isFullySubmitted));
        assertTrue(selected.getGroups().stream().noneMatch(g -> "single_seed".equals(g.getRelation())));
    }

    @Test void rareEntityAndNearbyResponsibilityWinOverCommonEarlierEntitiesAndUnrelatedDuties() {
        Chunk a=chunk("a","doc","XYZ","XYZ11.8","body/1",pad("The Contract Manager (CM) and Scheduling Coordinator (SC) liaise with the Rare Inspector (RI), who shall verify samples.",650));
        Chunk common=chunk("common","doc","XYZ","XYZ31.2","body/2",pad("The CM shall archive the records.",650));
        Chunk coordinator=chunk("coordinator","doc","XYZ","XYZ41.2","body/3",pad("The SC shall archive the records.",650));
        Chunk wrong=chunk("wrong","doc","XYZ","XYZ51.2","body/4",pad("The RI shall maintain the workplace register.",650));
        Chunk peer=chunk("peer","doc","XYZ","XYZ61.2","body/5",pad("The RI shall verify the samples before inspection.",650));
        List<Chunk> corpus=new ArrayList<>(Arrays.asList(a,common,coordinator,wrong,peer));
        for(int i=0;i<4;i++) corpus.add(chunk("frequent"+i,"doc","XYZ","XYZ"+(71+i)+".2","body/"+(6+i),"The CM and SC shall archive the monthly records."));
        VettingContextBuilder.Selection selected=new VettingContextBuilder(corpus).build("verify samples",Collections.singletonList(a),Collections.emptyMap(),1400);
        assertEquals(new LinkedHashSet<>(Arrays.asList("a","peer")),ids(selected));
        assertTrue(selected.getGroups().stream().anyMatch(g -> "shared_entity_extension".equals(g.getRelation())&&"local_terms_shared".equals(g.getRelationStrength())));
        assertEquals(1300,selected.getContentChars());
    }

    @Test void explicitlyAdoptedEditionRetainsItsContinuingBlocksButNotOtherVersions() {
        Chunk a=chunk("a","tender","ABC","ABC31.8","body/2","The processing shall follow LIB clause 71.9, 2031 edition.");
        Chunk old=chunk("old","standard-old","LIB","LIB71.9","page3","2031 edition. The reviewer shall provide the processing allowance.");old.setRole("standard");
        Chunk continuation=chunk("cont","standard-old","LIB","LIB71.9","page4","Except for revised submissions, the allowance applies to the first submission.");continuation.setRole("standard");continuation.setClauseHeadingLocation(old.getClauseHeadingLocation());
        Chunk newer=chunk("new","standard-new","LIB","LIB71.9","page3","2037 edition. Another processing allowance applies.");newer.setRole("standard");
        VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,old,continuation,newer)).build("processing",Collections.singletonList(a),Collections.singletonMap("standard",Arrays.asList(newer,continuation,old)),10000);
        assertEquals(new LinkedHashSet<>(Arrays.asList("a","old","cont")),ids(selected));assertFalse(selected.getUnresolvedComparisonIds().contains("a"));
    }

    @Test void ambiguousSuffixClauseVersionsCannotBeReintroducedBySharedEntityFiller() {
        for(String oldSuffix:Arrays.asList(".P","")) {
            Chunk a=chunk("a","tender","ABC","ABC31.8","body/2","The Azure Observer (AO) shall be appointed under LIB clause 71.9.");
            Chunk old=chunk("old","standard-old","LIB","LIB71.9"+oldSuffix,"page3","The AO shall provide the review allowance.");old.setRole("standard");old.setFileName("Library 2031 edition.pdf");
            Chunk newer=chunk("new","standard-new","LIB","LIB71.9.A","page3","The Azure Observer shall provide another review allowance.");newer.setRole("standard");newer.setFileName("Library 2037 edition.pdf");
            VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,old,newer)).build("allowance",Collections.singletonList(a),Collections.singletonMap("standard",Arrays.asList(newer,old)),10000);
            assertEquals(Collections.singleton("a"),ids(selected));assertTrue(selected.getUnresolvedComparisonIds().contains("a"));
        }
    }

    @Test void sourceKnownIntegerClauseReferencesAndFactsRemainContextWithoutMistakingAnEditionCode() {
        Chunk a=chunk("a","tender","QRT","QRT8","body/2","The tender shall be submitted with the schedule under QRT6 and library LIB2041.");
        Chunk target=chunk("target","tender","QRT","QRT6","body/8","The schedule shall show the adopted submission procedure.");
        Chunk fact=chunk("fact","email","FACT",null,"body/1","The project email confirms that QRT8 uses a separate submission procedure.");fact.setRole("project_fact");
        Chunk unrelated=chunk("unrelated","email","FACT",null,"body/2","The email records another procedure for QRT12.");unrelated.setRole("project_fact");
        Chunk library=chunk("lib","standard","LIB","LIB16.4","page3","The general library contains other requirements.");library.setRole("standard");
        VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,target,fact,unrelated,library)).build("submission",Collections.singletonList(a),Collections.singletonMap("project_fact",Arrays.asList(unrelated,fact)),10000);
        assertEquals(new LinkedHashSet<>(Arrays.asList("a","target","fact")),ids(selected));assertTrue(selected.getUnresolvedComparisonIds().isEmpty());
        assertTrue(selected.getGroups().stream().allMatch(g -> !g.getCoreIds().contains("fact")),"An email is supporting context, never a competing obligation.");
    }

    @Test void unrankedCommonActorWindowsCannotDisplaceRankedClausesAndTheirException() {
        Random random=new Random(98271);
        for(int i=0;i<12;i++) {
            String entity="Amber "+(i%2==0?"Planner":"Observer"),alias=i%2==0?"AP":"AO",owner="Q"+(char)('A'+random.nextInt(26));
            Chunk first=chunk("first","doc",owner,owner+"19.7","body/1",pad("The "+entity+" ("+alias+") shall allow time for processing the submission.",500));
            Chunk second=chunk("second","doc",owner,owner+"81.4","body/9",pad("The "+alias+" shall allow time for processing revised submissions.",500));
            Chunk exception=chunk("exception","doc",owner,owner+"81.4","body/10",pad("Except when already processed, the revised submission requires an additional allowance.",500));exception.setClauseHeadingLocation(second.getClauseHeadingLocation());
            Chunk common=chunk("common","doc",owner,owner+"42.7","body/2",pad("The "+alias+" shall attend workplace training and maintain the staff roster.",2500));
            VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(first,second,exception,common)).build("processing submission time",Arrays.asList(first,second,exception),Collections.emptyMap(),1600);
            assertEquals(new LinkedHashSet<>(Arrays.asList("first","second","exception")),ids(selected));assertEquals(1500,selected.getContentChars());
            assertTrue(selected.getGroups().stream().noneMatch(g -> g.getCoreIds().contains("common")&&g.isFullySubmitted()));
        }
    }

    @Test void missingLocalQualifierIsExplicitlyPartialAndDoesNotClaimACompleteComparison() {
        Chunk a=chunk("a","doc","XYZ","XYZ19.7","body/1",pad("The Amber Planner (AP) shall review the submission.",700));
        Chunk exception=chunk("exception","doc","XYZ","XYZ19.7","body/2",pad("Except when the approval is deferred, the submission must be reviewed.",1000));exception.setClauseHeadingLocation(a.getClauseHeadingLocation());
        Chunk b=chunk("b","doc","XYZ","XYZ81.4","body/9",pad("The AP shall review the revised submission.",700));
        VettingContextBuilder.Selection selected=new VettingContextBuilder(Arrays.asList(a,exception,b)).build("submission review",Arrays.asList(a,b),Collections.emptyMap(),1500);
        assertEquals(new LinkedHashSet<>(Arrays.asList("a","b")),ids(selected));assertEquals(1400,selected.getContentChars());
        assertEquals("selected_partial_context",selected.getGroups().get(0).getStatus());assertFalse(selected.getGroups().get(0).isFullySubmitted());
        assertTrue(selected.getUnresolvedComparisonIds().containsAll(Arrays.asList("a","b")));assertTrue(selected.getDroppedIds().contains("exception"));
    }
    @Test void flattenedOwnerContentsAndSplitPdfContentsCannotConsumeBudgetButObligationTablesRemain() {
        Random random=new Random(947201);
        for(int i=0;i<12;i++) {
            String owner=""+(char)('A'+random.nextInt(26))+(char)('A'+random.nextInt(26))+(char)('A'+random.nextInt(26));
            String first=owner+".D"+(17+i)+".41",second=owner+".D"+(35+i)+".72",third=owner+".D"+(59+i)+".81";
            Chunk flattened=chunk("flat","doc",owner,null,"body/contents",first+" MINIMUM REVIEW DAYS 81\n"+second+" SITE APPOINTMENTS 113\n"+third+" SAFETY ATTENDANCE 142");
            Chunk split=chunk("split","doc",owner,first,"page2","CONTENTS\n1\nBACKGROUND\n4\n2\nREVIEW PLAN\n7\n3\nSITE ATTENDANCE\n12");
            Chunk obligation=chunk("real","doc",owner,second,"body/10",first+" The reviewer shall allow the specified days 81\n"+second+" The supervisor must attend the site 113\n"+third+" Safety staff shall maintain the records 142");
            List<Chunk> corpus=Arrays.asList(flattened,split,obligation);
            VettingContextBuilder.Selection selected=new VettingContextBuilder(corpus).build("review attendance",corpus,Collections.emptyMap(),10000);
            assertEquals(Collections.singleton("real"),ids(selected));assertSame(obligation,selected.getChunks().get(0));assertEquals(obligation.getContent().length(),selected.getContentChars());
        }
    }

    private static Set<String> ids(VettingContextBuilder.Selection selected) {return selected.getChunks().stream().map(Chunk::getId).collect(Collectors.toCollection(LinkedHashSet::new));}
    private static String pad(String text,int size) {StringBuilder b=new StringBuilder(text);while(b.length()<size)b.append(' ');return b.toString();}
    private static Chunk chunk(String id,String document,String owner,String clause,String location,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId(document);c.setFileKey(owner);c.setFileName(owner+".docx");c.setRole("tender");
        c.setContent(text);c.setClauseId(clause);c.setClauseHeadingLocation(clause==null?null:location);c.setAnchor(location);c.setSourceHash("source-snapshot");
        Part part=new Part();part.setBlockId(location);part.setAnchor(location);part.setText(text);part.setStartOffset(0);part.setEndOffset(text.length());c.setParts(Collections.singletonList(part));return c;
    }
}
