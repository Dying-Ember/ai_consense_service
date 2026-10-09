package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndependentBlockIdentifierProbeTest {
    @Test void aCompoundBlockIdentifierCannotBeTruncatedToItsPrefix() {
        check("drawingsInspectionBlock","Block E","The drawings are inspected at Block E-2.","rejected");
    }
    @Test void specificationsLocationDoesNotSupplyTheDrawingsBlock() {
        check("drawingsInspectionBlock","Block E","The drawings are inspected at Block F; the Specification is inspected at Block E.","rejected");
    }
    @Test void anExplicitlyExcludedSpecificationLocationDoesNotBecomeItsBlock() {
        check("specificationInspectionBlock","Block E","The Specification is not inspected at Block E; its inspection location is Block F.","rejected");
    }
    @Test void aBareInspectionIdentifierStaysBare() {
        ExtractionDecisionVO decision=check("specificationInspectionBlock","E","Specification inspection block: E.","accepted");
        assertEquals("E",decision.getNormalizedValue());
    }
    @Test void anExactCompoundIdentifierKeepsItsHyphenatedSuffix() {
        ExtractionDecisionVO decision=check("drawingsInspectionBlock","Block E-2","The drawings are inspected at Block E-2.","accepted");
        assertEquals("E-2",decision.getNormalizedValue());
        assertEquals("\"Block E-2\"",JsonUtils.write(decision.getRawValue()));
    }
    private static ExtractionDecisionVO check(String key,Object value,String quote,String expected) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);item.put("sourceQuote",quote);item.put("confidence",.95);item.put("reason","Independent block identity evidence review");
        ExtractionPartVO part=new ExtractionPartVO("independent-block:0",1L,"block.txt","independent-source",0,quote,new ArrayList<>());
        part.setContext(new ExtractionContextVO("independent-block",Collections.singletonList(key),0,quote.length(),quote,null));
        ExtractionDecisionVO decision=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,quote);
        assertEquals(JsonUtils.write(value),JsonUtils.write(decision.getRawValue()));
        System.out.println("INDEPENDENT_BLOCK "+JsonUtils.write(Arrays.asList(key,value,quote,expected,decision.getStatus(),decision.getCodes(),decision.getNormalizedValue())));
        assertEquals(expected,decision.getStatus(),quote+" "+decision.getCodes());return decision;
    }
}
