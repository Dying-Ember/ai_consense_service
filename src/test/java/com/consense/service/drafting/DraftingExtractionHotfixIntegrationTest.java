package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.ai.LlmProfiles;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public upload, extraction, input and immutable-report seams; only external model calls are doubled. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory",
        "consense.llm.enabled=true","consense.llm.base-url=http://test-only-adapter.invalid",
        "consense.llm.chat-model=test-only-external-model"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingExtractionHotfixIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:draft_hotfix_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","drafting-hotfix-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired LlmProfiles profiles;
    @MockBean(name="llmClient") LlmClient externalModel;
    @BeforeEach void modelAvailable() {
        // Explicit-profile failures must occur at the mocked provider attempt,
        // rather than before dispatch because ambient profile config is blank.
        assertSame(externalModel,profiles.resolve("local").getClient());
        assertNull(profiles.resolve("local").getUnavailableReason());
        when(externalModel.available()).thenReturn(true);
        when(externalModel.chatModel()).thenReturn("test-only-external-model");
    }

    @Test void explicitUnknownRetainsItsReasonAndSourceWithoutInventingOrAdoptingAnAnswer() throws Exception {
        String project=project();
        String quote="Combined-foundation classification remains pending until the engineer reconciles the drawings.";
        upload(project,"test-only-scope.txt",quote);
        Map<String,Object> answer=candidate("foundationIncluded",null,quote);
        answer.put("reason","Scope classification is expressly pending; foundation-related work is not a Yes or No.");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(answer)));
        extract(project,0);
        JsonNode input=variable(project,"foundationIncluded");
        assertEquals("",input.path("value").asText());assertFalse(input.path("confirmed").asBoolean());
        assertTrue(input.path("note").asText().contains("expressly pending"));
        assertTrue(input.path("note").asText().contains("test-only-scope.txt"));
        assertEquals(quote,input.path("source").asText());
        assertEquals("source_unresolved",field(trace(project),"foundationIncluded").path("status").asText());
        assertEquals("no_candidate",field(trace(project),"domesticBlocks").path("status").asText());
    }

    @Test void billIdentitiesSurviveWhileOnlyExplicitRowMetadataCanPrefillClassification() throws Exception {
        String project=project();
        String quote="Schedule of Rates discussion\nBill No. | Description | Document treatment\n1 | Preliminaries | Bill schedule entry\n9 | Electrical Works | Schedule of Rates\nBill20 Other Works is a measured Bill of Quantities (BQ).";
        upload(project,"test-only-mixed-bills.txt",quote);
        Map<String,Object> first=new LinkedHashMap<>();first.put("number","1");first.put("description","Preliminaries");first.put("type","BQ");
        first.put("purpose","preliminaries");first.put("placement","DiscA");
        Map<String,Object> rates=new LinkedHashMap<>();rates.put("number","9");rates.put("description","Electrical Works");rates.put("type","SOR");
        Map<String,Object> measured=new LinkedHashMap<>();measured.put("number","20");measured.put("description","Other Works");measured.put("type","BQ");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("billNos",Arrays.asList(first,rates,measured),quote))));
        extract(project,0);JsonNode bills=JsonUtils.parse(variable(project,"billNos").path("value").asText());
        assertEquals(3,bills.size());assertEquals("Preliminaries",bills.get(0).path("description").asText());
        assertFalse(bills.get(0).hasNonNull("type"));assertFalse(bills.get(0).hasNonNull("purpose"));assertFalse(bills.get(0).hasNonNull("placement"));
        assertEquals("SOR",bills.get(1).path("type").asText());assertEquals("BQ",bills.get(2).path("type").asText());
        JsonNode decision=trace(project).path("decisions").get(0);
        assertEquals("accepted",decision.path("status").asText());
        assertEquals("BQ",decision.path("rawValue").get(0).path("type").asText());
        assertTrue(decision.path("codes").toString().contains("bill_metadata_unsupported:1:type"));
        assertFalse(variable(project,"billNos").path("confirmed").asBoolean());
    }

    @Test void unrelatedPendingTextQuestionsAndNegatedUnknownsDoNotBecomeSourceUnresolved() throws Exception {
        for(String quote:Arrays.asList(
                "The foundation classification is confirmed; the exact accepted contract period is not supplied.",
                "The foundation scope is confirmed. Domestic block classification remains pending.",
                "Is the foundation classification still pending?",
                "The foundation classification is not unknown.")) {
            String project=project();upload(project,"test-only-unknown-counterexample.txt",quote);
            Map<String,Object> answer=candidate("foundationIncluded",null,quote);answer.put("reason","The model says foundation is pending.");
            when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(answer)));
            extract(project,0);
            assertEquals("model_unanswered",field(trace(project),"foundationIncluded").path("status").asText(),quote);
            assertEquals("",variable(project,"foundationIncluded").path("value").asText());
        }
    }

    @Test void anotherBillOnTheSameLineCannotSupplyThisBillsPricingType() throws Exception {
        String project=project();String quote="Bill1 Preliminaries is an identity entry; Bill9 Electrical Works is a Schedule of Rates.";
        upload(project,"test-only-bill-locality.txt",quote);
        Map<String,Object> first=new LinkedHashMap<>();first.put("number","1");first.put("description","Preliminaries");first.put("type","SOR");
        Map<String,Object> ninth=new LinkedHashMap<>();ninth.put("number","9");ninth.put("description","Electrical Works");ninth.put("type","SOR");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("billNos",Arrays.asList(first,ninth),quote))));
        extract(project,0);JsonNode bills=JsonUtils.parse(variable(project,"billNos").path("value").asText());
        assertFalse(bills.get(0).hasNonNull("type"));assertEquals("SOR",bills.get(1).path("type").asText());
    }

    @Test void tradeArrangementCitationNeedsRepairToAQuoteThatActuallyListsEverySelectedTrade() throws Exception {
        String project=project();String arrangement="Select Building Services Specialist Sub-Contracts (BSSSC) in the variable list.";
        String trades="The specialist subcontract list comprises Electrical; Fire services and water pump; Lift.";
        upload(project,"test-only-trade-repair.txt",arrangement+"\n"+trades);
        List<String> selected=Arrays.asList("Electrical","Fire services and water pump","Lift");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("subcontractors",selected,arrangement))),
                JsonUtils.write(Arrays.asList(candidate("subcontractors",selected,trades))));
        extract(project,0);JsonNode report=trace(project);
        assertEquals("rejected",report.path("decisions").get(0).path("status").asText());
        assertTrue(report.path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
        assertEquals("accepted",report.path("decisions").get(1).path("status").asText());
        assertEquals(trades,variable(project,"subcontractors").path("source").asText());
        assertEquals(JsonUtils.write(selected),variable(project,"subcontractors").path("value").asText());
        assertFalse(variable(project,"subcontractors").path("confirmed").asBoolean());
    }

    @Test void omittedDirectResidentialScopeGetsOneEvidenceBoundedCoverageAttemptWithoutChangingPendingFoundation() throws Exception {
        String project=project();String domestic="The project scope describes a 44-storey domestic block over a commercial podium.";
        String pending="Combined-foundation classification remains pending until the drawings are checked.";
        upload(project,"test-only-residential-scope.txt",domestic+"\n"+pending);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("foundationIncluded",null,pending))),
                JsonUtils.write(Arrays.asList(candidate("domesticBlocks",true,domestic),candidate("foundationIncluded",true,domestic))));
        extract(project,0);JsonNode report=trace(project);
        assertEquals("true",variable(project,"domesticBlocks").path("value").asText());
        assertFalse(variable(project,"domesticBlocks").path("confirmed").asBoolean());
        assertEquals("",variable(project,"foundationIncluded").path("value").asText());
        assertEquals("coverage",report.path("parts").get(0).path("attempts").get(1).path("kind").asText());
        assertTrue(report.path("parts").get(0).path("attempts").get(1).path("userPrompt").asText().contains(domestic));
        assertEquals("rejected",report.path("decisions").get(2).path("status").asText());
        assertTrue(report.path("decisions").get(2).path("codes").toString().contains("coverage_key_not_allowed"));
    }

    @Test void negativeOrRequestedTradeListsAndCompositePrefixesCannotSelectUnsupportedTrades() throws Exception {
        Map<String,List<String>> cases=new LinkedHashMap<>();
        cases.put("Electrical, Fire services and water pump, and Lift are excluded from this subcontract list.",Arrays.asList("Electrical"));
        cases.put("The subcontract list does not include Electrical, Fire services and water pump or Lift.",Arrays.asList("Lift"));
        cases.put("Selected trades: Electrical: Yes. Air-conditioning and mechanical ventilation: No.",Arrays.asList("Electrical","Air-conditioning and mechanical ventilation"));
        cases.put("Please confirm whether Electrical, Fire services and Lift are selected.",Arrays.asList("Electrical","Fire services","Lift"));
        cases.put("The selected subcontract list comprises Fire services and water pump; Lift and escalator.",Arrays.asList("Fire services","Lift"));
        for(Map.Entry<String,List<String>> fixture:cases.entrySet()) {
            String project=project();upload(project,"test-only-negative-trades.txt",fixture.getKey());
            when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("subcontractors",fixture.getValue(),fixture.getKey()))),"[]");
            extract(project,0);
            assertEquals("",variable(project,"subcontractors").path("value").asText(),fixture.getKey());
            assertTrue(trace(project).path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
        }
    }

    @Test void domesticQuestionsNegationsPendingConditionsAndExistingNeighboursCannotSupportYes() throws Exception {
        for(String quote:Arrays.asList(
                "Does this scope include domestic blocks? Decision pending.",
                "The Works do not include domestic blocks.",
                "The Works include no domestic blocks.",
                "Domestic block construction: No.",
                "Please confirm whether the Works include construction of domestic blocks.",
                "Whether domestic block construction is included remains pending confirmation.",
                "The template says: If the Works include domestic blocks, retain this clause.",
                "The adjacent existing domestic block is outside the Works. The contract covers only a podium.",
                "One option would construct a domestic block, but the option has not been selected.")) {
            String project=project();upload(project,"test-only-domestic-counterexample.txt",quote);
            when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("domesticBlocks",true,quote))),"[]");
            extract(project,0);
            assertEquals("",variable(project,"domesticBlocks").path("value").asText(),quote);
            assertTrue(trace(project).path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
        }
    }

    @Test void anEmptyTradeListNeedsExplicitNoneAndWorkCategoryMentionNeedsSelectedTradeContext() throws Exception {
        for(String quote:Arrays.asList("Select Building Services Specialist Sub-Contracts (BSSSC).","9 | Electrical Works | Schedule of Rates")) {
            String project=project();upload(project,"test-only-trade-scope-context.txt",quote);
            Object value=quote.startsWith("9")?Arrays.asList("Electrical"):Collections.emptyList();
            when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("subcontractors",value,quote))),"[]");
            extract(project,0);assertEquals("",variable(project,"subcontractors").path("value").asText(),quote);
            assertTrue(trace(project).path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
        }
        String project=project();String none="No specialist subcontract trades are selected for this list.";
        upload(project,"test-only-explicit-no-trades.txt",none);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("subcontractors",Collections.emptyList(),none))));
        extract(project,0);assertEquals("[]",variable(project,"subcontractors").path("value").asText());
    }

    @Test void fullBillDescriptionWithSemicolonKeepsExplicitTypeWhileQuestionCannotEstablishType() throws Exception {
        String project=project();String description="Site Safety; Environmental Management and Other Sundry Requirements (All Provisional)";
        String quote="12 | "+description+" | Bill of Quantities (BQ)\nBill1 Preliminaries: Is this a BQ?";
        upload(project,"test-only-bill-name-and-question.txt",quote);
        Map<String,Object> twelfth=new LinkedHashMap<>();twelfth.put("number","12");twelfth.put("description",description);twelfth.put("type","BQ");
        Map<String,Object> first=new LinkedHashMap<>();first.put("number","1");first.put("description","Preliminaries");first.put("type","BQ");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("billNos",Arrays.asList(twelfth,first),quote))));
        extract(project,0);JsonNode bills=JsonUtils.parse(variable(project,"billNos").path("value").asText());
        assertEquals(description,bills.get(0).path("description").asText());assertEquals("BQ",bills.get(0).path("type").asText());
        assertFalse(bills.get(1).hasNonNull("type"));
    }

    @Test void ordinaryNullCannotHideDirectScopeButAnExplicitSourcePendingAnswerIsNotForced() throws Exception {
        String domestic="The project scope includes construction of domestic blocks.";
        String project=project();upload(project,"test-only-domestic-null.txt",domestic);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("domesticBlocks",null,domestic))),
                JsonUtils.write(Arrays.asList(candidate("domesticBlocks",true,domestic))));
        extract(project,0);assertEquals("true",variable(project,"domesticBlocks").path("value").asText());
        assertEquals("coverage",trace(project).path("parts").get(0).path("attempts").get(1).path("kind").asText());
        String pending="Domestic block classification remains pending confirmation.";
        project=project();upload(project,"test-only-domestic-pending.txt",domestic+"\n"+pending);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("domesticBlocks",null,pending))));
        extract(project,0);assertEquals("",variable(project,"domesticBlocks").path("value").asText());
        assertEquals(1,trace(project).path("parts").get(0).path("attempts").size());
        assertEquals("source_unresolved",field(trace(project),"domesticBlocks").path("status").asText());
    }

    @Test void emptyCoverageRemainsExplicitlyUnresolvedInsteadOfForcingTheDirectFact() throws Exception {
        String project=project();String source="The project scope describes a residential block over a podium.";
        upload(project,"test-only-empty-coverage.txt",source);when(externalModel.chat(anyList())).thenReturn("[]","[]");
        extract(project,0);JsonNode report=trace(project);
        assertEquals("coverage_unresolved",field(report,"domesticBlocks").path("status").asText());
        assertEquals("",variable(project,"domesticBlocks").path("value").asText());
        assertFalse(variable(project,"domesticBlocks").path("confirmed").asBoolean());
        assertEquals(2,report.path("parts").get(0).path("attempts").size());
    }

    @Test void foundationPendingCannotBeTransferredToIndependentDomesticScopeInCoverage() throws Exception {
        String domestic="The Structural Engineer noted that PRE.B2.010 describes a 31-storey domestic block over a six-storey podium.";
        String heading="Foundation scope remains unresolved";
        for(String quote:Arrays.asList(heading+"\n\n"+domestic,"Foundation construction remains pending.")) {
            String foundation=quote.startsWith(heading)?heading:"Foundation construction remains pending.";
            String project=project();upload(project,"test-only-independent-scope.txt",foundation+"\n\n"+domestic);
            Map<String,Object> unanswered=candidate("domesticBlocks",null,quote);
            unanswered.put("reason","The model incorrectly transfers the unresolved foundation classification to domestic construction.");
            String raw=JsonUtils.write(Arrays.asList(unanswered));
            when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("foundationIncluded",null,foundation))),raw);
            extract(project,0);JsonNode report=trace(project);
            assertEquals("coverage_unresolved",field(report,"domesticBlocks").path("status").asText(),quote);
            assertEquals("source_unresolved",field(report,"foundationIncluded").path("status").asText());
            assertFalse(report.path("decisions").get(1).path("codes").toString().contains("source_unresolved"));
            assertTrue(report.path("decisions").get(1).path("rawValue").isNull());
            assertEquals(quote,report.path("decisions").get(1).path("sourceQuote").asText());
            assertEquals(unanswered.get("reason"),report.path("decisions").get(1).path("reason").asText());
            assertEquals(raw,report.path("parts").get(0).path("attempts").get(1).path("rawResponse").asText());
            assertEquals("",variable(project,"domesticBlocks").path("value").asText());
            assertFalse(variable(project,"domesticBlocks").path("confirmed").asBoolean());
            assertEquals(2,report.path("parts").get(0).path("attempts").size());
        }
        String pending="Construction of domestic blocks remains unresolved pending scope review.";
        String project=project();upload(project,"test-only-domestic-still-pending.txt",domestic+"\n"+pending);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("domesticBlocks",null,pending))));
        extract(project,0);JsonNode report=trace(project);
        assertEquals("source_unresolved",field(report,"domesticBlocks").path("status").asText());
        assertEquals(1,report.path("parts").get(0).path("attempts").size());
        assertEquals("",variable(project,"domesticBlocks").path("value").asText());
        assertFalse(variable(project,"domesticBlocks").path("confirmed").asBoolean());
    }

    @Test void explicitlySelectedCoverageFailureRetainsUserValuesAndAnImmutableFailedReport() throws Exception {
        String project=project();String source="The project scope includes domestic blocks. No foundation works.";
        upload(project,"test-only-failed-coverage.txt",source);
        mvc.perform(put("/api/drafting/{id}/variables/foundationIncluded",project).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"true\"}"))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode before=response(get("/api/drafting/{id}/variables",project));
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("foundationIncluded",false,"No foundation works."))))
                .thenThrow(new com.consense.common.BizException(5001,"Test-only selected-source outage"));
        mvc.perform(post("/api/drafting/{id}/variables/extract",project).header("X-ConSense-Llm-Profile","local"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(5001));
        assertEquals(before,response(get("/api/drafting/{id}/variables",project)));
        JsonNode report=trace(project);assertEquals("failed",report.path("status").asText());
        assertEquals("local",report.path("modelIdentity").path("profileId").asText());
        assertEquals("coverage",report.path("parts").get(0).path("attempts").get(1).path("kind").asText());
        assertEquals("model_call_failed",report.path("parts").get(0).path("attempts").get(1).path("errorCode").asText());
        assertEquals(report,response(get("/api/drafting/{id}/variables/extract-traces/{runId}",project,report.path("runId").asText())));
    }

    @Test void billDescriptionMentionIsNotADirectResidentialScopeDecision() throws Exception {
        String quote="6 | Plumbing Works for Podium and Domestic Block | Bill schedule entry";
        String project=project();upload(project,"test-only-bill-domestic-mention.txt",quote);
        when(externalModel.chat(anyList())).thenReturn("[]");extract(project,0);
        JsonNode mentionAttempts=trace(project).path("parts").get(0).path("attempts");
        assertEquals("primary",mentionAttempts.get(0).path("kind").asText());
        for(JsonNode attempt:mentionAttempts)assertNotEquals("coverage",attempt.path("kind").asText(),"A Bill description is not a direct residential-scope decision.");
        project=project();upload(project,"test-only-bill-domestic-affirmation.txt",quote);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("domesticBlocks",true,quote))),"[]");
        extract(project,0);assertEquals("",variable(project,"domesticBlocks").path("value").asText());
        assertTrue(trace(project).path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
    }

    @Test void joinedUnknownCitationCanBeRepairedToAnExactUnresolvedPassageWithoutFillingTheValue() throws Exception {
        String table="Exact accepted contract period | Not supplied; Column B is blank | SIM03";
        String closing="No final awarded-period value is invented to close the checklist.";
        String project=project();upload(project,"test-only-joined-period-quote.txt",table+"\nOther current inputs remain unchanged.\n"+closing);
        String joined=table+"\n"+closing;
        Map<String,Object> original=candidate("contractPeriodMonths",null,joined);original.put("reason","No exact accepted period supplied; only a proposed ceiling is given.");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(original)),
                JsonUtils.write(Arrays.asList(candidate("contractPeriodMonths",null,table))));
        extract(project,0);JsonNode report=trace(project);JsonNode decisions=report.path("decisions");
        assertEquals("source_unresolved",field(report,"contractPeriodMonths").path("status").asText());
        assertTrue(decisions.get(0).path("rawValue").isNull());assertEquals(joined,decisions.get(0).path("sourceQuote").asText());
        assertTrue(decisions.get(0).path("codes").toString().contains("quote_not_in_part"));
        assertEquals("unanswered",decisions.get(0).path("status").asText());
        assertTrue(decisions.get(1).path("codes").toString().contains("source_unresolved"));
        assertEquals("repair",report.path("parts").get(0).path("attempts").get(1).path("kind").asText());
        assertEquals(table,variable(project,"contractPeriodMonths").path("source").asText());
        assertEquals("",variable(project,"contractPeriodMonths").path("value").asText());assertFalse(variable(project,"contractPeriodMonths").path("confirmed").asBoolean());
    }

    @Test void returnMediaWithinTwoEnvelopeCannotFillAlternativeProcedureWhileActualAlternativeRemainsSupported() throws Exception {
        String media="Record the detailed submission medium as hardcopy plus DVD-ROM under the two-envelope procedure.";
        String project=project();upload(project,"test-only-current-tender-medium.txt",media);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("otherTenderingArrangement",media,media))),"[]");
        extract(project,0);assertEquals("",variable(project,"otherTenderingArrangement").path("value").asText());
        assertEquals("rejected",trace(project).path("decisions").get(0).path("status").asText());
        assertTrue(trace(project).path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
        String alternative="Adopt a single-envelope tender procedure instead of the two-envelope procedure; replace SCT5 with the attached submission and evaluation provisions.";
        project=project();upload(project,"test-only-actual-alternative.txt",alternative);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("otherTenderingArrangement",alternative,alternative))));
        extract(project,0);assertEquals(alternative,variable(project,"otherTenderingArrangement").path("value").asText());
        assertFalse(variable(project,"otherTenderingArrangement").path("confirmed").asBoolean());
    }

    @Test void genericTenderProcedurePendingCannotClaimAnAlternativeProcedureIsSourceUnresolved() throws Exception {
        String generic="The programme threshold, bill descriptions, subcontract arrangement and tendering procedure remain outstanding.";
        String project=project();upload(project,"test-only-generic-tender-pending.txt",generic);
        Map<String,Object> unknown=candidate("otherTenderingArrangement",null,generic);
        unknown.put("reason","No alternative tender procedure or replacement SCT5 wording supplied.");
        String raw=JsonUtils.write(Arrays.asList(unknown));when(externalModel.chat(anyList())).thenAnswer(invocation->{
            List<LlmClient.ChatTurn> turns=invocation.getArgument(0);
            return turns.size()>1&&turns.get(1).getContent().contains("One bounded context recall")?"[]":raw;
        });
        extract(project,0);JsonNode report=trace(project);
        assertEquals("model_unanswered",field(report,"otherTenderingArrangement").path("status").asText());
        assertFalse(report.path("decisions").get(0).path("codes").toString().contains("source_unresolved"));
        assertTrue(report.path("decisions").get(0).path("rawValue").isNull());
        assertEquals(generic,report.path("decisions").get(0).path("sourceQuote").asText());
        assertEquals(unknown.get("reason"),report.path("decisions").get(0).path("reason").asText());
        assertEquals(raw,report.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
        assertEquals("primary",report.path("parts").get(0).path("attempts").get(0).path("kind").asText());
        for(JsonNode attempt:report.path("parts").get(0).path("attempts"))assertNotEquals("repair",attempt.path("kind").asText(),"An ordinary unknown cannot trigger value repair.");
        for(String quote:Arrays.asList("Alternative tender procedure and replacement SCT5 wording remain outstanding.","SCT5 replacement wording remains pending confirmation.")) {
            project=project();upload(project,"test-only-alternative-pending.txt",quote);
            when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("otherTenderingArrangement",null,quote))));
            extract(project,0);
            assertEquals("source_unresolved",field(trace(project),"otherTenderingArrangement").path("status").asText(),quote);
            assertEquals("",variable(project,"otherTenderingArrangement").path("value").asText());
            assertFalse(variable(project,"otherTenderingArrangement").path("confirmed").asBoolean());
        }
    }

    @Test void emptyBillListNeedsExplicitProjectAbsenceRatherThanCountReferenceOrMissingDescriptions() throws Exception {
        for(String quote:Arrays.asList(
                "Bill numbers and descriptions | 13 entries; see SIM04 | SIM04",
                "No bill descriptions supplied in this email; see the project bill schedule.",
                "Please provide the bill numbers and descriptions for the project.",
                "Are there no Bills included in this project?")) {
            String project=project();upload(project,"test-only-empty-bill-counterexample.txt",quote);
            Map<String,Object> answer=candidate("billNos",Collections.emptyList(),quote);
            answer.put("reason","The list itself is not supplied in this source.");
            String raw=JsonUtils.write(Arrays.asList(answer));when(externalModel.chat(anyList())).thenReturn(raw,"[]");
            extract(project,0);JsonNode report=trace(project);
            assertEquals("rejected",report.path("decisions").get(0).path("status").asText(),quote);
            assertTrue(report.path("decisions").get(0).path("codes").toString().contains("quote_value_mismatch"));
            assertEquals(0,report.path("decisions").get(0).path("rawValue").size());
            assertEquals(quote,report.path("decisions").get(0).path("sourceQuote").asText());
            assertEquals(raw,report.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
            assertTrue(report.path("decisions").get(0).path("codes").toString().contains("unsupported_empty_list"));
            // Unsupported absence uses bounded original-context recall when cued, never an empty-value repair.
            JsonNode attempts=report.path("parts").get(0).path("attempts");
            for(int i=1;i<attempts.size();i++)assertEquals("recall",attempts.get(i).path("kind").asText());
            assertEquals("",variable(project,"billNos").path("value").asText());
            assertFalse(variable(project,"billNos").path("confirmed").asBoolean());
        }
        String quote="No Bills are included in this project.";
        String project=project();upload(project,"test-only-explicit-no-bills.txt",quote);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("billNos",Collections.emptyList(),quote))));
        extract(project,0);JsonNode report=trace(project);
        assertEquals("accepted",report.path("decisions").get(0).path("status").asText());
        assertEquals("[]",variable(project,"billNos").path("value").asText());
        assertFalse(variable(project,"billNos").path("confirmed").asBoolean());
        assertEquals(1,report.path("parts").get(0).path("attempts").size());
    }

    @Test void unresolvedCitationRepairCannotInventAnAcceptedDurationFromTheBaseline() throws Exception {
        String table="Exact accepted contract period | Not supplied; Column B is blank | SIM03";
        String closing="No final awarded-period value is invented to close the checklist.";
        String project=project();upload(project,"test-only-forbidden-duration-repair.txt",table+"\nA separate paragraph lies between.\n"+closing);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("contractPeriodMonths",null,table+"\n"+closing))),
                JsonUtils.write(Arrays.asList(candidate("contractPeriodMonths",31,table))));
        extract(project,0);JsonNode report=trace(project);
        assertEquals("",variable(project,"contractPeriodMonths").path("value").asText());
        assertEquals("rejected",report.path("decisions").get(1).path("status").asText());
        assertTrue(report.path("decisions").get(1).path("codes").toString().contains("unanswered_repair_value_not_allowed"));
        assertTrue(report.path("decisions").get(0).path("rawValue").isNull());
    }

    @Test void missingUnknownQuoteDoesNotTriggerCitationRepairOrClaimSourceUnresolved() throws Exception {
        String project=project();upload(project,"test-only-no-null-quote.txt","Exact accepted contract period is not supplied.");
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("contractPeriodMonths",null,""))));
        extract(project,0);JsonNode report=trace(project);
        assertEquals(1,report.path("parts").get(0).path("attempts").size());
        assertEquals("model_unanswered",field(report,"contractPeriodMonths").path("status").asText());
        assertFalse(report.path("decisions").get(0).path("codes").toString().contains("quote_not_in_part"));
    }

    @Test void failedExplicitUnknownCitationRepairRetainsTheUsersAcceptedDurationAtomically() throws Exception {
        String table="Exact accepted contract period is not supplied.";String closing="No final awarded-period value is invented.";
        String project=project();upload(project,"test-only-failed-null-repair.txt",table+"\nA separate paragraph.\n"+closing);
        mvc.perform(put("/api/drafting/{id}/variables/contractPeriodMonths",project).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"36\"}"))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode before=response(get("/api/drafting/{id}/variables",project));
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("contractPeriodMonths",null,table+"\n"+closing))))
                .thenThrow(new com.consense.common.BizException(5001,"Test-only selected-source citation-repair outage"));
        mvc.perform(post("/api/drafting/{id}/variables/extract",project).header("X-ConSense-Llm-Profile","local"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(5001));
        assertEquals(before,response(get("/api/drafting/{id}/variables",project)));
        JsonNode report=trace(project);assertEquals("failed",report.path("status").asText());
        assertEquals("local",report.path("modelIdentity").path("profileId").asText());
        assertEquals("repair",report.path("parts").get(0).path("attempts").get(1).path("kind").asText());
        assertEquals("model_call_failed",report.path("parts").get(0).path("attempts").get(1).path("errorCode").asText());
    }

    @Test void citationRepairValueRestrictionDoesNotPreventTheSeparateDirectScopeCoveragePath() throws Exception {
        String domestic="The Works comprise a domestic block.";String closing="No unrelated project fact is added.";
        String project=project();upload(project,"test-only-domestic-citation-and-coverage.txt",domestic+"\nIntervening source paragraph.\n"+closing);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("domesticBlocks",null,domestic+"\n"+closing))),
                JsonUtils.write(Arrays.asList(candidate("domesticBlocks",true,domestic))),
                JsonUtils.write(Arrays.asList(candidate("domesticBlocks",true,domestic))));
        extract(project,0);JsonNode report=trace(project);
        assertEquals("true",variable(project,"domesticBlocks").path("value").asText());assertFalse(variable(project,"domesticBlocks").path("confirmed").asBoolean());
        assertEquals("unanswered_repair_value_not_allowed",report.path("decisions").get(1).path("codes").get(0).asText());
        assertEquals("coverage",report.path("parts").get(0).path("attempts").get(2).path("kind").asText());
        assertEquals("accepted",report.path("decisions").get(2).path("status").asText());
    }

    private Map<String,Object> candidate(String key,Object value,String quote) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);
        item.put("sourceQuote",quote);item.put("reason","Test-only source-supported suggestion");item.put("confidence",1);return item;
    }
    private String project() throws Exception {
        String id="hotfix-test-only-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY extraction hotfix\"}"))
                .andExpect(jsonPath("$.code").value(0));return id;
    }
    private void upload(String project,String name,String text) throws Exception {
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",project).file(new MockMultipartFile("files",name,"text/plain",text.getBytes(StandardCharsets.UTF_8))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
    }
    private void extract(String project,int code) throws Exception {
        mvc.perform(post("/api/drafting/{id}/variables/extract",project)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code));
    }
    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return JsonUtils.parse(mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode trace(String project) throws Exception {return response(get("/api/drafting/{id}/variables/extract-trace",project));}
    private JsonNode variable(String project,String key) throws Exception {
        for(JsonNode input:response(get("/api/drafting/{id}/variables",project)))if(key.equals(input.path("key").asText()))return input;
        throw new AssertionError("Missing input: "+key);
    }
    private JsonNode field(JsonNode trace,String key) {
        for(JsonNode input:trace.path("fields"))if(key.equals(input.path("key").asText()))return input;
        throw new AssertionError("Missing diagnostic: "+key);
    }
}
