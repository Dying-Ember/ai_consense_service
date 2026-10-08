package com.consense.service.vetting;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.util.StreamUtils;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

class VettingMigrationTest {
    @Test void upgradesExistingSchemaWithoutDeletingHumanStatuses() throws Exception {
        try (Connection connection=DriverManager.getConnection("jdbc:h2:mem:vetting_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","")) {
            execute(connection,"db/migration/V1__init.sql");
            execute(connection,"db/migration/V2__prompt_template.sql");
            try (Statement stmt=connection.createStatement()) {
                stmt.execute("insert into vetting_finding(project_id,code,group_key,status,created_at) values('p','VT-001','reference','Handled',CURRENT_TIMESTAMP)");
                stmt.execute("insert into source_document(project_id,category,file_key,file_name,text_content,created_at) values('p','VETTING_PACKAGE','PRE','PRE.docx','Existing tender wording',CURRENT_TIMESTAMP)");
            }
            execute(connection,"db/migration/V3__vetting_evidence_runs.sql");
            execute(connection,"db/migration/V4__vetting_source_roles.sql");
            execute(connection,"db/migration/V5__vetting_human_review.sql");
            try (Statement stmt=connection.createStatement();ResultSet row=stmt.executeQuery("select status,active,fingerprint,review_remarks,action_taken,addendum_required,review_updated_at from vetting_finding where code='VT-001'")) {
                assertTrue(row.next()); assertEquals("Handled",row.getString("status")); assertTrue(row.getBoolean("active")); assertNull(row.getString("fingerprint"));
                assertNull(row.getString("review_remarks")); assertNull(row.getString("action_taken"));
                assertNull(row.getObject("addendum_required")); assertNull(row.getTimestamp("review_updated_at"));
            }
            String remarks="  原文保留\r\nReviewed by project team.  ", action="Not yet issued in an addendum.";
            Timestamp reviewTime=Timestamp.from(java.time.Instant.parse("2026-10-02T03:30:00.123Z"));
            try (PreparedStatement update=connection.prepareStatement("update vetting_finding set review_remarks=?,action_taken=?,addendum_required=?,review_updated_at=? where code='VT-001'")) {
                update.setString(1,remarks);update.setString(2,action);update.setBoolean(3,false);update.setTimestamp(4,reviewTime);
                assertEquals(1,update.executeUpdate());
            }
            try (Statement stmt=connection.createStatement();ResultSet row=stmt.executeQuery("select status,review_remarks,action_taken,addendum_required,review_updated_at from vetting_finding where code='VT-001'")) {
                assertTrue(row.next());assertEquals("Handled",row.getString("status"));
                assertEquals(remarks,row.getString("review_remarks"));assertEquals(action,row.getString("action_taken"));
                assertFalse(row.getBoolean("addendum_required"));assertFalse(row.wasNull());assertEquals(reviewTime,row.getTimestamp("review_updated_at"));
            }
            try (Statement stmt=connection.createStatement()) {
                try (ResultSet row=stmt.executeQuery("select review_role,text_content from source_document where file_name='PRE.docx'")) {
                    assertTrue(row.next()); assertNull(row.getString("review_role"));
                    assertEquals("Existing tender wording", row.getString("text_content"));
                }
                stmt.execute("update source_document set review_role='project_fact' where file_name='PRE.docx'");
                try (ResultSet row=stmt.executeQuery("select review_role from source_document where file_name='PRE.docx'")) {
                    assertTrue(row.next()); assertEquals("project_fact", row.getString(1));
                }
                stmt.execute("insert into vetting_run(id,project_id,status,started_at) values('r','p','QUEUED',CURRENT_TIMESTAMP)");
                try (ResultSet row=stmt.executeQuery("select total_units,result_json,coverage_json from vetting_run where id='r'")) { assertTrue(row.next()); assertEquals(0,row.getInt(1)); assertNull(row.getString(2)); assertNull(row.getString(3)); }
            }
            execute(connection,"db/migration/V9__vetting_model_identity.sql");
            try(Statement stmt=connection.createStatement();ResultSet row=stmt.executeQuery("select status,model_identity_json from vetting_run where id='r'")) {
                assertTrue(row.next());assertEquals("QUEUED",row.getString(1));assertNull(row.getString(2),"Historical runs must remain unknown after migration.");
            }
        }
    }

    private static void execute(Connection connection,String resource) throws Exception {
        String sql;
        try(InputStream input=new ClassPathResource(resource).getInputStream()) { sql=StreamUtils.copyToString(input,StandardCharsets.UTF_8); }
        // Preserve production migrations. Adapt only historical MySQL table options for this H2 check.
        sql=sql.replace("\ufeff","").replaceAll("(?i)ENGINE\\s*=\\s*InnoDB\\s+DEFAULT\\s+CHARSET\\s*=\\s*utf8mb4\\s+COLLATE\\s*=\\s*utf8mb4_general_ci","");
        ScriptUtils.executeSqlScript(connection,new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));
    }
}
