-- Existing source review pins keep their original meaning. Family identity uses separate nullable pins.
ALTER TABLE canonical_source_binding MODIFY COLUMN adoption_manifest_sha256 VARCHAR(64) NULL;
ALTER TABLE canonical_source_binding MODIFY COLUMN review_reference_sha256 VARCHAR(64) NULL;
ALTER TABLE canonical_source_binding ADD COLUMN family_composition_sha256 VARCHAR(64) NULL;
ALTER TABLE canonical_source_binding ADD COLUMN family_component_set_sha256 VARCHAR(64) NULL;
