CREATE TABLE IF NOT EXISTS vetting_checklist_review (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    item_id VARCHAR(64) NOT NULL,
    run_snapshot_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    remarks TEXT,
    updated_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_vetting_checklist_run_item UNIQUE (run_id,item_id),
    CONSTRAINT fk_vetting_checklist_run FOREIGN KEY (run_id) REFERENCES vetting_run(id) ON DELETE CASCADE
);
