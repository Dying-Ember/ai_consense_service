package com.consense.ai;
import com.consense.config.ConsenseProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OpenAiAuthenticatedAvailabilityTest {
    @Test void availabilityUsesConfiguredCredential() {
        HttpSupport http = mock(HttpSupport.class);
        ConsenseProperties.Llm cfg = new ConsenseProperties.Llm();
        cfg.setBaseUrl("https://provider.example"); cfg.setApiKey("test-only-placeholder");
        when(http.get("https://provider.example/v1/models",4000,"Bearer test-only-placeholder")).thenReturn("{\"data\":[{\"id\":\"model\"}]}");
        assertTrue(new OpenAiLlmClient(cfg,http).available());
        verify(http).get("https://provider.example/v1/models",4000,"Bearer test-only-placeholder");
        verify(http,never()).get(anyString(),anyLong());
    }
}
