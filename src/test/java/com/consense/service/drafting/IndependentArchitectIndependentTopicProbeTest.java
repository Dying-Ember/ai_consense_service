package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A role qualifier is scoped to its field, and cannot poison an independent decided contact. */
class IndependentArchitectIndependentTopicProbeTest {
    private static final String NAME="Dr. River CHAN";
    private static final String QUOTE="The Project Architect is "+NAME+".";
    @Test void sameParagraphIndependentPendingPhoneDoesNotContaminateConfirmedName() {
        String source=QUOTE+" The appointment is confirmed. The telephone number remains pending.";
        assertEquals("River CHAN",check(QUOTE,source,source).getNormalizedValue(),"A separate pending phone matter cannot retain the honorific in the name slot.");
    }
    @Test void unrelatedAnotherProjectComparisonDoesNotPoisonCurrentContact() {
        String source=QUOTE+" This appointment is confirmed. Another project has a different Architect.";
        assertEquals("River CHAN",check(QUOTE,source,source).getNormalizedValue(),"The independently stated foreign comparison is not the quoted contact's project role.");
    }
    @Test void sameParagraphEarlierIndependentForeignContactDoesNotPoisonExplicitCurrentContact() {
        String source="Another project has no Project Architect appointment. For this project: "+QUOTE;
        assertEquals("River CHAN",check(QUOTE,source,source).getNormalizedValue());
    }
    @Test void blankSeparatedExactLegalNameInstructionStillGovernsTheFollowingContact() {
        String source="Use the Project Architect's exact legal name including its prefix.\n\n"+QUOTE;
        assertEquals(NAME,check(QUOTE,source,source).getNormalizedValue(),"A direct legal-name instruction does not cease to govern solely because the body has a blank-line boundary.");
    }
    @Test void anIndependentOtherPersonLegalNameDoesNotPreventNameProjection() {
        String source=QUOTE+" The appointment is confirmed. The Contractor's legal name is Dr. Builders Limited.";
        assertEquals("River CHAN",check(QUOTE,source,source).getNormalizedValue(),"A different legal person's registered-name role does not make Dr part of the Architect's name.");
    }
    private static ExtractionDecisionVO check(String quote,String supplied,String original){
        Map<String,Object> item=new LinkedHashMap<>();item.put("key","projectArchitectName");item.put("value",NAME);item.put("sourceQuote",quote);item.put("confidence",.98);
        ExtractionPartVO part=new ExtractionPartVO("independent-contact-topic:0",1L,"contact.txt","source-hash",0,supplied,new ArrayList<>());
        ExtractionDecisionVO decision=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
        assertEquals(JsonUtils.write(NAME),JsonUtils.write(decision.getRawValue()));assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());return decision;
    }
}
