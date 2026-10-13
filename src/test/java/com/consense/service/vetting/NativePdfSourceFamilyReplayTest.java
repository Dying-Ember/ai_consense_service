package com.consense.service.vetting;
import com.consense.common.JsonUtils;
import com.consense.service.sources.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
/** Actual saved public-wire replay is explicitly local; no original/CAS/model read. */
@EnabledIfEnvironmentVariable(named="CONSENSE_OER_FAMILY_REPLAY_ROOT", matches=".+")
class NativePdfSourceFamilyReplayTest {
    static String raw(String name)throws Exception {
        Map<String,String> pins=Map.of("actual-v1-50-public.json","8a42f9fb3176ccc51bf6aa78709518c13236e0b72fc7fb3762ff8fd3e891d452","actual-v2-4-public.json","8b1402abd61d8c1e70a525c53994793ee51e008af18c6701fc99a2e8ee1f0c55","actual-family-54-public.json","8f332d77fc89a721d931086d92ccf1766b7e7674567c519511fa8bedc1295fa0","actual-family-54-python-units-summary.json","4568a5c540056416a3900923214c8cdbb74ffaef8df20a7227f9ad69854e1f3a");
        assertTrue(pins.containsKey(name), "Unsupported local replay file");
        Path root=Path.of(System.getenv("CONSENSE_OER_FAMILY_REPLAY_ROOT")).toRealPath();
        Path path=root.resolve(name).toRealPath();
        assertEquals(root,path.getParent(),"Replay file must stay in the supplied local directory");
        assertTrue(Files.size(path)<=NativePdfCanonicalContract.MAX_BYTES,"Bounded public wire required");
        byte[] bytes=Files.readAllBytes(path);
        assertEquals(pins.get(name),String.format("%064x",new BigInteger(1,MessageDigest.getInstance("SHA-256").digest(bytes))),"Saved replay bytes differ from the reviewed run");
        return new String(bytes,StandardCharsets.UTF_8);
    }
    static JsonNode snapshot(String name)throws Exception{return JsonUtils.parse(raw(name));}
    @Test void savedActualV1FiftyAndV2FourAndFamilyFiftyFourKeepExactTypedIdentity()throws Exception{
        for(String name:new String[]{"actual-v1-50-public.json","actual-v2-4-public.json","actual-family-54-public.json"}){
            String raw=raw(name);JsonNode root=JsonUtils.parse(raw);
            assertEquals(root,CanonicalEvidenceService.validateSnapshot(raw,root.path("projectionSha256").asText()),name);
            assertEquals(name.startsWith("actual-family")?54:name.contains("v2")?4:50,root.path("chunks").size());
        }
    }
    @Test void fullActualFiftyFourParentsLoadForBothModulesWithUntouchedLiteralAndOrigin()throws Exception{
        for(String module:new String[]{"advisory","vetting"}){
            OerNativePdfFamilyBusinessTest.Wire w=new OerNativePdfFamilyBusinessTest.Wire(raw("actual-family-54-public.json"),true);
            OerBusinessEvidence.Session session=w.load(module);assertEquals(54,session.chunkCount());
            for(int i=0;i<54;i++){
                VettingCorpus.Chunk view=session.chunks().get(i);
                assertEquals(w.root.path("chunks").get(i),view.getCanonicalPayload());
                assertNull(OerBusinessEvidence.canonicalViewStatus(view));
                assertEquals(view.getCanonicalPayload().path("sourceFamilyOriginRef"),view.getCanonicalEvidence().getSourceFamilyOriginRef());
            }
        }
    }
    @Test void allFiftyFourSourceUnitContextsMatchFrozenPythonSummariesInOrder()throws Exception{
        JsonNode root=snapshot("actual-family-54-public.json"),expected=snapshot("actual-family-54-python-units-summary.json");
        Map<String,JsonNode> parents=new LinkedHashMap<>();root.path("chunks").forEach(p->parents.put(p.path("id").asText(),p));int i=0;
        for(JsonNode p:parents.values()){
            JsonNode actual=OerCanonicalSourceUnits.resolveAttached(parents,p,true),wanted=expected.get(i++);
            for(String f:new String[]{"originIds","unitId","status","reason","requiredMemberIds","blockIds","observedRanges"})assertEquals(wanted.path(f),actual.path(f),p.path("id")+" "+f);
            for(String context:new String[]{"hierarchyContext","attachmentContext"})if(wanted.has(context))
                for(String f:new String[]{"policy","status","reason","contextId","requiredMemberIds","memberPayloadSha256","edgeDeclarations"})assertEquals(wanted.path(context).path(f),actual.path(context).path(f),p.path("id")+" "+context+" "+f);
        }assertEquals(54,i);
    }
}
