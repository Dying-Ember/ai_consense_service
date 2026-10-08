package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.ai.AiGateway;
import com.consense.ai.LlmClient;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
class IndependentTenderMediaSpecProbeTest {
 @Test void explicitBqIssueRemainsUsableDespiteSeparateConvenienceCopyMedia() { check("L10Pro", "The BQ issue format is L10Pro, with hardcopy convenience copies supplied to tenderers.", "accepted"); }
 @Test void explicitBqIssueRemainsUsableDespiteSeparateReturnMedia() { check("L10Pro", "The BQ issue format is L10Pro and hardcopy tender documents are required for return.", "accepted"); }
 @Test void dependentDecisionUnconfirmedCannotSupplyAnAdoptedIssueFormat() { check("Hardcopy", "The BQ issue format is Hardcopy. This decision remains unconfirmed.", "rejected"); }
 @Test void dependentDecisionPendingCannotSupplyAnAdoptedIssueFormat() { check("Hardcopy", "The BQ issue format is Hardcopy. This decision is pending.", "rejected"); }
 @Test void sorOnlyRealDefectRemainsRejected() { check("Hardcopy", "Both SOR detailed schedules and subsequent addenda are issued in hardcopy.", "rejected"); }
 @Test void directBqDecisionRemainsAccepted() { check("Hardcopy", "The BQ issue format is Hardcopy.", "accepted"); }
 @Test void negatingTheOtherChoiceDoesNotNegateTheAdoptedChoice() { check("Hardcopy", "The BQ issue format is not L10Pro but Hardcopy.", "accepted"); }
 private static void check(String value,String source,String expected) {
  Map<String,Object> item=new LinkedHashMap<>();item.put("key","electronicTendering");item.put("value",value);item.put("sourceQuote",source);item.put("reason","Independent offline public-seam spec counterexample");item.put("confidence",.95);
  String raw=JsonUtils.write(Collections.singletonList(item));
  LlmClient client=mock(LlmClient.class);when(client.available()).thenReturn(true);when(client.chat(anyList())).thenReturn(raw,"[]");
  DraftExtractionHarness harness=new DraftExtractionHarness(new AiGateway(client));
  ExtractionContextVO context=new ExtractionContextVO("independent-media-review",Collections.emptyList(),0,source.length(),source,null);
  ExtractionPartVO part=new ExtractionPartVO("media-review:0",1L,"independent-media-review.txt","source",0,source,new ArrayList<>());part.setContext(context);
  ExtractTraceVO trace=new ExtractTraceVO();trace.setRawResponses(new ArrayList<>());
  harness.extractPart(trace,part,harness.systemPrompt("Independent source intake review."),harness.userPrompt("%s\n%s",part),source);
  ExtractionDecisionVO d=trace.getDecisions().stream().filter(x->x.getAttemptIndex()==1&&x.getItemIndex()==0).findFirst().orElseThrow(AssertionError::new);
  System.out.println("INDEPENDENT_MEDIA_PROBE " + JsonUtils.write(Arrays.asList(value,source,expected,d.getStatus(),d.getCodes(),d.getRawValue(),d.getNormalizedValue(),d.getSourceQuote())));
  assertEquals(source,d.getSourceQuote());assertEquals(JsonUtils.write(value),JsonUtils.write(d.getRawValue()));assertEquals(expected,d.getStatus(),source+" "+d.getCodes());
 }
}
