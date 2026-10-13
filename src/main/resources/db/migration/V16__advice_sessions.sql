CREATE TABLE advice_session (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    source_scope VARCHAR(32) NOT NULL,
    basis_scope VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_advice_session_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
CREATE INDEX idx_advice_session_project ON advice_session(project_id, created_at);
ALTER TABLE chat_message ADD COLUMN session_id VARCHAR(64) NULL;
ALTER TABLE chat_message ADD COLUMN source_snapshot_sha256 VARCHAR(64) NULL;
CREATE INDEX idx_chat_message_session ON chat_message(project_id, session_id, id);
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id) REFERENCES advice_session(id) ON DELETE CASCADE;
