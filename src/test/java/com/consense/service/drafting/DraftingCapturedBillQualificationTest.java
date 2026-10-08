package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.ExtractionAttemptVO;
import com.consense.web.dto.DraftingDtos.ExtractionContextVO;
import com.consense.web.dto.DraftingDtos.ExtractionDecisionVO;
import com.consense.web.dto.DraftingDtos.ExtractionPartVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Captured first hardened browser exam: a Bill qualification is not another formal identity. */
class DraftingCapturedBillQualificationTest {
    private static final JsonNode FIXTURE=fixture();

    @Test void capturedAllProvisionalQualificationCannotCreateAnotherParentBillIdentity() throws Exception {
        JsonNode captured=captured("qualification-misread-as-formal-description");
        JsonNode item=captured.path("item");
        assertEquals("12",item.path("value").get(0).path("number").asText());
        assertEquals("All Provisional",item.path("value").get(0).path("description").asText());
        assertTrue(captured.path("originalSource").asText().contains("Keep the thirteen formal parent Bill descriptions exactly as supplied in SIM04."));
        ExtractionDecisionVO decision=replay(captured);
        assertEquals("rejected",decision.getStatus(),"The qualification must not add a fourteenth row or rename the existing parent Bill.");
        assertEquals(item.path("value"),JsonUtils.mapper().valueToTree(decision.getRawValue()),"Reject while preserving the real model error for review.");
        assertFalse(decision.getCodes().contains("evidence_quote_reanchored"),"Do not import the formal description from another correspondence document.");
    }

    @Test void capturedThirteenFormalIdentitiesAndTheirActualTableQuoteRemainAccepted() throws Exception {
        JsonNode captured=captured("formal-thirteen-bill-table");
        JsonNode item=captured.path("item");
        ExtractionDecisionVO decision=replay(captured);
        assertEquals("accepted",decision.getStatus());
        JsonNode rows=JsonUtils.parse(decision.getNormalizedValue());
        assertEquals(item.path("value").size(),rows.size());
        assertEquals(13,rows.size());
        for(int i=0;i<rows.size();i++) {
            assertEquals(item.path("value").get(i).path("number"),rows.get(i).path("number"));
            assertEquals(item.path("value").get(i).path("description"),rows.get(i).path("description"));
        }
        assertEquals(item.path("sourceQuote").asText(),decision.getSourceQuote());
    }

    private static ExtractionDecisionVO replay(JsonNode captured) throws Exception {
        ExtractionContextVO context=JsonUtils.mapper().treeToValue(captured.path("context"),ExtractionContextVO.class);
        ExtractionAttemptVO attempt=new ExtractionAttemptVO(1,"captured-browser-exam","","",JsonUtils.write(captured.path("item")),"completed",null);attempt.setContext(context);
        ExtractionPartVO part=new ExtractionPartVO(captured.path("provenance").path("partId").asText(),1L,captured.path("fileName").asText(),captured.path("sourceHash").asText(),0,context.getSourceText(),new ArrayList<>());part.setContext(context);
        Method decide=DraftExtractionHarness.class.getDeclaredMethod("decide",ExtractionPartVO.class,ExtractionAttemptVO.class,int.class,JsonNode.class,String.class);decide.setAccessible(true);
        return (ExtractionDecisionVO)decide.invoke(new DraftExtractionHarness(null),part,attempt,captured.path("provenance").path("itemIndex").asInt(),captured.path("item"),captured.path("originalSource").asText());
    }
    private static JsonNode captured(String name) {
        for(JsonNode item:FIXTURE.path("cases"))if(name.equals(item.path("name").asText()))return item;
        throw new IllegalArgumentException(name);
    }
    private static JsonNode fixture() {
        try(InputStream stream=DraftingCapturedBillQualificationTest.class.getResourceAsStream("/drafting/harness/captured-bill-qualification/fixture.json")) {
            if(stream==null)throw new IllegalStateException("Missing real browser exam Bill fixture");
            return JsonUtils.mapper().readTree(stream);
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
