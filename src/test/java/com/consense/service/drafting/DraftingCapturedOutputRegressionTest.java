package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.ExtractionAttemptVO;
import com.consense.web.dto.DraftingDtos.ExtractionContextVO;
import com.consense.web.dto.DraftingDtos.ExtractionDecisionVO;
import com.consense.web.dto.DraftingDtos.ExtractionPartVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Replays real local model output against the production intake seam, without a model or database. */
class DraftingCapturedOutputRegressionTest {
    private static final String RESOURCE="/drafting/harness/captured-bill-waterproofing/fixture.json";
    private static final String WATER_KEY="otherWaterproofingSpecificationAreas";
    private static final String WATER_LABEL="Other waterproofing areas specified in Specification: ";
    private static final JsonNode FIXTURE=load();

    @Test void capturedSourcesAndRequestOffsetsKeepTheirExactRecordedIdentity() throws Exception {
        for(JsonNode source:FIXTURE.path("sources"))
            assertEquals(source.path("utf8Sha256").asText(),sha256(source.path("originalSource").asText()));
        for(JsonNode captured:FIXTURE.path("cases")) {
            String original=source(captured.path("source").asText());
            JsonNode context=captured.path("context");
            assertTrue(context.path("sourceEnd").asInt()<=original.length());
            assertTrue(captured.path("coreStart").asInt()<captured.path("coreEnd").asInt());
            assertTrue(context.path("sourceStart").asInt()<=captured.path("coreStart").asInt());
            assertTrue(context.path("sourceEnd").asInt()>=captured.path("coreEnd").asInt());
            assertFalse(captured.path("provenance").path("runId").asText().isEmpty());
        }
    }

    @Test void completeCapturedBillValueCanReanchorAnIntroductionQuoteToItsUniqueActualTable() throws Exception {
        JsonNode captured=captured("bill-introduction");
        ObjectNode item=item(captured);
        ExtractionDecisionVO decision=replay(captured,item,null,null);
        assertEquals("accepted",decision.getStatus());
        assertEquals(item.get("value"),JsonUtils.mapper().valueToTree(decision.getRawValue()),"Original model output must remain auditable.");
        assertNotEquals(item.path("sourceQuote").asText(),decision.getSourceQuote());
        assertTrue(DraftEvidenceQuotes.present(source("bill"),decision.getSourceQuote()));
        JsonNode rows=JsonUtils.parse(decision.getNormalizedValue());
        assertEquals(item.path("value").size(),rows.size());
        for(int n=0;n<rows.size();n++) {
            assertEquals(item.path("value").get(n).path("number"),rows.get(n).path("number"));
            assertEquals(item.path("value").get(n).path("description"),rows.get(n).path("description"));
        }
    }

    @Test void correctlyQuotedCapturedBillCandidateRemainsAccepted() throws Exception {
        JsonNode captured=captured("bill-exact-table");
        ExtractionDecisionVO decision=replay(captured,item(captured),null,null);
        assertEquals("accepted",decision.getStatus());
        assertEquals(captured.path("item").path("sourceQuote").asText(),decision.getSourceQuote());
    }

    @Test void billReanchoringDoesNotInventAlteredDescriptionsOmittedRowsOrAnUnrelatedQuote() throws Exception {
        JsonNode captured=captured("bill-introduction");
        ObjectNode altered=item(captured);((ObjectNode)altered.path("value").get(0)).put("description","Invented additional works");
        assertEquals("rejected",replay(captured,altered,null,null).getStatus());
        ObjectNode omitted=item(captured);((com.fasterxml.jackson.databind.node.ArrayNode)omitted.path("value")).remove(1);
        assertEquals("rejected",replay(captured,omitted,null,null).getStatus());
        ObjectNode unrelated=item(captured);unrelated.put("sourceQuote","Regards,");
        assertEquals("rejected",replay(captured,unrelated,null,null).getStatus());
    }

    @Test void billIntroductionCannotChooseBetweenDuplicateLogicalTables() throws Exception {
        JsonNode captured=captured("bill-introduction");
        String original=source("bill");
        String unit=original.substring(original.indexOf("The bill schedule in SCT1"),original.indexOf("Bills 9 to 11 are schedules of rates."));
        String ambiguous=original+"\n\nAlternative schedule for an unselected procurement option\n\n"+unit;
        assertEquals("rejected",replay(captured,item(captured),ambiguous,fullContext(ambiguous)).getStatus());
    }

    @Test void everyCapturedPartialWaterproofingAnswerStaysRejectedRatherThanBecomingACompleteSuggestion() throws Exception {
        for(String name:new String[]{"water-prefix-typo","water-prefix-short-quote","water-tail-introduction","water-tail-wrong-shape"}) {
            JsonNode captured=captured(name);
            assertEquals("rejected",replay(captured,item(captured),null,null).getStatus(),name);
            assertEquals("rejected",replay(captured,item(captured),null,fullContext(source("water"))).getStatus(),name+" with full supplied source");
        }
    }

    @Test void newPrimaryWindowsCarryTheEntireNumberedLogicalUnitWithoutSynthesizingText() {
        String original=source("water"),value=waterValue(original);
        int boundary=captured("water-prefix-typo").path("coreEnd").asInt();
        for(int[] core:new int[][]{{0,boundary},{boundary,original.length()}}) {
            ExtractionContextVO context=DraftSourceContext.primary(original,core[0],core[1]);
            assertNull(context.getStopReason());
            assertEquals(original.substring(context.getSourceStart(),context.getSourceEnd()),context.getSourceText());
            assertTrue(DraftEvidenceQuotes.present(context.getSourceText(),value));
            assertTrue(context.getSourceText().length()<=DraftSourceContext.WINDOW_BUDGET);
        }
    }

    @Test void completeWaterproofingTextAndExactContiguousQuoteAreAccepted() throws Exception {
        String original=source("water"),value=waterValue(original);
        ExtractionDecisionVO decision=replay(captured("water-prefix-typo"),waterItem(value,WATER_LABEL+value),null,fullContext(original));
        assertEquals("accepted",decision.getStatus());
        assertEquals(value,decision.getNormalizedValue());
    }

    @Test void aCompleteCandidateWithOnlyTheObservedMissingNumberMarkerCanRestoreOriginalTypography() throws Exception {
        String original=source("water"),value=waterValue(original);
        String typo=value.replace("7.Internal tankings;","7Internal tankings;");
        ObjectNode item=waterItem(typo,WATER_LABEL+typo);
        ExtractionDecisionVO decision=replay(captured("water-prefix-typo"),item,null,fullContext(original));
        assertEquals("accepted",decision.getStatus());
        assertEquals(value,decision.getNormalizedValue());
        assertEquals(item.path("value"),JsonUtils.mapper().valueToTree(decision.getRawValue()));
        assertTrue(DraftEvidenceQuotes.present(original,decision.getSourceQuote()));
    }

    @Test void evenAnExactFullQuoteDoesNotAuthorizeAnIncompleteOrAlteredListValue() throws Exception {
        String original=source("water"),value=waterValue(original),quote=WATER_LABEL+value;
        String prefix=value.substring(0,value.indexOf("\n\n17."));
        assertEquals("rejected",replay(captured("water-prefix-typo"),waterItem(prefix,quote),null,fullContext(original)).getStatus());
        String altered=value.replace("7.Internal tankings;","7.External tankings;");
        assertEquals("rejected",replay(captured("water-prefix-typo"),waterItem(altered,quote),null,fullContext(original)).getStatus());
        String missing=value.replace("13.G/F level slabs;\n\n","");
        assertEquals("rejected",replay(captured("water-prefix-typo"),waterItem(missing,quote),null,fullContext(original)).getStatus());
    }

    @Test void reanchoringNeedsAUniqueAssertedSourceUnitAndSufficientConfidence() throws Exception {
        String original=source("water"),value=waterValue(original),typo=value.replace("7.Internal tankings;","7Internal tankings;");
        String ambiguous=original+"\n\nUnselected alternative waterproofing schedule\n\n"+WATER_LABEL+value;
        assertEquals("rejected",replay(captured("water-prefix-typo"),waterItem(typo,WATER_LABEL+typo),ambiguous,fullContext(ambiguous)).getStatus());
        ObjectNode lowConfidence=waterItem(typo,WATER_LABEL+typo);lowConfidence.put("confidence",0.1);
        assertEquals("rejected",replay(captured("water-prefix-typo"),lowConfidence,null,fullContext(original)).getStatus());
        ObjectNode question=waterItem(typo,"Please confirm whether these waterproofing areas apply?");
        String pending=original+"\nPlease confirm whether these waterproofing areas apply?\n";
        assertEquals("rejected",replay(captured("water-prefix-typo"),question,pending,fullContext(pending)).getStatus());
    }

    @Test void sourceDrivenCompletenessAcceptsAnotherListLengthInsteadOfHardcodingTheExamCount() throws Exception {
        String real=waterValue(source("water"));
        String shorter=real.substring(0,real.indexOf("\n\n4."));
        String original="Other waterproofing areas specified in Specification\n\n"+WATER_LABEL+shorter+"\n\nEnd of adopted waterproofing scope.\n";
        ExtractionDecisionVO decision=replay(captured("water-prefix-typo"),waterItem(shorter,WATER_LABEL+shorter),original,fullContext(original));
        assertEquals("accepted",decision.getStatus());
        assertEquals(shorter,decision.getNormalizedValue());
    }

    private static JsonNode load() {
        try(InputStream stream=DraftingCapturedOutputRegressionTest.class.getResourceAsStream(RESOURCE)) {
            if(stream==null)throw new IllegalStateException("Missing captured regression fixture.");
            return JsonUtils.mapper().readTree(stream);
        } catch(Exception failure) {throw new IllegalStateException(failure);}
    }

    private static JsonNode captured(String name) {
        for(JsonNode item:FIXTURE.path("cases"))if(name.equals(item.path("name").asText()))return item;
        throw new IllegalArgumentException("Missing captured case "+name);
    }
    private static ObjectNode item(JsonNode captured) {return ((ObjectNode)captured.path("item")).deepCopy();}
    private static String source(String id) {return FIXTURE.path("sources").path(id).path("originalSource").asText();}
    private static ExtractionContextVO fullContext(String source) {
        return new ExtractionContextVO("regression-original-source",Collections.emptyList(),0,source.length(),source,null);
    }
    private static String waterValue(String source) {
        int start=source.indexOf(WATER_LABEL)+WATER_LABEL.length();
        int end=source.indexOf("\n\nFull 18 area list copied",start);
        if(start<WATER_LABEL.length()||end<start)throw new IllegalArgumentException("Original logical unit is missing.");
        return source.substring(start,end);
    }
    private static ObjectNode waterItem(String value,String quote) {
        ObjectNode item=JsonUtils.mapper().createObjectNode();
        item.put("key",WATER_KEY);item.put("value",value);item.put("sourceQuote",quote);
        item.put("reason","Offline regression of supported original text; no reference answer is sent to a model.");item.put("confidence",0.95);
        return item;
    }
    private static ExtractionDecisionVO replay(JsonNode captured,ObjectNode item,String replacementSource,ExtractionContextVO replacementContext) throws Exception {
        JsonNode source=FIXTURE.path("sources").path(captured.path("source").asText());
        String original=replacementSource==null?source.path("originalSource").asText():replacementSource;
        JsonNode recorded=captured.path("context");
        ExtractionContextVO context=replacementContext==null?new ExtractionContextVO(recorded.path("trigger").asText(),Collections.emptyList(),
                recorded.path("sourceStart").asInt(),recorded.path("sourceEnd").asInt(),
                original.substring(recorded.path("sourceStart").asInt(),recorded.path("sourceEnd").asInt()),
                recorded.path("stopReason").isMissingNode()||recorded.path("stopReason").isNull()?null:recorded.path("stopReason").asText()):replacementContext;
        ExtractionAttemptVO attempt=new ExtractionAttemptVO(1,"captured-replay","","",JsonUtils.write(Collections.singletonList(item)),"completed",null);
        attempt.setContext(context);
        ExtractionPartVO part=new ExtractionPartVO("captured-"+captured.path("name").asText(),1L,source.path("fileName").asText(),source.path("sourceHash").asText(),
                captured.path("partIndex").asInt(),context.getSourceText(),new ArrayList<>());
        part.setContext(context);
        Method decide=DraftExtractionHarness.class.getDeclaredMethod("decide",ExtractionPartVO.class,ExtractionAttemptVO.class,int.class,JsonNode.class,String.class);
        decide.setAccessible(true);
        return (ExtractionDecisionVO)decide.invoke(new DraftExtractionHarness(null),part,attempt,0,item,original);
    }
    private static String sha256(String source) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format("%02x",value&255));return result.toString();
    }
}
