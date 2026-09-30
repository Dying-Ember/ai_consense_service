package com.consense.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部本地服务对接参数集中在这里，方便按本机环境（Ollama / PaddleOCR / Qdrant）调整。
 */
@Data
@ConfigurationProperties(prefix = "consense")
public class ConsenseProperties {

    /** 上传文件落盘目录 */
    private String storageRoot = "./data/uploads";

    private Cors cors = new Cors();
    private Llm llm = new Llm();
    private Ocr ocr = new Ocr();
    private VectorCfg vector = new VectorCfg();
    private Vetting vetting = new Vetting();

    @Data
    public static class Cors {
        private String allowedOrigins = "http://localhost:5173";
    }

    /** 本地大模型（默认 Ollama） */
    @Data
    public static class Llm {
        private boolean enabled = true;
        /** ollama | openai */
        private String provider = "ollama";
        private String baseUrl = "http://localhost:11434";
        private String chatModel = "qwen2.5:14b";
        private String embedModel = "bge-m3";
        private double temperature = 0.2;
        private int numCtx = 8192;
        private long timeoutMs = 300_000L;
        private int maxRetry = 2;
        private String apiKey = "";
    }

    /** PaddleOCR / PP-Structure 服务 */
    @Data
    public static class Ocr {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:8868";
        /** classic | hubserving */
        private String mode = "classic";
        private String ocrPath = "/predict/ocr_system";
        private String structurePath = "/predict/structure_system";
        private long timeoutMs = 180_000L;
        /** PDF 文本层字符数低于该阈值时转 OCR */
        private int textLayerMinChars = 40;
        private int maxOcrPages = 60;
    }

    /** 向量库 */
    @Data
    public static class VectorCfg {
        /** qdrant | memory */
        private String provider = "qdrant";
        private String baseUrl = "http://localhost:6333";
        private String apiKey = "";
        private String collection = "consense_evidence";
        private int vectorSize = 1024;
        private String distance = "Cosine";
        private long timeoutMs = 60_000L;
        private int chunkSize = 800;
        private int chunkOverlap = 120;
        private int topK = 6;
        private double scoreThreshold = 0.35;
        /** 检索兜底扩召回：向量检索路径实际使用的 topK 下限 */
        private int retrieveTopKFloor = 20;
        /** 全量喂入（stuffing）阈值：项目证据总字符数 ≤ 该值时跳过向量检索，全部切片直接进 prompt */
        private int stuffingLimit = 30000;
    }

    /** 业务规则 */
    @Data
    public static class Vetting {
        private int maxRiskFindings = 10;
        private boolean diffAgainstBaseline = true;
    }
}
