package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class LocalWorkspaceBridgeApplicationService
        implements LocalWorkspaceBridgeApplicationApi {

    private final LocalWorkspaceBridgeRepository repository;
    private final ProjectAccessPolicy accessPolicy;
    private final IdGenerator ids;
    private final TimeProvider time;

    public LocalWorkspaceBridgeApplicationService(
            LocalWorkspaceBridgeRepository repository,
            ProjectAccessPolicy accessPolicy,
            IdGenerator ids,
            TimeProvider time) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public CreatedLocalWorkspaceBridgeView register(RegisterLocalWorkspaceBridgeCommand command) {
        accessPolicy.requireIdentityMembership(command.tenantId(), command.userId());
        String displayName = bounded(command.displayName(), "displayName", 120);
        String deviceId = bounded(command.deviceId(), "deviceId", 160);
        String rootHandle = bounded(command.rootHandle(), "rootHandle", 128);
        try {
            SourceRepository.requireOpaqueRootHandle(rootHandle);
        } catch (IllegalArgumentException error) {
            throw invalidRootHandle();
        }
        if (repository.exists(command.tenantId(), command.userId(), deviceId, rootHandle)) {
            throw new BusinessException(
                    "Local Workspace Bridge already exists",
                    HttpStatus.CONFLICT,
                    "WORKSPACE_BRIDGE_CONFLICT");
        }
        String rawToken = ProjectSecretSupport.randomToken("brg_");
        Instant now = time.now();
        LocalWorkspaceBridge bridge = new LocalWorkspaceBridge(
                ids.nextId(), command.tenantId(), command.userId(), displayName,
                deviceId, rootHandle, ProjectSecretSupport.sha256(rawToken),
                rawToken.substring(0, 12), LocalWorkspaceBridgeState.ACTIVE,
                now, now, now, null);
        try {
            repository.save(bridge);
        } catch (DataIntegrityViolationException error) {
            throw new BusinessException(
                    "Local Workspace Bridge already exists",
                    HttpStatus.CONFLICT,
                    "WORKSPACE_BRIDGE_CONFLICT");
        }
        return new CreatedLocalWorkspaceBridgeView(rawToken, toView(bridge));
    }

    @Override
    public LocalWorkspaceBridgeView heartbeat(HeartbeatLocalWorkspaceBridgeCommand command) {
        accessPolicy.requireIdentityMembership(command.tenantId(), command.userId());
        LocalWorkspaceBridge bridge = repository.findByTokenHash(
                        ProjectSecretSupport.sha256(command.rawToken() == null ? "" : command.rawToken()))
                .filter(value -> value.id().equals(command.bridgeId()))
                .filter(value -> value.tenantId().equals(command.tenantId()))
                .filter(value -> value.ownerId().equals(command.userId()))
                .filter(value -> value.state() == LocalWorkspaceBridgeState.ACTIVE)
                .orElseThrow(LocalWorkspaceBridgeApplicationService::bridgeAuthenticationFailed);
        LocalWorkspaceBridge updated = bridge.heartbeat(time.now());
        repository.save(updated);
        return toView(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalWorkspaceBridgeView> list(ListLocalWorkspaceBridgesQuery query) {
        accessPolicy.requireIdentityMembership(query.tenantId(), query.userId());
        return repository.findByOwner(query.tenantId(), query.userId()).stream()
                .map(LocalWorkspaceBridgeApplicationService::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LocalWorkspaceBridgeView resolve(ResolveLocalWorkspaceBridgeQuery query) {
        accessPolicy.requireIdentityMembership(query.tenantId(), query.userId());
        try {
            SourceRepository.requireOpaqueRootHandle(query.rootHandle());
        } catch (IllegalArgumentException error) {
            throw invalidRootHandle();
        }
        String bridgeId = requireUuid(query.bridgeId());
        return repository.findById(bridgeId)
                .filter(value -> value.tenantId().equals(query.tenantId()))
                .filter(value -> value.ownerId().equals(query.userId()))
                .filter(value -> value.rootHandle().equals(query.rootHandle()))
                .filter(value -> value.state() == LocalWorkspaceBridgeState.ACTIVE)
                .map(LocalWorkspaceBridgeApplicationService::toView)
                .orElseThrow(() -> new BusinessException(
                        "Local Workspace Bridge not found",
                        HttpStatus.NOT_FOUND,
                        "WORKSPACE_BRIDGE_NOT_FOUND"));
    }

    @Override
    public void revoke(RevokeLocalWorkspaceBridgeCommand command) {
        accessPolicy.requireIdentityMembership(command.tenantId(), command.userId());
        LocalWorkspaceBridge bridge = repository.findById(requireUuid(command.bridgeId()))
                .filter(value -> value.tenantId().equals(command.tenantId()))
                .filter(value -> value.ownerId().equals(command.userId()))
                .orElseThrow(() -> new BusinessException(
                        "Local Workspace Bridge not found",
                        HttpStatus.NOT_FOUND,
                        "WORKSPACE_BRIDGE_NOT_FOUND"));
        if (bridge.state() != LocalWorkspaceBridgeState.REVOKED) {
            repository.save(bridge.revoke(time.now()));
        }
    }

    private static LocalWorkspaceBridgeView toView(LocalWorkspaceBridge bridge) {
        return new LocalWorkspaceBridgeView(
                bridge.id(), bridge.displayName(), bridge.deviceId(), bridge.rootHandle(),
                bridge.tokenPrefix(), bridge.state(), bridge.lastSeenAt(), bridge.createdAt(),
                bridge.updatedAt(), bridge.revokedAt());
    }

    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new BusinessException(
                    field + " is invalid", HttpStatus.BAD_REQUEST, "WORKSPACE_BRIDGE_INPUT_INVALID");
        }
        return value.trim();
    }

    private static BusinessException invalidRootHandle() {
        return new BusinessException(
                "rootHandle must be an opaque non-path identifier",
                HttpStatus.BAD_REQUEST,
                "WORKSPACE_BRIDGE_ROOT_HANDLE_INVALID");
    }

    private static BusinessException bridgeAuthenticationFailed() {
        return new BusinessException(
                "Local Workspace Bridge authentication failed",
                HttpStatus.UNAUTHORIZED,
                "WORKSPACE_BRIDGE_AUTHENTICATION_FAILED");
    }

    private static String requireUuid(String value) {
        try { return java.util.UUID.fromString(value).toString(); }
        catch (Exception error) {
            throw new BusinessException(
                    "Local Workspace Bridge not found", HttpStatus.NOT_FOUND,
                    "WORKSPACE_BRIDGE_NOT_FOUND");
        }
    }
}
