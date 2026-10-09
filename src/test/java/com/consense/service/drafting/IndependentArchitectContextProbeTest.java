package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;

/** Independent supplied-source probes through the real public extraction path. */
class IndependentArchitectContextProbeTest {
    private static final String NAME="Dr. River CHAN";
    private static final String QUOTE="The Project Architect is "+NAME+".";
    private static final List<Map<String,Object>> RECEIPTS=new ArrayList<>();

    @AfterAll static void persistActualPublicIntakeReceipts() throws Exception {
        java.nio.file.Path output=java.nio.file.Paths.get("target","architect-context-probe","decisions.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.write(output,JsonUtils.write(RECEIPTS).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void sameParagraphRegisteredNameOutsideShortQuoteKeepsExactSpelling() {
        String source=QUOTE+" This is the Project Architect's registered name, including Dr. as part of the registered name.";
        ExtractionDecisionVO d=intake("projectArchitectName",NAME,QUOTE,source,source);
        assertEquals("accepted",d.getStatus(),d.getCodes().toString());
        assertEquals(NAME,d.getNormalizedValue(),"Registered name qualification in the same actual supplied paragraph governs the short quote.");
    }
    @Test void precedingLegalNameInstructionOutsideShortQuoteKeepsExactSpelling() {
        String source="Use the exact legal name, including its prefix.\n"+QUOTE;
        ExtractionDecisionVO d=intake("projectArchitectName",NAME,QUOTE,source,source);
        assertEquals("accepted",d.getStatus(),d.getCodes().toString());
        assertEquals(NAME,d.getNormalizedValue(),"Adjacent governing legal-name instruction cannot be omitted by short quoting.");
    }
    @Test void legalNameEvidenceOmittedFromActualSuppliedUnitIsNotUsedToRewrite() {
        String original=QUOTE+" This is the Project Architect's registered name, including Dr. as part of the registered name.";
        ExtractionDecisionVO d=intake("projectArchitectName",NAME,QUOTE,QUOTE,original);
        assertEquals("rejected",d.getStatus(),"The original bound qualification is absent from this actual supplied context.");
    }
    @Test void anotherProjectHeadingOutsideTheShortQuoteCannotSupplyCurrentName() {
        String source="For another project only:\nProject Architect:\n"+QUOTE;
        ExtractionDecisionVO d=intake("projectArchitectName",NAME,QUOTE,source,source);
        assertEquals("rejected",d.getStatus(),"The actual name belongs to a different project, despite matching a current-role phrase in its body.");
    }
    @Test void pendingHeadingOutsideShortQuoteCannotSupplyDecidedName() {
        String source="Pending confirmation:\nProject Architect:\n"+QUOTE;
        ExtractionDecisionVO d=intake("projectArchitectName",NAME,QUOTE,source,source);
        assertEquals("rejected",d.getStatus(),"A neutral nested heading cannot reset the pending role.");
    }
    @Test void followingDependentPendingParagraphCannotSupplyDecidedName() {
        String source=QUOTE+"\n\nThis appointment remains unconfirmed.";
        ExtractionDecisionVO d=intake("projectArchitectName",NAME,QUOTE,source,source);
        assertEquals("rejected",d.getStatus(),"The contiguous dependent qualification controls this appointment.");
    }
    @Test void anIndependentPendingPhoneMatterDoesNotPreventCurrentNameProjection() {
        String source=QUOTE+" The Project Architect appointment is confirmed.\n\nThe telephone number remains pending.";
        ExtractionDecisionVO d=intake("projectArchitectName",NAME,QUOTE,source,source);
        assertEquals("accepted",d.getStatus(),d.getCodes().toString());
        assertEquals("River CHAN",d.getNormalizedValue(),"Independent phone uncertainty does not make the named appointment unresolved.");
    }
    @Test void customUnrecognizedPrefixAndManualLegalTextRemainUnchanged() {
        String custom="Sir River CHAN";
        assertEquals(custom,intake("projectArchitectName",custom,"Project Architect: "+custom+".","Project Architect: "+custom+".","Project Architect: "+custom+".").getNormalizedValue());
        assertEquals("  "+NAME+"  ",DraftInputRules.normalize(DraftBlueprint.find("projectArchitectName"),"  "+NAME+"  "));
        assertEquals("Sir Sir River CHAN",DraftArchitectContactEvidence.renderedName("Sir",custom));
    }
    @Test void aCurrentProjectHeadingCanSeparateAnIndependentEarlierForeignContact() {
        String source="For another project only:\nThe Project Architect is Ms. Other CHAN.\n\nFor this project:\n"+QUOTE;
        ExtractionDecisionVO d=intake("projectArchitectName",NAME,QUOTE,source,source);
        assertEquals("accepted",d.getStatus(),d.getCodes().toString());
        assertEquals("River CHAN",d.getNormalizedValue());
    }
    @Test void actualSctSlotPreservesRegisteredManualNameAndAvoidsMatchingDuplicate() {
        Map<String,Object> inputs=new LinkedHashMap<>();
        inputs.put("projectArchitectPost","Architect");inputs.put("projectArchitectSalutation","Other");
        inputs.put("projectArchitectOtherTitle","Dr");inputs.put("projectArchitectName",NAME);inputs.put("projectArchitectPhone","23456789");
        Map<String,Object> plan=DraftBusinessRules.plan(inputs);
        Map<String,Object> action=DraftBusinessRules.list(plan.get("actions")).stream().map(DraftBusinessRules::asMap)
            .filter(r->"sct-architect-contact".equals(r.get("id"))).findFirst().orElseThrow();
        java.util.regex.Matcher number=java.util.regex.Pattern.compile("\\d+").matcher(String.valueOf(action.get("paragraphs")));
        assertTrue(number.find());
        String original=DraftBusinessRules.standardParagraph("SCT",Integer.parseInt(number.group()));
        String rendered=DraftClauseRules.apply(original,"SCT",plan);
        assertTrue(rendered.contains("("+NAME+")"),rendered);
        assertFalse(rendered.contains("Dr Dr."),rendered);
        assertEquals(NAME,inputs.get("projectArchitectName"));
    }
    private static ExtractionDecisionVO intake(String key,String value,String quote,String supplied,String original) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);
        item.put("sourceQuote",quote);item.put("confidence",.95);
        ExtractionPartVO part=new ExtractionPartVO("architect-independent:0",1L,"contact.txt","source-hash",0,supplied,new ArrayList<>());
        ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("rawModelItem",item);receipt.put("actualSuppliedSource",supplied);receipt.put("originalSource",original);
        receipt.put("actualPrimaryDecision",JsonUtils.mapper().valueToTree(d));RECEIPTS.add(receipt);
        assertEquals(JsonUtils.write(value),JsonUtils.write(d.getRawValue()),"Raw model business value remains unchanged.");
        return d;
    }
}
