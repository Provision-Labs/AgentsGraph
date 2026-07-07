package com.provisionlabs.agentsgraph.trace;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Access point for the Status & Trace Store layer: append-only execution events plus queries. */
public interface TraceStore {

    TraceRecord startFlow(String flowId, String tenantId);

    void appendEvent(String flowId, ExecutionEvent event);

    void updateStatus(String flowId, ExecutionStatus status);

    Optional<TraceRecord> find(String flowId);

    /** Executions matching all of the given filters; a {@code null} filter is treated as "any". */
    List<TraceRecord> query(Set<String> tags, ExecutionStatus status, String tenantId);
}
