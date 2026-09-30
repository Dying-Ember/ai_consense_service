package com.consense.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 三语文案。与设计稿 copy(en, zhHans, zhHant) 等价，
 * 前端按当前语言（zh-Hans / zh-Hant / en）取值，切换语言无需重新请求。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LocalizedText {

    private final String zhHans;
    private final String zhHant;
    private final String en;

    public LocalizedText() {
        this("", "", "");
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public LocalizedText(@JsonProperty("zhHans") String zhHans,
                         @JsonProperty("zhHant") String zhHant,
                         @JsonProperty("en") String en) {
        this.zhHans = nvl(zhHans);
        this.zhHant = nvl(zhHant).isEmpty() ? this.zhHans : nvl(zhHant);
        this.en = nvl(en);
    }

    public static LocalizedText of(String zhHans, String zhHant, String en) {
        return new LocalizedText(zhHans, zhHant, en);
    }

    /** 三语相同（如合约编号等专有值） */
    public static LocalizedText same(String value) {
        String text = nvl(value);
        return new LocalizedText(text, text, text);
    }

    public static LocalizedText empty() {
        return new LocalizedText("", "", "");
    }

    public String pick(String lang) {
        if ("en".equalsIgnoreCase(lang)) {
            return nvl(en).isEmpty() ? zhHans : en;
        }
        if ("zh-Hant".equalsIgnoreCase(lang)) {
            return nvl(zhHant).isEmpty() ? zhHans : zhHant;
        }
        return zhHans;
    }

    @JsonIgnore
    public boolean isBlank() {
        return nvl(zhHans).trim().isEmpty() && nvl(zhHant).trim().isEmpty() && nvl(en).trim().isEmpty();
    }

    public String getZhHans() {
        return zhHans;
    }

    public String getZhHant() {
        return zhHant;
    }

    public String getEn() {
        return en;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
