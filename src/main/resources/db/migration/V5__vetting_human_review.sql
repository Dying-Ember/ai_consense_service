-- Human-owned records are nullable for findings created before this migration.
ALTER TABLE vetting_finding ADD COLUMN review_remarks TEXT;
ALTER TABLE vetting_finding ADD COLUMN action_taken TEXT;
ALTER TABLE vetting_finding ADD COLUMN addendum_required BOOLEAN;
ALTER TABLE vetting_finding ADD COLUMN review_updated_at DATETIME(3);
