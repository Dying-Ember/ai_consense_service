package com.consense.web;

import com.consense.ai.AiGateway;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.consense.vector.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SystemHealthContractTest {
    @Test void readyDependenciesUseTheSameApiEnvelopeAsTheFrontendClient() throws Exception {
        AiGateway ai = mock(AiGateway.class); OcrClient ocr = mock(OcrClient.class); VectorStore vector = mock(VectorStore.class);
        when(ai.available()).thenReturn(true); when(ai.chatModel()).thenReturn("local-chat"); when(ai.embedModel()).thenReturn("local-embedding");
        when(ocr.available()).thenReturn(true); when(vector.available()).thenReturn(true);
        ConsenseProperties props = new ConsenseProperties();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SystemController(ai, ocr, vector, props)).build();
        mvc.perform(get("/api/system/health"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.llm.available").value(true))
                .andExpect(jsonPath("$.data.llm.chatModel").value("local-chat"))
                .andExpect(jsonPath("$.data.llm.embedModel").value("local-embedding"))
                .andExpect(jsonPath("$.data.ocr.available").value(true))
                .andExpect(jsonPath("$.data.vector.available").value(true));
    }
}
