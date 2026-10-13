package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen actual source-only replay; no gold answers, network, database or model. */
class OerTypedHierarchyContextTest {
    static final String REPLAY_ROOT=System.getenv("CONSENSE_OER_REPLAY_ROOT");
    static final Path ROOT=REPLAY_ROOT==null?null:Paths.get(REPLAY_ROOT);
    static final String META_PATH=".local/state/oer-unified-actual-preparation-20261010/run-222702-e68e99/retrieval-state/projects/6f1f490252b1f726c16b75b35495cd04032632fe3c4ce5ae5276040a6837bbff.json";
    static final String EXPECTED_PATH=".local/state/oer-typed-parent-context-closure-20261010/run-235240-sourceonly/actual-first-title-context-fixture.json";
    static JsonNode read(Path path)throws Exception{return JsonUtils.parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8));}
    static Map<String,JsonNode> parents()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ROOT!=null,"Actual local source replay requires explicit CONSENSE_OER_REPLAY_ROOT");
        String raw=new String(Files.readAllBytes(ROOT.resolve(META_PATH)),StandardCharsets.UTF_8);
        assertEquals("7e67db58cc193df3a616cb3fddcad91e84019467f09a4408772048d98908b9cc",VettingCorpus.hash(raw),"Frozen metadata changed");
        Map<String,JsonNode> out=new LinkedHashMap<>();for(JsonNode p:JsonUtils.parse(raw).path("chunks"))out.put(p.path("id").asText(),p);assertEquals(72,out.size());return out;
    }
    static ObjectNode expected()throws Exception{return (ObjectNode)read(ROOT.resolve(EXPECTED_PATH));}
    static Map<String,JsonNode> map(JsonNode rows){Map<String,JsonNode> out=new LinkedHashMap<>();rows.forEach(p->out.put(p.path("id").asText(),p));return out;}
    @TestFactory Collection<DynamicTest> authoredPythonContractFieldsHashesAndUnknownReasonsMatch()throws Exception {
        List<DynamicTest> tests=new ArrayList<>();JsonNode fixture=VettingDualStructureHealthTest.resource("authored_typed_parent_context.json");
        for(JsonNode row:fixture.path("cases"))tests.add(DynamicTest.dynamicTest(row.path("name").asText(),()->{
            Map<String,JsonNode> parents=map(row.path("parents"));String seed=row.path("seedId").asText();
            assertTrue(row.path("expectedObserved").equals(OerCanonicalSourceUnits.resolveObserved(parents,parents.get(seed))),"Old exact observed unit differs: "+row.path("name").asText());
            assertTrue(row.path("expected").equals(OerCanonicalSourceUnits.resolve(parents,parents.get(seed))),"Typed context fields/hash/order/unknown differ: "+row.path("name").asText()+" "+diff(row.path("expected"),OerCanonicalSourceUnits.resolve(parents,parents.get(seed)),""));
        }));return tests;
    }
    @Test void allFrozenActualSourceCasesMatchPythonIncludingUnassociatedNoContext()throws Exception {
        Map<String,JsonNode> parents=parents();JsonNode contract=read(ROOT.resolve(".local/state/oer-typed-parent-context-closure-20261010/run-235240-sourceonly/local-freeze/java-context-contract-cases.json"));
        for(JsonNode row:contract.path("actualMetadata").path("cases")) {
            String seed=row.path("seedId").asText();
            assertTrue(row.path("expected").equals(OerCanonicalSourceUnits.resolve(parents,parents.get(seed))),"Actual source context differs: "+row.path("name").asText()+" "+diff(row.path("expected"),OerCanonicalSourceUnits.resolve(parents,parents.get(seed)),""));
        }
    }
    @Test void actualTitleSeedMustDeliverAllDeclaredDescendantBodiesWithoutChangingObservedUnit()throws Exception {
        Map<String,JsonNode> parents=parents();ObjectNode expected=expected();String seed=expected.path("originIds").get(0).asText();ObjectNode actual=OerCanonicalSourceUnits.resolve(parents,parents.get(seed));
        assertEquals("complete_observed_hierarchy_context",actual.path("hierarchyContext").path("status").asText(),"Title retrieval still lacks declared descendant context");
        assertEquals(31,actual.path("hierarchyContext").path("members").size(),"Original title and 30 declared descendant whole units are required");
        ObjectNode old=expected.deepCopy();old.remove(Arrays.asList("hierarchyContext","originalRerankOrdinal","seedScore"));
        ObjectNode actualOld=actual.deepCopy();actualOld.remove("hierarchyContext");assertTrue(old.equals(actualOld),"Existing exact observed unit changed");
        expected.remove(Arrays.asList("originalRerankOrdinal","seedScore"));assertTrue(expected.equals(actual),"Independent Java context fields/hashes/order differ from frozen Python source-only replay "+diff(expected,actual,""));
    }
    static String diff(JsonNode a,JsonNode b,String path){
        if(a.equals(b))return "";
        if(a.isObject()&&b.isObject()){Set<String> keys=new TreeSet<>();a.fieldNames().forEachRemaining(keys::add);b.fieldNames().forEachRemaining(keys::add);for(String key:keys){String d=diff(a.path(key),b.path(key),path+"/"+key);if(!d.isEmpty())return d;}}
        if(a.isArray()&&b.isArray()){if(a.size()!=b.size())return path+" size expected="+a.size()+" actual="+b.size();for(int i=0;i<a.size();i++){String d=diff(a.get(i),b.get(i),path+"/"+i);if(!d.isEmpty())return d;}}
        String x=a.toString(),y=b.toString();return path+" expected="+x.substring(0,Math.min(100,x.length()))+" actual="+y.substring(0,Math.min(100,y.length()));
    }

}
