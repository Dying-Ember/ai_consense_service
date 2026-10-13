package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact offline Python outputs for generic shapes and saved actual GCT units. */
class OerAttachmentPythonDifferentialTest {
    private static final String BASE=".local/state/oer-gct-cuda-retrieval-20261010/root-runtime-prep-01/root-margin-fix-01/";
    private static JsonNode fixture(String name,String expectedSha) throws Exception {
        String root=System.getenv("CONSENSE_OER_REPLAY_ROOT");assertNotNull(root,"Explicit offline replay root is required");
        byte[] bytes=Files.readAllBytes(Path.of(root,BASE,name));
        assertEquals(expectedSha,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),name);
        return JsonUtils.parse(new String(bytes,StandardCharsets.UTF_8));
    }
    private static JsonNode generic() throws Exception {return fixture("generic-source-units-fixtures.json","fed3e9c5b3d60cfeba493f517f30a23cd2472c28b7acb4d9fe7e160dd647a336");}
    private static JsonNode actual() throws Exception {return fixture("actual-gct-singleton-fixtures.json","7fa92008ad96526c5aec977e4506c1819d9545ee1fb53e497ad10a5f2ab900bc");}
    private static Map<String,Map<String,JsonNode>> datasets(JsonNode fixture) {
        Map<String,Map<String,JsonNode>> datasets=new LinkedHashMap<>();
        for(JsonNode dataset:fixture.path("datasets")) {
            Map<String,JsonNode> parents=new LinkedHashMap<>();
            for(JsonNode parent:dataset.path("parents"))assertNull(parents.put(parent.path("id").asText(),parent));
            assertNull(datasets.put(dataset.path("datasetId").asText(),parents));
        }
        return datasets;
    }
    private static String firstDifference(JsonNode expected,JsonNode actual,String path) {
        if(expected.equals(actual))return null;
        if(expected.isObject()&&actual.isObject()) {
            Set<String> keys=new TreeSet<>();expected.fieldNames().forEachRemaining(keys::add);actual.fieldNames().forEachRemaining(keys::add);
            for(String key:keys){String diff=firstDifference(expected.path(key),actual.path(key),path+"/"+key);if(diff!=null)return diff;}
        } else if(expected.isArray()&&actual.isArray()&&expected.size()==actual.size()) {
            for(int i=0;i<expected.size();i++){String diff=firstDifference(expected.get(i),actual.get(i),path+"/"+i);if(diff!=null)return diff;}
        }
        return path+" expected="+(expected.isContainerNode()?expected.getNodeType()+"["+expected.size()+"]":expected.toString())
                +" actual="+(actual.isContainerNode()?actual.getNodeType()+"["+actual.size()+"]":actual.toString());
    }
    private static void equal(JsonNode expected,JsonNode actual,String label) {assertNull(firstDifference(expected,actual,""),label);}
    private static ObjectNode response(JsonNode row) {
        ObjectNode response=JsonUtils.mapper().createObjectNode();response.set("hits",row.path("expectedSelectedHits"));response.set("sourceUnits",row.path("expectedSourceUnits"));return response;
    }
    private static List<String> ranked(JsonNode row) {List<String> out=new ArrayList<>();row.path("expectedSelectedHits").forEach(hit->out.add(hit.path("id").asText()));return out;}
    private static void exactCase(JsonNode row,Map<String,JsonNode> parents) {
        JsonNode seed=parents.get(row.path("seedId").asText());assertNotNull(seed);
        equal(row.path("expectedOldObservedUnit"),OerCanonicalSourceUnits.resolveObserved(parents,seed),"legacy observed fields differ");
        ObjectNode expected=row.path("expectedSourceUnits").path("units").get(0).deepCopy();expected.remove(Arrays.asList("seedScore","originalRerankOrdinal"));
        equal(expected,row.path("hierarchyContext").asBoolean()?OerCanonicalSourceUnits.resolveAttached(parents,seed):OerCanonicalSourceUnits.resolveObserved(parents,seed),"new exact context differs");
        OerCanonicalSourceUnits.validate(response(row),parents,ranked(row));
    }
    @TestFactory Collection<DynamicTest> genericAndActualSourceEpisodesMatchFrozenPythonFieldsAndHashes() throws Exception {
        List<DynamicTest> tests=new ArrayList<>();int index=0;
        for(JsonNode input:Arrays.asList(generic(),actual())) {
            Map<String,Map<String,JsonNode>> datasets=datasets(input);
            for(JsonNode row:input.path("cases")) {
                String name=String.format("%02d-%s-%s-mode-%s",++index,row.path("datasetId").asText(),row.path("seedId").asText(),row.path("hierarchyContext").asBoolean());
                tests.add(DynamicTest.dynamicTest(name,()->exactCase(row,datasets.get(row.path("datasetId").asText()))));
            }
        }
        assertEquals(34,tests.size());return tests;
    }
    @Test void actualColdCapabilityFixtureIsAcceptedWithoutClaimingModelExecution() throws Exception {
        JsonNode health=fixture("new-v2-full-cold-capability-health.json","54fc1c52eb4e8fcc4253abd6477b7e526d7594276b7be295955e496650bc8c51");
        assertFalse(health.path("loaded").path("embedding").asBoolean());assertFalse(health.path("loaded").path("reranker").asBoolean());
        VettingRetrievalHealth.Identity identity=VettingRetrievalHealth.validate(health,OerAttachmentHealthTest.expected().getVetting());
        assertEquals(VettingRetrievalSourceUnits.ATTACHMENT_POLICY,identity.sourceUnitPolicy);
    }
    @Test void completeBusinessUnionRetainsAllPythonStructuralRangesAndAddress() throws Exception {
        JsonNode input=generic(),row=null;
        for(JsonNode candidate:input.path("cases"))if(candidate.path("datasetId").asText().equals("native-both-contexts-positive")
                &&candidate.path("seedId").asText().equals("root")&&candidate.path("hierarchyContext").asBoolean()){row=candidate;break;}
        assertNotNull(row);Map<String,JsonNode> parents=datasets(input).get(row.path("datasetId").asText());
        JsonNode unit=row.path("expectedSourceUnits").path("units").get(0);
        Method method=OerBusinessEvidence.class.getDeclaredMethod("transportUnit",JsonNode.class,Map.class);method.setAccessible(true);
        JsonNode transport=(JsonNode)method.invoke(null,unit,parents),structural=row.path("expectedSelectedStructuralContext").path("structuralUnit");
        equal(structural.path("requiredMemberIds"),transport.path("requiredMemberIds"),"complete business membership differs");
        equal(structural.path("observedRanges"),transport.path("observedRanges"),"complete business source ranges differ");
        assertEquals(row.path("expectedStructuralBinding").path("contextId"),transport.path("contextId"));
        List<String> expectedBlocks=new ArrayList<>(),actualBlocks=new ArrayList<>();
        structural.path("observedRanges").forEach(range->expectedBlocks.add(range.path("blockId").asText()));
        transport.path("blockIds").forEach(block->actualBlocks.add(block.asText()));assertEquals(expectedBlocks,actualBlocks);
    }
    @Test void actualContextMemberHashEdgeAddressAndUnknownCannotBeForged() throws Exception {
        JsonNode input=actual(),row=input.path("cases").get(0);Map<String,JsonNode> parents=datasets(input).get(row.path("datasetId").asText());
        for(String mutation:Arrays.asList("member","memberHash","edge","contextAddress","dropContext","inventUnknown")) {
            ObjectNode changed=response(row).deepCopy(),unit=(ObjectNode)changed.path("sourceUnits").path("units").get(0);
            ObjectNode context=(ObjectNode)unit.path("attachmentContext");
            if(mutation.equals("member"))((ObjectNode)context.path("members").get(0)).put("content","invented text");
            if(mutation.equals("memberHash"))((ObjectNode)context.path("memberPayloadSha256").get(0)).put("sha256","0".repeat(64));
            if(mutation.equals("edge"))((ObjectNode)context.path("edgeDeclarations").get(0).path("relation")).put("toBlockId","unowned");
            if(mutation.equals("contextAddress"))context.put("contextId","0".repeat(64));
            if(mutation.equals("dropContext"))unit.remove("attachmentContext");
            if(mutation.equals("inventUnknown")){context.put("status","unknown");context.put("reason","invented fallback");}
            assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(changed,parents,ranked(row)),mutation);
        }
    }
}
