package com.consense.service;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.SkillDoc;
import com.consense.repository.SkillDocRepository;
import com.consense.service.seed.DemoDataInitializer;
import com.consense.service.seed.SeedModels;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Skills 配置：技能卡片的读取与编辑，"恢复默认"从 resources/seed/skills.json 重新载入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillDocRepository skillDocRepository;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillVO {
        private String id;
        private String code;
        private LocalizedText name;
        private LocalizedText purpose;
        private List<LocalizedText> badges;
        private List<List<LocalizedText>> steps;
        private List<LocalizedText> ruleHead;
        private List<List<String>> rules;
        private List<LocalizedText> outItems;
        private List<LocalizedText> guardItems;
        private int order;
    }

    public List<SkillVO> list() {
        return skillDocRepository.findAllByOrderBySortOrderAsc().stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    public SkillVO get(String id) {
        return toVO(skillDocRepository.findById(id)
                .orElseThrow(() -> new BizException(4300, "技能不存在: " + id)));
    }

    @Transactional
    public SkillVO save(String id, SkillVO vo) {
        SkillDoc doc = skillDocRepository.findById(id)
                .orElseGet(() -> {
                    SkillDoc created = new SkillDoc();
                    created.setId(id);
                    return created;
                });
        doc.setCode(vo.getCode());
        doc.setNameZhHans(vo.getName() == null ? null : vo.getName().getZhHans());
        doc.setNameZhHant(vo.getName() == null ? null : vo.getName().getZhHant());
        doc.setNameEn(vo.getName() == null ? null : vo.getName().getEn());
        doc.setPurposeZhHans(vo.getPurpose() == null ? null : vo.getPurpose().getZhHans());
        doc.setPurposeZhHant(vo.getPurpose() == null ? null : vo.getPurpose().getZhHant());
        doc.setPurposeEn(vo.getPurpose() == null ? null : vo.getPurpose().getEn());
        doc.setContentJson(JsonUtils.write(new DemoDataInitializer.SkillContent(
                vo.getBadges(), vo.getSteps(), vo.getRuleHead(), vo.getRules(),
                vo.getOutItems(), vo.getGuardItems())));
        doc.setSortOrder(vo.getOrder());
        doc.setUpdatedAt(Instant.now());
        skillDocRepository.save(doc);
        return toVO(doc);
    }

    @Transactional
    public SkillVO reset(String id) {
        List<SeedModels.SeedSkill> seeds = readSeeds();
        SeedModels.SeedSkill seed = seeds.stream().filter(s -> id.equals(s.getId())).findFirst()
                .orElseThrow(() -> new BizException(4301, "默认技能配置中不存在: " + id));
        SkillVO vo = new SkillVO(seed.getId(), seed.getCode(), seed.getName(), seed.getPurpose(),
                seed.getBadges(), seed.getSteps(), seed.getRuleHead(), seed.getRules(),
                seed.getOutItems(), seed.getGuardItems(), seeds.indexOf(seed));
        return save(id, vo);
    }

    private SkillVO toVO(SkillDoc doc) {
        DemoDataInitializer.SkillContent content = doc.getContentJson() == null
                ? emptyContent()
                : JsonUtils.read(doc.getContentJson(), DemoDataInitializer.SkillContent.class);
        return new SkillVO(doc.getId(),
                doc.getCode(),
                LocalizedText.of(doc.getNameZhHans(), doc.getNameZhHant(), doc.getNameEn()),
                LocalizedText.of(doc.getPurposeZhHans(), doc.getPurposeZhHant(), doc.getPurposeEn()),
                content.getBadges(), content.getSteps(), content.getRuleHead(),
                content.getRules(), content.getOutItems(), content.getGuardItems(),
                doc.getSortOrder() == null ? 0 : doc.getSortOrder());
    }

    private DemoDataInitializer.SkillContent emptyContent() {
        return new DemoDataInitializer.SkillContent(
                Collections.<LocalizedText>emptyList(),
                Collections.<List<LocalizedText>>emptyList(),
                Collections.<LocalizedText>emptyList(),
                Collections.<List<String>>emptyList(),
                Collections.<LocalizedText>emptyList(),
                Collections.<LocalizedText>emptyList());
    }

    private List<SeedModels.SeedSkill> readSeeds() {
        try (InputStream in = new ClassPathResource("seed/skills.json").getInputStream()) {
            return JsonUtils.mapper().readValue(in,
                    JsonUtils.mapper().getTypeFactory()
                            .constructCollectionType(List.class, SeedModels.SeedSkill.class));
        } catch (Exception e) {
            throw new BizException(4302, "读取默认技能配置失败: " + e.getMessage());
        }
    }
}
