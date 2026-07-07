package com.provisionlabs.agentsgraph.config;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative description of a {@code routing_delegate}: an external module (model service,
 * Java service, human-in-the-loop queue, ...) that a {@link NodeDefinition} can delegate
 * routing decisions to when {@code routing_strategy} is {@link RoutingStrategy#CLASSIFICATOR}.
 */
public final class RoutingDelegateConfig {

    private final String type;
    private final String ref;
    private final Map<String, Object> params;
    private final long timeoutMs;

    public RoutingDelegateConfig(String type, String ref, Map<String, Object> params, long timeoutMs) {
        this.type = Objects.requireNonNull(type, "type");
        this.ref = Objects.requireNonNull(ref, "ref");
        this.params = params == null ? Collections.emptyMap() : Collections.unmodifiableMap(params);
        this.timeoutMs = timeoutMs;
    }

    public String getType() {
        return type;
    }

    public String getRef() {
        return ref;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
