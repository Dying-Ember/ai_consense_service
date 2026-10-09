package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Correct source floors must fit the actual SCT scalar slot without changing model RAW. */
class DraftFloorSuggestionNormalizationTest {
    private static final List<String> KEYS=Arrays.asList("specificationInspectionFloor","drawingsInspectionFloor");

    @Test void capturedFirstTryFloorsKeepRawEvidenceAndNormalizeAtPublicIntake() throws Exception {
        List<ExtractionDecisionVO> decisions=capturedDecisions();
        for(int index=0;index<KEYS.size();index++) {
            String key=KEYS.get(index),identifier=index==0?"6":"2";
            ExtractionDecisionVO decision=decisions.stream().filter(d->key.equals(d.getKey())).findFirst().orElseThrow(AssertionError::new);
            assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());
            assertEquals(JsonUtils.mapper().valueToTree("floor "+identifier),decision.getRawValue());
            assertTrue(decision.getSourceQuote().contains("on floor "+identifier+" of Block "));
            assertEquals(identifier,decision.getNormalizedValue());
        }
    }

    @Test void capturedNormalizedSuggestionsCompileIntoNativeSctWithOneFloorSuffix() throws Exception {
        String configured=System.getProperty("consense.acceptance.sourceDir");
        assumeTrue(configured!=null,"Point consense.acceptance.sourceDir at the actual competition standards.");
        Map<String,Object> inputs=new LinkedHashMap<>();
        for(ExtractionDecisionVO decision:capturedDecisions())if(KEYS.contains(decision.getKey()))inputs.put(decision.getKey(),decision.getNormalizedValue());
        inputs.put("specificationInspectionBlock","U");inputs.put("drawingsInspectionBlock","V");
        Map<String,Object> plan=DraftBusinessRules.plan(inputs);
        plan.put("actions",DraftBusinessRules.list(plan.get("actions")).stream().filter(raw->{
            String id=String.valueOf(DraftBusinessRules.asMap(raw).get("id"));
            return "sct-specification-inspection-address".equals(id)||"sct-drawings-inspection-address".equals(id);
        }).collect(Collectors.toList()));
        byte[] original=Files.readAllBytes(Paths.get(configured).resolve("02_Special Conditions of Tender (SCT).docx"));
        DocxTemplateEditor.DocxEditResult result=DraftFormattedRules.compile(original,"SCT",plan);
        String text=DraftFormattedRules.content(result.getIndex());
        assertTrue(text.contains("at the 6 floor, Block U"));
        assertTrue(text.contains("at the 2 floor, Block V"));
        assertFalse(text.contains("floor 6 floor"));assertFalse(text.contains("floor 2 floor"));
        for(Object raw:DraftBusinessRules.list(plan.get("actions")))assertEquals("applied",DraftBusinessRules.asMap(raw).get("application"));
    }

    @Test void onlySingleNumericFloorLabelsLoseTheirLeadingLabel() {
        for(String key:KEYS) {
            DraftBlueprint.InputSpec spec=DraftBlueprint.find(key);
            assertEquals("6",DraftInputRules.normalizeSuggestion(spec,"floor 6"));
            assertEquals("6th",DraftInputRules.normalizeSuggestion(spec,"  Floor 6th  "));
            assertEquals("2nd",DraftInputRules.normalizeSuggestion(spec,"FLOOR 2nd"));
            for(String identifier:Arrays.asList("6","6th","2nd","ground","B2"))assertEquals(identifier,DraftInputRules.normalizeSuggestion(spec,identifier));
            assertEquals("floor 6",DraftInputRules.normalize(spec,"floor 6"),"Manual storage remains exact.");
        }
    }

    @Test void ambiguousAndDescriptiveTextRemainsUnchanged() {
        for(String key:KEYS)for(String value:Arrays.asList("floor 6 or 7","floor 6 to 8","floors 6 and 7","floor 6/7","floor 6-7","floor plan 6","floor Lobby","floor B2","floor six","level 6","6/F","ground floor"))
            assertEquals(value,DraftInputRules.normalizeSuggestion(DraftBlueprint.find(key),value));
        assertEquals("floor 6",DraftInputRules.normalizeSuggestion(DraftBlueprint.find("specificationInspectionBlock"),"floor 6"));
        assertEquals("floor 6",DraftInputRules.normalizeSuggestion(DraftBlueprint.find("projectArchitectPost"),"floor 6"));
    }

    @Test void normalizationDoesNotRescueAnUnsupportedFloorValue() {
        String quote="The drawings are available for inspection on floor 2 of Block V at the Fictional Tender Information Centre.";
        Map<String,Object> item=DraftBusinessRules.map("key","drawingsInspectionFloor","value","floor 9","sourceQuote",quote,"confidence",.99);
        ExtractionPartVO part=new ExtractionPartVO("floor-negative:0",1L,"floor-negative.txt","source-hash",0,quote,new ArrayList<>());
        part.setContext(new ExtractionContextVO("inspection",Collections.singletonList("drawingsInspectionFloor"),0,quote.length(),quote,null));
        ExtractionDecisionVO decision=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,quote);
        assertEquals(JsonUtils.mapper().valueToTree("floor 9"),decision.getRawValue());assertEquals("rejected",decision.getStatus());
    }

    private static List<ExtractionDecisionVO> capturedDecisions() throws Exception {
        JsonNode fixture;
        try(InputStream in=DraftFloorSuggestionNormalizationTest.class.getResourceAsStream("/drafting/harness/captured-round5-floor-values.json")) {
            assertNotNull(in);fixture=JsonUtils.mapper().readTree(in);
        }
        assertEquals("80d0396f-1a3c-4ed0-8232-5c21e966a209",fixture.path("provenance").path("runId").asText());
        String raw=fixture.path("rawResponse").asText(),original=fixture.path("originalSource").asText();
        assertEquals(fixture.path("rawResponseSha256").asText(),sha(raw));
        ExtractionPartVO part=JsonUtils.mapper().treeToValue(fixture.path("part"),ExtractionPartVO.class);
        assertEquals(part.getSourceHash(),sha("PROJECT_INPUT\nnull\nPARSED\n"+original));
        List<ExtractionDecisionVO> decisions=DraftHarnessTestIntake.primaryDecisions(raw,part,original,1,fixture.path("systemPrompt").asText(),fixture.path("userPrompt").asText());
        for(JsonNode captured:fixture.path("originalDecisions")) {
            ExtractionDecisionVO decision=decisions.stream().filter(d->captured.path("key").asText().equals(d.getKey())).findFirst().orElseThrow(AssertionError::new);
            assertEquals(captured.path("rawValue"),JsonUtils.mapper().valueToTree(decision.getRawValue()));
            assertEquals(captured.path("sourceQuote").asText(),decision.getSourceQuote());
            assertEquals(captured.path("reason").asText(),decision.getReason());
        }
        return decisions;
    }
    private static String sha(String value) throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))result.append(String.format("%02x",b&255));return result.toString();
    }
}
