package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.ExtractionAttemptVO;
import com.consense.web.dto.DraftingDtos.ExtractionContextVO;
import com.consense.web.dto.DraftingDtos.ExtractionDecisionVO;
import com.consense.web.dto.DraftingDtos.ExtractionPartVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent refusal-boundary regression at the real intake seam; all data is an offline fixture. */
class DraftingEvidenceRecoveryBoundaryTest {
    private static final String WATER_KEY="otherWaterproofingSpecificationAreas";
    private static final String WATER_LABEL="Other waterproofing areas specified in Specification: ";
    private static final JsonNode FIXTURE=fixture();

    @Test void anExactListQuoteCannotBypassThePendingOrNegatedControllingInstruction() throws Exception {
        String value=waterValue();
        for(String control:new String[]{
                "Pending approval: other waterproofing areas specified in Specification",
                "These other waterproofing areas are not adopted.",
                "Do not use the following other waterproofing areas.",
                "Other waterproofing areas remain unknown pending scope confirmation.",
                "Please confirm whether the other waterproofing areas apply."}) {
            String original=control+"\n\n"+WATER_LABEL+value+"\n";
            ObjectNode item=candidate(WATER_KEY,value,WATER_LABEL+value);
            assertEquals("rejected",decide(item,original,context(original,0,original.length())).getStatus(),control);
        }
    }

    @Test void anExactBillTableQuoteCannotBypassThePendingOrNegatedControllingInstruction() throws Exception {
        ObjectNode item=captured("bill-exact-table").deepCopy();
        String quote=item.path("sourceQuote").asText();
        for(String control:new String[]{
                "The following bill schedule remains pending approval.",
                "The following bill identity list is not adopted.",
                "Do not use the following complete bill schedule.",
                "Please confirm whether the following bill schedule is applicable."}) {
            String original=control+"\n\nBill No. | Exact description | Pricing type\n\n"+quote+"\n";
            assertEquals("rejected",decide(item,original,context(original,0,original.length())).getStatus(),control);
        }
    }

    @Test void anExactCompleteQuoteDoesNotTurnMissingMiddleItemsIntoASupportedValue() throws Exception {
        String value=waterValue(),original=WATER_LABEL+value+"\n";
        String missing=value.replace("13.G/F level slabs;\n\n","");
        assertNotEquals(value,missing);
        assertEquals("rejected",decide(candidate(WATER_KEY,missing,original.trim()),original,context(original,0,original.length())).getStatus());
    }

    @Test void theSameItemCountCannotHideADuplicateOrChangedIdentifier() throws Exception {
        String value=waterValue(),original=WATER_LABEL+value+"\n";
        for(String broken:new String[]{value.replace("7.Internal tankings;","6.Internal tankings;"),
                value.replace("7.Internal tankings;","70.Internal tankings;"),
                value.replace("7.Internal tankings;","17.Internal tankings;")})
            assertEquals("rejected",decide(candidate(WATER_KEY,broken,original.trim()),original,context(original,0,original.length())).getStatus());
    }

    @Test void numberedMarkerRecoveryDoesNotEraseANegationOrChangedItemBody() throws Exception {
        String value=waterValue(),original=WATER_LABEL+value+"\n";
        for(String prefix:new String[]{"Do not use:\n","Invented replacement instruction:\n"})
            assertEquals("rejected",decide(candidate(WATER_KEY,prefix+value,original.trim()),original,context(original,0,original.length())).getStatus(),"Recovery must not silently discard a negated or invented value prefix.");
        for(String broken:new String[]{value.replace("7.Internal tankings;","7No internal tankings;"),
                value.replace("7.Internal tankings;","7External tankings;"),
                value.replace("18. All areas with waterproofing membranes","18. No areas with waterproofing membranes")})
            assertEquals("rejected",decide(candidate(WATER_KEY,broken,WATER_LABEL+broken),original,context(original,0,original.length())).getStatus());
        String unnumbered=value.replaceAll("(?m)^\\d+[.)][\\t ]*","");
        assertNotEquals(value,unnumbered);
        assertEquals("rejected",decide(candidate(WATER_KEY,unnumbered,original.trim()),original,context(original,0,original.length())).getStatus(),"Removing every marker cannot bypass complete-list validation.");
        assertEquals("rejected",decide(candidate(WATER_KEY,"Kitchens;",original.trim()),original,context(original,0,original.length())).getStatus(),"A literal body fragment without markers cannot stand in for the complete numbered list.");
    }

    @Test void nullBlankAndUnknownValuesStayUnansweredEvenWithCompleteEvidenceAvailable() throws Exception {
        for(String key:new String[]{WATER_KEY,"billNos"}) {
            String original=source(WATER_KEY.equals(key)?"water":"bill");
            for(JsonNode raw:new JsonNode[]{JsonUtils.mapper().nullNode(),JsonUtils.mapper().getNodeFactory().textNode(""),
                    JsonUtils.mapper().getNodeFactory().textNode("unknown")}) {
                ObjectNode item=JsonUtils.mapper().createObjectNode();item.put("key",key);item.set("value",raw);
                item.put("sourceQuote",original);item.put("confidence",0.95);
                ExtractionDecisionVO decision=decide(item,original,context(original,0,original.length()));
                assertEquals("unanswered",decision.getStatus(),key+" "+raw);
                assertNull(decision.getNormalizedValue());
                assertEquals(raw,JsonUtils.mapper().valueToTree(decision.getRawValue()));
            }
        }
    }

    @Test void anEmptyArrayDoesNotClaimAbsenceOrAutoFillTheListVisibleInSource() throws Exception {
        for(String key:new String[]{WATER_KEY,"billNos"}) {
            String original=source(WATER_KEY.equals(key)?"water":"bill");
            ObjectNode item=JsonUtils.mapper().createObjectNode();item.put("key",key);
            item.set("value",JsonUtils.mapper().createArrayNode());item.put("sourceQuote",original);item.put("confidence",0.95);
            ExtractionDecisionVO decision=decide(item,original,context(original,0,original.length()));
            assertEquals("rejected",decision.getStatus());
            assertFalse(decision.getCodes().contains("evidence_quote_reanchored"));
            assertFalse(decision.getCodes().contains("source_value_typography_restored"));
        }
    }

    @Test void aCorrectOriginalBillTableOutsideTheSuppliedWindowCannotBeUsedForRecovery() throws Exception {
        String original=source("bill");ObjectNode item=captured("bill-introduction").deepCopy();
        int end=original.indexOf("Bill No. | Exact description");
        ExtractionDecisionVO decision=decide(item,original,context(original,0,end));
        assertEquals("rejected",decision.getStatus());
        assertEquals(item.path("sourceQuote").asText(),decision.getSourceQuote());
        assertFalse(decision.getCodes().contains("evidence_quote_reanchored"));
    }

    @Test void originalWaterproofingItemsOutsideTheSuppliedWindowCannotRepairAFullCandidate() throws Exception {
        String original=source("water"),value=waterValue(),typo=value.replace("7.Internal tankings;","7Internal tankings;");
        ObjectNode item=candidate(WATER_KEY,typo,WATER_LABEL+typo);
        int end=original.indexOf("\n\n17.");
        ExtractionDecisionVO decision=decide(item,original,context(original,0,end));
        assertEquals("rejected",decision.getStatus());
        assertEquals(item.path("sourceQuote").asText(),decision.getSourceQuote());
        assertFalse(decision.getCodes().contains("evidence_quote_reanchored"));
        assertFalse(decision.getCodes().contains("source_value_typography_restored"));
    }

    @Test void anExactQuoteStillCannotChooseBetweenDuplicateBillTables() throws Exception {
        ObjectNode item=captured("bill-exact-table").deepCopy();String quote=item.path("sourceQuote").asText();
        String unit="The complete bill schedule is listed below.\n\nBill No. | Exact description | Pricing type\n\n"+quote+"\n";
        String original=unit+"\nSeparate procurement option\n\n"+unit;
        ExtractionDecisionVO decision=decide(item,original,context(original,0,original.length()));
        assertEquals("rejected",decision.getStatus());
        assertFalse(decision.getCodes().contains("evidence_quote_reanchored"));
    }

    @Test void aDuplicatedBillIdentityCannotBeRecoveredFromACompleteUniqueTable() throws Exception {
        ObjectNode item=captured("bill-introduction").deepCopy();ArrayNode rows=(ArrayNode)item.path("value");
        rows.set(1,rows.get(0).deepCopy());String original=source("bill");
        assertEquals("rejected",decide(item,original,context(original,0,original.length())).getStatus());
    }

    @Test void aGapInOriginalSourceNumberingCannotMakeItsInitialFragmentACompleteList() throws Exception {
        String value="1.Kitchens;\n2.Bathrooms;";
        String original="Other waterproofing areas specified in Specification\n\n"+WATER_LABEL+value+"\n4.Planters;\n5.Roofs.\n";
        assertEquals("rejected",decide(candidate(WATER_KEY,value,WATER_LABEL+value),original,context(original,0,original.length())).getStatus());
        String minimal=WATER_LABEL+"1.Kitchens;\n3.Planters.\n";
        assertEquals("rejected",decide(candidate(WATER_KEY,"1.Kitchens;",WATER_LABEL+"1.Kitchens;"),minimal,context(minimal,0,minimal.length())).getStatus(),"A two-marker original gap must not disappear because each consecutive run has only one entry.");
    }

    @Test void duplicateOriginalNumberingCannotMakeItsInitialFragmentACompleteList() throws Exception {
        String value="1.Kitchens;\n2.Bathrooms;";
        String original="Other waterproofing areas specified in Specification\n\n"+WATER_LABEL+value+"\n2.Planters;\n3.Roofs.\n";
        assertEquals("rejected",decide(candidate(WATER_KEY,value,WATER_LABEL+value),original,context(original,0,original.length())).getStatus());
    }

    @Test void aFieldTitleCannotHideItsParentPendingAlternativeHeading() throws Exception {
        String value="1.Kitchens;\n2.Bathrooms.";
        String original="Pending alternatives\nOther waterproofing areas specified in Specification\n"+value+"\n";
        assertEquals("rejected",decide(candidate(WATER_KEY,value,value),original,context(original,0,original.length())).getStatus());
    }

    @Test void aFollowingPendingQualificationPreventsReanchoringFromTheFieldLabel() throws Exception {
        String value="1.Kitchens;\n2.Bathrooms.";
        String label="Other waterproofing areas specified in Specification";
        String original=label+"\n\n"+value+"\n\nThese areas remain pending confirmation.\n";
        ExtractionDecisionVO decision=decide(candidate(WATER_KEY,value,label),original,context(original,0,original.length()));
        assertEquals("rejected",decision.getStatus());
        assertFalse(decision.getCodes().contains("evidence_quote_reanchored"));
    }

    private static JsonNode fixture() {
        try(InputStream stream=DraftingEvidenceRecoveryBoundaryTest.class.getResourceAsStream("/drafting/harness/captured-bill-waterproofing/fixture.json")) {
            if(stream==null)throw new IllegalStateException("Missing captured fixture");
            return JsonUtils.mapper().readTree(stream);
        }catch(Exception failed){throw new IllegalStateException(failed);}
    }
    private static ObjectNode captured(String name) {
        for(JsonNode item:FIXTURE.path("cases"))if(name.equals(item.path("name").asText()))return (ObjectNode)item.path("item");
        throw new IllegalArgumentException(name);
    }
    private static String source(String key) {return FIXTURE.path("sources").path(key).path("originalSource").asText();}
    private static String waterValue() {
        String original=source("water");int start=original.indexOf(WATER_LABEL)+WATER_LABEL.length();
        return original.substring(start,original.indexOf("\n\nFull 18 area list copied",start));
    }
    private static ObjectNode candidate(String key,String value,String quote) {
        ObjectNode item=JsonUtils.mapper().createObjectNode();item.put("key",key);item.put("value",value);
        item.put("sourceQuote",quote);item.put("confidence",0.95);return item;
    }
    private static ExtractionContextVO context(String original,int start,int end) {
        return new ExtractionContextVO("independent-boundary-regression",Collections.emptyList(),start,end,original.substring(start,end),null);
    }
    private static ExtractionDecisionVO decide(ObjectNode item,String original,ExtractionContextVO context) throws Exception {
        ExtractionAttemptVO attempt=new ExtractionAttemptVO(1,"fixture","","","","completed",null);attempt.setContext(context);
        ExtractionPartVO part=new ExtractionPartVO("boundary-regression",1L,"test-only-captured-source",null,0,context.getSourceText(),new ArrayList<>());part.setContext(context);
        Method method=DraftExtractionHarness.class.getDeclaredMethod("decide",ExtractionPartVO.class,ExtractionAttemptVO.class,int.class,JsonNode.class,String.class);method.setAccessible(true);
        return (ExtractionDecisionVO)method.invoke(new DraftExtractionHarness(null),part,attempt,0,item,original);
    }
}
