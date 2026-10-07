package com.consense.service.drafting;

import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.ocr.OcrClient;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/** Regression against the real standard's duplicated NTT2 alternative guidance. */
class DraftNttSourceAcceptanceTest {
    @Test void l10proRetainsBothBodyPartsAndRemovesOnlyTheirContextBoundGuidance() throws Exception {
        String configured=System.getProperty("consense.acceptance.sourceDir");
        assumeTrue(configured!=null,"Point consense.acceptance.sourceDir at the actual competition standards.");
        String name="01_Notes to Tenderers (NTT).docx";
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),mock(OcrClient.class));
        DocumentParser.ParsedDocument parsed=parser.parse(name,Files.readAllBytes(Paths.get(configured).resolve(name)));
        assertEquals("PARSED",parsed.getParseStatus());
        assertTrue(parsed.getText().length()>10000);
        String guide=DraftBusinessRules.standardParagraph("NTT",107)+"\n"
                +DraftBusinessRules.standardParagraph("NTT",108)+"\n"
                +DraftBusinessRules.standardParagraph("NTT",109);
        String unrelated="UNRELATED-GUIDANCE-START\n"+guide+"\nUNRELATED-END-SENTINEL";
        Map<String,Object> plan=DraftBusinessRules.plan(DraftBusinessRules.map("electronicTendering","L10Pro"));
        String result=DraftClauseRules.apply(parsed.getText()+"\n"+unrelated,"NTT",plan);
        Map<String,Object> applied=action(plan,"NTT-2-L10PRO");
        assertEquals("applied",applied.get("application"),"Repeated standard guidance must resolve by its own body and continuation context.");
        assertEquals(2,DraftBusinessRules.list(applied.get("contextAnchors")).size());
        String body=result.substring(0,result.indexOf("UNRELATED-GUIDANCE-START"));
        assertFalse(flat(body).contains(flat(guide)),"Remove both selected-branch guidance occurrences.");
        assertTrue(body.contains("Technical support for the L10Pro program operation is available up to the tender closing date."));
        assertTrue(flat(body).contains(flat("(d) Upon receipt of tender documents")),"Retain the selected alternative's continuation body and actual paragraph label.");
        assertTrue(flat(body).contains(flat("Should the tenderer find any suspected error, mistake, abnormality in or be unable to access any of the files in the DVD-ROMs, the tenderer shall immediately inform the appropriate Housing Department officer as stated in Condition SCT7(1) of the SCT for assistance.")),"Retain the continuation's complete error-reporting obligation, not merely its opening or an action flag.");
        assertTrue(result.endsWith(unrelated),"Identical guidance elsewhere is outside the authorised target range.");
        assertFalse(DraftBusinessRules.list(plan.get("unresolved")).stream().anyMatch(o->"application-NTT-2-L10PRO".equals(DraftBusinessRules.asMap(o).get("id"))));

        // A changed neighbouring body must not make us choose another identical occurrence.
        Map<String,Object> changedPlan=DraftBusinessRules.plan(DraftBusinessRules.map("electronicTendering","L10Pro"));
        String changed=parsed.getText().replace("Technical support for the L10Pro program operation", "Project-specific technical support for the L10Pro program operation");
        String preserved=DraftClauseRules.apply(changed+"\n"+unrelated,"NTT",changedPlan);
        assertEquals("unresolved",action(changedPlan,"NTT-2-L10PRO").get("application"));
        assertTrue(flat(preserved).contains(flat(guide)),"Missing source context preserves the selected target and reports it.");
        assertTrue(preserved.endsWith(unrelated));
    }
    private static Map<String,Object> action(Map<String,Object>plan,String id){for(Object o:DraftBusinessRules.list(plan.get("actions")))if(id.equals(DraftBusinessRules.asMap(o).get("id")))return DraftBusinessRules.asMap(o);throw new AssertionError(id);}
    private static String flat(String text){return text.replaceAll("(?U)\\s+","");}
}
