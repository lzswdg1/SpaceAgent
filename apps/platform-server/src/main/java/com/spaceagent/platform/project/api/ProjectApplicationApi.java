package com.spaceagent.platform.project.api;

import java.util.List;

/** Public tenant- and user-aware Project use cases. */
public interface ProjectApplicationApi {

    ProjectView createProject(CreateProjectCommand command);

    ProjectView getProject(GetProjectQuery query);

    List<ProjectView> listProjects(ListProjectsQuery query);

    ProjectView updateProject(UpdateProjectCommand command);

    ProjectView archiveProject(ArchiveProjectCommand command);

    ProjectMembershipView addMember(AddProjectMemberCommand command);

    void removeMember(RemoveProjectMemberCommand command);

    List<ProjectMembershipView> listMembers(ListProjectMembersQuery query);
}
