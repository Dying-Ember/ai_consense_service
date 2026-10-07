package com.consense.service.vetting;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Read-only view of recorded real corpora/index metadata and retrieval probes. Never loads models or opens a vector writer. */
@Service
public class VettingInspectionService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "tmp/vetting_foundation_hardening/";
    private final Path workspace;
    private final Path correctedRetrievalBinding;
    private final Path source44RetrievalBinding;
    private final Map<String, Dataset> datasets = new LinkedHashMap<>();
    private final Map<Path, String> verified = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> documentCache = new ConcurrentHashMap<>();
    private final Map<String,List<JsonNode>> probeEvents = new ConcurrentHashMap<>();
    private volatile JsonNode historical;
    private volatile Dataset currentNative;
    private volatile Dataset correctedSource;
    private volatile Dataset correctedRetrieval;
    private volatile JsonNode correctedRetrievalReceipt;
    private volatile Dataset source44Retrieval;
    private volatile JsonNode source44RetrievalReceipt;
    private static final String CORRECTED_DATASET="corrected-source42-native-v3-full44";
    private static final String FRESH44_DATASET="corrected-source44-native-v3-full44";
    private final Map<String,JsonNode> correctedIndexReceipts=new LinkedHashMap<>();
    private synchronized Dataset correctedSourceDataset() throws IOException {
        return correctedSourceDataset(CORRECTED_DATASET,PREFIX+"inspection-corrected-source42-20261004/completed_index_binding.json",42,1);
    }
    private synchronized Dataset fresh44SourceDataset() throws IOException {
        return correctedSourceDataset(FRESH44_DATASET,PREFIX+"inspection-corrected-source44-20261004/completed_index_binding.json",44,2);
    }
    private Dataset correctedSourceDataset(String id,String relativeBinding,int expectedRevisions,int expectedStages)throws IOException {
        Path binding=workspace.resolve(relativeBinding);if(!Files.isRegularFile(binding))return null;
        JsonNode receipt=read(binding);
        VettingInspectionIndexBinding.Bound bound=VettingInspectionIndexBinding.validate(receipt,id,expectedRevisions,expectedStages,new VettingInspectionIndexBinding.Artifacts(){
            public Path descriptor(JsonNode value)throws IOException{return VettingInspectionService.this.descriptor(value);}
            public JsonNode read(Path path)throws IOException{return VettingInspectionService.read(path);}
        });
        Dataset d=datasets.get(id);
        if(d==null){
            d=new Dataset(id,"44 sources · "+expectedRevisions+" OCR revisions · saved persisted index",workspace.relativize(bound.result).toString(),receipt.path("result").path("sha256").asText());
            load(d);if(!bound.sourceDocuments.equals(d.sourceDocuments))throw new IOException("Revisions belong to a different source snapshot");
            d.vectorSnapshotManifest=bound.vectors;d.sourceRevisionStages=bound.revisionStages;d.sourceRevisions=bound.revisionStages.get(bound.revisionStages.size()-1);d.oldSourceDocuments=bound.originalSourceDocuments;d.cumulativeRevisionCount=bound.revisionCount;
            datasets.put(id,d);correctedIndexReceipts.put(id,receipt.deepCopy());if(CORRECTED_DATASET.equals(id))correctedSource=d;
        }
        if(!receipt.equals(correctedIndexReceipts.get(id)))throw new IOException("Corrected dataset binding changed under the same identity");
        return d;
    }
    private void requireDescriptor(JsonNode actual,JsonNode expected)throws IOException {if(!actual.isObject()||!actual.equals(expected))throw new IOException("Bound artifact identity differs");descriptor(actual);}
    private synchronized Dataset correctedSourceRun() throws IOException {
        Dataset index=correctedSourceDataset();
        if(index==null||!Files.isRegularFile(correctedRetrievalBinding))return null;
        JsonNode binding=read(correctedRetrievalBinding);
        if(correctedRetrieval!=null){
            if(!binding.equals(correctedRetrievalReceipt))throw new IOException("Corrected query binding changed under the same run identity");
            descriptor(binding.path("result"));descriptor(binding.path("preparedPlan"));descriptor(binding.path("dispatchReceipt"));
            return correctedRetrieval;
        }
        Path indexBindingPath=workspace.resolve(PREFIX+"inspection-corrected-source42-20261004/completed_index_binding.json");
        JsonNode indexBinding=read(indexBindingPath);
        VettingInspectionRetrievalBinding.Bound bound=VettingInspectionRetrievalBinding.validate(binding,indexBindingPath,indexBinding,index.result,index.plan,new VettingInspectionRetrievalBinding.Artifacts(){
            public Path descriptor(JsonNode value)throws IOException{return VettingInspectionService.this.descriptor(value);}
            public JsonNode read(Path path)throws IOException{return VettingInspectionService.read(path);}
        });
        Dataset queries=new Dataset(CORRECTED_DATASET,"44 sources · 42 OCR revisions · actual16 topic2/6 retrieval (primary + coverage)",workspace.relativize(bound.result).toString(),binding.path("result").path("sha256").asText());
        load(queries);
        if(!queries.corpus.equals(index.corpus)||!Objects.equals(queries.sourceDocuments,index.sourceDocuments))throw new IOException("Corrected query dataset does not use the current index sources");
        // The catalog dataset keeps the index-only result, revisions and point snapshot. This is a separate query view.
        correctedRetrievalReceipt=binding;correctedRetrieval=queries;return queries;
    }
    private synchronized Dataset source44SourceRun() throws IOException {
        Dataset index=fresh44SourceDataset();Path path=source44RetrievalBinding;
        if(index==null||!Files.isRegularFile(path))return null;JsonNode binding=read(path);
        if(source44Retrieval!=null){
            if(!binding.equals(source44RetrievalReceipt))throw new IOException("Source44 query binding changed under the same run identity");
            for(String key:Arrays.asList("result","preparedPlan","dispatchReceipt","finalReceipt"))descriptor(binding.path(key));return source44Retrieval;
        }
        Path indexPath=workspace.resolve(PREFIX+"inspection-corrected-source44-20261004/completed_index_binding.json");JsonNode indexBinding=read(indexPath);
        VettingInspectionRetrievalBinding.Bound bound=VettingInspectionRetrievalBinding.validateSource44(binding,indexPath,indexBinding,index.result,index.plan,new VettingInspectionRetrievalBinding.Artifacts(){
            public Path descriptor(JsonNode value)throws IOException{return VettingInspectionService.this.descriptor(value);}
            public JsonNode read(Path file)throws IOException{return VettingInspectionService.read(file);}
        });
        Dataset queries=new Dataset(FRESH44_DATASET,"44 sources · 44 OCR revisions · actual16 structural-unit retrieval",workspace.relativize(bound.result).toString(),binding.path("result").path("sha256").asText());
        load(queries);if(!queries.corpus.equals(index.corpus)||!Objects.equals(queries.sourceDocuments,index.sourceDocuments))throw new IOException("Source44 query dataset differs from current index sources");
        source44RetrievalReceipt=binding;source44Retrieval=queries;return queries;
    }
    private Dataset currentNativeRun() throws IOException {
        Path binding=workspace.resolve(PREFIX+"inspection-historical-sent-20261004/completed_native_retrieval_binding.json");
        if(!Files.isRegularFile(binding))return null;
        JsonNode receipt=read(binding);if(!"completed-native-v3-retrieval-inspection-binding-v1".equals(receipt.path("protocol").asText()))throw new IOException("Current retrieval binding protocol differs");
        Path result=descriptor(receipt.path("result"));JsonNode raw=read(result);
        if(!"completed".equals(raw.path("status").asText())||raw.path("actualRetrievalAttempts").asInt()!=128||raw.path("actualIndexAttempts").asInt()!=0||!raw.path("queryOnly").asBoolean()||!raw.path("qdrantClose").path("completed").asBoolean()||!raw.path("originalProducerStateBeforeAfterByteExact").asBoolean()||!"5bec2f13e1ea5b1318dde9d31e4c707dc173dd58b01f6a7cf3aab9560c9660d8".equals(raw.path("indexSignature").asText()))throw new IOException("Current native retrieval is not a complete bound query-only result");
        JsonNode dispatch=read(descriptor(receipt.path("dispatchReceipt")));if(dispatch.path("exitCode").asInt(-1)!=0||!dispatch.path("result").equals(receipt.path("result")))throw new IOException("Current query dispatch completion differs");
        if(currentNative==null){Dataset d=new Dataset("native-v3-full44","Current native-v3 actual128 retrieval",workspace.relativize(result).toString(),receipt.path("result").path("sha256").asText());load(d);currentNative=d;}return currentNative;
    }
    private static final String HIST_RUN="bonsai-fullcase-semantic-1661", HIST_DATASET="historical-bonsai-submitted-1661";
    private JsonNode history() throws IOException {
        Path p=workspace.resolve(PREFIX+"inspection-historical-sent-20261004/historical_sent.json");
        if(!Files.isRegularFile(p))return null;
        verify(p,"569714e417c82d141b2615bda94b89b835cc333b9cfe202202e4e9a95c1fb53a");
        if(historical==null)historical=read(p);return historical;
    }
    private Map<String,Object> historyProvenance(JsonNode h){return map("scope",h.path("scope"),"datasetId",HIST_DATASET,"runId",h.path("runId"),"projectId",h.path("projectId"),"profile",h.path("profile"),"notCurrentNativeV3",true,"freshModelCalls",0,"dataSha256","569714e417c82d141b2615bda94b89b835cc333b9cfe202202e4e9a95c1fb53a");}
    private Map<String,Object> historicalChunk(String id)throws IOException {
        JsonNode h=history();if(h==null)throw notFound("Historical captured run unavailable");
        for(JsonNode c:h.path("chunks"))if(id.equals(c.path("id").asText()))return map("chunk",c,"windows",Collections.emptyList(),"vectorPoints",Collections.emptyList(),"vectorStatus","unavailable","vectorSnapshotStatus","unavailable","vectorSnapshotUnavailableReason","historical_capture_records_submitted_context_not_attached_vector_points","embeddingRecipe",null,"provenance",historyProvenance(h));
        throw notFound("Chunk not in historical submitted scope");
    }
    private Map<String,Object> historicalTasks(int page,int size)throws IOException {
        JsonNode h=history();if(h==null)throw notFound("Historical run unavailable");List<Object> rows=new ArrayList<>();
        for(JsonNode t:h.path("tasks"))rows.add(map("id",t.path("id"),"taskId",t.path("taskId"),"query",t.path("query"),"role",t.path("role"),"originalTopicOrdinal",t.path("originalTopicOrdinal"),"status",t.path("status"),"datasetId",HIST_DATASET));
        return page(rows,page,size,"available",null);
    }
    private Map<String,Object> historicalTask(String taskId,String stage,int page,int size)throws IOException {
        JsonNode h=history(),task=null;if(h==null)throw notFound("Historical run unavailable");
        for(JsonNode t:h.path("tasks"))if(taskId.equals(t.path("id").asText()))task=t;
        if(task==null)throw notFound("Historical task unavailable");List<JsonNode> rows=new ArrayList<>();boolean supported="sent".equals(stage)||"selected".equals(stage);
        if(supported)for(JsonNode c:task.path("selected")){ObjectNode row=c.deepCopy();row.put("rank",rows.size()+1);row.put("datasetId",HIST_DATASET);rows.add(row);}
        Map<String,Object> result=page(rows,page,size,supported?"available":"unavailable",supported?null:"use_sent_for_actual_generation_capture;retrieval_stage_not_attached_to_this_inspection_run");
        result.put("query",task.path("query"));result.put("role",task.path("role"));result.put("stage",stage);result.put("actualRequest",supported?task.path("request"):null);result.put("outputSchema",supported?task.path("schema"):null);result.put("profile",h.path("profile"));result.put("generationMetadata",task.path("generationMetadata"));result.put("provenance",task.path("provenance"));result.put("semanticQualityAccepted",null);return result;
    }

    public VettingInspectionService() { this(findWorkspace()); }
    public VettingInspectionService(Path workspace) {
        this(workspace,null);
    }
    VettingInspectionService(Path workspace,Path correctedRetrievalBinding) { this(workspace,correctedRetrievalBinding,null); }
    VettingInspectionService(Path workspace,Path correctedRetrievalBinding,Path source44RetrievalBinding) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.correctedRetrievalBinding=correctedRetrievalBinding==null?this.workspace.resolve(PREFIX+"inspection-corrected-source42-20261004/completed_retrieval_binding.json"):correctedRetrievalBinding.toAbsolutePath().normalize();
        if(!this.correctedRetrievalBinding.startsWith(this.workspace))throw new IllegalArgumentException("Retrieval binding outside inspection workspace");
        this.source44RetrievalBinding=source44RetrievalBinding==null?this.workspace.resolve(PREFIX+"inspection-corrected-source44-20261004/completed_retrieval_binding.json"):source44RetrievalBinding.toAbsolutePath().normalize();
        if(!this.source44RetrievalBinding.startsWith(this.workspace))throw new IllegalArgumentException("Source44 retrieval binding outside inspection workspace");
        datasets.put("native-v3-full44", new Dataset("native-v3-full44", "Native v3 — 44 sources, actual persisted index (no retrieval run)",
                PREFIX + "full44-native-v3-index-20261003T132419Z/actual/result.json",
                "5174b37877389c5f959aec71b0f7214657a374bbf8de2f4532991448cfab3371"));
        datasets.put("metadata-v2-full44", new Dataset("metadata-v2-full44", "Metadata v2 — 44 sources, actual primary64 retrieval", 
                PREFIX + "final-metadata-actual-20261003T0855Z/actual_full44/result.json", "916000b0775ef86d66193c165585229e50366563d19ea471739f4669ea1bdc93"));
    }
    private static Path findWorkspace() {
        String explicit = System.getenv("CONSENSE_WORKSPACE_ROOT");
        Path p = Paths.get(explicit == null || explicit.trim().isEmpty() ? System.getProperty("user.dir") : explicit).toAbsolutePath();
        for (Path c = p; c != null; c = c.getParent()) if (Files.isDirectory(c.resolve("ai_consense_service"))) return c;
        return p;
    }
    static final class Dataset {
        final String id, label, resultRelative, resultSha;
        volatile JsonNode result, plan;
        volatile Path corpus, metadata, sourceDocuments;
        volatile Path vectorSnapshotManifest;
        volatile Path oldSourceDocuments;
        volatile JsonNode sourceRevisions;
        volatile List<JsonNode> sourceRevisionStages;
        volatile int cumulativeRevisionCount;
        Dataset(String id, String label, String resultRelative, String resultSha) { this.id=id; this.label=label; this.resultRelative=resultRelative; this.resultSha=resultSha; }
    }
    public Map<String,Object> catalog() {
        List<Object> items=new ArrayList<>();
        Set<String> correctedAvailable=new HashSet<>();
        for(String correctedId:Arrays.asList(CORRECTED_DATASET,FRESH44_DATASET))try{
            Dataset current=CORRECTED_DATASET.equals(correctedId)?correctedSourceDataset():fresh44SourceDataset();if(current!=null)correctedAvailable.add(correctedId);
        }catch(IOException ex){items.add(map("id",correctedId,"label","Corrected source index","status","unavailable","unavailableReason","recorded_artifact_missing_or_identity_failed"));}
        for (Dataset d:datasets.values()) {
            if((CORRECTED_DATASET.equals(d.id)||FRESH44_DATASET.equals(d.id))&&!correctedAvailable.contains(d.id))continue;
            Map<String,Object> item=map("id",d.id,"label",d.label,"scope","saved_actual_experiment", "status","unavailable");
            if (Files.isRegularFile(workspace.resolve(d.resultRelative))) try {
                load(d); item.put("status","available"); item.put("projectId",d.result.path("projectId").asText());
                item.put("chunkCount",d.result.path("parentCount").asInt()); item.put("vectorStatus","saved_persisted_metadata");
                item.put("vectorPointCount",d.result.path("indexResult").path("vectorPoints").asInt());
                item.put("provenance",provenance(d));
            } catch(Exception ex) { item.put("unavailableReason","recorded_artifact_missing_or_identity_failed"); }
            else item.put("unavailableReason","saved_experiment_not_present_on_this_host");
            items.add(item);
        }
        try{JsonNode h=history();if(h!=null)items.add(map("id",HIST_DATASET,"label","Historical Bonsai 2026-10-02 — actual sent excerpts only, not current corpus","scope","historical_actual_http_generation_capture","status","available","projectId",h.path("projectId"),"chunkCount",h.path("uniqueSubmittedChunkCount"),"vectorStatus","unavailable","provenance",historyProvenance(h)));}catch(IOException ex){items.add(map("id",HIST_DATASET,"label","Historical Bonsai captured input","status","unavailable","unavailableReason","historical_capture_identity_failed"));}
        return map("datasets",items,"runs",runs(),"readOnly",true,"freshModelCalls",0,"freshVectorQueries",0);
    }
    private synchronized void load(Dataset d) throws IOException {
        if (d.result!=null) return;
        Path result=workspace.resolve(d.resultRelative); if(d.resultSha!=null) verify(result,d.resultSha);
        JsonNode r=read(result); Path plan=descriptor(r.path("plan")); JsonNode p=read(plan);
        Path corpus=descriptor(p.path("inputs").path("rawCorpus")); Path meta=descriptor(r.path("mainMetadata"));
        Path cm=descriptor(p.path("inputs").path("corpusManifest")); JsonNode manifest=read(cm);
        JsonNode source=manifest.path("sourceDocuments");
        if(source.isMissingNode()) {
            // Historical corpus manifests explicitly bind saved SourceDocument input through their production receipt.
            source=manifest.path("savedSourceDocuments");
        }
        d.corpus=corpus;d.metadata=meta;d.sourceDocuments=source.has("path")?descriptor(source):null;d.plan=p;d.result=r;
    }
    private Dataset dataset(String id) throws IOException {
        if(CORRECTED_DATASET.equals(id)||FRESH44_DATASET.equals(id)){Dataset corrected=CORRECTED_DATASET.equals(id)?correctedSourceDataset():fresh44SourceDataset();if(corrected==null)throw notFound("Corrected source index is not terminally bound");return corrected;}
        Dataset d=datasets.get(id);if(d==null) throw notFound("Unknown dataset");load(d);return d;
    }
    private Path descriptor(JsonNode n) throws IOException {
        if(!n.hasNonNull("path") || !n.hasNonNull("sha256")) throw new IOException("Missing artifact descriptor");
        Path p=Paths.get(n.path("path").asText()).toAbsolutePath().normalize();
        if(!p.startsWith(workspace)) throw new IOException("Artifact outside inspection workspace");
        if(!Files.isRegularFile(p) || (n.has("bytes") && Files.size(p)!=n.path("bytes").asLong())) throw new IOException("Artifact missing or size differs");
        verify(p,n.path("sha256").asText());return p;
    }
    private void verify(Path p,String expected) throws IOException {
        String identity=Files.size(p)+":"+Files.getLastModifiedTime(p).toMillis()+":"+expected;
        if(identity.equals(verified.get(p)))return;
        try(InputStream in=Files.newInputStream(p)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1)digest.update(b,0,n);StringBuilder hex=new StringBuilder();for(byte v:digest.digest())hex.append(String.format("%02x",v&255));
            if(!hex.toString().equals(expected))throw new IOException("Artifact digest differs");verified.put(p,identity);
        }catch(java.security.NoSuchAlgorithmException ex){throw new IOException(ex);}
    }
    private static JsonNode read(Path p)throws IOException{return JSON.readTree(p.toFile());}
    private static ResponseStatusException notFound(String message){return new ResponseStatusException(HttpStatus.NOT_FOUND,message);}
    static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    static Map<String,Object> page(List<?> all,int page,int pageSize,String status,String reason) {
        int size=Math.max(1,Math.min(100,pageSize)), number=Math.max(1,page);long offset=(long)(number-1)*size;
        int start=(int)Math.min(all.size(),offset),end=Math.min(all.size(),start+size);
        return map("items",new ArrayList<>(all.subList(start,end)),"total",all.size(),"page",number,"pageSize",size,"status",status,"unavailableReason",reason);
    }
    private void chunks(Dataset d, Consumer<JsonNode> visitor)throws IOException {
        try(BufferedReader in=Files.newBufferedReader(d.corpus,StandardCharsets.UTF_8)) {String line;while((line=in.readLine())!=null)if(!line.trim().isEmpty())visitor.accept(JSON.readTree(line));}
    }
    public Map<String,Object> chunks(String datasetId,int page,int size,String q,String doc,String role)throws IOException {
        if(HIST_DATASET.equals(datasetId)){JsonNode h=history();if(h==null)throw notFound("Historical dataset unavailable");List<JsonNode> rows=new ArrayList<>();String query=q==null?"":q.toLowerCase(Locale.ROOT);for(JsonNode c:h.path("chunks"))if((doc==null||doc.isEmpty()||doc.equals(c.path("documentId").asText()))&&(role==null||role.isEmpty()||role.equals(c.path("role").asText()))&&(query.isEmpty()||c.path("content").asText().toLowerCase(Locale.ROOT).contains(query)))rows.add(c);Map<String,Object> result=page(rows,page,size,"available",null);result.put("provenance",historyProvenance(h));return result;}
        Dataset d=dataset(datasetId);int number=Math.max(1,page),limit=Math.max(1,Math.min(100,size));long from=(long)(number-1)*limit;
        List<JsonNode> items=new ArrayList<>();long[] count={0};String needle=q==null?"":q.toLowerCase(Locale.ROOT);
        chunks(d,c->{if(doc!=null&&!doc.isEmpty()&&!doc.equals(c.path("documentId").asText()))return;
            if(role!=null&&!role.isEmpty()&&!role.equals(c.path("role").asText()))return;
            if(!needle.isEmpty()&&!c.path("content").asText().toLowerCase(Locale.ROOT).contains(needle)&&!c.path("fileName").asText().toLowerCase(Locale.ROOT).contains(needle)&&!c.path("anchor").asText().toLowerCase(Locale.ROOT).contains(needle))return;
            if(count[0]>=from&&items.size()<limit)items.add(c);count[0]++;});
        return map("items",items,"total",count[0],"page",number,"pageSize",limit,"status","available","unavailableReason",null,"provenance",provenance(d));
    }
    public Map<String,Object> chunk(String datasetId,String id)throws IOException {
        if(HIST_DATASET.equals(datasetId))return historicalChunk(id);
        Dataset d=dataset(datasetId);JsonNode[] found={null};chunks(d,c->{if(id.equals(c.path("id").asText()))found[0]=c;});if(found[0]==null)throw notFound("Chunk not in selected dataset");
        List<JsonNode> windows=new ArrayList<>();array(d.metadata,"windows",w->{if(id.equals(w.path("parentId").asText()))windows.add(w);});
        Map<String,Object> snapshot=vectorSnapshot(d,id,windows);
        return map("chunk",found[0],"windows",windows,"embeddingRecipe",safeRecipe(d.plan),"vectorStatus","saved_persisted_metadata","vectorInspectionBoundary","Metadata windows and independently read database snapshot points are separate fields; this endpoint performs no live Qdrant scroll or inference.","vectorPoints",snapshot.get("points"),"vectorSnapshotStatus",snapshot.get("status"),"vectorSnapshotUnavailableReason",snapshot.get("unavailableReason"),"vectorSnapshotProvenance",snapshot.get("provenance"),"provenance",provenance(d));
    }
    private Map<String,Object> vectorSnapshot(Dataset d,String parentId,List<JsonNode> windows)throws IOException {
        Path manifestPath=d.vectorSnapshotManifest!=null?d.vectorSnapshotManifest:workspace.resolve(PREFIX+"inspection-qdrant-native-v3-20261004/manifest.json");
        if((d.vectorSnapshotManifest==null&&!"native-v3-full44".equals(d.id))||!Files.isRegularFile(manifestPath))return map("points",Collections.emptyList(),"status","unavailable","unavailableReason","independent_database_snapshot_not_recorded_for_this_dataset","provenance",null);
        JsonNode manifest=read(manifestPath);JsonNode identity=manifest.path("sourceStoreIdentity");
        if(!"inspection-qdrant-snapshot-v1".equals(manifest.path("protocol").asText())||!d.result.path("projectId").asText().equals(identity.path("projectId").asText())||!d.result.path("indexSignature").asText().equals(identity.path("signature").asText()))throw new IOException("Vector snapshot dataset identity differs");
        Path rows=descriptor(manifest.path("parentPoints"));List<JsonNode> points=new ArrayList<>();Set<String> ids=new HashSet<>();for(JsonNode w:windows)ids.add(w.path("id").asText());
        try(BufferedReader in=Files.newBufferedReader(rows,StandardCharsets.UTF_8)){String line;while((line=in.readLine())!=null){JsonNode row=JSON.readTree(line);if(parentId.equals(row.path("parentId").asText())){for(JsonNode point:row.path("points")){if(!ids.contains(point.path("id").asText())||!identity.path("collection").asText().equals(point.path("collection").asText()))throw new IOException("Vector snapshot point binding differs");points.add(point);}break;}}}
        if(points.size()!=windows.size())throw new IOException("Vector snapshot parent window coverage differs");
        return map("points",points,"status","available","unavailableReason",null,"provenance",map("scope","independent_readonly_database_snapshot","snapshotAt",manifest.path("snapshotAt"),"sourceStoreIdentity",identity,"parentPointsSha256",manifest.path("parentPoints").path("sha256"),"readBoundary",manifest.path("readBoundary"),"liveDatabaseRead",false));
    }
    private static Map<String,Object> safeRecipe(JsonNode p) {
        ObjectNode environment=JSON.createObjectNode();for(String key:Arrays.asList("CONSENSE_MODEL_DEVICE","CONSENSE_MODEL_DTYPE","CONSENSE_MODEL_SERIAL_OFFLOAD","CONSENSE_EMBED_MAX_TOKENS","CONSENSE_RERANK_MAX_TOKENS","CONSENSE_EMBED_BATCH_SIZE","CONSENSE_RERANK_BATCH_SIZE","CONSENSE_WINDOW_OVERLAP_TOKENS","HF_HUB_OFFLINE","TRANSFORMERS_OFFLINE"))if(p.path("environment").has(key))environment.set(key,p.path("environment").get(key));
        return map("models",p.path("configuredModels"),"math",p.path("math"),"environment",environment,"runtimeFactor",p.path("runtimeFactor"),"windowToolingSha256",p.path("inputs").path("windowTooling").path("sha256").asText());
    }
    private Map<String,Object> provenance(Dataset d) {
        JsonNode shape=d.result.path("actualEncodedFloat32Groups").path(0).path("shape");
        Map<String,Object> out=map("scope","saved_actual_experiment","datasetId",d.id,"projectId",d.result.path("projectId").asText(),"corpusSha256",d.plan.path("inputs").path("rawCorpus").path("sha256").asText(),"metadataSha256",d.result.path("mainMetadata").path("sha256").asText(),"indexSignature",d.result.path("indexSignature"),"recordedVectorDimension",shape.size()==2?shape.get(1):null,"freshQueryExecuted",false,"liveDatabaseRead",false);
        if(d==correctedRetrieval){out.put("runId",VettingInspectionRetrievalBinding.RUN);out.put("resultSha256",d.resultSha);out.put("sourceIndexResult",d.result.path("previousActualIndex"));out.put("recordedRetrievalAttempts",d.result.path("actualRetrievalAttempts"));out.put("selectedBoundary","retrieval_returned_parents_only_not_semantic_context_selection");out.put("generationDispatchRecorded",false);}
        if(d==source44Retrieval){out.put("runId",VettingInspectionRetrievalBinding.SOURCE44_RUN);out.put("resultSha256",d.resultSha);out.put("sourceIndexResult",d.result.path("previousActualIndex"));out.put("recordedRetrievalAttempts",d.result.path("actualRetrievalAttempts"));out.put("selectionPolicy",VettingInspectionRetrievalBinding.SOURCE44_POLICY);out.put("selectedBoundary","actual_scored_seeds_only_derived_members_separate");out.put("sourceUnitBoundary","attached_unranked_observed_source_only_not_semantic_context_or_sent");out.put("generationDispatchRecorded",false);}
        return out;
    }
    /** Streaming array traversal avoids retaining a second copy of the 170 MB persisted metadata. */
    private static void array(Path path,String field,Consumer<JsonNode> visitor)throws IOException {
        try(JsonParser p=JSON.getFactory().createParser(path.toFile())) {
            if(p.nextToken()==JsonToken.START_ARRAY && field==null) {while(p.nextToken()!=JsonToken.END_ARRAY)visitor.accept(JSON.readTree(p));return;}
            while(p.nextToken()!=null)if(p.currentToken()==JsonToken.FIELD_NAME) {
                String name=p.currentName();p.nextToken();if(name.equals(field)&&p.currentToken()==JsonToken.START_ARRAY){while(p.nextToken()!=JsonToken.END_ARRAY)visitor.accept(JSON.readTree(p));return;}p.skipChildren();
            }
        }
    }
    private List<JsonNode> documents(Dataset d)throws IOException {
        List<JsonNode> cached=documentCache.get(d.id);if(cached!=null)return cached;
        Map<String,ObjectNode> byId=new LinkedHashMap<>();
        chunks(d,c->{String id=c.path("documentId").asText();ObjectNode x=byId.computeIfAbsent(id,k->{ObjectNode v=JSON.createObjectNode();v.put("id",id);v.set("documentId",c.path("documentId"));for(String f:Arrays.asList("fileName","fileKey","role","sourceHash","sourceQuality","sourceQualityHash"))if(c.has(f))v.set(f,c.get(f));v.put("chunkCount",0);return v;});x.put("chunkCount",x.path("chunkCount").asInt()+1);});
        if(d.sourceDocuments!=null)array(d.sourceDocuments,null,s->{ObjectNode x=byId.get(s.path("id").asText());if(x==null)return;for(String f:Arrays.asList("parseStatus","parseMessage","pageCount","ocrUsed","contentType","sizeBytes"))x.set(f,s.path(f));if(s.hasNonNull("parseCoverageJson"))try{x.set("parseCoverage",JSON.readTree(s.path("parseCoverageJson").asText()));}catch(IOException ignored){x.put("coverageStatus","unavailable_invalid_saved_json");}x.put("originalUrl",originalUrl(d.id,x.path("id").asText()));x.put("pageStatus",s.path("pageCount").asInt()>0?"recorded_pages":"physical_pages_unknown");});
        List<JsonNode> out=new ArrayList<>(byId.values());documentCache.put(d.id,out);return out;
    }
    public Map<String,Object> documents(String id,int page,int size)throws IOException {if(HIST_DATASET.equals(id)){JsonNode h=history();if(h==null)throw notFound("Historical dataset unavailable");Map<String,ObjectNode> docs=new LinkedHashMap<>();for(JsonNode c:h.path("chunks")){String key=c.path("documentId").asText();ObjectNode x=docs.computeIfAbsent(key,k->{ObjectNode v=JSON.createObjectNode();v.put("id",k);for(String f:Arrays.asList("fileName","fileKey","role","sourceHash"))v.set(f,c.path(f));v.put("pageStatus","historical_submitted_excerpts_only");v.put("chunkCount",0);return v;});x.put("chunkCount",x.path("chunkCount").asInt()+1);}return page(new ArrayList<>(docs.values()),page,size,"available",null);}Dataset d=dataset(id);Map<String,Object> out=page(documents(d),page,size,"available",null);out.put("provenance",provenance(d));return out;}
    private JsonNode source(Dataset d,String id)throws IOException {
        return source(d.sourceDocuments,id);
    }
    private JsonNode source(Path path,String id)throws IOException {if(path==null)return null;JsonNode[] found={null};boolean[] duplicate={false};array(path,null,s->{if(id.equals(s.path("id").asText())){if(found[0]!=null)duplicate[0]=true;found[0]=s;}});if(duplicate[0])throw new IOException("Ambiguous saved source document identity");return found[0];}
    private static String originalUrl(String datasetId,String doc){return "/api/vetting-inspection/documents/"+doc+"/original?datasetId="+datasetId;}
    public Path original(String datasetId,String doc)throws IOException {
        Dataset d=dataset(datasetId);boolean exists=documents(d).stream().anyMatch(x->doc.equals(x.path("id").asText()));if(!exists)throw notFound("Document not in dataset");
        JsonNode s=source(d,doc);if(s==null||!s.hasNonNull("storagePath"))throw notFound("Original asset unavailable");
        Path p=Paths.get(s.path("storagePath").asText()).toAbsolutePath().normalize();if(!p.startsWith(workspace)||!Files.isRegularFile(p)||!p.toRealPath().startsWith(workspace.toRealPath()))throw notFound("Original asset unavailable");return p;
    }
    private List<JsonNode> pageBlocks(Dataset d,String doc)throws IOException {
        JsonNode s=source(d,doc);if(s==null)return Collections.emptyList();JsonNode blocks=JSON.readTree(s.path("structuredContentJson").asText("[]"));if(!blocks.isArray())throw new IOException("Saved document blocks not an array");List<JsonNode> out=new ArrayList<>();blocks.forEach(out::add);return out;
    }
    private static Integer pageNumber(JsonNode n) {if(n.isInt()&&n.asInt()>0)return n.asInt();String x=n.asText("");if(x.matches("P?[1-9][0-9]*"))try{return Integer.valueOf(x.replace("P",""));}catch(NumberFormatException ignored){}return null;}
    public Map<String,Object> pages(String datasetId,String doc,int page,int size)throws IOException {
        if(HIST_DATASET.equals(datasetId))return page(Collections.emptyList(),page,size,"unavailable","historical_capture_contains_submitted_excerpts_not_complete_parsed_page_records");
        Dataset d=dataset(datasetId);JsonNode s=source(d,doc);if(s==null)return page(Collections.emptyList(),page,size,"unavailable","saved_parse_document_unavailable");
        int count=s.path("pageCount").asInt();List<Object> out=new ArrayList<>();Map<Integer,Integer> totals=new TreeMap<>(),ocr=new TreeMap<>();
        for(JsonNode b:pageBlocks(d,doc)){Integer no=pageNumber(b.path("pageNo"));if(no==null)continue;totals.merge(no,1,Integer::sum);String extraction=b.path("source").asText(b.path("extractionSource").asText());if(extraction.toLowerCase(Locale.ROOT).contains("ocr"))ocr.merge(no,1,Integer::sum);}
        Set<Integer> observed=new TreeSet<>(totals.keySet());for(int i=1;i<=count;i++)observed.add(i);
        for(Integer no:observed)out.add(map("pageNo",no,"blockCount",totals.getOrDefault(no,0),"ocrBlockCount",ocr.getOrDefault(no,0),"status",totals.containsKey(no)?"recorded_extraction":"no_recorded_text","originalUrl",originalUrl(datasetId,doc)));
        Map<String,Object> result=page(out,page,size,out.isEmpty()?"unavailable":"available",out.isEmpty()?"physical_pages_unknown_or_not_recorded":null);result.put("parseStatus",s.path("parseStatus"));result.put("provenance",provenance(d));return result;
    }
    public Map<String,Object> page(String datasetId,String doc,int number)throws IOException {
        Dataset d=dataset(datasetId);if(number<1)throw notFound("Invalid page");JsonNode s=source(d,doc);if(s==null)throw notFound("Saved source unavailable");
        List<JsonNode> blocks=new ArrayList<>();Set<String> extraction=new LinkedHashSet<>();StringBuilder text=new StringBuilder();
        for(JsonNode b:pageBlocks(d,doc))if(Integer.valueOf(number).equals(pageNumber(b.path("pageNo")))) {blocks.add(b);if(text.length()>0)text.append('\n');text.append(b.path("text").asText());extraction.add(b.path("source").asText(b.path("extractionSource").asText("unknown")));}
        if(number>s.path("pageCount").asInt()&&blocks.isEmpty())throw notFound("Page not recorded");
        JsonNode quality=s.hasNonNull("parseCoverageJson")?JSON.readTree(s.path("parseCoverageJson").asText()):JSON.createObjectNode();
        return map("pageNo",number,"text",text.toString(),"blocks",blocks,"quality",quality,"extractionSources",extraction,"textRevisions",pageRevisions(d,doc,number,blocks),"status",blocks.isEmpty()?"unavailable":"available","unavailableReason",blocks.isEmpty()?"no_recorded_text_for_page":null,"assetUrl",null,"assetStatus","page_image_not_recorded","originalUrl",originalUrl(datasetId,doc),"provenance",provenance(d));
    }
    private List<Object> pageRevisions(Dataset d,String doc,int number,List<JsonNode> blocks)throws IOException {
        if(d.sourceRevisions==null)return Collections.emptyList();
        List<JsonNode> stages=d.sourceRevisionStages==null?Collections.singletonList(d.sourceRevisions):d.sourceRevisionStages;
        Map<String,JsonNode> finalBlocks=uniquePageBlocks(blocks);List<Object> rows=new ArrayList<>();Map<String,JsonNode> latestChanges=new LinkedHashMap<>();
        int step=0;
        for(JsonNode stage:stages){
            step++;JsonNode sourceRevision=null;for(JsonNode r:stage.path("documentRevisions"))if(doc.equals(r.path("documentId").asText())){if(sourceRevision!=null)throw new IOException("Ambiguous revision document identity");sourceRevision=r;}
            if(sourceRevision==null)continue;
            JsonNode oldDocument=source(descriptor(stage.path("oldSourceSnapshot")),doc),newDocument=source(descriptor(stage.path("newSourceSnapshot")),doc);
            if(oldDocument==null||newDocument==null||!sourceHash(oldDocument).equals(sourceRevision.path("oldSourceHash").asText())||!sourceHash(newDocument).equals(sourceRevision.path("newSourceHash").asText())||!oldDocument.path("parseStatus").equals(newDocument.path("parseStatus"))||!oldDocument.path("parseCoverageJson").equals(newDocument.path("parseCoverageJson")))throw new IOException("Page before/after source or quality binding differs");
            Map<String,JsonNode> oldBlocks=uniquePageBlocks(documentPageBlocks(oldDocument,number)),newBlocks=uniquePageBlocks(documentPageBlocks(newDocument,number));
            for(JsonNode change:stage.path("wholeBlockChanges")){
                JsonNode original=change.path("sourceBlockBinding");if(!doc.equals(original.path("documentId").asText())||number!=original.path("physicalPage").asInt())continue;
                String id=original.path("blockId").asText();JsonNode before=oldBlocks.get(id),after=newBlocks.get(id);
                if(before==null||after==null||!before.path("text").equals(original.path("oldBlockText"))||!before.path("bbox").equals(original.path("oldBlockBbox"))||!after.path("text").equals(change.path("newText"))||!after.path("bbox").equals(original.path("oldBlockBbox")))throw new IOException("Page before/after source or quality binding differs");
                latestChanges.put(id,change);
                rows.add(map("candidateId",change.path("candidateId"),"blockId",id,"pageNo",number,"bbox",original.path("oldBlockBbox"),"oldText",original.path("oldBlockText"),"newText",change.path("newText"),"oldSourceHash",sourceRevision.path("oldSourceHash"),"newSourceHash",sourceRevision.path("newSourceHash"),"confidenceRefersToHistoricalOcr",change.path("confidenceRefersToHistoricalOcr"),"humanConfirmed",false,"textAccuracyPromoted",false,"revisionStep",step,"cumulativeRevisionCount",d.cumulativeRevisionCount));
            }
        }
        for(Map.Entry<String,JsonNode> entry:latestChanges.entrySet()){
            JsonNode actual=finalBlocks.get(entry.getKey()),change=entry.getValue();if(actual==null||!actual.path("text").equals(change.path("newText"))||!actual.path("bbox").equals(change.path("sourceBlockBinding").path("oldBlockBbox")))throw new IOException("Page revision does not match current complete block");
        }
        return rows;
    }
    private static Map<String,JsonNode> uniquePageBlocks(List<JsonNode> blocks)throws IOException {
        Map<String,JsonNode> out=new LinkedHashMap<>();for(JsonNode b:blocks)if(out.put(b.path("id").asText(),b)!=null)throw new IOException("Ambiguous page revision block identity");return out;
    }
    private static List<JsonNode> documentPageBlocks(JsonNode source,int page)throws IOException {
        JsonNode blocks=JSON.readTree(source.path("structuredContentJson").asText("[]"));if(!blocks.isArray())throw new IOException("Saved document blocks not an array");
        List<JsonNode> out=new ArrayList<>();for(JsonNode b:blocks)if(Integer.valueOf(page).equals(pageNumber(b.path("pageNo"))))out.add(b);return out;
    }
    private static String sourceHash(JsonNode source){com.consense.domain.SourceDocument d=new com.consense.domain.SourceDocument();d.setTextContent(source.path("textContent").asText(null));d.setStructuredContentJson(source.path("structuredContentJson").asText(null));return VettingCorpus.sourceHash(d);}
    public List<Object> runs() {
        List<Object> rows=new ArrayList<>();Dataset d=datasets.get("metadata-v2-full44");
        if(Files.isRegularFile(workspace.resolve(d.resultRelative)))try{load(d);rows.add(map("id","metadata-v2-primary64","label","Actual GPU primary64 — saved dense/BM25/RRF/rerank", "datasetId",d.id,"status",d.result.path("status").asText(),"taskCount",d.result.path("actualRetrievalAttempts").asInt(),"scope","saved_actual_retrieval","provenance",provenance(d)));}catch(IOException ignored){}
        try{JsonNode h=history();if(h!=null)rows.add(map("id",HIST_RUN,"label",h.path("label"),"datasetId",HIST_DATASET,"status","captured_actual_generation","taskCount",h.path("taskCount"),"scope",h.path("scope"),"provenance",historyProvenance(h)));}catch(IOException ignored){}
        try{Dataset n=currentNativeRun();if(n!=null)rows.add(map("id","native-v3-current128","label","Current native-v3 — actual128 dense/BM25/RRF/rerank (primary + coverage)","datasetId","native-v3-full44","status","completed","taskCount",128,"scope","saved_actual_current_native_retrieval","provenance",provenance(n)));}catch(IOException ignored){}
        try{Dataset n=correctedSourceRun();if(n!=null)rows.add(map("id",VettingInspectionRetrievalBinding.RUN,"label","44 sources · 42 OCR revisions · actual16 topic2/6 retrieval (primary + coverage)","datasetId",CORRECTED_DATASET,"status","completed","taskCount",16,"scope","saved_actual_corrected_source_retrieval","provenance",provenance(n)));}catch(IOException ignored){}
        try{Dataset n=source44SourceRun();if(n!=null)rows.add(map("id",VettingInspectionRetrievalBinding.SOURCE44_RUN,"label","44 sources · 44 OCR revisions · actual16 structural units (primary + coverage)","datasetId",FRESH44_DATASET,"status","completed","taskCount",16,"scope","saved_actual_source44_structural_unit_retrieval","provenance",provenance(n)));}catch(IOException ignored){}
        return rows;
    }
    private Dataset run(String id)throws IOException {if(VettingInspectionRetrievalBinding.SOURCE44_RUN.equals(id)){Dataset n=source44SourceRun();if(n==null)throw notFound("Source44 retrieval has not been terminally bound");return n;}if(VettingInspectionRetrievalBinding.RUN.equals(id)){Dataset n=correctedSourceRun();if(n==null)throw notFound("Corrected source retrieval has not been terminally bound");return n;}if("native-v3-current128".equals(id)){Dataset n=currentNativeRun();if(n==null)throw notFound("Current native retrieval has not been terminally bound");return n;}if(!"metadata-v2-primary64".equals(id))throw notFound("Unknown inspection run");return dataset("metadata-v2-full44");}
    public Map<String,Object> tasks(String runId,int page,int size)throws IOException {
        if(HIST_RUN.equals(runId))return historicalTasks(page,size);
        Dataset d=run(runId);List<Object> out=new ArrayList<>();for(JsonNode r:d.plan.path("requests")) {JsonNode request=r.path("request");Map<String,Object> task=map("id",String.valueOf(r.path("ordinal").asInt()),"taskId",String.valueOf(r.path("ordinal").asInt()),"query",request.path("query").asText(),"role",request.path("role").asText(),"originalTopicOrdinal",r.path("originalTopicOrdinal"),"profileId",r.path("profileId"),"status","recorded_actual_retrieval","datasetId",d.id);if(d==source44Retrieval)task.put("stages",Arrays.asList("dense","bm25","rrf","rerank","selected","source_units","sent"));out.add(task);}return page(out,page,size,"available",null);
    }
    private List<JsonNode> events(Dataset d)throws IOException {
        String identity=d.result.path("pipelineProbeManifest").path("sha256").asText();List<JsonNode> cached=probeEvents.get(identity);if(cached!=null)return cached;Path pm=descriptor(d.result.path("pipelineProbeManifest"));List<JsonNode> out=new ArrayList<>();
        Set<String> allowed=new HashSet<>(Arrays.asList("dense-parent-candidates","parent-bm25-candidates","parent-rrf-candidates","reranker-output"));
        array(pm,"events",e->{if(allowed.contains(e.path("stage").asText()))out.add(e);});probeEvents.put(identity,out);return out;
    }
    private JsonNode stagePayload(Dataset d,int ordinal,String stage)throws IOException {
        Path pm=descriptor(d.result.path("pipelineProbeManifest"));
        for(JsonNode e:events(d))if(stage.equals(e.path("stage").asText())) {
            JsonNode descriptor=e.path("artifact");Path p=pm.getParent().resolve(descriptor.path("relativePath").asText()).normalize();
            if(!p.startsWith(pm.getParent()))throw new IOException("Probe path outside run");verify(p,descriptor.path("sha256").asText());JsonNode event=read(p);
            if(event.path("context").path("queryOrdinal").asInt()==ordinal)return event.path("payload");
        }return JSON.createObjectNode();
    }
    public Map<String,Object> task(String runId,String taskId,String stage,int page,int size)throws IOException {
        if(HIST_RUN.equals(runId))return historicalTask(taskId,stage,page,size);
        Dataset d=run(runId);int ordinal;try{ordinal=Integer.parseInt(taskId);}catch(NumberFormatException ex){throw notFound("Unknown task");}
        JsonNode row=null;for(JsonNode r:d.plan.path("requests"))if(r.path("ordinal").asInt()==ordinal)row=r;if(row==null)throw notFound("Unknown task");
        JsonNode operation=null;for(JsonNode o:d.result.path("operations"))if("query".equals(o.path("operation").asText())&&o.path("ordinal").asInt()==ordinal)operation=o;
        if(operation==null)throw notFound("Task result not recorded");JsonNode saved=read(descriptor(operation.path("artifact")));JsonNode hits=saved.path("response").path("hits");
        if("source_units".equals(stage))return sourceUnitStage(d,row,saved,page,size);
        List<ObjectNode> ordered=new ArrayList<>();Map<String,Double> scores=new HashMap<>();String reason=null;boolean stageRecorded=false;
        JsonNode rerankPayload=stagePayload(d,ordinal,"reranker-output");
        if("dense".equals(stage)){JsonNode payload=stagePayload(d,ordinal,"dense-parent-candidates");stageRecorded=payload.path("orderedParents").isArray();for(JsonNode v:payload.path("orderedParents"))ordered.add((ObjectNode)v.deepCopy());}
        else if("bm25".equals(stage)){JsonNode payload=stagePayload(d,ordinal,"parent-bm25-candidates");stageRecorded=payload.path("allScopeScores").isArray()&&payload.path("orderedCandidateIds").isArray();for(JsonNode v:payload.path("allScopeScores"))scores.put(v.path("id").asText(),v.path("score").asDouble());for(JsonNode id:payload.path("orderedCandidateIds")){ObjectNode x=JSON.createObjectNode();x.put("id",id.asText());x.put("score",scores.get(id.asText()));ordered.add(x);}}
        else if("rrf".equals(stage)){JsonNode payload=stagePayload(d,ordinal,"parent-rrf-candidates");stageRecorded=payload.path("allFusedInOrder").isArray();for(JsonNode v:payload.path("allFusedInOrder"))ordered.add((ObjectNode)v.deepCopy());}
        else if("rerank".equals(stage)) {
            JsonNode payload=rerankPayload;JsonNode ids=payload.path("candidateIdentitiesInInputOrder"),ss=payload.path("scoresInCandidateOrder");
            stageRecorded=ids.isArray()&&ss.isArray();
            if(ids.size()!=ss.size())throw new IOException("Rerank score binding differs");for(int i=0;i<ids.size();i++){ObjectNode x=(ObjectNode)ids.get(i).deepCopy();x.put("score",ss.get(i).asDouble());x.put("beforeRank",i+1);ordered.add(x);}ordered.sort((a,b)->Double.compare(b.path("score").asDouble(),a.path("score").asDouble()));
        } else if("selected".equals(stage)) {stageRecorded=hits.isArray();for(JsonNode hit:hits)ordered.add((ObjectNode)hit.deepCopy());reason="retrieval_returned_parents_only_not_semantic_context_selection";}
        else if("sent".equals(stage))reason="this_retrieval_run_did_not_record_generation_dispatch_context";
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown ranking stage");
        Set<String> returned=new HashSet<>();for(JsonNode h:hits)returned.add(h.path("id").asText());
        boolean rerankerInputRecorded=rerankPayload.path("candidateIdentitiesInInputOrder").isArray();Set<String> rerankerInput=new HashSet<>();for(JsonNode identity:rerankPayload.path("candidateIdentitiesInInputOrder"))rerankerInput.add(identity.path("id").asText());
        for(int i=0;i<ordered.size();i++){ObjectNode x=ordered.get(i);String id=x.path("id").asText();boolean wasReturned=returned.contains(id);x.put("rank",i+1);x.put("afterRank",i+1);x.put("returnedByRetrieval",wasReturned);if(rerankerInputRecorded)x.put("inRerankerInput",rerankerInput.contains(id));else x.putNull("inRerankerInput");x.set("requestedCandidates",row.path("request").path("candidates"));x.put("dropReason",wasReturned?"":!rerankerInputRecorded?"reranker_input_not_recorded":rerankerInput.contains(id)?(saved.path("response").path("sourceUnits").isObject()?"not_selected_by_structural_unit_policy":"outside_final_retrieval_limit"):"not_in_reranker_input");x.put("datasetId",d.id);}
        boolean missing="sent".equals(stage)||!stageRecorded;if(missing&&reason==null)reason="this_retrieval_run_did_not_record_requested_ranking_stage";Map<String,Object> out=page(ordered,page,size,missing?"unavailable":"available",reason);out.put("query",row.path("request").path("query").asText());out.put("role",row.path("request").path("role").asText());out.put("stage",stage);out.put("provenance",provenance(d));out.put("scoreMeaning",saved.path("response").path("scoreMeaning"));out.put("finalRetrievalLimit",row.path("request").path("limit"));out.put("requestedCandidates",row.path("request").path("candidates"));return out;
    }

    /** Unit rows are transport descriptors, not ranked hits or reviewer dispatches. */
    private Map<String,Object> sourceUnitStage(Dataset d,JsonNode row,JsonNode saved,int page,int size)throws IOException {
        JsonNode response=saved.path("response"),episode=response.path("sourceUnits"),hits=response.path("hits"),units=episode.path("units");
        boolean recorded=episode.isObject()&&units.isArray();List<Object> items=new ArrayList<>();
        if(recorded){
            if(!VettingInspectionRetrievalBinding.SOURCE44_POLICY.equals(episode.path("policy").asText())||units.size()!=hits.size())throw new IOException("Source unit stage policy/seed mapping differs");
            for(int i=0;i<units.size();i++){
                JsonNode unit=units.get(i),seed=hits.get(i).path("payload");String id=hits.get(i).path("id").asText();
                if(!JSON.valueToTree(Collections.singletonList(id)).equals(unit.path("originIds")))throw new IOException("Source unit stage lost its canonical scored seed");
                items.add(map("id",id,"seedId",id,"unitId",unit.path("unitId"),"filename",seed.path("fileName"),"documentId",seed.path("documentId"),"sourceHash",seed.path("sourceHash"),
                        "chunk",seed.deepCopy(),"sourceUnit",unit.deepCopy(),"score",null,"rank",null,"transportOrdinal",i+1,"datasetId",d.id,
                        "seededRankingLabel","sourceUnit.originalRerankOrdinal and sourceUnit.seedScore describe only the actual scored seed",
                        "memberBoundary","attached_unranked_observed_source_only_not_semantic_selection_or_sent"));
            }
        }
        Map<String,Object> out=page(items,page,size,recorded?"available":"unavailable",recorded?null:"this_retrieval_run_did_not_record_source_unit_transport");
        out.put("query",row.path("request").path("query").asText());out.put("role",row.path("request").path("role").asText());out.put("stage","source_units");out.put("provenance",provenance(d));
        out.put("selectionPolicy",episode.path("policy"));out.put("scoreMeaning","No unit/member ranking score. The original sourceUnit tree records the seed score only.");
        out.put("derivedMembersCanBecomeOrigins",false);out.put("semanticScopeVerified",false);out.put("qualifiersComplete","unknown");out.put("generationDispatchRecorded",false);return out;
    }
}
