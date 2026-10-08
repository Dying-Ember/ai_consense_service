ALTER TABLE draft_variable ADD COLUMN manually_edited TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE draft_variable ADD COLUMN review_required TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE draft_variable ADD COLUMN candidates_json TEXT;
ALTER TABLE draft_variable ADD COLUMN adopted_sources_json TEXT;
ALTER TABLE draft_document ADD COLUMN snapshot_id VARCHAR(64);
ALTER TABLE draft_document ADD COLUMN rule_version VARCHAR(128);
ALTER TABLE draft_document ADD COLUMN input_snapshot_json LONGTEXT;
ALTER TABLE draft_document ADD COLUMN unresolved_json LONGTEXT;
ALTER TABLE draft_document ADD COLUMN content_edited TINYINT(1) NOT NULL DEFAULT 0;
