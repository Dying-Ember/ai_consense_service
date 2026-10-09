package com.consense.ai;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP boundary with synthetic credentials; no provider calls or business workflows. */
class MiniMaxOverloadHttpTest {
    private static final String KEY="SYNTHETIC_MINIMAX_TEST_KEY";
    private static final String OVERLOAD="{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"当前服务集群负载较高 (2064)\",\"http_code\":529},\"request_id\":\"test-request-id\"}";
    private static final String SUCCESS="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}]}";
    private HttpServer server;
    private final List<String> bodies=new ArrayList<>(),auth=new ArrayList<>();
    private final AtomicInteger count=new AtomicInteger();
    private final Map<String,String> responseHeaders=new LinkedHashMap<>();
    private int[] statuses;
    private String[] responses;
    private FastHttp http;
    private ConsenseProperties.Llm cfg;
    private boolean closeBeforeResponse,holdResponse;
    private final CountDownLatch releaseResponse=new CountDownLatch(1);

    private static class FastHttp extends HttpSupport {
        final List<Long> delays=new ArrayList<>();
        boolean interrupt;
        // Virtual delay seam keeps this real-HTTP test deterministic and fast.
        @Override protected void pauseOverloadRetry(long millis)throws InterruptedException {
            delays.add(millis);if(interrupt)throw new InterruptedException("test interruption");
        }
    }
    @BeforeEach void setup()throws IOException {
        statuses=new int[]{200};responses=new String[]{SUCCESS};http=new FastHttp();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            int index=count.getAndIncrement();
            bodies.add(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if(closeBeforeResponse){exchange.close();return;}
            if(holdResponse)try{releaseResponse.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            int status=statuses[Math.min(index,statuses.length-1)];
            byte[] body=responses[Math.min(index,responses.length-1)].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            responseHeaders.forEach((name,value)->exchange.getResponseHeaders().add(name,value));
            exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();cfg=new ConsenseProperties().getMinimaxCn();
        cfg.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());cfg.setApiKey(KEY);cfg.setTimeoutMs(1000);
        cfg.setMaxRetry(10);
    }
    private static byte[] readAll(java.io.InputStream input)throws IOException {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int read;
        while((read=input.read(buffer))!=-1)out.write(buffer,0,read);return out.toByteArray();
    }
    @AfterEach void close(){releaseResponse.countDown();server.stop(0);Thread.interrupted();}
    private List<LlmClient.ChatTurn> turns(){return Arrays.asList(LlmClient.ChatTurn.system("unchanged schema instruction"),LlmClient.ChatTurn.user("unchanged source"));}
    private String structured(){return new MiniMaxChatClient(cfg,http).chatStructured(turns(),JsonUtils.parse("{\"type\":\"array\"}"));}

    @Test void twoExplicitOverloadsThenSuccessKeepTheExactRequestAndUseTwoBoundedDelays() {
        statuses=new int[]{529,529,200};responses=new String[]{OVERLOAD,OVERLOAD,SUCCESS};
        assertEquals("[]",structured());assertEquals(3,count.get());assertEquals(Arrays.asList(2000L,5000L),http.delays);
        assertEquals(1,new HashSet<>(bodies).size());assertEquals(Collections.singleton("Bearer "+KEY),new HashSet<>(auth));
        assertEquals("MiniMax-M3",JsonUtils.parse(bodies.get(0)).path("model").asText());
        assertEquals(16384,JsonUtils.parse(bodies.get(0)).path("max_tokens").asInt());
    }
    @Test void ordinaryMiniMaxChatUsesTheSameBoundedTransportPolicy() {
        statuses=new int[]{529,200};responses=new String[]{OVERLOAD,SUCCESS};
        assertEquals("[]",new MiniMaxChatClient(cfg,http).chat(turns()));assertEquals(2,count.get());assertEquals(Collections.singletonList(2000L),http.delays);
    }

    @Test void relayModeReturnsTheFinal529WithoutStartingAnotherRelayRequest() {
        cfg.setOverloadRetryEnabled(false);statuses=new int[]{529,200};responses=new String[]{OVERLOAD,SUCCESS};
        assertThrows(BizException.class,this::structured);
        assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
        assertEquals("Bearer "+KEY,auth.get(0));
        JsonNode request=JsonUtils.parse(bodies.get(0));
        assertEquals("MiniMax-M3",request.path("model").asText());assertFalse(request.path("stream").asBoolean());
        assertEquals(16384,request.path("max_tokens").asInt());
    }

    @Test void relayModeStillBlocksImplicit503RetryAfterAndRedirectReplays() {
        cfg.setOverloadRetryEnabled(false);statuses=new int[]{503};responses=new String[]{OVERLOAD};responseHeaders.put("Retry-After","0");
        assertThrows(BizException.class,this::structured);assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
        count.set(0);statuses=new int[]{307};responseHeaders.clear();responseHeaders.put("Location",cfg.getBaseUrl()+"/v1/chat/completions");
        assertThrows(BizException.class,this::structured);assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
    }

    @Test void relayModeReturnsAValidCompletedAnswerWithoutChangingTheSemanticGate() {
        cfg.setOverloadRetryEnabled(false);
        assertEquals("[]",structured());assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
    }
    @Test void thirdOverloadStaysAFailureAndCannotLeakTheCredential() {
        statuses=new int[]{529};responses=new String[]{OVERLOAD.replace("test-request-id",KEY)};
        BizException failed=assertThrows(BizException.class,this::structured);
        assertEquals(3,count.get());assertEquals(Arrays.asList(2000L,5000L),http.delays);
        assertTrue(failed.getMessage().contains("HTTP 529"));assertFalse(failed.getMessage().contains(KEY));
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,408,421,429,500,502,503,504})
    void otherHttpStatusesNeverRetryEvenWithAnOverloadBody(int status) {
        statuses=new int[]{status};responses=new String[]{OVERLOAD};assertThrows(BizException.class,this::structured);
        assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
    }
    @ParameterizedTest @ValueSource(ints={401,429,500})
    void aDifferentFailureAfterOverloadStopsWithoutAThirdRequest(int status) {
        statuses=new int[]{529,status};responses=new String[]{OVERLOAD,OVERLOAD};assertThrows(BizException.class,this::structured);
        assertEquals(2,count.get());assertEquals(Collections.singletonList(2000L),http.delays);
    }
    @Test void malformedAmbiguousAndNonOverload529BodiesNeverRetry() {
        String[] invalid={"not json","{}","[]","{\"error\":{\"type\":null}}","{\"error\":{\"type\":529}}","{\"error\":{\"type\":\"rate_limit_error\"}}",OVERLOAD+"{}",OVERLOAD+" trailing",OVERLOAD.replace("\"type\":\"overloaded_error\"","\"type\":\"other\",\"type\":\"overloaded_error\"")};
        statuses=new int[]{529};for(String body:invalid){count.set(0);responses=new String[]{body};assertThrows(BizException.class,this::structured,body);assertEquals(1,count.get(),body);assertTrue(http.delays.isEmpty(),body);}
    }
    @Test void successfulHttpProviderErrorsAndIncompleteOrMalformedAnswersNeverRetry() {
        for(String body:Arrays.asList(OVERLOAD,"{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"[\"}}]}","not json")) {
            count.set(0);responses=new String[]{body};assertThrows(BizException.class,this::structured);assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
        }
    }
    @Test void interruptedBackoffStopsAndRestoresTheThreadInterruptFlag() {
        statuses=new int[]{529};responses=new String[]{OVERLOAD};http.interrupt=true;
        assertThrows(BizException.class,this::structured);assertEquals(1,count.get());assertTrue(Thread.currentThread().isInterrupted());assertEquals(Collections.singletonList(2000L),http.delays);
    }
    @Test void aConnectionClosedWithoutAResponseDoesNotReplayTheRequest() {
        closeBeforeResponse=true;assertThrows(BizException.class,this::structured);
        assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
    }
    @Test void aResponseTimeoutDoesNotReplayTheRequest() {
        holdResponse=true;cfg.setTimeoutMs(100);
        try{assertThrows(BizException.class,this::structured);assertEquals(1,count.get());assertTrue(http.delays.isEmpty());}
        finally{releaseResponse.countDown();}
    }
    @Test void invalidModelContentInACompletedEnvelopeIsReturnedOnceForTheExistingSemanticGate() {
        responses=new String[]{SUCCESS.replace("[]","not valid JSON")};
        assertEquals("not valid JSON",structured());assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
    }
    @Test void a503RetryAfterZeroCannotTriggerAnImplicitOkHttpReplay() {
        statuses=new int[]{503};responses=new String[]{OVERLOAD};responseHeaders.put("Retry-After","0");
        assertThrows(BizException.class,this::structured);assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
    }
    @ParameterizedTest @ValueSource(ints={301,302,303,307,308})
    void redirectsCannotResubmitOrChangeTheConfiguredProviderRequest(int status) {
        statuses=new int[]{status};responses=new String[]{OVERLOAD};responseHeaders.put("Location",cfg.getBaseUrl()+"/v1/chat/completions");
        assertThrows(BizException.class,this::structured);assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
    }
    @Test void a408RetryAfterZeroDoesNotResubmitAnUncertainGeneration() {
        statuses=new int[]{408};responses=new String[]{OVERLOAD};responseHeaders.put("Retry-After","0");
        assertThrows(BizException.class,this::structured);assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
    }
    @Test void genericOpenAiAndSharedHttpKeepTheirExistingNoHttpStatusRetry() {
        statuses=new int[]{529};responses=new String[]{OVERLOAD};
        assertThrows(BizException.class,()->new OpenAiLlmClient(cfg,http).chatStructured(turns(),JsonUtils.mapper().createObjectNode()));
        assertEquals(1,count.get());assertTrue(http.delays.isEmpty());count.set(0);
        assertThrows(BizException.class,()->http.postJson(cfg.getBaseUrl()+"/v1/chat/completions","{}",1000,null,10));
        assertEquals(1,count.get());assertTrue(http.delays.isEmpty());
    }
}
