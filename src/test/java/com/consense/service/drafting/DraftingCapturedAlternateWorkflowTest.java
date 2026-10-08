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
 * Offline replay of the 30 answers from the alternate-values frontend exam.
 * Recorded request scheduling is frozen, while production decode, intake, joint
 * validation, applicability, candidate merging and persistence run normally.
 * This is not another model evaluation. The answer oracle is assertions only.
 */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory",
        "logging.level.com.consense.ai.AiGateway=WARN"})
@ActiveProfiles("h2")
class DraftingCapturedAlternateWorkflowTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final String RESOURCE="/drafting/harness/";
    private JsonNode FIXTURE;
    private JsonNode EXPECTED;
    private String fixtureName;
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:captured_alternate_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->Paths.get("target","captured-alternate-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired DraftingService service;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @Autowired JdbcTemplate jdbc;
    @SpyBean DraftExtractionHarness harness;
    @MockBean LlmClient model;
    private final Set<String> replayedAttempts=new LinkedHashSet<>();
    private final Set<String> replayedJointKeys=new LinkedHashSet<>();

    @Test void recordedAnswersReachAllSeventyNineCorrectStatesWithoutAdoptingOrChangingAnotherProject() throws Exception {
        runFixture("captured-alternate-values",28,121);
    }

    @Test void recordedLegacyAnswersKeepAllSeventyNineStatesWithoutAdoptingOrChangingAnotherProject() throws Exception {
        runFixture("captured-legacy-values",35,180);
    }

    private void runFixture(String name,int expectedAttempts,int expectedDecisions) throws Exception {
        fixtureName=name;FIXTURE=resource(name+"/fixture.json");EXPECTED=resource(name+"/expected.json").path("expectedInputs");
        assertTrue(FIXTURE.path("replayOnly").asBoolean());
        assertEquals(79,EXPECTED.size());
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
            if(expected.isNull()) {
                assertions.add(()->assertTrue(variable.getValue()==null||variable.getValue().isEmpty(),key+" must remain blank"));
                assertions.add(()->assertTrue(variable.getCandidates()==null||variable.getCandidates().isEmpty(),key+" must have no candidate"));
            } else {
                assertions.add(()->assertEquals(identity(expected),identity(decode(variable.getValue(),variable.getKind())),key+" displayed suggestion"));
                assertions.add(()->assertTrue(variable.getCandidates().stream().anyMatch(candidate->identity(expected).equals(identity(decode(candidate.getValue(),variable.getKind())))),key+" must retain its supported complete candidate"));
            }
        });
        // The false from the isolated BQ5 title is not a project-wide decision.
        if("captured-alternate-values".equals(fixtureName))assertions.add(()->assertTrue(variables.get("allBqQuantitiesProvisional").getCandidates().stream().noneMatch(candidate->"false".equals(candidate.getValue())),"A single Bill title cannot establish the all-BQ false state."));
        for(VariableVO variable:actual)for(CandidateVO candidate:variable.getCandidates()) {
            assertions.add(()->assertTrue(DraftEvidenceQuotes.present(FIXTURE.path("sources").path(candidate.getSourceDocumentId().toString()).path("originalSource").asText(),candidate.getSourceQuote()),variable.getKey()+" must retain a literal original-source quotation"));
        }
        assertAll("Frozen frontend answers through production intake and persistence",assertions);
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
        try(InputStream stream=DraftingCapturedAlternateWorkflowTest.class.getResourceAsStream(RESOURCE+name)) {
            if(stream==null)throw new IllegalStateException("Missing captured resource "+name);return JsonUtils.mapper().readTree(stream);
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
    private static JsonNode decode(String value,String kind) {
        if(JsonUtils.isBlankText(value))return JsonUtils.mapper().nullNode();
        if(Arrays.asList("text","date","choice").contains(kind))return JsonUtils.mapper().getNodeFactory().textNode(value);
        return JsonUtils.parse(value);
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
        receipt.put("currentHarnessVersion",trace.getHarnessVersion());receipt.put("oracleUsedForModelResponses",false);write(directory.resolve("replay-receipt.json"),receipt);
    }
    private static void write(Path file,Object value) throws Exception {Files.write(file,JsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value).getBytes(StandardCharsets.UTF_8));}
}
