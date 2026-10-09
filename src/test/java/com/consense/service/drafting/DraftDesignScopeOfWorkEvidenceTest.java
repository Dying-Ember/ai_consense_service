package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftDesignScopeOfWorkEvidenceTest {
    private static final String HEADER="Component | Scope of work | Contractor designs | Contractor executes";
    private static final String RECORD="footings | Community hall footings | No | Yes";

    @Test void anExplicitScopeOfWorkColumnBindsTheContractorsRolesToItsOwnRow() {
        accepted(rows(row("footings",false,true,"Community hall footings")),HEADER+"\n"+RECORD);
    }
    @Test void theAlreadySupportedActualWorkScopeHeaderAcceptsTheSameTuple() {
        accepted(rows(row("footings",false,true,"Community hall footings")),HEADER.replace("Scope of work","Actual work scope")+"\n"+RECORD);
    }
    @Test void columnOrderStillComesFromTheActualHeaders() {
        String[] headers={"Component","Scope of work","Contractor designs","Contractor executes"};
        String[] cells={"footings","Community hall footings","No","Yes"};
        for(int a=0;a<4;a++)for(int b=0;b<4;b++)for(int c=0;c<4;c++)for(int d=0;d<4;d++) {
            if(new HashSet<>(Arrays.asList(a,b,c,d)).size()!=4)continue;
            String source=String.join(" | ",headers[a],headers[b],headers[c],headers[d])+"\n"+String.join(" | ",cells[a],cells[b],cells[c],cells[d]);
            accepted(rows(row("footings",false,true,"Community hall footings")),source);
            rejected(rows(row("footings",true,false,"Community hall footings")),source);
        }
    }
    @Test void aCompleteTableStillRequiresEveryActualRowAndScope() {
        String source="The complete responsibility schedule is:\n"+HEADER+"\n"+RECORD+"\nother | Canopy supports | No | Yes";
        accepted(rows(row("footings",false,true,"Community hall footings"),row("other",false,true,"Canopy supports")),source);
        rejected(rows(row("footings",false,true,"Community hall footings")),source);
        rejected(rows(row("footings",false,true,"Unlisted footings"),row("other",false,true,"Canopy supports")),source);
    }
    @Test void missingUnknownAndAmbiguousRolesCannotBecomeFalse() {
        for(String missing:Arrays.asList("","Pending","Unknown","No?","Proposed: No"))
            rejected(rows(row("footings",false,true,"Community hall footings")),HEADER+"\n"+RECORD.replace("No",missing));
        rejected(rows(row("footings",false,true,"Community hall footings")),"Component | Scope of work | Contractor executes\nfootings | Community hall footings | Yes");
        rejected(rows(row("footings",false,true,"Community hall footings")),"Component | Scope of work | Scope | Contractor designs | Contractor executes\nfootings | Community hall footings | Different footings | No | Yes");
    }
    @Test void headerRecognitionDoesNotSupplyTheContractorOwnerOrAdoption() {
        for(String prefix:Arrays.asList("Please confirm the responsibility table:","The proposed responsibility schedule is:","The responsibility table is not adopted:","If the option is selected:"))
            rejected(rows(row("footings",false,true,"Community hall footings")),prefix+"\n"+HEADER+"\n"+RECORD);
        rejected(rows(row("footings",false,true,"Community hall footings")),"The Architect's responsibility table is:\n"+HEADER.replace("Contractor designs","Design").replace("Contractor executes","Execution")+"\n"+RECORD);
    }
    @Test void matchingTheScopeDoesNotAllowFalseDesignToOverrideAContrarySourceCell() {
        rejected(rows(row("footings",false,true,"Community hall footings")),HEADER+"\n"+RECORD.replace("No","Yes"));
        rejected(rows(row("footings",false,true,"Community hall footings")),HEADER+"\n"+RECORD.replace("| Yes","| No"));
        rejected(rows(row("footings",false,true,"Community hall footings")),HEADER+"\n"+RECORD+"\nfootings | Community hall footings | Yes | No");
    }

    @SafeVarargs private static List<Map<String,Object>> rows(Map<String,Object>... records) {return Arrays.asList(records);}
    private static Map<String,Object> row(String component,boolean design,boolean execution,String scope) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("component",component);row.put("design",design);row.put("execution",execution);row.put("scope",scope);return row;
    }
    private static ExtractionDecisionVO decide(Object value,String source) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key","designResponsibilities");item.put("value",value);
        item.put("sourceQuote",source);item.put("reason","Explicit source column and tuple evidence");item.put("confidence",.95);
        ExtractionPartVO part=new ExtractionPartVO("scope-column:0",1L,"scope-column.txt","source",0,source,new ArrayList<>());
        part.setContext(new ExtractionContextVO("scope-column",Collections.emptyList(),0,source.length(),source,null));
        return DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,source);
    }
    private static void accepted(Object value,String source) {
        ExtractionDecisionVO decision=decide(value,source);assertEquals("accepted",decision.getStatus(),source+" "+decision.getCodes());
    }
    private static void rejected(Object value,String source) {
        ExtractionDecisionVO decision=decide(value,source);assertEquals("rejected",decision.getStatus(),source+" "+decision.getCodes());
        assertTrue(decision.getCodes().contains("quote_value_mismatch"),decision.getCodes().toString());
    }
}
