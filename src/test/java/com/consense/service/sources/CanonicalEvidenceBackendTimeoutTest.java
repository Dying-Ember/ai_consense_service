package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real HTTP reader: a configured deadline must replace OkHttp's default 10s read limit. */
class CanonicalEvidenceBackendTimeoutTest {
    private HttpServer server;
    private ExecutorService workers;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    @Test void configured_deadline_allows_a_snapshot_that_takes_longer_than_default_read_timeout() throws Exception {
        byte[] bytes = "{\"literal\":\"原件 😀\",\"unknown\":null}".getBytes(StandardCharsets.UTF_8);
        CanonicalEvidenceBackend backend = backend(11000, 25000, bytes);
        String projection = String.join("", Collections.nCopies(64, "a"));
        assertEquals(new String(bytes, StandardCharsets.UTF_8), backend.snapshot(projection, "advisory"));
    }

    @Test void configured_short_deadline_still_rejects_a_slow_response() throws Exception {
        CanonicalEvidenceBackend backend = backend(500, 100, "{}".getBytes(StandardCharsets.UTF_8));
        long start = System.nanoTime();
        CanonicalEvidenceException error = assertThrows(CanonicalEvidenceException.class, backend::health);
        assertEquals("OER_EVIDENCE_UPSTREAM_UNAVAILABLE", error.getMessage());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000, "Configured bound must still end the request");
    }

    private CanonicalEvidenceBackend backend(long delayMs, int timeoutMs, byte[] bytes) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(delayMs);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException closedAfterTimeout) {
                // A deadline may close the client while this intentionally delayed fixture responds.
            } finally { exchange.close(); }
        });
        server.start();
        CanonicalEvidenceProperties config = new CanonicalEvidenceProperties();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); config.setTimeoutMs(timeoutMs);
        return new CanonicalEvidenceBackend(config);
    }
}
