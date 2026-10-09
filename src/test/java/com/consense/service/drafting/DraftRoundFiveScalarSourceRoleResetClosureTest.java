package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen independent reset/prefix/adjacent-paragraph boundaries; no extra syntax scenarios. */
class DraftRoundFiveScalarSourceRoleResetClosureTest {
 @TestFactory Stream<DynamicTest> twelveUnchangedIndependentRoleResetCases() throws Exception {
  JsonNode fixture;
  try(InputStream in=getClass().getResourceAsStream("/drafting/harness/captured-round5-scalar-independent-role-reset.json")) {
   assertNotNull(in);fixture=JsonUtils.mapper().readTree(in);
  }
  assertEquals(12,fixture.path("cases").size());
  return StreamSupport.stream(fixture.path("cases").spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()-> {
   ExtractionDecisionVO d=DraftRoundFiveScalarSourceRoleClosureTest.intake(c.path("key").asText(),JsonUtils.mapper().convertValue(c.path("value"),Object.class),c.path("quote").asText(),c.path("source").asText());
   System.out.println("ROUND5_SOURCE_ROLE_RESET_CLOSURE "+c.path("name").asText()+" "+d.getStatus()+" "+d.getCodes());
   assertEquals(c.path("expectedStatus").asText(),d.getStatus(),c.path("name").asText()+" "+d.getCodes());
  }));
 }
}
