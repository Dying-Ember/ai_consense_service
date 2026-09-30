package com.consense.service.seed;

import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.domain.SkillDoc;
import com.consense.domain.SourceDocument;
import com.consense.domain.VettingFinding;
import com.consense.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 首次启动时把演示数据写入数据库（已存在则不覆盖）。
 * 对应设计稿里的 3 个 demo 项目、9 类审查源文件、3 个技能与示例审查发现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private static final List<String> GENERATED_KEYS = Arrays.asList("NTT", "SCT", "SCC");

    private final ProjectRepository projectRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final VettingFindingRepository findingRepository;
    private final SkillDocRepository skillDocRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<SeedModels.SeedProject> projects = readSeed("seed/projects.json", SeedModels.SeedProject.class);
        List<SeedModels.SeedVettingFile> vettingFiles = readSeed("seed/vetting-files.json", SeedModels.SeedVettingFile.class);
        List<SeedModels.SeedFinding> findings = readSeed("seed/findings.json", SeedModels.SeedFinding.class);

        seedProjects(projects, vettingFiles);
        seedFindings(findings);
        seedSkills(readSeed("seed/skills.json", SeedModels.SeedSkill.class));
    }

    private void seedProjects(List<SeedModels.SeedProject> projects, List<SeedModels.SeedVettingFile> files) {
        for (SeedModels.SeedProject seed : projects) {
            if (projectRepository.existsById(seed.getId())) {
                continue;
            }
            Project project = new Project();
            project.setId(seed.getId());
            project.setNameZhHans(seed.getName().getZhHans());
            project.setNameZhHant(seed.getName().getZhHant());
            project.setNameEn(seed.getName().getEn());
            project.setContractNo(seed.getContractNo());
            project.setPackageRef(seed.getPackageRef());
            project.setOutputReferenceFile(seed.getOutputReferenceFile());
            project.setPages(seed.getPages());
            project.setNttRange(seed.getNttRange());
            project.setSctRange(seed.getSctRange());
            project.setSccRange(seed.getSccRange());
            project.setSpecification(seed.getSpecification());
            project.setTitleLinesJson(JsonUtils.write(seed.getTitleLines()));
            project.setCreatedAt(Instant.now());
            project.setUpdatedAt(Instant.now());
            projectRepository.save(project);

            // 预置 9 类审查源文件的"占位"记录，等待真实文件上传
            for (SeedModels.SeedVettingFile file : files) {
                SourceDocument document = new SourceDocument();
                document.setProjectId(seed.getId());
                document.setCategory(SourceDocument.CATEGORY_VETTING_SUPPLEMENT);
                document.setFileKey(file.getKey());
                document.setFileName(file.getFileName());
                document.setParseStatus(GENERATED_KEYS.contains(file.getKey()) ? "GENERATED" : "PENDING");
                document.setParseMessage(file.getRole());
                document.setSizeBytes(0L);
                document.setPageCount(0);
                document.setOcrUsed(false);
                document.setCreatedAt(Instant.now());
                sourceDocumentRepository.save(document);
            }
            log.info("已初始化演示项目: {} ({})", seed.getName().getEn(), seed.getId());
        }

        if (!projects.isEmpty()) {
            log.info("项目种子数据就绪，共 {} 个项目", projects.size());
        }
    }

    private void seedFindings(List<SeedModels.SeedFinding> seeds) {
        String projectId = "demoProject";
        if (!projectRepository.existsById(projectId) || seeds.isEmpty()) {
            return;
        }
        if (!findingRepository.findByProjectIdOrderByCodeAsc(projectId).isEmpty()) {
            return;
        }
        for (SeedModels.SeedFinding seed : seeds) {
            VettingFinding finding = new VettingFinding();
            finding.setProjectId(projectId);
            finding.setCode(seed.getCode());
            finding.setTypes(seed.getTypes());
            finding.setGroupKey(groupOf(seed.getTypes()));
            finding.setScope(seed.getScope());
            finding.setSeverity(seed.getSeverity());
            finding.setStatus(VettingFinding.STATUS_OPEN);
            finding.setTitleZhHans(seed.getTitle().getZhHans());
            finding.setTitleZhHant(seed.getTitle().getZhHant());
            finding.setTitleEn(seed.getTitle().getEn());
            finding.setBodyZhHans(seed.getBody().getZhHans());
            finding.setBodyZhHant(seed.getBody().getZhHant());
            finding.setBodyEn(seed.getBody().getEn());
            finding.setImpactZhHans(seed.getImpact().getZhHans());
            finding.setImpactZhHant(seed.getImpact().getZhHant());
            finding.setImpactEn(seed.getImpact().getEn());
            finding.setSuggestionZhHans(seed.getSuggestion().getZhHans());
            finding.setSuggestionZhHant(seed.getSuggestion().getZhHant());
            finding.setSuggestionEn(seed.getSuggestion().getEn());
            finding.setRefs(seed.getRefs());
            finding.setLocation(seed.getLocation());
            finding.setExpected(seed.getExpected());
            finding.setEvidenceId(seed.getEvidenceId());
            finding.setFileKey(seed.getFileKey());
            finding.setPageNo(seed.getPageNo());
            finding.setBucketKey(seed.getBucketKey());
            finding.setCreatedAt(Instant.now());
            findingRepository.save(finding);
        }
        log.info("已为 {} 初始化 {} 条示例审查发现", projectId, seeds.size());
    }

    private void seedSkills(List<SeedModels.SeedSkill> seeds) {
        int order = 0;
        for (SeedModels.SeedSkill seed : seeds) {
            if (skillDocRepository.existsById(seed.getId())) {
                continue;
            }
            SkillDoc doc = new SkillDoc();
            doc.setId(seed.getId());
            doc.setCode(seed.getCode());
            doc.setNameZhHans(seed.getName().getZhHans());
            doc.setNameZhHant(seed.getName().getZhHant());
            doc.setNameEn(seed.getName().getEn());
            doc.setPurposeZhHans(seed.getPurpose().getZhHans());
            doc.setPurposeZhHant(seed.getPurpose().getZhHant());
            doc.setPurposeEn(seed.getPurpose().getEn());
            doc.setContentJson(JsonUtils.write(new SkillContent(seed.getBadges(), seed.getSteps(),
                    seed.getRuleHead(), seed.getRules(), seed.getOutItems(), seed.getGuardItems())));
            doc.setSortOrder(order++);
            doc.setUpdatedAt(Instant.now());
            skillDocRepository.save(doc);
        }
        log.info("技能配置种子数据就绪，共 {} 个技能", seeds.size());
    }

    /**
     * 底层 F2 类别 → 四类展示分组的映射。
     * 命中多个类别时按 FINDING_GROUP_PRIORITY 取第一个命中的分组（与设计稿一致）。
     */
    public static String groupOf(String types) {
        if (JsonUtils.isBlankText(types)) {
            return VettingFinding.GROUP_RISK;
        }
        List<String> present = Arrays.stream(types.split(","))
                .map(String::trim).collect(Collectors.toList());
        for (String candidate : FINDING_GROUP_PRIORITY) {
            if (present.contains(candidate)) {
                return FINDING_GROUP_MAP.getOrDefault(candidate, VettingFinding.GROUP_RISK);
            }
        }
        return VettingFinding.GROUP_RISK;
    }

    private static final List<String> FINDING_GROUP_PRIORITY = Collections.unmodifiableList(Arrays.asList(
            "f2-iii", "f2-iv", "f2-i", "f2-vi", "f2-viii", "f2-ix", "f2-v", "f2-vii", "f2-ii"));

    private static final Map<String, String> FINDING_GROUP_MAP;

    static {
        Map<String, String> map = new HashMap<>();
        map.put("f2-iii", VettingFinding.GROUP_REFERENCE);
        map.put("f2-iv", VettingFinding.GROUP_REFERENCE);
        map.put("f2-i", VettingFinding.GROUP_CONFLICT);
        map.put("f2-vi", VettingFinding.GROUP_CONFLICT);
        map.put("f2-viii", VettingFinding.GROUP_CONFLICT);
        map.put("f2-v", VettingFinding.GROUP_LANGUAGE);
        map.put("f2-ix", VettingFinding.GROUP_LANGUAGE);
        map.put("f2-ii", VettingFinding.GROUP_RISK);
        map.put("f2-vii", VettingFinding.GROUP_RISK);
        FINDING_GROUP_MAP = Collections.unmodifiableMap(map);
    }

    private <T> List<T> readSeed(String path, Class<T> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return JsonUtils.mapper().readValue(in,
                    JsonUtils.mapper().getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception e) {
            log.warn("读取种子数据 {} 失败: {}", path, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** SkillDoc.contentJson 的结构（构造器签名与原 record 一致） */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SkillContent {

        private List<LocalizedText> badges;
        private List<List<LocalizedText>> steps;
        private List<LocalizedText> ruleHead;
        private List<List<String>> rules;
        private List<LocalizedText> outItems;
        private List<LocalizedText> guardItems;
    }
}
