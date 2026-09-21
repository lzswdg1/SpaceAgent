package com.spaceagent.admin.identity.application;

import com.spaceagent.admin.audit.application.AdminAuditService;
import com.spaceagent.admin.audit.domain.AdminAuditOutcome;
import com.spaceagent.admin.config.AdminBootstrapProperties;
import com.spaceagent.admin.identity.domain.AdminIdentityRepository;
import com.spaceagent.admin.identity.domain.AdminPrincipalStatus;
import com.spaceagent.admin.identity.domain.AdminRole;
import com.spaceagent.admin.identity.domain.SystemAdministrator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Every-start reconciliation of one environment-managed administrator. */
@Component
@Order(0)
public class AdminBootstrapRunner implements ApplicationRunner {
    private final AdminBootstrapProperties properties;
    private final AdminIdentityRepository repository;
    private final PasswordEncoder encoder;
    private final AdminAuditService audit;
    private final Clock clock;

    public AdminBootstrapRunner(AdminBootstrapProperties properties, AdminIdentityRepository repository,
            PasswordEncoder adminPasswordEncoder, AdminAuditService audit, Clock adminClock) {
        this.properties = properties; this.repository = repository; this.encoder = adminPasswordEncoder;
        this.audit = audit; this.clock = adminClock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String login = properties.getLoginName() == null ? "" : properties.getLoginName().trim().toLowerCase(Locale.ROOT);
        String display = properties.getDisplayName() == null ? "" : properties.getDisplayName().trim();
        if (login.isBlank() || login.length() > 120 || display.isBlank() || display.length() > 120)
            throw new IllegalStateException("ADMIN_LOGIN and administrator display name must contain 1-120 characters");
        try { AdminAuthenticationService.validatePassword(properties.getPassword()); }
        catch (RuntimeException invalid) { throw new IllegalStateException("ADMIN_PASSWORD does not satisfy the administrator password policy"); }
        repository.lockConfiguredAccount();
        var existing = repository.findSingletonAuthentication();
        var now = clock.instant();
        UUID id;
        if (existing.isEmpty()) {
            id = UUID.randomUUID();
            repository.bootstrap(new SystemAdministrator(id, login, display, AdminPrincipalStatus.ACTIVE,
                    AdminRole.PLATFORM_SUPER_ADMIN, 1, false, false, null, now, now),
                    encoder.encode(properties.getPassword()), null, null, List.of(), now);
        } else {
            var record = existing.get();
            var principal = record.principal();
            id = principal.id();
            boolean unchanged = principal.loginName().equals(login) && principal.displayName().equals(display)
                    && principal.canAuthenticate() && !principal.mfaRequired() && !principal.mustChangePassword()
                    && encoder.matches(properties.getPassword(), record.passwordHash());
            if (unchanged) return;
            repository.synchronizeConfiguredAccount(id, login, display, encoder.encode(properties.getPassword()), now);
        }
        audit.append(id, null, "ADMIN_CONFIGURED_ACCOUNT_SYNC", "ADMIN_PRINCIPAL", id.toString(),
                "startup", null, AdminAuditOutcome.SUCCEEDED, null);
    }
}
