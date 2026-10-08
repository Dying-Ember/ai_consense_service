-- Preserve the configured chat adapter identity without relabelling older messages.
ALTER TABLE chat_message ADD COLUMN model_identity_json LONGTEXT NULL;
