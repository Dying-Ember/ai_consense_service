package com.consense.web;

import com.consense.ai.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP outcomes with only external chat adapters doubled; no real credential or network call. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:llm_source_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.minimax-cn.api-key=","consense.storage-root=target/llm-profile-metadata-uploads"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class LlmSourceSelectionIntegrationTest {
    @Autowired MockMvc mvc;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;

    @Test void unconfiguredSelectedModelActionFailsButItsFailureHistoryAndInputsStayReadable() throws Exception {
        String id="unconfigured-profile-"+java.util.UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY\"}")).andExpect(jsonPath("$.code").value(0));
        byte[] bytes;
        try(org.apache.poi.xwpf.usermodel.XWPFDocument doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("No foundation works are included.");doc.write(out);bytes=out.toByteArray();
        }
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",id).file(new org.springframework.mock.web.MockMultipartFile("files","unconfigured-fixture.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)))
                .andExpect(jsonPath("$.data.parsed").value(1));
        mvc.perform(post("/api/drafting/{id}/variables/extract",id).header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.code").value(5001)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("missing_api_key")));
        mvc.perform(get("/api/drafting/{id}/variables/extract-traces",id).header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].status").value("failed"))
                .andExpect(jsonPath("$.data[0].modelIdentity.profileId").value("minimax-cn"));
        mvc.perform(get("/api/drafting/{id}/inputs",id).header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.length()").value(1));
        verifyNoInteractions(local,miniMax);
    }

    @Test void selectedHealthReportsItsSafeProviderRootWhileEmbeddingRemainsTheDeploymentModel() throws Exception {
        when(local.embedModel()).thenReturn("deployment-embedding");
        mvc.perform(get("/api/system/health").header("X-ConSense-Llm-Profile","minimax-cn"))
                .andExpect(jsonPath("$.data.llm.provider").value("openai"))
                .andExpect(jsonPath("$.data.llm.baseUrl").value("https://api.minimax.cn"))
                .andExpect(jsonPath("$.data.llm.chatModel").value("MiniMax-M3"))
                .andExpect(jsonPath("$.data.llm.embedModel").value("deployment-embedding"))
                .andExpect(jsonPath("$.data.llm.available").value(false));
        mvc.perform(options("/api/system/llm-profiles").header("Origin","http://localhost:5173")
                .header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","X-ConSense-Llm-Profile"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Headers","X-ConSense-Llm-Profile"));
        verify(miniMax,never()).available();
    }

    @Test void profilesExposeSafeConfigurationWithoutCallingProvidersAndRejectUnknownIds() throws Exception {
        mvc.perform(get("/api/system/llm-profiles")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.defaultProfile").value("local"))
                .andExpect(jsonPath("$.data.profiles.length()").value(2))
                .andExpect(jsonPath("$.data.profiles[0].id").value("local"))
                .andExpect(jsonPath("$.data.profiles[1].id").value("minimax-cn"))
                .andExpect(jsonPath("$.data.profiles[1].model").value("MiniMax-M3"))
                .andExpect(jsonPath("$.data.profiles[1].configured").value(false))
                .andExpect(jsonPath("$.data.profiles[1].unavailableReason").value("missing_api_key"))
                .andExpect(jsonPath("$.data.profiles[1].apiKey").doesNotExist());
        mvc.perform(get("/api/system/llm-profiles").header("X-ConSense-Llm-Profile","minimax-cn")).andExpect(status().isOk());
        mvc.perform(get("/api/system/llm-profiles").header("X-ConSense-Llm-Profile","arbitrary-provider"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(local,miniMax);
    }
}
