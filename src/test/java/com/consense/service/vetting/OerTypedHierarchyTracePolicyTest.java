package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Producer episode policy, rather than context presence, labels business diagnostics. */
class OerTypedHierarchyTracePolicyTest {
    @TestFactory Collection<DynamicTest> canonicalV1AndV2WithoutParentContextRetainActualEpisodePolicy() {
        List<DynamicTest> tests=new ArrayList<>();
        for(String policy:Arrays.asList(VettingRetrievalSourceUnits.POLICY,VettingRetrievalSourceUnits.TYPED_PARENT_POLICY)) {
            tests.add(DynamicTest.dynamicTest(policy+"_no_parent_context",()->{
                OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();
                ((ObjectNode)w.response.path("sourceUnits")).put("policy",policy);
                assertFalse(w.response.path("sourceUnits").path("units").get(0).has("hierarchyContext"));
                OerBusinessEvidence.Session session=w.load();
                List<Chunk> ranked=w.service.retrieve(session,"diagnostic source-only query",null);
                assertPolicyAndOriginalTransport(session,ranked,policy,false);
            }));
        }
        return tests;
    }

    @Test void actualTypedParentContextRetainsV2PolicyThroughRequestDiagnostics() throws Exception {
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();
        OerBusinessEvidence.Session session=w.load("vetting");
        List<Chunk> ranked=w.service.retrieve(session,"source-only trace policy replay",null);
        assertPolicyAndOriginalTransport(session,ranked,VettingRetrievalSourceUnits.TYPED_PARENT_POLICY,true);
    }

    private void assertPolicyAndOriginalTransport(OerBusinessEvidence.Session session,List<Chunk> ranked,String policy,boolean context) {
        assertEquals(1,ranked.size());
        List<VettingRetrievalSourceUnits.Unit> units=VettingRetrievalSourceUnits.attached(ranked);
        assertEquals(1,units.size());
        VettingRetrievalSourceUnits.Unit unit=units.get(0);
        assertEquals(policy,unit.trace.get("selectionPolicy"),"Trace must bind the actual validated episode policy");
        assertEquals(context,unit.trace.containsKey("hierarchyContext"));
        String before=JsonUtils.write(session.chunks());
        List<String> originalUnitIds=new ArrayList<>(unit.ids);
        VettingSourceRequestPacks.Result plan=new VettingSourceRequestPacks(session.chunks()).planForCompleteInputBudget(
                "source policy observation",Collections.emptyList(),new VettingContextBuilder.Selection(),units);
        VettingSourceRequestPacks.Request request=plan.getRequests().stream().filter(r->"retrieved_structural_unit".equals(r.getKind())).findFirst().get();
        assertEquals(policy,request.getOriginObservation().get("selectionPolicy"));
        assertEquals(policy,request.getSourceTrace().get("selectionPolicy"));
        // The existing planner transports members in corpus order, whereas
        // the v1 exact observed unit uses its historical lexical ID order.
        List<String> expectedRequestIds=session.chunks().stream().map(Chunk::getId).filter(unit.ids::contains).collect(Collectors.toList());
        assertEquals(expectedRequestIds,request.getRequiredChunkIds());
        assertEquals(originalUnitIds,unit.ids,"Diagnostic annotation altered attached source IDs");
        assertEquals(Collections.singletonList(unit.originId),request.getEligibleOriginIds());
        assertEquals(before,JsonUtils.write(session.chunks()),"Diagnostic annotation altered source payloads");
    }
}
