package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact completed model replies, decoded through the public intake with their frozen context. */
class DraftRoundThreeScalarRegressionTest {
    @Test void locationAbsenceCapturedReplies() throws Exception { replay("projectInTinShuiWai"); }
    @Test void quantifiedNegationCapturedReplies() throws Exception { replay("allBqQuantitiesProvisional"); }
    @Test void inclusiveSizeCapturedReplies() throws Exception { replay("photocopyRateUpToA3"); }
    @Test void selectedTradesCapturedReplies() throws Exception { replay("subcontractors"); }
    @Test void servedObjectAndPronounCapturedReplies() throws Exception { replay("footingsServeBuildingsOrMajorExternalStructures"); }
    @Test void drawingsAvailabilityCapturedReplies() throws Exception { replay("drawingsInspectionBlock"); }

    @Test void locationNegativeCannotBecomePositiveOrDiscardSourceRoles() {
        String claim="No part of this contract is in Tin Shui Wai.";
        accept("projectInTinShuiWai",false,claim,claim);
        reject("projectInTinShuiWai",true,claim,claim);
        for(String prefix:Arrays.asList("For another project:\n\n", "Pending location decision:\n\n", "Do not adopt:\n\n"))
            reject("projectInTinShuiWai",false,claim,prefix+claim);
        reject("projectInTinShuiWai",false,"No part of the Architect's office is in Tin Shui Wai.","No part of the Architect's office is in Tin Shui Wai.");
    }

    @Test void quantifierNegationIsLocalAndCannotNegateFirmOrBorrowAnotherPredicate() {
        accept("allBqQuantitiesProvisional",false,"It is not the case that all BQ quantities are provisional.","It is not the case that all BQ quantities are provisional.");
        reject("allBqQuantitiesProvisional",true,"It is not the case that all BQ quantities are provisional.","It is not the case that all BQ quantities are provisional.");
        reject("allBqQuantitiesProvisional",false,"It is not the case that all BQ quantities are firm.","It is not the case that all BQ quantities are firm.");
        accept("allBqQuantitiesProvisional",true,"The programme is not the case discussed here; all BQ quantities are provisional.","The programme is not the case discussed here; all BQ quantities are provisional.");
        reject("allBqQuantitiesProvisional",false,"Please confirm whether it is not the case that all BQ quantities are provisional.","Please confirm whether it is not the case that all BQ quantities are provisional.");
        for(String prefix:Arrays.asList("For another project:\n\n", "Pending decision:\n\n", "Do not adopt:\n\n")) {
            String unsupported=prefix+"It is not the case that all BQ quantities are provisional.";
            reject("allBqQuantitiesProvisional",false,unsupported,unsupported);
        }
        String nested="It is not the case that all BQ quantities are not provisional.";
        reject("allBqQuantitiesProvisional",false,nested,nested);
    }

    @Test void inclusiveSizeDoesNotSwapRatesOrAdmitNegatedAmounts() {
        String quote="The photocopy rate up to and including A3 is HK$3.25; above A3 is HK$8.10.";
        accept("photocopyRateUpToA3",3.25,quote,quote);
        accept("photocopyRateAboveA3",8.10,quote,quote);
        reject("photocopyRateUpToA3",8.10,quote,quote);
        reject("photocopyRateAboveA3",3.25,quote,quote);
        String negative="The photocopy rate up to and including A3 is not HK$3.25.";
        reject("photocopyRateUpToA3",3.25,negative,negative);
    }

    @Test void neitherNorBindsOnlyImmediateFootingsSubject() {
        String quote="The footings listed in the approved register are pad bases. They serve neither buildings nor major external structures.";
        accept("footingsServeBuildingsOrMajorExternalStructures",false,quote,quote);
        accept("footingsServeBuildingsOrMajorExternalStructures",false,"They serve neither buildings nor major external structures.",quote);
        reject("footingsServeBuildingsOrMajorExternalStructures",true,quote,quote);
        String changed="The footings listed in the approved register are pad bases. The contractor and the Architect inspect them. They serve neither buildings nor major external structures.";
        reject("footingsServeBuildingsOrMajorExternalStructures",false,"They serve neither buildings nor major external structures.",changed);
        String joint="The footings and the piles are foundation components. They serve neither buildings nor major external structures.";
        reject("footingsServeBuildingsOrMajorExternalStructures",false,"They serve neither buildings nor major external structures.",joint);
        String pending=quote+" Their classification remains pending.";
        reject("footingsServeBuildingsOrMajorExternalStructures",false,quote,pending);
    }

    @Test void availableLocationBelongsOnlyToItsExplicitDocument() {
        String quote="The drawings are available on floor 8 of Block Z of that office.";
        String context="The Specification Library is available for inspection in Block A of the Tender Information Office. "+quote;
        accept("drawingsInspectionBlock","Z",quote,context);
        reject("drawingsInspectionBlock","Z",quote,quote);
        reject("specificationInspectionBlock","Z",quote,context);
        reject("drawingsInspectionBlock","Y",quote,context);
        String negative="The drawings are not available on floor 8 of Block Z of that office.";
        reject("drawingsInspectionBlock","Z",negative,negative);
        String pending=context+" This arrangement remains pending.";
        reject("drawingsInspectionBlock","Z",quote,pending);
        reject("drawingsInspectionBlock","Z",quote,"For another project: "+context);
        for(String prefix:Arrays.asList("For another project:\n\n", "Pending location decision:\n\n", "Do not adopt:\n\n"))
            reject("drawingsInspectionBlock","Z",quote,prefix+context);
        String switched="The drawings are available for inspection in Block Z and the specifications are available for inspection in Block Y.";
        accept("drawingsInspectionBlock","Z",switched,switched);
        reject("drawingsInspectionBlock","Y",switched,switched);
        String shop="The shop drawings are available for fabrication in Block Z.";
        reject("drawingsInspectionBlock","Z",shop,shop);
        String borrowed="The specifications can be inspected in Block A of the Tender Information Office. The construction drawings are available in Block Z of that office.";
        reject("drawingsInspectionBlock","Z",borrowed,borrowed);
    }

    @Test void selectedTradesExcludeOnlyTheirOwnNegationsAndPreserveCombinedIdentities() {
        List<String> selected=Arrays.asList("Air-conditioning and mechanical ventilation","Lift","Fire services");
        String quote="The selected specialist trades are Air conditioning and mechanical ventilation, Lift, and Fire services. There is no water pump trade in this selection and no escalator installation.";
        accept("subcontractors",selected,quote,quote);
        String other="The selected specialist trades are Lift and Fire services. Air conditioning and mechanical ventilation is not selected.";
        accept("subcontractors",Arrays.asList("Lift","Fire services"),other,other);
        reject("subcontractors",selected,other,other);
        String selectedNo="The selected specialist trades are Lift and Fire services, but no Air conditioning and mechanical ventilation.";
        reject("subcontractors",selected,selectedNo,selectedNo);
        String combined="The selected specialist trades are Lift and escalator and Fire services and water pump.";
        reject("subcontractors",Arrays.asList("Lift","Fire services"),combined,combined);
    }

    @Test void quantityCitationCannotDiscardAnOuterSourceRoleOrDependentQualifier() {
        String quote="It is not the case that all BQ quantities are provisional.";
        for(String prefix:Arrays.asList("For another project:\n\n", "Pending quantity decision:\n\n", "Do not adopt:\n\n"))
            reject("allBqQuantitiesProvisional",false,quote,prefix+quote);
        reject("allBqQuantitiesProvisional",false,quote,quote+"\n\nIts classification remains pending.");
        accept("allBqQuantitiesProvisional",false,quote,quote+"\n\nThe roofing decision is adopted.\n\nIts classification remains pending.");
    }

    @Test void aPendingFormalBillTitleDoesNotPoisonTheQuantityClassification() {
        String quote="It is not the case that all BQ quantities are provisional.";
        String source=quote+" The formal description of BQ Bill 7, Coast Works (All Provisional), remains pending.";
        accept("allBqQuantitiesProvisional",false,quote,source);
    }

    @Test void aPendingSorQuantityDecisionIsAnIndependentPricingDocument() {
        String quote="It is not the case that all BQ quantities are provisional.";
        accept("allBqQuantitiesProvisional",false,quote,quote+" The SOR provisional quantities remain pending.");
    }

    @Test void aPendingWaterproofingQuantityDecisionIsAnIndependentWorkScope() {
        String quote="It is not the case that all BQ quantities are provisional.";
        accept("allBqQuantitiesProvisional",false,quote,quote+" The waterproofing quantities remain pending.");
    }

    @Test void theBqQuantityDecisionAndItsDependentQualificationRemainUnsettled() {
        String quote="It is not the case that all BQ quantities are provisional.";
        reject("allBqQuantitiesProvisional",false,quote,quote+" The BQ quantities remain pending.");
        reject("allBqQuantitiesProvisional",false,quote,quote+" Its classification remains pending.");
        reject("allBqQuantitiesProvisional",false,quote,quote+"\n\nThis classification remains pending.");
    }

    @Test void tradeCitationCannotDiscardAnOuterSourceRoleOrDependentQualifier() {
        List<String> selected=Arrays.asList("Lift","Fire services");
        String quote="The selected specialist trades are Lift and Fire services.";
        for(String prefix:Arrays.asList("For another project:\n\n", "Pending trade selection:\n\n", "Do not adopt:\n\n"))
            reject("subcontractors",selected,quote,prefix+quote);
        reject("subcontractors",selected,quote,quote+"\n\nThis selection remains pending.");
        accept("subcontractors",selected,quote,quote+"\n\nThe roofing decision is adopted.\n\nIts classification remains pending.");
        String table="Selected trades:\n\nLift | Yes\n\nFire services | Yes";
        accept("subcontractors",selected,table,table);
        reject("subcontractors",selected,table,"For another project:\n\n"+table);
    }

    @Test void visibleTradeRolesCannotAdoptAnExampleOrPendingList() {
        List<String> selected=Arrays.asList("Lift","Fire services");
        String claim="The selected specialist trades are Lift and Fire services.";
        for(String prefix:Arrays.asList("For another project:\n", "Do not adopt:\n", "Proposed trade selection:\n")) {
            String quote=prefix+claim;
            reject("subcontractors",selected,quote,quote);
        }
    }

    private static void replay(String key) throws Exception {
        JsonNode fixture=fixture();int found=0;
        for(JsonNode record:fixture.path("records")) {
            JsonNode captured=record.path("originalDecision"),attempt=record.path("attempt");
            if(!key.equals(captured.path("key").asText()))continue;found++;
            String raw=attempt.path("rawResponse").asText();
            assertEquals(record.path("rawResponseSha256").asText(),sha(raw.getBytes(StandardCharsets.UTF_8)));
            ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
            assertEquals(JsonUtils.mapper().valueToTree(JsonUtils.mapper().treeToValue(attempt.path("context"),ExtractionContextVO.class)),JsonUtils.mapper().valueToTree(part.getContext()));
            String original=record.path("originalSource").asText();
            assertEquals(part.getSourceHash(),sha(("PROJECT_INPUT\nnull\nPARSED\n"+original).getBytes(StandardCharsets.UTF_8)));
            assertEquals(part.getContext().getSourceText(),original.substring(part.getContext().getSourceStart(),part.getContext().getSourceEnd()));
            List<ExtractionDecisionVO> results=DraftHarnessTestIntake.primaryDecisions(raw,part,original,captured.path("attemptIndex").asInt(),attempt.path("systemPrompt").asText(),attempt.path("userPrompt").asText());
            ExtractionDecisionVO actual=results.stream().filter(d->key.equals(d.getKey())&&d.getItemIndex()==captured.path("itemIndex").asInt()).findFirst().orElseThrow(AssertionError::new);
            assertEquals(captured.path("rawValue"),JsonUtils.mapper().valueToTree(actual.getRawValue()));
            assertEquals(captured.path("normalizedValue").asText(),actual.getNormalizedValue());
            assertEquals(captured.path("sourceQuote").asText(),actual.getSourceQuote());
            assertEquals(captured.path("reason").asText(),actual.getReason());
            assertEquals(captured.path("confidence").asDouble(),actual.getConfidence());
            String expected=record.path("expectedStatus").asText();
            System.out.println("ROUND3_SCALAR_REPLAY "+JsonUtils.write(Arrays.asList(part.getPartId(),captured.path("attemptIndex").asInt(),key,expected,actual.getStatus(),actual.getCodes())));
            assertEquals(expected,actual.getStatus(),key+" "+actual.getCodes());
        }
        assertTrue(found>0);
    }

    private static JsonNode fixture() throws Exception {
        byte[] bytes;
        try(InputStream in=DraftRoundThreeScalarRegressionTest.class.getResourceAsStream("/drafting/harness/captured-round3-scalars.json")) {assertNotNull(in);bytes=in.readAllBytes();}
        assertEquals("32e876327475451886117502ba17d9ac33a2587313b4372d58da94034ba59307",sha(new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8)));
        JsonNode fixture=JsonUtils.mapper().readTree(bytes);
        assertEquals(17,fixture.path("records").size());
        assertEquals("a8f32dc6-0c01-4a1d-8d14-58e37882a540",fixture.path("provenance").path("runId").asText());
        assertEquals("6b79383871a831229b6acb26c17f92f81465e8c40bec929d230ed8bc5d397816",fixture.path("provenance").path("traceSha256").asText());
        return fixture;
    }
    private static String sha(byte[] bytes) throws Exception {StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b&255));return s.toString();}
    private static void accept(String key,Object value,String quote,String original) {assertEquals("accepted",decide(key,value,quote,original).getStatus(),key+" "+quote);}
    private static void reject(String key,Object value,String quote,String original) {assertEquals("rejected",decide(key,value,quote,original).getStatus(),key+" "+quote);}
    private static ExtractionDecisionVO decide(String key,Object value,String quote,String original) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);item.put("reason","Offline role-boundary regression.");item.put("confidence",0.95);
        ExtractionPartVO part=new ExtractionPartVO("boundary:0",1L,"boundary.txt","source",0,original,new ArrayList<>());
        part.setContext(new ExtractionContextVO("scalar-boundary",Collections.emptyList(),0,original.length(),original,null));
        return DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
    }
}
