package com.spaceagent.admin.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTotpServiceTest {
    @Test
    void validatesRfc6238CompatibleSixDigitCodeWithinOneTimeStep() {
        AdminTotpService service = new AdminTotpService(
                Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

        assertThat(service.matchingTimeStep("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082"))
                .hasValue(1L);
        assertThat(service.matchingTimeStep("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "000000"))
                .isEmpty();
        assertThat(service.matchingTimeStep("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "123"))
                .isEmpty();
    }

    @Test
    void generatesFrameworkBackedBase32SecretAndUniqueRecoveryCodes() {
        AdminTotpService service = new AdminTotpService(Clock.systemUTC());

        assertThat(service.generateSecret()).matches("[A-Z2-7]{16,128}");
        assertThat(service.generateRecoveryCodes(8)).hasSize(8).doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[a-z0-9-]{8,64}"));
    }
}
