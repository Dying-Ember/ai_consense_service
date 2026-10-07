package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.domain.DraftVariable;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftInputRulesTest {
    @Test void competitionCatalogKeepsLegacyKeysAndDerivedValuesOutOfInputs() {
        assertEquals(21,DraftBlueprint.INPUTS.stream().filter(s -> !s.hidden).map(s -> s.group).distinct().count());
        for(String key:Arrays.asList("contractTitle","foundationIncluded","periodAtLeast39Months","billNos","subcontractArrangement","subcontractors","twoEnvelopeTendering","electronicTendering"))assertNotNull(DraftBlueprint.find(key));
        assertEquals(1,DraftBlueprint.INPUTS.stream().filter(s -> s.key.equals("targetOverrides")).count());
        assertNull(DraftBlueprint.find("fundingArrangement"));
        assertNull(DraftBlueprint.find("appendixG1a"));
        assertNull(DraftBlueprint.find("nscApplicable"));
    }
    @Test void unknownIsNotFalseAndUnresolvedDraftsHaveNoApprovalGate() {
        assertNull(DraftInputRules.derived(Collections.emptyList(),"").get("g1aTrigger"));
        assertDoesNotThrow(() -> DraftInputRules.requireReady(Collections.emptyList()));
        assertEquals("",DraftInputRules.normalize(DraftBlueprint.find("foundationIncluded"),"unknown"));
        assertThrows(BizException.class, () -> DraftInputRules.normalize(DraftBlueprint.find("subcontractArrangement"),"probably NSC"));
    }
    @Test void g1aRequiresBothTrue() {
        for (boolean a : new boolean[]{false,true}) for (boolean b : new boolean[]{false,true})
            assertEquals(a && b,DraftInputRules.derived(Arrays.asList(v("foundationIncluded",String.valueOf(a)),v("periodAtLeast39Months",String.valueOf(b))),"").get("g1aTrigger"));
    }
    @Test void billsNeedNumberDescriptionAndUniqueNumbers() {
        assertThrows(RuntimeException.class, () -> DraftInputRules.normalize(DraftBlueprint.find("billNos"),"[\"1 Preliminaries\"]"));
        String entered=DraftInputRules.normalize(DraftBlueprint.find("billNos"),"[{\"number\":\"1\",\"description\":\"A\"},{\"number\":\"1\",\"description\":\"B\"}]");
        assertFalse(DraftInputRules.valid(v("billNos",entered)),"Retain the editable duplicate rows and mark invalid rather than discard the user's entry.");
        assertTrue(DraftInputRules.normalize(DraftBlueprint.find("billNos"),"[{\"number\":\"9\",\"description\":\"Electrical Works (Schedule of Rates)\"}]").contains("Schedule of Rates"));
    }
    @Test void structuredIdentityAndOnlySixServices() {
        assertThrows(RuntimeException.class, () -> DraftInputRules.normalize(DraftBlueprint.find("contractTitle"),"Some title"));
        assertThrows(RuntimeException.class, () -> DraftInputRules.normalize(DraftBlueprint.find("subcontractors"),"[\"Plumbing\"]"));
        assertEquals("[\"Electrical\",\"Lift\"]",DraftInputRules.normalize(DraftBlueprint.find("subcontractors"),"[\"Lift\",\"Electrical\"]"));
    }
    static DraftVariable v(String key, String value) { DraftVariable v = new DraftVariable(); v.setVarKey(key); v.setValueText(value); v.setConfirmed(true); return v; }
}
