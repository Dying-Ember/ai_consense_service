package com.consense.service.advice;

import com.consense.ai.AiGateway;
import com.consense.ai.LlmClient;
import com.consense.ai.LlmProfiles;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.ChatMessage;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.repository.ChatMessageRepository;
import com.consense.repository.EvidenceChunkRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.prompt.PromptService;
import com.consense.service.vetting.FixedBaselineRetrievalClient;
import com.consense.service.vetting.FixedReferenceEvidence;
import com.consense.service.vetting.OerBusinessEvidence;
import com.consense.vector.VectorStore;
import com.consense.web.dto.AdviceDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import javax.persistence.EntityManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored in-memory carriers and mocked adapters only: no HTTP, model, database or vector service. */
class AdviceFixedBaselineTest {
    private AdviceService advice;
    private AiGateway ai;
    private LlmClient llm;
    private com.consense.service.vetting.VettingTokenBudgetCliObserver counter;
    private OerBusinessEvidence canonical;
    private FixedBaselineRetrievalClient fixed;
    private SourceDocumentRepository documents;
    private EvidenceChunkRepository legacy;
    private ChatMessageRepository messages;
    private VectorStore vectors;
    private VectorStore.SearchHit standard;
    private FixedReferenceEvidence evidence;
    private final List<ChatMessage> saved=new ArrayList<>();
    private final AtomicLong ids=new AtomicLong();

    @BeforeEach void setup() throws Exception {
        ProjectService projects=mock(ProjectService.class);when(projects.require("authored-project")).thenReturn(new Project());
        ai=mock(AiGateway.class);canonical=mock(OerBusinessEvidence.class);fixed=mock(FixedBaselineRetrievalClient.class);
        ConsenseProperties props=new ConsenseProperties();props.getLlm().setNumCtx(262144);props.getLlm().setStructuredThinking(false);
        llm=mock(LlmClient.class);when(llm.available()).thenReturn(true);when(ai.capture()).thenReturn(new LlmProfiles(props,llm,mock(LlmClient.class)).capture());
        counter=mock(com.consense.service.vetting.VettingTokenBudgetCliObserver.class);AdviceBudgetFixture.bind(counter,llm);
        documents=mock(SourceDocumentRepository.class);legacy=mock(EvidenceChunkRepository.class);messages=mock(ChatMessageRepository.class);vectors=mock(VectorStore.class);
        PromptService prompts=mock(PromptService.class);when(prompts.system(anyString())).thenReturn("AUTHORED_SYSTEM");when(prompts.userTemplate(anyString())).thenReturn("%s\n%s\n%s");
        PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        advice=new AdviceService(projects,ai,vectors,mock(OcrClient.class),props,documents,legacy,messages,prompts,canonical,transactions);
        ReflectionTestUtils.setField(advice,"fixedBaseline",fixed);ReflectionTestUtils.setField(advice,"entityManager",mock(EntityManager.class));
        ReflectionTestUtils.setField(advice,"tokenObserver",counter);
        when(messages.save(any())).thenAnswer(call->{ChatMessage row=call.getArgument(0);if(row.getId()==null){row.setId(ids.incrementAndGet());saved.add(row);}return row;});
        when(fixed.enabled()).thenReturn(true);when(fixed.frozenManifest()).thenReturn(JsonUtils.parse("{\"version\":\"AUTHORED-FROZEN-05\",\"developmentBaseline\":true}"));
        JsonNode payload=JsonUtils.parse("{\"id\":\"fixed:authored\",\"content\":\"AUTHORED complete standard text including the exception.\",\"fileLabel\":\"GCC authored standard\",\"fileKey\":\"GCC\",\"pageNo\":42,\"originalPageNos\":[42],\"anchor\":\"AUTHORED clause\",\"sourceHash\":\"AUTHORED-SOURCE-SHA\",\"originalSourceSha256\":\"AUTHORED-SOURCE-SHA\",\"sourceRole\":\"standard\",\"sourceQuality\":{\"family\":\"GCC\",\"finalQualityAccepted\":false},\"fixedDevelopmentBaseline\":true,\"fixedBaselineManifestSha256\":\"AUTHORED-MANIFEST-SHA\",\"fixedBaselineVersion\":\"05\",\"rawAudit\":\"RAW_AUDIT_SENTINEL\"}");
        Constructor<FixedReferenceEvidence> constructor=FixedReferenceEvidence.class.getDeclaredConstructor(String.class,double.class,JsonNode.class,JsonNode.class,JsonNode.class);constructor.setAccessible(true);
        JsonNode unit=JsonUtils.parse("{\"status\":\"complete_observed_unit\",\"requiredMemberIds\":[\"fixed:authored\"],\"presentMemberIds\":[\"fixed:authored\"],\"missingMemberIds\":[],\"qualifiersComplete\":\"unknown\"}");
        evidence=constructor.newInstance("fixed:authored",0.9,payload,null,unit);standard=evidence.asHit();
        when(fixed.search(eq("advisory"),anyString(),eq(10))).thenReturn(Collections.singletonList(standard));
        when(fixed.evidence(any())).thenReturn(evidence);when(fixed.toModelMaterial(anyList(),eq(200000))).thenReturn("{\"role\":\"standard\",\"reference_only\":true,\"id\":\"fixed:authored\",\"content\":\"AUTHORED complete standard text including the exception.\"}");
        when(llm.chat(anyList())).thenReturn(JsonUtils.write(answer("reference_only")));
    }
    private AdviceService.AnswerRecord answer(String basis){return new AdviceService.AnswerRecord(true,"AUTHORED explanation",Collections.singletonList("[1]"),null,Collections.emptyList(),basis);}
    private AskRequest reference(){AskRequest request=new AskRequest("Explain the authored standard.","fullset");request.setBasisScope("reference_only");return request;}

    @Test void explicitReferenceOnlyUsesFixedStandardsWithoutInventingAProjectCanonicalSession() {
        AskResponseVO response=advice.ask("authored-project",reference());
        assertTrue(response.isGrounded());assertEquals("reference_only",response.getBasisScope());
        CitationVO source=response.getSources().get(0);assertEquals("standard",source.getSourceRole());assertNull(source.getDocumentId());
        assertNull(source.getCanonicalEvidence());assertNull(source.getCanonicalSourceUnit());assertFalse(source.getApplicabilityContextComplete());
        assertEquals("fixed_development_baseline",source.getFixedBaselineEvidence().path("evidenceBackend").asText());
        assertFalse(source.getFixedBaselineEvidence().path("sourceQualityAccepted").asBoolean(true));
        assertTrue(source.getFixedBaselineEvidence().path("originalUrl").asText().contains("sourceSha256=AUTHORED-SOURCE-SHA"));
        assertTrue(response.getEvidenceId().startsWith("fixed-baseline:"));
        verifyNoInteractions(canonical,documents,legacy,vectors);
        assertEquals(2,saved.size());assertEquals("reference_only",saved.get(0).getBasisScope());
        CitationVO restored=JsonUtils.readList(saved.get(1).getSourcesJson(),CitationVO.class).get(0);
        assertEquals(source.getFixedBaselineEvidence(),restored.getFixedBaselineEvidence());
        assertEquals(evidence.getSourceUnit(),restored.getFixedBaselineEvidence().path("sourceUnit"));
        assertEquals("unknown",restored.getFixedBaselineEvidence().path("sourceUnit").path("qualifiersComplete").asText());
    }
    @Test void modelCannotUpgradeAnExplicitStandardExplanationToAProjectContractConclusion() {
        when(llm.chat(anyList())).thenReturn(JsonUtils.write(answer("project_contract")));
        AskResponseVO response=advice.ask("authored-project",reference());
        assertFalse(response.isGrounded());assertEquals("insufficient",response.getBasisScope());assertFalse(response.getClarifications().isEmpty());
        verifyNoInteractions(canonical,documents,legacy,vectors);
    }
    @Test void fixedOnlyCannotPretendThatTheProjectAdoptedTheStandard() throws Exception {
        AskResponseVO reference=advice.ask("authored-project",reference());
        Method gate=AdviceService.class.getDeclaredMethod("incorporationSupported",String.class,List.class,List.class);gate.setAccessible(true);
        assertEquals(false,gate.invoke(advice,"project_contract",Collections.singletonList("[1]"),reference.getSources()));
    }
    @Test void canonicalFailureNeverFallsBackToTheEnabledFixedReferenceClient() {
        when(canonical.enabled()).thenReturn(true);
        SourceDocument source=new SourceDocument();source.setId(1L);source.setProjectId("authored-project");source.setFileKey("SCC");source.setFileName("project SCC.txt");source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setReviewRole("tender");source.setTextContent("AUTHORED project condition");source.setParseStatus("PARSED");source.setCreatedAt(Instant.now());
        when(documents.findBusinessSourcesByProjectId("authored-project")).thenReturn(Collections.singletonList(source));
        when(canonical.load(anyString(),eq("advisory"),anyList())).thenThrow(new IllegalStateException("AUTHORED missing canonical binding"));
        BizException failure=assertThrows(BizException.class,()->advice.ask("authored-project",new AskRequest("Project applicability?","contract")));
        assertEquals(4204,failure.getCode());verify(fixed,never()).search(anyString(),anyString(),anyInt());verify(ai,never()).completeJson(anyString(),anyString(),any());assertTrue(saved.isEmpty());
    }
    @Test void explicitReferenceOnlyIsRejectedWhenTheDefaultOffAdapterIsDisabled() {
        when(fixed.enabled()).thenReturn(false);
        BizException failure=assertThrows(BizException.class,()->advice.ask("authored-project",reference()));
        assertEquals(4204,failure.getCode());verify(fixed,never()).search(anyString(),anyString(),anyInt());verify(ai,never()).completeJson(anyString(),anyString(),any());assertTrue(saved.isEmpty());
    }
    @Test void frozenSourceChangeDuringTheModelCallPreventsHistoryPersistence() {
        AtomicBoolean changed=new AtomicBoolean();
        doAnswer(call->{if(changed.get())throw new IllegalStateException("AUTHORED frozen source changed");return null;}).when(fixed).assertCurrent(anyList());
        when(llm.chat(anyList())).thenAnswer(call->{changed.set(true);return JsonUtils.write(answer("reference_only"));});
        BizException failure=assertThrows(BizException.class,()->advice.ask("authored-project",reference()));
        assertEquals(4204,failure.getCode());assertTrue(saved.isEmpty());
    }
    @Test void promptUsesTheCompleteFixedModelViewOnceAndDoesNotSerializeRawPayload() {
        advice.ask("authored-project",reference());
        org.mockito.ArgumentCaptor<List> prompt=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(llm).chatStructured(prompt.capture(),any());
        String input=((LlmClient.ChatTurn)prompt.getValue().get(1)).getContent();assertFalse(input.contains("RAW_AUDIT_SENTINEL"));
        String literal="AUTHORED complete standard text including the exception.";
        assertEquals(input.indexOf(literal),input.lastIndexOf(literal));assertTrue(input.contains(literal));
        assertTrue(input.contains("Explicit answer scope: reference_only"));
        verify(fixed).toModelMaterial(anyList(),eq(200000));
    }
    @Test void referenceOnlyWithoutASessionCannotBypassTheFinalNativeBudget() {
        doReturn(null).when(counter).observe(any());
        BizException unknown=assertThrows(BizException.class,()->advice.ask("authored-project",reference()));assertEquals(4205,unknown.getCode());
        doAnswer(call->{com.consense.service.vetting.VettingInputBudget.Observation result=AdviceBudgetFixture.observation(call.getArgument(0));result.setInputTokens(262144);return result;}).when(counter).observe(any());
        BizException over=assertThrows(BizException.class,()->advice.ask("authored-project",reference()));assertEquals(4205,over.getCode());
        verify(llm,never()).chatStructured(anyList(),any());assertTrue(saved.isEmpty());
    }
}
