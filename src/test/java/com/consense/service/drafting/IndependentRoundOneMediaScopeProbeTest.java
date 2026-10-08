package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IndependentRoundOneMediaScopeProbeTest {
 private static final String QUOTE="The adopted electronic issue format for the Bills is L10Pro.";
 @Test void directActualBqPricingScopeIsAccepted(){check(QUOTE+" Detailed BQ pricing files are supplied to tenderers.","accepted");}
 @Test void sorOnlyBillsCannotBorrowReferenceOnlyBqFiles(){check(QUOTE+" Detailed BQ pricing files are supplied for reference only. These adopted Bills comprise SOR only.","rejected");}
 @Test void sorOnlyBillsCannotBorrowUnselectedBqSamples(){check(QUOTE+" Detailed BQ pricing files are supplied as unselected samples. These adopted Bills comprise SOR only.","rejected");}
 @Test void aRemoteBqSupplyParagraphDoesNotGroundTheQuotedBills(){check(QUOTE+"\n\nDetailed BQ pricing files are supplied to tenderers.","rejected");}
 @Test void negativeBqSupplyDoesNotGroundTheQuotedBills(){check(QUOTE+" Detailed BQ pricing files are not supplied to tenderers.","rejected");}
 @Test void anotherContractBqSupplyDoesNotGroundTheQuotedBills(){check(QUOTE+" Detailed BQ pricing files are supplied for another contract.","rejected");}
 private static void check(String source,String expected){
  Map<String,Object> item=new LinkedHashMap<>();item.put("key","electronicTendering");item.put("value","L10Pro");item.put("sourceQuote",QUOTE);item.put("reason","Independent source-scope counterexample");item.put("confidence",.95);
  ExtractionPartVO part=new ExtractionPartVO("independent-scope:0",1L,"source.txt","source",0,source,new ArrayList<>());part.setContext(new ExtractionContextVO("probe",Collections.singletonList("electronicTendering"),0,source.length(),source,null));
  ExtractionDecisionVO d=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,source);
  assertEquals(QUOTE,d.getSourceQuote());System.out.println("INDEPENDENT_SCOPE "+JsonUtils.write(Arrays.asList(source,expected,d.getStatus(),d.getCodes())));assertEquals(expected,d.getStatus(),source+" "+d.getCodes());
 }
}
