package com.provisionlabs.agentsgraph.trace;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Access point for the Status & Trace Store layer: append-only execution events plus queries.
 *
 * <p>All mutations go through dedicated methods (rather than mutating a {@link TraceRecord}
 * returned by {@link #find}) so that implementations backed by a database, and not just plain
 * in-memory maps, can write changes through correctly.
 */
public interface TraceStore {

    TraceRecord startFlow(String flowId, String tenantId);

    void appendEvent(String flowId, ExecutionEvent event);

    void addTags(String flowId, Collection<String> tags);

    void updateStatus(String flowId, ExecutionStatus status);

    Optional<TraceRecord> find(String flowId);

    /** Executions matching all of the given filters; a {@code null} filter is treated as "any". */
    List<TraceRecord> query(Set<String> tags, ExecutionStatus status, String tenantId);
}
