package com.consense.service.advice;

import com.consense.ai.LlmOperation;
import com.consense.ai.LlmProfiles;
import com.consense.ai.ModelIdentity;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.Proxy;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Advice single-object Responses counter/generation, isolated from the shared Drafting Chat gateway. */
@Component
public final class AdviceResponsesTransport {
    public static final String WIRE="minimax-advice-single-object-responses-v1";
    public static final String POLICY="bound_provider_estimate_with_absolute_margin_v1";
    public static final String KIND="provider_reported_estimate";
    /** Public M3 model context, not a measurement of the current account/hosted deployment. */
    public static final long PUBLIC_M3_CONTEXT_BOUND=1000000L;
    public static final String JSON_INSTRUCTION="\n\n【输出要求】只输出一个合法的 JSON，不要 Markdown 代码块，不要任何解释文字。";
    public static final String RETRY_INSTRUCTION="\n\n重要：上一次输出不是合法 JSON。请严格只输出 JSON。";
    private final ConsenseProperties deployment;
    private final AdviceModelProperties settings;
    private final WireHttp http;
    private final AdviceWireObservation observation;
    @Autowired public AdviceResponsesTransport(ConsenseProperties d,AdviceModelProperties s){this(d,s,new NativeHttp(),AdviceWireObservation.configured(s));}
    public AdviceResponsesTransport(ConsenseProperties d,AdviceModelProperties s,WireHttp h){this(d,s,h,null);}
    AdviceResponsesTransport(ConsenseProperties d,AdviceModelProperties s,WireHttp h,AdviceWireObservation o){deployment=Objects.requireNonNull(d);settings=Objects.requireNonNull(s);http=Objects.requireNonNull(h);observation=o;}

    /** Explicit route only; disabled or invalid Responses remains an error, never a Chat fallback. */
    public boolean selected(LlmOperation operation){
        if(operation==null||!operation.isExplicit()||!"minimax-cn".equals(operation.getIdentity().getProfileId()))return false;
        String route=settings.getMinimaxWire();
        if("chat".equals(route))return false;
        if(!"responses".equals(route))throw failure("budget_unknown","advice_wire_configuration_unknown",null);
        return true;
    }
    public Operation capture(LlmOperation operation){
        if(!selected(operation))throw failure("budget_unknown","advice_responses_not_selected",null);
        return new Operation(operation,new Profile(operation));
    }
    public interface WireHttp { Response post(String endpoint,String body,long timeoutMs,int cap,String authorization); }
    public static final class Response {
        public final int status; public final String body; private final byte[] nativeEntity;
        public Response(int s,String b){this(s,b,null);}
        Response(int s,String b,byte[] exact){status=s;body=b;nativeEntity=exact==null?null:exact.clone();}
        byte[] entityBytes(){return nativeEntity==null?body.getBytes(StandardCharsets.UTF_8):nativeEntity.clone();}
        boolean hasNativeEntityBytes(){return nativeEntity!=null;}
    }
    /** Safe typed failure excludes provider response text, request content and credentials. */
    public static final class RequestFailure extends BizException {
        private final String status,reason; private final Integer httpStatus;
        RequestFailure(String s,String r,Integer code){super(4205,"Advice Responses "+s+": "+r+(code==null?"":" (HTTP "+code+")"));status=s;reason=r;httpStatus=code;}
        public String getStatus(){return status;} public String getReason(){return reason;} public Integer getHttpStatus(){return httpStatus;}
    }
    private static RequestFailure failure(String status,String reason,Integer code){return new RequestFailure(status,reason,code);}

    /** Bounded one-shot/no-redirect/no-replay transport; no prompt/body/auth logging. */
    public static final class NativeHttp implements WireHttp {
        private final OkHttpClient base=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        public Response post(String endpoint,String body,long timeoutMs,int cap,String authorization){
            try {
                final RequestBody delegate=RequestBody.create(body,MediaType.get("application/json; charset=utf-8"));
                RequestBody oneShot=new RequestBody(){
                    public MediaType contentType(){return delegate.contentType();}
                    public long contentLength()throws IOException{return delegate.contentLength();}
                    public void writeTo(okio.BufferedSink sink)throws IOException{delegate.writeTo(sink);}
                    public boolean isOneShot(){return true;}
                };
                OkHttpClient client=base.newBuilder().connectTimeout(timeoutMs,TimeUnit.MILLISECONDS).readTimeout(timeoutMs,TimeUnit.MILLISECONDS).writeTimeout(timeoutMs,TimeUnit.MILLISECONDS).callTimeout(timeoutMs,TimeUnit.MILLISECONDS).build();
                Request request=new Request.Builder().url(endpoint).header("Authorization",authorization).post(oneShot).build();
                try(okhttp3.Response response=client.newCall(request).execute()){
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    if(response.body()!=null)try(InputStream stream=response.body().byteStream()){
                        byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1){if(n>cap-bytes.size())throw failure("transport_failed","response_cap_exceeded",response.code());bytes.write(buffer,0,n);}
                    }
                    String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
                    return new Response(response.code(),decoded,bytes.toByteArray());
                }
            }catch(RequestFailure typed){throw typed;}
            catch(Exception error){throw failure("transport_failed","http_"+error.getClass().getSimpleName(),null);}
        }
    }
    private final class Profile {
        final String base,model,effort,key,sha,policySha,selectedSha,policy;
        final int output,counterCap,generationCap; final long context,margin,counterTimeout,generationTimeout;
        final double temperature; final boolean enabled;
        Profile(LlmOperation operation){
            ConsenseProperties.Responses c=settings.getResponses();ConsenseProperties.Llm selected=deployment.getMinimaxCn();
            if(c==null||operation==null||operation.getIdentity()==null)throw failure("budget_unknown","profile_missing",null);
            selectedSha=LlmProfiles.configurationSha256(selected);
            if(!"minimax-cn".equals(operation.getIdentity().getProfileId())||!operation.isExplicit()||!selectedSha.equals(operation.getIdentity().getConfigurationSha256()))throw failure("budget_unknown","selected_profile_identity_changed",null);
            model=c.getModel();base=normalizeBase(c.getBaseUrl());enabled=c.isEnabled();
            if(!"MiniMax-M3".equals(model)||!model.equals(operation.getIdentity().getModel())||!"openai".equals(operation.getIdentity().getProvider()))throw failure("budget_unknown","advice_model_contract_unknown",null);
            String thinking=operation.getStructuredOpenAiThinkingMode();
            if("disabled".equals(thinking))effort="none";else if("adaptive".equals(thinking))effort="high";else throw failure("budget_unknown","captured_thinking_mode_unknown",null);
            output=c.getMaxOutputTokens();temperature=c.getTemperature();context=c.getConfiguredContextTokens();margin=c.getSafetyMarginTokens();policy=c.getEstimatePolicy();
            counterTimeout=c.getCounterTimeoutMs();generationTimeout=c.getGenerationTimeoutMs();counterCap=c.getCounterMaxResponseBytes();generationCap=c.getGenerationMaxResponseBytes();
            key=JsonUtils.isBlankText(c.getApiKey())?selected.getApiKey():c.getApiKey();
            if(output<=0||output!=operation.getStructuredMaxTokens()||!Double.isFinite(temperature)||temperature<=0||temperature>1||temperature!=selected.getTemperature()
                    ||counterTimeout<=0||counterTimeout>60000||generationTimeout<=0||generationTimeout>600000||counterCap<1024||counterCap>4194304||generationCap<1024||generationCap>16777216||context<0||margin<0)
                throw failure("budget_unknown","responses_profile_configuration_invalid",null);
            Map<String,Object> cp=new LinkedHashMap<>();cp.put("policy",policy);cp.put("context",context);cp.put("margin",margin);cp.put("outputReserve",output);cp.put("contextScope","configured_operation_limit_not_measured_provider_maximum");policySha=hash(JsonUtils.write(cp));
            Map<String,Object> identity=new LinkedHashMap<>();identity.put("protocol",WIRE);identity.put("base",base);identity.put("model",model);identity.put("temperature",temperature);identity.put("reasoningEffort",effort);identity.put("outputReserve",output);identity.put("contextPolicySha256",policySha);identity.put("selectedChatConfigurationSha256",selectedSha);identity.put("counterTimeoutMs",counterTimeout);identity.put("generationTimeoutMs",generationTimeout);identity.put("counterCap",counterCap);identity.put("generationCap",generationCap);identity.put("enabled",enabled);identity.put("route",settings.getMinimaxWire());identity.put("retries",0);identity.put("stream",false);sha=hash(JsonUtils.write(identity));
        }
        boolean knownPolicy(){return enabled&&POLICY.equals(policy)&&context>0&&context<=PUBLIC_M3_CONTEXT_BOUND&&margin>0&&output<=context&&margin<=context-output;}
        String authorization(){if(JsonUtils.isBlankText(key))throw failure("budget_unknown","credential_unavailable",null);return "Bearer "+key;}
    }
    public static final class PreparedRequest {
        private final String generationJson,counterJson,bindingSha,sourceSha,system,user;
        private final Profile profile; private final int attempt; private AdviceWireObservation.Attempt observation;
        PreparedRequest(Profile p,String s,String u,String source,int number){
            profile=p;system=s;user=u;sourceSha=source;attempt=number;
            if(s==null||u==null||!isSha(source)||(number!=0&&number!=1))throw failure("budget_unknown","complete_request_source_binding_required",null);
            ObjectNode body=JsonUtils.mapper().createObjectNode();body.put("model",p.model);body.put("instructions",s+JSON_INSTRUCTION);
            body.putArray("input").addObject().put("type","message").put("role","user").put("content",u+(number==1?RETRY_INSTRUCTION:""));
            body.putObject("reasoning").put("effort",p.effort);body.put("temperature",p.temperature);body.put("stream",false);body.put("max_output_tokens",p.output);
            generationJson=JsonUtils.write(body);ObjectNode count=JsonUtils.mapper().createObjectNode();
            for(String field:Arrays.asList("model","instructions","input","reasoning"))count.set(field,body.get(field).deepCopy());counterJson=JsonUtils.write(count);
            bindingSha=hash(JsonUtils.write(Arrays.asList(WIRE,p.sha,p.policySha,hash(generationJson),hash(counterJson),source,number)));
        }
        public String getGenerationJson(){return generationJson;} public String getCounterJson(){return counterJson;}
        public String getGenerationEntitySha256(){return hash(generationJson);} public String getCounterProjectionSha256(){return hash(counterJson);}
        public String getBindingSha256(){return bindingSha;} public String getSourceSnapshotSha256(){return sourceSha;}
        public String getProfileSha256(){return profile.sha;} public String getContextPolicySha256(){return profile.policySha;}
        public int getOutputReserveTokens(){return profile.output;} public int getAttempt(){return attempt;}
        public long getConfiguredContextTokens(){return profile.context;} public long getSafetyMarginTokens(){return profile.margin;}
    }
    public static final class BudgetResult {
        private final PreparedRequest request; private final String status,reason,responseSha; private final long inputTokens;
        BudgetResult(PreparedRequest r,String s,String why,String response,long count){request=r;status=s;reason=why;responseSha=response;inputTokens=count;}
        public String getStatus(){return status;} public String getReason(){return reason;} public String getKind(){return KIND;}
        public String getBindingSha256(){return request.bindingSha;} public long getInputTokens(){return inputTokens;} public String getCounterResponseSha256(){return responseSha;}
        public long getConfiguredContextTokens(){return request.profile.context;} public long getSafetyMarginTokens(){return request.profile.margin;} public int getOutputReserveTokens(){return request.profile.output;}
        public boolean isExactTemplateObserved(){return false;} public boolean isGenerationPermitted(){return "provider_estimated_fit".equals(status);}
    }
    public static final class Completion<T> {
        public final T value; public final ModelIdentity identity; public final List<BudgetResult> budgets;
        Completion(T v,ModelIdentity id,List<BudgetResult> b){value=v;identity=id;budgets=Collections.unmodifiableList(new ArrayList<>(b));}
    }
    public final class Operation {
        private final LlmOperation selection; private final Profile profile; private final ModelIdentity identity;
        Operation(LlmOperation s,Profile p){selection=s;profile=p;identity=new ModelIdentity("minimax-cn",WIRE,p.model,p.sha,"configured_advice_responses_wire");}
        public ModelIdentity getIdentity(){return identity;}
        public long getConfiguredContextTokens(){return profile.context;}
        public PreparedRequest prepare(String system,String user,String sourceSha,int attempt){
            assertProfile();PreparedRequest request=new PreparedRequest(profile,system,user,sourceSha,attempt);
            if(observation!=null)request.observation=observation.begin(request,observationConfiguration(profile),profile.key,http instanceof NativeHttp);
            return request;
        }
        private void assertProfile(){if(!profile.sha.equals(new Profile(selection).sha))throw failure("budget_unknown","captured_responses_profile_changed",null);}
        private void assertPrepared(PreparedRequest request){assertProfile();if(request==null||request.profile!=profile||!new PreparedRequest(profile,request.system,request.user,request.sourceSha,request.attempt).bindingSha.equals(request.bindingSha))throw failure("budget_unknown","prepared_request_binding_changed",null);}
        public BudgetResult observe(PreparedRequest request,Runnable currentSourceGuard){
            try{BudgetResult result=observeBound(request,currentSourceGuard);if(request.observation!=null){request.observation.budget(result);request.observation.outcome("counter",result.getStatus());}return result;}
            catch(RuntimeException error){if(request!=null&&request.observation!=null)request.observation.outcome("counter","failed_before_budget_acceptance");throw error;}
        }
        private BudgetResult observeBound(PreparedRequest request,Runnable currentSourceGuard){
            assertPrepared(request);if(currentSourceGuard==null)throw failure("budget_unknown","fresh_source_guard_required",null);
            if(!profile.knownPolicy())return new BudgetResult(request,"budget_unknown","explicit_estimate_context_margin_policy_required",null,0);
            currentSourceGuard.run();Response response=post(profile.base+"/v1/responses/input_tokens",request.counterJson,profile.counterTimeout,profile.counterCap,profile,request,"counter");
            assertProfile();currentSourceGuard.run();
            if(response.status!=200)throw failure("budget_unknown","counter_provider_rejected",response.status);
            JsonNode raw=strictObject(response.body,"budget_unknown");Set<String> fields=new HashSet<>(Arrays.asList("object","input_tokens","model"));Iterator<String> names=raw.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))throw failure("budget_unknown","counter_envelope_unknown",null);
            JsonNode count=raw.get("input_tokens");if(!"response.input_tokens".equals(raw.path("object").asText())||count==null||!count.isIntegralNumber()||!count.canConvertToLong()||count.longValue()<=0||(raw.has("model")&&(!raw.path("model").isTextual()||!profile.model.equals(raw.path("model").textValue()))))throw failure("budget_unknown","counter_identity_or_count_invalid",null);
            long tokens=count.longValue();boolean fit=tokens<=profile.context-profile.output-profile.margin;
            return new BudgetResult(request,fit?"provider_estimated_fit":"over_budget",fit?"provider_estimate_policy_not_exact_template_observation":"provider_estimate_output_and_margin_exceed_configured_limit",hash(response.body),tokens);
        }
        public String generate(PreparedRequest request,BudgetResult budget,Runnable currentSourceGuard){
            assertPrepared(request);if(budget==null||budget.request!=request||!budget.isGenerationPermitted()||!profile.knownPolicy()||budget.inputTokens<=0||budget.inputTokens>profile.context-profile.output-profile.margin)throw failure(budget==null?"budget_unknown":budget.status,"bound_provider_estimated_fit_required",null);
            if(currentSourceGuard==null)throw failure("budget_unknown","fresh_source_guard_required",null);
            try{
                currentSourceGuard.run();Response response=post(profile.base+"/v1/responses",request.generationJson,profile.generationTimeout,profile.generationCap,profile,request,"generation");assertProfile();currentSourceGuard.run();
                String decoded=decode(response,profile);if(request.observation!=null)request.observation.outcome("generation","provider_envelope_validated_source_guards_passed_answer_json_not_yet_parsed");return decoded;
            }catch(RuntimeException error){if(request.observation!=null)request.observation.outcome("generation","failed_before_answer_acceptance");throw error;}
        }
        /** Same single-object suffix/retry as completeJson, but each request is freshly counted. */
        public <T> Completion<T> completeJson(String system,String user,String sourceSha,Class<T> type,Runnable guard){
            List<BudgetResult> results=new ArrayList<>();
            for(int attempt=0;attempt<2;attempt++){
                PreparedRequest request=prepare(system,user,sourceSha,attempt);BudgetResult result=observe(request,guard);results.add(result);
                if(!result.isGenerationPermitted())throw failure(result.status,result.reason,null);
                String raw=generate(request,result,guard);
                try{return new Completion<>(JsonUtils.read(JsonUtils.extractJson(raw),type),identity,results);}
                catch(Exception parse){if(attempt==1)throw failure("output_invalid","single_object_json_parse_failed_after_rebudgeted_retry",null);}
            }
            throw failure("output_invalid","single_object_json_parse_failed",null);
        }
    }
    private static Map<String,Object> observationConfiguration(Profile p){
        Map<String,Object> value=new LinkedHashMap<>();value.put("wire",WIRE);value.put("route","responses");value.put("baseUrl",p.base);value.put("model",p.model);value.put("reasoningEffort",p.effort);value.put("temperature",p.temperature);
        value.put("configuredContextTokens",p.context);value.put("estimatePolicy",p.policy);value.put("contextScope","configured_operation_limit_not_measured_provider_maximum");value.put("outputReserveTokens",p.output);value.put("safetyMarginTokens",p.margin);
        value.put("counterTimeoutMs",p.counterTimeout);value.put("generationTimeoutMs",p.generationTimeout);value.put("counterMaxResponseBytes",p.counterCap);value.put("generationMaxResponseBytes",p.generationCap);value.put("transportRetries",0);value.put("maximumJsonParsingAttempts",2);value.put("stream",false);value.put("responsesEnabled",p.enabled);value.put("selectedChatConfigurationSha256",p.selectedSha);return value;
    }
    private Response post(String endpoint,String body,long timeout,int cap,Profile p,PreparedRequest request,String phase){
        Response response;long started=System.nanoTime();
        try{response=http.post(endpoint,body,timeout,cap,p.authorization());}
        catch(RequestFailure safe){throw safe;}catch(RuntimeException unknown){throw failure("transport_failed","http_"+unknown.getClass().getSimpleName(),null);}
        if(response==null||response.body==null||response.body.getBytes(StandardCharsets.UTF_8).length>cap)throw failure("transport_failed","response_bound_exceeded",response==null?null:response.status);
        if(request.observation!=null)request.observation.response(phase,endpoint,response,System.nanoTime()-started);
        return response;
    }
    private static String decode(Response response,Profile p){
        if(response.status!=200)throw failure("provider_failed","generation_provider_rejected",response.status);
        JsonNode root=strictObject(response.body,"output_invalid"),code=root.path("base_resp").path("status_code");
        if(root.hasNonNull("error")||"failed".equals(root.path("status").asText())||(root.hasNonNull("base_resp")&&!root.path("base_resp").isObject())||(!code.isMissingNode()&&(!code.isIntegralNumber()||!code.canConvertToLong()||code.longValue()!=0)))throw failure("provider_failed","generation_provider_error",null);
        if(!"response".equals(root.path("object").asText())||!p.model.equals(root.path("model").asText()))throw failure("output_invalid","generation_response_identity_invalid",null);
        if(!"completed".equals(root.path("status").asText())||root.hasNonNull("incomplete_details"))throw failure("output_incomplete","generation_did_not_complete",null);
        if(!"disabled".equals(root.path("truncation").asText()))throw failure("output_invalid","generation_input_truncation_not_verified",null);
        if(!root.path("output").isArray())throw failure("output_invalid","generation_output_missing",null);
        StringBuilder text=new StringBuilder();int messages=0;
        for(JsonNode item:root.path("output")){
            if(!item.isObject())throw failure("output_invalid","generation_output_item_invalid",null);
            String kind=item.path("type").asText();if("reasoning".equals(kind))continue;
            if(!"message".equals(kind)||!"assistant".equals(item.path("role").asText())||!"completed".equals(item.path("status").asText())||++messages!=1||!item.path("content").isArray())throw failure("output_invalid","generation_message_or_tool_invalid",null);
            for(JsonNode block:item.path("content")){if(!block.isObject()||!"output_text".equals(block.path("type").asText())||!block.path("text").isTextual())throw failure("output_invalid","generation_text_or_refusal_invalid",null);text.append(block.path("text").textValue());}
        }
        if(messages!=1||JsonUtils.isBlankText(text.toString())||(root.hasNonNull("output_text")&&(!root.path("output_text").isTextual()||!text.toString().equals(root.path("output_text").textValue()))))throw failure("output_invalid","generation_text_missing_or_differs",null);
        return JsonUtils.isBlankText(p.key)?text.toString():text.toString().replace(p.key,"[REDACTED]");
    }
    private static JsonNode strictObject(String json,String status){
        try(JsonParser parser=JsonUtils.mapper().getFactory().createParser(json)){parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode value=JsonUtils.mapper().readTree(parser);if(value==null||!value.isObject()||parser.nextToken()!=null)throw new IOException();return value;}
        catch(Exception bad){throw failure(status,"provider_json_envelope_invalid",null);}
    }
    private static String normalizeBase(String value){
        try{URI uri=new URI(value);if(!"https".equals(uri.getScheme())||!"api.minimax.cn".equals(uri.getHost())||uri.getPort()!=-1||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!(uri.getRawPath().isEmpty()||"/".equals(uri.getRawPath())))throw new IllegalArgumentException();return "https://api.minimax.cn";}
        catch(Exception invalid){throw failure("budget_unknown","responses_endpoint_policy_invalid",null);}
    }
    private static String hash(String value){return VettingCorpus.hash(value);}
    private static boolean isSha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
}
