package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real legacy client handshake and ordinary source payload; explicit HTTP doubles only. */
class VettingLegacyPolicyCompatibilityTest {
    static final class Wire {
        final HttpSupport http=mock(HttpSupport.class);
        final ConsenseProperties props;
        final ObjectNode health,response;
        final List<Chunk> corpus;
        final VettingRetrievalClient client;
        Wire(boolean dual) throws Exception {
            VettingRetrievalSourceUnitsTest ordinary=new VettingRetrievalSourceUnitsTest();
            Chunk head=ordinary.c("ordinary-head","ordinary-terms","tender","AAA.1","body:1","AAA.1 The archive shall retain records."),
                    tail=ordinary.c("ordinary-tail","ordinary-terms","tender","AAA.1","body:2","Unless dormant the source record is required.");
            corpus=Arrays.asList(head,tail);response=(ObjectNode)ordinary.response(head,corpus);
            ObjectNode selection=VettingDualStructureHealthTest.health().path("retrieval").path("sourceWindows").path("structuralSelection").deepCopy();
            String policy=dual?VettingRetrievalSourceUnits.TYPED_PARENT_POLICY:VettingRetrievalSourceUnits.POLICY;
            if(dual) {
                health=VettingDualStructureHealthTest.health();props=VettingDualStructureHealthTest.expected();
                ((ObjectNode)health.path("retrieval").path("sourceWindows").path("structuralRecipe")).put("version",VettingRetrievalHealth.TYPED_PARENT_STRUCTURAL_VERSION);
                selection.put("hierarchyContextPolicy",OerCanonicalHierarchyContext.POLICY);
            } else {
                health=(ObjectNode)VettingStrictHybridTest.currentHealth().deepCopy();props=VettingStrictHybridTest.strict();
            }
            selection.put("policy",policy);ObjectNode windows=(ObjectNode)health.path("retrieval").path("sourceWindows");windows.set("structuralSelection",selection);
            ((ObjectNode)windows.path("algorithms")).put("retrieval_source_units.py",VettingCorpus.hash("explicit source algorithm fixture"));
            ((ObjectNode)response.path("sourceUnits")).put("policy",policy);
            String contract=dual?VettingRetrievalHealth.STRUCTURAL_CONTRACT:VettingRetrievalHealth.WINDOW_CONTRACT;
            response.put("mode",contract);
            Map<String,Object> receipt=VettingStrictHybridTest.receipt(corpus.size());receipt.put("contract",contract);
            when(http.get(endsWith("/health"),anyLong())).thenAnswer(call->JsonUtils.write(health));
            when(http.postJson(endsWith("/index"),anyString(),anyLong())).thenReturn(JsonUtils.write(receipt));
            when(http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenAnswer(call->JsonUtils.write(response));
            client=new VettingRetrievalClient(http,props);
        }
        void index(){client.index("p",corpus);}
        List<Chunk> query(){return client.retrieve("p","inspect original archive record","tender",corpus);}
    }

    @Test void dualV2OrdinaryChunksWithoutHierarchyContextPassActualClientSeam() throws Exception { assertOrdinary(true); }
    @Test void windowV1OrdinaryChunksStillPassActualClientSeam() throws Exception { assertOrdinary(false); }
    private void assertOrdinary(boolean dual) throws Exception {
        Wire w=new Wire(dual);String before=JsonUtils.write(w.corpus);w.index();List<Chunk> ranked=w.query();
        assertEquals(Collections.singletonList(w.corpus.get(0)),ranked);assertEquals(1,ranked.size());
        List<VettingRetrievalSourceUnits.Unit> units=VettingRetrievalSourceUnits.attached(ranked);assertEquals(1,units.size());
        assertEquals(Arrays.asList("ordinary-head","ordinary-tail"),units.get(0).ids);assertFalse(units.get(0).trace.containsKey("hierarchyContext"));
        String expectedPolicy=dual?VettingRetrievalSourceUnits.TYPED_PARENT_POLICY:VettingRetrievalSourceUnits.POLICY;
        assertEquals(expectedPolicy,units.get(0).trace.get("selectionPolicy"));
        assertEquals(before,JsonUtils.write(w.corpus));
        verify(w.http,times(2)).get(endsWith("/health"),anyLong());
        verify(w.http).postJson(endsWith("/index"),anyString(),anyLong());verify(w.http).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }

    @TestFactory Collection<DynamicTest> episodePolicyCannotDowngradeOrUpgradeIndexedCapability() {
        List<DynamicTest> tests=new ArrayList<>();
        for(boolean dual:Arrays.asList(false,true))tests.add(DynamicTest.dynamicTest(dual?"dual_v2_downgrade_to_v1":"window_v1_upgrade_to_v2",()->{
            Wire w=new Wire(dual);w.index();((ObjectNode)w.response.path("sourceUnits")).put("policy",dual?VettingRetrievalSourceUnits.POLICY:VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);
            IllegalStateException error=assertThrows(IllegalStateException.class,w::query);assertTrue(error.getMessage().contains("indexed health"),error.getMessage());
        }));return tests;
    }

    @TestFactory Collection<DynamicTest> legacyReaderRejectsEveryAttachedHierarchyFieldEvenEmptyOrNull() {
        List<DynamicTest> tests=new ArrayList<>();
        for(boolean dual:Arrays.asList(false,true))for(String field:Arrays.asList("null","empty","declared"))tests.add(DynamicTest.dynamicTest((dual?"v2":"v1")+"_hierarchy_"+field,()->{
            Wire w=new Wire(dual);ObjectNode unit=(ObjectNode)w.response.path("sourceUnits").path("units").get(0);
            if("null".equals(field))unit.putNull("hierarchyContext");else if("empty".equals(field))unit.putObject("hierarchyContext");
            else unit.putObject("hierarchyContext").put("policy",OerCanonicalHierarchyContext.POLICY).put("status","complete_observed_hierarchy_context");
            w.index();IllegalStateException error=assertThrows(IllegalStateException.class,w::query);assertTrue(error.getMessage().contains("legacy reader cannot validate hierarchyContext"),error.getMessage());
        }));return tests;
    }

    @TestFactory Collection<DynamicTest> advertisedMissingEpisodeRetainsExistingRequiredGate() {
        List<DynamicTest> tests=new ArrayList<>();for(boolean dual:Arrays.asList(false,true))tests.add(DynamicTest.dynamicTest(dual?"v2_missing_episode":"v1_missing_episode",()->{
            Wire w=new Wire(dual);w.index();w.response.remove("sourceUnits");IllegalStateException error=assertThrows(IllegalStateException.class,w::query);
            assertTrue(error.getMessage().contains("advertised source-unit episode is missing"),error.getMessage());
        }));return tests;
    }

    static JsonNode actualModes() throws Exception {
        String path=System.getenv("CONSENSE_OER_MODE_FIXTURE");
        org.junit.jupiter.api.Assumptions.assumeTrue(path!=null,"Actual Python mode capture requires explicit CONSENSE_OER_MODE_FIXTURE");
        String raw=new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8);
        assertEquals("7beec767e60af8626d67e8e8f757553a4c9f1e6cb9d9398583ef27b9daa22aed",VettingCorpus.hash(raw),"Frozen actual Python modes changed");
        JsonNode fixture=JsonUtils.parse(raw);assertEquals("consense.python-actual-mode-health-episode-fixtures.v1",fixture.path("schema").asText());
        assertEquals(4,fixture.path("cases").size());assertFalse(fixture.path("firstLlmResponseRead").asBoolean());assertFalse(fixture.path("officialAnswersOrGoldRead").asBoolean());
        return fixture;
    }

    @TestFactory Collection<DynamicTest> actualPythonModeHealthAndOriginalCanonicalEpisodesMatchJava() throws Exception {
        List<DynamicTest> tests=new ArrayList<>();
        for(JsonNode row:actualModes().path("cases"))tests.add(DynamicTest.dynamicTest(row.path("id").asText(),()->{
            boolean dual="dual-structure-v1".equals(row.path("mode").asText());
            ConsenseProperties props=dual?VettingDualStructureHealthTest.expected():VettingStrictHybridTest.strict();
            VettingRetrievalHealth.Identity identity=VettingRetrievalHealth.validate(row.path("health"),props.getVetting());
            JsonNode response=row.path("retrieveResponse");assertEquals(identity.sourceUnitPolicy,response.path("sourceUnits").path("policy").asText());
            Map<String,JsonNode> actualParents=OerTypedHierarchyContextTest.map(row.path("parents")),frozenParents=OerTypedHierarchyContextTest.parents();
            for(Map.Entry<String,JsonNode> parent:actualParents.entrySet())assertEquals(frozenParents.get(parent.getKey()),parent.getValue(),"Mode fixture altered original source payload");
            List<String> rankedIds=new ArrayList<>();response.path("hits").forEach(hit->rankedIds.add(hit.path("id").asText()));
            String before=JsonUtils.write(row);OerCanonicalSourceUnits.validate(response,actualParents,rankedIds);assertEquals(before,JsonUtils.write(row));
            for(JsonNode unit:response.path("sourceUnits").path("units")) {
                if(!dual||row.path("id").asText().endsWith("ordinary-scc"))assertFalse(unit.has("hierarchyContext"));
                else assertEquals(31,unit.path("hierarchyContext").path("members").size());
            }
        }));return tests;
    }

    @TestFactory Collection<DynamicTest> actualPythonHealthAlsoPassesOrdinaryLegacyClientHandshake() throws Exception {
        List<DynamicTest> tests=new ArrayList<>();
        for(JsonNode row:actualModes().path("cases"))if(row.path("id").asText().endsWith("ordinary-scc"))tests.add(DynamicTest.dynamicTest(row.path("id").asText()+"_health_with_authored_ordinary_legacy_chunks",()->{
            boolean dual="dual-structure-v1".equals(row.path("mode").asText());Wire w=new Wire(dual);
            // Native canonical SCC parents stay in the independent canonical
            // test above. Only the actual health is used with these authored
            // legacy DTOs; no native source field is stripped to fit a DTO.
            w.health.removeAll();w.health.setAll((ObjectNode)row.path("health").deepCopy());
            w.index();List<Chunk> ranked=w.query();assertEquals(Collections.singletonList(w.corpus.get(0)),ranked);
            assertEquals(row.path("retrieveResponse").path("sourceUnits").path("policy").asText(),VettingRetrievalSourceUnits.attached(ranked).get(0).trace.get("selectionPolicy"));
        }));return tests;
    }
}
