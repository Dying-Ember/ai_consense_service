package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exact current source and already-confirmed values; never calls recognition or a model. */
class DraftCurrentNegativeOutputReplayTest {
    static Stream<String> ordinaryParents() { return Stream.of(
        "scc-advance-adoption", "scc-railway-adoption", "scc-ovt-definitions", "scc-ovt-aftercare",
        "scc-ovt-protection", "scc-excision", "scc-permit-definition", "scc-permit-works", "scc-ssf",
        "scc-show-flats", "scc-design-foundation-warranty", "scc-design-adverse-ground", "scc-design-failure-cost",
        "scc-all-provisional-definition", "scc-bq-measurement", "scc-weather-8303", "scc-section-enhancement",
        "scc-late-possession", "scc-additional-loss", "scc-demolition-training", "scc-partnership", "scc-joint-venture",
        "scc-specialist-SCC20.303"); }
    @ParameterizedTest @MethodSource("ordinaryParents")
    void confirmedNegativeParentReachesTheActualSavedDocx(String actionId) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("consense.audit.captureDir")!=null&&!System.getProperty("consense.audit.captureDir").isBlank(),"Actual-source replay requires explicit -Dconsense.audit.captureDir; no original or user answers are committed.");
        Path capture=Paths.get(System.getProperty("consense.audit.captureDir"));
        Map<String,Object> saved=DraftBusinessRules.asMap(JsonUtils.readMap(Files.readString(capture.resolve("plan-api.json"))).get("data"));
        Map<String,Object> values=DraftBusinessRules.asMap(saved.get("inputValues"));
        String before=JsonUtils.write(values);
        Map<String,Object> plan=DraftBusinessRules.plan(values);
        Map<String,Object> action=DraftBusinessRules.asMap(DraftBusinessRules.list(plan.get("actions")).stream().filter(a->actionId.equals(DraftBusinessRules.asMap(a).get("id"))).findFirst().get());
        assertTrue(Arrays.asList("not_adopted","not_used").contains(action.get("action")),actionId);
        String clause=String.valueOf(action.get("clause"));
        plan.put("actions",new ArrayList<>(Collections.singletonList(action)));
        byte[] source=Files.readAllBytes(capture.resolve("SCC-source.docx"));
        DocxTemplateEditor.DocxEditResult result=DraftFormattedRules.compile(source,"SCC",plan);
        String actual=DraftFormattedRules.content(result.getIndex()).replaceAll("(?U)\\s+"," ");
        assertTrue(actual.contains(clause+" Not used"),actionId+" must not leave the inapplicable obligation in the actual saved DOCX");
        assertEquals("applied",action.get("applicationState"),JsonUtils.write(action.get("detail")));
        assertEquals(before,JsonUtils.write(values),"Confirmed inputs are immutable during output application");
    }
}
