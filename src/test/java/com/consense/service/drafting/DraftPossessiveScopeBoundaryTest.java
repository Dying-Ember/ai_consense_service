package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftPossessiveScopeBoundaryTest {
 @Test void anUnseenPossessiveDependentParagraphMustNotBeDiscarded(){
  String q="The footings serve buildings.",s=q+"\n\nTheir classification remains pending.";
  check("footingsServeBuildingsOrMajorExternalStructures",true,q,q,s,"rejected");
 }
 @Test void aContinuousAdoptedUnitRetainsItsLaterPossessiveUncertainty(){
  String q="The staff housing is a domestic residential block.",seen=q+"\n\nThis decision is adopted.",s=seen+"\n\nIts classification remains unconfirmed.";
  check("domesticBlocks",true,q,s,s,"rejected");check("domesticBlocks",true,q,seen,s,"rejected");
 }
 @Test void anotherActorMentioningFootingsCannotBecomeTheirFieldAntecedent(){
  String q="The footings serve buildings.",s=q+" The Architect inspects the footings. Their classification remains pending.";
  check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"accepted");
 }
 @Test void aJointStructuralSubjectDoesNotBecomeASoleFootingsAntecedent(){
  String q="The footings serve buildings.",s=q+" The footings and piles are under inspection. Their classification remains pending.";
  check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"accepted");
 }
 @Test void anUnrelatedAttributeOfTheSameBuildingDoesNotInvalidateItsType(){
  String q="The staff housing is a domestic residential block.",s=q+" Its location remains pending.";
  check("domesticBlocks",true,q,s,s,"accepted");
 }
 @Test void aNeutralNewTopicHeadingBreaksPossessiveReference(){
  String q="The footings serve buildings.",s=q+"\nRoofing:\nTheir classification remains pending.";
  check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"accepted");
 }
 @Test void aForeignFieldOwnersPendingPossessiveCannotPoisonThisProject(){
  String q="The footings serve buildings.",s=q+"\nFor another project only:\nThe footings are for garden signs. Their classification remains pending.";
  check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"accepted");
 }
 @Test void nativeTwoSiteRelationshipAndNoParcelContinuationRetainTheirQualifier(){
  String q="Building and demolition sites are separated; no parcel contains both types of work.",s=q+" Their relationship remains pending.";
  check("buildingDemolitionSitesSeparated",true,q,s,s,"rejected");
 }
 @Test void aResolvedFootingsSubjectRetainsItsOwnLaterQualifier(){
  String q="The listed footings are exclusively for small freestanding garden signboards. They do not serve a building or any major external structure.",s=q+" Their classification remains pending.";
  check("footingsServeBuildingsOrMajorExternalStructures",false,q,s,s,"rejected");
 }
 @Test void anAdoptedPossessiveQualificationRemainsAUsableSource(){
  String q="The staff housing is a domestic residential block.",s=q+"\n\nIts classification is adopted.";
  check("domesticBlocks",true,q,s,s,"accepted");
 }
 @Test void aNeutralClassificationHeadingDoesNotHideItsRelatedPendingUnit(){
  String q="The footings serve buildings.",s=q+"\n\nClassification:\n\nTheir classification remains pending.";
  check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"rejected");check("footingsServeBuildingsOrMajorExternalStructures",true,q,q,s,"rejected");
 }
 private static void check(String key,Object value,String quote,String supplied,String original,String expected){
  Map<String,Object> m=new LinkedHashMap<>();m.put("key",key);m.put("value",value);m.put("sourceQuote",quote);m.put("confidence",.98);
  ExtractionPartVO p=new ExtractionPartVO("possessive-boundary:0",1L,"source.txt","source",0,supplied,new ArrayList<>());
  p.setContext(new ExtractionContextVO("boundary",Collections.singletonList(key),0,supplied.length(),supplied,null));
  ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(m),p,original);
  assertEquals(JsonUtils.write(value),JsonUtils.write(d.getRawValue()));assertEquals(quote,d.getSourceQuote());assertEquals(expected,d.getStatus(),key+" "+original+" "+d.getCodes());
 }
}
