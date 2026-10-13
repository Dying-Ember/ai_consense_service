package com.consense.repository;

import com.consense.domain.AdviceSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface AdviceSessionRepository extends JpaRepository<AdviceSession, String> {
    List<AdviceSession> findByProjectIdOrderByCreatedAtAscIdAsc(String projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AdviceSession s where s.id = :id and s.projectId = :projectId")
    Optional<AdviceSession> lockSession(@Param("projectId") String projectId, @Param("id") String id);
}
