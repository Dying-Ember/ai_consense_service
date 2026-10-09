package com.consense.service.drafting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Offline public workflow replay of the 27 real baseline answers on harness .21.
 * Recorded request scheduling is frozen, while production decode, intake, joint
 * validation, applicability, candidate merging and persistence run normally.
 * This is not another model evaluation. The answer oracle is assertions only.
 */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory",
        "logging.level.com.consense.ai.AiGateway=WARN"})
@ActiveProfiles("h2")
class DraftingCapturedBaselineWorkflowTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final String RESOURCE="/drafting/harness/";
    private JsonNode FIXTURE;
    private JsonNode EXPECTED;
    private String fixtureName;
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:captured_baseline_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","captured-baseline-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired DraftingService service;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @Autowired JdbcTemplate jdbc;
    @SpyBean DraftExtractionHarness harness;
    @MockBean LlmClient model;
    private final Set<String> replayedAttempts=new LinkedHashSet<>();
    private final Set<String> replayedJointKeys=new LinkedHashSet<>();

    @Test void recordedBaselineAnswersReachSixtyNineCompleteSuggestionsAndTenBlanks() throws Exception {
        runFixture("captured-h21-baseline",26,113);
    }

    @Test void recordedFirstNovelRoundAnswersReachSixtyEightCompleteSuggestionsAndElevenBlanks() throws Exception {
        runFixture("captured-novel-round1",27,110);
    }

    @Test void recordedSecondNovelRoundAnswersReachSixtyTwoCompleteSuggestionsAndSeventeenBlanks() throws Exception {
        runFixture("captured-novel-round2",27,108);
    }

    @Test void recordedThirdNovelRoundAnswersReachSixtyNineCompleteSuggestionsAndTenBlanks() throws Exception {
        runFixture("captured-novel-round3",33,108);
    }

    @Test void recordedFourthNovelRoundAnswersReachSixtyTwoSuggestionsAndSeventeenBlanks() throws Exception {
        runFixture("captured-novel-round4",27,95);
    }

    @Test void recordedFifthNovelRoundAnswersReachFiftySixSuggestionsWhileTwoModelFailuresRemainMissing() throws Exception {
        runFixture("captured-novel-round5",28,105);
    }

    private void runFixture(String name,int expectedAttempts,int expectedDecisions) throws Exception {
        fixtureName=name;FIXTURE=resource(name+"/fixture.json");
        boolean novel="captured-novel-round1".equals(name);
        boolean roundTwo="captured-novel-round2".equals(name);
        boolean roundThree="captured-novel-round3".equals(name);
        boolean roundFour="captured-novel-round4".equals(name);
        boolean roundFive="captured-novel-round5".equals(name);
        EXPECTED=resource((novel||roundTwo||roundThree||roundFour||roundFive?name:"captured-alternate-values")+"/expected.json").path("expectedInputs");
        assertTrue(FIXTURE.path("replayOnly").asBoolean());
        assertEquals(79,EXPECTED.size());
        int nonempty=0,blank=0;
        for(JsonNode input:EXPECTED)if(input.path("value").isNull())blank++;else nonempty++;
        assertEquals(roundFive?58:roundTwo||roundFour?62:novel?68:69,nonempty);assertEquals(roundFive?21:roundTwo||roundFour?17:novel?11:10,blank);
        assertEquals(roundFive?"80d0396f-1a3c-4ed0-8232-5c21e966a209":roundFour?"4274693a-eb38-46d3-acfb-7559ef5c6587":roundThree?"a8f32dc6-0c01-4a1d-8d14-58e37882a540":roundTwo?"24ec77b5-5695-41fb-934f-bbb2717f8567":novel?"12d06aec-a541-4e21-a400-0632b80d0d3e":"bca1b3bc-b75b-4fb6-a8cc-7862d3b94166",FIXTURE.path("provenance").path("runId").asText());
        assertEquals(roundFive?28:roundThree?33:novel?29:27,FIXTURE.path("trace").path("rawResponses").size());
        assertEquals(roundTwo||roundThree||roundFour||roundFive?15:16,FIXTURE.path("trace").path("parts").size());
        assertEquals(expectedDecisions,FIXTURE.path("trace").path("decisions").size());
        assertEquals(roundTwo||roundThree||roundFour||roundFive?0:novel?2:1,FIXTURE.path("trace").path("relations").size());
        assertTrue(FIXTURE.path("provenance").path("noSynthesizedCapturedResponses").asBoolean());
        assertEquals(65536,FIXTURE.path("capturedRuntime").path("contextSize").asInt());
        assertEquals(16384,FIXTURE.path("capturedRuntime").path("maxOutputTokens").asInt());
        assertEquals(4096,FIXTURE.path("capturedRuntime").path("reasoningBudget").asInt());
        int rawIndex=0;
        for(JsonNode raw:FIXTURE.path("trace").path("rawResponses"))
            assertEquals(FIXTURE.path("provenance").path("rawResponseSha256InOrder").get(rawIndex++).asText(),DraftAdoption.hash(raw.asText()));
        if(roundFive)validateRoundFiveWrongStatesRemainFrozen();else if(roundFour)validateRoundFourWrongStatesRemainFrozen();else if(roundThree)validateRoundThreeWrongStatesRemainFrozen();else if(roundTwo)validateRoundTwoWrongStatesRemainFrozen();else if(!novel)validateOriginalWrongStatesRemainFrozen();else validateRoundOneWrongStatesRemainFrozen();
        assertEquals(expectedAttempts,FIXTURE.path("provenance").path("extractionAttempts").asInt());
        assertEquals(FIXTURE.path("trace").path("rawResponses").size(),FIXTURE.path("provenance").path("modelCalls").asInt());
        validateRecordedContexts();
        installReplay();

        String prior=project("captured-prior-");
        service.updateVariable(prior,"foundationIncluded",new VariablePatch("false",null,true,null,null));
        String protectedBefore=JsonUtils.write(service.listVariables(prior));
        String project=project("captured-alternate-");
        // Preserve captured source IDs so untouched joint output can be validated
        // against its original source and decision identities, without rewriting it.
        long firstSource=FIXTURE.path("trace").path("parts").get(0).path("sourceDocumentId").asLong();
        jdbc.execute("ALTER TABLE source_document ALTER COLUMN id RESTART WITH "+firstSource);
        Iterator<Map.Entry<String,JsonNode>> recordedSources=FIXTURE.path("sources").fields();
        List<Map<String,Object>> inputInventory=new ArrayList<>();
        while(recordedSources.hasNext()) {
            Map.Entry<String,JsonNode> entry=recordedSources.next();JsonNode recorded=entry.getValue();
            SourceDocument source=new SourceDocument();source.setProjectId(project);
            source.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);source.setParseStatus("PARSED");
            source.setFileName(recorded.path("fileName").asText());source.setTextContent(recorded.path("originalSource").asText());
            source=sources.save(source);
            assertEquals(Long.valueOf(entry.getKey()),source.getId());
            assertEquals(recorded.path("sourceHash").asText(),DraftAdoption.sourceHash(source));
            Map<String,Object> inventory=new LinkedHashMap<>();inventory.put("id",source.getId());inventory.put("fileName",source.getFileName());inputInventory.add(inventory);
        }
        List<VariableVO> actual=service.extractVariables(project);
        ExtractTraceVO trace=service.lastExtractTrace(project);
        Map<String,Object> plan=service.plan(project,null);
        export(actual,trace,plan,inputInventory,service.catalog(project));
        Map<String,VariableVO> variables=actual.stream().collect(Collectors.toMap(VariableVO::getKey,variable->variable));
        assertEquals(expectedAttempts,replayedAttempts.size(),"Every real extraction answer must be replayed once, including repair and recall.");
        assertEquals(expectedDecisions,trace.getDecisions().size(),"Do not hide or delete recorded erroneous model items.");
        assertEquals(expectedAttempts+replayedJointKeys.size(),trace.getRawResponses().size());
        Map<String,Long> recordedResponses=new LinkedHashMap<>();
        for(JsonNode response:FIXTURE.path("trace").path("rawResponses"))recordedResponses.merge(response.asText(),1L,Long::sum);
        for(String response:trace.getRawResponses()) {
            assertTrue(recordedResponses.getOrDefault(response,0L)>0,"Every replayed model response must be an unchanged captured response.");
            recordedResponses.put(response,recordedResponses.get(response)-1);
        }
        verify(model,times(replayedJointKeys.size())).chat(anyList());
        assertEquals("completed",trace.getStatus());
        assertFalse(trace.isStale());
        assertEquals(protectedBefore,JsonUtils.write(service.listVariables(prior)),"An unrelated adopted project must remain byte-for-byte unchanged.");
        assertTrue(((Map<?,?>)plan.get("effectiveValues")).isEmpty(),"Unadopted suggestions are not contract decisions.");

        List<Executable> assertions=new ArrayList<>();
        EXPECTED.fields().forEachRemaining(entry->{
            String key=entry.getKey();JsonNode expected=entry.getValue().path("value");VariableVO variable=variables.get(key);
            assertions.add(()->assertNotNull(variable,key));
            assertions.add(()->assertFalse(variable.isConfirmed(),key+" was auto-adopted"));
            assertions.add(()->assertFalse(variable.isManuallyEdited(),key+" was marked human-edited"));
            if(expected.isNull()||roundFive&&Arrays.asList("billNos","sections").contains(key)) {
                // Model output/provenance failures stay observable. The oracle is not an answer generator.
                assertions.add(()->assertTrue(variable.getValue()==null||variable.getValue().isEmpty(),key+" must remain blank"));
                assertions.add(()->assertTrue(variable.getCandidates()==null||variable.getCandidates().isEmpty(),key+" must have no candidate"));
            } else {
                assertions.add(()->assertEquals(businessIdentity(key,expected),businessIdentity(key,decode(variable.getValue(),variable.getKind())),key+" displayed suggestion"));
                assertions.add(()->assertTrue(variable.getCandidates().stream().anyMatch(candidate->businessIdentity(key,expected).equals(businessIdentity(key,decode(candidate.getValue(),variable.getKind())))),key+" must retain its supported complete candidate"));
            }
        });
        // The false from the isolated BQ5 title is not a project-wide decision.
        if("captured-alternate-values".equals(fixtureName))assertions.add(()->assertTrue(variables.get("allBqQuantitiesProvisional").getCandidates().stream().noneMatch(candidate->"false".equals(candidate.getValue())),"A single Bill title cannot establish the all-BQ false state."));
        for(VariableVO variable:actual)for(CandidateVO candidate:variable.getCandidates()) {
            assertions.add(()->assertTrue(DraftEvidenceQuotes.present(FIXTURE.path("sources").path(candidate.getSourceDocumentId().toString()).path("originalSource").asText(),candidate.getSourceQuote()),variable.getKey()+" must retain a literal original-source quotation"));
        }
        if(roundFive)assertions.add(()->assertRoundFiveFixes(trace,variables));
        else if(roundFour)assertions.add(()->assertRoundFourFixes(trace,variables));
        else if(roundThree)assertions.add(()->assertRoundThreeFixes(trace,variables));
        else if(roundTwo)assertions.add(()->assertRoundTwoFixes(trace,variables));
        else if(!novel) {
            assertions.add(()->assertTrue(variables.get("allBqQuantitiesProvisional").getCandidates().stream().noneMatch(candidate->"false".equals(candidate.getValue()))));
            assertions.add(()->assertBaselineFixes(trace,variables));
        } else assertions.add(()->assertRoundOneFixes(trace,variables));
        assertAll("Frozen frontend answers through production intake and persistence",assertions);
    }

    private void validateRoundFiveWrongStatesRemainFrozen() {
        Map<String,JsonNode> frozen=new LinkedHashMap<>();
        for(JsonNode variable:FIXTURE.path("capturedVariables"))frozen.put(variable.path("key").asText(),variable);
        for(String key:Arrays.asList("billNos","sections","electronicTendering","footingsServeBuildingsOrMajorExternalStructures","buildingDemolitionSitesSeparated"))
            assertEquals("",frozen.get(key).path("value").asText(),key+" original missing state must remain frozen");
        assertEquals("floor 6",frozen.get("specificationInspectionFloor").path("value").asText());
        assertEquals("floor 2",frozen.get("drawingsInspectionFloor").path("value").asText());
    }

    private void assertRoundFiveFixes(ExtractTraceVO trace,Map<String,VariableVO> variables) {
        assertEquals("Hardcopy",variables.get("electronicTendering").getValue());
        assertEquals("true",variables.get("footingsServeBuildingsOrMajorExternalStructures").getValue());
        assertEquals("true",variables.get("buildingDemolitionSitesSeparated").getValue());
        assertEquals("6",variables.get("specificationInspectionFloor").getValue());
        assertEquals("2",variables.get("drawingsInspectionFloor").getValue());
        List<String> rescued=Arrays.asList("electronicTendering","footingsServeBuildingsOrMajorExternalStructures","buildingDemolitionSitesSeparated");
        for(JsonNode original:FIXTURE.path("trace").path("decisions")) {
            if(!"rejected".equals(original.path("status").asText()))continue;
            ExtractionDecisionVO current=trace.getDecisions().stream().filter(d->Objects.equals(d.getPartId(),original.path("partId").asText())&&d.getAttemptIndex()==original.path("attemptIndex").asInt()&&d.getItemIndex()==original.path("itemIndex").asInt()).findFirst().orElseThrow(AssertionError::new);
            assertEquals(rescued.contains(original.path("key").asText())?"accepted":"rejected",current.getStatus(),"Do not relax unrelated captured failures.");
            if(!rescued.contains(original.path("key").asText()))assertEquals(JsonUtils.mapper().convertValue(original.path("codes"),List.class),current.getCodes());
        }
        assertEquals(6,trace.getDecisions().stream().filter(d->"222:0".equals(d.getPartId())&&d.getAttemptIndex()==1&&d.getCodes().contains("unknown_key")).count(),"Mixed reasoning output remains a model protocol failure.");
        assertEquals(2,trace.getDecisions().stream().filter(d->"sections".equals(d.getKey())&&"rejected".equals(d.getStatus())).count(),"Missing designation and noncontiguous quote remain rejected.");
    }

    private void validateRoundFourWrongStatesRemainFrozen() {
        for(JsonNode variable:FIXTURE.path("capturedVariables"))
            if(Arrays.asList("designResponsibilities","allBqQuantitiesProvisional").contains(variable.path("key").asText()))
                assertEquals("",variable.path("value").asText(),"Original failed suggestions must stay frozen.");
    }

    private void assertRoundFourFixes(ExtractTraceVO trace,Map<String,VariableVO> variables) {
        for(String key:Arrays.asList("designResponsibilities","allBqQuantitiesProvisional")) {
            assertFalse(variables.get(key).isReviewRequired(),key+" has no manufactured conflict.");
            assertEquals(3,trace.getDecisions().stream().filter(d->key.equals(d.getKey())&&"accepted".equals(d.getStatus())).count(),key+" all unchanged captured correct answers reach intake.");
        }
        assertEquals(4,JsonUtils.parse(variables.get("designResponsibilities").getValue()).size());
        assertEquals("true",variables.get("allBqQuantitiesProvisional").getValue());
        for(JsonNode original:FIXTURE.path("trace").path("decisions"))
            if("rejected".equals(original.path("status").asText())&&!Arrays.asList("designResponsibilities","allBqQuantitiesProvisional").contains(original.path("key").asText()))
                assertTrue(trace.getDecisions().stream().anyMatch(d->Objects.equals(d.getPartId(),original.path("partId").asText())&&d.getAttemptIndex()==original.path("attemptIndex").asInt()&&d.getItemIndex()==original.path("itemIndex").asInt()&&"rejected".equals(d.getStatus())),"Previously blocked bad model item must remain rejected.");
    }

    private void validateRoundThreeWrongStatesRemainFrozen() {
        Map<String,JsonNode> frozen=new LinkedHashMap<>();
        for(JsonNode variable:FIXTURE.path("capturedVariables"))frozen.put(variable.path("key").asText(),variable);
        for(String key:Arrays.asList("subcontractors","designResponsibilities","footingsServeBuildingsOrMajorExternalStructures",
                "allBqQuantitiesProvisional","billNos","photocopyRateUpToA3","projectInTinShuiWai","sections","drawingsInspectionBlock"))
            assertEquals("",frozen.get(key).path("value").asText(),key+" original false rejection must stay frozen");
    }

    private void assertRoundThreeFixes(ExtractTraceVO trace,Map<String,VariableVO> variables) {
        for(String key:Arrays.asList("subcontractors","designResponsibilities","footingsServeBuildingsOrMajorExternalStructures",
                "allBqQuantitiesProvisional","billNos","photocopyRateUpToA3","projectInTinShuiWai","sections","drawingsInspectionBlock"))
            assertFalse(variables.get(key).isReviewRequired(),key+" must have no artificial candidate conflict");
        // Correct table answers must preserve their complete row identities and column roles.
        assertEquals(7,JsonUtils.parse(variables.get("billNos").getValue()).size());
        assertEquals(4,JsonUtils.parse(variables.get("designResponsibilities").getValue()).size());
        assertEquals(3,JsonUtils.parse(variables.get("sections").getValue()).size());
        // The earlier Architect-designed piles/footings are not contractor design support.
        for(ExtractionDecisionVO decision:trace.getDecisions())
            if("170:0".equals(decision.getPartId())&&"designResponsibilities".equals(decision.getKey()))
                assertEquals("rejected",decision.getStatus());
    }

    private void validateRoundTwoWrongStatesRemainFrozen() {
        Map<String,JsonNode> frozen=new LinkedHashMap<>();
        for(JsonNode variable:FIXTURE.path("capturedVariables"))frozen.put(variable.path("key").asText(),variable);
        for(String key:Arrays.asList("domesticBlocks","footingsServeBuildingsOrMajorExternalStructures"))
            assertEquals("",frozen.get(key).path("value").asText(),key+" original false rejection must stay frozen");
        assertEquals("Dr. Marcus S. H. TSE",frozen.get("projectArchitectName").path("value").asText());
        assertEquals("Dr.",frozen.get("projectArchitectOtherTitle").path("value").asText());
        assertTrue(FIXTURE.path("trace").path("decisions").findValues("sourceQuote").stream().anyMatch(q->q.asText().equals("The obsolete stores are a standalone building, detached from all retained structures.")));
    }

    private void assertRoundTwoFixes(ExtractTraceVO trace,Map<String,VariableVO> variables) {
        assertEquals("true",variables.get("domesticBlocks").getValue());
        assertEquals("false",variables.get("footingsServeBuildingsOrMajorExternalStructures").getValue());
        assertEquals("Marcus S. H. TSE",variables.get("projectArchitectName").getValue());
        assertEquals("Dr",variables.get("projectArchitectOtherTitle").getValue());
        for(String key:Arrays.asList("domesticBlocks","footingsServeBuildingsOrMajorExternalStructures","projectArchitectName","projectArchitectOtherTitle"))
            assertFalse(variables.get(key).isReviewRequired(),key+" must not retain an artificial candidate conflict");
        String wrongQuote="The obsolete stores are a standalone building, detached from all retained structures.";
        List<ExtractionDecisionVO> wrong=trace.getDecisions().stream().filter(d->"buildingDemolitionSitesSeparated".equals(d.getKey())&&wrongQuote.equals(d.getSourceQuote())).collect(Collectors.toList());
        assertEquals(1,wrong.size());assertEquals("rejected",wrong.get(0).getStatus());
        assertTrue(wrong.get(0).getCodes().contains("quote_value_mismatch"));
        assertTrue(variables.get("buildingDemolitionSitesSeparated").getCandidates().stream().noneMatch(c->wrongQuote.equals(c.getSourceQuote())),"A standalone demolition building does not establish project-wide separated sites.");
    }

    private void validateRoundOneWrongStatesRemainFrozen() {
        Map<String,JsonNode> frozen=new LinkedHashMap<>();
        for(JsonNode variable:FIXTURE.path("capturedVariables"))frozen.put(variable.path("key").asText(),variable);
        for(String key:Arrays.asList("electronicTendering","otherWaterproofingSpecificationAreas","designResponsibilities","sections"))
            assertEquals("",frozen.get(key).path("value").asText(),key+" original failure must stay frozen");
        assertEquals("Block E",frozen.get("specificationInspectionBlock").path("value").asText());
        assertEquals("Block F",frozen.get("drawingsInspectionBlock").path("value").asText());
    }

    private void assertRoundOneFixes(ExtractTraceVO trace,Map<String,VariableVO> variables) {
        assertEquals("L10Pro",variables.get("electronicTendering").getValue());
        assertEquals(4,JsonUtils.parse(variables.get("designResponsibilities").getValue()).size());
        assertTrue(variables.get("designResponsibilities").getSource().startsWith("This is the complete component responsibility schedule"));
        assertEquals(3,JsonUtils.parse(variables.get("sections").getValue()).size());
        assertEquals("E",variables.get("specificationInspectionBlock").getValue());
        assertEquals("F",variables.get("drawingsInspectionBlock").getValue());
        for(String key:Arrays.asList("electronicTendering","otherWaterproofingSpecificationAreas","designResponsibilities","sections"))
            assertFalse(variables.get(key).isReviewRequired(),key+" must have no false conflict");
        for(ExtractionDecisionVO d:trace.getDecisions()) {
            boolean wrong="131:0".equals(d.getPartId())&&"footingsServeBuildingsOrMajorExternalStructures".equals(d.getKey())||
                    "140:0".equals(d.getPartId())&&Arrays.asList("sections","projectInTinShuiWai").contains(d.getKey());
            if(wrong){assertEquals("rejected",d.getStatus(),d.getKey());assertTrue(d.getCodes().contains("quote_value_mismatch"));}
        }
        assertEquals(1,variables.get("footingsServeBuildingsOrMajorExternalStructures").getCandidates().size());
        assertEquals(2,variables.get("projectInTinShuiWai").getCandidates().size());
        assertEquals(1,variables.get("sections").getCandidates().size());
    }

    private void validateOriginalWrongStatesRemainFrozen() {
        JsonNode frozenDesign=null;
        for(JsonNode variable:FIXTURE.path("capturedVariables"))if("designResponsibilities".equals(variable.path("key").asText()))frozenDesign=variable;
        assertNotNull(frozenDesign);assertEquals("",frozenDesign.path("value").asText());
        assertEquals(2,frozenDesign.path("candidates").size());
        JsonNode frozenWrong=null;
        for(JsonNode decision:FIXTURE.path("trace").path("decisions"))
            if("121:0".equals(decision.path("partId").asText())&&decision.path("attemptIndex").asInt()==1&&decision.path("itemIndex").asInt()==2)frozenWrong=decision;
        assertNotNull(frozenWrong);assertEquals("electronicTendering",frozenWrong.path("key").asText());
        assertEquals("accepted",frozenWrong.path("status").asText());
        assertEquals("Both SOR detailed schedules and subsequent addenda are issued in hardcopy.",frozenWrong.path("sourceQuote").asText());
    }

    private void assertBaselineFixes(ExtractTraceVO trace,Map<String,VariableVO> variables) {
        ExtractionDecisionVO wrong=trace.getDecisions().stream()
                .filter(d->"121:0".equals(d.getPartId())&&d.getAttemptIndex()==1&&d.getItemIndex()==2).findFirst().orElseThrow(AssertionError::new);
        assertEquals("electronicTendering",wrong.getKey());assertEquals("rejected",wrong.getStatus());
        assertTrue(wrong.getCodes().contains("quote_value_mismatch"));
        assertEquals("Both SOR detailed schedules and subsequent addenda are issued in hardcopy.",wrong.getSourceQuote());
        VariableVO media=variables.get("electronicTendering");assertEquals("Hardcopy",media.getValue());
        assertEquals(1,media.getCandidates().size());assertEquals(Long.valueOf(117),media.getCandidates().get(0).getSourceDocumentId());
        VariableVO design=variables.get("designResponsibilities");
        CandidateVO complete=design.getCandidates().stream().filter(c->Long.valueOf(118).equals(c.getSourceDocumentId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(4,JsonUtils.parse(design.getValue()).size());assertEquals(complete.getValue(),design.getValue());
        assertTrue(design.getSource().startsWith(complete.getSourceQuote().substring(0,Math.min(300,complete.getSourceQuote().length()))));
        assertEquals(complete.getReason(),design.getNote());assertFalse(design.isReviewRequired());
        assertFalse(design.isConfirmed());assertFalse(design.isManuallyEdited());
    }

    private void installReplay() throws Exception {
        when(model.available()).thenReturn(true);when(model.chatModel()).thenReturn("recorded-bonsai-alternate-replay");
        when(model.chat(anyList())).thenAnswer(invocation->{
            List<LlmClient.ChatTurn> turns=invocation.getArgument(0);
            String user=turns.stream().filter(turn->"user".equals(turn.getRole())).findFirst().orElseThrow(AssertionError::new).getContent();
            if(!user.contains("<joint-review-packets>"))throw new AssertionError("A replay must never dispatch an extraction or other model request.");
            for(JsonNode relation:FIXTURE.path("trace").path("relations"))if(user.startsWith("Variable key: "+relation.path("key").asText()+"\n")) {
                assertTrue(replayedJointKeys.add(relation.path("key").asText()),"Only one captured response exists for this joint review.");
                return relation.path("rawResponse").asText();
            }
            throw new AssertionError("No real captured joint response exists for this key.");
        });
        doAnswer(invocation->{
            ExtractTraceVO trace=invocation.getArgument(0);ExtractionPartVO part=invocation.getArgument(1);String source=invocation.getArgument(4);
            JsonNode captured=part(part.getPartId());assertEquals(captured.path("sourceText").asText(),part.getSourceText());
            for(JsonNode attempt:captured.path("attempts"))if(!"recall".equals(attempt.path("kind").asText()))replay(trace,part,attempt,source);
            return null;
        }).when(harness).extractPart(any(ExtractTraceVO.class),any(ExtractionPartVO.class),anyString(),anyString(),anyString());
        doAnswer(invocation->{
            ExtractTraceVO trace=invocation.getArgument(0);Map<Long,String> sourceTexts=invocation.getArgument(3);
            for(ExtractionPartVO part:trace.getParts())for(JsonNode attempt:part(part.getPartId()).path("attempts"))
                if("recall".equals(attempt.path("kind").asText()))replay(trace,part,attempt,sourceTexts.get(part.getSourceDocumentId()));
            return null;
        }).when(harness).recall(any(ExtractTraceVO.class),anyString(),anyString(),anyMap(),anyMap());
    }

    private void replay(ExtractTraceVO trace,ExtractionPartVO part,JsonNode recorded,String originalSource) throws Exception {
        ExtractionAttemptVO attempt=JsonUtils.mapper().treeToValue(recorded,ExtractionAttemptVO.class);
        assertTrue(replayedAttempts.add(part.getPartId()+"/"+attempt.getAttemptIndex()));
        part.getAttempts().add(attempt);trace.getRawResponses().add(attempt.getRawResponse());
        ExtractionPartVO intakePart=new ExtractionPartVO(part.getPartId(),part.getSourceDocumentId(),part.getFileName(),
                part.getSourceHash(),part.getPartIndex(),part.getSourceText(),new ArrayList<>());
        intakePart.setContext(attempt.getContext());
        // Each unchanged captured response goes through public extractPart, including its real wrapper decoder.
        // Only recorded scheduling is frozen: new bounded follow-up requests receive [] in a disposable trace.
        trace.getDecisions().addAll(DraftHarnessTestIntake.primaryDecisions(attempt.getRawResponse(),intakePart,originalSource,
                attempt.getAttemptIndex(),attempt.getSystemPrompt(),attempt.getUserPrompt()));
    }

    private void validateRecordedContexts() {
        Iterator<Map.Entry<String,JsonNode>> sources=FIXTURE.path("sources").fields();
        while(sources.hasNext()) {
            JsonNode source=sources.next().getValue();assertEquals(source.path("utf8Sha256").asText(),DraftAdoption.hash(source.path("originalSource").asText()));
        }
        for(JsonNode part:FIXTURE.path("trace").path("parts"))for(JsonNode attempt:part.path("attempts")) {
            JsonNode context=attempt.path("context");String source=FIXTURE.path("sources").path(part.path("sourceDocumentId").asText()).path("originalSource").asText();
            assertEquals(source.substring(context.path("sourceStart").asInt(),context.path("sourceEnd").asInt()),context.path("sourceText").asText(),part.path("partId").asText());
        }
    }
    private String project(String prefix) {
        Project project=new Project();project.setId(prefix+UUID.randomUUID());project.setNameZhHans("Offline captured regression");project.setContractNo("METADATA-MUST-NOT-BECOME-EVIDENCE");projects.save(project);return project.getId();
    }
    private JsonNode part(String id) {
        for(JsonNode part:FIXTURE.path("trace").path("parts"))if(id.equals(part.path("partId").asText()))return part;
        throw new AssertionError("No real captured part "+id);
    }
    private static JsonNode resource(String name) {
        try(InputStream stream=DraftingCapturedBaselineWorkflowTest.class.getResourceAsStream(RESOURCE+name)) {
            if(stream==null)throw new IllegalStateException("Missing captured resource "+name);return JsonUtils.mapper().readTree(stream);
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
    private static JsonNode decode(String value,String kind) {
        if(JsonUtils.isBlankText(value))return JsonUtils.mapper().nullNode();
        if(Arrays.asList("text","date","choice").contains(kind))return JsonUtils.mapper().getNodeFactory().textNode(value);
        return JsonUtils.parse(value);
    }
    /** Trade checkboxes are unordered; preserve order for every other business list. */
    private static JsonNode businessIdentity(String key,JsonNode value) {
        JsonNode normalized=identity(value);
        if(!"subcontractors".equals(key)||!normalized.isArray())return normalized;
        List<JsonNode> trades=new ArrayList<>();normalized.forEach(trades::add);
        trades.sort(Comparator.comparing(JsonNode::toString));
        ArrayNode result=JsonUtils.mapper().createArrayNode();trades.forEach(result::add);
        return result; // Duplicate/missing trades remain observable; no set deduplication.
    }
    /** Compare business data; only generated editor IDs and presentation whitespace are excluded. */
    private static JsonNode identity(JsonNode value) {
        if(value.isTextual())return JsonUtils.mapper().getNodeFactory().textNode(value.asText().replace('\u00a0',' ').replace('\u2018','\'').replace('\u2019','\'').replace('\u201c','"').replace('\u201d','"').trim().replaceAll("\\s+"," "));
        if(value.isObject()) {
            ObjectNode result=JsonUtils.mapper().createObjectNode();value.fields().forEachRemaining(entry->{if(!"id".equals(entry.getKey()))result.set(entry.getKey(),identity(entry.getValue()));});return result;
        }
        if(value.isArray()) {ArrayNode result=JsonUtils.mapper().createArrayNode();for(JsonNode item:value)result.add(identity(item));return result;}
        return value;
    }
    private void export(List<VariableVO> variables,ExtractTraceVO trace,Map<String,Object> plan,List<Map<String,Object>> inputs,Map<String,Object> catalog) throws Exception {
        Path directory=Paths.get("target","captured-workflow-replay",fixtureName);Files.createDirectories(directory);
        write(directory.resolve("variables.json"),variables);write(directory.resolve("trace.json"),trace);
        write(directory.resolve("plan.json"),plan);write(directory.resolve("inputs.json"),inputs);write(directory.resolve("catalog.json"),catalog);
        write(directory.resolve("manifest.json"),FIXTURE.path("documentManifest"));
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("mode","offline-recorded-model-output-replay");receipt.put("originalRunId",FIXTURE.path("provenance").path("runId").asText());
        receipt.put("capturedTraceUtf8Sha256",FIXTURE.path("provenance").path("capturedTraceUtf8Sha256").asText());
        receipt.put("extractionAttemptsReplayed",replayedAttempts.size());receipt.put("jointResponsesReplayed",replayedJointKeys.size());receipt.put("freshModelCalls",0);
        receipt.put("capturedJointResponses",FIXTURE.path("trace").path("relations").size());
        receipt.put("unusedCapturedJointResponses",FIXTURE.path("trace").path("relations").size()-replayedJointKeys.size());
        receipt.put("testOnlyEmptyFollowUpRepliesExcludedFromCapturedTrace",true);
        receipt.put("partsFrozen",FIXTURE.path("trace").path("parts").size());
        receipt.put("capturedDecisionsFrozen",FIXTURE.path("trace").path("decisions").size());
        receipt.put("currentHarnessVersion",trace.getHarnessVersion());receipt.put("oracleUsedForModelResponses",false);write(directory.resolve("replay-receipt.json"),receipt);
    }
    private static void write(Path file,Object value) throws Exception {Files.write(file,JsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value).getBytes(StandardCharsets.UTF_8));}
}
