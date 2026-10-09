package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent vocabulary, blank native rows, and adversarial tuple controls at public intake. */
class DraftRoundThreeNativeTableColumnRolesTest {
    private static final String BILL_PREFIX="Use the complete parent pricing register for this contract.\n\n";
    private static final String BILL_HEADER="Pricing document | Bill number | Issued Bill title";
    private static final String BILL_ROWS="SOR | 6 | Passenger Hoists\n\nBQ | 6 | Workshop Buildings";
    private static final String DESIGN_PREFIX="The complete component responsibility schedule for this project is adopted.\n\n";
    private static final String DESIGN_HEADER="Actual component or scope | Contractor constructs | Component class | Contractor designs";
    private static final String DESIGN_ROWS="Workshop bases | Yes | footings | No\n\nRiver posts | No | other | Yes";
    private static final String SECTION_PREFIX="The complete adopted Section schedule is below.\n\n";
    private static final String SECTION_HEADER="Site location | Section designation | Works types";
    private static final String SECTION_ROWS="Eastern yard | Section East | foundation\n\nWest warehouse | Section West | building";

    @Test void minimalPricingHeaderAliasesPreserveSeparateNumberNamespaces() { assertStatus("accepted","billNos",bills(),BILL_PREFIX+BILL_HEADER+"\n\n"+BILL_ROWS); }
    @Test void minimalDesignHeaderAliasesPreserveBothIndependentRoles() { assertStatus("accepted","designResponsibilities",designs(),DESIGN_PREFIX+DESIGN_HEADER+"\n\n"+DESIGN_ROWS); }
    @Test void minimalSectionLocationAliasAndBlankRowsKeepWholeTable() { assertStatus("accepted","sections",sections(),SECTION_PREFIX+SECTION_HEADER+"\n\n"+SECTION_ROWS); }
    @Test void allPricingHeaderPermutationsKeepTheSameTwoTupleIdentities() {
        permutations(new String[]{"Pricing document","Bill number","Issued Bill title"},new String[][]{{"SOR","6","Passenger Hoists"},{"BQ","6","Workshop Buildings"}},
                source->assertStatus("accepted","billNos",bills(),BILL_PREFIX+source));
    }
    @Test void allDesignHeaderPermutationsKeepSeparateDesignAndConstructionColumns() {
        permutations(new String[]{"Actual component or scope","Contractor constructs","Component class","Contractor designs"},new String[][]{{"Workshop bases","Yes","footings","No"},{"River posts","No","other","Yes"}},
                source->assertStatus("accepted","designResponsibilities",designs(),DESIGN_PREFIX+source));
    }
    @Test void allSectionHeaderPermutationsKeepTypesAndLocationsOnTheirOwnRows() {
        permutations(new String[]{"Site location","Section designation","Works types"},new String[][]{{"Eastern yard","Section East","foundation"},{"West warehouse","Section West","building"}},
                source->assertStatus("accepted","sections",sections(),SECTION_PREFIX+source));
    }
    @Test void everyCompleteTableRejectsCandidateOmission() {
        assertStatus("rejected","billNos",bills().subList(0,1),BILL_PREFIX+BILL_HEADER+"\n\n"+BILL_ROWS);
        assertStatus("rejected","designResponsibilities",designs().subList(0,1),DESIGN_PREFIX+DESIGN_HEADER+"\n\n"+DESIGN_ROWS);
        assertStatus("rejected","sections",sections().subList(0,1),SECTION_PREFIX+SECTION_HEADER+"\n\n"+SECTION_ROWS);
    }
    @Test void tableRowsCannotBorrowAnotherRowsRolesOrLocations() {
        List<Map<String,Object>> b=bills();b.get(0).put("type","BQ");assertStatus("rejected","billNos",b,BILL_PREFIX+BILL_HEADER+"\n\n"+BILL_ROWS);
        List<Map<String,Object>> d=designs();d.get(0).put("design",true);d.get(0).put("execution",false);assertStatus("rejected","designResponsibilities",d,DESIGN_PREFIX+DESIGN_HEADER+"\n\n"+DESIGN_ROWS);
        List<Map<String,Object>> s=sections();s.get(0).put("location","West warehouse");assertStatus("rejected","sections",s,SECTION_PREFIX+SECTION_HEADER+"\n\n"+SECTION_ROWS);
    }
    @Test void unknownOrMissingIdentityAndRoleColumnsDoNotAssertTupleMeaning() {
        for(String header:Arrays.asList("Pricing document | Serial | Issued Bill title","Pricing document | Bill number | Unlabelled"))
            assertStatus("rejected","billNos",bills(),BILL_PREFIX+header+"\n\n"+BILL_ROWS);
        for(String header:Arrays.asList(DESIGN_HEADER.replace("Component class","Unlabelled"),DESIGN_HEADER.replace("Contractor designs","Unlabelled"),DESIGN_HEADER.replace("Contractor constructs","Unlabelled")))
            assertStatus("rejected","designResponsibilities",designs(),DESIGN_PREFIX+header+"\n\n"+DESIGN_ROWS);
        for(String header:Arrays.asList(SECTION_HEADER.replace("Site location","Unlabelled"),SECTION_HEADER.replace("Section designation","Unlabelled"),SECTION_HEADER.replace("Works types","Unlabelled")))
            assertStatus("rejected","sections",sections(),SECTION_PREFIX+header+"\n\n"+SECTION_ROWS);
    }
    @Test void duplicateSourceIdentitiesDoNotBecomeACompleteAnswer() {
        assertStatus("rejected","billNos",bills(),BILL_PREFIX+BILL_HEADER+"\n\n"+BILL_ROWS+"\n\nSOR | 6 | Passenger Hoists");
        assertStatus("rejected","designResponsibilities",designs(),DESIGN_PREFIX+DESIGN_HEADER+"\n\n"+DESIGN_ROWS+"\n\nWorkshop bases | Yes | footings | No");
        assertStatus("rejected","sections",sections(),SECTION_PREFIX+SECTION_HEADER+"\n\n"+SECTION_ROWS+"\n\nEastern yard | Section East | foundation");
    }
    @Test void extraOrMissingNativeCellsNeverShiftRoleColumns() {
        assertStatus("rejected","billNos",bills(),BILL_PREFIX+BILL_HEADER+"\n\nSOR | 6 | Passenger Hoists | extra\n\nBQ | 6 | Workshop Buildings");
        assertStatus("rejected","designResponsibilities",designs(),DESIGN_PREFIX+DESIGN_HEADER+"\n\nWorkshop bases | Yes | footings | No | extra\n\nRiver posts | No | other | Yes");
        assertStatus("rejected","sections",sections(),SECTION_PREFIX+SECTION_HEADER+"\n\nEastern yard | Section East | foundation | extra\n\nWest warehouse | Section West | building");
    }
    @Test void negatedDesignOrExecutionCellDoesNotSupportAFalseRoleWithoutItsLiteralValue() {
        for(String cell:Arrays.asList("not Yes","pending No","whether No","No?"))
            assertStatus("rejected","designResponsibilities",designs(),DESIGN_PREFIX+DESIGN_HEADER+"\n\nWorkshop bases | Yes | footings | "+cell+"\n\nRiver posts | No | other | Yes");
    }
    @Test void genericDesignHeadersRequireContractorOwnerAndNeverBorrowTheArchitectsRole() {
        String table="Scope | Execution | Component class | Design\nWorkshop bases | Yes | footings | No\n\nRiver posts | No | other | Yes";
        assertStatus("accepted","designResponsibilities",designs(),"The Contractor's complete responsibility schedule is adopted.\n"+table);
        assertStatus("rejected","designResponsibilities",designs(),"The Architect's complete responsibility schedule is adopted.\n"+table);
    }
    @Test void proposedPendingOrNonadoptedTablesRemainRejected() {
        for(String prefix:Arrays.asList("Please confirm the following table.","The table is not adopted.","This schedule remains pending.")) {
            assertStatus("rejected","billNos",bills(),prefix+"\n\n"+BILL_HEADER+"\n\n"+BILL_ROWS);
            assertStatus("rejected","designResponsibilities",designs(),prefix+"\n\n"+DESIGN_HEADER+"\n\n"+DESIGN_ROWS);
            assertStatus("rejected","sections",sections(),prefix+"\n\n"+SECTION_HEADER+"\n\n"+SECTION_ROWS);
        }
    }
    @Test void foreignProjectDesignAndSectionTablesCannotAssertThisProjectsRows() {
        assertStatus("rejected","designResponsibilities",designs(),"For another project, the complete responsibility schedule is adopted.\n\n"+DESIGN_HEADER+"\n\n"+DESIGN_ROWS);
        assertStatus("rejected","sections",sections(),"For another project, the complete Section schedule is adopted.\n\n"+SECTION_HEADER+"\n\n"+SECTION_ROWS);
    }
    @Test void dependentUnadoptedDecisionAfterBlankNativeRowsRemainsBinding() {
        assertStatus("rejected","designResponsibilities",designs(),DESIGN_PREFIX+DESIGN_HEADER+"\n\n"+DESIGN_ROWS+"\n\nThis schedule is not adopted.");
        assertStatus("rejected","sections",sections(),SECTION_PREFIX+SECTION_HEADER+"\n\n"+SECTION_ROWS+"\n\nThis Section schedule remains pending.");
    }
    @Test void quotedTableCannotOmitAForeignOrPendingOrNonadoptedNativePreface() {
        for(String prefix:Arrays.asList("For another project, the table is adopted.","This table remains pending.","This table is not adopted.")) {
            String b=BILL_HEADER+"\n\n"+BILL_ROWS,d=DESIGN_HEADER+"\n\n"+DESIGN_ROWS,s=SECTION_HEADER+"\n\n"+SECTION_ROWS;
            assertStatus("rejected","billNos",bills(),prefix+"\n\nPricing register columns:\n\n"+b,b);
            assertStatus("rejected","designResponsibilities",designs(),prefix+"\n\nResponsibility register columns:\n\n"+d,d);
            assertStatus("rejected","sections",sections(),prefix+"\n\nSection register columns:\n\n"+s,s);
        }
    }
    @Test void quotedNativeTableCannotOmitItsDependentUnadoptedTail() {
        String b=BILL_HEADER+"\n\n"+BILL_ROWS,d=DESIGN_HEADER+"\n\n"+DESIGN_ROWS,s=SECTION_HEADER+"\n\n"+SECTION_ROWS;
        assertStatus("rejected","billNos",bills(),BILL_PREFIX+b+"\n\nThis pricing register remains pending.",b);
        assertStatus("rejected","designResponsibilities",designs(),DESIGN_PREFIX+d+"\n\nThis responsibility schedule is not adopted.",d);
        assertStatus("rejected","sections",sections(),SECTION_PREFIX+s+"\n\nThis Section schedule is not adopted.",s);
    }
    @Test void independentUnresolvedTopicDoesNotCancelAnAdoptedNativeTable() {
        String b=BILL_HEADER+"\n\n"+BILL_ROWS,d=DESIGN_HEADER+"\n\n"+DESIGN_ROWS,s=SECTION_HEADER+"\n\n"+SECTION_ROWS;
        String topic="\n\nA separate roof access inspection decision remains pending.";
        assertStatus("accepted","billNos",bills(),BILL_PREFIX+b+topic,b);
        assertStatus("accepted","designResponsibilities",designs(),DESIGN_PREFIX+d+topic,d);
        assertStatus("accepted","sections",sections(),SECTION_PREFIX+s+topic,s);
    }
    @Test void leadingBlankParagraphsAndCrLfRemainBoundedLiteralRecoverySlices() {
        String b=("\n"+BILL_PREFIX+BILL_HEADER+"\n\n"+BILL_ROWS).replace("\n","\r\n");
        String d=("\n"+DESIGN_PREFIX+DESIGN_HEADER+"\n\n"+DESIGN_ROWS).replace("\n","\r\n");
        String s=("\n"+SECTION_PREFIX+SECTION_HEADER+"\n\n"+SECTION_ROWS).replace("\n","\r\n");
        assertStatus("accepted","billNos",bills(),b,BILL_PREFIX.trim());
        assertStatus("accepted","designResponsibilities",designs(),d,(DESIGN_HEADER+"\n\n"+DESIGN_ROWS).replace("\n","\r\n"));
        assertStatus("accepted","sections",sections(),s,(SECTION_HEADER+"\n\n"+SECTION_ROWS).replace("\n","\r\n"));
    }
    @Test void unknownTitleHeadingWithNumberFirstCannotMasqueradeAsProseBillIdentities() {
        List<Map<String,Object>> value=bills();for(Map<String,Object> row:value)row.remove("type");
        assertStatus("rejected","billNos",value,BILL_PREFIX+"Bill number | Unlabelled\n\n6 | Passenger Hoists\n\n6 | Workshop Buildings");
        assertStatus("rejected","billNos",value,BILL_PREFIX+"Bill number | Issued Bill title | Issued Bill title\n\n6 | Passenger Hoists | Something else\n\n6 | Workshop Buildings | Another name");
    }
    @Test void repeatedIdenticalNativeUnitsCannotProvideAnAmbiguousQuoteAnchor() {
        String b=BILL_HEADER+"\n\n"+BILL_ROWS,d=DESIGN_HEADER+"\n\n"+DESIGN_ROWS,s=SECTION_HEADER+"\n\n"+SECTION_ROWS;
        assertStatus("rejected","billNos",bills(),BILL_PREFIX+b+"\n\n"+BILL_PREFIX+b,b);
        assertStatus("rejected","designResponsibilities",designs(),DESIGN_PREFIX+d+"\n\n"+DESIGN_PREFIX+d,d);
        assertStatus("rejected","sections",sections(),SECTION_PREFIX+s+"\n\n"+SECTION_PREFIX+s,s);
    }
    @Test void recoveryCannotImportAnUnsuppliedNativePrefaceIntoAnAttempt() {
        String d=DESIGN_HEADER+"\n\n"+DESIGN_ROWS,s=SECTION_HEADER+"\n\n"+SECTION_ROWS;
        for(String key:Arrays.asList("designResponsibilities","sections")) {
            String table="sections".equals(key)?s:d,prefix="sections".equals(key)?SECTION_PREFIX:DESIGN_PREFIX,source=prefix+table;
            Object value="sections".equals(key)?sections():designs();
            Map<String,Object> item=map("key",key,"value",value,"sourceQuote",table,"reason","Unsupplied preface control","confidence",.99);
            ExtractionPartVO part=new ExtractionPartVO("native-table:0",1L,"context-boundary.txt","source",0,source,new ArrayList<>());
            part.setContext(new ExtractionContextVO("bounded-native",Collections.singletonList(key),prefix.length(),source.length(),table,null));
            ExtractionDecisionVO actual=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,source);
            assertEquals("rejected",actual.getStatus());assertTrue(actual.getCodes().contains("context_incomplete_source"));
            assertEquals(table,actual.getSourceQuote());
        }
    }
    @Test void completeDesignRegisterRejectsOmissionWhileKeepingItsWholeRows() {
        String source=DESIGN_PREFIX.replace("schedule","register")+DESIGN_HEADER+"\n\n"+DESIGN_ROWS;
        assertStatus("accepted","designResponsibilities",designs(),source);
        assertStatus("rejected","designResponsibilities",designs().subList(0,1),source);
    }
    @Test void continuousSectionTailDecisionsCannotHideALaterPendingOrNonadoptedStatus() {
        String table=SECTION_HEADER+"\n\n"+SECTION_ROWS;
        for(String later:Arrays.asList("This Section schedule remains pending.","This Section schedule is not adopted.","This Section schedule is for another project.","This remains pending.","It is not adopted.","This is for another project.")) {
            String source=SECTION_PREFIX+table+"\n\nThis Section schedule is adopted.\n\n"+later;
            assertStatus("rejected","sections",sections(),source,table);
        }
    }
    @Test void independentTopicBetweenSectionTailDecisionsEndsTheirBinding() {
        String table=SECTION_HEADER+"\n\n"+SECTION_ROWS;
        String source=SECTION_PREFIX+table+"\n\nThis Section schedule is adopted.\n\nRoof access inspections require a separate decision.\n\nThis decision remains pending.";
        assertStatus("accepted","sections",sections(),source,table);
    }
    @Test void foreignSameValuedPricingDocumentCannotClassifyTheQuotedCurrentUntypedBill() { rejectBorrowedType("For another project, the complete pricing register is adopted."); }
    @Test void pendingSameValuedPricingDocumentCannotClassifyTheQuotedCurrentUntypedBill() { rejectBorrowedType("The complete pricing register remains pending."); }
    @Test void nonadoptedSameValuedPricingDocumentCannotClassifyTheQuotedCurrentUntypedBill() { rejectBorrowedType("The complete pricing register is not adopted."); }
    @Test void quotedCurrentTypedTableRetainsItsOwnClassificationBesideAnInvalidSameValuedTable() {
        String current=BILL_HEADER+"\n\n"+BILL_ROWS;
        String invalid="Bill number | Issued Bill title | Pricing document\n\n6 | Passenger Hoists | BQ\n\n6 | Workshop Buildings | SOR";
        ExtractionDecisionVO actual=assertStatus("accepted","billNos",bills(),"For another project, the pricing table is adopted.\n\n"+invalid+"\n\n"+BILL_PREFIX+current,current);
        for(int i=0;i<bills().size();i++)assertEquals(bills().get(i).get("type"),JsonUtils.parse(actual.getNormalizedValue()).get(i).path("type").asText());
    }
    private static void rejectBorrowedType(String prefix) {
        String current="Bill number | Issued Bill title\n\n6 | Passenger Hoists\n\n6 | Workshop Buildings";
        for(String invalid:Arrays.asList(BILL_HEADER+"\n\n"+BILL_ROWS,
                "Bill number | Issued Bill title | Pricing document\n\n6 | Passenger Hoists | SOR\n\n6 | Workshop Buildings | BQ")) {
            ExtractionDecisionVO actual=assertStatus("accepted","billNos",bills(),prefix+"\n\n"+invalid+"\n\n"+BILL_PREFIX+current,current);
            for(int i=0;i<bills().size();i++)assertFalse(JsonUtils.parse(actual.getNormalizedValue()).get(i).has("type"),"The untyped current identity cannot borrow a classification from another source unit.");
        }
    }
    @Test void neighbouringProjectBillPrefacesCannotEstablishCurrentPricingRows() {
        String table=BILL_HEADER+"\n\n"+BILL_ROWS;
        for(String label:Arrays.asList("neighbouring","neighboring","adjacent"))assertStatus("rejected","billNos",bills(),"For the "+label+" project, the complete pricing register is adopted.\n\n"+table,table);
    }
    @Test void neighbouringProjectDesignPrefacesCannotEstablishCurrentContractorRoles() {
        String table=DESIGN_HEADER+"\n\n"+DESIGN_ROWS;
        for(String label:Arrays.asList("neighbouring","neighboring","adjacent"))assertStatus("rejected","designResponsibilities",designs(),"For the "+label+" project, the complete component responsibility schedule is adopted.\n\n"+table,table);
    }
    @Test void neighbouringProjectSectionPrefacesCannotEstablishCurrentSectionIdentities() {
        String table=SECTION_HEADER+"\n\n"+SECTION_ROWS;
        for(String label:Arrays.asList("neighbouring","neighboring","adjacent"))assertStatus("rejected","sections",sections(),"For the "+label+" project, the complete Section schedule is adopted.\n\n"+table,table);
    }
    @Test void adjacentBuildingsOrLocationsInsideThisProjectRemainCurrentEvidence() {
        assertStatus("accepted","billNos",bills(),"The complete parent pricing register for this project's adjacent buildings is adopted.\n\n"+BILL_HEADER+"\n\n"+BILL_ROWS);
        List<Map<String,Object>> design=designs();design.get(0).put("scope","Bases of adjacent buildings inside this project");
        assertStatus("accepted","designResponsibilities",design,DESIGN_PREFIX+DESIGN_HEADER+"\n\n"+DESIGN_ROWS.replace("Workshop bases","Bases of adjacent buildings inside this project"));
        List<Map<String,Object>> section=sections();section.get(0).put("location","Adjacent eastern yard within this project");
        assertStatus("accepted","sections",section,SECTION_PREFIX+SECTION_HEADER+"\n\n"+SECTION_ROWS.replace("Eastern yard","Adjacent eastern yard within this project"));
    }
    @Test void foreignSameValuedPurposeAndTradeCannotEnterTheCurrentQuotedBillMetadata() {
        List<Map<String,Object>> value=bills();for(Map<String,Object> row:value){row.put("purpose","preliminaries");row.put("trade","Lift");}
        String current="Bill number | Issued Bill title\n\n6 | Passenger Hoists\n\n6 | Workshop Buildings";
        String foreign="Bill number | Issued Bill title | Pricing document | Purpose | Trade\n\n6 | Passenger Hoists | SOR | purpose: preliminaries | trade: Lift\n\n6 | Workshop Buildings | BQ | purpose: preliminaries | trade: Lift";
        ExtractionDecisionVO actual=assertStatus("accepted","billNos",value,"For another project, the pricing table is adopted.\n\n"+foreign+"\n\n"+BILL_PREFIX+current,current);
        for(int i=0;i<value.size();i++)for(String field:Arrays.asList("type","purpose","trade"))assertFalse(JsonUtils.parse(actual.getNormalizedValue()).get(i).has(field),field);
    }
    @Test void repeatedBillIntroductionCannotChooseAnActiveTableOverItsUnselectedDuplicate() {
        String intro="The formal Bill identity list is below.";
        String table=BILL_HEADER+"\n\n"+BILL_ROWS;
        String current="The current pricing register is adopted.\n\n"+intro+"\n\n"+table;
        String alternative="Alternative schedule for an unselected procurement option\n\n"+intro+"\n\n"+table;
        assertStatus("rejected","billNos",bills(),current+"\n\n"+alternative,intro);
    }
    @Test void repeatedBillIntroductionCannotChooseAnActiveTableOverItsPendingDuplicate() {
        String intro="The formal Bill identity list is below.";
        String table=BILL_HEADER+"\n\n"+BILL_ROWS;
        String current="The current pricing register is adopted.\n\n"+intro+"\n\n"+table;
        String pending="This pricing register remains pending.\n\n"+intro+"\n\n"+table;
        assertStatus("rejected","billNos",bills(),current+"\n\n"+pending,intro);
    }
    @Test void anExplicitWholeCurrentBillUnitRemainsDistinctFromItsUnselectedDuplicate() {
        String intro="The formal Bill identity list is below.";
        String table=BILL_HEADER+"\n\n"+BILL_ROWS;
        String current="The current pricing register is adopted.\n\n"+intro+"\n\n"+table;
        String alternative="Alternative schedule for an unselected procurement option\n\n"+intro+"\n\n"+table;
        assertStatus("accepted","billNos",bills(),current+"\n\n"+alternative,current);
    }

    private static List<Map<String,Object>> bills() {return new ArrayList<>(Arrays.asList(map("number","6","description","Passenger Hoists","type","SOR"),map("number","6","description","Workshop Buildings","type","BQ")));}
    private static List<Map<String,Object>> designs() {return new ArrayList<>(Arrays.asList(map("component","footings","scope","Workshop bases","design",false,"execution",true),map("component","other","scope","River posts","design",true,"execution",false)));}
    private static List<Map<String,Object>> sections() {return new ArrayList<>(Arrays.asList(map("designation","Section East","workTypes",Collections.singletonList("foundation"),"location","Eastern yard"),map("designation","Section West","workTypes",Collections.singletonList("building"),"location","West warehouse")));}
    private static Map<String,Object> map(Object... pairs) {Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static ExtractionDecisionVO assertStatus(String expected,String key,Object value,String source) {
        return assertStatus(expected,key,value,source,source);
    }
    private static ExtractionDecisionVO assertStatus(String expected,String key,Object value,String source,String quote) {
        Map<String,Object> item=map("key",key,"value",value,"sourceQuote",quote,"reason","Independent native table tuple control","confidence",.99);
        ExtractionPartVO part=new ExtractionPartVO("native-table:0",1L,"independent-table.txt","source",0,source,new ArrayList<>());
        part.setContext(new ExtractionContextVO("native-table",Collections.singletonList(key),0,source.length(),source,null));
        ExtractionDecisionVO actual=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,source);
        assertEquals(JsonUtils.write(value),JsonUtils.write(actual.getRawValue()));
        if(actual.getCodes().contains("evidence_quote_reanchored"))assertTrue(source.contains(actual.getSourceQuote()),"Recovery must retain literal source offsets.");
        assertEquals(expected,actual.getStatus(),key+" "+actual.getCodes()+" source="+source);
        return actual;
    }
    private interface Consumer {void accept(String source);}
    private static void permutations(String[] headers,String[][] rows,Consumer consumer) { permute(headers,rows,new ArrayList<>(),consumer); }
    private static void permute(String[] h,String[][] rows,List<Integer> order,Consumer consumer) {
        if(order.size()==h.length) {
            StringBuilder table=new StringBuilder();for(int i:order){if(table.length()>0)table.append(" | ");table.append(h[i]);}
            for(String[] row:rows){table.append("\n\n");for(int j=0;j<order.size();j++){if(j>0)table.append(" | ");table.append(row[order.get(j)]);}}
            consumer.accept(table.toString());return;
        }
        for(int i=0;i<h.length;i++)if(!order.contains(i)){order.add(i);permute(h,rows,order,consumer);order.remove(order.size()-1);}
    }
}
