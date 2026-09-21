package com.spaceagent.platform.integration;

import com.spaceagent.platform.integration.infrastructure.PlatformRequestAdmissionService;
import com.spaceagent.platform.integration.infrastructure.PlatformJsonBodyLimitFilter;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformRequestAdmissionServiceTest {
    @Test void configuredGlobalCeilingAndIdempotentReleaseRemainBounded() {
        var properties = new com.spaceagent.platform.integration.infrastructure.PlatformAdmissionProperties();
        properties.setMaxSseConnections(2); properties.setMaxSseConnectionsPerUser(1);
        var admission = new PlatformRequestAdmissionService(properties);
        var first = admission.acquireSse("one"); var second = admission.acquireSse("two");
        assertThatThrownBy(() -> admission.acquireSse("three")).isInstanceOf(BusinessException.class);
        first.close(); first.close();
        var third = admission.acquireSse("three");
        assertThatThrownBy(() -> admission.acquireSse("four")).isInstanceOf(BusinessException.class);
        second.close(); third.close();
        var invalid = new com.spaceagent.platform.integration.infrastructure.PlatformAdmissionProperties();
        invalid.setMaxSseConnections(1);
        assertThatThrownBy(() -> new PlatformRequestAdmissionService(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentReleaseAndAcquireCannotRemoveAnActiveUserCounter() throws Exception {
        var p = new com.spaceagent.platform.integration.infrastructure.PlatformAdmissionProperties();
        p.setMaxSseConnections(16); p.setMaxSseConnectionsPerUser(1);
        var admission = new PlatformRequestAdmissionService(p);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var exceeded = new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 6; thread++) futures.add(executor.submit(() -> {
                for (int i = 0; i < 300; i++) {
                    PlatformRequestAdmissionService.SseLease lease;
                    try { lease = admission.acquireSse("same-user"); } catch (BusinessException full) { continue; }
                    if (active.incrementAndGet() != 1) exceeded.set(true);
                    Thread.yield(); active.decrementAndGet(); lease.close();
                }
            }));
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        org.assertj.core.api.Assertions.assertThat(exceeded.get()).isFalse();
        admission.acquireSse("same-user").close();
    }
    @Test
    void authenticationAndSseAdmissionAreBounded() {
        var admission = new PlatformRequestAdmissionService();
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        for (int index = 0; index < 10; index++) {
            admission.requireAuthenticationAttempt("login", "target@example.com", request, 10);
        }
        assertThatThrownBy(() -> admission.requireAuthenticationAttempt(
                "login", "target@example.com", request, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo("AUTH_RATE_LIMITED");

        var independentRemote = new MockHttpServletRequest();
        independentRemote.setRemoteAddr("203.0.113.11");
        for (int index = 0; index < 10; index++) {
            admission.requireAuthenticationAttempt(
                    "login", "target@example.com", independentRemote, 10);
        }

        var leases = new ArrayList<PlatformRequestAdmissionService.SseLease>();
        for (int index = 0; index < 4; index++) leases.add(admission.acquireSse("user-1"));
        assertThatThrownBy(() -> admission.acquireSse("user-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo("SSE_CAPACITY_EXCEEDED");
        leases.forEach(PlatformRequestAdmissionService.SseLease::close);
        admission.acquireSse("user-1").close();
    }

    @Test
    void oversizedJsonIsRejectedBeforeControllerDeserialization() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/knowledge/documents/x/process");
        request.setContentType("application/json");
        request.setContent(new byte[2_000_001]);
        var response = new MockHttpServletResponse();

        new PlatformJsonBodyLimitFilter().doFilter(request, response, new MockFilterChain());

        org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(413);
        org.assertj.core.api.Assertions.assertThat(response.getContentAsString())
                .contains("PAYLOAD_TOO_LARGE");
    }
}
