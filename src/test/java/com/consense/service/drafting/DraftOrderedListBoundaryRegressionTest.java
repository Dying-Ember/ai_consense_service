package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.ocr.OcrClient;
import com.consense.web.dto.DraftingDtos.ExtractionContextVO;
import com.consense.web.dto.DraftingDtos.ExtractionDecisionVO;
import com.consense.web.dto.DraftingDtos.ExtractionPartVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Frozen model answers and source only; no answer key, model request, or database writes. */
class DraftOrderedListBoundaryRegressionTest {
    private static final String KEY="otherWaterproofingSpecificationAreas";
    private static final String TITLE="Other waterproofing areas specified in Specification";
    private static final String VALUE="1. Kitchen;\n2. Bathroom;\n Include waterproofed thresholds and drains;\n3. Plant room.";

    @Test void roundOneFrozenTextIsTheActualNativeDocxProjectionAndCapturedContexts() throws Exception {
        String source=resource("SIM11-novel-round1-source.txt");JsonNode fixture=novelFixture();
        byte[] binary=resourceBytes("SIM11-novel-round1-source.docx");
        assertEquals(fixture.path("sourceDocx").path("sha256").asText(),sha256(binary));
        DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),mock(OcrClient.class))
                .parse(fixture.path("sourceDocx").path("name").asText(),binary);
        assertEquals("PARSED",parsed.getParseStatus());assertEquals(source,parsed.getText());
        assertEquals(fixture.path("originalSourceUtf8Sha256").asText(),sha256(source.getBytes(StandardCharsets.UTF_8)));
        assertEquals(3,fixture.path("cases").size());assertFalse(fixture.path("oracleUsedForResponses").asBoolean());
        for(JsonNode captured:fixture.path("cases")) {
            JsonNode attempt=captured.path("attempt"),context=attempt.path("context");
            assertEquals(source.substring(context.path("sourceStart").asInt(),context.path("sourceEnd").asInt()),context.path("sourceText").asText());
            assertEquals(captured.path("rawResponseUtf8Sha256").asText(),sha256(attempt.path("rawResponse").asText().getBytes(StandardCharsets.UTF_8)));
            assertEquals("rejected",captured.path("originalDecision").path("status").asText());
            assertTrue(captured.path("originalDecision").path("codes").toString().contains("source_list_unresolved"));
        }
    }

    @Test void allThreeUnchangedRoundOneRawAnswersReachAcceptedPublicIntake() throws Exception {
        String source=resource("SIM11-novel-round1-source.txt");List<Executable> checks=new ArrayList<>();
        for(JsonNode captured:novelFixture().path("cases"))checks.add(()->{
            JsonNode recorded=captured.path("attempt");ExtractionContextVO context=JsonUtils.mapper().treeToValue(recorded.path("context"),ExtractionContextVO.class);
            ExtractionPartVO part=new ExtractionPartVO(captured.path("partId").asText(),captured.path("sourceDocumentId").asLong(),captured.path("fileName").asText(),
                    captured.path("sourceHash").asText(),captured.path("partIndex").asInt(),captured.path("sourceText").asText(),new ArrayList<>());part.setContext(context);
            List<ExtractionDecisionVO> decisions=DraftHarnessTestIntake.primaryDecisions(recorded.path("rawResponse").asText(),part,source,
                    recorded.path("attemptIndex").asInt(),recorded.path("systemPrompt").asText(),recorded.path("userPrompt").asText());
            List<ExtractionDecisionVO> selected=new ArrayList<>();for(ExtractionDecisionVO decision:decisions)if(KEY.equals(decision.getKey()))selected.add(decision);
            assertEquals(1,selected.size());ExtractionDecisionVO decision=selected.get(0);
            assertEquals("accepted",decision.getStatus(),captured.path("partId").asText()+" "+decision.getCodes());
            assertEquals(captured.path("rawItem").path("value"),JsonUtils.mapper().valueToTree(decision.getRawValue()));
            assertEquals(captured.path("rawItem").path("value").asText(),decision.getNormalizedValue());
            assertEquals(captured.path("rawItem").path("sourceQuote").asText(),decision.getSourceQuote());
            assertTrue(DraftEvidenceQuotes.present(source,decision.getSourceQuote()));
            assertFalse(decision.getCodes().contains("source_list_unresolved"));
        });
        assertAll("Both primary parts and recall keep their original nine-area answer",checks);
    }

    @Test void incidentalListMentionsAsTheObjectOfSiblingDecisionsDoNotControlIt() throws Exception {
        for(String tail:Arrays.asList("No wall tiling warranty is required. These decisions do not amend the waterproofing areas.",
                "The epoxy pipe warranty is not required. This decision does not change the specified waterproofing list.")) {
            String source=TITLE+"\n"+VALUE+"\n\n"+tail;
            assertEquals("accepted",decide(candidate(VALUE,VALUE),source,0,source.length()).getStatus(),tail);
        }
    }

    @Test void aSiblingScheduleOrItsPronounAndApprovalDoNotQualifyThisList() throws Exception {
        for(String tail:Arrays.asList("The roofing schedule is not adopted.",
                "The roofing warranty is required.\n\nIt remains pending.",
                "The roofing warranty is required.\n\nApproval remains pending.",
                "The three listed waterproofing areas are confirmed, while the roofing warranty is not required.\nIt remains pending.")) {
            String source=TITLE+"\n"+VALUE+"\n\n"+tail;
            assertEquals("accepted",decide(candidate(VALUE,VALUE),source,0,source.length()).getStatus(),tail);
        }
    }

    @Test void aRealRestrictionOfThisListStillControlsAMixedSiblingDecisionParagraph() throws Exception {
        for(String control:Arrays.asList("The waterproofing areas remain pending.","These areas are not required.",
                "No waterproofing areas are required.","The nine listed areas do not apply.","Please confirm whether these waterproofing areas apply?")) {
            String source=TITLE+"\n"+VALUE+"\n\nNo wall tiling warranty is required. "+control;
            ExtractionDecisionVO decision=decide(candidate(VALUE,VALUE),source,0,source.length());
            assertEquals("rejected",decision.getStatus(),control);assertTrue(decision.getCodes().contains("source_list_unresolved"),decision.getCodes().toString());
        }
    }

    @Test void roundOneFifthAndEighthContinuationsCannotBeOmittedOrCutOutOfTheSourceWindow() throws Exception {
        String source=resource("SIM11-novel-round1-source.txt");JsonNode item=novelFixture().path("cases").get(0).path("rawItem");
        for(String continuation:Arrays.asList(
                "Continue the waterproofing below the insulated wall panels, seal every access cover seating and provide compatible returns around the refrigerant pipe openings;",
                "Return the waterproofing over the planter rims and seal each junction with the entrance paving, keeping the root protection layers clear of the channel inspection covers;")) {
            assertTrue(item.path("value").asText().contains(continuation));
            ExtractionDecisionVO omitted=decide(candidate(item.path("value").asText().replace(continuation,""),item.path("sourceQuote").asText()),source,0,source.length());
            assertEquals("rejected",omitted.getStatus());assertTrue(omitted.getCodes().contains("source_list_value_mismatch"),omitted.getCodes().toString());
            int end=source.indexOf(continuation)+continuation.length()/2;
            ExtractionDecisionVO cut=decide((ObjectNode)item.deepCopy(),source,0,end);
            assertEquals("rejected",cut.getStatus());assertTrue(cut.getCodes().contains("context_incomplete_source"),cut.getCodes().toString());
        }
    }

    @Test void roundOneListsWithActualPendingNegationDuplicateOrGapStayRejected() throws Exception {
        String source=resource("SIM11-novel-round1-source.txt");JsonNode item=novelFixture().path("cases").get(0).path("rawItem");
        for(String qualifier:Arrays.asList("These waterproofing areas remain pending.","The nine listed areas are not adopted.",
                "No waterproofing areas are required.","These decisions do not change the waterproofing areas, which remain pending.",
                "These decisions do not amend the waterproofing areas, which are excluded.")) {
            String controlled=source+"\n\n"+qualifier;
            ExtractionDecisionVO decision=decide((ObjectNode)item.deepCopy(),controlled,0,controlled.length());
            assertEquals("rejected",decision.getStatus(),qualifier);assertTrue(decision.getCodes().contains("source_list_unresolved"),decision.getCodes().toString());
        }
        for(String malformed:Arrays.asList(source.replace("6. Staff accommodation","7. Staff accommodation"),
                source.replace("6. Staff accommodation","8. Staff accommodation"))) {
            ExtractionDecisionVO decision=decide((ObjectNode)item.deepCopy(),malformed,0,malformed.length());
            assertEquals("rejected",decision.getStatus());assertTrue(decision.getCodes().contains("source_list_invalid_sequence"),decision.getCodes().toString());
        }
    }

    @Test void anIndependentDecisionAssuranceDoesNotImportSiblingNegativesThroughASourceDeclaration() throws Exception {
        String source=resource("SIM11-novel-round1-source.txt");JsonNode item=novelFixture().path("cases").get(0).path("rawItem");
        for(String tail:Arrays.asList("Full 9 area list copied from PRE.B6.590.","The roofing warranty remains pending.")) {
            String extended=source+"\n\n"+tail;
            ExtractionDecisionVO decision=decide((ObjectNode)item.deepCopy(),extended,0,extended.length());
            assertEquals("accepted",decision.getStatus(),tail+" "+decision.getCodes());
            assertEquals(item.path("value").asText(),decision.getNormalizedValue());
        }
    }

    @Test void actualNineItemAnswersSurviveAnAdjacentUnrelatedNegativeWarrantyTopic() throws Exception {
        String original=resource("SIM11-alternate-source.txt");
        DraftOrderedSourceLists.Unit unit=DraftOrderedSourceLists.units(original).get(0);
        assertEquals(9,unit.entries.size());assertTrue(unit.validSequence);
        assertTrue(unit.entries.get(4).text.contains("Include the waterproofed junctions"));
        assertTrue(unit.entries.get(7).text.contains("Include membrane returns"));
        assertFalse(original.substring(unit.listEnd,unit.end).contains("No acrylic paint warranty"));
        JsonNode answers=JsonUtils.mapper().readTree(resource("SIM11-alternate-answers.json"));
        for(JsonNode captured:answers) {
            ObjectNode item=(ObjectNode)captured.path("rawItem");
            int start=captured.path("contextStart").asInt(),end=captured.path("contextEnd").asInt();
            ExtractionDecisionVO decision=decide(item,original,start,end);
            assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());
            assertEquals(item.path("value").asText(),decision.getNormalizedValue());
            assertFalse(decision.getCodes().contains("source_list_unresolved"));
        }
    }

    @Test void separateNegativeTopicsBeforeAndAfterTheListDoNotGovernIt() throws Exception {
        for(String unrelated:new String[]{"No acrylic paint warranty is required.","Play equipment installation is not included.",
                "The ventilation installation remains pending approval.","Roofing warranty is not adopted."}) {
            String original=unrelated+"\n\n"+TITLE+"\n"+VALUE+"\n\n"+unrelated+"\n";
            DraftOrderedSourceLists.Unit unit=DraftOrderedSourceLists.units(original).get(0);
            assertFalse(original.substring(unit.start,unit.listStart).contains(unrelated));
            assertFalse(original.substring(unit.listEnd,unit.end).contains(unrelated));
            assertEquals("accepted",decide(candidate(VALUE,VALUE),original,0,original.length()).getStatus(),unrelated);
        }
    }

    @Test void qualificationsOfThisListStillRejectEvenWhenTheModelQuotesOnlyTheItems() throws Exception {
        for(String qualification:new String[]{"These areas remain pending confirmation.","The above list is not adopted.",
                "No waterproofing areas are required.","Approval remains pending.","They are subject to final confirmation.",
                "This specification list is not applicable.","Whether the listed areas apply is still unknown.",
                "Please confirm whether these waterproofing areas apply?","Pending approval"}) {
            String original=TITLE+"\n"+VALUE+"\n\n"+qualification+"\n";
            DraftOrderedSourceLists.Unit unit=DraftOrderedSourceLists.units(original).get(0);
            assertTrue(original.substring(unit.listEnd,unit.end).contains(qualification),qualification);
            ExtractionDecisionVO decision=decide(candidate(VALUE,VALUE),original,0,original.length());
            assertEquals("rejected",decision.getStatus(),qualification);
            assertTrue(decision.getCodes().contains("source_list_unresolved"),decision.getCodes().toString());
        }
    }

    @Test void aGoverningPendingParentAndSourceDeclarationRemainAttached() throws Exception {
        String original="Pending alternatives\n"+TITLE+"\n"+VALUE+"\n\nFull 3 area list copied from PRE.B6.590.\n";
        DraftOrderedSourceLists.Unit unit=DraftOrderedSourceLists.units(original).get(0);
        assertTrue(original.substring(unit.start,unit.listStart).contains("Pending alternatives"));
        assertTrue(original.substring(unit.listEnd,unit.end).contains("Full 3 area list copied"));
        assertEquals("rejected",decide(candidate(VALUE,VALUE),original,0,original.length()).getStatus());
    }

    @Test void incompleteAndAlteredListsRemainRejectedWithUnrelatedNegativeNeighbors() throws Exception {
        String original=TITLE+"\n"+VALUE+"\n\nNo acrylic paint warranty is required.\n";
        for(String broken:new String[]{"1. Kitchen;\n2. Bathroom;",VALUE.replace("3. Plant room.","3. Machinery room."),
                VALUE.replace("2. Bathroom;","2. Bathroom;\n2. Duplicate;"),VALUE.replace(" Include waterproofed thresholds and drains;\n","")}) {
            assertEquals("rejected",decide(candidate(broken,VALUE),original,0,original.length()).getStatus(),broken);
        }
        int end=original.indexOf("3. Plant room.");
        assertEquals("rejected",decide(candidate(VALUE,VALUE),original,0,end).getStatus());
    }

    private static String resource(String name) throws Exception {
        return new String(resourceBytes(name),StandardCharsets.UTF_8);
    }
    private static byte[] resourceBytes(String name) throws Exception {
        try(InputStream stream=DraftOrderedListBoundaryRegressionTest.class.getResourceAsStream("/drafting/harness/ordered-list/"+name)) {
            assertNotNull(stream);return stream.readAllBytes();
        }
    }
    private static JsonNode novelFixture() throws Exception {return JsonUtils.mapper().readTree(resource("SIM11-novel-round1-answers.json"));}
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format("%02x",value&0xff));return hex.toString();
    }
    private static ObjectNode candidate(String value,String quote) {
        ObjectNode item=JsonUtils.mapper().createObjectNode();item.put("key",KEY);item.put("value",value);
        item.put("sourceQuote",quote);item.put("confidence",0.95);return item;
    }
    private static ExtractionDecisionVO decide(ObjectNode item,String original,int start,int end) throws Exception {
        ExtractionContextVO context=new ExtractionContextVO("ordered-boundary-replay",Collections.emptyList(),start,end,original.substring(start,end),null);
        ExtractionPartVO part=new ExtractionPartVO("boundary-regression",1L,"test-only-captured-source",null,0,context.getSourceText(),new ArrayList<>());part.setContext(context);
        return DraftHarnessTestIntake.primaryDecision(item,part,original);
    }
}
