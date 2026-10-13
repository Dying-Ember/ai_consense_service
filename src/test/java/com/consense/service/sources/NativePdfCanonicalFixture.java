package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import java.util.*;

/** Actual frozen Python reprojection of authored PDF inputs with adoption documentId=42. */
public final class NativePdfCanonicalFixture {
    private NativePdfCanonicalFixture() {}
    public static final String PUBLIC_SHA = "f2e7455b6cd84b08a11fccc85e66dc9ea2f0335444f382c57b2c4c2de41dc068";
    public static byte[] bytes(String name) throws IOException {
        try (InputStream input=NativePdfCanonicalFixture.class.getResourceAsStream("/oer/native-pdf-contract/"+name)) {
            if(input==null)throw new IOException(name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1)out.write(buffer,0,count);return out.toByteArray();
        }
    }
    public static String raw() throws IOException {
        byte[] raw=bytes("authored-business-public-v1.json");
        if(raw.length!=43078||!PUBLIC_SHA.equals(CanonicalJson.sha(raw)))throw new IOException("Authored actual PDF public fixture changed");
        return new String(raw,StandardCharsets.UTF_8);
    }
    public static ObjectNode snapshot() throws IOException {return (ObjectNode)JsonUtils.parse(raw());}
    /** Declared health double, not a running Python service or actual inference claim. */
    public static ObjectNode capability() {
        ObjectNode c=JsonUtils.mapper().createObjectNode();c.put("schema",NativePdfCanonicalContract.SCHEMA);c.put("producerKind",NativePdfCanonicalContract.PRODUCER);
        c.put("projectionVersion",NativePdfCanonicalContract.VERSION);c.put("storageShape",NativePdfCanonicalContract.STORAGE);
        c.put("projectionEndpoint","/project/native-pdf");c.put("projectionInputs","actual-cas-three-descriptors-only");c.put("artifactReadValidation","actual-cas-every-read");
        c.put("maximumMessageBytes",NativePdfCanonicalContract.MAX_BYTES);c.put("tableAndAppearanceText","unsupported");c.put("visualReturnStatus",NativePdfCanonicalContract.VISUAL);return c;
    }
    public static ObjectNode algorithms(JsonNode snapshot) {
        ObjectNode a=JsonUtils.mapper().createObjectNode();for(String name:NativePdfCanonicalContract.ALGORITHMS)a.put(name,CanonicalJson.sha(("authored health "+name).getBytes(StandardCharsets.UTF_8)));
        JsonNode identity=snapshot.path("projectionIdentity");a.set("native_pdf_projection_transport.py",identity.path("projectionCodeSha256"));
        a.set("native_pdf_canonical_projection.py",identity.path("internalProjectionCodeSha256"));
        a.set("native_pdf_observation.py",identity.path("projectionDependencySha256").path("native_pdf_observation.py"));return a;
    }
    /** Pointer double derived from the actual fixture, independently checked against the real service tests. */
    public static EvidencePointer pointer(JsonNode snapshot,JsonNode chunk,CanonicalSourceBinding binding,String module){
        EvidencePointer p=new EvidencePointer();p.setProjectId(binding.getProjectId());p.setDocumentId(binding.getDocumentId());p.setModule(module);
        p.setUnitId(chunk.path("unitId").asText());p.setCanonicalChunkId(chunk.path("id").asText());
        p.setOriginalSourceSha256(binding.getOriginalSourceSha256());p.setJavaParsedSourceHash(binding.getJavaParsedSourceHash());
        p.setCanonicalSourceHash(binding.getCanonicalSourceHash());p.setSourceRevisionSha256(binding.getSourceRevisionSha256());
        p.setProjectionSha256(binding.getProjectionSha256());p.setProjectionRevisionSha256(binding.getProjectionRevisionSha256());
        p.setSourceFilename(snapshot.path("source").path("filename").deepCopy());p.setSourceOrder(chunk.path("sourceOrder").deepCopy());
        List<Integer> pages=new ArrayList<>();chunk.path("physicalPages").forEach(v->pages.add(v.asInt()));p.setPhysicalPages(pages);
        p.setBboxConvention(NativePdfCanonicalContract.BBOX);p.setProjectionSchema(NativePdfCanonicalContract.SCHEMA);p.setProducerKind(NativePdfCanonicalContract.PRODUCER);p.setStorageShape(NativePdfCanonicalContract.STORAGE);
        p.setOriginalGeometryStatus(NativePdfCanonicalContract.GEOMETRY);p.setVisualReturnStatus(NativePdfCanonicalContract.VISUAL);p.setNativePdfEvidence(NativePdfCanonicalContract.displayDeclarations(snapshot));
        p.setEligibleModules(chunk.path("eligibleModules").deepCopy());p.setParts(chunk.path("parts").deepCopy());p.setSourceSpans(chunk.path("sourceSpans").deepCopy());
        p.setTableGroup(chunk.get("tableGroup"));p.setAssetRefs(chunk.path("assetRefs").deepCopy());p.setRelations(chunk.path("relations").deepCopy());
        p.setAdoption(chunk.path("adoption").deepCopy());p.setSourceQuality(chunk.path("sourceQuality").deepCopy());p.setUnknowns(snapshot.path("captureCompleteness").path("reference").path("unknowns").deepCopy());return p;
    }
}
