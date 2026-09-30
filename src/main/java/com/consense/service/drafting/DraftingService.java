package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.document.DocumentParser;
import com.consense.domain.DraftDocument;
import com.consense.domain.DraftVariable;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.DraftDocumentRepository;
import com.consense.repository.DraftVariableRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.prompt.PromptCatalog;
import com.consense.service.prompt.PromptService;
import com.consense.web.dto.DraftingDtos.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 起草模块：
 *  第 1 步 标准模板 + 项目证据（上传解析）
 *  第 2 步 基础变量确认（变量由模型从解析文件中识别，无预置清单）
 *  第 3 步 分文件变量确认 + 一键生成文稿
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftingService {

    /**
     * 模板/证据送入模型的最大预算。
     * CPU 推理下 qwen2.5:7b + 8192 上下文：prompt 控制在 1.1 万字符内（约 4-5K token），
     * 留约 3K token 给输出；长模板靠决策片段聚焦提取保证质量，不靠堆全文。
     */
    private static final int MAX_DOC_CHARS = 8000;
    private static final int MAX_TEMPLATE_CHARS = 10000;
    private static final int MAX_EVIDENCE_CHARS = 3000;
    private static final int MAX_EVIDENCE_SNIPPET_CHARS = 2500;
    /**
     * 模板中的决策线索标记：命中的位置附近（条款正文 + 右侧/下方 Guidance Note 小字）
     * 优先送入模型，避免长模板从头截断把决策点全部砍掉。
     */
    private static final List<String> DECISION_MARKERS = Arrays.asList(
            "Guidance", "guidance", "NOTE:", "Option A", "Option B",
            "Not used", "not used", "Delete", "delete", "amend", "Amend",
            "alternative", "Alternat", "For use in", "For use where",
            "two-envelope", "envelope tendering", "e-Tender", "e-tender",
            "precast", "Precast", "nominated", "Nominated");
    private static final List<String> TEMPLATE_KEYS = Arrays.asList("NTT", "SCT", "SCC");
    private static final List<String> ACTIONS = Arrays.asList("fill", "choice", "rewrite", "delete", "notused");
    private static final double MIN_CONFIDENCE = 0.70;

    private final ProjectService projectService;
    private final StorageService storageService;
    private final DocumentParser documentParser;
    private final AiGateway ai;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final DraftVariableRepository variableRepository;
    private final DraftDocumentRepository documentRepository;
    /** 提示词从数据库取（支持前端在线编辑），DB 无值时回退出厂默认 */
    private final PromptService promptService;
    private final DraftDocPdfWriter pdfWriter;

    /** 最近一次变量识别的过程留痕（内存态，重启即空）：projectId → trace */
    private final Map<String, ExtractTraceVO> extractTraces = new java.util.concurrent.ConcurrentHashMap<>();

    /** 模型识别结果（Jackson 反序列化用 Lombok POJO） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiscoveredVariable {
        private String key;
        private String scope;
        private String fileKey;
        private String labelZh;
        private String labelZhHant;
        private String labelEn;
        private String action;
        private List<String> options;
        private String affects;
        private String value;
        private String reason;
        private String sourceQuote;
        private Double confidence;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeneratedDoc {
        private String title;
        private String content;
    }

    // ------------------------------------------------------------ 第 1 步：模板

    public List<TemplateVO> listTemplates(String projectId) {
        projectService.require(projectId);
        List<SourceDocument> documents =
                sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,
                        SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        Map<String, SourceDocument> byKey = new HashMap<>();
        documents.forEach(d -> byKey.put(d.getFileKey(), d));

        List<TemplateVO> result = new ArrayList<>();
        for (String key : TEMPLATE_KEYS) {
            SourceDocument document = byKey.get(key);
            boolean uploaded = document != null
                    && ("PARSED".equals(document.getParseStatus()) || "FAILED".equals(document.getParseStatus()));
            result.add(new TemplateVO(
                    key,
                    "STD-" + key,
                    LocalizedText.of("标准 " + key + " 模板", "標準 " + key + " 模板", "Standard " + key + " template"),
                    document == null ? defaultTemplateFileName(key) : document.getFileName(),
                    LocalizedText.of("起草基准模板，变量识别以此为准。",
                            "起草基準模板，變量識別以此為準。",
                            "Baseline template used for variable discovery."),
                    uploaded
                            ? LocalizedText.of("已上传", "已上傳", "Uploaded")
                            : LocalizedText.of("已预载", "已預載", "Preloaded"),
                    uploaded ? "ok" : "demo"));
        }
        return result;
    }

    @Transactional
    public UploadResultVO uploadTemplates(String projectId, List<MultipartFile> files) {
        projectService.require(projectId);
        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int failed = 0;

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            String fileKey = matchTemplateKey(file.getOriginalFilename());
            StorageService.StoredFile stored =
                    storageService.store(projectId, SourceDocument.CATEGORY_STANDARD_TEMPLATE, file);
            SourceDocument document = sourceDocumentRepository
                    .findFirstByProjectIdAndFileKey(projectId, fileKey)
                    .filter(d -> SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(d.getCategory()))
                    .orElseGet(SourceDocument::new);
            document.setProjectId(projectId);
            document.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
            document.setFileKey(fileKey);
            document.setFileName(stored.getOriginalName());
            document.setContentType(stored.getContentType());
            document.setSizeBytes(stored.getSize());
            document.setStoragePath(stored.getPath());
            document.setCreatedAt(Instant.now());

            try {
                DocumentParser.ParsedDocument result =
                        documentParser.parse(stored.getOriginalName(), storageService.read(stored.getPath()));
                document.setParseStatus("PARSED");
                document.setParseMessage(result.getMessage());
                document.setPageCount(result.getPageCount());
                document.setOcrUsed(result.isOcrUsed());
                document.setTextContent(result.getText());
                parsed++;
                messages.add(fileKey + " ← " + stored.getOriginalName() + " 已解析（"
                        + result.getText().length() + " 字符）");
            } catch (Exception e) {
                document.setParseStatus("FAILED");
                document.setParseMessage(e.getMessage());
                failed++;
                messages.add(stored.getOriginalName() + " 解析失败：" + e.getMessage());
            }
            sourceDocumentRepository.save(document);
        }
        return new UploadResultVO(files.size(), parsed, failed, messages);
    }

    // ------------------------------------------------------------ 第 1 步：证据

    public List<EvidenceVO> listInputs(String projectId) {
        projectService.require(projectId);
        return sourceDocumentRepository
                .findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_PROJECT_INPUT)
                .stream().map(this::toEvidenceVO).collect(Collectors.toList());
    }

    /**
     * 替换指定 key 的标准模板（第 1 步「替换 NTT / SCT / SCC」按钮）：
     * 与文件名无关，直接以调用方指定的 fileKey 覆盖已有模板并重新解析。
     * 注意：解析（含扫描件 OCR）可能耗时数十分钟，特意不在 @Transactional 内执行，
     * 否则长事务会占住数据库连接直到 MySQL wait_timeout 断连回滚（上传接口同风险）。
     * 这里拆成两次 repository.save（各自短事务）：先落基础行，解析完再落解析结果。
     */
    public UploadResultVO replaceTemplate(String projectId, String fileKey, MultipartFile file) {
        projectService.require(projectId);
        if (!TEMPLATE_KEYS.contains(fileKey)) {
            throw new BizException(4009, "未知模板 key: " + fileKey);
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(4010, "替换文件不能为空");
        }
        StorageService.StoredFile stored =
                storageService.store(projectId, SourceDocument.CATEGORY_STANDARD_TEMPLATE, file);
        SourceDocument document = sourceDocumentRepository
                .findFirstByProjectIdAndFileKey(projectId, fileKey)
                .filter(d -> SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(d.getCategory()))
                .orElseGet(SourceDocument::new);
        document.setProjectId(projectId);
        document.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        document.setFileKey(fileKey);
        document.setFileName(stored.getOriginalName());
        document.setContentType(stored.getContentType());
        document.setSizeBytes(stored.getSize());
        document.setStoragePath(stored.getPath());
        document.setCreatedAt(Instant.now());
        document.setParseStatus("PENDING");
        document.setParseMessage(null);
        document.setTextContent(null);
        sourceDocumentRepository.save(document);

        List<String> messages = new ArrayList<>();
        int parsed;
        int failed;
        try {
            DocumentParser.ParsedDocument result =
                    documentParser.parse(stored.getOriginalName(), storageService.read(stored.getPath()));
            document.setParseStatus("PARSED");
            document.setParseMessage(result.getMessage());
            document.setPageCount(result.getPageCount());
            document.setOcrUsed(result.isOcrUsed());
            document.setTextContent(result.getText());
            parsed = 1;
            failed = 0;
            messages.add(fileKey + " ← " + stored.getOriginalName() + " 已替换并解析（"
                    + result.getText().length() + " 字符）");
        } catch (Exception e) {
            document.setParseStatus("FAILED");
            document.setParseMessage(e.getMessage());
            parsed = 0;
            failed = 1;
            messages.add(stored.getOriginalName() + " 解析失败：" + e.getMessage());
        }
        sourceDocumentRepository.save(document);
        return new UploadResultVO(1, parsed, failed, messages);
    }

    @Transactional
    public UploadResultVO uploadInputs(String projectId, List<MultipartFile> files) {
        projectService.require(projectId);
        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int failed = 0;

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            StorageService.StoredFile stored =
                    storageService.store(projectId, SourceDocument.CATEGORY_PROJECT_INPUT, file);
            SourceDocument document = new SourceDocument();
            document.setProjectId(projectId);
            document.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);
            document.setFileName(stored.getOriginalName());
            document.setContentType(stored.getContentType());
            document.setSizeBytes(stored.getSize());
            document.setStoragePath(stored.getPath());
            document.setCreatedAt(Instant.now());
            try {
                DocumentParser.ParsedDocument result =
                        documentParser.parse(stored.getOriginalName(), storageService.read(stored.getPath()));
                document.setParseStatus("PARSED");
                document.setParseMessage(result.getMessage());
                document.setPageCount(result.getPageCount());
                document.setOcrUsed(result.isOcrUsed());
                document.setTextContent(result.getText());
                parsed++;
                messages.add(stored.getOriginalName() + " 已解析");
            } catch (Exception e) {
                document.setParseStatus("FAILED");
                document.setParseMessage(e.getMessage());
                failed++;
                messages.add(stored.getOriginalName() + " 解析失败：" + e.getMessage());
            }
            sourceDocumentRepository.save(document);
        }
        return new UploadResultVO(files.size(), parsed, failed, messages);
    }

    // ------------------------------------------------------------ 第 2/3 步：变量

    public List<VariableVO> listVariables(String projectId) {
        projectService.require(projectId);
        return variableRepository.findByProjectIdOrderBySortOrderAsc(projectId)
                .stream().map(this::toVariableVO).collect(Collectors.toList());
    }

    /** 最近一次变量识别的过程留痕（提示词 + 模型原始返回）；尚未识别过则返回 null */
    public ExtractTraceVO lastExtractTrace(String projectId) {
        projectService.require(projectId);
        return extractTraces.get(projectId);
    }

    public ProgressVO progress(String projectId) {
        List<DraftVariable> variables = variableRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        List<DraftVariable> base = variables.stream()
                .filter(v -> DraftVariable.SCOPE_BASE.equals(v.getScope())).collect(Collectors.toList());
        List<DraftVariable> file = variables.stream()
                .filter(v -> DraftVariable.SCOPE_FILE.equals(v.getScope())).collect(Collectors.toList());
        long baseConfirmed = base.stream().filter(v -> Boolean.TRUE.equals(v.getConfirmed())).count();
        long fileConfirmed = file.stream().filter(v -> Boolean.TRUE.equals(v.getConfirmed())).count();
        boolean baseReady = !base.isEmpty() && baseConfirmed == base.size();
        boolean allReady = baseReady && fileConfirmed == file.size();
        List<String> generated = documentRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(d -> Boolean.TRUE.equals(d.getGenerated()))
                .map(DraftDocument::getFileKey)
                .collect(Collectors.toList());

        int evidenceCount = sourceDocumentRepository
                .findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_PROJECT_INPUT).size();
        int templateCount = sourceDocumentRepository
                .findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_STANDARD_TEMPLATE).stream()
                .filter(d -> "PARSED".equals(d.getParseStatus())).collect(Collectors.toList()).size();

        return new ProgressVO(evidenceCount, templateCount,
                base.size(), (int) baseConfirmed, file.size(), (int) fileConfirmed,
                baseReady, allReady, generated);
    }

    /**
     * 变量识别：不依赖任何预置清单，把解析后的标准模板与项目证据交给模型，
     * 由模型识别需要决策的变量（key / 标签 / 类型 / 选项）并给出建议值与依据。
     * 已人工确认的变量保留不动；未确认的旧变量会被本轮识别结果替换。
     */
    @Transactional
    public List<VariableVO> extractVariables(String projectId) {
        Project project = projectService.require(projectId);
        String templates = collectTemplateText(projectId);
        String evidence = collectEvidenceText(projectId, SourceDocument.CATEGORY_PROJECT_INPUT);
        if (JsonUtils.isBlankText(templates) && JsonUtils.isBlankText(evidence)) {
            throw new BizException(4005, "请先上传标准模板或项目沟通证据，再进行变量识别");
        }

        // 注意：discovery 提示词可能被用户在线定制（库里那版只有 2 个 %s 占位符），
        // 所以项目信息不走模板占位符，而是在代码里固定前置，保证一定会传给模型
        // 针对 BASE 变量从全部证据中抽取关键句，避免 BQ Bill / 分包清单等信息因整体截断而丢失
        String baseSnippets = collectBaseEvidenceSnippets(projectId);

        String userPrompt = "【项目信息】\n"
                + "合约编号：" + nvl(project.getContractNo()) + "\n"
                + "项目名称：" + projectDisplayName(project) + "\n"
                + "\n"
                + PromptService.format(promptService.userTemplate(PromptCatalog.KEY_DISCOVER),
                        JsonUtils.isBlankText(templates) ? "（未上传标准模板）" : templates,
                        JsonUtils.isBlankText(evidence) ? "（未上传项目证据）" : evidence)
                + "\n\n【BASE 变量聚焦摘录（从全部证据中抽取的关键句，用于补全被截断遗漏的信息）】\n"
                + baseSnippets;
        String systemPrompt = PromptService.format(promptService.system(PromptCatalog.KEY_DISCOVER),
                DraftBlueprint.baseKeysAsString());

        // 收集模型的原始返回（含重试），用于前端展示识别过程
        List<String> rawResponses = new ArrayList<>();
        List<DiscoveredVariable> discovered =
                ai.completeJsonList(systemPrompt, userPrompt, DiscoveredVariable.class, rawResponses);
        if (discovered == null) {
            discovered = Collections.emptyList();
        }

        // 留痕：把本次发给模型的完整提示词与模型原始返回存入内存，供前端“识别过程”面板查看
        extractTraces.put(projectId, new ExtractTraceVO(ai.chatModel(),
                Instant.now().toString(), systemPrompt, userPrompt, rawResponses));

        // 把设计稿里的 5 个 BASE 变量预先入库（已存在则不动 value / options / kind / affects 等元数据），
        // 这样模型只填值即可，输出量减半；BASE 顺序按 DraftBlueprint.BASE_VARIABLES 排好
        ensureBaseVariables(projectId);

        List<DraftVariable> existing = variableRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        Map<String, DraftVariable> byKey = new HashMap<>();
        for (DraftVariable v : existing) {
            byKey.put(v.getVarKey(), v);
        }

        Set<String> hitKeys = new HashSet<>();
        int order = 0;
        List<DraftVariable> toSave = new ArrayList<>();
        int generatedKeys = 0;
        for (DiscoveredVariable item : discovered) {
            double confidence = item.getConfidence() == null ? 0.8 : item.getConfidence();
            String key = sanitizeKey(item.getKey());
            if (JsonUtils.isBlankText(key)) {
                key = String.format(Locale.ROOT, "VAR-%02d", ++generatedKeys);
            }
            // BASE 无依据时模型按提示词输出空 value + confidence 0：不能因此跳过，
            // 否则上一轮的旧值（如误抄的标签）永远留在库里；FILE 低置信度仍然丢弃。
            if (confidence < MIN_CONFIDENCE && !DraftBlueprint.isBaseKey(key)) {
                continue;
            }
            // 兜底：模型偷偷把非蓝图 key 标成 BASE（比如自己编了 contractNo / projectName）——直接丢弃，
            // 既不入库也不进 hitKeys，下一轮清理就会把它们删掉
            if (DraftVariable.SCOPE_BASE.equalsIgnoreCase(nvl(item.getScope())) && !DraftBlueprint.isBaseKey(key)) {
                log.debug("丢弃模型自编的 BASE key={}（不在 DraftBlueprint 里）", key);
                continue;
            }
            DraftVariable variable = byKey.get(key);
            if (variable != null && Boolean.TRUE.equals(variable.getConfirmed())) {
                // 已人工确认的变量不动，模型无权覆盖
                hitKeys.add(key);
                continue;
            }
            if (variable == null) {
                variable = new DraftVariable();
                variable.setProjectId(projectId);
                variable.setVarKey(key);
                variable.setConfirmed(false);
                byKey.put(key, variable);
            }
            if (DraftBlueprint.isBaseKey(key)) {
                // BASE 变量：元数据来自设计稿，模型只填 value / sourceQuote / note / confidence
                applyBaseFill(variable, item, confidence);
                DraftBlueprint.BaseSpec spec = DraftBlueprint.findBase(key);
                // sortOrder 永远跟着设计稿顺序走
                variable.setSortOrder(DraftBlueprint.BASE_VARIABLES.indexOf(spec));
            } else {
                applyDiscovered(variable, item, confidence);
                variable.setSortOrder(1000 + order++);
            }
            variable.setUpdatedAt(Instant.now());
            toSave.add(variable);
            hitKeys.add(key);
        }

        // 清理两类孤儿（仅未确认的）：
        //  a) FILE 变量：上一轮识别出但本轮未被模型再识别 → 移除；
        //  b) BASE 变量：key 不在蓝图里的（如早期模型驱动的 contractNo / projectName）→ 移除。
        // ⚠️ BASE 是设计稿真相源：ensureBaseVariables() 已经预先入库了 5 条，
        //    它们默认 confirmed=false 且 model 可能这一轮一个 BASE 都没填出来，
        //    如果不豁免，"未在 hitKeys"那条会把刚插入的 5 条又整批删掉——这就是 BASE 页空白的原因。
        List<DraftVariable> toRemove = new ArrayList<>();
        for (DraftVariable v : existing) {
            if (Boolean.TRUE.equals(v.getConfirmed())) {
                continue;
            }
            if (DraftVariable.SCOPE_BASE.equals(v.getScope())) {
                continue;
            }
            if (!hitKeys.contains(v.getVarKey())) {
                toRemove.add(v);
            }
        }

        // 先 save 再 delete：同 key 走更新而非先删后插，避免唯一约束冲突
        variableRepository.saveAll(toSave);
        variableRepository.deleteAll(toRemove);
        variableRepository.flush();

        log.info("项目 {} 变量识别完成：识别 {} 个（含更新已有），移除 {} 个未被识别的未确认变量",
                projectId, toSave.size(), toRemove.size());
        return variableRepository.findByProjectIdOrderBySortOrderAsc(projectId)
                .stream().map(this::toVariableVO).collect(Collectors.toList());
    }

    /**
     * 把设计稿里的 5 个 BASE 变量预入库：不存在则插入元数据 + 空 value；
     * 已存在则不动（避免覆盖人工填好的 value）。BASE 是设计稿的真相源，
     * 永远显示这 5 条，不会被模型新增或重命名。
     */
    @Transactional
    public List<VariableVO> ensureBaseVariables(String projectId) {
        projectService.require(projectId);
        List<DraftVariable> existing = variableRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        Map<String, DraftVariable> byKey = new HashMap<>();
        for (DraftVariable v : existing) {
            byKey.put(v.getVarKey(), v);
        }
        int sortOrder = 0;
        for (DraftBlueprint.BaseSpec spec : DraftBlueprint.BASE_VARIABLES) {
            DraftVariable v = byKey.get(spec.key);
            if (v == null) {
                // 全新插入：所有蓝图字段都填进去
                v = new DraftVariable();
                v.setProjectId(projectId);
                v.setVarKey(spec.key);
                v.setScope(DraftVariable.SCOPE_BASE);
                v.setFileKey(null);
                v.setLabelZhHans(spec.labelZhHans);
                v.setLabelZhHant(spec.labelZhHant);
                v.setLabelEn(spec.labelEn);
                v.setAction(spec.action);
                v.setKind(spec.kind);
                v.setAffects(spec.affects);
                if (spec.options != null && !spec.options.isEmpty()) {
                    List<LocalizedText> opts = new ArrayList<>();
                    for (String o : spec.options) {
                        opts.add(LocalizedText.same(o));
                    }
                    v.setOptionsJson(JsonUtils.write(opts));
                }
                v.setConfirmed(false);
                v.setSortOrder(sortOrder);
                v.setUpdatedAt(Instant.now());
                variableRepository.save(v);
            } else {
                // 已有记录：把蓝图元数据同步过来（这是 BASE 变量字段的唯一真相源），
                // 保留 valueText / confirmed / sourceRef / noteText / choice 等用户或模型实际填的字段
                boolean changed = false;
                if (!DraftVariable.SCOPE_BASE.equals(v.getScope())) { v.setScope(DraftVariable.SCOPE_BASE); changed = true; }
                if (v.getFileKey() != null) { v.setFileKey(null); changed = true; }
                if (!spec.labelZhHans.equals(v.getLabelZhHans())) { v.setLabelZhHans(spec.labelZhHans); changed = true; }
                if (!spec.labelZhHant.equals(v.getLabelZhHant())) { v.setLabelZhHant(spec.labelZhHant); changed = true; }
                if (!spec.labelEn.equals(v.getLabelEn())) { v.setLabelEn(spec.labelEn); changed = true; }
                if (!spec.action.equals(v.getAction())) { v.setAction(spec.action); changed = true; }
                if (!java.util.Objects.equals(spec.kind, v.getKind())) { v.setKind(spec.kind); changed = true; }
                if (!spec.affects.equals(v.getAffects())) { v.setAffects(spec.affects); changed = true; }
                String desiredOpts = (spec.options == null || spec.options.isEmpty()) ? null : serializeOptions(spec.options);
                if (!java.util.Objects.equals(desiredOpts, v.getOptionsJson())) { v.setOptionsJson(desiredOpts); changed = true; }
                if (changed) {
                    v.setUpdatedAt(Instant.now());
                    variableRepository.save(v);
                }
            }
            sortOrder++;
        }
        return variableRepository.findByProjectIdOrderBySortOrderAsc(projectId)
                .stream().map(this::toVariableVO).collect(Collectors.toList());
    }

    /**
     * BASE 变量的取值写入：只更新 value / sourceRef / note / choice，不动 label / action / options / affects / kind。
     * 即便模型给出了 labelZh 等字段也忽略——保证 BASE 与设计稿完全一致。
     */
    private void applyBaseFill(DraftVariable variable, DiscoveredVariable item, double confidence) {
        // BASE 变量：scope / fileKey 与设计稿一致（元数据固定）
        variable.setScope(DraftVariable.SCOPE_BASE);
        variable.setFileKey(null);
        String value = nvl(item.getValue());
        DraftBlueprint.BaseSpec spec = DraftBlueprint.findBase(variable.getVarKey());
        if (spec != null && "list".equalsIgnoreCase(spec.kind)) {
            // 清单型变量：模型给的多行字符串要规范化为 JSON 数组，前端 listValues/listRows 才能正确解析
            value = normalizeListValue(value);
        }
        final String storedValue = value;
        variable.setValueText(storedValue);
        if (spec != null && "choice".equalsIgnoreCase(spec.action)) {
            // B03 资金安排：value 必须落在 options 里，否则视为模型自由文本、不写 choice
            if (spec.options != null && spec.options.contains(storedValue)) {
                variable.setChoice(storedValue);
            } else if (spec.options != null && !storedValue.isEmpty()) {
                // 取最相似的一个作为默认 choice（用户后续可改）
                String guess = spec.options.stream()
                        .filter(o -> storedValue.toLowerCase(Locale.ROOT).contains(o.toLowerCase(Locale.ROOT)))
                        .findFirst().orElse(spec.options.get(0));
                variable.setChoice(guess);
            } else {
                variable.setChoice(null);
            }
        } else {
            variable.setChoice(null);
        }
        variable.setSourceRef(truncate(nvl(item.getSourceQuote()), 480));
        variable.setNoteText(truncate("依据：" + nvl(item.getReason())
                + "（置信度 " + String.format(Locale.ROOT, "%.2f", confidence) + "）", 2000));
        variable.setResultText(null);
    }

    /**
     * 把清单型变量的 value 规范化：模型常输出换行分隔的纯字符串，前端 listValues/listRows 需要
     * JSON 数组才能正确解析；这里统一转成 ["条目1", "条目2", ...] 形式的 JSON 字符串。
     * 同时兼容模型把 \n 写成字面两个字符（反斜杠+n）的常见情况。
     */
    private String normalizeListValue(String raw) {
        if (raw == null) {
            return "[]";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "[]";
        }
        if (trimmed.startsWith("[")) {
            // 看起来已经是 JSON 数组（用户手工调整或后端兼容），原样保留
            return raw;
        }
        // 模型经常在 JSON 字符串里把 \n 写成字面两个字符（反斜杠+n），这里先还原成真换行再切
        String withRealNewlines = trimmed.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n");
        List<String> items = new ArrayList<>();
        for (String line : withRealNewlines.split("\\r?\\n")) {
            String item = line.trim();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items.isEmpty() ? "[]" : JsonUtils.write(items);
    }

    /** 把模型识别结果写入实体（调用方保证 variable 未被人工确认） */
    private void applyDiscovered(DraftVariable variable, DiscoveredVariable item, double confidence) {
        variable.setScope(DraftVariable.SCOPE_BASE.equalsIgnoreCase(nvl(item.getScope()))
                ? DraftVariable.SCOPE_BASE : DraftVariable.SCOPE_FILE);
        String fileKey = sanitizeFileKey(item.getFileKey());
        variable.setFileKey(DraftVariable.SCOPE_BASE.equals(variable.getScope()) ? null : fileKey);
        variable.setLabelZhHans(truncate(nvl(item.getLabelZh()), 480));
        variable.setLabelZhHant(truncate(firstNonBlank(item.getLabelZhHant(), item.getLabelZh()), 480));
        variable.setLabelEn(truncate(firstNonBlank(item.getLabelEn(), item.getLabelZh()), 480));
        String action = nvl(item.getAction()).trim().toLowerCase(Locale.ROOT);
        variable.setAction(ACTIONS.contains(action) ? action : "fill");
        variable.setValueText(nvl(item.getValue()));
        if ("choice".equals(variable.getAction())) {
            variable.setChoice(nvl(item.getValue()));
        }
        List<LocalizedText> options = new ArrayList<>();
        if (item.getOptions() != null) {
            for (String option : item.getOptions()) {
                if (!JsonUtils.isBlankText(option)) {
                    options.add(LocalizedText.same(option.trim()));
                }
            }
        }
        variable.setOptionsJson(options.isEmpty() ? null : JsonUtils.write(options));
        variable.setAffects(sanitizeAffects(item.getAffects()));
        variable.setSourceRef(truncate(nvl(item.getSourceQuote()), 480));
        variable.setNoteText(truncate("依据：" + nvl(item.getReason())
                + "（置信度 " + String.format(Locale.ROOT, "%.2f", confidence) + "）", 2000));
        variable.setResultText(null);
        variable.setKind(null);
        variable.setColsJson(null);
        variable.setLinkedBase(null);
        variable.setDerivedFrom(null);
    }

    @Transactional
    public VariableVO updateVariable(String projectId, String key, VariablePatch patch) {
        DraftVariable variable = variableRepository.findByProjectIdAndVarKey(projectId, key)
                .orElseThrow(() -> new BizException(4006, "变量不存在: " + key));
        if (patch.getValue() != null) {
            variable.setValueText(patch.getValue());
        }
        if (patch.getChoice() != null) {
            variable.setChoice(patch.getChoice());
            variable.setValueText(patch.getChoice());
        }
        if (patch.getNote() != null) {
            variable.setNoteText(patch.getNote());
        }
        if (patch.getResult() != null) {
            variable.setResultText(patch.getResult());
        }
        if (patch.getConfirmed() != null) {
            variable.setConfirmed(patch.getConfirmed());
            variable.setConfirmedFrom(patch.getConfirmed() ? nvl(variable.getFileKey()) : null);
        }
        variable.setUpdatedAt(Instant.now());
        variableRepository.save(variable);
        return toVariableVO(variableRepository.findByProjectIdAndVarKey(projectId, key).orElse(variable));
    }

    /**
     * 手工新增 FILE 变量：用于 QS 补录模型未覆盖到的 Guidance Note / 编辑目标。
     * 不允许新增 BASE 变量（BASE 是设计稿固定的 5 个），也不允许同 project 内重名 key。
     */
    @Transactional
    public VariableVO createVariable(String projectId, VariableCreate body) {
        String key = sanitizeKey(nvl(body.getKey()));
        if (key.isEmpty()) {
            throw new BizException(4007, "变量 key 不能为空，且仅允许字母数字下划线短横线");
        }
        if (variableRepository.findByProjectIdAndVarKey(projectId, key).isPresent()) {
            throw new BizException(4008, "变量 key 已存在: " + key);
        }
        String action = nvl(body.getAction()).trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            action = "fill";
        }
        String fileKey = sanitizeFileKey(body.getFileKey());
        if (JsonUtils.isBlankText(fileKey)) {
            throw new BizException(4007, "文件变量必须指定 fileKey（NTT / SCT / SCC）");
        }
        DraftVariable variable = new DraftVariable();
        variable.setProjectId(projectId);
        variable.setVarKey(key);
        variable.setScope(DraftVariable.SCOPE_FILE);
        variable.setFileKey(fileKey);
        variable.setLabelZhHans(truncate(nvl(body.getLabelZhHans()), 480));
        variable.setLabelZhHant(truncate(firstNonBlank(body.getLabelZhHant(), body.getLabelZhHans()), 480));
        variable.setLabelEn(truncate(firstNonBlank(body.getLabelEn(), body.getLabelZhHans()), 480));
        variable.setAction(action);
        variable.setValueText(nvl(body.getValue()));
        if ("choice".equals(action) && body.getOptions() != null && !body.getOptions().isEmpty()) {
            // 与 applyDiscovered 保持一致：options 以 LocalizedText 形式存入，三语共用同一文案
            List<com.consense.common.LocalizedText> optionTexts = new ArrayList<>();
            for (String option : body.getOptions()) {
                if (!JsonUtils.isBlankText(option)) {
                    optionTexts.add(com.consense.common.LocalizedText.same(option.trim()));
                }
            }
            variable.setOptionsJson(JsonUtils.write(optionTexts));
        }
        variable.setAffects(truncate(nvl(body.getAffects()), 128));
        variable.setSourceRef(truncate(nvl(body.getSourceQuote()), 512));
        variable.setNoteText(truncate("依据：" + nvl(body.getReason())
                + (body.getConfidence() != null ? "（置信度 " + String.format(Locale.ROOT, "%.2f", body.getConfidence()) + "）" : ""), 2000));
        variable.setConfirmed(false);
        variable.setUpdatedAt(Instant.now());
        // sortOrder 追加到已有最大之后，便于前端按创建顺序排列
        int maxOrder = variableRepository.findByProjectIdOrderBySortOrderAsc(projectId).stream()
                .mapToInt(v -> v.getSortOrder() == null ? 0 : v.getSortOrder()).max().orElse(0);
        variable.setSortOrder(maxOrder + 1);
        variableRepository.save(variable);
        return toVariableVO(variable);
    }

    @Transactional
    public List<VariableVO> confirmAll(String projectId, String scope, String fileKey) {
        List<DraftVariable> variables = variableRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        for (DraftVariable variable : variables) {
            if ("FILE".equalsIgnoreCase(scope) && !JsonUtils.isBlankText(fileKey)) {
                if (!fileKey.equals(variable.getFileKey())) {
                    continue;
                }
            } else if ("FILE".equalsIgnoreCase(scope)) {
                if (!DraftVariable.SCOPE_FILE.equals(variable.getScope())) {
                    continue;
                }
            } else if (!DraftVariable.SCOPE_BASE.equals(variable.getScope())) {
                continue;
            }
            variable.setConfirmed(true);
            variable.setConfirmedFrom(nvl(variable.getFileKey()));
            variable.setUpdatedAt(Instant.now());
        }
        variableRepository.saveAll(variables);
        return variableRepository.findByProjectIdOrderBySortOrderAsc(projectId)
                .stream().map(this::toVariableVO).collect(Collectors.toList());
    }

    // ------------------------------------------------------------ 文稿生成

    @Transactional
    public List<DraftDocumentVO> generate(String projectId, String lang) {
        List<DraftVariable> variables = variableRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        if (variables.isEmpty()) {
            throw new BizException(4009, "尚未识别任何变量，请先上传资料并识别变量");
        }
        List<DraftVariable> unconfirmed = variables.stream()
                .filter(v -> !Boolean.TRUE.equals(v.getConfirmed())).collect(Collectors.toList());
        if (!unconfirmed.isEmpty()) {
            throw new BizException(4007, "尚有 " + unconfirmed.size() + " 个变量未确认，请先在向导中确认全部变量");
        }
        Project project = projectService.require(projectId);

        List<DraftDocumentVO> result = new ArrayList<>();
        for (String fileKey : DraftBlueprint.draftFileKeys()) {
            DraftDocument document = documentRepository.findByProjectIdAndFileKey(projectId, fileKey)
                    .orElseGet(DraftDocument::new);
            document.setProjectId(projectId);
            document.setFileKey(fileKey);
            document.setTitle(DraftBlueprint.fileTitle(fileKey));

            List<Map<String, Object>> summary = new ArrayList<>();
            for (DraftVariable v : variables) {
                if (!affectsFile(v, fileKey)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", v.getVarKey());
                item.put("label", pick(v, lang));
                item.put("action", nvl(v.getAction()));
                item.put("value", nvl(v.getValueText()));
                item.put("result", nvl(v.getResultText()));
                summary.add(item);
            }
            String variablesJson = JsonUtils.write(summary);

            String templateText = sourceDocumentRepository
                    .findFirstByProjectIdAndFileKey(projectId, fileKey)
                    .filter(d -> SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(d.getCategory()))
                    .map(d -> truncate(nvl(d.getTextContent()), MAX_DOC_CHARS))
                    .orElse("（未上传该文件的标准模板，请按已确认变量直接起草）");

            String userPrompt = PromptService.format(promptService.userTemplate(PromptCatalog.KEY_CLAUSE),
                    DraftBlueprint.fileTitle(fileKey) + " / " + nvl(project.getContractNo()),
                    variablesJson,
                    templateText);

            GeneratedDoc generated = ai.completeJson(
                    promptService.system(PromptCatalog.KEY_CLAUSE) + "\n输出语言：" + languageName(lang),
                    userPrompt, GeneratedDoc.class);

            document.setContent(generated == null || generated.getContent() == null
                    ? fallbackContent(fileKey, project, variables, lang)
                    : generated.getContent());
            document.setGenerated(true);
            if (document.getCreatedAt() == null) {
                document.setCreatedAt(Instant.now());
            }
            document.setUpdatedAt(Instant.now());
            documentRepository.save(document);
            result.add(new DraftDocumentVO(fileKey, document.getTitle(), document.getContent(), true));
        }
        log.info("项目 {} 已生成 {} 份文稿", projectId, result.size());
        return result;
    }

    public List<DraftDocumentVO> listDocuments(String projectId) {
        projectService.require(projectId);
        Map<String, DraftDocument> byKey = new HashMap<>();
        documentRepository.findByProjectIdOrderByIdAsc(projectId)
                .forEach(d -> byKey.put(d.getFileKey(), d));
        List<DraftDocumentVO> result = new ArrayList<>();
        for (String fileKey : DraftBlueprint.draftFileKeys()) {
            DraftDocument document = byKey.get(fileKey);
            result.add(document == null
                    ? new DraftDocumentVO(fileKey, DraftBlueprint.fileTitle(fileKey), "", false)
                    : new DraftDocumentVO(fileKey, document.getTitle(), nvl(document.getContent()),
                    Boolean.TRUE.equals(document.getGenerated())));
        }
        return result;
    }

    /**
     * QS 在审阅界面直接修改生成稿正文（点击变量定位到文稿位置后编辑保存）。
     * 只改文稿本身，不动变量；改完仍视为已生成。
     */
    @Transactional
    public DraftDocumentVO updateDocument(String projectId, String fileKey, DocumentPatch patch) {
        projectService.require(projectId);
        if (patch.getContent() == null) {
            throw new BizException(4007, "content 不能为空");
        }
        DraftDocument document = documentRepository.findByProjectIdAndFileKey(projectId, fileKey)
                .orElseThrow(() -> new BizException(4008, "尚未生成 " + fileKey + " 文稿"));
        document.setContent(patch.getContent());
        if (!JsonUtils.isBlankText(patch.getContent())) {
            document.setGenerated(true);
        }
        document.setUpdatedAt(Instant.now());
        documentRepository.save(document);
        return new DraftDocumentVO(document.getFileKey(), document.getTitle(), nvl(document.getContent()),
                Boolean.TRUE.equals(document.getGenerated()));
    }

    public byte[] download(String projectId, String fileKey) {
        Project project = projectService.require(projectId);
        DraftDocument document = documentRepository.findByProjectIdAndFileKey(projectId, fileKey)
                .orElseThrow(() -> new BizException(4008, "尚未生成 " + fileKey + " 文稿"));
        String header = "# " + nvl(document.getTitle()) + "\n\n"
                + "Contract No.: " + nvl(project.getContractNo()) + "\n"
                + "Package Ref.: " + nvl(project.getPackageRef()) + "\n"
                + "Generated by ConSense CAC Solution\n\n---\n\n";
        // 带 BOM，方便 Windows 下用记事本 / Word 直接打开不乱码
        return ("\uFEFF" + header + nvl(document.getContent())).getBytes(StandardCharsets.UTF_8);
    }

    public String downloadFileName(String projectId, String fileKey) {
        Project project = projectService.require(projectId);
        return "ConSense_" + fileKey + "_" + nvl(project.getContractNo()) + ".md";
    }

    /**
     * 第 3 步审阅模式 / 最终预览：把生成稿渲染成真正的 PDF，前端用浏览器原生 PDF 视图展示。
     */
    public byte[] previewPdf(String projectId, String fileKey) {
        Project project = projectService.require(projectId);
        DraftDocument document = documentRepository.findByProjectIdAndFileKey(projectId, fileKey)
                .orElseThrow(() -> new BizException(4008, "尚未生成 " + fileKey + " 文稿"));
        return pdfWriter.write(project, fileKey,
                JsonUtils.isBlankText(document.getTitle()) ? DraftBlueprint.fileTitle(fileKey) : document.getTitle(),
                nvl(document.getContent()));
    }

    /**
     * 「读取资料」步骤上传的 NTT / SCT / SCC 原始模板 PDF（步骤 1 的源文件）。
     * 用于审阅模式下与文稿 PDF 并列展示，让用户在确认变量时能直接对照原始模板。
     */
    public byte[] previewTemplate(String projectId, String fileKey) {
        projectService.require(projectId);
        SourceDocument template = sourceDocumentRepository
                .findFirstByProjectIdAndFileKeyAndCategory(projectId, fileKey, SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .orElseThrow(() -> new BizException(4011, "尚未上传 " + fileKey + " 标准模板，请到第 1 步上传"));
        return storageService.read(template.getStoragePath());
    }

    public String previewTemplateFileName(String projectId, String fileKey) {
        return sourceDocumentRepository
                .findFirstByProjectIdAndFileKeyAndCategory(projectId, fileKey, SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .map(SourceDocument::getFileName)
                .orElse(fileKey + ".pdf");
    }

    /**
     * 第 3 步「标准模板在线编辑」：读取模板正文（解析后的文本 / Markdown），供前端 textarea 编辑。
     * 若 textContent 为空（例如历史数据 / 解析失败），惰性重解析一次并回填。
     */
    @Transactional
    public TemplateTextVO getTemplateText(String projectId, String fileKey) {
        projectService.require(projectId);
        SourceDocument template = sourceDocumentRepository
                .findFirstByProjectIdAndFileKeyAndCategory(projectId, fileKey, SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .orElseThrow(() -> new BizException(4011, "尚未上传 " + fileKey + " 标准模板，请到第 1 步上传"));
        if (JsonUtils.isBlankText(template.getTextContent()) && template.getStoragePath() != null) {
            try {
                DocumentParser.ParsedDocument result = documentParser.parse(
                        template.getFileName(), storageService.read(template.getStoragePath()));
                if (!JsonUtils.isBlankText(result.getText())) {
                    template.setTextContent(result.getText());
                    template.setParseStatus("PARSED");
                    sourceDocumentRepository.save(template);
                }
            } catch (Exception ignored) {
                // 保持空文本，前端展示空态提示
            }
        }
        return new TemplateTextVO(template.getTextContent() == null ? "" : template.getTextContent());
    }

    /**
     * 第 3 步「标准模板在线编辑」：保存模板正文到 textContent。
     * 仅持久化文本，不重新抽取变量（变量列表由用户在第 2 步或本面板手动维护）。
     */
    @Transactional
    public TemplateTextVO updateTemplateText(String projectId, String fileKey, String text) {
        projectService.require(projectId);
        SourceDocument template = sourceDocumentRepository
                .findFirstByProjectIdAndFileKeyAndCategory(projectId, fileKey, SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .orElseThrow(() -> new BizException(4011, "尚未上传 " + fileKey + " 标准模板，请到第 1 步上传"));
        template.setTextContent(text == null ? "" : text);
        sourceDocumentRepository.save(template);
        return new TemplateTextVO(template.getTextContent());
    }

    // ------------------------------------------------------------ 辅助

    private boolean affectsFile(DraftVariable variable, String fileKey) {
        if (fileKey.equals(variable.getFileKey())) {
            return true;
        }
        return variable.getAffects() != null
                && Arrays.stream(variable.getAffects().split(",")).map(String::trim).anyMatch(fileKey::equals);
    }

    /**
     * 收集标准模板文本：短模板全文送入；超长模板（如 SCC 全文 20+ 万字符）改为
     * 决策片段聚焦提取——优先保留 Guidance Note / Option / Delete 等标记附近的原文，
     * 再用文档开头（标题与目录）补足背景，确保决策点不因截断而丢失。
     */
    private String collectTemplateText(String projectId) {
        List<SourceDocument> documents = sourceDocumentRepository
                .findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        StringBuilder builder = new StringBuilder();
        int remaining = MAX_TEMPLATE_CHARS;
        for (SourceDocument document : documents) {
            if (JsonUtils.isBlankText(document.getTextContent()) || remaining <= 0) {
                continue;
            }
            String header = "\n\n=== " + document.getFileName() + " ===\n";
            String raw = document.getTextContent();
            String body;
            if (raw.length() <= MAX_DOC_CHARS) {
                body = raw;
            } else {
                body = focusDecisionSegments(raw, Math.min(MAX_DOC_CHARS, remaining - header.length()));
                int room = Math.min(MAX_DOC_CHARS, remaining - header.length()) - body.length();
                if (room > 800) {
                    // 文档开头（封面/目录）提供全局背景
                    body = truncate(raw, Math.min(1800, room)) + "\n……（中间内容省略）……\n" + body;
                }
            }
            builder.append(header).append(body);
            remaining -= body.length() + header.length();
        }
        return builder.toString().trim();
    }

    /**
     * 决策片段聚焦提取：定位所有决策标记（Guidance Note、Option、Delete 等）出现的位置，
     * 各取前后一段上下文，合并重叠区间后按原文顺序拼接，总量不超过 budget。
     */
    private String focusDecisionSegments(String text, int budget) {
        // 1. 收集每个标记命中位置的扩展区间 [from, to]
        List<int[]> spans = new ArrayList<>();
        for (String marker : DECISION_MARKERS) {
            int idx = 0;
            while (spans.size() < 400) {
                idx = text.indexOf(marker, idx);
                if (idx < 0) {
                    break;
                }
                spans.add(new int[]{
                        Math.max(0, idx - 300),
                        Math.min(text.length(), idx + 500)});
                idx += marker.length();
            }
        }
        if (spans.isEmpty()) {
            return truncate(text, budget);
        }
        // 2. 按起点排序并合并重叠区间
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] span : spans) {
            if (!merged.isEmpty() && span[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], span[1]);
            } else {
                merged.add(new int[]{span[0], span[1]});
            }
        }
        // 3. 顺序拼接，总量控制在 budget 内（每个片段之间用省略号衔接）
        StringBuilder builder = new StringBuilder();
        for (int[] span : merged) {
            if (builder.length() >= budget) {
                break;
            }
            if (builder.length() > 0) {
                builder.append("\n……（未命中决策线索的正文省略）……\n");
            }
            String segment = text.substring(span[0], Math.min(span[1], text.length()));
            int room = budget - builder.length();
            builder.append(segment.length() <= room ? segment : segment.substring(0, room));
        }
        return builder.toString();
    }

    private String collectEvidenceText(String projectId, String category) {
        List<SourceDocument> documents =
                sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId, category);
        StringBuilder builder = new StringBuilder();
        int remaining = MAX_EVIDENCE_CHARS;
        for (SourceDocument document : documents) {
            if (JsonUtils.isBlankText(document.getTextContent()) || remaining <= 0) {
                continue;
            }
            String header = "\n\n=== " + document.getFileName() + " ===\n";
            String body = truncate(document.getTextContent(), Math.min(MAX_DOC_CHARS, remaining));
            builder.append(header).append(body);
            remaining -= body.length() + header.length();
        }
        return builder.toString().trim();
    }

    /**
     * 从全部证据中为 BASE 变量抽取关键句（不占用 MAX_EVIDENCE_CHARS 预算），
     * 避免 BQ Bill、分包清单等写在靠后文件里的值被整体截断漏掉。
     */
    private String collectBaseEvidenceSnippets(String projectId) {
        List<String> keywords = Arrays.asList(
                "contract no", "contract title", "tender a", "tender b", "funding arrangement",
                "bill ", "bq", "bill schedule", "preliminar", "preamble",
                "sub-contractor", "subcontractor", "specialist", "nominated", "trade");
        List<SourceDocument> documents =
                sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,
                        SourceDocument.CATEGORY_PROJECT_INPUT);
        StringBuilder builder = new StringBuilder();
        int remaining = MAX_EVIDENCE_SNIPPET_CHARS;
        for (SourceDocument document : documents) {
            String text = nvl(document.getTextContent());
            if (text.isEmpty()) {
                continue;
            }
            String[] lines = text.split("\\r?\\n");
            boolean headerAdded = false;
            for (String line : lines) {
                if (remaining <= 0) {
                    break;
                }
                String lower = line.toLowerCase(Locale.ROOT);
                boolean hit = false;
                for (String kw : keywords) {
                    if (lower.contains(kw)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.length() < 6) {
                    continue;
                }
                if (!headerAdded) {
                    String header = "\n[" + document.getFileName() + "]\n";
                    builder.append(header);
                    remaining -= header.length();
                    headerAdded = true;
                }
                String entry = "  " + trimmed + "\n";
                if (entry.length() > remaining) {
                    break;
                }
                builder.append(entry);
                remaining -= entry.length();
            }
        }
        String result = builder.toString().trim();
        return result.isEmpty() ? "（无聚焦摘录）" : result;
    }

    private EvidenceVO toEvidenceVO(SourceDocument document) {
        String contentType = nvl(document.getContentType()).toLowerCase(Locale.ROOT);
        String fileName = nvl(document.getFileName()).toLowerCase(Locale.ROOT);
        String typeLabel;
        if (contentType.contains("message") || contentType.contains("rfc822")
                || fileName.endsWith(".eml") || fileName.endsWith(".msg")) {
            typeLabel = "邮件";
        } else if (contentType.contains("pdf") || fileName.endsWith(".pdf")) {
            typeLabel = "PDF";
        } else if (contentType.contains("word") || fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            typeLabel = "Word";
        } else {
            typeLabel = "文本";
        }
        String status = nvl(document.getParseStatus());
        String tag;
        if ("PARSED".equals(status)) {
            tag = "ok";
        } else if ("FAILED".equals(status)) {
            tag = "danger";
        } else {
            tag = "info";
        }
        return new EvidenceVO(
                document.getId(),
                "E-" + (document.getId() == null ? "0" : document.getId()),
                LocalizedText.same(typeLabel),
                status,
                tag,
                LocalizedText.same(nvl(document.getFileName())),
                LocalizedText.same(truncate(nvl(document.getTextContent()), 400)),
                nvl(document.getParseMessage()),
                document.getFileName(),
                document.getFileKey(),
                document.getPageCount(),
                document.getOcrUsed(),
                LocalizedText.same(nvl(document.getParseMessage())));
    }

    private VariableVO toVariableVO(DraftVariable variable) {
        List<LocalizedText> options = new ArrayList<>();
        if (!JsonUtils.isBlankText(variable.getOptionsJson())) {
            try {
                options = JsonUtils.readList(variable.getOptionsJson(), LocalizedText.class);
            } catch (Exception e) {
                log.debug("解析 options 失败: {}", e.getMessage());
            }
        }
        List<String> cols = new ArrayList<>();
        if (!JsonUtils.isBlankText(variable.getColsJson())) {
            try {
                cols = JsonUtils.readList(variable.getColsJson(), String.class);
            } catch (Exception e) {
                log.debug("解析 cols 失败: {}", e.getMessage());
            }
        }
        List<String> affects = JsonUtils.isBlankText(variable.getAffects())
                ? Collections.<String>emptyList()
                : Arrays.stream(variable.getAffects().split(",")).map(String::trim).collect(Collectors.toList());
        List<String> derivedFrom = JsonUtils.isBlankText(variable.getDerivedFrom())
                ? Collections.<String>emptyList()
                : Arrays.stream(variable.getDerivedFrom().split(",")).map(String::trim).collect(Collectors.toList());

        return new VariableVO(variable.getVarKey(),
                variable.getScope(),
                variable.getFileKey(),
                LocalizedText.of(variable.getLabelZhHans(), variable.getLabelZhHant(), variable.getLabelEn()),
                variable.getAction(),
                nvl(variable.getValueText()),
                options,
                Boolean.TRUE.equals(variable.getConfirmed()),
                variable.getConfirmedFrom(),
                variable.getSourceRef(),
                variable.getResultText(),
                variable.getKind(),
                cols,
                variable.getLinkedBase(),
                derivedFrom,
                affects,
                variable.getNoteText());
    }

    private String fallbackContent(String fileKey, Project project,
                                   List<DraftVariable> variables, String lang) {
        StringBuilder builder = new StringBuilder();
        builder.append("## ").append(DraftBlueprint.fileTitle(fileKey)).append("\n\n");
        builder.append("**Contract No.** ").append(nvl(project.getContractNo())).append("\n\n");
        for (DraftVariable variable : variables) {
            if (!affectsFile(variable, fileKey)) {
                continue;
            }
            builder.append("- **").append(pick(variable, lang)).append("**（")
                    .append(nvl(variable.getAction())).append("）：")
                    .append(nvl(variable.getValueText())).append("\n");
        }
        builder.append("\n> 模型未返回正文，以上为按已确认变量生成的条款要点。\n");
        return builder.toString();
    }

    private String matchTemplateKey(String fileName) {
        String name = nvl(fileName).toLowerCase(Locale.ROOT);
        for (String key : TEMPLATE_KEYS) {
            if (name.contains(key.toLowerCase(Locale.ROOT))) {
                return key;
            }
        }
        if (name.contains("tenderers") || name.contains("notes")) {
            return "NTT";
        }
        if (name.contains("special condition of tender") || name.contains("tender condition")) {
            return "SCT";
        }
        if (name.contains("special condition") || name.contains("contract")) {
            return "SCC";
        }
        return "NTT";
    }

    private String defaultTemplateFileName(String key) {
        if ("NTT".equals(key)) {
            return "01_Notes to Tenderers (NTT).docx";
        }
        if ("SCT".equals(key)) {
            return "03_Special Condition of Tender (SCT).docx";
        }
        return "07_Special Conditions of Contract (SCC).docx";
    }

    private String languageName(String lang) {
        if ("en".equalsIgnoreCase(lang)) {
            return "English";
        }
        if ("zh-Hant".equalsIgnoreCase(lang)) {
            return "繁體中文";
        }
        return "简体中文";
    }

    private String pick(DraftVariable variable, String lang) {
        LocalizedText text = LocalizedText.of(variable.getLabelZhHans(),
                variable.getLabelZhHant(), variable.getLabelEn());
        return text.pick(lang);
    }

    /** key 只保留字母数字与下划线，长度限制内 */
    private String sanitizeKey(String key) {
        if (key == null) {
            return null;
        }
        String cleaned = key.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private String sanitizeFileKey(String fileKey) {
        if (fileKey == null) {
            return null;
        }
        String cleaned = fileKey.trim().toUpperCase(Locale.ROOT);
        return TEMPLATE_KEYS.contains(cleaned) ? cleaned : null;
    }

    private String sanitizeAffects(String affects) {
        if (JsonUtils.isBlankText(affects)) {
            return null;
        }
        List<String> kept = Arrays.stream(affects.split(","))
                .map(String::trim)
                .filter(s -> TEMPLATE_KEYS.contains(s.toUpperCase(Locale.ROOT)))
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
        return kept.isEmpty() ? null : String.join(",", kept);
    }

    private String serializeOptions(List<String> options) {
        List<LocalizedText> opts = new ArrayList<>();
        for (String o : options) {
            opts.add(LocalizedText.same(o));
        }
        return JsonUtils.write(opts);
    }

    private String firstNonBlank(String primary, String fallback) {
        return JsonUtils.isBlankText(primary) ? fallback : primary;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n...[已截断]";
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 项目显示名：中文名优先，缺失时回退英文名；都没有返回空串 */
    private String projectDisplayName(Project project) {
        if (project == null) {
            return "";
        }
        if (!JsonUtils.isBlankText(project.getNameZhHans())) {
            return project.getNameZhHans();
        }
        return nvl(project.getNameEn());
    }
}
