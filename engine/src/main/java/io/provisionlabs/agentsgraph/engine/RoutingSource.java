package io.provisionlabs.agentsgraph.engine;

/** How a {@link RoutingDecision} was produced. */
public enum RoutingSource {
    RULES,
    DELEGATE,
    FALLBACK,
    /** Synthetic decision seeding a {@code resumeFrom} run - no routing was evaluated. */
    RESUME
}
