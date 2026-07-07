package com.provisionlabs.agentsgraph.config.json;

import com.provisionlabs.agentsgraph.config.EdgeDefinition;
import com.provisionlabs.agentsgraph.config.GraphDefinition;
import com.provisionlabs.agentsgraph.config.ProcessorDefinition;
import com.provisionlabs.agentsgraph.config.StepDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineJsonMapperTest {

    // Legacy docscan-pipeline plb_pipeline_config.config JSON (see src/main/resources/db/postgres/pipebot/pipelines.sql
    // of WebVane/services, docscan branch, docscan-pipeline module).
    private static final String OCR_ACCOUNTING_JSON = "{\n" +
            "  \"id\": \"ocr-accounting\",\n" +
            "  \"name\": \"Обработка первички для бухгалтерии\",\n" +
            "  \"templates\": [\"file:accountant\", \"file:default\"],\n" +
            "  \"steps\": [\n" +
            "    {\n" +
            "      \"processorId\": \"docscan-ocr\",\n" +
            "      \"params\": {\"processUrl\": \"http://docscan-service/process/{template}\"},\n" +
            "      \"outputToNext\": [\"json\"],\n" +
            "      \"outputToSave\": [\"tables\", \"textItems\", \"json\"]\n" +
            "    },\n" +
            "    {\n" +
            "      \"processorId\": \"llm-postprocessor\",\n" +
            "      \"params\": {\"processUrl\": \"http://docscan-service/process/{template}\"},\n" +
            "      \"outputToSave\": [\"tables\", \"textItems\", \"json\"]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    @Test
    void parsesLegacyPipelineJsonIntoASingleNodeSingleEdgeGraph() {
        GraphDefinition graph = PipelineJsonMapper.toGraphDefinition(OCR_ACCOUNTING_JSON, "v1");

        assertThat(graph.getId()).isEqualTo("ocr-accounting");
        assertThat(graph.getTemplates()).containsExactly("file:accountant", "file:default");
        assertThat(graph.getEntryNodeId()).isEqualTo(PipelineJsonMapper.ENTRY_NODE_ID);

        EdgeDefinition edge = graph.getEdge(PipelineJsonMapper.MAIN_EDGE_ID);
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
        GraphDefinition original = PipelineJsonMapper.toGraphDefinition(OCR_ACCOUNTING_JSON, "v1");
        String serialized = PipelineJsonMapper.toJson(original);
        GraphDefinition reparsed = PipelineJsonMapper.toGraphDefinition(serialized, "v2");

        assertThat(reparsed.getId()).isEqualTo(original.getId());
        assertThat(reparsed.getTemplates()).isEqualTo(original.getTemplates());
        assertThat(reparsed.getEdge(PipelineJsonMapper.MAIN_EDGE_ID).getSteps())
                .hasSameSizeAs(original.getEdge(PipelineJsonMapper.MAIN_EDGE_ID).getSteps());
    }

    @Test
    void parsesProcessorDefinitionsWithParamsAsRawJsonString() {
        // Mirrors a plb_pipeline_processor row where the "params" TEXT column holds a JSON string.
        String json = "[" +
                "{\"id\":\"docscan_ocr\",\"name\":\"docscan-ocr\",\"is_external\":true," +
                "\"instance_class\":\"org.webvane.pipes.DocScanOcrProcessor\"," +
                "\"params\":\"{\\\"processUrl\\\":\\\"http://10.64.0.40:8109\\\"}\"}" +
                "]";

        List<ProcessorDefinition> definitions = PipelineJsonMapper.toProcessorDefinitions(json);

        assertThat(definitions).hasSize(1);
        ProcessorDefinition definition = definitions.get(0);
        assertThat(definition.getId()).isEqualTo("docscan_ocr");
        assertThat(definition.isExternal()).isTrue();
        assertThat(definition.getInstanceClass()).isEqualTo("org.webvane.pipes.DocScanOcrProcessor");
        assertThat(definition.getParams()).containsEntry("processUrl", "http://10.64.0.40:8109");
    }

    @Test
    void parsesProcessorDefinitionsWithParamsAsNestedObject() {
        String json = "[{\"id\":\"p1\",\"name\":\"P1\",\"is_external\":false," +
                "\"instance_class\":\"com.example.P1\",\"params\":{\"k\":\"v\"}}]";

        List<ProcessorDefinition> definitions = PipelineJsonMapper.toProcessorDefinitions(json);

        assertThat(definitions).hasSize(1);
        assertThat(definitions.get(0).getParams()).isEqualTo(Map.of("k", "v"));
        assertThat(definitions.get(0).isExternal()).isFalse();
    }

    @Test
    void roundTripsProcessorDefinitionToJsonAndBack() {
        ProcessorDefinition original = new ProcessorDefinition(
                "p1", "P1", true, "com.example.P1", Map.of("timeoutMs", 3000));

        String json = PipelineJsonMapper.toJson(original);
        List<ProcessorDefinition> reparsed = PipelineJsonMapper.toProcessorDefinitions("[" + json + "]");

        assertThat(reparsed).hasSize(1);
        assertThat(reparsed.get(0).getId()).isEqualTo("p1");
        assertThat(reparsed.get(0).isExternal()).isTrue();
        assertThat(reparsed.get(0).getParams()).containsEntry("timeoutMs", 3000);
    }
}
