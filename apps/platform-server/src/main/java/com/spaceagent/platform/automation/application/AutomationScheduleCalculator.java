package com.spaceagent.platform.automation.application;

import com.spaceagent.platform.automation.domain.AutomationScheduleType;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Mature Spring cron parser plus explicit IANA timezone calculation. */
@Component
public class AutomationScheduleCalculator {

    public String normalizeCron(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cronExpr is required for periodic schedules");
        }
        String normalized = expression.trim().replaceAll("\\s+", " ");
        if (normalized.split(" ").length == 5) {
            normalized = "0 " + normalized;
        }
        CronExpression.parse(normalized);
        return normalized;
    }

    public String validateTimezone(String timezone) {
        String value = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.trim();
        ZoneId.of(value);
        return value;
    }

    public Instant initialFireAt(
            AutomationScheduleType type,
            String cronExpression,
            Instant scheduledAt,
            String timezone,
            Instant databaseNow) {
        if (type == AutomationScheduleType.ONE_TIME) {
            if (scheduledAt == null || !scheduledAt.isAfter(databaseNow)) {
                throw new IllegalArgumentException("scheduledAt must be in the future");
            }
            return scheduledAt;
        }
        return nextCron(cronExpression, timezone, databaseNow);
    }

    public Instant nextCron(String cronExpression, String timezone, Instant after) {
        ZoneId zone = ZoneId.of(validateTimezone(timezone));
        ZonedDateTime next = CronExpression.parse(normalizeCron(cronExpression))
                .next(ZonedDateTime.ofInstant(after, zone));
        if (next == null) {
            throw new IllegalArgumentException("cronExpr has no next occurrence");
        }
        return next.toInstant();
    }
}
