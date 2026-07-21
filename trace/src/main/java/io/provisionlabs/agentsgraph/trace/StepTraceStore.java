package io.provisionlabs.agentsgraph.trace;

import java.util.List;
import java.util.Optional;

/**
 * Storage for the step-level debug trace ({@link StepTraceRecord}). Only written when a flow runs
 * in debug mode - the normal execution path never touches this store, so it adds zero overhead
 * to production traffic.
 */
public interface StepTraceStore {

    void append(StepTraceRecord record);

    /** Every step of {@code flowId}, ordered by {@link StepTraceRecord#getSeq()}. */
    List<StepTraceRecord> findByFlow(String flowId);

    Optional<StepTraceRecord> find(String flowId, long seq);

    /** Retention: deletes records whose {@code startedAtMillis} is older; returns how many were removed. */
    long deleteOlderThan(long startedBeforeEpochMillis);
}
