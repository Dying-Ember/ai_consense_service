package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParseProbe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Optional fresh operation observation of the original report writer; no layout/content changes. */
final class VettingExportProbe {
    private final Path directory;private final String operationId,projectId,format,language,requestedAt;
    private String runId;private int sequence;private long observerNanos,serializationNanos,writeNanos;private boolean finished;
    private VettingExportProbe(Path directory,String operationId,String projectId,String format,String language,String requestedAt){
        this.directory=directory;this.operationId=operationId;this.projectId=projectId;this.format=format;this.language=language;this.requestedAt=requestedAt;
    }
    static VettingExportProbe start(ConsenseProperties props,String projectId,String format,String language){
        String configured=props.getVetting().getExportProbeDirectory();
        if(JsonUtils.isBlankText(configured))return new VettingExportProbe(null,null,projectId,format,language,null);
        String operation="export-"+UUID.randomUUID();String requestedAt=Instant.now().toString();
        Path root=Paths.get(configured).toAbsolutePath().normalize(),dir=root.resolve(operation);
        try{Files.createDirectories(root);Files.createDirectory(dir);return new VettingExportProbe(dir,operation,projectId,format,language,requestedAt);}
        catch(IOException e){throw new DocumentParseProbe.Failure("Cannot create fresh report export probe",e);}
    }
    boolean enabled(){return directory!=null;}
    void bindRun(String actualRunId){
        if(!enabled())return;
        if(JsonUtils.isBlankText(actualRunId)||runId!=null&&!runId.equals(actualRunId))throw new IllegalStateException("Report probe requires one actual selected report run ID");
        runId=actualRunId;
        event("export_run_binding",DocumentParseProbe.map("actualRunId",actualRunId,"requestedAt",requestedAt,"binding","ReportContext.job.id selected by unchanged reportContext policy"));
    }
    void event(String phase,Object observations){write(phase,observations,null);}
    void returned(byte[] bytes){write("actual_returned_report_bytes",DocumentParseProbe.map("byteArrayBoundary","Exact byte[] returned by service; HTTP client bytes are independently captured by the driver"),bytes);}
    private synchronized void write(String phase,Object observations,byte[] artifact){
        if(!enabled())return;long started=System.nanoTime();String name=String.format(Locale.ROOT,"%05d-%s",++sequence,phase);
        Map<String,Object> record=DocumentParseProbe.map("schemaVersion",1,"operationId",operationId,"projectId",projectId,"runId",runId,
            "format",format,"language",language,"phase",phase,"observedAt",Instant.now().toString(),"observations",observations);
        try{
            if(artifact!=null){
                long serial=System.nanoTime();String sha=DocumentParseProbe.sha256(artifact);serializationNanos+=System.nanoTime()-serial;
                String file=name+".report."+format;long io=System.nanoTime();try{Files.write(directory.resolve(file),artifact,StandardOpenOption.CREATE_NEW);}finally{writeNanos+=System.nanoTime()-io;}
                record.put("artifact",DocumentParseProbe.map("relativePath",file,"bytes",artifact.length,"sha256",sha));
            }
            long serial=System.nanoTime();byte[] body;
            try{body=JsonUtils.write(record).getBytes(StandardCharsets.UTF_8);}finally{serializationNanos+=System.nanoTime()-serial;}
            long io=System.nanoTime();try{Files.write(directory.resolve(name+".json"),body,StandardOpenOption.CREATE_NEW);}finally{writeNanos+=System.nanoTime()-io;}
        }catch(IOException|RuntimeException e){throw new DocumentParseProbe.Failure("Cannot save report export probe",e);}
        finally{observerNanos+=System.nanoTime()-started;}
    }
    void originalFailure(String phase,Throwable original){
        try{event(phase,DocumentParseProbe.map("failureChain",DocumentParseProbe.failures(original),"runIdentityKnown",runId!=null));}
        catch(DocumentParseProbe.Failure observation){original.addSuppressed(observation);}
    }
    void originalFailureElapsed(String phase,Throwable original,long elapsed){
        try{event(phase,DocumentParseProbe.map("failureChain",DocumentParseProbe.failures(original),"runIdentityKnown",runId!=null,
            "wallNanos",elapsed,"seconds",elapsed/1_000_000_000.0,"boundary","Actual reportContext failure before the selected run ID became known"));}
        catch(DocumentParseProbe.Failure observation){original.addSuppressed(observation);}
    }
    Timer measure(String phase,String boundary){return new Timer(this,phase,boundary);}
    static Timer measure(VettingExportProbe probe,String phase,String boundary){return probe==null?null:probe.measure(phase,boundary);}
    static final class Timer implements AutoCloseable{
        private final VettingExportProbe probe;private final String phase,boundary;private final long started,overhead;private boolean closed;
        Timer(VettingExportProbe probe,String phase,String boundary){this.probe=probe;this.phase=phase;this.boundary=boundary;this.overhead=probe.observerNanos;this.started=System.nanoTime();}
        @Override public void close(){if(closed)return;closed=true;long inclusive=System.nanoTime()-started,io=probe.observerNanos-overhead,actual=Math.max(0,inclusive-io);
            probe.event("phase_timing",DocumentParseProbe.map("stage",phase,"wallNanos",actual,"seconds",actual/1_000_000_000.0,"inclusiveWallNanos",inclusive,
                "probeOverheadNanos",io,"boundary",boundary,"clock","System.nanoTime; nested business stages are not additive"));}
    }
    synchronized void finish(String status){
        if(!enabled()||finished)return;finished=true;
        Map<String,Object> summary=DocumentParseProbe.map("schemaVersion",1,"operationId",operationId,"projectId",projectId,"runId",runId,"format",format,"status",status,
            "eventCount",sequence,"serializationAndHashWallNanos",serializationNanos,"artifactWriteWallNanos",writeNanos,"eventObserverWallNanos",observerNanos,
            "summarySelfFlushIncluded",false,"layoutQualityAccepted",null,"boundary","Report generation observations only; no Word pagination, native rendering inspection or semantic approval");
        try{Files.write(directory.resolve("probe_overhead_manifest.json"),JsonUtils.write(summary).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
        catch(IOException|RuntimeException e){throw new DocumentParseProbe.Failure("Cannot save report export overhead manifest",e);}
    }
    void finish(String status,Throwable originalFailure){
        try{finish(status);}catch(DocumentParseProbe.Failure observation){if(originalFailure==null)throw observation;originalFailure.addSuppressed(observation);}
    }
}
