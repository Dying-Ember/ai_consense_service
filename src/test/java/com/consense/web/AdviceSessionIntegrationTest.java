package com.consense.web;

import com.consense.ai.IncompleteModelResponseException;
import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.SourceDocument;
import com.consense.repository.ChatMessageRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.StorageService;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.vetting.VettingInputBudget;
import com.consense.service.vetting.VettingTokenBudgetCliObserver;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public HTTP plus isolated SQL persistence. Model and token observations are fixtures, never live acceptance. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.llm.chat-model=session-test-chat",
        "consense.llm.num-ctx=262144",
        "consense.llm.structured-thinking=false",
        "consense.advice.history-max-exchanges=2","consense.advice.history-max-messages=4","consense.advice.history-max-tokens=1024",
        "consense.minimax-cn.enabled=true","consense.minimax-cn.base-url=https://session-test.invalid",
        "consense.minimax-cn.api-key=session-test-only"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class AdviceSessionIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:advice_sessions_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->java.nio.file.Paths.get("target","advice-session-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired SourceDocumentRepository sources;
    @Autowired ChatMessageRepository messages;
    @Autowired ConsenseProperties properties;
    @Autowired StorageService storage;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;
    @MockBean VettingTokenBudgetCliObserver counter;
    private final List<String> prompts=Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong overrideTokens=new AtomicLong();
    private String answer="{\"grounded\":true,\"answer\":\"TEST ONLY supported session answer\",\"citations\":[\"[1]\"]}";

    @BeforeEach void adapters() {
        prompts.clear();overrideTokens.set(0);
        answer="{\"grounded\":true,\"answer\":\"TEST ONLY supported session answer\",\"citations\":[\"[1]\"]}";
        when(local.available()).thenReturn(true);when(miniMax.available()).thenReturn(true);
        when(local.chatModel()).thenReturn("session-test-chat");when(local.embedModel()).thenReturn("session-test-embedding");
        doAnswer(call->{List<float[]> out=new ArrayList<>();for(Object ignored:call.<List<?>>getArgument(0))out.add(new float[]{1,0});return out;}).when(local).embed(anyList());
        doAnswer(call->answer).when(local).chat(anyList());
        doAnswer(call->{prompts.add(JsonUtils.write(call.getArgument(0)));return answer;}).when(local).chatStructured(anyList(),any());
        doAnswer(call->{prompts.add(JsonUtils.write(call.getArgument(0)));return answer;}).when(miniMax).chatStructured(anyList(),any());
        doAnswer(call->observation(call.getArgument(0))).when(counter).observe(any());
    }

    @Test void sessionsPersistProjectAndImmutableScopeAndRejectOtherProjectsBeforeRetrieval() throws Exception {
        String project=project(),other=project(),session=session(project,"contract","project_contract");
        JsonNode listed=data(get("/api/advice/{id}/sessions",project));assertEquals(1,listed.size());
        assertEquals(project,listed.get(0).path("projectId").asText());assertEquals("contract",listed.get(0).path("scope").asText());assertFalse(listed.get(0).path("createdAt").asText().isEmpty());
        assertTrue(data(get("/api/advice/{id}/sessions",other)).isEmpty());
        mvc.perform(get("/api/advice/{id}/messages",other).param("sessionId",session)).andExpect(jsonPath("$.code").value(4202));
        mvc.perform(delete("/api/advice/{id}/messages",other).param("sessionId",session)).andExpect(jsonPath("$.code").value(4202));
        mvc.perform(askRequest(project,session,"fullset","project_contract","TEST ONLY mismatch")).andExpect(jsonPath("$.code").value(4202));
        mvc.perform(askRequest(project,session,"contract","reference_only","TEST ONLY mismatch")).andExpect(jsonPath("$.code").value(4202));
        verify(local,never()).chatStructured(anyList(),any());verifyNoInteractions(counter);
    }

    @Test void reloadKeepsPerAnswerSourcesAndClearingOneSessionDoesNotDeleteAnotherOrLegacy() throws Exception {
        String project=project();SourceDocument original=source(project,"ORIGINAL SOURCE TEST ONLY");rebuild(project);
        String first=session(project,"contract","project_contract"),second=session(project,"contract","project_contract");
        JsonNode response=ask(project,first,"FIRST QUESTION TEST ONLY");ask(project,second,"SECOND QUESTION TEST ONLY");
        data(post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"LEGACY QUESTION TEST ONLY\",\"scope\":\"contract\"}"));
        original.setTextContent("NEW SOURCE TEXT TEST ONLY");sources.saveAndFlush(original);
        JsonNode history=history(project,first);assertEquals(2,history.size());assertEquals(first,history.get(1).path("sessionId").asText());
        assertEquals(response.path("sources"),history.get(1).path("sources"));assertTrue(history.get(1).path("sources").toString().contains("ORIGINAL SOURCE"));
        assertEquals(response.path("modelIdentity"),history.get(1).path("modelIdentity"));
        data(delete("/api/advice/{id}/messages",project).param("sessionId",first));assertTrue(history(project,first).isEmpty());assertEquals(2,history(project,second).size());
        assertEquals(2,data(get("/api/advice/{id}/messages",project)).size());
        data(delete("/api/advice/{id}/messages",project));assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());assertEquals(2,history(project,second).size());
    }

    @Test void historyContainsOnlyWholeRecentExchangesFromTheSameSession() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);
        String first=session(project,"contract","project_contract"),other=session(project,"contract","project_contract");
        ask(project,other,"OTHER SESSION SECRET TEST ONLY");
        ask(project,first,"OLD FIRST EXCHANGE TEST ONLY");ask(project,first,"RECENT SECOND EXCHANGE TEST ONLY");ask(project,first,"RECENT THIRD EXCHANGE TEST ONLY");
        prompts.clear();ask(project,first,"FOLLOW UP FOURTH TEST ONLY");String prompt=prompts.get(0);
        assertTrue(prompt.contains("RECENT SECOND EXCHANGE"));assertTrue(prompt.contains("RECENT THIRD EXCHANGE"));
        assertFalse(prompt.contains("OLD FIRST EXCHANGE"));assertFalse(prompt.contains("OTHER SESSION SECRET"));assertTrue(prompt.contains("prior answers are not contractual evidence"));
    }

    @Test void aSourceOrModelChangeExcludesPreviousHistoryWithoutRelinkingOldCitations() throws Exception {
        String project=project();SourceDocument original=source(project,"ORIGINAL SOURCE TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        JsonNode old=ask(project,session,"OLD HISTORY SECRET TEST ONLY");original.setTextContent("REPLACED SOURCE TEST ONLY");sources.saveAndFlush(original);rebuild(project);
        prompts.clear();ask(project,session,"CURRENT SOURCE QUESTION TEST ONLY");assertFalse(prompts.get(0).contains("OLD HISTORY SECRET"));
        assertEquals(old.path("sources"),history(project,session).get(1).path("sources"));
        prompts.clear();data(askRequest(project,session,"contract","project_contract","NEW MODEL QUESTION TEST ONLY").header("X-ConSense-Llm-Profile","minimax-cn"));
        assertFalse(prompts.get(0).contains("CURRENT SOURCE QUESTION"));
    }

    @Test void clearDuringModelGenerationRejectsLatePublicationAndLeavesHistoryEmpty() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        when(local.chatStructured(anyList(),any())).thenAnswer(call->{data(delete("/api/advice/{id}/messages",project).param("sessionId",session));return answer;});
        mvc.perform(askRequest(project,session,"contract","project_contract","LATE ANSWER TEST ONLY")).andExpect(jsonPath("$.code").value(4202));
        assertTrue(history(project,session).isEmpty());
    }

    @Test void concurrentQuestionsCannotPublishAgainstTheSameSessionRevision() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        when(local.chatStructured(anyList(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return answer;});
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call=()->JsonUtils.parse(mvc.perform(askRequest(project,session,"contract","project_contract","CONCURRENT QUESTION TEST ONLY")).andReturn().getResponse().getContentAsString()).path("code").asInt();
            Future<Integer> first=executor.submit(call),second=executor.submit(call);assertTrue(entered.await(10,TimeUnit.SECONDS));release.countDown();
            List<Integer> codes=Arrays.asList(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(Arrays.asList(0,4202),codes);
            assertEquals(2,history(project,session).size());
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void changedSourcesDuringGenerationRejectTheNewExchange() throws Exception {
        String project=project();SourceDocument source=source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        when(local.chatStructured(anyList(),any())).thenAnswer(call->{source.setTextContent("SOURCE CHANGED WHILE CALLING TEST ONLY");sources.saveAndFlush(source);return answer;});
        mvc.perform(askRequest(project,session,"contract","project_contract","SOURCE CHANGE TEST ONLY")).andExpect(jsonPath("$.code").value(4204));assertTrue(history(project,session).isEmpty());
    }

    @Test void unknownOrExhaustedBudgetsNeverDispatchAndIncompleteOutputsNeverPublish() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        doReturn(null).when(counter).observe(any());
        mvc.perform(askRequest(project,session,"contract","project_contract","UNKNOWN BUDGET TEST ONLY")).andExpect(jsonPath("$.code").value(4205)).andExpect(jsonPath("$.message").exists());
        verify(local,never()).chatStructured(anyList(),any());assertTrue(history(project,session).isEmpty());
        adapters();overrideTokens.set(262144);
        mvc.perform(askRequest(project,session,"contract","project_contract","OVER BUDGET TEST ONLY")).andExpect(jsonPath("$.code").value(4205));verify(local,never()).chatStructured(anyList(),any());
        overrideTokens.set(0);when(local.chatStructured(anyList(),any())).thenThrow(new IncompleteModelResponseException("TEST ONLY incomplete",null,null,IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,null));
        mvc.perform(askRequest(project,session,"contract","project_contract","OUTPUT INCOMPLETE TEST ONLY")).andExpect(jsonPath("$.code").value(4205));assertTrue(history(project,session).isEmpty());
    }

    @Test void clarificationParentsMustRetainSessionScopeAndSourceVersion() throws Exception {
        String project=project();SourceDocument original=source(project,"CURRENT BASIS TEST ONLY");rebuild(project);
        String session=session(project,"contract","project_contract"),other=session(project,"contract","project_contract");
        answer="{\"grounded\":false,\"clarifications\":[\"What contract stage?\"]}";JsonNode pending=ask(project,session,"ORIGINAL CLARIFICATION QUESTION TEST ONLY");
        long parent=pending.path("messageId").asLong();
        mvc.perform(reply(project,other,parent)).andExpect(jsonPath("$.code").value(4202));
        answer="{\"grounded\":true,\"answer\":\"TEST ONLY supported answer\",\"citations\":[\"[1]\"]}";
        prompts.clear();JsonNode response=data(reply(project,session,parent));assertTrue(response.path("grounded").asBoolean());assertTrue(prompts.get(0).contains("user-supplied-background"));
        assertFalse(response.path("sources").toString().contains("USER ASSUMPTION"));
        original.setTextContent("NEW REVISION TEST ONLY");sources.saveAndFlush(original);rebuild(project);
        mvc.perform(reply(project,session,parent)).andExpect(jsonPath("$.code").value(4204));
    }

    @Test void historyTokenCapDropsWholeOldExchangesAndNeverCutsAStoredAnswer() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        answer="{\"grounded\":true,\"answer\":\""+String.join("",Collections.nCopies(9000,"X"))+" END OF COMPLETE ANSWER TEST ONLY\",\"citations\":[\"[1]\"]}";
        JsonNode old=ask(project,session,"LONG OLD EXCHANGE TEST ONLY");
        answer="{\"grounded\":true,\"answer\":\"TEST ONLY fresh answer\",\"citations\":[\"[1]\"]}";prompts.clear();ask(project,session,"NEXT QUESTION TEST ONLY");
        assertFalse(prompts.get(0).contains("LONG OLD EXCHANGE"));assertTrue(history(project,session).get(1).path("content").path("en").asText().endsWith("END OF COMPLETE ANSWER TEST ONLY"));
        assertEquals(old.path("content"),history(project,session).get(1).path("content"));
    }

    @Test void retryIsCountedWithTheExactMessagesSubmittedToTheCapturedClient() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        List<List<LlmClient.ChatTurn>> dispatched=new ArrayList<>();List<VettingInputBudget.Input> counted=new ArrayList<>();
        doAnswer(call->{VettingInputBudget.Input input=call.getArgument(0);counted.add(input);return observation(input);}).when(counter).observe(any());
        doAnswer(call->{dispatched.add(call.getArgument(0));return dispatched.size()==1?"TEST ONLY invalid JSON":answer;}).when(local).chatStructured(anyList(),any());
        assertTrue(ask(project,session,"RETRY QUESTION TEST ONLY").path("grounded").asBoolean());assertEquals(2,counted.size());assertEquals(2,dispatched.size());
        for(int index=0;index<2;index++)assertEquals(JsonUtils.write(counted.get(index).getMessages()),JsonUtils.write(dispatched.get(index)));
        assertFalse(dispatched.get(0).get(1).getContent().equals(dispatched.get(1).get(1).getContent()));
        assertFalse(counted.get(0).getSerializedInputSha256().equals(counted.get(1).getSerializedInputSha256()));assertEquals(2,history(project,session).size());
    }

    @Test void sourceMutationInsideTokenCountingPreventsModelDispatch() throws Exception {
        String project=project();SourceDocument original=source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        doAnswer(call->{original.setTextContent("MUTATED DURING COUNT TEST ONLY");sources.saveAndFlush(original);return observation(call.getArgument(0));}).when(counter).observe(any());
        mvc.perform(askRequest(project,session,"contract","project_contract","COUNTER MUTATION TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        verify(local,never()).chatStructured(anyList(),any());assertTrue(history(project,session).isEmpty());
    }

    @Test void changingTheModelProfileDuringCountingCannotDispatchAgainstTheOldIdentity() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        int outputReserve=properties.getLlm().getStructuredMaxTokens();
        for(String selectedSession:new String[]{session,null})try {
            doAnswer(call->{properties.getLlm().setStructuredMaxTokens(outputReserve+1);return observation(call.getArgument(0));}).when(counter).observe(any());
            mvc.perform(askRequest(project,selectedSession,"contract","project_contract","MODEL CHANGE DURING COUNT TEST ONLY")).andExpect(jsonPath("$.code").value(4205));
            verify(local,never()).chatStructured(anyList(),any());assertTrue(history(project,session).isEmpty());assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());
        } finally {properties.getLlm().setStructuredMaxTokens(outputReserve);}
    }

    @Test void originalSourceIdentityChangeRejectsPublicationEvenWhenParsedTextIsUnchanged() throws Exception {
        String project=project();SourceDocument original=source(project,"UNCHANGED PARSED TEXT TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        doAnswer(call->{original.setSourceSha256(hash('d'));sources.saveAndFlush(original);return answer;}).when(local).chatStructured(anyList(),any());
        mvc.perform(askRequest(project,session,"contract","project_contract","CHANGED ORIGINAL IDENTITY TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        assertTrue(history(project,session).isEmpty());
    }

    @Test void sameSizeAndRestoredTimestampByteMutationRejectsSessionAndLegacyAnswersWithoutChangingHistory() throws Exception {
        for(boolean explicit:new boolean[]{true,false}) {
            adapters();String project=project();SourceDocument original=source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);
            String session=explicit?session(project,"contract","project_contract"):null;
            data(askRequest(project,session,"contract","project_contract","FIRST ACCEPTED ANSWER TEST ONLY"));
            JsonNode before=explicit?history(project,session):data(get("/api/advice/{id}/messages",project));clearInvocations(local);
            java.nio.file.Path path=java.nio.file.Paths.get(original.getStoragePath());long size=java.nio.file.Files.size(path);java.nio.file.attribute.FileTime timestamp=java.nio.file.Files.getLastModifiedTime(path);
            org.mockito.stubbing.Answer<String> changed=call->{byte[] bytes=java.nio.file.Files.readAllBytes(path);bytes[0]^=1;java.nio.file.Files.write(path,bytes);java.nio.file.Files.setLastModifiedTime(path,timestamp);return answer;};
            doAnswer(changed).when(local).chatStructured(anyList(),any());
            mvc.perform(askRequest(project,session,"contract","project_contract","MUTATION DURING ANSWER TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
            assertEquals(size,java.nio.file.Files.size(path));assertEquals(timestamp,java.nio.file.Files.getLastModifiedTime(path));
            assertEquals(original.getSourceSha256(),sources.findById(original.getId()).get().getSourceSha256(),"The database identity stays unchanged; actual bytes must be rechecked");
            assertEquals(before,explicit?history(project,session):data(get("/api/advice/{id}/messages",project)));
            verify(local,times(1)).chatStructured(anyList(),any());verify(local,never()).chat(anyList());
        }
    }

    @Test void changedBytesBeforeSendingAreRejectedWithoutDispatchAndAnInvalidAnswerCannotCauseAnUnsafeRetry() throws Exception {
        String project=project();SourceDocument original=source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        java.nio.file.Path path=java.nio.file.Paths.get(original.getStoragePath());byte[] bytes=java.nio.file.Files.readAllBytes(path),changed=bytes.clone();changed[0]^=1;
        java.nio.file.Files.write(path,changed);
        mvc.perform(askRequest(project,session,"contract","project_contract","CHANGED BEFORE SENDING TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        mvc.perform(askRequest(project,null,"contract","project_contract","LEGACY CHANGED BEFORE SENDING TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        verify(local,never()).chatStructured(anyList(),any());verify(local,never()).chat(anyList());assertTrue(history(project,session).isEmpty());
        java.nio.file.Files.write(path,bytes);
        doAnswer(call->{java.nio.file.Files.write(path,changed);return "TEST ONLY invalid JSON";}).when(local).chatStructured(anyList(),any());
        mvc.perform(askRequest(project,null,"contract","project_contract","INVALID JSON WITH CHANGED BYTES TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        verify(local,times(1)).chatStructured(anyList(),any());verify(local,never()).chat(anyList());assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());
    }

    @Test void unchangedLegacySourcesAllowOneJsonRetryWithTheOriginalPromptLiterals() throws Exception {
        String project=project();source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);List<List<LlmClient.ChatTurn>> sent=new ArrayList<>();
        doAnswer(call->{sent.add(call.getArgument(0));return sent.size()==1?"TEST ONLY invalid JSON":answer;}).when(local).chatStructured(anyList(),any());
        assertTrue(data(askRequest(project,null,"contract","project_contract","LEGACY RETRY TEST ONLY")).path("grounded").asBoolean());
        assertEquals(2,sent.size());assertEquals(sent.get(0).get(0).getContent(),sent.get(1).get(0).getContent());
        assertTrue(sent.get(0).get(0).getContent().endsWith("\n\n【输出要求】只输出一个合法的 JSON，不要 Markdown 代码块，不要任何解释文字。"));
        assertEquals(sent.get(0).get(1).getContent()+"\n\n重要：上一次输出不是合法 JSON。请严格只输出 JSON。",sent.get(1).get(1).getContent());
        assertEquals(2,data(get("/api/advice/{id}/messages",project)).size());
    }

    @Test void bytesChangedByTheLegacyAvailabilityProbePreventActualDispatch() throws Exception {
        String project=project();SourceDocument original=source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);
        doAnswer(call->{java.nio.file.Path path=java.nio.file.Paths.get(original.getStoragePath());byte[] bytes=java.nio.file.Files.readAllBytes(path);bytes[0]^=1;java.nio.file.Files.write(path,bytes);return true;}).when(local).available();
        mvc.perform(askRequest(project,null,"contract","project_contract","AVAILABILITY MUTATION TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        verify(local,never()).chat(anyList());verify(local,never()).chatStructured(anyList(),any());assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());
    }

    @Test void bareAskCannotBypassUnknownOverBudgetOrSub256KAdmissionAndBoundRequestsKeepSingleQuestionSemantics() throws Exception {
        String project=project();source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);
        doReturn(null).when(counter).observe(any());
        mvc.perform(askRequest(project,null,"contract","project_contract","BARE UNKNOWN BUDGET TEST ONLY")).andExpect(jsonPath("$.code").value(4205));
        adapters();overrideTokens.set(262144);
        mvc.perform(askRequest(project,null,"contract","project_contract","BARE OVER BUDGET TEST ONLY")).andExpect(jsonPath("$.code").value(4205));
        doAnswer(call->{VettingInputBudget.Observation result=observation(call.getArgument(0));result.setInputTokens(1000);result.setEffectiveContextTokens(131072);return result;}).when(counter).observe(any());
        mvc.perform(askRequest(project,null,"contract","project_contract","BARE SHORT CONTEXT TEST ONLY")).andExpect(jsonPath("$.code").value(4205));
        verify(local,never()).chatStructured(anyList(),any());verify(local,never()).chat(anyList());assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());
        adapters();List<VettingInputBudget.Input> counted=new ArrayList<>();List<List<LlmClient.ChatTurn>> sent=new ArrayList<>();
        doAnswer(call->{VettingInputBudget.Input input=call.getArgument(0);counted.add(input);return observation(input);}).when(counter).observe(any());
        doAnswer(call->{sent.add(call.getArgument(0));return answer;}).when(local).chatStructured(anyList(),any());
        assertTrue(data(askRequest(project,null,"contract","project_contract","BARE BOUND 256K TEST ONLY")).path("grounded").asBoolean());
        assertEquals(1,counted.size());assertEquals(1,sent.size());assertEquals(JsonUtils.write(counted.get(0).getMessages()),JsonUtils.write(sent.get(0)));
        assertTrue(counted.get(0).getSourceSnapshotSha256().matches("[a-f0-9]{64}"));assertEquals(2,data(get("/api/advice/{id}/messages",project)).size());
        assertTrue(data(get("/api/advice/{id}/sessions",project)).isEmpty(),"Admission must not require silently creating a session");
    }

    private VettingInputBudget.Observation observation(VettingInputBudget.Input input) {
        VettingInputBudget.Observation result=new VettingInputBudget.Observation();result.setModel(input.getModel());result.setSerializedInputSha256(input.getSerializedInputSha256());
        result.setTokenizerIdentitySha256(hash('a'));result.setChatTemplateIdentitySha256(hash('b'));result.setEffectiveContextIdentitySha256(hash('c'));
        result.setCompleteChatTemplateAndSchemaObserved(true);result.setOutputReserveTokens(input.getOutputReserveTokens());result.setEffectiveContextTokens(262144);
        result.setInputTokens(overrideTokens.get()>0?overrideTokens.get():Math.max(1,input.getMessages().stream().mapToLong(t->t.getContent().length()).sum()/8));return result;
    }

    private MockHttpServletRequestBuilder reply(String project,String session,long parent) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("question","USER ASSUMPTION TEST ONLY post-contract");body.put("scope","contract");body.put("sessionId",session);body.put("replyToMessageId",parent);
        return post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body));
    }
    private MockHttpServletRequestBuilder askRequest(String project,String session,String scope,String basis,String question) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("question",question);body.put("scope",scope);body.put("basisScope",basis);body.put("sessionId",session);
        return post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body));
    }
    private JsonNode ask(String project,String session,String question)throws Exception{return data(askRequest(project,session,"contract","project_contract",question));}
    private JsonNode history(String project,String session)throws Exception{return data(get("/api/advice/{id}/messages",project).param("sessionId",session));}
    private String session(String project,String scope,String basis)throws Exception{return data(post("/api/advice/{id}/sessions",project).contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\""+scope+"\",\"basisScope\":\""+basis+"\"}")).path("id").asText();}
    private JsonNode data(MockHttpServletRequestBuilder request)throws Exception{return JsonUtils.parse(mvc.perform(request).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    private String project()throws Exception{String id="session-"+UUID.randomUUID();data(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"SESSION TEST ONLY\"}"));return id;}
    private void rebuild(String project)throws Exception{data(post("/api/advice/{id}/index/rebuild",project));}
    private SourceDocument source(String project,String text){SourceDocument source=new SourceDocument();source.setProjectId(project);source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setFileKey("SCC");source.setFileName("SCC session fixture.txt");source.setReviewRole("tender");source.setTextContent(text);source.setParseStatus("PARSED");
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);StorageService.StoredFile original=storage.store(project,SourceDocument.CATEGORY_VETTING_PACKAGE,new org.springframework.mock.web.MockMultipartFile("files",source.getFileName(),"text/plain",bytes));
        source.setStoragePath(original.getPath());source.setSourceSha256(OerSourceMetadata.originalSha256(bytes));return sources.saveAndFlush(source);}
    private static String hash(char value){char[] chars=new char[64];Arrays.fill(chars,value);return new String(chars);}
}
