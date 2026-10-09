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

/** Three actual frozen model replies go through public decoding and evidence intake. */
class DraftRoundFourQuantityReplayTest {
    @Test void primaryRetainsUniversalBqQuantityStatement() throws Exception { replay(1); }
    @Test void repairRetainsUniversalBqQuantityStatement() throws Exception { replay(2); }
    @Test void recallRetainsUniversalBqQuantityStatement() throws Exception { replay(3); }

    private static void replay(int ordinal) throws Exception {
        JsonNode fixture;
        try(InputStream in=DraftRoundFourQuantityReplayTest.class.getResourceAsStream("/drafting/harness/captured-round4-quantity.json")) {
            assertNotNull(in);fixture=JsonUtils.mapper().readTree(in);
        }
        assertEquals("4274693a-eb38-46d3-acfb-7559ef5c6587",fixture.path("provenance").path("runId").asText());
        assertEquals(3,fixture.path("records").size());
        JsonNode record=fixture.path("records").get(ordinal-1),attempt=record.path("attempt"),captured=record.path("originalDecision");
        assertEquals(ordinal,attempt.path("attemptIndex").asInt());assertEquals("rejected",captured.path("status").asText());
        String raw=attempt.path("rawResponse").asText(),original=record.path("originalSource").asText();
        assertEquals(record.path("rawResponseSha256").asText(),sha(raw));
        ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
        assertEquals(part.getSourceHash(),sha("PROJECT_INPUT\nnull\nPARSED\n"+original));
        assertEquals(part.getContext().getSourceText(),original.substring(part.getContext().getSourceStart(),part.getContext().getSourceEnd()));
        List<ExtractionDecisionVO> actual=DraftHarnessTestIntake.primaryDecisions(raw,part,original,ordinal,
                attempt.path("systemPrompt").asText(),attempt.path("userPrompt").asText());
        ExtractionDecisionVO decision=actual.stream().filter(d->"allBqQuantitiesProvisional".equals(d.getKey())&&d.getItemIndex()==captured.path("itemIndex").asInt()).findFirst().orElseThrow(AssertionError::new);
        assertEquals(captured.path("rawValue"),JsonUtils.mapper().valueToTree(decision.getRawValue()));
        assertEquals(captured.path("sourceQuote").asText(),decision.getSourceQuote());assertEquals(captured.path("reason").asText(),decision.getReason());
        System.out.println("ROUND4_QUANTITY_REPLAY attempt="+ordinal+" status="+decision.getStatus()+" codes="+decision.getCodes());
        assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());assertEquals("true",decision.getNormalizedValue());
    }
    private static String sha(String source) throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)))result.append(String.format("%02x",b&255));return result.toString();
    }
}
