package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/** The Python process owns GPU models; Java always validates returned IDs against its snapshot. */
@Component @RequiredArgsConstructor
public class VettingRetrievalClient {
    private final HttpSupport http;
    private final ConsenseProperties props;
    private final Map<String, IndexReceipt> strictReceipts = new ConcurrentHashMap<>();
    private static class IndexReceipt {
        final String signature, corpusFingerprint;
        final VettingRetrievalHealth.Identity health;
        IndexReceipt(String signature, String corpusFingerprint, VettingRetrievalHealth.Identity health) {
            this.signature = signature; this.corpusFingerprint = corpusFingerprint; this.health=health;
        }
    }
    public void index(String projectId, List<Chunk> chunks) {
        index(projectId, chunks, null);
    }
    void index(String projectId, List<Chunk> chunks, VettingReviewProbe.Call probe) {
        // Invalidate first; a failed re-handshake must not reuse a former receipt.
        strictReceipts.remove(projectId);
        if (props.getVetting().isRequireHybridRetrieval()) requireUniqueIds(chunks);
        VettingRetrievalHealth.Identity health = props.getVetting().isRequireHybridRetrieval() ? health(probe) : null;
        String request;
        try (VettingReviewProbe.Timer timer=measure(probe,"index_request_serialization","Body construction and JsonUtils.write before HTTP; no probe artifact I/O")) {
            Map<String,Object> body = new LinkedHashMap<>(); body.put("projectId", projectId); body.put("chunks", chunks);
            request = JsonUtils.write(body);
        }
        if (probe != null) probe.text("index_request", VettingReviewProbe.map("sourceChunkCount", chunks.size(), "url", url("/index")), request);
        String response;
        try (VettingReviewProbe.Timer timer=measure(probe,"index_http","HttpSupport.postJson including request handling, wait and decoded response string; TCP bytes not independently observed here")) {
            response = http.postJson(url("/index"), request, props.getVetting().getRetrievalIndexTimeoutMs());
        }
        catch (Exception e) {
            if (probe != null) probe.event("index_failure", VettingReviewProbe.map("error", e.toString(), "indexReceiptValidated", false));
            throw e;
        }
        if (probe != null) probe.text("index_response", VettingReviewProbe.map("url", url("/index")), response);
        if (props.getVetting().isRequireHybridRetrieval()) {
            JsonNode receipt;
            String signature;
            try (VettingReviewProbe.Timer timer=measure(probe,"index_receipt_gate","Receipt JSON parse plus unique-ID/project/count/signature checks; excludes separately measured corpus fingerprint")) {
            requireUniqueIds(chunks);
            receipt = JsonUtils.parse(response);
            signature = receipt.path("signature").asText();
            if (!projectId.equals(receipt.path("projectId").asText()) || !receipt.path("indexed").isIntegralNumber() || !receipt.path("indexed").canConvertToInt()
                    || receipt.path("indexed").asInt() != chunks.size() || !signature.matches("[0-9a-f]{64}")
                    || !health.contract.equals(receipt.path("contract").asText()))
            {
                if (probe != null) probe.event("index_receipt_gate", VettingReviewProbe.map("status", "rejected", "reason", "project_count_or_signature_invalid", "receipt", receipt));
                throw new IllegalStateException("Required hybrid index receipt does not bind the complete project corpus");
            }
            String expected = props.getVetting().getExpectedRetrievalSignature();
            if (!JsonUtils.isBlankText(expected) && !expected.equals(signature)) {
                if (probe != null) probe.event("index_receipt_gate", VettingReviewProbe.map("status", "rejected", "reason", "externally_frozen_signature_mismatch", "receipt", receipt));
                throw new IllegalStateException("Required hybrid index signature differs from the externally frozen identity");
            }
            }
            String fingerprint;
            try (VettingReviewProbe.Timer timer=measure(probe,"index_corpus_fingerprint","Normalized complete original corpus fingerprint after valid index receipt")) {
                fingerprint=corpusFingerprint(chunks);
            }
            strictReceipts.put(projectId, new IndexReceipt(signature, fingerprint, health));
            if (probe != null) probe.event("index_receipt_gate", VettingReviewProbe.map("status", "accepted", "receipt", receipt,
                    "precomputed", receipt.path("precomputed").asBoolean(), "cacheMode", receipt.path("mode").asText(), "signature", signature));
        } else if (probe != null) {
            probe.event("index_receipt_gate", VettingReviewProbe.map("status", "observed_not_strictly_validated", "strictHybrid", false));
        }
    }
    public List<Chunk> retrieve(String projectId, String query, String role, List<Chunk> corpus) {
        return retrieve(projectId, query, role, corpus, null);
    }
    List<Chunk> retrieve(String projectId, String query, String role, List<Chunk> corpus, VettingReviewProbe.Call probe) {
        boolean strict = props.getVetting().isRequireHybridRetrieval();
        IndexReceipt receipt = strictReceipts.get(projectId);
        if (strict && receipt == null) {
            if (probe != null) probe.event("retrieve_identity_gate", VettingReviewProbe.map("status", "rejected", "reason", "no_current_index_receipt", "requestSent", false));
            throw new IllegalStateException("Required hybrid retrieval has no valid current-corpus index receipt");
        }
        boolean changed=false;
        if (strict) try (VettingReviewProbe.Timer timer=measure(probe,"retrieve_current_corpus_fingerprint","Complete current corpus fingerprint before sending this role query; no HTTP or model operation")) {
            changed=!receipt.corpusFingerprint.equals(corpusFingerprint(corpus));
        }
        if (changed) {
            strictReceipts.remove(projectId);
            if (probe != null) probe.event("retrieve_identity_gate", VettingReviewProbe.map("status", "rejected", "reason", "current_corpus_changed", "requestSent", false));
            throw new IllegalStateException("Required hybrid current corpus changed after its index handshake");
        }
        if (strict) {
            try {
                VettingRetrievalHealth.Identity current=health(probe);
                if(!receipt.health.fingerprint.equals(current.fingerprint))
                    throw new IllegalStateException("Required hybrid retrieval recipe changed after its index handshake");
            } catch(RuntimeException e) {strictReceipts.remove(projectId);throw e;}
        }
        String request,response;
        try (VettingReviewProbe.Timer timer=measure(probe,"retrieve_request_serialization","Exact role-query body construction and JsonUtils.write before HTTP")) {
            Map<String,Object> body = new LinkedHashMap<>(); body.put("projectId", projectId); body.put("query", query);
            if (role != null) body.put("role", role);
            body.put("limit", props.getVetting().getTopK()); body.put("candidates", props.getVetting().getCandidateLimit());
            request = JsonUtils.write(body);
        }
        if (probe != null) probe.text("retrieve_request", VettingReviewProbe.map("url", url("/retrieve")), request);
        try (VettingReviewProbe.Timer timer=measure(probe,"retrieve_http","HttpSupport.postJson including wait and decoded response string; cache-only response is not fresh model retrieval")) {
            response = http.postJson(url("/retrieve"), request, props.getVetting().getRetrievalTimeoutMs());
        }
        catch (Exception e) {
            if (probe != null) probe.event("retrieve_failure", VettingReviewProbe.map("error", e.toString(), "responseObserved", false));
            throw e;
        }
        if (probe != null) probe.text("retrieve_response", VettingReviewProbe.map("url", url("/retrieve")), response);
        JsonNode root;
        try (VettingReviewProbe.Timer timer=measure(probe,"retrieve_response_parse","JsonUtils.parse of the returned decoded response string")) {
            root = JsonUtils.parse(response);
        }
        if (strict) {
            try (VettingReviewProbe.Timer timer=measure(probe,"retrieve_response_identity_gate","Project/signature/mode/hit shape and limit validation; no reranking")) {
            if (!projectId.equals(root.path("projectId").asText()) || !receipt.signature.equals(root.path("indexSignature").asText())
                    || !receipt.health.contract.equals(root.path("mode").asText()) || !root.path("hits").isArray()
                    || root.path("hits").size() > props.getVetting().getTopK()) {
                if (probe != null) probe.event("retrieve_identity_gate", VettingReviewProbe.map("status", "rejected", "reason", "response_project_signature_mode_or_shape_mismatch", "response", root));
                throw new IllegalStateException("Required hybrid retrieval response has a different index identity or invalid shape");
            }
            if (probe != null) probe.event("retrieve_identity_gate", VettingReviewProbe.map("status", "accepted", "indexSignature", receipt.signature,
                    "precomputed", root.path("precomputed").asBoolean(), "cacheMode", root.path("cacheMode").asText()));
            }
        }
        Map<String,Chunk> byId;
        try (VettingReviewProbe.Timer timer=measure(probe,"retrieve_source_lookup_build","Current original chunk ID lookup construction")) {
            byId = corpus.stream().collect(Collectors.toMap(Chunk::getId, c -> c));
        }
        List<Chunk> out = new ArrayList<>();
        try (VettingReviewProbe.Timer timer=measure(probe,"retrieve_hit_gates","All visited hit identity/payload/role gates and output order construction; measured probe event I/O excluded")) {
        for (JsonNode hit : root.path("hits")) {
            long gateStarted=System.nanoTime();
            String id = hit.path("id").asText(hit.path("payload").path("id").asText());
            Chunk c = byId.get(id);
            List<String> reasons = new ArrayList<>();
            if (strict && (!hit.path("id").isTextual() || JsonUtils.isBlankText(hit.path("id").asText()))) reasons.add("missing_explicit_hit_id");
            if (c == null) reasons.add("unknown_current_source_id");
            if (c != null && out.contains(c)) reasons.add("duplicate_hit_id");
            if (c != null && role != null && !role.equals(c.getRole())) reasons.add("source_role_mismatch");
            if (strict && (!hit.path("score").isNumber() || !Double.isFinite(hit.path("score").asDouble()))) reasons.add("invalid_rerank_score");
            if (strict && c != null && !normalizedPayload(c).equals(hit.path("payload"))) reasons.add("original_payload_mismatch");
            long gateNanos=System.nanoTime()-gateStarted;
            if (probe != null) probe.event("hit_identity_gate", VettingReviewProbe.map("sourceChunkId", id,
                    "documentId", c == null ? null : c.getDocumentId(), "sourceHash", c == null ? null : c.getSourceHash(),
                    "sourceRole", c == null ? null : c.getRole(), "status", reasons.isEmpty() ? "accepted" : strict ? "rejected" : "filtered",
                    "reasons", reasons, "strictHybrid", strict, "rawHit", hit,"wallNanos",gateNanos,"seconds",gateNanos/1_000_000_000.0));
            if (strict && !reasons.isEmpty())
                throw new IllegalStateException("Required hybrid hit is not the current original source chunk for the requested role");
            if (c != null && (role == null || role.equals(c.getRole())) && !out.contains(c)) out.add(c);
        }
        }
        return VettingRetrievalSourceUnits.read(root,out,byId,role,strict&&receipt.health.sourceUnitPolicy!=null);
    }
    private static VettingReviewProbe.Timer measure(VettingReviewProbe.Call probe,String stage,String boundary) {
        return probe==null ? null : probe.measure(stage,boundary);
    }
    private VettingRetrievalHealth.Identity health(VettingReviewProbe.Call probe) {
        try {
        String response;
        try(VettingReviewProbe.Timer timer=measure(probe,"retrieval_health_http","Inference-free public Python health request; no tokenizer/index/query operation")) {
            response=http.get(url("/health"),props.getVetting().getRetrievalTimeoutMs());
        }
        if(probe!=null)probe.text("retrieval_health_response",VettingReviewProbe.map("url",url("/health")),response);
        VettingRetrievalHealth.Identity identity;
        try(VettingReviewProbe.Timer timer=measure(probe,"retrieval_health_gate","Effective source-window recipe/version/model/source fingerprints, independent of dynamic model-loaded/memory fields")) {
            identity=VettingRetrievalHealth.validate(JsonUtils.parse(response),props.getVetting());
        }
        if(probe!=null)probe.event("retrieval_health_gate",VettingReviewProbe.map("status","accepted","contract",identity.contract,"signatureVersion",identity.signatureVersion,"recipeFingerprint",identity.fingerprint));
        return identity;
        } catch (RuntimeException e) {
            if(probe!=null)probe.event("retrieval_health_gate",VettingReviewProbe.map("status","rejected","error",e.toString(),"indexOrQueryRequestSentByHealth",false));
            throw e;
        }
    }
    private String url(String path) { return props.getVetting().getRetrievalUrl().replaceAll("/+$", "") + path; }
    private static void requireUniqueIds(List<Chunk> chunks) {
        Set<String> ids = new HashSet<>();
        for (Chunk chunk : chunks) if (chunk == null || JsonUtils.isBlankText(chunk.getId()) || !ids.add(chunk.getId()))
            throw new IllegalStateException("Required hybrid corpus has a missing or duplicate chunk ID");
    }
    /** Same top-level null/page normalization as the existing Python Chunk DTO. */
    static JsonNode normalizedPayload(Chunk chunk) {
        ObjectNode original = JsonUtils.mapper().valueToTree(chunk);
        ObjectNode value = JsonUtils.mapper().createObjectNode();
        original.fields().forEachRemaining(field -> { if (!field.getValue().isNull()) value.set(field.getKey(), field.getValue()); });
        JsonNode page = value.get("pageNo");
        if (page != null && page.isTextual() && page.asText().matches("P?[1-9][0-9]*"))
            value.put("pageNo", Integer.parseInt(page.asText().replaceFirst("^P", "")));
        return value;
    }
    private static JsonNode sortedObjects(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JsonUtils.mapper().createObjectNode();
            TreeMap<String,JsonNode> fields = new TreeMap<>(); value.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, item) -> sorted.set(key, sortedObjects(item))); return sorted;
        }
        if (value.isArray()) {
            ArrayNode out = JsonUtils.mapper().createArrayNode(); value.forEach(item -> out.add(sortedObjects(item))); return out;
        }
        return value;
    }
    private static String corpusFingerprint(List<Chunk> corpus) {
        requireUniqueIds(corpus);
        // Hash chunks separately to avoid retaining a second complete 80 MB corpus tree.
        List<String> identities = corpus.stream().map(c -> c.getId() + ":"
                + VettingCorpus.hash(JsonUtils.write(sortedObjects(normalizedPayload(c)))))
                .sorted().collect(Collectors.toList());
        return VettingCorpus.hash(JsonUtils.write(identities));
    }
    public static List<Chunk> lexical(String query, List<Chunk> corpus, int limit) {
        Set<String> terms = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2).collect(Collectors.toSet());
        Map<String,Double> scores = new HashMap<>();
        for (String term : terms) {
            long df = corpus.stream().filter(c -> c.getContent().toLowerCase(Locale.ROOT).contains(term)).count();
            double idf = Math.log(1 + (double) corpus.size() / (1 + df));
            for (Chunk c : corpus) {
                if (c.getContent().toLowerCase(Locale.ROOT).contains(term)) scores.merge(c.getId(), idf, Double::sum);
            }
        }
        return corpus.stream().filter(c -> scores.getOrDefault(c.getId(), 0.0) > 0)
                .sorted(Comparator.<Chunk>comparingDouble(c -> scores.getOrDefault(c.getId(), 0.0)).reversed())
                .limit(limit).collect(Collectors.toList());
    }
}
