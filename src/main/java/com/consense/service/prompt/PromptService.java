package com.consense.service.prompt;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.PromptTemplate;
import com.consense.repository.PromptTemplateRepository;
import com.consense.web.dto.PromptDtos.PromptVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 提示词配置服务：把原先硬编码在 DraftPrompts 里的提示词搬进数据库，支持前端在线编辑。
 *
 * <p>读取策略：DB 里有值用 DB 的（用户改过的），没有则回退到 {@link PromptCatalog} 的出厂默认。
 * 启动时会把出厂默认同步进 DB（供「恢复默认」与「是否已自定义」比对）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    private final PromptTemplateRepository repository;

    // ------------------------------------------------------------ 启动初始化

    /**
     * 启动时把注册表里的提示词写进数据库：
     * <ul>
     *   <li>记录不存在 —— 新插一条，当前值与出厂默认都取常量；</li>
     *   <li>记录已存在 —— 出厂默认同步为最新常量（便于「恢复默认」拿到新默认）；
     *       当前值仅在「没被用户改过」（等于旧默认）时才跟着更新，避免覆盖用户自定义。</li>
     * </ul>
     */
    @PostConstruct
    @Transactional
    public void bootstrap() {
        for (PromptCatalog.Spec spec : PromptCatalog.specs()) {
            PromptTemplate entity = repository.findById(spec.key).orElse(null);
            if (entity == null) {
                entity = new PromptTemplate();
                entity.setPromptKey(spec.key);
                entity.setGroupKey(spec.group);
                entity.setNameZhHans(spec.nameZhHans);
                entity.setNameZhHant(spec.nameZhHant);
                entity.setNameEn(spec.nameEn);
                entity.setDescriptionZhHans(spec.descriptionZhHans);
                entity.setDescriptionZhHant(spec.descriptionZhHant);
                entity.setDescriptionEn(spec.descriptionEn);
                entity.setSystemText(spec.defaultSystem);
                entity.setUserTemplate(spec.defaultUserTemplate);
                entity.setDefaultSystemText(spec.defaultSystem);
                entity.setDefaultUserTemplate(spec.defaultUserTemplate);
                entity.setSortOrder(spec.sortOrder);
                entity.setUpdatedAt(Instant.now());
                repository.save(entity);
                log.info("prompt seeded: {}", spec.key);
                continue;
            }
            boolean untouched = Objects.equals(entity.getSystemText(), entity.getDefaultSystemText())
                    && Objects.equals(entity.getUserTemplate(), entity.getDefaultUserTemplate());
            entity.setDefaultSystemText(spec.defaultSystem);
            entity.setDefaultUserTemplate(spec.defaultUserTemplate);
            if (untouched) {
                entity.setSystemText(spec.defaultSystem);
                entity.setUserTemplate(spec.defaultUserTemplate);
            }
            entity.setNameZhHans(spec.nameZhHans);
            entity.setNameZhHant(spec.nameZhHant);
            entity.setNameEn(spec.nameEn);
            entity.setDescriptionZhHans(spec.descriptionZhHans);
            entity.setDescriptionZhHant(spec.descriptionZhHant);
            entity.setDescriptionEn(spec.descriptionEn);
            entity.setSortOrder(spec.sortOrder);
            repository.save(entity);
        }
    }

    // ------------------------------------------------------------ 占位符填充

    /**
     * 占位符填充：优先走 {@link String#format}（标准 %s 语义）；
     * 若模板里含裸 % 或转义异常（用户在线编辑时容易手滑），退化为按顺序替换 %s，
     * 保证提示词写错不至于让整个模型调用直接抛异常。
     */
    public static String format(String template, Object... args) {
        if (template == null) {
            return "";
        }
        try {
            return String.format(template, args);
        } catch (RuntimeException ex) {
            log.warn("提示词 String.format 失败（模板可能含非法 % 转义），退化为顺序替换: {}", ex.getMessage());
            return formatByPosition(template, args);
        }
    }

    private static String formatByPosition(String template, Object... args) {
        StringBuilder builder = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            boolean placeholder = c == '%' && i + 1 < template.length()
                    && template.charAt(i + 1) == 's' && argIndex < args.length;
            if (placeholder) {
                builder.append(args[argIndex++]);
                i += 2;
                continue;
            }
            builder.append(c);
            i++;
        }
        // 参数多于占位符时追加到末尾，避免静默丢数据
        for (; argIndex < args.length; argIndex++) {
            builder.append('\n').append(args[argIndex]);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------ 消费方读取

    /** 取生效的 system prompt；DB 无值则回退出厂默认 */
    public String system(String key) {
        PromptTemplate entity = repository.findById(key).orElse(null);
        if (entity != null && !JsonUtils.isBlankText(entity.getSystemText())) {
            return entity.getSystemText();
        }
        PromptCatalog.Spec spec = PromptCatalog.find(key);
        return spec == null ? "" : spec.defaultSystem;
    }

    /** 取生效的 user 模板；DB 无值则回退出厂默认 */
    public String userTemplate(String key) {
        PromptTemplate entity = repository.findById(key).orElse(null);
        if (entity != null && !JsonUtils.isBlankText(entity.getUserTemplate())) {
            return entity.getUserTemplate();
        }
        PromptCatalog.Spec spec = PromptCatalog.find(key);
        return spec == null ? "" : spec.defaultUserTemplate;
    }

    // ------------------------------------------------------------ 管理接口

    @Transactional(readOnly = true)
    public List<PromptVO> list() {
        List<PromptVO> result = new ArrayList<>();
        for (PromptTemplate entity : repository.findAllByOrderBySortOrderAsc()) {
            result.add(toVO(entity));
        }
        return result;
    }

    /** 按分组组织（前端左侧分组树） */
    @Transactional(readOnly = true)
    public Map<String, List<PromptVO>> grouped() {
        Map<String, List<PromptVO>> map = new LinkedHashMap<>();
        for (PromptVO vo : list()) {
            map.computeIfAbsent(vo.getGroup(), k -> new ArrayList<>()).add(vo);
        }
        return map;
    }

    @Transactional
    public PromptVO save(String key, String systemText, String userTemplate) {
        PromptTemplate entity = require(key);
        if (!JsonUtils.isBlankText(systemText)) {
            entity.setSystemText(systemText);
        } else {
            entity.setSystemText(entity.getDefaultSystemText());
        }
        if (!JsonUtils.isBlankText(userTemplate)) {
            entity.setUserTemplate(userTemplate);
        } else {
            entity.setUserTemplate(entity.getDefaultUserTemplate());
        }
        entity.setUpdatedAt(Instant.now());
        return toVO(repository.save(entity));
    }

    /** 恢复出厂默认 */
    @Transactional
    public PromptVO reset(String key) {
        PromptTemplate entity = require(key);
        entity.setSystemText(entity.getDefaultSystemText());
        entity.setUserTemplate(entity.getDefaultUserTemplate());
        entity.setUpdatedAt(Instant.now());
        return toVO(repository.save(entity));
    }

    // ------------------------------------------------------------ 内部

    private PromptTemplate require(String key) {
        return repository.findById(key)
                .orElseThrow(() -> new BizException(4401, "提示词不存在: " + key));
    }

    private PromptVO toVO(PromptTemplate entity) {
        boolean customized = !Objects.equals(entity.getSystemText(), entity.getDefaultSystemText())
                || !Objects.equals(entity.getUserTemplate(), entity.getDefaultUserTemplate());
        return new PromptVO(
                entity.getPromptKey(),
                entity.getGroupKey(),
                LocalizedText.of(entity.getNameZhHans(), entity.getNameZhHant(), entity.getNameEn()),
                LocalizedText.of(entity.getDescriptionZhHans(), entity.getDescriptionZhHant(),
                        entity.getDescriptionEn()),
                entity.getSystemText(),
                entity.getUserTemplate(),
                entity.getDefaultSystemText(),
                entity.getDefaultUserTemplate(),
                customized,
                countPlaceholders(entity.getUserTemplate()),
                entity.getSortOrder() == null ? 0 : entity.getSortOrder(),
                entity.getUpdatedAt());
    }

    /** 统计 %s 占位符个数（%% 转义不计） */
    private int countPlaceholders(String template) {
        if (JsonUtils.isBlankText(template)) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i + 1 < template.length(); i++) {
            if (template.charAt(i) == '%' && template.charAt(i + 1) == 's') {
                count++;
                i++;
            }
        }
        return count;
    }
}
