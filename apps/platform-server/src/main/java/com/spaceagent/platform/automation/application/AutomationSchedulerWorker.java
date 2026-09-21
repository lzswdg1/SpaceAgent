package com.spaceagent.platform.automation.application;

import com.spaceagent.platform.automation.api.AutomationApplicationApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Stateless wake-up loop; PostgreSQL owns time, locks, occurrences and execution truth. */
@Component
@ConditionalOnProperty(
        prefix = "platform.automation", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AutomationSchedulerWorker {

    private static final Logger log = LoggerFactory.getLogger(AutomationSchedulerWorker.class);
    private static final int MAX_BATCH = 20;

    private final AutomationApplicationApi automation;

    public AutomationSchedulerWorker(AutomationApplicationApi automation) {
        this.automation = automation;
    }

    @Scheduled(fixedDelayString = "${platform.automation.poll-delay-ms:1000}")
    public void poll() {
        try {
            for (int index = 0; index < MAX_BATCH && automation.materializeNextDue(); index++) {
                // One short PostgreSQL transaction per occurrence.
            }
            for (int index = 0; index < MAX_BATCH && automation.dispatchNextApproved(); index++) {
                // Round-robin approval checks are ordered by durable updated_at.
            }
            automation.reconcileUnknownExecutions(600, MAX_BATCH);
        } catch (RuntimeException error) {
            log.warn("Automation durable poll failed; the next wake-up will retry", error);
        }
    }
}
