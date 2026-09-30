package com.consense.repository;

import com.consense.domain.DraftDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DraftDocumentRepository extends JpaRepository<DraftDocument, Long> {

    List<DraftDocument> findByProjectIdOrderByIdAsc(String projectId);

    Optional<DraftDocument> findByProjectIdAndFileKey(String projectId, String fileKey);

    @Modifying
    @Query("delete from DraftDocument d where d.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
