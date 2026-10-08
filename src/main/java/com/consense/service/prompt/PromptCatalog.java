package com.consense.service.prompt;

import com.consense.domain.PromptTemplate;
import com.consense.service.drafting.DraftPrompts;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 提示词注册表：定义系统里每条可编辑提示词的元信息（key / 分组 / 名称 / 说明 / 出厂默认文本）。
 *
 * <p>出厂默认文本直接取自 {@link DraftPrompts} 常量——这样代码常量依旧是「默认值」的唯一真相源，
 * 数据库里存的是「用户当前生效值」。用户在前端改过之后，只有「恢复默认」才会拿回常量值。</p>
 */
public final class PromptCatalog {

    private PromptCatalog() {
    }

    /** 一条提示词的定义 */
    public static final class Spec {

        public final String key;
        public final String group;
        public final String nameZhHans;
        public final String nameZhHant;
        public final String nameEn;
        public final String descriptionZhHans;
        public final String descriptionZhHant;
        public final String descriptionEn;
        public final String defaultSystem;
        public final String defaultUserTemplate;
        public final int sortOrder;

        public Spec(String key, String group,
                    String nameZhHans, String nameZhHant, String nameEn,
                    String descriptionZhHans, String descriptionZhHant, String descriptionEn,
                    String defaultSystem, String defaultUserTemplate, int sortOrder) {
            this.key = key;
            this.group = group;
            this.nameZhHans = nameZhHans;
            this.nameZhHant = nameZhHant;
            this.nameEn = nameEn;
            this.descriptionZhHans = descriptionZhHans;
            this.descriptionZhHant = descriptionZhHant;
            this.descriptionEn = descriptionEn;
            this.defaultSystem = defaultSystem;
            this.defaultUserTemplate = defaultUserTemplate;
            this.sortOrder = sortOrder;
        }
    }

    public static final String KEY_DISCOVER = PromptTemplate.KEY_DRAFTING_DISCOVER;
    public static final String KEY_CLAUSE = PromptTemplate.KEY_DRAFTING_CLAUSE;
    public static final String KEY_VETTING = PromptTemplate.KEY_VETTING_RUN;
    public static final String KEY_ADVICE = PromptTemplate.KEY_ADVICE_ASK;

    private static final List<Spec> SPECS = Collections.unmodifiableList(Arrays.asList(
            new Spec(
                    KEY_DISCOVER, PromptTemplate.GROUP_DRAFTING,
                    "变量识别", "變量識別", "Variable Discovery",
                    "从资料中识别六组八项固定输入，附原文依据；未知留空。",
                    "從資料中識別六組八項固定輸入，附原文依據；未知留空。",
                    "Extracts eight fixed inputs in six groups, with evidence; unknown values remain empty.",
                    DraftPrompts.DISCOVER_SYSTEM, DraftPrompts.DISCOVER_USER_TEMPLATE, 10),
            new Spec(
                    KEY_CLAUSE, PromptTemplate.GROUP_DRAFTING,
                    "文稿起草", "文稿起草", "Document Drafting",
                    "基于已确认变量起草 NTT / SCT / SCC 正文。按完整模板分段起草，并应用派生条款规则。",
                    "基於已確認變量起草 NTT / SCT / SCC 正文。",
                    "Drafts the NTT / SCT / SCC documents from confirmed variables.",
                    DraftPrompts.CLAUSE_SYSTEM, DraftPrompts.CLAUSE_USER_TEMPLATE, 20),
            new Spec(
                    KEY_VETTING, PromptTemplate.GROUP_VETTING,
                    "招标文件审查", "招標文件審查", "Tender Document Vetting",
                    "按 9 类 F2 规则逐条核对组装后的招标文件，产出可复核的审查发现。",
                    "按 9 類 F2 規則逐條核對組裝後的招標文件，產出可復核的審查發現。",
                    "Reviews the assembled tender package against the 9 F2 rule categories.",
                    DraftPrompts.VETTING_SYSTEM, DraftPrompts.VETTING_USER_TEMPLATE, 30),
            new Spec(
                    KEY_ADVICE, PromptTemplate.GROUP_ADVICE,
                    "合同条款问答", "合約條款問答", "Contract Clause Q&A",
                    "只依据检索到的合同条款摘录回答 QS 提问，必须给出条款编号依据。",
                    "只依據檢索到的合約條款摘錄回答 QS 提問，必須給出條款編號依據。",
                    "Answers QS questions strictly from the retrieved contract clause excerpts.",
                    DraftPrompts.ADVICE_SYSTEM, DraftPrompts.ADVICE_USER_TEMPLATE, 40)
    ));

    public static List<Spec> specs() {
        return SPECS;
    }

    /** 按 key 查定义；找不到返回 null */
    public static Spec find(String key) {
        if (key == null) {
            return null;
        }
        for (Spec spec : SPECS) {
            if (spec.key.equalsIgnoreCase(key)) {
                return spec;
            }
        }
        return null;
    }
}
