package com.spaceagent.platform.project.api;

import java.util.Optional;

/**
 * Read-only authorization boundary for Project Memory access and task ancestry.
 *
 * <p>Implementations must resolve access from authoritative project state. Callers
 * must not infer project ownership from conversations, runtime rows, or caller-supplied
 * project identifiers.
 */
public interface ProjectOwnershipPort {

    /** Returns true only for active OWNER, ADMIN, or MEMBER access; VIEWER is read-only. */
    boolean isOwnerOrMember(String projectId, String principalId);

    Optional<String> findProjectIdByTask(String taskId);

    /** Canonical tenant ancestry; never infer it from a request's claimed scope. */
    Optional<String> findTenantIdByProject(String projectId);
}
