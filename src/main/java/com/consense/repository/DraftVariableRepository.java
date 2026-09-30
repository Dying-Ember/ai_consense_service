package com.consense.repository;

import com.consense.domain.DraftVariable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DraftVariableRepository extends JpaRepository<DraftVariable, Long> {

    List<DraftVariable> findByProjectIdOrderBySortOrderAsc(String projectId);

    List<DraftVariable> findByProjectIdAndScopeOrderBySortOrderAsc(String projectId, String scope);

    Optional<DraftVariable> findByProjectIdAndVarKey(String projectId, String varKey);

    @Modifying
    @Query("delete from DraftVariable v where v.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
