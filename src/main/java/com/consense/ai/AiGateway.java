package com.consense.ai;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务层统一入口：负责提示词拼装、JSON 结构化输出与解析容错、可用性缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGateway {

    private static final String JSON_INSTRUCTION =
            "\n\n【输出要求】只输出一个合法的 JSON，不要 Markdown 代码块，不要任何解释文字。";

    private final LlmClient llmClient;

    private final AtomicLong lastProbeAt = new AtomicLong(0);
    private volatile boolean lastProbeResult = false;

    /** 30 秒内复用可用性探测结果，避免每次请求都打一次 /api/tags */
    public boolean available() {
        long now = System.currentTimeMillis();
        if (now - lastProbeAt.get() > 30_000) {
            lastProbeResult = llmClient.available();
            lastProbeAt.set(now);
        }
        return lastProbeResult;
    }

    public void requireAvailable() {
        if (!available()) {
            throw new BizException(5001,
                    "本地大模型服务不可用（模型 " + llmClient.chatModel()
                            + "）。请确认 Ollama 已启动且模型已 pull，或在 application.yml 中调整 consense.llm。");
        }
    }

    public String complete(String systemPrompt, String userPrompt) {
        requireAvailable();
        logPrompt("发送给模型的完整提示词", systemPrompt, userPrompt);
        String raw = llmClient.chat(Arrays.asList(
                LlmClient.ChatTurn.system(systemPrompt),
                LlmClient.ChatTurn.user(userPrompt)));
        logResponse(raw);
        return raw;
    }

    private void logResponse(String raw) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("\n================ 模型原始返回 ({} 字符) ================\n{}\n"
                        + "================ 模型返回结束 ================",
                raw == null ? 0 : raw.length(), raw);
    }

    private void logPrompt(String tag, String systemPrompt, String userPrompt) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("\n================ {} ================\n"
                        + "---- [system prompt] ({} 字符) ----\n{}\n"
                        + "---- [user prompt] ({} 字符) ----\n{}\n"
                        + "================ 结束 ================",
                tag,
                systemPrompt == null ? 0 : systemPrompt.length(), systemPrompt,
                userPrompt == null ? 0 : userPrompt.length(), userPrompt);
    }

    public String chat(List<LlmClient.ChatTurn> turns) {
        requireAvailable();
        return llmClient.chat(turns);
    }

    /**
     * 强制返回单个 JSON 对象；首次解析失败会自动重试一次。
     */
    public <T> T completeJson(String systemPrompt, String userPrompt, Class<T> type) {
        String raw = complete(systemPrompt + JSON_INSTRUCTION, userPrompt);
        try {
            return JsonUtils.read(JsonUtils.extractJson(raw), type);
        } catch (Exception first) {
            log.warn("首次 JSON 解析失败，触发重试: {}", first.getMessage());
            String retry = complete(systemPrompt + JSON_INSTRUCTION,
                    userPrompt + "\n\n重要：上一次输出不是合法 JSON。请严格只输出 JSON。");
            try {
                return JsonUtils.read(JsonUtils.extractJson(retry), type);
            } catch (Exception second) {
                throw new BizException(5002, "模型返回内容无法解析为 JSON：" + abbreviate(retry));
            }
        }
    }

    /**
     * 强制返回 JSON 数组；模型有时会给出 {"items":[...]} 或 [{"items":[...]}]，这里做兼容拍平。
     */
    public <T> List<T> completeJsonList(String systemPrompt, String userPrompt, Class<T> elementType) {
        return completeJsonList(systemPrompt, userPrompt, elementType, null);
    }

    /**
     * 同上，但把每次模型原始返回（含解析失败后的重试）追加到 rawOut，供业务层展示识别过程。
     */
    public <T> List<T> completeJsonList(String systemPrompt, String userPrompt, Class<T> elementType,
                                        List<String> rawOut) {
        String raw = complete(systemPrompt + JSON_INSTRUCTION
                        + " 顶层必须直接输出一个 JSON 数组 [ ... ]：数组元素就是每个对象本身，"
                        + "不要用 {\"items\": ...} 包装，不要输出嵌套数组。",
                userPrompt);
        if (rawOut != null) {
            rawOut.add(raw);
        }
        try {
            return parseList(raw, elementType);
        } catch (Exception first) {
            log.warn("首次 JSON 数组解析失败，触发重试: {}", first.getMessage());
            String retry = complete(systemPrompt + JSON_INSTRUCTION
                            + " 顶层必须直接输出一个扁平的 JSON 数组 [ ... ]，元素不要嵌套。",
                    userPrompt + "\n\n重要：上一次输出不是合法 JSON 数组。");
            if (rawOut != null) {
                rawOut.add(retry);
            }
            try {
                return parseList(retry, elementType);
            } catch (Exception second) {
                log.error("模型 JSON 数组解析失败，完整原始返回如下（{} 字符）：\n{}", retry.length(), retry);
                throw new BizException(5002, "模型返回内容无法解析为 JSON 数组：" + abbreviate(retry));
            }
        }
    }

    public List<float[]> embed(List<String> texts) {
        requireAvailable();
        return llmClient.embed(texts);
    }

    public String chatModel() {
        return llmClient.chatModel();
    }

    public String embedModel() {
        return llmClient.embedModel();
    }

    private <T> List<T> parseList(String raw, Class<T> elementType) {
        String json = JsonUtils.extractJson(raw);
        JsonNode node = unwrapList(JsonUtils.parse(json));
        return JsonUtils.readList(node.toString(), elementType);
    }

    private static final String[] LIST_FIELDS = {"items", "findings", "variables", "results", "data", "list"};

    /**
     * 拍平模型常见的集合包装：
     *  a) 顶层 {"items":[...]} → 直接取数组；
     *  b) 顶层 [{"items":[...]},{"items":[...]}] → 把各元素内嵌的数组合并为一个扁平数组。
     */
    private JsonNode unwrapList(JsonNode node) {
        if (node == null) {
            return node;
        }
        if (node.isObject()) {
            for (String field : LIST_FIELDS) {
                if (node.has(field) && node.get(field).isArray()) {
                    return unwrapList(node.get(field));
                }
            }
            return node;
        }
        if (node.isArray()) {
            boolean hasWrapped = false;
            for (JsonNode element : node) {
                if (element.isObject()) {
                    for (String field : LIST_FIELDS) {
                        if (element.has(field) && element.get(field).isArray()) {
                            hasWrapped = true;
                            break;
                        }
                    }
                }
                if (hasWrapped) {
                    break;
                }
            }
            if (hasWrapped) {
                ArrayNode flattened = JsonNodeFactory.instance.arrayNode();
                for (JsonNode element : node) {
                    boolean inlined = false;
                    if (element.isObject()) {
                        for (String field : LIST_FIELDS) {
                            if (element.has(field) && element.get(field).isArray()) {
                                element.get(field).forEach(flattened::add);
                                inlined = true;
                                break;
                            }
                        }
                    }
                    if (!inlined) {
                        flattened.add(element);
                    }
                }
                return flattened;
            }
        }
        return node;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > 200 ? compact.substring(0, 200) + "..." : compact;
    }
}
