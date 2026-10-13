package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.common.BizException;
import com.consense.service.vetting.FixedBaselineRetrievalClient;
import com.consense.service.vetting.FixedReferenceEvidence;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

/** Read-only fixed-reference lane. No project document ID or user-provided file path is accepted. */
@RestController
@RequestMapping("/api/oer/fixed-baseline")
@RequiredArgsConstructor
public class FixedBaselineSourceController {
    private final FixedBaselineRetrievalClient fixed;

    @GetMapping("/status")
    public ApiResponse<Map<String,Object>> status() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("configured",fixed.enabled());
        result.put("role","standard");
        result.put("projectAdoptionRequired",true);
        result.put("retrievalLiveVerified",false);
        if(fixed.enabled()) {
            JsonNode manifest=fixed.frozenManifest();
            for(String key:Arrays.asList("version","developmentBaseline","finalParsingAccepted",
                    "businessAccepted","H800Accepted","scope","coverage","indexSignature",
                    "collection","persistedVectorCount","knownGaps"))
                if(manifest.has(key))result.put(key,manifest.get(key));
        }
        return ApiResponse.ok(result);
    }

    @Data public static class SearchRequest {
        private String module,query;
        private int limit=10;
    }

    /** A search computes a query only; it never publishes or changes the frozen index. */
    @PostMapping("/search")
    public ApiResponse<List<FixedReferenceEvidence>> search(@RequestBody SearchRequest request) {
        if(request==null||!Arrays.asList("vetting","advisory").contains(request.getModule())
                ||request.getQuery()==null||request.getQuery().trim().isEmpty()
                ||request.getQuery().length()>20000||request.getLimit()<1||request.getLimit()>10)
            throw new BizException(4001,"Provide module vetting/advisory, a query and limit 1..10.");
        return ApiResponse.ok(fixed.searchEvidence(request.getModule(),request.getQuery(),request.getLimit()));
    }

    @GetMapping("/evidence")
    public ApiResponse<FixedReferenceEvidence> evidence(@RequestParam String id) {
        return ApiResponse.ok(fixed.evidence(fixed.readOnlyHitById(id)));
    }

    @GetMapping("/original")
    public ResponseEntity<ByteArrayResource> original(@RequestParam String sourceSha256) throws IOException {
        if(sourceSha256==null||!sourceSha256.matches("[a-f0-9]{64}"))
            throw new BizException(4001,"A frozen original source SHA256 is required.");
        JsonNode sources=fixed.frozenManifest().path("originalSources");
        JsonNode selected=null;
        for(JsonNode descriptor:sources)if(sourceSha256.equals(descriptor.path("sha256").asText())) {
            if(selected!=null)throw new BizException(4091,"Ambiguous frozen original source identity.");
            selected=descriptor;
        }
        if(selected==null)throw new BizException(4041,"Source is outside the selected fixed baseline.");
        byte[] bytes=readBound(selected,96L*1024*1024);
        return response(bytes,MediaType.APPLICATION_PDF,sourceSha256+".pdf",sourceSha256);
    }

    @GetMapping("/table")
    public ResponseEntity<ByteArrayResource> table(@RequestParam String id) throws IOException {
        VectorStore.SearchHit hit=fixed.readOnlyHitById(id);
        Object value=hit.getPayload().get("tableArtifact");
        if(!(value instanceof Map))throw new BizException(4041,"This observed parent has no complete table artifact.");
        JsonNode descriptor=com.consense.common.JsonUtils.mapper().valueToTree(value);
        byte[] bytes=readBound(descriptor,32L*1024*1024);
        return response(bytes,MediaType.APPLICATION_JSON,"complete-table.json",descriptor.path("sha256").asText());
    }

    private static ResponseEntity<ByteArrayResource> response(byte[] bytes,MediaType type,String name,String hash) {
        return ResponseEntity.ok().contentType(type).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\""+name+"\"")
                .header("X-Content-Type-Options","nosniff")
                .header("X-Source-SHA256",hash)
                .header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(new ByteArrayResource(bytes));
    }

    /** Return precisely the bytes that were hashed, avoiding a check-then-stream race. */
    static byte[] readBound(JsonNode descriptor,long maxBytes) throws IOException {
        String hash=descriptor.path("sha256").asText();long count=descriptor.path("bytes").asLong(-1);
        if(!hash.matches("[a-f0-9]{64}")||count<0||count>maxBytes||!descriptor.path("path").isTextual())
            throw new BizException(4091,"Invalid frozen artifact descriptor.");
        Path path=Paths.get(descriptor.path("path").asText());
        if(!Files.isRegularFile(path)||Files.size(path)!=count)
            throw new BizException(4091,"Frozen source bytes changed or are unavailable.");
        byte[] bytes=Files.readAllBytes(path);
        if(bytes.length!=count||!hash.equals(sha256(bytes)))
            throw new BizException(4091,"Frozen source SHA256 changed; reload the selected baseline.");
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder out=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));
            return out.toString();
        } catch(Exception error) {throw new IllegalStateException("SHA256 unavailable",error);}
    }
}
