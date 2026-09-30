package com.consense.vector;

import com.consense.ai.HttpSupport;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant REST 客户端。
 * 集合创建、点写入、带 projectId 过滤的相似度检索、按项目/文档删除。
 */
@Slf4j
@RequiredArgsConstructor
public class QdrantVectorStore implements VectorStore {

    private final ConsenseProperties.VectorCfg cfg;
    private final HttpSupport http;
    private volatile boolean collectionReady = false;

    @Override
    public void ensureCollection() {
        if (collectionReady) {
            return;
        }
        synchronized (this) {
            if (collectionReady) {
                return;
            }
            String url = base() + "/collections/" + cfg.getCollection();
            boolean exists;
            try {
                http.get(url, 5000);
                exists = true;
            } catch (Exception e) {
                exists = false;
            }
            if (!exists) {
                ObjectNode body = JsonUtils.mapper().createObjectNode();
                ObjectNode vectors = body.putObject("vectors");
                vectors.put("size", cfg.getVectorSize());
                vectors.put("distance", cfg.getDistance());
                http.putJson(url, JsonUtils.write(body), cfg.getTimeoutMs());
                log.info("已创建 Qdrant 集合 {} (size={}, distance={})",
                        cfg.getCollection(), cfg.getVectorSize(), cfg.getDistance());
            }
            collectionReady = true;
        }
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        ensureCollection();
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        ArrayNode array = body.putArray("points");
        for (VectorPoint point : points) {
            ObjectNode node = array.addObject();
            // Qdrant 只接受无符号整数或 UUID 作为 point id
            node.put("id", toUuid(point.getId()));
            ArrayNode vector = node.putArray("vector");
            for (float value : point.getVector()) {
                vector.add(value);
            }
            ObjectNode payload = node.putObject("payload");
            point.getPayload().forEach((key, value) -> payload.set(key,
                    JsonUtils.mapper().valueToTree(value)));
        }
        http.putJson(base() + "/collections/" + cfg.getCollection() + "/points?wait=true",
                JsonUtils.write(body), cfg.getTimeoutMs());
    }

    @Override
    public List<SearchHit> search(String projectId, float[] query, int topK, double scoreThreshold) {
        ensureCollection();
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        ArrayNode vector = body.putArray("vector");
        for (float value : query) {
            vector.add(value);
        }
        body.put("limit", topK);
        body.put("with_payload", true);
        body.put("score_threshold", scoreThreshold);
        ObjectNode filter = body.putObject("filter");
        ArrayNode must = filter.putArray("must");
        ObjectNode match = must.addObject();
        match.put("key", "projectId");
        match.putObject("match").put("value", projectId);

        String raw = http.postJson(base() + "/collections/" + cfg.getCollection() + "/points/search",
                JsonUtils.write(body), cfg.getTimeoutMs());
        JsonNode result = JsonUtils.parse(raw).path("result");
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode node : result) {
            Map<String, Object> payload = new LinkedHashMap<>();
            node.path("payload").fields().forEachRemaining(e ->
                    payload.put(e.getKey(), JsonUtils.mapper().convertValue(e.getValue(), Object.class)));
            hits.add(new SearchHit(node.path("id").asText(), node.path("score").asDouble(), payload));
        }
        return hits;
    }

    @Override
    public int count(String projectId) {
        try {
            ensureCollection();
            ObjectNode body = projectFilter(projectId);
            body.put("exact", true);
            String raw = http.postJson(base() + "/collections/" + cfg.getCollection() + "/points/count",
                    JsonUtils.write(body), cfg.getTimeoutMs());
            return JsonUtils.parse(raw).path("result").path("count").asInt(0);
        } catch (Exception e) {
            log.debug("Qdrant count 失败: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void deleteByProject(String projectId) {
        deleteWithFilter(projectFilter(projectId), "projectId=" + projectId);
    }

    @Override
    public void deleteByDocument(String documentId) {
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        ArrayNode must = body.putObject("filter").putArray("must");
        must.addObject().put("key", "documentId").putObject("match").put("value", documentId);
        deleteWithFilter(body, "documentId=" + documentId);
    }

    @Override
    public boolean available() {
        try {
            http.get(base() + "/collections", 4000);
            return true;
        } catch (Exception e) {
            log.debug("Qdrant 不可达: {}", e.getMessage());
            return false;
        }
    }

    private void deleteWithFilter(ObjectNode body, String desc) {
        try {
            ensureCollection();
            http.postJson(base() + "/collections/" + cfg.getCollection() + "/points/delete?wait=true",
                    JsonUtils.write(body), cfg.getTimeoutMs());
            log.info("已删除 Qdrant 向量: {}", desc);
        } catch (Exception e) {
            log.warn("删除 Qdrant 向量失败({}): {}", desc, e.getMessage());
        }
    }

    private ObjectNode projectFilter(String projectId) {
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        ArrayNode must = body.putObject("filter").putArray("must");
        must.addObject().put("key", "projectId").putObject("match").put("value", projectId);
        return body;
    }

    private String base() {
        String url = cfg.getBaseUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 把任意字符串 id 规范成确定的 UUID（Qdrant 对非 UUID 字符串会报错） */
    private String toUuid(String id) {
        try {
            return java.util.UUID.fromString(id).toString();
        } catch (Exception ignored) {
            return java.util.UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        }
    }

    /** 供健康检查使用 */
    public Map<String, Object> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            String raw = http.get(base() + "/collections", 4000);
            info.put("reachable", true);
            info.put("collections", JsonUtils.parse(raw).path("result").path("collections").size());
        } catch (Exception e) {
            info.put("reachable", false);
            info.put("error", e.getMessage());
        }
        return info;
    }
}
