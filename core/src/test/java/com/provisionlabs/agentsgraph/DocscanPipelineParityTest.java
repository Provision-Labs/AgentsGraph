package com.provisionlabs.agentsgraph;

import com.provisionlabs.agentsgraph.config.GraphDefinition;
import com.provisionlabs.agentsgraph.config.ProcessorDefinition;
import com.provisionlabs.agentsgraph.config.StepDefinition;
import com.provisionlabs.agentsgraph.config.json.GraphJsonMapper;
import com.provisionlabs.agentsgraph.context.ExecutionContext;
import com.provisionlabs.agentsgraph.control.GraphClassifier;
import com.provisionlabs.agentsgraph.control.TemplateGraphClassifier;
import com.provisionlabs.agentsgraph.engine.ProcessorLoader;
import com.provisionlabs.agentsgraph.trace.ExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression test showing this framework covers everything the legacy docscan-pipeline
 * did, in AgentsGraph's own native graph JSON format (see {@code examples/graphs/ocr-accounting.json}):
 * a single-node/single-edge graph (a "pipeline" is just a graph shaped this way, not a separate
 * format), two processors loaded from {@code ProcessorDefinition}s by reflection, step output
 * threaded via {@code output_to_next}/{@code output_to_save}, and both sync and async execution.
 */
class DocscanPipelineParityTest {

    // Same document as examples/graphs/ocr-accounting.json - keep the two in sync.
    private static final String GRAPH_JSON = "{\n" +
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
            "        {\"id\": \"step_ocr\", \"processor_id\": \"docscan-ocr\", \"output_to_next\": [\"json\"], \"output_to_save\": [\"tables\", \"json\"]},\n" +
            "        {\"id\": \"step_llm\", \"processor_id\": \"llm-postprocessor\", \"output_to_save\": [\"summary\"]}\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    @Test
    void loadsAndRunsAGraphShapedPipelineEndToEnd() throws Exception {
        AgentsGraphEngine engine = AgentsGraphEngine.inMemory();

        GraphDefinition graph = GraphJsonMapper.fromJson(GRAPH_JSON);
        engine.deployGraph(graph);

        ProcessorLoader.LoadResult loadResult = engine.loadProcessors(List.of(
                new ProcessorDefinition("docscan-ocr", "OCR", true,
                        TestDocscanOcrProcessor.class.getName(), Map.of("processUrl", "http://fake-ocr")),
                new ProcessorDefinition("llm-postprocessor", "LLM", true,
                        TestLlmPostProcessor.class.getName(), Map.of())));
        assertThat(loadResult.getFailures()).isEmpty();
        assertThat(engine.getProcessorHealthMonitor().isHealthy("docscan-ocr")).isTrue();

        GraphClassifier classifier = engine.createTemplateGraphClassifier("ocr-default", "llm-default");
        String pickedGraphId = classifier.classify(Map.of(
                TemplateGraphClassifier.INPUT_HAS_FILE, true,
                TemplateGraphClassifier.INPUT_TEMPLATE_TAG, "accountant"));
        assertThat(pickedGraphId).isEqualTo("ocr-accounting");

        Map<String, Object> input = new HashMap<>();
        input.put("hasFile", true);
        ExecutionContext initialContext = ExecutionContext.newFlow(input, Map.of("tenant_id", "acme"));

        ExecutionContext result = engine.execute(pickedGraphId, initialContext);

        assertThat(result.getAccumulatedState()).containsEntry("summary", "SUMMARY OF {\"a\":1}");
        assertThat(engine.getTraceStore().find(initialContext.getFlowId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.COMPLETED);

        // Both steps belong to the same edge, so their output_to_save contributions are merged
        // into a single OutputSink.save() call for that edge (see RuntimeOrchestrator).
        List<Map<String, Object>> saved = ((com.provisionlabs.agentsgraph.engine.InMemoryOutputSink) engine.getOutputSink())
                .getSaved(initialContext.getFlowId());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0))
                .containsEntry("tables", List.of("t1"))
                .containsEntry("json", "{\"a\":1}")
                .containsEntry("summary", "SUMMARY OF {\"a\":1}");
    }

    @Test
    void runsTheSameGraphAsynchronously() throws Exception {
        AgentsGraphEngine engine = AgentsGraphEngine.inMemory();
        engine.deployGraph(GraphJsonMapper.fromJson(GRAPH_JSON));
        engine.loadProcessors(List.of(
                new ProcessorDefinition("docscan-ocr", "OCR", true, TestDocscanOcrProcessor.class.getName(), Map.of()),
                new ProcessorDefinition("llm-postprocessor", "LLM", true, TestLlmPostProcessor.class.getName(), Map.of())));

        ExecutionContext initialContext = ExecutionContext.newFlow(Map.of("hasFile", true), Map.of());

        ExecutionContext result = engine.executeAsync("ocr-accounting", initialContext).get(5, TimeUnit.SECONDS);

        assertThat(result.getAccumulatedState()).containsEntry("summary", "SUMMARY OF {\"a\":1}");
    }

    @Test
    void graphRoundTripsThroughItsOwnJsonFormat() {
        GraphDefinition original = GraphJsonMapper.fromJson(GRAPH_JSON);
        GraphDefinition reparsed = GraphJsonMapper.fromJson(GraphJsonMapper.toJson(original));

        assertThat(reparsed.getId()).isEqualTo(original.getId());
        assertThat(reparsed.getTemplates()).isEqualTo(original.getTemplates());
        assertThat(reparsed.getEdge("edge_ocr_pipeline").getSteps())
                .hasSameSizeAs(original.getEdge("edge_ocr_pipeline").getSteps());
    }

    public static class TestDocscanOcrProcessor implements com.provisionlabs.agentsgraph.engine.Processor {
        @Override
        public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
            Map<String, Object> out = new HashMap<>();
            out.put("json", "{\"a\":1}");
            out.put("tables", List.of("t1"));
            out.put("textItems", List.of("hello"));
            return out;
        }
    }

    public static class TestLlmPostProcessor implements com.provisionlabs.agentsgraph.engine.Processor {
        @Override
        public Map<String, Object> execute(ExecutionContext context, StepDefinition step) {
            Object json = context.getAccumulatedState().get("json");
            return Map.of("summary", "SUMMARY OF " + json);
        }
    }
}
