package com.provisionlabs.agentsgraph.trace;

/** Lifecycle status of a flow execution, as tracked by the Status & Trace Store layer. */
public enum ExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    PAUSED
}
