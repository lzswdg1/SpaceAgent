package com.spaceagent.platform.runtime.domain;

/** Explains how an immutable per-step assignment was selected. */
public enum ProjectPlanStepAssignmentSource {
    PLAN_DEFAULT,
    STEP_OVERRIDE,
    HANDOFF
}
