package com.spaceagent.platform.tooling.api;
import com.spaceagent.platform.shared.api.AdministrationUsage.*;
public interface ToolingAdministrationUsageApi {
    Summary summary(Filter filter);
    History history(Filter filter);
}
