package io.provisionlabs.agentsgraph.engine;

import java.util.Collections;
import java.util.Map;

/** Structured output of a {@link RoutingDelegate}, before it is mapped by {@code output_mapping}. */
public final class DelegateResult {

    private final String edgeId;
    private final double confidence;
    private final Map<String, Object> raw;

    public DelegateResult(String edgeId, double confidence, Map<String, Object> raw) {
        this.edgeId = edgeId;
        this.confidence = confidence;
        this.raw = raw == null ? Collections.emptyMap() : raw;
    }

    public String getEdgeId() {
        return edgeId;
    }

    public double getConfidence() {
        return confidence;
    }

    public Map<String, Object> getRaw() {
        return raw;
    }
}
