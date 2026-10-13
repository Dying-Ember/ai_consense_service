package com.consense.repository;

import com.consense.domain.VettingRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

public interface VettingRunRepository extends JpaRepository<VettingRun, String> {
    Optional<VettingRun> findFirstByProjectIdOrderByStartedAtDesc(String projectId);
    List<VettingRun> findByStatusIn(List<String> statuses);

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VettingRun r where r.id = :id")
    Optional<VettingRun> lockById(@Param("id") String id);

    /** Closed scalar projection: polling never materializes the run or its report payloads. */
    interface ProgressView {
        String getId();
        String getStatus();
        String getPhase();
        int getCompletedUnits();
        int getTotalUnits();
        String getMessage();
        String getError();
        Instant getStartedAt();
        Instant getFinishedAt();
        String getSourceSnapshotSha256();
        String getModelIdentityJson();
        String getRecoveryStatus();
    }

    String PROGRESS_SELECT = "select r.id as id, r.status as status, r.phase as phase, "
            + "r.completedUnits as completedUnits, r.totalUnits as totalUnits, r.message as message, "
            + "r.error as error, r.startedAt as startedAt, r.finishedAt as finishedAt, "
            + "r.sourceSnapshotSha256 as sourceSnapshotSha256, r.modelIdentityJson as modelIdentityJson, r.recoveryStatus as recoveryStatus from VettingRun r ";

    @Query(PROGRESS_SELECT + "where r.projectId = :projectId and r.id = :id")
    Optional<ProgressView> findProgress(@Param("projectId") String projectId, @Param("id") String id);

    @Query(PROGRESS_SELECT + "where r.projectId = :projectId order by r.startedAt desc")
    List<ProgressView> findLatestProgress(@Param("projectId") String projectId, Pageable pageable);
}
