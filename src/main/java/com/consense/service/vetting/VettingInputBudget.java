package com.consense.service.vetting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.*;

/** Bound observations of complete chat inputs. No token estimates, generation, or implicit model discovery. */
public final class VettingInputBudget {
    private VettingInputBudget() { }
    @FunctionalInterface public interface Observer { Observation observe(Input input); }
    @Data public static class Input {
        private String provider, model, providerConfigurationSha256, sourceSnapshotSha256, serializedInputSha256;
        private List<LlmClient.ChatTurn> messages;
        private JsonNode schema;
        private int outputReserveTokens;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private VettingResponsesTransport.WireIdentity wire;
    }
    @Data public static class Observation {
        private String model, serializedInputSha256, tokenizerIdentitySha256, chatTemplateIdentitySha256, effectiveContextIdentitySha256;
        private long inputTokens, effectiveContextTokens;
        private int outputReserveTokens;
        private boolean completeChatTemplateAndSchemaObserved;
        private String scope = "complete_serialized_messages_with_gateway_schema_envelope";
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private VettingResponsesTransport.EstimateIdentity providerEstimate;
    }
    @Data public static class Result {
        private String status = "budget_unknown", reason = "no_bound_token_observer", serializedInputSha256;
        private Observation observation;
        private boolean extraDispatchPermitted;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Map<String,Object> failureDiagnostics;
    }
    public static Input input(String provider,String model,String system,String user,JsonNode schema,int outputReserve,String sourceSha) {
        return input(provider,model,null,system,user,schema,outputReserve,sourceSha);
    }
    public static Input input(String provider,String model,String providerConfigurationSha,String system,String user,JsonNode schema,int outputReserve,String sourceSha) {
        Input in=new Input();in.setProvider(provider);in.setModel(model);in.setSourceSnapshotSha256(sourceSha);in.setOutputReserveTokens(outputReserve);
        in.setProviderConfigurationSha256(providerConfigurationSha);
        in.setSchema(schema.deepCopy());in.setMessages(Arrays.asList(LlmClient.ChatTurn.system(system+"\nReturn only a JSON array conforming to this schema:\n"+JsonUtils.write(schema)),LlmClient.ChatTurn.user(user)));
        in.setSerializedInputSha256(bindingHash(in));return in;
    }
    private static String bindingHash(Input in) {Map<String,Object> b=new LinkedHashMap<>();b.put("provider",in.getProvider());b.put("model",in.getModel());b.put("providerConfigurationSha256",in.getProviderConfigurationSha256());b.put("messages",in.getMessages());b.put("schema",in.getSchema());b.put("outputReserveTokens",in.getOutputReserveTokens());b.put("sourceSnapshotSha256",in.getSourceSnapshotSha256());if(in.getWire()!=null)b.put("wire",in.getWire());return VettingCorpus.hash(JsonUtils.write(b));}
    static void bindWire(Input input,VettingResponsesTransport.WireIdentity wire) {input.setWire(wire);input.setSerializedInputSha256(bindingHash(input));}
    static boolean validBinding(Input input) {return input!=null&&Objects.equals(bindingHash(input),input.getSerializedInputSha256());}
    /** Safe configuration identity: intentionally excludes credentials and never serializes endpoint/config values. */
    public static String providerConfigurationSha256(ConsenseProperties.Llm cfg) {
        return com.consense.ai.LlmProfiles.configurationSha256(cfg);
    }
    public static Result check(Input input,Observer observer) {
        Result result=new Result();result.setSerializedInputSha256(input.getSerializedInputSha256());
        if(observer==null)return result;
        String before=JsonUtils.write(input);Input supplied=copy(input);Observation observed;
        try { observed=observer.observe(supplied); }
        catch(Exception failure){
            if(failure instanceof VettingResponsesTransport.TypedTransportException) {
                // Preserve the former NativeHttp reason; only this direct, safe typed failure carries diagnostics.
                result.setReason("token_observer_failed_IllegalStateException");
                result.setFailureDiagnostics(new LinkedHashMap<>(((VettingResponsesTransport.TypedTransportException)failure).getDiagnosticMetadata()));
            } else result.setReason("token_observer_failed_"+failure.getClass().getSimpleName());
            return result;
        }
        if(!before.equals(JsonUtils.write(input))||!before.equals(JsonUtils.write(supplied))||!bindingHash(input).equals(input.getSerializedInputSha256())){result.setReason("token_input_binding_changed");return result;}
        if(observed==null){result.setReason("token_observation_missing");return result;}
        result.setObservation(JsonUtils.read(JsonUtils.write(observed),Observation.class));
        if(input.getWire()!=null) return VettingResponsesTransport.checkEstimate(input,observed,result);
        if(observed.getProviderEstimate()!=null){result.setReason("token_observation_wire_mismatch");return result;}
        if(!Objects.equals(input.getModel(),observed.getModel())||!Objects.equals(input.getSerializedInputSha256(),observed.getSerializedInputSha256())
                ||!sha(observed.getTokenizerIdentitySha256())||!sha(observed.getChatTemplateIdentitySha256())||!sha(observed.getEffectiveContextIdentitySha256())
                ||!observed.isCompleteChatTemplateAndSchemaObserved()||!"complete_serialized_messages_with_gateway_schema_envelope".equals(observed.getScope())
                ||observed.getInputTokens()<=0||observed.getEffectiveContextTokens()<=0||input.getOutputReserveTokens()<=0||observed.getOutputReserveTokens()!=input.getOutputReserveTokens()) {
            result.setReason("token_observation_identity_or_scope_unknown");return result;
        }
        if(observed.getInputTokens()>observed.getEffectiveContextTokens()-observed.getOutputReserveTokens()){result.setStatus("over_budget");result.setReason("input_and_output_reserve_exceed_effective_context");return result;}
        result.setStatus("observed_tokens");result.setReason(null);result.setExtraDispatchPermitted(true);return result;
    }
    private static Input copy(Input in) {
        Input out=new Input();out.setProvider(in.getProvider());out.setModel(in.getModel());out.setProviderConfigurationSha256(in.getProviderConfigurationSha256());out.setSourceSnapshotSha256(in.getSourceSnapshotSha256());out.setSerializedInputSha256(in.getSerializedInputSha256());out.setOutputReserveTokens(in.getOutputReserveTokens());out.setSchema(in.getSchema().deepCopy());
        List<LlmClient.ChatTurn> messages=new ArrayList<>();for(LlmClient.ChatTurn turn:in.getMessages())messages.add(new LlmClient.ChatTurn(turn.getRole(),turn.getContent()));out.setMessages(messages);if(in.getWire()!=null)out.setWire(JsonUtils.read(JsonUtils.write(in.getWire()),VettingResponsesTransport.WireIdentity.class));return out;
    }
    private static boolean sha(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
}
