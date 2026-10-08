package com.consense.ai;

import com.consense.common.BizException;
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

class StructuredVettingDecodingTest {
    @Test void ollamaForwardsSchemaAndBoundsOnlyStructuredCalls() {
        HttpSupport http = mock(HttpSupport.class);
        ConsenseProperties.Llm cfg = new ConsenseProperties.Llm(); cfg.setMaxRetry(0);
        assertNull(cfg.getStructuredThinking());
        JsonNode schema = JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        when(http.postJson(anyString(), anyString(), anyLong(), isNull(), anyInt())).thenAnswer(call -> {
            JsonNode body = JsonUtils.parse(call.getArgument(1));
            assertFalse(body.has("think"), "The default must omit think, including an explicit JSON null");
            assertEquals(cfg.getNumCtx(), body.path("options").path("num_ctx").asInt());
            if (body.has("format")) {
                assertEquals(schema, body.get("format"));
                assertEquals(2048, body.path("options").path("num_predict").asInt());
            } else assertFalse(body.path("options").has("num_predict"));
            return "{\"done_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}";
        });
        OllamaLlmClient client = new OllamaLlmClient(cfg, http);
        assertEquals("[]", client.chatStructured(Collections.singletonList(LlmClient.ChatTurn.user("source")), schema));
        assertEquals("[]", client.chat(Collections.singletonList(LlmClient.ChatTurn.user("ordinary"))));
        verify(http, times(2)).postJson(anyString(), anyString(), anyLong(), isNull(), anyInt());
    }

    @Test void explicitStructuredThinkingUsesAJsonBooleanWithoutChangingOrdinaryChat() {
        JsonNode schema = JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        for (boolean thinking : new boolean[] {false, true}) {
            HttpSupport http = mock(HttpSupport.class);
            ConsenseProperties.Llm cfg = new ConsenseProperties.Llm();
            cfg.setBaseUrl("http://protocol.invalid"); cfg.setChatModel("arbitrary-protocol-model");
            cfg.setStructuredThinking(thinking);
            List<JsonNode> requests = new ArrayList<>();
            when(http.postJson(anyString(), anyString(), anyLong(), isNull(), anyInt())).thenAnswer(call -> {
                assertEquals("http://protocol.invalid/api/chat", call.getArgument(0));
                requests.add(JsonUtils.parse(call.getArgument(1)));
                return "{\"done_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}";
            });
            OllamaLlmClient client = new OllamaLlmClient(cfg, http);
            List<LlmClient.ChatTurn> turns = Collections.singletonList(LlmClient.ChatTurn.user("source"));
            assertEquals("[]", client.chatStructured(turns, schema));
            assertEquals("[]", client.chat(turns));
            cfg.setStructuredThinking(null);
            assertEquals("[]", client.chat(turns));
            JsonNode structured = requests.get(0);
            assertTrue(structured.get("think").isBoolean());
            assertEquals(thinking, structured.get("think").booleanValue());
            assertFalse(structured.get("options").has("think"), "think belongs to the native request root");
            assertEquals(schema, structured.get("format"));
            assertEquals("arbitrary-protocol-model", structured.get("model").asText());
            assertFalse(requests.get(1).has("think"));
            assertFalse(requests.get(1).has("format"));
            assertFalse(requests.get(1).get("options").has("num_predict"));
            assertEquals(requests.get(2), requests.get(1), "Ordinary request JSON is unchanged by the structured-only setting");
            verify(http, times(3)).postJson(anyString(), anyString(), anyLong(), isNull(), anyInt());
        }
    }

    @Test void outputBudgetExhaustionCannotMasqueradeAsAnEmptyCompletedReview() {
        HttpSupport http = mock(HttpSupport.class);
        when(http.postJson(anyString(), anyString(), anyLong(), isNull(), anyInt()))
                .thenReturn("{\"done_reason\":\"length\",\"message\":{\"content\":\"[]\"}}");
        OllamaLlmClient client = new OllamaLlmClient(new ConsenseProperties.Llm(), http);
        assertThrows(BizException.class, () -> client.chatStructured(Collections.emptyList(), JsonUtils.parse("{\"type\":\"array\"}")));
    }

    @Test void structuredGatewayPreservesEmptyArrayButRejectsMalformedOutputWithoutRetryOrSilentFallback() {
        LlmClient llm = mock(LlmClient.class); when(llm.available()).thenReturn(true);
        JsonNode schema = JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        when(llm.chatStructured(anyList(), eq(schema))).thenReturn("[]", "[{\"broken\":");
        AiGateway gateway = new AiGateway(llm);
        assertTrue(gateway.completeStructuredJsonList("review", "source", String.class, schema).isEmpty());
        assertThrows(RuntimeException.class, () -> gateway.completeStructuredJsonList("review", "source", String.class, schema));
        verify(llm, times(2)).chatStructured(anyList(), eq(schema));
        verify(llm, never()).chat(anyList());
    }
}
