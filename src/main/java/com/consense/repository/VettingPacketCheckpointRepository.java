package com.consense.repository;

import com.consense.domain.VettingPacketCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VettingPacketCheckpointRepository extends JpaRepository<VettingPacketCheckpoint,String> {
    Optional<VettingPacketCheckpoint> findByRunIdAndObservationSha256(String runId,String observationSha256);
    List<VettingPacketCheckpoint> findByRunIdOrderByUpdatedAtAsc(String runId);
}
