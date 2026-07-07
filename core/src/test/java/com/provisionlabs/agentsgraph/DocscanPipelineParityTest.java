package com.provisionlabs.agentsgraph;

import com.provisionlabs.agentsgraph.config.GraphDefinition;
import com.provisionlabs.agentsgraph.config.ProcessorDefinition;
import com.provisionlabs.agentsgraph.config.StepDefinition;
import com.provisionlabs.agentsgraph.config.json.PipelineJsonMapper;
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
 * End-to-end regression test showing this framework reproduces the legacy docscan-pipeline's
 * OCR-accounting example end to end: a pipeline JSON config loaded from {@code plb_pipeline_config},
 * two processors loaded from {@code plb_pipeline_processor}-style definitions by reflection, step
 * output threaded via {@code outputToNext}/{@code outputToSave}, and both sync and async execution.
 */
class DocscanPipelineParityTest {

    private static final String PIPELINE_JSON = "{\n" +
            "  \"id\": \"ocr-accounting\",\n" +
            "  \"name\": \"OCR for accounting\",\n" +
            "  \"templates\": [\"file:accountant\", \"file:default\"],\n" +
            "  \"steps\": [\n" +
            "    {\"processorId\": \"docscan-ocr\", \"outputToNext\": [\"json\"], \"outputToSave\": [\"tables\", \"json\"]},\n" +
            "    {\"processorId\": \"llm-postprocessor\", \"outputToSave\": [\"summary\"]}\n" +
            "  ]\n" +
            "}";

    @Test
    void loadsAndRunsALegacyStylePipelineEndToEnd() throws Exception {
        AgentsGraphEngine engine = AgentsGraphEngine.inMemory();

        GraphDefinition graph = PipelineJsonMapper.toGraphDefinition(PIPELINE_JSON, "v1");
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

        // Both steps belong to the same edge, so their outputToSave contributions are merged into
        // a single OutputSink.save() call for that edge (see RuntimeOrchestrator).
        List<Map<String, Object>> saved = ((com.provisionlabs.agentsgraph.engine.InMemoryOutputSink) engine.getOutputSink())
                .getSaved(initialContext.getFlowId());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0))
                .containsEntry("tables", List.of("t1"))
                .containsEntry("json", "{\"a\":1}")
                .containsEntry("summary", "SUMMARY OF {\"a\":1}");
    }

    @Test
    void runsTheSamePipelineAsynchronously() throws Exception {
        AgentsGraphEngine engine = AgentsGraphEngine.inMemory();
        engine.deployGraph(PipelineJsonMapper.toGraphDefinition(PIPELINE_JSON, "v1"));
        engine.loadProcessors(List.of(
                new ProcessorDefinition("docscan-ocr", "OCR", true, TestDocscanOcrProcessor.class.getName(), Map.of()),
                new ProcessorDefinition("llm-postprocessor", "LLM", true, TestLlmPostProcessor.class.getName(), Map.of())));

        ExecutionContext initialContext = ExecutionContext.newFlow(Map.of("hasFile", true), Map.of());

        ExecutionContext result = engine.executeAsync("ocr-accounting", initialContext).get(5, TimeUnit.SECONDS);

        assertThat(result.getAccumulatedState()).containsEntry("summary", "SUMMARY OF {\"a\":1}");
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
