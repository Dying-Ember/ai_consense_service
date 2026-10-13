-- Legacy rows remain unresolved: no inferred identities or automatic adoption.
ALTER TABLE source_document ADD COLUMN source_sha256 VARCHAR(64) NULL;
ALTER TABLE source_document ADD COLUMN oer_identity_json LONGTEXT NULL;
ALTER TABLE source_document ADD COLUMN native_pdf_evidence_json LONGTEXT NULL;

CREATE TABLE canonical_source_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id VARCHAR(64) NOT NULL,
    document_id BIGINT NOT NULL,
    projection_sha256 VARCHAR(64) NOT NULL,
    original_source_sha256 VARCHAR(64) NOT NULL,
    java_parsed_source_hash VARCHAR(64) NOT NULL,
    routing_sha256 VARCHAR(64) NOT NULL,
    source_revision_sha256 VARCHAR(64) NOT NULL,
    projection_revision_sha256 VARCHAR(64) NOT NULL,
    canonical_source_hash VARCHAR(64) NOT NULL,
    scope_policy_sha256 VARCHAR(64) NOT NULL,
    adoption_manifest_sha256 VARCHAR(64) NOT NULL,
    review_reference_sha256 VARCHAR(64) NOT NULL,
    snapshot_bytes_sha256 VARCHAR(64) NOT NULL,
    declared_module VARCHAR(16) NOT NULL,
    eligible_modules_json LONGTEXT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_canonical_source_binding UNIQUE (project_id, document_id, projection_sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
