package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the business transport seam; invented values are not quality labels. */
class OerAttachmentTransportTest {
    private static ObjectNode member(String id,String text) {
        ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("id",id);out.put("unitId","unit:"+id);out.put("content",text);
        out.putArray("assetRefs").addObject().put("originalPointer",id);
        out.putObject("sourceQuality").put("semanticQuality","unknown");
        out.putArray("parts").addObject().put("text",text).put("blockId","block:"+id).put("anchor","block:"+id).put("startOffset",0).put("endOffset",text.length());
        return out;
    }
    private static ObjectNode context(String kind,JsonNode... members) {
        ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("policy",kind.equals("attachment")?"same-source-reviewed-direct-margin-sidebar-context-v1":OerCanonicalHierarchyContext.POLICY);
        out.put("status","complete_observed_"+kind+"_context");out.putNull("reason");out.put("contextId",kind+"-authored");out.put("seedObservedUnitId","observed-authored");
        ArrayNode ids=out.putArray("requiredMemberIds"),payloads=out.putArray("members");
        for(JsonNode member:members){ids.add(member.path("id"));payloads.add(member.deepCopy());}
        out.putArray("edgeDeclarations");out.putArray("memberPayloadSha256");out.putArray("blockIds");out.putArray("observedRanges");
        out.put("memberOrder","canonical_source_input");out.put("derivedMembersCanBecomeOrigins",false);out.putNull("memberScores");out.put("qualifiersComplete","unknown");out.put("semanticScopeVerified",false);out.put("legalClosureVerified",false);
        return out;
    }
    private static ObjectNode observed(JsonNode seed) {
        ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("status","complete_observed_unit");out.putNull("reason");out.put("unitId","observed-authored");
        out.putArray("originIds").add(seed.path("id"));out.putArray("requiredMemberIds").add(seed.path("id"));out.putArray("members").add(seed.deepCopy());return out;
    }
    private static JsonNode transport(JsonNode unit,JsonNode... source)throws Exception {
        Map<String,JsonNode> parents=new LinkedHashMap<>();for(JsonNode member:source)parents.put(member.path("id").asText(),member);
        Method method=OerBusinessEvidence.class.getDeclaredMethod("transportUnit",JsonNode.class,Map.class);method.setAccessible(true);return (JsonNode)method.invoke(null,unit,parents);
    }
    private static List<String> ids(JsonNode unit){List<String> ids=new ArrayList<>();unit.path("requiredMemberIds").forEach(id->ids.add(id.asText()));return ids;}

    @Test void noteOnlyHitTransportsFullBoundBodyAndOriginalPointers()throws Exception {
        JsonNode note=member("note","See conditions"),body=member("body","Only sealed drawing sets are admissible.");
        ObjectNode unit=observed(note),attachment=context("attachment",body,note);unit.set("attachmentContext",attachment);
        JsonNode selected=transport(unit,body,note);assertEquals(Arrays.asList("body","note"),ids(selected));assertEquals(attachment.path("members"),selected.path("members"));
        assertFalse(selected.path("semanticScopeVerified").asBoolean());assertFalse(selected.path("legalClosureVerified").asBoolean());assertTrue(selected.path("memberScores").isNull());
    }
    @Test void hierarchyAndDirectAttachmentTransportCompleteUnionWithoutAnotherBranch()throws Exception {
        JsonNode body=member("body","All requirements follow."),child=member("child","Provide sealed copies."),note=member("note","See conditions");
        ObjectNode unit=observed(body);unit.set("hierarchyContext",context("hierarchy",body,child));unit.set("attachmentContext",context("attachment",body,note));
        JsonNode selected=transport(unit,body,note,child);assertEquals(Arrays.asList("body","note","child"),ids(selected));assertEquals(3,selected.path("members").size());
        for(JsonNode original:Arrays.asList(body,child,note))assertTrue(((ArrayNode)selected.path("members")).findValues("id").contains(original.path("id")));
    }
    @Test void unknownAttachmentCannotBeHiddenByCompleteHierarchy()throws Exception {
        JsonNode body=member("body","All requirements follow."),child=member("child","Provide sealed copies.");
        ObjectNode unit=observed(body),attachment=context("attachment");attachment.put("status","unknown");attachment.put("reason","attachment_endpoint_missing");
        unit.set("hierarchyContext",context("hierarchy",body,child));unit.set("attachmentContext",attachment);
        JsonNode selected=transport(unit,body,child);assertEquals("unknown",selected.path("status").asText());assertEquals("attachment_endpoint_missing",selected.path("reason").asText());assertTrue(selected.path("members").isEmpty());
    }
    @Test void hierarchyFailureAlsoBlocksAnOtherwiseCompleteAttachmentUnion()throws Exception {
        JsonNode body=member("body","All requirements follow."),note=member("note","See conditions");
        ObjectNode unit=observed(body),hierarchy=context("hierarchy");hierarchy.put("status","unknown");hierarchy.put("reason","hierarchy_endpoint_missing");
        unit.set("hierarchyContext",hierarchy);unit.set("attachmentContext",context("attachment",body,note));
        JsonNode selected=transport(unit,body,note);assertEquals("unknown",selected.path("status").asText());assertTrue(selected.path("members").isEmpty());
    }
}
