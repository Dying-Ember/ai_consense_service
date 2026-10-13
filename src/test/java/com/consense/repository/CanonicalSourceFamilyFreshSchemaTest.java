package com.consense.repository;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.service.sources.NativePdfSourceFamilyFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
/** Fresh Hibernate-created owned H2 schema; distinct from the exact migration upgrade check. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
class CanonicalSourceFamilyFreshSchemaTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){String url="jdbc:h2:mem:family_fresh_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";registry.add("spring.datasource.url",()->url);}
    @Autowired CanonicalSourceBindingRepository repository;@Autowired JdbcTemplate jdbc;@Autowired javax.persistence.EntityManager entityManager;
    @Test void newSchemaStoresFamilyNullLegacyPinsAndReloadsExactWire()throws Exception{
        CanonicalSourceBinding row=NativePdfSourceFamilyFixture.binding("authored-family-v1.json");row.setId(null);repository.saveAndFlush(row);entityManager.clear();CanonicalSourceBinding loaded=repository.findById(row.getId()).get();
        assertEquals(row.getSnapshotJson(),loaded.getSnapshotJson());assertNull(loaded.getAdoptionManifestSha256());assertNull(loaded.getReviewReferenceSha256());assertEquals(row.getFamilyCompositionSha256(),loaded.getFamilyCompositionSha256());assertEquals(row.getFamilyComponentSetSha256(),loaded.getFamilyComponentSetSha256());
        assertEquals("YES",jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name='canonical_source_binding' AND column_name='adoption_manifest_sha256'",String.class));
    }
}
