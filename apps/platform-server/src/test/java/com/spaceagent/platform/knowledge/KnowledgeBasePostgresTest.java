package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.KnowledgeBase;
import com.spaceagent.platform.knowledge.domain.KnowledgeBaseGrant;
import com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresKnowledgeBaseRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Timestamp;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class KnowledgeBasePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("knowledge_base_upgrade").withUsername("spaceagent").withPassword("fixture-password");
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    @BeforeAll static void upgrade() {
        ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target("1085").load().migrate();
        seed(jdbc, "org-one", "creator");
        seed(jdbc, "org-two", "stranger");
        jdbc.update("""
                INSERT INTO platform_knowledge_documents(id, owner_id, name, content_type, storage_location, status, created_at, updated_at)
                VALUES ('legacy-doc', 'creator', 'legacy', 'text/plain', 'inline:private', 'UPLOADED', ?, ?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        migrate(ds);
    }

    @Test void upgradePreservesLegacyDocumentOwnershipAndFreshSchemaMigrates() {
        assertThat(jdbc.queryForObject("SELECT owner_id FROM platform_knowledge_documents WHERE id='legacy-doc'", String.class))
                .isEqualTo("creator");
        jdbc.execute("CREATE DATABASE knowledge_base_fresh");
        var fresh = new DriverManagerDataSource(POSTGRES.getJdbcUrl().replace("knowledge_base_upgrade", "knowledge_base_fresh"),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        migrate(fresh);
        assertThat(new JdbcTemplate(fresh).queryForObject("SELECT count(*) FROM platform_knowledge_bases", Integer.class)).isZero();
    }

    @Test void roundTripFiltersBeforePaginationAndRevisionCasRejectsLostUpdates() {
        var repository = new PostgresKnowledgeBaseRepository(jdbc);
        KnowledgeBase base = base("base-cas", "org-one");
        repository.insert(base);
        repository.putGrant(new KnowledgeBaseGrant(base.id(), "org-one", KnowledgeBaseGrant.SubjectType.ROLE,
                "MEMBER", KnowledgeBase.Permission.READ, "creator", NOW));
        assertThat(repository.listVisible("member", "org-two", "MEMBER", 0, 10)).isEmpty();
        assertThat(repository.listVisible("member", "org-one", "MEMBER", 0, 1)).containsExactly(base);
        assertThat(repository.update(base.revise("first", null, base.state(), NOW), 1)).isTrue();
        assertThat(repository.update(base.revise("stale", null, base.state(), NOW), 1)).isFalse();
        var reconnected = new PostgresKnowledgeBaseRepository(new JdbcTemplate(ds));
        assertThat(reconnected.find(base.id()).orElseThrow().name()).isEqualTo("first");
        repository.removeGrant(base.id(), KnowledgeBaseGrant.SubjectType.ROLE, "MEMBER");
        assertThat(repository.listVisible("member", "org-one", "MEMBER", 0, 10)).isEmpty();
    }

    @Test void grantsHaveCompositeScopeAndTransactionRollback() {
        var repository = new PostgresKnowledgeBaseRepository(jdbc);
        KnowledgeBase base = base("base-tx", "org-one");
        repository.insert(base);
        assertThatThrownBy(() -> repository.putGrant(new KnowledgeBaseGrant(base.id(), "org-two",
                KnowledgeBaseGrant.SubjectType.ROLE, "MEMBER", KnowledgeBase.Permission.READ, "stranger", NOW)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            assertThat(repository.lock(base.id())).isPresent();
            repository.update(base.revise("rolled back", null, base.state(), NOW), 1);
            repository.putGrant(new KnowledgeBaseGrant(base.id(), "org-one", KnowledgeBaseGrant.SubjectType.USER,
                    "member", KnowledgeBase.Permission.WRITE, "creator", NOW));
            throw new IllegalStateException("simulate interrupted grant");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(base.id()).orElseThrow().revision()).isEqualTo(1);
        assertThat(repository.grants(base.id())).isEmpty();
        repository.insert(new KnowledgeBase("personal", KnowledgeBase.Scope.PERSONAL, null, "creator", "private", null,
                KnowledgeBase.State.ACTIVE, 1, NOW, NOW));
        assertThatThrownBy(() -> repository.putGrant(new KnowledgeBaseGrant("personal", "org-one",
                KnowledgeBaseGrant.SubjectType.USER, "member", KnowledgeBase.Permission.READ, "creator", NOW)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void deletingCreatorRemovesPersonalBasesButPreservesOrganizationAuthority() {
        seed(jdbc, "cleanup-org", "cleanup-user");
        var repository = new PostgresKnowledgeBaseRepository(jdbc);
        repository.insert(new KnowledgeBase("cleanup-private", KnowledgeBase.Scope.PERSONAL, null, "cleanup-user", "private", null,
                KnowledgeBase.State.ACTIVE, 1, NOW, NOW));
        repository.insert(new KnowledgeBase("cleanup-shared", KnowledgeBase.Scope.ORGANIZATION, "cleanup-org", "cleanup-user", "shared", null,
                KnowledgeBase.State.ACTIVE, 1, NOW, NOW));
        var cleanup = new com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresKnowledgeCleanupService(jdbc);
        assertThat(cleanup.cleanupUser("cleanup-user").blocked()).isFalse();
        jdbc.update("DELETE FROM platform_users WHERE id='cleanup-user'");
        assertThat(repository.find("cleanup-private")).isEmpty();
        assertThat(repository.find("cleanup-shared").orElseThrow().ownerId()).isNull();
        assertThat(repository.listVisible("new-owner", "cleanup-org", "OWNER", 0, 10))
                .extracting(KnowledgeBase::id).contains("cleanup-shared");
        assertThat(cleanup.cleanupOrganization("cleanup-org").blocked()).isFalse();
        assertThat(repository.find("cleanup-shared")).isEmpty();
    }

    @Test void organizationDocumentRemainsAfterCreatorDeletionAndOwnerlessPrivateRowsAreRejected() {
        seed(jdbc,"shared-doc-org","shared-doc-creator");
        var repository=new PostgresKnowledgeBaseRepository(jdbc);
        repository.insert(new KnowledgeBase("shared-doc-base",KnowledgeBase.Scope.ORGANIZATION,"shared-doc-org","shared-doc-creator",
                "organization source",null,KnowledgeBase.State.ACTIVE,1,NOW,NOW));
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(s->{
                    jdbc.update("INSERT INTO platform_knowledge_documents(id,owner_id,name,content_type,storage_location,status,created_at,updated_at) VALUES ('shared-doc',NULL,'shared','text/plain','managed-index','UPLOADED',now(),now())");
                    jdbc.update("INSERT INTO platform_knowledge_document_scopes(document_id,base_id,original_owner_id,provenance,assigned_at) VALUES ('shared-doc','shared-doc-base',NULL,'EXPLICIT',now())");
                });
        assertThat(new com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresKnowledgeCleanupService(jdbc).cleanupUser("shared-doc-creator").blocked()).isFalse();
        jdbc.update("DELETE FROM platform_users WHERE id='shared-doc-creator'");
        assertThat(repository.find("shared-doc-base").orElseThrow().ownerId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_documents WHERE id='shared-doc'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("INSERT INTO platform_knowledge_documents(id,owner_id,name,content_type,storage_location,status,created_at,updated_at) VALUES ('ownerless-private',NULL,'invalid','text/plain','inline:x','UPLOADED',now(),now())"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform_knowledge_document_scopes WHERE document_id='shared-doc'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE platform_knowledge_bases SET scope='PERSONAL',organization_id=NULL,owner_id='creator' WHERE id='shared-doc-base'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM platform_knowledge_documents WHERE id='shared-doc'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_document_scopes WHERE document_id='shared-doc'",Integer.class)).isZero();
    }

    private static KnowledgeBase base(String id, String org) {
        return new KnowledgeBase(id, KnowledgeBase.Scope.ORGANIZATION, org, "creator", "base", null,
                KnowledgeBase.State.ACTIVE, 1, NOW, NOW);
    }
    private static void migrate(DriverManagerDataSource source) {
        Flyway.configure().dataSource(source).locations("classpath:db/platform-server", "classpath:db/platform-runtime").load().migrate();
    }
    private static void seed(JdbcTemplate jdbc, String org, String user) {
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',?,?)",
                org, org, org, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                user, org, user + "@test.local", user, Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
