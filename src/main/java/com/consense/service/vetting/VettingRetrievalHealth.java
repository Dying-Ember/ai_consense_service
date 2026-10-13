package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Validates the public, inference-free health recipe. It is not proof of vector/model quality. */
final class VettingRetrievalHealth {
    static final String WINDOW_CONTRACT = "source-bound-window-points-full-parent-results-v1";
    static final String STRUCTURAL_CONTRACT = "source-bound-dual-granularity-structure-results-v1";
    static final String ATTACHMENT_STRUCTURAL_CONTRACT = "source-bound-dual-granularity-structure-results-v2";
    static final String STRUCTURAL_VERSION = "observed-structure-dual-granularity-and-same-pool-rerank-v1";
    static final String TYPED_PARENT_STRUCTURAL_VERSION = "observed-structure-dual-granularity-typed-parent-context-v2";
    static final String ATTACHMENT_STRUCTURAL_VERSION = "observed-structure-dual-granularity-typed-parent-attachment-context-v3";
    static final String SHARED_NOTE_STRUCTURAL_VERSION="observed-structure-dual-granularity-typed-parent-shared-note-context-v4";
    static String structuralVersion(String policy){
        if(VettingRetrievalSourceUnits.SHARED_NOTE_POLICY.equals(policy))return SHARED_NOTE_STRUCTURAL_VERSION;
        if(VettingRetrievalSourceUnits.ATTACHMENT_POLICY.equals(policy))return ATTACHMENT_STRUCTURAL_VERSION;
        if(VettingRetrievalSourceUnits.TYPED_PARENT_POLICY.equals(policy))return TYPED_PARENT_STRUCTURAL_VERSION;
        return STRUCTURAL_VERSION;
    }
    static boolean structuralContract(String contract){return STRUCTURAL_CONTRACT.equals(contract)||ATTACHMENT_STRUCTURAL_CONTRACT.equals(contract);}
    static final class Identity {
        final String contract, fingerprint;
        final int signatureVersion;
        final String sourceUnitPolicy;
        Identity(String contract,int version,String fingerprint,String sourceUnitPolicy){this.contract=contract;this.signatureVersion=version;this.fingerprint=fingerprint;this.sourceUnitPolicy=sourceUnitPolicy;}
    }
    private VettingRetrievalHealth(){ }
    static Identity validate(JsonNode health,ConsenseProperties.Vetting expected) {
        require(health!=null&&health.isObject()&&"ok".equals(health.path("status").asText()),"health status is not ok");
        require(health.path("offline").isBoolean()&&health.path("offline").asBoolean(),"required local retrieval is not offline");
        require(expected.getExpectedRetrievalSignatureVersion()>0&&integer(health.path("indexSignatureVersion"))
                &&health.path("indexSignatureVersion").intValue()==expected.getExpectedRetrievalSignatureVersion(),"index signature version differs");
        JsonNode retrieval=health.path("retrieval"),window=retrieval.path("sourceWindows");
        require(retrieval.isObject()&&window.isObject()&&expected.getExpectedRetrievalContract()!=null
                &&expected.getExpectedRetrievalContract().equals(window.path("contract").asText()),"source-window contract differs");
        boolean structural=structuralContract(expected.getExpectedRetrievalContract());
        require(structural ? expected.getExpectedRetrievalSignatureVersion()==4&&"dual-structure-v1".equals(expected.getExpectedRetrievalGranularity())
                : WINDOW_CONTRACT.equals(expected.getExpectedRetrievalContract())&&expected.getExpectedRetrievalSignatureVersion()==3&&"windows-v1".equals(expected.getExpectedRetrievalGranularity()),
                "configured contract/version/granularity combination is unknown");
        require((structural?"independent_lane_rrf":"max").equals(window.path("denseAggregation").asText())
                &&(structural?"observed_structure_context_max_with_same_pool_baseline":"max").equals(window.path("rerankAggregation").asText()),"window aggregation is unknown");
        if(structural)validateStructureRecipe(window,expected);
        else require(!window.has("structuralRecipe")&&!window.has("longEmbeddingMaxTokens")&&!window.has("contextRerankMaxTokens"),"baseline contract must not advertise a structural recipe");
        require(integer(retrieval.path("rrfConstant"))&&retrieval.path("rrfConstant").intValue()>0,"RRF constant is invalid");
        for(String name:Arrays.asList("dense","bm25"))positiveNumber(retrieval.path("rrfWeights").path(name),"RRF weight "+name);
        require("rank_bm25.BM25Okapi".equals(retrieval.path("bm25").path("implementation").asText()),"BM25 implementation is unknown");
        positiveNumber(retrieval.path("bm25").path("k1"),"BM25 k1");positiveNumber(retrieval.path("bm25").path("epsilon"),"BM25 epsilon");
        JsonNode b=retrieval.path("bm25").path("b");require(b.isNumber()&&Double.isFinite(b.asDouble())&&b.asDouble()>=0&&b.asDouble()<=1,"BM25 b is invalid");
        require(retrieval.path("roleFilter").isBoolean()&&retrieval.path("roleFilter").asBoolean(),"role filtering is unavailable");
        require(text(retrieval.path("tokenizerAlgorithm"))&&text(retrieval.path("tokenPattern")),"lexical algorithm identity is unavailable");
        JsonNode models=health.path("models"),runtime=health.path("runtime");
        for(String kind:Arrays.asList("embedding","reranker")) {
            JsonNode model=models.path(kind),run=runtime.path(kind),recipe=window.path("embedding".equals(kind)?"embeddingWindows":"rerankWindows");
            int cap="embedding".equals(kind)?expected.getExpectedEmbeddingWindowMaxTokens():expected.getExpectedRerankWindowMaxTokens();
            require(cap>=4&&cap<=8192&&expected.getExpectedWindowOverlapTokens()>=0&&expected.getExpectedWindowOverlapTokens()<cap,"configured window limits are invalid");
            require(model.isObject()&&text(model.path("name"))&&text(model.path("revision")),"model revision is unavailable: "+kind);
            require(run.isObject()&&model.path("name").equals(run.path("name"))&&model.path("revision").equals(run.path("revision")),"runtime model identity differs: "+kind);
            require(integer(run.path("maxTokens"))&&run.path("maxTokens").intValue()==cap,"runtime maxTokens differs: "+kind);
            require(integer(run.path("batchSize"))&&run.path("batchSize").intValue()>0,"runtime batch size is invalid: "+kind);
            String device=run.path("device").asText(),dtype=run.path("dtype").asText();
            require(Arrays.asList("cpu","cuda").contains(device)&&Arrays.asList("float32","float16").contains(dtype)
                    &&!("cpu".equals(device)&&"float16".equals(dtype)),"runtime dtype/device is invalid: "+kind);
            require(run.path("serialOffload").isBoolean(),"runtime offload declaration is absent: "+kind);
            require(recipe.isObject()&&integer(recipe.path("max_tokens"))&&recipe.path("max_tokens").intValue()==cap
                    &&integer(recipe.path("overlap_tokens"))&&recipe.path("overlap_tokens").intValue()==expected.getExpectedWindowOverlapTokens()
                    &&integer(recipe.path("max_windows_per_parent"))&&recipe.path("max_windows_per_parent").intValue()>0
                    &&"max".equals(recipe.path("aggregation").asText())&&"source-bound-token-windows-v1".equals(recipe.path("version").asText()),"source window recipe differs: "+kind);
        }
        require(runtime.path("reranker").equals(window.path("rerankRuntime")),"rerank recipe/runtime identity differs");
        JsonNode algorithms=window.path("algorithms");require(algorithms.isObject(),"source algorithm fingerprints are absent");
        String sourceUnitPolicy=null;
        require(!structural||window.has("structuralSelection"),"structural seed selection is absent");
        if(window.has("structuralSelection")) {
            JsonNode selection=window.path("structuralSelection");sourceUnitPolicy=selection.path("policy").asText();
            require(VettingRetrievalSourceUnits.supportsPolicy(sourceUnitPolicy)&&"unique_reliable_structural_units_or_unknown_single_seeds".equals(selection.path("topKScope").asText())&&selection.path("closureMembersAreRanked").isBoolean()&&!selection.path("closureMembersAreRanked").asBoolean(),"structural seed selection contract differs");
            require(algorithms.path("retrieval_source_units.py").asText().matches("[0-9a-f]{64}"),"source-unit algorithm fingerprint is absent");
            if(structural)require("canonical_observed_structure_only".equals(selection.path("scope").asText()),"structural source scope differs");
            boolean attached=VettingRetrievalSourceUnits.attachmentPolicy(sourceUnitPolicy);
            boolean sharedNotes=VettingRetrievalSourceUnits.SHARED_NOTE_POLICY.equals(sourceUnitPolicy);
            require(attached==ATTACHMENT_STRUCTURAL_CONTRACT.equals(expected.getExpectedRetrievalContract()),"attachment source-unit policy/contract differs");
            if(VettingRetrievalSourceUnits.TYPED_PARENT_POLICY.equals(sourceUnitPolicy)||attached)
                require(structural&&OerCanonicalHierarchyContext.POLICY.equals(selection.path("hierarchyContextPolicy").asText()),"typed-parent context capability differs");
            else require(!selection.has("hierarchyContextPolicy"),"legacy selection must not advertise a hierarchy context");
            if(attached)require((sharedNotes?OerCanonicalAttachmentContext.SHARED_POLICY:OerCanonicalAttachmentContext.POLICY).equals(selection.path("attachmentContextPolicy").asText()),"attachment context capability differs");
            else require(!selection.has("attachmentContextPolicy"),"legacy selection must not advertise attachment context");
        }
        if(structural)for(String name:Arrays.asList("structural_retrieval.py","embedding_compatibility.py"))
            require(algorithms.path(name).isTextual()&&algorithms.path(name).asText().matches("[0-9a-f]{64}"),"structural algorithm fingerprint is invalid: "+name);
        for(String name:Arrays.asList("window_runtime.py","token_windows.py","fixed_vector_cache.py","server.py","workspace_root.py"))
            require(algorithms.path(name).isTextual()&&algorithms.path(name).asText().matches("[0-9a-f]{64}"),"source algorithm fingerprint is invalid: "+name);
        // Dynamic loaded flags/memory usage are excluded; effective recipe/model/source
        // identities are retained. Health checks do not load tokenizer/model weights.
        ObjectNode identity=JsonUtils.mapper().createObjectNode();identity.set("models",models);identity.set("runtime",runtime);
        identity.set("retrieval",retrieval);identity.set("indexSignatureVersion",health.path("indexSignatureVersion"));identity.set("offline",health.path("offline"));
        String fingerprint=VettingCorpus.hash(JsonUtils.write(sorted(identity)));
        String frozen=expected.getExpectedRetrievalRecipeFingerprint();
        require(JsonUtils.isBlankText(frozen)||frozen.matches("[0-9a-f]{64}")&&frozen.equals(fingerprint),"externally frozen recipe fingerprint differs");
        return new Identity(expected.getExpectedRetrievalContract(),expected.getExpectedRetrievalSignatureVersion(),fingerprint,sourceUnitPolicy);
    }
    private static void validateStructureRecipe(JsonNode window,ConsenseProperties.Vetting expected) {
        JsonNode recipe=window.path("structuralRecipe");
        Set<String> fields=new HashSet<>();recipe.fieldNames().forEachRemaining(fields::add);
        require(recipe.isObject()&&fields.equals(new HashSet<>(Arrays.asList("mode","long_embedding_tokens","context_rerank_tokens","strict_context","version")))
                &&"dual-structure-v1".equals(recipe.path("mode").asText())
                &&structuralVersion(window.path("structuralSelection").path("policy").asText()).equals(recipe.path("version").asText()),"structural recipe identity differs");
        int embedding=expected.getExpectedStructuralEmbeddingMaxTokens(),rerank=expected.getExpectedStructuralRerankMaxTokens();
        require(embedding>=expected.getExpectedEmbeddingWindowMaxTokens()&&embedding>=4&&embedding<=8192
                &&rerank>=expected.getExpectedRerankWindowMaxTokens()&&rerank>=4&&rerank<=8192,"configured structural limits are invalid");
        require(integer(recipe.path("long_embedding_tokens"))&&recipe.path("long_embedding_tokens").intValue()==embedding
                &&integer(window.path("longEmbeddingMaxTokens"))&&window.path("longEmbeddingMaxTokens").intValue()==embedding,"structural embedding maxTokens differs");
        require(integer(recipe.path("context_rerank_tokens"))&&recipe.path("context_rerank_tokens").intValue()==rerank
                &&integer(window.path("contextRerankMaxTokens"))&&window.path("contextRerankMaxTokens").intValue()==rerank,"structural rerank maxTokens differs");
        require(recipe.path("strict_context").isBoolean()&&recipe.path("strict_context").asBoolean()==expected.isExpectedStructuralRerankStrict(),"structural strict-context policy differs");
        require(window.path("tokenizerIdentityObserved").isBoolean()&&!window.path("tokenizerIdentityObserved").asBoolean()
                &&window.path("rerankTokenizerAndModelFiles").isNull()
                &&"configured recipe; tokenizer/model identity not loaded by health".equals(window.path("identityScope").asText()),"structural health tokenizer observation scope differs");
    }
    private static boolean text(JsonNode n){return n.isTextual()&&!n.asText().trim().isEmpty();}
    private static boolean integer(JsonNode n){return n.isIntegralNumber()&&n.canConvertToInt();}
    private static void positiveNumber(JsonNode n,String name){require(n.isNumber()&&Double.isFinite(n.asDouble())&&n.asDouble()>0,name+" is invalid");}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException("Required hybrid retrieval health handshake rejected: "+reason);}
    private static JsonNode sorted(JsonNode n){
        if(n.isObject()){ObjectNode o=JsonUtils.mapper().createObjectNode();TreeMap<String,JsonNode>m=new TreeMap<>();n.fields().forEachRemaining(e->m.put(e.getKey(),e.getValue()));m.forEach((k,v)->o.set(k,sorted(v)));return o;}
        if(n.isArray()){com.fasterxml.jackson.databind.node.ArrayNode a=JsonUtils.mapper().createArrayNode();n.forEach(v->a.add(sorted(v)));return a;}return n;
    }
}
