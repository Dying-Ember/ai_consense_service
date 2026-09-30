package com.consense.vector;

import com.consense.config.ConsenseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无 Qdrant 时的内存兜底：重启即失效，仅用于跑通流程。
 */
@Slf4j
@RequiredArgsConstructor
public class InMemoryVectorStore implements VectorStore {

    private final ConsenseProperties.VectorCfg cfg;
    private final Map<String, VectorPoint> store = new ConcurrentHashMap<>();

    @Override
    public void ensureCollection() {
        // no-op
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        for (VectorPoint point : points) {
            store.put(point.getId(), point);
        }
    }

    @Override
    public List<SearchHit> search(String projectId, float[] query, int topK, double scoreThreshold) {
        List<SearchHit> hits = new ArrayList<>();
        for (VectorPoint point : store.values()) {
            Object pid = point.getPayload().get("projectId");
            if (pid == null || !projectId.equals(String.valueOf(pid))) {
                continue;
            }
            double score = cosine(query, point.getVector());
            if (score >= scoreThreshold) {
                hits.add(new SearchHit(point.getId(), score, point.getPayload()));
            }
        }
        hits.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }

    @Override
    public int count(String projectId) {
        return (int) store.values().stream()
                .filter(p -> projectId.equals(String.valueOf(p.getPayload().get("projectId"))))
                .count();
    }

    @Override
    public void deleteByProject(String projectId) {
        store.entrySet().removeIf(e -> projectId.equals(String.valueOf(e.getValue().getPayload().get("projectId"))));
    }

    @Override
    public void deleteByDocument(String documentId) {
        store.entrySet().removeIf(e -> documentId.equals(String.valueOf(e.getValue().getPayload().get("documentId"))));
    }

    @Override
    public boolean available() {
        return true;
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
