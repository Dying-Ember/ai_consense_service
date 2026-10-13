package com.consense.ai;

/** Optional adapter capability: the configured embedding model has its own readiness check. */
public interface EmbeddingAvailability {
    boolean embeddingAvailable();
}
