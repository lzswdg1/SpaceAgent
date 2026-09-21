package com.spaceagent.platform.automation.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;

public interface AutomationSystemAdministrationApi {
    SystemAdministrationPage<UserResourceSummary> schedulesByOwner(
            String userId, int page, int pageSize);
}
