package com.consense.domain;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

/** One immutable verified result per observed packet and run. */
@Getter @Setter @Entity
@Table(name="vetting_packet_checkpoint",uniqueConstraints=@UniqueConstraint(columnNames={"run_id","observation_sha256"}))
public class VettingPacketCheckpoint {
    @Id @Column(length=64) private String id;
    @Column(name="run_id",length=64,nullable=false) private String runId;
    @Column(name="observation_sha256",length=64,nullable=false) private String observationSha256;
    @Column(name="packet_id",length=256,nullable=false) private String packetId;
    @Column(name="source_snapshot_sha256",length=64,nullable=false) private String sourceSnapshotSha256;
    @Column(name="prompt_envelope_sha256",length=64,nullable=false) private String promptEnvelopeSha256;
    @Column(length=16,nullable=false) private String status;
    @Lob @Column(name="result_json",columnDefinition="LONGTEXT") private String resultJson;
    @Column(name="result_sha256",length=64) private String resultSha256;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
