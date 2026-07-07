package com.provisionlabs.agentsgraph.engine;

/** How a {@link RoutingDecision} was produced. */
public enum RoutingSource {
    RULES,
    DELEGATE,
    FALLBACK
}
