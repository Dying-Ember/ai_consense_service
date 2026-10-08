package com.consense.web;

import com.consense.ai.LlmClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:advice_unconfigured;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.minimax-cn.api-key="})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class AdviceUnconfiguredProfileIntegrationTest {
    @Autowired MockMvc mvc;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;

    @Test void explicitUnconfiguredModelActionFailsButHistoryRemainsReadable() throws Exception {
        String id="advice-unconfigured-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/advice/{id}/ask",id).header("X-ConSense-Llm-Profile","minimax-cn")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Synthetic question\"}"))
                .andExpect(jsonPath("$.code").value(5001));
        mvc.perform(get("/api/advice/{id}/messages",id).header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.length()").value(0));
        verifyNoInteractions(local,miniMax);
    }
}
