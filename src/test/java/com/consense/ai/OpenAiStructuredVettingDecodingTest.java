package com.consense.ai;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAiStructuredVettingDecodingTest {
    private static final JsonNode SCHEMA = JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
    private static final List<LlmClient.ChatTurn> TURNS = Collections.singletonList(LlmClient.ChatTurn.user("complete source text"));
    private static final String COMPLETE = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}]}";

    private static void response(HttpSupport http, String raw) {
        when(http.postJson(anyString(), anyString(), anyLong(), isNull(), anyInt())).thenReturn(raw);
    }

    @Test void structuredCallsHaveAnExplicitBudgetOneChoiceAndNoTransportRetry() {
        HttpSupport http = mock(HttpSupport.class);
        ConsenseProperties.Llm cfg = new ConsenseProperties.Llm();
        cfg.setBaseUrl("http://protocol.invalid/"); cfg.setChatModel("native-protocol-model");
        cfg.setStructuredMaxTokens(8192); cfg.setMaxRetry(2);
        when(http.postJson(anyString(), anyString(), anyLong(), isNull(), anyInt())).thenAnswer(call -> {
            assertEquals("http://protocol.invalid/v1/chat/completions", call.getArgument(0));
            JsonNode body = JsonUtils.parse(call.getArgument(1));
            assertEquals(8192, body.get("max_tokens").asInt());
            assertEquals(1, body.get("n").asInt()); assertFalse(body.get("stream").asBoolean());
            assertEquals("native-protocol-model", body.get("model").asText());
            assertEquals("complete source text", body.get("messages").get(0).get("content").asText());
            assertFalse(body.has("response_format"), "NInfer has no schema grammar; schema stays in the caller prompt");
            assertFalse(body.has("enable_thinking")); assertFalse(body.has("think"));
            assertFalse(body.has("thinking")); assertFalse(body.has("reasoning_split"));
            assertEquals(0, (int) call.getArgument(4));
            return COMPLETE;
        });
        assertEquals("[]", new OpenAiLlmClient(cfg, http).chatStructured(TURNS, SCHEMA));
        verify(http, times(1)).postJson(anyString(), anyString(), anyLong(), isNull(), eq(0));
    }

    @Test void ordinaryChatKeepsItsExistingRequestAndRetryPolicy() {
        HttpSupport http = mock(HttpSupport.class);
        ConsenseProperties.Llm cfg = new ConsenseProperties.Llm(); cfg.setStructuredMaxTokens(8192); cfg.setMaxRetry(2);
        cfg.setStructuredOpenAiThinkingMode("disabled"); cfg.setStructuredOpenAiReasoningSplit(true);
        when(http.postJson(anyString(), anyString(), anyLong(), isNull(), eq(2))).thenAnswer(call -> {
            JsonNode body = JsonUtils.parse(call.getArgument(1));
            assertFalse(body.has("max_tokens")); assertFalse(body.has("n")); assertFalse(body.has("response_format"));
            assertFalse(body.has("thinking")); assertFalse(body.has("reasoning_split"));
            return COMPLETE;
        });
        assertEquals("[]", new OpenAiLlmClient(cfg, http).chat(TURNS));
        verify(http, times(1)).postJson(anyString(), anyString(), anyLong(), isNull(), eq(2));
    }

    @Test void lengthMissingAndOtherTerminalReasonsDoNotBecomeCompletedEmptyReviews() {
        for (String reason : new String[] {"length", "content_filter", "tool_calls", "", "cancelled"}) {
            HttpSupport http = mock(HttpSupport.class);
            String raw = "{\"choices\":[{\"finish_reason\":\"" + reason + "\",\"message\":{\"content\":\"[]\"}}]}";
            response(http, raw);
            IncompleteModelResponseException error = assertThrows(IncompleteModelResponseException.class,
                    () -> new OpenAiLlmClient(new ConsenseProperties.Llm(), http).chatStructured(TURNS, SCHEMA));
            assertEquals(raw, error.getRawResponse()); assertEquals("[]", error.getModelContent());
            verify(http, times(1)).postJson(anyString(), anyString(), anyLong(), isNull(), eq(0));
        }
    }

    @Test void malformedChoiceContentRefusalAndToolCallsAreIncomplete() {
        for (String raw : new String[] {"{}", "{\"choices\":[]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\" \"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[]}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\",\"refusal\":\"refused\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\",\"tool_calls\":[{}]}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}},{}]}"}) {
            HttpSupport http = mock(HttpSupport.class); response(http, raw);
            assertThrows(IncompleteModelResponseException.class,
                    () -> new OpenAiLlmClient(new ConsenseProperties.Llm(), http).chatStructured(TURNS, SCHEMA));
        }
    }

    @Test void invalidStructuredConfigurationFailsBeforeSendingARequest() {
        HttpSupport http = mock(HttpSupport.class);
        ConsenseProperties.Llm cfg = new ConsenseProperties.Llm();
        OpenAiLlmClient client = new OpenAiLlmClient(cfg, http);
        assertThrows(IllegalArgumentException.class, () -> client.chatStructured(TURNS, null));
        assertThrows(IllegalArgumentException.class, () -> client.chatStructured(TURNS, JsonUtils.parse("[]")));
        for (int cap : new int[] {0, -1}) {
            cfg.setStructuredMaxTokens(cap);
            assertThrows(IllegalArgumentException.class, () -> client.chatStructured(TURNS, SCHEMA));
        }
        verifyNoInteractions(http);
    }

    @Test void gatewayPreservesActualIncompleteTextWithoutRepairRetry() {
        HttpSupport http = mock(HttpSupport.class);
        String raw = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"[]\"}}]}";
        when(http.get(anyString(), anyLong())).thenReturn("{\"data\":[{\"id\":\"local-model\"}]}"); response(http, raw);
        AiGateway gateway = new AiGateway(new OpenAiLlmClient(new ConsenseProperties.Llm(), http));
        List<String> rawOut = new ArrayList<>();
        assertThrows(IncompleteModelResponseException.class,
                () -> gateway.completeStructuredJsonList("review", "whole source", String.class, SCHEMA, rawOut));
        assertEquals(Collections.singletonList("[]"), rawOut);
        verify(http, times(1)).postJson(anyString(), anyString(), anyLong(), isNull(), eq(0));
    }

    @Test void ollamaUsesTheSameConfigurableBudgetAndKeepsIncompleteText() {
        HttpSupport http = mock(HttpSupport.class);
        ConsenseProperties.Llm cfg = new ConsenseProperties.Llm(); cfg.setStructuredMaxTokens(8192); cfg.setMaxRetry(2);
        when(http.get(anyString(), anyLong())).thenReturn("{\"models\":[]}");
        when(http.postJson(anyString(), anyString(), anyLong(), isNull(), eq(0))).thenAnswer(call -> {
            JsonNode body = JsonUtils.parse(call.getArgument(1));
            assertEquals(8192, body.get("options").get("num_predict").asInt());
            return "{\"done_reason\":\"length\",\"message\":{\"content\":\"[]\"}}";
        });
        AiGateway gateway = new AiGateway(new OllamaLlmClient(cfg, http));
        List<String> rawOut = new ArrayList<>();
        assertThrows(IncompleteModelResponseException.class,
                () -> gateway.completeStructuredJsonList("review", "whole source", String.class, SCHEMA, rawOut));
        assertEquals(Collections.singletonList("[]"), rawOut);
        verify(http, times(1)).postJson(anyString(), anyString(), anyLong(), isNull(), eq(0));
    }

    @Test void explicitOpenAiModesAndReasoningSplitUseOnlyTheirExactStructuredProtocolFields() {
        for (String mode : new String[] {"adaptive", "disabled"}) {
            for (Boolean split : new Boolean[] {null, false, true}) {
                HttpSupport http = mock(HttpSupport.class);
                ConsenseProperties.Llm cfg = new ConsenseProperties.Llm();
                cfg.setStructuredMaxTokens(16384); cfg.setStructuredOpenAiThinkingMode(mode);
                cfg.setStructuredOpenAiReasoningSplit(split);
                when(http.postJson(anyString(), anyString(), anyLong(), isNull(), eq(0))).thenAnswer(call -> {
                    JsonNode body = JsonUtils.parse(call.getArgument(1));
                    assertEquals(mode, body.path("thinking").path("type").asText());
                    assertEquals(1, body.path("thinking").size());
                    assertEquals(16384, body.path("max_tokens").asInt());
                    if (split == null) assertFalse(body.has("reasoning_split"));
                    else { assertTrue(body.get("reasoning_split").isBoolean()); assertEquals(split, body.get("reasoning_split").booleanValue()); }
                    assertFalse(body.has("think")); assertFalse(body.has("enable_thinking"));
                    return COMPLETE;
                });
                assertEquals("[]", new OpenAiLlmClient(cfg, http).chatStructured(TURNS, SCHEMA));
                verify(http, times(1)).postJson(anyString(), anyString(), anyLong(), isNull(), eq(0));
            }
        }
    }

    @Test void explicitlyConfiguredReasoningSplitDoesNotInventAThinkingMode() {
        HttpSupport http = mock(HttpSupport.class);
        ConsenseProperties.Llm cfg = new ConsenseProperties.Llm(); cfg.setStructuredOpenAiReasoningSplit(false);
        when(http.postJson(anyString(), anyString(), anyLong(), isNull(), eq(0))).thenAnswer(call -> {
            JsonNode body = JsonUtils.parse(call.getArgument(1));
            assertFalse(body.has("thinking")); assertFalse(body.path("reasoning_split").asBoolean());
            assertTrue(body.get("reasoning_split").isBoolean()); return COMPLETE;
        });
        assertEquals("[]", new OpenAiLlmClient(cfg, http).chatStructured(TURNS, SCHEMA));
    }

    @Test void invalidOpenAiThinkingModesFailBeforeTransportButDoNotAffectOrdinaryChat() {
        HttpSupport http = mock(HttpSupport.class);
        ConsenseProperties.Llm cfg = new ConsenseProperties.Llm();
        for (String value : new String[] {"", " ", "false", "enabled", "ADAPTIVE", " disabled", "disabled "}) {
            cfg.setStructuredOpenAiThinkingMode(value);
            assertThrows(IllegalArgumentException.class, () -> new OpenAiLlmClient(cfg, http).chatStructured(TURNS, SCHEMA));
        }
        verifyNoInteractions(http);
        response(http, COMPLETE);
        assertEquals("[]", new OpenAiLlmClient(cfg, http).chat(TURNS));
    }

    @Test void lengthWithReasoningAndNoContentRetainsNumericUsageAndIsBudgetFailure() {
        HttpSupport http = mock(HttpSupport.class);
        String raw = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"reasoning_content\":\"PRIVATE_NOT_A_FINDING\"}}],"
                + "\"usage\":{\"prompt_tokens\":10686,\"completion_tokens\":16384,\"total_tokens\":27070,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":16383,\"private\":\"NEVER_COPY\"}},"
                + "\"model\":\"UNTRUSTED_NAME\",\"base_resp\":{\"status_code\":0,\"status_msg\":\"UNTRUSTED_MESSAGE\"}}";
        response(http, raw);
        IncompleteModelResponseException error = assertThrows(IncompleteModelResponseException.class,
                () -> new OpenAiLlmClient(new ConsenseProperties.Llm(), http).chatStructured(TURNS, SCHEMA));
        assertEquals(IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED, error.getFailureKind());
        assertNull(error.getModelContent()); assertEquals(raw, error.getRawResponse());
        JsonNode metadata = error.getResponseMetadata();
        assertEquals("length", metadata.path("finishReason").asText()); assertEquals(1, metadata.path("choiceCount").asInt());
        assertEquals(16383L, metadata.path("usage").path("completion_tokens_details").path("reasoning_tokens").asLong());
        assertEquals(16384L, metadata.path("usage").path("completion_tokens").asLong());
        assertFalse(metadata.toString().contains("PRIVATE")); assertFalse(metadata.toString().contains("UNTRUSTED"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata).put("finishReason", "mutated");
        assertEquals("length", error.getResponseMetadata().path("finishReason").asText());
        verify(http, times(1)).postJson(anyString(), anyString(), anyLong(), isNull(), eq(0));
    }

    @Test void http200ProviderErrorsNeverYieldTheApparentlyCompleteContent() {
        for (String provider : new String[] {"\"error\":{\"message\":\"DO_NOT_COPY_SECRET\"}",
                "\"base_resp\":{\"status_code\":1008,\"status_msg\":\"DO_NOT_COPY_SECRET\"}",
                "\"base_resp\":{\"status_code\":\"bad\"}", "\"base_resp\":\"malformed\""}) {
            HttpSupport http = mock(HttpSupport.class);
            String raw = "{" + provider + ",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}]}";
            response(http, raw);
            IncompleteModelResponseException error = assertThrows(IncompleteModelResponseException.class,
                    () -> new OpenAiLlmClient(new ConsenseProperties.Llm(), http).chatStructured(TURNS, SCHEMA));
            assertEquals(IncompleteModelResponseException.FailureKind.PROVIDER_ERROR, error.getFailureKind());
            assertEquals("[]", error.getModelContent());
            assertFalse(error.getResponseMetadata().toString().contains("DO_NOT_COPY"));
            assertFalse(error.getMessage().contains("DO_NOT_COPY"));
            verify(http, times(1)).postJson(anyString(), anyString(), anyLong(), isNull(), eq(0));
        }
    }

    @Test void failureCategoriesDistinguishEnvelopeChoicesFinishTextToolsAndRefusal() {
        String[] raw = {"not JSON", "[]", "{\"choices\":[]}",
                "{\"choices\":[{\"finish_reason\":\"cancelled\",\"message\":{\"content\":\"[]\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}",
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":\"[]\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\",\"tool_calls\":[{}]}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\",\"refusal\":\"reason\"}}]}"};
        IncompleteModelResponseException.FailureKind[] expected = {
                IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE,
                IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE,
                IncompleteModelResponseException.FailureKind.INVALID_CHOICE_COUNT,
                IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH,
                IncompleteModelResponseException.FailureKind.MISSING_TEXT,
                IncompleteModelResponseException.FailureKind.TOOL_CALLS,
                IncompleteModelResponseException.FailureKind.TOOL_CALLS,
                IncompleteModelResponseException.FailureKind.REFUSAL};
        for (int i = 0; i < raw.length; i++) {
            HttpSupport http = mock(HttpSupport.class); response(http, raw[i]);
            IncompleteModelResponseException error = assertThrows(IncompleteModelResponseException.class,
                    () -> new OpenAiLlmClient(new ConsenseProperties.Llm(), http).chatStructured(TURNS, SCHEMA));
            assertEquals(expected[i], error.getFailureKind());
        }
    }

    @Test void metadataDropsFreeTextMalformedUsageAndOversizedCounters() {
        HttpSupport http = mock(HttpSupport.class);
        response(http, "{\"choices\":[{\"finish_reason\":\"SECRET_FINISH\",\"message\":{\"content\":\"[]\"}}],"
                + "\"usage\":{\"prompt_tokens\":9223372036854775808,\"completion_tokens\":-1,\"total_tokens\":\"SECRET_USAGE\","
                + "\"reasoning_tokens\":12,\"extra\":\"PRIVATE\",\"completion_tokens_details\":{\"reasoning_tokens\":1.5}}}");
        IncompleteModelResponseException error = assertThrows(IncompleteModelResponseException.class,
                () -> new OpenAiLlmClient(new ConsenseProperties.Llm(), http).chatStructured(TURNS, SCHEMA));
        assertEquals("unknown", error.getResponseMetadata().path("finishReason").asText());
        JsonNode safeUsage = error.getResponseMetadata().path("usage");
        assertEquals(1, safeUsage.size()); assertEquals(12L, safeUsage.path("reasoning_tokens").longValue());
        assertTrue(safeUsage.path("reasoning_tokens").isIntegralNumber());
        assertFalse(error.getMessage().contains("SECRET")); assertFalse(error.getResponseMetadata().toString().contains("PRIVATE"));
    }

    @Test void legacyExceptionConstructorAndOllamaRemainCompatibleWithOptionalOpenAiFields() {
        IncompleteModelResponseException legacy = new IncompleteModelResponseException("legacy", "raw", JsonUtils.parse("\"text\""));
        assertEquals("raw", legacy.getRawResponse()); assertEquals("text", legacy.getModelContent());
        assertEquals(IncompleteModelResponseException.FailureKind.UNKNOWN, legacy.getFailureKind()); assertTrue(legacy.getResponseMetadata().isEmpty());
        HttpSupport http = mock(HttpSupport.class); ConsenseProperties.Llm cfg = new ConsenseProperties.Llm();
        cfg.setStructuredOpenAiThinkingMode("invalid-openai-only-value"); cfg.setStructuredOpenAiReasoningSplit(true);
        when(http.postJson(anyString(), anyString(), anyLong(), isNull(), eq(0))).thenAnswer(call -> {
            JsonNode body = JsonUtils.parse(call.getArgument(1)); assertFalse(body.has("thinking")); assertFalse(body.has("reasoning_split"));
            return "{\"done_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}";
        });
        assertEquals("[]", new OllamaLlmClient(cfg, http).chatStructured(TURNS, SCHEMA));
        assertEquals(10000, new ConsenseProperties.Vetting().getSemanticContextChars());
        assertEquals(20000, new ConsenseProperties.Vetting().getSemanticContextExpansionChars());
    }
}
