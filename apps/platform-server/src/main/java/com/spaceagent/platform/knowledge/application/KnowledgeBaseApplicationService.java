package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.knowledge.api.KnowledgeBaseApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeAccessApplicationApi;
import com.spaceagent.platform.knowledge.domain.KnowledgeBase;
import com.spaceagent.platform.knowledge.domain.KnowledgeBaseGrant;
import com.spaceagent.platform.knowledge.domain.KnowledgeBaseRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static com.spaceagent.platform.knowledge.domain.KnowledgeBase.Permission.*;

@Service
@Transactional
public class KnowledgeBaseApplicationService implements KnowledgeBaseApplicationApi, KnowledgeAccessApplicationApi {
    private final KnowledgeBaseRepository bases;
    private final IdentityApplicationApi identity;
    private final IdGenerator ids;
    private final TimeProvider time;

    public KnowledgeBaseApplicationService(KnowledgeBaseRepository bases, IdentityApplicationApi identity,
                                           IdGenerator ids, TimeProvider time) {
        this.bases = bases;
        this.identity = identity;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public BaseView create(CreateCommand command) {
        requireActor(command.actor());
        if (command.scope() == null) throw invalid("Knowledge base scope is required");
        if (command.scope() == KnowledgeBase.Scope.ORGANIZATION
                && role(command.actor()) == TenantRole.VIEWER) throw forbidden();
        String name = name(command.name());
        description(command.description());
        var now = time.now();
        KnowledgeBase base = new KnowledgeBase(ids.nextId(), command.scope(),
                command.scope() == KnowledgeBase.Scope.ORGANIZATION ? command.actor().organizationId() : null,
                command.actor().userId(), name, command.description(), KnowledgeBase.State.ACTIVE, 1, now, now);
        bases.insert(base);
        return new BaseView(base, MANAGE);
    }

    @Override
    @Transactional(readOnly = true)
    public BaseView get(Actor actor, String baseId) {
        return authorize(actor, baseId, READ);
    }

    @Override
    @Transactional(readOnly = true)
    public BaseView authorize(Actor actor, String baseId, KnowledgeBase.Permission required) {
        if (required == null) throw invalid("Permission is required");
        KnowledgeBase base = active(baseId, false);
        return new BaseView(base, require(actor, base, required));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<BaseView> findAccessible(Actor actor, String baseId, KnowledgeBase.Permission required) {
        try { return java.util.Optional.of(authorize(actor, baseId, required)); }
        catch (BusinessException denied) {
            if (denied.getStatus() == HttpStatus.FORBIDDEN || denied.getStatus() == HttpStatus.NOT_FOUND)
                return java.util.Optional.empty();
            throw denied;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page list(Actor actor, int offset, int limit) {
        requireActor(actor);
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) throw invalid("Invalid page bounds");
        // An inactive organization must not reveal its collections, even with an old role claim.
        String currentRole = null;
        if (actor.organizationId() != null) {
            try { currentRole = role(actor).name(); }
            catch (BusinessException denied) {
                if (denied.getStatus() != HttpStatus.FORBIDDEN) throw denied;
            }
        }
        List<KnowledgeBase> rows = bases.listVisible(actor.userId(),
                currentRole == null ? null : actor.organizationId(), currentRole, offset, limit + 1);
        List<BaseView> items = rows.stream().limit(limit)
                .map(base -> new BaseView(base, require(actor, base, READ))).toList();
        return new Page(items, offset, limit, rows.size() > limit);
    }

    @Override
    public synchronized BaseView update(UpdateCommand command) {
        KnowledgeBase base = active(command.baseId(), true);
        require(command.actor(), base, MANAGE);
        description(command.description());
        KnowledgeBase updated = base.revise(name(command.name()), command.description(), base.state(), time.now());
        persist(updated, command.expectedRevision());
        return new BaseView(updated, MANAGE);
    }

    @Override
    public synchronized void archive(Actor actor, String baseId, long expectedRevision) {
        KnowledgeBase base = active(baseId, true);
        require(actor, base, MANAGE);
        persist(base.revise(base.name(), base.description(), KnowledgeBase.State.ARCHIVED, time.now()), expectedRevision);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBaseGrant> grants(Actor actor, String baseId) {
        KnowledgeBase base = active(baseId, false);
        require(actor, base, MANAGE);
        return bases.grants(baseId);
    }

    @Override
    public synchronized BaseView grant(GrantCommand command) {
        KnowledgeBase base = active(command.baseId(), true);
        require(command.actor(), base, MANAGE);
        if (base.scope() != KnowledgeBase.Scope.ORGANIZATION) throw invalid("Personal knowledge bases cannot be shared");
        if (command.permission() == null || command.subjectType() == null) throw invalid("Grant target and permission are required");
        if (command.subjectType() == KnowledgeBaseGrant.SubjectType.USER) {
            TenantRole targetRole = role(new Actor(command.subjectId(), base.organizationId()));
            if (targetRole == TenantRole.VIEWER && command.permission() != READ) throw invalid("VIEWER grants must be READ");
        } else {
            if (!java.util.Set.of("OWNER", "ADMIN", "MEMBER", "VIEWER").contains(
                    Objects.toString(command.subjectId(), ""))) throw invalid("Invalid grant role");
            if ("VIEWER".equals(command.subjectId()) && command.permission() != READ) throw invalid("VIEWER grants must be READ");
        }
        var grant = new KnowledgeBaseGrant(base.id(), base.organizationId(), command.subjectType(),
                command.subjectId(), command.permission(), command.actor().userId(), time.now());
        var existing = bases.grants(base.id());
        if (existing.size() >= 200 && existing.stream().noneMatch(value ->
                value.subjectType() == grant.subjectType() && value.subjectId().equals(grant.subjectId()))) {
            throw invalid("Knowledge base grant limit reached");
        }
        KnowledgeBase updated = base.revise(base.name(), base.description(), base.state(), time.now());
        persist(updated, command.expectedRevision());
        bases.putGrant(grant);
        return new BaseView(updated, require(command.actor(), updated, READ));
    }

    @Override
    public synchronized BaseView revoke(RevokeCommand command) {
        KnowledgeBase base = active(command.baseId(), true);
        require(command.actor(), base, MANAGE);
        if (base.scope() != KnowledgeBase.Scope.ORGANIZATION) throw invalid("Personal knowledge bases have no grants");
        if (command.subjectType() == null || command.subjectId() == null
                || command.subjectId().isBlank() || command.subjectId().length() > 64) throw invalid("Invalid grant target");
        KnowledgeBase updated = base.revise(base.name(), base.description(), base.state(), time.now());
        persist(updated, command.expectedRevision());
        bases.removeGrant(base.id(), command.subjectType(), command.subjectId());
        // A delegated manager can revoke their own grant; do not undo it by re-authorizing the response.
        return new BaseView(updated, permission(command.actor(), updated));
    }

    private void persist(KnowledgeBase updated, long expected) {
        if (expected < 1 || updated.revision() != expected + 1 || !bases.update(updated, expected)) {
            throw new BusinessException("Knowledge base changed; reload before editing", HttpStatus.CONFLICT,
                    "KNOWLEDGE_BASE_REVISION_CONFLICT");
        }
    }

    private KnowledgeBase active(String id, boolean lock) {
        return (lock ? bases.lock(id) : bases.find(id))
                .filter(base -> base.state() == KnowledgeBase.State.ACTIVE).orElseThrow(KnowledgeBaseApplicationService::notFound);
    }

    private KnowledgeBase.Permission require(Actor actor, KnowledgeBase base, KnowledgeBase.Permission required) {
        requireActor(actor);
        KnowledgeBase.Permission permission = permission(actor, base);
        if (permission == null) throw notFound();
        if (!permission.includes(required)) throw forbidden();
        return permission;
    }

    private KnowledgeBase.Permission permission(Actor actor, KnowledgeBase base) {
        if (base.scope() == KnowledgeBase.Scope.PERSONAL) {
            return Objects.equals(actor.userId(), base.ownerId()) ? MANAGE : null;
        }
        if (!Objects.equals(actor.organizationId(), base.organizationId())) return null;
        TenantRole role = role(actor);
        KnowledgeBase.Permission result;
        if (role == TenantRole.OWNER || Objects.equals(actor.userId(), base.ownerId())) result = MANAGE;
        else result = bases.grants(base.id()).stream()
                .filter(grant -> grant.subjectType() == KnowledgeBaseGrant.SubjectType.USER
                        ? grant.subjectId().equals(actor.userId()) : grant.subjectId().equals(role.name()))
                .map(KnowledgeBaseGrant::permission).max(Comparator.naturalOrder()).orElse(null);
        return role == TenantRole.VIEWER && result != null ? READ : result;
    }

    private TenantRole role(Actor actor) {
        if (actor == null || actor.organizationId() == null || actor.userId() == null) throw forbidden();
        identity.findTenant(actor.organizationId()).filter(tenant -> tenant.status() == TenantStatus.ACTIVE)
                .orElseThrow(KnowledgeBaseApplicationService::forbidden);
        return identity.findTenantMembership(actor.organizationId(), actor.userId())
                .filter(member -> member.status() == TenantMembershipStatus.ACTIVE)
                .map(member -> member.role()).orElseThrow(KnowledgeBaseApplicationService::forbidden);
    }

    private void requireActor(Actor actor) {
        if (actor == null || actor.userId() == null || actor.userId().isBlank()
                || identity.findUser(actor.userId()).isEmpty()) throw forbidden();
    }
    private static String name(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 255) throw invalid("Invalid knowledge base name");
        return value.trim();
    }
    private static void description(String value) {
        if (value != null && value.length() > 2000) throw invalid("Knowledge base description exceeds limit");
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "KNOWLEDGE_BASE_INVALID");
    }
    private static BusinessException forbidden() {
        return new BusinessException("Knowledge base access is forbidden", HttpStatus.FORBIDDEN, "KNOWLEDGE_BASE_FORBIDDEN");
    }
    private static BusinessException notFound() {
        return new BusinessException("Knowledge base not found", HttpStatus.NOT_FOUND, "KNOWLEDGE_BASE_NOT_FOUND");
    }
}
