package io.provisionlabs.agentsgraph.trace;

import java.util.Objects;

/**
 * Trace-layer summary of a routing decision made by the Runtime Orchestrator. Deliberately
 * independent of the {@code engine} module's live routing types so that {@code agentsgraph-trace}
 * has no compile dependency on {@code agentsgraph-engine} (the orchestrator depends on the trace
 * store to record events, not the other way around).
 */
public final class RoutingOutcome {

    private final String nextEdgeId;
    private final double confidence;
    private final String source;

    public RoutingOutcome(String nextEdgeId, double confidence, String source) {
        this.nextEdgeId = Objects.requireNonNull(nextEdgeId, "nextEdgeId");
        this.confidence = confidence;
        this.source = Objects.requireNonNull(source, "source");
    }

    public String getNextEdgeId() {
        return nextEdgeId;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getSource() {
        return source;
    }

    @Override
    public String toString() {
        return "RoutingOutcome{edge=" + nextEdgeId + ", confidence=" + confidence + ", source=" + source + '}';
    }
}
