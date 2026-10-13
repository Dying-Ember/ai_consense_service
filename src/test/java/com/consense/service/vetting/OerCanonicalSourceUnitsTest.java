package com.consense.service.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Expected records come from the independent Python selector, including its unknown reasons and hashes. */
class OerCanonicalSourceUnitsTest {
    private static JsonNode fixtures() throws Exception {return VettingDualStructureHealthTest.resource("authored_canonical_source_units.json");}
    private static Map<String,JsonNode> parents(JsonNode row) {
        Map<String,JsonNode> out=new LinkedHashMap<>();for(JsonNode p:row.path("parents"))out.put(p.path("id").asText(),p);return out;
    }
    private static JsonNode caseNamed(String name) throws Exception {
        for(JsonNode row:fixtures().path("cases"))if(name.equals(row.path("name").asText()))return row;
        throw new AssertionError(name);
    }
    @TestFactory Collection<DynamicTest> pythonResolverEpisodesMatchExactly() throws Exception {
        List<DynamicTest> tests=new ArrayList<>();for(JsonNode row:fixtures().path("cases"))tests.add(DynamicTest.dynamicTest(row.path("name").asText(),()->{
            Map<String,JsonNode> corpus=parents(row);String seed=row.path("seedId").asText();
            assertEquals(row.path("resolved"),OerCanonicalSourceUnits.resolve(corpus,corpus.get(seed)));
            OerCanonicalSourceUnits.validate(row.path("response"),corpus,Collections.singletonList(seed));
        }));return tests;
    }
    @Test void derivedMembersKeepOtherPartsWithoutExpandingTheirOtherBlocks() throws Exception {
        JsonNode row=caseNamed("block_member_other_blocks_do_not_become_origins");
        JsonNode resolved=OerCanonicalSourceUnits.resolve(parents(row),parents(row).get("head"));
        assertEquals("complete_observed_unit",resolved.path("status").asText());
        assertEquals(1,resolved.path("blockIds").size());assertEquals("A",resolved.path("blockIds").get(0).asText());
        assertEquals(Arrays.asList("head","middle"),Arrays.asList(resolved.path("requiredMemberIds").get(0).asText(),resolved.path("requiredMemberIds").get(1).asText()));
        assertEquals(2,resolved.path("members").get(1).path("parts").size());
    }
    @Test void completeUnitCannotBeDowngradedToArbitraryUnknownOrAddress() throws Exception {
        JsonNode row=caseNamed("non_bmp_utf16_exact_clause");Map<String,JsonNode> corpus=parents(row);
        for(String mutation:Arrays.asList("status","address","reason","blocks")) {
            ObjectNode response=row.path("response").deepCopy();ObjectNode unit=(ObjectNode)response.path("sourceUnits").path("units").get(0);
            if("status".equals(mutation)){unit.put("status","unknown");unit.put("reason","invented unknown");unit.putArray("members");unit.putArray("requiredMemberIds");unit.putArray("observedRanges");}
            if("address".equals(mutation))unit.put("unitId",VettingCorpus.hash("arbitrary SHA"));
            if("reason".equals(mutation))unit.put("reason","not a null reason");
            if("blocks".equals(mutation))unit.putArray("blockIds");
            assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(response,corpus,Collections.singletonList("head")),mutation);
        }
    }
    @Test void unknownCrossClauseCannotBecomeCompleteEvenWithAllInventedMembers() throws Exception {
        JsonNode row=caseNamed("source_block_crosses_clause_unit_boundary");Map<String,JsonNode> corpus=parents(row);
        ObjectNode response=row.path("response").deepCopy();ObjectNode unit=(ObjectNode)response.path("sourceUnits").path("units").get(0);
        unit.put("status","complete_observed_unit");unit.putNull("reason");
        ArrayNode ids=unit.putArray("requiredMemberIds"),members=unit.putArray("members");
        corpus.forEach((id,p)->{ids.add(id);members.add(p);});
        assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(response,corpus,Collections.singletonList("head")));
    }
    @Test void duplicateReliableClauseCannotBePresentedAsTwoDifferentUnitHashes() throws Exception {
        JsonNode row=caseNamed("non_bmp_utf16_exact_clause");Map<String,JsonNode> corpus=parents(row);
        ObjectNode response=row.path("response").deepCopy();ObjectNode next=response.withArray("hits").addObject();
        next.put("id","tail");next.put("score",0.8);next.set("payload",corpus.get("tail"));
        ObjectNode episode=(ObjectNode)response.path("sourceUnits");episode.withArray("originalHitIds").add("tail");
        ObjectNode unit=OerCanonicalSourceUnits.resolve(corpus,corpus.get("tail"));unit.put("originalRerankOrdinal",2);unit.put("seedScore",0.8);
        unit.put("unitId",VettingCorpus.hash("fake distinct reliable unit"));episode.withArray("units").add(unit);
        assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(response,corpus,Arrays.asList("head","tail")));
    }
}
