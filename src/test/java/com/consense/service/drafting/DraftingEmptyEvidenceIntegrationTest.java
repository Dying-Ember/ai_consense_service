package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Public DOCX upload / extraction / variables / immutable trace; only the external model is doubled. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingEmptyEvidenceIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:empty_evidence_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","empty-evidence-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @MockBean LlmClient model;
    @BeforeEach void available() {
        when(model.available()).thenReturn(true);when(model.chatModel()).thenReturn("test-only-empty-evidence");
    }

    @Test void insufficientFactsCannotBecomeAConfirmedEmptyRequirementsSuggestion() throws Exception {
        String project=project();
        String quote="Additional tender requirements: no requirement can be established from the available facts.";
        upload(project,quote);
        String raw=reply("additionalSubmissions",Collections.emptyList(),quote,"The available facts do not establish the applicable requirements.");
        when(model.chat(anyList())).thenReturn(raw);
        extract(project);
        JsonNode trace=trace(project),input=variable(project,"additionalSubmissions");
        retain("insufficient-facts",trace,input);
        assertTrue(input.path("candidates").isEmpty(),"Insufficient facts are unknown, not a source-confirmed empty requirement list.");
        assertEquals("",input.path("value").asText());assertFalse(input.path("confirmed").asBoolean());
        JsonNode primary=trace.path("parts").get(0).path("attempts").get(0);
        assertEquals(raw,primary.path("rawResponse").asText());
        JsonNode decision=trace.path("decisions").get(0);
        assertTrue(decision.path("rawValue").isArray());assertTrue(decision.path("rawValue").isEmpty());
        assertEquals(quote,decision.path("sourceQuote").asText());
        assertEquals("The available facts do not establish the applicable requirements.",decision.path("reason").asText());
        assertEquals(0.95,decision.path("confidence").asDouble());
        assertEquals("rejected",decision.path("status").asText());
        assertTrue(decision.path("codes").toString().contains("unsupported_empty_list"));
    }

    @Test void everyCollectionDistinguishesExplicitAbsenceFromPendingOrUnavailableFacts() throws Exception {
        Map<String,String[]> cases=new LinkedHashMap<>();
        cases.put("designResponsibilities",new String[]{"Design and execution responsibility by component remains pending.","Design and execution responsibility by component: none."});
        cases.put("oldValuableTrees",new String[]{"Old and Valuable Trees to preserve remain unknown because the site information is not supplied.","No Old and Valuable Trees are present on this site."});
        cases.put("billNos",new String[]{"Bill numbers and descriptions remain pending.","No Bills are included in this project."});
        cases.put("additionalSubmissions",new String[]{"Additional tender requirements: no requirement can be established from the available facts.","No additional tender requirements apply to this project."});
        cases.put("pseSubmissions",new String[]{"PSE requirements for domestic CON8 remain pending.","No PSE submissions are required for this project."});
        cases.put("sections",new String[]{"Sections: Works type and location remain unknown.","The Works are not divided into Sections."});
        cases.put("siteVisitRestrictions",new String[]{"Actual inspection restrictions remain pending.","No inspection restrictions apply to this tender."});
        cases.put("subcontractors",new String[]{"BSSSC specialist subcontract names and trades remain pending.","No specialist subcontract trades are selected for this list."});
        for(Map.Entry<String,String[]> example:cases.entrySet())for(boolean explicitNone:Arrays.asList(false,true)) {
            String key=example.getKey(),quote=example.getValue()[explicitNone?1:0],project=project();
            upload(project,quote);
            Object value=explicitNone?"[]":Collections.emptyList(); // Legacy serialized and native arrays share intake semantics.
            when(model.chat(anyList())).thenReturn(reply(key,value,quote,"TEST ONLY source status"));
            extract(project);JsonNode input=variable(project,key),trace=trace(project);
            retain(key+(explicitNone?"-confirmed-none":"-unknown"),trace,input);
            assertEquals(explicitNone?1:0,input.path("candidates").size(),key+": only an explicit source-confirmed absence supports an empty suggestion.");
            assertEquals(explicitNone?"[]":"",input.path("value").asText());
            assertFalse(input.path("confirmed").asBoolean(),"Source confirmation never adopts a suggestion for the human.");
            JsonNode original=trace.path("decisions").get(0);
            assertEquals(explicitNone?"accepted":"rejected",original.path("status").asText());
            if(!explicitNone)assertTrue(original.path("codes").toString().contains("unsupported_empty_list"),key);
        }
    }

    @Test void actualScenarioExplicitEmptyParagraphsRemainSupportedUnadoptedSuggestions() throws Exception {
        Map<String,String[]> cases=new LinkedHashMap<>();
        cases.put("oldValuableTrees",new String[]{
                "For this scenario only, the adopted list of Old and Valuable Trees to be preserved is explicitly empty.",
                " Do not treat an unknown tree list as this empty list. No registry serial number is invented."});
        cases.put("additionalSubmissions",new String[]{
                "For this scenario only, the list of additional project-specific general tender submission requirements is explicitly empty.",
                " Retained standard requirements and existing case Appendix H context remain separate; no extra obligation is invented by an unanswered list."});
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(Map.Entry<String,String[]> example:cases.entrySet()) {
            String key=example.getKey(),quote=example.getValue()[0],project=project();
            upload(project,quote+example.getValue()[1]);
            String raw=reply(key,Collections.emptyList(),quote,"The original paragraph expressly establishes an empty list for this scenario.");
            when(model.chat(anyList())).thenReturn(raw);
            extract(project);JsonNode trace=trace(project),input=variable(project,key);
            retain("actual-explicit-empty-"+key,trace,input);
            checks.add(()->{
                assertEquals("accepted",trace.path("decisions").get(0).path("status").asText(),key+": the actual original paragraph confirms absence.");
                assertEquals(1,input.path("candidates").size(),key);assertEquals("[]",input.path("value").asText(),key);
                assertFalse(input.path("confirmed").asBoolean(),key+": source confirmation never adopts a suggestion.");
                assertEquals(quote,trace.path("decisions").get(0).path("sourceQuote").asText());
                assertTrue(trace.path("decisions").get(0).path("rawValue").isArray());
                assertTrue(trace.path("decisions").get(0).path("rawValue").isEmpty());
                assertEquals(raw,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
                assertEquals(1,trace.path("parts").get(0).path("attempts").size(),key+": supported absence requires no recall.");
            });
        }
        assertAll("Actual explicit-empty source paragraphs",checks);
    }

    @Test void explicitSectionAbsenceWithTypeAndLocationCaptionRemainsUnadopted() throws Exception {
        String project=project();
        String quote="Sections: Works type and location: Explicitly none\n\n"
                +"No special requirements for phased completion in Sections are expressly specified; "
                +"do not invent a Section A from the one domestic block or treat generic Section wording elsewhere as a numbered Section schedule.";
        upload(project,quote);
        String raw=reply("sections",Collections.emptyList(),quote,"The source explicitly confirms an empty Sections schedule.");
        when(model.chat(anyList())).thenReturn(raw);
        extract(project);JsonNode trace=trace(project),input=variable(project,"sections");
        retain("actual-sections-caption-explicit-none",trace,input);
        assertEquals("accepted",trace.path("decisions").get(0).path("status").asText(),
                "The actual SIM13 label expressly confirms absence, rather than unknown work type/location.");
        assertEquals(1,input.path("candidates").size());assertEquals("[]",input.path("value").asText());
        assertFalse(input.path("confirmed").asBoolean(),"An explicitly empty source still does not adopt a human value.");
        assertEquals(raw,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
        assertEquals(quote,trace.path("decisions").get(0).path("sourceQuote").asText());
        assertTrue(trace.path("decisions").get(0).path("rawValue").isEmpty());
        for(JsonNode attempt:trace.path("parts").get(0).path("attempts"))if("recall".equals(attempt.path("kind").asText()))
            assertFalse(attempt.path("context").path("keys").toString().contains("\"sections\""),
                    "Supported Sections absence needs no Sections recall; other cues in the full source keep their existing recall behavior.");
    }

    @Test void sectionCaptionAbsenceStillRequiresAnAssertedCompleteScope() throws Exception {
        String quote="Sections: Works type and location: Explicitly none";
        List<String> sources=Arrays.asList(
                "Do not assume that "+quote+".",
                "It has not yet been confirmed that "+quote+".",
                quote+". However, Sections and their locations remain pending.",
                quote+". However, Sections include Section 1 for the North Wing.",
                quote+" for the demolition package.",
                "Sections: Works type and location: Unknown; no Section answer can be established from the available facts.");
        for(int i=0;i<sources.size();i++) {
            String source=sources.get(i),candidateQuote=i>=4?source:quote,project=project();upload(project,source);
            String raw=reply("sections",Collections.emptyList(),candidateQuote,"Only a label or incomplete absence claim was selected.");
            when(model.chat(anyList())).thenReturn(raw);extract(project);
            JsonNode trace=trace(project),input=variable(project,"sections");retain("sections-caption-guard-"+i,trace,input);
            assertEquals("rejected",trace.path("decisions").get(0).path("status").asText(),"Original qualification "+i);
            assertTrue(trace.path("decisions").get(0).path("codes").toString().contains("unsupported_empty_list"));
            assertTrue(input.path("candidates").isEmpty());assertEquals("",input.path("value").asText());assertFalse(input.path("confirmed").asBoolean());
            assertEquals(raw,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
            assertEquals(candidateQuote,trace.path("decisions").get(0).path("sourceQuote").asText());
        }
    }

    @Test void everyExplicitEmptyCollectionStillReadsItsOriginalScopeAndQualifications() throws Exception {
        Map<String,String[]> cases=new LinkedHashMap<>();
        cases.put("billNos",new String[]{"Bills","Bill numbers and descriptions remain pending until the missing facts are supplied.","However, Bill 1: Excavation is required for this project."});
        cases.put("subcontractors",new String[]{"selected subcontract trades","Selected subcontract trades remain pending until the missing facts are supplied.","However, selected subcontract trades include Air-Conditioning Installation."});
        cases.put("additionalSubmissions",new String[]{"additional project-specific general tender submission requirements","Additional project-specific general tender submission requirements remain pending until the missing facts are supplied.","However, additional project-specific general tender submission requirements require a signed plan."});
        cases.put("designResponsibilities",new String[]{"design and execution responsibilities by component","Design and execution responsibilities remain pending until the missing facts are supplied.","However, design and execution responsibilities include facade design and execution."});
        cases.put("oldValuableTrees",new String[]{"Old and Valuable Trees to be preserved","Old and Valuable Trees remain pending until the missing facts are supplied.","However, Old and Valuable Trees include tree T-1 to preserve on this site."});
        cases.put("pseSubmissions",new String[]{"PSE submissions for domestic CON8","PSE submissions remain pending until the missing facts are supplied.","However, PSE submissions require a domestic CON8 proposal."});
        cases.put("sections",new String[]{"Sections","Sections and their locations remain pending until the missing facts are supplied.","However, Sections include Section 1 for the North Wing."});
        cases.put("siteVisitRestrictions",new String[]{"actual inspection restrictions","Inspection restrictions remain pending until the missing facts are supplied.","However, inspection restrictions require prior written site access approval."});
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(Map.Entry<String,String[]> example:cases.entrySet())for(int arrangement=0;arrangement<7;arrangement++) {
            String key=example.getKey(),quote="For this scenario only, the adopted list of "+example.getValue()[0]+" is explicitly empty.";
            String pending=example.getValue()[1],concrete=example.getValue()[2];boolean accepted=arrangement==0||arrangement==5;
            String source=arrangement==0?quote:arrangement==1?quote+" "+pending:arrangement==2?pending+" "+quote:
                    arrangement==3?quote+" "+concrete:arrangement==4?concrete+" "+quote:
                    arrangement==5?quote+" However, Project Architect name remains pending.":
                    quote.substring(0,quote.length()-1)+" for the demolition package.";
            String project=project();upload(project,source);
            String raw=reply(key,Collections.emptyList(),quote,"Only the explicit-empty list statement was selected.");
            // A narrower-scope source uses its actual source quote, never an invented shortened quotation.
            if(arrangement==6)raw=reply(key,Collections.emptyList(),source,"The statement only covers one package.");
            when(model.chat(anyList())).thenReturn(raw);
            extract(project);JsonNode trace=trace(project),input=variable(project,key);
            retain("explicit-empty-context-"+key+"-"+arrangement,trace,input);
            String caseName=key+" arrangement "+arrangement,retainedRaw=raw;
            checks.add(()->{
                assertEquals(accepted?"accepted":"rejected",trace.path("decisions").get(0).path("status").asText(),caseName);
                assertEquals(accepted?1:0,input.path("candidates").size(),caseName);assertEquals(accepted?"[]":"",input.path("value").asText(),caseName);
                assertFalse(input.path("confirmed").asBoolean(),caseName);
                if(!accepted)assertTrue(trace.path("decisions").get(0).path("codes").toString().contains("unsupported_empty_list"),caseName);
                assertEquals(retainedRaw,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText(),caseName);
            });
        }
        assertAll("Every explicit-empty collection requires complete same-field context and applicable scope",checks);
    }

    @Test void negatedAndUnconfirmedExplicitEmptyClaimsNeverConfirmAnyCollection() throws Exception {
        Map<String,String> subjects=new LinkedHashMap<>();
        subjects.put("billNos","Bills");subjects.put("subcontractors","selected subcontract trades");
        subjects.put("additionalSubmissions","additional project-specific general tender submission requirements");
        subjects.put("designResponsibilities","design and execution responsibilities by component");
        subjects.put("oldValuableTrees","Old and Valuable Trees to be preserved");subjects.put("pseSubmissions","PSE submissions for domestic CON8");
        subjects.put("sections","Sections");subjects.put("siteVisitRestrictions","actual inspection restrictions");
        List<String> prefixes=Arrays.asList("It is not the case that the ","Do not assume the ","It is not confirmed that the ","We have not established that the ",
                "It has not been confirmed that the ","Do not assume that the ","It is not yet confirmed that the ","It has not yet been confirmed that the ");
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(Map.Entry<String,String> example:subjects.entrySet())for(int arrangement=0;arrangement<prefixes.size();arrangement++) {
            String key=example.getKey(),quote="the adopted list of "+example.getValue()+" is explicitly empty.";
            String source=prefixes.get(arrangement)+quote.substring(4),project=project();upload(project,source);
            String raw=reply(key,Collections.emptyList(),quote,"The model quoted a positive suffix of a nonassertion.");
            when(model.chat(anyList())).thenReturn(raw);extract(project);JsonNode trace=trace(project),input=variable(project,key);
            retain("nonasserted-empty-"+key+"-"+arrangement,trace,input);String caseName=key+" nonassertion "+arrangement;
            checks.add(()->{
                assertEquals("rejected",trace.path("decisions").get(0).path("status").asText(),caseName+": a nonassertion does not confirm absence.");
                assertTrue(trace.path("decisions").get(0).path("codes").toString().contains("unsupported_empty_list"),caseName);
                assertTrue(input.path("candidates").isEmpty(),caseName);assertEquals("",input.path("value").asText(),caseName);assertFalse(input.path("confirmed").asBoolean(),caseName);
                assertEquals(raw,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
                assertEquals(quote,trace.path("decisions").get(0).path("sourceQuote").asText());
            });
        }
        assertAll("No collection may answer from a negated or unconfirmed explicit-empty claim",checks);
    }

    @Test void aShortAbsenceQuoteCannotHideAnUnresolvedOriginalParagraph() throws Exception {
        String quote="No additional tender requirements apply to this project";
        List<String> sources=Arrays.asList(
                quote+", subject to confirmation once the missing facts are supplied.",
                "Please confirm whether "+quote+".",
                quote+". This is unconfirmed because information is missing.",
                quote+" except the signed environmental management plan.");
        int index=0;
        for(String source:sources) {
            String project=project();upload(project,source);
            when(model.chat(anyList())).thenReturn(reply("additionalSubmissions",Collections.emptyList(),quote,"Only the short negative phrase was selected."));
            extract(project);JsonNode trace=trace(project),input=variable(project,"additionalSubmissions");
            retain("unresolved-context-"+index++,trace,input);
            assertTrue(input.path("candidates").isEmpty(),"The original complete source paragraph has not confirmed absence.");
            assertEquals("",input.path("value").asText());
            assertTrue(trace.path("decisions").get(0).path("codes").toString().contains("unsupported_empty_list"));
        }
    }

    @Test void aContrastivePendingQualificationPreventsAnEmptyRequirementsSuggestion() throws Exception {
        String quote="No additional tender requirements apply to this project.";
        String raw=reply("additionalSubmissions",Collections.emptyList(),quote,"Only the first sentence was selected.");
        int index=0;
        for(String qualification:Arrays.asList(
                "However, this is unconfirmed pending the final procurement review.",
                "But this is unconfirmed pending the final procurement review.",
                "Nevertheless, this is unconfirmed pending the final procurement review.",
                "The procurement timetable is confirmed. Additional tender requirements remain pending until the missing facts are supplied.")) {
            String project=project();upload(project,quote+" "+qualification);
            when(model.chat(anyList())).thenReturn(raw);
            extract(project);JsonNode trace=trace(project),input=variable(project,"additionalSubmissions");
            retain("qualified-pending-"+index++,trace,input);
            assertTrue(input.path("candidates").isEmpty(),"The same-field pending qualification prevents a confirmed absence.");
            assertEquals("",input.path("value").asText());assertFalse(input.path("confirmed").asBoolean());
            JsonNode primary=trace.path("parts").get(0).path("attempts").get(0);
            assertEquals(raw,primary.path("rawResponse").asText());
            JsonNode decision=trace.path("decisions").get(0);
            assertEquals(quote,decision.path("sourceQuote").asText());
            assertTrue(decision.path("rawValue").isArray());assertTrue(decision.path("rawValue").isEmpty());
            assertEquals("rejected",decision.path("status").asText());
            assertTrue(decision.path("codes").toString().contains("unsupported_empty_list"));
            assertEquals(2,trace.path("parts").get(0).path("attempts").size());
        }

        String unrelated=project();
        upload(unrelated,quote+" However, Old and Valuable Trees remain pending.");
        extract(unrelated);JsonNode unrelatedTrace=trace(unrelated),unrelatedInput=variable(unrelated,"additionalSubmissions");
        retain("however-unrelated-pending",unrelatedTrace,unrelatedInput);
        assertEquals(1,unrelatedInput.path("candidates").size(),"Another field's pending facts do not qualify this absence.");
        assertEquals("[]",unrelatedInput.path("value").asText());assertFalse(unrelatedInput.path("confirmed").asBoolean());
        assertEquals("accepted",unrelatedTrace.path("decisions").get(0).path("status").asText());
    }

    @Test void aSameFieldConcreteContinuationRejectsEmptyAndRecallsTheSuppliedRequirement() throws Exception {
        String quote="No additional tender requirements apply to this project.";
        String requirement="additional tender submissions require a signed plan.";
        String project=project();upload(project,quote+" However, "+requirement);
        String primary=reply("additionalSubmissions",Collections.emptyList(),quote,"Only the first sentence was selected.");
        String recalled=reply("additionalSubmissions",Collections.singletonList(Collections.singletonMap("text","a signed plan")),requirement,"The original paragraph provides a requirement.");
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<LlmClient.ChatTurn> turns=invocation.getArgument(0);
            if(!turns.get(1).getContent().contains("One bounded context recall"))return primary;
            return turns.get(0).getContent().contains("\"key\":\"additionalSubmissions\"")?recalled:"[]";
        });
        extract(project);JsonNode trace=trace(project),input=variable(project,"additionalSubmissions");
        retain("same-field-concrete-continuation",trace,input);
        JsonNode original=trace.path("decisions").get(0);
        assertEquals("rejected",original.path("status").asText(),"A later same-field requirement prevents an answered-empty candidate.");
        assertTrue(original.path("codes").toString().contains("unsupported_empty_list"));
        assertEquals(primary,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
        assertTrue(original.path("rawValue").isArray());assertTrue(original.path("rawValue").isEmpty());
        assertEquals(quote,original.path("sourceQuote").asText());
        assertEquals(2,trace.path("parts").get(0).path("attempts").size());
        assertEquals("recall",trace.path("parts").get(0).path("attempts").get(1).path("kind").asText());
        assertEquals(recalled,trace.path("parts").get(0).path("attempts").get(1).path("rawResponse").asText());
        assertEquals("accepted",trace.path("decisions").get(1).path("status").asText());
        assertEquals(1,input.path("candidates").size());
        assertEquals("a signed plan",JsonUtils.parse(input.path("value").asText()).get(0).path("text").asText());
        assertFalse(input.path("confirmed").asBoolean());
    }

    @Test void aContrastiveTenderSubmissionCannotBeHiddenByAShortAbsenceQuote() throws Exception {
        String quote="No additional tender requirements apply to this project.";
        List<JsonNode> traces=new ArrayList<>(),inputs=new ArrayList<>();
        for(String connector:Arrays.asList("However,","But","Nevertheless,")) {
            String project=project();upload(project,quote+" "+connector+" provide a signed environmental management plan with the tender.");
            when(model.chat(anyList())).thenReturn(reply("additionalSubmissions",Collections.emptyList(),quote,"Only the first sentence was selected."));
            extract(project);JsonNode trace=trace(project),input=variable(project,"additionalSubmissions");
            retain("contrastive-submission-"+connector.replace(",","").toLowerCase(Locale.ROOT),trace,input);
            traces.add(trace);inputs.add(input);
        }
        assertAll("The contrastive tender submission prevents confirmed absence",()->{
            for(int i=0;i<inputs.size();i++) {
                assertTrue(inputs.get(i).path("candidates").isEmpty(),"Case "+i+" must not manufacture absence.");
                assertEquals("",inputs.get(i).path("value").asText());assertFalse(inputs.get(i).path("confirmed").asBoolean());
                assertEquals("rejected",traces.get(i).path("decisions").get(0).path("status").asText());
                assertTrue(traces.get(i).path("decisions").get(0).path("codes").toString().contains("unsupported_empty_list"));
                assertEquals(2,traces.get(i).path("parts").get(0).path("attempts").size(),"The existing bounded targeted recall must run.");
            }
        });
    }

    @Test void pendingPseSubmissionsCannotBeHiddenByAConfirmedNoneSentence() throws Exception {
        String quote="No PSE submissions are required for this project.";
        String project=project();upload(project,quote+" PSE submissions remain pending until the missing facts are supplied.");
        String raw=reply("pseSubmissions",Collections.emptyList(),quote,"Only the first sentence was selected.");
        when(model.chat(anyList())).thenReturn(raw);
        extract(project);JsonNode trace=trace(project),input=variable(project,"pseSubmissions");
        retain("pse-same-field-pending",trace,input);
        assertTrue(input.path("candidates").isEmpty(),"PSE uncertainty in the original paragraph does not establish absence.");
        assertEquals("",input.path("value").asText());assertFalse(input.path("confirmed").asBoolean());
        assertEquals(raw,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
        JsonNode decision=trace.path("decisions").get(0);
        assertEquals("rejected",decision.path("status").asText());
        assertEquals(quote,decision.path("sourceQuote").asText());
        assertTrue(decision.path("rawValue").isArray());assertTrue(decision.path("rawValue").isEmpty());
        assertTrue(decision.path("codes").toString().contains("unsupported_empty_list"));
    }

    @Test void everyCollectionReadsSameFieldQualificationsAndItemsThroughoutTheOriginalParagraph() throws Exception {
        Map<String,String[]> cases=new LinkedHashMap<>();
        cases.put("billNos",new String[]{"No Bills are included in this project.","Bill numbers and descriptions remain pending until the missing facts are supplied.","However, Bill 1: Excavation is required for this project."});
        cases.put("subcontractors",new String[]{"No specialist subcontract trades are selected for this list.","Selected subcontract trades remain pending until the missing facts are supplied.","However, selected subcontract trades include Air-Conditioning Installation."});
        cases.put("additionalSubmissions",new String[]{"No additional tender requirements apply to this project.","Additional tender requirements remain pending until the missing facts are supplied.","However, additional tender submissions require a signed plan."});
        cases.put("designResponsibilities",new String[]{"Design and execution responsibility by component: none.","Design and execution responsibilities remain pending until the missing facts are supplied.","However, design and execution responsibilities include facade design and execution."});
        cases.put("oldValuableTrees",new String[]{"No Old and Valuable Trees are present on this site.","Old and Valuable Trees remain pending until the missing facts are supplied.","However, Old and Valuable Trees include tree T-1 to preserve on this site."});
        cases.put("pseSubmissions",new String[]{"No PSE submissions are required for this project.","PSE submissions remain pending until the missing facts are supplied.","However, PSE submissions require a domestic CON8 proposal."});
        cases.put("sections",new String[]{"The Works are not divided into Sections.","Sections and their locations remain pending until the missing facts are supplied.","However, Sections include Section 1 for the North Wing."});
        cases.put("siteVisitRestrictions",new String[]{"No inspection restrictions apply to this tender.","Inspection restrictions remain pending until the missing facts are supplied.","However, inspection restrictions require prior written site access approval."});
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(Map.Entry<String,String[]> example:cases.entrySet())for(int arrangement=0;arrangement<5;arrangement++) {
            String key=example.getKey(),quote=example.getValue()[0],pending=example.getValue()[1],concrete=example.getValue()[2];
            boolean unrelated=arrangement==4;
            String source=arrangement==0?quote+" "+pending:arrangement==1?quote+" "+concrete:
                    arrangement==2?pending+" "+quote:arrangement==3?concrete+" "+quote:
                    quote+" However, Project Architect name remains pending.";
            String project=project();upload(project,source);
            String raw=reply(key,Collections.emptyList(),quote,"Only the explicit absence sentence was selected.");
            when(model.chat(anyList())).thenReturn(raw);
            extract(project);JsonNode trace=trace(project),input=variable(project,key);
            retain("full-context-"+key+"-"+arrangement,trace,input);
            String caseName=key+" arrangement "+arrangement;
            checks.add(()->{
                assertEquals(unrelated?1:0,input.path("candidates").size(),caseName+": only unrelated pending preserves supported absence.");
                assertEquals(unrelated?"[]":"",input.path("value").asText(),caseName);
                assertFalse(input.path("confirmed").asBoolean(),caseName);
                JsonNode original=trace.path("decisions").get(0);
                assertEquals(unrelated?"accepted":"rejected",original.path("status").asText(),caseName);
                if(!unrelated)assertTrue(original.path("codes").toString().contains("unsupported_empty_list"),caseName);
                assertTrue(original.path("rawValue").isArray());assertTrue(original.path("rawValue").isEmpty());
                assertEquals(quote,original.path("sourceQuote").asText());
                assertEquals(raw,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
            });
        }
        assertAll("Every supported collection requires the complete same-field original context",checks);
    }

    @Test void targetedRecallDistinguishesUnknownFromNoneAndRecoversSupportedUnadoptedRequirements() throws Exception {
        String project=project(),unknown="Additional tender requirements: no requirement can be established from the available facts.";
        String requirement="Provide a signed environmental management plan with the tender.";
        upload(project,unknown+"\n\nConfirmed additional tender requirement\n"+requirement);
        String primary=reply("additionalSubmissions",Collections.emptyList(),unknown,"The earlier available facts are insufficient.");
        String recalled=reply("additionalSubmissions",Collections.singletonList(Collections.singletonMap("text",requirement)),requirement,"The complete uploaded passage provides an actual requirement.");
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<LlmClient.ChatTurn> turns=invocation.getArgument(0);
            if(!turns.get(1).getContent().contains("One bounded context recall"))return primary;
            return turns.get(0).getContent().contains("\"key\":\"additionalSubmissions\"")?recalled:"[]";
        });
        mvc.perform(put("/api/prompts/drafting.discover").contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemText\":\"CUSTOM HW01 SOURCE INSTRUCTIONS\",\"userTemplate\":\"CUSTOM USER %s %s\"}"))
                .andExpect(jsonPath("$.code").value(0));
        try {
            extract(project);JsonNode trace=trace(project),input=variable(project,"additionalSubmissions");
            retain("supported-recall",trace,input);
            assertEquals(requirement,JsonUtils.parse(input.path("value").asText()).get(0).path("text").asText());
            assertEquals(1,input.path("candidates").size());assertFalse(input.path("confirmed").asBoolean());
            JsonNode part=trace.path("parts").get(0),first=part.path("attempts").get(0),recall=part.path("attempts").get(1);
            assertEquals(2,part.path("attempts").size(),"The unsupported empty uses one bounded recall without repair or recursion.");
            assertEquals(primary,first.path("rawResponse").asText());assertEquals(recalled,recall.path("rawResponse").asText());
            assertEquals("rejected",trace.path("decisions").get(0).path("status").asText());
            assertEquals("accepted",trace.path("decisions").get(1).path("status").asText());
            assertEquals("recall",recall.path("kind").asText());
            assertEquals("rejected_candidate",recall.path("context").path("trigger").asText());
            assertEquals("candidate_found",recall.path("context").path("stopReason").asText());
            assertTrue(recall.path("context").path("keys").toString().contains("additionalSubmissions"));
            assertFalse(recall.path("systemPrompt").asText().contains("\"key\":\"contractTitle\""),"The existing source-local cluster uses a compact targeted catalogue.");
            String original=part.path("sourceText").asText();JsonNode context=recall.path("context");
            assertEquals(original.substring(context.path("sourceStart").asInt(),context.path("sourceEnd").asInt()),context.path("sourceText").asText());
            assertTrue(context.path("sourceText").asText().contains(requirement));
            assertTrue(first.path("systemPrompt").asText().startsWith("CUSTOM HW01 SOURCE INSTRUCTIONS"));
            assertTrue(recall.path("systemPrompt").asText().startsWith("CUSTOM HW01 SOURCE INSTRUCTIONS"));
            assertTrue(first.path("systemPrompt").asText().toLowerCase(Locale.ROOT).contains("no requirement can be established from the available facts"),"The actual extraction prompt must explicitly distinguish the observed uncertainty wording.");
            assertTrue(recall.path("userPrompt").asText().contains("confirmed absence"));
            assertTrue(recall.path("userPrompt").asText().contains("insufficient facts"));
        } finally {mvc.perform(post("/api/prompts/drafting.discover/reset")).andExpect(jsonPath("$.code").value(0));}
    }

    @Test void absenceInOnePackageCannotEstablishAnEmptyProjectWideCollection() throws Exception {
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("additionalSubmissions","No additional tender requirements apply to the demolition package.");
        sources.put("designResponsibilities","No contractor design responsibilities apply to this project.");
        sources.put("oldValuableTrees","No Old and Valuable Trees are present on the eastern part of the site.");
        sources.put("pseSubmissions","No PSE submissions are required for the demolition package.");
        sources.put("sections","No Sections are designated for the demolition package.");
        sources.put("siteVisitRestrictions","No inspection restrictions apply to the demolition package.");
        sources.put("billNos","No Bills are included in the demolition package.");
        sources.put("subcontractors","No specialist subcontract trades are selected for the demolition package.");
        for(Map.Entry<String,String> source:sources.entrySet()) {
            String project=project();upload(project,source.getValue());
            when(model.chat(anyList())).thenReturn(reply(source.getKey(),Collections.emptyList(),source.getValue(),"The statement covers one package or part only."));
            extract(project);JsonNode trace=trace(project),input=variable(project,source.getKey());
            retain(source.getKey()+"-partial-scope",trace,input);
            assertTrue(input.path("candidates").isEmpty(),source.getKey()+": a narrower original scope does not establish absence across this input's scope.");
            assertTrue(trace.path("decisions").get(0).path("codes").toString().contains("unsupported_empty_list"));
        }
    }

    @Test void unsupportedEmptyFollowedByAnExplicitlyPendingRecallRemainsUnknownAndRetainsBothItems() throws Exception {
        String project=project(),quote="Additional tender requirements remain pending until the source information is supplied.";
        upload(project,quote);
        String primary=reply("additionalSubmissions",Collections.emptyList(),quote,"The model confused pending with none.");
        String pending=reply("additionalSubmissions",null,quote,"The source expressly leaves the requirements pending.");
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<LlmClient.ChatTurn> turns=invocation.getArgument(0);
            return turns.get(1).getContent().contains("One bounded context recall")?pending:primary;
        });
        extract(project);JsonNode input=variable(project,"additionalSubmissions"),trace=trace(project);
        retain("pending-recall",trace,input);
        assertEquals("",input.path("value").asText());assertTrue(input.path("candidates").isEmpty());
        assertFalse(input.path("confirmed").asBoolean());
        assertEquals(2,trace.path("parts").get(0).path("attempts").size());
        assertEquals(primary,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());
        assertEquals(pending,trace.path("parts").get(0).path("attempts").get(1).path("rawResponse").asText());
        assertTrue(trace.path("decisions").get(0).path("rawValue").isArray());
        assertEquals("rejected",trace.path("decisions").get(0).path("status").asText());
        assertTrue(trace.path("decisions").get(1).path("rawValue").isNull());
        assertEquals("unanswered",trace.path("decisions").get(1).path("status").asText());
        assertEquals("source_unresolved",diagnostic(trace,"additionalSubmissions").path("status").asText());
        assertEquals("explicit_pending",trace.path("parts").get(0).path("attempts").get(1).path("context").path("stopReason").asText());
        String run=trace.path("runId").asText();
        assertEquals(trace,response("/api/drafting/"+project+"/variables/extract-traces/"+run));
        assertEquals(input,variable(project,"additionalSubmissions"),"Reading retained items never adopts or changes the input.");
    }

    @Test void manualEmptyAndConcurrentAdoptedItemsSurviveUnsupportedEmptyAndSupportedRecall() throws Exception {
        String unknown="Additional tender requirements: no requirement can be established from the available facts.";
        String requirement="Provide a signed environmental management plan with the tender.";
        for(boolean duringRecall:Arrays.asList(false,true)) {
            String project=project();upload(project,unknown+"\n\nConfirmed additional tender requirement\n"+requirement);
            String manual=duringRecall?"[{\"text\":\"Human adopted requirement wording.\"}]":"[]";
            if(!duringRecall)save(project,"additionalSubmissions",manual);
            org.mockito.Mockito.doAnswer(invocation->{
                List<LlmClient.ChatTurn> turns=invocation.getArgument(0);
                if(!turns.get(1).getContent().contains("One bounded context recall"))return reply("additionalSubmissions",Collections.emptyList(),unknown,"Insufficient facts.");
                if(duringRecall)save(project,"additionalSubmissions",manual);
                return reply("additionalSubmissions",Collections.singletonList(Collections.singletonMap("text",requirement)),requirement,"Supported recalled suggestion.");
            }).when(model).chat(anyList());
            extract(project);JsonNode input=variable(project,"additionalSubmissions"),trace=trace(project);
            retain(duringRecall?"concurrent-manual":"manual-empty",trace,input);
            assertEquals(JsonUtils.parse(manual),JsonUtils.parse(input.path("value").asText()));
            assertTrue(input.path("confirmed").asBoolean());assertTrue(input.path("manuallyEdited").asBoolean());
            assertEquals(1,input.path("candidates").size());
            assertEquals(requirement,JsonUtils.parse(input.path("candidates").get(0).path("value").asText()).get(0).path("text").asText());
            assertEquals("rejected",trace.path("decisions").get(0).path("status").asText());
        }
    }

    @Test void repeatedUnsupportedEmptyRecallStopsAtTheExistingBudgetWithoutManufacturingAbsence() throws Exception {
        String project=project();
        for(int i=0;i<10;i++)upload(project,"Additional tender requirements for test scope "+i+": no requirement can be established from the available facts.");
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<LlmClient.ChatTurn> turns=invocation.getArgument(0);String user=turns.get(1).getContent();
            String source=user.substring(user.indexOf("<correspondence-part>\n")+"<correspondence-part>\n".length(),user.indexOf("\n</correspondence-part>"));
            return reply("additionalSubmissions",Collections.emptyList(),source,"The same unavailable facts still do not confirm absence.");
        });
        extract(project);JsonNode trace=trace(project),input=variable(project,"additionalSubmissions");
        retain("budget-exhausted",trace,input);
        assertEquals(10,trace.path("parts").size());
        int recalls=0,exhausted=0;
        for(JsonNode part:trace.path("parts")) {
            if("budget_exhausted".equals(part.path("context").path("stopReason").asText()))exhausted++;
            for(JsonNode attempt:part.path("attempts")) {
                assertNotEquals("repair",attempt.path("kind").asText(),"An unsupported absence uses original-context recall.");
                if("recall".equals(attempt.path("kind").asText())) {
                    recalls++;assertEquals("no_supported_candidate",attempt.path("context").path("stopReason").asText());
                }
                assertFalse(attempt.path("rawResponse").asText().isEmpty());
            }
        }
        assertEquals(8,recalls);assertEquals(2,exhausted);
        assertEquals(18,trace.path("decisions").size());
        for(JsonNode decision:trace.path("decisions")) {
            assertEquals("rejected",decision.path("status").asText());
            assertTrue(decision.path("rawValue").isArray());assertTrue(decision.path("rawValue").isEmpty());
            assertTrue(decision.path("codes").toString().contains("unsupported_empty_list"));
        }
        assertEquals("",input.path("value").asText());assertTrue(input.path("candidates").isEmpty());
        assertFalse(input.path("confirmed").asBoolean());
    }

    @Test void aNonCollectionArrayShapeKeepsTheExistingTargetedRepairBehavior() throws Exception {
        String project=project(),quote="Project Architect name: Alex Doe.";
        upload(project,quote);
        String invalid=reply("projectArchitectName",Collections.emptyList(),quote,"TEST ONLY wrong text shape.");
        String corrected=reply("projectArchitectName","Alex Doe",quote,"The supported text shape is corrected.");
        when(model.chat(anyList())).thenReturn(invalid,corrected);
        extract(project);JsonNode trace=trace(project),input=variable(project,"projectArchitectName");
        retain("non-collection-repair",trace,input);
        assertEquals("Alex Doe",input.path("value").asText());assertFalse(input.path("confirmed").asBoolean());
        JsonNode attempts=trace.path("parts").get(0).path("attempts");
        assertEquals(2,attempts.size());assertEquals("repair",attempts.get(1).path("kind").asText());
        assertEquals(invalid,attempts.get(0).path("rawResponse").asText());
        assertTrue(trace.path("decisions").get(0).path("codes").toString().contains("invalid_value_shape"));
        assertFalse(trace.path("decisions").get(0).path("codes").toString().contains("unsupported_empty_list"));
        assertEquals("accepted",trace.path("decisions").get(1).path("status").asText());
    }

    private String reply(String key,Object value,String quote,String reason) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);
        item.put("sourceQuote",quote);item.put("reason",reason);item.put("confidence",0.95);
        return JsonUtils.write(Collections.singletonList(item));
    }
    private String project() throws Exception {
        String id="empty-evidence-"+UUID.randomUUID();mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY empty evidence\"}"))
                .andExpect(jsonPath("$.code").value(0));return id;
    }
    private void upload(String project,String source) throws Exception {
        byte[] bytes;try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for(String line:source.split("\\R",-1))document.createParagraph().createRun().setText(line);
            document.write(out);bytes=out.toByteArray();
        }
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",project).file(new MockMultipartFile("files","test-only-correspondence.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.parsed").value(1));
    }
    private void extract(String project) throws Exception {
        mvc.perform(post("/api/drafting/{id}/variables/extract",project)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }
    private JsonNode trace(String project) throws Exception {return response("/api/drafting/"+project+"/variables/extract-trace");}
    private JsonNode response(String url) throws Exception {
        return JsonUtils.parse(mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode variable(String project,String key) throws Exception {
        for(JsonNode item:response("/api/drafting/"+project+"/variables"))if(key.equals(item.path("key").asText()))return item;
        throw new AssertionError("Missing input "+key);
    }
    private JsonNode diagnostic(JsonNode trace,String key) {
        for(JsonNode field:trace.path("fields"))if(key.equals(field.path("key").asText()))return field;
        throw new AssertionError("Missing diagnostic "+key);
    }
    private void save(String project,String key,String value) throws Exception {
        mvc.perform(put("/api/drafting/{id}/variables/{key}",project,key).contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.write(Collections.singletonMap("value",value)))).andExpect(jsonPath("$.code").value(0));
    }
    private void retain(String name,JsonNode trace,JsonNode input) throws Exception {
        String root=System.getProperty("hw01.evidence.dir");if(root==null)return;
        Path directory=Paths.get(root);Files.createDirectories(directory);
        Files.write(directory.resolve(name+"-trace.json"),JsonUtils.write(trace).getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve(name+"-variable.json"),JsonUtils.write(input).getBytes(StandardCharsets.UTF_8));
    }
}
