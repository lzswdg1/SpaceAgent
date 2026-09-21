package com.spaceagent.platform.project;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.AddProjectMemberCommand;
import com.spaceagent.platform.project.api.ArchiveProjectCommand;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.GetProjectQuery;
import com.spaceagent.platform.project.api.ListProjectMembersQuery;
import com.spaceagent.platform.project.api.ListProjectsQuery;
import com.spaceagent.platform.project.api.RemoveProjectMemberCommand;
import com.spaceagent.platform.project.api.UpdateProjectCommand;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.ProjectStatus;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectOwnershipAdapter;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformProjectApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T08:00:00Z");

    private final Set<String> tenantMemberships = new HashSet<>();
    private InMemoryProjectRepository projects;
    private InMemoryProjectMembershipRepository memberships;
    private ProjectApplicationService service;

    @BeforeEach
    void setUp() {
        projects = new InMemoryProjectRepository();
        memberships = new InMemoryProjectMembershipRepository();
        IdentityOwnershipPort identityOwnership =
                (tenantId, principalId) -> tenantMemberships.contains(tenantId + ":" + principalId);
        TimeProvider timeProvider = () -> NOW;
        service = new ProjectApplicationService(
                projects, memberships,
                new ProjectAccessPolicy(projects, memberships, identityOwnership, (t, u, w) -> {}),
                new UuidGenerator(), timeProvider);
    }

    @Test
    void createPersistsTenantOwnerActiveStatusAndOwnerMembership() {
        addTenantUser("tenant-a", "owner-a");

        var created = service.createProject(new CreateProjectCommand(
                "tenant-a", "owner-a", "  Project Alpha  ", "Foundation"));

        assertEquals("tenant-a", created.tenantId());
        assertEquals("owner-a", created.ownerId());
        assertEquals("Project Alpha", created.name());
        assertEquals("Foundation", created.description());
        assertEquals(ProjectStatus.ACTIVE, created.status());
        var ownerMembership = memberships.findMembership(created.id(), "owner-a").orElseThrow();
        assertEquals(ProjectRole.OWNER, ownerMembership.role());
    }

    @Test
    void queryIsLimitedToCurrentTenantAndExplicitMembership() {
        addTenantUser("tenant-a", "owner-a");
        addTenantUser("tenant-a", "member-a");
        addTenantUser("tenant-b", "owner-b");
        addTenantUser("tenant-b", "owner-a");
        var alpha = create("tenant-a", "owner-a", "Alpha");
        create("tenant-a", "owner-a", "Hidden");
        var beta = create("tenant-b", "owner-b", "Beta");
        service.addMember(new AddProjectMemberCommand(
                "tenant-a", "owner-a", alpha.id(), "member-a", ProjectRole.MEMBER));

        var visible = service.listProjects(new ListProjectsQuery("tenant-a", "member-a"));
        assertEquals(1, visible.size());
        assertEquals(alpha.id(), visible.getFirst().id());

        BusinessException crossTenant = assertThrows(BusinessException.class,
                () -> service.getProject(new GetProjectQuery(
                        "tenant-b", "owner-a", alpha.id())));
        assertEquals(HttpStatus.NOT_FOUND, crossTenant.getStatus());
        assertEquals("PROJECT_NOT_FOUND", crossTenant.getCode());

        BusinessException noMembership = assertThrows(BusinessException.class,
                () -> service.getProject(new GetProjectQuery(
                        "tenant-b", "owner-a", beta.id())));
        assertEquals(HttpStatus.FORBIDDEN, noMembership.getStatus());
    }

    @Test
    void viewerAndMemberCannotModifyAdminCanModifyAndOnlyOwnerCanArchive() {
        addTenantUser("tenant-a", "owner");
        addTenantUser("tenant-a", "viewer");
        addTenantUser("tenant-a", "member");
        addTenantUser("tenant-a", "admin");
        var project = create("tenant-a", "owner", "Roles");
        service.addMember(new AddProjectMemberCommand(
                "tenant-a", "owner", project.id(), "viewer", ProjectRole.VIEWER));
        service.addMember(new AddProjectMemberCommand(
                "tenant-a", "owner", project.id(), "member", ProjectRole.MEMBER));
        service.addMember(new AddProjectMemberCommand(
                "tenant-a", "owner", project.id(), "admin", ProjectRole.ADMIN));

        assertForbidden(() -> update(project.id(), "viewer", "viewer-edit"));
        assertForbidden(() -> update(project.id(), "member", "member-edit"));
        assertEquals("admin-edit", update(project.id(), "admin", "admin-edit").name());
        assertForbidden(() -> service.archiveProject(new ArchiveProjectCommand(
                "tenant-a", "admin", project.id())));

        var archived = service.archiveProject(new ArchiveProjectCommand(
                "tenant-a", "owner", project.id()));
        assertEquals(ProjectStatus.ARCHIVED, archived.status());
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> update(project.id(), "owner", "after-archive"));
        assertEquals("PROJECT_ARCHIVED", rejected.getCode());
    }

    @Test
    void membershipCanBeAddedRoleChangedAndRemovedByOwner() {
        addTenantUser("tenant-a", "owner");
        addTenantUser("tenant-a", "member");
        var project = create("tenant-a", "owner", "Membership");
        var ownership = new InMemoryProjectOwnershipAdapter(
                projects, memberships,
                (tenantId, principalId) -> tenantMemberships.contains(tenantId + ":" + principalId));

        var viewer = service.addMember(new AddProjectMemberCommand(
                "tenant-a", "owner", project.id(), "member", ProjectRole.VIEWER));
        assertEquals(ProjectRole.VIEWER, viewer.role());
        assertFalse(ownership.isOwnerOrMember(project.id(), "member"));

        var admin = service.addMember(new AddProjectMemberCommand(
                "tenant-a", "owner", project.id(), "member", ProjectRole.ADMIN));
        assertEquals(viewer.id(), admin.id());
        assertEquals(ProjectRole.ADMIN, admin.role());
        assertTrue(ownership.isOwnerOrMember(project.id(), "member"));
        assertEquals(2, service.listMembers(new ListProjectMembersQuery(
                "tenant-a", "owner", project.id())).size());

        service.removeMember(new RemoveProjectMemberCommand(
                "tenant-a", "owner", project.id(), "member"));
        assertFalse(ownership.isOwnerOrMember(project.id(), "member"));
        assertEquals(1, service.listMembers(new ListProjectMembersQuery(
                "tenant-a", "owner", project.id())).size());

        BusinessException ownerRemoval = assertThrows(BusinessException.class,
                () -> service.removeMember(new RemoveProjectMemberCommand(
                        "tenant-a", "owner", project.id(), "owner")));
        assertEquals("PROJECT_OWNER_IMMUTABLE", ownerRemoval.getCode());
    }

    private com.spaceagent.platform.project.api.ProjectView create(
            String tenantId, String ownerId, String name) {
        return service.createProject(new CreateProjectCommand(tenantId, ownerId, name, null));
    }

    private com.spaceagent.platform.project.api.ProjectView update(
            String projectId, String userId, String name) {
        return service.updateProject(new UpdateProjectCommand(
                "tenant-a", userId, projectId, name, null, false));
    }

    private void addTenantUser(String tenantId, String userId) {
        tenantMemberships.add(tenantId + ":" + userId);
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals("PROJECT_ACCESS_DENIED", exception.getCode());
    }
}
