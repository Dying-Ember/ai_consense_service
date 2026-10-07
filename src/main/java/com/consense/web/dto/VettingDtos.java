package com.consense.web.dto;

import com.consense.common.LocalizedText;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.time.Instant;
import javax.validation.constraints.Size;

public final class VettingDtos {

    private VettingDtos() {
    }

    /** 审查源集里的一份文件 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VettingFileVO {
        private String key;
        private LocalizedText role;
        private String fileName;
        private String basis;
        private String status;
        private boolean generated;
        private boolean parsed;
        private int pageCount;
        private String parseStatus;
        private String parseMessage;
        private int textChars;
        private boolean ocrUsed;
        private List<String> warnings;
        /** Stable machine-readable input role, alongside the localized display label. */
        private String sourceRole;
    }

    /** 一条审查发现 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FindingVO {
        private String code;
        private List<String> types;
        private String group;
        private String scope;
        private String severity;
        private String status;
        private LocalizedText title;
        private LocalizedText body;
        private LocalizedText impact;
        private LocalizedText suggestion;
        private String pattern;
        private String refs;
        private String location;
        private String expected;
        private String evidenceId;
        private String fileKey;
        private String pageNo;
        private String bucketKey;
        private String fingerprint;
        private String runId;
        private String source;
        private String verification;
        private List<FindingEvidence> evidence;
        private String reviewRemarks;
        private String actionTaken;
        private Boolean addendumRequired;
        /** Server-owned timestamp; not accepted by ReviewUpdateRequest. */
        private Instant reviewUpdatedAt;
    }

    /** Full replacement of human review records, independent of finding status. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewUpdateRequest {
        @Size(max = 4000, message = "must not exceed 4000 characters")
        private String reviewRemarks;
        @Size(max = 4000, message = "must not exceed 4000 characters")
        private String actionTaken;
        private Boolean addendumRequired;
    }

    /** 四类问题计数 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsVO {
        private int reference;
        private int conflict;
        private int language;
        private int risk;
        private int total;
        private int crossFile;
    }

    /** 审查运行结果 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunResultVO {
        private int total;
        private MetricsVO metrics;
        private List<String> messages;
        private String model;
        private com.consense.ai.ModelIdentity modelIdentity;
        public RunResultVO(int total,MetricsVO metrics,List<String> messages,String model){this.total=total;this.metrics=metrics;this.messages=messages;this.model=model;}
    }

    /** 简单 K/V 证据条目，用于建议弹窗与来源弹窗 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceItemVO {
        /** 来源文件标签：fileKey · 文件名 */
        private String code;
        /** 原文中该片段所在页码（由正文 --- Pn --- 标记反推，可能为 null） */
        private String pageNo;
        private String text;
        private String side;
        private String documentId;
        private String fileKey;
        private String fileName;
        private String anchor;
        private String quote;
        private boolean located;
        private String sourceHash;
        private List<Double> bbox;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String packetId;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String packetSourceSnapshotSha256;
        /** Preserve the legacy source-dialog twelve-argument constructor. */
        public EvidenceItemVO(String code,String pageNo,String text,String side,String documentId,String fileKey,String fileName,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox) {
            this.code=code;this.pageNo=pageNo;this.text=text;this.side=side;this.documentId=documentId;this.fileKey=fileKey;this.fileName=fileName;this.anchor=anchor;this.quote=quote;this.located=located;this.sourceHash=sourceHash;this.bbox=bbox;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceVO {
        private String title;
        /** 是否在原文里逐字定位到；false 时 items 为空，前端应提示「未能定位原文」而非展示兜底内容 */
        private boolean located;
        private List<EvidenceItemVO> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class FindingEvidence {
        private String side;
        private String documentId;
        private String fileKey;
        private String fileName;
        private String pageNo;
        private String anchor;
        private String quote;
        private boolean located;
        private String sourceHash;
        private List<Double> bbox;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String packetId;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String packetSourceSnapshotSha256;
        /** Preserve legacy/rule ten-argument construction and serialization. */
        public FindingEvidence(String side,String documentId,String fileKey,String fileName,String pageNo,String anchor,String quote,boolean located,String sourceHash,List<Double> bbox) {
            this.side=side;this.documentId=documentId;this.fileKey=fileKey;this.fileName=fileName;this.pageNo=pageNo;this.anchor=anchor;this.quote=quote;this.located=located;this.sourceHash=sourceHash;this.bbox=bbox;
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DocumentCoverageVO {
        private String documentId;
        private String fileKey;
        private String fileName;
        private String parseStatus;
        private int textChars;
        private int reviewedChars;
        private int totalSegments;
        private int reviewedSegments;
        private List<String> warnings;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CoverageVO {
        private int totalDocuments;
        private int reviewedDocuments;
        private List<DocumentCoverageVO> documents;
        private List<String> warnings;
        private List<SemanticTopicVO> semanticTopics = new java.util.ArrayList<>();
        public CoverageVO(int totalDocuments,int reviewedDocuments,List<DocumentCoverageVO> documents,List<String> warnings) {
            this.totalDocuments=totalDocuments;this.reviewedDocuments=reviewedDocuments;this.documents=documents;this.warnings=warnings;
        }
    }

    /** Actual call/decoding ledger, never a claim of correct or complete contractual review. */
    @Data @NoArgsConstructor
    public static class SemanticTopicVO {
        private int topicIndex;
        private String topic,status,error,rawResponseSha256;
        private String reviewKind,selectionStrategy,failureKind;
        private com.fasterxml.jackson.databind.JsonNode modelResponseMetadata;
        private List<String> referenceIds = new java.util.ArrayList<>();
        private List<String> submittedChunkIds = new java.util.ArrayList<>();
        private List<String> unresolvedChunkIds = new java.util.ArrayList<>();
        private int submittedChars,budgetDroppedGroups,partialContextGroups,unresolvedSegments;
        private int initialContextBudgetChars,effectiveContextBudgetChars,missingLocatedReferenceTargets;
        private boolean contextExpansionAttempted,contextExpanded;
        private int returnedAssessments,issueAssessments,consistentAssessments,insufficientContextAssessments;
        private int acceptedFindings,rejectedRecords;
        /** Legacy status above describes the global call/decode only. This is the coverage authority. */
        private String globalCallStatus,aggregateReviewStatus;
        private String requestPacketPolicy,reviewCoverageScope = "observed_source_requests_only_qualifiers_unknown";
        private int sourceRequestCount,pendingSourceRequestCount,extraPacketCount,failedPacketCount,notSubmittedPacketCount;
        private boolean semanticScopeVerified;
        private List<SemanticPacketVO> packetAudits = new java.util.ArrayList<>();
        private List<SemanticSourceRequestVO> sourceRequests = new java.util.ArrayList<>();
    }

    @Data @NoArgsConstructor
    public static class SemanticPacketVO {
        private String packetId,sourceObservationPacketId,kind,status,failureKind,error,rawResponseSha256,sourceSnapshotSha256,promptEnvelopeSha256;
        private int packetIndex,submittedChars;
        private List<String> submittedChunkIds = new java.util.ArrayList<>(), requestIds = new java.util.ArrayList<>();
        private String inputBudgetStatus = "budget_unknown";
        private com.fasterxml.jackson.databind.JsonNode inputBudgetMetadata,modelResponseMetadata;
        private int returnedAssessments,issueAssessments,consistentAssessments,insufficientContextAssessments,acceptedFindings,rejectedRecords;
        private boolean actualGatewayCallStarted,semanticScopeVerified;
    }
    @Data @NoArgsConstructor
    public static class SemanticSourceRequestVO {
        private String requestId,kind,observationId,transportStatus,unknownReason,reviewExecutionStatus,packetId;
        private int packIndex,contentChars;
        private List<String> eligibleOriginIds = new java.util.ArrayList<>(),requiredChunkIds = new java.util.ArrayList<>(),missingChunkIds = new java.util.ArrayList<>();
        private com.fasterxml.jackson.databind.JsonNode sourceTrace,originObservation;
        private String applicability = "unknown",qualifiersComplete = "unknown";
        private boolean semanticScopeVerified;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class VettingJobVO {
        private String id;
        private String status;
        private String phase;
        private int completedUnits;
        private int totalUnits;
        private String message;
        private Instant startedAt;
        private Instant finishedAt;
        private RunResultVO result;
        private String error;
        private CoverageVO coverage;
        private com.consense.ai.ModelIdentity modelIdentity;
        public VettingJobVO(String id,String status,String phase,int completedUnits,int totalUnits,String message,Instant startedAt,Instant finishedAt,RunResultVO result,String error,CoverageVO coverage){this.id=id;this.status=status;this.phase=phase;this.completedUnits=completedUnits;this.totalUnits=totalUnits;this.message=message;this.startedAt=startedAt;this.finishedAt=finishedAt;this.result=result;this.error=error;this.coverage=coverage;}
    }
}
