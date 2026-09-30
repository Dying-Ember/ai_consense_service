package com.consense.web.dto;

import com.consense.common.LocalizedText;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;

import java.util.List;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectVO {
        private String id;
        private LocalizedText name;
        private String contractNo;
        private String packageRef;
        private String outputReferenceFile;
        private String pages;
        private String nttRange;
        private String sctRange;
        private String sccRange;
        private String specification;
        private List<LocalizedText> titleLines;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectRequest {
        @NotBlank
        private String id;
        @NotBlank
        private String nameZhHans;
        private String nameZhHant;
        private String nameEn;
        private String contractNo;
        private String packageRef;
        private String outputReferenceFile;
        private String pages;
        private String nttRange;
        private String sctRange;
        private String sccRange;
        private String specification;
    }
}
