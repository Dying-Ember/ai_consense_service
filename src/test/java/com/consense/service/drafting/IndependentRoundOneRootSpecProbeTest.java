package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IndependentRoundOneRootSpecProbeTest {
 private static final String HEADER="Component | Actual work scope | Contractor designs | Contractor executes";
 private static final String ROW="footings | Youth centre footings | No | Yes";
 private static final String TABLE=HEADER+"\n"+ROW;
 private static final String COMPLETE="This is the complete component responsibility schedule:";
 @Test void genuineDesignTableReanchorsVisibleCompletePreface(){check("designResponsibilities",design(),TABLE,COMPLETE+"\n"+TABLE,COMPLETE+"\n"+TABLE,"accepted");}
 @Test void designCannotRecoverAnUnseenPrefaceFromTruncatedContext(){check("designResponsibilities",design(),TABLE,TABLE,COMPLETE+"\n"+TABLE,"rejected");}
 @Test void designCannotIgnoreItsVisibleUnselectedPrefix(){String s="The proposed responsibility table is not adopted:\n"+TABLE;check("designResponsibilities",design(),TABLE,s,s,"rejected");}
 @Test void designCannotIgnoreItsTrailingPendingDecision(){String s=COMPLETE+"\n"+TABLE+"\nThis decision remains pending.";check("designResponsibilities",design(),s,s,s,"rejected");}
 @Test void designCannotRecoverPastAnOmittedTrailingUnconfirmedDecision(){String s=COMPLETE+"\n"+TABLE+"\nThis decision remains unconfirmed.";check("designResponsibilities",design(),TABLE,s,s,"rejected");}
 @Test void otherProjectCompleteDesignTableIsNotThisContractEvidence(){String s="For another project, the complete Contractor responsibility schedule is:\n"+TABLE;check("designResponsibilities",design(),s,s,s,"rejected");}
 @Test void completeDesignTableCannotOmitAnExistingComponent(){String s=COMPLETE+"\n"+TABLE+"\nother | Internal partitions | No | Yes";check("designResponsibilities",design(),s,s,s,"rejected");}
 @Test void directFootingsRelationshipRemainsAccepted(){check("footingsServeBuildingsOrMajorExternalStructures",true,"The footings serve the buildings.","accepted");}
 @Test void adjacentActorSupportIsNotFootingsService(){check("footingsServeBuildingsOrMajorExternalStructures",true,"The Contractor inspects the footings and provides temporary supports for the building.","rejected");}
 @Test void otherProjectFootingsDoNotProveThisContractClassification(){check("footingsServeBuildingsOrMajorExternalStructures",true,"For another project, the footings serve the buildings.","rejected");}
 @Test void footingsCannotIgnoreItsTrailingUnconfirmedDecision(){check("footingsServeBuildingsOrMajorExternalStructures",true,"The footings serve the buildings. This decision remains unconfirmed.","rejected");}
 @Test void footingsQuoteCannotDropItsQuestionPrefix(){String q="the footings serve the buildings.",s="Please confirm whether "+q;check("footingsServeBuildingsOrMajorExternalStructures",true,q,s,s,"rejected");}
 @Test void directWholeProjectAbsenceRemainsAccepted(){check("projectInTinShuiWai",false,"The project is not in Tin Shui Wai.","accepted");}
 @Test void anotherProjectsNegativeLocationIsNotThisProjectAbsence(){check("projectInTinShuiWai",false,"Another project is not in Tin Shui Wai.","rejected");}
 @Test void wholeProjectLocationCannotIgnoreItsTrailingPendingDecision(){check("projectInTinShuiWai",false,"The project is not in Tin Shui Wai. This decision remains pending.","rejected");}
 @Test void wholeProjectLabelCannotDropItsQuestionPrefix(){String q="project in Tin Shui Wai: No.",s="Please confirm whether "+q;check("projectInTinShuiWai",false,q,s,s,"rejected");}
 @Test void blockLabelBecomesIdentifierWithoutAlteringItsRawEvidence(){ExtractionDecisionVO d=check("specificationInspectionBlock","Block E","Inspect the Specification Library at Block E.","accepted");assertEquals("E",d.getNormalizedValue());assertEquals("\"Block E\"",JsonUtils.write(d.getRawValue()));}
 @Test void aBlockIdentifierWithItsOwnWordIsPreserved(){ExtractionDecisionVO d=check("drawingsInspectionBlock","Blockhouse","Drawings are inspected at Blockhouse.","accepted");assertEquals("Blockhouse",d.getNormalizedValue());}
 @Test void editorIdIsIgnoredOnlyInNormalizedSectionBusinessInput(){
  Map<String,Object> r=new LinkedHashMap<>();r.put("id","editor-42");r.put("designation","Section A");r.put("workTypes",Collections.singletonList("foundation"));r.put("location","North site");String s="Section designation | Works types | Location\nSection A | foundation | North site";
  ExtractionDecisionVO d=check("sections",Collections.singletonList(r),s,"accepted");assertFalse(JsonUtils.parse(d.getNormalizedValue()).get(0).has("id"));assertEquals("editor-42",JsonUtils.mapper().valueToTree(d.getRawValue()).get(0).path("id").asText());
 }
 private static List<Map<String,Object>> design(){Map<String,Object>r=new LinkedHashMap<>();r.put("component","footings");r.put("scope","Youth centre footings");r.put("design",false);r.put("execution",true);return Collections.singletonList(r);}
 private static ExtractionDecisionVO check(String key,Object value,String quote,String expected){return check(key,value,quote,quote,quote,expected);}
 private static ExtractionDecisionVO check(String key,Object value,String quote,String supplied,String original,String expected){
  Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);item.put("reason","Independent round-one domain evidence probe");item.put("confidence",.95);
  ExtractionPartVO part=new ExtractionPartVO("independent-root:0",1L,"source.txt","source",0,supplied,new ArrayList<>());part.setContext(new ExtractionContextVO("probe",Collections.singletonList(key),0,supplied.length(),supplied,null));
  ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,original);
  assertEquals(JsonUtils.write(value),JsonUtils.write(d.getRawValue()));System.out.println("ROOT_SPEC "+JsonUtils.write(Arrays.asList(key,supplied,expected,d.getStatus(),d.getCodes(),d.getSourceQuote())));assertEquals(expected,d.getStatus(),key+" "+supplied+" "+d.getCodes());return d;
 }
}
