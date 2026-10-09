package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Ordinary compound subjects keep one relationship, its polarity, and its original source role. */
class DraftRoundFiveScalarEvidenceTest {
 @Test void issueFormatOfBqOriginalAndAddendaKeepsTheWholeSubject() {
  check("electronicTendering","Hardcopy","The issue format of the BQ original and BQ addenda is Hardcopy.","accepted");
  check("electronicTendering","L10Pro","The issue format for the BQ original and BQ addenda is L10Pro.","accepted");
 }
 @Test void aNegativeAlternativeCannotEraseTheExplicitOtherIssueMode() {
  String source="The issue format of the BQ original and BQ addenda is Hardcopy. L10Pro files are not issued for this tender.";
  check("electronicTendering","Hardcopy",source,"accepted");check("electronicTendering","L10Pro",source,"rejected");
 }
 @Test void compoundIssueSubjectStillCannotBorrowTheReturnOrSorMode() {
  for(String source:Arrays.asList("The issue format of the BQ original and BQ addenda is L10Pro, and the SOR issue format is Hardcopy.",
   "The issue format of the BQ original and BQ addenda is L10Pro, and the tender return format is Hardcopy.")) {
   check("electronicTendering","L10Pro",source,"accepted");check("electronicTendering","Hardcopy",source,"rejected");
  }
  check("electronicTendering","Hardcopy","The issue format of the BQ original is L10Pro and the BQ addenda are issued in Hardcopy.","rejected");
 }
 @Test void newIssuePhrasingKeepsQuestionOtherProjectAndNegativeGuards() {
  for(String source:Arrays.asList("Please confirm whether the issue format of the BQ original and BQ addenda is Hardcopy.",
   "For another project the issue format of the BQ original and BQ addenda is Hardcopy.",
   "The issue format of the BQ original and BQ addenda is not Hardcopy."))check("electronicTendering","Hardcopy",source,"rejected");
 }
 @Test void footingsPostmodifierAndOrdinaryConsequenceAdverbKeepTheServedObject() {
  check("footingsServeBuildingsOrMajorExternalStructures",true,"The pad footings in the register support the library building.","accepted");
  check("footingsServeBuildingsOrMajorExternalStructures",true,"The footings therefore serve a building, rather than minor standalone landscape items.","accepted");
 }
 @Test void footingsPostmodifierStillNeedsTheFootingsAsActorAndKeepsPolarity() {
  for(String source:Arrays.asList("The Contractor inspects the pad footings in the register and provides temporary supports for the building.",
   "For another project the pad footings in the register support the library building.",
   "Please confirm whether the pad footings in the register support the library building.",
   "The footings therefore do not serve buildings or major external structures."))check("footingsServeBuildingsOrMajorExternalStructures",true,source,"rejected");
 }
 @Test void twoDirectionalSitesAreOneExplicitSeparationSubject() {
  check("buildingDemolitionSitesSeparated",true,"The north building site and the south demolition site are separate fenced sites with separate entrances.","accepted");
  check("buildingDemolitionSitesSeparated",true,"The west demolition site and the east building site are physically separate.","accepted");
  check("buildingDemolitionSitesSeparated",false,"The north building site and the south demolition site are not separate.","accepted");
 }
 @Test void sitePairKeepsItsPendingOtherProjectAndOppositeValueGuards() {
  for(String source:Arrays.asList("Please confirm whether the north building site and the south demolition site are separate.",
   "For another project the north building site and the south demolition site are separate.",
   "The north building site and the south demolition site are not separate.",
   "The north building site and the south building site are separate.",
   "The north building site and the south demolition site are separate. This decision remains pending.",
   "The north building site and the south demolition site are separate. Their classification remains unconfirmed."))check("buildingDemolitionSitesSeparated",true,source,"rejected");
 }
 @Test void anIndependentSiteClauseStillSplitsBeforeItsPendingRelationship() {
  check("buildingDemolitionSitesSeparated",true,"The north building site and the south demolition site are separate, and the demolition site requires a roofing warranty which remains pending.","rejected");
  check("buildingDemolitionSitesSeparated",true,"The north building site is discussed separately, and the south demolition site is separate.","rejected");
 }
 @Test void aModelSubstringCannotDropTheNewPhrasingsSourceRole() {
  String q="The issue format of the BQ original and BQ addenda is Hardcopy.";
  check("electronicTendering","Hardcopy",q,"For another project only: "+q,"For another project only: "+q,"rejected");
  q="The pad footings in the register support the library building.";
  check("footingsServeBuildingsOrMajorExternalStructures",true,q,"For another project only: "+q,"For another project only: "+q,"rejected");
  q="The north building site and the south demolition site are separate.";
  check("buildingDemolitionSitesSeparated",true,q,"Please confirm whether "+q,"Please confirm whether "+q,"rejected");
 }
 @Test void explicitIssueQuoteKeepsItsOriginalQuestionAndDependentPendingScope() {
  String q="The BQ issue format is Hardcopy.";
  for(String s:Arrays.asList("For another project only: "+q,"Please confirm whether "+q,q+" This decision remains pending."))
   check("electronicTendering","Hardcopy",q,s,s,"rejected");
 }
 private static void check(String key,Object value,String source,String expected) {
  check(key,value,source,source,source,expected);
 }
 private static void check(String key,Object value,String quote,String supplied,String original,String expected) {
  Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);item.put("reason","Source-bound scalar public intake regression");item.put("confidence",.95);
  ExtractionPartVO part=new ExtractionPartVO("scalar:0",1L,"scalar.txt","source",0,supplied,new ArrayList<>());part.setContext(new ExtractionContextVO("scalar",Collections.singletonList(key),0,supplied.length(),supplied,null));
  ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
  assertEquals(expected,d.getStatus(),key+" "+quote+" "+d.getCodes());assertEquals(quote,d.getSourceQuote());
 }
}
