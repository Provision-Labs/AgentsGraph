package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.RoutingDelegateConfig;
import io.provisionlabs.agentsgraph.context.ExecutionContext;

/**
 * External decision-maker plugged into a {@code classificator} node: an ML model service, a
 * hand-written Java routing service, a human-in-the-loop queue, an LLM intent router, etc.
 * Registered under {@link RoutingDelegateConfig#getRef()} in a {@link RoutingDelegateRegistry}.
 */
public interface RoutingDelegate {

    DelegateResult decide(ExecutionContext context, RoutingDelegateConfig config);
}
