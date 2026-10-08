package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.ai.AiGateway;
import com.consense.ai.LlmClient;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Replays source-grounding intake, never converting an expected-answer oracle into evidence. */
class DraftTenderMediaEvidenceRegressionTest {
    @Test void catalogueMeaningRemainsBqIssueFormat() {
        DraftBlueprint.InputSpec spec=DraftBlueprint.find("electronicTendering");
        assertEquals("BQ issue format",spec.labelEn);
        assertEquals(Arrays.asList("L10Pro","Hardcopy"),spec.options);
    }

    @Test void realBaselineSorOnlyQuoteCannotSupplyBqMediaEvenWhenValuesCoincide() throws Exception {
        JsonNode capture=resource("captured-bq-media-baseline.json");
        assertEquals("bca1b3bc-b75b-4fb6-a8cc-7862d3b94166",capture.path("runId").asText());
        JsonNode record=capture.path("cases").get(1);
        ExtractionDecisionVO result=recordedDecision(record);
        assertEquals("rejected",result.getStatus(),result.getCodes().toString());
        assertTrue(result.getCodes().contains("quote_value_mismatch"));
        assertEquals("Both SOR detailed schedules and subsequent addenda are issued in hardcopy.",result.getSourceQuote());
    }

    @Test void realBaselineExplicitBqIssueQuoteStillSuppliesTheCorrectCandidate() throws Exception {
        ExtractionDecisionVO result=recordedDecision(resource("captured-bq-media-baseline.json").path("cases").get(0));
        assertEquals("accepted",result.getStatus(),result.getCodes().toString());
        assertEquals("Hardcopy",result.getNormalizedValue());
    }

    @Test void realRound1AllThreeOriginalBillsFormatRepliesKeepTheirBqScope() throws Exception {
        JsonNode capture=resource("captured-round1-bq-media.json");
        assertEquals("12d06aec-a541-4e21-a400-0632b80d0d3e",capture.path("runId").asText());
        assertEquals(3,capture.path("cases").size());
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(JsonNode record:capture.path("cases"))checks.add(()->{
            ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
            ExtractionAttemptVO attempt=JsonUtils.mapper().treeToValue(record.path("attempt"),ExtractionAttemptVO.class);
            part.setContext(attempt.getContext());
            List<ExtractionDecisionVO> decisions=DraftHarnessTestIntake.primaryDecisions(attempt.getRawResponse(),part,
                    record.path("originalSource").asText(),attempt.getAttemptIndex(),attempt.getSystemPrompt(),attempt.getUserPrompt());
            ExtractionDecisionVO result=decisions.stream().filter(d->d.getItemIndex()==record.path("itemIndex").asInt())
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals("electronicTendering",result.getKey());
            assertEquals("The adopted electronic issue format for the Bills is L10Pro.",
                    record.path("recordedDecision").path("sourceQuote").asText());
            assertEquals("accepted",result.getStatus(),"captured attempt "+attempt.getAttemptIndex()+" "+result.getCodes());
            assertEquals("L10Pro",result.getNormalizedValue());
            assertEquals(record.path("recordedDecision").path("sourceQuote").asText(),result.getSourceQuote(),
                    "The neighboring original scope grounds the unchanged model citation.");
        });
        assertAll("All three unchanged captured model replies must ground the actual BQ issue scope",checks);
    }

    @Test void everyCorrectElectronicCandidateFromBothEarlierRealRunsKeepsItsEvidence() throws Exception {
        int checked=0;
        for(String name:Arrays.asList("captured-alternate-values","captured-legacy-values")) {
            JsonNode fixture=resource(name+"/fixture.json");
            for(JsonNode decision:fixture.path("trace").path("decisions"))
                if("electronicTendering".equals(decision.path("key").asText())&&"accepted".equals(decision.path("status").asText())) {
                    JsonNode partRecord=null;
                    for(JsonNode part:fixture.path("trace").path("parts"))
                        if(decision.path("partId").asText().equals(part.path("partId").asText()))partRecord=part;
                    assertNotNull(partRecord);JsonNode attemptRecord=null;
                    for(JsonNode attempt:partRecord.path("attempts"))
                        if(decision.path("attemptIndex").asInt()==attempt.path("attemptIndex").asInt())attemptRecord=attempt;
                    assertNotNull(attemptRecord);
                    assertEquals(1,decision.path("attemptIndex").asInt(),"These preserved electronic decisions are primary replies.");
                    ExtractionPartVO part=JsonUtils.mapper().treeToValue(partRecord,ExtractionPartVO.class);
                    // The persisted part may hold its final recall window; replay this dispatch's unchanged primary window.
                    part.setContext(JsonUtils.mapper().treeToValue(attemptRecord.path("context"),ExtractionContextVO.class));
                    String original=fixture.path("sources").path(partRecord.path("sourceDocumentId").asText()).path("originalSource").asText();
                    assertFalse(original.isEmpty());
                    ExtractionDecisionVO replay=intake(part,attemptRecord.path("rawResponse").asText(),decision.path("itemIndex").asInt(),original);
                    assertEquals("accepted",replay.getStatus(),name+" "+decision.path("partId").asText()+" "+replay.getCodes());
                    assertEquals(decision.path("normalizedValue").asText(),replay.getNormalizedValue());checked++;
                }
        }
        assertEquals(5,checked);
    }

    @Test void bqOrTheWholeTenderIssueCanEstablishMedia() throws Exception {
        accepted("Hardcopy","The Bill of Quantities is issued in hardcopy.");
        accepted("L10Pro","The BQ issue format is L10Pro.");
        accepted("Hardcopy","BQ issue format | Hardcopy | Confirmed");
        accepted("Hardcopy","All tender documents for this contract are issued in hardcopy.");
        accepted("Hardcopy","Tender documents are issued as printed originals.");
        accepted("L10Pro","The tender documents are issued in L10Pro electronic format.");
    }

    @Test void unrelatedDocumentsAndSorOnlyMediaAreNotBqEvidence() throws Exception {
        for(String source:Arrays.asList("Both SOR detailed schedules and subsequent addenda are issued in hardcopy.",
                "All SOR tender documents are issued in hardcopy.","The Schedules of Rates are issued in hardcopy.",
                "The tender drawings are issued in hardcopy.","The addenda are issued in hardcopy.",
                "The correspondence is issued in hardcopy.","The photocopies of tender documents are issued in hardcopy.",
                "A reference PDF of the BQ issue is supplied in hardcopy for convenience.",
                "The SOR schedules are issued in hardcopy independently of the BQ issue format.",
                "For another project the BQ issue format is Hardcopy.",
                "All tender documents except the BQ are issued in hardcopy."))
            rejected("Hardcopy",source);
    }

    @Test void tenderReturnMediaCannotBeUsedAsIssueMedia() throws Exception {
        for(String source:Arrays.asList("The BQ must be returned in hardcopy.",
                "Tender documents must be submitted in hardcopy with a DVD-ROM.",
                "BQ return format | Hardcopy | Confirmed",
                "Use L10Pro for the tender return only."))
            rejected(source.contains("L10Pro")?"L10Pro":"Hardcopy",source);
    }

    @Test void mediaCannotBeBorrowedAcrossClausesOrTableRows() throws Exception {
        String source="The BQ issue format is L10Pro. Both SOR detailed schedules and subsequent addenda are issued in hardcopy.";
        accepted("L10Pro",source);rejected("Hardcopy",source);
        source="BQ issue format | L10Pro\nTender return format | Hardcopy";
        accepted("L10Pro",source);rejected("Hardcopy",source);
        source="The BQ issue format is Hardcopy; the tender return uses L10Pro.";
        accepted("Hardcopy",source);rejected("L10Pro",source);
        source="The BQ issue format is L10Pro and the SOR issue format is Hardcopy.";
        accepted("L10Pro",source);rejected("Hardcopy",source);
    }

    @Test void pendingProposedQuestionOrNegativeFormatsDoNotSupplyAnAdoptedFormat() throws Exception {
        for(String source:Arrays.asList("Please confirm whether the BQ issue format is Hardcopy.",
                "The BQ issue format is Hardcopy?","The proposed BQ issue format is Hardcopy.",
                "The BQ issue format is Hardcopy if approved.","The BQ issue format is Hardcopy but remains pending.",
                "The BQ issue format is not Hardcopy.","The BQ is not issued in hardcopy.",
                "The BQ issue format is pending. The SOR issue format is Hardcopy."))
            rejected("Hardcopy",source);
    }

    @Test void conflictingIssueClaimsCannotBePickedByConvenience() throws Exception {
        String source="The BQ issue format is Hardcopy. The BQ issue format is L10Pro.";
        rejected("Hardcopy",source);rejected("L10Pro",source);
        rejected("Hardcopy","The BQ issue format is Hardcopy. The BQ issue format is not Hardcopy.");
        rejected("Hardcopy","The BQ issue format is Hardcopy; this remains pending.");
        rejected("Hardcopy","The BQ issue format is Hardcopy. This remains pending.");
        rejected("Hardcopy","The BQ issue format is Hardcopy. The format is not confirmed.");
        accepted("Hardcopy","The BQ issue format is Hardcopy. Refuge floor installation remains pending.");
        rejected("Hardcopy","The BQ issue format is either Hardcopy or L10Pro.");
    }

    @Test void convenienceAndReturnMediaDoNotBecomeTheBqIssueMode() throws Exception {
        for(String source:Arrays.asList(
                "The BQ issue format is L10Pro, with hardcopy convenience copies supplied to tenderers.",
                "The BQ issue format is L10Pro and hardcopy tender documents are required for return.")) {
            accepted("L10Pro",source);rejected("Hardcopy",source);
        }
    }

    @Test void aNegatedAlternativeCannotNegateTheExplicitlyAdoptedOtherMode() throws Exception {
        String source="The BQ issue format is not L10Pro but Hardcopy.";
        accepted("Hardcopy",source);rejected("L10Pro",source);
        source="The BQ issue format is not Hardcopy but L10Pro.";
        accepted("L10Pro",source);rejected("Hardcopy",source);
    }

    @Test void dependentDecisionNounsKeepTheirUnresolvedStatus() throws Exception {
        for(String qualifier:Arrays.asList("This decision remains unconfirmed.","This decision is pending.",
                "That decision is pending.","This issue format is not confirmed."))
            rejected("Hardcopy","The BQ issue format is Hardcopy. "+qualifier);
    }

    @Test void issueRoleBeforeAnExplicitBqObjectCanEstablishMedia() throws Exception {
        accepted("L10Pro","The adopted electronic issue format for the BQ is L10Pro.");
        accepted("Hardcopy","The issue format for the Bills of Quantities is Hardcopy.");
        rejected("L10Pro","The issue format for the SOR is L10Pro.");
        rejected("L10Pro","A reference copy uses the issue format for the BQ in L10Pro.");
    }

    @Test void bareBillsNeedTheirVisibleAdjacentBqPricingScope() throws Exception {
        String quote="The adopted electronic issue format for the Bills is L10Pro.";
        String source=quote+" Detailed BQ and SOR pricing files are supplied on Disc B. A printed reference set is for inspection only and does not change the adopted L10Pro issue format.";
        assertEquals("accepted",synthetic("L10Pro",quote,source,source).getStatus());
        String defined=quote+" The Bills comprise BQ and SOR pricing schedules.";
        assertEquals("accepted",synthetic("L10Pro",quote,defined,defined).getStatus());
        assertEquals("rejected",synthetic("Hardcopy",quote,source,source).getStatus());
        assertEquals("rejected",synthetic("L10Pro",quote,source,quote).getStatus(),"An unprovided adjacent sentence cannot establish the role.");
        rejected("L10Pro",quote);
    }

    @Test void sorOnlyRemoteOrDifferentRowBqTextCannotResolveBareBills() throws Exception {
        String quote="The adopted electronic issue format for the Bills is L10Pro.";
        for(String source:Arrays.asList(
                quote+" Detailed SOR pricing files are supplied on Disc B.",
                quote+" Detailed SOR pricing files are supplied on Disc B. Detailed BQ pricing files are supplied on Disc A.",
                quote+"\n\nDetailed BQ and SOR pricing files are supplied on Disc B.",
                quote+"\nDetailed BQ and SOR pricing files are supplied on Disc B.",
                quote+" Detailed BQ pricing files for another project are supplied on Disc B.",
                quote+" Detailed BQ and SOR pricing files are to be returned on Disc B.",
                quote+" Detailed BQ and SOR pricing files are not supplied on Disc B.",
                quote+" Detailed BQ and SOR pricing files are supplied on Disc B if approved."))
            assertEquals("rejected",synthetic("L10Pro",quote,source,source).getStatus(),source);
    }

    @Test void contextualBillsStillRespectPendingNegativeConflictingAndExcludedScope() throws Exception {
        String quote="The adopted electronic issue format for the Bills is L10Pro.";
        String scope=" Detailed BQ and SOR pricing files are supplied on Disc B.";
        for(String suffix:Arrays.asList(" This decision remains unconfirmed.",
                " The BQ issue format is Hardcopy."," The BQ issue format is not L10Pro.",
                " Tender documents excluding the BQ are issued in L10Pro.")) {
            String source=quote+scope+suffix;
            assertEquals("rejected",synthetic("L10Pro",quote,source,source).getStatus(),source);
        }
        for(String statement:Arrays.asList(
                "The electronic issue format for the Bills is not L10Pro.",
                "The proposed electronic issue format for the Bills is L10Pro.",
                "Please confirm whether the electronic issue format for the Bills is L10Pro.")) {
            String source=statement+scope;
            assertEquals("rejected",synthetic("L10Pro",statement,source,source).getStatus(),source);
        }
    }

    @Test void duplicateOriginalBillsAnchorsCannotSelectTheConvenientScope() throws Exception {
        String quote="The adopted electronic issue format for the Bills is L10Pro.";
        String withBq=quote+" Detailed BQ and SOR pricing files are supplied on Disc B.";
        String withSor=quote+" Detailed SOR pricing files are supplied on Disc B.";
        for(String separator:Arrays.asList(" ","\n\n")) {
            String source=withBq+separator+withSor;
            assertEquals("rejected",synthetic("L10Pro",quote,source,source).getStatus(),source);
        }
    }

    @Test void referenceOrSampleBqSupplyCannotEstablishAdoptedBillsScope() throws Exception {
        String quote="The adopted electronic issue format for the Bills is L10Pro.";
        for(String suffix:Arrays.asList("for reference only", "as unselected samples", "as illustrative examples", "as draft specimens")) {
            String source=quote+" Detailed BQ pricing files are supplied "+suffix+".";
            assertEquals("rejected",synthetic("L10Pro",quote,source,source).getStatus(),source);
        }
    }

    @Test void explicitSorOnlyBillsScopeOverridesAnAdjacentBqSupplyMention() throws Exception {
        String quote="The adopted electronic issue format for the Bills is L10Pro.";
        for(String exclusion:Arrays.asList("These adopted Bills comprise SOR only.",
                "The Bills contain only Schedules of Rates.","The Bills include SOR only.")) {
            String source=quote+" Detailed BQ pricing files are supplied to tenderers. "+exclusion;
            assertEquals("rejected",synthetic("L10Pro",quote,source,source).getStatus(),source);
        }
    }

    private static JsonNode resource(String name) throws Exception {
        try(InputStream stream=DraftTenderMediaEvidenceRegressionTest.class.getResourceAsStream("/drafting/harness/"+name)) {
            assertNotNull(stream,name);return JsonUtils.mapper().readTree(stream);
        }
    }
    private static ExtractionDecisionVO recordedDecision(JsonNode record) throws Exception {
        ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
        ExtractionAttemptVO attempt=JsonUtils.mapper().treeToValue(record.path("attempt"),ExtractionAttemptVO.class);
        return intake(part,attempt.getRawResponse(),record.path("itemIndex").asInt(),record.path("originalSource").asText());
    }
    private static void accepted(String value,String source) throws Exception {
        assertEquals("accepted",synthetic(value,source).getStatus(),source);
    }
    private static void rejected(String value,String source) throws Exception {
        ExtractionDecisionVO result=synthetic(value,source);
        assertEquals("rejected",result.getStatus(),source+" "+result.getCodes());
        assertTrue(result.getCodes().contains("quote_value_mismatch"));
    }
    private static ExtractionDecisionVO synthetic(String value,String source) throws Exception {
        return synthetic(value,source,source,source);
    }
    private static ExtractionDecisionVO synthetic(String value,String quote,String source,String supplied) throws Exception {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key","electronicTendering");item.put("value",value);
        item.put("sourceQuote",quote);item.put("reason","Offline BQ issue media evidence regression");item.put("confidence",.95);
        int start=source.indexOf(supplied);assertTrue(start>=0);
        ExtractionContextVO context=new ExtractionContextVO("tender-media-regression",Collections.emptyList(),start,start+supplied.length(),supplied,null);
        ExtractionPartVO part=new ExtractionPartVO("media:0",1L,"media-regression.txt","source",0,source,new ArrayList<>());part.setContext(context);
        ExtractionAttemptVO attempt=new ExtractionAttemptVO(1,"primary","","",JsonUtils.write(Collections.singletonList(item)),"completed",null);attempt.setContext(context);
        return intake(part,attempt.getRawResponse(),0,source);
    }
    private static ExtractionDecisionVO intake(ExtractionPartVO part,String raw,int index,String originalSource) {
        LlmClient client=mock(LlmClient.class);when(client.available()).thenReturn(true);when(client.chat(anyList())).thenReturn(raw,"[]");
        DraftExtractionHarness harness=new DraftExtractionHarness(new AiGateway(client));
        ExtractTraceVO trace=new ExtractTraceVO();trace.setRawResponses(new ArrayList<>());part.setAttempts(new ArrayList<>());
        harness.extractPart(trace,part,harness.systemPrompt("Source intake regression."),harness.userPrompt("%s\n%s",part),originalSource);
        ExtractionDecisionVO primary=trace.getDecisions().stream()
                .filter(d->d.getAttemptIndex()==1&&d.getItemIndex()==index).findFirst().orElseThrow(AssertionError::new);
        assertEquals("electronicTendering",primary.getKey());return primary;
    }
}
