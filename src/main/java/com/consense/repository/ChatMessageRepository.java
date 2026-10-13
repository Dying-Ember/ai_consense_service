package com.consense.repository;

import com.consense.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByProjectIdOrderByIdAsc(String projectId);

    List<ChatMessage> findByProjectIdAndSessionIdOrderByIdAsc(String projectId, String sessionId);
    List<ChatMessage> findByProjectIdAndSessionIdOrderByIdDesc(String projectId, String sessionId, org.springframework.data.domain.Pageable page);
    List<ChatMessage> findByProjectIdAndSessionIdIsNullOrderByIdAsc(String projectId);

    @Modifying
    @Query("delete from ChatMessage m where m.projectId = :projectId and m.sessionId = :sessionId")
    void deleteBySession(@Param("projectId") String projectId, @Param("sessionId") String sessionId);

    @Modifying
    @Query("delete from ChatMessage m where m.projectId = :projectId and m.sessionId is null")
    void deleteLegacyByProjectId(@Param("projectId") String projectId);

    @Modifying
    @Query("delete from ChatMessage m where m.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
