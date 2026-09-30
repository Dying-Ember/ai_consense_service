package com.consense.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("JSON 序列化失败: " + e.getMessage());
        }
    }

    public static <T> T read(String json, Class<T> type) {
        if (isBlankText(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new BizException("JSON 解析失败: " + e.getMessage());
        }
    }

    public static <T> List<T> readList(String json, Class<T> type) {
        if (isBlankText(json)) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception e) {
            throw new BizException("JSON 列表解析失败: " + e.getMessage());
        }
    }

    public static Map<String, Object> readMap(String json) {
        if (isBlankText(json)) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BizException("JSON 对象解析失败: " + e.getMessage());
        }
    }

    public static JsonNode parse(String json) {
        if (isBlankText(json)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BizException("JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 大模型经常返回 ```json ... ``` 包裹的内容，这里做容错剥离。
     */
    /** Java 8 没有 String#isBlank，这里统一空串判断 */
    public static boolean isBlankText(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
        }
        return text.trim();
    }

    /**
     * 从混合文本中截取第一个完整的 JSON 对象或数组。
     */
    public static String extractJson(String raw) {
        String text = stripCodeFence(raw);
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');
        int start;
        char open;
        char close;
        if (objStart < 0 && arrStart < 0) {
            return text;
        }
        if (objStart >= 0 && (arrStart < 0 || objStart < arrStart)) {
            start = objStart;
            open = '{';
            close = '}';
        } else {
            start = arrStart;
            open = '[';
            close = ']';
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return text.substring(start);
    }
}
