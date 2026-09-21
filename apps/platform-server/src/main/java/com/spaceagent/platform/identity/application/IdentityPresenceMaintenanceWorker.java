package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.domain.IdentityPresenceRepository;
import com.spaceagent.shared.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;

@Component
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class IdentityPresenceMaintenanceWorker {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityPresenceMaintenanceWorker.class);
    private final IdentityPresenceRepository repository;
    private final TimeProvider time;
    private final int retentionHours;
    private final int batchSize;

    public IdentityPresenceMaintenanceWorker(
            IdentityPresenceRepository repository,
            TimeProvider time,
            @Value("${platform.identity.presence.retention-hours:24}") int retentionHours,
            @Value("${platform.identity.presence.sweep-batch-size:1000}") int batchSize) {
        this.repository = repository;
        this.time = time;
        this.retentionHours = Math.max(1, Math.min(retentionHours, 24 * 30));
        this.batchSize = Math.max(1, Math.min(batchSize, 5_000));
    }

    @Scheduled(fixedDelayString = "${platform.identity.presence.sweep-delay-ms:60000}")
    public void poll() {
        try {
            var cutoff = time.now().minus(retentionHours, ChronoUnit.HOURS);
            for (int index = 0; index < 10; index++) {
                if (repository.sweepExpired(cutoff, batchSize) < batchSize) return;
            }
        } catch (RuntimeException error) {
            LOG.warn("Identity presence cleanup failed safely: type={}",
                    error.getClass().getSimpleName());
        }
    }
}
