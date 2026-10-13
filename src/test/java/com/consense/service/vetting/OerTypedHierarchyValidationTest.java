package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit wire tamper probes; no original facts or neural relevance labels invented. */
class OerTypedHierarchyValidationTest {
    static JsonNode authored()throws Exception {return VettingDualStructureHealthTest.resource("authored_typed_parent_context.json").path("cases").get(0);}
    static ObjectNode episode(Map<String,JsonNode> parents,String seed,ObjectNode unit,String policy){
        double score=unit.has("seedScore")?unit.path("seedScore").asDouble():0.9;unit.put("seedScore",score);if(!unit.has("originalRerankOrdinal"))unit.put("originalRerankOrdinal",1);
        ObjectNode response=JsonUtils.mapper().createObjectNode();ObjectNode hit=response.putArray("hits").addObject();hit.put("id",seed);hit.put("score",score);hit.set("payload",parents.get(seed).deepCopy());
        ObjectNode source=response.putObject("sourceUnits");source.put("policy",policy);source.put("scope","canonical_observed_structure_only");source.put("memberOrder","canonical_parent_id_lexical");source.put("qualifiersComplete","unknown");source.put("derivedMembersCanBecomeOrigins",false);source.put("semanticScopeVerified",false);
        source.putArray("originalHitIds").add(seed);source.putArray("units").add(unit);return response;
    }
    @Test void v1ReadsOnlyOldObservedEpisodeWhileV2RequiresRecomputedContext()throws Exception {
        JsonNode row=authored();Map<String,JsonNode> parents=OerTypedHierarchyContextTest.map(row.path("parents"));String seed=row.path("seedId").asText();
        ObjectNode old=episode(parents,seed,row.path("expectedObserved").deepCopy(),VettingRetrievalSourceUnits.POLICY);assertDoesNotThrow(()->OerCanonicalSourceUnits.validate(old,parents,Collections.singletonList(seed)));
        ((ObjectNode)old.path("sourceUnits")).put("policy",VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(old,parents,Collections.singletonList(seed)));
        ObjectNode updated=episode(parents,seed,row.path("expected").deepCopy(),VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);assertDoesNotThrow(()->OerCanonicalSourceUnits.validate(updated,parents,Collections.singletonList(seed)));
        ((ObjectNode)updated.path("sourceUnits")).put("policy",VettingRetrievalSourceUnits.POLICY);assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(updated,parents,Collections.singletonList(seed)));
    }
    @TestFactory Collection<DynamicTest> hierarchyAndSeedTamperCannotPassAsUnvalidatedExtraFields()throws Exception {
        List<DynamicTest> tests=new ArrayList<>();
        for(String mutation:Arrays.asList("missingContext","memberMissing","memberLiteral","memberOrder","memberHash","edgeRelation","edgeId","contextId","originAllowed","memberScores","semanticVerified","legalVerified","unknownInvented","extraContextField","extraUnitField","seedScore"))tests.add(DynamicTest.dynamicTest(mutation,()->{
            JsonNode row=authored();Map<String,JsonNode> parents=OerTypedHierarchyContextTest.map(row.path("parents"));String seed=row.path("seedId").asText();ObjectNode response=episode(parents,seed,row.path("expected").deepCopy(),VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);
            ObjectNode unit=(ObjectNode)response.path("sourceUnits").path("units").get(0),context=(ObjectNode)unit.path("hierarchyContext");
            switch(mutation){
                case "missingContext":unit.remove("hierarchyContext");break;
                case "memberMissing":context.withArray("members").remove(1);break;
                case "memberLiteral":((ObjectNode)context.path("members").get(1)).put("content","forged contract fact");break;
                case "memberOrder":JsonNode first=context.withArray("requiredMemberIds").get(0);context.withArray("requiredMemberIds").set(0,context.withArray("requiredMemberIds").get(1));context.withArray("requiredMemberIds").set(1,first);break;
                case "memberHash":((ObjectNode)context.path("memberPayloadSha256").get(1)).put("sha256",VettingCorpus.hash("forged member"));break;
                case "edgeRelation":((ObjectNode)context.path("edgeDeclarations").get(0).path("relation")).put("basis","forged relation");break;
                case "edgeId":((ObjectNode)context.path("edgeDeclarations").get(0)).put("edgeId",VettingCorpus.hash("forged edge"));break;
                case "contextId":context.put("contextId",VettingCorpus.hash("forged context"));break;
                case "originAllowed":context.put("derivedMembersCanBecomeOrigins",true);break;
                case "memberScores":context.putArray("memberScores").add(0.99);break;
                case "semanticVerified":context.put("semanticScopeVerified",true);break;
                case "legalVerified":context.put("legalClosureVerified",true);break;
                case "unknownInvented":context.put("status","unknown");context.put("reason","not original uncertainty");context.putArray("members");context.putArray("requiredMemberIds");break;
                case "extraContextField":context.put("modelOverrideAccepted",true);break;
                case "extraUnitField":unit.put("futureUnvalidatedContext",true);break;
                case "seedScore":unit.put("seedScore",0.5);break;
                default:throw new AssertionError(mutation);
            }
            assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(response,parents,Collections.singletonList(seed)),mutation);
        }));return tests;
    }
    @Test void recipeAndCapabilityMustMatchPolicyAndChangeHealthIdentity()throws Exception {
        ObjectNode old=VettingDualStructureHealthTest.health();String oldId=VettingRetrievalHealth.validate(old,VettingDualStructureHealthTest.expected().getVetting()).fingerprint;
        ObjectNode next=old.deepCopy();ObjectNode selection=(ObjectNode)next.path("retrieval").path("sourceWindows").path("structuralSelection");selection.put("policy",VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(next,VettingDualStructureHealthTest.expected().getVetting()));
        ((ObjectNode)next.path("retrieval").path("sourceWindows").path("structuralRecipe")).put("version",VettingRetrievalHealth.TYPED_PARENT_STRUCTURAL_VERSION);
        assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(next,VettingDualStructureHealthTest.expected().getVetting()));
        selection.put("hierarchyContextPolicy",OerCanonicalHierarchyContext.POLICY);String nextId=VettingRetrievalHealth.validate(next,VettingDualStructureHealthTest.expected().getVetting()).fingerprint;assertNotEquals(oldId,nextId);
        selection.put("policy",VettingRetrievalSourceUnits.POLICY);assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(next,VettingDualStructureHealthTest.expected().getVetting()));
    }
    @Test void indexedV2CapabilityCannotAcceptLegacyEpisodeAndV1CannotAcceptTypedEpisode()throws Exception {
        OerNativePdfRetrievalClientTest.Wire typed=new OerNativePdfRetrievalClientTest.Wire();
        ObjectNode windows=(ObjectNode)typed.health.path("retrieval").path("sourceWindows");
        ((ObjectNode)windows.path("structuralRecipe")).put("version",VettingRetrievalHealth.TYPED_PARENT_STRUCTURAL_VERSION);
        ((ObjectNode)windows.path("structuralSelection")).put("policy",VettingRetrievalSourceUnits.TYPED_PARENT_POLICY).put("hierarchyContextPolicy",OerCanonicalHierarchyContext.POLICY);
        typed.index();assertThrows(IllegalStateException.class,typed::query);
        ((ObjectNode)typed.response.path("sourceUnits")).put("policy",VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);assertDoesNotThrow(typed::query);
        OerNativePdfRetrievalClientTest.Wire legacy=new OerNativePdfRetrievalClientTest.Wire();legacy.index();
        ((ObjectNode)legacy.response.path("sourceUnits")).put("policy",VettingRetrievalSourceUnits.TYPED_PARENT_POLICY);assertThrows(IllegalStateException.class,legacy::query);
    }
}
