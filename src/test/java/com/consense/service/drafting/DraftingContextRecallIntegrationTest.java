package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.ai.LlmClient.ChatTurn;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual DOCX / extraction / variables / trace API; only the external model is replaced. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingContextRecallIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:context_recall_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","context-recall-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @MockBean LlmClient model;
    @BeforeEach void available() {
        when(model.available()).thenReturn(true);when(model.chatModel()).thenReturn("context-fixture");
    }

    @Test void adjacentAdoptionHeadingAndParagraphReachTheSameRequestWithoutChangingCoreEvidence() throws Exception {
        String project=project();
        String heading="Adopted replacement wording for NTT13 under BSSSC";
        String instruction="Use the following adopted replacement wording for this draft.";
        String answer="Only the selected registered contractors shall execute the Works.";
        String source="Background information. ".repeat(120)+"\n"+heading+"\n"+instruction+"\n"+answer;
        upload(project,source);
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent();
            return user.contains(heading)&&user.contains(answer)?reply("nscAlternativeText",answer,answer):"[]";
        });
        extract(project);JsonNode trace=trace(project);
        assertEquals(answer,variable(project,"nscAlternativeText").path("value").asText());
        assertFalse(variable(project,"nscAlternativeText").path("confirmed").asBoolean());
        assertTrue(trace.path("parts").size()>1,"The original evidence remains budgeted into core parts.");
        StringBuilder original=new StringBuilder();for(JsonNode part:trace.path("parts"))original.append(part.path("sourceText").asText());
        boolean contextualized=false;
        for(JsonNode part:trace.path("parts"))for(JsonNode attempt:part.path("attempts"))if(attempt.path("context").isObject()) {
            JsonNode context=attempt.path("context");
            assertEquals(original.substring(context.path("sourceStart").asInt(),context.path("sourceEnd").asInt()),context.path("sourceText").asText());
            if(context.path("sourceText").asText().contains(heading)&&context.path("sourceText").asText().contains(answer))contextualized=true;
        }
        assertTrue(contextualized,"The dispatched original window and offsets must remain inspectable.");
    }

    @Test void overlappingCompleteTextWithTypographicQuotesIsOneSuggestionAndRetainsBothRawResponses() throws Exception {
        String project=project();
        String original="The tenderer\u2019s selected specialist shall execute the Works. The specialist shall complete testing and commissioning before handover. "
                +"The tenderer shall provide the agreed supervision and coordination for these works. The specialist shall comply with the project programme and the Contract requirements. "
                +"All submissions shall be reviewed by the Architect before the affected works commence.";
        String equivalent=original.replace('\u2019','\'');
        upload(project,"Background information. ".repeat(120)+"\nAdopted replacement wording for NTT13 under BSSSC\nUse the following exact wording.\n"+original);
        java.util.concurrent.atomic.AtomicInteger primary=new java.util.concurrent.atomic.AtomicInteger();
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent();
            if(user.contains("One bounded context recall"))return "[]";
            assertTrue(user.contains(original),"Both original windows supply the complete answer paragraph.");
            String value=primary.getAndIncrement()==0?original:equivalent;
            return reply("nscAlternativeText",value,value);
        });
        extract(project);JsonNode trace=trace(project),field=variable(project,"nscAlternativeText");
        assertEquals(2,primary.get());assertEquals(2,trace.path("parts").size());
        List<String> accepted=new ArrayList<>(),raw=new ArrayList<>();
        for(JsonNode decision:trace.path("decisions"))if("nscAlternativeText".equals(decision.path("key").asText())&&"accepted".equals(decision.path("status").asText()))
            accepted.add(decision.path("normalizedValue").asText());
        for(JsonNode part:trace.path("parts"))raw.add(part.path("attempts").get(0).path("rawResponse").asText());
        assertEquals(Arrays.asList(original,equivalent),accepted,"Every accepted decision retains its actual spelling.");
        assertEquals(Arrays.asList(reply("nscAlternativeText",original,original),reply("nscAlternativeText",equivalent,equivalent)),raw);
        assertEquals(original,field.path("value").asText(),"Typographic equivalents from the same source must not create a false conflict.");
        assertEquals(1,field.path("candidates").size());
        assertEquals(original,field.path("candidates").get(0).path("value").asText());
        assertEquals(original,field.path("candidates").get(0).path("sourceQuote").asText());
        assertEquals(1,diagnostic(trace,"nscAlternativeText").path("candidateCount").asInt());
        assertEquals("suggested",diagnostic(trace,"nscAlternativeText").path("status").asText());
        assertFalse(field.path("confirmed").asBoolean());
    }

    @Test void distinctCompleteTextAndSeparateSourceProvenanceStillRequireHumanReview() throws Exception {
        String shortText="The tenderer\u2019s selected specialist shall execute the Works.";
        String longerText=shortText+" The specialist shall also complete testing before handover.";
        String heading="Adopted replacement wording for NTT13 under BSSSC\nUse the following exact wording.\n";
        for(boolean separateSources:Arrays.asList(false,true)) {
            String project=project(),second=separateSources?shortText.replace('\u2019','\''):longerText;
            if(separateSources) {upload(project,heading+shortText);upload(project,heading+second);}
            else upload(project,"Background information. ".repeat(120)+"\n"+heading+shortText+"\n"+second);
            java.util.concurrent.atomic.AtomicInteger primary=new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation->{
                List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent();
                if(user.contains("One bounded context recall"))return "[]";
                // This fixture does not classify source relations; an unhelpful review preserves human resolution.
                if(user.contains("<joint-review-packets>"))return "[]";
                String value=primary.getAndIncrement()==0?shortText:second;
                assertTrue(user.contains(value),"Each value has its complete original source paragraph.");
                return reply("nscAlternativeText",value,value);
            }).when(model).chat(anyList());
            extract(project);JsonNode trace=trace(project),field=variable(project,"nscAlternativeText");
            assertEquals(2,primary.get());assertEquals(2,field.path("candidates").size());
            JsonNode first=field.path("candidates").get(0),last=field.path("candidates").get(1);
            assertEquals(shortText,first.path("value").asText());assertEquals(second,last.path("value").asText());
            if(separateSources)assertNotEquals(first.path("sourceDocumentId"),last.path("sourceDocumentId"),"Typographic equivalence cannot erase a separate original source.");
            else {assertEquals(first.path("sourceDocumentId"),last.path("sourceDocumentId"));assertEquals(first.path("sourceHash"),last.path("sourceHash"));}
            assertEquals("",field.path("value").asText(),"A shared substring or quote style alone cannot settle a sourced disagreement.");
            assertEquals(2,diagnostic(trace,"nscAlternativeText").path("candidateCount").asInt());
            assertEquals("candidate_conflict",diagnostic(trace,"nscAlternativeText").path("status").asText());
            assertFalse(field.path("confirmed").asBoolean());
        }
    }

    @Test void omittedCuedInputGetsOneCompactRecallAndUnsupportedKeysCannotBecomeSuggestions() throws Exception {
        String project=project();String answer="Only the selected registered contractors shall execute the Works.";
        String source="Adopted replacement wording for NTT13 under BSSSC\nUse the following wording for this draft.\n"+answer;
        upload(project,source);
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent();
            if(!user.contains("One bounded context recall"))return "[]";
            String system=turns.get(0).getContent();
            assertTrue(system.contains("\"key\":\"nscAlternativeText\""));
            assertFalse(system.contains("\"key\":\"contractTitle\""));
            JsonNode good=JsonUtils.parse(reply("nscAlternativeText",answer,answer)).get(0);
            JsonNode excluded=JsonUtils.parse(reply("foundationIncluded",true,answer)).get(0);
            return JsonUtils.write(Arrays.asList(good,excluded));
        });
        extract(project);JsonNode trace=trace(project);JsonNode attempts=trace.path("parts").get(0).path("attempts");
        assertEquals(answer,variable(project,"nscAlternativeText").path("value").asText());
        assertEquals(2,attempts.size());assertEquals("recall",attempts.get(1).path("kind").asText());
        assertEquals("candidate_found",attempts.get(1).path("context").path("stopReason").asText());
        assertEquals("missing_output",attempts.get(1).path("context").path("trigger").asText());
        assertEquals("recall_key_not_allowed",trace.path("decisions").get(1).path("codes").get(0).asText());
        assertTrue(variable(project,"foundationIncluded").path("candidates").isEmpty());
        assertFalse(variable(project,"nscAlternativeText").path("confirmed").asBoolean());
    }

    @Test void aBudgetedTableRetainsItsHeaderAndRowsAndOverlapDoesNotMultiplyEvidence() throws Exception {
        String project=project();List<Map<String,Object>> rows=new ArrayList<>();StringBuilder table=new StringBuilder("Bill number | Bill description\n");
        for(int i=1;i<=4;i++) {
            String description=("Component "+i+" "+"detailed construction work ".repeat(36)).trim();
            rows.add(new LinkedHashMap<>(Map.of("number",String.valueOf(i),"description",description)));
            table.append(i).append(" | ").append(description).append("\n");
        }
        upload(project,table.toString());
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent();
            boolean complete=user.contains("Bill number | Bill description");
            for(Map<String,Object> row:rows)complete&=user.contains(row.get("description").toString());
            return complete?reply("billNos",rows,table.toString().trim()):"[]";
        });
        extract(project);JsonNode bills=variable(project,"billNos");
        assertEquals(4,JsonUtils.parse(bills.path("value").asText()).size());
        assertEquals(1,bills.path("candidates").size(),"The same original table is one source of support even when dispatched in overlapping windows.");
        assertFalse(bills.path("confirmed").asBoolean());
        JsonNode trace=trace(project);assertTrue(trace.path("parts").size()>1);
        assertEquals(1,diagnostic(trace,"billNos").path("candidateCount").asInt(),"Diagnostic support count must also deduplicate overlapping dispatches.");
        boolean headerAndAllRows=false;
        for(JsonNode part:trace.path("parts"))for(JsonNode attempt:part.path("attempts")) {
            String original=attempt.path("context").path("sourceText").asText();
            if(original.contains("Bill number | Bill description")&&original.contains(rows.get(3).get("description").toString()))headerAndAllRows=true;
        }
        assertTrue(headerAndAllRows);
    }

    @Test void distinctCuedTopicsAreSearchedInSeparateOriginalWindows() throws Exception {
        String project=project();String answer="Only the selected registered contractors shall execute the Works.";
        upload(project,"Project Architect name\nAlex Doe\n\nAdopted replacement wording for NTT13 under BSSSC\nUse the following wording.\n"+answer);
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent(),system=turns.get(0).getContent();
            if(!user.contains("One bounded context recall"))return "[]";
            List<JsonNode> items=new ArrayList<>();
            if(system.contains("\"key\":\"projectArchitectName\"")&&user.contains("Alex Doe"))items.add(JsonUtils.parse(reply("projectArchitectName","Alex Doe","Alex Doe")).get(0));
            if(system.contains("\"key\":\"nscAlternativeText\"")&&user.contains(answer))items.add(JsonUtils.parse(reply("nscAlternativeText",answer,answer)).get(0));
            return JsonUtils.write(items);
        });
        extract(project);
        assertEquals("Alex Doe",variable(project,"projectArchitectName").path("value").asText());
        assertEquals(answer,variable(project,"nscAlternativeText").path("value").asText());
        JsonNode attempts=trace(project).path("parts").get(0).path("attempts");
        assertEquals(3,attempts.size());
        for(int i=1;i<attempts.size();i++) {
            JsonNode context=attempts.get(i).path("context");assertEquals(1,context.path("keys").size());
            assertEquals("candidate_found",context.path("stopReason").asText());
        }
    }

    @Test void genuinelyMissingAndExplicitlyPendingInputsRemainBlankWithoutInventedFalseOrLists() throws Exception {
        String project=project();String pending="Project Architect name remains pending and is not provided.";
        upload(project,pending+"\nThe meeting concerned document preparation only.");
        when(model.chat(anyList())).thenReturn(reply("projectArchitectName",null,pending));extract(project);
        JsonNode trace=trace(project);
        assertEquals("",variable(project,"projectArchitectName").path("value").asText());
        assertEquals("",variable(project,"wtoGpaApplies").path("value").asText());
        assertEquals("",variable(project,"billNos").path("value").asText());
        assertEquals(1,trace.path("parts").get(0).path("attempts").size());
        assertEquals("source_unresolved",diagnostic(trace,"projectArchitectName").path("status").asText());
        assertEquals("no_candidate",diagnostic(trace,"wtoGpaApplies").path("status").asText());
    }

    @Test void aMalformedOrFailedRecallIsRetainedAndNeverRecursivelyRetried() throws Exception {
        for(boolean transportFailure:Arrays.asList(false,true)) {
            org.mockito.Mockito.reset(model);available();
            String project=project();upload(project,"Project Architect name\nThe relevant contact details follow in a later message.");
            when(model.chat(anyList())).thenAnswer(invocation->{
                List<ChatTurn> turns=invocation.getArgument(0);
                if(!turns.get(1).getContent().contains("One bounded context recall"))return "[]";
                if(transportFailure)throw new IllegalStateException("Fixture provider failure");
                return "[{\"key\":\"projectArchitectName\",";
            });
            extract(project);JsonNode trace=trace(project);JsonNode attempts=trace.path("parts").get(0).path("attempts");
            assertEquals("completed",trace.path("status").asText());assertEquals(2,attempts.size());
            assertEquals("failed",attempts.get(1).path("status").asText());
            assertEquals(transportFailure?"model_call_failed":"invalid_json",attempts.get(1).path("errorCode").asText());
            assertEquals(attempts.get(1).path("errorCode"),attempts.get(1).path("context").path("stopReason"));
            assertTrue(variable(project,"projectArchitectName").path("candidates").isEmpty());
        }
    }

    @Test void updatedParentSuggestionEnablesRecallWhileConcurrentManualTextRemainsAuthoritative() throws Exception {
        String project=project();String answer="Only the selected registered contractors shall execute the Works.";
        String arrangement="The Building Services subcontract arrangement is BSSSC.";
        upload(project,arrangement+"\nAdopted replacement wording for NTT13 under BSSSC\nUse the following wording.\n"+answer);
        when(model.chat(anyList())).thenReturn(reply("subcontractArrangement","NSC",arrangement));extract(project);
        assertEquals("NSC",variable(project,"subcontractArrangement").path("value").asText());
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);
            if(!turns.get(1).getContent().contains("One bounded context recall")) {
                List<JsonNode> primary=Arrays.asList(JsonUtils.parse(reply("subcontractArrangement","BSSSC",arrangement)).get(0),
                        JsonUtils.parse(reply("nscAlternativeText",null,"Use the following wording.")).get(0));
                return JsonUtils.write(primary);
            }
            mvc.perform(put("/api/drafting/{id}/variables/nscAlternativeText",project).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"value\":\"Human adopted project wording.\"}")).andExpect(jsonPath("$.code").value(0));
            return reply("nscAlternativeText",answer,answer);
        });
        extract(project);JsonNode current=variable(project,"nscAlternativeText");
        assertEquals("Human adopted project wording.",current.path("value").asText());
        assertTrue(current.path("confirmed").asBoolean());assertTrue(current.path("manuallyEdited").asBoolean());
        assertEquals(answer,current.path("candidates").get(0).path("value").asText());
        boolean ordinaryNullRecall=false;
        for(JsonNode attempt:trace(project).path("parts").get(0).path("attempts"))if("recall".equals(attempt.path("kind").asText())&&
                attempt.path("context").path("keys").toString().contains("nscAlternativeText"))ordinaryNullRecall|="ordinary_null".equals(attempt.path("context").path("trigger").asText());
        assertTrue(ordinaryNullRecall,trace(project).path("parts").get(0).path("attempts").toString());
        // A manually selected inactive arrangement suppresses recall but preserves the retained child text.
        mvc.perform(put("/api/drafting/{id}/variables/subcontractArrangement",project).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"NSC\"}")).andExpect(jsonPath("$.code").value(0));
        extract(project);assertEquals(1,trace(project).path("parts").get(0).path("attempts").size());
        assertEquals("Human adopted project wording.",variable(project,"nscAlternativeText").path("value").asText());
    }

    @Test void sourcedDisagreementFromRecallRemainsAHumanChoiceRatherThanUploadOrderWinning() throws Exception {
        String project=project();String first="Project Architect name: Alex Doe.",second="Project Architect name: Bea Chan.";
        upload(project,first);upload(project,second);
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent();
            if(!user.contains("One bounded context recall"))return "[]";
            return user.contains(first)?reply("projectArchitectName","Alex Doe",first):reply("projectArchitectName","Bea Chan",second);
        });
        extract(project);JsonNode variable=variable(project,"projectArchitectName");
        assertEquals("",variable.path("value").asText());assertEquals(2,variable.path("candidates").size());
        assertEquals("candidate_conflict",diagnostic(trace(project),"projectArchitectName").path("status").asText());
        assertNotEquals(variable.path("candidates").get(0).path("sourceDocumentId"),variable.path("candidates").get(1).path("sourceDocumentId"));
        assertFalse(variable.path("confirmed").asBoolean());
    }

    @Test void oversizedLogicalUnitsAndThePerRunRecallBudgetStopWithoutDroppingAnyCoreText() throws Exception {
        String oversizedProject=project();String source="Adopted replacement wording for NTT13 under BSSSC\nUse the following adopted wording.\n"+"Extended exact clause wording ".repeat(250);
        upload(oversizedProject,source);when(model.chat(anyList())).thenReturn("[]");extract(oversizedProject);
        JsonNode trace=trace(oversizedProject);boolean insufficient=false;StringBuilder preserved=new StringBuilder();
        for(JsonNode part:trace.path("parts")) {
            preserved.append(part.path("sourceText").asText());insufficient|="context_insufficient".equals(part.path("context").path("stopReason").asText());
            for(JsonNode attempt:part.path("attempts"))assertNotEquals("recall",attempt.path("kind").asText());
        }
        assertEquals(source.replaceAll("\\s+"," ").trim(),preserved.toString().replaceAll("\\s+"," ").trim());
        assertTrue(insufficient);assertEquals("",variable(oversizedProject,"nscAlternativeText").path("value").asText());
        String project=project();for(int i=0;i<11;i++)upload(project,"Project Architect name\nPlease obtain contact information from the business user.");
        extract(project);int recalls=0;boolean exhausted=false;
        for(JsonNode part:trace(project).path("parts")) {
            exhausted|="budget_exhausted".equals(part.path("context").path("stopReason").asText());
            for(JsonNode attempt:part.path("attempts"))if("recall".equals(attempt.path("kind").asText()))recalls++;
        }
        assertTrue(recalls>0&&recalls<11);assertTrue(exhausted);
        assertEquals("",variable(project,"projectArchitectName").path("value").asText());
    }

    @Test void aQuotedFragmentOfAnOversizedReplacementParagraphIsNotACompleteSuggestion() throws Exception {
        String project=project();String fragment="Extended exact clause wording ".repeat(4).trim();
        upload(project,"Adopted replacement wording for NTT13 under BSSSC\nUse the following adopted wording.\n"+"Extended exact clause wording ".repeat(250));
        when(model.chat(anyList())).thenReturn(reply("nscAlternativeText",fragment,fragment));extract(project);
        assertTrue(variable(project,"nscAlternativeText").path("candidates").isEmpty());
        assertEquals("",variable(project,"nscAlternativeText").path("value").asText());
        boolean visiblyRejected=false;
        for(JsonNode decision:trace(project).path("decisions"))if("nscAlternativeText".equals(decision.path("key").asText()))
            visiblyRejected|="rejected".equals(decision.path("status").asText())&&decision.path("codes").toString().contains("context_incomplete_source");
        assertTrue(visiblyRejected);
    }

    @Test void aCompleteTextRowInsideAnOversizedNativeTableCannotSupplyReplacementWording() throws Exception {
        String project=project(),answer="Only the selected registered contractors shall execute the Works.";
        byte[] bytes;
        try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Adopted replacement wording for NTT13 under BSSSC");
            org.apache.poi.xwpf.usermodel.XWPFTable table=document.createTable(32,2);
            table.getRow(0).getCell(0).setText("Item");table.getRow(0).getCell(1).setText("Adopted replacement wording");
            table.getRow(1).getCell(0).setText("A");table.getRow(1).getCell(1).setText(answer);
            for(int i=2;i<32;i++) {
                table.getRow(i).getCell(0).setText("Condition "+i);
                table.getRow(i).getCell(1).setText("Remaining required contract condition ".repeat(10));
            }
            document.write(out);bytes=out.toByteArray();
        }
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",project).file(new MockMultipartFile("files","oversized-table.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
        String quote="A | "+answer;
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);
            return turns.get(1).getContent().contains(quote)?reply("nscAlternativeText",answer,quote):"[]";
        });
        extract(project);JsonNode trace=trace(project);
        assertTrue(trace.path("parts").size()>1,"The native table is larger than a single source window.");
        assertEquals("",variable(project,"nscAlternativeText").path("value").asText());
        assertTrue(variable(project,"nscAlternativeText").path("candidates").isEmpty());
        boolean rejected=false;
        for(JsonNode decision:trace.path("decisions"))if("nscAlternativeText".equals(decision.path("key").asText()))
            rejected|="rejected".equals(decision.path("status").asText())&&decision.path("codes").toString().contains("context_incomplete_source");
        assertTrue(rejected,"An exact complete row is still a fragment of its original oversized table.");
    }

    @Test void domainAcronymsInUploadedHeadingsRouteOmittedInputsWithoutInternalKeys() throws Exception {
        String[][] examples={{"wtoGpaApplies","Contract subject to WTO GPA\nYes."},
                {"subsidisedSaleFlatsProduction","SSF production\nYes."}};
        for(String[] example:examples) {
            String project=project(),key=example[0],source=example[1];
            assertFalse(source.contains(key));upload(project,source);
            org.mockito.Mockito.doAnswer(invocation->{
                List<ChatTurn> turns=invocation.getArgument(0);
                return turns.get(1).getContent().contains("One bounded context recall")?reply(key,true,source):"[]";
            }).when(model).chat(anyList());
            extract(project);JsonNode current=variable(project,key),trace=trace(project);
            assertEquals("true",current.path("value").asText(),"The uploaded domain acronym is a routing cue, not value evidence.");
            assertFalse(current.path("confirmed").asBoolean());
            JsonNode attempts=trace.path("parts").get(0).path("attempts");
            assertEquals("[]",attempts.get(0).path("rawResponse").asText(),"The original missing primary output remains visible.");
            assertEquals(2,attempts.size());assertEquals("recall",attempts.get(1).path("kind").asText());
            assertEquals(key,attempts.get(1).path("context").path("keys").get(0).asText());
            assertEquals(source.replaceAll("\\s+"," "),attempts.get(1).path("context").path("sourceText").asText().trim().replaceAll("\\s+"," "));
            assertEquals("candidate_found",attempts.get(1).path("context").path("stopReason").asText());
        }
    }

    @Test void containedOriginalWindowsShareOneRecallAndRetainTheExplicitPendingDecision() throws Exception {
        String project=project(),pending="The exact contract period in months remains pending confirmation.";
        String facade="Tender based on precast concrete facades: Yes.";
        upload(project,"Dear QS\n\n"+pending+"\n"+facade+"\nRegards.");
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent(),system=turns.get(0).getContent();
            if(!user.contains("One bounded context recall"))return "[]";
            List<JsonNode> items=new ArrayList<>();
            if(system.contains("\"key\":\"contractPeriodMonths\""))items.add(JsonUtils.parse(reply("contractPeriodMonths",null,pending)).get(0));
            if(system.contains("\"key\":\"precastFacadeTenderBasis\""))items.add(JsonUtils.parse(reply("precastFacadeTenderBasis",true,facade)).get(0));
            return JsonUtils.write(items);
        });
        extract(project);JsonNode trace=trace(project),attempts=trace.path("parts").get(0).path("attempts");
        assertEquals("[]",attempts.get(0).path("rawResponse").asText());
        assertEquals(2,attempts.size(),"Contained original windows are one evidence cluster, even with distinct target keys.");
        JsonNode context=attempts.get(1).path("context");
        assertEquals("recall",attempts.get(1).path("kind").asText());
        assertTrue(context.path("keys").toString().contains("contractPeriodMonths"));
        assertTrue(context.path("keys").toString().contains("precastFacadeTenderBasis"));
        assertTrue(context.path("sourceText").asText().contains("Dear QS"));
        String original=trace.path("parts").get(0).path("sourceText").asText();
        assertEquals(original.substring(context.path("sourceStart").asInt(),context.path("sourceEnd").asInt()),context.path("sourceText").asText());
        assertEquals("",variable(project,"contractPeriodMonths").path("value").asText());
        assertTrue(variable(project,"contractPeriodMonths").path("candidates").isEmpty());
        assertEquals("source_unresolved",diagnostic(trace,"contractPeriodMonths").path("status").asText());
        assertEquals("true",variable(project,"precastFacadeTenderBasis").path("value").asText());
        assertFalse(variable(project,"precastFacadeTenderBasis").path("confirmed").asBoolean());
    }

    @Test void containedRecallWindowsAcrossCorePartsMergeWithinEachOriginalSourceOnly() throws Exception {
        String project=project(),pending="For this contract period in months, the exact duration remains pending confirmation;";
        String facade="Tender based on precast concrete facades: Yes.";
        String source="X".repeat(2700)+"\nContract period in months\n"+pending+" "+"long original supporting detail ".repeat(13)+"\n"+facade+"\nRegards.\n";
        upload(project,source);upload(project,source);
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent(),system=turns.get(0).getContent();
            if(!user.contains("One bounded context recall"))return "[]";
            List<JsonNode> items=new ArrayList<>();
            if(system.contains("\"key\":\"contractPeriodMonths\""))items.add(JsonUtils.parse(reply("contractPeriodMonths",null,pending)).get(0));
            if(system.contains("\"key\":\"precastFacadeTenderBasis\""))items.add(JsonUtils.parse(reply("precastFacadeTenderBasis",true,facade)).get(0));
            return JsonUtils.write(items);
        });
        extract(project);JsonNode trace=trace(project);Map<String,StringBuilder> originals=new LinkedHashMap<>();
        Map<String,List<JsonNode>> sourceParts=new LinkedHashMap<>();int recallCount=0,pendingCount=0;
        for(JsonNode part:trace.path("parts")) {
            String id=part.path("sourceDocumentId").asText();
            originals.computeIfAbsent(id,key->new StringBuilder()).append(part.path("sourceText").asText());
            sourceParts.computeIfAbsent(id,key->new ArrayList<>()).add(part);
            assertEquals(id+":"+part.path("partIndex").asInt(),part.path("partId").asText());
            assertEquals("[]",part.path("attempts").get(0).path("rawResponse").asText());
            for(JsonNode attempt:part.path("attempts"))if("recall".equals(attempt.path("kind").asText()))recallCount++;
        }
        assertEquals(2,sourceParts.size());assertEquals(2,recallCount,"A cross-core cluster dispatches once per original source, never across documents.");
        for(Map.Entry<String,List<JsonNode>> entry:sourceParts.entrySet()) {
            assertEquals(2,entry.getValue().size());JsonNode owner=entry.getValue().get(0),context=owner.path("attempts").get(1).path("context");
            assertEquals(2,owner.path("attempts").size(),"The actual merged recall is owned by the earliest original core part.");
            assertEquals(1,entry.getValue().get(1).path("attempts").size());
            assertTrue(context.path("keys").toString().contains("contractPeriodMonths"));
            assertTrue(context.path("keys").toString().contains("precastFacadeTenderBasis"));
            String original=originals.get(entry.getKey()).toString();
            assertEquals(0,context.path("sourceStart").asInt());assertEquals(original.length(),context.path("sourceEnd").asInt());
            assertEquals(original,context.path("sourceText").asText());assertTrue(original.length()<=6000);
        }
        for(JsonNode decision:trace.path("decisions"))if("contractPeriodMonths".equals(decision.path("key").asText())&&decision.path("codes").toString().contains("source_unresolved"))pendingCount++;
        assertEquals(2,pendingCount);assertEquals("",variable(project,"contractPeriodMonths").path("value").asText());
        assertTrue(variable(project,"contractPeriodMonths").path("candidates").isEmpty());
        assertEquals("true",variable(project,"precastFacadeTenderBasis").path("value").asText());
        assertFalse(variable(project,"precastFacadeTenderBasis").path("confirmed").asBoolean());
    }

    @Test void aRecalledInactiveParentStopsTheAlreadyPlannedChildSearch() throws Exception {
        String project=project();upload(project,"Subcontract arrangement\nNSC\n\nAdopted replacement wording for NTT13 under BSSSC\nNo project-specific wording is adopted under the NSC arrangement.");
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);
            if(turns.get(1).getContent().contains("One bounded context recall")&&turns.get(0).getContent().contains("\"key\":\"subcontractArrangement\""))
                return reply("subcontractArrangement","NSC","NSC");
            return "[]";
        });
        extract(project);assertEquals("NSC",variable(project,"subcontractArrangement").path("value").asText());
        JsonNode attempts=trace(project).path("parts").get(0).path("attempts");
        for(JsonNode attempt:attempts)if("recall".equals(attempt.path("kind").asText()))
            assertFalse(attempt.path("context").path("keys").toString().contains("nscAlternativeText"),"A recalled inactive parent also changes remaining child eligibility.");
        assertEquals("",variable(project,"nscAlternativeText").path("value").asText());
        assertEquals("inactive",trace(project).path("parts").get(0).path("context").path("stopReason").asText());
        assertEquals("[\"nscAlternativeText\"]",trace(project).path("parts").get(0).path("context").path("keys").toString(),"Other keys in the merged cluster are not mislabeled as inactive.");
    }

    @Test void laterChildPlanSurvivesAnEarlierRecallThatMakesItsInitialParentConflicting() throws Exception {
        String project=project(),answer="Only the selected registered contractors shall execute the Works.";
        upload(project,"Subcontract arrangement\nBSSSC");
        upload(project,"Subcontract arrangement\nNSC\n\nAdopted replacement wording for NTT13 under BSSSC\nUse the following exact wording.\n"+answer);
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent(),system=turns.get(0).getContent();
            if(!user.contains("One bounded context recall"))return user.contains(answer)?reply("subcontractArrangement","NSC","NSC"):"[]";
            if(system.contains("\"key\":\"nscAlternativeText\"")&&user.contains(answer))return reply("nscAlternativeText",answer,answer);
            if(system.contains("\"key\":\"subcontractArrangement\""))return reply("subcontractArrangement","BSSSC","BSSSC");
            return "[]";
        });
        extract(project);JsonNode trace=trace(project);int childRecalls=0;
        for(JsonNode part:trace.path("parts"))for(JsonNode attempt:part.path("attempts"))if("recall".equals(attempt.path("kind").asText())&&
                attempt.path("context").path("keys").toString().contains("nscAlternativeText"))childRecalls++;
        assertEquals(1,childRecalls,"A child discarded against the initial NSC cannot be recovered by a later dispatch refresh.");
        assertEquals("[]",trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
        assertEquals("NSC",JsonUtils.parse(trace.path("parts").get(1).path("attempts").get(0).path("rawResponse").asText()).get(0).path("value").asText());
        assertEquals("",variable(project,"subcontractArrangement").path("value").asText());
        assertEquals("candidate_conflict",diagnostic(trace,"subcontractArrangement").path("status").asText());
        assertEquals(answer,variable(project,"nscAlternativeText").path("value").asText());
        assertFalse(variable(project,"nscAlternativeText").path("confirmed").asBoolean());
        mvc.perform(put("/api/drafting/{id}/variables/nscAlternativeText",project).contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.write(Collections.singletonMap("value",answer)))).andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/drafting/{id}/variables/subcontractArrangement",project).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"NSC\"}")).andExpect(jsonPath("$.code").value(0));
        extract(project);trace=trace(project);boolean inactive=false;
        for(JsonNode part:trace.path("parts")) {
            inactive|="inactive".equals(part.path("context").path("stopReason").asText())&&part.path("context").path("keys").toString().contains("nscAlternativeText");
            for(JsonNode attempt:part.path("attempts"))if("recall".equals(attempt.path("kind").asText()))
                assertFalse(attempt.path("context").path("keys").toString().contains("nscAlternativeText"),"A current human NSC selection still prevents the child dispatch.");
        }
        assertTrue(inactive);assertEquals("NSC",variable(project,"subcontractArrangement").path("value").asText());
        assertTrue(variable(project,"subcontractArrangement").path("manuallyEdited").asBoolean());
        assertEquals(answer,variable(project,"nscAlternativeText").path("value").asText(),"Human adopted child wording is not cleared when its current branch is inactive.");
    }

    @Test void anEarlyPendingPassageDoesNotSuppressASeparateExplicitAnswerInTheSameSourcePart() throws Exception {
        String project=project();String pending="Project Architect name remains pending and is not provided.";
        String answer="Project Architect name: Alex Doe.";
        upload(project,pending+"\n\nSupplementary adopted contact details\n"+answer);
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<ChatTurn> turns=invocation.getArgument(0);
            return turns.get(1).getContent().contains("One bounded context recall")?reply("projectArchitectName","Alex Doe",answer):reply("projectArchitectName",null,pending);
        });
        extract(project);JsonNode current=variable(project,"projectArchitectName"),trace=trace(project);
        assertEquals("Alex Doe",current.path("value").asText());assertFalse(current.path("confirmed").asBoolean());
        assertTrue(trace.path("decisions").get(0).path("codes").toString().contains("source_unresolved"),"The original pending observation is retained.");
        assertEquals("candidate_found",trace.path("parts").get(0).path("attempts").get(1).path("context").path("stopReason").asText());
    }

    private String reply(String key,Object value,String quote) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);
        item.put("reason","Explicit uploaded test instruction");item.put("confidence",0.95);return JsonUtils.write(Collections.singletonList(item));
    }
    private String project() throws Exception {
        String id="context-"+UUID.randomUUID();mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\""+id+"\",\"nameZhHans\":\"Context test\"}")).andExpect(jsonPath("$.code").value(0));return id;
    }
    private void upload(String project,String source) throws Exception {
        byte[] bytes;try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for(String line:source.split("\\R",-1))document.createParagraph().createRun().setText(line);
            document.write(out);bytes=out.toByteArray();
        }
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",project).file(new MockMultipartFile("files","context.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
    }
    private void extract(String project) throws Exception {mvc.perform(post("/api/drafting/{id}/variables/extract",project)).andExpect(jsonPath("$.code").value(0));}
    private JsonNode trace(String project) throws Exception {return response("/api/drafting/"+project+"/variables/extract-trace");}
    private JsonNode response(String url) throws Exception {return JsonUtils.parse(mvc.perform(get(url)).andExpect(jsonPath("$.code").value(0))
            .andReturn().getResponse().getContentAsString()).path("data");}
    private JsonNode variable(String project,String key) throws Exception {
        for(JsonNode item:response("/api/drafting/"+project+"/variables"))if(key.equals(item.path("key").asText()))return item;
        throw new AssertionError("Missing "+key);
    }
    private JsonNode diagnostic(JsonNode trace,String key) {
        for(JsonNode field:trace.path("fields"))if(key.equals(field.path("key").asText()))return field;
        throw new AssertionError("Missing diagnostic "+key);
    }
}
