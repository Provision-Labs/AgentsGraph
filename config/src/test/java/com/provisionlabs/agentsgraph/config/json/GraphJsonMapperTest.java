package com.provisionlabs.agentsgraph.config.json;

import com.provisionlabs.agentsgraph.config.EdgeDefinition;
import com.provisionlabs.agentsgraph.config.GraphDefinition;
import com.provisionlabs.agentsgraph.config.RoutingStrategy;
import com.provisionlabs.agentsgraph.config.StepDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphJsonMapperTest {

    // Mirrors examples/graphs/ocr-accounting.json: a pipeline is just a graph with one node
    // unconditionally routed to one edge - there's no separate "pipeline JSON shape" to adapt.
    private static final String OCR_ACCOUNTING_JSON = "{\n" +
            "  \"id\": \"ocr-accounting\",\n" +
            "  \"version\": \"v1\",\n" +
            "  \"templates\": [\"file:accountant\", \"file:default\"],\n" +
            "  \"entry_node_id\": \"intent_router\",\n" +
            "  \"nodes\": [\n" +
            "    {\n" +
            "      \"id\": \"intent_router\",\n" +
            "      \"routing_strategy\": \"rules\",\n" +
            "      \"routing_table\": {\"default\": \"edge_ocr_pipeline\"},\n" +
            "      \"fallback_edge_id\": \"edge_ocr_pipeline\"\n" +
            "    }\n" +
            "  ],\n" +
            "  \"edges\": [\n" +
            "    {\n" +
            "      \"id\": \"edge_ocr_pipeline\",\n" +
            "      \"steps\": [\n" +
            "        {\n" +
            "          \"id\": \"step_ocr\",\n" +
            "          \"processor_id\": \"docscan-ocr\",\n" +
            "          \"params\": {\"processUrl\": \"http://docscan-service/process/{template}\"},\n" +
            "          \"output_to_next\": [\"json\"],\n" +
            "          \"output_to_save\": [\"tables\", \"textItems\", \"json\"]\n" +
            "        },\n" +
            "        {\n" +
            "          \"id\": \"step_llm\",\n" +
            "          \"processor_id\": \"llm-postprocessor\",\n" +
            "          \"output_to_save\": [\"tables\", \"textItems\", \"json\"]\n" +
            "        }\n" +
            "      ],\n" +
            "      \"tags_to_add\": [\"ocr_processed\"]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    @Test
    void parsesAFullGraphNotJustAFlatPipelineShape() {
        GraphDefinition graph = GraphJsonMapper.fromJson(OCR_ACCOUNTING_JSON);

        assertThat(graph.getId()).isEqualTo("ocr-accounting");
        assertThat(graph.getVersion()).isEqualTo("v1");
        assertThat(graph.getTemplates()).containsExactly("file:accountant", "file:default");
        assertThat(graph.getEntryNodeId()).isEqualTo("intent_router");
        assertThat(graph.getNode("intent_router").getRoutingStrategy()).isEqualTo(RoutingStrategy.RULES);
        assertThat(graph.getNode("intent_router").getRoutingTable()).containsEntry("default", "edge_ocr_pipeline");
        assertThat(graph.getNode("intent_router").getFallbackEdgeId()).isEqualTo("edge_ocr_pipeline");

        EdgeDefinition edge = graph.getEdge("edge_ocr_pipeline");
        assertThat(edge.getTagsToAdd()).containsExactly("ocr_processed");
        assertThat(edge.getSteps()).hasSize(2);

        StepDefinition step0 = edge.getSteps().get(0);
        assertThat(step0.getProcessorRef()).isEqualTo("docscan-ocr");
        assertThat(step0.getParams()).containsEntry("processUrl", "http://docscan-service/process/{template}");
        assertThat(step0.getOutputToNext()).containsExactly("json");
        assertThat(step0.getOutputToSave()).containsExactly("tables", "textItems", "json");

        StepDefinition step1 = edge.getSteps().get(1);
        assertThat(step1.getProcessorRef()).isEqualTo("llm-postprocessor");
        assertThat(step1.getOutputToNext()).isEmpty();
    }

    @Test
    void roundTripsGraphToJsonAndBack() {
        GraphDefinition original = GraphJsonMapper.fromJson(OCR_ACCOUNTING_JSON);
        GraphDefinition reparsed = GraphJsonMapper.fromJson(GraphJsonMapper.toJson(original));

        assertThat(reparsed.getId()).isEqualTo(original.getId());
        assertThat(reparsed.getVersion()).isEqualTo(original.getVersion());
        assertThat(reparsed.getTemplates()).isEqualTo(original.getTemplates());
        assertThat(reparsed.getEdge("edge_ocr_pipeline").getSteps())
                .hasSameSizeAs(original.getEdge("edge_ocr_pipeline").getSteps());
    }

    @Test
    void parsesAndRoundTripsARoutingDelegateNode() {
        String json = "{\n" +
                "  \"id\": \"smart_intent_router\",\n" +
                "  \"version\": \"v1\",\n" +
                "  \"entry_node_id\": \"router\",\n" +
                "  \"nodes\": [\n" +
                "    {\n" +
                "      \"id\": \"router\",\n" +
                "      \"routing_strategy\": \"classificator\",\n" +
                "      \"routing_delegate\": {\n" +
                "        \"type\": \"model_service\",\n" +
                "        \"ref\": \"llm_intent_classifier_v4\",\n" +
                "        \"params\": {\"temperature\": 0.1},\n" +
                "        \"timeout_ms\": 3000\n" +
                "      },\n" +
                "      \"fallback_edge_id\": \"pipe_error_handler\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"edges\": [{\"id\": \"pipe_error_handler\"}]\n" +
                "}";

        GraphDefinition graph = GraphJsonMapper.fromJson(json);

        assertThat(graph.getNode("router").getRoutingStrategy()).isEqualTo(RoutingStrategy.CLASSIFICATOR);
        assertThat(graph.getNode("router").getRoutingDelegate().getRef()).isEqualTo("llm_intent_classifier_v4");
        assertThat(graph.getNode("router").getRoutingDelegate().getTimeoutMs()).isEqualTo(3000L);
        assertThat(graph.getNode("router").getRoutingDelegate().getParams()).containsEntry("temperature", 0.1);

        GraphDefinition reparsed = GraphJsonMapper.fromJson(GraphJsonMapper.toJson(graph));
        assertThat(reparsed.getNode("router").getRoutingDelegate().getRef()).isEqualTo("llm_intent_classifier_v4");
    }
}
