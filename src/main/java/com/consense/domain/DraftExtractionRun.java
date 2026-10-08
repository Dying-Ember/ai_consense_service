package com.consense.domain;

import java.time.Instant;
import javax.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** A finalized extraction report is an append-only record, independent of adoption. */
@Entity @Immutable @Getter @NoArgsConstructor
@Table(name="draft_extraction_run",indexes=@Index(name="idx_draft_extraction_project_finished",columnList="project_id,finished_at"))
public class DraftExtractionRun {
    @Id @Column(length=64) private String id;
    @Column(name="project_id",length=64,nullable=false) private String projectId;
    @Column(name="finished_at",nullable=false) private Instant finishedAt;
    @Lob @Column(name="trace_json",columnDefinition="LONGTEXT",nullable=false) private String traceJson;

    public DraftExtractionRun(String id,String projectId,Instant finishedAt,String traceJson) {
        this.id=id;this.projectId=projectId;this.finishedAt=finishedAt;this.traceJson=traceJson;
    }
}
