package io.provisionlabs.agentsgraph.trace;

import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.time.Instant;
import java.util.Objects;

/** One audit-log entry: a single Node routing decision and the context snapshot after its Edge ran. */
public final class ExecutionEvent {

    private final String nodeId;
    private final RoutingOutcome routingDecision;
    private final Instant timestamp;
    private final ExecutionContext contextSnapshot;

    public ExecutionEvent(String nodeId, RoutingOutcome routingDecision, Instant timestamp,
                           ExecutionContext contextSnapshot) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.routingDecision = Objects.requireNonNull(routingDecision, "routingDecision");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.contextSnapshot = Objects.requireNonNull(contextSnapshot, "contextSnapshot");
    }

    public String getNodeId() {
        return nodeId;
    }

    public RoutingOutcome getRoutingDecision() {
        return routingDecision;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public ExecutionContext getContextSnapshot() {
        return contextSnapshot;
    }
}
