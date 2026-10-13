package com.consense.domain;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

@Getter @Setter @Entity
@Table(name = "advice_session")
public class AdviceSession {
    @Id @Column(length = 64) private String id;
    @Column(name = "project_id", length = 64, nullable = false) private String projectId;
    @Column(name = "source_scope", length = 32, nullable = false) private String scope;
    @Column(name = "basis_scope", length = 32, nullable = false) private String basisScope;
    @Column(nullable = false) private long revision;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
}
