package com.spaceagent.platform.observability.infrastructure.memory;

import com.spaceagent.platform.observability.domain.TraceDetail;
import com.spaceagent.platform.observability.domain.TraceFilter;
import com.spaceagent.platform.observability.domain.TracePage;
import com.spaceagent.platform.observability.domain.TraceStats;
import com.spaceagent.platform.observability.domain.TraceStatus;
import com.spaceagent.platform.observability.domain.TraceSummary;
import com.spaceagent.platform.observability.domain.TracingQueryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryTracingQueryRepository implements TracingQueryRepository {

    private final Map<String, TraceDetail> traces = new ConcurrentHashMap<>();

    /** Test/dev projection seed; production traces are derived exclusively from SQL views. */
    public void put(TraceDetail trace) {
        traces.put(trace.trace().id(), trace);
    }

    public void clear() {
        traces.clear();
    }

    @Override
    public TracePage search(TraceFilter filter, int limit, int offset) {
        List<TraceSummary> matches = matching(filter);
        return new TracePage(
                matches.stream().skip(offset).limit(limit).toList(),
                matches.size(), limit, offset);
    }

    @Override
    public Optional<TraceDetail> find(String tenantId, String ownerId, String traceId) {
        return Optional.ofNullable(traces.get(traceId))
                .filter(value -> value.trace().organizationId().equals(tenantId))
                .filter(value -> value.trace().userId().equals(ownerId));
    }

    @Override
    public TraceStats stats(TraceFilter filter) {
        List<TraceSummary> values = matching(filter);
        List<Long> durations = values.stream().map(TraceSummary::durationMs)
                .filter(java.util.Objects::nonNull).sorted().toList();
        return new TraceStats(
                values.size(), count(values, TraceStatus.SUCCESS), count(values, TraceStatus.ERROR),
                count(values, TraceStatus.ABORTED) + count(values, TraceStatus.RETRY_ABORTED),
                durations.stream().mapToLong(Long::longValue).average().orElse(0),
                percentile(durations, 0.5), percentile(durations, 0.95), 0,
                values.stream().mapToLong(TraceSummary::totalTokens).sum(), null);
    }

    private List<TraceSummary> matching(TraceFilter filter) {
        return traces.values().stream().map(TraceDetail::trace)
                .filter(value -> value.organizationId().equals(filter.tenantId()))
                .filter(value -> value.userId().equals(filter.ownerId()))
                .filter(value -> filter.status() == null || value.status() == filter.status())
                .filter(value -> filter.agentId() == null
                        || filter.agentId().equals(value.agentId()))
                .filter(value -> filter.sessionId() == null
                        || filter.sessionId().equals(value.sessionId()))
                .filter(value -> matchesKeyword(value, filter.keyword()))
                .filter(value -> filter.minDurationMs() == null
                        || value.durationMs() != null
                        && value.durationMs() >= filter.minDurationMs())
                .filter(value -> filter.maxDurationMs() == null
                        || value.durationMs() != null
                        && value.durationMs() <= filter.maxDurationMs())
                .filter(value -> filter.since() == null
                        || !value.startTime().isBefore(filter.since()))
                .filter(value -> filter.until() == null
                        || !value.startTime().isAfter(filter.until()))
                .sorted(Comparator.comparing(TraceSummary::startTime).reversed()
                        .thenComparing(TraceSummary::id))
                .toList();
    }

    private static boolean matchesKeyword(TraceSummary value, String keyword) {
        if (keyword == null) {
            return true;
        }
        String needle = keyword.toLowerCase();
        return List.of(value.id(), nullable(value.agentName()), nullable(value.sessionName()),
                        nullable(value.errorMessage())).stream()
                .anyMatch(item -> item.toLowerCase().contains(needle));
    }

    private static long count(List<TraceSummary> values, TraceStatus status) {
        return values.stream().filter(value -> value.status() == status).count();
    }

    private static double percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }
}
