package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.context.ExecutionContext;

import java.util.Optional;

/**
 * Runtime counterpart of a {@link NodeDefinition}: evaluates the context, applies the routing
 * table or delegate, and selects the target {@link io.provisionlabs.agentsgraph.config.EdgeDefinition}.
 * Falls back to {@code fallback_edge_id} whenever no rule matches or the delegate misbehaves,
 * guaranteeing every evaluation produces a decision.
 */
public final class Node {

    private final NodeDefinition definition;
    private final ConditionEngine conditionEngine;
    private final RoutingDelegateRegistry delegateRegistry;

    public Node(NodeDefinition definition, ConditionEngine conditionEngine, RoutingDelegateRegistry delegateRegistry) {
        this.definition = definition;
        this.conditionEngine = conditionEngine;
        this.delegateRegistry = delegateRegistry;
    }

    public NodeDefinition getDefinition() {
        return definition;
    }

    public RoutingDecision route(ExecutionContext context) {
        if (definition.getRoutingStrategy() == RoutingStrategy.RULES) {
            return routeByRules(context);
        }
        return routeByDelegate(context);
    }

    private RoutingDecision routeByRules(ExecutionContext context) {
        Optional<String> edgeId = conditionEngine.evaluate(definition.getRoutingTable(), context);
        if (edgeId.isPresent()) {
            return new RoutingDecision(edgeId.get(), 1.0, RoutingSource.RULES);
        }
        return fallbackOrThrow("no routing_table rule matched");
    }

    private RoutingDecision routeByDelegate(ExecutionContext context) {
        try {
            RoutingDelegate delegate = delegateRegistry.resolve(definition.getRoutingDelegate().getRef());
            DelegateResult result = delegate.decide(context, definition.getRoutingDelegate());
            if (result != null && result.getEdgeId() != null) {
                return new RoutingDecision(result.getEdgeId(), result.getConfidence(), RoutingSource.DELEGATE);
            }
            return fallbackOrThrow("routing delegate returned no edge_id");
        } catch (RuntimeException e) {
            return fallbackOrThrow("routing delegate failed: " + e.getMessage());
        }
    }

    private RoutingDecision fallbackOrThrow(String reason) {
        if (definition.getFallbackEdgeId() != null) {
            return new RoutingDecision(definition.getFallbackEdgeId(), 0.0, RoutingSource.FALLBACK);
        }
        throw new AgentsGraphException("Node '" + definition.getId() + "' could not route context: " + reason);
    }
}
