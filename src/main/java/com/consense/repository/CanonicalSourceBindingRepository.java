package com.consense.repository;

import com.consense.domain.CanonicalSourceBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface CanonicalSourceBindingRepository extends JpaRepository<CanonicalSourceBinding,Long> {
    Optional<CanonicalSourceBinding> findByProjectIdAndDocumentIdAndProjectionSha256(String projectId,Long documentId,String projectionSha256);
    List<CanonicalSourceBinding> findByProjectIdAndDocumentIdOrderByIdDesc(String projectId,Long documentId);
}
