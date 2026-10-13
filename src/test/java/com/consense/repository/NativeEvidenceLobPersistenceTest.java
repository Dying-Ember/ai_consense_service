package com.consense.repository;

import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.EnumSet;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Checks actual dialect DDL and file-backed restart, without parsing or model substitutes. */
class NativeEvidenceLobPersistenceTest {
    @TempDir Path directory;

    @Test void h2_mysql_mode_creates_real_lobs_for_all_source_and_binding_payloads() throws Exception {
        String url = databaseUrl("schema");
        try (SessionFactory factory = factory(url, "create")) {
            try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
                for (String column : new String[]{"oer_identity_json", "native_pdf_evidence_json",
                        "text_content", "structured_content_json", "parse_coverage_json"}) {
                    assertClob(connection, "source_document", column);
                }
                assertClob(connection, "canonical_source_binding", "snapshot_json");
                assertClob(connection, "canonical_source_binding", "eligible_modules_json");
            }
        }
    }

    @Test void complete_unicode_payloads_survive_checkpoint_shutdown_and_new_factory() throws Exception {
        String url = databaseUrl("restart");
        String chunk = "原件 😀 {\"blank\":null,\"literal\":1e-07,\"cells\":[\"\",\" ; \"]}\r\n";
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 65536; i++) large.append(chunk);
        String payload = large.toString();
        Long sourceId;
        Long bindingId;
        try (SessionFactory factory = factory(url, "create")) {
            try (Session session = factory.openSession()) {
                session.beginTransaction();
                SourceDocument source = new SourceDocument();
                source.setProjectId("lob-restart"); source.setCategory("VETTING_PACKAGE");
                source.setFileName("original-preserved.pdf"); source.setParseStatus("PARTIAL");
                source.setOerIdentityJson(payload); source.setNativePdfEvidenceJson(payload);
                source.setTextContent(payload); source.setStructuredContentJson(payload);
                source.setParseCoverageJson(payload);
                session.save(source);
                CanonicalSourceBinding binding = new CanonicalSourceBinding();
                binding.setProjectId(source.getProjectId()); binding.setDocumentId(source.getId());
                binding.setProjectionSha256(sha()); binding.setOriginalSourceSha256(sha());
                binding.setJavaParsedSourceHash(sha()); binding.setRoutingSha256(sha());
                binding.setSourceRevisionSha256(sha()); binding.setProjectionRevisionSha256(sha());
                binding.setCanonicalSourceHash(sha()); binding.setScopePolicySha256(sha());
                binding.setAdoptionManifestSha256(sha()); binding.setReviewReferenceSha256(sha());
                binding.setSnapshotBytesSha256(sha()); binding.setDeclaredModule("advisory");
                binding.setEligibleModulesJson("[\"vetting\",\"advisory\"]"); binding.setSnapshotJson(payload);
                session.save(binding); session.getTransaction().commit();
                sourceId = source.getId(); bindingId = binding.getId();
            }
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                    Statement sql = connection.createStatement()) { sql.execute("CHECKPOINT SYNC"); }
        }
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement sql = connection.createStatement()) { sql.execute("SHUTDOWN"); }
        try (SessionFactory reopened = factory(url, "validate"); Session session = reopened.openSession()) {
            SourceDocument source = session.get(SourceDocument.class, sourceId);
            assertEquals("PARTIAL", source.getParseStatus());
            assertEquals(payload, source.getOerIdentityJson()); assertEquals(payload, source.getNativePdfEvidenceJson());
            assertEquals(payload, source.getTextContent()); assertEquals(payload, source.getStructuredContentJson());
            assertEquals(payload, source.getParseCoverageJson());
            CanonicalSourceBinding binding = session.get(CanonicalSourceBinding.class, bindingId);
            assertEquals(payload, binding.getSnapshotJson());
            assertEquals("[\"vetting\",\"advisory\"]", binding.getEligibleModulesJson());
            assertEquals(sourceId, binding.getDocumentId());
        }
    }

    @Test void mysql57_dialect_keeps_longtext_without_a_database_connection() throws Exception {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQL57Dialect")
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false").build();
        try {
            Metadata metadata = metadata(registry);
            Path script = directory.resolve("mysql57-generated.sql");
            SchemaExport export = new SchemaExport().setOutputFile(script.toString());
            export.createOnly(EnumSet.of(TargetType.SCRIPT), metadata);
            assertTrue(export.getExceptions().isEmpty());
            String ddl = new String(Files.readAllBytes(script), StandardCharsets.UTF_8).toLowerCase();
            for (String column : new String[]{"oer_identity_json", "native_pdf_evidence_json",
                    "text_content", "structured_content_json", "parse_coverage_json", "snapshot_json",
                    "eligible_modules_json"}) {
                assertTrue(ddl.contains(column + " longtext"), column + " must remain MySQL LONGTEXT");
            }
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }

    private String databaseUrl(String name) {
        return "jdbc:h2:file:" + directory.resolve(name).toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    }
    private SessionFactory factory(String url, String ddl) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", url).applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.hbm2ddl.auto", ddl).build();
        try { return metadata(registry).buildSessionFactory(); }
        catch (RuntimeException error) { StandardServiceRegistryBuilder.destroy(registry); throw error; }
    }
    private Metadata metadata(StandardServiceRegistry registry) {
        return new MetadataSources(registry).addAnnotatedClass(SourceDocument.class)
                .addAnnotatedClass(CanonicalSourceBinding.class).buildMetadata();
    }
    private void assertClob(Connection connection, String table, String column) throws Exception {
        try (java.sql.PreparedStatement query = connection.prepareStatement(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME=?")) {
            query.setString(1, table); query.setString(2, column);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next(), table + "." + column);
                assertEquals("character large object", row.getString(1), table + "." + column);
            }
        }
    }
    private String sha() { return String.join("", java.util.Collections.nCopies(64, "a")); }
}
