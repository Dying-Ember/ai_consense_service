package com.consense.repository;

import com.consense.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByProjectIdOrderByIdAsc(String projectId);

    @Modifying
    @Query("delete from ChatMessage m where m.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
