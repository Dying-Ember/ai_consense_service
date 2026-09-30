package com.consense.web.dto;

import com.consense.common.LocalizedText;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** 提示词配置相关 DTO */
public final class PromptDtos {

    private PromptDtos() {
    }

    /** 前端展示的一条提示词 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromptVO {
        private String key;
        private String group;
        private LocalizedText name;
        private LocalizedText description;
        private String systemText;
        private String userTemplate;
        private String defaultSystemText;
        private String defaultUserTemplate;
        /** 是否已被自定义（与出厂默认不一致） */
        private boolean customized;
        /** user 模板里的占位符个数（%s），前端据此提示需要保留几个占位符 */
        private int placeholderCount;
        private int sortOrder;
        private Instant updatedAt;
    }

    /** 保存请求 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromptSaveRequest {
        private String systemText;
        private String userTemplate;
    }

    /** 分组信息（前端左侧导航用） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromptGroupVO {
        private String key;
        private LocalizedText name;
        private List<PromptVO> prompts;
    }
}
