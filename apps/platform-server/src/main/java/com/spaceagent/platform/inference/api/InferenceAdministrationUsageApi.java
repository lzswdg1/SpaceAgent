package com.spaceagent.platform.inference.api;
import com.spaceagent.platform.shared.api.AdministrationUsage.*;
public interface InferenceAdministrationUsageApi {
    Summary summary(String kind,Filter filter);
    History history(String kind,Filter filter);
}
