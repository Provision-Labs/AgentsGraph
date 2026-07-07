package com.provisionlabs.agentsgraph.config.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.provisionlabs.agentsgraph.config.EdgeDefinition;
import com.provisionlabs.agentsgraph.config.GraphDefinition;
import com.provisionlabs.agentsgraph.config.NodeDefinition;
import com.provisionlabs.agentsgraph.config.ProcessorDefinition;
import com.provisionlabs.agentsgraph.config.RoutingStrategy;
import com.provisionlabs.agentsgraph.config.StepDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between the JSON shapes used by the legacy docscan-pipeline config store
 * ({@code plb_pipeline_config.config} / {@code plb_pipeline_processor}) and this framework's own
 * {@link GraphDefinition} / {@link ProcessorDefinition}, so existing pipeline configs can be
 * loaded into AgentsGraph without rewriting them.
 *
 * <p>A legacy pipeline is a flat, unbranching list of steps, so it maps onto a graph with exactly
 * one {@link NodeDefinition} (id {@value #ENTRY_NODE_ID}, unconditionally routed via {@code
 * default}) leading to exactly one {@link EdgeDefinition} (id {@value #MAIN_EDGE_ID}) carrying the
 * step pipeline.
 */
public final class PipelineJsonMapper {

    public static final String ENTRY_NODE_ID = "entry";
    public static final String MAIN_EDGE_ID = "edge_main";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PipelineJsonMapper() {
    }

    public static GraphDefinition toGraphDefinition(String pipelineConfigJson, String version) {
        PipelineConfigJson json = readValue(pipelineConfigJson, PipelineConfigJson.class);
        return toGraphDefinition(json, version);
    }

    public static GraphDefinition toGraphDefinition(PipelineConfigJson json, String version) {
        EdgeDefinition.Builder edgeBuilder = EdgeDefinition.builder(MAIN_EDGE_ID);
        int i = 0;
        for (PipelineConfigJson.StepConfigJson step : json.steps) {
            edgeBuilder.step(new StepDefinition(
                    "step_" + (i++), step.processorId, step.params, step.outputToNext, step.outputToSave));
        }

        NodeDefinition entryNode = NodeDefinition.builder(ENTRY_NODE_ID)
                .type("router")
                .routingStrategy(RoutingStrategy.RULES)
                .routingRule("default", MAIN_EDGE_ID)
                .fallbackEdgeId(MAIN_EDGE_ID)
                .build();

        return GraphDefinition.builder(json.id, version)
                .entryNodeId(ENTRY_NODE_ID)
                .node(entryNode)
                .edge(edgeBuilder.build())
                .templates(json.templates)
                .build();
    }

    /** Inverse of {@link #toGraphDefinition}; assumes the graph follows that single node/edge shape. */
    public static String toJson(GraphDefinition graph) {
        PipelineConfigJson json = new PipelineConfigJson();
        json.id = graph.getId();
        json.name = graph.getId();
        json.templates = new ArrayList<>(graph.getTemplates());

        EdgeDefinition edge = graph.getEdge(MAIN_EDGE_ID);
        for (StepDefinition step : edge.getSteps()) {
            PipelineConfigJson.StepConfigJson stepJson = new PipelineConfigJson.StepConfigJson();
            stepJson.processorId = step.getProcessorRef();
            stepJson.params = new LinkedHashMap<>(step.getParams());
            stepJson.outputToNext = new ArrayList<>(step.getOutputToNext());
            stepJson.outputToSave = new ArrayList<>(step.getOutputToSave());
            json.steps.add(stepJson);
        }
        return writeValue(json);
    }

    public static List<ProcessorDefinition> toProcessorDefinitions(String processorsJsonArray) {
        List<ProcessorDefinitionJson> parsed = readValue(
                processorsJsonArray, new TypeReference<List<ProcessorDefinitionJson>>() { });
        List<ProcessorDefinition> result = new ArrayList<>();
        for (ProcessorDefinitionJson json : parsed) {
            result.add(toProcessorDefinition(json));
        }
        return result;
    }

    public static ProcessorDefinition toProcessorDefinition(ProcessorDefinitionJson json) {
        return new ProcessorDefinition(json.id, json.name, json.isExternal, json.instanceClass, paramsOf(json));
    }

    /** {@code params} may arrive as a nested JSON object or as a raw JSON string (legacy DB column). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(ProcessorDefinitionJson json) {
        if (json.params == null) {
            return Map.of();
        }
        if (json.params instanceof String) {
            String raw = ((String) json.params).trim();
            if (raw.isEmpty()) {
                return Map.of();
            }
            return readValue(raw, new TypeReference<Map<String, Object>>() { });
        }
        if (json.params instanceof Map) {
            return (Map<String, Object>) json.params;
        }
        throw new IllegalArgumentException("Unsupported params type: " + json.params.getClass());
    }

    /** Serializes a processor's {@code params} map alone, e.g. for a DB {@code TEXT} column. */
    public static String toParamsJson(Map<String, Object> params) {
        return writeValue(params == null ? Map.of() : params);
    }

    /** Inverse of {@link #toParamsJson}; treats {@code null}/blank input as an empty map. */
    public static Map<String, Object> fromParamsJson(String paramsJson) {
        if (paramsJson == null || paramsJson.trim().isEmpty()) {
            return Map.of();
        }
        return readValue(paramsJson, new TypeReference<Map<String, Object>>() { });
    }

    public static String toJson(ProcessorDefinition definition) {
        ProcessorDefinitionJson json = new ProcessorDefinitionJson();
        json.id = definition.getId();
        json.name = definition.getName();
        json.isExternal = definition.isExternal();
        json.instanceClass = definition.getInstanceClass();
        json.params = definition.getParams();
        return writeValue(json);
    }

    private static <T> T readValue(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON as " + type.getSimpleName(), e);
        }
    }

    private static <T> T readValue(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON as " + type.getType(), e);
        }
    }

    private static String writeValue(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize " + value.getClass(), e);
        }
    }
}
