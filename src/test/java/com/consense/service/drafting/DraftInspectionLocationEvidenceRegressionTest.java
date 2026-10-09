package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The original model value and citation remain visible while inspection roles are checked. */
class DraftInspectionLocationEvidenceRegressionTest {
    @Test void anOfficeAddressIsNotAnInspectionLocationForEitherDocument() {
        for(String key:Arrays.asList("specificationInspectionBlock","drawingsInspectionBlock"))
            check(key,"Block E","The Contractor's office is at Block E.","rejected");
    }
    @Test void aHiddenQuestionPrefixCannotBeDroppedFromTheOriginalParagraph() {
        String quote="The drawings are inspected at Block E.";
        String original="Please confirm whether "+quote;
        check("drawingsInspectionBlock","Block E",quote,original,original,"rejected");
        check("drawingsInspectionBlock","Block E",quote,quote,original,"rejected");
    }
    @Test void aDependentUnconfirmedLocationCannotBeDroppedFromTheCitationOrContext() {
        String quote="The drawings are inspected at Block E.";
        String original=quote+"\n\nThis location remains unconfirmed.";
        check("drawingsInspectionBlock","Block E",quote,original,original,"rejected");
        check("drawingsInspectionBlock","Block E",quote,quote,original,"rejected");
    }
    @Test void aContinuousDependentUnitKeepsItsLaterUnconfirmedLocation() {
        String quote="The drawings are inspected at Block E.";
        String source=quote+"\n\nThis inspection arrangement is adopted.\n\nThis location remains unconfirmed.";
        check("drawingsInspectionBlock","Block E",quote,source,source,"rejected");
    }
    @Test void anOmittedLaterDependentQualifierMakesTheSourceWindowIncomplete() {
        String quote="The drawings are inspected at Block E.";
        String supplied=quote+"\n\nThis inspection arrangement is adopted.";
        check("drawingsInspectionBlock","Block E",quote,supplied,supplied+"\n\nThis location remains unconfirmed.","rejected");
    }
    @Test void anIndependentTopicEndsTheDependentInspectionUnit() {
        String quote="The drawings are inspected at Block E.";
        String source=quote+"\n\nThis inspection arrangement is adopted.\n\nThe roofing warranty remains pending.\n\nThis location remains unconfirmed.";
        check("drawingsInspectionBlock","Block E",quote,source,source,"accepted");
    }
    @Test void unrelatedWarrantyAndOtherDocumentPendingDecisionsDoNotPoisonAnInspectionLocation() {
        check("drawingsInspectionBlock","Block E","The drawings are inspected at Block E, and the roofing warranty is not required.","accepted");
        check("drawingsInspectionBlock","Block E","The drawings are inspected at Block E. The Specification inspection location remains pending.","accepted");
    }
    @Test void separateDocumentClausesCannotLendEachOtherTheirBlock() {
        String source="The drawings are inspected at Block F and the Specification is inspected at Block E.";
        check("drawingsInspectionBlock","Block E",source,"rejected");
        check("drawingsInspectionBlock","Block F",source,"accepted");
        check("specificationInspectionBlock","Block E",source,"accepted");
    }
    @Test void explicitJointInspectionAndAnActualLibraryParagraphRemainUsable() {
        String joint="The Specification and drawings can be inspected at Block E.";
        check("drawingsInspectionBlock","Block E",joint,"accepted");
        check("specificationInspectionBlock","Block E",joint,"accepted");
        check("specificationInspectionBlock","Block E","The Specification Library can be inspected on floor 7, Block E, Fictional Tender Information Office, 66 Study Road, Sha Tin.","accepted");
    }
    @Test void manualAndSuggestedBlockValuesKeepOneActualTemplatePrefix() {
        DraftBlueprint.InputSpec spec=DraftBlueprint.find("specificationInspectionBlock");
        assertEquals("Block E-2",DraftInputRules.normalize(spec,"Block E-2"),"Manual storage retains the user's input");
        for(String value:Arrays.asList("Block E-2",DraftInputRules.normalizeSuggestion(spec,"Block E-2"))) {
            Map<String,Object> inputs=new LinkedHashMap<>();inputs.put("specificationInspectionFloor","7");inputs.put("specificationInspectionBlock",value);
            String original=DraftBusinessRules.standardParagraph("SCT",926)+"\n"+DraftBusinessRules.standardParagraph("SCT",928);
            String rendered=DraftClauseRules.apply(original,"SCT",DraftBusinessRules.plan(inputs));
            assertTrue(rendered.contains("Block E-2"));assertFalse(rendered.contains("Block Block E-2"));
        }
    }
    private static ExtractionDecisionVO check(String key,Object value,String quote,String expected) {
        return check(key,value,quote,quote,quote,expected);
    }
    private static ExtractionDecisionVO check(String key,Object value,String quote,String supplied,String original,String expected) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);item.put("confidence",.95);
        ExtractionPartVO part=new ExtractionPartVO("inspection:0",1L,"inspection.txt","source-hash",0,supplied,new ArrayList<>());
        part.setContext(new ExtractionContextVO("inspection",Collections.singletonList(key),0,supplied.length(),supplied,null));
        ExtractionDecisionVO decision=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
        assertEquals(JsonUtils.write(value),JsonUtils.write(decision.getRawValue()));assertEquals(quote,decision.getSourceQuote());
        assertEquals(expected,decision.getStatus(),quote+" "+decision.getCodes());return decision;
    }
}
