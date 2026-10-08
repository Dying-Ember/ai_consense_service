package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Contact slots must not print the salutation twice; raw evidence and manual text stay intact. */
class DraftArchitectContactRegressionTest {
    @Test void capturedRoundTwoNameSeparatesItsExplicitHonorific() {
        String quote="The Project Architect is Dr. Marcus S. H. TSE, whose post is Senior Architect Sports Facilities.";
        assertEquals("Marcus S. H. TSE",check("projectArchitectName","Dr. Marcus S. H. TSE",quote).getNormalizedValue());
    }
    @Test void capturedRoundTwoOtherTitleUsesTheContactSlotSpelling() {
        String quote="The salutation is Other and the title to print in its detail is Dr.";
        assertEquals("Dr",check("projectArchitectOtherTitle","Dr.",quote).getNormalizedValue());
    }
    @Test void ordinaryContactTitlesCanBeSeparatedWithoutChangingThePersonalName() {
        for(String title:Arrays.asList("Mr.","Ms", "Ir.","Prof.","Professor")) {
            String name=title+" Ada M. L. WONG";
            assertEquals("Ada M. L. WONG",check("projectArchitectName",name,"Project Architect: "+name+".").getNormalizedValue());
        }
    }
    @Test void aBareNameAndARegisteredNameKeepTheirSourceSpelling() {
        assertEquals("Marcus S. H. TSE",check("projectArchitectName","Marcus S. H. TSE","Project Architect name: Marcus S. H. TSE.").getNormalizedValue());
        String name="Dr. River CHAN";
        assertEquals(name,check("projectArchitectName",name,"The Project Architect's registered name is Dr. River CHAN.").getNormalizedValue());
    }
    @Test void unrelatedTextIsNotEnoughToRewriteAnHonorificLookingName() {
        String name="Dr. River CHAN";
        assertEquals(name,check("projectArchitectName",name,"Dr. River CHAN attended the meeting.").getNormalizedValue());
        assertEquals("Custom title.",check("projectArchitectOtherTitle","Custom title.","The title is Custom title.").getNormalizedValue());
    }
    @Test void manualContactTextRemainsVerbatimInStorage() {
        for(String key:Arrays.asList("projectArchitectName","projectArchitectOtherTitle")) {
            String manual="  Dr. River CHAN  ";
            assertEquals(manual,DraftInputRules.normalize(DraftBlueprint.find(key),manual));
        }
    }
    @Test void qualifiedOrForeignContactDoesNotLoseItsSourceText() {
        String name="Dr. River CHAN";
        for(String quote:Arrays.asList("The former Project Architect is Dr. River CHAN.",
                "Project Architect: Dr. River CHAN, subject to confirmation.",
                "The proposed Project Architect is Dr. River CHAN.",
                "Project Architect: Dr. River CHAN. This is the registered name."))
            assertEquals(name,DraftArchitectContactEvidence.suggestion("projectArchitectName",name,quote),quote);
        assertEquals("Dr.",DraftArchitectContactEvidence.suggestion("projectArchitectOtherTitle","Dr.","The title is Dr., pending confirmation."));
        assertEquals("Dr.",DraftArchitectContactEvidence.suggestion("projectArchitectOtherTitle","Dr.","Dr. River CHAN attended the meeting."));
    }
    @Test void renderKeepsTheAdoptedNameAndOnlyOmitsAMatchingRecognizedTitle() {
        assertEquals("Dr. River CHAN",DraftArchitectContactEvidence.renderedName("Dr","Dr. River CHAN"));
        assertEquals("Ms Dr. River CHAN",DraftArchitectContactEvidence.renderedName("Ms","Dr. River CHAN"));
        assertEquals("Custom title Custom title River CHAN",DraftArchitectContactEvidence.renderedName("Custom title","Custom title River CHAN"));
        assertEquals("Dr Drummond CHAN",DraftArchitectContactEvidence.renderedName("Dr","Drummond CHAN"));
    }
    @Test void aCurrentHeadingCannotResetAnUnresolvedContactInstruction() {
        String quote="The Project Architect is Dr. River CHAN.";
        for(String governing:Arrays.asList("Pending confirmation:","Do not adopt these details:")) {
            String source=governing+"\n\nFor this project:\n\n"+quote;
            assertFalse(DraftArchitectContactEvidence.supported("projectArchitectName",quote,source,source),source);
        }
        String source="For another project only:\n\nContact details:\n\n"+quote;
        assertFalse(DraftArchitectContactEvidence.supported("projectArchitectName",quote,source,source));
    }
    @Test void theActualSctContactSlotPrintsOnlyOneMatchingTitle() {
        Map<String,Object> inputs=new LinkedHashMap<>();
        inputs.put("projectArchitectPost","Senior Architect Sports Facilities");
        inputs.put("projectArchitectSalutation","Other");inputs.put("projectArchitectOtherTitle","Dr.");
        inputs.put("projectArchitectName","Dr. Marcus S. H. TSE");inputs.put("projectArchitectPhone","23456789");
        Map<String,Object> plan=DraftBusinessRules.plan(inputs);
        Map<String,Object> action=DraftBusinessRules.list(plan.get("actions")).stream().map(DraftBusinessRules::asMap)
                .filter(row->"sct-architect-contact".equals(row.get("id"))).findFirst().orElseThrow();
        String original=String.valueOf(action.get("sourceText"));
        if("null".equals(original)||original.isEmpty()) {
            Object paragraphs=action.get("paragraphs");
            java.util.regex.Matcher number=java.util.regex.Pattern.compile("\\d+").matcher(String.valueOf(paragraphs));
            assertTrue(number.find());original=DraftBusinessRules.standardParagraph("SCT",Integer.parseInt(number.group()));
        }
        String rendered=DraftClauseRules.apply(original,"SCT",plan);
        assertTrue(rendered.contains("Dr. Marcus S. H. TSE"),rendered);
        assertFalse(rendered.contains("Dr. Dr."),rendered);
        assertEquals("Dr. Marcus S. H. TSE",inputs.get("projectArchitectName"));
    }
    private static ExtractionDecisionVO check(String key,String value,String quote) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("key",key);item.put("value",value);
        item.put("sourceQuote",quote);item.put("confidence",.95);
        ExtractionPartVO part=new ExtractionPartVO("architect:0",1L,"contact.txt","source-hash",0,quote,new ArrayList<>());
        ExtractionDecisionVO decision=DraftHarnessTestIntake.primaryDecision(JsonUtils.mapper().valueToTree(item),part,quote);
        assertEquals(JsonUtils.write(value),JsonUtils.write(decision.getRawValue()));
        assertEquals(quote,decision.getSourceQuote());assertEquals("accepted",decision.getStatus(),decision.getCodes().toString());
        return decision;
    }
}
