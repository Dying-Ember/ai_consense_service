ALTER TABLE vetting_run ADD COLUMN recovery_contract_sha256 VARCHAR(64);
ALTER TABLE vetting_run ADD COLUMN model_profile_explicit BOOLEAN;
ALTER TABLE vetting_run ADD COLUMN execution_owner VARCHAR(64);
ALTER TABLE vetting_run ADD COLUMN lease_until TIMESTAMP(6) NULL;
ALTER TABLE vetting_run ADD COLUMN recovery_status VARCHAR(32);
ALTER TABLE vetting_run ADD COLUMN report_snapshot_sha256 VARCHAR(64);

CREATE TABLE vetting_packet_checkpoint (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    observation_sha256 VARCHAR(64) NOT NULL,
    packet_id VARCHAR(256) NOT NULL,
    source_snapshot_sha256 VARCHAR(64) NOT NULL,
    prompt_envelope_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_json LONGTEXT,
    result_sha256 VARCHAR(64),
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uq_vetting_packet_observation (run_id, observation_sha256)
);
CREATE TABLE vetting_report_snapshot (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    KEY ix_vetting_report_run (run_id)
);
