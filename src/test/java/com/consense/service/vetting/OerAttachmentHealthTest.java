package com.consense.service.vetting;

import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored capability deltas, no assertion that health implies neural execution. */
class OerAttachmentHealthTest {
    static ConsenseProperties expected(){ConsenseProperties p=VettingDualStructureHealthTest.expected();p.getVetting().setExpectedRetrievalContract(VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_CONTRACT);return p;}
    static ObjectNode authoredHealth()throws Exception {
        ObjectNode h=VettingDualStructureHealthTest.health(),window=(ObjectNode)h.path("retrieval").path("sourceWindows");
        window.put("contract",VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_CONTRACT);
        ((ObjectNode)window.path("structuralRecipe")).put("version",VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_VERSION);
        ObjectNode selection=(ObjectNode)window.path("structuralSelection");selection.put("policy",VettingRetrievalSourceUnits.ATTACHMENT_POLICY);selection.put("hierarchyContextPolicy",OerCanonicalHierarchyContext.POLICY);selection.put("attachmentContextPolicy",OerCanonicalAttachmentContext.POLICY);return h;
    }
    @Test void exactV2ContractAndV3SelectionAdvertiseBothIndependentContexts()throws Exception {
        VettingRetrievalHealth.Identity identity=VettingRetrievalHealth.validate(authoredHealth(),expected().getVetting());assertEquals(VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_CONTRACT,identity.contract);assertEquals(VettingRetrievalSourceUnits.ATTACHMENT_POLICY,identity.sourceUnitPolicy);assertEquals(4,identity.signatureVersion);
    }
    @Test void newAndOldContractsCannotBeInterchanged()throws Exception {
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(authoredHealth(),VettingDualStructureHealthTest.expected().getVetting()));
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(VettingDualStructureHealthTest.health(),expected().getVetting()));
        assertNotNull(VettingRetrievalHealth.validate(VettingDualStructureHealthTest.health(),VettingDualStructureHealthTest.expected().getVetting()));
    }
    @Test void missingWrongOrLegacyAttachmentCapabilityCannotBeAccepted()throws Exception {
        for(String mutation:Arrays.asList("missing","wrong","old-policy","old-structure-version")){
            ObjectNode h=authoredHealth(),window=(ObjectNode)h.path("retrieval").path("sourceWindows"),selection=(ObjectNode)window.path("structuralSelection");
            if(mutation.equals("missing"))selection.remove("attachmentContextPolicy");if(mutation.equals("wrong"))selection.put("attachmentContextPolicy","unreviewed");if(mutation.equals("old-policy"))selection.put("policy",VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);if(mutation.equals("old-structure-version"))((ObjectNode)window.path("structuralRecipe")).put("version",VettingRetrievalHealth.TYPED_PARENT_STRUCTURAL_VERSION);
            assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,expected().getVetting()),mutation);
        }
    }
    @Test void legacyHealthCannotSmuggleAttachmentDeclaration()throws Exception {
        ObjectNode h=VettingDualStructureHealthTest.health();((ObjectNode)h.path("retrieval").path("sourceWindows").path("structuralSelection")).put("attachmentContextPolicy",OerCanonicalAttachmentContext.POLICY);
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,VettingDualStructureHealthTest.expected().getVetting()));
    }
}
