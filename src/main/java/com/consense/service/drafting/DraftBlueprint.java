package com.consense.service.drafting;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 起草模块的固定蓝图：
 *   - BASE_VARIABLES：基础变量的设计稿清单（key / 标签 / action / options / kind / affects 由设计稿固定），
 *     变量 value / sourceQuote / reason / confidence 由模型从证据中识别。
 *   - TEMPLATE_KEYS：起草覆盖的文稿文件 key。
 *
 * 这是 BASE 变量的唯一真相源；模型不允许新增 / 删除 / 重命名 BASE 变量，只能在其中填值。
 */
public final class DraftBlueprint {

    private DraftBlueprint() {
    }

    /** 起草覆盖的标准文稿 */
    public static final List<String> TEMPLATE_KEYS = Arrays.asList("NTT", "SCT", "SCC");

    /** 起草覆盖的标准文稿（与 TEMPLATE_KEYS 同义，供 generate() 调用） */
    public static List<String> draftFileKeys() {
        return TEMPLATE_KEYS;
    }

    /** 文稿文件标题（供 generate() 在用户 prompt 与文档标题中使用） */
    public static String fileTitle(String fileKey) {
        if (fileKey == null) {
            return "";
        }
        switch (fileKey) {
            case "NTT":
                return "Notes to Tenderers";
            case "SCT":
                return "Special Conditions of Tender";
            case "SCC":
                return "Special Conditions of Contract";
            default:
                return fileKey;
        }
    }

    /** 单条 BASE 变量的设计稿定义 */
    public static final class BaseSpec {

        public final String key;           // 英文稳定 id
        public final String code;          // 设计稿编号，如 B01
        public final String labelZhHans;   // 简体中文标签
        public final String labelZhHant;   // 繁體中文標籤
        public final String labelEn;       // English label
        public final String action;        // fill | choice
        public final List<String> options; // choice 选项；其他为 null
        public final String kind;          // null | "list"（清单型：BQ Bill / 分包）
        public final String affects;        // 影响的文件 key，逗号分隔

        public BaseSpec(String key, String code, String labelZhHans, String labelZhHant,
                        String labelEn, String action, List<String> options, String kind, String affects) {
            this.key = key;
            this.code = code;
            this.labelZhHans = labelZhHans;
            this.labelZhHant = labelZhHant;
            this.labelEn = labelEn;
            this.action = action;
            this.options = options == null ? null : Collections.unmodifiableList(options);
            this.kind = kind;
            this.affects = affects;
        }
    }

    /** 设计稿里的 5 个 BASE 变量（顺序固定，前端展示按此顺序）。
     *  key / 标签 / action / options / kind / affects 由设计稿固定，模型不允许新增 / 删除 / 重命名，
     *  只能为每个 BASE 填写 value（其余字段 sourceQuote/reason/confidence 由识别产生）。 */
    public static final List<BaseSpec> BASE_VARIABLES = Collections.unmodifiableList(Arrays.asList(
            new BaseSpec(
                    "contractTitle", "B01",
                    "合约编号与名称", "合約編號與名稱", "Contract No. and Title",
                    "fill", null, null, "NTT,SCT,SCC"),
            new BaseSpec(
                    "worksType", "B02",
                    "合约工程类型", "合約工程類型", "Type of Works",
                    "fill", null, null, "SCC"),
            new BaseSpec(
                    "fundingArrangement", "B03",
                    "Tender A / B 与资金安排", "Tender A / B 與資金安排", "Tender A / B Funding Arrangement",
                    "choice", Arrays.asList("Tender A", "Tender B"), null, "SCC"),
            new BaseSpec(
                    "billNos", "BQ",
                    "BQ Bill 编号与名称", "BQ Bill 編號與名稱", "BQ Bill Items",
                    "fill", null, "list", "NTT,SCC"),
            new BaseSpec(
                    "subcontractors", "SUB",
                    "专业分包清单", "專業分包清單", "Nominated Sub-contractors",
                    "fill", null, "list", "SCC")
    ));

    private static final Map<String, BaseSpec> BASE_BY_KEY;

    static {
        Map<String, BaseSpec> map = new LinkedHashMap<>();
        for (BaseSpec spec : BASE_VARIABLES) {
            map.put(spec.key, spec);
        }
        BASE_BY_KEY = Collections.unmodifiableMap(map);
    }

    /** 按 key 查找 BASE 设计稿定义；找不到返回 null（视为模型自行识别的 FILE 变量） */
    public static BaseSpec findBase(String key) {
        if (key == null) {
            return null;
        }
        // 大小写不敏感：BASE_BY_KEY 用原始大小写做 key，HashMap 查找时也按原大小写匹配；
        // 兼容模型的 key 大小写可能与设计稿不一致（如 contracttitle / CONTRACT_TITLE）。
        for (BaseSpec spec : BASE_VARIABLES) {
            if (spec.key.equalsIgnoreCase(key)) {
                return spec;
            }
        }
        return null;
    }

    /** 是否是设计稿里的 BASE key */
    public static boolean isBaseKey(String key) {
        return findBase(key) != null;
    }

    /** BASE 变量 key 集合（用于提示词中明确告知模型） */
    public static String baseKeysAsString() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < BASE_VARIABLES.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            BaseSpec spec = BASE_VARIABLES.get(i);
            builder.append(spec.key).append("（").append(spec.code).append(' ').append(spec.labelZhHans).append('）');
        }
        return builder.toString();
    }
}