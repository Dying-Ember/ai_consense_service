-- H2-only repair for an existing local database created with forced MySQL LONGTEXT.
-- Stop the owning application and make an offline backup first. Do not use on MySQL.
-- Inspect INFORMATION_SCHEMA.COLUMNS before and after, and reopen to verify full values.
-- This script is not automatically run by the application or by Flyway.
ALTER TABLE source_document ALTER COLUMN oer_identity_json CLOB;
ALTER TABLE source_document ALTER COLUMN native_pdf_evidence_json CLOB;
ALTER TABLE source_document ALTER COLUMN text_content CLOB;
ALTER TABLE source_document ALTER COLUMN structured_content_json CLOB;
ALTER TABLE source_document ALTER COLUMN parse_coverage_json CLOB;
ALTER TABLE canonical_source_binding ALTER COLUMN eligible_modules_json CLOB NOT NULL;
ALTER TABLE canonical_source_binding ALTER COLUMN snapshot_json CLOB NOT NULL;
CHECKPOINT SYNC;
