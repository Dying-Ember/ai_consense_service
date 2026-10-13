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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exact SQL upgrade of a populated H2 fixture; public HTTP assertions, not a Flyway/MySQL runtime claim. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=none","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory",
        "consense.llm.num-ctx=262144","consense.llm.structured-thinking=false",
        "consense.minimax-cn.enabled=true","consense.minimax-cn.base-url=https://minimax-test.invalid",
        "consense.minimax-cn.api-key=migration-test-only"})
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
            migrationSql("V13__project_document_scope_and_advice_evidence.sql").populate(connection);
            migrationSql("V14__canonical_source_bindings.sql").populate(connection);
            migrationSql("V16__advice_sessions.sql").populate(connection);
            migrationSql("V17__vetting_recovery_and_report_snapshots.sql").populate(connection);
            migrationSql("V18__advice_full_evidence_identity.sql").populate(connection);
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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;
    @MockBean com.consense.service.vetting.VettingTokenBudgetCliObserver counter;

    @Test void migratedHistoricalAnswerStaysUnknownAndNewAnswerIdentitySurvivesHttpReload() throws Exception {
        com.consense.service.advice.AdviceBudgetFixture.bind(counter,local,miniMax);
        JsonNode old=history();assertEquals(1,old.size());
        assertEquals("Legacy text TEST ONLY",old.get(0).path("content").path("en").asText());
        assertTrue(old.get(0).path("modelIdentity").isMissingNode());
        when(local.available()).thenReturn(true);when(local.embed(anyList())).thenReturn(Collections.singletonList(new float[]{1,0}));
        when(miniMax.available()).thenReturn(true);
        when(miniMax.chat(anyList())).thenReturn("{\"grounded\":true,\"answer\":\"Post-migration answer TEST ONLY\",\"citations\":[\"[1]\"]}");
        mvc.perform(multipart("/api/vetting/{id}/package/upload",PROJECT).file(new MockMultipartFile("files",
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
        JsonNode session=JsonUtils.parse(mvc.perform(post("/api/advice/{id}/sessions",PROJECT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"contract\",\"basisScope\":\"project_contract\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");
        mvc.perform(get("/api/advice/{id}/sessions",PROJECT)).andExpect(jsonPath("$.data[0].id").value(session.path("id").asText()));
        mvc.perform(get("/api/advice/{id}/messages",PROJECT).param("sessionId",session.path("id").asText())).andExpect(jsonPath("$.data").isEmpty());
        assertEquals(after,history(),"Session migration and creation must preserve legacy answer snapshots");
    }
    private JsonNode history() throws Exception {
        return JsonUtils.parse(mvc.perform(get("/api/advice/{id}/messages",PROJECT).header("X-ConSense-Llm-Profile","local"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void exactSqlUpgradeAndHttpReloadPreserveTheFullFixedEvidenceIdentity() throws Exception {
        String project="long-evidence-"+UUID.randomUUID();String identity="fixed-baseline:fixed:"+String.join("",Collections.nCopies(64,"a"));assertEquals(85,identity.length());
        jdbc.update("INSERT INTO project(id,name_zh_hans,created_at,updated_at) VALUES (?, ?, CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",project,"FULL ID TEST ONLY");
        jdbc.update("INSERT INTO chat_message(project_id,role,title,content,evidence_id,created_at) VALUES (?, 'assistant', 'Fixed evidence answer', 'FULL ID TEST ONLY', ?, CURRENT_TIMESTAMP)",project,identity);
        assertEquals(256,jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_name='chat_message' AND column_name='evidence_id'",Integer.class));
        JsonNode history=JsonUtils.parse(mvc.perform(get("/api/advice/{id}/messages",project)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(1,history.size());assertEquals(identity,history.get(0).path("evidenceId").asText());assertEquals("FULL ID TEST ONLY",history.get(0).path("content").path("en").asText());
        verify(local,never()).chatStructured(anyList(),any());verify(miniMax,never()).chatStructured(anyList(),any());
    }
}
