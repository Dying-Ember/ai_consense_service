package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(length = 16, nullable = false)
    private String role;

    /** 助手气泡标题，如 "Answer with basis" / "I don't know" */
    @Column(length = 255)
    private String title;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 命中 guardrail 时记录范围：fullset / tender / contract */
    @Column(name = "unknown_scope", length = 32)
    private String unknownScope;

    @Lob
    @Column(name = "citations_json", columnDefinition = "TEXT")
    private String citationsJson;

    @Column(name = "evidence_id", length = 64)
    private String evidenceId;

    /** Frozen configured chat identity; absent for legacy and deterministic no-basis messages. */
    @Lob
    @Column(name = "model_identity_json", columnDefinition = "LONGTEXT")
    private String modelIdentityJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
