package io.provisionlabs.agentsgraph.config.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.GraphDefinition;
import io.provisionlabs.agentsgraph.config.NodeDefinition;
import io.provisionlabs.agentsgraph.config.RoutingDelegateConfig;
import io.provisionlabs.agentsgraph.config.RoutingStrategy;
import io.provisionlabs.agentsgraph.config.StepDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a {@link GraphDefinition} to/from its JSON representation ({@link GraphJson}). This is
 * the framework's <em>only</em> graph JSON format: a linear pipeline is authored as a graph with
 * one node unconditionally routed to one edge (see {@code examples/graphs/ocr-accounting.json}),
 * not as a separate legacy shape translated into a graph.
 */
public final class GraphJsonMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private GraphJsonMapper() {
    }

    public static GraphDefinition fromJson(String json) {
        GraphJson graphJson = readValue(json);
        return toGraphDefinition(graphJson);
    }

    public static GraphDefinition toGraphDefinition(GraphJson json) {
        GraphDefinition.Builder graphBuilder = GraphDefinition.builder(json.id, json.version)
                .entryNodeId(json.entryNodeId)
                .templates(json.templates);

        for (GraphJson.NodeJson nodeJson : json.nodes) {
            graphBuilder.node(toNodeDefinition(nodeJson));
        }
        for (GraphJson.EdgeJson edgeJson : json.edges) {
            graphBuilder.edge(toEdgeDefinition(edgeJson));
        }
        return graphBuilder.build();
    }

    public static String toJson(GraphDefinition graph) {
        GraphJson json = new GraphJson();
        json.id = graph.getId();
        json.version = graph.getVersion();
        json.templates = new ArrayList<>(graph.getTemplates());
        json.entryNodeId = graph.getEntryNodeId();

        for (NodeDefinition node : graph.getNodes().values()) {
            json.nodes.add(toNodeJson(node));
        }
        for (EdgeDefinition edge : graph.getEdges().values()) {
            json.edges.add(toEdgeJson(edge));
        }
        return writeValue(json);
    }

    private static NodeDefinition toNodeDefinition(GraphJson.NodeJson json) {
        NodeDefinition.Builder builder = NodeDefinition.builder(json.id)
                .type(json.type)
                .routingStrategy(parseRoutingStrategy(json.routingStrategy));

        for (Map.Entry<String, String> rule : json.routingTable.entrySet()) {
            builder.routingRule(rule.getKey(), rule.getValue());
        }
        for (Map.Entry<String, String> mapping : json.inputMapping.entrySet()) {
            builder.inputMapping(mapping.getKey(), mapping.getValue());
        }
        for (Map.Entry<String, String> mapping : json.outputMapping.entrySet()) {
            builder.outputMapping(mapping.getKey(), mapping.getValue());
        }
        if (json.routingDelegate != null) {
            builder.routingDelegate(new RoutingDelegateConfig(
                    json.routingDelegate.type, json.routingDelegate.ref,
                    json.routingDelegate.params, json.routingDelegate.timeoutMs));
        }
        builder.fallbackEdgeId(json.fallbackEdgeId);
        return builder.build();
    }

    private static GraphJson.NodeJson toNodeJson(NodeDefinition node) {
        GraphJson.NodeJson json = new GraphJson.NodeJson();
        json.id = node.getId();
        json.type = node.getType();
        json.routingStrategy = node.getRoutingStrategy().name().toLowerCase(Locale.ROOT);
        json.routingTable = new LinkedHashMap<>(node.getRoutingTable());
        json.inputMapping = new LinkedHashMap<>(node.getInputMapping());
        json.outputMapping = new LinkedHashMap<>(node.getOutputMapping());
        json.fallbackEdgeId = node.getFallbackEdgeId();

        RoutingDelegateConfig delegate = node.getRoutingDelegate();
        if (delegate != null) {
            GraphJson.RoutingDelegateJson delegateJson = new GraphJson.RoutingDelegateJson();
            delegateJson.type = delegate.getType();
            delegateJson.ref = delegate.getRef();
            delegateJson.params = new LinkedHashMap<>(delegate.getParams());
            delegateJson.timeoutMs = delegate.getTimeoutMs();
            json.routingDelegate = delegateJson;
        }
        return json;
    }

    private static EdgeDefinition toEdgeDefinition(GraphJson.EdgeJson json) {
        EdgeDefinition.Builder builder = EdgeDefinition.builder(json.id).nextNodeId(json.nextNodeId);

        int index = 0;
        for (GraphJson.StepJson stepJson : json.steps) {
            String stepId = stepJson.id != null ? stepJson.id : "step_" + (index++);
            builder.step(new StepDefinition(
                    stepId, stepJson.processorId, stepJson.params, stepJson.outputToNext, stepJson.outputToSave));
        }
        for (Map.Entry<String, String> mapping : json.outputMapping.entrySet()) {
            builder.outputMapping(mapping.getKey(), mapping.getValue());
        }
        for (String tag : json.tagsToAdd) {
            builder.tagToAdd(tag);
        }
        return builder.build();
    }

    private static GraphJson.EdgeJson toEdgeJson(EdgeDefinition edge) {
        GraphJson.EdgeJson json = new GraphJson.EdgeJson();
        json.id = edge.getId();
        json.outputMapping = new LinkedHashMap<>(edge.getOutputMapping());
        json.tagsToAdd = new ArrayList<>(edge.getTagsToAdd());
        json.nextNodeId = edge.getNextNodeId();

        for (StepDefinition step : edge.getSteps()) {
            GraphJson.StepJson stepJson = new GraphJson.StepJson();
            stepJson.id = step.getId();
            stepJson.processorId = step.getProcessorRef();
            stepJson.params = new LinkedHashMap<>(step.getParams());
            stepJson.outputToNext = new ArrayList<>(step.getOutputToNext());
            stepJson.outputToSave = new ArrayList<>(step.getOutputToSave());
            json.steps.add(stepJson);
        }
        return json;
    }

    private static RoutingStrategy parseRoutingStrategy(String value) {
        if ("classificator".equalsIgnoreCase(value) || "classifier".equalsIgnoreCase(value)) {
            return RoutingStrategy.CLASSIFICATOR;
        }
        return RoutingStrategy.RULES;
    }

    private static GraphJson readValue(String json) {
        try {
            return MAPPER.readValue(json, GraphJson.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse graph JSON", e);
        }
    }

    private static String writeValue(GraphJson json) {
        try {
            return MAPPER.writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize graph JSON", e);
        }
    }
}
