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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama 原生协议客户端：
 * 对话   POST {base}/api/chat        { model, messages, stream:false, options }
 * 向量   POST {base}/api/embed       { model, input:[...] }  （旧版回退 /api/embeddings）
 */
@Slf4j
public class OllamaLlmClient implements LlmClient {

    private final ConsenseProperties.Llm cfg;
    private final HttpSupport http;

    public OllamaLlmClient(ConsenseProperties.Llm cfg, HttpSupport http) {
        this.cfg = cfg;
        this.http = http;
    }

    @Override
    public String chat(List<ChatTurn> turns) {
        return chat(turns, null);
    }

    @Override
    public String chatStructured(List<ChatTurn> turns, JsonNode schema) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("A JSON schema object is required");
        return chat(turns, schema);
    }

    private String chat(List<ChatTurn> turns, JsonNode schema) {
        if (schema != null && cfg.getStructuredMaxTokens() <= 0) {
            throw new IllegalArgumentException("Structured output budget must be positive");
        }
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        body.put("model", cfg.getChatModel());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        for (ChatTurn turn : turns) {
            ObjectNode node = messages.addObject();
            node.put("role", turn.getRole());
            node.put("content", turn.getContent());
        }
        ObjectNode options = body.putObject("options");
        options.put("temperature", cfg.getTemperature());
        options.put("num_ctx", cfg.getNumCtx());
        if (schema != null) {
            body.set("format", schema);
            Boolean thinking = cfg.getStructuredThinking();
            if (thinking != null) body.put("think", thinking);
            // Vetting must fail visibly rather than generate an unbounded quotation/list loop.
            options.put("num_predict", cfg.getStructuredMaxTokens());
        }

        String raw = http.postJson(trim(cfg.getBaseUrl()) + "/api/chat",
                JsonUtils.write(body), cfg.getTimeoutMs(), null, schema == null ? cfg.getMaxRetry() : 0);

        JsonNode root = JsonUtils.parse(raw);
        if (schema != null && "length".equals(root.path("done_reason").asText())) {
            throw new IncompleteModelResponseException("Structured model response exhausted its output budget; semantic review is incomplete",
                    raw, root.path("message").path("content"));
        }
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode() || JsonUtils.isBlankText(content.asText())) {
            throw new BizException("Ollama 未返回内容，请确认模型 " + cfg.getChatModel() + " 已 pull");
        }
        return content.asText();
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

        String raw;
        try {
            raw = http.postJson(trim(cfg.getBaseUrl()) + "/api/embed",
                    JsonUtils.write(body), cfg.getTimeoutMs(), null, cfg.getMaxRetry());
            JsonNode embeddings = JsonUtils.parse(raw).path("embeddings");
            if (embeddings.isArray() && !embeddings.isEmpty()) {
                return toVectors(embeddings);
            }
        } catch (Exception e) {
            log.warn("Ollama /api/embed 不可用，回退到 /api/embeddings: {}", e.getMessage());
        }

        // 旧版逐个 embedding
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            ObjectNode single = JsonUtils.mapper().createObjectNode();
            single.put("model", cfg.getEmbedModel());
            single.put("prompt", text);
            String one = http.postJson(trim(cfg.getBaseUrl()) + "/api/embeddings",
                    JsonUtils.write(single), cfg.getTimeoutMs(), null, cfg.getMaxRetry());
            JsonNode vector = JsonUtils.parse(one).path("embedding");
            result.add(toVector(vector));
        }
        return result;
    }

    @Override
    public boolean available() {
        try {
            String raw = http.get(trim(cfg.getBaseUrl()) + "/api/tags", 4000);
            return JsonUtils.parse(raw).has("models");
        } catch (Exception e) {
            log.debug("Ollama 不可达: {}", e.getMessage());
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

    private List<float[]> toVectors(JsonNode array) {
        List<float[]> result = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            result.add(toVector(node));
        }
        return result;
    }

    private float[] toVector(JsonNode node) {
        float[] vector = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            vector[i] = (float) node.get(i).asDouble();
        }
        return vector;
    }

    private String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 供健康检查展示的附加信息 */
    public Map<String, Object> tags() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            JsonNode root = JsonUtils.parse(http.get(trim(cfg.getBaseUrl()) + "/api/tags", 4000));
            List<String> models = new ArrayList<>();
            root.path("models").forEach(m -> models.add(m.path("name").asText()));
            info.put("reachable", true);
            info.put("models", models);
        } catch (Exception e) {
            info.put("reachable", false);
            info.put("error", e.getMessage());
        }
        return info;
    }
}
