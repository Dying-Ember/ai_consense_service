package com.consense.service.sources;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePdfSourceFamilyContractTest {
    static String wire()throws Exception{return new String(NativePdfSourceFamilyContractTest.class.getResourceAsStream("/oer/native-pdf-family/authored-family-v1.json").readAllBytes(),StandardCharsets.UTF_8);}
    @Test void exactFrozenPythonFamilyAndFullChildrenPassWithoutMutation()throws Exception{
        String raw=wire();JsonNode root=JsonUtils.parse(raw);assertEquals(root,NativePdfSourceFamilyContract.validateEnvelope(raw,root.path("projectionSha256").asText()));
        assertEquals(2,root.path("chunks").size());for(JsonNode p:root.path("chunks"))NativePdfSourceFamilyContract.validateChunk(p,root.path("source").path("sourceSha256").asText());
        JsonNode display=NativePdfSourceFamilyContract.displayDeclarations(root);NativePdfSourceFamilyContract.validateDisplayDeclarations(display);assertEquals(NativePdfSourceFamilyContract.unknowns(root),NativePdfSourceFamilyContract.unknowns(display));assertFalse(display.path("sourceQuality").path("legalQualityAccepted").asBoolean());
    }
    @Test void everyPartialFamilyMarkerIsRecognizedBeforeLegacyAliases()throws Exception{
        JsonNode root=JsonUtils.parse(wire());for(String f:new String[]{"schema","producerKind","storageShape","sourceFamilyComponents"}){ObjectNode partial=JsonUtils.mapper().createObjectNode();partial.set(f,root.path(f));assertTrue(NativePdfSourceFamilyContract.isFamily(partial));assertThrows(RuntimeException.class,()->NativePdfSourceFamilyContract.validateEnvelope(JsonUtils.write(partial),"a".repeat(64)));}
        ObjectNode marker=JsonUtils.mapper().createObjectNode();marker.set("sourceFamilyOriginRef",root.path("chunks").get(0).path("sourceFamilyOriginRef"));assertTrue(NativePdfSourceFamilyContract.isFamily(marker));
    }
    @Test void familyCannotPublishPixelsOrPromoteCompositionToLegalAcceptance()throws Exception{
        JsonNode root=JsonUtils.parse(wire());ObjectNode d=NativePdfSourceFamilyContract.displayDeclarations(root);
        d.put("visualReturnStatus","published");assertThrows(RuntimeException.class,()->NativePdfSourceFamilyContract.validateDisplayDeclarations(d));
        ObjectNode invalid=NativePdfSourceFamilyContract.displayDeclarations(root);((ObjectNode)invalid.path("sourceQuality")).put("legalQualityAccepted",true);assertThrows(RuntimeException.class,()->NativePdfSourceFamilyContract.validateDisplayDeclarations(invalid));
    }
    @Test void childAdoptionIsRetainedAndCannotBecomeTheFamilyUnit()throws Exception{
        JsonNode root=JsonUtils.parse(wire());ObjectNode parent=root.path("chunks").get(0).deepCopy();String original=root.path("source").path("sourceSha256").asText();assertNotEquals(parent.path("unitId"),parent.path("adoption").path("unitId"));((ObjectNode)parent.path("adoption")).set("unitId",parent.path("unitId"));assertThrows(RuntimeException.class,()->NativePdfSourceFamilyContract.validateChunk(parent,original));
    }

    @Test void savedAuthoredV1AndV2AndCompleteFamilyKeepExactTypedIdentity()throws Exception{
        for(String name:new String[]{"authored-child-v1.json","authored-child-v2.json","authored-family-v2.json"}){
            String raw=NativePdfSourceFamilyFixture.raw(name);JsonNode root=JsonUtils.parse(raw);
            assertEquals(root,CanonicalEvidenceService.validateSnapshot(raw,root.path("projectionSha256").asText()),name);
            assertEquals(name.startsWith("authored-family")?2:1,root.path("chunks").size());
        }
    }
    @Test void nativeV2RequiresExactTwoTypedDependencyPinsAndConsistentChildVersion()throws Exception{
        ObjectNode root=(ObjectNode)NativePdfSourceFamilyFixture.snapshot("authored-child-v2.json");
        ((ObjectNode)root.path("projectionIdentity").path("projectionDependencySha256")).remove("pdf_selected_source.py");
        JsonNode missing=root;assertThrows(RuntimeException.class,()->NativePdfCanonicalContract.validateEnvelope(missing));
        root=(ObjectNode)NativePdfSourceFamilyFixture.snapshot("authored-child-v2.json");
        ((ObjectNode)root.path("chunks").get(0)).put("internalProjectionVersion",NativePdfCanonicalContract.INTERNAL_VERSION);JsonNode mixed=root;
        assertThrows(RuntimeException.class,()->NativePdfCanonicalContract.validateEnvelope(mixed));
        ObjectNode unknown=(ObjectNode)NativePdfSourceFamilyFixture.snapshot("authored-child-v2.json");((ObjectNode)unknown.path("projectionIdentity")).put("internalProjectionVersion","unknown-v3");
        assertThrows(RuntimeException.class,()->NativePdfCanonicalContract.validateEnvelope(unknown));
    }
    @Test void resealedParentOmissionAndChangedLiteralCannotReplaceFullChildProof()throws Exception{
        ObjectNode root=(ObjectNode)NativePdfSourceFamilyFixture.snapshot("authored-family-v1.json");((ArrayNode)root.path("chunks")).remove(1);
        String partial=NativePdfSourceFamilyFixture.reseal(root);assertThrows(RuntimeException.class,()->CanonicalEvidenceService.validateSnapshot(partial,JsonUtils.parse(partial).path("projectionSha256").asText()));
        ObjectNode altered=(ObjectNode)NativePdfSourceFamilyFixture.snapshot("authored-family-v1.json");((ObjectNode)altered.path("chunks").get(0)).put("content","invented literal");
        String changed=NativePdfSourceFamilyFixture.reseal(altered);assertThrows(RuntimeException.class,()->CanonicalEvidenceService.validateSnapshot(changed,JsonUtils.parse(changed).path("projectionSha256").asText()));
    }
}
