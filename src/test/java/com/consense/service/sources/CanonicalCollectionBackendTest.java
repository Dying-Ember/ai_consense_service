package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real bounded local HTTP transport probes; no CAS/model quality claim. */
class CanonicalCollectionBackendTest {
    HttpServer server;CanonicalEvidenceProperties config;CanonicalEvidenceBackend backend;
    byte[] body="{}".getBytes(StandardCharsets.UTF_8);int status=200;String requested;
    final AtomicInteger calls=new AtomicInteger();final String address=String.join("",Collections.nCopies(64,"a"));
    @BeforeEach void setup() throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{calls.incrementAndGet();requested=exchange.getRequestURI().toString();
            if(status==302)exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/forbidden");
            exchange.sendResponseHeaders(status,body.length);try(OutputStream stream=exchange.getResponseBody()){stream.write(body);}catch(IOException closedAfterSizeRejection){/* Expected for bounded clients. */}
        });server.start();config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());backend=new CanonicalEvidenceBackend(config);
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void fixedBatchRouteUsesRootAndOrdinalWithOneEncodedModuleQuery(){
        assertArrayEquals(body,backend.batch(address,2,"advisory").bytes);assertEquals("/canonical/"+address+"/batches/2?module=advisory",requested);
        assertEquals(1,calls.get());
    }
    @Test void fixedEnvelopeRouteUsesRootAndOrdinalWithOneEncodedModuleQuery(){
        assertArrayEquals(body,backend.envelope(address,2,"advisory").bytes);assertEquals("/canonical/"+address+"/envelopes/2?module=advisory",requested);
        assertEquals(1,calls.get());
    }
    @Test void configurationCannotRaiseWholeBatchCapAboveSixteenMiB(){
        config.setMaxSnapshotBytes(NativeDocumentCollectionContract.MAX_BYTES*4);body=new byte[NativeDocumentCollectionContract.MAX_BYTES+1];
        CanonicalEvidenceException failure=assertThrows(CanonicalEvidenceException.class,()->backend.batch(address,0,"vetting"));assertEquals("OER_EVIDENCE_TOO_LARGE",failure.getMessage());
        CanonicalEvidenceException rootFailure=assertThrows(CanonicalEvidenceException.class,()->backend.snapshot(address,"vetting"));assertEquals("OER_EVIDENCE_TOO_LARGE",rootFailure.getMessage());
        CanonicalEvidenceException envelopeFailure=assertThrows(CanonicalEvidenceException.class,()->backend.envelope(address,0,"vetting"));assertEquals("OER_EVIDENCE_TOO_LARGE",envelopeFailure.getMessage());
    }
    @Test void invalidAddressesNeverReachHttpAndRedirectCannotChooseAnotherEndpoint(){
        assertThrows(CanonicalEvidenceException.class,()->backend.batch("../../sha256/a.blob",0,"vetting"));
        assertThrows(CanonicalEvidenceException.class,()->backend.batch(address,-1,"vetting"));assertEquals(0,calls.get());
        assertThrows(CanonicalEvidenceException.class,()->backend.envelope("../../sha256/a.blob",0,"vetting"));
        assertThrows(CanonicalEvidenceException.class,()->backend.envelope(address,-1,"vetting"));assertEquals(0,calls.get());
        status=302;assertThrows(CanonicalEvidenceException.class,()->backend.batch(address,0,"vetting"));assertEquals(1,calls.get());
    }
}
