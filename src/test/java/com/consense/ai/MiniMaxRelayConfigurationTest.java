package com.consense.ai;

import com.consense.config.ConsenseProperties;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Configuration binding only, with isolated synthetic environment values and no application/network startup. */
class MiniMaxRelayConfigurationTest {
    private ConsenseProperties.Llm bind(boolean relay, Map<String,Object> variables) throws IOException {
        MockEnvironment env=new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("synthetic-env",variables));
        YamlPropertySourceLoader loader=new YamlPropertySourceLoader();
        if(relay)for(PropertySource<?> source:loader.load("relay",new ClassPathResource("application-minimax-relay.yml")))env.getPropertySources().addLast(source);
        for(PropertySource<?> source:loader.load("base",new ClassPathResource("application.yml")))env.getPropertySources().addLast(source);
        return Binder.get(env).bind("consense",Bindable.of(ConsenseProperties.class)).get().getMinimaxCn();
    }
    private Map<String,Object> variables() {
        Map<String,Object> vars=new LinkedHashMap<>();
        vars.put("CONSENSE_MINIMAX_BASE_URL","http://127.0.0.1:8092");
        vars.put("MiniMaxCN_Token_Plan_API_Key","SYNTHETIC_OFFICIAL_KEY");
        return vars;
    }
    @Test void relayProfileBindsTheDedicatedTokenRootBudgetAndSingleSendPolicy() throws IOException {
        Map<String,Object> vars=variables();vars.put("CONSENSE_MINIMAX_RELAY_API_KEY","SYNTHETIC_RELAY_TOKEN");
        vars.put("CONSENSE_MINIMAX_API_KEY","SYNTHETIC_GENERIC_KEY");
        // A default direct-provider env value must not re-enable retries in the relay profile.
        vars.put("CONSENSE_MINIMAX_OVERLOAD_RETRY_ENABLED","true");
        ConsenseProperties.Llm cfg=bind(true,vars);
        assertEquals("SYNTHETIC_RELAY_TOKEN",cfg.getApiKey());assertEquals("http://127.0.0.1:8092",cfg.getBaseUrl());
        assertEquals("MiniMax-M3",cfg.getChatModel());assertFalse(cfg.isOverloadRetryEnabled());assertEquals(0,cfg.getMaxRetry());
        assertEquals(1900000,cfg.getTimeoutMs());assertEquals(16384,cfg.getStructuredMaxTokens());
        assertEquals("disabled",cfg.getStructuredOpenAiThinkingMode());assertTrue(cfg.getStructuredOpenAiReasoningSplit());
    }
    @Test void missingRelayTokenCannotFallBackToEitherOfficialCredentialVariable() throws IOException {
        Map<String,Object> vars=variables();vars.put("CONSENSE_MINIMAX_API_KEY","SYNTHETIC_GENERIC_KEY");
        assertEquals("",bind(true,vars).getApiKey());
    }
    @Test void directConfigurationKeepsTheLegacyCredentialAndBoundedRetryDefault() throws IOException {
        ConsenseProperties.Llm cfg=bind(false,variables());
        assertEquals("SYNTHETIC_OFFICIAL_KEY",cfg.getApiKey());assertTrue(cfg.isOverloadRetryEnabled());
    }
    @Test void directConfigurationAllowsAnExplicitGenericCredentialAlias() throws IOException {
        Map<String,Object> vars=variables();vars.put("CONSENSE_MINIMAX_API_KEY","SYNTHETIC_GENERIC_KEY");
        assertEquals("SYNTHETIC_GENERIC_KEY",bind(false,vars).getApiKey());
    }
}
