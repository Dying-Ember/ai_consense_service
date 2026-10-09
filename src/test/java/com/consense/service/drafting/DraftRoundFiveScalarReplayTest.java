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

/** Unchanged recorded primary/repair/recall replies use public decoding and evidence intake. */
class DraftRoundFiveScalarReplayTest {
    @Test void capturedIssueFormatKeepsItsDirectBqRole() throws Exception { replay("electronicTendering"); }
    @Test void capturedFootingsServiceKeepsItsBuildingObject() throws Exception { replay("footingsServeBuildingsOrMajorExternalStructures"); }
    @Test void capturedSeparateBuildingAndDemolitionSitesRemainUsable() throws Exception { replay("buildingDemolitionSitesSeparated"); }

    private static void replay(String key) throws Exception {
        JsonNode fixture;
        try(InputStream in=DraftRoundFiveScalarReplayTest.class.getResourceAsStream("/drafting/harness/captured-round5-scalars.json")) {
            assertNotNull(in);fixture=JsonUtils.mapper().readTree(in);
        }
        assertEquals("80d0396f-1a3c-4ed0-8232-5c21e966a209",fixture.path("provenance").path("runId").asText());
        assertEquals(8,fixture.path("records").size());
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(JsonNode record:fixture.path("records")) {
            JsonNode attempt=record.path("attempt"),captured=record.path("originalDecision");
            if(!key.equals(captured.path("key").asText()))continue;
            checks.add(()-> {
                int ordinal=attempt.path("attemptIndex").asInt();
                assertEquals("rejected",captured.path("status").asText());
                String raw=attempt.path("rawResponse").asText(),original=record.path("originalSource").asText();
                assertEquals(record.path("rawResponseSha256").asText(),sha(raw));
                ExtractionPartVO part=JsonUtils.mapper().treeToValue(record.path("part"),ExtractionPartVO.class);
                part.setContext(JsonUtils.mapper().treeToValue(attempt.path("context"),ExtractionContextVO.class));
                assertEquals(part.getSourceHash(),sha("PROJECT_INPUT\nnull\nPARSED\n"+original));
                assertEquals(part.getContext().getSourceText(),original.substring(part.getContext().getSourceStart(),part.getContext().getSourceEnd()));
                List<ExtractionDecisionVO> actual=DraftHarnessTestIntake.primaryDecisions(raw,part,original,ordinal,
                        attempt.path("systemPrompt").asText(),attempt.path("userPrompt").asText());
                ExtractionDecisionVO decision=actual.stream().filter(d->key.equals(d.getKey())&&d.getItemIndex()==captured.path("itemIndex").asInt()).findFirst().orElseThrow(AssertionError::new);
                assertEquals(captured.path("rawValue"),JsonUtils.mapper().valueToTree(decision.getRawValue()));
                assertEquals(captured.path("sourceQuote").asText(),decision.getSourceQuote());assertEquals(captured.path("reason").asText(),decision.getReason());
                System.out.println("ROUND5_SCALAR_REPLAY key="+key+" attempt="+ordinal+" status="+decision.getStatus()+" codes="+decision.getCodes());
                assertEquals("accepted",decision.getStatus(),key+" attempt "+ordinal+" "+decision.getCodes());
                assertEquals(captured.path("normalizedValue").asText(),decision.getNormalizedValue());
            });
        }
        assertEquals("buildingDemolitionSitesSeparated".equals(key)?2:3,checks.size());assertAll(key+" exact captured attempts",checks);
    }
    private static String sha(String source) throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)))result.append(String.format("%02x",b&255));return result.toString();
    }
}
