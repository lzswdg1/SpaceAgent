package com.spaceagent.admin.identity.application;

import com.spaceagent.admin.shared.AdminApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminLoginAdmissionServiceTest {
    @Test
    void limitsOneNetworkLoginPairWithoutLockingTheAdministratorGlobally() {
        var admission = new AdminLoginAdmissionService(System::currentTimeMillis);
        for (int index = 0; index < 5; index++) {
            admission.requireAttempt("203.0.113.10", "local-admin", 5);
        }
        assertThatThrownBy(() -> admission.requireAttempt(
                "203.0.113.10", "local-admin", 5))
                .isInstanceOfSatisfying(AdminApiException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("ADMIN_LOGIN_RATE_LIMITED"));

        for (int index = 0; index < 5; index++) {
            admission.requireAttempt("203.0.113.11", "local-admin", 5);
        }
    }

    @Test
    void expiredWindowCanAdmitAgain() {
        AtomicLong now = new AtomicLong();
        var admission = new AdminLoginAdmissionService(now::get);
        admission.requireAttempt("203.0.113.10", "local-admin", 1);
        assertThatThrownBy(() -> admission.requireAttempt(
                "203.0.113.10", "local-admin", 1)).isInstanceOf(AdminApiException.class);

        now.addAndGet(Duration.ofMinutes(15).toMillis());
        admission.requireAttempt("203.0.113.10", "local-admin", 1);
    }
}
