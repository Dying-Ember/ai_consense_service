package com.consense.service.vetting;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Authored CUDA health declaration derived from a retained historical CPU capture; no CUDA model was invoked. */
final class OerCudaHealthFixtures {
    private OerCudaHealthFixtures() {}

    static void apply(ObjectNode health) {
        health.put("device", "cuda");
        for(String kind:new String[]{"embedding","reranker"}) {
            ObjectNode runtime=(ObjectNode)health.path("runtime").path(kind);
            runtime.put("device", "cuda");
            runtime.put("dtype", "float32");
            runtime.put("serialOffload", true);
        }
        ((ObjectNode)health.path("retrieval").path("sourceWindows"))
                .set("rerankRuntime", health.path("runtime").path("reranker").deepCopy());
    }
}
