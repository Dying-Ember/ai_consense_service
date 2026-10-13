package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.consense.domain.CanonicalSourceBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact Python-produced public wire from the real Java observer on an authored OOXML file. */
public final class NativeCanonicalFixture {
    private NativeCanonicalFixture(){}
    public static final String SNAPSHOT_BYTES_SHA="9e50fe75904a6b92c8a082f036f6d5985adc6f02305e6a01d161d95791ab8182";
    public static byte[] bytes(String name) throws IOException{
        try(InputStream input=NativeCanonicalFixture.class.getResourceAsStream("/oer/canonical/"+name)){
            if(input==null)throw new IOException(name);ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1)result.write(buffer,0,count);return result.toByteArray();
        }
    }
    public static ObjectNode snapshot() throws IOException {
        byte[] raw=bytes("authored-native-public-v2.json");
        if(!SNAPSHOT_BYTES_SHA.equals(CanonicalJson.sha(raw)))throw new IOException("Authored native fixture bytes changed");
        return (ObjectNode)JsonUtils.parse(new String(raw,StandardCharsets.UTF_8));
    }
    public static ObjectNode capability(){ObjectNode result=JsonUtils.mapper().createObjectNode();
        result.put("schema",NativeCanonicalContract.SCHEMA);result.put("producerKind",NativeCanonicalContract.PRODUCER);
        result.put("projectionVersion",NativeCanonicalContract.VERSION);result.put("storageShape",NativeCanonicalContract.STORAGE);
        result.put("projectionEndpoint","/project/native");result.put("artifactReadValidation","actual-cas-every-read");return result;
    }
    public static EvidencePointer pointer(JsonNode snapshot,JsonNode chunk,CanonicalSourceBinding binding,String module){
        EvidencePointer p=new EvidencePointer();p.setProjectId(binding.getProjectId());p.setDocumentId(binding.getDocumentId());p.setModule(module);
        p.setUnitId(chunk.path("unitId").asText());p.setCanonicalChunkId(chunk.path("id").asText());
        p.setOriginalSourceSha256(binding.getOriginalSourceSha256());p.setJavaParsedSourceHash(binding.getJavaParsedSourceHash());
        p.setCanonicalSourceHash(binding.getCanonicalSourceHash());p.setSourceRevisionSha256(binding.getSourceRevisionSha256());
        p.setProjectionSha256(binding.getProjectionSha256());p.setProjectionRevisionSha256(binding.getProjectionRevisionSha256());
        p.setSourceFilename(snapshot.path("source").path("sourceFilename").deepCopy());p.setSourceOrder(chunk.path("sourceOrder").deepCopy());
        p.setPhysicalPages(new ArrayList<>());p.setBboxConvention(null);p.setProjectionSchema(NativeCanonicalContract.SCHEMA);
        p.setProducerKind(NativeCanonicalContract.PRODUCER);p.setStorageShape(NativeCanonicalContract.STORAGE);
        p.setOriginalGeometryStatus(NativeCanonicalContract.GEOMETRY);p.setVisualReturnStatus(NativeCanonicalContract.VISUAL);
        p.setEligibleModules(chunk.path("eligibleModules").deepCopy());p.setParts(chunk.path("parts").deepCopy());p.setSourceSpans(chunk.path("sourceSpans").deepCopy());
        p.setTableGroup(chunk.get("tableGroup"));p.setAssetRefs(chunk.path("assetRefs").deepCopy());p.setRelations(chunk.path("relations").deepCopy());
        p.setAdoption(chunk.path("adoption").deepCopy());p.setSourceQuality(chunk.path("sourceQuality").deepCopy());p.setUnknowns(snapshot.path("unknowns").deepCopy());return p;
    }
}
