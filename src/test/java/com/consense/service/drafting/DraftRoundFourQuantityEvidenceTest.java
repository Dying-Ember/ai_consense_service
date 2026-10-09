package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public-path quantity grammar guards; no oracle or expected answer feeds production. */
class DraftRoundFourQuantityEvidenceTest {
    @Test void universalQuantitiesInUniversalBqBillsIsACompleteSubject() {
        accepted(true,"All quantities in all four BQ Bills are provisional.");
        accepted(true,"All quantities in all BQ Bills are provisional.");
        accepted(true,"All quantities in all 8 BQ Bills are provisional.");
        accepted(true,"All quantities in all eleven Bills of Quantities are provisional.");
        accepted(true,"All quantities in all twenty-one BQ Bills are provisional.");
    }
    @Test void quantifiedBillSubjectsKeepExplicitPolarity() {
        accepted(false,"Not all quantities in all six BQ Bills are provisional.");
        accepted(false,"All quantities in all six BQ Bills are not provisional.");
        accepted(false,"All quantities in all six BQ Bills are firm.");
        rejected(true,"Not all quantities in all six BQ Bills are provisional.");
        rejected(false,"All quantities in all six BQ Bills are provisional.");
        rejected(false,"All quantities in all six BQ Bills are not firm.");
        rejected(true,"All quantities in all six BQ Bills are provisional. Not all quantities in all six BQ Bills are provisional.");
    }
    @Test void subjectAndPredicateNegationDoesNotEstablishAWholeBqStatus() {
        for(String source:Arrays.asList(
                "Not all quantities in all four BQ Bills are not provisional.",
                "Not all quantities in all 8 Bills of Quantities are not provisional.",
                "Not all BQ quantities are not provisional.",
                "NOT ALL QUANTITIES IN ALL FOUR BQ BILLS ARE NOT PROVISIONAL.")) {
            rejected(false,source);rejected(true,source);
        }
    }
    @Test void partialSchedulesOtherDocumentTypesAndTitlesDoNotEstablishWholeBqStatus() {
        for(String source:Arrays.asList(
                "Some quantities in all four BQ Bills are provisional.",
                "All quantities in four of the BQ Bills are provisional.",
                "All quantities in the first four BQ Bills are provisional.",
                "All quantities in all selected BQ Bills are provisional.",
                "All quantities in all four SOR Bills are provisional.",
                "All quantities in all four BQ Bills are provisional except the canopy quantities.",
                "Bill description: All quantities in all four BQ Bills are provisional.",
                "BQ | 4 | All Provisional",
                "All quantities in all four BQ Bills are provisional?")) {
            rejected(true,source);rejected(false,source);
        }
    }
    @Test void completeStatementCannotDiscardPendingOrAnotherProjectSourceRole() {
        String quote="All quantities in all four BQ Bills are provisional.";
        for(String prefix:Arrays.asList("Pending quantity decision:\n\n","For another project:\n\n","Do not adopt:\n\n","Please confirm whether ")) {
            rejected(true,quote,prefix+quote);
        }
        rejected(true,quote,quote+" The BQ quantities remain pending.");
        rejected(true,quote,quote+" Its classification remains pending.");
    }
    private static void accepted(boolean value,String source) {
        ExtractionDecisionVO decision=decide(value,source,source);
        System.out.println("ROUND4_QUANTITY_GRAMMAR source="+source+" status="+decision.getStatus()+" codes="+decision.getCodes());
        assertEquals("accepted",decision.getStatus(),source+" "+decision.getCodes());
    }
    private static void rejected(boolean value,String source) { rejected(value,source,source); }
    private static void rejected(boolean value,String quote,String original) {
        ExtractionDecisionVO decision=decide(value,quote,original);
        assertEquals("rejected",decision.getStatus(),quote+" "+decision.getCodes());
    }
    private static ExtractionDecisionVO decide(boolean value,String quote,String original) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key","allBqQuantitiesProvisional");item.put("value",value);item.put("sourceQuote",quote);item.put("reason","Offline complete-quantity statement regression.");item.put("confidence",0.95);
        ExtractionPartVO part=new ExtractionPartVO("quantity-boundary:0",1L,"quantity.txt","source",0,original,new ArrayList<>());
        part.setContext(new ExtractionContextVO("quantity-boundary",Collections.emptyList(),0,original.length(),original,null));
        return DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
    }
}
