package com.consense.repository;

import com.consense.domain.VettingFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VettingFindingRepository extends JpaRepository<VettingFinding, Long> {

    List<VettingFinding> findByProjectIdOrderByCodeAsc(String projectId);

    Optional<VettingFinding> findByProjectIdAndCode(String projectId, String code);

    @Modifying
    @Query("delete from VettingFinding f where f.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
