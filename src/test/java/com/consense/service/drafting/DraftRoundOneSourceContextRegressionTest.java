package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Unchanged real answers at the public intake seam; no oracle supplies model items. */
class DraftRoundOneSourceContextRegressionTest {
    @Test void completeNativeDesignPrefaceTravelsWithTheAlreadyCompleteModelTable() throws Exception {
        ExtractionDecisionVO initial=decision("131:0","designResponsibilities");
        ExtractionDecisionVO complete=decision("136:0","designResponsibilities");
        assertEquals("accepted",initial.getStatus());assertEquals("accepted",complete.getStatus());
        assertTrue(complete.getSourceQuote().startsWith("This is the complete component responsibility schedule"));
        assertTrue(complete.getCodes().contains("evidence_quote_reanchored"));
        List<CandidateVO> candidates=Arrays.asList(candidate(initial),candidate(complete));
        assertEquals(complete.getNormalizedValue(),DraftDesignCandidates.merge(DraftBlueprint.find("designResponsibilities"),candidates));
        Collections.reverse(candidates);
        assertEquals(complete.getNormalizedValue(),DraftDesignCandidates.merge(DraftBlueprint.find("designResponsibilities"),candidates));
    }

    @Test void constructionResponsibilitiesDoNotProveTheStructuresServedByFootings() throws Exception {
        ExtractionDecisionVO wrong=decision("131:0","footingsServeBuildingsOrMajorExternalStructures");
        assertEquals("rejected",wrong.getStatus());assertTrue(wrong.getCodes().contains("quote_value_mismatch"));
        assertEquals("accepted",decision("136:0","footingsServeBuildingsOrMajorExternalStructures").getStatus());
    }

    @Test void oneBlockLocationDoesNotProveProjectWideAbsence() throws Exception {
        ExtractionDecisionVO wrong=decision("140:0","projectInTinShuiWai");
        assertEquals("rejected",wrong.getStatus());assertTrue(wrong.getCodes().contains("quote_value_mismatch"));
        assertEquals("accepted",decision("131:0","projectInTinShuiWai").getStatus());
        assertEquals("accepted",decision("138:0","projectInTinShuiWai").getStatus());
    }

    @Test void capturedBlockLabelsBecomeIdentifiersForTheExistingTemplatePrefix() throws Exception {
        assertEquals("E",decision("141:0","specificationInspectionBlock").getNormalizedValue());
        assertEquals("F",decision("141:0","drawingsInspectionBlock").getNormalizedValue());
    }

    @Test void scopeRelationshipsNeedTheirOwnSubjectAndPolarity() {
        for(String quote:Arrays.asList("Please confirm whether the footings serve buildings.",
                "The footings do not serve buildings or major external structures.",
                "The Architect designs the footings. The Contractor constructs buildings.",
                "The Contractor builds footings for small fences only."))assertIntake("footingsServeBuildingsOrMajorExternalStructures",true,quote,"rejected");
        assertIntake("footingsServeBuildingsOrMajorExternalStructures",false,"The footings do not serve buildings or major external structures.","accepted");
        assertIntake("footingsServeBuildingsOrMajorExternalStructures",true,"The Contractor constructs shallow footings for the buildings within the same contract.","accepted");
        for(String quote:Arrays.asList("The domestic block is in Sha Tin.","Please confirm whether the project is in Tin Shui Wai.",
                "A neighbouring project is in Tin Shui Wai.","The project is in Sha Tin. One block is in Tin Shui Wai."))
            assertIntake("projectInTinShuiWai",false,quote,"rejected");
        assertIntake("projectInTinShuiWai",true,"Contract No. 20260783, Construction of Community Facilities at Tin Shui Wai Area 117.","accepted");
        assertIntake("projectInTinShuiWai",false,"There are no works in Tin Shui Wai.","accepted");
        assertIntake("projectInTinShuiWai",true,"The project is not in Tin Shui Wai.","rejected");
        assertIntake("projectInTinShuiWai",true,"A neighbouring project is in Tin Shui Wai.","rejected");
    }

    @Test void designPrefaceCannotBeBorrowedOrTruncatedToApproveASubset() {
        String table="Component | Scope | Contractor executes | Contractor designs\npiling | North hall piles | Yes | Yes\nfootings | South hall footings | Yes | No";
        Object full=DraftBusinessRules.parse("[{\"component\":\"piling\",\"scope\":\"North hall piles\",\"design\":true,\"execution\":true},{\"component\":\"footings\",\"scope\":\"South hall footings\",\"design\":false,\"execution\":true}]");
        String source="The complete component responsibility schedule is:\n"+table;
        assertEquals("accepted",intake("designResponsibilities",full,table,source,source).getStatus());
        assertEquals("rejected",intake("designResponsibilities",full,table,table,source).getStatus());
        assertEquals("rejected",intake("designResponsibilities",Collections.singletonList(DraftBusinessRules.list(full).get(0)),table,source,source).getStatus());
        for(String prefix:Arrays.asList("If the option is selected:","The complete component responsibility schedule remains pending.")) {
            String qualified=prefix+"\n"+table;
            assertEquals("rejected",intake("designResponsibilities",full,table,qualified,qualified).getStatus(),prefix);
        }
        String unrelated="The complete component responsibility schedule is elsewhere.\n\nThis table is partial:\n"+table;
        ExtractionDecisionVO result=intake("designResponsibilities",full,table,unrelated,unrelated);
        assertEquals("accepted",result.getStatus());assertFalse(result.getSourceQuote().contains("elsewhere"));
    }

    @Test void blockLabelsAreRemovedOnceWithoutEditingManualStorageOrUnrelatedText() {
        assertEquals("E",DraftInputRules.normalizeSuggestion(DraftBlueprint.find("specificationInspectionBlock"),"Block E"));
        assertEquals("Block E",DraftInputRules.normalize(DraftBlueprint.find("specificationInspectionBlock"),"Block E"));
        assertEquals("Blockbuster",DraftInputRules.normalizeSuggestion(DraftBlueprint.find("drawingsInspectionBlock"),"Blockbuster"));
        for(String block:Arrays.asList("E","Block E")) {
            Map<String,Object> values=new LinkedHashMap<>();values.put("specificationInspectionFloor","7");values.put("specificationInspectionBlock",block);
            Map<String,Object> plan=DraftBusinessRules.plan(values);
            String source=DraftBusinessRules.standardParagraph("SCT",926)+"\n"+DraftBusinessRules.standardParagraph("SCT",928);
            String output=DraftClauseRules.apply(source,"SCT",plan);
            assertTrue(output.contains("Block E"));assertFalse(output.contains("Block Block E"));
        }
    }

    private static void assertIntake(String key,Object value,String quote,String expected) {
        ExtractionDecisionVO result=intake(key,value,quote,quote,quote);
        assertEquals(expected,result.getStatus(),quote+" "+result.getCodes());
    }
    private static ExtractionDecisionVO intake(String key,Object value,String quote,String supplied,String original) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);item.put("confidence",.98);
        ExtractionPartVO part=new ExtractionPartVO("boundary:0",1L,"source.txt","test-source",0,supplied,new ArrayList<>());
        part.setContext(new ExtractionContextVO("boundary",Collections.emptyList(),original.indexOf(supplied),original.indexOf(supplied)+supplied.length(),supplied,null));
        return DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
    }

    private static ExtractionDecisionVO decision(String id,String key) throws Exception {
        JsonNode fixture;
        try(InputStream in=DraftRoundOneSourceContextRegressionTest.class.getResourceAsStream("/drafting/harness/captured-round1-source-context.json")) {
            assertNotNull(in);fixture=JsonUtils.mapper().readTree(in);
        }
        assertEquals("12d06aec-a541-4e21-a400-0632b80d0d3e",fixture.path("provenance").path("runId").asText());
        for(JsonNode record:fixture.path("records"))if(id.equals(record.path("part").path("partId").asText())) {
            ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
            return DraftHarnessTestIntake.primaryDecisions(record.path("raw").asText(),part,record.path("originalSource").asText())
                    .stream().filter(d->key.equals(d.getKey())).findFirst().orElseThrow(AssertionError::new);
        }
        throw new AssertionError(id);
    }
    private static CandidateVO candidate(ExtractionDecisionVO d) {
        return new CandidateVO(d.getNormalizedValue(),1L,"captured-source.docx","source-hash",d.getSourceQuote(),d.getReason(),d.getConfidence());
    }
}
