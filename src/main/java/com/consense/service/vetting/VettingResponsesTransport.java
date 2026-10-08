package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.Proxy;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Vetting-only immutable Responses wire + provider estimate. No hidden-template/tokenizer claim. */
@Component
public final class VettingResponsesTransport {
    public static final String WIRE="minimax-responses-text-v1";
    public static final String COUNTER="minimax-responses-input-estimate-v1";
    public static final String KIND="provider_reported_estimate";
    public static final String SCOPE="complete_provider_wire_input_estimate_v1";
    public static final String POLICY="bound_provider_estimate_with_absolute_margin_v1";
    private final ConsenseProperties properties;
    private final WireHttp http;

    @Autowired public VettingResponsesTransport(ConsenseProperties properties) {this(properties,new NativeHttp());}
    public VettingResponsesTransport(ConsenseProperties properties,WireHttp http) {this.properties=Objects.requireNonNull(properties);this.http=Objects.requireNonNull(http);}
    public boolean enabled(){return properties.getVetting().getResponses().isEnabled();}
    public String model(){return properties.getVetting().getResponses().getModel();}

    @Data public static class WireIdentity {
        private String protocol, counterContract, profileSha256, generationEntitySha256, counterProjectionSha256, contextPolicySha256;
        private long configuredContextTokens, safetyMarginTokens;
        private String estimatePolicy, contextEvidenceScope;
    }
    @Data public static class EstimateIdentity {
        private String kind, wireProtocol, counterContract, profileSha256, generationEntitySha256, counterProjectionSha256, contextPolicySha256, counterResponseSha256;
        private String estimatePolicy, contextEvidenceScope, modelIdentityScope;
        private long configuredContextTokens, safetyMarginTokens;
        private boolean responseModelUnavailable;
    }
    public interface WireHttp { Response post(String endpoint,String body,long timeoutMs,int maxResponseBytes,String authorization); }
    public static final class Response {
        final int status; final String body;
        public Response(int status,String body){this.status=status;this.body=body;}
    }
    /** Safe observation of a transport failure; never retains the original exception/cause/message. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"cause","stackTrace","suppressed","message","localizedMessage"})
    public static final class TypedTransportException extends IllegalStateException {
        private final String phase,failureCategory,exceptionClassName;
        private final long wallNanos,bytesRead;
        private final Integer responseStatus;
        private TypedTransportException(CallState state,Exception failure) {
            super("Responses HTTP transport failed");
            phase=state.phase;failureCategory=category(failure);exceptionClassName=failure.getClass().getName();
            wallNanos=Math.max(0,System.nanoTime()-state.startedNanos);responseStatus=state.responseStatus;bytesRead=state.bytesRead;
        }
        public String getPhase(){return phase;}
        public String getFailureCategory(){return failureCategory;}
        public String getExceptionClassName(){return exceptionClassName;}
        public long getWallNanos(){return wallNanos;}
        public Integer getResponseStatus(){return responseStatus;}
        /** Bytes returned by the bounded response body stream, including an over-cap read; not socket wire bytes. */
        public long getBytesRead(){return bytesRead;}
        public String getRootCauseStatus(){return "unknown";}
        public Map<String,Object> getDiagnosticMetadata(){
            Map<String,Object> m=new LinkedHashMap<>();m.put("phase",phase);m.put("category",failureCategory);m.put("exceptionClass",exceptionClassName);m.put("wallNanos",wallNanos);m.put("responseStatus",responseStatus);m.put("bytesRead",bytesRead);m.put("rootCauseStatus","unknown");return m;
        }
        private static String category(Exception failure) {
            if(failure instanceof ResponseCapException)return "response_cap_exceeded";
            if(failure instanceof CharacterCodingException)return "invalid_utf8";
            if(failure instanceof java.net.SocketTimeoutException)return "timeout";
            if(failure instanceof InterruptedIOException)return "timeout_or_interrupted";
            if(failure instanceof IOException)return "io_failure";
            if(failure instanceof IllegalArgumentException)return "configuration_failure";
            return "transport_failure";
        }
    }
    private static final class ResponseCapException extends IOException { }
    /** One listener/state for one post. Callback arguments containing addresses or headers are not retained. */
    static final class CallState extends okhttp3.EventListener {
        final long startedNanos=System.nanoTime();
        volatile String phase="unknown";
        volatile String lastRequestPhase="unknown";
        volatile Integer responseStatus;
        volatile long bytesRead;
        @Override public void dnsStart(Call call,String domainName){phase="dns";}
        @Override public void connectStart(Call call,java.net.InetSocketAddress address,Proxy proxy){phase="connect";}
        @Override public void secureConnectStart(Call call){phase="tls";}
        @Override public void requestHeadersStart(Call call){phase="request_headers";lastRequestPhase=phase;}
        @Override public void requestBodyStart(Call call){phase="request_body";lastRequestPhase=phase;}
        // OkHttp responseHeadersStart is emitted only after headers are read successfully.
        // Request completion must therefore mark the intervening response-header wait.
        @Override public void requestBodyEnd(Call call,long byteCount){phase="response_headers";}
        @Override public void requestHeadersEnd(Call call,Request request){if(request.body()==null)phase="response_headers";}
        @Override public void requestFailed(Call call,IOException failure){phase=lastRequestPhase;}
        @Override public void responseHeadersStart(Call call){phase="response_headers";}
        @Override public void responseHeadersEnd(Call call,okhttp3.Response response){responseStatus=response.code();}
        @Override public void responseBodyStart(Call call){phase="response_body";}
        // Terminal callbacks deliberately do not replace the phase at the failure with a generic label.
    }
    /** Separate client: no redirects, proxy, retries, unbounded response reads, or response logging. */
    public static final class NativeHttp implements WireHttp {
        private final OkHttpClient client;
        public NativeHttp(){this(new OkHttpClient());}
        NativeHttp(OkHttpClient base){client=Objects.requireNonNull(base).newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY).build();}
        @Override public Response post(String endpoint,String body,long timeoutMs,int maxResponseBytes,String authorization) {
            CallState state=new CallState();
            try {
                OkHttpClient bound=client.newBuilder().eventListener(state).connectTimeout(timeoutMs,TimeUnit.MILLISECONDS).readTimeout(timeoutMs,TimeUnit.MILLISECONDS).writeTimeout(timeoutMs,TimeUnit.MILLISECONDS).callTimeout(timeoutMs,TimeUnit.MILLISECONDS).build();
                Request request=new Request.Builder().url(endpoint).header("Authorization",authorization).post(RequestBody.create(body,MediaType.get("application/json; charset=utf-8"))).build();
                try(okhttp3.Response response=bound.newCall(request).execute()) {
                    state.responseStatus=response.code();state.phase="response_body";
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    if(response.body()!=null)try(InputStream stream=response.body().byteStream()) {byte[] b=new byte[8192];int n;while((n=stream.read(b))!=-1){state.bytesRead+=n;if(n>maxResponseBytes-out.size())throw new ResponseCapException();out.write(b,0,n);}}
                    state.phase="utf8_decode";
                    String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();
                    return new Response(response.code(),text);
                }
            } catch(Exception failure){throw new TypedTransportException(state,failure);}
        }
    }
    private static final class Profile {
        final String base,model,effort,policy,key,sha,policySha;
        final double temperature; final int output,counterCap,generationCap; final long context,margin,counterTimeout,generationTimeout;
        Profile(ConsenseProperties.Responses c) {
            if(!c.isEnabled())throw new IllegalArgumentException("Responses profile disabled");
            base=normalizeBase(c.getBaseUrl());model=c.getModel();effort=c.getReasoningEffort();temperature=c.getTemperature();output=c.getMaxOutputTokens();
            context=c.getConfiguredContextTokens();margin=c.getSafetyMarginTokens();policy=c.getEstimatePolicy();key=c.getApiKey();
            counterTimeout=c.getCounterTimeoutMs();generationTimeout=c.getGenerationTimeoutMs();counterCap=c.getCounterMaxResponseBytes();generationCap=c.getGenerationMaxResponseBytes();
            if(!"MiniMax-M3".equals(model)||!"high".equals(effort)||!Double.isFinite(temperature)||temperature!=0.2||output!=16384
                    ||counterTimeout<=0||counterTimeout>60000||generationTimeout<=0||generationTimeout>600000
                    ||counterCap<1024||counterCap>4194304||generationCap<1024||generationCap>16777216||context<0||margin<0)throw new IllegalArgumentException("Responses public profile invalid");
            Map<String,Object> cp=new LinkedHashMap<>();cp.put("policy",policy);cp.put("configuredContextTokens",context);cp.put("safetyMarginTokens",margin);cp.put("outputReserveTokens",output);cp.put("contextEvidenceScope","configured_operation_limit_not_measured_provider_maximum");policySha=hash(JsonUtils.write(cp));
            Map<String,Object> p=new LinkedHashMap<>();p.put("wire",WIRE);p.put("counterContract",COUNTER);p.put("baseUrl",base);p.put("model",model);p.put("temperature",temperature);p.put("maxOutputTokens",output);p.put("reasoningEffort",effort);p.put("counterTimeoutMs",counterTimeout);p.put("generationTimeoutMs",generationTimeout);p.put("counterMaxResponseBytes",counterCap);p.put("generationMaxResponseBytes",generationCap);p.put("contextPolicySha256",policySha);p.put("retries",0);p.put("stream",false);sha=hash(JsonUtils.write(p));
        }
        boolean knownPolicy(){return POLICY.equals(policy)&&context>0&&context>=output&&margin<=context-output;}
        String authorization(){if(JsonUtils.isBlankText(key))throw new IllegalStateException("Responses credential unavailable");return "Bearer "+key;}
    }
    public static final class PreparedRequest {
        private final Profile profile;
        private final String inputJson,generationJson,counterJson,schemaJson,turnsJson;
        private final WireIdentity wire;
        private PreparedRequest(Profile p,VettingInputBudget.Input in) {
            profile=p;inputJson=JsonUtils.write(in);schemaJson=JsonUtils.write(in.getSchema());turnsJson=JsonUtils.write(in.getMessages());
            ObjectNode body=body(in,p);generationJson=JsonUtils.write(body);
            ObjectNode count=JsonUtils.mapper().createObjectNode();for(String key:Arrays.asList("model","instructions","input","reasoning"))count.set(key,body.get(key).deepCopy());counterJson=JsonUtils.write(count);
            wire=identity(p,generationJson,counterJson);
        }
        public String getInputJson(){return inputJson;}
        public String getGenerationJson(){return generationJson;}
        public String getCounterJson(){return counterJson;}
        public String getGenerationEndpoint(){return profile.base+"/v1/responses";}
        public String getCounterEndpoint(){return profile.base+"/v1/responses/input_tokens";}
        public WireIdentity getWire(){return JsonUtils.read(JsonUtils.write(wire),WireIdentity.class);}
    }
    public VettingInputBudget.Input input(String system,String user,JsonNode schema,String sourceSha) {
        Profile p=profile();VettingInputBudget.Input in=VettingInputBudget.input(WIRE,p.model,p.sha,system,user,schema,p.output,sourceSha);
        PreparedRequest prepared=new PreparedRequest(p,in);VettingInputBudget.bindWire(in,prepared.getWire());return in;
    }
    /** File-only replay entry: a saved Gateway system already includes the schema envelope exactly once. */
    public VettingInputBudget.Input inputFromGateway(String gatewaySystem,String user,JsonNode schema,String sourceSha) {
        if(schema==null||!schema.isObject()||gatewaySystem==null)throw new IllegalArgumentException("Saved Gateway schema/system required");
        String suffix="\nReturn only a JSON array conforming to this schema:\n"+JsonUtils.write(schema);
        if(!gatewaySystem.endsWith(suffix))throw new IllegalArgumentException("Saved Gateway envelope differs");
        VettingInputBudget.Input in=input(gatewaySystem.substring(0,gatewaySystem.length()-suffix.length()),user,schema,sourceSha);
        if(!gatewaySystem.equals(in.getMessages().get(0).getContent())||!Objects.equals(user,in.getMessages().get(1).getContent()))throw new IllegalArgumentException("Saved Gateway turns differ");
        return in;
    }
    public PreparedRequest prepare(VettingInputBudget.Input input) {
        Profile p=profile();
        if(!VettingInputBudget.validBinding(input)||!WIRE.equals(input.getProvider())||!p.model.equals(input.getModel())||!p.sha.equals(input.getProviderConfigurationSha256())||input.getOutputReserveTokens()!=p.output
                ||input.getWire()==null||!isSha(input.getSourceSnapshotSha256())||input.getSchema()==null||!input.getSchema().isObject()
                ||input.getMessages()==null||input.getMessages().size()!=2||!"system".equals(input.getMessages().get(0).getRole())||!"user".equals(input.getMessages().get(1).getRole())
                ||input.getMessages().get(0).getContent()==null||input.getMessages().get(1).getContent()==null)throw new IllegalArgumentException("Responses complete Input binding differs");
        PreparedRequest prepared=new PreparedRequest(p,input);
        if(!JsonUtils.write(prepared.wire).equals(JsonUtils.write(input.getWire())))throw new IllegalArgumentException("Responses wire projection differs");
        return prepared;
    }
    public VettingInputBudget.Observation observe(VettingInputBudget.Input input) {
        PreparedRequest prepared=prepare(input);Profile p=prepared.profile;
        if(!p.knownPolicy())return null; // A default-off/unknown policy never authorizes a request or a counter.
        Response response=http.post(prepared.getCounterEndpoint(),prepared.counterJson,p.counterTimeout,p.counterCap,p.authorization());
        if(response==null||response.body==null||response.body.getBytes(StandardCharsets.UTF_8).length>p.counterCap)throw new IllegalStateException("Responses counter response bound exceeded");
        if(response.status!=200)throw new IllegalStateException("Responses counter HTTP status "+response.status);
        JsonNode raw=strictObject(response.body);Set<String> allowed=new HashSet<>(Arrays.asList("object","input_tokens","model"));
        Iterator<String> names=raw.fieldNames();while(names.hasNext())if(!allowed.contains(names.next()))throw new IllegalStateException("Responses counter envelope differs");
        JsonNode count=raw.get("input_tokens");if(!"response.input_tokens".equals(raw.path("object").asText())||count==null||!count.isIntegralNumber()||!count.canConvertToLong()||count.longValue()<=0)throw new IllegalStateException("Responses counter count invalid");
        boolean unavailable=!raw.has("model");if(!unavailable&&(!raw.get("model").isTextual()||!p.model.equals(raw.get("model").textValue())))throw new IllegalStateException("Responses counter model differs");
        if(!p.sha.equals(profile().sha))throw new IllegalStateException("Responses profile changed during counter");
        VettingInputBudget.Observation out=new VettingInputBudget.Observation();out.setModel(p.model);out.setSerializedInputSha256(input.getSerializedInputSha256());out.setInputTokens(count.longValue());out.setEffectiveContextTokens(p.context);out.setOutputReserveTokens(p.output);out.setScope(SCOPE);out.setCompleteChatTemplateAndSchemaObserved(false);
        EstimateIdentity e=new EstimateIdentity();e.setKind(KIND);e.setWireProtocol(WIRE);e.setCounterContract(COUNTER);e.setProfileSha256(p.sha);e.setGenerationEntitySha256(prepared.wire.getGenerationEntitySha256());e.setCounterProjectionSha256(prepared.wire.getCounterProjectionSha256());e.setContextPolicySha256(p.policySha);e.setConfiguredContextTokens(p.context);e.setSafetyMarginTokens(p.margin);e.setEstimatePolicy(p.policy);e.setContextEvidenceScope(prepared.wire.getContextEvidenceScope());e.setModelIdentityScope("request_declared");e.setResponseModelUnavailable(unavailable);e.setCounterResponseSha256(hash(JsonUtils.write(raw)));out.setProviderEstimate(e);return out;
    }
    static VettingInputBudget.Result checkEstimate(VettingInputBudget.Input in,VettingInputBudget.Observation o,VettingInputBudget.Result result) {
        WireIdentity w=in.getWire();EstimateIdentity e=o.getProviderEstimate();
        if(e==null||!WIRE.equals(w.getProtocol())||!COUNTER.equals(w.getCounterContract())||!KIND.equals(e.getKind())||!SCOPE.equals(o.getScope())||o.isCompleteChatTemplateAndSchemaObserved()
                ||o.getTokenizerIdentitySha256()!=null||o.getChatTemplateIdentitySha256()!=null||o.getEffectiveContextIdentitySha256()!=null
                ||!Objects.equals(in.getModel(),o.getModel())||!Objects.equals(in.getSerializedInputSha256(),o.getSerializedInputSha256())
                ||!Objects.equals(in.getProviderConfigurationSha256(),w.getProfileSha256())||!isSha(w.getProfileSha256())||!isSha(w.getGenerationEntitySha256())||!isSha(w.getCounterProjectionSha256())||!isSha(w.getContextPolicySha256())
                ||!WIRE.equals(e.getWireProtocol())||!COUNTER.equals(e.getCounterContract())||!Objects.equals(w.getProfileSha256(),e.getProfileSha256())||!Objects.equals(w.getGenerationEntitySha256(),e.getGenerationEntitySha256())||!Objects.equals(w.getCounterProjectionSha256(),e.getCounterProjectionSha256())||!Objects.equals(w.getContextPolicySha256(),e.getContextPolicySha256())||!isSha(e.getCounterResponseSha256())
                ||!POLICY.equals(w.getEstimatePolicy())||!Objects.equals(w.getEstimatePolicy(),e.getEstimatePolicy())||!"configured_operation_limit_not_measured_provider_maximum".equals(w.getContextEvidenceScope())||!Objects.equals(w.getContextEvidenceScope(),e.getContextEvidenceScope())||!"request_declared".equals(e.getModelIdentityScope())
                ||w.getConfiguredContextTokens()<=0||w.getSafetyMarginTokens()<0||w.getConfiguredContextTokens()!=e.getConfiguredContextTokens()||w.getSafetyMarginTokens()!=e.getSafetyMarginTokens()
                ||o.getInputTokens()<=0||o.getEffectiveContextTokens()!=w.getConfiguredContextTokens()||o.getOutputReserveTokens()!=in.getOutputReserveTokens()||in.getOutputReserveTokens()<=0) {result.setReason("provider_estimate_identity_or_policy_unknown");return result;}
        long available=w.getConfiguredContextTokens()-in.getOutputReserveTokens();
        if(available<0||w.getSafetyMarginTokens()>available||o.getInputTokens()>available-w.getSafetyMarginTokens()){result.setStatus("over_budget");result.setReason("provider_estimate_output_and_absolute_margin_exceed_configured_limit");return result;}
        result.setStatus("provider_estimated_fit");result.setReason("provider_estimate_policy_not_exact_template_observation");result.setExtraDispatchPermitted(true);return result;
    }
    public <T> List<T> complete(VettingInputBudget.Input input,VettingInputBudget.Result budget,String system,String user,JsonNode schema,Class<T> type,List<String> rawOut,Map<String,Long> timings) {
        PreparedRequest prepared=prepare(input);VettingInputBudget.Input regenerated=input(system,user,schema,input.getSourceSnapshotSha256());
        if(!prepared.inputJson.equals(JsonUtils.write(regenerated)))throw new IllegalArgumentException("Responses final prompts differ from counted Input");
        VettingInputBudget.Result checked=new VettingInputBudget.Result();checked.setSerializedInputSha256(input.getSerializedInputSha256());
        if(budget==null||budget.getObservation()==null||!input.getSerializedInputSha256().equals(budget.getSerializedInputSha256())||!"provider_estimated_fit".equals(budget.getStatus())||!budget.isExtraDispatchPermitted()
                ||!"provider_estimated_fit".equals(checkEstimate(input,budget.getObservation(),checked).getStatus()))throw new IllegalArgumentException("Responses dispatch requires bound estimate policy acceptance");
        if(!prepared.profile.knownPolicy())throw new IllegalStateException("Responses context policy unknown");
        return new AiGateway(new PreparedClient(prepared)).completeStructuredJsonList(system,user,type,schema,rawOut,timings);
    }
    private final class PreparedClient implements LlmClient {
        private final PreparedRequest prepared;
        PreparedClient(PreparedRequest p){prepared=p;}
        @Override public String chatStructured(List<ChatTurn> turns,JsonNode schema) {
            if(!JsonUtils.write(turns).equals(prepared.turnsJson)||!prepared.schemaJson.equals(JsonUtils.write(schema))||!prepared.profile.sha.equals(profile().sha))throw new IllegalArgumentException("Responses dispatch entity changed");
            Response response=http.post(prepared.getGenerationEndpoint(),prepared.generationJson,prepared.profile.generationTimeout,prepared.profile.generationCap,prepared.profile.authorization());
            if(response==null||response.body==null||response.body.getBytes(StandardCharsets.UTF_8).length>prepared.profile.generationCap)throw new IllegalStateException("Responses generation response bound exceeded");
            return decode(response,prepared.profile);
        }
        @Override public String chat(List<ChatTurn> turns){throw new UnsupportedOperationException("Structured vetting only");}
        @Override public List<float[]> embed(List<String> texts){throw new UnsupportedOperationException("No embedding transport");}
        @Override public boolean available(){return true;}
        @Override public String chatModel(){return prepared.profile.model;}
        @Override public String embedModel(){return null;}
    }
    private static String decode(Response response,Profile p) {
        String safe=redact(response.body,p.key);JsonNode r;
        try{r=strictObject(response.body);}catch(Exception failure){throw incomplete("Responses envelope invalid",safe,null,IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE,null);}
        JsonNode provider=r.path("base_resp"),code=provider.path("status_code");
        if(response.status!=200||r.hasNonNull("error")||"failed".equals(r.path("status").asText())||(!provider.isMissingNode()&&!provider.isNull()&&!provider.isObject())||(!code.isMissingNode()&&(!code.isIntegralNumber()||!code.canConvertToLong()||code.longValue()!=0)))throw incomplete("Responses provider failed",safe,null,IncompleteModelResponseException.FailureKind.PROVIDER_ERROR,r);
        if(!"response".equals(r.path("object").asText())||!p.model.equals(r.path("model").asText()))throw incomplete("Responses response identity invalid",safe,null,IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE,r);
        String status=r.path("status").asText("");
        if(!"completed".equals(status)||r.hasNonNull("incomplete_details")) {
            String reason=r.path("incomplete_details").path("reason").asText("");
            throw incomplete("Responses output incomplete",safe,null,"max_output_tokens".equals(reason)?IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED:IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH,r);
        }
        JsonNode output=r.get("output");if(output==null||!output.isArray())throw incomplete("Responses output missing",safe,null,IncompleteModelResponseException.FailureKind.MISSING_TEXT,r);
        StringBuilder text=new StringBuilder();int messages=0;
        for(JsonNode item:output) {
            if(!item.isObject())throw incomplete("Responses output item invalid",safe,null,IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE,r);
            String kind=item.path("type").asText("");
            if("reasoning".equals(kind))continue; // Do not read or join reasoning content.
            if("function_call".equals(kind))throw incomplete("Responses tool call unsupported",safe,null,IncompleteModelResponseException.FailureKind.TOOL_CALLS,r);
            if(!"message".equals(kind)||!"assistant".equals(item.path("role").asText())||!"completed".equals(item.path("status").asText())||++messages!=1||!item.path("content").isArray())throw incomplete("Responses message invalid",safe,null,IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE,r);
            for(JsonNode block:item.get("content")) {
                if("refusal".equals(block.path("type").asText()))throw incomplete("Responses refusal",safe,null,IncompleteModelResponseException.FailureKind.REFUSAL,r);
                if(!block.isObject()||!"output_text".equals(block.path("type").asText())||!block.path("text").isTextual())throw incomplete("Responses text block invalid",safe,null,IncompleteModelResponseException.FailureKind.MISSING_TEXT,r);
                text.append(block.get("text").textValue());
            }
        }
        if(messages!=1||JsonUtils.isBlankText(text.toString()))throw incomplete("Responses text missing",safe,null,IncompleteModelResponseException.FailureKind.MISSING_TEXT,r);
        if(r.hasNonNull("output_text")&&(!r.get("output_text").isTextual()||!text.toString().equals(r.get("output_text").textValue())))throw incomplete("Responses convenience text differs",safe,null,IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE,r);
        return text.toString();
    }
    private static IncompleteModelResponseException incomplete(String m,String raw,JsonNode text,IncompleteModelResponseException.FailureKind kind,JsonNode r) {
        ObjectNode projected=JsonUtils.mapper().createObjectNode();
        if(r!=null&&r.path("usage").isObject()) {ObjectNode usage=projected.putObject("usage");for(String[] pair:new String[][]{{"input_tokens","prompt_tokens"},{"output_tokens","completion_tokens"},{"total_tokens","total_tokens"}}){JsonNode n=r.path("usage").path(pair[0]);if(n.isIntegralNumber()&&n.canConvertToLong()&&n.longValue()>=0)usage.set(pair[1],n.deepCopy());}}
        return new IncompleteModelResponseException(m,raw,text,kind,projected);
    }
    private Profile profile(){return new Profile(properties.getVetting().getResponses());}
    private static ObjectNode body(VettingInputBudget.Input in,Profile p) {
        ObjectNode body=JsonUtils.mapper().createObjectNode();body.put("model",p.model);body.put("instructions",in.getMessages().get(0).getContent());body.putArray("input").addObject().put("type","message").put("role","user").put("content",in.getMessages().get(1).getContent());body.putObject("reasoning").put("effort",p.effort);body.put("temperature",p.temperature);body.put("stream",false);body.put("max_output_tokens",p.output);return body;
    }
    private static WireIdentity identity(Profile p,String generation,String counter) {
        WireIdentity w=new WireIdentity();w.setProtocol(WIRE);w.setCounterContract(COUNTER);w.setProfileSha256(p.sha);w.setGenerationEntitySha256(hash(generation));w.setCounterProjectionSha256(hash(counter));w.setContextPolicySha256(p.policySha);w.setConfiguredContextTokens(p.context);w.setSafetyMarginTokens(p.margin);w.setEstimatePolicy(p.policy);w.setContextEvidenceScope("configured_operation_limit_not_measured_provider_maximum");return w;
    }
    private static JsonNode strictObject(String json) {
        if(json==null)throw new IllegalStateException("Provider JSON missing");
        try(JsonParser parser=JsonUtils.mapper().getFactory().createParser(json)){parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode value=JsonUtils.mapper().readTree(parser);if(value==null||!value.isObject()||parser.nextToken()!=null)throw new IOException("whole_object_required");return value;}
        catch(Exception error){throw new IllegalStateException("Provider JSON invalid: "+error.getClass().getSimpleName());}
    }
    private static String normalizeBase(String value) {
        try{URI u=new URI(value);if(!"https".equals(u.getScheme())||!"api.minimax.cn".equals(u.getHost())||u.getPort()!=-1||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||!(u.getRawPath().isEmpty()||"/".equals(u.getRawPath())))throw new IllegalArgumentException();return "https://api.minimax.cn";}
        catch(Exception error){throw new IllegalArgumentException("Responses endpoint policy invalid");}
    }
    private static String redact(String value,String secret){return value==null?null:JsonUtils.isBlankText(secret)?value:value.replace(secret,"[redacted]");}
    private static String hash(String value){return VettingCorpus.hash(value);}
    private static boolean isSha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
}
