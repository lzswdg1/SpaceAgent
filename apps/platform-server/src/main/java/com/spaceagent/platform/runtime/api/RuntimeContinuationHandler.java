package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;

/** Inverted extension point: Runtime dispatches without importing the owning module. */
public interface RuntimeContinuationHandler {

    RuntimeContinuationType type();

    void handle(ContinuationHandlerContext context);

    default boolean completesRun() { return true; }

    record ContinuationHandlerContext(
            RuntimeCoordinationApplicationApi.ContinuationView continuation,
            RuntimeCoordinationApplicationApi.LeaseView lease) {}
}
