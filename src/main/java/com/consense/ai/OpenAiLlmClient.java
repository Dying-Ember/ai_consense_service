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
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        body.put("model", cfg.getChatModel());
        body.put("temperature", cfg.getTemperature());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        for (ChatTurn turn : turns) {
            ObjectNode node = messages.addObject();
            node.put("role", turn.getRole());
            node.put("content", turn.getContent());
        }
        String raw = http.postJson(trim(cfg.getBaseUrl()) + "/v1/chat/completions",
                JsonUtils.write(body), cfg.getTimeoutMs(), authHeader(), cfg.getMaxRetry());
        JsonNode content = JsonUtils.parse(raw).path("choices").path(0).path("message").path("content");
        if (content.isMissingNode()) {
            throw new BizException("OpenAI 兼容端点未返回内容");
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
            String raw = http.get(trim(cfg.getBaseUrl()) + "/v1/models", 4000);
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
