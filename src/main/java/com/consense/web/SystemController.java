package com.consense.web;

import com.consense.ai.AiGateway;
import com.consense.common.ApiResponse;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.consense.vector.VectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地依赖健康检查，前端顶栏据此提示"离线/在线"。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final AiGateway ai;
    private final OcrClient ocrClient;
    private final VectorStore vectorStore;
    private final ConsenseProperties props;

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean llm = ai.available();
        boolean ocr = props.getOcr().isEnabled() && ocrClient.available();
        boolean vector = vectorStore.available();

        Map<String, Object> llmInfo = new LinkedHashMap<>();
        com.consense.ai.LlmOperation selected=ai.capture();
        llmInfo.put("available", llm);
        llmInfo.put("provider", selected!=null&&selected.isExplicit()?selected.getIdentity().getProvider():props.getLlm().getProvider());
        llmInfo.put("baseUrl", selected!=null&&selected.isExplicit()?selected.getBaseUrl():props.getLlm().getBaseUrl());
        llmInfo.put("chatModel", ai.chatModel());
        llmInfo.put("embedModel", ai.embedModel());

        Map<String, Object> ocrInfo = new LinkedHashMap<>();
        ocrInfo.put("available", ocr);
        ocrInfo.put("enabled", props.getOcr().isEnabled());
        ocrInfo.put("baseUrl", props.getOcr().getBaseUrl());
        ocrInfo.put("mode", props.getOcr().getMode());

        Map<String, Object> vectorInfo = new LinkedHashMap<>();
        vectorInfo.put("available", vector);
        vectorInfo.put("provider", props.getVector().getProvider());
        vectorInfo.put("baseUrl", props.getVector().getBaseUrl());
        vectorInfo.put("collection", props.getVector().getCollection());

        result.put("llm", llmInfo);
        result.put("ocr", ocrInfo);
        result.put("vector", vectorInfo);
        result.put("storageRoot", props.getStorageRoot());
        result.put("ready", llm && vector);
        return ApiResponse.ok(result);
    }
}
