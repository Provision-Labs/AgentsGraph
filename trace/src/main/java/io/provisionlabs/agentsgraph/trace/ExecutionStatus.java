package io.provisionlabs.agentsgraph.trace;

/**
 * Lifecycle status of a flow execution, as tracked by the Status &amp; Trace Store layer.
 *
 * <p>The two failure statuses are distinct on purpose:
 * <ul>
 *   <li>{@link #ERROR} - a step pipeline failed, but the graph handled it via the node's
 *       {@code fallback_edge_id}: the flow finished and produced a (fallback) result, yet did
 *       NOT complete its intended path. The failure's details are in
 *       {@link TraceRecord#getError()}.</li>
 *   <li>{@link #FAILED} - the flow aborted: the failure propagated out of the orchestrator to
 *       the caller (no fallback configured, the fallback itself failed, or routing failed with
 *       no fallback). Details are in {@link TraceRecord#getError()} as well.</li>
 * </ul>
 */
public enum ExecutionStatus {
    RUNNING,
    COMPLETED,
    /** Finished via a {@code fallback_edge_id} after a step failure - see the record's {@code error}. */
    ERROR,
    /** Aborted - the failure propagated to the caller; see the record's {@code error}. */
    FAILED,
    PAUSED
}
