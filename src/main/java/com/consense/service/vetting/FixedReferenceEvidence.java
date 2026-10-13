package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

/** Lossless, source-bound fixed reference. It carries no canonical/project-adoption claim. */
public final class FixedReferenceEvidence {
    private final String id;
    private final double score;
    private final JsonNode payload, tableArtifact, sourceUnit;
    FixedReferenceEvidence(String id, double score, JsonNode payload, JsonNode tableArtifact, JsonNode sourceUnit) {
        this.id = id; this.score = score; this.payload = payload.deepCopy();
        this.tableArtifact = tableArtifact == null ? null : tableArtifact.deepCopy();
        this.sourceUnit = sourceUnit == null ? null : sourceUnit.deepCopy();
    }
    public String getId() { return id; }
    public double getScore() { return score; }
    public String getContent() { return payload.path("content").asText(); }
    public String getFileLabel() { return payload.path("fileLabel").asText(payload.path("fileKey").asText()); }
    public int getPageNo() { return payload.path("pageNo").asInt(); }
    public String getSourceFamily() { return payload.path("sourceQuality").path("family").asText(); }
    public JsonNode getPayload() { return payload.deepCopy(); }
    public JsonNode getTableArtifact() { return tableArtifact == null ? null : tableArtifact.deepCopy(); }
    public JsonNode getSourceUnit() { return sourceUnit == null ? null : sourceUnit.deepCopy(); }
    public JsonNode pointer() {
        com.fasterxml.jackson.databind.node.ObjectNode pointer=JsonUtils.mapper().createObjectNode();
        pointer.put("evidenceBackend","fixed_development_baseline");pointer.put("parentId",id);
        pointer.put("manifestSha256",payload.path("fixedBaselineManifestSha256").asText());pointer.put("baselineVersion",payload.path("fixedBaselineVersion").asText());
        pointer.put("sourceFamily",getSourceFamily());pointer.put("sourceRole","standard");pointer.put("originalSourceSha256",payload.path("originalSourceSha256").asText(payload.path("sourceHash").asText()));
        if(payload.has("originalPageNos"))pointer.set("originalPageNos",payload.get("originalPageNos").deepCopy());pointer.put("pageNo",getPageNo());
        pointer.put("fullObservedParentPreserved",true);pointer.put("sourceQualityAccepted",false);pointer.put("projectApplicability","unconfirmed");pointer.put("qualifiersComplete","unknown");pointer.put("legalOwnershipAccepted",false);
        if(sourceUnit!=null){com.fasterxml.jackson.databind.node.ObjectNode group=pointer.putObject("sourceUnit");for(String key:java.util.Arrays.asList("unitId","kind","status","sourceIdentity","requiredMemberIds","presentMemberIds","missingMemberIds","originIds","required","present","missing","unknown","qualifiersComplete","semanticScopeVerified"))if(sourceUnit.has(key))group.set(key,sourceUnit.get(key).deepCopy());}
        pointer.put("evidenceUrl","/api/oer/fixed-baseline/evidence?id="+query(id));pointer.put("originalUrl","/api/oer/fixed-baseline/original?sourceSha256="+query(pointer.path("originalSourceSha256").asText()));
        if(tableArtifact!=null)pointer.put("tableUrl","/api/oer/fixed-baseline/table?id="+query(id));return pointer;
    }
    private static String query(String value){try{return java.net.URLEncoder.encode(value,"UTF-8");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}}
    public VectorStore.SearchHit asHit() {
        Map<String,Object> map = JsonUtils.mapper().convertValue(payload, new TypeReference<Map<String,Object>>() {});
        return new VectorStore.SearchHit(id, score, map);
    }
}
