package com.consense.service.drafting;

import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.ocr.OcrClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/** Opt-in local acceptance against the actual competition standards, never a fabricated mini-template. */
class DraftingStandardSourceAcceptanceTest {
    static final Map<String,String> standards=new LinkedHashMap<>();
    static Path evidence;
    @BeforeAll static void readActualSources() throws Exception {
        String configured=System.getProperty("consense.acceptance.sourceDir");
        assumeTrue(configured!=null,"Run with -Dconsense.acceptance.sourceDir pointing to the competition standards.");
        Path source=Paths.get(configured);
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),mock(OcrClient.class));
        String[] names={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
        String[] keys={"NTT","SCT","SCC"};
        for(int i=0;i<keys.length;i++) {
            DocumentParser.ParsedDocument parsed=parser.parse(names[i],Files.readAllBytes(source.resolve(names[i])));
            assertEquals("PARSED",parsed.getParseStatus(),names[i]);
            assertTrue(parsed.getText().length()>10000,names[i]);
            standards.put(keys[i],parsed.getText());
        }
        evidence=Paths.get("target","drafting-standard-source-acceptance",UUID.randomUUID().toString());
        Files.createDirectories(evidence);
    }
    Map<String,Object> adopted() {
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("foundationIncluded",true);values.put("periodAtLeast39Months",true);
        values.put("twoEnvelopeTendering",true);values.put("electronicTendering","L10Pro");
        values.put("subcontractArrangement","NSC");values.put("wtoGpaApplies",true);
        values.put("railwayProtectionAreaWorks",false);values.put("precastFacadeTenderBasis",false);
        values.put("worksSubjectToExcision",false);
        values.put("photocopyRateUpToA3",0);values.put("photocopyRateAboveA3",2);
        return values;
    }
    String apply(String key,Map<String,Object> values,String suffix) throws Exception {
        Map<String,Object> plan=DraftBusinessRules.plan(values);
        String original=standards.get(key)+"\nUNRELATED-END-SENTINEL";
        String result=DraftClauseRules.apply(original,key,plan);
        assertTrue(result.endsWith("UNRELATED-END-SENTINEL"),"Full tail must survive all source edits.");
        Files.write(evidence.resolve(key+"-"+suffix+".txt"),result.getBytes(StandardCharsets.UTF_8));
        Files.write(evidence.resolve(key+"-"+suffix+"-plan.json"),com.consense.common.JsonUtils.write(plan).getBytes(StandardCharsets.UTF_8));
        return result;
    }
    @Test void actualNttBondRequiresBothFactsAndInclusive39() throws Exception {
        Map<String,Object> values=adopted();
        String yes=apply("NTT",values,"bond-g1a");
        assertTrue(yes.contains("Appendix G1a to Conditions of Contract"),"Actual NTT10 must be edited, not merely a plan flag.");
        values.put("foundationIncluded",false);values.remove("periodAtLeast39Months");
        String no=apply("NTT",values,"bond-g1");
        assertTrue(no.contains("Appendix G1 to Conditions of Contract"),"An explicit false selects G1 even with unknown duration.");
    }
    @Test void actualSctZeroPhotocopyRateSurvivesAndOtherRateIsSeparate() throws Exception {
        String result=apply("SCT",adopted(),"rates");
        assertTrue(result.matches("(?s).*0(?:\\.0+)?\\s+per\\s+(?:A3|sheet|page).*"),"Zero rate must be inserted in the actual source wording.");
    }
    @Test void actualSourceTargetOverrideEditsLocatedClauseAndPreservesTail() throws Exception {
        Map<String,Object> values=adopted();
        String wording="The tenderer shall follow the business user's adopted procedure for this simulated acceptance case.";
        values.put("targetOverrides",Collections.singletonList(DraftBusinessRules.map("actionId","NTT-13-SUBCONTRACT","action","amend","value",wording)));
        String result=apply("NTT",values,"manual-target");
        assertTrue(result.contains(wording),"A valid adopted target must affect real NTT13 source text.");
        assertFalse(result.contains("*Such requirement does not apply to engagement of Nominated Sub-contractor"),"The old target paragraph must be replaced, not append-only duplicated.");
    }
    @Test void wholeParagraphManualDecisionWinsOverAutomaticEditsAtSameSourcePosition() throws Exception {
        Map<String,Object> values=adopted();
        String wording="The Contractor shall procure an on-demand bond under the verified SCC edition, Appendix G1a to Conditions of Contract.";
        values.put("targetOverrides",Collections.singletonList(DraftBusinessRules.map("actionId","NTT-10-SCC-REFERENCE","action","amend","value",wording,"sourceMapping","The business user adopts this exact paragraph against standard NTT P484 for this test.")));
        String result=apply("NTT",values,"manual-shared-paragraph");
        assertTrue(result.contains(wording),"An automatic G1/G1a edit must not prevent a stronger adopted full-paragraph replacement at the same source position.");
    }
    @Test void mixedPricingRemovesCompleteUnselectedOptionAndPreservesRequestedVariation() throws Exception {
        for(boolean provisional:new boolean[]{false,true}) {
            Map<String,Object> values=adopted();
            values.put("pricingScheme","BQ_SOR");values.put("allBqQuantitiesProvisional",provisional);
            String result=apply("SCC",values,provisional?"mixed-b":"mixed-a");
            assertTrue(result.contains("The Contractor shall not be reimbursed for any cost or expense incurred by him for the preparation and submission of any Requested Variation Proposal"),"SCC11.303 must survive selection of 11.302 A or B.");
            Map<String,Object> plan=DraftBusinessRules.plan(values);
            DraftClauseRules.apply(standards.get("SCC"),"SCC",plan);
            Map<String,Object> selected=null;
            for(Object item:DraftBusinessRules.list(plan.get("actions")))if("scc-mixed-measurement".equals(DraftBusinessRules.asMap(item).get("id")))selected=DraftBusinessRules.asMap(item);
            assertNotNull(selected);
            assertEquals("applied",selected.get("application"),"The complete selected alternative must actually be applied to the standard source.");
        }
    }
    @Test void designComponentScopeIsDifferentForWarrantyAndAdverseGround() throws Exception {
        Map<String,Object> values=adopted();
        values.put("designResponsibilities",Arrays.asList(
                DraftBusinessRules.map("component","piling","design",true,"execution",true),
                DraftBusinessRules.map("component","footings","design",true,"execution",true)));
        values.put("footingsServeBuildingsOrMajorExternalStructures",false);
        String result=apply("SCC",values,"component-scope");
        assertTrue(result.contains("that the piles as designed and constructed will be adequate"),"SCC7.302 must omit footings outside its qualified structural scope even if piling makes the clause applicable.");
        assertTrue(result.contains("adverse ground conditions affecting the design and construction of piles, shallow foundations including footings (raft/pad)"),"SCC8.304 has a different component scope and retains the actual adopted footings.");
    }
    @Test void adoptedRepaymentAmendmentChangesActualBodyRatherThanGuidance() throws Exception {
        Map<String,Object> values=adopted();
        values.put("advancePaymentAdopted",true);values.put("advanceRepaymentMonths",8);values.put("advanceRepaymentFirstCertificate",9);
        String result=apply("SCC",values,"advance-repayment");
        assertTrue(result.matches("(?s).*over a period of (?:eight|8) consecutive months.*"));
        assertTrue(result.matches("(?s).*in the (?:ninth|9th) monthly interim payment certificate.*"));
    }
    @Test void electricalSafetyReferenceUsesOnlyItsAdoptedSchedule() throws Exception {
        Map<String,Object> values=adopted();
        values.put("subcontractArrangement","BSSSC");values.put("subcontractors",Collections.singletonList("Electrical"));
        values.put("billNos",Arrays.asList(
                DraftBusinessRules.map("id","ordinary","number","4","description","Building Works","type","BQ","purpose","general","placement","standard"),
                DraftBusinessRules.map("id","safety","number","7","description","Safety, Environmental and Hygiene Payments for Electrical Works","type","SOR","purpose","electricalSafety","placement","DiscB")));
        String result=apply("SCC",values,"electrical-safety");
        assertTrue(result.contains("Schedule No. 7 of the schedule of rates for site safety, environmental management and site hygiene for electrical Specialist Sub-contract Works"),"SCC20.302 must use the actual safety purpose/type rather than all Bill numbers.");
    }
    @Test void wrongSourceDoesNotReceiveNumericPositionEdits() {
        String changed="10. Sureties\nAn unrelated contractual promise for a different template edition.\n11. Other\nKeep intact.";
        Map<String,Object> plan=DraftBusinessRules.plan(adopted());
        String result=DraftClauseRules.apply(changed,"NTT",plan);
        assertTrue(result.contains("An unrelated contractual promise for a different template edition."));
        assertFalse(result.contains("Appendix G1a"));
        assertTrue(((List<?>)plan.get("unresolved")).stream().anyMatch(item -> com.consense.common.JsonUtils.write(item).contains("NTT-10-BOND-REFERENCE")),"Unmatched known target must be recorded, not considered applied.");
    }
    @Test void issueAndReturnAlternativesRemoveTheirEntireContinuation() throws Exception {
        for(String mode:Arrays.asList("L10Pro","Hardcopy")) {
            Map<String,Object> values=adopted();values.put("electronicTendering",mode);
            String result=apply("SCT",values,"complete-media-"+mode);
            String flat=result.replaceAll("\\s+", " ");
            if("L10Pro".equals(mode)) {
                assertTrue(flat.contains("All data in the disc are compressed into a single L10Pro program compatible file"));
                assertFalse(flat.contains("The files in Disc C for the Bills of Quantities"),"The hardcopy alternative's continuation must be removed, not just its heading.");
                assertFalse(flat.contains("with each item priced, extended and totaled"),"The unselected SCT3 hardcopy return branch must be removed in full.");
            } else {
                assertTrue(flat.contains("The files in Disc C for the Bills of Quantities"));
                assertTrue(flat.contains("with each item priced, extended and totaled"));
                assertFalse(flat.contains("All data in the disc are compressed into a single L10Pro program compatible file"));
                assertFalse(flat.contains("using the L10Pro program for tender submission"));
            }
            Map<String,Object> plan=DraftBusinessRules.plan(values);DraftClauseRules.apply(standards.get("SCT"),"SCT",plan);
            for(String id:Arrays.asList("sct-issue-alternative","sct-pricing-return-alternative")) {
                Map<String,Object> target=null;for(Object item:DraftBusinessRules.list(plan.get("actions")))if(id.equals(DraftBusinessRules.asMap(item).get("id")))target=DraftBusinessRules.asMap(item);
                assertNotNull(target);assertEquals("applied",target.get("application"),id+" must edit the actual source.");
            }
        }
    }
    @Test void originalHardcopyQualifierUsesItsActualBodyParagraph() throws Exception {
        Map<String,Object> values=adopted();
        String electronic=apply("SCT",values,"original-hardcopy-l10pro").replaceAll("\\s+", " ");
        assertTrue(electronic.contains("(including the Form of Tender) may be treated as a tendering irregularity"),"L10Pro must delete the starred BQ qualifier in SCT5(10) actual body P760, leaving the Form of Tender requirement.");
        values.put("electronicTendering","Hardcopy");
        String paper=apply("SCT",values,"original-hardcopy-paper").replaceAll("\\s+", " ");
        assertTrue(paper.contains("Form of Tender and the Bill of Quantities and General Summary"),"Hardcopy retains the adopted BQ requirement and removes only its editing star.");
    }
    @Test void negativeWholeClausesApplyToUnmodifiedStandardBodies() throws Exception {
        Map<String,Object> values=adopted();values.put("domesticBlocks",false);values.put("precastFacadePermission",false);values.put("wtoGpaApplies",false);
        String result=apply("SCT",values,"negative-whole");
        for(String marker:Arrays.asList("SCT4 Not used","SCT10 Not used","SCT14 Not used"))assertTrue(result.contains(marker),marker+" must be applied to the actual standard, rather than left unresolved by an overly narrow anchor guard.");
    }
    @Test void automaticWholeClauseDoesNotDeleteAddedProjectWording() {
        Map<String,Object> values=adopted();values.put("domesticBlocks",false);
        String added="The business user has added this project-specific protection within SCT4.";
        String source=standards.get("SCT").replace("SCT5",added+"\nSCT5");
        Map<String,Object> plan=DraftBusinessRules.plan(values);
        String result=DraftClauseRules.apply(source,"SCT",plan);
        assertTrue(result.contains(added),"An automatic whole-clause action must not silently erase additional project wording.");
        assertTrue(DraftBusinessRules.list(plan.get("unresolved")).stream().anyMatch(item->"application-sct-domestic-entire-clause".equals(DraftBusinessRules.asMap(item).get("id"))),"Changed whole-clause scope must be explicitly unresolved.");
    }
}
