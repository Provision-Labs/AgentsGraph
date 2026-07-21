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
    private final String failureReason;
    private final Throwable failure;

    public RoutingDecision(String nextEdgeId, double confidence, RoutingSource source) {
        this(nextEdgeId, confidence, source, Collections.emptyMap());
    }

    public RoutingDecision(String nextEdgeId, double confidence, RoutingSource source,
                            Map<String, Object> delegateOutput) {
        this(nextEdgeId, confidence, source, delegateOutput, null, null);
    }

    /** A {@link RoutingSource#FALLBACK} decision carrying WHY routing fell back - see {@link #getFailure()}. */
    public static RoutingDecision fallback(String fallbackEdgeId, String failureReason, Throwable failure) {
        return new RoutingDecision(fallbackEdgeId, 0.0, RoutingSource.FALLBACK,
                Collections.emptyMap(), failureReason, failure);
    }

    private RoutingDecision(String nextEdgeId, double confidence, RoutingSource source,
                             Map<String, Object> delegateOutput, String failureReason, Throwable failure) {
        this.nextEdgeId = Objects.requireNonNull(nextEdgeId, "nextEdgeId");
        this.confidence = confidence;
        this.source = Objects.requireNonNull(source, "source");
        this.delegateOutput = delegateOutput == null ? Collections.emptyMap() : delegateOutput;
        this.failureReason = failureReason;
        this.failure = failure;
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

    /**
     * Why routing fell back (only set for {@link RoutingSource#FALLBACK} decisions) - e.g.
     * "no routing_table rule matched" or "routing delegate failed: ...".
     */
    public String getFailureReason() {
        return failureReason;
    }

    /**
     * The exception that made routing fall back, when there was one (a throwing delegate) -
     * preserved so the orchestrator can log it and persist its stack trace into the trace
     * record's {@code error} field instead of losing it in wrapper layers.
     */
    public Throwable getFailure() {
        return failure;
    }

    @Override
    public String toString() {
        return "RoutingDecision{edge=" + nextEdgeId + ", confidence=" + confidence + ", source=" + source + '}';
    }
}
