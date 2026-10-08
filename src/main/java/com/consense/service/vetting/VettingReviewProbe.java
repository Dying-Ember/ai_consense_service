package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Optional file-only observation of an explicitly identified review run. */
final class VettingReviewProbe {
    static final class Failure extends IllegalStateException {
        Failure(String message, Throwable cause) { super(message,cause); }
    }
    private final Path directory;
    private final String runId, projectId;
    private int sequence;
    private long eventSerializationNanos, textEncodingAndHashNanos, artifactWriteNanos, eventObserverNanos;
    private int observerFailures;
    private final Map<String,Map<String,Object>> stageTotals = new LinkedHashMap<>();
    private boolean finished;
    private VettingReviewProbe(Path directory, String runId, String projectId) {
        this.directory = directory; this.runId = runId; this.projectId = projectId;
    }
    static VettingReviewProbe start(ConsenseProperties props, String runId, String projectId) {
        String configured = props.getVetting().getProbeDirectory();
        if (JsonUtils.isBlankText(configured)) return new VettingReviewProbe(null, runId, projectId);
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}"))
            throw new IllegalArgumentException("Review probe requires an explicit safe review run ID");
        Path root = Paths.get(configured).toAbsolutePath().normalize();
        Path target = root.resolve(runId).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Review probe path escaped configured directory");
        try { Files.createDirectories(root); Files.createDirectory(target); }
        catch (IOException e) { throw new Failure("Cannot create fresh review probe directory", e); }
        VettingReviewProbe probe = new VettingReviewProbe(target, runId, projectId);
        probe.call(0, null, null).event("run_started", map("strictHybrid", props.getVetting().isRequireHybridRetrieval(),
                "expectedRetrievalSignature", props.getVetting().getExpectedRetrievalSignature(),
                "parameters", props.getVetting(), "boundary", "Observations do not certify source semantics or full clause scope"));
        return probe;
    }
    boolean enabled() { return directory != null; }
    Call call(int topicIndex, String topic, String role) { return new Call(this, topicIndex, topic, role); }
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) out.put((String)pairs[i],pairs[i+1]);
        return out;
    }
    private synchronized void write(Call call, String phase, Object observations, String text) {
        if (!enabled()) return;
        long observerStarted = System.nanoTime();
        String number=String.format(Locale.ROOT, "%05d", ++sequence);
        String name=number+"-"+phase;
        Map<String,Object> record=map("schemaVersion",1,"runId",runId,"projectId",projectId,
                "callId",runId+"/"+call.topicIndex+"/"+(call.role==null?"context":call.role),
                "topicIndex",call.topicIndex,"topic",call.topic,"role",call.role,
                "phase",phase,"observedAt",Instant.now().toString(),"observations",observations);
        try {
            if (text!=null) {
                long encodingStarted = System.nanoTime();
                byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
                String hash=VettingCorpus.hash(text);
                textEncodingAndHashNanos += System.nanoTime()-encodingStarted;
                String file=name+".txt";
                long writeStarted=System.nanoTime();
                try { Files.write(directory.resolve(file),bytes,StandardOpenOption.CREATE_NEW); }
                finally { artifactWriteNanos += System.nanoTime()-writeStarted; }
                record.put("textArtifact",map("relativePath",file,"bytes",bytes.length,"sha256",hash,
                        "encoding","UTF-8 encoding of the exact string returned to or supplied by the Java caller; TCP bytes not independently observed"));
            }
            long serializationStarted=System.nanoTime();
            byte[] encoded;
            try { encoded=JsonUtils.write(record).getBytes(StandardCharsets.UTF_8); }
            finally { eventSerializationNanos += System.nanoTime()-serializationStarted; }
            long writeStarted=System.nanoTime();
            try { Files.write(directory.resolve(name+".json"),encoded,StandardOpenOption.CREATE_NEW); }
            finally { artifactWriteNanos += System.nanoTime()-writeStarted; }
        } catch(IOException | RuntimeException e) { observerFailures++; throw new Failure("Review probe evidence could not be saved", e); }
        finally { eventObserverNanos += System.nanoTime()-observerStarted; }
    }
    private synchronized long overheadNanos() { return eventObserverNanos; }
    private synchronized void timing(Call call,String stage,long inclusiveNanos,long overheadNanos,String boundary) {
        long wallNanos=Math.max(0,inclusiveNanos-overheadNanos);
        if(enabled()) {
            Map<String,Object> totals=stageTotals.computeIfAbsent(stage,key->map("count",0L,"wallNanos",0L,
                    "inclusiveWallNanos",0L,"probeOverheadDuringPhaseNanos",0L));
            totals.put("count",((Long)totals.get("count"))+1);
            totals.put("wallNanos",((Long)totals.get("wallNanos"))+wallNanos);
            totals.put("inclusiveWallNanos",((Long)totals.get("inclusiveWallNanos"))+inclusiveNanos);
            totals.put("probeOverheadDuringPhaseNanos",((Long)totals.get("probeOverheadDuringPhaseNanos"))+overheadNanos);
        }
        write(call,"phase_timing",map("stage",stage,"wallNanos",wallNanos,"seconds",wallNanos/1_000_000_000.0,
                "inclusiveWallNanos",inclusiveNanos,"probeOverheadDuringPhaseNanos",overheadNanos,
                "boundary",boundary,"clock","System.nanoTime monotonic elapsed; nested stages must not be summed"),null);
    }
    /** Cumulative event I/O costs; this summary's own encode/write cannot be self-measured inside its bytes. */
    synchronized void finish(String status) {
        if(!enabled() || finished) return;
        finished=true;
        Map<String,Object> summary=map("schemaVersion",1,"runId",runId,"projectId",projectId,"status",status,
                "observedAt",Instant.now().toString(),"eventCount",sequence,"observerFailureCount",observerFailures,
                "eventSerializationWallNanos",eventSerializationNanos,"textEncodingAndHashWallNanos",textEncodingAndHashNanos,
                "artifactWriteWallNanos",artifactWriteNanos,"totalEventObserverWallNanos",eventObserverNanos,
                "stageTimingAggregates",stageTotals,"summarySelfSerializationAndWriteIncluded",false,
                "boundary","All preceding probe event/text serialization and writes are counted; this final summary's own flush is excluded. Business timing excludes measured probe event overhead; nested business stages are not additive.");
        try { Files.write(directory.resolve("probe_overhead_manifest.json"),JsonUtils.write(summary).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW); }
        catch(IOException | RuntimeException e) { throw new Failure("Review probe overhead summary could not be saved",e); }
    }
    static final class Timer implements AutoCloseable {
        private final Call call;
        private final String stage,boundary;
        private final long started,observerStarted;
        private boolean closed;
        Timer(Call call,String stage,String boundary) {
            this.call=call;this.stage=stage;this.boundary=boundary;
            this.observerStarted=call.probe.overheadNanos();this.started=System.nanoTime();
        }
        @Override public void close() {
            if(closed)return;closed=true;
            long inclusive=System.nanoTime()-started;
            call.probe.timing(call,stage,inclusive,call.probe.overheadNanos()-observerStarted,boundary);
        }
    }
    static final class Call {
        private final VettingReviewProbe probe;
        final int topicIndex; final String topic, role;
        Call(VettingReviewProbe probe,int topicIndex,String topic,String role) {
            this.probe=probe; this.topicIndex=topicIndex; this.topic=topic; this.role=role;
        }
        boolean enabled() { return probe.enabled(); }
        Timer measure(String stage,String boundary) { return new Timer(this,stage,boundary); }
        void event(String phase,Object observations) { probe.write(this,phase,observations,null); }
        void text(String phase,Object observations,String raw) { probe.write(this,phase,observations,raw); }
    }
}
