package com.consense.domain;

import java.time.Instant;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Append-only DOCX revision. DraftDocument selects the current revision without rewriting old bytes. */
@Getter @Setter @Entity @org.hibernate.annotations.Immutable
@Table(name="draft_artifact")
public class DraftArtifact {
    @Id @Column(length=64) private String id;
    @Column(name="project_id",length=64,nullable=false) private String projectId;
    @Column(name="file_key",length=16,nullable=false) private String fileKey;
    @Column(name="snapshot_id",length=64,nullable=false) private String snapshotId;
    @Column(name="source_sha256",length=64,nullable=false) private String sourceSha256;
    @Column(name="docx_sha256",length=64,nullable=false) private String docxSha256;
    @Lob @Column(name="docx_bytes",columnDefinition="LONGBLOB",nullable=false) private byte[] docxBytes;
    @Lob @Column(name="ledger_json",columnDefinition="LONGTEXT") private String ledgerJson;
    @Lob @Column(name="blocks_json",columnDefinition="LONGTEXT") private String blocksJson;
    @Lob @Column(name="field_impacts_json",columnDefinition="LONGTEXT") private String fieldImpactsJson;
    @Lob @Column(name="bindings_json",columnDefinition="LONGTEXT") private String bindingsJson;
    @Column(name="parent_revision_id",length=64) private String parentRevisionId;
    @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
    public byte[] getDocxBytes(){return docxBytes==null?null:docxBytes.clone();}
    public void setDocxBytes(byte[] bytes){docxBytes=bytes==null?null:bytes.clone();}
}
