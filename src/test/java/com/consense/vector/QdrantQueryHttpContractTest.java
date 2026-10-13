package com.consense.vector;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP boundary; no Qdrant process, external request or model. */
class QdrantQueryHttpContractTest {
    @Test void scopedQueryUsesPointsApiAndNestedHitsWhileKeepingAllowlistBeforeTopK() throws Exception {
        try (QueryServer server = new QueryServer()) {
            List<VectorStore.SearchHit> hits = server.store.search("PROJECT-A",
                    new HashSet<>(Arrays.asList("DOC-B", "DOC-A")), new float[]{1f, .25f}, 3, .4);
            assertEquals("/collections/query-contract/points/query", server.postPath.get());
            JsonNode request = JsonUtils.parse(server.postBody.get());
            assertFalse(request.has("vector"));
            assertEquals(2, request.path("query").size());
            assertEquals(.25, request.path("query").get(1).asDouble(), .000001);
            assertEquals(3, request.path("limit").asInt());
            assertTrue(request.path("with_payload").asBoolean());
            assertEquals(.4, request.path("score_threshold").asDouble(), .000001);
            JsonNode must = request.path("filter").path("must");
            assertEquals(2, must.size());
            assertEquals("projectId", must.get(0).path("key").asText());
            assertEquals("PROJECT-A", must.get(0).path("match").path("value").asText());
            assertEquals("documentId", must.get(1).path("key").asText());
            assertEquals("DOC-A", must.get(1).path("match").path("any").get(0).asText());
            assertEquals("DOC-B", must.get(1).path("match").path("any").get(1).asText());
            assertEquals(1, hits.size());
            assertEquals("HIT-1", hits.get(0).getId());
            assertEquals(.875, hits.get(0).getScore(), .000001);
            assertEquals("literal clause", hits.get(0).text());
            assertEquals("DOC-A", hits.get(0).getPayload().get("documentId"));
            assertEquals(2, server.requests.get(), "One existing collection GET, one query POST");
        }
    }

    @Test void projectOnlyOverloadUsesSameQueryProtocolWithoutDocumentFilter() throws Exception {
        try (QueryServer server = new QueryServer()) {
            assertEquals("HIT-1", server.store.search("PROJECT-A", new float[]{1f}, 1, 0).get(0).getId());
            assertEquals("/collections/query-contract/points/query", server.postPath.get());
            JsonNode body = JsonUtils.parse(server.postBody.get());
            assertTrue(body.has("query"));
            assertEquals(1, body.path("filter").path("must").size());
        }
    }

    @Test void emptyDocumentAllowlistReturnsWithoutEvenCollectionHttp() throws Exception {
        try (QueryServer server = new QueryServer()) {
            assertTrue(server.store.search("PROJECT-A", Collections.emptySet(), new float[]{1f}, 1, 0).isEmpty());
            assertEquals(0, server.requests.get());
            assertNull(server.postBody.get());
        }
    }

    @Test void validEmptyNestedPointsResponseReturnsNoHits() throws Exception {
        try (QueryServer server = new QueryServer()) {
            server.response.set("{\"result\":{\"points\":[]},\"status\":\"ok\"}");
            assertTrue(server.store.search("PROJECT-A", Collections.singleton("DOC-A"), new float[]{1f}, 1, 0).isEmpty());
            assertEquals("/collections/query-contract/points/query", server.postPath.get());
        }
    }

    private static final class QueryServer implements AutoCloseable {
        final HttpServer server;
        final QdrantVectorStore store;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> postPath = new AtomicReference<>();
        final AtomicReference<String> postBody = new AtomicReference<>();
        final AtomicReference<String> response = new AtomicReference<>(
                "{\"result\":{\"points\":[{\"id\":\"HIT-1\",\"score\":0.875,\"payload\":{\"content\":\"literal clause\",\"documentId\":\"DOC-A\"}}]},\"status\":\"ok\"}");
        QueryServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                boolean post = "POST".equals(exchange.getRequestMethod());
                if (post) {
                    postPath.set(exchange.getRequestURI().getPath());
                    postBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                }
                byte[] body = (post ? response.get() : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            ConsenseProperties.VectorCfg config = new ConsenseProperties.VectorCfg();
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setCollection("query-contract");
            config.setTimeoutMs(2000);
            store = new QdrantVectorStore(config, new HttpSupport());
        }
        @Override public void close() { server.stop(0); }
    }
}
