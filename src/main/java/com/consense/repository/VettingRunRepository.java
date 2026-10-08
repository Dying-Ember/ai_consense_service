package com.consense.repository;

import com.consense.domain.VettingRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface VettingRunRepository extends JpaRepository<VettingRun, String> {
    Optional<VettingRun> findFirstByProjectIdOrderByStartedAtDesc(String projectId);
    List<VettingRun> findByStatusIn(List<String> statuses);
}
