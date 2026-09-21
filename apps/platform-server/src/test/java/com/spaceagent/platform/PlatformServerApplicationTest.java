package com.spaceagent.platform;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "management.endpoints.web.exposure.include=health,prometheus")
@AutoConfigureMockMvc
@AutoConfigureObservability(metrics = true, tracing = true)
class PlatformServerApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Autowired
    private Tracer tracer;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void applicationContextStartsWithPrometheusAndSafeOtlpDefaults() throws Exception {
        assertNotNull(applicationContext);
        assertNotNull(prometheusMeterRegistry);
        assertNotNull(tracer);
        assertEquals("${PLATFORM_USER_DELETION_ENABLED:false}",
                PlatformServerApplication.runtimeDefaults().get(
                        "platform.identity.user-cleanup.deletion-enabled"));
        assertEquals("${PLATFORM_OBSERVABILITY_OTLP_ENABLED:false}",
                PlatformServerApplication.runtimeDefaults().get(
                        "management.otlp.tracing.export.enabled"));
        assertEquals("${PLATFORM_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY:0.10}",
                PlatformServerApplication.runtimeDefaults().get(
                        "management.tracing.sampling.probability"));

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "jvm_memory_used_bytes")));
    }
}
