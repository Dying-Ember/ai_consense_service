package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Author differential contracts only; actual source readback, no model relevance claim. */
class OerSharedNoteAuthorDifferentialTest {
    static final String NEW_POLICY="unique-source-structural-unit-seeds-typed-parent-shared-note-context-v4";
    static final String NEW_ATTACHMENT="same-source-reviewed-direct-shared-note-star-context-v2";
    static final String NEW_VERSION="observed-structure-dual-granularity-typed-parent-shared-note-context-v4";
    static final String NEW_UNION="independent-observed-hierarchy-and-direct-shared-note-union-v2";
    static JsonNode fixture() throws Exception {
        String path=System.getenv("CONSENSE_SHARED_NOTE_FIXTURE");assertNotNull(path,"Explicit owned fixture path required");
        byte[] raw=Files.readAllBytes(Path.of(path));
        assertEquals("cda3a7a07579fb7bd0a2e3cd39e20b7daac9f3c21b1c36ac95c018f17dfb4633",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        return JsonUtils.parse(new String(raw,StandardCharsets.UTF_8));
    }
    static Map<String,JsonNode> parents(JsonNode dataset) {
        Map<String,JsonNode> out=new LinkedHashMap<>();dataset.path("parents").forEach(p->assertNull(out.put(p.path("id").asText(),p)));return out;
    }
    static JsonNode transport(JsonNode unit,Map<String,JsonNode> parents)throws Exception {
        Method method=OerBusinessEvidence.class.getDeclaredMethod("transportUnit",JsonNode.class,Map.class);method.setAccessible(true);return (JsonNode)method.invoke(null,unit,parents);
    }
    @TestFactory Collection<DynamicTest> allActualAndAuthoredNewPolicyEpisodesMatchPython()throws Exception {
        List<DynamicTest> tests=new ArrayList<>();
        for(JsonNode dataset:fixture().path("datasets"))for(JsonNode row:dataset.path("cases"))tests.add(DynamicTest.dynamicTest(dataset.path("datasetId").asText()+"/"+row.path("seedId").asText(),()->{
            Map<String,JsonNode> corpus=parents(dataset);String seed=row.path("seedId").asText();
            OerCanonicalSourceUnits.validate(row.path("response"),corpus,Collections.singletonList(seed));
            JsonNode actual=transport(row.path("expectedResolved"),corpus),expected=row.path("expectedStructuralContext");
            if(!expected.isNull()) {
                assertEquals(expected.path("requiredMemberIds"),actual.path("requiredMemberIds"));
                assertEquals(expected.path("observedRanges"),actual.path("observedRanges"));
                assertEquals(row.path("expectedStructuralBinding").path("contextId"),actual.path("contextId"));
                if(expected.has("combinedContextPolicy"))assertEquals(NEW_UNION,actual.path("policy").asText());
            }else assertEquals("unknown",actual.path("status").asText());
            assertFalse(actual.path("legalClosureVerified").asBoolean());
            assertEquals("unknown",actual.path("qualifiersComplete").asText());
        }));
        assertEquals(33,tests.size());return tests;
    }
    @Test void oldV3MultipleTargetsRemainUnknownAndCannotMasqueradeAsV4()throws Exception {
        JsonNode dataset=fixture().path("datasets").get(0),row=dataset.path("cases").get(0);Map<String,JsonNode> corpus=parents(dataset);
        assertEquals("unknown",OerCanonicalSourceUnits.resolveAttached(corpus,corpus.get(row.path("seedId").asText())).path("attachmentContext").path("status").asText());
        ObjectNode changed=row.path("response").deepCopy();((ObjectNode)changed.path("sourceUnits")).put("policy",VettingRetrievalSourceUnits.ATTACHMENT_POLICY);
        assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(changed,corpus,Collections.singletonList(row.path("seedId").asText())));
    }
    @Test void fullStarCannotLoseMemberOriginalPartEdgeUnknownOrAddress()throws Exception {
        JsonNode dataset=fixture().path("datasets").get(0),row=dataset.path("cases").get(0);Map<String,JsonNode> corpus=parents(dataset);
        for(String mutation:Arrays.asList("member","part","edge","contextId","qualifiers","legal","semantic","dropContext")) {
            ObjectNode changed=row.path("response").deepCopy(),unit=(ObjectNode)changed.path("sourceUnits").path("units").get(0);
            ObjectNode context=(ObjectNode)unit.path("attachmentContext");
            if(mutation.equals("member"))context.withArray("members").remove(1);
            if(mutation.equals("part"))((ObjectNode)context.path("members").get(1)).putArray("parts");
            if(mutation.equals("edge"))context.withArray("edgeDeclarations").remove(1);
            if(mutation.equals("contextId"))context.put("contextId","f".repeat(64));
            if(mutation.equals("qualifiers"))context.put("qualifiersComplete","complete");
            if(mutation.equals("legal"))context.put("legalClosureVerified",true);
            if(mutation.equals("semantic"))context.put("semanticScopeVerified",true);
            if(mutation.equals("dropContext"))unit.remove("attachmentContext");
            assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(changed,corpus,Collections.singletonList(row.path("seedId").asText())),mutation);
        }
    }
    static ObjectNode newHealth()throws Exception {
        ObjectNode h=OerAttachmentHealthTest.authoredHealth(),window=(ObjectNode)h.path("retrieval").path("sourceWindows");
        ((ObjectNode)window.path("structuralRecipe")).put("version",NEW_VERSION);
        ObjectNode selection=(ObjectNode)window.path("structuralSelection");selection.put("policy",NEW_POLICY);selection.put("attachmentContextPolicy",NEW_ATTACHMENT);return h;
    }
    @Test void exactNewHealthVersionAndCapabilitiesRequiredWithoutChangingCaps()throws Exception {
        VettingRetrievalHealth.Identity accepted=VettingRetrievalHealth.validate(newHealth(),OerAttachmentHealthTest.expected().getVetting());assertEquals(NEW_POLICY,accepted.sourceUnitPolicy);
        for(String mutation:Arrays.asList("oldVersion","oldContext","missingContext","unknownPolicy","oldSelection","longCap","strict")) {
            ObjectNode h=newHealth(),window=(ObjectNode)h.path("retrieval").path("sourceWindows"),selection=(ObjectNode)window.path("structuralSelection"),recipe=(ObjectNode)window.path("structuralRecipe");
            if(mutation.equals("oldVersion"))recipe.put("version",VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_VERSION);
            if(mutation.equals("oldContext"))selection.put("attachmentContextPolicy",OerCanonicalAttachmentContext.POLICY);
            if(mutation.equals("missingContext"))selection.remove("attachmentContextPolicy");
            if(mutation.equals("unknownPolicy"))selection.put("policy","invented-v5");
            if(mutation.equals("oldSelection"))selection.put("policy",VettingRetrievalSourceUnits.ATTACHMENT_POLICY);
            if(mutation.equals("longCap"))recipe.put("context_rerank_tokens",4096);
            if(mutation.equals("strict"))recipe.put("strict_context",false);
            assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,OerAttachmentHealthTest.expected().getVetting()),mutation);
        }
        assertNotNull(VettingRetrievalHealth.validate(OerAttachmentHealthTest.authoredHealth(),OerAttachmentHealthTest.expected().getVetting()));
    }
}
