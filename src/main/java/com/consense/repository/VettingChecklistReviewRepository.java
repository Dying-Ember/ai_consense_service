package com.consense.repository;

import com.consense.domain.VettingChecklistReview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VettingChecklistReviewRepository extends JpaRepository<VettingChecklistReview,String> {
    List<VettingChecklistReview> findByProjectIdAndRunId(String projectId,String runId);
}
