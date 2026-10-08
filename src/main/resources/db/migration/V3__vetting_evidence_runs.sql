ALTER TABLE source_document ADD COLUMN structured_content_json LONGTEXT;
ALTER TABLE source_document ADD COLUMN parse_coverage_json LONGTEXT;
ALTER TABLE vetting_finding ADD COLUMN fingerprint VARCHAR(64);
ALTER TABLE vetting_finding ADD COLUMN run_id VARCHAR(64);
ALTER TABLE vetting_finding ADD COLUMN source VARCHAR(16);
ALTER TABLE vetting_finding ADD COLUMN verification VARCHAR(16);
ALTER TABLE vetting_finding ADD COLUMN evidence_json LONGTEXT;
ALTER TABLE vetting_finding ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX ix_finding_fingerprint ON vetting_finding(project_id, fingerprint);
CREATE TABLE vetting_run (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    phase VARCHAR(64),
    completed_units INT NOT NULL DEFAULT 0,
    total_units INT NOT NULL DEFAULT 0,
    message VARCHAR(2048),
    error VARCHAR(2048),
    lang VARCHAR(16),
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    result_json LONGTEXT,
    coverage_json LONGTEXT
);
CREATE INDEX ix_vetting_run_project ON vetting_run(project_id, started_at);
