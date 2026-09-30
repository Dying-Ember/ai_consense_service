package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Skills 配置：infer（模板变量推理）/ extract（事实抽取）/ vetting（审查规则）。
 */
@Getter
@Setter
@Entity
@Table(name = "skill_doc")
public class SkillDoc {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64)
    private String code;

    @Column(name = "name_zh_hans", length = 255)
    private String nameZhHans;

    @Column(name = "name_zh_hant", length = 255)
    private String nameZhHant;

    @Column(name = "name_en", length = 255)
    private String nameEn;

    @Lob
    @Column(name = "purpose_zh_hans", columnDefinition = "TEXT")
    private String purposeZhHans;

    @Lob
    @Column(name = "purpose_zh_hant", columnDefinition = "TEXT")
    private String purposeZhHant;

    @Lob
    @Column(name = "purpose_en", columnDefinition = "TEXT")
    private String purposeEn;

    /** 完整结构：badges / steps / ruleHead / rules / outItems / guardItems */
    @Lob
    @Column(name = "content_json", columnDefinition = "LONGTEXT")
    private String contentJson;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
