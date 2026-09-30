package com.consense.repository;

import com.consense.domain.SourceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, Long> {

    List<SourceDocument> findByProjectIdAndCategoryOrderByIdAsc(String projectId, String category);

    List<SourceDocument> findByProjectIdOrderByIdAsc(String projectId);

    Optional<SourceDocument> findFirstByProjectIdAndFileKey(String projectId, String fileKey);

    Optional<SourceDocument> findFirstByProjectIdAndFileKeyAndCategory(String projectId, String fileKey, String category);

    @Modifying
    @Query("delete from SourceDocument d where d.projectId = :projectId and d.category in :categories")
    void deleteByProjectIdAndCategories(@Param("projectId") String projectId,
                                        @Param("categories") List<String> categories);

    @Modifying
    @Query("delete from SourceDocument d where d.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
