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

/** Exact real replies at public intake, including each repair/recall's original bounded context. */
class DraftRoundTwoSourceScopeRegressionTest {
    @Test void housingTypePrimaryIsAnActualDomesticBlock() throws Exception { replay("149:0",1,"domesticBlocks"); }
    @Test void housingTypeRepairIsAnActualDomesticBlock() throws Exception { replay("149:0",2,"domesticBlocks"); }
    @Test void housingTypeRecallIsAnActualDomesticBlock() throws Exception { replay("149:0",3,"domesticBlocks"); }
    @Test void domesticConstructionMethodRecallEstablishesScope() throws Exception { replay("151:0",2,"domesticBlocks"); }
    @Test void footingsPronounPrimaryKeepsItsExplicitSubject() throws Exception { replay("156:0",1,"footingsServeBuildingsOrMajorExternalStructures"); }
    @Test void footingsPronounRepairKeepsItsExplicitSubject() throws Exception { replay("156:0",2,"footingsServeBuildingsOrMajorExternalStructures"); }
    @Test void footingsPronounRecallKeepsItsExplicitSubject() throws Exception { replay("156:0",3,"footingsServeBuildingsOrMajorExternalStructures"); }
    @Test void expressParcelSeparationRemainsUsable() throws Exception { replay("158:0",1,"buildingDemolitionSitesSeparated"); }
    @Test void aStandaloneBuildingCannotProveSeparationOfWorkSites() throws Exception { replay("160:0",1,"buildingDemolitionSitesSeparated"); }

    private static void replay(String id,int ordinal,String key) throws Exception {
        JsonNode fixture=fixture();
        for(JsonNode record:fixture.path("records")) {
            JsonNode captured=record.path("originalDecision"),attempt=record.path("attempt");
            if(!id.equals(captured.path("partId").asText())||ordinal!=captured.path("attemptIndex").asInt()||!key.equals(captured.path("key").asText()))continue;
            String raw=attempt.path("rawResponse").asText();
            assertEquals(record.path("rawResponseSha256").asText(),sha(raw.getBytes(StandardCharsets.UTF_8)));
            ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
            assertEquals(JsonUtils.mapper().valueToTree(JsonUtils.mapper().treeToValue(attempt.path("context"),ExtractionContextVO.class)),
                    JsonUtils.mapper().valueToTree(part.getContext()));
            String original=record.path("originalSource").asText();
            assertEquals(part.getSourceHash(),sha(("PROJECT_INPUT\nnull\nPARSED\n"+original).getBytes(StandardCharsets.UTF_8)));
            assertEquals(part.getContext().getSourceText(),original.substring(part.getContext().getSourceStart(),part.getContext().getSourceEnd()));
            List<ExtractionDecisionVO> results=DraftHarnessTestIntake.primaryDecisions(raw,part,record.path("originalSource").asText(),
                    ordinal,attempt.path("systemPrompt").asText(),attempt.path("userPrompt").asText());
            ExtractionDecisionVO actual=results.stream().filter(d->key.equals(d.getKey())&&d.getItemIndex()==captured.path("itemIndex").asInt())
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals(captured.path("rawValue"),JsonUtils.mapper().valueToTree(actual.getRawValue()));
            assertEquals(captured.path("normalizedValue").asText(),actual.getNormalizedValue());
            assertEquals(captured.path("sourceQuote").asText(),actual.getSourceQuote());
            assertEquals(captured.path("reason").asText(),actual.getReason());
            assertEquals(captured.path("confidence").asDouble(),actual.getConfidence());
            String expected=record.path("expectedStatus").asText();
            System.out.println("ROUND2_SCOPE_REPLAY "+JsonUtils.write(java.util.Arrays.asList(id,ordinal,key,expected,actual.getStatus(),actual.getCodes())));
            assertEquals(expected,actual.getStatus(),id+" attempt "+ordinal+" "+key+" "+actual.getCodes());
            if("rejected".equals(expected))assertTrue(actual.getCodes().contains("quote_value_mismatch"));
            return;
        }
        throw new AssertionError(id+" "+ordinal+" "+key);
    }

    private static JsonNode fixture() throws Exception {
        byte[] bytes;
        try(InputStream in=DraftRoundTwoSourceScopeRegressionTest.class.getResourceAsStream("/drafting/harness/captured-round2-source-scope.json")) {
            assertNotNull(in);bytes=in.readAllBytes();
        }
        // Original CRLF archive SHA-256: 57788c99515d8922f2dcb2176f9779598db9410314f5a3f9ac2f21028da8f708.
        // Git text checkout may change the JSON envelope's line endings; decoded evidence and its hashes remain exact.
        byte[] canonicalEnvelope=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
        assertEquals("b76b5d75c001a4e1f2e8b90e2d82a0e6aefeaff901aed4bfcacd071a07dd5cac",sha(canonicalEnvelope));
        JsonNode fixture=JsonUtils.mapper().readTree(bytes);
        assertEquals(9,fixture.path("records").size());
        assertEquals("24ec77b5-5695-41fb-934f-bbb2717f8567",fixture.path("provenance").path("runId").asText());
        assertEquals("92bebbec8b66ed060153d536d41eecd52d4b4d38237e588c08cbe338b950b885",fixture.path("provenance").path("traceSha256").asText());
        assertEquals("caf1d5a48d817e335fc40586576404558ae95c7e66a777462e888f1ef7ad22a7",fixture.path("provenance").path("inputsSha256").asText());
        return fixture;
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format("%02x",b&255));
        return result.toString();
    }
}
