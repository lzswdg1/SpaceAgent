package com.spaceagent.platform.memory.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;

public interface MemorySystemAdministrationApi {
    SystemAdministrationPage<UserResourceSummary> memoriesByUser(
            String userId, int page, int pageSize);
}
