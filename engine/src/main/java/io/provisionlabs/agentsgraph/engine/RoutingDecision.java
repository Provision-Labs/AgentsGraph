package io.provisionlabs.agentsgraph.engine;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Result of a {@link Node} evaluating its routing table or delegate against a context. */
public final class RoutingDecision {

    private final String nextEdgeId;
    private final double confidence;
    private final RoutingSource source;
    private final Map<String, Object> delegateOutput;

    public RoutingDecision(String nextEdgeId, double confidence, RoutingSource source) {
        this(nextEdgeId, confidence, source, Collections.emptyMap());
    }

    public RoutingDecision(String nextEdgeId, double confidence, RoutingSource source,
                            Map<String, Object> delegateOutput) {
        this.nextEdgeId = Objects.requireNonNull(nextEdgeId, "nextEdgeId");
        this.confidence = confidence;
        this.source = Objects.requireNonNull(source, "source");
        this.delegateOutput = delegateOutput == null ? Collections.emptyMap() : delegateOutput;
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

    /**
     * The {@link RoutingDelegate}'s raw {@link DelegateResult#getRaw()} (empty for
     * {@link RoutingSource#RULES}/{@link RoutingSource#FALLBACK}), before {@code output_mapping}
     * projects it into the context - see {@link RuntimeOrchestrator}.
     */
    public Map<String, Object> getDelegateOutput() {
        return delegateOutput;
    }

    @Override
    public String toString() {
        return "RoutingDecision{edge=" + nextEdgeId + ", confidence=" + confidence + ", source=" + source + '}';
    }
}
