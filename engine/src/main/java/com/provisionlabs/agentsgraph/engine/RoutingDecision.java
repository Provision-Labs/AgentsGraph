package com.provisionlabs.agentsgraph.engine;

import java.util.Objects;

/** Result of a {@link Node} evaluating its routing table or delegate against a context. */
public final class RoutingDecision {

    private final String nextEdgeId;
    private final double confidence;
    private final RoutingSource source;

    public RoutingDecision(String nextEdgeId, double confidence, RoutingSource source) {
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

    public RoutingSource getSource() {
        return source;
    }

    @Override
    public String toString() {
        return "RoutingDecision{edge=" + nextEdgeId + ", confidence=" + confidence + ", source=" + source + '}';
    }
}
