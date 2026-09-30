package com.consense.config;

import com.consense.ai.HttpSupport;
import com.consense.ai.LlmClient;
import com.consense.ai.OllamaLlmClient;
import com.consense.ai.OpenAiLlmClient;
import com.consense.ocr.OcrClient;
import com.consense.ocr.PaddleOcrClient;
import com.consense.vector.InMemoryVectorStore;
import com.consense.vector.QdrantVectorStore;
import com.consense.vector.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地 AI 依赖装配：大模型 / OCR / 向量库的 provider 由 application.yml 决定。
 */
@Slf4j
@Configuration
public class AiConfig {

    @Bean
    public LlmClient llmClient(ConsenseProperties props, HttpSupport http) {
        ConsenseProperties.Llm llm = props.getLlm();
        if ("openai".equalsIgnoreCase(llm.getProvider())) {
            log.info("LLM provider = openai-compatible, baseUrl={}", llm.getBaseUrl());
            return new OpenAiLlmClient(llm, http);
        }
        log.info("LLM provider = ollama, baseUrl={}, chatModel={}, embedModel={}",
                llm.getBaseUrl(), llm.getChatModel(), llm.getEmbedModel());
        return new OllamaLlmClient(llm, http);
    }

    @Bean
    public OcrClient ocrClient(ConsenseProperties props, HttpSupport http) {
        ConsenseProperties.Ocr ocr = props.getOcr();
        log.info("OCR provider = paddle, mode={}, baseUrl={}", ocr.getMode(), ocr.getBaseUrl());
        return new PaddleOcrClient(ocr, http);
    }

    @Bean
    public VectorStore vectorStore(ConsenseProperties props, HttpSupport http) {
        ConsenseProperties.VectorCfg cfg = props.getVector();
        if ("memory".equalsIgnoreCase(cfg.getProvider())) {
            log.warn("向量库使用内存实现（重启后索引丢失），baseUrl={} 未启用", cfg.getBaseUrl());
            return new InMemoryVectorStore(cfg);
        }
        log.info("向量库 provider = qdrant, baseUrl={}, collection={}", cfg.getBaseUrl(), cfg.getCollection());
        return new QdrantVectorStore(cfg, http);
    }
}
