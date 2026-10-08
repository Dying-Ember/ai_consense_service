package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParseProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Default-off, non-interfering observation of rules -> final selection -> original persistence. */
@Slf4j
final class VettingServiceProbe {
    private final boolean configured;
    private final String operationId, runId, projectId;
    private Path directory;
    private int sequence;
    private long observerNanos, serializationNanos, writeNanos;
    private boolean finished;
    private final List<Throwable> observerFailures = new ArrayList<>();
    private final Map<String, Map<String,Object>> references = new LinkedHashMap<>();

    private VettingServiceProbe(boolean configured, String operationId, String runId, String projectId) {
        this.configured=configured; this.operationId=operationId; this.runId=runId; this.projectId=projectId;
    }
    static VettingServiceProbe start(ConsenseProperties props, String runId, String projectId, String origin) {
        String root=props.getVetting().getServiceProbeDirectory();
        boolean configured=!JsonUtils.isBlankText(root);
        VettingServiceProbe probe=new VettingServiceProbe(configured,configured?"service-"+UUID.randomUUID():null,runId,projectId);
        if(!configured)return probe;
        long started=System.nanoTime();
        try {
            if(JsonUtils.isBlankText(runId)||JsonUtils.isBlankText(projectId))throw new IllegalArgumentException("Service probe requires the actual persisted run and project IDs");
            Path parent=Paths.get(root).toAbsolutePath().normalize();
            Files.createDirectories(parent);probe.directory=parent.resolve(probe.operationId);Files.createDirectory(probe.directory);
        } catch(Throwable e) { probe.observationFailure("create_directory",e); }
        finally { probe.observerNanos+=System.nanoTime()-started; }
        probe.observe("service_run_bound",()->map("origin",origin,"actualRunId",runId,"actualProjectId",projectId,
                "boundary","This lifecycle operation is independent of SemanticReview, corpus, parse and report probe directories. Observation is not semantic approval."));
        return probe;
    }
    static Map<String,Object> returnedTransaction(Object... pairs){
        Map<String,Object> record=map(pairs);
        boolean outer=TransactionSynchronizationManager.isActualTransactionActive();
        record.put("transactionReturnedNormally",true);record.put("participatingOuterTransaction",outer);
        record.put("committed",outer?null:Boolean.TRUE);
        record.put("commitObservation",outer?"unknown_outer_transaction_still_active":"own_transaction_returned_normally");
        record.put("commitBoundary","Original REQUIRED transaction callback returned. An active outer transaction can still commit or roll back; its outcome is not observed here.");
        return record;
    }
    boolean enabled(){return configured;}
    synchronized long overheadNanos(){return observerNanos;}
    synchronized Map<String,Object> reference(String phase){Map<String,Object> value=references.get(phase);return value==null?null:new LinkedHashMap<>(value);}
    static Map<String,Object> map(Object... pairs){return DocumentParseProbe.map(pairs);}

    /** Values are built only when configured and serialized immediately before mutable business objects advance. */
    synchronized void observe(String phase,Supplier<Object> observations) {
        if(!configured||directory==null)return;
        long started=System.nanoTime();
        try {
            String name=String.format(Locale.ROOT,"%05d-%s.json",++sequence,phase);
            Map<String,Object> record=map("schemaVersion",1,"operationId",operationId,"runId",runId,"projectId",projectId,
                    "phase",phase,"observedAt",Instant.now().toString(),"observations",observations.get());
            long serialization=System.nanoTime();byte[] body;
            try{body=JsonUtils.write(record).getBytes(StandardCharsets.UTF_8);}finally{serializationNanos+=System.nanoTime()-serialization;}
            long io=System.nanoTime();try{Files.write(directory.resolve(name),body,StandardOpenOption.CREATE_NEW);}finally{writeNanos+=System.nanoTime()-io;}
            references.put(phase,map("relativePath",name,"bytes",body.length,"sha256",DocumentParseProbe.sha256(body)));
        } catch(Throwable e) { observationFailure(phase,e); }
        finally { observerNanos+=System.nanoTime()-started; }
    }
    private synchronized void observationFailure(String phase,Throwable e){
        IllegalStateException failure=new IllegalStateException("Service lifecycle observation failed at "+phase,e);
        observerFailures.add(failure);try{log.warn("Lifecycle observer {} for run {} failed at {}; original business operation continues",operationId,runId,phase,e);}catch(Throwable ignored){/* Logging must not replace the original operation. */}
    }
    void elapsed(String phase,long started,long overheadBefore,String boundary,boolean completed){
        long inclusive=System.nanoTime()-started,overhead=overheadNanos()-overheadBefore;
        observe("phase_timing",()->map("stage",phase,"completed",completed,"wallNanos",Math.max(0,inclusive-overhead),
                "inclusiveWallNanos",inclusive,"probeOverheadNanos",overhead,"seconds",Math.max(0,inclusive-overhead)/1_000_000_000.0,
                "boundary",boundary,"clock","System.nanoTime; stages may be nested and are not additive"));
    }
    void originalFailure(String phase,Throwable original){
        if(!configured)return;
        attachObserverFailures(original);
        observe(phase,()->map("originalFailure",failureTree(original),"observerFailureCount",observerFailureCount(),
                "boundary","Actual original throwable, including cause and suppressed failures; the business exception is not replaced by an observer exception."));
        attachObserverFailures(original);
    }
    synchronized int observerFailureCount(){return observerFailures.size();}
    private synchronized void attachObserverFailures(Throwable original){
        if(original==null)return;
        for(Throwable failure:observerFailures)if(failure!=original&&!Arrays.asList(original.getSuppressed()).contains(failure))original.addSuppressed(failure);
    }
    static Object failureTree(Throwable original){return tree(original,Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>()));}
    private static Object tree(Throwable value,Set<Throwable> visited){
        if(value==null)return null;
        if(!visited.add(value))return map("class",value.getClass().getName(),"repeatedReference",true);
        List<Object> suppressed=new ArrayList<>();for(Throwable item:value.getSuppressed())suppressed.add(tree(item,visited));
        return map("class",value.getClass().getName(),"message",value.getMessage(),"stackTrace",Arrays.asList(value.getStackTrace()),
                "cause",tree(value.getCause(),visited),"suppressed",suppressed);
    }
    synchronized void finish(String status,Throwable original){
        if(!configured||finished)return;finished=true;attachObserverFailures(original);
        observe("service_lifecycle_terminal",()->map("status",status,"terminalStatusMeaning","Lifecycle operation outcome; not proof an external participating transaction committed","originalFailure",failureTree(original),
                "observerFailures",observerFailures.stream().map(VettingServiceProbe::failureTree).collect(java.util.stream.Collectors.toList()),
                "observerFailureCount",observerFailures.size(),"semanticQualityAccepted",null));
        if(directory!=null){
            try{
                Map<String,Object> summary=map("schemaVersion",1,"operationId",operationId,"runId",runId,"projectId",projectId,"status",status,
                        "finishedAt",Instant.now().toString(),"terminalStatusMeaning","Lifecycle operation outcome; external transaction outcome is separately marked unknown","eventCount",sequence,"observerFailures",observerFailures.stream().map(VettingServiceProbe::failureTree).collect(java.util.stream.Collectors.toList()),
                        "observerFailureCount",observerFailures.size(),"serializationWallNanos",serializationNanos,"artifactWriteWallNanos",writeNanos,
                        "observerWallNanos",observerNanos,"summarySelfFlushIncluded",false,"semanticQualityAccepted",null);
                Files.write(directory.resolve("service_probe_manifest.json"),JsonUtils.write(summary).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            }catch(Throwable e){observationFailure("terminal_manifest",e);}
        }
        attachObserverFailures(original);
    }
}
