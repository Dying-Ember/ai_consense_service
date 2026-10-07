package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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

/** Public upload/extraction/state/trace boundaries; only the external LLM is doubled. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingHarnessIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:draft_harness_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","drafting-harness-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @MockBean LlmClient externalModel;
    @BeforeEach void modelAvailable() {
        when(externalModel.available()).thenReturn(true);
        when(externalModel.chatModel()).thenReturn("captured-qwen2.5:7b-instruct-q4_K_M");
    }

    @Test void everyReturnedItemHasAnObservableDecisionWhileValidSuggestionsRemainUnadopted() throws Exception {
        String project=project();
        String source="Contract C-42 is entitled Works A. No foundation works. Exact accepted duration is unknown.";
        upload(project,"simulated-harness-input.docx",source);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(
                candidate("contractTitle","Works A",source,1), candidate("foundationIncluded",false,source,1),
                candidate("contractPeriodMonths",null,source,0),candidate("inventedInput","answer",source,1))),"[]");
        extract(project,0);
        JsonNode trace=trace(project);
        assertEquals("completed",trace.path("status").asText());
        assertEquals(4,trace.path("decisions").size());
        assertEquals("rejected",trace.path("decisions").get(0).path("status").asText());
        assertEquals("invalid_value_shape",trace.path("decisions").get(0).path("codes").get(0).asText());
        assertEquals("Works A",trace.path("decisions").get(0).path("rawValue").asText());
        assertEquals("accepted",trace.path("decisions").get(1).path("status").asText());
        assertEquals("false",trace.path("decisions").get(1).path("normalizedValue").asText());
        assertEquals("unanswered",trace.path("decisions").get(2).path("status").asText());
        assertTrue(trace.path("decisions").get(2).path("rawValue").isNull());
        assertEquals("unknown_key",trace.path("decisions").get(3).path("codes").get(0).asText());
        assertEquals("rejected",field(trace,"contractTitle").path("status").asText());
        assertEquals("source_unresolved",field(trace,"contractPeriodMonths").path("status").asText());
        assertEquals("no_candidate",field(trace,"wtoGpaApplies").path("status").asText());
        JsonNode foundation=variable(project,"foundationIncluded");
        assertEquals("false",foundation.path("value").asText());
        assertFalse(foundation.path("confirmed").asBoolean());
        assertFalse(foundation.path("manuallyEdited").asBoolean());
        assertEquals(source,trace.path("parts").get(0).path("sourceText").asText());
        assertEquals("primary",trace.path("parts").get(0).path("attempts").get(0).path("kind").asText());
        assertFalse(trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText().isEmpty());
    }

    @Test void effectiveDefaultUsesOneNativeCatalogueAndDelimitsFullSourceWhileCustomPromptsSurvive() throws Exception {
        String project=project();String source="Only supplied correspondence may establish a supported answer. Contract Z-7, title Works B.";
        upload(project,"simulated-protocol.docx",source);when(externalModel.chat(anyList())).thenReturn("[]");
        extract(project,0);JsonNode trace=trace(project);
        String system=trace.path("parts").get(0).path("attempts").get(0).path("systemPrompt").asText();
        String user=trace.path("parts").get(0).path("attempts").get(0).path("userPrompt").asText();
        assertEquals(1,occurrences(system,"\"key\":\"contractTitle\""));
        assertEquals(1,occurrences(system,"DRAFTING EXTRACTION PROTOCOL"));
        assertTrue(system.contains("native JSON"));
        assertTrue(system.contains("\"number\":\"C-2026/10\""));
        JsonNode extractionCatalogue=JsonUtils.parse(system.substring(system.indexOf("Canonical editable catalogue:\n")+"Canonical editable catalogue:\n".length()));
        JsonNode identity=null;for(JsonNode schema:extractionCatalogue)if("contractTitle".equals(schema.path("key").asText()))identity=schema;
        assertNotNull(identity);
        for(JsonNode column:identity.path("columnFields"))assertTrue(column.path("optional").asBoolean(),"Extraction permits source-supported partial contract siblings.");
        assertFalse(system.contains("serialized inside"));
        assertFalse(user.contains("DRAFTING EXTRACTION PROTOCOL"));
        assertTrue(user.contains("<correspondence-part>\n"+source+"\n</correspondence-part>"));
        assertFalse(user.contains("\\n"),"Default request delimiters must be real line breaks.");
        mvc.perform(put("/api/prompts/drafting.discover").contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemText\":\"CUSTOM QS WORDING\",\"userTemplate\":\"CUSTOM USER %s %s\"}"))
                .andExpect(jsonPath("$.code").value(0));
        try {
            extract(project,0);trace=trace(project);
            assertTrue(trace.path("systemPrompt").asText().startsWith("CUSTOM QS WORDING"));
            assertTrue(trace.path("userPrompt").asText().contains("CUSTOM USER"));
            assertTrue(trace.path("userPrompt").asText().contains(source));
        } finally {mvc.perform(post("/api/prompts/drafting.discover/reset")).andExpect(jsonPath("$.code").value(0));}
    }

    @Test void intakeExposesAllRejectReasonsAndGroundsLiteralIdentifiersWithoutJudgingEnumsOrBooleans() throws Exception {
        String project=project();String source="Contract ‘Z-7’ is entitled Works B. Bill 1: Excavation. The arrangement is Building Services Specialist Sub-contractors. No trades are nominated. Threshold is not met.";
        upload(project,"simulated-intake.docx",source);
        Map<String,Object> absentConfidence=candidate("wtoGpaApplies",true,source,null);
        Map<String,Object> bill=new LinkedHashMap<>();bill.put("number","88");bill.put("description","Invented Works");bill.put("type","BQ");
        Map<String,Object> partial=new LinkedHashMap<>();partial.put("number","Z-7");
        List<Map<String,Object>> items=Arrays.asList(
                candidate("targetOverrides",Collections.emptyList(),source,1),absentConfidence,
                candidate("precastFacadeTenderBasis",true,source,1.2),candidate("railwayProtectionAreaWorks",true,source,0.2),
                candidate("domesticBlocks",true,null,1),candidate("twoEnvelopeTendering",true,"Nonexistent joined quotation",1),
                candidate("projectArchitectName",Arrays.asList("Wrong text shape"),source,1),
                candidate("contractTitle",Collections.singletonMap("title","Invented Title"),source,1),
                candidate("billNos",Arrays.asList(bill),source,1),candidate("contractTitle",partial,"Contract 'Z-7' is entitled Works B.",1),
                candidate("subcontractArrangement","BSSSC",source,1),candidate("subcontractors",Collections.emptyList(),source,1),
                candidate("periodAtLeast39Months",false,source,1),candidate("contractPeriodMonths","unknown",null,0));
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(items),"[]");extract(project,0);
        JsonNode trace=trace(project);JsonNode decisions=trace.path("decisions");
        String[] expected={"hidden_key","confidence_missing","confidence_out_of_range","confidence_below_threshold","quote_missing",
                "quote_not_in_part","invalid_value_shape","lexical_support_missing","lexical_support_missing"};
        for(int i=0;i<expected.length;i++) {
            assertEquals("rejected",decisions.get(i).path("status").asText());
            assertTrue(decisions.get(i).path("codes").toString().contains(expected[i]),"Missing "+expected[i]);
        }
        for(int i=9;i<13;i++)assertEquals("accepted",decisions.get(i).path("status").asText());
        assertEquals("unanswered",decisions.get(13).path("status").asText());
        assertEquals("Z-7",JsonUtils.parse(variable(project,"contractTitle").path("value").asText()).path("number").asText());
        assertEquals("",JsonUtils.parse(variable(project,"contractTitle").path("value").asText()).path("title").asText());
        assertEquals("BSSSC",variable(project,"subcontractArrangement").path("value").asText(),"Enum literal need not occur verbatim in source.");
        assertEquals("[]",variable(project,"subcontractors").path("value").asText());
        assertEquals("false",variable(project,"periodAtLeast39Months").path("value").asText());
        assertTrue(variable(project,"billNos").path("candidates").isEmpty(),"A source reference cannot supply invented Bill identifiers.");
    }

    @Test void oneSyntaxRetryAndOneTargetedRepairRetainEveryAttemptAndCannotReplaceAcceptedKeys() throws Exception {
        String project=project();String source="Contract Z-7 is entitled Works B. No foundation works. Accepted duration is unknown.";
        upload(project,"simulated-repair.docx",source);
        Map<String,Object> contract=new LinkedHashMap<>();contract.put("number","Z-7");contract.put("title","Works B");
        String malformed="[{\"key\":\"contractTitle\",\"value\":";
        String primary=JsonUtils.write(Arrays.asList(candidate("contractTitle","Works B",source,1),
                candidate("foundationIncluded",false,source,1),candidate("contractPeriodMonths",null,source,0)));
        String repaired=JsonUtils.write(Arrays.asList(candidate("contractTitle",contract,source,1),candidate("foundationIncluded",true,source,1)));
        when(externalModel.chat(anyList())).thenReturn(malformed,primary,repaired);extract(project,0);
        JsonNode trace=trace(project);JsonNode attempts=trace.path("parts").get(0).path("attempts");
        assertEquals(3,attempts.size());
        assertEquals("failed",attempts.get(0).path("status").asText());
        assertEquals(malformed,attempts.get(0).path("rawResponse").asText());
        assertEquals("primary",attempts.get(1).path("kind").asText());
        assertEquals("repair",attempts.get(2).path("kind").asText());
        assertTrue(attempts.get(2).path("userPrompt").asText().contains("invalid_value_shape"));
        assertTrue(attempts.get(2).path("userPrompt").asText().contains(source));
        assertEquals(5,trace.path("decisions").size());
        assertEquals("rejected",trace.path("decisions").get(0).path("status").asText());
        assertEquals("accepted",trace.path("decisions").get(3).path("status").asText());
        assertEquals("repair_key_not_allowed",trace.path("decisions").get(4).path("codes").get(0).asText());
        assertEquals("false",variable(project,"foundationIncluded").path("value").asText());
        assertEquals("Z-7",JsonUtils.parse(variable(project,"contractTitle").path("value").asText()).path("number").asText());
        assertFalse(variable(project,"contractTitle").path("confirmed").asBoolean());
        assertEquals(3,trace.path("rawResponses").size());
    }

    @Test void failedLastPartPublishesItsOwnImmutableHistoryWithoutPartialCandidateOrAdoptionWrites() throws Exception {
        String project=project();String first="No foundation works. The threshold is not met.";
        upload(project,"simulated-first.docx",first);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("foundationIncluded",false,first,1))));
        extract(project,0);JsonNode completed=trace(project);String completedId=completed.path("runId").asText();
        mvc.perform(put("/api/drafting/{id}/variables/foundationIncluded",project).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"true\"}"))
                .andExpect(jsonPath("$.code").value(0));
        upload(project,"simulated-last.docx","A final source remains incomplete.");
        JsonNode before=response(get("/api/drafting/{id}/variables",project));
        String malformed="[{\"key\":\"foundationIncluded\"";
        when(externalModel.chat(anyList())).thenReturn("[]",malformed,malformed);extract(project,5002);
        assertEquals(before,response(get("/api/drafting/{id}/variables",project)));
        JsonNode failed=trace(project);assertEquals("failed",failed.path("status").asText());
        assertNotEquals(completedId,failed.path("runId").asText());
        assertEquals(2,failed.path("parts").size());
        assertEquals(2,failed.path("parts").get(1).path("attempts").size());
        assertEquals(malformed,failed.path("parts").get(1).path("attempts").get(1).path("rawResponse").asText());
        assertEquals("not_assessed",field(failed,"wtoGpaApplies").path("status").asText());
        JsonNode history=response(get("/api/drafting/{id}/variables/extract-traces",project).param("limit","20"));
        assertEquals(2,history.size());assertEquals(failed.path("runId"),history.get(0).path("runId"));
        JsonNode prior=response(get("/api/drafting/{id}/variables/extract-traces/{runId}",project,completedId));
        assertEquals("completed",prior.path("status").asText());assertTrue(prior.path("stale").asBoolean());
        assertEquals(completed.path("rawResponses"),prior.path("rawResponses"));
        assertEquals(completed.path("evidenceRevision"),prior.path("evidenceRevision"));
        assertEquals(1,response(get("/api/drafting/{id}/variables/extract-traces",project).param("limit","1")).size());
        String other=project();
        mvc.perform(get("/api/drafting/{id}/variables/extract-traces/{runId}",other,completedId)).andExpect(jsonPath("$.code").value(4006));
        assertEquals(before,response(get("/api/drafting/{id}/variables",project)),"History inspection must be read only.");
    }

    @Test void candidateDisagreementRemainsObservableWhenUserAdoptsDuringTheModelCall() throws Exception {
        String project=project();String first="No foundation works.";String second="Foundation works are included.";
        upload(project,"simulated-one.docx",first);upload(project,"simulated-two.docx",second);
        java.util.concurrent.atomic.AtomicInteger dispatch=new java.util.concurrent.atomic.AtomicInteger();
        when(externalModel.chat(anyList())).thenAnswer(invocation->{
            if(dispatch.getAndIncrement()==0) {
                mvc.perform(put("/api/drafting/{id}/variables/foundationIncluded",project).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"true\"}"))
                        .andExpect(jsonPath("$.code").value(0));
                return JsonUtils.write(Arrays.asList(candidate("foundationIncluded",false,first,1)));
            }
            return JsonUtils.write(Arrays.asList(candidate("foundationIncluded",true,second,1)));
        });
        extract(project,0);JsonNode trace=trace(project);JsonNode manual=variable(project,"foundationIncluded");
        assertEquals("true",manual.path("value").asText());assertTrue(manual.path("manuallyEdited").asBoolean());assertTrue(manual.path("confirmed").asBoolean());
        assertEquals(2,manual.path("candidates").size());
        assertEquals("candidate_conflict",field(trace,"foundationIncluded").path("status").asText());
        assertEquals(2,field(trace,"foundationIncluded").path("decisionRefs").size());
        assertEquals(first,trace.path("decisions").get(0).path("sourceQuote").asText());
        assertEquals(second,trace.path("decisions").get(1).path("sourceQuote").asText());
    }

    @Test void failedRunRetainsUndispatchedPartsAndTheirOriginalSourceIdentity() throws Exception {
        String project=project();String first="First source remains pending.";String later="Later source must remain visible even if never dispatched.";
        upload(project,"simulated-failed-first.docx",first);upload(project,"simulated-not-dispatched.docx",later);
        when(externalModel.chat(anyList())).thenReturn("not json","still not json");extract(project,5002);
        JsonNode trace=trace(project);assertEquals("failed",trace.path("status").asText());
        assertEquals(2,trace.path("parts").size());
        assertEquals(2,trace.path("parts").get(0).path("attempts").size());
        assertTrue(trace.path("parts").get(1).path("attempts").isEmpty());
        assertEquals(later,trace.path("parts").get(1).path("sourceText").asText());
        assertTrue(trace.path("parts").get(1).path("sourceHash").asText().matches("[a-f0-9]{64}"));
        assertEquals("not_assessed",field(trace,"foundationIncluded").path("status").asText());
    }

    @Test void emptyContractAndSerializedNullStayUnansweredWithoutRepairWhileFalseEmptyListAndLiteralTextSurvive() throws Exception {
        String project=project();String source="No foundation works; no specialist subcontract trades are selected for this list. The architect's name is 42. Contract identity and accepted duration remain unknown.";
        upload(project,"simulated-unknowns.docx",source);
        Map<String,Object> emptyContract=new LinkedHashMap<>();emptyContract.put("number","");emptyContract.put("title",null);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(
                candidate("contractTitle",emptyContract,source,1),candidate("contractPeriodMonths","null",source,1),
                candidate("foundationIncluded",false,source,1),candidate("subcontractors",Collections.emptyList(),source,1),
                candidate("projectArchitectName","42",source,1))),"[]");
        extract(project,0);JsonNode trace=trace(project);
        assertEquals("model_unanswered",field(trace,"contractTitle").path("status").asText());
        assertEquals("source_unresolved",field(trace,"contractPeriodMonths").path("status").asText());
        assertEquals(1,trace.path("parts").get(0).path("attempts").size(),"Unknowns must not trigger a model repair.");
        assertEquals("false",variable(project,"foundationIncluded").path("value").asText());
        assertEquals("[]",variable(project,"subcontractors").path("value").asText());
        assertEquals("42",variable(project,"projectArchitectName").path("value").asText(),"Text that looks numeric remains literal text.");
        assertTrue(variable(project,"contractTitle").path("candidates").isEmpty());
        assertTrue(variable(project,"contractPeriodMonths").path("candidates").isEmpty());
    }

    @Test void customizedUserTextCannotDropOrDuplicateTheSeparatelyDelimitedSourcePart() throws Exception {
        String project=project();String source="Unique correspondence clause: Works C is still pending.";
        upload(project,"simulated-custom-source.docx",source);when(externalModel.chat(anyList())).thenReturn("[]");
        try {
            for(String custom:Arrays.asList("CUSTOM WITHOUT PLACEHOLDERS","CUSTOM ONE %s","CUSTOM TWO %s %s")) {
                mvc.perform(put("/api/prompts/drafting.discover").contentType(MediaType.APPLICATION_JSON)
                        .content(promptCustomization("CUSTOM QS",custom)))
                        .andExpect(jsonPath("$.code").value(0));
                extract(project,0);JsonNode trace=trace(project);String user=trace.path("parts").get(0).path("attempts").get(0).path("userPrompt").asText();
                assertTrue(user.contains(custom.split(" %s")[0]));
                assertEquals(1,occurrences(user,source));
                assertEquals(1,occurrences(user,"<correspondence-part>"));
                assertTrue(user.contains("<correspondence-part>\n"+source+"\n</correspondence-part>"));
            }
        } finally {mvc.perform(post("/api/prompts/drafting.discover/reset")).andExpect(jsonPath("$.code").value(0));}
    }

    @Test void sourceChangeDuringModelCallRejectsCandidateCommitAndKeepsConcurrentManualAdoption() throws Exception {
        String project=project();String source="No foundation works.";upload(project,"simulated-original.docx",source);
        when(externalModel.chat(anyList())).thenReturn(JsonUtils.write(Arrays.asList(candidate("foundationIncluded",false,source,1))));
        extract(project,0);JsonNode prior=trace(project);
        when(externalModel.chat(anyList())).thenAnswer(invocation->{
            mvc.perform(put("/api/drafting/{id}/variables/foundationIncluded",project).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"true\"}"))
                    .andExpect(jsonPath("$.code").value(0));
            upload(project,"simulated-added-during-call.docx","A revised source is now available.");
            return JsonUtils.write(Arrays.asList(candidate("foundationIncluded",true,source,1)));
        });
        extract(project,4007);JsonNode failed=trace(project);
        assertEquals("failed",failed.path("status").asText());assertEquals("source_changed",failed.path("failureCode").asText());
        assertTrue(failed.path("stale").asBoolean());assertEquals(prior.path("evidenceRevision"),failed.path("evidenceRevision"));
        JsonNode manual=variable(project,"foundationIncluded");assertEquals("true",manual.path("value").asText());
        assertTrue(manual.path("confirmed").asBoolean());assertTrue(manual.path("manuallyEdited").asBoolean());
        assertEquals("false",manual.path("candidates").get(0).path("value").asText(),"Failed extraction keeps earlier candidates alongside the new manual value.");
        assertEquals(2,response(get("/api/drafting/{id}/variables/extract-traces",project)).size());
    }

    @Test void failedTargetRepairHasNoImplicitRetryAndLeavesRejectionsVisible() throws Exception {
        String project=project();String source="Contract Z-7 is entitled Works B. No foundation works.";upload(project,"simulated-repair-failure.docx",source);
        String primary=JsonUtils.write(Arrays.asList(candidate("contractTitle","Works B",source,1),candidate("foundationIncluded",false,source,1)));
        when(externalModel.chat(anyList())).thenReturn(primary,"invalid repair response","[]");extract(project,0);
        JsonNode trace=trace(project);assertEquals("completed",trace.path("status").asText());
        assertEquals(2,trace.path("parts").get(0).path("attempts").size());
        assertEquals("failed",trace.path("parts").get(0).path("attempts").get(1).path("status").asText());
        assertEquals("invalid_json",trace.path("parts").get(0).path("attempts").get(1).path("errorCode").asText());
        assertEquals("rejected",field(trace,"contractTitle").path("status").asText());
        assertTrue(variable(project,"contractTitle").path("candidates").isEmpty());
        assertEquals("false",variable(project,"foundationIncluded").path("value").asText());
    }

    /** Post-implementation acceptance replay: real frozen DOCX bytes and every actual A10 reply. */
    @Test void actualA10NineResponsesReplayAgainstEightFrozenDocxSourcesThroughPublicHttp() throws Exception {
        String fixture="/drafting/harness/a10-replay/";
        byte[] traceBytes=resourceBytes(fixture+"trace.json");
        JsonNode provenance=JsonUtils.parse(new String(resourceBytes(fixture+"provenance.json"),StandardCharsets.UTF_8));
        assertEquals(provenance.path("traceSha256").asText(),sha256(traceBytes));
        JsonNode captured=JsonUtils.parse(new String(traceBytes,StandardCharsets.UTF_8)).path("data");
        assertEquals(9,captured.path("rawResponses").size());assertEquals(8,provenance.path("sources").size());
        String[] oldPrompts=captured.path("userPrompt").asText().split("\\r?\\n\\r?\\n--- Evidence part ---\\r?\\n\\r?\\n",-1);
        assertEquals(9,oldPrompts.length);
        Map<String,JsonNode> capturedParts=new LinkedHashMap<>();Map<String,String> exactSources=new LinkedHashMap<>();
        for(JsonNode original:provenance.path("primary")) {
            int index=original.path("responseIndex").asInt();
            Matcher source=Pattern.compile("Source: ([^\\n]+)\\n([\\s\\S]*?)\\nReturn a JSON array of supported allowed keys").matcher(oldPrompts[index]);
            assertTrue(source.find(),"Legacy source identity must be explicit.");
            assertEquals(original.path("fileName").asText(),source.group(1));
            assertEquals(original.path("sourceTextSha256").asText(),sha256(source.group(2).getBytes(StandardCharsets.UTF_8)));
            String raw=captured.path("rawResponses").get(index).asText();
            assertEquals(original.path("rawResponseSha256").asText(),sha256(raw.getBytes(StandardCharsets.UTF_8)));
            assertEquals(original.path("itemCount").asInt(),JsonUtils.parse(raw).size());
            String key=source.group(1)+":"+original.path("partIndex").asInt();
            assertNull(capturedParts.put(key,original));exactSources.put(key,source.group(2));
        }
        String project=project();
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload=multipart("/api/drafting/{id}/inputs/upload",project);
        for(JsonNode original:provenance.path("sources")) {
            byte[] bytes=resourceBytes(fixture+original.path("resource").asText());
            assertEquals(original.path("sha256").asText(),sha256(bytes));assertEquals(original.path("bytes").asInt(),bytes.length);
            upload.file(new MockMultipartFile("files",original.path("fileName").asText(),"application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes));
        }
        mvc.perform(upload).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(8));
        Set<String> primaryDispatched=new LinkedHashSet<>();List<String> repairs=new ArrayList<>(),coverages=new ArrayList<>();
        // Independent frozen source truth; supplemental output never shifts or rewrites the nine captured primary replies.
        String coverageReply=JsonUtils.write(Collections.singletonList(candidate("domesticBlocks",true,
                "PRE.B2.010 describes a 31-storey domestic block over a six-storey podium.",1)));
        when(externalModel.chat(anyList())).thenAnswer(invocation->{
            List<LlmClient.ChatTurn> turns=invocation.getArgument(0);String user=null;
            for(LlmClient.ChatTurn turn:turns)if("user".equals(turn.getRole()))user=turn.getContent();
            assertNotNull(user);
            if(user.contains("<joint-review-packets>")) {
                JsonNode packets=JsonUtils.parse(user.substring(user.indexOf("<joint-review-packets>")+22,user.indexOf("</joint-review-packets>")));
                assertEquals(2,packets.size());
                for(JsonNode packet:packets) {
                    StringBuilder full=new StringBuilder();for(Map.Entry<String,String> frozen:exactSources.entrySet())if(frozen.getKey().startsWith(packet.path("fileName").asText()+":"))full.append(frozen.getValue());
                    assertFalse(full.toString().isEmpty(),"Joint review must use an independently recorded frozen source.");JsonNode context=packet.path("context");
                    assertEquals(full.substring(context.path("sourceStart").asInt(),context.path("sourceEnd").asInt()),context.path("sourceText").asText());
                }
                return "[]"; // This frozen fixture replays intake responses; source relations remain unclassified.
            }
            Matcher identity=Pattern.compile("Source document: ([^\\n]+); part ([0-9]+)\\n<correspondence-part>\\n([\\s\\S]*?)\\n</correspondence-part>").matcher(user);
            assertTrue(identity.find(),"Dispatch must expose actual filename, index and complete source.");
            String key=identity.group(1)+":"+identity.group(2);JsonNode original=capturedParts.get(key);
            assertNotNull(original,"No positional fallback or unrecorded source may be replayed.");
            if(user.contains("One bounded context recall")) {
                StringBuilder full=new StringBuilder();for(Map.Entry<String,String> part:exactSources.entrySet())if(part.getKey().startsWith(identity.group(1)+":"))full.append(part.getValue());
                assertTrue(full.toString().contains(identity.group(3)),"Recall dispatch contains only an original source window.");return "[]";
            }
            assertTrue(identity.group(3).contains(exactSources.get(key)),"Additive context must preserve the unchanged frozen core part.");
            if(user.contains("One targeted repair only.")) {repairs.add(key);return "[]";}
            if(user.contains("One bounded coverage check:")) {
                assertTrue(identity.group(1).startsWith("02_"),"Only the independently grounded residential-scope source is eligible.");
                String system=turns.stream().filter(turn->"system".equals(turn.getRole())).findFirst().get().getContent();
                JsonNode allowed=JsonUtils.parse(system.substring(system.lastIndexOf("Canonical editable catalogue:\n")+"Canonical editable catalogue:\n".length()));
                assertEquals(1,allowed.size());assertEquals("domesticBlocks",allowed.get(0).path("key").asText());
                coverages.add(key);return coverageReply;
            }
            assertTrue(primaryDispatched.add(key),"Captured valid primary outputs need no syntax retry.");
            return captured.path("rawResponses").get(original.path("responseIndex").asInt()).asText();
        });
        extract(project,0);JsonNode replay=trace(project);JsonNode values=response(get("/api/drafting/{id}/variables",project));
        java.nio.file.Path evidence=Paths.get("target","drafting-harness-a10-replay",DB);Files.createDirectories(evidence);
        Files.write(evidence.resolve("trace.json"),JsonUtils.write(replay).getBytes(StandardCharsets.UTF_8));
        Files.write(evidence.resolve("variables.json"),JsonUtils.write(values).getBytes(StandardCharsets.UTF_8));
        assertEquals("completed",replay.path("status").asText());assertEquals(9,replay.path("parts").size());
        assertEquals(capturedParts.keySet(),primaryDispatched);assertFalse(repairs.isEmpty(),"Rejected captured items must exercise tracked repair dispatch.");
        int returned=0,primaryCount=0,repairCount=0,coverageCount=0;Set<String> files=new HashSet<>();Set<String> codes=new HashSet<>();
        List<JsonNode> primaryOutputs=new ArrayList<>();
        for(JsonNode part:replay.path("parts")) {
            String key=part.path("fileName").asText()+":"+part.path("partIndex").asInt();JsonNode original=capturedParts.get(key);
            assertNotNull(original);files.add(part.path("fileName").asText());assertEquals(exactSources.get(key),part.path("sourceText").asText());
            assertTrue(part.path("sourceHash").asText().matches("[a-f0-9]{64}"));
            JsonNode first=part.path("attempts").get(0);String raw=captured.path("rawResponses").get(original.path("responseIndex").asInt()).asText();
            assertEquals("primary",first.path("kind").asText());assertEquals("completed",first.path("status").asText());assertEquals(raw,first.path("rawResponse").asText());
            primaryOutputs.add(first.path("rawResponse"));primaryCount++;
            JsonNode originalItems=JsonUtils.parse(raw);int observed=0;
            for(JsonNode decision:replay.path("decisions"))if(part.path("partId").equals(decision.path("partId"))) {
                if(decision.path("attemptIndex").asInt()!=1) {
                    assertEquals("coverage",part.path("attempts").get(decision.path("attemptIndex").asInt()-1).path("kind").asText());
                    assertEquals("domesticBlocks",decision.path("key").asText());assertTrue(decision.path("rawValue").asBoolean());
                    assertEquals("accepted",decision.path("status").asText());continue;
                }
                int itemIndex=decision.path("itemIndex").asInt();assertEquals(observed,itemIndex,"Every original item appears exactly once in original order.");JsonNode item=originalItems.get(itemIndex);assertNotNull(item);
                assertEquals(item.path("key"),decision.path("key"));assertEquals(item.get("value"),decision.get("rawValue"));
                assertEquals(item.path("sourceQuote").asText(),decision.path("sourceQuote").asText());
                assertEquals(item.path("reason").asText(),decision.path("reason").asText());assertEquals(item.path("confidence").asDouble(),decision.path("confidence").asDouble());
                assertTrue(Arrays.asList("accepted","rejected","unanswered").contains(decision.path("status").asText()));
                for(JsonNode code:decision.path("codes"))codes.add(code.asText());observed++;
            }
            assertEquals(originalItems.size(),observed);returned+=observed;
            for(int i=1;i<part.path("attempts").size();i++) {
                JsonNode extra=part.path("attempts").get(i);String kind=extra.path("kind").asText();
                assertTrue("repair".equals(kind)||"coverage".equals(kind)||"recall".equals(kind));
                assertEquals("coverage".equals(kind)?coverageReply:"[]",extra.path("rawResponse").asText());assertEquals("completed",extra.path("status").asText());
                if("repair".equals(kind))repairCount++;else if("coverage".equals(kind))coverageCount++;
            }
        }
        assertEquals(8,files.size());assertEquals(9,primaryCount);assertEquals(repairs.size(),repairCount);assertEquals(coverages.size(),coverageCount);
        assertEquals(captured.path("rawResponses"),JsonUtils.mapper().valueToTree(primaryOutputs));
        assertEquals(179,returned);assertEquals(179+coverages.size(),replay.path("decisions").size());
        assertEquals("true",variable(project,"domesticBlocks").path("value").asText());assertFalse(variable(project,"domesticBlocks").path("confirmed").asBoolean());
        for(String code:Arrays.asList("invalid_value_shape","quote_not_in_part","unknown_key","confidence_below_threshold","value_unanswered"))assertTrue(codes.contains(code),"Captured rejection must remain observable: "+code);
        JsonNode firstUnknown=replayDecision(replay,provenance.path("primary").get(0).path("fileName").asText(),0,"foundationIncluded");
        assertEquals("",firstUnknown.path("rawValue").asText());assertEquals("unanswered",firstUnknown.path("status").asText());
        JsonNode threshold=replayDecision(replay,provenance.path("primary").get(2).path("fileName").asText(),0,"periodAtLeast39Months");
        assertEquals("false",threshold.path("rawValue").asText());assertEquals("accepted",threshold.path("status").asText());assertEquals("false",threshold.path("normalizedValue").asText());
        JsonNode emptyBills=replayDecision(replay,provenance.path("primary").get(8).path("fileName").asText(),1,"billNos");
        assertEquals("[]",emptyBills.path("rawValue").asText());assertEquals("rejected",emptyBills.path("status").asText());assertFalse(emptyBills.path("codes").toString().contains("value_unanswered"));
        for(int sourceIndex:Arrays.asList(0,7)) {
            JsonNode bill=replayDecision(replay,provenance.path("primary").get(sourceIndex).path("fileName").asText(),0,"billNos");
            assertEquals("rejected",bill.path("status").asText());
            assertTrue(bill.path("codes").toString().contains(sourceIndex==0?"invalid_value_shape":"lexical_support_missing"));
        }
        JsonNode bills=variable(project,"billNos");JsonNode billRows=JsonUtils.parse(bills.path("value").asText());
        JsonNode expected=JsonUtils.parse(new String(resourceBytes("/drafting/harness/expected-source-cases.json"),StandardCharsets.UTF_8)).path("facts").path("billNos");
        assertEquals(13,billRows.size());for(int i=0;i<13;i++) {assertEquals(expected.get(i).path("number"),billRows.get(i).path("number"));assertEquals(expected.get(i).path("description"),billRows.get(i).path("description"));}
        assertEquals(1,bills.path("candidates").size());assertFalse(bills.path("confirmed").asBoolean());
        assertEquals("candidate_conflict",field(replay,"electronicTendering").path("status").asText());
        Set<String> media=new HashSet<>(),mediaParts=new HashSet<>();
        for(JsonNode ref:field(replay,"electronicTendering").path("decisionRefs")) {
            JsonNode decision=replay.path("decisions").get(ref.asInt());assertEquals("electronicTendering",decision.path("key").asText());
            if("accepted".equals(decision.path("status").asText())) {media.add(decision.path("normalizedValue").asText());mediaParts.add(decision.path("partId").asText());assertFalse(decision.path("sourceQuote").asText().isEmpty());}
        }
        assertEquals(new HashSet<>(Arrays.asList("L10Pro","Hardcopy")),media);assertTrue(mediaParts.size()>1,"Disagreement retains distinct source-part provenance, not a verified source conflict.");
        JsonNode history=response(get("/api/drafting/{id}/variables/extract-traces",project).param("limit","20"));
        assertEquals(1,history.size());assertEquals(replay.path("runId"),history.get(0).path("runId"));
        JsonNode stored=response(get("/api/drafting/{id}/variables/extract-traces/{runId}",project,replay.path("runId").asText()));
        assertEquals(replay,stored);assertEquals(values,response(get("/api/drafting/{id}/variables",project)),"Reading captured history does not adopt or rewrite values.");
        mvc.perform(get("/api/drafting/{id}/variables/extract-traces/{runId}",project(),replay.path("runId").asText())).andExpect(jsonPath("$.code").value(4006));
        mvc.perform(put("/api/drafting/{id}/variables/foundationIncluded",project).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"true\"}"))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode beforeFailure=response(get("/api/drafting/{id}/variables",project));
        String malformed="[{\"key\":\"foundationIncluded\"";
        org.mockito.Mockito.doReturn(malformed,malformed).when(externalModel).chat(anyList());extract(project,5002);
        JsonNode failed=trace(project);assertEquals("failed",failed.path("status").asText());assertEquals(9,failed.path("parts").size());
        assertEquals(2,failed.path("parts").get(0).path("attempts").size());
        for(JsonNode attempt:failed.path("parts").get(0).path("attempts"))assertEquals(malformed,attempt.path("rawResponse").asText());
        for(int i=1;i<9;i++)assertTrue(failed.path("parts").get(i).path("attempts").isEmpty());
        assertEquals(beforeFailure,response(get("/api/drafting/{id}/variables",project)),"Failed primary replay commits no partial candidates or changes to manual/adopted values.");
        assertTrue(variable(project,"foundationIncluded").path("confirmed").asBoolean());assertTrue(variable(project,"foundationIncluded").path("manuallyEdited").asBoolean());
        assertEquals(2,response(get("/api/drafting/{id}/variables/extract-traces",project)).size());
        assertEquals(stored,response(get("/api/drafting/{id}/variables/extract-traces/{runId}",project,replay.path("runId").asText())),"The captured completed run stays immutable after a failed run.");
        Files.write(evidence.resolve("failed-trace.json"),JsonUtils.write(failed).getBytes(StandardCharsets.UTF_8));
        Files.write(evidence.resolve("variables-after-failure.json"),JsonUtils.write(beforeFailure).getBytes(StandardCharsets.UTF_8));
        System.out.println("A10 public HTTP replay: 8 exact DOCX sources, 9 exact primary replies, 179 original decisions; empty tracked repairs="+repairCount+"; evidence="+evidence.toAbsolutePath());
    }

    private JsonNode replayDecision(JsonNode trace,String file,int index,String key) {
        for(JsonNode part:trace.path("parts"))if(file.equals(part.path("fileName").asText())&&index==part.path("partIndex").asInt())
            for(JsonNode decision:trace.path("decisions"))if(part.path("partId").equals(decision.path("partId"))&&key.equals(decision.path("key").asText()))return decision;
        throw new AssertionError("Missing actual replay decision: "+file+":"+index+":"+key);
    }
    private byte[] resourceBytes(String name) throws Exception {
        try(InputStream stream=getClass().getResourceAsStream(name)) {assertNotNull(stream,"Missing frozen test-only resource: "+name);return org.springframework.util.StreamUtils.copyToByteArray(stream);}
    }
    private String sha256(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(Locale.ROOT,"%02x",value&0xff));return result.toString();
    }

    private String promptCustomization(String system,String user) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("systemText",system);body.put("userTemplate",user);return JsonUtils.write(body);
    }
    private int occurrences(String value,String token){return value.split(java.util.regex.Pattern.quote(token),-1).length-1;}
    private Map<String,Object> candidate(String key,Object value,String quote,Number confidence) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);
        item.put("sourceQuote",quote);item.put("reason","Simulated harness fixture");item.put("confidence",confidence);return item;
    }
    private String project() throws Exception {
        String id="harness-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"Simulated harness test\"}"))
                .andExpect(jsonPath("$.code").value(0));return id;
    }
    private void upload(String project,String name,String text) throws Exception {
        byte[] bytes;
        try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for(String line:text.split("\\R",-1))document.createParagraph().createRun().setText(line);
            document.write(out);bytes=out.toByteArray();
        }
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",project).file(new MockMultipartFile("files",name,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)))
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
