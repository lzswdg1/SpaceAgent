package com.spaceagent.platform.artifact.infrastructure.persistence;

import com.spaceagent.platform.artifact.api.ArtifactCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.spaceagent.platform.artifact.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresArtifactCleanupService implements ArtifactCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    private final ArtifactObjectRepository objects;private final ArtifactObjectStorageGateway storage;
    public PostgresArtifactCleanupService(JdbcTemplate jdbc) { this(jdbc,null,null); }
    @Autowired public PostgresArtifactCleanupService(JdbcTemplate jdbc,ArtifactObjectRepository objects,ArtifactObjectStorageGateway storage){this.jdbc=jdbc;this.objects=objects;this.storage=storage;}
    @Override
    public void cleanupOrganization(String organizationId) {
        if(objects!=null)cleanupObjectBytes(organizationId);
        jdbc.update("DELETE FROM platform_artifact_object_deletions WHERE tenant_id=?",organizationId);
        jdbc.update("DELETE FROM platform_artifact_object_legal_holds WHERE tenant_id=?",organizationId);
        jdbc.update("DELETE FROM platform_artifact_object_references WHERE tenant_id=?",organizationId);
        jdbc.update("DELETE FROM platform_artifact_object_staging WHERE tenant_id=?",organizationId);
        jdbc.update("DELETE FROM platform_artifact_objects WHERE tenant_id=?",organizationId);
        jdbc.update("DELETE FROM platform_artifacts WHERE tenant_id = ?", organizationId);
    }

    @Override @Transactional
    public void cleanupRuns(List<String> agentRunIds) {
        if (agentRunIds == null) return;
        for (String runId : agentRunIds) {
            jdbc.update("DELETE FROM platform_artifacts WHERE agent_run_id = ?", runId);
        }
    }

    private void cleanupObjectBytes(String tenant){Long holds=jdbc.queryForObject("SELECT count(*) FROM platform_artifact_object_legal_holds WHERE tenant_id=? AND state='ACTIVE'",Long.class,tenant);Long claimed=jdbc.queryForObject("SELECT count(*) FROM platform_artifact_object_deletions WHERE tenant_id=? AND state='CLAIMED'",Long.class,tenant);if((holds!=null&&holds>0)||(claimed!=null&&claimed>0))throw blocked();try{for(var row:jdbc.query("SELECT id::text,COALESCE(staging_reference,'artifact-staging:'||id::text) ref FROM platform_artifact_object_staging WHERE tenant_id=? AND state<>'PUBLISHED'",(r,n)->new Ref(r.getString(1),r.getString(2)),tenant))storage.deleteStaging(new ArtifactObjectStorageGateway.DeleteStagingCommand(tenant,row.ref()));for(var row:jdbc.query("SELECT id::text,storage_reference FROM platform_artifact_objects WHERE tenant_id=? AND state<>'DELETED'",(r,n)->new Ref(r.getString(1),r.getString(2)),tenant))storage.deleteObject(new ArtifactObjectStorageGateway.DeleteObjectCommand(tenant,row.ref()));}catch(RuntimeException error){throw blocked();}}
    private static BusinessException blocked(){return new BusinessException("Artifact bytes deletion is blocked",HttpStatus.CONFLICT,"ARTIFACT_BYTES_DELETE_BLOCKED");}private record Ref(String id,String ref){}
}
