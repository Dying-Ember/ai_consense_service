package com.consense.web.dto;

import com.consense.common.LocalizedText;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

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

    /** Current uploaded source identity and native main-document paragraph order. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TemplateReadingVO {
        private String fileKey;
        private String fileName;
        private String sourceHash;
        private String format;
        private boolean catalogueSourceVerified;
        private List<TemplateParagraphVO> paragraphs;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TemplateParagraphVO {
        private String id;
        private int ordinal;
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
        private boolean manuallyEdited;
        private boolean reviewRequired;
        private List<CandidateVO> candidates;
        private String adoptionState;
        private String validationIssue;
        public VariableVO(String key, String scope, String fileKey, LocalizedText label, String action,
                          String value, List<LocalizedText> options, boolean confirmed, String confirmedFrom,
                          String source, String result, String kind, List<String> cols, String linkedBase,
                          List<String> derivedFrom, List<String> affects, String note) {
            this.key=key; this.scope=scope; this.fileKey=fileKey; this.label=label; this.action=action;
            this.value=value; this.options=options; this.confirmed=confirmed; this.confirmedFrom=confirmedFrom;
            this.source=source; this.result=result; this.kind=kind; this.cols=cols; this.linkedBase=linkedBase;
            this.derivedFrom=derivedFrom; this.affects=affects; this.note=note;
        }
        public String getGroup() {
            com.consense.service.drafting.DraftBlueprint.InputSpec spec = com.consense.service.drafting.DraftBlueprint.find(key);
            return spec == null ? null : spec.group;
        }
    }

    /** 变量局部更新 */
    @Data
    @NoArgsConstructor
    public static class VariablePatch {
        private String value;
        private String choice;
        private Boolean confirmed;
        private String note;
        private String result;
        private Integer candidateIndex;
        private CandidateVO candidateSnapshot;
        private SuggestionSnapshot suggestionSnapshot;
        private Boolean reviewed;
        public VariablePatch(String value, String choice, Boolean confirmed, String note, String result) {
            this.value=value; this.choice=choice; this.confirmed=confirmed; this.note=note; this.result=result;
        }
    }

    /** Read-before-adopt identity supplied by the current view; never a new project value. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestionSnapshot {
        private String value;
        private String source;
        private List<CandidateVO> candidates;
        private Boolean reviewRequired;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandidateVO {
        private String value;
        private Long sourceDocumentId;
        private String fileName;
        private String sourceHash;
        private String sourceQuote;
        private String reason;
        private Double confidence;
    }

    /** A plan preview does not mutate saved project inputs. */
    @Data
    @NoArgsConstructor
    public static class PlanPreview {
        private Map<String,String> values;
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
        private String revisionId;
        private String docxSha256;
        private List<BlockPatch> blocks;
        public DocumentPatch(String content) { this.content=content; }
    }

    @Data
    @NoArgsConstructor
    public static class BlockPatch {
        private String id;
        private String bindingId;
        private String expectedTextHash;
        private String text;
        private List<String> insertAfter;
    }

    /** Revision-bound template targets; evidence anchors remain separate source references. */
    @Data @NoArgsConstructor
    public static class DocumentBindingsVO {
        private String fileKey;
        private String view;
        private String sourceSha256;
        private String revisionId;
        private String docxSha256;
        private String pdfSha256;
        private String renderProfileHash;
        private String geometryStatus;
        private List<Map<String,Object>> bindings;
        private Map<String,Object> nativeLayout;
    }

    /** 最近一次变量识别的过程留痕（提示词 + 模型原始返回），供前端展示模型思路 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractTraceVO {
        private com.consense.ai.ModelIdentity modelIdentity;
        private String model;
        private String finishedAt;
        private String systemPrompt;
        private String userPrompt;
        private List<String> rawResponses;
        private String runId;
        private String harnessVersion;
        private String evidenceRevision;
        private String status;
        private String startedAt;
        private String failureCode;
        private String failureMessage;
        private boolean stale;
        private List<ExtractionPartVO> parts = new java.util.ArrayList<>();
        private List<ExtractionDecisionVO> decisions = new java.util.ArrayList<>();
        private List<ExtractionFieldVO> fields = new java.util.ArrayList<>();
        private List<ExtractionRelationVO> relations = new java.util.ArrayList<>();

        public ExtractTraceVO(String model,String finishedAt,String systemPrompt,String userPrompt,List<String> rawResponses) {
            this.model=model;this.finishedAt=finishedAt;this.systemPrompt=systemPrompt;this.userPrompt=userPrompt;this.rawResponses=rawResponses;
        }
    }

    /** Proposed source relations never change candidates or the value adopted for this draft. */
    @Data @NoArgsConstructor
    public static class ExtractionRelationVO {
        private String key;
        private String relation;
        private String status;
        private List<String> codes = new java.util.ArrayList<>();
        private List<Integer> decisionRefs;
        private List<JointEvidenceVO> evidence;
        private String systemPrompt;
        private String userPrompt;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        private String rawResponse;
        private Object rawProposal;
        private String scopeQuote;
        private Integer fromDecisionRef;
        private Integer toDecisionRef;
        private String reason;
        private Double confidence;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class JointEvidenceVO {
        private int decisionRef;
        private String partId;
        private Long sourceDocumentId;
        private String fileName;
        private String sourceHash;
        private String value;
        private String sourceQuote;
        private ExtractionContextVO context;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExtractionPartVO {
        private String partId;
        private Long sourceDocumentId;
        private String fileName;
        private String sourceHash;
        private int partIndex;
        private String sourceText;
        private List<ExtractionAttemptVO> attempts;
        private ExtractionContextVO context;
        public ExtractionPartVO(String partId,Long sourceDocumentId,String fileName,String sourceHash,int partIndex,String sourceText,List<ExtractionAttemptVO> attempts) {
            this.partId=partId;this.sourceDocumentId=sourceDocumentId;this.fileName=fileName;this.sourceHash=sourceHash;
            this.partIndex=partIndex;this.sourceText=sourceText;this.attempts=attempts;
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExtractionAttemptVO {
        private int attemptIndex;
        private String kind;
        private String systemPrompt;
        private String userPrompt;
        private String rawResponse;
        private String status;
        private String errorCode;
        private ExtractionContextVO context;
        public ExtractionAttemptVO(int attemptIndex,String kind,String systemPrompt,String userPrompt,String rawResponse,String status,String errorCode) {
            this.attemptIndex=attemptIndex;this.kind=kind;this.systemPrompt=systemPrompt;this.userPrompt=userPrompt;
            this.rawResponse=rawResponse;this.status=status;this.errorCode=errorCode;
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExtractionContextVO {
        private String trigger;
        private List<String> keys;
        private int sourceStart;
        private int sourceEnd;
        private String sourceText;
        private String stopReason;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExtractionDecisionVO {
        private String partId;
        private int attemptIndex;
        private int itemIndex;
        private String key;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        private Object rawValue;
        private String normalizedValue;
        private String sourceQuote;
        private String reason;
        private Double confidence;
        private String status;
        private List<String> codes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExtractionFieldVO {
        private String key;
        private String status;
        private int candidateCount;
        private int rejectionCount;
        private int unansweredCount;
        private List<Integer> decisionRefs;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExtractRunSummaryVO {
        private String runId;
        private String harnessVersion;
        private String model;
        private String status;
        private String startedAt;
        private String finishedAt;
        private String evidenceRevision;
        private String failureCode;
        private String failureMessage;
        private boolean stale;
        private com.consense.ai.ModelIdentity modelIdentity;
        public ExtractRunSummaryVO(String runId,String harnessVersion,String model,String status,String startedAt,String finishedAt,String evidenceRevision,String failureCode,String failureMessage,boolean stale){
            this.runId=runId;this.harnessVersion=harnessVersion;this.model=model;this.status=status;this.startedAt=startedAt;this.finishedAt=finishedAt;this.evidenceRevision=evidenceRevision;this.failureCode=failureCode;this.failureMessage=failureMessage;this.stale=stale;
        }
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
        public int getInputTotal() { return baseTotal; }
        public int getInputConfirmed() { return baseConfirmed; }
        public boolean isInputsReady() { return allReady; }
    }

    /** 生成的文稿 */
    @Data
    @NoArgsConstructor
    public static class DraftDocumentVO {
        private String fileKey;
        private String title;
        private String content;
        private boolean generated;
        private String snapshotId;
        private String ruleVersion;
        private boolean stale;
        private boolean contentEdited;
        private String revisionId;
        private String docxSha256;
        private String sourceSha256;
        private List<Map<String,Object>> blocks;
        private String fieldStatus;
        private List<Map<String,Object>> fieldImpacts;
        private String pdfSha256;
        private String renderProfileHash;
        private List<Map<String,Object>> unresolved;
        private Map<String,Object> nativeLayout;
        public DraftDocumentVO(String fileKey, String title, String content, boolean generated) {
            this.fileKey=fileKey; this.title=title; this.content=content; this.generated=generated;
        }
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
