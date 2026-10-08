package com.consense.ai;
import com.consense.config.ConsenseProperties;
import lombok.Getter;

/** Immutable chat selection captured once at action entry and transferable to a queued worker. */
@Getter
public final class LlmOperation {
    private final boolean explicit;
    private final LlmClient client;
    private final ModelIdentity identity;
    private final String unavailableReason;
    private final String baseUrl;
    private final int structuredMaxTokens,numCtx;
    LlmOperation(boolean explicit,LlmClient client,ModelIdentity identity,String unavailableReason,ConsenseProperties.Llm cfg) {
        this.explicit=explicit;this.client=client;this.identity=identity;this.unavailableReason=unavailableReason;
        this.structuredMaxTokens=cfg.getStructuredMaxTokens();this.numCtx=cfg.getNumCtx();this.baseUrl=cfg.getBaseUrl();
    }
}
