package com.provisionlabs.agentsgraph.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Reference {@link TraceStore} implementation backed by a concurrent in-memory map. */
public final class InMemoryTraceStore implements TraceStore {

    private final Map<String, TraceRecord> records = new ConcurrentHashMap<>();

    @Override
    public TraceRecord startFlow(String flowId, String tenantId) {
        return records.computeIfAbsent(flowId, id -> new TraceRecord(id, tenantId));
    }

    @Override
    public void appendEvent(String flowId, ExecutionEvent event) {
        requireRecord(flowId).appendEvent(event);
    }

    @Override
    public void updateStatus(String flowId, ExecutionStatus status) {
        requireRecord(flowId).setStatus(status);
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

    private TraceRecord requireRecord(String flowId) {
        TraceRecord record = records.get(flowId);
        if (record == null) {
            throw new IllegalArgumentException("No trace record started for flow '" + flowId + "'");
        }
        return record;
    }
}
