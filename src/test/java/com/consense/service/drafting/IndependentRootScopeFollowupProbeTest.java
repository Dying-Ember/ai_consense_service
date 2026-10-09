package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent public-intake evidence probes; no oracle or model creates the replies. */
class IndependentRootScopeFollowupProbeTest {
    private static final String FOOTING="footingsServeBuildingsOrMajorExternalStructures";
    private static final String LOCALITY="projectInTinShuiWai";
    private static final String HEADER="Component | Actual work scope | Contractor designs | Contractor executes";
    private static final String TABLE=HEADER+"\nfootings | Youth centre footings | No | Yes";
    private static final String COMPLETE="This is the complete component responsibility schedule:";
    private static final String INITIAL_TABLE="Component | Contractor designs | Contractor executes\npiling | Yes | Yes\nfootings | No | Yes";
    private static final String FULL_TABLE=HEADER+"\npiling | North building piles | Yes | Yes\nfootings | South building footings | No | Yes\nother | Internal partitions | No | Yes";

    @Test void settledFootingsUseSurvivesAnUnrelatedNegativeClause() {
        check(FOOTING,true,"The footings serve the buildings, and the roofing warranty is not adopted.","accepted");
    }
    @Test void thisProjectsFootingsUseSurvivesASeparateOtherProjectComparison() {
        check(FOOTING,true,"For this project, the footings serve the buildings. Another project has no piling works.","accepted");
    }
    @Test void separateActorsCannotJoinInspectionToTheBuildingsUse() {
        check(FOOTING,true,"The Engineer inspects the footings, and the Contractor supports the buildings.","rejected");
    }
    @Test void explicitNegativeUseKeepsItsOwnPolarity() {
        check(FOOTING,false,"The footings do not serve any buildings or any major external structures.","accepted");
    }
    @Test void delayedConfirmationOfThatFootingsUseRemainsUnanswered() {
        String quote="The footings serve the buildings.";
        String source=quote+"\n\nThis classification remains unconfirmed.";
        check(FOOTING,true,quote,source,source,"rejected");
    }
    @Test void wholeProjectLocationSurvivesAnUnrelatedNegativeClause() {
        check(LOCALITY,true,"The project is in Tin Shui Wai, and no roofing warranty is required.","accepted");
    }
    @Test void anExpresslyForbiddenNegativeLocationLabelIsNotAnAdoptedAnswer() {
        check(LOCALITY,false,"Do not use this answer: Project in Tin Shui Wai: No.","rejected");
    }
    @Test void localBuildingAbsenceDoesNotEstablishWholeProjectAbsence() {
        check(LOCALITY,false,"Only the domestic caretaker block is not in Tin Shui Wai.","rejected");
    }
    @Test void twoLineUnadoptedDesignPrefixStillGovernsItsTable() {
        String source="The following Contractor responsibility table is not adopted.\nFor ease of reference its columns are:\n"+TABLE;
        check("designResponsibilities",singleDesign(),TABLE,source,source,"rejected");
    }
    @Test void twoLineOtherProjectDesignPrefixCannotBecomeThisProjectTable() {
        String source="For another project only:\n"+COMPLETE+"\n"+TABLE;
        check("designResponsibilities",singleDesign(),TABLE,source,source,"rejected");
    }
    @Test void aNamedTrailingApprovalNoteStillGovernsItsTable() {
        String source=COMPLETE+"\n"+TABLE+"\nApproval note: this schedule remains unconfirmed.";
        check("designResponsibilities",singleDesign(),TABLE,source,source,"rejected");
    }
    @Test void unrelatedRoofingPendingDoesNotInvalidateASettledDesignTable() {
        String source=COMPLETE+"\n"+TABLE+"\nThe roofing warranty remains pending.";
        check("designResponsibilities",singleDesign(),TABLE,source,source,"accepted");
    }
    @Test void aDependentSuffixOmittedFromTheSuppliedContextCannotBeRecovered() {
        String supplied=COMPLETE+"\n"+TABLE;
        check("designResponsibilities",singleDesign(),TABLE,supplied,supplied+"\nThis decision remains pending.","rejected");
    }
    @Test void anExplicitRefinementWithMatchingCountsSelectsAnExistingCompleteAnswer() {
        assertNotNull(merge("The two initial entries are:", refinement("two","")));
    }
    @Test void mismatchingReferencedInitialCountKeepsAConflict() {
        assertNull(merge("The two initial entries are:",refinement("three","")));
    }
    @Test void anExplicitLocalInitialScopeCannotExpandIntoDifferentCompleteScopes() {
        assertNull(merge("The two initial entries are: for the East Annex only",refinement("two","")));
    }
    @Test void aCompleteLocalTableCannotOverrideWholeProjectInitialResponsibilities() {
        assertNull(merge("For the whole project, the two initial entries are:",refinement("two"," for Block A")));
    }
    @Test void lowerCaseNamedLocalScopeIsStillALocalRestriction() {
        assertNull(merge("The two initial entries are: for block a only",refinement("two","")));
    }
    @Test void sharedEditorIdDoesNotConflateDifferentBusinessSections() {
        List<Map<String,Object>> value=new ArrayList<>();
        for(String designation:Arrays.asList("Section A","Section B")) {
            Map<String,Object> section=new LinkedHashMap<>();section.put("id","editor-independent-901");section.put("designation",designation);section.put("workTypes",Collections.singletonList("foundation"));section.put("location",designation.equals("Section A")?"North site":"South site");value.add(section);
        }
        String source="Section designation | Works types | Location\nSection A | foundation | North site\nSection B | foundation | South site";
        ExtractionDecisionVO decision=check("sections",value,source,"accepted");
        for(int index=0;index<2;index++) {
            assertFalse(JsonUtils.parse(decision.getNormalizedValue()).get(index).has("id"));
            assertEquals("editor-independent-901",JsonUtils.mapper().valueToTree(decision.getRawValue()).get(index).path("id").asText());
        }
    }
    @Test void blockPrefixCaseAndCompoundIdentifiersPreserveRawEvidence() {
        ExtractionDecisionVO decision=check("drawingsInspectionBlock","BLOCK E-2","The drawings are inspected at BLOCK E-2.","accepted");
        assertEquals("E-2",decision.getNormalizedValue());
        assertEquals("\"BLOCK E-2\"",JsonUtils.write(decision.getRawValue()));
    }

    private static String refinement(String count,String local) {
        return "This is the complete component responsibility schedule"+local+"; it supplies the work limits for the earlier "+count+" entries and does not change their design or execution assignments:";
    }
    private static String merge(String initialPrefix,String fullPrefix) {
        List<Map<String,Object>> initial=Arrays.asList(row("piling",null,true,true),row("footings",null,false,true));
        List<Map<String,Object>> full=Arrays.asList(row("piling","North building piles",true,true),row("footings","South building footings",false,true),row("other","Internal partitions",false,true));
        ExtractionDecisionVO initialDecision=check("designResponsibilities",initial,initialPrefix+"\n"+INITIAL_TABLE,"accepted");
        ExtractionDecisionVO fullDecision=check("designResponsibilities",full,fullPrefix+"\n"+FULL_TABLE,"accepted");
        List<CandidateVO> candidates=Arrays.asList(candidate(initialDecision),candidate(fullDecision));
        String forward=DraftDesignCandidates.merge(DraftBlueprint.find("designResponsibilities"),candidates);
        Collections.reverse(candidates);
        assertEquals(forward,DraftDesignCandidates.merge(DraftBlueprint.find("designResponsibilities"),candidates),"Candidate order cannot resolve a semantic conflict");
        if(forward!=null)assertTrue(candidates.stream().anyMatch(candidate->forward.equals(candidate.getValue())),"Select an existing answer; do not synthesize one");
        System.out.println("INDEPENDENT_REFINES "+JsonUtils.write(Arrays.asList(initialPrefix,fullPrefix,forward)));
        return forward;
    }
    private static CandidateVO candidate(ExtractionDecisionVO decision) {
        return new CandidateVO(decision.getNormalizedValue(),1L,"source.txt","independent-frozen-source",decision.getSourceQuote(),"Independent intake candidate",.95);
    }
    private static List<Map<String,Object>> singleDesign() {
        return new ArrayList<>(Collections.singletonList(row("footings","Youth centre footings",false,true)));
    }
    private static Map<String,Object> row(String component,String scope,boolean design,boolean execution) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("component",component);row.put("design",design);row.put("execution",execution);if(scope!=null)row.put("scope",scope);return row;
    }
    private static ExtractionDecisionVO check(String key,Object value,String quote,String expected) {
        return check(key,value,quote,quote,quote,expected);
    }
    private static ExtractionDecisionVO check(String key,Object value,String quote,String supplied,String original,String expected) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);item.put("confidence",.95);item.put("reason","Independent source role and scope review");
        ExtractionPartVO part=new ExtractionPartVO("independent-followup:0",1L,"source.txt","independent-source",0,supplied,new ArrayList<>());
        part.setContext(new ExtractionContextVO("independent-spec",Collections.singletonList(key),0,supplied.length(),supplied,null));
        ExtractionDecisionVO decision=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
        assertEquals(JsonUtils.write(value),JsonUtils.write(decision.getRawValue()),"Preserve the original model value");
        System.out.println("INDEPENDENT_FOLLOWUP "+JsonUtils.write(Arrays.asList(key,supplied,expected,decision.getStatus(),decision.getCodes(),decision.getSourceQuote())));
        assertEquals(expected,decision.getStatus(),key+" "+supplied+" "+decision.getCodes());
        return decision;
    }
}
