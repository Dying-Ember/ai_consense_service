ALTER TABLE draft_document ADD COLUMN revision_id VARCHAR(64);
CREATE TABLE draft_artifact (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 file_key VARCHAR(16) NOT NULL,
 snapshot_id VARCHAR(64) NOT NULL,
 source_sha256 VARCHAR(64) NOT NULL,
 docx_sha256 VARCHAR(64) NOT NULL,
 docx_bytes LONGBLOB NOT NULL,
 ledger_json LONGTEXT,
 blocks_json LONGTEXT,
 field_impacts_json LONGTEXT,
 parent_revision_id VARCHAR(64),
 created_at DATETIME(6) NOT NULL,
 INDEX idx_draft_artifact_project (project_id,file_key)
);
CREATE TABLE draft_pdf_artifact (
    id VARCHAR(64) PRIMARY KEY,
    docx_sha256 VARCHAR(64) NOT NULL,
    pdf_sha256 VARCHAR(64) NOT NULL,
    render_profile_hash VARCHAR(64) NOT NULL,
    pdf_bytes LONGBLOB NOT NULL,
    manifest_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_draft_pdf_docx ON draft_pdf_artifact(docx_sha256);
