package com.spaceagent.platform.automation.domain;

import java.time.Instant;

/** Schedule row locked by the repository together with authoritative database time. */
public record DueAutomationSchedule(AutomationSchedule schedule, Instant databaseNow) {}
