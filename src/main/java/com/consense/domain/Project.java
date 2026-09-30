package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "project")
public class Project {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "name_zh_hans", length = 512)
    private String nameZhHans;

    @Column(name = "name_zh_hant", length = 512)
    private String nameZhHant;

    @Column(name = "name_en", length = 512)
    private String nameEn;

    @Column(name = "contract_no", length = 128)
    private String contractNo;

    @Column(name = "package_ref", length = 128)
    private String packageRef;

    @Column(name = "output_reference_file", length = 512)
    private String outputReferenceFile;

    @Column(length = 16)
    private String pages;

    @Column(name = "ntt_range", length = 128)
    private String nttRange;

    @Column(name = "sct_range", length = 128)
    private String sctRange;

    @Column(name = "scc_range", length = 128)
    private String sccRange;

    @Column(length = 512)
    private String specification;

    @Lob
    @Column(name = "title_lines_json", columnDefinition = "TEXT")
    private String titleLinesJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
