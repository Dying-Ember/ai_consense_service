package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.config.ConsenseProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalEvidenceBackend;
import com.consense.service.sources.NativeDocumentCollectionContract;
import com.consense.service.sources.NativeCanonicalContract;
import com.consense.service.sources.NativePdfCanonicalContract;
import com.consense.service.sources.NativePdfSourceFamilyContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import okhttp3.HttpUrl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Lossless canonical wire. Callers supply current bindings from CanonicalEvidenceService. */
@Component
public class OerCanonicalRetrievalClient {
    private static final String INPUT = "canonical-source-chunks-upstream-scope-and-parser-projection-v1";
    private static final String ASSETS = "source-and-projection-bound-content-addressed-assets-v1";
    private static final String RECIPE = "scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1";
    private static final String INDEX_STATUS = "read-only-existing-canonical-index-v1";
    private static final String SOURCE_PAGE_INDEX = "explicit-composite-index-pins-v1";
    private static final List<String> SCOPE = Arrays.asList("documentId", "sourceHash", "role", "metadataVersion",
            "segmentationVersion", "nativeTableMetadataVersion", "sourceQualityMetadataVersion", "sourceQualityHash");
    private final HttpSupport http;
    private final ConsenseProperties properties;
    private final CanonicalEvidenceProperties evidence;
    private final CanonicalEvidenceBackend backend;
    private final Map<String, ReceiptState> receiptStates = new ConcurrentHashMap<>();

    /** A key has one invalidation generation, including work already awaiting HTTP. */
    private static final class ReceiptState {
        long generation;
        boolean invalidated;
        Receipt receipt;
        String context;
    }
    private static final class Operation {
        final ReceiptState state;
        final long generation;
        final Receipt receipt;
        final boolean indexing,observing;
        Operation(ReceiptState state,boolean indexing,boolean observing){
            this.state=state;this.generation=state.generation;this.receipt=state.receipt;this.indexing=indexing;this.observing=observing;
        }
    }
    private Operation beginOperation(String key,boolean indexing){
        return beginOperation(key,indexing,false);
    }
    private Operation beginOperation(String key,boolean indexing,boolean observing){
        ReceiptState state=receiptStates.computeIfAbsent(key,ignored->new ReceiptState());
        synchronized(state){
            if(indexing){state.generation++;state.invalidated=true;state.receipt=null;state.context=null;}
            else require(observing||!state.invalidated,"canonical_index_receipt_invalidated");
            return new Operation(state,indexing,observing);
        }
    }
    private void invalidate(Operation operation){
        if(operation==null)return;
        synchronized(operation.state){
            // An expired response must not erase a later successful explicit index.
            if(operation.state.generation!=operation.generation)return;
            operation.state.generation++;operation.state.invalidated=true;
            operation.state.receipt=null;operation.state.context=null;
        }
    }
    private static void currentOperation(Operation operation){
        require(operation.state.generation==operation.generation,"canonical_index_operation_invalidated");
        require(operation.indexing||!operation.state.invalidated,"canonical_index_receipt_invalidated");
    }
    private void bindContext(Operation operation,String module,Corpus corpus,Capability health){
        String context=JsonUtils.write(Arrays.asList(module,corpus.fingerprint,corpus.localBindingFingerprint,
                health.fingerprint,health.sourceUnitPolicy,corpus.localRuntimeFingerprint));
        synchronized(operation.state){
            require(operation.state.generation==operation.generation,"canonical_index_operation_invalidated");
            require(operation.indexing||operation.observing||!operation.state.invalidated,"canonical_index_receipt_invalidated");
            if(!corpus.localRuntimeFingerprint.equals(localRuntimeFingerprint(module))){
                invalidate(operation);throw new IllegalStateException("current canonical runtime configuration changed");
            }
            if(operation.state.context!=null&&!operation.state.context.equals(context)){
                invalidate(operation);throw new IllegalStateException("current canonical corpus or recipe changed");
            }
            if(operation.indexing||!operation.state.invalidated)operation.state.context=context;
        }
    }
    private static boolean sameReceipt(Receipt first,Receipt second){
        return first!=null&&second!=null&&first.signature.equals(second.signature)&&first.fingerprint.equals(second.fingerprint)
                &&first.health.equals(second.health)&&first.module.equals(second.module)&&first.sourceUnitPolicy.equals(second.sourceUnitPolicy);
    }
    private IndexState publishReceipt(Operation operation,Receipt receipt,Corpus corpus,List<CanonicalSourceBinding> current,String module){
        synchronized(operation.state){
            localAdmission(operation,corpus,current,module);
            if(!operation.indexing&&operation.state.receipt!=null&&!sameReceipt(operation.state.receipt,receipt)){
                invalidate(operation);throw new IllegalStateException("canonical index signature changed");
            }
            operation.state.receipt=receipt;
            if(operation.indexing)operation.state.invalidated=false;
            return new IndexState(true,receipt.signature,corpus.chunks.size(),null);
        }
    }
    private JsonNode publishQuery(Operation operation,JsonNode result,Corpus corpus,List<CanonicalSourceBinding> current,String module){
        JsonNode output=result.deepCopy();
        synchronized(operation.state){
            try {
                localAdmission(operation,corpus,current,module);
                require(sameReceipt(operation.receipt,operation.state.receipt),"no current canonical index receipt");
                return output;
            }catch(RuntimeException invalid){invalidate(operation);throw invalid;}
        }
    }

    /** Pure local admission under the publication lock; this never starts another HTTP wait. */
    private void localAdmission(Operation operation,Corpus corpus,List<CanonicalSourceBinding> current,String module){
        currentOperation(operation);validateLocalSources(corpus,current);
        require(corpus.localRuntimeFingerprint.equals(localRuntimeFingerprint(module)),"current canonical runtime configuration changed");
    }

    /** Snapshot strings are immutable. Retain them exactly, without escaping them into another large JSON. */
    private static final class LocalBindingState {
        final List<Object> fields;
        final String snapshot;
        LocalBindingState(CanonicalSourceBinding binding){fields=localBindingFields(binding);snapshot=binding.getSnapshotJson();}
        boolean matches(CanonicalSourceBinding binding){return binding!=null&&fields.equals(localBindingFields(binding))
                &&Objects.equals(snapshot,binding.getSnapshotJson());}
    }
    private static List<Object> localBindingFields(CanonicalSourceBinding binding){
        return Arrays.asList(binding.getId(),binding.getProjectId(),binding.getDocumentId(),binding.getProjectionSha256(),
                binding.getOriginalSourceSha256(),binding.getJavaParsedSourceHash(),binding.getRoutingSha256(),
                binding.getSourceRevisionSha256(),binding.getProjectionRevisionSha256(),binding.getCanonicalSourceHash(),
                binding.getScopePolicySha256(),binding.getAdoptionManifestSha256(),binding.getReviewReferenceSha256(),
                binding.getFamilyCompositionSha256(),binding.getFamilyComponentSetSha256(),
                binding.getSnapshotBytesSha256(),binding.getDeclaredModule(),binding.getEligibleModulesJson(),
                binding.getCreatedAt()==null?null:binding.getCreatedAt().toString());
    }
    private void validateLocalSources(Corpus corpus,List<CanonicalSourceBinding> current){
        require(current!=null&&corpus.localBindings.size()==current.size(),"current canonical selected sources changed");
        for(int ordinal=0;ordinal<current.size();ordinal++)
            require(corpus.localBindings.get(ordinal).matches(current.get(ordinal)),"current canonical stored binding changed");
        require(corpus.sourcePageEvidence.equals(sourcePageEvidence(current)),"current source page evidence changed");
    }
    private String localRuntimeFingerprint(String module){
        ConsenseProperties.Vetting expected=properties.getVetting();
        return hash(JsonUtils.write(Arrays.asList(url(module,"/health"),expected.getExpectedRetrievalContract(),
                expected.getExpectedRetrievalSignatureVersion(),expected.getExpectedRetrievalGranularity(),
                expected.getExpectedEmbeddingWindowMaxTokens(),expected.getExpectedRerankWindowMaxTokens(),expected.getExpectedWindowOverlapTokens(),
                expected.getExpectedStructuralEmbeddingMaxTokens(),expected.getExpectedStructuralRerankMaxTokens(),expected.isExpectedStructuralRerankStrict(),
                expected.getExpectedRetrievalRecipeFingerprint(),expected.getExpectedRetrievalSignature())));
    }

    @Autowired public OerCanonicalRetrievalClient(HttpSupport http,ConsenseProperties properties,CanonicalEvidenceProperties evidence,CanonicalEvidenceBackend backend){
        this.http=http;this.properties=properties;this.evidence=evidence;this.backend=backend;
    }
    /** Existing direct fixtures keep their constructor; collection HTTP still uses the configured bounded backend. */
    public OerCanonicalRetrievalClient(HttpSupport http,ConsenseProperties properties,CanonicalEvidenceProperties evidence){
        this(http,properties,evidence,new CanonicalEvidenceBackend(evidence));
    }

    private static class Corpus {
        final List<String> projections = new ArrayList<>();
        final Map<String, JsonNode> chunks = new LinkedHashMap<>();
        final Map<String, String> sourcePageEvidence = new TreeMap<>();
        final List<LocalBindingState> localBindings=new ArrayList<>();
        String policy, fingerprint,localBindingFingerprint,localRuntimeFingerprint;
        boolean nativeSources,collectionSources,nativePdfSources,familySources;
    }
    public boolean attachmentContextEnabled(){return VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_CONTRACT.equals(properties.getVetting().getExpectedRetrievalContract());}
    /** Fresh source-only capability. An existing index must still match the current source and health. */
    public String sourceUnitPolicy(String indexKey,String module,CanonicalEvidenceService.VerifiedBindingFrame frame){
        return sourceUnitPolicy(indexKey,module,frame.bindings(),frame);
    }
    public String sourceUnitPolicy(String indexKey,String module,List<CanonicalSourceBinding> current){return sourceUnitPolicy(indexKey,module,current,null);}
    private String sourceUnitPolicy(String indexKey,String module,List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        Operation operation=beginOperation(indexKey,false);Receipt receipt=operation.receipt;
        try{
            Corpus corpus=corpus(current,module,frame);
            Capability live=stageHealth(frame,false,module,corpus);
            bindContext(operation,module,corpus,live);
            if(receipt!=null){
                require(module.equals(receipt.module)&&corpus.fingerprint.equals(receipt.fingerprint),"current canonical corpus changed");
                require(receipt.health.equals(live.fingerprint)&&receipt.sourceUnitPolicy.equals(live.sourceUnitPolicy),"canonical recipe changed after index");
            }
            require(VettingRetrievalSourceUnits.supportsPolicy(live.sourceUnitPolicy),"canonical source-unit capability is unknown");
            revalidateCurrent(operation,module,current,frame,corpus,live);
            synchronized(operation.state){localAdmission(operation,corpus,current,module);return live.sourceUnitPolicy;}
        }catch(RuntimeException invalid){invalidate(operation);throw invalid;}
    }
    private static class Receipt {
        final String signature, fingerprint, health, module, sourceUnitPolicy;
        Receipt(String signature, String fingerprint, String health, String module,String sourceUnitPolicy) {
            this.signature=signature; this.fingerprint=fingerprint; this.health=health; this.module=module;this.sourceUnitPolicy=sourceUnitPolicy;
        }
    }
    private static final class Capability {
        final String fingerprint,sourceUnitPolicy,indexStatusContract;
        Capability(String fingerprint,String policy,String indexStatusContract){this.fingerprint=fingerprint;this.sourceUnitPolicy=policy;this.indexStatusContract=indexStatusContract;}
    }
    public static final class IndexState {
        public final boolean ready;
        public final String signature,reason;
        public final int chunkCount;
        public IndexState(boolean ready,String signature,int chunks,String reason){this.ready=ready;this.signature=signature;this.chunkCount=chunks;this.reason=reason;}
    }
    /** Current source and live module/recipe status, independent of legacy vector counts. */
    public IndexState indexStatus(String key,String module,CanonicalEvidenceService.VerifiedBindingFrame frame){
        return indexStatus(key,module,frame.bindings(),frame);
    }
    public IndexState indexStatus(String key,String module,List<CanonicalSourceBinding> current){return indexStatus(key,module,current,null);}
    private IndexState indexStatus(String key,String module,List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        Corpus corpus=null;Operation operation=null;
        try {
            operation=beginOperation(key,false,true);Receipt receipt=operation.receipt;
            corpus=corpus(current,module,frame);
            Capability currentHealth=stageHealth(frame,false,module,corpus);
            bindContext(operation,module,corpus,currentHealth);
            synchronized(operation.state){
                require(operation.state.generation==operation.generation,"canonical_index_operation_invalidated");
                if(operation.state.invalidated)return new IndexState(false,null,corpus.chunks.size(),"canonical_index_receipt_invalidated");
            }
            if(receipt!=null){
                require(module.equals(receipt.module)&&corpus.fingerprint.equals(receipt.fingerprint),"current canonical corpus changed");
                require(receipt.health.equals(currentHealth.fingerprint)&&receipt.sourceUnitPolicy.equals(currentHealth.sourceUnitPolicy),"canonical recipe changed after index");
            }
            require(INDEX_STATUS.equals(currentHealth.indexStatusContract),"read-only canonical index status capability missing");
            ObjectNode request=JsonUtils.mapper().createObjectNode();request.put("projectId",key);request.put("module",module);
            ArrayNode addresses=request.putArray("projectionSha256");corpus.projections.forEach(addresses::add);
            if(!corpus.sourcePageEvidence.isEmpty())request.set("sourcePageEvidence",JsonUtils.mapper().valueToTree(corpus.sourcePageEvidence));
            if(frame!=null)frame.beforeWait();
            JsonNode result=statusReceipt(http.postJson(url(module,"/index-canonical/status"),JsonUtils.write(request),properties.getVetting().getRetrievalIndexTimeoutMs()));
            require(result.isObject()&&key.equals(result.path("projectId").asText())
                    &&result.path("ready").isBoolean()&&result.path("ready").asBoolean()
                    &&result.path("readOnly").isBoolean()&&result.path("readOnly").asBoolean()
                    &&INDEX_STATUS.equals(result.path("indexStatusContract").asText())
                    &&integer(result.path("indexed"))&&result.path("indexed").asInt()==corpus.chunks.size()
                    &&integer(result.path("vectorPoints"))&&result.path("vectorPoints").asInt()>=0
                    &&sha(result.path("signature").asText())&&module.equals(result.path("targetModule").asText())
                    &&properties.getVetting().getExpectedRetrievalContract().equals(result.path("contract").asText())
                    &&addresses.equals(result.path("canonicalProjections")),"canonical status identity differs");
            validateIndexPins(result,corpus.sourcePageEvidence);scope(result.path("competitionScope"),module,corpus.policy);
            String frozen=properties.getVetting().getExpectedRetrievalSignature();
            require(JsonUtils.isBlankText(frozen)||frozen.equals(result.path("signature").asText()),"frozen index signature differs");
            require(receipt==null||receipt.signature.equals(result.path("signature").asText()),"canonical index signature changed");
            revalidateCurrent(operation,module,current,frame,corpus,currentHealth);
            Receipt recovered=new Receipt(result.path("signature").asText(),corpus.fingerprint,currentHealth.fingerprint,module,currentHealth.sourceUnitPolicy);
            return publishReceipt(operation,recovered,corpus,current,module);
        } catch(RuntimeException unavailable){invalidate(operation);return new IndexState(false,null,corpus==null?0:corpus.chunks.size(),unavailable.getMessage());}
    }

    /** Complete JSON is authoritative; no old Chunk/Part DTO conversion occurs. */
    public void index(String indexKey,String module,CanonicalEvidenceService.VerifiedBindingFrame frame){
        index(indexKey,module,frame.bindings(),frame);
    }
    public void index(String indexKey, String module, List<CanonicalSourceBinding> current){index(indexKey,module,current,null);}
    private void index(String indexKey, String module, List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        Operation operation=beginOperation(indexKey,true);
        try {
        Corpus corpus = corpus(current,module,frame);
        Capability health = stageHealth(frame,true,module,corpus);
        bindContext(operation,module,corpus,health);
        ObjectNode request = JsonUtils.mapper().createObjectNode();
        request.put("projectId", indexKey); request.put("module", module);
        ArrayNode addresses = request.putArray("projectionSha256"); corpus.projections.forEach(addresses::add);
        if(!corpus.sourcePageEvidence.isEmpty())request.set("sourcePageEvidence",JsonUtils.mapper().valueToTree(corpus.sourcePageEvidence));
        if(frame!=null)frame.beforeWait();
        JsonNode result = JsonUtils.parse(http.postJson(url(module,"/index-canonical"), JsonUtils.write(request),
                properties.getVetting().getRetrievalIndexTimeoutMs()));
        require(indexKey.equals(result.path("projectId").asText()) && integer(result.path("indexed"))
                && result.path("indexed").asInt()==corpus.chunks.size() && sha(result.path("signature").asText())
                && VettingRetrievalHealth.structuralContract(result.path("contract").asText())
                && properties.getVetting().getExpectedRetrievalContract().equals(result.path("contract").asText())
                && module.equals(result.path("targetModule").asText())
                && addresses.equals(result.path("canonicalProjections")), "canonical index identity differs");
        validateIndexPins(result,corpus.sourcePageEvidence);
        require(corpus.sourcePageEvidence.equals(sourcePageEvidence(current)),"source page evidence changed during index");
        scope(result.path("competitionScope"), module, corpus.policy);
        String frozen=properties.getVetting().getExpectedRetrievalSignature();
        require(JsonUtils.isBlankText(frozen) || frozen.equals(result.path("signature").asText()), "frozen index signature differs");
        revalidateCurrent(operation,module,current,frame,corpus,health);
        publishReceipt(operation,new Receipt(result.path("signature").asText(),corpus.fingerprint,health.fingerprint,module,health.sourceUnitPolicy),corpus,current,module);
        }catch(RuntimeException invalid){invalidate(operation);throw invalid;}
    }

    /** Returned seeds stay ranked; closure members retain complete source JSON and unknowns. */
    public JsonNode retrieve(String indexKey,String module,String query,String role,CanonicalEvidenceService.VerifiedBindingFrame frame){
        return retrieve(indexKey,module,query,role,frame.bindings(),frame);
    }
    public JsonNode retrieve(String indexKey,String module,String query,String role,List<CanonicalSourceBinding> current){
        return retrieve(indexKey,module,query,role,current,null);
    }
    private JsonNode retrieve(String indexKey,String module,String query,String role,List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        Operation operation=beginOperation(indexKey,false);Receipt receipt=operation.receipt;
        require(receipt!=null, "no current canonical index receipt");
        Corpus corpus;Capability live;
        try {
            corpus=corpus(current,module,frame);
            require(module.equals(receipt.module) && corpus.fingerprint.equals(receipt.fingerprint), "current canonical corpus changed");
            live=stageHealth(frame,true,module,corpus);
            bindContext(operation,module,corpus,live);
            require(receipt.health.equals(live.fingerprint)&&receipt.sourceUnitPolicy.equals(live.sourceUnitPolicy), "canonical recipe changed after index");
        } catch (RuntimeException invalid) { invalidate(operation); throw invalid; }
        ConsenseProperties.Vetting config=properties.getVetting();
        require(config.getTopK()>0 && config.getTopK()<=config.getCandidateLimit(), "query output/candidate limits differ");
        ObjectNode request=JsonUtils.mapper().createObjectNode();
        request.put("projectId",indexKey); request.put("query",query); request.put("candidates",config.getCandidateLimit());
        request.put("limit",config.getTopK()); if(role!=null)request.put("role",role);
        if(frame!=null)frame.beforeWait();
        JsonNode result=JsonUtils.parse(http.postJson(url(module,"/retrieve"),JsonUtils.write(request),config.getRetrievalTimeoutMs()));
        try {require(corpus.sourcePageEvidence.equals(sourcePageEvidence(current)),"source page evidence changed during retrieval");
            revalidateCurrent(operation,module,current,frame,corpus,live);}
        catch(RuntimeException invalid){invalidate(operation);throw invalid;}
        require(indexKey.equals(result.path("projectId").asText()) && receipt.signature.equals(result.path("indexSignature").asText())
                && VettingRetrievalHealth.structuralContract(result.path("mode").asText())
                && properties.getVetting().getExpectedRetrievalContract().equals(result.path("mode").asText())
                && result.path("hits").isArray() && result.path("hits").size()<=config.getTopK(), "canonical response identity differs");
        scope(result.path("competitionScope"),module,corpus.policy);
        List<String> ranked = new ArrayList<>(); double previous=Double.POSITIVE_INFINITY;
        for(JsonNode hit:result.path("hits")) {
            String id=hit.path("id").asText(); JsonNode original=corpus.chunks.get(id);
            require(original!=null && !ranked.contains(id) && original.equals(hit.path("payload")), "canonical ranked payload differs");
            require(role==null || role.equals(original.path("role").asText()), "canonical ranked role differs");
            require(hit.path("score").isNumber() && Double.isFinite(hit.path("score").asDouble())
                    && hit.path("score").asDouble()<=previous, "canonical rerank scores/order invalid");
            previous=hit.path("score").asDouble(); ranked.add(id);
        }
        require(receipt.sourceUnitPolicy.equals(result.path("sourceUnits").path("policy").asText()),"canonical source-unit policy differs from indexed capability");
        validateUnits(result,corpus,ranked);
        return publishQuery(operation,result,corpus,current,module);
    }

    private void revalidateCurrent(Operation operation,String module,List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame,Corpus corpus,Capability health){
        if(frame!=null)frame.revalidate();
        Corpus after=corpus(current,module,frame);Capability afterHealth=stageHealth(frame,false,module,after);
        require(corpus.fingerprint.equals(after.fingerprint)&&health.fingerprint.equals(afterHealth.fingerprint)
                &&health.sourceUnitPolicy.equals(afterHealth.sourceUnitPolicy),"canonical sources or runtime changed during operation");
        // This is the final health wait. A frame re-reads committed DB/source and
        // full CAS through its existing post-wait guards; the List seam then uses
        // exact retained local bytes/fields. Do not append another health request.
        if(frame!=null)frame.revalidate();
        validateLocalSources(corpus,current);
        bindContext(operation,module,after,afterHealth);
    }

    private Capability stageHealth(CanonicalEvidenceService.VerifiedBindingFrame frame,boolean anotherWait,String module,Corpus corpus){
        if(frame!=null)frame.beforeWait();
        try{return health(module,corpus.policy,corpus.nativeSources,corpus.collectionSources,corpus.nativePdfSources,corpus.familySources,!corpus.sourcePageEvidence.isEmpty());}
        finally{if(frame!=null&&anotherWait)frame.revalidate();}
    }

    private Corpus corpus(List<CanonicalSourceBinding> bindings,String module,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        require(Arrays.asList("drafting","vetting","advisory").contains(module) && bindings!=null && !bindings.isEmpty(), "canonical module/sources missing");
        Corpus out=new Corpus(); Set<Long> documents=new HashSet<>(); List<String> identities=new ArrayList<>();
        out.localRuntimeFingerprint=localRuntimeFingerprint(module);
        List<List<Object>> localIdentities=new ArrayList<>();
        for(CanonicalSourceBinding binding:bindings){
            require(binding!=null,"canonical stored binding is invalid");LocalBindingState retained=new LocalBindingState(binding);
            out.localBindings.add(retained);localIdentities.add(retained.fields);
        }
        out.localBindingFingerprint=hash(JsonUtils.write(localIdentities));
        out.sourcePageEvidence.putAll(sourcePageEvidence(bindings));
        for(CanonicalSourceBinding binding:bindings) {
            require(binding!=null && binding.getDocumentId()!=null && documents.add(binding.getDocumentId())
                    && sha(binding.getProjectionSha256()) && binding.getSnapshotJson()!=null
                    && hash(binding.getSnapshotJson()).equals(binding.getSnapshotBytesSha256()), "canonical stored binding is invalid");
            JsonNode snapshot=JsonUtils.parse(binding.getSnapshotJson()), identity=snapshot.path("projectionIdentity");
            boolean collection=NativeDocumentCollectionContract.isCollection(snapshot);
            boolean nativeDocx=NativeCanonicalContract.isNative(snapshot)||collection;
            boolean family=NativePdfSourceFamilyContract.isFamily(snapshot),nativePdf=!family&&NativePdfCanonicalContract.isNativePdf(snapshot);
            if(family){
                CanonicalEvidenceService.validateSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());
                CanonicalEvidenceService.validateBindingPins(binding,snapshot);out.familySources=true;
                JsonNode live=frame==null?CanonicalEvidenceService.validateSnapshot(backend.snapshot(binding.getProjectionSha256(),module),binding.getProjectionSha256())
                        :frame.snapshot(binding.getDocumentId(),binding.getProjectionSha256(),module);
                require(live.equals(snapshot),"canonical stored native PDF family changed");
            }else if(nativePdf){
                CanonicalEvidenceService.validateSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());out.nativePdfSources=true;
                JsonNode live=frame==null?CanonicalEvidenceService.validateSnapshot(backend.snapshot(binding.getProjectionSha256(),module),binding.getProjectionSha256())
                        :frame.snapshot(binding.getDocumentId(),binding.getProjectionSha256(),module);
                require(live.equals(snapshot),"canonical stored native PDF source changed");
            }
            if(nativeDocx){CanonicalEvidenceService.validateSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());out.nativeSources=true;}
            JsonNode parents=snapshot.path("chunks");
            if(collection){
                out.collectionSources=true;
                if(frame!=null)parents=frame.snapshot(binding.getDocumentId(),binding.getProjectionSha256(),module).path("chunks");
                else {
                    String current=backend.snapshot(binding.getProjectionSha256(),module);
                    JsonNode live=CanonicalEvidenceService.validateSnapshot(current,binding.getProjectionSha256());
                    require(live.equals(snapshot),"canonical stored collection root changed");
                    parents=NativeDocumentCollectionContract.readDocument(current,binding.getProjectionSha256(),
                            ordinal->backend.batch(binding.getProjectionSha256(),ordinal,module).bytes,
                            ordinal->backend.envelope(binding.getProjectionSha256(),ordinal,module).bytes).publicProjection.path("chunks");
                }
            }
            require((family||nativePdf||nativeDocx||"consense.canonical-projection.v1".equals(snapshot.path("schema").asText()))
                    && binding.getProjectionSha256().equals(snapshot.path("projectionSha256").asText())
                    && String.valueOf(binding.getDocumentId()).equals(identity.path("documentId").asText())
                    && binding.getOriginalSourceSha256().equals(snapshot.path("source").path("sourceSha256").asText())
                    && binding.getCanonicalSourceHash().equals(snapshot.path("sourceHash").asText())
                    && contains(identity.path("eligibleModules"),module), "canonical envelope/source/module differs");
            require(parents.isArray() && parents.size()>0, "canonical chunks are missing");
            String policy=snapshot.path(collection?"manifestMetadata":"manifest").path("scope").path("scopePolicySha256").asText();
            require(sha(policy) && (out.policy==null || out.policy.equals(policy)), "canonical sources use different scope policies"); out.policy=policy;
            for(JsonNode chunk:parents) {
                String id=chunk.path("id").asText();
                require(!id.isEmpty() && !out.chunks.containsKey(id) && contains(chunk.path("eligibleModules"),module)
                        && identity.path("documentId").equals(chunk.path("documentId"))
                        && snapshot.path("sourceHash").equals(chunk.path("sourceHash"))
                        && binding.getOriginalSourceSha256().equals(chunk.path("originalSourceSha256").asText()), "canonical chunk identity differs");
                mapped(chunk); out.chunks.put(id,chunk);
            }
            out.projections.add(binding.getProjectionSha256());
            identities.add(JsonUtils.write(Arrays.asList(binding.getProjectId(),binding.getDocumentId(),binding.getJavaParsedSourceHash(),
                    binding.getRoutingSha256(),binding.getProjectionSha256(),binding.getSnapshotBytesSha256())));
        }
        validateLocalSources(out,bindings);
        out.fingerprint=hash(JsonUtils.write(out.sourcePageEvidence.isEmpty()?identities:Arrays.asList(identities,out.sourcePageEvidence))); return out;
    }

    /** Only selected, explicitly reviewed composites affect this index; sealed chunks stay unchanged. */
    private Map<String,String> sourcePageEvidence(List<CanonicalSourceBinding> bindings){
        Map<String,String> pins=new TreeMap<>();for(CanonicalSourceBinding binding:bindings){
            String identity=evidence.getSourcePageRenderEvidenceIdentities().get(binding.getProjectionSha256());
            if(identity!=null){require(sha(identity),"reviewed source page evidence identity invalid");pins.put(binding.getProjectionSha256(),identity);}
        }return pins;
    }
    private static void validateIndexPins(JsonNode result,Map<String,String> expected){
        JsonNode echo=result.path("sourcePageEvidence");
        if(expected.isEmpty()&&echo.isMissingNode())return;
        require(echo.isObject()&&JsonUtils.mapper().valueToTree(expected).equals(echo),"source page index evidence echo differs");
    }
    private Capability health(String module,String policy,boolean nativeSources,boolean collectionSources,boolean nativePdfSources,boolean familySources,boolean sourcePages) {
        JsonNode root=JsonUtils.parse(http.get(url(module,"/health"),properties.getVetting().getRetrievalTimeoutMs()));
        VettingRetrievalHealth.Identity base=VettingRetrievalHealth.validate(root,properties.getVetting());
        JsonNode oer=root.path("oer"), window=root.path("retrieval").path("sourceWindows");
        if(sourcePages)require(SOURCE_PAGE_INDEX.equals(oer.path("sourcePageIndexContract").asText()),"explicit source page index capability missing");
        require("cuda".equals(root.path("device").asText()), "canonical CUDA runtime required");
        for(String kind:Arrays.asList("embedding","reranker")) {
            JsonNode runtime=root.path("runtime").path(kind);
            require("cuda".equals(runtime.path("device").asText()) && "float32".equals(runtime.path("dtype").asText())
                    && runtime.path("serialOffload").isBoolean() && runtime.path("serialOffload").asBoolean(),
                    "canonical CUDA float32 serial runtime required: "+kind);
        }
        require(VettingRetrievalHealth.structuralContract(base.contract) && base.signatureVersion==4
                && INPUT.equals(oer.path("inputContract").asText()) && ASSETS.equals(oer.path("assetReturnContract").asText())
                && RECIPE.equals(oer.path("recipe").asText()) && module.equals(oer.path("targetModule").asText())
                && falseBoolean(oer.path("sourceQualityVerifiedByHealth")) && falseBoolean(oer.path("parserCandidatesAcceptedByRetrieval"))
                && "/project".equals(oer.path("canonicalProjectionEndpoint").asText())
                && policy.equals(oer.path("scopePolicy").path("manifestSha256").asText())
                && oer.path("scopePolicy").equals(window.path("competitionScope"))
                && INPUT.equals(window.path("oerInputContract").asText()) && module.equals(window.path("oerTargetModule").asText()),
                "canonical capability/scope handshake differs");
        for(String name:Arrays.asList("oer_server.py","competition_scope.py","artifact_store.py","structured_parser_evidence.py",
                "canonical_projection.py","oer_source_registry.py","oer_request_evidence.py"))
            require(sha(window.path("algorithms").path(name).asText()), "canonical algorithm identity missing: "+name);
        if(nativeSources){
            NativeCanonicalContract.validateCapability(oer);
            NativeCanonicalContract.validateAlgorithms(window.path("algorithms"));
        }
        if(collectionSources)NativeDocumentCollectionContract.validateCapability(oer,window.path("algorithms"));
        if(nativePdfSources){NativePdfCanonicalContract.validateCapability(oer);NativePdfCanonicalContract.validateAlgorithms(window.path("algorithms"));}
        if(familySources){NativePdfSourceFamilyContract.validateCapability(oer);NativePdfSourceFamilyContract.validateAlgorithms(window.path("algorithms"));}
        return new Capability(hash(base.fingerprint+JsonUtils.write(oer)),base.sourceUnitPolicy,oer.path("indexStatusContract").asText());
    }

    /** This endpoint returns only a small receipt; reject ambiguous or over-cap JSON. */
    private static JsonNode statusReceipt(String raw){
        require(raw!=null&&raw.length()<=16777216&&raw.getBytes(StandardCharsets.UTF_8).length<=16777216,"canonical status receipt exceeds bounded wire");
        try{return JsonUtils.mapper().copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);}
        catch(Exception invalid){throw new IllegalStateException("Canonical retrieval rejected: canonical status receipt is not complete strict JSON",invalid);}
    }

    private static void scope(JsonNode receipt,String module,String policy) {
        require(module.equals(receipt.path("targetModule").asText()) && INPUT.equals(receipt.path("inputContract").asText())
                && policy.equals(receipt.path("policyIdentity").path("manifestSha256").asText())
                && falseBoolean(receipt.path("sourceQualityChanged")), "canonical scope receipt differs");
    }
    private static void validateUnits(JsonNode result,Corpus corpus,List<String> ranked) {
        OerCanonicalSourceUnits.validate(result,corpus.chunks,ranked);
    }

    private static void mapped(JsonNode chunk) {
        require(chunk.path("content").isTextual() && chunk.path("parts").isArray() && chunk.path("parts").size()>0, "canonical text/parts missing");
        List<String> text=new ArrayList<>();
        for(JsonNode part:chunk.path("parts")) {
            require(part.path("text").isTextual() && part.path("blockId").isTextual() && !part.path("blockId").asText().isEmpty()
                    && part.path("anchor").isTextual() && !part.path("anchor").asText().isEmpty()
                    && integer(part.path("startOffset")) && integer(part.path("endOffset")) && part.path("startOffset").asInt()>=0
                    && part.path("endOffset").asInt()-part.path("startOffset").asInt()==part.path("text").asText().length(), "canonical UTF16 part mapping differs");
            text.add(part.path("text").asText());
        }
        require(String.join("\n",text).equals(chunk.path("content").asText()), "canonical source text was transformed");
        for(String field:SCOPE)require(chunk.path(field).isTextual() && !chunk.path(field).asText().isEmpty(), "canonical source identity missing: "+field);
    }
    private static ObjectNode identity(JsonNode chunk) { ObjectNode out=JsonUtils.mapper().createObjectNode(); for(String field:SCOPE)out.set(field,chunk.path(field)); return out; }
    private static boolean intersects(JsonNode parts,Set<String> blocks) { for(JsonNode p:parts)if(blocks.contains(p.path("blockId").asText()))return true; return false; }
    private static List<String> ids(JsonNode values) { require(values.isArray(),"canonical ID list missing"); List<String> out=new ArrayList<>(); for(JsonNode v:values){require(v.isTextual()&&!v.asText().isEmpty()&&!out.contains(v.asText()),"canonical IDs missing/duplicate"); out.add(v.asText());} return out; }
    private static boolean contains(JsonNode values,String text) { if(!values.isArray())return false; for(JsonNode v:values)if(v.isTextual()&&text.equals(v.asText()))return true; return false; }
    private String url(String module,String path) {
        String selected=evidence.getRetrievalBaseUrls().getOrDefault(module,evidence.getBaseUrl());
        HttpUrl base=HttpUrl.parse(selected==null?"":selected);
        require(base!=null && base.username().isEmpty() && base.password().isEmpty()
                && base.query()==null && base.fragment()==null && "/".equals(base.encodedPath()), "canonical configured backend URL invalid");
        return base.newBuilder().addPathSegments(path.substring(1)).build().toString();
    }
    private static boolean integer(JsonNode node) { return node.isIntegralNumber()&&node.canConvertToInt(); }
    private static boolean falseBoolean(JsonNode node) { return node.isBoolean()&&!node.asBoolean(); }
    private static boolean sha(String value) { return value!=null&&value.matches("[0-9a-f]{64}"); }
    private static String hash(String value) { try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder(); for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255)); return out.toString(); } catch(Exception invalid){throw new IllegalStateException(invalid);} }
    private static void require(boolean valid,String message) { if(!valid)throw new IllegalStateException("Canonical retrieval rejected: "+message); }
}
