package io.provisionlabs.agentsgraph.trace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Reference {@link TraceStore} implementation backed by a concurrent in-memory map. */
public final class InMemoryTraceStore implements TraceStore {

    private final Map<String, TraceRecord> records = new ConcurrentHashMap<>();
    private final Map<String, List<StepTraceRecord>> stepsByFlow = new ConcurrentHashMap<>();

    @Override
    public TraceRecord startFlow(String flowId, String tenantId) {
        return records.computeIfAbsent(flowId, id -> new TraceRecord(id, tenantId));
    }

    @Override
    public void appendEvent(String flowId, ExecutionEvent event) {
        requireRecord(flowId).appendEvent(event);
    }

    @Override
    public void addTags(String flowId, Collection<String> tags) {
        requireRecord(flowId).addTags(tags);
    }

    @Override
    public void updateStatus(String flowId, ExecutionStatus status) {
        requireRecord(flowId).setStatus(status);
    }

    @Override
    public void recordError(String flowId, String error) {
        requireRecord(flowId).setError(error);
    }

    @Override
    public Optional<TraceRecord> find(String flowId) {
        return Optional.ofNullable(records.get(flowId));
    }

    @Override
    public List<TraceRecord> query(Set<String> tags, ExecutionStatus status, String tenantId) {
        List<TraceRecord> matches = new ArrayList<>();
        for (TraceRecord record : records.values()) {
            if (status != null && record.getStatus() != status) {
                continue;
            }
            if (tenantId != null && !tenantId.equals(record.getTenantId())) {
                continue;
            }
            if (tags != null && !record.getTags().containsAll(tags)) {
                continue;
            }
            matches.add(record);
        }
        return matches;
    }

    @Override
    public void appendStep(StepTraceRecord record) {
        stepsByFlow.computeIfAbsent(record.getFlowId(),
                id -> java.util.Collections.synchronizedList(new ArrayList<>())).add(record);
    }

    @Override
    public List<StepTraceRecord> findSteps(String flowId) {
        List<StepTraceRecord> steps = stepsByFlow.getOrDefault(flowId, List.of());
        synchronized (steps) {
            List<StepTraceRecord> sorted = new ArrayList<>(steps);
            sorted.sort(java.util.Comparator.comparingLong(StepTraceRecord::getSeq));
            return sorted;
        }
    }

    @Override
    public Optional<StepTraceRecord> findStep(String flowId, long seq) {
        return findSteps(flowId).stream().filter(record -> record.getSeq() == seq).findFirst();
    }

    @Override
    public long deleteStepsOlderThan(long startedBeforeEpochMillis) {
        long removed = 0;
        for (List<StepTraceRecord> steps : stepsByFlow.values()) {
            synchronized (steps) {
                int before = steps.size();
                steps.removeIf(record -> record.getStartedAtMillis() < startedBeforeEpochMillis);
                removed += before - steps.size();
            }
        }
        stepsByFlow.values().removeIf(List::isEmpty);
        return removed;
    }

    private TraceRecord requireRecord(String flowId) {
        TraceRecord record = records.get(flowId);
        if (record == null) {
            throw new IllegalArgumentException("No trace record started for flow '" + flowId + "'");
        }
        return record;
    }
}
