package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 审查发现（finding）。归入四类分组：reference / conflict / language / risk。
 */
@Getter
@Setter
@Entity
@Table(name = "vetting_finding", uniqueConstraints = @UniqueConstraint(
        name = "uk_finding_code", columnNames = {"project_id", "code"}))
public class VettingFinding {

    public static final String GROUP_REFERENCE = "reference";
    public static final String GROUP_CONFLICT = "conflict";
    public static final String GROUP_LANGUAGE = "language";
    public static final String GROUP_RISK = "risk";

    public static final String STATUS_OPEN = "Open";
    public static final String STATUS_HANDLED = "Handled";
    public static final String STATUS_ASSIGNED = "Assigned";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(length = 32, nullable = false)
    private String code;

    /** 底层 F2 类别，逗号分隔，如 "f2-v,f2-viii" */
    @Column(length = 160)
    private String types;

    @Column(name = "group_key", length = 32, nullable = false)
    private String groupKey;

    /** inter（跨文件） / intra（文件内） */
    @Column(length = 16)
    private String scope;

    /** high / medium / low */
    @Column(length = 16)
    private String severity;

    @Column(length = 32, nullable = false)
    private String status = STATUS_OPEN;

    @Lob
    @Column(name = "title_zh_hans", columnDefinition = "TEXT")
    private String titleZhHans;

    @Lob
    @Column(name = "title_zh_hant", columnDefinition = "TEXT")
    private String titleZhHant;

    @Lob
    @Column(name = "title_en", columnDefinition = "TEXT")
    private String titleEn;

    @Lob
    @Column(name = "body_zh_hans", columnDefinition = "TEXT")
    private String bodyZhHans;

    @Lob
    @Column(name = "body_zh_hant", columnDefinition = "TEXT")
    private String bodyZhHant;

    @Lob
    @Column(name = "body_en", columnDefinition = "TEXT")
    private String bodyEn;

    @Lob
    @Column(name = "impact_zh_hans", columnDefinition = "TEXT")
    private String impactZhHans;

    @Lob
    @Column(name = "impact_zh_hant", columnDefinition = "TEXT")
    private String impactZhHant;

    @Lob
    @Column(name = "impact_en", columnDefinition = "TEXT")
    private String impactEn;

    @Lob
    @Column(name = "suggestion_zh_hans", columnDefinition = "TEXT")
    private String suggestionZhHans;

    @Lob
    @Column(name = "suggestion_zh_hant", columnDefinition = "TEXT")
    private String suggestionZhHant;

    @Lob
    @Column(name = "suggestion_en", columnDefinition = "TEXT")
    private String suggestionEn;

    @Column(name = "pattern_text", length = 512)
    private String patternText;

    @Column(length = 512)
    private String refs;

    @Column(length = 512)
    private String location;

    @Column(length = 512)
    private String expected;

    @Column(name = "evidence_id", length = 64)
    private String evidenceId;

    @Column(name = "file_key", length = 16)
    private String fileKey;

    @Column(name = "page_no", length = 16)
    private String pageNo;

    /** 三栏定位器第二栏的分组键（变量 / 错误类别 / 页码） */
    @Column(name = "bucket_key", length = 64)
    private String bucketKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
