package com.consense.repository;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.service.sources.NativePdfSourceFamilyFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.Path;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
/** Exact V14 -> V15 on populated owned file DB, closed and reopened; no production schema claims. */
class CanonicalSourceFamilySqlUpgradeTest {
    @TempDir Path owned;
    static void populate(Connection c,String resource)throws Exception{ResourceDatabasePopulator sql=new ResourceDatabasePopulator(new ClassPathResource(resource));sql.setSqlScriptEncoding("UTF-8");sql.populate(c);}
    static void insert(Connection c,CanonicalSourceBinding b,boolean family)throws Exception{
        String columns="project_id,document_id,projection_sha256,original_source_sha256,java_parsed_source_hash,routing_sha256,source_revision_sha256,projection_revision_sha256,canonical_source_hash,scope_policy_sha256,adoption_manifest_sha256,review_reference_sha256,snapshot_bytes_sha256,declared_module,eligible_modules_json,snapshot_json,created_at";
        Object[] fields={b.getProjectId(),b.getDocumentId(),b.getProjectionSha256(),b.getOriginalSourceSha256(),b.getJavaParsedSourceHash(),b.getRoutingSha256(),b.getSourceRevisionSha256(),b.getProjectionRevisionSha256(),b.getCanonicalSourceHash(),b.getScopePolicySha256(),b.getAdoptionManifestSha256(),b.getReviewReferenceSha256(),b.getSnapshotBytesSha256(),b.getDeclaredModule(),b.getEligibleModulesJson(),b.getSnapshotJson(),Timestamp.from(b.getCreatedAt())};
        if(family)columns+=",family_composition_sha256,family_component_set_sha256";
        String placeholders=String.join(",",java.util.Collections.nCopies(family?19:17,"?"));try(PreparedStatement sql=c.prepareStatement("INSERT INTO canonical_source_binding ("+columns+") VALUES ("+placeholders+")")){
            for(int i=0;i<fields.length;i++)sql.setObject(i+1,fields[i]);if(family){sql.setString(18,b.getFamilyCompositionSha256());sql.setString(19,b.getFamilyComponentSetSha256());}sql.executeUpdate();}
    }
    @Test void populatedUpgradeKeepsAllOldFieldsAndFamilyIdentityAfterCloseReopen()throws Exception{
        String url="jdbc:h2:file:"+owned.resolve("family-upgrade").toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        CanonicalSourceBinding old=NativePdfSourceFamilyFixture.binding("authored-child-v1.json"),family=NativePdfSourceFamilyFixture.binding("authored-family-v1.json");
        try(Connection c=DriverManager.getConnection(url,"sa","");Statement st=c.createStatement()){
            st.execute("CREATE TABLE source_document (id BIGINT PRIMARY KEY)");populate(c,"db/migration/V14__canonical_source_bindings.sql");insert(c,old,false);
            populate(c,"db/migration/V15__canonical_source_family_pins.sql");insert(c,family,true);
            try(ResultSet r=st.executeQuery("SELECT is_nullable FROM information_schema.columns WHERE table_name='canonical_source_binding' AND column_name='adoption_manifest_sha256'")){assertTrue(r.next());assertEquals("YES",r.getString(1));}
        }
        try(Connection c=DriverManager.getConnection(url,"sa","");PreparedStatement st=c.prepareStatement("SELECT * FROM canonical_source_binding WHERE projection_sha256=?")){
            for(CanonicalSourceBinding expected:new CanonicalSourceBinding[]{old,family}){st.setString(1,expected.getProjectionSha256());try(ResultSet r=st.executeQuery()){
                assertTrue(r.next());assertEquals(expected.getSnapshotJson(),r.getString("snapshot_json"));assertEquals(expected.getSnapshotBytesSha256(),r.getString("snapshot_bytes_sha256"));assertEquals(expected.getOriginalSourceSha256(),r.getString("original_source_sha256"));assertEquals(expected.getJavaParsedSourceHash(),r.getString("java_parsed_source_hash"));assertEquals(expected.getRoutingSha256(),r.getString("routing_sha256"));assertEquals(expected.getSourceRevisionSha256(),r.getString("source_revision_sha256"));assertEquals(expected.getProjectionRevisionSha256(),r.getString("projection_revision_sha256"));assertEquals(expected.getCanonicalSourceHash(),r.getString("canonical_source_hash"));assertEquals(expected.getScopePolicySha256(),r.getString("scope_policy_sha256"));assertEquals(expected.getEligibleModulesJson(),r.getString("eligible_modules_json"));assertEquals(expected.getDeclaredModule(),r.getString("declared_module"));assertEquals(expected.getAdoptionManifestSha256(),r.getString("adoption_manifest_sha256"));assertEquals(expected.getReviewReferenceSha256(),r.getString("review_reference_sha256"));assertEquals(expected.getFamilyCompositionSha256(),r.getString("family_composition_sha256"));assertEquals(expected.getFamilyComponentSetSha256(),r.getString("family_component_set_sha256"));assertFalse(r.next());
            }}
        }
    }
}
