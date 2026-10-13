package com.consense.service.sources;
import com.consense.common.JsonUtils;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;
/** Owned authored and retained public-wire fixtures; no actual CAS or model invocation. */
public final class NativePdfSourceFamilyFixture {
    private NativePdfSourceFamilyFixture(){}
    public static String raw(String name)throws Exception{return new String(NativePdfSourceFamilyFixture.class.getResourceAsStream("/oer/native-pdf-family/"+name).readAllBytes(),StandardCharsets.UTF_8);}
    public static JsonNode snapshot(String name)throws Exception{return JsonUtils.parse(raw(name));}
    public static String reseal(ObjectNode root){root.remove("projectionSha256");String raw=CanonicalJson.strictCanonical(JsonUtils.write(root));root.put("projectionSha256",CanonicalJson.hash(raw,null));return CanonicalJson.strictCanonical(JsonUtils.write(root));}
    public static CanonicalSourceBinding binding(String name)throws Exception{return bindingFromRaw(raw(name));}
    public static CanonicalSourceBinding bindingFromRaw(String raw)throws Exception{
        JsonNode root=JsonUtils.parse(raw),i=root.path("projectionIdentity");CanonicalSourceBinding b=new CanonicalSourceBinding();
        b.setId(1L);b.setProjectId("family-project");b.setDocumentId(Long.valueOf(i.path("documentId").asText()));b.setProjectionSha256(root.path("projectionSha256").asText());
        b.setOriginalSourceSha256(i.path("sourceSha256").asText());b.setJavaParsedSourceHash(CanonicalJson.sha("authored-java-source".getBytes(StandardCharsets.UTF_8)));b.setRoutingSha256(CanonicalJson.sha("authored-routing".getBytes(StandardCharsets.UTF_8)));
        b.setSourceRevisionSha256(i.path("sourceRevisionSha256").asText());b.setProjectionRevisionSha256(root.path("projectionRevisionSha256").asText());b.setCanonicalSourceHash(root.path("sourceHash").asText());
        b.setScopePolicySha256(root.path("manifest").path("scope").path("scopePolicySha256").asText());b.setDeclaredModule(i.path("module").asText());b.setEligibleModulesJson(JsonUtils.write(i.path("eligibleModules")));
        if(NativePdfSourceFamilyContract.isFamily(root)){b.setFamilyCompositionSha256(i.path("compositionSha256").asText());b.setFamilyComponentSetSha256(i.path("componentSetSha256").asText());}
        else {b.setAdoptionManifestSha256(i.path("adoptionManifestSha256").asText());b.setReviewReferenceSha256(i.path("referenceSha256").asText());}
        b.setSnapshotJson(raw);b.setSnapshotBytesSha256(CanonicalJson.sha(raw.getBytes(StandardCharsets.UTF_8)));return b;
    }
    public static EvidencePointer pointer(CanonicalSourceBinding binding,JsonNode root,JsonNode chunk,String module){return ReflectionTestUtils.invokeMethod(CanonicalEvidenceService.class,"pointer",binding,root,chunk,module);}
    public static ObjectNode capability(){ObjectNode c=JsonUtils.mapper().createObjectNode();c.put("schema",NativePdfSourceFamilyContract.SCHEMA);c.put("version",NativePdfSourceFamilyContract.VERSION);c.put("producerKind",NativePdfSourceFamilyContract.PRODUCER);c.put("sourceRevisionKind",NativePdfSourceFamilyContract.REVISION);c.put("componentContract","complete-cold-actual-cas-native-pdf-public-roots");c.put("maximumMessageBytes",NativePdfSourceFamilyContract.MAX_BYTES);c.put("crossComponentRelations","unsupported");c.put("adoptedCoverage","disjoint-physical-pages-required-v1");c.put("wholeSourceAccepted",false);c.put("semanticAcceptanceByComposition",false);c.put("projectEndpoint","not_installed_v1_candidate");return c;}
}
