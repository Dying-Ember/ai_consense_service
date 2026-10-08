package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Opt-in, operation-bound file observer. It does not classify source semantics or change parser output. */
public final class DocumentParseProbe {
    public static final class Failure extends IllegalStateException {
        public Failure(String message,Throwable cause) { super(message,cause); }
    }
    private final Path directory;
    private final String jobId,sourceId,sourceSha256;
    private int sequence;
    private long serializationNanos,writeNanos,observerNanos,imageEncodingNanos;
    private boolean finished;
    private DocumentParseProbe(Path directory,String jobId,String sourceId,String sourceSha256) {
        this.directory=directory;this.jobId=jobId;this.sourceId=sourceId;this.sourceSha256=sourceSha256;
    }
    public static boolean configured(ConsenseProperties props) { return !JsonUtils.isBlankText(props.getDocument().getProbeDirectory()); }
    public static DocumentParseProbe disabled(){return new DocumentParseProbe(null,null,null,null);}
    public static DocumentParseProbe start(ConsenseProperties props,String jobId,String sourceId,String expectedSha256,String fileName,byte[] bytes) {
        if(!configured(props))return new DocumentParseProbe(null,jobId,sourceId,null);
        safeId(jobId,"parse job ID");safeId(sourceId,"source ID");
        if(expectedSha256==null || !expectedSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Parse probe requires an explicit source SHA256");
        long hashStart=System.nanoTime();String actual=sha256(bytes);long hashNanos=System.nanoTime()-hashStart;
        if(!expectedSha256.equals(actual))throw new IllegalArgumentException("Parse probe source bytes differ from the expected SHA256");
        DocumentParseProbe probe=new DocumentParseProbe(fresh(props,jobId),jobId,sourceId,actual);
        probe.event("parse_started",null,map("fileName",fileName,"sourceBytes",bytes.length,"sourceIdentityHashWallNanos",hashNanos,
                "parameters",props.getDocument(),"ocrSelectionParameters",props.getOcr(),"historicalParseTimeReconstructed",false));
        return probe;
    }
    public static DocumentParseProbe corpus(ConsenseProperties props,String reviewRunId,String projectId) {
        if(!configured(props))return new DocumentParseProbe(null,reviewRunId,null,null);
        safeId(reviewRunId,"review run ID");safeId(projectId,"project ID");
        String job="corpus-"+reviewRunId;
        DocumentParseProbe probe=new DocumentParseProbe(fresh(props,job),job,null,null);
        probe.event("corpus_stage_started",null,map("reviewRunId",reviewRunId,"projectId",projectId,
                "boundary","Stored parsed state reused; this operation does not reparse or OCR the original sources"));
        return probe;
    }
    private static void safeId(String id,String label) {
        if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}"))throw new IllegalArgumentException("Probe requires safe explicit "+label);
    }
    private static Path fresh(ConsenseProperties props,String job) {
        Path root=Paths.get(props.getDocument().getProbeDirectory()).toAbsolutePath().normalize();
        Path dir=root.resolve(job).normalize();if(!dir.startsWith(root))throw new IllegalArgumentException("Probe job escaped root");
        try { Files.createDirectories(root);Files.createDirectory(dir);return dir; }
        catch(IOException e){throw new Failure("Cannot create fresh document probe job",e);}
    }
    public boolean enabled(){return directory!=null;}
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;
    }
    public static String sha256(byte[] bytes) {
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte x:digest)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
        catch(Exception e){throw new IllegalStateException("Cannot calculate source identity",e);}
    }
    public static List<Map<String,Object>> failures(Throwable failure) {
        List<Map<String,Object>> chain=new ArrayList<>();Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable value=failure;value!=null&&visited.add(value);value=value.getCause())
            chain.add(map("class",value.getClass().getName(),"message",value.getMessage(),"stackTrace",Arrays.asList(value.getStackTrace())));
        return chain;
    }
    public void event(String phase,Integer page,Object observations){write(phase,page,observations,null,null);}
    public void originalFailure(String phase,Integer page,Throwable original){
        try{event(phase,page,map("failureChain",failures(original)));}
        catch(Failure observation){original.addSuppressed(observation);}
    }
    public void binary(String phase,Integer page,Object observations,byte[] bytes,String extension){write(phase,page,observations,bytes,extension);}
    public void image(String phase,int page,BufferedImage image,int dpi) {
        if(!enabled())return;
        long start=System.nanoTime();
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
            byte[] encoded=out.toByteArray();long encodingNanos=System.nanoTime()-start;
            synchronized(this){imageEncodingNanos+=encodingNanos;observerNanos+=encodingNanos;}
            binary(phase,page,map("actualDpi",dpi,"width",image.getWidth(),"height",image.getHeight(),
                    "artifactRepresentation","PNG observation encoding of the actual rendered BufferedImage, not an OCR response",
                    "observerImageEncodingWallNanos",encodingNanos),encoded,"png");
        }catch(IOException e){throw new Failure("Cannot encode observed render image",e);}
    }
    private synchronized void write(String phase,Integer page,Object observations,byte[] bytes,String extension) {
        if(!enabled())return;
        long started=System.nanoTime();String stem=String.format(Locale.ROOT,"%05d-%s",++sequence,phase);
        Map<String,Object> record=map("schemaVersion",1,"jobId",jobId,"sourceId",sourceId,"sourceSha256",sourceSha256,
                "physicalPage",page,"phase",phase,"observedAt",Instant.now().toString(),"observations",observations);
        try {
            if(bytes!=null) {
                String name=stem+"."+extension;
                long serial=System.nanoTime();String hash=sha256(bytes);serializationNanos+=System.nanoTime()-serial;
                long io=System.nanoTime();try {Files.write(directory.resolve(name),bytes,StandardOpenOption.CREATE_NEW);}finally{writeNanos+=System.nanoTime()-io;}
                record.put("artifact",map("relativePath",name,"bytes",bytes.length,"sha256",hash));
            }
            long serial=System.nanoTime();byte[] encoded;
            try{encoded=JsonUtils.write(record).getBytes(StandardCharsets.UTF_8);}finally{serializationNanos+=System.nanoTime()-serial;}
            long io=System.nanoTime();try{Files.write(directory.resolve(stem+".json"),encoded,StandardOpenOption.CREATE_NEW);}finally{writeNanos+=System.nanoTime()-io;}
        }catch(IOException | RuntimeException e){throw new Failure("Document probe evidence could not be saved",e);}
        finally{observerNanos+=System.nanoTime()-started;}
    }
    public synchronized Timer measure(String phase,Integer page,String boundary){return new Timer(this,phase,page,boundary);}
    public static final class Timer implements AutoCloseable {
        private final DocumentParseProbe probe;private final String phase,boundary;private final Integer page;private final long started,overhead;
        private boolean closed;
        Timer(DocumentParseProbe probe,String phase,Integer page,String boundary){this.probe=probe;this.phase=phase;this.page=page;this.boundary=boundary;this.overhead=probe.observerNanos;this.started=System.nanoTime();}
        @Override public void close(){if(closed)return;closed=true;long inclusive=System.nanoTime()-started,io=probe.observerNanos-overhead;long actual=Math.max(0,inclusive-io);
            probe.event("phase_timing",page,map("stage",phase,"wallNanos",actual,"seconds",actual/1_000_000_000.0,"inclusiveWallNanos",inclusive,"probeOverheadNanos",io,"boundary",boundary));}
    }
    public synchronized void finish(String status) {
        if(!enabled()||finished)return;finished=true;
        Map<String,Object> result=map("schemaVersion",1,"jobId",jobId,"sourceId",sourceId,"sourceSha256",sourceSha256,"status",status,
                "eventCount",sequence,"serializationAndHashWallNanos",serializationNanos,"renderImageObservationEncodingWallNanos",imageEncodingNanos,
                "artifactWriteWallNanos",writeNanos,"eventAndImageObserverWallNanos",observerNanos,
                "summarySelfFlushIncluded",false,"boundary","Monotonic wall observations, not historical reconstruction or source accuracy. Final summary self flush is excluded; render PNG observation encoding is separately reported.");
        try{Files.write(directory.resolve("probe_overhead_manifest.json"),JsonUtils.write(result).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
        catch(IOException | RuntimeException e){throw new Failure("Cannot save document probe overhead manifest",e);}
    }
    public void finish(String status,Throwable originalFailure) {
        try{finish(status);}catch(Failure observation){if(originalFailure==null)throw observation;originalFailure.addSuppressed(observation);}
    }
    public static void bindPersisted(ConsenseProperties props,String jobId,String sourceId,String sourceSha256,String parsedSourceHash,String parseStatus) {
        if(!configured(props))return;safeId(jobId,"parse job ID");safeId(sourceId,"source ID");
        Path dir=Paths.get(props.getDocument().getProbeDirectory()).toAbsolutePath().normalize().resolve(jobId);
        try {
            JsonNodeBinding.verify(dir,sourceId,sourceSha256);
            Files.write(dir.resolve("source_persisted_binding.json"),JsonUtils.write(map("jobId",jobId,"sourceId",sourceId,"sourceSha256",sourceSha256,
                    "parsedSourceHash",parsedSourceHash,"parseStatus",parseStatus,"observedAt",Instant.now().toString())).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        }catch(IOException e){throw new Failure("Cannot bind persisted source to original parse job",e);}
    }
    private static final class JsonNodeBinding {
        static void verify(Path dir,String sourceId,String sha)throws IOException {
            com.fasterxml.jackson.databind.JsonNode start=JsonUtils.parse(new String(Files.readAllBytes(dir.resolve("00001-parse_started.json")),StandardCharsets.UTF_8));
            if(!sourceId.equals(start.path("sourceId").asText()) || !sha.equals(start.path("sourceSha256").asText()))throw new IllegalStateException("Persisted source binding differs from observed parse input");
        }
    }
}
