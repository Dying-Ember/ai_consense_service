package com.consense.ai;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Carries the observed provider response/text so an incomplete topic retains diagnostics. */
public final class IncompleteModelResponseException extends BizException {
    public enum FailureKind {
        OUTPUT_BUDGET_EXHAUSTED, INCOMPLETE_FINISH, INVALID_CHOICE_COUNT, MISSING_TEXT,
        TOOL_CALLS, REFUSAL, PROVIDER_ERROR, INVALID_RESPONSE_ENVELOPE, UNKNOWN
    }

    private final String rawResponse;
    private final String modelContent;
    private final FailureKind failureKind;
    private final JsonNode responseMetadata;

    /** Compatibility constructor for existing callers, including Ollama. */
    public IncompleteModelResponseException(String message, String rawResponse, JsonNode content) {
        this(message, rawResponse, content, FailureKind.UNKNOWN, null);
    }

    /** The full parsed response is reduced to numeric/enum metadata; reasoning text is never read. */
    public IncompleteModelResponseException(String message, String rawResponse, JsonNode content,
                                            FailureKind failureKind, JsonNode providerResponse) {
        super(message);
        this.rawResponse = rawResponse;
        this.modelContent = content != null && content.isTextual() ? content.textValue() : null;
        this.failureKind = failureKind == null ? FailureKind.UNKNOWN : failureKind;
        this.responseMetadata = safeMetadata(providerResponse);
    }

    public String getRawResponse() { return rawResponse; }
    public String getModelContent() { return modelContent; }
    public FailureKind getFailureKind() { return failureKind; }
    public JsonNode getResponseMetadata() { return responseMetadata.deepCopy(); }

    static String safeFinishReason(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) return "missing";
        switch (value.textValue()) {
            case "stop": case "length": case "content_filter": case "tool_calls":
            case "function_call": case "cancelled": return value.textValue();
            default: return "unknown";
        }
    }

    private static JsonNode safeMetadata(JsonNode root) {
        ObjectNode safe = JsonUtils.mapper().createObjectNode();
        if (root == null || !root.isObject()) return safe;
        JsonNode choices = root.path("choices");
        if (choices.isArray()) {
            safe.put("choiceCount", choices.size());
            if (choices.size() == 1 && choices.get(0).isObject()) {
                safe.put("finishReason", safeFinishReason(choices.get(0).path("finish_reason")));
            }
        }
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            ObjectNode tokens = JsonUtils.mapper().createObjectNode();
            for (String key : new String[] {"prompt_tokens", "completion_tokens", "total_tokens", "reasoning_tokens"}) {
                copyNonnegativeLong(usage, tokens, key);
            }
            ObjectNode completion = JsonUtils.mapper().createObjectNode();
            copyNonnegativeLong(usage.path("completion_tokens_details"), completion, "reasoning_tokens");
            if (!completion.isEmpty()) tokens.set("completion_tokens_details", completion);
            if (!tokens.isEmpty()) safe.set("usage", tokens);
        }
        JsonNode code = root.path("base_resp").path("status_code");
        if (code.isIntegralNumber() && code.canConvertToLong()) safe.put("providerStatusCode", code.longValue());
        return safe;
    }

    private static void copyNonnegativeLong(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.path(key);
        if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0) {
            target.put(key, value.longValue());
        }
    }
}
