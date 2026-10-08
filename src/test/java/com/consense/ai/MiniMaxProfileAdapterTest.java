package com.consense.ai;

import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** External HTTP boundary only; credentials here are synthetic fixtures, never environment reads. */
class MiniMaxProfileAdapterTest {
    @Test void ordinaryAndStructuredCallsUseConfiguredChinaRootLongOutputCapAndSeparatedReasoning() {
        ConsenseProperties props=new ConsenseProperties();ConsenseProperties.Llm cfg=props.getMinimaxCn();cfg.setApiKey("test-only-key");
        HttpSupport http=mock(HttpSupport.class);
        when(http.postJson(anyString(),anyString(),anyLong(),anyString(),anyInt())).thenAnswer(call->{
            assertEquals("https://api.minimax.cn/v1/chat/completions",call.getArgument(0));
            JsonNode request=JsonUtils.parse(call.getArgument(1));assertEquals("MiniMax-M3",request.path("model").asText());
            assertEquals(16384,request.path("max_tokens").asInt());assertEquals("disabled",request.path("thinking").path("type").asText());
            assertTrue(request.path("reasoning_split").asBoolean());assertEquals("Bearer test-only-key",call.getArgument(3));assertEquals(0,(int)call.getArgument(4));
            assertFalse(request.toString().contains("test-only-key"));
            return "{\"base_resp\":{\"status_code\":0},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\",\"reasoning_content\":\"DO NOT CONSUME\"}}]}";
        });
        MiniMaxChatClient client=new MiniMaxChatClient(cfg,http);
        String fingerprint=LlmProfiles.configurationSha256(cfg);
        cfg.setApiKey("second-test-only-key");assertEquals(fingerprint,LlmProfiles.configurationSha256(cfg));cfg.setApiKey("test-only-key");
        assertFalse(JsonUtils.write(cfg).contains("test-only-key"));assertFalse(cfg.toString().contains("test-only-key"));
        List<LlmClient.ChatTurn> turns=Arrays.asList(LlmClient.ChatTurn.system("TEST ONLY"),LlmClient.ChatTurn.user("synthetic source"));
        assertEquals("[]",client.chat(turns));assertEquals("[]",client.chatStructured(turns,JsonUtils.mapper().createObjectNode()));
        assertEquals(2048,props.getLlm().getStructuredMaxTokens(),"Existing local deployment cap must remain unchanged.");
    }

    @Test void providerFailuresRemainFailuresButCannotPersistAnEchoedCredential() {
        ConsenseProperties.Llm cfg=new ConsenseProperties().getMinimaxCn();cfg.setApiKey("synthetic-secret-do-not-persist");HttpSupport http=mock(HttpSupport.class);
        when(http.postJson(anyString(),anyString(),anyLong(),anyString(),anyInt())).thenReturn("{\"base_resp\":{\"status_code\":1008,\"status_msg\":\"quota synthetic-secret-do-not-persist\"},\"choices\":[]}");
        MiniMaxChatClient client=new MiniMaxChatClient(cfg,http);
        IncompleteModelResponseException response=assertThrows(IncompleteModelResponseException.class,()->client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
        assertEquals(IncompleteModelResponseException.FailureKind.PROVIDER_ERROR,response.getFailureKind());
        assertFalse(response.getRawResponse().contains(cfg.getApiKey()));assertEquals(1008,response.getResponseMetadata().path("providerStatusCode").asInt());
        when(http.postJson(anyString(),anyString(),anyLong(),anyString(),anyInt())).thenThrow(new BizException("HTTP401 Authorization: Bearer synthetic-secret-do-not-persist"));
        BizException transport=assertThrows(BizException.class,()->client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
        assertTrue(transport.getMessage().contains("HTTP401"));assertFalse(transport.getMessage().contains(cfg.getApiKey()));
    }
}
