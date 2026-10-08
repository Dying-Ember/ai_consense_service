package com.consense.repository;

import com.consense.domain.DraftExtractionRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftExtractionRunRepository extends JpaRepository<DraftExtractionRun,String> {
    Optional<DraftExtractionRun> findFirstByProjectIdOrderByFinishedAtDescIdDesc(String projectId);
    Optional<DraftExtractionRun> findByIdAndProjectId(String id,String projectId);
    List<DraftExtractionRun> findByProjectIdOrderByFinishedAtDescIdDesc(String projectId,Pageable page);
}
