package com.consense.repository;
import com.consense.domain.DraftPdfArtifact;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DraftPdfArtifactRepository extends JpaRepository<DraftPdfArtifact,String> {
    Optional<DraftPdfArtifact> findFirstByDocxSha256OrderByCreatedAtDesc(String docxSha256);
}
