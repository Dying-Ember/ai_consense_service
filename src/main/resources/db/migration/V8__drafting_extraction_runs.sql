CREATE TABLE draft_extraction_run (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    finished_at TIMESTAMP(6) NOT NULL,
    trace_json LONGTEXT NOT NULL
);
CREATE INDEX idx_draft_extraction_project_finished ON draft_extraction_run(project_id,finished_at);
