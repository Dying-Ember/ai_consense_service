package com.consense.domain;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

/** Immutable source review snapshot; no latest-file or parser-candidate alias. */
@Getter @Setter @Entity
@Table(name="canonical_source_binding",uniqueConstraints=@UniqueConstraint(name="uk_canonical_source_binding",
        columnNames={"project_id","document_id","projection_sha256"}))
public class CanonicalSourceBinding {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="project_id",length=64,nullable=false) private String projectId;
    @Column(name="document_id",nullable=false) private Long documentId;
    @Column(name="projection_sha256",length=64,nullable=false) private String projectionSha256;
    @Column(name="original_source_sha256",length=64,nullable=false) private String originalSourceSha256;
    @Column(name="java_parsed_source_hash",length=64,nullable=false) private String javaParsedSourceHash;
    @Column(name="routing_sha256",length=64,nullable=false) private String routingSha256;
    @Column(name="source_revision_sha256",length=64,nullable=false) private String sourceRevisionSha256;
    @Column(name="projection_revision_sha256",length=64,nullable=false) private String projectionRevisionSha256;
    @Column(name="canonical_source_hash",length=64,nullable=false) private String canonicalSourceHash;
    @Column(name="scope_policy_sha256",length=64,nullable=false) private String scopePolicySha256;
    @Column(name="adoption_manifest_sha256",length=64) private String adoptionManifestSha256;
    @Column(name="review_reference_sha256",length=64) private String reviewReferenceSha256;
    @Column(name="family_composition_sha256",length=64) private String familyCompositionSha256;
    @Column(name="family_component_set_sha256",length=64) private String familyComponentSetSha256;
    @Column(name="snapshot_bytes_sha256",length=64,nullable=false) private String snapshotBytesSha256;
    @Column(name="declared_module",length=16,nullable=false) private String declaredModule;
    @Lob @Column(name="eligible_modules_json",nullable=false) private String eligibleModulesJson;
    @Lob @Column(name="snapshot_json",nullable=false) private String snapshotJson;
    @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
}
