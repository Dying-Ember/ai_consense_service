package com.consense.vector;

import java.util.List;
import java.util.Map;

/**
 * 向量库抽象：默认 Qdrant，可切内存实现。
 */
public interface VectorStore {

    /** 集合不存在则创建 */
    void ensureCollection();

    void upsert(List<VectorPoint> points);

    List<SearchHit> search(String projectId, float[] query, int topK, double scoreThreshold);

    int count(String projectId);

    void deleteByProject(String projectId);

    void deleteByDocument(String documentId);

    boolean available();

    /** 入库点（构造器签名与原 record 一致） */
    final class VectorPoint {

        private final String id;
        private final float[] vector;
        private final Map<String, Object> payload;

        public VectorPoint(String id, float[] vector, Map<String, Object> payload) {
            this.id = id;
            this.vector = vector;
            this.payload = payload;
        }

        public String getId() {
            return id;
        }

        public float[] getVector() {
            return vector;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }
    }

    /** 检索命中（构造器签名与原 record 一致） */
    final class SearchHit {

        private final String id;
        private final double score;
        private final Map<String, Object> payload;

        public SearchHit(String id, double score, Map<String, Object> payload) {
            this.id = id;
            this.score = score;
            this.payload = payload;
        }

        public String getId() {
            return id;
        }

        public double getScore() {
            return score;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }

        public String text() {
            Object value = payload == null ? null : payload.get("content");
            return value == null ? "" : String.valueOf(value);
        }

        public String fileLabel() {
            Object value = payload == null ? null : payload.get("fileLabel");
            return value == null ? "" : String.valueOf(value);
        }

        public String pageNo() {
            Object value = payload == null ? null : payload.get("pageNo");
            return value == null ? "" : String.valueOf(value);
        }

        public String anchor() {
            Object value = payload == null ? null : payload.get("anchor");
            return value == null ? "" : String.valueOf(value);
        }
    }
}
