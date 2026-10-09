package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact independent public-intake counterexamples, without changing their source or expected verdict. */
class DraftRoundFiveScalarSourceRoleClosureTest {
 @TestFactory Stream<DynamicTest> fourUnchangedIndependentSourceRoleCases() throws Exception {
  return independentCases("captured-round5-scalar-independent-source-guards.json",4);
 }
 @TestFactory Stream<DynamicTest> threeUnchangedIndependentInheritedRoleCases() throws Exception {
  return independentCases("captured-round5-scalar-inherited-source-guards.json",3);
 }
 private Stream<DynamicTest> independentCases(String name,int count) throws Exception {
  JsonNode fixture;
  try(InputStream in=getClass().getResourceAsStream("/drafting/harness/"+name)) {
   assertNotNull(in);fixture=JsonUtils.mapper().readTree(in);
  }
  assertEquals(count,fixture.path("cases").size());
  return StreamSupport.stream(fixture.path("cases").spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()-> {
   ExtractionDecisionVO d=intake(c.path("key").asText(),JsonUtils.mapper().convertValue(c.path("value"),Object.class),c.path("quote").asText(),c.path("source").asText());
   System.out.println("ROUND5_SOURCE_ROLE_CLOSURE "+c.path("name").asText()+" "+d.getStatus()+" "+d.getCodes());
   assertEquals(c.path("expectedStatus").asText(),d.getStatus(),c.path("name").asText()+" "+d.getCodes());
  }));
 }
 @Test void aNeutralHeadingCannotDiscardTheIssueFormatsUnselectedRole() {
  String q="The official issue format of the BQ original and BQ addenda is Hardcopy.";
  for(String role:Arrays.asList("For reference only:","Illustrative sample:","Unselected draft:"))
   check("electronicTendering","Hardcopy",q,role+"\n\nIssue format:\n\n"+q,"rejected");
 }
 @Test void anIndependentAdoptedIssueCanFollowAReferenceExample() {
  String q="The official issue format of the BQ original and BQ addenda is Hardcopy.";
  check("electronicTendering","Hardcopy",q,"Illustrative sample:\n\nThe BQ issue format is L10Pro.\n\nCurrent adopted issue format:\n\n"+q,"accepted");
  check("electronicTendering","Hardcopy",q,"Illustrative sample:\n\nCurrent adopted issue format:\n\n"+q,"accepted");
 }
 @Test void sourceRolesInsideTheQuotesOwnPrefixRemainUnadopted() {
  String q="The official issue format of the BQ original and BQ addenda is Hardcopy.";
  check("electronicTendering","Hardcopy",q,"Illustrative sample: "+q,"rejected");
 }
 @Test void aShortSiteQuoteKeepsItsAdjacentSeparationClassification() {
  String q="The north building site and the south demolition site are separate.";
  check("buildingDemolitionSitesSeparated",true,q,q+" The separation classification remains pending.","rejected");
 }
 @Test void unrelatedPendingClassificationsDoNotInvalidateKnownSiteSeparation() {
  String q="The north building site and the south demolition site are separate.";
  check("buildingDemolitionSitesSeparated",true,q,q+" The paint classification remains pending.","accepted");
  check("buildingDemolitionSitesSeparated",true,q,q+" The contractor appointment remains pending.","accepted");
 }
 private static void check(String key,Object value,String quote,String source,String expected) {
  ExtractionDecisionVO d=intake(key,value,quote,source);assertEquals(expected,d.getStatus(),source+" "+d.getCodes());
 }
 static ExtractionDecisionVO intake(String key,Object value,String quote,String source) {
  Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);item.put("reason","Independent original source-role closure");item.put("confidence",.95);
  ExtractionPartVO p=new ExtractionPartVO("closure:0",1L,"closure.txt","source",0,source,new ArrayList<>());
  p.setContext(new ExtractionContextVO("closure",Collections.singletonList(key),0,source.length(),source,null));
  ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),p,source);
  assertEquals(quote,d.getSourceQuote());assertEquals(JsonUtils.mapper().valueToTree(value),JsonUtils.mapper().valueToTree(d.getRawValue()));return d;
 }
}
