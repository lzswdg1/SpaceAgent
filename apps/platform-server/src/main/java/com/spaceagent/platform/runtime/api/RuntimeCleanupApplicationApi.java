package com.spaceagent.platform.runtime.api;

import java.time.Instant;
import java.util.List;

public interface RuntimeCleanupApplicationApi {
    QuiesceView quiesceOrganization(String organizationId);
    void purgeOrganization(String organizationId);
    QuiesceView quiesceUser(String userId);
    List<String> userRunIds(String userId);
    void purgeUser(String userId);

    record QuiesceView(boolean ready, Instant retryAt) {
    }
}
