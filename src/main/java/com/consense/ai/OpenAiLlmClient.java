package com.consense.ai;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI 兼容协议（vLLM / LM Studio / One-API / Xinference 等）。
 * 对话 POST {base}/v1/chat/completions
 * 向量 POST {base}/v1/embeddings
 */
@Slf4j
public class OpenAiLlmClient implements LlmClient {

    private final ConsenseProperties.Llm cfg;
    private final HttpSupport http;

    public OpenAiLlmClient(ConsenseProperties.Llm cfg, HttpSupport http) {
        this.cfg = cfg;
        this.http = http;
    }

    @Override
    public String chat(List<ChatTurn> turns) {
        return chat(turns, false);
    }

    /** Prompt schema is supplied by AiGateway; this client does not assume native JSON grammar support. */
    @Override
    public String chatStructured(List<ChatTurn> turns, JsonNode schema) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("A JSON schema object is required");
        if (cfg.getStructuredMaxTokens() <= 0) throw new IllegalArgumentException("Structured output budget must be positive");
        String thinkingMode = cfg.getStructuredOpenAiThinkingMode();
        if (thinkingMode != null && !"adaptive".equals(thinkingMode) && !"disabled".equals(thinkingMode)) {
            throw new IllegalArgumentException("Structured OpenAI thinking mode must be adaptive, disabled, or null");
        }
        return chat(turns, true);
    }

    private String chat(List<ChatTurn> turns, boolean structured) {
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        body.put("model", cfg.getChatModel());
        body.put("temperature", cfg.getTemperature());
        body.put("stream", false);
        if (structured) {
            body.put("n", 1);
            body.put("max_tokens", cfg.getStructuredMaxTokens());
            if (cfg.getStructuredOpenAiThinkingMode() != null) {
                body.putObject("thinking").put("type", cfg.getStructuredOpenAiThinkingMode());
            }
            if (cfg.getStructuredOpenAiReasoningSplit() != null) {
                body.put("reasoning_split", cfg.getStructuredOpenAiReasoningSplit());
            }
        }
        ArrayNode messages = body.putArray("messages");
        for (ChatTurn turn : turns) {
            ObjectNode node = messages.addObject();
            node.put("role", turn.getRole());
            node.put("content", turn.getContent());
        }
        String raw = http.postJson(trim(cfg.getBaseUrl()) + "/v1/chat/completions",
                JsonUtils.write(body), cfg.getTimeoutMs(), authHeader(), structured ? 0 : cfg.getMaxRetry());
        JsonNode root;
        try {
            root = JsonUtils.parse(raw);
        } catch (BizException invalidEnvelope) {
            if (!structured) throw invalidEnvelope;
            throw new IncompleteModelResponseException("Structured provider response is not a JSON envelope", raw, null,
                    IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE, null);
        }
        JsonNode choices = root.path("choices");
        JsonNode choice = choices.path(0);
        JsonNode content = choice.path("message").path("content");
        if (structured) {
            if (!root.isObject()) {
                throw incomplete("Structured provider response must be an object", raw, content,
                        IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE, root);
            }
            JsonNode error = root.path("error");
            JsonNode provider = root.path("base_resp");
            JsonNode providerCode = provider.path("status_code");
            if ((!error.isMissingNode() && !error.isNull())
                    || (!provider.isMissingNode() && !provider.isNull() && !provider.isObject())
                    || (!providerCode.isMissingNode() && (!providerCode.isIntegralNumber()
                        || !providerCode.canConvertToLong() || providerCode.longValue() != 0))) {
                throw incomplete("Structured provider response reports an error", raw, content,
                        IncompleteModelResponseException.FailureKind.PROVIDER_ERROR, root);
            }
            if (!choices.isArray() || choices.size() != 1 || !choice.isObject()) {
                throw incomplete("Structured model response must contain exactly one completed choice", raw, content,
                        IncompleteModelResponseException.FailureKind.INVALID_CHOICE_COUNT, root);
            }
            String finishReason = choice.path("finish_reason").asText("");
            if (!"stop".equals(finishReason)) {
                IncompleteModelResponseException.FailureKind kind = "length".equals(finishReason)
                        ? IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED
                        : "tool_calls".equals(finishReason) || "function_call".equals(finishReason)
                            ? IncompleteModelResponseException.FailureKind.TOOL_CALLS
                            : IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH;
                throw incomplete("Structured model response is incomplete: finish_reason="
                        + IncompleteModelResponseException.safeFinishReason(choice.path("finish_reason")), raw, content, kind, root);
            }
            JsonNode toolCalls = choice.path("message").path("tool_calls");
            JsonNode refusal = choice.path("message").path("refusal");
            boolean hasToolCalls = !toolCalls.isMissingNode() && !toolCalls.isNull()
                    && !(toolCalls.isArray() && toolCalls.isEmpty());
            boolean refused = !refusal.isMissingNode() && !refusal.isNull()
                    && (!refusal.isTextual() || !JsonUtils.isBlankText(refusal.textValue()));
            if (hasToolCalls || refused || !content.isTextual() || JsonUtils.isBlankText(content.textValue())) {
                IncompleteModelResponseException.FailureKind kind = refused
                        ? IncompleteModelResponseException.FailureKind.REFUSAL
                        : hasToolCalls ? IncompleteModelResponseException.FailureKind.TOOL_CALLS
                            : IncompleteModelResponseException.FailureKind.MISSING_TEXT;
                throw incomplete("Structured model response did not complete a text assessment", raw, content, kind, root);
            }
        }
        if (content.isMissingNode()) {
            throw new BizException("OpenAI 兼容端点未返回内容");
        }
        return content.asText();
    }

    private IncompleteModelResponseException incomplete(String message, String raw, JsonNode content,
                    IncompleteModelResponseException.FailureKind kind, JsonNode root) {
        return new IncompleteModelResponseException(message, raw, content, kind, root);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        body.put("model", cfg.getEmbedModel());
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        String raw = http.postJson(trim(cfg.getBaseUrl()) + "/v1/embeddings",
                JsonUtils.write(body), cfg.getTimeoutMs(), authHeader(), cfg.getMaxRetry());
        JsonNode data = JsonUtils.parse(raw).path("data");
        List<float[]> result = new ArrayList<>(data.size());
        for (JsonNode node : data) {
            JsonNode vector = node.path("embedding");
            float[] array = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                array[i] = (float) vector.get(i).asDouble();
            }
            result.add(array);
        }
        return result;
    }

    @Override
    public boolean available() {
        try {
            String endpoint = trim(cfg.getBaseUrl()) + "/v1/models";
            String raw = JsonUtils.isBlankText(cfg.getApiKey()) ? http.get(endpoint, 4000)
                    : http.get(endpoint, 4000, authHeader());
            return JsonUtils.parse(raw).has("data");
        } catch (Exception e) {
            log.debug("OpenAI 兼容端点不可达: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String chatModel() {
        return cfg.getChatModel();
    }

    @Override
    public String embedModel() {
        return cfg.getEmbedModel();
    }

    private String authHeader() {
        return JsonUtils.isBlankText(cfg.getApiKey()) ? null : "Bearer " + cfg.getApiKey();
    }

    private String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
