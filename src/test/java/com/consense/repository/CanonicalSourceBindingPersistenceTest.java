package com.consense.repository;

import com.consense.domain.CanonicalSourceBinding;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the exact new SQL against populated H2 MySQL-mode state, then uses actual JPA. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=none","spring.flyway.enabled=false"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
class CanonicalSourceBindingPersistenceTest {
    private static final String URL="jdbc:h2:mem:canonical_migration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        try(Connection c=DriverManager.getConnection(URL,"sa","");Statement sql=c.createStatement()){
            sql.execute("CREATE TABLE source_document(id BIGINT PRIMARY KEY,project_id VARCHAR(64),file_name VARCHAR(512))");
            sql.execute("INSERT INTO source_document VALUES(11,'project-a','legacy-preserved.pdf')");
            ResourceDatabasePopulator migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V14__canonical_source_bindings.sql"));
            migration.setSqlScriptEncoding("UTF-8");migration.populate(c);
            ResourceDatabasePopulator familyMigration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V15__canonical_source_family_pins.sql"));familyMigration.setSqlScriptEncoding("UTF-8");familyMigration.populate(c);
        }catch(Exception error){throw new IllegalStateException("Cannot execute canonical migration",error);}
        registry.add("spring.datasource.url",()->URL);
    }
    @Autowired CanonicalSourceBindingRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.persistence.EntityManager entityManager;
    @Test void exact_sql_preserves_legacy_unknowns_and_jpa_roundtrips_complete_snapshot(){
        assertEquals("legacy-preserved.pdf",jdbc.queryForObject("SELECT file_name FROM source_document WHERE id=11",String.class));
        assertNull(jdbc.queryForObject("SELECT source_sha256 FROM source_document WHERE id=11",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM canonical_source_binding",Integer.class));
        CanonicalSourceBinding row=new CanonicalSourceBinding();row.setProjectId("project-a");row.setDocumentId(11L);row.setProjectionSha256(sha());
        row.setOriginalSourceSha256(sha());row.setJavaParsedSourceHash(sha());row.setRoutingSha256(sha());row.setSourceRevisionSha256(sha());
        row.setProjectionRevisionSha256(sha());row.setCanonicalSourceHash(sha());row.setScopePolicySha256(sha());row.setAdoptionManifestSha256(sha());
        row.setReviewReferenceSha256(sha());row.setSnapshotBytesSha256(sha());row.setDeclaredModule("vetting");row.setEligibleModulesJson("[\"vetting\",\"advisory\"]");
        String raw="{\"future\":{\"text\":\"中文 😀\",\"unknown\":null},\"float\":1e-07}";row.setSnapshotJson(raw);row.setCreatedAt(Instant.now());
        repository.saveAndFlush(row);entityManager.clear();
        CanonicalSourceBinding loaded=repository.findByProjectIdAndDocumentIdAndProjectionSha256("project-a",11L,sha()).get();
        assertEquals(raw,loaded.getSnapshotJson());assertEquals(row.getId(),loaded.getId());
        assertEquals(1,repository.findByProjectIdAndDocumentIdOrderByIdDesc("project-a",11L).size());
    }
    @Test void familyPinsAndNullLegacyReviewPinsRoundTripInActualJpaAfterExactUpgrade()throws Exception{
        CanonicalSourceBinding row=com.consense.service.sources.NativePdfSourceFamilyFixture.binding("authored-family-v1.json");row.setId(null);
        repository.saveAndFlush(row);entityManager.clear();CanonicalSourceBinding loaded=repository.findById(row.getId()).get();
        assertNull(loaded.getAdoptionManifestSha256());assertNull(loaded.getReviewReferenceSha256());
        assertEquals(row.getFamilyCompositionSha256(),loaded.getFamilyCompositionSha256());assertEquals(row.getFamilyComponentSetSha256(),loaded.getFamilyComponentSetSha256());
        assertEquals(row.getSnapshotJson(),loaded.getSnapshotJson());
        com.consense.service.sources.CanonicalEvidenceService.validateBindingPins(loaded,com.consense.common.JsonUtils.parse(loaded.getSnapshotJson()));
    }
    private static String sha(){return String.join("",java.util.Collections.nCopies(64,"a"));}
}
