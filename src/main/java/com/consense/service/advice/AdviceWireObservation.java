package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Advice-only opt-in HTTP entity recorder. No headers, keys, frontend sources or evaluator inputs. */
final class AdviceWireObservation {
    private final Path root;
    AdviceWireObservation(Path destination) {
        try {
            if(destination==null||!destination.isAbsolute())throw new IOException();
            Path normalized=destination.normalize();
            if(!Files.isDirectory(normalized,LinkOption.NOFOLLOW_LINKS))throw new IOException();
            for(Path component=normalized;component!=null;component=component.getParent())if(Files.isSymbolicLink(component))throw new IOException();
            // Windows 8.3 spellings may differ from the actual directory without being a symlink.
            root=normalized.toRealPath();
        } catch(IOException error){throw failed("owned_absolute_observation_root_required");}
    }
    static AdviceWireObservation configured(AdviceModelProperties settings) {
        String configured=settings.getWireObservationRoot();
        if(configured==null||configured.trim().isEmpty())return null;
        try{return new AdviceWireObservation(Paths.get(configured));}
        catch(RuntimeException error){throw failed("owned_absolute_observation_root_required");}
    }
    Attempt begin(AdviceResponsesTransport.PreparedRequest request,Map<String,Object> configuration,String secret,boolean nativeHttp) {
        byte[] generation=request.getGenerationJson().getBytes(StandardCharsets.UTF_8),counter=request.getCounterJson().getBytes(StandardCharsets.UTF_8);
        rejectSecret(generation,secret);rejectSecret(counter,secret);
        try {
            Path directory=Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
            Attempt attempt=new Attempt(directory,secret,nativeHttp);
            Map<String,Object> metadata=new LinkedHashMap<>(configuration);
            metadata.put("schema","consense-advice-http-entity-observation-v1");metadata.put("module","advice");
            metadata.put("frontendSourcesIncludedAsSeparateArtifact",false);metadata.put("sourceSnapshotSha256",request.getSourceSnapshotSha256());
            metadata.put("profileSha256",request.getProfileSha256());metadata.put("contextPolicySha256",request.getContextPolicySha256());
            metadata.put("bindingSha256",request.getBindingSha256());metadata.put("attempt",request.getAttempt());
            metadata.put("counterRequest",attempt.entity("counter-request.utf8.json",counter));
            metadata.put("generationRequest",attempt.entity("generation-request.utf8.json",generation));
            metadata.put("httpEntityScope","UTF8 JSON HTTP entity; not TCP/TLS/framing bytes");
            metadata.put("providerMaximumMeasured",false);metadata.put("sourceFreshnessAtPrepare","not_yet_checked");
            attempt.json("request-receipt.json",metadata);return attempt;
        } catch(IOException error){throw failed("observation_request_seal_failed");}
    }
    static final class Attempt {
        final Path directory;private final String secret;private final boolean nativeHttp;
        Attempt(Path d,String s,boolean n){directory=d;secret=s;nativeHttp=n;}
        void response(String phase,String endpoint,AdviceResponsesTransport.Response response,long elapsedNanos) {
            byte[] bytes=response.entityBytes();rejectSecret(bytes,secret);
            Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("phase",phase);metadata.put("httpStatus",response.status);
            metadata.put("endpoint",safeEndpoint(endpoint));metadata.put("elapsedNanos",elapsedNanos);
            metadata.put("response",entity(phase+"-response.utf8.json",bytes));
            metadata.put("transport",nativeHttp?"native_http_no_proxy_no_redirect_no_retry":"injected_wire_http_not_independently_observed");
            metadata.put("responseEntityProvenance",response.hasNativeEntityBytes()?"native_http_entity_bytes":"synthetic_or_injected_utf8_body");
            boolean direct=nativeHttp&&response.hasNativeEntityBytes()&&officialEndpoint(endpoint);
            metadata.put("providerRawObserved",direct);
            metadata.put("providerRawScope",direct?"official_direct_decoded_http_entity":"upstream_provider_entity_unobserved");
            metadata.put("sourceFreshnessAfterResponse","not_yet_checked");
            metadata.put("usageObservation",usage(bytes));json(phase+"-response-receipt.json",metadata);
        }
        void budget(AdviceResponsesTransport.BudgetResult budget) {
            Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("status",budget.getStatus());metadata.put("reason",budget.getReason());
            metadata.put("kind",budget.getKind());metadata.put("inputTokens",budget.getInputTokens()>0?budget.getInputTokens():null);
            metadata.put("counterResponseSha256",budget.getCounterResponseSha256());metadata.put("bindingSha256",budget.getBindingSha256());
            metadata.put("configuredContextTokens",budget.getConfiguredContextTokens());metadata.put("outputReserveTokens",budget.getOutputReserveTokens());
            metadata.put("safetyMarginTokens",budget.getSafetyMarginTokens());
            metadata.put("inputOutputAndMarginTokens",budget.getInputTokens()>0?budget.getInputTokens()+budget.getOutputReserveTokens()+budget.getSafetyMarginTokens():null);
            metadata.put("exactTemplateObserved",false);metadata.put("generationPermitted",budget.isGenerationPermitted());
            metadata.put("sourceFreshnessAtBudget",budget.getInputTokens()>0?"guard_passed_before_and_after_counter":"not_observed");
            json("budget-receipt.json",metadata);
        }
        void outcome(String phase,String status) {Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("phase",phase);metadata.put("status",status);json(phase+"-outcome.json",metadata);}
        Map<String,Object> entity(String file,byte[] bytes) {
            rejectSecret(bytes,secret);
            try{Files.write(directory.resolve(file),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
            catch(IOException error){throw failed("observation_entity_seal_failed");}
            Map<String,Object> result=new LinkedHashMap<>();result.put("file",file);result.put("utf8Bytes",bytes.length);result.put("sha256",sha(bytes));return result;
        }
        void json(String file,Object value){entity(file,JsonUtils.write(value).getBytes(StandardCharsets.UTF_8));}
    }
    /** Usage is a parsed projection; the response entity above is the unchanged raw evidence. */
    private static Map<String,Object> usage(byte[] bytes) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("kind","unknown");result.put("reason","usage_missing_or_unverified");
        try(JsonParser parser=JsonUtils.mapper().getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode root=JsonUtils.mapper().readTree(parser);
            if(root==null||!root.isObject()||parser.nextToken()!=null){result.put("reason","provider_envelope_invalid");return result;}
            JsonNode usage=root.get("usage");
            if(usage==null||usage.isNull())return result;
            result.put("parsedUsageProjection",usage);
            if(!usage.isObject()){result.put("reason","usage_not_object");return result;}
            JsonNode input=usage.get("input_tokens"),output=usage.get("output_tokens"),total=usage.get("total_tokens");
            if(!nonnegativeLong(input)||!nonnegativeLong(output)||(total!=null&&(!nonnegativeLong(total)||input.longValue()>Long.MAX_VALUE-output.longValue()||input.longValue()+output.longValue()!=total.longValue()))){result.put("reason","usage_counts_invalid");return result;}
            result.put("kind","provider_reported_counts");result.put("reason","usage_counts_validated_not_exact_template_measurement");
            result.put("inputTokens",input.longValue());result.put("outputTokens",output.longValue());if(total!=null)result.put("totalTokens",total.longValue());return result;
        } catch(Exception error){result.put("reason","provider_envelope_invalid");return result;}
    }
    private static boolean nonnegativeLong(JsonNode node){return node!=null&&node.isIntegralNumber()&&node.canConvertToLong()&&node.longValue()>=0;}
    private static boolean officialEndpoint(String endpoint){try{URI u=new URI(endpoint);return "https".equals(u.getScheme())&&"api.minimax.cn".equals(u.getHost())&&u.getPort()==-1&&u.getRawUserInfo()==null&&u.getRawQuery()==null&&u.getRawFragment()==null&&Arrays.asList("/v1/responses/input_tokens","/v1/responses").contains(u.getRawPath());}catch(Exception invalid){return false;}}
    private static String safeEndpoint(String endpoint){return officialEndpoint(endpoint)?endpoint:"non_official_endpoint_unreported";}
    private static void rejectSecret(byte[] bytes,String secret){
        if(secret==null||secret.isEmpty())return;
        String text=new String(bytes,StandardCharsets.UTF_8),escaped=JsonUtils.write(secret);
        if(text.contains(secret)||text.contains(escaped.substring(1,escaped.length()-1)))throw failed("credential_reflection_refused_no_raw_body_saved");
        // Read every decoded string, including overwritten fields and trailing JSON values.
        // The raw entity is never reduced to a tree or rewritten before persistence.
        try(JsonParser parser=JsonUtils.mapper().getFactory().createParser(bytes)) {
            JsonToken token;
            while((token=parser.nextToken())!=null) {
                if((token==JsonToken.FIELD_NAME||token==JsonToken.VALUE_STRING)&&parser.getText().contains(secret))
                    throw failed("credential_reflection_refused_no_raw_body_saved");
            }
        } catch(IOException invalidJson){/* Exact non-JSON responses remain observable. */}
    }
    static String sha(byte[] bytes){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format("%02x",b&255));return value.toString();}catch(Exception impossible){throw new IllegalStateException("SHA256 unavailable");}}
    private static AdviceResponsesTransport.RequestFailure failed(String reason){return new AdviceResponsesTransport.RequestFailure("observation_failed",reason,null);}
}
