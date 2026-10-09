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

/** Actual frozen model replies reach the real public decoding and evidence intake. */
class DraftRoundFourDesignReplayTest {
    @Test void primaryRetainsCompleteEmployerDesignContractorExecutionTable() throws Exception { replay(1); }
    @Test void repairRetainsCompleteEmployerDesignContractorExecutionTable() throws Exception { replay(2); }
    @Test void recallRetainsCompleteEmployerDesignContractorExecutionTable() throws Exception { replay(3); }

    private static void replay(int ordinal) throws Exception {
        JsonNode fixture;
        try(InputStream in=DraftRoundFourDesignReplayTest.class.getResourceAsStream("/drafting/harness/captured-round4-design.json")) {
            assertNotNull(in);byte[] bytes=in.readAllBytes();
            assertEquals("f9c3811bb78581af73a6d35368c65344ec1d17ca0ad3e2477654f4f1150e42d6",sha(new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n")));
            fixture=JsonUtils.mapper().readTree(bytes);
        }
        assertEquals("4274693a-eb38-46d3-acfb-7559ef5c6587",fixture.path("provenance").path("runId").asText());
        assertEquals("23d3a847b0b3519b4e84141c74ef987f50a96360a07df8189c2bce76660b7bb0",fixture.path("provenance").path("traceSha256").asText());
        assertEquals(3,fixture.path("records").size());
        JsonNode record=fixture.path("records").get(ordinal-1),attempt=record.path("attempt"),captured=record.path("originalDecision");
        assertEquals(ordinal,attempt.path("attemptIndex").asInt());
        assertEquals("rejected",captured.path("status").asText());
        String raw=attempt.path("rawResponse").asText(),original=record.path("originalSource").asText();
        assertEquals(record.path("rawResponseSha256").asText(),sha(raw));
        ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
        assertEquals(part.getSourceHash(),sha("PROJECT_INPUT\nnull\nPARSED\n"+original));
        assertEquals(part.getContext().getSourceText(),original.substring(part.getContext().getSourceStart(),part.getContext().getSourceEnd()));
        List<ExtractionDecisionVO> actual=DraftHarnessTestIntake.primaryDecisions(raw,part,original,ordinal,
                attempt.path("systemPrompt").asText(),attempt.path("userPrompt").asText());
        ExtractionDecisionVO decision=actual.stream().filter(d->"designResponsibilities".equals(d.getKey())&&d.getItemIndex()==captured.path("itemIndex").asInt()).findFirst().orElseThrow(AssertionError::new);
        assertEquals(captured.path("rawValue"),JsonUtils.mapper().valueToTree(decision.getRawValue()));
        assertEquals(captured.path("reason").asText(),decision.getReason());
        System.out.println("ROUND4_DESIGN_REPLAY attempt="+ordinal+" status="+decision.getStatus()+" codes="+decision.getCodes());
        assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());
        assertEquals(captured.path("rawValue"),JsonUtils.parse(decision.getNormalizedValue()));
        assertEquals(4,JsonUtils.parse(decision.getNormalizedValue()).size());
    }
    private static String sha(String source) throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)))result.append(String.format("%02x",b&255));return result.toString();
    }
}
