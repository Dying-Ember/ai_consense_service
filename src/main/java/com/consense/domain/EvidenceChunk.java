package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 证据切片：原文落在 MySQL，向量落在 Qdrant（point_id 关联）。
 */
@Getter
@Setter
@Entity
@Table(name = "evidence_chunk")
public class EvidenceChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex = 0;

    /** 展示用的来源标签，如 "SCC · Special Conditions of Contract.pdf" */
    @Column(name = "file_label", length = 512)
    private String fileLabel;

    @Column(name = "page_no", length = 16)
    private String pageNo;

    /** 条款锚点，如 "SCC4.1" */
    @Column(length = 255)
    private String anchor;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "point_id", length = 64)
    private String pointId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
