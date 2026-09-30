package com.consense.web.dto;

import com.consense.common.LocalizedText;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public final class DraftingDtos {

    private DraftingDtos() {
    }

    /** 标准模板条目（第 1 步上半区） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateVO {
        private String key;
        private String id;
        private LocalizedText label;
        private String fileName;
        private LocalizedText note;
        private LocalizedText status;
        private String tag;
    }

    /** 第 3 步「标准模板在线编辑」：模板正文（解析后的纯文本 / Markdown） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateTextVO {
        private String text;
    }

    /** 项目沟通证据条目（第 1 步下半区） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceVO {
        private Long id;
        private String code;
        private LocalizedText type;
        private String status;
        private String tag;
        private LocalizedText title;
        private LocalizedText body;
        private String source;
        private String fileName;
        private String fileKey;
        private Integer pageCount;
        private Boolean ocrUsed;
        private LocalizedText message;
    }

    /** 起草变量 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariableVO {
        private String key;
        private String scope;
        private String fileKey;
        private LocalizedText label;
        private String action;
        private String value;
        private List<LocalizedText> options;
        private boolean confirmed;
        private String confirmedFrom;
        private String source;
        private String result;
        private String kind;
        private List<String> cols;
        private String linkedBase;
        private List<String> derivedFrom;
        private List<String> affects;
        private String note;
    }

    /** 变量局部更新 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariablePatch {
        private String value;
        private String choice;
        private Boolean confirmed;
        private String note;
        private String result;
    }

    /** 手工新增 FILE 变量（仅用于 QS 补录：模型识别没覆盖到的 Guidance Note / 编辑目标） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariableCreate {
        private String key;
        private String fileKey;
        private String labelZhHans;
        private String labelZhHant;
        private String labelEn;
        private String action;
        private List<String> options;
        private String value;
        private String affects;
        private String sourceQuote;
        private String reason;
        private Double confidence;
    }

    /** 文稿局部更新（QS 在审阅界面直接修改生成稿正文） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentPatch {
        private String content;
    }

    /** 最近一次变量识别的过程留痕（提示词 + 模型原始返回），供前端展示模型思路 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractTraceVO {
        private String model;
        private String finishedAt;
        private String systemPrompt;
        private String userPrompt;
        private List<String> rawResponses;
    }

    /** 向导进度 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressVO {
        private int evidenceCount;
        private int templateCount;
        private int baseTotal;
        private int baseConfirmed;
        private int fileTotal;
        private int fileConfirmed;
        private boolean baseReady;
        private boolean allReady;
        private List<String> generatedFiles;
    }

    /** 生成的文稿 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DraftDocumentVO {
        private String fileKey;
        private String title;
        private String content;
        private boolean generated;
    }

    /** 文件上传结果 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadResultVO {
        private int accepted;
        private int parsed;
        private int failed;
        private List<String> messages;
    }

    /** 第 3 步 OCR 增强：单页 OCR 结果 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OcrPageVO {
        /** 全文（拼接所有 line.text） */
        private String text;
        /** 平均置信度 */
        private double confidence;
        /** 每行识别结果（含图像坐标 bbox：[x, y, w, h]，与上传 PNG 同尺寸） */
        private List<OcrLineVO> lines;
        /** 上传图像字节数（前端日志/调试用） */
        private int imageBytes;
    }

    /** 第 3 步 OCR 增强：单行 OCR 结果 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OcrLineVO {
        private String text;
        private double confidence;
        /** 图像坐标系四点最小外接矩形 [x, y, w, h]；OCR 未返回坐标时为 null */
        private double[] bbox;
    }
}
