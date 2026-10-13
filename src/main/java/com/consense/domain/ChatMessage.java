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

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "source_snapshot_sha256", length = 64)
    private String sourceSnapshotSha256;

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

    @Column(name = "evidence_id", length = 256)
    private String evidenceId;

    /** Frozen configured chat identity; absent for legacy and deterministic no-basis messages. */
    @Lob
    @Column(name = "model_identity_json", columnDefinition = "LONGTEXT")
    private String modelIdentityJson;

    /** Immutable submitted evidence snapshot; historical answers never relink to current text. */
    @Lob @Column(name = "sources_json", columnDefinition = "LONGTEXT")
    private String sourcesJson;

    @Lob @Column(name = "clarifications_json", columnDefinition = "LONGTEXT")
    private String clarificationsJson;

    @Column(name = "source_scope", length = 32)
    private String sourceScope;

    @Column(name = "basis_scope", length = 32)
    private String basisScope;

    @Column(name = "reply_to_message_id")
    private Long replyToMessageId;

    @Column(name = "question_message_id")
    private Long questionMessageId;

    @Lob @Column(name = "original_question", columnDefinition = "LONGTEXT")
    private String originalQuestion;

    @Lob @Column(name = "user_background_json", columnDefinition = "LONGTEXT")
    private String userBackgroundJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
