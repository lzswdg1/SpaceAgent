package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentValidationApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignment;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignmentRepository;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignmentSource;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/** Linearizes immutable per-step assignment evidence and validates before persistence. */
@Service
public class ProjectPlanStepAssignmentApplicationService implements ProjectPlanStepAssignmentApplicationApi {
    private final ProjectPlanStepAssignmentRepository repository;
    private final ProjectPlanStepAssignmentValidationApplicationApi validation;
    private final IdGenerator ids;
    private final Clock clock;

    @Autowired
    public ProjectPlanStepAssignmentApplicationService(ProjectPlanStepAssignmentRepository repository,
            ProjectPlanStepAssignmentValidationApplicationApi validation, IdGenerator ids) {
        this(repository, validation, ids, Clock.systemUTC());
    }

    public ProjectPlanStepAssignmentApplicationService(ProjectPlanStepAssignmentRepository repository,
            ProjectPlanStepAssignmentValidationApplicationApi validation, IdGenerator ids, Clock clock) {
        this.repository = repository; this.validation = validation; this.ids = ids; this.clock = clock;
    }

    @Override @Transactional public AssignmentView definePlanDefault(PlanDefaultAssignmentCommand c) {
        return materialize(c.tenantId(), c.ownerId(), c.projectId(), c.taskPlanId(), c.planStepId(),
                ProjectPlanStepAssignmentSource.PLAN_DEFAULT, c.agentId(), c.primaryConfigurationHash(),
                c.reviewerAgentId(), c.reviewerConfigurationHash(), c.modelPoolId(), c.capabilityHash(), c.configurationHash(), false);
    }
    @Override @Transactional public AssignmentView defineStepOverride(StepOverrideAssignmentCommand c) {
        return materialize(c.tenantId(), c.ownerId(), c.projectId(), c.taskPlanId(), c.planStepId(),
                ProjectPlanStepAssignmentSource.STEP_OVERRIDE, c.agentId(), c.primaryConfigurationHash(),
                c.reviewerAgentId(), c.reviewerConfigurationHash(), c.modelPoolId(), c.capabilityHash(), c.configurationHash(), false);
    }
    @Override @Transactional public AssignmentView handoff(HandoffAssignmentCommand c) {
        return materialize(c.tenantId(), c.ownerId(), c.projectId(), c.taskPlanId(), c.planStepId(),
                ProjectPlanStepAssignmentSource.HANDOFF, c.agentId(), c.primaryConfigurationHash(),
                c.reviewerAgentId(), c.reviewerConfigurationHash(), c.modelPoolId(), c.capabilityHash(), c.configurationHash(), true);
    }
    @Override public AssignmentView get(AssignmentQuery q) {
        return repository.findLatest(q.tenantId(), q.ownerId(), q.projectId(), q.taskPlanId(), q.planStepId()).map(this::view).orElseThrow();
    }
    private AssignmentView materialize(String tenant, String owner, String project, String plan, String step,
            ProjectPlanStepAssignmentSource source, String agent, String version, String reviewer, String reviewerVersion,
            String pool, String capability, String config, boolean supersede) {
        var resolved = validation.resolve(
                new ProjectPlanStepAssignmentValidationApplicationApi.ResolveEvidenceCommand(
                        tenant, owner, agent, version, reviewer, reviewerVersion, pool));
        if ((capability != null && !capability.equals(resolved.capabilityHash()))
                || (config != null && !config.equals(resolved.configurationHash()))) {
            throw new BusinessException("Assignment evidence is stale", HttpStatus.CONFLICT,
                    "ASSIGNMENT_REQUIRED");
        }
        capability = resolved.capabilityHash();
        config = resolved.configurationHash();
        var prior = repository.findLatestForUpdate(tenant, owner, project, plan, step).orElse(null);
        if (!supersede && prior != null && prior.source() == source && prior.agentId().equals(agent)
                && Objects.equals(prior.primaryConfigurationHash(), version) && prior.reviewerAgentId().equals(reviewer)
                && Objects.equals(prior.reviewerConfigurationHash(), reviewerVersion) && Objects.equals(prior.modelPoolId(),pool)
                && prior.capabilityHash().equals(capability) && prior.configurationHash().equals(config)) return view(prior);
        var value = ProjectPlanStepAssignment.fromRevision(ids.nextId(), tenant, owner, project, plan, step,
                prior == null ? 1 : prior.revision() + 1, source, agent, version, reviewer, reviewerVersion,
                pool, capability, config, clock.instant());
        repository.insert(value); return view(value);
    }
    private AssignmentView view(ProjectPlanStepAssignment value) {
        return new AssignmentView(value.id(), value.taskPlanId(), value.planStepId(), value.revision(), value.source(),
                value.agentId(), value.primaryConfigurationHash(), value.reviewerAgentId(), value.reviewerConfigurationHash(),
                value.modelPoolId(), value.capabilityHash(), value.configurationHash(), value.assignmentHash(), value.assignedAt());
    }
}
