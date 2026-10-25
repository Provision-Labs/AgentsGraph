package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.EdgeDefinition;
import io.provisionlabs.agentsgraph.config.Placeholders;
import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import io.provisionlabs.agentsgraph.config.StepDefinition;
import io.provisionlabs.agentsgraph.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code ${{NAME}}} in processor params (resolved before init) and in step params (resolved per step run). */
class PlaceholdersInEngineTest {

    private static Placeholders with(Map<String, String> values) {
        return new Placeholders(List.of(new Placeholders.Source(Placeholders.ENVIRONMENT, values::get)));
    }

    @Test
    void processorInitGetsResolvedParams() {
        ProcessorRegistry registry = new ProcessorRegistry();
        ProcessorLoader loader = new ProcessorLoader(registry, ProcessorInstantiator.REFLECTIVE,
                with(Map.of("OCR_SERVER", "http://ocr:8191")));

        ProcessorLoader.LoadResult result = loader.load(List.of(new ProcessorDefinition("ocr", "OCR", true,
                TestEchoProcessor.class.getName(),
                Map.of("processUrl", "${{OCR_SERVER}}/processing/{template}", "llm", "${{LLM_SERVER:http://llm:8096}}"))));

        assertThat(result.getFailures()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> initParams = (Map<String, Object>) registry.resolve("ocr")
                .execute(ExecutionContext.newFlow(Map.of(), Map.of()), new StepDefinition("s", "ocr", Map.of()))
                .get("initParams");
        assertThat(initParams).containsEntry("processUrl", "http://ocr:8191/processing/{template}")
                .containsEntry("llm", "http://llm:8096");
    }

    @Test
    void unresolvedPlaceholderFailsOnlyItsRowAndNamesThePlaceholder() {
        ProcessorRegistry registry = new ProcessorRegistry();
        ProcessorLoader loader = new ProcessorLoader(registry, ProcessorInstantiator.REFLECTIVE, with(Map.of()));

        ProcessorLoader.LoadResult result = loader.load(List.of(
                new ProcessorDefinition("ocr", "OCR", true, TestEchoProcessor.class.getName(),
                        Map.of("processUrl", "${{OCR_SERVER}}/processing")),
                new ProcessorDefinition("echo", "Echo", false, TestEchoProcessor.class.getName(), Map.of())));

        assertThat(result.getFailures()).singleElement().satisfies(f -> {
            assertThat(f.getProcessorId()).isEqualTo("ocr");
            assertThat(f.getReason()).contains("OCR_SERVER");
        });
        assertThat(result.getLoaded()).containsOnlyKeys("echo");
    }

    @Test
    void stepParamsAreResolvedWhenTheStepRuns() {
        ProcessorRegistry registry = new ProcessorRegistry();
        registry.register("prompt", (context, step) -> Map.of("model", step.getParams().get("model")));
        EdgeDefinition edge = EdgeDefinition.builder("edge_llm")
                .step(new StepDefinition("step_llm_prompt", "prompt", Map.of("model", "${{LLM_MODEL:qwen2.5-chat}}"),
                        List.of("model"), List.of()))
                .build();

        EdgeResult byDefault = new Edge(edge, registry, with(Map.of()))
                .execute(ExecutionContext.newFlow(Map.of(), Map.of()));
        EdgeResult bySetting = new Edge(edge, registry, with(Map.of("LLM_MODEL", "qwen3-8b")))
                .execute(ExecutionContext.newFlow(Map.of(), Map.of()));

        assertThat(byDefault.getUpdatedContext().getAccumulatedState().get("model")).isEqualTo("qwen2.5-chat");
        assertThat(bySetting.getUpdatedContext().getAccumulatedState().get("model")).isEqualTo("qwen3-8b");
    }
}
