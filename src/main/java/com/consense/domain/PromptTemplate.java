package com.consense.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.Instant;

/**
 * 提示词配置：一条记录 = 一个模型功能（system prompt + user 模板成对）。
 *
 * <p>出厂默认值放在 default_* 字段，system_text / user_template 是当前生效值；
 * 「恢复默认」只把当前值重置为 default_*。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "prompt_template")
public class PromptTemplate {

    /** 变量识别（起草流程） */
    public static final String KEY_DRAFTING_DISCOVER = "drafting.discover";
    /** 文稿起草 */
    public static final String KEY_DRAFTING_CLAUSE = "drafting.clause";
    /** 招标文件审查 */
    public static final String KEY_VETTING_RUN = "vetting.run";
    /** 合同条款问答 */
    public static final String KEY_ADVICE_ASK = "advice.ask";

    public static final String GROUP_DRAFTING = "drafting";
    public static final String GROUP_VETTING = "vetting";
    public static final String GROUP_ADVICE = "advice";

    @Id
    @Column(name = "prompt_key", length = 64, nullable = false)
    private String promptKey;

    @Column(name = "group_key", length = 32, nullable = false)
    private String groupKey;

    @Column(name = "name_zh_hans", length = 255)
    private String nameZhHans;

    @Column(name = "name_zh_hant", length = 255)
    private String nameZhHant;

    @Column(name = "name_en", length = 255)
    private String nameEn;

    @Lob
    @Column(name = "description_zh_hans", columnDefinition = "TEXT")
    private String descriptionZhHans;

    @Lob
    @Column(name = "description_zh_hant", columnDefinition = "TEXT")
    private String descriptionZhHant;

    @Lob
    @Column(name = "description_en", columnDefinition = "TEXT")
    private String descriptionEn;

    @Lob
    @Column(name = "system_text", columnDefinition = "LONGTEXT")
    private String systemText;

    @Lob
    @Column(name = "user_template", columnDefinition = "LONGTEXT")
    private String userTemplate;

    @Lob
    @Column(name = "default_system_text", columnDefinition = "LONGTEXT")
    private String defaultSystemText;

    @Lob
    @Column(name = "default_user_template", columnDefinition = "LONGTEXT")
    private String defaultUserTemplate;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
