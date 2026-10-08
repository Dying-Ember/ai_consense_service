package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Current-run accepted facts constrain suggestions, never replace human adoption. */
class DraftApplicabilityEvidenceTest {
    @Test void knownLegalFormMakesBothFallbackAnswersInactiveRegardlessOfItemOrder() {
        for(boolean reversed:Arrays.asList(false,true)) {
            ExtractTraceVO trace=trace(decision("contractorLegalForm","other"),decision("partnershipClauseAdopted",false),decision("jointVentureClauseAdopted",false));
            if(reversed)Collections.reverse(trace.getDecisions());
            new DraftExtractionHarness(null).rejectInactiveSuggestions(trace,Collections.emptyMap());
            for(ExtractionDecisionVO decision:trace.getDecisions())if(!"contractorLegalForm".equals(decision.getKey())) {
                assertEquals("rejected",decision.getStatus());assertTrue(decision.getCodes().contains("input_not_applicable"));
                assertEquals("false",decision.getNormalizedValue(),"Keep diagnostics rather than converting false into absence silently.");
            }
        }
    }

    @Test void genuinelyUnknownFormStillAllowsExplicitFallbackChoices() {
        ExtractTraceVO trace=trace(decision("partnershipClauseAdopted",true),decision("jointVentureClauseAdopted",false));
        new DraftExtractionHarness(null).rejectInactiveSuggestions(trace,Collections.emptyMap());
        assertTrue(trace.getDecisions().stream().allMatch(d->"accepted".equals(d.getStatus())));
    }

    @Test void differingKnownFormsDoNotBecomeAnUnknownFormByRemovingTheirConflict() {
        ExtractTraceVO trace=trace(decision("contractorLegalForm","other"),decision("contractorLegalForm","partnership"),
                decision("partnershipClauseAdopted",false),decision("jointVentureClauseAdopted",false));
        new DraftExtractionHarness(null).rejectInactiveSuggestions(trace,Collections.emptyMap());
        for(int i=2;i<4;i++) {
            assertEquals("rejected",trace.getDecisions().get(i).getStatus());
            assertTrue(trace.getDecisions().get(i).getCodes().contains("input_applicability_conflict"));
        }
    }

    @Test void allCatalogueConditionsApplyToCollectionsAndScalars() {
        ExtractTraceVO trace=trace(decision("advancePaymentAdopted",false),decision("advanceRepaymentMonths",8),
                decision("twoEnvelopeTendering",false),decision("photocopyRateUpToA3",1.8),
                decision("subcontractArrangement","none"),decision("subcontractors",Arrays.asList("Electrical")));
        new DraftExtractionHarness(null).rejectInactiveSuggestions(trace,Collections.emptyMap());
        for(ExtractionDecisionVO decision:trace.getDecisions())if(Arrays.asList("advanceRepaymentMonths","photocopyRateUpToA3","subcontractors").contains(decision.getKey()))
            assertEquals("rejected",decision.getStatus(),decision.getKey());
    }

    @Test void adoptedParentWinsOverAnUnadoptedModelSuggestionAndAConflictingParentDoesNotBecomeFalse() {
        ExtractTraceVO trace=trace(decision("advancePaymentAdopted",false),decision("advanceRepaymentMonths",8));
        new DraftExtractionHarness(null).rejectInactiveSuggestions(trace,Collections.singletonMap("advancePaymentAdopted",true));
        assertEquals("accepted",trace.getDecisions().get(1).getStatus());
        trace=trace(decision("advancePaymentAdopted",false),decision("advancePaymentAdopted",true),decision("advanceRepaymentMonths",8));
        new DraftExtractionHarness(null).rejectInactiveSuggestions(trace,Collections.emptyMap());
        assertEquals("accepted",trace.getDecisions().get(2).getStatus(),"Conflicting parent facts remain unresolved; do not choose a No by iteration order.");
    }

    private static ExtractionDecisionVO decision(String key,Object value) {
        return new ExtractionDecisionVO("source:0",1,0,key,JsonUtils.mapper().valueToTree(value),value instanceof String?(String)value:JsonUtils.write(value),
                "A previously validated source passage.","A production intake result.",0.95,"accepted",new ArrayList<>());
    }
    private static ExtractTraceVO trace(ExtractionDecisionVO... decisions) {
        ExtractTraceVO trace=new ExtractTraceVO("offline-regression",null,"",null,new ArrayList<>());
        trace.getDecisions().addAll(Arrays.asList(decisions));return trace;
    }
}
