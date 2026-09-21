package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.application.ProjectCodingJobApplicationService;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectCodingJobRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformProjectCodingJobApplicationTest {
    private static final Instant NOW=Instant.parse("2026-09-06T07:00:00Z");
    @Test void lifecycleIsIdempotentFencedAndApprovalResumable(){
        var service=new ProjectCodingJobApplicationService(new InMemoryProjectCodingJobRepository(),
                new UuidGenerator(),()->NOW,new ObjectMapper());
        var command=command("coding-idempotency-001");var queued=service.enqueue(command);
        assertThat(service.enqueue(command).id()).isEqualTo(queued.id());
        var claim=service.claim("worker",300,3).orElseThrow();var lease=lease(claim);
        var workspace=service.attachWorkspace(lease,UUID.randomUUID().toString());
        var run=service.attachCodingRun(lease,"run-1");
        service.initializeContext(lease,"{\"messages\":[]}");
        service.savePendingTool(lease,"{\"id\":\"call-1\",\"name\":\"coding_write_file\",\"arguments\":\"{}\"}");
        String approval=UUID.randomUUID().toString();
        var waiting=service.waitForApproval(lease,approval);
        assertThat(waiting.state()).isEqualTo(ProjectCodingJobState.WAITING_APPROVAL);
        assertThatThrownBy(()->service.resume(new ProjectCodingJobApplicationApi.ResumeCommand(
                "tenant","user",command.projectId(),command.taskPlanId(),command.planStepId(),
                queued.id(),UUID.randomUUID().toString())))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode())
                        .isEqualTo("PROJECT_CODING_APPROVAL_MISMATCH"));
        assertThat(service.resume(new ProjectCodingJobApplicationApi.ResumeCommand(
                "tenant","user",command.projectId(),command.taskPlanId(),command.planStepId(),
                queued.id(),approval)).state()).isEqualTo(ProjectCodingJobState.PENDING);
        var reclaimed=service.claim("worker-2",300,3).orElseThrow();
        assertThat(reclaimed.fencingToken()).isEqualTo(2);
        assertThatThrownBy(()->service.heartbeat(lease,300))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode())
                        .isEqualTo("PROJECT_CODING_LEASE_LOST"));
        var active=lease(reclaimed);
        service.recordToolResult(active,"{\"messages\":[]}",1);
        String patch=UUID.randomUUID().toString(),commit=UUID.randomUUID().toString();
        service.recordPrepared(active,patch,commit);
        service.attachReviewerRun(active,"reviewer-run");
        service.recordReview(active,UUID.randomUUID().toString(),true,null,1);
        var complete=service.complete(active,UUID.randomUUID().toString());
        assertThat(complete.state()).isEqualTo(ProjectCodingJobState.COMPLETED);
        assertThat(complete.workspaceId()).isEqualTo(workspace.workspaceId());
        assertThat(complete.codingRunId()).isEqualTo(run.codingRunId());
    }
    @Test void pausedJobCanBeTerminallyHandedOffWithoutAnActiveLease(){
        var service=new ProjectCodingJobApplicationService(new InMemoryProjectCodingJobRepository(),
                new UuidGenerator(),()->NOW,new ObjectMapper());
        var command=command("coding-handoff-001");var queued=service.enqueue(command);
        assertThatThrownBy(()->service.handoff(new ProjectCodingJobApplicationApi.HandoffCommand(
                "tenant","user",command.projectId(),command.taskPlanId(),command.planStepId(),queued.id())))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode())
                        .isEqualTo("PROJECT_CODING_HANDOFF_STATE_INVALID"));
        var claim=service.claim("worker",300,3).orElseThrow();var lease=lease(claim);
        service.attachWorkspace(lease,uuid());service.attachCodingRun(lease,"run-1");
        service.savePendingTool(lease,"{\"id\":\"call-1\",\"name\":\"coding_write_file\",\"arguments\":\"{}\"}");
        service.waitForApproval(lease,uuid());
        var handedOff=service.handoff(new ProjectCodingJobApplicationApi.HandoffCommand(
                "tenant","user",command.projectId(),command.taskPlanId(),command.planStepId(),queued.id()));
        assertThat(handedOff.state()).isEqualTo(ProjectCodingJobState.HANDED_OFF);
        assertThat(handedOff.completedAt()).isEqualTo(NOW);
        assertThat(service.claim("other",300,3)).isEmpty();
    }
    private static ProjectCodingJobApplicationApi.EnqueueCommand command(String key){return new ProjectCodingJobApplicationApi.EnqueueCommand(
            "tenant","user",uuid(),uuid(),uuid(),uuid(),uuid(),uuid(),uuid(),null,uuid(),uuid(),uuid(),uuid(),"main",key);}
    private static ProjectCodingJobApplicationApi.ClaimCommand lease(ProjectCodingJobApplicationApi.ClaimView v){return new ProjectCodingJobApplicationApi.ClaimCommand(v.job().id(),v.job().state()==ProjectCodingJobState.RUNNING&&v.fencingToken()==1?"worker":"worker-2",v.claimToken(),v.fencingToken());}
    private static String uuid(){return UUID.randomUUID().toString();}
}
