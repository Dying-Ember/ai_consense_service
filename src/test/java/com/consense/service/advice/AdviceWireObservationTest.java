package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Pure synthetic HTTP fixtures only: no provider, relay, model, database or GPU calls. */
class AdviceWireObservationTest {
    @TempDir Path root;
    AdviceResponsesTransportTest.Fixture fixture(){return new AdviceResponsesTransportTest.Fixture();}
    AdviceResponsesTransport.Operation operation(AdviceResponsesTransportTest.Fixture f){return new AdviceResponsesTransport(f.props,f.settings,f.http,new AdviceWireObservation(root.toAbsolutePath())).capture(f.selection);}
    List<Path> attempts()throws IOException{try(java.util.stream.Stream<Path> stream=Files.list(root)){return stream.filter(Files::isDirectory).sorted().collect(Collectors.toList());}}
    JsonNode json(Path directory,String file)throws IOException{return JsonUtils.parse(new String(Files.readAllBytes(directory.resolve(file)),StandardCharsets.UTF_8));}
    String text(Path directory,String file)throws IOException{return new String(Files.readAllBytes(directory.resolve(file)),StandardCharsets.UTF_8);}
    String withUsage(String output,String usage){return output.substring(0,output.length()-1)+",\"usage\":"+usage+"}";}
    @Test void preservesCompleteLiteralWireUnicodeCrossPageUnknownAndRawResponseBeforeParsing()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);
        String user="完整原题☃\n{\"hit\":\"Full text shall not be shortened\",\"continuesFrom\":19,\"continuedOn\":20,\"unknown\":null,\"quote\":\"a\\\"\\n\"}";
        AdviceResponsesTransport.PreparedRequest prepared=op.prepare("whole system",user,AdviceResponsesTransportTest.SOURCE,0);
        String raw=" \n"+withUsage(AdviceResponsesTransportTest.output("{\"grounded\":true,\"answer\":\"保留全文\"}"),"{\"input_tokens\":1000,\"output_tokens\":25,\"total_tokens\":1025,\"output_tokens_details\":{\"reasoning_tokens\":3}}")+"\r\n";
        f.http.counter(1000);f.http.replies.add(new AdviceResponsesTransport.Response(200,raw));
        AdviceResponsesTransport.BudgetResult budget=op.observe(prepared,()->{});op.generate(prepared,budget,()->{});Path d=attempts().get(0);
        assertArrayEquals(prepared.getGenerationJson().getBytes(StandardCharsets.UTF_8),Files.readAllBytes(d.resolve("generation-request.utf8.json")));
        assertArrayEquals(prepared.getCounterJson().getBytes(StandardCharsets.UTF_8),Files.readAllBytes(d.resolve("counter-request.utf8.json")));
        assertEquals(user,json(d,"generation-request.utf8.json").path("input").get(0).path("content").asText());assertEquals(raw,text(d,"generation-response.utf8.json"));
        JsonNode receipt=json(d,"request-receipt.json");assertEquals(prepared.getGenerationEntitySha256(),receipt.path("generationRequest").path("sha256").asText());
        assertEquals(prepared.getGenerationJson().getBytes(StandardCharsets.UTF_8).length,receipt.path("generationRequest").path("utf8Bytes").asInt());
        assertEquals(prepared.getBindingSha256(),receipt.path("bindingSha256").asText());assertEquals(10000,receipt.path("counterTimeoutMs").asLong());assertEquals(300000,receipt.path("generationTimeoutMs").asLong());
        assertEquals(1048576,receipt.path("counterMaxResponseBytes").asInt());assertEquals(4194304,receipt.path("generationMaxResponseBytes").asInt());assertEquals("none",receipt.path("reasoningEffort").asText());
        JsonNode usage=json(d,"generation-response-receipt.json").path("usageObservation");assertEquals("provider_reported_counts",usage.path("kind").asText());assertEquals(1000,usage.path("inputTokens").asLong());assertEquals(3,usage.path("parsedUsageProjection").path("output_tokens_details").path("reasoning_tokens").asInt());
        JsonNode b=json(d,"budget-receipt.json");assertEquals(17408+1000,b.path("inputOutputAndMarginTokens").asLong());assertEquals(32768,b.path("configuredContextTokens").asLong());assertFalse(b.path("exactTemplateObserved").asBoolean());
        assertEquals(2,f.http.bodies.size());
    }
    @Test void disabledObservationHasNoFileOrWireChange()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=f.operation();
        AdviceResponsesTransport.PreparedRequest r=op.prepare("s","u",AdviceResponsesTransportTest.SOURCE,0);f.http.counter(1);f.http.generation("{}");op.generate(r,op.observe(r,()->{}),()->{});
        assertTrue(attempts().isEmpty());assertEquals(Arrays.asList(r.getCounterJson(),r.getGenerationJson()),f.http.bodies);assertNull(f.settings.getWireObservationRoot());
    }
    @Test void retriesKeepTwoDifferentFullRequestsRawEntitiesAndBoundBudgets()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);f.http.counter(1000);f.http.generation("not JSON");f.http.counter(1005);f.http.generation("{\"grounded\":true,\"answer\":\"ok\"}");
        AdviceResponsesTransport.Completion<AdviceResponsesTransportTest.Answer> result=op.completeJson("s","whole source",AdviceResponsesTransportTest.SOURCE,AdviceResponsesTransportTest.Answer.class,()->{});
        assertEquals(2,result.budgets.size());List<Path> directories=attempts();assertEquals(2,directories.size());Set<String> bindings=new HashSet<>();Set<Integer> numbers=new HashSet<>();
        for(Path d:directories){JsonNode receipt=json(d,"request-receipt.json");int number=receipt.path("attempt").asInt();numbers.add(number);bindings.add(receipt.path("bindingSha256").asText());assertEquals(number==0?"whole source":"whole source"+AdviceResponsesTransport.RETRY_INSTRUCTION,json(d,"generation-request.utf8.json").path("input").get(0).path("content").asText());assertTrue(Files.exists(d.resolve("generation-response.utf8.json")));}
        assertEquals(new HashSet<>(Arrays.asList(0,1)),numbers);assertEquals(2,bindings.size());assertEquals(4,f.http.bodies.size());
    }
    @Test void missingAndMalformedUsageRemainUnknownWithoutInventedCounts()throws Exception{
        for(String usage:Arrays.asList("null","[]","{\"input_tokens\":true,\"output_tokens\":2}","{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":99}","{\"input_tokens\":-1,\"output_tokens\":2}")){
            AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);AdviceResponsesTransport.PreparedRequest r=op.prepare("s","u",AdviceResponsesTransportTest.SOURCE,0);f.http.counter(1);f.http.replies.add(new AdviceResponsesTransport.Response(200,withUsage(AdviceResponsesTransportTest.output("{}"),usage)));op.generate(r,op.observe(r,()->{}),()->{});
        }
        for(Path d:attempts()){JsonNode usage=json(d,"generation-response-receipt.json").path("usageObservation");assertEquals("unknown",usage.path("kind").asText());assertFalse(usage.has("inputTokens"));}
    }
    @Test void unknownPolicyAndOverBudgetRetainCompleteWireButNeverGenerate()throws Exception{
        AdviceResponsesTransportTest.Fixture unknown=fixture();unknown.settings.getResponses().setConfiguredContextTokens(0);AdviceResponsesTransport.Operation a=operation(unknown);AdviceResponsesTransport.PreparedRequest ra=a.prepare("s","whole unknown source",AdviceResponsesTransportTest.SOURCE,0);assertFalse(a.observe(ra,()->{}).isGenerationPermitted());assertTrue(unknown.http.bodies.isEmpty());
        AdviceResponsesTransportTest.Fixture over=fixture();AdviceResponsesTransport.Operation b=operation(over);AdviceResponsesTransport.PreparedRequest rb=b.prepare("s","whole over budget source",AdviceResponsesTransportTest.SOURCE,0);over.http.counter(16000);AdviceResponsesTransport.BudgetResult result=b.observe(rb,()->{});assertFalse(result.isGenerationPermitted());assertThrows(AdviceResponsesTransport.RequestFailure.class,()->b.generate(rb,result,()->{}));assertEquals(1,over.http.bodies.size());
        for(Path d:attempts()){assertTrue(Files.exists(d.resolve("generation-request.utf8.json")));assertFalse(Files.exists(d.resolve("generation-response.utf8.json")));assertFalse(json(d,"budget-receipt.json").path("generationPermitted").asBoolean());}
    }
    @Test void sourceChangeAfterCounterKeepsRawButNeverMarksBudgetAccepted()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);AdviceResponsesTransport.PreparedRequest r=op.prepare("s","u",AdviceResponsesTransportTest.SOURCE,0);f.http.counter(1);AtomicInteger checks=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->op.observe(r,()->{if(checks.incrementAndGet()==2)throw new IllegalStateException();}));Path d=attempts().get(0);assertTrue(Files.exists(d.resolve("counter-response.utf8.json")));assertFalse(Files.exists(d.resolve("budget-receipt.json")));assertEquals("failed_before_budget_acceptance",json(d,"counter-outcome.json").path("status").asText());assertEquals(1,f.http.bodies.size());
    }
    @Test void safeHttpFailureKeepsUnchangedRawStatusAndNoGeneration()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);AdviceResponsesTransport.PreparedRequest r=op.prepare("s","u",AdviceResponsesTransportTest.SOURCE,0);String raw="{\"error\":\"overloaded\"}";f.http.replies.add(new AdviceResponsesTransport.Response(529,raw));
        AdviceResponsesTransport.RequestFailure error=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->op.observe(r,()->{}));assertEquals(Integer.valueOf(529),error.getHttpStatus());Path d=attempts().get(0);assertEquals(raw,text(d,"counter-response.utf8.json"));assertEquals(529,json(d,"counter-response-receipt.json").path("httpStatus").asInt());assertEquals(1,f.http.bodies.size());
    }
    @Test void knownCredentialInRequestRefusesBeforeNetworkOrArtifact()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);AdviceResponsesTransport.RequestFailure error=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->op.prepare("s",AdviceResponsesTransportTest.SECRET,AdviceResponsesTransportTest.SOURCE,0));assertEquals("observation_failed",error.getStatus());assertFalse(error.getMessage().contains(AdviceResponsesTransportTest.SECRET));assertTrue(f.http.bodies.isEmpty());assertTrue(attempts().isEmpty());
    }
    @Test void reflectedCredentialResponseIsRefusedWithoutRawArtifactOrReplay()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);AdviceResponsesTransport.PreparedRequest r=op.prepare("s","u",AdviceResponsesTransportTest.SOURCE,0);f.http.replies.add(new AdviceResponsesTransport.Response(529,AdviceResponsesTransportTest.SECRET));
        AdviceResponsesTransport.RequestFailure error=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->op.observe(r,()->{}));assertEquals("observation_failed",error.getStatus());Path d=attempts().get(0);assertFalse(Files.exists(d.resolve("counter-response.utf8.json")));try(java.util.stream.Stream<Path> stream=Files.walk(d)){for(Path p:stream.filter(Files::isRegularFile).collect(Collectors.toList()))assertFalse(text(p.getParent(),p.getFileName().toString()).contains(AdviceResponsesTransportTest.SECRET));}assertEquals(1,f.http.bodies.size());
    }
    @Test void failedRequestWriteStopsBeforeHttpAndNeverTriggersJsonRetry()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);Files.delete(root);
        AdviceResponsesTransport.RequestFailure error=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->op.completeJson("s","u",AdviceResponsesTransportTest.SOURCE,AdviceResponsesTransportTest.Answer.class,()->{}));assertEquals("observation_failed",error.getStatus());assertTrue(f.http.bodies.isEmpty());
    }
    @Test void injectedTransportNeverClaimsDirectProviderRaw()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);AdviceResponsesTransport.PreparedRequest r=op.prepare("s","u",AdviceResponsesTransportTest.SOURCE,0);f.http.counter(1);op.observe(r,()->{});JsonNode receipt=json(attempts().get(0),"counter-response-receipt.json");assertFalse(receipt.path("providerRawObserved").asBoolean());assertEquals("upstream_provider_entity_unobserved",receipt.path("providerRawScope").asText());
    }
    @Test void simulatedNativeBytesAreImmutableAndGatewayStillCannotClaimProviderRaw()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.PreparedRequest r=f.operation().prepare("s","u",AdviceResponsesTransportTest.SOURCE,0);AdviceWireObservation.Attempt a=new AdviceWireObservation(root.toAbsolutePath()).begin(r,Collections.emptyMap(),AdviceResponsesTransportTest.SECRET,true);
        byte[] raw=" \n{\"usage\":null}\r\n".getBytes(StandardCharsets.UTF_8);AdviceResponsesTransport.Response response=new AdviceResponsesTransport.Response(200,new String(raw,StandardCharsets.UTF_8),raw);raw[0]='x';
        a.response("counter","http://127.0.0.1:18082/v1/responses/input_tokens",response,1);Path d=attempts().get(0);assertEquals(' ',Files.readAllBytes(d.resolve("counter-response.utf8.json"))[0]);assertFalse(json(d,"counter-response-receipt.json").path("providerRawObserved").asBoolean());assertEquals("native_http_entity_bytes",json(d,"counter-response-receipt.json").path("responseEntityProvenance").asText());
    }
    @Test void invalidConfiguredRootDoesNotAffectDisabledModeButOptInFailsSafely(){AdviceModelProperties settings=new AdviceModelProperties();assertNull(AdviceWireObservation.configured(settings));settings.setWireObservationRoot("relative-root");assertThrows(AdviceResponsesTransport.RequestFailure.class,()->AdviceWireObservation.configured(settings));}
    @Test void unicodeEscapedCredentialIsRefusedBeforeRawPersistence()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.Operation op=operation(f);AdviceResponsesTransport.PreparedRequest r=op.prepare("s","u",AdviceResponsesTransportTest.SOURCE,0);StringBuilder escaped=new StringBuilder();for(char c:AdviceResponsesTransportTest.SECRET.toCharArray())escaped.append(String.format("\\u%04x",(int)c));
        f.http.replies.add(new AdviceResponsesTransport.Response(529,"{\"message\":\""+escaped+"\"}"));AdviceResponsesTransport.RequestFailure failure=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->op.observe(r,()->{}));assertEquals("credential_reflection_refused_no_raw_body_saved",failure.getReason());assertFalse(Files.exists(attempts().get(0).resolve("counter-response.utf8.json")));assertEquals(1,f.http.bodies.size());
    }
    @Test void simulatedDirectBoundaryRequiresBothNativeTransportAndExactNativeEntityBytes()throws Exception{
        AdviceResponsesTransportTest.Fixture f=fixture();AdviceResponsesTransport.PreparedRequest r=f.operation().prepare("s","u",AdviceResponsesTransportTest.SOURCE,0);AdviceWireObservation observer=new AdviceWireObservation(root.toAbsolutePath());byte[] bytes="{\"usage\":null}".getBytes(StandardCharsets.UTF_8);
        AdviceWireObservation.Attempt direct=observer.begin(r,Collections.emptyMap(),AdviceResponsesTransportTest.SECRET,true);direct.response("counter","https://api.minimax.cn/v1/responses/input_tokens",new AdviceResponsesTransport.Response(200,new String(bytes,StandardCharsets.UTF_8),bytes),1);
        assertTrue(json(direct.directory,"counter-response-receipt.json").path("providerRawObserved").asBoolean());
        AdviceWireObservation.Attempt bodyOnly=observer.begin(r,Collections.emptyMap(),AdviceResponsesTransportTest.SECRET,true);bodyOnly.response("counter","https://api.minimax.cn/v1/responses/input_tokens",new AdviceResponsesTransport.Response(200,new String(bytes,StandardCharsets.UTF_8)),1);assertFalse(json(bodyOnly.directory,"counter-response-receipt.json").path("providerRawObserved").asBoolean());
    }
}
