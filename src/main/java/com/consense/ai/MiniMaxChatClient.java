package com.consense.ai;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;

/** China Token Plan chat adapter. Reasoning is separated and structured thinking disabled. */
public final class MiniMaxChatClient implements LlmClient {
    private final OpenAiLlmClient delegate;
    private final ConsenseProperties.Llm cfg;
    private final HttpSupport http;
    public MiniMaxChatClient(ConsenseProperties.Llm cfg,HttpSupport http){this.cfg=cfg;this.http=http;delegate=new OpenAiLlmClient(cfg,http);}
    @Override public String chat(List<ChatTurn> turns){return safeCall(()->delegate.chatStructured(turns,JsonUtils.mapper().createObjectNode()));}
    @Override public String chatStructured(List<ChatTurn> turns,JsonNode schema){return safeCall(()->delegate.chatStructured(turns,schema));}
    @Override public List<float[]> embed(List<String> texts){throw new BizException(5001,"MiniMax chat selection does not provide deployment embeddings.");}
    @Override public boolean available(){
        if(!cfg.isEnabled()||JsonUtils.isBlankText(cfg.getApiKey()))return false;
        try {String root=cfg.getBaseUrl().replaceAll("/+$","");return JsonUtils.parse(http.get(root+"/v1/models",4000,"Bearer "+cfg.getApiKey())).has("data");}
        catch(RuntimeException unavailable){return false;}
    }
    @Override public String chatModel(){return delegate.chatModel();}
    @Override public String embedModel(){return "";}

    /** Provider error bodies can echo Authorization; retain the failure kind without persisting a credential. */
    private String safeCall(Supplier<String> request) {
        try {return redact(request.get());}
        catch(IncompleteModelResponseException e) {
            String raw=redact(e.getRawResponse());JsonNode envelope=null;
            try {envelope=JsonUtils.parse(raw);}catch(RuntimeException ignored) {/* Preserve an invalid-envelope failure. */}
            JsonNode content=e.getModelContent()==null?null:JsonUtils.mapper().getNodeFactory().textNode(redact(e.getModelContent()));
            throw new IncompleteModelResponseException(redact(e.getMessage()),raw,content,e.getFailureKind(),envelope);
        } catch(BizException e){throw new BizException(e.getCode(),redact(e.getMessage()));}
        catch(RuntimeException e){throw new BizException(5001,"MiniMax chat transport failed: "+redact(e.getMessage()));}
    }
    private String redact(String text){return text==null||JsonUtils.isBlankText(cfg.getApiKey())?text:text.replace(cfg.getApiKey(),"[REDACTED]");}
}
