package com.consense.web;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exact SQL upgrade of a populated H2 fixture; public HTTP assertions, not a Flyway/MySQL runtime claim. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=none","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.minimax-cn.api-key=migration-test-only"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class AdviceModelIdentityMigrationIntegrationTest {
    private static final String URL="jdbc:h2:mem:advice_migration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final String PROJECT="legacy-advice-before-profile";
    @DynamicPropertySource static void upgradedDatabase(DynamicPropertyRegistry r) {
        // Existing Flyway 7.15 cannot introspect this repo's H2 2.x. Execute exact SQL without changing dependencies.
        try(Connection connection=DriverManager.getConnection(URL,"sa","");Statement sql=connection.createStatement()) {
            for(String migration:new String[]{"V1__init.sql","V2__prompt_template.sql","V3__vetting_evidence_runs.sql",
                    "V4__vetting_source_roles.sql","V5__vetting_human_review.sql","V6__drafting_adoption_snapshot.sql",
                    "V7__drafting_formatted_artifacts.sql","V8__drafting_extraction_runs.sql",
                    "V9__vetting_model_identity.sql"})
                migrationSql(migration).populate(connection);
            sql.executeUpdate("INSERT INTO project(id,name_zh_hans,created_at,updated_at) VALUES ('"+PROJECT+"','LEGACY TEST ONLY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO chat_message(project_id,role,title,content,created_at) VALUES ('"+PROJECT+"','assistant','Legacy answer','Legacy text TEST ONLY',CURRENT_TIMESTAMP)");
            migrationSql("V10__advice_model_identity.sql").populate(connection);
        } catch(Exception error) {throw new IllegalStateException("Cannot seed pre-identity fixture",error);}
        r.add("spring.datasource.url",()->URL);
        r.add("consense.storage-root",()->java.nio.file.Paths.get("target","advice-migration-uploads").toAbsolutePath().toString());
    }
    private static ResourceDatabasePopulator migrationSql(String file) throws java.io.IOException {
        byte[] bytes;
        try(java.io.InputStream input=new ClassPathResource("db/migration/"+file).getInputStream()) {
            bytes=org.springframework.util.StreamUtils.copyToByteArray(input);
        }
        // A UTF-8 encoding signature is metadata, not SQL; leave every script byte after it unchanged.
        if(bytes.length>=3 && bytes[0]==(byte)0xef && bytes[1]==(byte)0xbb && bytes[2]==(byte)0xbf)
            bytes=Arrays.copyOfRange(bytes,3,bytes.length);
        ResourceDatabasePopulator populator=new ResourceDatabasePopulator(new ByteArrayResource(bytes,file));
        populator.setSqlScriptEncoding("UTF-8");
        return populator;
    }
    @Autowired MockMvc mvc;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;

    @Test void migratedHistoricalAnswerStaysUnknownAndNewAnswerIdentitySurvivesHttpReload() throws Exception {
        JsonNode old=history();assertEquals(1,old.size());
        assertEquals("Legacy text TEST ONLY",old.get(0).path("content").path("en").asText());
        assertTrue(old.get(0).path("modelIdentity").isMissingNode());
        when(local.available()).thenReturn(true);when(local.embed(anyList())).thenReturn(Collections.singletonList(new float[]{1,0}));
        when(miniMax.available()).thenReturn(true);
        when(miniMax.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"Post-migration answer TEST ONLY\"}");
        mvc.perform(multipart("/api/drafting/{id}/inputs/upload",PROJECT).file(new MockMultipartFile("files",
                "MIGRATION-TEST-ONLY.txt","text/plain","TEST ONLY. SCC4.1 is a synthetic basis.".getBytes(StandardCharsets.UTF_8))))
                .andExpect(jsonPath("$.data.parsed").value(1));
        mvc.perform(post("/api/advice/{id}/index/rebuild",PROJECT)).andExpect(jsonPath("$.code").value(0));
        JsonNode answer=JsonUtils.parse(mvc.perform(post("/api/advice/{id}/ask",PROJECT)
                .header("X-ConSense-Llm-Profile","minimax-cn").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"What is the synthetic basis?\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");
        JsonNode after=history();assertEquals(3,after.size());
        assertTrue(after.get(0).path("modelIdentity").isMissingNode());
        assertEquals("minimax-cn",after.get(2).path("modelIdentity").path("profileId").asText());
        assertEquals(answer.path("modelIdentity"),after.get(2).path("modelIdentity"));
    }
    private JsonNode history() throws Exception {
        return JsonUtils.parse(mvc.perform(get("/api/advice/{id}/messages",PROJECT).header("X-ConSense-Llm-Profile","local"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");
    }
}
