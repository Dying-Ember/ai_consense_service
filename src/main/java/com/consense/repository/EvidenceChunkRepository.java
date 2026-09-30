package com.consense.repository;

import com.consense.domain.EvidenceChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EvidenceChunkRepository extends JpaRepository<EvidenceChunk, Long> {

    List<EvidenceChunk> findByProjectIdOrderByChunkIndexAsc(String projectId);

    List<EvidenceChunk> findByProjectIdAndDocumentIdOrderByChunkIndexAsc(String projectId, Long documentId);

    @Modifying
    @Query("delete from EvidenceChunk c where c.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);

    @Modifying
    @Query("delete from EvidenceChunk c where c.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);
}
