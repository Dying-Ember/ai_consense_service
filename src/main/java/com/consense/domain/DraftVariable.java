package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Fixed drafting inputs use INPUT. BASE / FILE remain for preserved legacy records.
 */
@Getter
@Setter
@Entity
@Table(name = "draft_variable", uniqueConstraints = @UniqueConstraint(
        name = "uk_draft_var", columnNames = {"project_id", "var_key"}))
public class DraftVariable {

    public static final String SCOPE_INPUT = "INPUT";
    public static final String SCOPE_BASE = "BASE";
    public static final String SCOPE_FILE = "FILE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(name = "var_key", length = 64, nullable = false)
    private String varKey;

    @Column(length = 16, nullable = false)
    private String scope;

    @Column(name = "file_key", length = 16)
    private String fileKey;

    @Column(name = "label_zh_hans", length = 512)
    private String labelZhHans;

    @Column(name = "label_zh_hant", length = 512)
    private String labelZhHant;

    @Column(name = "label_en", length = 512)
    private String labelEn;

    /** delete / notused / choice / rewrite / fill */
    @Column(length = 16)
    private String action;

    @Lob
    @Column(name = "value_text", columnDefinition = "TEXT")
    private String valueText;

    @Column(length = 255)
    private String choice;

    @Lob
    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    @Column(nullable = false)
    private Boolean confirmed = false;

    @Column(name = "confirmed_from", length = 16)
    private String confirmedFrom;

    @Column(name = "source_ref", length = 512)
    private String sourceRef;

    @Lob
    @Column(name = "result_text", columnDefinition = "TEXT")
    private String resultText;

    @Lob
    @Column(name = "note_text", columnDefinition = "TEXT")
    private String noteText;

    /** Editing a value adopts it for this draft, independently of a confirm button. */
    @Column(name = "manually_edited", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private Boolean manuallyEdited = false;

    @Column(name = "review_required", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private Boolean reviewRequired = false;

    /** Model candidates never replace a manually adopted value. */
    @Lob
    @Column(name = "candidates_json", columnDefinition = "TEXT")
    private String candidatesJson;

    @Lob
    @Column(name = "adopted_sources_json", columnDefinition = "TEXT")
    private String adoptedSourcesJson;

    /** 链式变量：确认一次即同步到关联变量 */
    @Column(name = "linked_base", length = 64)
    private String linkedBase;

    /** 派生变量依赖，如 bondform 依赖 V05,V06 */
    @Column(name = "derived_from", length = 128)
    private String derivedFrom;

    /** 影响到的文件，逗号分隔：NTT,SCT,SCC */
    @Column(length = 128)
    private String affects;

    /** 空 | list （清单型变量） */
    @Column(length = 16)
    private String kind;

    @Lob
    @Column(name = "cols_json", columnDefinition = "TEXT")
    private String colsJson;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
