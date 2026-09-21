package com.spaceagent.platform.project.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for explicit Project memberships. */
public interface ProjectMembershipRepository {

    void addMember(ProjectMembership membership);

    boolean removeMember(String projectId, String userId);

    Optional<ProjectMembership> findMembership(String projectId, String userId);

    List<ProjectMembership> listMembers(String projectId);
}
