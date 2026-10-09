package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.ExtractionContextVO;
import com.consense.web.dto.DraftingDtos.ExtractionDecisionVO;
import com.consense.web.dto.DraftingDtos.ExtractionPartVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Column order must not change the meaning of a sourced Bill identity. */
class DraftBillSourceTablesTest {
    private static final String INTRO="Use the following complete formal Bill schedule of two rows. BQ and SOR have separate number series.\n\n";
    private static final String VALUE="[{\"type\":\"BQ\",\"number\":\"1\",\"description\":\"Building Works (All Provisional)\"},{\"type\":\"SOR\",\"number\":\"1\",\"description\":\"Ventilation Plant and Ductwork\"}]";

    @Test void explicitTypesRemainInTheirOwnNamespaceForEveryColumnOrder() throws Exception {
        for(String table:Arrays.asList(
                "Pricing type | Bill No. | Formal description\nBQ | 1 | Building Works (All Provisional)\nSOR | 1 | Ventilation Plant and Ductwork",
                "Bill No. | Pricing type | Formal description\n1 | BQ | Building Works (All Provisional)\n1 | SOR | Ventilation Plant and Ductwork",
                "Formal description | Pricing type | Bill No.\nBuilding Works (All Provisional) | BQ | 1\nVentilation Plant and Ductwork | SOR | 1",
                "Pricing type | Formal description | Bill No.\nBQ | Building Works (All Provisional) | 1\nSOR | Ventilation Plant and Ductwork | 1",
                "Formal description | Bill No. | Pricing type\nBuilding Works (All Provisional) | 1 | BQ\nVentilation Plant and Ductwork | 1 | SOR",
                "Bill No. | Formal description | Pricing type\n1 | Building Works (All Provisional) | BQ\n1 | Ventilation Plant and Ductwork | SOR")) {
            ExtractionDecisionVO decision=decide(JsonUtils.parse(VALUE),INTRO+table,INTRO+table);
            assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());
            JsonNode normalized=JsonUtils.parse(decision.getNormalizedValue());
            for(int i=0;i<2;i++) {
                assertEquals(JsonUtils.parse(VALUE).get(i).path("type"),normalized.get(i).path("type"));
                assertEquals(JsonUtils.parse(VALUE).get(i).path("description"),normalized.get(i).path("description"));
            }
        }
    }

    @Test void introductionQuoteCanReanchorACompleteSameNumberDifferentTypeTable() throws Exception {
        String table="Pricing type | Bill No. | Formal description\nBQ | 1 | Building Works (All Provisional)\nSOR | 1 | Ventilation Plant and Ductwork";
        ExtractionDecisionVO decision=decide(JsonUtils.parse(VALUE),INTRO+table,INTRO.trim());
        assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());
        assertTrue(decision.getCodes().contains("evidence_quote_reanchored"));
        assertTrue(DraftEvidenceQuotes.present(INTRO+table,decision.getSourceQuote()));
    }

    @Test void exactNameIncludesParentheticalQualificationInAnyColumnOrder() throws Exception {
        String source=INTRO+"Pricing type | Bill No. | Formal description\nBQ | 1 | Building Works (All Provisional)\nSOR | 1 | Ventilation Plant and Ductwork";
        ArrayNode rows=(ArrayNode)JsonUtils.parse(VALUE);
        ((ObjectNode)rows.get(0)).put("description","Building Works");
        assertEquals("rejected",decide(rows,source,source).getStatus());
        rows=(ArrayNode)JsonUtils.parse(VALUE);rows.remove(1);
        assertEquals("rejected",decide(rows,source,source).getStatus());
    }

    @Test void absentTypeEvidenceKeepsTheIdentityButNeverInventsAClassification() throws Exception {
        String source="The complete Bill schedule is:\n\nBill No. | Formal description\n1 | Building Works";
        ExtractionDecisionVO untyped=decide(JsonUtils.parse("[{\"number\":\"1\",\"description\":\"Building Works\"}]"),source,source);
        assertEquals("accepted",untyped.getStatus());
        assertFalse(JsonUtils.parse(untyped.getNormalizedValue()).get(0).has("type"));
        ExtractionDecisionVO guessed=decide(JsonUtils.parse("[{\"type\":\"BQ\",\"number\":\"1\",\"description\":\"Building Works\"}]"),source,source);
        assertEquals("accepted",guessed.getStatus());
        assertFalse(JsonUtils.parse(guessed.getNormalizedValue()).get(0).has("type"));
        assertTrue(guessed.getCodes().contains("bill_metadata_unsupported:1:type"));
    }

    @Test void anotherRowsTypeAndAHeadingCannotClassifyThisRow() throws Exception {
        String source="The complete Bill schedule uses BQ and SOR.\n\nBill No. | Formal description | Type\n1 | Building Works | \n2 | Rates | SOR";
        JsonNode invented=JsonUtils.parse("[{\"number\":\"1\",\"description\":\"Building Works\",\"type\":\"SOR\"},{\"number\":\"2\",\"description\":\"Rates\",\"type\":\"SOR\"}]");
        assertEquals("rejected",decide(invented,source,source).getStatus());
    }

    @Test void wrongNamespaceDoesNotUseSameNumberToBorrowAnotherDescription() throws Exception {
        String source=INTRO+"Bill No. | Formal description | Pricing type\n1 | Building Works (All Provisional) | BQ\n1 | Ventilation Plant and Ductwork | SOR";
        ArrayNode rows=(ArrayNode)JsonUtils.parse(VALUE);((ObjectNode)rows.get(0)).put("type","SOR");
        assertEquals("rejected",decide(rows,source,source).getStatus());
    }

    @Test void aMissingNamespaceCannotAmbiguouslyResolveIdenticalNumberAndName() throws Exception {
        String source=INTRO+"Pricing type | Bill No. | Formal description\nBQ | 1 | Works\nSOR | 1 | Works";
        JsonNode rows=JsonUtils.parse("[{\"number\":\"1\",\"description\":\"Works\"}]");
        assertEquals("rejected",decide(rows,source,source).getStatus());
    }

    @Test void explicitNamespacesKeepSameNumberSameNameRowsDistinct() throws Exception {
        String source=INTRO+"Pricing type | Bill No. | Formal description\nBQ | 1 | Works\nSOR | 1 | Works";
        JsonNode rows=JsonUtils.parse("[{\"type\":\"BQ\",\"number\":\"1\",\"description\":\"Works\"},{\"type\":\"SOR\",\"number\":\"1\",\"description\":\"Works\"}]");
        ExtractionDecisionVO decision=decide(rows,source,INTRO.trim());
        assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());
        JsonNode normalized=JsonUtils.parse(decision.getNormalizedValue());
        assertEquals(2,normalized.size());
        assertEquals("BQ",normalized.get(0).path("type").asText());
        assertEquals("SOR",normalized.get(1).path("type").asText());
    }

    @Test void crlfOffsetsKeepCitationRecoveryAnExactContiguousSourceSlice() throws Exception {
        String source=(INTRO+"Pricing type | Bill No. | Formal description\nBQ | 1 | Building Works (All Provisional)\nSOR | 1 | Ventilation Plant and Ductwork\n\nRegards,\nQuantity Surveyor").replace("\n","\r\n");
        for(DraftBillSourceTables.Table table:DraftBillSourceTables.tables(source)) {
            assertTrue(source.substring(table.start,table.end).contains("SOR | 1 | Ventilation Plant and Ductwork"));
            assertFalse(source.substring(table.start,table.end).contains("Regards,"));
        }
        ExtractionDecisionVO decision=decide(JsonUtils.parse(VALUE),source,INTRO.trim());
        assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());
        assertTrue(source.contains(decision.getSourceQuote()));
    }

    @Test void requestedOrPendingTypeIsNotAnExplicitClassification() throws Exception {
        for(String type:Arrays.asList("BQ?","pending BQ","whether BQ","not BQ")) {
            String source="Bill No. | Formal description | Type\n1 | Building Works | "+type;
            assertEquals("rejected",decide(JsonUtils.parse("[{\"number\":\"1\",\"description\":\"Building Works\",\"type\":\"BQ\"}]"),source,source).getStatus());
        }
        for(String name:Arrays.asList("Please confirm Building Works","Building Works?","pending Building Works")) {
            String source="Pricing type | Bill No. | Formal description\nBQ | 1 | "+name;
            ObjectNode row=JsonUtils.mapper().createObjectNode();row.put("type","BQ");row.put("number","1");row.put("description",name);
            assertEquals("rejected",decide(JsonUtils.mapper().createArrayNode().add(row),source,source).getStatus());
        }
    }

    @Test void markdownDelimitersAndSeparatorRowsPreserveHeaderColumnRoles() throws Exception {
        String source=INTRO+"| Formal description | Pricing type | Bill No. |\n| :--- | ---: | --- |\n"+
                "| Building Works (All Provisional) | Bill of Quantities (BQ) | 1 |\n"+
                "| Ventilation Plant and Ductwork | Schedule of Rates (SOR) | 1 |";
        ExtractionDecisionVO decision=decide(JsonUtils.parse(VALUE),source,source);
        assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());
        assertEquals("BQ",JsonUtils.parse(decision.getNormalizedValue()).get(0).path("type").asText());
        assertEquals("SOR",JsonUtils.parse(decision.getNormalizedValue()).get(1).path("type").asText());
    }

    private static ExtractionDecisionVO decide(JsonNode value,String source,String quote) throws Exception {
        ObjectNode item=JsonUtils.mapper().createObjectNode();item.put("key","billNos");item.set("value",value);
        item.put("sourceQuote",quote);item.put("reason","Offline sourced Bill identity regression");item.put("confidence",.99);
        ExtractionContextVO context=new ExtractionContextVO("bill-column-regression",Collections.emptyList(),0,source.length(),source,null);
        ExtractionPartVO part=new ExtractionPartVO("bill-column-regression",1L,"correspondence.docx","fixture-source",0,source,new ArrayList<>());
        part.setContext(context);
        return DraftHarnessTestIntake.primaryDecision(item,part,source);
    }
}
