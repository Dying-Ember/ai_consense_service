package com.consense.web.dto;

import com.consense.common.LocalizedText;
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
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AskRequest {
        private String question;
        private String scope;
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
