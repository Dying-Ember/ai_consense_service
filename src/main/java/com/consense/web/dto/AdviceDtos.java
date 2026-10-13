package com.consense.web.dto;

import com.consense.common.LocalizedText;
import com.consense.ai.ModelIdentity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public final class AdviceDtos {

    private AdviceDtos() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessageVO {
        private Long id;
        private String role;
        private LocalizedText title;
        private LocalizedText content;
        private String unknownScope;
        private List<String> citations;
        private String evidenceId;
        private List<CitationVO> sources;
        private ModelIdentity modelIdentity;
        private List<String> clarifications;
        private String sourceScope;
        private String basisScope;
        private String sessionId;
        public ChatMessageVO(Long id,String role,LocalizedText title,LocalizedText content,String unknownScope,List<String> citations,String evidenceId,List<CitationVO> sources,ModelIdentity modelIdentity,List<String> clarifications,String sourceScope,String basisScope) {
            this(id,role,title,content,unknownScope,citations,evidenceId,sources,modelIdentity,clarifications,sourceScope,basisScope,null);
        }
        public ChatMessageVO(Long id,String role,LocalizedText title,LocalizedText content,String unknownScope,List<String> citations,String evidenceId,List<CitationVO> sources,ModelIdentity modelIdentity) {
            this(id,role,title,content,unknownScope,citations,evidenceId,sources,modelIdentity,null,null,null);
        }
        public ChatMessageVO(Long id,String role,LocalizedText title,LocalizedText content,String unknownScope,List<String> citations,String evidenceId,List<CitationVO> sources,ModelIdentity modelIdentity,List<String> clarifications,String sourceScope) {
            this(id,role,title,content,unknownScope,citations,evidenceId,sources,modelIdentity,clarifications,sourceScope,null);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CitationVO {
        private String fileLabel;
        private String pageNo;
        private String anchor;
        private String content;
        private double score;
        private String evidenceId;
        private String documentId;
        private String sourceHash;
        private String sourceRole;
        private Boolean applicabilityContextComplete;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private List<CanonicalEvidenceDtos.EvidencePointer> canonicalEvidence;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode canonicalSourceUnit;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String scoreMeaning;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.fasterxml.jackson.databind.JsonNode fixedBaselineEvidence;
        public CitationVO(String fileLabel,String pageNo,String anchor,String content,double score,String evidenceId,String documentId,String sourceHash,String sourceRole,Boolean applicabilityContextComplete,List<CanonicalEvidenceDtos.EvidencePointer> canonicalEvidence,com.fasterxml.jackson.databind.JsonNode canonicalSourceUnit,String scoreMeaning) {
            this(fileLabel,pageNo,anchor,content,score,evidenceId,documentId,sourceHash,sourceRole,applicabilityContextComplete);
            this.canonicalEvidence=canonicalEvidence;this.canonicalSourceUnit=canonicalSourceUnit;this.scoreMeaning=scoreMeaning;
        }
        public CitationVO(String fileLabel,String pageNo,String anchor,String content,double score,String evidenceId,String documentId,String sourceHash,String sourceRole,Boolean applicabilityContextComplete) {
            this.fileLabel=fileLabel;this.pageNo=pageNo;this.anchor=anchor;this.content=content;this.score=score;this.evidenceId=evidenceId;
            this.documentId=documentId;this.sourceHash=sourceHash;this.sourceRole=sourceRole;this.applicabilityContextComplete=applicabilityContextComplete;
        }
        public CitationVO(String fileLabel,String pageNo,String anchor,String content,double score) {
            this(fileLabel,pageNo,anchor,content,score,null,null,null,null,null);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AskRequest {
        private String question;
        private String scope;
        private Long replyToMessageId;
        /** Explicit standard interpretation only; absent retains the project-contract route. */
        private String basisScope;
        private String sessionId;
        public AskRequest(String question,String scope,Long replyToMessageId,String basisScope){this(question,scope,replyToMessageId,basisScope,null);}
        public AskRequest(String question,String scope,Long replyToMessageId){this(question,scope,replyToMessageId,null);}
        public AskRequest(String question,String scope){this(question,scope,null);}
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AskResponseVO {
        private boolean grounded;
        private LocalizedText title;
        private LocalizedText content;
        private String unknownScope;
        private List<String> citations;
        private String evidenceId;
        private List<CitationVO> sources;
        private String model;
        private ModelIdentity modelIdentity;
        private List<String> clarifications;
        private String sourceScope;
        private String basisScope;
        private Long messageId;
        private String sessionId;
        public AskResponseVO(boolean grounded,LocalizedText title,LocalizedText content,String unknownScope,List<String> citations,String evidenceId,List<CitationVO> sources,String model,ModelIdentity modelIdentity) {
            this(grounded,title,content,unknownScope,citations,evidenceId,sources,model,modelIdentity,null,null,null,null,null);
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SessionRequest {
        private String scope;
        private String basisScope;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SessionVO {
        private String id;
        private String projectId;
        private String scope;
        private String basisScope;
        private java.time.Instant createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class AdviceSourceVO {
        private String documentId;
        private String fileKey;
        private String fileName;
        private String sourceRole;
        private String documentScope;
        private String parseStatus;
        private String sourceHash;
        private boolean included;
        private String exclusionReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexStatusVO {
        private int chunks;
        private int indexed;
        private String vectorProvider;
        private String collection;
        private String embedModel;
        private String llmModel;
        private boolean llmReady;
        private boolean vectorReady;
        private boolean ocrReady;
        private LocalizedText message;
        private boolean stale;
        private int eligibleDocuments;
        private int indexedDocuments;
        private boolean embeddingReady;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String evidenceBackend;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String sourceScope;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String indexKey;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String indexSignature;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String indexReason;
        public IndexStatusVO(int chunks,int indexed,String vectorProvider,String collection,String embedModel,String llmModel,boolean llmReady,boolean vectorReady,boolean ocrReady,LocalizedText message,boolean stale,int eligibleDocuments,int indexedDocuments,boolean embeddingReady) {
            this.chunks=chunks;this.indexed=indexed;this.vectorProvider=vectorProvider;this.collection=collection;this.embedModel=embedModel;this.llmModel=llmModel;
            this.llmReady=llmReady;this.vectorReady=vectorReady;this.ocrReady=ocrReady;this.message=message;this.stale=stale;
            this.eligibleDocuments=eligibleDocuments;this.indexedDocuments=indexedDocuments;this.embeddingReady=embeddingReady;
        }
        public IndexStatusVO(int chunks,int indexed,String vectorProvider,String collection,String embedModel,String llmModel,boolean llmReady,boolean vectorReady,boolean ocrReady,LocalizedText message,boolean stale,int eligibleDocuments,int indexedDocuments) {
            this(chunks,indexed,vectorProvider,collection,embedModel,llmModel,llmReady,vectorReady,ocrReady,message,stale,eligibleDocuments,indexedDocuments,false);
        }
        public IndexStatusVO(int chunks,int indexed,String vectorProvider,String collection,String embedModel,String llmModel,boolean llmReady,boolean vectorReady,boolean ocrReady,LocalizedText message) {
            this(chunks,indexed,vectorProvider,collection,embedModel,llmModel,llmReady,vectorReady,ocrReady,message,false,0,0);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuickQuestionVO {
        private LocalizedText text;
        private Boolean unknown;
        private String evidenceId;
    }
}
