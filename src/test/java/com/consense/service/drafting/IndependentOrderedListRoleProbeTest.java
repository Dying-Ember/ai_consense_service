package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent ordered-list source-role boundaries through the real primary intake. */
class IndependentOrderedListRoleProbeTest {
    private static final String KEY="otherWaterproofingSpecificationAreas";
    private static final String HEADING="Other waterproofing areas specified in Specification";
    private static final String VALUE="1. Kitchen;\n2. Bathroom;\n Include waterproofed thresholds and drains;\n3. Plant room.";

    @Test void unrelatedNegativeClauseDoesNotWithdrawAnExplicitlyConfirmedList() {
        String source=HEADING+"\n"+VALUE+"\n\nThe three listed waterproofing areas are confirmed, while the roofing warranty is not required.";
        check(VALUE,source,source,"accepted");
    }
    @Test void anotherProjectListDoesNotSupplyThisContractsWaterproofingAreas() {
        String source="For another project only:\n"+HEADING+"\n"+VALUE;
        check(VALUE,source,source,"rejected");
    }
    @Test void aRealNamedListRestrictionAfterASiblingParagraphStillApplies() {
        String source=HEADING+"\n"+VALUE+"\n\nThe roofing warranty is required.\n\nThe above waterproofing schedule is not adopted.";
        check(VALUE,source,source,"rejected");
    }
    @Test void explicitIndependentWarrantyAssuranceKeepsTheActualListUnchanged() {
        String source=HEADING+"\n"+VALUE+"\n\nThe roofing warranty is not required. These decisions do not change the waterproofing areas.";
        ExtractionDecisionVO decision=check(VALUE,source,source,"accepted");
        assertEquals(VALUE,decision.getNormalizedValue());
    }
    @Test void anOmittedContinuationCannotBeFilledByTheHarness() {
        String source=HEADING+"\n"+VALUE;
        check(VALUE.replace(" Include waterproofed thresholds and drains;\n",""),source,source,"rejected");
    }
    @Test void sourceWindowCuttingAContinuationIsNotCompleteEvidence() {
        String source=HEADING+"\n"+VALUE;
        String supplied=source.substring(0,source.indexOf("thresholds")+4);
        check(VALUE,supplied,source,"rejected");
    }
    @Test void laterRowsCannotHideAnOriginalDuplicateNumber() {
        String source=HEADING+"\n"+VALUE.replace("3. Plant room.","2. Plant room.");
        check(VALUE,source,source,"rejected");
    }
    @Test void repeatedCompleteListsRemainAmbiguous() {
        String first=HEADING+"\n"+VALUE;
        String source=first+"\n\nThis is a separate document:\n"+first;
        check(VALUE,source,source,"rejected");
    }
    private static ExtractionDecisionVO check(String value,String supplied,String original,String expected) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",KEY);item.put("value",value);item.put("sourceQuote",VALUE);item.put("confidence",.95);item.put("reason","Independent ordered source role review");
        ExtractionPartVO part=new ExtractionPartVO("independent-list:0",1L,"list.txt","independent-source",0,supplied,new ArrayList<>());
        part.setContext(new ExtractionContextVO("independent-list",Collections.singletonList(KEY),0,supplied.length(),supplied,null));
        ExtractionDecisionVO decision=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
        assertEquals(value,JsonUtils.mapper().valueToTree(decision.getRawValue()).asText(),"Keep the raw model answer");
        System.out.println("INDEPENDENT_LIST_ROLE "+JsonUtils.write(Arrays.asList(original,expected,decision.getStatus(),decision.getCodes(),decision.getSourceQuote())));
        assertEquals(expected,decision.getStatus(),original+" "+decision.getCodes());return decision;
    }
}
