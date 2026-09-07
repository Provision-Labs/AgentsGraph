package io.provisionlabs.agentsgraph.engine;

import io.provisionlabs.agentsgraph.config.ProcessorDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessorLoaderTest {

    @Test
    void loadsAndRegistersAProcessorByInstanceClass() {
        ProcessorRegistry registry = new ProcessorRegistry();
        ProcessorLoader loader = new ProcessorLoader(registry);

        ProcessorDefinition definition = new ProcessorDefinition(
                "echo", "Echo", true, TestEchoProcessor.class.getName(), Map.of("processUrl", "http://x"));

        ProcessorLoader.LoadResult result = loader.load(List.of(definition));

        assertThat(result.getFailures()).isEmpty();
        assertThat(result.getLoaded()).containsKey("echo");
        assertThat(result.getExternalProcessorIds()).containsExactly("echo");

        // registered into the ProcessorRegistry too, not just returned in the result
        Processor resolved = registry.resolve("echo");
        assertThat(resolved).isInstanceOf(TestEchoProcessor.class);
    }

    @Test
    void reportsAFailureWithoutAbortingTheRestOfTheBatch() {
        ProcessorRegistry registry = new ProcessorRegistry();
        ProcessorLoader loader = new ProcessorLoader(registry);

        ProcessorDefinition broken = new ProcessorDefinition(
                "broken", "Broken", false, "com.example.DoesNotExist", Map.of());
        ProcessorDefinition ok = new ProcessorDefinition(
                "echo", "Echo", false, TestEchoProcessor.class.getName(), Map.of());

        ProcessorLoader.LoadResult result = loader.load(List.of(broken, ok));

        assertThat(result.getFailures()).extracting(ProcessorLoader.LoadFailure::getProcessorId)
                .containsExactly("broken");
        assertThat(result.getLoaded()).containsKey("echo");
    }

    @Test
    void customInstantiatorInjectsDependenciesAfterConstructionAndBeforeInit() {
        ProcessorRegistry registry = new ProcessorRegistry();
        // the shape a Spring wiring takes: reflective construction, then autowireBean(instance)
        ProcessorInstantiator wiring = ProcessorInstantiator.REFLECTIVE.andThen(instance -> {
            ((TestEchoProcessor) instance).setDependency("wired");
            return instance;
        });
        ProcessorLoader loader = new ProcessorLoader(registry, wiring);

        ProcessorLoader.LoadResult result = loader.load(List.of(new ProcessorDefinition(
                "echo", "Echo", false, TestEchoProcessor.class.getName(), Map.of("k", "v"))));

        assertThat(result.getFailures()).isEmpty();
        Map<String, Object> out = registry.resolve("echo").execute(
                io.provisionlabs.agentsgraph.context.ExecutionContext.newFlow(Map.of(), Map.of()),
                new io.provisionlabs.agentsgraph.config.StepDefinition("s1", "echo", Map.of()));
        assertThat(out).containsEntry("dependency", "wired").containsEntry("initParams", Map.of("k", "v"));
    }

    @Test
    void instantiatorFailureIsIsolatedLikeAMissingClass() {
        ProcessorRegistry registry = new ProcessorRegistry();
        ProcessorInstantiator failing = definition -> {
            if ("broken".equals(definition.getId())) {
                throw new IllegalStateException("no bean for " + definition.getInstanceClass());
            }
            return ProcessorInstantiator.REFLECTIVE.instantiate(definition);
        };
        ProcessorLoader loader = new ProcessorLoader(registry, failing);

        ProcessorLoader.LoadResult result = loader.load(List.of(
                new ProcessorDefinition("broken", "Broken", false, TestEchoProcessor.class.getName(), Map.of()),
                new ProcessorDefinition("echo", "Echo", false, TestEchoProcessor.class.getName(), Map.of())));

        assertThat(result.getFailures()).extracting(ProcessorLoader.LoadFailure::getProcessorId).containsExactly("broken");
        assertThat(result.getFailures().get(0).getReason()).contains("no bean for");
        assertThat(result.getLoaded()).containsOnlyKeys("echo");
    }

    @Test
    void healthMonitorTreatsExternalAndInternalProcessorsDifferently() {
        ProcessorRegistry registry = new ProcessorRegistry();
        ProcessorLoader loader = new ProcessorLoader(registry);

        ProcessorDefinition external = new ProcessorDefinition(
                "ext", "Ext", true, TestEchoProcessor.class.getName(), Map.of());
        ProcessorDefinition internal = new ProcessorDefinition(
                "int", "Int", false, TestEchoProcessor.class.getName(), Map.of());

        ProcessorLoader.LoadResult result = loader.load(List.of(external, internal));
        ProcessorHealthMonitor monitor = new ProcessorHealthMonitor(result);

        // TestEchoProcessor doesn't override isHealthy(), so both report healthy by default,
        // but only "ext" is actually consulted via Processor.isHealthy() (is_external = true).
        assertThat(monitor.isHealthy("ext")).isTrue();
        assertThat(monitor.isHealthy("int")).isTrue();
        assertThat(monitor.isHealthy("unknown")).isFalse();
    }
}
