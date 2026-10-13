package com.consense.domain;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

@Getter @Setter @Entity @Table(name="vetting_report_snapshot")
@org.hibernate.annotations.Immutable
public class VettingReportSnapshot {
    @Id @Column(length=64) private String id;
    @Column(name="run_id",length=64,nullable=false) private String runId;
    @Lob @Column(name="payload_json",columnDefinition="LONGTEXT",nullable=false) private String payloadJson;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
