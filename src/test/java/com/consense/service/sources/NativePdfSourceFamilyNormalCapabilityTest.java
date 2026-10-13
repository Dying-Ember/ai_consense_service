package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Normal HTTP health must be accepted without relaxing declared source/visual limits. */
class NativePdfSourceFamilyNormalCapabilityTest {
    static ObjectNode installed() {
        ObjectNode capability=NativePdfSourceFamilyFixture.capability();
        capability.put("projectEndpoint","/project/native-pdf-family");
        capability.put("projectionInputs","actual-cas-family-spec-only");
        capability.put("sourcePageRenderStatus","unsupported");
        return capability;
    }
    static ObjectNode oer(ObjectNode capability) {
        ObjectNode root=JsonUtils.mapper().createObjectNode();
        root.set("nativePdfSourceFamily",capability);
        return root;
    }
    @Test void exactInstalledNormalHttpCapabilityIsAccepted() {
        assertDoesNotThrow(()->NativePdfSourceFamilyContract.validateCapability(oer(installed())));
    }
    @Test void exactLegacyCandidateReadCapabilityRemainsAccepted() {
        assertDoesNotThrow(()->NativePdfSourceFamilyContract.validateCapability(oer(NativePdfSourceFamilyFixture.capability())));
    }
    @Test void partialUnknownOrPromotedNormalCapabilitiesRemainRejected() {
        for(String field:new String[]{"projectionInputs","sourcePageRenderStatus"}) {
            ObjectNode missing=installed();missing.remove(field);
            assertThrows(RuntimeException.class,()->NativePdfSourceFamilyContract.validateCapability(oer(missing)));
        }
        for(String[] change:new String[][]{{"projectEndpoint","/invented-family"},{"projectionInputs","inline-public-wire"},{"sourcePageRenderStatus","published"}}) {
            ObjectNode changed=installed();changed.put(change[0],change[1]);
            assertThrows(RuntimeException.class,()->NativePdfSourceFamilyContract.validateCapability(oer(changed)));
        }
        ObjectNode legacyWithInstalledFields=installed();legacyWithInstalledFields.put("projectEndpoint","not_installed_v1_candidate");
        assertThrows(RuntimeException.class,()->NativePdfSourceFamilyContract.validateCapability(oer(legacyWithInstalledFields)));
        ObjectNode promoted=installed();promoted.put("wholeSourceAccepted",true);
        assertThrows(RuntimeException.class,()->NativePdfSourceFamilyContract.validateCapability(oer(promoted)));
    }
}
