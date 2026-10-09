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
    /** Separate server-owned China Token Plan chat settings; never the embedding deployment. */
    private Llm minimaxCn = miniMaxDefaults();
    private Ocr ocr = new Ocr();
    private Document document = new Document();
    private VectorCfg vector = new VectorCfg();
    private Vetting vetting = new Vetting();
    private Drafting drafting = new Drafting();

    private static Llm miniMaxDefaults() {
        Llm cfg=new Llm();cfg.setProvider("openai");cfg.setBaseUrl("https://api.minimax.cn");cfg.setChatModel("MiniMax-M3");
        cfg.setEmbedModel("");cfg.setStructuredOpenAiThinkingMode("disabled");cfg.setStructuredOpenAiReasoningSplit(true);
        cfg.setStructuredMaxTokens(16384);return cfg;
    }

    @Data
    public static class Drafting {
        /** Explicit renderer; LibreOffice remains the default. No discovery or fallback. */
        private String rendererExecutable;
        private String rendererKind="libreoffice";
        private String rendererAssetRoot;
        private String rendererFontCache;
        private String rendererWorkRoot;
        private long rendererTimeoutMs=120000;
    }

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
        /** Optional Ollama thinking control for structured calls; null preserves the model default. */
        private Boolean structuredThinking;
        /** Optional OpenAI structured-call extension: null omits thinking; adaptive or disabled only. */
        private String structuredOpenAiThinkingMode;
        /** Optional OpenAI structured-call extension; null omits reasoning_split. */
        private Boolean structuredOpenAiReasoningSplit;
        /** Explicit output cap for vetting calls; includes no implicit server default. */
        private int structuredMaxTokens = 2048;
        private long timeoutMs = 300_000L;
        private int maxRetry = 2;
        /** MiniMax only: the direct provider owns overload retry; turn off behind a retrying relay. */
        private boolean overloadRetryEnabled = true;
        @lombok.ToString.Exclude
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
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

    /** DOCX expanded-byte limits; these may tighten the parser's fixed safety ceilings. */
    @Data
    public static class Document {
        /** Optional parse/source-construction observation root; explicit job/source identities required. */
        private String probeDirectory;
        private long maxZipEntryBytes = 128L * 1024 * 1024;
        private long maxZipXmlBytes = 32L * 1024 * 1024;
        private long maxZipTotalBytes = 256L * 1024 * 1024;
        private int maxZipEntries = 5000;
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
        /** Default-off vetting-only wire. Drafting and the shared Llm configuration are unchanged. */
        private Responses responses = new Responses();
        private int maxRiskFindings = 10;
        private boolean diffAgainstBaseline = true;
        private boolean semanticEnabled = true;
        private String retrievalUrl = "http://localhost:8868";
        private long retrievalTimeoutMs = 180_000;
        /** First-time corpus embedding/indexing can take much longer than one query. */
        private long retrievalIndexTimeoutMs = 1_800_000;
        private int semanticTopics = 16;
        private int semanticContextChars = 10000;
        /** Optional bounded literal-reference expansion; does not imply full contract coverage. */
        private int semanticContextExpansionChars = 20000;
        /** Opt-in local, sealed complete-input token counter. No shell, model discovery or estimates. */
        private boolean tokenBudgetObserverEnabled = false;
        private java.util.List<String> tokenBudgetObserverCommand = new java.util.ArrayList<>();
        private String tokenBudgetObserverRecipePath;
        private String tokenBudgetObserverRecipeSha256;
        private long tokenBudgetObserverTimeoutMs = 10000;
        private int tokenBudgetObserverMaxOutputBytes = 1048576;
        /** Additional comparisons nominated by explicit project-message clause references. */
        private int projectReferenceComparisons = 16;
        private int projectReferenceContextChars = 12000;
        private int candidateLimit = 50;
        private int topK = 10;
        /** Opt-in: never replace failed/invalid hybrid retrieval with lexical results. */
        private boolean requireHybridRetrieval = false;
        /** Required local wire contract; corpus/signature identity is still per-project. */
        private String expectedRetrievalContract = "source-bound-window-points-full-parent-results-v1";
        private int expectedRetrievalSignatureVersion = 3;
        private int expectedEmbeddingWindowMaxTokens = 512;
        private int expectedRerankWindowMaxTokens = 512;
        private int expectedWindowOverlapTokens = 64;
        /** Optional externally frozen public health recipe fingerprint; never a model answer. */
        private String expectedRetrievalRecipeFingerprint;
        /** Optional externally frozen index signature, e.g. the approved GPU cache seal. */
        private String expectedRetrievalSignature;
        /** Optional absolute/local output root for run-bound source and gate snapshots. */
        private String probeDirectory;
        /** Optional fresh export-operation observations, distinct from review and parse probes. */
        private String exportProbeDirectory;
        /** Default-off, non-interfering rules/filter/persistence lifecycle observations. */
        private String serviceProbeDirectory;
    }

    @Data
    public static class Responses {
        private boolean enabled = false;
        private String baseUrl = "https://api.minimax.cn";
        private String model = "MiniMax-M3";
        private double temperature = 0.2;
        private int maxOutputTokens = 16384;
        /** For M3, high enables reasoning; it is not a claim of adjustable reasoning depth. */
        private String reasoningEffort = "high";
        private long counterTimeoutMs = 10000, generationTimeoutMs = 300000;
        private int counterMaxResponseBytes = 1048576, generationMaxResponseBytes = 4194304;
        /** Explicit deployment policy, never an observed provider maximum. Zero/null stays unknown. */
        private long configuredContextTokens = 0, safetyMarginTokens = 0;
        private String estimatePolicy;
        /** Bind from deployment configuration/environment; never included in public identities or logs. */
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
        private String apiKey = "";
    }
}
