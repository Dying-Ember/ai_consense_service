package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "vetting_run")
public class VettingRun {
    @Id @Column(length = 64) private String id;
    @Column(name = "project_id", length = 64, nullable = false) private String projectId;
    @Column(length = 16, nullable = false) private String status;
    @Column(length = 64) private String phase;
    @Column(name = "completed_units") private int completedUnits;
    @Column(name = "total_units") private int totalUnits;
    @Column(length = 2048) private String message;
    @Column(length = 2048) private String error;
    @Column(length = 16) private String lang;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Lob @Column(name = "result_json", columnDefinition = "LONGTEXT") private String resultJson;
    @Lob @Column(name = "coverage_json", columnDefinition = "LONGTEXT") private String coverageJson;
    @Lob @Column(name = "model_identity_json", columnDefinition = "LONGTEXT") private String modelIdentityJson;
}
