package com.consense.web.dto;

import com.consense.common.LocalizedText;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
}
