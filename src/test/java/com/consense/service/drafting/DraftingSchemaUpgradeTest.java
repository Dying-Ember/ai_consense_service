package com.consense.service.drafting;

import com.consense.domain.DraftDocument;
import com.consense.domain.DraftVariable;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** The existing local H2 profile upgrades populated tables without Flyway. */
class DraftingSchemaUpgradeTest {
    @Test void localH2UpgradePreservesLegacyInputAndDraftWithSafeNewColumnDefaults() throws Exception {
        String url="jdbc:h2:mem:legacy_drafting_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try(Connection connection=DriverManager.getConnection(url,"sa","");Statement sql=connection.createStatement()) {
            sql.execute("CREATE TABLE draft_variable (id BIGINT AUTO_INCREMENT PRIMARY KEY,project_id VARCHAR(64) NOT NULL,var_key VARCHAR(64) NOT NULL,scope VARCHAR(16) NOT NULL,value_text LONGTEXT,confirmed BOOLEAN NOT NULL,sort_order INTEGER NOT NULL,updated_at TIMESTAMP NOT NULL)");
            sql.execute("INSERT INTO draft_variable (project_id,var_key,scope,value_text,confirmed,sort_order,updated_at) VALUES ('legacy','foundationIncluded','INPUT','true',TRUE,0,CURRENT_TIMESTAMP)");
            sql.execute("CREATE TABLE draft_document (id BIGINT AUTO_INCREMENT PRIMARY KEY,project_id VARCHAR(64) NOT NULL,file_key VARCHAR(16) NOT NULL,content LONGTEXT,`generated` BOOLEAN NOT NULL,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL)");
            sql.execute("INSERT INTO draft_document (project_id,file_key,content,`generated`,created_at,updated_at) VALUES ('legacy','NTT','Original legacy English draft.',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
        Configuration configuration=new Configuration().addAnnotatedClass(DraftVariable.class).addAnnotatedClass(DraftDocument.class);
        configuration.setProperty("hibernate.connection.driver_class","org.h2.Driver");
        configuration.setProperty("hibernate.connection.url",url);configuration.setProperty("hibernate.connection.username","sa");configuration.setProperty("hibernate.connection.password","");
        configuration.setProperty("hibernate.dialect","org.hibernate.dialect.H2Dialect");configuration.setProperty("hibernate.hbm2ddl.auto","update");
        try(SessionFactory factory=configuration.buildSessionFactory();Session session=factory.openSession()) {
            DraftVariable variable=session.get(DraftVariable.class,1L);DraftDocument document=session.get(DraftDocument.class,1L);
            assertEquals("true",variable.getValueText());assertEquals(Boolean.TRUE,variable.getConfirmed());
            assertEquals(Boolean.FALSE,variable.getManuallyEdited());assertEquals(Boolean.FALSE,variable.getReviewRequired());
            assertEquals("Original legacy English draft.",document.getContent());assertEquals(Boolean.TRUE,document.getGenerated());
            assertEquals(Boolean.FALSE,document.getContentEdited());assertNull(document.getSnapshotId());
        }
    }
}
