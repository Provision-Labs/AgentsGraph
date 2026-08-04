package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeOutputThreadingTest {

    @Test
    void outputToNextFiltersWhatTheNextStepSeesAndOutputToSaveCollectsRegardless() {
        ProcessorRegistry registry = new ProcessorRegistry();

        registry.register("step0", (context, step) -> {
            Map<String, Object> out = new HashMap<>();
            out.put("json", "{\"a\":1}");
            out.put("tables", List.of("t1"));
            out.put("textItems", List.of("hello"));
            return out;
        });

        registry.register("step1", (context, step) -> {
            // Only "json" should have been threaded through (outputToNext=["json"] on step0).
            Map<String, Object> out = new HashMap<>();
            out.put("sawJson", context.getAccumulatedState().get("json"));
            out.put("sawTables", context.getAccumulatedState().containsKey("tables"));
            return out;
        });

        EdgeDefinition edge = EdgeDefinition.builder("edge_main")
                .step(new StepDefinition("s0", "step0", Map.of(), List.of("json"), List.of("tables", "textItems", "json")))
                .step(new StepDefinition("s1", "step1", Map.of(), List.of(), List.of()))
                .build();

        ExecutionContext context = ExecutionContext.newFlow(Map.of(), Map.of());
        EdgeResult result = new Edge(edge, registry).execute(context);

        assertThat(result.getUpdatedContext().getAccumulatedState().get("sawJson")).isEqualTo("{\"a\":1}");
        assertThat(result.getUpdatedContext().getAccumulatedState().get("sawTables")).isEqualTo(false);

        assertThat(result.getSavedOutputs())
                .containsEntry("tables", List.of("t1"))
                .containsEntry("textItems", List.of("hello"))
                .containsEntry("json", "{\"a\":1}");
    }

    @Test
    void earlierStepsOutputSurvivesIntermediateStepsThatDoNotReEmitIt() {
        // Форма docscan-бага: step_ocr выдает json, step_ocr_visualize json НЕ переиздает,
        // step_llm_prompt должен все равно его видеть - вклад каждого шага НАКАПЛИВАЕТСЯ
        // (через его output_to_next фильтр), а не замещается выходом последнего шага.
        ProcessorRegistry registry = new ProcessorRegistry();

        registry.register("ocr", (context, step) -> Map.of("json", "{\"doc\":1}"));
        registry.register("visualize", (context, step) -> Map.of("tables", List.of("t1")));
        registry.register("prompt", (context, step) -> {
            Map<String, Object> out = new HashMap<>();
            out.put("sawJson", context.getAccumulatedState().get("json"));
            out.put("sawTables", context.getAccumulatedState().get("tables"));
            return out;
        });

        EdgeDefinition edge = EdgeDefinition.builder("edge_pipeline")
                .step(new StepDefinition("s0", "ocr", Map.of(), List.of("json"), List.of()))
                .step(new StepDefinition("s1", "visualize", Map.of(), List.of("json", "tables"), List.of()))
                .step(new StepDefinition("s2", "prompt", Map.of(), List.of(), List.of()))
                .build();

        EdgeResult result = new Edge(edge, registry).execute(ExecutionContext.newFlow(Map.of(), Map.of()));

        assertThat(result.getUpdatedContext().getAccumulatedState().get("sawJson")).isEqualTo("{\"doc\":1}");
        assertThat(result.getUpdatedContext().getAccumulatedState().get("sawTables")).isEqualTo(List.of("t1"));
    }

    @Test
    void emptyOutputToNextForwardsTheEntireStepOutput() {
        ProcessorRegistry registry = new ProcessorRegistry();
        registry.register("step0", (context, step) -> Map.of("x", 1, "y", 2));
        registry.register("step1", (context, step) -> {
            Map<String, Object> out = new HashMap<>();
            out.put("sawX", context.getAccumulatedState().get("x"));
            out.put("sawY", context.getAccumulatedState().get("y"));
            return out;
        });

        EdgeDefinition edge = EdgeDefinition.builder("edge_main")
                .step(new StepDefinition("s0", "step0", Map.of()))
                .step(new StepDefinition("s1", "step1", Map.of()))
                .build();

        EdgeResult result = new Edge(edge, registry).execute(ExecutionContext.newFlow(Map.of(), Map.of()));

        assertThat(result.getUpdatedContext().getAccumulatedState()).containsEntry("sawX", 1).containsEntry("sawY", 2);
        assertThat(result.getSavedOutputs()).isEmpty();
    }
}
