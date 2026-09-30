package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.domain.VettingFinding;
import com.consense.repository.SourceDocumentRepository;
import com.consense.repository.VettingFindingRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.prompt.PromptCatalog;
import com.consense.service.prompt.PromptService;
import com.consense.service.seed.DemoDataInitializer;
import com.consense.web.dto.DraftingDtos.UploadResultVO;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 审查模块：源集管理、运行审查（LLM 依据 9 类 F2 规则产出 finding）、
 * 三栏定位器数据、处置状态、审查报告 PDF 导出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VettingService {

    private static final List<String> FILE_ORDER =
            Arrays.asList("NTT", "SCT", "SCC", "GCT", "FT", "AA", "GCC", "SL", "PRE");
    private static final List<String> GENERATED_KEYS = Arrays.asList("NTT", "SCT", "SCC");
    private static final int MAX_DOC_CHARS = 5000;
    /** 单份文件至少保留的正文长度，避免均分后靠后的文件只剩碎片 */
    private static final int MIN_DOC_CHARS = 1200;
    /**
     * 送模型的整包正文预算（字符）。
     * num-ctx 只有 8192 token，实测英文正文约 3.7 字符/token ——
     * 旧值 90000 字符必然超窗被静默截断，模型看不到材料就只能编造条款号（证据链失真的直接原因）。
     * 14000 字符 ≈ 3800 token 的正文，加上模板约 4300 token，
     * 给 JSON 输出留出约 3800 token 余量（实测一次审查模型会吐 3000+ token）。
     */
    private static final int MAX_PAYLOAD_CHARS = 14000;
    /** 模型给的信息未能在原文中定位到时写入 evidenceId 的值，前端据此提示人工复核 */
    public static final String EVIDENCE_UNVERIFIED = "unverified";

    private final ProjectService projectService;
    private final StorageService storageService;
    private final DocumentParser documentParser;
    private final AiGateway ai;
    private final ConsenseProperties props;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final VettingFindingRepository findingRepository;
    private final VettingPdfWriter pdfWriter;
    /** 提示词从数据库取（支持前端在线编辑），DB 无值时回退出厂默认 */
    private final PromptService promptService;

    /** 模型返回的 finding 记录 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FindingRecord {
        private String types;
        private String scope;
        private String severity;
        private String fileKey;
        private String fileLabel;
        private String pageNo;
        private String bucketKey;
        private String location;
        private String refs;
        private String expected;
        private String titleZh;
        private String titleEn;
        private String bodyZh;
        private String bodyEn;
        private String impactZh;
        private String impactEn;
        private String suggestionZh;
        private String suggestionEn;
        private String quote;
    }

    // ------------------------------------------------------------ 审查源集

    public List<VettingFileVO> listFiles(String projectId) {
        projectService.require(projectId);
        Map<String, SourceDocument> byKey = new LinkedHashMap<>();
        for (SourceDocument document : sourceDocumentRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (!SourceDocument.CATEGORY_VETTING_SUPPLEMENT.equals(document.getCategory())
                    && !SourceDocument.CATEGORY_VETTING_PACKAGE.equals(document.getCategory())) {
                continue;
            }
            byKey.merge(document.getFileKey() == null ? "OTHER:" + document.getId() : document.getFileKey(),
                    document, (a, b) -> a);
        }

        // 起草生成的 NTT / SCT / SCC 也计入源集
        for (SourceDocument document : sourceDocumentRepository
                .findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_STANDARD_TEMPLATE)) {
            if (document.getFileKey() != null && !byKey.containsKey(document.getFileKey())) {
                byKey.put(document.getFileKey(), document);
            }
        }

        List<VettingFileVO> result = new ArrayList<>();
        List<String> ordered = new ArrayList<>(FILE_ORDER);
        byKey.keySet().stream().filter(k -> !ordered.contains(k)).forEach(ordered::add);

        for (String key : ordered) {
            SourceDocument document = byKey.get(key);
            if (document == null) {
                continue;
            }
            boolean generated = GENERATED_KEYS.contains(key);
            boolean parsed = "PARSED".equals(document.getParseStatus());
            result.add(new VettingFileVO(
                    key,
                    LocalizedText.of(generated ? "起草生成文件" : "审查补充文件",
                            generated ? "起草生成文件" : "審查補充文件",
                            generated ? "Drafting output" : "Supplementary file"),
                    document.getFileName(),
                    nvl(document.getParseMessage()),
                    document.getParseStatus(),
                    generated,
                    parsed || generated,
                    document.getPageCount() == null ? 0 : document.getPageCount()));
        }
        return result;
    }

    @Transactional
    public UploadResultVO uploadPackage(String projectId, List<MultipartFile> files) {
        projectService.require(projectId);
        List<String> messages = new ArrayList<>();
        int parsed = 0;
        int failed = 0;

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            StorageService.StoredFile stored =
                    storageService.store(projectId, SourceDocument.CATEGORY_VETTING_PACKAGE, file);
            SourceDocument document = new SourceDocument();
            document.setProjectId(projectId);
            document.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
            document.setFileKey(matchFileKey(stored.getOriginalName()));
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
                messages.add(stored.getOriginalName() + " 已解析（" + result.getPageCount() + " 页"
                        + (result.isOcrUsed() ? "，含 OCR" : "") + "）");
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

    // ------------------------------------------------------------ 运行审查

    @Transactional
    public RunResultVO run(String projectId, String lang) {
        Project project = projectService.require(projectId);
        List<SourceDocument> documents = reviewableDocuments(projectId);
        if (documents.isEmpty()) {
            throw new BizException(4101, "审查源集为空，请先上传整份招标文件材料或完成起草生成");
        }
        ai.requireAvailable();

        StringBuilder payload = new StringBuilder();
        int remaining = MAX_PAYLOAD_CHARS;
        for (int i = 0; i < documents.size() && remaining > 0; i++) {
            SourceDocument document = documents.get(i);
            String text = nvl(document.getTextContent());
            if (JsonUtils.isBlankText(text)) {
                continue;
            }
            // 预算按剩余文件数均分：8K 窗口里塞 3 份文件时，
            // 均分才能让每份都有一段可审内容，而不是前两份吃饱、第三份整份丢掉
            int docsLeft = Math.max(1, documents.size() - i);
            int allowance = Math.min(MAX_DOC_CHARS, Math.max(MIN_DOC_CHARS, remaining / docsLeft));
            String body = text.length() > allowance ? text.substring(0, allowance) + "\n...[已截断]" : text;
            String header = "\n\n===== 文件：" + nvl(document.getFileKey()) + " · "
                    + document.getFileName() + " =====\n";
            payload.append(header).append(body);
            remaining -= body.length() + header.length();
        }

        String userPrompt = PromptService.format(promptService.userTemplate(PromptCatalog.KEY_VETTING),
                nvl(project.getContractNo()),
                LocalizedText.of(project.getNameZhHans(), project.getNameZhHant(), project.getNameEn()).pick(lang),
                payload);

        List<FindingRecord> records =
                ai.completeJsonList(promptService.system(PromptCatalog.KEY_VETTING), userPrompt, FindingRecord.class);

        findingRepository.deleteByProjectId(projectId);

        int maxRisk = props.getVetting().getMaxRiskFindings();
        int riskCount = 0;
        int index = 1;
        int skippedDuplicate = 0;
        int unverified = 0;
        List<String> messages = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FindingRecord record : records) {
            if (JsonUtils.isBlankText(record.getTypes())) {
                continue;
            }
            // 去重：模型有时会把同一处问题重复输出多条（甚至照抄示例值），这里只保留首条
            if (!seen.add(dedupeKey(record))) {
                skippedDuplicate++;
                continue;
            }
            String group = DemoDataInitializer.groupOf(record.getTypes());
            if (VettingFinding.GROUP_RISK.equals(group)) {
                if (riskCount >= maxRisk) {
                    messages.add("主观风险条款已达上限 " + maxRisk + " 条，其余已按规则略过");
                    continue;
                }
                riskCount++;
            }
            VettingFinding finding = new VettingFinding();
            finding.setProjectId(projectId);
            finding.setCode(String.format("VT-%03d", index++));
            finding.setTypes(record.getTypes());
            finding.setGroupKey(group);
            finding.setScope(normalizeScope(record.getScope()));
            finding.setSeverity(normalizeSeverity(record.getSeverity()));
            finding.setStatus(VettingFinding.STATUS_OPEN);
            finding.setTitleZhHans(record.getTitleZh());
            finding.setTitleZhHant(record.getTitleZh());
            finding.setTitleEn(record.getTitleEn());
            finding.setBodyZhHans(record.getBodyZh());
            finding.setBodyZhHant(record.getBodyZh());
            finding.setBodyEn(record.getBodyEn());
            finding.setImpactZhHans(record.getImpactZh());
            finding.setImpactZhHant(record.getImpactZh());
            finding.setImpactEn(record.getImpactEn());
            finding.setSuggestionZhHans(record.getSuggestionZh());
            finding.setSuggestionZhHant(record.getSuggestionZh());
            finding.setSuggestionEn(record.getSuggestionEn());
            finding.setPatternText(truncate(record.getQuote(), 480));
            finding.setRefs(truncate(record.getRefs(), 500));
            finding.setLocation(truncate(record.getLocation(), 500));
            finding.setExpected(truncate(record.getExpected(), 500));
            finding.setFileKey(normalizeFileKey(record.getFileKey()));
            finding.setBucketKey(defaultBucket(group, record.getBucketKey()));
            // —— 证据校验：拿模型给的原文回源集里定位。
            //     命中 → 用真实页码 + 真实文件锚点覆盖模型自报值（模型给的 pageNo 不可信）；
            //     未命中 → 标记 unverified，前端提示「未能定位原文」，不再拿无关内容充当证据。
            String fallbackPage = JsonUtils.isBlankText(record.getPageNo()) ? "P1" : record.getPageNo();
            Located verified = locateIn(byFileKey(documents, finding.getFileKey()),
                    needlesOf(finding.getPatternText(), finding.getLocation(), finding.getRefs()));
            if (verified != null) {
                finding.setEvidenceId("doc:" + verified.documentId + ":" + nvl(verified.pageNo));
                finding.setPageNo(JsonUtils.isBlankText(verified.pageNo) ? fallbackPage : verified.pageNo);
            } else {
                finding.setEvidenceId(EVIDENCE_UNVERIFIED);
                finding.setPageNo(fallbackPage);
                unverified++;
            }
            finding.setCreatedAt(Instant.now());
            findingRepository.save(finding);
        }

        if (skippedDuplicate > 0) {
            messages.add("模型返回 " + records.size() + " 条，已合并 " + skippedDuplicate + " 条重复发现");
        }
        if (unverified > 0) {
            messages.add(unverified + " 条发现未能定位到原文，请人工复核");
        }

        List<VettingFinding> saved = findingRepository.findByProjectIdOrderByCodeAsc(projectId);
        log.info("项目 {} 审查完成，共 {} 条发现（跳过重复 {}，未定位原文 {}）",
                projectId, saved.size(), skippedDuplicate, unverified);
        return new RunResultVO(saved.size(), metricsOf(saved), messages, ai.chatModel());
    }

    /**
     * 去重键：类型 + 条款 + 落点 + 标题 + 原文片段，忽略空白与大小写。
     * 把 quote 纳入键里是有意为之——模型照抄示例时这几项会整组雷同，
     * 而真正不同的两个问题几乎不可能连原文片段都一致，这样才不会误合。
     */
    private String dedupeKey(FindingRecord record) {
        String quote = nvl(record.getQuote());
        return (nvl(record.getTypes()) + "|" + nvl(record.getRefs()) + "|" + nvl(record.getLocation())
                + "|" + nvl(record.getTitleZh()) + "|" + quote.substring(0, Math.min(120, quote.length())))
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * 审查源集：优先用显式上传的整份招标文件包；
     * 其次是 9 类招标文件（NTT/SCT/SCC/...，含起草生成的稿子）；
     * 起草用的「项目沟通证据」（PROJECT_INPUT 邮件 / 纪要）不是审查对象，不送入模型。
     */
    private List<SourceDocument> reviewableDocuments(String projectId) {
        List<SourceDocument> uploaded = sourceDocumentRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(d -> "PARSED".equals(d.getParseStatus())
                        && !JsonUtils.isBlankText(d.getTextContent()))
                .collect(Collectors.toList());
        if (uploaded.isEmpty()) {
            return uploaded;
        }
        List<SourceDocument> packageDocs = uploaded.stream()
                .filter(d -> SourceDocument.CATEGORY_VETTING_PACKAGE.equals(d.getCategory()))
                .collect(Collectors.toList());
        if (!packageDocs.isEmpty()) {
            return packageDocs;
        }
        List<SourceDocument> tenderFiles = dedupeByFileKey(uploaded.stream()
                .filter(d -> !JsonUtils.isBlankText(d.getFileKey()) && FILE_ORDER.contains(d.getFileKey()))
                .collect(Collectors.toList()));
        if (!tenderFiles.isEmpty()) {
            return tenderFiles;
        }
        List<SourceDocument> others = dedupeByFileKey(uploaded.stream()
                .filter(d -> !SourceDocument.CATEGORY_VETTING_SUPPLEMENT.equals(d.getCategory())
                        && !SourceDocument.CATEGORY_PROJECT_INPUT.equals(d.getCategory()))
                .collect(Collectors.toList()));
        return others.isEmpty() ? uploaded : others;
    }

    /** 同一 fileKey 有多份（重复上传 / 旧版本）时只保留正文最完整的一份，并按 9 类文件顺序排列 */
    private List<SourceDocument> dedupeByFileKey(List<SourceDocument> documents) {
        Map<String, SourceDocument> best = new LinkedHashMap<>();
        for (SourceDocument document : documents) {
            SourceDocument exist = best.get(document.getFileKey());
            if (exist == null || nvl(document.getTextContent()).length() > nvl(exist.getTextContent()).length()) {
                best.put(document.getFileKey(), document);
            }
        }
        List<SourceDocument> result = new ArrayList<>(best.values());
        result.sort(Comparator.comparingInt(d -> {
            int index = FILE_ORDER.indexOf(d.getFileKey());
            return index < 0 ? FILE_ORDER.size() : index;
        }));
        return result;
    }

    // ------------------------------------------------------------ 查询

    public List<FindingVO> listFindings(String projectId, String search, String group,
                                        String scope, String fileKey, String page) {
        projectService.require(projectId);
        return query(projectId, search, group, scope, fileKey, page).stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    public MetricsVO metrics(String projectId) {
        projectService.require(projectId);
        return metricsOf(findingRepository.findByProjectIdOrderByCodeAsc(projectId));
    }

    private List<VettingFinding> query(String projectId, String search, String group,
                                       String scope, String fileKey, String page) {
        String keyword = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        return findingRepository.findByProjectIdOrderByCodeAsc(projectId).stream()
                .filter(f -> JsonUtils.isBlankText(group) || "all".equals(group)
                        || group.equals(f.getGroupKey()))
                .filter(f -> JsonUtils.isBlankText(scope) || "all".equals(scope)
                        || scope.equals(f.getScope()))
                .filter(f -> JsonUtils.isBlankText(fileKey) || "all".equals(fileKey)
                        || fileKey.equals(f.getFileKey()))
                .filter(f -> JsonUtils.isBlankText(page) || "all".equals(page)
                        || page.equals(f.getPageNo()))
                .filter(f -> keyword.isEmpty() || matches(f, keyword))
                .collect(Collectors.toList());
    }

    private boolean matches(VettingFinding finding, String keyword) {
        return Stream.of(finding.getCode(), finding.getTitleZhHans(), finding.getTitleEn(),
                        finding.getBodyZhHans(), finding.getBodyEn(), finding.getRefs(),
                        finding.getLocation(), finding.getExpected(), finding.getPatternText(),
                        finding.getTypes(), finding.getFileKey(), finding.getPageNo())
                .filter(Objects::nonNull)
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(keyword));
    }

    @Transactional
    public FindingVO updateStatus(String projectId, String code, String status) {
        VettingFinding finding = findingRepository.findByProjectIdAndCode(projectId, code)
                .orElseThrow(() -> new BizException(4102, "审查发现不存在: " + code));
        String normalized;
        if ("Handled".equals(nvl(status))) {
            normalized = VettingFinding.STATUS_HANDLED;
        } else if ("Assigned".equals(nvl(status))) {
            normalized = VettingFinding.STATUS_ASSIGNED;
        } else {
            normalized = VettingFinding.STATUS_OPEN;
        }
        finding.setStatus(normalized);
        findingRepository.save(finding);
        return toVO(finding);
    }

    /**
     * 点开某条 finding 的来源时，回溯到原文片段。
     *
     * <p>关键约束：只在该 finding 声明的 fileKey 对应文件里找，且必须是**真实命中**。
     * 定位不到就老实返回 {@code located=false}，绝不拿别的文件（更不是封面页）充当证据——
     * 那正是原先「证据链不对」的根源。</p>
     */
    public EvidenceVO evidence(String projectId, String code) {
        projectService.require(projectId);
        VettingFinding finding = findingRepository.findByProjectIdAndCode(projectId, code).orElse(null);
        if (finding == null) {
            return new EvidenceVO("Evidence", false, Collections.<EvidenceItemVO>emptyList());
        }

        List<SourceDocument> scoped = scopedDocuments(projectId, finding.getFileKey());
        List<String> needles = needles(finding);
        List<EvidenceItemVO> items = new ArrayList<>();
        for (SourceDocument document : scoped) {
            Located located = locate(document, needles);
            if (located == null) {
                continue;
            }
            items.add(new EvidenceItemVO(
                    nvl(document.getFileKey()) + " · " + document.getFileName(),
                    located.pageNo,
                    excerpt(document.getTextContent(), located.index)));
            if (items.size() >= 3) {
                break;
            }
        }

        String title = nvl(finding.getCode()) + " · " + nvl(finding.getLocation());
        return new EvidenceVO(title, !items.isEmpty(), items);
    }

    /**
     * 候选检索串，按「越可能逐字命中」排序：
     * quote（模型抄的原文）优先，其次 location，最后才是 refs / expected 里的条款编号片段。
     */
    private List<String> needles(VettingFinding finding) {
        List<String> needles = new ArrayList<>();
        addNeedle(needles, finding.getPatternText(), 8);
        addNeedle(needles, finding.getLocation(), 3);
        for (String raw : new String[]{finding.getRefs(), finding.getExpected()}) {
            if (JsonUtils.isBlankText(raw)) {
                continue;
            }
            for (String token : raw.split("[·→↔|,;\\s]+")) {
                addNeedle(needles, token, 4);
            }
        }
        return needles.stream().distinct().limit(8).collect(Collectors.toList());
    }

    private void addNeedle(List<String> needles, String raw, int minLength) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.length() >= minLength) {
            needles.add(trimmed);
        }
    }

    /**
     * 运行期证据校验用的检索串：整段 quote 优先，其次 location，再退到 refs / expected 里的条款编号片段。
     * 与 {@link #needles(VettingFinding)} 的区别是按「越可能逐字命中」排序且允许更短的片段。
     */
    private List<String> needlesOf(String... raws) {
        List<String> needles = new ArrayList<>();
        for (String raw : raws) {
            if (JsonUtils.isBlankText(raw)) {
                continue;
            }
            addNeedle(needles, raw, 5);
            for (String token : raw.split("[·→↔|,;\\s]+")) {
                addNeedle(needles, token, 5);
            }
        }
        return needles.stream().distinct().limit(8).collect(Collectors.toList());
    }

    /** 按 fileKey 限定候选文件；fileKey 为空（模型没给）时才放开到全部文件 */
    private List<SourceDocument> scopedDocuments(String projectId, String fileKey) {
        List<SourceDocument> candidates = new ArrayList<>();
        for (SourceDocument document : sourceDocumentRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (!JsonUtils.isBlankText(document.getTextContent())) {
                candidates.add(document);
            }
        }
        return byFileKey(candidates, fileKey);
    }

    /**
     * 在给定文件集合里按 fileKey 过滤；fileKey 为空才放开到全部文件。
     *
     * <p>注意必须**严格相等**：早先允许「文档 fileKey 为空时保留」，
     * 结果 finding 声明 NTT 时会把没有编号的邮件 / 纪要也当候选，证据里混进无关文件。</p>
     */
    private List<SourceDocument> byFileKey(List<SourceDocument> documents, String fileKey) {
        if (JsonUtils.isBlankText(fileKey)) {
            return documents;
        }
        List<SourceDocument> result = new ArrayList<>();
        for (SourceDocument document : documents) {
            if (fileKey.equalsIgnoreCase(nvl(document.getFileKey()))) {
                result.add(document);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 原文定位

    /** 命中结果：所在文件 id、字符下标、由 --- Pn --- 反推的真实页码 */
    private static final class Located {
        private final Long documentId;
        private final int index;
        private final String pageNo;

        private Located(Long documentId, int index, String pageNo) {
            this.documentId = documentId;
            this.index = index;
            this.pageNo = pageNo;
        }
    }

    private Located locate(SourceDocument document, List<String> needles) {
        String text = document.getTextContent();
        for (String needle : needles) {
            int index = indexOfLoose(text, needle);
            if (index >= 0) {
                return new Located(document.getId(), index, pageAt(text, index));
            }
        }
        return null;
    }

    /** 跨多份文件定位：检索串按先后优先级遍历，先命中的胜出（quote 优先于 refs） */
    private Located locateIn(List<SourceDocument> scoped, List<String> needles) {
        for (String needle : needles) {
            for (SourceDocument document : scoped) {
                int index = indexOfLoose(document.getTextContent(), needle);
                if (index >= 0) {
                    return new Located(document.getId(), index, pageAt(document.getTextContent(), index));
                }
            }
        }
        return null;
    }

    /**
     * 宽松查找：先原文比对，再逐字符比对并忽略空白差异。
     * 模型给出的 refs 常写作 {@code NTT3(b)}，而正文里是 {@code NTT 3(b)}，只有忽略空白才能命中。
     */
    private int indexOfLoose(String text, String needle) {
        if (JsonUtils.isBlankText(text) || JsonUtils.isBlankText(needle)) {
            return -1;
        }
        String haystack = text;
        String pin = needle.trim();
        int direct = haystack.toLowerCase(Locale.ROOT).indexOf(pin.toLowerCase(Locale.ROOT));
        if (direct >= 0) {
            return direct;
        }
        int limit = haystack.length() - pin.length();
        for (int start = 0; start <= limit; start++) {
            int h = start;
            int p = 0;
            while (p < pin.length()) {
                if (h >= haystack.length()) {
                    break;
                }
                char hc = haystack.charAt(h);
                char pc = pin.charAt(p);
                if (Character.isWhitespace(pc)) {
                    p++;
                    continue;
                }
                if (Character.isWhitespace(hc)) {
                    h++;
                    continue;
                }
                if (Character.toLowerCase(hc) != Character.toLowerCase(pc)) {
                    break;
                }
                h++;
                p++;
            }
            if (p == pin.length()) {
                return start;
            }
        }
        return -1;
    }

    /** 页面标记格式见 DocumentParser#joinPages：{@code --- P12 ---} */
    private static final Pattern PAGE_MARKER = Pattern.compile("---\\s*P(\\d+)\\s*---");

    /** 取该位置之前最近的一个 --- Pn --- 标记作为真实页码；没有标记（DOCX/TXT 单页）返回 null */
    private String pageAt(String text, int index) {
        String page = null;
        Matcher matcher = PAGE_MARKER.matcher(text);
        while (matcher.find()) {
            if (matcher.start() > index) {
                break;
            }
            page = matcher.group(1);
        }
        return page == null ? null : "P" + page;
    }

    private String excerpt(String text, int index) {
        int start = Math.max(0, index - 200);
        int end = Math.min(text.length(), index + 520);
        return (start > 0 ? "..." : "") + text.substring(start, end).trim() + (end < text.length() ? "..." : "");
    }

    public byte[] exportPdf(String projectId, String lang) {
        Project project = projectService.require(projectId);
        List<VettingFinding> findings = findingRepository.findByProjectIdOrderByCodeAsc(projectId);
        if (findings.isEmpty()) {
            throw new BizException(4103, "尚未运行审查，无法导出报告");
        }
        return pdfWriter.write(project, findings, lang);
    }

    public String pdfFileName(String projectId) {
        Project project = projectService.require(projectId);
        return "ConSense_Vetting_Report_" + nvl(project.getContractNo()) + ".pdf";
    }

    // ------------------------------------------------------------ 辅助

    private MetricsVO metricsOf(List<VettingFinding> findings) {
        int reference = count(findings, VettingFinding.GROUP_REFERENCE);
        int conflict = count(findings, VettingFinding.GROUP_CONFLICT);
        int language = count(findings, VettingFinding.GROUP_LANGUAGE);
        int risk = count(findings, VettingFinding.GROUP_RISK);
        int crossFile = (int) findings.stream().filter(f -> "inter".equals(f.getScope())).count();
        return new MetricsVO(reference, conflict, language, risk, findings.size(), crossFile);
    }

    private int count(List<VettingFinding> findings, String group) {
        return (int) findings.stream().filter(f -> group.equals(f.getGroupKey())).count();
    }

    private FindingVO toVO(VettingFinding finding) {
        List<String> types = JsonUtils.isBlankText(finding.getTypes())
                ? Collections.<String>emptyList()
                : Arrays.stream(finding.getTypes().split(",")).map(String::trim).collect(Collectors.toList());
        return new FindingVO(finding.getCode(),
                types,
                finding.getGroupKey(),
                finding.getScope(),
                finding.getSeverity(),
                finding.getStatus(),
                LocalizedText.of(finding.getTitleZhHans(), finding.getTitleZhHant(), finding.getTitleEn()),
                LocalizedText.of(finding.getBodyZhHans(), finding.getBodyZhHant(), finding.getBodyEn()),
                LocalizedText.of(finding.getImpactZhHans(), finding.getImpactZhHant(), finding.getImpactEn()),
                LocalizedText.of(finding.getSuggestionZhHans(), finding.getSuggestionZhHant(),
                        finding.getSuggestionEn()),
                finding.getPatternText(),
                finding.getRefs(),
                finding.getLocation(),
                finding.getExpected(),
                finding.getEvidenceId(),
                finding.getFileKey(),
                finding.getPageNo(),
                finding.getBucketKey());
    }

    private String matchFileKey(String fileName) {
        String name = nvl(fileName).toUpperCase(Locale.ROOT);
        for (String key : FILE_ORDER) {
            if (name.contains(key)) {
                return key;
            }
        }
        if (name.contains("TENDERER")) {
            return "NTT";
        }
        if (name.contains("SPECIFICATION")) {
            return "SL";
        }
        if (name.contains("PRELIMINAR")) {
            return "PRE";
        }
        return null;
    }

    private String normalizeScope(String scope) {
        return "intra".equalsIgnoreCase(nvl(scope)) ? "intra" : "inter";
    }

    private String normalizeSeverity(String severity) {
        String value = nvl(severity).toLowerCase(Locale.ROOT);
        if ("high".equals(value) || "medium".equals(value) || "low".equals(value)) {
            return value;
        }
        return "medium";
    }

    private String normalizeFileKey(String fileKey) {
        String value = nvl(fileKey).toUpperCase(Locale.ROOT);
        return FILE_ORDER.contains(value) ? value : null;
    }

    private String defaultBucket(String group, String bucketKey) {
        if (!JsonUtils.isBlankText(bucketKey)) {
            return bucketKey.trim();
        }
        if (VettingFinding.GROUP_REFERENCE.equals(group)) {
            return "missing";
        }
        if (VettingFinding.GROUP_LANGUAGE.equals(group)) {
            return "grammar";
        }
        if (VettingFinding.GROUP_CONFLICT.equals(group)) {
            return "particulars";
        }
        return "scope";
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
