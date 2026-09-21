package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;

public interface KnowledgeSystemAdministrationApi {
    SystemAdministrationPage<UserResourceSummary> documentsByOwner(
            String userId, int page, int pageSize);
}
