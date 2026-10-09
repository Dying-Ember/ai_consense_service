package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Agreed public upload/extract/variables/immutable-trace seam; only external LLM is doubled. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingJointEvidenceIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final String KEY="additionalSubmissions";
    private static final String ORIGINAL="Additional tender submissions for Zone A: provide a signed environmental management plan.";
    private static final String SUPPLEMENT="In addition to the signed environmental management plan required for the Zone A tender, provide a construction method statement as an additional tender submission.";
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:joint_evidence_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","joint-evidence-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired DraftingService service;
    @MockBean LlmClient model;
    private final List<String> jointReplies=new ArrayList<>();
    private String secondaryQuote=SUPPLEMENT,relationChoice="supplement";
    private java.util.function.Consumer<Map<String,Object>> reviewMutation=reply->{ };
    private java.util.function.Function<String,String> primaryOverride;
    private String jointRawOverride;
    @BeforeEach void available() {
        when(model.available()).thenReturn(true);when(model.chatModel()).thenReturn("TEST ONLY joint evidence");
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<LlmClient.ChatTurn> turns=invocation.getArgument(0);String user=turns.get(turns.size()-1).getContent();
            if(user.contains("<joint-review-packets>")) {
                String json=user.substring(user.indexOf("<joint-review-packets>")+22,user.indexOf("</joint-review-packets>"));
                JsonNode packets=JsonUtils.parse(json);
                List<Map<String,Object>> refs=new ArrayList<>();
                for(JsonNode packet:packets)refs.add(DraftBusinessRules.map("decisionRef",packet.path("decisionRef").asInt(),
                        "sourceDocumentId",packet.path("sourceDocumentId").asLong(),"sourceHash",packet.path("sourceHash").asText(),
                        "sourceStart",packet.path("context").path("sourceStart").asInt(),"sourceEnd",packet.path("context").path("sourceEnd").asInt(),
                        "sourceQuote",packet.path("sourceQuote").asText()));
                String key=user.substring("Variable key:".length(),user.indexOf('\n')).trim();
                Map<String,Object> proposed=DraftBusinessRules.map("key",key,"relation",relationChoice,"reason","TEST ONLY source relation proposal", "confidence",0.95,"evidence",refs,"scopeQuote","Zone A tender");
                if(Arrays.asList("supplement","explicit_replacement").contains(relationChoice)) {
                    JsonNode from=packets.get(0),to=packets.get(1);if(ORIGINAL.equals(to.path("sourceQuote").asText())){from=packets.get(1);to=packets.get(0);}
                    proposed.put("fromDecisionRef",from.path("decisionRef").asInt());proposed.put("toDecisionRef",to.path("decisionRef").asInt());
                }
                reviewMutation.accept(proposed);String raw=jointRawOverride==null?JsonUtils.write(proposed):jointRawOverride;
                jointReplies.add(raw);return raw;
            }
            return primaryOverride==null?reply(user.contains(secondaryQuote)?secondaryQuote:ORIGINAL):primaryOverride.apply(user);
        });
    }

    @Test void replacementWithADifferentSourceScopeCannotUseAnInventedSharedScope()throws Exception {
        relationChoice="explicit_replacement";secondaryQuote="Additional tender submissions for Zone B: this replaces the signed environmental management plan with a site risk register.";
        String project=project();upload(project,"test-only-2027-latest.docx","TEST ONLY original correspondence\nDate: 2026-10-06\nScope: Zone A tender.\n"+ORIGINAL);
        upload(project,"test-only-2020-older.docx","TEST ONLY different scope\nDate: 2026-10-07\nScope: Zone B tender.\n"+secondaryQuote);
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("different-scope",currentTrace,input);
        assertEquals(2,input.path("candidates").size());assertFalse(input.path("confirmed").asBoolean());
        JsonNode relation=currentTrace.path("relations").get(0);assertEquals("rejected",relation.path("status").asText(),"Zone A cannot be quoted as the common scope when the corrective passage is only Zone B.");
        assertEquals("undetermined",relation.path("relation").asText());assertTrue(relation.path("codes").toString().contains("joint_scope_invalid"));
        assertEquals("explicit_replacement",relation.path("rawProposal").path("relation").asText());assertEquals(jointReplies.get(0),relation.path("rawResponse").asText());
    }

    @Test void aQuotedDenialOfReplacementCannotBecomeAnExplicitReplacementProposal()throws Exception {
        relationChoice="explicit_replacement";secondaryQuote="Additional tender submissions for Zone A: provide a construction method statement. This does not replace the signed environmental management plan.";
        String project=project();upload(project,"test-only-original.docx","TEST ONLY original correspondence\nDate: 2026-10-06\nScope: Zone A tender.\n"+ORIGINAL);
        upload(project,"test-only-later.docx","TEST ONLY later correspondence\nDate: 2026-10-07\nScope: Zone A tender.\n"+secondaryQuote);
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("denied-replacement",currentTrace,input);
        assertEquals(2,input.path("candidates").size());JsonNode relation=currentTrace.path("relations").get(0);
        assertEquals("rejected",relation.path("status").asText(),"A source expressly denying replacement cannot support that model proposal.");
        assertEquals("undetermined",relation.path("relation").asText());assertTrue(relation.path("codes").toString().contains("joint_amendment_language_missing"));
        assertEquals(secondaryQuote,relation.path("rawProposal").path("evidence").get(1).path("sourceQuote").asText());
    }

    @SuppressWarnings("unchecked")
    @Test void overflowedProviderRangesCannotWrapToAnOriginalOffset()throws Exception {
        reviewMutation=proposed->((List<Map<String,Object>>)proposed.get("evidence")).get(0).put("sourceStart",4294967296L);
        String project=project();upload(project,"test-only-original.docx","TEST ONLY original correspondence\nScope: Zone A tender.\n"+ORIGINAL);
        upload(project,"test-only-supplement.docx","TEST ONLY supplementary correspondence\nScope: Zone A tender.\n"+SUPPLEMENT);
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("overflow-range",currentTrace,input);
        assertEquals(2,input.path("candidates").size());JsonNode relation=currentTrace.path("relations").get(0);
        assertEquals("rejected",relation.path("status").asText(),"A fabricated offset cannot wrap to the server's original range.");
        assertTrue(relation.path("codes").toString().contains("joint_source_range_invalid"));
        assertEquals(4294967296L,relation.path("rawProposal").path("evidence").get(0).path("sourceStart").asLong());
    }

    @SuppressWarnings("unchecked")
    @Test void nearbyOtherFieldAmendmentCannotSupplyTheComparedCandidateRelation()throws Exception {
        String unrelated="PSE submissions for Zone A tender: this replaces the feasibility note with a drainage schedule.";
        relationChoice="explicit_replacement";reviewMutation=proposed->((List<Map<String,Object>>)proposed.get("evidence")).get(1).put("sourceQuote",unrelated);
        String project=project();upload(project,"test-only-original.docx","TEST ONLY original correspondence\nScope: Zone A tender.\n"+ORIGINAL);
        upload(project,"test-only-supplement.docx","TEST ONLY supplementary correspondence\nScope: Zone A tender.\n"+SUPPLEMENT+"\n"+unrelated);
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("unrelated-amendment",currentTrace,input);
        assertEquals(2,input.path("candidates").size());JsonNode relation=currentTrace.path("relations").get(0);
        assertEquals("rejected",relation.path("status").asText(),"A real nearby PSE replacement does not establish replacement of additional tender submissions.");
        assertTrue(relation.path("codes").toString().contains("joint_quote_not_candidate_evidence"));
        assertEquals(unrelated,relation.path("rawProposal").path("evidence").get(1).path("sourceQuote").asText());
    }

    @Test void sameDayExplicitCorrectionRecordsItsDirectionAndRetainsTheAdoptedEarlierValue()throws Exception {
        relationChoice="explicit_replacement";secondaryQuote="For the Zone A tender, this explicitly replaces the signed environmental management plan requirement: provide a construction method statement as the sole additional tender submission.";
        String project=project();upload(project,"test-only-z-last-file.docx","TEST ONLY original correspondence\nDate: 2026-10-06\nScope: Zone A tender.\n"+ORIGINAL);
        extract(project);JsonNode earlier=trace(project);mvc.perform(put("/api/drafting/{id}/variables/{key}",project,KEY).contentType(MediaType.APPLICATION_JSON).content(DraftAdoptionTestPayload.candidate(service,project,KEY,0))).andExpect(jsonPath("$.code").value(0));
        JsonNode adopted=variable(project);upload(project,"test-only-a-first-file.docx","TEST ONLY explicit correction\nDate: 2026-10-06\nScope: Zone A tender.\n"+secondaryQuote);
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("same-day-correction",currentTrace,input);
        assertEquals(adopted.path("value"),input.path("value"));assertEquals(adopted.path("source"),input.path("source"));assertTrue(input.path("confirmed").asBoolean());assertTrue(input.path("reviewRequired").asBoolean());assertEquals(2,input.path("candidates").size());
        JsonNode relation=currentTrace.path("relations").get(0);assertEquals("proposed",relation.path("status").asText());assertEquals("explicit_replacement",relation.path("relation").asText());
        assertTrue(relation.path("fromDecisionRef").isInt()&&relation.path("toDecisionRef").isInt(),"A replacement must identify which original candidate is replaced and which corrective candidate is proposed.");
        assertEquals(ORIGINAL,currentTrace.path("decisions").get(relation.path("fromDecisionRef").asInt()).path("sourceQuote").asText());
        assertEquals(secondaryQuote,currentTrace.path("decisions").get(relation.path("toDecisionRef").asInt()).path("sourceQuote").asText());
        assertEquals(earlier.path("decisions"),response("/api/drafting/"+project+"/variables/extract-traces/"+earlier.path("runId").asText()).path("decisions"));
    }

    @Test void aSharedSourceDateIsNotEvidenceOfCommonApplicableScope()throws Exception {
        relationChoice="explicit_replacement";secondaryQuote="Additional tender submissions for Zone B: this replaces the signed environmental management plan with a site risk register.";
        reviewMutation=proposed->proposed.put("scopeQuote","2026-10-06");
        String project=project();upload(project,"test-only-first.docx","TEST ONLY original correspondence\nDate: 2026-10-06\nScope: Zone A tender.\n"+ORIGINAL);
        upload(project,"test-only-second.docx","TEST ONLY different scope\nDate: 2026-10-06\nScope: Zone B tender.\n"+secondaryQuote);
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("date-as-scope",currentTrace,input);
        assertEquals(2,input.path("candidates").size());JsonNode relation=currentTrace.path("relations").get(0);
        assertEquals("rejected",relation.path("status").asText(),"The same quoted calendar date does not establish common scope for a replacement.");
        assertTrue(relation.path("codes").toString().contains("joint_scope_invalid"));assertEquals("2026-10-06",relation.path("rawProposal").path("scopeQuote").asText());
    }

    @Test void aShortCorrectionQuoteCannotHideItsLinkedPendingOriginalQualification()throws Exception {
        relationChoice="explicit_replacement";secondaryQuote="For the Zone A tender, this replaces the signed environmental management plan requirement: provide a construction method statement as the sole additional tender submission.";
        String project=project();upload(project,"test-only-original.docx","TEST ONLY original correspondence\nScope: Zone A tender.\n"+ORIGINAL);
        upload(project,"test-only-correction.docx","TEST ONLY pending correction\nScope: Zone A tender.\n"+secondaryQuote+" However, this replacement remains unconfirmed pending the procurement review.");
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("pending-correction-context",currentTrace,input);
        assertEquals(2,input.path("candidates").size());JsonNode relation=currentTrace.path("relations").get(0);
        assertEquals("rejected",relation.path("status").asText(),"The original paragraph leaves this particular replacement pending despite the short affirmative quote.");
        assertTrue(relation.path("codes").toString().contains("joint_amendment_context_unresolved"));
        assertTrue(relation.path("evidence").get(1).path("context").path("sourceText").asText().contains("unconfirmed pending the procurement review"));
    }

    @Test void anUnrelatedPendingFieldDoesNotBlockAnExplicitSameScopeCorrection()throws Exception {
        relationChoice="explicit_replacement";secondaryQuote="For the Zone A tender, this replaces the signed environmental management plan requirement: provide a construction method statement as the sole additional tender submission.";
        String project=pairedSources(secondaryQuote+" PSE requirements remain pending until the missing classification is supplied.");
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("unrelated-pending-positive",currentTrace,input);
        assertEquals(2,input.path("candidates").size());assertEquals("proposed",currentTrace.path("relations").get(0).path("status").asText());
        assertEquals("explicit_replacement",currentTrace.path("relations").get(0).path("relation").asText());
    }

    @Test void laterSourceDatesAndUploadOrderCannotSupplyMissingReplacementLanguage()throws Exception {
        relationChoice="explicit_replacement";secondaryQuote="Additional tender submissions for Zone A: provide a construction method statement.";
        for(boolean reverse:Arrays.asList(false,true)) {
            String project=project();String original="TEST ONLY original\nDate: 2026-10-06\nScope: Zone A tender.\n"+ORIGINAL,later="TEST ONLY later\nDate: 2026-10-07\nScope: Zone A tender.\n"+secondaryQuote;
            if(reverse)upload(project,"test-only-oldest-2000.docx",later);upload(project,"test-only-latest-2030.docx",original);if(!reverse)upload(project,"test-only-oldest-2000.docx",later);
            extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("date-only-order-"+reverse,currentTrace,input);
            assertEquals(2,input.path("candidates").size());assertEquals("",input.path("value").asText());assertFalse(input.path("confirmed").asBoolean());
            JsonNode relation=currentTrace.path("relations").get(0);assertEquals("rejected",relation.path("status").asText());assertTrue(relation.path("codes").toString().contains("joint_amendment_language_missing"));
        }
    }

    @Test void contradictionAndUndeterminedProposalsLeaveBothCandidatesForHumanResolution()throws Exception {
        secondaryQuote="Additional tender submissions for Zone A: submit only a construction method statement; a signed environmental management plan must not be submitted.";
        for(String kind:Arrays.asList("contradiction","undetermined")) {
            relationChoice=kind;String project=pairedSources(secondaryQuote);extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain(kind,currentTrace,input);
            assertEquals(2,input.path("candidates").size());assertEquals("",input.path("value").asText());assertFalse(input.path("confirmed").asBoolean());
            JsonNode relation=currentTrace.path("relations").get(0);assertEquals(kind,relation.path("relation").asText());assertEquals("undetermined".equals(kind)?"undetermined":"proposed",relation.path("status").asText());
            if("undetermined".equals(kind))assertTrue(relation.path("codes").toString().contains("joint_relation_undetermined"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test void invalidProviderIdentityQuotesRangesAndDirectionAreRetainedAndRejected()throws Exception {
        Map<String,java.util.function.Consumer<Map<String,Object>>> cases=new LinkedHashMap<>();
        cases.put("source-id",p->((List<Map<String,Object>>)p.get("evidence")).get(0).put("sourceDocumentId",-9L));
        cases.put("source-hash",p->((List<Map<String,Object>>)p.get("evidence")).get(1).put("sourceHash","SYNTHETIC wrong hash"));
        cases.put("decision-ref",p->((List<Map<String,Object>>)p.get("evidence")).get(0).put("decisionRef",4294967296L));
        cases.put("source-range",p->((List<Map<String,Object>>)p.get("evidence")).get(0).put("sourceEnd",-1));
        cases.put("source-quote",p->((List<Map<String,Object>>)p.get("evidence")).get(1).put("sourceQuote","Fabricated source passage"));
        cases.put("field-key",p->p.put("key","billNos"));cases.put("relation-enum",p->p.put("relation","latest_wins"));
        cases.put("direction",p->p.put("toDecisionRef",p.get("fromDecisionRef")));cases.put("confidence",p->p.put("confidence",1.1));
        cases.put("scope-type",p->p.put("scopeQuote",2026));
        String[] expected={"joint_source_identity_invalid","joint_source_identity_invalid","joint_source_identity_invalid","joint_source_range_invalid","joint_quote_invalid","joint_invalid_relation","joint_invalid_relation","joint_direction_invalid","joint_confidence_invalid","joint_scope_invalid"};
        int i=0;for(Map.Entry<String,java.util.function.Consumer<Map<String,Object>>> example:cases.entrySet()) {
            reviewMutation=example.getValue();jointReplies.clear();String project=pairedSources(SUPPLEMENT);extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("guard-"+example.getKey(),currentTrace,input);
            assertEquals(2,input.path("candidates").size());JsonNode relation=currentTrace.path("relations").get(0);assertEquals("rejected",relation.path("status").asText(),example.getKey());
            assertEquals("undetermined",relation.path("relation").asText());assertTrue(relation.path("codes").toString().contains(expected[i++]),example.getKey());assertEquals(jointReplies.get(0),relation.path("rawResponse").asText());
        }
    }

    @Test void malformedAndFailedJointReviewDoesNotFailExtractionOrDiscardCandidates()throws Exception {
        for(boolean providerFails:Arrays.asList(false,true)) {
            jointRawOverride=providerFails?null:"{ invalid provider JSON";reviewMutation=providerFails?p->{throw new IllegalStateException("TEST ONLY provider unavailable");}:p->{ };
            String project=pairedSources(SUPPLEMENT);extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("joint-failure-"+providerFails,currentTrace,input);
            assertEquals("completed",currentTrace.path("status").asText());assertEquals(2,input.path("candidates").size());JsonNode relation=currentTrace.path("relations").get(0);
            assertEquals("failed",relation.path("status").asText());assertTrue(relation.path("codes").toString().contains(providerFails?"joint_provider_failed":"joint_invalid_json"));
            if(providerFails)assertTrue(relation.path("rawResponse").isNull());else assertEquals(jointRawOverride,relation.path("rawResponse").asText());
            assertEquals(2,relation.path("evidence").size());assertTrue(relation.path("userPrompt").asText().contains("<joint-review-packets>"));
        }
    }

    @Test void oneFieldReviewsOnlyOnePairAndRecordsItsRemainingAlternativeAsBudgetSkipped()throws Exception {
        relationChoice="undetermined";String third="Additional tender submissions for Zone A: provide an independent site risk register.";
        primaryOverride=user->reply(user.contains(third)?third:user.contains(SUPPLEMENT)?SUPPLEMENT:ORIGINAL);
        String project=pairedSources(SUPPLEMENT);upload(project,"test-only-third.docx","TEST ONLY third correspondence\nScope: Zone A tender.\n"+third);
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("field-budget",currentTrace,input);assertEquals(3,input.path("candidates").size());
        assertEquals(2,currentTrace.path("relations").size());int reviewed=0,skipped=0;
        for(JsonNode relation:currentTrace.path("relations"))if("skipped".equals(relation.path("status").asText())){skipped++;assertTrue(relation.path("codes").toString().contains("field_pair_budget_exhausted"));assertTrue(relation.path("rawResponse").isNull());}else{reviewed++;assertEquals("undetermined",relation.path("status").asText());}
        assertEquals(1,reviewed);assertEquals(1,skipped);
    }

    @Test void aRunReviewsAtMostFourFieldsAndRetainsTheUnreviewedPairAndItsCandidates()throws Exception {
        relationChoice="undetermined";
        String[][] fields={{"projectArchitectPost","Project Architect post","Resident Architect","Chief Architect"},{"projectArchitectName","Project Architect name","Alice Smith","Bob Jones"},{"specificationInspectionFloor","Specification Library inspection floor","First floor","Third floor"},{"specificationInspectionBlock","Specification Library inspection block","East Block","West Block"},{"drawingsInspectionFloor","Drawings inspection floor","Second floor","Fourth floor"}};
        List<Map<String,Object>> before=new ArrayList<>(),after=new ArrayList<>();StringBuilder first=new StringBuilder("TEST ONLY first packet\nScope: Zone A tender.\n"),second=new StringBuilder("TEST ONLY second packet\nScope: Zone A tender.\n");
        for(String[] field:fields) {
            String oldQuote=field[1]+": "+field[2]+".",newQuote=field[1]+": "+field[3]+".";first.append(oldQuote).append('\n');second.append(newQuote).append('\n');
            before.add(DraftBusinessRules.map("key",field[0],"value",field[2],"sourceQuote",oldQuote,"reason","TEST ONLY literal field value","confidence",0.95));after.add(DraftBusinessRules.map("key",field[0],"value",field[3],"sourceQuote",newQuote,"reason","TEST ONLY literal field value","confidence",0.95));
        }
        primaryOverride=user->JsonUtils.write(user.contains("TEST ONLY second packet")?after:before);
        String project=project();upload(project,"test-only-first.docx",first.toString());upload(project,"test-only-second.docx",second.toString());extract(project);JsonNode currentTrace=trace(project);retain("run-budget",currentTrace,variable(project));
        assertEquals(5,currentTrace.path("relations").size());int reviewed=0,skipped=0;for(JsonNode relation:currentTrace.path("relations")) {
            if("skipped".equals(relation.path("status").asText())){skipped++;assertTrue(relation.path("codes").toString().contains("joint_review_budget_exhausted"));assertEquals(2,relation.path("evidence").size());assertTrue(relation.path("rawResponse").isNull());}else{reviewed++;assertEquals("undetermined",relation.path("status").asText());}
        }
        assertEquals(4,reviewed);assertEquals(1,skipped);for(String[] field:fields)for(JsonNode input:response("/api/drafting/"+project+"/variables"))if(field[0].equals(input.path("key").asText())){assertEquals(2,input.path("candidates").size(),field[0]);assertFalse(input.path("confirmed").asBoolean());}
    }

    @Test void equalValuesFromTwoSourcesDoNotConsumeJointReviewBudget()throws Exception {
        secondaryQuote=ORIGINAL;String project=pairedSources(ORIGINAL);extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("equal-values",currentTrace,input);
        assertEquals(2,input.path("candidates").size());assertTrue(currentTrace.path("relations").isEmpty());assertFalse(input.path("confirmed").asBoolean());
    }

    private String pairedSources(String second)throws Exception {String project=project();upload(project,"test-only-original.docx","TEST ONLY original correspondence\nDate: 2026-10-06\nScope: Zone A tender.\n"+ORIGINAL);upload(project,"test-only-second.docx","TEST ONLY second correspondence\nDate: 2026-10-07\nScope: Zone A tender.\n"+second);return project;}

    @Test void acceptedRecallUsesItsExactSuccessfulAttemptContextForJointEvidence() throws Exception {
        primaryOverride=user->{
            if(user.contains("One bounded context recall"))return user.contains("\""+KEY+"\"")&&user.contains(SUPPLEMENT)?reply(SUPPLEMENT):"[]";
            return user.contains(ORIGINAL)?reply(ORIGINAL):"[]";
        };
        String project=project();upload(project,"test-only-original.docx","Scope: Zone A tender.\n"+ORIGINAL);
        extract(project);
        mvc.perform(put("/api/drafting/{id}/variables/{key}",project,KEY).contentType(MediaType.APPLICATION_JSON).content(DraftAdoptionTestPayload.candidate(service,project,KEY,0)))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode adopted=variable(project);
        StringBuilder source=new StringBuilder("Scope: Zone A tender.\nAdditional tender submissions\n");
        for(int i=0;i<55;i++)source.append("Background information for unrelated logistics item ").append(i).append(" remains unchanged.\n");
        source.append(SUPPLEMENT);
        upload(project,"test-only-recalled-supplement.docx",source.toString());
        extract(project);JsonNode currentTrace=trace(project),input=variable(project);retain("accepted-recall-context",currentTrace,input);
        assertEquals(2,input.path("candidates").size());assertEquals(adopted.path("value"),input.path("value"));assertEquals(adopted.path("source"),input.path("source"));
        assertTrue(input.path("confirmed").asBoolean());assertTrue(input.path("reviewRequired").asBoolean());
        JsonNode decision=null,part=null,attempt=null;
        for(JsonNode row:currentTrace.path("decisions"))if("accepted".equals(row.path("status").asText())&&SUPPLEMENT.equals(row.path("sourceQuote").asText()))decision=row;
        assertNotNull(decision,"The original-source recall must first be accepted through the actual extraction API.");
        for(JsonNode row:currentTrace.path("parts"))if(row.path("partId").equals(decision.path("partId")))part=row;
        assertNotNull(part);
        for(JsonNode row:part.path("attempts"))if(row.path("attemptIndex").equals(decision.path("attemptIndex")))attempt=row;
        assertNotNull(attempt);assertEquals("recall",attempt.path("kind").asText());assertEquals("completed",attempt.path("status").asText());
        assertFalse(part.path("context").path("sourceText").asText().contains(SUPPLEMENT),"This accepted quote lies outside the owning part's original primary window.");
        JsonNode acceptedContext=attempt.path("context");assertTrue(acceptedContext.path("sourceText").asText().contains(SUPPLEMENT));
        assertEquals("candidate_found",acceptedContext.path("stopReason").asText());
        StringBuilder original=new StringBuilder();for(JsonNode row:currentTrace.path("parts"))if(row.path("sourceDocumentId").equals(part.path("sourceDocumentId")))original.append(row.path("sourceText").asText());
        assertEquals(original.substring(acceptedContext.path("sourceStart").asInt(),acceptedContext.path("sourceEnd").asInt()),acceptedContext.path("sourceText").asText());
        assertEquals(1,currentTrace.path("relations").size());JsonNode relation=currentTrace.path("relations").get(0);
        assertEquals("proposed",relation.path("status").asText(),"Verified accepted recall context is available for a bounded joint review.");
        assertEquals("supplement",relation.path("relation").asText());assertEquals(1,jointReplies.size());
        JsonNode packet=null;for(JsonNode row:relation.path("evidence"))if(SUPPLEMENT.equals(row.path("sourceQuote").asText()))packet=row;
        assertNotNull(packet);assertEquals(part.path("sourceDocumentId"),packet.path("sourceDocumentId"));assertEquals(part.path("sourceHash"),packet.path("sourceHash"));
        assertEquals(acceptedContext.path("sourceStart"),packet.path("context").path("sourceStart"));assertEquals(acceptedContext.path("sourceEnd"),packet.path("context").path("sourceEnd"));
        assertEquals(acceptedContext.path("sourceText"),packet.path("context").path("sourceText"));assertTrue(packet.path("context").path("sourceText").asText().length()<=6000);
    }

    @Test void structurallyInsufficientAcceptedContextIsNotExpandedForJointReview() throws Exception {
        String key="foundationIncluded",yes="Foundation included: Yes.",no="Foundation included: No.";
        primaryOverride=user->{
            String quote=user.contains(yes)?yes:user.contains(no)?no:null;
            return quote==null?"[]":JsonUtils.write(Collections.singletonList(DraftBusinessRules.map("key",key,"value",quote.equals(yes),"sourceQuote",quote,"reason","TEST ONLY direct Boolean response","confidence",0.95)));
        };
        String project=project();mvc.perform(put("/api/drafting/{id}/variables/{key}",project,key).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"true\"}"))
                .andExpect(jsonPath("$.code").value(0));
        upload(project,"test-only-incomplete-original.docx",yes+" "+"Unrelated background information. ".repeat(220));
        upload(project,"test-only-second.docx",no);extract(project);JsonNode currentTrace=trace(project),input=null;
        for(JsonNode field:response("/api/drafting/"+project+"/variables"))if(key.equals(field.path("key").asText()))input=field;
        assertNotNull(input);retain("insufficient-accepted-context",currentTrace,input);
        assertEquals(2,input.path("candidates").size());assertEquals("true",input.path("value").asText());assertTrue(input.path("confirmed").asBoolean());
        assertEquals(1,currentTrace.path("relations").size());JsonNode relation=currentTrace.path("relations").get(0);
        assertEquals(key,relation.path("key").asText());assertEquals("undetermined",relation.path("status").asText());
        assertTrue(relation.path("codes").toString().contains("joint_context_insufficient"));assertTrue(relation.path("rawResponse").isNull());assertTrue(jointReplies.isEmpty());
        boolean retainedInsufficient=false;for(JsonNode packet:relation.path("evidence"))if("context_insufficient".equals(packet.path("context").path("stopReason").asText()))retainedInsufficient=true;
        assertTrue(retainedInsufficient,"The incomplete original context is retained without a fabricated expansion or model request.");
    }

    @Test void explicitSameScopeSupplementIsInspectableWithoutReplacingTheAdoptedValue() throws Exception {
        String project=project();upload(project,"test-only-original.docx","TEST ONLY original correspondence\nDate: 2026-10-06\nScope: Zone A tender.\n"+ORIGINAL);
        extract(project);JsonNode originalTrace=trace(project),originalInput=variable(project);
        assertEquals(1,originalInput.path("candidates").size());
        mvc.perform(put("/api/drafting/{id}/variables/{key}",project,KEY).contentType(MediaType.APPLICATION_JSON).content(DraftAdoptionTestPayload.candidate(service,project,KEY,0)))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode adopted=variable(project);assertTrue(adopted.path("confirmed").asBoolean());
        upload(project,"test-only-supplement.docx","TEST ONLY supplementary correspondence\nDate: 2026-10-07\nScope: Zone A tender.\n"+SUPPLEMENT);
        extract(project);JsonNode currentTrace=trace(project),currentInput=variable(project);
        retain("supplement",currentTrace,currentInput);
        assertEquals(2,currentInput.path("candidates").size(),"Both supported candidates remain available for human resolution.");
        assertEquals(adopted.path("value"),currentInput.path("value"));assertEquals(adopted.path("source"),currentInput.path("source"));
        assertEquals(originalInput.path("candidates").get(0),currentInput.path("candidates").get(0),"The adopted original candidate keeps its exact source identity, quotation and value.");
        assertTrue(currentInput.path("confirmed").asBoolean());assertTrue(currentInput.path("reviewRequired").asBoolean());
        assertEquals(1,currentTrace.path("relations").size(),"Differing candidates need an inspectable bounded joint evidence proposal.");
        JsonNode relation=currentTrace.path("relations").get(0);assertEquals(KEY,relation.path("key").asText());assertEquals("supplement",relation.path("relation").asText());
        assertEquals("proposed",relation.path("status").asText());assertEquals(2,relation.path("decisionRefs").size());assertEquals(2,relation.path("evidence").size());
        assertEquals(jointReplies.get(0),relation.path("rawResponse").asText());
        Set<String> quotes=new HashSet<>();
        for(JsonNode evidence:relation.path("evidence")) {
            quotes.add(evidence.path("sourceQuote").asText());JsonNode context=evidence.path("context");
            assertTrue(context.path("sourceText").asText().contains("Scope: Zone A tender."));assertTrue(context.path("sourceText").asText().contains("Date: 2026-10-"));
            assertTrue(context.path("sourceText").asText().length()<=6000);assertEquals(context.path("sourceText").asText().length(),context.path("sourceEnd").asInt()-context.path("sourceStart").asInt());
            JsonNode decision=currentTrace.path("decisions").get(evidence.path("decisionRef").asInt());assertEquals("accepted",decision.path("status").asText());
            JsonNode part=null;for(JsonNode candidatePart:currentTrace.path("parts"))if(candidatePart.path("partId").equals(decision.path("partId")))part=candidatePart;
            assertNotNull(part);assertEquals(part.path("sourceDocumentId"),evidence.path("sourceDocumentId"));assertEquals(part.path("sourceHash"),evidence.path("sourceHash"));
            assertEquals(reply(evidence.path("sourceQuote").asText()),part.path("attempts").get(0).path("rawResponse").asText());
        }
        assertEquals(new HashSet<>(Arrays.asList(ORIGINAL,SUPPLEMENT)),quotes);
        JsonNode history=response("/api/drafting/"+project+"/variables/extract-traces/"+originalTrace.path("runId").asText());
        assertEquals(originalTrace.path("decisions"),history.path("decisions"));assertEquals(originalTrace.path("rawResponses"),history.path("rawResponses"));
    }

    private String reply(String quote) {return JsonUtils.write(Collections.singletonList(DraftBusinessRules.map("key",KEY,"value",Collections.singletonList(DraftBusinessRules.map("text",quote)),"sourceQuote",quote,"reason","TEST ONLY literal supplied requirement","confidence",0.95)));}
    private String project()throws Exception {String id="joint-evidence-"+UUID.randomUUID();mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(DraftBusinessRules.map("id",id,"nameZhHans","TEST ONLY joint evidence")))).andExpect(jsonPath("$.code").value(0));return id;}
    private void upload(String project,String name,String text)throws Exception {
        byte[] bytes;try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {for(String line:text.split("\\R",-1))document.createParagraph().createRun().setText(line);document.write(out);bytes=out.toByteArray();}
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",project).file(new MockMultipartFile("files",name,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes))).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
    }
    private void extract(String project)throws Exception {mvc.perform(post("/api/drafting/{id}/variables/extract",project)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));}
    private JsonNode trace(String project)throws Exception {return response("/api/drafting/"+project+"/variables/extract-trace");}
    private JsonNode response(String url)throws Exception {return JsonUtils.parse(mvc.perform(get(url)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    private JsonNode variable(String project)throws Exception {for(JsonNode field:response("/api/drafting/"+project+"/variables"))if(KEY.equals(field.path("key").asText()))return field;throw new AssertionError("Missing variable");}
    private void retain(String name,JsonNode trace,JsonNode input)throws Exception {String root=System.getProperty("hw02.evidence.dir");if(root==null)return;Path folder=Paths.get(root);Files.createDirectories(folder);Files.write(folder.resolve(name+"-trace.json"),JsonUtils.write(trace).getBytes(StandardCharsets.UTF_8));Files.write(folder.resolve(name+"-variable.json"),JsonUtils.write(input).getBytes(StandardCharsets.UTF_8));}
}
