package io.provisionlabs.agentsgraph.config.json;

import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessorJsonMapperTest {

    @Test
    void parsesProcessorDefinitionsWithParamsAsRawJsonString() {
        // Mirrors an agentsgraph_processor row where the "params" TEXT column holds a JSON string.
        String json = "[" +
                "{\"id\":\"docscan-ocr\",\"name\":\"docscan-ocr\",\"is_external\":true," +
                "\"instance_class\":\"org.webvane.pipes.DocScanOcrProcessor\"," +
                "\"params\":\"{\\\"processUrl\\\":\\\"http://10.64.0.40:8109\\\"}\"}" +
                "]";

        List<ProcessorDefinition> definitions = ProcessorJsonMapper.fromJsonArray(json);

        assertThat(definitions).hasSize(1);
        ProcessorDefinition definition = definitions.get(0);
        assertThat(definition.getId()).isEqualTo("docscan-ocr");
        assertThat(definition.isExternal()).isTrue();
        assertThat(definition.getInstanceClass()).isEqualTo("org.webvane.pipes.DocScanOcrProcessor");
        assertThat(definition.getParams()).containsEntry("processUrl", "http://10.64.0.40:8109");
    }

    @Test
    void parsesProcessorDefinitionsWithParamsAsNestedObject() {
        String json = "[{\"id\":\"p1\",\"name\":\"P1\",\"is_external\":false," +
                "\"instance_class\":\"com.example.P1\",\"params\":{\"k\":\"v\"}}]";

        List<ProcessorDefinition> definitions = ProcessorJsonMapper.fromJsonArray(json);

        assertThat(definitions).hasSize(1);
        assertThat(definitions.get(0).getParams()).isEqualTo(Map.of("k", "v"));
        assertThat(definitions.get(0).isExternal()).isFalse();
    }

    @Test
    void roundTripsProcessorDefinitionToJsonAndBack() {
        ProcessorDefinition original = new ProcessorDefinition(
                "p1", "P1", true, "com.example.P1", Map.of("timeoutMs", 3000));

        String json = ProcessorJsonMapper.toJson(original);
        List<ProcessorDefinition> reparsed = ProcessorJsonMapper.fromJsonArray("[" + json + "]");

        assertThat(reparsed).hasSize(1);
        assertThat(reparsed.get(0).getId()).isEqualTo("p1");
        assertThat(reparsed.get(0).isExternal()).isTrue();
        assertThat(reparsed.get(0).getParams()).containsEntry("timeoutMs", 3000);
    }

    @Test
    void roundTripsParamsJsonAlone() {
        Map<String, Object> params = Map.of("processUrl", "http://x", "timeoutMs", 500);
        String json = ProcessorJsonMapper.toParamsJson(params);
        assertThat(ProcessorJsonMapper.fromParamsJson(json)).isEqualTo(params);
        assertThat(ProcessorJsonMapper.fromParamsJson(null)).isEmpty();
        assertThat(ProcessorJsonMapper.fromParamsJson("")).isEmpty();
    }
}
