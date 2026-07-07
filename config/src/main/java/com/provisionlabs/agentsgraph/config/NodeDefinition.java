package com.provisionlabs.agentsgraph.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A {@code Node}: a decision router / AI classifier as described in the Config Store layer.
 * Evaluates the {@link com.provisionlabs.agentsgraph.context.ExecutionContext} and selects
 * the next {@link EdgeDefinition} to execute, either via a declarative {@code routing_table}
 * ({@link RoutingStrategy#RULES}) or a delegated classifier ({@link RoutingStrategy#CLASSIFICATOR}).
 */
public final class NodeDefinition {

    private final String id;
    private final String type;
    private final RoutingStrategy routingStrategy;
    private final Map<String, String> routingTable;
    private final RoutingDelegateConfig routingDelegate;
    private final Map<String, String> inputMapping;
    private final Map<String, String> outputMapping;
    private final String fallbackEdgeId;

    private NodeDefinition(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.type = Objects.requireNonNull(builder.type, "type");
        this.routingStrategy = Objects.requireNonNull(builder.routingStrategy, "routingStrategy");
        this.routingTable = Collections.unmodifiableMap(new LinkedHashMap<>(builder.routingTable));
        this.routingDelegate = builder.routingDelegate;
        this.inputMapping = Collections.unmodifiableMap(new LinkedHashMap<>(builder.inputMapping));
        this.outputMapping = Collections.unmodifiableMap(new LinkedHashMap<>(builder.outputMapping));
        this.fallbackEdgeId = builder.fallbackEdgeId;

        if (routingStrategy == RoutingStrategy.CLASSIFICATOR && routingDelegate == null) {
            throw new IllegalArgumentException(
                    "Node '" + id + "' declares routing_strategy=classificator but has no routing_delegate");
        }
        if (routingStrategy == RoutingStrategy.RULES && routingTable.isEmpty()) {
            throw new IllegalArgumentException(
                    "Node '" + id + "' declares routing_strategy=rules but has an empty routing_table");
        }
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public RoutingStrategy getRoutingStrategy() {
        return routingStrategy;
    }

    public Map<String, String> getRoutingTable() {
        return routingTable;
    }

    public RoutingDelegateConfig getRoutingDelegate() {
        return routingDelegate;
    }

    public Map<String, String> getInputMapping() {
        return inputMapping;
    }

    public Map<String, String> getOutputMapping() {
        return outputMapping;
    }

    public String getFallbackEdgeId() {
        return fallbackEdgeId;
    }

    public static final class Builder {
        private final String id;
        private String type = "router";
        private RoutingStrategy routingStrategy = RoutingStrategy.RULES;
        private final Map<String, String> routingTable = new LinkedHashMap<>();
        private RoutingDelegateConfig routingDelegate;
        private final Map<String, String> inputMapping = new LinkedHashMap<>();
        private final Map<String, String> outputMapping = new LinkedHashMap<>();
        private String fallbackEdgeId;

        private Builder(String id) {
            this.id = id;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder routingStrategy(RoutingStrategy routingStrategy) {
            this.routingStrategy = routingStrategy;
            return this;
        }

        public Builder routingRule(String condition, String edgeId) {
            this.routingTable.put(condition, edgeId);
            return this;
        }

        public Builder routingDelegate(RoutingDelegateConfig routingDelegate) {
            this.routingDelegate = routingDelegate;
            return this;
        }

        public Builder inputMapping(String from, String to) {
            this.inputMapping.put(from, to);
            return this;
        }

        public Builder outputMapping(String from, String to) {
            this.outputMapping.put(from, to);
            return this;
        }

        public Builder fallbackEdgeId(String fallbackEdgeId) {
            this.fallbackEdgeId = fallbackEdgeId;
            return this;
        }

        public NodeDefinition build() {
            return new NodeDefinition(this);
        }
    }
}
