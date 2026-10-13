package com.consense.ai;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic property sources and mock transport only; never read the real API key or start Spring. */
class TeammateDefaultConfigurationTest {
    private ConsenseProperties bind(boolean relay, Map<String,Object> variables) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("synthetic-env", variables));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        if (relay) for (PropertySource<?> source : loader.load("relay", new ClassPathResource("application-minimax-relay.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        for (PropertySource<?> source : loader.load("base", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("consense", Bindable.of(ConsenseProperties.class)).get();
    }

    @Test void upstreamDefaultsBindAndMiniMaxChatAndHealthAppendOneVersionSegment() throws IOException {
        ConsenseProperties properties = bind(false, Collections.emptyMap());
        assertEquals("http*://*:5173", properties.getCors().getAllowedOrigins());
        assertEquals("http://localhost:11434", properties.getLlm().getBaseUrl());
        assertEquals("https://api.minimax.cn", properties.getMinimaxCn().getBaseUrl());
        assertEquals("http://localhost:9999", properties.getOcr().getBaseUrl());
        assertEquals("http://localhost:6333", properties.getVector().getBaseUrl());
        ConsenseProperties.Llm config = properties.getMinimaxCn();
        config.setApiKey("SYNTHETIC_ONLY_KEY");
        HttpSupport http = mock(HttpSupport.class);
        when(http.get(anyString(), anyLong(), anyString())).thenReturn("{\"data\":[]}");
        when(http.postJsonWithOverloadBackoff(anyString(), anyString(), anyLong(), anyString())).thenReturn(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}]}");
        MiniMaxChatClient client = new MiniMaxChatClient(config, http);
        assertTrue(client.available());
        assertEquals("[]", client.chat(Collections.singletonList(LlmClient.ChatTurn.user("synthetic request"))));
        verify(http).get("https://api.minimax.cn/v1/models", 4000, "Bearer SYNTHETIC_ONLY_KEY");
        verify(http).postJsonWithOverloadBackoff(eq("https://api.minimax.cn/v1/chat/completions"), anyString(), anyLong(), eq("Bearer SYNTHETIC_ONLY_KEY"));
    }

    @Test void everyExplicitEnvironmentOverrideStillPrecedesTheNewBaseDefaults() throws IOException {
        Map<String,Object> variables = new LinkedHashMap<>();
        variables.put("CONSENSE_ALLOWED_ORIGINS", "http://127.0.0.1:5173");
        variables.put("CONSENSE_LLM_BASE_URL", "http://127.0.0.1:18001");
        variables.put("CONSENSE_MINIMAX_BASE_URL", "http://127.0.0.1:18002");
        variables.put("CONSENSE_OCR_BASE_URL", "http://127.0.0.1:18003");
        variables.put("CONSENSE_VECTOR_BASE_URL", "http://127.0.0.1:18004");
        ConsenseProperties properties = bind(false, variables);
        assertEquals("http://127.0.0.1:5173", properties.getCors().getAllowedOrigins());
        assertEquals("http://127.0.0.1:18001", properties.getLlm().getBaseUrl());
        assertEquals("http://127.0.0.1:18002", properties.getMinimaxCn().getBaseUrl());
        assertEquals("http://127.0.0.1:18003", properties.getOcr().getBaseUrl());
        assertEquals("http://127.0.0.1:18004", properties.getVector().getBaseUrl());
    }

    @Test void dedicatedRelayProfileAndTokenKeepTheirPrecedenceAndSingleSendPolicy() throws IOException {
        Map<String,Object> variables = new LinkedHashMap<>();
        variables.put("CONSENSE_MINIMAX_BASE_URL", "http://127.0.0.1:8092");
        variables.put("CONSENSE_MINIMAX_RELAY_API_KEY", "SYNTHETIC_RELAY_TOKEN");
        variables.put("CONSENSE_MINIMAX_API_KEY", "SYNTHETIC_DIRECT_TOKEN");
        ConsenseProperties.Llm config = bind(true, variables).getMinimaxCn();
        assertEquals("http://127.0.0.1:8092", config.getBaseUrl());
        assertEquals("SYNTHETIC_RELAY_TOKEN", config.getApiKey());
        assertEquals(0, config.getMaxRetry());
        assertFalse(config.isOverloadRetryEnabled());
        assertFalse(JsonUtils.write(config).contains("SYNTHETIC_RELAY_TOKEN"));
    }
}
