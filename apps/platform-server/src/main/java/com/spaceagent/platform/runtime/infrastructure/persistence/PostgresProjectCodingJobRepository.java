package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.domain.ProjectCodingJob;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobRepository;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresProjectCodingJobRepository implements ProjectCodingJobRepository {
    private static final String SELECT = "SELECT * FROM platform_project_coding_jobs";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public PostgresProjectCodingJobRepository(JdbcTemplate jdbc) {
        this.jdbc=jdbc; this.named=new NamedParameterJdbcTemplate(jdbc);
    }
    public void insert(ProjectCodingJob v) {
        named.update("""
            INSERT INTO platform_project_coding_jobs(
              id,tenant_id,owner_id,project_id,project_directory_id,conversation_id,
              source_repository_id,root_task_id,task_id,task_plan_id,execution_id,plan_step_id,agent_id,
              reviewer_agent_id,base_ref,idempotency_hash,input_hash,state,
              workspace_id,coding_run_id,reviewer_run_id,iteration,review_round,context_json,
              pending_tool_json,pending_approval_id,patch_artifact_id,commit_artifact_id,review_id,
              source_merge_id,safe_error_code,attempt,claim_owner,claim_token,fencing_token,
              lease_until,revision,created_at,started_at,updated_at,completed_at)
            VALUES(CAST(:id AS UUID),:tenant,:owner,CAST(:project AS UUID),CAST(:directory AS UUID),
              :conversation,CAST(:source AS UUID),CAST(:rootTask AS UUID),CAST(:task AS UUID),
              CAST(:plan AS UUID),CAST(:execution AS UUID),CAST(:step AS UUID),:agent,
              :reviewerAgent,:baseRef,:idempotency,:inputHash,:state,
              CAST(:workspace AS UUID),:codingRun,:reviewerRun,:iteration,:reviewRound,CAST(:context AS JSONB),
              CAST(:pending AS JSONB),CAST(:approval AS UUID),CAST(:patch AS UUID),CAST(:commit AS UUID),
              CAST(:review AS UUID),CAST(:merge AS UUID),:error,:attempt,:claimOwner,
              CAST(:claimToken AS UUID),:fence,:lease,:revision,:created,:started,:updated,:completed)
            """, params(v));
    }
    public Optional<ProjectCodingJob> findById(String id) {
        return jdbc.query(SELECT+" WHERE id=CAST(? AS UUID)",this::map,id).stream().findFirst();
    }
    @Transactional public Optional<ProjectCodingJob> findByIdForUpdate(String id) {
        return jdbc.query(SELECT+" WHERE id=CAST(? AS UUID) FOR UPDATE",this::map,id).stream().findFirst();
    }
    public Optional<ProjectCodingJob> findByIdempotency(String tenant,String owner,String hash) {
        return jdbc.query(SELECT+" WHERE tenant_id=? AND owner_id=? AND idempotency_hash=?",
                this::map,tenant,owner,hash).stream().findFirst();
    }
    public List<ProjectCodingJob> findActiveByTaskPlan(String plan,String owner) {
        return jdbc.query(SELECT+" WHERE task_plan_id=CAST(? AS UUID) AND owner_id=? "
                +"AND state IN ('PENDING','RUNNING','WAITING_APPROVAL') "
                +"ORDER BY created_at,id",this::map,plan,owner);
    }
    public List<ProjectCodingJob> findActiveByExecutionId(String execution,String owner) {
        return jdbc.query(SELECT+" WHERE execution_id=CAST(? AS UUID) AND owner_id=? "
                +"AND state IN ('PENDING','RUNNING','WAITING_APPROVAL') "
                +"ORDER BY created_at,id",this::map,execution,owner);
    }
    public List<ProjectCodingJob> findByPlanStep(String step,String owner,int offset,int limit) {
        return jdbc.query(SELECT+" WHERE plan_step_id=CAST(? AS UUID) AND owner_id=? "
                +"ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",this::map,step,owner,limit,offset);
    }
    public long countByPlanStep(String step,String owner) {
        Long n=jdbc.queryForObject("SELECT count(*) FROM platform_project_coding_jobs "
                +"WHERE plan_step_id=CAST(? AS UUID) AND owner_id=?",Long.class,step,owner);
        return n==null?0:n;
    }
    @Transactional public Optional<ProjectCodingJob> claim(
            String owner,String token,Instant now,Instant until,int maximumAttempts) {
        jdbc.update("""
            UPDATE platform_project_coding_jobs SET state='FAILED',
              safe_error_code='PROJECT_CODING_ATTEMPTS_EXHAUSTED',claim_owner=NULL,
              claim_token=NULL,lease_until=NULL,revision=revision+1,updated_at=?,completed_at=?
            WHERE state='RUNNING' AND lease_until<=? AND attempt>=?
            """,ts(now),ts(now),ts(now),maximumAttempts);
        List<String> ids=jdbc.query("""
            WITH candidate AS (SELECT id FROM platform_project_coding_jobs
              WHERE (state='PENDING' OR (state='RUNNING' AND lease_until<=?)) AND attempt<?
              ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
            UPDATE platform_project_coding_jobs job SET state='RUNNING',claim_owner=?,
              claim_token=CAST(? AS UUID),lease_until=?,attempt=attempt+1,
              fencing_token=fencing_token+1,revision=revision+1,
              started_at=COALESCE(started_at,?),updated_at=?
            FROM candidate WHERE job.id=candidate.id RETURNING job.id::text
            """,(r,n)->r.getString(1),ts(now),maximumAttempts,owner,token,ts(until),ts(now),ts(now));
        return ids.isEmpty()?Optional.empty():findById(ids.getFirst());
    }
    public boolean saveClaimed(ProjectCodingJob expected,ProjectCodingJob updated,Instant now) {
        var p=params(updated).addValue("expectedRevision",expected.revision())
                .addValue("expectedToken",expected.claimToken()).addValue("now",ts(now));
        return named.update(updateSql()+" WHERE id=CAST(:id AS UUID) AND revision=:expectedRevision "
                +"AND state='RUNNING' AND claim_token=CAST(:expectedToken AS UUID) "
                +"AND fencing_token=:fence AND lease_until>:now",p)==1;
    }
    public void saveLifecycle(ProjectCodingJob expected,ProjectCodingJob updated) {
        var p=params(updated).addValue("expectedRevision",expected.revision());
        if(named.update(updateSql()+" WHERE id=CAST(:id AS UUID) AND revision=:expectedRevision",p)!=1)
            throw new IllegalStateException("Project Coding Job revision conflict");
    }
    private static String updateSql(){return """
        UPDATE platform_project_coding_jobs SET state=:state,workspace_id=CAST(:workspace AS UUID),
          coding_run_id=:codingRun,reviewer_run_id=:reviewerRun,iteration=:iteration,
          review_round=:reviewRound,context_json=CAST(:context AS JSONB),
          pending_tool_json=CAST(:pending AS JSONB),
          pending_approval_id=CAST(:approval AS UUID),patch_artifact_id=CAST(:patch AS UUID),
          commit_artifact_id=CAST(:commit AS UUID),review_id=CAST(:review AS UUID),
          source_merge_id=CAST(:merge AS UUID),safe_error_code=:error,attempt=:attempt,
          claim_owner=:claimOwner,claim_token=CAST(:claimToken AS UUID),fencing_token=:fence,
          lease_until=:lease,revision=:revision,started_at=:started,updated_at=:updated,
          completed_at=:completed
        """;}
    private MapSqlParameterSource params(ProjectCodingJob v){return new MapSqlParameterSource()
      .addValue("id",v.id()).addValue("tenant",v.tenantId()).addValue("owner",v.ownerId())
      .addValue("project",v.projectId()).addValue("directory",v.projectDirectoryId())
      .addValue("conversation",v.conversationId()).addValue("source",v.sourceRepositoryId())
      .addValue("rootTask",v.rootTaskId()).addValue("task",v.taskId()).addValue("plan",v.taskPlanId())
      .addValue("execution",v.executionId())
      .addValue("step",v.planStepId()).addValue("agent",v.agentId())
      .addValue("reviewerAgent",v.reviewerAgentId()).addValue("baseRef",v.baseRef())
      .addValue("idempotency",v.idempotencyHash()).addValue("inputHash",v.inputHash())
      .addValue("state",v.state().name()).addValue("workspace",v.workspaceId())
      .addValue("codingRun",v.codingRunId()).addValue("reviewerRun",v.reviewerRunId())
      .addValue("iteration",v.iteration()).addValue("reviewRound",v.reviewRound())
      .addValue("context",v.contextJson()).addValue("pending",v.pendingToolJson())
      .addValue("approval",v.pendingApprovalId()).addValue("patch",v.patchArtifactId())
      .addValue("commit",v.commitArtifactId()).addValue("review",v.reviewId())
      .addValue("merge",v.sourceMergeId()).addValue("error",v.safeErrorCode())
      .addValue("attempt",v.attempt()).addValue("claimOwner",v.claimOwner())
      .addValue("claimToken",v.claimToken()).addValue("fence",v.fencingToken())
      .addValue("lease",ts(v.leaseUntil())).addValue("revision",v.revision())
      .addValue("created",ts(v.createdAt())).addValue("started",ts(v.startedAt()))
      .addValue("updated",ts(v.updatedAt())).addValue("completed",ts(v.completedAt()));}
    private ProjectCodingJob map(ResultSet r,int n)throws SQLException{return new ProjectCodingJob(
      r.getString("id"),r.getString("tenant_id"),r.getString("owner_id"),r.getString("project_id"),
      r.getString("project_directory_id"),r.getString("conversation_id"),r.getString("source_repository_id"),
      r.getString("root_task_id"),r.getString("task_id"),r.getString("task_plan_id"),
      r.getString("execution_id"),r.getString("plan_step_id"),
      r.getString("agent_id"),null,null,
      r.getString("base_ref"),r.getString("idempotency_hash"),r.getString("input_hash"),
      ProjectCodingJobState.valueOf(r.getString("state")),r.getString("workspace_id"),
      r.getString("coding_run_id"),r.getString("reviewer_run_id"),r.getInt("iteration"),
      r.getInt("review_round"),r.getString("context_json"),r.getString("pending_tool_json"),
      r.getString("pending_approval_id"),r.getString("patch_artifact_id"),r.getString("commit_artifact_id"),
      r.getString("review_id"),r.getString("source_merge_id"),r.getString("safe_error_code"),
      r.getInt("attempt"),r.getString("claim_owner"),r.getString("claim_token"),r.getLong("fencing_token"),
      instant(r.getTimestamp("lease_until")),r.getLong("revision"),r.getTimestamp("created_at").toInstant(),
      instant(r.getTimestamp("started_at")),r.getTimestamp("updated_at").toInstant(),
      instant(r.getTimestamp("completed_at")),r.getString("reviewer_agent_id"));}
    private static Timestamp ts(Instant v){return v==null?null:Timestamp.from(v);}
    private static Instant instant(Timestamp v){return v==null?null:v.toInstant();}
}
