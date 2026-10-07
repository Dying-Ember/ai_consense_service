package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VettingServiceProbeTest {
    @TempDir Path root;
    private final String run="a0c544cd-372a-4442-aabb-901732ca040e",project="fixture-project";

    @Test void defaultOffDoesNotEvaluateOrCreateArtifacts() {
        ConsenseProperties props=new ConsenseProperties();AtomicInteger evaluated=new AtomicInteger();
        VettingServiceProbe probe=VettingServiceProbe.start(props,run,project,"fixture");
        probe.observe("must_not_run",()->{evaluated.incrementAndGet();throw new AssertionError("Disabled observer built a snapshot");});
        probe.finish("completed",null);
        assertFalse(probe.enabled());assertEquals(0,evaluated.get());assertEquals(0,probe.overheadNanos());assertEquals(0,probe.observerFailureCount());
    }
    @Test void independentOperationDoesNotClaimSemanticRunDirectory() throws Exception {
        ConsenseProperties props=props();props.getVetting().setProbeDirectory(root.toString());
        VettingServiceProbe service=VettingServiceProbe.start(props,run,project,"fixture");
        VettingReviewProbe semantic=VettingReviewProbe.start(props,run,project);
        service.observe("raw_candidate",()->VettingServiceProbe.map("candidate","original"));service.finish("completed",null);
        assertTrue(semantic.enabled());assertTrue(Files.isDirectory(root.resolve(run)));
        List<Path> operations=operations();assertEquals(1,operations.size());assertNotEquals(root.resolve(run),operations.get(0));
        JsonNode event=event(operations.get(0),"raw_candidate");
        assertEquals(run,event.path("runId").asText());assertEquals(project,event.path("projectId").asText());
        assertTrue(event.path("operationId").asText().startsWith("service-"));
    }
    @Test void snapshotsFreezeMutableDataAndBindExactEventBytes() throws Exception {
        VettingServiceProbe probe=VettingServiceProbe.start(props(),run,project,"fixture");
        Map<String,Object> mutable=new LinkedHashMap<>();mutable.put("title","Before normalization");
        probe.observe("raw_candidate",()->mutable);mutable.put("title","After normalization");
        probe.finish("completed",null);Path operation=operations().get(0);
        assertEquals("Before normalization",event(operation,"raw_candidate").path("observations").path("title").asText());
        Map<String,Object> ref=probe.reference("raw_candidate");byte[] bytes=Files.readAllBytes(operation.resolve((String)ref.get("relativePath")));
        assertEquals(com.consense.document.DocumentParseProbe.sha256(bytes),ref.get("sha256"));assertEquals(bytes.length,ref.get("bytes"));
    }
    @Test void observerInitializationFailureCannotReplaceOriginalThrowable() throws Exception {
        Path file=root.resolve("not-a-directory");Files.write(file,new byte[]{1});ConsenseProperties props=new ConsenseProperties();props.getVetting().setServiceProbeDirectory(file.toString());
        VettingServiceProbe probe=assertDoesNotThrow(()->VettingServiceProbe.start(props,run,project,"fixture"));
        RuntimeException original=new RuntimeException("business",new IllegalArgumentException("original cause"));
        IllegalStateException originalSuppressed=new IllegalStateException("prior suppressed");original.addSuppressed(originalSuppressed);
        assertDoesNotThrow(()->probe.originalFailure("original_failure",original));assertDoesNotThrow(()->probe.finish("aborted",original));
        assertEquals("original cause",original.getCause().getMessage());assertSame(originalSuppressed,original.getSuppressed()[0]);
        assertEquals(2,original.getSuppressed().length);assertTrue(probe.observerFailureCount()>0);
    }
    @Test void snapshotFailureIsIsolatedAndCauseSuppressedEvidenceIsComplete() throws Exception {
        VettingServiceProbe probe=VettingServiceProbe.start(props(),run,project,"fixture");
        assertDoesNotThrow(()->probe.observe("bad_snapshot",()->{throw new AssertionError("snapshot-only failure");}));
        RuntimeException original=new RuntimeException("actual business exception",new java.io.IOException("actual cause"));
        original.addSuppressed(new IllegalArgumentException("actual suppressed"));probe.originalFailure("original_failure",original);
        probe.finish("aborted",original);JsonNode tree=event(operations().get(0),"original_failure").path("observations").path("originalFailure");
        assertEquals("actual business exception",tree.path("message").asText());assertEquals("actual cause",tree.path("cause").path("message").asText());
        assertEquals("actual suppressed",tree.path("suppressed").get(0).path("message").asText());
        assertEquals("snapshot-only failure",tree.path("suppressed").get(1).path("cause").path("message").asText());
        assertEquals(1,JsonUtils.parse(Files.readString(operations().get(0).resolve("service_probe_manifest.json"))).path("observerFailureCount").asInt());
    }
    @Test void timingSeparatesProbeOverheadAndNeverClaimsSemanticAcceptance() throws Exception {
        VettingServiceProbe probe=VettingServiceProbe.start(props(),run,project,"fixture");long start=System.nanoTime(),before=probe.overheadNanos();
        probe.observe("nested_snapshot",()->VettingServiceProbe.map("value",17));
        probe.elapsed("fixture_transaction",start,before,"Measured original fixture only",true);probe.finish("completed",null);
        JsonNode timing=event(operations().get(0),"phase_timing").path("observations");
        assertTrue(timing.path("completed").asBoolean());assertTrue(timing.path("wallNanos").asLong()>=0);
        assertEquals(timing.path("inclusiveWallNanos").asLong(),timing.path("wallNanos").asLong()+timing.path("probeOverheadNanos").asLong());
        JsonNode summary=JsonUtils.parse(Files.readString(operations().get(0).resolve("service_probe_manifest.json"),StandardCharsets.UTF_8));
        assertEquals("completed",summary.path("status").asText());assertFalse(summary.path("semanticQualityAccepted").asBoolean());
    }
    private ConsenseProperties props(){ConsenseProperties props=new ConsenseProperties();props.getVetting().setServiceProbeDirectory(root.toString());return props;}
    private List<Path> operations() throws Exception{try(java.util.stream.Stream<Path> paths=Files.list(root)){return paths.filter(p->p.getFileName().toString().startsWith("service-")).collect(java.util.stream.Collectors.toList());}}
    private static JsonNode event(Path dir,String phase) throws Exception{try(java.util.stream.Stream<Path> files=Files.list(dir)){Path p=files.filter(x->x.getFileName().toString().matches("\\d{5}-"+phase+"\\.json")).findFirst().orElseThrow(AssertionError::new);return JsonUtils.parse(Files.readString(p,StandardCharsets.UTF_8));}}
}
