package com.consense.ai;

import com.consense.common.BizException;
import com.consense.config.ConsenseProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual HTTP adapter contract, with no model pull or inference calls. */
class EmbeddingModelAvailabilityTest {
    @Test void reachableChatServerWithoutConfiguredEmbeddingDoesNotClaimEmbeddingReady() throws Exception {
        try(TagsServer server=new TagsServer("{\"models\":[{\"name\":\"qwen2.5:7b\"}]}")) {
            AiGateway gateway=server.gateway("bge-m3");
            assertTrue(gateway.available());
            assertFalse(gateway.embeddingAvailable(),"Chat availability is not embedding-model availability");
            BizException missing=assertThrows(BizException.class,()->gateway.embed(Collections.singletonList("source passage")));
            assertTrue(missing.getMessage().contains("bge-m3"));
            assertEquals(0,server.nonTagRequests.get(),"Health check must not issue inference or model-pull requests");
        }
    }

    @Test void configuredModelAndTagMustMatchExactlyWithOnlyLatestAsAnOmittedTag() throws Exception {
        try(TagsServer server=new TagsServer("{\"models\":[{\"name\":\"bge-m3-other:latest\"}]}")) {
            assertFalse(server.gateway("bge-m3").embeddingAvailable());
            server.body.set("{\"models\":[{\"name\":\"bge-m3:latest\"}]}");
            assertTrue(server.gateway("bge-m3").embeddingAvailable());
            assertFalse(server.gateway("bge-m3:experimental").embeddingAvailable());
            server.body.set("{\"models\":[{\"model\":\"bge-m3:experimental\"}]}");
            assertTrue(server.gateway("bge-m3:experimental").embeddingAvailable());
        }
    }

    @Test void unreachableMalformedAndEmptyModelInventoriesRemainUnavailable() throws Exception {
        try(TagsServer server=new TagsServer("{\"models\":[]}")) {
            assertFalse(server.gateway("bge-m3").embeddingAvailable());
            server.body.set("{\"models\":{\"name\":\"bge-m3\"}}");
            assertFalse(server.gateway("bge-m3").embeddingAvailable());
            server.httpStatus=503;
            assertFalse(server.gateway("bge-m3").embeddingAvailable());
        }
    }

    @Test void legacyAdaptersAndExistingMockFixturesKeepTheirAvailabilityContract() {
        LlmClient legacy=mock(LlmClient.class);when(legacy.available()).thenReturn(true);
        assertTrue(new AiGateway(legacy).embeddingAvailable());
        when(legacy.available()).thenReturn(false);
        assertFalse(new AiGateway(legacy).embeddingAvailable());
    }

    private static final class TagsServer implements AutoCloseable {
        private final HttpServer server;
        final AtomicReference<String> body;
        final AtomicInteger nonTagRequests=new AtomicInteger();
        volatile int httpStatus=200;
        TagsServer(String response) throws Exception {
            body=new AtomicReference<>(response);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                boolean tags=exchange.getRequestURI().getPath().equals("/api/tags")&&exchange.getRequestMethod().equals("GET");
                if(!tags)nonTagRequests.incrementAndGet();
                byte[] content=(tags?body.get():"unexpected non-tag request").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(tags?httpStatus:404,content.length);
                try(java.io.OutputStream out=exchange.getResponseBody()){out.write(content);}
            });
            server.start();
        }
        AiGateway gateway(String model) {
            ConsenseProperties.Llm cfg=new ConsenseProperties.Llm();
            cfg.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());cfg.setChatModel("qwen2.5:7b");
            cfg.setEmbedModel(model);cfg.setMaxRetry(0);
            return new AiGateway(new OllamaLlmClient(cfg,new HttpSupport()));
        }
        @Override public void close(){server.stop(0);}
    }
}
