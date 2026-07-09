package io.provisionlabs.agentsgraph.config.json;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson-friendly mirror of a full {@link io.provisionlabs.agentsgraph.config.GraphDefinition}:
 * the framework's own declarative JSON dialect (nodes + edges, {@code snake_case} keys matching
 * the routing config example in the project README), extended with the per-step
 * {@code output_to_next}/{@code output_to_save} data-flow controls a linear pipeline needs. A
 * pipeline is simply a graph with one node unconditionally routed to one edge - there is no
 * separate "pipeline format" to convert from.
 *
 * <pre>{@code
 * {
 *   "id": "ocr-accounting",
 *   "version": "v1",
 *   "templates": ["file:accountant", "file:default"],
 *   "entry_node_id": "intent_router",
 *   "nodes": [
 *     {
 *       "id": "intent_router",
 *       "routing_strategy": "rules",
 *       "routing_table": {"default": "edge_ocr_pipeline"},
 *       "fallback_edge_id": "edge_ocr_pipeline"
 *     }
 *   ],
 *   "edges": [
 *     {
 *       "id": "edge_ocr_pipeline",
 *       "steps": [
 *         {"id": "step_ocr", "processor_id": "docscan-ocr", "output_to_next": ["json"], "output_to_save": ["tables", "json"]},
 *         {"id": "step_llm", "processor_id": "llm-postprocessor", "output_to_save": ["summary"]}
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 */
public class GraphJson {

    public String id;
    public String version = "v1";
    public List<String> templates = new ArrayList<>();

    @JsonProperty("entry_node_id")
    public String entryNodeId;

    public List<NodeJson> nodes = new ArrayList<>();
    public List<EdgeJson> edges = new ArrayList<>();

    public static class NodeJson {
        public String id;
        public String type = "router";

        @JsonProperty("routing_strategy")
        public String routingStrategy = "rules";

        @JsonProperty("routing_table")
        public Map<String, String> routingTable = new LinkedHashMap<>();

        @JsonProperty("routing_delegate")
        public RoutingDelegateJson routingDelegate;

        @JsonProperty("input_mapping")
        public Map<String, String> inputMapping = new LinkedHashMap<>();

        @JsonProperty("output_mapping")
        public Map<String, String> outputMapping = new LinkedHashMap<>();

        @JsonProperty("fallback_edge_id")
        public String fallbackEdgeId;
    }

    public static class RoutingDelegateJson {
        public String type;
        public String ref;
        public Map<String, Object> params = new LinkedHashMap<>();

        @JsonProperty("timeout_ms")
        public long timeoutMs;
    }

    public static class EdgeJson {
        public String id;
        public List<StepJson> steps = new ArrayList<>();

        @JsonProperty("output_mapping")
        public Map<String, String> outputMapping = new LinkedHashMap<>();

        @JsonProperty("tags_to_add")
        public List<String> tagsToAdd = new ArrayList<>();

        @JsonProperty("next_node_id")
        public String nextNodeId;
    }

    public static class StepJson {
        public String id;

        @JsonProperty("processor_id")
        public String processorId;

        public Map<String, Object> params = new LinkedHashMap<>();

        @JsonProperty("output_to_next")
        public List<String> outputToNext = new ArrayList<>();

        @JsonProperty("output_to_save")
        public List<String> outputToSave = new ArrayList<>();
    }
}
