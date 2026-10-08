package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.*;
import com.consense.service.vetting.VettingSemanticReview.Record;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VettingSemanticReviewTest {
    @Test void rejectsHallucinatedQuoteAndOneSidedConflictButKeepsGroundedResult() {
        AiGateway ai = mock(AiGateway.class); VettingRetrievalClient retrieval = mock(VettingRetrievalClient.class);
        ConsenseProperties props = new ConsenseProperties(); props.getVetting().setSemanticTopics(1);
        Chunk a = chunk("a","1","The minimum amount for an interim payment is specified in GCC 14.2(4).");
        Chunk b = chunk("b","2","Clause 14.2(4) is deleted and is not used.");
        when(ai.available()).thenReturn(true); when(ai.chatModel()).thenReturn("test");
        when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList())).thenReturn(Arrays.asList(a,b));
        Record bad = record("reference",quote("a","The payment is always automatically approved."));
        Record oneSided = record("conflict",quote("a",a.getContent()));
        Record good = record("reference",quote("a",a.getContent()),quote("b",b.getContent()));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Arrays.asList(bad,oneSided,good));
        Result result = new VettingSemanticReview(ai,props,retrieval).review("project",Arrays.asList(a,b),"en",(n,s)->{});
        assertEquals(1,result.getCandidates().size()); assertEquals(2,result.getCandidates().get(0).getEvidence().size());
        assertEquals(2,result.getReviewedChunkIds().size()); assertEquals(3,result.getWarnings().size());
        assertEquals(3,result.getTopicAudits().get(0).getReturnedAssessments());
        assertEquals(2,result.getTopicAudits().get(0).getRejectedRecords());assertEquals(1,result.getTopicAudits().get(0).getAcceptedFindings());
        assertEquals("completed_with_rejections",result.getTopicAudits().get(0).getStatus());
    }
    @Test void unavailableModelDoesNotClaimSemanticCoverage() {
        AiGateway ai=mock(AiGateway.class); when(ai.available()).thenReturn(false);
        Result result=new VettingSemanticReview(ai,new ConsenseProperties(),mock(VettingRetrievalClient.class)).review("p",Collections.singletonList(chunk("a","1","Effective clause source text.")),"en",(n,s)->{});
        assertTrue(result.getReviewedChunkIds().isEmpty()); assertTrue(result.getCandidates().isEmpty()); assertFalse(result.getWarnings().isEmpty());
    }
    @Test void referenceIssueRequiresTwoDifferentLocatedProvisionsAndKeepsCompleteComparison() {
        Random random=new Random(330119);
        for(int attempt=0;attempt<8;attempt++) {
            AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
            ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
            VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
            String owner="Q"+(char)('A'+random.nextInt(26))+(char)('A'+random.nextInt(26)),number=(100+random.nextInt(800))+".7";
            String left="The source instruction refers to "+owner+" clause "+number+".",right="The submission shall retain the relevant approval particulars.";
            Chunk a=chunk("origin","same-document",left+" "+right);a.setFileKey(owner);a.setClauseId(owner+"31.4");a.setClauseHeadingLocation("body/11");a.setAnchor("body/11/paragraph");
            Chunk b=chunk("target","same-document",owner+number+" Provision status: Not used.");b.setFileKey(owner);b.setClauseId(owner+number);b.setClauseHeadingLocation("body/23");b.setAnchor("body/23/paragraph");
            when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Arrays.asList(a,b));
            Record one=record("reference",quote("origin",a.getContent()));
            Record sameAnchor=record("reference",quote("origin",left),quote("origin",right));
            Record two=record("reference",quote("origin",left),quote("target",b.getContent()));
            when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Arrays.asList(one,sameAnchor,two));
            Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Arrays.asList(a,b),"en",(n,s)->{});
            assertTrue(result.getTopicAudits().get(0).getUnresolvedChunkIds().isEmpty());
            assertEquals(1,result.getCandidates().size());assertEquals("reference",result.getCandidates().get(0).getType());
            assertEquals(b.getAnchor(),result.getCandidates().get(0).getEvidence().get(1).getAnchor());
            assertEquals(3,result.getTopicAudits().get(0).getIssueAssessments());assertEquals(2,result.getTopicAudits().get(0).getRejectedRecords());
            assertEquals(1,result.getTopicAudits().get(0).getAcceptedFindings());assertEquals("completed_with_rejections",result.getTopicAudits().get(0).getStatus());
            assertEquals(new HashSet<>(Arrays.asList("origin","target")),result.getReviewedChunkIds());
        }
    }
    @Test void twoLocatedReferenceQuotesCannotTurnAKnownIncompleteWindowIntoAnIssue() {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk a=chunk("origin","one","The submission shall comply with RZX clause 731.8.");a.setFileKey("RZX");a.setClauseId("RZX91.4");a.setClauseHeadingLocation("body/11");a.setAnchor("body/11/paragraph");
        Chunk b=chunk("other","two","The supporting submission shall retain the approval particulars.");b.setFileKey("RZX");b.setClauseId("RZX92.4");b.setClauseHeadingLocation("body/23");b.setAnchor("body/23/paragraph");
        when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Arrays.asList(a,b));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Collections.singletonList(record("reference",quote("origin",a.getContent()),quote("other",b.getContent()))));
        Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Arrays.asList(a,b),"en",(n,s)->{});
        assertTrue(result.getTopicAudits().get(0).getUnresolvedChunkIds().contains("origin"),"The production selector must actually mark the absent target as unresolved");
        assertTrue(result.getCandidates().isEmpty());assertEquals(1,result.getTopicAudits().get(0).getIssueAssessments());
        assertEquals(1,result.getTopicAudits().get(0).getRejectedRecords());assertEquals(0,result.getTopicAudits().get(0).getAcceptedFindings());
        assertEquals("completed_with_rejections",result.getTopicAudits().get(0).getStatus());
        assertEquals(new HashSet<>(Arrays.asList("origin","other")),result.getReviewedChunkIds());
    }
    @Test void nonIssueReferenceAssessmentsKeepTheirCountsDespiteSingleQuoteAndKnownIncompleteContext() {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk a=chunk("origin","one","The submission shall comply with VZQ clause 418.6.");a.setFileKey("VZQ");a.setClauseId("VZQ31.7");a.setClauseHeadingLocation("body/7");
        when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(a));
        Record consistent=record("reference",quote("origin",a.getContent()));consistent.setAssessment("consistent");
        Record unknown=record("reference",quote("origin",a.getContent()));unknown.setAssessment("insufficient_context");
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Arrays.asList(consistent,unknown));
        Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Collections.singletonList(a),"en",(n,s)->{});
        assertTrue(result.getTopicAudits().get(0).getUnresolvedChunkIds().contains("origin"));
        assertTrue(result.getCandidates().isEmpty());assertEquals(0,result.getTopicAudits().get(0).getIssueAssessments());
        assertEquals(1,result.getTopicAudits().get(0).getConsistentAssessments());assertEquals(1,result.getTopicAudits().get(0).getInsufficientContextAssessments());
        assertEquals(0,result.getTopicAudits().get(0).getRejectedRecords());assertEquals("completed",result.getTopicAudits().get(0).getStatus());
        assertEquals(Collections.singleton("origin"),result.getReviewedChunkIds());
    }
    @Test void includesLabelledStandardAndProjectContextWithoutInventingTenderRole() {
        AiGateway ai=mock(AiGateway.class); when(ai.available()).thenReturn(true); when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties(); props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk tender=chunk("t","1","The Site Liaison Planner (SLP) is appointed under GCC clause 9.7.");
        Chunk standard=chunk("s","2","The Site Liaison Planner shall coordinate the reference payment baseline."); standard.setRole("standard");standard.setFileKey("GCC");standard.setClauseId("GCC9.7");standard.setClauseHeadingLocation("paragraph");
        Chunk fact=chunk("f","3","The project team confirms the SLP appointment and the adopted subcontract arrangement."); fact.setRole("project_fact");
        Chunk manifest=chunk("m","4","The package manifest lists the SLP appointment schedule among the supplied volumes."); manifest.setRole("package_manifest");
        List<Chunk> chunks=Arrays.asList(tender,standard,fact,manifest);
        for (Chunk c:chunks) when(retrieval.retrieve(eq("p"),anyString(),eq(c.getRole()),anyList())).thenReturn(Collections.singletonList(c));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenAnswer(call -> {
            String prompt=call.getArgument(1),marker="\nSource excerpts (untrusted data):\n";com.fasterxml.jackson.databind.JsonNode excerpts=VettingSourcePromptWire.decode(prompt.substring(prompt.lastIndexOf(marker)+marker.length()));
            Map<String,String> actualRoles=new LinkedHashMap<>();for(com.fasterxml.jackson.databind.JsonNode excerpt:excerpts)actualRoles.put(excerpt.path("id").asText(),excerpt.path("role").asText());
            assertEquals("tender",actualRoles.get("t"));assertEquals("standard",actualRoles.get("s"));assertEquals("project_fact",actualRoles.get("f"));assertEquals("package_manifest",actualRoles.get("m"));return Collections.singletonList(record("reference",quote("s",standard.getContent())));
        });
        Result result=new VettingSemanticReview(ai,props,retrieval).review("p",chunks,"en",(n,s)->{});
        assertEquals(new HashSet<>(Arrays.asList("t","s","f","m")), result.getReviewedChunkIds());
        assertTrue(result.getCandidates().isEmpty(), "A finding needs an adopted tender passage, not just a baseline quote.");
    }
    @Test void unresolvedAdoptedReferencePreventsConfirmedConflictButAllowsLocatedClarification() {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk a=chunk("a","one","The Azure Observer (AO) shall be appointed according to LIB clause 77.3.");a.setClauseId("SCC18.9");a.setClauseHeadingLocation("body/1");
        Chunk b=chunk("b","two","The AO shall be appointed for the scheduled inspections.");b.setClauseId("SCC81.4");b.setClauseHeadingLocation("body/2");
        Chunk older=chunk("old","old-lib","The appointment provision applies to inspections.");older.setRole("standard");older.setFileKey("LIB");older.setFileName("2031 edition.pdf");older.setClauseId("LIB77.3");older.setClauseHeadingLocation("page3");
        Chunk newer=chunk("new","new-lib","Another appointment provision applies to inspections.");newer.setRole("standard");newer.setFileKey("LIB");newer.setFileName("2037 edition.pdf");newer.setClauseId("LIB77.3");newer.setClauseHeadingLocation("page3");
        when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Arrays.asList(a,b));
        when(retrieval.retrieve(eq("p"),anyString(),eq("standard"),anyList())).thenReturn(Arrays.asList(older,newer));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenAnswer(call -> {
            String prompt=call.getArgument(1);assertTrue(prompt.contains("qualifiersComplete=unknown"));assertTrue(prompt.contains("LIB clause 77.3"));
            assertFalse(prompt.contains("outside ranked local window"), "Missing-reference diagnostics must not be presented as source provisions.");
            return Arrays.asList(record("conflict",quote("a",a.getContent()),quote("b",b.getContent())),record("risk",quote("a",a.getContent()),quote("b",b.getContent())));
        });
        Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Arrays.asList(a,b,older,newer),"en",(n,s)->{});
        assertEquals(Collections.singleton("risk"),new HashSet<>(Arrays.asList(result.getCandidates().get(0).getType())));assertEquals(1,result.getCandidates().size());
        assertEquals(new HashSet<>(Arrays.asList("a","b")),result.getReviewedChunkIds());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("引用目标或邻接限定未补齐")));
    }
    @Test void malformedNullRecordOrEvidenceDoesNotClaimCompletedCoverageOrRetainEarlierCandidates() {
        for(boolean nullEvidence:Arrays.asList(false,true)) {
            AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
            ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
            VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
            Chunk a=chunk("a","one","The processing instruction shall be checked against the supplied reference.");
            when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(a));
            Record valid=record("risk",quote("a",a.getContent()));Record malformed=nullEvidence?record("risk",(Quote)null):null;
            when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Arrays.asList(valid,malformed));
            Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Collections.singletonList(a),"en",(n,s)->{});
            assertTrue(result.getReviewedChunkIds().isEmpty());assertTrue(result.getCandidates().isEmpty());
            assertEquals("failed",result.getTopicAudits().get(0).getStatus());assertEquals(0,result.getTopicAudits().get(0).getAcceptedFindings());
            assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("未完成语义检查")));
        }
    }
    @Test void successfulEmptyModelResponseCountsOnlyActuallySubmittedSource() {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk a=chunk("a","one","The supplied source shall be reviewed for specific coordination issues.");
        Chunk notRetrieved=chunk("other","two","The other source shall not be silently counted as submitted.");
        when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(a));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Collections.emptyList());
        Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Arrays.asList(a,notRetrieved),"en",(n,s)->{});
        assertEquals(Collections.singleton("a"),result.getReviewedChunkIds());assertTrue(result.getCandidates().isEmpty());
    }
    @Test void distinctQuotedSentencesAtTheSameSourceAnchorCannotInventTwoProvisions() {
        Random random=new Random(7931);
        for(int attempt=0;attempt<12;attempt++) {
            AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
            ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
            VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
            String owner=""+(char)('A'+random.nextInt(26))+(char)('A'+random.nextInt(26));
            String left="The "+owner+" observer shall review the samples.",right="The "+owner+" observer shall retain the records.";
            Chunk a=chunk("a","one",left+" "+right);a.setAnchor("body/"+(50+attempt)+"/paragraph");
            Chunk b=chunk("b","one","The "+owner+" observer shall attend the inspection.");b.setAnchor("body/"+(150+attempt)+"/paragraph");
            when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Arrays.asList(a,b));
            when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Arrays.asList(
                    record("conflict",quote("a",left),quote("a",right)),record("conflict",quote("a",left),quote("b",b.getContent()))));
            Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Arrays.asList(a,b),"en",(n,s)->{});
            assertEquals(1,result.getCandidates().size());assertEquals(b.getAnchor(),result.getCandidates().get(0).getEvidence().get(1).getAnchor());
            assertEquals(new HashSet<>(Arrays.asList("a","b")),result.getReviewedChunkIds());
        }
    }
    @Test void anyIncompleteNonemptyRecordRejectsTheWholeTopicAndPreservesNoPartialCoverage() {
        Random random=new Random(30177);
        for(int attempt=0;attempt<16;attempt++) {
            AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
            ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
            VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
            Chunk a=chunk("a","one","The participant "+random.nextInt(99999)+" shall complete the source review.");
            when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(a));
            Record valid=record("risk",quote("a",a.getContent())),malformed=record("risk",quote("a",a.getContent()));
            switch(attempt%4) {case 0: malformed.setTitle(" ");break;case 1: malformed.setComment(null);break;case 2: malformed.setEvidence(null);break;default: malformed.setEvidence(Collections.emptyList());}
            List<Record> records=new ArrayList<>(Arrays.asList(valid,malformed));if(random.nextBoolean())Collections.reverse(records);
            when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(records);
            Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Collections.singletonList(a),"en",(n,s)->{});
            assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().isEmpty());
            assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("schema invalid")));
        }
    }
    @Test void primaryEvidenceIsTheActualTenderDocumentEvenWhenTheModelListsAStandardFirst() {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk tender=chunk("t","tender-doc","The submission shall comply with GCC clause 91.7.");tender.setFileName("actual-volume.docx");
        Chunk standard=chunk("s","standard-doc","The submission shall include the reference particulars.");standard.setRole("standard");standard.setFileKey("GCC");standard.setFileName("reference-volume.docx");standard.setClauseId("GCC91.7");standard.setClauseHeadingLocation("page3");
        when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(tender));
        when(retrieval.retrieve(eq("p"),anyString(),eq("standard"),anyList())).thenReturn(Collections.singletonList(standard));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Collections.singletonList(record("risk",quote("s",standard.getContent()),quote("t",tender.getContent()))));
        Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Arrays.asList(tender,standard),"en",(n,s)->{});
        assertEquals(1,result.getCandidates().size());assertEquals("tender-doc",result.getCandidates().get(0).getEvidence().get(0).getDocumentId());
        assertEquals("actual-volume.docx",result.getCandidates().get(0).getEvidence().get(0).getFileName());assertEquals("standard-doc",result.getCandidates().get(0).getEvidence().get(1).getDocumentId());
    }
    @Test void consistencyAndMissingContextAssessmentsAreNeverReleasedAsFindings() {
        AiGateway ai=mock(AiGateway.class); when(ai.available()).thenReturn(true); when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties(); props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk source=chunk("actual","document","The design review shall allow for at least fifty days before construction.");
        when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(source));
        Record consistent=record("reference",quote("actual",source.getContent())); consistent.setAssessment("consistent");
        Record unknown=record("risk",quote("actual",source.getContent())); unknown.setAssessment("insufficient_context");
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Arrays.asList(consistent,unknown));
        Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Collections.singletonList(source),"en",(n,s)->{});
        assertTrue(result.getCandidates().isEmpty()); assertEquals(Collections.singleton("actual"),result.getReviewedChunkIds());
        assertEquals(1,result.getTopicAudits().get(0).getConsistentAssessments());assertEquals(1,result.getTopicAudits().get(0).getInsufficientContextAssessments());
        assertEquals("completed",result.getTopicAudits().get(0).getStatus());assertEquals(0,result.getTopicAudits().get(0).getAcceptedFindings());
    }
    @Test void persistedCallLedgerHashesTheActualResponseEvenWhenParsingFails() {
        String raw="[{\"assessment\":\"issue\",";
        com.consense.ai.LlmClient client=mock(com.consense.ai.LlmClient.class);when(client.available()).thenReturn(true);when(client.chatModel()).thenReturn("test");
        when(client.chatStructured(anyList(),any())).thenReturn(raw);
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk source=chunk("actual","document","The supplied project provision shall be checked against its operative reference.");
        when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(source));
        Result result=new VettingSemanticReview(new AiGateway(client),props,retrieval).review("p",Collections.singletonList(source),"en",(n,s)->{});
        assertEquals("failed",result.getTopicAudits().get(0).getStatus());assertEquals(VettingCorpus.hash(raw),result.getTopicAudits().get(0).getRawResponseSha256());
        assertEquals(Collections.singletonList("actual"),result.getTopicAudits().get(0).getSubmittedChunkIds());
        assertTrue(result.getReviewedChunkIds().isEmpty());assertTrue(result.getCandidates().isEmpty());
        String serialized=com.consense.common.JsonUtils.write(result.getTopicAudits());
        assertEquals(VettingCorpus.hash(raw),com.consense.common.JsonUtils.parse(serialized).get(0).path("rawResponseSha256").asText());
    }
    @Test void explicitProjectReferenceGetsASeparateActualCallWithoutDependingOnRankOrAnotherRetrieval() {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk unrelated=chunk("ranked","contract","The contractor shall maintain the payment records.");
        Chunk target=chunk("target","contract","(1) The drawings may be inspected at the project office.");
        target.setClauseId("SCC81.4");target.setClauseHeadingLocation("body/21");target.setAnchor("body/21/paragraph");
        Chunk fact=chunk("fact","message","SCC81.4(1) | Please confirm the address. | The drawings may be inspected at the project office.");
        fact.setRole("project_fact");fact.setFileKey("OTHER");
        VettingCorpus.Part factPart = new VettingCorpus.Part(); factPart.setText(fact.getContent());
        factPart.setBlockId("body:5:table-row:1"); factPart.setAnchor("body/5/table-row/1"); factPart.setEndOffset(fact.getContent().length());
        VettingCorpus.TableRow factTable = new VettingCorpus.TableRow(); factTable.setRowIndex(1); factTable.setTableLocation("body/5");
        factTable.setHeaderBlockId("body:5:table-row:0"); factTable.setHeaderLocation("body/5/table-row/0");
        factTable.setHeaders(Arrays.asList("Clause", "Required input", "Reply"));
        factTable.setCells(Arrays.asList("SCC81.4(1)", "Please confirm the address.", "The drawings may be inspected at the project office."));
        factTable.setHeaderBasis("project_fact_first_native_row_heuristic_compatibility");
        factPart.setTable(factTable);
        // The business interpretation requires the native header to be actually selected with this row.
        VettingCorpus.Part headerPart = new VettingCorpus.Part(); headerPart.setText("Clause | Required input | Reply");
        headerPart.setBlockId("body:5:table-row:0"); headerPart.setAnchor("body/5/table-row/0"); headerPart.setEndOffset(headerPart.getText().length());
        VettingCorpus.TableRow headerTable = new VettingCorpus.TableRow(); headerTable.setRowIndex(0); headerTable.setTableLocation("body/5");
        headerTable.setCells(Arrays.asList("Clause", "Required input", "Reply")); headerPart.setTable(headerTable);
        fact.setParts(Arrays.asList(headerPart, factPart)); fact.setContent(headerPart.getText()+"\n"+factPart.getText());
        fact.setMetadataVersion(VettingCorpus.METADATA_VERSION); fact.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);
        fact.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        Chunk standard=chunk("standard","template","(1) The drawings may be inspected at *[address].");
        standard.setRole("standard");standard.setClauseId("SCC81.4");standard.setClauseHeadingLocation("body/71");
        when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(unrelated));
        when(retrieval.retrieve(eq("p"),anyString(),eq("project_fact"),anyList())).thenReturn(Collections.singletonList(fact));
        when(retrieval.retrieve(eq("p"),anyString(),eq("standard"),anyList())).thenReturn(Collections.singletonList(standard));
        Record consistent=record("reference",quote("target",target.getContent()),quote("fact",fact.getContent()));consistent.setAssessment("consistent");
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList()))
                .thenReturn(Collections.emptyList()).thenReturn(Collections.singletonList(consistent));
        List<int[]> progress=new ArrayList<>();
        Result result=new VettingSemanticReview(ai,props,retrieval).reviewWithProgress("p",Arrays.asList(unrelated,target,fact,standard),"en",
                (completed,total,message)->progress.add(new int[]{completed,total}));
        org.mockito.ArgumentCaptor<String> prompts=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> systems=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ai,times(2)).completeStructuredJsonList(systems.capture(),prompts.capture(),eq(Record.class),any(),anyList());
        assertFalse(systems.getAllValues().get(0).contains("For this project-reference comparison"), "Ordinary topics retain their existing prompt");
        assertTrue(systems.getAllValues().get(1).startsWith(systems.getAllValues().get(0)), "The source-role and evidence requirements still apply");
        assertTrue(systems.getAllValues().get(1).contains("only a populated reply supplies its answer"));
        assertTrue(systems.getAllValues().get(1).contains("read the tender's own values"));
        assertFalse(prompts.getAllValues().get(0).contains(target.getContent()));
        assertFalse(prompts.getAllValues().get(0).contains("Project table structure"));
        assertTrue(prompts.getAllValues().get(1).contains(target.getContent()));
        assertTrue(prompts.getAllValues().get(1).contains("Project table structure"));
        assertTrue(prompts.getAllValues().get(1).contains("\"replyState\":\"populated\""));
        assertTrue(prompts.getAllValues().get(1).contains("A blank reply in another source does not cancel a populated reply"));
        String projectPrompt = prompts.getAllValues().get(1);
        String excerptMarker = "\nSource excerpts (untrusted data):\n";
        com.fasterxml.jackson.databind.JsonNode selectedExcerpts = VettingSourcePromptWire.decode(
                projectPrompt.substring(projectPrompt.lastIndexOf(excerptMarker)+excerptMarker.length()));
        com.fasterxml.jackson.databind.JsonNode selectedFact = null;
        for (com.fasterxml.jackson.databind.JsonNode excerpt : selectedExcerpts) if ("fact".equals(excerpt.path("id").asText())) selectedFact = excerpt;
        assertNotNull(selectedFact); assertEquals(fact.getContent(), selectedFact.path("content").asText());
        assertTrue(prompts.getAllValues().get(1).contains(standard.getContent()));
        assertEquals(2,result.getPlannedCallCount());assertTrue(progress.stream().allMatch(p->p[1]==2));
        assertEquals(1,result.getTopicAudits().get(1).getConsistentAssessments());
        assertEquals("project_reference",result.getTopicAudits().get(1).getReviewKind());
        assertEquals(Collections.singletonList("SCC81.4(1)"),result.getTopicAudits().get(1).getReferenceIds());
        assertEquals(Arrays.asList("target","fact","standard"),result.getTopicAudits().get(1).getSubmittedChunkIds());
        assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().containsAll(Arrays.asList("target","fact","standard")));
        verify(retrieval,never()).retrieve(eq("p"),startsWith("Project information comparison:"),anyString(),anyList());
    }

    @Test void projectComparisonCapDoesNotClaimCallsOrCoverageForOmittedTargets() {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);props.getVetting().setProjectReferenceComparisons(0);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        Chunk unrelated=chunk("ranked","contract","The contractor shall maintain payment records.");
        Chunk target=chunk("target","contract","The drawings may be inspected at the project office.");target.setClauseId("SCC81.4");target.setClauseHeadingLocation("body/21");
        Chunk fact=chunk("fact","message","SCC81.4 | Please confirm the inspection address.");fact.setRole("project_fact");fact.setFileKey("OTHER");
        when(retrieval.retrieve(eq("p"),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(unrelated));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Collections.emptyList());
        Result result=new VettingSemanticReview(ai,props,retrieval).review("p",Arrays.asList(unrelated,target,fact),"en",(n,s)->{});
        assertEquals(1,result.getPlannedCallCount());verify(ai,times(1)).completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList());
        assertFalse(result.getReviewedChunkIds().contains("target"));assertFalse(result.getReviewedChunkIds().contains("fact"));
        assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("SCC81.4 未提交：超过本次比较数量限制")));
    }
    @Test void correspondingTemplateAndAnUnquotedProjectReplyCannotEstablishAReferenceIssueOrConflict() {
        for (String type : Arrays.asList("reference", "conflict")) {
            Result result = sourceRoleComparison(type, false, false, false);
            assertTrue(result.getTopicAudits().get(0).getUnresolvedChunkIds().isEmpty(), "This tests the role basis, not missing context");
            assertTrue(result.getCandidates().isEmpty());
            assertEquals(1, result.getTopicAudits().get(0).getRejectedRecords());
        }
    }
    @Test void aDifferentStandardOwnerIsInsufficientWithoutAnExplicitQuotedTargetReference() {
        Result result = sourceRoleComparison("reference", false, false, true);
        assertTrue(result.getCandidates().isEmpty());
        assertEquals(1, result.getTopicAudits().get(0).getRejectedRecords());
    }
    @Test void explicitlyReferencedStandardTargetAndQuotedProjectReplyRemainEligibleComparisons() {
        Result standard = sourceRoleComparison("reference", true, false, true);
        assertTrue(standard.getTopicAudits().get(0).getUnresolvedChunkIds().isEmpty());
        assertEquals(1, standard.getCandidates().size(), "A quoted cross-owner clause reference remains a comparison basis");
        Result project = sourceRoleComparison("reference", false, true, false);
        assertEquals(1, project.getCandidates().size(), "The actually quoted project reply remains context for a value/reference comparison");
        Result obligation = sourceRoleComparison("conflict", false, true, false);
        assertTrue(obligation.getCandidates().isEmpty(), "A project reply is not a second competing contract obligation");
    }
    @Test void unknownTenderOwnerCannotEstablishTheStandardReferenceRelationship() {
        Result result = sourceRoleComparison("reference", true, false, true, "OTHER");
        assertTrue(result.getCandidates().isEmpty());
        assertEquals(1, result.getTopicAudits().get(0).getRejectedRecords());
    }
    private static Result sourceRoleComparison(String type, boolean explicitReference, boolean quoteProject, boolean separateOwner) {
        return sourceRoleComparison(type, explicitReference, quoteProject, separateOwner, "SCC");
    }
    private static Result sourceRoleComparison(String type, boolean explicitReference, boolean quoteProject, boolean separateOwner, String tenderOwner) {
        AiGateway ai = mock(AiGateway.class); when(ai.available()).thenReturn(true); when(ai.chatModel()).thenReturn("test");
        ConsenseProperties props = new ConsenseProperties(); props.getVetting().setSemanticTopics(1); props.getVetting().setProjectReferenceComparisons(0);
        VettingRetrievalClient retrieval = mock(VettingRetrievalClient.class);
        Chunk tender = chunk("tender", "contract", explicitReference
                ? "The Site Liaison Planner (SLP) shall comply with GCC clause 91.7."
                : "The Site Liaison Planner (SLP) shall inspect the drawings at the depot office.");
        tender.setClauseId("SCC81.4"); tender.setClauseHeadingLocation("body/11"); tender.setAnchor("body/11/paragraph");
        tender.setFileKey(tenderOwner);
        Chunk standard = chunk("standard", "template", "The Site Liaison Planner (SLP) shall inspect the drawings at *[address].");
        standard.setRole("standard"); standard.setFileKey(separateOwner ? "GCC" : "SCC");
        standard.setClauseId(separateOwner ? "GCC91.7" : "SCC81.4"); standard.setClauseHeadingLocation("body/71"); standard.setAnchor("body/71/paragraph");
        Chunk fact = chunk("fact", "reply", "SCC81.4 | The project team confirms the SLP shall inspect the drawings at the harbour office.");
        fact.setRole("project_fact"); fact.setFileKey("OTHER"); fact.setAnchor("body/7/table-row/1");
        when(retrieval.retrieve(eq("p"), anyString(), eq("tender"), anyList())).thenReturn(Collections.singletonList(tender));
        when(retrieval.retrieve(eq("p"), anyString(), eq("standard"), anyList())).thenReturn(Collections.singletonList(standard));
        when(retrieval.retrieve(eq("p"), anyString(), eq("project_fact"), anyList())).thenReturn(Collections.singletonList(fact));
        Chunk comparator = quoteProject ? fact : standard;
        when(ai.completeStructuredJsonList(anyString(), anyString(), eq(Record.class), any(), anyList())).thenAnswer(call -> {
            String prompt = call.getArgument(1);
            assertTrue(prompt.contains(tender.getContent())); assertTrue(prompt.contains(comparator.getContent()));
            if (!quoteProject) assertTrue(prompt.contains(fact.getContent()), "Having a reply in context must not substitute for quoting it");
            return Collections.singletonList(record(type, quote(tender.getId(), tender.getContent()), quote(comparator.getId(), comparator.getContent())));
        });
        return new VettingSemanticReview(ai, props, retrieval).review("p", Arrays.asList(tender, standard, fact), "en", (n,s) -> {});
    }
    private static Record record(String type,Quote... quotes) { Record r=new Record(); r.setAssessment("issue"); r.setType(type); r.setTitle("Reference issue"); r.setComment("Review the two provisions."); r.setEvidence(Arrays.asList(quotes)); return r; }
    private static Quote quote(String id,String text) { Quote q=new Quote(); q.setChunkId(id); q.setSide(id); q.setQuote(text); return q; }
    private static Chunk chunk(String id,String doc,String text) { Chunk c=new Chunk(); c.setId(id); c.setDocumentId(doc); c.setFileName(doc+".docx"); c.setFileKey("SCC"); c.setContent(text); c.setRole("tender"); c.setAnchor("paragraph"); c.setSourceHash("abc"); return c; }
}
