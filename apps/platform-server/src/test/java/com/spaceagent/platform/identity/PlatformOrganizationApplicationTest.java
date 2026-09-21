package com.spaceagent.platform.identity;

import com.spaceagent.platform.identity.api.AddOrganizationMemberCommand;
import com.spaceagent.platform.identity.api.CreateOrganizationCommand;
import com.spaceagent.platform.identity.api.LeaveOrganizationCommand;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.api.RemoveOrganizationMemberCommand;
import com.spaceagent.platform.identity.api.TransferOrganizationOwnershipCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.application.OrganizationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationCleanupApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryOrganizationCleanupRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformOrganizationApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    private InMemoryIdentityRepository repository;
    private OrganizationApplicationService organizations;
    private IdentityApplicationService identity;

    @BeforeEach
    void setUp() {
        repository = new InMemoryIdentityRepository();
        UuidGenerator ids = new UuidGenerator();
        TimeProvider time = () -> NOW;
        var cleanup = new OrganizationCleanupApplicationService(
                repository, new InMemoryOrganizationCleanupRepository(), time, 0, 10, 1);
        organizations = new OrganizationApplicationService(repository, ids, time, cleanup);
        identity = new IdentityApplicationService(repository, ids, time);
    }

    @Test
    void personalProvisionCreatesCreatorOwnerAndActiveMembership() {
        var provisioned = provision("alice@example.com", "Alice", "alice-personal");

        assertEquals(provisioned.user().id(), provisioned.organization().creatorUserId());
        assertEquals(TenantRole.OWNER, provisioned.membership().role());
        assertEquals(TenantStatus.ACTIVE, provisioned.organization().status());
        assertTrue(identity.isMemberOfTenant(
                provisioned.organization().id(), provisioned.user().id()));
        assertEquals(1, organizations.listOrganizations(provisioned.user().id()).size());
    }

    @Test
    void ownerAndAdminManageOnlyAllowedMembershipRoles() {
        var alice = provision("alice@example.com", "Alice", "alice-personal");
        var bob = provision("bob@example.com", "Bob", "bob-personal");
        var charlie = provision("charlie@example.com", "Charlie", "charlie-personal");
        String organizationId = organizations.createOrganization(new CreateOrganizationCommand(
                alice.user().id(), "Engineering", "engineering")).id();

        organizations.addMember(new AddOrganizationMemberCommand(
                alice.user().id(), organizationId, bob.user().id(), TenantRole.MEMBER));
        organizations.addMember(new AddOrganizationMemberCommand(
                alice.user().id(), organizationId, charlie.user().id(), TenantRole.VIEWER));
        assertForbidden(() -> organizations.addMember(new AddOrganizationMemberCommand(
                bob.user().id(), organizationId, charlie.user().id(), TenantRole.MEMBER)));

        organizations.addMember(new AddOrganizationMemberCommand(
                alice.user().id(), organizationId, bob.user().id(), TenantRole.ADMIN));
        assertEquals(TenantRole.MEMBER, organizations.addMember(new AddOrganizationMemberCommand(
                bob.user().id(), organizationId, charlie.user().id(), TenantRole.MEMBER)).role());
        assertForbidden(() -> organizations.addMember(new AddOrganizationMemberCommand(
                bob.user().id(), organizationId, charlie.user().id(), TenantRole.ADMIN)));
        assertEquals(3, organizations.listMembers(
                organizationId, charlie.user().id()).size());

        assertThrows(BusinessException.class,
                () -> organizations.removeMember(new RemoveOrganizationMemberCommand(
                        alice.user().id(), organizationId, alice.user().id())));
    }

    @Test
    void ownerTransferIsRequiredBeforeLeavingNonEmptyOrganization() {
        var alice = provision("alice@example.com", "Alice", "alice-personal");
        var bob = provision("bob@example.com", "Bob", "bob-personal");
        String organizationId = organizations.createOrganization(new CreateOrganizationCommand(
                alice.user().id(), "Shared", "shared")).id();
        organizations.addMember(new AddOrganizationMemberCommand(
                alice.user().id(), organizationId, bob.user().id(), TenantRole.MEMBER));

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> organizations.leaveOrganization(new LeaveOrganizationCommand(
                        alice.user().id(), organizationId)));
        assertEquals("ORGANIZATION_OWNER_TRANSFER_REQUIRED", blocked.getCode());

        var transferred = organizations.transferOwnership(
                new TransferOrganizationOwnershipCommand(
                        alice.user().id(), organizationId, bob.user().id()));
        assertEquals(bob.user().id(), transferred.creatorUserId());
        assertEquals(TenantRole.ADMIN, repository.findMembership(
                organizationId, alice.user().id()).orElseThrow().role());
        assertEquals(TenantRole.OWNER, repository.findMembership(
                organizationId, bob.user().id()).orElseThrow().role());

        assertEquals(1L, organizations.leaveOrganization(new LeaveOrganizationCommand(
                alice.user().id(), organizationId)).remainingActiveMembers());
        var deleted = organizations.leaveOrganization(new LeaveOrganizationCommand(
                bob.user().id(), organizationId));
        assertEquals(0L, deleted.remainingActiveMembers());
        assertEquals(TenantStatus.DELETING, deleted.organizationStatus());
        assertFalse(identity.isMemberOfTenant(organizationId, bob.user().id()));
        assertEquals(TenantStatus.DELETING,
                repository.findTenantById(organizationId).orElseThrow().status());
    }

    @Test
    void duplicateSlugIsRejected() {
        var alice = provision("alice@example.com", "Alice", "alice-personal");
        organizations.createOrganization(new CreateOrganizationCommand(
                alice.user().id(), "First", "shared-slug"));

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> organizations.createOrganization(new CreateOrganizationCommand(
                        alice.user().id(), "Second", "shared-slug")));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("ORGANIZATION_SLUG_CONFLICT", conflict.getCode());
    }

    private com.spaceagent.platform.identity.api.ProvisionedOrganizationView provision(
            String externalId,
            String displayName,
            String slug) {
        return organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        externalId, displayName, "Personal - " + displayName, slug));
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals("ORGANIZATION_ACCESS_DENIED", exception.getCode());
    }
}
