package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Scope decisions require a source-bound answer about this relationship, not a neighboring actor or project. */
class DraftScopeEvidenceRegressionTest {
 @Test void adjacentActorSupportIsNotFootingsService(){check("footingsServeBuildingsOrMajorExternalStructures",true,"The Contractor inspects the footings and provides temporary supports for the building.","rejected");}
 @Test void otherProjectFootingsDoNotProveThisContractClassification(){check("footingsServeBuildingsOrMajorExternalStructures",true,"For another project, the footings serve the buildings.","rejected");}
 @Test void footingsCannotIgnoreItsTrailingUnconfirmedDecision(){check("footingsServeBuildingsOrMajorExternalStructures",true,"The footings serve the buildings. This decision remains unconfirmed.","rejected");}
 @Test void footingsQuoteCannotDropItsQuestionPrefix(){String q="the footings serve the buildings.",s="Please confirm whether "+q;check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"rejected");}
 @Test void anotherProjectsNegativeLocationIsNotThisProjectAbsence(){check("projectInTinShuiWai",false,"Another project is not in Tin Shui Wai.","rejected");}
 @Test void wholeProjectLocationCannotIgnoreItsTrailingPendingDecision(){check("projectInTinShuiWai",false,"The project is not in Tin Shui Wai. This decision remains pending.","rejected");}
 @Test void wholeProjectLabelCannotDropItsQuestionPrefix(){String q="project in Tin Shui Wai: No.",s="Please confirm whether "+q;check("projectInTinShuiWai",false,q,s,s,"rejected");}
 @Test void directFootingsServiceAndExplicitNegativeRemainUsable(){
  check("footingsServeBuildingsOrMajorExternalStructures",true,"The footings serve the buildings.","accepted");
  check("footingsServeBuildingsOrMajorExternalStructures",false,"The footings do not serve buildings or major external structures.","accepted");
  check("footingsServeBuildingsOrMajorExternalStructures",true,"The appointed Contractor will design and construct the piles, pile caps and shallow footings for the buildings within the same contract, together with the building works.","accepted");
 }
 @Test void actualProjectTitleAndWholeProjectAbsenceRemainUsable(){
  check("projectInTinShuiWai",true,"The meeting confirmed Contract No. 20260247, Construction of Community Facilities at Tin Shui Wai Area 117.","accepted");
  check("projectInTinShuiWai",true,"The project and its domestic caretaker block are in Tin Shui Wai.","accepted");
  check("projectInTinShuiWai",false,"All buildings are in Sha Tin; there are no works in Tin Shui Wai.","accepted");
  check("projectInTinShuiWai",false,"The project is in Sha Tin rather than Tin Shui Wai.","accepted");
  check("projectInTinShuiWai",false,"Project in Tin Shui Wai: No","accepted");
 }
 @Test void locationOfOneBuildingDoesNotProveWholeProjectAbsence(){check("projectInTinShuiWai",false,"The domestic block is in Sha Tin.","rejected");}
 @Test void actorDesignAndConstructionDoNotProveFootingsServedObject(){check("footingsServeBuildingsOrMajorExternalStructures",true,"The Architect designs the pile caps and the shallow footings, which the Contractor constructs.","rejected");}
 @Test void unrelatedPendingWarrantyDoesNotInvalidateASettledScope(){
  check("footingsServeBuildingsOrMajorExternalStructures",true,"The footings serve the buildings. The roofing warranty remains pending.","accepted");
  check("projectInTinShuiWai",false,"The project is not in Tin Shui Wai. The roofing warranty remains pending.","accepted");
 }
 @Test void anUnseenQuestionPrefixCannotBeDiscardedAsOutsideTheSuppliedFragment(){String q="the footings serve the buildings.",s="Please confirm whether "+q;check("footingsServeBuildingsOrMajorExternalStructures",true,q,q,s,"rejected");}
 @Test void conflictingDirectScopeStatementsStayUnresolved(){
  check("footingsServeBuildingsOrMajorExternalStructures",true,"The footings serve buildings. The footings do not serve buildings or major external structures.","rejected");
  check("projectInTinShuiWai",false,"The project is in Tin Shui Wai. The project is not in Tin Shui Wai.","rejected");
 }
 @Test void laterParagraphsCannotBorrowThisQuotesProjectRole(){String q="The footings serve the buildings.",s="For another project, "+q+"\n\nThis project has foundation works.";check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"rejected");}
 @Test void aDirectlyFollowingDependentParagraphStillGovernsTheQuote(){
  String q="The footings serve the buildings.",s=q+"\n\nThis remains pending.";check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"rejected");
  q="The project is not in Tin Shui Wai.";s=q+"\n\nThis decision remains unconfirmed.";check("projectInTinShuiWai",false,q,s,s,"rejected");
 }
 @Test void aSeparateUnrelatedPendingParagraphDoesNotGovernTheSettledScope(){
  String q="The footings serve the buildings.",s=q+"\n\nThe roofing warranty remains pending.";check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"accepted");
 }
 @Test void theProjectArchitectsLocationDoesNotLocateTheWholeProject(){
  check("projectInTinShuiWai",true,"The project architect lives in Tin Shui Wai.","rejected");
  check("projectInTinShuiWai",false,"The project architect's office is not in Tin Shui Wai.","rejected");
 }
 @Test void settledFootingsUseSurvivesAnUnrelatedNegativeClause(){check("footingsServeBuildingsOrMajorExternalStructures",true,"The footings serve the buildings, and the roofing warranty is not adopted.","accepted");}
 @Test void thisProjectsFootingsUseSurvivesASeparateOtherProjectComparison(){check("footingsServeBuildingsOrMajorExternalStructures",true,"For this project, the footings serve the buildings. Another project has no piling works.","accepted");}
 @Test void wholeProjectLocationSurvivesAnUnrelatedNegativeClause(){check("projectInTinShuiWai",true,"The project is in Tin Shui Wai, and no roofing warranty is required.","accepted");}
 @Test void anExpresslyForbiddenNegativeLocationLabelIsNotAnAdoptedAnswer(){check("projectInTinShuiWai",false,"Do not use this answer: Project in Tin Shui Wai: No.","rejected");}
 @Test void aSeparateLineOtherProjectPrefixStillGovernsTheQuotedAssertion(){check("projectInTinShuiWai",true,"For another project only:\nThe project is in Tin Shui Wai.","rejected");}
 @Test void aSeparateLineForbiddenPrefixStillGovernsTheQuotedLabel(){check("projectInTinShuiWai",false,"Do not use this answer:\nProject in Tin Shui Wai: No.","rejected");}
 @Test void aContinuousDependentUnitCannotDropItsLaterUnconfirmedClassification(){String q="The project is in Tin Shui Wai.",s=q+"\n\nThis decision is adopted.\n\nThis classification remains unconfirmed.";check("projectInTinShuiWai",true,q,s,s,"rejected");}
 @Test void aSeparateLineOtherProjectPrefixAlsoGovernsFootings(){check("footingsServeBuildingsOrMajorExternalStructures",true,"For another project only:\nThe footings serve buildings.\nThe footings serve major external structures.","rejected");}
 @Test void aDirectlyPrecedingOtherProjectHeadingStillGovernsAcrossABlankLine(){String q="The project is in Tin Shui Wai.",s="For another project only:\n\n"+q;check("projectInTinShuiWai",true,q,s,s,"rejected");}
 @Test void anUnseenForbiddenHeadingCannotBeDiscarded(){String q="Project in Tin Shui Wai: No.",s="Do not use this answer:\n\n"+q;check("projectInTinShuiWai",false,q,q,s,"rejected");}
 @Test void aContinuousDependentUnitMustBeVisibleInTheActualContext(){String q="The footings serve buildings.",seen=q+"\n\nThis decision is adopted.",s=seen+"\n\nThis classification remains unconfirmed.";check("footingsServeBuildingsOrMajorExternalStructures",true,q,seen,s,"rejected");}
 @Test void anIndependentNextTopicEndsTheDependentUnit(){String q="The project is in Tin Shui Wai.",s=q+"\n\nThis decision is adopted.\n\nThe roofing warranty is discussed separately.\n\nThis classification remains unconfirmed.";check("projectInTinShuiWai",true,q,s,s,"accepted");}
 @Test void aVisibleAdoptedDependentUnitKeepsADirectAnswerUsable(){String q="The footings serve buildings.",s=q+"\n\nThis decision is adopted.\n\nThis classification is confirmed.";check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"accepted");}
 @Test void aNeutralSubheadingCannotClearAnOtherProjectSourceRole(){check("projectInTinShuiWai",true,"For another project only:\nLocation:\nThe project is in Tin Shui Wai.","rejected");}
 @Test void aNeutralSubheadingCannotClearAForbiddenSourceRole(){check("projectInTinShuiWai",false,"Do not use this answer:\nLocation:\nProject in Tin Shui Wai: No.","rejected");}
 @Test void anExplicitCurrentProjectHeadingHasItsOwnVisibleRole(){check("projectInTinShuiWai",true,"For another project only:\nThe project is in Sha Tin.\nFor this project:\nThe project is in Tin Shui Wai.","accepted");}
 private static void check(String key,Object value,String quote,String expected){check(key,value,quote,quote,quote,expected);}
 private static void check(String key,Object value,String quote,String supplied,String original,String expected){
  Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);item.put("reason","Scope provenance public intake regression");item.put("confidence",.95);
  ExtractionPartVO part=new ExtractionPartVO("scope:0",1L,"scope.txt","source",0,supplied,new ArrayList<>());part.setContext(new ExtractionContextVO("scope",Collections.singletonList(key),0,supplied.length(),supplied,null));
  ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
  assertEquals(JsonUtils.write(value),JsonUtils.write(d.getRawValue()));assertEquals(quote,d.getSourceQuote());System.out.println("SCOPE_PUBLIC "+JsonUtils.write(Arrays.asList(key,supplied,expected,d.getStatus(),d.getCodes())));assertEquals(expected,d.getStatus(),key+" "+supplied+" "+d.getCodes());
 }
}
