package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** No network or real credentials: native wire opt-in and grounded coverage are separate gates. */
class VettingExplicitResponsesRouteTest {
    static void route(ConsenseProperties props,String wire) {
        new Binder(new MapConfigurationPropertySource(Collections.singletonMap("consense.vetting.minimax-cn-wire",wire)))
            .bind("consense",Bindable.ofInstance(props));
    }
    static LlmProfiles profiles(ConsenseProperties props) {
        props.getMinimaxCn().setApiKey("CHAT_ONLY_FAKE_CREDENTIAL");
        LlmClient chat=mock(LlmClient.class);when(chat.available()).thenReturn(true);when(chat.chatModel()).thenReturn("MiniMax-M3");when(chat.chatStructured(anyList(),any())).thenReturn("[]");
        return new LlmProfiles(props,mock(LlmClient.class),chat);
    }
    static AiGateway gateway(LlmProfiles profiles) {
        return new AiGateway(mock(LlmClient.class),profiles);
    }
    static Chunk source() {
        Chunk c=new Chunk();c.setId("a");c.setDocumentId("1");c.setFileKey("PRE");c.setFileName("PRE.txt");c.setRole("tender");
        c.setAnchor("paragraph 1");c.setSourceHash(VettingCorpus.hash("original"));c.setContent("The minimum amount for an interim payment is twenty dollars.");return c;
    }
    static VettingSemanticReview review(AiGateway ai,ConsenseProperties p,VettingResponsesTransport t) {
        p.getVetting().setSemanticTopics(1);p.getVetting().setProjectReferenceComparisons(0);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList())).thenReturn(Collections.singletonList(source()));
        VettingSemanticReview r=new VettingSemanticReview(ai,p,retrieval);r.setResponsesTransport(t);return r;
    }
    @Test void noneEffortIsAcceptedAndPreservedOnBothNativeRequests() {
        ConsenseProperties p=VettingResponsesTransportTest.props();p.getVetting().getResponses().setReasoningEffort("none");
        VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();VettingResponsesTransport t=new VettingResponsesTransport(p,wire);
        VettingInputBudget.Input in=VettingResponsesTransportTest.input(t);VettingInputBudget.Result budget=VettingInputBudget.check(in,t::observe);
        assertEquals("provider_estimated_fit",budget.getStatus());
        t.complete(in,budget,"Synthetic instruction unchanged.","Native cells ['', 'Room | East 😀 中文']",VettingResponsesTransportTest.schema(),Map.class,new ArrayList<>(),null);
        assertEquals(2,wire.bodies.size());for(String body:wire.bodies)assertEquals("none",JsonUtils.parse(body).path("reasoning").path("effort").asText());
    }
    @Test void explicitMinimaxOptInUsesNativeCounterAndGenerationWithoutChatProbe() {
        ConsenseProperties p=VettingResponsesTransportTest.props();route(p,"responses");LlmProfiles profiles=profiles(p);AiGateway ai=spy(gateway(profiles));
        VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();VettingSemanticReview review=review(ai,p,new VettingResponsesTransport(p,wire));
        try(LlmProfiles.Scope ignored=profiles.bind(profiles.resolve("minimax-cn"))) {
            VettingSemanticReview.Result result=review.review("p",Collections.singletonList(source()),"en",(n,s)->{});
            assertEquals(1,result.getActualGatewayCallCount());assertEquals("MiniMax-M3",result.getModel());
            assertEquals(1,wire.endpoints.stream().filter(x->x.endsWith("/v1/responses")).count());
            assertTrue(wire.endpoints.stream().anyMatch(x->x.endsWith("/v1/responses/input_tokens")));
            assertTrue(wire.bodies.stream().allMatch(x->"none".equals(JsonUtils.parse(x).path("reasoning").path("effort").asText())));
            verify(ai,never()).requireAvailable();verify(ai,never()).completeStructuredJsonList(anyString(),anyString(),any(),any(),anyList());
            assertTrue(result.getCandidates().isEmpty());assertEquals("completed_empty",result.getTopicAudits().get(0).getGlobalCallStatus());
            assertFalse(result.getTopicAudits().get(0).isSemanticScopeVerified(),"provider estimate and decoded output do not establish whole-source semantic completeness");
        }
    }
    @Test void samePreparedEntitySurvivesDefensiveInputCopyButFreshCountIsStillRequired() {
        ConsenseProperties p=VettingResponsesTransportTest.props();VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();
        VettingResponsesTransport.Operation operation=new VettingResponsesTransport(p,wire).capture("minimax-cn","none");
        VettingInputBudget.Input in=operation.input("Synthetic instruction unchanged.","Native cells ['', 'Room | East 😀 中文']",VettingResponsesTransportTest.schema(),VettingCorpus.hash("synthetic source"));
        VettingResponsesTransport.PreparedRequest prepared=operation.prepare(in);
        VettingInputBudget.Input copied=operation.input("Synthetic instruction unchanged.","Native cells ['', 'Room | East 😀 中文']",VettingResponsesTransportTest.schema(),VettingCorpus.hash("synthetic source"));
        assertNotSame(in,copied);assertEquals(JsonUtils.write(in),JsonUtils.write(copied));
        assertSame(prepared,operation.prepare(copied));
        VettingInputBudget.Result first=VettingInputBudget.check(in,operation::observe),second=VettingInputBudget.check(in,operation::observe);
        assertEquals("provider_estimated_fit",first.getStatus());assertEquals("provider_estimated_fit",second.getStatus());assertEquals(2,wire.endpoints.size());
        operation.complete(in,second,"Synthetic instruction unchanged.","Native cells ['', 'Room | East 😀 中文']",VettingResponsesTransportTest.schema(),Map.class,new ArrayList<>(),null);
        assertSame(prepared,operation.prepare(in));assertEquals(prepared.getGenerationJson(),wire.bodies.get(2));
        for(String key:Arrays.asList("model","instructions","input","reasoning"))assertEquals(JsonUtils.parse(prepared.getCounterJson()).get(key),JsonUtils.parse(prepared.getGenerationJson()).get(key));
        assertFalse(prepared.getGenerationJson().contains("FAKE_TEST_CREDENTIAL_ONLY"));
        assertEquals(1,prepared.getGenerationJson().split("Return only a JSON array conforming to this schema:",-1).length-1);
    }
    @Test void capturedRouteAndSafeIdentitySurviveOtherSelectionsAndDoNotBorrowChatCredentials() {
        ConsenseProperties p=VettingResponsesTransportTest.props();route(p,"responses");LlmProfiles profiles=profiles(p);AiGateway ai=gateway(profiles);
        VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();VettingSemanticReview review=review(ai,p,new VettingResponsesTransport(p,wire));
        VettingLlmOperation captured=review.captureOperation(profiles.resolve("minimax-cn"));
        assertEquals("minimax-cn",captured.getIdentity().getProfileId());assertEquals(VettingResponsesTransport.WIRE,captured.getIdentity().getProvider());
        assertNotEquals(profiles.resolve("minimax-cn").getIdentity().getConfigurationSha256(),captured.getIdentity().getConfigurationSha256());
        route(p,"chat-completions");p.getVetting().getResponses().setApiKey("NEW_FAKE_RESPONSES_CREDENTIAL");
        try(LlmProfiles.Scope ignored=profiles.bind(profiles.resolve("minimax-cn"));VettingSemanticReview.OperationScope routeScope=review.bindOperation(captured)) {
            assertEquals(1,review.review("p",Collections.singletonList(source()),"en",(n,s)->{}).getActualGatewayCallCount());
        }
        assertTrue(wire.bodies.stream().allMatch(x->"none".equals(JsonUtils.parse(x).path("reasoning").path("effort").asText())));
        assertFalse(JsonUtils.write(captured.getIdentity()).contains("FAKE"));
    }
    @Test void missingDedicatedCredentialCannotUseChatCredentialOrLocalObserver() {
        ConsenseProperties p=VettingResponsesTransportTest.props();route(p,"responses");p.getVetting().getResponses().setApiKey("");LlmProfiles profiles=profiles(p);
        VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();VettingSemanticReview review=review(gateway(profiles),p,new VettingResponsesTransport(p,wire));
        review.setInputBudgetObserver(x->{throw new AssertionError("local counter cannot authorize native wire");});
        try(LlmProfiles.Scope ignored=profiles.bind(profiles.resolve("minimax-cn"))) {
            VettingSemanticReview.Result result=review.review("p",Collections.singletonList(source()),"en",(n,s)->{});
            assertEquals(0,result.getActualGatewayCallCount());assertTrue(wire.endpoints.isEmpty());assertEquals("budget_unknown",result.getTopicAudits().get(0).getPacketAudits().get(0).getInputBudgetStatus());
        }
    }
    @Test void publicProfileOrInputDriftCannotGenerateAfterCount() {
        for(int variant=0;variant<3;variant++) {
            ConsenseProperties p=VettingResponsesTransportTest.props();VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();
            VettingResponsesTransport.Operation operation=new VettingResponsesTransport(p,wire).capture("minimax-cn","none");
            VettingInputBudget.Input in=operation.input("Synthetic instruction unchanged.","Native cells ['', 'Room | East 😀 中文']",VettingResponsesTransportTest.schema(),VettingCorpus.hash("synthetic source"));
            VettingInputBudget.Result budget=VettingInputBudget.check(in,operation::observe);
            if(variant==0)p.getVetting().getResponses().setSafetyMarginTokens(501);
            if(variant==1)in.setSourceSnapshotSha256(VettingCorpus.hash("changed source"));
            if(variant==2)in.getMessages().set(1,LlmClient.ChatTurn.user("changed user"));
            assertThrows(Exception.class,()->operation.complete(in,budget,"Synthetic instruction unchanged.","Native cells ['', 'Room | East 😀 中文']",VettingResponsesTransportTest.schema(),Map.class,new ArrayList<>(),null));assertEquals(1,wire.endpoints.size());
        }
    }
    @Test void unknownOverOrMalformedCounterKeepsPacketPendingAndDoesNotGenerate() {
        for(int variant=0;variant<3;variant++) {
            ConsenseProperties p=VettingResponsesTransportTest.props();route(p,"responses");LlmProfiles profiles=profiles(p);
            VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();
            if(variant==0)p.getVetting().getResponses().setEstimatePolicy(null);
            if(variant==1)wire.count=262144;
            if(variant==2)wire.counterOverride="{\"object\":\"response.input_tokens\",\"input_tokens\":100.5}";
            VettingSemanticReview review=review(gateway(profiles),p,new VettingResponsesTransport(p,wire));
            try(LlmProfiles.Scope ignored=profiles.bind(profiles.resolve("minimax-cn"))) {
                VettingSemanticReview.Result result=review.review("p",Collections.singletonList(source()),"en",(n,s)->{});
                assertEquals(0,result.getActualGatewayCallCount());assertTrue(result.getReviewedChunkIds().isEmpty());assertTrue(wire.endpoints.stream().noneMatch(x->x.endsWith("/v1/responses")));
                assertEquals(variant==1?"over_budget":"budget_unknown",result.getTopicAudits().get(0).getPacketAudits().get(0).getInputBudgetStatus());
            }
        }
    }
    @Test void explicitLocalAndDefaultMinimaxKeepExistingChatWire() {
        ConsenseProperties p=VettingResponsesTransportTest.props();LlmProfiles profiles=profiles(p);VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();
        VettingSemanticReview review=review(gateway(profiles),p,new VettingResponsesTransport(p,wire));
        assertFalse(review.captureOperation(profiles.resolve("local")).isResponses());
        assertFalse(review.captureOperation(profiles.resolve("minimax-cn")).isResponses());
        route(p,"responses");assertFalse(review.captureOperation(profiles.resolve("local")).isResponses());assertTrue(wire.endpoints.isEmpty());
    }
    @Test void unsupportedEndpointWireOrThinkingModeAndChangedReserveAreRejectedBeforeHttp() {
        for(int variant=0;variant<4;variant++) {
            ConsenseProperties p=VettingResponsesTransportTest.props();route(p,"responses");
            if(variant==0)p.getVetting().getResponses().setBaseUrl("http://localhost:8092");
            if(variant==1)route(p,"unrecognized");
            if(variant==2)p.getMinimaxCn().setStructuredOpenAiThinkingMode(null);
            if(variant==3)p.getMinimaxCn().setStructuredMaxTokens(2048);
            LlmProfiles profiles=profiles(p);VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();
            VettingSemanticReview review=review(gateway(profiles),p,new VettingResponsesTransport(p,wire));
            assertThrows(Exception.class,()->review.captureOperation(profiles.resolve("minimax-cn")));assertTrue(wire.endpoints.isEmpty());
        }
    }
    @Test void disabledSemanticReviewDoesNotValidateOrDispatchUnusedNativeConfiguration() {
        ConsenseProperties p=VettingResponsesTransportTest.props();route(p,"responses");p.getVetting().setSemanticEnabled(false);
        p.getVetting().getResponses().setBaseUrl("http://unused-invalid-endpoint");LlmProfiles profiles=profiles(p);AiGateway ai=spy(gateway(profiles));
        VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http();VettingSemanticReview review=review(ai,p,new VettingResponsesTransport(p,wire));
        try(LlmProfiles.Scope ignored=profiles.bind(profiles.resolve("minimax-cn"))) {
            VettingSemanticReview.Result result=review.review("p",Collections.singletonList(source()),"en",(n,s)->{});
            assertEquals("deterministic rules",result.getModel());assertEquals(0,result.getActualGatewayCallCount());assertTrue(wire.endpoints.isEmpty());
            verify(ai,never()).requireAvailable();
        }
    }
}
