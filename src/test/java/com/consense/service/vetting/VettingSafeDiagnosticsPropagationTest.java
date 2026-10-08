package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSourceRequestPacks.Pack;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VettingSafeDiagnosticsPropagationTest {
    static final Set<String> SAFE=new HashSet<>(Arrays.asList("phase","category","exceptionClass","wallNanos","responseStatus","bytesRead","rootCauseStatus"));
    static JsonNode diagnostics(VettingInputBudget.Result r) {return JsonUtils.parse(JsonUtils.write(r)).get("failureDiagnostics");}
    static void safe(JsonNode d) {
        assertNotNull(d);Set<String> names=new HashSet<>();d.fieldNames().forEachRemaining(names::add);assertEquals(SAFE,names);assertEquals("unknown",d.path("rootCauseStatus").textValue());assertTrue(d.path("wallNanos").longValue()>0);
        String json=JsonUtils.write(d);for(String secret:Arrays.asList("SYNTHETIC_PRIVATE_ORIGINAL_MESSAGE","127.0.0.1","api.minimax.cn","Authorization","Bearer","\"message\":","\"cause\":","\"headers\":","\"requestBody\":","\"stackTrace\":"))assertFalse(json.contains(secret),secret);
    }
    @ParameterizedTest @ValueSource(strings={"noObserver","ordinaryIllegalState","nestedTypedCause","ordinaryIllegalArgument","nullObservation","fitObservation","overObservation","providerFit","providerOver","providerHttpFailure"})
    void ordinaryResultsRemainExactAgainstActualFrozen731Serialization(String name)throws Exception {
        byte[] bytes;try(java.io.InputStream in=getClass().getResourceAsStream("/safe-diagnostics/legacy-results-731.json")){assertNotNull(in);bytes=readAll(in);}
        JsonNode original=JsonUtils.parse(new String(bytes,StandardCharsets.UTF_8));String expected=JsonUtils.write(original.get(name));String actual=JsonUtils.write(SafeDiagnosticsFixtureSupport.legacyResults().get(name));
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));assertFalse(actual.contains("failureDiagnostics"));
    }
    private static byte[] readAll(java.io.InputStream in)throws Exception {java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[1024];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}
    @Test void directNativeFailureSurvivesRealCheckAndResultCopyWithoutGrantingGeneration() {
        VettingResponsesTransport.TypedTransportException failure=SafeDiagnosticsFixtureSupport.typed("response_body",200,3);
        VettingInputBudget.Result r=VettingInputBudget.check(SafeDiagnosticsFixtureSupport.input(),in->{throw failure;});
        assertEquals("budget_unknown",r.getStatus());assertEquals("token_observer_failed_IllegalStateException",r.getReason());assertFalse(r.isExtraDispatchPermitted());assertNull(r.getObservation());
        JsonNode d=diagnostics(r);safe(d);assertEquals("response_body",d.path("phase").textValue());assertEquals(200,d.path("responseStatus").intValue());assertEquals(3,d.path("bytesRead").longValue());
        VettingInputBudget.Result copy=JsonUtils.read(JsonUtils.write(r),VettingInputBudget.Result.class);assertEquals(JsonUtils.write(r),JsonUtils.write(copy));safe(diagnostics(copy));
    }
    @Test void failureAtUnobservedPhaseKeepsNullableStatusAndZeroBytes() {
        VettingInputBudget.Result r=VettingInputBudget.check(SafeDiagnosticsFixtureSupport.input(),in->{throw SafeDiagnosticsFixtureSupport.typed("dns",null,0);});JsonNode d=diagnostics(r);safe(d);assertTrue(d.get("responseStatus").isNull());assertEquals(0,d.path("bytesRead").longValue());assertEquals("dns",d.path("phase").textValue());
    }
    @Test void previewMemoReturnsSafeIndependentCopiesAndNeverCallsObserverTwice()throws Exception {
        Chunk c=SafeDiagnosticsFixtureSupport.chunk("seed","The shipment ledger shall be retained.");
        VettingTokenAwareRequestPacks planner=new VettingTokenAwareRequestPacks(Collections.singletonList(c));Pack p=new Pack();p.setIndex(0);p.setChunks(Collections.singletonList(c));p.setContentChars(c.getContent().length());
        Class<?> memoClass=Class.forName("com.consense.service.vetting.VettingTokenAwareRequestPacks$PreviewMemo");Constructor<?> constructor=memoClass.getDeclaredConstructor();constructor.setAccessible(true);Object memo=constructor.newInstance();
        Method method=VettingTokenAwareRequestPacks.class.getDeclaredMethod("observe",Pack.class,VettingTokenAwareRequestPacks.InputFactory.class,VettingInputBudget.Observer.class,VettingTokenAwareRequestPacks.Plan.class,memoClass);method.setAccessible(true);
        VettingTokenAwareRequestPacks.Plan plan=new VettingTokenAwareRequestPacks.Plan();AtomicInteger calls=new AtomicInteger();VettingResponsesTransport.TypedTransportException failure=SafeDiagnosticsFixtureSupport.typed("response_headers",null,0);
        VettingTokenAwareRequestPacks.InputFactory factory=pack->VettingInputBudget.input("synthetic","SYNTHETIC_MODEL","Synthetic system",VettingSourcePromptWire.write(VettingSourceMaterial.project(pack.getChunks())),VettingOutputSchema.forChunkIds(Collections.singletonList("seed")),64,VettingCorpus.hash(JsonUtils.write(pack.getChunks())));
        VettingInputBudget.Observer observer=in->{calls.incrementAndGet();throw failure;};
        VettingInputBudget.Result first=(VettingInputBudget.Result)method.invoke(planner,p,factory,observer,plan,memo);safe(diagnostics(first));
        // Mutate the returned caller result: the internal memo must retain its own safe metadata copy.
        String expected=JsonUtils.write(first);Field field=VettingInputBudget.Result.class.getDeclaredField("failureDiagnostics");field.setAccessible(true);((Map<String,Object>)field.get(first)).put("bytesRead",999);
        assertFalse(expected.equals(JsonUtils.write(first)));
        VettingInputBudget.Result second=(VettingInputBudget.Result)method.invoke(planner,p,factory,observer,plan,memo);assertEquals(expected,JsonUtils.write(second));safe(diagnostics(second));assertEquals(0,diagnostics(second).path("bytesRead").longValue());assertEquals(1,calls.get());assertEquals(1,plan.getActualPreviewObserverCalls());assertEquals(1,plan.getPreviewCacheHits());assertFalse(second.isExtraDispatchPermitted());
    }
}
