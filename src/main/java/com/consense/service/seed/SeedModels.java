package com.consense.service.seed;

import com.consense.common.LocalizedText;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * resources/seed/*.json 的反序列化模型。
 * 种子数据全部外置成 JSON，便于直接改文案而不用碰 Java 代码。
 */
public final class SeedModels {

    private SeedModels() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeedProject {
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
    public static class SeedVettingFile {
        private String key;
        private String role;
        private String fileName;
        private String basis;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeedSkill {
        private String id;
        private String code;
        private LocalizedText name;
        private LocalizedText purpose;
        private List<LocalizedText> badges;
        private List<List<LocalizedText>> steps;
        private List<LocalizedText> ruleHead;
        private List<List<String>> rules;
        private List<LocalizedText> outItems;
        private List<LocalizedText> guardItems;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeedFinding {
        private String code;
        private String types;
        private String scope;
        private String severity;
        private String fileKey;
        private String pageNo;
        private String bucketKey;
        private LocalizedText title;
        private LocalizedText body;
        private LocalizedText impact;
        private LocalizedText suggestion;
        private String refs;
        private String location;
        private String expected;
        private String evidenceId;
    }
}
