package com.consense.service.advice;

import com.consense.ai.AiGateway;
import com.consense.ai.ModelIdentity;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.config.ConsenseProperties;
import com.consense.domain.ChatMessage;
import com.consense.domain.EvidenceChunk;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.repository.ChatMessageRepository;
import com.consense.repository.EvidenceChunkRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.prompt.PromptCatalog;
import com.consense.service.prompt.PromptService;
import com.consense.vector.VectorStore;
import com.consense.web.dto.AdviceDtos.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 咨询模块：证据索引 + 检索增强问答。
 *
 * 对应设计稿的 guardrail：回答必须带条款或摘录依据，
 * 检索不到依据时返回 "I don't know"，不做任何补全与推断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdviceService {

    private final ProjectService projectService;
    private final AiGateway ai;
    private final VectorStore vectorStore;
    private final OcrClient ocrClient;
    private final ConsenseProperties props;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final EvidenceChunkRepository chunkRepository;
    private final ChatMessageRepository chatMessageRepository;
    /** 提示词从数据库取（支持前端在线编辑），DB 无值时回退出厂默认 */
    private final PromptService promptService;

    // ------------------------------------------------------------ 索引

    @Transactional
    public IndexStatusVO rebuildIndex(String projectId) {
        projectService.require(projectId);
        List<SourceDocument> documents = sourceDocumentRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(d -> !JsonUtils.isBlankText(d.getTextContent()))
                .collect(Collectors.toList());
        if (documents.isEmpty()) {
            throw new BizException(4200, "该项目还没有可索引的文档，请先上传模板、证据或招标文件");
        }
        if (!vectorStore.available()) {
            throw new BizException(4201, "向量库不可用（" + props.getVector().getBaseUrl()
                    + "）。请启动 Qdrant，或把 consense.vector.provider 改为 memory 先跑通流程。");
        }

        vectorStore.deleteByProject(projectId);
        chunkRepository.deleteByProjectId(projectId);

        int chunkSize = props.getVector().getChunkSize();
        int overlap = props.getVector().getChunkOverlap();
        int total = 0;
        int index = 0;

        for (SourceDocument document : documents) {
            List<String> pieces = split(document.getTextContent(), chunkSize, overlap);
            if (pieces.isEmpty()) {
                continue;
            }
            List<float[]> vectors = ai.embed(pieces);
            List<EvidenceChunk> chunks = new ArrayList<>();
            List<VectorStore.VectorPoint> points = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) {
                EvidenceChunk chunk = new EvidenceChunk();
                chunk.setProjectId(projectId);
                chunk.setDocumentId(document.getId());
                chunk.setChunkIndex(index++);
                chunk.setFileLabel(nvl(document.getFileKey()) + " · " + nvl(document.getFileName()));
                chunk.setPageNo(pageOf(document, i, pieces.size()));
                chunk.setAnchor(anchorOf(pieces.get(i)));
                chunk.setContent(pieces.get(i));
                chunk.setCreatedAt(Instant.now());
                chunkRepository.save(chunk);

                String pointId = document.getId() + "-" + i;
                chunk.setPointId(pointId);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("projectId", projectId);
                payload.put("documentId", String.valueOf(document.getId()));
                payload.put("fileLabel", chunk.getFileLabel());
                payload.put("pageNo", chunk.getPageNo());
                payload.put("anchor", nvl(chunk.getAnchor()));
                payload.put("content", pieces.get(i));
                points.add(new VectorStore.VectorPoint(pointId,
                        i < vectors.size() ? vectors.get(i) : new float[0], payload));
                chunks.add(chunk);
            }
            chunkRepository.saveAll(chunks);
            if (!points.isEmpty()) {
                vectorStore.upsert(points);
            }
            total += pieces.size();
        }
        log.info("项目 {} 证据索引重建完成，共 {} 个切片", projectId, total);
        return status(projectId);
    }

    public IndexStatusVO status(String projectId) {
        projectService.require(projectId);
        int chunks = chunkRepository.findByProjectIdOrderByChunkIndexAsc(projectId).size();
        boolean vectorReady = vectorStore.available();
        int indexed = vectorReady ? vectorStore.count(projectId) : 0;
        boolean llmReady = ai.available();
        boolean ocrReady = props.getOcr().isEnabled() && ocrClient.available();

        LocalizedText message;
        if (chunks == 0) {
            message = LocalizedText.of("尚未建立证据索引，请先点击「刷新证据索引」。",
                    "尚未建立證據索引，請先點擊「刷新證據索引」。",
                    "No evidence index yet — click 'Refresh evidence index' to build one.");
        } else if (!vectorReady) {
            message = LocalizedText.of("向量库不可达，检索将无法工作。",
                    "向量庫不可達，檢索將無法工作。",
                    "Vector store unreachable; retrieval is unavailable.");
        } else {
            message = LocalizedText.of("索引就绪，共 " + chunks + " 个切片。",
                    "索引就緒，共 " + chunks + " 個切片。",
                    chunks + " chunks indexed and ready.");
        }
        return new IndexStatusVO(chunks, indexed,
                props.getVector().getProvider(), props.getVector().getCollection(),
                ai.embedModel(), ai.chatModel(), llmReady, vectorReady, ocrReady, message);
    }

    // ------------------------------------------------------------ 问答

    @Transactional
    public AskResponseVO ask(String projectId, AskRequest request) {
        Project project = projectService.require(projectId);
        String question = request == null ? "" : nvl(request.getQuestion()).trim();
        if (question.isEmpty()) {
            throw new BizException(4202, "请先输入问题");
        }
        String scope = JsonUtils.isBlankText(request.getScope()) ? "fullset" : request.getScope();

        // Configuration errors are model-action errors even when no index has been built.
        if (ai.explicitProfileSelected() && ai.capture().getUnavailableReason() != null) {
            ai.requireAvailable();
        }

        saveMessage(projectId, ChatMessage.ROLE_USER, null, question, null, null, null, null);

        List<VectorStore.SearchHit> hits = retrieve(projectId, question);
        List<CitationVO> sources = hits.stream().map(this::toCitation).collect(Collectors.toList());

        if (hits.isEmpty()) {
            AskResponseVO response = unknown(projectId, scope, sources, Collections.<String>emptyList(), false);
            persistAssistant(projectId, response);
            return response;
        }

        if (ai.explicitProfileSelected()) {
            ai.requireAvailable();
        }

        String excerpts = renderExcerpts(hits);
        String userPrompt = PromptService.format(promptService.userTemplate(PromptCatalog.KEY_ADVICE),
                scopeLabel(scope), excerpts, question);

        AnswerRecord answer;
        try {
            answer = ai.completeJson(promptService.system(PromptCatalog.KEY_ADVICE), userPrompt, AnswerRecord.class);
        } catch (Exception e) {
            // External exception text may contain credentials; retain only the safe failure type/profile.
            log.warn("Advice model call failed ({}).", e.getClass().getSimpleName());
            ModelIdentity selected = ai.modelIdentity();
            throw new BizException(4203, "Selected LLM profile "
                    + (selected == null ? "legacy deployment" : selected.getProfileId())
                    + " failed; the operation was not rerouted.");
        }

        if (answer == null || !answer.isGrounded() || JsonUtils.isBlankText(answer.getAnswer())) {
            List<String> extra = answer != null && answer.getMissingReason() != null
                    ? Arrays.asList(answer.getMissingReason())
                    : Collections.<String>emptyList();
            AskResponseVO response = unknown(projectId, scope, sources, extra, true);
            persistAssistant(projectId, response);
            return response;
        }

        List<String> citations = answer.getCitations() == null || answer.getCitations().isEmpty()
                ? sources.stream().map(CitationVO::getFileLabel).distinct().limit(3).collect(Collectors.toList())
                : answer.getCitations();

        AskResponseVO response = new AskResponseVO(true,
                LocalizedText.of("带依据的回答", "帶依據的回答", "Answer with basis"),
                LocalizedText.same(answer.getAnswer()),
                null,
                citations,
                evidenceIdOf(sources),
                sources,
                ai.chatModel(), ai.modelIdentity());
        persistAssistant(projectId, response);
        log.info("项目 {} 问答完成，命中 {} 条证据", projectId, hits.size());
        return response;
    }

    /**
     * 混合检索：
     *  1) 证据量小（总字符 ≤ stuffingLimit）→ 全量喂入（stuffing），跳过向量检索，
     *     检索环节零误差，所有切片连同引用信息直接进 prompt；
     *  2) 证据量大 → 向量检索，topK 提到 retrieveTopKFloor（默认 20）扩大召回，
     *     弱化"检索没捞到导致模型看不到"的问题。
     */
    private List<VectorStore.SearchHit> retrieve(String projectId, String question) {
        List<EvidenceChunk> chunks = chunkRepository.findByProjectIdOrderByChunkIndexAsc(projectId);
        if (chunks.isEmpty()) {
            throw new BizException(4204, "该项目尚未建立证据索引，请先在咨询页点击「刷新证据索引」");
        }

        int totalChars = 0;
        for (EvidenceChunk chunk : chunks) {
            totalChars += nvl(chunk.getContent()).length();
        }
        if (totalChars <= props.getVector().getStuffingLimit()) {
            log.info("项目 {} 证据总量 {} 字符（≤ {}），全量喂入（stuffing），共 {} 个切片",
                    projectId, totalChars, props.getVector().getStuffingLimit(), chunks.size());
            return chunks.stream().map(this::chunkToHit).collect(Collectors.toList());
        }

        // 大证据量：走向量检索，扩召回
        if (!ai.embeddingAvailable()) {
            if (ai.explicitProfileSelected()) {
                throw new BizException(4203,
                        "The deployment embedding adapter is unavailable; the selected chat profile was not rerouted.");
            }
            return Collections.emptyList();
        }
        if (!vectorStore.available()) {
            return Collections.emptyList();
        }
        List<float[]> vectors = ai.embed(Collections.singletonList(question));
        if (vectors.isEmpty() || vectors.get(0).length == 0) {
            return Collections.emptyList();
        }
        int topK = Math.max(props.getVector().getTopK(), props.getVector().getRetrieveTopKFloor());
        return vectorStore.search(projectId, vectors.get(0), topK, props.getVector().getScoreThreshold());
    }

    /** EvidenceChunk → SearchHit（stuffing 模式构造全量命中，score 置 1.0） */
    private VectorStore.SearchHit chunkToHit(EvidenceChunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", chunk.getProjectId());
        payload.put("documentId", String.valueOf(chunk.getDocumentId()));
        payload.put("fileLabel", nvl(chunk.getFileLabel()));
        payload.put("pageNo", chunk.getPageNo());
        payload.put("anchor", nvl(chunk.getAnchor()));
        payload.put("content", nvl(chunk.getContent()));
        return new VectorStore.SearchHit(nvl(chunk.getPointId()), 1.0, payload);
    }

    private AskResponseVO unknown(String projectId, String scope, List<CitationVO> sources,
                                  List<String> extraReasons, boolean chatDispatched) {
        List<String> citations = new ArrayList<>();
        if (sources.isEmpty()) {
            citations.add("Searched selected scope · no basis");
        } else {
            sources.stream().map(CitationVO::getFileLabel).distinct().limit(3).forEach(citations::add);
        }
        citations.addAll(extraReasons);
        return new AskResponseVO(false,
                LocalizedText.of("I don't know", "I don't know", "I don't know"),
                LocalizedText.empty(),
                scope,
                citations,
                evidenceIdOf(sources),
                sources,
                chatDispatched ? ai.chatModel() : null, chatDispatched ? ai.modelIdentity() : null);
    }

    /**
     * 与设计稿 unknownAnswer(scope) 完全一致的兜底文案，由前端按当前语言取值。
     */
    public static LocalizedText unknownAnswerText(String scopeKey, String scopeEn) {
        return LocalizedText.of(
                "在选定范围（" + scopeKey + "）内未找到充分条款依据。系统不会超出合约包进行推断。",
                "在選定範圍（" + scopeKey + "）內未找到充分條款依據。系統不會超出合約包進行推斷。",
                "No sufficient provision was found in the selected scope (" + scopeEn
                        + "). The system will not infer beyond the contract package.");
    }

    private String scopeLabel(String scope) {
        if ("tender".equals(nvl(scope))) {
            return "Tender documents only / 仅招标文件";
        }
        if ("contract".equals(nvl(scope))) {
            return "Conditions of Contract only / 仅合约条件";
        }
        return "Selected package conditions / 选定合约条件";
    }

    private String renderExcerpts(List<VectorStore.SearchHit> hits) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (VectorStore.SearchHit hit : hits) {
            builder.append("[").append(index++).append("] ")
                    .append(hit.fileLabel())
                    .append("  ").append(hit.pageNo())
                    .append("  ").append(hit.anchor())
                    .append("\n").append(hit.text()).append("\n\n");
        }
        return builder.toString().trim();
    }

    private CitationVO toCitation(VectorStore.SearchHit hit) {
        return new CitationVO(hit.fileLabel(), hit.pageNo(), hit.anchor(), hit.text(), hit.getScore());
    }

    private String evidenceIdOf(List<CitationVO> sources) {
        return sources.isEmpty() ? null : "chunk:" + sources.get(0).getFileLabel();
    }

    // ------------------------------------------------------------ 历史

    public List<ChatMessageVO> history(String projectId) {
        projectService.require(projectId);
        List<ChatMessageVO> result = new ArrayList<>();
        for (ChatMessage message : chatMessageRepository.findByProjectIdOrderByIdAsc(projectId)) {
            List<String> citations = message.getCitationsJson() == null
                    ? Collections.<String>emptyList()
                    : safeList(message.getCitationsJson());
            result.add(new ChatMessageVO(message.getId(),
                    message.getRole(),
                    message.getTitle() == null ? null : LocalizedText.same(message.getTitle()),
                    message.getContent() == null ? null : LocalizedText.same(message.getContent()),
                    message.getUnknownScope(),
                    citations,
                    message.getEvidenceId(),
                    Collections.<CitationVO>emptyList(),
                    message.getModelIdentityJson() == null ? null
                            : JsonUtils.read(message.getModelIdentityJson(), ModelIdentity.class)));
        }
        return result;
    }

    @Transactional
    public void clearHistory(String projectId) {
        projectService.require(projectId);
        chatMessageRepository.deleteByProjectId(projectId);
    }

    public List<QuickQuestionVO> quickQuestions(String projectId) {
        return Arrays.asList(
                new QuickQuestionVO(LocalizedText.of(
                        "SCC4.1 下的文件优先次序是什么？",
                        "SCC4.1 下的文件優先次序是什麼？",
                        "What is the order of precedence under SCC4.1?"), Boolean.FALSE, "SCC4.1"),
                new QuickQuestionVO(LocalizedText.of(
                        "信封一与信封二在投标提交中如何使用？",
                        "信封一與信封二在投標提交中如何使用？",
                        "How are Envelope 1 and Envelope 2 used in the tender submission?"), Boolean.FALSE, "SCT5"),
                new QuickQuestionVO(LocalizedText.of(
                        "本文件包中 L10Pro 的用途是什么？",
                        "本文件包中 L10Pro 的用途是什麼？",
                        "What is L10Pro used for in this package?"), Boolean.FALSE, "L10Pro"),
                new QuickQuestionVO(LocalizedText.of(
                        "平台转换层需要什么混凝土等级？",
                        "平台轉換層需要什麼混凝土等級？",
                        "What concrete grade is required for the podium transfer slab?"), Boolean.TRUE, null),
                new QuickQuestionVO(LocalizedText.of(
                        "这份组装好的合约文件包属于哪个项目？",
                        "這份組裝好的合約文件包屬於哪個項目？",
                        "Which project does this assembled contract package relate to?"), Boolean.FALSE, null));
    }

    // ------------------------------------------------------------ 内部

    private void saveMessage(String projectId, String role, String title, String content,
                             String unknownScope, List<String> citations, String evidenceId, ModelIdentity modelIdentity) {
        ChatMessage message = new ChatMessage();
        message.setProjectId(projectId);
        message.setRole(role);
        message.setTitle(title);
        message.setContent(content);
        message.setUnknownScope(unknownScope);
        message.setCitationsJson(citations == null ? null : JsonUtils.write(citations));
        message.setEvidenceId(evidenceId);
        message.setModelIdentityJson(modelIdentity == null ? null : JsonUtils.write(modelIdentity));
        message.setCreatedAt(Instant.now());
        chatMessageRepository.save(message);
    }

    private void persistAssistant(String projectId, AskResponseVO response) {
        String content = response.isGrounded()
                ? (response.getContent() == null ? "" : response.getContent().getZhHans())
                : null;
        saveMessage(projectId, ChatMessage.ROLE_ASSISTANT,
                response.getTitle() == null ? null : response.getTitle().getZhHans(),
                content,
                response.getUnknownScope(),
                response.getCitations(),
                response.getEvidenceId(), response.getModelIdentity());
    }

    private List<String> safeList(String json) {
        try {
            return JsonUtils.readList(json, String.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 按段落切块，超长段落再按字符切，保留 overlap 重叠 */
    private List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (JsonUtils.isBlankText(text)) {
            return chunks;
        }
        String[] paragraphs = text.split("\\n{2,}");
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String block = paragraph.trim();
            if (block.isEmpty()) {
                continue;
            }
            if (block.length() > chunkSize) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                for (int i = 0; i < block.length(); i += Math.max(1, chunkSize - overlap)) {
                    chunks.add(block.substring(i, Math.min(block.length(), i + chunkSize)));
                    if (i + chunkSize >= block.length()) {
                        break;
                    }
                }
                continue;
            }
            if (current.length() + block.length() + 2 > chunkSize) {
                chunks.add(current.toString().trim());
                String tail = current.length() > overlap
                        ? current.substring(current.length() - overlap)
                        : current.toString();
                current.setLength(0);
                current.append(tail).append("\n\n");
            }
            current.append(block).append("\n\n");
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks.stream().filter(c -> !c.trim().isEmpty()).collect(Collectors.toList());
    }

    private String pageOf(SourceDocument document, int index, int total) {
        if (document.getPageCount() != null && document.getPageCount() > 1 && total > 0) {
            int page = (int) Math.round((index + 1.0) / total * document.getPageCount());
            return "P" + Math.max(1, page);
        }
        return "P1";
    }

    /** 抽取切片里像条款编号的锚点，如 SCC4.1 / NTT3(b) */
    private String anchorOf(String content) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b((?:NTT|SCT|SCC|GCT|GCC|FT|AA|SL|PRE)\\s?\\d+(?:\\.\\d+)*(?:\\([a-z0-9]+\\))*)")
                .matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 模型返回的回答记录 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnswerRecord {
        private boolean grounded;
        private String answer;
        private List<String> citations;
        private String missingReason;
    }
}
