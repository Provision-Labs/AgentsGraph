package io.provisionlabs.agentsgraph.trace;

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

    /**
     * Records the failure that put the flow into {@link ExecutionStatus#ERROR}/{@link
     * ExecutionStatus#FAILED} - typically the exception's stack trace (see
     * {@link TraceRecord#getError()}).
     */
    void recordError(String flowId, String error);

    Optional<TraceRecord> find(String flowId);

    /** Executions matching all of the given filters; a {@code null} filter is treated as "any". */
    List<TraceRecord> query(Set<String> tags, ExecutionStatus status, String tenantId);

    // --- Step-level debug trace ({@link StepTraceRecord}) -------------------------------------
    // Written only when a flow runs in debug mode; the normal execution path never calls these,
    // so they add zero overhead to production traffic.

    void appendStep(StepTraceRecord record);

    /** Every recorded step of {@code flowId}, ordered by {@link StepTraceRecord#getSeq()}. */
    List<StepTraceRecord> findSteps(String flowId);

    Optional<StepTraceRecord> findStep(String flowId, long seq);

    /**
     * Retention for the (heavyweight) step-level debug trace: deletes step records whose
     * {@code startedAtMillis} is older; returns how many were removed. Flow-level records are
     * not touched.
     */
    long deleteStepsOlderThan(long startedBeforeEpochMillis);
}
