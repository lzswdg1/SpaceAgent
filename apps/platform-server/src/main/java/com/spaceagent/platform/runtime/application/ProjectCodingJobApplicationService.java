package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectCodingJob;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobRepository;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrier;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierEntry;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectCodingJobApplicationService implements ProjectCodingJobApplicationApi {
    private static final int MAX_CONTEXT_BYTES=1_000_000;
    private final ProjectCodingJobRepository jobs; private final IdGenerator ids;
    private final TimeProvider time; private final ObjectMapper json;
    private ProjectPlanMergeBarrierRepository mergeBarriers;
    public ProjectCodingJobApplicationService(ProjectCodingJobRepository jobs,IdGenerator ids,
                                               TimeProvider time,ObjectMapper json){
        this.jobs=jobs;this.ids=ids;this.time=time;this.json=json;
    }
    @Autowired(required = false)
    public void setMergeBarrierRepository(ProjectPlanMergeBarrierRepository mergeBarriers) {
        this.mergeBarriers = mergeBarriers;
    }
    @Transactional public JobView enqueue(EnqueueCommand c){
        String key=text(c.idempotencyKey(),"idempotencyKey",200);if(key.length()<8)throw invalid("Idempotency-Key is too short");
        String idempotency=hash(key);String input=hash(String.join("\n",values(c)));
        var existing=jobs.findByIdempotency(c.tenantId(),c.ownerId(),idempotency).orElse(null);
        if(existing!=null){if(!existing.inputHash().equals(input))throw conflict("PROJECT_CODING_IDEMPOTENCY_CONFLICT");return view(existing);}
        Instant now=time.now();var created=new ProjectCodingJob(ids.nextId(),text(c.tenantId(),"tenantId",36),
                text(c.ownerId(),"ownerId",36),uuid(c.projectId()),uuid(c.projectDirectoryId()),
                text(c.conversationId(),"conversationId",36),uuid(c.sourceRepositoryId()),uuid(c.rootTaskId()),
                uuid(c.taskId()),uuid(c.taskPlanId()),optionalUuid(c.executionId()),uuid(c.planStepId()),
                text(c.agentId(),"agentId",36),
                optionalText(c.primaryConfigurationHash()),optionalText(c.reviewerConfigurationHash()),text(c.baseRef(),"baseRef",240),
                idempotency,input,ProjectCodingJobState.PENDING,null,null,null,0,0,null,null,null,
                null,null,null,null,null,0,null,null,0,null,1,now,null,now,null,
                c.reviewerAgentId());
        try{jobs.insert(created);return view(created);}catch(DataIntegrityViolationException|IllegalStateException e){
            var winner=jobs.findByIdempotency(c.tenantId(),c.ownerId(),idempotency).orElse(null);
            if(winner!=null&&winner.inputHash().equals(input))return view(winner);throw conflict("PROJECT_CODING_ACTIVE");}
    }
    public JobView get(Query q){return view(require(q.tenantId(),q.ownerId(),q.projectId(),q.taskPlanId(),q.planStepId(),q.jobId(),false));}
    public JobPage list(ListQuery q){uuid(q.projectId());uuid(q.taskPlanId());uuid(q.planStepId());int page=Math.max(1,q.page()),size=Math.max(1,Math.min(100,q.pageSize()));
        var items=jobs.findByPlanStep(q.planStepId(),q.ownerId(),(page-1)*size,size).stream()
                .filter(v->v.tenantId().equals(q.tenantId())&&v.projectId().equals(q.projectId())&&v.taskPlanId().equals(q.taskPlanId())).map(this::view).toList();
        return new JobPage(items,page,size,jobs.countByPlanStep(q.planStepId(),q.ownerId()));}
    public Optional<ClaimView> claim(String worker,int leaseSeconds,int maximumAttempts){Instant now=time.now();
        return jobs.claim(text(worker,"workerId",160),ids.nextId(),now,now.plus(Math.max(60,Math.min(1800,leaseSeconds)),ChronoUnit.SECONDS),Math.max(1,Math.min(10,maximumAttempts)))
                .map(v->new ClaimView(view(v),v.claimToken(),v.fencingToken(),v.leaseUntil(),v.contextJson(),v.pendingToolJson()));}
    public JobView heartbeat(ClaimCommand c,int seconds){return mutate(c,v->copy(v,v.state(),v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),null,v.attempt(),v.claimOwner(),v.claimToken(),v.fencingToken(),time.now().plus(Math.max(60,Math.min(1800,seconds)),ChronoUnit.SECONDS),v.revision()+1,v.startedAt(),time.now(),null));}
    public JobView releaseForPause(ClaimCommand c){var v=claim(c);var n=copy(v,ProjectCodingJobState.PENDING,v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),v.safeErrorCode(),v.attempt(),null,null,v.fencingToken(),null,v.revision()+1,v.startedAt(),time.now(),null);if(!jobs.saveClaimed(v,n,time.now()))throw conflict("PROJECT_CODING_LEASE_LOST");return view(n);}
    public JobView attachWorkspace(ClaimCommand c,String id){return mutate(c,v->copy(v,v.state(),uuid(id),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),null,v.attempt(),v.claimOwner(),v.claimToken(),v.fencingToken(),v.leaseUntil(),v.revision()+1,v.startedAt(),time.now(),null));}
    public JobView attachCodingRun(ClaimCommand c,String id){return mutate(c,v->copy(v,v.state(),v.workspaceId(),text(id,"codingRunId",36),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),null,v.attempt(),v.claimOwner(),v.claimToken(),v.fencingToken(),v.leaseUntil(),v.revision()+1,v.startedAt(),time.now(),null));}
    public JobView initializeContext(ClaimCommand c,String context){payload(context,"context");return mutate(c,v->{if(v.contextJson()!=null)return v;return copy(v,v.state(),v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),context,v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),null,v.attempt(),v.claimOwner(),v.claimToken(),v.fencingToken(),v.leaseUntil(),v.revision()+1,v.startedAt(),time.now(),null);});}
    public JobView savePendingTool(ClaimCommand c,String pending){payload(pending,"pendingTool");return mutate(c,v->copy(v,v.state(),v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),pending,null,v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),null,v.attempt(),v.claimOwner(),v.claimToken(),v.fencingToken(),v.leaseUntil(),v.revision()+1,v.startedAt(),time.now(),null));}
    public JobView recordToolResult(ClaimCommand c,String context,int iteration){payload(context,"context");return mutate(c,v->copy(v,v.state(),v.workspaceId(),v.codingRunId(),v.reviewerRunId(),iteration,v.reviewRound(),context,null,null,v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),null,v.attempt(),v.claimOwner(),v.claimToken(),v.fencingToken(),v.leaseUntil(),v.revision()+1,v.startedAt(),time.now(),null));}
    public JobView waitForApproval(ClaimCommand c,String approval){return mutate(c,v->copy(v,ProjectCodingJobState.WAITING_APPROVAL,v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),uuid(approval),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),null,v.attempt(),null,null,v.fencingToken(),null,v.revision()+1,v.startedAt(),time.now(),null));}
    @Transactional public JobView resume(ResumeCommand c){var v=require(c.tenantId(),c.ownerId(),c.projectId(),c.taskPlanId(),c.planStepId(),c.jobId(),true);if(v.state()!=ProjectCodingJobState.WAITING_APPROVAL||!java.util.Objects.equals(v.pendingApprovalId(),c.approvalId()))throw conflict("PROJECT_CODING_APPROVAL_MISMATCH");var n=copy(v,ProjectCodingJobState.PENDING,v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),null,v.attempt(),null,null,v.fencingToken(),null,v.revision()+1,v.startedAt(),time.now(),null);jobs.saveLifecycle(v,n);return view(n);}
    @Transactional public JobView handoff(HandoffCommand c){var v=require(c.tenantId(),c.ownerId(),c.projectId(),c.taskPlanId(),c.planStepId(),c.jobId(),true);if(v.state()!=ProjectCodingJobState.WAITING_APPROVAL&&v.state()!=ProjectCodingJobState.BLOCKED)throw conflict("PROJECT_CODING_HANDOFF_STATE_INVALID");Instant now=time.now();var n=copy(v,ProjectCodingJobState.HANDED_OFF,v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),v.safeErrorCode(),v.attempt(),null,null,v.fencingToken(),null,v.revision()+1,v.startedAt(),now,v.completedAt()==null?now:v.completedAt());jobs.saveLifecycle(v,n);return view(n);}
    @Transactional public JobView cancel(CancelCommand c){var v=require(c.tenantId(),c.ownerId(),c.projectId(),c.taskPlanId(),c.planStepId(),c.jobId(),true);text(c.reason(),"reason",240);if(c.expectedRevision()<1||v.revision()!=c.expectedRevision())throw conflict("PROJECT_CODING_STALE_REVISION");if(v.state()==ProjectCodingJobState.FAILED&&"PROJECT_PLAN_EXECUTION_CANCELLED".equals(v.safeErrorCode()))return view(v);if(v.sourceMergeId()!=null)throw conflict("PROJECT_CODING_CANCEL_MERGE_PROVEN");Instant now=time.now();boolean expired=v.state()==ProjectCodingJobState.RUNNING&&(v.leaseUntil()==null||!v.leaseUntil().isAfter(now));if(v.state()!=ProjectCodingJobState.PENDING&&v.state()!=ProjectCodingJobState.WAITING_APPROVAL&&!expired)throw conflict(v.state()==ProjectCodingJobState.RUNNING?"PROJECT_CODING_CANCEL_LEASE_ACTIVE":"PROJECT_CODING_CANCEL_STATE_INVALID");var n=copy(v,ProjectCodingJobState.FAILED,v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),"PROJECT_PLAN_EXECUTION_CANCELLED",v.attempt(),null,null,v.fencingToken(),null,v.revision()+1,v.startedAt(),now,now);jobs.saveLifecycle(v,n);return view(n);}
    public JobView recordPrepared(ClaimCommand c,String patch,String commit){return mutate(c,v->copy(v,v.state(),v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),null,null,uuid(patch),uuid(commit),v.reviewId(),v.sourceMergeId(),null,v.attempt(),v.claimOwner(),v.claimToken(),v.fencingToken(),v.leaseUntil(),v.revision()+1,v.startedAt(),time.now(),null));}
    public JobView attachReviewerRun(ClaimCommand c,String reviewer){return mutate(c,v->copy(v,v.state(),v.workspaceId(),v.codingRunId(),text(reviewer,"reviewerRunId",36),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),null,v.attempt(),v.claimOwner(),v.claimToken(),v.fencingToken(),v.leaseUntil(),v.revision()+1,v.startedAt(),time.now(),null));}
    public JobView recordReview(ClaimCommand c,String review,boolean approved,String context,int round){if(context!=null)payload(context,"context");return mutate(c,v->copy(v,v.state(),v.workspaceId(),v.codingRunId(),approved?v.reviewerRunId():null,v.iteration(),round,context==null?v.contextJson():context,null,null,approved?v.patchArtifactId():null,approved?v.commitArtifactId():null,uuid(review),v.sourceMergeId(),null,v.attempt(),v.claimOwner(),v.claimToken(),v.fencingToken(),v.leaseUntil(),v.revision()+1,v.startedAt(),time.now(),null));}
    public JobView complete(ClaimCommand c,String merge){return terminal(c,ProjectCodingJobState.COMPLETED,null,uuid(merge));}
    @Transactional
    public JobView completeAndEnqueueBarrier(BarrierCompletionCommand command) {
        if (mergeBarriers == null) throw conflict("PROJECT_MERGE_BARRIER_UNAVAILABLE");
        if (command == null || command.claim() == null || command.applyIndex() < 0) {
            throw invalid("merge barrier completion is invalid");
        }
        String sourceMergeId = uuid(command.sourceMergeId());
        ProjectCodingJob current = claim(command.claim());
        if (current.executionId() == null) throw conflict("PROJECT_MERGE_BARRIER_EXECUTION_MISSING");
        Instant now = time.now();
        mergeBarriers.createIfAbsent(new ProjectPlanMergeBarrier(
                current.executionId(), current.tenantId(), current.ownerId(), current.projectId(),
                current.taskPlanId(), 0, ProjectPlanMergeBarrier.State.ACTIVE, 1,
                now, now, null));
        if (!mergeBarriers.append(new ProjectPlanMergeBarrierEntry(
                current.executionId(), command.applyIndex(), current.planStepId(), sourceMergeId,
                ProjectPlanMergeBarrierEntry.State.READY, 1, now, now, null))) {
            throw conflict("PROJECT_MERGE_BARRIER_CONFLICT");
        }
        ProjectCodingJob completed = copy(current, ProjectCodingJobState.COMPLETED,
                current.workspaceId(), current.codingRunId(), current.reviewerRunId(),
                current.iteration(), current.reviewRound(), current.contextJson(),
                current.pendingToolJson(), current.pendingApprovalId(), current.patchArtifactId(),
                current.commitArtifactId(), current.reviewId(), sourceMergeId, null,
                current.attempt(), null, null, current.fencingToken(), null,
                current.revision() + 1, current.startedAt(), now, now);
        if (!jobs.saveClaimed(current, completed, now)) {
            throw conflict("PROJECT_CODING_LEASE_LOST");
        }
        return view(completed);
    }
    public JobView fail(FailCommand c){return terminal(new ClaimCommand(c.jobId(),c.workerId(),c.claimToken(),c.fencingToken()),c.blocked()?ProjectCodingJobState.BLOCKED:ProjectCodingJobState.FAILED,safe(c.safeErrorCode()),null);}
    private JobView terminal(ClaimCommand c,ProjectCodingJobState state,String error,String merge){return mutate(c,v->copy(v,state,v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),v.contextJson(),v.pendingToolJson(),v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),merge==null?v.sourceMergeId():merge,error,v.attempt(),null,null,v.fencingToken(),null,v.revision()+1,v.startedAt(),time.now(),time.now()));}
    private JobView mutate(ClaimCommand c,java.util.function.Function<ProjectCodingJob,ProjectCodingJob> f){var v=claim(c);var n=f.apply(v);if(n==v)return view(v);if(!jobs.saveClaimed(v,n,time.now()))throw conflict("PROJECT_CODING_LEASE_LOST");return view(n);}
    private ProjectCodingJob claim(ClaimCommand c){var v=jobs.findById(uuid(c.jobId())).orElseThrow(()->missing());if(v.state()!=ProjectCodingJobState.RUNNING||!java.util.Objects.equals(v.claimOwner(),c.workerId())||!java.util.Objects.equals(v.claimToken(),c.claimToken())||v.fencingToken()!=c.fencingToken()||v.leaseUntil()==null||!v.leaseUntil().isAfter(time.now()))throw conflict("PROJECT_CODING_LEASE_LOST");return v;}
    private ProjectCodingJob require(String tenant,String owner,String project,String plan,String step,String id,boolean lock){uuid(project);uuid(plan);uuid(step);var value=(lock?jobs.findByIdForUpdate(uuid(id)):jobs.findById(uuid(id))).filter(v->v.tenantId().equals(tenant)&&v.ownerId().equals(owner)&&v.projectId().equals(project)&&v.taskPlanId().equals(plan)&&v.planStepId().equals(step)).orElseThrow(()->missing());return value;}
    private JobView view(ProjectCodingJob v){String name=null;try{if(v.pendingToolJson()!=null)name=json.readTree(v.pendingToolJson()).path("name").asText(null);}catch(Exception ignored){}return new JobView(v.id(),v.tenantId(),v.ownerId(),v.projectId(),v.projectDirectoryId(),v.conversationId(),v.sourceRepositoryId(),v.rootTaskId(),v.taskId(),v.taskPlanId(),v.executionId(),v.planStepId(),v.agentId(),v.primaryConfigurationHash(),v.reviewerConfigurationHash(),v.baseRef(),v.state(),v.workspaceId(),v.codingRunId(),v.reviewerRunId(),v.iteration(),v.reviewRound(),name,v.pendingApprovalId(),v.patchArtifactId(),v.commitArtifactId(),v.reviewId(),v.sourceMergeId(),v.safeErrorCode(),v.attempt(),v.revision(),v.createdAt(),v.startedAt(),v.updatedAt(),v.completedAt(),v.reviewerAgentId());}
    private void payload(String value,String name){if(value==null||value.getBytes(StandardCharsets.UTF_8).length>MAX_CONTEXT_BYTES)throw invalid(name+" exceeds limit");try{if(!json.readTree(value).isContainerNode())throw new Exception();}catch(Exception e){throw invalid(name+" is invalid JSON");}}
    private static List<String> values(EnqueueCommand c){return java.util.Arrays.asList(c.tenantId(),c.ownerId(),c.projectId(),c.projectDirectoryId(),c.conversationId(),c.sourceRepositoryId(),c.rootTaskId(),c.taskId(),c.taskPlanId(),c.executionId(),c.planStepId(),c.agentId(),c.primaryConfigurationHash(),c.reviewerAgentId(),c.reviewerConfigurationHash(),c.baseRef()).stream().map(v->v==null?"":v).toList();}
    private static String text(String v,String name,int max){if(v==null||v.isBlank()||v.trim().length()>max)throw invalid(name+" is invalid");return v.trim();}
    private static String uuid(String v){try{return UUID.fromString(v).toString();}catch(Exception e){throw missing();}}
    private static String optionalUuid(String v){return v==null?null:uuid(v);}
    private static String optionalText(String v){return v==null||v.isBlank()?null:v.trim();}
    private static String safe(String v){return v!=null&&v.matches("[A-Z0-9_]{1,120}")?v:"PROJECT_CODING_FAILED";}
    private static String hash(String v){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static BusinessException invalid(String m){return new BusinessException(m,HttpStatus.BAD_REQUEST,"PROJECT_CODING_INVALID");}
    private static BusinessException conflict(String c){return new BusinessException("Project Coding Job conflict",HttpStatus.CONFLICT,c);}
    private static BusinessException missing(){return new BusinessException("Project Coding Job not found",HttpStatus.NOT_FOUND,"PROJECT_CODING_NOT_FOUND");}
    private static ProjectCodingJob copy(
            ProjectCodingJob v, ProjectCodingJobState state, String workspace, String run,
            String reviewerRun, int iteration, int reviewRound, String context, String pending,
            String approval, String patch, String commit, String review, String merge, String error,
            int attempt, String claimOwner, String claimToken, long fence, Instant lease,
            long revision, Instant started, Instant updated, Instant completed) {
        return new ProjectCodingJob(v.id(),v.tenantId(),v.ownerId(),v.projectId(),v.projectDirectoryId(),
                v.conversationId(),v.sourceRepositoryId(),v.rootTaskId(),v.taskId(),v.taskPlanId(),v.executionId(),
                v.planStepId(),v.agentId(),v.primaryConfigurationHash(),v.reviewerConfigurationHash(),v.baseRef(),
                v.idempotencyHash(),v.inputHash(),state,workspace,run,reviewerRun,iteration,reviewRound,
                context,pending,approval,patch,commit,review,merge,error,attempt,claimOwner,claimToken,
                fence,lease,revision,v.createdAt(),started,updated,completed,v.reviewerAgentId());
    }
}
