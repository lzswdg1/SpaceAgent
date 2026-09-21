package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.api.KnowledgeCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspaceRepository;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspaceStorageGateway;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresKnowledgeCleanupService implements KnowledgeCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    private final DocumentWorkspaceRepository workspaces;
    private final DocumentWorkspaceStorageGateway storage;
    private com.spaceagent.platform.knowledge.domain.KnowledgeIndexDeletionRepository indexDeletion;
    @Autowired public void configureIndexDeletion(com.spaceagent.platform.knowledge.domain.KnowledgeIndexDeletionRepository deletion){this.indexDeletion=deletion;}

    public PostgresKnowledgeCleanupService(JdbcTemplate jdbc) {
        this(jdbc,null,null);
    }

    @Autowired public PostgresKnowledgeCleanupService(JdbcTemplate jdbc,DocumentWorkspaceRepository workspaces,DocumentWorkspaceStorageGateway storage){this.jdbc=jdbc;this.workspaces=workspaces;this.storage=storage;}

    @Override
    public CleanupResult cleanupUser(String userId) {
        if(indexDeletion!=null && !indexDeletion.scopeCleanup(userId,null))return new CleanupResult(true,"KNOWLEDGE_INDEX_CLEANUP_PENDING");
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform_knowledge_index_jobs j
                  JOIN platform_knowledge_index_generations g ON g.id=j.generation_id
                  JOIN platform_knowledge_documents d ON d.id=g.document_id WHERE d.owner_id=?)
                """,Boolean.class,userId))) return new CleanupResult(true,"KNOWLEDGE_INDEX_CLEANUP_PENDING");
        if(workspaces!=null&&!deleteBytes(jdbc.query("SELECT tenant_id,id::text FROM platform_document_workspaces WHERE scope_type='USER' AND owner_id=?",(r,n)->new Scope(r.getString(1),r.getString(2)),userId)))return CleanupResult.blockedResult();
        jdbc.update("DELETE FROM platform_knowledge_url_content_versions WHERE url_job_id IN(SELECT id FROM platform_knowledge_url_jobs WHERE owner_id=?)",userId);
        jdbc.update("DELETE FROM platform_knowledge_url_observations WHERE url_job_id IN(SELECT id FROM platform_knowledge_url_jobs WHERE owner_id=?)",userId);
        jdbc.update("DELETE FROM platform_knowledge_url_jobs WHERE owner_id=?",userId);
        jdbc.update("DELETE FROM platform_document_workspaces WHERE scope_type='USER' AND owner_id=?",userId);
        jdbc.update("DELETE FROM platform_knowledge_documents WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_knowledge_base_grants WHERE subject_type='USER' AND subject_id=?", userId);
        jdbc.update("DELETE FROM platform_knowledge_bases WHERE scope='PERSONAL' AND owner_id=?", userId);
        return CleanupResult.completed();
    }

    @Override public CleanupResult cleanupOrganization(String tenantId){
        if(indexDeletion!=null && !indexDeletion.scopeCleanup(null,tenantId))return new CleanupResult(true,"KNOWLEDGE_INDEX_CLEANUP_PENDING");
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform_knowledge_index_jobs j
                  JOIN platform_knowledge_bases b ON b.id=j.base_id WHERE b.organization_id=?)
                """,Boolean.class,tenantId))) return new CleanupResult(true,"KNOWLEDGE_INDEX_CLEANUP_PENDING");
        if(workspaces!=null&&!deleteBytes(jdbc.query("SELECT tenant_id,id::text FROM platform_document_workspaces WHERE tenant_id=? AND scope_type='ORGANIZATION'",(r,n)->new Scope(r.getString(1),r.getString(2)),tenantId)))return CleanupResult.blockedResult();
        jdbc.update("DELETE FROM platform_document_workspaces WHERE tenant_id=? AND scope_type='ORGANIZATION'",tenantId);
        jdbc.update("DELETE FROM platform_knowledge_bases WHERE organization_id=?", tenantId);
        return CleanupResult.completed();
    }

    private boolean deleteBytes(List<Scope> scopes){for(Scope scope:scopes){Long unknown=jdbc.queryForObject("SELECT count(*) FROM platform_document_workspace_operations WHERE workspace_id=CAST(? AS UUID) AND state IN('PENDING','UNKNOWN')",Long.class,scope.id());if(unknown!=null&&unknown>0)return false;try{var workspace=workspaces.findById(scope.tenant(),scope.id()).orElseThrow();storage.delete(workspace);}catch(RuntimeException error){return false;}}return true;}
    private record Scope(String tenant,String id){}
}
