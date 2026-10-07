package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 上传的原始素材：标准模板、起草证据、审查源文件、审查补充文件。
 */
@Getter
@Setter
@Entity
@Table(name = "source_document")
public class SourceDocument {

    /** 标准模板（NTT/SCT/SCC 基线） */
    public static final String CATEGORY_STANDARD_TEMPLATE = "STANDARD_TEMPLATE";
    /** 项目沟通证据（邮件 / 纪要 / 备忘 / 澄清） */
    public static final String CATEGORY_PROJECT_INPUT = "PROJECT_INPUT";
    /** 审查整份招标文件包 */
    public static final String CATEGORY_VETTING_PACKAGE = "VETTING_PACKAGE";
    /** 审查补充文件 */
    public static final String CATEGORY_VETTING_SUPPLEMENT = "VETTING_SUPPLEMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(length = 32, nullable = false)
    private String category;

    /** NTT / SCT / SCC / GCT / FT / AA / GCC / SL / PRE，未识别为 null */
    @Column(name = "file_key", length = 32)
    private String fileKey;

    /** Vetting input role: tender / standard / project_fact / package_manifest; null is legacy auto. */
    @Column(name = "review_role", length = 32)
    private String reviewRole;

    @Column(name = "file_name", length = 512, nullable = false)
    private String fileName;

    @Column(name = "content_type", length = 160)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes = 0L;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    /** PENDING / PARSING / PARSED / FAILED */
    @Column(name = "parse_status", length = 32, nullable = false)
    private String parseStatus = "PENDING";

    @Column(name = "parse_message", length = 1024)
    private String parseMessage;

    @Column(name = "page_count")
    private Integer pageCount = 0;

    @Column(name = "ocr_used", nullable = false)
    private Boolean ocrUsed = false;

    @Lob
    @Column(name = "text_content", columnDefinition = "LONGTEXT")
    private String textContent;

    /** Original structure / evidence locations. No artificial DOCX page numbers. */
    @Lob
    @Column(name = "structured_content_json", columnDefinition = "LONGTEXT")
    private String structuredContentJson;

    @Lob
    @Column(name = "parse_coverage_json", columnDefinition = "LONGTEXT")
    private String parseCoverageJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
