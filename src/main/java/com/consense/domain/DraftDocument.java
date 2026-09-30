package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 生成出来的文稿（NTT / SCT / SCC）。
 */
@Getter
@Setter
@Entity
@Table(name = "draft_document", uniqueConstraints = @UniqueConstraint(
        name = "uk_draft_doc", columnNames = {"project_id", "file_key"}))
public class DraftDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(name = "file_key", length = 16, nullable = false)
    private String fileKey;

    @Column(length = 512)
    private String title;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "`generated`", nullable = false)
    private Boolean generated = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
