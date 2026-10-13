package com.consense.domain;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

/** A manual countercheck applies only to one run and its captured requirement/source binding. */
@Getter @Setter
@Entity
@Table(name="vetting_checklist_review",uniqueConstraints=@UniqueConstraint(columnNames={"run_id","item_id"}))
public class VettingChecklistReview {
    @Id @Column(length=64) private String id;
    @Column(name="project_id",length=64,nullable=false) private String projectId;
    @Column(name="run_id",length=64,nullable=false) private String runId;
    @Column(name="item_id",length=64,nullable=false) private String itemId;
    @Column(name="run_snapshot_sha256",length=64,nullable=false) private String runSnapshotSha256;
    @Column(length=24,nullable=false) private String status;
    @Column(columnDefinition="TEXT") private String remarks;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Version private long version;
}
