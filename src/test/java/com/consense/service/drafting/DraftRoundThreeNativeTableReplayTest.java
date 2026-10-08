package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Whole frozen model answers and their actual attempt bounds at the public harness intake. */
class DraftRoundThreeNativeTableReplayTest {
    @Test void pricingPrimaryKeepsSameNumberSeparateDocumentRows() throws Exception { replay("168:0",1,"billNos"); }
    @Test void pricingRepairKeepsAllSevenRows() throws Exception { replay("168:0",2,"billNos"); }
    @Test void designPrimaryKeepsSeparateDesignAndConstructionRoles() throws Exception { replay("174:0",1,"designResponsibilities"); }
    @Test void designRepairKeepsAllFourRows() throws Exception { replay("174:0",2,"designResponsibilities"); }
    @Test void designRecallKeepsItsExactBoundedContext() throws Exception { replay("174:0",3,"designResponsibilities"); }
    @Test void sectionsPrimaryKeepsThreeFormalIdentities() throws Exception { replay("177:0",1,"sections"); }
    @Test void sectionsRepairKeepsLocationsOnTheirOwnRows() throws Exception { replay("177:0",2,"sections"); }
    @Test void initialDesignPrimaryWithoutExecutionRemainsRejected() throws Exception { replay("170:0",1,"designResponsibilities"); }
    @Test void initialDesignRepairWithoutJointRolesRemainsRejected() throws Exception { replay("170:0",2,"designResponsibilities"); }
    @Test void initialDesignRecallCannotBorrowTheArchitectsRole() throws Exception { replay("170:0",3,"designResponsibilities"); }

    private static void replay(String id,int ordinal,String key) throws Exception {
        JsonNode fixture;
        try(InputStream in=DraftRoundThreeNativeTableReplayTest.class.getResourceAsStream("/drafting/harness/captured-round3-native-tables.json")) {
            assertNotNull(in);byte[] bytes=in.readAllBytes();
            assertEquals("7f2f2617843138ace90f4b7a44315db5abad58b2fb9afc60b028462156e3d0b6",sha(new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n")));
            fixture=JsonUtils.mapper().readTree(bytes);
        }
        assertEquals("a8f32dc6-0c01-4a1d-8d14-58e37882a540",fixture.path("provenance").path("runId").asText());
        assertEquals("6b79383871a831229b6acb26c17f92f81465e8c40bec929d230ed8bc5d397816",fixture.path("provenance").path("traceSha256").asText());
        assertEquals("90e710e9564e072a5dfbbaf177e5d8bd3a1c2f06f5416e32d46ecd33c8c35b65",fixture.path("provenance").path("inputsSha256").asText());
        assertEquals(10,fixture.path("records").size());
        for(JsonNode record:fixture.path("records")) {
            JsonNode captured=record.path("originalDecision"),attempt=record.path("attempt");
            if(!id.equals(captured.path("partId").asText())||ordinal!=captured.path("attemptIndex").asInt()||!key.equals(captured.path("key").asText()))continue;
            String raw=attempt.path("rawResponse").asText(),original=record.path("originalSource").asText();
            assertEquals(record.path("rawResponseSha256").asText(),sha(raw));
            ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
            assertEquals(part.getSourceHash(),sha("PROJECT_INPUT\nnull\nPARSED\n"+original));
            assertEquals(JsonUtils.mapper().valueToTree(JsonUtils.mapper().treeToValue(attempt.path("context"),ExtractionContextVO.class)),JsonUtils.mapper().valueToTree(part.getContext()));
            assertEquals(part.getContext().getSourceText(),original.substring(part.getContext().getSourceStart(),part.getContext().getSourceEnd()));
            List<ExtractionDecisionVO> actual=DraftHarnessTestIntake.primaryDecisions(raw,part,original,ordinal,
                    attempt.path("systemPrompt").asText(),attempt.path("userPrompt").asText());
            ExtractionDecisionVO decision=actual.stream().filter(d->key.equals(d.getKey())&&d.getItemIndex()==captured.path("itemIndex").asInt()).findFirst().orElseThrow(AssertionError::new);
            assertEquals(captured.path("rawValue"),JsonUtils.mapper().valueToTree(decision.getRawValue()));
            String capturedQuote=captured.path("sourceQuote").asText();
            if(!capturedQuote.equals(decision.getSourceQuote())) {
                assertTrue(decision.getCodes().contains("evidence_quote_reanchored"));
                assertTrue(original.contains(decision.getSourceQuote()),"Recovery must be an exact contiguous original-source slice.");
                assertTrue(DraftEvidenceQuotes.present(original,decision.getSourceQuote()));
                assertTrue(DraftEvidenceQuotes.present(decision.getSourceQuote(),capturedQuote));
            }
            assertEquals(captured.path("reason").asText(),decision.getReason());
            assertEquals(captured.path("confidence").asDouble(),decision.getConfidence());
            String expected=record.path("expectedStatus").asText();
            System.out.println("ROUND3_TABLE_REPLAY "+id+" "+ordinal+" "+key+" expected="+expected+" actual="+decision.getStatus()+" codes="+decision.getCodes());
            assertEquals(expected,decision.getStatus());
            if("accepted".equals(expected)) {
                JsonNode normalized=JsonUtils.parse(decision.getNormalizedValue());
                assertEquals(captured.path("rawValue").size(),normalized.size());
                for(int i=0;i<normalized.size();i++) {
                    String[] fields="billNos".equals(key)?new String[]{"number","description","type"}:
                            "sections".equals(key)?new String[]{"designation","workTypes","location"}:new String[]{"component","scope","design","execution"};
                    for(String field:fields)assertEquals(captured.path("rawValue").get(i).path(field),normalized.get(i).path(field),field);
                    if("billNos".equals(key)) {
                        assertFalse(normalized.get(i).has("purpose"));assertFalse(normalized.get(i).has("trade"));
                    }
                }
            } else assertTrue(decision.getCodes().contains("quote_value_mismatch"));
            return;
        }
        throw new AssertionError(id+" "+ordinal+" "+key);
    }
    private static String sha(String source) throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)))result.append(String.format("%02x",b&255));return result.toString();
    }
}
