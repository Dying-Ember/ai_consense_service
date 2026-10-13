package com.consense.service.drafting;
import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.TemplateIndex;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Minimized actual source seam: independent action, source matcher and native edit factories. */
class DraftCurrentNegativeRuleReplayTest {
    static TemplateIndex source; static Map<String,Object> values;
    @BeforeAll static void source()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("consense.audit.captureDir")!=null&&!System.getProperty("consense.audit.captureDir").isBlank(),"Actual-source replay requires explicit -Dconsense.audit.captureDir; no original or user answers are committed.");
        Path capture=Paths.get(System.getProperty("consense.audit.captureDir"));
        source=new DocxTemplateEditor().inspect(Files.readAllBytes(capture.resolve("SCC-source.docx")));
        values=DraftBusinessRules.asMap(DraftBusinessRules.asMap(JsonUtils.readMap(Files.readString(capture.resolve("plan-api.json"))).get("data")).get("inputValues"));
    }
    static Stream<String> parents(){return DraftCurrentNegativeOutputReplayTest.ordinaryParents();}
    @ParameterizedTest @MethodSource("parents")
    void currentNegativeProducesSourceBoundNotUsedEdit(String actionId){
        String before=JsonUtils.write(values);Map<String,Object> plan=DraftBusinessRules.plan(values);
        Map<String,Object> action=DraftBusinessRules.asMap(DraftBusinessRules.list(plan.get("actions")).stream().filter(a->actionId.equals(DraftBusinessRules.asMap(a).get("id"))).findFirst().orElseThrow());
        plan.put("actions",new ArrayList<>(Collections.singletonList(action)));
        DraftSourceEditCompiler compiler=new DraftSourceEditCompiler(source,"SCC");
        System.out.println("[negative-rule-case-start] "+actionId);
        String output=DraftClauseRules.apply(compiler.projection(),"SCC",plan,compiler);
        assertEquals("applied",action.get("applicationState"),JsonUtils.write(action.get("detail"))+" "+action.get("formatError"));
        assertTrue(output.replaceAll("(?U)\\s+"," ").contains(action.get("clause")+" Not used"));
        assertFalse(compiler.edits().isEmpty());assertEquals(before,JsonUtils.write(values));
    }
}
